# ShopNow infrastructure commands and queries

This is the team command reference for the live ShopNow development environment. Run Bash commands from the Jenkins agent, the management EC2 host, or an approved operator workstation. Never print access keys, decoded Kubernetes secrets, or the MongoDB URI.

## Live environment

| Item | Value |
| --- | --- |
| AWS account / region | 559272000457 / ap-south-1 |
| EKS cluster / version | shopnow-app-eks / 1.36 |
| Kubernetes namespace | shopnow-ns |
| VPC | vpc-02d6c8773f62350e4 |
| Public subnets | subnet-088ea34a6270f1000, subnet-04b729189cb399e38 |
| Terraform S3 state bucket | harish-pc-s3-bucket |
| Terraform lock table | shopnow-terraform-locks |
| Management host | i-05988c1864c27c07f / 13.201.136.220 |
| Public ELB | a2d7eee8d8179427fa36d881be68d64a |
| ELB DNS name | a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com |
| Infra job / verified build | herovired-infra-services / 52 |

## 1. Safe operator context

~~~bash
cd /path/to/herovired-infra
export AWS_REGION=ap-south-1
export AWS_ACCOUNT_ID=559272000457
export EKS_CLUSTER_NAME=shopnow-app-eks
export K8S_NAMESPACE=shopnow-ns
export TF_STATE_BUCKET=harish-pc-s3-bucket
export LOCK_TABLE=shopnow-terraform-locks
export MANAGEMENT_INSTANCE_ID=i-05988c1864c27c07f
export INGRESS_HOST=a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com
export INGRESS_LB_NAME=a2d7eee8d8179427fa36d881be68d64a
export KUBECONFIG="$(mktemp)"

aws sts get-caller-identity --region "$AWS_REGION"
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" --kubeconfig "$KUBECONFIG"
kubectl cluster-info
~~~

## 2. AWS, VPC, EC2, and EKS

~~~bash
aws eks describe-cluster --region ap-south-1 --name shopnow-app-eks --query 'cluster.{Name:name,Status:status,Version:version,Endpoint:endpoint,AuthMode:accessConfig.authenticationMode,PublicEndpoint:resourcesVpcConfig.endpointPublicAccess,VpcId:resourcesVpcConfig.vpcId,Subnets:resourcesVpcConfig.subnetIds}' --output table

aws ec2 describe-vpcs --region ap-south-1 --vpc-ids vpc-02d6c8773f62350e4 --query 'Vpcs[0].{VpcId:VpcId,Cidr:CidrBlock,State:State}' --output table
aws ec2 describe-subnets --region ap-south-1 --subnet-ids subnet-088ea34a6270f1000 subnet-04b729189cb399e38 --query 'Subnets[*].{SubnetId:SubnetId,Cidr:CidrBlock,AZ:AvailabilityZone,PublicIpOnLaunch:MapPublicIpOnLaunch,State:State}' --output table
aws ec2 describe-instances --region ap-south-1 --instance-ids i-05988c1864c27c07f --query 'Reservations[0].Instances[0].{Id:InstanceId,State:State.Name,Type:InstanceType,PublicIp:PublicIpAddress,PrivateIp:PrivateIpAddress,Subnet:SubnetId,Profile:IamInstanceProfile.Arn}' --output table

aws eks list-nodegroups --region ap-south-1 --cluster-name shopnow-app-eks --output table
aws eks describe-nodegroup --region ap-south-1 --cluster-name shopnow-app-eks --nodegroup-name dev-shopnow-nodes --query 'nodegroup.{Name:nodegroupName,Status:status,InstanceTypes:instanceTypes,Min:scalingConfig.minSize,Desired:scalingConfig.desiredSize,Max:scalingConfig.maxSize,Role:nodeRole}' --output table
aws eks describe-nodegroup --region ap-south-1 --cluster-name shopnow-app-eks --nodegroup-name dev-shopnow-workloads --query 'nodegroup.{Name:nodegroupName,Status:status,InstanceTypes:instanceTypes,Min:scalingConfig.minSize,Desired:scalingConfig.desiredSize,Max:scalingConfig.maxSize,Role:nodeRole}' --output table

kubectl get nodes -o wide
~~~

| Node group | Type | Desired / min / max |
| --- | --- | --- |
| dev-shopnow-nodes | t3.micro | 2 / 2 / 2 |
| dev-shopnow-workloads | t3.small | 2 / 2 / 3 |

Change node type or scaling only in Terraform, then run a reviewed plan through Jenkins. Do not resize an EKS node group manually in the AWS Console.

## 3. Terraform backend, state, plan, and apply

The active workspace is dev; its state path is env:/dev/terraform/terraform.tfstate.

~~~bash
cd terraform
terraform fmt -check
terraform init -reconfigure -input=false -backend-config="bucket=harish-pc-s3-bucket" -backend-config="key=terraform/terraform.tfstate" -backend-config="region=ap-south-1" -backend-config="dynamodb_table=shopnow-terraform-locks"
terraform workspace select dev
terraform validate
terraform state list | sort
terraform plan -input=false -var="aws_region=ap-south-1" -var="cluster_name=shopnow-app-eks" -var="ecr_repo_prefix=shopnow-dev"
~~~

~~~bash
aws s3api head-bucket --bucket harish-pc-s3-bucket --region ap-south-1
aws dynamodb describe-table --table-name shopnow-terraform-locks --region ap-south-1 --query 'Table.{Name:TableName,Status:TableStatus,BillingMode:BillingModeSummary.BillingMode}' --output table
aws dynamodb scan --table-name shopnow-terraform-locks --region ap-south-1 --projection-expression 'LockID, Digest, Info' --output json
~~~

Use Jenkins for normal applies. For a reviewed emergency apply, create and apply the same saved plan; never apply an unreviewed configuration.

~~~bash
terraform plan -input=false -out=tfplan -var="aws_region=ap-south-1" -var="cluster_name=shopnow-app-eks" -var="ecr_repo_prefix=shopnow-dev"
terraform apply tfplan
~~~

Do not use -lock=false, terraform state rm, terraform force-unlock, or manual DynamoDB deletion while a Jenkins Terraform job might be active. The checksum repair helper only removes a confirmed stale checksum:

~~~bash
TF_STATE_BUCKET=harish-pc-s3-bucket LOCK_TABLE=shopnow-terraform-locks STATE_KEY='env:/dev/terraform/terraform.tfstate' AWS_REGION=ap-south-1 bash scripts/repair_terraform_state_digest.sh
~~~

Read its output first. Use --force only after confirming that no Terraform operation is running.

## 4. IAM, management host, and SSM

The management instance profile is dev-shopnow-management-profile; its role is dev-shopnow-management-role. It can discover EKS, observe shopnow-ns, and create a port-forward. It must not read Kubernetes secrets.

~~~bash
aws iam list-attached-role-policies --role-name dev-shopnow-management-role --output table
aws iam list-role-policies --role-name dev-shopnow-management-role --output table
aws iam get-role-policy --role-name dev-shopnow-management-role --policy-name dev-shopnow-management-eks-discovery --output json
aws ssm describe-instance-information --region ap-south-1 --filters "Key=InstanceIds,Values=i-05988c1864c27c07f" --output table
~~~

Run on the management host:

~~~bash
export KUBECONFIG="$(mktemp)"
aws eks update-kubeconfig --region ap-south-1 --name shopnow-app-eks --kubeconfig "$KUBECONFIG"
kubectl auth can-i get pods -n shopnow-ns
kubectl auth can-i create pods --subresource=portforward -n shopnow-ns
kubectl auth can-i get secrets -n shopnow-ns
~~~

Expected results: yes, yes, no.

## 5. Kubernetes, Helm, and External Secrets

~~~bash
kubectl get namespace shopnow-ns ingress-nginx monitor-ns
kubectl get deployment,pods,service,ingress -n shopnow-ns -o wide
kubectl get ingressclass
kubectl get deployment,service,pods -n ingress-nginx -o wide

helm list --all-namespaces
helm status external-secrets --namespace shopnow-ns
helm status ingress-nginx --namespace ingress-nginx

kubectl get secretstore,externalsecret -n shopnow-ns
kubectl describe secretstore aws-secret-store -n shopnow-ns
kubectl describe externalsecret mongo-secret -n shopnow-ns
kubectl wait --for=condition=Ready externalsecret/mongo-secret -n shopnow-ns --timeout=120s
kubectl get secret mongo-secret -n shopnow-ns -o go-template='{{range $key, $value := .data}}{{println $key}}{{end}}'
~~~

| Helm release | Namespace | Chart | Why it exists |
| --- | --- | --- |
| external-secrets | shopnow-ns | external-secrets-2.9.0 | Syncs AWS Secrets Manager data using IRSA |
| ingress-nginx | ingress-nginx | ingress-nginx-4.15.1 | Creates the nginx IngressClass and public AWS load balancer |

Terraform owns both releases. Do not install a duplicate External Secrets release under another name or namespace.

## 6. ECR images and deployed workloads

~~~bash
for repository in frontend admin backend; do
  aws ecr describe-repositories --region ap-south-1 --repository-names "shopnow-dev/$repository" --query 'repositories[0].{Name:repositoryName,Uri:repositoryUri,Mutability:imageTagMutability,ScanOnPush:imageScanningConfiguration.scanOnPush}' --output table
  aws ecr describe-images --region ap-south-1 --repository-name "shopnow-dev/$repository" --image-ids imageTag=25-df7ed225 --query 'imageDetails[0].{Tags:imageTags,Digest:imageDigest,PushedAt:imagePushedAt,Size:imageSizeInBytes}' --output table
done

kubectl get deployment frontend admin backend -n shopnow-ns -o jsonpath='{range .items[*]}{.metadata.name}{"\\t"}{range .spec.template.spec.containers[*]}{.image}{" "}{end}{"\\n"}{end}'
~~~

The current deployed tag is 25-df7ed225. ECR repositories are immutable: build a new tag and deploy its explicit image URI rather than overwriting a tag.

## 7. Ingress, ELB, and endpoint access

~~~bash
kubectl get ingress -n shopnow-ns -o wide
kubectl get service ingress-nginx-controller -n ingress-nginx -o wide
kubectl get deployment ingress-nginx-controller -n ingress-nginx -o wide

aws elb describe-load-balancers --region ap-south-1 --load-balancer-names a2d7eee8d8179427fa36d881be68d64a --query 'LoadBalancerDescriptions[0].{Name:LoadBalancerName,DNS:DNSName,Scheme:Scheme,Subnets:Subnets,SecurityGroups:SecurityGroups,Instances:Instances[*].InstanceId}' --output json
aws elb describe-instance-health --region ap-south-1 --load-balancer-name a2d7eee8d8179427fa36d881be68d64a --query 'InstanceStates[*].{InstanceId:InstanceId,State:State,Reason:ReasonCode,Description:Description}' --output table

curl -fsSIL "http://$INGRESS_HOST/shopnow/"
curl -fsSIL "http://$INGRESS_HOST/shopnow/admin/"
curl -fsS "http://$INGRESS_HOST/shopnow/api/health"; echo
~~~

| Service | Public URL |
| --- | --- |
| Customer portal | http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/ |
| Admin portal | http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/admin/ |
| API health | http://a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com/shopnow/api/health |

The current public endpoint is HTTP only. Before production customer traffic, add a domain, ACM certificate, HTTPS listener, and HTTP-to-HTTPS redirect.

## 8. Ansible, Docker, Jenkins, webhook, and scripts

Ansible runs only when RUN_ANSIBLE_AFTER_APPLY=true:

~~~bash
ansible-playbook -i ansible/inventories/generated/hosts.ini ansible/playbooks/validate-management.yml
ansible-playbook -i ansible/inventories/generated/hosts.ini ansible/playbooks/configure-management.yml
~~~

Do not edit ansible/inventories/generated/hosts.ini; scripts/generate_ansible_inventory.py generates it from Terraform outputs.

~~~bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
docker logs --tail=100 jenkins
docker logs --tail=100 jenkins-docker
~~~

Jenkins console references:

~~~text
http://localhost:8080/job/shopnow-service-dev/25/console
http://localhost:8080/job/herovired-infra-services/52/console
~~~

The GitHub webhook targets Jenkins through ngrok at /github-webhook/. The free ngrok hostname is temporary; retrieve it from the running ngrok session or http://127.0.0.1:4040 on the Jenkins host.

| Repository component | Purpose |
| --- | --- |
| scripts/ensure_tf_state_backend.sh | Checks or creates Terraform backend storage |
| scripts/repair_terraform_state_digest.sh | Repairs a confirmed stale state checksum only |
| scripts/create_aws_secret.sh | Creates or updates the shopnow/mongo secret during the approved pipeline stage |
| scripts/generate_ansible_inventory.py | Generates Ansible inventory from Terraform output |
| scripts/ensure_ansible.sh | Installs required Ansible tooling on the Jenkins agent |
| scripts/build-and-push.sh | Manual image helper; normal delivery uses ShopNow Jenkins |

## 9. Triage order

1. Check Jenkins console and checked-out commit SHA.
2. Check AWS identity, EKS cluster state, and node groups.
3. Check Kubernetes nodes, workloads, and recent namespace events.
4. Confirm the ExternalSecret is Ready before investigating MongoDB failures.
5. Confirm deployed ECR image tags and rollout status.
6. Check NGINX, Ingress, ELB instance health, then public API health.
7. Share only non-sensitive console output, events, and logs.
