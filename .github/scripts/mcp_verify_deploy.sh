#!/usr/bin/env bash
# Positively confirm the test hub is running THIS PR's exact server source
# before the e2e tests run. The "Deploy PR source" step is continue-on-error
# (the hub recompiles mid-self-update and the deploy call legitimately returns
# a cloud 5xx -- a false error), and its internal verify only checked that the
# source *length* diverged from the pre-image. That can't tell a genuinely
# un-landed deploy apart from the tolerated 5xx, so e2e could silently run on
# STALE hub code (observed on #137: tests ran against a server missing the new
# tools). This step is the authoritative gate: it re-fetches the hub's full
# source and byte-compares it to the PR source. It is NOT continue-on-error --
# a mismatch fails the job loudly. (context: PR #236 / #234 e2e hardening)
#
# Usage: mcp_verify_deploy.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL     -- full cloud OAuth URL with access_token
#        RUNNER_TEMP -- GHA temp dir; falls back to /tmp
#
# Pass conditions:
#   - hub source == PR source                  -> verified (deploy landed, or a
#                                                 legitimate no-op where the hub
#                                                 already held byte-identical code)
# Fail conditions (exit 1):
#   - hub source != PR source after retries    -> deploy did NOT land; the e2e
#                                                 tests would run on stale code
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required}"

SOURCE_FILE="${1:-hubitat-mcp-server.groovy}"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"
PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"
HUB_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_hub_source.groovy"

APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

# A full re-fetch is ~24 chunked calls over the cloud relay; allow a few
# attempts for the hub-side recompile to settle and for transient relay flake.
FETCH_ATTEMPTS=4
ATTEMPT_SLEEP=15

if [ ! -f "$SOURCE_FILE" ]; then
  echo "::error::PR source file not found: $SOURCE_FILE"
  exit 1
fi

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Resolve the Apps Code CLASS id (source-bearing), reusing the deploy step's
# saved value when present; otherwise re-resolve via hub_list_apps scope=types.
CLASS_ID=""
if [ -f "$CLASS_ID_FILE" ]; then
  CLASS_ID="$(cat "$CLASS_ID_FILE")"
fi
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  # `|| true`: a transient curl/relay flake on this one call shouldn't abort the
  # gate via set -e -- fall through to the empty/null check below, which fails
  # loudly with a clear message (never a false pass).
  LIST_RESP=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}' || true)
  LIST_TEXT=$(echo "$LIST_RESP" | jq -r '.result.content[0].text // empty')
  # `[ ... ][0]` (not `... | head -n1`): selecting the first match inside jq avoids
  # a SIGPIPE-on-head abort under `set -o pipefail`.
  CLASS_ID=$(echo "$LIST_TEXT" | jq -r --arg ns "$APP_NAMESPACE" --arg name "$APP_NAME" \
    'first(.apps[]? | select(.namespace == $ns and .name == $name) | .id) // empty')
fi
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Could not resolve the Apps Code class id for $APP_NAMESPACE:$APP_NAME to verify the deploy."
  exit 1
fi

# Fetch the hub's full source for $CLASS_ID into $1. Returns 0 on a complete
# fetch, 1 on any transient empty/error chunk (caller retries the whole fetch).
fetch_hub_source() {
  local out="$1" offset=0 chunk=64000 rpc resp text ok hm nx
  : > "$out"
  while :; do
    rpc=$(jq -nc --arg id "$CLASS_ID" --argjson off "$offset" --argjson len "$chunk" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:$off,length:$len}}}')
    resp=$(mcp_call "$rpc")
    text=$(echo "$resp" | jq -r '.result.content[0].text // empty')
    [ -z "$text" ] && return 1
    ok=$(echo "$text" | jq -r '.success // false')
    [ "$ok" != "true" ] && return 1
    # jq -j: concatenate chunks byte-for-byte (no separator newline).
    echo "$text" | jq -j '.source // ""' >> "$out"
    hm=$(echo "$text" | jq -r '.hasMore // false')
    nx=$(echo "$text" | jq -r '.nextOffset // empty')
    { [ "$hm" != "true" ] || [ -z "$nx" ]; } && break
    offset="$nx"
  done
  return 0
}

PR_BYTES=$(wc -c < "$SOURCE_FILE")
echo "Verifying the hub is running THIS PR's source ($PR_BYTES bytes, class $CLASS_ID)..."

attempt=1
while [ "$attempt" -le "$FETCH_ATTEMPTS" ]; do
  if fetch_hub_source "$HUB_SOURCE_FILE"; then
    HUB_BYTES=$(wc -c < "$HUB_SOURCE_FILE")
    if cmp -s "$HUB_SOURCE_FILE" "$SOURCE_FILE"; then
      if [ -f "$PRE_SOURCE_FILE" ] && cmp -s "$PRE_SOURCE_FILE" "$SOURCE_FILE"; then
        echo "::notice::Verified no-op -- the hub already held byte-identical source ($PR_BYTES bytes). e2e runs on this PR's code."
      else
        echo "Verified: the hub source is byte-identical to this PR's source ($PR_BYTES bytes). e2e runs on this PR's code."
      fi
      exit 0
    fi
    echo "Attempt $attempt/$FETCH_ATTEMPTS: hub source is $HUB_BYTES B, not yet byte-identical to the PR's $PR_BYTES B (recompile may still be settling)."
  else
    echo "Attempt $attempt/$FETCH_ATTEMPTS: hub_get_source returned a transient empty/error chunk; retrying."
  fi
  attempt=$((attempt + 1))
  [ "$attempt" -le "$FETCH_ATTEMPTS" ] && sleep "$ATTEMPT_SLEEP"
done

HUB_BYTES=$(wc -c < "$HUB_SOURCE_FILE" 2>/dev/null || echo 0)
echo "::error::Deploy NOT verified: after $FETCH_ATTEMPTS attempts the hub's source is not byte-identical to this PR's source."
echo "::error::PR source = $PR_BYTES B; hub source = $HUB_BYTES B. The new source never landed -- the app most likely FAILED TO COMPILE or SAVE on the hub (e.g. an unresolved #include because a library wasn't installed, or a Groovy syntax error), so Hubitat kept the old source. The e2e tests would run against STALE hub code, so failing the job."
echo "First byte divergence:"
cmp "$HUB_SOURCE_FILE" "$SOURCE_FILE" 2>&1 | head -1 || true
exit 1
