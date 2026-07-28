"""Persistence for agent runs, transcript steps, and tool calls.

Runs are persisted rather than held in memory because the approval gate suspends them:
a run waiting on a human must survive a restart, and an audit trail that lives in a
process is not an audit trail.
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    create_engine,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker

from .config import settings


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _uuid() -> str:
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class RunStatus(str, enum.Enum):
    RUNNING = "RUNNING"
    WAITING_APPROVAL = "WAITING_APPROVAL"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ToolCallStatus(str, enum.Enum):
    EXECUTED = "EXECUTED"
    PENDING_APPROVAL = "PENDING_APPROVAL"
    APPROVED = "APPROVED"
    DENIED = "DENIED"
    FAILED = "FAILED"


class AgentRun(Base):
    __tablename__ = "agent_runs"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    agent: Mapped[str] = mapped_column(String(64))
    tenant_id: Mapped[str] = mapped_column(String(63))
    question: Mapped[str] = mapped_column(Text)
    context: Mapped[dict] = mapped_column(JSON, default=dict)
    status: Mapped[RunStatus] = mapped_column(Enum(RunStatus), default=RunStatus.RUNNING)
    answer: Mapped[str | None] = mapped_column(Text, nullable=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    requested_by: Mapped[str] = mapped_column(String(255), default="unknown")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    steps: Mapped[list["RunStep"]] = relationship(
        back_populates="run", cascade="all, delete-orphan", order_by="RunStep.sequence"
    )
    tool_calls: Mapped[list["ToolCall"]] = relationship(
        back_populates="run", cascade="all, delete-orphan", order_by="ToolCall.sequence"
    )


class RunStep(Base):
    """One entry in the transcript: a model turn, a tool result, or a gate decision."""

    __tablename__ = "agent_run_steps"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_runs.id"))
    sequence: Mapped[int] = mapped_column(Integer)
    role: Mapped[str] = mapped_column(String(32))  # model | tool | system
    content: Mapped[str] = mapped_column(Text)
    model: Mapped[str | None] = mapped_column(String(128), nullable=True)
    cost_usd: Mapped[float | None] = mapped_column(Float, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    run: Mapped[AgentRun] = relationship(back_populates="steps")


class ToolCall(Base):
    """A tool invocation and — for mutating tools — its approval record."""

    __tablename__ = "agent_tool_calls"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    run_id: Mapped[str] = mapped_column(ForeignKey("agent_runs.id"))
    sequence: Mapped[int] = mapped_column(Integer)
    tool: Mapped[str] = mapped_column(String(64))
    args: Mapped[dict] = mapped_column(JSON, default=dict)
    mutating: Mapped[bool] = mapped_column(default=False)
    status: Mapped[ToolCallStatus] = mapped_column(Enum(ToolCallStatus))
    result: Mapped[str | None] = mapped_column(Text, nullable=True)
    decided_by: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    run: Mapped[AgentRun] = relationship(back_populates="tool_calls")


_engine = None
_SessionLocal = None


def init_engine(url: str | None = None, create_schema: bool = True):
    """Build the engine and ensure tables exist.

    Uses metadata.create_all rather than a migration tool: the schema is young and this
    keeps the service self-contained. Alembic is the seam to add once it starts evolving.
    """
    global _engine, _SessionLocal
    url = url or settings.database_url
    connect_args: dict = {}
    _engine = create_engine(url, future=True, connect_args=connect_args)

    if url.startswith("postgresql") and settings.db_schema:
        from sqlalchemy import text

        with _engine.begin() as conn:
            conn.execute(text(f'CREATE SCHEMA IF NOT EXISTS "{settings.db_schema}"'))
            conn.execute(text(f'SET search_path TO "{settings.db_schema}"'))
        for table in Base.metadata.tables.values():
            table.schema = settings.db_schema

    if create_schema:
        Base.metadata.create_all(_engine)
    _SessionLocal = sessionmaker(bind=_engine, expire_on_commit=False, future=True)
    return _engine


def session_factory():
    if _SessionLocal is None:
        init_engine()
    return _SessionLocal
