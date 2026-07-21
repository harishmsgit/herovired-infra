SHELL := /bin/bash
.PHONY: help login-ecr build-images build-and-push terraform-init terraform-plan terraform-apply deploy clean

# Defaults (override via environment or `make VAR=value`)
AWS_REGION ?= ap-south-1
AWS_ACCOUNT_ID ?= 495013583028
ECR_REPO_PREFIX ?= shopnow
SINGLE_ECR_REPOSITORY ?=
IMAGE_TAG ?= latest
USER_NAME ?= harish
TF_STATE_BUCKET ?= shopnow-terraform-state
LOCK_TABLE ?= shopnow-terraform-locks
K8S_NAMESPACE ?= shopnow-ns
MANIFEST_ROOT ?= herovired-infra/kubernetes/k8s-manifests

REGISTRY := $(AWS_ACCOUNT_ID).dkr.ecr.$(AWS_REGION).amazonaws.com

ifeq ($(strip $(SINGLE_ECR_REPOSITORY)),)
FRONTEND_IMAGE := $(REGISTRY)/$(ECR_REPO_PREFIX)/frontend:$(IMAGE_TAG)
ADMIN_IMAGE := $(REGISTRY)/$(ECR_REPO_PREFIX)/admin:$(IMAGE_TAG)
BACKEND_IMAGE := $(REGISTRY)/$(ECR_REPO_PREFIX)/backend:$(IMAGE_TAG)
else
FRONTEND_IMAGE := $(REGISTRY)/$(SINGLE_ECR_REPOSITORY):frontend-$(IMAGE_TAG)
ADMIN_IMAGE := $(REGISTRY)/$(SINGLE_ECR_REPOSITORY):admin-$(IMAGE_TAG)
BACKEND_IMAGE := $(REGISTRY)/$(SINGLE_ECR_REPOSITORY):backend-$(IMAGE_TAG)
endif

help:
	@echo "Makefile targets:"
	@echo "  make login-ecr            # Log in to ECR via AWS CLI"
	@echo "  make build-images         # Build and push images using scripts/build-and-push.sh"
	@echo "  make terraform-init       # Terraform init (in terraform/)",
	@echo "  make terraform-plan       # Terraform plan (in terraform/)",
	@echo "  make terraform-apply      # Terraform apply (in terraform/)",
	@echo "  make deploy               # Apply k8s manifests from $(MANIFEST_ROOT) using computed image tags"
	@echo "  make clean                # Cleanup workspace artifacts (no-op)"

login-ecr:
	@echo "Logging in to ECR registry $(REGISTRY)"
	aws ecr get-login-password --region $(AWS_REGION) | docker login --username AWS --password-stdin $(REGISTRY)

build-images: login-ecr
	@echo "Building and pushing images to $(REGISTRY)"
	@./scripts/build-and-push.sh $(REGISTRY)/$(if $(strip $(SINGLE_ECR_REPOSITORY)),$(SINGLE_ECR_REPOSITORY),$(ECR_REPO_PREFIX)) $(IMAGE_TAG) $(USER_NAME)

terraform-init:
	@echo "Initializing terraform in ./terraform"
	@cd terraform && terraform init -reconfigure \
		-backend-config="bucket=$(TF_STATE_BUCKET)" \
		-backend-config="region=$(AWS_REGION)" \
		-backend-config="dynamodb_table=$(LOCK_TABLE)"

terraform-plan:
	@cd terraform && terraform plan -out=tfplan

terraform-apply:
	@cd terraform && terraform apply -auto-approve tfplan

deploy:
	@echo "Deploying manifests from $(MANIFEST_ROOT) to namespace $(K8S_NAMESPACE)"
	@kubectl create namespace $(K8S_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@set -e; find $(MANIFEST_ROOT) -type f \( -name '*.yaml' -o -name '*.yml' \) | sort | while read file; do \
		echo "Applying $$file"; \
		sed -e 's|REPLACE_NAMESPACE|$(K8S_NAMESPACE)|g' -e 's|REPLACE_FRONTEND_IMAGE|$(FRONTEND_IMAGE)|g' -e 's|REPLACE_ADMIN_IMAGE|$(ADMIN_IMAGE)|g' -e 's|REPLACE_BACKEND_IMAGE|$(BACKEND_IMAGE)|g' "$$file" | kubectl apply -f -; \
	done

clean:
	@echo "Nothing to clean by default. Add custom cleanup if required."
