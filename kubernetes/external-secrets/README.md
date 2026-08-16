External Secrets setup and IRSA guidance

This document explains how to install External Secrets Operator (ESO), create the AWS IAM role for IRSA, and populate AWS Secrets Manager with the MongoDB secret used by the cluster.

1) Install External Secrets Operator (recommended via Helm)

```bash
# add helm repo (if not already)
helm repo add external-secrets https://external-secrets.github.io/kubernetes-external-secrets/
helm repo update

# install into namespace external-secrets
kubectl create ns external-secrets || true
helm upgrade --install external-secrets external-secrets/kubernetes-external-secrets \
  --namespace external-secrets \
  --set installCRDs=true
```

2) IRSA (recommended) — create IAM role for ESO with SecretsManager read access

We provide a helper script at `scripts/create_iam_role_for_external_secrets.sh` that:
- creates an IAM policy allowing `secretsmanager:GetSecretValue`, `DescribeSecret`, `ListSecrets` for the needed secret prefix
- creates an IAM role with a trust policy scoped to the EKS OIDC provider and the ESO service account
- prints the role ARN so you can annotate the ESO `ServiceAccount` with `eks.amazonaws.com/role-arn: <ROLE_ARN>`

Usage (example):

```bash
# required variables
AWS_ACCOUNT_ID=123456789012
REGION=ap-south-1
CLUSTER_NAME=shopnow-app-eks
SERVICE_ACCOUNT_NAMESPACE=external-secrets
SERVICE_ACCOUNT_NAME=external-secrets
ROLE_NAME=external-secrets-role

scripts/create_iam_role_for_external_secrets.sh "$AWS_ACCOUNT_ID" "$REGION" "$CLUSTER_NAME" "$SERVICE_ACCOUNT_NAMESPACE" "$SERVICE_ACCOUNT_NAME" "$ROLE_NAME"
```

3) Example ServiceAccount manifest (if you want to manually annotate)

See `kubernetes/external-secrets/external-secrets-sa.yaml` — annotate with the role ARN returned by the script.

4) Populate AWS Secrets Manager

We provide `scripts/create_aws_secret.sh` which creates/updates a secret at `shopnow/mongo` by default. Run:

```bash
# create or update the secret
scripts/create_aws_secret.sh shopnow/mongo ap-south-1
```

5) Deploy ExternalSecret in the `shopnow-ns` namespace

The repo contains `kubernetes/k8s-manifests/database/mongo-secret-externalsecret.yaml` which expects the SecretStore named `aws-secret-store` in `shopnow-ns` to be configured to access AWS Secrets Manager.

6) Clean up the committed plaintext secret

We removed the plaintext `mongo-secret.yaml` from the main manifests; do not reintroduce secrets in git.

7) Verify ExternalSecret sync (quick check)

After you create the AWS secret and apply the `ExternalSecret`, verify the operator synced the secret into Kubernetes:

```bash
# wait for the k8s secret to appear (timeout 120s)
kubectl -n shopnow-ns wait --for=condition=available --timeout=120s secret/mongo-secret || true

# print the MONGODB_URI value (base64 decoded)
kubectl get secret mongo-secret -n shopnow-ns -o jsonpath='{.data.MONGODB_URI}' | base64 --decode
```

If the value is empty, check the ExternalSecret controller logs in the `external-secrets` namespace and ensure the SecretStore authentication is configured correctly (IRSA or `aws-creds` secret).
