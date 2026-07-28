"""The agent loop: plan → tool → observe, suspending at the approval gate.

The loop is written as a *resumable* function over persisted state rather than a
long-lived coroutine. ``advance()`` drives a run until it finishes or hits a mutating
tool, then returns. Approving later calls ``advance()`` again. That is what lets a run
wait hours for a human, survive a restart, and still produce a complete transcript.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from .agents import AGENTS, AgentDefinition
from .config import settings
from .db import AgentRun, RunStatus, RunStep, ToolCall, ToolCallStatus
from .gateway import GatewayClient, GatewayError
from .protocol import FinalAnswer, ProtocolError, ToolInvocation, parse, wrap_tool_result
from .tools.registry import ToolRegistry, UnknownTool

log = logging.getLogger(__name__)


class AgentLoop:
    def __init__(self, registry: ToolRegistry, gateway: GatewayClient):
        self.registry = registry
        self.gateway = gateway

    # ── public API ─────────────────────────────────────────────────────────

    def advance(self, session: Session, run: AgentRun) -> AgentRun:
        """Drive the run until it completes, fails, or needs an approval."""
        if run.status in (RunStatus.COMPLETED, RunStatus.FAILED):
            return run
        if run.status == RunStatus.WAITING_APPROVAL:
            # Nothing to do until a human decides; approve()/deny() resume the run.
            return run

        agent = AGENTS.get(run.agent)
        if agent is None:
            return self._fail(session, run, f"Unknown agent: {run.agent}")

        try:
            while self._step_count(run) < settings.max_steps:
                turn = self._ask_model(session, run, agent)
                if turn is None:
                    return run  # failed inside _ask_model

                if isinstance(turn, FinalAnswer):
                    return self._complete(session, run, turn.answer)

                outcome = self._handle_tool(session, run, turn)
                if outcome == "suspended":
                    return run

            # Out of steps: answer with what has been gathered rather than nothing.
            return self._complete(
                session, run,
                "Stopped after reaching the step limit. Findings so far are in the transcript; "
                "the investigation did not reach a confident root cause.")
        except GatewayError as exc:
            return self._fail(session, run, str(exc))

    def approve(self, session: Session, run: AgentRun, call: ToolCall, decided_by: str) -> AgentRun:
        call.status = ToolCallStatus.APPROVED
        call.decided_by = decided_by
        call.decided_at = datetime.now(timezone.utc)

        result = self._execute(call.tool, call.args)
        call.result = result
        self._append_step(session, run, "tool",
                          wrap_tool_result(call.tool, result, settings.tool_result_chars))
        self._append_step(session, run, "system",
                          f"Human {decided_by} APPROVED {call.tool}.")
        run.status = RunStatus.RUNNING
        session.commit()
        return self.advance(session, run)

    def deny(self, session: Session, run: AgentRun, call: ToolCall, decided_by: str) -> AgentRun:
        call.status = ToolCallStatus.DENIED
        call.decided_by = decided_by
        call.decided_at = datetime.now(timezone.utc)
        call.result = "Denied by human reviewer."

        # The agent is told plainly and must continue — a denial is information, not a
        # dead end, and an analyst that stops being useful when told "no" is not useful.
        self._append_step(
            session, run, "system",
            f"Human {decided_by} DENIED {call.tool}. That action will not happen. "
            "Continue without it: finish your analysis and propose alternatives.")
        run.status = RunStatus.RUNNING
        session.commit()
        return self.advance(session, run)

    # ── internals ──────────────────────────────────────────────────────────

    def _ask_model(self, session: Session, run: AgentRun, agent: AgentDefinition):
        system_prompt = agent.system_prompt(self.registry)
        history, user_message = self._build_context(run)

        response = self.gateway.complete(system_prompt, user_message, history)
        content = response.get("content", "")
        self._append_step(session, run, "model", content,
                          model=response.get("model"), cost_usd=response.get("cost_usd"))
        try:
            return parse(content)
        except ProtocolError as exc:
            # Give the model exactly one corrective nudge before failing the run.
            if self._has_correction(run):
                self._fail(session, run, f"Model did not follow the tool protocol: {exc}")
                return None
            self._append_step(
                session, run, "system",
                "Your last reply was not valid JSON. Reply with ONE JSON object: "
                '{"tool":...,"args":{...}} or {"answer":"..."}.')
            session.commit()
            return self._ask_model(session, run, agent)

    def _handle_tool(self, session: Session, run: AgentRun, turn: ToolInvocation) -> str:
        sequence = len(run.tool_calls) + 1
        try:
            tool = self.registry.get(turn.tool)
        except UnknownTool:
            self._append_step(
                session, run, "system",
                f"No tool named '{turn.tool}'. Available: {', '.join(self.registry.names())}.")
            session.commit()
            return "continued"

        call = ToolCall(run_id=run.id, sequence=sequence, tool=tool.name, args=turn.args,
                        mutating=tool.mutating,
                        status=ToolCallStatus.PENDING_APPROVAL if tool.mutating
                        else ToolCallStatus.EXECUTED)
        # Attach to the relationship, not just the session: callers read run.tool_calls
        # in this same unit of work, before any refresh.
        run.tool_calls.append(call)
        session.add(call)

        if tool.mutating:
            # The gate. Enforced here by the runtime — not by the prompt, and not by the
            # model's judgement about whether this particular call is safe.
            run.status = RunStatus.WAITING_APPROVAL
            self._append_step(
                session, run, "system",
                f"Awaiting human approval for {tool.name}({turn.args}).")
            session.commit()
            log.info("Run %s suspended awaiting approval for %s", run.id, tool.name)
            return "suspended"

        result = self._execute(tool.name, turn.args)
        call.result = result
        self._append_step(session, run, "tool",
                          wrap_tool_result(tool.name, result, settings.tool_result_chars))
        session.commit()
        return "continued"

    def _execute(self, name: str, args: dict) -> str:
        try:
            return str(self.registry.get(name).handler(**(args or {})))
        except TypeError as exc:
            # Wrong/missing arguments: report back so the model can correct itself.
            return f"ERROR: bad arguments for {name}: {exc}"
        except Exception as exc:  # noqa: BLE001 - a tool failure must not kill the run
            log.warning("Tool %s failed: %s", name, exc)
            return f"ERROR: {name} failed: {exc}"

    def _build_context(self, run: AgentRun) -> tuple[list[dict], str]:
        """Transcript as gateway history, with the newest turn as the user message."""
        entries: list[dict] = [{"role": "user", "content": self._opening(run)}]
        for step in run.steps:
            role = "assistant" if step.role == "model" else "user"
            entries.append({"role": role, "content": step.content})

        latest = entries[-1]["content"] if len(entries) > 1 else entries[0]["content"]
        history = entries[:-1] if len(entries) > 1 else []
        return history, latest

    @staticmethod
    def _opening(run: AgentRun) -> str:
        context = run.context or {}
        lines = [f"Tenant: {run.tenant_id}"]
        if context.get("incident"):
            lines.append(f"Incident: {context['incident']}")
        lines.append(f"Question: {run.question}")
        return "\n".join(lines)

    @staticmethod
    def _step_count(run: AgentRun) -> int:
        return sum(1 for s in run.steps if s.role == "model")

    @staticmethod
    def _has_correction(run: AgentRun) -> bool:
        return any("not valid JSON" in s.content for s in run.steps if s.role == "system")

    def _append_step(self, session: Session, run: AgentRun, role: str, content: str,
                     model: str | None = None, cost_usd: float | None = None) -> None:
        step = RunStep(run_id=run.id, sequence=len(run.steps) + 1, role=role,
                       content=content, model=model, cost_usd=cost_usd)
        run.steps.append(step)
        session.add(step)
        run.updated_at = datetime.now(timezone.utc)

    def _complete(self, session: Session, run: AgentRun, answer: str) -> AgentRun:
        run.answer = answer
        run.status = RunStatus.COMPLETED
        run.updated_at = datetime.now(timezone.utc)
        session.commit()
        return run

    def _fail(self, session: Session, run: AgentRun, error: str) -> AgentRun:
        run.error = error
        run.status = RunStatus.FAILED
        run.updated_at = datetime.now(timezone.utc)
        session.commit()
        log.error("Run %s failed: %s", run.id, error)
        return run
