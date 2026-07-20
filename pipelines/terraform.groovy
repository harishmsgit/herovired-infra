def loadSharedInfra(script) {
  def support = script.fileExists('herovired-infra/jenkins/common.groovy') ? script.load('herovired-infra/jenkins/common.groovy') : null
  def config = support ? support.readCommonEnv(script) : [:]
  return [support: support, config: config]
}

def findTerraformDir(script) {
  def candidates = [
    'herovired-infra/terraform',
    'terraform',
    'infra/terraform',
    'deploy/terraform'
  ]
  for (candidate in candidates) {
    if (script.fileExists("${candidate}/main.tf")) {
      return candidate
    }
  }
  return script.sh(script: '''
    set -e
    find . -type f -name main.tf | head -n 1 | sed -e 's|/main.tf$||' -e 's|^./||'
  ''', returnStdout: true).trim()
}

pipeline {
  agent any

  parameters {
    string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region for provisioning')
    string(name: 'TF_STATE_BUCKET', defaultValue: 'shopnow-terraform-state', description: 'S3 bucket for Terraform state')
    string(name: 'LOCK_TABLE', defaultValue: 'shopnow-terraform-locks', description: 'DynamoDB table for Terraform locking')
    string(name: 'ENVIRONMENT', defaultValue: 'dev', description: 'Deployment environment name')
    string(name: 'EKS_CLUSTER_NAME', defaultValue: 'java-spring-eks', description: 'EKS cluster name used for discovery and downstream jobs')
    string(name: 'AWS_CREDENTIALS_ID', defaultValue: 'awsId', description: 'Jenkins AWS credentials ID or set AWS_CREDENTIALS_ID in the environment')
    booleanParam(name: 'SKIP_BACKEND_CREATION', defaultValue: false, description: 'Skip S3 and DynamoDB backend creation')
    booleanParam(name: 'RUN_ANSIBLE_AFTER_APPLY', defaultValue: true, description: 'Trigger the Ansible configuration job after Terraform apply succeeds')
    string(name: 'ANSIBLE_JOB_NAME', defaultValue: 'shopnow-ansible-config', description: 'Jenkins job name that uses the shared Ansible pipeline')
    string(name: 'SSH_PRIVATE_KEY_CREDENTIALS_ID', defaultValue: 'management-ec2-ssh-key', description: 'SSH private key credential passed to the Ansible job')
    string(name: 'REMOTE_USER', defaultValue: 'ubuntu', description: 'SSH user passed to the Ansible job')
  }

  environment {
    AWS_REGION = "${params.AWS_REGION}"
    TF_VAR_aws_region = "${params.AWS_REGION}"
    TF_VAR_state_bucket = "${params.TF_STATE_BUCKET}"
    TF_VAR_lock_table = "${params.LOCK_TABLE}"
    EKS_CLUSTER_NAME = "${params.EKS_CLUSTER_NAME}"
    IMAGE_TAG = ''
    TERRAFORM_DIR = ''
    TF_WORKSPACE = ''
  }

  options {
    skipDefaultCheckout()
    timestamps()
    timeout(time: 45, unit: 'MINUTES')
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
            env.AWS_REGION = infraSupport.resolveConfigValue(this, sharedConfig, 'AWS_REGION', 'us-east-1')
            env.TF_VAR_aws_region = env.AWS_REGION
            env.TF_STATE_BUCKET = infraSupport.resolveConfigValue(this, sharedConfig, 'TF_STATE_BUCKET', 'shopnow-terraform-state')
            env.LOCK_TABLE = infraSupport.resolveConfigValue(this, sharedConfig, 'LOCK_TABLE', 'shopnow-terraform-locks')
            env.EKS_CLUSTER_NAME = infraSupport.resolveConfigValue(this, sharedConfig, 'EKS_CLUSTER_NAME', 'java-spring-eks')
            env.ANSIBLE_JOB_NAME = infraSupport.resolveConfigValue(this, sharedConfig, 'ANSIBLE_JOB_NAME', 'shopnow-ansible-config')
            env.REMOTE_USER = infraSupport.resolveConfigValue(this, sharedConfig, 'REMOTE_USER', 'ubuntu')
            env.SSH_PRIVATE_KEY_CREDENTIALS_ID = infraSupport.resolveConfigValue(this, sharedConfig, 'SSH_PRIVATE_KEY_CREDENTIALS_ID', 'management-ec2-ssh-key')
          }

          env.IMAGE_TAG = "${BUILD_NUMBER}-${sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()}"
          def terraformDir = findTerraformDir(this)
          if (!terraformDir) {
            error('Terraform module path not found. Add terraform/main.tf or update the shared Terraform pipeline discovery rules.')
          }
          env.TERRAFORM_DIR = terraformDir
          echo "Terraform module path: ${env.TERRAFORM_DIR}"
        }
      }
    }

    stage('Validate AWS Access') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (awsCreds) {
            withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
              sh 'aws sts get-caller-identity --region ${AWS_REGION}'
            }
          } else {
            echo 'AWS_CREDENTIALS_ID not provided; using agent environment credentials or instance profile.'
            sh 'aws sts get-caller-identity --region ${AWS_REGION}'
          }
        }
      }
    }

    stage('Prepare Backend') {
      when {
        expression { return !params.SKIP_BACKEND_CREATION }
      }
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
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
              '''
            }
          }
        }
      }
    }

    stage('Terraform Init') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh '''
                terraform init -reconfigure \
                  -backend-config="bucket=${TF_STATE_BUCKET}" \
                  -backend-config="key=terraform/terraform.tfstate" \
                  -backend-config="region=${AWS_REGION}" \
                  -backend-config="use_lockfile=true" \
                  -backend-config="dynamodb_table=${LOCK_TABLE}"
              '''
            }
          }
        }
      }
    }

    stage('Select Workspace') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            def branchName = env.BRANCH_NAME ?: env.GIT_BRANCH ?: sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
            def rawName = params.ENVIRONMENT?.trim() ?: branchName
            def workspace = rawName.toLowerCase().replaceAll('[^a-z0-9_-]', '-')
            if (workspace in ['main', 'master', 'prod'] || workspace.startsWith('release')) {
              workspace = 'prod'
            } else if (workspace in ['develop', 'dev', 'development']) {
              workspace = 'dev'
            } else if (workspace in ['qa', 'staging', 'stage']) {
              workspace = 'qa'
            }
            env.TF_WORKSPACE = workspace

            dir(env.TERRAFORM_DIR) {
              sh '''
                echo "Using Terraform workspace: ${TF_WORKSPACE}"
                terraform workspace select -or-create ${TF_WORKSPACE}
              '''
            }
          }
        }
      }
    }

    stage('Terraform Validate') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh 'terraform validate'
            }
          }
        }
      }
    }

    stage('Import Existing Resources') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh '''
                echo "Attempting to import existing AWS resources into Terraform state..."

                CLUSTER_NAME=$(terraform output -raw cluster_name 2>/dev/null || terraform output -raw eks_cluster_name 2>/dev/null || echo "${EKS_CLUSTER_NAME}")

                if aws eks describe-cluster --name ${CLUSTER_NAME} --region ${AWS_REGION} 2>/dev/null; then
                  echo "Found existing EKS cluster: ${CLUSTER_NAME}"
                  terraform import aws_eks_cluster.main ${CLUSTER_NAME} 2>&1 || echo "Cluster already in state or import skipped."
                else
                  echo "No existing EKS cluster found in AWS or already imported."
                fi
              '''
            }
          }
        }
      }
    }

    stage('Terraform Plan') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }

          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh 'terraform init -input=false'
              sh 'terraform plan -out=tfplan'
              sh 'terraform show tfplan > plan.txt'
            }
          }
        }
      }
    }

    stage('Terraform Apply') {
      steps {
        script {
          def awsCreds = params.AWS_CREDENTIALS_ID?.trim() ?: env.AWS_CREDENTIALS_ID?.trim()
          if (!awsCreds) {
            error('AWS_CREDENTIALS_ID must be provided as a build parameter or via Jenkins environment variable AWS_CREDENTIALS_ID.')
          }
          withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: awsCreds]]) {
            dir(env.TERRAFORM_DIR) {
              sh 'terraform apply -auto-approve tfplan'
            }
          }
        }
      }
    }
  }

  post {
    success {
      echo 'Terraform provisioning completed successfully.'
      script {
        if (params.RUN_ANSIBLE_AFTER_APPLY) {
          build job: env.ANSIBLE_JOB_NAME ?: params.ANSIBLE_JOB_NAME,
            wait: true,
            propagate: true,
            parameters: [
              string(name: 'AWS_REGION', value: env.AWS_REGION ?: params.AWS_REGION),
              string(name: 'TF_STATE_BUCKET', value: env.TF_STATE_BUCKET ?: params.TF_STATE_BUCKET),
              string(name: 'LOCK_TABLE', value: env.LOCK_TABLE ?: params.LOCK_TABLE),
              string(name: 'ENVIRONMENT', value: env.TF_WORKSPACE ?: params.ENVIRONMENT),
              string(name: 'EKS_CLUSTER_NAME', value: env.EKS_CLUSTER_NAME ?: params.EKS_CLUSTER_NAME),
              string(name: 'AWS_CREDENTIALS_ID', value: params.AWS_CREDENTIALS_ID),
              string(name: 'SSH_PRIVATE_KEY_CREDENTIALS_ID', value: env.SSH_PRIVATE_KEY_CREDENTIALS_ID ?: params.SSH_PRIVATE_KEY_CREDENTIALS_ID),
              string(name: 'REMOTE_USER', value: env.REMOTE_USER ?: params.REMOTE_USER)
            ]
        }
      }
    }
    failure {
      echo 'Terraform provisioning failed. Review logs and fix any issues.'
    }
  }
}
