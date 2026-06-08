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
# and stays queryable throughout. Small/fast calls (the libraries, the package's
# libraries bundle, get_source, get_info) return the hub's VERBATIM compile/save
# result synchronously within the ~10s cloud relay window (a bundle install is the hub
# fetching + unpacking a small zip, well within the window), so VERIFY for those is
# simply: parse the tool
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
# Shared watchdog JSON-RPC + deploy-confirmation helpers (mcp_call / call_tool [surfaces JSON-RPC
# errors] / call_tool_retry / ok_of / err_of / resolve_class_id / app_total_length [noSave probe] /
# deploy_app_via_watchdog [confirms via fresh lastSelfDeploy]). One source of truth so the deploy, arm,
# and self-update scripts cannot drift.
source "$(dirname "$0")/mcp_watchdog_lib.sh"

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

  # Snapshot existing hub libraries once, to choose update-vs-create per library. Use the RETRYING
  # read: this is an idempotent list, and a single cloud-relay drop here (the hub is briefly busy right
  # after the best-effort backup above + the arm's canonical-main bundle refresh) must not hard-fail the
  # whole deploy. (Retry is safe for reads; the create/update/delete WRITES below stay single-shot.)
  LIB_LIST_TEXT=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  if [ -z "$LIB_LIST_TEXT" ]; then
    echo "::error::hub_list_libraries returned no MCP content from the watchdog endpoint after retries -- cannot plan create-vs-update. Re-run; if it persists, check the watchdog app logs."
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
CLASS_ID="$(resolve_class_id "$APP_NAMESPACE" "$APP_NAME")"
echo "Resolved Apps Code class id for ${APP_NAMESPACE}:${APP_NAME}: ${CLASS_ID}"

APP_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/$(basename "$APP_FILE")"
NEW_BYTES=$(wc -c < "$APP_FILE")
# The hub's totalLength is a CHARACTER count (Groovy String.length() = UTF-16 code units). wc -m gives
# codepoints == UTF-16 units for all-BMP source (the repo is ASCII), used as a belt-and-suspenders
# "right source landed" cross-check below.
NEW_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$APP_FILE" | tr -d '[:space:]')

# Mark the hub DIRTY for the canonical-main refresh: we are about to deploy PR code over the app, so the
# recorded "hub is on canonical main" SHA marker is no longer valid. Clearing it means that if THIS run's
# disarm later fails to restore main, the NEXT run's arm refreshes canonical main instead of skipping on a
# stale marker (closing the contaminated-hub-after-failed-restore gap). The disarm rewrites the marker
# only after a CONFIRMED restore. Best-effort -- the marker is a skip optimization, not a safety gate.
echo "Clearing the canonical-main SHA marker (about to deploy PR code -- hub no longer guaranteed canonical main)..."
call_tool "$(jq -nc '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"mcp-main-deployed-sha.txt",content:"",confirm:true}}}')" >/dev/null || true

# Deploy the PR's app and CONFIRM it landed via a FRESH lastSelfDeploy success (shared helper). This is
# the only confirmation that survives the relay-dropped 1.6MB response AND a same-length source change --
# a stale/wrong source can never false-green as "landed". deploy_app_via_watchdog exits 1 (with the hub's
# verbatim compile error) on a rejection or if it can't confirm.
deploy_app_via_watchdog "$CLASS_ID" "$APP_URL" "install PR app"

# Belt-and-suspenders: the fresh lastSelfDeploy proves a deploy of THIS importUrl succeeded; cross-check
# the live source length is this PR's length (the RIGHT revision). Skipped if the length read fails.
POST_LEN="$(app_total_length "$CLASS_ID")"
if [ -n "$POST_LEN" ] && [ "$POST_LEN" != "0" ] && [ "$POST_LEN" != "$NEW_CHARS" ]; then
  echo "::error::App deploy confirmed by lastSelfDeploy, but the live source totalLength (${POST_LEN}) does not match this PR's length (${NEW_CHARS}) -- a different revision may be live. Investigate."
  exit 1
fi

echo "Deploy succeeded via watchdog: PR app source (${NEW_BYTES} bytes) is live on the hub at class ${CLASS_ID} (totalLength ${POST_LEN:-<unreadable>})."
echo "WATCHDOG_DEPLOY_OK classId=${CLASS_ID} libraries=${#INCLUDES[@]} appUrl=${APP_URL}"
