"""Run shared native Rule Machine assertions through the regular E2E transport."""

import importlib
import json
import os
import sys
import time
import unittest
import urllib.parse
import uuid

from native_rule_document_e2e import HubClient

SUITES = {
    "lifecycle": ("native_rm_parity_lifecycle_e2e", "NativeRmParityLifecycleE2E"),
    "expressions": ("native_rm_parity_expressions_e2e", "NativeRmParityExpressions"),
    "actions": ("native_rm_parity_actions_e2e", "NativeRmParityActions"),
    "backup": ("native_rm_backup_e2e", "NativeRmBackupE2E"),
}


class RegularNativeClient(HubClient):
    def __init__(self, runner):
        local_base = os.environ.get("HUB_URL") or runner.client.hub_url
        parsed = urllib.parse.urlsplit(local_base)
        if parsed.hostname == "cloud.hubitat.com":
            raise AssertionError("Native verification requires HUB_URL for the local hub")
        super().__init__(local_base.rstrip("/"), str(runner.client.app_id), runner.client.access_token)
        self.runner = runner
        self.mcp_url = runner.client.endpoint + "?" + urllib.parse.urlencode({
            "access_token": runner.client.access_token})

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
    DRIVERS = {
        "switch": "Virtual Switch", "switch2": "Virtual Switch", "out": "Virtual Switch",
        "temperature": "Virtual Temperature Sensor", "temperature2": "Virtual Temperature Sensor",
        "dimmer": "Virtual Dimmer", "button": "Virtual Button",
        "color": "Virtual RGBW Light", "blind": "Virtual Blind", "shade": "Virtual Shade",
        "garage": "Virtual Garage Door Controller", "valve": "Virtual Valve",
        "tone": "Virtual Tone", "player": "Virtual Music Player",
        "motion": "Virtual Motion Sensor", "contact": "Virtual Contact Sensor",
        "water": "Virtual Water Sensor", "humidity": "Virtual Humidity Sensor",
        "illuminance": "Virtual Illuminance Sensor", "presence": "Virtual Presence",
    }

    def __init__(self, runner):
        super().__init__()
        self.runner = runner

    def __missing__(self, key):
        if key not in self.DRIVERS:
            raise AssertionError(f"No regular E2E native fixture recipe for {key}")
        fixture_key = "native_" + key
        self.runner.PERM_FIXTURES = dict(self.runner.PERM_FIXTURES)
        self.runner.PERM_FIXTURES[fixture_key] = ("E2E_PERM_Native_" + key, self.DRIVERS[key])
        self[key] = self.runner._ensure_perm_fixture(fixture_key)
        return self[key]


def native_context(runner):
    cached = getattr(runner, "_native_rule_context", None)
    if cached is not None:
        return cached
    client = RegularNativeClient(runner)
    explicit = os.environ.get("NATIVE_RULE_FIXTURES")
    fixtures = json.loads(explicit) if explicit else RunnerFixtures(runner)
    parent_id = os.environ.get("NATIVE_RULE_PARENT_ID")
    if not parent_id:
        result = runner.client.call_tool("hub_list_apps", {"scope": "instances"})
        rows = result.get("apps", result.get("installedApps", []))
        parents = [row for row in rows if (row.get("appTypeName") or row.get("name")) == "Rule Machine"]
        if len(parents) != 1:
            raise AssertionError(f"Expected one Rule Machine parent, found {parents}")
        parent_id = parents[0]["id"]
    mode_id = os.environ.get("NATIVE_RULE_MODE_ID")
    if not mode_id:
        modes = runner.client.call_tool("hub_list_modes", {}).get("modes", [])
        if not modes:
            raise AssertionError("Native rule E2E requires a hub mode")
        mode_id = modes[0]["id"]
    cached = dict(client=client, parent_id=int(parent_id), mode_id=str(mode_id), fixtures=fixtures,
                  custom_drivers=json.loads(os.environ.get("NATIVE_RULE_FIXTURE_DRIVERS", "{}")),
                  explicit=bool(explicit))
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
            device_id = str(cls.fixtures[key])
            metadata = cls.client.native(f"/device/fullJson/{device_id}")
            device = metadata["device"]
            if (str(device["id"]) != device_id or device.get("label") != "E2E_PERM_Native_" + key
                    or device.get("virtual") is not True):
                raise AssertionError("Native fixture identity or virtual-device status differs")
            return metadata
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


from native_rm_backup_e2e import NativeRmBackupE2E
from native_rule_document_e2e import change, field, log_action


class NativeRecoverySelectors(NativeRmBackupE2E):
    def restore(self, key, **options):
        return self.call("hub_restore_backup", scope="source", backupKey=key, confirm=True, **options)

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
        rule_id = self.create([self.switch_action(device_id)])
        added = self.update(rule_id, [log_action("post-snapshot marker")])
        self.assertTrue(added.get("success"), added)
        self.delete_device(dni)
        restored = self.restore(added["backup"]["backupKey"], preserveRuleId=True)
        self.assertTrue(restored.get("partial"), restored)
        self.assertIsNone(restored.get("failedStep"), restored)
        skipped = [row for row in restored.get("settingsSkipped", []) if "no longer exist" in str(row.get("reason"))]
        self.assertTrue(any(str(device_id) in str(row.get("reason")) for row in skipped), restored)

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
        rule_id = self.create([self.switch_condition(switches["Gone"][0])])
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
        self.delete_device(switches["Gone"][1])
        refused = self.restore(keys["Kitchen"])
        self.assertTrue(refused.get("partial") or refused.get("success") is False, refused)
        self.assertNotIn(str(switches["Gone"][0]), str(self.settings(rule_id).get("rDev_" + condition, {}).get("device_ids")))


SUITES["recovery"] = (__name__, "NativeRecoverySelectors")


from native_rm_parity_actions_e2e import NativeRmParityActions


class NativePickerSelectors(NativeRmParityActions):
    def test_set_rule_switch_only_on_and_device_list_no_false_partial(self):
        rule_id = self.create([change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures["switch"])]),
            field("onOff.#", "bool", False), field("optSwitch.#", "bool", True)])])
        action_id = str(self.doc(rule_id)["actions"][0]["id"])
        values = self._values(rule_id)
        self.assertFalse(values["onOff." + action_id])
        self.assertTrue(values["optSwitch." + action_id])
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
        form = self._submit(shade_rule, form, fields={"actType." + index: "switchActs"})
        subtype_field = next(row for row in form["fields"] if row["name"] == "actSubType." + index)
        choices = [row["value"] for row in subtype_field.get("options", [])
                   if "shade" in str(row).lower() and "close" in str(row).lower()]
        self.assertEqual(len(choices), 1, subtype_field)
        form = self._submit(shade_rule, form, fields={"actSubType." + index: choices[0]})
        form = self._submit(shade_rule, form, fields={"shadeRL." + index: True})
        form = self._submit(shade_rule, form, fields={"shadeOpenClose." + index: [str(self.fixtures["shade"])]})
        committed = self._submit(shade_rule, form, control=self._control(form, name="actionDone"))
        self.assertTrue(committed.get("success"), committed)
        self.assertFalse(committed.get("partial"), committed)
        self._devices(shade_rule, "shadeOpenClose." + index, "shade")
        self.assertTrue(self._values(shade_rule)["shadeRL." + index])
        self.healthy(shade_rule)


SUITES["picker"] = (__name__, "NativePickerSelectors")
