output "vpc_id" {
  description = "ID of the created VPC"
  value       = aws_vpc.main.id
}

output "public_subnet_ids" {
  description = "IDs of the created public subnets"
  value       = aws_subnet.public[*].id
}

output "cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.main.name
}

output "eks_cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.main.name
}

output "eks_cluster_endpoint" {
  description = "EKS cluster API endpoint"
  value       = aws_eks_cluster.main.endpoint
}

output "management_ec2_public_ip" {
  description = "Public IP address of the management EC2 instance"
  value       = aws_instance.management.public_ip
}

output "management_ec2_private_ip" {
  description = "Private IP address of the management EC2 instance"
  value       = aws_instance.management.private_ip
}

output "ecr_repository_names" {
  description = "ECR repository names for app services"
  value       = [for repo in aws_ecr_repository.app : repo.name]
}

output "ecr_repository_urls" {
  description = "ECR repository URLs for app services"
  value       = [for repo in aws_ecr_repository.app : repo.repository_url]
}