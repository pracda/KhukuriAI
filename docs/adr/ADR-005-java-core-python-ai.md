# ADR-005: Java core services, Python AI services

**Status:** Accepted · **Date:** 2026-07-27

## Context

The platform needs a language strategy. The founder's deepest expertise is Java/Spring Boot; the AI ecosystem (agent tooling, embeddings, evaluation libraries) is Python-first. Options: all-Java (Spring AI/LangChain4j), all-Python, or a split.

## Decision

**Java 21 / Spring Boot** for everything stateful and enterprise-facing: gateway, identity, ingest, incident, scan hub. **Python / FastAPI** only where the AI ecosystem provides decisive leverage: the agent runtime (and later RAG/knowledge services). The boundary is an HTTP contract in `contracts/openapi/`; the runtime holds no provider keys and calls models only through the gateway.

## Consequences

- Core services get mature auth, observability, and data tooling — and play to existing strength.
- The agent runtime can adopt Python-native agent/eval libraries without dragging them into the core.
- Cost: two toolchains in CI and two base images; accepted because the boundary is one contract, not many.
