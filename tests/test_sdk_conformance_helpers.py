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


@pytest.mark.parametrize(("configured", "expected"), [(None, 6), ("6", 6), ("36", 36), ("120", 120)])
def test_mrtr_workload_keeps_default_and_requires_each_distinct_real_mutation(configured, expected):
    environment = {} if configured is None else {"E2E_MRTR_ACTION_COUNT": configured}
    actions = helpers.mrtr_log_actions("timing proof", environment)
    assert len(actions) == expected
    assert all(action["capability"] == "log" for action in actions)
    assert [action["message"] for action in actions] == [f"timing proof {index}" for index in range(1, expected + 1)]
    assert len({action["message"] for action in actions}) == expected


@pytest.mark.parametrize("configured", ["", "0", "5", "121", "-1", "+6", "6.0", "word", " 6", "6 ", None, True])
def test_mrtr_workload_rejects_invalid_or_unbounded_configuration(configured):
    with pytest.raises(ValueError, match="E2E_MRTR_ACTION_COUNT"):
        helpers.mrtr_log_actions("timing proof", {"E2E_MRTR_ACTION_COUNT": configured})


@pytest.mark.parametrize(("configured", "expected"), [(None, 16), ("4", 4), ("72", 72), ("120", 120)])
def test_write_cap_workload_creates_every_distinct_real_variable(configured, expected):
    environment = {} if configured is None else {"E2E_WRITE_CAP_VARIABLE_COUNT": configured}
    items = helpers.write_cap_variable_items("BAT_E2E_WriteCap_run", environment)
    assert items == [{"name": f"BAT_E2E_WriteCap_run_{index}", "type": "String", "value": "held"}
                     for index in range(expected)]
    assert len({item["name"] for item in items}) == expected


@pytest.mark.parametrize("configured", ["", "0", "3", "121", "-1", "+4", "4.0", "word", " 4", "4 ", None, True])
def test_write_cap_workload_rejects_invalid_or_unbounded_configuration(configured):
    with pytest.raises(ValueError, match="E2E_WRITE_CAP_VARIABLE_COUNT"):
        helpers.write_cap_variable_items("BAT_E2E_WriteCap_run", {"E2E_WRITE_CAP_VARIABLE_COUNT": configured})


def test_write_cap_holder_retry_requires_refusal_and_complete_absence_readback():
    refused = {"status": "too_many_writes_in_flight"}
    names = ["fixture_0", "fixture_1"]
    assert helpers.write_cap_holder_retry_blocker(refused, {"hubVariables": [{"name": "other"}]}, names) is None
    for inventory in (
        {"hubVariables": [{"name": "fixture_1"}]},
        {"hubVariables": [{"name": "other"}], "nextCursor": "more"},
        {"hubVariables": [], "success": False},
        {"hubVariables": [{"error": "unreadable"}]},
        {"hubVariables": None}, {}, None,
    ):
        assert helpers.write_cap_holder_retry_blocker(refused, inventory, names)
    for payload in ({"status": "failed"}, {}, None):
        assert helpers.write_cap_holder_retry_blocker(payload, {"hubVariables": []}, names)
    assert helpers.write_cap_holder_retry_blocker(refused, {"hubVariables": []}, [])


def test_write_cap_holder_retry_blocker_names_the_declining_condition():
    refused = {"status": "too_many_writes_in_flight"}
    names = ["fixture_0"]
    assert helpers.write_cap_holder_retry_blocker(refused, {"hubVariables": []}, names) is None
    for inventory, reason in (
        ({"hubVariables": [], "nextCursor": "more"}, "nextCursor"),
        ({"hubVariables": [], "success": False}, "success:false"),
        ({"hubVariables": ["fixture_0"]}, "without a name"),
        ({"hubVariables": [{"name": "fixture_0"}]}, "present on the hub"),
    ):
        assert reason in helpers.write_cap_holder_retry_blocker(refused, inventory, names)


def test_expanded_mrtr_workload_does_not_relax_elapsed_or_continuation_requirements():
    assert len(helpers.mrtr_log_actions("timing proof", {"E2E_MRTR_ACTION_COUNT": "36"})) == 36
    one_leg = [{"duration": 2.325, "status": 200, "has_request_state": False,
                "mcp_protocol_version": "2026-07-28"}]
    with pytest.raises(AssertionError, match="at least 3"):
        summarize_mrtr_proof(one_leg, 2.325)
    multiple_legs = [{"duration": 3.0, "status": 200, "has_request_state": index > 0,
                      "mcp_protocol_version": "2026-07-28"} for index in range(3)]
    with pytest.raises(AssertionError, match="exceed 10"):
        summarize_mrtr_proof(multiple_legs, 9.9)


class _Headers(dict):
    def get(self, key, default=None):
        return super().get(key.lower(), default)


def test_bps_key_extraction_requires_the_documented_guide_line() -> None:
    guide = """Acknowledgment key: I-HAVE-READ-THE-GUIDE-set_rule_reference-3fa29b1c
Pass this exact value as the bestPracticeKey argument on the write tools this section covers.

## Native Rule Machine
"""

    assert extract_bps_acknowledgment_key(guide) == "I-HAVE-READ-THE-GUIDE-set_rule_reference-3fa29b1c"

    for unkeyed in (
        "bestPracticeKey is required",
        "Acknowledgment key: bps-ack-299",
        "Acknowledgment key (builtin_app_tools_crud): I-HAVE-READ-THE-GUIDE-builtin_app_tools_crud-0a1b2c3d",
    ):
        with pytest.raises(AssertionError, match="acknowledgment key"):
            extract_bps_acknowledgment_key(unkeyed)


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
            raise helpers.RequestNotRead()

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


@pytest.mark.parametrize("environ", [{}, {"E2E_MRTR_ACTION_COUNT": "36"}, {"E2E_MRTR_ACTION_COUNT": "120"}])
def test_exact_rule_log_messages_accepts_every_configured_persisted_action(environ) -> None:
    expected = [action["message"] for action in helpers.mrtr_log_actions("proof message", environ)]
    actual = helpers.assert_exact_rule_log_messages(
        _rule_config(expected), expected, operation="regular proof readback",
    )

    assert actual == expected


@pytest.mark.parametrize("count", [0, 5, 121])
def test_exact_rule_log_messages_rejects_unbounded_expected_workloads(count) -> None:
    expected = [f"proof message {index}" for index in range(count)]
    with pytest.raises(AssertionError, match="6 through 120"):
        helpers.assert_exact_rule_log_messages(_rule_config(expected), expected, operation="proof readback")


@pytest.mark.parametrize("invalid_message", ["", "   ", None])
def test_exact_rule_log_messages_rejects_empty_or_nontext_expected_messages(invalid_message) -> None:
    expected = [*EXPECTED_LOG_MESSAGES[:-1], invalid_message]
    with pytest.raises(AssertionError, match="nonempty text"):
        helpers.assert_exact_rule_log_messages(_rule_config(expected), expected, operation="proof readback")


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda cfg: [cfg["settings"].pop(f"{prefix}.36")
                      for prefix in ("actType", "actSubType", "logmsg")], "exactly 36 action rows"),
        (lambda cfg: cfg["settings"].__setitem__("logmsg.36", "corrupted"), "exact messages"),
        (lambda cfg: cfg["settings"].__setitem__("logmsg.36", "proof message 35"), "distinct"),
        (lambda cfg: cfg["settings"].pop("actSubType.36"), "action-subtype indices"),
        (lambda cfg: cfg["settings"].pop("logmsg.36"), "log-message indices"),
        (lambda cfg: cfg["settings"].__setitem__("actType.36", "otherActs"), "non-log action type"),
        (lambda cfg: cfg["settings"].__setitem__("actSubType.36", "otherMsg"), "non-log action subtype"),
    ],
)
def test_exact_rule_log_messages_rejects_truncated_or_corrupted_configured_workload(mutate, message) -> None:
    expected = [action["message"] for action in helpers.mrtr_log_actions(
        "proof message", {"E2E_MRTR_ACTION_COUNT": "36"},
    )]
    config = _rule_config(expected)
    mutate(config)
    with pytest.raises(AssertionError, match=message):
        helpers.assert_exact_rule_log_messages(config, expected, operation="SDK proof readback")


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


@pytest.mark.parametrize(("count", "wait_count", "condition_count", "events_per_wait"), [
    (6, 40, 54, 4), (36, 40, 24, 4), (48, 40, 12, 4), (59, 40, 1, 4),
    (60, 39, 1, 8), (72, 27, 1, 8), (99, 0, 1, 8),
])
def test_native_mrtr_batch_uses_direct_native_rows_and_preserves_each_log(count, wait_count, condition_count, events_per_wait):
    changes, messages = helpers.native_mrtr_changes(
        "SDK proof", "588", {"E2E_MRTR_ACTION_COUNT": str(count)},
    )
    assert len(changes) == 100
    conditions, waits, actions = changes[:condition_count], changes[condition_count:-count], changes[-count:]
    assert len(waits) == wait_count
    assert all(row["value"]["subtype"] == "getWaitEvents" for row in waits)
    assert all(len(row["value"]["events"]) == events_per_wait for row in waits)
    thresholds = [event["fields"][2]["value"] for row in waits for event in row["value"]["events"]]
    assert thresholds == list(range(1000, 1000 + wait_count * events_per_wait))
    assert len(conditions) == condition_count
    assert all(row["part"] == "condition" for row in conditions)
    assert all(row["value"]["capability"] == "Temperature" for row in conditions)
    assert [row["value"]["fields"][2]["value"] for row in conditions] == list(range(60, 60 + condition_count))
    assert all(row["value"]["fields"][0] == {
        "name": "rDev_#", "type": "capability.temperatureMeasurement", "value": ["588"],
    } for row in conditions)
    assert messages == [f"SDK proof {index}" for index in range(1, count + 1)]
    assert all(row["part"] == "action" and row["value"]["type"] == "messageActs"
               and row["value"]["subtype"] == "getLogMsg" for row in actions)
    assert [row["value"]["fields"][0]["value"] for row in actions] == messages


@pytest.mark.parametrize("device_id", ["", "-1", "588x", None, 588])
def test_native_mrtr_batch_rejects_invalid_fixture_ids(device_id):
    with pytest.raises(ValueError, match="fixture ID must be decimal"):
        helpers.native_mrtr_changes("SDK proof", device_id, {})


def test_native_mrtr_batch_rejects_over_limit_instead_of_silently_truncating():
    with pytest.raises(ValueError, match="at most 99"):
        helpers.native_mrtr_changes("SDK proof", "588", {"E2E_MRTR_ACTION_COUNT": "120"})


def _native_condition_readback():
    changes, _ = helpers.native_mrtr_changes("SDK proof", "588", {})
    settings, typed = {}, []
    for index, row in enumerate(changes[:54], 1):
        fields = [{"name": "rCapab_#", "type": "enum", "value": row["value"]["capability"]},
                  *row["value"]["fields"]]
        for field in fields:
            name = field["name"].replace("#", str(index))
            device = field["type"].startswith("capability.")
            settings[name] = dict.fromkeys(field["value"], "Temperature fixture") if device else field["value"]
            typed.append({"name": name, "type": field["type"], "value": field["value"],
                          "device_ids": field["value"] if device else None})
    return {"settings": settings}, {"conditions": {str(i): "native condition" for i in range(1, 55)},
                                     "settings": typed}, changes


def test_native_condition_readback_verifies_every_persisted_row():
    config, document, changes = _native_condition_readback()
    helpers.assert_exact_native_conditions(config, document, changes)


@pytest.mark.parametrize("corruption", ["missing", "duplicate", "device", "comparator", "threshold", "order", "type", "document"])
def test_native_condition_readback_rejects_lost_replayed_or_changed_rows(corruption):
    config, document, changes = _native_condition_readback()
    settings = config["settings"]
    if corruption == "missing":
        del settings["rDev_54"]
    elif corruption == "duplicate":
        for prefix in ("rCapab_", "rDev_", "RelrDev_", "state_"):
            settings[prefix + "55"] = settings[prefix + "54"]
        document["conditions"]["55"] = "replayed condition"
    elif corruption == "device":
        settings["rDev_54"] = {"589": "Wrong device"}
    elif corruption == "comparator":
        settings["RelrDev_54"] = "<"
    elif corruption == "threshold":
        settings["state_54"] = 60
    elif corruption == "order":
        settings["state_1"], settings["state_2"] = settings["state_2"], settings["state_1"]
    elif corruption == "type":
        settings["rCapab_54"] = "Humidity"
    else:
        del document["conditions"]["54"]
    with pytest.raises(AssertionError):
        helpers.assert_exact_native_conditions(config, document, changes)


def _native_action_readback():
    changes, messages = helpers.native_mrtr_changes("SDK proof", "588", {})
    settings, typed, actions = {}, [], []
    event_index = 0
    for index, change in enumerate((row for row in changes if row["part"] == "action"), 1):
        requested = change["value"]
        settings[f"actType.{index}"] = requested["type"]
        settings[f"actSubType.{index}"] = requested["subtype"]
        value = {"method": requested["subtype"]}
        if "events" in requested:
            value["wait"] = []
            for event in requested["events"]:
                event_index += 1
                value["wait"].append(event_index)
                fields = [{"name": "tCapab#", "type": "enum", "value": "Temperature"}, *event["fields"]]
                for field in fields:
                    name = field["name"].replace("#", "-" + str(event_index))
                    settings[name] = (dict.fromkeys(field["value"], "Temperature fixture")
                                      if field["type"].startswith("capability.") else field["value"])
                    typed.append({"name": name, "type": field["type"], "value": field["value"],
                                  "device_ids": field["value"] if field["type"].startswith("capability.") else None})
        else:
            settings[f"logmsg.{index}"] = requested["fields"][0]["value"]
        actions.append({"id": str(index), "value": value})
    return {"settings": settings}, {"actions": actions, "settings": typed}, changes, messages


def test_native_action_readback_verifies_all_wait_events_and_logs():
    helpers.assert_exact_native_actions(*_native_action_readback())


@pytest.mark.parametrize("corruption", ["lost_action", "action_order", "event_id", "missing_event", "type", "device", "comparator", "threshold", "orphan", "duplicate_log"])
def test_native_action_readback_rejects_lost_replayed_or_corrupted_work(corruption):
    config, document, changes, messages = _native_action_readback()
    if corruption == "lost_action":
        document["actions"].pop()
    elif corruption == "action_order":
        document["actions"][0], document["actions"][1] = document["actions"][1], document["actions"][0]
    elif corruption == "event_id":
        document["actions"][1]["value"]["wait"][0] = 1
    elif corruption == "missing_event":
        document["actions"][0]["value"]["wait"].pop()
    elif corruption == "type":
        document["settings"][0]["type"] = "text"
    elif corruption == "device":
        config["settings"]["tDev-160"] = {"589": "Wrong device"}
    elif corruption == "comparator":
        config["settings"]["ReltDev-160"] = "<"
    elif corruption == "threshold":
        config["settings"]["tstate-160"] = 1000
    elif corruption == "orphan":
        config["settings"]["tCapab-161"] = "Temperature"
    else:
        config["settings"]["logmsg.46"] = config["settings"]["logmsg.45"]
    with pytest.raises(AssertionError):
        helpers.assert_exact_native_actions(config, document, changes, messages)


@pytest.mark.parametrize("corruption", ["typed_device", "typed_type", "raw_list"])
def test_native_condition_readback_requires_native_map_and_typed_identity(corruption):
    config, document, changes = _native_condition_readback()
    device = next(row for row in document["settings"] if row["name"] == "rDev_54")
    if corruption == "typed_device":
        device["device_ids"] = ["589"]
    elif corruption == "typed_type":
        device["type"] = "enum"
    else:
        config["settings"]["rDev_54"] = ["588"]
    with pytest.raises(AssertionError):
        helpers.assert_exact_native_conditions(config, document, changes)


def test_native_action_readback_requires_native_device_map():
    config, document, changes, messages = _native_action_readback()
    config["settings"]["tDev-160"] = ["588"]
    with pytest.raises(AssertionError, match="Raw wait device differs"):
        helpers.assert_exact_native_actions(config, document, changes, messages)



def _section_page(section, cursor, limit):
    offset = {"": 0, "opaque-part-two": 17, "opaque-part-three": 34}[cursor]
    end = min(51, offset + 17)
    if section == "conditions":
        rows = {str(i): f"condition {i}" for i in sorted(range(51), key=str)[offset:end]}
    elif section == "actions":
        rows = [{"id": str(i), "value": {"method": "getLogMsg"}} for i in range(offset, end)]
    else:
        rows = [{"name": f"setting{i:02}", "type": "enum", "value": str(i)} for i in range(offset, end)]
    following = {0: "opaque-part-two", 17: "opaque-part-three", 34: None}[offset]
    return {section: rows, "nextCursor": following, "page": {"offset": offset, "limit": limit,
            "total": 51, "revision": section + "-stable"}}


def test_native_paged_readback_accepts_byte_bounded_pages_and_opaque_cursors():
    calls = []
    async def fetch(section, cursor, limit):
        calls.append((section, cursor, limit))
        return _section_page(section, cursor, limit)
    document = asyncio.run(helpers.read_native_document_sections(fetch))
    assert calls == [(section, cursor, 50) for section in ("conditions", "actions", "settings")
                     for cursor in ("", "opaque-part-two", "opaque-part-three")]
    assert list(document["conditions"]) == sorted(map(str, range(51)))
    assert [row["id"] for row in document["actions"]] == list(map(str, range(51)))
    assert [row["name"] for row in document["settings"]] == [f"setting{i:02}" for i in range(51)]


@pytest.mark.parametrize("corruption", ["revision", "total", "offset", "stalled", "cycle", "skipped", "early_end", "duplicate", "order", "missing_identity", "missing_cursor", "empty_progress"])
def test_native_paged_readback_rejects_torn_missing_replayed_or_disordered_pages(corruption):
    async def fetch(section, cursor, limit):
        result = _section_page(section, cursor, limit)
        if section == "settings":
            if cursor == "opaque-part-two":
                if corruption == "revision":
                    result["page"]["revision"] = "changed"
                elif corruption == "total":
                    result["page"]["total"] = 52
                elif corruption == "offset":
                    result["page"]["offset"] = 16
                elif corruption == "duplicate":
                    result["settings"][0]["name"] = "setting00"
                elif corruption == "missing_identity":
                    del result["settings"][0]["name"]
                elif corruption == "missing_cursor":
                    del result["nextCursor"]
                elif corruption == "stalled":
                    result["nextCursor"] = cursor
                elif corruption == "empty_progress":
                    result["settings"] = []
            elif cursor == "opaque-part-three" and corruption == "cycle":
                result["nextCursor"] = "opaque-part-two"
            elif cursor == "" and corruption == "skipped":
                result["nextCursor"] = "opaque-part-three"
            elif cursor == "" and corruption == "early_end":
                result["nextCursor"] = None
            elif cursor == "" and corruption == "order":
                result["settings"][0], result["settings"][1] = result["settings"][1], result["settings"][0]
        return result
    with pytest.raises(AssertionError):
        asyncio.run(helpers.read_native_document_sections(fetch))


def test_native_paged_readback_accepts_empty_sections_only_with_complete_metadata():
    async def fetch(section, cursor, limit):
        assert cursor == ""
        return {section: {} if section == "conditions" else [], "nextCursor": None, "page": {
            "offset": 0, "limit": limit, "total": 0, "revision": section}}
    assert asyncio.run(helpers.read_native_document_sections(fetch)) == {"conditions": {}, "actions": [], "settings": []}


def test_sdk_parent_discovery_finds_renamed_childless_parent_on_later_inventory_pages():
    calls = []
    async def fetch(scope, cursor):
        calls.append((scope, cursor))
        if scope == "types":
            if not cursor:
                return {"apps": [{"id": 17, "name": "Other", "system": True}], "nextCursor": "more-types"}
            assert cursor == "more-types"
            return {"apps": [{"id": 18, "name": "Rule Machine", "system": True}]}
        if not cursor:
            return {"apps": [{"id": 27, "name": "Rule Machine", "appTypeId": 99, "parentId": None}],
                    "nextCursor": "more-instances"}
        assert cursor == "more-instances"
        return {"apps": [{"id": 28, "name": "My renamed automations", "appTypeId": 18,
                           "parentId": None, "hasChildren": False}]}
    assert asyncio.run(helpers.discover_rule_machine_parent(fetch)) == 28
    assert calls == [("types", ""), ("types", "more-types"), ("instances", ""), ("instances", "more-instances")]


@pytest.mark.parametrize("parents", [
    [], [{"id": 28, "name": "Rule Machine", "appTypeId": 99, "parentId": None}],
    [{"id": 28, "name": "Rule Machine", "appTypeId": 18, "parentId": 5}],
    [{"id": 28, "appTypeId": 18, "parentId": None}, {"id": 29, "appTypeId": 18, "parentId": None}],
    [{"id": "not-decimal", "appTypeId": 18, "parentId": None}],
])
def test_sdk_parent_discovery_rejects_missing_wrong_nested_ambiguous_or_invalid_identity(parents):
    async def fetch(scope, _cursor):
        return {"apps": [{"id": 18, "name": "Rule Machine", "system": True}] if scope == "types" else parents}
    with pytest.raises(AssertionError):
        asyncio.run(helpers.discover_rule_machine_parent(fetch))


def test_sdk_parent_discovery_reads_later_pages_before_rejecting_ambiguity():
    async def fetch(scope, cursor):
        if scope == "types":
            return {"apps": [{"id": 18, "name": "Rule Machine", "system": True}]}
        return {"apps": [{"id": 28 if not cursor else 29, "appTypeId": 18, "parentId": None}],
                "nextCursor": "second" if not cursor else None}
    with pytest.raises(AssertionError, match="Expected one Rule Machine parent"):
        asyncio.run(helpers.discover_rule_machine_parent(fetch))


def test_sdk_parent_discovery_rejects_repeated_inventory_cursor():
    async def fetch(_scope, _cursor):
        return {"apps": [], "nextCursor": "same"}
    with pytest.raises(AssertionError, match="cursor repeated"):
        asyncio.run(helpers.discover_rule_machine_parent(fetch))


def test_native_mrtr_changes_sizes_a_smaller_batch_without_waits():
    changes, messages = helpers.native_mrtr_changes("proof", "588", {"E2E_MRTR_ACTION_COUNT": "30"}, total=50, max_waits=0)
    assert len(changes) == 50 and len(messages) == 30
    assert sum(1 for row in changes if row["part"] == "condition") == 20
    assert not any(row["value"].get("subtype") == "getWaitEvents" for row in changes if row["part"] == "action")


def test_native_mrtr_changes_refuses_more_logs_than_the_batch_holds():
    with pytest.raises(ValueError):
        helpers.native_mrtr_changes("proof", "588", {"E2E_MRTR_ACTION_COUNT": "50"}, total=50)
