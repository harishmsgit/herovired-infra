# HeroVired Infrastructure

HeroVired Infrastructure is the cloud and DevOps project for [ShopNow](https://github.com/harishmsgit/shopNow). It creates the AWS environment, configures the management host, deploys ShopNow to Kubernetes, synchronizes database secrets, and monitors the application.

## Project at a glance

| Area | Tool | Purpose |
|---|---|---|
| Infrastructure | Terraform | AWS network, IAM, EC2, and EKS |
| Configuration | Ansible | Management-host setup and validation |
| Containers | Docker and ECR | ShopNow application images |
| Orchestration | Kubernetes | Runs all application components |
| Automation | Jenkins | Infrastructure and deployment pipeline |
| Secrets | AWS Secrets Manager, External Secrets | MongoDB credentials |
| Monitoring | Prometheus, Grafana | Application and cluster health |

## Architecture

```text
GitHub repositories
  -> Jenkins pipeline
  -> Terraform creates AWS infrastructure
  -> Ansible configures the management host
  -> Amazon ECR stores ShopNow images
  -> Amazon EKS runs ShopNow
  -> Nginx Ingress routes application traffic
  -> Prometheus and Grafana monitor the deployment
```

### Architecture steps

1. Application code is stored in the `shopNow` repository.
2. Infrastructure and deployment configuration is stored here.
3. Jenkins starts the pipeline after a reviewed change.
4. Terraform creates or updates the AWS network, IAM, EC2, and EKS.
5. Ansible configures and validates the management host.
6. ShopNow Docker images are stored in Amazon ECR.
7. External Secrets reads the MongoDB secret from AWS Secrets Manager.
8. Kubernetes deploys MongoDB, backend, frontend, admin, and ingress.
9. The AWS load balancer exposes the application routes.
10. Prometheus and Grafana monitor the deployment.

## Deployment sequence

```text
Developer pushes a change
  -> Jenkins validates the project
  -> Terraform plans and creates AWS resources
  -> Jenkins deploys the ECR images to Kubernetes
  -> Kubernetes starts the pods
  -> Readiness checks confirm the rollout
```

## Project structure

```text
herovired-infra/
|-- terraform/                 # AWS infrastructure
|-- ansible/                   # Host configuration
|-- kubernetes/
|   |-- k8s-manifests/         # Application resources
|   |-- external-secrets/      # Secret integration
|   `-- monitoring/            # Prometheus and Grafana
|-- docker/jenkins-agent/      # Jenkins agent image
|-- scripts/                   # Automation helpers
|-- Jenkinsfile                # Main pipeline
`-- README.md
```

## Prerequisites

AWS CLI, Terraform, kubectl, Helm, Ansible, Docker, Jenkins, Python, and `jq`.

```bash
aws --version
terraform version
kubectl version --client
helm version
ansible --version
docker --version
```

## Step-by-step deployment

### 1. Clone and verify AWS access

```bash
git clone https://github.com/harishmsgit/herovired-infra.git
cd herovired-infra
aws sts get-caller-identity
```

Examples use region `ap-south-1` and cluster `shopnow-app-eks`.

### 2. Initialize Terraform

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
terraform fmt -check -recursive
terraform init -reconfigure
terraform validate
```

Update `terraform.tfvars` for your environment. Do not store credentials or passwords in it.

### 3. Review and apply the plan

```bash
terraform workspace select dev
terraform plan -out=tfplan
terraform show tfplan
terraform apply tfplan
cd ..
```

Review the plan first because it creates chargeable AWS resources.

### 4. Configure the management host

```bash
terraform -chdir=terraform output -json > ansible/terraform-outputs.json
python scripts/generate_ansible_inventory.py \
  --terraform-output ansible/terraform-outputs.json \
  --inventory ansible/inventories/generated/hosts.ini
ansible-playbook -i ansible/inventories/generated/hosts.ini \
  ansible/playbooks/configure-management.yml
ansible-playbook -i ansible/inventories/generated/hosts.ini \
  ansible/playbooks/validate-management.yml
```

### 5. Connect to EKS

```bash
aws eks update-kubeconfig --region ap-south-1 --name shopnow-app-eks
kubectl cluster-info
kubectl get nodes -o wide
```

### 6. Deploy secrets and ShopNow

```bash
kubectl apply -f kubernetes/k8s-manifests/namespace/namespace.yaml
kubectl apply -f kubernetes/external-secrets/external-secrets-sa.yaml
kubectl apply -f kubernetes/k8s-manifests/database/aws-secretstore.yaml
kubectl apply -f kubernetes/k8s-manifests/database/mongo-secret-externalsecret.yaml
kubectl apply -f kubernetes/k8s-manifests/database/
kubectl apply -f kubernetes/k8s-manifests/backend/
kubectl apply -f kubernetes/k8s-manifests/frontend/
kubectl apply -f kubernetes/k8s-manifests/admin/
kubectl apply -f kubernetes/k8s-manifests/ingress/
kubectl apply -f kubernetes/monitoring/
```

The normal application release runs through Jenkins using image URIs from ECR.

### 7. Verify the deployment

```bash
kubectl get deploy,pods,svc,ingress -n shopnow-ns -o wide
kubectl get events -n shopnow-ns --sort-by=.lastTimestamp
kubectl rollout status deployment/backend -n shopnow-ns --timeout=5m
kubectl rollout status deployment/frontend -n shopnow-ns --timeout=5m
kubectl rollout status deployment/admin -n shopnow-ns --timeout=5m
```

Get the load-balancer address:

```bash
kubectl get service -n ingress-nginx ingress-nginx-controller \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{"\n"}'
```

## Jenkins flow

```text
Code change -> Validation -> Terraform plan -> Terraform apply
            -> Configure secrets -> Deploy ECR images -> Health checks
```

## Useful commands

```bash
kubectl get nodes -o wide
kubectl get pods -n shopnow-ns
kubectl get ingress -n shopnow-ns
kubectl logs -n shopnow-ns deployment/backend --tail=200
kubectl get secretstore,externalsecret -n shopnow-ns
kubectl rollout undo deployment/backend -n shopnow-ns
```

## Complete AWS, EKS, Nginx, and ShopNow flow

```text
shopNow repository             herovired-infra repository
        |                                  |
        +------------> Jenkins <-----------+
                         |
          +--------------+---------------+
          |                              |
     Amazon ECR                  Terraform creates AWS
   stores app images               and Amazon EKS
          |                              |
          +----------> EKS <-------------+
                         |
                 Nginx Ingress
                  /      |      \
          Customer UI  Admin UI  Backend API
                                   |
                                MongoDB
```

### Part 1: Infrastructure creation

1. Jenkins checks out `herovired-infra`.
2. Terraform reads the configuration in `terraform/`.
3. Terraform creates the VPC, subnets, IAM roles, management host, and EKS cluster in AWS.
4. Ansible uses Terraform outputs to configure and validate the management host.
5. Jenkins configures kubectl to communicate with the EKS control plane.

### Part 2: Application deployment

1. Jenkins checks out `shopNow` and builds the frontend, admin, and backend images.
2. The images are pushed to Amazon ECR.
3. Kubernetes Deployments pull the images from ECR and create application pods.
4. Kubernetes Services expose stable internal endpoints for each group of pods.
5. External Secrets copies the required MongoDB values from AWS Secrets Manager into `mongo-secret`.
6. The backend and MongoDB pods receive the secret as runtime configuration.

### Part 3: User request routing

```text
User opens the ShopNow URL
  -> AWS Load Balancer receives the request
  -> Nginx Ingress checks the URL path
  -> Customer path goes to frontend-service:80
  -> Admin path goes to admin-service:80
  -> API path goes to backend-service:5000
  -> Backend reads or updates MongoDB on port 27017
  -> The response returns to the user
```

The ingress manifest replaces the configured application base path during deployment and routes:

| Traffic | Kubernetes destination | Work performed |
|---|---|---|
| Customer UI | `frontend-service:80` | Nginx serves the React customer build |
| Admin UI | `admin-service:80` | Nginx serves the React admin build |
| REST API | `backend-service:5000` | Express handles ShopNow API requests |
| Database traffic | `mongo:27017` inside the cluster | MongoDB stores products, users, and invoices |

Only the ingress is intended for public traffic. MongoDB stays inside the EKS network and the browser never connects to it directly.

## Screenshots

### Infrastructure architecture

![ShopNow AWS and EKS infrastructure architecture](screenshots/architecture.png)

### Amazon ECR repositories

![ShopNow container images stored in Amazon ECR](screenshots/aws-ecr.png)

### Amazon EKS cluster

![Amazon EKS cluster configuration and workloads](screenshots/aws-eks.png)

### Kubernetes and monitoring

![Kubernetes resources and monitoring evidence](screenshots/kubernetes-monitoring.png)

The screenshots above were selected from the original POC evidence to show the project without including database credentials or customer details.

## Notes consolidated from `docs/`

This README now contains the useful infrastructure information that was previously split across the documentation folder:

### Tool responsibilities

| Tool | Role in this project |
|---|---|
| Terraform | Declares and creates AWS resources and keeps their state |
| Ansible | Configures and validates the management host after provisioning |
| Jenkins | Connects validation, Terraform, Ansible, images, and Kubernetes deployment |
| Kubernetes | Maintains the desired application pods and internal Services |
| Nginx Ingress | Routes customer, admin, and API paths from the load balancer |
| External Secrets | Copies the MongoDB configuration from AWS Secrets Manager |
| Prometheus/Grafana | Collects and displays application and cluster health information |

### Infrastructure contract

- Terraform is the source of truth for AWS resources.
- Kubernetes manifests are the source of truth for workloads and Services.
- Ansible configures the management host using generated Terraform outputs.
- Jenkins is the main deployment entry point for shared environments.
- ShopNow supplies three application images; this repository verifies and deploys them.
- MongoDB is internal to the cluster and is not routed through the public load balancer.

### Deployment order

1. Validate the repository and AWS identity.
2. Create and review the Terraform plan.
3. Apply the reviewed plan and collect its outputs.
4. Configure the management host with Ansible.
5. Synchronize `mongo-secret` from AWS Secrets Manager.
6. Deploy MongoDB and wait for it to become available.
7. Deploy the backend and verify its health.
8. Deploy frontend, admin, and ingress.
9. Apply monitoring resources and verify the complete rollout.

### Quick troubleshooting

| Problem | Check |
|---|---|
| Terraform plan or apply fails | AWS identity, backend configuration, state lock, variables, and provider errors |
| kubectl cannot reach EKS | Region, cluster name, kubeconfig, IAM access, and Kubernetes RBAC |
| Pod remains pending | Namespace events, node capacity, image access, resource requests, and storage |
| Image cannot be pulled | ECR URI, image tag, repository policy, region, and node IAM role |
| Secret is not ready | External Secrets operator, service account, SecretStore, AWS secret name, and IAM permission |
| Ingress returns 404 or 502 | Ingress path, rewrite rule, Service name/port, pod health, and ingress-controller logs |

### Terraform, Jenkins, and Ansible summary

- Terraform compares configuration with saved state, shows the expected changes in a plan, and applies the approved result.
- Jenkins runs repeatable pipeline stages and passes application image URIs into the Kubernetes deployment.
- Ansible uses an inventory generated from Terraform outputs to install, configure, and validate management-host tools.

## References

- [Terraform](https://developer.hashicorp.com/terraform/docs)
- [Amazon EKS](https://docs.aws.amazon.com/eks/)
- [Kubernetes](https://kubernetes.io/docs/)
- [Ansible](https://docs.ansible.com/)
- [Jenkins Pipeline](https://www.jenkins.io/doc/book/pipeline/)
- [External Secrets Operator](https://external-secrets.io/)
