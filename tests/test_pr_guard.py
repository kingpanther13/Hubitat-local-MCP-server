"""pytest unit tests for .github/scripts/pr_guard.py

Covers: extract_first, extract_version_history, and the pure logic of
check_bookkeeping via monkeypatching git and file reads.
"""

import re
import sys
import os

# Make pr_guard importable without installing it.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".github", "scripts"))

import pytest
import pr_guard


# ---------------------------------------------------------------------------
# extract_first
# ---------------------------------------------------------------------------

def test_extract_first_basic_match():
    """Returns the first capture group when the pattern matches."""
    result = pr_guard.extract_first("Version: 1.2.3", r"Version:\s*(\d+\.\d+\.\d+)")
    assert result == "1.2.3"


def test_extract_first_multiline_flag():
    """re.MULTILINE flag is forwarded correctly."""
    text = "preamble\n * Version: 4.5.6\nmore"
    result = pr_guard.extract_first(text, r"^\s*\*\s*Version:\s*(\d+\.\d+\.\d+)", re.MULTILINE)
    assert result == "4.5.6"


def test_extract_first_no_match_returns_none():
    """Returns None when the pattern does not match."""
    result = pr_guard.extract_first("nothing here", r"(\d+\.\d+\.\d+)")
    assert result is None


def test_extract_first_empty_string_returns_none():
    """Returns None for empty input string."""
    result = pr_guard.extract_first("", r"(\d+)")
    assert result is None


def test_extract_first_returns_first_capture_not_whole_match():
    """Returns only the capture group, not the whole match."""
    result = pr_guard.extract_first("prefix 99 suffix", r"prefix (\d+) suffix")
    assert result == "99"


# ---------------------------------------------------------------------------
# extract_version_history
# ---------------------------------------------------------------------------

def test_extract_version_history_basic():
    """Extracts content between '## Version History' and the next heading."""
    readme = "## Some Section\nfoo\n\n## Version History\n- v1.0 stuff\n\n## Next Section\nbar"
    result = pr_guard.extract_version_history(readme)
    assert "v1.0 stuff" in result
    assert "foo" not in result
    assert "bar" not in result


def test_extract_version_history_last_section():
    """Works when Version History is the last section (no trailing heading)."""
    readme = "## Intro\nsome text\n\n## Version History\n- v2.0 something\n- v1.0 old"
    result = pr_guard.extract_version_history(readme)
    assert "v2.0 something" in result
    assert "v1.0 old" in result


def test_extract_version_history_missing_returns_empty():
    """Returns empty string when there is no Version History section."""
    readme = "## Introduction\nnothing relevant\n\n## Usage\ncommands here"
    result = pr_guard.extract_version_history(readme)
    assert result == ""


def test_extract_version_history_empty_input():
    """Returns empty string for empty input."""
    result = pr_guard.extract_version_history("")
    assert result == ""


def test_extract_version_history_not_contaminated_by_other_sections():
    """Content from adjacent sections does not bleed into the extracted block."""
    readme = (
        "## Before\nshould not appear\n\n"
        "## Version History\nv3.0 release\n\n"
        "## After\nalso should not appear"
    )
    result = pr_guard.extract_version_history(readme)
    assert "should not appear" not in result
    assert "also should not appear" not in result
    assert "v3.0 release" in result


# ---------------------------------------------------------------------------
# check_bookkeeping — monkeypatched pure-logic tests
# ---------------------------------------------------------------------------

def _make_groovy(version: str) -> str:
    """Minimal groovy stub with the two version patterns pr_guard reads."""
    return (
        f" * Version: {version}\n"
        f"def currentVersion() {{\n"
        f'    return "{version}"\n'
        f"}}\n"
    )


def _make_manifest(version: str, date_released: str = "2026-01-01",
                   release_notes: str = "v{v} - 2026-01-01\n- initial") -> str:
    import json
    return json.dumps({
        "version": version,
        "dateReleased": date_released,
        "releaseNotes": release_notes.format(v=version),
    })


def test_check_bookkeeping_clean_pass(monkeypatch, tmp_path):
    """No errors when none of the bookkeeping fields changed."""
    version = "0.11.0"
    server_groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    manifest_text = _make_manifest(version)
    readme = "## Version History\n- v0.11.0 release\n"

    # Patch git (simulates base ref) and file reads (simulates working tree)
    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return server_groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return manifest_text
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return "## Changelog\n- 0.11.0 initial"
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    # Write matching files to tmp_path so ROOT reads return same content
    (tmp_path / "hubitat-mcp-server.groovy").write_text(server_groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(manifest_text)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("## Changelog\n- 0.11.0 initial")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert errors == []


def test_check_bookkeeping_version_changed(monkeypatch, tmp_path):
    """Error reported when groovy server version is bumped."""
    base_version = "0.11.0"
    new_version = "0.11.1"
    base_groovy = _make_groovy(base_version)
    new_groovy = _make_groovy(new_version)
    rule_groovy = f" * Version: {base_version}\n"
    manifest_text = _make_manifest(base_version)
    readme = "## Version History\n- unchanged\n"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return base_groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return manifest_text
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return ""
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(new_groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(manifest_text)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert any("hubitat-mcp-server.groovy" in e and "server header comment" in e
               for e in errors)


def test_check_bookkeeping_manifest_version_changed(monkeypatch, tmp_path):
    """Error reported when packageManifest.json 'version' field changed."""
    version = "0.11.0"
    groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    base_manifest = _make_manifest(version)

    import json
    new_manifest_data = json.loads(base_manifest)
    new_manifest_data["version"] = "0.11.1"
    new_manifest = json.dumps(new_manifest_data)

    readme = "## Version History\n- unchanged\n"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return base_manifest
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return ""
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(new_manifest)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert any("packageManifest.json" in e and "'version'" in e for e in errors)


def test_check_bookkeeping_readme_version_history_changed(monkeypatch, tmp_path):
    """Error reported when README.md Version History section changed."""
    version = "0.11.0"
    groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    manifest = _make_manifest(version)
    base_readme = "## Version History\n- v0.11.0 original\n\n## Other\ncontent"
    new_readme = "## Version History\n- v0.11.0 original\n- v0.11.1 new\n\n## Other\ncontent"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return manifest
        if path == "README.md":
            return base_readme
        if path == "CHANGELOG.md":
            return ""
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(manifest)
    (tmp_path / "README.md").write_text(new_readme)
    (tmp_path / "CHANGELOG.md").write_text("")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert any("README.md" in e and "Version History" in e for e in errors)


def test_check_bookkeeping_changelog_changed(monkeypatch, tmp_path):
    """Error reported when CHANGELOG.md is modified (and base was non-empty)."""
    version = "0.11.0"
    groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    manifest = _make_manifest(version)
    readme = "## Version History\n- unchanged\n"
    base_changelog = "## Changelog\n- 0.11.0 initial"
    new_changelog = "## Changelog\n- 0.11.0 initial\n- 0.11.1 added"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return manifest
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return base_changelog
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(manifest)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text(new_changelog)

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert any("CHANGELOG.md" in e for e in errors)


def test_check_bookkeeping_changelog_new_file_no_error(monkeypatch, tmp_path):
    """No CHANGELOG error when base CHANGELOG is empty (new repo / first time)."""
    version = "0.11.0"
    groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    manifest = _make_manifest(version)
    readme = "## Version History\n- unchanged\n"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return manifest
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return ""  # empty base = file is new
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text(manifest)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("## New content added\n")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    # CHANGELOG errors must not be present (only CHANGELOG check is gated on non-empty base)
    assert not any("CHANGELOG.md" in e for e in errors)


def test_check_bookkeeping_invalid_manifest_json(monkeypatch, tmp_path):
    """Reports a JSON error and stops further manifest checks when JSON is malformed."""
    version = "0.11.0"
    groovy = _make_groovy(version)
    rule_groovy = f" * Version: {version}\n"
    readme = "## Version History\n- unchanged\n"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy
        if path == "packageManifest.json":
            return _make_manifest(version)
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return ""
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy)
    (tmp_path / "packageManifest.json").write_text("{invalid json!!}")
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    assert any("packageManifest.json" in e and "invalid JSON" in e for e in errors)


def test_check_bookkeeping_multiple_errors_accumulated(monkeypatch, tmp_path):
    """Multiple independent violations all appear in the error list."""
    base_version = "0.11.0"
    new_version = "0.11.1"
    base_groovy = _make_groovy(base_version)
    new_groovy = _make_groovy(new_version)
    rule_groovy_base = f" * Version: {base_version}\n"
    rule_groovy_new = f" * Version: {new_version}\n"
    base_manifest = _make_manifest(base_version)

    import json
    new_manifest_data = json.loads(base_manifest)
    new_manifest_data["version"] = new_version
    new_manifest = json.dumps(new_manifest_data)

    readme = "## Version History\n- unchanged\n"

    def fake_file_at_ref(_ref, path):
        if path == "hubitat-mcp-server.groovy":
            return base_groovy
        if path == "hubitat-mcp-rule.groovy":
            return rule_groovy_base
        if path == "packageManifest.json":
            return base_manifest
        if path == "README.md":
            return readme
        if path == "CHANGELOG.md":
            return ""
        return ""

    monkeypatch.setattr(pr_guard, "file_at_ref", fake_file_at_ref)

    (tmp_path / "hubitat-mcp-server.groovy").write_text(new_groovy)
    (tmp_path / "hubitat-mcp-rule.groovy").write_text(rule_groovy_new)
    (tmp_path / "packageManifest.json").write_text(new_manifest)
    (tmp_path / "README.md").write_text(readme)
    (tmp_path / "CHANGELOG.md").write_text("")

    monkeypatch.setattr(pr_guard, "ROOT", tmp_path)

    errors = pr_guard.check_bookkeeping("origin/main")
    # Should have at least groovy server, groovy rule, and manifest version errors
    assert len(errors) >= 3
