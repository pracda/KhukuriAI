"""LLM access — exclusively through the Khukuri Gateway.

The runtime holds no provider credentials. Every reasoning step is a gateway call, which
is what makes budgets, routing, guardrails, and the audit trail apply to agent traffic
the same way they apply to a human typing in Desktop.
"""

from __future__ import annotations

import logging

import httpx

from .config import settings

log = logging.getLogger(__name__)

# Gateway request limits (validated server-side); exceeding them is a 400, so the
# runtime trims to fit rather than discovering it at the edge.
SYSTEM_PROMPT_CAP = 2000
USER_MESSAGE_CAP = 8000


class GatewayError(RuntimeError):
    pass


class GatewayClient:
    def __init__(self, url: str | None = None, api_key: str | None = None,
                 client: httpx.Client | None = None):
        self.url = (url or settings.gateway_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.gateway_api_key
        self._client = client or httpx.Client(timeout=settings.request_timeout_seconds)

    def complete(self, system_prompt: str, user_message: str,
                 history: list[dict] | None = None) -> dict:
        """One reasoning turn. Returns {content, model, provider, cost_usd}."""
        body = {
            "provider": settings.gateway_provider,
            "task": settings.gateway_task,
            "systemPrompt": system_prompt[:SYSTEM_PROMPT_CAP],
            "userMessage": user_message[:USER_MESSAGE_CAP],
            "history": history or [],
            "includeMeta": True,
        }
        try:
            response = self._client.post(
                f"{self.url}/api/v1/chat",
                json=body,
                headers={"X-API-Key": self.api_key},
            )
        except httpx.HTTPError as exc:
            raise GatewayError(f"Gateway unreachable: {exc}") from exc

        if response.status_code == 402:
            # The gateway's budget cap. Surfacing it plainly beats a silent stall.
            raise GatewayError("Gateway budget exceeded for this key")
        if response.status_code == 429:
            raise GatewayError("Gateway rate limit reached")
        if response.status_code >= 400:
            raise GatewayError(f"Gateway returned {response.status_code}: {response.text[:200]}")

        payload = response.json()
        usage = payload.get("usage") or {}
        return {
            "content": payload.get("content", ""),
            "model": payload.get("model"),
            "provider": payload.get("provider"),
            "cost_usd": usage.get("costUsd") or payload.get("costUsd"),
        }

    def close(self) -> None:
        self._client.close()
