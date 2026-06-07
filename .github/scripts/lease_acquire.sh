#!/usr/bin/env bash
# Acquire test hub lease for a CI run.
#
# Usage:  lease_acquire.sh <by-identifier>
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         LEASE_WAIT_TIMEOUT_S  — max seconds to WAIT for a busy lease to free
#                                 before aborting (default 600). Set 0 to fail fast.
#         LEASE_POLL_INTERVAL_S — seconds between polls while waiting (default 15).
#
# Exits 0 on successful claim. While the lease is held by someone else and not
# expired, this WAITS (polling) and claims it automatically the moment it frees or
# the holder's TTL lapses — so a run queued behind another holder, or behind a manual
# hub session that GitHub's `concurrency` group can't see, starts on its own instead
# of needing a manual re-run. Exits 1 only if the lease is STILL held after
# LEASE_WAIT_TIMEOUT_S, or if the post-write race-check shows another claim landed last.
#
# Lease shape (JSON, written into Hubitat Hub Variable `_TEST_HUB_LEASED_BY`):
#   {"by":"<who>","since":<epoch_ms>,"until":<epoch_ms>}
# Empty string = released. See protocol in CLAUDE.md / issue #77 for context.

set -euo pipefail

BY="${1:?Usage: $0 <by-identifier>}"
: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

LEASE_DURATION_MIN=30
LEASE_WAIT_TIMEOUT_S="${LEASE_WAIT_TIMEOUT_S:-600}"
LEASE_POLL_INTERVAL_S="${LEASE_POLL_INTERVAL_S:-15}"
VERIFY_SLEEP_S=2

# Portable epoch helpers (date +%s%N is GNU-only — fails on BSD/macOS). Seconds are
# derived from ms by the callers (NOW_MS / 1000) to avoid a second subprocess per poll.
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
# epoch-ms -> ISO8601 UTC, for human-readable log lines. timezone.utc (not datetime.UTC)
# so this stays valid on Python 3.2+, not just 3.11+.
fmt_ts() { python3 -c "import datetime,sys; print(datetime.datetime.fromtimestamp(int(sys.argv[1])//1000, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$1"; }

# Retry on transient cloud-gateway failures (the ~10s relay ceiling returns a 504,
# which --fail turns into curl exit 22). Without this a single 504 on lease acquire
# fails the WHOLE e2e before any test runs -- exactly the transient-flake class we
# don't want. A persistent failure (5 misses) still aborts honestly.
mcp_call() {
  local attempt=1 resp
  while [ "$attempt" -le 5 ]; do
    if resp=$(curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
        -H "Content-Type: application/json" -d "$1" 2>/dev/null); then
      printf '%s' "$resp"
      return 0
    fi
    echo "::warning::lease mcp_call attempt ${attempt}/5 failed (transient cloud-gateway error, e.g. 504). Retrying in 5s..." >&2
    attempt=$((attempt + 1))
    [ "$attempt" -le 5 ] && sleep 5
  done
  echo "::error::lease mcp_call: all 5 attempts failed (cloud gateway unreachable or persistent 5xx)." >&2
  return 1
}

get_lease_value() {
  mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_manage_variables","arguments":{"tool":"hub_get_variable","args":{"name":"_TEST_HUB_LEASED_BY"}}}}' \
    | jq -r '.result.content[0].text' \
    | jq -r '.value // ""'
}

set_lease_value() {
  # Arg is the JSON-stringified value (already double-quoted + escaped).
  local value_json="$1"
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"hub_manage_variables\",\"arguments\":{\"tool\":\"hub_set_variable\",\"args\":{\"name\":\"_TEST_HUB_LEASED_BY\",\"value\":${value_json}}}}}" >/dev/null
}

# Wait (polling) until the lease is free, expired, or already ours — or until the
# wait budget runs out. The job's own timeout-minutes bounds the total, and because
# we claim only AFTER this wait, the lease TTL below starts at acquisition.
WAIT_DEADLINE_S=$(( $(now_ms) / 1000 + LEASE_WAIT_TIMEOUT_S ))
while :; do
  NOW_MS="$(now_ms)"
  NOW_S=$(( NOW_MS / 1000 ))
  CURRENT="$(get_lease_value)"

  if [ -z "$CURRENT" ]; then
    break  # released
  fi
  CURRENT_BY="$(echo "$CURRENT" | jq -r '.by // ""' 2>/dev/null || echo "")"
  CURRENT_UNTIL="$(echo "$CURRENT" | jq -r '.until // 0' 2>/dev/null || echo 0)"
  if [ "$CURRENT_BY" = "$BY" ]; then
    break  # re-entrant: already held by this run
  fi
  if [ "$CURRENT_UNTIL" -le "$NOW_MS" ]; then
    break  # holder's TTL lapsed -> reclaimable
  fi

  # Held by someone else and still valid — wait for it.
  UNTIL_TS="$(fmt_ts "$CURRENT_UNTIL")"
  if [ "$NOW_S" -ge "$WAIT_DEADLINE_S" ]; then
    echo "::error::Test hub still leased by '$CURRENT_BY' until ${UNTIL_TS} after waiting ${LEASE_WAIT_TIMEOUT_S}s. Aborting."
    exit 1
  fi
  REMAIN_S=$(( WAIT_DEADLINE_S - NOW_S ))
  echo "::notice::Test hub leased by '$CURRENT_BY' until ${UNTIL_TS}; waiting for release (poll every ${LEASE_POLL_INTERVAL_S}s, up to ${REMAIN_S}s more)..."
  sleep "$LEASE_POLL_INTERVAL_S"
done

# Claim. Compute the TTL now, at acquisition time, so a preceding wait doesn't eat into it.
NOW_MS="$(now_ms)"
EXPIRES_MS=$((NOW_MS + LEASE_DURATION_MIN * 60 * 1000))

# Build the lease JSON, then JSON-stringify it for the set_variable arg.
CLAIM_JSON="$(jq -nc \
  --arg  by    "$BY" \
  --argjson since "$NOW_MS" \
  --argjson until "$EXPIRES_MS" \
  '{by: $by, since: $since, until: $until}')"
CLAIM_AS_STRING="$(printf '%s' "$CLAIM_JSON" | jq -Rs .)"

set_lease_value "$CLAIM_AS_STRING"

# Post-write race-check: re-read after a short delay. If "by" isn't us,
# someone else's claim landed last and stole the lease — abort.
sleep "$VERIFY_SLEEP_S"
AFTER="$(get_lease_value)"
AFTER_BY="$(echo "$AFTER" | jq -r '.by // ""' 2>/dev/null || echo "")"

if [ "$AFTER_BY" != "$BY" ]; then
  echo "::error::Lease stolen by '$AFTER_BY' before our verify check. Aborting."
  exit 1
fi

EXPIRES_TS="$(fmt_ts "$EXPIRES_MS")"
echo "Lease acquired: by=$BY, until=${EXPIRES_TS} (${LEASE_DURATION_MIN} min TTL)"
