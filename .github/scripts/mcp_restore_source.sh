#!/usr/bin/env bash
# Push the pre-deploy source snapshot back to the test hub. Gated on the
# deploy-landed marker that mcp_deploy_source.sh only writes after a
# verified write -- no marker means deploy was a no-op and there's
# nothing to undo.
#
# Runs in `if: always()` cleanup. Every error path falls through with a
# warning and exit 0 so lease release + env restore still run.
#
# Usage: mcp_restore_source.sh
# Env:   MCP_URL     -- full cloud OAuth URL with access_token
#        RUNNER_TEMP -- GHA temp dir; falls back to /tmp

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"
PRE_LEN_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_charlen"
DEPLOY_LANDED_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_landed"

POST_RESTORE_VERIFY_SLEEP=20
POST_RESTORE_VERIFY_TIMEOUT=90
POST_RESTORE_VERIFY_INTERVAL=8

# Marker-gated: deploy writes this only after verifying the hub actually
# took the write. Absent marker = deploy was a no-op = nothing to undo.
# Saves a wasted 1.2MB cleanup push against the donated test hub on every
# run while the cloud body cap blocks deploys.
if [ ! -f "$DEPLOY_LANDED_FILE" ]; then
  echo "No deploy-landed marker at $DEPLOY_LANDED_FILE -- deploy never mutated the hub. Skipping restore."
  exit 0
fi
echo "Deploy-landed marker present: $(cat "$DEPLOY_LANDED_FILE")"

if [ ! -f "$PRE_SOURCE_FILE" ]; then
  echo "::warning::Deploy-landed marker is present but no snapshot at $PRE_SOURCE_FILE -- can't restore. Hub may be on stale PR source."
  exit 0
fi

if [ ! -f "$CLASS_ID_FILE" ]; then
  echo "::warning::No class-ID sidecar at $CLASS_ID_FILE -- can't target the right Apps Code entry. Skipping restore."
  exit 0
fi

CLASS_ID=$(cat "$CLASS_ID_FILE")
if [ -z "$CLASS_ID" ]; then
  echo "::warning::Class-ID sidecar is empty. Skipping restore."
  exit 0
fi

PRE_LEN=""
if [ -f "$PRE_LEN_FILE" ]; then
  PRE_LEN=$(cat "$PRE_LEN_FILE")
fi

PRE_BYTES=$(wc -c < "$PRE_SOURCE_FILE")
if [ "$PRE_BYTES" -lt 1000 ]; then
  echo "::warning::Pre-source snapshot is suspiciously small ($PRE_BYTES bytes); refusing to push it back as the hub source"
  exit 0
fi

echo "Restoring class $CLASS_ID source from snapshot ($PRE_BYTES bytes, target charlen=$PRE_LEN)..."

RESTORE_RPC_FILE="${RUNNER_TEMP:-/tmp}/mcp_restore_rpc.json"
jq -nc \
  --arg id "$CLASS_ID" \
  --rawfile src "$PRE_SOURCE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,source:$src,confirm:true}}}' \
  > "$RESTORE_RPC_FILE"

RESTORE_RESP_FILE="${RUNNER_TEMP:-/tmp}/mcp_restore_resp.txt"
curl -sS --max-time 120 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -w '\n%{http_code}' \
  --data-binary "@$RESTORE_RPC_FILE" > "$RESTORE_RESP_FILE" || true
rm -f "$RESTORE_RPC_FILE"

# `sed '$d'` to drop the trailing status line is portable; GNU's
# `head -c -N` is not.
HTTP_CODE=$(tail -n1 "$RESTORE_RESP_FILE")
RESTORE_BODY=$(sed '$d' "$RESTORE_RESP_FILE")
echo "Hub responded HTTP $HTTP_CODE; body length=$(printf '%s' "$RESTORE_BODY" | wc -c)"
rm -f "$RESTORE_RESP_FILE"

# Polls hub_get_source until totalLength matches the pre-deploy charlen
# (meaning the restore landed).
verify_restored_to_pre_len() {
  if [ -z "$PRE_LEN" ] || [ "$PRE_LEN" = "0" ]; then
    echo "::warning::No PRE_LEN sidecar to verify against -- restore landed unverified."
    return 0
  fi
  local elapsed=0
  local rpc resp text current_len ok
  while [ $elapsed -lt $POST_RESTORE_VERIFY_TIMEOUT ]; do
    rpc=$(jq -nc --arg id "$CLASS_ID" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1}}}')
    resp=$(curl -sS --max-time 30 -X POST "$MCP_URL" \
      -H "Content-Type: application/json" \
      --data-binary "$rpc" || true)
    text=$(echo "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
    ok=$(echo "$text" | jq -r '.success // false' 2>/dev/null || echo false)
    current_len=$(echo "$text" | jq -r '.totalLength // 0' 2>/dev/null || echo 0)
    if [ "$ok" = "true" ] && [ "$current_len" = "$PRE_LEN" ]; then
      echo "Restore verified: hub source totalLength=$current_len matches pre-deploy baseline=$PRE_LEN."
      return 0
    fi
    echo "Polling restore... current totalLength=$current_len, target=$PRE_LEN, elapsed=${elapsed}s/${POST_RESTORE_VERIFY_TIMEOUT}s"
    sleep $POST_RESTORE_VERIFY_INTERVAL
    elapsed=$((elapsed + POST_RESTORE_VERIFY_INTERVAL))
  done
  echo "::warning::Restore verification timed out after ${POST_RESTORE_VERIFY_TIMEOUT}s; hub totalLength=$current_len (target $PRE_LEN)"
  return 1
}

# `if: always()` step -- a failure here must not abort the remaining
# cleanup (lease release, env restore), so every error path falls through
# with a warning and exit 0.
if [ "$HTTP_CODE" = "200" ]; then
  RESTORE_TEXT=$(printf '%s' "$RESTORE_BODY" | jq -r '.result.content[0].text // empty' || true)
  if [ -n "$RESTORE_TEXT" ] && [ "$(echo "$RESTORE_TEXT" | jq -r '.success // false')" = "true" ]; then
    echo "Restore succeeded (200/JSON). Hub class $CLASS_ID source returned to pre-run state."
    exit 0
  fi
  echo "::warning::Restore got 200 but body didn't parse / tool reported failure"
  echo "Body head: $(printf '%s' "$RESTORE_BODY" | head -c 800)"
  exit 0
elif [ "$HTTP_CODE" = "504" ] || [ "$HTTP_CODE" = "502" ] || [ "$HTTP_CODE" = "503" ]; then
  echo "::notice::Restore returned HTTP $HTTP_CODE (cloud gateway timeout). Polling to verify hub-side completion..."
  sleep $POST_RESTORE_VERIFY_SLEEP
  if verify_restored_to_pre_len; then
    echo "Restore verified despite HTTP $HTTP_CODE."
    exit 0
  fi
  echo "::warning::Restore not verified -- hub source may NOT be back to pre-run state."
  exit 0
else
  echo "::warning::Restore got HTTP $HTTP_CODE -- hub source likely NOT returned to pre-run state"
  echo "Body head: $(printf '%s' "$RESTORE_BODY" | head -c 800)"
  exit 0
fi
