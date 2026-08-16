# Team Architecture Summary

This is the short version for quick team discussion, review meetings, and handoff conversations.

---

## 1. Project Architecture Overview

The application is deployed on AWS using a Kubernetes-based platform with Terraform for infrastructure provisioning and Ansible for machine and cluster setup.

### Main components
- AWS VPC with public subnets in ap-south-1a and ap-south-1b
- EKS cluster: shopnow-app-eks
- Management EC2 instance for cluster access and admin tasks
- Kubernetes namespaces:
  - shopnow-ns for application workloads
  - monitor-ns for monitoring stack
- Application services:
  - Frontend
  - Admin
  - Backend
  - MongoDB
- Monitoring stack:
  - Prometheus
  - Grafana
  - CloudWatch logs

---

## 2. Network Design

### VPC
- CIDR: 10.20.0.0/16
- Used to isolate infrastructure and workloads in a controlled AWS network

### Subnets
- Public subnet 1: 10.20.1.0/24 in ap-south-1a
- Public subnet 2: 10.20.2.0/24 in ap-south-1b
- These provide multi-AZ access and support public-facing traffic

### Routing and gateways
- Internet Gateway handles public internet traffic
- NAT Gateway supports outbound access for private resources if needed
- Routing is designed so public services are reachable while private services remain protected

### Why this matters
- External users access the application through ingress/load balancer traffic
- Internal services use cluster DNS and service discovery
- Database and internal resources stay private by default

---

## 3. Cluster and Namespace Model

### EKS Cluster
- Cluster name: shopnow-app-eks
- Running in AWS region ap-south-1
- Worker nodes are distributed across the VPC subnets

### Namespaces
- shopnow-ns: business application workloads
- monitor-ns: Prometheus, Grafana, and monitoring resources

### Application traffic flow
- Browser request hits ingress
- Ingress forwards to frontend/admin/backend services
- Backend talks to MongoDB through Kubernetes service DNS
- Monitoring stack scrapes metrics and logs for observability

---

## 4. Internal vs External Traffic

### External traffic
- Public traffic reaches the app through ingress or public-facing load balancer
- Used mainly for customer-facing frontend and admin access

### Internal traffic
- Backend and database communication stays inside the cluster network
- Service names such as backend and mongodb are used instead of hardcoded pod IPs
- This keeps the application loosely coupled and easier to scale

---

## 5. Security and Access Boundaries

### Security group design
- Public ingress allows traffic to the app entry points
- Private services remain internal-only
- Database and internal services restrict access to approved network paths

### IAM and identity
- EKS roles and worker node roles manage AWS access securely
- Pod identity / IRSA can be used for application access to AWS services without long-lived static secrets

### Key principle
- Public entry points are limited
- Private workloads are isolated and access-controlled

---

## 6. Terraform and Ansible Roles

### Terraform
- Provisions AWS infrastructure components
- Manages VPC, EKS, subnets, IAM, security groups, and state storage
- Uses S3 for state and DynamoDB for locking

### Ansible
- Configures the management host
- Installs required tools such as Docker, kubectl, Helm
- Configures cluster access and validates the environment

### Jenkins pipeline
- Builds and pushes images to ECR
- Runs Terraform for infrastructure provisioning
- Runs Ansible for configuration and validation
- Deploys the application workloads to Kubernetes

---

## 7. Deployment Flow

```text
GitHub source code
   ↓
Jenkins CI/CD pipeline
   ↓
Build Docker images
   ↓
Push to ECR
   ↓
Terraform provisions AWS foundation
   ↓
Ansible configures target environment
   ↓
Kubernetes deploys frontend/admin/backend
   ↓
Monitoring and logs verify health
```

### Jenkins pipeline parameters to set
These are the key values usually configured when starting the pipeline manually:

- AWS_REGION
- AWS_ACCOUNT_ID
- TF_STATE_BUCKET
- TF_STATE_BUCKET_REGION
- LOCK_TABLE
- EKS_CLUSTER_NAME
- ECR_REPO_PREFIX
- ECR_REPOSITORY_STRATEGY
- SINGLE_ECR_REPOSITORY
- FRONTEND_IMAGE_URI / ADMIN_IMAGE_URI / BACKEND_IMAGE_URI
- IMAGE_TAG
- DEPLOY_FRONTEND
- DEPLOY_ADMIN
- DEPLOY_BACKEND
- AWS_CREDENTIALS_ID
- SSH_PRIVATE_KEY_CREDENTIALS_ID
- REMOTE_USER
- RUN_TERRAFORM
- RUN_ANSIBLE_AFTER_APPLY
- RUN_DEPLOYMENT
- K8S_NAMESPACE
- MONITORING_NAMESPACE
- MONITORING_RELEASE_NAME
- GRAFANA_ADMIN_PASSWORD
- ENABLE_MONITORING_CHECKS
- AUTO_INSTALL_CLI_TOOLS

These allow the team to run the pipeline in a controlled way and choose exactly which infrastructure or service set should be updated.

### If the user does not want parameter-wise deployment
Yes, this can be handled in code as a default deployment mode. The pipeline can be designed so that:
- default mode = deploy all services
- or default mode = deploy only frontend/admin/backend based on branch or environment
- or a fixed deployment policy is coded in Jenkinsfile when parameters are not passed

Example possibilities:
- `DEPLOY_MODE = 'all'` by default
- `DEPLOY_MODE = 'frontend'` for only UI deployment
- `DEPLOY_MODE = 'backend'` for service-only deployment
- `DEPLOY_MODE = 'infra-only'` for Terraform-only runs

This is useful when the team wants a simpler Jenkins experience and does not want to manually select each parameter every time.

In practice, the best setup is:
- keep manual parameters for advanced or emergency runs
- keep safe defaults in code for standard deployments
- use environment/branch-based logic to reduce operator effort

---

## 8. Observability Model

### Monitoring
- Prometheus scrapes metrics from cluster workloads
- Grafana visualizes performance and health

### Logging
- Application logs are collected into CloudWatch
- Operators can review logs for deployment, runtime, or failure-related issues

### Operational focus
- Track pod health
- Monitor CPU, memory, and app latency
- Verify cluster health and ingress availability

---

## 9. Maintenance Model

### Routine checks
- Cluster health
- Pod readiness
- Deployment status
- Namespace health
- Ingress and service routing
- Logs and error review

### Important changes that usually happen
- Scaling node groups or adjusting instance sizes
- Updating Kubernetes versions
- Adding or removing app workloads
- Updating secrets or environment-specific values
- Adjusting ingress routing or security rules
- Expanding storage for MongoDB or application data

---

## 10. Quick Team Discussion Summary

The environment follows a standard enterprise Kubernetes pattern:

- AWS gives the cloud foundation
- Terraform manages the infrastructure as code
- Ansible configures the environment and tools
- Kubernetes hosts the application
- Ingress exposes public traffic safely
- Services and namespaces isolate workloads and routing
- Monitoring keeps the platform observable and maintainable

This architecture is designed to be repeatable, secure, and easy to extend as the application evolves.

---

## 11. One-Line Version for Presentations

“We provision AWS infrastructure with Terraform, configure the environment with Ansible, deploy services on EKS, expose the app through ingress, isolate workloads using namespaces and services, and monitor everything with Prometheus, Grafana, and CloudWatch.”
