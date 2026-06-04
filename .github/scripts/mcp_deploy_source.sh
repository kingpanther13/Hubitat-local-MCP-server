#!/usr/bin/env bash
# Snapshot the hub's current parent-app source, then attempt to push the
# PR's source via update_app_code. On a verified write, drops a
# "deploy landed" marker that mcp_restore_source.sh checks before
# pushing anything back -- so a no-op deploy (e.g. blocked by cloud
# body cap) doesn't trigger a wasted 1.5MB cleanup write.
#
# Usage: mcp_deploy_source.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL     -- full cloud OAuth URL with access_token
#        RUNNER_TEMP -- GHA temp dir; falls back to /tmp
#
# Preconditions (enforced hub-side by update_app_code):
#   - Developer Mode ON (self-update guard)
#   - enableHubAdminWrite ON
#   - Hub backup <24h (best-effort create_hub_backup attempted here;
#     gateway timeouts tolerated -- the gate is timestamp-cached and
#     CI runs keep the window populated)
#
# The MCP URL's app ID (e.g. 38) is the installed-instance ID, NOT the
# source-bearing Apps Code class ID. We look up the class ID via
# list_hub_apps and match by (namespace, name) from definition().

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

SOURCE_FILE="${1:-hubitat-mcp-server.groovy}"
PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"
PRE_LEN_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_charlen"
# Written only after the deploy is verified to have landed on the hub.
# Restore step early-exits if this is absent: no marker = nothing to undo.
DEPLOY_LANDED_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_landed"

# Pulled from the parent app's definition() block — keep these in sync if
# the parent ever changes its namespace/name (it shouldn't; HPM tracks it).
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

# Cloud's gateway window is ~10s; a 1.5MB Groovy recompile on a real C-7/
# C-8 hub takes ~10-25s. When cloud returns 5xx, we sleep, then poll
# get_app_source until totalLength changes from PRE_LEN (proving the
# write landed) or we hit the timeout.
POST_DEPLOY_VERIFY_SLEEP=20
POST_DEPLOY_VERIFY_TIMEOUT=90
POST_DEPLOY_VERIFY_INTERVAL=8

# Defensive: clear any stale marker from a previous run.
rm -f "$DEPLOY_LANDED_FILE"

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
: "${PR_SOURCE_URL:?PR_SOURCE_URL env var required -- the raw URL the hub fetches via update_app_code importUrl mode (the workflow sets it from the PR head SHA)}"
echo "Deploying class $CLASS_ID via importUrl (hub fetches ${NEW_BYTES} bytes from ${PR_SOURCE_URL})..."

# importUrl mode (issue #228): the hub fetches the PR branch's raw source
# directly, so the ~1.5MB blob never crosses the cloud gateway -- an inline
# source=... write reliably 504s before the hub receives it, but the
# importUrl RPC body is tiny (just the URL). importUrl shipped on
# update_app_code in #213 (2026-05-26), BEFORE the hub_ rename in #224
# (2026-06-02), so the pre-rename server currently on the test hub already
# exposes it. Self-update recompiles the MCP app mid-call, so the call may
# not return cleanly -- the 5xx / curl-failure tolerance and the
# get_app_source verify below already handle that.
DEPLOY_RPC_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_rpc.json"
jq -nc \
  --arg id "$CLASS_ID" \
  --arg url "$PR_SOURCE_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"update_app_code",arguments:{appId:$id,importUrl:$url,confirm:true}}}' \
  > "$DEPLOY_RPC_FILE"

# Poll get_app_source until totalLength differs from PRE_LEN, proving the
# write landed even when cloud bailed mid-recompile with a 5xx. Edge case:
# if the PR source happens to have identical char length to the pre-image
# (rare; whitespace-only or coincidentally length-matching diffs), this
# returns a false negative and the deploy is declared failed even though
# the write succeeded. Acceptable for an advisory continue-on-error step.
verify_deploy_landed() {
  local elapsed=0
  local rpc resp text current_len ok
  while [ $elapsed -lt $POST_DEPLOY_VERIFY_TIMEOUT ]; do
    rpc=$(jq -nc --arg id "$CLASS_ID" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"get_app_source",arguments:{appId:$id,offset:0,length:1}}}')
    resp=$(mcp_call "$rpc")
    text=$(echo "$resp" | jq -r '.result.content[0].text // empty')
    ok=$(echo "$text" | jq -r '.success // false')
    current_len=$(echo "$text" | jq -r '.totalLength // 0')
    if [ "$ok" = "true" ] && [ -n "$current_len" ] && [ "$current_len" != "$PRE_LEN" ] && [ "$current_len" != "0" ]; then
      echo "Deploy verified: hub source totalLength=$current_len differs from baseline=$PRE_LEN."
      return 0
    fi
    echo "Polling deploy... current totalLength=$current_len, baseline=$PRE_LEN, elapsed=${elapsed}s/${POST_DEPLOY_VERIFY_TIMEOUT}s"
    sleep $POST_DEPLOY_VERIFY_INTERVAL
    elapsed=$((elapsed + POST_DEPLOY_VERIFY_INTERVAL))
  done
  echo "::error::Deploy verification timed out after ${POST_DEPLOY_VERIFY_TIMEOUT}s; hub totalLength still $current_len (baseline was $PRE_LEN)"
  return 1
}

DEPLOY_RESP_FILE="${RUNNER_TEMP:-/tmp}/mcp_deploy_resp.txt"
# Don't || true here -- capture curl's exit code explicitly so DNS /
# connection / TLS failures surface distinctly from HTTP-level errors.
# set +e/-e around the call so curl's non-zero doesn't kill the script
# before we can report it.
set +e
mcp_call_stdin < "$DEPLOY_RPC_FILE" > "$DEPLOY_RESP_FILE"
CURL_RC=$?
set -e
rm -f "$DEPLOY_RPC_FILE"

if [ $CURL_RC -ne 0 ]; then
  echo "::error::curl exited $CURL_RC before HTTP exchange completed (DNS / connect / TLS / timeout)"
  rm -f "$DEPLOY_RESP_FILE"
  exit 1
fi

# Body and HTTP status are separated by curl -w's trailing `\n<code>`.
# `sed '$d'` drops the last line (portable, unlike GNU `head -c -N`).
HTTP_CODE=$(tail -n1 "$DEPLOY_RESP_FILE")
DEPLOY_BODY=$(sed '$d' "$DEPLOY_RESP_FILE")
echo "Hub responded HTTP $HTTP_CODE; body length=$(printf '%s' "$DEPLOY_BODY" | wc -c)"
rm -f "$DEPLOY_RESP_FILE"

if [ "$HTTP_CODE" = "200" ]; then
  # Happy path: parse the MCP envelope and accept the hub's own success report.
  # jq stderr passes through so parse errors are visible in CI logs.
  DEPLOY_TEXT=$(printf '%s' "$DEPLOY_BODY" | jq -r '.result.content[0].text // empty' || true)
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
  printf 'verified-via=200-json bytes=%s\n' "$NEW_BYTES" > "$DEPLOY_LANDED_FILE"
elif [ "$HTTP_CODE" = "504" ] || [ "$HTTP_CODE" = "502" ] || [ "$HTTP_CODE" = "503" ]; then
  # Cloud bailed before the hub finished recompiling. The hub usually
  # keeps working; poll get_app_source totalLength until it diverges
  # from the pre-deploy snapshot.
  echo "::notice::Hub returned HTTP $HTTP_CODE (cloud gateway timeout). Sleeping ${POST_DEPLOY_VERIFY_SLEEP}s for the hub-side recompile, then polling get_app_source to verify..."
  sleep $POST_DEPLOY_VERIFY_SLEEP
  if verify_deploy_landed; then
    echo "Deploy verified despite HTTP $HTTP_CODE. PR source is on the hub."
    printf 'verified-via=5xx-poll bytes=%s\n' "$NEW_BYTES" > "$DEPLOY_LANDED_FILE"
  else
    echo "::error::Deploy not verified -- hub source did not converge away from pre-deploy length."
    exit 1
  fi
else
  echo "::error::update_app_code got unexpected HTTP $HTTP_CODE"
  echo "Body head: $(printf '%s' "$DEPLOY_BODY" | head -c 1000)"
  exit 1
fi

echo "Pre-image preserved at $PRE_SOURCE_FILE, deploy-landed marker at $DEPLOY_LANDED_FILE."
