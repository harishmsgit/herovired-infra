def loadSharedInfra(script) {
  def support = script.fileExists('herovired-infra/jenkins/common.groovy') ? script.load('herovired-infra/jenkins/common.groovy') : null
  def config = support ? support.readCommonEnv(script) : [:]
  return [support: support, config: config]
}

def findInfraPath(script, List<String> candidates, String fallbackPattern) {
  for (candidate in candidates) {
    if (script.fileExists(candidate) || script.fileExists("${candidate}/namespace/namespace.yaml")) {
      return candidate
    }
  }
  return script.sh(script: fallbackPattern, returnStdout: true).trim()
}

def withAwsCredentials(String credentialsId, Closure body) {
  if (credentialsId?.trim()) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: credentialsId.trim()]]) {
      body()
    }
  } else {
    body()
  }
}

def serviceMatrix(String userName) {
  return [
    [name: 'frontend', context: 'frontend', imageName: 'shopnow-frontend', repoSuffix: 'frontend', buildArgs: "--build-arg USER_NAME=${userName}", kubernetesName: 'frontend'],
    [name: 'admin', context: 'admin', imageName: 'shopnow-admin', repoSuffix: 'admin', buildArgs: "--build-arg USER_NAME=${userName}", kubernetesName: 'admin'],
    [name: 'backend', context: 'backend', imageName: 'shopnow-backend', repoSuffix: 'backend', buildArgs: '', kubernetesName: 'backend']
  ]
}

def workspacePath(String repoRoot, String relativePath) {
  return repoRoot == '.' ? relativePath : "${repoRoot}/${relativePath}"
}

def buildPipelineContext(script) {
  def sharedInfra = loadSharedInfra(script)
  def infraSupport = sharedInfra.support
  def sharedConfig = sharedInfra.config ?: [:]

  def awsAccountId = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'AWS_ACCOUNT_ID', '495013583028') : (script.params.AWS_ACCOUNT_ID?.trim() ?: script.env.AWS_ACCOUNT_ID?.trim())
  def awsRegion = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'AWS_REGION', 'ap-south-1') : (script.params.AWS_REGION?.trim() ?: script.env.AWS_REGION?.trim())
  def ecrRepoPrefix = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'ECR_REPO_PREFIX', 'shopnow') : (script.params.ECR_REPO_PREFIX?.trim() ?: script.env.ECR_REPO_PREFIX?.trim())
  def ecrRepositoryStrategy = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'ECR_REPOSITORY_STRATEGY', 'service-repos') : (script.params.ECR_REPOSITORY_STRATEGY?.trim() ?: script.env.ECR_REPOSITORY_STRATEGY?.trim() ?: 'service-repos')
  def singleEcrRepository = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'SINGLE_ECR_REPOSITORY', '') : (script.params.SINGLE_ECR_REPOSITORY?.trim() ?: script.env.SINGLE_ECR_REPOSITORY?.trim())
  def ecrImageTagMutability = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'ECR_IMAGE_TAG_MUTABILITY', 'MUTABLE') : (script.params.ECR_IMAGE_TAG_MUTABILITY?.trim() ?: script.env.ECR_IMAGE_TAG_MUTABILITY?.trim() ?: 'MUTABLE')
  def userName = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'USER_NAME', 'harish') : (script.params.USER_NAME?.trim() ?: script.env.USER_NAME?.trim() ?: 'aryan')
  def k8sNamespace = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'K8S_NAMESPACE', 'shopnow-ns') : (script.params.K8S_NAMESPACE?.trim() ?: script.env.K8S_NAMESPACE?.trim())
  def monitoringNamespace = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'MONITORING_NAMESPACE', 'monitor-ns') : (script.params.MONITORING_NAMESPACE?.trim() ?: script.env.MONITORING_NAMESPACE?.trim())
  def monitoringReleaseName = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'MONITORING_RELEASE_NAME', 'prometheus') : (script.params.MONITORING_RELEASE_NAME?.trim() ?: script.env.MONITORING_RELEASE_NAME?.trim())
  def helmVersion = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'HELM_VERSION', 'v3.15.4') : (script.params.HELM_VERSION?.trim() ?: script.env.HELM_VERSION?.trim())
  def grafanaAdminPassword = infraSupport ? infraSupport.resolveConfigValue(script, sharedConfig, 'GRAFANA_ADMIN_PASSWORD', 'dev-grafana-admin') : (script.params.GRAFANA_ADMIN_PASSWORD?.trim() ?: script.env.GRAFANA_ADMIN_PASSWORD?.trim())

  if (!awsAccountId || awsAccountId.equalsIgnoreCase('null')) {
    script.error('AWS_ACCOUNT_ID is required.')
  }
  if (!awsRegion || awsRegion.equalsIgnoreCase('null')) {
    script.error('AWS_REGION is required.')
  }
  if (!ecrRepoPrefix || ecrRepoPrefix.equalsIgnoreCase('null')) {
    script.error('ECR_REPO_PREFIX is required.')
  }

  def repoRoot = script.env.REPO_ROOT?.trim()
  if (!repoRoot || repoRoot.equalsIgnoreCase('null')) {
    repoRoot = script.fileExists('frontend/package.json') && script.fileExists('admin/package.json') && script.fileExists('backend/package.json') ? '.' :
      (script.fileExists('shopNow/frontend/package.json') && script.fileExists('shopNow/admin/package.json') && script.fileExists('shopNow/backend/package.json') ? 'shopNow' : null)
  }
  if (!repoRoot) {
    script.error('Could not locate frontend, admin, and backend package.json files in workspace; check repository layout and checkout path.')
  }

  def manifestRoot = script.env.MANIFEST_ROOT?.trim()
  if (!manifestRoot || manifestRoot.equalsIgnoreCase('null')) {
    def manifestCandidates = ['herovired-infra/kubernetes/k8s-manifests', 'kubernetes/k8s-manifests', 'k8s', 'deploy/k8s', 'manifests']
    manifestRoot = manifestCandidates.find { script.fileExists(workspacePath(repoRoot, it)) }
    if (manifestRoot) {
      manifestRoot = workspacePath(repoRoot, manifestRoot)
    }
  }

  def monitoringRoot = script.env.MONITORING_ROOT?.trim()
  if (!monitoringRoot || monitoringRoot.equalsIgnoreCase('null')) {
    def monitoringCandidates = ['herovired-infra/kubernetes/monitoring', 'kubernetes/monitoring', 'monitoring']
    monitoringRoot = monitoringCandidates.find { script.fileExists(workspacePath(repoRoot, it)) }
    if (monitoringRoot) {
      monitoringRoot = workspacePath(repoRoot, monitoringRoot)
    }
  }

  def imageTag = script.env.IMAGE_TAG?.trim()
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = script.params.IMAGE_TAG?.trim()
  }
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = script.env.BUILD_NUMBER?.toString()?.trim()
  }
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = script.currentBuild?.number?.toString()?.trim()
  }
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = script.env.BUILD_ID?.toString()?.trim()
  }
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = script.sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
  }
  if (!imageTag || imageTag.equalsIgnoreCase('null')) {
    imageTag = "${System.currentTimeMillis()}"
  }

  def serviceImages = serviceMatrix(userName).collectEntries { service ->
    def imageUri = ecrRepositoryStrategy == 'single-repo' || singleEcrRepository ?
      "${awsAccountId}.dkr.ecr.${awsRegion}.amazonaws.com/${singleEcrRepository ?: ecrRepoPrefix}:${service.repoSuffix}-${imageTag}" :
      "${awsAccountId}.dkr.ecr.${awsRegion}.amazonaws.com/${ecrRepoPrefix}/${service.repoSuffix}:${imageTag}"
    [(service.name): imageUri]
  }

  return [
    awsAccountId: awsAccountId,
    awsRegion: awsRegion,
    ecrRepoPrefix: ecrRepoPrefix,
    ecrRepositoryStrategy: ecrRepositoryStrategy,
    singleEcrRepository: singleEcrRepository,
    ecrImageTagMutability: ecrImageTagMutability,
    userName: userName,
    k8sNamespace: k8sNamespace,
    monitoringNamespace: monitoringNamespace,
    monitoringReleaseName: monitoringReleaseName,
    helmVersion: helmVersion,
    grafanaAdminPassword: grafanaAdminPassword,
    repoRoot: repoRoot,
    manifestRoot: manifestRoot,
    monitoringRoot: monitoringRoot,
    imageTag: imageTag,
    serviceImages: serviceImages,
  ]
}

def applyPipelineContext(script, ctx) {
  script.env.AWS_ACCOUNT_ID = ctx.awsAccountId
  script.env.AWS_REGION = ctx.awsRegion
  script.env.ECR_REPO_PREFIX = ctx.ecrRepoPrefix
  script.env.ECR_REPOSITORY_STRATEGY = ctx.ecrRepositoryStrategy
  script.env.SINGLE_ECR_REPOSITORY = ctx.singleEcrRepository ?: ''
  script.env.ECR_IMAGE_TAG_MUTABILITY = ctx.ecrImageTagMutability
  script.env.USER_NAME = ctx.userName
  script.env.K8S_NAMESPACE = ctx.k8sNamespace ?: script.env.K8S_NAMESPACE
  script.env.MONITORING_NAMESPACE = ctx.monitoringNamespace ?: script.env.MONITORING_NAMESPACE
  script.env.MONITORING_RELEASE_NAME = ctx.monitoringReleaseName ?: script.env.MONITORING_RELEASE_NAME
  script.env.HELM_VERSION = ctx.helmVersion ?: script.env.HELM_VERSION
  script.env.GRAFANA_ADMIN_PASSWORD = ctx.grafanaAdminPassword ?: script.env.GRAFANA_ADMIN_PASSWORD
  script.env.REPO_ROOT = ctx.repoRoot
  script.env.MANIFEST_ROOT = ctx.manifestRoot ?: ''
  script.env.MONITORING_ROOT = ctx.monitoringRoot ?: ''
  script.env.IMAGE_TAG = ctx.imageTag
  script.env.FRONTEND_IMAGE_URI = ctx.serviceImages.frontend
  script.env.ADMIN_IMAGE_URI = ctx.serviceImages.admin
  script.env.BACKEND_IMAGE_URI = ctx.serviceImages.backend
}

def ensureKubectl(script, kubectlVersion) {
  script.sh("""
    set -e
    export PATH=\"\$HOME/bin:\$PATH\"
    mkdir -p \"\$HOME/bin\"
    if ! command -v kubectl >/dev/null 2>&1; then
      curl -L https://dl.k8s.io/release/${kubectlVersion}/bin/linux/amd64/kubectl -o \"\$HOME/bin/kubectl\"
      chmod +x \"\$HOME/bin/kubectl\"
    fi
  """)
}

def ensureHelm(script, helmVersion) {
  script.sh("""
    set -e
    export PATH=\"\$HOME/bin:\$PATH\"
    mkdir -p \"\$HOME/bin\"
    if ! command -v helm >/dev/null 2>&1; then
      curl -L https://get.helm.sh/helm-${helmVersion}-linux-amd64.tar.gz -o /tmp/helm.tgz
      tar -xzf /tmp/helm.tgz -C /tmp
      mv /tmp/linux-amd64/helm \"\$HOME/bin/helm\"
      chmod +x \"\$HOME/bin/helm\"
    fi
  """)
}

def sendMonitoringNotification(script, status, message) {
  def webhook = script.params.ALERT_WEBHOOK_URL?.trim()
  if (!webhook) {
    script.echo('ALERT_WEBHOOK_URL not provided; skipping external notification.')
    return
  }

  def payload = """{
    \"status\": \"${status}\",
    \"job\": \"${script.env.JOB_NAME ?: 'unknown'}\",
    \"build\": \"${script.env.BUILD_NUMBER ?: 'unknown'}\",
    \"buildUrl\": \"${script.env.BUILD_URL ?: 'unknown'}\",
    \"message\": \"${message.replace('"', '\\\\"')}\"
  }"""

  script.sh """
    set +e
    curl -sS -X POST '${webhook}' \
      -H 'Content-Type: application/json' \
      -d '${payload}' >/dev/null
    exit 0
  """
}

pipeline {
  agent any

  parameters {
    string(name: 'AWS_REGION', defaultValue: 'ap-south-1', description: 'AWS region')
    string(name: 'AWS_ACCOUNT_ID', defaultValue: '495013583028', description: 'AWS account ID for ECR registry')
    string(name: 'AWS_CREDENTIALS_ID', defaultValue: 'awsId', description: 'Jenkins AWS credentials ID')
    string(name: 'ECR_REPO_PREFIX', defaultValue: 'shopnow', description: 'ECR repository prefix, for example <your-username>-shopnow')
    choice(name: 'ECR_REPOSITORY_STRATEGY', choices: ['service-repos', 'single-repo'], description: 'Use service repositories (<prefix>/frontend) or one shared repository (<repo>:frontend-<tag>)')
    string(name: 'SINGLE_ECR_REPOSITORY', defaultValue: '', description: 'Shared ECR repository for single-repo mode, for example shopnow-ecr-21-07-2027')
    choice(name: 'ECR_IMAGE_TAG_MUTABILITY', choices: ['MUTABLE', 'IMMUTABLE'], description: 'Tag mutability to use when creating ECR repositories')
    string(name: 'IMAGE_TAG', defaultValue: '', description: 'Docker image tag; defaults to BUILD_NUMBER-gitsha')
    string(name: 'USER_NAME', defaultValue: 'harish', description: 'Frontend and admin build arg used for public path customization')
    string(name: 'EKS_CLUSTER_NAME', defaultValue: 'java-spring-eks', description: 'EKS cluster name')
    string(name: 'K8S_NAMESPACE', defaultValue: 'shopnow-ns', description: 'Kubernetes namespace')
    string(name: 'MONITORING_NAMESPACE', defaultValue: 'monitor-ns', description: 'Namespace where Prometheus/Grafana stack is running')
    string(name: 'MONITORING_RELEASE_NAME', defaultValue: 'prometheus', description: 'Helm release name for kube-prometheus-stack')
    string(name: 'HELM_VERSION', defaultValue: 'v3.15.4', description: 'Helm version used to bootstrap monitoring stack')
    string(name: 'GRAFANA_ADMIN_PASSWORD', defaultValue: 'dev-grafana-admin', description: 'Grafana admin password used for bootstrap')
    booleanParam(name: 'ENABLE_MONITORING_CHECKS', defaultValue: true, description: 'Apply monitoring manifests and verify monitoring health')
    string(name: 'ALERT_WEBHOOK_URL', defaultValue: '', description: 'Optional webhook URL for Jenkins monitoring/failure notifications')
    booleanParam(name: 'RUN_DEPLOYMENT', defaultValue: true, description: 'Deploy to EKS after build')
  }

  options {
    skipDefaultCheckout()
    disableConcurrentBuilds()
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    timeout(time: 60, unit: 'MINUTES')
  }

  environment {
    AWS_REGION = "${params.AWS_REGION}"
    AWS_ACCOUNT_ID = "${params.AWS_ACCOUNT_ID}"
    ECR_REPO_PREFIX = "${params.ECR_REPO_PREFIX}"
    ECR_REPOSITORY_STRATEGY = "${params.ECR_REPOSITORY_STRATEGY}"
    SINGLE_ECR_REPOSITORY = "${params.SINGLE_ECR_REPOSITORY}"
    ECR_IMAGE_TAG_MUTABILITY = "${params.ECR_IMAGE_TAG_MUTABILITY}"
    USER_NAME = "${params.USER_NAME}"
    EKS_CLUSTER_NAME = "${params.EKS_CLUSTER_NAME}"
    K8S_NAMESPACE = "${params.K8S_NAMESPACE}"
    MONITORING_NAMESPACE = "${params.MONITORING_NAMESPACE}"
    MONITORING_RELEASE_NAME = "${params.MONITORING_RELEASE_NAME}"
    HELM_VERSION = "${params.HELM_VERSION}"
    GRAFANA_ADMIN_PASSWORD = "${params.GRAFANA_ADMIN_PASSWORD}"
    KUBECTL_VERSION = 'v1.30.0'
    REPO_ROOT = ''
    IMAGE_TAG = ''
    MANIFEST_ROOT = ''
    MONITORING_ROOT = ''
    FRONTEND_IMAGE_URI = ''
    ADMIN_IMAGE_URI = ''
    BACKEND_IMAGE_URI = ''
  }

  stages {
    stage('Initialize Context') {
      steps {
        checkout scm
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          echo 'Initialized pipeline context'
          echo "AWS_REGION=${ctx.awsRegion}"
          echo "ECR_REPO_PREFIX=${ctx.ecrRepoPrefix}"
          echo "REPO_ROOT=${ctx.repoRoot}"
          echo "IMAGE_TAG=${ctx.imageTag}"
          echo "MANIFEST_ROOT=${ctx.manifestRoot ?: '<missing>'}"
          echo "MONITORING_ROOT=${ctx.monitoringRoot ?: '<missing>'}"
          echo "FRONTEND_IMAGE_URI=${ctx.serviceImages.frontend}"
          echo "ADMIN_IMAGE_URI=${ctx.serviceImages.admin}"
          echo "BACKEND_IMAGE_URI=${ctx.serviceImages.backend}"
        }
      }
    }

    stage('Validate Tooling') {
      steps {
        script {
          sh 'docker --version'
          sh 'aws --version'
          sh 'git --version'
        }
      }
    }

    stage('Validate AWS Access') {
      steps {
        script {
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            sh 'aws sts get-caller-identity --region ${AWS_REGION}'
          }
        }
      }
    }

    stage('Build Docker Images') {
      steps {
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          for (service in serviceMatrix(ctx.userName)) {
            dir(workspacePath(ctx.repoRoot, service.context)) {
              def buildArgs = service.buildArgs ? "${service.buildArgs} " : ''
              def imageUri = ctx.serviceImages[service.name]
              sh """
                docker build \\
                  --tag ${service.imageName}:${IMAGE_TAG} \\
                  --tag ${service.imageName}:latest \\
                  --tag ${imageUri} \\
                  ${buildArgs}.
              """
            }
          }
        }
      }
    }

    stage('Login to ECR') {
      when {
        expression { return params.RUN_DEPLOYMENT }
      }
      steps {
        script {
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            sh 'aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com'
          }
        }
      }
    }

    stage('Ensure ECR Repositories') {
      when {
        expression { return params.RUN_DEPLOYMENT }
      }
      steps {
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            if (ctx.ecrRepositoryStrategy == 'single-repo' || ctx.singleEcrRepository) {
              def repoName = ctx.singleEcrRepository ?: ctx.ecrRepoPrefix
              sh """
                if aws ecr describe-repositories --repository-names ${repoName} --region ${AWS_REGION} >/dev/null 2>&1; then
                  echo "ECR repository already exists: ${repoName}"
                else
                  aws ecr create-repository \
                    --repository-name ${repoName} \
                    --region ${AWS_REGION} \
                    --image-tag-mutability ${ctx.ecrImageTagMutability} \
                    --image-scanning-configuration scanOnPush=true
                fi
              """
            } else {
              for (service in serviceMatrix(ctx.userName)) {
                def repoName = "${ctx.ecrRepoPrefix}/${service.repoSuffix}"
                sh """
                  if aws ecr describe-repositories --repository-names ${repoName} --region ${AWS_REGION} >/dev/null 2>&1; then
                    echo "ECR repository already exists: ${repoName}"
                  else
                    aws ecr create-repository \
                      --repository-name ${repoName} \
                      --region ${AWS_REGION} \
                      --image-tag-mutability ${ctx.ecrImageTagMutability} \
                      --image-scanning-configuration scanOnPush=true
                  fi
                """
              }
            }
          }
        }
      }
    }

    stage('Push to ECR') {
      when {
        expression { return params.RUN_DEPLOYMENT }
      }
      steps {
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          for (service in serviceMatrix(ctx.userName)) {
            def imageUri = ctx.serviceImages[service.name]
            sh """
              docker image inspect ${imageUri} >/dev/null 2>&1 || docker tag ${service.imageName}:${IMAGE_TAG} ${imageUri}
              docker push ${imageUri}
            """
          }
        }
      }
    }

    stage('Deploy to EKS') {
      when {
        expression { return params.RUN_DEPLOYMENT }
      }
      steps {
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          if (!ctx.manifestRoot?.trim()) {
            error('Kubernetes manifest root not found. Expected herovired-infra/kubernetes/k8s-manifests or a supported fallback path.')
          }
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            ensureKubectl(this, env.KUBECTL_VERSION)
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'
            sh 'kubectl create namespace ${K8S_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
            dir(ctx.manifestRoot) {
              sh """
                set -e
                for component in namespace database backend frontend admin ingress daemonsets-example; do
                  if [ -d "$component" ]; then
                    find "$component" -type f \( -name '*.yaml' -o -name '*.yml' \) | sort | while read file; do
                      sed \
                        -e 's|REPLACE_NAMESPACE|${K8S_NAMESPACE}|g' \
                        -e 's|REPLACE_FRONTEND_IMAGE|${ctx.serviceImages.frontend}|g' \
                        -e 's|REPLACE_ADMIN_IMAGE|${ctx.serviceImages.admin}|g' \
                        -e 's|REPLACE_BACKEND_IMAGE|${ctx.serviceImages.backend}|g' \
                        "$file" | kubectl apply -f -
                    done
                  fi
                done
              """
            }
          }
        }
      }
    }

    stage('Verify Deployment') {
      when {
        expression { return params.RUN_DEPLOYMENT }
      }
      steps {
        script {
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            ensureKubectl(this, env.KUBECTL_VERSION)
            sh 'kubectl get all -n ${K8S_NAMESPACE}'
            sh 'kubectl get ingress -n ${K8S_NAMESPACE} || true'
            sh 'kubectl get hpa -n ${K8S_NAMESPACE} || true'
          }
        }
      }
    }

    stage('Configure Monitoring') {
      when {
        expression { return params.RUN_DEPLOYMENT && params.ENABLE_MONITORING_CHECKS }
      }
      steps {
        script {
          def ctx = buildPipelineContext(this)
          applyPipelineContext(this, ctx)

          if (!ctx.monitoringRoot?.trim()) {
            error('Monitoring manifest root not found. Expected herovired-infra/kubernetes/monitoring or a supported fallback path.')
          }
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            ensureKubectl(this, env.KUBECTL_VERSION)
            ensureHelm(this, env.HELM_VERSION)
            sh 'aws eks update-kubeconfig --region ${AWS_REGION} --name ${EKS_CLUSTER_NAME}'
            sh 'kubectl create namespace ${MONITORING_NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -'
            sh '''
              if ! kubectl get crd servicemonitors.monitoring.coreos.com >/dev/null 2>&1 || \
                 ! kubectl get crd prometheusrules.monitoring.coreos.com >/dev/null 2>&1; then
                helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
                helm repo update >/dev/null
                helm upgrade --install ${MONITORING_RELEASE_NAME} prometheus-community/kube-prometheus-stack \
                  --namespace ${MONITORING_NAMESPACE} \
                  --create-namespace \
                  --set grafana.adminPassword=${GRAFANA_ADMIN_PASSWORD}
              fi
            '''
            dir(ctx.monitoringRoot) {
              sh """
                set -e
                for manifest in *.yaml *.yml; do
                  if [ -f "$manifest" ]; then
                    sed \
                      -e 's|REPLACE_NAMESPACE|${K8S_NAMESPACE}|g' \
                      -e 's|REPLACE_MONITORING_NAMESPACE|${MONITORING_NAMESPACE}|g' \
                      -e 's|REPLACE_MONITORING_RELEASE|${MONITORING_RELEASE_NAME}|g' \
                      "$manifest" | kubectl apply -f -
                  fi
                done
              """
            }
          }
        }
      }
    }

    stage('Monitoring Health Check') {
      when {
        expression { return params.RUN_DEPLOYMENT && params.ENABLE_MONITORING_CHECKS }
      }
      steps {
        script {
          withAwsCredentials(params.AWS_CREDENTIALS_ID) {
            ensureKubectl(this, env.KUBECTL_VERSION)
            sh 'kubectl get pods -n ${MONITORING_NAMESPACE}'
            sh 'kubectl get servicemonitor -n ${MONITORING_NAMESPACE} || true'
            sh 'kubectl get prometheusrule -n ${MONITORING_NAMESPACE} || true'
          }
        }
      }
    }
  }

  post {
    success {
      script {
        def ctx = buildPipelineContext(this)
        applyPipelineContext(this, ctx)

        echo "Pipeline succeeded. Frontend image: ${ctx.serviceImages.frontend}"
        echo "Pipeline succeeded. Admin image: ${ctx.serviceImages.admin}"
        echo "Pipeline succeeded. Backend image: ${ctx.serviceImages.backend}"
        sendMonitoringNotification(this, 'success', 'Sprint 5 deployment and monitoring checks completed successfully.')
      }
    }
    failure {
      script {
        sendMonitoringNotification(this, 'failure', 'Sprint 5 pipeline failed. Check deployment and monitoring health stages.')
      }
    }
    always {
      cleanWs()
    }
  }
}
