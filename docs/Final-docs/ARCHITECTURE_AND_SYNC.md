# Architecture Diagrams & Application Infrastructure Sync

## 1. Complete System Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│                                  Internet                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐  │
│  │ Developer → GitHub  → Jenkins  → Docker Build  → ECR Push          │  │
│  │ User (Browser) → ALB (ELB) → Ingress → Services → Pods             │  │
│  └─────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────┘
                                      ↓
┌────────────────────────────────────────────────────────────────────────────┐
│                         AWS VPC (10.20.0.0/16)                             │
│                                                                            │
│  ┌────────────────────┐  ┌────────────────────┐                          │
│  │ Public Subnet 1    │  │ Public Subnet 2    │                          │
│  │ (10.20.1.0/24)     │  │ (10.20.2.0/24)     │                          │
│  │ AZ: ap-south-1a    │  │ AZ: ap-south-1b    │                          │
│  │                    │  │                    │                          │
│  │  Management EC2    │  │  EKS Node 2        │                          │
│  │  t3.medium         │  │  t3.medium         │                          │
│  │  └─ Ubuntu 22      │  │  └─ AL2            │                          │
│  │  └─ SSH Port 22    │  │  └─ Kubelet        │                          │
│  │  └─ Ansible         │  │  └─ Docker         │                          │
│  │  └─ kubectl         │  │                    │                          │
│  │                    │  │                    │                          │
│  └────────────────────┘  └────────────────────┘                          │
│           ↑                      ↓                                        │
│           │               EKS Cluster Plane                              │
│           │           (Managed by AWS)                                   │
│           │               • API Server                                   │
│           │               • etcd                                         │
│           │               • Controller Mgr                               │
│           │               • Scheduler                                    │
│           │                    ↓                                         │
│  ┌────────────────────────────────────────────────────────────────┐    │
│  │           Kubernetes Cluster (shopnow-app-eks)                │    │
│  │  ┌─────────────────────────────────────────────────────────┐  │    │
│  │  │ shopnow-ns (Application Namespace)                      │  │    │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────────┐      │  │    │
│  │  │  │ Frontend   │ │ Admin      │ │ Backend        │      │  │    │
│  │  │  │ Deployment │ │ Deployment │ │ Deployment     │      │  │    │
│  │  │  │ (replicas:3)│ │(replicas:2)│ │ (replicas:3)   │      │  │    │
│  │  │  │            │ │            │ │                │      │  │    │
│  │  │  │ Port: 80   │ │ Port: 80   │ │ Port: 3000     │      │  │    │
│  │  │  └────────────┘ └────────────┘ └────────────────┘      │  │    │
│  │  │        ↓              ↓                  ↓              │  │    │
│  │  │  ┌────────────────────────────────────────────┐        │  │    │
│  │  │  │ Ingress (ALB)                              │        │  │    │
│  │  │  │ • Host-based routing                       │        │  │    │
│  │  │  │ • Path-based routing                       │        │  │    │
│  │  │  │ • TLS termination                          │        │  │    │
│  │  │  └────────────────────────────────────────────┘        │  │    │
│  │  │        ↓                                               │  │    │
│  │  │  ┌──────────────────────────────────────────────────┐  │  │    │
│  │  │  │ Services (Internal DNS)                          │  │  │    │
│  │  │  │ • frontend:80 (ClusterIP)                        │  │  │    │
│  │  │  │ • admin:80 (ClusterIP)                           │  │  │    │
│  │  │  │ • backend:3000 (ClusterIP)                       │  │  │    │
│  │  │  │ • mongodb:27017 (ClusterIP) ← Pod-to-pod access │  │  │    │
│  │  │  └──────────────────────────────────────────────────┘  │  │    │
│  │  │        ↓                                               │  │    │
│  │  │  ┌──────────────────────────────────────────────────┐  │  │    │
│  │  │  │ MongoDB StatefulSet (Persistent Storage)         │  │  │    │
│  │  │  │ • Pod: mongodb-0                                │  │  │    │
│  │  │  │ • PersistentVolume (EBS gp2, 20GB)              │  │  │    │
│  │  │  │ • Data directory: /data/db                      │  │  │    │
│  │  │  └──────────────────────────────────────────────────┘  │  │    │
│  │  └─────────────────────────────────────────────────────────┘  │    │
│  │                                                               │    │
│  │  ┌─────────────────────────────────────────────────────────┐  │    │
│  │  │ monitor-ns (Monitoring Namespace)                       │  │    │
│  │  │  ┌──────────────┐    ┌──────────────┐                  │  │    │
│  │  │  │ Prometheus   │    │ Grafana      │                  │  │    │
│  │  │  │ :9090        │    │ :3000        │                  │  │    │
│  │  │  │              │    │              │                  │  │    │
│  │  │  │ • Scrapes    │    │ • Dashboards │                  │  │    │
│  │  │  │   metrics    │    │ • Alerts     │                  │  │    │
│  │  │  │ • 30d        │    │ • Queries    │                  │  │    │
│  │  │  │   retention  │    │              │                  │  │    │
│  │  │  └──────────────┘    └──────────────┘                  │  │    │
│  │  └─────────────────────────────────────────────────────────┘  │    │
│  └────────────────────────────────────────────────────────────────┘    │
│           ↓            ↓             ↓             ↓                    │
│  ┌───────────────────────────────────────────────────────────────┐    │
│  │           AWS Services (Outside VPC)                          │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ ECR (Elastic Container Registry)                       │  │    │
│  │  │ • shopnow-dev/frontend:45-abc1234                      │  │    │
│  │  │ • shopnow-dev/admin:45-abc1234                         │  │    │
│  │  │ • shopnow-dev/backend:45-abc1234                       │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ S3 (State Storage & Backups)                           │  │    │
│  │  │ • harish-pc-s3-bucket/terraform/state                  │  │    │
│  │  │ • Versioning enabled                                   │  │    │
│  │  │ • Encryption AES-256                                   │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ DynamoDB (State Locking)                               │  │    │
│  │  │ • shopnow-terraform-locks                              │  │    │
│  │  │ • Prevents concurrent Terraform applies                │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ CloudWatch (Logs & Monitoring)                         │  │    │
│  │  │ • /aws/eks/shopnow-app-eks/frontend                    │  │    │
│  │  │ • /aws/eks/shopnow-app-eks/admin                       │  │    │
│  │  │ • /aws/eks/shopnow-app-eks/backend                     │  │    │
│  │  │ • /aws/eks/shopnow-app-eks/cluster                     │  │    │
│  │  │ • Retention: 30 days                                   │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ Secrets Manager (Credentials)                          │  │    │
│  │  │ • shopnow/mongo (MongoDB connection string)            │  │    │
│  │  │ • shopnow/api-keys (API credentials)                   │  │    │
│  │  │ • Accessed by ExternalSecrets operator                 │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  │  ┌────────────────────────────────────────────────────────┐  │    │
│  │  │ IAM (Identity & Access Management)                     │  │    │
│  │  │ • EKS Cluster Role (for control plane)                 │  │    │
│  │  │ • EKS Node Role (for worker nodes)                     │  │    │
│  │  │ • Management EC2 Role (for Ansible)                    │  │    │
│  │  │ • Pod Roles (via IRSA)                                 │  │    │
│  │  └────────────────────────────────────────────────────────┘  │    │
│  └───────────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Data Flow Architecture

```
┌─────────────────────┐
│   GitHub Repository │
│  (Source Code)      │
└──────────┬──────────┘
           │ Webhook on push
           ↓
┌─────────────────────────────────────────────────────────┐
│              Jenkins CI/CD Pipeline                      │
│                                                          │
│  1. Checkout                                            │
│  2. Build (npm install, npm test, npm build)           │
│  3. Docker Build (Dockerfile)                          │
│  4. Push to ECR (docker push)                          │
│  5. Trigger Infrastructure Pipeline                    │
└──────────┬───────────────────────────────────────────────┘
           │ Image tags: 45-abc1234
           ↓
┌─────────────────────────────────────────────────────────┐
│              ECR Repositories                            │
│  • shopnow-dev/frontend:45-abc1234                      │
│  • shopnow-dev/admin:45-abc1234                         │
│  • shopnow-dev/backend:45-abc1234                       │
└──────────┬───────────────────────────────────────────────┘
           │ Infrastructure Pipeline triggers
           ↓
┌─────────────────────────────────────────────────────────┐
│              Terraform Provisioning                      │
│                                                          │
│  1. Read S3 state                                       │
│  2. Compare with desired state (terraform.tfvars)      │
│  3. Acquire DynamoDB lock                              │
│  4. Provision/update:                                  │
│     • VPC, subnets, IGW                                │
│     • EKS cluster, node groups                         │
│     • EC2 management instance                          │
│     • ECR repositories                                 │
│     • IAM roles, security groups                       │
│  5. Write new state to S3                              │
│  6. Release DynamoDB lock                              │
└──────────┬───────────────────────────────────────────────┘
           │ Terraform outputs: endpoints, IPs, ARNs
           ↓
┌─────────────────────────────────────────────────────────┐
│              Ansible Configuration                       │
│                                                          │
│  1. Generate inventory from Terraform outputs          │
│  2. SSH to management EC2                              │
│  3. Install Docker, kubectl, Helm                      │
│  4. Configure kubeconfig (EKS auth)                    │
│  5. Create Kubernetes namespaces                       │
│  6. Deploy MongoDB StatefulSet (with EBS storage)      │
│  7. Deploy Prometheus + Grafana                        │
│  8. Validate all components                            │
└──────────┬───────────────────────────────────────────────┘
           │ Cluster ready
           ↓
┌─────────────────────────────────────────────────────────┐
│              Kubernetes Deployments                      │
│                                                          │
│  kubectl apply -f:                                     │
│  • Namespace manifest                                  │
│  • ServiceAccount + RBAC roles                         │
│  • Frontend Deployment (image from ECR)               │
│  • Admin Deployment (image from ECR)                  │
│  • Backend Deployment (image from ECR)                │
│  • Services (ClusterIP for internal DNS)              │
│  • Ingress (ALB routing)                              │
│  • MongoDB Service (internal DNS)                     │
│  • ConfigMaps (app configuration)                     │
│  • Secrets (API keys, passwords via ExternalSecrets) │
└──────────┬───────────────────────────────────────────────┘
           │ Containers start
           ↓
┌─────────────────────────────────────────────────────────┐
│              Application Pods Running                    │
│                                                          │
│  Frontend Pod:                                          │
│  • Container image: ECR URI (45-abc1234)               │
│  • Port 80 exposed                                     │
│  • Mounts: /usr/share/nginx/html (static files)        │
│  • Health check: HTTP 200 on /                         │
│                                                          │
│  Admin Pod:                                             │
│  • Container image: ECR URI (45-abc1234)               │
│  • Port 80 exposed                                     │
│  • Mounts: /usr/share/nginx/html (static files)        │
│  • Health check: HTTP 200 on /admin                    │
│                                                          │
│  Backend Pod:                                           │
│  • Container image: ECR URI (45-abc1234)               │
│  • Port 3000 exposed                                   │
│  • Environment: MONGODB_URI from ExternalSecret       │
│  • IAM role injected (for AWS API calls)              │
│  • Health check: HTTP 200 on /health                  │
│                                                          │
│  MongoDB Pod:                                           │
│  • Container image: mongo:5.0                          │
│  • Port 27017 (exposed internally only)                │
│  • PersistentVolume: EBS gp2 (20GB)                    │
│  • Data directory: /data/db                            │
└──────────┬───────────────────────────────────────────────┘
           │ Continuous data flow
           ↓
┌─────────────────────────────────────────────────────────┐
│              Inter-Pod Communication                     │
│                                                          │
│  Frontend ──(HTTP)──> Ingress ──(DNS:backend:3000)──> Backend
│  Admin ──(HTTP)──> Ingress ──(DNS:backend:3000)──> Backend
│  Backend ──(DNS:mongodb:27017)──> MongoDB
│                                                          │
│  Kubernetes Service provides DNS names:                 │
│  • backend.shopnow-ns.svc.cluster.local:3000           │
│  • mongodb.shopnow-ns.svc.cluster.local:27017          │
│  • frontend.shopnow-ns.svc.cluster.local:80            │
│  • admin.shopnow-ns.svc.cluster.local:80               │
└──────────┬───────────────────────────────────────────────┘
           │ Monitoring & logging
           ↓
┌─────────────────────────────────────────────────────────┐
│              CloudWatch & Prometheus                     │
│                                                          │
│  Pod Logs → CloudWatch Logs:                           │
│  • /aws/eks/shopnow-app-eks/frontend                   │
│  • /aws/eks/shopnow-app-eks/admin                      │
│  • /aws/eks/shopnow-app-eks/backend                    │
│  • /aws/eks/shopnow-app-eks/mongodb                    │
│                                                          │
│  Metrics → Prometheus:                                  │
│  • kubernetes_build_info (cluster info)                │
│  • container_cpu_usage_seconds_total (CPU)             │
│  • container_memory_usage_bytes (memory)               │
│  • http_requests_total (application metrics)           │
│  • mongodb_connections (database metrics)              │
│                                                          │
│  Visualized in Grafana Dashboards:                     │
│  • Cluster Overview                                     │
│  • Pod Performance                                      │
│  • Application Metrics                                 │
│  • Database Metrics                                    │
│  • Infrastructure Metrics                              │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Application ↔ Infrastructure Sync

### **How Applications Use Infrastructure**

```
┌────────────────────────────────────────────────────────────┐
│              APPLICATION LAYER                             │
│                                                            │
│  Frontend (React/Vue/Angular)                             │
│  ├─ Serves static files (JS, CSS, HTML)                   │
│  ├─ Makes API calls to backend:                           │
│  │  └─ http://<backend-service>/api/users                │
│  └─ Uses Ingress hostname for routing                     │
│                                                            │
│  Admin (React/Vue/Angular)                                │
│  ├─ Serves admin dashboard                               │
│  ├─ Makes API calls to backend:                           │
│  │  └─ http://<backend-service>/api/admin                │
│  └─ Uses Ingress hostname for routing                     │
│                                                            │
│  Backend (Node.js/Express)                                │
│  ├─ Listens on port 3000                                 │
│  ├─ Connects to MongoDB via:                              │
│  │  └─ mongodb://mongodb:27017/shopnow                    │
│  ├─ Environment variables from ConfigMaps/Secrets:        │
│  │  ├─ DB_HOST: "mongodb" (Kubernetes DNS)              │
│  │  ├─ DB_PORT: "27017"                                 │
│  │  ├─ DB_NAME: "shopnow"                               │
│  │  ├─ API_SECRET: "xxxxxxx" (from Secret)              │
│  │  └─ AWS_REGION: "ap-south-1"                         │
│  ├─ Accesses AWS services:                               │
│  │  ├─ S3 (image uploads)                               │
│  │  ├─ SQS (async jobs)                                 │
│  │  ├─ Secrets Manager (API keys)                       │
│  │  └─ Uses IAM role (IRSA)                             │
│  ├─ Exposes metrics endpoint:                             │
│  │  └─ /metrics (Prometheus scrapes)                     │
│  └─ Logs to stdout:                                       │
│     └─ Captured by kubelet → CloudWatch Logs             │
│                                                            │
│  MongoDB (Stateful Database)                             │
│  ├─ Listens on port 27017 (internal only)               │
│  ├─ Stores data on PersistentVolume:                      │
│  │  └─ EBS volume (20GB gp2)                            │
│  ├─ Exposed via Service (ClusterIP):                      │
│  │  └─ mongodb:27017 (internal DNS)                      │
│  └─ Replicated across Pods (if StatefulSet replicas>1)   │
│                                                            │
└────────────────────────────────────────────────────────────┘
                              ↓
┌────────────────────────────────────────────────────────────┐
│              INFRASTRUCTURE LAYER                          │
│                                                            │
│  Kubernetes Services (DNS resolution)                     │
│  ├─ backend → <pod-ip>:3000 (ClusterIP)                 │
│  ├─ mongodb → <pod-ip>:27017 (ClusterIP)                │
│  ├─ frontend → <pod-ip>:80 (ClusterIP)                  │
│  └─ admin → <pod-ip>:80 (ClusterIP)                     │
│                                                            │
│  Ingress (External access)                               │
│  ├─ Route: / → frontend:80                              │
│  ├─ Route: /admin → admin:80                            │
│  ├─ Route: /api → backend:3000                          │
│  └─ TLS termination + Load balancing                     │
│                                                            │
│  Kubernetes Namespaces (isolation)                       │
│  ├─ shopnow-ns (application pods)                        │
│  ├─ monitor-ns (monitoring stack)                        │
│  └─ Resource quotas per namespace                        │
│                                                            │
│  ConfigMaps (configuration)                              │
│  ├─ app-config.json (application settings)              │
│  └─ Mounted as volume or env vars                        │
│                                                            │
│  Secrets (sensitive data)                                │
│  ├─ database-password                                    │
│  ├─ api-keys                                            │
│  └─ Sourced from AWS Secrets Manager (ExternalSecrets)  │
│                                                            │
│  PersistentVolumes (storage)                             │
│  ├─ EBS volumes for MongoDB                             │
│  ├─ Automatic provisioning                              │
│  └─ Snapshots for backups                               │
│                                                            │
│  Security Groups (network firewall)                       │
│  ├─ Management → EKS communication                       │
│  ├─ EKS Nodes → ECR image pull                          │
│  └─ Pods → external API calls                           │
│                                                            │
│  IAM Roles (access control)                              │
│  ├─ EKS Node Role → EC2 permissions                     │
│  ├─ Pod Roles (IRSA) → AWS API access                   │
│  └─ Management Role → EKS admin permissions             │
│                                                            │
│  VPC Networking (isolation)                              │
│  ├─ CIDR: 10.20.0.0/16 (no overlap with other apps)    │
│  ├─ Subnets: 10.20.1.0/24, 10.20.2.0/24 (multi-AZ)     │
│  ├─ Route tables (internal + IGW routing)               │
│  └─ NAT Gateway (outbound internet access)              │
│                                                            │
│  Auto-scaling                                            │
│  ├─ Horizontal Pod Autoscaler (HPA)                     │
│  │  └─ Scale pods based on CPU/memory metrics           │
│  ├─ Vertical Pod Autoscaler (VPA)                       │
│  │  └─ Recommend resource requests/limits              │
│  └─ Cluster Autoscaler (CA)                             │
│     └─ Add nodes when pods can't be scheduled            │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

### **Configuration Propagation Flow**

```
┌──────────────────────────────────────────────────────┐
│  Developer makes change to application config        │
│  (e.g., database connection string)                  │
└────────────┬─────────────────────────────────────────┘
             │
             ↓
┌──────────────────────────────────────────────────────┐
│  1. Update Kubernetes ConfigMap/Secret               │
│     Option A: Manually edit                          │
│     kubectl edit configmap app-config                │
│                                                      │
│     Option B: CI/CD pipeline                         │
│     Jenkins triggers kubectl apply                   │
│     kubectl apply -f configmap.yaml                  │
│                                                      │
│     Option C: Infrastructure as Code                 │
│     Terraform applies configuration                  │
│     Ansible deploys configuration                    │
└────────────┬─────────────────────────────────────────┘
             │
             ↓
┌──────────────────────────────────────────────────────┐
│  2. Pod restart or config reload                     │
│     Option A: Rolling restart                        │
│     kubectl rollout restart deployment/backend       │
│                                                      │
│     Option B: Automatic (if using Reloader)         │
│     Reloader watches ConfigMap/Secret changes        │
│     Automatically restarts affected pods             │
│                                                      │
│     Option C: No restart (if app watches files)      │
│     App detects config change automatically          │
└────────────┬─────────────────────────────────────────┘
             │
             ↓
┌──────────────────────────────────────────────────────┐
│  3. Application uses new configuration               │
│     pod starts → reads env vars → connects to DB    │
│     with new settings                                │
└──────────────────────────────────────────────────────┘
```

---

## 4. Infrastructure Change Impact Matrix

```
┌─────────────────────┬───────────────┬─────────────────┬────────────────┐
│ Infrastructure      │ Change Type   │ Application     │ Downtime       │
│ Component           │               │ Impact          │                │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ Node group          │ Scale up      │ Can schedule    │ None           │
│ (worker nodes)      │ (replicas++)  │ more pods       │ (add capacity) │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│                     │ Scale down    │ Pods evicted    │ Brief          │
│                     │ (replicas--)  │ (PodDisruption) │ (pod restart)  │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ EKS Cluster         │ Version       │ New k8s API     │ 2-3 hrs        │
│                     │ upgrade       │ features        │ (rolling)      │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ Security Group      │ Port/CIDR     │ Connectivity    │ Immediate      │
│                     │ change        │ impact          │ (packet drop)  │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ IAM Role            │ Permission    │ AWS API access  │ None           │
│                     │ add/remove    │ allowed/denied  │ (if pod active)│
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ EBS Volume          │ Expand size   │ More storage    │ None           │
│ (MongoDB storage)   │               │ available       │ (online)       │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ Secret/ConfigMap    │ Value change  │ App behavior    │ ~1 min         │
│                     │               │ after restart   │ (restart pods) │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ Ingress             │ New path/host │ New routes      │ Seconds        │
│ routing             │ added         │ accessible      │ (L7 config)    │
├─────────────────────┼───────────────┼─────────────────┼────────────────┤
│ VPC Firewall        │ Rule change   │ Outbound access │ Immediate      │
│ (Network Policy)    │               │ allowed/denied  │ (packet drop)  │
└─────────────────────┴───────────────┴─────────────────┴────────────────┘
```

---

## 5. Maintenance & Update Procedures

### **Weekly**
```
1. Check cluster health
   kubectl cluster-info
   kubectl get nodes
   kubectl top nodes

2. Verify application pods
   kubectl get pods -n shopnow-ns
   kubectl top pods -n shopnow-ns

3. Review error logs
   aws logs tail /aws/eks/shopnow-app-eks/backend --since 1w
```

### **Monthly**
```
1. Terraform state check
   terraform plan (should show "No changes")

2. Kubernetes updates available?
   kubectl get nodes -o json | grep kubeProxyVersion

3. Docker image updates?
   docker images --digest

4. Database health check
   kubectl exec -it pod/mongodb-0 -- mongo --eval "db.adminCommand('ping')"
```

### **Quarterly**
```
1. Kubernetes version upgrade
   Review changelogs for breaking changes
   Test in dev environment first
   terraform apply with new version

2. Node AMI update
   Automatically updated by AWS (patches only)
   Or manually trigger: aws ec2 describe-images --filters

3. Security audit
   Review IAM permissions
   Review network policies
   Review RBAC roles
```

### **Annually**
```
1. Disaster recovery drill
   Destroy non-critical resources
   Verify rebuild process
   Test backup restoration

2. Capacity planning
   Review 12-month usage trends
   Forecast next year's needs
   Adjust instance types/counts

3. Cost optimization review
   Analyze spending trends
   Look for unused resources
   Consider reserved instances
```

---

## Summary Table

| Layer | Component | Responsibility | Update Frequency |
|-------|-----------|-----------------|------------------|
| **Application** | Frontend/Admin/Backend | Business logic | Daily (deployments) |
| **Container** | Docker images | Packaging | Daily (CI/CD) |
| **Registry** | ECR repositories | Image storage | Daily (push) |
| **Orchestration** | Kubernetes pods | Pod scheduling | Real-time (autoscaling) |
| **Infrastructure** | VPC, EKS, EC2, IAM | Resource provisioning | Monthly (Terraform) |
| **Configuration** | Ansible playbooks | OS/software setup | Monthly (Ansible) |
| **Monitoring** | Prometheus, Grafana, CloudWatch | Observability | Real-time (metrics) |
| **Storage** | EBS, S3, DynamoDB | Data persistence | As-needed (backups) |
| **Security** | Security Groups, RBAC, IAM | Access control | Quarterly (audit) |

