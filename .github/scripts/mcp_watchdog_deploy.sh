#!/usr/bin/env bash
# Install THIS PR's package onto the live test hub via the WATCHDOG MCP endpoint,
# in Hubitat Package Manager's order: the BUNDLE (which delivers every #included
# library) first, then the APP last. Libraries are NOT installed individually --
# the bundle ships them; doing both was redundant and forced an extra recompile.
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
# and stays queryable throughout. Small/fast calls (the package's libraries bundle,
# get_source, get_info) return the hub's VERBATIM compile/save
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
#   1. BUNDLE  hub_install_bundle  importUrl=<bundle zip url>  confirm:true  (delivers EVERY #included library)
#   2. APP     hub_update_app  appId=<MCP class id>  importUrl=<app raw url>  confirm:true
# Libraries are delivered ONLY by the bundle -- there is no per-#include hub_update_library/hub_create_library
# step (section 1 only DISCOVERS the #includes + sanity-checks the manifest declares a bundle to deliver them).
#
# Usage: mcp_watchdog_deploy.sh [path/to/hubitat-mcp-server.groovy]
# Env:   WATCHDOG_URL          -- full watchdog MCP endpoint URL with access_token (secret WATCHDOG_MCP_URL)
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA
#        RUNNER_TEMP           -- GHA temp dir; falls back to /tmp
#
# The app's importUrl points the hub at the PR branch's raw source so the ~1.5MB
# blob never has to cross the MCP transport as an inline argument (importUrl
# deploy, issue #228); the bundle importUrl is built from PR_RAW_BASE +
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
# 1) LIBRARIES are delivered ONLY by the BUNDLE below (section 2) -- the bundle is the real HPM/user
#    "one Update click" delivery path, and it ships every library the app #includes. Re-installing each
#    #included library INDIVIDUALLY here (the old per-#include hub_update_library/create loop) was
#    redundant -- the bundle delivers the same libraries -- and it triggered an extra hub recompile of
#    the running app per library touched. So this step now only DISCOVERS the #include set and
#    sanity-checks the package can deliver it (a manifest with #includes but no bundle would leave the
#    directives unresolved and the app would fail to compile). The exact library bytes land in section 2.
# ===========================================================================
mapfile -t INCLUDES < <(
  grep -hoE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' "$APP_FILE" \
    | sed -E 's/^[[:space:]]*#include[[:space:]]+//' | sort -u || true
)
if [ "${#INCLUDES[@]}" -gt 0 ] && [ -z "$BUNDLE_RELS" ]; then
  echo "::error::App #includes ${#INCLUDES[@]} library(ies) (${INCLUDES[*]}) but packageManifest.json declares NO bundle to deliver them. A bundle-only install would leave the #include directives unresolved and the app would not compile. Add the libraries' bundle to the manifest."
  exit 1
fi
echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]:-<none>} -- delivered via the package bundle (section 2), the HPM way (no redundant per-library install)."

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
