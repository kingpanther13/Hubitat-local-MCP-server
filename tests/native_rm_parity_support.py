"""Shared fixtures for opt-in RM goal parity; all authoring uses public tools."""

import hashlib
import json
import os
import time
import urllib.parse
import urllib.request
import uuid

import native_rule_document_e2e as documents

change = documents.change
field = documents.field
log_action = documents.log_action


documents.TOOLS.update({
    "hub_get_tool_guide", "hub_get_rule_health", "hub_list_rules",
    "hub_get_rule", "hub_call_rule", "hub_clone_rule",
    "hub_set_rule_paused", "hub_set_rule_enabled", "hub_set_rule_stopped",
    "hub_update_rule_name", "hub_delete_rule", "hub_search_tools",
    "hub_create_variable", "hub_delete_variable", "hub_get_variable",
    "hub_set_variable", "hub_manage_virtual_device",
    "hub_manage_rules", "hub_read_rules",
    "hub_set_rule_private_boolean", "hub_clone_native_app",
    "hub_export_native_app", "hub_import_native_app", "hub_delete_native_app",
    "hub_set_app_disabled", "hub_get_info",
})


class ParityCase(documents.unittest.TestCase):
    status = documents.NativeRuleDocumentE2E.status
    state = documents.NativeRuleDocumentE2E.state
    report_timing = documents.NativeRuleDocumentE2E.report_timing

    @classmethod
    def setUpClass(cls):
        if os.environ.get("NATIVE_RULE_E2E_ENABLE") != "1":
            raise documents.unittest.SkipTest("Explicit live authorization required")
        base = os.environ["HUB_URL"]
        if base != os.environ["NATIVE_RULE_E2E_ALLOWED_HUB_URL"]:
            raise AssertionError("Hub URL differs from authorized target")
        parsed = urllib.parse.urlsplit(base)
        if (parsed.scheme not in ("http", "https") or not parsed.netloc
                or parsed.username or parsed.password or parsed.query
                or parsed.fragment or parsed.path not in ("", "/")):
            raise AssertionError("Hub URL must contain only scheme and authority")
        cls.client = documents.HubClient(base.rstrip("/"), os.environ["MCP_APP_ID"],
                                         os.environ["MCP_ACCESS_TOKEN"])
        cls.parent_id = os.environ["NATIVE_RULE_PARENT_ID"]
        cls.mode_id = os.environ["NATIVE_RULE_MODE_ID"]
        cls.fixtures = json.loads(os.environ["NATIVE_RULE_FIXTURES"])
        cls.custom_drivers = json.loads(os.environ.get("NATIVE_RULE_FIXTURE_DRIVERS", "{}"))
        cls.prefix = "NativeDocumentE2E_" + uuid.uuid4().hex[:10]
        cls.started = time.monotonic()
        for key in cls.fixtures:
            cls.fixture_metadata(key)
        print(f"\nFixture rule prefix: {cls.prefix}", flush=True)

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.3f}s "
              f"calls={dict(cls.client.calls)}", flush=True)

    def setUp(self):
        self.started_test = time.monotonic()
        self.before_calls = self.client.calls.copy()
        self.original_values = {}
        self.owned_devices = {}
        self.created_sequence = 0
        self.addCleanup(self.report_timing)
        self.addCleanup(self.restore_fixtures)

    def call(self, tool, **kwargs):
        return self.client.tool(tool, **kwargs)

    def doc(self, rule_id):
        result = self.call("hub_get_rule_document", rule_id=int(rule_id))
        self.assertIsNot(result.get("success"), False, result)
        self.assertEqual(str(result["rule_id"]), str(rule_id))
        return result

    document = doc

    def settings(self, rule_id):
        rows = self.client.native(f"/installedapp/settings/{rule_id}")["settings"]
        return {row["name"]: dict(row, device_ids=[
            str(value) for value in (row.get("deviceList") or [])
        ]) for row in rows}

    def healthy(self, rule):
        doc = rule if isinstance(rule, dict) else self.doc(rule)
        self.assertEqual(doc["validation"]["structuralIssues"], [])
        self.assertTrue(doc["nativeHealth"]["available"])
        self.assertFalse(doc["nativeHealth"]["broken"])
        return doc

    def create(self, changes, name=None):
        self.created_sequence += 1
        name = self.prefix + "_" + (name or
            f"{self._testMethodName}_{self.created_sequence}")
        result = self.call("hub_create_rule_document", parent_id=int(self.parent_id),
                           name=name, changes=changes, confirm=True)
        if result.get("rule_id"):
            self.adopt(result["rule_id"], name)
        self.assertTrue(result.get("success"), result)
        return int(result["rule_id"])

    def adopt(self, rule_id, name):
        self.assertTrue(name.startswith(self.prefix + "_"), name)
        self.addCleanup(self.remove_rule, int(rule_id), name)
        return int(rule_id)

    def remove_rule(self, rule_id, name):
        installed = self.status(rule_id, missing_ok=True).get("installedApp")
        if not installed:
            return
        self.assertEqual(str(installed["id"]), str(rule_id))
        self.assertTrue(name.startswith(self.prefix + "_"))
        self.assertEqual(self.settings(rule_id)["origLabel"]["value"], name)
        parent = installed.get("parentAppId", installed.get("parentId"))
        if parent is not None:
            self.assertEqual(str(parent), str(self.parent_id))
        self.client.native("/installedapp/update/json", "POST", form={
            "id": rule_id, "_action_remove": "Remove",
        })
        self.assertNotIn("installedApp", self.status(rule_id, missing_ok=True))

    def update(self, rule_id, changes):
        return self.call("hub_update_rule_document", rule_id=int(rule_id),
                         changes=changes, confirm=True)

    def snapshot(self, rule_id):
        state = self.state(rule_id)
        keys = ("actions", "actionList", "capabsfalse", "capabstrue", "eval",
                "ndx", "actNdx", "ruleNdx", "predCapabs", "hasPredicate")
        selected = {key: state.get(key) for key in keys}
        selected.update({key: value for key, value in state.items()
                         if key.startswith("lv_")})
        return {"state": selected, "settings": self.settings(rule_id)}

    @classmethod
    def fixture_metadata(cls, key):
        device_id = str(cls.fixtures[key])
        if not device_id.isdigit():
            raise AssertionError("Fixture ID must be numeric")
        metadata = cls.client.native(f"/device/fullJson/{device_id}")
        device = metadata["device"]
        if str(device["id"]) != device_id or not str(device.get("label", "")).startswith(
                os.environ["NATIVE_RULE_FIXTURE_LABEL_PREFIX"]):
            raise AssertionError("Fixture identity or label differs")
        if device.get("virtual") is not True:
            # User drivers are marked nonvirtual even when they only emit synthetic events.
            # Such fixtures require explicit source-hash authorization, not a name exception.
            permitted = cls.custom_drivers.get(key)
            if not permitted or str(device.get("deviceTypeId")) != str(permitted["id"]):
                raise AssertionError("Fixture is neither virtual nor a declared synthetic driver")
            if any(device.get(flag) for flag in ("zigbee", "ZWave", "matter", "network", "bluetooth", "linkedDevice")):
                raise AssertionError("Synthetic fixture has a physical or network connection")
            source = cls.client.native(f"/driver/ajax/code?id={permitted['id']}")["source"]
            if hashlib.sha256(source.encode()).hexdigest() != permitted["sha256"]:
                raise AssertionError("Synthetic fixture driver source differs from authorization")
        return metadata

    def attribute(self, key, name):
        return self.fixture_metadata(key)["device"]["currentStates"][name]["value"]

    fixture_attribute = attribute

    def command(self, key, method, values=(), remember=True):
        metadata = self.fixture_metadata(key)
        commands = metadata.get("commands", metadata["device"].get("commands"))
        matches = [row for row in commands if row.get("name") == method]
        self.assertEqual(len(matches), 1, f"Native fixture command unavailable: {method}")
        params = matches[0].get("parameters") or []
        self.assertEqual(len(params), len(values))
        restore = {
            "on": ("switch", None), "off": ("switch", None),
            "setTemperature": ("temperature", "setTemperature"),
            "setLevel": ("level", "setLevel"),
            "active": ("motion", None), "inactive": ("motion", None),
            "open": ("contact", None), "close": ("contact", None),
        }
        self.assertIn(method, set(restore) | {"push", "doubleTap", "hold", "release"})
        if remember and method in restore:
            attribute, setter = restore[method]
            identity = (key, attribute)
            if identity not in self.original_values:
                self.original_values[identity] = (self.attribute(key, attribute), setter)
        headers = {"Content-Type": "application/json"}
        if os.environ.get("HUB_UI_COOKIE"):
            headers["Cookie"] = os.environ["HUB_UI_COOKIE"]
        request = urllib.request.Request(self.client.base + "/device/runmethod",
            data=json.dumps({"id": int(self.fixtures[key]), "method": method,
                             "args": [{"type": p["type"], "value": v}
                                      for p, v in zip(params, values, strict=True)]}).encode(),
            headers=headers, method="POST")
        self.client.calls["native_http"] += 1
        self.client.calls["fixture_commands"] += 1
        with self.client.opener.open(request, timeout=30) as response:
            response.read()

    def restore_fixtures(self):
        for (key, attribute), (value, setter) in self.original_values.items():
            method = setter or ("close" if value == "closed" else value)
            self.command(key, method, [float(value)] if setter else (), remember=False)
            self.wait_for(lambda key=key, attribute=attribute, value=value, setter=setter:
                          str(self.attribute(key, attribute)) == str(value)
                          or (setter and float(self.attribute(key, attribute)) == float(value)))

    def wait_for(self, predicate, timeout=5):
        deadline = time.monotonic() + timeout
        while not predicate():
            if time.monotonic() >= deadline:
                self.fail("Expected native state did not arrive before deadline")
            time.sleep(0.1)

    def run_actions(self, rule_id):
        doc = self.healthy(rule_id)
        self.assertFalse(doc["nativeHealth"]["paused"])
        self.assertFalse(doc["nativeHealth"]["stopped"])
        result = self.client.native("/installedapp/btn", "POST", form={
            "id": rule_id, "name": "runAction",
            "settings[runAction]": "clicked", "runAction.type": "button",
        })
        self.assertEqual(result.get("status"), "success")

    def page_text(self, rule_id):
        return json.dumps(self.client.native(f"/installedapp/configure/json/{rule_id}"))

    def create_hub_variable(self, name, type_name, value):
        actual = self.prefix + "_" + name
        result = self.call("hub_create_variable", name=actual, type=type_name,
                           value=value, confirm=True)
        self.assertTrue(result.get("success"), result)
        self.addCleanup(self.delete_hub_variable, actual)
        return actual

    def delete_hub_variable(self, name):
        self.assertTrue(name.startswith(self.prefix + "_"))
        result = self.call("hub_delete_variable", name=name, confirm=True, force=True)
        self.assertTrue(result.get("success"), result)

    def disposable_switch(self, name):
        label = self.prefix + "_" + name
        dni = label
        result = self.call("hub_manage_virtual_device", action="create",
                           deviceType="Virtual Switch", deviceLabel=label,
                           deviceNetworkId=dni, confirm=True)
        self.assertTrue(result.get("success"), result)
        device_id = result.get("deviceId") or result.get("device", {}).get("id")
        self.assertIsNotNone(device_id, result)
        self.owned_devices[dni] = str(device_id)
        self.addCleanup(self.delete_device, dni)
        return int(device_id), dni

    def delete_device(self, dni):
        if dni not in self.owned_devices:
            return
        self.assertTrue(dni.startswith(self.prefix + "_"))
        result = self.call("hub_manage_virtual_device", action="delete",
                           deviceNetworkId=dni, confirm=True)
        self.assertTrue(result.get("success"), result)
        del self.owned_devices[dni]
