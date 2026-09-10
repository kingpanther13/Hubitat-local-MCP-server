"""Permanent cleanup must observe state even after an accepted no-op command."""

from types import SimpleNamespace

import e2e_test as et
import pytest


@pytest.mark.parametrize("scenario", ["comparator", "multi"])
def test_permanent_cleanup_counts_noop_resets_and_attempts_every_device(scenario):
    resets = []
    body_failed = False

    def call_tool(name, args):
        nonlocal body_failed
        if not body_failed:
            if scenario == "multi" or args.get("deviceId") == "switch_b":
                body_failed = True
                raise RuntimeError("injected body failure")
            if args.get("expectedValues") == ["50"]:
                raise et.McpError("invalid numeric comparator")
            return {"success": True, "timedOut": False, "waitFor": {"converged": True}}
        assert name == "hub_call_device_command"
        resets.append(args)
        # The transport accepted the reset, but the device remains at its test value.
        return {"success": True, "waitFor": {"converged": False, "finalValue": "60" if args["deviceId"] == "dimmer" else "on"}}

    runner = et.TestRunner(SimpleNamespace(call_tool=call_tool))
    runner._ensure_perm_fixture = lambda key: key
    method = runner.test_poll_comparator_and_stable if scenario == "comparator" else runner.test_poll_multi_device
    with pytest.raises(RuntimeError, match="injected body failure"):
        method()

    expected = {"dimmer": ("level", "10"), "switch_b": ("switch", "off")} if scenario == "comparator" else {
        "switch_a": ("switch", "off"), "switch_b": ("switch", "off"),
    }
    assert {row["deviceId"] for row in resets} == set(expected)
    assert len(runner._fixture_reset_failures) == 2
    for row in resets:
        attribute, value = expected[row["deviceId"]]
        assert row.get("waitFor", {}).get("attribute") == attribute
        assert row["waitFor"]["expectedValue"] == value
