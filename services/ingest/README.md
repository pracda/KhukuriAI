# Ingest

Telemetry front door: OTLP receiver → Kafka (`telemetry.raw`) → batched ClickHouse writes. See [ADR-003](../../docs/adr/ADR-003-clickhouse-for-telemetry.md), [ADR-004](../../docs/adr/ADR-004-kafka-event-bus.md).

**Status:** 🧱 Phase 2 — not started. Until it exists, the Compose OTel Collector exports to debug output.

## Owns

- OTLP gRPC/HTTP receive, authenticated by per-tenant ingest keys
- Producing to `telemetry.raw` (envelope schema in `../../contracts/events/`)
- Consuming and batch-inserting into ClickHouse, at-least-once with idempotent inserts

## Never does

Interpretation of data — no detection, no queries. That is Incident's job.

## Stack

Java 21 · Spring Boot · Kafka · ClickHouse
