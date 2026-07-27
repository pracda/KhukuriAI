# AWS thin slice

Terraform for the deliberately small production footprint (~$20–50/mo):

- one `t4g.small` EC2 instance running Docker Compose (gateway, identity, Postgres)
- Caddy for TLS termination
- state in S3, secrets in SSM Parameter Store

## Why not EKS

This is a cost decision made on purpose. A single-founder platform with one live endpoint does not need a ~$75/mo control plane plus node groups; it needs an honest, reproducible, IaC-managed deployment. The Compose file *is* the service topology; Kubernetes manifests live in `../kind/` and are exercised locally. If real multi-node load ever exists, the migration path is ECS/EKS with the same images.

**Status:** 🧱 Phase 1 — written when the gateway migration lands. The existing gateway deployment is imported/replaced under Terraform management at that point.
