"""pytest: the e2e install hands the whole package to watchdog v3 and delivers libraries only by bundle.

Regression guard for ``.github/scripts/mcp_watchdog_deploy.sh``. V3 installs the bundle and both
apps as one operation and verifies them by source hash, so the shell script must not grow its own
per-library or per-app install calls again: those would run outside v3's hold and repeat a
recompile. It checks tool-CALL RPCs (``name:"hub_..."``), not bare mentions in comments.
"""

from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent / ".github" / "scripts"
SCRIPT = SCRIPTS / "mcp_watchdog_deploy.sh"


def test_deploy_issues_no_install_calls_of_its_own():
    text = SCRIPT.read_text()
    for rpc in ('name:"hub_update_library"', 'name:"hub_create_library"', 'name:"hub_install_bundle"',
                'name:"hub_update_app"'):
        assert rpc not in text, (
            f"{SCRIPT.name} issues a {rpc} call -- the package install belongs to watchdog v3's "
            "hub_update_package, which holds the hub and verifies every component by hash."
        )


def test_deploy_hands_the_package_to_v3():
    text = SCRIPT.read_text()
    assert 'watchdog_v3.py" deploy-pr --bundle "$BUNDLE_PATH"' in text, (
        f"{SCRIPT.name} no longer installs through watchdog_v3.py deploy-pr."
    )
    assert "mcp_deadman_heartbeat" not in text and not (SCRIPTS / "mcp_deadman_heartbeat.sh").exists(), (
        "v3 has no deadline to extend; the heartbeat must not come back."
    )


def test_deploy_guards_includes_without_a_bundle():
    text = SCRIPT.read_text()
    assert "declares NO bundle to deliver them" in text, (
        f"{SCRIPT.name} dropped the bundle-coverage guard: an app that #includes libraries while the "
        "manifest declares no bundle must fail loudly, not deploy an app whose #includes can't resolve."
    )


def test_deploy_builds_the_bundle_in_ci_instead_of_trusting_a_committed_zip():
    """PRs do not commit bundles/*.zip, so the expected library hashes must come from a bundle built
    from the checkout; watchdog_v3.py then requires the published artifact to equal that build."""
    text = SCRIPT.read_text()
    assert "python3 tools/build-bundle.py" in text, (
        f"{SCRIPT.name} no longer builds the bundle zip from the PR checkout."
    )
    client = (SCRIPTS / "watchdog_v3.py").read_text()
    assert "/bundle-artifacts/shas/" in client and "differs from the bundle built from this checkout" in client, (
        "watchdog_v3.py no longer compares the published bundle-artifacts entry with the checkout build."
    )


def test_the_prepare_and_restore_steps_keep_the_names_the_workflow_on_main_calls():
    """hub-e2e.yml on main drives every open PR's scripts by these file names."""
    assert 'watchdog_v3.py" prepare' in (SCRIPTS / "mcp_arm_watchdog.sh").read_text()
    assert 'watchdog_v3.py" restore-main' in (SCRIPTS / "mcp_disarm_watchdog.sh").read_text()
