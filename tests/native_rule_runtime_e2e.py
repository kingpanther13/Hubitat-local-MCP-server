"""Opt-in native Rule Machine runtime acceptance on labeled virtual fixtures.

Uses the same environment as native_rule_document_e2e.py. NATIVE_RULE_FIXTURES
must include distinct switch, switch2, and temperature IDs. This suite runs
actions and sends on/off/setTemperature commands to those verified fixtures.
Existing fixture attribute values must be initialized; cleanup restores them.
Run explicitly: python tests/native_rule_runtime_e2e.py

These are assertions of required behavior, including invalid-enum detection.
Unsupported recipes or failing invariants fail the suite; they are not xfails.
Scheduling, event delivery, and the remaining action families are not covered.
"""

import json
import os
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

from native_rule_document_e2e import HubClient


def field(name, type_name, value):
    return {"name": name, "type": type_name, "value": value}


def change(part, **value):
    return {"part": part, "value": value}


def variable_action(target, operation, fields):
    return change("action", type="modeActs", subtype="getSetVariable", fields=[
        field("xVarV.#", "enum", target),
        field("numOp.#", "enum", operation),
        *fields,
    ])


class NativeRuleRuntimeE2E(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("NATIVE_RULE_E2E_ENABLE") != "1":
            raise unittest.SkipTest("Live suite requires NATIVE_RULE_E2E_ENABLE=1")
        required = (
            "HUB_URL", "NATIVE_RULE_E2E_ALLOWED_HUB_URL", "MCP_APP_ID",
            "MCP_ACCESS_TOKEN", "NATIVE_RULE_PARENT_ID", "NATIVE_RULE_FIXTURES",
            "NATIVE_RULE_FIXTURE_LABEL_PREFIX",
        )
        missing = [key for key in required if not os.environ.get(key)]
        if missing:
            raise AssertionError("Missing environment: " + ", ".join(missing))
        base = os.environ["HUB_URL"]
        if base != os.environ["NATIVE_RULE_E2E_ALLOWED_HUB_URL"]:
            raise AssertionError("HUB_URL is not the explicitly allowed test hub")
        parsed = urllib.parse.urlsplit(base)
        if (parsed.scheme not in ("http", "https") or not parsed.netloc
                or parsed.username or parsed.password or parsed.query
                or parsed.fragment or parsed.path not in ("", "/")):
            raise AssertionError("HUB_URL must contain only a scheme and authority")
        cls.client = HubClient(
            base.rstrip("/"), os.environ["MCP_APP_ID"], os.environ["MCP_ACCESS_TOKEN"],
        )
        cls.parent_id = os.environ["NATIVE_RULE_PARENT_ID"]
        cls.fixtures = json.loads(os.environ["NATIVE_RULE_FIXTURES"])
        for name in ("switch", "switch2", "temperature"):
            if name not in cls.fixtures or not str(cls.fixtures[name]).isdigit():
                raise AssertionError(f"NATIVE_RULE_FIXTURES needs a numeric {name} ID")
        if len({str(cls.fixtures[key]) for key in ("switch", "switch2", "temperature")}) != 3:
            raise AssertionError("Runtime fixtures must be three distinct devices")
        cls.prefix = "NativeRuntimeE2E_" + uuid.uuid4().hex[:10]
        cls.started = time.monotonic()
        print(f"\nFixture rule prefix: {cls.prefix}", flush=True)
        for key in ("switch", "switch2", "temperature"):
            metadata = cls.fixture_metadata(key)
            attribute = "temperature" if key == "temperature" else "switch"
            current = metadata["device"]["currentStates"].get(attribute)
            if not current or current.get("value") is None:
                raise AssertionError(f"Initialize {key}.{attribute} before running runtime tests")
            if attribute == "switch" and current["value"] not in ("on", "off"):
                raise AssertionError(f"Fixture {key} has an unknown switch state")
            if attribute == "temperature":
                float(current["value"])

    @classmethod
    def fixture_metadata(cls, key):
        device_id = str(cls.fixtures[key])
        metadata = cls.client.native(f"/device/fullJson/{device_id}")
        device = metadata["device"]
        if str(device["id"]) != device_id or device.get("virtual") is not True:
            raise AssertionError(f"Fixture {key} is not the expected virtual device")
        if not str(device.get("label", "")).startswith(
                os.environ["NATIVE_RULE_FIXTURE_LABEL_PREFIX"]):
            raise AssertionError(f"Fixture {key} lacks the required test label")
        return metadata

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.2f}s "
              f"calls={dict(cls.client.calls)}", flush=True)
        print("COVERAGE GAPS: scheduling, event-trigger delivery, waits at runtime, "
              "and exhaustive action families", flush=True)

    def setUp(self):
        self.started_test = time.monotonic()
        self.before_calls = self.client.calls.copy()
        self.original_values = {}
        self.addCleanup(self.report_timing)
        self.addCleanup(self.restore_fixtures)

    def report_timing(self):
        print(f"\n{self._testMethodName}: {time.monotonic() - self.started_test:.2f}s "
              f"calls={dict(self.client.calls - self.before_calls)}", flush=True)

    def attribute(self, key, attribute):
        return self.fixture_metadata(key)["device"]["currentStates"][attribute]["value"]

    def remember_fixture(self, key):
        if key not in self.original_values:
            attribute = "temperature" if key == "temperature" else "switch"
            self.original_values[key] = self.attribute(key, attribute)

    def command(self, key, method, values=(), remember=True):
        if key not in ("switch", "switch2", "temperature", "button"):
            raise AssertionError("Command target is not a declared runtime fixture")
        allowed = {"temperature": ("setTemperature",), "button": ("push",)}.get(key, ("on", "off"))
        if method not in allowed:
            raise AssertionError("Command is outside the runtime fixture allowlist")
        if key == "button" and remember:
            raise AssertionError("Momentary button events cannot be restored")
        metadata = self.fixture_metadata(key)
        commands = metadata.get("commands", metadata["device"].get("commands"))
        self.assertIsInstance(commands, list, "Native device command schema is missing")
        definitions = [item for item in commands if item.get("name") == method]
        self.assertEqual(len(definitions), 1, f"Native command schema missing/ambiguous: {method}")
        parameters = definitions[0].get("parameters") or []
        self.assertEqual(len(parameters), len(values))
        args = [{"type": parameter["type"], "value": value}
                for parameter, value in zip(parameters, values, strict=True)]
        if remember:
            self.remember_fixture(key)
        headers = {"Content-Type": "application/json"}
        if os.environ.get("HUB_UI_COOKIE"):
            headers["Cookie"] = os.environ["HUB_UI_COOKIE"]
        request = urllib.request.Request(
            self.client.base + "/device/runmethod",
            data=json.dumps({
                "id": int(self.fixtures[key]), "method": method, "args": args,
            }).encode(),
            headers=headers, method="POST",
        )
        self.client.calls["native_http"] += 1
        self.client.calls["fixture_commands"] += 1
        try:
            with self.client.opener.open(request, timeout=30) as response:
                response.read()
        except urllib.error.HTTPError as exc:
            raise AssertionError(f"Fixture command HTTP {exc.code}; not retried") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise AssertionError("Fixture command transport failed; not retried") from None

    def restore_fixtures(self):
        failures = []
        for key, value in self.original_values.items():
            try:
                if key == "temperature":
                    self.command(key, "setTemperature", [float(value)], remember=False)
                    self.wait_for(lambda key=key, value=value: float(self.attribute(key, "temperature")) == float(value))
                else:
                    self.command(key, value, remember=False)
                    self.wait_for(lambda key=key, value=value: self.attribute(key, "switch") == value)
            except Exception as exc:
                failures.append(f"{key}: {type(exc).__name__}: {exc}")
        self.assertEqual(failures, [], "Fixture restoration failed")

    def wait_for(self, predicate, timeout=12):
        deadline = time.monotonic() + timeout
        while True:
            if predicate():
                return
            if time.monotonic() >= deadline:
                self.fail("Native runtime outcome did not arrive before the deadline")
            time.sleep(0.25)

    def status(self, rule_id, missing_ok=False):
        try:
            return self.client.native(f"/installedapp/statusJson/{rule_id}")
        except RuntimeError as exc:
            if missing_ok and getattr(exc, "status", None) == 404:
                return {}
            raise

    def state(self, rule_id):
        return {row["name"]: row["value"] for row in self.status(rule_id)["appState"]}

    def settings(self, rule_id):
        rows = self.client.native(f"/installedapp/settings/{rule_id}")["settings"]
        return {row["name"]: row for row in rows}

    def document(self, rule_id):
        result = self.client.tool("hub_get_rule_document", rule_id=rule_id)
        self.assertIsNot(result.get("success"), False, result)
        self.assertEqual(str(result["rule_id"]), rule_id)
        return result

    def create_rule(self, changes):
        name = self.prefix + "_" + self._testMethodName
        result = self.client.tool(
            "hub_create_rule_document", parent_id=self.parent_id,
            name=name, changes=changes, confirm=True,
        )
        if result.get("rule_id"):
            self.addCleanup(self.remove_rule, str(result["rule_id"]), name)
        self.assertTrue(result.get("success"), result)
        rule_id = str(result["rule_id"])
        native = self.status(rule_id)["installedApp"]
        self.assertEqual(native.get("label") or native.get("name"), name)
        self.assertEqual(str(native["id"]), rule_id)
        return rule_id

    def remove_rule(self, rule_id, expected_name):
        native = self.status(rule_id, missing_ok=True).get("installedApp")
        if not native:
            return
        self.assertEqual(str(native["id"]), rule_id)
        self.assertEqual(self.settings(rule_id)["origLabel"]["value"], expected_name)
        self.assertTrue(expected_name.startswith(self.prefix + "_"))
        self.client.native("/installedapp/update/json", "POST", form={
            "id": rule_id, "_action_remove": "Remove",
        })
        self.assertNotIn("installedApp", self.status(rule_id, missing_ok=True))

    def update(self, rule_id, changes):
        result = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, changes=changes, confirm=True,
        )
        self.assertTrue(result.get("success"), result)
        self.assertEqual(str(result["rule_id"]), rule_id)

    def run_actions(self, rule_id):
        document = self.document(rule_id)
        self.assertEqual(document["validation"]["structuralIssues"], [])
        self.assertTrue(document["nativeHealth"]["available"])
        self.assertFalse(document["nativeHealth"]["broken"])
        self.assertFalse(document["nativeHealth"]["paused"])
        self.assertFalse(document["nativeHealth"]["stopped"])
        response = self.client.native("/installedapp/btn", "POST", form={
            "id": rule_id, "name": "runAction",
            "settings[runAction]": "clicked", "runAction.type": "button",
        })
        self.assertEqual(response.get("status"), "success")
        self.client.calls["native_run_actions"] += 1

    def assert_variable(self, rule_id, name, expected, type_name="integer"):
        self.wait_for(lambda: self.state(rule_id)["lv_" + name]["value"] == expected)
        native = self.state(rule_id)["lv_" + name]
        public = self.document(rule_id)["variables"][name]
        self.assertEqual(native["type"], type_name)
        self.assertEqual(public["type"], type_name)
        self.assertEqual(native["value"], expected)
        self.assertEqual(public["value"], expected)

    def switch_action(self, keys, on, **extra):
        return change("action", type="switchActs", subtype="getOnOffSwitch", fields=[
            field("onOffSwitch.#", "capability.switch", [str(self.fixtures[k]) for k in keys]),
            field("onOff.#", "bool", on),
        ], **extra)

    def test_device_choices_reject_wrong_capability_before_persistence(self):
        choices = self.client.native("/device/listJson?capability=capability.switch")
        allowed = {str(row["id"]) for row in choices}
        self.assertIn(str(self.fixtures["switch"]), allowed)
        self.assertNotIn(str(self.fixtures["temperature"]), allowed)
        rule_id = self.create_rule([self.switch_action(["switch"], True)])
        before = self.document(rule_id)
        action_id = before["actions"][0]["id"]
        picker = f"onOffSwitch.{action_id}"
        saved_picker = self.settings(rule_id)[picker]
        result = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, confirm=True, changes=[
                change("action", id=action_id, type="switchActs",
                       subtype="getOnOffSwitch", fields=[
                           field("onOffSwitch.#", "capability.switch",
                                 [str(self.fixtures["temperature"])]),
                           field("onOff.#", "bool", True),
                       ]),
            ],
        )
        self.assertIs(result.get("success"), False, result)
        self.assertEqual(self.settings(rule_id)[picker], saved_picker)
        self.assertEqual(self.document(rule_id)["actions"], before["actions"])

        self.update(rule_id, [self.switch_action(["switch2"], False, id=action_id)])
        self.command("switch2", "on")
        self.run_actions(rule_id)
        self.wait_for(lambda: self.attribute("switch2", "switch") == "off")
        self.assertEqual(
            set(self.settings(rule_id)[picker]["deviceList"]),
            {str(self.fixtures["switch2"])},
        )

    def test_numeric_local_copy_then_add_constant_runs_to_ten(self):
        rule_id = self.create_rule([
            change("variable", operation="create", name="Source", type="integer", value=7),
            change("variable", operation="create", name="Result", type="integer", value=0),
            variable_action("Result", "variable", [
                field("xVar3.#", "enum", "Source"), field("valOffset.#", "number", 0),
            ]),
            variable_action("Result", "add number", [field("valNumber.#", "number", 3)]),
        ])
        self.run_actions(rule_id)
        self.assert_variable(rule_id, "Result", 10)
        self.assert_variable(rule_id, "Source", 7)
        self.update(rule_id, [
            change("variable", operation="set", name="Source", value=11),
            change("variable", operation="set", name="Result", value=-100),
        ])
        self.run_actions(rule_id)
        self.assert_variable(rule_id, "Result", 14)

    def test_set_numeric_variable_from_current_device_value(self):
        self.command("temperature", "setTemperature", [23.5])
        self.wait_for(lambda: float(self.attribute("temperature", "temperature")) == 23.5)
        rule_id = self.create_rule([
            change("variable", operation="create", name="Reading", type="bigdecimal", value=0),
            variable_action("Reading", "sensor value", [
                field("valCapab.#", "enum", "Temperature"),
                field("valDev.#", "capability.temperatureMeasurement",
                      str(self.fixtures["temperature"])),
                field("valOffset.#", "decimal", 0),
            ]),
        ])
        action_id = str(self.document(rule_id)["actions"][0]["id"])
        sensor_setting = self.settings(rule_id)[f"valDev.{action_id}"]
        self.assertIs(sensor_setting["multiple"], False)
        self.assertEqual(set(sensor_setting["deviceList"]), {str(self.fixtures["temperature"])})
        self.run_actions(rule_id)
        self.assert_variable(rule_id, "Reading", 23.5, "bigdecimal")
        self.command("temperature", "setTemperature", [31.25])
        self.wait_for(lambda: float(self.attribute("temperature", "temperature")) == 31.25)
        self.run_actions(rule_id)
        self.assert_variable(rule_id, "Reading", 31.25, "bigdecimal")

    def test_if_else_runs_the_correct_virtual_switch_branch(self):
        rule_id = self.create_rule([
            change("condition", capability="Switch", fields=[
                field("rDev_#", "capability.switch", [str(self.fixtures["switch"])]),
                field("state_#", "enum", "on"),
            ]),
        ])
        condition_id = next(iter(self.document(rule_id)["conditions"]))
        self.update(rule_id, [
            change("action", type="condActs", subtype="getIfThen",
                   fields=[], expression=[condition_id]),
            self.switch_action(["switch2"], True),
            change("terminator", subtype="else"),
            self.switch_action(["switch2"], False),
            change("terminator", subtype="endIf"),
        ])
        for input_state, initial_output, expected in (("off", "on", "off"), ("on", "off", "on")):
            with self.subTest(input=input_state):
                self.command("switch", input_state)
                self.command("switch2", initial_output)
                self.wait_for(lambda input_state=input_state: self.attribute("switch", "switch") == input_state)
                self.wait_for(lambda initial_output=initial_output: self.attribute("switch2", "switch") == initial_output)
                self.run_actions(rule_id)
                self.wait_for(lambda expected=expected: self.attribute("switch2", "switch") == expected)

    def test_two_device_action_persists_and_controls_both_devices(self):
        for key in ("switch", "switch2"):
            self.command(key, "off")
            self.wait_for(lambda key=key: self.attribute(key, "switch") == "off")
        rule_id = self.create_rule([self.switch_action(["switch", "switch2"], True)])
        action_id = str(self.document(rule_id)["actions"][0]["id"])
        expected_ids = {str(self.fixtures["switch"]), str(self.fixtures["switch2"])}
        self.assertEqual(set(self.settings(rule_id)[f"onOffSwitch.{action_id}"]["deviceList"]),
                         expected_ids)
        self.run_actions(rule_id)
        self.wait_for(lambda: all(self.attribute(k, "switch") == "on"
                                  for k in ("switch", "switch2")))
        self.update(rule_id, [self.switch_action(["switch", "switch2"], False, id=action_id)])
        self.assertEqual(str(self.document(rule_id)["actions"][0]["id"]), action_id)
        self.assertEqual(set(self.settings(rule_id)[f"onOffSwitch.{action_id}"]["deviceList"]),
                         expected_ids)
        self.run_actions(rule_id)
        self.wait_for(lambda: all(self.attribute(k, "switch") == "off"
                                  for k in ("switch", "switch2")))

    def config_snapshot(self, rule_id):
        state = self.state(rule_id)
        keys = ("actions", "actionList", "capabsfalse", "capabstrue", "eval",
                "ndx", "actNdx", "ruleNdx", "predCapabs", "hasPredicate")
        config = {key: state.get(key) for key in keys}
        config.update({key: value for key, value in state.items() if key.startswith("lv_")})
        return {"state": config, "settings": self.settings(rule_id)}

    def test_invalid_native_enums_are_reported_without_running_actions(self):
        invalid_changes = [
            change("condition", capability="Switch", fields=[
                field("rDev_#", "capability.switch", [str(self.fixtures["switch"])]),
                field("state_#", "enum", "__invalid_switch_state__"),
            ]),
            variable_action("Result", "__invalid_numeric_operation__", []),
        ]
        for invalid in invalid_changes:
            with self.subTest(part=invalid["part"]):
                rule_id = self.create_rule([
                    change("variable", operation="create", name="Result", type="integer", value=0),
                ])
                before = self.config_snapshot(rule_id)
                status_before = self.status(rule_id)
                response = self.client.tool(
                    "hub_update_rule_document", rule_id=rule_id,
                    changes=[invalid], confirm=True,
                )
                self.assertIs(response.get("success"), False, response)
                self.assertIsInstance(response.get("partial"), bool, response)
                field_prefix = "state_" if invalid["part"] == "condition" else "numOp."
                self.assertIn(field_prefix, json.dumps(response))
                after = self.config_snapshot(rule_id)
                for name, setting in after["settings"].items():
                    if name.startswith(field_prefix):
                        self.assertNotIn("__invalid_", str(setting.get("value")))
                if response["partial"]:
                    # Native field discovery may stage settings; it must not finish or run the action.
                    self.assertEqual(after["state"]["actionList"], before["state"]["actionList"])
                    self.assertEqual(after["state"]["lv_Result"], before["state"]["lv_Result"])
                    status_after = self.status(rule_id)
                    for key in ("eventSubscriptions", "scheduledJobs"):
                        self.assertEqual(status_after[key], status_before[key])
                else:
                    self.assertEqual(after, before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
