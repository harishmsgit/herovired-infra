# Shared Kubernetes

Place shared Kubernetes manifests and overlays here.



## Install External Secrets Operator:
kubectl create ns external-secrets || true
helm repo add external-secrets https://external-secrets.github.io/kubernetes-external-secrets/
helm repo update
helm upgrade --install external-secrets external-secrets/kubernetes-external-secrets --namespace external-secrets --set installCRDs=true

# Create IAM role for ESO (example):
scripts/create_iam_role_for_external_secrets.sh 123456789012 ap-south-1 shopnow-app-eks external-secrets external-secrets external-secrets-role
# then annotate the service account (script prints the command)

# Bootstrap the MongoDB secret in AWS Secrets Manager:
scripts/create_aws_secret.sh shopnow/mongo ap-south-1

# Deploy SecretStore/ExternalSecret (adjust region/auth):
kubectl apply -f kubernetes/k8s-manifests/database/aws-secretstore.yaml
kubectl apply -f kubernetes/k8s-manifests/database/mongo-secret-externalsecret.yaml -n shopnow-ns



