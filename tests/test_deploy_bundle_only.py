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


def test_deploy_probes_the_hub_before_skipping_and_heals_when_stale():
    """The PR's headline deploy-side fix: a bundle byte-identical to canonical main no longer skips
    BLIND. It first probes the hub (verify_includes_current probe) and, when the hub is stale (a merge
    landed mid-run, leaving a restored hub a merge behind), HEALS in-run by pointing the install at
    canonical main's own artifact instead of skipping. The post-install enforce pass runs only when a
    bundle actually installed; on the all-skip path the per-skip probe IS the verification. Without
    these pins, a revert to skip-on-byte-equality -- the exact staleness this PR fixes -- leaves every
    other deploy guard green (the marker test above survives because its strings merely moved into the
    verify function), and the live e2e only trips on the byte-equal+hub-stale race, which a given run
    may never hit."""
    text = SCRIPT.read_text()
    assert "verify_includes_current probe" in text, (
        f"{SCRIPT.name}: the skip path no longer probes the hub before skipping -- a byte-equal-to-main "
        "bundle would skip blind while the hub is a merge behind (the staleness this PR fixes)."
    )
    assert "verify_includes_current enforce" in text, (
        f"{SCRIPT.name}: the post-install enforce verification is gone."
    )
    assert 'BUNDLE_URL="$MAIN_BUNDLE_URL"' in text, (
        f"{SCRIPT.name}: the stale-hub heal no longer points the install at canonical main's artifact -- "
        "a stale hub would be left stale instead of healed in-run."
    )
    assert "ANY_BUNDLE_INSTALLED" in text, (
        f"{SCRIPT.name}: the enforce pass is no longer gated on whether a bundle actually installed -- "
        "on the all-skip path the per-skip probe is the verification, not a second enforce pass."
    )
