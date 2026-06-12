"""Guard: any workflow that pushes to MAIN must check out with the release deploy key.

Run 27445202166 (post-#269): rebuild-bundle.yml pushed to main with the default
GITHUB_TOKEN and was rejected by main's ruleset (GH013, required status checks) --
leaving main's committed bundle zip stale against the app's #includes until a
hotfix. The incompatibility was knowable statically: release.yml documents that a
GITHUB_TOKEN push to main is rejected and pushes via secrets.RELEASE_DEPLOY_KEY.
This test makes that knowledge a PR-blocking check, so a workflow that would fail
its first post-merge push fails CI here, BEFORE it can be merged.

Static limits, stated honestly: this proves the right credential is WIRED, not
that the key itself works -- that is only proven by a real push (release.yml
exercises it on every release).
"""
import re
from pathlib import Path

WORKFLOWS = Path(__file__).resolve().parent.parent / ".github" / "workflows"

# A push that targets the protected main branch, in any of the forms our
# workflows use (direct, HEAD:main, with options between push and the remote).
PUSH_TO_MAIN = re.compile(r"git push\b[^\n|&;]*\borigin\b[^\n|&;]*\b(?:HEAD:)?main\b")
DEPLOY_KEY = "ssh-key: ${{ secrets.RELEASE_DEPLOY_KEY }}"


def test_every_workflow_pushing_to_main_uses_the_release_deploy_key():
    offenders = []
    for wf in sorted(WORKFLOWS.glob("*.yml")):
        text = wf.read_text(encoding="utf-8")
        if PUSH_TO_MAIN.search(text) and DEPLOY_KEY not in text:
            offenders.append(wf.name)
    assert not offenders, (
        f"workflow(s) {offenders} push to main without checking out via "
        f"'{DEPLOY_KEY}'. main's ruleset rejects a GITHUB_TOKEN push (GH013, run "
        "27445202166) -- the push will fail on its first post-merge run. Add the "
        "deploy-key checkout the way release.yml and rebuild-bundle.yml do."
    )


def test_guard_actually_sees_the_known_main_pushers():
    """Self-check: the regex must MATCH the workflows known to push to main --
    a regex that silently matches nothing would make the guard vacuous."""
    matched = [
        wf.name for wf in sorted(WORKFLOWS.glob("*.yml"))
        if PUSH_TO_MAIN.search(wf.read_text(encoding="utf-8"))
    ]
    for known in ("release.yml", "rebuild-bundle.yml"):
        assert known in matched, (
            f"{known} no longer matches the push-to-main pattern -- either its push "
            "moved/changed shape (update PUSH_TO_MAIN) or it genuinely stopped "
            "pushing to main (update this list)."
        )
