# Khukuri Gateway

The only door to LLM providers. Every model call on the platform — desktop chat, agent reasoning, scan triage — passes through this service.

**Status:** 🔄 migration target. The production-deployed predecessor (`llm-api-gateway`, live since 2026-07) is being migrated into this directory and rebranded. Until migration lands, this directory holds only the contract and migration notes.

## Owns

- Authentication of every request (JWT from Identity; per-tenant gateway keys)
- Model abstraction and routing (task-aware: cheap models for cheap work)
- Per-key budgets and rate limits (Redis-backed)
- Guardrails: prompt-injection and secret-leak filters
- Prompt templates and versioning
- Usage metering (tokens, cost) and per-request audit rows

## Never does

Business logic. Talking to ClickHouse. Storing conversation state for clients.

## Migration checklist

- [ ] Import source from `llm-api-gateway` (history preserved via subtree or fresh start + archive link — decide at migration)
- [ ] Rename packages/branding → `ai.khukuri.gateway`
- [ ] Swap standalone auth for Identity-issued JWTs (keep per-tenant keys — existing pattern generalizes)
- [ ] Remove any client-direct provider paths that bypass the gateway (known: desktop image generation)
- [ ] Contract in `../contracts/openapi/gateway.yaml` matches deployed behavior before any new features

## Stack

Java 21 · Spring Boot · Postgres (audit, keys, usage) · Redis (rate limits)
