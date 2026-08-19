# HeroVired Infrastructure Architecture

## Purpose

This repository defines and operates the AWS/EKS platform for ShopNow. Terraform
is authoritative for cloud resources; Kubernetes manifests are authoritative for
workloads; Ansible configures and validates the management host; Jenkins
orchestrates controlled reconciliation and deployment.

## System context

```text
Git repositories
  ├─ shopNow: source, tests, Dockerfiles, application images
  └─ herovired-infra: Terraform, Ansible, Kubernetes, Jenkins
            │
            v
       Jenkins pipeline ──> AWS APIs / ECR
            │                    │
            ├─ Terraform ────────┤ VPC, IAM, EKS, EC2, Helm integrations
            ├─ Ansible ──────────┤ management-host configuration/validation
            └─ kubectl ─────────> EKS workloads, ingress, secrets, monitoring
                                      │
                                      v
                         frontend / admin / backend / MongoDB
```

## Layers and source of truth

| Layer | Authority | Examples |
|---|---|---|
| Cloud foundation | `terraform/` | VPC, subnets, routes, IAM, EKS, management host |
| Configuration | `ansible/` | packages, kubectl access, host validation |
| Workloads | `kubernetes/k8s-manifests/` | namespace, Deployments, Services, ingress, MongoDB |
| Secrets projection | External Secrets manifests | AWS Secrets Manager to Kubernetes Secret |
| Monitoring | `kubernetes/monitoring/` | dashboards, ServiceMonitor, alert rules |
| Orchestration | `Jenkinsfile` | validation, plan/apply, image checks, rollout gates |

Do not manage the same resource from multiple layers. Existing resources adopted
by Terraform must be imported once and retain a one-to-one state binding.

## AWS topology

Terraform provisions the network, EKS control plane and worker capacity, IAM
roles/policies, management host, cluster access, and selected Helm/Kubernetes
resources. Public/private exposure is determined by the checked-in network and
ingress configuration. Environments must use isolated accounts where practical,
or at minimum separate state, VPC, cluster, IAM, secrets and namespaces.

## Kubernetes topology

- Application namespace: `shopnow-ns` by default.
- Monitoring namespace: `monitor-ns` by default.
- Stateless workloads: frontend, admin and backend Deployments.
- Data workload: MongoDB 7 Deployment and ClusterIP Service on `27017`.
- Traffic: ingress routes to frontend, admin and backend Services.
- Backend service targets port `5000`; web services target port `80`.
- External Secrets materializes `mongo-secret` from AWS Secrets Manager.

The current single MongoDB pod is suitable for learning/development, not for a
production availability or durability claim.

## State and configuration flow

Terraform uses remote state to map configuration to real AWS resources. Remote
state, locking, encryption, least-privilege access and versioning are mandatory.
The pipeline exports intentional Terraform outputs to generate Ansible inventory;
it must not parse or directly modify raw state.

```text
Terraform configuration -> reviewed plan -> apply -> outputs
                                              │
                                              v
                                     generated inventory
                                              │
                                              v
                                     Ansible validation
```

HashiCorp recommends remote state for team collaboration and warns against
version-controlling state because it can expose secrets and lose locking
guarantees: [Terraform state](https://developer.hashicorp.com/terraform/language/state).

## Identity and secret boundaries

- Jenkins assumes only the permissions needed for the selected stage.
- EKS access uses explicit IAM-to-Kubernetes authorization and least-privilege
  RBAC.
- External Secrets reads `shopnow/mongo` and writes `mongo-secret` at runtime.
- Secret values never belong in Terraform variables files, plans, console output,
  Kubernetes manifests, Jenkins parameters, images or documentation.
- Workloads should use IAM roles for service accounts instead of node-wide cloud
  credentials where AWS access is required.

## Reliability model

Infrastructure changes are serialized by state locking and Jenkins concurrency
controls. Deployments use readiness, rollout status and health gates. Production
design should add multi-AZ worker capacity, topology spread, PodDisruptionBudgets,
autoscaling, controlled node upgrades, managed database resilience, tested backup
restoration and explicit RPO/RTO.

## Observability

Prometheus/Grafana monitor cluster and application signals; CloudWatch supplies
AWS/control-plane evidence where configured. Required operational dimensions are
availability, request errors/latency, pod restarts, resource saturation, rollout
status, node health, ingress health, MongoDB health, ExternalSecret readiness and
Terraform/Jenkins failures.

## Security architecture

- Encrypt traffic externally with TLS and data at rest with AWS/Kubernetes
  supported encryption.
- Restrict security groups and network policies to required flows.
- Scan Terraform, manifests, dependencies and images before promotion.
- Use private workers/endpoints where feasible and restrict administrative entry.
- Pin providers, Helm charts and container images; record image digests.
- Maintain audit logs for cloud, cluster and deployment actions.

## Standards and references

- [AWS Well-Architected Framework](https://docs.aws.amazon.com/wellarchitected/latest/framework/welcome.html)
- [Kubernetes concepts](https://kubernetes.io/docs/concepts/)
- [Terraform core workflow](https://developer.hashicorp.com/terraform/intro/core-workflow)
- [CIS Kubernetes Benchmark](https://www.cisecurity.org/benchmark/kubernetes)
- [OWASP Kubernetes Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Kubernetes_Security_Cheat_Sheet.html)
- [SLSA supply-chain levels](https://slsa.dev/spec/)
