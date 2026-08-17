"""Unit tests for the official-SDK live proof's observer-only trace helpers."""

import asyncio
from types import SimpleNamespace

import pytest
import sdk_conformance_helpers as helpers
from sdk_conformance_helpers import (
    RELAY_LEG_CEILING_SECONDS,
    RequestTrace,
    assert_mrtr_owner_rounds,
    cleanup_preserving_primary,
    extract_bps_acknowledgment_key,
    summarize_modern_posts,
    summarize_mrtr_proof,
)


class _Headers(dict):
    def get(self, key, default=None):
        return super().get(key.lower(), default)


def test_bps_key_extraction_requires_the_documented_guide_line() -> None:
    guide = """# Best-Practice Reference

Acknowledgment key: bps-ack-299
"""

    assert extract_bps_acknowledgment_key(guide) == "bps-ack-299"

    with pytest.raises(AssertionError, match="acknowledgment key"):
        extract_bps_acknowledgment_key("bestPracticeKey is required")


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
        extensions={},
        content=b'{"jsonrpc":"2.0","method":"tools/call","params":{"requestState":"must-not-be-recorded"}}',
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
        "has_request_state": True,
        "status": 200,
        "started": 100.0,
        "ended": 108.0,
        "duration": 8.0,
    }]
    serialized = repr(legs)
    assert "access_token" not in serialized
    assert "Bearer" not in serialized
    assert "requestState" not in serialized


def test_request_trace_uses_secret_free_leg_ids_and_rejects_unknown_response() -> None:
    trace = RequestTrace(clock=lambda: 100.0)
    request = _request()
    request.extensions = {}

    asyncio.run(trace.record_request(request))

    trace_id = request.extensions[RequestTrace.TRACE_ID_EXTENSION]
    assert trace._pending[trace_id] == 0
    assert all(value is not request for value in trace._pending.values())
    unknown = _request()
    unknown.extensions = {}
    with pytest.raises(AssertionError, match="unrecorded request leg"):
        asyncio.run(trace.record_response(SimpleNamespace(
            request=unknown, status_code=200,
        )))


def test_cleanup_preserves_a_primary_failure_but_stays_strict_after_success() -> None:
    cleanup_error = RuntimeError("cleanup failed")

    async def fail_cleanup() -> None:
        raise cleanup_error

    primary_error = AssertionError("proof failed")
    assert asyncio.run(cleanup_preserving_primary(
        fail_cleanup, primary_error,
    )) is cleanup_error

    with pytest.raises(RuntimeError, match="cleanup failed"):
        asyncio.run(cleanup_preserving_primary(fail_cleanup, None))


def test_request_trace_marks_an_initial_tool_leg_without_retaining_its_body() -> None:
    request = _request()
    request.content = b'{"jsonrpc":"2.0","method":"tools/call","params":{"arguments":{"secret":"value"}}}'

    assert RequestTrace._fields(request) == {
        "method": "POST",
        "mcp_protocol_version": "2026-07-28",
        "mcp_method": "tools/call",
        "mcp_name": "hub_manage_rule_machine",
        "has_request_state": False,
    }
    assert "secret" not in repr(RequestTrace._fields(request))


def test_request_trace_treats_only_an_unread_stream_as_state_unknown() -> None:
    request = _request()

    class UnreadRequest:
        method = request.method
        headers = request.headers

        @property
        def content(self):
            raise helpers.RequestNotRead("stream body was not buffered")

    assert RequestTrace._fields(UnreadRequest())["has_request_state"] is None

    class BrokenRequest(UnreadRequest):
        @property
        def content(self):
            raise RuntimeError("observer defect")

    with pytest.raises(RuntimeError, match="observer defect"):
        RequestTrace._fields(BrokenRequest())


def test_mrtr_summary_counts_continuations_and_enforces_timing_contract() -> None:
    legs = [
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 8.1,
         "has_request_state": False},
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 8.0,
         "has_request_state": True},
        {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 4.2,
         "has_request_state": True},
    ]

    summary = summarize_mrtr_proof(legs, logical_elapsed=20.7)

    assert summary == {
        "legs": 3,
        "continuation_rounds": 2,
        "logical_elapsed": 20.7,
        "max_leg_elapsed": 8.1,
        "sdk_round_limit": 10,
    }


def test_owner_rounds_allow_detached_worker_coordination_responses() -> None:
    assert assert_mrtr_owner_rounds(1, 3) == 2

    with pytest.raises(AssertionError, match="owner slices"):
        assert_mrtr_owner_rounds(0, 3)
    with pytest.raises(AssertionError, match="owner slices"):
        assert_mrtr_owner_rounds(3, 3)
    with pytest.raises(AssertionError, match="owner slices"):
        assert_mrtr_owner_rounds(4, 3)


def test_mrtr_summary_absorbs_one_relay_dropped_leg() -> None:
    # The observed incident this exists for: the server answers well under the ceiling and
    # the relay drops one leg at ~10s, which the SDK replays. Counting that leg's duration
    # against the ceiling -- or rejecting it as a non-2xx -- fails the required gate for the
    # transport doing exactly what MRTR is built to absorb.
    summary = summarize_mrtr_proof(
        [{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
          "has_request_state": False},
         {"status": 504, "mcp_protocol_version": "2026-07-28", "duration": 10.1,
          "has_request_state": True},
         {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 4.0,
          "has_request_state": True},
         {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 4.5,
          "has_request_state": True}],
        logical_elapsed=21.6,
    )

    assert summary["legs"] == 4
    # The ceiling reports the slowest ANSWERED leg, not the relay's timeout.
    assert summary["max_leg_elapsed"] == 4.5


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
        # A single relay drop is absorbed (the SDK replays it), but a proof where EVERY leg
        # was dropped never reached the server and proves nothing.
        ([{"status": 504, "mcp_protocol_version": "2026-07-28", "duration": 3.0}] * 3,
         11.0, "dropped by the relay"),
        # Mixed None/str and None/int diagnostic sets: an absent header and an unanswered
        # leg must still report the contract failure, not a sort TypeError.
        ([{"status": None, "mcp_protocol_version": None, "duration": 3.0},
          {"status": 504, "mcp_protocol_version": "2025-06-18", "duration": 3.0},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0}],
         11.0, "2026-07-28"),
        ([{"status": None, "mcp_protocol_version": "2026-07-28", "duration": 3.0},
          {"status": 504, "mcp_protocol_version": "2026-07-28", "duration": 3.0},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0}],
         11.0, "2xx"),
        ([{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": False},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": False},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": True}],
         11.0, "requestState"),
        ([{"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": None},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": True},
          {"status": 200, "mcp_protocol_version": "2026-07-28", "duration": 3.0,
           "has_request_state": True}],
         11.0, "requestState"),
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

    assert summarize_modern_posts(posts) == {
        "posts": 3, "statuses": [200], "capacity_excused": [],
    }

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


def test_modern_post_summary_excuses_only_pre_boundary_served_failures() -> None:
    ok = {"mcp_protocol_version": "2026-07-28", "mcp_method": "tools/call",
          "mcp_name": "hub_get_info", "status": 200}
    relay_504 = {**ok, "status": 504}
    unanswered = {**ok, "status": None}

    # Without a boundary, a 504 or a lost response fails the served contract.
    with pytest.raises(AssertionError, match="serve every"):
        summarize_modern_posts([relay_504, ok])
    with pytest.raises(AssertionError, match="came back"):
        summarize_modern_posts([unanswered, ok])

    # A capacity-recovery boundary excuses exactly the legs before it, and the
    # excused statuses are reported rather than dropped.
    summary = summarize_modern_posts([relay_504, unanswered, ok, ok], served_since=2)
    assert summary == {"posts": 4, "statuses": [200], "capacity_excused": [504, None]}

    # A post-boundary failure still fails: the recovered hub must serve every leg.
    with pytest.raises(AssertionError, match="serve every"):
        summarize_modern_posts([relay_504, ok, relay_504], served_since=1)

    # The header contract stays global -- a pre-boundary leg on a legacy protocol
    # version is a client-side fact no capacity event can excuse.
    with pytest.raises(AssertionError, match="2026-07-28"):
        summarize_modern_posts(
            [{**relay_504, "mcp_protocol_version": "2025-06-18"}, ok], served_since=1,
        )

    # The boundary must leave at least one served leg and index into the trace.
    with pytest.raises(AssertionError, match="after the capacity-recovery boundary"):
        summarize_modern_posts([relay_504], served_since=1)
    with pytest.raises(AssertionError, match="must index"):
        summarize_modern_posts([ok], served_since=2)


def test_request_trace_capacity_recovery_boundary_counts_posts_only() -> None:
    trace = RequestTrace()
    assert trace.capacity_recovery_boundary == 0
    # The boundary indexes into posts(), so non-POST legs (SSE GETs, session
    # DELETEs) recorded before the bounce must not shift it.
    trace.legs = [
        {"method": "POST", "mcp_protocol_version": "2026-07-28", "status": 504},
        {"method": "GET", "mcp_protocol_version": None, "status": 405},
        {"method": "POST", "mcp_protocol_version": "2026-07-28", "status": 200},
        {"method": "DELETE", "mcp_protocol_version": None, "status": 200},
    ]

    assert trace.mark_capacity_recovery() == 2
    assert trace.capacity_recovery_boundary == 2
    assert trace.capacity_recovery_boundary == len(trace.posts())


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
