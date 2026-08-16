# Emergency Validation Checklist

Use this during incidents, after deployments, or during a quick operational review. The goal is to validate the most important networking and cluster health checks in under 5 minutes.

---

## 1. Confirm the AWS Network is Healthy

```bash
# Check the VPCs
aws ec2 describe-vpcs --region ap-south-1 --query 'Vpcs[*].[VpcId,CidrBlock,State]' --output table

# Check subnets and AZ mapping
aws ec2 describe-subnets --region ap-south-1 --query 'Subnets[*].[SubnetId,AvailabilityZone,CidrBlock,MapPublicIpOnLaunch]' --output table

# Check route tables and associations
aws ec2 describe-route-tables --region ap-south-1 --query 'RouteTables[*].[RouteTableId,VpcId,Associations[*].SubnetId]' --output table

# Check internet gateways
aws ec2 describe-internet-gateways --region ap-south-1 --query 'InternetGateways[*].[InternetGatewayId,Attachments[*].VpcId]' --output table

# Check NAT gateways
aws ec2 describe-nat-gateways --region ap-south-1 --query 'NatGateways[*].[NatGatewayId,State,VpcId,SubnetId]' --output table
```

### Quick validation points
- VPC exists and is in available state
- Public subnets exist in ap-south-1a and ap-south-1b
- Route table has a local route and public path to Internet Gateway
- NAT exists for private egress if applicable

---

## 2. Confirm the EKS Cluster Is Healthy

```bash
# Check cluster status
aws eks describe-cluster --name shopnow-app-eks --region ap-south-1 --query 'cluster.{Name:name,Status:status,Version:version,Endpoint:endpoint}' --output table

# Check nodegroups
aws eks list-nodegroups --cluster-name shopnow-app-eks --region ap-south-1 --output table
```

```bash
# Check cluster from kubectl
kubectl get nodes -o wide
kubectl get pods -A
kubectl get namespaces
```

### Quick validation points
- Cluster status should be ACTIVE
- Nodes should be Ready
- No node should remain NotReady
- Core system pods should be running

---

## 3. Confirm Application Namespace and Pod Health

```bash
# Application workload namespace
kubectl get pods -n shopnow-ns -o wide
kubectl get svc -n shopnow-ns
kubectl get ingress -n shopnow-ns

# Monitoring namespace
kubectl get pods -n monitor-ns -o wide
```

### Quick validation points
- All app pods are Running or Ready
- No CrashLoopBackOff or ImagePullBackOff
- Services exist for frontend, admin, backend, and MongoDB
- Ingress exists and is assigned an address or host

---

## 4. Confirm Ingress and Public Traffic Path

```bash
# Show ingress resources
kubectl get ingress -A
kubectl describe ingress -n shopnow-ns

# Basic health check
curl -I https://<your-domain-or-ingress-host>
```

### Quick validation points
- Ingress exists
- Backend rules are attached to frontend/admin/backend services
- HTTP/HTTPS endpoints respond successfully
- No 502/503 due to upstream failures

---

## 5. Confirm Private Service Communication

```bash
# Check services and endpoints
kubectl get svc -n shopnow-ns
kubectl get endpoints -n shopnow-ns

# Test internal DNS from a pod or debug container
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup backend.shopnow-ns.svc.cluster.local
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup mongodb.shopnow-ns.svc.cluster.local
```

### Quick validation points
- Backend service resolves correctly
- MongoDB service resolves correctly
- Pod-to-pod communication using internal DNS works
- No broken service endpoints

---

## 6. Confirm Logs and Error Signals

```bash
# View application logs
kubectl logs -n shopnow-ns deployment/backend --tail=100
kubectl logs -n shopnow-ns deployment/frontend --tail=50
kubectl logs -n shopnow-ns deployment/admin --tail=50

# AWS log groups
aws logs tail /aws/eks/shopnow-app-eks/backend --follow --region ap-south-1
```

### Quick validation points
- There are no repeated startup or crash errors
- No database connectivity failures
- No ingress or TLS errors
- No AWS access or policy failures

---

## 7. Quick Incident Decision Tree

### If VPC/Subnet is wrong
- Check VPC, route tables, subnets, and gateway attachments
- Confirm no public subnet misrouting
- Confirm NAT exists when private components need outbound internet access

### If EKS cluster is not healthy
- Check cluster status in AWS
- Check node readiness in kubectl
- Investigate node events and kubelet health

### If pods are failing
- Use kubectl describe pod
- Use kubectl logs <pod-name>
- Check if image pull, config, or secret issues exist

### If ingress is failing
- Check ingress resource status
- Check service endpoints and backend health
- Verify security groups and load balancer rules

### If internal app traffic is failing
- Check service endpoints and DNS resolution
- Check pod-to-pod network connectivity
- Check MongoDB and backend connectivity

---

## 8. 5-Minute Go/No-Go Checklist

Use this during deployment review or incident triage:

```markdown
[ ] VPC exists and is in available state
[ ] Public subnets exist in both AZs
[ ] Route tables point correctly to IGW / NAT
[ ] EKS cluster status is ACTIVE
[ ] All nodes are Ready
[ ] shopnow-ns pods are Running
[ ] monitor-ns pods are Running
[ ] Services exist for app components
[ ] Ingress is present and healthy
[ ] No crash or image pull loop in app pods
[ ] Logs show no critical errors
[ ] Internal DNS resolves for backend and MongoDB
```

If any one of these fails, treat the environment as not ready for normal operations.

---

## 9. Team Shortcut

The fastest sanity check is:

```bash
aws eks describe-cluster --name shopnow-app-eks --region ap-south-1 --query 'cluster.status' --output text
kubectl get nodes
kubectl get pods -A
kubectl get ingress -A
```

This single sequence tells you quickly whether the core AWS network, cluster, pods, and ingress path are healthy.
