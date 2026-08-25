#!/usr/bin/env bash
set -euo pipefail

# Usage: scripts/create_aws_secret.sh [secret-name] [region]
# Example: scripts/create_aws_secret.sh shopnow/mongo ap-south-1

SECRET_NAME=${1:-shopnow/mongo}
REGION=${2:-ap-south-1}

: "${MONGO_INITDB_ROOT_USERNAME:?Set MONGO_INITDB_ROOT_USERNAME before running this script}"
: "${MONGO_INITDB_ROOT_PASSWORD:?Set MONGO_INITDB_ROOT_PASSWORD before running this script}"
: "${MONGODB_URI:?Set MONGODB_URI before running this script}"

SECRET_FILE=$(mktemp)
trap 'rm -f "$SECRET_FILE"' EXIT

jq -n \
  --arg username "$MONGO_INITDB_ROOT_USERNAME" \
  --arg password "$MONGO_INITDB_ROOT_PASSWORD" \
  --arg uri "$MONGODB_URI" \
  '{
    MONGO_INITDB_ROOT_USERNAME: $username,
    MONGO_INITDB_ROOT_PASSWORD: $password,
    MONGODB_URI: $uri
  }' > "$SECRET_FILE"

if aws secretsmanager describe-secret --secret-id "$SECRET_NAME" --region "$REGION" >/dev/null 2>&1; then
  echo "Updating existing secret ${SECRET_NAME} in ${REGION}..."
  aws secretsmanager put-secret-value --secret-id "$SECRET_NAME" --region "$REGION" --secret-string "file://${SECRET_FILE}"
else
  echo "Creating secret ${SECRET_NAME} in ${REGION}..."
  aws secretsmanager create-secret --name "$SECRET_NAME" --region "$REGION" --secret-string "file://${SECRET_FILE}"
fi

echo "Secret ${SECRET_NAME} created/updated in ${REGION}."
