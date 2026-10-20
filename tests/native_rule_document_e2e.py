"""Opt-in live acceptance tests for the native Rule Machine document API.

Required environment: NATIVE_RULE_E2E_ENABLE=1, HUB_URL,
NATIVE_RULE_E2E_ALLOWED_HUB_URL (exact HUB_URL), MCP_APP_ID, MCP_ACCESS_TOKEN,
NATIVE_RULE_PARENT_ID, NATIVE_RULE_FIXTURES (JSON object containing switch,
motion and temperature device IDs), NATIVE_RULE_FIXTURE_LABEL_PREFIX, and
NATIVE_RULE_MODE_ID. Every supplied device must be a labeled virtual fixture.
Optional HUB_UI_COOKIE authenticates native UI requests.

Run explicitly with: python tests/native_rule_document_e2e.py
Creates disposable rules and removes only rules created by this run. Never
dispatches device commands. Existing fixture devices and modes are retained.
Coverage is document authoring and native persistence, not runtime delivery,
generic native apps, backup/restore, or complete action/capability parity.
"""

import collections
import json
import os
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid


TOOLS = {
    "hub_get_rule_document",
    "hub_create_rule_document",
    "hub_update_rule_document",
    "hub_call_app_form",
}
GAPS = (
    "required-expression removal and variable type conversion",
    "datetime local variables and referenced-variable deletion",
    "generic native apps and backup/restore",
    "runtime execution, subscriptions firing, and exhaustive capability parity",
)


def field(name, type_name, value):
    return {"name": name, "type": type_name, "value": value}


def change(part, **value):
    return {"part": part, "value": value}


def log_action(message, **extra):
    return change(
        "action", type="messageActs", subtype="getLogMsg",
        fields=[field("logmsg.#", "textarea", message)], **extra,
    )


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Unexpected redirect; check endpoint or UI authentication")


class HttpFailure(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"HTTP {status}; request was not retried")


class HubClient:
    def __init__(self, base, app_id, token):
        self.base = base
        self.mcp_url = (
            base + "/apps/api/" + urllib.parse.quote(app_id, safe="")
            + "/mcp?" + urllib.parse.urlencode({"access_token": token})
        )
        self.opener = urllib.request.build_opener(NoRedirect())
        self.calls = collections.Counter()
        self.sequence = 0

    def request(self, url, method="GET", payload=None, headers=None, form=None):
        headers = dict(headers or {})
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=180) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise HttpFailure(exc.code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise RuntimeError("Transport failed; request was not retried") from None
        try:
            return json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise RuntimeError("Endpoint returned non-JSON data") from None

    def native(self, path, method="GET", payload=None, form=None):
        self.calls["native_http"] += 1
        headers = {}
        if os.environ.get("HUB_UI_COOKIE"):
            headers["Cookie"] = os.environ["HUB_UI_COOKIE"]
        return self.request(self.base + path, method, payload, headers, form)

    def tool(self, tool_name, **arguments):
        if tool_name not in TOOLS:
            raise ValueError("Tool is outside the native authoring acceptance allowlist")
        self.calls[tool_name] += 1
        params = {"name": tool_name, "arguments": arguments}
        headers = {
            "MCP-Protocol-Version": "2026-07-28",
            "Mcp-Method": "tools/call",
            "Mcp-Name": tool_name,
        }
        for round_number in range(21):
            self.sequence += 1
            self.calls["mcp_http"] += 1
            envelope = self.request(self.mcp_url, "POST", {
                "jsonrpc": "2.0", "id": self.sequence,
                "method": "tools/call", "params": params,
            }, headers)
            if "error" in envelope:
                raise AssertionError(f"{tool_name}: JSON-RPC error {envelope['error'].get('code')}")
            result = envelope["result"]
            if result.get("resultType") == "input_required":
                state = result.get("requestState")
                if not isinstance(state, str) or not state:
                    raise AssertionError("Continuation omitted requestState")
                params["requestState"] = state
                time.sleep(min(0.05 * (2 ** round_number), 0.25))
                continue
            decoded = result.get("structuredContent")
            if decoded is None:
                texts = [c["text"] for c in result.get("content", [])
                         if c.get("type") == "text"]
                if len(texts) != 1:
                    raise AssertionError("Expected one structured document-tool result")
                try:
                    decoded = json.loads(texts[0])
                except ValueError:
                    if not result.get("isError"):
                        raise AssertionError("Successful tool returned non-JSON content") from None
                    decoded = {"success": False, "error": texts[0]}
            if not isinstance(decoded, dict):
                raise AssertionError("Expected an object document-tool result")
            if result.get("isError"):
                decoded = dict(decoded, success=False)
            return decoded
        raise AssertionError("Document operation exceeded 20 continuations")


class NativeRuleDocumentE2E(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("NATIVE_RULE_E2E_ENABLE") != "1":
            raise unittest.SkipTest("Live suite requires NATIVE_RULE_E2E_ENABLE=1")
        required = (
            "HUB_URL", "NATIVE_RULE_E2E_ALLOWED_HUB_URL", "MCP_APP_ID",
            "MCP_ACCESS_TOKEN", "NATIVE_RULE_PARENT_ID", "NATIVE_RULE_FIXTURES",
            "NATIVE_RULE_FIXTURE_LABEL_PREFIX", "NATIVE_RULE_MODE_ID",
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
        cls.mode_id = os.environ["NATIVE_RULE_MODE_ID"]
        cls.prefix = "NativeDocumentE2E_" + uuid.uuid4().hex[:10]
        cls.started = time.monotonic()
        print(f"\nFixture rule prefix: {cls.prefix}", flush=True)
        for key in ("switch", "motion", "temperature"):
            if key not in cls.fixtures:
                raise AssertionError(f"NATIVE_RULE_FIXTURES needs {key}")
        for device_id in cls.fixtures.values():
            if not str(device_id).isdigit():
                raise AssertionError("Fixture IDs must be numeric")
            metadata = cls.client.native(f"/device/fullJson/{device_id}")
            device = metadata.get("device", metadata)
            if device.get("virtual") not in (True, "true"):
                raise AssertionError(f"Fixture {device_id} is not explicitly virtual")
            if not str(device.get("label", "")).startswith(
                    os.environ["NATIVE_RULE_FIXTURE_LABEL_PREFIX"]):
                raise AssertionError(f"Fixture {device_id} lacks the required test label")

    @classmethod
    def tearDownClass(cls):
        print(f"\nTOTAL {time.monotonic() - cls.started:.2f}s "
              f"calls={dict(cls.client.calls)}", flush=True)
        print("COVERAGE GAPS: " + "; ".join(GAPS), flush=True)

    def setUp(self):
        self.started_test = time.monotonic()
        self.before_calls = self.client.calls.copy()
        self.addCleanup(self.report_timing)

    def report_timing(self):
        calls = self.client.calls - self.before_calls
        print(f"\n{self.id().split('.')[-1]}: "
              f"{time.monotonic() - self.started_test:.2f}s calls={dict(calls)}",
              flush=True)

    def status(self, rule_id, missing_ok=False):
        try:
            return self.client.native(f"/installedapp/statusJson/{rule_id}")
        except HttpFailure as exc:
            if missing_ok and exc.status == 404:
                return {}
            raise

    def settings(self, rule_id):
        payload = self.client.native(f"/installedapp/settings/{rule_id}")
        rows = payload["settings"]
        self.assertIsInstance(rows, list)
        return {row["name"]: row for row in rows}

    def state(self, rule_id):
        status = self.status(rule_id)
        self.assertIn("installedApp", status)
        return {row["name"]: row["value"] for row in status["appState"]}

    def document(self, rule_id, sections=None):
        args = {"rule_id": str(rule_id)}
        if sections is not None:
            args["sections"] = sections
        result = self.client.tool("hub_get_rule_document", **args)
        self.assertIsNot(result.get("success"), False, result)
        self.assertEqual(str(result["rule_id"]), str(rule_id))
        return result

    def healthy(self, document):
        self.assertEqual(document["validation"]["structuralIssues"], [])
        self.assertTrue(document["nativeHealth"]["available"])
        self.assertFalse(document["nativeHealth"]["broken"])

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
        self.assertEqual(str(native["id"]), rule_id)
        self.assertEqual(native.get("trueLabel") or native.get("label") or native.get("name"), name)
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
        self.assertNotIn(
            "installedApp", self.status(rule_id, missing_ok=True), "Native cleanup failed",
        )

    def update(self, rule_id, changes):
        result = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, changes=changes, confirm=True,
        )
        self.assertTrue(result.get("success"), result)
        self.assertEqual(str(result["rule_id"]), rule_id)
        return result

    def switch_condition(self, state="on", **extra):
        return change("condition", capability="Switch", fields=[
            field("rDev_#", "capability.switch", [str(self.fixtures["switch"])]),
            field("state_#", "enum", state),
        ], **extra)

    def switch_trigger(self):
        return change("trigger", capability="Switch", fields=[
            field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
            field("tstate#", "enum", "*changed*"),
        ])

    def config_snapshot(self, rule_id):
        state = self.state(rule_id)
        keys = ("actions", "actionList", "capabsfalse", "capabstrue", "eval",
                "ndx", "actNdx", "ruleNdx", "predCapabs", "hasPredicate")
        selected = {key: state.get(key) for key in keys}
        selected.update({key: value for key, value in state.items()
                         if key.startswith("lv_")})
        return {"state": selected, "settings": self.settings(rule_id)}

    def test_complex_document_and_existing_identity_edit(self):
        rule_id = self.create_rule([
            self.switch_condition(),
            change("condition", capability="Motion", fields=[
                field("rDev_#", "capability.motionSensor", [str(self.fixtures["motion"])]),
                field("state_#", "enum", "active"),
            ]),
            self.switch_condition("off"),
            self.switch_trigger(),
            change("variable", operation="create", name="Counter", type="integer", value=1),
            log_action("initial"),
        ])
        initial = self.document(rule_id)
        ids = sorted(initial["conditions"], key=int)
        self.assertEqual(len(ids), 3)
        a, b, c = ids
        tokens = ["(", a, "OR", "NOT", b, ")", "AND", c]
        self.update(rule_id, [
            change("required", tokens=[a, "OR", c]),
            change("action", type="condActs", subtype="getIfThen",
                   fields=[], expression=tokens),
            log_action("inside conditional"),
            change("terminator", subtype="endIf"),
        ])
        doc = self.document(rule_id)
        self.healthy(doc)
        native = self.state(rule_id)
        action_ids = [str(row["id"]) for row in doc["actions"]]
        self.assertEqual(action_ids, [str(i) for i in native["actionList"]])
        if_rows = [row for row in doc["actions"]
                   if row["value"].get("method") == "getIfThen"]
        self.assertEqual(len(if_rows), 1)
        expression_id = str(if_rows[0]["value"]["rule"])
        self.assertEqual(native["eval"][expression_id], tokens)
        self.assertEqual(native["eval"]["0"], [a, "OR", c])
        first_id = action_ids[0]
        self.update(rule_id, [
            log_action("edited in place", id=first_id),
            self.switch_condition("off", id=a),
            change("expression", id=expression_id, tokens=[a, "AND", b]),
        ])
        edited = self.document(rule_id)
        self.healthy(edited)
        self.assertEqual([str(row["id"]) for row in edited["actions"]], action_ids)
        self.assertEqual(set(edited["conditions"]), set(ids))
        self.assertEqual(edited["expressions"][expression_id], [a, "AND", b])
        settings = self.settings(rule_id)
        self.assertEqual(settings[f"logmsg.{first_id}"]["value"], "edited in place")
        self.assertEqual(settings[f"state_{a}"]["value"], "off")
        self.assertEqual(str(self.status(rule_id)["installedApp"]["id"]), rule_id)
        before = self.config_snapshot(rule_id)
        refusal = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, confirm=True,
            changes=[{"part": "delete", "value": {"part": "condition", "id": a}}],
        )
        self.assertIs(refusal.get("success"), False)
        self.assertFalse(refusal.get("partial"))
        self.assertEqual(self.config_snapshot(rule_id), before)

    def test_malformed_expressions_do_not_change_native_configuration(self):
        rule_id = self.create_rule([self.switch_condition(), log_action("unchanged")])
        condition_id = next(iter(self.document(rule_id)["conditions"]))
        for tokens in ([condition_id, "AND"], ["(", condition_id],
                       ["AND", condition_id], ["999999999"]):
            with self.subTest(tokens=tokens):
                before = self.config_snapshot(rule_id)
                result = self.client.tool(
                    "hub_update_rule_document", rule_id=rule_id,
                    changes=[change("required", tokens=tokens)], confirm=True,
                )
                self.assertIs(result.get("success"), False, result)
                self.assertFalse(result.get("partial"), result)
                self.assertEqual(self.config_snapshot(rule_id), before)

    def test_wait_events_use_their_native_ids_at_an_offset_action(self):
        rule_id = self.create_rule([log_action("one"), log_action("two")])
        before = self.document(rule_id)
        self.update(rule_id, [change(
            "action", type="delayActs", subtype="getWaitEvents", fields=[], events=[
                {"capability": "Switch", "fields": [
                    field("tDev#", "capability.switch", [str(self.fixtures["switch"])]),
                    field("tstate#", "enum", "on"), field("stays#", "bool", True),
                    field("SHours#", "number", 0), field("SMins#", "number", 0),
                    field("SSecs#", "number", 5),
                ]},
                {"capability": "Mode", "fields": [
                    field("modesX#", "enum", [self.mode_id]),
                ]},
            ],
        )])
        doc = self.document(rule_id)
        self.healthy(doc)
        self.assertEqual(len(doc["actions"]), len(before["actions"]) + 1)
        wait = doc["actions"][-1]
        self.assertEqual(wait["value"]["method"], "getWaitEvents")
        self.assertNotEqual(str(wait["id"]), "1")
        settings = self.settings(rule_id)
        switch_rows = [row for name, row in settings.items()
                       if name.startswith("tDev-")]
        self.assertEqual(len(switch_rows), 1)
        event_id = switch_rows[0]["name"].removeprefix("tDev")
        self.assertEqual(set(switch_rows[0]["deviceList"]), {str(self.fixtures["switch"])})
        self.assertIn(settings[f"stays{event_id}"]["value"], (True, "true"))
        self.assertEqual(int(settings[f"SSecs{event_id}"]["value"]), 5)
        modes = [row for name, row in settings.items() if name.startswith("modesX-")]
        self.assertEqual(len(modes), 1)
        self.assertNotEqual(modes[0]["name"].removeprefix("modesX"), event_id)
        mode_value = modes[0]["value"]
        if isinstance(mode_value, str):
            try:
                mode_value = json.loads(mode_value)
            except ValueError:
                pass
        actual_modes = mode_value if isinstance(mode_value, list) else [mode_value]
        self.assertEqual([str(mode) for mode in actual_modes], [self.mode_id])
        native = self.state(rule_id)
        self.assertIn(str(wait["id"]), native["actions"])
        self.assertEqual(native["actions"][str(wait["id"])]["method"], "getWaitEvents")
        self.assertEqual([int(native[name + event_id]) for name in ("SHours", "SMins", "SSecs")],
                         [0, 0, 5])
        self.assertEqual([int(part) for part in native["hhmmss" + event_id].split(":")],
                         [0, 0, 5])
        event_ids = {
            str(-int(event_id)),
            str(-int(modes[0]["name"].removeprefix("modesX"))),
        }
        self.assertEqual(
            {str(i) for i in native["waitNdx"]["." + str(wait["id"])]}, event_ids,
        )

    def test_local_variable_values_types_and_delete_roundtrip(self):
        rule_id = self.create_rule([log_action("variables")])
        for name, type_name, value, updated in (
            ("Count", "integer", 4, 7),
            ("Ratio", "bigdecimal", 1.25, 2.5),
            ("Flag", "boolean", False, True),
            ("Text", "string", "first", "second"),
        ):
            with self.subTest(type=type_name):
                self.update(rule_id, [change(
                    "variable", operation="create", name=name, type=type_name, value=value,
                )])
                self.assert_variable(rule_id, name, type_name, value)
                self.update(rule_id, [change(
                    "variable", operation="set", name=name, value=updated,
                )])
                self.assert_variable(rule_id, name, type_name, updated)
        self.update(rule_id, [change("variable", operation="delete", name="Text")])
        self.assertNotIn("Text", self.document(rule_id, ["variables"])["variables"])
        self.assertNotIn("lv_Text", self.state(rule_id))

    def assert_variable(self, rule_id, name, type_name, value):
        public = self.document(rule_id, ["variables"])["variables"][name]
        native = self.state(rule_id)["lv_" + name]
        self.assertEqual(public["type"], type_name)
        self.assertEqual(native["type"], type_name)
        self.assertEqual(public["value"], value)
        self.assertEqual(native["value"], value)

    def test_independently_broken_trigger_is_not_reported_healthy(self):
        rule_id = self.create_rule([self.switch_trigger(), log_action("broken fixture")])
        doc = self.document(rule_id)
        trigger_id = next(iter(doc["triggers"]))
        setting_name = "tDev" + str(trigger_id)
        self.assertTrue(self.settings(rule_id)[setting_name]["deviceList"])
        self.client.native(f"/installedapp/settings/{rule_id}", "POST", payload=[
            {"name": setting_name, "type": "capability.switch",
             "multiple": True, "deviceList": ""},
        ])
        self.assertFalse(self.settings(rule_id)[setting_name]["deviceList"])
        page = self.client.native(f"/installedapp/configure/json/{rule_id}/selectTriggers")
        self.assertIn("Broken", json.dumps(page), "Independent fixture corruption was not visible")
        self.client.native(f"/installedapp/configure/json/{rule_id}/mainPage")
        doc = self.document(rule_id)
        unhealthy = (doc["nativeHealth"].get("broken")
                     or doc["validation"]["structuralIssues"])
        self.assertTrue(unhealthy, "Broken trigger was returned as a healthy document")

    def test_action_delete_and_exact_order(self):
        rule_id = self.create_rule([
            log_action("first"), log_action("second"), log_action("third"),
        ])
        ids = [str(row["id"]) for row in self.document(rule_id)["actions"]]
        self.assertEqual(len(ids), 3)
        reordered = [ids[2], ids[0], ids[1]]
        self.update(rule_id, [change("order", ids=reordered)])
        self.assertEqual(
            [str(row["id"]) for row in self.document(rule_id)["actions"]], reordered,
        )
        self.assertEqual([str(i) for i in self.state(rule_id)["actionList"]], reordered)
        self.update(rule_id, [{"part": "delete", "value": {"part": "action", "id": ids[0]}}])
        expected = [ids[2], ids[1]]
        doc = self.document(rule_id)
        self.healthy(doc)
        self.assertEqual([str(row["id"]) for row in doc["actions"]], expected)
        native = self.state(rule_id)
        self.assertEqual([str(i) for i in native["actionList"]], expected)
        self.assertNotIn(ids[0], native["actions"])
        settings = self.settings(rule_id)
        self.assertNotIn("logmsg." + ids[0], settings)
        self.assertEqual(settings["logmsg." + ids[2]]["value"], "third")
        before = self.config_snapshot(rule_id)
        result = self.client.tool(
            "hub_update_rule_document", rule_id=rule_id, confirm=True,
            changes=[change("order", ids=[ids[2], ids[2]])],
        )
        self.assertIs(result.get("success"), False)
        self.assertFalse(result.get("partial"))
        self.assertEqual(self.config_snapshot(rule_id), before)

    def test_condition_and_trigger_deletion_preserves_unrelated_rows(self):
        rule_id = self.create_rule([
            self.switch_condition(), self.switch_condition("off"),
            self.switch_trigger(), log_action("retain"),
        ])
        doc = self.document(rule_id)
        conditions = sorted(doc["conditions"], key=int)
        trigger_id = next(iter(doc["triggers"]))
        self.update(rule_id, [
            {"part": "delete", "value": {"part": "condition", "id": conditions[0]}},
            {"part": "delete", "value": {"part": "trigger", "id": trigger_id}},
        ])
        doc = self.document(rule_id)
        self.healthy(doc)
        self.assertEqual(set(doc["conditions"]), {conditions[1]})
        self.assertEqual(doc["triggers"], {})
        self.assertEqual(len(doc["actions"]), 1)
        settings = self.settings(rule_id)
        self.assertNotIn("rDev_" + conditions[0], settings)
        self.assertNotIn("tDev" + str(trigger_id), settings)
        self.assertIn("rDev_" + conditions[1], settings)

    @unittest.skip("Coverage gap: generic native app authoring is not implemented")
    def test_generic_native_app_configuration(self):
        """Rule Machine document acceptance does not prove generic app parity."""


if __name__ == "__main__":
    unittest.main(verbosity=2)
