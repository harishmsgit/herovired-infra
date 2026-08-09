#!/usr/bin/env bash
set -euo pipefail

# Creates an IAM role and inline policy for External Secrets Operator (ESO) to read Secrets Manager secrets
# Usage:
# scripts/create_iam_role_for_external_secrets.sh <aws-account-id> <region> <eks-cluster-name> <sa-namespace> <sa-name> <role-name>

AWS_ACCOUNT_ID=${1:?}
REGION=${2:-ap-south-1}
CLUSTER_NAME=${3:?}
SA_NAMESPACE=${4:-external-secrets}
SA_NAME=${5:-external-secrets}
ROLE_NAME=${6:-external-secrets-role}

# Get OIDC provider URL
OIDC_PROVIDER=$(aws eks describe-cluster --name "$CLUSTER_NAME" --region "$REGION" --query "cluster.identity.oidc.issuer" --output text)
if [ -z "$OIDC_PROVIDER" ]; then
  echo "Could not find OIDC provider for cluster $CLUSTER_NAME in $REGION"
  exit 1
fi

# Remove https:// prefix for provider ARN
OIDC_URL=${OIDC_PROVIDER#https://}

# Build trust policy allowing the service account to assume the role via web identity
TRUST_POLICY=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {"Federated": "arn:aws:iam::${AWS_ACCOUNT_ID}:oidc-provider/${OIDC_URL}"},
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "${OIDC_URL}:sub": "system:serviceaccount:${SA_NAMESPACE}:${SA_NAME}"
        }
      }
    }
  ]
}
EOF
)

echo "Creating IAM role ${ROLE_NAME} with trust policy for ${SA_NAMESPACE}/${SA_NAME}..."
ROLE_ARN=$(aws iam create-role --role-name "$ROLE_NAME" --assume-role-policy-document "$TRUST_POLICY" --query 'Role.Arn' --output text --region "$REGION" 2>/dev/null || true)
if [ -z "$ROLE_ARN" ]; then
  echo "Role may already exist; getting existing role ARN..."
  ROLE_ARN=$(aws iam get-role --role-name "$ROLE_NAME" --query 'Role.Arn' --output text --region "$REGION")
fi

echo "Role ARN: $ROLE_ARN"

# Create inline policy allowing Secrets Manager read for the shopnow prefix
POLICY_NAME="${ROLE_NAME}-secrets-read"
POLICY_DOC=$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret",
        "secretsmanager:ListSecrets"
      ],
      "Resource": ["arn:aws:secretsmanager:${REGION}:${AWS_ACCOUNT_ID}:secret:shopnow/*","arn:aws:secretsmanager:${REGION}:${AWS_ACCOUNT_ID}:secret:shopnow*"]
    }
  ]
}
EOF
)

echo "Putting inline policy ${POLICY_NAME} on role ${ROLE_NAME}..."
aws iam put-role-policy --role-name "$ROLE_NAME" --policy-name "$POLICY_NAME" --policy-document "$POLICY_DOC" --region "$REGION"

echo "Done. Annotate the External Secrets Operator ServiceAccount with the role ARN:"
cat <<EOF
kubectl -n ${SA_NAMESPACE} annotate serviceaccount ${SA_NAME} eks.amazonaws.com/role-arn=${ROLE_ARN} --overwrite
EOF

echo "If your cluster does not yet have an OIDC provider, follow EKS docs to create one before using IRSA."
