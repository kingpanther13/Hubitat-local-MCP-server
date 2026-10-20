"""Opt-in native form acceptance using disposable native rules.

Uses native_rule_runtime_e2e.py's environment and labeled virtual fixtures.
All calls use the new document/form tools or independent native UI endpoints.
Existing fixture devices receive no commands. Run this file explicitly.
"""

import json
import os
import time
import unittest

import native_rule_document_e2e as documents
import native_rule_runtime_e2e as runtime


class NativeAppFormE2E(unittest.TestCase):
    setUpClass = classmethod(runtime.NativeRuleRuntimeE2E.setUpClass.__func__)
    fixture_metadata = classmethod(runtime.NativeRuleRuntimeE2E.fixture_metadata.__func__)
    setUp = runtime.NativeRuleRuntimeE2E.setUp
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
    attribute = runtime.NativeRuleRuntimeE2E.attribute
    command = runtime.NativeRuleRuntimeE2E.command
    remember_fixture = runtime.NativeRuleRuntimeE2E.remember_fixture
    restore_fixtures = runtime.NativeRuleRuntimeE2E.restore_fixtures

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.2f}s "
              f"calls={dict(cls.client.calls)}", flush=True)
        print("COVERAGE GAPS: other native app families, deeper child configuration, "
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

    def remove_native_child(self, child_id, parent_id, app_name):
        status = self.status(child_id, missing_ok=True)
        child = status.get("installedApp")
        if not child:
            return
        self.assertEqual(str(child["id"]), child_id)
        self.assertEqual(str(child["parentAppId"]), str(parent_id))
        self.assertEqual(child["appType"]["namespace"], "hubitat")
        self.assertEqual(child["appType"]["name"], app_name)
        self.client.native("/installedapp/update/json", "POST", form={
            "id": child_id, "_action_remove": "Remove",
        })
        self.assertNotIn("installedApp", self.status(child_id, missing_ok=True))

    def test_create_native_children_from_their_parent_controls(self):
        parents = json.loads(os.environ.get("NATIVE_APP_FORM_PARENT_IDS", "{}"))
        self.assertEqual(set(parents), {"button_controller", "room_lighting"},
                         "Supply the two disposable native parent IDs")
        for parent_id in parents.values():
            with self.subTest(parent_id=parent_id):
                parent = self.open_form(parent_id)
                control = self.control(parent, kind="create_child")
                result = self.submit(parent_id, parent["page"]["name"], control=control)
                child_id = str(result.get("created_app_id", ""))
                if child_id.isdigit():
                    self.addCleanup(self.remove_native_child, child_id, parent_id, control["value"])
                self.assertIs(result.get("success"), True, result)
                self.assertTrue(child_id.isdigit(), result)
                self.assertIs(result.get("closed"), True)
                child = self.status(child_id)["installedApp"]
                self.assertEqual(str(child["parentAppId"]), str(parent_id))
                self.assertEqual(child["appType"]["namespace"], "hubitat")
                self.assertEqual(child["appType"]["name"], control["value"])
                opened = self.open_form(child_id)
                self.assertTrue(opened["page"]["name"])
                self.assertTrue(opened["fields"] or opened["controls"])

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

    def test_button_child_creation_edit_and_actual_button_delivery(self):
        button_id = str(self.fixtures.get("button", ""))
        self.assertTrue(button_id.isdigit(), "Supply the labeled virtual button fixture")
        self.assertNotIn(button_id, [str(self.fixtures[key])
                                    for key in ("switch", "switch2", "temperature")])
        self.fixture_metadata("button")
        parents = json.loads(os.environ.get("NATIVE_APP_FORM_PARENT_IDS", "{}"))
        parent_id = parents["button_controller"]
        parent = self.open_form(parent_id)
        create = self.control(parent, kind="create_child")
        created = self.submit(parent_id, parent["page"]["name"], control=create)
        controller_id = str(created.get("created_app_id", ""))
        if controller_id.isdigit():
            self.addCleanup(self.remove_native_child, controller_id, parent_id, create["value"])
        self.assertIs(created.get("success"), True, created)
        self.assertTrue(controller_id.isdigit(), created)

        form = self.open_form(controller_id)
        form = self.submit(controller_id, form["page"]["name"], {"buttonDev": [button_id]})
        self.assertIs(form.get("success"), True, form)
        add_button = self.control(form, title="Add Button")
        form = self.submit(controller_id, form["page"]["name"], control=add_button)
        self.assertIs(form.get("success"), True, form)
        form = self.submit(controller_id, form["page"]["name"], {"newBut": ["1"]})
        self.assertIs(form.get("success"), True, form)
        # The native page's changeSubmit script reloads after committing the selection.
        form = self.open_form(controller_id)
        create_rule = self.control(form, name="1 pushed")
        handoff = self.submit(controller_id, form["page"]["name"], control=create_rule)
        rule_id = str(handoff.get("navigated_app_id", ""))
        if rule_id.isdigit():
            self.addCleanup(self.remove_native_child, rule_id, controller_id, "Button Rule-5.1")
        self.assertIs(handoff.get("success"), True, handoff)
        self.assertTrue(rule_id.isdigit(), handoff)
        self.assertIs(handoff.get("closed"), True)
        self.assertEqual(handoff.get("navigated_page"), "selectActions")
        initial = self.settings(rule_id)
        binding = {key: initial[key] for key in ("tDev1", "tCapab1", "tstate1", "ButtontDev1")}
        self.assertEqual(set(binding["tDev1"]["deviceList"]), {button_id})
        self.assertEqual(binding["tstate1"]["value"], "pushed")
        self.assertEqual(str(binding["ButtontDev1"]["value"]), "1")

        action = documents.change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            documents.field("onOffSwitch.#", "capability.switch", [str(self.fixtures["switch"])]),
            documents.field("onOff.#", "bool", True),
        ])
        self.update(rule_id, [action])
        self.assertEqual({key: self.settings(rule_id)[key] for key in binding}, binding)
        document = self.document(rule_id)
        self.assertFalse(document["nativeHealth"]["broken"])
        self.assertEqual(len(document["actions"]), 1)
        action_id = document["actions"][0]["id"]
        subscriptions = self.status(rule_id)["eventSubscriptions"]
        self.assertTrue(any(str(item.get("typeId")) == button_id
                            and item.get("name", "").startswith("pushed")
                            for item in subscriptions), subscriptions)
        self.command("switch", "off")
        self.command("button", "push", [1], remember=False)
        self.wait_for(lambda: self.attribute("switch", "switch") == "on")

        # Retarget the existing native row; preserve the parent event binding and rule ID.
        action["value"]["id"] = action_id
        action["value"]["fields"][0]["value"] = [str(self.fixtures["switch2"])]
        self.update(rule_id, [action])
        self.assertEqual({key: self.settings(rule_id)[key] for key in binding}, binding)
        self.assertEqual(self.document(rule_id)["actions"][0]["id"], action_id)
        self.command("switch", "off")
        self.command("switch2", "off")
        self.command("button", "push", [1], remember=False)
        self.wait_for(lambda: self.attribute("switch2", "switch") == "on")
        self.assertEqual(self.attribute("switch", "switch"), "off")
        controller = self.open_form(controller_id)
        link = self.control(controller, kind="local_app", value=rule_id)
        handoff = self.submit(controller_id, controller["page"]["name"], control=link)
        self.assertIs(handoff.get("success"), True, handoff)
        self.assertEqual(str(handoff["navigated_app_id"]), rule_id)
        self.assertEqual(handoff["navigated_page"], "selectActions")
        opened = self.open_form(rule_id, handoff["navigated_page"])
        self.assertEqual(opened["page"]["name"], "selectActions")

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

    def test_room_lighting_native_configuration_and_event_binding_edit(self):
        button_id = str(self.fixtures.get("button", ""))
        self.assertTrue(button_id.isdigit(), "Supply the labeled virtual button fixture")
        self.fixture_metadata("button")
        parent_id = json.loads(os.environ["NATIVE_APP_FORM_PARENT_IDS"])["room_lighting"]
        parent = self.open_form(parent_id)
        create = self.control(parent, kind="create_child")
        created = self.submit(parent_id, parent["page"]["name"], control=create)
        child_id = str(created.get("created_app_id", ""))
        if child_id.isdigit():
            self.addCleanup(self.remove_native_child, child_id, parent_id, create["value"])
        self.assertIs(created.get("success"), True, created)
        self.assertTrue(child_id.isdigit(), created)
        form = self.open_form(child_id)
        form = self.submit(child_id, form["page"]["name"], {
            "roomDevsL": [str(self.fixtures["switch"])],
            "origLabel": self.prefix + "_room_lighting",
        })
        self.assertIs(form.get("success"), True, form)
        form = self.submit(child_id, form["page"]["name"],
                           control=self.control(form, page="onMeansPage"))
        form = self.submit(child_id, form["page"]["name"],
                           {"onMeans": ["button is pressed"]})
        self.assertIs(form.get("success"), True, form)
        form = self.submit(child_id, form["page"]["name"], {"buttons": [button_id]})
        self.assertIs(form.get("success"), True, form)
        button_number = next(item["name"] for item in form["fields"]
                             if item["name"].startswith("butNo."))
        button_action = next(item["name"] for item in form["fields"]
                             if item["name"].startswith("butAct."))
        form = self.submit(child_id, form["page"]["name"],
                           {button_number: ["1"], button_action: "pushed"})
        self.assertIs(form.get("success"), True, form)
        form = self.submit(child_id, form["page"]["name"],
                           control=self.control(form, name="_action_previous"))
        installed = self.submit(child_id, form["page"]["name"],
                                control=self.control(form, name="_action_update"))
        self.assertIs(installed.get("success"), True, installed)
        self.assertIs(installed.get("closed"), True)
        settings = self.settings(child_id)
        self.assertEqual(set(settings["roomDevsL"]["deviceList"]),
                         {str(self.fixtures["switch"])})
        self.assertEqual(set(settings["buttons"]["deviceList"]), {button_id})
        subscriptions = self.status(child_id)["eventSubscriptions"]
        self.assertTrue(any(str(item.get("typeId")) == button_id
                            and item.get("name", "").startswith("pushed")
                            for item in subscriptions), subscriptions)
        self.command("switch", "off")
        self.command("button", "push", [1], remember=False)
        self.wait_for(lambda: self.attribute("switch", "switch") == "on")
        form = self.open_form(child_id)
        form = self.submit(child_id, form["page"]["name"],
                           control=self.control(form, name="turnOff"))
        self.assertIs(form.get("success"), True, form)
        self.wait_for(lambda: self.attribute("switch", "switch") == "off")

        form = self.submit(child_id, form["page"]["name"],
                           control=self.control(form, page="onMeansPage"))
        form = self.submit(child_id, form["page"]["name"], {button_number: ["2"]})
        self.assertIs(form.get("success"), True, form)
        form = self.submit(child_id, form["page"]["name"],
                           control=self.control(form, name="_action_previous"))
        installed = self.submit(child_id, form["page"]["name"],
                                control=self.control(form, name="_action_update"))
        self.assertIs(installed.get("success"), True, installed)
        self.assertEqual(self.settings(child_id)["roomDevsL"], settings["roomDevsL"])
        self.command("button", "push", [2], remember=False)
        self.wait_for(lambda: self.attribute("switch", "switch") == "on")
        self.assertEqual(str(self.status(child_id)["installedApp"]["id"]), child_id)
        form = self.open_form(child_id)
        self.assertEqual(form["page"]["name"], "mainPage")

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

    def test_time_rule_schedules_stop_edit_and_restart_in_place(self):
        rule_id = self.create_rule([
            documents.change("trigger", capability="Certain Time (and optional date)", fields=[
                documents.field("time#", "enum", "A specific time"),
                documents.field("atTime#", "time", "2026-01-01T23:59:00.000+0000"),
            ]),
            documents.log_action("Scheduled fixture"),
        ])
        jobs = self.status(rule_id)["scheduledJobs"]
        self.assertTrue(jobs)
        self.assertTrue(any(job.get("status") == "PENDING" and job.get("nextRunTime")
                            for job in jobs))
        form = self.open_form(rule_id)
        stopped = self.submit(rule_id, form["page"]["name"],
                              control=self.control(form, name="stopRule"))
        self.assertIs(stopped.get("success"), True, stopped)
        self.assertIs(self.document(rule_id)["nativeHealth"]["stopped"], True)
        self.assertFalse(self.status(rule_id)["scheduledJobs"])
        self.assertFalse(self.status(rule_id)["eventSubscriptions"])
        edited = self.update(rule_id, [documents.log_action("Added while stopped")])
        self.assertIs(edited["stopped"], True)
        self.assertEqual(str(edited["rule_id"]), rule_id)
        self.assertFalse(self.status(rule_id)["scheduledJobs"])
        form = self.open_form(rule_id)
        started = self.submit(rule_id, form["page"]["name"],
                              control=self.control(form, name="stopRule"))
        self.assertIs(started.get("success"), True, started)
        self.assertIs(self.document(rule_id)["nativeHealth"]["stopped"], False)
        self.assertTrue(self.status(rule_id)["scheduledJobs"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
