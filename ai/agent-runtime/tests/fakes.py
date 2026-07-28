"""Test doubles.

A scripted gateway rather than a live model: real LLM output is non-deterministic, which
makes it useless for asserting that the loop suspends at a gate or resumes after a denial.
Scripting the model turns tests the machinery exactly; the live run tests the rest.
"""

from __future__ import annotations


class ScriptedGateway:
    def __init__(self, script: list[str]):
        self.script = list(script)
        self.calls: list[dict] = []

    def complete(self, system_prompt: str, user_message: str, history=None) -> dict:
        self.calls.append({
            "system_prompt": system_prompt,
            "user_message": user_message,
            "history": history or [],
        })
        content = self.script.pop(0) if self.script else '{"answer":"no more script"}'
        return {"content": content, "model": "test-model", "provider": "test",
                "cost_usd": 0.001}
