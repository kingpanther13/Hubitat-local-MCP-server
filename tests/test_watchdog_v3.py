"""Exercise the watchdog v3 e2e client with a transport that never reaches a hub."""

import hashlib
import importlib.util
import io
import json
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PLAN = {"ref": "a" * 40, "libraries": []}


@pytest.fixture
def module(monkeypatch):
    spec = importlib.util.spec_from_file_location("watchdog_v3", ROOT / ".github/scripts/watchdog_v3.py")
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    monkeypatch.setattr(result.time, "sleep", lambda _seconds: None)
    return result


class Hub:
    """A scripted v3: `phases` is consumed one status read at a time; the last entry repeats."""

    def __init__(self, phases=("awaiting_verification",), *, lost_start=False, dead_main=False):
        self.calls = []
        self.phases = list(phases)
        self.lost_start = lost_start
        self.dead_main = dead_main
        self.started = []
        self.held = None
        self.info = {}
        self.start_replies = []
        self.release_replies = []
        self.instances = [{"id": 38, "parentId": None, "type": "MCP Rule Server", "disabled": False}]
        self.versions = [7]
        self.library_versions = [3]

    def probe(self, url):
        self.calls.append((url, "probe"))
        if self.started and url == "main" and self.dead_main:
            raise OSError("endpoint unavailable")

    def call(self, url, name, args):
        self.calls.append((url, name))
        if name == "hub_list_app_instances":
            return {"apps": [dict(item) for item in self.instances]}
        if name == "hub_update_package":
            self.started.append(args["requestId"])
            if self.lost_start:
                raise OSError("relay timeout")
            if self.start_replies:
                return self.start_replies.pop(0)
            return {"success": True, "requestId": args["requestId"], "phase": "queued"}
        if name == "hub_get_package_deployment":
            phase = self.phases.pop(0) if len(self.phases) > 1 else self.phases[0]
            return {"success": phase not in ("stopped", "interrupted"), "requestId": args["requestId"],
                    "phase": phase, "component": "MCP Rule Server", "hold": phase != "complete",
                    "error": "compile rejected" if phase == "stopped" else None}
        if name == "hub_set_package_deployment":
            if args.get("abandon"):
                assert args["writesSettled"] is True
                self.held = None
                return {"success": False, "requestId": args["requestId"], "phase": "abandoned", "hold": False}
            assert args["endpointVerified"] is True
            if self.release_replies:
                return self.release_replies.pop(0)
            return {"success": True, "requestId": args["requestId"], "phase": "complete", "hold": False}
        if name == "hub_get_info":
            return {"watchdogVersion": 3, "packageDeployment": self.held, **self.info}
        if name == "hub_list_apps":
            return {"apps": [{"id": 178, "namespace": "mcp", "name": "MCP Rule Server"},
                             {"id": 179, "namespace": "mcp", "name": "MCP Rule"}]}
        if name == "hub_get_source":
            version = self.versions.pop(0) if len(self.versions) > 1 else self.versions[0]
            return {"success": True, "version": version, "totalLength": 100}
        if name == "hub_list_libraries":
            version = self.library_versions.pop(0) if len(self.library_versions) > 1 else self.library_versions[0]
            return {"source": "hub_api", "libraries": [
                {"id": "12", "name": "Example", "namespace": "mcp", "version": version},
                {"id": "40", "name": "Unrelated", "namespace": "other", "version": 1}]}
        raise AssertionError(name)

    def count(self, name):
        return sum(1 for _, called in self.calls if called == name)


def run(module, hub, **kwargs):
    return module.deploy(hub, "watchdog", "main", PLAN, "op", attempts=6, interval=0, **kwargs)


def test_lost_start_response_polls_the_same_operation_and_never_resubmits(module):
    hub = Hub(lost_start=True)
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op"]


def test_a_stopped_deployment_reports_the_hub_error_and_releases_nothing(module):
    hub = Hub(phases=["updating_app", "stopped"])
    with pytest.raises(module.HubError, match="compile rejected"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_a_dead_mcp_endpoint_after_install_keeps_the_hold_and_names_the_endpoint(module, monkeypatch):
    hub = Hub(dead_main=True)
    clock = iter(range(0, 100000, 400))
    monkeypatch.setattr(module.time, "monotonic", lambda: next(clock))
    with pytest.raises(module.HubError, match="the MCP server did not answer"):
        run(module, hub, wait_s=10**9)
    assert hub.count("hub_set_package_deployment") == 0


def test_a_slow_mcp_endpoint_is_waited_for_and_reported(module, capsys):
    hub = Hub()
    original = hub.probe
    failures = iter([True] * 4)

    def probe(url):
        if url == "main" and hub.started and next(failures, False):
            hub.calls.append((url, "probe"))
            raise OSError("no response from hub")
        original(url)

    hub.probe = probe
    assert run(module, hub)["phase"] == "complete"
    assert capsys.readouterr().out.count("waiting for the MCP server to answer") == 4


def test_a_changed_app_instance_refuses_the_release(module):
    hub = Hub()
    original = hub.call

    def call(url, name, args):
        if name == "hub_list_app_instances" and hub.started:
            hub.instances[0]["id"] = 99
        return original(url, name, args)

    hub.call = call
    with pytest.raises(module.HubError, match="instance"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_fixtures_deleted_during_a_deployment_do_not_block_the_release(module):
    """The restore runs alongside the fixture purge, which deletes test rules and child apps."""
    hub = Hub()
    hub.instances += [{"id": 501, "parentId": None, "type": "Rule-5.1", "disabled": False},
                      {"id": 502, "parentId": 38, "type": "MCP Rule", "disabled": False}]
    original = hub.call

    def call(url, name, args):
        if name == "hub_list_app_instances" and hub.started:
            hub.instances[:] = hub.instances[:1]
        return original(url, name, args)

    hub.call = call
    assert run(module, hub)["phase"] == "complete"


def test_a_disabled_mcp_server_refuses_the_release(module):
    hub = Hub()
    original = hub.call

    def call(url, name, args):
        if name == "hub_list_app_instances" and hub.started:
            hub.instances[0]["disabled"] = True
        return original(url, name, args)

    hub.call = call
    with pytest.raises(module.HubError, match="instance"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_status_for_another_operation_cannot_release_this_one(module):
    hub = Hub()
    original = hub.call

    def call(url, name, args):
        result = original(url, name, args)
        if name == "hub_get_package_deployment":
            result = {"success": False, "error": "No deployment with this requestId"}
        return result

    hub.call = call
    with pytest.raises(module.HubError, match="no record"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_an_awaiting_status_with_an_active_worker_is_not_acknowledged(module):
    hub = Hub()
    original = hub.call

    def call(url, name, args):
        result = original(url, name, args)
        if name == "hub_get_package_deployment":
            result["workerActive"] = True
        return result

    hub.call = call
    with pytest.raises(module.HubError, match=r"timed out at awaiting_verification \(MCP Rule Server\)"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_a_lost_release_response_keeps_polling_until_status_shows_complete(module):
    hub = Hub(phases=["awaiting_verification", "awaiting_verification", "complete"])
    original = hub.call

    def call(url, name, args):
        if name == "hub_set_package_deployment":
            hub.calls.append((url, name))
            raise OSError("relay timeout during the recheck")
        return original(url, name, args)

    hub.call = call
    assert run(module, hub)["phase"] == "complete"
    assert hub.count("hub_set_package_deployment") == 2
    assert hub.started == ["op"]


def test_a_refused_completion_is_retried_while_the_hold_stays(module):
    hub = Hub()
    hub.release_replies = [{"success": False, "phase": "awaiting_verification", "hold": True,
                            "error": "Could not read the installed library list; safety hold retained"}]
    assert run(module, hub)["phase"] == "complete"
    assert hub.count("hub_set_package_deployment") == 2


def test_a_hub_restart_mid_deployment_is_abandoned_and_redeployed_once(module):
    hub = Hub(phases=["updating_app", "interrupted", "awaiting_verification"])
    hub.held = {"requestId": "op", "phase": "interrupted", "hold": True, "workerActive": False}
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op", "op-retry"]


def test_a_second_interruption_is_not_retried_again(module):
    hub = Hub(phases=["interrupted"])
    hub.held = {"requestId": "op", "phase": "interrupted", "hold": True, "workerActive": False}
    with pytest.raises(module.HubError, match="restarted during the retried"):
        run(module, hub)
    assert hub.started == ["op", "op-retry"]


def test_a_busy_refusal_is_retried_because_nothing_was_scheduled(module):
    hub = Hub()
    hub.start_replies = [{"success": False, "busy": True, "activeTool": "hub_purge_e2e_artifacts"}] * 2
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op", "op", "op"]


def test_a_leftover_hold_is_released_once_before_resubmitting(module):
    hub = Hub()
    hub.held = {"requestId": "old", "phase": "stopped", "hold": True, "workerActive": False}
    hub.start_replies = [{"success": False, "heldRequestId": "old", "error": "held"}]
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op", "op"]
    assert hub.held is None


def test_any_other_refusal_fails_without_retrying(module):
    hub = Hub()
    hub.start_replies = [{"success": False, "error": "requestId is already bound to different inputs"}]
    with pytest.raises(module.HubError, match="already bound"):
        run(module, hub)
    assert hub.started == ["op"]


def test_clear_hold_does_nothing_without_a_hold(module):
    hub = Hub()
    hub.held = {"requestId": "done", "phase": "complete", "hold": False}
    assert module.clear_hold(hub, "watchdog", interval=0) is None
    assert hub.count("hub_set_package_deployment") == 0


@pytest.mark.parametrize("held", [
    {"requestId": "old", "phase": "stopped", "hold": True, "workerActive": False},
    {"requestId": "old", "phase": "interrupted", "hold": True, "workerActive": False},
    {"requestId": "old", "phase": "awaiting_verification", "hold": True, "workerActive": False},
    {"requestId": "old", "phase": "updating_app", "hold": True, "workerActive": True, "workerStale": True},
], ids=["stopped", "interrupted", "awaiting", "stale-worker"])
def test_clear_hold_abandons_a_resting_or_dead_operation_after_writes_settle(module, held):
    hub = Hub()
    hub.held = held
    assert module.clear_hold(hub, "watchdog", interval=0)["requestId"] == "old"
    assert hub.held is None
    assert hub.count("hub_get_source") >= 6


def test_clear_hold_waits_for_a_live_worker_and_never_abandons_it(module):
    hub = Hub()
    hub.held = {"requestId": "live", "phase": "updating_app", "hold": True, "workerActive": True}
    with pytest.raises(module.HubError, match="still running"):
        module.clear_hold(hub, "watchdog", interval=0, attempts=3)
    assert hub.count("hub_set_package_deployment") == 0


def test_settling_requires_a_quiet_stretch_after_the_last_version_change(module):
    hub = Hub()
    hub.versions = [7, 7, 8, 8, 8, 8, 8, 8, 8]
    module.wait_until_settled(hub, "watchdog", samples=3, interval=0)
    assert hub.count("hub_get_source") >= 8


def test_settling_gives_up_while_versions_keep_moving(module):
    hub = Hub()
    hub.versions = list(range(100))
    with pytest.raises(module.HubError, match="still in flight"):
        module.wait_until_settled(hub, "watchdog", samples=3, interval=0, attempts=5)


class Discovery:
    def __init__(self, version, instances, page):
        self.version, self.instances, self.page = version, instances, page
        self.calls = []

    def call(self, url, name, args):
        self.calls.append((url, name))
        if name == "hub_get_info":
            return {"watchdogVersion": 3 if url == V3_URL else self.version}
        if name == "hub_list_app_instances":
            return {"apps": self.instances}
        if name == "hub_read_apps_code":
            assert args == {"tool": "hub_get_app_config", "args": {"appId": "48028"}}
            return self.page
        raise AssertionError(name)


V3_URL = "https://cloud.hubitat.com/api/0f0f0f0f-aaaa-bbbb-cccc-121212121212/apps/48028/mcp?access_token=abc-123"
V3_PAGE = {"page": {"sections": [{"paragraphs": [f"Cloud /mcp endpoint (token-in-query):{V3_URL}"]}]}}
V3_INSTANCE = [{"id": 48028, "type": "E2E Dead-Man Watchdog v3"}, {"id": 38, "type": "MCP Rule Server"}]


def test_a_secret_already_pointing_at_v3_is_used_as_is(module):
    hub = Discovery(3, [], {})
    assert module.resolve_v3_url(hub, "main", "configured") == "configured"
    assert hub.calls == [("configured", "hub_get_info")]


def test_v3_is_discovered_from_its_app_page_while_the_secret_points_at_v2(module, tmp_path):
    hub = Discovery(2, V3_INSTANCE, V3_PAGE)
    cache = tmp_path / "endpoint"
    assert module.resolve_v3_url(hub, "main", "configured", cache) == V3_URL
    assert cache.read_text() == V3_URL
    hub.calls.clear()
    assert module.resolve_v3_url(hub, "main", "configured", cache) == V3_URL
    assert hub.calls == []


@pytest.mark.parametrize("instances,page,message", [
    ([], V3_PAGE, "found 0"),
    ([*V3_INSTANCE, {"id": 5, "type": "E2E Dead-Man Watchdog v3"}], V3_PAGE, "found 2"),
    (V3_INSTANCE, {"page": {}}, "exactly one endpoint"),
], ids=["not-installed", "duplicated", "no-endpoint-shown"])
def test_discovery_refuses_an_ambiguous_v3(module, instances, page, message):
    with pytest.raises(module.HubError, match=message):
        module.resolve_v3_url(Discovery(2, instances, page), "main", "configured")


def bundle_bytes(kind="library", extra=False):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as bundle:
        manifest = f"mcp\nmcp_libraries\n{kind} mcp.Example.groovy\n"
        bundle.writestr("install.txt", manifest)
        bundle.writestr("update.txt", manifest)
        bundle.writestr("mcp.Example.groovy", "library source\n")
        if extra:
            bundle.writestr("watchdog.groovy", "unexpected app code")
    return buffer.getvalue()


def test_plan_hashes_exact_library_bytes(module):
    assert module.plan_from_bundle("a" * 40, bundle_bytes()) == {
        "ref": "a" * 40,
        "libraries": [{"name": "Example", "sha256": hashlib.sha256(b"library source\n").hexdigest()}],
    }


@pytest.mark.parametrize("ref,kind,extra", [("main", "library", False), ("a" * 40, "app", False), ("a" * 40, "library", True)])
def test_plan_rejects_mutable_refs_and_app_code_in_the_bundle(module, ref, kind, extra):
    with pytest.raises(ValueError):
        module.plan_from_bundle(ref, bundle_bytes(kind, extra))


def test_a_stopped_status_inside_an_error_envelope_keeps_its_details(module, monkeypatch):
    transport = module.Transport()
    monkeypatch.setattr(transport, "rpc", lambda *args: {
        "isError": True, "content": [{"type": "text", "text":
        '{"success":false,"phase":"stopped","requestId":"one","error":"compile rejected"}'}],
    })
    result = transport.call("v3", "hub_get_package_deployment", {"requestId": "one"})
    assert result["phase"] == "stopped" and result["error"] == "compile rejected"


@pytest.fixture
def cli(module, monkeypatch, tmp_path):
    """The CLI with its hub and GitHub edges replaced; records what it would have done."""
    hub = Hub()
    seen = {"deploys": [], "order": [], "follows": []}
    monkeypatch.setattr(module, "endpoints", lambda: (hub, "watchdog", "main"))
    monkeypatch.setattr(module, "clear_hold", lambda *args, **kwargs: seen["order"].append("clear_hold"))

    def deploy(transport, v3, mcp, plan, request_id, **kwargs):
        seen["deploys"].append((plan, request_id))
        seen["order"].append("deploy")
        return {"requestId": request_id, "phase": "complete", "hold": False}

    def start(transport, v3, plan, request_id):
        seen["deploys"].append((plan, request_id))
        seen["order"].append("submit")
        return [("38", "MCP Rule Server", False)]

    def follow(transport, v3, mcp, plan, request_id, baseline, **kwargs):
        seen["follows"].append((request_id, baseline, kwargs))
        if seen.get("follow_error"):
            raise seen["follow_error"]
        return {"requestId": request_id, "phase": "complete", "hold": False}

    monkeypatch.setattr(module, "deploy", deploy)
    monkeypatch.setattr(module, "start", start)
    monkeypatch.setattr(module, "follow", follow)
    monkeypatch.setenv("RUNNER_TEMP", str(tmp_path))
    monkeypatch.setenv("GITHUB_REPOSITORY", "owner/repo")
    monkeypatch.setenv("GITHUB_RUN_ID", "77")
    monkeypatch.setenv("GITHUB_RUN_ATTEMPT", "2")
    monkeypatch.setenv("PR_RAW_BASE", "https://raw.githubusercontent.com/owner/repo")
    monkeypatch.setenv("PR_HEAD_SHA_RESOLVED", "c" * 40)
    monkeypatch.setenv("MAIN_SHA", "b" * 40)
    bundle = tmp_path / "mcp-libraries.zip"
    bundle.write_bytes(bundle_bytes())
    return module, hub, seen, bundle


def test_restore_submits_main_as_it_is_now_after_releasing_the_hold_and_purging(cli, monkeypatch):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    original = hub.call

    def call(url, name, args):
        if name == "hub_purge_e2e_artifacts":
            seen["order"].append("purge")
            return {"success": True, "deletedCount": 3}
        return original(url, name, args)

    hub.call = call
    module.main(["restore-main"])
    assert seen["order"] == ["clear_hold", "purge", "submit"]
    assert seen["follows"] == []
    plan, request_id = seen["deploys"][0]
    assert plan["ref"] == "d" * 40 and request_id == "e2e-77-2-main"
    assert plan["baseUrl"] == plan["bundleBaseUrl"] == "https://raw.githubusercontent.com/owner/repo"


def test_a_cancelled_run_releases_its_hold_and_purges_but_deploys_nothing(cli, capsys):
    module, hub, seen, _bundle = cli
    original = hub.call

    def call(url, name, args):
        if name == "hub_purge_e2e_artifacts":
            seen["order"].append("purge")
            return {"success": True, "deletedCount": 1}
        return original(url, name, args)

    hub.call = call
    module.main(["restore-main", "--cancelled"])
    assert seen["order"] == ["clear_hold", "purge"]
    assert seen["deploys"] == []
    assert "main was NOT restored" in capsys.readouterr().out


def test_a_run_cancelled_mid_install_leaves_the_hold_and_does_not_fail(cli, monkeypatch, capsys):
    module, hub, _seen, _bundle = cli

    def still_running(*args, **kwargs):
        raise module.HubError("A package deployment is still running on the hub")

    monkeypatch.setattr(module, "clear_hold", still_running)
    hub.call = lambda url, name, args: pytest.fail("nothing may be written while the worker runs")
    module.main(["restore-main", "--cancelled"])
    out = capsys.readouterr().out
    assert "left for the next run" in out
    assert "A package deployment is still running on the hub" in out


def test_a_purge_that_reports_failures_warns_and_does_not_fail(cli, monkeypatch, capsys):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    hub.call = lambda url, name, args: {"success": False, "failedCount": 2}
    module.main(["restore-main"])
    assert seen["order"] == ["clear_hold", "submit"]
    assert "::warning::The fixture purge reported failures" in capsys.readouterr().out


def test_a_response_cut_off_mid_body_is_a_lost_response(module, monkeypatch):
    def cut_off(request, timeout):
        raise module.http.client.IncompleteRead(b"{")

    monkeypatch.setattr(module.urllib.request, "urlopen", cut_off)
    with pytest.raises(OSError, match="usable response"):
        module.Transport().rpc("https://hub.invalid/mcp", "tools/list", {})


def test_restore_falls_back_to_the_starting_main_when_the_new_one_has_no_bundle_yet(cli, monkeypatch):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: None if "d" * 40 in url else bundle_bytes())
    hub.call = lambda url, name, args: {"success": True}
    module.main(["restore-main"])
    assert seen["deploys"][0][0]["ref"] == "b" * 40


def test_restore_still_deploys_when_the_purge_response_is_lost(cli, monkeypatch):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: None)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())

    def call(url, name, args):
        raise OSError("relay timeout")

    hub.call = call
    module.main(["restore-main"])
    assert seen["order"] == ["clear_hold", "submit"]


def test_a_restore_that_cannot_start_warns_and_never_fails_the_run(cli, monkeypatch, capsys):
    module, _hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: None)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: None)
    module.main(["restore-main"])
    assert seen["deploys"] == []
    out = capsys.readouterr().out
    assert "::warning::Main was not restored (No published bundle for main)" in out
    assert "does not affect the e2e result" in out


def test_the_wait_follows_the_submitted_restore_with_its_baseline(cli, monkeypatch):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    hub.call = lambda url, name, args: {"success": True}
    module.main(["restore-main"])
    module.main(["wait-restore"])
    request_id, baseline, options = seen["follows"][0]
    assert request_id == "e2e-77-2-main"
    assert baseline == [("38", "MCP Rule Server", False)]
    assert options["attempts"] == 72


def test_the_wait_never_fails_the_run(cli, monkeypatch, capsys):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    hub.call = lambda url, name, args: {"success": True}
    module.main(["restore-main"])
    seen["follow_error"] = module.HubError("Deployment stopped: compile rejected")
    module.main(["wait-restore"])
    assert "::warning::Main was not restored (Deployment stopped: compile rejected)" in capsys.readouterr().out


def test_the_wait_does_nothing_when_no_restore_was_submitted(cli, capsys):
    module, _hub, seen, _bundle = cli
    module.main(["wait-restore"])
    assert seen["follows"] == []
    assert "nothing to wait for" in capsys.readouterr().out


def test_pr_install_requires_the_published_bundle_to_equal_the_checkout_build(cli, monkeypatch):
    module, _hub, seen, bundle = cli
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: b"different bytes")
    with pytest.raises(SystemExit, match="differs from the bundle built"):
        module.main(["deploy-pr", "--bundle", str(bundle)])
    assert seen["deploys"] == []


def test_pr_install_deploys_the_head_sha_from_the_base_repository(cli, monkeypatch):
    module, _hub, seen, bundle = cli
    urls = []
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: urls.append(url) or bundle.read_bytes())
    module.main(["deploy-pr", "--bundle", str(bundle)])
    plan, request_id = seen["deploys"][0]
    assert urls == ["https://raw.githubusercontent.com/owner/repo/bundle-artifacts/shas/" + "c" * 40 + "/mcp-libraries.zip"]
    assert plan["ref"] == "c" * 40 and request_id == "e2e-77-2-pr"
    assert plan["libraries"][0]["name"] == "Example"


def test_pr_install_without_a_published_bundle_warns_and_lets_v3_decide(cli, monkeypatch, capsys):
    module, _hub, seen, bundle = cli
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: None)
    module.main(["deploy-pr", "--bundle", str(bundle)])
    assert len(seen["deploys"]) == 1
    assert "::warning::No bundle-artifacts entry" in capsys.readouterr().out


def test_prepare_releases_a_leftover_hold_and_takes_no_backup(cli):
    module, hub, seen, _bundle = cli
    module.main(["prepare"])
    assert seen["order"] == ["clear_hold"]
    assert hub.count("hub_create_backup") == 0


def test_prepare_still_releases_a_hold_when_the_mcp_endpoint_check_gets_no_answer(cli, capsys):
    module, hub, seen, _bundle = cli
    original = hub.call

    def call(url, name, args):
        if name == "hub_get_info" and args.get("peer"):
            raise OSError("relay timeout")
        return original(url, name, args)

    hub.call = call
    module.main(["prepare"])
    assert seen["order"] == ["clear_hold"]
    assert "::warning::The MCP endpoint check got no answer" in capsys.readouterr().out


def test_an_endpoint_failure_never_prints_the_exception(cli, monkeypatch):
    module, _hub, _seen, _bundle = cli

    def endpoints():
        raise OSError("https://cloud.hubitat.com/api/x/apps/1/mcp?access_token=secret")

    monkeypatch.setattr(module, "endpoints", endpoints)
    with pytest.raises(SystemExit) as failure:
        module.main(["prepare"])
    assert "secret" not in str(failure.value)


def answering(hub, tool, replies):
    """Make `tool` answer (or raise) each of `replies` once, then behave as the fake normally does."""
    original = hub.call
    queue = list(replies)

    def call(url, name, args):
        if name == tool and queue:
            hub.calls.append((url, name))
            reply = queue.pop(0)
            if isinstance(reply, Exception):
                raise reply
            if reply is not None:
                return reply
        return original(url, name, args)

    hub.call = call


def test_a_failed_instance_read_after_the_install_is_retried_not_fatal(module):
    """v3 answers a failed loopback read with success:false; that is a retry, not an identity change."""
    hub = Hub()
    original = hub.call
    failures = [{"success": False, "error": "empty response from /hub2/appsList"}]

    def call(url, name, args):
        if name == "hub_list_app_instances" and hub.started and failures:
            return failures.pop()
        return original(url, name, args)

    hub.call = call
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op"]


def test_an_unreadable_instance_list_names_the_hubs_reason(module):
    hub = Hub()
    answering(hub, "hub_list_app_instances", [{"success": False, "error": "empty response from /hub2/appsList"}])
    with pytest.raises(module.Unreadable, match="empty response from /hub2/appsList"):
        module.instance_snapshot(hub, "watchdog")


@pytest.mark.parametrize("tool,reply", [
    ("hub_get_source", {"success": False, "error": "Empty response from hub"}),
    ("hub_list_apps", {"apps": [], "note": "Empty response from hub API"}),
    ("hub_list_libraries", {"libraries": [], "count": 0}),
], ids=["source", "app-list", "library-list"])
def test_settling_resamples_after_a_read_the_hub_could_not_serve(module, tool, reply):
    hub = Hub()
    answering(hub, tool, [reply])
    module.wait_until_settled(hub, "watchdog", samples=3, interval=0)


def test_settling_gives_up_while_a_library_version_keeps_moving(module):
    hub = Hub()
    hub.library_versions = list(range(100))
    with pytest.raises(module.HubError, match="still in flight"):
        module.wait_until_settled(hub, "watchdog", samples=3, interval=0, attempts=5)


def test_dropped_status_reads_are_polled_through_without_resubmitting(module):
    hub = Hub(phases=["updating_app", "awaiting_verification"])
    answering(hub, "hub_get_package_deployment", [OSError("relay timeout"), None, OSError("relay timeout")])
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == ["op"]


def test_a_start_the_hub_rejects_reports_its_reason_at_once(module):
    hub = Hub()
    answering(hub, "hub_update_package", [module.ToolError(
        "Invalid params: libraries must contain the expected name and SHA-256", invalid=True)])
    with pytest.raises(module.HubError, match="Deployment refused: Invalid params: libraries"):
        run(module, hub)
    assert hub.count("hub_get_package_deployment") == 0


def test_a_start_that_threw_on_the_hub_is_followed_by_reading_status(module, capsys):
    hub = Hub()
    answering(hub, "hub_update_package", [module.ToolError("Tool error: scheduler unavailable")])
    assert run(module, hub)["phase"] == "complete"
    assert hub.started == []
    assert "the start request failed on the hub (Tool error: scheduler unavailable)" in capsys.readouterr().out


def test_a_release_that_fails_on_the_hub_says_so_and_is_retried(module, capsys):
    hub = Hub()
    answering(hub, "hub_set_package_deployment", [module.ToolError("Tool error: Could not persist deployment stage")])
    assert run(module, hub)["phase"] == "complete"
    assert "the release failed on the hub (Tool error: Could not persist deployment stage)" in capsys.readouterr().out


def test_a_worker_that_went_silent_stops_the_wait_and_names_where(module):
    hub = Hub(phases=["updating_app"])
    original = hub.call

    def call(url, name, args):
        result = original(url, name, args)
        if name == "hub_get_package_deployment":
            result.update(workerActive=True, workerStale=True)
        return result

    hub.call = call
    with pytest.raises(module.HubError, match=r"went silent in updating_app \(MCP Rule Server\)"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


def test_a_json_rpc_error_carries_the_hubs_message(module, monkeypatch):
    body = b'{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid params: ref"}}'
    monkeypatch.setattr(module.urllib.request, "urlopen", lambda request, timeout: io.BytesIO(body))
    with pytest.raises(module.ToolError, match="Invalid params: ref") as failure:
        module.Transport().rpc("https://hub.invalid/mcp", "tools/call", {})
    assert failure.value.invalid


def test_a_tool_that_threw_is_a_hub_failure_not_a_lost_response(module, monkeypatch):
    transport = module.Transport()
    monkeypatch.setattr(transport, "rpc", lambda *args: {
        "isError": True, "content": [{"type": "text", "text": "Tool error: boom"}]})
    with pytest.raises(module.ToolError, match="boom") as failure:
        transport.call("v3", "hub_update_package", {})
    assert not failure.value.invalid


def test_only_a_404_means_the_bundle_does_not_exist(module, monkeypatch):
    def failing(code):
        def urlopen(url, timeout):
            raise module.urllib.error.HTTPError(url, code, "nope", None, None)
        return urlopen

    monkeypatch.setattr(module.urllib.request, "urlopen", failing(404))
    assert module.fetch("https://example.invalid/bundle.zip", interval=0) is None
    monkeypatch.setattr(module.urllib.request, "urlopen", failing(503))
    with pytest.raises(module.HubError, match="HTTP 503"):
        module.fetch("https://example.invalid/bundle.zip", interval=0)


def test_an_unresolvable_main_says_it_fell_back(module, monkeypatch, capsys):
    def no_network(*args, **kwargs):
        raise module.subprocess.SubprocessError("no network")

    monkeypatch.setattr(module.subprocess, "run", no_network)
    assert module.current_main_sha("owner/repo") is None
    assert "::warning::Could not resolve main's current SHA" in capsys.readouterr().out


def test_a_discovered_endpoint_is_masked_before_it_is_first_used(module, monkeypatch, capsys):
    """Until the secret points at v3, the discovered URL is not a GitHub secret: the mask is its only cover."""
    monkeypatch.setenv("GITHUB_ACTIONS", "true")
    hub = Discovery(2, V3_INSTANCE, V3_PAGE)
    original = hub.call
    printed_before_use = []

    def call(url, name, args):
        if url == V3_URL:
            printed_before_use.append(capsys.readouterr().out)
        return original(url, name, args)

    hub.call = call
    module.resolve_v3_url(hub, "main", "configured")
    assert printed_before_use == [f"::add-mask::{V3_URL}\n::add-mask::abc-123\n"]


def test_the_endpoint_command_prints_the_url_as_its_last_line(cli, capsys):
    """mcp_watchdog_deploy.sh takes the last stdout line as WATCHDOG_URL."""
    module, _hub, _seen, _bundle = cli
    module.main(["endpoint"])
    assert capsys.readouterr().out.splitlines()[-1] == "watchdog"


@pytest.mark.parametrize("argv", [["restore-main"], ["restore-main", "--cancelled"], ["wait-restore"]],
                         ids=["restore", "cancelled", "wait"])
def test_no_restore_command_fails_a_run_when_the_watchdog_cannot_be_reached(cli, monkeypatch, capsys, tmp_path, argv):
    module, _hub, _seen, _bundle = cli
    (tmp_path / "watchdog-v3-restore.json").write_text('{"requestId": "x", "plan": {}, "baseline": []}')

    def endpoints():
        raise OSError("https://cloud.hubitat.com/api/x/apps/1/mcp?access_token=secret")

    monkeypatch.setattr(module, "endpoints", endpoints)
    module.main(argv)
    out = capsys.readouterr().out
    assert "::warning::Main was not restored" in out
    assert "secret" not in out


def test_a_restore_that_cannot_be_recorded_is_still_reported_as_submitted(cli, monkeypatch, capsys, tmp_path):
    module, hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    monkeypatch.setenv("RUNNER_TEMP", str(tmp_path / "missing"))
    hub.call = lambda url, name, args: {"success": True}
    module.main(["restore-main"])
    out = capsys.readouterr().out
    assert seen["order"] == ["clear_hold", "submit"]
    assert "is being restored on the hub" in out
    assert "::warning::Could not record the restore" in out
    assert "Main was not restored" not in out


def test_the_real_restore_sequence_releases_the_hold_purges_and_submits_once(module, monkeypatch, tmp_path):
    hub = Hub()
    hub.held = {"requestId": "e2e-77-2-pr", "phase": "stopped", "hold": True, "workerActive": False}
    answering(hub, "hub_purge_e2e_artifacts", [{"success": True, "deletedCount": 2}])
    monkeypatch.setattr(module, "endpoints", lambda: (hub, "watchdog", "main"))
    monkeypatch.setattr(module, "current_main_sha", lambda repository: "d" * 40)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: bundle_bytes())
    monkeypatch.setenv("RUNNER_TEMP", str(tmp_path))
    monkeypatch.setenv("GITHUB_REPOSITORY", "owner/repo")
    monkeypatch.setenv("GITHUB_RUN_ID", "77")
    monkeypatch.setenv("GITHUB_RUN_ATTEMPT", "2")
    module.main(["restore-main"])
    names = [name for _url, name in hub.calls]
    assert names.index("hub_set_package_deployment") < names.index("hub_purge_e2e_artifacts") \
        < names.index("hub_update_package")
    assert hub.started == ["e2e-77-2-main"]
    state = json.loads((tmp_path / "watchdog-v3-restore.json").read_text())
    assert state["requestId"] == "e2e-77-2-main"
    assert state["baseline"] == [["38", "MCP Rule Server", False]]


def test_status_reads_that_never_answer_are_bounded_by_time_not_only_by_attempts(module, monkeypatch):
    hub = Hub()
    answering(hub, "hub_get_package_deployment", [OSError("relay timeout")] * 50)
    clock = iter(range(0, 100000, 70))
    monkeypatch.setattr(module.time, "monotonic", lambda: next(clock))
    with pytest.raises(module.HubError, match="timed out at no status was ever read"):
        module.deploy(hub, "watchdog", "main", PLAN, "op", attempts=50, interval=0, wait_s=300)
    assert hub.count("hub_get_package_deployment") < 10
