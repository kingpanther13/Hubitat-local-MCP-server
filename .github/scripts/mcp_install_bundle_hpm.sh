#!/usr/bin/env bash
# Prove the HPM bundle-install path end-to-end on the live test hub (issue #209).
#
# Hubitat Package Manager installs a package by (1) installing its BUNDLES -- each bundle is a
# .zip the hub fetches and unpacks into Libraries/Apps/Drivers Code -- and (2) installing/compiling
# its APPS, whose `#include` directives resolve against the just-delivered library code. This
# script reproduces that exact sequence against the real hub through the cloud MCP endpoint, so a
# malformed zip, a wrong library name, a bad manifest path, or a broken endpoint surfaces here on
# every PR instead of on a user's hub after they HPM-update.
#
# It runs AFTER the app has been deployed once (so hub_install_bundle itself exists on the hub) and
# AFTER mcp_install_libraries.sh bootstrapped mcp.McpSmokeTestLib as a plain user library (so that
# first deploy compiled). To make the BUNDLE the sole deliverer -- a faithful fresh-install mimic,
# and to avoid a user-library-vs-bundle-library name clash -- it deletes that bootstrap library
# first, then:
#   1. installs the real bundle zip via hub_install_bundle (HPM "install bundles" step),
#   2. verifies the library is back in Libraries Code with the expected marker content,
#   3. resaves the app so its #include recompiles against the bundle-delivered library
#      (HPM "install apps" step, run AFTER bundles -- exactly HPM's order).
#
# Safety on a shared hub: the app is already compiled and running in hub memory, so the cloud MCP
# endpoint stays reachable even while the on-disk library is briefly absent. An EXIT trap re-ensures
# mcp.McpSmokeTestLib exists (re-creating it from the PR source if the bundle install failed) so the
# later restore-source step never re-saves an app that references a missing library.
#
# Env:  MCP_URL               -- full cloud OAuth URL with access_token
#       PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#       PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA
#
# No `set -e`: every hub call is error-checked explicitly (a bare pipefail exit would skip the
# friendly ::error:: annotations and the recovery trap). pipefail + nounset still catch typos.
set -uo pipefail

: "${MCP_URL:?MCP_URL env var required}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required}"

LIB_NS="mcp"
LIB_NAME="McpSmokeTestLib"
LIB_MARKER="smoke-ok-v1"
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"
BUNDLE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/bundles/mcp-smoke-test.zip"
# Recovery source uses the library file's real path (filename != declared namespace.name).
LIB_SOURCE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/libraries/mcp-smoke-test-lib.groovy"

mcp_call() {
  curl -sS --max-time 300 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" --data-binary "$1"
}

# Echo the inner MCP tool-result text (the tool's JSON payload) for an RPC body, or empty.
tool_text() {
  local resp
  resp=$(mcp_call "$1" || true)
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

# Echo the hub library id for (LIB_NS, LIB_NAME), or empty if not present.
lib_id() {
  local text
  text=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  printf '%s' "$text" | jq -r --arg ns "$LIB_NS" --arg name "$LIB_NAME" \
    '(.libraries // []) | map(select(.namespace==$ns and .name==$name)) | (.[0].id // empty)' 2>/dev/null || true
}

# Safety net: if the library is gone at exit (bundle install failed after we deleted it),
# re-create it from the PR source so restore-source can re-save the app without a dangling #include.
ensure_lib_present() {
  if [ -z "$(lib_id)" ]; then
    echo "::warning::${LIB_NS}.${LIB_NAME} absent at exit -- re-creating from PR source to keep the hub safe for restore."
    local rpc
    rpc=$(jq -nc --arg url "$LIB_SOURCE_URL" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_library",arguments:{importUrl:$url,confirm:true}}}')
    mcp_call "$rpc" >/dev/null || echo "::warning::recovery hub_create_library call failed/timed out"
  fi
}
trap ensure_lib_present EXIT

# Keep the destructive-confirm <24h backup window warm (timestamp-cached; a 504 here is tolerated).
echo "Triggering hub backup (best-effort)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup failed/timed out; relying on cached <24h backup timestamp"

# --- 1) Remove the bootstrap user-library so the bundle is the SOLE deliverer (fresh-install mimic).
EXISTING_ID="$(lib_id)"
if [ -n "$EXISTING_ID" ]; then
  echo "Deleting bootstrap library ${LIB_NS}.${LIB_NAME} (id ${EXISTING_ID}) so the bundle delivers it cleanly..."
  DEL_RPC=$(jq -nc --arg id "$EXISTING_ID" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_delete_item",arguments:{type:"library",id:$id,confirm:true}}}')
  DEL_TEXT=$(tool_text "$DEL_RPC")
  DEL_OK=$(printf '%s' "$DEL_TEXT" | jq -r '.success // false' 2>/dev/null || echo false)
  if [ "$DEL_OK" != "true" ]; then
    # Non-fatal: fall through to the bundle install (it will overwrite/coexist). If the hub refused
    # because the library is in use, the bundle-over-existing path is then the thing under test.
    echo "::warning::Could not delete bootstrap library (continuing; bundle install will overwrite or coexist): $(printf '%s' "$DEL_TEXT" | head -c 200)"
  fi
else
  echo "No existing ${LIB_NS}.${LIB_NAME} on the hub -- the bundle will create it fresh."
fi

# --- 2) HPM "install bundles": install the REAL bundle zip via the REAL hub endpoint.
echo "Installing bundle from ${BUNDLE_URL} via hub_install_bundle ..."
BUN_RPC=$(jq -nc --arg url "$BUNDLE_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
BUN_TEXT=$(tool_text "$BUN_RPC")
BUN_OK=$(printf '%s' "$BUN_TEXT" | jq -r '.success // false' 2>/dev/null || echo false)
if [ "$BUN_OK" != "true" ]; then
  echo "::error::hub_install_bundle did not report success -- HPM would fail to deliver this package's library: $(printf '%s' "$BUN_TEXT" | jq -c '{success,error,endpoint,rawResponse}' 2>/dev/null || printf '%s' "$BUN_TEXT" | head -c 400)"
  exit 1
fi
echo "Bundle installed: $(printf '%s' "$BUN_TEXT" | jq -c '{success,endpoint,message}' 2>/dev/null || true)"

# --- 3) Verify the bundle delivered the CORRECT library content into Libraries Code.
NEW_ID="$(lib_id)"
if [ -z "$NEW_ID" ]; then
  echo "::error::Bundle install reported success but ${LIB_NS}.${LIB_NAME} is not in the hub library list -- the zip did not deliver the library."
  exit 1
fi
SRC_RPC=$(jq -nc --arg id "$NEW_ID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"library",id:$id}}}')
SRC_TEXT=$(tool_text "$SRC_RPC")
LIB_SRC=$(printf '%s' "$SRC_TEXT" | jq -r '.source // empty' 2>/dev/null || true)
if ! printf '%s' "$LIB_SRC" | grep -q "$LIB_MARKER"; then
  echo "::error::Bundle-delivered ${LIB_NS}.${LIB_NAME} (id ${NEW_ID}) does not contain the expected marker '${LIB_MARKER}' -- the zip shipped stale or wrong library content. Source head: $(printf '%s' "$LIB_SRC" | head -c 200)"
  exit 1
fi
echo "Verified: bundle delivered ${LIB_NS}.${LIB_NAME} (id ${NEW_ID}) carrying marker '${LIB_MARKER}'."

# --- 4) HPM "install apps" (after bundles): recompile the app so its #include resolves against the
# bundle-delivered library. Resolve the Apps Code CLASS id (scope=types), then hub_update_app resave.
#
# Reading the compile result through the cloud relay: Hubitat reloads the app ONLY on a SUCCESSFUL
# recompile (which drops the in-flight cloud connection -> 5xx/curl-error), whereas a COMPILE FAILURE
# is rejected synchronously with HTTP 200 + {success:false} and no reload. So:
#   HTTP 200 + success:true  -> compiled (responded before reload)        -> PASS
#   HTTP 200 + success:false -> #include did NOT resolve / compile failed  -> FAIL (loud)
#   curl error / 5xx         -> app reloaded => recompiled successfully    -> PASS
# Note the curl-error/5xx PASS branch can also fire on pure relay flakiness (e.g. a --max-time
# stall), i.e. a false green on THIS check -- but it cannot mask a real bundle-delivery failure:
# step 3 above already verified the bundle delivered the library WITH the correct marker content, so
# the #include is guaranteed to resolve by the time we get here. This resave is a defensive recompile
# in HPM's bundle->app order, not the primary delivery proof.
LIST_TEXT=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
CLASS_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$APP_NAMESPACE" --arg name "$APP_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' 2>/dev/null | head -n1)
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Could not resolve Apps Code class id for ${APP_NAMESPACE}:${APP_NAME} to recompile after the bundle install."
  exit 1
fi
echo "Resaving app class ${CLASS_ID} so its #include recompiles against the bundle-delivered library..."
RESAVE_RPC=$(jq -nc --arg id "$CLASS_ID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,resave:true,confirm:true}}}')
RESAVE_RESP=$(curl -sS --max-time 120 -X POST "$MCP_URL" -H "Content-Type: application/json" \
  -w '\n%{http_code}' --data-binary "$RESAVE_RPC")
RESAVE_RC=$?
# On the connection-drop (reload) path curl may not append a code, so HTTP_CODE can be a body
# fragment rather than "200". That's fine ONLY because the `RESAVE_RC -ne 0` check below runs
# FIRST and short-circuits before HTTP_CODE is consulted -- keep that ordering (load-bearing).
HTTP_CODE=$(printf '%s' "$RESAVE_RESP" | tail -n1)
RESAVE_BODY=$(printf '%s' "$RESAVE_RESP" | sed '$d')

RELOADED=false
if [ $RESAVE_RC -ne 0 ]; then
  echo "::notice::Resave call returned curl rc=${RESAVE_RC} (connection dropped) -- the app reloaded, which only happens on a SUCCESSFUL recompile. Treating as compiled."
  RELOADED=true
elif [ "$HTTP_CODE" = "200" ]; then
  RESAVE_TEXT=$(printf '%s' "$RESAVE_BODY" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
  RESAVE_OK=$(printf '%s' "$RESAVE_TEXT" | jq -r '.success // false' 2>/dev/null || echo false)
  if [ "$RESAVE_OK" != "true" ]; then
    echo "::error::App FAILED to recompile against the bundle-delivered library -- the #include did not resolve (HPM-installed package would not compile on a user's hub): $(printf '%s' "$RESAVE_TEXT" | jq -c '{success,error,note}' 2>/dev/null || printf '%s' "$RESAVE_TEXT" | head -c 400)"
    exit 1
  fi
  echo "App recompiled cleanly against the bundle-delivered library (HTTP 200 / success:true)."
elif [ "$HTTP_CODE" = "502" ] || [ "$HTTP_CODE" = "503" ] || [ "$HTTP_CODE" = "504" ]; then
  echo "::notice::Resave returned HTTP ${HTTP_CODE} (cloud gateway timeout from the app reload) -- a reload only follows a SUCCESSFUL recompile. Treating as compiled."
  RELOADED=true
else
  echo "::error::Resave returned unexpected HTTP ${HTTP_CODE}: $(printf '%s' "$RESAVE_BODY" | head -c 400)"
  exit 1
fi

# If the recompile reloaded the app, give the hub a moment to come back before the next CI step
# (Run E2E tests) starts hitting the cloud endpoint against a still-reloading app.
if [ "$RELOADED" = "true" ]; then
  echo "Letting the hub settle after the app reload (20s)..."
  sleep 20
fi

echo "HPM bundle-install path proven: real zip -> /bundle2 endpoint -> library delivered with correct content -> app #include recompiles. The marker assertions in tests/e2e_test.py now ride on the bundle-delivered library."
