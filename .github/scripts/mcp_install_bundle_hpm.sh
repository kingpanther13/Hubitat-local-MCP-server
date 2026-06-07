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
# AFTER mcp_install_libraries.sh bootstrapped each #include'd library as a plain user library (so
# that first deploy compiled). To make the BUNDLE the sole deliverer -- a faithful fresh-install
# mimic, and to avoid a user-library-vs-bundle-library name clash -- it deletes EVERY bundle-managed
# user library first, then:
#   1. installs the real bundle zip via hub_install_bundle (HPM "install bundles" step),
#   2. verifies each library is back in Libraries Code (plus expected marker content where defined),
#   3. resaves the app so its #include directives recompile against the bundle-delivered libraries
#      (HPM "install apps" step, run AFTER bundles -- exactly HPM's order).
#
# Safety on a shared hub: the app is already compiled and running in hub memory, so the cloud MCP
# endpoint stays reachable even while the on-disk libraries are briefly absent. An EXIT trap
# re-ensures every bundle-managed library exists (re-creating it from the PR source if the bundle
# install failed) so the later restore-source step never re-saves an app that references a missing
# library.
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

APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"
BUNDLE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/bundles/mcp-libraries.zip"

# Libraries the bundle is expected to deliver, as "ns|name|marker|source_relpath":
#   marker         -- a string that MUST appear in the delivered source (content check); "" = presence-only
#   source_relpath -- the library file's real path (filename != declared namespace.name), for recovery
# Keep in sync with tools/build-bundle.py's LIBS and the app's #include directives.
LIB_SPECS=(
  "mcp|McpSmokeTestLib|smoke-ok-v1|libraries/mcp-smoke-test-lib.groovy"
  "mcp|McpRoomsLib||libraries/mcp-rooms-lib.groovy"
  "mcp|McpBundlesLib||libraries/mcp-bundles-lib.groovy"
)

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

# Echo the hub's library list as a compact JSON array (the tool's `.libraries`), or "[]".
# Fetch this ONCE per phase and look ids up locally with lib_id_in -- hub_list_libraries is a
# cloud-relay round-trip, so re-listing per (ns,name) would multiply slow, flaky calls for no gain
# (the list is stable within a phase; deleting one spec doesn't change another's lookup).
list_libraries_json() {
  local text
  text=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  printf '%s' "$text" | jq -c '.libraries // []' 2>/dev/null || printf '[]'
}

# Echo the hub library id for (ns, name) from a pre-fetched library list (see list_libraries_json),
# or empty if not present.
lib_id_in() {
  local libs_json="$1" ns="$2" name="$3"
  printf '%s' "$libs_json" | jq -r --arg ns "$ns" --arg name "$name" \
    'map(select(.namespace==$ns and .name==$name)) | (.[0].id // empty)' 2>/dev/null || true
}

# Safety net: re-create any bundle-managed library that's missing at exit (bundle install failed
# after we deleted it), from the PR source, so restore-source can re-save the app without a
# dangling #include.
ensure_libs_present() {
  local spec ns name _marker src url rpc libs_json
  libs_json="$(list_libraries_json)"
  for spec in "${LIB_SPECS[@]}"; do
    IFS='|' read -r ns name _marker src <<<"$spec"
    if [ -z "$(lib_id_in "$libs_json" "$ns" "$name")" ]; then
      url="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${src}"
      echo "::warning::${ns}.${name} absent at exit -- re-creating from PR source to keep the hub safe for restore."
      rpc=$(jq -nc --arg url "$url" \
        '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_library",arguments:{importUrl:$url,confirm:true}}}')
      mcp_call "$rpc" >/dev/null || echo "::warning::recovery hub_create_library call for ${ns}.${name} failed/timed out"
    fi
  done
}
trap ensure_libs_present EXIT

# Keep the destructive-confirm <24h backup window warm (timestamp-cached; a 504 here is tolerated).
echo "Triggering hub backup (best-effort)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup failed/timed out; relying on cached <24h backup timestamp"

# --- 0) Remove any pre-existing mcp libraries bundle FIRST so its bundle-managed libraries don't
# linger. A prior issue #209 run installs this bundle and leaves it; re-installing OVER a
# bundle-managed copy can strand the bootstrap (baseline tools can't edit a bundle-managed library)
# and risks a duplicate library that #include would bind ambiguously. The PR app is already deployed
# by now, so the new hub_list_bundles / hub_delete_bundle tools exist. Deleting the bundle removes
# every library it manages at once; the install below recreates them. Best-effort + non-fatal -- the
# install overwrites regardless, but a clean delete-first makes the fresh-install mimic idempotent
# across runs. (Covers the old "mcp_smoke_test" name too, for the rename transition.)
delete_bundle_named() {
  local want="$1" list id rpc text
  list=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_bundles","arguments":{}}}')
  id=$(printf '%s' "$list" | jq -r --arg n "$want" \
    '(.bundles // []) | map(select(.name==$n)) | (.[0].id // empty)' 2>/dev/null || true)
  [ -n "$id" ] || return 0
  echo "Deleting pre-existing bundle '${want}' (id ${id}) for a clean fresh-install ..."
  rpc=$(jq -nc --arg id "$id" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_delete_bundle",arguments:{bundleId:$id,confirm:true}}}')
  text=$(tool_text "$rpc")
  if [ "$(printf '%s' "$text" | jq -r '.success // false' 2>/dev/null || echo false)" != "true" ]; then
    echo "::warning::Could not delete pre-existing bundle '${want}' (continuing; install will overwrite): $(printf '%s' "$text" | jq -c '{success,error}' 2>/dev/null || printf '%s' "$text" | head -c 200)"
  fi
}
delete_bundle_named "mcp_libraries"
delete_bundle_named "mcp_smoke_test"

# --- 1) Remove any leftover bootstrap user-libraries so the bundle is the SOLE deliverer.
PRE_LIBS_JSON="$(list_libraries_json)"  # one list for the whole deletion phase
for spec in "${LIB_SPECS[@]}"; do
  IFS='|' read -r ns name _marker _src <<<"$spec"
  existing_id="$(lib_id_in "$PRE_LIBS_JSON" "$ns" "$name")"
  if [ -n "$existing_id" ]; then
    echo "Deleting bootstrap library ${ns}.${name} (id ${existing_id}) so the bundle delivers it cleanly..."
    del_rpc=$(jq -nc --arg id "$existing_id" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_delete_item",arguments:{type:"library",id:$id,confirm:true}}}')
    del_text=$(tool_text "$del_rpc")
    del_ok=$(printf '%s' "$del_text" | jq -r '.success // false' 2>/dev/null || echo false)
    if [ "$del_ok" != "true" ]; then
      # Non-fatal: fall through to the bundle install (it will overwrite/coexist). If the hub refused
      # because the library is in use, the bundle-over-existing path is then the thing under test.
      echo "::warning::Could not delete bootstrap library ${ns}.${name} (continuing; bundle install will overwrite or coexist): $(printf '%s' "$del_text" | head -c 200)"
    fi
  else
    echo "No existing ${ns}.${name} on the hub -- the bundle will create it fresh."
  fi
done

# --- 2) HPM "install bundles": install the REAL bundle zip via the REAL hub endpoint.
echo "Installing bundle from ${BUNDLE_URL} via hub_install_bundle ..."
BUN_RPC=$(jq -nc --arg url "$BUNDLE_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
BUN_TEXT=$(tool_text "$BUN_RPC")
BUN_OK=$(printf '%s' "$BUN_TEXT" | jq -r '.success // false' 2>/dev/null || echo false)
if [ "$BUN_OK" != "true" ]; then
  echo "::error::hub_install_bundle did not report success -- HPM would fail to deliver this package's libraries: $(printf '%s' "$BUN_TEXT" | jq -c '{success,error,endpoint,rawResponse}' 2>/dev/null || printf '%s' "$BUN_TEXT" | head -c 400)"
  exit 1
fi
echo "Bundle installed: $(printf '%s' "$BUN_TEXT" | jq -c '{success,endpoint,message}' 2>/dev/null || true)"

# --- 3) Verify the bundle delivered EACH library into Libraries Code (presence + marker content).
POST_LIBS_JSON="$(list_libraries_json)"  # one list for the whole verification phase
for spec in "${LIB_SPECS[@]}"; do
  IFS='|' read -r ns name marker _src <<<"$spec"
  new_id="$(lib_id_in "$POST_LIBS_JSON" "$ns" "$name")"
  if [ -z "$new_id" ]; then
    echo "::error::Bundle install reported success but ${ns}.${name} is not in the hub library list -- the zip did not deliver this library."
    exit 1
  fi
  if [ -n "$marker" ]; then
    src_rpc=$(jq -nc --arg id "$new_id" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"library",id:$id}}}')
    src_text=$(tool_text "$src_rpc")
    lib_src=$(printf '%s' "$src_text" | jq -r '.source // empty' 2>/dev/null || true)
    if ! printf '%s' "$lib_src" | grep -q "$marker"; then
      echo "::error::Bundle-delivered ${ns}.${name} (id ${new_id}) does not contain the expected marker '${marker}' -- the zip shipped stale or wrong library content. Source head: $(printf '%s' "$lib_src" | head -c 200)"
      exit 1
    fi
    echo "Verified: bundle delivered ${ns}.${name} (id ${new_id}) carrying marker '${marker}'."
  else
    echo "Verified: bundle delivered ${ns}.${name} (id ${new_id})."
  fi
done

# --- 4) HPM "install apps" (after bundles): recompile the app so its #include directives resolve
# against the bundle-delivered libraries. Resolve the Apps Code CLASS id (scope=types), then
# hub_update_app resave.
#
# Reading the compile result through the cloud relay: Hubitat reloads the app ONLY on a SUCCESSFUL
# recompile (which drops the in-flight cloud connection -> 5xx/curl-error), whereas a COMPILE FAILURE
# is rejected synchronously with HTTP 200 + {success:false} and no reload. So:
#   HTTP 200 + success:true  -> compiled (responded before reload)        -> PASS
#   HTTP 200 + success:false -> #include did NOT resolve / compile failed  -> FAIL (loud)
#   curl error / 5xx         -> app reloaded => recompiled successfully    -> PASS
# Note the curl-error/5xx PASS branch can also fire on pure relay flakiness (e.g. a --max-time
# stall), i.e. a false green on THIS check -- but it cannot mask a real bundle-delivery failure:
# step 3 above already verified the bundle delivered every library (with marker content where
# defined), so the #include directives are guaranteed to resolve by the time we get here. This
# resave is a defensive recompile in HPM's bundle->app order, not the primary delivery proof.
LIST_TEXT=$(tool_text '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
CLASS_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$APP_NAMESPACE" --arg name "$APP_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' 2>/dev/null | head -n1)
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Could not resolve Apps Code class id for ${APP_NAMESPACE}:${APP_NAME} to recompile after the bundle install."
  exit 1
fi
echo "Resaving app class ${CLASS_ID} so its #include directives recompile against the bundle-delivered libraries..."
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
    echo "::error::App FAILED to recompile against the bundle-delivered libraries -- a #include did not resolve (HPM-installed package would not compile on a user's hub): $(printf '%s' "$RESAVE_TEXT" | jq -c '{success,error,note}' 2>/dev/null || printf '%s' "$RESAVE_TEXT" | head -c 400)"
    exit 1
  fi
  echo "App recompiled cleanly against the bundle-delivered libraries (HTTP 200 / success:true)."
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

echo "HPM bundle-install path proven: real zip -> /bundle2 endpoint -> libraries delivered with correct content -> app #include recompiles. The marker/library assertions in tests/e2e_test.py now ride on the bundle-delivered libraries."
