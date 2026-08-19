# HeroVired Infrastructure Project Contract

## Contract status

This document defines stable ownership and integration boundaries for the
ShopNow platform. Terraform/configuration/manifests remain implementation sources
of truth. Breaking changes require review, a migration, rollback criteria, and
coordination with application owners.

## Repository ownership

| Concern | Infrastructure responsibility | Application responsibility |
|---|---|---|
| AWS network, IAM, EKS, EC2 | Own and reconcile | Declare requirements |
| Terraform state and locking | Own and protect | None |
| Kubernetes runtime manifests | Own and deploy | Supply runtime contract |
| Application images | Verify and deploy | Build, test, scan and publish |
| REST/data semantics | Route and observe | Own |
| Secrets transport | AWS/External Secrets integration | Consume by agreed key |
| Release | Rollout and evidence | Immutable artifacts and metadata |

## Pipeline input contract

The root `Jenkinsfile` is the only canonical infrastructure pipeline. It accepts
explicit controls for Terraform, Ansible and workload deployment. Application
deployments must provide immutable image URIs for the components being changed.
An infrastructure-only execution requires explicit authorization; absence of an
image must never silently trigger infrastructure mutation.

Required behavior:

- checkout and path discovery must fail closed;
- AWS identity and required tools are validated before mutation;
- images are verified before Kubernetes deployment;
- Terraform applies only a reviewed saved plan;
- secret readiness is checked without printing values;
- rollout failure fails the job;
- build metadata records inputs and results.

## Runtime integration contract

| Resource | Default | Contract |
|---|---|---|
| Application namespace | `shopnow-ns` | Workloads and `mongo-secret` colocated |
| Monitoring namespace | `monitor-ns` | Monitoring resources isolated |
| Backend target | `5000` | Provides `/api/health` and application API |
| Frontend/admin target | `80` | Nginx serves compiled React assets |
| Mongo service | `mongo:27017` | Cluster-internal only |
| Secret | `mongo-secret` | Contains `MONGODB_URI` and Mongo initialization keys |
| AWS secret | `shopnow/mongo` | External secret authority |

Renaming any item requires an atomic update of producers, consumers, probes,
monitoring, policy and rollback documentation.

## Infrastructure invariants

- One Terraform state binding per managed remote object.
- One active apply per environment.
- State is remote, encrypted, access-controlled, versioned and lock-protected.
- No manually created replacement for a Terraform-owned resource.
- No public database service.
- No plaintext secret in Git, state inputs, logs, images or documentation.
- Production is isolated from development state, credentials and blast radius.
- Every production workload has resource requests/limits, probes and an owner.

## Change classes and approval

| Class | Examples | Minimum control |
|---|---|---|
| Low | dashboard, non-functional alert text | Review and validation |
| Medium | workload image, replicas, resource limits | Review, rollout gates, rollback |
| High | IAM, networking, EKS, ingress, secrets, state | Plan review and explicit approval |
| Critical | destroy, state surgery, data-store replacement | Change record, backup, peer execution, incident readiness |

Emergency work does not remove evidence or review obligations; it may shorten
the approval path under the incident process.

## Security contract

- IAM/RBAC follows least privilege and separates deployment from approval.
- Credentials are short-lived where possible and rotated after disclosure.
- Supply-chain controls retain source commit, digest, SBOM, scan and provenance.
- CI masks secrets and prevents untrusted changes from accessing protected
  credentials.
- Network exposure is deny-by-default and exceptions are documented.
- Vulnerability exceptions are owned, time-bound and approved.

## Availability and recovery contract

Each production service must define owner, service level objectives, alerting,
RPO, RTO, backup method, restoration procedure and last successful exercise.
Terraform state recovery and database recovery are separate procedures. A
successful infrastructure apply is not evidence of successful data restoration.

## Interface evolution

Use backward-compatible, staged changes. Add new configuration before requiring
it; deploy tolerant consumers before removing old values; verify usage; then
remove compatibility code. Pin and deliberately upgrade Terraform providers,
modules, Helm charts, Kubernetes APIs and image versions.

## Definition of done

1. formatting, validation, policy and security checks pass;
2. Terraform plan is reviewed and saved for the apply;
3. deployment health and representative application checks pass;
4. monitoring and alerts cover the change;
5. rollback and recovery are verified in proportion to risk;
6. commit, plan, image digests, approvals and outcome are retained;
7. these four canonical documents remain accurate.
