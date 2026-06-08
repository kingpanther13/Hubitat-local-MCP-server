#!/usr/bin/env bash
# Shared helpers for the watchdog-driven e2e scripts (deploy / arm / self-update). Source it:
#   source "$(dirname "$0")/mcp_watchdog_lib.sh"
# Every call targets $WATCHDOG_URL (the watchdog's own /mcp endpoint), never the main $MCP_URL.
#
# Single source of truth for the JSON-RPC plumbing + the deploy CONFIRMATION, so the three callers
# cannot drift. call_tool surfaces a JSON-RPC error envelope loudly (a tool/protocol error must not be
# mistaken for an empty relay drop). deploy_app_via_watchdog confirms a deploy LANDED via a FRESH
# lastSelfDeploy success -- the only signal that works for a relay-dropped response AND a same-length
# change AND a no-op, closing the "length match == landed" false-green class.

mcp_call() { curl -sS --max-time 120 -X POST "$WATCHDOG_URL" -H "Content-Type: application/json" --data-binary "$1"; }

# Echo the inner tool-result text, or empty. A JSON-RPC ERROR envelope ({"error":...}, no .result) is a
# real hub/protocol failure: surface it loudly and return empty so the caller's empty-check fails -- it
# must NOT look like a (recoverable) dropped response.
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

WD_RPC_ATTEMPTS="${WD_RPC_ATTEMPTS:-5}"
WD_RPC_RETRY_SLEEP="${WD_RPC_RETRY_SLEEP:-5}"
# Like call_tool but retries an IDEMPOTENT read on an empty (relay-dropped) result. Reads only.
call_tool_retry() {
  local attempt=1 out
  while [ "$attempt" -le "$WD_RPC_ATTEMPTS" ]; do
    out=$(call_tool "$1"); [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    attempt=$((attempt + 1)); [ "$attempt" -le "$WD_RPC_ATTEMPTS" ] && sleep "$WD_RPC_RETRY_SLEEP"
  done
  return 0
}

ok_of() { printf '%s' "$1" | jq -r '.success // false' 2>/dev/null || echo false; }
err_of() { printf '%s' "$1" | jq -r '.error // .message // .errorMessage // empty' 2>/dev/null || true; }

# resolve_class_id NS NAME -> echoes the Apps Code CLASS id (matching namespace AND name), exits 1 loudly
# on an unusable response or a miss. Retries the read so a single relay 504 can't hard-fail.
resolve_class_id() {
  local ns="$1" name="$2" list_text class_id
  list_text=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
  if [ -z "$list_text" ]; then
    echo "::error::hub_list_apps (scope=types) returned no content from the watchdog -- cannot resolve the class id for ${ns}:${name}." >&2
    exit 1
  fi
  class_id=$(printf '%s' "$list_text" | jq -r --arg ns "$ns" --arg name "$name" \
    'first(.apps[]? | select(.namespace == $ns and .name == $name) | .id) // empty' 2>/dev/null || true)
  if [ -z "$class_id" ] || [ "$class_id" = "null" ]; then
    echo "::error::Apps Code class ${ns}:${name} not found via hub_list_apps (scope=types) on the watchdog endpoint." >&2
    exit 1
  fi
  printf '%s' "$class_id"
}

# app_total_length CLASS_ID -> echoes the live app source totalLength (a noSave probe, so it never
# auto-saves the live source over the dead-man restore cache), or empty.
app_total_length() {
  local id="$1"
  call_tool_retry "$(jq -nc --arg id "$id" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')" \
    | jq -r '.totalLength // empty' 2>/dev/null || true
}

# deploy_app_via_watchdog CLASS_ID SOURCE_URL [LABEL] [SELF_CLASS_ID]
# Deploy an app via the watchdog importUrl and CONFIRM it landed via a FRESH lastSelfDeploy success.
# This is the only confirmation that survives a relay-dropped 1.6MB response, a SAME-LENGTH source change
# (no length transition to observe), and a byte-identical no-op -- so a stale/wrong source can never
# false-green as "landed". Pass SELF_CLASS_ID (== CLASS_ID) when the watchdog is deploying its OWN code,
# so its self-reload's empty/null POST response isn't read as a failure (issue #237). Returns 0 on
# confirmed success; exits 1 on a confirmed rejection (surfacing the hub's verbatim compile error) or if
# it can't confirm within the window.
deploy_app_via_watchdog() {
  local class_id="$1" src_url="$2" label="${3:-app}" self_class="${4:-}"
  local pre_info pre_at deploy_text deploy_rpc
  # Baseline lastSelfDeploy.at. REQUIRE the read to succeed: without a known baseline we cannot tell this
  # deploy's fresh record from a stale prior-run one (the false-green the idiot-check flagged).
  pre_info=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}')
  if [ -z "$pre_info" ]; then
    echo "::error::[$label] could not read hub_get_info to baseline the deploy confirmation -- re-run." >&2
    exit 1
  fi
  pre_at=$(printf '%s' "$pre_info" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
  echo "[$label] Deploying app class ${class_id} via importUrl (${src_url}) through the watchdog (lastSelfDeploy.at baseline ${pre_at})..."
  if [ -n "$self_class" ]; then
    deploy_rpc=$(jq -nc --arg id "$class_id" --arg url "$src_url" --arg sc "$self_class" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,selfUpdate:true,selfClassId:$sc,confirm:true}}}')
  else
    deploy_rpc=$(jq -nc --arg id "$class_id" --arg url "$src_url" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,confirm:true}}}')
  fi
  deploy_text=$(call_tool "$deploy_rpc")
  # A synchronous result with success:false is a hub rejection -- fail with its verbatim message.
  if [ -n "$deploy_text" ] && [ "$(ok_of "$deploy_text")" != "true" ]; then
    echo "::error::[$label] Hub REJECTED the deploy (synchronous). Verbatim: $(err_of "$deploy_text")" >&2
    echo "Tool response: $(printf '%s' "$deploy_text" | head -c 1500)" >&2
    exit 1
  fi
  # Confirm via a FRESH lastSelfDeploy for this class (the 1.6MB deploy's response is usually relay-dropped).
  local deadline info cur_app cur_at cur_ok cur_err
  deadline=$(( $(date +%s) + 420 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    sleep 5
    info=$(call_tool '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}')
    cur_app=$(printf '%s' "$info" | jq -r '.lastSelfDeploy.appId // empty' 2>/dev/null || true)
    cur_at=$(printf '%s' "$info" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
    cur_ok=$(printf '%s' "$info" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
    cur_err=$(printf '%s' "$info" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
    if [ "$cur_app" = "$class_id" ] && [ "${cur_at:-0}" -gt "${pre_at:-0}" ] 2>/dev/null; then
      if [ "$cur_ok" = "false" ]; then
        echo "::error::[$label] Hub REJECTED the deploy (fresh lastSelfDeploy, at ${cur_at} > ${pre_at}). Verbatim compile error: ${cur_err:-<none>}" >&2
        exit 1
      fi
      if [ "$cur_ok" = "true" ]; then
        echo "[$label] Deploy confirmed: fresh lastSelfDeploy success for class ${class_id} (at ${cur_at} > baseline ${pre_at})."
        return 0
      fi
    fi
    echo "  ...[$label] not confirmed yet (lsd.appId=${cur_app:-?} at=${cur_at:-?}/${pre_at}); waiting for a fresh lastSelfDeploy..."
  done
  echo "::error::[$label] deploy did not confirm within 420s (no FRESH lastSelfDeploy success for class ${class_id}, at > ${pre_at}). Re-run; if it persists, check the watchdog app logs." >&2
  exit 1
}
