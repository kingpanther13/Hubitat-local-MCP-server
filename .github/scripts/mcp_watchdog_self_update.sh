#!/usr/bin/env bash
# Self-update the dead-man watchdog to THIS PR's e2e-deadman-watchdog-v2.groovy, over the watchdog's
# OWN /mcp endpoint ($WATCHDOG_URL), BEFORE arm/install. level99 only ever does the initial import; from
# then on every e2e run pulls the PR's watchdog version itself -- so e2e always exercises the watchdog
# the PR ships, not whatever copy happens to be installed.
#
# Skip-if-unchanged: only deploy when the hub's live watchdog source length differs from this PR's
# (a real edit to the watchdog changes its length), so a stable watchdog isn't needlessly reloaded.
#
# Brick-safe: the watchdog is small (~70KB), so its deploy returns synchronously within the relay window
# -- a compile error comes back success:false WITH the hub's verbatim message and the OLD watchdog keeps
# running (Hubitat never replaces code that fails to compile). After a successful update we re-verify the
# /mcp endpoint (tools/list) so a watchdog that compiled but broke its transport fails the run loudly.
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL must be set (from the WATCHDOG_MCP_URL secret)}"
: "${PR_RAW_BASE:?PR_RAW_BASE must be set}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED must be set}"

WD_NAMESPACE="mcp"
WD_NAME="E2E Dead-Man Watchdog v2"
WD_FILE="e2e-deadman-watchdog-v2.groovy"
WD_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${WD_FILE}"

mcp_call() { curl -sS --max-time 120 -X POST "$WATCHDOG_URL" -H "Content-Type: application/json" --data-binary "$1"; }
# Surfaces a JSON-RPC error envelope ({"error":...}) loudly and returns empty, so an importUrl
# fetch/compile failure fails fast with the real cause instead of looking like a dropped response.
call_tool() {
  local resp err
  resp=$(mcp_call "$1" || true)
  err=$(printf '%s' "$resp" | jq -r '.error.message // (.error | strings) // empty' 2>/dev/null || true)
  if [ -n "$err" ] && [ "$err" != "null" ]; then
    echo "::error::watchdog JSON-RPC error: $(printf '%s' "$err" | head -c 200)" >&2
    return 0
  fi
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}
RPC_ATTEMPTS="${RPC_ATTEMPTS:-5}"; RPC_RETRY_SLEEP="${RPC_RETRY_SLEEP:-5}"
call_tool_retry() {
  local attempt=1 out
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    out=$(call_tool "$1"); [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    attempt=$((attempt + 1)); [ "$attempt" -le "$RPC_ATTEMPTS" ] && sleep "$RPC_RETRY_SLEEP"
  done
  return 0
}
ok_of() { printf '%s' "$1" | jq -r '.success // false' 2>/dev/null || echo false; }
err_of() { printf '%s' "$1" | jq -r '.error // .message // .errorMessage // empty' 2>/dev/null || true; }

# Resolve the watchdog's OWN Apps Code CLASS id (namespace=mcp, name="E2E Dead-Man Watchdog v2").
LIST_TEXT=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
if [ -z "$LIST_TEXT" ]; then
  echo "::error::hub_list_apps (scope=types) returned no content from the watchdog -- cannot resolve the watchdog's own class id for self-update."
  exit 1
fi
WD_CLASS=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$WD_NAMESPACE" --arg name "$WD_NAME" \
  'first(.apps[]? | select(.namespace == $ns and .name == $name) | .id) // empty' 2>/dev/null || true)
if [ -z "$WD_CLASS" ] || [ "$WD_CLASS" = "null" ]; then
  echo "::error::Could not find the watchdog's own Apps Code class (${WD_NAMESPACE}:${WD_NAME}) via hub_list_apps -- is the watchdog installed? Cannot self-update."
  exit 1
fi
echo "Watchdog own Apps Code class id: ${WD_CLASS}"

# Always deploy this PR's watchdog (do NOT skip on a length match -- a length-preserving edit would
# leave the OLD watchdog live and validate the wrong code green). A byte-identical no-op is cheap (the
# hub won't reload), and the post-update length + tools/list gate confirms the right version is live.
PR_WD_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$WD_FILE" | tr -d '[:space:]')
echo "Deploying this PR's watchdog (length ${PR_WD_CHARS}) from ${WD_URL} ..."
# selfClassId == appId arms the issue #237 self-update capture: when the watchdog updates its OWN code
# the loopback POST reloads it mid-request, so an empty/null response is the EXPECTED success signal
# (without this, that null is read as a failure and reds a good run). The length + tools/list gate below
# still guards a genuine non-landing, so this can't false-green.
UPD_RPC=$(jq -nc --arg id "$WD_CLASS" --arg url "$WD_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,selfUpdate:true,selfClassId:$id,confirm:true}}}')
UPD_TEXT=$(call_tool "$UPD_RPC")

if [ -z "$UPD_TEXT" ]; then
  # A small app should answer synchronously; an empty body means the update reloaded the watchdog
  # mid-response (it IS updating itself). Treat it as in-flight and confirm by polling the live length.
  echo "::notice::No synchronous response (the watchdog is reloading itself) -- polling hub_get_source until its source length reaches ${PR_WD_CHARS}..."
elif [ "$(ok_of "$UPD_TEXT")" != "true" ]; then
  echo "::error::Watchdog self-update REJECTED by the hub (the OLD watchdog keeps running -- not bricked). Hub verbatim: $(err_of "$UPD_TEXT")"
  echo "Tool response: $(printf '%s' "$UPD_TEXT" | head -c 1500)"
  exit 1
fi

# Confirm the new watchdog is live AND its /mcp transport works: poll the source length (the self-update
# reloads the endpoint, so early calls may be empty), then assert tools/list responds.
DEADLINE=$(( $(date +%s) + 180 ))
CONFIRMED=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  sleep 10
  CUR=$(call_tool "$(jq -nc --arg id "$WD_CLASS" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')" | jq -r '.totalLength // empty' 2>/dev/null || true)
  if [ "$CUR" = "$PR_WD_CHARS" ]; then CONFIRMED="yes"; echo "Watchdog source now ${CUR} (matches this PR)."; break; fi
  echo "  ...watchdog length ${CUR:-<reloading>} != ${PR_WD_CHARS}; waiting for the reload to settle..."
done
if [ -z "$CONFIRMED" ]; then
  echo "::error::Watchdog self-update did not confirm within 180s (live source length never reached this PR's ${PR_WD_CHARS}). The update may have failed or the endpoint is down."
  exit 1
fi

TOOLS_N=$(printf '%s' "$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' || true)" | jq -r '.result.tools | length' 2>/dev/null || echo 0)
if ! [ "${TOOLS_N:-0}" -gt 0 ] 2>/dev/null; then
  echo "::error::Watchdog self-update compiled but its /mcp transport is not answering tools/list (got ${TOOLS_N:-0} tools). Investigate before relying on it."
  exit 1
fi
echo "WATCHDOG_SELF_UPDATE_OK class=${WD_CLASS} length=${PR_WD_CHARS} tools=${TOOLS_N}"
