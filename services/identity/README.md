# Identity

OIDC authorization server + tenancy for the platform. See [ADR-006](../../docs/adr/ADR-006-spring-authorization-server.md).

**Status:** 🧱 Phase 1 — not started.

## Owns

- OIDC login (Authorization Code + PKCE for Desktop)
- Tenants (`retail-shop`, `ember`, `khukuri`), users, roles (`owner`/`admin`/`analyst`/`viewer`)
- Service accounts (client-credentials) for service-to-service calls
- Per-tenant **ingest keys** (telemetry) and **gateway keys** (assistant access)

## Never does

Anything AI.

## Stack

Java 21 · Spring Boot · Spring Authorization Server · Postgres
