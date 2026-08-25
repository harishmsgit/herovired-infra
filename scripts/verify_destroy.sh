#!/usr/bin/env bash
set -u

REGION="${AWS_REGION:-ap-south-1}"

echo "EKS cluster (expected: ResourceNotFound)"
aws eks describe-cluster --region "$REGION" --name shopnow-app-eks --query cluster.status --output text

echo "Active ShopNow-tagged EC2 instances (expected: [])"
aws ec2 describe-instances --region "$REGION" \
  --filters Name=tag:Project,Values=shopNow Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].{Id:InstanceId,State:State.Name}' --output json

echo "ShopNow dev VPCs (expected: [])"
aws ec2 describe-vpcs --region "$REGION" --filters Name=tag:Name,Values=dev-shopnow-vpc \
  --query 'Vpcs[].VpcId' --output json

echo "ECR repositories (each expected: RepositoryNotFoundException)"
for repository in shopnow-dev/frontend shopnow-dev/admin shopnow-dev/backend; do
  aws ecr describe-repositories --region "$REGION" --repository-names "$repository" \
    --query 'repositories[].repositoryName' --output text
done

echo "Secret (expected: DeletedDate until recovery window expires)"
aws secretsmanager describe-secret --region "$REGION" --secret-id shopnow/mongo \
  --query '{Name:Name,DeletedDate:DeletedDate}' --output json

echo "Backend (expected after final cleanup: bucket/table not found)"
aws s3api head-bucket --bucket harish-pc-s3-bucket
aws dynamodb describe-table --region "$REGION" --table-name shopnow-terraform-locks \
  --query 'Table.TableStatus' --output text
