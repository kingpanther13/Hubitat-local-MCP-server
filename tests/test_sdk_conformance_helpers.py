"""Unit tests for the official-SDK live proof's observer-only trace helpers."""

import asyncio
from types import SimpleNamespace

import pytest
import sdk_conformance_helpers as helpers
from sdk_conformance_helpers import (
    RELAY_LEG_CEILING_SECONDS,
    RequestTrace,
    summarize_modern_posts,
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


EXPECTED_LOG_MESSAGES = [f"proof message {index}" for index in range(1, 7)]


def _rule_config(messages=EXPECTED_LOG_MESSAGES):
    settings = {}
    for index, message in enumerate(messages, 1):
        settings[f"actType.{index}"] = "messageActs"
        settings[f"actSubType.{index}"] = "getLogMsg"
        settings[f"logmsg.{index}"] = message
    return {"success": True, "settings": settings}


def test_exact_rule_log_messages_accepts_only_the_six_persisted_action_rows() -> None:
    actual = helpers.assert_exact_rule_log_messages(
        _rule_config(), EXPECTED_LOG_MESSAGES, operation="regular proof readback",
    )

    assert actual == EXPECTED_LOG_MESSAGES


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda cfg: cfg["settings"].__setitem__("logmsg.4", "wrong"), "exact messages"),
        (lambda cfg: cfg["settings"].__setitem__("logmsg.4", "proof message 3"), "distinct"),
        (lambda cfg: cfg["settings"].pop("logmsg.4"), "log-message indices"),
        (lambda cfg: cfg["settings"].update({
            "actType.7": "messageActs",
            "actSubType.7": "getLogMsg",
            "logmsg.7": "extra",
        }), "exactly 6 action rows"),
    ],
)
def test_exact_rule_log_messages_rejects_wrong_duplicate_missing_or_extra_values(
    mutate, message,
) -> None:
    config = _rule_config()
    mutate(config)

    with pytest.raises(AssertionError, match=message):
        helpers.assert_exact_rule_log_messages(
            config, EXPECTED_LOG_MESSAGES, operation="SDK proof readback",
        )


def test_request_trace_returns_every_observed_post_for_modern_contract_checks() -> None:
    trace = RequestTrace()
    trace.legs = [
        {"method": "POST", "mcp_protocol_version": "2026-07-28", "status": 200},
        {"method": "GET", "mcp_protocol_version": None, "status": 405},
        {"method": "POST", "mcp_protocol_version": None, "status": 400},
    ]

    assert trace.posts() == [trace.legs[0], trace.legs[2]]


def test_modern_post_summary_requires_complete_modern_routing_and_responses() -> None:
    posts = [
        {"mcp_protocol_version": "2026-07-28", "mcp_method": "tools/list",
         "mcp_name": None, "status": 200},
        {"mcp_protocol_version": "2026-07-28", "mcp_method": "resources/read",
         "mcp_name": "hubitat://context", "status": 200},
        {"mcp_protocol_version": "2026-07-28", "mcp_method": "tools/call",
         "mcp_name": "hub_get_info", "status": 200},
    ]

    assert summarize_modern_posts(posts) == {"posts": 3, "statuses": [200]}

    invalid = [
        ({**posts[0], "mcp_protocol_version": "2025-06-18"}, "2026-07-28"),
        ({**posts[0], "mcp_method": None}, "Mcp-Method"),
        ({**posts[2], "mcp_name": None}, "Mcp-Name"),
        ({**posts[0], "status": None}, "came back"),
        ({**posts[0], "status": 400}, "serve every"),
    ]
    for bad_post, message in invalid:
        with pytest.raises(AssertionError, match=message):
            summarize_modern_posts([bad_post])


def test_exact_fixture_lookup_settles_until_a_delayed_rule_appears() -> None:
    replies = iter([
        [{"id": 1, "label": "BAT_E2E_SDK_MRTR_exact_backup"}],
        [],
        [{"id": 42, "label": "BAT_E2E_SDK_MRTR_exact"}],
    ])
    sleeps = []

    async def list_rules():
        return next(replies)

    async def sleep(seconds):
        sleeps.append(seconds)

    result = asyncio.run(helpers.find_exact_fixture_id_with_settle(
        list_rules,
        "BAT_E2E_SDK_MRTR_exact",
        attempts=4,
        delay_seconds=0.25,
        sleep=sleep,
    ))

    assert result == "42"
    assert sleeps == [0.25, 0.25]


def test_exact_fixture_lookup_refuses_ambiguous_matches_without_sleeping() -> None:
    sleeps = []

    async def list_rules():
        return [
            {"id": 42, "label": "BAT_E2E_SDK_MRTR_exact"},
            {"appId": 43, "name": "BAT_E2E_SDK_MRTR_exact"},
        ]

    async def sleep(seconds):
        sleeps.append(seconds)

    with pytest.raises(AssertionError, match="ambiguous cleanup"):
        asyncio.run(helpers.find_exact_fixture_id_with_settle(
            list_rules,
            "BAT_E2E_SDK_MRTR_exact",
            attempts=4,
            delay_seconds=0.25,
            sleep=sleep,
        ))

    assert sleeps == []
