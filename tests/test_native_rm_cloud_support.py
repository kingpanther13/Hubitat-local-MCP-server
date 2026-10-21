"""Shared acceptance must use ordinary MCP calls rather than native-admin routing."""

import unittest
from types import SimpleNamespace

import pytest
from native_rm_cloud_support import CloudCaseMixin, CloudMcpClient, read_cloud_section


def test_cloud_client_ignores_native_origin_inputs_and_uses_existing_client(monkeypatch):
    monkeypatch.setenv("HUB_URL", "http://unrelated.invalid")
    calls = []
    client = SimpleNamespace(endpoint="https://mcp.invalid/apps/api/7/mcp",
                             call_tool=lambda name, arguments: calls.append((name, arguments)) or {"value": "on"})
    cloud = CloudMcpClient(SimpleNamespace(client=client, native_hub_url="http://also-unrelated.invalid"))
    assert cloud.tool("hub_get_device_attribute", deviceId="3", attribute="switch") == {"value": "on"}
    assert calls == [("hub_get_device_attribute", {"deviceId": "3", "attribute": "switch"})]
    with pytest.raises(AssertionError, match="standalone diagnostics"):
        cloud.native("/installedapp/statusJson/42")


def test_device_projection_uses_actual_public_sections():
    sections = {"identity": {"id": "3", "label": "fixture", "virtual": True},
                "attributes": {"currentStates": {"switch": {"value": "on"}}, "capabilities": ["Switch"]},
                "metadata": {"deviceTypeId": 12}, "commands": [{"name": "on", "parameters": []}],
                "relationships": {"device": {"parentAppId": 7}}, "integrations": {"device": {"linkedDevice": False}}}
    client = SimpleNamespace(endpoint="https://mcp.invalid", call_tool=lambda *_args: {"id": "3", "sections": sections})
    result = CloudMcpClient(SimpleNamespace(client=client)).device_metadata("3")
    assert result["sections"] is sections
    assert result["commands"] is sections["commands"]
    assert result["device"]["currentStates"] == sections["attributes"]["currentStates"]
    assert result["device"]["deviceTypeId"] == 12
    assert result["device"]["parentAppId"] == 7


@pytest.mark.parametrize("corruption", [None, "revision", "duplicate", "premature"])
def test_cloud_section_read_requires_complete_consistent_actual_pages(corruption):
    calls = []
    def call(name, **arguments):
        calls.append((name, arguments))
        cursor = arguments["args"]["cursor"]
        names = ["first"] if not cursor else ["second"]
        if cursor and corruption == "duplicate":
            names = ["first"]
        return {"rule_id": 42, "settings": [{"name": value, "type": "bool", "value": False} for value in names],
                "page": {"offset": 0 if not cursor else 1, "limit": 50, "total": 2,
                         "revision": "changed" if cursor and corruption == "revision" else "stable"},
                "nextCursor": None if cursor or corruption == "premature" else "opaque-token"}
    if corruption is None:
        assert [row["name"] for row in read_cloud_section(call, 42, "settings")] == ["first", "second"]
        assert calls[1][1]["args"]["cursor"] == "opaque-token"
    else:
        with pytest.raises(AssertionError):
            read_cloud_section(call, 42, "settings")


def test_cloud_case_refuses_to_fabricate_native_state_or_status():
    case = type("CloudCase", (CloudCaseMixin, unittest.TestCase), {})()
    with pytest.raises(AssertionError, match="Raw native app state"):
        case.state(42)
    with pytest.raises(AssertionError, match="installed_app"):
        case.status(42)


def test_cloud_commands_use_public_tools_and_declared_string_arguments():
    calls = []
    case = type("CloudCase", (CloudCaseMixin, unittest.TestCase), {})()
    case.fixtures, case.original_values = {"temperature": "3"}, {}
    case.call = lambda name, **arguments: calls.append((name, arguments)) or {"success": True}
    case.command("temperature", "setTemperature", [72.5], remember=False)
    assert calls == [("hub_call_device_command", {"deviceId": "3", "command": "setTemperature", "parameters": ["72.5"]})]


def test_cloud_case_page_text_retains_real_native_messages_and_independent_configuration():
    calls = []
    def call(name, **arguments):
        calls.append((name, arguments))
        if name == "hub_get_app_config":
            return {"success": True, "app": {"id": 42}, "settings": {"origLabel": "proof"}}
        assert name == "hub_set_rule" and arguments["operation"] == "form"
        return {"success": True, "messages": ["Run Actions: order sentinel", "IF (temperature of Lamp is off)"], "controls": []}
    case = type("CloudCase", (CloudCaseMixin, unittest.TestCase), {})()
    case.call = call
    text = case.page_text(42)
    assert all(value in text for value in ("Run Actions:", "IF (", "temperature of", "is off", "proof"))
    assert calls[0] == ("hub_get_app_config", {"appId": "42", "includeSettings": True})
    assert calls[1][1]["args"] == {"steps": [{"kind": "open", "page": "mainPage"}]}


def test_representative_shared_case_uses_real_cloud_endpoint_shape_without_native_http(monkeypatch):
    import urllib.request

    import e2e_native_rule_integration as integration
    import requests

    monkeypatch.delenv("HUB_URL", raising=False)
    monkeypatch.delenv("HUB_UI_COOKIE", raising=False)
    monkeypatch.setenv("NATIVE_RULE_FIXTURES", '{"switch":"3"}')
    monkeypatch.setenv("NATIVE_RULE_FIXTURE_LABEL_PREFIX", "E2E_Authorized_")
    monkeypatch.setenv("NATIVE_RULE_PARENT_ID", "28")
    monkeypatch.setenv("NATIVE_RULE_MODE_ID", "1")
    monkeypatch.setenv("NATIVE_RULE_FIXTURE_DRIVERS", "{}")
    monkeypatch.setattr(requests, "get", lambda *_args, **_kwargs: pytest.fail("native HTTP GET"))
    monkeypatch.setattr(requests, "post", lambda *_args, **_kwargs: pytest.fail("native HTTP POST"))
    monkeypatch.setattr(urllib.request.OpenerDirector, "open", lambda *_args, **_kwargs: pytest.fail("native urllib HTTP"))
    origin = "https://cloud.hubitat.com/api/00000000-0000-0000-0000-000000000000"
    endpoint = origin + "/apps/7/mcp"
    calls = []
    sections = {"identity": {"id": "3", "label": "E2E_Authorized_Switch", "virtual": True},
                "attributes": {"currentStates": {"switch": {"value": "on"}}}, "commands": [],
                "relationships": {"device": {}}, "integrations": {"device": {}}, "metadata": {}}
    def call_tool(name, arguments):
        calls.append((name, arguments))
        if name == "hub_get_device":
            return {"id": "3", "sections": sections}
        if name == "hub_set_rule":
            return {"rule_id": 42, "validation": {"structuralIssues": []},
                    "nativeHealth": {"available": True, "broken": False, "paused": False, "stopped": False}}
        if name == "hub_call_rule":
            return {"success": True}
        assert name == "hub_get_device_attribute"
        return {"value": "on"}
    runner = SimpleNamespace(client=SimpleNamespace(hub_url=origin, endpoint=endpoint, call_tool=call_tool))
    class RepresentativeCase(integration.ParityCase):
        def test_cloud_goal(self):
            self.assertFalse(self.native_diagnostics)
            self.assertEqual(self.client.mcp_url, endpoint)
            self.run_actions(42)
            self.assertEqual(self.attribute("switch", "switch"), "on")
    monkeypatch.setitem(integration.SUITES, "cloud-unit", (__name__, "RepresentativeCase"))
    monkeypatch.setattr(integration.importlib, "import_module", lambda _name: SimpleNamespace(RepresentativeCase=RepresentativeCase))
    integration.run_native_rule_case(runner, "cloud-unit", "test_cloud_goal")
    assert [name for name, _arguments in calls] == ["hub_get_device", "hub_set_rule", "hub_call_rule", "hub_get_device_attribute"]


def test_raw_cloud_rpc_validation_is_accounted_once_without_rewriting_envelope():
    import e2e_test as existing

    envelope = {"jsonrpc": "2.0", "id": 1, "error": {"code": -32602, "message": "Invalid params: wrong original arguments"}}
    payload = {"method": "tools/call", "params": {"name": "hub_manage_rule_machine",
               "arguments": {"tool": "hub_set_rule", "args": {"operation": "update"}}}}
    runner = existing.TestRunner.__new__(existing.TestRunner)
    response = SimpleNamespace(raise_for_status=lambda: None, json=lambda: envelope)
    runner.client = SimpleNamespace(endpoint="https://cloud.hubitat.com/api/uuid/apps/7/mcp",
                                    raw_request=lambda *_args, **_kwargs: response, _expected_validation_logs=[])
    cloud = CloudMcpClient(runner)
    assert cloud.request(cloud.mcp_url, "POST", payload) is envelope
    assert runner.client._expected_validation_logs == [existing._validation_log_expectation(
        payload["method"], payload["params"], envelope["error"])]
