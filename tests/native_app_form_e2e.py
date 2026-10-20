"""Opt-in native form acceptance using disposable native rules.

Uses native_rule_runtime_e2e.py's environment and labeled virtual fixtures.
All calls use the new document/form tools or independent native UI endpoints.
Existing fixture devices receive no commands. Run this file explicitly.
"""

import time
import unittest

import native_rule_document_e2e as documents
import native_rule_runtime_e2e as runtime


class NativeAppFormE2E(unittest.TestCase):
    setUpClass = classmethod(runtime.NativeRuleRuntimeE2E.setUpClass.__func__)
    fixture_metadata = classmethod(runtime.NativeRuleRuntimeE2E.fixture_metadata.__func__)
    setUp = documents.NativeRuleDocumentE2E.setUp
    report_timing = documents.NativeRuleDocumentE2E.report_timing
    status = documents.NativeRuleDocumentE2E.status
    settings = documents.NativeRuleDocumentE2E.settings
    state = documents.NativeRuleDocumentE2E.state
    document = documents.NativeRuleDocumentE2E.document
    create_rule = documents.NativeRuleDocumentE2E.create_rule
    remove_rule = documents.NativeRuleDocumentE2E.remove_rule
    update = documents.NativeRuleDocumentE2E.update
    config_snapshot = documents.NativeRuleDocumentE2E.config_snapshot
    wait_for = runtime.NativeRuleRuntimeE2E.wait_for

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.2f}s "
              f"calls={dict(cls.client.calls)}", flush=True)
        print("COVERAGE GAPS: other native app families, child creation, "
              "and remaining native editor controls", flush=True)

    def form(self, rule_id, steps, **options):
        return self.client.tool(
            "hub_call_app_form", app_id=int(rule_id),
            steps=steps, confirm=True, **options,
        )

    def open_form(self, rule_id, page=None):
        step = {"kind": "open"}
        if page:
            step["page"] = page
        result = self.form(rule_id, [step])
        self.assertIs(result.get("success"), True, result)
        return result

    def submit(self, rule_id, page, fields=None, control=None):
        step = {"kind": "submit", "expect_page": page}
        if fields is not None:
            step["fields"] = fields
        if control is not None:
            step["activate"] = control["control_id"]
        return self.form(rule_id, [step])

    def control(self, form, **match):
        found = [item for item in form["controls"]
                 if all(item.get(key) == value for key, value in match.items())]
        self.assertEqual(len(found), 1, {"wanted": match, "controls": form["controls"]})
        self.assertIsInstance(found[0]["control_id"], str)
        return found[0]

    def create_multidevice_rule(self):
        return self.create_rule([
            documents.change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
                documents.field("onOffSwitch.#", "capability.switch", [
                    str(self.fixtures["switch"]), str(self.fixtures["switch2"]),
                ]),
                documents.field("onOff.#", "bool", True),
            ]),
            documents.change(
                "variable", operation="create", name="Counter", type="integer", value=0,
            ),
        ])

    def test_form_and_native_ui_edits_preserve_existing_device_selection(self):
        rule_id = self.create_multidevice_rule()
        original = self.settings(rule_id)
        pickers = {name: row for name, row in original.items()
                   if name.startswith("onOffSwitch.")}
        self.assertEqual(len(pickers), 1)
        expected_devices = {str(self.fixtures[key]) for key in ("switch", "switch2")}
        self.assertEqual(set(next(iter(pickers.values()))["deviceList"]), expected_devices)
        form = self.open_form(rule_id)
        self.assertEqual(form["page"]["name"], "mainPage")
        self.assertTrue(any(item["name"] == "comments" for item in form["fields"]))
        result = self.submit(rule_id, "mainPage", {"comments": "Written through native form"})
        self.assertIs(result.get("success"), True, result)
        self.assertEqual(self.settings(rule_id)["comments"]["value"], "Written through native form")
        for name, row in pickers.items():
            self.assertEqual(self.settings(rule_id)[name], row)

        # Independently edit through the native UI endpoint and read through the new tool.
        native = self.client.native(f"/installedapp/configure/json/{rule_id}")
        self.client.native("/installedapp/update/json", "POST", form={
            "id": rule_id, "currentPage": native["configPage"]["name"],
            "pageBreadcrumbs": native["pageBreadcrumbs"],
            "_cancellable": str(native["cancelButton"]).lower(),
            "settings[comments]": "Written through native UI",
            "comments.type": "textarea", "comments.multiple": "false",
        })
        document = self.document(rule_id, ["settings"])
        rows = {row["name"]: row for row in document["settings"]}
        self.assertEqual(rows["comments"]["value"], "Written through native UI")
        self.assertEqual(str(document["rule_id"]), rule_id)
        for name, row in pickers.items():
            self.assertEqual(self.settings(rule_id)[name], row)

    def test_native_choices_and_required_inputs_refuse_without_setting_changes(self):
        rule_id = self.create_multidevice_rule()
        form = self.open_form(rule_id)
        logging = next(item for item in form["fields"] if item["name"] == "logging")
        self.assertIs(logging["multiple"], True)
        self.assertTrue(logging["options"])
        self.assertNotIn("__invalid_logging__", [item["value"] for item in logging["options"]])
        before = self.config_snapshot(rule_id)
        result = self.submit(rule_id, "mainPage", {"logging": ["__invalid_logging__"]})
        self.assertIs(result.get("success"), False, result)
        self.assertIs(result.get("partial"), False, result)
        self.assertEqual(self.config_snapshot(rule_id), before)
        result = self.submit(rule_id, "mainPage", {"origLabel": ""})
        self.assertIs(result.get("success"), False, result)
        self.assertIs(result.get("partial"), False, result)
        self.assertEqual(self.config_snapshot(rule_id), before)

    def test_native_buttons_keep_same_name_controls_distinct_and_execute(self):
        rule_id = self.create_rule([
            documents.change(
                "variable", operation="create", name="Counter", type="integer", value=0,
            ),
            runtime.variable_action(
                "Counter", "add number", [documents.field("valNumber.#", "number", 1)],
            ),
        ])
        actions = self.open_form(rule_id, "selectActions")
        counter_controls = [item for item in actions["controls"] if item["name"] == "Counter"]
        self.assertEqual(len(counter_controls), 2, counter_controls)
        self.assertEqual(len({item["control_id"] for item in counter_controls}), 2)
        self.assertTrue(any("Edit value" in item["title"] for item in counter_controls))
        self.assertTrue(any("Delete" in item["title"] for item in counter_controls))

        form = self.open_form(rule_id)
        result = self.submit(rule_id, "mainPage", control=self.control(form, name="pausRule"))
        self.assertIs(result.get("success"), True, result)
        self.assertIs(self.document(rule_id)["nativeHealth"]["paused"], True)
        result = self.submit(rule_id, result["page"]["name"],
                             control=self.control(result, name="pausRule"))
        self.assertIs(result.get("success"), True, result)
        self.assertIs(self.document(rule_id)["nativeHealth"]["paused"], False)
        result = self.submit(rule_id, result["page"]["name"],
                             control=self.control(result, name="runAction"))
        self.assertIs(result.get("success"), True, result)
        self.wait_for(lambda: self.state(rule_id)["lv_Counter"]["value"] == 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
