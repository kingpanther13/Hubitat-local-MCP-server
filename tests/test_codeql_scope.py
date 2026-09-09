"""The manual scan must select branch changes, not repository artifacts."""

import importlib.util
import json
import subprocess
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "codeql_scope", Path(__file__).resolve().parents[1] / ".github/scripts/codeql_scope.py"
)
scope = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scope)


@pytest.mark.parametrize("language,expected", [
    ("python", ["tests/new.py"]),
    ("javascript", ["src/new.ts"]),
    ("actions", [".github/workflows/new.yml", "custom/action.yaml"]),
])
def test_only_selected_language_and_no_reference_artifacts(language, expected):
    paths = ["tests/new.py", "src/new.ts", ".github/workflows/new.yml", "custom/action.yaml",
             "resources/hub2-source/appUI.js", "resources/hub2-source/example.py", "docs/evidence.json"]
    assert scope.select_paths(paths, language) == expected


def test_no_supported_changes_means_no_scan():
    assert scope.select_paths(["README.md", "app.groovy"], "python") == []


def test_glob_filename_cannot_expand_scope():
    with pytest.raises(ValueError):
        scope.select_paths(["src/all*.py"], "python")


def test_real_git_diff_excludes_unchanged_deleted_and_upstream_only_files(tmp_path, monkeypatch):
    head = tmp_path / "head"
    base = tmp_path / "base"
    head.mkdir()

    def git(root, *args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

    git(head, "init", "-b", "main")
    git(head, "config", "user.email", "ci@example.invalid")
    git(head, "config", "user.name", "CI fixture")
    for name in ("changed.py", "unchanged.py", "deleted.py"):
        (head / name).write_text("print('base')\n", encoding="utf-8")
    git(head, "add", ".")
    git(head, "commit", "-m", "base")
    git(head, "checkout", "-b", "topic")
    (head / "changed.py").write_text("print('branch')\n", encoding="utf-8")
    (head / "added.py").write_text("print('new')\n", encoding="utf-8")
    (head / "deleted.py").unlink()
    git(head, "add", "-A")
    git(head, "commit", "-m", "branch changes")
    topic_sha = git(head, "rev-parse", "HEAD")
    git(head, "checkout", "main")
    (head / "upstream_only.py").write_text("print('upstream')\n", encoding="utf-8")
    git(head, "add", ".")
    git(head, "commit", "-m", "upstream change")
    git(head, "checkout", "topic")
    git(head, "worktree", "add", str(base), "main")
    output = tmp_path / "reports"
    outputs = tmp_path / "outputs"
    monkeypatch.setenv("GITHUB_OUTPUT", str(outputs))
    scope.main([str(head), str(base), "python", str(output)])
    evidence = json.loads((output / "scope.json").read_text(encoding="utf-8"))
    assert evidence["head_paths"] == ["added.py", "changed.py"]
    assert evidence["base_paths"] == ["changed.py"]
    assert evidence["head_sha"] == topic_sha
    staged = tmp_path / "codeql-source"
    assert sorted(path.name for path in (staged / "head").iterdir()) == ["added.py", "changed.py"]
    assert sorted(path.name for path in (staged / "base").iterdir()) == ["changed.py"]
    assert (staged / "head/changed.py").read_bytes() == (head / "changed.py").read_bytes()
    assert outputs.read_text() == "head=true\nbase=true\n"
