"""Tools that call the platform's own APIs.

Every tool reaches the Incident service over HTTP with a service token. The runtime never
queries ClickHouse or Postgres directly: the query surface stays typed, tenant scoping is
enforced by the service that owns the data, and a mistake here cannot become an arbitrary
read of someone else's telemetry.
"""

from __future__ import annotations

import json
import logging

import httpx

from ..config import settings
from ..identity import ServiceTokenProvider
from .registry import Tool, ToolRegistry

log = logging.getLogger(__name__)


class PlatformClient:
    def __init__(self, tokens: ServiceTokenProvider | None = None,
                 client: httpx.Client | None = None):
        self.tokens = tokens or ServiceTokenProvider()
        self._client = client or httpx.Client(timeout=settings.request_timeout_seconds)

    def _get(self, path: str, params: dict) -> str:
        response = self._client.get(
            f"{settings.incident_url.rstrip('/')}{path}",
            params={k: v for k, v in params.items() if v not in (None, "")},
            headers=self.tokens.auth_header(),
        )
        if response.status_code >= 400:
            return f"ERROR {response.status_code}: {response.text[:300]}"
        return _compact(response.json())

    def _post(self, path: str, body: dict | None = None) -> str:
        response = self._client.post(
            f"{settings.incident_url.rstrip('/')}{path}",
            json=body or {},
            headers=self.tokens.auth_header(),
        )
        if response.status_code >= 400:
            return f"ERROR {response.status_code}: {response.text[:300]}"
        return _compact(response.json())


def _compact(payload) -> str:
    """Dense JSON — every character spent here is context the model cannot spend reasoning."""
    if isinstance(payload, list) and not payload:
        return "[] (no matching records)"
    return json.dumps(payload, separators=(",", ":"), default=str)


def build_registry(platform: PlatformClient | None = None) -> ToolRegistry:
    api = platform or PlatformClient()
    registry = ToolRegistry()

    # ── read-only: auto-execute ────────────────────────────────────────────
    registry.register(Tool(
        name="get_service_health",
        description="Error rate per service. Start here.",
        args={"tenant": "str", "window_seconds": "int"},
        handler=lambda tenant, window_seconds=1800, **_: api._get(
            "/api/v1/telemetry/service-health",
            {"tenant": tenant, "windowSeconds": window_seconds}),
    ))
    registry.register(Tool(
        name="get_error_logs",
        description="Top error messages, grouped and counted.",
        args={"tenant": "str", "window_seconds": "int", "service": "str?"},
        handler=lambda tenant, window_seconds=1800, service=None, **_: api._get(
            "/api/v1/telemetry/error-logs",
            {"tenant": tenant, "windowSeconds": window_seconds, "service": service}),
    ))
    registry.register(Tool(
        name="get_metric",
        description="Time series for one metric (e.g. db.pool.active).",
        args={"name": "str", "tenant": "str", "window_seconds": "int"},
        handler=lambda name, tenant, window_seconds=3600, service=None, **_: api._get(
            "/api/v1/telemetry/metric",
            {"name": name, "tenant": tenant, "windowSeconds": window_seconds,
             "service": service, "bucketSeconds": 60}),
    ))
    registry.register(Tool(
        name="get_deployments",
        description="Recent deploys — what changed before the failure.",
        args={"tenant": "str", "window_seconds": "int"},
        handler=lambda tenant, window_seconds=86400, **_: api._get(
            "/api/v1/deployments",
            {"tenant": tenant, "windowSeconds": window_seconds}),
    ))
    registry.register(Tool(
        name="get_slow_spans",
        description="Slowest operations by p95 latency.",
        args={"tenant": "str", "window_seconds": "int"},
        handler=lambda tenant, window_seconds=1800, **_: api._get(
            "/api/v1/telemetry/slow-spans",
            {"tenant": tenant, "windowSeconds": window_seconds}),
    ))
    registry.register(Tool(
        name="list_incidents",
        description="Currently open incidents.",
        args={"tenant": "str", "status": "str?"},
        handler=lambda tenant=None, status=None, **_: api._get(
            "/api/v1/incidents", {"tenant": tenant, "status": status}),
    ))

    # ── mutating: approval-gated, always ───────────────────────────────────
    registry.register(Tool(
        name="acknowledge_incident",
        description="Mark an incident as being worked on.",
        args={"reference": "str"},
        mutating=True,
        handler=lambda reference, **_: api._post(f"/api/v1/incidents/{reference}/acknowledge"),
    ))
    registry.register(Tool(
        name="resolve_incident",
        description="Close an incident.",
        args={"reference": "str"},
        mutating=True,
        handler=lambda reference, **_: api._post(f"/api/v1/incidents/{reference}/resolve"),
    ))
    registry.register(Tool(
        name="restart_service",
        description="Restart a service to clear a stuck state.",
        args={"tenant": "str", "service": "str"},
        mutating=True,
        handler=_restart_service,
    ))
    return registry


def _restart_service(tenant: str, service: str, **_) -> str:
    """Restart via a configured runner webhook.

    With no webhook configured the runtime has no way to touch infrastructure, and says
    so rather than pretending the restart happened. An agent that reports success it did
    not achieve is worse than one that cannot act at all.
    """
    if not settings.runner_webhook_url:
        return ("NOT EXECUTED: no runner webhook is configured (AGENT_RUNNER_WEBHOOK_URL), "
                "so this runtime cannot restart services. A human must perform the restart.")
    response = httpx.post(
        settings.runner_webhook_url,
        json={"tenant": tenant, "service": service},
        timeout=settings.request_timeout_seconds,
    )
    if response.status_code >= 400:
        return f"Restart failed ({response.status_code}): {response.text[:200]}"
    return f"Restart requested for {service} in {tenant}; runner responded {response.status_code}."
