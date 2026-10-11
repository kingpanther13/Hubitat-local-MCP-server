"""pytest: the e2e install hands the whole package, built in CI, to watchdog v3.

Regression guard for ``.github/scripts/mcp_watchdog_deploy.sh``. V3 installs both built apps as one
operation and verifies them by source hash, so the shell script must not grow its own per-library or
per-app install calls again: those would run outside v3's hold and repeat a recompile. It checks
tool-CALL RPCs (``name:"hub_..."``), not bare mentions in comments.
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
    assert 'watchdog_v3.py" deploy-pr --dist "$REPO_DIR/dist"' in text, (
        f"{SCRIPT.name} no longer installs through watchdog_v3.py deploy-pr."
    )
    assert "mcp_deadman_heartbeat" not in text and not (SCRIPTS / "mcp_deadman_heartbeat.sh").exists(), (
        "v3 has no deadline to extend; the heartbeat must not come back."
    )


def test_v3_refuses_a_built_parent_that_still_includes_libraries():
    client = (SCRIPTS / "watchdog_v3.py").read_text()
    assert "still has #include directives" in client, (
        "watchdog_v3.py dropped the guard against a built app whose #includes nothing would deliver."
    )


def test_deploy_builds_the_apps_in_ci_instead_of_trusting_the_published_ones():
    """The expected app hashes must come from a build of the checkout; watchdog_v3.py then requires
    the published shas/<sha>/ entry to equal that build."""
    text = SCRIPT.read_text()
    assert "python3 tools/build-release-app.py" in text, (
        f"{SCRIPT.name} no longer builds the apps from the PR checkout."
    )
    assert "tools/build-bundle.py" not in text and "mcp-libraries.zip" not in text, (
        f"{SCRIPT.name} still builds the retired library bundle."
    )
    client = (SCRIPTS / "watchdog_v3.py").read_text()
    assert "/bundle-artifacts/shas/" in client and "differs from the one built from this checkout" in client, (
        "watchdog_v3.py no longer compares the published bundle-artifacts entry with the checkout build."
    )


def test_the_prepare_and_teardown_steps_keep_the_names_the_workflow_on_main_calls():
    """hub-e2e.yml on main drives every open PR's scripts by these file names."""
    assert 'watchdog_v3.py" prepare' in (SCRIPTS / "mcp_arm_watchdog.sh").read_text()
    assert 'watchdog_v3.py" teardown' in (SCRIPTS / "mcp_disarm_watchdog.sh").read_text()
