"""The agent loop: investigation, error handling, and the step ceiling."""

from __future__ import annotations

from khukuri_agent.db import RunStatus, ToolCallStatus
from khukuri_agent.loop import AgentLoop

from fakes import ScriptedGateway


def test_runs_a_full_investigation_and_answers(session, registry, make_run):
    gateway = ScriptedGateway([
        '{"thought":"check health","tool":"get_service_health","args":{"tenant":"retail-shop"}}',
        '{"thought":"read the errors","tool":"get_error_logs","args":{"tenant":"retail-shop"}}',
        '{"answer":"Connection pool exhaustion: 33% of records are HikariPool timeouts."}',
    ])
    run = AgentLoop(registry, gateway).advance(session, make_run())

    assert run.status == RunStatus.COMPLETED
    assert "pool exhaustion" in run.answer
    assert [c.tool for c in run.tool_calls] == ["get_service_health", "get_error_logs"]
    assert all(c.status == ToolCallStatus.EXECUTED for c in run.tool_calls)


def test_tool_output_reaches_the_model_wrapped_as_untrusted(session, registry, make_run):
    gateway = ScriptedGateway([
        '{"tool":"get_error_logs","args":{"tenant":"retail-shop"}}',
        '{"answer":"done"}',
    ])
    AgentLoop(registry, gateway).advance(session, make_run())

    # The turn after the tool call must carry the framed result, not raw output.
    second_turn = gateway.calls[1]["user_message"]
    assert "<tool_result" in second_turn
    assert "untrusted data" in second_turn
    assert "HikariPool" in second_turn


def test_unknown_tool_is_reported_back_instead_of_failing_the_run(session, registry, make_run):
    gateway = ScriptedGateway([
        '{"tool":"delete_everything","args":{}}',
        '{"answer":"Understood, I used the available tools."}',
    ])
    run = AgentLoop(registry, gateway).advance(session, make_run())

    assert run.status == RunStatus.COMPLETED
    assert any("No tool named 'delete_everything'" in s.content for s in run.steps)
    # Nothing was recorded as a call, because nothing was callable.
    assert run.tool_calls == []


def test_a_failing_tool_is_reported_and_the_run_continues(session, registry, make_run):
    gateway = ScriptedGateway([
        '{"tool":"boom","args":{}}',
        '{"answer":"That tool failed; here is what I could establish."}',
    ])
    run = AgentLoop(registry, gateway).advance(session, make_run())

    assert run.status == RunStatus.COMPLETED
    assert "tool exploded" in run.tool_calls[0].result


def test_malformed_json_gets_one_correction_then_succeeds(session, registry, make_run):
    gateway = ScriptedGateway([
        "I'm going to look at the logs now, one moment",  # prose, but parseable as answer
    ])
    run = AgentLoop(registry, gateway).advance(session, make_run())
    # Prose is accepted as an answer rather than wasting a turn.
    assert run.status == RunStatus.COMPLETED


def test_step_ceiling_stops_a_runaway_agent(session, registry, make_run, monkeypatch):
    from khukuri_agent import loop as loop_module

    monkeypatch.setattr(loop_module.settings, "max_steps", 3)
    # A model that never finishes.
    gateway = ScriptedGateway(
        ['{"tool":"get_service_health","args":{"tenant":"retail-shop"}}'] * 10)
    run = AgentLoop(registry, gateway).advance(session, make_run())

    assert run.status == RunStatus.COMPLETED
    assert "step limit" in run.answer
    assert len(gateway.calls) == 3


def test_gateway_failure_fails_the_run_with_the_reason(session, registry, make_run):
    from khukuri_agent.gateway import GatewayError

    class BrokenGateway:
        def complete(self, *a, **k):
            raise GatewayError("Gateway budget exceeded for this key")

    run = AgentLoop(registry, BrokenGateway()).advance(session, make_run())

    assert run.status == RunStatus.FAILED
    assert "budget exceeded" in run.error


def test_unknown_agent_fails_cleanly(session, registry, make_run):
    run = AgentLoop(registry, ScriptedGateway([])).advance(
        session, make_run(agent="does-not-exist"))
    assert run.status == RunStatus.FAILED
    assert "Unknown agent" in run.error


def test_transcript_records_model_cost_for_auditing(session, registry, make_run):
    gateway = ScriptedGateway(['{"answer":"quick answer"}'])
    run = AgentLoop(registry, gateway).advance(session, make_run())

    model_steps = [s for s in run.steps if s.role == "model"]
    assert model_steps and model_steps[0].cost_usd == 0.001
    assert model_steps[0].model == "test-model"
