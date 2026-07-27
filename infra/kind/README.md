# Local Kubernetes (kind)

Kubernetes manifests for running the platform on a local kind/k3d cluster.

**Status:** 🧱 Phase 2+ — added once at least two application services exist. Purpose: exercise the k8s deployment path (probes, config, service discovery) without paying for a cloud control plane. Production stays on the Compose-on-EC2 thin slice until scale demands otherwise — see [../terraform/README.md](../terraform/README.md).
