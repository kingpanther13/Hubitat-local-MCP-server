"""pytest unit tests for helpers and transport-isolated TestRunner behavior.

Importing the module itself is skipped if the 'requests' library is not available,
which keeps a bare `pytest` invocation usable; CI installs requests so this module
actually runs there.
"""

import io
import json
import os
import sys
import zipfile
from collections import Counter
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

# tests/ is already on sys.path conceptually, but be explicit for safety.
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import pytest

# e2e_test.py imports `requests` at module level. Skip the whole module gracefully if
# requests is not installed, so a bare `pytest` run still works locally. CI installs it.
requests = pytest.importorskip("requests", reason="'requests' not installed; skipping e2e helpers")

import e2e_test as et  # noqa: E402 -- must follow the importorskip above (e2e_test imports requests at module level)


@pytest.mark.parametrize("refetched", [False, True])
def test_jobs_cursor_checks_multiple_pages_without_pinning_expired_snapshot(refetched):
    first = {
        "scheduledJobs": {"jobs": [{"name": str(i)} for i in range(100)], "count": 100, "total": 101},
        "runningJobs": {"jobs": [], "count": 0}, "hubActions": {"actions": [], "count": 0},
        "snapshot": {"fetchedAt": 1000}, "nextCursor": "next-page",
    }
    second = {
        "scheduledJobs": {"jobs": [{"name": "last"}], "count": 1, "total": 102 if refetched else 101},
        "runningJobs": {"jobs": ["new"] if refetched else [], "count": 1 if refetched else 0},
        "hubActions": {"actions": [], "count": 0},
        "snapshot": {"fetchedAt": 32000 if refetched else 1000},
    }
    calls = []

    def call_tool(gateway, args):
        calls.append(args)
        if args["tool"] == "hub_get_performance_stats":
            return {"uptime": "1d"}
        return first if args["args"]["cursor"] == "" else second

    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(call_tool=call_tool)
    runner.test_get_hub_jobs_cursor()
    assert [call["args"].get("cursor") for call in calls[:2]] == ["", "next-page"]
    if not refetched:
        second["scheduledJobs"]["jobs"] = []
        second["scheduledJobs"]["count"] = 0
        with pytest.raises(AssertionError, match="pages summed"):
            runner.test_get_hub_jobs_cursor()


@pytest.mark.parametrize("refetched", [False, True])
def test_jobs_provenance_accepts_shared_snapshot_from_another_transport(refetched):
    jobs = {"scheduledJobs": {}, "snapshot": {
        "fetchedAt": 1000, "ageMs": 500, "background": False, "budgeted": True,
    }}
    stats = {"uptime": "1d", "snapshot": {
        "fetchedAt": 2000 if refetched else 1000, "ageMs": 0 if refetched else 600,
        "background": True if refetched else False, "budgeted": True,
    }}
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(call_tool=lambda gateway, args:
                                    jobs if args["tool"] == "hub_get_jobs" else stats)
    runner.test_get_hub_jobs_snapshot_provenance()
    stats["snapshot"]["ageMs"] = 30001
    with pytest.raises(AssertionError, match="stale snapshot"):
        runner.test_get_hub_jobs_snapshot_provenance()


def _raw_tool_body(body, *, is_error=False):
    return {
        "isError": is_error,
        "content": [{"type": "text", "text": json.dumps(body)}],
    }


def _iso_ms(text):
    return int(datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000)


@pytest.mark.parametrize("refusal", [
    "Device not found: 0",
    "Device metadata fetch failed (/device/fullJson/0)",
])
def test_event_bookmark_reaches_history_after_expected_zero_refusals(refusal):
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")
    client._gateway_members = {"hub_read_devices": {"hub_list_device_events"}}
    client._gateway_route = {"hub_list_device_events": "hub_read_devices"}
    dates = ["2026-09-28T12:00:00.000+0000", "2026-09-28T12:00:01.000+0000"]
    calls = []

    def send(method, params=None, **_kwargs):
        assert method == "tools/call"
        assert params["name"] == "hub_read_devices"
        args = params["arguments"]["args"]
        calls.append(args)
        if "deviceId" not in args:
            return _raw_tool_body({"source": "location", "count": 1,
                                   "events": [{"name": "systemStart", "kind": "hub", "date": dates[0]}]})
        if args["deviceId"] in (0, "0"):
            return _raw_tool_body({"success": False, "isError": True, "error": refusal}, is_error=True)
        assert str(args["deviceId"]) == "42"
        since = args.get("since")
        explicit = since is not None
        until = args.get("until")
        if until is not None and isinstance(since, int) and until <= since:
            return _raw_tool_body({"success": False, "isError": True,
                                   "error": "until must be later than the window start"}, is_error=True)
        rows = [] if since == "2099-01-01T00:00:00.000+0000" else [
            {"date": date, "name": "switch", "value": "on"} for date in dates[1 if explicit else 0:]]
        if until is not None:
            rows = [r for r in rows if _iso_ms(r["date"]) <= until]
        result = {"source": "device", "deviceId": "42", "events": rows, "count": len(rows),
                  "sinceMode": "explicit" if explicit else "relative", "sinceTimestamp": dates[0]}
        if until is not None:
            result["untilTimestamp"] = "2026-09-28T12:00:00.000+0000"
        if explicit:
            result["since"] = since if isinstance(since, str) else dates[0]
        else:
            result["hoursBack"] = args.get("hoursBack", 24)
        return _raw_tool_body(result)

    client._send = send
    runner = object.__new__(et.TestRunner)
    runner.client = client
    runner.get_test_switch_id = lambda: "42"
    runner.test_list_device_events_since_bookmark()
    assert [call["deviceId"] for call in calls[:2]] == [0, "0"]
    assert any(call.get("since") == dates[0] for call in calls)
    assert any(isinstance(call.get("since"), int) for call in calls)


@pytest.mark.parametrize("result", [
    {"success": True, "source": "location", "events": []},
    {"success": False, "isError": True, "error": "Location event history fetch failed", "source": "location"},
    {"success": False, "isError": True, "error": "Device metadata fetch failed (/device/fullJson/42)"},
    {"success": False, "isError": True, "error": "Read tools are disabled"},
])
def test_event_bookmark_rejects_location_fallback_and_unrelated_failures(result):
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")
    client._gateway_members = {"hub_read_devices": {"hub_list_device_events"}}
    client._gateway_route = {"hub_list_device_events": "hub_read_devices"}
    client._send = lambda *_args, **_kwargs: _raw_tool_body(result, is_error=result.get("isError", False))
    runner = object.__new__(et.TestRunner)
    runner.client = client
    runner.get_test_switch_id = lambda: "42"
    with pytest.raises((AssertionError, et.McpToolError)):
        runner.test_list_device_events_since_bookmark()


@pytest.mark.parametrize("method", ["test_device_health_traceroute", "test_device_health_speedtest"])
@pytest.mark.parametrize("failure", [None, "relay", "slow_leg"])
def test_health_probes_require_completed_bounded_transport(method, failure):
    def call_tool(tool, args):
        if tool == "hub_get_logs":
            assert failure == "relay"
            return {"logs": [{"message": "native network timeout"}]}
        assert tool == "hub_get_device_health"
        if failure == "relay":
            raise et.McpError("504 Gateway Timeout")
        return {"traceroute": {"host": args.get("tracerouteHost"), "output": "route"},
                "speedtest": {"output": "download complete"}}

    runner = et.TestRunner.__new__(et.TestRunner)
    runner.client = SimpleNamespace(
        call_tool=call_tool, _last_continuation_rounds=3, _last_logical_elapsed=12.0,
        _last_http_legs=[(10.1 if failure == "slow_leg" else 4.0, 200, True)] * 3,
    )
    invoke = getattr(runner, method)
    if failure == "relay":
        with pytest.raises(et.McpError, match="504"):
            invoke()
    elif failure == "slow_leg":
        with pytest.raises(AssertionError, match="relay limits"):
            invoke()
    else:
        invoke()


@pytest.mark.parametrize("failure", [None, "flip", "read", "restore"])
def test_metadata_mode_switch_is_registered_and_restores_without_catalog(monkeypatch, failure):
    name = "test_metadata_after_mode_switch"
    assert ("infrastructure", name, name) in et.TEST_REGISTRY
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")
    gateway, restores, missing, searches = True, 0, 0, 0

    def send(method, params=None, **_kwargs):
        nonlocal gateway, restores, missing, searches
        assert method == "tools/call", "mode regression must not fetch tools/list"
        tool, args = params["name"], params["arguments"]
        if tool == "hub_update_mcp_settings":
            wanted = args["settings"]["useGateways"]
            if wanted:
                restores += 1
                if failure == "restore" and restores == 1:
                    raise et.McpError("temporary restore failure")
            gateway = wanted
            if not wanted and failure == "flip":
                raise et.McpError("lost mode-change response")
            return _raw_tool_body({"success": True})
        if tool == "hub_list_rooms":
            assert not gateway
            if failure == "read":
                raise et.McpError("flat read failure")
            return _raw_tool_body({"rooms": []})
        if tool == "hub_read_rooms":
            if not gateway:
                raise et.McpError("Gateway is disabled - useGateways is OFF")
            if args["tool"] == "hub_get_room":
                missing += 1
                raise et.McpError("Missing required parameter for hub_get_room: room")
            return _raw_tool_body({"rooms": []})
        assert tool == "hub_search_tools" and gateway
        searches += 1
        return _raw_tool_body({"results": [{"tool": "hub_get_room"}]})

    monkeypatch.setattr(client, "_send", send)
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    runner = object.__new__(et.TestRunner)
    runner.client = client
    if failure in {"flip", "read"}:
        with pytest.raises(et.McpError, match=r"lost mode-change response|flat read failure"):
            getattr(runner, name)()
    else:
        getattr(runner, name)()
        assert (missing, searches) == (2, 1)
    assert gateway
    assert restores == (2 if failure == "restore" else 1)


def _watchdog_response(logs):
    return SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {
            "jsonrpc": "2.0",
            "id": 1,
            "result": _raw_tool_body({"success": True, "logs": logs}),
        },
    )


@pytest.mark.parametrize("denial", ["access", "confirmation", "accepted"])
def test_device_replace_boundary_requires_access_denial_for_both_positions(denial):
    calls = []

    def call_tool(name, args):
        assert name == "hub_call_device_replace"
        assert args.get("list_options") is True or args.get("confirm") is False
        calls.append(args)
        if denial == "accepted":
            return {"success": True}
        reason = "Device not found: 10" if denial == "access" else "confirm=true is required"
        raise et.McpError(reason)

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool))
    if denial == "access":
        runner._device_replace_boundary_checks("10", "20")
        assert calls == [
            {"old_device_id": "10", "list_options": True},
            {"old_device_id": "10", "new_device_id": "20", "confirm": False},
            {"old_device_id": "20", "new_device_id": "10", "confirm": False},
        ]
    else:
        with pytest.raises(AssertionError, match=r"unselected device|reject device access"):
            runner._device_replace_boundary_checks("10", "20")


@pytest.mark.parametrize("rejection", ["unknown", "wrong-code", "wrong-name", "unavailable", "accepted"])
def test_bypass_boundary_preserves_existing_preferences_and_requires_unknown_name_rejection(rejection):
    prefix = f"{et.PREFIX}UnknownPreference"
    declared = {"logEnable": True, prefix: False, prefix + "_": True}
    preferences_before = dict(declared)
    preference_attempts = []
    label_attempts = []
    bypass_changes = []
    bypass = False
    current_label = "Native original"
    native_observations = []
    denied_tools = []
    log_bypasses = []

    class FakeClient:
        def call_tool(self, name, arguments=None):
            nonlocal current_label
            arguments = arguments or {}
            if name == "hub_get_logs":
                assert arguments == {"deviceId": "10", "limit": 5}
                log_bypasses.append(bypass)
                return {"logs": [{"deviceId": "10", "message": "Unselected device log"}]}
            if not bypass:
                assert arguments["deviceId"] == "10"
                denied_tools.append(name)
                raise et.McpError("Device not found: 10")
            if name == "hub_list_devices":
                assert arguments == {"scope": "all", "labelFilter": f"{et.SCAFFOLD_PREFIX}Configuration_StandaloneBypass"}
                return {"devices": [{"id": "10", "mcpAuthorized": True}]}
            if name == "hub_get_device":
                if arguments.get("mode") == "configuration":
                    assert arguments.get("fields") == ["label"]
                    return {
                        "preferenceRead": {"status": "complete"},
                        "availableFields": {"preferences": list(declared)},
                        "editableFields": [{"name": "label", "valuePresent": True, "value": current_label}],
                    }
                return {"id": "10", "name": "Unlisted", "label": "Original",
                        "commands": [{"name": "captureConfiguration"}]}
            if name == "hub_list_device_events":
                return {"events": [], "count": 0}
            if name == "hub_list_device_dependents":
                return {"deviceId": "10", "appsUsing": []}
            if name == "hub_call_device_command":
                assert arguments["command"] == "captureConfiguration"
                native_observations.append({"nonce": arguments["parameters"][0]})
                return {"success": True}
            if name == "hub_get_device_attribute":
                assert arguments["attribute"] == "nativeConfiguration"
                return {"value": json.dumps(native_observations[-1])}
            if name == "hub_update_device":
                if "label" in arguments:
                    current_label = arguments["label"]
                    label_attempts.append(arguments["label"])
                    return {"success": True, "changes": [{"property": "label"}]}
                patch = arguments["preferences"]
                preference_attempts.append(patch)
                assert not set(patch) & set(declared), "Boundary probe attempted an existing preference"
                if rejection in {"unknown", "wrong-code", "wrong-name"}:
                    rejected_name = "differentPreference" if rejection == "wrong-name" else next(iter(patch))
                    error = {"code": -32603 if rejection == "wrong-code" else -32602, "message": (
                        f"Invalid params: Unknown preference '{rejected_name}'; "
                        "read hub_get_device(mode='configuration') for declared names "
                        'See hub_get_tool_guide(section="update_device") for '
                        "hub_update_device's reference and best practices."
                    )}
                    raise et.McpError(f"JSON-RPC error: {error}", rpc_error=error)
                if rejection == "unavailable":
                    raise et.McpError("Unable to read complete preference definitions/storage")
                return {"success": True}
            raise AssertionError(f"Unexpected boundary call: {name} {arguments}")

    def set_bypass(value):
        nonlocal bypass
        bypass = value
        bypass_changes.append(value)
        return {"success": True, "updated": {"bypassDeviceAllowlist": value}}

    runner = object.__new__(et.TestRunner)
    runner.client = FakeClient()
    if rejection == "unknown":
        runner._bypass_boundary_checks("10", set_bypass)
    else:
        with pytest.raises(AssertionError, match=r"Undeclared preference|accepted an undeclared"):
            runner._bypass_boundary_checks("10", set_bypass)

    assert preference_attempts == [{prefix + "__": True}]
    assert declared == preferences_before
    assert label_attempts == ["Native original _BWTEST", "Native original"]
    assert current_label == "Native original"
    assert len(native_observations) == 1
    assert log_bypasses == [False, True]
    assert denied_tools == ["hub_get_device", "hub_get_device_attribute", "hub_list_device_events",
                            "hub_update_device", "hub_call_device_command",
                            "hub_list_device_dependents"] + (["hub_get_device"] if rejection == "unknown" else [])
    assert bypass_changes == [True, False]


@pytest.mark.parametrize("native_label", ["Native original", ""])
@pytest.mark.parametrize("failure", ["reported", "exception"])
def test_bypass_boundary_restores_exact_native_label_after_a_committed_rename_failure(native_label, failure):
    current_label = native_label
    label_attempts = []
    bypass_changes = []
    bypass = False
    denied_tools = []
    log_bypasses = []

    class FakeClient:
        def call_tool(self, name, arguments=None):
            nonlocal current_label
            arguments = arguments or {}
            if name == "hub_get_logs":
                assert arguments == {"deviceId": "10", "limit": 5}
                log_bypasses.append(bypass)
                return {"logs": [{"deviceId": "10", "message": "Unselected device log"}]}
            if not bypass:
                assert arguments["deviceId"] == "10"
                denied_tools.append(name)
                raise et.McpError("Device not found: 10")
            if name == "hub_list_devices":
                assert arguments == {"scope": "all", "labelFilter": f"{et.SCAFFOLD_PREFIX}Configuration_StandaloneBypass"}
                return {"devices": [{"id": "10", "mcpAuthorized": True}]}
            if name == "hub_get_device":
                if arguments.get("mode") == "configuration":
                    return {"editableFields": [{"name": "label", "valuePresent": True, "value": current_label}]}
                return {"id": "10", "name": "Fallback name", "label": "Summary fallback", "commands": []}
            if name == "hub_list_device_events":
                return {"events": [], "count": 0}
            if name == "hub_list_device_dependents":
                return {"deviceId": "10", "appsUsing": []}
            if name == "hub_update_device" and "label" in arguments:
                current_label = arguments["label"]
                label_attempts.append(current_label)
                if len(label_attempts) == 1:
                    if failure == "exception":
                        raise et.McpError("Reply lost after committed rename")
                    return {"success": False}
                return {"success": True}
            raise AssertionError(f"Unexpected boundary call: {name} {arguments}")

    def set_bypass(value):
        nonlocal bypass
        bypass = value
        bypass_changes.append(value)
        return {"success": True, "updated": {"bypassDeviceAllowlist": value}}

    runner = object.__new__(et.TestRunner)
    runner.client = FakeClient()
    error_type = et.McpError if failure == "exception" else AssertionError
    with pytest.raises(error_type, match=r"Reply lost|rename did not succeed"):
        runner._bypass_boundary_checks("10", set_bypass)

    assert label_attempts == [f"{native_label} _BWTEST", native_label]
    assert current_label == native_label
    assert log_bypasses == [False, True]
    assert denied_tools == ["hub_get_device", "hub_get_device_attribute", "hub_list_device_events",
                            "hub_update_device", "hub_call_device_command",
                            "hub_list_device_dependents"]
    assert bypass_changes == [True, False]


def test_validation_log_expectation_uses_reactive_gateway_tool_and_exact_reason():
    params = {
        "name": "hub_manage_variables",
        "arguments": {
            "tool": "hub_create_variable",
            "args": {"name": "probe"},
        },
    }
    error = {
        "code": -32602,
        "message": "Invalid params: Mandatory best-practice acknowledgment is enabled for write "
                   "tools. Read hub_get_tool_guide(section='best_practice_reference') first.",
    }

    assert et._validation_log_expectation("tools/call", params, error) == (
        "Validation error in hub_create_variable: Mandatory best-practice acknowledgment is "
        "enabled for write tools. Read hub_get_tool_guide(section='best_practice_reference') first."
    )


def test_validation_log_expectation_strips_only_the_exact_legacy_reactive_hint():
    params = {
        "name": "hub_manage_devices",
        "arguments": {"tool": "hub_update_device", "args": {"deviceId": "42"}},
    }
    raw_reason = "preference probeBool must be a boolean"
    exact_hint = (
        ' See hub_get_tool_guide(section="update_device") for '
        "hub_update_device's reference and best practices."
    )

    assert et._validation_log_expectation("tools/call", params, {
        "code": -32602, "message": f"Invalid params: {raw_reason}{exact_hint}",
    }) == f"Validation error in hub_update_device: {raw_reason}"

    # Similar caller-authored text is part of the raw exception and must not be
    # broadly removed merely because it mentions the guide.
    altered_hint = exact_hint.replace("hub_update_device's", "another_tool's")
    assert et._validation_log_expectation("tools/call", params, {
        "code": -32602, "message": f"Invalid params: {raw_reason}{altered_hint}",
    }) == f"Validation error in hub_update_device: {raw_reason}{altered_hint}"


def test_tool_validation_log_expectation_reads_the_iserror_validation_shape():
    reason = "Unknown preference 'probe'"
    hint = (' See hub_get_tool_guide(section="update_device") for '
            "hub_update_device's reference and best practices.")
    payload = json.dumps({"success": False, "isError": True,
                          "tool": "hub_update_device", "error": f"{reason}{hint}"})

    assert et._tool_validation_log_expectation(payload) == (
        f"Validation error in hub_update_device: {reason}"
    )


def test_tool_failure_log_expectation_names_the_leaf_of_a_gateway_call():
    # The hub logs "Tool <leaf> returned a failure result"; the gateway name never appears.
    payload = {"success": False, "error": "boom"}
    args = {"tool": "hub_update_device", "args": {"deviceId": "42"}}
    assert et._tool_failure_log_expectation("hub_manage_devices", payload, args) == (
        "Tool hub_update_device returned a failure result")
    # The result's own tool field wins when present.
    assert et._tool_failure_log_expectation("hub_manage_devices",
                                            {**payload, "tool": "hub_delete_device"}, args) == (
        "Tool hub_delete_device returned a failure result")
    # A flat call has no gateway argument to resolve through.
    assert et._tool_failure_log_expectation("hub_update_device", payload, {"deviceId": "42"}) == (
        "Tool hub_update_device returned a failure result")


@pytest.mark.parametrize(("payload", "expected"), [
    ({"success": False, "error": "the hub refused the write"}, "Tool hub_update_device returned a failure result"),
    ({"isError": True, "error": "worker failed", "tool": "hub_update_device"}, "Tool hub_update_device returned a failure result"),
    ({"success": True, "deviceId": "42"}, None),
    ({"partial": True}, None),
    ("not a dict", None),
])
def test_tool_failure_log_expectation_matches_the_failure_result_line(payload, expected):
    assert et._tool_failure_log_expectation("hub_update_device", payload) == expected


@pytest.mark.parametrize("payload", [
    # A runtime failure logs a different line and must not consume a validation slot.
    json.dumps({"success": False, "error": "the hub refused the write"}),
    json.dumps({"success": True, "deviceId": "42"}),
    json.dumps({"isError": True, "error": "no tool key"}),
    json.dumps({"isError": True, "tool": "hub_update_device"}),
    # The MRTR runtime-failure shape: isError/tool/error too, but not a validation refusal.
    json.dumps({"success": False, "isError": True, "tool": "hub_call_rule",
                "error": "Tool error: boom", "aggregate": {"kind": "call_rule"}}),
    json.dumps({"success": False, "isError": True, "tool": "hub_call_rule",
                "error": "Tool error: boom"}),
    "not json at all",
    "",
])
def test_tool_validation_log_expectation_ignores_everything_else(payload):
    assert et._tool_validation_log_expectation(payload) is None


@pytest.mark.parametrize(
    ("method", "params", "error"),
    [
        ("tools/list", {}, {"code": -32602, "message": "Invalid params: bad"}),
        ("tools/call", {"name": "hub_get_info", "arguments": {}},
         {"code": -32601, "message": "Method not found"}),
        ("tools/call", {"name": "hub_get_info", "arguments": {}},
         {"code": -32602, "message": "different error shape"}),
    ],
)
def test_validation_log_expectation_rejects_unrelated_rpc_errors(method, params, error):
    assert et._validation_log_expectation(method, params, error) is None


def test_partition_hub_errors_consumes_only_observed_validation_error_count():
    intentional = "Validation error in hub_create_variable: missing bestPracticeKey"
    stale = {"name": "12:00:00", "message": "pre-existing failure"}
    logs = [
        stale,
        {"name": "12:00:01", "message": intentional},
        {"name": "12:00:02", "message": intentional},
        {"name": "12:00:03", "message": intentional},
        {"name": "12:00:04", "message": "unexpected runtime failure"},
    ]
    baseline = {"12:00:00|pre-existing failure"}

    expected, unexpected = et._partition_new_hub_errors(
        logs, baseline, [intentional, intentional]
    )

    assert [entry["name"] for entry in expected] == ["12:00:01", "12:00:02"]
    assert [entry["name"] for entry in unexpected] == ["12:00:03", "12:00:04"]


def test_partition_hub_errors_decodes_exact_mcp1_envelope_without_broad_ignores():
    intentional = "Validation error in hub_update_device: invalid preference"

    def native_line(message):
        envelope = {
            "appId": "38", "generation": "g1", "id": "row-1",
            "entry": {"level": "error", "component": "server", "message": message},
        }
        return "app|38|MCP Rule Server|[MCP1] " + json.dumps(envelope)

    expected_row = {"name": "12:00:01", "message": native_line(intentional)}
    malformed = {"name": "12:00:02", "message": "app|38|MCP Rule Server|[MCP1] {truncated"}
    unrelated = {"name": "12:00:03", "message": native_line("unrelated runtime failure")}

    expected, unexpected = et._partition_new_hub_errors(
        [expected_row, malformed, unrelated], Counter(), [intentional]
    )

    assert expected == [expected_row]
    assert unexpected == [malformed, unrelated]

    # Baseline identity is the original raw line plus name, before nested-message
    # decoding, so an already-present envelope is never reclassified as fresh.
    baseline = Counter({f"{expected_row['name']}|{expected_row['message']}": 1})
    assert et._partition_new_hub_errors(
        [expected_row], baseline, [intentional]
    ) == ([], [])


def test_entries_new_since_snapshot_detects_identical_same_timestamp_duplicate():
    old = {"timestamp": 1234, "level": "error", "message": "same refusal"}
    unrelated = {"timestamp": 1235, "level": "error", "message": "other failure"}

    fresh = et._entries_new_since_snapshot([old, dict(old), unrelated], [old])

    assert fresh == [old, unrelated]


def test_watchdog_hub_logs_reads_direct_native_history_without_main_client(monkeypatch):
    class MainClientMustNotBeUsed:
        def call_tool(self, _name, _arguments):
            raise AssertionError("direct native-history proof consulted the main MCP app")

    logs = [
        {"name": "12:00:01", "level": "ERROR", "message": "first"},
        {"name": "12:00:02", "level": "ERROR", "message": "second"},
    ]
    posted = []

    def post(*args, **kwargs):
        posted.append((args, kwargs))
        return _watchdog_response(logs)

    runner = object.__new__(et.TestRunner)
    runner.client = MainClientMustNotBeUsed()
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", post)

    assert runner._watchdog_hub_logs(level="ERROR", limit=100) == logs
    assert posted == [((), {
        "url": "https://watchdog.invalid/mcp",
        "json": {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {
                "name": "hub_get_hub_logs",
                "arguments": {"level": "ERROR", "limit": 100},
            },
        },
        "timeout": 30,
    })]


def test_watchdog_hub_logs_accepts_successful_empty_native_history(monkeypatch):
    response = SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body({
                "logs": [], "count": 0, "totalParsed": 0,
                "appliedFilters": {"level": "error", "limit": 100},
            }),
        },
    )
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace()
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", lambda *args, **kwargs: response)

    assert runner._watchdog_hub_logs(level="ERROR", limit=100) == []


@pytest.mark.parametrize(
    "response_json",
    [
        {"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "failed"}},
        {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body({"success": False, "error": "native logs unavailable"}),
        },
        {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body(
                {"logs": [], "count": 0, "error": "unparseable /logs/past/json"}
            ),
        },
        {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body(
                {"logs": [], "count": 0, "message": "No log data returned from hub"}
            ),
        },
        {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body(
                {"success": True, "logs": []}, is_error=True
            ),
        },
        {
            "jsonrpc": "2.0", "id": 1,
            "result": _raw_tool_body({"success": True, "logs": "not-a-list"}),
        },
    ],
)
def test_watchdog_hub_logs_fails_closed_on_unusable_payload(monkeypatch, response_json):
    response = SimpleNamespace(
        raise_for_status=lambda: None,
        json=lambda: response_json,
    )
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace()
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", lambda *args, **kwargs: response)

    with pytest.raises(RuntimeError, match=r"watchdog.*logs"):
        runner._watchdog_hub_logs(level="ERROR", limit=100)


@pytest.mark.parametrize(
    ("lane", "test_status", "reset_failures", "expected"),
    [
        pytest.param("", "pass", [], True, id="all-tests-pass"),
        pytest.param("", "fail", [], False, id="test-failed"),
        pytest.param("", "skip", [], False, id="test-skipped"),
        pytest.param("", "pass", ["5329 via off: response lost"], False,
                     id="fixture-reset-unresolved"),
        pytest.param("ephemeral", "pass", ["5329 via off: response lost"], False,
                     id="fixture-reset-unresolved-on-ephemeral-hub"),
        pytest.param("ephemeral", "skip", [], False,
                     id="undocumented-skip-still-fails-on-ephemeral-hub"),
    ],
)
def test_print_summary_requires_tests_and_fixture_resets_to_succeed(
    lane, test_status, reset_failures, expected, capsys, monkeypatch,
):
    if lane:
        monkeypatch.setenv("E2E_LANE", lane)
    else:
        monkeypatch.delenv("E2E_LANE", raising=False)
    runner = object.__new__(et.TestRunner)
    runner.results = [{
        "group": "isolated", "name": "summary_probe", "status": test_status,
        "message": "probe result", "duration": 0.1,
    }]
    runner.client = SimpleNamespace(op_timings=[], continuation_timings=[])
    runner.throttle_bounces = 0
    runner.server_app_id = None
    runner._fixture_reset_failures = reset_failures
    runner._soft_passes = []

    assert runner._print_summary() is expected
    output = capsys.readouterr().out
    assert ("[FIXTURE-RESET]" in output) is bool(reset_failures)


@pytest.mark.parametrize("outcome", ["pass", "fail", "skip", "retry"])
@pytest.mark.parametrize("pace", [0, 0.5])
def test_run_one_paces_once_after_terminal_result(monkeypatch, outcome, pace):
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(_last_op=None)
    runner.results = []
    runner._soft_passes = []
    runner.pace_seconds = pace
    runner._backups_on = None
    sleeps = []
    monkeypatch.setattr(et.time, "sleep", sleeps.append)
    runner._settle_before_504_retry = lambda name: None
    attempts = []

    def probe():
        attempts.append(1)
        if outcome == "fail":
            raise AssertionError("value did not change")
        if outcome == "skip":
            raise et.SkipTest("fixture unavailable")
        if outcome == "retry" and len(attempts) == 1:
            raise et.RelayLostResponseError("504 Gateway Timeout")

    runner.probe = probe
    runner._run_one("isolated", "probe", "probe")
    assert sleeps == ([pace] if pace else [])
    assert len(attempts) == (2 if outcome == "retry" else 1)
    assert len(runner.results) == 1
    assert runner.results[0]["status"] == {"retry": "pass"}.get(outcome, outcome)


def _variable_delete_runner(defer):
    runner = object.__new__(et.TestRunner)
    runner.defer_native_deletes = defer
    runner.created_variable_names = ["BAT_E2E_v"]
    runner.deferred_variable_names = []
    calls = []

    def call_tool(tool, args):
        if args["tool"] == "hub_list_variables":
            return {"hubVariables": []}
        calls.append(args["args"]["name"])
        return {"success": True}

    runner.client = SimpleNamespace(_last_op=None, call_tool=call_tool)
    return runner, calls


@pytest.mark.parametrize("defer", [False, True])
def test_variable_fixture_deletes_defer_to_the_purge_only_when_flagged(defer):
    runner, calls = _variable_delete_runner(defer)
    runner._delete_variable_safe("BAT_E2E_v")
    assert calls == ([] if defer else ["BAT_E2E_v"])
    assert runner.created_variable_names == []
    assert runner.deferred_variable_names == (["BAT_E2E_v"] if defer else [])


def test_rule_engine_variables_are_deleted_inline_even_when_deferring():
    # They live in the MCP app's state, which the watchdog purge cannot reach.
    runner, calls = _variable_delete_runner(True)
    runner.rule_engine_variable_names = {"BAT_E2E_v"}
    runner._delete_variable_safe("BAT_E2E_v")
    assert calls == ["BAT_E2E_v"]
    assert runner.deferred_variable_names == []


def test_the_rule_engine_sweep_deletes_only_prefixed_rule_variables():
    runner = object.__new__(et.TestRunner)
    runner.created_variable_names = ["BAT_E2E_CondVar"]
    deleted = []

    def call_tool(tool, args):
        if args["tool"] == "hub_list_variables":
            return {"ruleVariables": [{"name": "BAT_E2E_CondVar"}, {"name": "Real"}],
                    "hubVariables": [{"name": "BAT_E2E_Hub"}]}
        deleted.append(args["args"]["name"])
        return {"success": True}

    runner.client = SimpleNamespace(_last_op=None, call_tool=call_tool)
    runner._sweep_rule_engine_variables()
    assert deleted == ["BAT_E2E_CondVar"]
    assert runner.created_variable_names == []


def test_a_test_rerun_deletes_its_deferred_variables_first(monkeypatch):
    runner, calls = _variable_delete_runner(True)
    runner._delete_variable_safe("BAT_E2E_v")
    runner.results = []
    runner._soft_passes = []
    runner.pace_seconds = 0
    runner._backups_on = None
    runner._settle_before_504_retry = lambda name: None
    monkeypatch.setattr(et.time, "sleep", lambda s: None)
    seen = []

    def probe():
        seen.append(list(calls))
        if len(seen) == 1:
            raise et.RelayLostResponseError("504 Gateway Timeout")

    runner.probe = probe
    runner._run_one("isolated", "probe", "probe")
    assert seen == [[], ["BAT_E2E_v"]]
    assert runner.deferred_variable_names == []


def test_permanent_variables_are_created_reset_or_recreated_only_when_needed():
    runner = object.__new__(et.TestRunner)
    runner.defer_native_deletes = True
    runner.created_variable_names = []
    runner.deferred_variable_names = []
    hub = {
        "E2E_PERM_Var_SvNumber": {"name": "E2E_PERM_Var_SvNumber", "type": "Number", "value": 0},
        "E2E_PERM_Var_SvString": {"name": "E2E_PERM_Var_SvString", "type": "String", "value": "stale"},
        "E2E_PERM_Var_SvBool": {"name": "E2E_PERM_Var_SvBool", "type": "String", "value": "false"},
    }
    calls = []

    def call_tool(tool, args):
        leaf, leaf_args = args["tool"], args.get("args", {})
        calls.append((leaf, leaf_args.get("name") or [v["name"] for v in leaf_args.get("variables", [])]))
        if leaf == "hub_list_variables":
            return {"hubVariables": list(hub.values())}
        if leaf == "hub_create_variable":
            hub.update({v["name"]: dict(v) for v in leaf_args["variables"]})
        if leaf == "hub_delete_variable":
            hub.pop(leaf_args["name"], None)
        return {"success": True}

    runner.client = SimpleNamespace(_last_op=None, call_tool=call_tool)
    names = runner._ensure_perm_variables("sv_number", "sv_string", "sv_bool", "sv_num_src")
    assert names["sv_bool"] == "E2E_PERM_Var_SvBool"
    writes = [c for c in calls if c[0] != "hub_list_variables"]
    assert writes == [
        ("hub_delete_variable", "E2E_PERM_Var_SvBool"),
        ("hub_create_variable", ["E2E_PERM_Var_SvBool", "E2E_PERM_Var_SvNumSrc"]),
        ("hub_set_variable", "E2E_PERM_Var_SvString"),
    ]
    assert runner.deferred_variable_names == []


def test_assertion_failure_is_not_attributed_to_successful_cleanup():
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(_last_op=("hub_delete_variable", 3.6, True))
    assert runner._last_op_str(AssertionError("copy stayed at zero")) == "assertion"


def test_limiter_summary_does_not_claim_retried_dispatches_passed(capsys):
    runner = object.__new__(et.TestRunner)
    runner.results = [{"group": "native_apps", "name": "blocked", "status": "fail",
                       "message": "dispatch remained blocked", "duration": 0.1}]
    runner.client = SimpleNamespace(op_timings=[], continuation_timings=[])
    runner.throttle_bounces = 6
    runner.server_app_id = "38"
    runner._fixture_reset_failures = []
    runner._soft_passes = []
    assert runner._print_summary() is False
    output = capsys.readouterr().out
    assert "retried dispatches passed" not in output
    assert "6 watchdog bounce(s)" in output


@pytest.mark.parametrize("unavailable", [False, True])
def test_load_snapshot_is_read_only_redacted_and_preserves_failed_operation(capsys, unavailable):
    calls = []
    failed_op = ("hub_call_rule", 1.2, False)
    runner = object.__new__(et.TestRunner)
    runner.server_app_id = "38"

    def call_tool(name, arguments=None):
        calls.append(name)
        runner.client._last_op = (name, 0.1, True)
        if unavailable:
            raise et.McpToolError(name, "sensitive response detail")
        if name == "hub_read_diagnostics":
            assert arguments["tool"] == "hub_get_performance_stats"
            return {"uptime": 42, "appStats": [
                {"id": i, "totalMs": 100 - i, "name": "private app name"} for i in range(6)
            ] + [{"id": 38, "totalMs": 5}]}
        assert name == "hub_get_hub_mesh"
        return {"hubMeshEnabled": True, "hubMeshToken": "secret token",
                "peers": [{"token": "peer secret"}], "sharedDevices": []}

    runner.client = SimpleNamespace(call_tool=call_tool, _last_op=failed_op,
                                    op_timings=[("hub_call_rule", 1.2, "native_apps/example", False)])
    runner._record_load_snapshot("before fixture edits", include_mesh=True)
    assert runner.client._last_op is failed_op
    output = capsys.readouterr().out
    assert "secret" not in output and "private app name" not in output and "sensitive" not in output
    snapshot = json.loads(output.split("LOAD_SNAPSHOT ", 1)[1])
    if unavailable:
        assert snapshot["diagnosticError"] == "McpToolError"
        assert calls == ["hub_read_diagnostics"]
    else:
        assert [row["id"] for row in snapshot["apps"]] == [0, 1, 2, 3, 4, 38]
        assert snapshot["mesh"]["enabled"] is True
        assert snapshot["mesh"]["peersCount"] == 1
        assert calls == ["hub_read_diagnostics", "hub_get_hub_mesh"]


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


_LIMITED = "RMUtils.sendAction failed: App 38 generates excessive hub load"


@pytest.mark.parametrize(
    "replies, bounces, bounce_ok, expected_calls, expected_bounces, expect_limited",
    [
        pytest.param([{"success": True}], 1, True, 1, 0, False, id="clean-call-never-bounces"),
        pytest.param([{"success": False, "error": _LIMITED}, {"success": True}], 1, True, 2, 1, False,
                     id="envelope-trip-bounces-and-retries"),
        pytest.param([et.McpToolError("hub_manage_rule_machine", _LIMITED), {"success": True}], 1, True, 2, 1, False,
                     id="raised-trip-bounces-and-retries"),
        pytest.param([{"success": False, "error": _LIMITED}] * 3, 2, True, 3, 2, True,
                     id="sticky-limiter-exhausts-bounce-rounds"),
        pytest.param([{"success": False, "error": _LIMITED}], 1, False, 1, 1, True,
                     id="failed-bounce-does-not-retry"),
        pytest.param([{"success": False, "error": "no such rule"}], 1, True, 1, 0, False,
                     id="other-failure-is-returned-not-bounced"),
    ],
)
def test_call_with_limiter_bounce_retries_only_limiter_trips(
    replies, bounces, bounce_ok, expected_calls, expected_bounces, expect_limited,
):
    calls, bounced = [], []
    replies = iter(replies)

    class Client:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            reply = next(replies)
            if isinstance(reply, Exception):
                raise reply
            return reply

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner._clear_load_throttle = lambda reason: bounced.append(reason) or bounce_ok

    res, limited = runner._call_with_limiter_bounce(
        "hub_manage_rule_machine", "hub_call_rule", {"ruleId": 7, "action": "actions"},
        "probe", bounces=bounces)

    assert calls == [("hub_manage_rule_machine",
                      {"tool": "hub_call_rule", "args": {"ruleId": 7, "action": "actions"}})] * expected_calls
    assert len(bounced) == expected_bounces
    assert all(reason.startswith("probe: ") for reason in bounced)
    assert (limited is not None) is expect_limited
    if not expect_limited:
        assert limited is None and isinstance(res, dict)


def test_call_with_limiter_bounce_propagates_non_limiter_errors():
    class Client:
        def call_tool(self, name, arguments):
            raise et.McpToolError("hub_manage_rule_machine", "rule 7 not found")

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner._clear_load_throttle = lambda reason: pytest.fail("a non-limiter error must not bounce")

    with pytest.raises(et.McpToolError, match="not found"):
        runner._call_with_limiter_bounce("hub_manage_rule_machine", "hub_call_rule", {}, "probe")


def _logs_body(payload):
    return {"resultType": "complete", "content": [{"text": json.dumps(payload)}]}


def test_capture_504_context_names_the_failed_call_and_anchors_the_window(capsys):
    """Label and window come from the exception's failed op and the failure time, not _last_op or now."""
    sent = []

    class Client:
        _last_op = ("hub_get_visual_rule", 0.8, True)

        def _send(self, method, params, headers=None):
            sent.append(params)
            if params["arguments"]["args"]["mode"] == "hub":
                return _logs_body({"logs": [{"name": "2026-09-26 06:07:01.100", "level": "WARN",
                                             "message": "app|38|MCP|[hubrt] slow GET"}]})
            return _logs_body({"entries": [{"timestamp": 1790402821000, "level": "debug",
                                            "component": "mrtr", "message": "slice scheduled"}]})

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner.server_app_id = "38"
    exc = et.McpError("504 Gateway Timeout on tools/call")
    exc._mcp_failed_op = ("hub_set_visual_rule", 10.3, False)
    failed_at = et.datetime(2026, 9, 26, 13, 5, 40, tzinfo=et.UTC)

    runner._capture_504_context("test_visual_rule_editor_form_lifecycle", exc, failed_at)

    out = capsys.readouterr().out
    assert [p["arguments"]["args"]["mode"] for p in sent] == ["hub", "mcp"]
    assert sent[0]["arguments"]["args"]["appId"] == 38
    assert sent[0]["arguments"]["args"]["since"] == "2026-09-26T13:03:29Z"   # failed_at - (10.3s + 120s)
    assert "failed op hub_set_visual_rule 10.3s [err]" in out
    assert "hub_get_visual_rule" not in out
    assert "[hubrt] slow GET" in out and "slice scheduled" in out
    assert runner.client._last_op == ("hub_get_visual_rule", 0.8, True)


@pytest.mark.parametrize("main_reply", [
    pytest.param("raise", id="main-read-504"),
    pytest.param(_logs_body({"status": "in_progress", "retryable": True}), id="snapshot-still-loading"),
    pytest.param(_logs_body({"logs": [], "error": "Unexpected log format from hub"}), id="error-body"),
    pytest.param({"resultType": "input_required", "requestState": "x"}, id="continuation-not-followed"),
    pytest.param({"isError": True, "content": [{"text": "{}"}]}, id="is-error"),
])
def test_capture_504_context_falls_back_to_the_watchdog_when_the_main_read_is_unusable(capsys, monkeypatch, main_reply):
    """A failed, still-loading or errored main read must fall back, never print as '0 entries'."""

    class Client:
        _last_op = None

        def _send(self, method, params, headers=None):
            if params["arguments"]["args"]["mode"] == "hub":
                if main_reply == "raise":
                    raise et.RelayLostResponseError("504 Gateway Timeout on tools/call")
                return main_reply
            return _logs_body({"entries": []})

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner.server_app_id = "38"
    runner.watchdog_url = "https://watchdog.invalid/mcp"
    monkeypatch.setattr(et.requests, "post", lambda *a, **k: _watchdog_response([
        {"name": "2026-09-26 06:07:01.100", "level": "warn", "message": "app|38|MCP Rule Server|slow slice"},
        {"name": "2026-09-26 06:07:01.200", "level": "info", "message": "app|5993|Watchdog|unrelated"},
    ]))

    runner._capture_504_context("probe")

    out = capsys.readouterr().out
    assert "hub log via watchdog" in out
    assert "slow slice" in out and "unrelated" not in out
    assert "hub log since" not in out


def test_capture_504_context_reports_both_hub_log_sources_unreadable(capsys, monkeypatch):
    class Client:
        _last_op = None

        def _send(self, method, params, headers=None):
            raise et.RelayLostResponseError("504 Gateway Timeout on tools/call")

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner.server_app_id = "38"
    runner.watchdog_url = ""

    runner._capture_504_context("probe")

    out = capsys.readouterr().out
    assert "hub log unreadable (main:" in out and "watchdog:" in out
    assert "mcp log read failed" in out


def test_capture_504_context_without_app_id_says_so(capsys):
    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(_last_op=None, _send=lambda *a, **k: pytest.fail("must not read logs without an app id"))
    runner.server_app_id = ""

    runner._capture_504_context("probe")

    assert "HUBITAT_APP_ID not set" in capsys.readouterr().out


@pytest.mark.parametrize("app, stranded", [
    pytest.param({"id": 46736, "type": "Button Controller-5.1", "parentId": 7242,
                  "name": "Button Controller-5.1: E2E_PERM_Button"}, True, id="relabelled-after-perm-button"),
    pytest.param({"id": 46737, "type": "Button Controller-5.1", "parentId": 7242,
                  "name": "Button Controller-5.1: BAT_E2E_WalkBtnDev"}, True, id="relabelled-after-bat-device"),
    pytest.param({"id": 7242, "type": "Button Controllers", "parentId": None,
                  "name": "Button Controllers"}, False, id="parent-app"),
    pytest.param({"id": 50, "type": "Button Controller-5.1", "parentId": None,
                  "name": "Button Controller-5.1: E2E_PERM_Button"}, False, id="no-parent"),
    pytest.param({"id": 17765, "type": "Button Controller-5.1", "parentId": 7242,
                  "name": "Button Controller-5.1"}, False, id="unlabelled"),
    pytest.param({"id": 60, "type": "Button Controller-5.1", "parentId": 7242,
                  "name": "Button Controller-5.1: Kitchen Pico"}, False, id="real-device"),
    pytest.param({"id": 61, "type": "Rule-5.1", "parentId": 216,
                  "name": "BAT_E2E_Rule"}, False, id="other-type"),
])
def test_is_stranded_button_controller(app, stranded):
    assert et._is_stranded_button_controller(app) is stranded


def test_run_artifact_suffix_is_stable_and_unique_per_github_attempt():
    first_attempt = {"GITHUB_RUN_ID": "31680286237", "GITHUB_RUN_ATTEMPT": "1"}
    second_attempt = {"GITHUB_RUN_ID": "31680286237", "GITHUB_RUN_ATTEMPT": "2"}

    assert et._run_artifact_suffix(first_attempt) == "31680286237_1"
    assert et._run_artifact_suffix(second_attempt) == "31680286237_2"
    assert et._run_artifact_suffix(second_attempt) == "31680286237_2"


def test_tool_error_payload_recovers_a_structured_refusal_and_tolerates_prose():
    refusal = {
        "success": False, "isError": True, "status": "too_many_writes_in_flight",
        "limit": 1,
        "active": [{"tool": "hub_create_variable", "startedAt": 1, "transport": "modern"}],
        "note": "1 write operation(s) are already active (cap 1, the maxConcurrentWrites setting).",
    }
    raised = et.McpToolError("hub_set_variable", json.dumps(refusal))

    assert et._tool_error_payload(raised) == refusal
    # A plain-prose tool error carries no envelope, so a caller can test .get("status")
    # without first proving the message was JSON.
    assert et._tool_error_payload(et.McpToolError("hub_get_variable", "not found")) == {}
    # A JSON scalar/array is not an envelope either.
    assert et._tool_error_payload(et.McpToolError("hub_get_variable", "[1, 2]")) == {}


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


def test_send_retains_structured_rpc_error_with_mixed_quotes(send_client):
    error = {"code": -32602, "message": (
        "Invalid params: Unknown preference 'probe'; "
        'See hub_get_tool_guide(section="update_device") for hub_update_device\'s reference.'
    )}
    client = send_client(lambda *args, **kwargs: SimpleNamespace(
        status_code=200, reason="OK", raise_for_status=lambda: None,
        json=lambda: {"jsonrpc": "2.0", "id": 1, "error": error},
    ))
    with pytest.raises(et.McpError) as raised:
        client._send("tools/call", {"name": "hub_update_device", "arguments": {"deviceId": "10"}})
    assert raised.value.rpc_error == error
    assert raised.value.rpc_error["message"].startswith("Invalid params: Unknown preference 'probe';")
    assert client._expected_validation_logs == [
        "Validation error in hub_update_device: Unknown preference 'probe'; "
        'See hub_get_tool_guide(section="update_device") for hub_update_device\'s reference.'
    ]


@pytest.mark.parametrize("name,args", [
    ("hub_manage_native_rules_and_apps", {"tool": "hub_set_native_app", "args": {
        "appType": "basic_rule", "name": "BAT", "confirm": True,
    }}),
    ("hub_manage_virtual_device", {"action": "create", "deviceType": "Virtual Switch", "confirm": True}),
    ("hub_manage_virtual_device", {"action": "delete", "deviceNetworkId": "test-dni", "confirm": True}),
    ("hub_update_device", {"deviceId": "88", "label": "Changed"}),
    ("hub_manage_devices", {"tool": "hub_update_device", "args": {"deviceId": "88", "label": "Changed"}}),
])
def test_send_does_not_retry_a_lost_first_mrtr_request(send_client, name, args):
    # The first request of an MRTR write starts the write; a fast one may already be
    # terminal when the relay drops the response, so a transport replay could run it twice.
    posts = []

    def post(*args, **kwargs):
        posts.append(kwargs["json"])
        return SimpleNamespace(status_code=504, reason="Gateway Timeout")

    client = send_client(post)

    with pytest.raises(et.RelayLostResponseError):
        client._send("tools/call", {"name": name, "arguments": args})

    assert len(posts) == 1
    assert client._transport_retries == 0


def test_send_retries_a_lost_state_bearing_mrtr_continuation(send_client):
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
        "name": "hub_manage_virtual_device",
        "arguments": {"action": "create", "deviceType": "Virtual Switch", "confirm": True},
        "requestState": "state-live",
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
        "relay_dropped_legs": 0,
        "continuation_rounds": 3,
        "logical_elapsed": 20.8,
        "max_answered_leg_elapsed": 8.1,
    }


def test_regular_e2e_mrtr_ceiling_ignores_a_relay_dropped_leg():
    # The observed live failure: five legs the server answered well under the ceiling,
    # plus one the relay dropped at 10.039s and the client replayed successfully. The
    # ceiling measures OUR response time, so a leg we never answered cannot breach it --
    # counting it there failed the run for the transport doing what MRTR absorbs.
    summary = et._summarize_mrtr_e2e_proof(
        continuation_rounds=4,
        result_type="complete",
        logical_elapsed=43.5,
        http_legs=[
            (4.374, 200, True),
            (6.429, 200, True),
            (8.307, 200, True),
            (8.632, 200, True),
            (10.039, 504, False),
            (3.045, 200, True),
        ],
        server_rounds=1,
    )

    assert summary["relay_dropped_legs"] == 1
    assert summary["max_answered_leg_elapsed"] == 8.632


def test_regular_e2e_mrtr_ceiling_catches_a_slow_2xx_leg_that_did_not_decode():
    # A 200 whose body fails to decode -- the relay's HTML error page under load, which
    # _send retries JSONDecodeError specifically to absorb. It is a leg the server ANSWERED,
    # so its duration is ours and must still trip the ceiling. Gating on `decoded` instead
    # of on the status dropped it out of the ceiling AND out of the relay-drop bound.
    with pytest.raises(AssertionError, match="per-leg relay ceiling"):
        et._summarize_mrtr_e2e_proof(
            continuation_rounds=2,
            result_type="complete",
            logical_elapsed=20.8,
            http_legs=[
                (0.2, 200, True),
                (9.6, 200, False),
                (3.0, 200, True),
                (2.0, 200, True),
            ],
            server_rounds=1,
        )


def test_regular_e2e_mrtr_ceiling_still_catches_a_slow_answered_leg():
    # The negative pin: excluding dropped legs must not blunt the ceiling for legs the
    # server DID answer, or the guard would be hollow.
    with pytest.raises(AssertionError, match="per-leg relay ceiling"):
        et._summarize_mrtr_e2e_proof(
            continuation_rounds=2,
            result_type="complete",
            logical_elapsed=20.8,
            http_legs=[
                (0.2, 200, True),
                (9.7, 200, True),
                (3.0, 200, True),
            ],
            server_rounds=1,
        )


def test_regular_e2e_mrtr_rejects_a_server_that_drops_most_legs():
    # Relay drops are absorbed, not ignored: a server tripping the relay as a rule is a
    # real regression the (now narrowed) ceiling can no longer see.
    with pytest.raises(AssertionError, match="lost at least as many legs to the relay"):
        et._summarize_mrtr_e2e_proof(
            continuation_rounds=2,
            result_type="complete",
            logical_elapsed=40.0,
            http_legs=[
                (0.2, 200, True),
                (10.1, 504, False),
                (10.2, 504, False),
                (10.3, 504, False),
                (3.0, 200, True),
                (2.0, 200, True),
            ],
            server_rounds=1,
        )


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
        ("hub_set_rule:update", 4.5, 1, [0.2, 4.0]),
        ("hub_set_rule:update", 6.0, 2, [0.1, 2.5, 3.0]),
        ("hub_call_rule", 1.2, 1, [0.3, 0.7]),
    ])

    assert rows == [
        {
            "operation": "hub_set_rule:update",
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
    assert client._last_request_state == "state-123"
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
            "hub_set_rule", {"operation": "update", "rule_id": 42, "args": {"changes": []}, "confirm": True}, flat=True,
        )

    assert client._last_continuation_rounds == 1
    assert client._last_result_type == "input_required"
    assert client._last_http_leg_seconds == [2.1, 9.8]
    assert client._last_logical_elapsed > 0
    assert client._last_http_legs == [
        (2.1, 200, True),
        (9.8, 504, False),
    ]


def test_failure_diagnostic_retains_transport_operation_after_successful_cleanup():
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")
    failure = et.RelayLostResponseError("504 Gateway Timeout on tools/call")

    def send(method, params=None, **_kwargs):
        if params["name"] == "hub_get_source":
            raise failure
        assert params["name"] == "hub_delete_file"
        return _raw_tool_body({"success": True})

    client._send = send
    with pytest.raises(et.RelayLostResponseError) as caught:
        try:
            client.call_tool("hub_get_source", {"type": "library", "id": "42"}, flat=True)
        finally:
            client.call_tool("hub_delete_file", {"fileName": "owned-backup", "confirm": True}, flat=True)

    runner = object.__new__(et.TestRunner)
    runner.client = client
    assert caught.value is failure
    assert client._last_op[0] == "hub_delete_file"
    assert runner._last_op_str(caught.value).startswith("hub_get_source ")
    assert runner._last_op_str(caught.value).endswith(" [err]")


@pytest.mark.parametrize("is_error", [True, False])
def test_tool_failure_telemetry_survives_successful_cleanup(is_error):
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")

    def send(method, params=None, **_kwargs):
        if params["name"] == "hub_call_rule":
            return {**_raw_tool_body({"success": False, "error": "excessive hub load"}),
                    "isError": is_error}
        return _raw_tool_body({"success": True})

    client._send = send
    caught = None
    try:
        result = client.call_tool("hub_call_rule", {"ruleId": [42], "action": "run"}, flat=True)
        assert not is_error and result["success"] is False
    except et.McpToolError as exc:
        assert is_error
        caught = exc
    finally:
        client.call_tool("hub_delete_variable", {"name": "owned"}, flat=True)

    assert client.op_timings[0][0] == "hub_call_rule"
    assert client.op_timings[0][3] is False
    assert client.op_timings[1][3] is True
    if is_error:
        runner = object.__new__(et.TestRunner)
        runner.client = client
        assert runner._last_op_str(caught).startswith("hub_call_rule ")
        assert runner._last_op_str(caught).endswith(" [err]")


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
    runner._native_rule_fixture_seq = 0
    runner._native_rule_parent_id = lambda: 28
    return runner


def test_create_native_rule_defaults_to_scalar_app_id(monkeypatch):
    calls = []
    runner = _native_rule_runner(SimpleNamespace(call_tool=lambda name, args:
        calls.append((name, args)) or {"success": True, "rule_id": 41}))
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")
    assert runner._create_native_rule("ScalarCreate") == 41
    assert runner.created_native_app_ids == ["41"]
    assert calls == [("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {
        "operation": "create", "args": {"parent_id": 28, "name": "BAT_E2E_ScalarCreate_run_1_1", "changes": []},
        "confirm": True}})]


def test_create_native_rule_return_result_preserves_create_envelope():
    envelope = {"success": True, "rule_id": 42, "results": [{"part": "action", "result": {"success": True}}]}
    changes = [et.native_log("fixture")]
    def call_tool(name, args):
        assert name == "hub_manage_rule_machine"
        assert args["args"]["args"]["changes"] == changes
        return envelope
    runner = _native_rule_runner(SimpleNamespace(call_tool=call_tool))
    assert runner._create_native_rule("TupleCreate", changes, return_result=True) == (42, envelope)
    assert runner.created_native_app_ids == ["42"]


@pytest.mark.parametrize("response", [
    {"success": False, "partial": True, "results": [{"result": {"success": True}}]},
    {"success": True, "partial": True},
    {"success": False, "error": "Native rule health is broken or unverified"},
])
def test_native_document_update_never_passes_partial_or_unverified_activation(response):
    runner = _native_rule_runner(SimpleNamespace(call_tool=lambda *_args: response))
    with pytest.raises(AssertionError):
        runner._set_rule(42, [et.native_log("must verify")])


def test_native_document_update_uses_direct_public_operation():
    calls = []
    runner = _native_rule_runner(SimpleNamespace(call_tool=lambda *args:
        calls.append(args) or {"success": True, "results": []}))
    changes = [et.native_log("once")]
    runner._set_rule(42, changes)
    assert calls == [("hub_set_rule", {"operation": "update", "rule_id": 42,
                                     "args": {"changes": changes}, "confirm": True})]


def test_create_native_rule_relay_lost_adoption_marks_bundled_fixture_for_readback(
    monkeypatch,
):
    calls = []
    fixture = [et.native_change("condition", capability="Switch", fields=[
        et.native_field("rDev_#", "capability.switch", ["88"]), et.native_field("state_#", "enum", "on")])]

    class FakeClient:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            if len(calls) == 1:
                raise et.RelayLostResponseError("504 Gateway Timeout")
            if len(calls) == 2:
                assert name == "hub_read_rules"
                return {"rules": [{"id": 43, "label": "BAT_E2E_AdoptedCreate_run_1_1"}]}
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
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")

    result = runner._create_native_rule(
        "AdoptedCreate",
        fixture,
        return_result=True,
    )

    assert result == (43, None)
    attempted_args = calls[0][1]["args"]
    assert attempted_args["args"]["name"] == "BAT_E2E_AdoptedCreate_run_1_1"
    assert attempted_args["args"]["changes"] == fixture
    runner._assert_switch_required_expression(43, 88)
    assert runner.created_native_app_ids == ["43"]


def test_create_native_rule_relay_lost_refuses_ambiguous_exact_label(monkeypatch):
    class FakeClient:
        def call_tool(self, _name, _arguments):
            if not hasattr(self, "called"):
                self.called = True
                raise et.RelayLostResponseError("504 Gateway Timeout")
            return {"rules": [
                {"id": 43, "label": "BAT_E2E_Ambiguous_run_1_1"},
                {"id": 44, "name": "BAT_E2E_Ambiguous_run_1_1"},
            ]}

    runner = _native_rule_runner(FakeClient())
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")

    with pytest.raises(AssertionError, match="ambiguous"):
        runner._create_native_rule("Ambiguous", return_result=True)


def test_create_native_rule_relay_lost_waits_for_delayed_exact_match(monkeypatch):
    calls = []

    class FakeClient:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            if len(calls) == 1:
                raise et.RelayLostResponseError("504 Gateway Timeout")
            if len(calls) == 2:
                return {"rules": [{"id": 99, "label": "some other rule"}]}
            assert name == "hub_read_rules"
            return {"rules": [{"id": 43, "label": "BAT_E2E_Delayed_run_1_1"}]}

    runner = _native_rule_runner(FakeClient())
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")

    result = runner._create_native_rule("Delayed", return_result=True)

    assert result == (43, None)
    assert [name for name, _arguments in calls] == [
        "hub_manage_rule_machine",
        "hub_read_rules",
        "hub_read_rules",
    ]


@pytest.mark.parametrize("persistent", [False, True])
def test_create_native_rule_recovers_lookup_transport_without_replaying_create(monkeypatch, persistent):
    posts = []
    client = et.HubitatMcpClient("http://hub.invalid", "1", "unused")
    client._gateway_members = {
        "hub_manage_rule_machine": {"hub_set_rule"},
        "hub_manage_native_rules_and_apps": {"hub_list_rules"},
        "hub_read_rules": {"hub_list_rules"},
    }
    client._gateway_route = {}
    client._read_only_catalog_tools = {"hub_read_rules"}

    def post(*args, **kwargs):
        params = kwargs["json"]["params"]
        posts.append(params)
        if params["arguments"]["tool"] == "hub_set_rule" or len(posts) == 2 or persistent:
            return SimpleNamespace(status_code=504, reason="Gateway Timeout")
        result = _raw_tool_body({"rules": [{"id": 43, "label": "BAT_E2E_ReadRecovery_run_1_1"}]})
        return SimpleNamespace(
            status_code=200, reason="OK", raise_for_status=lambda: None,
            json=lambda: {"jsonrpc": "2.0", "id": 1, "result": result},
        )

    client.session = SimpleNamespace(post=post)
    runner = _native_rule_runner(client)
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")

    if persistent:
        with pytest.raises(requests.HTTPError, match="504"):
            runner._create_native_rule("ReadRecovery", return_result=True)
        assert runner.created_native_app_ids == []
        assert len(posts) == 4  # one uncertain create, three bounded read attempts
    else:
        assert runner._create_native_rule("ReadRecovery", return_result=True) == (43, None)
        assert runner.created_native_app_ids == ["43"]
        assert len(posts) == 3
    assert [p["arguments"]["tool"] for p in posts].count("hub_set_rule") == 1
    assert all(p["name"] == "hub_read_rules" for p in posts[1:])


def test_create_native_rule_never_reissues_after_bounded_absence(monkeypatch):
    calls = []
    create_calls = 0

    class FakeClient:
        def call_tool(self, name, arguments):
            nonlocal create_calls
            calls.append((name, arguments))
            if name == "hub_manage_rule_machine":
                create_calls += 1
                if create_calls == 1:
                    raise et.RelayLostResponseError("504 Gateway Timeout")
                return {"success": True, "rule_id": 44}
            assert name == "hub_read_rules"
            return {"rules": []}

    runner = _native_rule_runner(FakeClient())
    monkeypatch.setattr(et.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(et, "_run_artifact_suffix", lambda: "run_1")

    with pytest.raises(et.RelayLostResponseError, match="unsafe same-label reissue"):
        runner._create_native_rule("LateCommit", return_result=True)

    result = runner._create_native_rule("LateCommit", return_result=True)

    assert result == (44, {"success": True, "rule_id": 44})
    attempted_creates = [arguments["args"]["args"]["name"] for name, arguments in calls
                         if name == "hub_manage_rule_machine"]
    assert attempted_creates == [
        "BAT_E2E_LateCommit_run_1_1",
        "BAT_E2E_LateCommit_run_1_2",
    ]
    assert runner.created_native_app_ids == ["44"]


def test_get_persisted_rule_config_requires_exact_target_app():
    calls = []

    class FakeClient:
        def call_tool(self, name, arguments):
            calls.append((name, arguments))
            return {"success": True, "app": {"id": 43}, "settings": {"state_1": "on"}}

    runner = _native_rule_runner(FakeClient())
    cfg = runner._get_persisted_rule_config(43)
    assert cfg["settings"] == {"state_1": "on"}
    assert calls == [("hub_read_apps_code", {
        "tool": "hub_get_app_config",
        "args": {"appId": 43, "includeSettings": True},
    })]


def test_require_create_envelope_requests_retry_after_relay_loss():
    runner = _native_rule_runner(None)

    with pytest.raises(et.RelayLostResponseError, match=r"504.*metadata"):
        runner._require_create_envelope(None, "ChangedTrig")


def test_require_create_envelope_preserves_delivered_response():
    runner = _native_rule_runner(None)
    envelope = {"success": True, "partial": False}

    assert runner._require_create_envelope(envelope, "ChangedTrig") is envelope


def test_get_persisted_rule_config_rejects_wrong_app():
    class FakeClient:
        def call_tool(self, _name, _arguments):
            return {"success": True, "app": {"id": 44}, "settings": {}}

    runner = _native_rule_runner(FakeClient())

    with pytest.raises(AssertionError, match="wrong app"):
        runner._get_persisted_rule_config(43)


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


@pytest.mark.parametrize("value,wanted,expected", [
    ({"88": "Switch"}, 88, True),
    ([{"id": 88}], 88, True),
    (188, 88, False),
    ({"188": "Switch"}, 88, False),
])
def test_setting_holds_exact_avoids_substring_device_matches(value, wanted, expected):
    assert et.TestRunner._setting_holds_exact(value, wanted) is expected

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
    """Gateway-wrapped native create keeps its explicit operation timing key."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {"operation": "create"}}) == "hub_set_rule:create"


def test_op_key_set_rule_unknown_operation_never_echoes_the_argument():
    # The key is printed in the run summary, so caller-supplied text must not reach it.
    for operation in ("secret-token-value", {"x": 1}, 7):
        assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {"operation": operation}}) == "hub_set_rule:other"


def test_op_key_gateway_set_rule_edit():
    """Native update timing stays separate from native create timing."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {"operation": "update", "rule_id": 5}}) == "hub_set_rule:update"


def test_op_key_gateway_other_subtool_uses_sub_tool():
    """A gateway call resolves to its sub-tool, not the gateway name."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}}) == "hub_list_rules"


def test_op_key_decodes_stringified_inner_args_before_classifying_edit():
    """Stringified gateway arguments retain the explicit update operation."""
    assert et._op_key(
        "hub_manage_rule_machine",
        {"tool": "hub_set_rule", "args": json.dumps({"operation": "update", "rule_id": 5})},
    ) == "hub_set_rule:update"


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


def _bundle_export_runner(bundles_after_install, write_calls):
    """A runner on a hub with no bundle: hub_list_bundles is empty until the fixture installs."""
    state = {"installed": False}

    class Client:
        def call_tool(self, name, arguments=None):
            tool = (arguments or {}).get("tool")
            if name == "hub_read_files" and tool == "hub_list_files":
                return {"files": [], "freeSpaceBytes": 1}
            if name == "hub_list_files" and (arguments or {}).get("folder") == "../etc":
                raise et.McpToolError(name, "folder must be a relative File Manager folder path such as 'webcore'")
            if name == "hub_read_apps_code" and tool == "hub_list_bundles":
                return {"source": "hub_api", "bundles": bundles_after_install if state["installed"] else []}
            if name == "hub_manage_code" and tool == "hub_install_bundle":
                state["installed"] = True
                return {"success": True}
            raise AssertionError(f"unexpected direct call: {name} {arguments}")

    def write_once(gateway, tool, args, label):
        write_calls.append((tool, args))
        if tool == "hub_export_bundle":
            return {"success": True, "bytes": 321, "fileName": args["saveAs"]}
        return {"success": True}

    runner = object.__new__(et.TestRunner)
    runner.client = Client()
    runner._bundle_id = None
    runner._soft_passes = []
    runner._current_test = "system_tools/test_export_bundle"
    runner._write_once = write_once
    runner._list_all_file_names = lambda name_filter=None: ([name_filter], True)
    return runner, state


def test_export_bundle_installs_and_removes_the_throwaway_fixture_when_the_hub_has_no_bundle(monkeypatch):
    monkeypatch.setenv("PR_RAW_BASE", "https://raw.githubusercontent.com/owner/repo")
    monkeypatch.setenv("PR_HEAD_SHA_RESOLVED", "c" * 40)
    write_calls = []
    runner, state = _bundle_export_runner([{"id": 9, "name": "throwaway", "namespace": "mcptest"}], write_calls)
    et.TestRunner.test_export_bundle(runner)
    assert state["installed"]
    assert write_calls[0] == ("hub_export_bundle", {"bundleId": "9", "saveAs": f"{et.PREFIX}bundle_export_9.zip"})
    assert write_calls[-1] == ("hub_delete_bundle", {"bundleId": "9", "confirm": True})


def test_export_bundle_skips_without_a_bundle_or_the_pr_raw_url(monkeypatch):
    monkeypatch.delenv("PR_RAW_BASE", raising=False)
    monkeypatch.delenv("PR_HEAD_SHA_RESOLVED", raising=False)
    write_calls = []
    runner, state = _bundle_export_runner([], write_calls)
    et.TestRunner.test_export_bundle(runner)
    assert not state["installed"] and write_calls == []


def test_export_bundle_uses_logical_writes_filtered_verification_and_exact_backup_cleanup():
    """The export path issues every write once, verifies through a targeted live
    listing, and deletes the exact backup returned by the first cleanup call."""
    bundle_id = "7"
    file_name = f"{et.PREFIX}bundle_export_{bundle_id}.zip"
    backup_name = f"{et.PREFIX}bundle_export_{bundle_id}_backup_123.zip"
    write_calls = []
    list_filters = []

    class NoDirectWritesClient:
        # Only the File Manager listing reads (the 2.5.2 listing-shape check) go direct.
        def call_tool(self, name, arguments=None):
            if name == "hub_read_files" and (arguments or {}).get("tool") == "hub_list_files":
                return {"files": [{"name": "a.txt", "type": "file"}], "freeSpaceBytes": 1}
            if name == "hub_list_files" and (arguments or {}).get("folder") == "../etc":
                raise et.McpToolError(name, "folder must be a relative File Manager folder path such as 'webcore'")
            raise AssertionError(f"unexpected direct call: {name} {arguments}")

    runner = object.__new__(et.TestRunner)
    runner.client = NoDirectWritesClient()
    runner._bundle_id = bundle_id
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


def test_bundle_fixture_contains_only_unused_app_code():
    fixtures = Path(__file__).resolve().parent / "fixtures"
    source_name = "mcptest.E2eThrowawayApp.groovy"
    with zipfile.ZipFile(fixtures / "mcp-e2e-throwaway-bundle.zip") as bundle:
        assert bundle.namelist() == [source_name, "install.txt", "update.txt"]
        for manifest_name in ("install.txt", "update.txt"):
            assert bundle.read(manifest_name).decode("utf-8").splitlines() == [
                "mcptest", "mcptest_e2e_throwaway", f"app {source_name}",
            ]
        source = bundle.read(source_name).decode("utf-8")
        assert source == (fixtures / "e2e-throwaway-app.groovy").read_text(encoding="utf-8")
        assert 'name: "Deadman Test Target Bundle"' in source
        assert 'namespace: "mcptest"' in source


def test_delete_bundle_uses_logical_write_helper(monkeypatch):
    monkeypatch.setenv("PR_RAW_BASE", "https://raw.invalid/repo")
    monkeypatch.setenv("PR_HEAD_SHA_RESOLVED", "abc123")
    write_calls = []
    # Snapshot before the install, readback after it, re-list after the delete.
    list_results = iter([
        {"bundles": []},
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


def test_build_capacity_recovery_is_the_conformance_bounce_seam(monkeypatch):
    """sdk_conformance_test._load_hub_config wires the SDK MRTR proof's
    capacity-recovery callable through sdk_conformance_helpers.build_capacity_recovery
    under CAPACITY_RECOVERY_CONFIG_KEY. Exercise that construction path here (the
    helpers module is importable without the SDK closure; the conformance script is
    not): the builder must hand back TestRunner's bounce, TestRunner construction
    must do no hub I/O, and with WATCHDOG_URL unset the bounce must decline
    deterministically without touching the network."""
    import sdk_conformance_helpers as sch

    monkeypatch.delenv("WATCHDOG_URL", raising=False)
    monkeypatch.delenv("HUBITAT_APP_ID", raising=False)
    client = et.HubitatMcpClient(
        hub_url="https://cloud.hubitat.com/api/00000000-0000-0000-0000-000000000000",
        app_id="38", access_token="dummy",
    )

    def _no_network(*args, **kwargs):
        raise AssertionError("bounce without WATCHDOG_URL must not touch the network")

    monkeypatch.setattr(et.requests, "post", _no_network)
    bounce = sch.build_capacity_recovery(et, client)
    assert bounce.__func__ is et.TestRunner._clear_load_throttle
    assert sch.CAPACITY_RECOVERY_CONFIG_KEY == "clear_load_throttle"
    assert bounce("interface pin") is False


@pytest.mark.parametrize("initial", ["on", "off"])
@pytest.mark.parametrize("matches", [True, False])
def test_poll_wall_clock_scenarios_use_observed_state_without_device_commands(monkeypatch, initial, matches):
    clock = [0.0]
    monkeypatch.setattr(et.time, "monotonic", lambda: clock[0])
    calls = []

    def call_tool(name, arguments):
        if name == "hub_get_info":
            return {"mcpClient": {"client": {"source": "local"}}}
        assert name == "hub_get_device_attribute", "poll scenarios must not depend on command delivery"
        calls.append(arguments.copy())
        if "expectedValue" not in arguments:
            return {"value": initial}
        expected = initial if matches else ("off" if initial == "on" else "on")
        assert arguments["expectedValue"] == expected
        if not matches:
            clock[0] += 2.0
        return {"success": matches, "timedOut": not matches, "polledCount": 1 if matches else 11,
                "finalValue": initial}

    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(call_tool=call_tool)
    runner.get_test_switch_id = lambda: "owned-switch"
    if matches:
        runner.test_poll_immediate_match()
    else:
        runner.test_poll_timeout()
    assert len(calls) == 2


@pytest.mark.parametrize("stays_stale,logs_fail", [(False, False), (True, False), (True, True)])
def test_lan_fixture_identity_waits_for_its_nonce_and_never_accepts_stale_observations(
    monkeypatch, capsys, stays_stale, logs_fail,
):
    clock = [0.0]
    monkeypatch.setattr(et.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(et.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    reads = []
    log_reads = []

    def call_tool(name, arguments):
        if name == "hub_get_logs":
            assert arguments == {"deviceId": "10", "level": "error", "limit": 10}
            log_reads.append(arguments.copy())
            runner.client._last_op = ("hub_get_logs", 0.2, not logs_fail)
            if logs_fail:
                raise et.McpToolError("hub_get_logs", "diagnostic unavailable")
            return {"logs": [{"message": "fixture command rejected"}]}
        assert name == "hub_get_device_attribute", "identity wait must not repeat the observer command"
        runner.client._last_op = ("hub_get_device_attribute", 0.1, True)
        assert arguments == {"deviceId": "10", "attribute": "nativeDeviceInfo"}
        reads.append(arguments.copy())
        native = {"nonce": "old", "deviceId": "other-fixture", "fixtureVersion": 2}
        if not stays_stale and len(reads) > 1:
            native.update(nonce="123", deviceId="10")
        return {"value": json.dumps(native)}

    runner = object.__new__(et.TestRunner)
    runner.client = SimpleNamespace(call_tool=call_tool)
    if stays_stale:
        with pytest.raises(AssertionError, match="observer did not complete for device 10, nonce 123") as failure:
            runner._wait_configuration_fixture_identity("10", "123")
        assert failure.value._mcp_failed_op == ("hub_get_device_attribute", 0.1, True)
        assert clock[0] == 10.0
        assert len(log_reads) == 1
        output = capsys.readouterr().out
        assert "CONFIGURATION_OBSERVER_LOGS device 10" in output
        assert ("diagnostic unavailable" if logs_fail else "fixture command rejected") in output
    else:
        result = runner._wait_configuration_fixture_identity("10", "123")
        assert result["nonce"] == "123" and result["deviceId"] == "10"
        assert len(reads) == 2
        assert not log_reads


@pytest.mark.parametrize("hub_url, actions, allowed", [
    ("https://cloud.hubitat.com/api/0000-uuid", "true", True),
    ("https://quiet-river-42.trycloudflare.com", "true", True),
    ("https://quiet-river-42.trycloudflare.com", None, False),
    ("https://quiet-river-42.trycloudflare.com/", "true", False),
    ("http://quiet-river-42.trycloudflare.com", "true", False),
    ("https://quiet-river-42.trycloudflare.com.example.net", "true", False),
    ("https://Quiet-River.trycloudflare.com", "true", False),
    ("https://192.168.1.5", "true", False),
])
def test_ci_guard_accepts_only_relay_or_ephemeral_tunnel_inside_actions(monkeypatch, hub_url, actions, allowed):
    if actions:
        monkeypatch.setenv("GITHUB_ACTIONS", actions)
    else:
        monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    if allowed:
        et.refuse_unless_ci_test_hub(hub_url)
    else:
        with pytest.raises(SystemExit) as refused:
            et.refuse_unless_ci_test_hub(hub_url)
        assert refused.value.code == 2


def test_ephemeral_refusal_and_banner_label_never_print_the_hub_url(monkeypatch, capsys):
    monkeypatch.setenv("E2E_LANE", "ephemeral")
    monkeypatch.delenv("GITHUB_ACTIONS", raising=False)
    with pytest.raises(SystemExit):
        et.refuse_unless_ci_test_hub("https://secret-host.example.net")
    assert "secret-host" not in capsys.readouterr().out
    assert et.hub_label("https://quiet-river-42.trycloudflare.com") == "ephemeral test hub"
    monkeypatch.setenv("E2E_LANE", "full")
    assert et.hub_label("https://cloud.hubitat.com/api/x") == "https://cloud.hubitat.com/api/x"


HUB_INFO = {
    "firmwareVersion": "2.5.2.129", "localIP": "192.168.255.2", "zigbeeId": "00AB12CD34EF5678",
    "hubData": {"hubUID": "uid-0f3c-91aa", "macAddress": "AA:BB:CC:DD:EE:FF", "gateway": "192.168.255.1"},
    "network": {"activeDnsServers": ["10.9.8.7"], "ipMode": "dhcp"},
    "healthAlerts": {"active": ["cloud"], "details": {"alertItems": [
        {"key": "cloud", "message": "Cloud connection is unavailable."}]}},
}


def test_hub_payload_redaction_is_ephemeral_only_and_keeps_counts(monkeypatch):
    monkeypatch.setattr(et, "_ephemeral_literals", set())
    monkeypatch.delenv("E2E_LANE", raising=False)
    assert et.redact_hub_payload(HUB_INFO) is HUB_INFO
    monkeypatch.setenv("E2E_LANE", "ephemeral")
    redacted = et.redact_hub_payload(HUB_INFO)
    for key in ("localIP", "zigbeeId", "hubData", "network"):
        assert redacted[key] == "<redacted>"
    assert redacted["healthAlerts"] == "<1 health-alert entries>"
    assert redacted["firmwareVersion"] == "2.5.2.129"
    assert et.redact_hub_payload([{"localIP": "1.2.3.4"}]) == [{"localIP": "<redacted>"}]
    assert "Cloud connection" not in str(et.redact_hub_payload(HUB_INFO["healthAlerts"]["details"], "healthAlerts"))


def test_ephemeral_stream_scrubs_learned_identity_macs_and_tunnel_hosts(monkeypatch):
    monkeypatch.setattr(et, "_ephemeral_literals", set())
    monkeypatch.setenv("E2E_LANE", "ephemeral")
    out, err = io.StringIO(), io.StringIO()
    monkeypatch.setattr(sys, "stdout", out)
    monkeypatch.setattr(sys, "stderr", err)
    et.install_ephemeral_redaction()
    et.install_ephemeral_redaction()  # idempotent: never double-wrapped
    assert isinstance(sys.stdout, et._RedactingStream) and sys.stdout._stream is out
    et.register_ephemeral_identity(HUB_INFO)
    print(f"FULL-FAILURE: {HUB_INFO} via https://quiet-river-42.trycloudflare.com/apps/api/1 de:ad:be:ef:00:01")
    print("Cloud connection is unavailable.", file=sys.stderr)
    text = out.getvalue() + err.getvalue()
    for leaked in ("192.168.255.2", "192.168.255.1", "10.9.8.7", "00AB12CD34EF5678", "uid-0f3c-91aa",
                   "AA:BB:CC:DD:EE:FF", "de:ad:be:ef:00:01", "trycloudflare", "Cloud connection"):
        assert leaked not in text, leaked
    assert "2.5.2.129" in text and "dhcp" in text


def test_redaction_is_inert_outside_the_ephemeral_lane(monkeypatch):
    monkeypatch.delenv("E2E_LANE", raising=False)
    out = io.StringIO()
    monkeypatch.setattr(sys, "stdout", out)
    et.install_ephemeral_redaction()
    assert sys.stdout is out


@pytest.mark.parametrize("details,offline", [
    ({"zwaveData": {"enabled": False, "healthy": True}}, True),
    ({"zwaveData": {"enabled": True, "healthy": False}}, True),
    ({"zwaveData": {"enabled": True, "healthy": True}}, False),
    ({"zwaveData": {"enabled": True}}, False),
    ({"zwaveData": {}}, False),
    # Unreadable details are no evidence of an offline radio, so the test stays strict.
    ({"source": "sdk_only"}, False),
    ({"zwaveData": "raw"}, False),
])
def test_zwave_radio_offline_needs_the_hubs_own_disabled_or_unhealthy_report(details, offline):
    assert et._zwave_radio_offline(details) is offline


@pytest.mark.parametrize("error,unavailable", [
    ("Failed to fetch /hub/zwave/getChildAndRouteInfoJson: status code: 503, reason phrase: Service Unavailable", True),
    ("Failed to fetch /hub/zwave/getChildAndRouteInfoJson: radio unavailable", True),
    ("Failed to fetch /hub/zwave/getChildAndRouteInfoJson: status code: 500, reason phrase: Server Error", False),
    (None, False),
])
def test_radio_unavailable_error_accepts_only_503_or_unavailable(error, unavailable):
    assert et._radio_unavailable_error(error) is unavailable


@pytest.mark.parametrize("lane, message, expected", [
    ("ephemeral", "WATCHDOG_URL/HUBITAT_APP_ID not set", True),
    ("ephemeral", "Z-Wave radio offline on this hub", True),
    ("ephemeral", "some other reason", False),
    ("", "WATCHDOG_URL/HUBITAT_APP_ID not set", False),
])
def test_accepted_skip_passes_only_documented_ephemeral_reasons(monkeypatch, lane, message, expected):
    if lane:
        monkeypatch.setenv("E2E_LANE", lane)
    else:
        monkeypatch.delenv("E2E_LANE", raising=False)
    assert et.accepted_skip(message) is expected


def _native_parent_client(pages, calls=None):
    inventory = iter(pages)
    def call_tool(name, arguments):
        if calls is not None:
            calls.append((name, arguments))
        if arguments["scope"] == "types":
            return {"apps": [{"id": 18, "name": "Rule Machine", "system": True}]}
        return next(inventory)
    return SimpleNamespace(call_tool=call_tool)


def test_native_parent_discovery_matches_native_type_id_across_all_pages():
    calls = []
    client = _native_parent_client([
        {"apps": [{"id": 1, "name": "Rule Machine", "appTypeId": 99, "parentId": None}], "nextCursor": "page-two"},
        {"apps": [{"id": 28, "name": "My Automations", "appTypeId": 18, "parentId": None, "hasChildren": False}]},
    ], calls)
    assert et._discover_native_rule_parent_id(client) == 28
    assert calls == [
        ("hub_list_apps", {"scope": "types"}),
        ("hub_list_apps", {"scope": "instances", "cursor": ""}),
        ("hub_list_apps", {"scope": "instances", "cursor": "page-two"}),
    ]


def test_native_parent_discovery_checks_later_pages_for_ambiguous_exact_types():
    client = _native_parent_client([
        {"apps": [{"id": 28, "name": "First", "appTypeId": 18, "parentId": None}], "nextCursor": "next"},
        {"apps": [{"id": 29, "name": "Second", "appTypeId": 18, "parentId": None}]},
    ])
    with pytest.raises(AssertionError, match="Expected one Rule Machine parent"):
        et._discover_native_rule_parent_id(client)


@pytest.mark.parametrize("row", [
    {"id": 28, "name": "Rule Machine", "appTypeId": 19, "parentId": None},
    {"id": 28, "name": "Rule Machine", "appTypeId": 18, "parentId": 9},
    {"id": "bad-id", "name": "Rule Machine", "appTypeId": 18, "parentId": None},
])
def test_native_parent_discovery_rejects_wrong_type_or_invalid_id(row):
    with pytest.raises(AssertionError):
        et._discover_native_rule_parent_id(_native_parent_client([{"apps": [row]}]))


def test_native_parent_discovery_refuses_repeated_inventory_cursor():
    client = _native_parent_client([{"apps": [], "nextCursor": "same"}] * 2)
    with pytest.raises(AssertionError, match="cursor repeated"):
        et._discover_native_rule_parent_id(client)


@pytest.mark.parametrize("types", [
    [], [{"id": 18, "name": "Rule Machine", "system": False}],
    [{"id": 18, "name": "Rule Machine", "system": True}] * 2,
])
def test_native_parent_discovery_requires_unique_builtin_type(types):
    client = SimpleNamespace(call_tool=lambda *_args: {"apps": types})
    with pytest.raises(AssertionError, match="built-in Rule Machine app type"):
        et._discover_native_rule_parent_id(client)


def test_missing_native_parent_is_installed_once_then_independently_rediscovered():
    installs = []
    client = _native_parent_client([
        {"apps": []},
        {"apps": [{"id": 28, "appTypeId": 18, "parentId": None}]},
    ])
    assert et._discover_native_rule_parent_id(client, install_missing=installs.append) == 28
    assert installs == [18]


def test_existing_native_parent_never_installs_another():
    client = _native_parent_client([{"apps": [{"id": 28, "appTypeId": 18, "parentId": None}]}])
    assert et._discover_native_rule_parent_id(client, install_missing=lambda *_args: pytest.fail("unexpected install")) == 28


def test_ambiguous_native_parent_does_not_install():
    client = _native_parent_client([{"apps": [
        {"id": 28, "appTypeId": 18, "parentId": None},
        {"id": 29, "appTypeId": 18, "parentId": None},
    ]}])
    with pytest.raises(AssertionError, match="Expected one Rule Machine parent"):
        et._discover_native_rule_parent_id(client, install_missing=lambda *_args: pytest.fail("unexpected install"))


def test_native_parent_install_does_not_repeat_when_rediscovery_fails():
    installs = []
    client = _native_parent_client([{"apps": []}, {"apps": []}])
    with pytest.raises(AssertionError, match="Expected one Rule Machine parent"):
        et._discover_native_rule_parent_id(client, install_missing=installs.append)
    assert installs == [18]


def test_edited_snapshot_copy_retargets_only_explicit_device_references_and_labels():
    payload = {"deviceReplacements": {"7": {"deviceLabel": "Old fixture", "deviceId": "7"}},
               "appReplacements": {"42": {"appLabel": "Rule"}},
               "appData": {"42": {"appSettings": [
                   {"name": "pick.7", "type": "capability.switch", "deviceList": {"7": "Old fixture"},
                    "value": ["7"], "deviceIdsForDeviceList": ["7"]},
                   {"name": "Counter", "type": "number", "value": 7}],
                   "state": {"actions": {"7": {"method": "getOnOffSwitch"}}, "actionList": ["7"],
                             "eval": {"0": ["7"]}, "capabsfalse": {"7": "Old fixture is on"},
                             "deviceIdsForDeviceList": {"pick.7": ["7"]}},
                   "subscriptions": [{"deviceId": 7}]}}}
    snapshot = {"captureFormat": "native-rule-edit-v1", "ruleId": 42,
                "nativeExport": json.dumps(payload),
                "configJson": {"settings": {"pick.7": {"7": "Old fixture"}, "Counter": 7}},
                "statusJson": {"appSettings": payload["appData"]["42"]["appSettings"]}}
    original = json.dumps(snapshot, indent=2)
    edited = json.loads(et.TestRunner._rm_edited_snapshot(original, 7, "Old fixture", 9, "New fixture"))
    native = json.loads(edited["nativeExport"])
    data = native["appData"]["42"]
    assert data["appSettings"][0]["deviceList"] == {"9": "New fixture"}
    assert data["appSettings"][0]["value"] == ["9"]
    assert data["state"]["deviceIdsForDeviceList"] == {"pick.7": ["9"]}
    assert data["subscriptions"] == [{"deviceId": 9}]
    assert native["deviceReplacements"] == {"9": {"deviceLabel": "New fixture", "deviceId": "9"}}
    assert data["state"]["capabsfalse"] == {"7": "New fixture is on"}
    assert data["state"]["actions"] == payload["appData"]["42"]["state"]["actions"]
    assert data["state"]["actionList"] == ["7"]
    assert data["state"]["eval"] == {"0": ["7"]}
    assert data["appSettings"][1]["value"] == 7
    assert edited["configJson"]["settings"] == {"pick.7": {"9": "New fixture"}, "Counter": 7}
    assert edited["statusJson"]["appSettings"][0]["deviceIdsForDeviceList"] == ["9"]
    assert original == json.dumps(snapshot, indent=2)


def test_run_one_sets_pre_edit_backups_per_test_for_any_caller(monkeypatch):
    # The ephemeral provider calls _run_one directly, so the per-test backup toggle lives there.
    writes = []

    def call_tool(name, args):
        assert name == "hub_update_mcp_settings"
        writes.append(args["settings"]["backupBeforeRuleEdits"])
        return {"success": True, "updated": {"backupBeforeRuleEdits": args["settings"]["backupBeforeRuleEdits"]}}

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool))
    monkeypatch.setattr(et.TestRunner, "BACKUP_PROVING_TESTS", frozenset({"test_proves"}))
    ran = []
    monkeypatch.setattr(runner, "_run_one_attempts", lambda group, name, method: ran.append((method, runner._backups_on)))
    for method in ("test_plain", "test_proves", "test_plain", "test_plain"):
        runner._run_one("native_apps", method, method)
    assert ran == [("test_plain", None), ("test_proves", True), ("test_plain", False), ("test_plain", False)]
    assert writes == [True, False]
    # A test that turns them on itself leaves that state for the next one to correct.
    runner._rule_edit_backups(True)
    runner._run_one("native_apps", "test_plain", "test_plain")
    assert writes[-2:] == [True, False]


def test_run_one_records_one_failure_when_the_backup_toggle_fails(monkeypatch):
    runner = et.TestRunner(SimpleNamespace(call_tool=lambda name, args: {"success": False}))
    monkeypatch.setattr(et.TestRunner, "BACKUP_PROVING_TESTS", frozenset({"test_proves"}))
    monkeypatch.setattr(runner, "_run_one_attempts", lambda *a: pytest.fail("the test must not run"))
    runner._run_one("native_apps", "test_proves", "test_proves")
    assert [(r["name"], r["status"]) for r in runner.results] == [("test_proves", "fail")]
    assert "could not turn pre-edit backups on" in runner.results[0]["message"]
