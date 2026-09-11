"""Exercise the matrix's real setup/finally path without a hub."""

import json
from copy import deepcopy
from types import SimpleNamespace

import e2e_test as et
import pytest


@pytest.mark.parametrize("show,status,failure", [
    (False, "", "body"), (True, None, "body"), (False, "switch", "body"),
    (True, "switch", "body"), (False, "", "setup-error"), (False, "", "setup-noop"),
    (False, "", "after-edit"), (False, "", "restore-confirmation"),
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
        "retryEnabled": False, "controllerType": "", "groupId": None, "roomId": None, "zigbeeId": None,
    }
    original = deepcopy(info)
    writes, body_starts, files = [], [], []
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
        if name in ("hub_write_file", "hub_delete_file"):
            # The restore recipe the cleanup layer replays when the in-test finally never runs.
            assert args.get("confirm") is True and args.get("fileName") == f"e2e-configuration-baseline-{path}.json"
            if name == "hub_write_file":
                record = json.loads(args["content"])
                assert record["deviceId"] == "10" and record["label"] == "Fixture" and record["deviceTypeId"] == 100
                assert record["restore"]["label"] == "Fixture" and record["restore"]["room"] in (None, "")
                assert set(record["preferences"]) == set(expected)
            files.append((name, len(writes)))
            return {"success": True}
        assert name == "hub_get_device", name
        if args.get("mode") == "configuration":
            return configuration()
        if args.get("mode") == "details":
            if args["sections"] == ["attributes", "configuration"]:
                return {
                    "sectionRead": {section: {"status": "complete"} for section in args["sections"]},
                    "sections": {
                        "attributes": {"declaredAttributes": [
                            {"name": key, "value": json.dumps(value)} for key, value in snapshot.items()]},
                        "configuration": configuration(),
                    },
                }
            if args["sections"] == ["identity", "metadata"]:
                assert args["fields"] == ["roomId", "zigbeeId", "notes", "tags", "defaultIcon"]
                return {
                    "sectionRead": {section: {"status": "complete"} for section in args["sections"]},
                    "sections": {
                        "identity": {key: info[key] for key in ("roomId", "zigbeeId")},
                        "metadata": {key: info[key] for key in ("notes", "tags", "defaultIcon")},
                    },
                }
            assert args["sections"] == ["identity"] and args["fields"] == ["groupId", "controllerType"]
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
            if failure in ("after-edit", "restore-confirmation") and info["dataValues"]["configurationProbe"] == "changed":
                raise RuntimeError("injected observation failure after grouped edit")
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
        changed_data = {key for key, value in patch.get("dataValues", {}).items()
                        if info["dataValues"].get(key) != value}
        if not (setup and failure == "setup-noop"):
            for key, value in patch.items():
                info["roomName" if key == "room" else key] = deepcopy(value)
        if setup and failure == "setup-error":
            raise RuntimeError("injected setup failure after mutation")
        if body and failure == "body":
            raise RuntimeError("injected matrix failure after mutation")
        changes = [{"property": k} for k in patch if k != "dataValues"]
        if failure != "restore-confirmation" or body:
            changes.extend({"property": f"dataValue.{key}"} for key in changed_data)
        return {"success": True, "mrtr": {"continued": True}, "changes": changes}

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool, app_id="38"))
    runner._write_once = write_once
    message = {"body": "injected matrix failure", "setup-error": "injected setup failure",
               "setup-noop": "Native showOnHome", "after-edit": "injected observation failure",
               "restore-confirmation": "Native data write was not confirmed"}[failure]
    with pytest.raises((RuntimeError, AssertionError), match=message):
        runner._device_configuration_profile(
            {"path": path, "label": "Fixture", "authorized": authorized}, "10", "11",
            {"version": 2, "driver": "Primary", "replacementDriver": "Alternate", "nativeFields": {}},
            {"Primary": 100, "Alternate": 101}, expected, "Fixture room",
        )
    assert bool(runner._fixture_reset_failures) is (failure == "restore-confirmation")
    # The recipe is written BEFORE the first mutating write and discarded only after a verified
    # in-test restoration; a failed restoration leaves it for the cleanup layer.
    assert files and files[0] == ("hub_write_file", 0), files
    assert (("hub_delete_file" in [f[0] for f in files]) is (not runner._fixture_reset_failures)), files
    reached_edit = failure in ("body", "after-edit", "restore-confirmation")
    assert body_starts == ([(True, "switch")] if reached_edit else [])
    assert len(writes) == (2 if (show, status) == (True, "switch") else 3 if reached_edit else 2)
    if reached_edit:
        assert writes[-2]["dataValues"] == {"configurationProbe": "changed"}
    assert writes[-1]["dataValues"] == {"configurationProbe": "original"}
    # Hubitat represents the None selection as either null or empty text.
    for key in ("defaultCurrentState", "roomName"):
        original[key] = original[key] or ""
        info[key] = info[key] or ""
    assert info == original


@pytest.mark.parametrize("drift", ["none", "preferences", "fields", "both"])
def test_canonical_reconcile_patches_only_what_drifted(drift):
    """The pre-run/cleanup sweep brings a permanent fixture back to the manifest's canonical
    baseline without a recipe: one configuration read, a write only for what drifted, identity
    fields the manifest leaves null reported rather than guessed."""
    canonical = {
        "preferences": {"probeBool": {"type": "bool", "value": False}, "probeText": {"type": "text", "value": "original saved text"}},
        "fields": {"maxEvents": 40, "notes": "", "tags": [], "room": None, "enabled": True, "dashboardIds": []},
        "identity": ["name", "deviceNetworkId", "zigbeeId"],
    }
    profile = {"path": "standalone-bypass", "label": "Fixture", "authorized": False,
               "canonical": {"name": None, "deviceNetworkId": "fixture-dni", "zigbeeId": None}}
    prefs = {"probeBool": True if drift in ("preferences", "both") else False, "probeText": "original saved text"}
    fields = {"maxEvents": 47 if drift in ("fields", "both") else 40, "notes": "", "tags": "", "room": None,
              "enabled": True, "dashboardIds": [], "name": "Fixture_name", "deviceNetworkId": "fixture-dni", "zigbeeId": "0200"}
    writes, bypass = [], []

    def call_tool(name, args=None):
        args = args or {}
        if "tool" in args:
            name, args = args["tool"], args.get("args", {})
        if name == "hub_update_mcp_settings":
            bypass.append(args["settings"])
            return {"success": True, "updated": args["settings"]}
        if name == "hub_get_device":
            assert args == {"deviceId": "10", "mode": "configuration"}
            return {"preferences": [{"name": k, "value": v, "valuePresent": True} for k, v in prefs.items()],
                    "editableFields": [{"name": k, "value": v, "writable": True} for k, v in fields.items()]}
        assert name == "hub_update_device", name
        writes.append({k: v for k, v in args.items() if k != "deviceId"})
        return {"success": True}

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool, app_id="38"))
    runner._reconcile_canonical_configuration("pre-run", profile, "10", canonical)

    assert not runner._fixture_reset_failures
    if drift == "none":
        assert writes == [] and bypass == []
        return
    assert bypass == [{"bypassDeviceAllowlist": True}]
    patched_prefs = [w["preferences"] for w in writes if "preferences" in w]
    patched_fields = {k for w in writes for k in w if k not in ("preferences", "confirm")}
    if drift in ("preferences", "both"):
        assert patched_prefs == [{"probeBool": {"type": "bool", "value": False}}]
    else:
        assert patched_prefs == []
    assert patched_fields == ({"maxEvents"} if drift in ("fields", "both") else set())
    # Identity: the supplied DNI already matches (no write); name/zigbeeId are null in the manifest
    # and must never be written.
    assert not any(k in w for w in writes for k in ("name", "zigbeeId", "deviceNetworkId"))
