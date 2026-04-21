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
    """Return lines with comments and literal string contents replaced.

    - Block comments (/* ... */) → replaced with blank lines (preserves line count)
    - Line comments (// ...) → stripped from end of line
    - Single-quoted strings → contents replaced with spaces (not GStrings in Groovy)
    - Double-quoted strings (GStrings) → literal text replaced with spaces,
      but ${...} interpolation bodies are preserved verbatim so rules scan
      the Groovy expression they contain (e.g. ${foo.getClass()} still
      triggers SANDBOX-001).
    - Triple-quoted single-quoted strings → fully blanked
    - Triple-quoted double-quoted strings → same GString treatment as "..."
      when closing on the same line; multi-line bodies fall back to blanked
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
            # Triple-double-quoted GString (may contain ${...})
            elif line[i : i + 3] == '"""':
                end = line.find('"""', i + 3)
                if end != -1:
                    cleaned.append("   ")
                    cleaned.append(_scrub_gstring_body(line[i + 3 : end]))
                    cleaned.append("   ")
                    i = end + 3
                else:
                    # Multi-line triple-quoted body — rare; fall back to blanks
                    cleaned.append(" " * (len(line) - i))
                    i = len(line)
            # Triple-single-quoted (plain string, no interpolation)
            elif line[i : i + 3] == "'''":
                end = line.find("'''", i + 3)
                if end != -1:
                    cleaned.append(" " * (end + 3 - i))
                    i = end + 3
                else:
                    cleaned.append(" " * (len(line) - i))
                    i = len(line)
            # Double-quoted GString — preserve ${...} bodies, blank literal text
            elif line[i] == '"':
                cleaned.append(" ")  # opening quote
                j = i + 1
                while j < len(line):
                    if line[j] == "\\" and j + 1 < len(line):
                        cleaned.append("  ")
                        j += 2
                    elif line[j] == '"':
                        cleaned.append(" ")
                        j += 1
                        break
                    elif line[j] == "$" and j + 1 < len(line) and line[j + 1] == "{":
                        # Preserve interpolation body verbatim so rules can
                        # scan the Groovy expression inside.
                        depth = 1
                        k = j + 2
                        cleaned.append("  ")  # ${
                        while k < len(line) and depth > 0:
                            if line[k] == "{":
                                depth += 1
                                cleaned.append(line[k])
                            elif line[k] == "}":
                                depth -= 1
                                if depth == 0:
                                    cleaned.append(" ")  # closing }
                                    k += 1
                                    break
                                cleaned.append(line[k])
                            else:
                                cleaned.append(line[k])
                            k += 1
                        j = k
                    else:
                        cleaned.append(" ")
                        j += 1
                i = j
            # Single-quoted string — plain string in Groovy, no interpolation
            elif line[i] == "'":
                cleaned.append(" ")
                j = i + 1
                while j < len(line):
                    if line[j] == "\\" and j + 1 < len(line):
                        cleaned.append("  ")
                        j += 2
                    elif line[j] == "'":
                        cleaned.append(" ")
                        j += 1
                        break
                    else:
                        cleaned.append(" ")
                        j += 1
                i = j
            else:
                cleaned.append(line[i])
                i += 1

        result.append("".join(cleaned))

    return result


def _scrub_gstring_body(body: str) -> str:
    """Within a triple-double-quoted GString body, blank literal text but
    keep ${...} interpolations verbatim (same treatment as a "..." GString)."""
    out = []
    i = 0
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            out.append("  ")
            i += 2
        elif body[i] == "$" and i + 1 < len(body) and body[i + 1] == "{":
            depth = 1
            k = i + 2
            out.append("  ")
            while k < len(body) and depth > 0:
                if body[k] == "{":
                    depth += 1
                    out.append(body[k])
                elif body[k] == "}":
                    depth -= 1
                    if depth == 0:
                        out.append(" ")
                        k += 1
                        break
                    out.append(body[k])
                else:
                    out.append(body[k])
                k += 1
            i = k
        else:
            out.append(" ")
            i += 1
    return "".join(out)


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

    # Check strict semver (catches typos like 0.10.0-rc1, v0.10.0, stray whitespace)
    # that would silently break isNewerVersion() on user hubs.
    strict_re = re.compile(r"^\d+\.\d+\.\d+$")
    for label, version in versions.items():
        if not strict_re.match(version):
            findings.append(
                {
                    "file": str(VERSION_SOURCES[label]["file"].relative_to(REPO_ROOT)),
                    "line": 0,
                    "rule": "VERSION",
                    "message": (
                        f"Version {version!r} in {label} is not strict semver "
                        "(X.Y.Z only, no prefixes or suffixes). "
                        "Non-numeric versions silently break the update checker."
                    ),
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


SELF_TEST_CASES = [
    # (description, groovy source, list of (rule_id, should_match))
    (
        "getClass() inside a GString interpolation is flagged",
        'log.warn "type=${obj?.getClass()?.simpleName}"',
        [("SANDBOX-001", True)],
    ),
    (
        "getClass() as plain string literal content is NOT flagged",
        'log.warn "text mentioning getClass() as example"',
        [("SANDBOX-001", False)],
    ),
    (
        "getClass() inside a single-quoted string is NOT flagged (not a GString)",
        "log.warn 'type=${obj?.getClass()?.simpleName}'",
        [("SANDBOX-001", False)],
    ),
    (
        "getClass() inside a triple-double-quoted GString interpolation is flagged",
        'def s = """prefix ${obj.getClass()} suffix"""',
        [("SANDBOX-001", True)],
    ),
    (
        "Bare getClass() call is flagged",
        "def t = obj.getClass().simpleName",
        [("SANDBOX-001", True)],
    ),
    (
        "Nested braces inside a GString interpolation don't break the scanner",
        'def s = "count=${list.findAll { it.getClass() }.size()}"',
        [("SANDBOX-001", True)],
    ),
    (
        "Locale inside a GString interpolation is flagged",
        'log.info "loc=${Locale.default}"',
        [("SANDBOX-002", True)],
    ),
    (
        "Escaped dollar does not open an interpolation",
        'def s = "literal \\${getClass()}"',
        [("SANDBOX-001", False)],
    ),
]


def run_self_test() -> int:
    """Scan inline fixtures and confirm each rule triggers where expected."""
    from tempfile import NamedTemporaryFile

    failures = 0
    for i, (desc, source, expected) in enumerate(SELF_TEST_CASES, start=1):
        with NamedTemporaryFile(
            mode="w", suffix=".groovy", delete=False, encoding="utf-8"
        ) as fh:
            fh.write(source + "\n")
            tmp = Path(fh.name)
        try:
            stripped = strip_comments_and_strings(source)
            # Build a findings-like set from the stripped output
            hits = {
                rule["id"]
                for line in stripped
                for rule in RULES
                if re.search(rule["pattern"], line)
            }
            for rule_id, should_match in expected:
                matched = rule_id in hits
                if matched != should_match:
                    failures += 1
                    print(
                        f"SELF-TEST FAIL [{i}] {desc}\n"
                        f"  rule={rule_id} expected={'hit' if should_match else 'miss'} "
                        f"actual={'hit' if matched else 'miss'}\n"
                        f"  source: {source!r}\n"
                        f"  stripped: {stripped!r}"
                    )
        finally:
            try:
                tmp.unlink()
            except OSError:
                pass

    if failures:
        print(f"--- {failures} self-test failure(s) ---")
        return 1
    print(f"Self-test: {len(SELF_TEST_CASES)} case(s) passed.")
    return 0


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return run_self_test()

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
