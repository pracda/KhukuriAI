"""Service-token acquisition for calling platform APIs."""

from __future__ import annotations

import logging
import time

import httpx

from .config import settings

log = logging.getLogger(__name__)


class ServiceTokenProvider:
    """Client-credentials token with refresh, used for Incident's internal endpoints."""

    def __init__(self, client: httpx.Client | None = None):
        self._client = client or httpx.Client(timeout=settings.request_timeout_seconds)
        self._token: str | None = None
        self._expires_at: float = 0.0

    def token(self) -> str:
        if self._token and time.time() < self._expires_at - 30:
            return self._token

        # RFC 6749: parameters go in a form-encoded body. Passing them as query
        # parameters is rejected with unsupported_grant_type.
        response = self._client.post(
            f"{settings.identity_url.rstrip('/')}/oauth2/token",
            data={"grant_type": "client_credentials", "scope": "internal"},
            auth=(settings.identity_client_id, settings.identity_client_secret),
        )
        if response.status_code >= 400:
            raise RuntimeError(
                f"Could not obtain a service token ({response.status_code}): {response.text[:200]}"
            )
        payload = response.json()
        self._token = payload["access_token"]
        self._expires_at = time.time() + float(payload.get("expires_in", 300))
        return self._token

    def auth_header(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token()}"}
