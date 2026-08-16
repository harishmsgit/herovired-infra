# Infrastructure Update Summary

**Date**: 2026-08-16  
**Changes**: AWS Account Migration & EKS Cluster Rename

## Changes Applied

### 1. AWS Account ID Update
- **Old Account ID**: `495013583028`
- **New Account ID**: `559272000457`

### 2. EKS Cluster Name Update
- **Old Cluster Name**: `java-spring-eks`
- **New Cluster Name**: `shopnow-app-eks`

## Files Updated (10 total)

### Core Configuration Files
1. ✅ `herovired-infra/config/common.env`
   - AWS_ACCOUNT_ID: 495013583028 → 559272000457
   - EKS_CLUSTER_NAME: java-spring-eks → shopnow-app-eks

2. ✅ `herovired-infra/Jenkinsfile`
   - AWS_ACCOUNT_ID parameter default: 495013583028 → 559272000457
   - EKS_CLUSTER_NAME parameter default: java-spring-eks → shopnow-app-eks

3. ✅ `herovired-infra/terraform/terraform.tfvars.example`
   - cluster_name: shopnow-eks → shopnow-app-eks

### Pipeline Files
4. ✅ `herovired-infra/Makefile`
   - AWS_ACCOUNT_ID: 495013583028 → 559272000457

5. ✅ `herovired-infra/pipelines/ansible.groovy`
   - EKS_CLUSTER_NAME parameter default: java-spring-eks → shopnow-app-eks
   - resolveConfigValue default: java-spring-eks → shopnow-app-eks

6. ✅ `herovired-infra/pipelines/sprint5.groovy`
   - awsAccountId fallback: 495013583028 → 559272000457
   - AWS_ACCOUNT_ID parameter default: 495013583028 → 559272000457
   - EKS_CLUSTER_NAME parameter default: java-spring-eks → shopnow-app-eks

7. ✅ `herovired-infra/pipelines/terraform.groovy`
   - EKS_CLUSTER_NAME parameter default: java-spring-eks → shopnow-app-eks
   - resolveConfigValue default: java-spring-eks → shopnow-app-eks

### Deployment Scripts
8. ✅ `shopNow/deploy-aws-eks.ps1`
   - AWS Account fallback: 495013583028 → 559272000457
   - Cluster name fallback: java-spring-eks → shopnow-app-eks

9. ✅ `shopNow/deploy-aws-eks.sh`
   - AWS Account fallback: 495013583028 → 559272000457
   - Cluster name fallback: java-spring-eks → shopnow-app-eks

10. ✅ `shopNow/Jenkinsfile`
    - AWS_ACCOUNT_ID parameter default: 495013583028 → 559272000457

## Verification Results

✅ **All old references have been removed**
- No remaining instances of `495013583028`
- No remaining instances of `java-spring-eks`

## Next Steps

1. **Environment Variables**: Update any CI/CD system environment variables:
   - AWS_ACCOUNT_ID=559272000457
   - EKS_CLUSTER_NAME=shopnow-app-eks

2. **AWS CLI/SDK Configurations**: Update any local AWS profiles with the new account

3. **GitHub Secrets** (if using GitHub Actions):
   - Run: `AWS_ACCOUNT_ID=559272000457 ./herovired-infra/scripts/set_github_secrets.sh`

4. **Documentation**: Update any team documentation referencing the old account/cluster names

5. **Test Deployments**: Verify connectivity to the new EKS cluster after these changes

## Related Configuration Values (Already Correct)

- ECR_REPO_PREFIX: `shopnow`
- K8S_NAMESPACE: `shopnow-ns`
- AWS_REGION: `ap-south-1`
- TF_STATE_BUCKET: `harish-terraform-state-bucket`
- LOCK_TABLE: `shopnow-terraform-locks`

---

**Note**: All changes are backward compatible with environment variable overrides. Deployed services will use these values from environment variables if set.
