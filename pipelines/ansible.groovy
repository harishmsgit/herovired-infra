def loadSharedInfra(script) {
  def support = script.fileExists('herovired-infra/jenkins/common.groovy') ? script.load('herovired-infra/jenkins/common.groovy') : null
  def config = support ? support.readCommonEnv(script) : [:]
  return [support: support, config: config]
}

def findByCandidates(script, List<String> candidates, String fallbackPattern) {
  for (candidate in candidates) {
    if (script.fileExists(candidate) || script.fileExists("${candidate}/ansible.cfg") || script.fileExists("${candidate}/main.tf")) {
      return candidate
    }
  }
  return script.sh(script: fallbackPattern, returnStdout: true).trim()
}

pipeline {
  agent any

  parameters {
    string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region used by Terraform and EKS')
    string(name: 'TF_STATE_BUCKET', defaultValue: 'shopnow-terraform-state', description: 'S3 bucket for Terraform state')
    string(name: 'LOCK_TABLE', defaultValue: 'shopnow-terraform-locks', description: 'DynamoDB table for Terraform locking')
    string(name: 'ENVIRONMENT', defaultValue: 'dev', description: 'Terraform workspace/environment to read outputs from')
    string(name: 'EKS_CLUSTER_NAME', defaultValue: 'java-spring-eks', description: 'EKS cluster name used when Terraform outputs are unavailable')
    string(name: 'AWS_CREDENTIALS_ID', defaultValue: 'awsId', description: 'Jenkins AWS credentials ID')
    string(name: 'SSH_PRIVATE_KEY_CREDENTIALS_ID', defaultValue: 'management-ec2-ssh-key', description: 'Jenkins SSH private key credentials for the management EC2 instance')
    string(name: 'REMOTE_USER', defaultValue: 'ubuntu', description: 'SSH user for the management EC2 instance')
  }

  environment {
    AWS_REGION = "${params.AWS_REGION}"
    TF_VAR_aws_region = "${params.AWS_REGION}"
    TF_VAR_state_bucket = "${params.TF_STATE_BUCKET}"
    TF_VAR_lock_table = "${params.LOCK_TABLE}"
    EKS_CLUSTER_NAME = "${params.EKS_CLUSTER_NAME}"
    PROJECT_DIR = ''
    TERRAFORM_DIR = ''
    ANSIBLE_DIR = ''
    INVENTORY_FILE = ''
    TF_OUTPUT_FILE = ''
  }

  options {
    skipDefaultCheckout()
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds()
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          def sharedInfra = loadSharedInfra(this)
          def infraSupport = sharedInfra.support
          def sharedConfig = sharedInfra.config ?: [:]

          if (infraSupport) {
            env.AWS_REGION = infraSupport.resolveConfigValue(this, sharedConfig, 'AWS_REGION', 'ap-south-1')
            env.TF_VAR_aws_region = env.AWS_REGION
            env.TF_STATE_BUCKET = infraSupport.resolveConfigValue(this, sharedConfig, 'TF_STATE_BUCKET', 'shopnow-terraform-state')
            env.LOCK_TABLE = infraSupport.resolveConfigValue(this, sharedConfig, 'LOCK_TABLE', 'shopnow-terraform-locks')
            env.EKS_CLUSTER_NAME = infraSupport.resolveConfigValue(this, sharedConfig, 'EKS_CLUSTER_NAME', 'java-spring-eks')
            env.REMOTE_USER = infraSupport.resolveConfigValue(this, sharedConfig, 'REMOTE_USER', 'ubuntu')
            env.SSH_PRIVATE_KEY_CREDENTIALS_ID = infraSupport.resolveConfigValue(this, sharedConfig, 'SSH_PRIVATE_KEY_CREDENTIALS_ID', 'management-ec2-ssh-key')
          }

          def projectDir = script.fileExists('frontend/package.json') && script.fileExists('admin/package.json') && script.fileExists('backend/package.json') ? '.' :
            (script.fileExists('shopNow/frontend/package.json') && script.fileExists('shopNow/admin/package.json') && script.fileExists('shopNow/backend/package.json') ? 'shopNow' : null)
          if (!projectDir) {
            error('Project path not found. Ensure the repository contains frontend, admin, and backend package.json files.')
          }

          def terraformDir = findByCandidates(this, ['herovired-infra/terraform', 'terraform', 'infra/terraform', 'deploy/terraform'], '''
            set -e
            find . -type f -name main.tf | head -n 1 | sed -e 's|/main.tf$||' -e 's|^./||'
          ''')
          def ansibleDir = findByCandidates(this, ['herovired-infra/ansible', 'ansible', 'infra/ansible', 'deploy/ansible'], '''
            set -e
            find . -type d -name ansible | head -n 1 | sed -e 's|^./||'
          ''')

          if (!terraformDir) {
            error('Terraform module path not found. Add terraform/main.tf or update the shared Ansible pipeline discovery rules.')
          }
          if (!ansibleDir) {
            error('Ansible directory not found. Add ansible/ playbooks or update the shared Ansible pipeline discovery rules.')
          }

          env.PROJECT_DIR = projectDir
          env.TERRAFORM_DIR = terraformDir
          env.ANSIBLE_DIR = ansibleDir
          env.ANSIBLE_CONFIG = "${WORKSPACE}/${ansibleDir}/ansible.cfg"
          env.INVENTORY_FILE = "${WORKSPACE}/${ansibleDir}/inventories/generated/hosts.ini"
          env.TF_OUTPUT_FILE = "${WORKSPACE}/${ansibleDir}/terraform-outputs.json"
          echo "Project path: ${env.PROJECT_DIR}"
          echo "Terraform path: ${env.TERRAFORM_DIR}"
          echo "Ansible path: ${env.ANSIBLE_DIR}"
        }
      }
    }

    stage('Validate Tooling') {
      steps {
        dir(env.PROJECT_DIR) {
          sh '''
            . "$WORKSPACE/herovired-infra/scripts/ensure_ansible.sh"
            ansible --version
            terraform version
            aws --version
            python3 --version
          '''
        }
      }
    }

    stage('Read Terraform Outputs') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or Jenkins environment variable.')
          }

          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh '''
                terraform init -input=false -reconfigure \
                  -backend-config="bucket=${TF_STATE_BUCKET}" \
                  -backend-config="key=terraform/terraform.tfstate" \
                  -backend-config="region=${AWS_REGION}" \
                  -backend-config="use_lockfile=true" \
                  -backend-config="dynamodb_table=${LOCK_TABLE}"
                terraform workspace select ${ENVIRONMENT}
                terraform workspace show
                terraform state list | tee "${WORKSPACE}/${TERRAFORM_DIR}/state-list.txt"
                terraform output -json > "${TF_OUTPUT_FILE}"
              '''
            }
          }
          sh '''
            echo "Terraform state sample: $(sed -n '1p' "${WORKSPACE}/${TERRAFORM_DIR}/state-list.txt" 2>/dev/null || echo '<empty>')"
            echo "Terraform output management_ec2_public_ip: $(python3 \"$WORKSPACE/herovired-infra/scripts/generate_ansible_inventory.py\" --print-field management_ec2_public_ip --terraform-output \"${TF_OUTPUT_FILE}\")"
          '''
        }
      }
    }

    stage('Generate Inventory') {
      steps {
        dir(env.PROJECT_DIR) {
          sh '''
            mkdir -p "$(dirname "${INVENTORY_FILE}")"
            python3 "$WORKSPACE/herovired-infra/scripts/generate_ansible_inventory.py" \
              --terraform-output "${TF_OUTPUT_FILE}" \
              --inventory "${INVENTORY_FILE}" \
              --remote-user "${REMOTE_USER}"
            echo "Generated inventory:"
            cat "${INVENTORY_FILE}"
          '''
        }
      }
    }

    stage('Resolve Management Host') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or Jenkins environment variable.')
          }

          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.PROJECT_DIR) {
              sh '''
                HOST=$(awk '/ansible_host/ {print $2}' "${INVENTORY_FILE}" | cut -d'=' -f2)
                echo "Inventory resolved IP: ${HOST}"
                if ! nc -vz -w5 "${HOST}" 22 >/dev/null 2>&1; then
                  echo "Inventory host ${HOST} is not reachable on port 22; resolving current management public IP from AWS..."
                  AWS_PUBLIC_IP=$(aws ec2 describe-instances \
                    --filters "Name=tag:Name,Values=*management-instance" "Name=instance-state-name,Values=running" \
                    --query "Reservations[].Instances[0].PublicIpAddress" \
                    --output text --region ${AWS_REGION})
                  if [ -z "${AWS_PUBLIC_IP}" ] || [ "${AWS_PUBLIC_IP}" = "None" ]; then
                    echo "Failed to resolve a running management instance public IP from AWS."
                    exit 1
                  fi
                  echo "Resolved AWS public IP: ${AWS_PUBLIC_IP}"
                  python3 "$WORKSPACE/herovired-infra/scripts/generate_ansible_inventory.py" \
                    --terraform-output "${TF_OUTPUT_FILE}" \
                    --inventory "${INVENTORY_FILE}" \
                    --remote-user "${REMOTE_USER}" \
                    --public-ip "${AWS_PUBLIC_IP}"
                  echo "Regenerated inventory with AWS fallback:"
                  cat "${INVENTORY_FILE}"
                else
                  echo "Inventory host ${HOST} is reachable."
                fi
              '''
            }
          }
        }
      }
    }

    stage('Configure Management EC2') {
      steps {
        dir(env.PROJECT_DIR) {
          withCredentials([sshUserPrivateKey(credentialsId: env.SSH_PRIVATE_KEY_CREDENTIALS_ID ?: params.SSH_PRIVATE_KEY_CREDENTIALS_ID, keyFileVariable: 'SSH_KEY_FILE', usernameVariable: 'SSH_USERNAME')]) {
            sh '''
              . "$WORKSPACE/herovired-infra/scripts/ensure_ansible.sh"
              export ANSIBLE_SSH_PRIVATE_KEY_FILE="$SSH_KEY_FILE"
              export ANSIBLE_PRIVATE_KEY_FILE="$SSH_KEY_FILE"
              export ANSIBLE_REMOTE_USER="${REMOTE_USER}"
              echo "Using SSH key file: $SSH_KEY_FILE"
              echo "Using SSH user: ${REMOTE_USER}"
              echo "Inventory file: ${INVENTORY_FILE}"
              EKS_CLUSTER_NAME=$(python3 "$WORKSPACE/herovired-infra/scripts/generate_ansible_inventory.py" --print-field eks_cluster_name --terraform-output "${TF_OUTPUT_FILE}" || echo "${EKS_CLUSTER_NAME}")
              ansible-playbook -vvv \
                -i "${INVENTORY_FILE}" \
                "${ANSIBLE_DIR}/playbooks/configure-management.yml" \
                -e "aws_region=${AWS_REGION}" \
                -e "eks_cluster_name=${EKS_CLUSTER_NAME}" \
                -e "ansible_user=${REMOTE_USER}" \
                -e "ansible_ssh_private_key_file=${SSH_KEY_FILE}" \
                -e "ansible_private_key_file=${SSH_KEY_FILE}"
            '''
          }
        }
      }
    }

    stage('Validate Configuration') {
      steps {
        dir(env.PROJECT_DIR) {
          withCredentials([sshUserPrivateKey(credentialsId: env.SSH_PRIVATE_KEY_CREDENTIALS_ID ?: params.SSH_PRIVATE_KEY_CREDENTIALS_ID, keyFileVariable: 'SSH_KEY_FILE', usernameVariable: 'SSH_USERNAME')]) {
            sh '''
              . "$WORKSPACE/herovired-infra/scripts/ensure_ansible.sh"
              export ANSIBLE_SSH_PRIVATE_KEY_FILE="$SSH_KEY_FILE"
              export ANSIBLE_PRIVATE_KEY_FILE="$SSH_KEY_FILE"
              export ANSIBLE_REMOTE_USER="${REMOTE_USER}"
              echo "Validating SSH target with inventory:"
              cat "${INVENTORY_FILE}"
              ansible-playbook -vvv \
                -i "${INVENTORY_FILE}" \
                "${ANSIBLE_DIR}/playbooks/validate-management.yml" \
                -e "ansible_user=${REMOTE_USER}" \
                -e "ansible_ssh_private_key_file=${SSH_KEY_FILE}" \
                -e "ansible_private_key_file=${SSH_KEY_FILE}"
            '''
          }
        }
      }
    }
  }

  post {
    success {
      echo 'Ansible configuration completed and validated successfully.'
    }
    failure {
      echo 'Ansible configuration failed. Review connection, SSH key, and package installation logs.'
    }
  }
}
