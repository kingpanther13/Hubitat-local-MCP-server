#!/usr/bin/env bash
# Explicit maintenance must deploy only a ref whose complete non-E2E CI passed.
set -euo pipefail
: "${GITHUB_REPOSITORY:?}" "${GITHUB_SHA:?}"
RUNS=$(gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/actions/runs?head_sha=$GITHUB_SHA&per_page=100")
for workflow in unit-tests.yml groovy24-parse.yml groovy2x-spock.yml python-tests.yml ruff.yml sandbox-lint.yml pr-guard.yml self-deploy-recovery.yml lease-scripts-test.yml lane-gate-test.yml; do
  if ! printf '%s' "$RUNS" | jq -e --arg sha "$GITHUB_SHA" --arg path ".github/workflows/$workflow" '
      [.[].workflow_runs[] | select(.head_sha == $sha and .path == $path)] |
      sort_by(.run_number, .run_attempt) | last |
      .status == "completed" and .conclusion == "success"' >/dev/null; then
    echo "::error::Latest $workflow run has not passed on $GITHUB_SHA; no hub changes made."
    exit 1
  fi
done
echo "All ten non-E2E workflows passed on $GITHUB_SHA."
