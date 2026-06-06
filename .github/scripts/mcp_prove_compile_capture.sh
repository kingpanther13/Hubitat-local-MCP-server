#!/usr/bin/env bash
# Prove, live on every run, that a rejected self-deploy's VERBATIM hub error is captured and
# recoverable via hub_get_info.lastSelfDeploy (issue #237).
#
# Runs AFTER the main deploy has landed the persist-capable code. It submits a deliberately-broken
# self-update -- a Hubitat app whose definition() has NO name -- to the MCP server's OWN Apps Code
# class. The hub REJECTS it (keeping the good, running code intact), records the verbatim validation
# error to atomicState.lastSelfDeploy, and this step recovers + asserts it via hub_get_info -- the
# exact channel the big-app 504 path uses. We deliberately ignore the broken update's inline response
# and recover via hub_get_info, so this exercises the recovery channel, not just the inline error.
#
# Safety: a rejected save NEVER replaces the running app, so this is non-destructive. The step also
# re-checks the real app's marker afterward to confirm the broken source was rejected, not applied.
#
# Env:  MCP_URL      -- full cloud OAuth URL with access_token
#       RUNNER_TEMP  -- GHA temp dir (for the deploy step's saved class id); falls back to /tmp
set -uo pipefail

: "${MCP_URL:?MCP_URL env var required}"

CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"
EXPECT_SUBSTR="name cannot be empty"

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" -H "Content-Type: application/json" --data-binary "$1"
}
tool_text() {
  local resp
  resp=$(mcp_call "$1" || true)
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

# Resolve the self Apps Code CLASS id: reuse the deploy step's saved value, else look it up.
CLASS_ID=""
[ -f "$CLASS_ID_FILE" ] && CLASS_ID="$(cat "$CLASS_ID_FILE")"
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  LT=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
  CLASS_ID=$(printf '%s' "$LT" | jq -r 'first(.apps[]? | select(.namespace=="mcp" and .name=="MCP Rule Server") | .id) // empty' 2>/dev/null || true)
fi
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::compile-capture probe: could not resolve the MCP server's Apps Code class id."
  exit 1
fi
echo "Compile-capture probe (issue #237): target self class id = $CLASS_ID"

# Keep the destructive-confirm <24h backup window warm.
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup failed/timed out; relying on cached <24h backup timestamp"

# A deliberately-broken Hubitat app: definition() with NO name. The hub must reject it with
# "name cannot be empty in definition section". Small enough to POST inline (no 504).
BROKEN_SRC='definition(namespace: "mcp", author: "ci", description: "issue #237 e2e no-name compile-failure probe")

preferences { page(name: "mainPage", title: "x", install: true, uninstall: true) {} }

def installed() {}
def updated() {}'

RPC=$(jq -nc --arg id "$CLASS_ID" --arg src "$BROKEN_SRC" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,source:$src,confirm:true}}}')
echo "Submitting a deliberately-broken self-update (definition with no name); the hub must reject it..."
# Ignore the inline response ON PURPOSE -- we recover via hub_get_info so this proves the same
# recovery channel the big-app 504 path depends on, not just the small-source inline error.
mcp_call "$RPC" >/dev/null || true

# Recover the hub's verbatim error from atomicState.lastSelfDeploy via hub_get_info.
attempt=1; FOUND=""; MARKER=""
while [ "$attempt" -le 5 ]; do
  INFO=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}')
  OK=$(printf '%s' "$INFO" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
  ERR=$(printf '%s' "$INFO" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
  MARKER=$(printf '%s' "$INFO" | jq -r '.smokeTestMarker // empty' 2>/dev/null || true)
  if [ "$OK" = "false" ] && [ -n "$ERR" ]; then FOUND="$ERR"; break; fi
  echo "Polling hub_get_info.lastSelfDeploy for the rejected self-deploy (attempt ${attempt}/5; success=${OK:-none})..."
  attempt=$((attempt + 1)); [ "$attempt" -le 5 ] && sleep 5
done

if [ -z "$FOUND" ]; then
  echo "::error::compile-capture probe FAILED: hub_get_info.lastSelfDeploy never surfaced a failed self-deploy error after the broken update (issue #237 capture not working)."
  exit 1
fi
echo "Recovered the hub's VERBATIM self-deploy error via hub_get_info.lastSelfDeploy:"
echo "    ${FOUND}"
case "$FOUND" in
  *"$EXPECT_SUBSTR"*)
    echo "Matched the expected substring \"$EXPECT_SUBSTR\" -- compile-error capture is PROVEN end-to-end." ;;
  *)
    echo "::error::Captured a self-deploy error, but it did not contain \"$EXPECT_SUBSTR\". Got: ${FOUND}"
    exit 1 ;;
esac

# The broken save must have been REJECTED -- confirm the real app is still running (marker intact).
if [ "$MARKER" != "smoke-ok-v1" ]; then
  echo "::error::After the probe the running app's smokeTestMarker is '${MARKER}', not 'smoke-ok-v1' -- the broken source may NOT have been rejected as expected. Investigate the hub (the restore step will push the pre-run source back)."
  exit 1
fi
echo "Confirmed the real app is still running (smokeTestMarker=smoke-ok-v1): the broken self-update was rejected, not applied. Non-destructive."
