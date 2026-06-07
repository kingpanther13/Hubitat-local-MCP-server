#!/usr/bin/env bash
# Disarm the E2E Dead-Man Watchdog at the end of a run (the dead-man's "I'm alive" signal).
#
# Writes {armed:false} to the watchdog flag file so a clean run can't leave the watchdog armed to
# fire its auto-restore (the ARM_WINDOW_MS deadline, ~35 min). This runs as an always() step BEFORE
# the lease is released, so the
# next run can never inherit an armed flag. A silently-failed disarm is the dangerous case (the
# watchdog would fire mid-next-run and revert the server), so every write is READ BACK and asserted
# -- this step succeeds only when armed==false is confirmed on the hub, else it fails loudly.
#
# Env:  MCP_URL        -- full cloud OAuth URL with access_token
#       GITHUB_RUN_ID  -- this run's id (stamped into the flag for traceability)
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

FLAG_FILE="e2e-deadman.json"

# --- helpers copied verbatim from mcp_deploy_source.sh (the proven cloud-gateway wrappers) -------

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
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

# --- write {armed:false}, then READ IT BACK and assert -------------------------------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; portable + sub-second precision is moot.
DISARMED_AT_MS=$(( $(date +%s) * 1000 ))
FLAG_JSON=$(jq -nc \
  --arg runId "$GITHUB_RUN_ID" \
  --argjson disarmedAt "$DISARMED_AT_MS" \
  '{armed:false, runId:$runId, disarmedAt:$disarmedAt}')

echo "Disarming the watchdog: writing {armed:false} to '$FLAG_FILE'..."
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
if [ "$RB_ARMED" != "false" ]; then
  echo "::error::Disarm did NOT land: flag read back armed=$RB_ARMED (expected false). The write may have silently no-opped, leaving the watchdog ARMED to fire (~35 min). Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

echo "WATCHDOG_DISARMED runId=$GITHUB_RUN_ID"
