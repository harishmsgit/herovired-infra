# What to Create Manually vs Terraform Creates - New Account Setup

## 📋 Summary
When you trigger the Jenkins pipeline, it will **automatically** create most AWS resources. However, you need to manually create a **few prerequisites** first.

---

## ✅ TERRAFORM CREATES AUTOMATICALLY (23 Resources)

### Network & Security
- ✅ VPC (vpc-main)
- ✅ Internet Gateway
- ✅ Public Subnets (multi-AZ)
- ✅ Route Tables & Routes
- ✅ Security Groups (EKS cluster, Management EC2, Nodes)

### Compute & Orchestration
- ✅ EKS Cluster (shopnow-app-eks)
- ✅ EKS Node Group (worker nodes)
- ✅ EC2 Management Instance (for Ansible, kubectl, etc.)

### IAM & Roles
- ✅ EKS Cluster IAM Role
- ✅ EKS Node Group IAM Role
- ✅ Management EC2 IAM Role & Instance Profile
- ✅ IAM Policy Attachments (ECS readonly, SSM, CNI, etc.)

### Container Registry
- ✅ ECR Repositories (frontend, backend, admin)

### State Management
- ✅ S3 Bucket for Terraform state (harish-terraform-state-bucket) - **CREATED BY PIPELINE IF NOT EXISTS**
- ✅ DynamoDB Table for locking (shopnow-terraform-locks) - **CREATED BY PIPELINE IF NOT EXISTS**

---

## ⚠️ MUST CREATE MANUALLY BEFORE PIPELINE

### 1. **AWS CLI Configuration** (CRITICAL)
```powershell
# Verify your credentials are pointing to the NEW account
aws sts get-caller-identity

# Should show:
# "Account": "559272000457"
```

### 2. **IAM User or Role with Permissions** (CRITICAL)
The AWS credentials need these permissions:
- EKS full access (eks:*)
- EC2 full access (ec2:*)
- IAM full access (iam:*)
- S3 full access (s3:*)
- DynamoDB full access (dynamodb:*)
- VPC full access (ec2:DescribeVpcs, etc.)

**Recommendation**: Use AWS managed policy `AdministratorAccess` or create a custom policy

### 3. **EC2 Key Pair** (REQUIRED for SSH access)
```powershell
# Check if the key pair exists
aws ec2 describe-key-pairs --key-names "replace-with-existing-keypair"

# If not, create one:
aws ec2 create-key-pair --key-name "replace-with-existing-keypair" --query 'KeyMaterial' --output text > keypair.pem
chmod 400 keypair.pem
```

**Update this in**: `herovired-infra/terraform/terraform.tfvars`
```
management_key_name = "your-existing-keypair"
```

---

## 🔄 PIPELINE AUTO-CREATES (If Missing)

The Jenkinsfile has built-in checks at the "Terraform Init" stage:

```groovy
// S3 bucket auto-create
if ! aws s3api head-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION} 2>/dev/null; then
  aws s3api create-bucket --bucket ${TF_STATE_BUCKET} ...
fi

// DynamoDB table auto-create
if ! aws dynamodb describe-table --table-name ${LOCK_TABLE} --region ${AWS_REGION} 2>/dev/null; then
  aws dynamodb create-table --table-name ${LOCK_TABLE} ...
fi
```

✅ **S3 Bucket**: `harish-terraform-state-bucket` (auto-created if missing)  
✅ **DynamoDB Table**: `shopnow-terraform-locks` (auto-created if missing)

---

## 📊 Complete Resource List

| Resource | Type | Manual? | Auto-Create? | Notes |
|---|---|---|---|---|
| VPC | Network | ❌ | ✅ | Created by Terraform |
| Subnets | Network | ❌ | ✅ | Multi-AZ public subnets |
| Security Groups | Network | ❌ | ✅ | EKS & Management SGs |
| EKS Cluster | Compute | ❌ | ✅ | shopnow-app-eks |
| EKS Node Group | Compute | ❌ | ✅ | Worker nodes |
| Management EC2 | Compute | ❌ | ✅ | For Ansible & kubectl |
| IAM Roles | Security | ❌ | ✅ | EKS, Nodes, Management |
| IAM Policies | Security | ❌ | ✅ | Attached to roles |
| ECR Repos | Registry | ❌ | ✅ | frontend, backend, admin |
| S3 Bucket | Storage | ⚠️ | ✅* | Pipeline creates if missing |
| DynamoDB Table | Database | ⚠️ | ✅* | Pipeline creates if missing |
| **EC2 Key Pair** | **Security** | **✅ YES** | ❌ | Must exist beforehand |
| **IAM Permissions** | **Security** | **✅ YES** | ❌ | Your AWS user needs them |

---

## 🚀 Pre-Pipeline Checklist

Before clicking "Build" on Jenkins:

- [ ] AWS CLI configured with new account (559272000457)
- [ ] AWS user/role has Admin or required permissions
- [ ] EC2 key pair exists (or create one)
- [ ] Update `terraform.tfvars` with key pair name
- [ ] Jenkins is running and has AWS credentials configured
- [ ] No conflicting network CIDR blocks in your AWS account

---

## 📝 Configuration Files to Review

1. **herovired-infra/config/common.env**
   - ✅ Already updated with new account & cluster name

2. **herovired-infra/terraform/terraform.tfvars** (Example)
   - ✅ cluster_name = "shopnow-app-eks" ✅
   - ⚠️ management_key_name = "replace-with-existing-keypair" (UPDATE THIS!)

3. **herovired-infra/Jenkinsfile**
   - ✅ AWS_ACCOUNT_ID = 559272000457 ✅
   - ✅ EKS_CLUSTER_NAME = shopnow-app-eks ✅
   - ✅ TF_STATE_BUCKET = harish-terraform-state-bucket ✅
   - ✅ LOCK_TABLE = shopnow-terraform-locks ✅

---

## 🔧 Quick Setup Commands

```powershell
# 1. Verify AWS account
aws sts get-caller-identity

# 2. Create EC2 key pair
aws ec2 create-key-pair --key-name shopnow-key-pair --query 'KeyMaterial' --output text > shopnow-key-pair.pem

# 3. Check key pair created
aws ec2 describe-key-pairs --key-names shopnow-key-pair

# 4. Update terraform.tfvars
# Edit: herovired-infra/terraform/terraform.tfvars
# Change: management_key_name = "shopnow-key-pair"

# 5. Ready for pipeline!
echo "All prerequisites met. Pipeline can run now!"
```

---

## ⚠️ Common Issues

| Issue | Cause | Solution |
|---|---|---|
| "Access Denied" errors | AWS credentials pointing to old account | Run: `aws sts get-caller-identity` and verify account |
| Key pair not found | EC2 key doesn't exist in new account | Create: `aws ec2 create-key-pair --key-name mykey` |
| Permission denied for EKS | IAM user lacks permissions | Add `AmazonEKSFullAccess` or admin policy |
| S3 bucket already exists | Name taken in another account | Use different bucket name in common.env |
| DynamoDB table locked | State already locked | Clear old locks (manual cleanup) |

---

## ✅ Verification Before Pipeline

Run these commands to verify everything is ready:

```powershell
# 1. Check AWS account
aws sts get-caller-identity | Select-Object Account

# 2. List key pairs (should show your key)
aws ec2 describe-key-pairs | ConvertFrom-Json | .KeyPairs

# 3. Check S3 buckets (won't exist yet, that's OK)
aws s3 ls | grep harish-terraform-state

# 4. Check DynamoDB tables (won't exist yet, that's OK)
aws dynamodb list-tables | ConvertFrom-Json | .TableNames

echo "✅ All prerequisites verified!"
```

---

## Pipeline Execution Flow

```
1. Checkout code ✅
2. Initialize environment ✅
3. Terraform Init (creates S3 bucket & DynamoDB if missing)
4. Terraform Plan
5. Terraform Apply → Creates all resources
6. Ansible Configure (configures management EC2)
7. Deploy to EKS (deploys applications)
```

All stages depend on prerequisites being met!
