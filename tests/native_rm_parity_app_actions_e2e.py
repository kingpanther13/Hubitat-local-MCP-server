"""App-backed RM actions authored through native document inputs.

Only disposable, uniquely named native apps and labeled virtual devices are used.
Run this acceptance class through the shared cloud MCP test runner.
"""

import html
import re

import native_rule_document_e2e as documents
from native_rm_cloud_support import CloudCaseMixin
from native_rm_parity_support import ParityCase, change, field

documents.TOOLS.update({"hub_list_apps", "hub_list_devices", "hub_set_native_app", "hub_get_app_config", "hub_list_modes"})


def page_paragraphs(configuration):
    paragraphs = []

    def visit(node):
        if isinstance(node, dict):
            for text in node.get("paragraphs", []):
                if isinstance(text, str):
                    paragraphs.append(" ".join(html.unescape(re.sub(r"<[^>]*>", "", text)).split()))
            for key, value in node.items():
                if key != "paragraphs":
                    visit(value)
        elif isinstance(node, list):
            for value in node:
                visit(value)
    visit(configuration.get("page", {}))
    return paragraphs


def scheduler_mode_rows(configuration, mode_name):
    mode = re.compile(r"(?<![\w])" + re.escape(mode_name) + r"(?![\w])")
    return [text for text in page_paragraphs(configuration) if mode.search(text)]


def native_fixture_identity(row, app_id, native_name, parent_id):
    if (not isinstance(row, dict) or str(row.get("id")) != str(app_id)
            or row.get("type") != native_name):
        raise AssertionError("Created native fixture identity changed")
    if str(row.get("parentId")) != str(parent_id):
        raise AssertionError("Created native fixture parent changed")
    return row


class NativeRmParityAppActions(CloudCaseMixin, ParityCase):
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
        rows, cursor = [], ""
        while cursor is not None:
            result = self.call("hub_list_apps", scope="instances", cursor=cursor)
            self.assertIsNot(result.get("success"), False, result)
            rows.extend(result.get("apps", []))
            cursor = result.get("nextCursor")
        return rows

    def _native_install(self, app_type, native_name, label):
        before = {str(row["id"]) for row in self._apps()}
        created = self.call("hub_set_native_app", appType=app_type, name=label, confirm=True)
        app_id = created.get("appId")
        parent_id = created.get("parentAppId")
        parent_types = {"scene": "Groups and Scenes", "room_lighting": "Room Lighting",
                        "thermostat_controller": "Thermostat Controllers", "thermostat_scheduler": "Thermostat Schedulers"}
        if parent_id is not None and str(parent_id) not in before:
            native_fixture_identity(self.installed_app(parent_id), parent_id, parent_types[app_type], None)
            self.owned_apps.append((int(parent_id), parent_types[app_type], None))
        if app_id is not None:
            self.assertNotIn(str(app_id), before, created)
            self.owned_apps.append((int(app_id), native_name, parent_id))
        self.assertTrue(app_id, created)
        self.assertEqual(created.get("appType"), app_type, created)
        self.assertIsNotNone(parent_id, created)
        # A newly created thermostat app can defer its label until a thermostat is set.
        if created.get("success") is not True:
            self.assertIn(app_type, ("thermostat_controller", "thermostat_scheduler"), created)
            self.assertIs(created.get("labelApplied"), False, created)
            self.assertIn("could not be verified as the app's display label", str(created.get("note", "")))
        native_fixture_identity(self.installed_app(app_id), app_id, native_name, parent_id)
        return int(app_id)

    def _remove_app(self, app_id, native_name, parent_id):
        installed = self.installed_app(app_id, missing_ok=True)
        if installed is None:
            return
        native_fixture_identity(installed, app_id, native_name, parent_id)
        if parent_id is None:
            self.assertFalse(any(str(row.get("parentId")) == str(app_id) for row in self._apps()),
                             "A new parent still has children; refusing cascading cleanup")
        removed = self.call("hub_delete_native_app", appId=app_id, confirm=True, force=parent_id is not None)
        self.assertTrue(removed.get("success"), removed)
        self.assertIsNone(self.installed_app(app_id, missing_ok=True))

    def _walk(self, app_id, operation, **values):
        arguments = {"appId": int(app_id), "confirm": True}
        if operation == "write":
            arguments["settings"] = values["write"]
        elif operation == "click":
            click = values["click"]
            arguments["button"] = click["name"]
            if "stateAttribute" in click:
                arguments["stateAttribute"] = click["stateAttribute"]
        else:
            raise AssertionError("Unsupported fixture edit operation")
        result = self.call("hub_set_native_app", **arguments)
        self.assertTrue(result.get("success"), result)
        return result

    def _configure(self, app_id, label, writes):
        for values in writes:
            self._walk(app_id, "write", write=values)
        config = self.raw_configuration(app_id)
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
        visit(config["page"])
        label_input = "origLabel" if "origLabel" in names else "name"
        self.assertIn(label_input, names, "Native fixture exposes no label input")
        self._walk(app_id, "write", write={label_input: label})
        self.assertEqual(self.raw_configuration(app_id)["settings"][label_input], label)

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
        visit(self.raw_configuration(app_id)["page"])
        self.assertTrue(fields, f"Native {name} input is missing")
        self.assertTrue(all(row == fields[0] for row in fields), f"Contradictory native {name} descriptors")
        self.assertEqual(fields[0]["type"], type_)
        self.assertIs(fields[0].get("multiple", False), multiple)

    def _select_scheduler_mode(self, app_id, mode, mode_name):
        self._scheduler_input(app_id, "perX", "enum", True)
        self._walk(app_id, "write", write={"perX": [mode]})
        self.assertTrue(scheduler_mode_rows(self.raw_configuration(app_id), mode_name),
                        "Selected mode has no independently rendered scheduler row")

    def _set_scheduler_heat(self, app_id, mode, value):
        self._scheduler_input(app_id, "tempX", "decimal", False)
        self._walk(app_id, "write", write={"tempX": value})

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
        app_id = self._native_install("room_lighting", "Room Lights", name)
        forced = self._add(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "X")], ["Activate", name, "Force"])
        self.assertNotIn("forceRL." + forced, self.settings(rule_id))
        self._add(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "X"), ("forceRL", "bool", True)], ["Activate", name, "Conditions"])
        self._add(rule_id, "rulesActs", "getRLTurnOff", [("actOffRL", "enum", [str(app_id)])], ["Turn Off Room Lights", name])
        self.assertIn(name, self.page_text(rule_id))
        self._refuse(rule_id, "rulesActs", "getRLActivate", [("actRL", "enum", str(app_id)), ("modeRL", "enum", "999999999")])
        self._refuse(rule_id, "rulesActs", "getRLTurnOff", [("actOffRL", "enum", [str(app_id), "999999999"])])

    def test_rm_action_scene(self):
        rule_id = self.create([])
        if not any(str(row.get("type", "")).startswith("Scene-") for row in self._apps()):
            self._refuse(rule_id, "sceneActs", "getActivateScene", [("sceneAct", "enum", ["999999999"])])
        label = self.prefix + "_Scene"
        scene = self._native_install("scene", "Scene-1.2", label)
        self._configure(scene, label, [{"switches": [int(self.fixtures["switch"])]}])
        self._walk(scene, "click", click={"name": "captureBtn"})
        devices = self.call("hub_list_devices", scope="all", labelFilter=label).get("devices", [])
        matches = [row for row in devices if row.get("label") == label]
        self.assertEqual(len(matches), 1, matches)
        activator = str(matches[0]["id"])
        activator_metadata = self.client.device_metadata(activator)
        self.assertEqual(str(activator_metadata["device"].get("parentAppId")), str(scene))
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
        controller = self._native_install("thermostat_controller", "Thermostat Controller 2.0", self.prefix + "_Controller")
        scheduler = self._native_install("thermostat_scheduler", "Thermostat Scheduler 2.0", self.prefix + "_Scheduler")
        mode_scheduler = self._native_install("thermostat_scheduler", "Thermostat Scheduler 2.0", self.prefix + "_ModeScheduler")
        thermostat = str(self.fixtures["thermostat"])
        thermostat2 = str(self.fixtures["thermostat2"])
        sensors = [str(self.fixtures[key]) for key in ("temperature", "temperature2")]
        self._configure(controller, self.prefix + "_Controller", [{"therm": [int(thermostat)]}, {"tempSensors": [int(sensors[0])]}])
        self._configure(scheduler, self.prefix + "_Scheduler", [{"therm": [int(thermostat)]}, {"schedTypeL": "Time Periods"}])
        mode = str(self.mode_id)
        self._walk(mode_scheduler, "write", write={"therm": [int(thermostat2)]})
        self._walk(mode_scheduler, "write", write={"schedTypeL": "Hub Modes"})
        self._walk(mode_scheduler, "click", click={"name": "addMode", "stateAttribute": "addMode"})
        configured_mode, other_mode = self._two_modes()
        self._select_scheduler_mode(mode_scheduler, mode, configured_mode["name"])
        self._walk(mode_scheduler, "click", click={"name": mode, "stateAttribute": "tsH"})
        self._set_scheduler_heat(mode_scheduler, mode, 68)
        self._walk(mode_scheduler, "click", click={"name": "update"})
        self._configure(mode_scheduler, self.prefix + "_ModeScheduler", [])
        rows = scheduler_mode_rows(self.raw_configuration(mode_scheduler), configured_mode["name"])
        self.assertTrue(any(re.search(r"(?<![0-9])68(?:\.0+)?(?![0-9])", row) for row in rows), rows)
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
        self.assertFalse(scheduler_mode_rows(self.raw_configuration(mode_scheduler), other_mode["name"]))
        self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("thisTherm", "capability.thermostat", [thermostat2]), ("thermSched", "enum", "setSched4Mode"), ("modN", "enum", unconfigured_mode)])
        self._refuse(rule_id, "lockActs", "getSetThermostatScheduler", [("tMethod", "bool", True), ("thisApp", "enum", str(mode_scheduler)), ("thermSched", "enum", "setTimeAway"), ("timeAwayHeat", "decimal", 60)])
