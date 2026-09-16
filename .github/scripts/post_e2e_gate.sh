#!/usr/bin/env bash
# Post the REQUIRED "Full e2e (runs with label)" commit status from a job OUTSIDE the e2e job.
#
# The e2e job posts this status itself, but only once it is RUNNING. A pre-e2e job that fails
# (fork-scan blocking, fork-bundle erroring) SKIPS e2e, so nothing posts and the required gate sits
# at "Expected -- waiting for status to be reported": not pending, not red, and indistinguishable
# from a run still in flight. This script is what those jobs call to report the failure instead.
#
# Deliberately NOT used by the e2e job: that job checks out the PR head, so a fork PR would supply
# its own copy of this file. The e2e job keeps its inline post; only base-checkout jobs call this.
#
# Env: GH_TOKEN, REPO (owner/name), GATE_SHA (40-hex), STATE, DESC.
set -euo pipefail

: "${GH_TOKEN:?GH_TOKEN required}"
: "${REPO:?REPO required}"
: "${GATE_SHA:?GATE_SHA required}"
: "${STATE:?STATE required}"
: "${DESC:?DESC required}"

if ! printf '%s' "$GATE_SHA" | grep -qE '^[0-9a-f]{40}$'; then
  echo "::error::GATE_SHA is not a 40-hex commit SHA: $GATE_SHA"; exit 1
fi

# Same 5x retry as the other two posts: a transient API blip must not strand the gate, which is the
# whole point of this script.
for attempt in 1 2 3 4 5; do
  if gh api -X POST "repos/${REPO}/statuses/${GATE_SHA}" \
       -f state="$STATE" -f context="Full e2e (runs with label)" -f description="$DESC" >/dev/null; then
    echo "Posted gate status '$STATE': $DESC"
    exit 0
  fi
  echo "::warning::Gate status POST attempt ${attempt}/5 failed; retrying in 5s..."
  sleep 5
done
echo "::error::Gate status POST failed after 5 attempts."
exit 1
