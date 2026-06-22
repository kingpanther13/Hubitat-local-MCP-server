#!/usr/bin/env bash
# Acquire test hub lease for a CI run.
#
# Usage:  lease_acquire.sh <by-identifier>
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         LEASE_WAIT_TIMEOUT_S        — max seconds to WAIT for a successfully-read but HELD
#                                       lease to free before aborting (default 14400 = 4h: runs
#                                       QUEUE on the lease, so several PRs can wait their turn
#                                       on the single shared hub). Set 0 to fail fast.
#         LEASE_UNREACHABLE_TIMEOUT_S — max seconds to tolerate the endpoint being UNREADABLE
#                                       (5xx on every read = hub/app down, not lease held)
#                                       before fast-failing (default 120). Much shorter than the
#                                       held-lease budget: a down hub won't free a lease, so
#                                       waiting it out just burns ~10min per run.
#         LEASE_POLL_INTERVAL_S       — seconds between polls while waiting (default 30).
#
# Exits 0 on successful claim. While the lease is read as held by someone else and not
# expired, this WAITS (polling) and claims it automatically the moment it frees or
# the holder's TTL lapses — so a run queued behind another holder, or behind a manual
# hub session that GitHub's `concurrency` group can't see, starts on its own instead
# of needing a manual re-run. A brief read failure or a malformed/corrupt lease value
# mid-wait is polled through, never treated as free. Exits 1 if the lease is STILL held
# after LEASE_WAIT_TIMEOUT_S, if the endpoint stays UNREADABLE past LEASE_UNREACHABLE_TIMEOUT_S
# (the fast-fail for a down hub/app), or if the post-write race-check shows another claim last.
#
# Lease shape (JSON, written into Hubitat Hub Variable `_TEST_HUB_LEASED_BY`):
#   {"by":"<who>","since":<epoch_ms>,"until":<epoch_ms>}
# Empty string = released. See protocol in CLAUDE.md / issue #77 for context.

set -euo pipefail

BY="${1:?Usage: $0 <by-identifier>}"
: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

LEASE_DURATION_MIN=30
LEASE_WAIT_TIMEOUT_S="${LEASE_WAIT_TIMEOUT_S:-14400}"
LEASE_UNREACHABLE_TIMEOUT_S="${LEASE_UNREACHABLE_TIMEOUT_S:-120}"
LEASE_POLL_INTERVAL_S="${LEASE_POLL_INTERVAL_S:-30}"
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
  # Prints the lease value (may be "" when released) + exit 0 for a well-formed tools/call
  # result OR a "variable not found" error -- a FRESH hub where _TEST_HUB_LEASED_BY was never
  # created reads as released, so the first claim can bootstrap it (the claim write
  # auto-creates the var). Any OTHER non-result response exits non-zero so the caller polls
  # (read-fail path) and never falsely claims: a -32603 the main app emits mid-recompile during
  # another run's deploy, a transport failure, a non-JSON body. `jq -e` distinguishes a real
  # result from an error envelope; without it a missing .result.content collapsed to "" and
  # read as "released", double-booking the single hub.
  local resp
  resp="$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_manage_variables","arguments":{"tool":"hub_get_variable","args":{"name":"_TEST_HUB_LEASED_BY"}}}}')" || return 1
  # "Variable not found" -> the lease var was never created -> released/free. (A transient
  # not-found can't false-claim: the empty-break still requires a confirming second read.)
  if printf '%s' "$resp" | jq -e '(.error.message // "") | test("not found"; "i")' >/dev/null 2>&1; then
    return 0
  fi
  local text
  text="$(printf '%s' "$resp" | jq -e -r '.result.content[0].text')" || return 1
  printf '%s' "$text" | jq -e -r '.value // ""' || return 1
}

set_lease_value() {
  # Arg is the JSON-stringified value (already double-quoted + escaped).
  local value_json="$1"
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"hub_manage_variables\",\"arguments\":{\"tool\":\"hub_set_variable\",\"args\":{\"name\":\"_TEST_HUB_LEASED_BY\",\"value\":${value_json}}}}}" >/dev/null
}

fifo_my_turn() {
  # FIFO across PRs, decided GitHub-side (the hub never schedules e2e -- GitHub does). Ordered by
  # when each run actually ENTERED THE LINE: the start time of its "e2e (run)" job -- NOT
  # run_number (which is creation order). That distinction matters for two maintainer-flagged
  # cases:
  #   * an outside-contributor PR parks on the approve gate (status=waiting, already excluded
  #     below) until a human approves it; its e2e job then STARTS at approval time, so it joins
  #     the BACK of the line then -- it does NOT cut ahead on its old creation-time number.
  #   * applying a release:*/e2e:full label spawns a NEW run whose e2e job starts at label time,
  #     so it joins at the back too (the per-branch concurrency cancels the old focused run).
  # A FREE lease is claimed only by the run whose e2e job started earliest (ties broken by run id).
  # On any GitHub-API hiccup this returns "my turn" -- the lease claim+verify still guarantees
  # one-at-a-time, so a blip only degrades FIFO to first-come, never a double-book.
  [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ] || return 0
  command -v gh >/dev/null 2>&1 || return 0
  local ids id ts earliest_id="" earliest_ts=""
  ids="$(gh api "/repos/${GITHUB_REPOSITORY}/actions/workflows/hub-e2e.yml/runs?status=in_progress&per_page=100" \
         --jq '.workflow_runs[].id' 2>/dev/null)" || return 0
  [ -z "$ids" ] && return 0
  for id in $ids; do
    ts="$(gh api "/repos/${GITHUB_REPOSITORY}/actions/runs/${id}/jobs" \
          --jq '[.jobs[] | select(.name=="e2e (run)" and .started_at != null) | .started_at] | min // empty' 2>/dev/null)" || continue
    [ -z "$ts" ] && continue   # this run's e2e job hasn't started yet -> not contending for the lease yet
    if [ -z "$earliest_ts" ] || [[ "$ts" < "$earliest_ts" ]] || { [ "$ts" = "$earliest_ts" ] && [ "$id" -lt "$earliest_id" ]; }; then
      earliest_ts="$ts"; earliest_id="$id"
    fi
  done
  [ -z "$earliest_id" ] && return 0          # nobody's e2e job has started -> proceed (defensive)
  [ "$earliest_id" = "$GITHUB_RUN_ID" ]      # the earliest-started e2e job is mine -> my turn
}

# Wait (polling) until the lease is free, expired, or already ours — or until the
# wait budget runs out. The job's own timeout-minutes bounds the total, and because
# we claim only AFTER this wait, the lease TTL below starts at acquisition.
WAIT_DEADLINE_S=$(( $(now_ms) / 1000 + LEASE_WAIT_TIMEOUT_S ))
# Epoch-s of the FIRST of a run of consecutive read failures; reset to "" on any successful
# read. Lets a persistently-unreachable endpoint (hub/app down) fast-fail on its own short
# budget instead of burning the full held-lease wait below.
read_fail_since=""
while :; do
  # A brief read failure (cloud gateway 5xx past mcp_call's own 5 retries) is NOT fatal mid-
  # wait -- a failed read claims nothing, so ride out a transient blip. But a PERSISTENTLY
  # unreachable endpoint means the hub or its MCP app is DOWN, not that the lease is held;
  # a down hub never frees the lease, so waiting the full LEASE_WAIT_TIMEOUT_S there just burns
  # ~10min per run (and every queued run repeats it). Read failures therefore get their own
  # short LEASE_UNREACHABLE_TIMEOUT_S budget and fast-fail past it.
  if ! CURRENT="$(get_lease_value)"; then
    NOW_S=$(( $(now_ms) / 1000 ))
    [ -z "$read_fail_since" ] && read_fail_since="$NOW_S"
    UNREACH_S=$(( NOW_S - read_fail_since ))
    if [ "$UNREACH_S" -ge "$LEASE_UNREACHABLE_TIMEOUT_S" ]; then
      echo "::error::Test hub MCP endpoint unreachable for ${UNREACH_S}s (5xx on every lease read) -- the hub or its MCP server app is down, not the lease held. Aborting fast instead of waiting out the ${LEASE_WAIT_TIMEOUT_S}s held-lease budget."
      exit 1
    fi
    echo "::warning::Lease read failed (cloud gateway); endpoint unreachable ${UNREACH_S}s/${LEASE_UNREACHABLE_TIMEOUT_S}s, retrying next poll..."
    sleep "$LEASE_POLL_INTERVAL_S"
    continue
  fi
  read_fail_since=""  # a successful read clears the unreachable streak

  if [ -z "$CURRENT" ]; then
    # A single empty read is already trustworthy now that get_lease_value strict-parses (a
    # degraded response returns non-zero and polls, never a false-empty). This confirming read
    # closes the remaining race: a lease WRITTEN between read1 and our claim. If one appears,
    # fall through to the BUSY parse below instead of claiming over it.
    sleep "$VERIFY_SLEEP_S"
    if ! CONFIRM="$(get_lease_value)"; then
      echo "::notice::Lease read empty once but the confirm read failed; polling instead of claiming."
      sleep "$LEASE_POLL_INTERVAL_S"
      continue
    fi
    if [ -z "$CONFIRM" ]; then
      # Released (confirmed empty) -- but only the FIFO-earliest run in line claims it; an
      # earlier-queued run goes first. (The expired-lease reclaim further down is NOT gated:
      # that path is crash recovery for a holder that never released, where the dead holder
      # must not keep its place in line.)
      if fifo_my_turn; then
        break  # released + my turn
      fi
      echo "::notice::Lease is free but an earlier hub-e2e run is ahead in the queue; waiting my turn."
      sleep "$LEASE_POLL_INTERVAL_S"
      continue
    fi
    CURRENT="$CONFIRM"  # a lease appeared between the two reads -- fall through to the BUSY parse below
  fi

  NOW_MS="$(now_ms)"
  NOW_S=$(( NOW_MS / 1000 ))

  # Parse defensively. A non-empty value that isn't well-formed JSON, or whose `until`
  # is missing/non-numeric, means the variable holds SOMETHING (corrupt/truncated read,
  # partial write) — treat it as BUSY, never as free. Claiming over an unparseable value
  # would double-book the hub, which is exactly what the lease exists to prevent. (A bare
  # `// 0` fallback here would read as "expired -> reclaimable" and silently double-claim.)
  if ! CURRENT_BY="$(printf '%s' "$CURRENT" | jq -re '.by // ""' 2>/dev/null)" \
     || ! CURRENT_UNTIL="$(printf '%s' "$CURRENT" | jq -re '.until' 2>/dev/null)" \
     || ! printf '%s' "$CURRENT_UNTIL" | grep -qE '^[0-9]+$'; then
    if [ "$NOW_S" -ge "$WAIT_DEADLINE_S" ]; then
      echo "::error::Lease variable malformed/non-JSON through the ${LEASE_WAIT_TIMEOUT_S}s wait budget: $(printf '%.120s' "$CURRENT"). Aborting."
      exit 1
    fi
    echo "::warning::Lease variable holds a malformed/non-JSON value (corrupt or truncated read?); treating as busy: $(printf '%.120s' "$CURRENT")"
    sleep "$LEASE_POLL_INTERVAL_S"
    continue
  fi

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

# Post-write race-check: re-read after a short delay. If "by" isn't us, someone else's claim
# landed last and stole the lease — abort. Ride out a lone transient read failure here the way
# the wait loop does (one retry) so a blip in this 2s window doesn't fail a claim that actually
# landed; a sustained failure still aborts safely (the 30-min TTL bounds the lease we wrote).
sleep "$VERIFY_SLEEP_S"
if ! AFTER="$(get_lease_value)"; then
  echo "::warning::Post-write verify read failed transiently; re-reading once."
  sleep "$VERIFY_SLEEP_S"
  AFTER="$(get_lease_value)" || { echo "::error::Could not re-read the lease to verify our claim after writing; aborting (the 30-min TTL bounds the lease we wrote)."; exit 1; }
fi
AFTER_BY="$(printf '%s' "$AFTER" | jq -r '.by // ""' 2>/dev/null || echo "")"

if [ "$AFTER_BY" != "$BY" ]; then
  echo "::error::Lease stolen by '$AFTER_BY' before our verify check. Aborting."
  exit 1
fi

EXPIRES_TS="$(fmt_ts "$EXPIRES_MS")"
echo "Lease acquired: by=$BY, until=${EXPIRES_TS} (${LEASE_DURATION_MIN} min TTL)"
