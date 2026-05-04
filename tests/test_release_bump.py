"""pytest unit tests for .github/scripts/release_bump.py

Covers: parse_release_notes, split_release_blocks, filter_same_minor,
        manifest_block_from_bullets, and bump_manifest (integration scenarios).
"""

import json
import sys
import os

# Make release_bump importable without installing it.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".github", "scripts"))

import pytest
import release_bump as rb


# ---------------------------------------------------------------------------
# parse_release_notes
# ---------------------------------------------------------------------------

def test_parse_release_notes_basic_two_bullets():
    """Two top-level bullets under a standard heading are both returned."""
    body = "## Release Notes\n- first\n- second"
    assert rb.parse_release_notes(body) == ["- first", "- second"]


def test_parse_release_notes_lenient_lowercase_colon():
    """Heading matched case-insensitively with optional trailing colon."""
    body = "## release notes:\n- ok"
    assert rb.parse_release_notes(body) == ["- ok"]


def test_parse_release_notes_lenient_different_level():
    """Any heading level (h3 uppercase) is accepted."""
    body = "### RELEASE NOTES\n- ok"
    assert rb.parse_release_notes(body) == ["- ok"]


def test_parse_release_notes_one_level_nested():
    """One level of sub-bullets is preserved inside the parent bullet."""
    body = "## Release Notes\n- main\n  - sub\n- another"
    assert rb.parse_release_notes(body) == ["- main\n  - sub", "- another"]


def test_parse_release_notes_two_levels_nested():
    """Two levels of sub-bullet nesting are both preserved."""
    body = "## Release Notes\n- main\n  - sub\n    - deeper\n- next"
    assert rb.parse_release_notes(body) == ["- main\n  - sub\n    - deeper", "- next"]


def test_parse_release_notes_prose_between_bullets_skipped():
    """Prose lines between bullets are silently skipped, not section-terminating."""
    body = "## Release Notes\n- first\nsome prose here\n- second"
    assert rb.parse_release_notes(body) == ["- first", "- second"]


def test_parse_release_notes_blank_lines_between_bullets_skipped():
    """Blank lines between bullets are silently skipped."""
    body = "## Release Notes\n- first\n\n- second"
    assert rb.parse_release_notes(body) == ["- first", "- second"]


def test_parse_release_notes_terminates_at_next_heading():
    """A markdown heading after the section terminates bullet collection."""
    body = "## Release Notes\n- ok\n## Next section\n- ignored"
    assert rb.parse_release_notes(body) == ["- ok"]


def test_parse_release_notes_no_section_returns_empty():
    """Returns [] when no Release Notes section is present."""
    assert rb.parse_release_notes("No section here at all") == []


def test_parse_release_notes_empty_string_returns_empty():
    """Returns [] for empty string body."""
    assert rb.parse_release_notes("") == []


def test_parse_release_notes_none_returns_empty():
    """Returns [] for None body."""
    assert rb.parse_release_notes(None) == []


def test_parse_release_notes_section_only_prose_returns_empty():
    """Returns [] when section exists but contains only prose, no bullets."""
    body = "## Release Notes\nJust some prose.\nMore prose."
    assert rb.parse_release_notes(body) == []


def test_parse_release_notes_star_bullet_accepted():
    """Asterisk (*) bullet marker is accepted in addition to dash (-)."""
    body = "## Release Notes\n* one\n* two"
    assert rb.parse_release_notes(body) == ["* one", "* two"]


def test_parse_release_notes_orphan_indented_bullet_skipped():
    """An indented bullet with no preceding top-level bullet is silently skipped."""
    body = "## Release Notes\n  - orphan\n- real"
    assert rb.parse_release_notes(body) == ["- real"]


# ---------------------------------------------------------------------------
# split_release_blocks
# ---------------------------------------------------------------------------

def test_split_release_blocks_multi_block_order():
    """Multi-block input returns list of (version, block_text) in document order."""
    text = "v0.11.1 - 2026-05-05\n- fix one\n\nv0.11.0 - 2026-05-04\n- feat two"
    blocks = rb.split_release_blocks(text)
    assert len(blocks) == 2
    assert blocks[0][0] == "0.11.1"
    assert blocks[1][0] == "0.11.0"
    assert "fix one" in blocks[0][1]
    assert "feat two" in blocks[1][1]


def test_split_release_blocks_single_block():
    """Single-block input returns a one-element list."""
    text = "v1.2.3 - 2026-01-01\n- something"
    blocks = rb.split_release_blocks(text)
    assert len(blocks) == 1
    assert blocks[0][0] == "1.2.3"


def test_split_release_blocks_empty_input():
    """Empty string input returns []."""
    assert rb.split_release_blocks("") == []


def test_split_release_blocks_tolerates_extra_blank_lines():
    """Extra blank lines between blocks are tolerated; count still correct."""
    text = "v0.2.0 - 2026-02-01\n- b\n\n\n\nv0.1.0 - 2026-01-01\n- a"
    blocks = rb.split_release_blocks(text)
    assert len(blocks) == 2
    assert blocks[0][0] == "0.2.0"
    assert blocks[1][0] == "0.1.0"


def test_split_release_blocks_legacy_runon_prose():
    """Legacy run-on prose entries (no bullet structure) are accepted by the splitter."""
    text = "v0.10.1 - lots of run-on text / more stuff.\nv0.10.0 - other stuff."
    blocks = rb.split_release_blocks(text)
    assert len(blocks) == 2
    assert blocks[0][0] == "0.10.1"
    assert blocks[1][0] == "0.10.0"


# ---------------------------------------------------------------------------
# filter_same_minor
# ---------------------------------------------------------------------------

def test_filter_same_minor_keeps_matching():
    """Blocks matching new_version's MAJOR.MINOR are kept."""
    blocks = [("0.11.1", "v0.11.1 - x"), ("0.11.0", "v0.11.0 - y")]
    result = rb.filter_same_minor(blocks, "0.11.2")
    assert [v for v, _ in result] == ["0.11.1", "0.11.0"]


def test_filter_same_minor_drops_other_minor():
    """Blocks from a different minor are dropped."""
    blocks = [("0.11.0", "v0.11.0 - a"), ("0.10.5", "v0.10.5 - b")]
    result = rb.filter_same_minor(blocks, "0.11.1")
    assert [v for v, _ in result] == ["0.11.0"]


def test_filter_same_minor_drops_other_major():
    """Blocks from a different major are dropped."""
    blocks = [("0.11.0", "v0.11.0 - a"), ("1.0.0", "v1.0.0 - b")]
    result = rb.filter_same_minor(blocks, "0.11.1")
    assert [v for v, _ in result] == ["0.11.0"]


def test_filter_same_minor_empty_input():
    """Empty input returns []."""
    assert rb.filter_same_minor([], "1.0.0") == []


def test_filter_same_minor_major_zero_zero():
    """new_version=1.0.0: only 1.0.x blocks are kept."""
    blocks = [
        ("1.0.2", "v1.0.2 - a"),
        ("1.0.1", "v1.0.1 - b"),
        ("1.0.0", "v1.0.0 - c"),
        ("0.11.5", "v0.11.5 - d"),
    ]
    result = rb.filter_same_minor(blocks, "1.0.3")
    assert [v for v, _ in result] == ["1.0.2", "1.0.1", "1.0.0"]


# ---------------------------------------------------------------------------
# manifest_block_from_bullets
# ---------------------------------------------------------------------------

def test_manifest_block_from_bullets_no_notes():
    """Single PR with no sub-bullets produces header line only."""
    bullets = ["- Fix something ([#10](https://example.com/10), @alice)"]
    result = rb.manifest_block_from_bullets(bullets, "0.11.1", "2026-05-05")
    assert result == "v0.11.1 - 2026-05-05\n- Fix something (#10)"


def test_manifest_block_from_bullets_one_level_nested():
    """One level of nested sub-bullets is preserved in the output."""
    bullets = [
        "- Title ([#20](https://example.com/20), @bob)\n  - sub note one\n  - sub note two"
    ]
    result = rb.manifest_block_from_bullets(bullets, "0.11.1", "2026-05-05")
    assert result == (
        "v0.11.1 - 2026-05-05\n"
        "- Title (#20)\n"
        "  - sub note one\n"
        "  - sub note two"
    )


def test_manifest_block_from_bullets_two_level_nested():
    """Two levels of sub-bullet nesting both pass through unchanged."""
    bullets = [
        "- Title ([#30](https://example.com/30), @carol)\n  - sub\n    - deep"
    ]
    result = rb.manifest_block_from_bullets(bullets, "0.11.1", "2026-05-05")
    assert result == (
        "v0.11.1 - 2026-05-05\n"
        "- Title (#30)\n"
        "  - sub\n"
        "    - deep"
    )


def test_manifest_block_from_bullets_multi_pr():
    """Multiple PRs are joined with a single newline between them."""
    bullets = [
        "- PR A ([#1](https://example.com/1), @x)",
        "- PR B ([#2](https://example.com/2), @y)",
    ]
    result = rb.manifest_block_from_bullets(bullets, "0.11.1", "2026-05-05")
    lines = result.splitlines()
    assert lines[0] == "v0.11.1 - 2026-05-05"
    assert "- PR A (#1)" in result
    assert "- PR B (#2)" in result


def test_manifest_block_from_bullets_url_stripped_no_author():
    """URL-only ref (no @author) is also collapsed to plain (#N)."""
    bullets = ["- Do stuff ([#143](https://example.com/143))"]
    result = rb.manifest_block_from_bullets(bullets, "0.11.0", "2026-05-04")
    assert "(#143)" in result
    assert "https" not in result
    assert "@" not in result


def test_manifest_block_from_bullets_no_pr_ref_passthrough():
    """A bullet with no PR ref (fallback '- PR #N') passes through unchanged."""
    bullets = ["- PR #99"]
    result = rb.manifest_block_from_bullets(bullets, "0.11.0", "2026-05-04")
    assert "- PR #99" in result


# ---------------------------------------------------------------------------
# compute_next — single point of failure for every release version string
# ---------------------------------------------------------------------------

def test_compute_next_patch_increments_patch():
    """release:patch increments the patch component only."""
    assert rb.compute_next("0.11.0", "release:patch") == "0.11.1"


def test_compute_next_minor_resets_patch():
    """release:minor increments minor and resets patch to 0 (not preserving)."""
    assert rb.compute_next("0.11.5", "release:minor") == "0.12.0"


def test_compute_next_major_resets_minor_and_patch():
    """release:major increments major and resets BOTH minor and patch to 0."""
    assert rb.compute_next("0.11.5", "release:major") == "1.0.0"


def test_compute_next_patch_from_zeroes():
    """Patch from a fresh 0.0.0 baseline."""
    assert rb.compute_next("0.0.0", "release:patch") == "0.0.1"


def test_compute_next_patch_at_double_digit():
    """No string-based off-by-one at the 9 → 10 boundary."""
    assert rb.compute_next("9.9.9", "release:patch") == "9.9.10"


def test_compute_next_zero_to_one_major():
    """0.x → 1.0.0 transition (the user-flagged accumulation reset boundary)."""
    assert rb.compute_next("0.11.5", "release:major") == "1.0.0"


def test_compute_next_unknown_label_raises_value_error():
    """Unknown labels raise ValueError instead of silently bumping patch."""
    with pytest.raises(ValueError, match="Unknown release label"):
        rb.compute_next("0.11.0", "release:hotfix")


def test_compute_next_empty_label_raises_value_error():
    """Empty label is also rejected with ValueError."""
    with pytest.raises(ValueError):
        rb.compute_next("0.11.0", "")


# ---------------------------------------------------------------------------
# bump_manifest — integration tests (uses tmp_path fixture)
# ---------------------------------------------------------------------------

def _make_manifest(tmp_path, monkeypatch, version="0.11.0", release_notes=""):
    """Write a minimal packageManifest.json to tmp_path and redirect rb.MANIFEST.

    Uses monkeypatch.setattr so the module attribute auto-restores after the
    test — direct assignment leaks across tests and points at deleted paths
    once tmp_path is cleaned up.
    """
    data = {
        "packageName": "Test",
        "author": "tester",
        "version": version,
        "minimumHEVersion": "2.2",
        "dateReleased": "2026-01-01",
        "releaseNotes": release_notes,
        "apps": [],
        "drivers": [],
    }
    manifest_path = tmp_path / "packageManifest.json"
    manifest_path.write_text(json.dumps(data, indent=4) + "\n")
    monkeypatch.setattr(rb, "MANIFEST", manifest_path)
    return manifest_path


def _read_manifest(tmp_path):
    return json.loads((tmp_path / "packageManifest.json").read_text())


def _block(version, date, lines):
    """Build a realistic manifest block string."""
    body = "\n".join(f"- {l}" for l in lines)
    return f"v{version} - {date}\n{body}"


def test_bump_manifest_patch_keeps_same_minor(tmp_path, monkeypatch):
    """release:patch with one prior same-minor entry retains it below the new block."""
    _make_manifest(tmp_path, monkeypatch, "0.11.0",
                   release_notes="v0.11.0 - 2026-05-04\n- old fix (#100)")
    new_block = _block("0.11.1", "2026-05-05", ["new fix (#101)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    assert notes.index("v0.11.1") < notes.index("v0.11.0"), "0.11.1 must come before 0.11.0"
    assert "v0.11.0" in notes


def test_bump_manifest_patch_keeps_multiple_same_minor(tmp_path, monkeypatch):
    """release:patch with two prior same-minor entries retains both in order."""
    prior = "v0.11.1 - 2026-05-05\n- b (#102)\n\nv0.11.0 - 2026-05-04\n- a (#100)"
    _make_manifest(tmp_path, monkeypatch, "0.11.1", release_notes=prior)
    new_block = _block("0.11.2", "2026-05-06", ["c (#103)"])
    rb.bump_manifest("0.11.2", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    pos_2 = notes.index("v0.11.2")
    pos_1 = notes.index("v0.11.1")
    pos_0 = notes.index("v0.11.0")
    assert pos_2 < pos_1 < pos_0, "versions must appear in descending order"


def test_bump_manifest_patch_drops_older_minors(tmp_path, monkeypatch):
    """release:patch drops v0.10.x and v0.9.x entries, keeps only same-minor."""
    prior = (
        "v0.11.0 - 2026-05-04\n- x (#100)\n\n"
        "v0.10.5 - 2026-04-01\n- y (#90)\n\n"
        "v0.10.0 - 2026-03-01\n- z (#80)\n\n"
        "v0.9.7 - 2026-02-01\n- w (#70)"
    )
    _make_manifest(tmp_path, monkeypatch, "0.11.0", release_notes=prior)
    new_block = _block("0.11.1", "2026-05-05", ["new (#101)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    assert "v0.11.1" in notes
    assert "v0.11.0" in notes
    assert "v0.10.5" not in notes
    assert "v0.10.0" not in notes
    assert "v0.9.7" not in notes


def test_bump_manifest_minor_wipes(tmp_path, monkeypatch):
    """release:minor replaces the field entirely with just the new block."""
    prior = "v0.11.5 - 2026-05-04\n- a\n\nv0.11.0 - 2026-05-01\n- b"
    _make_manifest(tmp_path, monkeypatch, "0.11.5", release_notes=prior)
    new_block = _block("0.12.0", "2026-05-05", ["minor bump (#200)"])
    rb.bump_manifest("0.12.0", "release:minor", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    assert "v0.12.0" in notes
    assert "v0.11.5" not in notes
    assert "v0.11.0" not in notes


def test_bump_manifest_major_wipes(tmp_path, monkeypatch):
    """release:major replaces the field entirely with just the new block."""
    prior = "v0.11.5 - 2026-05-04\n- a\n\nv0.11.0 - 2026-05-01\n- b"
    _make_manifest(tmp_path, monkeypatch, "0.11.5", release_notes=prior)
    new_block = _block("1.0.0", "2026-05-05", ["major release (#300)"])
    rb.bump_manifest("1.0.0", "release:major", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    assert "v1.0.0" in notes
    assert "v0.11.5" not in notes
    assert "v0.11.0" not in notes


def test_bump_manifest_fresh_manifest_empty_notes(tmp_path, monkeypatch):
    """Patch bump on a manifest with empty releaseNotes produces only the new block."""
    _make_manifest(tmp_path, monkeypatch, "0.11.0", release_notes="")
    new_block = _block("0.11.1", "2026-05-05", ["first patch (#101)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    assert m["releaseNotes"] == new_block


def test_bump_manifest_first_run_regression_anchor(tmp_path, monkeypatch):
    """Regression anchor: real v0.11.0 manifest state; bump to 0.11.1 adds on top."""
    real_notes = (
        'v0.11.0 - 2026-05-04\n'
        '- fix(manage_virtual_device): rename "Virtual Presence Sensor" enum entry '
        'to "Virtual Presence" (#144)\n'
        '- feat(developer-mode): add manage_mcp_self gateway + delete_variable (#145)'
    )
    _make_manifest(tmp_path, monkeypatch, "0.11.0", release_notes=real_notes)
    new_block = _block("0.11.1", "2026-05-05", ["new feature (#146)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]
    # New block is on top
    assert notes.index("v0.11.1") < notes.index("v0.11.0")
    # Original v0.11.0 block is verbatim
    assert real_notes in notes


def test_bump_manifest_updates_version_and_date(tmp_path, monkeypatch):
    """bump_manifest writes new_version and today's date into the JSON."""
    from datetime import datetime, timezone
    _make_manifest(tmp_path, monkeypatch, "0.11.0", release_notes="")
    new_block = _block("0.11.1", "2026-05-05", ["something (#101)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    assert m["version"] == "0.11.1"
    assert m["dateReleased"] == today


def test_bump_manifest_null_release_notes_no_crash(tmp_path, monkeypatch):
    """bump_manifest handles null releaseNotes in the manifest without crashing."""
    data = {
        "packageName": "Test",
        "author": "tester",
        "version": "0.11.0",
        "minimumHEVersion": "2.2",
        "dateReleased": "2026-01-01",
        "releaseNotes": None,
        "apps": [],
        "drivers": [],
    }
    manifest_path = tmp_path / "packageManifest.json"
    manifest_path.write_text(json.dumps(data, indent=4) + "\n")
    monkeypatch.setattr(rb, "MANIFEST", manifest_path)
    new_block = _block("0.11.1", "2026-05-05", ["fix (#101)"])
    # Must not raise
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    assert "v0.11.1" in m["releaseNotes"]


def test_bump_manifest_legacy_blob_shrinkage_regression_anchor(tmp_path, monkeypatch):
    """Regression anchor for the headline 65 KB → small shrinkage.

    Locks in two properties simultaneously:
    1. The resulting releaseNotes is dramatically smaller than the legacy blob
       (a refactor that re-introduces accumulation across minors would balloon
       the field again — this assertion catches that without depending on an
       exact byte count).
    2. None of the older-minor versions appear in the result. A regression
       that changes filter_same_minor to filter_same_major or no-filter would
       leave v0.10.x / v0.9.x entries in the field; this assertion catches it.

    Uses a synthesized ~5 KB legacy blob covering 8 minor lines (v0.4 through
    v0.11) — proportionally the same shape as the real 65 KB legacy state, but
    small enough to read inline.
    """
    legacy_blob = "\n\n".join([
        "v0.11.0 - 2026-05-04\n- new format starts here (#144)",
        "v0.10.5 - 2026-04-15\n- some old run-on prose / more old prose / "
        "still more / and so on " * 20,
        "v0.10.1 - 2026-04-01\n- another old entry / with run-on text " * 20,
        "v0.10.0 - 2026-03-20\n- old major-zero-ten entry / etc " * 15,
        "v0.9.7 - 2026-02-15\n- nine seven entry / lots of prose " * 15,
        "v0.9.0 - 2026-02-01\n- nine zero entry / blah blah " * 15,
        "v0.8.7 - 2026-01-15\n- eight seven / etc " * 15,
        "v0.4.5 - 2025-08-01\n- ancient entry / etc " * 15,
    ])
    legacy_size = len(legacy_blob)
    assert legacy_size > 4000, "fixture should be a meaningful blob, not tiny"

    _make_manifest(tmp_path, monkeypatch, "0.11.0", release_notes=legacy_blob)
    new_block = _block("0.11.1", "2026-05-05", ["new author-curated entry (#150)"])
    rb.bump_manifest("0.11.1", "release:patch", new_block)
    m = _read_manifest(tmp_path)
    notes = m["releaseNotes"]

    # Property 1: the resulting field is dramatically smaller — at least 5x
    # reduction, which any genuine fix to the accumulation will achieve.
    assert len(notes) < legacy_size / 5, (
        f"releaseNotes did not shrink: legacy {legacy_size} bytes, "
        f"after bump {len(notes)} bytes (need < {legacy_size // 5})"
    )

    # Property 2: every older-minor version is gone, the new one and same-minor
    # one are present.
    assert "v0.11.1" in notes
    assert "v0.11.0" in notes
    for older in ("v0.10.5", "v0.10.1", "v0.10.0", "v0.9.7", "v0.9.0",
                  "v0.8.7", "v0.4.5"):
        assert older not in notes, f"older minor {older} leaked through"
