#!/usr/bin/env python3
"""Pre-approval scan of a FORK PR's CI-executed files (issue: e2e secret exposure).

The e2e job runs the PR head's `.github/scripts/*`, `tests/*.py` and the pip
requirements it installs, with the test hub's MCP_URL / WATCHDOG_URL at job scope.
The approval click is the security boundary, so this turns "read the diff first"
into a short list: every ADDED line under the executed paths that makes a network
call, reads the environment, or changes an installed dependency.

Advisory by design -- e2e tests legitimately call the network, so findings cannot
block. Two things DO exit nonzero, because in both cases the reviewer would
otherwise approve on a report that isn't one: a change to the pinned conformance
requirements (a poisoned dependency needs no knowledge of this project and looks
like housekeeping in a diff), and a watched file whose diff the API won't serve
(oversized or binary), which leaves its added code unread. A maintainer who wants
either case tested can dispatch hub-e2e.yml manually.

Runs from the BASE checkout with no secrets and never executes PR code -- it only
reads the PR's patch through the API.

Env: GH_TOKEN, BASE_REPO (owner/name), PR_NUMBER. SCAN_DRY_RUN=1 prints the comment
body and the exit verdict without posting, for validating the scanner itself.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys

MARKER = "<!-- fork-pr-scan -->"
HARD_FAIL_PATH = "tests/sdk-conformance-requirements.txt"

# Paths the e2e job EXECUTES from the PR head (mcp_watchdog_deploy.sh, e2e_test.py,
# sdk_conformance_test.py, lane/lease scripts, and the pip install of the requirements).
WATCHED = (".github/scripts/", "tests/")

PATTERNS: list[tuple[str, str]] = [
    ("network", r"\b(curl|wget|nc|ncat|socat|ssh|scp)\b"),
    ("network", r"https?://"),
    # Call-ish use only: naming a library in an `except requests.HTTPError` is not a network call,
    # and that noise is what makes a reviewer stop reading the list.
    ("network", r"\b(requests|httpx|session)\.(get|post|put|patch|delete|head|request|Session)\b"),
    ("network", r"\b(urllib|urllib2|urllib3|http\.client|socket|aiohttp|ftplib|smtplib|telnetlib)\."),
    ("network", r"^\s*(import|from)\s+(requests|httpx|urllib|socket|aiohttp|ftplib|smtplib)\b"),
    ("network", r"\bwebhook\b"),
    ("env read", r"\$\{?(MCP_URL|WATCHDOG_URL|GH_TOKEN|GITHUB_TOKEN|HUBITAT_[A-Z_]+)\b"),
    ("env read", r"\bos\.(environ|getenv)\b"),
    ("env read", r"\b(printenv|env\s*\||set\s*\||export\s+-p)\b"),
    ("env read", r"\bsecrets\."),
    ("env read", r"::add-mask::"),
    ("obfuscation", r"\b(base64|b64encode|b64decode|hexlify|rot13|codecs\.encode)\b"),
    ("obfuscation", r"\bxxd\b|\bod\s+-"),
    ("dependency", r"\bpip\s+install\b|\buv\s+pip\b|\bnpm\s+i(nstall)?\b"),
]
COMPILED = [(label, re.compile(rx)) for label, rx in PATTERNS]


def gh(*args: str) -> str:
    return subprocess.run(
        ["gh", *args], check=True, capture_output=True, text=True
    ).stdout


def watched(path: str) -> bool:
    return path.startswith(WATCHED) or path == HARD_FAIL_PATH


def added_lines(patch: str) -> list[tuple[int, str]]:
    """Added lines as (new-file line number, text), from the hunk headers."""
    out: list[tuple[int, str]] = []
    lineno = 0
    for raw in patch.splitlines():
        hunk = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)", raw)
        if hunk:
            lineno = int(hunk.group(1))
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            out.append((lineno, raw[1:]))
            lineno += 1
        elif not raw.startswith("-"):
            lineno += 1
    return out


def build_report(files: list[dict]) -> tuple[str, bool]:
    """Return the comment body and whether the scan blocks approval."""
    findings: list[tuple[str, int, str, str]] = []
    dep_changed = []
    scanned = []
    unscannable = []
    for f in files:
        path = f["filename"]
        if not watched(path):
            continue
        if path == HARD_FAIL_PATH:
            dep_changed.append(path)
        patch = f.get("patch")
        # The API omits `patch` for an oversized or binary diff. Reporting such a file as scanned
        # would hand the reviewer a clean list over code nobody read, so it blocks instead.
        if not patch and f.get("status") != "removed":
            unscannable.append(path)
            continue
        scanned.append(path)
        for lineno, text in added_lines(patch or ""):
            stripped = text.strip()
            if not stripped or stripped.startswith("#"):
                continue
            # ALL matching kinds, not the first: an exfiltration line reads the environment AND
            # calls the network, and a reviewer needs to see both against that one line.
            kinds = sorted({label for label, rx in COMPILED if rx.search(text)})
            if kinds:
                findings.append((path, lineno, " + ".join(kinds), stripped[:200]))

    lines = [MARKER, "## Fork PR pre-approval scan", ""]
    if not scanned and not unscannable:
        lines += [
            "No changes under `.github/scripts/`, `tests/`, or the pinned requirements file, "
            "so nothing the e2e job executes from this PR changed.",
        ]
    elif scanned:
        lines += [
            "The e2e job runs these files from the PR head with the test hub's MCP credentials "
            "in scope, so read the lines below before approving the run.",
            "",
            "Files scanned: " + ", ".join(f"`{p}`" for p in sorted(scanned)),
            "",
        ]
        if findings:
            lines += ["| File | Line | Kind | Added code |", "| --- | --- | --- | --- |"]
            for path, lineno, label, text in findings:
                cell = text.replace("|", "\\|").replace("`", "'")
                lines.append(f"| `{path}` | {lineno} | {label} | `{cell}` |")
        else:
            # Only ever said about files actually read -- next to an unreadable diff it would be
            # the false assurance this scan exists to prevent.
            lines.append("No network calls, environment reads, or dependency changes in the added lines.")
        lines += [
            "",
            "Advisory: e2e tests make network calls as a matter of course, so these are for reading, "
            "not a verdict.",
        ]
    if dep_changed:
        lines += [
            "",
            f"**Blocking: `{HARD_FAIL_PATH}` changed.** A dependency bump reaches the runner as "
            "installed code and needs no knowledge of this project, so e2e will not be offered for "
            "approval on this PR. Vet the pins, then run the workflow manually if the change is wanted.",
        ]
    if unscannable:
        lines += [
            "",
            "**Blocking: no diff available for "
            + ", ".join(f"`{p}`" for p in sorted(unscannable))
            + ".** GitHub omits the patch for an oversized or binary change, so this scan cannot "
            "report what those files add. Read them at the PR head; e2e is not offered for approval "
            "until they are scannable, and a manual workflow run is the way through.",
        ]

    return "\n".join(lines), bool(dep_changed or unscannable)


def main() -> int:
    repo = os.environ["BASE_REPO"]
    pr = os.environ["PR_NUMBER"]

    pages = json.loads(gh("api", f"repos/{repo}/pulls/{pr}/files?per_page=100", "--paginate", "--slurp"))
    body, blocking = build_report([f for page in pages for f in page])
    print(body)

    if os.environ.get("SCAN_DRY_RUN") == "1":
        print(f"::notice::dry run -- not posting; would exit {1 if blocking else 0}")
        return 0

    existing = json.loads(gh("api", f"repos/{repo}/issues/{pr}/comments?per_page=100", "--paginate", "--slurp"))
    existing = [c for page in existing for c in page]
    mine = next((c for c in existing if MARKER in (c.get("body") or "")), None)
    try:
        if mine:
            gh("api", "-X", "PATCH", f"repos/{repo}/issues/comments/{mine['id']}", "-f", f"body={body}")
        else:
            gh("api", "-X", "POST", f"repos/{repo}/issues/{pr}/comments", "-f", f"body={body}")
    except subprocess.CalledProcessError as exc:
        print(f"::error::could not post the scan comment: {exc.stderr}", file=sys.stderr)
        return 1

    if blocking:
        print("::error::fork scan blocks approval (pinned-dependency change, or a file it could "
              "not read) -- see the scan comment.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
