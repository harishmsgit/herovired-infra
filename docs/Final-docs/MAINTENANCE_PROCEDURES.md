# Comprehensive Infrastructure Maintenance Procedures

## Table of Contents

1. **Daily Tasks**
2. **Weekly Tasks**
3. **Monthly Tasks**
4. **Quarterly Tasks**
5. **Annual Tasks**
6. **Emergency Procedures**
7. **Backup & Recovery**
8. **Performance Tuning**

---

## Daily Tasks (5 minutes)

### **1. Check Pipeline Status**

```bash
# Check if Jenkins pipeline passed
curl -s https://jenkins.example.com/api/json | jq '.jobs[] | select(.name=="herovired-infra")'

# Expected: "lastBuild": {"result":"SUCCESS"}
```

**What to look for:**
- ❌ Failed build? → Check console output
- ✅ Success? → Infrastructure is stable
- ⏳ In progress? → Monitor without interruption

---

### **2. Verify Application Health**

```bash
# Check if all pods are running
kubectl get pods -n shopnow-ns -o wide

# Expected output:
# NAME                       READY   STATUS    RESTARTS   AGE
# frontend-abc123-xyz        1/1     Running   0          5d
# admin-def456-uvw           1/1     Running   0          5d
# backend-ghi789-rst         1/1     Running   0          5d
# mongodb-0                  1/1     Running   0          10d
```

**Red flags:**
- ❌ `CrashLoopBackOff` → Pod crashing repeatedly
- ❌ `ImagePullBackOff` → Can't pull container image
- ❌ `Pending` → Can't schedule (insufficient resources)

**Fix**:
```bash
# Check pod details
kubectl describe pod <pod-name> -n shopnow-ns

# View pod logs
kubectl logs <pod-name> -n shopnow-ns
```

---

### **3. Check Node Status**

```bash
# Verify all nodes are Ready
kubectl get nodes

# Expected output:
# NAME                          STATUS   ROLES    AGE    VERSION
# ip-10-20-1-50.ec2.internal   Ready    <none>   30d    v1.28.0
# ip-10-20-2-50.ec2.internal   Ready    <none>   30d    v1.28.0
```

**Red flags:**
- ❌ `NotReady` → Node connectivity issues
- ❌ `SchedulingDisabled` → Node cordoned

**Fix**:
```bash
# Check node resource pressure
kubectl describe node <node-name> | grep -A 5 "Conditions"

# Check disk usage
kubectl describe node <node-name> | grep Allocatable -A 5
```

---

### **4. Monitor Key Metrics**

```bash
# CPU/Memory usage
kubectl top nodes
kubectl top pods -n shopnow-ns

# Expected: CPU <50%, Memory <60% (under normal load)
```

**Red flags:**
- ⚠️ CPU > 80% → Performance degradation risk
- ⚠️ Memory > 80% → OOM kill risk
- ⚠️ Disk > 80% → Storage full risk

---

## Weekly Tasks (30 minutes)

### **1. Review Error Logs**

```bash
# Get errors from backend service
aws logs tail /aws/eks/shopnow-app-eks/backend \
  --since 7d \
  --filter-pattern "ERROR"

# Get all errors across services
for log_group in /aws/eks/shopnow-app-eks/*; do
  echo "=== $log_group ==="
  aws logs tail "$log_group" --since 7d --filter-pattern "ERROR" | head -20
done
```

**Common errors to investigate:**
- Database connection failures
- Missing configuration
- Unhandled exceptions
- Authentication failures

---

### **2. Backup State Files**

```bash
# Backup Terraform state
terraform state pull > backups/state-$(date +%Y%m%d).json

# Upload to S3
aws s3 cp backups/state-$(date +%Y%m%d).json \
  s3://harish-pc-s3-bucket/backups/

# Verify backup
aws s3 ls s3://harish-pc-s3-bucket/backups/
```

**Verify backup contents:**
```bash
# Check if state is valid JSON
jq empty backups/state-$(date +%Y%m%d).json && echo "✓ Valid state backup"
```

---

### **3. Resource Utilization Trend**

```bash
# Get historical data from Prometheus
curl -s 'http://prometheus:9090/api/v1/query_range' \
  --data-urlencode 'query=sum(rate(container_cpu_usage_seconds_total[5m])) by (pod)' \
  --data-urlencode 'start='$(date -d '7 days ago' +%s) \
  --data-urlencode 'end='$(date +%s) \
  --data-urlencode 'step=1h' | jq '.data.result'

# Looking for: Upward trends indicate need for scaling
```

---

### **4. Check Ingress Health**

```bash
# Verify ingress is accessible
curl -I https://shopnow.example.com
# Expected: HTTP 200/301/302 (not 503/504)

curl -I https://shopnow.example.com/api/health
# Expected: HTTP 200

# Check ingress configuration
kubectl get ingress -n shopnow-ns
kubectl describe ingress shopnow-ingress -n shopnow-ns
```

---

### **5. Database Connectivity Test**

```bash
# Check MongoDB is reachable
kubectl exec -it pod/backend-xxx -n shopnow-ns -- \
  curl -s mongodb:27017 || echo "Connected"

# Check database size
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  mongo shopnow --eval "db.stats()"

# Expected output: shows database size, collection count, etc.
```

---

## Monthly Tasks (1-2 hours)

### **1. Full Infrastructure Review**

```bash
# Review Terraform plan for any drift
cd herovired-infra/terraform

terraform plan > /tmp/tfplan.txt
if grep -q "No changes" /tmp/tfplan.txt; then
  echo "✓ Infrastructure matches desired state"
else
  echo "⚠️  Infrastructure drift detected"
  cat /tmp/tfplan.txt | head -50
fi
```

**If drift detected:**
```bash
# Option 1: Apply changes to match desired state
terraform apply

# Option 2: Identify cause of drift
# - Manual AWS console changes? (should use Terraform)
# - Kubernetes controller updates? (expected)
# - Ansible changes? (might need to update ansible.cfg)
```

---

### **2. Kubernetes Version Check**

```bash
# Get current cluster version
kubectl version --short

# Check for available updates (requires AWS CLI)
aws eks describe-cluster \
  --name shopnow-app-eks \
  --region ap-south-1 \
  --query 'cluster.platformVersion'

# Check for recommended updates
aws eks list-updates \
  --cluster-name shopnow-app-eks \
  --region ap-south-1 \
  | jq '.updates'
```

**If updates available:**
```bash
# Plan upgrade (test in dev first!)
# Update terraform/variables.tf:
# kubernetes_version = "1.29"  # was 1.28

terraform plan -var="kubernetes_version=1.29"

# If looks good:
terraform apply

# Expected: Rolling restart of control plane (10-15 min downtime)
```

---

### **3. ECR Image Cleanup**

```bash
# List all images
aws ecr describe-images \
  --repository-name shopnow-dev/backend \
  --region ap-south-1 \
  --query 'imageDetails[*].[imageTags,imageSizeBytes,imagePushedAt]'

# Delete old images (older than 30 days)
for image in $(aws ecr describe-images \
  --repository-name shopnow-dev/backend \
  --region ap-south-1 \
  --query 'imageDetails[*].imageTags' | \
  jq -r '.[]'); do
  
  pushed_date=$(aws ecr describe-images \
    --repository-name shopnow-dev/backend \
    --image-ids imageTag=$image \
    --query 'imageDetails[0].imagePushedAt' | tr -d '"')
  
  if [[ $(date -d "$pushed_date" +%s) -lt $(date -d '30 days ago' +%s) ]]; then
    echo "Deleting old image: $image"
    aws ecr batch-delete-image \
      --repository-name shopnow-dev/backend \
      --image-ids imageTag=$image
  fi
done

# Verify cleanup
aws ecr describe-images --repository-name shopnow-dev/backend | \
  jq '.imageDetails | length'
```

**Expected:** Keep only last 10-20 images per repository

---

### **4. RBAC & Security Audit**

```bash
# Check all service accounts
kubectl get serviceaccount -A

# Check all roles
kubectl get roles -A

# Check all role bindings
kubectl get rolebindings -A

# Verify pod security policies (if using)
kubectl get podsecuritypolicies

# Audit IAM roles
for role in $(aws iam list-roles --query 'Roles[*].RoleName' --region ap-south-1 | \
  jq -r '.[]' | grep shopnow); do
  
  echo "=== Role: $role ==="
  aws iam list-attached-role-policies --role-name $role --region ap-south-1 | \
    jq '.AttachedPolicies'
done
```

**Red flags:**
- ❌ Service accounts with admin roles
- ❌ IAM roles with wildcard permissions (`*`)
- ❌ Public S3 bucket or no encryption

---

### **5. Certificate Expiration Check**

```bash
# Check TLS certificates
kubectl get certificates -A

# Check certificate expiration dates
kubectl get secrets -n shopnow-ns -o json | \
  jq '.items[] | select(.type=="kubernetes.io/tls") | .metadata.name'

# Get expiration details
kubectl get secret shopnow-tls -n shopnow-ns -o jsonpath='{.data.tls\.crt}' | \
  base64 -d | openssl x509 -text | grep -A 2 "Not After"

# Expected: "Not After: YYYY-MM-DD" should be > 30 days away
```

**If expiring soon:**
```bash
# Renew certificate (depends on your cert management approach)
# Option 1: Let's Encrypt (automatic via cert-manager)
# Option 2: Manual AWS ACM certificate

# Update Ingress to use new certificate
kubectl patch ingress shopnow-ingress -n shopnow-ns \
  -p '{"spec":{"tls":[{"hosts":["shopnow.example.com"],"secretName":"shopnow-tls-new"}]}}'
```

---

### **6. Storage Health Check**

```bash
# Check PersistentVolumes
kubectl get pv

# Check PersistentVolumeClaims
kubectl get pvc -n shopnow-ns

# Get storage usage for MongoDB
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  df -h /data/db

# Expected: Should be < 80% full

# Check EBS volumes
aws ec2 describe-volumes \
  --region ap-south-1 \
  --filters "Name=tag:kubernetes.io/cluster/shopnow-app-eks,Values=owned" \
  --query 'Volumes[*].[VolumeId,Size,State]'
```

**If storage > 80%:**
```bash
# Expand EBS volume
# 1. Increase size in Terraform
# 2. Apply changes
# 3. Restart MongoDB pod
# 4. Expand filesystem inside pod

kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  resize2fs /dev/xvda1  # or appropriate device
```

---

## Quarterly Tasks (2-3 hours)

### **1. Kubernetes Version Upgrade**

```bash
# Get current version
kubectl version --short
# Current: Server Version: v1.28.0

# Check for available updates
aws eks list-updates \
  --cluster-name shopnow-app-eks \
  --region ap-south-1

# 1. Update Terraform
cd terraform
# Edit versions.tf or variables.tf:
# variable "kubernetes_version" { default = "1.29" }

# 2. Plan upgrade
terraform plan -var="kubernetes_version=1.29"

# 3. Review changes (control plane first, then nodes)

# 4. Apply upgrade (during maintenance window)
terraform apply

# 5. Monitor upgrade progress
# Check control plane version
kubectl version --short

# Wait for nodes to upgrade
kubectl get nodes -o wide
# All nodes should show new version

# 6. Verify applications still working
kubectl get pods -n shopnow-ns
kubectl get events -n shopnow-ns | head -20
```

**Timeline:** ~2-3 hours (rolling update, no major downtime)

---

### **2. Instance Type Evaluation**

```bash
# Review current instance types
kubectl get nodes -o json | jq '.items[] | {name: .metadata.name, type: .metadata.labels."node.kubernetes.io/instance-type"}'

# Example output:
# {
#   "name": "ip-10-20-1-50.ec2.internal",
#   "type": "t3.medium"
# }

# Check current capacity
kubectl describe nodes | grep -A 5 "Allocated resources"

# If nodes consistently > 80% CPU/Memory:
# → Consider upgrading to t3.large

# If nodes consistently < 20% CPU/Memory:
# → Consider downgrading to t3.small (cost saving)

# To upgrade:
# 1. Update terraform/variables.tf
# 2. terraform apply (triggers node replacement)
# 3. Old nodes cordoned → pods evicted → new nodes created
```

---

### **3. DNS & Network Audit**

```bash
# Check DNS resolution inside cluster
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nslookup backend.shopnow-ns.svc.cluster.local

# Expected: Should return Service IP

# Test external DNS
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  nslookup example.com

# Check network policies
kubectl get networkpolicies -A

# Test connectivity between pods
kubectl run -it --rm debug --image=busybox --restart=Never -- \
  wget -O- http://backend:3000/health

# Check for DNS latency issues
kubectl logs -n kube-system -l k8s-app=kube-dns --tail=50
```

---

### **4. Disaster Recovery Drill**

```bash
# ⚠️ WARNING: This test will cause brief downtime

# 1. Backup current database
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  mongodump --out /tmp/backup

# 2. Simulate node failure (cordon)
kubectl cordon <node-name>

# 3. Watch pods evict and reschedule
kubectl get pods -n shopnow-ns -w

# Expected: Pods migrate to remaining nodes

# 4. Uncordon node
kubectl uncordon <node-name>

# 5. Verify full recovery
kubectl get pods -n shopnow-ns --all-columns

# 6. Restore database (verify backup integrity)
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  mongorestore /tmp/backup
```

---

### **5. Performance Baseline Update**

```bash
# Capture current performance metrics
# Use Prometheus to export baseline

# Current CPU usage by service
curl -s http://prometheus:9090/api/v1/query \
  --data-urlencode 'query=sum(rate(container_cpu_usage_seconds_total[5m])) by (pod_name)' | \
  jq '.data.result' > baseline-cpu-$(date +%Y%m%d).json

# Current Memory usage by service
curl -s http://prometheus:9090/api/v1/query \
  --data-urlencode 'query=sum(container_memory_usage_bytes) by (pod_name)' | \
  jq '.data.result' > baseline-memory-$(date +%Y%m%d).json

# Compare to previous quarter
# Look for upward trends that might require scaling

# Expected growth: ~10-15% per quarter (for growing SaaS)
```

---

## Annual Tasks (Full Day)

### **1. Infrastructure Audit & Security Review**

```bash
# 1. Review all AWS resources
aws ec2 describe-instances \
  --filters "Name=tag:Project,Values=shopnow" \
  --region ap-south-1

aws eks describe-cluster \
  --name shopnow-app-eks \
  --region ap-south-1

aws ecr describe-repositories \
  --region ap-south-1

# 2. Check for unused resources
aws ec2 describe-volumes \
  --filters "Name=status,Values=available" \
  --region ap-south-1
# → Unused EBS volumes = cost

# 3. Security compliance check
# Run CIS Kubernetes benchmark
# https://www.cisecurity.org/benchmark/kubernetes

# 4. Review CloudTrail logs
aws cloudtrail lookup-events \
  --region ap-south-1 \
  --max-items 100 \
  --query 'Events[*].[EventTime,EventName,Username,CloudTrailEvent]'
```

---

### **2. Capacity Planning & Cost Optimization**

```bash
# Analyze usage over past 12 months
aws cloudwatch get-metric-statistics \
  --metric-name CPUUtilization \
  --namespace AWS/EC2 \
  --statistics Average,Maximum \
  --start-time $(date -d '365 days ago' +%Y-%m-%dT00:00:00Z) \
  --end-time $(date +%Y-%m-%dT00:00:00Z) \
  --period 86400  # 1 day \
  --region ap-south-1

# Cost analysis
# Review AWS Cost Explorer
# https://console.aws.amazon.com/cost-management

# Recommendations:
# - Consolidate resources if < 20% average utilization
# - Scale up if > 80% peak utilization
# - Consider Reserved Instances for 20-30% savings
```

---

### **3. Major Version Upgrades**

```bash
# Check for deprecated APIs
kubectl api-resources

# Update Terraform provider
cd terraform
terraform init -upgrade

# Review breaking changes
terraform plan
# Look for "must be replaced" warnings

# Test in dev environment first
cd ../staging
terraform apply

# Review application compatibility
# Run full test suite

# Schedule production upgrade (maintenance window)
cd ../production
terraform apply

# Monitor carefully for 24 hours
```

---

### **4. Backup & Disaster Recovery Validation**

```bash
# 1. Test Terraform state restore
aws s3 cp s3://harish-pc-s3-bucket/backups/state-*.json backups/
# Try to restore from 1 year ago

# 2. Test MongoDB backup restoration
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  mongorestore /path/to/old/backup

# 3. Verify backup automation is working
# Check S3 backup bucket
aws s3 ls s3://harish-pc-s3-bucket/backups/

# Expected: Daily backups for past 365 days

# 4. Test disaster scenario
# - Terminate all nodes
# - Verify infrastructure rebuilds from Terraform
# - Verify applications restart
# - Verify data restored from backups
```

---

## Emergency Procedures

### **Emergency: Pod Crashing in Production**

```bash
# 1. IMMEDIATE: Reduce impact
kubectl scale deployment backend -n shopnow-ns --replicas=0
# This stops the pod from thrashing

# 2. ASSESS: Understand the problem
kubectl logs <pod-name> -n shopnow-ns --previous
# Look for error messages in last 50 lines

kubectl describe pod <pod-name> -n shopnow-ns
# Look for events section

# 3. FIX OPTIONS:
# Option A: Rollback previous version
kubectl rollout undo deployment/backend -n shopnow-ns

# Option B: Fix configuration
kubectl set env deployment/backend \
  DEBUG=true \
  -n shopnow-ns

# Option C: Update image
kubectl set image deployment/backend \
  backend=<ecr-uri>:v1.0.1 \
  -n shopnow-ns

# 4. RECOVER: Restore to operational state
kubectl scale deployment backend -n shopnow-ns --replicas=3

# 5. MONITOR: Watch for recurrence
kubectl logs -f deployment/backend -n shopnow-ns
```

---

### **Emergency: Database Disk Full**

```bash
# 1. IMMEDIATE: Stop writes
kubectl scale deployment backend -n shopnow-ns --replicas=0
# Prevents more data from being written

# 2. CHECK: How full is it?
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- \
  df -h /data/db

# 3. OPTIONS:
# Option A: Expand volume (30 min)
# - In Terraform: increase storage_size from 20 to 50
# - terraform apply
# - Inside pod: resize2fs /dev/xvda1

# Option B: Clean old data (5 min)
# - Connect to MongoDB
kubectl exec -it pod/mongodb-0 -n shopnow-ns -- mongo
# db.logs.deleteMany({createdAt: {$lt: new Date("2024-01-01")}})
# db.sessions.deleteMany({expiresAt: {$lt: new Date()}})

# 4. RECOVER: Resume application
kubectl scale deployment backend -n shopnow-ns --replicas=3

# 5. PREVENT: Add monitoring alert
# Set up CloudWatch alarm for disk > 80%
aws cloudwatch put-metric-alarm \
  --alarm-name "MongoDB disk usage" \
  --metric-name DiskUsagePercent \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold
```

---

### **Emergency: Kubernetes Node Failure**

```bash
# 1. ASSESS: Which node failed?
kubectl get nodes
# Look for "NotReady" status

failed_node="ip-10-20-1-50.ec2.internal"

# 2. IMMEDIATE: Drain the node
kubectl drain $failed_node --ignore-daemonsets --delete-emptydir-data
# This evicts pods to other nodes

# 3. FIX OPTIONS:
# Option A: Restart node (AWS EC2)
aws ec2 reboot-instances --instance-ids i-xxxxxxx --region ap-south-1

# Option B: Replace node (Terraform)
terraform taint aws_eks_node_group.workers
terraform apply  # destroys old group, creates new one

# Option C: Force terminate + recreate
aws ec2 terminate-instances --instance-ids i-xxxxxxx --region ap-south-1
# ASG will auto-launch replacement

# 4. MONITOR: Watch for pod rescheduling
kubectl get pods -n shopnow-ns -w

# 5. RECOVER: Uncordon node once healthy
kubectl uncordon $failed_node
```

---

### **Emergency: Security Breach/Unauthorized Access**

```bash
# 1. IMMEDIATE: Isolate compromised component
kubectl delete pod <compromised-pod> -n shopnow-ns

# 2. PRESERVE: Capture evidence
kubectl logs <pod> -n shopnow-ns > /tmp/pod-logs.txt
kubectl describe pod <pod> -n shopnow-ns > /tmp/pod-describe.txt

# 3. INVESTIGATE: Review access logs
aws logs filter-log-events \
  --log-group-name /aws/eks/shopnow-app-eks/backend \
  --filter-pattern "unauthorized OR denied"

# 4. FIX: Update credentials/secrets
kubectl delete secret api-key -n shopnow-ns
kubectl create secret generic api-key \
  --from-literal=key="<new-secure-key>" \
  -n shopnow-ns

# 5. ROTATE: Update all tokens/passwords
# - Regenerate SSH keys
# - Update IAM access keys
# - Rotate database passwords

# 6. AUDIT: Review permissions
kubectl get rolebindings,clusterrolebindings -A | grep -v system:

# 7. MONITOR: Enable audit logging
# Add to Kubernetes audit policy
```

---

## Performance Tuning

### **Optimize CPU Usage**

```bash
# Identify CPU consumers
kubectl top pods -n shopnow-ns --sort-by=cpu

# 1. Check resource requests/limits
kubectl get pods -n shopnow-ns -o json | \
  jq '.items[] | {name: .metadata.name, cpu: .spec.containers[0].resources}'

# 2. Adjust if CPU usage >> requests (wasting resources)
kubectl set resources deployment backend \
  --requests=cpu=200m,memory=256Mi \
  --limits=cpu=500m,memory=512Mi \
  -n shopnow-ns

# 3. Enable horizontal pod autoscaling
kubectl autoscale deployment backend \
  --min=2 --max=10 \
  --cpu-percent=70 \
  -n shopnow-ns
```

---

### **Optimize Memory Usage**

```bash
# Identify memory consumers
kubectl top pods -n shopnow-ns --sort-by=memory | head -10

# 1. Check for memory leaks
kubectl top pod <pod-name> -n shopnow-ns --containers
# If memory grows over time → memory leak

# 2. Fix memory leak (application bug)
# OR restart pod periodically

# 3. Adjust memory requests/limits
kubectl set resources deployment backend \
  --requests=memory=512Mi \
  --limits=memory=1Gi \
  -n shopnow-ns

# 4. Monitor memory usage over time
# Use Prometheus:
curl -s http://prometheus:9090/api/v1/query_range \
  --data-urlencode 'query=container_memory_usage_bytes{pod_name="backend"}' \
  --data-urlencode 'start='$(date -d '7 days ago' +%s) \
  --data-urlencode 'end='$(date +%s) \
  --data-urlencode 'step=1h'
```

---

### **Optimize Network Latency**

```bash
# Test pod-to-pod communication
kubectl run -it --rm debug --image=nicolaka/netshoot --restart=Never -- \
  ping backend.shopnow-ns.svc.cluster.local

# Check DNS resolution time
kubectl run -it --rm debug --image=nicolaka/netshoot --restart=Never -- \
  nslookup backend.shopnow-ns.svc.cluster.local
# Should be < 5ms

# Check network policies aren't blocking traffic
kubectl get networkpolicies -n shopnow-ns

# Verify subnets are properly configured
aws ec2 describe-subnets --region ap-south-1 | \
  jq '.Subnets[] | {CidrBlock, AvailabilityZone}'
```

---

## Checklist Template

### **Weekly Checklist**

```markdown
[ ] All pods running in shopnow-ns
[ ] Node status all Ready
[ ] No pod restarts in last 24h
[ ] Error logs reviewed
[ ] Backup created to S3
[ ] Metrics look normal (CPU < 50%, Mem < 60%)
[ ] Database connectivity verified
[ ] Ingress health check passed
```

### **Monthly Checklist**

```markdown
[ ] Terraform plan shows no drift
[ ] Kubernetes cluster stable
[ ] All updates available reviewed
[ ] RBAC/Security policies audited
[ ] TLS certificates valid for > 30 days
[ ] Storage < 80% full
[ ] ECR images cleaned up
[ ] Error logs reviewed and actioned
```

### **Quarterly Checklist**

```markdown
[ ] Kubernetes version evaluated for upgrade
[ ] Instance types evaluated for cost optimization
[ ] DNS/Network audit completed
[ ] Disaster recovery drill completed
[ ] Performance baselines updated
[ ] Capacity planning reviewed
```

### **Annual Checklist**

```markdown
[ ] Full security audit completed
[ ] Compliance review (CIS, SOC2, etc.)
[ ] Disaster recovery plan validated
[ ] Backup restoration tested
[ ] Cost optimization reviewed
[ ] Major version upgrades planned
[ ] Capacity for next year planned
```

