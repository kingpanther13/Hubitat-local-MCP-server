"""pytest unit tests for helpers and transport-isolated TestRunner behavior.

Importing the module itself is skipped if the 'requests' library is not available,
which keeps a bare `pytest` invocation usable; CI installs requests so this module
actually runs there.
"""

import json
import os
import sys
from types import SimpleNamespace

# tests/ is already on sys.path conceptually, but be explicit for safety.
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import pytest

# e2e_test.py imports `requests` at module level. Skip the whole module gracefully if
# requests is not installed, so a bare `pytest` run still works locally. CI installs it.
requests = pytest.importorskip("requests", reason="'requests' not installed; skipping e2e helpers")

import e2e_test as et  # noqa: E402 -- must follow the importorskip above (e2e_test imports requests at module level)


def _raw_tool_body(body, *, is_error=False):
    return {
        "isError": is_error,
        "content": [{"type": "text", "text": json.dumps(body)}],
    }


def _watchdog_response(logs):
    return SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {
            "jsonrpc": "2.0",
            "id": 1,
            "result": _raw_tool_body({"success": True, "logs": logs}),
        },
    )


def test_limiter_lines_falls_back_to_watchdog_and_filters_exact_device_method(monkeypatch):
    target = (
        "dev|5781|BAT_E2E_CmdRoundtrip|error|"
        "LimitExceededException: App 38 generates excessive hub load (method on)"
    )

    class UnavailableMainClient:
        def call_tool(self, _name, _arguments):
            raise et.RelayLostResponseError("504 Gateway Timeout")

    posted = []

    def post(*args, **kwargs):
        posted.append((args, kwargs))
        return _watchdog_response([
            {"name": "fresh-exact", "message": target},
            {"name": "wrong-method", "message": target.replace("method on", "method off")},
            {"name": "wrong-device", "message": target.replace("dev|5781|", "dev|5782|")},
            {"name": "not-limited", "message": "dev|5781|BAT|error|ordinary failure (method on)"},
        ])

    runner = object.__new__(et.TestRunner)
    runner.client = UnavailableMainClient()
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", post)

    assert runner._limiter_lines(5781, method="on") == {f"fresh-exact|{target}"}
    assert posted == [((), {
        "url": "https://watchdog.invalid/mcp",
        "json": {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {
                "name": "hub_get_hub_logs",
                "arguments": {"level": "ERROR", "limit": 40},
            },
        },
        "timeout": 30,
    })]


def test_limiter_logged_uses_watchdog_for_unusable_main_reads_and_requires_fresh_line(monkeypatch):
    stale = (
        "dev|5781|BAT_E2E_CmdRoundtrip|error|"
        "LimitExceededException: App 38 generates excessive hub load (method on)"
    )
    fresh = stale.replace("App 38", "App 39")

    class UnusableMainClient:
        def call_tool(self, _name, _arguments):
            return {"success": False, "error": "log endpoint unavailable"}

    watchdog_replies = iter([
        _watchdog_response([{"name": "stale", "message": stale}]),
        _watchdog_response([
            {"name": "stale", "message": stale},
            {"name": "fresh", "message": fresh},
            {"name": "other-method", "message": fresh.replace("method on", "method off")},
        ]),
    ])

    runner = object.__new__(et.TestRunner)
    runner.client = UnusableMainClient()
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", lambda *args, **kwargs: next(watchdog_replies))

    baseline = runner._limiter_lines(5781, method="on")

    assert baseline == {f"stale|{stale}"}
    assert runner._limiter_logged(5781, method="on", baseline=baseline) is True


def test_run_artifact_suffix_is_stable_and_unique_per_github_attempt():
    first_attempt = {"GITHUB_RUN_ID": "31680286237", "GITHUB_RUN_ATTEMPT": "1"}
    second_attempt = {"GITHUB_RUN_ID": "31680286237", "GITHUB_RUN_ATTEMPT": "2"}

    assert et._run_artifact_suffix(first_attempt) == "31680286237_1"
    assert et._run_artifact_suffix(second_attempt) == "31680286237_2"
    assert et._run_artifact_suffix(second_attempt) == "31680286237_2"


@pytest.fixture
def send_client(monkeypatch):
    """Build a fully seeded transport-isolated client for `_send` tests."""
    def factory(post, *, read_only_tools=()):
        client = object.__new__(et.HubitatMcpClient)
        client._request_id = 0
        client._transport_retries = 0
        client._http_leg_timings = []
        client._read_only_catalog_tools = set(read_only_tools)
        client.endpoint = "https://example.invalid/mcp"
        client.access_token = "secret"
        client.verbose = False
        client.session = SimpleNamespace(post=post)
        return client

    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    return factory


def test_send_records_only_the_actual_http_post_duration(monkeypatch, send_client):
    response = SimpleNamespace(
        status_code=200,
        reason="OK",
        json=lambda: {"jsonrpc": "2.0", "id": 1, "result": {"resultType": "complete"}},
        raise_for_status=lambda: None,
    )
    client = send_client(
        lambda *args, **kwargs: response,
        read_only_tools={"hub_get_info"},
    )
    ticks = iter((100.0, 108.0))
    monkeypatch.setattr(et.time, "monotonic", lambda: next(ticks))

    client._send("tools/call", {"name": "hub_get_info", "arguments": {}})

    assert client._http_leg_timings == [("tools/call", 8.0, 200)]


def test_send_retries_a_lost_round_zero_mrtr_reservation(send_client):
    responses = iter([
        SimpleNamespace(status_code=504, reason="Gateway Timeout"),
        SimpleNamespace(
            status_code=200,
            reason="OK",
            json=lambda: {"jsonrpc": "2.0", "id": 1, "result": {
                "resultType": "input_required", "requestState": "state-live",
            }},
            raise_for_status=lambda: None,
        ),
    ])
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return next(responses)

    client = send_client(post)

    result = client._send("tools/call", {
        "name": "hub_manage_native_rules_and_apps",
        "arguments": {
            "tool": "hub_set_native_app",
            "args": {"appType": "basic_rule", "name": "BAT", "confirm": True},
        },
    })

    assert result == {"resultType": "input_required", "requestState": "state-live"}
    assert len(posts) == 2
    assert posts[0] == posts[1]
    assert client._transport_retries == 1


def test_send_does_not_retry_a_lost_non_mrtr_write(send_client):
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return SimpleNamespace(status_code=504, reason="Gateway Timeout")

    client = send_client(post)

    with pytest.raises(et.RelayLostResponseError):
        client._send("tools/call", {
            "name": "hub_manage_variables",
            "arguments": {
                "tool": "hub_create_variable",
                "args": {"name": "BAT", "value": "x", "confirm": True},
            },
        })

    assert len(posts) == 1


@pytest.mark.parametrize("leaf_args", [
    {"ruleId": 1, "action": "rule"},
    json.dumps({"ruleId": 1, "action": "rule"}),
])
def test_send_does_not_retry_a_lost_single_rule_call_without_confirm(
    send_client, leaf_args,
):
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return SimpleNamespace(status_code=504, reason="Gateway Timeout")

    client = send_client(post, read_only_tools={"hub_read_rules"})

    with pytest.raises(et.RelayLostResponseError):
        client._send("tools/call", {
            "name": "hub_manage_native_rules_and_apps",
            "arguments": {
                "tool": "hub_call_rule",
                "args": leaf_args,
            },
        })

    assert len(posts) == 1


def test_send_retries_only_catalog_proven_read_tool(send_client):
    responses = iter([
        SimpleNamespace(status_code=504, reason="Gateway Timeout"),
        SimpleNamespace(
            status_code=200,
            reason="OK",
            json=lambda: {"jsonrpc": "2.0", "id": 1, "result": {
                "resultType": "complete", "content": [],
            }},
            raise_for_status=lambda: None,
        ),
    ])
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return next(responses)

    client = send_client(post, read_only_tools={"hub_read_rules"})

    result = client._send("tools/call", {
        "name": "hub_read_rules",
        "arguments": {"tool": "hub_list_rules", "args": {}},
    })

    assert result["resultType"] == "complete"
    assert len(posts) == 2
    assert client._transport_retries == 1


@pytest.mark.parametrize(
    ("wire_name", "arguments"),
    [
        (
            "hub_update_mcp_settings",
            {"settings": {"maxConcurrentWrites": 2}, "confirm": True},
        ),
        (
            "hub_manage_mcp",
            {"tool": "hub_update_mcp_settings", "args": {
                "settings": {"maxConcurrentWrites": 2}, "confirm": True,
            }},
        ),
    ],
)
def test_send_retries_the_structurally_identified_settings_write(
    send_client, wire_name, arguments,
):
    responses = iter([
        SimpleNamespace(status_code=504, reason="Gateway Timeout"),
        SimpleNamespace(
            status_code=200,
            reason="OK",
            json=lambda: {"jsonrpc": "2.0", "id": 1, "result": {
                "resultType": "complete", "content": [],
            }},
            raise_for_status=lambda: None,
        ),
    ])
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return next(responses)

    client = send_client(post)

    result = client._send("tools/call", {
        "name": wire_name,
        "arguments": arguments,
    })

    assert result["resultType"] == "complete"
    assert len(posts) == 2


def test_send_does_not_trust_settings_tool_name_inside_write_data(send_client):
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return SimpleNamespace(status_code=504, reason="Gateway Timeout")

    client = send_client(post)

    with pytest.raises(et.RelayLostResponseError):
        client._send("tools/call", {
            "name": "hub_manage_devices",
            "arguments": {
                "tool": "hub_call_device_command",
                "args": {
                    "deviceId": 1,
                    "command": "send",
                    "parameters": ["hub_update_mcp_settings"],
                },
            },
        })

    assert len(posts) == 1


def test_read_only_tools_from_catalog_fails_closed():
    tools = [
        {"name": "hub_read_rules", "annotations": {"readOnlyHint": True}},
        {"name": "hub_manage_rules", "annotations": {"readOnlyHint": False}},
        {"name": "missing_annotations"},
        {"name": "malformed", "annotations": []},
    ]

    assert et._read_only_tools_from_catalog(tools) == {"hub_read_rules"}


@pytest.mark.parametrize(
    ("method", "params", "expected_name"),
    [
        ("server/discover", None, None),
        ("tools/list", {"cursor": "next"}, None),
        ("resources/read", {"uri": "hubitat://context"}, "hubitat://context"),
        ("tools/call", {"name": "hub_get_info", "arguments": {}}, "hub_get_info"),
    ],
)
def test_send_defaults_every_standard_e2e_request_to_modern_headers(
    monkeypatch, method, params, expected_name,
):
    client = object.__new__(et.HubitatMcpClient)
    client._request_id = 0
    client._transport_retries = 0
    client._http_leg_timings = []
    client.endpoint = "https://example.invalid/mcp"
    client.access_token = "secret"
    client.verbose = False
    posted = []
    response = SimpleNamespace(
        status_code=200,
        reason="OK",
        json=lambda: {"jsonrpc": "2.0", "id": 1, "result": {}},
        raise_for_status=lambda: None,
    )

    def post(*args, **kwargs):
        posted.append(kwargs)
        return response

    client.session = SimpleNamespace(post=post)
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)

    client._send(method, params)

    assert posted[0]["headers"] == {
        "MCP-Protocol-Version": et.MODERN_PROTOCOL_VERSION,
        "Mcp-Method": method,
        **({"Mcp-Name": expected_name} if expected_name else {}),
    }


def test_raw_request_defaults_a_single_message_to_modern_headers(monkeypatch):
    client = object.__new__(et.HubitatMcpClient)
    client.endpoint = "https://example.invalid/mcp"
    client.access_token = "secret"
    posted = []
    response = SimpleNamespace(status_code=200, reason="OK")

    def post(*args, **kwargs):
        posted.append(kwargs)
        return response

    client.session = SimpleNamespace(post=post)
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)

    client.raw_request({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": "hub_get_info", "arguments": {}},
    })

    assert posted[0]["headers"] == {
        "MCP-Protocol-Version": et.MODERN_PROTOCOL_VERSION,
        "Mcp-Method": "tools/call",
        "Mcp-Name": "hub_get_info",
    }


def test_regular_e2e_client_refuses_an_explicit_legacy_or_headerless_path(monkeypatch):
    client = object.__new__(et.HubitatMcpClient)
    client._request_id = 0
    client._transport_retries = 0
    client._http_leg_timings = []
    client.endpoint = "https://example.invalid/mcp"
    client.access_token = "secret"
    client.verbose = False
    client.session = SimpleNamespace(post=lambda *args, **kwargs: pytest.fail("must not POST"))
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    payload = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}

    with pytest.raises(AssertionError, match="only 2026-07-28"):
        client._send("tools/list", headers={"MCP-Protocol-Version": "2025-06-18"})
    with pytest.raises(AssertionError, match="only 2026-07-28"):
        client.raw_request(payload, headers={})


def test_regular_e2e_mrtr_summary_requires_a_long_multi_leg_terminal_call():
    summary = et._summarize_mrtr_e2e_proof(
        continuation_rounds=3,
        result_type="complete",
        logical_elapsed=20.8,
        http_legs=[
            (0.2, 200, True),
            (8.1, 200, True),
            (8.0, 200, True),
            (4.1, 200, True),
        ],
        server_rounds=1,
    )

    assert summary == {
        "legs": 4,
        "successful_decoded_responses": 4,
        "replayed_legs": 0,
        "continuation_rounds": 3,
        "logical_elapsed": 20.8,
        "max_leg_elapsed": 8.1,
    }


def test_regular_e2e_mrtr_summary_accepts_one_safe_transport_replay():
    summary = et._summarize_mrtr_e2e_proof(
        continuation_rounds=2,
        result_type="complete",
        logical_elapsed=20.8,
        http_legs=[
            (0.2, 200, True),
            (9.2, 504, False),
            (3.0, 200, True),
            (4.1, 200, True),
        ],
        server_rounds=1,
    )

    assert summary["legs"] == 4
    assert summary["successful_decoded_responses"] == 3
    assert summary["replayed_legs"] == 1


def test_continuation_telemetry_aggregates_and_ranks_zero_one_and_multi_round_calls():
    rows = et._summarize_continuation_telemetry([
        ("hub_get_info", 0.4, 0, [0.4]),
        ("hub_set_rule:edit", 4.5, 1, [0.2, 4.0]),
        ("hub_set_rule:edit", 6.0, 2, [0.1, 2.5, 3.0]),
        ("hub_call_rule", 1.2, 1, [0.3, 0.7]),
    ])

    assert rows == [
        {
            "operation": "hub_set_rule:edit",
            "logical_calls": 2,
            "logical_seconds": 10.5,
            "physical_legs": 5,
            "continuation_rounds": 3,
            "max_leg_seconds": 4.0,
        },
        {
            "operation": "hub_call_rule",
            "logical_calls": 1,
            "logical_seconds": 1.2,
            "physical_legs": 2,
            "continuation_rounds": 1,
            "max_leg_seconds": 0.7,
        },
        {
            "operation": "hub_get_info",
            "logical_calls": 1,
            "logical_seconds": 0.4,
            "physical_legs": 1,
            "continuation_rounds": 0,
            "max_leg_seconds": 0.4,
        },
    ]


@pytest.mark.parametrize(
    ("rounds", "result_type", "elapsed", "legs", "server_rounds", "message"),
    [
        (1, "complete", 12.0, [0.2, 8.0], 1, "multiple continuation"),
        (2, "input_required", 12.0, [0.2, 8.0, 4.0], 2, "terminal complete"),
        (2, "complete", 10.0, [0.2, 8.0, 4.0], 2, "exceed 10"),
        (2, "complete", 12.0, [0.2, 8.0], 2, "decoded response"),
        (2, "complete", 12.0, [0.2, 9.5, 4.0], 2, "relay ceiling"),
        (2, "complete", 12.0, [0.2, 8.0, 4.0], 0, "owner slices"),
        (2, "complete", 12.0, [0.2, 8.0, 4.0], 2, "owner slices"),
        (2, "complete", 12.0, [0.2, 8.0, 4.0], 3, "owner slices"),
    ],
)
def test_regular_e2e_mrtr_summary_rejects_an_invalid_proof(
    rounds, result_type, elapsed, legs, server_rounds, message,
):
    with pytest.raises(AssertionError, match=message):
        et._summarize_mrtr_e2e_proof(
            continuation_rounds=rounds,
            result_type=result_type,
            logical_elapsed=elapsed,
            http_legs=[(duration, 200, True) for duration in legs],
            server_rounds=server_rounds,
        )


def test_call_tool_follows_modern_request_state_continuations():
    client = object.__new__(et.HubitatMcpClient)
    client.op_timings = []
    client._active_test = "mrtr/unit"
    client._last_op = None
    client._last_continuation_rounds = 0
    calls = []

    def send(method, params=None, headers=None):
        calls.append((method, dict(params or {}), dict(headers or {})))
        if len(calls) == 1:
            return {"resultType": "input_required", "requestState": "state-123"}
        return {
            "resultType": "complete",
            "content": [{"type": "text", "text": json.dumps({"success": True})}],
        }

    client._send = send

    result = client.call_tool(
        "hub_call_rule", {"ruleId": [1, 2], "action": "stop"}, flat=True)

    assert result == {"success": True}
    assert client._last_continuation_rounds == 1
    assert client._last_result_type == "complete"
    assert calls[0][1] == {
        "name": "hub_call_rule",
        "arguments": {"ruleId": [1, 2], "action": "stop"},
    }
    assert calls[1][1]["requestState"] == "state-123"
    assert calls[1][1]["arguments"] == calls[0][1]["arguments"]
    assert calls[0][2] == {
        "MCP-Protocol-Version": "2026-07-28",
        "Mcp-Method": "tools/call",
        "Mcp-Name": "hub_call_rule",
    }


def test_call_tool_keeps_same_state_contention_inside_one_logical_call():
    client = object.__new__(et.HubitatMcpClient)
    client.op_timings = []
    client.continuation_timings = []
    client._active_test = "mrtr/contention"
    client._last_op = None
    client._last_continuation_rounds = 0
    client._http_leg_timings = []
    calls = []
    replies = iter([
        {"resultType": "input_required", "requestState": "state-live"},
        {"resultType": "input_required", "requestState": "state-live"},
        {"resultType": "complete", "content": [
            {"type": "text", "text": json.dumps({"success": True})}
        ]},
    ])

    def send(method, params=None, headers=None):
        calls.append((method, dict(params or {}), dict(headers or {})))
        client._http_leg_timings.append(("tools/call", 0.5, 200))
        return next(replies)

    client._send = send

    result = client.call_tool(
        "hub_call_rule", {"ruleId": [1, 2], "action": "stop"}, flat=True)

    assert result == {"success": True}
    assert client._last_continuation_rounds == 2
    assert len(calls) == 3
    assert calls[1][1]["requestState"] == "state-live"
    assert calls[2][1]["requestState"] == "state-live"
    assert (
        calls[0][1]["arguments"]
        == calls[1][1]["arguments"]
        == calls[2][1]["arguments"]
    )
    assert client.continuation_timings[-1][0] == "hub_call_rule"
    assert client.continuation_timings[-1][2:] == (2, [0.5, 0.5, 0.5])


def test_call_tool_retains_physical_leg_telemetry_when_a_continuation_504s():
    client = object.__new__(et.HubitatMcpClient)
    client.op_timings = []
    client._active_test = "mrtr/relay-failure"
    client._last_op = None
    client._last_continuation_rounds = 0
    client._last_result_type = None
    client._last_logical_elapsed = 0.0
    client._last_http_leg_seconds = []
    client._last_http_legs = []
    client._http_leg_timings = []
    calls = 0

    def send(method, params=None, headers=None):
        nonlocal calls
        calls += 1
        if calls == 1:
            client._http_leg_timings.append(("tools/call", 2.1, 200))
            return {"resultType": "input_required", "requestState": "state-live"}
        client._http_leg_timings.append(("tools/call", 9.8, 504))
        raise et.RelayLostResponseError("504 Gateway Timeout on tools/call")

    client._send = send

    with pytest.raises(et.RelayLostResponseError):
        client.call_tool(
            "hub_set_rule", {"appId": 42, "confirm": True}, flat=True,
        )

    assert client._last_continuation_rounds == 1
    assert client._last_result_type == "input_required"
    assert client._last_http_leg_seconds == [2.1, 9.8]
    assert client._last_logical_elapsed > 0
    assert client._last_http_legs == [
        (2.1, 200, True),
        (9.8, 504, False),
    ]


def test_call_tool_paces_ten_same_state_contention_rounds_and_still_completes(monkeypatch):
    client = object.__new__(et.HubitatMcpClient)
    client.op_timings = []
    client._active_test = "mrtr/contention-limit"
    client._last_op = None
    client._last_continuation_rounds = 0
    calls = []
    sleeps = []
    contention = {"resultType": "input_required", "requestState": "state-busy"}
    replies = iter([dict(contention) for _ in range(10)] + [{
        "resultType": "complete",
        "content": [{"type": "text", "text": json.dumps({"success": True})}],
    }])

    def send(method, params=None, headers=None):
        calls.append((method, dict(params or {}), dict(headers or {})))
        return next(replies)

    client._send = send
    monkeypatch.setattr(et.time, "sleep", sleeps.append)

    result = client.call_tool(
        "hub_call_rule", {"ruleId": [1, 2], "action": "stop"}, flat=True)

    assert result == {"success": True}
    assert client._last_continuation_rounds == 10
    assert len(calls) == 11
    assert sleeps == [0.05, 0.1, 0.2, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25]
    assert all(call[1].get("requestState") == "state-busy" for call in calls[1:])



def test_settle_before_504_retry_probes_without_a_fixed_minute(monkeypatch):
    sleeps = []
    probes = []

    class FakeClient:
        def _send(self, method, params):
            probes.append((method, params))
            return _raw_tool_body({"success": True})

    runner = object.__new__(et.TestRunner)
    runner.client = FakeClient()
    monkeypatch.setattr(et.time, "sleep", sleeps.append)

    runner._settle_before_504_retry("example")

    assert probes == [("tools/call", {"name": "hub_get_info", "arguments": {}})]
    assert sleeps == []


def _native_rule_runner(client):
    runner = object.__new__(et.TestRunner)
    runner.client = client
    runner.created_native_app_ids = []
    return runner


def test_create_native_rule_defaults_to_scalar_app_id():
    class FakeClient:
        def call_tool(self, name, arguments):
            assert name == "hub_manage_rule_machine"
            assert arguments == {
                "tool": "hub_set_rule",
                "args": {"name": "BAT_E2E_ScalarCreate", "confirm": True},
            }
            return {"success": True, "appId": 41, "ruleId": 41}

    runner = _native_rule_runner(FakeClient())

    result = runner._create_native_rule("ScalarCreate")

    assert result == 41
    assert runner.created_native_app_ids == ["41"]


def test_create_native_rule_return_result_preserves_create_envelope():
    envelope = {
        "success": True,
        "appId": 42,
        "ruleId": 42,
        "actions": [{"success": True, "actionIndex": 3}],
    }

    class FakeClient:
        def call_tool(self, name, arguments):
            assert name == "hub_manage_rule_machine"
            assert arguments["args"]["addActions"] == [
                {"capability": "log", "message": "fixture"},
            ]
            return envelope

    runner = _native_rule_runner(FakeClient())

    result = runner._create_native_rule(
        "TupleCreate",
        {"addActions": [{"capability": "log", "message": "fixture"}]},
        return_result=True,
    )

    assert result == (42, envelope)
    assert runner.created_native_app_ids == ["42"]


def test_patch_rule_returns_all_checkpointed_entries_from_one_logical_call():
    calls = []

    class FakeClient:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            return {
                "success": False,
                "partial": True,
                "patchResults": [{"op": "addAction", "success": True, "actionIndex": 3}],
                "patches": [{"op": "addAction", "success": False, "error": "refused"}],
                "health": {"ok": True},
            }

    runner = _native_rule_runner(FakeClient())
    patches = [
        {"addAction": {"capability": "log", "message": "land"}},
        {"addAction": {"capability": "switch", "state": "on"}},
    ]

    entries = runner._patch_rule(42, patches)

    assert entries == [
        {"op": "addAction", "success": True, "actionIndex": 3},
        {"op": "addAction", "success": False, "error": "refused"},
    ]
    assert calls == [("hub_manage_rule_machine", {
        "tool": "hub_set_rule",
        "args": {"appId": 42, "patches": patches, "confirm": True},
    })]
    assert runner._last_write_health == ("42", {"ok": True})


def test_create_native_rule_relay_lost_adoption_marks_bundled_fixture_for_readback(
    monkeypatch,
):
    calls = []
    fixture = {"conditions": [
        {"capability": "Switch", "deviceIds": [88], "state": "on"},
    ]}

    class FakeClient:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            if len(calls) == 1:
                raise et.RelayLostResponseError("504 Gateway Timeout")
            if len(calls) == 2:
                assert name == "hub_manage_native_rules_and_apps"
                return {"rules": [{"id": 43, "label": "BAT_E2E_AdoptedCreate"}]}
            assert name == "hub_read_apps_code"
            return {
                "page": {"paragraphs": ["Required Expression: Test Switch is on"]},
                "settings": {
                    "rCapab_4": "Switch",
                    "rDev_4": [88],
                    "state_4": "on",
                },
            }

    runner = _native_rule_runner(FakeClient())
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)

    result = runner._create_native_rule(
        "AdoptedCreate",
        {"addRequiredExpression": fixture},
        return_result=True,
    )

    assert result == (43, None)
    attempted_args = calls[0][1]["args"]
    assert attempted_args["addRequiredExpression"] == fixture
    runner._assert_switch_required_expression(43, 88)
    assert runner.created_native_app_ids == ["43"]


def test_assert_switch_required_expression_accepts_exact_persisted_fixture():
    class FakeClient:
        def call_tool(self, name, arguments):
            assert name == "hub_read_apps_code"
            assert arguments == {
                "tool": "hub_get_app_config",
                "args": {"appId": 43, "includeSettings": True},
            }
            return {
                "page": {"paragraphs": ["Required Expression: Switch is on"]},
                "settings": {
                    "rCapab_7": "Switch",
                    "rDev_7": {"88": "Test Switch"},
                    "state_7": "on",
                },
            }

    runner = _native_rule_runner(FakeClient())

    runner._assert_switch_required_expression(43, 88, "on")


def test_assert_switch_required_expression_rejects_shell_without_expression():
    class FakeClient:
        def call_tool(self, _name, _arguments):
            return {"page": {"paragraphs": ["Define Required Expression"]}, "settings": {}}

    runner = _native_rule_runner(FakeClient())

    with pytest.raises(AssertionError, match=r"relay 504.*did not persist the Required Expression"):
        runner._assert_switch_required_expression(43, 88, "on")


def test_driver_lifecycle_uses_logical_write_helper_for_create():
    direct_calls = []
    write_calls = []
    reads = iter([
        {"success": True, "version": 1, "source": "DRIVER-LEG-MARKER-V1"},
        {"success": True, "version": 2, "source": "DRIVER-LEG-MARKER-V2"},
        {"success": True, "version": 2, "source": "DRIVER-LEG-MARKER-V2"},
    ])

    class FakeClient:
        def call_tool(self, name, arguments):
            direct_calls.append((name, arguments))
            tool = arguments.get("tool")
            if (name, tool) == ("hub_manage_code", "hub_create_driver"):
                raise AssertionError("driver creation must use the logical write helper")
            if (name, tool) == ("hub_read_apps_code", "hub_get_source"):
                return next(reads)
            if (name, tool) == ("hub_manage_code", "hub_update_driver"):
                return {
                    "success": False,
                    "error": "unable to resolve class ClassThatDoesNotExistBatE2eDrv",
                }
            if (name, tool) == ("hub_manage_code", "hub_delete_item"):
                return {"success": True}
            raise AssertionError(f"unexpected direct call: {name} {arguments}")

    runner = object.__new__(et.TestRunner)
    runner.client = FakeClient()

    def write_once(gateway, tool, args, label):
        write_calls.append((gateway, tool, args, label))
        if tool == "hub_create_driver":
            return {"success": True, "driverId": 77}
        if tool == "hub_update_driver":
            return {"success": True, "previousVersion": 1}
        raise AssertionError(f"unexpected logical write: {tool}")

    runner._write_once = write_once

    et.TestRunner.test_update_driver_code_lifecycle(runner)

    assert [(gateway, tool, label) for gateway, tool, _args, label in write_calls] == [
        ("hub_manage_code", "hub_create_driver", "driver code create"),
        ("hub_manage_code", "hub_update_driver", "driver code round-trip"),
    ]
    create_args = write_calls[0][2]
    assert create_args["confirm"] is True
    assert "DRIVER-LEG-MARKER-V1" in create_args["source"]



def test_backup_gate_retries_when_an_async_state_write_replaces_the_fallback_stamp():
    """A concurrent Hubitat state save can restore an unrelated fresh stamp after the
    test proves its stale stamp landed. Retry the controlled fallback proof instead of
    accepting that interference or failing the full lane."""
    from datetime import UTC, datetime, timedelta

    newest_dt = (datetime.now(UTC) - timedelta(hours=1)).replace(microsecond=0)
    newest_ms = int(newest_dt.timestamp() * 1000)
    unrelated_fresh_ms = newest_ms + 20 * 60 * 1000

    class FakeClient:
        def __init__(self):
            self.last_stamp = unrelated_fresh_ms
            self.list_calls = 0
            self.stale_stamps = 0
            self.write_calls = 0
            self.returned_interference = False

        def call_tool(self, name, arguments=None):
            arguments = arguments or {}
            if name == "hub_manage_backup":
                assert arguments == {"tool": "hub_list_backups", "args": {"scope": "hub_local"}}
                self.list_calls += 1
                return {"hubLocalBackups": [{
                    "createTimeOrig": newest_dt.strftime("%Y-%m-%dT%H:%M:%S%z"),
                }]}
            if name == "hub_create_backup":
                mock_epoch = arguments.get("mockEpoch")
                if mock_epoch is not None:
                    self.stale_stamps += 1
                    self.last_stamp = mock_epoch
                    return {"success": True, "mocked": True}
                self.last_stamp = unrelated_fresh_ms
                return {"success": True, "mocked": True}
            if name == "hub_get_info":
                if self.write_calls == 1 and not self.returned_interference:
                    self.returned_interference = True
                    return {"lastBackupEpoch": unrelated_fresh_ms}
                return {"lastBackupEpoch": self.last_stamp}
            if name == "hub_manage_files":
                tool = arguments["tool"]
                if tool == "hub_write_file":
                    self.write_calls += 1
                    self.last_stamp = newest_ms
                    return {"success": True}
                if tool == "hub_delete_file":
                    return {"success": True}
            raise AssertionError(f"unexpected call: {name} {arguments}")

    client = FakeClient()
    runner = object.__new__(et.TestRunner)
    runner.client = client

    et.TestRunner.test_backup_gate_list_fallback(runner)

    assert client.list_calls == 2
    assert client.stale_stamps == 2
    assert client.write_calls == 2

# ---------------------------------------------------------------------------
# _inject_device_id
# ---------------------------------------------------------------------------

def test_inject_device_id_replaces_placeholder():
    """PLACEHOLDER in deviceId is replaced with the given device ID."""
    obj = {"type": "device_event", "deviceId": "PLACEHOLDER", "attribute": "switch"}
    result = et._inject_device_id(obj, "99")
    assert result["deviceId"] == "99"


def test_inject_device_id_non_placeholder_unchanged():
    """Non-PLACEHOLDER deviceId values are left unchanged."""
    obj = {"type": "device_command", "deviceId": "42", "command": "on"}
    result = et._inject_device_id(obj, "99")
    assert result["deviceId"] == "42"


def test_inject_device_id_no_device_id_key():
    """Object with no deviceId key passes through unchanged."""
    obj = {"type": "log", "message": "hello"}
    result = et._inject_device_id(obj, "99")
    assert result == obj


def test_inject_device_id_does_not_mutate_original():
    """Original dict is not mutated (shallow copy)."""
    obj = {"deviceId": "PLACEHOLDER", "type": "x"}
    original_id = obj["deviceId"]
    et._inject_device_id(obj, "55")
    assert obj["deviceId"] == original_id


def test_inject_device_id_does_not_recurse_into_dict_condition():
    """Singular `condition` is a dict, not a list — _inject_device_id only
    recurses into list-valued keys (conditions/thenActions/elseActions/actions),
    so a PLACEHOLDER inside a dict-typed `condition` is left untouched. This
    locks in the documented limitation; a future refactor that adds dict
    recursion will need to update this test."""
    obj = {
        "type": "if_then_else",
        "condition": {"type": "device_state", "deviceId": "PLACEHOLDER"},
        "thenActions": [],
        "elseActions": [],
    }
    result = et._inject_device_id(obj, "77")
    assert result["condition"]["deviceId"] == "PLACEHOLDER"


def test_inject_device_id_recurses_into_actions_list():
    """PLACEHOLDER inside an 'actions' list entry is replaced."""
    obj = {
        "type": "rule",
        "actions": [
            {"type": "device_command", "deviceId": "PLACEHOLDER", "command": "on"},
            {"type": "log", "message": "done"},
        ],
    }
    result = et._inject_device_id(obj, "33")
    assert result["actions"][0]["deviceId"] == "33"
    assert result["actions"][1].get("deviceId") is None


def test_inject_device_id_recurses_into_then_actions():
    """PLACEHOLDER inside 'thenActions' list entry is replaced."""
    obj = {
        "type": "if_then_else",
        "thenActions": [{"type": "device_command", "deviceId": "PLACEHOLDER"}],
        "elseActions": [],
    }
    result = et._inject_device_id(obj, "11")
    assert result["thenActions"][0]["deviceId"] == "11"


def test_inject_device_id_recurses_into_else_actions():
    """PLACEHOLDER inside 'elseActions' list entry is replaced."""
    obj = {
        "type": "if_then_else",
        "thenActions": [],
        "elseActions": [{"type": "device_command", "deviceId": "PLACEHOLDER"}],
    }
    result = et._inject_device_id(obj, "22")
    assert result["elseActions"][0]["deviceId"] == "22"


# ---------------------------------------------------------------------------
# _op_key (per-op timing key resolution)
# ---------------------------------------------------------------------------

def test_op_key_gateway_set_rule_create():
    """Gateway-wrapped hub_set_rule with no inner appId resolves to a :create op."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {}}) == "hub_set_rule:create"


def test_op_key_gateway_set_rule_edit():
    """An inner appId marks an :edit (mutation), so fixture-create cost stays separable in the summary."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {"appId": "5"}}) == "hub_set_rule:edit"


def test_op_key_gateway_other_subtool_uses_sub_tool():
    """A gateway call resolves to its sub-tool, not the gateway name."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}}) == "hub_list_rules"


def test_op_key_decodes_stringified_inner_args_before_classifying_edit():
    """A stringified appId must still classify the operation as an edit."""
    assert et._op_key(
        "hub_manage_rule_machine",
        {"tool": "hub_set_rule", "args": json.dumps({"appId": "5"})},
    ) == "hub_set_rule:edit"


def test_op_key_flat_tool_uses_name():
    """A flat (non-gateway) call resolves to the tool name; None args are tolerated."""
    assert et._op_key("hub_get_info", {}) == "hub_get_info"
    assert et._op_key("hub_get_info", None) == "hub_get_info"


# ---------------------------------------------------------------------------
# _gateway_route_from_catalog (issue #319 leaf -> gateway reverse map)
# ---------------------------------------------------------------------------

def _gw(name: str, subtools: list[str]) -> dict:
    """A minimal gateway-mode tools/list gateway entry (the {tool, args} envelope)."""
    return {
        "name": name,
        "inputSchema": {
            "type": "object",
            "properties": {
                "tool": {"type": "string", "enum": subtools},
                "args": {"type": "object"},
            },
        },
    }


def _leaf(name: str, props: dict | None = None) -> dict:
    """A minimal core/leaf tools/list entry (no {tool, args} envelope)."""
    return {"name": name, "inputSchema": {"type": "object", "properties": props or {}}}


def test_route_map_routes_leaf_to_its_gateway():
    route = et._gateway_route_from_catalog([_gw("hub_manage_rooms", ["hub_create_room", "hub_delete_room"])])
    assert route == {"hub_create_room": "hub_manage_rooms", "hub_delete_room": "hub_manage_rooms"}


def test_route_map_ignores_core_leaf_entries():
    """Core tools (no tool+args envelope) contribute nothing -- they are never routed."""
    route = et._gateway_route_from_catalog([
        _leaf("hub_get_info"),
        _leaf("hub_manage_virtual_device", {"action": {"type": "string", "enum": ["create", "delete"]}}),
        _gw("hub_manage_rooms", ["hub_create_room"]),
    ])
    assert "hub_get_info" not in route
    assert "hub_manage_virtual_device" not in route
    assert route["hub_create_room"] == "hub_manage_rooms"


def test_route_map_multi_gateway_read_prefers_pure_read_gateway():
    """A read living in both a manage_ and a read_ gateway routes through the read surface,
    regardless of catalog order."""
    entries = [
        _gw("hub_manage_devices", ["hub_list_devices", "hub_call_device_command"]),
        _gw("hub_read_devices", ["hub_list_devices"]),
    ]
    route = et._gateway_route_from_catalog(entries)
    assert route["hub_list_devices"] == "hub_read_devices"
    assert route["hub_call_device_command"] == "hub_manage_devices"
    # ...and with the read gateway FIRST it stays on the read surface.
    route_rev = et._gateway_route_from_catalog(list(reversed(entries)))
    assert route_rev["hub_list_devices"] == "hub_read_devices"


def test_route_map_multi_manage_membership_first_gateway_wins():
    """A write in two manage_ gateways routes through the first in catalog order (deterministic)."""
    route = et._gateway_route_from_catalog([
        _gw("hub_manage_rule_machine", ["hub_delete_native_app"]),
        _gw("hub_manage_native_rules_and_apps", ["hub_delete_native_app"]),
    ])
    assert route["hub_delete_native_app"] == "hub_manage_rule_machine"


def test_route_map_flat_catalog_yields_empty_map():
    """A flat-mode catalog (every tool a leaf) builds an empty map -- every call falls
    through to direct dispatch."""
    assert et._gateway_route_from_catalog([_leaf("hub_list_rooms"), _leaf("hub_get_info")]) == {}


def test_route_map_tolerates_missing_schema_and_enum():
    """Entries without inputSchema/properties/enum are skipped, not crashed on."""
    route = et._gateway_route_from_catalog([
        {"name": "hub_weird"},
        {"name": "hub_no_props", "inputSchema": {"type": "object"}},
        {"name": "hub_no_enum", "inputSchema": {"type": "object", "properties": {"tool": {"type": "string"}, "args": {"type": "object"}}}},
        {"name": "hub_nondict_tool", "inputSchema": {"type": "object", "properties": {"tool": None, "args": {"type": "object"}}}},
        _gw("hub_manage_rooms", ["hub_create_room"]),
    ])
    assert route == {"hub_create_room": "hub_manage_rooms"}


# ---------------------------------------------------------------------------
# _gateway_members_from_catalog + the call_tool membership guard (issue #319)
# ---------------------------------------------------------------------------

def test_members_map_lists_each_gateways_subtools():
    members = et._gateway_members_from_catalog([
        _gw("hub_manage_rooms", ["hub_list_rooms", "hub_create_room"]),
        _gw("hub_read_devices", ["hub_list_devices"]),
        _leaf("hub_get_info"),
    ])
    assert members == {
        "hub_manage_rooms": {"hub_list_rooms", "hub_create_room"},
        "hub_read_devices": {"hub_list_devices"},
    }
    assert "hub_get_info" not in members   # core tools are not gateways


def test_members_map_flat_catalog_is_empty():
    assert et._gateway_members_from_catalog([_leaf("hub_list_rooms"), _leaf("hub_get_info")]) == {}


def _client_with_catalog(tools: list) -> "et.HubitatMcpClient":
    """A client whose catalog maps are pre-seeded from `tools` without any network I/O.

    Built via __new__ so __init__ (URL assembly + a requests.Session) never runs, which
    means every attribute call_tool touches has to be seeded here. Its per-op timing
    bookkeeping needs op_timings / _active_test / _last_op; a stub missing any of them
    fails in call_tool's `finally` — after the guard under test already passed — so the
    seed list belongs in one place rather than ad hoc per test.
    """
    c = et.HubitatMcpClient.__new__(et.HubitatMcpClient)
    c._gateway_members = et._gateway_members_from_catalog(tools)
    c._gateway_route = et._gateway_route_from_catalog(tools)
    c._request_id = 0
    c.op_timings = []
    c._active_test = ""
    c._last_op = None
    return c


def test_membership_guard_rejects_wrong_gateway():
    """A gateway-envelope call whose sub-tool is NOT a member of the named gateway raises
    loudly -- the guard against the _find_app_id_by_label class of silent bug."""
    c = _client_with_catalog([
        _gw("hub_manage_native_rules_and_apps", ["hub_list_rules", "hub_set_native_app"]),
        _gw("hub_read_apps_code", ["hub_list_apps"]),
    ])
    # hub_list_apps is a member of hub_read_apps_code, NOT hub_manage_native_rules_and_apps.
    with pytest.raises(et.McpError) as ei:
        c.call_tool("hub_manage_native_rules_and_apps",
                    {"tool": "hub_list_apps", "args": {"scope": "instances"}})
    msg = str(ei.value)
    assert "not a member" in msg and "hub_list_apps" in msg and "hub_manage_native_rules_and_apps" in msg


def test_membership_guard_allows_valid_membership_then_routes():
    """A valid gateway-envelope call passes the guard. (It would then be sent as-is; we
    stop before network I/O by asserting no guard error is raised for a real member.)"""
    c = _client_with_catalog([_gw("hub_manage_rooms", ["hub_list_rooms", "hub_delete_room"])])
    # No McpError from the guard for a real member; _send would be next (not exercised here).
    # Drive only the guard by monkeypatching _send to short-circuit.
    c._send = lambda method, params, headers=None: {"content": [{"type": "text", "text": "{}"}]}
    c.call_tool("hub_manage_rooms", {"tool": "hub_delete_room", "args": {"room": "X", "confirm": True}})


def test_membership_guard_skipped_for_flat_calls():
    """flat=True bypasses the guard entirely (deliberate flat-dispatch proofs)."""
    c = _client_with_catalog([_gw("hub_manage_rooms", ["hub_list_rooms"])])
    c._send = lambda method, params, headers=None: {"content": [{"type": "text", "text": "{}"}]}
    # A leaf name with flat=True is never treated as a gateway envelope; no guard, no raise.
    c.call_tool("hub_list_rooms", flat=True)


# ---------------------------------------------------------------------------
# TestRunner._list_all_file_names (issue #342: size-guard-immune paginated listing)
# ---------------------------------------------------------------------------

class _PagedFilesClient:
    """Stub client: hub_read_files/hub_list_files pops one canned page per call."""

    def __init__(self, pages):
        self._pages = list(pages)
        self.calls = []

    def call_tool(self, name, arguments=None, **_kw):
        self.calls.append((name, arguments))
        item = self._pages.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def _list_files_via(pages):
    runner = object.__new__(et.TestRunner)  # no __init__: only .client is needed
    runner.client = _PagedFilesClient(pages)
    return et.TestRunner._list_all_file_names(runner), runner.client


def test_list_all_file_names_accumulates_across_pages_and_forwards_cursor():
    """Names accumulate across pages; each nextCursor is forwarded verbatim."""
    (names, authoritative), client = _list_files_via([
        {"files": [{"name": "a.txt"}, {"name": "b.txt"}], "nextCursor": "2"},
        {"files": [{"name": "c.txt"}]},
    ])
    assert names == ["a.txt", "b.txt", "c.txt"]
    assert authoritative is True
    assert client.calls[0][1]["args"]["cursor"] == ""
    assert client.calls[1][1]["args"]["cursor"] == "2"


def test_list_all_file_names_forwards_filter_on_every_page():
    """A targeted listing keeps its server-side filter across cursor pages."""
    runner = object.__new__(et.TestRunner)
    runner.client = _PagedFilesClient([
        {"files": [{"name": "needle-a.zip"}], "nextCursor": "2"},
        {"files": [{"name": "needle-b.zip"}]},
    ])

    names, authoritative = et.TestRunner._list_all_file_names(runner, "needle")

    assert names == ["needle-a.zip", "needle-b.zip"]
    assert authoritative is True
    assert [call[1]["args"] for call in runner.client.calls] == [
        {"cursor": "", "filter": "needle"},
        {"cursor": "2", "filter": "needle"},
    ]


def test_list_all_file_names_response_too_large_is_non_authoritative():
    """A response_too_large envelope must NOT read as an authoritative empty listing
    (the false-'absent' verdict that failed test_export_bundle)."""
    (names, authoritative), _ = _list_files_via([
        {"files": [{"name": "a.txt"}], "nextCursor": "1"},
        {"response_too_large": True, "truncated": True},
    ])
    assert authoritative is False
    assert names == ["a.txt"]  # partial names kept: presence evidence stays usable


def test_list_all_file_names_degraded_blind_empty_page_is_non_authoritative():
    """A blind empty page with a message/error marker (File Manager degraded under
    load) is non-authoritative."""
    (names, authoritative), _ = _list_files_via([
        {"files": [], "message": "File Manager unavailable"},
    ])
    assert authoritative is False
    assert names == []


def test_list_all_file_names_clean_empty_listing_is_authoritative():
    """A marker-free empty page is a real (authoritative) empty File Manager."""
    (names, authoritative), _ = _list_files_via([{"files": []}])
    assert authoritative is True
    assert names == []


def test_list_all_file_names_transport_error_is_non_authoritative():
    """A transport-level failure mid-enumeration keeps partial names, drops authority."""
    (names, authoritative), _ = _list_files_via([
        {"files": [{"name": "a.txt"}], "nextCursor": "1"},
        et.McpError("relay 504"),
    ])
    assert authoritative is False
    assert names == ["a.txt"]


def test_export_bundle_uses_logical_writes_filtered_verification_and_exact_backup_cleanup():
    """The export path issues every write once, verifies through a targeted live
    listing, and deletes the exact backup returned by the first cleanup call."""
    bundle_id = "7"
    file_name = f"{et.PREFIX}bundle_export_{bundle_id}.zip"
    backup_name = f"{et.PREFIX}bundle_export_{bundle_id}_backup_123.zip"
    write_calls = []
    list_filters = []

    class NoDirectWritesClient:
        def call_tool(self, name, arguments=None):
            raise AssertionError(f"unexpected direct call: {name} {arguments}")

    runner = object.__new__(et.TestRunner)
    runner.client = NoDirectWritesClient()
    runner._mcp_bundle_id = bundle_id
    runner._soft_passes = []
    runner._current_test = "system_tools/test_export_bundle"

    def write_once(gateway, tool, args, label):
        write_calls.append((gateway, tool, args, label))
        if tool == "hub_export_bundle":
            return {"success": True, "bytes": 321, "fileName": file_name}
        if tool == "hub_delete_file" and args["fileName"] == file_name:
            return {"success": True, "fileName": file_name, "backupFile": backup_name}
        if tool == "hub_delete_file" and args["fileName"] == backup_name:
            return {"success": True, "fileName": backup_name}
        raise AssertionError(f"unexpected logical write: {tool} {args}")

    def list_file_names(name_filter=None):
        list_filters.append(name_filter)
        return [file_name], True

    runner._write_once = write_once
    runner._list_all_file_names = list_file_names

    et.TestRunner.test_export_bundle(runner)

    assert list_filters == [file_name]
    assert [(gateway, tool, args["fileName"] if tool == "hub_delete_file" else args["saveAs"])
            for gateway, tool, args, _label in write_calls] == [
        ("hub_manage_code", "hub_export_bundle", file_name),
        ("hub_manage_files", "hub_delete_file", file_name),
        ("hub_manage_files", "hub_delete_file", backup_name),
    ]


def test_delete_bundle_uses_logical_write_helper(monkeypatch):
    monkeypatch.setenv("PR_RAW_BASE", "https://raw.invalid/repo")
    monkeypatch.setenv("PR_HEAD_SHA_RESOLVED", "abc123")
    write_calls = []
    list_results = iter([
        {"bundles": [{"id": "44", "namespace": "mcptest", "name": "throwaway"}]},
        {"bundles": []},
    ])

    class FakeClient:
        def call_tool(self, name, arguments=None):
            tool = (arguments or {}).get("tool")
            if tool == "hub_install_bundle":
                return {"success": True}
            if tool == "hub_list_bundles":
                return next(list_results)
            if tool == "hub_delete_bundle":
                raise AssertionError("bundle deletion must use the logical write helper")
            raise AssertionError(f"unexpected direct call: {name} {arguments}")

    runner = object.__new__(et.TestRunner)
    runner.client = FakeClient()
    def write_once(gateway, tool, args, label):
        write_calls.append((gateway, tool, args, label))
        return {"success": True, "verified": True, "bundleId": args["bundleId"]}

    runner._write_once = write_once

    et.TestRunner.test_delete_bundle(runner)

    assert write_calls == [(
        "hub_manage_code",
        "hub_delete_bundle",
        {"bundleId": "44", "confirm": True},
        "throwaway bundle delete",
    )]
