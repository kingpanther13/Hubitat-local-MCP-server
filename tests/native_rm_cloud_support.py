"""Shared Rule Machine acceptance through the ordinary authenticated MCP client."""

import hashlib
import json
import os
import sys
import uuid
from collections import Counter


class CloudMcpClient:
    def __init__(self, runner):
        self.runner = runner
        self.calls = Counter()
        self.mcp_url = runner.client.endpoint

    def tool(self, tool_name, **arguments):
        self.calls[tool_name] += 1
        try:
            return self.runner.client.call_tool(tool_name, arguments)
        except Exception as exc:
            if not hasattr(exc, "tool_name") or not hasattr(exc, "content_text"):
                raise
            try:
                payload = json.loads(exc.content_text)
            except (ValueError, TypeError):
                payload = {"error": exc.content_text}
            if not isinstance(payload, dict):
                payload = {"error": exc.content_text}
            return dict(payload, success=False)

    def request(self, url, method="GET", payload=None, headers=None, form=None):
        assert url == self.mcp_url and method == "POST" and form is None, "Use MCP tools for shared acceptance"
        response = self.runner.client.raw_request(payload, headers=headers)
        response.raise_for_status()
        result = response.json()
        if isinstance(result, dict) and result.get("error"):
            module = sys.modules[type(self.runner).__module__]
            expectation = module._validation_log_expectation(payload.get("method"), payload.get("params"), result["error"])
            if expectation:
                self.runner.client._expected_validation_logs.append(expectation)
        return result

    def native(self, *_args, **_kwargs):
        raise AssertionError("Native admin HTTP belongs to standalone diagnostics, not shared E2E")

    def device_metadata(self, device_id):
        result = self.tool("hub_get_device", deviceId=str(device_id), mode="details", sections=[
            "identity", "attributes", "commands", "relationships", "integrations", "metadata"])
        assert result.get("success") is not False and str(result.get("id")) == str(device_id), result
        sections = result.get("sections")
        assert isinstance(sections, dict), "Public device details returned no sections"
        identity, attributes = sections["identity"], sections["attributes"]
        device = dict(identity)
        device.update(attributes)
        device.update(sections["metadata"])
        device.update(sections["relationships"].get("device", {}))
        device.update(sections["integrations"].get("device", {}))
        return {"device": device, "commands": sections["commands"], "sections": sections}

    def install_rule_machine_parent(self, _type_id):
        runner = self.runner
        name = "BAT_E2E_RM_ParentBootstrap_" + uuid.uuid4().hex[:10]
        created = self.tool("hub_set_native_app", appType="rule_machine", name=name, confirm=True)
        assert created.get("success") is True and created.get("appId"), created
        child_id = str(created["appId"])
        runner.created_native_app_ids.append(child_id)
        deleted = self.tool("hub_delete_native_app", appId=child_id, confirm=True)
        assert deleted.get("success") is True, deleted
        runner._untrack_native_app(child_id)


def read_cloud_section(call, rule_id, section):
    rows = [] if section in {"actions", "settings"} else {}
    cursor, seen_cursors, seen_rows = "", set(), set()
    offset, total, revision = 0, None, None
    while True:
        result = call("hub_set_rule", operation="read", rule_id=int(rule_id),
                      args={"sections": [section], "cursor": cursor, "limit": 50})
        assert result.get("success") is not False and str(result.get("rule_id")) == str(rule_id), result
        page = result["page"]
        assert page["offset"] == offset and page["limit"] == 50, page
        if total is None:
            total, revision = page["total"], page["revision"]
        assert page["total"] == total and page["revision"] == revision, "Native section changed during readback"
        part = result[section]
        assert isinstance(part, type(rows)), "Native section shape changed"
        identities = (list(map(str, part)) if isinstance(part, dict) else
                      [str(row["name" if section == "settings" else "id"]) for row in part])
        assert len(set(identities)) == len(identities) and not seen_rows.intersection(identities), "Duplicate native rows"
        seen_rows.update(identities)
        if isinstance(rows, dict):
            rows.update(part)
        else:
            rows.extend(part)
        offset += len(part)
        assert offset <= total, "Native page exceeds section total"
        following = result["nextCursor"]
        if following is None:
            assert offset == total, "Native section ended before its total"
            return rows
        assert part and isinstance(following, str) and following and following not in seen_cursors, "Native cursor does not progress"
        seen_cursors.add(following)
        cursor = following


class CloudCaseMixin:
    native_diagnostics = False

    def doc(self, rule_id):
        result = self.call("hub_set_rule", operation="read", rule_id=int(rule_id),
                           args={"sections": ["actions", "conditions", "triggers", "expressions", "variables"]})
        self.assertIsNot(result.get("success"), False, result)
        self.assertEqual(str(result["rule_id"]), str(rule_id))
        return result

    document = doc

    def raw_configuration(self, rule_id):
        result = self.call("hub_get_app_config", appId=str(rule_id), includeSettings=True)
        self.assertTrue(result.get("success"), result)
        self.assertEqual(str(result["app"]["id"]), str(rule_id))
        return result

    def settings(self, rule_id):
        rows = read_cloud_section(self.call, rule_id, "settings")
        return {row["name"]: row for row in rows}

    def compiled_snapshot(self, rule_id):
        doc = self.doc(rule_id)
        # Opening mainPage can consume unfinished native editor state. Snapshot only
        # pure public reads, retaining every compiled entry and complete typed setting.
        return {"document": {key: doc[key] for key in ("actions", "conditions", "triggers", "expressions", "variables")},
                "typed_settings": self.settings(rule_id)}

    snapshot = compiled_snapshot

    def health_status(self, rule_id):
        result = self.call("hub_get_rule_health", appId=str(rule_id))
        self.assertIsNot(result.get("success"), False, result)
        for key in ("eventSubscriptionCount", "scheduledJobCount"):
            self.assertIsInstance(result.get(key), int, result)
        return result

    def installed_app(self, rule_id, missing_ok=False):
        cursor, matches = "", []
        while cursor is not None:
            result = self.call("hub_list_apps", scope="instances", cursor=cursor)
            self.assertIsNot(result.get("success"), False, result)
            matches.extend(row for row in result["apps"] if str(row["id"]) == str(rule_id))
            cursor = result.get("nextCursor")
        self.assertLessEqual(len(matches), 1, matches)
        if not matches and missing_ok:
            return None
        self.assertEqual(len(matches), 1, {"app_id": rule_id, "matches": matches})
        return matches[0]

    def status(self, *_args, **_kwargs):
        raise AssertionError("Use installed_app, health_status or public document assertions in shared E2E")

    def state(self, *_args, **_kwargs):
        raise AssertionError("Raw native app state is a standalone diagnostic; assert public compiled results here")

    def page_text(self, rule_id):
        configuration = self.raw_configuration(rule_id)
        form = self.call("hub_set_rule", operation="form", rule_id=int(rule_id), confirm=True,
                         args={"steps": [{"kind": "open", "page": "mainPage"}]})
        self.assertTrue(form.get("success"), form)
        self.assertIsInstance(form.get("messages"), list, form)
        return json.dumps({"configuration": configuration, "messages": form["messages"],
                           "controls": form.get("controls", [])})

    @classmethod
    def fixture_metadata(cls, key):
        device_id = str(cls.fixtures[key])
        metadata = cls.client.device_metadata(device_id)
        device = metadata["device"]
        assert str(device["id"]) == device_id, "Fixture identity differs"
        prefix = os.environ["NATIVE_RULE_FIXTURE_LABEL_PREFIX"]
        assert str(device.get("label", "")).startswith(prefix), "Fixture label differs"
        if device.get("virtual") is not True and device.get("virtual") != "true":
            permitted = cls.custom_drivers.get(key)
            assert permitted and str(device.get("deviceTypeId")) == str(permitted["id"]), "Synthetic fixture driver differs"
            assert not any(device.get(flag) for flag in ("zigbee", "ZWave", "matter", "network", "bluetooth", "linkedDevice")), "Synthetic fixture has a physical connection"
            source = cls.client.tool("hub_get_source", type="driver", id=str(permitted["id"]))
            assert source.get("success") is True and not source.get("hasMore"), source
            assert hashlib.sha256(source["source"].encode()).hexdigest() == permitted["sha256"], "Synthetic fixture source differs"
        return metadata

    def attribute(self, key, name):
        result = self.call("hub_get_device_attribute", deviceId=str(self.fixtures[key]), attribute=name)
        self.assertIsNot(result.get("success"), False, result)
        self.assertIn("value", result)
        return result["value"]

    fixture_attribute = attribute

    def command(self, key, method, values=(), remember=True):
        restore = {"on": ("switch", None), "off": ("switch", None),
                   "setTemperature": ("temperature", "setTemperature"), "setLevel": ("level", "setLevel"),
                   "active": ("motion", None), "inactive": ("motion", None),
                   "open": ("contact", None), "close": ("contact", None)}
        if remember and method in restore:
            attribute, setter = restore[method]
            if (key, attribute) not in self.original_values:
                self.original_values[key, attribute] = (self.attribute(key, attribute), setter)
        result = self.call("hub_call_device_command", deviceId=str(self.fixtures[key]), command=method,
                           **({"parameters": [str(value) for value in values]} if values else {}))
        self.assertTrue(result.get("success"), result)

    def run_actions(self, rule_id):
        self.healthy(rule_id)
        result = self.call("hub_call_rule", ruleId=rule_id, action="actions")
        self.assertIsNot(result.get("success"), False, result)

    def remove_rule(self, rule_id, name):
        if self.installed_app(rule_id, missing_ok=True) is None:
            return
        self.assertTrue(name.startswith(self.prefix + "_"))
        self.assertEqual(self.raw_configuration(rule_id)["settings"]["origLabel"], name)
        result = self.call("hub_delete_native_app", appId=str(rule_id), confirm=True)
        self.assertTrue(result.get("success"), result)
        self.assertIsNone(self.installed_app(rule_id, missing_ok=True))
