def loadSharedInfra(script) {
  def support = script.fileExists('herovired-infra/jenkins/common.groovy') ? script.load('herovired-infra/jenkins/common.groovy') : null
  def config = support ? support.readCommonEnv(script) : [:]
  return [support: support, config: config]
}

def infraRoot(script) {
  if (script.fileExists('herovired-infra/terraform/main.tf') && script.fileExists('herovired-infra/ansible/playbooks/configure-management.yml')) {
    return 'herovired-infra'
  }
  if (script.fileExists('terraform/main.tf') && script.fileExists('ansible/playbooks/configure-management.yml')) {
    return '.'
  }
  script.error('Could not locate terraform and ansible under herovired-infra/ or the workspace root.')
}

def repoRoot(script) {
  if (script.fileExists('frontend/package.json') && script.fileExists('admin/package.json') && script.fileExists('backend/package.json')) {
    return '.'
  }
  if (script.fileExists('shopNow/frontend/package.json') && script.fileExists('shopNow/admin/package.json') && script.fileExists('shopNow/backend/package.json')) {
    return 'shopNow'
  }
  return null
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

  if (strategy == 'single-repo' || sharedRepo) {
    def repoName = sharedRepo ?: repoPrefix
    return "${registry}/${repoName}:${serviceName}-${imageTag}"
  }

  return "${registry}/${repoPrefix}/${serviceName}:${imageTag}"
}

pipeline {
  agent any

  parameters {
    string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region used by Terraform and EKS')
    string(name: 'AWS_ACCOUNT_ID', defaultValue: '495013583028', description: 'AWS account used for shared infra lookups')
    string(name: 'TF_STATE_BUCKET', defaultValue: 'shopnow-terraform-state', description: 'S3 bucket for Terraform state')
    string(name: 'LOCK_TABLE', defaultValue: 'shopnow-terraform-locks', description: 'DynamoDB table for Terraform locking')
    string(name: 'EKS_CLUSTER_NAME', defaultValue: 'java-spring-eks', description: 'EKS cluster name')
    string(name: 'ECR_REPO_PREFIX', defaultValue: 'shopnow', description: 'ECR repository prefix')
    choice(name: 'ECR_REPOSITORY_STRATEGY', choices: ['service-repos', 'single-repo'], description: 'Use service repositories (<prefix>/frontend) or one shared repository (<repo>:frontend-<tag>)')
    string(name: 'SINGLE_ECR_REPOSITORY', defaultValue: '', description: 'Shared ECR repository name for single-repo mode, for example shopnow-ecr-21-07-2027')
    string(name: 'FRONTEND_IMAGE_URI', defaultValue: '', description: 'Optional explicit frontend image URI (overrides computed URI)')
    string(name: 'ADMIN_IMAGE_URI', defaultValue: '', description: 'Optional explicit admin image URI (overrides computed URI)')
    string(name: 'BACKEND_IMAGE_URI', defaultValue: '', description: 'Optional explicit backend image URI (overrides computed URI)')
    string(name: 'IMAGE_TAG', defaultValue: '', description: 'Image tag to deploy; defaults to build number and git SHA')
    string(name: 'DEPLOY_FRONTEND', defaultValue: 'false', description: 'Deploy the frontend service')
    string(name: 'DEPLOY_ADMIN', defaultValue: 'false', description: 'Deploy the admin service')
    string(name: 'DEPLOY_BACKEND', defaultValue: 'false', description: 'Deploy the backend service')
    string(name: 'AWS_CREDENTIALS_ID', defaultValue: 'awsId', description: 'Jenkins AWS credentials ID')
    string(name: 'SSH_PRIVATE_KEY_CREDENTIALS_ID', defaultValue: 'management-ec2-ssh-key', description: 'SSH private key credentials for the management EC2 instance')
    string(name: 'REMOTE_USER', defaultValue: 'ubuntu', description: 'SSH user for the management EC2 instance')
    booleanParam(name: 'RUN_TERRAFORM', defaultValue: true, description: 'Run Terraform when infra files change')
    booleanParam(name: 'RUN_ANSIBLE_AFTER_APPLY', defaultValue: true, description: 'Run Ansible after Terraform apply or when ansible files change')
    booleanParam(name: 'RUN_DEPLOYMENT', defaultValue: true, description: 'Deploy application workloads using current image tags')
    string(name: 'K8S_NAMESPACE', defaultValue: 'shopnow-ns', description: 'Kubernetes namespace for the application workloads')
    string(name: 'MONITORING_NAMESPACE', defaultValue: 'monitor-ns', description: 'Namespace for monitoring workloads')
    string(name: 'MONITORING_RELEASE_NAME', defaultValue: 'prometheus', description: 'Monitoring release name')
    string(name: 'GRAFANA_ADMIN_PASSWORD', defaultValue: 'dev-grafana-admin', description: 'Grafana admin password')
    booleanParam(name: 'ENABLE_MONITORING_CHECKS', defaultValue: true, description: 'Apply monitoring manifests and verify monitoring health')
  }

  options {
    skipDefaultCheckout()
    timestamps()
    disableConcurrentBuilds()
    timeout(time: 75, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
  }

  environment {
    AWS_REGION = "${params.AWS_REGION}"
    AWS_ACCOUNT_ID = "${params.AWS_ACCOUNT_ID}"
    TF_STATE_BUCKET = "${params.TF_STATE_BUCKET}"
    LOCK_TABLE = "${params.LOCK_TABLE}"
    EKS_CLUSTER_NAME = "${params.EKS_CLUSTER_NAME}"
    ECR_REPO_PREFIX = "${params.ECR_REPO_PREFIX}"
    ECR_REPOSITORY_STRATEGY = "${params.ECR_REPOSITORY_STRATEGY}"
    SINGLE_ECR_REPOSITORY = "${params.SINGLE_ECR_REPOSITORY}"
    FRONTEND_IMAGE_URI = "${params.FRONTEND_IMAGE_URI}"
    ADMIN_IMAGE_URI = "${params.ADMIN_IMAGE_URI}"
    BACKEND_IMAGE_URI = "${params.BACKEND_IMAGE_URI}"
    IMAGE_TAG = "${params.IMAGE_TAG}"
    DEPLOY_FRONTEND = "${params.DEPLOY_FRONTEND}"
    DEPLOY_ADMIN = "${params.DEPLOY_ADMIN}"
    DEPLOY_BACKEND = "${params.DEPLOY_BACKEND}"
    K8S_NAMESPACE = "${params.K8S_NAMESPACE}"
    MONITORING_NAMESPACE = "${params.MONITORING_NAMESPACE}"
    MONITORING_RELEASE_NAME = "${params.MONITORING_RELEASE_NAME}"
    GRAFANA_ADMIN_PASSWORD = "${params.GRAFANA_ADMIN_PASSWORD}"
    REMOTE_USER = "${params.REMOTE_USER}"
    SSH_PRIVATE_KEY_CREDENTIALS_ID = "${params.SSH_PRIVATE_KEY_CREDENTIALS_ID}"
    INFRA_ROOT = ''
    APP_ROOT = ''
    TERRAFORM_DIR = ''
    ANSIBLE_DIR = ''
    INVENTORY_FILE = ''
    TF_OUTPUT_FILE = ''
    INFRA_CHANGED = 'false'
    TERRAFORM_CHANGED = 'false'
    ANSIBLE_CHANGED = 'false'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Initialize') {
      steps {
        script {
          def sharedInfra = loadSharedInfra(this)
          def infraSupport = sharedInfra.support
          def sharedConfig = sharedInfra.config ?: [:]

          if (infraSupport) {
            env.AWS_REGION = infraSupport.resolveConfigValue(this, sharedConfig, 'AWS_REGION', params.AWS_REGION)
            env.TF_STATE_BUCKET = infraSupport.resolveConfigValue(this, sharedConfig, 'TF_STATE_BUCKET', params.TF_STATE_BUCKET)
            env.LOCK_TABLE = infraSupport.resolveConfigValue(this, sharedConfig, 'LOCK_TABLE', params.LOCK_TABLE)
            env.EKS_CLUSTER_NAME = infraSupport.resolveConfigValue(this, sharedConfig, 'EKS_CLUSTER_NAME', params.EKS_CLUSTER_NAME)
            env.REMOTE_USER = infraSupport.resolveConfigValue(this, sharedConfig, 'REMOTE_USER', params.REMOTE_USER)
            env.SSH_PRIVATE_KEY_CREDENTIALS_ID = infraSupport.resolveConfigValue(this, sharedConfig, 'SSH_PRIVATE_KEY_CREDENTIALS_ID', params.SSH_PRIVATE_KEY_CREDENTIALS_ID)
          }

          env.INFRA_ROOT = infraRoot(this)
          env.APP_ROOT = repoRoot(this) ?: ''
          env.TERRAFORM_DIR = env.INFRA_ROOT == '.' ? 'terraform' : 'herovired-infra/terraform'
          env.ANSIBLE_DIR = env.INFRA_ROOT == '.' ? 'ansible' : 'herovired-infra/ansible'
          env.INVENTORY_FILE = "${WORKSPACE}/${env.ANSIBLE_DIR}/inventories/generated/hosts.ini"
          env.TF_OUTPUT_FILE = "${WORKSPACE}/${env.ANSIBLE_DIR}/terraform-outputs.json"
          if (!env.IMAGE_TAG?.trim()) {
            env.IMAGE_TAG = "${env.BUILD_NUMBER}-${sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()}"
          }

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

          echo "Infra root: ${env.INFRA_ROOT}"
          echo "Terraform dir: ${env.TERRAFORM_DIR}"
          echo "Ansible dir: ${env.ANSIBLE_DIR}"
          if (env.TERRAFORM_CHANGED == 'true') {
            env.DEPLOY_FRONTEND = 'true'
            env.DEPLOY_ADMIN = 'true'
            env.DEPLOY_BACKEND = 'true'
          }

          echo "Terraform changed: ${env.TERRAFORM_CHANGED}"
          echo "Ansible changed: ${env.ANSIBLE_CHANGED}"
          echo "Deploy frontend: ${env.DEPLOY_FRONTEND}"
          echo "Deploy admin: ${env.DEPLOY_ADMIN}"
          echo "Deploy backend: ${env.DEPLOY_BACKEND}"
        }
      }
    }

    stage('Validate AWS Access') {
      steps {
        script {
          ensureAwsCredentials(this, params.AWS_CREDENTIALS_ID) {
            sh 'aws sts get-caller-identity --region ${AWS_REGION}'
          }
        }
      }
    }

    stage('Terraform') {
      when {
        expression { return params.RUN_TERRAFORM && env.TERRAFORM_CHANGED == 'true' }
      }
      steps {
        script {
          ensureAwsCredentials(this, params.AWS_CREDENTIALS_ID) {
            dir(env.TERRAFORM_DIR) {
              sh '''
                set -e
                if ! aws s3api head-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION} 2>/dev/null; then
                  if [ "${AWS_REGION}" = "us-east-1" ]; then
                    aws s3api create-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION}
                  else
                    aws s3api create-bucket --bucket ${TF_STATE_BUCKET} --region ${AWS_REGION} --create-bucket-configuration LocationConstraint=${AWS_REGION}
                  fi
                fi

                if ! aws dynamodb describe-table --table-name ${LOCK_TABLE} --region ${AWS_REGION} 2>/dev/null; then
                  aws dynamodb create-table --table-name ${LOCK_TABLE} --attribute-definitions AttributeName=LockID,AttributeType=S --key-schema AttributeName=LockID,KeyType=HASH --billing-mode PAY_PER_REQUEST --region ${AWS_REGION}
                fi

                terraform init -reconfigure \
                  -backend-config="bucket=${TF_STATE_BUCKET}" \
                  -backend-config="key=terraform/terraform.tfstate" \
                  -backend-config="region=${AWS_REGION}" \
                  -backend-config="use_lockfile=true" \
                  -backend-config="dynamodb_table=${LOCK_TABLE}"

                terraform workspace select -or-create ${ENVIRONMENT:-dev} || terraform workspace select default
                terraform validate
                terraform plan -out=tfplan
                terraform apply -auto-approve tfplan
                terraform output -json > ${TF_OUTPUT_FILE}
              '''
            }
          }
        }
      }
    }

    stage('Generate Inventory') {
      when {
        expression { return params.RUN_ANSIBLE_AFTER_APPLY && (env.ANSIBLE_CHANGED == 'true' || env.TERRAFORM_CHANGED == 'true') }
      }
      steps {
        sh '''
          set -e
          . "$WORKSPACE/herovired-infra/scripts/ensure_ansible.sh"
          python3 "$WORKSPACE/herovired-infra/scripts/generate_ansible_inventory.py" \
            --terraform-output "$TF_OUTPUT_FILE" \
            --inventory "$INVENTORY_FILE" \
            --remote-user "$REMOTE_USER"
        '''
      }
    }

    stage('Configure Management Host') {
      when {
        expression { return params.RUN_ANSIBLE_AFTER_APPLY && (env.ANSIBLE_CHANGED == 'true' || env.TERRAFORM_CHANGED == 'true') }
      }
      steps {
        script {
          ensureAwsCredentials(this, params.AWS_CREDENTIALS_ID) {
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'
          }
          sh '''
            set -e
            . "$WORKSPACE/herovired-infra/scripts/ensure_ansible.sh"
            export ANSIBLE_CONFIG="$WORKSPACE/herovired-infra/ansible/ansible.cfg"
            ansible-playbook -i "$INVENTORY_FILE" "$WORKSPACE/herovired-infra/ansible/playbooks/configure-management.yml" \
              -e "aws_region=${AWS_REGION}" \
              -e "eks_cluster_name=${EKS_CLUSTER_NAME}" \
              -e "remote_user=${REMOTE_USER}"

            ansible-playbook -i "$INVENTORY_FILE" "$WORKSPACE/herovired-infra/ansible/playbooks/validate-management.yml"
          '''
        }
      }
    }

    stage('Deploy Application Workloads') {
      when {
        expression { return params.RUN_DEPLOYMENT && (env.DEPLOY_FRONTEND == 'true' || env.DEPLOY_ADMIN == 'true' || env.DEPLOY_BACKEND == 'true') }
      }
      steps {
        script {
          def frontendImage = env.FRONTEND_IMAGE_URI?.trim() ? env.FRONTEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'frontend', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
          def adminImage = env.ADMIN_IMAGE_URI?.trim() ? env.ADMIN_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'admin', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)
          def backendImage = env.BACKEND_IMAGE_URI?.trim() ? env.BACKEND_IMAGE_URI.trim() : buildImageUri(env.AWS_ACCOUNT_ID, env.AWS_REGION, env.ECR_REPO_PREFIX, 'backend', env.IMAGE_TAG, env.ECR_REPOSITORY_STRATEGY, env.SINGLE_ECR_REPOSITORY)

          echo "Frontend image URI: ${frontendImage} (source: ${env.FRONTEND_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"
          echo "Admin image URI: ${adminImage} (source: ${env.ADMIN_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"
          echo "Backend image URI: ${backendImage} (source: ${env.BACKEND_IMAGE_URI?.trim() ? 'explicit' : 'computed'})"

          ensureAwsCredentials(this, params.AWS_CREDENTIALS_ID) {
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'
          }

          sh 'kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
          sh "sed -e 's|name: shopnow-ns|name: ${K8S_NAMESPACE}|g' herovired-infra/kubernetes/k8s-manifests/namespace/namespace.yaml | kubectl apply -f -"
          sh "for file in herovired-infra/kubernetes/k8s-manifests/database/*.yaml; do sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' \"$file\" | kubectl apply -f -; done"

          def deployTasks = [:]
          if (env.DEPLOY_FRONTEND == 'true') {
            deployTasks.frontend = {
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_FRONTEND_IMAGE|${frontendImage}|g' herovired-infra/kubernetes/k8s-manifests/frontend/deployment.yaml | kubectl apply -f -"
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' herovired-infra/kubernetes/k8s-manifests/frontend/service.yaml | kubectl apply -f -"
            }
          }
          if (env.DEPLOY_ADMIN == 'true') {
            deployTasks.admin = {
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_ADMIN_IMAGE|${adminImage}|g' herovired-infra/kubernetes/k8s-manifests/admin/deployment.yaml | kubectl apply -f -"
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' herovired-infra/kubernetes/k8s-manifests/admin/service.yaml | kubectl apply -f -"
            }
          }
          if (env.DEPLOY_BACKEND == 'true') {
            deployTasks.backend = {
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_BACKEND_IMAGE|${backendImage}|g' herovired-infra/kubernetes/k8s-manifests/backend/deployment.yaml | kubectl apply -f -"
              sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' herovired-infra/kubernetes/k8s-manifests/backend/service.yaml | kubectl apply -f -"
            }
          }

          if (deployTasks.isEmpty()) {
            echo 'No application workloads selected for deployment.'
          } else {
            parallel deployTasks
          }

          sh "sed -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' herovired-infra/kubernetes/k8s-manifests/ingress/ingress-shopnow.yaml | kubectl apply -f -"

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

          if (params.ENABLE_MONITORING_CHECKS) {
            sh 'kubectl create namespace ${MONITORING_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
            sh "for file in herovired-infra/kubernetes/monitoring/*.yaml; do sed -e 's|namespace: monitor-ns|namespace: ${MONITORING_NAMESPACE}|g' -e 's|namespace=\\\"shopnow-ns\\\"|namespace=\\\"${K8S_NAMESPACE}\\\"|g' -e 's|namespace: shopnow-ns|namespace: ${K8S_NAMESPACE}|g' -e 's|REPLACE_MONITORING_RELEASE|${MONITORING_RELEASE_NAME}|g' \"$file\" | kubectl apply -f -; done"
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

    stage('Summary') {
      steps {
        echo "Infra job finished. Terraform changed=${env.TERRAFORM_CHANGED}, Ansible changed=${env.ANSIBLE_CHANGED}."
      }
    }
  }
}