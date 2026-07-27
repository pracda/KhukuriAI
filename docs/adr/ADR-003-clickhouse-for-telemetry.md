# ADR-003: ClickHouse as the telemetry store

**Status:** Accepted · **Date:** 2026-07-27

## Context

The ops vertical needs a store for logs, metrics, and traces that AI agents can query with ad-hoc aggregate questions ("error rate by endpoint over the last 30 minutes, compared to before the deploy"). Candidates: Loki+Prometheus, Elasticsearch, Postgres-only, ClickHouse.

## Decision

**ClickHouse**, single node in Compose, receiving all three signal types via the OpenTelemetry export path.

## Consequences

- Columnar SQL fits the agent tool surface: the Telemetry Query API translates tool calls into aggregate SQL.
- One store for all three signals instead of two systems (Loki+Prom) with two query languages.
- Native OTel exporter support; runs comfortably single-node on a laptop.
- Cost: operational learning curve vs Postgres; no built-in dashboards (Desktop and, later, Grafana cover this). Accepted.
- Rejected: Elasticsearch (heavy, license churn), Postgres-only (telemetry volume + weak analytics).
