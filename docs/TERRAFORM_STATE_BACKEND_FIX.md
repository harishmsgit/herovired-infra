# Terraform backend state fix for Jenkins pipeline

## Issue summary

The pipeline can fail during `terraform init` with errors like:

- `The value must not start or end with "/"`
- `Missing region value`
- stale backend state prompts or invalid state checksum mismatch

This is usually caused by stale Terraform backend metadata or a stale DynamoDB lock digest entry.

## Root cause

The S3 backend object key and the DynamoDB lock key are different things.

- Correct S3 state key example: `terraform/terraform.tfstate`
- Lock key example in DynamoDB: `harish-pc-s3-bucket/env:/dev/terraform/terraform.tfstate-md5`

If the local `.terraform` directory is stale, or the DynamoDB digest does not match the current S3 object checksum, Terraform refuses to initialize or read remote state.

## Verified values for this project

- AWS region: `ap-south-1`
- S3 bucket: `harish-pc-s3-bucket`
- Lock table: `shopnow-terraform-locks`
- Current S3 state key: `terraform/terraform.tfstate`
- Stale DynamoDB digest: `8da4f4928ea3a7e8fb36dc9ffb28d2cc`
- Actual current S3 digest: `4f416a3d4bdc6317511e169793ee975e`

## Quick fix

Run the following commands from the Terraform directory:

```bash
cd herovired-infra/terraform
rm -rf .terraform

export AWS_REGION=ap-south-1
export AWS_DEFAULT_REGION=ap-south-1

terraform init -reconfigure \
  -backend-config="bucket=harish-pc-s3-bucket" \
  -backend-config="key=terraform/terraform.tfstate" \
  -backend-config="region=ap-south-1" \
  -backend-config="dynamodb_table=shopnow-terraform-locks"
```

If Terraform still fails with a checksum mismatch, update the stale lock digest in DynamoDB:

```bash
aws dynamodb update-item \
  --table-name shopnow-terraform-locks \
  --region ap-south-1 \
  --key '{"LockID":{"S":"harish-pc-s3-bucket/env:/dev/terraform/terraform.tfstate-md5"}}' \
  --attribute-updates '{"Digest":{"Value":{"S":"4f416a3d4bdc6317511e169793ee975e"},"Action":"PUT"}}' \
  --return-values ALL_NEW
```

Then continue:

```bash
terraform init -reconfigure
terraform workspace select dev || terraform workspace new dev
terraform validate
terraform plan
```

## Jenkins-safe pattern

Use this in the pipeline before running Terraform:

```bash
set -e
export TF_INPUT=false
export AWS_REGION=ap-south-1
export AWS_DEFAULT_REGION=ap-south-1
rm -rf .terraform

terraform init -reconfigure \
  -backend-config="bucket=${TF_STATE_BUCKET}" \
  -backend-config="key=terraform/terraform.tfstate" \
  -backend-config="region=${TF_STATE_BUCKET_REGION}" \
  -backend-config="dynamodb_table=${LOCK_TABLE}"

if ! terraform state list >/dev/null 2>&1; then
  echo "Terraform state checksum mismatch detected; repairing stale lock entry"
  aws dynamodb update-item \
    --table-name ${LOCK_TABLE} \
    --region ${AWS_REGION} \
    --key '{"LockID":{"S":"'"${TF_STATE_BUCKET}"'/env:/dev/terraform/terraform.tfstate-md5"}}' \
    --attribute-updates '{"Digest":{"Value":{"S":"4f416a3d4bdc6317511e169793ee975e"},"Action":"PUT"}}'
  terraform init -reconfigure \
    -backend-config="bucket=${TF_STATE_BUCKET}" \
    -backend-config="key=terraform/terraform.tfstate" \
    -backend-config="region=${TF_STATE_BUCKET_REGION}" \
    -backend-config="dynamodb_table=${LOCK_TABLE}"
fi
```

## Important rules

- Never use the DynamoDB lock key as the S3 state key.
- Never leave stale `.terraform` metadata in the workspace.
- Always pass backend values explicitly during `terraform init`.
- Always set the AWS region explicitly when using the S3 backend.

## When to use this fix

Use this fix when the pipeline shows:

- `The value must not start or end with "/"`
- `Missing region value`
- stale checksum mismatch from S3 vs DynamoDB
- backend init fails even though the bucket exists
- Jenkins pipeline fails before plan/apply due to state lock errors

## Final note

This is a Terraform remote-state integrity issue, not an application code issue. The correct fix is to clear stale backend state, rebind the backend with explicit values, and repair the stale DynamoDB digest when the remote checksum no longer matches the lock record.
