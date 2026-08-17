#!/usr/bin/env bash
set -euo pipefail

BUCKET="${TF_STATE_BUCKET:-harish-pc-s3-bucket}"
TABLE="${LOCK_TABLE:-shopnow-terraform-locks}"
STATE_KEY="${STATE_KEY:-env:/dev/terraform/terraform.tfstate}"
AWS_REGION="${AWS_REGION:-ap-south-1}"
FORCE="${FORCE:-false}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bucket)
      BUCKET="$2"
      shift 2
      ;;
    --table)
      TABLE="$2"
      shift 2
      ;;
    --state-key)
      STATE_KEY="$2"
      shift 2
      ;;
    --region)
      AWS_REGION="$2"
      shift 2
      ;;
    --force)
      FORCE="true"
      shift
      ;;
    -h|--help)
      echo "Usage: $0 [--bucket BUCKET] [--table TABLE] [--state-key STATE_KEY] [--region REGION] [--force]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if ! command -v aws >/dev/null 2>&1; then
  echo "aws CLI is required but was not found in PATH." >&2
  exit 1
fi

if ! aws s3api head-bucket --bucket "$BUCKET" --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "S3 bucket $BUCKET does not exist. Nothing to repair."
  exit 0
fi

if ! aws dynamodb describe-table --table-name "$TABLE" --region "$AWS_REGION" >/dev/null 2>&1; then
  echo "DynamoDB lock table $TABLE does not exist. Nothing to repair."
  exit 0
fi

if [[ "$FORCE" != "true" ]]; then
  echo "This will remove the stale Terraform state digest from DynamoDB for key: $STATE_KEY"
  echo "Review the state before continuing. Re-run with --force to apply the fix."
  exit 0
fi

aws dynamodb delete-item \
  --table-name "$TABLE" \
  --region "$AWS_REGION" \
  --key "{\"LockID\":{\"S\":\"$STATE_KEY\"}}" >/dev/null

echo "Removed stale Terraform state digest for $STATE_KEY from $TABLE."
echo "Run terraform init and then terraform plan/apply again."
