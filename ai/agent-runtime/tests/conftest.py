from __future__ import annotations

import pytest

from khukuri_agent import db as db_module
from khukuri_agent.db import AgentRun, Base
from khukuri_agent.tools.registry import Tool, ToolRegistry
from fakes import ScriptedGateway  # noqa: F401  (re-exported for tests)


@pytest.fixture()
def session():
    for table in Base.metadata.tables.values():
        table.schema = None
    engine = db_module.init_engine("sqlite://", create_schema=True)
    factory = db_module.session_factory()
    with factory() as s:
        yield s
    engine.dispose()


@pytest.fixture()
def registry():
    reg = ToolRegistry()
    reg.register(Tool(
        name="get_service_health",
        description="Error rate per service.",
        args={"tenant": "str"},
        handler=lambda **kwargs: '[{"service":"retail-shop","errors":20,"total":60,'
                                 '"errorRate":0.333}]',
    ))
    reg.register(Tool(
        name="get_error_logs",
        description="Top errors.",
        args={"tenant": "str"},
        handler=lambda **kwargs: '[{"service":"retail-shop","count":20,'
                                 '"sample":"HikariPool-1 Connection is not available"}]',
    ))
    reg.register(Tool(
        name="boom",
        description="Always fails.",
        args={},
        handler=lambda **kwargs: (_ for _ in ()).throw(RuntimeError("tool exploded")),
    ))
    reg.register(Tool(
        name="restart_service",
        description="Restart a service.",
        args={"service": "str"},
        mutating=True,
        handler=lambda **kwargs: "Restart requested.",
    ))
    return reg


@pytest.fixture()
def make_run(session):
    def _make(question="Why are sales failing?", tenant="retail-shop", agent="ops-analyst"):
        run = AgentRun(agent=agent, tenant_id=tenant, question=question, context={})
        session.add(run)
        session.commit()
        return run

    return _make
