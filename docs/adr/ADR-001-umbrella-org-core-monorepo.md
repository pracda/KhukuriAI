# ADR-001: Umbrella GitHub org + core monorepo

**Status:** Accepted · **Date:** 2026-07-27

## Context

The platform consolidates ~9 previously independent repositories (gateway phases, agent experiments, POS apps, desktop client). Options: one giant monorepo for everything, fully separate repos, or a hybrid.

## Decision

GitHub org `khukuri-ai` holds everything. The **core monorepo (`khukuri`)** contains the gateway, all platform services, the agent runtime, contracts, infra, and docs — components that version and deploy together. The **desktop client** and the **satellite tenant apps** (retail POS, restaurant POS) stay separate repos in the org: they have independent release cycles and are consumers of the platform, not part of it.

## Consequences

- One clone, one CI, one set of contracts for everything that forms "the platform."
- Cross-service refactors are single PRs; no version-matrix drift between services.
- Desktop releases (installers, auto-update) are not coupled to platform CI.
- Cost: monorepo CI needs path filtering as the codebase grows; accepted.
