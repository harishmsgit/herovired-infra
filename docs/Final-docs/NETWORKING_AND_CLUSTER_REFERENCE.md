# Networking, VPC, and Cluster Quick Reference

This file is meant for quick team discussion and operational handoff. It summarizes the network layout, cluster design, namespace isolation, service communication, external access, and how the application interacts with the infrastructure.

---

## 1. Core AWS Network Layout

### VPC
- VPC name: shopnow infrastructure VPC
- CIDR: 10.20.0.0/16
- Purpose: isolates the application and shared platform resources from other AWS workloads
- Components in the VPC:
  - EKS worker nodes
  - EC2 management host
  - Kubernetes control plane (AWS-managed EKS)
  - Application services and monitoring workloads
  - Load balancers and internal service traffic

### Subnets
- Public subnets:
  - 10.20.1.0/24 in ap-south-1a
  - 10.20.2.0/24 in ap-south-1b
- Design intent:
  - host internet-facing resources and management access
  - provide cross-AZ redundancy for the EKS nodes and ingress-facing workloads
- Private workloads (if later added):
  - private subnet per AZ for databases, internal services, lower-risk components
  - route outbound traffic through NAT Gateway

### Availability Zones
- Region: ap-south-1
- AZs used:
  - ap-south-1a
  - ap-south-1b
- Why this matters:
  - workloads remain available across AZ failure
  - cluster and node groups can be scheduled across two AZs
  - database and service resilience improve with multi-AZ placement

### Route Tables
- Default behavior:
  - public subnet route to Internet Gateway
  - return traffic from internet to the app through the same path
- For internal-only traffic:
  - private subnets route to NAT Gateway for outbound internet access
  - VPC-local traffic stays within the VPC via local route
- Typical route entries:
  - local route: 10.20.0.0/16 -> local
  - public route: 0.0.0.0/0 -> Internet Gateway
  - private route: 0.0.0.0/0 -> NAT Gateway

### Internet Gateway (IGW)
- Used for public internet access to ingress and public-facing components
- Lets user traffic enter the VPC from the internet
- Required for public subnets to allow internet access

### NAT Gateway
- Used for private subnet outbound internet access
- Allows private workloads to download updates, talk to AWS APIs, or reach external services without being directly internet-exposed
- Not used for inbound internet access
- Security implication:
  - private workloads do not have public IPs
  - traffic can leave the VPC, but inbound public requests are blocked unless explicitly exposed through a load balancer or ingress

---

## 2. Kubernetes Cluster and Namespace Design

### Cluster
- Cluster name: shopnow-app-eks
- Managed by AWS EKS
- Worker nodes run in the VPC subnets
- Control plane is AWS-managed, while EC2 nodes are managed by the VPC and EKS node group
- Cluster access:
  - AWS CLI: aws eks update-kubeconfig --region ap-south-1 --name shopnow-app-eks
  - Jenkins / management host uses kubeconfig to interact with the cluster
  - kubectl commands are used from the management EC2 instance or CI runner

### Namespace Layout
- Application namespace:
  - shopnow-ns
- Monitoring namespace:
  - monitor-ns
- Purpose:
  - isolating business app workloads from monitoring resources
  - easier RBAC control and audit
  - cleaner operational separation for app deployments vs observability stack

### Pods in the Application Namespace
- Frontend pod(s)
- Admin pod(s)
- Backend pod(s)
- MongoDB pod(s) or StatefulSet
- Typical access model:
  - frontend and admin are exposed through ingress and services
  - backend is internal-facing but often routes through ingress and service calls
  - MongoDB is internal-only and only reached through backend and Kubernetes service discovery

### Services and DNS
- Kubernetes Service gives stable internal DNS names for workloads
- Example names inside cluster:
  - frontend.shopnow-ns.svc.cluster.local
  - admin.shopnow-ns.svc.cluster.local
  - backend.shopnow-ns.svc.cluster.local
  - mongodb.shopnow-ns.svc.cluster.local
- Pod access pattern:
  - frontend calls backend using service name or internal DNS
  - backend connects to MongoDB using mongodb service name
  - cluster DNS resolves service names automatically

---

## 3. Internal vs External Traffic Flow

### External Traffic
- Users hit the public endpoint through the internet
- Typical flow:
  1. Browser sends request to public hostname / domain
  2. Request is routed to ingress or load balancer in the VPC
  3. Ingress routes to frontend or backend service
  4. Service selects the target pod
  5. Pod handles the request and returns response
- Public access is usually limited to:
  - frontend and admin interfaces
  - ingress / load balancer endpoints
  - TLS termination, path routing, and health checks

### Internal Traffic
- Internal traffic stays inside the VPC and Kubernetes network
- Typical flow:
  1. frontend or admin calls backend service
  2. backend calls MongoDB using cluster DNS
  3. backend may access AWS services through IAM / IRSA / NAT / internal gateways
  4. internal service discovery keeps app components loosely coupled and portable
- Internal traffic is usually:
  - ClusterIP services
  - pod-to-pod communication via Kubernetes network overlay
  - private service access without public exposure

### Traffic Security Model
- Publicly exposed resources must be filtered using:
  - security groups
  - ingress rules
  - Kubernetes ingress rules
  - AWS load balancer security policies
- Private workloads are never exposed directly to the internet by default
- Database and internal services should be reachable only via the cluster network and approved ports

---

## 4. Pod and Service Access Patterns

### Pod Access
- Each pod has an internal IP in the cluster network
- Applications inside the cluster use service names rather than pod IPs directly
- Pod-to-pod communication uses networking rules inside the VPC and Kubernetes overlay
- Ingress is the main public access entry point for external users

### Service Types
- ClusterIP: default internal-only service for pod access inside cluster
- NodePort: less common for direct node-level access, generally not preferred for production
- LoadBalancer / Ingress: public entry points for end users

### Namespace Isolation
- Namespaces provide logical separation, not full network isolation by default
- Access controls can be applied using:
  - RBAC roles
  - service accounts
  - network policies
  - quota policies
- Example:
  - shopnow-ns contains application workloads
  - monitor-ns contains Prometheus and Grafana
  - access between namespaces is allowed only if explicit network and security policies permit it

---

## 5. Ingress, Gateway, and Public Access

### Ingress Pattern
- Ingress resources route traffic based on:
  - hostnames
  - path rules
  - service selection
- Example routing:
  - / -> frontend service
  - /admin -> admin service
  - /api -> backend service

### Gateway Layer
- Ingress acts as the gateway for external application traffic
- The public-facing gateway sits in the VPC and is attached to public subnets
- It forwards traffic to internal services without exposing them directly

### Why This Matters
- Frontend and admin are usually public-facing
- Backend and database remain internal to the cluster and VPC network
- This pattern reduces attack surface and keeps private services out of the public internet

---

## 6. Security Groups and Network Controls

### Typical Security Group Principles
- Management host:
  - allows SSH from approved IPs only
  - allows access to EKS and required infrastructure ports
- EKS worker nodes:
  - allow traffic from ingress and cluster services
  - allow required outbound calls to AWS APIs and package registries
- MongoDB:
  - allow internal cluster connectivity only
  - no public access by default
- Ingress / load balancer:
  - allow inbound 80/443 from internet
  - forward only to approved service ports

### Network Policy (if used)
- Restricts which pods can talk to each other
- Example:
  - frontend can talk to backend
  - backend can talk to MongoDB
  - monitoring can scrape metrics from application pods but not directly manage them

---

## 7. IAM, Roles, and Access to AWS Services

### IAM Roles for EKS and Pods
- EKS cluster role: allows AWS to manage the Kubernetes control plane
- EKS node role: allows worker nodes to pull images and interact with AWS APIs
- Pod identity / IRSA: allows application pods to access AWS services safely without static credentials

### Example AWS Access Patterns
- Backend pod can access S3 for file uploads or artifact storage
- Pods may read secrets from AWS Secrets Manager or similar secret stores
- Application uses temporary AWS credentials from the pod role, not long-lived static keys

---

## 8. Storage and Data Path

### MongoDB Storage
- MongoDB typically runs in a StatefulSet
- Data is stored on persistent volumes backed by EBS
- Storage is in the VPC and remains attached to the database pod or persistent volume claim
- EBS volume protects data across pod restarts and node-level recovery scenarios

### State and Backups
- Terraform state is in S3 bucket
- Locking is handled with DynamoDB table
- This keeps infrastructure state consistent and prevents concurrent writes during plan/apply operations

---

## 9. Monitoring and Observability Network Path

- Prometheus scrapes metrics from pods and services
- Grafana queries Prometheus and shows dashboards
- CloudWatch collects logs and system metrics from the cluster and workloads
- Traffic flow:
  - app pod emits metrics/logs
  - Prometheus scrapes endpoints
  - Grafana visualizes metrics
  - CloudWatch stores logs and health data

---

## 10. Quick “How It Works” Summary

### For the Team
- VPC provides isolated network boundary
- Subnets distribute workloads across AZs
- Internet Gateway exposes public traffic
- NAT allows private resources outbound access
- EKS cluster runs Kubernetes workloads
- Namespaces isolate app vs monitoring resources
- Services provide stable internal names
- Ingress exposes the app externally without exposing backend/database directly
- Pods communicate through Kubernetes service discovery
- AWS IAM and security groups enforce safe access boundaries

---

## 11. Practical Discussion Checklist

Use this during team calls:
- Is the resource public or private?
- Which subnet/AZ does it live in?
- Is it accessible through ingress, service, or internal networking only?
- Does it need public internet access or NAT-only outbound networking?
- Which namespace owns it?
- Which service name should applications use?
- Is the pod using internal DNS or an external endpoint?
- Which IAM role or security group governs its access?
- Does it require monitoring, logs, or metrics collection?

---

## 12. Example Architecture Snapshot

```text
Internet
  |
  v
Ingress / Load Balancer (public)
  |
  +--> Frontend Service -> Frontend Pod
  +--> Admin Service -> Admin Pod
  +--> Backend Service -> Backend Pod
                         |
                         +--> MongoDB Service -> MongoDB Pod (internal-only)

VPC: 10.20.0.0/16
  Public subnet AZ-a: 10.20.1.0/24
  Public subnet AZ-b: 10.20.2.0/24
  IGW: internet access for public resources
  NAT: outbound access for private resources

Cluster: shopnow-app-eks
Namespace: shopnow-ns
Monitoring Namespace: monitor-ns
```

This gives the team a simple mental model for how traffic, isolation, service discovery, and infrastructure boundaries work in the application environment.

---

## 13. Quick AWS CLI Verification Commands

Use these commands for a fast verification during standups, troubleshooting, or infrastructure reviews.

### VPC and Subnets
```bash
# List VPCs
aws ec2 describe-vpcs --region ap-south-1 --query 'Vpcs[*].[VpcId,CidrBlock,State]' --output table

# List subnets in the VPC
aws ec2 describe-subnets --region ap-south-1 --query 'Subnets[*].[SubnetId,AvailabilityZone,CidrBlock,MapPublicIpOnLaunch]' --output table

# Find the VPC ID by name/tag
aws ec2 describe-vpcs --region ap-south-1 --filters "Name=tag:Name,Values=*shopnow*" --query 'Vpcs[*].[VpcId,Tags[*]]' --output table
```

### Route Tables, Internet Gateway, and NAT
```bash
# List route tables for the VPC
aws ec2 describe-route-tables --region ap-south-1 --query 'RouteTables[*].[RouteTableId,VpcId,Associations[*].SubnetId]' --output table

# List internet gateways
aws ec2 describe-internet-gateways --region ap-south-1 --query 'InternetGateways[*].[InternetGatewayId,Attachments[*].VpcId]' --output table

# List NAT gateways
aws ec2 describe-nat-gateways --region ap-south-1 --query 'NatGateways[*].[NatGatewayId,State,VpcId,SubnetId]' --output table
```

### EKS Cluster and Node Health
```bash
# Check EKS cluster status
aws eks describe-cluster --name shopnow-app-eks --region ap-south-1 --query 'cluster.{Name:name,Status:status,Version:version,Endpoint:endpoint}' --output table

# List nodegroups
aws eks list-nodegroups --cluster-name shopnow-app-eks --region ap-south-1 --output table

# List nodes in the cluster (via kubectl)
kubectl get nodes -o wide
```

### Namespace and Pod Validation
```bash
# Check namespaces
kubectl get namespaces

# Check application namespace
kubectl get pods -n shopnow-ns -o wide

# Check monitoring namespace
kubectl get pods -n monitor-ns -o wide

# Check services in the app namespace
kubectl get svc -n shopnow-ns
```

### Ingress and External Access
```bash
# Check ingress resources
kubectl get ingress -A

# Describe ingress rules for troubleshooting
kubectl describe ingress -n shopnow-ns

# Check whether the public path is reachable (basic health check)
curl -I https://<your-domain-or-ingress-host>
```

### AWS Logs and Cluster Insight
```bash
# View recent logs for the backend service
aws logs tail /aws/eks/shopnow-app-eks/backend --follow --region ap-south-1

# View cluster events
kubectl get events -A --sort-by=.metadata.creationTimestamp | tail -50
```

### Quick “Everything is okay?” Checklist
```bash
aws eks describe-cluster --name shopnow-app-eks --region ap-south-1 --query 'cluster.status' --output text
kubectl get nodes
kubectl get pods -A
kubectl get ingress -A
```

Expected quick checks:
- EKS cluster status is ACTIVE
- All nodes are Ready
- Application pods are Running
- Monitoring pods are Running
- Ingress resources exist and are assigned endpoints
- Public and private networking paths are consistent with the design

---

## 14. Team Discussion Summary

When discussing the environment, keep the following model in mind:
- Public access enters through ingress and internet-facing components
- Private resources remain inside the VPC and use NAT for outbound access
- Cluster services communicate internally by DNS and service names
- Namespaces separate application and monitoring workloads
- IAM and security groups restrict who can reach what
- Kubernetes handles service discovery and routing while AWS provides network boundaries

This makes the architecture easier to explain during operational reviews, incident response, and deployment planning.

