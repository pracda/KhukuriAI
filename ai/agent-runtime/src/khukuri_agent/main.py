"""HTTP surface for the agent runtime."""

from __future__ import annotations

import logging

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from .config import settings
from .db import AgentRun, RunStatus, ToolCall, ToolCallStatus, init_engine, session_factory
from .gateway import GatewayClient
from .loop import AgentLoop
from .tools.platform import build_registry

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

app = FastAPI(title="Khukuri Agent Runtime", version="0.1.0")

_registry = None
_loop = None


def get_loop() -> AgentLoop:
    global _registry, _loop
    if _loop is None:
        _registry = build_registry()
        _loop = AgentLoop(_registry, GatewayClient())
    return _loop


def get_session() -> Session:
    factory = session_factory()
    session = factory()
    try:
        yield session
    finally:
        session.close()


@app.on_event("startup")
def startup() -> None:
    init_engine()
    log.info("Agent runtime ready — gateway=%s incident=%s",
             settings.gateway_url, settings.incident_url)


class CreateRunRequest(BaseModel):
    agent: str = "ops-analyst"
    tenant: str
    question: str = Field(min_length=3)
    incident: str | None = None
    requested_by: str = "unknown"


class DecisionRequest(BaseModel):
    decided_by: str = "unknown"


def _run_dto(run: AgentRun) -> dict:
    pending = next((c for c in run.tool_calls
                    if c.status == ToolCallStatus.PENDING_APPROVAL), None)
    return {
        "id": run.id,
        "agent": run.agent,
        "tenant": run.tenant_id,
        "question": run.question,
        "status": run.status.value,
        "answer": run.answer,
        "error": run.error,
        "pendingApproval": None if pending is None else {
            "toolCallId": pending.id,
            "tool": pending.tool,
            "args": pending.args,
        },
        "steps": [
            {"sequence": s.sequence, "role": s.role, "content": s.content,
             "model": s.model, "costUsd": s.cost_usd}
            for s in run.steps
        ],
        "toolCalls": [
            {"id": c.id, "tool": c.tool, "args": c.args, "mutating": c.mutating,
             "status": c.status.value, "decidedBy": c.decided_by, "result": c.result}
            for c in run.tool_calls
        ],
    }


@app.post("/api/v1/runs", status_code=201)
def create_run(request: CreateRunRequest, session: Session = Depends(get_session),
               loop: AgentLoop = Depends(get_loop)) -> dict:
    run = AgentRun(
        agent=request.agent,
        tenant_id=request.tenant,
        question=request.question,
        context={"incident": request.incident} if request.incident else {},
        requested_by=request.requested_by,
    )
    session.add(run)
    session.commit()
    loop.advance(session, run)
    session.refresh(run)
    return _run_dto(run)


@app.get("/api/v1/runs/{run_id}")
def get_run(run_id: str, session: Session = Depends(get_session)) -> dict:
    run = session.get(AgentRun, run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="No such run")
    return _run_dto(run)


@app.get("/api/v1/runs")
def list_runs(session: Session = Depends(get_session), limit: int = 20) -> list[dict]:
    runs = (session.query(AgentRun)
            .order_by(AgentRun.created_at.desc())
            .limit(min(limit, 100)).all())
    return [{"id": r.id, "agent": r.agent, "tenant": r.tenant_id, "status": r.status.value,
             "question": r.question, "createdAt": r.created_at.isoformat()} for r in runs]


def _pending_call(session: Session, run_id: str, tool_call_id: str) -> tuple[AgentRun, ToolCall]:
    run = session.get(AgentRun, run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="No such run")
    call = session.get(ToolCall, tool_call_id)
    if call is None or call.run_id != run.id:
        raise HTTPException(status_code=404, detail="No such tool call on this run")
    if call.status != ToolCallStatus.PENDING_APPROVAL:
        # Re-deciding a settled call would rewrite history; refuse plainly.
        raise HTTPException(status_code=409,
                            detail=f"Tool call is already {call.status.value}")
    return run, call


@app.post("/api/v1/runs/{run_id}/tool-calls/{tool_call_id}/approve")
def approve(run_id: str, tool_call_id: str, request: DecisionRequest,
            session: Session = Depends(get_session),
            loop: AgentLoop = Depends(get_loop)) -> dict:
    run, call = _pending_call(session, run_id, tool_call_id)
    loop.approve(session, run, call, request.decided_by)
    session.refresh(run)
    return _run_dto(run)


@app.post("/api/v1/runs/{run_id}/tool-calls/{tool_call_id}/deny")
def deny(run_id: str, tool_call_id: str, request: DecisionRequest,
         session: Session = Depends(get_session),
         loop: AgentLoop = Depends(get_loop)) -> dict:
    run, call = _pending_call(session, run_id, tool_call_id)
    loop.deny(session, run, call, request.decided_by)
    session.refresh(run)
    return _run_dto(run)


@app.get("/api/v1/tools")
def list_tools(loop: AgentLoop = Depends(get_loop)) -> list[dict]:
    return [{"name": t.name, "description": t.description, "mutating": t.mutating,
             "args": t.args} for t in loop.registry.all()]


@app.get("/health")
def health() -> dict:
    return {"status": "UP"}
