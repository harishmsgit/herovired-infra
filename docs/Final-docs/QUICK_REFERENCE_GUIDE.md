# Quick Reference Guide

## Overview

This is a **quick lookup** for common tasks. For detailed procedures, refer to specific guides:
- **TERRAFORM_GUIDE.md** - Infrastructure as Code
- **ANSIBLE_GUIDE.md** - Configuration Management  
- **DEPLOYMENT_AND_MONITORING_GUIDE.md** - Deployment & Monitoring
- **ARCHITECTURE_AND_SYNC.md** - System Architecture
- **MAINTENANCE_PROCEDURES.md** - Daily/Weekly/Monthly/Quarterly tasks

---

## Frequently Used Commands

### **Kubernetes Diagnostics**

| Task | Command |
|------|---------|
| List all pods | `kubectl get pods -A` |
| List pods in namespace | `kubectl get pods -n shopnow-ns` |
| Pod details | `kubectl describe pod <pod-name> -n shopnow-ns` |
| Pod logs | `kubectl logs <pod-name> -n shopnow-ns` |
| Follow logs | `kubectl logs -f <pod-name> -n shopnow-ns` |
| Previous logs (if crashed) | `kubectl logs <pod-name> -n shopnow-ns --previous` |
| Pod resource usage | `kubectl top pods -n shopnow-ns` |
| Node resource usage | `kubectl top nodes` |
| Node status | `kubectl get nodes` |
| Node details | `kubectl describe node <node-name>` |
| List services | `kubectl get svc -n shopnow-ns` |
| List ingress | `kubectl get ingress -n shopnow-ns` |
| Get deployment status | `kubectl rollout status deployment/backend -n shopnow-ns` |
| Restart deployment | `kubectl rollout restart deployment/backend -n shopnow-ns` |
| View events | `kubectl get events -n shopnow-ns --sort-by='.lastTimestamp'` |

---

### **Terraform Management**

| Task | Command |
|------|---------|
| Initialize | `cd terraform && terraform init` |
| Validate | `terraform validate` |
| Plan changes | `terraform plan` |
| Apply changes | `terraform apply` |
| View state | `terraform state list` |
| Show resource | `terraform state show aws_eks_cluster.shopnow` |
| Backup state | `terraform state pull > state-backup.json` |
| Destroy resources | `terraform destroy` |
| Format code | `terraform fmt -recursive` |
| Taint resource | `terraform taint aws_eks_node_group.workers` |

---

### **AWS CLI Essentials**

| Task | Command |
|------|---------|
| Check AWS identity | `aws sts get-caller-identity` |
| List ECS clusters | `aws eks list-clusters --region ap-south-1` |
| Get cluster info | `aws eks describe-cluster --name shopnow-app-eks --region ap-south-1` |
| Update kubeconfig | `aws eks update-kubeconfig --name shopnow-app-eks --region ap-south-1` |
| List EC2 instances | `aws ec2 describe-instances --region ap-south-1` |
| List ECR repos | `aws ecr describe-repositories --region ap-south-1` |
| Get S3 buckets | `aws s3 ls` |
| Check logs | `aws logs tail /aws/eks/shopnow-app-eks/backend --follow` |
| Get metrics | `aws cloudwatch get-metric-statistics --metric-name CPUUtilization --namespace AWS/EC2 --region ap-south-1` |

---

## Architecture at a Glance

```
Developer pushes code
          ↓
   GitHub webhook
          ↓
   Jenkins pipeline
    ├─ Build Docker images
    ├─ Push to ECR
    └─ Trigger infrastructure
          ↓
   Terraform provisions AWS
    ├─ VPC, subnets
    ├─ EKS cluster
    ├─ EC2 management host
    └─ IAM, security groups
          ↓
   Ansible configures cluster
    ├─ Installs Docker, kubectl, Helm
    ├─ Creates namespaces
    ├─ Deploys MongoDB
    └─ Deploys monitoring (Prometheus, Grafana)
          ↓
   Kubernetes deploys applications
    ├─ Frontend deployment
    ├─ Admin deployment
    ├─ Backend deployment
    └─ Services + Ingress for routing
          ↓
   Applications running
    ├─ Pod communication via Kubernetes DNS
    ├─ Backend connects to MongoDB
    ├─ Metrics scraped by Prometheus
    └─ Logs sent to CloudWatch
```

---

## Common Scenarios & Solutions

### **Scenario: Pod won't start**

```bash
# 1. Check pod status
kubectl describe pod <pod-name> -n shopnow-ns

# 2. Common causes (look for events):
# ImagePullBackOff → Image not in ECR
# CrashLoopBackOff → Application error
# Pending → Not enough resources
# ImageInspectError → Image corrupted

# 3. View logs
kubectl logs <pod-name> -n shopnow-ns

# 4. Check image URI
kubectl get deployment backend -n shopnow-ns -o json | jq '.spec.template.spec.containers[0].image'

# 5. Fix (example: update image)
kubectl set image deployment/backend \
  backend=559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend:v1.0.1 \
  -n shopnow-ns
```

---

### **Scenario: Application latency increased**

```bash
# 1. Check resource usage
kubectl top pods -n shopnow-ns
kubectl top nodes

# 2. If CPU/Memory high:
# Option A: Scale up pods
kubectl scale deployment backend -n shopnow-ns --replicas=5

# Option B: Scale up nodes
cd terraform
# Edit variables.tf: desired_size = 4 (was 3)
terraform apply

# 3. Check network issues
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  ping -c 3 mongodb.shopnow-ns.svc.cluster.local

# 4. Check database
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  mongo shopnow --eval "db.serverStatus().opcounters"

# 5. Check for errors in logs
kubectl logs -f deployment/backend -n shopnow-ns | grep -i error
```

---

### **Scenario: Node is NotReady**

```bash
# 1. Identify failed node
kubectl get nodes
# Look for "NotReady" status

failed_node="ip-10-20-1-50.ec2.internal"

# 2. Drain pods from node
kubectl drain $failed_node --ignore-daemonsets --delete-emptydir-data

# 3. Check node status
kubectl describe node $failed_node | grep -A 10 "Conditions"

# 4. Options to recover:
# A. Restart node (keep same instance)
aws ec2 reboot-instances --instance-ids i-xxxxxxx --region ap-south-1

# B. Replace node (destroy + recreate)
terraform taint aws_eks_node_group.workers
terraform apply

# C. Manual termination (ASG auto-replaces)
aws ec2 terminate-instances --instance-ids i-xxxxxxx --region ap-south-1

# 5. Uncordon node when ready
kubectl uncordon $failed_node

# 6. Verify pods reschedule
kubectl get pods -n shopnow-ns -w
```

---

### **Scenario: Database disk full**

```bash
# 1. Check usage
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- df -h /data/db

# 2. Option A: Expand volume (30 min)
cd terraform
# Edit: storage_size = 50 (was 20)
terraform apply

# 3. Option B: Clean old data (5 min)
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- mongo
# db.logs.deleteMany({createdAt: {$lt: new Date("2024-01-01")}})

# 4. Monitor expansion
kubectl get pvc -n shopnow-ns

# 5. Prevent future issues
# Add CloudWatch alarm for disk > 80%
```

---

### **Scenario: Certificate expiring soon**

```bash
# 1. Check expiration
kubectl get secret shopnow-tls -n shopnow-ns -o jsonpath='{.data.tls\.crt}' | \
  base64 -d | openssl x509 -text | grep "Not After"

# 2. If < 30 days:
# Option A: Automatic (cert-manager handles it)
# Just wait, cert-manager renews automatically

# Option B: Manual renewal
# 1. Get new certificate from your provider
# 2. Update secret
kubectl create secret tls shopnow-tls \
  --cert=new-cert.crt \
  --key=new-key.key \
  -n shopnow-ns \
  --dry-run=client -o yaml | kubectl apply -f -

# 3. Verify ingress uses the secret
kubectl get ingress shopnow-ingress -n shopnow-ns -o yaml | grep secretName
```

---

### **Scenario: Need to deploy new version**

```bash
# 1. Docker image built and pushed to ECR
# Image URI: 559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend:v1.1.0

# 2. Update deployment
kubectl set image deployment/backend \
  backend=559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend:v1.1.0 \
  -n shopnow-ns

# 3. Monitor rollout
kubectl rollout status deployment/backend -n shopnow-ns -w

# 4. If rollout fails, rollback
kubectl rollout undo deployment/backend -n shopnow-ns

# 5. To see all versions
kubectl rollout history deployment/backend -n shopnow-ns
```

---

### **Scenario: Infrastructure changes not applying**

```bash
# 1. Check for Terraform lock
aws dynamodb get-item \
  --table-name shopnow-terraform-locks \
  --key '{"LockID":{"S":"harish-pc-s3-bucket/terraform/terraform.tfstate"}}' \
  --region ap-south-1

# If locked (from failed previous run):
# Option A: Wait 30 seconds (auto-timeout)
# Option B: Force unlock (CAREFUL!)
terraform force-unlock <LOCK_ID>

# 2. Check state consistency
terraform plan
# Should show "No changes" if consistent

# 3. If changes show up:
# Option A: Apply them
terraform apply

# Option B: Investigate cause
# Was there manual AWS console change? (should use TF instead)
# Was there Kubernetes controller change? (expected)

# 4. Refresh state (if out of sync)
terraform refresh
```

---

## Quick Troubleshooting Flowchart

```
Problem occurs
│
├─ Is it a Kubernetes issue?
│  ├─ kubectl get pods -n shopnow-ns
│  ├─ kubectl describe pod <pod>
│  ├─ kubectl logs <pod>
│  └─ Fix: kubectl set image / kubectl scale / kubectl rollout
│
├─ Is it an Infrastructure issue?
│  ├─ kubectl get nodes
│  ├─ kubectl top nodes
│  ├─ AWS EC2 console check
│  └─ Fix: terraform plan & apply / kubectl drain & uncordon
│
├─ Is it a Database issue?
│  ├─ kubectl exec -it pod/mongodb-0 -- mongo
│  ├─ Check disk: df -h /data/db
│  ├─ Check connections: db.serverStatus()
│  └─ Fix: Clean data / Expand volume / Restart pod
│
├─ Is it an Application issue?
│  ├─ kubectl logs -f deployment/backend
│  ├─ Check error rate in Grafana
│  ├─ Check recent deployments
│  └─ Fix: kubectl rollout undo / Fix code & redeploy
│
└─ Is it a Monitoring issue?
   ├─ Check Prometheus up? curl http://prometheus:9090/metrics
   ├─ Check Grafana up? curl http://grafana:3000
   ├─ Check CloudWatch logs? aws logs tail /aws/eks/...
   └─ Fix: kubectl restart deployment/prometheus / deployment/grafana
```

---

## Maintenance Schedule at a Glance

| Frequency | Tasks | Time |
|-----------|-------|------|
| **Daily** | • Check pod status<br>• Check node status<br>• Check pipeline<br>• Monitor key metrics | 5 min |
| **Weekly** | • Review error logs<br>• Backup state<br>• Resource trend check<br>• Ingress health<br>• Database connectivity | 30 min |
| **Monthly** | • Full infra review<br>• Kubernetes version check<br>• ECR cleanup<br>• RBAC audit<br>• Certificate expiration<br>• Storage health | 1-2 hr |
| **Quarterly** | • K8s version upgrade<br>• Instance type eval<br>• DNS/network audit<br>• Disaster recovery drill<br>• Performance baseline | 2-3 hr |
| **Annual** | • Security audit<br>• Cost optimization<br>• Major upgrades<br>• Backup validation<br>• Disaster recovery test | 1 day |

---

## Emergency Hotline

**If something is broken:**

1. **Stay calm.** Most issues have happened before.
2. **Don't panic-delete.** All infrastructure is reproducible.
3. **Check these first:**
   - `kubectl get pods -n shopnow-ns`
   - `kubectl get nodes`
   - `aws logs tail /aws/eks/shopnow-app-eks/backend --follow`
4. **Isolate affected component:**
   - `kubectl scale deployment <name> --replicas=0` (stop the pain)
5. **Assess & plan:**
   - Describe the problem
   - Identify root cause
   - Plan the fix
6. **Execute & verify:**
   - Apply fix
   - Monitor results
   - Communicate status
7. **Document & prevent:**
   - Update runbook
   - Add monitoring alert
   - Plan preventive action

---

## Useful Links

- **Kubernetes Docs**: https://kubernetes.io/docs/
- **Terraform AWS Provider**: https://registry.terraform.io/providers/hashicorp/aws/latest/docs
- **EKS Best Practices**: https://aws.github.io/aws-eks-best-practices/
- **Prometheus Docs**: https://prometheus.io/docs/
- **Grafana Docs**: https://grafana.com/docs/
- **Ansible Docs**: https://docs.ansible.com/
- **AWS ECS/EKS**: https://docs.aws.amazon.com/eks/

---

## Document Index

| Document | Purpose | Audience |
|----------|---------|----------|
| **TERRAFORM_GUIDE.md** | Infrastructure provisioning | DevOps/SRE |
| **ANSIBLE_GUIDE.md** | Configuration management | DevOps/SRE |
| **DEPLOYMENT_AND_MONITORING_GUIDE.md** | Application deployment & monitoring | DevOps/SRE/Developers |
| **ARCHITECTURE_AND_SYNC.md** | System architecture & app-infra sync | Architects/Tech Leads |
| **MAINTENANCE_PROCEDURES.md** | Daily/Weekly/Monthly tasks | On-call Engineers |
| **QUICK_REFERENCE_GUIDE.md** (this file) | Quick lookups & common scenarios | Everyone |

