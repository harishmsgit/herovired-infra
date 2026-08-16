# Deployment, Monitoring & Maintenance Guide

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                         AWS ACCOUNT                          │
│                    559272000457 (ap-south-1)                 │
├──────────────────────┬──────────────────────┬────────────────┤
│                      │                      │                │
│  ┌──────────────────┐│ ┌─────────────────┐ │┌──────────────┐│
│  │  GitHub Actions  ││ │   Jenkins CI/CD │ ││  Developers  ││
│  │  (build trigger) ││ │  (orchestration)│ ││  (CLI tools) ││
│  └─────────┬────────┘│ └────────┬────────┘ │└──────────────┘│
│            │         │         │          │                │
│ ┌──────────▼─────────────────────▼──────────┐              │
│ │        S3 State Backend                    │              │
│ │  (harish-pc-s3-bucket)                     │              │
│ │  ├─ Terraform state files                  │              │
│ │  ├─ DynamoDB locks                         │              │
│ │  └─ Version history                        │              │
│ └──────────────────────────────────────────┘              │
│                      │                                     │
│         ┌────────────▼────────────┐                       │
│         │    VPC: 10.20.0.0/16    │                       │
│         │                         │                       │
│     ┌───┴──────────┬──────────────┴───┐                   │
│     │              │                  │                   │
│  ┌──▼────┐    ┌───▼────┐    ┌─────────▼──┐               │
│  │Public │    │Public  │    │Management  │               │
│  │Subnet1│    │Subnet2 │    │EC2 (Ubuntu)│               │
│  │10.20. │    │10.20.2 │    │  t3.medium │               │
│  │1.0/24 │    │.0/24   │    │            │               │
│  └───┬──┘    └───┬────┘    └─────────┬──┘               │
│      │           │                    │                   │
│      │    ┌──────▼──────┐             │                   │
│      │    │  EKS Cluster│             │                   │
│      │    │shopnow-app- │             │                   │
│      │    │eks          │             │                   │
│      │    ├──────────────┤             │                   │
│      │    │ Node Group 1 │             │                   │
│      │    │ (ap-south-   │             │                   │
│      │    │  1a)         │             │                   │
│      │    ├──────────────┤             │                   │
│      │    │ Node Group 2 │             │                   │
│      │    │ (ap-south-   │             │                   │
│      │    │  1b)         │             │                   │
│      │    ├──────────────┤             │                   │
│      │    │  Namespaces: │             │                   │
│      │    │  - shopnow-ns│             │                   │
│      │    │  - monitor-ns│             │                   │
│      │    └──────────────┘             │                   │
│      │                                 │                   │
│  ┌───┴─────────────────────────────────┴──┐               │
│  │         Amazon Elastic Container       │               │
│  │         Registry (ECR)                  │               │
│  │  ├─ shopnow-dev/frontend:v1.0.0        │               │
│  │  ├─ shopnow-dev/admin:v1.0.0           │               │
│  │  └─ shopnow-dev/backend:v1.0.0         │               │
│  └───────────────────────────────────────┘               │
│                                                           │
│  ┌────────────────────────────────────────┐              │
│  │   Persistent Storage (EBS + RDS)       │              │
│  │  ├─ MongoDB StatefulSet (EBS)          │              │
│  │  ├─ Configuration (ConfigMap)          │              │
│  │  └─ Secrets (Secrets Manager)          │              │
│  └────────────────────────────────────────┘              │
│                                                           │
│  ┌────────────────────────────────────────┐              │
│  │   Monitoring (Prometheus + Grafana)    │              │
│  │  ├─ Prometheus scraping endpoints      │              │
│  │  ├─ Grafana dashboards                 │              │
│  │  └─ CloudWatch logs + metrics          │              │
│  └────────────────────────────────────────┘              │
└───────────────────────────────────────────────────────────┘
```

---

## Application Deployment Pipeline

```
1. Developer Commits Code
   ↓ GitHub Webhook
2. Jenkins Pipeline Starts
   ├─ Checkout source code
   ├─ Build Docker image (shopNow/Dockerfile)
   ├─ Push to ECR (549013583028.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/*)
   └─ Trigger infrastructure pipeline
   ↓
3. Terraform Provisions Infrastructure
   ├─ Create/update VPC, subnets
   ├─ Create/update EKS cluster
   ├─ Create/update ECR repositories
   └─ Create/update IAM roles
   ↓
4. Ansible Configures Cluster
   ├─ Install Docker, kubectl, Helm
   ├─ Connect to EKS cluster
   ├─ Create Kubernetes namespaces
   ├─ Deploy MongoDB StatefulSet
   └─ Deploy Prometheus + Grafana
   ↓
5. Deploy Application Workloads
   ├─ Frontend Deployment → shopnow-ns
   ├─ Admin Deployment → shopnow-ns
   ├─ Backend Deployment → shopnow-ns
   ├─ MongoDB Service (internal DNS)
   └─ Ingress (external access)
   ↓
6. Health Checks
   ├─ Pod readiness probes
   ├─ Service endpoint checks
   ├─ Ingress health
   └─ Application connectivity tests
   ↓
7. Monitoring Active
   ├─ Prometheus scraping metrics
   ├─ Grafana dashboards updated
   └─ CloudWatch logs streaming
```

---

## Deployment Flow (Detailed)

### **Stage 1: Code Build (Jenkins)**

**Trigger**: GitHub push or manual "Build Now"

```bash
# Jenkins Pipeline executes (shopNow/Jenkinsfile):

1. Checkout
   - Clones repository
   - Checks out feature branch

2. Build & Test
   - npm install (dependencies)
   - npm test (unit tests)
   - npm build (production build)

3. Docker Image Creation
   - Builds frontend/Dockerfile
   - Builds admin/Dockerfile
   - Builds backend/Dockerfile
   - Tags: {repo}:v{BUILD_NUMBER}-{GIT_SHA}

4. Push to ECR
   - Authenticates to ECR (AWS credentials)
   - Pushes frontend image
   - Pushes admin image
   - Pushes backend image

5. Trigger Infrastructure Pipeline
   - Calls herovired-infra/Jenkinsfile
   - Passes image tags as parameters
```

**Output**:
```
ECR Repositories:
559272000457.dkr.ecr.ap-south-1.amazonaws.com/
├─ shopnow-dev/frontend:45-abc1234
├─ shopnow-dev/admin:45-abc1234
└─ shopnow-dev/backend:45-abc1234
```

---

### **Stage 2: Infrastructure Provisioning (Terraform)**

**Trigger**: Jenkins calls infrastructure pipeline

```bash
# Terraform Stage (herovired-infra/Jenkinsfile):

1. Initialize
   - Load configuration (common.env)
   - Validate parameters
   - Detect infrastructure changes

2. Setup Backend
   - Create S3 bucket (if not exists)
   - Create DynamoDB lock table
   - Configure remote state

3. Terraform Plan
   - Reads current state from S3
   - Compares with desired state (terraform.tfvars)
   - Outputs plan (what will be created/updated/destroyed)

4. Terraform Apply
   - Acquires DynamoDB lock
   - Creates/updates resources:
     * VPC + subnets + IGW
     * EKS cluster + node groups
     * EC2 management instance
     * ECR repositories
     * IAM roles + policies
     * Security groups
   - Writes new state to S3

5. Outputs
   - EKS cluster endpoint
   - Management EC2 IP
   - ECR repository URLs
   - Kubeconfig data
```

**Output**:
```json
{
  "eks_cluster_endpoint": "https://abc123.eks.ap-south-1.amazonaws.com",
  "management_ec2_ip": "10.20.1.50",
  "ecr_registry": "559272000457.dkr.ecr.ap-south-1.amazonaws.com"
}
```

---

### **Stage 3: Configuration Management (Ansible)**

**Trigger**: After Terraform completes successfully

```bash
# Ansible Stage (herovired-infra/Jenkinsfile):

1. Generate Inventory
   - Reads Terraform outputs
   - Creates hosts.ini with management EC2 IP
   - Configures SSH (uses shopnow-key-pair)

2. Configure Management Host
   - SSH to EC2 instance
   - Install Docker Engine
   - Install kubectl (Kubernetes CLI)
   - Install Helm 3 (package manager)
   - Configure kubeconfig (EKS authentication)
   - Configure AWS IAM role for EC2

3. Create Kubernetes Namespaces
   - kubectl apply namespace YAML
   - Create shopnow-ns (application)
   - Create monitor-ns (monitoring)

4. Deploy Persistent Storage
   - Deploy MongoDB StatefulSet
   - Create PersistentVolumeClaims (EBS)
   - Expose MongoDB Service (internal DNS)

5. Deploy Monitoring Stack
   - Deploy Prometheus operator
   - Deploy Grafana
   - Create ServiceMonitors
   - Create PrometheusRules (alerts)

6. Validate Cluster
   - kubectl get nodes (verify all nodes active)
   - kubectl get pods -A (verify all pods running)
   - kubectl get svc (verify all services)
```

**Output**:
```
Namespaces:
✓ shopnow-ns (application)
✓ monitor-ns (monitoring)
✓ kube-system (Kubernetes internal)

Services:
✓ mongodb (ClusterIP 10.x.x.x:27017)
✓ prometheus (ClusterIP 10.x.x.x:9090)
✓ grafana (ClusterIP 10.x.x.x:3000)
```

---

### **Stage 4: Deploy Applications**

**Trigger**: After Ansible validation passes

```bash
# Kubernetes Deployment (herovired-infra/Jenkinsfile):

1. Create Namespace Configuration
   - kubectl create namespace shopnow-ns
   - Apply RBAC policies
   - Apply NetworkPolicies

2. Deploy Persistent Storage
   - kubectl apply -f kubernetes/k8s-manifests/database/

3. Deploy Microservices (Parallel)
   ├─ Frontend Deployment
   │  ├─ Create Deployment (3 replicas)
   │  ├─ Set image to ECR URI
   │  ├─ Set resource limits (CPU/memory)
   │  ├─ Configure readiness probes
   │  └─ Expose via Service (ClusterIP)
   │
   ├─ Admin Deployment
   │  ├─ Create Deployment (2 replicas)
   │  ├─ Set image to ECR URI
   │  ├─ Configure environment variables
   │  └─ Expose via Service (ClusterIP)
   │
   └─ Backend Deployment
      ├─ Create Deployment (3 replicas)
      ├─ Set image to ECR URI
      ├─ Inject AWS credentials (IAM role)
      ├─ Configure MongoDB connection
      └─ Expose via Service (ClusterIP)

4. Expose Applications Externally
   - Create Ingress resource
   - Point domain names to ALB
   - Configure TLS certificates
   - Set URL paths:
     * / → frontend
     * /admin → admin
     * /api → backend

5. Health Checks
   - Wait for deployments to be ready
   - kubectl rollout status deployment/frontend
   - kubectl rollout status deployment/admin
   - kubectl rollout status deployment/backend
   - Verify services are accessible
```

**Output**:
```
Deployments Ready:
✓ frontend (3/3 replicas running)
✓ admin (2/2 replicas running)
✓ backend (3/3 replicas running)

Services:
✓ frontend:80 (ClusterIP)
✓ admin:80 (ClusterIP)
✓ backend:3000 (ClusterIP)
✓ mongodb:27017 (ClusterIP)

Ingress:
✓ shopnow-ingress → ALB → Public IP
```

---

## Monitoring & Observability

### **Metrics Collection**

```
Prometheus (Port 9090)
├─ Scrapes every 30 seconds:
│  ├─ Kubernetes API server metrics
│  ├─ Node exporter (CPU, memory, disk)
│  ├─ Docker container metrics
│  ├─ Application custom metrics (/metrics endpoint)
│  └─ MongoDB metrics (if using mongodb_exporter)
│
├─ Stores metrics locally (30 days retention)
│
└─ Exposes API (:9090/api/v1/query)
   - Used by Grafana for visualization
   - Used by alerting engine for rules
```

### **Visualization & Dashboards**

```
Grafana (Port 3000)
├─ Data source: Prometheus
├─ Dashboards:
│  ├─ Cluster Overview (nodes, pods, resources)
│  ├─ Pod Performance (CPU, memory, network I/O)
│  ├─ Application Metrics (request rate, latency, errors)
│  ├─ Database Metrics (connections, operations, locks)
│  └─ Infrastructure (VPC, EKS, EC2)
│
├─ Alerts:
│  ├─ High CPU usage (>80%)
│  ├─ High memory usage (>80%)
│  ├─ Pod restarts (>5 in 10min)
│  ├─ Service down (no healthy endpoints)
│  └─ Disk space low (<10% free)
│
└─ Notifications:
   ├─ Slack messages
   ├─ PagerDuty incidents
   └─ Email alerts
```

### **Logs Collection**

```
CloudWatch Logs (AWS native)
├─ Pod logs:
│  ├─ /aws/eks/shopnow-app-eks/frontend
│  ├─ /aws/eks/shopnow-app-eks/admin
│  ├─ /aws/eks/shopnow-app-eks/backend
│  └─ /aws/eks/shopnow-app-eks/mongodb
│
├─ Infrastructure logs:
│  ├─ /aws/eks/shopnow-app-eks/cluster
│  ├─ /aws/ec2/management
│  └─ /aws/vpc/flow-logs
│
├─ Retention: 30 days (configurable)
│
└─ Insights:
   - Query logs with CloudWatch Insights
   - Example: fields @timestamp, @message | filter error
```

---

## Maintenance Procedures

### **Weekly Tasks**

```bash
# 1. Verify cluster health
kubectl cluster-info
kubectl get nodes
kubectl get pods -A | grep -v Running
kubectl top nodes
kubectl top pods -A

# 2. Check resource utilization
kubectl describe node <node-name>
# Look for: Memory%, Disk%, CPU%

# 3. Backup kubeconfig
cp ~/.kube/config ~/kubeconfig-backup-$(date +%Y%m%d).yaml

# 4. Review error logs
aws logs tail /aws/eks/shopnow-app-eks/backend --follow --since 1w
```

---

### **Monthly Tasks**

```bash
# 1. Review Terraform state
cd terraform
terraform state list
terraform plan -out=tfplan  # should show "No changes"

# 2. Update Kubernetes components
# Check for available updates:
kubectl version --short
helm repo update
helm search repo -l

# 3. Review security policies
kubectl get networkpolicies -A
kubectl get podsecuritypolicies

# 4. Check certificate expiration
# For TLS certificates in Ingress:
kubectl get certificates -A
kubectl describe certificate shopnow-tls

# 5. Review failed pod events
kubectl get events -A --sort-by='.lastTimestamp' | tail -20

# 6. Scale resources if needed
# Edit terraform.tfvars:
# desired_size = 3  # increase node count
# replicas = 5      # increase pod replicas
terraform apply
```

---

### **Quarterly Tasks**

```bash
# 1. Update Terraform provider
cd terraform
terraform init -upgrade

# 2. Update Kubernetes version
# Planning only:
terraform plan | grep kubernetes_version

# 3. Rotate SSH keys
# Generate new key pair:
ssh-keygen -t rsa -b 4096 -f shopnow-key-pair-new
# Store in AWS Secrets Manager
# Update Terraform to use new key
# Terminate old EC2 instance
# Create new instance

# 4. Update Docker/container images
# Review security updates
docker pull shopnow-dev/frontend:latest
docker pull shopnow-dev/admin:latest
docker pull shopnow-dev/backend:latest

# 5. Audit IAM permissions
aws iam get-role --role-name <role-name>
aws iam list-attached-role-policies --role-name <role-name>

# 6. Review CloudWatch logs
# Archive old logs to S3
aws logs create-export-task \
  --log-group-name /aws/eks/shopnow-app-eks/backend \
  --from $(date -d '3 months ago' +%s)000 \
  --to $(date +%s)000 \
  --destination harish-pc-s3-bucket
```

---

### **Annual Tasks**

```bash
# 1. Full infrastructure audit
# Review Terraform code for deprecated resources
terraform plan -upgrade

# 2. Disaster recovery test
# Destroy non-critical resources and redeploy
terraform destroy -target=aws_eks_node_group.workers
# Wait for rebuild
terraform apply

# 3. Update base images
# Update EKS AMI to latest
# Update management EC2 AMI
terraform apply -var="ami_version=latest"

# 4. Security compliance review
# Run CIS Kubernetes benchmark
# Review network policies
# Audit RBAC roles
kubectl auth can-i --list --as=system:serviceaccount:default:default

# 5. Capacity planning
# Review past 12 months of metrics
# Forecast growth needs
# Update instance types/counts
```

---

## Troubleshooting Common Issues

### **Issue: Pod won't start**

```bash
# 1. Check pod status
kubectl describe pod <pod-name> -n shopnow-ns

# 2. Common causes:
# - Image not found in ECR
kubectl describe pod | grep ImagePull

# - Insufficient resources
kubectl describe nodes | grep Allocated

# - ConfigMap/Secret missing
kubectl get configmap -n shopnow-ns
kubectl get secret -n shopnow-ns

# 3. View logs
kubectl logs <pod-name> -n shopnow-ns --previous  # if crashed
```

---

### **Issue: High latency or errors**

```bash
# 1. Check resource utilization
kubectl top nodes
kubectl top pods -n shopnow-ns

# 2. Check if horizontal pod autoscaler triggered
kubectl get hpa -n shopnow-ns

# 3. Check network policies
kubectl get networkpolicies -n shopnow-ns
kubectl describe networkpolicy <name> -n shopnow-ns

# 4. Check database connectivity
kubectl exec -it <backend-pod> -n shopnow-ns -- nc -zv mongodb 27017

# 5. Review application logs
kubectl logs <pod-name> -n shopnow-ns -f  # follow logs
```

---

### **Issue: Node not ready**

```bash
# 1. Check node status
kubectl describe node <node-name>

# 2. Check node conditions
# Should see: Ready, MemoryPressure=False, DiskPressure=False

# 3. SSH to node
ssh -i ~/.ssh/shopnow-key-pair.pem ec2-user@<node-ip>

# 4. Check kubelet status (on node)
sudo systemctl status kubelet
sudo journalctl -u kubelet -f  # follow logs

# 5. Check disk usage
df -h  # if high, scale down pods

# 6. Cordon and drain if needed
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets
```

---

## Best Practices

| Practice | Benefit |
|----------|---------|
| **Infrastructure as Code** | Reproducible, version-controlled deployments |
| **Automated deployments** | Faster, fewer human errors |
| **Continuous monitoring** | Early problem detection |
| **Regular backups** | Disaster recovery capability |
| **Staged environments** | Test before production |
| **Documentation** | Knowledge transfer |
| **Automated rollbacks** | Quick recovery from failures |
| **Resource quotas** | Prevent one app from starving others |

---

## Summary

| Component | Purpose | Update Frequency |
|-----------|---------|-----------------|
| Terraform | Infrastructure provisioning | Monthly |
| Ansible | Configuration management | Monthly |
| Docker images | Application containers | Daily |
| Kubernetes manifests | Application deployment | Daily |
| Monitoring | Observability + alerts | Real-time |
| Backups | Disaster recovery | Daily |

