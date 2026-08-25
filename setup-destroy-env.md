# ShopNow AWS Setup and Teardown Runbook

This runbook rebuilds or removes the AWS environment used by the `shopNow` application and the `herovired-infra` repository. Run all infrastructure commands from `herovired-infra/` unless stated otherwise.

## Automation entry points

| File | Purpose | Safety behavior |
|---|---|---|
| `scripts/setup_env.sh` | Backend/ECR checks, Terraform init, validation and setup plan | Plans by default; `--apply` is required to apply |
| `scripts/destroy_env.sh` | Terraform destroy plan and non-backend cleanup | Plans by default; `--apply` plus `CONFIRM_SHOPNOW_DESTROY=dev` is required |
| `scripts/verify_destroy.sh` | Read-only live AWS cleanup checks | Does not mutate AWS |
| `scripts/ensure_tf_state_backend.sh` | Creates/checks the S3 state bucket and DynamoDB lock table | Used before Terraform init |
| `scripts/create_aws_secret.sh` | Creates/updates `shopnow/mongo` from required environment variables | Does not contain a password |
| `scripts/create_iam_role_for_external_secrets.sh` | IRSA/External Secrets role helper | Review account, cluster and namespace first |
| `scripts/build-and-push.sh` | Builds and pushes application images to ECR | Requires authenticated ECR access |
| `Jenkinsfile` | Infrastructure CI/CD orchestration | Parameter-gated Terraform and deployment stages |

Quick setup plan:

```bash
bash scripts/setup_env.sh
# Review terraform/tfplan
bash scripts/setup_env.sh --apply
```

Quick destroy plan:

```bash
bash scripts/destroy_env.sh
# Review terraform/destroy.tfplan
export CONFIRM_SHOPNOW_DESTROY=dev
bash scripts/destroy_env.sh --apply
```

The destroy helper intentionally does not delete the backend. Follow the backend-last section manually after `terraform state list` returns no resources.

## Project file map

### Infrastructure and CI/CD

- `terraform/backend.tf`: S3 backend declaration.
- `terraform/versions.tf`: Terraform/provider version constraints.
- `terraform/provider.tf`: AWS, Kubernetes, Helm and TLS providers.
- `terraform/variables.tf` and `terraform/terraform.tfvars.example`: supported inputs and safe example values.
- `terraform/main.tf`: VPC, EC2, EKS, node groups, IAM, IRSA, Helm and RBAC resources.
- `terraform/outputs.tf`: cluster, VPC, management host and ECR discovery outputs.
- `Jenkinsfile` and `jenkins/common.groovy`: validated infrastructure pipeline and shared functions.
- `ansible/playbooks/configure-management.yml`: management-host configuration.
- `ansible/playbooks/validate-management.yml`: post-configuration checks.

### Application and secrets manifests

- `kubernetes/k8s-manifests/namespace/namespace.yaml`: `shopnow-ns`.
- `kubernetes/k8s-manifests/database/`: MongoDB, SecretStore and ExternalSecret.
- `kubernetes/k8s-manifests/backend/`: backend Deployment and Service.
- `kubernetes/k8s-manifests/frontend/`: frontend Deployment and Service.
- `kubernetes/k8s-manifests/admin/`: admin Deployment and Service.
- `kubernetes/k8s-manifests/ingress/ingress-shopnow.yaml`: NGINX routes for user, admin and API traffic.
- `kubernetes/external-secrets/external-secrets-sa.yaml`: External Secrets service account/IRSA annotation.
- `kubernetes/monitoring/`: ServiceMonitor, Prometheus rules and Grafana dashboard.

### Supporting documentation and evidence

- `docs/ARCHITECTURE.md`: detailed component and traffic architecture.
- `docs/COMMANDS.md`: AWS, EKS, MongoDB, monitoring and log commands.
- `docs/DEPLOYMENT_STRATEGY.md`: CI/CD and rollout strategy.
- `docs/PR_AND_PIPELINE_TROUBLESHOOTING.md`: GitHub/Jenkins troubleshooting.
- `screenshots/enterprise-aws-architecture.png`: enterprise AWS architecture diagram.
- `screenshots/aws-eks.png`, `aws-ecr.png`, `kubernetes-monitoring.png`: deployment evidence.

## Environment definition

| Setting | Value |
|---|---|
| AWS region | `ap-south-1` |
| Terraform workspace | `dev` |
| Terraform state bucket | `harish-pc-s3-bucket` |
| Terraform state key | `terraform/terraform.tfstate` |
| Workspace state object | `env:/dev/terraform/terraform.tfstate` |
| Terraform lock table | `shopnow-terraform-locks` |
| Environment | `dev` |
| VPC CIDR | `10.20.0.0/16` |
| Public subnet A | `10.20.1.0/24` |
| Public subnet B | `10.20.2.0/24` |
| EKS cluster | `shopnow-app-eks` |
| Application namespace | `shopnow-ns` |
| Monitoring namespace | `monitor-ns` |
| ECR repositories | `shopnow-dev/frontend`, `shopnow-dev/admin`, `shopnow-dev/backend` |
| Secrets Manager secret | `shopnow/mongo` |
| EC2 key pair | `shopnow-key-pair` |

## What Terraform manages

- VPC, Internet Gateway, public subnets, route table and associations.
- EKS and management-host security groups.
- EKS cluster and two managed node groups.
- EKS cluster, node, management-host, and External Secrets IAM roles/policies.
- EKS OIDC provider and IRSA trust.
- EC2 management host and instance profile.
- ingress-nginx and External Secrets Helm releases.
- EKS `aws-auth`, management RBAC Role and RoleBinding.

## Resources outside Terraform state

These need separate setup and cleanup:

- S3 Terraform state bucket.
- DynamoDB Terraform lock table.
- Three ECR repositories and their images.
- Secrets Manager secret `shopnow/mongo`.
- EC2 key pair `shopnow-key-pair`.
- Application Kubernetes manifests applied by Jenkins/kubectl.
- Monitoring manifests when deployed separately.

## Security rules

- Never store AWS keys, MongoDB passwords, private keys, kubeconfig, state files, plans, or decoded secrets in Git.
- Generate a new MongoDB password for every rebuild.
- Never paste `aws secretsmanager get-secret-value` or decoded Kubernetes Secret output into logs or documentation.
- Restrict `allowed_ssh_cidr`; do not retain `0.0.0.0/0` outside a temporary demonstration.
- Restrict the EKS public endpoint CIDRs and enable EKS control-plane logs before production use.
- Use immutable image tags or digests and rotate any previously published credentials.

## Prerequisites

```bash
aws --version
terraform version
kubectl version --client
helm version
ansible --version
docker --version
jq --version
aws sts get-caller-identity
```

Required local access:

- AWS identity with permission to create the resources in this runbook.
- EC2 private key corresponding to `shopnow-key-pair`, or a newly created replacement.
- Jenkins credentials and GitHub webhook configuration.

## Rebuild procedure

### 1. Clone both repositories

```bash
git clone https://github.com/harishmsgit/shopNow.git
git clone https://github.com/harishmsgit/herovired-infra.git
cd herovired-infra
```

### 2. Create the Terraform backend

```bash
AWS_REGION=ap-south-1 \
TF_STATE_BUCKET=harish-pc-s3-bucket \
LOCK_TABLE=shopnow-terraform-locks \
bash scripts/ensure_tf_state_backend.sh
```

### 3. Create or select the EC2 key pair

Use an existing key pair only if its private key is still available. To create a new one:

```bash
aws ec2 create-key-pair \
  --region ap-south-1 \
  --key-name shopnow-key-pair \
  --query KeyMaterial \
  --output text > shopnow-key-pair.pem

chmod 600 shopnow-key-pair.pem
```

The private key is displayed only once. Store it securely and never commit it.

### 4. Configure Terraform

Review `terraform/terraform.tfvars`:

```hcl
aws_region          = "ap-south-1"
environment         = "dev"
cluster_name        = "shopnow-app-eks"
management_key_name = "shopnow-key-pair"
allowed_ssh_cidr    = "<YOUR-TRUSTED-PUBLIC-IP>/32"
ecr_repo_prefix     = "shopnow-dev"
vpc_cidr            = "10.20.0.0/16"
public_subnet_cidrs = ["10.20.1.0/24", "10.20.2.0/24"]
instance_type       = "t3.micro"
node_instance_types = ["t3.micro"]
node_min_size       = 2
node_desired_size   = 2
node_max_size       = 2
workload_node_instance_types = ["t3.small"]
workload_node_min_size       = 2
workload_node_desired_size   = 2
workload_node_max_size       = 3
```

### 5. Create ECR repositories

```bash
for REPOSITORY in shopnow-dev/frontend shopnow-dev/admin shopnow-dev/backend; do
  aws ecr describe-repositories \
    --region ap-south-1 \
    --repository-names "$REPOSITORY" >/dev/null 2>&1 || \
  aws ecr create-repository \
    --region ap-south-1 \
    --repository-name "$REPOSITORY" \
    --image-tag-mutability IMMUTABLE \
    --encryption-configuration encryptionType=AES256
done
```

### 6. Initialize, plan, and apply Terraform

```bash
terraform -chdir=terraform init -reconfigure \
  -backend-config=bucket=harish-pc-s3-bucket \
  -backend-config=key=terraform/terraform.tfstate \
  -backend-config=region=ap-south-1 \
  -backend-config=dynamodb_table=shopnow-terraform-locks

terraform -chdir=terraform workspace select -or-create dev
terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform validate
terraform -chdir=terraform plan -out=tfplan
terraform -chdir=terraform show tfplan
terraform -chdir=terraform apply tfplan
```

### 7. Connect kubectl

```bash
aws eks update-kubeconfig \
  --region ap-south-1 \
  --name shopnow-app-eks

kubectl cluster-info
kubectl get nodes -o wide
```

### 8. Create the MongoDB secret safely

Generate values outside Git:

```bash
export MONGO_INITDB_ROOT_USERNAME=shopuser
export MONGO_INITDB_ROOT_PASSWORD="$(openssl rand -base64 32 | tr -d '\n')"
export MONGODB_URI="mongodb://${MONGO_INITDB_ROOT_USERNAME}:${MONGO_INITDB_ROOT_PASSWORD}@mongo:27017/shopnow?authSource=admin"

bash scripts/create_aws_secret.sh shopnow/mongo ap-south-1

unset MONGO_INITDB_ROOT_USERNAME MONGO_INITDB_ROOT_PASSWORD MONGODB_URI
```

### 9. Verify IRSA and External Secrets

Terraform creates the OIDC provider, IAM role/policy and Helm release. Verify the live values rather than assuming a role name:

```bash
kubectl get deployment external-secrets -n shopnow-ns \
  -o jsonpath='{.spec.template.spec.serviceAccountName}{"\n"}'

kubectl get serviceaccount -n shopnow-ns -o yaml
kubectl apply -f kubernetes/k8s-manifests/database/aws-secretstore.yaml
kubectl apply -f kubernetes/k8s-manifests/database/mongo-secret-externalsecret.yaml
kubectl get secretstore,externalsecret -n shopnow-ns
```

Expected result: `aws-secret-store` is valid and `mongo-secret` is `SecretSynced`/ready.

### 10. Build and push ShopNow images

From the `shopNow` repository, build frontend, admin and backend with a Git SHA/release tag. Push them to:

```text
<ACCOUNT>.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/frontend:<TAG>
<ACCOUNT>.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/admin:<TAG>
<ACCOUNT>.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend:<TAG>
```

Prefer the ShopNow Jenkins pipeline so the tag, digest and build evidence are retained.

### 11. Deploy ShopNow

Use the infrastructure Jenkins pipeline with:

- `RUN_TERRAFORM` only when infrastructure reconciliation is required.
- `RUN_ANSIBLE_AFTER_APPLY` to configure/validate the management host.
- `RUN_DEPLOYMENT` with explicit frontend, admin and backend image URIs.
- `ALLOW_INFRA_ONLY_RUN` only for an intentional infrastructure-only execution.

Deployment order:

1. Namespace and External Secrets resources.
2. MongoDB.
3. Backend.
4. Frontend and admin.
5. Ingress.
6. Monitoring resources when required.

### 12. Verify the application

```bash
kubectl get deployments,pods,services,ingress -n shopnow-ns -o wide
kubectl get events -n shopnow-ns --sort-by=.lastTimestamp
kubectl rollout status deployment/backend -n shopnow-ns --timeout=5m
kubectl rollout status deployment/frontend -n shopnow-ns --timeout=5m
kubectl rollout status deployment/admin -n shopnow-ns --timeout=5m

export LB_HOST=$(kubectl get service \
  -n ingress-nginx ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

curl -fsS "http://$LB_HOST/shopnow/api/health"
curl -I "http://$LB_HOST/shopnow/"
curl -I "http://$LB_HOST/shopnow/admin/"
```

## Decommission procedure

### 1. Confirm identity and state

```bash
aws sts get-caller-identity
terraform -chdir=terraform workspace show
terraform -chdir=terraform state list
```

The workspace must be `dev` and the account/region must be the intended ShopNow environment.

### 2. Preserve non-secret evidence

Save only names, versions, image digests, and sanitized status. Do not save Terraform state, kubeconfig, private keys, decoded Secrets, MongoDB documents, customer data, or credentials.

### 3. Create a destroy plan

```bash
terraform -chdir=terraform plan -destroy -out=destroy.tfplan
terraform -chdir=terraform show destroy.tfplan
```

Review the plan before applying it.

### 4. Apply the Terraform destroy plan

```bash
terraform -chdir=terraform apply destroy.tfplan
```

This removes Terraform-owned EKS, EC2, networking, IAM/IRSA, Helm and Kubernetes resources.

### 5. Remove resources outside Terraform

Delete ECR repositories and images:

```bash
for REPOSITORY in shopnow-dev/frontend shopnow-dev/admin shopnow-dev/backend; do
  aws ecr delete-repository \
    --region ap-south-1 \
    --repository-name "$REPOSITORY" \
    --force
done
```

Schedule deletion of the MongoDB secret:

```bash
aws secretsmanager delete-secret \
  --region ap-south-1 \
  --secret-id shopnow/mongo \
  --recovery-window-in-days 7
```

Delete the EC2 key pair only after confirming no other environment uses it:

```bash
aws ec2 delete-key-pair \
  --region ap-south-1 \
  --key-name shopnow-key-pair
```

### 6. Remove the Terraform backend last

Only after Terraform state is empty and no environment shares the backend:

```bash
aws s3api list-object-versions --bucket harish-pc-s3-bucket
aws dynamodb scan --table-name shopnow-terraform-locks --region ap-south-1
```

Delete every object version and delete marker, then:

```bash
aws s3api delete-bucket \
  --bucket harish-pc-s3-bucket \
  --region ap-south-1

aws dynamodb delete-table \
  --table-name shopnow-terraform-locks \
  --region ap-south-1
```

### 7. Verify cleanup

```bash
aws eks list-clusters --region ap-south-1
aws ec2 describe-instances --region ap-south-1 \
  --filters 'Name=tag:Project,Values=shopNow' 'Name=instance-state-name,Values=pending,running,stopping,stopped'
aws ecr describe-repositories --region ap-south-1
aws secretsmanager list-secrets --region ap-south-1
aws elbv2 describe-load-balancers --region ap-south-1
aws elb describe-load-balancers --region ap-south-1
```

Expected result: no ShopNow EKS cluster, running EC2 instance, load balancer, ECR repository, active Mongo secret, VPC or related IAM role remains.

## Known current-environment notes

- Monitoring manifests exist, but `monitor-ns` had no deployed resources at the last verification.
- ECR repositories are data sources in Terraform and therefore survive `terraform destroy` unless deleted separately.
- The state backend and EC2 key pair are not Terraform-owned.
- Secrets Manager deletion uses a recovery window by default; it remains recoverable until the window expires.
- Application screenshots and READMEs are documentation only and do not affect AWS cleanup.

## Teardown record: August 24-25, 2026

The current `dev` environment was removed with the following observed results:

- Initial plan: `0 to add, 0 to change, 33 to destroy`.
- Terraform removed ingress-nginx before EKS, which removed the external load balancer first.
- External Secrets uninstall timed out after its Kubernetes namespace had already disappeared. Its confirmed-stale Terraform entry was removed only after `kubectl get namespace external-secrets` returned `NotFound`.
- An orphan EC2 instance tagged `dev-shopnow-management-instance` blocked its security group and internet gateway; it was terminated after exact instance/tag/ENI verification.
- The degraded workload node group remained in AWS `DELETING` for about 3 hours 19 minutes. Do not interrupt merely because EKS deletion is slow; verify its live status first.
- A temporary local DNS failure caused Terraform to time out while polling EKS/IAM. A later live check returned `ResourceNotFoundException` for the cluster, and a refreshed destroy completed the remaining resources.
- An unassociated orphan route table tagged `dev-shopnow-public-rt`, containing a blackholed route to the deleted gateway, blocked VPC deletion. It was inspected and deleted explicitly.
- Final Terraform state was empty before backend deletion.
- ECR `frontend`, `backend` and `admin` repositories were force-deleted.
- `shopnow/mongo` was scheduled for deletion with a 7-day recovery window, ending September 1, 2026.
- `shopnow-key-pair`, the S3 backend bucket and the dedicated DynamoDB lock table were deleted last.
