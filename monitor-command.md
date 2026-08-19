# ShopNow infrastructure verification runbook

This runbook verifies the complete delivery path without printing credentials or secret values:

GitHub push -> Jenkins -> Terraform/AWS -> EKS -> External Secrets -> workloads

Run the Bash commands from Jenkins, the management host, or a trusted machine with Git, AWS CLI, Terraform, kubectl, and Helm installed. Do not paste access keys, Jenkins credentials, or decoded Kubernetes secret values into logs.

## 1. Set environment variables

~~~bash
cd /path/to/herovired-infra

export AWS_REGION=ap-south-1
export AWS_ACCOUNT_ID=559272000457
export EKS_CLUSTER_NAME=shopnow-app-eks
export K8S_NAMESPACE=shopnow-ns
export ESO_RELEASE=external-secrets
export TF_STATE_BUCKET=harish-pc-s3-bucket
export LOCK_TABLE=shopnow-terraform-locks
~~~

Windows PowerShell:

~~~powershell
Set-Location 'D:\Capstone-projects\Harish-Final-Capstone\herovired-infra'
$env:AWS_REGION = 'ap-south-1'
$env:EKS_CLUSTER_NAME = 'shopnow-app-eks'
$env:K8S_NAMESPACE = 'shopnow-ns'
~~~

## 2. Git, webhook, and Jenkins

~~~bash
git status --short
git remote -v
git branch --show-current
git fetch origin feature/infra-capstone-project-v1
git log --oneline -5
git rev-parse HEAD
git rev-parse origin/feature/infra-capstone-project-v1
git log -1 --format='%H%n%s' origin/feature/infra-capstone-project-v1
~~~

The latest remote SHA must match Jenkins's 'Checking out Revision' line. In GitHub, check Settings -> Webhooks -> Recent Deliveries. The ngrok terminal must show:

~~~text
POST /github-webhook/ 200
~~~

## 3. AWS identity, state backend, and secret metadata

~~~bash
aws sts get-caller-identity --region "$AWS_REGION"
aws configure list

aws s3api head-bucket --bucket "$TF_STATE_BUCKET" --region "$AWS_REGION"
aws dynamodb describe-table --table-name "$LOCK_TABLE" --region "$AWS_REGION" \
  --query 'Table.{Name:TableName,Status:TableStatus,ItemCount:ItemCount,BillingMode:BillingModeSummary.BillingMode}' \
  --output table
aws dynamodb scan --table-name "$LOCK_TABLE" --region "$AWS_REGION" --output json

# Prints metadata only; it never returns SecretString.
aws secretsmanager describe-secret --secret-id shopnow/mongo --region "$AWS_REGION" \
  --query '{Name:Name,ARN:ARN,LastChangedDate:LastChangedDate,VersionIdsToStages:VersionIdsToStages}' \
  --output json
~~~

Never delete a DynamoDB lock while a Jenkins Terraform build may still be running.

## 4. Terraform configuration and remote state

Do not run 'terraform apply' locally while Jenkins may be applying the same state.

~~~bash
cd terraform
terraform fmt -check
terraform init -reconfigure \
  -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config="key=terraform/terraform.tfstate" \
  -backend-config="region=$AWS_REGION" \
  -backend-config="dynamodb_table=$LOCK_TABLE"
terraform workspace select dev
terraform validate
terraform state list | sort
terraform state show aws_eks_cluster.main
terraform state show aws_eks_node_group.main
terraform state show helm_release.external_secrets
terraform plan \
  -var="aws_region=$AWS_REGION" \
  -var="cluster_name=$EKS_CLUSTER_NAME" \
  -var="ecr_repo_prefix=shopnow-dev"
cd ..
~~~

The existing Helm release must be managed as 'helm_release.external_secrets'. If it exists in the cluster but not Terraform state, import it only when no Jenkins build owns the lock:

~~~bash
cd terraform
terraform import helm_release.external_secrets shopnow-ns/external-secrets
~~~

## 5. AWS infrastructure checks

~~~bash
aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" \
  --query 'cluster.{Name:name,Status:status,Version:version,Endpoint:endpoint,OIDCIssuer:identity.oidc.issuer}' \
  --output table

aws eks describe-nodegroup --cluster-name "$EKS_CLUSTER_NAME" --nodegroup-name dev-shopnow-nodes --region "$AWS_REGION" \
  --query 'nodegroup.{Status:status,Version:version,Desired:scalingConfig.desiredSize,Min:scalingConfig.minSize,Max:scalingConfig.maxSize,Instances:instanceTypes}' \
  --output table

aws ec2 describe-vpcs --vpc-ids vpc-02d6c8773f62350e4 --region "$AWS_REGION" \
  --query 'Vpcs[0].{VpcId:VpcId,Cidr:CidrBlock,State:State}' --output table
aws ec2 describe-subnets --subnet-ids subnet-088ea34a6270f1000 subnet-04b729189cb399e38 --region "$AWS_REGION" \
  --query 'Subnets[*].{SubnetId:SubnetId,Cidr:CidrBlock,AZ:AvailabilityZone,State:State}' --output table
aws ec2 describe-route-tables --route-table-ids rtb-066d960926aaed2e9 --region "$AWS_REGION" --output json

aws ec2 describe-instances --instance-ids i-05988c1864c27c07f --region "$AWS_REGION" \
  --query 'Reservations[].Instances[].{Id:InstanceId,State:State.Name,PrivateIP:PrivateIpAddress,PublicIP:PublicIpAddress,Profile:IamInstanceProfile.Arn}' \
  --output table
aws ssm describe-instance-information --region "$AWS_REGION" \
  --filters "Key=InstanceIds,Values=i-05988c1864c27c07f" --output table
~~~

Verify ECR image metadata:

~~~bash
for repository in frontend admin backend; do
  aws ecr describe-images --repository-name "shopnow-dev/$repository" --region "$AWS_REGION" \
    --image-ids imageTag=manual \
    --query 'imageDetails[0].{Repository:repositoryName,Tags:imageTags,Digest:imageDigest,Pushed:imagePushedAt}' \
    --output table
done
~~~

## 6. EKS access and Kubernetes control plane

Use a temporary kubeconfig so this check does not overwrite your normal local configuration:

~~~bash
export KUBECONFIG="$(mktemp)"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" --kubeconfig "$KUBECONFIG"

kubectl cluster-info
kubectl get nodes -o wide
kubectl get pods -n kube-system -o wide
kubectl get events -A --sort-by='.lastTimestamp' | tail -n 50
kubectl auth can-i get pods -n "$K8S_NAMESPACE"
kubectl auth can-i get secrets -n "$K8S_NAMESPACE"
~~~

For unhealthy cluster resources:

~~~bash
kubectl describe node <node-name>
kubectl describe pod <pod-name> -n kube-system
~~~

## 7. Helm, IRSA, CRDs, and External Secrets Operator

~~~bash
helm list --all-namespaces
helm status "$ESO_RELEASE" --namespace "$K8S_NAMESPACE"

kubectl get deployment external-secrets -n "$K8S_NAMESPACE"
kubectl rollout status deployment/external-secrets -n "$K8S_NAMESPACE" --timeout=5m
kubectl get pods -n "$K8S_NAMESPACE" -l app.kubernetes.io/instance="$ESO_RELEASE" -o wide
kubectl logs deployment/external-secrets -n "$K8S_NAMESPACE" --tail=100

kubectl get serviceaccount shopnow-external-secrets -n "$K8S_NAMESPACE" \
  -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}{"\n"}'
aws iam get-role --role-name dev-shopnow-external-secrets-role --output json
aws iam get-role-policy --role-name dev-shopnow-external-secrets-role \
  --policy-name dev-shopnow-external-secrets-read --output json
~~~

Verify stable API versions and Helm ownership:

~~~bash
for crd in secretstores.external-secrets.io externalsecrets.external-secrets.io; do
  echo "== $crd =="
  kubectl get crd "$crd" -o jsonpath='{range .spec.versions[?(@.served==true)]}{.name}{"\n"}{end}'
done

kubectl get validatingwebhookconfiguration secretstore-validate \
  -o jsonpath='{.metadata.annotations.meta\.helm\.sh/release-name}{" "}{.metadata.annotations.meta\.helm\.sh/release-namespace}{"\n"}'
~~~

Expected: the CRDs serve v1; webhook ownership is external-secrets shopnow-ns.

## 8. ExternalSecret synchronization

These commands check status and key names only; they do not decode MONGODB_URI.

~~~bash
kubectl get secretstore aws-secret-store -n "$K8S_NAMESPACE"
kubectl describe secretstore aws-secret-store -n "$K8S_NAMESPACE"
kubectl get externalsecret mongo-secret -n "$K8S_NAMESPACE"
kubectl describe externalsecret mongo-secret -n "$K8S_NAMESPACE"
kubectl wait --for=condition=Ready externalsecret/mongo-secret -n "$K8S_NAMESPACE" --timeout=120s

kubectl get secret mongo-secret -n "$K8S_NAMESPACE"
kubectl get secret mongo-secret -n "$K8S_NAMESPACE" \
  -o go-template='{{range $key, $value := .data}}{{println $key}}{{end}}'
kubectl get events -n "$K8S_NAMESPACE" --sort-by='.lastTimestamp' | tail -n 50
~~~

The key list must include MONGODB_URI.

## 9. Application, service, ingress, and monitoring

~~~bash
kubectl get deployments,pods,replicasets -n "$K8S_NAMESPACE" -o wide
kubectl get services,endpoints,ingress -n "$K8S_NAMESPACE" -o wide
kubectl rollout status deployment/mongo -n "$K8S_NAMESPACE" --timeout=5m
kubectl rollout status deployment/frontend -n "$K8S_NAMESPACE" --timeout=5m
kubectl rollout status deployment/admin -n "$K8S_NAMESPACE" --timeout=5m
kubectl rollout status deployment/backend -n "$K8S_NAMESPACE" --timeout=5m
kubectl get events -n "$K8S_NAMESPACE" --sort-by='.lastTimestamp' | tail -n 100

# Public application access: resolve the actual NGINX/EKS load-balancer address.
export APP_BASE_PATH=shopnow
export INGRESS_HOST="$(kubectl get ingress shopnow-ingress -n "$K8S_NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')"
if [ -z "$INGRESS_HOST" ]; then
  export INGRESS_HOST="$(kubectl get ingress shopnow-ingress -n "$K8S_NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
fi
test -n "$INGRESS_HOST" || { echo 'Ingress has no public address yet; run: kubectl get ingress -n shopnow-ns -w'; exit 1; }

export SHOPNOW_FRONTEND_URL="http://${INGRESS_HOST}/${APP_BASE_PATH}/"
export SHOPNOW_ADMIN_URL="http://${INGRESS_HOST}/${APP_BASE_PATH}/admin/"
export SHOPNOW_API_HEALTH_URL="http://${INGRESS_HOST}/${APP_BASE_PATH}/api/health"
printf 'Frontend: %s\nAdmin:    %s\nAPI:      %s\n' "$SHOPNOW_FRONTEND_URL" "$SHOPNOW_ADMIN_URL" "$SHOPNOW_API_HEALTH_URL"

# HTTP 200/3xx verifies the React apps; the API must return JSON with status OK.
curl -fsSIL "$SHOPNOW_FRONTEND_URL"
curl -fsSIL "$SHOPNOW_ADMIN_URL"
curl -fsS "$SHOPNOW_API_HEALTH_URL"; echo

export MONITORING_NAMESPACE=monitor-ns
kubectl get pods,services -n "$MONITORING_NAMESPACE" -o wide
kubectl get servicemonitor,prometheusrule -n "$MONITORING_NAMESPACE" || true
kubectl get events -n "$MONITORING_NAMESPACE" --sort-by='.lastTimestamp' | tail -n 50
~~~

The expected public URLs are `http://<ingress-address>/shopnow/`,
`http://<ingress-address>/shopnow/admin/`, and
`http://<ingress-address>/shopnow/api/health`. Use the printed values rather than
guessing the AWS load-balancer address.

### Current deployment URLs (19 August 2026)

The current public load balancer is
`a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com`:

~~~text
http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/
http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/admin/
http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/api/health
~~~

AWS can assign a new address if this load balancer is replaced, so use the
dynamic command block above as the source of truth after any infrastructure change.

### Application API, order activity, deployment, and infrastructure checks

Run this exact block from the management EC2, Jenkins agent, or an authenticated
operator workstation. It uses the live ShopNow routes and omits customer PII,
MongoDB credentials, and Kubernetes secret values.

~~~bash
# Required once per shell. The management host role has exactly the read and
# port-forward permissions required by these commands.
export AWS_REGION=ap-south-1
export EKS_CLUSTER_NAME=shopnow-app-eks
export K8S_NAMESPACE=shopnow-ns
export APP_BASE_PATH=shopnow
export INGRESS_HOST=a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com
export SHOPNOW_API_URL="http://${INGRESS_HOST}/${APP_BASE_PATH}/api"
export KUBECONFIG="$(mktemp)"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" --kubeconfig "$KUBECONFIG"

echo '=== Application API health and order summary ==='
curl -fsS "$SHOPNOW_API_URL/health"; echo
# Shows aggregate order totals only.
curl -fsS "$SHOPNOW_API_URL/analytics/dashboard" | jq .
# Shows recent orders without customer name, phone, email, address, or token.
curl -fsS "$SHOPNOW_API_URL/invoices?limit=10" |
  jq '{total, currentPage, totalPages, invoices: [.invoices[] | {invoiceNumber, status, paymentStatus, total, createdAt, itemCount: (.items | length)}]}'

echo '=== Backend application logs (startup, database, and errors) ==='
kubectl logs deployment/backend -n "$K8S_NAMESPACE" --all-containers --tail=200

echo '=== API request evidence at the ingress ==='
# Recent requests sent to the ShopNow API, including HTTP method and status.
kubectl logs deployment/ingress-nginx-controller -n ingress-nginx --tail=500 |
  grep --line-buffered "/${APP_BASE_PATH}/api"
# Recent real order-create requests. Do not send a test POST: it creates an
# invoice and decrements stock.
kubectl logs deployment/ingress-nginx-controller -n ingress-nginx --tail=500 |
  grep --line-buffered "POST /${APP_BASE_PATH}/api/invoices"

echo '=== Frontend, admin, backend, and Mongo logs ==='
kubectl logs deployment/frontend -n "$K8S_NAMESPACE" --all-containers --tail=200
kubectl logs deployment/admin -n "$K8S_NAMESPACE" --all-containers --tail=200
kubectl logs deployment/mongo -n "$K8S_NAMESPACE" --all-containers --tail=200

echo '=== Deployment rollout and pod diagnosis ==='
kubectl get deployment frontend admin backend mongo -n "$K8S_NAMESPACE" -o wide
kubectl rollout status deployment/frontend -n "$K8S_NAMESPACE" --timeout=5m
kubectl rollout status deployment/admin -n "$K8S_NAMESPACE" --timeout=5m
kubectl rollout status deployment/backend -n "$K8S_NAMESPACE" --timeout=5m
kubectl get pods -n "$K8S_NAMESPACE" -l 'app in (frontend,admin,backend,mongo)' -o wide
kubectl get replicaset -n "$K8S_NAMESPACE" -o wide
kubectl get events -n "$K8S_NAMESPACE" --sort-by='.lastTimestamp' | tail -n 100
# Describe the active backend pod and show the previous container log if it restarted.
export BACKEND_POD="$(kubectl get pods -n "$K8S_NAMESPACE" -l app=backend -o jsonpath='{.items[0].metadata.name}')"
test -n "$BACKEND_POD" && kubectl describe pod "$BACKEND_POD" -n "$K8S_NAMESPACE"
test -n "$BACKEND_POD" && kubectl logs "$BACKEND_POD" -n "$K8S_NAMESPACE" --all-containers --previous --tail=200 || true

echo '=== Ingress and AWS infrastructure ==='
kubectl get ingress -n "$K8S_NAMESPACE" -o wide
kubectl get service ingress-nginx-controller -n ingress-nginx -o wide
kubectl get deployment ingress-nginx-controller -n ingress-nginx -o wide
aws eks describe-cluster --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" \
  --query 'cluster.{Status:status,Version:version,Endpoint:endpoint,PublicAccess:resourcesVpcConfig.endpointPublicAccess}' \
  --output table
aws eks describe-nodegroup --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME" --nodegroup-name dev-shopnow-nodes \
  --query 'nodegroup.{Status:status,Desired:scalingConfig.desiredSize,Min:scalingConfig.minSize,Max:scalingConfig.maxSize,Instances:instanceTypes}' \
  --output table
aws eks describe-nodegroup --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME" --nodegroup-name dev-shopnow-workloads \
  --query 'nodegroup.{Status:status,Desired:scalingConfig.desiredSize,Min:scalingConfig.minSize,Max:scalingConfig.maxSize,Instances:instanceTypes}' \
  --output table
aws elb describe-load-balancers --region "$AWS_REGION" \
  --load-balancer-names a2d7eee8d8179427fa36d881be68d64a \
  --query 'LoadBalancerDescriptions[0].{Name:LoadBalancerName,DNS:DNSName,Scheme:Scheme,Subnets:Subnets,Instances:Instances[*].InstanceId}' \
  --output json
aws elb describe-instance-health --region "$AWS_REGION" \
  --load-balancer-name a2d7eee8d8179427fa36d881be68d64a \
  --query 'InstanceStates[*].{InstanceId:InstanceId,State:State,Reason:ReasonCode,Description:Description}' \
  --output table
~~~

Run one of these live streams at a time; each command keeps the terminal open
until you press `Ctrl+C`:

~~~bash
# Backend application events and errors.
kubectl logs -f deployment/backend -n shopnow-ns --all-containers --tail=100

# Every API request through the public gateway.
kubectl logs -f deployment/ingress-nginx-controller -n ingress-nginx --tail=0 |
  grep --line-buffered '/shopnow/api'

# Only order placement requests.
kubectl logs -f deployment/ingress-nginx-controller -n ingress-nginx --tail=0 |
  grep --line-buffered 'POST /shopnow/api/invoices'
~~~

The backend currently writes startup, database, and error events to its pod
logs. Normal API and order-create requests are verified from the NGINX ingress
access log, while the invoice summary confirms a completed order without
printing customer information. Add structured backend request/order audit logs
before relying on pod logs as a full audit trail.

Investigate an unhealthy workload without exposing secrets:

~~~bash
kubectl describe deployment <deployment-name> -n "$K8S_NAMESPACE"
kubectl describe pod <pod-name> -n "$K8S_NAMESPACE"
kubectl logs <pod-name> -n "$K8S_NAMESPACE" --all-containers --tail=200
kubectl logs <pod-name> -n "$K8S_NAMESPACE" --all-containers --previous --tail=200
~~~

## 10. Failure-specific checks

### Instant External Secrets diagnosis (actual values)

Run this exact block from the Jenkins agent, management host, or a machine already authenticated to AWS. It uses the real project values and prints no secret value:

~~~bash
export KUBECONFIG="$(mktemp)"
aws eks update-kubeconfig --region ap-south-1 --name shopnow-app-eks --kubeconfig "$KUBECONFIG"

echo '=== Cluster and nodes ==='
kubectl get nodes -o wide

echo '=== External Secrets Helm release ==='
helm status external-secrets -n shopnow-ns

echo '=== External Secrets deployment and pods ==='
kubectl get deployment,pods -n shopnow-ns -o wide
kubectl describe deployment external-secrets -n shopnow-ns
kubectl logs deployment/external-secrets -n shopnow-ns --all-containers --tail=200

echo '=== Recent events (most important failure output) ==='
kubectl get events -n shopnow-ns --sort-by='.lastTimestamp' | tail -n 100

echo '=== IRSA service account and AWS role ==='
kubectl get serviceaccount shopnow-external-secrets -n shopnow-ns \
  -o jsonpath='{.metadata.annotations.eks\.amazonaws\.com/role-arn}{"\n"}'
aws iam get-role --role-name dev-shopnow-external-secrets-role \
  --query 'Role.AssumeRolePolicyDocument' --output json
aws iam get-role-policy --role-name dev-shopnow-external-secrets-role \
  --policy-name dev-shopnow-external-secrets-read --output json

echo '=== ExternalSecret status (does not show the MongoDB URI) ==='
kubectl describe secretstore aws-secret-store -n shopnow-ns
kubectl describe externalsecret mongo-secret -n shopnow-ns
kubectl get secret mongo-secret -n shopnow-ns \
  -o go-template='{{range $key, $value := .data}}{{println $key}}{{end}}' 2>/dev/null || true
~~~

Copy the output beginning with `=== External Secrets deployment and pods ===` and `=== Recent events` if a command fails.

### Jenkins checked out an older commit

~~~bash
git ls-remote origin refs/heads/feature/infra-capstone-project-v1
~~~

Compare this SHA to Jenkins's 'Checking out Revision'. Rescan the Jenkins branch/job if they differ.

### EKS credential failure

~~~bash
aws eks get-token --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME" >/dev/null && echo 'EKS token request succeeded'
kubectl get nodes
~~~

### Helm CRD or webhook ownership error

~~~bash
helm status external-secrets -n shopnow-ns
cd terraform && terraform state show helm_release.external_secrets
~~~

Do not install a second External Secrets release under another namespace or name.

### ExternalSecret not Ready

~~~bash
kubectl describe externalsecret mongo-secret -n "$K8S_NAMESPACE"
kubectl describe secretstore aws-secret-store -n "$K8S_NAMESPACE"
kubectl logs deployment/external-secrets -n "$K8S_NAMESPACE" --tail=200
aws secretsmanager describe-secret --secret-id shopnow/mongo --region "$AWS_REGION" --output json
~~~

### AWS endpoint timeout

~~~bash
aws sts get-caller-identity --region "$AWS_REGION"
aws ecr describe-repositories --region "$AWS_REGION" --max-results 1
aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" --query 'cluster.status' --output text
~~~

## 11. End-to-end acceptance check

~~~bash
git rev-parse origin/feature/infra-capstone-project-v1
aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" --query 'cluster.status' --output text
kubectl get nodes
helm status "$ESO_RELEASE" -n "$K8S_NAMESPACE"
kubectl wait --for=condition=Ready externalsecret/mongo-secret -n "$K8S_NAMESPACE" --timeout=120s
kubectl get secret mongo-secret -n "$K8S_NAMESPACE"
kubectl get pods -n "$K8S_NAMESPACE"
kubectl get ingress -n "$K8S_NAMESPACE"
kubectl get pods -n monitor-ns
~~~

The environment passes when Jenkins checks out the latest SHA, EKS nodes are Ready, the ExternalSecret is Ready, mongo-secret has MONGODB_URI, selected workloads complete rollout, and recent events show no unresolved failures.
