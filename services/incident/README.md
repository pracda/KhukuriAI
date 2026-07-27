# Incident

Detection and the incident lifecycle — and the **Telemetry Query API**, which is the tool surface the agent runtime uses to investigate.

**Status:** 🧱 Phase 2 — not started.

## Owns

- Detection rules over ClickHouse (error rate, latency, saturation), evaluated on a schedule
- Incident lifecycle: open → acknowledged → mitigated → resolved; events on `incidents.events`
- Telemetry Query API: typed endpoints (`get_error_logs`, `get_deployments`, `get_metric`, …) consumed as agent tools

## Never does

Calling LLMs directly. Interpretation is the ops-analyst agent's job.

## Stack

Java 21 · Spring Boot · ClickHouse · Kafka · Postgres (incident records)
