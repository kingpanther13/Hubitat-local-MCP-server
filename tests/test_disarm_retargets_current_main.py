"""pytest: the disarm re-targets the restore at CURRENT canonical main before flipping the flag.

Regression guard for ``.github/scripts/mcp_disarm_watchdog.sh``. The arm pins the restore manifest to
main-as-of-arm (immutable shas/ URLs), so a merge landing mid-run used to leave the restored hub one
merge behind current main -- the next non-library PR's deploy then skipped its bundle install on
byte-equality with (new) main while the hub still carried the OLD main's libraries. The disarm now
re-resolves main at teardown and rewrites the app URLs (+ re-measured chars), the bundle URLs, and
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
    the marker to "changed" before flipping the flag. Pin the three load-bearing pieces: the force is
    GATED on BUNDLE_BYTES_MOVED (forcing it on every re-target would needlessly reinstall + recompile
    on clean same-bytes restores), the content is the run-scoped ${GITHUB_RUN_ID}:changed (the dead-man
    skip compares against <runId>:unchanged), and the write asserts TOOL-LEVEL success (the helper
    returns rc 0 on a {success:false} body, so exit status alone would let a silent no-op through)."""
    text = SCRIPT.read_text()
    assert '"e2e-pr-bundle-state.txt"' in text, (
        f"{SCRIPT.name} dropped the bundle-state marker force-write -- a re-targeted restore could "
        "skip the bundle leg under the stale 'unchanged' marker while the apps land at the new main."
    )
    assert 'BUNDLE_BYTES_MOVED" = "true" ]' in text, (
        f"{SCRIPT.name}: the marker force is no longer gated on BUNDLE_BYTES_MOVED -- forcing 'changed' "
        "on every re-target makes clean same-bytes restores needlessly reinstall the bundle + recompile."
    )
    assert '"${GITHUB_RUN_ID}:changed"' in text, (
        f"{SCRIPT.name}: the forced marker is no longer run-scoped (${{GITHUB_RUN_ID}}:changed) -- a bare "
        "value the dead-man's <runId>: comparison would not key to this run."
    )
    assert ".success // false" in text, (
        f"{SCRIPT.name}: the marker force-write no longer asserts tool-level success -- mcp_tool_call_text "
        "returns rc 0 on a {success:false} body, so a silent write rejection would leave the marker stale."
    )


def test_disarm_falls_back_to_the_arm_time_manifest_on_every_obstacle():
    """Every re-target obstacle must restore SOME main rather than fail the teardown or hand the
    watchdog a torn manifest (apps at one SHA, bundle at another). Pin EACH obstacle's fallback by its
    distinct message so deleting any single one fails a NAMED assertion -- a bare count floor let up to
    half of them be silently removed."""
    text = SCRIPT.read_text()
    obstacles = [
        "is not a SHA-anchored raw.githubusercontent URL",  # outer: the top-level app url
        "does not embed the arm-time SHA",                   # app url cannot be re-anchored
        "could not measure",                                 # app source re-measure failed
        "carries no apps[] entries",                         # old-format flag, no apps[]
        "no bundle-artifacts entry for",                     # bundle not published at current SHA
        "could not download the arm-time/current bundle",    # bundle byte-compare fetch failed
        "could not force the bundle-state marker",           # marker force rejected
    ]
    for o in obstacles:
        assert o in text, (
            f"{SCRIPT.name} dropped the arm-time-manifest fallback for re-target obstacle: {o!r}."
        )
    # Both EXPECT_PREFIX rejections (app AND bundle) keep their fallback.
    assert text.count("is not under the expected") >= 2, (
        f"{SCRIPT.name} lost an EXPECT_PREFIX-rejection fallback (need both the app and bundle url checks)."
    )


def test_disarm_retarget_rewrites_the_singular_app_and_canonical_sha():
    """The success-branch rewrite is atomic over apps[], the singular .app (the restore's fallback
    selector AND the disarm read-back's classId assert), bundles[], and canonicalMainSha. The read-back
    only checks .app.classId -- invariant arm->current (same parent class) -- so a dropped app:($apps[0])
    (leaving .app.url at the arm-time SHA) or a dropped CANONICAL_MAIN_SHA rewrite would NOT be caught
    there. Pin both here."""
    text = SCRIPT.read_text()
    assert "app:($apps[0])" in text, (
        f"{SCRIPT.name}: the re-target no longer rewrites the singular .app alongside apps[] -- .app.url "
        "would stay at the arm-time SHA, a torn manifest the classId read-back cannot detect."
    )
    assert 'CANONICAL_MAIN_SHA="$CUR_MAIN_SHA"' in text, (
        f"{SCRIPT.name}: the re-target no longer updates CANONICAL_MAIN_SHA -- the flag's canonicalMainSha "
        "would stay arm-time while the URLs moved to current main."
    )


def test_disarm_every_retarget_fetch_blocks_redirects():
    """SSRF: the app/bundle URLs are read back from the hub flag manifest, so each entry is validated
    against the raw.githubusercontent.com/<owner>/<repo>/ prefix the first app url proved BEFORE any
    curl, and every re-target fetch must carry --max-redirs 0 so a 3xx cannot bounce off-host. The
    redirect guard is asserted POSITIVELY per fetch -- a brittle "no curl -fsSL --max-time" substring
    would be slipped by a reordered flag or a newly-added unguarded fetch."""
    text = SCRIPT.read_text()
    assert 'EXPECT_PREFIX="https://raw.githubusercontent.com/${GH_OWNER}/${GH_REPO}/"' in text, (
        f"{SCRIPT.name} dropped the host+owner+repo prefix pin -- a poisoned apps[]/bundles[] entry "
        "could redirect a re-target fetch to an attacker-controlled host (SSRF)."
    )
    assert text.count('"$EXPECT_PREFIX"*) ;;') >= 2, (
        f"{SCRIPT.name} no longer validates BOTH the app and bundle urls against EXPECT_PREFIX before "
        "fetching them."
    )
    fetch_tokens = ('"$N_URL"', '"$B_URL"', '"$N_BURL"', "${CAND}")
    fetch_lines = [
        ln for ln in text.splitlines() if "curl" in ln and any(tok in ln for tok in fetch_tokens)
    ]
    assert len(fetch_lines) >= 3, (
        f"{SCRIPT.name}: the SSRF test can no longer locate the re-target curl fetches "
        "($N_URL/$B_URL/$N_BURL/${CAND}.size) -- renamed vars? found "
        f"{len(fetch_lines)} line(s)."
    )
    for ln in fetch_lines:
        assert ln.count("--max-redirs 0") >= ln.count("curl"), (
            f"{SCRIPT.name}: a re-target fetch lacks --max-redirs 0 (redirect-based SSRF):\n  {ln.strip()}"
        )
