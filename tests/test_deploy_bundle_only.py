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
    assert "/bundle-artifacts/shas/" in text, (
        f"{SCRIPT.name} no longer resolves the bundle from the bundle-artifacts branch -- without "
        "it, a library-touching PR has no URL serving its fresh zip (PRs do not commit the zip)."
    )
    for stale_fetch in ('BUNDLE_URL="${PR_RAW_BASE}', "BUNDLE_URL=\"$PR_RAW_BASE"):
        assert stale_fetch not in text, (
            f"{SCRIPT.name} builds the bundle URL from PR_RAW_BASE again -- that fetches the zip "
            "COMMITTED at the PR SHA, which PRs no longer rebuild (stale library code)."
        )


def test_deploy_verifies_landed_libraries_after_the_bundle_step():
    """Run 27322480301: hub_install_bundle reported success while leaving pre-existing
    libraries STALE -- the deploy must re-verify every #include'd library (one copy per
    namespace+name, on-hub length equals the PR file) so that failure class can never
    pass silently again. Pin the verification's load-bearing pieces."""
    text = SCRIPT.read_text()
    for marker in ('"hub_list_libraries"', 'name:"hub_get_source"', "totalLength", "is STALE on the hub"):
        assert marker in text, (
            f"{SCRIPT.name} dropped the post-install library verification piece {marker!r} -- without "
            "it a success-but-stale bundle install only surfaces as a runtime MissingMethodException."
        )
