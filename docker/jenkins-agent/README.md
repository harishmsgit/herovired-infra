Jenkins agent image with kubectl / helm / aws

Build and push

```bash
# build (replace <registry> with your registry, e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com)
docker build -t <registry>/jenkins-agent:latest -f docker/jenkins-agent/Dockerfile .

# push (example for ECR)
# aws ecr get-login-password --region <region> | docker login --username AWS --password-stdin <registry>
# docker push <registry>/jenkins-agent:latest
```

CI (GitHub Actions)

This repository includes a GitHub Actions workflow that builds and pushes the image to GitHub Container Registry (GHCR) on changes to `docker/jenkins-agent/`.

To enable pushing to GHCR, ensure `GITHUB_TOKEN` has `packages: write` (default for workflows in the same repo). The workflow is at `.github/workflows/build-jenkins-agent.yml`.

ECR support

The workflow is now configured to push the built image to Amazon ECR. To enable this you must add the following repository secrets:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_REGION` (e.g. `ap-south-1`)
- `AWS_ACCOUNT_ID` (your ECR account id)

The workflow will build and push to `<AWS_ACCOUNT_ID>.dkr.ecr.<AWS_REGION>.amazonaws.com/jenkins-agent:latest` and `...:${{ github.sha }}`.

Usage in Jenkins

- If using the Kubernetes plugin, set the Pod template container image to `<registry>/jenkins-agent:latest`.

- If using a Docker agent block in a scripted/declarative pipeline, you can reference the image (ensure the agent can run Docker):

```groovy
pipeline {
  agent {
    docker {
      image '<registry>/jenkins-agent:latest'
      args '-u root:root'
    }
  }
  stages { /* ... */ }
}
```

Notes

- The image installs `aws` (AWS CLI v2), `kubectl` and `helm`. It is intended as a base agent; adjust users/UIDs and add extra tools as needed.
- Prefer running the agent with appropriate IAM credentials (IRSA, instance profile, or mounted AWS creds) rather than baking credentials into the image.
