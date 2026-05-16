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
PRE_LEN_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_charlen"

# Pulled from the parent app's definition() block — keep these in sync if
# the parent ever changes its namespace/name (it shouldn't; HPM tracks it).
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

# How long we wait for the hub-side recompile to finish AFTER cloud
# returns 504. Cloud's gateway window is ~10s; the hub's actual recompile
# of a 1.2MB Groovy app on a real Hubitat C-7/C-8 is ~10-25s; we poll
# get_app_source until totalLength changes from PRE_LEN (proving the
# write landed) or we time out.
POST_DEPLOY_VERIFY_SLEEP=20
POST_DEPLOY_VERIFY_TIMEOUT=90
POST_DEPLOY_VERIFY_INTERVAL=8

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
  # without shell-arg or newline mangling. -w '\n%{http_code}' appends the
  # HTTP status code on a new line so callers can distinguish "hub returned
  # 200 with non-JSON body" from "hub returned 504/413/etc." -- both fail
  # the JSON parse but for very different reasons.
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -w '\n%{http_code}' \
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
: > "$PRE_LEN_FILE"
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
    PRE_LEN=$(echo "$TEXT" | jq -r '.totalLength // 0')
    echo "DEBUG first-chunk meta: totalLength=$PRE_LEN chunkLength=$(echo "$TEXT" | jq -r '.chunkLength // null') hasMore=$(echo "$TEXT" | jq -r '.hasMore // null')"
    printf '%s' "$PRE_LEN" > "$PRE_LEN_FILE"
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

# Poll get_app_source until totalLength differs from a known baseline,
# proving the write landed even when cloud bailed mid-recompile with a
# 504. Caller passes the baseline charlen we want to see change away
# from (PRE_LEN for deploy verification, NEW_LEN-ish for restore).
verify_source_changed_from() {
  local baseline="$1"
  local what="$2"   # "deploy" or "restore" -- for logs
  local elapsed=0
  local rpc resp text current_len ok
  while [ $elapsed -lt $POST_DEPLOY_VERIFY_TIMEOUT ]; do
    rpc=$(jq -nc --arg id "$CLASS_ID" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"get_app_source",arguments:{appId:$id,offset:0,length:1}}}')
    resp=$(mcp_call "$rpc")
    text=$(echo "$resp" | jq -r '.result.content[0].text // empty')
    ok=$(echo "$text" | jq -r '.success // false')
    current_len=$(echo "$text" | jq -r '.totalLength // 0')
    if [ "$ok" = "true" ] && [ -n "$current_len" ] && [ "$current_len" != "$baseline" ] && [ "$current_len" != "0" ]; then
      echo "$what verified: hub source totalLength=$current_len differs from baseline=$baseline."
      return 0
    fi
    echo "Polling ($what)... current totalLength=$current_len, baseline=$baseline, elapsed=${elapsed}s/${POST_DEPLOY_VERIFY_TIMEOUT}s"
    sleep $POST_DEPLOY_VERIFY_INTERVAL
    elapsed=$((elapsed + POST_DEPLOY_VERIFY_INTERVAL))
  done
  echo "::error::$what verification timed out after ${POST_DEPLOY_VERIFY_TIMEOUT}s; hub totalLength still $current_len (baseline was $baseline)"
  return 1
}

DEPLOY_RESP_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_resp.txt"
mcp_call_stdin < "$DEPLOY_RPC_FILE" > "$DEPLOY_RESP_FILE" || true
rm -f "$DEPLOY_RPC_FILE"

# Body and HTTP status are separated by the trailing `\n<code>` from curl -w.
HTTP_CODE=$(tail -n1 "$DEPLOY_RESP_FILE")
# Strip the last line (status) to leave just the body.
DEPLOY_BODY=$(head -c -$((${#HTTP_CODE} + 1)) "$DEPLOY_RESP_FILE")
echo "Hub responded HTTP $HTTP_CODE; body length=$(printf '%s' "$DEPLOY_BODY" | wc -c)"
rm -f "$DEPLOY_RESP_FILE"

if [ "$HTTP_CODE" = "200" ]; then
  # Happy path: parse the MCP envelope and accept the hub's own success report.
  DEPLOY_TEXT=$(printf '%s' "$DEPLOY_BODY" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
  if [ -z "$DEPLOY_TEXT" ]; then
    echo "::error::update_app_code returned 200 but body was not parseable MCP JSON"
    echo "Body head (first 1500 bytes):"
    printf '%s' "$DEPLOY_BODY" | head -c 1500
    exit 1
  fi
  DEPLOY_OK=$(echo "$DEPLOY_TEXT" | jq -r '.success // false')
  if [ "$DEPLOY_OK" != "true" ]; then
    echo "::error::update_app_code failed: $(echo "$DEPLOY_TEXT" | jq -r '.error // .message // empty')"
    echo "Tool response: $DEPLOY_TEXT" | head -c 2000
    exit 1
  fi
  echo "Deploy succeeded (200/JSON). PR source ($NEW_BYTES bytes) is now live on the hub at class $CLASS_ID."
elif [ "$HTTP_CODE" = "504" ] || [ "$HTTP_CODE" = "502" ] || [ "$HTTP_CODE" = "503" ]; then
  # Cloud gateway gave up waiting (the hub-side recompile of a ~1.2MB
  # Groovy app takes longer than cloud's ~10s response budget). The hub
  # usually keeps working and completes the save -- verify by polling
  # get_app_source totalLength until it changes from the pre-deploy
  # snapshot.
  echo "::notice::Hub returned HTTP $HTTP_CODE (cloud gateway timeout). Sleeping ${POST_DEPLOY_VERIFY_SLEEP}s for the hub-side recompile, then polling get_app_source to verify..."
  sleep $POST_DEPLOY_VERIFY_SLEEP
  if verify_source_changed_from "$PRE_LEN" "deploy"; then
    echo "Deploy verified despite HTTP $HTTP_CODE. PR source is on the hub."
  else
    echo "::error::Deploy not verified -- hub source did not converge away from pre-deploy length."
    exit 1
  fi
else
  echo "::error::update_app_code got unexpected HTTP $HTTP_CODE"
  echo "Body head: $(printf '%s' "$DEPLOY_BODY" | head -c 1000)"
  exit 1
fi

echo "Pre-image preserved at $PRE_SOURCE_FILE for cleanup-time restore."
