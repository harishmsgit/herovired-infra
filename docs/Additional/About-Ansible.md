In this project, Ansible is responsible only for configuring the EC2 management host. It does not create AWS resources or deploy the ShopNow Kubernetes workloads.

## Current configuration flow

```text
Terraform
   ↓ outputs EC2 public IP and EKS cluster name
Inventory generator
   ↓ creates Ansible hosts.ini
Jenkins
   ↓ injects SSH private key
Ansible configure playbook
   ↓ installs and configures management tools
Ansible validation playbook
   ↓ confirms tools are working
Jenkins
   ↓ deploys workloads to EKS using kubectl
```

### 1. Terraform creates the infrastructure

Terraform provisions resources such as:

- VPC and networking
- EKS cluster
- Management EC2 instance
- IAM roles and security groups

Terraform writes outputs containing the management EC2 public IP and EKS cluster name.

### 2. Jenkins generates the Ansible inventory

Jenkins runs:

```bash
python3 scripts/generate_ansible_inventory.py \
  --terraform-output "$TF_OUTPUT_FILE" \
  --inventory "$INVENTORY_FILE" \
  --remote-user "$REMOTE_USER"
```

This creates an inventory similar to:

```ini
[management]
management ansible_host=<EC2_PUBLIC_IP> ansible_user=ec2-user
```

This tells Ansible which server to configure and which SSH user to use.

### 3. Jenkins provides SSH authentication

The SSH private key is stored in Jenkins Credentials. Jenkins temporarily exposes it as:

```text
ANSIBLE_SSH_PRIVATE_KEY
```

The pipeline connects using:

```bash
ansible-playbook \
  -i "$INVENTORY_FILE" \
  ansible/playbooks/configure-management.yml \
  --private-key "$ANSIBLE_SSH_PRIVATE_KEY" \
  -e "aws_region=${AWS_REGION}" \
  -e "eks_cluster_name=${EKS_CLUSTER_NAME}"
```

### 4. Ansible configures the management server

`configure-management.yml` detects the operating system and installs:

- Docker
- curl
- unzip
- Python pip
- kubectl
- AWS CLI v2

It also:

- starts Docker;
- enables Docker after reboot;
- attempts to generate the EKS kubeconfig.

Because Ansible modules describe the required state, repeated runs generally do not reinstall packages that are already present.

### 5. Ansible validates the server

`validate-management.yml` checks:

```bash
docker --version
kubectl version --client=true
aws --version
```

If these checks succeed, Jenkins knows that the management host has the required tools.

### 6. Jenkins handles the Kubernetes deployment

After Ansible finishes, Jenkins deploys the frontend, admin, and backend workloads using `kubectl`.

Therefore, the ownership is:

| Tool | Responsibility |
|---|---|
| Terraform | Creates AWS infrastructure |
| Ansible | Configures the management EC2 host |
| Jenkins | Orchestrates the complete pipeline |
| Helm/Kubernetes | Runs the application on EKS |
| External Secrets | Synchronizes AWS secrets into Kubernetes |

## Improvements needed in the current Ansible setup

The present implementation works, but I would improve the following:

- Enable SSH host-key validation instead of `host_key_checking = False`.
- Use an EC2 IAM role so kubeconfig does not depend on copied AWS credentials.
- Match the `kubectl` version to the EKS version instead of fixing it at `v1.30.0`.
- Replace `failed_when: false` for kubeconfig generation with controlled failure handling.
- Make AWS CLI installation more idempotent.
- Remove downloaded files from `/tmp`.
- Add Docker-user group configuration if Jenkins or `ec2-user` must run Docker without `sudo`.
- Introduce Ansible roles such as `docker`, `aws_cli`, `kubectl`, and `eks_access`.
- Pin package and tool versions in variables.
- Add automated `ansible-lint` and syntax validation.

## Alternative technologies

### AWS Systems Manager State Manager — best AWS-native alternative

For this AWS-only project, this is the strongest alternative to Ansible.

Advantages:

- No inbound SSH access required.
- No SSH private key in Jenkins.
- Uses the SSM agent and EC2 IAM role.
- Can continuously enforce the required configuration.
- Provides association status and execution history.
- Integrates naturally with AWS.

AWS describes State Manager as a scalable service for keeping managed nodes in a defined state. [AWS State Manager documentation](https://docs.aws.amazon.com/systems-manager/latest/userguide/state-manager-about.html)

A suitable design would be:

```text
Terraform creates EC2 + IAM role
             ↓
EC2 registers with Systems Manager
             ↓
State Manager association installs tools
             ↓
Jenkins checks association status
```

### cloud-init — suitable for initial bootstrap

Terraform can pass cloud-init configuration through EC2 user data. It can install Docker, AWS CLI, and kubectl when the instance first starts.

Advantages:

- Simple
- No separate configuration server
- No SSH required during provisioning

Disadvantages:

- Primarily intended for instance initialization.
- Less convenient for repeated configuration updates.
- Troubleshooting and ongoing enforcement are weaker than Ansible or SSM.

### Packer and immutable AMIs — best for stable production hosts

Packer can build a preconfigured AMI containing Docker, AWS CLI, and kubectl. Terraform then creates EC2 instances from that image.

Advantages:

- Fast and predictable server startup
- Minimal configuration after launch
- Versioned and testable server images
- Reduces configuration drift

The usual production combination is:

```text
Packer builds AMI
Terraform provisions EC2
SSM performs small runtime configuration
```

### Puppet or Chef — useful at large scale

Puppet and Chef continuously enforce configuration across many servers. Puppet uses an agent/server desired-state model. [Puppet documentation](https://www.puppet.com/docs/puppet/6/puppet_overview.html)

They are suitable when managing hundreds or thousands of long-lived machines, but they would add unnecessary operational complexity for a single management EC2 instance.

### Terraform provisioners — not recommended

Terraform supports `remote-exec` and `local-exec`, but HashiCorp recommends purpose-built configuration-management mechanisms because provisioner behavior is difficult for Terraform to model reliably. [Terraform provisioner guidance](https://developer.hashicorp.com/terraform/language/provisioners)

Terraform should continue creating infrastructure, not become the main server-configuration tool.

## Recommendation

For the current capstone:

- Keep Ansible because it clearly demonstrates configuration-management knowledge.
- Refactor the playbook into reusable roles.
- Use the EC2 instance role for AWS/EKS access.
- Enable proper SSH host verification.
- Pin and validate tool versions.

For a more production-oriented AWS design:

```text
Terraform + Packer + AWS Systems Manager
```

That approach removes SSH-key handling, reduces configuration drift, and provides a stronger AWS-native operating model.



---------------------------------------------------

The committed `hosts.ini` is intentionally only a placeholder:

```ini
[management]
# Generated by scripts/generate_ansible_inventory.py
```

Ansible works because Jenkins overwrites this file dynamically inside its build workspace.

## Runtime flow

### 1. Terraform creates or reads the management EC2

After `terraform apply`, Jenkins exports Terraform outputs:

```bash
terraform output -json > "$TF_OUTPUT_FILE"
```

The JSON contains:

```text
management_ec2_public_ip
eks_cluster_name
```

### 2. Jenkins generates a temporary inventory

Jenkins runs:

```bash
python3 scripts/generate_ansible_inventory.py \
  --terraform-output "$TF_OUTPUT_FILE" \
  --inventory "$INVENTORY_FILE" \
  --remote-user "$REMOTE_USER"
```

The script reads the EC2 public IP and replaces the workspace inventory with something like:

```ini
[management]
management ansible_host=13.x.x.x ansible_user=ec2-user
```

### 3. Ansible uses the generated file

Jenkins then runs:

```bash
ansible-playbook \
  -i "$INVENTORY_FILE" \
  ansible/playbooks/configure-management.yml \
  --private-key "$ANSIBLE_SSH_PRIVATE_KEY"
```

Therefore, Ansible uses the generated Jenkins workspace file—not the unchanged placeholder in your local Git checkout.

### 4. Jenkins deletes the workspace

The pipeline ends with:

```groovy
cleanWs()
```

That removes:

- Generated `hosts.ini`
- `terraform-outputs.json`
- Temporary kubeconfigs
- Other build files

This is why you cannot see the populated inventory afterward.

## Important distinction

For a normal application deployment, the upstream pipeline sends:

```text
RUN_TERRAFORM=false
RUN_ANSIBLE_AFTER_APPLY=false
RUN_DEPLOYMENT=true
```

In that case, both inventory generation and Ansible are skipped. The deployment still works because Jenkins deploys directly to EKS with `kubectl`.

Ansible runs only when:

```text
RUN_ANSIBLE_AFTER_APPLY=true
```

## Generate it locally

From the infrastructure repository:

```bash
terraform -chdir=terraform output -json > ansible/terraform-outputs.json

python3 scripts/generate_ansible_inventory.py \
  --terraform-output ansible/terraform-outputs.json \
  --inventory ansible/inventories/generated/hosts.ini \
  --remote-user ec2-user
```

Verify it:

```bash
cat ansible/inventories/generated/hosts.ini

ansible-inventory \
  -i ansible/inventories/generated/hosts.ini \
  --list
```

Test connectivity:

```bash
ansible management \
  -i ansible/inventories/generated/hosts.ini \
  --private-key /path/to/private-key.pem \
  -m ping
```

If you run Ansible locally with the current placeholder file, it will find no management host and perform no configuration. Its successful operation in Jenkins depends on the dynamically generated inventory.