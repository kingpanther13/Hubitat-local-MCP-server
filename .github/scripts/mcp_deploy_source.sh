#!/usr/bin/env bash
# Deploy the PR's parent-app source to the donated test hub so E2E
# actually exercises the code under review (rather than whatever was
# last deployed manually). Snapshots the hub's current source to
# RUNNER_TEMP first so mcp_restore_source.sh can put it back on
# cleanup -- if the snapshot fails, the deploy is skipped, so the
# hub is only ever in the PR-code state while we hold a verified
# pre-image.
#
# Usage:  mcp_deploy_source.sh [path/to/hubitat-mcp-server.groovy]
# Env:    MCP_URL              -- full cloud OAuth URL with access_token
#         HUBITAT_APP_ID       -- parent-app ID parsed from MCP_URL
#         RUNNER_TEMP          -- GHA-provided temp dir; falls back to /tmp
#
# Preconditions enforced by update_app_code in the hub:
#   - Developer Mode ON (otherwise the self-update guard refuses)
#   - enableHubAdminWrite ON (already set up by manual hub config)
#   - Hub backup <24h (best-effort create_hub_backup attempted here;
#     gateway timeouts are tolerated since the gate counts the cached
#     state.lastBackupTimestamp written by any previous successful
#     run, and CI runs keep that window populated)

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
: "${HUBITAT_APP_ID:?HUBITAT_APP_ID env var required (parent-app numeric ID)}"

SOURCE_FILE="${1:-hubitat-mcp-server.groovy}"
PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"

if [ ! -f "$SOURCE_FILE" ]; then
  echo "::error::Source file not found: $SOURCE_FILE"
  exit 1
fi

mcp_call() {
  # --max-time 120 -- update_app_code uploads ~1.2MB of Groovy and
  # the hub recompiles inline; 30s (the read/write default elsewhere
  # in CI) is too tight for the round-trip.
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d "$1"
}

echo "Triggering hub backup (best-effort; tolerated if it 504s -- the <24h gate is timestamp-cached)..."
BACKUP_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_hub_backup","arguments":{"confirm":true}}}'
mcp_call "$BACKUP_RPC" >/dev/null 2>&1 || \
  echo "::warning::create_hub_backup call failed/timed out; relying on cached <24h backup timestamp"

echo "Snapshotting current app $HUBITAT_APP_ID source to $PRE_SOURCE_FILE..."
: > "$PRE_SOURCE_FILE"
OFFSET=0
CHUNK_LEN=64000
while :; do
  RPC=$(jq -nc \
    --arg id "$HUBITAT_APP_ID" \
    --argjson off "$OFFSET" \
    --argjson len "$CHUNK_LEN" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"get_app_source",arguments:{appId:$id,offset:$off,length:$len}}}')
  if [ "$OFFSET" -eq 0 ]; then
    echo "DEBUG first-chunk request: $RPC"
  fi
  RESP=$(mcp_call "$RPC")
  TEXT=$(echo "$RESP" | jq -r '.result.content[0].text // empty')
  if [ -z "$TEXT" ]; then
    echo "::error::get_app_source returned empty MCP content (resp head: $(echo "$RESP" | head -c 500))"
    rm -f "$PRE_SOURCE_FILE"
    exit 1
  fi
  OK=$(echo "$TEXT" | jq -r '.success // false')
  if [ "$OK" != "true" ]; then
    echo "::error::get_app_source failed: $(echo "$TEXT" | jq -r '.error // empty')"
    rm -f "$PRE_SOURCE_FILE"
    exit 1
  fi
  # Debug: log shape on the first iteration so we can diagnose unexpected
  # response envelopes (e.g. after #174's response-size guard rollout).
  if [ "$OFFSET" -eq 0 ]; then
    echo "DEBUG first-chunk envelope keys: $(echo "$TEXT" | jq -r 'keys | join(",")')"
    echo "DEBUG first-chunk meta: success=$(echo "$TEXT" | jq -r '.success // null') totalLength=$(echo "$TEXT" | jq -r '.totalLength // null') chunkLength=$(echo "$TEXT" | jq -r '.chunkLength // null') hasMore=$(echo "$TEXT" | jq -r '.hasMore // null') sourceLen=$(echo "$TEXT" | jq -r '.source // "" | length')"
    echo "DEBUG first-chunk full text head: $(echo "$TEXT" | head -c 600)"
  fi
  # jq -j (join output, no trailing newline) -- chunks must concatenate
  # byte-for-byte; jq -r would inject a separator newline at every 64KB
  # boundary, corrupting the snapshot for the restore step.
  echo "$TEXT" | jq -j '.source // ""' >> "$PRE_SOURCE_FILE"
  HAS_MORE=$(echo "$TEXT" | jq -r '.hasMore // false')
  NEXT=$(echo "$TEXT" | jq -r '.nextOffset // empty')
  if [ "$HAS_MORE" != "true" ] || [ -z "$NEXT" ]; then
    break
  fi
  OFFSET="$NEXT"
done

PRE_BYTES=$(wc -c < "$PRE_SOURCE_FILE")
echo "Snapshotted $PRE_BYTES bytes."

if [ "$PRE_BYTES" -lt 1000 ]; then
  echo "::error::Pre-deploy snapshot is suspiciously small ($PRE_BYTES bytes); refusing to deploy"
  rm -f "$PRE_SOURCE_FILE"
  exit 1
fi

NEW_BYTES=$(wc -c < "$SOURCE_FILE")
echo "Pushing $SOURCE_FILE ($NEW_BYTES bytes) to app $HUBITAT_APP_ID..."

DEPLOY_RPC=$(jq -nc \
  --arg id "$HUBITAT_APP_ID" \
  --rawfile src "$SOURCE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"update_app_code",arguments:{appId:$id,source:$src,confirm:true}}}')

DEPLOY_RESP=$(mcp_call "$DEPLOY_RPC")
DEPLOY_TEXT=$(echo "$DEPLOY_RESP" | jq -r '.result.content[0].text // empty')

if [ -z "$DEPLOY_TEXT" ]; then
  echo "::error::update_app_code returned empty MCP content"
  echo "Full response head: $(echo "$DEPLOY_RESP" | head -c 1000)"
  exit 1
fi

DEPLOY_OK=$(echo "$DEPLOY_TEXT" | jq -r '.success // false')
if [ "$DEPLOY_OK" != "true" ]; then
  echo "::error::update_app_code failed: $(echo "$DEPLOY_TEXT" | jq -r '.error // .message // empty')"
  echo "Tool response: $DEPLOY_TEXT" | head -c 2000
  exit 1
fi

echo "Deploy succeeded. PR source ($NEW_BYTES bytes) is now live on the hub."
echo "Pre-image preserved at $PRE_SOURCE_FILE for cleanup-time restore."
