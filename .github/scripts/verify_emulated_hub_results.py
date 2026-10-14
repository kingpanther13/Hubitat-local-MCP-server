#!/usr/bin/env python3
"""Reject an empty or incomplete software-hub integration run."""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def validate_reports(directory: Path, expected_suite: str, minimum_tests: int = 1) -> int:
    if minimum_tests < 1:
        raise ValueError("Minimum scenario count must be positive")
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No JUnit test reports in {directory}")

    total = 0
    expected_count = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as exc:
            raise ValueError(f"Malformed JUnit report: {report.name}") from exc
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        if not suites:
            raise ValueError(f"Missing test suite in {report.name}")
        for suite in suites:
            cases = suite.findall("testcase")
            if not cases:
                raise ValueError(f"Empty test suite in {report.name}")
            for attribute in ("tests", "failures", "errors", "skipped"):
                value = suite.get(attribute)
                if value is not None:
                    try:
                        count = int(value)
                    except ValueError as exc:
                        raise ValueError(f"Invalid {attribute} count in {report.name}") from exc
                    expected = len(cases) if attribute == "tests" else 0
                    if count != expected:
                        raise ValueError(f"Inconsistent or nonpassing {attribute} count in {report.name}")
            for case in cases:
                if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                    raise ValueError(f"Scenario did not pass: {suite.get('name')}.{case.get('name')}")
            total += len(cases)
            if suite.get("name") == expected_suite:
                expected_count += len(cases)
    if not expected_count:
        raise ValueError(f"Required integration suite did not execute: {expected_suite}")
    if expected_count < minimum_tests:
        raise ValueError(f"Required integration suite executed {expected_count} scenarios; expected at least {minimum_tests}")
    return total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--min-tests", type=int, default=1)
    args = parser.parse_args()
    try:
        count = validate_reports(args.report_directory, args.suite, args.min_tests)
    except (OSError, ValueError) as exc:
        print(f"Software-hub result validation failed: {exc}", file=sys.stderr)
        return 1
    print(f"Software-hub integration: {count} scenarios passed; no failed or skipped scenarios.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
