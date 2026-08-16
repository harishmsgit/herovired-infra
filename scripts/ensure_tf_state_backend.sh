#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-ap-south-1}"
TF_STATE_BUCKET="${TF_STATE_BUCKET:-harish-pc-s3-bucket}"
LOCK_TABLE="${LOCK_TABLE:-shopnow-terraform-locks}"

if ! command -v aws >/dev/null 2>&1; then
  echo "AWS CLI is required but was not found in PATH." >&2
  exit 1
fi

if aws s3api head-bucket --bucket "$TF_STATE_BUCKET" --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "S3 bucket already exists: s3://$TF_STATE_BUCKET"
else
  echo "S3 bucket does not exist. Creating: s3://$TF_STATE_BUCKET"
  aws s3 mb "s3://$TF_STATE_BUCKET" --region "$AWS_REGION" --no-cli-pager
  aws s3api put-bucket-versioning \
    --bucket "$TF_STATE_BUCKET" \
    --versioning-configuration Status=Enabled \
    --region "$AWS_REGION"
  aws s3api put-bucket-encryption \
    --bucket "$TF_STATE_BUCKET" \
    --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}' \
    --region "$AWS_REGION"
fi

if aws dynamodb describe-table --table-name "$LOCK_TABLE" --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "DynamoDB lock table already exists: $LOCK_TABLE"
else
  echo "DynamoDB lock table does not exist. Creating: $LOCK_TABLE"
  aws dynamodb create-table \
    --table-name "$LOCK_TABLE" \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5 \
    --region "$AWS_REGION" \
    --no-cli-pager
fi

echo "Terraform backend is ready."
echo "Use: terraform init -reconfigure"
