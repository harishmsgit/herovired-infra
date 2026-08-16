# Terraform Infrastructure Guide

## Overview

Terraform is the **Infrastructure-as-Code (IaC)** tool that provisions and manages all AWS cloud resources for the ShopNow application. It creates everything from networking to Kubernetes clusters in an automated, reproducible way.

---

## What Terraform Does

### 1. **VPC & Networking** (terraform/vpc.tf)
```
VPC: 10.20.0.0/16
├── Public Subnet 1: 10.20.1.0/24 (ap-south-1a)
├── Public Subnet 2: 10.20.2.0/24 (ap-south-1b)
└── Internet Gateway + Route Tables
```
**Purpose**: Creates isolated network for all resources, allows internet access via IGW

---

### 2. **EKS Cluster** (terraform/eks.tf)
```
EKS Cluster: shopnow-app-eks
├── Control Plane: Managed by AWS
├── Worker Nodes: t3.medium instances
│   ├── Node Group 1 (ap-south-1a)
│   └── Node Group 2 (ap-south-1b)
├── RBAC: IAM roles for nodes
└── Add-ons: VPC-CNI, kube-proxy, coredns
```
**Purpose**: Kubernetes orchestration platform for containerized applications

---

### 3. **EC2 Management Host** (terraform/management.tf)
```
Management Instance: t3.medium (Ubuntu 22.04)
├── Role: Ansible control node
├── Security Group: SSH (22) from CI/CD
├── Key Pair: shopnow-key-pair
└── Auto-assigned Public IP
```
**Purpose**: Runs Ansible playbooks to configure EKS and applications

---

### 4. **ECR Repositories** (terraform/ecr.tf)
```
ECR Registry (ap-south-1):
├── shopnow-dev/frontend
├── shopnow-dev/admin
└── shopnow-dev/backend
```
**Purpose**: Private Docker image storage for MERN stack services

---

### 5. **IAM Roles & Policies** (terraform/iam.tf)
```
Service Accounts:
├── EKS Node Role → EC2 instance permissions
├── EKS Pod Role → Kubernetes IRSA
└── Management Role → EC2 management host permissions

Permissions Granted:
├── EKS API access
├── ECR pull (image registry)
├── CloudWatch logs (monitoring)
├── Secrets Manager (credentials)
└── S3 bucket access (state, backups)
```
**Purpose**: Least-privilege access control for all components

---

### 6. **S3 Bucket** (terraform/main.tf)
```
S3 Bucket: harish-pc-s3-bucket
├── Terraform state storage
├── State locking: enabled
└── Versioning: enabled
```
**Purpose**: Remote state storage (prevents conflicts, enables team collaboration)

---

### 7. **DynamoDB Table** (terraform/main.tf)
```
DynamoDB Table: shopnow-terraform-locks
├── Primary Key: LockID
├── On-Demand Billing
└── Used for: State locking
```
**Purpose**: Prevents concurrent Terraform applies (mutual exclusion)

---

## Terraform File Structure

```
terraform/
├── main.tf                  # Entry point, state backend config
├── provider.tf             # AWS provider version constraints
├── variables.tf            # All variable definitions
├── outputs.tf              # Output values (IPs, ARNs, endpoints)
├── terraform.tfvars        # Environment-specific values (dev/prod)
├── backend.tf              # S3 backend configuration
├── vpc.tf                  # VPC, subnets, IGW
├── eks.tf                  # EKS cluster + node groups
├── management.tf           # EC2 management instance
├── ecr.tf                  # ECR repositories
├── iam.tf                  # IAM roles + policies
├── monitoring.tf           # CloudWatch + alarms
├── .terraform.lock.hcl     # Provider version lock (do NOT edit)
└── terraform.tfvars.example # Template for terraform.tfvars
```

---

## Most Frequent Changes in Production

### 1. **Scaling Worker Nodes** ⭐⭐⭐
**When**: Application traffic increases
```hcl
# In terraform.tfvars
desired_size = 3  # was 2, now 3
max_size = 5      # was 4, now 5

# Command to apply
terraform plan -var="desired_size=3"
terraform apply -auto-approve tfplan
```

---

### 2. **Changing Instance Types** ⭐⭐⭐
**When**: Workload requires more CPU/memory
```hcl
# In terraform.tfvars
instance_type = "t3.large"  # was t3.medium

# Impact: Rolling restart of node group
terraform plan
terraform apply
# Result: Old nodes terminate → new nodes spin up
```

---

### 3. **Adding New ECR Repositories** ⭐⭐
**When**: New microservice deployed
```hcl
# In terraform/ecr.tf - add new resource
resource "aws_ecr_repository" "new_service" {
  name                 = "shopnow-dev/new-service"
  image_tag_mutability = "MUTABLE"
}

terraform apply
```

---

### 4. **Modifying Security Groups** ⭐⭐
**When**: Need to expose new ports or restrict access
```hcl
# In terraform/eks.tf
ingress {
  from_port   = 443  # HTTPS
  to_port     = 443
  protocol    = "tcp"
  cidr_blocks = ["10.20.0.0/16"]  # only from VPC
}

terraform apply
```

---

### 5. **Adding Environment Variables to IAM Roles** ⭐
**When**: Application needs access to new AWS service
```hcl
# In terraform/iam.tf - add new policy
{
  Effect = "Allow"
  Action = [
    "s3:GetObject",
    "s3:PutObject"
  ]
  Resource = ["arn:aws:s3:::my-bucket/*"]
}

terraform apply
```

---

## Terraform Management Tasks

### **Initialization**
```bash
cd herovired-infra/terraform

# First time setup
terraform init
# Optionally use var file:
terraform init -backend-config="bucket=harish-pc-s3-bucket" \
              -backend-config="key=terraform/state" \
              -backend-config="region=ap-south-1"
```

### **Planning Changes**
```bash
# See what will change
terraform plan -var-file="terraform.tfvars"

# Save plan to file
terraform plan -var-file="terraform.tfvars" -out=tfplan

# Review specific resource
terraform plan -var-file="terraform.tfvars" | grep aws_eks_cluster
```

### **Applying Changes**
```bash
# Apply interactively (prompts yes/no)
terraform apply -var-file="terraform.tfvars"

# Apply non-interactively (auto-approve)
terraform apply -auto-approve tfplan

# Apply specific resource only
terraform apply -target=aws_eks_cluster.shopnow
```

### **Destroying Resources**
```bash
# Destroy everything
terraform destroy

# Destroy specific resource
terraform destroy -target=aws_instance.management

# Destroy but keep S3 bucket (to preserve state)
# Manually delete from AWS console after destroy
```

### **Viewing State**
```bash
# Show all resources
terraform state list

# Show specific resource details
terraform state show aws_eks_cluster.shopnow

# Pull remote state to local (debug)
terraform state pull > state-backup.json

# View outputs
terraform output
terraform output eks_cluster_endpoint
```

### **Troubleshooting**
```bash
# Enable debug logging
export TF_LOG=debug
terraform plan

# Save logs to file
export TF_LOG_PATH="/tmp/terraform-debug.log"

# Validate configuration
terraform validate

# Format code
terraform fmt -recursive

# Check unused variables
terraform validate
```

---

## State Management Best Practices

### **Remote State** ✅
```hcl
# backend.tf - already configured
backend "s3" {
  bucket         = "harish-pc-s3-bucket"
  key            = "terraform/state"
  region         = "ap-south-1"
  dynamodb_table = "shopnow-terraform-locks"
  encrypt        = true
}
```
**Why**: 
- ✅ Centralized state (team access)
- ✅ State locking (prevents conflicts)
- ✅ Automatic backups (versioning)
- ✅ Encrypted at rest

### **State Locking**
```
When terraform plan/apply runs:
1. Acquires lock on DynamoDB table
2. Reads state from S3
3. Makes changes
4. Writes new state to S3
5. Releases lock

If process crashes: Lock held for ~30 seconds before timeout
```

### **State Backup Procedure**
```bash
# Weekly backup
terraform state pull > backups/state-$(date +%Y%m%d).json

# Store in S3
aws s3 cp state-backup.json s3://harish-pc-s3-bucket/backups/

# Version history available automatically (S3 versioning enabled)
```

---

## Module Organization

### Current Structure (Flat)
```
terraform/
├── main.tf      # All resources in one directory
├── variables.tf
└── outputs.tf
```

### Recommended Future Structure (Modular)
```
terraform/
├── main.tf
├── variables.tf
├── outputs.tf
├── modules/
│   ├── networking/       # VPC, subnets, security groups
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── eks/              # EKS cluster + node groups
│   │   ├── main.tf
│   │   └── variables.tf
│   ├── iam/              # IAM roles + policies
│   │   ├── main.tf
│   │   └── variables.tf
│   └── storage/          # S3, ECR, DynamoDB
│       ├── main.tf
│       └── variables.tf
└── environments/
    ├── dev/              # Development overrides
    │   ├── terraform.tfvars
    │   └── backend.tfvars
    └── prod/             # Production overrides
        ├── terraform.tfvars
        └── backend.tfvars
```

**Benefits**: Better reusability, clarity, and team collaboration

---

## Common Errors & Solutions

| Error | Cause | Solution |
|-------|-------|----------|
| `Error acquiring lock` | Concurrent apply | Wait 30s or manually release: `aws dynamodb update-item --table shopnow-terraform-locks --key ...` |
| `InvalidLocationConstraintException` | Wrong region config | Check `TF_STATE_BUCKET_REGION` matches bucket region |
| `AccessDenied on S3` | IAM permissions | Verify AWS credentials have `s3:*` and `dynamodb:*` permissions |
| `Error: timeout waiting for plugin` | Provider initialization slow | Add retry logic or increase timeout |
| `State serial mismatch` | Concurrent updates | Never manually edit state; use `terraform state` commands |

---

## Security Considerations

### **Sensitive Data in State**
❌ **Never commit to Git**: Database passwords, SSH keys, tokens
✅ **Use**: `sensitive = true` in outputs, AWS Secrets Manager for secrets

```hcl
output "db_password" {
  value     = random_password.db.result
  sensitive = true  # Hides from logs
}
```

### **Backend Security**
- ✅ S3 encryption enabled (AES-256)
- ✅ State locking via DynamoDB
- ✅ Versioning enabled (history protection)
- ✅ Public access blocked
- ✅ IAM policy restricted to CI/CD user

### **Access Control**
```bash
# Only CI/CD user can modify infrastructure
aws iam create-user-policy --user-name ci-user --policy-document ...
```

---

## Migration & Upgrades

### **Terraform Version Upgrade**
```bash
# Current: v1.x
# Future: v2.x

# Steps:
1. Test locally: terraform init -upgrade
2. Run: terraform plan
3. Review changes (usually breaking changes)
4. Update configurations as needed
5. Commit to version control
6. Test in dev environment first
```

### **AWS Provider Upgrade**
```bash
# In versions.tf
terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"  # allows 5.x but not 6.x
    }
  }
}

# To upgrade:
rm .terraform.lock.hcl
terraform init -upgrade
terraform plan
```

---

## Summary

| Aspect | Details |
|--------|---------|
| **Purpose** | Provision AWS infrastructure (VPC, EKS, EC2, ECR, IAM) |
| **Update Frequency** | Daily (config changes) to Monthly (version upgrades) |
| **Common Changes** | Scaling nodes, adding ECR repos, security group rules |
| **Risk Level** | Medium (can affect running applications) |
| **Rollback** | Automatic (previous state in S3 versioning) |
| **Learning Curve** | 2-3 weeks to master |

