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

# Force UTF-8 on stdout/stderr so prints containing em dashes, arrows, and
# other non-ASCII characters in messages don't crash on Windows consoles
# whose default codepage is cp1252. The lint runs cleanly on Linux/macOS
# (already UTF-8), CI (also UTF-8), and Windows terminals that respect
# the env var; the only failure mode this fixes is a Windows local run
# where the contributor doesn't pre-set PYTHONIOENCODING. errors=replace
# (rather than strict) keeps a single mis-encoded character from masking
# whatever the lint was actually trying to report.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

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
        # Match both `getClass()` invocations and bare property-access form
        # (`obj.getClass` in a GString triggers the no-arg method at runtime).
        "id": "SANDBOX-001",
        "pattern": r"\bgetClass\b",
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
    {
        "id": "SANDBOX-012",
        "pattern": r"\bnew\s+(?:java\s*\.\s*util\s*\.\s*)?ArrayDeque\s*\(",
        "message": "ArrayDeque instantiation blocked in Hubitat sandbox at parse time -- use Groovy list literal `[]` (LinkedList-backed; supports addLast/removeLast for LIFO semantics)",
        "severity": "error",
    },
    {
        "id": "SANDBOX-013",
        "pattern": r"\bnew\s+(?:groovy\s*\.\s*lang\s*\.\s*)?GroovyShell\b|\bGroovyShell\s*\.",
        "message": "GroovyShell blocked in Hubitat sandbox",
        "severity": "error",
    },
]

# ---------------------------------------------------------------------------
# Comment/string stripping
# ---------------------------------------------------------------------------


_BARE_GSTRING_IDENT_START = re.compile(r"[A-Za-z_]")
_BARE_GSTRING_IDENT_CHAR = re.compile(r"[A-Za-z0-9_]")


def _consume_bare_gstring_var(text: str, start: int) -> tuple[str, int]:
    """Consume a bare `$identifier[.identifier]*` GString reference.

    Assumes `text[start] == '$'` and `text[start + 1]` is an identifier
    start character. Returns (preserved_text, index_past_end). The leading
    `$` is blanked (we only care about what follows for rule matching) but
    the identifier chain is preserved verbatim so rules like SANDBOX-001
    can match `$foo.getClass` (a legal bare-form Groovy property access
    that triggers the no-arg method at runtime).
    """
    n = len(text)
    out = [" "]  # blank the $
    k = start + 1
    # First identifier
    while k < n and _BARE_GSTRING_IDENT_CHAR.match(text[k]):
        out.append(text[k])
        k += 1
    # Subsequent .identifier segments
    while (
        k + 1 < n
        and text[k] == "."
        and _BARE_GSTRING_IDENT_START.match(text[k + 1])
    ):
        out.append(".")
        k += 1
        while k < n and _BARE_GSTRING_IDENT_CHAR.match(text[k]):
            out.append(text[k])
            k += 1
    return "".join(out), k


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
    - Double-quoted strings ("...") → literal text blanked; ${...}
      interpolation bodies AND bare $identifier[.prop...] references are
      preserved so rules scan the Groovy expression (e.g. ${foo.getClass()}
      and $foo.getClass both trigger SANDBOX-001)
    - Triple-double-quoted strings (\"\"\"...\"\"\") → same GString treatment
      when closing on the same line; multi-line bodies fall back to blanked

    Invariants:
    - Output preserves the column and line count of the input so finding
      line numbers match the original source.
    - Spaces (not sentinel tokens like __STR__) are used so downstream regex
      rules can't accidentally match the sentinel itself.

    Known limitations (deliberate, not bugs):
    - Slashy strings (/.../) and dollar-slashy ($/.../$/) are not recognized
      as strings — their content is scanned as raw source. Rare in real
      Hubitat code; the only existing use is a bare Pattern literal with no
      interpolation. False positives would require literal sandbox-forbidden
      names inside a regex body, which is implausible.
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
                    elif (
                        line[j] == "$"
                        and j + 1 < len(line)
                        and _BARE_GSTRING_IDENT_START.match(line[j + 1])
                    ):
                        body, j = _consume_bare_gstring_var(line, j)
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
    preserving ${...} interpolations and bare `$identifier[.prop...]`
    references. Shares nested-string-aware brace handling with the
    single-line GString walker via _consume_gstring_interpolation."""
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
        elif (
            body[i] == "$"
            and i + 1 < n
            and _BARE_GSTRING_IDENT_START.match(body[i + 1])
        ):
            preserved, i = _consume_bare_gstring_var(body, i)
            out.append(preserved)
        else:
            out.append(" ")
            i += 1
    return "".join(out)


# ---------------------------------------------------------------------------
# Scanning
# ---------------------------------------------------------------------------


def scan_source(source: str, display_path: str) -> list[dict]:
    """Scan Groovy source text for sandbox anti-patterns.

    Separated from scan_file so the self-test can exercise the same code
    path without touching disk.
    """
    findings = []
    stripped_lines = strip_comments_and_strings(source)
    source_lines = source.split("\n")

    for line_num, line in enumerate(stripped_lines, start=1):
        for rule in RULES:
            if re.search(rule["pattern"], line):
                findings.append(
                    {
                        "file": display_path,
                        "line": line_num,
                        "rule": rule["id"],
                        "message": rule["message"],
                        "severity": rule["severity"],
                        "source": source_lines[line_num - 1].strip(),
                    }
                )

    return findings


def scan_file(filepath: Path) -> list[dict]:
    """Scan a single groovy file for sandbox anti-patterns."""
    source = filepath.read_text(encoding="utf-8", errors="replace")
    rel_path = str(filepath.relative_to(REPO_ROOT))
    return scan_source(source, rel_path)


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
# Tool-count consistency check
# ---------------------------------------------------------------------------
#
# Tool counts are quoted across many docs (README, SKILL, TOOL_GUIDE,
# agent-skill mirrors, BAT-v2). Without a check, every tool-adding PR has
# to manually update them in lockstep, and drift is silent until someone
# notices. This check derives the canonical counts from the Groovy source
# (getGatewayConfig + getAllToolDefinitions) and flags any current-state
# claim in docs that disagrees.
#
# Historical references in version-history sections, migration tables, and
# version-pinned phrasings (e.g. "v0.8.0 had 21 core tools") are skipped
# so the lint doesn't false-fire on accurate history.

DOC_FILES_FOR_COUNTS = [
    REPO_ROOT / "README.md",
    REPO_ROOT / "SKILL.md",
    REPO_ROOT / "TOOL_GUIDE.md",
    REPO_ROOT / "agent-skill" / "hubitat-mcp" / "SKILL.md",
    REPO_ROOT / "agent-skill" / "hubitat-mcp" / "tool-reference.md",
    REPO_ROOT / "tests" / "BAT-v2.md",
    # tests/e2e_test.py hardcodes a tools/list count assertion + descriptive
    # message; the message string contains "(N core + M gateways)" forms
    # the existing patterns match.
    REPO_ROOT / "tests" / "e2e_test.py",
    # tests/BAT.md is intentionally NOT in scope. It's the legacy v1 suite,
    # explicitly documented at the top as describing the pre-v0.8.0
    # architecture for historical reference. Every count there is meant to
    # be a snapshot of an older release, and the prose phrasings ("pre-v0.8.0
    # architecture (8 gateways, ...)") don't fit clean line-level historical
    # patterns without false negatives elsewhere. BAT-v2.md is the current
    # source of truth and remains in scope.
]

# Two tiers of historical-line markers:
#
# WIDE (line-scope) — markers that essentially never appear on a live
# count line. A line containing one of these is almost certainly
# documenting historical state, so we skip the entire line.
#   - Version-pin markers like `**v0.7.7**:` or `(v0.8.0)` always scope
#     a count to a past release.
# NARROW (window-scope) — markers that CAN legitimately appear on a
# live-count line, scoping only a nearby clause. We check these only
# within the HISTORICAL_NEAR_MATCH_WINDOW chars that PRECEDE the matched
# count (look-back only). Historical markers describe past state that
# comes before a live claim; checking forward would silently swallow live
# counts whose trailing clause references old values — e.g.
# "22 core tools today, was 18 previously" would incorrectly skip the
# live "22 core" if the window extended past match_start into the "was 18"
# that follows.
HISTORICAL_NEAR_MATCH_WINDOW = 20  # chars of look-back window ending immediately before the matched count
HISTORICAL_LINE_PATTERNS_WIDE = [
    # "v0.7.7 (all 74 tools)" — version followed by paren-count is the
    # canonical historical scoping shape; counts within the parens are
    # always scoped to the version.
    re.compile(r"\bv\d+\.\d+(?:\.\d+)?\s*\("),
    # "**v0.7.7**: 74 tools listed" — bold-version + colon is also a
    # canonical historical-scoping shape (definitional clause).
    re.compile(r"\*\*v\d+\.\d+(?:\.\d+)?\*\*\s*:"),
]
HISTORICAL_LINE_PATTERNS_NARROW = [
    re.compile(r"→"),                              # migration arrows
    re.compile(r"\bwas\s+\d+\b", re.IGNORECASE),  # "was 90"
    re.compile(r"\bpreviously\s+\d+\b", re.IGNORECASE),
    re.compile(r"\bbefore\s*[:=]\s*\d+\b", re.IGNORECASE),
    # `(v0.7.7` / `(v0.8.0)` — bare parenthesized version. NARROW because
    # this shape ALSO appears as a feature-availability marker in live
    # text (e.g. "T120 — All N gateways in one session (v0.8.0)" where
    # the version-pin is just provenance, not historical scoping). Only
    # treat as historical if the version-paren sits within ±window of
    # the count it would scope.
    re.compile(r"\(v\d+\.\d+(?:\.\d+)?"),
]
# Backward-compat alias: the union exists for any external caller that
# imports HISTORICAL_LINE_PATTERNS directly.
HISTORICAL_LINE_PATTERNS = HISTORICAL_LINE_PATTERNS_WIDE + HISTORICAL_LINE_PATTERNS_NARROW

# Section headings that flip the file into "history mode" — counts inside
# the section (or any DESCENDANT subsection) are historical. The ancestor
# walk in `_is_historical_at` traverses the full heading stack so a `###
# v1.0.0 (2026-01-15)` sub-section under `## Version History` correctly
# inherits historical status. We deliberately do NOT include a
# parenthesized-version-anywhere pattern here — that flagged live
# scenario titles like the original "T120 — All N gateways in one session
# (v0.8.0)". When a real version-pinned subsection exists, the ancestor
# walk picks up the parent Version History / Changelog heading.
HISTORICAL_SECTION_HEADINGS = [
    re.compile(r"^#+\s*Version\s+History\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^#+\s*Changelog\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^#+\s*Changes\s+from\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^#+\s*Migration\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^#+\s*Release\s+Notes\b", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^#+\s*History\b", re.IGNORECASE | re.MULTILINE),
]


def _extract_canonical_counts() -> dict | None:
    """Parse hubitat-mcp-server.groovy to derive canonical tool counts.

    Returns a dict {total, core, gateways, tools_list, proxied,
    per_gateway: {name: op_count}} or None if extraction fails.
    """
    srv_path = REPO_ROOT / "hubitat-mcp-server.groovy"
    if not srv_path.exists():
        return None
    src = srv_path.read_text(encoding="utf-8", errors="replace")

    # Comment-stripping intentionally NOT done. Reasoning: this codebase's
    # tool description heredocs commonly contain both `//` (URLs like
    # `https://...`) and `/* ... */`-shaped tokens (regex example syntax,
    # quoted Hubitat doc snippets). A naive regex strip mangles those
    # strings and breaks the canonical extraction more often than it helps.
    # The hypothetical case of "tool definition commented out for
    # deprecation" would produce an over-count (lint reports doc N vs
    # canonical N+1, which is loud and recoverable), so the trade-off
    # favors a simpler parser. A real Groovy AST walk would be the right
    # fix if commented-out definitions ever become a recurring class.

    # Carve out the getGatewayConfig() body
    gw_match = re.search(
        r"^def getGatewayConfig\(\) \{(.*?)^}",
        src,
        re.DOTALL | re.MULTILINE,
    )
    if not gw_match:
        return None
    gw_block = gw_match.group(1)

    per_gateway: dict[str, int] = {}
    proxied_names: set[str] = set()
    # Two-stage anchor:
    #   1. `^\s+([a-z_]+):\s*\[\s*description:` — gateway-key indented
    #      AND immediately opens with `description:`. This is the
    #      structural anchor that prevents matching nested map keys
    #      like `summaries:` or `searchHints:` as gateways (every
    #      gateway in this codebase opens with description; the inner
    #      maps don't).
    #   2. `\".*?\".*?\btools:\s*\[([^\]]+)\]` — capture the tools
    #      array regardless of what other keys (`category:`, comments,
    #      etc.) appear between description and tools. `\btools:` with
    #      word-boundary avoids false matches on suffixes like
    #      `subtools:`.
    # Escaped quotes inside description aren't currently used in this
    # codebase; if a future maintainer adds one, the inner `\".*?\"`
    # would terminate early — flag it then if it actually breaks.
    for m in re.finditer(
        r"^\s+([a-z_]+):\s*\[\s*description:\s*\".*?\".*?\btools:\s*\[([^\]]+)\]",
        gw_block,
        re.MULTILINE | re.DOTALL,
    ):
        name = m.group(1)
        # Strip line-comments BEFORE comma-splitting. A trailing `//`
        # comment on a tool line (e.g. `"foo", // disabled\n "bar"`)
        # would otherwise put part of the comment AND the next entry
        # into the same comma-separated chunk; per-entry split-on-`//`
        # would then keep only the empty pre-comment whitespace and
        # silently drop the next tool. Today's source has no inline
        # comments inside tools[], but this is forward-compat.
        no_comments = re.sub(r"//[^\n]*", "", m.group(2))
        tool_list = [
            t.strip().strip("\"'")
            for t in no_comments.split(",")
            if t.strip()
        ]
        per_gateway[name] = len(tool_list)
        proxied_names.update(tool_list)

    if not per_gateway:
        return None

    # Carve out getAllToolDefinitions() and extract tool names
    all_match = re.search(
        r"^def getAllToolDefinitions\(\) \{(.*?)^}",
        src,
        re.DOTALL | re.MULTILINE,
    )
    if not all_match:
        return None
    # Keep raw list separate from the set so a duplicate `name:` entry
    # in getAllToolDefinitions() is detectable: the count check sees
    # total = len(list) > len(docs) and fires; the self-test cross-
    # checks list-len vs set-len. If we collapsed both into len(set),
    # duplicate-name regressions would be silent on both gates.
    raw_name_list = re.findall(r"^\s*name:\s*['\"]([a-z_]+)['\"]", all_match.group(1), re.MULTILINE)
    tool_names: set[str] = set(raw_name_list)
    total = len(raw_name_list)

    proxied = sum(per_gateway.values())
    core = total - proxied
    gateways = len(per_gateway)
    tools_list = core + gateways

    return {
        "total": total,
        "core": core,
        "gateways": gateways,
        "tools_list": tools_list,
        "proxied": proxied,
        "per_gateway": per_gateway,
        # Name sets for tool-name consistency check (separate from counts):
        "tool_names": tool_names,           # all tool identifiers in getAllToolDefinitions()
        "gateway_names": set(per_gateway.keys()),  # gateway facade identifiers (manage_X)
        "proxied_names": proxied_names,     # tools enumerated under any gateway's `tools:` list
    }


def _is_historical_at(content: str, match_start: int, match_end: int | None = None) -> bool:
    """Return True if the match falls inside a historical / migration
    context that should be skipped by the count lint.

    Two layers:
      (1) Per-match window: HISTORICAL_LINE_PATTERNS are checked only
          within ±HISTORICAL_NEAR_MATCH_WINDOW chars of the match itself,
          NOT the entire enclosing line. A live count line that happens
          to mention "was 90" elsewhere should not silently disable lint
          for the live count next to it.
      (2) Section-level ancestor walk: walks backward through ALL
          preceding headings tracking heading level. Any ancestor (a
          heading at strictly lower level than the smallest level seen
          so far) matching HISTORICAL_SECTION_HEADINGS marks the match
          as historical. This correctly handles `### v1.0.0 ...` sub-
          headings under a `## Version History` parent.
    """
    if match_end is None:
        match_end = match_start

    # Layer 1a: WIDE markers — version-pins anywhere on the line. These
    # scope the entire line to historical context.
    line_start = content.rfind("\n", 0, match_start) + 1
    line_end_pos = content.find("\n", match_start)
    if line_end_pos == -1:
        line_end_pos = len(content)
    line = content[line_start:line_end_pos]
    for pat in HISTORICAL_LINE_PATTERNS_WIDE:
        if pat.search(line):
            return True

    # Layer 1b: NARROW markers — only inside the look-back window ending at
    # match_start. Historical markers describe PAST state that precedes a live
    # claim; a trailing "was N" or "previously N" appearing AFTER the count
    # describes the live count's own history and should not suppress the live
    # count. Anchoring the window to [match_start - window, match_start]
    # (look-back only) correctly reflects this semantic.
    win_start = max(0, match_start - HISTORICAL_NEAR_MATCH_WINDOW)
    win_end = match_start  # exclusive — do not extend past the count itself
    window = content[win_start:win_end]
    for pat in HISTORICAL_LINE_PATTERNS_NARROW:
        if pat.search(window):
            return True

    # Layer 2: ancestor walk through preceding headings. Track the
    # smallest level seen so far — only a heading at strictly smaller
    # level (i.e. an ancestor section) can mark this match as historical.
    # Sibling/descendant historical headings don't propagate.
    preceding = content[:match_start]
    min_level = float("inf")
    for m in reversed(list(re.finditer(r"^(#+)\s+\S", preceding, re.MULTILINE))):
        level = len(m.group(1))
        if level >= min_level:
            continue  # sibling or descendant; not an ancestor of the match
        min_level = level
        line_end = preceding.find("\n", m.start())
        if line_end == -1:
            line_end = len(preceding)
        heading_line = preceding[m.start():line_end]
        for pat in HISTORICAL_SECTION_HEADINGS:
            if pat.match(heading_line):
                return True
    return False


# Backward-compat alias: callers that pass only match_start still work.
_is_historical_line = _is_historical_at


# Patterns to scan in docs. Each entry: (regex, count_kind).
# count_kind keys map to canonical fields above ("total", "core",
# "gateways", "tools_list", "proxied").
COUNT_PATTERNS: list[tuple[re.Pattern, str]] = [
    # Total
    (re.compile(r"\((\d+)\s+total\b"), "total"),
    (re.compile(r"\b(\d+)\s+tools?\s+total\b", re.IGNORECASE), "total"),
    (re.compile(r"\bcovering\s+(\d+)\s+(?:total\s+)?tools?\b", re.IGNORECASE), "total"),
    (re.compile(r"\bMCP\s+Tools?\s+\((\d+)\s+total\b", re.IGNORECASE), "total"),
    (re.compile(r"\bexposes?\s+(\d+)\s+tools?\b", re.IGNORECASE), "total"),
    (re.compile(r"\bexposing\s+(\d+)\s+tools?\b", re.IGNORECASE), "total"),
    (re.compile(r"\bhas\s+(\d+)\s+tools?\s+total\b", re.IGNORECASE), "total"),
    (re.compile(r"\b(\d+)\s+MCP\s+tools?\b", re.IGNORECASE), "total"),
    # "search across all N tools" / "for all N tools" — catalog references.
    (re.compile(r"\b(?:across|for|reference\s+for)\s+all\s+(\d+)\s+(?:MCP\s+)?tools?\b", re.IGNORECASE), "total"),
    # "All N tools are covered" — BAT test-coverage claim that tracks the
    # total tool count (covered = total minus excluded destructives).
    (re.compile(r"\bAll\s+(\d+)\s+tools?\s+are\s+covered\b", re.IGNORECASE), "total"),
    (re.compile(r"\b(\d+)\s+additional\s+tools?\b", re.IGNORECASE), "proxied"),
    # "returns all N tool definitions" — getAllToolDefinitions() phrasing.
    (re.compile(r"\b(\d+)\s+tool\s+definitions?\b", re.IGNORECASE), "total"),
    # "from N items to M" — gateway-pattern explanation phrasing.
    (re.compile(r"\bfrom\s+(\d+)\s+items?\s+to\s+\d+\b", re.IGNORECASE), "total"),
    # "N proxied" anywhere (not just paren-prefix) — covers compact-summary
    # phrasings like "(... 80 proxied, 103 total)".
    (re.compile(r"\b(\d+)\s+proxied\b(?!\s+tools)", re.IGNORECASE), "proxied"),
    # "N total" when followed by punctuation or end-of-clause — catches
    # the BAT-v2 header-style "80 proxied, 103 total)" without firing on
    # "30 total scenarios" / "13 total messages" qualifying-noun forms.
    (re.compile(r"\b(\d+)\s+total\b(?=\s*[.)\n,])"), "total"),
    # Core
    (re.compile(r"\b(\d+)\s+core\s+tools?\b", re.IGNORECASE), "core"),
    (re.compile(r"\b(\d+)\s+core\s*\+\s*\d+\s+gateways?\b", re.IGNORECASE), "core"),
    # Gateways
    (re.compile(r"\b\d+\s+core\s*\+\s*(\d+)\s+gateways?\b", re.IGNORECASE), "gateways"),
    (re.compile(r"(?<!\.)\b(\d+)\s+gateways?\b", re.IGNORECASE), "gateways"),
    # tools/list count
    (re.compile(r"\b(\d+)\s+on\s+`?tools/list`?\b"), "tools_list"),
    (re.compile(r"\b(\d+)\s+items?\s+on\s+`?tools/list`?\b"), "tools_list"),
    # Proxied count
    (re.compile(r"\((\d+)\s+proxied\b"), "proxied"),
    (re.compile(r"\b(\d+)\s+proxied\s+tools?\b"), "proxied"),
    # Table-row form: "Total tools in codebase | 90", etc. Each fixes the
    # phrasing tightly so a stray two-column table elsewhere can't match
    # by accident.
    (re.compile(r"\btotal\s+tools?\s+in\s+codebase\s*\|?\s*(\d+)\b", re.IGNORECASE), "total"),
    (re.compile(r"\btools?\s+proxied\s+behind\s+gateways?\s*\|?\s*(\d+)\b", re.IGNORECASE), "proxied"),
    (re.compile(r"\btotal\s+visible\s+on\s+`?tools/list`?\s*\|?\s*(\d+)\b", re.IGNORECASE), "tools_list"),
    # Architecture-table component-row phrasings used in BAT-v2 §
    # "Architecture": "Core tools on `tools/list` | 23" and "Gateways on
    # `tools/list` | 12". Requires either the pipe table separator or the
    # backtick around tools/list so bare prose like "core tools on
    # tools/list 23" (no anchor) doesn't false-fire on future doc drift.
    (re.compile(r"\bcore\s+tools?\s+on\s+(?:`tools/list`|\|?\s*tools/list\s*\|)\s*\|?\s*(\d+)\b", re.IGNORECASE), "core"),
    (re.compile(r"\bgateways?\s+on\s+(?:`tools/list`|\|?\s*tools/list\s*\|)\s*\|?\s*(\d+)\b", re.IGNORECASE), "gateways"),
]

# Per-gateway pattern: "manage_X (N)", "`manage_X` (N)", "<b>manage_X</b> (N)",
# "### manage_X (N tools)", "manage_X (N tools, 7 original + 3 library tools)".
# The 0-10 char window between the gateway name and the open paren
# tolerates backticks and short HTML tags without matching across
# unrelated text. The trailing class allows `)` (bare or `(N tools)`),
# `,` (qualifier follows like "(N tools, 7 original + ...)"), or
# whitespace + non-paren (e.g. "(N tools generic across...)").
PER_GATEWAY_PATTERN = re.compile(
    r"\b(manage_[a-z_]+)\b[^(\n]{0,10}\((\d+)(?:\s+tools?)?(?:[,)]|\s+(?:tools?|original|library|read|write))"
)

# Per-gateway in markdown tables: "| `manage_X` | N |" — the count lives
# in a separate column from the name, so the parenthesis-anchored pattern
# above misses these.
PER_GATEWAY_TABLE_PATTERN = re.compile(
    r"\|\s*`?(manage_[a-z_]+)`?\s*\|\s*(\d+)\s*\|"
)

# "AI calls `manage_X` ... sees N tools" — gateway op-count claim where
# the gateway name and the count are separated by descriptive prose. The
# 1-80 char window is loose enough to cover phrasings like "AI calls
# `manage_native_rules_and_apps` with no args, sees 12 tools" without
# crossing sentence boundaries (terminated by `.` or newline).
PER_GATEWAY_SEES_PATTERN = re.compile(
    r"`(manage_[a-z_]+)`[^.\n]{1,80}\bsees?\s+(?:catalog\s+of\s+)?(\d+)\s+tools?\b"
)


def check_tool_counts() -> list[dict]:
    """Verify documented tool counts match the canonical counts derived
    from hubitat-mcp-server.groovy. Skips historical / migration
    contexts."""
    findings: list[dict] = []
    canonical = _extract_canonical_counts()
    if canonical is None:
        findings.append(
            {
                "file": "hubitat-mcp-server.groovy",
                "line": 0,
                "rule": "TOOL_COUNT",
                "message": (
                    "Could not extract canonical tool counts from "
                    "getGatewayConfig() / getAllToolDefinitions(). The "
                    "lint can't verify doc consistency until the parser "
                    "is updated to handle the current source layout."
                ),
                "severity": "error",
                "source": "",
            }
        )
        return findings

    for doc_path in DOC_FILES_FOR_COUNTS:
        if not doc_path.exists():
            continue
        rel = str(doc_path.relative_to(REPO_ROOT)).replace("\\", "/")
        content = doc_path.read_text(encoding="utf-8", errors="replace")

        # High-level counts. Track (line, kind, actual) so the same drift
        # surfaced by two overlapping patterns doesn't dupe.
        seen: set[tuple[int, str, int]] = set()
        for pat, kind in COUNT_PATTERNS:
            expected = canonical[kind]
            for m in pat.finditer(content):
                if _is_historical_at(content, m.start(), m.end()):
                    continue
                actual = int(m.group(1))
                if actual == expected:
                    continue
                line_no = content[:m.start()].count("\n") + 1
                dedup_key = (line_no, kind, actual)
                if dedup_key in seen:
                    continue
                seen.add(dedup_key)
                line_start = content.rfind("\n", 0, m.start()) + 1
                line_end = content.find("\n", m.start())
                if line_end == -1:
                    line_end = len(content)
                line_text = content[line_start:line_end].strip()
                findings.append(
                    {
                        "file": rel,
                        "line": line_no,
                        "rule": "TOOL_COUNT",
                        "message": (
                            f"{kind} count claims {actual}, canonical is "
                            f"{expected}. Update doc or check that the "
                            "claim isn't historical (add a v0.X.Y marker, "
                            "→ migration arrow, or 'was N' phrasing to "
                            "skip)."
                        ),
                        "severity": "error",
                        "source": line_text[:200],
                    }
                )

        # Per-gateway op counts: parenthesized form, markdown table form,
        # and "AI ... sees N tools" gateway-context form.
        per_gw_seen: set[tuple[int, str, int]] = set()
        for m in (
            list(PER_GATEWAY_PATTERN.finditer(content))
            + list(PER_GATEWAY_TABLE_PATTERN.finditer(content))
            + list(PER_GATEWAY_SEES_PATTERN.finditer(content))
        ):
            if _is_historical_at(content, m.start(), m.end()):
                continue
            gw_name = m.group(1)
            actual = int(m.group(2))
            line_no_dedup = content[:m.start()].count("\n") + 1
            dedup_key = (line_no_dedup, gw_name, actual)
            if dedup_key in per_gw_seen:
                continue
            per_gw_seen.add(dedup_key)
            expected = canonical["per_gateway"].get(gw_name)
            if expected is None:
                # Unknown gateway name in docs — likely a renamed or
                # removed gateway; surface the canonical list to make
                # the typo / stale-rename obvious.
                line_no = content[:m.start()].count("\n") + 1
                known = ", ".join(sorted(canonical["per_gateway"].keys()))
                findings.append(
                    {
                        "file": rel,
                        "line": line_no,
                        "rule": "TOOL_COUNT",
                        "message": (
                            f"Doc references unknown gateway {gw_name!r}. "
                            "Either the gateway was renamed/removed in "
                            "the Groovy source, or the doc reference is "
                            f"stale. Known gateways: {{{known}}}."
                        ),
                        "severity": "error",
                        "source": "",
                    }
                )
                continue
            if actual != expected:
                line_no = content[:m.start()].count("\n") + 1
                line_start = content.rfind("\n", 0, m.start()) + 1
                line_end = content.find("\n", m.start())
                if line_end == -1:
                    line_end = len(content)
                line_text = content[line_start:line_end].strip()
                findings.append(
                    {
                        "file": rel,
                        "line": line_no,
                        "rule": "TOOL_COUNT",
                        "message": (
                            f"{gw_name} claims {actual} ops, canonical "
                            f"is {expected}."
                        ),
                        "severity": "error",
                        "source": line_text[:200],
                    }
                )

    return findings


# ---------------------------------------------------------------------------
# Tool-name consistency check
# ---------------------------------------------------------------------------
#
# Catches a different drift class than tool-counts: stale tool-name
# references in docs (e.g., `get_rule_diagnostics` after the v0.X rename
# to `custom_get_rule_diagnostics`). Tool-counts are silent on names —
# the count was right but the rename wasn't propagated. This check scans
# markdown table rows that reference a tool by backtick-wrapped identifier
# in first-column position, and flags any name not in the canonical set
# extracted from `getAllToolDefinitions()` + `getGatewayConfig()`.

# Files in scope: ones with proper tool tables. BAT-v2 excluded — it
# references tools by name in test prose (not first-column table position),
# so a first-column scan would miss its mentions and a prose scan would
# false-positive on intentional historical references.
DOC_FILES_FOR_TOOL_NAMES = [
    REPO_ROOT / "README.md",
    REPO_ROOT / "TOOL_GUIDE.md",
    REPO_ROOT / "agent-skill" / "hubitat-mcp" / "tool-reference.md",
]

# Markdown table row in the form `| `name` | description |` — the leading
# `|` + whitespace + backtick + snake_case identifier + closing backtick
# anchors the tool-table-cell shape. Lookahead for `|` ensures we're in
# table column 1, not just any backtick-wrapped reference in prose.
TOOL_TABLE_ROW_PATTERN = re.compile(
    r"^\s*\|\s*`([a-z_]+)`\s*(?=\|)",
    re.MULTILINE,
)

# Markdown table separator (`|---|---|`) — anchors backward search for the
# header row. Tolerant of optional `:` alignment markers and varying dash
# counts.
TABLE_SEPARATOR_PATTERN = re.compile(
    r"^\s*\|(?:\s*:?-{2,}:?\s*\|)+\s*$",
    re.MULTILINE,
)

# Header text in column 1 that signals "this table lists MCP tools" —
# matched case-insensitively, whole-cell. RM action/condition/comparison
# tables use "Type"/"Comparison"/etc. and are correctly excluded.
TOOL_TABLE_HEADER_NAMES = {"tool", "name", "mcp tool", "tool name"}

# Identifiers that legitimately appear in tool-table-shaped rows even
# under a "Tool"/"Name" header but are not in getAllToolDefinitions(). Add
# entries here when a future doc legitimately uses a tool-table shape for
# non-tool entries. Keep small — every entry here is a blind spot for
# the lint.
TOOL_NAME_ALLOWLIST: set[str] = set()


def _table_header_for_match(content: str, match_start: int) -> str | None:
    """Return the column-1 header text for the markdown table containing
    `match_start`, or None if no table-header context is found.

    Scans backward for the nearest table-separator line (`|---|---|`).
    The line immediately above the separator is the header row; column 1
    is everything between the leading `|` and the next `|`. Returns the
    header text lowercased and stripped of `**` emphasis."""
    sep_match = None
    for m in TABLE_SEPARATOR_PATTERN.finditer(content, 0, match_start):
        sep_match = m
    if sep_match is None:
        return None
    header_end = content.rfind("\n", 0, sep_match.start())
    if header_end == -1:
        return None
    header_start = content.rfind("\n", 0, header_end) + 1
    header_line = content[header_start:header_end]
    cells = header_line.split("|")
    if len(cells) < 2:
        return None
    col1 = cells[1].strip().strip("*").strip().lower()
    return col1


def check_tool_name_consistency() -> list[dict]:
    """Verify tool-name references in doc tables match canonical names.

    For each markdown table row in the form `| `<name>` | ...`, check
    that `<name>` is a valid tool name (in `getAllToolDefinitions()`),
    a gateway name (in `getGatewayConfig()`), or in the explicit
    allowlist. Flag stale references that look like renamed tools.

    HTML-comment scope (`<!-- ... -->`) is NOT respected — same trade-off
    as the comment-stripping decision documented in
    `_extract_canonical_counts()`. A future doc author who wraps a tool
    table in an HTML comment to suppress it would still have its rows
    linted; today's docs don't trigger this."""
    findings: list[dict] = []
    canonical = _extract_canonical_counts()
    if canonical is None:
        # check_tool_counts already flagged this with a clearer message.
        return findings

    valid_names = canonical["tool_names"] | canonical["gateway_names"] | TOOL_NAME_ALLOWLIST

    for doc_path in DOC_FILES_FOR_TOOL_NAMES:
        if not doc_path.exists():
            continue
        rel = str(doc_path.relative_to(REPO_ROOT)).replace("\\", "/")
        content = doc_path.read_text(encoding="utf-8", errors="replace")

        seen: set[tuple[int, str]] = set()
        for m in TOOL_TABLE_ROW_PATTERN.finditer(content):
            if _is_historical_line(content, m.start()):
                continue
            name = m.group(1)
            if name in valid_names:
                continue
            header_col1 = _table_header_for_match(content, m.start())
            if header_col1 not in TOOL_TABLE_HEADER_NAMES:
                # This row is in a non-tool table (RM action types,
                # condition operators, gateway op lists with their own
                # naming scheme, etc.). Skip — outside scope of this lint.
                continue
            line_no = content[:m.start()].count("\n") + 1
            dedup_key = (line_no, name)
            if dedup_key in seen:
                continue
            seen.add(dedup_key)
            line_start = content.rfind("\n", 0, m.start()) + 1
            line_end = content.find("\n", m.start())
            if line_end == -1:
                line_end = len(content)
            line_text = content[line_start:line_end].strip()
            findings.append(
                {
                    "file": rel,
                    "line": line_no,
                    "rule": "TOOL_NAME",
                    "message": (
                        f"Tool table references `{name}` which is not in "
                        f"`getAllToolDefinitions()` or `getGatewayConfig()`. "
                        "Likely a stale rename or typo. If this is "
                        "intentionally a non-tool identifier in a tool-"
                        "table-shaped row, add to TOOL_NAME_ALLOWLIST in "
                        "tests/sandbox_lint.py."
                    ),
                    "severity": "error",
                    "source": line_text[:200],
                }
            )

    return findings


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

IS_CI = os.environ.get("CI") == "true" or os.environ.get("GITHUB_ACTIONS") == "true"


def check_tool_guide_pointers() -> list[dict]:
    """Verify every get_tool_guide(section='X') pointer in the .groovy schemas
    references a section key that actually exists in getToolGuideSections().

    Failure mode this catches: schema description text says
    "Call get_tool_guide(section='X') for the foo reference" but X is not
    a key in the getToolGuideSections() map. Flat-mode callers following
    the pointer get a 'section not found' response or worse, an empty
    payload they have to interpret.

    Drift dimension also caught: every getToolGuideSections key should have
    a corresponding heading in TOOL_GUIDE.md. We assert presence (not exact
    content match) so prose tweaks don't trip the lint; renames or deletes do.
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    tool_guide = REPO_ROOT / "TOOL_GUIDE.md"
    if not server.exists() or not tool_guide.exists():
        return findings

    src = server.read_text(encoding="utf-8")
    tg = tool_guide.read_text(encoding="utf-8")

    # 1. Extract every section key from getToolGuideSections().
    #    Match lines like `        device_authorization: '''## Device Authorization (CRITICAL)`
    sections_block_match = re.search(
        r"def getToolGuideSections\(\)\s*\{\s*return\s*\[(.*?)\n\s*\]\s*\}",
        src,
        re.DOTALL,
    )
    if not sections_block_match:
        findings.append({
            "file": str(server.relative_to(REPO_ROOT)),
            "line": 1,
            "severity": "error",
            "code": "tool-guide-no-sections",
            "message": "Could not locate getToolGuideSections() return literal — has the function shape changed?",
        })
        return findings

    sections_block = sections_block_match.group(1)
    section_keys = set(re.findall(r"^\s*([a-z_][a-z0-9_]*):\s*'''", sections_block, re.MULTILINE))

    # 2. Extract every get_tool_guide(section='X') reference from the .groovy.
    #    Tolerate both single and double quotes; whitespace around the `=`.
    pointer_re = re.compile(r"get_tool_guide\(section\s*=\s*['\"]([a-z_][a-z0-9_]*)['\"]\)")
    for line_no, line in enumerate(src.splitlines(), start=1):
        for ptr in pointer_re.findall(line):
            if ptr not in section_keys:
                findings.append({
                    "file": str(server.relative_to(REPO_ROOT)),
                    "line": line_no,
                    "severity": "error",
                    "code": "tool-guide-broken-pointer",
                    "message": (
                        f"get_tool_guide(section='{ptr}') points at a section that is NOT a key "
                        f"in getToolGuideSections(). Either add the section to the dispatcher or "
                        f"fix the pointer. Known sections: {sorted(section_keys)}."
                    ),
                })

    # 3. Drift check: every section key should have a matching heading anchor in TOOL_GUIDE.md.
    #    Translate snake_case key -> the heading text the engineer wrote it from.
    #    Use a substring check (presence in TOOL_GUIDE.md) rather than exact slugify — keeps
    #    the lint tolerant of prose edits while catching renames.
    key_to_heading_hint = {
        "device_authorization": "Device Authorization",
        "hub_admin_write": "Hub Admin Write",
        "virtual_devices": "Virtual Device",
        "update_device": "update_device",
        "rules": "Rule Structure Reference",
        "backup": "Backup System",
        "file_manager": "File Manager",
        "performance": "Performance Tips",
        "builtin_app_tools": "Built-in App Tools",
        "update_native_app_reference": "`update_native_app` capability reference",
        "create_native_app_reference": "`create_native_app` reference",
    }
    for key in section_keys:
        hint = key_to_heading_hint.get(key)
        if hint is None:
            # New section added to the .groovy without a hint mapping above.
            # Fail loud rather than silently skip — keeps this lint honest.
            findings.append({
                "file": str(server.relative_to(REPO_ROOT)),
                "line": 1,
                "severity": "error",
                "code": "tool-guide-no-heading-hint",
                "message": (
                    f"getToolGuideSections key '{key}' has no entry in key_to_heading_hint "
                    f"(in tests/sandbox_lint.py). Add a mapping so the TOOL_GUIDE.md drift "
                    f"check can verify the heading still exists."
                ),
            })
            continue
        if hint not in tg:
            findings.append({
                "file": str(tool_guide.relative_to(REPO_ROOT)),
                "line": 1,
                "severity": "error",
                "code": "tool-guide-heading-missing",
                "message": (
                    f"getToolGuideSections key '{key}' baked into the .groovy, but the matching "
                    f"heading '{hint}' is not present in TOOL_GUIDE.md. Either restore the heading "
                    f"or update key_to_heading_hint in tests/sandbox_lint.py."
                ),
            })

    return findings


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
        # Groovy's bare-form GString supports `$identifier[.prop...]` for
        # property access. `$foo.getClass` (no parens) is legal and triggers
        # the no-arg method at runtime, so the sandbox restriction applies.
        "Bare $obj.getClass GString form is flagged",
        'log.warn "type=$obj.getClass"',
        [("SANDBOX-001", True)],
    ),
    (
        "Bare $var reference without a forbidden identifier is NOT flagged",
        'log.warn "name=$user.email"',
        [("SANDBOX-001", False), ("SANDBOX-002", False)],
    ),
    (
        "Bare $var inside a single-quoted string is NOT expanded (still a miss)",
        "log.warn 'type=$obj.getClass'",
        [("SANDBOX-001", False)],
    ),
    (
        "Bare $Locale.default in an interpolation is flagged",
        'log.info "loc=$Locale.default"',
        [("SANDBOX-002", True)],
    ),
    (
        "new ArrayDeque() (no-arg constructor) is flagged",
        "def stack = new ArrayDeque()",
        [("SANDBOX-012", True)],
    ),
    (
        "new ArrayDeque(collection) is flagged",
        "def stack = new ArrayDeque(apps)",
        [("SANDBOX-012", True)],
    ),
    (
        "new java.util.ArrayDeque() fully-qualified is flagged",
        "def stack = new java.util.ArrayDeque()",
        [("SANDBOX-012", True)],
    ),
    (
        "Groovy list literal `[]` is NOT flagged (the safe alternative)",
        "def stack = []",
        [("SANDBOX-012", False)],
    ),
    (
        "new LinkedList() is NOT flagged (LinkedList is sandbox-allowed)",
        "def stack = new LinkedList()",
        [("SANDBOX-012", False)],
    ),
    (
        "new GroovyShell() is flagged",
        "def shell = new GroovyShell()",
        [("SANDBOX-013", True)],
    ),
    (
        "new groovy.lang.GroovyShell() fully-qualified is flagged",
        "def shell = new groovy.lang.GroovyShell(binding)",
        [("SANDBOX-013", True)],
    ),
    (
        "GroovyShell.parse(...) static call is flagged",
        "def script = GroovyShell.parse(src)",
        [("SANDBOX-013", True)],
    ),
    (
        "GroovyShell mentioned in a string literal is NOT flagged",
        'log.warn "do not use GroovyShell here"',
        [("SANDBOX-013", False)],
    ),
]


# Doc-content fixtures for the count-pattern + historical-skip
# machinery. Each fixture pairs a doc-shaped string with a synthetic
# canonical-counts dict and asserts which TOOL_COUNT findings should
# appear when `_check_doc_against_canonical` is run.
#
# Run via `_run_count_self_test` (separate from `SELF_TEST_CASES` which
# only covers Groovy `scan_source` rules). The runner mutates the
# canonical dict to verify each pattern fires on real drift, and
# verifies historical-skip rules with paired pos/neg cases per pattern.
COUNT_SELF_TEST_CASES = [
    # === Headline count patterns: each phrasing should fire on a stale value ===
    (
        "total in `(N total)`",
        "Surface area: (101 total) tools currently exposed.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `N tools total`",
        "The MCP exposes 101 tools total across the surface.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `MCP Tools (N total)`",
        "## MCP Tools (101 total)",
        {"total": 102}, ["total"],
    ),
    (
        "total in `exposes N tools`",
        "The server exposes 101 tools across 12 gateways.",
        {"total": 102, "gateways": 12}, ["total"],
    ),
    (
        "total in `All N tools are covered`",
        "All 101 tools are covered by at least one BAT scenario.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `N tool definitions`",
        "getAllToolDefinitions() returns all 101 tool definitions.",
        {"total": 102}, ["total"],
    ),
    (
        "total in table-row form",
        "| Total tools in codebase | 101 |",
        {"total": 102}, ["total"],
    ),
    (
        "core in `N core tools`",
        "There are 23 core tools plus the gateway proxies.",
        {"core": 24}, ["core"],
    ),
    (
        "core in `N core + N gateways`",
        "Architecture: 23 core + 12 gateways on tools/list.",
        {"core": 24, "gateways": 12}, ["core"],
    ),
    (
        "core in `Core tools on tools/list | N` table-row",
        "| Core tools on `tools/list` | 23 |",
        {"core": 24}, ["core"],
    ),
    (
        "gateways in `N gateways`",
        "Currently 12 gateways are registered.",
        {"gateways": 13}, ["gateways"],
    ),
    (
        "gateways in `Gateways on tools/list | N` table-row",
        "| Gateways on `tools/list` | 12 |",
        {"gateways": 13}, ["gateways"],
    ),
    (
        "tools_list in `N on tools/list`",
        "There are 35 on tools/list (the visible surface).",
        {"tools_list": 36}, ["tools_list"],
    ),
    (
        "tools_list in `Total visible on tools/list | N` table-row",
        "| Total visible on `tools/list` | 35 |",
        {"tools_list": 36}, ["tools_list"],
    ),
    (
        "proxied in `(N proxied)`",
        "Architecture: 35 visible (80 proxied) tools.",
        {"proxied": 79}, ["proxied"],
    ),
    (
        "proxied in `Tools proxied behind gateways | N` table-row",
        "| Tools proxied behind gateways | 78 |",
        {"proxied": 79}, ["proxied"],
    ),

    # === Historical-skip: WIDE markers (line-scope) ===
    # Each WIDE marker should let the count escape lint even with a
    # stale value, regardless of how far it sits from the count.
    (
        "WIDE skip: `**vN.N.N**:` anywhere on the line",
        "**v0.7.7**: 74 tools listed at that point in the codebase history.",
        {"total": 99}, [],   # 74 != 99 but skipped
    ),
    (
        "WIDE skip: `vN.N.N (` parenthetical",
        "Run prompts on v0.7.7 (all 74 on tools/list) and v0.8.0 to compare.",
        {"total": 99}, [],
    ),
    (
        "NARROW skip: `(vN.N.N` close to a count scopes that count only",
        # `(v0.7.7)` is within ±20 of `74` (chars 13→23) but ~16 chars
        # from `9 gateways` (chars 13→39, end of `(v0.7.7` at char 19).
        # `9 gateways` is OUTSIDE the window relative to either bound,
        # so the count fires; `74` would too if any pattern matched it
        # (no current pattern catches bare `N tools` without context).
        "Architecture (v0.7.7): 74 tools across 9 gateways.",
        {"total": 99, "gateways": 12}, ["gateways"],
    ),

    # === Historical-skip: NARROW markers (window-scope) ===
    # Each NARROW marker scopes only a count within ±20 chars; a
    # live count further out should still fire.
    (
        "NARROW skip: `was N` near match scopes the nearby count",
        "We support 80 proxied tools (was 75 before bundling).",
        # 80 matches canonical (skipped here), 75 is historical (skipped by `was N`)
        {"proxied": 80}, [],
    ),
    (
        "NARROW skip does NOT swallow a live count further away",
        "23 core tools are exposed today, but it was 18 in v0.7.",
        # 23 is live → fire on stale canonical; 18 has `was` near it → skip
        {"core": 24}, ["core"],
    ),
    (
        "NARROW skip: `→` migration arrow scopes nearby count",
        "Migration: was 21 core → 23 core after the gateway split.",
        # 21 historical (within window of `→`); 23 is also within window → both skip
        {"core": 25}, [],
    ),
    (
        "NARROW skip: `previously N` near match",
        "There are 12 gateways (previously 9 before split).",
        {"gateways": 12}, [],
    ),
    (
        "NARROW skip: `before: N` near match",
        "(Before: 98) now 101 MCP tools registered.",
        # 101 MCP tools would drift (canonical=102) but `Before: 98` sits fully
        # in its look-back window → the `before\s*[:=]\s*\d+` pattern matches → skip.
        {"total": 102}, [],
    ),

    # === Section-level: ancestor walk ===
    (
        "Section skip: `## Version History` ancestor flips contained counts to historical",
        "## Version History\n\n- v1.0.0: was 99 tools.\n- v0.9.0: 95 tools.\n",
        {"total": 102}, [],
    ),
    (
        "Section skip: `### vN.N.N` UNDER Version History inherits historical via ancestor walk",
        "## Version History\n\n### v1.0.0 (2026-01-01)\n\n95 tools listed.\n",
        {"total": 102}, [],
    ),
    (
        "Section skip: `## Changelog` ancestor flips contained counts",
        "## Changelog\n\n- 95 tools at last release.\n",
        {"total": 102}, [],
    ),
    (
        "Section skip: `## Migration` ancestor flips contained counts",
        "## Migration\n\n9 gateways became 12 gateways after the split.\n",
        {"gateways": 12}, [],
    ),
    (
        "NEGATIVE: a live test scenario heading with `(vN.N.N)` is NOT historical",
        "### T120 — All N gateways in one session (v0.8.0)\n\nThe LLM sees 12 gateways live.\n",
        # If the old over-aggressive HISTORICAL_SECTION_HEADINGS regex still
        # treated `(v0.8.0)` headings as historical, this would skip silently.
        # With the ancestor-walk-only design, we expect the count to fire.
        {"gateways": 13}, ["gateways"],
    ),
    (
        "NEGATIVE: stale count IN the `(v0.8.0)`-suffixed live heading itself fires",
        # Pin the maintainer's specific regression: a live test scenario
        # whose title carries a `(v0.8.0)` provenance suffix should NOT
        # cause a stale count earlier in the same title to be skipped.
        # Validates that `\(v\d+...` is NARROW (window-scope), not WIDE.
        "### T120 — All 10 gateways in one session (v0.8.0)\n",
        {"gateways": 12}, ["gateways"],
    ),

    # === Per-gateway extraction patterns ===
    (
        "Per-gateway: `manage_X (N tools)` parenthesized",
        "The `manage_logs` (8 tools) gateway covers system-log access.",
        {"per_gateway": {"manage_logs": 9}}, ["per_gateway:manage_logs"],
    ),
    (
        "Per-gateway: `manage_X (N tools, qualifier)` allows trailing qualifier",
        "manage_app_driver_code (10 tools, 7 original + 3 library tools)",
        {"per_gateway": {"manage_app_driver_code": 11}}, ["per_gateway:manage_app_driver_code"],
    ),
    (
        "Per-gateway: markdown-table form",
        "| `manage_logs` | 8 |",
        {"per_gateway": {"manage_logs": 9}}, ["per_gateway:manage_logs"],
    ),
    (
        "Per-gateway: `sees N tools` after backticked gateway name",
        "AI calls `manage_native_rules_and_apps` with no args, sees 12 tools.",
        {"per_gateway": {"manage_native_rules_and_apps": 13}}, ["per_gateway:manage_native_rules_and_apps"],
    ),
    (
        "Per-gateway: `sees catalog of N tools` phrasing (catalog-of variant)",
        "AI calls `manage_installed_apps` with no args, sees catalog of 4 tools.",
        {"per_gateway": {"manage_installed_apps": 5}}, ["per_gateway:manage_installed_apps"],
    ),

    # === Required #2 — asymmetric window: trailing `was N` must not suppress live count ===
    # The maintainer's concrete miss: "22 core tools today, was 18 previously."
    # With a symmetric ±20 window the `was` at pos 21 fell inside the forward
    # half and silently swallowed the live count. The look-back-only window
    # (ending at match_start) keeps `was` outside and lets the drift fire.
    (
        "NARROW asymmetric: trailing `was N` does NOT suppress preceding live count",
        "22 core tools today, was 18 previously.",
        {"core": 23}, ["core"],
    ),

    # === Required #3 — positive fixtures for every COUNT_PATTERN ===
    (
        "total in `covering N tools`",
        "This release is covering 101 tools across all gateways.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `exposing N tools`",
        "The server is exposing 101 tools to the AI consumer.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `has N tools total`",
        "The implementation has 101 tools total in the registry.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `N MCP tools`",
        "There are 101 MCP tools registered for dispatch.",
        {"total": 102}, ["total"],
    ),
    (
        "total in `across all N tools`",
        "The search index spans across all 101 tools in the catalog.",
        {"total": 102}, ["total"],
    ),
    (
        "proxied in `N additional tools`",
        "Each gateway exposes 78 additional tools beyond the core set.",
        {"proxied": 79}, ["proxied"],
    ),
    (
        "total in `from N items to M`",
        "The client goes from 35 items to 101 after gateway expansion.",
        {"total": 102}, ["total"],
    ),
    (
        "proxied in bare `N proxied` (not paren-prefix, named fixture)",
        "Summary: 23 core + 13 gateways, 80 proxied, 103 total tools.",
        {"proxied": 79}, ["proxied"],
    ),
    (
        "total in `N total` punctuation-bounded (named fixture)",
        "The surface area is 101 total, split across core and gateways.",
        {"total": 102}, ["total"],
    ),
    (
        "tools_list in `N items on tools/list`",
        "There are 35 items on tools/list for the AI to discover.",
        {"tools_list": 36}, ["tools_list"],
    ),
    (
        "proxied in `N proxied tools` (named fixture)",
        "The server routes calls to 80 proxied tools inside gateways.",
        {"proxied": 79}, ["proxied"],
    ),
    (
        "total in `total tools in codebase` table-row (named fixture)",
        "| Total tools in codebase | 101 |",
        {"total": 102}, ["total"],
    ),

    # === Required #4 — sibling section does NOT propagate historical status ===
    # A `## Version History` heading and `## Live API` heading are siblings.
    # A count under `## Live API` must not inherit historical status from the
    # sibling; only an ANCESTOR heading (strictly lower level) does that.
    (
        "Section skip: sibling `## Version History` does NOT silence count in sibling `## Live API`",
        "## Version History\n\n- v0.7: 90 tools, 8 gateways\n\n## Live API\n\n23 core tools listed.\n",
        {"core": 24}, ["core"],
    ),

    # === Required #5 — ancestor inheritance across two heading levels ===
    # A `####`-level heading under `### vN.N.N` under `## Version History`
    # should still inherit historical status via the ancestor walk.
    (
        "Section skip: `####` UNDER `### vN.N.N` UNDER `## Version History` inherits via two levels",
        "## Version History\n\n### v1.0.0 (2026-01-15)\n\n#### Old release details\n\n95 tools in release.\n",
        {"total": 102}, [],
    ),

    # === Required #6 — NARROW ±20 boundary fixtures for each marker ===
    # After the asymmetric-window change the meaningful threshold is the
    # look-BACK direction only: [match_start - 20, match_start).
    # Each pair below has:
    #   "inside"  (marker at match_start-20 = win_start): skip
    #   "outside" (marker at match_start-21 < win_start): fire
    #
    # Arrow `→` (1 char + 19 dots = 20 chars to match_start → inside):
    (
        "NARROW boundary: `→` 20 chars before match (inside window, skip)",
        "→" + "." * 19 + "23 core tools here.",
        {"core": 24}, [],
    ),
    (
        "NARROW boundary: `→` 21 chars before match (outside window, fire)",
        "→" + "." * 20 + "23 core tools here.",
        {"core": 24}, ["core"],
    ),
    # `previously N` (13 chars + 7 filler = 20 chars to match_start → inside):
    (
        "NARROW boundary: `previously N` 20 chars before match (inside window, skip)",
        "previously 8 " + "." * 7 + "23 core tools here.",
        {"core": 24}, [],
    ),
    (
        "NARROW boundary: `previously N` 21 chars before match (outside window, fire)",
        "previously 8 " + "." * 8 + "23 core tools here.",
        {"core": 24}, ["core"],
    ),
    # `before: N` (10 chars + 10 filler = 20 chars to match_start → inside):
    (
        "NARROW boundary: `before: N` 20 chars before match (inside window, skip)",
        "before: 8 " + "." * 10 + "23 core tools here.",
        {"core": 24}, [],
    ),
    (
        "NARROW boundary: `before: N` 21 chars before match (outside window, fire)",
        "before: 8 " + "." * 11 + "23 core tools here.",
        {"core": 24}, ["core"],
    ),
    # `(vN.N.N` (8 chars + 12 filler = 20 chars to match_start → inside):
    (
        "NARROW boundary: `(vN.N.N` 20 chars before match (inside window, skip)",
        "(v0.7.7 " + "." * 12 + "23 core tools here.",
        {"core": 24}, [],
    ),
    (
        "NARROW boundary: `(vN.N.N` 21 chars before match (outside window, fire)",
        "(v0.7.7 " + "." * 13 + "23 core tools here.",
        {"core": 24}, ["core"],
    ),

    # === Nit C — additional HISTORICAL_SECTION_HEADINGS variants ===
    (
        "Section skip: `## Release Notes` ancestor flips contained counts",
        "## Release Notes\n\n- 95 tools at last release.\n",
        {"total": 102}, [],
    ),
    (
        "Section skip: `## History` ancestor flips contained counts",
        "## History\n\n9 gateways registered in earlier versions.\n",
        {"gateways": 12}, [],
    ),
]


def _check_doc_against_canonical(content: str, canonical_override: dict) -> set[str]:
    """Run the doc-scanning checks against `content` with a synthetic
    canonical dict overlaid on the real extractor result. Returns the
    set of finding-kinds reported (e.g. {'total', 'core',
    'per_gateway:manage_logs'}). Used by `_run_count_self_test` to
    verify each pattern fires on drift and each historical-skip rule
    holds.
    """
    real = _extract_canonical_counts() or {
        "total": 0, "core": 0, "gateways": 0, "tools_list": 0,
        "proxied": 0, "per_gateway": {}, "tool_names": set(),
        "gateway_names": set(), "proxied_names": set(),
    }
    canonical = dict(real)
    canonical.update(canonical_override)
    if "per_gateway" in canonical_override:
        # When overriding per-gateway, merge with real per_gateway so other
        # gateways referenced incidentally in the fixture text don't all
        # spuriously fire.
        merged = dict(real["per_gateway"])
        merged.update(canonical_override["per_gateway"])
        canonical["per_gateway"] = merged

    kinds: set[str] = set()
    seen: set[tuple[int, str, int]] = set()

    # COUNT_PATTERNS scan
    for pat, kind in COUNT_PATTERNS:
        expected = canonical[kind]
        for m in pat.finditer(content):
            if _is_historical_at(content, m.start(), m.end()):
                continue
            actual = int(m.group(1))
            if actual == expected:
                continue
            line_no = content[:m.start()].count("\n") + 1
            dedup = (line_no, kind, actual)
            if dedup in seen:
                continue
            seen.add(dedup)
            kinds.add(kind)

    # Per-gateway scans
    per_gw_seen: set[tuple[int, str, int]] = set()
    for m in (
        list(PER_GATEWAY_PATTERN.finditer(content))
        + list(PER_GATEWAY_TABLE_PATTERN.finditer(content))
        + list(PER_GATEWAY_SEES_PATTERN.finditer(content))
    ):
        if _is_historical_at(content, m.start(), m.end()):
            continue
        gw_name = m.group(1)
        actual = int(m.group(2))
        line_no = content[:m.start()].count("\n") + 1
        dedup = (line_no, gw_name, actual)
        if dedup in per_gw_seen:
            continue
        per_gw_seen.add(dedup)
        expected = canonical["per_gateway"].get(gw_name)
        if expected is None or actual != expected:
            kinds.add(f"per_gateway:{gw_name}")

    return kinds


def _run_count_self_test() -> int:
    failures = 0
    for i, (desc, content, override, expected_kinds) in enumerate(
        COUNT_SELF_TEST_CASES, start=1
    ):
        actual_kinds = _check_doc_against_canonical(content, override)
        expected_set = set(expected_kinds)
        if actual_kinds != expected_set:
            failures += 1
            print(
                f"COUNT-SELF-TEST FAIL [{i}] {desc}\n"
                f"  expected kinds: {sorted(expected_set)}\n"
                f"  actual kinds:   {sorted(actual_kinds)}\n"
                f"  content: {content!r}"
            )
    return failures


def run_self_test() -> int:
    """Scan inline fixtures through scan_source and confirm each rule
    triggers where expected. Uses scan_source (not strip_comments_and_strings
    + inline rule loop) so the self-test exercises the same code path
    CI uses for real files."""
    failures = 0
    for i, (desc, source, expected) in enumerate(SELF_TEST_CASES, start=1):
        findings = scan_source(source, f"<self-test case {i}>")
        hits = {f["rule"] for f in findings}
        for rule_id, should_match in expected:
            matched = rule_id in hits
            if matched != should_match:
                failures += 1
                stripped = strip_comments_and_strings(source)
                print(
                    f"SELF-TEST FAIL [{i}] {desc}\n"
                    f"  rule={rule_id} expected={'hit' if should_match else 'miss'} "
                    f"actual={'hit' if matched else 'miss'}\n"
                    f"  source: {source!r}\n"
                    f"  stripped: {stripped!r}"
                )

    # Sanity-check the canonical tool-count extractor: must succeed and
    # return a self-consistent count breakdown. Extractor failure here
    # would otherwise only surface as opaque "could not extract" errors
    # during real lint runs.
    canonical = _extract_canonical_counts()
    if canonical is None:
        failures += 1
        print(
            "SELF-TEST FAIL [tool-count extractor]\n"
            "  _extract_canonical_counts() returned None — getGatewayConfig() "
            "or getAllToolDefinitions() format may have changed in the "
            "Groovy source."
        )
    else:
        derived = canonical["core"] + sum(canonical["per_gateway"].values())
        if derived != canonical["total"]:
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-count extractor]\n"
                f"  derived total {derived} != reported total "
                f"{canonical['total']} — internal inconsistency in "
                "_extract_canonical_counts()"
            )
        # Compare the raw list length against the de-duplicated set size.
        # `total` is intentionally len(list); `tool_names` is set(list).
        # A duplicate `name:` entry in the Groovy source diverges them,
        # which is the regression class this assertion catches.
        if canonical["total"] != len(canonical["tool_names"]):
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-name extractor]\n"
                f"  raw `name:` count {canonical['total']} != "
                f"unique-names count {len(canonical['tool_names'])} — "
                "duplicate `name:` entries in getAllToolDefinitions()."
            )
        # gateway_names and tool_names should be disjoint by construction:
        # getAllToolDefinitions() lists every tool the dispatcher routes to
        # (core tools + every gateway's proxied operations), while
        # getGatewayConfig() registers the parent facades separately.
        # Gateways are NEVER entries in getAllToolDefinitions(); a name
        # appearing in both would mean a naming collision the dispatcher
        # would resolve in undefined order.
        if canonical["gateway_names"] & canonical["tool_names"]:
            overlap = canonical["gateway_names"] & canonical["tool_names"]
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-name extractor]\n"
                f"  gateway names overlap tool_names: {sorted(overlap)} — "
                "a gateway should not also have a getAllToolDefinitions() "
                "entry; check for naming collision."
            )
        # Every tool listed in a gateway's `tools:` array must exist in
        # getAllToolDefinitions() — gateways are facades, not registries.
        # A gateway pointing at a non-existent tool is dead-on-arrival:
        # the gateway dispatch path lazy-expands into the tool's schema
        # and signature, both pulled from getAllToolDefinitions(). This
        # catches the "removed a tool but forgot the gateway entry" and
        # "renamed a tool but missed the gateway entry" regressions.
        ghost_proxies = canonical["proxied_names"] - canonical["tool_names"]
        if ghost_proxies:
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-name extractor]\n"
                f"  gateways reference ghost tools (not in "
                f"getAllToolDefinitions()): {sorted(ghost_proxies)} — "
                "either remove the gateway entry or add the tool to "
                "getAllToolDefinitions()."
            )

    # Doc-content fixtures for COUNT_PATTERNS + historical-skip rules.
    count_failures = _run_count_self_test()
    failures += count_failures

    if failures:
        print(f"--- {failures} self-test failure(s) ---")
        return 1
    total_cases = len(SELF_TEST_CASES) + len(COUNT_SELF_TEST_CASES)
    print(
        f"Self-test: {total_cases} case(s) passed "
        f"({len(SELF_TEST_CASES)} sandbox, {len(COUNT_SELF_TEST_CASES)} count)."
    )
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

    # Check tool-count consistency between Groovy source and docs
    all_findings.extend(check_tool_counts())

    # Check tool-name references in doc tables match canonical tool names
    all_findings.extend(check_tool_name_consistency())

    # Check that every get_tool_guide(section='X') pointer in the schemas
    # references a section that actually exists in getToolGuideSections().
    # Catches the silent-truncation regression class of "trim points caller
    # at get_tool_guide(section=Y), but Y was never added to the dispatcher".
    all_findings.extend(check_tool_guide_pointers())

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
