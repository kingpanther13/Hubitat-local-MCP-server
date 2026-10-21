"""Run shared native Rule Machine assertions through the regular E2E transport."""

import hashlib
import importlib
import json
import os
import re
import sys
import time
import unittest
import urllib.parse
import uuid
from pathlib import Path
from typing import ClassVar

import requests
from native_rm_backup_e2e import NativeRmBackupE2E
from native_rm_parity_actions_e2e import NativeRmParityActions
from native_rm_parity_support import ParityCase
from native_rule_document_e2e import HubClient, change, field, log_action

SUITES = {
    "lifecycle": ("native_rm_parity_lifecycle_e2e", "NativeRmParityLifecycleE2E"),
    "expressions": ("native_rm_parity_expressions_e2e", "NativeRmParityExpressions"),
    "actions": ("native_rm_parity_actions_e2e", "NativeRmParityActions"),
    "app_actions": ("native_rm_parity_app_actions_e2e", "NativeRmParityAppActions"),
    "backup": ("native_rm_backup_e2e", "NativeRmBackupE2E"),
}


def normalized_hub_url(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise AssertionError("Native verification requires an HTTP hub base URL without credentials or query")
    port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    return parsed.scheme.lower(), parsed.hostname.lower(), port, parsed.path.rstrip("/")


def native_boolean(value):
    if value is True or value == "true":
        return True
    if value is False or value == "false":
        return False
    raise AssertionError("Native boolean must be a boolean or lowercase boolean text")


class RegularNativeClient(HubClient):
    def __init__(self, runner):
        self.runner = runner
        native_base = getattr(runner, "native_hub_url", None)
        local_base = os.environ.get("HUB_URL") or native_base or runner.client.hub_url
        local_url = normalized_hub_url(local_base)
        parsed = urllib.parse.urlsplit(local_base)
        if parsed.hostname == "cloud.hubitat.com":
            raise AssertionError("Native verification requires HUB_URL for the local hub")
        super().__init__(local_base.rstrip("/"), str(runner.client.app_id), runner.client.access_token)
        if local_url != normalized_hub_url(runner.client.hub_url):
            allowed = os.environ.get("NATIVE_RULE_E2E_ALLOWED_HUB_URL") or native_base
            if not allowed or local_url != normalized_hub_url(allowed):
                raise AssertionError("A different native URL requires explicit native URL authorization")
        self.mcp_url = runner.client.endpoint + "?" + urllib.parse.urlencode({
            "access_token": runner.client.access_token})

    def install_rule_machine_parent(self, type_id):
        headers = {"Cookie": os.environ["HUB_UI_COOKIE"]} if os.environ.get("HUB_UI_COOKIE") else {}
        self.calls["native_http"] += 1
        installed = requests.get(self.base + "/installedapp/sysAppById/" + str(type_id),
                                 headers=headers, allow_redirects=False, timeout=180)
        assert installed.status_code == 302, "Native Rule Machine install did not redirect to its editor"
        location = installed.headers.get("Location")
        assert isinstance(location, str) and location, "Native Rule Machine install returned no editor location"
        editor_url = urllib.parse.urljoin(self.base + "/", location)
        parsed = urllib.parse.urlsplit(editor_url)
        assert normalized_hub_url(editor_url)[:3] == normalized_hub_url(self.base)[:3], "Native editor redirects to another origin"
        assert re.fullmatch(r"/installedapp/configure/[0-9]+", parsed.path), "Native install redirected outside its editor"
        self.calls["native_http"] += 1
        editor = requests.get(editor_url, headers=headers, allow_redirects=False, timeout=180)
        assert editor.status_code == 200, "Native Rule Machine editor did not open successfully"

    def tool(self, tool_name, **arguments):
        self.calls[tool_name] += 1
        try:
            return self.runner.client.call_tool(tool_name, arguments)
        except Exception as exc:
            if not hasattr(exc, "tool_name") or not hasattr(exc, "content_text"):
                raise
            try:
                payload = json.loads(exc.content_text)
            except (ValueError, TypeError):
                payload = {"error": exc.content_text}
            if not isinstance(payload, dict):
                payload = {"error": exc.content_text}
            return dict(payload, success=False)

    def request(self, url, method="GET", payload=None, headers=None, form=None):
        response = super().request(url, method, payload, headers, form)
        if url == self.mcp_url and isinstance(response, dict) and response.get("error"):
            module = sys.modules[type(self.runner).__module__]
            expectation = module._validation_log_expectation(payload.get("method"), payload.get("params"), response["error"])
            if expectation:
                self.runner.client._expected_validation_logs.append(expectation)
        return response


class RunnerFixtures(dict):
    DRIVERS: ClassVar[dict[str, str]] = {
        "switch": "Virtual Switch", "switch2": "Virtual Switch", "out": "Virtual Switch",
        "temperature": "Virtual Temperature Sensor", "temperature2": "Virtual Temperature Sensor",
        "dimmer": "Virtual Dimmer", "button": "Virtual Button",
        "color": "Virtual RGBW Light", "blind": "Virtual Blind", "shade": "Virtual Shade",
        "garage": "Virtual Garage Door Controller", "valve": "Virtual Valve",
        "tone": "BAT_E2E_KEEP Virtual Tone", "player": "Virtual Player",
        "motion": "Virtual Motion Sensor", "contact": "Virtual Contact Sensor",
        "water": "Virtual Water Sensor", "humidity": "Virtual Humidity Sensor",
        "illuminance": "Virtual Illuminance Sensor", "presence": "Virtual Presence",
        "omni": "Virtual Omni Sensor",
        "thermostat": "Virtual Thermostat", "thermostat2": "Virtual Thermostat",
    }

    def __init__(self, runner):
        super().__init__()
        self.runner = runner
        self.tone_driver = None

    def _verify_tone_source(self, driver_id):
        source = (Path(__file__).parent / "fixtures" / "native-tone.groovy").read_text(encoding="utf-8")
        stored = self.runner.client.call_tool("hub_read_apps_code", {
            "tool": "hub_get_source", "args": {"type": "driver", "id": driver_id}})
        if stored.get("success") is not True or stored.get("source") != source or stored.get("hasMore"):
            raise AssertionError("Synthetic tone fixture driver source differs from authorization")
        return hashlib.sha256(source.encode()).hexdigest()

    def _ensure_tone_driver(self, context):
        name = self.DRIVERS["tone"]
        matches, seen = [], set()
        args = {"include": "all", "nameFilter": name, "cursor": ""}
        while True:
            page = self.runner.client.call_tool("hub_read_apps_code", {"tool": "hub_list_drivers", "args": args})
            if (page.get("success") is False or page.get("source") != "hub_api"
                    or not isinstance(page.get("drivers"), list)):
                raise AssertionError("Cannot verify the synthetic tone driver catalog")
            matches.extend(row for row in page["drivers"] if row.get("name") == name)
            cursor = page.get("nextCursor")
            if not cursor:
                break
            if cursor in seen:
                raise AssertionError("Synthetic tone driver catalog repeated a cursor")
            seen.add(cursor)
            args = dict(args, cursor=cursor)
        if len(matches) > 1 or (matches and matches[0].get("bucket") != "user"):
            raise AssertionError("Synthetic tone driver identity is ambiguous or not user installed")
        if matches:
            driver_id = str(matches[0].get("id", ""))
        else:
            source = (Path(__file__).parent / "fixtures" / "native-tone.groovy").read_text(encoding="utf-8")
            created = self.runner.client.call_tool("hub_manage_code", {
                "tool": "hub_create_driver", "args": {"source": source, "confirm": True}})
            if created.get("success") is not True:
                raise AssertionError("Synthetic tone fixture driver installation failed")
            driver_id = str(created.get("driverId", ""))
        if not driver_id.isdecimal():
            raise AssertionError("Synthetic tone fixture driver has no numeric ID")
        permitted = {"id": driver_id, "sha256": self._verify_tone_source(driver_id)}
        # A nonempty cache tells the runner its full built-in catalog was already loaded.
        if not self.runner._driver_type_ids:
            self.runner._driver_type_id(self.DRIVERS["switch"])
        self.runner._driver_type_ids[name] = driver_id
        self.runner._driver_buckets[name] = "user"
        context["custom_drivers"]["tone"] = permitted
        self.tone_driver = permitted

    def metadata(self, key, device_id=None):
        device_id = str(self[key] if device_id is None else device_id)
        metadata = self.runner._native_rule_context["client"].native(f"/device/fullJson/{device_id}")
        device = metadata["device"]
        if (str(device.get("id")) != device_id or device.get("label") != "E2E_PERM_Native_" + key):
            raise AssertionError("Adopted native fixture identity differs")
        if key == "tone":
            permitted = self.tone_driver
            if not permitted or str(device.get("deviceTypeId")) != permitted["id"]:
                raise AssertionError("Synthetic tone fixture driver identity differs")
            if any(device.get(flag) for flag in ("zigbee", "ZWave", "matter", "network", "bluetooth", "linkedDevice")):
                raise AssertionError("Synthetic fixture has a physical or network connection")
            if self._verify_tone_source(permitted["id"]) != permitted["sha256"]:
                raise AssertionError("Synthetic tone fixture driver source differs from authorization")
        elif device.get("virtual") is not True:
            raise AssertionError("Adopted native fixture virtual-device status differs")
        return metadata

    def __missing__(self, key):
        if key not in self.DRIVERS:
            raise AssertionError(f"No regular E2E native fixture recipe for {key}")
        fixture_key = "native_" + key
        self.runner.PERM_FIXTURES = dict(self.runner.PERM_FIXTURES)
        self.runner.PERM_FIXTURES[fixture_key] = ("E2E_PERM_Native_" + key, self.DRIVERS[key])
        context = getattr(self.runner, "_native_rule_context", None)
        if context is None:
            raise AssertionError("Native fixture validation requires a bound hub context")
        if key == "tone":
            self._ensure_tone_driver(context)
        device_id = self.runner._ensure_perm_fixture(fixture_key)
        self.runner._perm_fixture_ids.pop(fixture_key, None)
        self.metadata(key, device_id)
        self.runner._perm_fixture_ids[fixture_key] = device_id
        self[key] = device_id
        return device_id


def native_context(runner):
    cached = getattr(runner, "_native_rule_context", None)
    if cached is not None:
        return cached
    client = RegularNativeClient(runner)
    explicit = os.environ.get("NATIVE_RULE_FIXTURES")
    fixtures = json.loads(explicit) if explicit else RunnerFixtures(runner)
    parent_id = os.environ.get("NATIVE_RULE_PARENT_ID")
    if not parent_id:
        from e2e_test import _discover_native_rule_parent_id
        parent_id = _discover_native_rule_parent_id(runner.client, install_missing=client.install_rule_machine_parent)
    mode_id = os.environ.get("NATIVE_RULE_MODE_ID")
    if not mode_id:
        modes = runner.client.call_tool("hub_list_modes", {}).get("modes", [])
        if not modes:
            raise AssertionError("Native rule E2E requires a hub mode")
        mode_id = modes[0]["id"]
    cached = {"client": client, "parent_id": int(parent_id), "mode_id": str(mode_id), "fixtures": fixtures,
              "custom_drivers": json.loads(os.environ.get("NATIVE_RULE_FIXTURE_DRIVERS", "{}")),
              "explicit": bool(explicit)}
    if explicit:
        if not isinstance(fixtures, dict) or not fixtures:
            raise AssertionError("NATIVE_RULE_FIXTURES must identify the authorized native fixtures")
        if not os.environ.get("NATIVE_RULE_FIXTURE_LABEL_PREFIX"):
            raise AssertionError("Explicit native fixtures require NATIVE_RULE_FIXTURE_LABEL_PREFIX")
        validator = type("RegularNativeFixtureValidation", (ParityCase,), {
            key: value for key, value in cached.items() if key != "explicit"})
        for key in fixtures:
            validator.fixture_metadata(key)
    runner._native_rule_context = cached
    return cached


def run_native_rule_case(runner, suite, method):
    module_name, class_name = SUITES[suite]
    base = getattr(importlib.import_module(module_name), class_name)
    context = native_context(runner)
    attrs = {key: value for key, value in context.items() if key != "explicit"}
    attrs.update(prefix="BAT_E2E_Native_" + uuid.uuid4().hex[:10], started=time.monotonic())
    if not context["explicit"]:
        @classmethod
        def fixture_metadata(cls, key):
            return context["fixtures"].metadata(key, cls.fixtures[key])
        attrs["fixture_metadata"] = fixture_metadata
    case_type = type("Regular" + class_name, (base,), attrs)
    case = case_type(method)
    result = unittest.TestResult()
    case.run(result)
    failures = result.errors + result.failures
    if failures:
        raise AssertionError("\n".join(detail for _, detail in failures))
    if result.skipped:
        raise AssertionError(f"Native rule coverage unexpectedly skipped: {result.skipped}")
    if result.testsRun != 1:
        raise AssertionError("Native rule case did not execute")


def edited_device_snapshot(source, old_id, old_label, new_id, new_label):
    """Mechanically copy a known fixture reference; the replacement is supplied by the test."""
    snapshot = json.loads(source)
    if snapshot.get("captureFormat") != "native-rule-edit-v1":
        raise AssertionError("Expected the readable native edit snapshot format")
    old_id, new_id = str(old_id), str(new_id)

    def device_value(value):
        if isinstance(value, dict):
            return {new_id if str(key) == old_id else key:
                    new_label if str(key) == old_id else device_value(item) for key, item in value.items()}
        if isinstance(value, list):
            return [device_value(item) for item in value]
        if str(value) == old_id:
            return int(new_id) if isinstance(value, int) else new_id
        return value

    def projections(value):
        if isinstance(value, list):
            return [projections(item) for item in value]
        if isinstance(value, dict):
            copied = {key: projections(item) for key, item in value.items()}
            for key in ("deviceId", "deviceIds", "deviceList", "deviceIdsForDeviceList"):
                if key in copied:
                    copied[key] = device_value(copied[key])
            if str(copied.get("type", "")).startswith("capability") and "value" in copied:
                copied["value"] = device_value(copied["value"])
            return copied
        return value.replace(old_label, new_label) if isinstance(value, str) else value

    payload = json.loads(snapshot["nativeExport"])
    replacements = payload["deviceReplacements"]
    if old_id not in replacements or new_id in replacements:
        raise AssertionError("Expected one original device reference and a distinct chosen replacement")
    replacement = projections(replacements.pop(old_id))
    for key in ("id", "deviceId"):
        if key in replacement and str(replacement[key]) == old_id:
            replacement[key] = device_value(replacement[key])
    replacement["deviceLabel"] = new_label
    replacements[new_id] = replacement
    payload = projections(payload)
    snapshot = projections(snapshot)
    settings = snapshot["configJson"]["settings"]
    for key, value in settings.items():
        if isinstance(value, dict) and old_id in value:
            settings[key] = device_value(value)
    snapshot["nativeExport"] = json.dumps(payload, separators=(",", ":"))
    return json.dumps(snapshot, separators=(",", ":"))


class NativeRecoverySelectors(NativeRmBackupE2E):
    def restore(self, key, **options):
        return self.call("hub_restore_backup", scope="source", backupKey=key, confirm=True, **options)

    def read_recovery_file(self, file_name):
        chunks, offset = [], 0
        while True:
            result = self.call("hub_read_file", fileName=file_name, offset=offset, length=60000)
            self.assertTrue(result.get("success"), result)
            self.assertEqual(result["offset"], offset)
            chunks.append(result["content"])
            if not result.get("hasMore"):
                return "".join(chunks)
            self.assertGreater(result["nextOffset"], offset)
            offset = result["nextOffset"]

    def readable_recovery(self, key):
        result = self.call("hub_get_backup", backupKey=key)
        self.assertNotIn("error", result)
        content = self.read_recovery_file(result["fileName"])
        self.assertEqual(json.loads(content)["captureFormat"], "native-rule-edit-v1")
        if "source" in result:
            self.assertEqual(result["source"].encode(), content.encode())
        return result["fileName"], content

    def assert_missing_device_refusal(self, rule_id, key, missing_id):
        before = self.configuration(rule_id)
        refused = self.restore(key, preserveRuleId=True)
        self.assertIs(refused.get("success"), False, refused)
        self.assertIs(refused.get("partial"), False, refused)
        self.assertEqual(refused.get("failedStep"), "devicePreflight", refused)
        self.assertIn(str(missing_id), list(map(str, refused.get("missingDeviceIds", []))), refused)
        self.assertEqual(self.configuration(rule_id), before)
        return refused

    def restore_edited_device_copy(self, rule_id, original_file, original, old_id, old_label, new_id, new_label):
        edited = edited_device_snapshot(original, old_id, old_label, new_id, new_label)
        file_name = self.prefix + "_backup_edited_recovery.json"
        written = self.call("hub_write_file", fileName=file_name, content=edited, confirm=True)
        self.assertTrue(written.get("success"), written)
        self.addCleanup(self.call, "hub_delete_file", fileName=file_name, confirm=True)
        self.assertEqual(self.read_recovery_file(file_name).encode(), edited.encode())
        restored = self.call("hub_restore_backup", scope="source", sourceFile=file_name,
                             preserveRuleId=True, confirm=True)
        self.assertTrue(restored.get("success"), restored)
        self.assertFalse(restored.get("partial"), restored)
        self.assertEqual(str(restored["ruleId"]), str(rule_id))
        self.assertEqual(restored.get("restoredVia"), "nativeStateReplay", restored)
        self.assertEqual(self.read_recovery_file(original_file).encode(), original.encode())
        self.healthy(rule_id)
        return restored

    def switch_action(self, device_id):
        return change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(device_id)]), field("onOff.#", "bool", False)])

    def switch_condition(self, device_id, **extra):
        return change("condition", capability="Switch", fields=[
            field("rDev_#", "capability.switch", [str(device_id)]), field("state_#", "enum", "on")], **extra)

    def switch_trigger(self, state):
        return change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
            field("tstate#", "enum", state)])

    def test_rm_rule_restore_in_place_with_device_picker(self):
        rule_id = self.create([self.switch_action(self.fixtures["switch"]),
                               self.switch_condition(self.fixtures["switch"])])
        before = self.doc(rule_id)
        added = self.update(rule_id, [log_action("post-snapshot marker")])
        self.assertTrue(added.get("success"), added)
        key = added["backup"]["backupKey"]
        condition = next(iter(before["conditions"]))
        self.assertTrue(self.update(rule_id, [change("required", tokens=[condition])]).get("success"))
        restored = self.restore(key, preserveRuleId=True)
        self.assertTrue(restored.get("success"), restored)
        self.assertEqual(str(restored["ruleId"]), str(rule_id))
        self.assertFalse(restored.get("recreated"), restored)
        self.assertNotIn("post-snapshot marker", str(self.doc(rule_id)))
        self.assertFalse(self.state(rule_id).get("hasPredicate"))
        pickers = [row for name, row in self.settings(rule_id).items() if name.startswith("onOffSwitch.")]
        self.assertEqual(len(pickers), 1)
        self.assertEqual(set(pickers[0]["device_ids"]), {str(self.fixtures["switch"])})
        self.healthy(rule_id)

    def test_rm_rule_restore_skips_a_deleted_device(self):
        device_id, dni = self.disposable_switch("RestoreGone")
        old_label = self.client.native(f"/device/fullJson/{device_id}")["device"]["label"]
        replacement_id, _ = self.disposable_switch("RestoreReplacement")
        replacement_label = self.client.native(f"/device/fullJson/{replacement_id}")["device"]["label"]
        rule_id = self.create([self.switch_action(device_id), log_action("unrelated saved action"),
            change("variable", operation="create", name="Counter", type="integer", value=17)])
        baseline = self.doc(rule_id)
        added = self.update(rule_id, [log_action("post-snapshot marker")])
        self.assertTrue(added.get("success"), added)
        key = added["backup"]["backupKey"]
        original_file, original = self.readable_recovery(key)
        self.delete_device(dni)
        self.assert_missing_device_refusal(rule_id, key, device_id)
        self.assertEqual(self.read_recovery_file(original_file).encode(), original.encode())
        self.restore_edited_device_copy(rule_id, original_file, original, device_id, old_label,
                                        replacement_id, replacement_label)
        restored = self.doc(rule_id)
        self.assertEqual([row["id"] for row in restored["actions"]], [row["id"] for row in baseline["actions"]])
        self.assertEqual(restored["variables"], baseline["variables"])
        self.assertIn("unrelated saved action", str(restored))
        self.assertNotIn("post-snapshot marker", str(restored))
        pickers = [row for name, row in self.settings(rule_id).items() if name.startswith("onOffSwitch.")]
        self.assertEqual(len(pickers), 1)
        self.assertEqual(pickers[0]["device_ids"], [str(replacement_id)])

    def test_rm_rule_restore_brings_back_a_removed_trigger(self):
        rule_id = self.create([self.switch_trigger("on"), self.switch_trigger("off"), log_action("trigger recovery")])
        before = self.doc(rule_id)
        trigger_id = max(before["triggers"], key=int)
        removed = self.update(rule_id, [{"part": "delete", "value": {"part": "trigger", "id": trigger_id}}])
        self.assertTrue(removed.get("success"), removed)
        key = removed["backup"]["backupKey"]
        self.assertNotIn(trigger_id, self.doc(rule_id)["triggers"])
        restored = self.restore(key, preserveRuleId=True)
        self.assertTrue(restored.get("success"), restored)
        self.assertEqual(self.doc(rule_id)["triggers"], before["triggers"])
        label = self.settings(rule_id)["origLabel"]["value"]
        copied = self.restore(key, preserveRuleId=False)
        self.assertTrue(copied.get("success"), copied)
        copy_id = int(copied["ruleId"])
        self.assertNotEqual(copy_id, rule_id)
        self.adopt(copy_id, label)
        self.assertEqual(len(self.doc(copy_id)["triggers"]), 2)
        self.assertNotIn("installedApp", self.status(rule_id, missing_ok=True))
        again = self.restore(key)
        self.assertTrue(again.get("alreadyRestored"), again)
        self.assertEqual(str(again["ruleId"]), str(copy_id))

    def test_rm_rule_restore_of_a_deleted_rule_uses_the_exact_copy(self):
        rule_id = self.create([self.switch_trigger("on"), log_action("deleted-rule marker")])
        label = self.settings(rule_id)["origLabel"]["value"]
        deleted = self.call("hub_delete_native_app", appId=rule_id, force=True, confirm=True)
        self.assertTrue(deleted.get("success"), deleted)
        restored = self.restore(deleted["backup"]["backupKey"])
        self.assertTrue(restored.get("success"), restored)
        self.assertEqual(restored.get("restoredVia"), "nativeImport")
        copy_id = int(restored["ruleId"])
        self.assertNotEqual(copy_id, rule_id)
        self.adopt(copy_id, label)
        document = self.doc(copy_id)
        self.assertEqual(len(document["triggers"]), 1)
        self.assertIn("deleted-rule marker", str(document))
        self.healthy(copy_id)

    def test_rm_rule_restore_puts_actions_back_in_order(self):
        rule_id = self.create([log_action("order first"), log_action("order second")])
        before = [str(row["id"]) for row in self.doc(rule_id)["actions"]]
        moved = self.update(rule_id, [change("order", ids=list(reversed(before)))])
        self.assertTrue(moved.get("success"), moved)
        self.assertEqual([str(row["id"]) for row in self.doc(rule_id)["actions"]], list(reversed(before)))
        restored = self.restore(moved["backup"]["backupKey"])
        self.assertTrue(restored.get("success"), restored)
        self.assertEqual([str(row["id"]) for row in self.doc(rule_id)["actions"]], before)
        self.healthy(rule_id)

    def test_rm_rule_restore_expression_by_device_name(self):
        switches = {name: self.disposable_switch("Lamp (" + name + ")") for name in ("Gone", "Kitchen", "Den")}
        self.backups_enabled(False)
        rule_id = self.create([self.switch_condition(switches["Gone"][0]), log_action("expression unrelated"),
            change("variable", operation="create", name="Counter", type="integer", value=23)])
        baseline = self.doc(rule_id)
        condition = next(iter(self.doc(rule_id)["conditions"]))
        self.assertTrue(self.update(rule_id, [change("required", tokens=[condition])]).get("success"))
        self.backups_enabled(True)
        self.call("hub_update_mcp_settings", settings={"backupEveryRuleWrite": True}, confirm=True)
        self.addCleanup(self.call, "hub_update_mcp_settings", settings={"backupEveryRuleWrite": False}, confirm=True)
        keys = {}
        for name in ("Kitchen", "Den"):
            result = self.update(rule_id, [self.switch_condition(switches[name][0], id=condition)])
            self.assertTrue(result.get("success"), result)
            keys[name] = result["backup"]["backupKey"]
        restored = self.restore(keys["Den"])
        self.assertTrue(restored.get("success"), restored)
        self.assertIn("Lamp (Kitchen)", str(self.doc(rule_id)["conditions"]))
        self.assertNotIn("Lamp (Den)", str(self.doc(rule_id)["conditions"]))
        key = keys["Kitchen"]
        original_file, original = self.readable_recovery(key)
        gone_id = switches["Gone"][0]
        old_label = self.client.native(f"/device/fullJson/{gone_id}")["device"]["label"]
        replacement_id = switches["Den"][0]
        replacement_label = self.client.native(f"/device/fullJson/{replacement_id}")["device"]["label"]
        self.delete_device(switches["Gone"][1])
        self.assert_missing_device_refusal(rule_id, key, gone_id)
        self.assertIn("Lamp (Kitchen)", str(self.doc(rule_id)["conditions"]))
        self.assertEqual(self.read_recovery_file(original_file).encode(), original.encode())
        self.restore_edited_device_copy(rule_id, original_file, original, gone_id, old_label,
                                        replacement_id, replacement_label)
        recovered = self.doc(rule_id)
        self.assertEqual(recovered["expressions"]["0"], [condition])
        self.assertEqual(recovered["variables"], baseline["variables"])
        self.assertEqual(recovered["actions"], baseline["actions"])
        self.assertIn("Lamp (Den)", str(recovered["conditions"]))
        self.assertNotIn("Lamp (Kitchen)", str(recovered["conditions"]))
        self.assertEqual(self.settings(rule_id)["rDev_" + condition]["device_ids"], [str(replacement_id)])

SUITES["recovery"] = (__name__, "NativeRecoverySelectors")


class NativePickerSelectors(NativeRmParityActions):
    def test_set_rule_switch_only_on_and_device_list_no_false_partial(self):
        rule_id = self.create([change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures["switch"])]),
            field("onOff.#", "bool", False), field("optSwitch.#", "bool", True)])])
        action_id = str(self.doc(rule_id)["actions"][0]["id"])
        values = self._values(rule_id)
        self.assertIs(native_boolean(values["onOff." + action_id]), False)
        self.assertIs(native_boolean(values["optSwitch." + action_id]), True)
        self._devices(rule_id, "onOffSwitch." + action_id, "switch")
        self.healthy(rule_id)

        if "shade" not in self.fixtures:
            label = self.prefix + "_Shade"
            created = self.call("hub_manage_virtual_device", action="create", deviceType="Virtual Shade",
                                deviceLabel=label, deviceNetworkId=label, confirm=True)
            self.assertTrue(created.get("success"), created)
            shade_id = created.get("deviceId") or created.get("device", {}).get("id")
            self.assertIsNotNone(shade_id, created)
            self.owned_devices[label] = str(shade_id)
            self.addCleanup(self.delete_device, label)
            self.fixtures = dict(self.fixtures, shade=str(shade_id))
        shade_rule = self.create([])
        form, index = self._new_editor(shade_rule)
        category_field = next(row for row in form["fields"] if row["name"] == "actType." + index)
        categories = [row for row in category_field.get("options", [])
                      if "shades/blinds" in row.get("label", "").lower() and "fans" in row.get("label", "").lower()]
        self.assertEqual(len(categories), 1, category_field)
        self.assertEqual(categories[0]["value"], "sceneActs", category_field)
        form = self._submit(shade_rule, form, fields={"actType." + index: categories[0]["value"]})
        subtype_field = next(row for row in form["fields"] if row["name"] == "actSubType." + index)
        choices = [row for row in subtype_field.get("options", []) if row.get("value") == "getRLShade"]
        self.assertEqual(len(choices), 1, subtype_field)
        self.assertIn("open/close", choices[0].get("label", "").lower())
        form = self._submit(shade_rule, form, fields={"actSubType." + index: choices[0]["value"]})
        form = self._submit(shade_rule, form, fields={"shadeRL." + index: True})
        form = self._submit(shade_rule, form, fields={"shadeOpenClose." + index: [str(self.fixtures["shade"])]})
        committed = self._submit(shade_rule, form, control=self._control(form, name="actionDone"))
        self.assertTrue(committed.get("success"), committed)
        self.assertFalse(committed.get("partial"), committed)
        self._devices(shade_rule, "shadeOpenClose." + index, "shade")
        self.assertIs(native_boolean(self._values(shade_rule)["shadeRL." + index]), True)
        self.healthy(shade_rule)


SUITES["picker"] = (__name__, "NativePickerSelectors")
