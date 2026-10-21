"""Historical RM lifecycle goals through the current document/form surface.

Opt-in live suite; shared support owns authorization, fixture validation and cleanup.
Historical method names identify the source goals, not retired tool compatibility.
Backup assertions are deliberately outside this suite. Native metadata replaces the
retired synthetic authoring catalog; stopped-rule edits preserve stopped status.
"""

import json
import unittest

from native_rm_parity_support import ParityCase, change, field, log_action


class NativeRmParityLifecycleE2E(ParityCase):
    def _form(self, app_id, page=None):
        step = {"kind": "open"}
        if page:
            step["page"] = page
        result = self.call("hub_set_rule", operation="form", rule_id=int(app_id), confirm=True, args={"steps": [step]})
        self.assertTrue(result.get("success"), result)
        return result

    def _submit(self, app_id, current, fields=None, control=None):
        step = {"kind": "submit", "expect_page": current["page"]["name"]}
        if fields is not None:
            step["fields"] = fields
        if control is not None:
            step["activate"] = control["control_id"]
        return self.call("hub_set_rule", operation="form", rule_id=int(app_id), confirm=True, args={"steps": [step]})

    def _trigger(self, state="on", **extra):
        return change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [int(self.fixtures["switch"])]),
            field("tstate#", "enum", state),
        ], **extra)

    def _values(self, app_id):
        values = {}
        for name, row in self.settings(app_id).items():
            value = row.get("value")
            if row.get("type", "").startswith("capability"):
                value = row.get("device_ids", [])
            elif row.get("multiple") and isinstance(value, str):
                try:
                    value = json.loads(value)
                except ValueError:
                    pass
            values[name] = value
        return values

    def _listed(self, app_id):
        rows = self.call("hub_list_rules").get("rules", [])
        return next(row for row in rows if str(row["id"]) == str(app_id))

    def test_periodic_trigger_deletion_preserves_other_rows(self):
        target = self.create([self._trigger(), log_action("periodic deletion baseline")])
        before = self.doc(target)
        form = self._form(target, "selectTriggers")
        more = next(row for row in form["controls"] if row["control_id"] == "true||moreCond")
        form = self._submit(target, form, control=more)
        self.assertTrue(form.get("success"), form)
        capability = next(row for row in form["fields"] if row["name"].startswith("tCapab"))
        form = self._submit(target, form, fields={capability["name"]: "Periodic Schedule"})
        self.assertTrue(form.get("success"), form)
        periodic = next(row for row in form["controls"] if row.get("page") == "periodic")
        form = self._submit(target, form, control=periodic)
        self.assertTrue(form.get("success"), form)
        frequency = next(row for row in form["fields"] if row["name"].startswith("whichPeriod"))
        self.assertIn("Cron String", {row["value"] for row in frequency["options"]})
        form = self._submit(target, form, fields={frequency["name"]: "Cron String"})
        self.assertTrue(form.get("success"), form)
        cron = next(row for row in form["fields"] if row["name"].startswith("cronStr"))
        form = self._submit(target, form, fields={cron["name"]: "0 0/5 * * * ?"})
        self.assertTrue(form.get("success"), form)
        previous = next(row for row in form["controls"] if row["name"] == "_action_previous")
        form = self._submit(target, form, control=previous)
        self.assertTrue(form.get("success"), form)
        finalized = self.update(target, [log_action("periodic deletion baseline", id=before["actions"][0]["id"])])
        self.assertTrue(finalized.get("success"), finalized)
        current = self.doc(target)
        periodic_ids = set(current["triggers"]) - set(before["triggers"])
        self.assertEqual(len(periodic_ids), 1, current)
        self.assertTrue(self.status(target).get("scheduledJobs"))
        self.assertIn(cron["name"], self.settings(target))
        deleted = self.update(target, [{"part": "delete", "value": {"part": "trigger", "id": periodic_ids.pop()}}])
        self.assertTrue(deleted.get("success"), deleted)
        after = self.healthy(target)
        self.assertEqual(after["triggers"], before["triggers"])
        self.assertEqual(after["actions"], before["actions"])
        self.assertNotIn(cron["name"], self.settings(target))
        self.assertFalse(self.status(target).get("scheduledJobs"))
        self.assertTrue(self.status(target).get("eventSubscriptions"))

    def _health(self, app_id, **extra):
        return self.call("hub_get_rule_health", appId=int(app_id), **extra)

    def _refused(self, result):
        self.assertIs(result.get("success"), False, result)
        self.assertTrue(result.get("error") or any(row.get("result", {}).get("error") for row in result.get("results", [])), result)

    def _guide(self):
        return self.call("hub_get_tool_guide", section="set_rule_reference")

    def test_set_rule_guide_param(self):
        guide = str(self._guide())
        for name in ("hub_set_rule", "condition", "trigger", "action", "fields"):
            self.assertIn(name, guide)
        self.assertIn("hub_set_rule", str(self.call(
            "hub_get_tool_guide", section="set_rule_reference")))

    def test_set_rule_self_gateway_meta(self):
        app_id = self.create([log_action("meta baseline")])
        before = self.snapshot(app_id)
        self.assertIn("hub_set_rule", str(self._guide()))
        definitions = self.client.request(self.client.mcp_url, "POST", {
            "jsonrpc": "2.0", "id": 61001, "method": "tools/list", "params": {}})
        def tool_names(node):
            if isinstance(node, str):
                return {node} if node.startswith("hub_") and " " not in node else set()
            if isinstance(node, dict):
                names = {node["name"]} if isinstance(node.get("name"), str) else set()
                for value in node.values():
                    names.update(tool_names(value))
                return names
            if isinstance(node, list):
                return set().union(*(tool_names(value) for value in node))
            return set()

        names = tool_names(definitions)
        self.assertIn("hub_set_rule", names)
        self.assertTrue({"hub_get_rule_document", "hub_create_rule_document",
                         "hub_update_rule_document", "hub_call_app_form"}.isdisjoint(names))
        self.assertEqual(before, self.snapshot(app_id))
        probe = self.call("hub_set_rule", operation="update", rule_id=int(app_id), confirm=False, args={"changes": [log_action("must not land")]})
        self.assertTrue(probe.get("confirm_required"), probe)
        self.assertEqual(before, self.snapshot(app_id))

    def test_set_rule_self_gateway_envelope_edit(self):
        app_id = self.create([log_action("first")])
        before = self.snapshot(app_id)
        probe = self.call("hub_set_rule", operation="update", rule_id=int(app_id), confirm=False, args={"changes": [log_action("unconfirmed")]})
        self.assertTrue(probe.get("confirm_required"), probe)
        self.assertEqual(before, self.snapshot(app_id))
        for message in ("via-envelope", "wrapped-list"):
            result = self.update(app_id, [log_action(message)])
            self.assertTrue(result.get("success"), result)
            self.healthy(app_id)
        values = self._values(app_id)
        for message in ("first", "via-envelope", "wrapped-list"):
            self.assertIn(message, list(values.values()))

    def test_set_rule_self_gateway_envelope_create(self):
        app_id = self.create([log_action("envelope-create")])
        self.healthy(app_id)
        self.assertIn("envelope-create", list(self._values(app_id).values()))
        self.assertEqual(str(app_id), str(self.doc(app_id)["rule_id"]))

    def test_set_rule_native_lifecycle(self):
        clock = change("trigger", capability="Certain Time (and optional date)", fields=[
            field("time#", "enum", "A specific time"),
            field("atTime#", "time", "2026-01-01T17:00:00.000-0500")])
        app_id = self.create([clock, log_action("native lifecycle")], name="Lifecycle (Paused)")
        active = self._listed(app_id)
        self.assertEqual("active", active.get("status"), active)
        self.assertIs(active.get("paused"), False, active)
        self.assertIs(active.get("disabled"), False, active)
        expected_label = active.get("name") or active.get("label")
        self.assertTrue(expected_label.endswith(" (Paused)"), active)
        for paused, label in ((True, "paused"), (False, "active")):
            result = self.call("hub_set_rule_paused", ruleId=app_id, paused=paused)
            self.assertIsNot(result.get("success"), False, result)
            self.wait_for(lambda label=label: self._listed(app_id).get("status") == label)
            health = self._health(app_id)
            self.assertIs(health.get("paused"), paused, health)
            self.assertEqual(expected_label, health.get("label"), health)
        for action, stopped in (("stop", True), ("start", False)):
            result = self.call("hub_call_rule", ruleId=app_id, action=action)
            self.assertIsNot(result.get("success"), False, result)
            self.wait_for(lambda stopped=stopped: self._health(app_id).get("stopped") is stopped)
            health = self._health(app_id)
            self.assertIs(health.get("stopped"), stopped, health)
            for key in ("eventSubscriptionCount", "scheduledJobCount"):
                self.assertIsInstance(health.get(key), int, health)
                self.assertGreaterEqual(health[key], 0, health)
            if stopped:
                self.assertEqual(0, health["eventSubscriptionCount"], health)
                edited = self.update(app_id, [log_action("edit while stopped")])
                self.assertTrue(edited.get("success"), edited)
                self.assertIs(self._health(app_id).get("stopped"), True)
            else:
                self.assertGreaterEqual(health["scheduledJobCount"], 1, health)
        self.call("hub_set_app_disabled", appId=app_id, disabled=True)
        self.wait_for(lambda: self._listed(app_id).get("disabled") is True)
        self.assertEqual("disabled", self._listed(app_id).get("status"))
        try:
            self._refused(self.call("hub_set_rule", operation="update", rule_id=int(app_id), confirm=True, args={"changes": [log_action("disabled must not land")]}))
            self._refused(self.call("hub_set_rule", operation="form", rule_id=int(app_id), confirm=True, args={"steps": [{"kind": "open"}]}))
        finally:
            self.call("hub_set_app_disabled", appId=app_id, disabled=False)
        self.wait_for(lambda: self._listed(app_id).get("status") == "active")
        self.assertTrue(self.update(app_id, [log_action("second action")]).get("success"))
        trigger_id = next(iter(self.doc(app_id)["triggers"]))
        rejected = self.update(app_id, [change("trigger", id=trigger_id,
            capability="Certain Time (and optional date)", fields=[field("tstate#", "enum", "on")])])
        self._refused(rejected)
        page = self._form(app_id, "selectTriggers")
        self.assertFalse(any("hasAll" in row["control_id"] for row in page["controls"]), page)
        self.assertFalse(any(row["name"] in ("tCapab" + str(trigger_id), "tstate" + str(trigger_id)) for row in page["fields"]), page)
        old_triggers = self.doc(app_id)["triggers"]
        added = self.update(app_id, [change("trigger", capability="Mode", fields=[
            field("modesX#", "enum", [str(self.mode_id)])])])
        self.assertTrue(added.get("success"), added)
        mode_id = next(key for key in self.doc(app_id)["triggers"] if key not in old_triggers)
        original = self._values(app_id)
        refused_mode = self.update(app_id, [change("trigger", id=mode_id, capability="Mode", fields=[
            field("tstate#", "enum", "not-a-mode-state")])])
        self._refused(refused_mode)
        mode_page = self._form(app_id, "selectTriggers")
        self.assertFalse(any("hasAll" in row["control_id"] for row in mode_page["controls"]), mode_page)
        self.assertFalse(any(row["name"] == "tCapab" + str(mode_id) for row in mode_page["fields"]), mode_page)
        self.assertEqual(original, self._values(app_id))
        removed_mode = self.update(app_id, [{"part": "delete", "value": {"part": "trigger", "id": int(mode_id)}}])
        self.assertTrue(removed_mode.get("success"), removed_mode)
        self.assertEqual(old_triggers, self.doc(app_id)["triggers"])
        self.healthy(app_id)
        deleted = self.call("hub_delete_native_app", appId=app_id, confirm=True)
        self.assertTrue(deleted.get("success"), deleted)
        listed = self.call("hub_list_rules")
        self.assertNotIn(str(app_id), [str(row["id"]) for row in listed.get("rules", [])])

    def test_set_rule_failloud_wrong_trigger_shape(self):
        app_id = self.create([log_action("fail-loud baseline")])
        before = self.snapshot(app_id)
        wrong = change("trigger", capability="Periodic Schedule", minutes=1, fields=[])
        rejected = self.update(app_id, [wrong])
        self._refused(rejected)
        self.assertEqual(before, self.snapshot(app_id))
        for value in (change("trigger", capability="Certain Time (and optional date)",
                             fields=[field("time#", "enum", "17:30")]),
                      change("action", type="messageActs", subtype="notAnAction", fields=[])):
            result = self.update(app_id, [value])
            self._refused(result)
            self.assertEqual(before, self.snapshot(app_id))

        # Native typed fields keep semantic malformed-value checks independent
        # of the retired convenience argument vocabulary.
        switch = int(self.fixtures["switch"])
        negatives = [
            ("wrong garage device", change("action", type="lockActs", subtype="getOCGarage", fields=[field("garageOpenClose.#", "capability.garageDoorControl", [switch])])),
            ("wrong valve device", change("action", type="lockActs", subtype="getOCValve", fields=[field("valveOpenClose.#", "capability.valve", [switch])])),
            ("wrong tone device", change("action", type="soundActs", subtype="getTone", fields=[field("toner.#", "capability.tone", [switch])])),
            ("blind tilt requires number", change("action", type="sceneActs", subtype="getBlindTilt", fields=[field("tiltLevel.#", "number", "forty")])),
            ("blind tilt missing", change("action", type="sceneActs", subtype="getBlindTilt", fields=[])),
            ("music command invalid", change("action", type="soundActs", subtype="getSetMusicPlayer", fields=[field("playerCommand.#", "enum", "noSuchMusicCommand")])),
            ("music volume requires number", change("action", type="soundActs", subtype="getSetMusicPlayer", fields=[field("playerLevel.#", "number", "loud")])),
            ("invalid return boolean", change("action", type="modeActs", subtype="getReturnValue", fields=[field("valType.#", "enum", "Boolean"), field("valBool.#", "enum", "maybe")])),
            ("zero integer return produces no native row", change("action", type="modeActs", subtype="getReturnValue", fields=[field("valType.#", "enum", "Integer"), field("valInt.#", "number", 0)])),
            ("invalid custom command capability", change("action", type="modeActs", subtype="getDefinedAction", fields=[field("myCapab.#", "enum", "Initialize")])),
            ("nonexistent custom command", change("action", type="modeActs", subtype="getDefinedAction", fields=[field("myCapab.#", "enum", "Switch"), field("devices.#", "capability.switch", [switch]), field("cCmd.#", "enum", "noSuchCommandE2E")])),
            ("last event command without trigger device", change("action", type="modeActs", subtype="getDefinedAction", fields=[field("useLastDev.#", "bool", True), field("cCmd.#", "enum", "off")])),
            ("missing append file", change("action", type="modeActs", subtype="getAppendLocalFile", fields=[field("localFile.#", "enum", self.prefix + "_no_such_file.txt"), field("fileContents.#", "textarea", "x")])),
            ("missing delete file", change("action", type="modeActs", subtype="getDeleteLocalFile", fields=[field("deleteFile.#", "enum", self.prefix + "_no_such_file.txt")])),
            ("invalid HSM command", change("action", type="lockActs", subtype="getSetHSM", fields=[field("alarm.#", "enum", "armEverything")])),
            ("invalid return type", change("action", type="modeActs", subtype="getReturnValue", fields=[field("valType.#", "enum", "UnknownType")])),
            ("missing referenced condition", log_action("conditional must not land", condition=999999999)),
            ("block cannot carry simple condition", change("action", type="condActs", subtype="getIfThen", fields=[], expression=["999999999"], condition=999999999)),
            ("missing expression condition", change("required", tokens=["999999999"])),
            ("invalid expression token", change("required", tokens=["AND"])),
            ("invalid numeric trigger", change("trigger", capability="Temperature", fields=[field("tDev#", "capability.temperatureMeasurement", [int(self.fixtures["temperature"])]), field("ReltDev#", "enum", "increased")])),
            ("invalid switch state", self._trigger("purple")),
            ("device ID must be positive", change("trigger", capability="Switch", fields=[field("tDev#", "capability.switch", [-1]), field("tstate#", "enum", "on")])),
            ("single native enum cannot be object", change("trigger", capability="Switch", fields=[field("tstate#", "enum", {"state": "on"})])),
            ("unsupported comparator condition", change("condition", capability="Switch", fields=[field("rDev_#", "capability.switch", [switch]), field("RelrDev_#", "enum", "*changed*")])),
            ("last event condition unsupported", change("condition", capability="Last Event Device", fields=[])),
            ("lock codes condition unsupported", change("condition", capability="Lock codes", fields=[])),
        ]
        target = app_id
        original = self.settings(target)
        original_actions = self.doc(target)["actions"]
        function = self.create([log_action("function baseline")])
        form = self._form(function, "mainPage")
        enabled = self._submit(function, form, fields={"isFunction": True})
        self.assertTrue(enabled.get("success"), enabled)
        self.assertIn(self._values(function)["isFunction"], (True, "true"))
        function_settings = self.settings(function)
        function_actions = self.doc(function)["actions"]
        for label, requested in negatives:
            with self.subTest(case=label):
                is_return = requested["value"].get("subtype") == "getReturnValue"
                current = function if is_return else target
                refused = self.update(current, [requested])
                self._refused(refused)
                self.assertEqual(function_settings if is_return else original, self.settings(current), refused)
                self.assertEqual(function_actions if is_return else original_actions,
                                 self.doc(current)["actions"], refused)
                self.healthy(current)
                health = self._health(current)
                self.assertFalse(health.get("orphanedActionRows"), health)
                self.assertNotIn(self.prefix + "_no_such_file.txt", self.page_text(current))

        # First compile a valid literal action using a capability-matching
        # fixture, so a later refusal cannot pass merely on a bad device.
        variable = "parityAmount"
        for label, fixture, action_type, subtype, device_name, device_type, literal_name, selectors in (
            ("blind literal and variable", "blind", "sceneActs", "getBlindTilt", "blindTilt.#", "capability.windowBlind", "tiltLevel.#", []),
            ("music literal and variable", "player", "soundActs", "getSetMusicPlayer", "player.#", "capability.musicPlayer", "playerLevel.#", [field("playerCommand.#", "enum", "set volume")]),
        ):
            with self.subTest(case=label):
                self.assertIn(fixture, self.fixtures)
                target = self.create([change("variable", operation="create", name=variable, type="integer", value=40),
                                      log_action("exclusive baseline")])
                base_fields = [field(device_name, device_type, [int(self.fixtures[fixture])]), *selectors]
                valid = self.update(target, [change("action", type=action_type, subtype=subtype,
                                                   fields=[*base_fields, field(literal_name, "number", 30)])])
                self.assertTrue(valid.get("success"), valid)
                action = next(row for row in self.doc(target)["actions"] if row["value"].get("method") == subtype)
                literal_key = literal_name.replace("#", str(action["id"]))
                self.assertEqual(30.0, float(self._values(target)[literal_key]))
                original = self.snapshot(target)
                refused = self.update(target, [change("action", id=action["id"], type=action_type, subtype=subtype,
                    fields=[*base_fields, field("uVar.#", "bool", True), field("xVar.#", "enum", variable),
                            field(literal_name, "number", 30)])])
                self._refused(refused)
                self.assertEqual(original, self.snapshot(target))
                self.healthy(target)

        # Each remaining semantic negative starts from a successfully compiled
        # native row, so unsupported selectors or unrelated device types cannot
        # masquerade as proof of the intended restriction.
        with self.subTest(case="location event requires a selected name"):
            target = self.create([change("trigger", capability="Location Event", fields=[
                field("tstate#", "enum", "systemStart")]), log_action("location baseline")])
            slot = next(iter(self.doc(target)["triggers"]))
            self.assertEqual("systemStart", self._values(target)["tstate" + slot])
            before = self.snapshot(target)
            refused = self.update(target, [change("trigger", id=slot, capability="Location Event",
                                                  fields=[field("tstate#", "enum", "")])])
            self._refused(refused)
            self.assertEqual(before, self.snapshot(target), refused)

        with self.subTest(case="empty variable comparator has no stays duration"):
            name = self.create_hub_variable("empty_stays", "String", "nonempty")
            target = self.create([change("trigger", capability="Variable", fields=[
                field("xVar#", "enum", name), field("ReltDev#", "enum", "*is empty*")]),
                log_action("variable baseline")])
            slot = next(iter(self.doc(target)["triggers"]))
            self.assertEqual("*is empty*", self._values(target)["ReltDev" + slot])
            before = self.snapshot(target)
            refused = self.update(target, [change("trigger", id=slot, capability="Variable", fields=[
                field("stays#", "bool", True), field("SMins#", "number", 5)])])
            self._refused(refused)
            self.assertEqual(before, self.snapshot(target), refused)

        with self.subTest(case="periodic frequency refuses another frequency's time input"):
            target = self.create([log_action("periodic baseline")])
            form = self._form(target, "selectTriggers")
            more = next(row for row in form["controls"] if row["control_id"] == "true||moreCond")
            form = self._submit(target, form, control=more)
            self.assertTrue(form.get("success"), form)
            capability = next(row for row in form["fields"] if row["name"].startswith("tCapab"))
            form = self._submit(target, form, fields={capability["name"]: "Periodic Schedule"})
            self.assertTrue(form.get("success"), form)
            periodic = next(row for row in form["controls"] if row.get("kind") == "href" and row.get("page") == "periodic")
            form = self._submit(target, form, control=periodic)
            self.assertTrue(form.get("success"), form)
            frequency = next(row for row in form["fields"] if row["name"].startswith("whichPeriod"))
            offered = {row["value"] for row in frequency["options"]}
            self.assertTrue({"Daily", "Hourly"}.issubset(offered), form)
            form = self._submit(target, form, fields={frequency["name"]: "Daily"})
            self.assertTrue(form.get("success"), form)
            daily_time = next(row for row in form["fields"] if row["name"].startswith("startingDC") and row["type"] == "time")
            form = self._submit(target, form, fields={frequency["name"]: "Hourly"})
            self.assertTrue(form.get("success"), form)
            self.assertNotIn(daily_time["name"], {row["name"] for row in form["fields"]})
            before = self.snapshot(target)
            refused = self._submit(target, form, fields={daily_time["name"]: "2026-01-01T08:00:00.000-0500"})
            self._refused(refused)
            self.assertFalse(refused.get("partial"), refused)
            self.assertIn(daily_time["name"], refused["error"])
            self.assertEqual(before, self.snapshot(target), refused)

        referenced = self.create([log_action("referenced rule")])
        with self.subTest(case="rule paused condition has no changed comparator"):
            target = self.create([change("condition", capability="Rule paused", fields=[
                field("rDev_#", "enum", [str(referenced)])])])
            slot = next(iter(self.doc(target)["conditions"]))
            self.assertEqual([str(referenced)], list(map(str, self._values(target)["rDev_" + slot])))
            self.assertIn(self._values(referenced)["origLabel"], self.doc(target)["conditions"][slot])
            before = self.snapshot(target)
            refused = self.update(target, [change("condition", id=slot, capability="Rule paused",
                                                  fields=[field("RelrDev_#", "enum", "*changed*")])])
            self._refused(refused)
            self.assertEqual(before, self.snapshot(target), refused)
            self.assertNotIn("addTrigger", json.dumps(refused))

        with self.subTest(case="run actions requires actual rule IDs"):
            target = self.create([change("action", type="rulesActs", subtype="getRuleActions", fields=[
                field("runRuleType.#", "enum", "Rule Machine"),
                field("ruleAct.#", "enum", [str(referenced)])])])
            action = self.doc(target)["actions"][0]
            self.assertEqual([str(referenced)], list(map(str, self._values(target)["ruleAct." + action["id"]])))
            before = self.snapshot(target)
            for invalid in (["999999999"], ["*"], []):
                with self.subTest(targets=invalid):
                    refused = self.update(target, [change("action", id=action["id"], type="rulesActs",
                        subtype="getRuleActions", fields=[field("ruleAct.#", "enum", invalid)])])
                    self._refused(refused)
                    self.assertEqual(before, self.snapshot(target), refused)

        with self.subTest(case="required days condition cannot have no days"):
            target = self.create([change("condition", capability="Days of week", fields=[
                field("days#", "enum", ["Monday"])])])
            slot = next(iter(self.doc(target)["conditions"]))
            enabled = self.update(target, [change("required", tokens=[slot])])
            self.assertTrue(enabled.get("success"), enabled)
            self.assertEqual(["Monday"], self._values(target)["days" + slot])
            before = self.snapshot(target)
            refused = self.update(target, [change("condition", id=slot, capability="Days of week",
                                                  fields=[field("days#", "enum", [])])])
            self._refused(refused)
            self.assertEqual(before, self.snapshot(target), refused)
            self.healthy(target)

        with self.subTest(case="water condition has state choices without a comparator"):
            water_key = "water" if "water" in self.fixtures else "omni"
            self.fixture_metadata(water_key)
            target = self.create([change("condition", capability="Water sensor", fields=[
                field("rDev_#", "capability.waterSensor", [str(self.fixtures[water_key])]),
                field("state_#", "enum", "wet")])])
            slot = next(iter(self.doc(target)["conditions"]))
            enabled = self.update(target, [change("required", tokens=[slot])])
            self.assertTrue(enabled.get("success"), enabled)
            self.assertEqual("wet", self._values(target)["state_" + slot])
            before = self.snapshot(target)
            refused = self.update(target, [change("condition", id=slot, capability="Water sensor",
                                                  fields=[field("RelrDev_#", "enum", "*changed*")])])
            self._refused(refused)
            self.assertEqual(before, self.snapshot(target), refused)
            self.healthy(target)

        # The native inline-condition creator excludes Mode, but its defined
        # condition picker includes an existing valid Mode condition.
        with self.subTest(case="existing Mode condition is a legitimate simple action condition"):
            target = self.create([change("condition", capability="Mode", fields=[
                field("modes#", "enum", [str(self.mode_id)])])])
            slot = next(iter(self.doc(target)["conditions"]))
            attached = self.update(target, [log_action("mode conditional action", condition=slot)])
            self.assertTrue(attached.get("success"), attached)
            action = self.doc(target)["actions"][0]
            self.assertEqual(slot, str(action["value"].get("cond")))
            self.assertIn("mode conditional action", self.page_text(target))
            self.healthy(target)

        accepted_id = self.create([log_action("custom command baseline")])
        accepted = self.update(accepted_id, [change("action", type="modeActs", subtype="getDefinedAction", fields=[
            field("myCapab.#", "enum", "Switch"), field("devices.#", "capability.switch", [switch]),
            field("cCmd.#", "enum", "off")])])
        self.assertTrue(accepted.get("success"), accepted)
        self.assertFalse(accepted.get("partial"), accepted)
        self.assertIn("off() on", self.page_text(accepted_id))
        custom = next(row for row in self.doc(accepted_id)["actions"] if row["value"].get("method") == "getDefinedAction")
        before_edit = self.snapshot(accepted_id)
        invalid_edit = self.update(accepted_id, [change("action", id=custom["id"], type="modeActs", subtype="getDefinedAction", fields=[
            field("myCapab.#", "enum", "Initialize"), field("devices.#", "capability.switch", [switch]),
            field("cCmd.#", "enum", "off")])])
        self._refused(invalid_edit)
        self.assertEqual(before_edit, self.snapshot(accepted_id))
        self.healthy(accepted_id)

    def test_clone_stage_disabled(self):
        source = self.create([log_action("stage source")])
        name = self.prefix + "_StagedClone"
        clone = self.call("hub_clone_native_app", appId=source, newName=name,
                          stageDisabled=True, confirm=True)
        clone_id = clone.get("newAppId")
        if clone_id:
            self.adopt(clone_id, name)
        self.assertTrue(clone.get("success"), clone)
        self.assertTrue(clone_id, clone)
        self.assertIn(str(clone_id), [str(x) for x in clone.get("stagedDisabled", [])])
        self.wait_for(lambda: self._listed(clone_id).get("disabled") is True)
        self.assertEqual("disabled", self._listed(clone_id).get("status"))
        self.assertIs(self._listed(source).get("disabled"), False)
        exported = self.call("hub_export_native_app", appId=source)
        self.assertTrue(exported.get("success"), exported)
        self.assertTrue(exported.get("jsonContent"), exported)
        before = {str(r["id"]) for r in self.call("hub_list_rules").get("rules", [])}
        name = self.prefix + "_StagedImport"
        imported = self.call("hub_import_native_app", parentHintAppId=source,
                             jsonContent=exported["jsonContent"], newName=name,
                             stageDisabled=True, confirm=True)
        imported_id = imported.get("newAppId")
        if imported_id:
            self.adopt(imported_id, name)
        self.assertTrue(imported.get("success"), imported)
        self.assertIn(str(imported_id), [str(x) for x in imported.get("stagedDisabled", [])])
        self.wait_for(lambda: self._listed(imported_id).get("disabled") is True)
        after = {str(r["id"]) for r in self.call("hub_list_rules").get("rules", [])}
        self.assertEqual({str(imported_id)}, after - before)
        self.assertIs(self._listed(source).get("disabled"), False)

    def test_call_rule_multi_id_aggregates_per_rule(self):
        ids = [self.create([log_action("aggregate A")]), self.create([log_action("aggregate B")])]
        result = self.call("hub_call_rule", ruleId=ids, action="stop")
        self.assertNotEqual("in_progress", result.get("status"), result)
        if result.get("status") != "continuation_limit":
            self.assertNotIn("remainingRuleIds", result)
        rows = result.get("results")
        self.assertIsInstance(rows, list, result)
        self.assertTrue(all(isinstance(row, dict) and row.get("ruleId") is not None for row in rows), result)
        self.assertEqual(sorted(map(str, ids)), sorted(str(row["ruleId"]) for row in rows))
        self.assertEqual(len(rows), len({str(row["ruleId"]) for row in rows}))
        self.assertEqual(sorted(map(str, ids)), sorted(map(str, result.get("ruleIds", []))))
        failed = [row for row in rows if row.get("success") is not True]
        self.assertIs(result.get("success"), not failed, result)
        if failed:
            self.assertIs(result.get("partial"), len(failed) < len(rows), result)
            self.assertEqual(sorted(str(row["ruleId"]) for row in failed),
                             sorted(map(str, result.get("failedRuleIds", []))))
        else:
            self.assertFalse(result.get("partial"), result)
            self.assertFalse(result.get("failedRuleIds"), result)
        for app_id in ids:
            self.wait_for(lambda app_id=app_id: self._health(app_id).get("stopped") is True)

    def test_set_rule_walkstep_introspect(self):
        app_id = self.create([log_action("introspect")])
        page = self._form(app_id, "selectTriggers")
        self.assertEqual("selectTriggers", page["page"]["name"])
        self.assertEqual("mainPage", self._form(app_id, "mainPage")["page"]["name"])
        page = self._form(app_id, "selectTriggers")
        more = next(c for c in page["controls"] if c["name"] == "true" and c.get("kind") == "button" and c["control_id"].endswith("|moreCond"))
        page = self._submit(app_id, page, control=more)
        self.assertTrue(page.get("success"), page)
        selector = next(f for f in page["fields"] if f["name"].startswith("tCapab"))
        written = self._submit(app_id, page, fields={selector["name"]: "Switch"})
        self.assertTrue(written.get("success"), written)
        self.assertEqual("Switch", self._values(app_id)[selector["name"]])

    def test_rule_health_prefers_rulebuilderjson(self):
        app_id = self.create([log_action("health source")], name="RuleHealthSrc")
        health = self._health(app_id)
        self.assertIs(health.get("ok"), True, health)
        self.assertIs(health.get("broken"), False, health)
        self.assertIs(health.get("paused"), False, health)
        self.assertIn("ruleBuilderJson", str(health.get("source")))
        listed = self.call("hub_list_rules", labelFilter=(self.prefix + "_RuleHealthSrc").lower())
        rows = listed.get("rules", [])
        self.assertTrue(rows, listed)
        self.assertTrue(all((self.prefix + "_RuleHealthSrc").lower() in str(r.get("label", "")).lower() for r in rows))
        row = next(r for r in rows if str(r["id"]) == str(app_id))
        self.assertEqual("Rule-5.1", row.get("type"))
        self.assertEqual("5.x", row.get("rmVersion"))
        html = self._health(app_id, source="configPage")
        self.assertEqual("configPage", html.get("source"), html)
        self.assertIsNone(html.get("broken"), html)

    def test_rule_health_broken_true_on_dangling_trigger(self):
        device_id, dni = self.disposable_switch("RHBrokenDev")
        trigger = change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [int(device_id)]), field("tstate#", "enum", "on")])
        app_id = self.create([trigger, log_action("dangling trigger")])
        self.assertIs(self._health(app_id).get("broken"), False)
        self.delete_device(dni)
        self._form(app_id, "mainPage")
        health = self._health(app_id)
        self.assertIs(health.get("broken"), True, health)
        self.assertEqual("rm", health.get("ruleFormat"), health)
        self.assertIs(health.get("ok"), False, health)
        self.assertIn("ruleBuilderJson", str(health.get("source")))

    def test_rule_health_ui_shaped_else_endif_rows(self):
        app_id = self.create([change("condition", capability="Switch", fields=[
            field("rDev_#", "capability.switch", [int(self.fixtures["switch"])]),
            field("state_#", "enum", "on")])])
        condition_id = next(iter(self.doc(app_id)["conditions"]))
        result = self.update(app_id, [change("action", type="condActs", subtype="getIfThen",
            fields=[], expression=[str(condition_id)]), log_action("then-branch"),
            change("terminator", subtype="else"), log_action("else-branch"),
            change("terminator", subtype="endIf")])
        self.assertTrue(result.get("success"), result)
        settings = self._values(app_id)
        branches = {key.split(".", 1)[1] for key, value in settings.items()
                    if key.startswith("actSubType.") and value in ("getElse", "getEndIf")}
        self.assertEqual(2, len(branches), settings)
        exported = self.call("hub_export_native_app", sourceAppId=app_id)
        self.assertTrue(exported.get("success"), exported)
        document = json.loads(exported["jsonContent"])
        doomed = {"actType." + row for row in branches}

        self.assertIsInstance(document, dict)
        app_data = document.get("appData", {}).get(str(app_id))
        self.assertIsInstance(app_data, dict, document.keys())
        exported_settings = app_data.get("appSettings")
        self.assertIsInstance(exported_settings, list, app_data.keys())
        names = [row.get("name") for row in exported_settings]
        for branch in branches:
            self.assertIn("actSubType." + branch, names)
        self.assertTrue(any(str(name).startswith("actType.") for name in names), names)
        # Native branch controls can already omit actType; unlike a synthetic
        # writer, native creation need not add an actType just to remove it.
        expected_drop = doomed.intersection(settings)
        dropped = [row for row in exported_settings if row.get("name") in doomed]
        self.assertEqual(expected_drop, {row["name"] for row in dropped})
        app_data["appSettings"] = [row for row in exported_settings if row.get("name") not in doomed]
        self.assertNotIn("actSubType.99", names)
        app_data["appSettings"].append({"name": "actSubType.99", "type": "enum", "multiple": False,
                                        "deviceList": {}, "value": "getIfThen"})
        name = self.prefix + "_UIShapeImport"
        imported = self.call("hub_import_native_app", parentHintAppId=app_id,
                             jsonContent=json.dumps(document), newName=name, confirm=True)
        imported_id = imported.get("newAppId")
        if imported_id:
            self.adopt(imported_id, name)
        self.assertTrue(imported.get("success"), imported)
        values = self._values(imported_id)
        rows = {key.split(".", 1)[1] for key, value in values.items()
                if key.startswith("actSubType.") and value in ("getElse", "getEndIf")}
        self.assertEqual(len(branches), len(rows), values)
        self.assertFalse(any("actType." + row in values for row in rows), values)
        self.assertTrue(any(key.startswith("actType.") for key in values), values)
        health = self._health(imported_id)
        self.assertFalse(health.get("checkErrors"), health)
        self.assertIn("configPage", str(health.get("source")))
        self.assertEqual([], health.get("structuralIssues"), health)
        self.assertNotIn("never closed", str(health).lower())
        self.assertIsInstance(health.get("orphanedActionRows"), list, health)
        self.assertTrue(any("action 99" in str(row) and "getIfThen" in str(row)
                            for row in health["orphanedActionRows"]), health)
        self.assertIs(health.get("ok"), True, health)
        endif_id = next(row for row in rows if values["actSubType." + row] == "getEndIf")
        before = self.snapshot(imported_id)
        refused = self.update(imported_id, [{"part": "delete", "value": {"part": "action", "id": int(endif_id)}}])
        self._refused(refused)
        self.assertEqual(before, self.snapshot(imported_id))
        self.assertEqual([], self._health(imported_id).get("structuralIssues"))

    def test_set_rule_raw_settings_and_validation(self):
        app_id = self.create([log_action("raw settings")])
        page = self._form(app_id, "mainPage")
        result = self._submit(app_id, page, fields={"comments": "BAT_E2E raw settings", "logging": ["Triggers", "Actions"]})
        self.assertTrue(result.get("success"), result)
        self.assertEqual("BAT_E2E raw settings", self._values(app_id).get("comments"))
        self.assertEqual(["Actions", "Triggers"], sorted(self._values(app_id).get("logging", [])))
        page = self._form(app_id, "selectTriggers")
        more = next(c for c in page["controls"] if c["name"] == "true" and c.get("kind") == "button" and c["control_id"].endswith("|moreCond"))
        page = self._submit(app_id, page, control=more)
        selector = next(f["name"] for f in page["fields"] if f["name"].startswith("tCapab"))
        written = self._submit(app_id, page, fields={selector: "Switch"})
        self.assertTrue(written.get("success"), written)
        self.assertFalse(written.get("partial"), written)
        self.assertEqual("Switch", self._values(app_id)[selector])
        self.assertTrue(self._form(app_id, "selectTriggers").get("success"))

    def test_set_rule_create_refusal_and_fail_closed_stop(self):
        # Shape errors must precede child creation. Native option failures may leave
        # preceding changes committed, but later changes must never run.
        for suffix, bad in (("Trigger", change("trigger", capability="Temperature", value="increased", fields=[])),
                            ("Action", change("action", type="dimmerActs", subtype="getSetLevel", missingLevel=True, fields=[]))):
            name = self.prefix + "_CreateRefused" + suffix
            before = {str(row["id"]) for row in self.call("hub_list_rules").get("rules", [])}
            result = self.call("hub_set_rule", operation="create", confirm=True, args={"parent_id": int(self.parent_id), "name": name, "changes": [log_action("first"), bad]})
            if result.get("rule_id"):
                self.adopt(result["rule_id"], name)
            self._refused(result)
            after = {str(row["id"]) for row in self.call("hub_list_rules").get("rules", [])}
            self.assertEqual(before, after, result)
        name = self.prefix + "_CreateStop"
        result = self.call("hub_set_rule", operation="create", confirm=True, args={"parent_id": int(self.parent_id), "name": name, "changes": [self._trigger(), self._trigger("purple"),
                     change("condition", capability="Switch", fields=[
                         field("rDev_#", "capability.switch", [int(self.fixtures["switch"])]),
                         field("state_#", "enum", "on")]), change("required", tokens=["3"]),
                     log_action("skipped action")]})
        app_id = result.get("rule_id")
        if app_id:
            self.adopt(app_id, name)
        self.assertTrue(app_id, result)
        self._refused(result)
        self.assertTrue(result.get("partial"), result)
        values = self._values(app_id)
        self.assertTrue(any(key.startswith("tstate") and value == "on" for key, value in values.items()), values)
        self.assertFalse(any(key.startswith("rCapab_") for key in values), values)
        self.assertNotIn("skipped action", list(values.values()))
        self.assertNotEqual("true", str(values.get("useST")).lower())
        self.assertFalse(self.state(app_id).get("hasPredicate"))
        completed = result.get("results", [])
        self.assertTrue(completed, result)
        self.assertTrue(completed[0].get("result", {}).get("success"), result)
        self.assertFalse(completed[-1].get("result", {}).get("success"), result)

    def test_set_rule_discover_meta(self):
        # The live native selector is authoritative; synthetic per-capability
        # argument schemas from the retired writer are intentionally absent.
        app_id = self.create([log_action("discover")])
        page = self._form(app_id, "selectTriggers")
        more = next(c for c in page["controls"] if c["name"] == "true" and c.get("kind") == "button" and c["control_id"].endswith("|moreCond"))
        page = self._submit(app_id, page, control=more)
        selector = next(f for f in page["fields"] if f["name"].startswith("tCapab"))
        options = {str(option["value"]) for option in selector.get("options", [])}
        self.assertIn("Temperature", options)
        self.assertIn("Switch", options)
        page = self._submit(app_id, page, fields={selector["name"]: "Temperature"})
        self.assertTrue(page.get("success"), page)
        names = {row["name"] for row in page["fields"]}
        self.assertTrue(any(name.startswith("tDev") for name in names), page)
        device = next(row for row in page["fields"] if row["name"].startswith("tDev"))
        value = str(self.fixtures["temperature"])
        page = self._submit(app_id, page, fields={device["name"]: [value] if device["multiple"] else value})
        self.assertTrue(page.get("success"), page)
        names = {row["name"] for row in page["fields"]}
        self.assertTrue(any(name.startswith("Rel") for name in names), page)
        comparators = [f for f in page["fields"] if f["name"].startswith("Rel")]
        self.assertTrue(any("*changed*" in {str(o["value"]) for o in f.get("options", [])}
                            for f in comparators), page)
        self.assertIn("condition", str(self._guide()))

    def test_mrtr_rule_edit_uses_standard_continuation(self):
        import time

        app_id = self.create([log_action("MRTR base")])
        messages = ["MRTR regular E2E proof " + str(i) for i in range(32)]
        # Real condition compilation exercises dependency renders as well as
        # persistence; fast log-only batches need not cross a request budget.
        conditions = [change("condition", capability="Temperature", fields=[
            field("rDev_#", "capability.temperatureMeasurement", [str(self.fixtures["temperature"])]),
            field("RelrDev_#", "enum", ">"), field("state_#", "decimal", 60 + index)])
            for index in range(68)]
        changes = [*conditions, *[log_action(message) for message in messages]]
        arguments = {"operation": "update", "rule_id": int(app_id), "args": {"changes": changes}, "confirm": True}
        headers = {"MCP-Protocol-Version": "2026-07-28", "Mcp-Method": "tools/call",
                   "Mcp-Name": "hub_set_rule"}
        params = {"name": "hub_set_rule", "arguments": arguments}
        legs = []
        request_state = None
        rounds = 0
        started = time.monotonic()
        for index in range(101):
            tick = time.monotonic()
            envelope = self.client.request(self.client.mcp_url, "POST", {
                "jsonrpc": "2.0", "id": 62000 + index, "method": "tools/call", "params": params}, headers)
            legs.append(time.monotonic() - tick)
            self.assertNotIn("error", envelope, envelope)
            response = envelope["result"]
            if response.get("resultType") == "input_required":
                request_state = response.get("requestState")
                self.assertIsInstance(request_state, str, response)
                self.assertTrue(request_state, response)
                params["requestState"] = request_state
                rounds += 1
                time.sleep(0.1)
                continue
            break
        else:
            self.fail("MRTR request exceeded 100 continuation rounds")
        elapsed = time.monotonic() - started
        self.assertGreater(rounds, 0, {"rounds": rounds, "legs": legs, "elapsed": elapsed})
        self.assertTrue(request_state)
        self.assertEqual("complete", response.get("resultType"), response)
        self.assertFalse(response.get("isError"), response)
        result = response.get("structuredContent")
        if result is None:
            result = json.loads(next(row["text"] for row in response["content"] if row.get("type") == "text"))
        self.assertTrue(result.get("success"), result)
        self.assertFalse(result.get("partial"), result)
        rows = result.get("results", [])
        self.assertEqual(len(changes), len(rows), result)
        self.assertTrue(all(row.get("result", {}).get("success") for row in rows), result)
        self.assertLess(max(legs), 10.0, {"legs": legs, "elapsed": elapsed})
        for index, replacement in enumerate((None, False, 0)):
            altered = json.loads(json.dumps(arguments))
            altered["args"]["changes"][-1]["value"]["fields"][0]["value"] = replacement
            changed = self.client.request(self.client.mcp_url, "POST", {
                "jsonrpc": "2.0", "id": 63000 + index, "method": "tools/call",
                "params": {"name": "hub_set_rule", "arguments": altered,
                           "requestState": request_state}}, headers)
            self.assertEqual(-32602, changed.get("error", {}).get("code"), changed)
            self.assertIn("original arguments", changed["error"].get("message", ""))
        pong = self.client.request(self.client.mcp_url, "POST", {
            "jsonrpc": "2.0", "id": 63010, "method": "ping"})
        self.assertNotIn("error", pong, pong)
        replay = self.client.request(self.client.mcp_url, "POST", {
            "jsonrpc": "2.0", "id": 63011, "method": "tools/call", "params": params}, headers)
        replay_result = replay.get("result", {})
        self.assertEqual("complete", replay_result.get("resultType"), replay)
        self.assertFalse(replay_result.get("isError"), replay)
        replay_value = replay_result.get("structuredContent")
        if replay_value is None:
            replay_value = json.loads(next(row["text"] for row in replay_result["content"] if row.get("type") == "text"))
        self.assertEqual(result, replay_value)
        recent = self.call("hub_get_info").get("recentWrites", [])
        mine = [row for row in recent if row.get("tool") == "hub_set_rule"
                and str(row.get("rule_id")) == str(app_id)]
        self.assertTrue(mine, recent)
        self.assertEqual("finished", mine[0].get("status"), mine)
        self.assertIs(mine[0].get("success"), True, mine)
        persisted = [value for key, value in self._values(app_id).items() if key.startswith("logmsg.")]
        self.assertEqual(sorted(["MRTR base", *messages]), sorted(persisted))
        self.assertEqual(68, len(self.doc(app_id)["conditions"]))
        persisted_thresholds = sorted(float(value) for key, value in self._values(app_id).items()
                                      if key.startswith("state_"))
        self.assertEqual([float(60 + index) for index in range(68)], persisted_thresholds)
        self.healthy(app_id)


if __name__ == "__main__":
    unittest.main(verbosity=2)
