# ADR-008: Gateway ⇄ Identity federation via strangler pattern

**Status:** Accepted · **Date:** 2026-07-27

## Context

The gateway shipped to production with a complete standalone auth system: its own HS256 JWTs, its own users/organizations/API keys, tiers, budgets, and an audit pipeline keyed to those entities. The platform now has an Identity service (ADR-006) that should ultimately own authentication. A big-bang swap would break the deployed gateway and its live client (the retail POS keys).

## Decision

Federate incrementally — legacy auth stays fully intact; platform auth is added alongside, behind `identity.federation.enabled` (default **off**):

- **Phase A (this ADR, implemented):** bearer tokens that fail local HS256 validation are additionally verified as Identity-issued RS256 tokens (JWKS + issuer validation, lazily-built decoder so an unreachable Identity can never break boot or legacy auth). Platform grants map to gateway roles: `khukuri:owner|admin` → ADMIN, anything else → USER. Federated principals carry a `FEDERATED_IDENTITY` authority for auditability. The API-key-required contract on `/chat` is unchanged — a federated JWT alone cannot call it.
- **Phase B (planned):** platform `khk_gw_*` keys accepted on `/chat` via a *shadow-key sync* — verified platform keys materialize as gateway-side ApiKey records (tier from tenant), so budgets, spend tracking, and audit keep working against real rows instead of special cases.
- **Phase C (planned):** gateway-local registration/token issuance retired; Identity becomes the only credential source; gateway HS256 secret removed.

## Consequences

- Zero behavior change for every existing client until the flag is turned on next to a running Identity.
- One OIDC login (Desktop → Identity) will work across platform services immediately in dev.
- Cost: two token formats in flight during the transition; accepted and time-boxed by phases B/C.
- Every invalid bearer token, when the flag is on, costs one extra local verification attempt (and possibly a JWKS fetch on first use) — acceptable; the JWKS is cached by the decoder thereafter.
