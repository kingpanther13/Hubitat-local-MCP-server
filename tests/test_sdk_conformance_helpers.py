"""Unit tests for the official-SDK live proof's observer-only trace helpers."""

import asyncio
from types import SimpleNamespace

import pytest
from sdk_conformance_helpers import (
    RELAY_LEG_CEILING_SECONDS,
    RequestTrace,
    summarize_mrtr_proof,
)


class _Headers(dict):
    def get(self, key, default=None):
        return super().get(key.lower(), default)


def _request(name: str = "hub_manage_rule_machine") -> SimpleNamespace:
    return SimpleNamespace(
        method="POST",
        headers=_Headers({
            "mcp-protocol-version": "2026-07-28",
            "mcp-method": "tools/call",
            "mcp-name": name,
            "authorization": "Bearer must-not-be-recorded",
        }),
        url="https://example.invalid/mcp?access_token=must-not-be-recorded",
        content=b'{"requestState":"must-not-be-recorded"}',
    )


def test_request_trace_times_tool_legs_without_secret_bearing_fields() -> None:
    ticks = iter((100.0, 108.0))
    trace = RequestTrace(clock=lambda: next(ticks))
    request = _request()

    async def observe() -> None:
        await trace.record_request(request)
        await trace.record_response(SimpleNamespace(request=request, status_code=200))

    asyncio.run(observe())

    legs = trace.tool_call_legs(0, "hub_manage_rule_machine")
    assert legs == [{
        "method": "POST",
        "mcp_protocol_version": "2026-07-28",
        "mcp_method": "tools/call",
        "mcp_name": "hub_manage_rule_machine",
        "status": 200,
        "started": 100.0,
        "ended": 108.0,
        "duration": 8.0,
    }]
    serialized = repr(legs)
    assert "access_token" not in serialized
    assert "Bearer" not in serialized
    assert "requestState" not in serialized


def test_mrtr_summary_counts_continuations_and_enforces_timing_contract() -> None:
    legs = [
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 8.1},
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 8.0},
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 4.2},
    ]

    summary = summarize_mrtr_proof(legs, logical_elapsed=20.7)

    assert summary == {
        "legs": 3,
        "continuation_rounds": 2,
        "logical_elapsed": 20.7,
        "max_leg_elapsed": 8.1,
        "sdk_round_limit": 10,
    }


@pytest.mark.parametrize(
    ("legs", "elapsed", "message"),
    [
        ([{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 4.0}] * 2,
         11.0, "at least 3"),
        ([{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0}] * 3,
         9.9, "exceed 10"),
        ([{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0},
          {"status": 200, "mcp_protocol_version": "2026-07-28",
           "duration": RELAY_LEG_CEILING_SECONDS},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0}],
         11.0, "relay ceiling"),
        ([{"status": 200, "mcp_protocol_version": "2025-06-18", "duration": 3.0}] * 3,
         11.0, "2026-07-28"),
        ([{"status": 504, "mcp_protocol_version": "2026-07-28", "duration": 3.0}] * 3,
         11.0, "2xx"),
    ],
)
def test_mrtr_summary_rejects_an_invalid_proof(legs, elapsed, message) -> None:
    with pytest.raises(AssertionError, match=message):
        summarize_mrtr_proof(legs, logical_elapsed=elapsed)
