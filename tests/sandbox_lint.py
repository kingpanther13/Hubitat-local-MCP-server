#!/usr/bin/env python3
"""
Hubitat Groovy Sandbox Linter

Scans .groovy files for patterns known to crash at runtime in the Hubitat
sandbox even though they compile fine. Also checks version consistency
across project files.

Exit 0 = clean, exit 1 = errors found.
Outputs GitHub Actions annotations when running in CI.
"""

import re
import sys
import os
import json
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent

GROOVY_FILES = [
    REPO_ROOT / "hubitat-mcp-server.groovy",
    REPO_ROOT / "hubitat-mcp-rule.groovy",
]

VERSION_SOURCES = {
    "hubitat-mcp-server.groovy header": {
        "file": REPO_ROOT / "hubitat-mcp-server.groovy",
        "pattern": r"^\s*\*\s*Version:\s*(\d+\.\d+\.\d+)",
    },
    "hubitat-mcp-server.groovy currentVersion()": {
        "file": REPO_ROOT / "hubitat-mcp-server.groovy",
        "pattern": r'def\s+currentVersion\s*\(\)\s*\{\s*\n\s*return\s+"(\d+\.\d+\.\d+)"',
        "multiline": True,
    },
    "hubitat-mcp-rule.groovy header": {
        "file": REPO_ROOT / "hubitat-mcp-rule.groovy",
        "pattern": r"^\s*\*\s*Version:\s*(\d+\.\d+\.\d+)",
    },
    "packageManifest.json version": {
        "file": REPO_ROOT / "packageManifest.json",
        "pattern": r'"version"\s*:\s*"(\d+\.\d+\.\d+)"',
    },
}

# ---------------------------------------------------------------------------
# Anti-pattern rules
# ---------------------------------------------------------------------------

RULES = [
    {
        "id": "SANDBOX-001",
        "pattern": r"\bgetClass\s*\(",
        "message": "getClass() blocked in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-002",
        "pattern": r"\bLocale\s*\.\s*\w+",
        "message": "Locale class not available in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-003",
        "pattern": r"\.format\s*\([^)]*,\s*Locale",
        "message": "Date.format(String, Locale) overload not available in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-004",
        "pattern": r"\blog\s*\.\s*is\w+Enabled\s*\(",
        "message": "log.is*Enabled() not available in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-005",
        "pattern": r"\bEval\s*\.\s*(?:me|x|xy)\s*\(",
        "message": "Eval not available in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-006",
        "pattern": r"\bnew\s+Thread\b",
        "message": "Thread creation blocked in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-007",
        "pattern": r"\bClass\s*\.\s*forName\s*\(|\.newInstance\s*\(",
        "message": "Reflection (Class.forName / newInstance) blocked in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-008",
        "pattern": r"\bjava\s*\.\s*io\s*\.\s*File\b|\bnew\s+File\s*\(",
        "message": "Filesystem access blocked in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-009",
        "pattern": r"\bRuntime\s*\.\s*exec\s*\(|\bProcessBuilder\b",
        "message": "Process execution blocked in Hubitat sandbox",
        "severity": "error",
    },
    {
        "id": "SANDBOX-010",
        "pattern": r"\batomicState\s*\.\s*\w+\s*\[.*\]\s*=",
        "message": "Nested atomicState mutation does not persist — assign the whole map back",
        "severity": "warning",
    },
    {
        "id": "SANDBOX-011",
        "pattern": r"\bnew\s+.*HubAction\b",
        "message": "HubAction only valid in drivers, not apps",
        "severity": "error",
    },
]

# ---------------------------------------------------------------------------
# Comment/string stripping
# ---------------------------------------------------------------------------


def strip_comments_and_strings(source: str) -> list[str]:
    """Return lines with comments and string contents replaced.

    - Block comments (/* ... */) → replaced with blank lines (preserves line count)
    - Line comments (// ...) → stripped from end of line
    - String literal contents → replaced with __STR__ placeholder
    - Handles triple-quoted strings, double-quoted, and single-quoted strings
    """
    lines = source.split("\n")
    result: list[str] = []
    in_block_comment = False

    for line in lines:
        if in_block_comment:
            end = line.find("*/")
            if end != -1:
                in_block_comment = False
                # Keep content after the block comment close
                line = " " * (end + 2) + line[end + 2 :]
            else:
                result.append("")
                continue

        # Process the line character by character
        cleaned = []
        i = 0
        while i < len(line):
            # Block comment start
            if line[i : i + 2] == "/*":
                end = line.find("*/", i + 2)
                if end != -1:
                    cleaned.append(" " * (end + 2 - i))
                    i = end + 2
                else:
                    in_block_comment = True
                    break
            # Line comment
            elif line[i : i + 2] == "//":
                break
            # Triple-quoted string (Groovy)
            elif line[i : i + 3] in ('"""', "'''"):
                quote = line[i : i + 3]
                cleaned.append(quote[0] + "__STR__" + quote[0])
                j = i + 3
                # Find closing triple quote — may span lines but we handle
                # single-line case; multi-line triple quotes rarely contain
                # sandbox-violating patterns
                end = line.find(quote, j)
                if end != -1:
                    i = end + 3
                else:
                    i = len(line)
            # Double-quoted string
            elif line[i] == '"':
                cleaned.append('"__STR__"')
                j = i + 1
                while j < len(line):
                    if line[j] == "\\" and j + 1 < len(line):
                        j += 2
                    elif line[j] == '"':
                        j += 1
                        break
                    else:
                        j += 1
                i = j
            # Single-quoted string
            elif line[i] == "'":
                cleaned.append("'__STR__'")
                j = i + 1
                while j < len(line):
                    if line[j] == "\\" and j + 1 < len(line):
                        j += 2
                    elif line[j] == "'":
                        j += 1
                        break
                    else:
                        j += 1
                i = j
            else:
                cleaned.append(line[i])
                i += 1

        result.append("".join(cleaned))

    return result


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------


def scan_file(filepath: Path) -> list[dict]:
    """Scan a single groovy file for sandbox anti-patterns."""
    findings = []
    source = filepath.read_text(encoding="utf-8", errors="replace")
    stripped_lines = strip_comments_and_strings(source)
    rel_path = filepath.relative_to(REPO_ROOT)

    for line_num, line in enumerate(stripped_lines, start=1):
        for rule in RULES:
            if re.search(rule["pattern"], line):
                findings.append(
                    {
                        "file": str(rel_path),
                        "line": line_num,
                        "rule": rule["id"],
                        "message": rule["message"],
                        "severity": rule["severity"],
                        "source": source.split("\n")[line_num - 1].strip(),
                    }
                )

    return findings


# ---------------------------------------------------------------------------
# Version consistency
# ---------------------------------------------------------------------------


def check_versions() -> list[dict]:
    """Extract versions from all sources and flag mismatches."""
    versions: dict[str, str] = {}
    findings = []

    for label, spec in VERSION_SOURCES.items():
        filepath = spec["file"]
        if not filepath.exists():
            findings.append(
                {
                    "file": str(filepath.relative_to(REPO_ROOT)),
                    "line": 0,
                    "rule": "VERSION",
                    "message": f"Version source file not found: {label}",
                    "severity": "error",
                    "source": "",
                }
            )
            continue

        content = filepath.read_text(encoding="utf-8", errors="replace")
        flags = re.MULTILINE
        if spec.get("multiline"):
            flags |= re.DOTALL

        match = re.search(spec["pattern"], content, flags)
        if match:
            versions[label] = match.group(1)
        else:
            # Find approximate line for the expected pattern
            findings.append(
                {
                    "file": str(filepath.relative_to(REPO_ROOT)),
                    "line": 0,
                    "rule": "VERSION",
                    "message": f"Could not extract version from: {label}",
                    "severity": "error",
                    "source": "",
                }
            )

    # Check all versions match
    unique_versions = set(versions.values())
    if len(unique_versions) > 1:
        detail = ", ".join(f"{k}={v}" for k, v in sorted(versions.items()))
        findings.append(
            {
                "file": "packageManifest.json",
                "line": 0,
                "rule": "VERSION",
                "message": f"Version mismatch across files: {detail}",
                "severity": "error",
                "source": "",
            }
        )

    return findings


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

IS_CI = os.environ.get("CI") == "true" or os.environ.get("GITHUB_ACTIONS") == "true"


def format_finding(f: dict) -> str:
    """Format a single finding for human-readable output."""
    severity = f["severity"].upper()
    loc = f"{f['file']}:{f['line']}" if f["line"] else f["file"]
    msg = f"[{f['rule']}] {f['message']}"
    parts = [f"{severity}: {loc}: {msg}"]
    if f["source"]:
        parts.append(f"  > {f['source']}")
    return "\n".join(parts)


def format_annotation(f: dict) -> str:
    """Format a finding as a GitHub Actions annotation."""
    level = "warning" if f["severity"] == "warning" else "error"
    line_part = f",line={f['line']}" if f["line"] else ""
    return f"::{level} file={f['file']}{line_part}::[{f['rule']}] {f['message']}"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> int:
    all_findings: list[dict] = []

    # Scan groovy files
    for gf in GROOVY_FILES:
        if gf.exists():
            all_findings.extend(scan_file(gf))
        else:
            print(f"WARNING: Expected file not found: {gf}")

    # Check version consistency
    all_findings.extend(check_versions())

    # Sort by file, then line
    all_findings.sort(key=lambda f: (f["file"], f["line"]))

    # Output
    errors = [f for f in all_findings if f["severity"] == "error"]
    warnings = [f for f in all_findings if f["severity"] == "warning"]

    if all_findings:
        for f in all_findings:
            print(format_finding(f))
            if IS_CI:
                print(format_annotation(f))
            print()
    else:
        print("Sandbox lint: all checks passed.")

    # Summary
    print(f"--- {len(errors)} error(s), {len(warnings)} warning(s) ---")

    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
