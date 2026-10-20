"""RM action/trigger behavioral ports through document and native-form tools.

Opt-in virtual fixtures and owned-rule cleanup are enforced by ParityCase.
Historical names identify goals; retired request/response shapes are not aliases.
No backups or other native-app configuration are used by this suite.
"""

import html
import json
import re
import time
import unittest

import native_rm_parity_expressions_e2e as expression_helpers
from native_rm_parity_support import ParityCase, change, field, log_action


class NativeRmParityActions(ParityCase):
    _ok = expression_helpers.NativeRmParityExpressions._ok
    _refuse_unchanged = expression_helpers.NativeRmParityExpressions._refuse_unchanged
    _value = expression_helpers.NativeRmParityExpressions._value
    _devices = expression_helpers.NativeRmParityExpressions._devices
    _row = expression_helpers.NativeRmParityExpressions._row
    _switch_fields = expression_helpers.NativeRmParityExpressions._switch_fields
    _switch_condition = expression_helpers.NativeRmParityExpressions._switch_condition
    _required = expression_helpers.NativeRmParityExpressions._required
    _action = expression_helpers.NativeRmParityExpressions._action
    _form = expression_helpers.NativeRmParityExpressions._form
    _submit = expression_helpers.NativeRmParityExpressions._submit
    _control = expression_helpers.NativeRmParityExpressions._control

    def _ids(self, rule_id):
        return [str(row["id"]) for row in self.doc(rule_id)["actions"]]

    def _values(self, rule_id):
        values = {}
        for name, row in self.settings(rule_id).items():
            value = row.get("value")
            if str(row.get("type", "")).startswith("capability"):
                value = list(row["device_ids"])
            elif row.get("multiple") and isinstance(value, str):
                try:
                    value = json.loads(value)
                except ValueError:
                    pass
            values[name] = value
        return values

    def _switch(self, key="switch", on=True, **extra):
        return change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures[key])]),
            field("onOff.#", "bool", on),
        ], **extra)

    def _wait(self, events, **extra):
        return change("action", type="delayActs", subtype="getWaitEvents",
                      fields=[], events=events, **extra)

    def _event(self, key="switch", state="on", stays=None):
        fields = self._switch_fields(key=key, state=state, trigger=True)
        if stays is not None:
            fields += [field("stays#", "bool", True),
                       *[field(name + "#", "number", value)
                         for name, value in zip(("SHours", "SMins", "SSecs"), stays, strict=True)]]
        return {"capability": "Switch", "fields": fields}

    def _wait_row(self, rule_id):
        rows = [row for row in self.doc(rule_id)["actions"]
                if row["value"]["method"] == "getWaitEvents"]
        self.assertEqual(len(rows), 1, rows)
        action = rows[0]
        event_ids = [str(value) for value in action["value"]["wait"]]
        self.assertTrue(event_ids)
        self.assertEqual(len(event_ids), len(set(event_ids)))
        self.assertEqual(event_ids, [str(value) for value in
                         self.state(rule_id)["waitNdx"]["." + str(action["id"])]] )
        return action, ["-" + value for value in event_ids]

    def _assert_event(self, rule_id, slot, key="switch", state="on"):
        self.assertEqual(self._value(rule_id, "tCapab" + slot), "Switch")
        self.assertEqual(self._value(rule_id, "tstate" + slot), state)
        self._devices(rule_id, "tDev" + slot, key)

    def _error(self, result):
        self.assertIs(result.get("success"), False, result)
        errors = [str(result.get("error", ""))]
        errors += [str(row.get("result", {}).get("error", ""))
                   for row in result.get("results", [])]
        self.assertTrue(any(errors), result)
        return " ".join(errors)

    def test_set_rule_waitevents_on_offset_action_slot(self):
        rule_id = self.create([])
        condition = self._switch_condition(rule_id)
        self._required(rule_id, [condition])
        result = self._ok(rule_id, [log_action("offset base"), self._wait([self._event()])])
        self.assertEqual(len(result["results"]), 2)
        action, slots = self._wait_row(rule_id)
        self.assertEqual(len(slots), 1)
        self.assertNotEqual(str(action["id"]), self._ids(rule_id)[0])
        self._assert_event(rule_id, slots[0])
        values = self._values(rule_id)
        self.assertEqual([name for name in values if re.fullmatch(r"tstate-\d+", name)
                          and values[name] == "on"], ["tstate" + slots[0]])
        self.assertEqual(values["actSubType." + str(action["id"])], "getWaitEvents")
        self.assertEqual(self.doc(rule_id)["expressions"]["0"], [condition])
        self.healthy(rule_id)

    def test_set_rule_wait_events_mode_event(self):
        rule_id = self.create([self._wait([self._event()])])
        original, original_slots = self._wait_row(rule_id)
        action_id, order = str(original["id"]), self._ids(rule_id)
        form = self._form(rule_id, {"kind": "open", "page": "selectActions"})
        edit = [row for row in form["controls"] if row.get("kind") == "button"
                and row.get("name", "").split(".")[0] == action_id
                and row.get("control_id", "").endswith("||doAct")]
        self.assertEqual(len(edit), 1, form)
        form = self._submit(rule_id, form, control=edit[0])
        links = [row for row in form["controls"] if row.get("kind") == "href"
                 and row.get("page") == "doActPage"]
        self.assertEqual(len(links), 1, form)
        form = self._submit(rule_id, form, control=links[0])

        def wait_control(name):
            nonlocal form
            if not any(row.get("name") == name for row in form["controls"]):
                form = self._form(rule_id, {"kind": "open", "page": "doActPage"})
            return self._control(form, name=name)

        control = wait_control("anotherWait")
        form = self._submit(rule_id, form, control=control)
        # Native anotherWait requests a same-page submit to expose its new slot.
        form = self._form(rule_id, {"kind": "open", "page": "doActPage"})
        capabilities = [row["name"] for row in form["fields"]
                        if re.fullmatch(r"tCapab-\d+", row["name"])
                        and row["name"].removeprefix("tCapab") not in original_slots]
        self.assertEqual(len(capabilities), 1, form)
        added_slot = capabilities[0].removeprefix("tCapab")
        form = self._submit(rule_id, form, fields={capabilities[0]: "Mode"})
        form = self._submit(rule_id, form, fields={"modesX" + added_slot: [str(self.mode_id)]})
        control = wait_control("hasAll")
        form = self._submit(rule_id, form, control=control)
        control = wait_control("doneWaits")
        form = self._submit(rule_id, form, control=control)
        for _ in range(3):
            compiled = next(row for row in self.doc(rule_id)["actions"]
                            if str(row["id"]) == action_id)
            if len(compiled["value"]["wait"]) == 2:
                break
            form = self._form(rule_id, {"kind": "open", "page": "doActPage"})
        self._form(rule_id, {"kind": "open", "page": "selectActions"})
        action, slots = self._wait_row(rule_id)
        self.assertEqual(str(action["id"]), action_id)
        self.assertEqual(self._ids(rule_id), order)
        self.assertEqual(len(slots), 2)
        self.assertEqual(slots, original_slots + [added_slot])
        self._assert_event(rule_id, slots[0])
        self.assertEqual(self._value(rule_id, "tCapab" + slots[1]), "Mode")
        values = self._values(rule_id)
        self.assertEqual([str(value) for value in values["modesX" + slots[1]]],
                         [str(self.mode_id)])
        self.assertNotIn("tstate" + slots[1], values)
        typed = self.settings(rule_id)
        self.assertTrue(typed["tDev" + slots[0]]["type"].startswith("capability"))
        for name in ("tCapab" + slots[0], "tstate" + slots[0],
                     "tCapab" + slots[1], "modesX" + slots[1]):
            self.assertEqual(typed[name]["type"], "enum", typed[name])
        self.healthy(rule_id)

    def test_set_rule_wait_events_and_stays_duration(self):
        rule_id = self.create([self._wait([self._event(stays=(0, 5, 0))])])
        _, slots = self._wait_row(rule_id)
        self.assertEqual(len(slots), 1)
        slot = slots[0]
        self._assert_event(rule_id, slot)
        self.assertIn(self._value(rule_id, "stays" + slot), (True, "true"))
        self.assertEqual([int(self._value(rule_id, name + slot))
                          for name in ("SHours", "SMins", "SSecs")], [0, 5, 0])
        native = self.state(rule_id)
        self.assertEqual([int(native[name + slot]) for name in
                          ("SHours", "SMins", "SSecs")], [0, 5, 0])
        self.assertEqual([int(value) for value in native["hhmmss" + slot].split(":")],
                         [0, 5, 0])
        self.healthy(rule_id)

    def test_set_rule_action_expression_reject_is_pre_write(self):
        rule_id = self.create([log_action("unchanged expression baseline")])
        for type_name, subtype in (("condActs", "getIfThen"),
                                   ("condActs", "getElseIf"),
                                   ("repeatActs", "getWhile"),
                                   ("delayActs", "getWaitRule")):
            with self.subTest(subtype=subtype):
                for tokens in (["999999"], ["("], ["AND", "999999"]):
                    self._refuse_unchanged(rule_id, [change(
                        "action", type=type_name, subtype=subtype,
                        fields=[], expression=tokens)])
                self.healthy(rule_id)
        self.assertEqual(len(self._ids(rule_id)), 1)
        self.assertNotIn("Broken Condition", self.page_text(rule_id))

    def test_set_rule_enum_doactpage(self):
        rule_id = self.create([])
        condition = self._switch_condition(rule_id, custom=True)
        self._ok(rule_id, [change("action", type="condActs", subtype="getIfThen",
                                 fields=[], expression=[condition]),
                           log_action("fired"), change("terminator", subtype="endIf")])
        self._devices(rule_id, "rDev_" + condition, "switch")
        self.assertEqual(self._value(rule_id, "rCustomAttr_" + condition), "switch")
        self.assertEqual(self._value(rule_id, "state_" + condition), "on")
        actions = self.doc(rule_id)["actions"]
        self.assertEqual([row["value"]["method"] for row in actions],
                         ["getIfThen", "getLogMsg", "getEndIf"])
        expression_id = str(actions[0]["value"]["rule"])
        self.assertEqual(self.doc(rule_id)["expressions"][expression_id], [condition])
        page = self.page_text(rule_id)
        self.assertIn("fired", page)
        self.assertNotIn("Broken Condition", page)
        self.healthy(rule_id)

    def test_set_rule_move_action(self):
        rule_id = self.create([log_action(message) for message in ("a", "b", "c")])
        ids = self._ids(rule_id)
        self.assertEqual(len(set(ids)), 3)
        before = self._values(rule_id)
        wanted = [ids[1], ids[0], ids[2]]
        self._ok(rule_id, [change("order", ids=wanted)])
        self.assertEqual(self._ids(rule_id), wanted)
        self.assertEqual([str(value) for value in self.state(rule_id)["actionList"]], wanted)
        self.assertEqual(self._values(rule_id), before)
        self._refuse_unchanged(rule_id, [change("order", ids=[ids[0], ids[0], ids[2]])])
        self.healthy(rule_id)

    def test_set_rule_trigger_mutations(self):
        rule_id = self.create([change("trigger", capability="Switch",
                                      fields=self._switch_fields(trigger=True)), self._switch()])
        self.assertEqual(len(self.doc(rule_id)["triggers"]), 1)
        self.assertEqual(len(self.doc(rule_id)["actions"]), 1)
        original = set(self.doc(rule_id)["triggers"])
        trigger = self._row(rule_id, "trigger", "Switch", self._switch_fields(trigger=True))
        self._ok(rule_id, [change("trigger", id=trigger, capability="Switch",
                                  fields=self._switch_fields(state="off", trigger=True))])
        self.assertEqual(self._value(rule_id, "tstate" + trigger), "off")
        self.assertEqual(set(self.doc(rule_id)["triggers"]), original | {trigger})
        self._refuse_unchanged(rule_id, [change("trigger", id="999999", capability="Switch",
                                               fields=self._switch_fields(trigger=True))])
        result = self._ok(rule_id, [{"part": "delete", "value": {"part": "trigger", "id": trigger}},
            change("trigger", capability="Switch", fields=self._switch_fields(trigger=True))])
        self.assertEqual([row["part"] for row in result["results"]], ["delete", "trigger"])
        self.assertTrue(result["results"][0]["result"]["deleted"])
        new_trigger = str(result["results"][1]["result"]["id"])
        self._ok(rule_id, [change("trigger", id=new_trigger, capability="Switch",
                                  fields=self._switch_fields(state="off", trigger=True))])
        self.assertEqual(self._value(rule_id, "tstate" + new_trigger), "off")
        self._ok(rule_id, [{"part": "delete", "value": {"part": "trigger", "id": new_trigger}}])
        self.assertEqual(set(self.doc(rule_id)["triggers"]), original)
        for prefix in ("tCapab", "tDev", "tstate"):
            self.assertNotIn(prefix + new_trigger, self.settings(rule_id))
        self.healthy(rule_id)
        before_states = [row["value"] for name, row in self.settings(rule_id).items()
                         if re.fullmatch(r"tstate\d+", name)]
        stopped = self.update(rule_id, [
            change("trigger", capability="Switch", fields=self._switch_fields(state="off", trigger=True)),
            change("trigger", capability="Switch", fields=self._switch_fields(state="purple", trigger=True)),
            change("trigger", capability="Switch", fields=self._switch_fields(trigger=True)),
            log_action("trigger-stop-skipped"),
        ])
        self.assertIn("tstate", self._error(stopped))
        self.assertIs(stopped.get("partial"), True, stopped)
        self.assertEqual(len(stopped["results"]), 2)
        self.assertEqual(len(stopped["remaining"]), 2)
        after_states = [row["value"] for name, row in self.settings(rule_id).items()
                        if re.fullmatch(r"tstate\d+", name)]
        self.assertEqual(after_states.count("off"), before_states.count("off") + 1)
        self.assertEqual(after_states.count("on"), before_states.count("on"))
        self.assertNotIn("purple", after_states)
        self.assertNotIn("trigger-stop-skipped", self.page_text(rule_id))
        unused = self._switch_condition(rule_id)
        refused = self.update(rule_id, [change("trigger", capability="Switch", fields=[
            *self._switch_fields(state="purple", trigger=True),
            field("isCondTrig.#", "bool", True), field("condTrig.#", "enum", unused),
        ])])
        self._error(refused)
        self.assertIn(unused, self.doc(rule_id)["conditions"])
        bound = self._row(rule_id, "condition", "Switch", self._switch_fields(state="off"))
        trigger = self._row(rule_id, "trigger", "Switch", [
            *self._switch_fields(trigger=True), field("isCondTrig.#", "bool", True),
            field("condTrig.#", "enum", bound),
        ])
        self.assertEqual(str(self._value(rule_id, "condTrig." + trigger)), bound)
        self.assertNotEqual(bound, unused)
        self.assertEqual(self._value(rule_id, "tstate" + trigger), "on")
        self.healthy(rule_id)


    def _delete(self, part, row_id):
        return {"part": "delete", "value": {"part": part, "id": str(row_id)}}

    def _stop(self, rule_id, changes, kept, absent):
        result = self.update(rule_id, changes)
        error = self._error(result)
        self.assertIs(result.get("partial"), True, result)
        rows = result.get("results", [])
        self.assertTrue(rows, result)
        self.assertTrue(rows[0]["result"].get("success"), result)
        self.assertIs(rows[-1]["result"].get("success"), False, result)
        self.assertTrue(result.get("remaining"), result)
        page = self.page_text(rule_id)
        for marker in kept:
            self.assertIn(marker, page, result)
        for marker in absent:
            self.assertNotIn(marker, page, result)
        return result, error

    def test_set_rule_action_mutations(self):
        rule_id = self.create([log_action("remove-me-marker")])
        marker = self._ids(rule_id)[0]
        self._ok(rule_id, [log_action("one"), log_action("two")])
        self.assertEqual(len(self._ids(rule_id)), 3)
        self._ok(rule_id, [self._delete("action", marker)])
        self.assertNotIn(marker, self._ids(rule_id))
        self.assertNotIn("logmsg." + marker, self.settings(rule_id))
        self.assertNotIn("remove-me-marker", self.page_text(rule_id))
        self._ok(rule_id, [self._delete("action", index) for index in self._ids(rule_id)])
        self.assertEqual(self._ids(rule_id), [])
        self._ok(rule_id, [log_action("final")])
        self.healthy(rule_id)

        bad_custom = change("action", type="modeActs", subtype="getDefinedAction", fields=[
            field("myCapab.#", "enum", "E2E Not A Capability"),
            field("devices.#", "capability.switch", [str(self.fixtures["switch"])]),
            field("cCmd.#", "enum", "on"),
        ])
        _, error = self._stop(rule_id, [log_action("bulk-kept"), bad_custom,
            log_action("bulk-skipped")], ["bulk-kept", "final"], ["bulk-skipped"])
        self.assertIn("myCapab", error)

        # A malformed replacement is refused before any preceding delete occurs.
        for bad in (change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
                        {"name": "onOff.#", "type": "bool"}]),
                    change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
                        field("onOff.#", "bool", "blink")])):
            with self.subTest(replacement=bad):
                self._refuse_unchanged(rule_id,
                    [*[self._delete("action", index) for index in self._ids(rule_id)],
                     log_action("preclear-must-not-land"), bad])
        # A native-dependent refusal after a valid clear keeps only the clean prefix.
        missing = change("action", type="modeActs", subtype="getDeleteLocalFile", fields=[
            field("deleteFile.#", "enum", self.prefix + "_missing_file.txt")])
        self._stop(rule_id, [*[self._delete("action", index) for index in self._ids(rule_id)],
            log_action("replace-kept"), missing, log_action("replace-skipped")],
            ["replace-kept"], ["bulk-kept", "final", "replace-skipped"])
        self.assertEqual(len(self._ids(rule_id)), 1)
        self.healthy(rule_id)

    def test_set_rule_patches(self):
        rule_id = self.create([])
        result = self._ok(rule_id, [log_action("p1"), log_action("p2")])
        self.assertEqual([row["part"] for row in result["results"]], ["action", "action"])
        self.assertEqual(len(self._ids(rule_id)), 2)
        self.healthy(rule_id)
        current = self._form(rule_id, {"kind": "open"})
        before = self.snapshot(rule_id)
        refused = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[{
            "kind": "submit", "expect_page": current["page"]["name"],
            "fields": {"origLabel": {"a": "b"}},
        }])
        self._error(refused)
        self.assertEqual(self.snapshot(rule_id), before)
        self._stop(rule_id, [log_action("patch-kept"), change(
            "action", type="messageActs", subtype="getLogMsg", fields=[
                field("logmsg.#", "textarea", "partial-marker"),
                field("noSuchField.#", "number", 1)]), log_action("patch-skipped")],
            ["patch-kept"], ["patch-skipped"])
        # New documents use one flat ordered list; inner list coordinates are
        # retired, while failed-item and later-operation stop goals stay intact.
        self._stop(rule_id, [log_action("inner-kept"), change(
            "action", type="modeActs", subtype="getDefinedAction", fields=[
                field("myCapab.#", "enum", "E2E Not A Capability")]),
            log_action("inner-skipped"), log_action("later-skipped")],
            ["inner-kept"], ["inner-skipped", "later-skipped"])
        self.healthy(rule_id)

    def _new_editor(self, rule_id):
        form = self._form(rule_id, {"kind": "open", "page": "selectActions"})
        controls = [row for row in form["controls"] if row.get("kind") == "button"
                    and row.get("control_id") == "N||doAct"]
        self.assertEqual(len(controls), 1, form)
        form = self._submit(rule_id, form, control=controls[0])
        links = [row for row in form["controls"] if row.get("kind") == "href"
                 and row.get("page") == "doActPage"]
        self.assertEqual(len(links), 1, form)
        form = self._submit(rule_id, form, control=links[0])
        names = [row["name"] for row in form["fields"] if row["name"].startswith("actType.")]
        self.assertEqual(len(names), 1, form)
        return form, names[0].split(".", 1)[1]

    def test_set_rule_walkstep_drive(self):
        rule_id = self.create([])
        result = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[
            {"kind": "open", "page": "selectTriggers"},
            {"kind": "open", "page": "mainPage"},
        ])
        self.assertTrue(result.get("success"), result)
        self.assertEqual([row["page"] for row in result["completed"]],
                         ["selectTriggers", "mainPage"])
        before = self.snapshot(rule_id)
        bad = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[
            {"kind": "open", "page": "mainPage"}, {"kind": "bogus_op"},
            {"kind": "submit", "fields": {"comments": "must-not-land"}},
        ])
        self._error(bad)
        self.assertEqual(self.snapshot(rule_id), before)
        current = self._form(rule_id, {"kind": "open", "page": "selectTriggers"})
        before = self.snapshot(rule_id)
        for fields, control in (({"tCapab999999": "Switch"}, None),
                                 ({}, "noSuchButtonE2E")):
            step = {"kind": "submit", "expect_page": current["page"]["name"], "fields": fields}
            if control is not None:
                step["activate"] = control
            bad = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[step])
            self._error(bad)
            self.assertEqual(self.snapshot(rule_id), before)

        form, index = self._new_editor(rule_id)
        page = form["page"]["name"]
        result = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[
            {"kind": "submit", "expect_page": page,
             "fields": {"actType." + index: "messageActs"}},
            {"kind": "submit", "expect_page": page,
             "fields": {"actSubType." + index: "getLogMsg"}},
            {"kind": "submit", "expect_page": page,
             "fields": {"logmsg." + index: "drive-complete"}},
        ])
        self.assertTrue(result.get("success"), result)
        self.assertEqual(len(result["completed"]), 3)
        self._submit(rule_id, result, control=self._control(result, name="actionDone"))
        self.assertEqual(self._value(rule_id, "logmsg." + index), "drive-complete")
        self.assertEqual(self._ids(rule_id), [index])
        self.healthy(rule_id)
        # Native controls and names are discovered anew after every dependency.
        trigger_rule = self.create([])
        current = self._form(trigger_rule, {"kind": "open", "page": "selectTriggers"})
        if not any(row["name"].startswith("tCapab") for row in current["fields"]):
            current = self._submit(trigger_rule, current,
                control=self._control(current, title="Create New Trigger"))
        capability = [row["name"] for row in current["fields"]
                      if row["name"].startswith("tCapab")]
        self.assertEqual(len(capability), 1, current)
        slot = capability[0].removeprefix("tCapab")
        current = self._submit(trigger_rule, current, fields={capability[0]: "Switch"})
        current = self._submit(trigger_rule, current,
                               fields={"tDev" + slot: [str(self.fixtures["switch"])]})
        before = self.snapshot(trigger_rule)
        rejected = self.call("hub_call_app_form", app_id=trigger_rule, confirm=True, steps=[{
            "kind": "submit", "expect_page": current["page"]["name"],
            "fields": {"tstate" + slot: "E2E Not A State"}}])
        self.assertIn("tstate", self._error(rejected))
        self.assertEqual(self.snapshot(trigger_rule), before)
        current = self._form(trigger_rule, {"kind": "open", "page": "selectTriggers"})
        current = self._submit(trigger_rule, current,
                               fields={"tDev" + slot: [str(self.fixtures["switch2"])]})
        self._devices(trigger_rule, "tDev" + slot, "switch2")
        current = self._submit(trigger_rule, current, fields={"tstate" + slot: "on"})
        self._submit(trigger_rule, current, control=self._control(current, name="hasAll"))
        self.assertIn(slot, self.doc(trigger_rule)["triggers"])
        self.healthy(trigger_rule)

        form, repeat_id = self._new_editor(rule_id)
        repeat_page = form["page"]["name"]
        repeated = self.call("hub_call_app_form", app_id=rule_id, confirm=True, steps=[
            {"kind": "submit", "expect_page": repeat_page,
             "fields": {"actType." + repeat_id: "repeatActs"}},
            {"kind": "submit", "expect_page": repeat_page,
             "fields": {"actSubType." + repeat_id: "getRepeat"}},
            {"kind": "submit", "expect_page": repeat_page,
             "fields": {"repeatMinute." + repeat_id: 5}},
        ])
        self.assertTrue(repeated.get("success"), repeated)
        committed = self._submit(rule_id, repeated,
                                 control=self._control(repeated, name="actionDone"))
        self.assertTrue(committed.get("success"), committed)
        health = self.call("hub_get_rule_health", appId=rule_id)
        self.assertTrue(any("repeat" in str(issue).lower()
                            for issue in health.get("structuralIssues", [])), health)
        form, inside_id = self._new_editor(rule_id)
        for name, value in (("actType.", "messageActs"), ("actSubType.", "getLogMsg"),
                            ("logmsg.", "inside-open-repeat")):
            form = self._submit(rule_id, form, fields={name + inside_id: value})
        self._submit(rule_id, form, control=self._control(form, name="actionDone"))
        self.assertIn("inside-open-repeat", self.page_text(rule_id))
        self.assertIn("drive-complete", self.page_text(rule_id))
        health = self.call("hub_get_rule_health", appId=rule_id)
        self.assertTrue(any("repeat" in str(issue).lower()
                            for issue in health.get("structuralIssues", [])), health)
        self._ok(rule_id, [change("terminator", subtype="endRep")])
        self.healthy(rule_id)


    def _typed_action(self, type_name, subtype, fields, **extra):
        return change("action", type=type_name, subtype=subtype, fields=[
            field(name + ".#", type_, value) for name, type_, value in fields], **extra)

    def _catalog(self, expected):
        rule_id = self.create([entry[0] for entry in expected])
        rows = self.doc(rule_id)["actions"]
        self.assertEqual(len(rows), len(expected))
        values = self._values(rule_id)
        page = html.unescape(re.sub(r"<[^>]+>", "", self.page_text(rule_id)))
        for action, (_, stored, text) in zip(rows, expected, strict=True):
            slot = "." + str(action["id"])
            for key, wanted in stored.items():
                actual = values[key + slot]
                if isinstance(wanted, bool):
                    self.assertEqual(str(actual).lower(), str(wanted).lower())
                elif isinstance(wanted, list):
                    self.assertEqual(list(map(str, actual)), list(map(str, wanted)))
                elif isinstance(wanted, (int, float)):
                    self.assertEqual(float(actual), float(wanted))
                else:
                    self.assertEqual(actual, wanted)
            if isinstance(text, re.Pattern):
                self.assertRegex(page, text)
            elif text is not None:
                self.assertIn(text, page)
        self.healthy(rule_id)
        return rule_id

    def test_rm_action_device_catalog(self):
        keys = ("garage", "valve", "blind", "tone", "player")
        labels = {key: self.fixture_metadata(key)["device"]["label"] for key in keys}
        devices = {key: [str(self.fixtures[key])] for key in keys}
        variable = self.create_hub_variable("catalog_number", "Number", 7)
        track = "http://x/a.mp3"
        doors = []
        for key, subtype, boolean_name, device_name, capability, verbs in (
            ("garage", "getOCGarage", "garageRL", "garageOpenClose", "garageDoorControl", ("Close", "Open")),
            ("valve", "getOCValve", "valveRL", "valveOpenClose", "valve", ("Open", "Close")),
        ):
            for verb in verbs:
                closed = verb == "Close"
                doors.append((self._typed_action("lockActs", subtype, [
                    (boolean_name, "bool", closed),
                    (device_name, "capability." + capability, devices[key]),
                ]), {boolean_name: closed, device_name: devices[key]}, verb + ": " + labels[key]))
        doors.append((self._typed_action("soundActs", "getTone", [
            ("toner", "capability.tone", devices["tone"])]),
            {"toner": devices["tone"]}, "Beep: " + labels["tone"]))
        self._catalog(doors)
        variable_pattern = re.escape(variable) + r"(?![A-Za-z0-9_])"
        media = [
            (self._typed_action("sceneActs", "getBlindTilt", [
                ("blindTilt", "capability.windowBlind", devices["blind"]),
                ("tiltLevel", "number", 40)]),
             {"blindTilt": devices["blind"], "tiltLevel": 40},
             "Tilt Blinds: " + labels["blind"] + " to 40"),
            (self._typed_action("sceneActs", "getBlindTilt", [
                ("blindTilt", "capability.windowBlind", devices["blind"]),
                ("uVar", "bool", True), ("xVar", "enum", variable)]),
             {"blindTilt": devices["blind"], "uVar": True, "xVar": variable},
             re.compile(re.escape("Tilt Blinds: " + labels["blind"] + " to ") + variable_pattern)),
        ]
        for command, input_name, type_name, value, suffix in (
            ("set volume", "playerLevel", "number", 30, " --> Set Level to 30"),
            ("play track", "playerTrack", "text", track, ": Play Track: '" + track + "'"),
            ("play text", "playerText", "text", "hello", ": Play Text: 'hello'"),
        ):
            media.append((self._typed_action("soundActs", "getSetMusicPlayer", [
                ("player", "capability.musicPlayer", devices["player"]),
                ("playerCommand", "enum", command), (input_name, type_name, value)]),
                {"player": devices["player"], "playerCommand": command, input_name: value},
                labels["player"] + suffix))
        media.append((self._typed_action("soundActs", "getSetMusicPlayer", [
            ("player", "capability.musicPlayer", devices["player"]),
            ("playerCommand", "enum", "set volume"), ("uVar", "bool", True),
            ("xVar", "enum", variable)]),
            {"playerCommand": "set volume", "uVar": True, "xVar": variable},
            re.compile(re.escape(labels["player"] + " --> Set Level to ") + variable_pattern)))
        rule_id = self._catalog(media)
        literal_tilt = self._ids(rule_id)[0]
        self.assertNotIn(str(self._value(rule_id, "uVar." + literal_tilt)).lower(), ("true", "on"))
        self._refuse_unchanged(rule_id, [self._typed_action("soundActs", "getSetMusicPlayer", [
            ("player", "capability.musicPlayer", devices["player"]),
            ("playerCommand", "enum", "play track and restore"),
            ("playerTrack", "text", track)])])
        self.healthy(rule_id)

    def test_rm_action_return_value(self):
        number = self.create_hub_variable("return_number", "Number", 7)
        string = self.create_hub_variable("return_string", "String", "text")
        rule_id = self.create([])
        self._refuse_unchanged(rule_id, [self._typed_action("modeActs", "getReturnValue", [
            ("valType", "enum", "Integer"), ("valInt", "number", 5)])])
        form = self._form(rule_id, {"kind": "open"})
        self.assertTrue(any(row["name"] == "isFunction" for row in form["fields"]), form)
        self._submit(rule_id, form, fields={"isFunction": True})
        self.assertIn(self._value(rule_id, "isFunction"), (True, "true"))
        expected = [
            ([('valType', 'enum', 'Integer'), ('valInt', 'number', 5)],
             {"valType": "Integer", "valInt": 5}, "Return '5'"),
            ([('valType', 'enum', 'String'), ('valString', 'textarea', 'done')],
             {"valType": "String", "valString": "done"}, "Return 'done'"),
            ([('valType', 'enum', 'Decimal'), ('valDec', 'decimal', 2.5)],
             {"valType": "Decimal", "valDec": 2.5}, None),
            ([('valType', 'enum', 'Boolean'), ('valBool', 'enum', 'false')],
             {"valType": "Boolean", "valBool": "false"}, "Return 'false'"),
            ([('valType', 'enum', 'Integer'), ('useVar', 'bool', True), ('valVar', 'enum', number)],
             {"valType": "Integer", "useVar": True, "valVar": number},
             re.compile("Return '" + re.escape(number) + "'")),
        ]
        self._ok(rule_id, [self._typed_action("modeActs", "getReturnValue", fields)
                           for fields, _, _ in expected])
        self.assertEqual(len(self._ids(rule_id)), 5)
        values = self._values(rule_id)
        page = html.unescape(re.sub(r"<[^>]+>", "", self.page_text(rule_id)))
        for index, (_, stored, text) in zip(self._ids(rule_id), expected, strict=True):
            for name, wanted in stored.items():
                actual = values[name + "." + index]
                if isinstance(wanted, bool):
                    self.assertEqual(str(actual).lower(), str(wanted).lower())
                elif isinstance(wanted, (int, float)):
                    self.assertEqual(float(actual), wanted)
                else:
                    self.assertEqual(actual, wanted)
            if isinstance(text, re.Pattern):
                self.assertRegex(page, text)
            elif text is not None:
                self.assertIn(text, page)
        original_order = self._ids(rule_id)
        form, descriptor_id = self._new_editor(rule_id)
        for name, value in ((f"actType.{descriptor_id}", "modeActs"),
                            (f"actSubType.{descriptor_id}", "getReturnValue"),
                            (f"valType.{descriptor_id}", "Integer"),
                            (f"useVar.{descriptor_id}", True)):
            form = self._submit(rule_id, form, fields={name: value})
        picker = next(row for row in form["fields"] if row["name"] == f"valVar.{descriptor_id}")
        self.assertEqual(picker["type"], "enum")
        offered = {row["value"] for row in picker["options"]}
        self.assertIn(number, offered)
        self.assertNotIn(string, offered)
        form = self._submit(rule_id, form, fields={picker["name"]: number})
        self._submit(rule_id, form, control=self._control(form, name="actionDone"))
        self.assertEqual(self._value(rule_id, f"valVar.{descriptor_id}"), number)
        self._ok(rule_id, [{"part": "delete", "value": {"part": "action", "id": descriptor_id}}])
        self.assertEqual(self._ids(rule_id), original_order)
        refused = self._refuse_unchanged(rule_id, [self._typed_action("modeActs", "getReturnValue", [
            ("valType", "enum", "Integer"), ("useVar", "bool", True),
            ("valVar", "enum", string)])])
        error = self._error(refused)
        self.assertIn("valVar", error)
        self.healthy(rule_id)

    def _edit(self, rule_id, row_id, type_name, subtype, fields, expected):
        order = self._ids(rule_id)
        before = self.settings(rule_id)
        self._ok(rule_id, [self._typed_action(type_name, subtype, fields, id=row_id)])
        self.assertEqual(self._ids(rule_id), order)
        after = self.settings(rule_id)
        changed = {name for name in set(before) | set(after) if before.get(name) != after.get(name)}
        self.assertTrue(all(name.endswith("." + str(row_id)) for name in changed), changed)
        values = self._values(rule_id)
        for name, wanted in expected.items():
            actual = values[name + "." + str(row_id)]
            self.assertEqual(str(actual).lower() if isinstance(wanted, bool) else actual,
                             str(wanted).lower() if isinstance(wanted, bool) else wanted)
        self.healthy(rule_id)

    def test_set_rule_modifyaction_retarget(self):
        for key in ("button", "blind", "dimmer", "color"):
            self.fixture_metadata(key)
        variable = self.create_hub_variable("edit_number", "Number", 7)
        target_a = self.create([])
        target_b = self.create([])
        caller = self.create([self._typed_action("rulesActs", "getRuleActions", [
            ("runRuleType", "enum", "Rule Machine"),
            ("ruleAct", "enum", [str(target_a)])]), log_action("order sentinel")])
        first, sentinel = self._ids(caller)
        self._edit(caller, first, "rulesActs", "getRuleActions",
                   [("ruleAct", "enum", [str(target_b)])], {"ruleAct": [str(target_b)]})
        self.assertEqual(self._ids(caller), [first, sentinel])
        page = self.page_text(caller)
        self.assertLess(page.index("Run Actions:"), page.index("order sentinel"))
        self.assertEqual(sum(row["value"]["method"] == "getRuleActions"
                             for row in self.doc(caller)["actions"]), 1)
        private = self._action(caller, self._typed_action("rulesActs", "getSetPrivateBoolean", [
            ("pvRuleType", "enum", "Rule Machine"), ("pvTF", "bool", True),
            ("privateT", "enum", ["*"])]))
        for value in (False, True):
            self._edit(caller, private, "rulesActs", "getSetPrivateBoolean",
                       [("pvTF", "bool", value)], {"privateT": ["*"], "pvTF": value})
        # These are authoring-only rows: no mode, button or bulb commands are run.
        actions = [
            self._typed_action("modeActs", "getSetMode", [("mode", "enum", str(self.mode_id))]),
            self._typed_action("modeActs", "getSetVariable", [
                ("xVarV", "enum", variable), ("numOp", "enum", "number"), ("valNumber", "number", 1)]),
            self._typed_action("switchActs", "getPushButton", [
                ("pushButton", "capability.pushableButton", str(self.fixtures["button"])),
                ("pushButNo", "number", 1), ("pushButOp", "enum", "hold")]),
            self._typed_action("sceneActs", "getBlindTilt", [
                ("blindTilt", "capability.windowBlind", [str(self.fixtures["blind"])]),
                ("tiltLevel", "number", 40)]),
            self._typed_action("dimmerActs", "getSetDimmer", [
                ("dimA", "capability.switchLevel", [str(self.fixtures["dimmer"])]),
                ("dimLA", "number", 50)]),
            self._typed_action("dimmerActs", "getSetColor", [
                ("bulbs", "capability.colorControl", [str(self.fixtures["color"])]),
                ("color", "enum", "Red")]),
        ]
        self._ok(target_b, actions)
        mode, var, button, blind, dimmer, color = self._ids(target_b)
        self.assertEqual(self._value(target_b, "pushButOp." + button), "hold")
        self.assertEqual(self._value(target_b, "color." + color), "Red")
        for index, type_name, subtype, fields in (
            (mode, "modeActs", "getSetMode", [("mode", "enum", "999999")]),
            (var, "modeActs", "getSetVariable", [("xVarV", "enum", self.prefix + "_missing_var")]),
            (button, "switchActs", "getPushButton", [("pushButton", "capability.pushableButton", str(self.fixtures["switch"]))]),
            (button, "switchActs", "getPushButton", [("pushButOp", "enum", "squeeze")]),
        ):
            with self.subTest(invalid=fields):
                self._refuse_unchanged(target_b, [self._typed_action(type_name, subtype, fields, id=index)])
        self._edit(target_b, button, "switchActs", "getPushButton",
                   [("pushButOp", "enum", "push")], {"pushButOp": "push"})
        for index, subtype, literal_name, literal_value in (
            (blind, "getBlindTilt", "tiltLevel", 55), (dimmer, "getSetDimmer", "dimLA", 30),
        ):
            type_name = "sceneActs" if index == blind else "dimmerActs"
            self._edit(target_b, index, type_name, subtype,
                       [("uVar", "bool", True), ("xVar", "enum", variable)],
                       {"uVar": True, "xVar": variable})
            self.assertIn(variable, html.unescape(self.page_text(target_b)))
            self._edit(target_b, index, type_name, subtype,
                       [("uVar", "bool", False), (literal_name, "number", literal_value)],
                       {"uVar": False})
            self.assertEqual(float(self._value(target_b, literal_name + "." + index)), literal_value)
        self._edit(target_b, color, "dimmerActs", "getSetColor", [
            ("color", "enum", "Custom HSB color"), ("colorHex", "number", 120),
            ("colorSat", "number", 80)], {"color": "Custom HSB color"})
        self.assertEqual(float(self._value(target_b, "colorHex." + color)), 120)
        self.assertEqual(float(self._value(target_b, "colorSat." + color)), 80)
        self._edit(target_b, color, "dimmerActs", "getSetColor", [
            ("color", "enum", "Custom RGB color"), ("colorH", "text", "#00ff00")],
            {"color": "Custom RGB color", "colorH": "#00ff00"})
        self._refuse_unchanged(target_b, [self._typed_action("dimmerActs", "getSetColor", [
            ("colorLevel", "number", 40)], id=color)])
        self.assertEqual(self._value(target_b, "colorH." + color).lower(), "#00ff00")
        batch_ids = [target_a, target_b]
        paused = self.call("hub_set_rule_paused", ruleId=batch_ids, paused=True)
        if paused.get("success") is False:
            self.assertIn("excessive hub load", str(paused.get("error", "")))
        else:
            self.assertEqual(sorted(map(str, paused.get("ruleIds", []))),
                             sorted(map(str, batch_ids)))
        pairs = []
        for _ in range(5):
            pair = tuple(self.call("hub_get_rule_health", appId=index).get("paused")
                         for index in batch_ids)
            pairs.append(pair)
            if len(pairs) > 1 and pair == pairs[-2] and pair in ((True, True), (False, False)):
                break
            time.sleep(1)
        self.assertGreaterEqual(len(pairs), 2)
        self.assertEqual(pairs[-1], pairs[-2])
        self.assertIn(pairs[-1], ((True, True), (False, False)))
        if paused.get("success") is not False:
            self.assertEqual(pairs[-1], (True, True))


if __name__ == "__main__":
    unittest.main(verbosity=2)
