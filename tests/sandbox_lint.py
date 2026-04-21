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


def _consume_gstring_interpolation(text: str, start: int) -> tuple[str, int]:
    """Walk from `start` (index of `$` in `${`) to the matching `}`, returning
    (preserved_text, index_past_close).

    The body is preserved verbatim so downstream regex rules scan the Groovy
    expression, except that nested string literals inside the body have their
    contents blanked (so a `}` inside `"literal }"` doesn't close the
    interpolation early, and a stray `getClass()` inside a nested literal
    doesn't trigger a false positive).

    Assumes `text[start] == '$'` and `text[start+1] == '{'`.
    """
    out = ["  "]  # ${
    depth = 1
    k = start + 2
    n = len(text)
    while k < n and depth > 0:
        ch = text[k]
        if ch == "{":
            depth += 1
            out.append(ch)
            k += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                out.append(" ")  # closing }
                k += 1
                break
            out.append(ch)
            k += 1
        elif ch == '"' or ch == "'":
            # Nested string literal inside the interpolation body. Skip past
            # its contents (respecting escapes) so embedded `}` characters
            # don't decrement our depth counter and stray sandbox-forbidden
            # names inside literal text don't trigger false positives.
            out.append(" ")
            k += 1
            while k < n:
                if text[k] == "\\" and k + 1 < n:
                    out.append("  ")
                    k += 2
                elif text[k] == ch:
                    out.append(" ")
                    k += 1
                    break
                else:
                    out.append(" ")
                    k += 1
        elif ch == "\\" and k + 1 < n:
            out.append(str(text[k]) + str(text[k + 1]))
            k += 2
        else:
            out.append(ch)
            k += 1
    return "".join(out), k


def strip_comments_and_strings(source: str) -> list[str]:
    """Return lines with comments and literal string contents replaced.

    Behavior:
    - Block comments (/* ... */) → replaced with spaces (preserves line count)
    - Line comments (// ...) → stripped from end of line
    - Single-quoted strings ('...') → fully blanked (not GStrings in Groovy)
    - Triple-single-quoted strings ('''...''') → fully blanked
    - Double-quoted strings ("...") → literal text blanked, ${...}
      interpolation bodies preserved so rules scan the Groovy expression
      (e.g. ${foo.getClass()} still triggers SANDBOX-001)
    - Triple-double-quoted strings (\"\"\"...\"\"\") → same GString treatment
      when closing on the same line; multi-line bodies fall back to blanked

    Invariants:
    - Output preserves the column and line count of the input so finding
      line numbers match the original source.
    - Spaces (not sentinel tokens like __STR__) are used so downstream regex
      rules can't accidentally match the sentinel itself.

    Known limitations (deliberate, not bugs):
    - Bare `$identifier[.prop...]` GString form (no braces) is NOT preserved;
      only `${...}` interpolations are. Groovy allows `"x=$foo.getClass"` as
      a property-access GString, but the bare form is rare in practice and
      ambiguous to scan. See the self-test fixture for pinned behavior.
    - Slashy strings (/.../) and dollar-slashy ($/.../$/) are not recognized
      and will be scanned as raw source. Rare in real Hubitat code.
    - Multi-line triple-quoted bodies (opening and closing on different
      lines) are blanked wholesale.
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
                        body, j = _consume_gstring_interpolation(line, j)
                        cleaned.append(body)
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
    """Blank literal text in a triple-double-quoted GString body while
    preserving ${...} interpolations verbatim. Shares nested-string-aware
    brace handling with the single-line GString walker via
    _consume_gstring_interpolation."""
    out = []
    i = 0
    n = len(body)
    while i < n:
        if body[i] == "\\" and i + 1 < n:
            out.append("  ")
            i += 2
        elif body[i] == "$" and i + 1 < n and body[i + 1] == "{":
            preserved, i = _consume_gstring_interpolation(body, i)
            out.append(preserved)
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
        "getClass() inside a triple-single-quoted string is NOT flagged",
        "def s = '''text ${obj.getClass()} here'''",
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
        "Nested closure braces inside a GString interpolation don't break the scanner",
        'def s = "count=${list.findAll { it.getClass() }.size()}"',
        [("SANDBOX-001", True)],
    ),
    (
        "Nested double-quoted string inside a GString interpolation is scanned correctly",
        'log.info "${ "literal".getClass() }"',
        [("SANDBOX-001", True)],
    ),
    (
        "Brace inside a nested string inside a GString does not close the interpolation early",
        'log.info "${foo.replace(\'}\', \'\').getClass()}"',
        [("SANDBOX-001", True)],
    ),
    (
        "getClass() inside a nested string inside an interpolation is NOT flagged",
        'def s = "ok=${foo.toString().replace("getClass()", "X")}"',
        [("SANDBOX-001", False)],
    ),
    (
        "Back-to-back interpolations are both scanned",
        'log.warn "${a.getClass()}${Locale.default}"',
        [("SANDBOX-001", True), ("SANDBOX-002", True)],
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
    (
        "Line comment containing GString-like text is NOT flagged",
        '// this is a comment mentioning "${foo.getClass()}"',
        [("SANDBOX-001", False)],
    ),
    (
        "Block comment containing GString-like text is NOT flagged",
        '/* "${foo.getClass()}" example */',
        [("SANDBOX-001", False)],
    ),
    (
        # Known limitation: the bare `$var.prop` GString form without braces
        # is not preserved. Pinned as a miss so any future change is
        # intentional. Groovy allows this form only for property access.
        "Bare $var.getClass GString form is a known false-negative (not flagged)",
        'log.warn "type=$obj.getClass"',
        [("SANDBOX-001", False)],
    ),
]


def run_self_test() -> int:
    """Scan inline fixtures and confirm each rule triggers where expected."""
    failures = 0
    for i, (desc, source, expected) in enumerate(SELF_TEST_CASES, start=1):
        stripped = strip_comments_and_strings(source)
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
