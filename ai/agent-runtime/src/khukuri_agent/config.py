"""Runtime configuration.

Note what is absent: there are no LLM provider keys here. The runtime reaches models
only through the Khukuri Gateway, which owns provider credentials, budgets, and
guardrails. That is design principle #1, enforced by not having the keys to break it.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_", env_file=".env", extra="ignore")

    database_url: str = "postgresql+psycopg://khukuri:@localhost:5442/khukuri"
    db_schema: str = "agent"

    # The only door to models.
    gateway_url: str = "http://localhost:8080"
    gateway_api_key: str = ""
    gateway_provider: str = "anthropic"
    gateway_task: str = "reasoning"

    # Platform APIs the tools call.
    incident_url: str = "http://localhost:8183"
    identity_url: str = "http://localhost:8181"
    identity_client_id: str = "khukuri-gateway"
    identity_client_secret: str = "local-dev-gateway-secret"

    # Loop bounds. A runaway agent is a cost incident, so the ceiling is explicit.
    max_steps: int = 8
    tool_result_chars: int = 3000
    request_timeout_seconds: float = 60.0

    # Optional webhook a restart_service approval calls. Unset means the runtime has no
    # infrastructure reach, which is the safe default.
    runner_webhook_url: str = ""


settings = Settings()
