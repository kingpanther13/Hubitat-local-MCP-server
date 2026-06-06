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

# The cloud gateway has a ~10s per-request ceiling; a slow big read comes back as a non-JSON 502
# page that would crash an unguarded `... | jq`. mcp_tool_call_text retries ONLY that non-JSON case
# -- a valid JSON envelope (incl. a hub error) is returned immediately and never masked, so the
# strict byte-compare below still catches stale code or a true save failure. Prints the inner tool
# text; returns 0 on a parseable envelope (text may be empty -> caller checks), 1 only if every
# attempt was non-JSON. Warnings to stderr; caller decides if a final give-up is fatal.
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

# Resolve the Apps Code CLASS id (source-bearing), reusing the deploy step's
# saved value when present; otherwise re-resolve via hub_list_apps scope=types.
CLASS_ID=""
if [ -f "$CLASS_ID_FILE" ]; then
  CLASS_ID="$(cat "$CLASS_ID_FILE")"
fi
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  # Resilient read: retries only the non-JSON gateway-timeout case; a valid envelope (incl. a hub
  # error) is returned immediately. A persistently non-JSON gateway fails loudly as transient below.
  if ! LIST_TEXT=$(mcp_tool_call_text "hub_list_apps (verify class-ID lookup)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}'); then
    echo "::error::hub_list_apps: the Hubitat cloud gateway returned a non-JSON response $RPC_ATTEMPTS times -- a transient relay/timeout (the ~10s gateway ceiling), NOT a compile failure. Re-run the job."
    exit 1
  fi
  # `first(...)` inside jq (not `| head -n1`) avoids a SIGPIPE-on-head abort under set -o pipefail.
  # `2>/dev/null || true`: an empty/odd LIST_TEXT must fall through to the empty-CLASS_ID check
  # below (a loud, honest failure), not abort here.
  CLASS_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$APP_NAMESPACE" --arg name "$APP_NAME" \
    'first(.apps[]? | select(.namespace == $ns and .name == $name) | .id) // empty' 2>/dev/null || true)
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
    text=$(mcp_tool_call_text "hub_get_source (verify offset $offset)" "$rpc") || return 1
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
