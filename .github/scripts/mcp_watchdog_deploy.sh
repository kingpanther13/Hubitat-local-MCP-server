#!/usr/bin/env bash
# Install THIS PR's package onto the live test hub via the WATCHDOG MCP endpoint,
# in Hubitat Package Manager's REPAIR order: the BUNDLE (which delivers every #included
# library) first, then EVERY app the manifest declares (parent + child) -- full HPM
# repair, overriding whatever is installed. Libraries are NOT installed individually --
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
# Install order (HPM's repair order; #include deps must exist before the app compiles):
#   1. BUNDLE(S)  hub_install_bundle  importUrl=<bundle zip url>  confirm:true  (delivers EVERY #included library)
#   2. APPS       hub_update_app for EVERY manifest app (parent + child), importUrl=<app raw url>, confirm:true
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
  # The bundle build is DETERMINISTIC (tools/build-bundle.py pins create_system), so byte-equality
  # against canonical main proves the PR ships the same libraries. When equal, installing the PR's
  # bundle is a pure waste -- the hub already carries those exact library bytes (the arm/restore keep
  # them at main) and the install would only fire a dependent-recompile wave of the ~1.8MB app. The
  # outcome is recorded run-scoped on the hub so the dead-man restore can skip its mirror reinstall
  # for the same reason; anything but a verified this-run "unchanged" makes the restore install.
  ANY_BUNDLE_DIFFERS="false"
  while IFS= read -r BUNDLE_REL; do
    [ -z "$BUNDLE_REL" ] && continue
    BUNDLE_PATH="$(dirname "$APP_FILE")/${BUNDLE_REL}"
    if [ ! -f "$BUNDLE_PATH" ]; then
      echo "::error::packageManifest.json declares bundle '${BUNDLE_REL}' but it is not in the checkout ($BUNDLE_PATH). Manifest/repo drift -- CI cannot prove HPM bundle delivery. Failing instead of passing as 'no bundle'."
      exit 1
    fi
    BUNDLE_SAME="false"
    if [ -n "${MAIN_SOURCE_URL:-}" ]; then
      MAIN_ZIP_TMP="$(mktemp)"
      if curl -fsSL "${MAIN_SOURCE_URL%/*}/${BUNDLE_REL}" -o "$MAIN_ZIP_TMP" 2>/dev/null \
         && cmp -s "$BUNDLE_PATH" "$MAIN_ZIP_TMP"; then
        BUNDLE_SAME="true"
      fi
      rm -f "$MAIN_ZIP_TMP"
    fi
    if [ "$BUNDLE_SAME" = "true" ]; then
      echo "Bundle '${BUNDLE_REL}' is byte-identical to canonical main's -- skipping the install (hub already carries these exact library bytes; no recompile wave)."
      continue
    fi
    ANY_BUNDLE_DIFFERS="true"
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
  # Run-scoped bundle-state marker for the dead-man restore: "<runId>:unchanged" lets restorePackage
  # skip reinstalling main's cached bundle (the libraries never left main's bytes this run). Any other
  # content -- different runId (stale marker from a crashed run), "changed", or a failed write -- makes
  # the restore do the install: fail-safe in the direction of restoring.
  BUNDLE_STATE=$([ "$ANY_BUNDLE_DIFFERS" = "true" ] && echo "changed" || echo "unchanged")
  MARKER_RPC=$(jq -nc --arg c "${GITHUB_RUN_ID:-unknown}:${BUNDLE_STATE}" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"e2e-pr-bundle-state.txt",content:$c,confirm:true}}}')
  call_tool "$MARKER_RPC" >/dev/null \
    || echo "::warning::could not write the bundle-state marker; the restore will reinstall the cached bundle (safe, just slower)."
  echo "Bundle state marker: ${GITHUB_RUN_ID:-unknown}:${BUNDLE_STATE}"
fi

# ===========================================================================
# 3) APPS -- full HPM repair deploys EVERY app the manifest declares (parent AND
#    child), not just the parent. For each: resolve its Apps Code class id by
#    namespace+name, build its raw URL at THIS PR's head SHA (re-anchored from the
#    manifest location, the same prefix-strip as the bundle in section 2), and deploy
#    via hub_update_app importUrl (the hub fetches the raw source itself; issue #228).
#    deploy_app_via_watchdog CONFIRMS each landed via a FRESH lastSelfDeploy success
#    (the watchdog records the appId it deployed) -- the only signal that survives the
#    ~1.6MB parent app's relay-dropped ~200s response AND a same-length change, so a
#    stale/wrong source can never false-green. As a belt-and-suspenders cross-check we
#    also confirm each app's live totalLength matches this PR's file.
# ===========================================================================
# Deploy the SELF app (mcp:MCP Rule Server) LAST, like the production toolUpdatePackage does, so the e2e
# installs in the same app order that ships (and a mid-loop failure leaves the small child changed rather
# than the 1.6MB server). Not a brick risk on this path -- every call goes through the WATCHDOG, a separate
# app the server's recompile never drops -- but matching the shipped order keeps e2e faithful. sort_by a
# boolean puts false (non-self) before true (self).
APP_RECS=$(jq -r '[.apps[]?] | sort_by(.namespace == "mcp" and .name == "MCP Rule Server") | .[] | "\(.namespace)\t\(.name)\t\(.location)"' "$MANIFEST_FILE")
if [ -z "$APP_RECS" ]; then
  echo "::error::packageManifest.json declares no apps -- nothing to deploy. Refusing to pass as a successful install."
  exit 1
fi

# Mark the hub DIRTY for the canonical-main refresh: we are about to deploy PR code over the app, so the
# recorded "hub is on canonical main" SHA marker is no longer valid. Clearing it means that if THIS run's
# disarm later fails to restore main, the NEXT run's arm refreshes canonical main instead of skipping on a
# stale marker (closing the contaminated-hub-after-failed-restore gap). The disarm rewrites the marker
# only after a CONFIRMED restore. Best-effort -- the marker is a skip optimization, not a safety gate.
echo "Clearing the canonical-main SHA marker (about to deploy PR code -- hub no longer guaranteed canonical main)..."
call_tool "$(jq -nc '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"mcp-main-deployed-sha.txt",content:"",confirm:true}}}')" >/dev/null || true

DEPLOYED_APPS=0
while IFS=$'\t' read -r A_NS A_NAME A_LOC; do
  [ -z "$A_NS" ] && continue
  # Re-anchor the manifest location (committed against a fixed branch) to THIS PR's head SHA --
  # the same host/owner/repo/ref prefix-strip used for the bundle in section 2.
  A_REL=$(printf '%s' "$A_LOC" | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
  A_FILE="$(dirname "$APP_FILE")/${A_REL}"
  if [ ! -f "$A_FILE" ]; then
    echo "::error::packageManifest.json declares app '${A_NS}:${A_NAME}' at '${A_REL}' but it is not in the checkout ($A_FILE). Manifest/repo drift -- failing instead of deploying a stale revision."
    exit 1
  fi
  A_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${A_REL}"
  A_CLASS="$(resolve_class_id "$A_NS" "$A_NAME")"
  echo "Deploying app ${A_NS}:${A_NAME} (class ${A_CLASS}) from ${A_URL} via the watchdog ..."
  deploy_app_via_watchdog "$A_CLASS" "$A_URL" "install ${A_NAME}"

  # Belt-and-suspenders: the fresh lastSelfDeploy proves THIS importUrl deployed; cross-check the live
  # source length is this PR's length (the RIGHT revision). totalLength is a CHARACTER count (Groovy
  # String.length() = UTF-16 units); wc -m == UTF-16 units for all-BMP source (the repo is ASCII).
  A_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$A_FILE" | tr -d '[:space:]')
  POST_LEN="$(app_total_length "$A_CLASS")"
  if [ -n "$POST_LEN" ] && [ "$POST_LEN" != "0" ] && [ "$POST_LEN" != "$A_CHARS" ]; then
    echo "::error::App ${A_NS}:${A_NAME} deploy confirmed by lastSelfDeploy, but the live source totalLength (${POST_LEN}) does not match this PR's length (${A_CHARS}) -- a different revision may be live. Investigate."
    exit 1
  fi
  echo "App ${A_NS}:${A_NAME} live at class ${A_CLASS} (totalLength ${POST_LEN:-<unreadable>})."
  DEPLOYED_APPS=$((DEPLOYED_APPS + 1))
done <<< "$APP_RECS"

echo "Full-repair deploy succeeded via watchdog: ${DEPLOYED_APPS} app(s) + their library bundle(s) live on the hub."
echo "WATCHDOG_DEPLOY_OK apps=${DEPLOYED_APPS} libraries=${#INCLUDES[@]}"
