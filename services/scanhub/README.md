# Scan Hub

Security scan orchestration and the normalized findings store.

**Status:** 🧱 Phase 2 — not started.

## Owns

- Scheduling scans (`scan.jobs` on Kafka): dependency (Trivy), secrets (Gitleaks), containers
- Workers that run scanners and normalize raw output into a common findings model
- Findings store in Postgres (severity, package, location, status)
- `findings-ready` events that trigger security-analyst triage

## Never does

Interpreting findings — exploitability and prioritization are the security-analyst agent's job.

## Stack

Java 21 · Spring Boot · Kafka · Postgres · Trivy/Gitleaks in worker containers
