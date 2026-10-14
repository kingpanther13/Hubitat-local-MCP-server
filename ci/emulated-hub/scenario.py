"""HTTP integration scenario; run by EmulatedHubHttpSpec on GitHub only."""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests"))
from e2e_test import HubitatMcpClient, McpToolError


def rejected(client, tool, args):
    try:
        result = client.call_tool(tool, args)
    except McpToolError:
        return
    assert isinstance(result, dict) and result.get("success") is False, result


def run(base_url, mode):
    client = HubitatMcpClient(base_url, "1", "software-only-token")
    client.session.trust_env = False
    initialize = client._send("initialize", {"protocolVersion": "2026-07-28", "capabilities": {},
                                             "clientInfo": {"name": "software-hub", "version": "1"}})
    assert initialize["protocolVersion"] == "2025-11-25"  # production initialize is legacy-capped
    catalog = client.list_tools()["tools"]
    assert catalog and len({t["name"] for t in catalog}) == len(catalog)
    if mode == "gateway":
        assert any(t["name"] == "hub_manage_variables" for t in catalog)
    else:
        assert any(t["name"] == "hub_set_variable" for t in catalog)
    assert client.call_tool("hub_list_devices", {"filter": "virtual"})["total"] == 0
    assert client.call_tool("hub_list_files", {})["total"] == 0

    # Invalid transport input and modern metadata are handled by production dispatch.
    malformed = client.raw_request({"id": 91, "method": "ping"})
    assert malformed.json()["error"]["code"] == -32600
    mismatch = client.raw_request({"jsonrpc": "2.0", "id": 92, "method": "ping"},
                                  headers={"MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/list"})
    assert mismatch.status_code == 400 and "error" in mismatch.json()

    # Native hub variables persist through the simulated platform API.
    assert client.call_tool("hub_get_variable", {"name": "software_counter"})["value"] == 1
    assert client.call_tool("hub_set_variable", {"name": "software_counter", "value": 7})["source"] == "hub"
    assert client.call_tool("hub_get_variable", {"name": "software_counter"})["value"] == 7
    assert client.call_tool("hub_list_variables", {})["hubVariables"][0]["value"] == 7

    # Every virtual device is created by production code, commands mutate a platform event store.
    devices = []
    for index in range(2):
        result = client.call_tool("hub_manage_virtual_device", {"action": "create", "deviceType": "Virtual Switch",
                                  "deviceLabel": f"Software {index}", "deviceNetworkId": f"software-{index}", "confirm": True})
        assert result["success"] is True and not result.get("partialSuccess"), result
        devices.append(result["device"]["id"])
    first = client.call_tool("hub_list_devices", {"filter": "virtual", "cursor": "", "limit": 1})
    assert first["total"] == 2 and len(first["devices"]) == 1 and first["nextCursor"] == "1"
    second = client.call_tool("hub_list_devices", {"filter": "virtual", "cursor": first["nextCursor"], "limit": 1})
    assert len(second["devices"]) == 1 and first["devices"][0]["id"] != second["devices"][0]["id"]
    for value in ("on", "off", "on"):
        result = client.call_tool("hub_call_device_command", {"deviceId": devices[0], "command": value})
        assert result["success"] is True
        assert client.call_tool("hub_get_device_attribute", {"deviceId": devices[0], "attribute": "switch"})["value"] == value
    assert client.call_tool("hub_get_device_attribute", {"deviceId": devices[1], "attribute": "switch"})["value"] == "off"
    rejected(client, "hub_call_device_command", {"deviceId": devices[0], "command": "unsupportedCommand"})
    rejected(client, "hub_manage_virtual_device", {"action": "create", "customDriver": {"namespace": "software", "name": "Not implemented"},
                                                "deviceLabel": "Unsupported", "deviceNetworkId": "unsupported", "confirm": True})
    rejected(client, "hub_get_device", {"deviceId": "999"})  # existing, unselected fixture

    # Settings writes persist through the platform and change subsequent permissions.
    rejected(client, "hub_update_mcp_settings", {"settings": {"enableWrite": False}, "confirm": True})
    changed = client.call_tool("hub_update_mcp_settings", {"settings": {"bypassDeviceAllowlist": True}, "confirm": True})
    assert changed["success"] is True
    assert client.call_tool("hub_get_device_attribute", {"deviceId": "999", "attribute": "switch"})["value"] == "off"
    client.call_tool("hub_update_mcp_settings", {"settings": {"bypassDeviceAllowlist": False}, "confirm": True})
    rejected(client, "hub_get_device", {"deviceId": "999"})
    client.call_tool("hub_update_mcp_settings", {"settings": {"enableRead": False}, "confirm": True})
    rejected(client, "hub_get_variable", {"name": "software_counter"})
    client.call_tool("hub_update_mcp_settings", {"settings": {"enableRead": True}, "confirm": True})
    assert client.call_tool("hub_get_variable", {"name": "software_counter"})["value"] == 7

    # File storage persists across calls; overwrites retain production backup behavior.
    text = "software hub: café\nsecond line"
    assert client.call_tool("hub_write_file", {"fileName": "software.txt", "content": text, "confirm": True})["success"] is True
    assert client.call_tool("hub_read_file", {"fileName": "software.txt"})["content"] == text
    overwrite = client.call_tool("hub_write_file", {"fileName": "software.txt", "content": "updated", "confirm": True})
    assert overwrite["backupFile"]
    assert client.call_tool("hub_read_file", {"fileName": overwrite["backupFile"]})["content"] == text
    assert client.call_tool("hub_delete_file", {"fileName": "software.txt", "confirm": True})["success"] is True
    rejected(client, "hub_read_file", {"fileName": "software.txt"})
    for index in range(2):
        assert client.call_tool("hub_manage_virtual_device", {"action": "delete", "deviceNetworkId": f"software-{index}", "confirm": True})["success"] is True
    assert client.call_tool("hub_list_devices", {"filter": "virtual"})["total"] == 0

    # This software boundary has no native admin backend; failure is required.
    rejected(client, "hub_get_device", {"deviceId": "not-an-id"})
    print(json.dumps({"mode": mode, "passed": True, "domains": ["protocol", "devices", "variables", "settings", "files"]}))
    client.session.close()


if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2])
