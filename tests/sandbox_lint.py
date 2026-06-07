#!/usr/bin/env python3
"""
Hubitat Groovy Sandbox Linter

Scans .groovy files for patterns known to crash at runtime in the Hubitat
sandbox even though they compile fine. Also checks version consistency
across project files.

Exit 0 = clean, exit 1 = errors found.
Outputs GitHub Actions annotations when running in CI.
"""

import os
import re
import sys
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
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent

# Glob the whole libraries/ dir (not a hardcoded list) so every library module the app
# #includes is sandbox-scanned automatically -- when real code (e.g. the RM tools) moves into
# its own library file under the issue #209 modularization, the lint covers it with no edit here.
GROOVY_FILES = [
    REPO_ROOT / "hubitat-mcp-server.groovy",
    REPO_ROOT / "hubitat-mcp-rule.groovy",
    # Standalone e2e safety-net app: it runs in the Hubitat sandbox on the e2e hub, so lint it for
    # forbidden patterns here rather than discovering a violation only at live install time.
    REPO_ROOT / "e2e-deadman-watchdog.groovy",
    *sorted((REPO_ROOT / "libraries").glob("*.groovy")),
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
    {
        # The hub runs Groovy 2.4 (antlr2 parser), which rejects a bare `{ ... }`
        # block immediately after a `case X:` label as an ambiguous
        # parameterless-closure-vs-open-block. hubitat_ci's Groovy 3.0 (Parrot)
        # parser accepts it, so the Spock suite compiles clean while the real hub
        # refuses to save the app ("Ambiguous expression could be either a
        # parameterless closure expression or an isolated open code block").
        # Extract the case body into a helper method (or drop the wrapping braces
        # and declare no locals) so dispatch cases stay plain statements.
        "id": "SANDBOX-014",
        "pattern": r"\bcase\b[^:]*:\s*\{",
        "message": "Bare '{ }' block right after 'case X:' is rejected by the hub's Groovy 2.4 parser (ambiguous closure vs open block) even though hubitat_ci's Groovy 3.0 accepts it -- extract the case body to a method or remove the braces",
        "severity": "error",
    },
    {
        # The Hubitat sandbox forbids referencing a java.io stream/reader/writer class as a
        # ClassExpression (e.g. `instanceof InputStream`, a cast, or a typed declaration). The hub
        # rejects the app at PARSE time: "Expression [ClassExpression] is not allowed:
        # java.io.InputStream". Both hubitat_ci's Groovy 3.0 (real JVM) and a plain regex compile
        # such code fine, so ONLY a real-hub deploy catches it otherwise -- which is exactly how an
        # `instanceof InputStream` shipped this far. Duck-type instead: branch on byte[]/CharSequence
        # and read remaining bodies via `.bytes` / `.text` (see _readRespText), never naming the
        # class. Scans run on comment/string-stripped source, so doc mentions of these classes are
        # unaffected. (This is a curated list of the realistic accidental classes; the real-hub e2e
        # deploy remains the comprehensive compile gate for sandbox ClassExpressions not listed here.)
        "id": "SANDBOX-015",
        "pattern": r"\b(?:InputStream|OutputStream|FileInputStream|FileOutputStream|ByteArrayInputStream|ByteArrayOutputStream|DataInputStream|DataOutputStream|BufferedInputStream|BufferedOutputStream|BufferedReader|BufferedWriter|FileReader|FileWriter|InputStreamReader|OutputStreamWriter|RandomAccessFile|PushbackInputStream|PrintStream|PrintWriter)\b",
        "message": "java.io stream/reader/writer class referenced as a ClassExpression -- blocked by the Hubitat sandbox at parse time ('ClassExpression not allowed'). Duck-type instead: branch on byte[]/CharSequence and read via .bytes/.text rather than naming the class (e.g. avoid `instanceof InputStream`).",
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

    # Tool DEFINITIONS now live partly in #include'd library modules (issue #209): a domain's defs
    # sit in its libraries/*.groovy alongside its impl, contributed via _getAllToolDefinitions_part<Name>()
    # chunk methods. The app #includes every library module, so the canonical tool surface =
    # main + all library modules; concatenate them so those def chunks are parsed + counted. The
    # gateway config (getGatewayConfig) lives only in main, so the first-match carve below is unaffected.
    _lib_dir = REPO_ROOT / "libraries"
    if _lib_dir.is_dir():
        for _lib in sorted(_lib_dir.glob("*.groovy")):
            src += "\n" + _lib.read_text(encoding="utf-8", errors="replace")

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

    # Carve out getAllToolDefinitions() and its chunk helpers, then extract tool
    # names. PR1C split the over-64KB-bytecode getAllToolDefinitions() body into
    # _getAllToolDefinitions_part1..N() chunk methods that the public method just
    # concatenates; the tool defs now live in those chunks, so gather them all.
    # (The dispatcher body carries no `name:` lines, so including it is harmless;
    # a pre-split source with all defs in getAllToolDefinitions() still matches.)
    all_bodies = re.findall(
        r"^def (?:getAllToolDefinitions|_getAllToolDefinitions_part\w+)\(\) \{(.*?)^}",
        src,
        re.DOTALL | re.MULTILINE,
    )
    if not all_bodies:
        return None
    all_defs_text = "\n".join(all_bodies)
    # Keep raw list separate from the set so a duplicate `name:` entry
    # in getAllToolDefinitions() is detectable: the count check sees
    # total = len(list) > len(docs) and fires; the self-test cross-
    # checks list-len vs set-len. If we collapsed both into len(set),
    # duplicate-name regressions would be silent on both gates.
    raw_name_list = re.findall(r"^\s*name:\s*['\"]([a-z_]+)['\"]", all_defs_text, re.MULTILINE)
    tool_names: set[str] = set(raw_name_list)
    total = len(raw_name_list)

    # Count DISTINCT proxied tools, not the sum of per-gateway tool counts:
    # a tool may belong to more than one gateway (multi-gateway membership --
    # reads are listed in both their mixed manage_ gateway and a read_ gateway),
    # so sum(per_gateway.values()) over-counts and would drive `core` negative.
    proxied = len(proxied_names)
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


def check_tool_guide_pointers(src_override: str | None = None,
                              tg_override: str | None = None,
                              anchors_override: dict | None = None) -> list[dict]:
    """Verify every get_tool_guide(section='X') pointer in the .groovy schemas
    references a section key that actually exists in getToolGuideSections().

    src_override / tg_override let the self-test drive this function with
    synthetic corpora -- routes the must-catch / must-not-catch fixtures
    through the production dispatch + finding-dict construction so a missing
    key in any appended dict surfaces in the self-test rather than at first
    real-failure time.

    Failure modes this catches:

    1. **Broken pointer.** Schema description says "Call
       `get_tool_guide(section='X')` for the foo reference" but X is not a
       key in the getToolGuideSections() map. Flat-mode callers following
       the pointer get a 'section not found' response. Emitted as
       `tool-guide-broken-pointer`.

    2. **Drifted heading.** Every getToolGuideSections key should have a
       corresponding heading in TOOL_GUIDE.md (mapped through the
       `key_to_heading_hint` table below). Presence (not exact content
       match) so prose tweaks don't trip the lint; renames or deletes do.
       Emitted as `tool-guide-heading-missing`.

    3. **Unmapped new key.** A section added to the dispatcher without an
       entry in `key_to_heading_hint` fails loud rather than silently
       skipping the drift check for that key. Forces the contributor adding
       the section to also add the hint. Emitted as
       `tool-guide-no-heading-hint`.

    4. **Content-anchor drift.** Per-section anchor strings (declared in
       `key_to_content_anchors` below) must appear in BOTH the source
       doc-block AND TOOL_GUIDE.md. Catches in-body prose drift that the
       heading-presence check at step 2/3 cannot see. Emitted as one of
       `tool-guide-anchor-missing-{both,source,doc}`.
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    tool_guide = REPO_ROOT / "TOOL_GUIDE.md"
    if src_override is not None:
        src = src_override
    else:
        if not server.exists():
            return findings
        src = server.read_text(encoding="utf-8", errors="replace")
    if tg_override is not None:
        tg = tg_override
    else:
        if not tool_guide.exists():
            return findings
        tg = tool_guide.read_text(encoding="utf-8", errors="replace")

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
            "rule": "tool-guide-no-sections",
            "message": "Could not locate getToolGuideSections() return literal -- has the function shape changed?",
            "source": "",
        })
        return findings

    sections_block = sections_block_match.group(1)
    # Match exactly the 8-space top-level indentation inside `return [ ... ]` so a stray
    # `something: '''` inside one of the baked markdown bodies (deeper indentation, or
    # mid-paragraph) can't be mistaken for a real section key.
    section_keys = set(re.findall(r"^ {8}([a-z_][a-z0-9_]*):\s*'''", sections_block, re.MULTILINE))
    # Extract each section's full body too so the content-anchor check below can verify
    # specific anchor strings exist in BOTH the source doc-block AND TOOL_GUIDE.md. The
    # heading-presence check at L1252 only protects against renames/deletions; without a
    # content check, in-body prose can drift silently between the two files (live failure
    # mode: content-body drift -- heading-presence check passes but a specific entry is
    # absent from the source doc-block, so agents calling get_tool_guide see stale text).
    # Non-greedy match to next 8-space key, or end-of-block.
    section_bodies = {}
    body_re = re.compile(
        r"^ {8}([a-z_][a-z0-9_]*):\s*'''(.*?)'''(?=\s*(?:,|$|\n\s{0,8}[a-z_]+:))",
        re.MULTILINE | re.DOTALL,
    )
    for m in body_re.finditer(sections_block):
        section_bodies[m.group(1)] = m.group(2)

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
                    "rule": "tool-guide-broken-pointer",
                    "message": (
                        f"get_tool_guide(section='{ptr}') points at a section that is NOT a key "
                        f"in getToolGuideSections(). Either add the section to the dispatcher or "
                        f"fix the pointer. Known sections: {sorted(section_keys)}."
                    ),
                    "source": line.strip()[:200],
                })

    # 3. Drift check: every section key should have a matching heading anchor in TOOL_GUIDE.md.
    #    Translate snake_case key -> the heading text the engineer wrote it from.
    #    Use a substring check (presence in TOOL_GUIDE.md) rather than exact slugify — keeps
    #    the lint tolerant of prose edits while catching renames.
    key_to_heading_hint = {
        "device_authorization": "Device Authorization",
        "hub_admin_write": "Destructive Write",
        "virtual_devices": "Virtual Device",
        "update_device": "update_device",
        "rules": "Rule Structure Reference",
        "backup": "Backup System",
        "file_manager": "File Manager",
        "performance": "Performance Tips",
        "builtin_app_tools": "Installed-App & Native-Rule",
        "set_rule_reference": "`hub_set_rule` capability reference",
        "set_rule_create_reference": "`hub_set_rule` create reference",
    }
    for key in section_keys:
        hint = key_to_heading_hint.get(key)
        if hint is None:
            # New section added to the .groovy without a hint mapping above.
            # Fail loud rather than silently skip -- keeps this lint honest.
            findings.append({
                "file": str(server.relative_to(REPO_ROOT)),
                "line": 1,
                "severity": "error",
                "rule": "tool-guide-no-heading-hint",
                "message": (
                    f"getToolGuideSections key '{key}' has no entry in key_to_heading_hint "
                    f"(in tests/sandbox_lint.py). Add a mapping so the TOOL_GUIDE.md drift "
                    f"check can verify the heading still exists."
                ),
                "source": "",
            })
            continue
        if hint not in tg:
            findings.append({
                "file": str(tool_guide.relative_to(REPO_ROOT)),
                "line": 1,
                "severity": "error",
                "rule": "tool-guide-heading-missing",
                "message": (
                    f"getToolGuideSections key '{key}' baked into the .groovy, but the matching "
                    f"heading '{hint}' is not present in TOOL_GUIDE.md. Either restore the heading "
                    f"or update key_to_heading_hint in tests/sandbox_lint.py."
                ),
                "source": "",
            })

    # 4. Content anchors: per-section list of substrings that MUST appear in both the
    #    source doc-block and TOOL_GUIDE.md. Catches in-body prose drift that the
    #    heading-presence check at step 3 cannot see -- e.g. a new addAction capability
    #    family added to TOOL_GUIDE.md but never ported back into the source doc-block.
    #    Add one anchor per facts-section worth pinning; do NOT pin prose unless it
    #    represents a load-bearing API surface fact (capability name, error keyword,
    #    API endpoint slug, etc.).
    default_anchors = {
        "set_rule_reference": [
            # setVariable / Hub Variable addAction family
            "setVariable",
            # Mode action's modeName-resolution behavior
            "modeName",
            # Discrete-event sensor note for STPage capability list
            "discrete events",
            # Variable comparison capability for STPage
            "Variable comparison",
            # Lowercase parameter type validator only accepts these
            "lowercase",
            # Extended per-capability shapes heading (was a dangling cross-reference
            # before -- pin both surfaces now so future drift fires the anchor lint).
            "Extended per-capability spec shapes",
            # addTrigger.condition narrowness vs the wider expression conditions.
            # The selectTriggers narrowness phrasing was overclaimed before; pin both
            # surfaces so a future "all extended shapes apply here" regression fires.
            "selectTriggers",
            # Nested subExpression rejection on addAction (F7 scope-document).
            # The reject is in production today; both surfaces must keep advertising it.
            "nested subExpression",
            # Trailing-updateRule failure response slots (F2 addRequiredExpression
            # + B5 addTrigger parity). Pin one slot name per side.
            "expressionNotLive",
            "subscriptionsNotLive",
        ],
    }
    key_to_content_anchors = anchors_override if anchors_override is not None else default_anchors
    for key, anchors in key_to_content_anchors.items():
        if key not in section_bodies:
            # Either the body extractor regex shape changed, or the key is unmapped.
            # The unmapped-key path is already covered by step 3's tool-guide-no-heading-hint.
            continue
        body = section_bodies[key]
        for anchor in anchors:
            in_body = anchor in body
            in_tg = anchor in tg
            if not in_body and not in_tg:
                findings.append({
                    "file": "tests/sandbox_lint.py",
                    "line": 1,
                    "severity": "error",
                    "rule": "tool-guide-anchor-missing-both",
                    "message": (
                        f"Content anchor '{anchor}' (key='{key}') is missing from BOTH the source "
                        f"doc-block in hubitat-mcp-server.groovy AND TOOL_GUIDE.md. Either remove "
                        f"the anchor from key_to_content_anchors (no longer load-bearing) or "
                        f"restore the content in both files."
                    ),
                    "source": "",
                })
            elif not in_body:
                findings.append({
                    "file": str(server.relative_to(REPO_ROOT)),
                    "line": 1,
                    "severity": "error",
                    "rule": "tool-guide-anchor-missing-source",
                    "message": (
                        f"Content anchor '{anchor}' (key='{key}') is present in TOOL_GUIDE.md but "
                        f"NOT in the source doc-block in hubitat-mcp-server.groovy. Agents calling "
                        f"get_tool_guide(section='{key}') will not see this fact. Port the text "
                        f"into the doc-block or drop the anchor from key_to_content_anchors."
                    ),
                    "source": "",
                })
            elif not in_tg:
                findings.append({
                    "file": str(tool_guide.relative_to(REPO_ROOT)),
                    "line": 1,
                    "severity": "error",
                    "rule": "tool-guide-anchor-missing-doc",
                    "message": (
                        f"Content anchor '{anchor}' (key='{key}') is present in the source "
                        f"doc-block but NOT in TOOL_GUIDE.md. Add it to keep the human-readable "
                        f"reference in sync, or drop the anchor from key_to_content_anchors."
                    ),
                    "source": "",
                })

    return findings


def check_discrete_event_caps_doc_parity(
    src_override: str | None = None,
    doc_surfaces_override: dict | None = None,
) -> list[dict]:
    """Verify every doc surface that lists discrete-event sensor capabilities
    only names capabilities that are in production's DISCRETE_EVENT_CAPS map.

    Production code's authoritative class predicate lives in the
    DISCRETE_EVENT_CAPS map literal in hubitat-mcp-server.groovy. Doc surfaces
    that list capabilities as discrete-event (the inline addRE schema
    description, the inline get_tool_guide content block, TOOL_GUIDE.md, and
    docs/rm_action_subtype_schemas.md) MUST cite a subset of that production
    set -- otherwise agents copy a doc example that the live walker rejects.

    Scope of what this rule catches: capability-NAME presence parity ONLY.
    The lint extracts capability keys from the DISCRETE_EVENT_CAPS map and
    flags any cap name in a doc-surface positive-claim region that is NOT in
    that canonical set (e.g. the CO2-symmetric-to-CO pitfall). It catches:
    (a) adding a new doc cap that production does not accept,
    (b) the well-known pitfall caps drift (Carbon dioxide sensor).
    It does NOT catch: state-value drift (doc says `'tested'` but production
    only accepts `["detected", "clear"]`); removal drift (cap dropped from
    production but still in docs flags only as a no-finding because the doc
    cap is no longer in the canonical set and is silently treated as
    out-of-scope). State-value parity + removal parity are TODO -- track
    those separately rather than overclaiming this rule.

    Implementation note: the doc-surface scan uses a fixed 800-char window
    after a discrete-event phrase to bound the search; a markdown table
    spanning more than ~30 rows could trail past the window. The known doc
    surfaces today are all under that limit.

    src_override / doc_surfaces_override let the self-test drive this with
    synthetic corpora. doc_surfaces_override is a dict {label: text}.
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    if src_override is not None:
        src = src_override
    else:
        if not server.exists():
            return findings
        src = server.read_text(encoding="utf-8", errors="replace")

    # 1. Extract the canonical DISCRETE_EVENT_CAPS set from production.
    #    The map literal shape is:
    #        def DISCRETE_EVENT_CAPS = [
    #            "Water sensor":                ["wet", "dry"],
    #            ...
    #        ]
    map_match = re.search(
        r"def\s+DISCRETE_EVENT_CAPS\s*=\s*\[(.*?)\n\s*\]",
        src,
        re.DOTALL,
    )
    if not map_match:
        findings.append({
            "file": str(server.relative_to(REPO_ROOT)),
            "line": 1,
            "severity": "error",
            "rule": "discrete-event-caps-no-map",
            "message": (
                "Could not locate `def DISCRETE_EVENT_CAPS = [ ... ]` literal -- "
                "has the map shape changed? Update the check_discrete_event_caps_doc_parity "
                "extractor regex or remove the lint rule if intentionally restructured."
            ),
            "source": "",
        })
        return findings
    canonical_caps = set(re.findall(r'"([^"]+)"\s*:', map_match.group(1)))
    if not canonical_caps:
        findings.append({
            "file": str(server.relative_to(REPO_ROOT)),
            "line": 1,
            "severity": "error",
            "rule": "discrete-event-caps-empty",
            "message": "DISCRETE_EVENT_CAPS map literal parsed but yielded zero capabilities.",
            "source": "",
        })
        return findings

    # 2. Build the doc-surface map. Each surface is the full text of the source
    #    block to scan; the check is "for each capability mentioned in a
    #    discrete-event context, verify it's in canonical_caps".
    if doc_surfaces_override is not None:
        doc_surfaces = doc_surfaces_override
    else:
        tool_guide = REPO_ROOT / "TOOL_GUIDE.md"
        action_schemas = REPO_ROOT / "docs" / "rm_action_subtype_schemas.md"
        doc_surfaces = {}
        # Two inline surfaces in the server source: extract narrow scope so we
        # only scan the "discrete events" / "discrete-event" notes, not the
        # whole 800KB file (which mentions caps in many unrelated contexts).
        for m in re.finditer(
            r"(?:report discrete events|discrete-event capability|some sensor capabilities).{0,800}",
            src,
            re.DOTALL,
        ):
            label = f"hubitat-mcp-server.groovy:{src[:m.start()].count(chr(10)) + 1}"
            doc_surfaces[label] = m.group(0)
        if tool_guide.exists():
            tg = tool_guide.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(
                r"(?:report discrete events|discrete-event capability|some sensor capabilities).{0,800}",
                tg,
                re.DOTALL,
            ):
                label = f"TOOL_GUIDE.md:{tg[:m.start()].count(chr(10)) + 1}"
                doc_surfaces[label] = m.group(0)
        if action_schemas.exists():
            as_text = action_schemas.read_text(encoding="utf-8", errors="replace")
            # The discrete-event table in this file is the authoritative table.
            m = re.search(
                r"###\s*Sensor capabilities with discrete event states.*?(?=\n##|\Z)",
                as_text,
                re.DOTALL,
            )
            if m:
                label = f"docs/rm_action_subtype_schemas.md:{as_text[:m.start()].count(chr(10)) + 1}"
                doc_surfaces[label] = m.group(0)

    # 3. The set of all capability names this lint cares about: production's
    #    canonical set + the well-known pitfall caps that are NOT in production
    #    but might be accidentally added to a doc surface (Carbon dioxide sensor
    #    is the documented pitfall).
    pitfall_caps = {"Carbon dioxide sensor"}
    caps_to_check = canonical_caps | pitfall_caps

    # 4. For each doc surface, isolate the *positive claim* region -- the
    #    parenthesized list of capabilities adjacent to the "report discrete
    #    events" phrase, OR the markdown-table rows under a "discrete event"
    #    heading. Only claims inside that narrow region count as the doc
    #    asserting the capability is discrete-event. Mentions in surrounding
    #    explanatory / exclusion text (e.g. "Carbon dioxide sensor is
    #    intentionally EXCLUDED because ...") do NOT count.
    #
    #    Two positive-claim shapes we recognize:
    #    (a) "(Water sensor, Smoke detector, ...) report discrete events"
    #        -- the parenthetical immediately preceding the phrase.
    #    (b) "report discrete events" with no parenthetical immediately
    #        before -- treat the immediately-following inline list (up to
    #        the next sentence-end period) as the positive claim region.
    #    (c) Markdown-table rows of the form `| `<cap>` |` under a heading
    #        that contains "discrete event" -- the table rows are the
    #        positive claim region.
    paren_before_phrase_re = re.compile(
        r"\(([^()]*?)\)\s*report discrete events"
    )
    table_row_re = re.compile(r"\|\s*`([^`]+)`\s*\|")
    for label, surface_text in doc_surfaces.items():
        positive_claim_regions = []
        for m in paren_before_phrase_re.finditer(surface_text):
            positive_claim_regions.append(m.group(1))
        # Markdown-table form (only fires when surface starts with the table heading)
        if "discrete event" in surface_text.lower() and "|" in surface_text:
            for tr in table_row_re.finditer(surface_text):
                positive_claim_regions.append(tr.group(1))
        positive_claim_text = " ".join(positive_claim_regions)
        if not positive_claim_text:
            # No positive-claim region detected -- this doc surface mentions
            # discrete events but doesn't carry a parenthetical / table-row
            # cap list. Skip; the heading-presence checks elsewhere cover
            # structural drift.
            continue
        for cap in caps_to_check:
            if cap in positive_claim_text and cap not in canonical_caps:
                file_part, _, line_part = label.partition(":")
                findings.append({
                    "file": file_part,
                    "line": int(line_part) if line_part.isdigit() else 1,
                    "severity": "error",
                    "rule": "discrete-event-caps-doc-drift",
                    "message": (
                        f"Doc surface lists capability '{cap}' as a discrete-event "
                        f"capability (inside a positive-claim region), but '{cap}' "
                        f"is NOT in production's DISCRETE_EVENT_CAPS map (canonical "
                        f"set: {sorted(canonical_caps)}). Agents copying this doc "
                        f"would build a condition the live walker rejects. Either "
                        f"remove '{cap}' from the positive-claim list or add it to "
                        f"the production DISCRETE_EVENT_CAPS map."
                    ),
                    "source": positive_claim_text[:160].replace("\n", " "),
                })

    return findings


# Self-test fixtures for check_discrete_event_caps_doc_parity. Drives the real
# check function with synthetic corpora to cover must-catch + must-not-catch
# cases (PIPELINE.md Rule 13).
DISCRETE_EVENT_CAPS_SELF_TEST_CASES = [
    # (description, synthetic_src, doc_surfaces, expected_codes)
    (
        "doc surface positive-claim region lists only canonical caps -- no finding (must-not-catch)",
        'def DISCRETE_EVENT_CAPS = [\n    "Water sensor": ["wet", "dry"],\n    "Smoke detector": ["detected", "clear"]\n]',
        {"surface1": "some sensor capabilities (Water sensor, Smoke detector) report discrete events"},
        set(),
    ),
    (
        "positive-claim region lists pitfall cap NOT in canonical -- flags drift (must-catch)",
        'def DISCRETE_EVENT_CAPS = [\n    "Water sensor": ["wet", "dry"]\n]',
        {"surface_drift": "some sensor capabilities (Water sensor, Carbon dioxide sensor) report discrete events"},
        {"discrete-event-caps-doc-drift"},
    ),
    (
        "no DISCRETE_EVENT_CAPS map in synthetic source -- flags no-map (extractor regression guard)",
        "def someOtherMap = [:]",
        {"surface_noop": "some sensor capabilities report discrete events"},
        {"discrete-event-caps-no-map"},
    ),
    (
        "explanatory-exclusion text mentioning pitfall cap OUTSIDE positive-claim region -- no false positive",
        'def DISCRETE_EVENT_CAPS = [\n    "Water sensor": ["wet", "dry"]\n]',
        # The positive-claim region is just `(Water sensor)`; the explanatory text after
        # mentions Carbon dioxide sensor but to explain its EXCLUSION, not to claim it as
        # discrete-event. The classifier must scope to the parenthetical region only.
        {"surface_exclusion": "some sensor capabilities (Water sensor) report discrete events. Carbon dioxide sensor is intentionally EXCLUDED because CarbonDioxideMeasurement is numeric ppm."},
        set(),
    ),
    (
        "markdown-table positive-claim region with pitfall cap row -- flags drift (must-catch)",
        'def DISCRETE_EVENT_CAPS = [\n    "Water sensor": ["wet", "dry"]\n]',
        {"surface_table": "### Sensor capabilities with discrete event states\n\n| Capability | State values |\n|---|---|\n| `Water sensor` | wet, dry |\n| `Carbon dioxide sensor` | detected, clear |\n"},
        {"discrete-event-caps-doc-drift"},
    ),
]


def _run_discrete_event_caps_self_test() -> int:
    """Drive check_discrete_event_caps_doc_parity with synthetic corpora and verify:
    (a) the right rule codes fire (dispatch correctness)
    (b) every finding is format_finding-renderable (finding-dict shape correctness)
    """
    failures = 0
    for i, (desc, src, surfaces, expected_codes) in enumerate(
        DISCRETE_EVENT_CAPS_SELF_TEST_CASES, start=1
    ):
        findings = check_discrete_event_caps_doc_parity(
            src_override=src,
            doc_surfaces_override=surfaces,
        )
        # Shape check first (same pattern as _run_tool_guide_anchor_self_test).
        shape_ok = True
        for f in findings:
            try:
                _ = format_finding(f)
            except KeyError as ke:
                failures += 1
                shape_ok = False
                print(
                    f"DISCRETE-EVENT-CAPS-SELF-TEST FAIL [{i}] {desc}\n"
                    f"  finding dict missing required key for format_finding: {ke}\n"
                    f"  finding keys present: {sorted(f.keys())}\n"
                    f"  finding: {f!r}"
                )
        if not shape_ok:
            continue
        actual_codes = {f["rule"] for f in findings}
        if actual_codes != expected_codes:
            failures += 1
            print(
                f"DISCRETE-EVENT-CAPS-SELF-TEST FAIL [{i}] {desc}\n"
                f"  expected codes: {sorted(expected_codes)}\n"
                f"  actual codes:   {sorted(actual_codes)}\n"
                f"  all findings: {findings!r}"
            )
    return failures


def check_trailing_updaterule_envelope_parity(
    src_override: str | None = None,
) -> list[dict]:
    """Verify every `catch (Exception updateExc)` block in the RM dispatcher
    is followed by the full 5-slot trailing-updateRule envelope shape:
    `updateRuleFailed`, one of the `*NotLive` slots (subscriptionsNotLive /
    expressionNotLive / variableNotLive / patchesNotLive), `updateRuleError`,
    `repairHints`, and `partial`.

    Failure mode this catches: a future dispatcher that wires a trailing
    updateRule click + catch block but forgets to thread the dedicated slots
    into the return shape. The catch block silently sets a local boolean and
    the response never surfaces the regression, so callers cannot detect the
    not-live state without log-grep -- the exact bug class B3 fixed.

    Known scope caveat: this is a textual proximity check, not an AST walk.
    The check uses a fixed 4500-char vicinity window around each catch block
    to detect required slots. A dispatcher whose try / catch / return spans
    more than 4500 chars (roughly 120 lines of Groovy with comments) could
    silently lint-pass even if the return shape is missing slots. Today's
    dispatchers all fit. If the codebase outgrows the window, replace the
    fixed-char scan with brace-balanced block detection rather than just
    enlarging the window.
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    if src_override is not None:
        src = src_override
    else:
        if not server.exists():
            return findings
        src = server.read_text(encoding="utf-8", errors="replace")

    # Each match marks the start of a trailing-updateRule catch block. Scope
    # to the literal handler shape so we don't false-positive on broader
    # catch-Exception patterns elsewhere in the file.
    catch_pattern = re.compile(r"catch\s*\(\s*Exception\s+updateExc\s*\)")
    # Required envelope slots. At least one of NOTLIVE_SLOT_VARIANTS must
    # appear in the vicinity (different dispatchers use different slot names).
    REQUIRED_SLOTS = ["updateRuleFailed", "updateRuleError", "repairHints", "partial"]
    NOTLIVE_SLOT_VARIANTS = [
        "subscriptionsNotLive",
        "expressionNotLive",
        "variableNotLive",
        "patchesNotLive",
    ]
    # Vicinity window: large enough to span declaration -> catch -> return,
    # small enough to avoid bleeding into the next dispatcher's return shape.
    LOOKAHEAD_CHARS = 4500

    for m in catch_pattern.finditer(src):
        # Also look BEHIND a bit because some dispatchers declare the booleans
        # before the try (so updateRuleFailed/etc. live in the def block above
        # the catch). Use a small backward window plus the forward window.
        scope_start = max(0, m.start() - 1500)
        scope_end = min(len(src), m.end() + LOOKAHEAD_CHARS)
        vicinity = src[scope_start:scope_end]

        missing = [slot for slot in REQUIRED_SLOTS if slot not in vicinity]
        if not any(slot in vicinity for slot in NOTLIVE_SLOT_VARIANTS):
            missing.append(f"one of {NOTLIVE_SLOT_VARIANTS}")

        if missing:
            line_no = src[:m.start()].count("\n") + 1
            findings.append({
                "file": str(server.relative_to(REPO_ROOT)),
                "line": line_no,
                "severity": "error",
                "rule": "trailing-updaterule-envelope-incomplete",
                "message": (
                    f"`catch (Exception updateExc)` at L{line_no} is missing one or more "
                    f"trailing-updateRule envelope slots in its return shape: {missing}. "
                    f"Callers cannot detect the not-live state without log-grep. Pattern "
                    f"reference: addRequiredExpression / addTrigger / bulk addTriggers "
                    f"dispatchers all set the 5-slot envelope on the catch path."
                ),
                "source": src[m.start():m.start() + 120].replace("\n", " "),
            })

    return findings


# Self-test fixtures for check_trailing_updaterule_envelope_parity. Drives
# the real check function with synthetic corpora to cover must-catch +
# must-not-catch cases (PIPELINE.md Rule 13).
ENVELOPE_PARITY_SELF_TEST_CASES = [
    # (description, synthetic_src, expected_codes)
    (
        "complete envelope -- no finding (must-not-catch)",
        """
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
            updateRuleError = updateExc.message
        }
        def repairHints = []
        return [
            success: false,
            partial: true,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints
        ]
        """,
        set(),
    ),
    (
        "missing repairHints in envelope -- flags incomplete (must-catch)",
        """
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
        }
        return [
            success: false,
            partial: true,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: null
        ]
        """,
        {"trailing-updaterule-envelope-incomplete"},
    ),
    (
        "missing ANY NotLive slot -- flags incomplete (must-catch)",
        """
        def updateRuleFailed = false
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
        }
        return [
            success: false,
            partial: true,
            updateRuleFailed: updateRuleFailed,
            updateRuleError: null,
            repairHints: []
        ]
        """,
        {"trailing-updaterule-envelope-incomplete"},
    ),
    (
        "no `catch (Exception updateExc)` block at all -- no finding (rule never fires)",
        "def foo = 1\ntry { stuff() } catch (Exception e) { log.error e.message }",
        set(),
    ),
    (
        "missing `partial` slot in envelope -- flags incomplete (must-catch)",
        """
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
            updateRuleError = updateExc.message
        }
        def repairHints = []
        return [
            success: false,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints
        ]
        """,
        {"trailing-updaterule-envelope-incomplete"},
    ),
]


def _run_envelope_parity_self_test() -> int:
    """Drive check_trailing_updaterule_envelope_parity with synthetic corpora
    and verify dispatch correctness + finding-dict shape correctness.
    """
    failures = 0
    for i, (desc, src, expected_codes) in enumerate(
        ENVELOPE_PARITY_SELF_TEST_CASES, start=1
    ):
        findings = check_trailing_updaterule_envelope_parity(src_override=src)
        shape_ok = True
        for f in findings:
            try:
                _ = format_finding(f)
            except KeyError as ke:
                failures += 1
                shape_ok = False
                print(
                    f"ENVELOPE-PARITY-SELF-TEST FAIL [{i}] {desc}\n"
                    f"  finding dict missing required key for format_finding: {ke}\n"
                    f"  finding keys present: {sorted(f.keys())}"
                )
        if not shape_ok:
            continue
        actual_codes = {f["rule"] for f in findings}
        if actual_codes != expected_codes:
            failures += 1
            print(
                f"ENVELOPE-PARITY-SELF-TEST FAIL [{i}] {desc}\n"
                f"  expected codes: {sorted(expected_codes)}\n"
                f"  actual codes:   {sorted(actual_codes)}\n"
                f"  all findings: {findings!r}"
            )
    return failures


def check_read_write_split(src_override: str | None = None) -> list[dict]:
    """Enforce BOTH directions of the gateway read/write-split invariant
    (AGENTS.md "Gateway read/write split" -- a hard CI failure):

      (A) No stranded read. Every tool in getReadOnlyToolNames() MUST be
          reachable from a hub_read_* gateway OR be a flat top-level tool. It may
          NEVER be reachable ONLY through a hub_manage_* gateway.
          -> rule "read-write-split-stranded-read".
      (B) No write in a read gateway. Every tool inside a hub_read_* gateway MUST
          be in getReadOnlyToolNames() (read-only). A single write flips the
          gateway's rolled-up readOnlyHint to write+destructive
          (annotationsForGateway), mislabeling the whole read surface.
          -> rule "read-write-split-write-in-read-gateway".

    Rationale: clients route read-only browsing through the hub_read_* gateways
    and surface those tools under readOnlyHint=true. A read that lives ONLY inside
    a hub_manage_* gateway is invisible on the read-browse surface AND inherits
    the write annotation -- a read mislabeled as a write and hidden from the read
    path. Multi-gateway membership is fine: a read MAY co-live in a hub_manage_*
    gateway as long as it is ALSO in a hub_read_* gateway (or is flat).

    Both directions derive the read gateways by the `hub_read_` name prefix (NOT a
    hard-coded list), so an Nth read gateway is covered automatically -- unlike
    McpToolAnnotationsSpec's gateway-rollup assertion, which hard-codes its read
    gateway names. Ships with must-catch + must-not-catch self-test fixtures
    (READ_WRITE_SPLIT_SELF_TEST_CASES) so the guard provably fires and can never
    silently no-op.

    src_override lets the self-test drive this with synthetic corpora.
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    if src_override is not None:
        src = src_override
    else:
        if not server.exists():
            return findings
        src = server.read_text(encoding="utf-8", errors="replace")
        # Tool defs now live partly in #include'd library modules (issue #209); the app includes
        # every libraries/*.groovy, so append them so moved def chunks (e.g.
        # _getAllToolDefinitions_partRooms) are seen by the gateway-vs-defs reachability checks
        # below. The src_override path stays main-only for the synthetic self-test corpora.
        _lib_dir = REPO_ROOT / "libraries"
        if _lib_dir.is_dir():
            for _lib in sorted(_lib_dir.glob("*.groovy")):
                src += "\n" + _lib.read_text(encoding="utf-8", errors="replace")

    rel = str(server.relative_to(REPO_ROOT))

    def _fail(rule: str, message: str) -> list[dict]:
        findings.append({
            "file": rel, "line": 1, "severity": "error",
            "rule": rule, "message": message, "source": "",
        })
        return findings

    # 1. getGatewayConfig() -> {gateway_name: [tool, ...]}. Same two-stage anchor
    #    _extract_canonical_counts() uses, but we keep the full tool LISTS (not
    #    just counts) and the gateway names (to split hub_read_ vs hub_manage_).
    gw_match = re.search(r"^def getGatewayConfig\(\) \{(.*?)^}", src, re.DOTALL | re.MULTILINE)
    if not gw_match:
        return _fail(
            "read-write-split-no-gateway-config",
            "Could not locate getGatewayConfig() return literal -- has the function shape "
            "changed? The read/write-split guard cannot run until the parser is updated.",
        )
    gateway_tools: dict[str, list[str]] = {}
    gateway_pos: dict[str, int] = {}  # absolute src offset of each gateway, for line numbers
    for m in re.finditer(
        r"^\s+([a-z_]+):\s*\[\s*description:\s*\".*?\".*?\btools:\s*\[([^\]]+)\]",
        gw_match.group(1), re.MULTILINE | re.DOTALL,
    ):
        no_comments = re.sub(r"//[^\n]*", "", m.group(2))
        gateway_tools[m.group(1)] = [t.strip().strip("\"'") for t in no_comments.split(",") if t.strip()]
        gateway_pos[m.group(1)] = gw_match.start(1) + m.start()
    if not gateway_tools:
        return _fail(
            "read-write-split-no-gateway-config",
            "getGatewayConfig() parsed but yielded zero gateways -- parser/source shape mismatch.",
        )

    # 2. getReadOnlyToolNames() -> the read-only tool set (the source of truth
    #    that feeds the readOnlyHint annotations).
    ro_match = re.search(r"^def getReadOnlyToolNames\(\) \{(.*?)^}", src, re.DOTALL | re.MULTILINE)
    if not ro_match:
        return _fail(
            "read-write-split-no-readonly-list",
            "Could not locate getReadOnlyToolNames() return literal -- has the function shape changed?",
        )
    read_only = set(re.findall(r'"([a-z_]+)"', re.sub(r"//[^\n]*", "", ro_match.group(1))))
    if not read_only:
        return _fail(
            "read-write-split-no-readonly-list",
            "getReadOnlyToolNames() parsed but yielded zero tool names -- parser/source shape mismatch.",
        )

    # 3. Flat (top-level) tools = every getAllToolDefinitions() tool that no
    #    gateway proxies. A read-only tool that is flat satisfies the invariant.
    #    PR1C split the defs across _getAllToolDefinitions_part1..N() chunk
    #    methods (64KB-method-bytecode cap); gather names from all of them.
    all_bodies = re.findall(
        r"^def (?:getAllToolDefinitions|_getAllToolDefinitions_part\w+)\(\) \{(.*?)^}",
        src, re.DOTALL | re.MULTILINE,
    )
    if not all_bodies:
        return _fail(
            "read-write-split-no-tool-definitions",
            "Could not locate getAllToolDefinitions() return literal -- has the function shape changed?",
        )
    all_tool_names = set(re.findall(r"^\s*name:\s*['\"]([a-z_]+)['\"]", "\n".join(all_bodies), re.MULTILINE))
    proxied: set[str] = set()
    for tools in gateway_tools.values():
        proxied.update(tools)
    flat_tools = all_tool_names - proxied

    # 4. Read-gateway reach: every tool surfaced by a gateway whose name carries
    #    the hub_read_ prefix (derived, not hard-coded).
    read_gateway_tools: set[str] = set()
    for name, tools in gateway_tools.items():
        if name.startswith("hub_read_"):
            read_gateway_tools.update(tools)

    # 5. The invariant. A read-only tool is stranded iff it is neither flat nor in
    #    any hub_read_* gateway -- i.e. reachable ONLY through hub_manage_*.
    ro_body_start = ro_match.start(1)
    for tool in sorted(read_only):
        if tool in read_gateway_tools or tool in flat_tools:
            continue
        holders = sorted(n for n, ts in gateway_tools.items() if tool in ts)
        where = f"only via hub_manage_* gateway(s) {holders}" if holders else "by no gateway at all (and it is not flat)"
        idx = src.find(f'"{tool}"', ro_body_start)
        line_no = (src[:idx].count("\n") + 1) if idx != -1 else (src[:ro_match.start()].count("\n") + 1)
        findings.append({
            "file": rel,
            "line": line_no,
            "severity": "error",
            "rule": "read-write-split-stranded-read",
            "message": (
                f"Read-only tool '{tool}' is reachable {where}; it is in NO hub_read_* gateway and is "
                f"not a flat top-level tool. AGENTS.md 'Gateway read/write split' makes this a hard "
                f"failure: a read-only tool MUST be in a hub_read_* gateway (multi-gateway membership "
                f"alongside a hub_manage_* gateway is fine) or be flat. Fix: add '{tool}' to the "
                f"matching hub_read_* gateway's tools[] list, or -- if it is actually a write -- remove "
                f"it from getReadOnlyToolNames()."
            ),
            "source": "",
        })

    # 6. Mirror invariant (B): a hub_read_* gateway must contain ONLY read-only
    #    tools. Derived by prefix so an Nth read gateway is covered too (the Spock
    #    rollup hard-codes its read-gateway names; this does not).
    for name in sorted(gateway_tools):
        if not name.startswith("hub_read_"):
            continue
        for tool in gateway_tools[name]:
            if tool in read_only:
                continue
            g_abs = gateway_pos.get(name, ro_match.start())
            idx = src.find(f'"{tool}"', g_abs)
            line_no = (src[:idx].count("\n") + 1) if idx != -1 else (src[:g_abs].count("\n") + 1)
            findings.append({
                "file": rel,
                "line": line_no,
                "severity": "error",
                "rule": "read-write-split-write-in-read-gateway",
                "message": (
                    f"Tool '{tool}' is in read gateway '{name}' but is NOT in getReadOnlyToolNames() "
                    f"(i.e. it is treated as a write). A hub_read_* gateway must contain only "
                    f"read-only tools -- a single write flips the gateway's rolled-up readOnlyHint to "
                    f"write+destructive (annotationsForGateway), mislabeling the entire read surface. "
                    f"Fix: move '{tool}' to the appropriate hub_manage_* gateway, or -- if it is "
                    f"genuinely read-only -- add it to getReadOnlyToolNames()."
                ),
                "source": "",
            })
    return findings


def _build_read_write_split_corpus(gateways: dict, read_only: list, all_tools: list) -> str:
    """Construct a minimal Groovy corpus satisfying check_read_write_split's three
    extractors (getGatewayConfig / getReadOnlyToolNames / getAllToolDefinitions)
    so self-test fixtures can drive the REAL check. `gateways` maps gateway-name
    -> list of proxied tool names."""
    lines = ["def getGatewayConfig() {", "    return ["]
    for name, tools in gateways.items():
        csv = ", ".join(f'"{t}"' for t in tools)
        lines += [
            f"        {name}: [",
            f'            description: "{name} facade",',
            f"            tools: [{csv}]",
            "        ],",
        ]
    lines += ["    ]", "}", "", "def getReadOnlyToolNames() {", "    return ["]
    lines.append("        " + ", ".join(f'"{t}"' for t in read_only))
    lines += ["    ] as Set", "}", "", "def getAllToolDefinitions() {", "    return ["]
    for t in all_tools:
        lines += ["        [", f'            name: "{t}"', "        ],"]
    lines += ["    ]", "}"]
    return "\n".join(lines) + "\n"


# Self-test fixtures for check_read_write_split. Drive the real check with
# synthetic Groovy corpora to cover must-catch + must-not-catch cases
# (PIPELINE.md Rule 13) so the guard provably fires and never silently no-ops.
READ_WRITE_SPLIT_SELF_TEST_CASES = [
    # (description, groovy source, expected_rule_codes)
    (
        "read surfaced by a hub_read_* gateway -- no finding (must-not-catch)",
        _build_read_write_split_corpus(
            {"hub_read_devices": ["hub_get_device"],
             "hub_manage_devices": ["hub_get_device", "hub_update_device"]},
            ["hub_get_device"],
            ["hub_get_device", "hub_update_device"],
        ),
        set(),
    ),
    (
        "read is flat (proxied by no gateway) -- no finding (must-not-catch)",
        _build_read_write_split_corpus(
            {"hub_manage_devices": ["hub_update_device"]},
            ["hub_get_info"],
            ["hub_get_info", "hub_update_device"],
        ),
        set(),
    ),
    (
        "read in BOTH a read and a manage gateway -- multi-membership OK (must-not-catch)",
        _build_read_write_split_corpus(
            {"hub_read_rules": ["hub_get_custom_rule"],
             "hub_manage_custom_rules": ["hub_get_custom_rule", "hub_delete_custom_rule"]},
            ["hub_get_custom_rule"],
            ["hub_get_custom_rule", "hub_delete_custom_rule"],
        ),
        set(),
    ),
    (
        "read reachable ONLY through a hub_manage_* gateway -- flags stranded (must-catch)",
        _build_read_write_split_corpus(
            {"hub_manage_logs": ["hub_get_logs", "hub_delete_debug_logs"]},
            ["hub_get_logs"],
            ["hub_get_logs", "hub_delete_debug_logs"],
        ),
        {"read-write-split-stranded-read"},
    ),
    (
        "read in no gateway at all and not flat -- flags stranded, empty holders (must-catch)",
        _build_read_write_split_corpus(
            {"hub_manage_logs": ["hub_delete_debug_logs"]},
            ["hub_ghost_read"],
            ["hub_delete_debug_logs"],
        ),
        {"read-write-split-stranded-read"},
    ),
    (
        "no getGatewayConfig() in source -- flags extractor guard (must-catch)",
        'def getReadOnlyToolNames() {\n    return [ "hub_get_device" ] as Set\n}\n',
        {"read-write-split-no-gateway-config"},
    ),
    # --- Mirror invariant (B): no write tool inside a hub_read_* gateway ---
    (
        "write tool inside a hub_read_* gateway -- flags write-in-read (must-catch)",
        _build_read_write_split_corpus(
            {"hub_read_devices": ["hub_get_device", "hub_update_device"]},
            ["hub_get_device"],
            ["hub_get_device", "hub_update_device"],
        ),
        {"read-write-split-write-in-read-gateway"},
    ),
    (
        "only read-only tools inside a hub_read_* gateway -- no finding (must-not-catch)",
        _build_read_write_split_corpus(
            {"hub_read_files": ["hub_list_files", "hub_read_file"]},
            ["hub_list_files", "hub_read_file"],
            ["hub_list_files", "hub_read_file"],
        ),
        set(),
    ),
]


def _run_read_write_split_self_test() -> int:
    """Drive check_read_write_split with synthetic corpora and verify dispatch
    correctness + finding-dict shape correctness."""
    failures = 0
    for i, (desc, src, expected_codes) in enumerate(READ_WRITE_SPLIT_SELF_TEST_CASES, start=1):
        findings = check_read_write_split(src_override=src)
        shape_ok = True
        for f in findings:
            try:
                _ = format_finding(f)
            except KeyError as ke:
                failures += 1
                shape_ok = False
                print(
                    f"READ-WRITE-SPLIT-SELF-TEST FAIL [{i}] {desc}\n"
                    f"  finding dict missing required key for format_finding: {ke}\n"
                    f"  finding keys present: {sorted(f.keys())}"
                )
        if not shape_ok:
            continue
        actual_codes = {f["rule"] for f in findings}
        if actual_codes != expected_codes:
            failures += 1
            print(
                f"READ-WRITE-SPLIT-SELF-TEST FAIL [{i}] {desc}\n"
                f"  expected codes: {sorted(expected_codes)}\n"
                f"  actual codes:   {sorted(actual_codes)}\n"
                f"  all findings: {findings!r}"
            )
    return failures


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
    (
        "instanceof InputStream (the ClassExpression the hub rejected) is flagged",
        "else if (d instanceof InputStream) zipBytes = d.bytes",
        [("SANDBOX-015", True)],
    ),
    (
        "a (BufferedReader) cast is flagged",
        "def r = (BufferedReader) resp.data",
        [("SANDBOX-015", True)],
    ),
    (
        "a typed FileOutputStream declaration is flagged",
        "FileOutputStream out = openIt()",
        [("SANDBOX-015", True)],
    ),
    (
        "duck-typed .bytes read (the safe replacement) is NOT flagged",
        "zipBytes = d.bytes",
        [("SANDBOX-015", False)],
    ),
    (
        "instanceof CharSequence (sandbox-allowed) is NOT flagged",
        "if (d instanceof CharSequence) unexpectedBodyDesc = 'text'",
        [("SANDBOX-015", False)],
    ),
    (
        "InputStream mentioned only in a comment is NOT flagged",
        "return d.text  // Reader/InputStream -- may throw mid-stream",
        [("SANDBOX-015", False)],
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
        # Override the other counts the sentence mentions to their literal in-text
        # values so ONLY `proxied` drifts (80 vs 79); otherwise the live core /
        # gateways / total counts -- which changed after PR1B's gateway reshape --
        # also fire and the fixture stops isolating the proxied pattern.
        {"proxied": 79, "core": 23, "gateways": 13, "total": 103}, ["proxied"],
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


# (anchor, body_text, tg_text, expected_rule_codes) -- must-catch / must-not-catch
# fixtures for the content-anchor drift check inside check_tool_guide_pointers (step 4).
# Each fixture exercises one of the four outcome paths:
#   - missing in BOTH -> tool-guide-anchor-missing-both
#   - missing in source only -> tool-guide-anchor-missing-source
#   - missing in doc only -> tool-guide-anchor-missing-doc
#   - present in both -> no finding (must-not-catch case)
#
# Fixtures route through the REAL check_tool_guide_pointers via synthetic corpus
# overrides (src_override / tg_override / anchors_override). This catches not just
# the anchor-dispatch correctness but also the finding-dict shape (rule/source keys
# must be present so format_finding does not KeyError downstream).
TOOL_GUIDE_ANCHOR_SELF_TEST_CASES = [
    (
        "anchor present in both source body and TOOL_GUIDE.md -- no finding",
        "modeName",
        "Mode action takes modeName for resolution",
        "Mode capability uses modeName lookup",
        set(),
    ),
    (
        "anchor missing from BOTH source and doc -- flags missing-both",
        "ghostAnchor",
        "Body has no reference to it",
        "Doc has no reference to it",
        {"tool-guide-anchor-missing-both"},
    ),
    (
        "anchor present in doc but missing from source body -- flags missing-source",
        "setVariable",
        "Mode and modeName references",
        "Hub Variable (capability='setVariable') is documented",
        {"tool-guide-anchor-missing-source"},
    ),
    (
        "anchor present in source body but missing from doc -- flags missing-doc",
        "discrete events",
        "Sensors report discrete events here",
        "STPage list contains no such note",
        {"tool-guide-anchor-missing-doc"},
    ),
]


def _build_synthetic_groovy_corpus(section_key: str, body: str) -> str:
    """Construct a minimal Groovy corpus that satisfies check_tool_guide_pointers'
    parser: a getToolGuideSections() return literal with exactly ONE section whose
    key + body match the test fixture. The body is wrapped in a triple-single-quoted
    Groovy heredoc with the same 8-space indentation the production regex matches.
    """
    # Indent the body so any embedded ''' won't terminate the heredoc accidentally
    # (we keep the fixture bodies simple -- plain text without ''' or backticks).
    return (
        "def getToolGuideSections() {\n"
        "    return [\n"
        f"        {section_key}: '''{body}''',\n"
        "    ]\n"
        "}\n"
    )


def _run_tool_guide_anchor_self_test() -> int:
    """Drive check_tool_guide_pointers with synthetic corpora and verify both:
    (a) the right rule codes fire (dispatch correctness), AND
    (b) every finding can be rendered by format_finding without KeyError
        (finding-dict shape correctness -- catches missing 'rule'/'source' keys).
    """
    failures = 0
    # Use a synthetic section key + heading hint pair so the real heading-presence
    # check at step 3 does not fire for these fixtures. The hint must appear in the
    # synthetic TG corpus to pass step 3; we prepend it to every fixture's tg_text.
    synthetic_key = "selftest_anchor_section"
    synthetic_hint = "selftest_anchor_section"  # any unique substring works
    # Step 3 (heading-hint mapping) checks via the hardcoded `key_to_heading_hint`
    # in production code. A synthetic key not in that map would fire
    # tool-guide-no-heading-hint; we filter that code out of the actual-set so the
    # self-test only asserts on step 4's anchor codes. (Alternatives: make
    # key_to_heading_hint injectable too. Filter is simpler and the noise is local.)
    irrelevant_rules = {"tool-guide-no-heading-hint"}
    for i, (desc, anchor, body, tg, expected_codes) in enumerate(
        TOOL_GUIDE_ANCHOR_SELF_TEST_CASES, start=1
    ):
        synthetic_src = _build_synthetic_groovy_corpus(synthetic_key, body)
        synthetic_tg = f"{synthetic_hint}\n{tg}\n"
        synthetic_anchors = {synthetic_key: [anchor]}
        findings = check_tool_guide_pointers(
            src_override=synthetic_src,
            tg_override=synthetic_tg,
            anchors_override=synthetic_anchors,
        )
        # Shape check FIRST: every finding the production path appended must be
        # format_finding-renderable. Catches the missing-'rule'/'source' bug class
        # that crashes the CLI output path. Done before the rule-code comparison
        # so a shape failure prints a clean structured message instead of letting
        # the rule-code accessor below crash with a bare KeyError.
        shape_ok = True
        for f in findings:
            try:
                _ = format_finding(f)
            except KeyError as ke:
                failures += 1
                shape_ok = False
                print(
                    f"TOOL-GUIDE-ANCHOR-SELF-TEST FAIL [{i}] {desc}\n"
                    f"  finding dict missing required key for format_finding: {ke}\n"
                    f"  finding keys present: {sorted(f.keys())}\n"
                    f"  finding: {f!r}"
                )
        if not shape_ok:
            # Shape failures already reported; skip the dispatch-correctness check
            # for this fixture to avoid noisy compound failures from key accesses.
            continue
        actual_codes = {f["rule"] for f in findings if f["rule"] not in irrelevant_rules}
        if actual_codes != expected_codes:
            failures += 1
            print(
                f"TOOL-GUIDE-ANCHOR-SELF-TEST FAIL [{i}] {desc}\n"
                f"  expected codes: {sorted(expected_codes)}\n"
                f"  actual codes:   {sorted(actual_codes)}\n"
                f"  all-finding-rules: {sorted({f['rule'] for f in findings})}"
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
        # Self-consistency of the count breakdown. NOTE: sum(per_gateway.values())
        # is NOT used as `core + sum == total` anymore -- since PR1B introduced
        # multi-gateway membership (a read listed in both its hub_read_* and a
        # hub_manage_* gateway), the per-gateway sum DOUBLE-COUNTS those reads and
        # exceeds the DISTINCT proxied count. The canonical relationship is on the
        # distinct count: total == core + distinct_proxied (and core is derived as
        # total - distinct_proxied, so this also guards a future core-formula change).
        sum_with_dups = sum(canonical["per_gateway"].values())
        if canonical["core"] < 0:
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-count extractor]\n"
                f"  core {canonical['core']} is negative — distinct proxied "
                f"({canonical['proxied']}) exceeds total ({canonical['total']}); "
                "_extract_canonical_counts() is inconsistent."
            )
        if canonical["core"] + canonical["proxied"] != canonical["total"]:
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-count extractor]\n"
                f"  core ({canonical['core']}) + distinct proxied "
                f"({canonical['proxied']}) != total ({canonical['total']}) — "
                "internal inconsistency in _extract_canonical_counts()."
            )
        # Multi-membership means the per-gateway sum must be >= the distinct
        # proxied count; sum < distinct would mean a gateway tool went uncounted.
        if sum_with_dups < canonical["proxied"]:
            failures += 1
            print(
                f"SELF-TEST FAIL [tool-count extractor]\n"
                f"  per-gateway sum ({sum_with_dups}) < distinct proxied "
                f"({canonical['proxied']}) — a gateway tool is uncounted; "
                "_extract_canonical_counts() is inconsistent."
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

    # Tool-guide-anchor must-catch / must-not-catch fixtures (PIPELINE.md Rule 13:
    # the anchor-drift check inside check_tool_guide_pointers is a class-wide
    # mechanism; it ships with positive + negative fixtures so a future regression
    # in the dispatch logic surfaces here rather than silently weakening the lint).
    anchor_failures = _run_tool_guide_anchor_self_test()
    failures += anchor_failures

    # Discrete-event-caps must-catch / must-not-catch fixtures (PIPELINE.md Rule 13).
    discrete_event_failures = _run_discrete_event_caps_self_test()
    failures += discrete_event_failures

    # Trailing-updateRule envelope parity must-catch / must-not-catch fixtures
    # (PIPELINE.md Rule 13).
    envelope_parity_failures = _run_envelope_parity_self_test()
    failures += envelope_parity_failures

    # Read/write-split must-catch / must-not-catch fixtures (PIPELINE.md Rule 13):
    # the guard that a read-only tool is never stranded behind only a hub_manage_*
    # gateway ships with positive + negative fixtures so a regression in the
    # dispatch logic surfaces here rather than silently weakening the lint.
    read_write_split_failures = _run_read_write_split_self_test()
    failures += read_write_split_failures

    if failures:
        print(f"--- {failures} self-test failure(s) ---")
        return 1
    total_cases = (
        len(SELF_TEST_CASES)
        + len(COUNT_SELF_TEST_CASES)
        + len(TOOL_GUIDE_ANCHOR_SELF_TEST_CASES)
        + len(DISCRETE_EVENT_CAPS_SELF_TEST_CASES)
        + len(ENVELOPE_PARITY_SELF_TEST_CASES)
        + len(READ_WRITE_SPLIT_SELF_TEST_CASES)
    )
    print(
        f"Self-test: {total_cases} case(s) passed "
        f"({len(SELF_TEST_CASES)} sandbox, {len(COUNT_SELF_TEST_CASES)} count, "
        f"{len(TOOL_GUIDE_ANCHOR_SELF_TEST_CASES)} tool-guide-anchor, "
        f"{len(DISCRETE_EVENT_CAPS_SELF_TEST_CASES)} discrete-event-caps, "
        f"{len(ENVELOPE_PARITY_SELF_TEST_CASES)} envelope-parity, "
        f"{len(READ_WRITE_SPLIT_SELF_TEST_CASES)} read-write-split)."
    )
    return 0


def check_include_library_lockstep() -> list[dict]:
    """Every `#include mcp.X` in the app must stay in lockstep with its delivery (issue #209):
    (1) a libraries/*.groovy whose library() declares (namespace=X.ns, name=X.name),
    (2) a tools/build-bundle.py LIBS entry (else the HPM bundle won't deliver it), and
    (3) a getPackageLibraryRegistry() entry (else hub_update_package aborts before deploy).

    A gap means the library can't load on a user's hub, so the app's #include fails to compile.
    Catches it cheaply here (no hub) instead of at install/recompile time.

    Direction: this is #include -> delivery (every include must resolve). It does NOT flag an
    orphan library/LIBS/registry entry with no #include (a stale-but-harmless entry). It DOES flag
    two library files declaring the same (namespace, name), since a duplicate makes the hub's
    #include bind ambiguously (only one of the two copies wins).
    """
    findings: list[dict] = []
    server = REPO_ROOT / "hubitat-mcp-server.groovy"
    if not server.exists():
        return findings
    src = server.read_text(encoding="utf-8", errors="replace")
    rel = "hubitat-mcp-server.groovy"

    includes = re.findall(
        r"(?m)^[ \t]*#include[ \t]+([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)[ \t]*$", src
    )
    if not includes:
        return findings

    # (1) libraries declared by (namespace, name) from each libraries/*.groovy library() call.
    declared: dict[tuple[str, str], str] = {}
    lib_dir = REPO_ROOT / "libraries"
    if lib_dir.is_dir():
        for lib in sorted(lib_dir.glob("*.groovy")):
            text = lib.read_text(encoding="utf-8", errors="replace")
            m = re.search(r"(?m)^library\s*\((.*)\)\s*$", text)
            if not m:
                continue
            decl = m.group(1)
            nm = re.search(r"name:\s*['\"]([^'\"]+)['\"]", decl)
            ns = re.search(r"namespace:\s*['\"]([^'\"]+)['\"]", decl)
            if nm and ns:
                key = (ns.group(1), nm.group(1))
                if key in declared:
                    findings.append({
                        "file": f"libraries/{lib.name}", "line": 1, "severity": "error",
                        "rule": "INCLUDE_LOCKSTEP", "source": "",
                        "message": (
                            f"Duplicate library declaration namespace='{key[0]}', name='{key[1]}' in "
                            f"both {declared[key]} and {lib.name} -- a duplicate (namespace, name) makes "
                            f"the hub's #include bind ambiguously (only one copy wins). Rename or remove one."
                        ),
                    })
                else:
                    declared[key] = lib.name

    # (2) build-bundle.py LIBS dest names ({NAMESPACE}.<Name>.groovy).
    bb = REPO_ROOT / "tools" / "build-bundle.py"
    bundled_names: set[str] = set()
    if bb.exists():
        bundled_names = set(
            re.findall(r"\{NAMESPACE\}\.(\w+)\.groovy", bb.read_text(encoding="utf-8", errors="replace"))
        )

    # (3) getPackageLibraryRegistry() keys ("ns.Name").
    reg_match = re.search(
        r"def getPackageLibraryRegistry\(\) \{(.*?)^}", src, re.DOTALL | re.MULTILINE
    )
    registry_keys: set[str] = set()
    if reg_match:
        registry_keys = set(re.findall(r'"([A-Za-z0-9_]+\.[A-Za-z0-9_]+)"\s*:', reg_match.group(1)))

    include_line: dict[str, int] = {}
    for i, line in enumerate(src.splitlines(), 1):
        m = re.match(r"^[ \t]*#include[ \t]+([A-Za-z0-9_]+\.[A-Za-z0-9_]+)", line)
        if m:
            include_line.setdefault(m.group(1), i)

    for ns, name in includes:
        token = f"{ns}.{name}"
        ln = include_line.get(token, 1)
        if (ns, name) not in declared:
            findings.append({
                "file": rel, "line": ln, "severity": "error", "rule": "INCLUDE_LOCKSTEP", "source": "",
                "message": (
                    f"#include {token} has no matching libraries/*.groovy "
                    f"(a library() with namespace='{ns}', name='{name}'). The app won't compile -- "
                    f"add the library file."
                ),
            })
            continue  # downstream checks are moot without the file
        if name not in bundled_names:
            findings.append({
                "file": rel, "line": ln, "severity": "error", "rule": "INCLUDE_LOCKSTEP", "source": "",
                "message": (
                    f"#include {token} ({declared[(ns, name)]}) is not in tools/build-bundle.py LIBS -- "
                    f"the HPM bundle won't deliver it, so the app fails to compile on a user's hub after "
                    f"update. Add it to LIBS and rebuild the bundle."
                ),
            })
        if token not in registry_keys:
            findings.append({
                "file": rel, "line": ln, "severity": "error", "rule": "INCLUDE_LOCKSTEP", "source": "",
                "message": (
                    f"#include {token} is not in getPackageLibraryRegistry() -- hub_update_package would "
                    f'abort before deploying. Add the "{token}" entry.'
                ),
            })
    return findings


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

    # Check that every doc surface that lists discrete-event sensor capabilities
    # only names capabilities that are in production's DISCRETE_EVENT_CAPS map.
    # Catches the "doc surface drifts ahead of production" class -- agents
    # copying a stale-doc example would build a condition the live walker rejects.
    all_findings.extend(check_discrete_event_caps_doc_parity())

    # Check that every `catch (Exception updateExc)` block in the RM dispatcher
    # is followed by the full 5-slot trailing-updateRule envelope shape.
    # Catches the "dispatcher catches the click rejection but forgets to thread
    # the dedicated slots into the return shape" class -- callers cannot detect
    # the not-live state without log-grep otherwise.
    all_findings.extend(check_trailing_updaterule_envelope_parity())

    # Enforce the gateway read/write-split invariant: a read-only tool must be
    # reachable from a hub_read_* gateway or be flat -- NEVER stranded behind only
    # a hub_manage_* gateway (AGENTS.md "Gateway read/write split"). Catches the
    # "a read got added to a manage gateway but never surfaced on the read side"
    # class, which mislabels the read as a write and hides it from the read path.
    all_findings.extend(check_read_write_split())

    # Issue #209 lockstep: every #include'd library must have a libraries/ file + a build-bundle.py
    # LIBS entry + a getPackageLibraryRegistry() entry, so a broken/undelivered library fails CI
    # here instead of failing the app's compile on a user's hub.
    all_findings.extend(check_include_library_lockstep())

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
