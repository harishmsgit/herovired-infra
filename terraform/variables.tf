variable "aws_region" {
  description = "AWS region where resources will be provisioned"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment identifier"
  type        = string
  default     = null
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets"
  type        = list(string)
  default     = ["10.20.1.0/24", "10.20.2.0/24"]
}

variable "instance_type" {
  description = "EC2 instance type for the management instance"
  type        = string
  default     = "t3.medium"
}

variable "allowed_ssh_cidr" {
  description = "CIDR block allowed to SSH into the management instance"
  type        = string
  default     = "0.0.0.0/0"
}

variable "cluster_name" {
  description = "Name of the EKS cluster"
  type        = string
  default     = "shopnow-eks"
}

variable "management_key_name" {
  description = "Existing EC2 key pair name used for SSH access to the management instance"
  type        = string
  default     = null
}

variable "state_bucket" {
  description = "S3 bucket name used to store Terraform state"
  type        = string
  default     = "shopnow-terraform-state"
}

variable "lock_table" {
  description = "DynamoDB table name used for Terraform state locking"
  type        = string
  default     = "shopnow-terraform-locks"
}

variable "node_instance_types" {
  description = "EKS node group instance types"
  type        = list(string)
  default     = ["t3.medium"]
}

variable "node_desired_size" {
  description = "Desired node count"
  type        = number
  default     = 2
}

variable "node_min_size" {
  description = "Minimum node count"
  type        = number
  default     = 1
}

variable "node_max_size" {
  description = "Maximum node count"
  type        = number
  default     = 3
}