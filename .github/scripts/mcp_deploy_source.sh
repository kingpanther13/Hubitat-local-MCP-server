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
#         HUBITAT_APP_ID       -- installed-instance ID parsed from MCP_URL.
#                                 NOT used for source operations -- the
#                                 source-bearing Apps Code entry lives at a
#                                 different ID in a separate namespace, so
#                                 we look it up via list_hub_apps + match by
#                                 (namespace, name) from definition().
#         RUNNER_TEMP          -- GHA-provided temp dir; falls back to /tmp
#
# Preconditions enforced by update_app_code in the hub:
#   - Developer Mode ON (otherwise the self-update guard refuses)
#   - enableHubAdminWrite ON (already set up by manual hub config)
#   - Hub backup <24h (best-effort create_hub_backup attempted here;
#     gateway timeouts are tolerated since the gate counts the cached
#     state.lastBackupTimestamp written by any previous successful
#     run, and CI runs keep that window populated)
#
# Large payloads (~1.2MB of Groovy) are streamed via stdin with
# `--data-binary @-` so we never push the RPC envelope through a
# command-line argument and hit Linux ARG_MAX.

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

SOURCE_FILE="${1:-hubitat-mcp-server.groovy}"
PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"

# Pulled from the parent app's definition() block — keep these in sync if
# the parent ever changes its namespace/name (it shouldn't; HPM tracks it).
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

if [ ! -f "$SOURCE_FILE" ]; then
  echo "::error::Source file not found: $SOURCE_FILE"
  exit 1
fi

# Two-arg call for small/in-process payloads; large payloads (the source
# blob in update_app_code) pipe stdin via mcp_call_stdin to dodge ARG_MAX.
mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

mcp_call_stdin() {
  # Caller pipes the RPC body to stdin. --data-binary @- streams the body
  # without shell-arg or newline mangling.
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary @-
}

echo "Triggering hub backup (best-effort; tolerated if it 504s -- the <24h gate is timestamp-cached)..."
BACKUP_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_hub_backup","arguments":{"confirm":true}}}'
# `>/dev/null` (not `2>&1`) so genuine curl errors -- DNS, connection refused,
# TLS handshake -- stay visible in the log even though the response body is
# discarded.
mcp_call "$BACKUP_RPC" >/dev/null || \
  echo "::warning::create_hub_backup call failed/timed out; relying on cached <24h backup timestamp"

echo "Looking up Apps Code class ID for $APP_NAMESPACE:$APP_NAME via list_hub_apps..."
LIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_hub_apps","arguments":{}}}'
LIST_RESP=$(mcp_call "$LIST_RPC")
LIST_TEXT=$(echo "$LIST_RESP" | jq -r '.result.content[0].text // empty')
if [ -z "$LIST_TEXT" ]; then
  echo "::error::list_hub_apps returned empty MCP content (resp head: $(echo "$LIST_RESP" | head -c 500))"
  exit 1
fi

# /hub2/userAppTypes returns an array of {id, name, namespace, ...}. Match
# both namespace AND name so we don't grab a fork that happens to reuse
# either field. `select(...)` returns nothing on miss -- guard against that.
CLASS_ID=$(echo "$LIST_TEXT" | jq -r \
  --arg ns "$APP_NAMESPACE" \
  --arg name "$APP_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)

if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Apps Code class for $APP_NAMESPACE:$APP_NAME not found via list_hub_apps."
  echo "DEBUG list_hub_apps envelope keys: $(echo "$LIST_TEXT" | jq -r 'keys | join(",")')"
  echo "DEBUG first 5 entries: $(echo "$LIST_TEXT" | jq -c '.apps[0:5] // []')"
  exit 1
fi

echo "Resolved class ID: $CLASS_ID (saving to $CLASS_ID_FILE for restore step)"
printf '%s' "$CLASS_ID" > "$CLASS_ID_FILE"

echo "Snapshotting current class $CLASS_ID source to $PRE_SOURCE_FILE..."
: > "$PRE_SOURCE_FILE"
OFFSET=0
CHUNK_LEN=64000
while :; do
  RPC=$(jq -nc \
    --arg id "$CLASS_ID" \
    --argjson off "$OFFSET" \
    --argjson len "$CHUNK_LEN" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"get_app_source",arguments:{appId:$id,offset:$off,length:$len}}}')
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
  if [ "$OFFSET" -eq 0 ]; then
    echo "DEBUG first-chunk meta: totalLength=$(echo "$TEXT" | jq -r '.totalLength // null') chunkLength=$(echo "$TEXT" | jq -r '.chunkLength // null') hasMore=$(echo "$TEXT" | jq -r '.hasMore // null')"
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
  rm -f "$PRE_SOURCE_FILE" "$CLASS_ID_FILE"
  exit 1
fi

NEW_BYTES=$(wc -c < "$SOURCE_FILE")
echo "Pushing $SOURCE_FILE ($NEW_BYTES bytes) to class $CLASS_ID..."

# Build the RPC envelope as a file (jq --rawfile handles JSON-escaping the
# 1.2MB source), then pipe to curl via stdin so we never put the body on
# the command line. Same dodge for the restore script.
DEPLOY_RPC_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_rpc.json"
jq -nc \
  --arg id "$CLASS_ID" \
  --rawfile src "$SOURCE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"update_app_code",arguments:{appId:$id,source:$src,confirm:true}}}' \
  > "$DEPLOY_RPC_FILE"

DEPLOY_RESP=$(mcp_call_stdin < "$DEPLOY_RPC_FILE")
rm -f "$DEPLOY_RPC_FILE"

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

echo "Deploy succeeded. PR source ($NEW_BYTES bytes) is now live on the hub at class $CLASS_ID."
echo "Pre-image preserved at $PRE_SOURCE_FILE for cleanup-time restore."
