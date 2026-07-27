# Agent Runtime

The reasoning layer: agent definitions, the planning loop, tool execution, and approval gates. See [ADR-005](../../docs/adr/ADR-005-java-core-python-ai.md), [ADR-007](../../docs/adr/ADR-007-approval-gate-protocol.md).

**Status:** 🧱 Phase 2 — not started.

## Owns

- Agent definitions: `ops-analyst`, `security-analyst` (later: workforce agents)
- The plan → tool → observe loop; run transcripts persisted per run
- Tool registry with the read-only / mutating classification (code-reviewed, never model-decided)
- The approval-gate protocol: `PENDING_APPROVAL` persistence, resume on approve/deny
- Wrapping tool results as untrusted data before they reach the model

## Never does

- Hold provider API keys — **every** model call goes through the Gateway
- Touch infrastructure directly — tools call platform APIs with service tokens

## Stack

Python 3.12 · FastAPI · Postgres (runs, approvals) · Gateway for all model calls
