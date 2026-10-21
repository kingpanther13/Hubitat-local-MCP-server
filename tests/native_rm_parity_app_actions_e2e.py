"""App-backed RM actions authored through native document inputs.

Only disposable, uniquely named native apps and labeled virtual devices are used.
The shared ParityCase authorization and rule cleanup apply unchanged.
"""

import html
import json
import os
import re
import unittest
import urllib.parse

import requests

import native_rule_document_e2e as documents
from native_rm_parity_support import ParityCase, change, field

documents.TOOLS.update({"hub_list_apps", "hub_list_devices", "hub_set_native_app", "hub_get_app_config", "hub_list_modes"})


class NativeRmParityAppActions(ParityCase):
    def setUp(self):
        super().setUp()
        self.owned_apps = []
        self.addCleanup(self._remove_owned_apps)

    def _remove_owned_apps(self):
        failures = []
        for app in reversed(self.owned_apps):
            try:
                self._remove_app(*app)
            except Exception as exc:
                failures.append(str(exc))
        self.assertFalse(failures, failures)

    def _apps(self):
        result = []

        def visit(node):
            if isinstance(node.get("data"), dict):
                result.append(node["data"])
            for child in node.get("children", []):
                visit(child)
        for node in self.client.native("/hub2/appsList").get("apps", []):
            visit(node)
        return result

    def _type(self, name):
        rows, cursor = [], ""
        while cursor is not None:
            page = self.call("hub_list_apps", scope="types", cursor=cursor)
            self.assertIsNot(page.get("success"), False, page)
            rows.extend(page.get("apps", []))
            cursor = page.get("nextCursor")
        matches = [row for row in rows if row.get("namespace") == "hubitat"
                   and (row.get("name") == name or
                        (name == "Scene" and re.fullmatch(r"Scene-[0-9.]+", row.get("name", ""))))]
        self.assertEqual(len(matches), 1, {"native_app_type": name, "matches": matches})
        return matches[0]

    def _native_install(self, type_name, parent_id=None):
        if parent_id is None:
            type_row = self._type(type_name)
            native_name = type_row["name"]
        else:
            selectors = []

            def visit(node):
                if isinstance(node, dict):
                    if (node.get("element") == "app" and node.get("namespace") == "hubitat"
                            and str(node.get("appId")) == str(parent_id)):
                        selectors.append(node)
                    for value in node.values():
                        visit(value)
                elif isinstance(node, list):
                    for value in node:
                        visit(value)
            visit(self.client.native(f"/installedapp/configure/json/{parent_id}")["configPage"])
            matches = [node for node in selectors if node.get("appName") == type_name or
                       (type_name == "Scene" and re.fullmatch(r"Scene-[0-9.]+", node.get("appName", "")))]
            self.assertEqual(len(matches), 1, {"requested_child": type_name, "selectors": selectors})
            native_name = matches[0]["appName"]
        before = {str(row["id"]) for row in self._apps()}
        path = ("/installedapp/sysAppById/" + str(type_row["id"]) if parent_id is None else
                "/installedapp/createchild/hubitat/" + urllib.parse.quote(native_name, safe="") +
                "/parent/" + str(parent_id))
        headers = {"Cookie": os.environ["HUB_UI_COOKIE"]} if os.environ.get("HUB_UI_COOKIE") else {}
        self.client.calls["native_http"] += 1
        response = requests.get(self.client.base + path, headers=headers, allow_redirects=False, timeout=180)
        self.assertEqual(response.status_code, 302, {"native_type": native_name, "status": response.status_code})
        location = urllib.parse.urlsplit(urllib.parse.urljoin(self.client.base + "/", response.headers.get("Location", "")))
        base = urllib.parse.urlsplit(self.client.base)
        self.assertEqual((location.scheme, location.netloc), (base.scheme, base.netloc))
        self.assertRegex(location.path, r"^/installedapp/configure/[0-9]+$")
        app_id = int(location.path.rsplit("/", 1)[1])
        self.assertNotIn(str(app_id), before)
        self.owned_apps.append((app_id, native_name, parent_id))
        self.client.calls["native_http"] += 1
        opened = requests.get(self.client.base + location.path, headers=headers, allow_redirects=False, timeout=180)
        self.assertEqual(opened.status_code, 200)
        installed = self.status(app_id)["installedApp"]
        self.assertEqual(str(installed["id"]), str(app_id))
        self.assertEqual(installed["appType"]["name"], native_name)
        if parent_id is not None:
            self.assertEqual(str(installed["parentAppId"]), str(parent_id))
        return app_id

    def _remove_app(self, app_id, native_name, parent_id):
        installed = self.status(app_id, missing_ok=True).get("installedApp")
        if not installed:
            return
        self.assertEqual(str(installed["id"]), str(app_id))
        self.assertEqual(installed["appType"]["name"], native_name)
        self.assertEqual(installed["appType"]["namespace"], "hubitat")
        if parent_id is not None:
            self.assertEqual(str(installed["parentAppId"]), str(parent_id))
        self.client.native("/installedapp/update/json", "POST", form={"id": app_id, "_action_remove": "Remove"})
        self.assertNotIn("installedApp", self.status(app_id, missing_ok=True))

    def _parent(self, name):
        rows = [row for row in self._apps() if row.get("type") == name and row.get("hidden") is not True]
        self.assertLessEqual(len(rows), 1, rows)
        return int(rows[0]["id"]) if rows else self._native_install(name)

    def _walk(self, app_id, operation, **values):
        config = self.client.native(f"/installedapp/configure/json/{app_id}")
        page = config["configPage"]["name"]
        result = self.call("hub_set_native_app", appId=int(app_id), confirm=True,
                           walkStep={"page": page, "operation": operation, **values})
        self.assertTrue(result.get("success"), result)
        return result

    def _configure(self, app_id, label, writes):
        for values in writes:
            self._walk(app_id, "write", write=values)
        config = self.client.native(f"/installedapp/configure/json/{app_id}")
        names = set()

        def visit(node):
            if isinstance(node, dict):
                if isinstance(node.get("name"), str):
                    names.add(node["name"])
                for value in node.values():
                    visit(value)
            elif isinstance(node, list):
                for value in node:
                    visit(value)
        visit(config["configPage"])
        label_input = "origLabel" if "origLabel" in names else "name"
        self.assertIn(label_input, names, "Native fixture exposes no label input")
        self._walk(app_id, "write", write={label_input: label})
        self._walk(app_id, "done")
        self.assertEqual(self.settings(app_id)[label_input]["value"], label)

    def _scheduler_input(self, app_id, name, type_, multiple):
        fields = []

        def visit(node):
            if isinstance(node, dict):
                if node.get("name") == name:
                    fields.append(node)
                for value in node.values():
                    visit(value)
            elif isinstance(node, list):
                for value in node:
                    visit(value)
        visit(self.client.native(f"/installedapp/configure/json/{app_id}")["configPage"])
        self.assertTrue(fields, f"Native {name} input is missing")
        self.assertTrue(all(row == fields[0] for row in fields), f"Contradictory native {name} descriptors")
        self.assertEqual(fields[0]["type"], type_)
        self.assertIs(fields[0]["multiple"], multiple)

    def _select_scheduler_mode(self, app_id, mode):
        self._scheduler_input(app_id, "perX", "enum", True)
        # This native selector is consumed into modeTable, so setting readback
        # cannot verify it. Submit the UI form and verify the resulting row.
        result = self.client.native("/installedapp/update/json", "POST", form={
            "id": app_id, "settings[perX]": json.dumps([mode]),
            "perX.type": "enum", "perX.multiple": "true",
        })
        self.assertIsNot(result.get("success"), False, result)
        self.assertNotEqual(result.get("status"), "error", result)
        self.assertFalse(result.get("error"), result)
        self.assertIs(self.state(app_id).get("modeTable", {}).get(mode, {}).get("used"), True)

    def _set_scheduler_heat(self, app_id, mode, value):
        self._scheduler_input(app_id, "tempX", "decimal", False)
        result = self.client.native("/installedapp/update/json", "POST", form={
            "id": app_id, "settings[tempX]": str(value),
            "tempX.type": "decimal", "tempX.multiple": "false",
        })
        self.assertIsNot(result.get("success"), False, result)
        self.assertNotEqual(result.get("status"), "error", result)
        self.assertFalse(result.get("error"), result)
        saved = self.state(app_id).get("modeTable", {}).get(mode, {})
        self.assertIs(saved.get("used"), True)
        self.assertEqual(float(saved["heat"]), value)

    def _add(self, rule_id, category, subtype, inputs, rendered):
        before = {str(row["id"]) for row in self.doc(rule_id)["actions"]}
        result = self.update(rule_id, [change("action", type=category, subtype=subtype,
                                             fields=[field(name + ".#", type_, value) for name, type_, value in inputs])])
        self.assertTrue(result.get("success"), result)
        self.assertFalse(result.get("partial"), result)
        added = [row for row in self.doc(rule_id)["actions"] if str(row["id"]) not in before]
        self.assertEqual(len(added), 1, added)
        row = added[0]
        self.assertEqual(row["value"]["method"], subtype)
        typed = self.settings(rule_id)
        for name, type_, value in inputs:
            saved = typed[name + "." + str(row["id"])]
            self.assertEqual(saved["type"], type_)
            if type_.startswith("capability."):
                self.assertEqual(sorted(map(str, saved["device_ids"])), sorted(map(str, value)))
            elif isinstance(value, bool):
                self.assertEqual(str(saved["value"]).lower(), str(value).lower())
            elif isinstance(value, (int, float)):
                self.assertEqual(float(saved["value"]), value)
            elif isinstance(value, list):
                self.assertEqual(list(map(str, saved["value"])), list(map(str, value)))
            else:
                self.assertEqual(str(saved["value"]), str(value))
        self.healthy(rule_id)
        page = self.call("hub_get_app_config", appId=rule_id, pageName="selectActions")
        self.assertTrue(page.get("success"), page)
        rows = [entry for section in page["page"].get("sections", [])
                for entry in section.get("embeddedActions", [])
                if re.fullmatch(re.escape(str(row["id"])) + r"\.\d+\.\w+", str(entry.get("name", "")))]
        self.assertEqual(len(rows), 1, rows)
        text = " ".join(html.unescape(re.sub(r"<[^>]*>", "", rows[0].get("description", ""))).split())
        for fragment in rendered:
            self.assertIn(fragment.lower(), text.lower(), {"action_id": row["id"], "rendered": text, "missing": fragment})
        return str(row["id"])

    def _refuse(self, rule_id, category, subtype, inputs):
        before = self.snapshot(rule_id)
        result = self.update(rule_id, [change("action", type=category, subtype=subtype,
                                             fields=[field(name + ".#", type_, value) for name, type_, value in inputs])])
        self.assertIs(result.get("success"), False, result)
        self.assertEqual(self.snapshot(rule_id), before)
        self.healthy(rule_id)

    def _two_modes(self):
        modes = self.call("hub_list_modes")["modes"]
        configured = [row for row in modes if str(row["id"]) == str(self.mode_id)]
        others = [row for row in modes if str(row["id"]) != str(self.mode_id)]
        self.assertEqual(len(configured), 1, modes)
        self.assertTrue(others, "App-backed action coverage requires two existing native modes")
        return configured[0], others[0]

    def test_rm_action_room_lights(self):
        rule_id = self.create([])
        if not any(row.get("type") == "Room Lights" for row in self._apps()):
            self._refuse(rule_id, "rulesActs", "getRLTurnOff", [("actOffRL", "enum", ["999999999"])])
        name = self.prefix + "_RoomLights"
        self._parent("Room Lighting")
        created = self.call("hub_set_native_app", appType="room_lighting", name=name, confirm=True)
        self.assertTrue(created.get("success"), created)
        app_id = int(created["appId"])
        parent_id = int(self.status(app_id)["installedApp"]["parentAppId"])
        self.owned_apps.append((app_id, "Room Lights", parent_id))
        self._add(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "X")], ["Activate", name, "Force"])
        self._add(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "X"), ("forceRL", "bool", True)], ["Activate", name, "Conditions"])
        self._add(rule_id, "rulesActs", "getRLTurnOff", [("actOffRL", "enum", [str(app_id)])], ["Turn Off Room Lights", name])
        self.assertIn(name, self.page_text(rule_id))
        self._refuse(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "999999999")])
        self._refuse(rule_id, "rulesActs", "getRLTurnOff", [("actOffRL", "enum", [str(app_id), "999999999"])])

    def test_rm_action_scene(self):
        rule_id = self.create([])
        if not any(str(row.get("type", "")).startswith("Scene-") for row in self._apps()):
            self._refuse(rule_id, "sceneActs", "getActivateScene", [("sceneAct", "enum", ["999999999"])])
        scene = self._native_install("Scene", self._parent("Groups and Scenes"))
        label = self.prefix + "_Scene"
        self._configure(scene, label, [{"switches": [int(self.fixtures["switch"])]}])
        self._walk(scene, "click", click={"name": "captureBtn"})
        devices = self.call("hub_list_devices", scope="all", labelFilter=label).get("devices", [])
        matches = [row for row in devices if row.get("label") == label]
        self.assertEqual(len(matches), 1, matches)
        activator = str(matches[0]["id"])
        self.assertEqual(str(self.client.native("/device/fullJson/" + activator)["device"]["parentAppId"]), str(scene))
        self._add(rule_id, "sceneActs", "getActivateScene", [("sceneAct", "enum", [activator])], ["Activate scenes", label])
        modes = self._two_modes()
        mode_ids = [str(row["id"]) for row in modes]
        self._add(rule_id, "sceneActs", "getActivateScenePerMode",
                  [("sceneModes", "enum", mode_ids),
                   *[("scene" + mode, "enum", [activator]) for mode in mode_ids]],
                  ["Scenes per mode", *[row["name"] for row in modes], label])
        self.assertIn(label, self.page_text(rule_id))
        self._refuse(rule_id, "sceneActs", "getActivateScene", [("sceneAct", "enum", [str(scene)])])

    def test_rm_action_thermostat_apps(self):
        variable = self.create_hub_variable("app_action_number", "Number", 7)
        rule_id = self.create([])
        existing = {row.get("type") for row in self._apps()}
        if "Thermostat Controller 2.0" not in existing:
            self._refuse(rule_id, "lockActs", "getSetThermController", [("thisApp", "enum", "999999999")])
        if not existing.intersection({"Thermostat Scheduler 2.0", "Thermostat Scheduler Legacy"}):
            self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [str(self.fixtures["thermostat"])])])
        controller = self._native_install("Thermostat Controller 2.0", self._parent("Thermostat Controllers"))
        schedulers = self._parent("Thermostat Schedulers")
        scheduler = self._native_install("Thermostat Scheduler 2.0", schedulers)
        mode_scheduler = self._native_install("Thermostat Scheduler 2.0", schedulers)
        thermostat = str(self.fixtures["thermostat"])
        thermostat2 = str(self.fixtures["thermostat2"])
        sensors = [str(self.fixtures[key]) for key in ("temperature", "temperature2")]
        self._configure(controller, self.prefix + "_Controller", [{"therm": [int(thermostat)]}, {"tempSensors": [int(sensors[0])]}])
        self._configure(scheduler, self.prefix + "_Scheduler", [{"therm": [int(thermostat)]}, {"schedTypeL": "Time Periods"}])
        mode = str(self.mode_id)
        self._walk(mode_scheduler, "write", write={"therm": [int(thermostat2)]})
        self._walk(mode_scheduler, "write", write={"schedTypeL": "Hub Modes"})
        self._walk(mode_scheduler, "click", click={"name": "addMode", "stateAttribute": "addMode"})
        self._select_scheduler_mode(mode_scheduler, mode)
        self._walk(mode_scheduler, "click", click={"name": mode, "stateAttribute": "tsH"})
        self._set_scheduler_heat(mode_scheduler, mode, 68)
        self._walk(mode_scheduler, "click", click={"name": "update"})
        self._configure(mode_scheduler, self.prefix + "_ModeScheduler", [])
        thermostat_name = self.fixture_metadata("thermostat")["device"]["label"]
        thermostat2_name = self.fixture_metadata("thermostat2")["device"]["label"]
        sensor_names = [self.fixture_metadata(key)["device"]["label"] for key in ("temperature", "temperature2")]
        on_thermostat = ["Thermostat Scheduler for", thermostat_name]
        self._add(rule_id, "lockActs", "getSetThermController", [("thisApp", "enum", str(controller)), ("tcSensors", "capability.temperatureMeasurement", sensors)], ["Set Sensors for", self.prefix + "_Controller", *sensor_names])
        by_thermostat = self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat]), ("thermSched", "enum", "setEcoMode"), ("ecoMode", "enum", "on")], [*on_thermostat, "EcoMode on"])
        self.assertEqual(str(self.settings(rule_id)["thisApp." + by_thermostat]["value"]), str(scheduler))
        by_scheduler = self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("tMethod", "bool", True), ("thisApp", "enum", str(scheduler)), ("thermSched", "enum", "setEcoOffset"), ("ecoOffset", "decimal", 2.5)], [*on_thermostat, "EcoMode Offset 2.5"])
        self.assertEqual(self.settings(rule_id)["thisTherm." + by_scheduler]["device_ids"], [thermostat])
        self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat]), ("thermSched", "enum", "setEcoOffset"), ("uVar", "bool", True), ("xVar", "enum", variable)], [])
        self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("tMethod", "bool", True), ("thisApp", "enum", str(scheduler)), ("thermSched", "enum", "setHold"), ("setHold", "enum", "off")], [*on_thermostat, "Set Hold off"])
        for operation, extras in (("setSchedDay", [("dayN", "enum", "0")]), ("setThermSched", [])):
            self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat]), ("thermSched", "enum", operation), *extras], [*on_thermostat, "Activate Scheduled Setpoints", *(["Monday"] if operation == "setSchedDay" else [])])
        self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat]), ("thermSched", "enum", "setTimeAway"), ("timeAwayHeat", "decimal", 60), ("uVar3", "bool", True), ("xVar3", "enum", variable)], [*on_thermostat, "Days/Times Away", "Heat 60", "Cool", variable])
        configured_mode, other_mode = self._two_modes()
        mode_name = configured_mode["name"]
        self._add(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat2]), ("thermSched", "enum", "setSched4Mode"), ("modN", "enum", mode)], ["Thermostat Scheduler for", thermostat2_name, "Activate Scheduled Setpoints for", mode_name])
        self._refuse(rule_id, "lockActs", "getSetThermController", [("thisApp", "enum", "999999999"), ("tcSensors", "capability.temperatureMeasurement", sensors)])
        self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("tMethod", "bool", True), ("thisApp", "enum", "999999999"), ("thermSched", "enum", "setHold"), ("setHold", "enum", "off")])
        unconfigured_mode = str(other_mode["id"])
        self.assertNotIn(unconfigured_mode, self.state(mode_scheduler).get("modeTable", {}))
        self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat2]), ("thermSched", "enum", "setSched4Mode"), ("modN", "enum", unconfigured_mode)])
        self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("tMethod", "bool", True), ("thisApp", "enum", str(mode_scheduler)), ("thermSched", "enum", "setTimeAway"), ("timeAwayHeat", "decimal", 60)])


if __name__ == "__main__":
    unittest.main(verbosity=2)
