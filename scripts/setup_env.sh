#!/usr/bin/env bash
set -euo pipefail

REGION="${AWS_REGION:-ap-south-1}"
BUCKET="${TF_STATE_BUCKET:-harish-pc-s3-bucket}"
LOCK_TABLE="${TF_LOCK_TABLE:-shopnow-terraform-locks}"
WORKSPACE="${TF_WORKSPACE:-dev}"
KEY_NAME="${EC2_KEY_NAME:-shopnow-key-pair}"
APPLY=false

[[ "${1:-}" == "--apply" ]] && APPLY=true

for command in aws terraform; do
  command -v "$command" >/dev/null || { echo "Missing command: $command" >&2; exit 1; }
done

aws sts get-caller-identity

AWS_REGION="$REGION" TF_STATE_BUCKET="$BUCKET" LOCK_TABLE="$LOCK_TABLE" \
  bash scripts/ensure_tf_state_backend.sh

if ! aws ec2 describe-key-pairs --region "$REGION" --key-names "$KEY_NAME" >/dev/null 2>&1; then
  echo "EC2 key pair $KEY_NAME does not exist. Create it securely before continuing." >&2
  exit 1
fi

for repository in shopnow-dev/frontend shopnow-dev/admin shopnow-dev/backend; do
  aws ecr describe-repositories --region "$REGION" --repository-names "$repository" >/dev/null 2>&1 || \
    aws ecr create-repository --region "$REGION" --repository-name "$repository" \
      --image-tag-mutability IMMUTABLE --encryption-configuration encryptionType=AES256
done

terraform -chdir=terraform init -reconfigure \
  -backend-config="bucket=$BUCKET" \
  -backend-config="key=terraform/terraform.tfstate" \
  -backend-config="region=$REGION" \
  -backend-config="dynamodb_table=$LOCK_TABLE"
terraform -chdir=terraform workspace select -or-create "$WORKSPACE"
terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform validate
terraform -chdir=terraform plan -out=tfplan

if [[ "$APPLY" == true ]]; then
  terraform -chdir=terraform apply tfplan
else
  echo "Plan created. Review it, then rerun: bash scripts/setup_env.sh --apply"
fi
