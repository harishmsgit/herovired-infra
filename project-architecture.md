# ShopNow end-to-end project architecture

This document describes the verified ShopNow development environment and the production architecture pattern used to operate it. It separates currently deployed components from recommended production controls so the team does not mistake a target design for an already enabled feature.

## 1. Live environment reference

| Layer | Verified value |
|---|---|
| AWS account / region | \`559272000457\` / \`ap-south-1\` |
| VPC | \`vpc-02d6c8773f62350e4\` |
| Public subnets | \`subnet-088ea34a6270f1000\`, \`subnet-04b729189cb399e38\` |
| EKS cluster | \`shopnow-app-eks\`, Kubernetes \`1.36\` |
| EKS node groups | \`dev-shopnow-nodes\` (\`t3.micro\`) and \`dev-shopnow-workloads\` (\`t3.small\`) |
| Application namespace | \`shopnow-ns\` |
| Management host | \`i-05988c1864c27c07f\`, public IP \`13.201.136.220\` |
| Ingress ELB | \`a2d7eee8d8179427fa36d881be68d64a-277526266.ap-south-1.elb.amazonaws.com\` |
| Customer / admin / API paths | \`/shopnow/\`, \`/shopnow/admin/\`, \`/shopnow/api/health\` |
| Terraform backend | S3 \`harish-pc-s3-bucket\`, state key \`env:/dev/terraform/terraform.tfstate\`, lock table \`shopnow-terraform-locks\` |
| Application release identity | ECR tag \`25-df7ed225\` for frontend, admin, and backend |

## 2. End-to-end architecture

~~~mermaid
flowchart LR
  Dev[Developer / Pull Request] --> GH[GitHub repositories]
  GH --> WH[GitHub webhook]
  WH --> Tunnel[ngrok public tunnel<br/>development only]
  Tunnel --> J[Jenkins on localhost:8080]

  GH --> AppJob[shopnow-service-dev]
  AppJob --> Build[Parallel Docker builds<br/>frontend, admin, backend]
  Build --> ECR[(Amazon ECR<br/>immutable tags)]
  AppJob --> InfraJob[herovired-infra-services<br/>explicit image URIs]

  GH --> InfraJob
  InfraJob --> TF[Terraform]
  TF --> AWS[AWS VPC, IAM, EKS,<br/>ECR, management host]
  TF --> Helm[Helm releases<br/>ingress-nginx + external-secrets]
  InfraJob --> K8s[Kubernetes manifests<br/>shopnow-ns]

  Internet[Customer / Admin browser] --> ELB[Internet-facing ELB]
  ELB --> Nginx[NGINX Ingress Controller]
  Nginx --> Frontend[Frontend service<br/>/shopnow/]
  Nginx --> Admin[Admin service<br/>/shopnow/admin/]
  Nginx --> Backend[Backend service<br/>/shopnow/api/]
  Backend --> Mongo[MongoDB service]

  SM[AWS Secrets Manager<br/>shopnow/mongo] --> ESO[External Secrets Operator<br/>IRSA]
  ESO --> KSecret[Kubernetes mongo-secret]
  KSecret --> Mongo
  KSecret --> Backend

  Mgmt[Management EC2<br/>dev-shopnow-management-role] --> EKSAuth[EKS auth + namespace RBAC]
  EKSAuth --> K8s
~~~

## 3. Request path

1. The browser requests the public ELB at \`/shopnow/\`, \`/shopnow/admin/\`, or \`/shopnow/api/...\`.
2. The internet-facing ELB forwards traffic to the NGINX Ingress Controller service.
3. The ingress routes customer UI traffic to \`frontend-service:80\`, admin traffic to \`admin-service:80\`, and API traffic to \`backend-service:5000\`.
4. The ingress rewrites the public API prefix so \`/shopnow/api/health\` reaches the backend's \`/api/health\` route.
5. The backend accesses MongoDB using configuration supplied through the externally synchronized \`mongo-secret\`.
6. Responses return through NGINX and the ELB to the browser.

## 4. Deployment path

1. A ShopNow commit triggers the \`shopnow-service-dev\` Jenkins job.
2. Jenkins creates frontend, admin, and backend images in parallel.
3. Images are pushed to the three ECR repositories under a new immutable tag, e.g. \`25-df7ed225\`.
4. The application job starts \`herovired-infra-services\` with the exact full ECR image URIs.
5. The infrastructure job uses \`RUN_TERRAFORM=false\` for a normal application release and applies the Kubernetes manifests.
6. Kubernetes performs the workload rollout; Jenkins waits for the relevant Deployments and validates the public API health endpoint.
7. For a platform change, the infrastructure job runs Terraform/Helm deliberately with \`RUN_TERRAFORM=true\`; this is not part of every app deployment.

The two repositories have complementary responsibilities:

| Repository | Controls | Must not own |
|---|---|---|
| \`shopNow\` | Application source, Dockerfiles, browser build paths, API behavior, image builds | AWS resources, cluster IAM, ingress controller lifecycle, Terraform state |
| \`herovired-infra\` | AWS/EKS/IAM/Helm/Kubernetes desired state, ingress routes, secret integration, deployment orchestration | Application business logic or secret values |

## 5. Security and access model

| Principal | Access purpose | Boundary |
|---|---|---|
| Jenkins application credentials | Build/push ECR image and trigger deployment | Must move to a dedicated least-privilege CI role with short-lived credentials for production. |
| External Secrets IRSA role | Read the named AWS secret | Scoped to \`shopnow/mongo\`; Kubernetes receives only the materialized secret. |
| Management EC2 role | Discover EKS cluster | AWS policy grants \`eks:DescribeCluster\` only. |
| Management EC2 Kubernetes identity | Operations | Namespace-scoped inspect/log/port-forward access; no Kubernetes secret reads or workload mutation. |
| Public user | Consume browser/API routes | Public HTTP is current development access; production must use HTTPS/WAF/DNS controls. |

## 6. Observability and incident flow

Operational evidence is collected at four layers:

| Layer | Primary signal | Initial command/location |
|---|---|---|
| Jenkins | Build, image tag, downstream job result | \`http://localhost:8080/job/shopnow-service-dev/\` and infra job console |
| AWS/EKS | Cluster/node/load-balancer state | AWS CLI and \`kubectl get\` commands in the runbooks |
| Ingress | Public route method/status/upstream | \`ingress-nginx-controller\` logs filtered for \`/shopnow/\` |
| Application | Backend errors, pod restarts, Mongo health, API order queries | Backend/Mongo logs and read-only API endpoints |

Use this escalation sequence: public health check → ingress access log → Deployment/pod state → backend log → Mongo/ExternalSecret condition → ECR image/Jenkins release identity → IAM/network/cluster health.

## 7. Production architecture pattern

The deployed design is a development baseline. A production-ready realization should follow these patterns:

- Separate development, staging, and production AWS accounts or at least fully isolated state, credentials, VPCs, EKS clusters, and secrets.
- Use private workload subnets, a restricted EKS endpoint, controlled administrative access, multi-AZ capacity, and autoscaling.
- Place a stable DNS name, ACM TLS certificate, HTTPS redirect, WAF, rate limits, and edge monitoring in front of the ingress.
- Keep images immutable; require code review, tests, scans, SBOM/provenance, and approval before promotion.
- Use readiness/liveness probes, resource requests/limits, HPA, pod disruption budgets, topology spreading, and SLO-driven alerting.
- Centralize structured logs, metrics, traces, audit events, and business-order events with retained access-controlled storage.
- Use a managed, backed-up, monitored database with restore drills rather than a single in-cluster MongoDB pod for production orders.
- Deliver high-risk changes through canary or blue/green routing; preserve a known-good version for a defined rollback window.
- Reconcile Git desired state continuously or through a controlled Jenkins deployment path, and alert on drift.

## 8. Release strategy decision guide

| Release type | Recommended rollout | Rollback method |
|---|---|---|
| Compatible frontend/admin update | Rolling update with readiness checks | Deploy prior immutable UI tag through Jenkins |
| Compatible backend change | Rolling/canary with API and error-rate checks | Deploy prior backend tag or switch service traffic |
| Breaking API/schema change | Canary or blue/green with expand-contract migration | Route to prior version; retain compatible schema until safe |
| Ingress/DNS/TLS change | Parallel route/host validation then cutover | Revert DNS/ingress configuration |
| Terraform/IAM/network change | Reviewed plan and approved maintenance window | Restore via reviewed IaC; never delete state/roles blindly |
| Secret rotation | Rotate in Secrets Manager, wait for ExternalSecret sync | Restore prior secret version only under approved incident procedure |

Blue/green is a target pattern, not currently enabled. To implement it, add parallel deployment/service resources, a stable traffic selector or ingress backend switch, automated health and business-metric gates, and a tested traffic rollback procedure.

## 9. Documentation map

- \`infra-related-commands-queries.md\`: AWS, EKS, Terraform, Helm, Ansible, Jenkins, and endpoint commands.
- \`infra-configuration-guide.md\`: infrastructure configuration ownership, safe change process, and platform production patterns.
- \`shopNow-related-commands-queries.md\`: application checks, application logs, API/order investigation, and release operations.
- \`shopNow-configuration-guide.md\`: service dependencies, configuration contract, application release pattern, and production hardening.

Together, these documents are the operational handover. Keep values that change frequently (ELB hostnames, image tags, build numbers, instance IDs) verified by command before using them in a production change.
