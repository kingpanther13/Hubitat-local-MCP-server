"""pytest: the disarm "no stale code" jq keep-set filter actually detects an orphan.

Regression guard for the jq idiom bug where ``select(($keep | index(.)) == null)``
rebound ``.`` to ``$keep`` inside ``index()`` and made the stale list ALWAYS empty --
so ``assert_no_stale_mcp_code`` in ``.github/scripts/mcp_disarm_watchdog.sh`` (the hard
gate behind restorePackage's best-effort cleanup) passed VACUOUSLY even on a hub still
carrying a PR's bundle/library. This extracts the REAL jq expressions from the script and
runs them against a dirty + clean input, so any regression to a vacuous filter fails here.
"""

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "mcp_disarm_watchdog.sh"

_JQ = shutil.which("jq")


def _extract_filter(arr: str) -> str:
    """Pull the single-quoted jq program that builds the stale list for `.<arr>` out of the script."""
    text = SCRIPT.read_text()
    # The stale-detector filter for `.<arr>` -- distinguished from the earlier keep-set BUILDER
    # ('[.bundles[]? | {namespace, name}]') by its trailing `| .name]` projection.
    m = re.search(r"'(\[\." + arr + r"\[\]\?[^']*\| \.name\])'", text)
    assert m, f"could not find the {arr} stale-detector jq filter in {SCRIPT}"
    return m.group(1)


def _run(jq_filter: str, keep, doc) -> list:
    assert _JQ is not None  # callers are guarded by skipif(_JQ is None)
    out = subprocess.run(
        [_JQ, "-c", "--argjson", "keep", json.dumps(keep), jq_filter],
        input=json.dumps(doc),
        capture_output=True,
        text=True,
        check=True,
    )
    return json.loads(out.stdout)


@pytest.mark.skipif(_JQ is None, reason="jq not installed")
@pytest.mark.parametrize("arr", ["bundles", "libraries"])
def test_nostale_filter_detects_orphan_and_passes_clean(arr):
    jq_filter = _extract_filter(arr)
    keep = [{"namespace": "mcp", "name": "mcp_keep"}]

    # An mcp-namespace entry NOT in the keep-set must be reported as stale; a different
    # namespace must be ignored, and the kept entry must not be flagged.
    dirty = {
        arr: [
            {"namespace": "mcp", "name": "mcp_keep", "id": "1"},
            {"namespace": "mcp", "name": "mcp_orphan", "id": "2"},
            {"namespace": "other", "name": "unrelated", "id": "3"},
        ]
    }
    assert _run(jq_filter, keep, dirty) == ["mcp_orphan"], (
        f"the {arr} no-stale filter did not detect the orphan -- it may have regressed to the "
        "vacuous `index(.)` form that always returns an empty stale list"
    )

    # A clean hub (only the kept entry present) must report nothing.
    clean = {arr: [{"namespace": "mcp", "name": "mcp_keep", "id": "1"}]}
    assert _run(jq_filter, keep, clean) == []
