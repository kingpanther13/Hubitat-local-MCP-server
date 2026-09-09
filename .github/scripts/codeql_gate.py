"""Fail an independent CodeQL scan on findings or incomplete SARIF evidence."""

import json
import sys
from pathlib import Path


def check_report(report):
    """Return the finding count; reject missing or unsuccessful scan evidence."""
    if not isinstance(report, dict) or report.get("version") != "2.1.0":
        raise ValueError("Expected a SARIF 2.1.0 report")
    runs = report.get("runs")
    if not isinstance(runs, list) or not runs:
        raise ValueError("Missing SARIF runs")
    count = 0
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
        count += len(results)
    return count


def main(argv):
    if len(argv) != 1:
        print("Usage: codeql_gate.py <report.sarif>", file=sys.stderr)
        return 2
    try:
        count = check_report(json.loads(Path(argv[0]).read_text(encoding="utf-8")))
    except (OSError, ValueError) as exc:
        print(f"CodeQL evidence error: {exc}", file=sys.stderr)
        return 2
    print(f"CodeQL: {count} findings. Full results are in the SARIF artifact.")
    return 1 if count else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
