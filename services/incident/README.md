# Incident

Detection, the incident lifecycle, and the **Telemetry Query API** — the tool surface the ops-analyst agent uses to investigate.

**Status:** ✅ v0 implemented and verified end-to-end against live Kafka, ClickHouse, and Postgres.

## Owns

- Threshold rules evaluated on a schedule over ClickHouse telemetry
- Incident lifecycle `OPEN → ACKNOWLEDGED → MITIGATED → RESOLVED`, with events on `incidents.events`
- Deployment records — the "what changed?" half of root-cause analysis
- The Telemetry Query API consumed as agent tools

## Never does

Call an LLM. Interpretation is the [agent runtime](../../ai/agent-runtime/)'s job; this service serves facts.

## Telemetry Query API — the agent's tools

Each endpoint answers a question an investigator actually asks, rather than exposing SQL. That keeps the agent's tool schema typed and means a confused or compromised model cannot express an arbitrary query.

| Endpoint | Agent tool | Answers |
|---|---|---|
| `GET /api/v1/telemetry/error-logs` | `get_error_logs` | What is failing, grouped and counted |
| `GET /api/v1/telemetry/metric?name=` | `get_metric` | Is this metric climbing? (bucketed series) |
| `GET /api/v1/telemetry/service-health` | `get_service_health` | Error rate per service |
| `GET /api/v1/telemetry/slow-spans` | `get_slow_spans` | Where is time going? (p95 by span) |
| `GET /api/v1/deployments` | `get_deployments` | What changed recently? |
| `GET /api/v1/incidents` | `list_incidents` | What is currently broken? |

## Incident + deployment API

```
GET  /api/v1/incidents?tenant=&status=
GET  /api/v1/incidents/{reference}
POST /api/v1/incidents/{reference}/acknowledge|mitigate|resolve
POST /api/v1/deployments          # CI/CD records a deploy
```

Illegal lifecycle transitions return `409` with the reason rather than silently no-opping.

## Detection rules

Configured under `incident.detection` — threshold-based on purpose. Anomaly detection is the obvious next step, but a threshold an engineer can read and predict beats a model whose false positives are unexplainable.

| Rule | Fires when | Guard |
|---|---|---|
| `error-rate` | share of ERROR logs ≥ threshold (default 5%) | `min-events` ignores tiny samples where one error is 50% |
| `latency-p95` | p95 span duration ≥ threshold (default 2000ms) | `min-spans` |
| `saturation:<metric>` | latest value of a named metric ≥ threshold | — |

Severity scales with how far past the threshold the observation is: ≥1.25× MEDIUM, ≥2× HIGH, ≥3× CRITICAL.

**Rules are symmetric.** A firing rule opens (or refreshes) an incident; a rule that stops firing auto-resolves it. Without the resolve half, every transient blip would leave an incident open forever and the board becomes noise nobody trusts.

**Dedup is enforced at the database.** A partial unique index allows at most one unresolved incident per `(tenant, service, rule)`. A detector running every 30 seconds against an hour-long outage would otherwise open 120 incidents for one failure; concurrent passes collapse on the index rather than racing.

## Security model

Every endpoint requires an Identity-issued token. Tenant scoping is enforced by `TenantAccess` on **every** read:

- **Platform admin** (`khukuri:owner|admin`) — all tenants
- **Service token** (`SCOPE_internal`, the agent runtime) — all tenants; it acts on the platform's behalf and receives its tenant from the incident context
- **Everyone else** — only tenants they hold a role grant for

Requesting another tenant returns **403, not an empty list** — a caller asking for someone else's data should be told no, not handed a plausible-looking blank page. Query methods take the resolved tenant list as a parameter, so a missing check is a compile error rather than a silent leak.

## Known gaps (v0)

- **A service that goes completely silent never auto-resolves.** Rules evaluate over services *present in the window*; a service emitting nothing produces no row, so neither branch runs and its incidents stay open. Arguably correct — silence is not health — but it means "resolved" currently means "reporting and healthy". A dedicated absence-of-telemetry rule is the fix.
- Detection state is recomputed from scratch each pass; there is no flap damping, so a metric oscillating around its threshold will open/resolve repeatedly.
- Deployments are recorded explicitly by CI/CD. Deriving them from a changed `service.version` resource attribute is possible but cannot see a deploy that never emitted telemetry.
- Thresholds are global, not per-service; a batch job with a naturally high error rate needs its own tuning.
- **Saturation reads the *latest* sample, so short spikes are missed.** `latestMetricByService` uses `argMax(value, timestamp)`. A pool that queued 43 threads during a burst and recovered before the next detector pass reads as `0` and never opens an incident — observed live. For saturation, `max()` over the window is the correct aggregate: a pool that hit its ceiling at any point in the window is worth knowing about even if it recovered. This is the top fix.
- **Saturation rules compare absolute values, so only gauges belong in them.** A cumulative counter (`db.client.connections.wait_time`, `.use_time`) only ever grows, so a fixed threshold fires once and then permanently — this bit us live, opening an incident on a counter at 1.4 million. Rate-of-change rules are the fix; until then, configure gauges only.
- **Saturation rules do not filter by metric attributes.** A metric split across attribute values — `db.client.connections.usage` carries `state=used|idle` — is aggregated with `argMax` across all of them, so the rule would compare an arbitrary series. The configured rules therefore use metrics that are unambiguous on their own (`pending_requests`, `wait_time`). Attribute-aware rule predicates are the fix.

## Run

```bash
# needs: postgres + clickhouse + kafka (infra/compose), Identity on 8181, Ingest on 8182
SPRING_DATASOURCE_PASSWORD=... CLICKHOUSE_PASSWORD=... mvn spring-boot:run   # port 8183
```

Two datasources are declared explicitly: **Postgres** (primary — incidents, deployments, Flyway, JPA) and **ClickHouse** (read-only telemetry, its own `JdbcTemplate`).

## Stack

Java 21 · Spring Boot · Spring Security (OAuth2 resource server) · JPA + Flyway on Postgres · ClickHouse JDBC · Spring Kafka
