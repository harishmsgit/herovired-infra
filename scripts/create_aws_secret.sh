#!/usr/bin/env bash
set -euo pipefail

# Usage: scripts/create_aws_secret.sh [secret-name] [region]
# Example: scripts/create_aws_secret.sh shopnow/mongo ap-south-1

SECRET_NAME=${1:-shopnow/mongo}
REGION=${2:-ap-south-1}

cat > /tmp/mongo-secret.json <<EOF
{
  "MONGO_INITDB_ROOT_USERNAME": "shopuser",
  "MONGO_INITDB_ROOT_PASSWORD": "ShopNowPass123",
  "MONGODB_URI": "mongodb://shopuser:ShopNowPass123@mongo:27017/shopnow?authSource=admin"
}
EOF

if aws secretsmanager describe-secret --secret-id "$SECRET_NAME" --region "$REGION" >/dev/null 2>&1; then
  echo "Updating existing secret ${SECRET_NAME} in ${REGION}..."
  aws secretsmanager put-secret-value --secret-id "$SECRET_NAME" --region "$REGION" --secret-string file:///tmp/mongo-secret.json
else
  echo "Creating secret ${SECRET_NAME} in ${REGION}..."
  aws secretsmanager create-secret --name "$SECRET_NAME" --region "$REGION" --secret-string file:///tmp/mongo-secret.json
fi

echo "Secret ${SECRET_NAME} created/updated in ${REGION}."
