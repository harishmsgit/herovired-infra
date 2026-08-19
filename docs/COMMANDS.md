# HeroVired Infrastructure Command Reference

This is the operational runbook for the ShopNow AWS/EKS platform. Prefer the
Jenkins pipeline for shared environments. Local commands are for validation,
diagnosis, and approved recovery. Never print or commit secret values.

## Toolchain checks

```bash
aws --version
terraform version
ansible --version
kubectl version --client
helm version
docker --version
jq --version
```

Confirm AWS identity and EKS access:

```bash
aws sts get-caller-identity
aws eks update-kubeconfig --region ap-south-1 --name shopnow-app-eks
kubectl cluster-info
kubectl get nodes -o wide
```

## Static validation

```bash
terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate
ansible-playbook --syntax-check ansible/playbooks/configure-management.yml
ansible-playbook --syntax-check ansible/playbooks/validate-management.yml
kubectl apply --dry-run=client -f kubernetes/k8s-manifests/namespace/namespace.yaml
```

When installed:

```bash
ansible-lint ansible
yamllint ansible kubernetes
shellcheck scripts/*.sh
```

## Terraform workflow

Terraform uses remote S3 state and locking. Never edit state JSON directly and
never run concurrent applies for the same environment.

```bash
cd terraform
terraform fmt -check -recursive
terraform init -reconfigure
terraform workspace list
terraform workspace select dev
terraform validate
terraform plan -out=tfplan
terraform show tfplan
terraform apply tfplan
terraform output -json
```

Read-only diagnosis:

```bash
terraform state list
terraform state show aws_eks_cluster.main
terraform plan -detailed-exitcode
```

Exit code `0` means no changes, `2` means changes, and `1` means failure. State
imports/removals and lock repair require peer review, a confirmed backup, and no
active apply. Follow HashiCorp's [state guidance](https://developer.hashicorp.com/terraform/language/state).

## Jenkins infrastructure pipeline

The canonical entry point is `Jenkinsfile`. Key controls:

| Parameter | Purpose |
|---|---|
| `RUN_TERRAFORM` | Reconcile cloud/platform infrastructure |
| `RUN_ANSIBLE_AFTER_APPLY` | Configure/validate management host and secrets integration |
| `RUN_DEPLOYMENT` | Deploy workloads when image URIs are provided |
| `ALLOW_INFRA_ONLY_RUN` | Explicitly authorize an infrastructure-only execution |

For application releases, pass explicit immutable frontend, admin and backend
image URIs. Review the build summary and Terraform plan before approving changes.

## Kubernetes operations

```bash
kubectl get namespaces
kubectl get deploy,pods,svc,ingress -n shopnow-ns
kubectl get events -n shopnow-ns --sort-by=.lastTimestamp
kubectl get pods -n shopnow-ns -o wide
kubectl rollout status deployment/backend -n shopnow-ns --timeout=5m
kubectl logs -n shopnow-ns deployment/backend --tail=200
```

Inspect without exposing secrets:

```bash
kubectl get externalsecret,secretstore -n shopnow-ns
kubectl describe externalsecret mongo-secret -n shopnow-ns
kubectl get secret mongo-secret -n shopnow-ns -o jsonpath='{.data.MONGODB_URI}' | wc -c
```

The last command checks presence/size only. Do not decode values in CI logs.

## Ansible

Generate inventory from Terraform output and validate connectivity:

```bash
terraform -chdir=terraform output -json > ansible/terraform-outputs.json
python scripts/generate_ansible_inventory.py \
  --terraform-output ansible/terraform-outputs.json \
  --inventory ansible/inventories/generated/hosts.ini
ansible-inventory -i ansible/inventories/generated/hosts.ini --list
ansible-playbook -i ansible/inventories/generated/hosts.ini \
  ansible/playbooks/configure-management.yml --check
ansible-playbook -i ansible/inventories/generated/hosts.ini \
  ansible/playbooks/validate-management.yml
```

Treat generated inventory and Terraform outputs as sensitive operational
artifacts; do not commit them.

## Monitoring

```bash
kubectl get pods,svc -n monitor-ns
kubectl get servicemonitor,prometheusrule -n monitor-ns
kubectl logs -n monitor-ns -l app.kubernetes.io/name=prometheus --tail=100
kubectl port-forward -n monitor-ns service/prometheus-operated 9090:9090
```

Use the deployed service names returned by `kubectl get svc` if release naming
differs. Validate ShopNow targets, alerts, pod restarts, request failures, latency
and resource saturation after each release.

## Rollback and incident checks

```bash
kubectl rollout history deployment/backend -n shopnow-ns
kubectl rollout undo deployment/backend -n shopnow-ns
kubectl rollout status deployment/backend -n shopnow-ns --timeout=5m
```

For infrastructure failures, stop further applies, preserve logs/plan/state
evidence, confirm the remote lock owner, and use Terraform to reconcile. Do not
manually delete cloud resources that Terraform owns. Never run `terraform destroy`
against a shared environment without an approved decommission plan and verified
backups.

## Repository hygiene

```bash
git status --short
git diff --check
git diff --stat
terraform -chdir=terraform fmt -check -recursive
```

Do not commit `.terraform/`, state, plan files, generated inventories,
kubeconfigs, credentials, decoded secrets, private keys, or debug logs.
