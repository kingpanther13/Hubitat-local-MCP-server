"""Exercise the app-code E2E assertions and fixture cleanup without a hub."""

from copy import deepcopy

import pytest

pytest.importorskip("requests")

import e2e_test as et


class AppCodeClient:
    def __init__(self, fault=None):
        self.fault = fault
        self.calls = []
        self.apps = {}
        self.backups = {}
        self.created = []
        self.deleted = []
        self.counts = {}

    def call_tool(self, gateway, arguments):
        tool, args = arguments["tool"], arguments["args"]
        self.calls.append((tool, deepcopy(args)))
        self.counts[tool] = self.counts.get(tool, 0) + 1
        if tool == "hub_create_app":
            app_id = str(len(self.created) + 1)
            self.apps[app_id] = {"success": True, "version": 1, "source": args["source"]}
            self.created.append(app_id)
            result = {"appId": app_id}
        elif tool == "hub_get_source":
            result = deepcopy(self.apps[str(args["id"])])
        elif tool == "hub_update_app":
            app_id = str(args["appId"])
            app = self.apps[app_id]
            if "oauth" in args:
                result = {"oauth": {"success": True, "enabled": True, "clientId": "fixture"}}
            elif "expectedVersion" in args:
                result = {"success": False, "conflict": True}
            elif "ClassThatDoesNotExistBatE2e" in args["source"]:
                result = {"success": False, "error": "unable to resolve class ClassThatDoesNotExistBatE2e"}
            else:
                self.backups.setdefault(f"app_{app_id}", app["source"])
                result = {"success": True, "previousVersion": app["version"]}
                app.update(source=args["source"], version=app["version"] + 1)
        elif tool == "hub_restore_backup":
            key = args["backupKey"]
            app_id = key.split("_")[-1]
            app = self.apps[app_id]
            undo = f"prerestore_app_{app_id}"
            if key == undo:
                undo = f"redo_app_{app_id}"
            self.backups.setdefault(undo, app["source"])
            app.update(source=self.backups[key], version=app["version"] + 1)
            result = {"success": True, "undoAvailable": True, "preRestoreBackup": undo}
        elif tool == "hub_get_backup":
            result = {"source": self.backups[args["backupKey"]]}
        elif tool == "hub_delete_item":
            app_id = str(args["item_id"])
            self.deleted.append(app_id)
            del self.apps[app_id]
            result = {"success": True}
        else:
            raise AssertionError(f"unexpected tool {tool}")
        if self.fault:
            self.fault(self, tool, args, result)
        return result


def runner_for(client):
    runner = object.__new__(et.TestRunner)
    runner.client = client
    return runner


@pytest.mark.parametrize("method,restore_count,update_count", [
    ("test_update_app_code_lifecycle", 0, 4),
    ("test_app_code_backup_restore_lifecycle", 4, 1),
])
def test_app_lifecycle_scenarios_are_independent_and_clean_up(method, restore_count, update_count):
    assert ("app_code_update", method, method) in et.TEST_REGISTRY
    client = AppCodeClient()
    runner = runner_for(client)
    getattr(runner, method)()
    assert client.counts.get("hub_restore_backup", 0) == restore_count
    assert client.counts["hub_update_app"] == update_count
    assert client.created == client.deleted == ["1"]
    assert client.apps == {}


@pytest.mark.parametrize("restore_leg", [1, 2, 3, 4])
def test_lost_restore_response_propagates_without_replay_and_deletes_fixture(restore_leg):
    def fault(client, tool, args, result):
        if tool == "hub_restore_backup" and client.counts[tool] == restore_leg:
            raise et.RelayLostResponseError("504 response lost after commit")

    client = AppCodeClient(fault)
    with pytest.raises(et.RelayLostResponseError):
        runner_for(client).test_app_code_backup_restore_lifecycle()
    assert client.counts["hub_restore_backup"] == restore_leg
    assert client.created == client.deleted == ["1"]
    assert client.apps == {}


@pytest.mark.parametrize("defect", ["undo_unverified", "wrong_restored_source", "retry_overwrites_undo", "redo_reuses_selected_key"])
def test_restore_scenario_keeps_response_and_exact_source_assertions(defect):
    def fault(client, tool, args, result):
        if defect == "undo_unverified" and tool == "hub_restore_backup":
            result["undoAvailable"] = False
        elif defect == "wrong_restored_source" and tool == "hub_restore_backup":
            client.apps["1"]["source"] += "\n// wrong snapshot"
        elif defect == "retry_overwrites_undo" and tool == "hub_restore_backup" and client.counts[tool] == 2:
            client.backups["prerestore_app_1"] = client.apps["1"]["source"]
        elif defect == "redo_reuses_selected_key" and tool == "hub_restore_backup" and client.counts[tool] == 3:
            result["preRestoreBackup"] = args["backupKey"]

    client = AppCodeClient(fault)
    with pytest.raises(AssertionError):
        runner_for(client).test_app_code_backup_restore_lifecycle()
    assert client.created == client.deleted == ["1"]
    assert client.apps == {}


def test_lost_conflict_response_propagates_and_cleans_up_without_replay():
    def fault(client, tool, args, result):
        if tool == "hub_update_app" and "expectedVersion" in args:
            raise et.RelayLostResponseError("504 conflict response lost")

    client = AppCodeClient(fault)
    with pytest.raises(et.RelayLostResponseError):
        runner_for(client).test_update_app_code_lifecycle()
    assert client.counts["hub_update_app"] == 3
    assert client.created == client.deleted == ["1"]
    assert client.apps == {}


@pytest.mark.parametrize("defect", ["compiler_accepted", "conflict_mutated_source", "oauth_refused"])
def test_update_scenario_keeps_refusal_readback_and_oauth_assertions(defect):
    def fault(client, tool, args, result):
        if tool != "hub_update_app":
            return
        if defect == "compiler_accepted" and "ClassThatDoesNotExistBatE2e" in args.get("source", ""):
            result["success"] = True
        elif defect == "conflict_mutated_source" and "expectedVersion" in args:
            client.apps["1"]["source"] = args["source"]
        elif defect == "oauth_refused" and "oauth" in args:
            result["oauth"]["success"] = False

    client = AppCodeClient(fault)
    with pytest.raises(AssertionError):
        runner_for(client).test_update_app_code_lifecycle()
    assert client.created == client.deleted == ["1"]
    assert client.apps == {}


@pytest.mark.parametrize("persistent", [False, True])
def test_restore_scenario_uses_only_one_fresh_fixture_rerun(persistent):
    def fault(client, tool, args, result):
        if tool == "hub_restore_backup" and client.counts[tool] in ((2, 4) if persistent else (2,)):
            raise et.RelayLostResponseError("504 restore retry response lost")

    client = AppCodeClient(fault)
    runner = runner_for(client)
    runner.results = []
    runner._soft_passes = []
    settles = []
    runner._settle_before_504_retry = settles.append
    name = "test_app_code_backup_restore_lifecycle"
    runner._run_one("app_code_update", name, name)
    assert [result["status"] for result in runner.results] == ["fail" if persistent else "pass"]
    assert settles == [name]
    assert client.created == client.deleted == ["1", "2"]
    assert client.apps == {}
    sources = [args["source"] for tool, args in client.calls if tool == "hub_create_app"]
    assert sources[0] != sources[1]
    assert client.counts["hub_restore_backup"] == (4 if persistent else 6)
