"""Historical RM expression and variable contracts through current public tools.

Run only with the explicit virtual-fixture authorization enforced by ParityCase.
The backup/restore segment of replace_required_expression is intentionally excluded.
Failures remain failures: this module has no expected-failure or capability skips.
"""

import html
import unittest

from native_rm_parity_support import ParityCase, change, field, log_action


class NativeRmParityExpressions(ParityCase):
    def _ok(self, rule_id, changes):
        result = self.update(rule_id, changes)
        self.assertTrue(result.get("success"), result)
        self.assertFalse(result.get("partial"), result)
        self.assertEqual(str(result["rule_id"]), str(rule_id))
        return result

    def _refuse_unchanged(self, rule_id, changes):
        before = self.snapshot(rule_id)
        result = self.update(rule_id, changes)
        self.assertIs(result.get("success"), False, result)
        self.assertEqual(self.snapshot(rule_id), before, result)
        return result

    def _value(self, rule_id, name):
        return self.settings(rule_id).get(name, {}).get("value")

    def _devices(self, rule_id, name, *keys):
        row = self.settings(rule_id)[name]
        self.assertEqual(set(map(str, row.get("device_ids") or row.get("deviceList") or [])),
                         {str(self.fixtures[key]) for key in keys}, row)

    def _row(self, rule_id, part, capability, fields):
        section = "triggers" if part == "trigger" else "conditions"
        before = set(map(str, self.doc(rule_id)[section]))
        self._ok(rule_id, [change(part, capability=capability, fields=fields)])
        added = set(map(str, self.doc(rule_id)[section])) - before
        self.assertEqual(len(added), 1, added)
        return added.pop()

    def _switch_fields(self, key="switch", state="on", trigger=False, custom=False):
        prefix = "tDev" if trigger else "rDev_"
        fields = [field(prefix + "#", "capability.*" if custom else "capability.switch",
                        str(self.fixtures[key]) if custom else [str(self.fixtures[key])])]
        if custom:
            fields.append(field("tCustomAttr#" if trigger else "rCustomAttr_#", "enum", "switch"))
        fields.append(field("tstate#" if trigger else "state_#", "enum", state))
        return fields

    def _switch_condition(self, rule_id, state="on", custom=False):
        return self._row(rule_id, "condition", "Custom Attribute" if custom else "Switch",
                         self._switch_fields(state=state, custom=custom))

    def _required(self, rule_id, tokens):
        self._ok(rule_id, [change("required", tokens=list(tokens))])
        self.assertEqual(self.doc(rule_id)["expressions"]["0"], list(tokens))
        native = self.state(rule_id)
        self.assertTrue(native["hasPredicate"], native)
        self.assertEqual(native["eval"]["0"], list(tokens))

    def _action(self, rule_id, action):
        before = {str(row["id"]) for row in self.doc(rule_id)["actions"]}
        self._ok(rule_id, [action])
        rows = [row for row in self.doc(rule_id)["actions"] if str(row["id"]) not in before]
        self.assertEqual(len(rows), 1, rows)
        return str(rows[0]["id"])

    def _switch_action(self, on=True, **extra):
        return change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures["switch"])]),
            field("onOff.#", "bool", on),
        ], **extra)

    @staticmethod
    def _variable_action(name, operation, fields):
        return change("action", type="modeActs", subtype="getSetVariable", fields=[
            field("xVarV.#", "enum", name), field("numOp.#", "enum", operation), *fields,
        ])

    @staticmethod
    def _variable_fields(name, comparator=None, value=None, value_type="text", rhs=None):
        rows = [field("xVar_#", "enum", name)]
        if comparator is not None:
            rows.append(field("RelrDev_#", "enum", comparator))
        if rhs is not None:
            rows.extend([field("isVar_#", "bool", True), field("xVarR_#", "enum", rhs)])
        if value is not None:
            rows.append(field("state_#", value_type, value))
        return rows

    def _temperature_fields(self, key, comparator, value, trigger=False, relative=False):
        return [
            field("tDev#" if trigger else "rDev_#", "capability.temperatureMeasurement",
                  [str(self.fixtures[key])]),
            field("ReltDev#" if trigger else "RelrDev_#", "enum", comparator),
            *([field("istDev#" if trigger else "isDev_#", "bool", True),
               field("reltDevice#" if trigger else "relDevice_#",
                     "capability.temperatureMeasurement", str(self.fixtures["temperature2"]))]
              if relative else []),
            field("tstate#" if trigger else "state_#", "decimal", value),
        ]

    def _form(self, rule_id, step):
        result = self.call("hub_call_app_form", app_id=int(rule_id), steps=[step], confirm=True)
        self.assertTrue(result.get("success"), result)
        return result

    def _submit(self, rule_id, form, fields=None, control=None):
        step = {"kind": "submit", "expect_page": form["page"]["name"]}
        if fields is not None:
            step["fields"] = fields
        if control is not None:
            step["activate"] = control["control_id"]
        return self._form(rule_id, step)

    def _control(self, form, name=None, title=None):
        matches = [row for row in form["controls"]
                   if (name is None or row.get("name") == name)
                   and (title is None or title in row.get("title", ""))]
        self.assertEqual(len(matches), 1, {"name": name, "title": title, "form": form})
        return matches[0]

    def test_set_rule_enum_custom_attribute(self):
        rule_id = self.create([])
        trigger = self._row(rule_id, "trigger", "Custom Attribute",
                            self._switch_fields(trigger=True, custom=True))
        condition = self._switch_condition(rule_id, custom=True)
        conditional_trigger = self._row(rule_id, "trigger", "Switch", [
            *self._switch_fields(trigger=True),
            field("isCondTrig.#", "bool", True), field("condTrig.#", "enum", condition),
        ])
        self.assertEqual(self._value(rule_id, f"tCustomAttr{trigger}"), "switch")
        self.assertEqual(self._value(rule_id, f"tstate{trigger}"), "on")
        self._devices(rule_id, f"tDev{trigger}", "switch")
        self.assertEqual(self._value(rule_id, f"rCustomAttr_{condition}"), "switch")
        self.assertEqual(self._value(rule_id, f"state_{condition}"), "on")
        self._devices(rule_id, f"rDev_{condition}", "switch")
        self.assertEqual(str(self._value(rule_id, f"condTrig.{conditional_trigger}")), condition)
        self.healthy(rule_id)

    def test_set_rule_trigger_state_change_comparator(self):
        rule_id = self.create([])
        switch = self._row(rule_id, "trigger", "Switch",
                           self._switch_fields(trigger=True, state="*changed*"))
        self.assertEqual(self._value(rule_id, f"tstate{switch}"), "*changed*")
        self._devices(rule_id, f"tDev{switch}", "switch")
        mode = self._row(rule_id, "trigger", "Mode", [field("modesX#", "enum", ["0"])])
        self.assertEqual(list(map(str, self._value(rule_id, f"modesX{mode}"))), ["0"])
        self.healthy(rule_id)

    def test_set_rule_required_expression_and_local_var(self):
        rule_id = self.create([])
        self._refuse_unchanged(rule_id, [change("condition", fields=self._switch_fields())])
        self._refuse_unchanged(rule_id, [change("required", tokens=["999999999"])])
        self._action(rule_id, self._switch_action())
        self._refuse_unchanged(rule_id, [change("condition", capability="Switch", fields=[
            *self._switch_fields(), field("RelrDev_#", "enum", ">"),
        ])])
        self._ok(rule_id, [change("variable", operation="create", name="fields", type="integer", value=0)])
        action = self._action(rule_id, self._variable_action(
            "fields", "number", [field("valNumber.#", "number", 0)]))
        condition = self._switch_condition(rule_id)
        self._required(rule_id, [condition])
        self.assertEqual(self._value(rule_id, f"xVarV.{action}"), "fields")
        self.assertEqual(float(self._value(rule_id, f"valNumber.{action}")), 0)
        self.assertEqual(self.doc(rule_id)["variables"]["fields"]["value"], 0)
        self._refuse_unchanged(rule_id, [change(
            "variable", operation="create", name="fields", type="integer", value=10)])
        # DateTime locals retain the native date/time string used by its editor.
        timestamp = "2026-01-01T12:30:00.000-0500"
        self._ok(rule_id, [change("variable", operation="create", name="seenAt",
                                 type="datetime", value=timestamp)])
        local = self.doc(rule_id)["variables"]["seenAt"]
        self.assertEqual(local["type"], "datetime")
        self.assertEqual(local["value"], timestamp)
        self.assertIn("seenAt", self.page_text(rule_id))
        self.healthy(rule_id)
        self._refuse_unchanged(rule_id, [change(
            "variable", operation="create", name="seenEpoch", type="datetime", value=1767270600000)])
        self._refuse_unchanged(rule_id, [change(
            "variable", operation="create", name="seenInvalidDate", type="datetime", value="2026-02-30T12:30:00.000-0500")])
        self._refuse_unchanged(rule_id, [change(
            "variable", operation="create", name="seenBad", type="datetime", value="tomorrow")])
        self._ok(rule_id, [
            {"part": "delete", "value": {"part": "action", "id": action}},
            change("variable", operation="delete", name="fields"),
        ])
        self.assertNotIn("fields", self.doc(rule_id)["variables"])
        self.healthy(rule_id)

    def test_set_rule_contains_comparator(self):
        name = self.create_hub_variable("contains_msg", "String", "an error occurred")
        rule_id = self.create([])
        condition = self._row(rule_id, "condition", "Variable",
                              self._variable_fields(name, "*contains*", "error"))
        self._required(rule_id, [condition])
        self.assertEqual(self._value(rule_id, f"RelrDev_{condition}"), "*contains*")
        self.assertEqual(self._value(rule_id, f"state_{condition}"), "error")
        self.healthy(rule_id)
        boolean = self.create_hub_variable("bool_flag", "Boolean", True)
        other = self.create([])
        yes = self._row(other, "condition", "Variable",
                        self._variable_fields(boolean, value="true", value_type="enum"))
        no = self._row(other, "condition", "Variable",
                       self._variable_fields(boolean, value="false", value_type="enum"))
        self._required(other, [yes])
        self._ok(other, [
            change("action", type="condActs", subtype="getIfThen", fields=[], expression=[no]),
            log_action("boolean branch"), change("terminator", subtype="endIf"),
        ])
        self.assertEqual(sorted(str(self._value(other, f"state_{slot}")).lower()
                                for slot in (yes, no)), ["false", "true"])
        self.healthy(other)

    def test_set_rule_variable_rhs_parity(self):
        left = self.create_hub_variable("rhs_left", "Number", 0)
        right = self.create_hub_variable("rhs_right", "Number", 7)
        rule_id = self.create([])
        fields = self._variable_fields(left, ">", rhs=right, value=0, value_type="number")
        condition = self._row(rule_id, "condition", "Variable", fields)
        self._required(rule_id, [condition])
        self.assertEqual(self._value(rule_id, f"xVarR_{condition}"), right)
        # The native value slot is the variable offset. A second literal RHS
        # cannot silently overwrite that already specified slot.
        self._refuse_unchanged(rule_id, [change("condition", id=condition, capability="Variable",
                                               fields=[*fields, field("state_#", "number", 0)])])
        self.assertEqual(self.doc(rule_id)["expressions"]["0"], [condition])
        self.healthy(rule_id)

    def test_set_rule_remove_referenced_local_breaks_rule(self):
        rule_id = self.create([
            change("variable", operation="create", name="refLocal", type="integer", value=0),
            self._variable_action("refLocal", "number", [field("valNumber.#", "number", 9)]),
        ])
        self.run_actions(rule_id)
        self.wait_for(lambda: self.doc(rule_id)["variables"]["refLocal"]["value"] == 9)
        # The first fresh read must observe the run without rendering an editor.
        self.assertEqual(self.doc(rule_id)["variables"]["refLocal"]["value"], 9)
        result = self.update(rule_id, [change("variable", operation="delete", name="refLocal")])
        self.assertIs(result.get("success"), False, result)
        self.assertTrue(result.get("partial"), result)
        self.assertNotIn("refLocal", self.doc(rule_id)["variables"])
        self.assertIn("broken", str(result).lower())
        self.assertIn("broken", self.page_text(rule_id).lower())

    def test_set_rule_trigger_custom_attribute_enum_changed_routes(self):
        rule_id = self.create([])
        trigger = self._row(rule_id, "trigger", "Custom Attribute",
                            self._switch_fields(trigger=True, custom=True, state="*changed*"))
        self._devices(rule_id, f"tDev{trigger}", "switch")
        self.assertEqual(self._value(rule_id, f"tCustomAttr{trigger}"), "switch")
        self.assertEqual(self._value(rule_id, f"tstate{trigger}"), "*changed*")
        self.assertNotIn(f"ReltDev{trigger}", self.settings(rule_id))
        self.healthy(rule_id)

    def test_set_rule_re_custom_attribute_enum_changed_not_representable(self):
        rule_id = self.create([])
        self._refuse_unchanged(rule_id, [change("condition", capability="Custom Attribute",
                                               fields=self._switch_fields(custom=True, state="*changed*"))])
        self.assertFalse(self.doc(rule_id)["expressions"].get("0"))
        self.healthy(rule_id)

    def test_set_rule_replace_required_expression(self):
        rule_id = self.create([])
        on = self._switch_condition(rule_id)
        self._required(rule_id, [on])
        off = self._switch_condition(rule_id, state="off")
        self._required(rule_id, [off])
        self.assertEqual(str(self.status(rule_id)["installedApp"]["id"]), str(rule_id))
        self.assertIn("is off", self.page_text(rule_id).lower())
        self._refuse_unchanged(rule_id, [change("condition", id=off, capability="Switch",
                                               fields=self._switch_fields(state="definitely_not_a_valid_state"))])
        for missing_mode in ("NoSuchMode", "999999999"):
            with self.subTest(missing_mode=missing_mode):
                self._refuse_unchanged(rule_id, [change("condition", capability="Mode",
                                                       fields=[field("modes#", "enum", [missing_mode])])])
                self.assertEqual(self.doc(rule_id)["expressions"]["0"], [off])
                self.assertIn("is off", self.page_text(rule_id).lower())
                self.healthy(rule_id)
        # Deliberately exclude the historical backup restoration segment.
        name = self.create_hub_variable("changed_string", "String", "before")
        changed = self._row(rule_id, "condition", "Variable", self._variable_fields(name, "*changed*"))
        self._required(rule_id, [changed])
        self.healthy(rule_id)

    def test_set_rule_setvariable_from_device_and_math(self):
        target = self.create_hub_variable("math_number", "Number", 0)
        source = self.create_hub_variable("math_source", "Number", 7)
        string = self.create_hub_variable("math_string", "String", "initial")
        string_source = self.create_hub_variable("math_text_source", "String", "copied")
        boolean = self.create_hub_variable("math_bool", "Boolean", False)
        device_fields = [
            field("customDev.#", "capability.*", str(self.fixtures["temperature"])),
            field("tCustomAttr.#", "enum", "temperature"),
        ]
        rule_a = self.create([self._variable_action(target, "device attribute", device_fields)])
        action = str(self.doc(rule_a)["actions"][0]["id"])
        self.assertEqual(self._value(rule_a, f"tCustomAttr.{action}"), "temperature")
        self._devices(rule_a, f"customDev.{action}", "temperature")
        self.healthy(rule_a)
        rule_b = self.create([])
        for operator, operand in (("+", 10), ("-", target), ("+", 5.5)):
            with self.subTest(operator=operator, operand=operand):
                numeric = isinstance(operand, (int, float))
                fields = [field("xVar3.#", "enum", target), field("valMathOp.#", "enum", operator),
                          field("xVar4.#", "enum", "(constant)" if numeric else operand)]
                if numeric:
                    fields.append(field("valConst2.#", "number", operand))
                action = self._action(rule_b, self._variable_action(target, "variable math", fields))
                self.assertEqual(self._value(rule_b, f"xVar3.{action}"), target)
                self.assertEqual(self._value(rule_b, f"valMathOp.{action}"), operator)
                if numeric:
                    self.assertEqual(float(self._value(rule_b, f"valConst2.{action}")), operand)
                else:
                    self.assertEqual(self._value(rule_b, f"xVar4.{action}"), target)
        self.assertEqual(len(self.doc(rule_b)["actions"]), 3)
        self.healthy(rule_b)
        rule_c = self.create([self._variable_action(target, "variable math", [
            field("xVar3.#", "enum", target), field("valMathOp.#", "enum", "absolute"),
        ])])
        unary = str(self.doc(rule_c)["actions"][0]["id"])
        self.assertNotIn(f"xVar4.{unary}", self.settings(rule_c))
        self.assertNotIn(f"valConst2.{unary}", self.settings(rule_c))
        for invalid_target in (string, boolean, target):
            with self.subTest(invalid_target=invalid_target):
                self._refuse_unchanged(rule_c, [self._variable_action(invalid_target, "device attribute", [
                    field("customDev.#", "capability.*", str(self.fixtures["switch"])),
                    field("tCustomAttr.#", "enum", "switch"),
                ])])
        copied = self._action(rule_c, change("action", type="modeActs", subtype="getSetVariable", fields=[
            field("xVarV.#", "enum", string), field("valStringOp.#", "enum", "Copy variable"),
            field("xVar3.#", "enum", string_source),
        ]))
        self.assertEqual(self._value(rule_c, f"xVar3.{copied}"), string_source)
        self.assertEqual(self._value(rule_c, f"valStringOp.{copied}"), "Copy variable")
        self.assertNotIn(f"numOp.{copied}", self.settings(rule_c))
        self.assertNotIn(f"customDev.{copied}", self.settings(rule_c))
        self.healthy(rule_c)
        rule_d = self.create([
            self._variable_action(target, "variable", [
                field("xVar3.#", "enum", source), field("valOffset.#", "number", 0),
            ]),
            self._variable_action(target, "add number", [field("valNumber.#", "number", 3)]),
        ])
        copied, added = [str(row["id"]) for row in self.doc(rule_d)["actions"]]
        self.assertEqual(self._value(rule_d, f"xVar3.{copied}"), source)
        self.assertEqual(float(self._value(rule_d, f"valOffset.{copied}")), 0)
        self.assertEqual(float(self._value(rule_d, f"valNumber.{added}")), 3)
        self.run_actions(rule_d)
        self.wait_for(lambda: float(self.call("hub_get_variable", name=target)["value"]) == 10, timeout=15)
        self.healthy(rule_d)

    def test_set_rule_walker_enum_required_expression(self):
        rule_id = self.create([])
        condition = self._switch_condition(rule_id, custom=True)
        self._required(rule_id, [condition])
        self.assertEqual(self._value(rule_id, f"rCustomAttr_{condition}"), "switch")
        self.assertEqual(self._value(rule_id, f"state_{condition}"), "on")
        self._devices(rule_id, f"rDev_{condition}", "switch")
        self.healthy(rule_id)

    def test_set_rule_walker_compare_to_device(self):
        rule_id = self.create([])
        condition = self._row(rule_id, "condition", "Temperature",
                              self._temperature_fields("temperature", ">", -2, relative=True))
        self._required(rule_id, [condition])
        self._devices(rule_id, f"rDev_{condition}", "temperature")
        self._devices(rule_id, f"relDevice_{condition}", "temperature2")
        self.assertEqual(float(self._value(rule_id, f"state_{condition}")), -2)
        self.assertIn("temperature of", self.page_text(rule_id).lower())
        trigger = self._row(rule_id, "trigger", "Temperature",
                            self._temperature_fields("temperature", ">", 2, trigger=True, relative=True))
        self._devices(rule_id, f"reltDevice{trigger}", "temperature2")
        self.assertEqual(float(self._value(rule_id, f"tstate{trigger}")), 2)
        extra_condition = self._row(rule_id, "condition", "Temperature",
                                    self._temperature_fields("temperature", "<", 1, relative=True))
        conditional = self._row(rule_id, "trigger", "Temperature", [
            *self._temperature_fields("temperature", ">", 50, trigger=True),
            field("isCondTrig.#", "bool", True), field("condTrig.#", "enum", extra_condition),
        ])
        self.assertEqual(str(self._value(rule_id, f"condTrig.{conditional}")), extra_condition)
        self.assertEqual(self._value(rule_id, f"RelrDev_{extra_condition}"), "<")
        self.healthy(rule_id)
        # Both an offset and a competing literal map to state_; duplicate writes
        # must be refused, rather than silently letting one RHS replace the other.
        self._refuse_unchanged(rule_id, [change("condition", id=condition, capability="Temperature", fields=[
            *self._temperature_fields("temperature", ">", -2, relative=True),
            field("state_#", "decimal", 70),
        ])])

    def test_set_rule_required_expression_multi_condition(self):
        for grouped in (False, True):
            with self.subTest(grouped=grouped):
                rule_id = self.create([])
                ids = [self._row(rule_id, "condition", "Temperature", self._temperature_fields(key, op, value))
                       for key, op, value in (("temperature", ">", 70), ("temperature2", "<", 80),
                                              ("temperature", ">=", 65))]
                tokens = (["(", ids[0], "OR", ids[1], ")", "AND", ids[2]] if grouped
                          else [ids[0], "AND", ids[1], "AND", ids[2]])
                self._required(rule_id, tokens)
                self.assertEqual(len(self.doc(rule_id)["conditions"]), 3)
                page = html.unescape(self.page_text(rule_id))
                self.assertGreaterEqual(page.lower().count("temperature of"), 3, page)
                self.assertIn("OR" if grouped else "AND", page)
                self.assertIn("< 80", page)
                self.healthy(rule_id)

    def test_set_rule_action_after_required_expression(self):
        rule_id = self.create([])
        required = self._switch_condition(rule_id)
        self._required(rule_id, [required])
        log_id = self._action(rule_id, log_action("after-RE"))
        first = next(row["value"] for row in self.doc(rule_id)["actions"] if str(row["id"]) == log_id)
        self.assertFalse(first.get("cond"), first)
        self.assertFalse(first.get("rule"), first)
        condition = self._switch_condition(rule_id)
        action = self._action(rule_id, self._switch_action(condition=condition))
        self._ok(rule_id, [self._switch_action(False, id=action)])
        compiled = next(row["value"] for row in self.doc(rule_id)["actions"] if str(row["id"]) == action)
        self.assertEqual(str(compiled["cond"]), condition)
        self.assertIn(str(self._value(rule_id, f"onOff.{action}")).lower(), ("false", "off"))
        self.assertIn("IF (", self.page_text(rule_id))
        defined = log_action("defined condition")
        defined["value"]["condition"] = condition
        defined_id = self._action(rule_id, defined)
        self.assertEqual(str(self._value(rule_id, f"useCondNdx.{defined_id}")), condition)
        self.assertEqual(str(self._value(rule_id, f"useCond.{defined_id}")).lower(), "true")
        self.assertEqual(set(map(str, self.doc(rule_id)["conditions"])), {required, condition})
        self.assertNotIn("Broken Condition", self.page_text(rule_id))
        self.healthy(rule_id)
        self._refuse_unchanged(rule_id, [change("action", type="modeActs", subtype="getDefinedAction",
                                               condition=condition, fields=[
            field("myCapab.#", "enum", "Switch"),
            field("devices.#", "capability.switch", [str(self.fixtures["switch"])]),
            field("cCmd.#", "enum", "noSuchCommandE2E"),
        ])])
        # This proves preservation of reused conditions. Creating and rolling back
        # a new inline condition remains a separate historical coverage gap.

    def test_set_rule_walkstep_action_after_required_expression(self):
        rule_id = self.create([])
        condition = self._switch_condition(rule_id)
        self._required(rule_id, [condition])
        form = self._form(rule_id, {"kind": "open", "page": "selectActions"})
        links = [row for row in form["controls"] if row.get("kind") == "button"
                 and row.get("control_id") == "N||doAct"]
        self.assertEqual(len(links), 1, form)
        form = self._submit(rule_id, form, control=links[0])
        links = [row for row in form["controls"] if row.get("kind") == "href"
                 and row.get("page") == "doActPage"]
        self.assertEqual(len(links), 1, form)
        form = self._submit(rule_id, form, control=links[0])
        names = [row["name"] for row in form["fields"] if row["name"].startswith("actType.")]
        self.assertEqual(len(names), 1, form)
        index = names[0].split(".", 1)[1]
        for name, value in ((f"actType.{index}", "messageActs"), (f"actSubType.{index}", "getLogMsg"),
                            (f"logmsg.{index}", "walkstep-after-RE")):
            form = self._submit(rule_id, form, fields={name: value})
        self._submit(rule_id, form, control=self._control(form, name="actionDone"))
        self.assertEqual(self._value(rule_id, f"logmsg.{index}"), "walkstep-after-RE")
        rows = [row for row in self.doc(rule_id)["actions"] if str(row["id"]) == index]
        self.assertEqual(len(rows), 1, rows)
        self.assertEqual(rows[0]["value"]["method"], "getLogMsg")
        self.assertFalse(rows[0]["value"].get("cond"), rows)
        self.assertFalse(rows[0]["value"].get("rule"), rows)
        self.assertNotIn("Broken Condition", self.page_text(rule_id))
        self.assertIn("walkstep-after-RE", self.page_text(rule_id))

    def test_set_rule_update_with_false_required_expression_is_suppressed(self):
        rule_id = self.create([])
        self._row(rule_id, "trigger", "Switch", self._switch_fields(trigger=True))
        condition = self._row(rule_id, "condition", "Private Boolean",
                              [field("state_#", "enum", "true")])
        self._required(rule_id, [condition])
        self._action(rule_id, log_action("gated trigger"))
        result = self.call("hub_set_rule_private_boolean", ruleId=int(rule_id), value=False)
        self.assertIsNot(result.get("success"), False, result)
        self.wait_for(lambda: not (self.status(rule_id).get("eventSubscriptions") or []))
        result = self._ok(rule_id, [log_action("edit while required expression is false")])
        self.assertEqual(self.doc(rule_id)["expressions"]["0"], [condition])
        self.assertFalse(self.status(rule_id).get("eventSubscriptions"))
        self.assertFalse(self.status(rule_id).get("scheduledJobs"))
        self.healthy(rule_id)
        self.assertIn("SUPPRESSED", str(result).upper(),
                      "A false Required Expression must be distinguished from missing activation")


if __name__ == "__main__":
    unittest.main(verbosity=2)
