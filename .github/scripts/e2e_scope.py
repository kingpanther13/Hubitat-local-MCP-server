#!/usr/bin/env python3
"""Compute the FOCUSED e2e subset for a PR: the @test groups affected by the change.

Two inputs (the gate step in hub-e2e.yml passes both):
  - $CHANGED_FILES      -- the PR's changed-file list (newline-separated). Library/source files map to
                           e2e @test groups via FILE_GROUP_MAP.
  - $CHANGED_TEST_FUNCS -- test functions added in tests/e2e_test.py (space/newline-separated). Each is
                           mapped to its OWN @test("group") (read from the checked-out test file), so a
                           tests-only PR -- or a boy-scout test added in an otherwise-unrelated PR --
                           runs the group that CONTAINS the new test, not just the smoke core.
Plus a smoke core, always. Emits `groups` (csv) to $GITHUB_OUTPUT.

The WORKFLOW decides the LANE (full when a release:*/e2e:full label is present, focused otherwise); the
full lane ignores this script. An unmapped changed file adds no group -- smoke still runs and the FULL
lane is the real safety net before merge.
"""
import os
import re

# Always run a tiny smoke core so no focused run has ZERO integration coverage.
SMOKE_GROUPS = ["infrastructure", "protocol"]

TEST_FILE = "tests/e2e_test.py"

# Changed file -> e2e @test group(s) it exercises. Best-effort; refine as the suite evolves.
FILE_GROUP_MAP = {
    "libraries/mcp-native-rules-lib.groovy":    ["native_apps"],
    "libraries/mcp-visual-rules-lib.groovy":    ["visual_rules"],
    "libraries/mcp-custom-rules-lib.groovy":    ["rule_crud", "trigger_types", "condition_types", "action_types", "complex_patterns"],
    "hubitat-mcp-rule.groovy":                  ["rule_crud", "trigger_types", "condition_types", "action_types", "complex_patterns"],
    "libraries/mcp-diagnostics-lib.groovy":     ["diagnostics", "system_tools"],
    "libraries/mcp-debug-logging-lib.groovy":   ["developer_mode", "diagnostics"],
    "libraries/mcp-system-lib.groovy":          ["system_tools", "infrastructure"],
    "libraries/mcp-self-admin-lib.groovy":      ["developer_mode"],
    "libraries/mcp-devices-lib.groovy":         ["devices", "poll_until_attribute", "device_swap"],
    "libraries/mcp-virtual-devices-lib.groovy": ["virtual_device_lifecycle", "devices"],
    "libraries/mcp-variables-lib.groovy":       ["hub_variables"],
    "libraries/mcp-code-management-lib.groovy": ["app_code_update", "driver_code_update", "installed_app_reads", "system_tools"],
    "libraries/mcp-bundles-lib.groovy":         ["system_tools"],
    "libraries/mcp-hpm-lib.groovy":             ["system_tools"],
    "libraries/mcp-files-lib.groovy":           ["system_tools"],
    "libraries/mcp-item-backups-lib.groovy":    ["system_tools"],
    "libraries/mcp-rooms-lib.groovy":           ["infrastructure"],
    "libraries/mcp-discovery-lib.groovy":       ["infrastructure", "protocol"],
    "libraries/mcp-app-cloner-lib.groovy":      ["native_apps", "rule_crud"],
    "libraries/mcp-smoke-test-lib.groovy":      ["infrastructure"],
}


def _changed_files() -> list[str]:
    return [f.strip() for f in os.environ.get("CHANGED_FILES", "").splitlines() if f.strip()]


def _changed_test_funcs() -> list[str]:
    raw = os.environ.get("CHANGED_TEST_FUNCS", "")
    return [t.strip() for t in raw.replace(",", " ").split() if t.strip()]


def _test_group_map() -> dict:
    """test_func_name -> @test group, read from the checked-out e2e test file (the @test("group")
    decorator sits directly above each `def test_...`)."""
    out: dict[str, str] = {}
    cur = None
    try:
        with open(TEST_FILE, encoding="utf-8") as fh:
            for line in fh:
                dec = re.match(r'\s*@test\("([a-z_]+)"\)', line)
                if dec:
                    cur = dec.group(1)
                    continue
                d = re.match(r'\s*def (\w+)\(', line)
                if d:
                    # Reset on ANY def so a @test decorator can't leak across a non-test helper
                    # sitting between it and the next test function.
                    if cur and d.group(1).startswith("test_"):
                        out[d.group(1)] = cur
                    cur = None
    except OSError:
        pass
    return out


def _emit(groups) -> None:
    payload = f"groups={','.join(sorted(set(groups)))}"
    print(payload)
    gh_out = os.environ.get("GITHUB_OUTPUT")
    if gh_out:
        with open(gh_out, "a", encoding="utf-8") as fh:
            fh.write(payload + "\n")


def main() -> None:
    groups = set(SMOKE_GROUPS)
    for f in _changed_files():
        if f in FILE_GROUP_MAP:
            groups.update(FILE_GROUP_MAP[f])
    changed_tests = _changed_test_funcs()
    if changed_tests:
        tg = _test_group_map()
        for t in changed_tests:
            g = tg.get(t)
            if g:
                groups.add(g)
            else:
                print(f"[scope] note: changed test {t} -> no @test group found (new group / helper?); full lane covers it")
    print(f"[scope] focused subset -- groups={sorted(groups)}")
    _emit(groups)


if __name__ == "__main__":
    main()
