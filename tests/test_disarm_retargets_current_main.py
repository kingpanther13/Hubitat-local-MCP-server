"""pytest: the disarm re-targets the restore at CURRENT canonical main before flipping the flag.

Regression guard for ``.github/scripts/mcp_disarm_watchdog.sh``. The arm pins the restore manifest to
main-as-of-arm (immutable shas/ URLs), so a merge landing mid-run used to leave the restored hub one
merge behind current main -- the next non-library PR's deploy then skipped its bundle install on
byte-equality with (new) main while the hub still carried the OLD main's libraries. The disarm now
re-resolves main at teardown and rewrites the manifest's app/bundle URLs + expected chars +
canonicalMainSha to the current SHA, forcing the run-scoped bundle-state marker to "changed" when the
bundle bytes moved (so the watchdog's restore cannot skip the bundle leg under the stale marker).
These tests pin the load-bearing pieces so an edit cannot silently drop the re-target.
"""

from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "mcp_disarm_watchdog.sh"


def test_disarm_resolves_current_main_sha():
    text = SCRIPT.read_text()
    assert "git ls-remote" in text and "refs/heads/main" in text, (
        f"{SCRIPT.name} no longer resolves current main's SHA at disarm time -- the restore would "
        "reinstall main-as-of-arm, leaving the hub one merge behind whenever a merge lands mid-run."
    )


def test_disarm_reresolves_the_bundle_on_bundle_artifacts():
    text = SCRIPT.read_text()
    assert "/bundle-artifacts/shas/" in text and "/bundle-artifacts/branches/main/" in text, (
        f"{SCRIPT.name} no longer re-resolves the bundle on the bundle-artifacts branch for the "
        "current main SHA -- a re-targeted restore would install the arm-time bundle bytes."
    )


def test_disarm_forces_the_bundle_state_marker_when_bytes_moved():
    """An apps-new/libraries-old restore is the staleness class this re-target closes: the deploy's
    run-scoped "<runId>:unchanged" marker lets the watchdog skip the bundle leg, which is only valid
    against main-at-DEPLOY-time bytes. When the re-target finds the bundle bytes moved, it MUST force
    the marker to "changed" before flipping the flag."""
    text = SCRIPT.read_text()
    assert '"e2e-pr-bundle-state.txt"' in text and ":changed" in text, (
        f"{SCRIPT.name} dropped the bundle-state marker force-write -- a re-targeted restore could "
        "skip the bundle leg under the stale 'unchanged' marker while the apps land at the new main."
    )


def test_disarm_falls_back_to_the_arm_time_manifest():
    """Every re-target obstacle must restore SOME main rather than fail the teardown or hand the
    watchdog a torn manifest."""
    text = SCRIPT.read_text()
    assert text.count("keeping the arm-time manifest") >= 4, (
        f"{SCRIPT.name} lost the arm-time-manifest fallback on a re-target obstacle -- a partial "
        "rewrite must never reach the watchdog (apps at one SHA, bundle at another)."
    )


def test_disarm_pins_fetched_urls_to_the_expected_host_owner_repo():
    """SSRF guard: the app/bundle URLs are read back from the hub flag manifest (untrusted), so each
    entry must be validated against the raw.githubusercontent.com/<owner>/<repo>/ prefix the first app
    url proved BEFORE any curl, and every fetch must run --max-redirs 0 so a 3xx cannot bounce off-host.
    The substring SHA check alone (a url like https://evil/<sha>/x) is not enough."""
    text = SCRIPT.read_text()
    assert 'EXPECT_PREFIX="https://raw.githubusercontent.com/${GH_OWNER}/${GH_REPO}/"' in text, (
        f"{SCRIPT.name} dropped the host+owner+repo prefix pin -- a poisoned apps[]/bundles[] entry "
        "could redirect a re-target fetch to an attacker-controlled host (SSRF)."
    )
    assert text.count('"$EXPECT_PREFIX"*) ;;') >= 2, (
        f"{SCRIPT.name} no longer validates BOTH the app and bundle urls against EXPECT_PREFIX before "
        "fetching them."
    )
    assert "curl -fsSL --max-time" not in text, (
        f"{SCRIPT.name} has a re-target curl without --max-redirs 0 -- a redirect could bounce the "
        "fetch off the pinned host (redirect-based SSRF)."
    )
