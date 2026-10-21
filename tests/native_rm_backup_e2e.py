"""Opt-in native RM recovery acceptance; uses ParityCase's explicit target guard.

Run only on an authorized test hub with its labeled virtual switch/out fixtures.
Exercises recovery through public tools and compares native persistence and runtime.
Automatic pre-edit backups are enabled on completion, including after failure.
"""

import json
import time
import unittest

import native_rule_document_e2e as documents
from native_rm_parity_support import ParityCase, change, field, log_action

documents.TOOLS.update({"hub_list_backups", "hub_get_backup", "hub_restore_backup",
                        "hub_update_mcp_settings", "hub_set_native_app", "hub_read_file", "hub_write_file", "hub_delete_file"})


class NativeRmBackupE2E(ParityCase):
    maxDiff = None

    def setUp(self):
        super().setUp()
        self.addCleanup(self.backups_enabled, True)
        self.backups_enabled(True)

    def backups_enabled(self, enabled):
        result = self.call("hub_update_mcp_settings", confirm=True, settings={
            "backupBeforeRuleEdits": enabled, "backupEveryRuleWrite": False})
        self.assertTrue(result.get("success"), result)

    def backup_keys(self, rule_id):
        keys, args = set(), {"scope": "source"}
        while True:
            result = self.call("hub_list_backups", **args)
            self.assertIsNot(result.get("success"), False, result)
            keys.update(row["backupKey"] for row in result.get("backups", [])
                        if str(row.get("ruleId", row.get("id"))) == str(rule_id))
            if not result.get("nextCursor"):
                return keys
            args["cursor"] = result["nextCursor"]

    def recovery(self, rule_id, key):
        started = time.monotonic()
        result = self.call("hub_restore_backup", backupKey=key, confirm=True)
        self.assertTrue(result.get("success"), result)
        self.assertEqual(str(result["ruleId"]), str(rule_id))
        self.assertEqual(result.get("restoredVia"), "nativeStateReplay", result)
        self.healthy(rule_id)
        print("BACKUP_RESTORE", json.dumps({"rule_id": rule_id, "backupKey": key,
            "seconds": time.monotonic() - started, "result": result}), flush=True)
        return result

    def condition(self, state="on", **extra):
        return change("condition", capability="Switch", fields=[
            field("rDev_#", "capability.switch", [str(self.fixtures["switch"])]),
            field("state_#", "enum", state)], **extra)

    def trigger(self, state="on"):
        return change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
            field("tstate#", "enum", state)])

    def output(self, enabled):
        return change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures["out"])]),
            field("onOff.#", "bool", enabled)])

    def configuration(self, rule_id):
        doc = self.doc(rule_id)
        return {"document": {key: doc.get(key) for key in (
            "actions", "conditions", "triggers", "expressions", "variables")},
            "settings": {name: {key: row.get(key) for key in (
                "type", "multiple", "value", "deviceList")}
                for name, row in self.settings(rule_id).items()
                if row.get("type") != "button"}}

    def _legacy_in_place_recovery(self, required):
        self.backups_enabled(False)
        rule_id = self.create([self.condition(), self.condition("off"), self.trigger(),
                               self.output(True), log_action("legacy baseline")])
        condition_ids = sorted(self.doc(rule_id)["conditions"], key=int)
        if required:
            result = self.update(rule_id, [change("required", tokens=[condition_ids[0]])])
            self.assertTrue(result.get("success"), result)
        self.command("switch", "off")
        before = self.configuration(rule_id)
        self.backups_enabled(True)
        forced = self.call("hub_update_mcp_settings", confirm=True, settings={
            "backupBeforeRuleEdits": True, "backupEveryRuleWrite": True})
        self.assertTrue(forced.get("success"), forced)
        keys = self.backup_keys(rule_id)
        captured = self.call("hub_set_native_app", appId=int(rule_id), confirm=True,
                             settings={"comments": "legacy capture compatibility"})
        self.assertTrue(captured.get("success"), captured)
        key = captured["backup"]["backupKey"]
        self.backups_enabled(True)
        self.assertEqual(self.backup_keys(rule_id) - keys, {key})
        metadata = self.call("hub_get_backup", backupKey=key)
        self.assertNotIn("error", metadata)
        chunks, offset = [], 0
        while True:
            page = self.call("hub_read_file", fileName=metadata["fileName"], offset=offset, length=60000)
            self.assertTrue(page.get("success"), page)
            self.assertEqual(page["offset"], offset)
            chunks.append(page["content"])
            if not page.get("hasMore"):
                break
            self.assertGreater(page["nextOffset"], offset)
            offset = page["nextOffset"]
        snapshot = json.loads("".join(chunks))
        self.assertNotIn("captureFormat", snapshot)
        self.assertIsInstance(snapshot["configJson"], dict)
        self.assertIsInstance(snapshot["statusJson"], dict)
        if required:
            self.assertFalse(snapshot.get("nativeExport"), snapshot)
            self.assertIn("Required Expression", snapshot.get("nativeExportSkipped", ""))
        else:
            self.assertTrue(snapshot.get("nativeExport"), snapshot)
        doc = self.doc(rule_id)
        action_ids = [str(row["id"]) for row in doc["actions"]]
        edits = [self.condition("off", id=condition_ids[0]),
                 change("trigger", id=next(iter(doc["triggers"])), capability="Switch", fields=[
                     field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
                     field("tstate#", "enum", "off")]),
                 change("action", id=action_ids[0], type="switchActs", subtype="getOnOffSwitch", fields=[
                     field("onOffSwitch.#", "capability.switch", [str(self.fixtures["out"])]),
                     field("onOff.#", "bool", False)]),
                 log_action("legacy changed", id=action_ids[1])]
        if required:
            edits.append(change("required", tokens=[condition_ids[1]]))
        else:
            edits.extend([change("required", tokens=[condition_ids[0]]),
                          log_action("legacy extra row must disappear")])
        edited = self.update(rule_id, edits)
        self.assertTrue(edited.get("success"), edited)
        self.assertNotEqual(self.configuration(rule_id), before)
        restored = self.call("hub_restore_backup", scope="source", backupKey=key,
                             preserveRuleId=True, confirm=True)
        self.assertTrue(restored.get("success"), restored)
        self.assertEqual(str(restored["ruleId"]), str(rule_id))
        self.assertEqual(restored.get("restoredVia"), "nativeStateReplay", restored)
        self.healthy(rule_id)
        self.assertEqual(self.configuration(rule_id), before)
        self.assertEqual(str(self.status(rule_id)["installedApp"]["id"]), str(rule_id))
        self.command("out", "off")
        self.command("switch", "on")
        self.wait_for(lambda: self.attribute("out", "switch") == "on")

    def test_legacy_export_backup_restores_in_place(self):
        self._legacy_in_place_recovery(required=False)

    def test_legacy_required_expression_backup_restores_in_place(self):
        self._legacy_in_place_recovery(required=True)

    def test_complex_edit_recovers_deleted_rows_and_existing_payloads(self):
        self.backups_enabled(False)
        rule_id = self.create([self.condition(), self.condition("off"),
            self.condition(), self.trigger("on"), self.trigger("off"),
            change("variable", operation="create", name="Counter", type="integer", value=7),
            log_action("recovery original"), log_action("recover deleted action")])
        doc = self.doc(rule_id)
        a, b, unused = sorted(doc["conditions"], key=int)
        result = self.update(rule_id, [change("required", tokens=[a, "OR", b]),
            change("action", type="condActs", subtype="getIfThen", fields=[], expression=[a]),
            change("action", type="condActs", subtype="getIfThen", fields=[], expression=[a, "OR", b]),
            self.output(True), change("terminator", subtype="endIf"),
            change("terminator", subtype="endIf"),
            change("action", type="delayActs", subtype="getWaitEvents", fields=[], events=[
                {"capability": "Switch", "fields": [
                    field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
                    field("tstate#", "enum", "off")]}]), self.output(False)])
        self.assertTrue(result.get("success"), result)
        self.command("switch", "off")
        self.call("hub_call_rule", ruleId=rule_id, action="stop")
        self.call("hub_call_rule", ruleId=rule_id, action="start")
        self.healthy(rule_id)
        before = self.configuration(rule_id)
        doc = self.doc(rule_id)
        ids = [str(row["id"]) for row in doc["actions"]]
        expression = str(next(row["value"]["rule"] for row in doc["actions"]
                              if row["value"]["method"] == "getIfThen"))
        trigger_id = next(iter(doc["triggers"]))
        self.backups_enabled(True)
        keys = self.backup_keys(rule_id)
        started = time.monotonic()
        edited = self.update(rule_id, [log_action("changed same ID", id=ids[0]),
            {"part": "delete", "value": {"part": "action", "id": ids[1]}},
            {"part": "delete", "value": {"part": "trigger", "id": trigger_id}},
            {"part": "delete", "value": {"part": "condition", "id": unused}},
            self.condition("off", id=a), change("required", tokens=[b]),
            change("expression", id=expression, tokens=[b]),
            change("variable", operation="set", name="Counter", value=99),
            change("variable", operation="create", name="Added", type="string", value="remove me"),
            log_action("new action must disappear"), self.trigger("*changed*")])
        self.assertTrue(edited.get("success"), edited)
        key = edited["backup"]["backupKey"]
        self.assertEqual(self.backup_keys(rule_id) - keys, {key})
        self.assertNotEqual(self.configuration(rule_id), before)
        print("BACKUP_EDIT", json.dumps({"rule_id": rule_id, "seconds": time.monotonic() - started,
            "backup": edited["backup"]}), flush=True)
        self.recovery(rule_id, key)
        self.assertEqual(self.configuration(rule_id), before)
        self.assertEqual(str(self.status(rule_id)["installedApp"]["id"]), str(rule_id))
        self.command("out", "off")
        self.command("switch", "on")
        self.wait_for(lambda: self.attribute("out", "switch") == "on")
        self.command("switch", "off")
        self.wait_for(lambda: self.attribute("out", "switch") == "off")
        print("BACKUP_BEHAVIOR", json.dumps({"rule_id": rule_id,
            "deleted_rows_restored": True, "same_id_payload_restored": True,
            "variables_restored": True, "native_trigger_and_wait_fired": True}), flush=True)

    def test_stopped_time_rule_recovers_clock_and_schedule(self):
        def clock(value, **extra):
            return change("trigger", capability="Certain Time (and optional date)", fields=[
                field("time#", "enum", "A specific time"),
                field("atTime#", "time", value)], **extra)

        rule_id = self.create([clock("2026-01-01T17:00:00.000-0500"), log_action("timed recovery")])
        self.call("hub_call_rule", ruleId=rule_id, action="stop")
        before = self.configuration(rule_id)
        trigger_id = next(iter(self.doc(rule_id)["triggers"]))
        edited = self.update(rule_id, [clock("2026-01-01T18:00:00.000-0500", id=trigger_id),
                                      log_action("remove timed edit")])
        self.assertTrue(edited.get("success"), edited)
        self.assertNotEqual(self.configuration(rule_id), before)
        self.recovery(rule_id, edited["backup"]["backupKey"])
        self.assertEqual(self.configuration(rule_id), before)
        self.assertTrue(self.state(rule_id).get("stopped"))
        self.call("hub_call_rule", ruleId=rule_id, action="start")
        self.wait_for(lambda: bool(self.status(rule_id).get("scheduledJobs")))

    def test_form_workflow_has_one_pre_edit_snapshot(self):
        rule_id = self.create([log_action("form recovery")])
        before = self.settings(rule_id).get("comments", {}).get("value", "")
        keys = self.backup_keys(rule_id)
        page = self.call("hub_set_rule", operation="form", rule_id=rule_id,
                         confirm=True, args={"steps": [{"kind": "open"}]})
        self.assertTrue(page.get("success"), page)
        key = page["backup"]["backupKey"]
        for value in ("first form edit", "second form edit"):
            page = self.call("hub_set_rule", operation="form", rule_id=rule_id,
                confirm=True, args={"steps": [{"kind": "submit",
                "expect_page": page["page"]["name"], "fields": {"comments": value}}]})
            self.assertTrue(page.get("success"), page)
            self.assertEqual(page["backup"]["backupKey"], key)
            self.assertEqual(self.settings(rule_id)["comments"]["value"], value)
        self.assertEqual(self.backup_keys(rule_id) - keys, {key})
        self.recovery(rule_id, key)
        self.assertEqual(self.settings(rule_id).get("comments", {}).get("value", ""), before)
        stale = self.call("hub_set_rule", operation="form", rule_id=rule_id, confirm=True,
            args={"steps": [{"kind": "submit", "expect_page": page["page"]["name"],
                            "fields": {"comments": "stale edit must not apply"}}]})
        self.assertIs(stale.get("success"), False, stale)
        self.assertEqual(self.settings(rule_id).get("comments", {}).get("value", ""), before)

    def test_reads_and_disabled_backups_do_not_capture(self):
        rule_id = self.create([log_action("capture control")])
        keys = self.backup_keys(rule_id)
        self.doc(rule_id)
        self.call("hub_set_rule")
        self.call("hub_set_rule", operation="update")
        self.assertEqual(self.backup_keys(rule_id), keys)
        self.backups_enabled(False)
        result = self.update(rule_id, [log_action("unprotected edit")])
        self.assertTrue(result.get("success"), result)
        self.assertTrue(result["backup"].get("skipped"), result)
        self.assertEqual(self.backup_keys(rule_id), keys)
        self.backups_enabled(True)
        result = self.update(rule_id, [log_action("protected edit")])
        self.assertTrue(result.get("success"), result)
        self.assertEqual(self.backup_keys(rule_id) - keys, {result["backup"]["backupKey"]})


if __name__ == "__main__":
    unittest.main(verbosity=2)
