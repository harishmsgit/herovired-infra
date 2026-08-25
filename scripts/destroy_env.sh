#!/usr/bin/env bash
set -euo pipefail

REGION="${AWS_REGION:-ap-south-1}"
WORKSPACE="${TF_WORKSPACE:-dev}"
APPLY=false
[[ "${1:-}" == "--apply" ]] && APPLY=true

aws sts get-caller-identity
actual_workspace="$(terraform -chdir=terraform workspace show)"
[[ "$actual_workspace" == "$WORKSPACE" ]] || {
  echo "Refusing destroy: expected workspace $WORKSPACE, found $actual_workspace" >&2
  exit 1
}

terraform -chdir=terraform plan -destroy -out=destroy.tfplan
terraform -chdir=terraform show destroy.tfplan

if [[ "$APPLY" != true ]]; then
  echo "Destroy plan only. Review it, then set CONFIRM_SHOPNOW_DESTROY=dev and rerun with --apply."
  exit 0
fi

[[ "${CONFIRM_SHOPNOW_DESTROY:-}" == "$WORKSPACE" ]] || {
  echo "Refusing destroy: export CONFIRM_SHOPNOW_DESTROY=$WORKSPACE" >&2
  exit 1
}

terraform -chdir=terraform apply destroy.tfplan

for repository in shopnow-dev/frontend shopnow-dev/admin shopnow-dev/backend; do
  aws ecr delete-repository --region "$REGION" --repository-name "$repository" --force || true
done
aws secretsmanager delete-secret --region "$REGION" --secret-id shopnow/mongo \
  --recovery-window-in-days 7 || true
aws ec2 delete-key-pair --region "$REGION" --key-name shopnow-key-pair || true

echo "Terraform and non-backend resources processed. Verify with scripts/verify_destroy.sh."
echo "Delete the S3 state bucket and DynamoDB lock table manually, and only after state is empty."
