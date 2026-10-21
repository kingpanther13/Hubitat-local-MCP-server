"""Regular native fixtures must remain synthetic when installed or adopted."""

import hashlib
from pathlib import Path
from types import SimpleNamespace

import e2e_native_rule_integration as integration
import e2e_test as et
import pytest

TONE_NAME = "BAT_E2E_KEEP Virtual Tone"
TONE_SOURCE = (Path(__file__).parent / "fixtures" / "native-tone.groovy").read_text(encoding="utf-8")


class FixtureHub:
    def __init__(self, existing=False):
        self.driver = {"id": "41", "name": TONE_NAME, "bucket": "user", "namespace": "mcptest"}
        self.installed = existing
        self.source = TONE_SOURCE
        self.calls = []
        self.devices = {}
        self.runner = et.TestRunner(SimpleNamespace(
            call_tool=self.call_tool,
            endpoint="https://cloud.hubitat.com/api/00000000-0000-0000-0000-000000000000/apps/7/mcp"))
        self.runner._native_rule_context = {"client": integration.CloudMcpClient(self.runner), "custom_drivers": {}}
        self.fixtures = integration.RunnerFixtures(self.runner)

    def call_tool(self, name, args):
        if "tool" in args:
            name, args = args["tool"], args["args"]
        self.calls.append((name, args.copy()))
        if name == "hub_list_drivers":
            assert args["include"] == "all"
            if args.get("nameFilter"):
                assert args["nameFilter"] == TONE_NAME
                if not args.get("cursor"):
                    return {"source": "hub_api", "drivers": [], "nextCursor": "page2"}
                assert args["cursor"] == "page2"
                return {"source": "hub_api", "drivers": [self.driver] if self.installed else []}
            return {"source": "hub_api", "drivers": [
                {"id": "1", "name": "Virtual Switch", "bucket": "virtual"},
                {"id": "2", "name": "Virtual Omni Sensor", "bucket": "virtual"},
                {"id": "3", "name": "Virtual Player", "bucket": "virtual"},
            ]}
        if name == "hub_create_driver":
            assert not self.installed
            assert args == {"source": TONE_SOURCE, "confirm": True}
            self.installed = True
            return {"success": True, "driverId": "41"}
        if name == "hub_get_source":
            assert args == {"type": "driver", "id": "41"}
            return {"success": True, "source": self.source, "hasMore": False}
        if name == "hub_get_device":
            assert args["mode"] == "details"
            assert args["sections"] == ["identity", "attributes", "commands", "relationships", "integrations", "metadata"]
            device = self.devices[args["deviceId"]]
            identity = {key: device[key] for key in ("id", "label", "virtual", "network") if key in device}
            connections = {key: device[key] for key in ("zigbee", "ZWave", "matter", "bluetooth", "linkedDevice") if key in device}
            return {"id": args["deviceId"], "sections": {
                "identity": identity, "attributes": {}, "commands": [], "relationships": {"device": {}},
                "integrations": {"device": connections}, "metadata": {"deviceTypeId": device["deviceTypeId"]}}}
        if name == "hub_list_devices":
            assert args == {"scope": "all", "labelFilter": "E2E_PERM_"}
            return {"devices": list(self.devices.values())}
        if name == "hub_create_device":
            device_id = str(9 + len(self.devices))
            self.devices[device_id] = {"id": device_id, "label": args["label"],
                                       "deviceTypeId": args["deviceTypeId"],
                                       "virtual": args["deviceTypeId"] != "41"}
            return {"success": True, "deviceId": device_id, "label": args["label"]}
        raise AssertionError(f"Unexpected tool {name}")

    def native(self, path):
        pytest.fail(f"Fixture validation bypassed public MCP metadata: {path}")

    def existing_tone(self, **changes):
        self.devices["9"] = {"id": "9", "label": "E2E_PERM_Native_tone", "deviceTypeId": "41",
                             "virtual": False, **changes}


def test_fresh_tone_installs_exact_source_and_preserves_other_driver_recipes():
    hub = FixtureHub()
    assert hub.fixtures["tone"] == "9"
    assert hub.runner._native_rule_context["custom_drivers"]["tone"] == {
        "id": "41", "sha256": hashlib.sha256(TONE_SOURCE.encode()).hexdigest()}
    assert hub.runner._driver_type_ids[TONE_NAME] == "41"
    assert hub.fixtures["omni"] == "10"
    assert hub.devices["10"]["deviceTypeId"] == "2"
    assert len([call for call in hub.calls if call[0] == "hub_create_driver"]) == 1
    assert {args["deviceId"] for name, args in hub.calls if name == "hub_get_device"} == {"9", "10"}


def test_existing_exact_tone_driver_and_device_are_reused_after_paged_lookup():
    hub = FixtureHub(existing=True)
    hub.existing_tone()
    assert hub.fixtures["tone"] == "9"
    assert not [call for call in hub.calls if call[0].startswith("hub_create_")]
    assert [args.get("cursor") for name, args in hub.calls
            if name == "hub_list_drivers" and args.get("nameFilter")] == ["", "page2"]
    assert hub.fixtures.metadata("tone")["device"]["id"] == "9"


def test_player_fixture_uses_the_builtin_driver_catalog_name():
    hub = FixtureHub()
    assert hub.fixtures["player"] == "9"
    assert hub.devices["9"]["deviceTypeId"] == "3"
    assert hub.fixtures.metadata("player")["device"]["virtual"] is True


def test_existing_tone_name_with_different_source_is_not_adopted_or_overwritten():
    hub = FixtureHub(existing=True)
    hub.source += "\ndef unrelated() {}\n"
    with pytest.raises(AssertionError, match="source"):
        hub.fixtures["tone"]
    assert not [call for call in hub.calls if call[0].startswith("hub_create_")]
    assert TONE_NAME not in hub.runner._driver_type_ids
    assert "native_tone" not in hub.runner._perm_fixture_ids


@pytest.mark.parametrize("page", [
    {"source": "unavailable", "drivers": []},
    {"source": "hub_api_raw", "drivers": []},
    {"source": "hub_api", "drivers": None},
])
def test_failed_catalog_is_not_treated_as_permission_to_create(page):
    hub = FixtureHub()
    original = hub.runner.client.call_tool

    def call_tool(name, args):
        if args.get("tool") == "hub_list_drivers":
            return page
        return original(name, args)

    hub.runner.client.call_tool = call_tool
    with pytest.raises(AssertionError, match="catalog"):
        hub.fixtures["tone"]
    assert not hub.calls


@pytest.mark.parametrize("changes", [
    {"deviceTypeId": "42"}, {"deviceTypeId": "42", "virtual": True},
    {"label": "Other tone"}, {"id": "10"},
    *({flag: True} for flag in ("zigbee", "ZWave", "matter", "network", "bluetooth", "linkedDevice")),
])
def test_tone_device_with_wrong_identity_driver_or_connection_is_refused(changes):
    hub = FixtureHub(existing=True)
    hub.existing_tone(**changes)
    # Exercise validation even when the runner previously resolved this exact fixture key.
    hub.runner._perm_fixture_ids["native_tone"] = "9"
    with pytest.raises(AssertionError):
        hub.fixtures["tone"]
    assert "tone" not in hub.fixtures
    assert "native_tone" not in hub.runner._perm_fixture_ids


def test_tone_source_is_reverified_before_later_fixture_use():
    hub = FixtureHub(existing=True)
    hub.existing_tone()
    assert hub.fixtures["tone"] == "9"
    hub.source += "\ndef changed() {}\n"
    with pytest.raises(AssertionError, match="source"):
        hub.fixtures.metadata("tone")


def test_other_nonvirtual_fixtures_get_no_synthetic_driver_exception():
    hub = FixtureHub()
    hub.devices["9"] = {"id": "9", "label": "E2E_PERM_Native_omni", "virtual": False,
                         "deviceTypeId": "41"}
    with pytest.raises(AssertionError, match="virtual"):
        hub.fixtures["omni"]
    assert "omni" not in hub.fixtures
