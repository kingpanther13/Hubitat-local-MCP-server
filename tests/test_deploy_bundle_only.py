"""pytest: the watchdog e2e deploy delivers libraries ONLY via the bundle (no per-#include install).

Regression guard for the bundle-only conversion of ``.github/scripts/mcp_watchdog_deploy.sh``. The
deploy used to install each ``#include``d library INDIVIDUALLY (a ``hub_update_library`` /
``hub_create_library`` per ``#include``) AND install the bundle that ships those same libraries --
redundant work plus an extra recompile of the running app. The bundle is now the sole library delivery
path (the HPM "one Update click" flow). If a future edit re-introduces a per-library install, e2e
silently regains the redundant recompile -- this test fails first. It checks the actual tool-CALL RPCs
(``name:"hub_..."``), not bare mentions, so the explanatory comment naming the old loop is fine.
"""

from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "mcp_watchdog_deploy.sh"


def test_deploy_does_not_install_libraries_individually():
    text = SCRIPT.read_text()
    for rpc in ('name:"hub_update_library"', 'name:"hub_create_library"'):
        assert rpc not in text, (
            f"{SCRIPT.name} issues a {rpc} call -- the deploy must deliver #included libraries ONLY via "
            "the bundle (hub_install_bundle). A per-#include library install is the redundant "
            "pre-bundle-only flow and forces an extra recompile of the running app."
        )


def test_deploy_installs_the_bundle():
    text = SCRIPT.read_text()
    assert 'name:"hub_install_bundle"' in text, (
        f"{SCRIPT.name} no longer calls hub_install_bundle -- bundle-only delivery requires it (the "
        "bundle is the sole #include library source)."
    )


def test_deploy_guards_includes_without_a_bundle():
    text = SCRIPT.read_text()
    assert "declares NO bundle to deliver them" in text, (
        f"{SCRIPT.name} dropped the bundle-coverage guard: an app that #includes libraries while the "
        "manifest declares no bundle must fail loudly, not deploy an app whose #includes can't resolve."
    )


def test_deploy_builds_the_bundle_in_ci_instead_of_fetching_the_committed_zip():
    """PRs no longer commit bundles/*.zip (rebuild-bundle.yml owns it on main post-merge), so the
    deploy MUST build the PR's zip from the checkout and stage it through the watchdog -- a raw
    URL fetch of the bundle at the PR SHA would install a STALE zip and e2e would silently test
    outdated library code."""
    text = SCRIPT.read_text()
    assert "python3 tools/build-bundle.py" in text, (
        f"{SCRIPT.name} no longer builds the bundle zip from the PR checkout -- without the in-CI "
        "build, e2e installs whatever stale zip is committed at the PR SHA."
    )
    assert "http://127.0.0.1:8080/local/" in text, (
        f"{SCRIPT.name} no longer installs the staged bundle from the hub's own /local/ URL -- the "
        "built zip must be staged via the watchdog's base64 hub_write_file and imported locally."
    )
    for stale_fetch in ('BUNDLE_URL="${PR_RAW_BASE}', "BUNDLE_URL=\"$PR_RAW_BASE"):
        assert stale_fetch not in text, (
            f"{SCRIPT.name} builds the bundle URL from PR_RAW_BASE again -- that fetches the zip "
            "COMMITTED at the PR SHA, which PRs no longer rebuild (stale library code)."
        )
