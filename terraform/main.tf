locals {
  env         = coalesce(var.environment, terraform.workspace)
  name_prefix = "${local.env}-shopnow"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name        = "${local.name_prefix}-vpc"
    Environment = local.env
    Project     = "shopNow"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name        = "${local.name_prefix}-igw"
    Environment = local.env
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name        = "${local.name_prefix}-public-rt"
    Environment = local.env
  }
}

resource "aws_subnet" "public" {
  count                   = length(var.public_subnet_cidrs)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name                                        = "${local.name_prefix}-public-${count.index + 1}"
    Environment                                 = local.env
    "kubernetes.io/role/elb"                    = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "eks_cluster" {
  name        = "${local.name_prefix}-eks-sg"
  description = "EKS control plane security group"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
    description = "Allow cluster communication"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${local.name_prefix}-eks-sg"
    Environment = local.env
  }
}

resource "aws_security_group" "management" {
  name        = "${local.name_prefix}-management-sg"
  description = "Security group for the management EC2 instance"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${local.name_prefix}-management-sg"
    Environment = local.env
  }
}

data "aws_iam_policy_document" "eks_cluster_assume" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "eks_cluster" {
  name               = "${local.name_prefix}-eks-cluster-role"
  assume_role_policy = data.aws_iam_policy_document.eks_cluster_assume.json
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  role       = aws_iam_role.eks_cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

data "aws_iam_policy_document" "eks_node_assume" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "eks_node_group" {
  name               = "${local.name_prefix}-eks-node-group-role"
  assume_role_policy = data.aws_iam_policy_document.eks_node_assume.json
}

resource "aws_iam_role_policy_attachment" "node_policy" {
  role       = aws_iam_role.eks_node_group.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

resource "aws_iam_role_policy_attachment" "cni_policy" {
  role       = aws_iam_role.eks_node_group.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_iam_role_policy_attachment" "ecr_full_access_policy" {
  role       = aws_iam_role.eks_node_group.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}

resource "aws_iam_role_policy_attachment" "ssm_policy" {
  role       = aws_iam_role.eks_node_group.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "management_assume" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "management" {
  name               = "${local.name_prefix}-management-role"
  assume_role_policy = data.aws_iam_policy_document.management_assume.json
}

resource "aws_iam_instance_profile" "management" {
  name = "${local.name_prefix}-management-profile"
  role = aws_iam_role.management.name
}

resource "aws_iam_role_policy_attachment" "management_ssm" {
  role       = aws_iam_role.management.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "management_ecr_full_access" {
  role       = aws_iam_role.management.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryFullAccess"
}

resource "aws_iam_role_policy_attachment" "management_eks_access" {
  role       = aws_iam_role.management.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "main" {
  name     = var.cluster_name
  role_arn = aws_iam_role.eks_cluster.arn

  vpc_config {
    subnet_ids              = aws_subnet.public[*].id
    endpoint_public_access  = true
    endpoint_private_access = false
    security_group_ids      = [aws_security_group.eks_cluster.id]
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster_policy,
  ]

  tags = {
    Name        = var.cluster_name
    Environment = local.env
    Project     = "shopNow"
  }
}

resource "aws_eks_node_group" "main" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.name_prefix}-nodes"
  node_role_arn   = aws_iam_role.eks_node_group.arn
  subnet_ids      = aws_subnet.public[*].id
  instance_types  = var.node_instance_types

  scaling_config {
    desired_size = var.node_desired_size
    min_size     = var.node_min_size
    max_size     = var.node_max_size
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_policy,
    aws_iam_role_policy_attachment.cni_policy,
    aws_iam_role_policy_attachment.ecr_full_access_policy,
    aws_iam_role_policy_attachment.ssm_policy,
  ]

  tags = {
    Name        = "${local.name_prefix}-nodes"
    Environment = local.env
  }
}

# Keep the existing node group online while this production-sized workload
# group is created. This avoids the outage caused by replacing an EKS node
# group solely to change its instance type.
resource "aws_eks_node_group" "workloads" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${local.name_prefix}-workloads"
  node_role_arn   = aws_iam_role.eks_node_group.arn
  subnet_ids      = aws_subnet.public[*].id
  instance_types  = var.workload_node_instance_types
  capacity_type   = "ON_DEMAND"

  scaling_config {
    desired_size = var.workload_node_desired_size
    min_size     = var.workload_node_min_size
    max_size     = var.workload_node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = {
    workload = "shopnow"
  }

  depends_on = [
    aws_iam_role_policy_attachment.node_policy,
    aws_iam_role_policy_attachment.cni_policy,
    aws_iam_role_policy_attachment.ecr_full_access_policy,
    aws_iam_role_policy_attachment.ssm_policy,
  ]

  tags = {
    Name        = "${local.name_prefix}-workloads"
    Environment = local.env
    NodePool    = "workloads"
    Project     = "shopNow"
  }
}

# External Secrets Operator reads application secrets using its own least-privilege
# IAM role. IRSA keeps AWS credentials out of Kubernetes Secret objects.
data "aws_caller_identity" "current" {}

data "tls_certificate" "eks_oidc" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks_oidc.certificates[0].sha1_fingerprint]

  tags = {
    Name        = "${local.name_prefix}-eks-oidc"
    Environment = local.env
    Project     = "shopNow"
  }
}

data "aws_iam_policy_document" "external_secrets_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:sub"
      values   = ["system:serviceaccount:shopnow-ns:shopnow-external-secrets"]
    }
  }
}

resource "aws_iam_role" "external_secrets" {
  name               = "${local.name_prefix}-external-secrets-role"
  assume_role_policy = data.aws_iam_policy_document.external_secrets_assume.json
}

resource "aws_iam_role_policy" "external_secrets_secrets_read" {
  name = "${local.name_prefix}-external-secrets-read"
  role = aws_iam_role.external_secrets.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:shopnow/*"
      },
    ]
  })
}

resource "helm_release" "external_secrets" {
  # This release was created before Terraform managed it. Keep its existing
  # name and namespace so Helm retains ownership of its CRDs and admission
  # webhooks during the in-place upgrade.
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "2.9.0"
  namespace        = "shopnow-ns"
  create_namespace = true
  wait             = true
  timeout          = 600
  atomic           = true

  set {
    name  = "controllerClass"
    value = "shopnow"
  }

  # Reconcile only the application's namespaced resources. This avoids
  # cluster-wide permissions for ClusterSecretStore/ClusterExternalSecret.
  set {
    name  = "scopedRBAC"
    value = "true"
  }

  set {
    name  = "scopedNamespace"
    value = "shopnow-ns"
  }

  set {
    name  = "serviceAccount.name"
    value = "shopnow-external-secrets"
  }

  set {
    name  = "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = aws_iam_role.external_secrets.arn
    type  = "string"
  }

  depends_on = [
    aws_eks_node_group.workloads,
    aws_iam_role_policy.external_secrets_secrets_read,
  ]
}

resource "aws_instance" "management" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.management.id]
  iam_instance_profile   = aws_iam_instance_profile.management.name
  key_name               = var.management_key_name

  associate_public_ip_address = true

  user_data = <<-EOF
    #!/bin/bash
    set -eux
    dnf update -y || yum update -y || true
    dnf install -y docker git curl unzip python3 python3-pip || yum install -y docker git curl unzip python3 python3-pip || true
    systemctl enable docker
    systemctl start docker
    usermod -aG docker ec2-user || true
  EOF

  # User data is bootstrap-only. Do not restart or alter the management host
  # during an unrelated infrastructure rollout because of historical drift.
  lifecycle {
    ignore_changes = [user_data]
  }

  tags = {
    Name        = "${local.name_prefix}-management-instance"
    Environment = local.env
    Role        = "management-instance"
  }
}

# Use data source to reference existing ECR repositories instead of trying to create them.
# The repositories are created externally or imported into Terraform state via 'terraform import'.
data "aws_ecr_repository" "app" {
  for_each = toset(["frontend", "admin", "backend"])
  name     = "${var.ecr_repo_prefix}/${each.key}"
}
