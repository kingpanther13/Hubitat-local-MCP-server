#!/usr/bin/env bash
# >>> TEMPORARY (issue #209 / #237 one-time validation) -- DELETE after one green run <<<
#
# Proves a compile-fail error reaches the e2e logs, end to end, on the live hub.
#
# Deploys a 1-line invalid-Groovy source INLINE to the MCP server's OWN Apps Code
# class (the same class the deploy step already landed + byte-verified the good PR
# code on). The hub REJECTS it at the compile gate and KEEPS the good code -- a
# rejected save never reloads the app -- so the hub is never bricked. It then
# asserts BOTH capture channels carried the hub's VERBATIM error:
#
#   1. synchronous: the hub_update_app response (success:false + non-empty error)
#      -- the path mcp_deploy_source.sh uses for small/inline/HTTP-200 deploys.
#   2. persist-in-state: atomicState.lastSelfDeploy (success:false + non-empty
#      error), read back via hub_get_info -- the recovery channel the big-source
#      importUrl/504 deploy relies on (issue #237).
#
# importUrl is null for an inline deploy, so channel 2 matches on success:false +
# non-empty error, NOT on importUrl (which the production recover_self_deploy_error
# keys on for the 504 path). This step never writes the deploy-landed marker, so
# mcp_restore_source.sh (gated on that marker) does nothing for this throwaway
# deploy, and the subsequent bundle-resave step re-asserts the good app anyway.
#
# REMOVAL: delete this file and the "TEMP one-time compile-error capture
# validation" step in .github/workflows/hub-e2e.yml once one real e2e run prints
# a COMPILE-ERROR-CAPTURE-OK line. (grep -rn TEMPORARY .github/ ; grep -rln TEMP_validate .github/)
# >>> END TEMPORARY <<<
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"

if [ ! -s "$CLASS_ID_FILE" ]; then
  echo "::error::TEMP validation: class-id sidecar $CLASS_ID_FILE missing/empty -- the deploy step (mcp_deploy_source.sh) must run before this step."
  exit 1
fi
CLASS_ID="$(cat "$CLASS_ID_FILE")"
echo "TEMP validation targeting MCP server own Apps Code class id=$CLASS_ID (reusing the deploy step's resolved id; no re-resolve)."

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Retry ONLY the non-JSON case (the ~10s cloud-gateway timeout). A real JSON
# envelope -- including a genuine hub error -- returns immediately and is never
# retried, so this can't turn a real result into a false pass.
mcp_text() {
  local label="$1" rpc="$2" attempt=1 resp text
  while [ "$attempt" -le 5 ]; do
    resp=$(mcp_call "$rpc" || true)
    if text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // ""' 2>/dev/null); then
      printf '%s' "$text"; return 0
    fi
    echo "::warning::${label}: non-JSON gateway response (attempt ${attempt}/5). Head: $(printf '%s' "$resp" | head -c 120 | tr '\n' ' ')" >&2
    attempt=$((attempt + 1)); [ "$attempt" -le 5 ] && sleep 6
  done
  return 1
}

# Baseline lastSelfDeploy.at BEFORE the deliberate fail so the assertion proves a
# FRESH write -- NOT a stale success:false record left by a prior run (which would
# otherwise pass the check spuriously). This exact stale-record case was observed
# live on a real hub, so the freshness check is load-bearing, not theoretical.
PRE_LSD_AT=$(mcp_text "hub_get_info (pre-fail lastSelfDeploy baseline)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' 2>/dev/null | jq -r '.lastSelfDeploy.at // empty' 2>/dev/null || true)
echo "Pre-fail lastSelfDeploy.at baseline: ${PRE_LSD_AT:-<none>}"

# A 1-line unterminated expression: a guaranteed Groovy parse error. No valid
# stub, no definition() block, no library reference -- it can ONLY be rejected at
# the compile gate, so the good running code is kept.
BAD_SRC='def x = '
UPDATE_RPC=$(jq -nc --arg id "$CLASS_ID" --arg src "$BAD_SRC" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,source:$src,confirm:true}}}')

echo "Deploying a deliberately-broken inline source to class $CLASS_ID (expecting a clean compile rejection)..."
if ! UPDATE_TEXT=$(mcp_text "hub_update_app (deliberate compile-fail)" "$UPDATE_RPC"); then
  echo "::error::TEMP validation: hub_update_app got a non-JSON gateway response 5x -- a transient relay timeout, not a capture result. Re-run the job."
  exit 1
fi

SYNC_OK=$(printf '%s' "$UPDATE_TEXT" | jq -r '.success // empty')
SYNC_ERR=$(printf '%s' "$UPDATE_TEXT" | jq -r '.error // .message // empty')
echo "Synchronous channel -> success=${SYNC_OK:-<none>} error=${SYNC_ERR:-<none>}"

if [ "$SYNC_OK" = "true" ] || [ -z "$SYNC_ERR" ]; then
  echo "::error::TEMP validation FAILED (synchronous channel): expected success=false + a non-empty verbatim error; got success=${SYNC_OK:-<none>}, error=${SYNC_ERR:-<empty>}. Either the bad source compiled (it must not) or the hub returned no error text -- capture is broken."
  echo "Full tool response: $(printf '%s' "$UPDATE_TEXT" | head -c 2000)"
  exit 1
fi

echo "Reading hub_get_info.lastSelfDeploy to confirm the persist-in-state recovery channel recorded the same rejection (and that the record is FRESH, not a stale leftover)..."
LSD_OK="" ; LSD_ERR="" ; LSD_AT="" ; attempt=1
while [ "$attempt" -le 3 ]; do
  INFO_TEXT=$(mcp_text "hub_get_info (lastSelfDeploy check, try $attempt)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}') || INFO_TEXT=""
  LSD_OK=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
  LSD_ERR=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
  LSD_AT=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.at // empty' 2>/dev/null || true)
  if [ "$LSD_OK" = "false" ] && [ -n "$LSD_ERR" ] && [ -n "$LSD_AT" ] && [ "$LSD_AT" != "${PRE_LSD_AT:-}" ]; then break; fi
  attempt=$((attempt + 1)); [ "$attempt" -le 3 ] && sleep 5
done
echo "Persist-in-state channel -> lastSelfDeploy.success=${LSD_OK:-<none>} error=${LSD_ERR:-<none>} at=${LSD_AT:-<none>} (baseline ${PRE_LSD_AT:-<none>})"

if [ "$LSD_OK" != "false" ] || [ -z "$LSD_ERR" ] || [ -z "$LSD_AT" ] || [ "$LSD_AT" = "${PRE_LSD_AT:-}" ]; then
  echo "::error::TEMP validation FAILED (persist-in-state channel): hub_get_info.lastSelfDeploy did not record a FRESH rejection (success=${LSD_OK:-<none>}, error=${LSD_ERR:-<empty>}, at=${LSD_AT:-<none>}, baseline=${PRE_LSD_AT:-<none>}). Either nothing was recorded, or only a stale record from a prior run is present -- the big-source importUrl/504 recovery path (issue #237) would not surface a verbatim error for THIS deploy."
  exit 1
fi

echo "COMPILE-ERROR-CAPTURE-OK (synchronous): ${SYNC_ERR}"
echo "COMPILE-ERROR-CAPTURE-OK (persist-in-state): ${LSD_ERR}"
echo "::notice::Both capture channels surfaced the hub's verbatim compile error; the good code remains live (a compile-fail is rejected at the gate). Safe to remove this TEMP step + script (see header)."
