"""Require completed successful non-E2E workflow runs for the exact experiment SHA."""

import argparse
import json
import os
import re
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

WORKFLOWS = (
    "unit-tests.yml", "groovy2x-spock.yml", "groovy24-parse.yml", "sandbox-lint.yml",
    "python-tests.yml", "ruff.yml", "pr-guard.yml", "lane-gate-test.yml",
    "lease-scripts-test.yml", "self-deploy-recovery.yml", "dependency-updates-check.yml",
)
BRANCH = "experiment/529-compile-static"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    sha = os.environ["GITHUB_SHA"]
    repository = os.environ["GITHUB_REPOSITORY"]
    if os.environ["GITHUB_REF"] != f"refs/heads/{BRANCH}":
        raise ValueError("Publication is restricted to the compile-static experiment branch")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected an immutable full source SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository")
    records = []
    for workflow in WORKFLOWS:
        runs = []
        page = 1
        while True:
            query = urlencode({"head_sha": sha, "per_page": 100, "page": page})
            request = Request(
                f"https://api.github.com/repos/{repository}/actions/workflows/{workflow}/runs?{query}",
                headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                         "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"},
            )
            with urlopen(request, timeout=30) as response:
                data = json.load(response)
            runs.extend(run for run in data["workflow_runs"]
                        if run["head_sha"] == sha and run["head_branch"] == BRANCH)
            if page * 100 >= data["total_count"]:
                break
            page += 1
        if not runs:
            raise ValueError(f"{workflow}: no run for {sha} on {BRANCH}")
        if any(run["status"] != "completed" for run in runs):
            raise ValueError(f"{workflow}: a run for this SHA is still in flight")
        latest = max(runs, key=lambda run: (run["id"], run.get("run_attempt", 1)))
        if latest["conclusion"] != "success":
            raise ValueError(f"{workflow}: latest run concluded {latest['conclusion']}: {latest['html_url']}")
        record = {key: latest[key] for key in ("id", "run_attempt", "head_sha", "status", "conclusion", "html_url")}
        record["workflow"] = workflow
        records.append(record)
        print(f"{workflow}: success {latest['html_url']}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"sourceSha": sha, "branch": BRANCH, "runs": records}, indent=2) + "\n",
                           encoding="utf-8")


if __name__ == "__main__":
    main()
