#!/usr/bin/env bash
# Install THIS PR's package onto the live test hub via the WATCHDOG MCP endpoint,
# in Hubitat Package Manager's exact order: LIBRARIES first, then the BUNDLE (if
# the package ships one), then the APP last.
#
# This single script REPLACES the four cloud-relay deploy scripts it consolidates:
#   - mcp_install_libraries.sh   (install/update every #included library)
#   - mcp_deploy_source.sh       (push the app source via hub_update_app importUrl)
#   - mcp_verify_deploy.sh       (byte-compare the landed app source)
#   - mcp_install_bundle_hpm.sh  (install the package bundle the HPM way)
#
# Why one script can do all four cleanly: the WATCHDOG endpoint ($WATCHDOG_URL,
# from secret WATCHDOG_MCP_URL) is a SECOND MCP driver of the same loopback
# code-install plumbing. Its advantage over self-deploying through the primary
# endpoint is that the watchdog is a DIFFERENT running app, so installing the MCP
# package does NOT reload the app answering the request -- it never bricks itself
# and stays queryable throughout. Small/fast calls (the libraries, the CURRENT
# small smoke-test bundle, get_source, get_info) return the hub's VERBATIM
# compile/save result synchronously within the ~10s cloud relay window (the bundle
# is fast only because mcp-smoke-test.zip is tiny -- a large bundle would need the
# app's dropped-response tolerance too), so VERIFY for those is simply: parse the tool
# response and fail loudly -- surfacing the hub's verbatim errorMessage -- if
# success != true. The one exception is the ~1.6MB APP deploy: it runs ~200s
# hub-side, so the relay drops its response even though the deploy proceeds, exactly
# like the live server's self-deploy. For that case we tolerate the dropped response
# and poll hub_get_source until the new source lands, consulting lastSelfDeploy only
# as a secondary source for the hub's verbatim compile error if it never does.
#
# Install order (HPM's order; #include deps must exist before the app compiles):
#   1. LIBRARIES  hub_update_library / hub_create_library  importUrl=<lib raw url>  confirm:true
#   2. BUNDLE     hub_install_bundle                        importUrl=<bundle zip url> confirm:true  (only if present)
#   3. APP        hub_update_app  appId=<MCP class id>      importUrl=<app raw url>  confirm:true
#
# Usage: mcp_watchdog_deploy.sh [path/to/hubitat-mcp-server.groovy]
# Env:   WATCHDOG_URL          -- full watchdog MCP endpoint URL with access_token (secret WATCHDOG_MCP_URL)
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA
#        RUNNER_TEMP           -- GHA temp dir; falls back to /tmp
#
# The app's importUrl points the hub at the PR branch's raw source so the ~1.5MB
# blob never has to cross the MCP transport as an inline argument (importUrl
# deploy, issue #228); each library/bundle importUrl is built from PR_RAW_BASE +
# the resolved head SHA + the repo-relative path.
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full watchdog MCP endpoint URL with access_token; from secret WATCHDOG_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (https://raw.githubusercontent.com/<owner>/<repo>)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (40-hex PR head SHA)}"

APP_FILE="${1:-hubitat-mcp-server.groovy}"
if [ ! -f "$APP_FILE" ]; then
  echo "::error::App file not found: $APP_FILE (wrong working directory or bad path arg). Refusing to silently report 'nothing to install'."
  exit 1
fi
LIB_DIR="$(dirname "$APP_FILE")/libraries"

# Pulled from the parent app's definition() block -- keep in sync if the parent
# ever changes its namespace/name (HPM tracks it, so it shouldn't).
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

# The package's bundle .zip(s), DERIVED from packageManifest.json -- never hardcoded. The bundle
# name changes with the #209 modularization, and a hardcoded name would silently stop proving HPM
# delivery once it drifts. Each manifest bundles[].location is a full raw URL; strip the
# host/owner/repo/ref prefix to the repo-relative path. The deploy installs every manifest bundle and
# FAILS if the manifest declares one whose file is missing from the checkout (a real drift, not a
# "no bundle" no-op).
MANIFEST_FILE="$(dirname "$APP_FILE")/packageManifest.json"
BUNDLE_RELS=""
if [ -f "$MANIFEST_FILE" ]; then
  BUNDLE_RELS=$(jq -r '.bundles[]?.location // empty' "$MANIFEST_FILE" \
    | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
fi

# ---------------------------------------------------------------------------
# mcp_call -- POST one JSON-RPC body to the watchdog endpoint and echo the raw
# HTTP response body. `|| true` is
# NOT used here so a transient curl failure surfaces via the empty-parse guards
# in the helpers below; --data-binary streams the body without arg/newline
# mangling and dodges ARG_MAX for the (small) importUrl bodies.
mcp_call() {
  curl -sS --max-time 120 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Echo the inner MCP tool-result text (the tool's own JSON payload) for an RPC
# body, or empty. `|| true` so a transient relay failure can't bare-exit under
# `set -e` before the caller inspects the (empty) result.
call_tool() {
  local resp
  resp=$(mcp_call "$1" || true)
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

# Like call_tool but retries an IDEMPOTENT read up to RPC_ATTEMPTS times on an empty (relay-dropped)
# result, mirroring the 5x wrapper in mcp_arm_watchdog.sh / mcp_disarm_watchdog.sh. Use ONLY for reads
# (the class-id lookup, the source-length probe); WRITES stay single-shot to match the prior scripts.
RPC_ATTEMPTS="${RPC_ATTEMPTS:-5}"
RPC_RETRY_SLEEP="${RPC_RETRY_SLEEP:-5}"
call_tool_retry() {
  local attempt=1 out
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    out=$(call_tool "$1")
    if [ -n "$out" ]; then printf '%s' "$out"; return 0; fi
    attempt=$((attempt + 1))
    [ "$attempt" -le "$RPC_ATTEMPTS" ] && sleep "$RPC_RETRY_SLEEP"
  done
  return 0  # echo nothing; the caller's empty-check is the guard
}

# Echo "true"/"false" for a tool result's .success field (the .success projection of a tool result).
ok_of() { printf '%s' "$1" | jq -r '.success // false' 2>/dev/null || echo false; }

# Echo the hub's VERBATIM error/note text from a tool result, for loud surfacing.
err_of() { printf '%s' "$1" | jq -r '.error // .message // .errorMessage // empty' 2>/dev/null || true; }

# Resolve the source-bearing Apps Code CLASS id for (namespace, name) via
# hub_list_apps scope=types. The class id (NOT the running instance id) is what
# hub_update_app's appId must reference. Echoes the id; exits 1 loudly if the
# watchdog response is unusable or the class isn't found. Retries the read so a
# single relay 504 doesn't hard-fail the install after libraries+bundle landed.
resolve_class_id() {
  local list_text class_id
  list_text=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
  if [ -z "$list_text" ]; then
    echo "::error::hub_list_apps (scope=types) returned no MCP content from the watchdog endpoint -- cannot resolve the Apps Code class id for ${APP_NAMESPACE}:${APP_NAME}." >&2
    exit 1
  fi
  # /hub2/userAppTypes is an array of {id,name,namespace,...}. Match BOTH namespace
  # AND name so a fork reusing either field isn't grabbed. select(...) yields
  # nothing on a miss -- the empty-check below is the guard.
  class_id=$(printf '%s' "$list_text" | jq -r \
    --arg ns "$APP_NAMESPACE" --arg name "$APP_NAME" \
    'first(.apps[]? | select(.namespace == $ns and .name == $name) | .id) // empty' 2>/dev/null || true)
  if [ -z "$class_id" ] || [ "$class_id" = "null" ]; then
    echo "::error::Apps Code class for ${APP_NAMESPACE}:${APP_NAME} not found via hub_list_apps (scope=types) on the watchdog endpoint." >&2
    echo "DEBUG first 5 entries: $(printf '%s' "$list_text" | jq -c '.apps[0:5] // []' 2>/dev/null || printf '%s' "$list_text" | head -c 300)" >&2
    exit 1
  fi
  printf '%s' "$class_id"
}

# Echo the app code class's current source character length (totalLength) for a
# given class id, or empty if it can't be read. Used to detect when the importUrl
# install's new source has landed. A 1-char read is enough -- totalLength comes
# back in the first chunk's metadata. noSave:true is CRITICAL: without it,
# hub_get_source auto-saves the full live source to the dead-man restore cache
# (mcp-source-app-<id>.groovy), overwriting cached MAIN with the just-deployed PR
# code mid-run -- which would make the disarm "restore" PR code. Retries the read.
app_total_length() {
  local id="$1" rpc text
  rpc=$(jq -nc --arg id "$id" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')
  text=$(call_tool_retry "$rpc")
  printf '%s' "$text" | jq -r '.totalLength // empty' 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# The watchdog's destructive WRITE tools require ONLY confirm:true -- unlike the main server it DROPS
# the 24h-backup requirement (see the watchdog's SECURITY banner). This hub_create_backup call is a
# best-effort DEFENSIVE snapshot, NOT a gate prerequisite, so a failure here is tolerated.
echo "Triggering a defensive hub backup via the watchdog (best-effort; not a write-gate prerequisite)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup call failed/timed out; continuing (the backup is a defensive snapshot, not required for writes)"

# ===========================================================================
# 1) LIBRARIES FIRST -- install/update every library the app #includes so the
#    `#include` directives resolve when the app compiles (issue #209). Matches
#    each #include to its library file by the (namespace, name) declared in the
#    library() block (NOT by filename, mirroring how the hub resolves #includes).
# ===========================================================================
mapfile -t INCLUDES < <(
  grep -hoE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' "$APP_FILE" \
    | sed -E 's/^[[:space:]]*#include[[:space:]]+//' | sort -u || true
)

if [ "${#INCLUDES[@]}" -eq 0 ]; then
  echo "No #include directives in $APP_FILE -- no libraries to install."
else
  echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]}"

  # Snapshot existing hub libraries once, to choose update-vs-create per library.
  LIB_LIST_TEXT=$(call_tool '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  if [ -z "$LIB_LIST_TEXT" ]; then
    echo "::error::hub_list_libraries returned no MCP content from the watchdog endpoint -- cannot plan create-vs-update."
    exit 1
  fi

  for tok in "${INCLUDES[@]}"; do
    ns="${tok%%.*}"
    name="${tok##*.}"

    # Find the local library file whose library() declares this (namespace, name).
    libfile=""
    for f in "$LIB_DIR"/*.groovy; do
      [ -f "$f" ] || continue
      if grep -qE 'library[[:space:]]*\(' "$f" \
        && grep -qE "name:[[:space:]]*[\"']${name}[\"']" "$f" \
        && grep -qE "namespace:[[:space:]]*[\"']${ns}[\"']" "$f"; then
        libfile="$f"
        break
      fi
    done
    if [ -z "$libfile" ]; then
      echo "::error::#include ${tok} has no matching library file in ${LIB_DIR}"
      exit 1
    fi
    rel="${libfile#./}"
    url="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${rel}"

    existing_id=$(printf '%s' "$LIB_LIST_TEXT" | jq -r --arg ns "$ns" --arg name "$name" \
      '(.libraries // []) | map(select(.namespace==$ns and .name==$name)) | (.[0].id // empty)' 2>/dev/null || true)

    if [ -n "$existing_id" ] && [ "$existing_id" != "null" ]; then
      echo "Updating existing library ${tok} (id ${existing_id}) from ${url} ..."
      UPD_RPC=$(jq -nc --arg id "$existing_id" --arg url "$url" \
        '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_library",arguments:{libraryId:$id,importUrl:$url,confirm:true}}}')
      UPD_TEXT=$(call_tool "$UPD_RPC")
      if [ "$(ok_of "$UPD_TEXT")" = "true" ]; then
        echo "Library ${tok} updated."
        continue
      fi
      # Update failed. The existing copy may be BUNDLE-MANAGED (a prior bundle run
      # delivered it) and non-editable in place. Self-heal so a previous bundle run
      # can't strand this step on a later PR: delete the existing copy, recreate fresh.
      echo "::warning::hub_update_library failed for ${tok}; deleting + recreating (existing copy may be bundle-managed): $(printf '%s' "$UPD_TEXT" | jq -c '{success,error}' 2>/dev/null || printf '%s' "$UPD_TEXT" | head -c 200)"
      DEL_RPC=$(jq -nc --arg id "$existing_id" \
        '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_delete_item",arguments:{type:"library",id:$id,confirm:true}}}')
      DEL_TEXT=$(call_tool "$DEL_RPC")
      if [ "$(ok_of "$DEL_TEXT")" != "true" ]; then
        echo "::error::Could not update OR delete existing library ${tok} (id ${existing_id}) -- it appears locked (e.g. bundle-managed) and this script cannot reconcile it. Remove it manually on the hub (FOR DEVELOPERS > Libraries code, or the bundle in Bundle Manager): $(printf '%s' "$DEL_TEXT" | jq -c '{success,error}' 2>/dev/null || printf '%s' "$DEL_TEXT" | head -c 300)"
        exit 1
      fi
      echo "Deleted stale ${tok}; will recreate from source."
    else
      echo "Creating library ${tok} from ${url} ..."
    fi

    CRT_RPC=$(jq -nc --arg url "$url" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_library",arguments:{importUrl:$url,confirm:true}}}')
    CRT_TEXT=$(call_tool "$CRT_RPC")
    if [ "$(ok_of "$CRT_TEXT")" != "true" ]; then
      echo "::error::Library ${tok} create failed (transient relay error or a hub-side rejection). Hub verbatim: $(err_of "$CRT_TEXT") -- full: $(printf '%s' "$CRT_TEXT" | jq -c '{success,error,note,verified}' 2>/dev/null || printf '%s' "$CRT_TEXT" | head -c 400)"
      exit 1
    fi
    echo "Library ${tok} OK."
  done

  echo "All ${#INCLUDES[@]} PR library(ies) installed/updated -- the app's #include directives will resolve on deploy."
fi

# ===========================================================================
# 2) BUNDLE(S) -- install every bundle the manifest declares, the HPM way (HPM
#    "install bundles", run BEFORE "install apps"). hub_install_bundle has the
#    hub fetch + unpack the zip into Libraries/Apps/Drivers Code. A manifest with
#    no bundles is a clean no-op; a manifest bundle whose file is missing from the
#    checkout is a HARD failure (manifest/repo drift -- CI must not pass green
#    while silently failing to prove HPM delivery).
# ===========================================================================
if [ -z "$BUNDLE_RELS" ]; then
  echo "packageManifest.json declares no bundles -- skipping the bundle step (clean no-op)."
else
  while IFS= read -r BUNDLE_REL; do
    [ -z "$BUNDLE_REL" ] && continue
    BUNDLE_PATH="$(dirname "$APP_FILE")/${BUNDLE_REL}"
    if [ ! -f "$BUNDLE_PATH" ]; then
      echo "::error::packageManifest.json declares bundle '${BUNDLE_REL}' but it is not in the checkout ($BUNDLE_PATH). Manifest/repo drift -- CI cannot prove HPM bundle delivery. Failing instead of passing as 'no bundle'."
      exit 1
    fi
    BUNDLE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${BUNDLE_REL}"
    echo "Installing package bundle '${BUNDLE_REL}' from ${BUNDLE_URL} via hub_install_bundle ..."
    BUN_RPC=$(jq -nc --arg url "$BUNDLE_URL" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
    BUN_TEXT=$(call_tool "$BUN_RPC")
    if [ "$(ok_of "$BUN_TEXT")" != "true" ]; then
      echo "::error::hub_install_bundle did not report success for '${BUNDLE_REL}' -- HPM would fail to deliver this package's bundle. Hub verbatim: $(err_of "$BUN_TEXT") -- full: $(printf '%s' "$BUN_TEXT" | jq -c '{success,error,endpoint,rawResponse}' 2>/dev/null || printf '%s' "$BUN_TEXT" | head -c 400)"
      exit 1
    fi
    echo "Bundle '${BUNDLE_REL}' installed: $(printf '%s' "$BUN_TEXT" | jq -c '{success,endpoint,message}' 2>/dev/null || true)"
  done <<< "$BUNDLE_RELS"
fi

# ===========================================================================
# 3) APP LAST -- deploy the app via hub_update_app importUrl (the hub fetches the
#    ~1.5MB raw source itself; issue #228). A clean compile that returns within the
#    ~10s relay window comes back success:true; a rejected save comes back
#    success:false WITH the hub's verbatim errorMessage. But the ~1.6MB app deploy
#    usually runs ~200s hub-side and the relay drops its response -- so when the
#    response is empty we tolerate it and POLL hub_get_source until the saved source
#    advances to this PR's length (lastSelfDeploy is a secondary compile-error source).
#    VERIFY = the app's code version (source totalLength) advanced to this PR's length.
# ===========================================================================
CLASS_ID="$(resolve_class_id)"
echo "Resolved Apps Code class id for ${APP_NAMESPACE}:${APP_NAME}: ${CLASS_ID}"

APP_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/$(basename "$APP_FILE")"
NEW_BYTES=$(wc -c < "$APP_FILE")
# The hub's totalLength is a CHARACTER count (Groovy String.length() = UTF-16 code units), not bytes.
# wc -m gives codepoints, which equals UTF-16 units for all BMP characters (everything but rare non-BMP
# like emoji). NEW_CHARS is LOAD-BEARING: in the dropped-response poll below there is no synchronous
# success to fall back on, so the length transition to NEW_CHARS (plus a fresh lastSelfDeploy) IS the
# landing proof. An all-BMP source is required for the exact-equality match (the repo source is ASCII).
NEW_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$APP_FILE" | tr -d '[:space:]')

# Baseline the live app source length BEFORE the deploy, so the post-deploy check can confirm the new
# source actually landed (totalLength reached this PR's length) rather than trusting success:true. A read
# failure here only relaxes that one assertion; it never turns a real failure into a pass.
PRE_LEN="$(app_total_length "$CLASS_ID")"
echo "Pre-deploy app source totalLength baseline: ${PRE_LEN:-<unreadable>}"

echo "Deploying app class ${CLASS_ID} via importUrl (hub fetches ${NEW_BYTES} bytes from ${APP_URL}) through the watchdog endpoint..."
DEPLOY_RPC=$(jq -nc --arg id "$CLASS_ID" --arg url "$APP_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,confirm:true}}}')
DEPLOY_TEXT=$(call_tool "$DEPLOY_RPC")

if [ -z "$DEPLOY_TEXT" ]; then
  # The ~1.6MB importUrl deploy (hub-side fetch + loopback POST + recompile, ~200s) outlives the cloud
  # relay's ~10s response window, so the watchdog's synchronous result is lost in transit even though the
  # deploy proceeds hub-side -- the SAME situation the live MCP server's self-deploy hits. The proven
  # recovery (run 27105537027) is to tolerate the dropped response and POLL hub_get_source until the saved
  # source length is this PR's length. If the PR doesn't change the server (PRE_LEN already == NEW_CHARS),
  # the source is already at that length -> a no-op deploy, treated as landed (the hub holds the right
  # code either way). lastSelfDeploy is consulted only as a SECONDARY source for the hub's verbatim
  # compile error if the source never reaches this PR's length.
  WAIT_S=420
  echo "::notice::No deploy response within the cloud relay window (expected for the ~1.6MB importUrl deploy). Polling hub_get_source for up to ${WAIT_S}s until the source is this PR's length (${NEW_CHARS})..."
  LANDED=""
  POLL_DEADLINE=$(( $(date +%s) + WAIT_S ))
  while [ "$(date +%s)" -lt "$POLL_DEADLINE" ]; do
    sleep 15
    CUR_LEN="$(app_total_length "$CLASS_ID")"
    if [ -z "$CUR_LEN" ] || [ "$CUR_LEN" = "0" ]; then
      echo "  ...hub_get_source not readable yet (watchdog busy fetching/recompiling); retrying..."
      continue
    fi
    if [ "$CUR_LEN" = "$NEW_CHARS" ]; then
      if [ "$PRE_LEN" = "$NEW_CHARS" ]; then
        echo "App source is already this PR's length (${CUR_LEN}) -- a no-op deploy (the PR doesn't change the server, or it was already deployed). Treating as landed."
      else
        echo "Source landed: app class ${CLASS_ID} totalLength ${PRE_LEN:-?} -> ${CUR_LEN} (this PR's ${NEW_CHARS})."
      fi
      LANDED="yes"; break
    fi
    if [ -n "$PRE_LEN" ] && [ "$CUR_LEN" != "$PRE_LEN" ]; then
      echo "  ...source changed (${PRE_LEN} -> ${CUR_LEN}) but not yet this PR's length (${NEW_CHARS}); recompile in progress, retrying..."
    else
      echo "  ...source still at baseline (${CUR_LEN}); deploy not landed yet, retrying..."
    fi
  done
  if [ -z "$LANDED" ]; then
    # The saved source never reached this PR's length. Pull lastSelfDeploy as a SECONDARY source for the
    # hub's verbatim compile error (a recent failure for this class is the likely cause).
    FINAL_LEN="$(app_total_length "$CLASS_ID")"
    INFO_TEXT="$(call_tool '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}')"
    LSD_APP=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.appId // empty' 2>/dev/null || true)
    LSD_OK=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
    LSD_ERR=$(printf '%s' "$INFO_TEXT" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
    if [ "$LSD_APP" = "$CLASS_ID" ] && [ "$LSD_OK" = "false" ] && [ -n "$LSD_ERR" ] && [ "$LSD_ERR" != "null" ]; then
      echo "::error::App deploy did not land (source totalLength ${FINAL_LEN:-<unreadable>}, expected ${NEW_CHARS}). Hub REJECTED it -- verbatim compile error (from lastSelfDeploy): ${LSD_ERR}"
    else
      echo "::error::App deploy did not land within ${WAIT_S}s: source totalLength ${FINAL_LEN:-<unreadable>} never reached this PR's length ${NEW_CHARS} (baseline ${PRE_LEN:-<unknown>}). The deploy may still be compiling, or the loopback POST / source fetch failed -- re-run; if it persists, check the watchdog app logs."
    fi
    exit 1
  fi
  echo "Deploy confirmed by the landed-source poll (the relay dropped the response; the new source is live on the hub)."
  # Synthesize a success result so the downstream no-op/version verification runs and re-confirms.
  DEPLOY_TEXT='{"success":true,"recovered":true}'
fi

if [ "$(ok_of "$DEPLOY_TEXT")" != "true" ]; then
  # The hub REJECTED the save/compile. The watchdog relayed the hub's VERBATIM
  # errorMessage synchronously -- surface it; do not guess the cause.
  HUB_ERR="$(err_of "$DEPLOY_TEXT")"
  HUB_NOTE=$(printf '%s' "$DEPLOY_TEXT" | jq -r '.note // empty' 2>/dev/null || true)
  if [ -n "$HUB_ERR" ]; then
    echo "::error::Hub REJECTED the app deploy (hub_update_app success=false). Hub verbatim errorMessage: ${HUB_ERR}${HUB_NOTE:+ -- ${HUB_NOTE}}"
  else
    echo "::error::Hub rejected the app deploy (hub_update_app success=false) but returned no error text. Full tool response below."
  fi
  echo "Tool response: $(printf '%s' "$DEPLOY_TEXT" | head -c 2000)"
  exit 1
fi

# Confirm the source actually landed (totalLength reached this PR's length), so a stale-but-"ok" response
# can't pass the gate. For the recovered (dropped-response) path the poll already proved this; the
# re-read is cheap and consistent. Only enforced when both lengths are readable.
POST_LEN="$(app_total_length "$CLASS_ID")"
echo "Post-deploy app source totalLength: ${POST_LEN:-<unreadable>} (baseline ${PRE_LEN:-<unreadable>}, this PR ${NEW_CHARS})"
if [ -n "$POST_LEN" ] && [ "$POST_LEN" != "0" ]; then
  if [ "$POST_LEN" = "$NEW_CHARS" ]; then
    if [ "$PRE_LEN" = "$NEW_CHARS" ]; then
      echo "::notice::App source totalLength is this PR's length (${POST_LEN}) and was already at it -- a legitimate no-op (the PR doesn't change the server, or it was already deployed). Treating as deployed."
    else
      echo "App version advanced: source totalLength ${PRE_LEN} -> ${POST_LEN} (this PR's length)."
    fi
  elif [ -n "$PRE_LEN" ] && [ "$POST_LEN" = "$PRE_LEN" ]; then
    echo "::error::hub_update_app reported success=true but the app's source did NOT change: totalLength is still ${POST_LEN} (baseline ${PRE_LEN}), and it does not match this PR's length (${NEW_CHARS}). The new source did not take effect on the hub."
    exit 1
  else
    echo "::warning::Post-deploy source totalLength ${POST_LEN} is neither the baseline (${PRE_LEN:-?}) nor this PR's length (${NEW_CHARS}); the deploy may have landed a different revision. Relying on hub_update_app success=true."
  fi
else
  echo "::warning::Could not read the post-deploy source length to confirm the source landed; relying on hub_update_app success=true alone (the watchdog returned the hub's synchronous compile result)."
fi

echo "Deploy succeeded via watchdog: PR app source (${NEW_BYTES} bytes) compiled and is live on the hub at class ${CLASS_ID}."
echo "WATCHDOG_DEPLOY_OK classId=${CLASS_ID} libraries=${#INCLUDES[@]} appUrl=${APP_URL}"
