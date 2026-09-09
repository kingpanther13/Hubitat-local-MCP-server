"""Fail a CodeQL branch scan on new findings or incomplete SARIF evidence."""

import json
import sys
from collections import Counter
from pathlib import Path


def check_report(report):
    """Return findings; reject missing or unsuccessful scan evidence."""
    if not isinstance(report, dict) or report.get("version") != "2.1.0":
        raise ValueError("Expected a SARIF 2.1.0 report")
    runs = report.get("runs")
    if not isinstance(runs, list) or not runs:
        raise ValueError("Missing SARIF runs")
    findings = []
    for run in runs:
        if not isinstance(run, dict):
            raise ValueError("Invalid SARIF run")
        invocations = run.get("invocations")
        if not isinstance(invocations, list) or not invocations:
            raise ValueError("Missing scan invocation evidence")
        if any(not isinstance(item, dict) or item.get("executionSuccessful") is not True for item in invocations):
            raise ValueError("Scan invocation did not succeed")
        results = run.get("results")
        if not isinstance(results, list):
            raise ValueError("Missing scan results")
        findings.extend(results)
    return findings


def finding_key(result):
    """Match stable CodeQL locations without hiding extra instances of a rule."""
    try:
        rule = result["ruleId"]
        location = result["locations"][0]["physicalLocation"]["artifactLocation"]["uri"]
        fingerprint = result["partialFingerprints"]["primaryLocationLineHash"]
        if not all(isinstance(value, str) and value for value in (rule, location, fingerprint)):
            raise ValueError("Invalid CodeQL finding identity")
        return rule, location, fingerprint
    except (KeyError, IndexError, TypeError) as exc:
        raise ValueError("Missing CodeQL finding identity") from exc


def new_findings(head, base):
    # Counter subtraction retains duplicate findings instead of collapsing them.
    return Counter(map(finding_key, head)) - Counter(map(finding_key, base))


def main(argv):
    if len(argv) != 2:
        print("Usage: codeql_gate.py <head.sarif> <base.sarif>", file=sys.stderr)
        return 2
    try:
        head, base = [check_report(json.loads(Path(path).read_text(encoding="utf-8"))) for path in argv]
        added = new_findings(head, base)
    except (OSError, ValueError) as exc:
        print(f"CodeQL evidence error: {exc}", file=sys.stderr)
        return 2
    count = added.total()
    print(f"CodeQL: {len(head)} branch findings, {len(base)} baseline findings, {count} new.")
    for (rule, path, fingerprint), occurrences in sorted(added.items()):
        print(f"  {rule}: {path} ({fingerprint}, {occurrences} occurrence(s))")
    print("Full branch and baseline results are in the SARIF artifact.")
    return 1 if count else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
