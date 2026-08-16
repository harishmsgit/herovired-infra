# Infrastructure Documentation Index

This is the central index for the infrastructure and operations documentation for the project.

---

## Overview

The project has multiple layers of infrastructure and operations knowledge:
- Terraform provisions the AWS foundation
- Ansible configures and validates the management host and Kubernetes environment
- Jenkins orchestrates the CI/CD and deployment flow
- Kubernetes runs the application workloads and monitoring stack
- Monitoring and logging provide health, metrics, and alerts
- This documentation set explains the system and how to maintain it

---

## Document Map

### Core Infrastructure
- [TERRAFORM_GUIDE.md](TERRAFORM_GUIDE.md)  
  Terraform architecture, file layout, state management, common changes, troubleshooting, and maintenance patterns.

- [ANSIBLE_GUIDE.md](ANSIBLE_GUIDE.md)  
  Ansible playbooks, inventories, host configuration, validation, troubleshooting, and configuration operations.

### Deployment and Monitoring
- [DEPLOYMENT_AND_MONITORING_GUIDE.md](DEPLOYMENT_AND_MONITORING_GUIDE.md)  
  End-to-end deployment pipeline, infrastructure flow, monitoring setup, alerting, dashboards, and operational maintenance.

- [ARCHITECTURE_AND_SYNC.md](ARCHITECTURE_AND_SYNC.md)  
  Full architecture view, app-to-infra sync, configuration propagation, and change impact analysis.

### Operations and Support
- [NETWORKING_AND_CLUSTER_REFERENCE.md](NETWORKING_AND_CLUSTER_REFERENCE.md)  
  VPC, subnets, AZs, routing, gateways, NAT, cluster layout, namespaces, ingress, and internal/external traffic flow.

- [EMERGENCY_VALIDATION_CHECKLIST.md](EMERGENCY_VALIDATION_CHECKLIST.md)  
  Fast validation commands and go/no-go checks for incidents, deployments, or quick team reviews.

- [MAINTENANCE_PROCEDURES.md](MAINTENANCE_PROCEDURES.md)  
  Daily, weekly, monthly, quarterly, and annual maintenance tasks, emergency handling, and performance tuning.

- [QUICK_REFERENCE_GUIDE.md](QUICK_REFERENCE_GUIDE.md)  
  Short command lists and common troubleshooting scenarios.

---

## Recommended Reading Order

### For New Team Members
1. [DOCUMENTATION_INDEX.md](DOCUMENTATION_INDEX.md)
2. [ARCHITECTURE_AND_SYNC.md](ARCHITECTURE_AND_SYNC.md)
3. [NETWORKING_AND_CLUSTER_REFERENCE.md](NETWORKING_AND_CLUSTER_REFERENCE.md)
4. [DEPLOYMENT_AND_MONITORING_GUIDE.md](DEPLOYMENT_AND_MONITORING_GUIDE.md)
5. [TERRAFORM_GUIDE.md](TERRAFORM_GUIDE.md)
6. [ANSIBLE_GUIDE.md](ANSIBLE_GUIDE.md)
7. [EMERGENCY_VALIDATION_CHECKLIST.md](EMERGENCY_VALIDATION_CHECKLIST.md)

### For Production Ops / Incident Response
1. [EMERGENCY_VALIDATION_CHECKLIST.md](EMERGENCY_VALIDATION_CHECKLIST.md)
2. [NETWORKING_AND_CLUSTER_REFERENCE.md](NETWORKING_AND_CLUSTER_REFERENCE.md)
3. [MAINTENANCE_PROCEDURES.md](MAINTENANCE_PROCEDURES.md)
4. [DEPLOYMENT_AND_MONITORING_GUIDE.md](DEPLOYMENT_AND_MONITORING_GUIDE.md)

### For Infrastructure Change Work
1. [TERRAFORM_GUIDE.md](TERRAFORM_GUIDE.md)
2. [ANSIBLE_GUIDE.md](ANSIBLE_GUIDE.md)
3. [ARCHITECTURE_AND_SYNC.md](ARCHITECTURE_AND_SYNC.md)
4. [MAINTENANCE_PROCEDURES.md](MAINTENANCE_PROCEDURES.md)

---

## Quick Team Use

During a discussion, use the docs in this sequence:
- Architecture: [ARCHITECTURE_AND_SYNC.md](ARCHITECTURE_AND_SYNC.md)
- Networking: [NETWORKING_AND_CLUSTER_REFERENCE.md](NETWORKING_AND_CLUSTER_REFERENCE.md)
- Deployments: [DEPLOYMENT_AND_MONITORING_GUIDE.md](DEPLOYMENT_AND_MONITORING_GUIDE.md)
- Incident checks: [EMERGENCY_VALIDATION_CHECKLIST.md](EMERGENCY_VALIDATION_CHECKLIST.md)
- Maintenance: [MAINTENANCE_PROCEDURES.md](MAINTENANCE_PROCEDURES.md)

---

## Common Questions Covered

- What does Terraform create?
- What does Ansible configure?
- How does Jenkins deploy the app?
- How do pods communicate with each other?
- What is the VPC and subnet layout?
- How is external traffic routed?
- How do we validate the cluster during incidents?
- What are the standard maintenance tasks?
- What should be checked when a deployment fails?

---

## Documentation Maintenance

This documentation should be updated when:
- infrastructure changes are made in Terraform
- new Ansible roles or playbooks are introduced
- production architecture changes
- new monitoring dashboards or alerts are added
- network design or namespace model changes
- deployment process changes or new release steps are introduced

---

## Final Team Note

The documentation is intended to make system understanding easy for engineering, operations, and review meetings. If a change impacts infrastructure, networking, monitoring, or deployment flow, update the relevant guide and keep this index current.
