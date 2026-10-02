"""Exercise the watchdog v3 e2e client with a transport that never reaches a hub."""

import hashlib
import importlib.util
import io
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


def test_a_dead_mcp_endpoint_after_install_keeps_the_hold(module):
    hub = Hub(dead_main=True)
    with pytest.raises(module.HubError, match="does not answer"):
        run(module, hub)
    assert hub.count("hub_set_package_deployment") == 0


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
    with pytest.raises(module.HubError, match="timed out"):
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
    assert hub.count("hub_set_package_deployment") <= 2


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
            return {"watchdogVersion": 3 if url.startswith("https://cloud.hubitat.com") else self.version}
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
    seen = {"deploys": [], "order": []}
    monkeypatch.setattr(module, "endpoints", lambda: (hub, "watchdog", "main"))
    monkeypatch.setattr(module, "clear_hold", lambda *args, **kwargs: seen["order"].append("clear_hold"))

    def deploy(transport, v3, mcp, plan, request_id, **kwargs):
        seen["deploys"].append((plan, request_id))
        seen["order"].append("deploy")
        return {"requestId": request_id, "phase": "complete", "hold": False}

    monkeypatch.setattr(module, "deploy", deploy)
    monkeypatch.setenv("GITHUB_REPOSITORY", "owner/repo")
    monkeypatch.setenv("GITHUB_RUN_ID", "77")
    monkeypatch.setenv("GITHUB_RUN_ATTEMPT", "2")
    monkeypatch.setenv("PR_RAW_BASE", "https://raw.githubusercontent.com/owner/repo")
    monkeypatch.setenv("PR_HEAD_SHA_RESOLVED", "c" * 40)
    monkeypatch.setenv("MAIN_SHA", "b" * 40)
    bundle = tmp_path / "mcp-libraries.zip"
    bundle.write_bytes(bundle_bytes())
    return module, hub, seen, bundle


def test_restore_deploys_main_as_it_is_now_after_releasing_the_hold_and_purging(cli, monkeypatch):
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
    assert seen["order"] == ["clear_hold", "purge", "deploy"]
    plan, request_id = seen["deploys"][0]
    assert plan["ref"] == "d" * 40 and request_id == "e2e-77-2-main"
    assert plan["baseUrl"] == plan["bundleBaseUrl"] == "https://raw.githubusercontent.com/owner/repo"


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
    assert seen["order"] == ["clear_hold", "deploy"]


def test_restore_fails_loudly_when_main_has_no_published_bundle(cli, monkeypatch):
    module, _hub, seen, _bundle = cli
    monkeypatch.setattr(module, "current_main_sha", lambda repository: None)
    monkeypatch.setattr(module, "fetch", lambda url, **kwargs: None)
    with pytest.raises(SystemExit, match="No published bundle for main"):
        module.main(["restore-main"])
    assert seen["deploys"] == []


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


def test_an_endpoint_failure_never_prints_the_exception(cli, monkeypatch):
    module, _hub, _seen, _bundle = cli

    def endpoints():
        raise OSError("https://cloud.hubitat.com/api/x/apps/1/mcp?access_token=secret")

    monkeypatch.setattr(module, "endpoints", endpoints)
    with pytest.raises(SystemExit) as failure:
        module.main(["prepare"])
    assert "secret" not in str(failure.value)
