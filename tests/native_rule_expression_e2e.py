"""Opt-in expression and trigger runtime acceptance for native rule documents.

Uses the environment and labeled virtual fixtures of native_rule_runtime_e2e.py.
Commands are restricted to those verified fixtures; cleanup removes created rules
before restoring fixture values. Run: python tests/native_rule_expression_e2e.py

Covers expression evaluation, required-expression suppression, trigger mutation,
and stopped batches. Unsupported behavior fails these acceptance tests.
"""

import json
import time
import unittest

import native_rule_document_e2e as document_helpers
import native_rule_runtime_e2e as runtime_helpers


field = document_helpers.field
change = document_helpers.change
log_action = document_helpers.log_action


class NativeRuleExpressionE2E(unittest.TestCase):
    # Borrow helpers explicitly so importing this suite does not inherit other tests.
    setUpClass = classmethod(runtime_helpers.NativeRuleRuntimeE2E.setUpClass.__func__)
    fixture_metadata = classmethod(runtime_helpers.NativeRuleRuntimeE2E.fixture_metadata.__func__)
    setUp = runtime_helpers.NativeRuleRuntimeE2E.setUp
    report_timing = runtime_helpers.NativeRuleRuntimeE2E.report_timing
    remember_fixture = runtime_helpers.NativeRuleRuntimeE2E.remember_fixture
    command = runtime_helpers.NativeRuleRuntimeE2E.command
    restore_fixtures = runtime_helpers.NativeRuleRuntimeE2E.restore_fixtures
    attribute = runtime_helpers.NativeRuleRuntimeE2E.attribute
    wait_for = runtime_helpers.NativeRuleRuntimeE2E.wait_for
    run_actions = runtime_helpers.NativeRuleRuntimeE2E.run_actions
    assert_variable = runtime_helpers.NativeRuleRuntimeE2E.assert_variable
    status = document_helpers.NativeRuleDocumentE2E.status
    settings = document_helpers.NativeRuleDocumentE2E.settings
    state = runtime_helpers.NativeRuleRuntimeE2E.state
    document = document_helpers.NativeRuleDocumentE2E.document
    healthy = document_helpers.NativeRuleDocumentE2E.healthy
    create_rule = document_helpers.NativeRuleDocumentE2E.create_rule
    remove_rule = document_helpers.NativeRuleDocumentE2E.remove_rule
    update = document_helpers.NativeRuleDocumentE2E.update
    config_snapshot = document_helpers.NativeRuleDocumentE2E.config_snapshot

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.2f}s "
              f"calls={dict(cls.client.calls)}", flush=True)
        print("COVERAGE GAPS: scheduling, wait delivery, other event capabilities, "
              "generic native apps, and backup/restore", flush=True)

    def condition(self, key, state="on", **extra):
        return change("condition", capability="Switch", fields=[
            field("rDev_#", "capability.switch", [str(self.fixtures[key])]),
            field("state_#", "enum", state),
        ], **extra)

    def trigger(self, key="switch", state="on", **extra):
        return change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [str(self.fixtures[key])]),
            field("tstate#", "enum", state),
        ], **extra)

    def add_counter(self, amount=1, target="Events"):
        return runtime_helpers.variable_action(
            target, "add number", [field("valNumber.#", "number", amount)],
        )

    def condition_id(self, rule_id, key):
        document = self.document(rule_id)
        settings = self.settings(rule_id)
        matches = [str(condition_id) for condition_id in document["conditions"]
                   if set(settings[f"rDev_{condition_id}"]["deviceList"])
                   == {str(self.fixtures[key])}]
        self.assertEqual(len(matches), 1, f"Expected one condition for {key}")
        return matches[0]

    def set_switch(self, key, value):
        self.command(key, value)
        self.wait_for(lambda: self.attribute(key, "switch") == value)

    def subscriptions(self, rule_id, key=None):
        status = self.status(rule_id)
        self.assertEqual(str(status["installedApp"]["id"]), str(rule_id))
        rows = status.get("eventSubscriptions")
        if rows is None:
            rows = []
        self.assertIsInstance(rows, list)
        if key is None:
            return rows
        return [row for row in rows if row.get("type") == "DEVICE"
                and str(row.get("typeId")) == str(self.fixtures[key])
                and row.get("name") == "switch"]

    def assert_counter_stable(self, rule_id, expected, duration=3):
        deadline = time.monotonic() + duration
        while True:
            self.assertEqual(self.state(rule_id)["lv_Events"]["value"], expected)
            if time.monotonic() >= deadline:
                return
            time.sleep(0.25)

    def test_grouped_and_or_not_expression_runs_its_full_truth_table(self):
        rule_id = self.create_rule([
            self.condition("switch"), self.condition("switch2"),
            change("variable", operation="create", name="Events", type="integer", value=0),
        ])
        a = self.condition_id(rule_id, "switch")
        b = self.condition_id(rule_id, "switch2")
        tokens = [a, "OR", "(", b, "AND", "NOT", a, ")"]
        self.update(rule_id, [
            change("action", type="condActs", subtype="getIfThen", fields=[], expression=tokens),
            self.add_counter(), change("terminator", subtype="else"),
            self.add_counter(10), change("terminator", subtype="endIf"),
        ])
        document = self.document(rule_id)
        self.healthy(document)
        branches = [row for row in document["actions"]
                    if row["value"].get("method") == "getIfThen"]
        self.assertEqual(len(branches), 1)
        expression_id = str(branches[0]["value"]["rule"])
        self.assertEqual(document["expressions"][expression_id], tokens)
        self.assertEqual(self.state(rule_id)["eval"][expression_id], tokens)
        action_ids = [str(row["id"]) for row in document["actions"]]
        for first, second, expected in (
            ("off", "off", 10), ("off", "on", 1),
            ("on", "off", 1), ("on", "on", 1),
        ):
            with self.subTest(first=first, second=second):
                self.set_switch("switch", first)
                self.set_switch("switch2", second)
                self.update(rule_id, [change("variable", operation="set", name="Events", value=0)])
                self.assert_variable(rule_id, "Events", 0)
                self.run_actions(rule_id)
                self.assert_variable(rule_id, "Events", expected)
                self.assertEqual([str(i) for i in self.state(rule_id)["actionList"]], action_ids)

    def test_false_required_expression_suppresses_trigger_and_rearms_when_true(self):
        self.set_switch("switch", "off")
        self.set_switch("switch2", "off")
        rule_id = self.create_rule([
            self.condition("switch2"), self.trigger(),
            change("variable", operation="create", name="Events", type="integer", value=0),
            self.add_counter(),
        ])
        gate = self.condition_id(rule_id, "switch2")
        self.update(rule_id, [change("required", tokens=[gate])])
        document = self.document(rule_id)
        self.healthy(document)
        native = self.state(rule_id)
        self.assertIs(native["hasPredicate"], True)
        self.assertEqual(native["eval"]["0"], [gate])
        self.wait_for(lambda: not self.subscriptions(rule_id, "switch"))
        self.assertTrue(self.subscriptions(rule_id, "switch2"), "Required-expression watcher is missing")
        self.set_switch("switch", "on")
        self.assert_counter_stable(rule_id, 0)
        self.set_switch("switch", "off")
        self.set_switch("switch2", "on")
        self.wait_for(lambda: bool(self.subscriptions(rule_id, "switch")))
        self.assert_variable(rule_id, "Events", 0)
        self.set_switch("switch", "on")
        self.assert_variable(rule_id, "Events", 1)
        self.set_switch("switch2", "off")
        self.wait_for(lambda: not self.subscriptions(rule_id, "switch"))
        self.set_switch("switch", "off")
        self.set_switch("switch", "on")
        self.assert_counter_stable(rule_id, 1)
        self.healthy(self.document(rule_id))

    def test_trigger_state_edit_retarget_and_removal_change_actual_delivery(self):
        self.set_switch("switch", "off")
        self.set_switch("switch2", "off")
        rule_id = self.create_rule([
            self.trigger(),
            change("variable", operation="create", name="Events", type="integer", value=0),
            self.add_counter(),
        ])
        trigger_id = str(next(iter(self.document(rule_id)["triggers"])))
        action_ids = [str(i) for i in self.state(rule_id)["actionList"]]
        self.wait_for(lambda: bool(self.subscriptions(rule_id, "switch")))
        self.set_switch("switch", "on")
        self.assert_variable(rule_id, "Events", 1)
        self.update(rule_id, [self.trigger(state="off", id=trigger_id)])
        settings = self.settings(rule_id)
        self.assertEqual(settings[f"tstate{trigger_id}"]["value"], "off")
        self.assertEqual(set(self.document(rule_id)["triggers"]), {trigger_id})
        self.set_switch("switch", "off")
        self.assert_variable(rule_id, "Events", 2)
        self.set_switch("switch", "on")
        self.assert_counter_stable(rule_id, 2)
        self.update(rule_id, [self.trigger("switch2", "on", id=trigger_id)])
        settings = self.settings(rule_id)
        self.assertEqual(set(settings[f"tDev{trigger_id}"]["deviceList"]),
                         {str(self.fixtures["switch2"])})
        self.assertEqual(settings[f"tstate{trigger_id}"]["value"], "on")
        self.assertEqual(set(self.document(rule_id)["triggers"]), {trigger_id})
        self.wait_for(lambda: not self.subscriptions(rule_id, "switch")
                      and bool(self.subscriptions(rule_id, "switch2")))
        self.set_switch("switch", "off")
        self.set_switch("switch", "on")
        self.assert_counter_stable(rule_id, 2)
        self.set_switch("switch2", "on")
        self.assert_variable(rule_id, "Events", 3)
        self.update(rule_id, [{"part": "delete", "value": {"part": "trigger", "id": trigger_id}}])
        self.assertEqual(self.document(rule_id)["triggers"], {})
        self.assertEqual(self.state(rule_id)["capabstrue"], {})
        settings = self.settings(rule_id)
        for prefix in ("tCapab", "tDev", "tstate"):
            self.assertNotIn(prefix + trigger_id, settings)
        self.wait_for(lambda: not self.subscriptions(rule_id))
        self.set_switch("switch2", "off")
        self.set_switch("switch2", "on")
        self.assert_counter_stable(rule_id, 3)
        self.assertEqual([str(i) for i in self.state(rule_id)["actionList"]], action_ids)
        self.healthy(self.document(rule_id))

    def test_changed_trigger_fires_for_both_switch_edges(self):
        self.set_switch("switch", "off")
        rule_id = self.create_rule([
            self.trigger(state="*changed*"),
            change("variable", operation="create", name="Events", type="integer", value=0),
            self.add_counter(),
        ])
        trigger_id = str(next(iter(self.document(rule_id)["triggers"])))
        self.assertEqual(self.settings(rule_id)[f"tstate{trigger_id}"]["value"], "*changed*")
        self.wait_for(lambda: bool(self.subscriptions(rule_id, "switch")))
        for expected, value in enumerate(("on", "off", "on"), start=1):
            with self.subTest(edge=value, count=expected):
                self.set_switch("switch", value)
                self.assert_variable(rule_id, "Events", expected)
        self.healthy(self.document(rule_id))

    def test_invalid_shapes_and_expressions_stop_all_following_writes(self):
        rule_id = self.create_rule([
            self.condition("switch"),
            change("variable", operation="create", name="Sentinel", type="integer", value=0),
            log_action("baseline"),
        ])
        condition_id = self.condition_id(rule_id, "switch")
        invalid = [
            change("required", tokens=[condition_id, "AND"]),
            change("required", tokens=["(", condition_id]),
            change("required", tokens=["999999999"]),
            {"part": "trigger", "value": {"capability": "Switch", "fields": [], "unknown": True}},
        ]
        for operation in invalid:
            with self.subTest(operation=operation):
                before = self.config_snapshot(rule_id)
                subscriptions = self.subscriptions(rule_id)
                result = self.client.tool(
                    "hub_update_rule_document", rule_id=rule_id, confirm=True,
                    changes=[operation, change("variable", operation="set", name="Sentinel", value=99),
                             log_action("must not be written")],
                )
                self.assertIs(result.get("success"), False, result)
                self.assertIs(result.get("partial"), False, result)
                self.assertEqual(self.config_snapshot(rule_id), before)
                self.assertEqual(self.subscriptions(rule_id), subscriptions)
                self.assert_variable(rule_id, "Sentinel", 0)

    def test_native_enum_failure_keeps_earlier_write_and_stops_later_changes(self):
        rule_id = self.create_rule([
            change("variable", operation="create", name="Stage", type="integer", value=0),
            change("variable", operation="create", name="Sentinel", type="integer", value=0),
            log_action("baseline"),
        ])
        before = self.config_snapshot(rule_id)
        subscriptions = self.subscriptions(rule_id)
        skipped = [change("variable", operation="set", name="Sentinel", value=99),
                   log_action("must not be written")]
        result = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, confirm=True,
            changes=[change("variable", operation="set", name="Stage", value=1),
                     self.condition("switch", "__invalid_switch_state__"), *skipped],
        )
        self.assertIs(result.get("success"), False, result)
        self.assertIs(result.get("partial"), True, result)
        self.assertIn("state_", json.dumps(result))
        self.assertEqual([row["part"] for row in result["results"]], ["variable", "condition"])
        self.assertEqual(result["remaining"], skipped)
        self.assert_variable(rule_id, "Stage", 1)
        self.assert_variable(rule_id, "Sentinel", 0)
        after = self.config_snapshot(rule_id)
        self.assertEqual(after["state"]["actionList"], before["state"]["actionList"])
        self.assertEqual(after["state"]["actions"], before["state"]["actions"])
        self.assertNotIn("must not be written", json.dumps(after["settings"]))
        self.assertEqual(self.subscriptions(rule_id), subscriptions)


if __name__ == "__main__":
    unittest.main(verbosity=2)
