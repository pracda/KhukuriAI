# Identity

OIDC authorization server + tenancy for the platform. See [ADR-006](../../docs/adr/ADR-006-spring-authorization-server.md).

**Status:** ✅ v0 implemented — OIDC endpoints, seeded tenants, RBAC-guarded admin API, per-tenant key issuance and verification.

## What it does

- **OIDC provider** (Spring Authorization Server): Authorization Code + PKCE for Khukuri Desktop (`khukuri-desktop`, public client), client-credentials for platform services (`khukuri-gateway`, scope `internal`). Discovery at `/.well-known/openid-configuration`.
- **Tenants:** `khukuri`, `retail-shop`, `ember` seeded on first boot; more via the admin API.
- **Users & roles:** grants are `tenant:role` with roles `owner` / `admin` / `analyst` / `viewer`, carried in the JWT `roles` claim. `owner`/`admin` on the `khukuri` tenant ⇒ platform admin.
- **API keys:** per-tenant `INGEST` (telemetry) and `GATEWAY` (LLM access) keys, format `khk_ing_…` / `khk_gw_…`. Only a SHA-256 hash is stored; the raw key is returned exactly once at issuance.

## API

| Method | Endpoint | Auth | Purpose |
|---|---|---|---|
| GET/POST | `/api/v1/tenants` | platform admin | List / create tenants |
| POST | `/api/v1/tenants/{slug}/keys` | platform admin | Issue a key (raw key in response, once) |
| GET | `/api/v1/tenants/{slug}/keys` | platform admin | List keys (masked) |
| DELETE | `/api/v1/keys/{id}` | platform admin | Revoke a key |
| POST | `/api/v1/keys/verify` | service token (`SCOPE_internal`) | Gateway/Ingest validate a presented tenant key |
| GET | `/api/v1/users/me` | any JWT | Who am I + role grants |

## Run

```bash
# data plane first (from infra/compose): docker compose up -d
mvn spring-boot:run          # port 8181, needs Postgres on localhost:5442
```

Or containerized, from `infra/compose`: `docker compose --profile apps up -d --build identity`.

Config (env): `IDENTITY_ADMIN_PASSWORD` (bootstrap `admin` user), `IDENTITY_CLIENT_GATEWAY_SECRET`, `IDENTITY_ISSUER`, standard `SPRING_DATASOURCE_*`. Tables live in the `identity` schema (Flyway-managed).

## Deliberate v0 tradeoffs

- **Ephemeral signing key** — a fresh RSA key per boot invalidates tokens on restart. Persistent key material (env-provided PEM) is a pre-exposure hardening item.
- **In-memory client registry & authorizations** — registered clients come from config, not the DB; refresh tokens don't survive restarts.
- No user self-registration — users are provisioned by admins (by design for now).

## Never does

Anything AI.
