# PR and Pipeline Troubleshooting

This document records the challenges encountered while preparing the pull
request and fixing the related Jenkins and GitHub Actions failures. It also
collects the local commands used to diagnose, correct, validate, and publish
the changes. Never paste credentials into terminals as command arguments,
documentation, commits, PR comments, or chat messages.

## Issues encountered and resolutions

### Jenkins stopped during AWS validation

The infrastructure pipeline stopped while running:

```bash
aws sts get-caller-identity --region ap-south-1
```

The log then reported that the build resumed after a Jenkins restart, followed
by a durable-task heartbeat error and exit code `-1`. This was a Jenkins process
interruption, not an AWS authentication failure. Post-failure diagnostics were
still able to connect to AWS and EKS, and the Kubernetes workloads were healthy.

Resolution:

- restart the interrupted pipeline as a new build;
- check the Jenkins container restart count, exit status, and logs;
- add bounded AWS CLI connection/read timeouts where appropriate;
- investigate host restarts or out-of-memory events if the problem repeats.

### GitHub Actions did not receive an AWS region

The image workflows used an unset repository secret:

```yaml
aws-region: ${{ secrets.AWS_REGION }}
```

GitHub therefore passed an empty value and the action failed with `Input
required and not supplied: aws-region`. Both workflows also used the obsolete
`aws-actions/configure-aws-credentials@v2`, which generated Node.js and AWS SDK
deprecation warnings.

Resolution applied in commit `8c2a84c`:

- define the non-sensitive region as job environment variable
  `AWS_REGION: ap-south-1`;
- use `${{ env.AWS_REGION }}` for the action input;
- use `$AWS_REGION` in shell commands;
- upgrade `aws-actions/configure-aws-credentials` to `v6.2.3`.

### A rerun continued to use the old workflow

GitHub's **Re-run failed jobs** operation uses the workflow definition from the
original commit. The rerun consequently continued to show `@v2` even after the
fix had been pushed.

Resolution: start a new workflow run with **Actions → workflow → Run workflow**
and select `feature/infra-capstone-project-v1`.

### AWS repository secrets were unavailable

Debug output showed `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` resolving to
`null`. Repository secrets must exist before an AWS-authenticated workflow can
push images to ECR. Secrets are also intentionally unavailable to untrusted
fork-based pull requests.

Resolution: add the credentials as GitHub Actions repository secrets and run
the workflow from a trusted repository branch. Prefer GitHub OIDC with a
least-privilege IAM role in future work so long-lived access keys are not needed.

### Credentials were used as secret names

While using GitHub CLI, credential values were accidentally supplied where the
secret **name** was expected. Any credential exposed in terminal history, logs,
chat, or another public location must be treated as compromised.

Resolution:

1. deactivate and delete the exposed AWS access key;
2. create and configure a new access key;
3. delete the incorrectly named GitHub secret;
4. create secrets using the fixed names shown below;
5. never reuse or commit the exposed credentials.

## Local PR workflow

Check the current repository and branch before editing:

```bash
cd ~/herovired-infra
git status --short
git branch --show-current
git remote -v
git fetch origin
```

Create or switch to the feature branch:

```bash
git switch feature/infra-capstone-project-v1
# For a new branch instead:
# git switch -c feature/<short-description>
```

Review changes and validate whitespace:

```bash
git status --short
git diff
git diff --check
git diff --stat
```

Commit and push the selected files:

```bash
git add .github/workflows/build-jenkins-agent.yml \
  .github/workflows/build-jenkinsfile-runner.yml
git commit -m "fix: configure AWS region in image workflows"
git push -u origin feature/infra-capstone-project-v1
```

Create or inspect the PR with GitHub CLI:

```bash
gh auth status
gh pr create \
  --repo harishmsgit/herovired-infra \
  --base main \
  --head feature/infra-capstone-project-v1 \
  --title "Fix AWS configuration in image workflows" \
  --body "Configures the AWS region and updates the credentials action."
gh pr view --web
gh pr checks
```

If the branch is behind the target branch, update it without discarding local
work:

```bash
git fetch origin
git rebase origin/main
# Resolve each reported conflict, then:
git add <resolved-files>
git rebase --continue
git push --force-with-lease
```

Use `--force-with-lease` only after reviewing the rebased history and only on
your feature branch. Do not force-push a shared protected branch.

## GitHub CLI and repository secrets

Install and authenticate GitHub CLI on Ubuntu/WSL when it is not available:

```bash
sudo apt update
sudo apt install gh
gh auth login
gh auth status
```

After rotating any exposed key and updating the local AWS profile, send values
through standard input so they are not included in the command arguments:

```bash
aws configure

aws configure get aws_access_key_id |
  gh secret set AWS_ACCESS_KEY_ID --repo harishmsgit/herovired-infra

aws configure get aws_secret_access_key |
  gh secret set AWS_SECRET_ACCESS_KEY --repo harishmsgit/herovired-infra

gh secret set AWS_ACCOUNT_ID \
  --repo harishmsgit/herovired-infra \
  --body "559272000457"
```

Verify names and timestamps only; GitHub never returns secret values:

```bash
gh secret list --repo harishmsgit/herovired-infra
```

Delete an incorrectly named secret without placing its sensitive-looking name
in documentation:

```bash
gh secret list --repo harishmsgit/herovired-infra
gh secret delete '<incorrect-secret-name>' \
  --repo harishmsgit/herovired-infra
```

## AWS validation

Confirm the active local identity and expected account:

```bash
aws sts get-caller-identity --region ap-south-1
aws configure list
```

Test the same operation with bounded network timeouts:

```bash
aws sts get-caller-identity \
  --region ap-south-1 \
  --cli-connect-timeout 10 \
  --cli-read-timeout 20 \
  --no-cli-pager
```

Confirm ECR access without modifying repositories:

```bash
aws ecr describe-repositories \
  --region ap-south-1 \
  --no-cli-pager
```

## Jenkins diagnostics

List the local containers and check whether Jenkins restarted:

```bash
docker ps
docker inspect jenkins \
  --format 'RestartCount={{.RestartCount}} StartedAt={{.State.StartedAt}} OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}}'
docker logs --since 2h jenkins
```

Enter the Jenkins container when container-level diagnosis is required:

```bash
docker exec -it jenkins sh
```

An interactive container shell may report `Unable to locate credentials`
because Jenkins injects credentials only inside the pipeline's
`withCredentials` block. Validate the configured Jenkins credential through a
temporary masked pipeline step rather than copying secrets into the container.

## EKS and deployment verification

Configure cluster access and inspect workload health:

```bash
aws eks update-kubeconfig \
  --region ap-south-1 \
  --name shopnow-app-eks
kubectl get nodes -o wide
kubectl get deployment,pods -n shopnow-ns -o wide
kubectl get events -n shopnow-ns --sort-by=.lastTimestamp
```

Check External Secrets without printing secret data:

```bash
helm status external-secrets -n shopnow-ns
kubectl get secretstore,externalsecret -n shopnow-ns
kubectl describe secretstore aws-secret-store -n shopnow-ns
kubectl logs deployment/external-secrets \
  -n shopnow-ns --all-containers --tail=200
```

## Final verification checklist

- `git diff --check` succeeds.
- The new workflow run shows `configure-aws-credentials@v6.2.3`.
- `aws-region` resolves to `ap-south-1`.
- Required repository secret names exist and their values are not logged.
- AWS identity belongs to account `559272000457`.
- The ECR image build and push complete successfully.
- Jenkins and Kubernetes workloads remain healthy.
- No credential, kubeconfig, Terraform state, or generated secret is committed.
