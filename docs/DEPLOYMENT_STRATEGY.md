# HeroVired Infrastructure Deployment Strategy

## Principles

- Version control is the desired-state authority.
- Build once and promote immutable application image digests.
- Terraform follows write, plan, review and apply.
- Changes are small, observable, reversible where possible, and isolated by
  environment.
- Secrets are referenced, never transported through source or logs.

## Delivery flow

```text
Pull request
  -> static validation and security checks
  -> reviewed merge
  -> immutable application images
  -> infrastructure pipeline preflight
  -> Terraform saved plan and approval (when enabled)
  -> External Secrets/Ansible reconciliation (when enabled)
  -> Kubernetes workload rollout
  -> health, smoke and monitoring gates
  -> release evidence
```

The pipeline stages are checkout, initialization, tool assurance, preflight,
AWS validation, image verification, optional Terraform, secrets provisioning,
External Secrets synchronization, inventory generation, optional Ansible,
application rollout, monitoring resources and summary.

## Environment strategy

Development, staging and production use separate state and blast radius.
Prefer separate AWS accounts. At minimum isolate state bucket/key and lock,
VPC/EKS, IAM roles, Secrets Manager values, namespaces, ingress hosts and
monitoring labels. Production applies require protected branches/environments
and an approver who is not the author for high-risk changes.

## Terraform strategy

1. Format and validate configuration.
2. Initialize the correct remote backend and select the intended environment.
3. Generate a saved plan against current remote state.
4. Review replacements, destroys, IAM/network exposure and sensitive outputs.
5. Approve and apply the exact saved plan once.
6. Capture structured outputs and verify cloud/cluster health.

Do not use `-target` as a normal deployment mechanism. Do not directly edit
state. Import existing resources deliberately. HashiCorp's documented workflow
is the baseline: [Terraform provisioning workflow](https://developer.hashicorp.com/terraform/cli/run).

## Kubernetes rollout strategy

Rolling update is the default for stateless ShopNow workloads. Requirements:

- immutable image reference;
- readiness/liveness/startup behavior appropriate to the service;
- backward compatibility during mixed-version operation;
- rollout timeout and automatic pipeline failure;
- capacity/resource limits sufficient for surge;
- post-rollout health and representative transaction checks.

Use canary for material production behavior changes once measurable traffic
splitting and automated abort thresholds exist. Use blue/green for changes that
need an immediate traffic switch and justify duplicate capacity. Recreate is not
acceptable for production stateless services.

Follow [Kubernetes Deployment rollout and rollback](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/).

## Ordering and dependencies

1. Cloud/network/IAM/EKS prerequisites.
2. Cluster access, ingress/controller and External Secrets prerequisites.
3. SecretStore, ExternalSecret and verified `mongo-secret` readiness.
4. Data services with confirmed persistence/recovery.
5. Backend rollout and health.
6. Frontend/admin rollout and ingress verification.
7. Monitoring resources and observation window.

Never restart or replace a stateful workload merely to refresh credentials
without verifying persistent storage, the database's credential-rotation
procedure, and a recoverable backup.

## Release gates

- correct AWS account, region, cluster and namespace;
- no concurrent Terraform apply;
- reviewed plan contains expected operations only;
- requested images exist and have passed policy;
- External Secrets is ready without exposing values;
- all desired replicas become available;
- health/API/ingress checks pass;
- error, latency, restart and saturation signals remain healthy;
- rollback target and operator are identified.

## Rollback and roll-forward

Application rollback redeploys last-known-good image digests through Jenkins.
`kubectl rollout undo` is an emergency bridge and must be reconciled back into
the declared release state. Infrastructure normally rolls forward with a new
reviewed Terraform plan; reverting Git alone does not revert cloud resources.

For failed Terraform apply: stop concurrency, retain logs and plan, inspect state
and real resources, refresh/plan, then reconcile. State surgery is a last resort
requiring backup and peer review. Database recovery follows its own runbook and
RPO/RTO, never an unreviewed infrastructure rollback.

## Production hardening roadmap

- multi-account environment isolation and short-lived federation;
- managed highly available MongoDB with tested point-in-time recovery;
- private networking, NetworkPolicy and restrictive security groups;
- autoscaling, topology spread, disruption budgets and capacity tests;
- policy-as-code admission, signed images, SBOM and provenance enforcement;
- canary analysis, error budgets and automatic rollback signals;
- scheduled EKS, provider and dependency upgrade rehearsals;
- quarterly disaster-recovery and credential-rotation exercises.

## Evidence and audit

Retain source commits, Terraform version/providers, plan/apply result, workspace,
AWS identity, image digests, manifest/config version, approvals, scan/test output,
rollout status, smoke checks, monitoring snapshot, incident notes and rollback
decision. Redact secrets and customer data from every artifact.
