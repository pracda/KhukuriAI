# ADR-006: Spring Authorization Server over Keycloak

**Status:** Accepted · **Date:** 2026-07-27

## Context

The platform needs OIDC login, tenants, roles, service accounts, and per-tenant API keys. Keycloak provides all of it off the shelf; Spring Authorization Server means building tenancy and key management ourselves.

## Decision

**Spring Authorization Server** inside `services/identity`.

## Consequences

- Tenancy, RBAC, and API-key issuance live in our own domain model instead of being modeled awkwardly around Keycloak realms/clients — and per-tenant ingest/gateway keys are a core product feature, not an add-on.
- Materially stronger engineering signal for a portfolio project than configuring Keycloak.
- Cost: we own security-sensitive code; mitigated by staying on library defaults, no custom crypto, and a dedicated security review before any external user exists.
- Revisit trigger: if SSO federation (SAML, external IdPs) becomes a requirement, reevaluate Keycloak as a fronting IdP.
