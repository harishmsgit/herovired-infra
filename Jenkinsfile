// Load shared Jenkins helper library if available.  
// Checks both repository-root `jenkins/` and `herovired-infra/jenkins/` paths
// so the pipeline works whether it's executed from the monorepo root
// or when the infra is checked out under a subdirectory.
def loadSharedInfra(script) {
  def candidates = ['jenkins/common.groovy', 'herovired-infra/jenkins/common.groovy']
  def support = null
  for (p in candidates) {
    if (script.fileExists(p)) {
      support = script.load(p)
      break
    }
  }

  def config = support ? support.readCommonEnv(script) : [:]
  return [support: support, config: config]
}

def changeMatches(List<String> changedFiles, List<String> prefixes) {
  return changedFiles.any { file -> prefixes.any { prefix -> file == prefix || file.startsWith(prefix) } }
}

def ensureAwsCredentials(script, String credentialsId, Closure body) {
  if (credentialsId?.trim()) {
    script.withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: credentialsId.trim()]]) {
      body()
    }
  } else {
    body()
  }
}

def buildImageUri(String accountId, String region, String repoPrefix, String serviceName, String imageTag, String repositoryStrategy, String singleRepository) {
  def registry = "${accountId}.dkr.ecr.${region}.amazonaws.com"
  def strategy = repositoryStrategy?.trim()
  def sharedRepo = singleRepository?.trim()
  def tag = (imageTag?.trim() && imageTag.trim() != 'null') ? imageTag.trim() : 'manual'

  if (strategy == 'single-repo' || sharedRepo) {
    def repoName = sharedRepo ?: repoPrefix
    return "${registry}/${repoName}:${serviceName}-${tag}"
  }

  return "${registry}/${repoPrefix}/${serviceName}:${tag}"
}

def ensureImageTag(script) {
  def tag = script.env.IMAGE_TAG?.trim()
  if (!tag || tag == 'null') {
    def buildNumber = script.env.BUILD_NUMBER?.trim()
    if (!buildNumber || buildNumber == 'null') {
      buildNumber = 'manual'
    }

    def shortSha = script.sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    if (!shortSha || shortSha == 'null') {
      shortSha = new java.text.SimpleDateFormat('yyyyMMddHHmmss').format(new java.util.Date())
    }

    tag = "${buildNumber}-${shortSha}".replaceAll(/[^A-Za-z0-9_.-]+/, '-')
    if (!tag || tag == 'null') {
      tag = "manual-${new java.text.SimpleDateFormat('yyyyMMddHHmmss').format(new java.util.Date())}"
    }
    script.env.IMAGE_TAG = tag
  }
  return script.env.IMAGE_TAG
}

pipeline {
  agent any

  options {
    skipDefaultCheckout()
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 75, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
  }

  environment {
    AWS_REGION = 'ap-south-1'
    AWS_ACCOUNT_ID = '559272000457'
    AWS_CREDENTIALS_ID = 'awsId'
    TF_STATE_BUCKET = 'harish-pc-s3-bucket'
    TF_STATE_BUCKET_REGION = 'ap-south-1'
    LOCK_TABLE = 'shopnow-terraform-locks'
    EKS_CLUSTER_NAME = 'shopnow-app-eks'
    ECR_REPO_PREFIX = 'shopnow-dev'
    ECR_REPOSITORY_STRATEGY = 'service-repos'
    SINGLE_ECR_REPOSITORY = ''
    FRONTEND_IMAGE_URI = ''
    ADMIN_IMAGE_URI = ''
    BACKEND_IMAGE_URI = ''
    IMAGE_TAG = ''
    DEPLOY_FRONTEND = 'true'
    DEPLOY_ADMIN = 'true'
    DEPLOY_BACKEND = 'true'
    SSH_PRIVATE_KEY_CREDENTIALS_ID = 'management-ec2-ssh-key'
    // The Terraform management AMI is Amazon Linux, whose default SSH user is ec2-user.
    REMOTE_USER = 'ec2-user'
    RUN_TERRAFORM = 'true'
    RUN_ANSIBLE_AFTER_APPLY = 'true'
    RUN_DEPLOYMENT = 'true'
    K8S_NAMESPACE = 'shopnow-ns'
    MONITORING_NAMESPACE = 'monitor-ns'
    MONITORING_RELEASE_NAME = 'prometheus'
    GRAFANA_ADMIN_PASSWORD = 'dev-grafana-admin'
    ENABLE_MONITORING_CHECKS = 'true'
    AUTO_INSTALL_CLI_TOOLS = 'true'
    // AWS_REGION, TF_STATE_BUCKET, LOCK_TABLE, EKS_CLUSTER_NAME, REMOTE_USER,
    // SSH_PRIVATE_KEY_CREDENTIALS_ID, IMAGE_TAG, INFRA_ROOT, APP_ROOT, TERRAFORM_DIR,
    // ANSIBLE_DIR, K8S_MANIFESTS_DIR, MONITORING_DIR, INVENTORY_FILE, TF_OUTPUT_FILE,
    // INFRA_CHANGED, TERRAFORM_CHANGED, ANSIBLE_CHANGED, DEPLOY_FRONTEND, DEPLOY_ADMIN,
    // DEPLOY_BACKEND are intentionally NOT pre-declared here: pre-declaring them in this
    // block pins their value for the whole run and later env.X reassignments in the
    // Initialize stage silently fail to reach shell steps in later stages. They are set
    // as fresh vars in the Initialize stage instead.
  }

  stages {
    stage('Checkout') {
      steps {
        // The SCM job configuration selects the branch/revision before this pipeline starts.
        // Keep it set to feature/infra-capstone-project-v1 so webhook builds use its latest commit.
        checkout scm
      }
    }

    stage('Initialize') {
      steps {
        script {
          def sharedInfra = loadSharedInfra(this)
          def infraSupport = sharedInfra.support
          def sharedConfig = sharedInfra.config ?: [:]

          env.AWS_REGION = env.AWS_REGION
          env.TF_STATE_BUCKET = env.TF_STATE_BUCKET
          env.LOCK_TABLE = env.LOCK_TABLE
          env.EKS_CLUSTER_NAME = env.EKS_CLUSTER_NAME
          env.REMOTE_USER = env.REMOTE_USER
          env.SSH_PRIVATE_KEY_CREDENTIALS_ID = env.SSH_PRIVATE_KEY_CREDENTIALS_ID
          env.IMAGE_TAG = env.IMAGE_TAG

          if (infraSupport) {
            env.AWS_REGION = infraSupport.resolveConfigValue(this, sharedConfig, 'AWS_REGION', env.AWS_REGION)
            env.TF_STATE_BUCKET = infraSupport.resolveConfigValue(this, sharedConfig, 'TF_STATE_BUCKET', env.TF_STATE_BUCKET)
            env.LOCK_TABLE = infraSupport.resolveConfigValue(this, sharedConfig, 'LOCK_TABLE', env.LOCK_TABLE)
            env.EKS_CLUSTER_NAME = infraSupport.resolveConfigValue(this, sharedConfig, 'EKS_CLUSTER_NAME', env.EKS_CLUSTER_NAME)
            env.REMOTE_USER = infraSupport.resolveConfigValue(this, sharedConfig, 'REMOTE_USER', env.REMOTE_USER)
            env.SSH_PRIVATE_KEY_CREDENTIALS_ID = infraSupport.resolveConfigValue(this, sharedConfig, 'SSH_PRIVATE_KEY_CREDENTIALS_ID', env.SSH_PRIVATE_KEY_CREDENTIALS_ID)
          }

          def infraRootProbe = sh(
            script: '''
              set -e
              if [ -f terraform/main.tf ] && [ -f ansible/playbooks/configure-management.yml ]; then
                echo "."
              elif [ -f herovired-infra/terraform/main.tf ] && [ -f herovired-infra/ansible/playbooks/configure-management.yml ]; then
                echo "herovired-infra"
              else
                echo "__NOT_FOUND__"
              fi
            ''',
            returnStdout: true
          ).trim()

          if (!infraRootProbe || infraRootProbe == '__NOT_FOUND__') {
            error('Could not locate terraform and ansible under herovired-infra/ or the workspace root.')
          }

          // Use local vars as the source of truth; env.X re-reads inside the
          // same script block have proven unreliable on this Jenkins instance.
          def infraRoot = infraRootProbe

          def appRootProbe = sh(
            script: '''
              set -e
              if [ -f frontend/package.json ] && [ -f admin/package.json ] && [ -f backend/package.json ]; then
                echo "."
              elif [ -f shopNow/frontend/package.json ] && [ -f shopNow/admin/package.json ] && [ -f shopNow/backend/package.json ]; then
                echo "shopNow"
              else
                echo "__NOT_FOUND__"
              fi
            ''',
            returnStdout: true
          ).trim()
          def appRoot = appRootProbe == '__NOT_FOUND__' ? '' : appRootProbe

          def terraformDir = infraRoot == '.' ? 'terraform' : "${infraRoot}/terraform"
          def ansibleDir = infraRoot == '.' ? 'ansible' : "${infraRoot}/ansible"
          def k8sManifestsDir = infraRoot == '.' ? 'kubernetes/k8s-manifests' : "${infraRoot}/kubernetes/k8s-manifests"
          def monitoringDir = infraRoot == '.' ? 'kubernetes/monitoring' : "${infraRoot}/kubernetes/monitoring"

          env.INFRA_ROOT = infraRoot
          env.APP_ROOT = appRoot
          env.TERRAFORM_DIR = terraformDir
          env.ANSIBLE_DIR = ansibleDir
          env.K8S_MANIFESTS_DIR = k8sManifestsDir
          env.MONITORING_DIR = monitoringDir
          env.INVENTORY_FILE = "${WORKSPACE}/${ansibleDir}/inventories/generated/hosts.ini"
          env.TF_OUTPUT_FILE = "${WORKSPACE}/${ansibleDir}/terraform-outputs.json"
          env.IMAGE_TAG = ensureImageTag(this)

          def currentSha = env.GIT_COMMIT?.trim()
          if (!currentSha) {
            currentSha = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
          }

          def previousSha = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim() ?: env.GIT_PREVIOUS_COMMIT?.trim()
          if (!previousSha) {
            previousSha = sh(script: 'git rev-parse --verify HEAD~1 2>/dev/null || true', returnStdout: true).trim()
          }

          def changedFiles = []
          if (previousSha) {
            def changeLog = sh(script: "git diff --name-only ${previousSha} ${currentSha}", returnStdout: true).trim()
            changedFiles = changeLog ? changeLog.split('\n') as List<String> : []
          } else {
            echo 'No previous commit found; treating all relevant infra files as changed for initial run.'
            changedFiles = [
              'herovired-infra/terraform/',
              'terraform/',
              'herovired-infra/ansible/',
              'ansible/',
              'herovired-infra/'
            ]
          }

          def terraformChanged = changeMatches(changedFiles, ['herovired-infra/terraform/', 'terraform/'])
          def ansibleChanged = changeMatches(changedFiles, ['herovired-infra/ansible/', 'ansible/'])
          def infraChanged = changeMatches(changedFiles, ['herovired-infra/', 'terraform/', 'ansible/'])

          env.INFRA_CHANGED = infraChanged.toString()
          env.TERRAFORM_CHANGED = terraformChanged.toString()
          env.ANSIBLE_CHANGED = ansibleChanged.toString()

          echo "Infra root: ${infraRoot}"
          echo "Terraform dir: ${terraformDir}"
          echo "Ansible dir: ${ansibleDir}"

          def deployFrontend = env.DEPLOY_FRONTEND == 'true'
          def deployAdmin = env.DEPLOY_ADMIN == 'true'
          def deployBackend = env.DEPLOY_BACKEND == 'true'
          if (terraformChanged) {
            deployFrontend = true
            deployAdmin = true
            deployBackend = true
          }
          env.DEPLOY_FRONTEND = deployFrontend.toString()
          env.DEPLOY_ADMIN = deployAdmin.toString()
          env.DEPLOY_BACKEND = deployBackend.toString()

          echo "Terraform changed: ${terraformChanged}"
          echo "Ansible changed: ${ansibleChanged}"
          echo "Deploy frontend: ${deployFrontend}"
          echo "Deploy admin: ${deployAdmin}"
          echo "Deploy backend: ${deployBackend}"
        }
      }
    }

    stage('Ensure CLI Tools') {
      steps {
        script {
          env.LOCAL_TOOLS_BIN = "${WORKSPACE}/.tools/bin"
          env.PATH = "${env.LOCAL_TOOLS_BIN}:${env.PATH}"

          sh '''
            set -eu
            mkdir -p "${LOCAL_TOOLS_BIN}"
            missing=""
            for cmd in kubectl helm aws terraform; do
              if ! command -v $cmd >/dev/null 2>&1; then
                missing="$missing $cmd"
              fi
            done

            if [ -z "$missing" ]; then
              echo "All required CLI tools present: kubectl helm aws terraform"
              exit 0
            fi

            echo "Missing CLI tools:$missing"
            if [ "${AUTO_INSTALL_CLI_TOOLS}" != 'true' ]; then
              echo "Set parameter AUTO_INSTALL_CLI_TOOLS=true to attempt automatic installation, or install manually with the commands below."
              echo "kubectl: https://kubernetes.io/docs/tasks/tools/"
              echo "helm: https://helm.sh/docs/intro/install/"
              echo "awscli: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
              echo "terraform: https://developer.hashicorp.com/terraform/downloads"
              exit 1
            fi

            echo "AUTO_INSTALL_CLI_TOOLS=true — installing missing tools into ${LOCAL_TOOLS_BIN} (no root/sudo required)"

            for cmd in $missing; do
              case $cmd in
                kubectl)
                  KUBECTL_VER=$(curl -L -s https://dl.k8s.io/release/stable.txt)
                  curl -L -s -o "${LOCAL_TOOLS_BIN}/kubectl" "https://dl.k8s.io/release/${KUBECTL_VER}/bin/linux/amd64/kubectl" || true
                  chmod +x "${LOCAL_TOOLS_BIN}/kubectl" 2>/dev/null || true
                  ;;
                helm)
                  HELM_VER="v3.15.4"
                  curl -L -s "https://get.helm.sh/helm-${HELM_VER}-linux-amd64.tar.gz" -o /tmp/helm.tar.gz || true
                  tar -xzf /tmp/helm.tar.gz -C /tmp || true
                  cp /tmp/linux-amd64/helm "${LOCAL_TOOLS_BIN}/helm" 2>/dev/null || true
                  chmod +x "${LOCAL_TOOLS_BIN}/helm" 2>/dev/null || true
                  rm -rf /tmp/helm.tar.gz /tmp/linux-amd64
                  ;;
                aws)
                  curl -s "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip || true
                  unzip -o -q /tmp/awscliv2.zip -d /tmp || true
                  /tmp/aws/install --install-dir "${LOCAL_TOOLS_BIN}/aws-cli" --bin-dir "${LOCAL_TOOLS_BIN}" --update || true
                  rm -rf /tmp/aws /tmp/awscliv2.zip
                  ;;
                terraform)
                  TERRAFORM_VER="1.9.8"
                  curl -L -s "https://releases.hashicorp.com/terraform/${TERRAFORM_VER}/terraform_${TERRAFORM_VER}_linux_amd64.zip" -o /tmp/terraform.zip || true
                  unzip -o -q /tmp/terraform.zip -d "${LOCAL_TOOLS_BIN}" || true
                  chmod +x "${LOCAL_TOOLS_BIN}/terraform" 2>/dev/null || true
                  rm -f /tmp/terraform.zip
                  ;;
              esac
            done

            still_missing=""
            for cmd in kubectl helm aws terraform; do
              if ! command -v $cmd >/dev/null 2>&1; then
                still_missing="$still_missing $cmd"
              fi
            done

            if [ -n "$still_missing" ]; then
              echo "Still missing after install:$still_missing"
              exit 1
            fi
            echo "CLI tools installed/available in ${LOCAL_TOOLS_BIN}"
          '''
        }
      }
    }
    
    stage('Preflight Checks') {
      steps {
        script {
          sh '''
            set -e
            if command -v ansible-lint >/dev/null 2>&1; then
              echo 'Running ansible-lint...'
              ansible-lint "${ANSIBLE_DIR}" || true
            else
              echo 'ansible-lint not found; skipping'
            fi

            if command -v ansible-playbook >/dev/null 2>&1; then
              echo 'Checking ansible syntax...'
              ansible-playbook --syntax-check "${ANSIBLE_DIR}/playbooks/configure-management.yml" || true
            else
              echo 'ansible-playbook not found; skipping syntax check'
            fi

            if command -v kubeval >/dev/null 2>&1; then
              echo 'Running kubeval...'
              kubeval "${K8S_MANIFESTS_DIR}" || true
            else
              echo 'kubeval not found; skipping'
            fi
          '''

          echo 'Preflight checks completed. kubectl dry-run will be performed after AWS authentication.'
        }
      }
    }

    stage('Validate AWS Access') {
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            sh 'aws sts get-caller-identity --region ${AWS_REGION}'
          }
        }
      }
    }

    stage('Verify Images') {
      when {
        expression { return env.RUN_DEPLOYMENT == 'true' && (env.DEPLOY_FRONTEND == 'true' || env.DEPLOY_ADMIN == 'true' || env.DEPLOY_BACKEND == 'true') }
      }
      steps {
        script {
          def resolvedTag = ensureImageTag(this)
          echo "Using image tag: ${resolvedTag}"

          def checks = []
          if (env.DEPLOY_FRONTEND == 'true') {
            def img = env.FRONTEND_IMAGE_URI?.trim() ? env.FRONTEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'frontend', resolvedTag, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
            checks << [name: 'frontend', uri: img]
          }
          if (env.DEPLOY_ADMIN == 'true') {
            def img = env.ADMIN_IMAGE_URI?.trim() ? env.ADMIN_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'admin', resolvedTag, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
            checks << [name: 'admin', uri: img]
          }
          if (env.DEPLOY_BACKEND == 'true') {
            def img = env.BACKEND_IMAGE_URI?.trim() ? env.BACKEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'backend', resolvedTag, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
            checks << [name: 'backend', uri: img]
          }

          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            checks.each { c ->
              echo "Verifying image for ${c.name}: ${c.uri}"
              if (c.uri.contains('.dkr.ecr.')) {
                def parts = c.uri.tokenize(':')
                def tag = parts[-1]
                def repoPath = c.uri.substring(c.uri.indexOf('/') + 1, c.uri.lastIndexOf(':'))
                sh """
                  set +e
                  aws ecr describe-images --region ${AWS_REGION} --repository-name "${repoPath}" --image-ids imageTag="${tag}" >/tmp/verify-image-${c.name}.log 2>&1
                  status=\$?
                  cat /tmp/verify-image-${c.name}.log
                  if [ \$status -eq 0 ]; then
                    echo "✓ Image exists: ${c.uri}"
                  else
                    echo "⚠ Image not found in ECR (will be built during deployment): ${c.uri}"
                    echo "AWS CLI exit code: \$status"
                  fi
                """
              } else {
                echo "Skipping non-ECR image ${c.uri}"
              }
            }
          }
        }
      }
    }

    stage('Terraform') {
      when {
        expression { return env.RUN_TERRAFORM == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            dir(env.TERRAFORM_DIR) {
              sh '''
                set -e
                export TF_PLUGIN_CACHE_DIR="$HOME/.terraform.d/plugin-cache"
                export TF_INPUT=false
                export TF_LOG_PATH="/tmp/terraform-debug.log"
                export TF_LOG=warn
                mkdir -p "$TF_PLUGIN_CACHE_DIR"
                
                # The working directory is ephemeral, but the committed lock file pins
                # verified provider checksums. Preserve it so the shared plugin cache can
                # reuse providers rather than downloading and resolving them every build.
                echo "Preparing Terraform working directory..."
                rm -rf .terraform || true
                
                if ! aws s3api head-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION} 2>/dev/null; then
                  echo "Terraform S3 backend bucket ${TF_STATE_BUCKET} not found. Attempting creation."
                  if [ "${AWS_REGION}" = "us-east-1" ]; then
                    if ! aws s3api create-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION}; then
                      echo "Bucket ${TF_STATE_BUCKET} already exists or was created concurrently; continuing."
                    fi
                  else
                    if ! aws s3api create-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION} --create-bucket-configuration LocationConstraint=${AWS_REGION}; then
                      echo "Bucket ${TF_STATE_BUCKET} already exists or was created concurrently; continuing."
                    fi
                  fi
                else
                  echo "Terraform S3 backend bucket ${TF_STATE_BUCKET} already exists; skipping creation."
                fi

                if ! aws dynamodb describe-table --table-name ${LOCK_TABLE} --region ${AWS_REGION} 2>/dev/null; then
                  echo "Terraform lock table ${LOCK_TABLE} not found. Attempting creation."
                  if ! aws dynamodb create-table --table-name ${LOCK_TABLE} --attribute-definitions AttributeName=LockID,AttributeType=S --key-schema AttributeName=LockID,KeyType=HASH --billing-mode PAY_PER_REQUEST --region ${AWS_REGION}; then
                    echo "Lock table ${LOCK_TABLE} already exists or was created concurrently; continuing."
                  fi
                else
                  echo "Terraform lock table ${LOCK_TABLE} already exists; skipping creation."
                fi

                terraform init -reconfigure \
                  -backend-config="bucket=${TF_STATE_BUCKET}" \
                  -backend-config="key=terraform/terraform.tfstate" \
                  -backend-config="region=${TF_STATE_BUCKET_REGION}" \
                  -backend-config="dynamodb_table=${LOCK_TABLE}"

                # Recover from stale Terraform state checksum mismatches between S3 and DynamoDB.
                # This is safe when the state already matches the real infra resources and the lock
                # table entry is stale; it prevents the pipeline from failing before plan/apply.
                if terraform state list >/dev/null 2>&1; then
                  echo "Terraform remote state is readable; no digest repair needed."
                else
                  echo "Terraform state checksum mismatch detected. Attempting stale lock repair..."
                  if [ -f "$WORKSPACE/${INFRA_ROOT}/scripts/repair_terraform_state_digest.sh" ]; then
                    TF_STATE_BUCKET="${TF_STATE_BUCKET}" LOCK_TABLE="${LOCK_TABLE}" STATE_KEY="env:/dev/terraform/terraform.tfstate" AWS_REGION="${AWS_REGION}" bash "$WORKSPACE/${INFRA_ROOT}/scripts/repair_terraform_state_digest.sh" --force
                    terraform init -reconfigure \
                      -backend-config="bucket=${TF_STATE_BUCKET}" \
                      -backend-config="key=terraform/terraform.tfstate" \
                      -backend-config="region=${TF_STATE_BUCKET_REGION}" \
                      -backend-config="dynamodb_table=${LOCK_TABLE}"
                  fi
                fi

                  terraform workspace select -or-create ${ENVIRONMENT:-dev} || terraform workspace select default

                  # A previous partial apply created public subnet 10.20.1.0/24 in AWS,
                  # but that resource is absent from the remote state. Re-adopt a subnet
                  # only when its exact CIDR already exists in this Terraform-managed VPC.
                  # This prevents CreateSubnet CIDR-conflict failures without adopting a
                  # subnet from a different VPC.
                  vpc_id=$(terraform state show aws_vpc.main 2>/dev/null | grep -E '^[[:space:]]*id[[:space:]]*=' | head -n 1 | cut -d '"' -f 2)
                  if [ -n "${vpc_id}" ]; then
                    subnet_index=0
                    for subnet_cidr in 10.20.1.0/24 10.20.2.0/24; do
                      subnet_address="aws_subnet.public[${subnet_index}]"
                      if ! terraform state show "${subnet_address}" >/dev/null 2>&1; then
                        existing_subnet_id=$(aws ec2 describe-subnets \
                          --region "${AWS_REGION}" \
                          --filters "Name=vpc-id,Values=${vpc_id}" "Name=cidr-block,Values=${subnet_cidr}" \
                          --query 'Subnets[0].SubnetId' \
                          --output text)
                        if [ "${existing_subnet_id}" != "None" ] && [ -n "${existing_subnet_id}" ]; then
                          echo "Importing existing subnet ${existing_subnet_id} (${subnet_cidr}) into Terraform state."
                          terraform import "${subnet_address}" "${existing_subnet_id}"
                        fi
                      fi
                      subnet_index=$((subnet_index + 1))
                    done

                    # Re-adopt the existing public-subnet route-table associations.
                    # AWS identifies each association import by subnet ID and route-table ID.
                    subnet_index=0
                    for subnet_cidr in 10.20.1.0/24 10.20.2.0/24; do
                      subnet_address="aws_subnet.public[${subnet_index}]"
                      association_address="aws_route_table_association.public[${subnet_index}]"
                      if ! terraform state show "${association_address}" >/dev/null 2>&1; then
                        subnet_id=$(terraform state show "${subnet_address}" 2>/dev/null | grep -E '^[[:space:]]*id[[:space:]]*=' | head -n 1 | cut -d '"' -f 2)
                        route_table_id=$(terraform state show aws_route_table.public 2>/dev/null | grep -E '^[[:space:]]*id[[:space:]]*=' | head -n 1 | cut -d '"' -f 2)
                        if [ -n "${subnet_id}" ] && [ -n "${route_table_id}" ]; then
                          current_route_table_id=$(aws ec2 describe-route-tables \
                            --region "${AWS_REGION}" \
                            --filters "Name=association.subnet-id,Values=${subnet_id}" \
                            --query 'RouteTables[0].RouteTableId' \
                            --output text)
                          if [ "${current_route_table_id}" != "None" ] && [ -n "${current_route_table_id}" ]; then
                            # Import the association currently attached to the subnet. Terraform
                            # can then safely replace it with the desired public route table.
                            echo "Importing current route-table association for ${subnet_id}."
                            terraform import "${association_address}" "${subnet_id}/${current_route_table_id}"
                          fi
                        fi
                      fi
                      subnet_index=$((subnet_index + 1))
                    done

                    # The cluster was created in an earlier partial apply. Import it when
                    # it exists but is absent from state, rather than issuing CreateCluster.
                    if ! terraform state show aws_eks_cluster.main >/dev/null 2>&1; then
                      if aws eks describe-cluster --region "${AWS_REGION}" --name "${EKS_CLUSTER_NAME}" >/dev/null 2>&1; then
                        echo "Importing existing EKS cluster ${EKS_CLUSTER_NAME} into Terraform state."
                        terraform import aws_eks_cluster.main "${EKS_CLUSTER_NAME}"
                      fi
                    fi

                    # External Secrets was originally installed outside Terraform. Import the
                    # owning Helm release by its existing namespace/name before plan so Terraform
                    # upgrades it in place rather than creating a conflicting second release.
                    if ! terraform state show helm_release.external_secrets >/dev/null 2>&1; then
                      export KUBECONFIG="$WORKSPACE/.kubeconfig-terraform"
                      aws eks update-kubeconfig --region "${AWS_REGION}" --name "${EKS_CLUSTER_NAME}" --kubeconfig "$KUBECONFIG"
                      release_status_log=/tmp/external-secrets-release-status.log
                      if helm status external-secrets --namespace "${K8S_NAMESPACE}" >"$release_status_log" 2>&1; then
                        echo "Importing existing Helm release external-secrets in ${K8S_NAMESPACE}."
                        terraform import helm_release.external_secrets "${K8S_NAMESPACE}/external-secrets"
                      elif grep -qi 'release: not found' "$release_status_log"; then
                        echo "External Secrets Helm release is not present; Terraform will install it."
                      else
                        echo "Unable to determine the External Secrets Helm release state:" >&2
                        cat "$release_status_log" >&2
                        exit 1
                      fi
                    fi
                  fi

                  # Skip refresh during validate to avoid slow provider initialization
                terraform validate -json >/dev/null 2>&1 || terraform validate
                
                terraform plan \
                  -var="aws_region=${AWS_REGION}" \
                  -var="cluster_name=${EKS_CLUSTER_NAME}" \
                  -var="ecr_repo_prefix=${ECR_REPO_PREFIX}" \
                  -parallelism=2 \
                  -out=tfplan
                
                terraform apply -auto-approve -parallelism=2 tfplan
                terraform output -json > ${TF_OUTPUT_FILE}
              '''
            }
          }
        }
      }
    }

    stage('Provision Secrets') {
      when {
        expression { return env.RUN_ANSIBLE_AFTER_APPLY == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            def secretScript = env.INFRA_ROOT == '.' ? "$WORKSPACE/scripts/create_aws_secret.sh" : "$WORKSPACE/${env.INFRA_ROOT}/scripts/create_aws_secret.sh"
            sh "${secretScript} shopnow/mongo ${AWS_REGION}"
          }
        }
      }
    }

    stage('Apply External Secrets Resources') {
      when {
        expression { return env.RUN_ANSIBLE_AFTER_APPLY == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            sh '''
              set -e
              # Terraform installs the External Secrets controller with IRSA. Apply these
              # resources before verification so it can fetch shopnow/mongo from AWS.
              aws eks update-kubeconfig --region "${AWS_REGION}" --name "${EKS_CLUSTER_NAME}"
              kubectl create namespace "${K8S_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
              kubectl rollout status deployment/external-secrets -n "${K8S_NAMESPACE}" --timeout=5m
              # The in-place Helm upgrade retains ownership of the existing cluster CRDs.
              # Require the stable v1 API used by the manifests below.
              for crd in secretstores.external-secrets.io externalsecrets.external-secrets.io; do
                kubectl get crd "$crd" >/dev/null
                if ! kubectl get crd "$crd" -o jsonpath='{range .spec.versions[?(@.served==true)]}{.name}{"\\n"}{end}' | grep -qx 'v1'; then
                  echo "Required served API version external-secrets.io/v1 is missing from $crd." >&2
                  exit 1
                fi
              done
              sed -e "s|name: shopnow-ns|name: ${K8S_NAMESPACE}|g" "${K8S_MANIFESTS_DIR}/namespace/namespace.yaml" | kubectl apply -f -
              for file in "${K8S_MANIFESTS_DIR}/database/aws-secretstore.yaml" "${K8S_MANIFESTS_DIR}/database/mongo-secret-externalsecret.yaml"; do
                sed -e "s|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g" "$file" | kubectl apply -f -
              done
            '''
          }
        }
      }
    }

    stage('Verify ExternalSecret Sync') {
      when {
        expression { return env.RUN_ANSIBLE_AFTER_APPLY == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            sh '''
              set -e
              # Wait for ESO's authoritative Ready condition before consuming its output.
              # On failure, print controller and resource diagnostics for actionable logs.
              if ! kubectl wait --for=condition=Ready externalsecret/mongo-secret -n "${K8S_NAMESPACE}" --timeout=120s; then
                echo 'ExternalSecret did not become Ready within timeout.' >&2
                kubectl describe externalsecret mongo-secret -n "${K8S_NAMESPACE}" || true
                kubectl describe secretstore aws-secret-store -n "${K8S_NAMESPACE}" || true
                kubectl logs deployment/external-secrets -n "${K8S_NAMESPACE}" --tail=100 || true
                exit 1
              fi

              kubectl get secret mongo-secret -n "${K8S_NAMESPACE}" >/dev/null
              kubectl get secret mongo-secret -n "${K8S_NAMESPACE}" -o jsonpath='{.data.MONGODB_URI}' | base64 --decode >/tmp/mongo_uri
              if [ ! -s /tmp/mongo_uri ]; then
                echo 'mongo-secret is Ready but does not contain MONGODB_URI.' >&2
                exit 1
              fi
              echo 'mongo-secret is Ready and contains MONGODB_URI.'
            '''
          }
        }
      }
    }

    stage('Generate Inventory') {
      when {
        expression { return env.RUN_ANSIBLE_AFTER_APPLY == 'true' }
      }
      steps {
        sh '''
          set -e
          . "$WORKSPACE/${INFRA_ROOT}/scripts/ensure_ansible.sh"
          python3 "$WORKSPACE/${INFRA_ROOT}/scripts/generate_ansible_inventory.py" \
            --terraform-output "$TF_OUTPUT_FILE" \
            --inventory "$INVENTORY_FILE" \
            --remote-user "$REMOTE_USER"
        '''
      }
    }

    stage('Configure Management Host') {
      when {
        expression { return env.RUN_ANSIBLE_AFTER_APPLY == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'
          }
          withCredentials([sshUserPrivateKey(
            credentialsId: env.SSH_PRIVATE_KEY_CREDENTIALS_ID,
            keyFileVariable: 'ANSIBLE_SSH_PRIVATE_KEY'
          )]) {
            sh '''
              set -e
              . "$WORKSPACE/${INFRA_ROOT}/scripts/ensure_ansible.sh"
              export ANSIBLE_CONFIG="$WORKSPACE/${ANSIBLE_DIR}/ansible.cfg"
              ansible-playbook -i "$INVENTORY_FILE" "$WORKSPACE/${ANSIBLE_DIR}/playbooks/configure-management.yml" \
                --private-key "$ANSIBLE_SSH_PRIVATE_KEY" \
                -e "aws_region=${AWS_REGION}" \
                -e "eks_cluster_name=${EKS_CLUSTER_NAME}"

              ansible-playbook -i "$INVENTORY_FILE" "$WORKSPACE/${ANSIBLE_DIR}/playbooks/validate-management.yml" \
                --private-key "$ANSIBLE_SSH_PRIVATE_KEY"
            '''
          }
        }
      }
    }

    stage('Deploy Application Workloads') {
      when {
        expression { return env.RUN_DEPLOYMENT == 'true' && (env.DEPLOY_FRONTEND == 'true' || env.DEPLOY_ADMIN == 'true' || env.DEPLOY_BACKEND == 'true') }
      }
      steps {
        script {
          def frontendImage = env.FRONTEND_IMAGE_URI?.trim() ? env.FRONTEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'frontend', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
          def adminImage = env.ADMIN_IMAGE_URI?.trim() ? env.ADMIN_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'admin', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
          def backendImage = env.BACKEND_IMAGE_URI?.trim() ? env.BACKEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'backend', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)

          echo "Frontend image URI: ${frontendImage} (source: ${env.FRONTEND_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"
          echo "Admin image URI: ${adminImage} (source: ${env.ADMIN_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"
          echo "Backend image URI: ${backendImage} (source: ${env.BACKEND_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"

          ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'

            sh 'kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
            sh "sed -e 's|name: shopnow-ns|name: ${K8S_NAMESPACE}|g' ${K8S_MANIFESTS_DIR}/namespace/namespace.yaml | kubectl apply -f -"
            sh "for file in ${K8S_MANIFESTS_DIR}/database/*.yaml; do sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' \"$file\" | kubectl apply -f -; done"

            def deployTasks = [:]
            if (env.DEPLOY_FRONTEND == 'true') {
              deployTasks.frontend = {
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_FRONTEND_IMAGE|${frontendImage}|g' ${K8S_MANIFESTS_DIR}/frontend/deployment.yaml | kubectl apply -f -"
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' ${K8S_MANIFESTS_DIR}/frontend/service.yaml | kubectl apply -f -"
              }
            }
            if (env.DEPLOY_ADMIN == 'true') {
              deployTasks.admin = {
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_ADMIN_IMAGE|${adminImage}|g' ${K8S_MANIFESTS_DIR}/admin/deployment.yaml | kubectl apply -f -"
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' ${K8S_MANIFESTS_DIR}/admin/service.yaml | kubectl apply -f -"
              }
            }
            if (env.DEPLOY_BACKEND == 'true') {
              deployTasks.backend = {
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_BACKEND_IMAGE|${backendImage}|g' ${K8S_MANIFESTS_DIR}/backend/deployment.yaml | kubectl apply -f -"
                sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' ${K8S_MANIFESTS_DIR}/backend/service.yaml | kubectl apply -f -"
              }
            }

            if (deployTasks.isEmpty()) {
              echo 'No application workloads selected for deployment.'
            } else {
              parallel deployTasks
            }

            sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' ${K8S_MANIFESTS_DIR}/ingress/ingress-shopnow.yaml | kubectl apply -f -"

            sh 'kubectl rollout status deployment/mongo -n ${K8S_NAMESPACE} --timeout=5m'
            if (env.DEPLOY_FRONTEND == 'true') {
              sh 'kubectl rollout status deployment/frontend -n ${K8S_NAMESPACE} --timeout=5m'
            }
            if (env.DEPLOY_ADMIN == 'true') {
              sh 'kubectl rollout status deployment/admin -n ${K8S_NAMESPACE} --timeout=5m'
            }
            if (env.DEPLOY_BACKEND == 'true') {
              sh 'kubectl rollout status deployment/backend -n ${K8S_NAMESPACE} --timeout=5m'
            }

            if (env.ENABLE_MONITORING_CHECKS == 'true') {
              sh 'kubectl create namespace ${MONITORING_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
              sh "for file in ${MONITORING_DIR}/*.yaml; do sed -e 's|namespace: monitor-ns|namespace: ${MONITORING_NAMESPACE}|g' -e 's|namespace=\"shopnow-ns\"|namespace=\"${K8S_NAMESPACE}\"|g' -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_MONITORING_RELEASE|${MONITORING_RELEASE_NAME}|g' \"$file\" | kubectl apply -f -; done"
              sh 'kubectl get pods -n ${MONITORING_NAMESPACE}'
              sh 'kubectl get servicemonitor -n ${MONITORING_NAMESPACE} || true'
              sh 'kubectl get prometheusrule -n ${MONITORING_NAMESPACE} || true'
            }

            sh 'kubectl get pods -n ${K8S_NAMESPACE} -o wide'
            sh 'kubectl get svc -n ${K8S_NAMESPACE}'
            sh 'kubectl get ingress -n ${K8S_NAMESPACE} || true'
          }
        }
      }
    }

    stage('Summary') {
      steps {
        echo "Infra job finished. Terraform changed=${env.TERRAFORM_CHANGED}, Ansible changed=${env.ANSIBLE_CHANGED}."
      }
    }
  }

  post {
    always {
      echo 'Archiving available text logs.'
      archiveArtifacts artifacts: '**/*.log, **/*.txt', allowEmptyArchive: true
    }
    failure {
      echo 'Build failed. Review archived artifacts and console output.'
      // Helm's atomic rollback reports only a timeout when a Kubernetes workload
      // is not Ready. Capture the controller state and events while the failed
      // release still exists, without printing any application secret values.
      script {
        ensureAwsCredentials(this, env.AWS_CREDENTIALS_ID) {
          sh '''
            set +e
            export KUBECONFIG="$WORKSPACE/.kubeconfig-failure-diagnostics"
            aws eks update-kubeconfig --region "${AWS_REGION}" --name "${EKS_CLUSTER_NAME}" --kubeconfig "$KUBECONFIG"

            echo '=== External Secrets Helm release ==='
            helm status external-secrets -n "${K8S_NAMESPACE}" || true

            echo '=== External Secrets deployments and pods ==='
            kubectl get deployment,pods -n "${K8S_NAMESPACE}" -o wide || true
            kubectl describe deployment external-secrets -n "${K8S_NAMESPACE}" || true
            kubectl logs deployment/external-secrets -n "${K8S_NAMESPACE}" --all-containers --tail=200 || true

            echo '=== Recent namespace events ==='
            kubectl get events -n "${K8S_NAMESPACE}" --sort-by='.lastTimestamp' || true

            echo '=== External Secrets API objects ==='
            kubectl get crd secretstores.external-secrets.io externalsecrets.external-secrets.io || true
            kubectl get validatingwebhookconfiguration secretstore-validate || true
          '''
        }
      }
    }
    cleanup {
      echo 'Cleaning workspace.'
      cleanWs()
    }
  }
}
