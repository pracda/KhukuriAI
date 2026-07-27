# ADR-004: Kafka as the event bus

**Status:** Accepted · **Date:** 2026-07-27

## Context

Telemetry ingest, incident events, and scan jobs need decoupling between producers and consumers, buffering under burst, and replay. Alternatives: direct HTTP writes between services, Redpanda, Redis Streams.

## Decision

**Apache Kafka** (KRaft, single broker locally) as the platform event bus. Initial topics: `telemetry.raw`, `incidents.events`, `scan.jobs`. Topic schemas are versioned in `contracts/events/`.

## Consequences

- Ingest survives ClickHouse downtime (buffer + replay); detection and triage become event-driven consumers rather than polling chains.
- Adding consumers (e.g., a future notification service) requires no producer changes.
- Cost: one more stateful service in Compose; single-broker locally means replication factor 1 (documented, acceptable for dev/demo).
- Rejected: direct writes (no buffering or replay), Redis Streams (weaker ecosystem/ordering guarantees at scale), Redpanda (fine technically; Kafka chosen for ecosystem and team familiarity).
