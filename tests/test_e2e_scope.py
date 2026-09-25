"""Focused E2E selection must retain coverage for shared protection policy."""

import runpy
from pathlib import Path


def test_shared_policy_change_selects_protection_and_dashboard_regressions(monkeypatch, capsys):
    root = Path(__file__).resolve().parents[1]
    scope = runpy.run_path(str(root / ".github/scripts/e2e_scope.py"))
    monkeypatch.setenv("CHANGED_FILES", "hubitat-mcp-server.groovy")
    monkeypatch.delenv("CHANGED_TEST_FUNCS", raising=False)
    monkeypatch.delenv("GITHUB_OUTPUT", raising=False)
    scope["main"]()
    output = capsys.readouterr().out
    groups = next(line.removeprefix("groups=").split(",")
                  for line in output.splitlines() if line.startswith("groups="))
    assert {"developer_mode", "dashboards", "visual_rules"} <= set(groups)
