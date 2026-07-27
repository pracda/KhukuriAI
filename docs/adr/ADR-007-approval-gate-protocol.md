# ADR-007: Approval-gate protocol for mutating agent tools

**Status:** Accepted · **Date:** 2026-07-27

## Context

Agents propose actions like "restart the service" or "open a PR." Letting a model mutate infrastructure directly is unacceptable; making every tool call interactive makes agents useless.

## Decision

Tools are classified **read-only** or **mutating** in the tool registry — the classification lives in code review, not model judgment. Read-only tools execute immediately. A mutating tool call is persisted in Postgres as `PENDING_APPROVAL` with full arguments and context, surfaced to a human in Desktop, and executes only on an approve carrying the approver's authenticated identity. Deny returns "action declined" to the agent, which must continue and propose alternatives. Every transition is an audit row.

This holds **regardless of what the model asks for** — prompt-injected instructions inside tool results (e.g., malicious text in a log line) cannot cross the gate, because gating is enforced by the runtime, not by the model.

## Consequences

- Agent runs are resumable across the approval wait (state is persisted, not held in memory).
- The audit trail answers "who approved what, when, and what happened" — a headline enterprise feature.
- Cost: mutating flows are asynchronous by design; the runtime must handle suspended runs from day one. Accepted deliberately.
