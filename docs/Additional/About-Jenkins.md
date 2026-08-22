In this project, Jenkins is the central orchestrator. It connects GitHub, Docker, AWS, ECR, Terraform, Ansible, and EKS into one CI/CD workflow.

## High-level flow

```text
Developer pushes code to GitHub
              ↓
GitHub webhook triggers Jenkins
              ↓
ShopNow application pipeline — CI
              ↓
Builds three Docker images
              ↓
Pushes images to AWS ECR
              ↓
Triggers infrastructure pipeline — CD
              ↓
Validates AWS/ECR/EKS
              ↓
Optionally runs Terraform and Ansible
              ↓
Deploys images to Kubernetes
              ↓
Checks rollout and application health
```

## How Jenkins connects to AWS

Jenkins has an AWS credential stored under this credential ID:

```text
awsId
```

Both Jenkinsfiles reference it using:

```groovy
AWS_CREDENTIALS_ID = 'awsId'
```

The pipeline loads it using Jenkins’ AWS Credentials Binding:

```groovy
withCredentials([
  [$class: 'AmazonWebServicesCredentialsBinding',
   credentialsId: 'awsId']
]) {
    // AWS commands
}
```

During this block, Jenkins temporarily injects:

```text
AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY
```

If temporary credentials are used, it also supplies:

```text
AWS_SESSION_TOKEN
```

Jenkins masks these values in console output and removes them after the block.

The pipeline first validates authentication:

```bash
aws sts get-caller-identity --region ap-south-1
```

The expected AWS account is:

```text
559272000457
```

## How Jenkins accesses different AWS services

The same temporary credential binding is used for several operations.

### ECR

Jenkins checks or creates repositories and pushes Docker images:

```bash
aws ecr describe-repositories
aws ecr create-repository
aws ecr get-login-password
```

### Terraform backend

Jenkins accesses:

- S3 for Terraform state
- DynamoDB for state locking

### EKS

Jenkins generates a kubeconfig:

```bash
aws eks update-kubeconfig \
  --region ap-south-1 \
  --name shopnow-app-eks
```

The AWS CLI generates temporary EKS authentication tokens. `kubectl` then uses that kubeconfig to communicate with the Kubernetes API.

### Secrets Manager

Jenkins creates or updates the `shopnow/mongo` secret. External Secrets later projects it into Kubernetes.

## How Jenkins connects to Docker

Jenkins runs inside this container:

```text
jenkins
```

A separate Docker daemon runs inside:

```text
jenkins-docker
```

This is a Docker-in-Docker arrangement:

```text
Jenkins container
   │
   │ Docker CLI commands
   ↓
jenkins-docker container
   │
   ├─ builds images
   ├─ stores temporary image layers
   └─ pushes images to ECR
```

The Jenkins container must be configured with a Docker endpoint such as:

```text
DOCKER_HOST=tcp://jenkins-docker:2376
```

or, in some installations:

```text
DOCKER_HOST=tcp://jenkins-docker:2375
```

You can verify it inside Jenkins:

```bash
docker exec -it jenkins sh
echo "$DOCKER_HOST"
docker version
docker info
```

`docker version` should show both:

```text
Client
Server
```

If it shows only the client or cannot connect, Jenkins cannot reach the Docker daemon.

Docker itself does not gather Jenkins pipeline data. Jenkins gathers metadata and passes build instructions to Docker.

## What pipeline data Jenkins gathers

### Git data

After checkout, Jenkins reads:

```bash
git rev-parse HEAD
git rev-parse --short HEAD
git diff --name-only <previous-commit> <current-commit>
```

This provides:

- Current commit SHA
- Short commit SHA
- Previous successful commit
- Changed files
- Repository branch

### Jenkins-generated data

Jenkins supplies:

```text
BUILD_NUMBER
BUILD_URL
JOB_NAME
WORKSPACE
GIT_COMMIT
GIT_PREVIOUS_SUCCESSFUL_COMMIT
```

### Image tag

The application pipeline combines the build number and short commit SHA:

```text
<BUILD_NUMBER>-<SHORT_GIT_SHA>
```

Example:

```text
29-f11a997f
```

This produces immutable image references such as:

```text
559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/frontend:29-f11a997f
559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/admin:29-f11a997f
559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend:29-f11a997f
```

### Infrastructure data

Terraform produces JSON outputs containing:

- EKS cluster name and endpoint
- VPC ID
- Subnet IDs
- Management EC2 public/private IP
- ECR repository information

Ansible inventory is generated from these Terraform outputs.

### Deployment data

The application pipeline sends the following information to the infrastructure pipeline:

```text
AWS_REGION
AWS_ACCOUNT_ID
ECR_REPO_PREFIX
FRONTEND_IMAGE_URI
ADMIN_IMAGE_URI
BACKEND_IMAGE_URI
IMAGE_TAG
DEPLOY_FRONTEND
DEPLOY_ADMIN
DEPLOY_BACKEND
RUN_TERRAFORM
RUN_ANSIBLE_AFTER_APPLY
RUN_DEPLOYMENT
```

This is the contract connecting CI with CD.

# CI pipeline: `shopNow`

The application Jenkinsfile performs the CI portion.

## 1. Checkout

```groovy
stage('Checkout')
```

Jenkins downloads the ShopNow repository and checks out the configured branch/commit.

## 2. Initialize

```groovy
stage('Initialize')
```

Jenkins:

- Finds the repository root
- Reads Git commit information
- Generates the image tag
- Calculates image repository names
- Records which services must be built

The current pipeline intentionally builds all three services on every run:

```text
frontend
admin
backend
```

## 3. Build Docker images in parallel

```groovy
stage('Build in Parallel')
```

The images are built concurrently:

```text
                ┌─ frontend Docker build
Jenkins ────────├─ admin Docker build
                └─ backend Docker build
```

Frontend build:

```bash
docker build \
  --tag shopnow-frontend:<tag> \
  --tag <frontend-ecr-uri> \
  --build-arg PUBLIC_URL=/shopnow \
  --build-arg REACT_APP_API_BASE_URL=/shopnow/api .
```

Admin build:

```bash
docker build \
  --tag shopnow-admin:<tag> \
  --tag <admin-ecr-uri> \
  --build-arg PUBLIC_URL=/shopnow/admin \
  --build-arg REACT_APP_API_BASE_URL=/shopnow/api .
```

Backend build:

```bash
docker build \
  --tag shopnow-backend:<tag> \
  --tag <backend-ecr-uri> .
```

Parallel execution reduces overall build time.

## 4. Authenticate Docker with ECR

Jenkins requests a temporary ECR password:

```bash
aws ecr get-login-password --region ap-south-1 |
docker login \
  --username AWS \
  --password-stdin \
  559272000457.dkr.ecr.ap-south-1.amazonaws.com
```

The ECR password is passed through standard input and is not written into the Jenkinsfile.

## 5. Push images in parallel

```groovy
stage('Push to ECR')
```

Jenkins pushes:

```bash
docker push <frontend-image-uri>
docker push <admin-image-uri>
docker push <backend-image-uri>
```

Again, all three pushes run in parallel.

## 6. Trigger the CD pipeline

```groovy
stage('Deployment Orchestration')
```

The application pipeline triggers:

```text
herovired-infra-services
```

It passes the exact image URIs and image tag to the infrastructure job:

```groovy
build job: env.INFRA_JOB_NAME,
      wait: true,
      propagate: true,
      parameters: [...]
```

Important behavior:

- `wait: true` means the application pipeline waits for deployment.
- `propagate: true` means a failed deployment also fails the application pipeline.
- The infrastructure job receives exact images, so it does not guess which version to deploy.

For a normal application release it sends:

```text
RUN_TERRAFORM=false
RUN_ANSIBLE_AFTER_APPLY=false
RUN_DEPLOYMENT=true
```

Therefore, normal code changes do not unnecessarily recreate infrastructure.

# CD pipeline: `herovired-infra`

The infrastructure Jenkinsfile performs the deployment.

## 1. Checkout and initialization

Jenkins checks out `herovired-infra` and reads the parameters received from the application pipeline.

It validates that explicit image URIs exist for the selected services.

## 2. Ensure required tools

Jenkins verifies or installs:

- AWS CLI
- Terraform
- kubectl
- Helm

## 3. Preflight checks

Where available, the pipeline performs:

- Terraform validation
- Ansible syntax validation
- Kubernetes manifest validation

## 4. Validate AWS identity

```bash
aws sts get-caller-identity
```

This prevents the pipeline from proceeding with missing or incorrect AWS credentials.

## 5. Verify images in ECR

Before deployment, Jenkins runs:

```bash
aws ecr describe-images \
  --repository-name <repository> \
  --image-ids imageTag=<tag>
```

This confirms that the CI pipeline successfully published each requested image.

## 6. Terraform—when requested

For infrastructure changes:

```text
RUN_TERRAFORM=true
```

Jenkins runs:

```bash
terraform init
terraform validate
terraform plan -out=tfplan
terraform apply tfplan
terraform output -json
```

For normal application releases this stage is disabled.

## 7. Secrets and External Secrets—when requested

The infrastructure pipeline can:

- Create/update the AWS Secrets Manager value
- Apply `SecretStore`
- Apply `ExternalSecret`
- Wait until `mongo-secret` becomes ready

Secret values are not supposed to be printed.

## 8. Ansible—when requested

For a new or changed management EC2 host:

```text
RUN_ANSIBLE_AFTER_APPLY=true
```

Jenkins:

- Generates inventory from Terraform outputs
- Loads the SSH private key from Jenkins Credentials
- Configures the host
- Validates Docker, kubectl, and AWS CLI

For a normal application deployment, this is disabled.

## 9. Deploy to EKS

Jenkins replaces image placeholders in the Kubernetes deployment manifests:

```text
REPLACE_FRONTEND_IMAGE
REPLACE_ADMIN_IMAGE
REPLACE_BACKEND_IMAGE
```

It then sends the rendered manifests directly to Kubernetes:

```bash
sed ... deployment.yaml | kubectl apply -f -
```

The deployments now reference the precise images produced by CI.

## 10. Rollout verification

The pipeline waits for each deployment to become ready:

```bash
kubectl rollout status deployment/frontend
kubectl rollout status deployment/admin
kubectl rollout status deployment/backend
```

If a pod cannot start, an image cannot be pulled, or a readiness probe fails, the CD job fails. Because `propagate: true` is configured, the upstream application job fails too.

## 11. Summary and diagnostics

Jenkins records:

- Image URIs
- Image tag
- Components deployed
- Terraform/Ansible execution status
- Deployment outcome
- Text and log artifacts

On failure, it collects information such as:

```bash
kubectl get deployments,pods
kubectl get events
kubectl describe deployment
kubectl logs
helm status
```

## Complete internal CI/CD sequence

```text
GitHub push
    ↓
Jenkins webhook
    ↓
shopNow job
    ├─ Checkout source
    ├─ Read Git SHA/build number
    ├─ Generate immutable image tag
    ├─ Build frontend/admin/backend images
    ├─ Authenticate with AWS
    ├─ Login Docker to ECR
    └─ Push images
           ↓ exact image URIs
herovired-infra-services job
    ├─ Checkout infrastructure code
    ├─ Validate AWS identity
    ├─ Verify images in ECR
    ├─ Optionally run Terraform
    ├─ Optionally run Ansible
    ├─ Authenticate with EKS
    ├─ Apply Kubernetes manifests
    ├─ Wait for rollouts
    └─ Report success or diagnostics
```

## Important current gap

The application pipeline currently builds images but does not show dedicated stages for:

- Unit tests
- Integration tests
- Dependency vulnerability scanning
- Static code analysis
- Container-image scanning before push
- Quality gates

A stronger CI sequence should be:

```text
Checkout
  → install dependencies
  → lint
  → unit tests
  → application build
  → security scan
  → Docker build
  → image scan
  → ECR push
  → deployment
```

The current implementation demonstrates automated build and deployment, but adding these quality stages would make it a more complete production-grade CI/CD pipeline.


---------------------------------------------------------------

A Jenkins agent is the machine or container that executes pipeline commands. The Jenkins controller manages jobs and the UI, while the agent performs the actual work.

## Architecture

```text
Developer pushes to GitHub
          ↓
Jenkins controller
  - receives webhook
  - reads Jenkinsfile
  - schedules stages
  - stores logs/status
          ↓
Jenkins agent
  - checks out code
  - runs Docker builds
  - executes AWS CLI
  - runs Terraform
  - runs Ansible
  - runs kubectl and Helm
          ↓
AWS / ECR / EKS
```

## Controller versus agent

| Jenkins controller | Jenkins agent |
|---|---|
| Hosts Jenkins UI | Executes pipeline commands |
| Stores job configuration | Checks out repositories |
| Receives GitHub webhooks | Builds Docker images |
| Schedules pipeline work | Pushes images to ECR |
| Manages credentials | Runs Terraform and Ansible |
| Collects logs and results | Deploys to EKS |

Your pipeline currently declares:

```groovy
pipeline {
  agent any
}
```

This means Jenkins can execute the pipeline on any available node matching the job requirements. If no separate agent is configured, Jenkins may run it directly inside the controller container.

## Agent connection process

A separate agent normally connects to the Jenkins controller using:

```text
Controller address: http://jenkins:8080
Agent port: 50000
```

Your Docker output showed:

```text
8080  → Jenkins web interface
50000 → Jenkins inbound-agent communication
```

The agent authenticates using:

- Jenkins agent name
- Agent secret
- Controller URL
- Work directory

Conceptually:

```bash
java -jar agent.jar \
  -url http://jenkins:8080 \
  -secret <agent-secret> \
  -name docker-agent \
  -workDir /home/jenkins
```

The agent opens an outbound connection to the controller. The controller then sends pipeline tasks over that connection.

## Your custom Jenkins agent image

The infrastructure repository contains:

```text
docker/jenkins-agent/Dockerfile
```

This image prepares an execution environment containing tools such as:

- AWS CLI
- kubectl
- Helm
- Terraform-related dependencies
- Docker tooling
- Jenkins agent runtime

It creates a `jenkins` user and uses:

```text
/home/jenkins
```

as its working directory.

GitHub Actions builds this agent image and pushes it to ECR:

```text
559272000457.dkr.ecr.ap-south-1.amazonaws.com/jenkins-agent
```

However, building and publishing the image does not automatically register an agent with Jenkins. Jenkins must still be configured to start a container from that image.

## What happens when a build starts

### 1. Controller receives the build

A GitHub webhook or manual action starts the Jenkins job.

### 2. Controller selects an agent

Because the Jenkinsfile uses `agent any`, Jenkins selects any online executor.

For stronger control, assign a label:

```groovy
pipeline {
  agent {
    label 'shopnow-agent'
  }
}
```

### 3. Jenkins creates a workspace

The agent gets a directory such as:

```text
/var/jenkins_home/workspace/shopnow-service-dev
```

or, on a remote agent:

```text
/home/jenkins/workspace/shopnow-service-dev
```

### 4. Agent checks out Git

The agent executes:

```bash
git clone
git fetch
git checkout
```

The application and infrastructure files are placed in its workspace.

### 5. Controller injects credentials

When the Jenkinsfile enters:

```groovy
withCredentials(...)
```

the controller temporarily supplies credentials to the agent as environment variables or temporary files.

Examples:

```text
AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY
ANSIBLE_SSH_PRIVATE_KEY
```

The agent uses them only within that block.

### 6. Agent executes commands

For the application pipeline:

```bash
docker build
aws ecr get-login-password
docker push
```

For the infrastructure pipeline:

```bash
terraform init
terraform plan
terraform apply
ansible-playbook
aws eks update-kubeconfig
kubectl apply
helm status
```

### 7. Agent returns results

The agent sends back:

- Console output
- Exit codes
- Stage status
- Archived logs
- Test results
- Build metadata

The controller displays this information in the Jenkins UI.

### 8. Workspace cleanup

Your infrastructure pipeline runs:

```groovy
cleanWs()
```

The generated inventory, Terraform output file, temporary kubeconfig, and other workspace files are removed.

## How the agent uses Docker

There are two common configurations.

### Docker socket

```text
Jenkins agent
      ↓ /var/run/docker.sock
Host Docker daemon
```

The agent container mounts:

```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

The Docker CLI inside the agent controls the host Docker daemon.

### Docker-in-Docker

Your environment has a `jenkins-docker` container, which suggests:

```text
Jenkins/agent container
        ↓ DOCKER_HOST
jenkins-docker daemon
        ↓
builds ShopNow images
```

The connection usually uses:

```text
DOCKER_HOST=tcp://jenkins-docker:2376
```

Check from the container executing the pipeline:

```bash
echo "$DOCKER_HOST"
docker version
docker info
```

`docker version` must display both the client and server.

## How to confirm where the job runs

Add a temporary stage:

```groovy
stage('Agent Information') {
  steps {
    sh '''
      echo "Node: $NODE_NAME"
      echo "Workspace: $WORKSPACE"
      whoami
      hostname
      docker version || true
      aws --version
      terraform version
      kubectl version --client
      helm version
    '''
  }
}
```

You can also check:

**Jenkins → Manage Jenkins → Nodes**

If only **Built-In Node** is listed, the pipelines are running on the controller. If another node is listed and online, Jenkins may use that agent.

## Recommended setup

Do not run heavy builds on the Jenkins controller. Configure a dedicated agent:

```groovy
pipeline {
  agent {
    label 'shopnow-docker-agent'
  }
}
```

The dedicated agent should have:

- Docker connectivity
- AWS CLI
- Terraform
- Ansible
- kubectl
- Helm
- Git
- Appropriate CPU and memory
- No permanent AWS keys inside the image

Credentials should continue to come from Jenkins Credentials or, preferably, an attached IAM role.