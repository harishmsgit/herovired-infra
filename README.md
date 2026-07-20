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
