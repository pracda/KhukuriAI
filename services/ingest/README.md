# Ingest

Telemetry front door: OTLP receiver → Kafka (`telemetry.raw`) → batched ClickHouse writes. See [ADR-003](../../docs/adr/ADR-003-clickhouse-for-telemetry.md), [ADR-004](../../docs/adr/ADR-004-kafka-event-bus.md).

**Status:** ✅ v0 implemented and verified end-to-end against live Kafka + ClickHouse.

## Owns

- OTLP/HTTP receive on the standard paths (`/v1/logs`, `/v1/metrics`, `/v1/traces`), authenticated by per-tenant **ingest keys**
- Producing the [`telemetry.raw`](../../contracts/events/telemetry.raw.v1.schema.json) envelope to Kafka, partitioned by tenant
- Consuming that topic, flattening OTLP into rows, and batch-inserting into ClickHouse

## Never does

Interpretation of data — no detection, no rules, no queries. That is [Incident](../incident/)'s job.

## How a tenant ships telemetry

Point any OpenTelemetry SDK or Collector at this service with the tenant's ingest key:

```yaml
exporters:
  otlphttp:
    endpoint: http://localhost:8182
    encoding: json               # required — see limitations
    headers:
      X-Khukuri-Ingest-Key: khk_ing_...
```

Issue a key from Identity (platform admin):

```bash
curl -X POST http://localhost:8181/api/v1/tenants/retail-shop/keys \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{"type":"INGEST","label":"otel collector"}'
```

## Security model

- The key is verified against Identity's internal `/api/v1/keys/verify` endpoint, using client-credentials held by this service. **Key type is enforced here**: a valid `GATEWAY` key presented to the telemetry endpoint is rejected, and vice versa.
- **`tenant_id` is stamped from the authenticated key, never read from the payload.** A tenant cannot claim to be another tenant by crafting resource attributes.
- Verified keys are cached (default 60s, `ingest.identity.cache-ttl`) so each batch is not a round-trip. **Revocation therefore takes effect within one TTL** — the deliberate tradeoff.

## Storage

Three ClickHouse tables — `logs`, `metrics`, `spans` — created on boot (idempotent DDL; the seam where a versioned migration runner goes once the schema evolves). Each is a `ReplacingMergeTree` partitioned by day, with resource attributes denormalized onto every row so queries never need a join:

```sql
SELECT service_name, count() FROM logs
WHERE tenant_id = 'retail-shop' AND severity = 'ERROR'
  AND timestamp > now() - INTERVAL 30 MINUTE
GROUP BY service_name;
```

## Delivery guarantees

At-least-once: Kafka offsets commit after a successful ClickHouse write, so a crash mid-batch replays it. Rows carry a content fingerprint in the sort key, so replays collapse on merge — **ClickHouse dedup is eventual, not immediate**, so a query run seconds after a replay may briefly see duplicates. An unparseable payload is logged and skipped rather than retried forever; one malformed batch must not stall a tenant's telemetry.

## v0 limitations

- **OTLP/JSON only.** Protobuf (the OTLP default) is rejected with `415` and an actionable message rather than silently dropped. Adding it means protobuf codegen against `opentelemetry-proto`; the receiver already carries the payload opaquely, so only the consumer-side parser changes.
- Histograms are reduced to their running `sum`; bucket boundaries are not stored.
- No backpressure signalling — a saturated ClickHouse shows up as consumer lag, not as `429`s to the sender.

## Run

```bash
# data plane first, from infra/compose:  docker compose up -d postgres kafka clickhouse
# Identity must be running for key verification (port 8181)
CLICKHOUSE_PASSWORD=... IDENTITY_CLIENT_SECRET=... mvn spring-boot:run   # port 8182
```

Config (env): `CLICKHOUSE_URL` / `CLICKHOUSE_USER` / `CLICKHOUSE_PASSWORD`, `KAFKA_BOOTSTRAP`, `IDENTITY_URL` / `IDENTITY_CLIENT_ID` / `IDENTITY_CLIENT_SECRET`, `INGEST_TOPIC`, `INGEST_KEY_CACHE_TTL`.

## Stack

Java 21 · Spring Boot · Spring Kafka · ClickHouse JDBC

> Dependency note: the `clickhouse-jdbc` `all` shaded classifier (0.6.5) omits the core `com.clickhouse.client` classes and fails at startup with `NoClassDefFoundError`. This module uses the explicit non-shaded artifacts instead.
