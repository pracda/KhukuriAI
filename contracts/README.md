# Contracts

Cross-service contracts are written here **before** implementation. Services may be rewritten; contracts are stable and versioned.

- `openapi/` — one OpenAPI spec per service HTTP surface. Planned: `gateway.yaml` (first — must match deployed behavior), `identity.yaml`, `incident.yaml` (the agent tool surface), `scanhub.yaml`, `agent-runtime.yaml`.
- `events/` — JSON Schema per Kafka topic, one file per schema version. Planned: `telemetry.raw`, `incidents.events`, `scan.jobs`.

Rules:

1. Breaking a contract requires a new version file, not an edit.
2. CI validates schemas; services will validate against these files in contract tests.
3. If code and contract disagree, the contract is wrong *only* when the deployed gateway is the code in question (it predates this repo) — fix the contract to match reality, then evolve both together.
