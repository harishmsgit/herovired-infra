# ShopNow infrastructure configuration and management guide

This document explains what the infrastructure repository configures, why each component exists, how ShopNow depends on it, and the controlled way to change it.

## 1. Ownership and operating model

| Area | Source of truth | Normal change owner | Apply path |
| --- | --- | --- | --- |
| AWS network, IAM, EKS, ECR, management host | terraform/ | Infrastructure team | Jenkins infra job with RUN_TERRAFORM=true |
| Helm platform releases | terraform/main.tf | Infrastructure team | Same Terraform apply |
| Kubernetes app services, ingress, database, ExternalSecret | kubernetes/ | Platform + application team | Infra job with explicit image URIs |
| Management host package/configuration | ansible/ | Infrastructure team | Infra job with RUN_ANSIBLE_AFTER_APPLY=true |
| Shared deployment defaults | Jenkinsfile, config/common.env, jenkins/common.groovy | Platform team | Reviewed Git change |
| AWS Mongo secret data | AWS Secrets Manager plus Jenkins credential source | Secret owner | Approved secret stage only |

Terraform state is the record of AWS and Terraform-managed Kubernetes resources. Git is the record of intended configuration. Kubernetes live objects should be changed through the pipeline so Terraform, Git, and the cluster do not drift.

## 2. Configuration map

| File or folder | What it configures | Why it matters | Safe update method |
| --- | --- | --- | --- |
| terraform/versions.tf | Terraform and provider constraints | Reproducible provider behavior | Upgrade in a pull request, run init and plan |
| terraform/provider.tf | AWS, Kubernetes, and Helm authentication | Lets Terraform manage AWS and EKS resources | Keep token generation and region aligned with EKS |
| terraform/backend.tf | Backend declaration | Enables S3/DynamoDB remote state | Change only with a state migration plan |
| terraform/variables.tf | Input contract | Makes region, names, size, and tags explicit | Add validation/defaults; update Jenkins variables if needed |
| terraform/terraform.tfvars | Environment values | Local/default variable values | Do not place credentials or secret values here |
| terraform/main.tf | AWS, IAM, EKS, Helm, RBAC, management host | Main desired infrastructure state | Plan, peer review, apply via Jenkins |
| terraform/outputs.tf | Values consumed by Ansible and operators | Avoids hand-discovered IDs | Add outputs when automation needs a new value |
| kubernetes/k8s-manifests/ | App namespace, deployments, services, ingress, database | Declares workload and routing state | Change manifest plus matching app image build |
| kubernetes/external-secrets/ | Service account and secret integration files | Connects Kubernetes to AWS Secrets Manager | Preserve IRSA role annotation and namespace |
| kubernetes/monitoring/ | ServiceMonitor, PrometheusRule, dashboard | Observability integration | CRDs must exist before enabling checks |
| ansible/playbooks/ | Management host packages and configuration | Gives an operator host approved tooling | Run generated inventory, never hard-code mutable IPs |
| scripts/ | Pipeline helpers | Encapsulates recovery/bootstrap logic | Keep scripts idempotent and review shell changes |
| Jenkinsfile | Deployment orchestration | Guards unsafe direct runs and connects app image output to Kubernetes | Preserve explicit-image and explicit-infra-run protections |
| config/common.env | Shared non-secret environment defaults | Keeps child jobs aligned | Update alongside Jenkins values; do not store secrets |
| jenkins/common.groovy | Shared config-resolution functions | Lets pipelines load common.env safely | Test changes in both Jenkinsfiles |

## 3. Terraform-managed AWS components

### Network

The stack uses VPC vpc-02d6c8773f62350e4 with public subnets subnet-088ea34a6270f1000 and subnet-04b729189cb399e38. The public subnets are required for the internet-facing Kubernetes Service of type LoadBalancer.

Change workflow:

1. Change CIDR, route, security-group, or subnet declarations in terraform/main.tf.
2. Run terraform plan from the dev workspace.
3. Review for resource replacement. VPC and subnet replacement is disruptive.
4. Run the approved Terraform Jenkins stage.
5. Check EKS nodes, ELB health, and public endpoints.

Never delete or recreate subnets behind an active EKS cluster without an explicit migration plan.

### EKS

The cluster is shopnow-app-eks, version 1.36, with CONFIG_MAP authentication mode and a public API endpoint. It has two managed node groups:

| Group | Instance type | Purpose |
| --- | --- | --- |
| dev-shopnow-nodes | t3.micro | System/control workload capacity |
| dev-shopnow-workloads | t3.small | ShopNow workload capacity |

The workload node group uses t3.small because the earlier selected instance type was not free-tier eligible. Instance-family availability and cost must be reviewed before changing node groups.

Change workflow:

1. Update the Terraform declaration.
2. Check that the new instance type is available in ap-south-1 and fits the budget.
3. Plan and review desired/min/max capacity.
4. Apply Terraform.
5. Monitor node readiness and pod scheduling.

### IAM, IRSA, and aws-auth

The configuration creates distinct roles for the EKS control plane, node groups, External Secrets, and management host. The aws-auth ConfigMap keeps the node role mapping and maps dev-shopnow-management-role to group shopnow-management.

The management role policy grants only eks:DescribeCluster for discovery. Kubernetes RBAC permits pod/service observation and pods/portforward creation in shopnow-ns. It does not grant secret reads or workload mutation.

Change workflow:

1. Prefer a new narrow policy statement or namespaced Role/RoleBinding.
2. Do not attach administrator policies to solve an access error.
3. Preserve the existing EKS node role mapping in aws-auth.
4. Apply Terraform and verify with kubectl auth can-i.

### ECR

The service repositories are:

| Service | Repository |
| --- | --- |
| Frontend | 559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/frontend |
| Admin | 559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/admin |
| Backend | 559272000457.dkr.ecr.ap-south-1.amazonaws.com/shopnow-dev/backend |

The repositories use immutable tags. This prevents a deployed tag from being silently changed. The ShopNow build creates a new tag in the form build-number-git-sha and passes full image URIs to the infra job.

## 4. Terraform-managed Helm platform components

### External Secrets Operator

The Helm release external-secrets runs in shopnow-ns. Its service account uses an IAM role for service accounts (IRSA) to read the named AWS secret. The ExternalSecret materializes only the requested key as the Kubernetes secret mongo-secret.

Do not:

- decode or commit the MongoDB URI;
- create a second External Secrets Helm release;
- replace the ServiceAccount without preserving its IRSA annotation;
- make application deployment depend on a printed secret value.

### NGINX Ingress Controller

The Helm release ingress-nginx runs in ingress-nginx. It creates:

- IngressClass nginx;
- deployment ingress-nginx-controller;
- a Service of type LoadBalancer;
- internet-facing Classic ELB a2d7eee8d8179427fa36d881be68d64a.

The controller accepts the three ShopNow ingress objects and publishes their paths through the ELB DNS name. The controller is a platform dependency: do not uninstall it while application Ingress objects exist.

## 5. Kubernetes application deployment configuration

| Manifest area | Workload or object | Dependency |
| --- | --- | --- |
| namespace/namespace.yaml | shopnow-ns | All app resources |
| database/mongo-deployment.yaml | MongoDB deployment | mongo-secret created by External Secrets |
| database/mongo-service.yaml | Mongo ClusterIP service | Backend Mongo hostname |
| frontend/deployment.yaml and service.yaml | React static frontend | Explicit ECR frontend image |
| admin/deployment.yaml and service.yaml | React static admin | Explicit ECR admin image |
| backend/deployment.yaml and service.yaml | Node/Express API | Explicit ECR backend image and Mongo secret |
| ingress/ingress-shopnow.yaml | Public path routing | NGINX IngressClass and three services |

The pipeline substitutes image placeholders only with the explicit image URI received from the ShopNow build. It then waits for workload rollouts and applies ingress routing with APP_BASE_PATH=shopnow.

Current public path contract:

| Route | Destination | Rewrite purpose |
| --- | --- | --- |
| /shopnow/ | frontend-service | Serves React customer portal with base path /shopnow |
| /shopnow/admin/ | admin-service | Serves React admin portal with base path /shopnow/admin |
| /shopnow/api/ | backend-service | Rewrites to the backend /api/ routes |

When changing a route, update all of these in one release:

1. APP_BASE_PATH in the infrastructure Jenkinsfile;
2. ingress-shopnow.yaml patterns and rewrite behavior;
3. PUBLIC_URL and REACT_APP_API_BASE_URL build arguments in the ShopNow Jenkinsfile;
4. runbook endpoint URLs;
5. a new frontend/admin image build and deployment.

## 6. Jenkins configuration and safety controls

The job herovired-infra-services accepts:

| Input | Meaning |
| --- | --- |
| FRONTEND_IMAGE_URI, ADMIN_IMAGE_URI, BACKEND_IMAGE_URI | Full immutable image URIs from the ShopNow build |
| IMAGE_TAG | Audit tag used in output and verification |
| DEPLOY_FRONTEND, DEPLOY_ADMIN, DEPLOY_BACKEND | Selects workload rollout |
| RUN_TERRAFORM | Enables AWS/Helm/RBAC reconciliation only when explicitly requested |
| RUN_ANSIBLE_AFTER_APPLY | Enables secret, inventory, and management-host stages |
| RUN_DEPLOYMENT | Enables workload deployment when explicit images are present |
| ALLOW_INFRA_ONLY_RUN | Explicit acknowledgement for a Terraform/Ansible run without app images |

The Initialize stage disables Terraform, Ansible, and workload deployment for a direct webhook run with no explicit image input. This prevents an ordinary documentation or infrastructure commit from changing live infrastructure unexpectedly.

The Terraform stage uses a workspace-local Helm repository cache. It initializes the external-secrets and ingress-nginx repositories before Terraform invokes the Helm provider. This avoids clean-agent failures caused by stale global Helm cache data.

## 7. Ansible and management host

Terraform produces host information. scripts/generate_ansible_inventory.py converts it into ansible/inventories/generated/hosts.ini. The configure-management playbook targets the Amazon Linux management host with user ec2-user.

Change workflow:

1. Change a playbook or role in ansible/.
2. Run ansible-playbook --check if safe for the task.
3. Trigger only RUN_ANSIBLE_AFTER_APPLY=true if the infrastructure state is already correct.
4. Validate with validate-management.yml and an SSH/SSM connection.

The SSH private key is stored in Jenkins credential management under management-ec2-ssh-key. Never commit it to this repository.

## 8. Change management checklist

For every infrastructure change:

1. Make the smallest Git change possible.
2. Run formatting and static validation.
3. Run a Terraform plan against the dev workspace.
4. Review adds, changes, destroys, and replacements.
5. Confirm no active Terraform lock and no concurrent Jenkins run.
6. Commit and push the reviewed change.
7. Run the appropriate Jenkins stage with explicit parameters.
8. Verify AWS resources, Kubernetes rollout, Helm status, ExternalSecret readiness, and public endpoints.
9. Update the operational command document if identifiers, routes, or runbooks changed.

For an urgent failure, gather diagnostics first. Do not delete state records, recreate releases, or widen IAM permissions as the first response.

## 9. Production infrastructure strategy and DevOps patterns

The current environment is a functional development baseline. The following is the operating pattern to use as it is promoted toward production; any item described as a target must be implemented and verified before it is claimed as live.

| DevOps pattern | Current implementation | Production standard / next configuration |
| --- | --- | --- |
| Infrastructure as code | Terraform is the source of truth for AWS, EKS, Helm, IAM, and RBAC. | Separate dev/stage/prod state keys/accounts; protected branches; reviewed plans and a recorded apply approval. |
| Immutable artifact delivery | ECR tags are immutable and deployments receive explicit full image URIs. | Attach image digest, SBOM, provenance, scan result, and release record to every promotion. |
| Desired-state deployment | Jenkins applies versioned manifests from Git. | Use an approved GitOps controller or equivalently strict Jenkins reconciliation, drift detection, and no routine manual kubectl changes. |
| Least privilege | Management access is namespace-scoped and secret reads are excluded. | Use short-lived CI roles, separate human/operator roles, permission boundaries, access review, and no personal AWS-user credentials in Jenkins. |
| Secrets management | AWS Secrets Manager plus External Secrets/IRSA supplies Mongo configuration. | Per-environment KMS encryption, rotation policy, secret access audit, and no secret output in logs or plans. |
| Availability | Managed EKS node groups span public subnets. | Private workload subnets, multi-AZ capacity, autoscaling, disruption budgets, topology spread, backups, and tested regional recovery. |
| Network edge | Ingress-NGINX is exposed through a Classic ELB over HTTP. | Stable DNS, ACM TLS certificate, HTTPS redirect, WAF, ALB/NLB or approved ingress edge, restricted security groups, and DDoS controls. |
| Observability | kubectl/ingress logs and operational runbooks exist. | Central logs, metrics, traces, dashboards, SLOs, alerts, retention, and incident runbooks with an on-call owner. |
| Release safety | Rollout status and health checks run after deployment. | Automated pre/post-deployment gates, canary or blue/green routing, error-budget-based promotion, and tested rollback. |
| Cost/capacity control | Explicit node-group sizes and known instance types are configured. | Tagged cost allocation, capacity forecasts, autoscaler policy, budget alerts, and right-sizing review. |

### Environment and state isolation

Do not deploy production by reusing the \`dev\` Terraform workspace or its state object. Create a separately controlled production environment with its own AWS account where possible; at minimum use a dedicated state bucket/key, DynamoDB lock table, VPC, EKS cluster, IAM roles, Secrets Manager secrets, ECR promotion policy, and Jenkins credentials. Prevent a development Jenkins job from assuming production roles.

### Infrastructure delivery flow

1. An infrastructure change is proposed in Git and reviewed by an infrastructure owner.
2. CI runs \`terraform fmt -check\`, \`terraform validate\`, static security checks, and a non-mutating plan.
3. The plan is reviewed for destructive/replacement actions; production applies require explicit approval.
4. Jenkins acquires the Terraform lock, applies once, and records the plan/apply output as release evidence.
5. Platform health gates verify EKS nodes, system pods, Helm releases, ExternalSecret readiness, ingress address, and application health.
6. A post-deploy drift check and monitoring observation window confirm the desired state is stable.

### Blue/green infrastructure and application cutover

For a low-risk application release, a Kubernetes rolling update with probes can be sufficient. For customer-facing or breaking changes, prepare parallel capacity and use blue/green or canary traffic management:

1. Provision the green workload using a distinct version label and immutable images.
2. Run smoke, integration, security, and performance checks without production traffic.
3. Shift a small, measured traffic share through the ingress/service routing layer.
4. Promote only if availability, latency, 4xx/5xx rate, and business-order metrics meet the release SLOs.
5. Retain the blue deployment for the agreed rollback window; rollback by switching traffic, not by rebuilding artifacts.

This repository currently contains a single-ingress rolling deployment pattern. Implement separate blue/green services, traffic controls, observability gates, and rollback automation before operating blue/green in production.

### Required production configuration baseline

- Encrypt state storage and enforce versioning, lock protection, restricted bucket policy, and audited access.
- Use private EKS endpoint access or tightly restricted public CIDRs, with a controlled administrative path.
- Separate system, platform, and workload node pools; define autoscaling, requests/limits, and disruption budgets.
- Enable control-plane, CloudTrail, VPC flow, load-balancer, and workload logging to a central retention destination.
- Establish backups and restore testing for every persistent store; the current in-cluster single MongoDB pod is not an HA production database.
- Enforce image scanning, dependency scanning, IaC policy checks, and approval gates before promotion.
- Test both an application rollback and a Terraform failure/recovery procedure before the first production release.
