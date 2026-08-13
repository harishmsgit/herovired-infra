# herovired-infra

Shared infrastructure defaults and helper scripts for ShopNow-style services.

## Contents

- `config/common.env`: shared defaults for Jenkins, Terraform, Ansible, Helm, Kubernetes, and ECR naming.
- `jenkins/common.groovy`: Jenkins helper for loading shared defaults and resolving environment values.
- `pipelines/`: shared Jenkins pipeline implementations used by the service repo wrappers.
- `terraform/`: placeholder for shared Terraform modules and backend conventions.
- `ansible/`: placeholder for shared Ansible roles and playbooks.
- `helm/`: placeholder for shared Helm charts and values.
- `kubernetes/`: placeholder for shared Kubernetes manifests and overlays.
- `scripts/`: shared helper scripts for build, inventory generation, and tool bootstrap.

## How to use

Service repositories can load `jenkins/common.groovy` and read `config/common.env` to reuse common values instead of hardcoding them in each pipeline.

## Deployment integration with ShopNow

This repo is the infrastructure layer. It does not contain the application itself; it provides the cluster, networking, secrets, manifests, and deployment automation that the ShopNow application needs to run in real time.

Runtime flow:

1. The app service builds Docker images and pushes them to Amazon ECR.
2. The infra repo applies the correct Kubernetes manifests and deployment config.
3. EKS pulls the latest image from ECR and starts or rolls out the new pods.
4. The frontend, admin, and backend services become available through the cluster and ingress routing.
5. The developer monitors pod health, logs, and endpoints after deployment.

Related project documentation:

- [../STARTUP-CHECKLIST.md](../STARTUP-CHECKLIST.md) for prerequisites and common commands
- [../DEPLOYMENT-CHECKLIST.md](../DEPLOYMENT-CHECKLIST.md) for deployment and monitoring steps
- [../SERVICE-DEPENDENCY-REALTIME-ROLES.md](../SERVICE-DEPENDENCY-REALTIME-ROLES.md) for runtime responsibilities of each repo
