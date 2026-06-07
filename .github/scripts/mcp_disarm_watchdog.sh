#!/usr/bin/env bash
# Disarm the E2E Dead-Man Watchdog v2 at the end of a run (the dead-man's "I'm alive" signal) AND
# drive its clean-finish restore of main.
#
# v2's disarm is not just "stop the timer": the watchdog treats intent=disarm as a request to restore
# the whole MCP package from the cache ONCE per runId (it stamps restoreFor=runId so it never repeats).
# So this step writes {armed:false, intent:"disarm", runId, ...manifest...} (carrying the SAME manifest
# the arm flag holds, so the watchdog knows what to restore), then POLLS the flag until the watchdog
# writes back restoreResult=="restored" && restoreFor==runId -- and ASSERTS that. The watchdog's
# checkDeadman runs every minute, so the restore lands within a couple of minutes; we poll on a bounded
# timeout. This runs as an always() step BEFORE the lease is released, so the next run can never inherit
# an armed flag, and main is left restored to the known-good cache.
#
# CRITICAL: every hub I/O here goes through the WATCHDOG's own MCP endpoint ($WATCHDOG_URL, from the
# WATCHDOG_MCP_URL secret), NOT the main server's $MCP_URL -- the watchdog is the driver of the restore
# plumbing and may be the only live server if the run bricked main. A silently-failed disarm is the
# dangerous case (the watchdog would otherwise fire mid-next-run), so the disarm WRITE is read back and
# the restore is polled+asserted -- this step succeeds only when armed==false landed AND the watchdog
# confirmed restoreResult==restored for this runId.
#
# Env:  WATCHDOG_URL   -- full cloud OAuth URL for the WATCHDOG's /mcp endpoint (WATCHDOG_MCP_URL secret)
#       GITHUB_RUN_ID  -- this run's id (stamped into the flag; the watchdog echoes it as restoreFor)
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint, from secret WATCHDOG_MCP_URL)}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

FLAG_FILE="e2e-deadman-v2.json"

# Bound the wait for the watchdog's once-a-minute checkDeadman to land the restore. ~6 min covers
# several schedule ticks plus restore time; well under the e2e job's own timeout.
RESTORE_POLL_ATTEMPTS=18
RESTORE_POLL_SLEEP=20

# --- helpers copied verbatim from mcp_deploy_source.sh (the proven cloud-gateway wrappers) -------
# Every call here targets $WATCHDOG_URL (the watchdog's own MCP endpoint), not the main $MCP_URL.

mcp_call() {
  curl -sS --max-time 120 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

RPC_ATTEMPTS=5
RPC_RETRY_SLEEP=6
mcp_tool_call_text() {
  local label="$1" rpc="$2" attempt=1 resp text
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    resp=$(mcp_call "$rpc" || true)
    if text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // ""' 2>/dev/null); then
      printf '%s' "$text"
      return 0
    fi
    echo "::warning::${label}: non-JSON gateway response (attempt ${attempt}/${RPC_ATTEMPTS}; likely the ~10s cloud-gateway timeout). Body head: $(printf '%s' "$resp" | head -c 120 | tr '\n' ' ')" >&2
    attempt=$((attempt + 1))
    [ "$attempt" -le "$RPC_ATTEMPTS" ] && sleep "$RPC_RETRY_SLEEP"
  done
  return 1
}

# --- 1) Read the current (armed) flag to reuse its manifest -----------------------------------------
# The watchdog's disarm-path restore reads flag.manifest, so the disarm flag MUST carry the same
# manifest the arm wrote. Rather than re-deriving it, we read the armed flag and reuse its manifest +
# mainSha, flipping armed:false / intent:"disarm". If no flag/manifest is present (arm never ran or it
# was wiped), we cannot drive a restore -- fail loudly.
echo "Reading the current flag '$FLAG_FILE' to reuse its restore manifest..."
READ_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! CUR_TEXT=$(mcp_tool_call_text "hub_read_file (read armed flag for manifest)" "$READ_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not read the armed flag to disarm. The watchdog may still be ARMED. Investigate / re-run."
  exit 1
fi
CUR_CONTENT=$(echo "$CUR_TEXT" | jq -r '.content // ""')
MANIFEST_JSON=$(printf '%s' "$CUR_CONTENT" | jq -c '.manifest // empty' 2>/dev/null || echo "")
MAIN_SHA=$(printf '%s' "$CUR_CONTENT" | jq -r '.mainSha // ""' 2>/dev/null || echo "")
if [ -z "$MANIFEST_JSON" ] || [ "$MANIFEST_JSON" = "null" ]; then
  echo "::error::The flag '$FLAG_FILE' has no manifest -- arm never ran or the flag was wiped. The watchdog cannot restore main on disarm. Response: $(printf '%s' "$CUR_TEXT" | head -c 600)"
  exit 1
fi

# --- 2) Write {armed:false, intent:"disarm", ...manifest...}, then READ IT BACK and assert ----------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; portable + sub-second precision is moot.
DISARMED_AT_MS=$(( $(date +%s) * 1000 ))
FLAG_JSON=$(jq -nc \
  --arg runId "$GITHUB_RUN_ID" \
  --argjson disarmedAt "$DISARMED_AT_MS" \
  --arg mainSha "$MAIN_SHA" \
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:false, intent:"disarm", runId:$runId, disarmedAt:$disarmedAt, mainSha:$mainSha, manifest:$manifest}')

echo "Disarming the watchdog: writing {armed:false, intent:\"disarm\"} to '$FLAG_FILE'..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (disarm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the disarm write. The watchdog may still be ARMED and could fire its auto-restore (~35 min). Investigate / re-run."
  exit 1
fi

echo "Reading the flag back to confirm it disarmed..."
READBACK_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! READBACK_TEXT=$(mcp_tool_call_text "hub_read_file (disarm read-back)" "$READBACK_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') read-back returned non-JSON $RPC_ATTEMPTS times -- could not confirm the disarm landed. The watchdog may still be ARMED. Investigate / re-run."
  exit 1
fi
RB_ARMED=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .armed' 2>/dev/null || echo "")
RB_INTENT=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .intent' 2>/dev/null || echo "")
if [ "$RB_ARMED" != "false" ] || [ "$RB_INTENT" != "disarm" ]; then
  echo "::error::Disarm did NOT land: flag read back armed=$RB_ARMED intent=$RB_INTENT (expected armed=false intent=disarm). The write may have silently no-opped, leaving the watchdog ARMED to fire (~35 min). Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

# --- 3) Poll for the watchdog's clean-finish restore (restoreResult==restored && restoreFor==runId) -
# checkDeadman runs every minute and processes intent=disarm exactly once per runId, stamping
# restoreFor=runId + restoreResult. We poll the flag until that stamp matches THIS run, then assert it
# is "restored". A bounded loop so a stuck/dead watchdog fails the step loudly instead of hanging.
echo "Polling '$FLAG_FILE' for the watchdog's clean-finish restore (restoreFor==$GITHUB_RUN_ID, up to $(( RESTORE_POLL_ATTEMPTS * RESTORE_POLL_SLEEP ))s)..."
POLL_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
attempt=1
RESTORE_RESULT=""
RESTORE_FOR=""
RESTORE_DETAIL=""
while [ "$attempt" -le "$RESTORE_POLL_ATTEMPTS" ]; do
  if ! POLL_TEXT=$(mcp_tool_call_text "hub_read_file (restore poll $attempt)" "$POLL_RPC"); then
    echo "::warning::Restore poll ${attempt}/${RESTORE_POLL_ATTEMPTS}: hub_read_file returned non-JSON $RPC_ATTEMPTS times -- transient; retrying."
  else
    RESTORE_RESULT=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreResult // ""' 2>/dev/null || echo "")
    RESTORE_FOR=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreFor // ""' 2>/dev/null || echo "")
    RESTORE_DETAIL=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreDetail // ""' 2>/dev/null || echo "")
    if [ "$RESTORE_FOR" = "$GITHUB_RUN_ID" ]; then
      # The watchdog has acted on THIS run's disarm. Assert it restored (vs. latched "failed").
      if [ "$RESTORE_RESULT" = "restored" ]; then
        echo "WATCHDOG_DISARMED runId=$GITHUB_RUN_ID restoreResult=restored restoreFor=$RESTORE_FOR detail=$RESTORE_DETAIL"
        exit 0
      fi
      echo "::error::Watchdog clean-finish restore FAILED for run $GITHUB_RUN_ID (restoreResult=$RESTORE_RESULT, restoreFor=$RESTORE_FOR). Main may NOT be restored to the known-good cache. Detail: $RESTORE_DETAIL"
      exit 1
    fi
    echo "Restore poll ${attempt}/${RESTORE_POLL_ATTEMPTS}: not yet (restoreResult=${RESTORE_RESULT:-<none>}, restoreFor=${RESTORE_FOR:-<none>}); waiting for checkDeadman..."
  fi
  attempt=$((attempt + 1))
  [ "$attempt" -le "$RESTORE_POLL_ATTEMPTS" ] && sleep "$RESTORE_POLL_SLEEP"
done

echo "::error::Timed out after $(( RESTORE_POLL_ATTEMPTS * RESTORE_POLL_SLEEP ))s waiting for the watchdog to restore main for run $GITHUB_RUN_ID (last seen restoreResult=${RESTORE_RESULT:-<none>}, restoreFor=${RESTORE_FOR:-<none>}). The disarm flag landed (armed=false) but checkDeadman did not confirm the restore -- the watchdog may be dead or its schedule dropped. Investigate."
exit 1
