"""Exercise the manual deployment client with a transport that never reaches a hub."""

import importlib.util
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def module():
    spec = importlib.util.spec_from_file_location(
        "watchdog_package_deploy", ROOT / ".github/scripts/watchdog_package_deploy.py"
    )
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class Transport:
    def __init__(self, *, lost_start=False, dead_main=False, dead_watchdog=False, phase="awaiting_verification"):
        self.calls = []
        self.lost_start = lost_start
        self.dead_main = dead_main
        self.dead_watchdog = dead_watchdog
        self.phase = phase
        self.started = False

    def probe(self, url):
        self.calls.append((url, "probe"))
        if self.started and ((url == "main" and self.dead_main) or (url == "watchdog" and self.dead_watchdog)):
            raise OSError("endpoint unavailable")

    def call(self, url, name, args):
        self.calls.append((url, name))
        if self.started and self.dead_watchdog:
            raise OSError("watchdog unavailable")
        if name == "hub_list_app_instances":
            return {"apps": [{"id": 38, "parentId": None, "type": "MCP Rule Server", "disabled": False}]}
        if name == "hub_update_package":
            self.started = True
            if self.lost_start:
                raise OSError("relay timeout")
            return {"success": True, "requestId": args["requestId"], "status": "queued"}
        if name == "hub_get_package_deployment":
            return {"success": self.phase != "stopped", "requestId": args["requestId"],
                    "phase": self.phase, "component": "MCP Rule Server", "hold": True}
        if name == "hub_set_package_deployment":
            assert args["endpointVerified"] is True
            return {"success": True, "requestId": args["requestId"], "phase": "complete", "hold": False}
        raise AssertionError(name)


def run(module, transport):
    return module.deploy(transport, "watchdog", "main", {"ref": "a" * 40, "libraries": []},
                         "test-operation", attempts=2, interval=0)


def test_lost_start_response_polls_same_operation_and_never_resubmits(module):
    transport = Transport(lost_start=True)
    assert run(module, transport)["phase"] == "complete"
    assert transport.calls.count(("watchdog", "hub_update_package")) == 1


@pytest.mark.parametrize("kwargs", [{"dead_main": True}, {"dead_watchdog": True}, {"phase": "stopped"}])
def test_either_endpoint_failure_keeps_hold_and_never_mutates_the_survivor(module, kwargs):
    transport = Transport(**kwargs)
    with pytest.raises(RuntimeError):
        run(module, transport)
    assert ("watchdog", "hub_set_package_deployment") not in transport.calls
    assert all(name in {"probe", "hub_list_app_instances", "hub_update_package", "hub_get_package_deployment"}
               for _, name in transport.calls)
    assert all(url == "watchdog" or name == "probe" for url, name in transport.calls)


def test_running_job_timeout_leaves_hold_and_does_not_trigger_cleanup(module):
    transport = Transport(phase="updating_app")
    with pytest.raises(RuntimeError, match="hold"):
        run(module, transport)
    assert transport.calls.count(("watchdog", "hub_update_package")) == 1
    assert ("watchdog", "hub_set_package_deployment") not in transport.calls


def test_progress_from_another_operation_cannot_release_this_job(module):
    transport = Transport()
    original = transport.call

    def call(url, name, args):
        result = original(url, name, args)
        if name == "hub_get_package_deployment":
            result["requestId"] = "different-operation"
        return result

    transport.call = call
    with pytest.raises(RuntimeError):
        run(module, transport)
    assert ("watchdog", "hub_set_package_deployment") not in transport.calls


def test_instance_identity_change_refuses_release(module):
    transport = Transport()
    original = transport.call

    def call(url, name, args):
        result = original(url, name, args)
        if name == "hub_list_app_instances" and transport.started:
            result["apps"][0]["id"] = 99
        return result

    transport.call = call
    with pytest.raises(RuntimeError, match="instance"):
        run(module, transport)
    assert ("watchdog", "hub_set_package_deployment") not in transport.calls
