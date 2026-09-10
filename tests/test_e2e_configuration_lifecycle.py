"""Exercise the matrix's real setup/finally path without a hub."""

import json
from copy import deepcopy
from types import SimpleNamespace

import e2e_test as et
import pytest


@pytest.mark.parametrize("show,status,failure", [
    (False, "", "body"), (True, None, "body"), (False, "switch", "body"),
    (True, "switch", "body"), (False, "", "setup-error"), (False, "", "setup-noop"),
])
@pytest.mark.parametrize("path,authorized", [("standalone-sdk", True), ("standalone-bypass", False)])
def test_matrix_establishes_pane_values_and_restores_actual_baseline(show, status, failure, path, authorized):
    expected = {
        "probeBool": ("bool", False), "probeNumber": ("number", 3),
        "probeText": ("text", "original saved text"), "probeEnum": ("enum", "eco"),
        "probeMultiple": ("enum", ["red"]),
    }
    info = {
        "deviceId": "10", "fixtureVersion": 2, "deviceTypeId": 100, "parentAppId": None,
        "showOnHome": show, "defaultCurrentState": status, "enabled": True,
        "largeReadProbePresent": False, "roomName": None, "dataValues": {"configurationProbe": "original"},
        "maxEvents": 40, "maxStates": 30, "spammyThreshold": 300, "notes": "", "tags": [],
        "defaultIcon": "", "name": "Fixture", "label": "Fixture", "deviceNetworkId": "fixture-10",
        "retryEnabled": False, "controllerType": "", "groupId": None,
    }
    original = deepcopy(info)
    writes, body_starts = [], []
    snapshot = {}

    def configuration():
        return {
            "preferenceRead": {"status": "complete"},
            "preferences": [{"name": name, "type": kind, "value": value, "valuePresent": True,
                             "valueStatus": "stored", "defaultValue": True, "range": "0..20",
                             "multiple": isinstance(value, list), "options": ["eco"]}
                            for name, (kind, value) in expected.items()],
            "editableFields": [{"name": key, "value": value, "writable": True} for key, value in info.items()]
                              + [{"name": "room", "value": info["roomName"], "writable": True}],
        }

    def call_tool(name, args=None):
        args = args or {}
        if "tool" in args:
            name, args = args["tool"], args.get("args", {})
        assert name == "hub_get_device", name
        if args.get("mode") == "configuration":
            return configuration()
        if args.get("mode") == "details":
            assert args["sections"] == ["identity"]
            assert args["fields"] == ["groupId", "controllerType"]
            return {
                "sectionRead": {"identity": {"status": "complete"}},
                "sections": {"identity": {key: info[key] for key in args["fields"]}},
            }
        return {"id": "10", "name": "Fixture", "label": "Fixture", "room": None,
                "capabilities": [], "commands": [], "attributes": [
                    {"name": key, "value": json.dumps(value)} for key, value in snapshot.items()]}

    def write_once(gateway, name, args, purpose):
        if name == "hub_call_device_command":
            assert args["command"] == "captureConfiguration"
            nonce = args["parameters"][0]
            snapshot.update(nativeDeviceInfo={**deepcopy(info), "nonce": nonce}, nativeConfiguration={
                "deviceId": "10", "fixtureVersion": 2, "nonce": nonce,
                "settings": [{"name": k, "type": kind, "value": value} for k, (kind, value) in expected.items()],
                "inputValues": [{"name": k, "inputValue": value} for k, (_, value) in expected.items()],
                "runtimeMultiple": ["red"], "runtimeMultipleIsList": True,
            })
            return {"success": True}
        assert name == "hub_update_device", name
        patch = {k: v for k, v in args.items() if k not in ("deviceId", "confirm", "preferences")}
        writes.append(deepcopy(patch))
        setup = set(patch) == {"showOnHome", "defaultCurrentState"}
        body = str(patch.get("notes", "")).startswith("Native persistence:")
        if body:
            body_starts.append((info["showOnHome"], info["defaultCurrentState"]))
        if not (setup and failure == "setup-noop"):
            for key, value in patch.items():
                info["roomName" if key == "room" else key] = deepcopy(value)
        if setup and failure == "setup-error":
            raise RuntimeError("injected setup failure after mutation")
        if body:
            raise RuntimeError("injected matrix failure after mutation")
        changes = [{"property": k} for k in patch if k != "dataValues"]
        changes.extend({"property": f"dataValue.{key}"} for key in patch.get("dataValues", {}))
        return {"success": True, "mrtr": {"continued": True}, "changes": changes}

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool, app_id="38"))
    runner._write_once = write_once
    message = {"body": "injected matrix failure", "setup-error": "injected setup failure",
               "setup-noop": "Native showOnHome"}[failure]
    with pytest.raises((RuntimeError, AssertionError), match=message):
        runner._device_configuration_profile(
            {"path": path, "label": "Fixture", "authorized": authorized}, "10", "11",
            {"version": 2, "driver": "Primary", "replacementDriver": "Alternate", "nativeFields": {}},
            {"Primary": 100, "Alternate": 101}, expected, "Fixture room",
        )
    assert not runner._fixture_reset_failures
    assert body_starts == ([(True, "switch")] if failure == "body" else [])
    assert len(writes) == (2 if (show, status) == (True, "switch") else 3 if failure == "body" else 2)
    if failure == "body":
        assert writes[-2]["dataValues"] == {"configurationProbe": "changed"}
    assert writes[-1]["dataValues"] == {"configurationProbe": "original"}
    # Hubitat represents the None selection as either null or empty text.
    for key in ("defaultCurrentState", "roomName"):
        original[key] = original[key] or ""
        info[key] = info[key] or ""
    assert info == original
