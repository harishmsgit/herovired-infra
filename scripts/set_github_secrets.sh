#!/usr/bin/env bash
set -euo pipefail

# Usage:
#   GITHUB_REPO=owner/repo ./scripts/set_github_secrets.sh
# Or export variables and run non-interactively:
#   AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=... AWS_ACCOUNT_ID=... ./scripts/set_github_secrets.sh

REPO_ARG=""
if [ -n "${GITHUB_REPO:-}" ]; then
  REPO_ARG="--repo $GITHUB_REPO"
fi

ensure_value() {
  local name="$1"
  local val="${!name:-}"
  if [ -z "$val" ]; then
    read -r -p "Enter $name: " val
  fi
  echo "$val"
}

AWS_ACCESS_KEY_ID_VAL=$(ensure_value AWS_ACCESS_KEY_ID)
AWS_SECRET_ACCESS_KEY_VAL=$(ensure_value AWS_SECRET_ACCESS_KEY)
AWS_REGION_VAL=$(ensure_value AWS_REGION)
AWS_ACCOUNT_ID_VAL=$(ensure_value AWS_ACCOUNT_ID)

echo "Setting secrets in GitHub repository${GITHUB_REPO:+ $GITHUB_REPO}..."
gh secret set AWS_ACCESS_KEY_ID $REPO_ARG --body "$AWS_ACCESS_KEY_ID_VAL"
gh secret set AWS_SECRET_ACCESS_KEY $REPO_ARG --body "$AWS_SECRET_ACCESS_KEY_VAL"
gh secret set AWS_REGION $REPO_ARG --body "$AWS_REGION_VAL"
gh secret set AWS_ACCOUNT_ID $REPO_ARG --body "$AWS_ACCOUNT_ID_VAL"

echo "All secrets set. You can now trigger the workflow manually or via gh workflow run."
