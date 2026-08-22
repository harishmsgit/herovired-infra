Terraform is the main infrastructure-provisioning tool in this project. It creates and maintains the AWS and foundational Kubernetes resources required to run ShopNow.

## Overall flow

```text
Jenkins
   ↓ supplies AWS credentials and variables
Terraform init
   ↓ connects to remote state
Terraform plan
   ↓ compares code, state, and AWS
Terraform apply
   ↓ creates or updates infrastructure
Terraform outputs
   ↓ supplies values to Ansible and Jenkins
Ansible
   ↓ configures management EC2
Jenkins/Kubernetes
   ↓ deploys ShopNow workloads
```

## What Terraform creates

### 1. Networking

Terraform creates:

- VPC
- Internet Gateway
- Public subnets across available Availability Zones
- Public route table
- Default internet route
- Route-table associations

The public subnets are tagged so AWS load balancers created by Kubernetes can use them.

### 2. Security groups

Terraform creates separate security groups for:

- EKS control-plane communication
- Management EC2 access

The management security group currently permits:

- SSH on port `22` from `allowed_ssh_cidr`
- HTTP on port `80`
- HTTPS on port `443`
- Outbound traffic

For better security, `allowed_ssh_cidr` should be restricted to the administrator’s public IP rather than `0.0.0.0/0`.

### 3. EKS cluster

Terraform creates the ShopNow EKS control plane:

```hcl
resource "aws_eks_cluster" "main"
```

It configures:

- Cluster name
- IAM role
- Public subnets
- Cluster security group
- Public Kubernetes API endpoint
- AWS tags

The cluster API is currently publicly reachable, but AWS IAM and Kubernetes RBAC still control authorization.

### 4. EKS worker nodes

Terraform manages two EKS node groups:

```text
Main node group
Workload node group
```

The workload node group:

- Uses on-demand EC2 instances
- Has configurable minimum, desired, and maximum capacity
- Uses rolling updates with `max_unavailable = 1`
- Is labelled `workload=shopnow`

The scaling values come from Terraform variables.

### 5. IAM roles and permissions

Terraform creates IAM roles for:

- EKS control plane
- EKS worker nodes
- Management EC2
- External Secrets Operator

It attaches permissions for:

- EKS cluster operation
- EKS worker-node participation
- Container networking
- ECR access
- Systems Manager
- EKS cluster discovery
- Reading ShopNow secrets from AWS Secrets Manager

### 6. EKS OIDC and External Secrets access

Terraform creates an EKS OIDC identity provider and an IAM role for External Secrets.

```text
External Secrets service account
              ↓ assumes IAM role through OIDC/IRSA
AWS Secrets Manager
              ↓ returns shopnow/* secrets
Kubernetes Secret
```

This avoids storing permanent AWS credentials inside Kubernetes.

The External Secrets role can only read secrets matching:

```text
arn:aws:secretsmanager:ap-south-1:<account>:secret:shopnow/*
```

### 7. External Secrets Operator

Terraform installs External Secrets through Helm:

```hcl
resource "helm_release" "external_secrets"
```

It configures:

- Namespace: `shopnow-ns`
- Dedicated service account
- IAM role annotation
- Namespace-scoped RBAC
- Atomic Helm deployment
- Ten-minute deployment timeout

Terraform manages the Helm installation, while Jenkins later creates and verifies the application `SecretStore` and `ExternalSecret` resources.

### 8. NGINX Ingress Controller

Terraform installs the NGINX Ingress Controller through Helm:

```hcl
resource "helm_release" "ingress_nginx"
```

It creates a Kubernetes `LoadBalancer` service, which results in an AWS load balancer serving as the public entry point for ShopNow.

### 9. Management EC2 instance

Terraform creates an Amazon Linux 2023 management instance.

The initial EC2 user-data script installs:

- Docker
- Git
- curl
- unzip
- Python
- pip

It also:

- starts and enables Docker;
- adds `ec2-user` to the Docker group;
- attaches the management IAM instance profile;
- assigns a public IP.

After creation, Ansible performs the more complete configuration.

### 10. Kubernetes access for the management host

Terraform updates the EKS `aws-auth` ConfigMap to map the management EC2 IAM role to a Kubernetes group:

```text
shopnow-management
```

It then creates a namespace-scoped Kubernetes Role and RoleBinding.

The management host can:

- View pods, logs, services, deployments and ingresses
- Create pod port-forward sessions

It cannot:

- Modify deployments
- Read Kubernetes Secrets
- Administer the entire cluster

This follows least-privilege access.

### 11. Existing ECR repositories

Terraform reads the existing repositories for:

- Frontend
- Admin
- Backend

It currently uses data sources:

```hcl
data "aws_ecr_repository" "app"
```

This means Terraform expects those repositories to already exist; it does not create them.

## How Terraform stores state

Terraform needs a state file to remember which AWS resources it owns.

This project uses:

- S3 for the remote Terraform state
- DynamoDB for state locking
- Terraform workspaces for environments such as `dev`

The Jenkins pipeline initializes it with:

```bash
terraform init -reconfigure \
  -backend-config="bucket=${TF_STATE_BUCKET}" \
  -backend-config="key=terraform/terraform.tfstate" \
  -backend-config="region=${TF_STATE_BUCKET_REGION}" \
  -backend-config="dynamodb_table=${LOCK_TABLE}"
```

The remote state allows Jenkins and authorized team members to use the same infrastructure record. Locking prevents two Terraform operations from changing the infrastructure simultaneously.

## Terraform execution in Jenkins

### Initialize

```bash
terraform init -reconfigure
```

Downloads providers and connects to the remote backend.

### Select environment

```bash
terraform workspace select -or-create dev
```

Selects the `dev` state workspace.

### Validate

```bash
terraform validate
```

Checks Terraform configuration syntax and internal consistency.

### Plan

```bash
terraform plan \
  -var="aws_region=${AWS_REGION}" \
  -var="cluster_name=${EKS_CLUSTER_NAME}" \
  -var="ecr_repo_prefix=${ECR_REPO_PREFIX}" \
  -parallelism=2 \
  -out=tfplan
```

Terraform compares:

```text
Terraform configuration
        +
Remote state
        +
Actual AWS resources
```

It creates a saved plan describing what will be added, changed, replaced, or removed.

### Apply

```bash
terraform apply -auto-approve -parallelism=2 tfplan
```

Terraform applies exactly the saved plan.

### Produce outputs

```bash
terraform output -json > "$TF_OUTPUT_FILE"
```

Terraform exports:

- VPC ID
- Public subnet IDs
- EKS cluster name
- EKS endpoint
- Management EC2 public and private IP
- ECR repository names and URLs

Jenkins and Ansible consume these outputs.

## Existing-resource recovery

The Jenkins pipeline contains recovery logic for resources created by earlier or partially completed runs.

It can import existing:

- Public subnets
- Route-table associations
- EKS cluster
- External Secrets Helm release

Example:

```bash
terraform import aws_eks_cluster.main "$EKS_CLUSTER_NAME"
```

Importing tells Terraform:

> This resource already exists. Add it to Terraform state instead of trying to create another one.

## Responsibility separation

| Component | Responsibility |
|---|---|
| Terraform | AWS infrastructure and foundational cluster services |
| Ansible | Management EC2 software configuration |
| Jenkins | Pipeline orchestration |
| Helm | External Secrets and ingress installation |
| Kubernetes manifests | ShopNow workloads and services |
| External Secrets | AWS-to-Kubernetes secret synchronization |

In simple terms: Terraform builds the platform, Ansible prepares the management server, and Jenkins deploys the application onto the platform.