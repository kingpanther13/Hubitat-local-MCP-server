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
# code-install plumbing, but unlike the primary cloud endpoint it returns the
# hub's VERBATIM compile/save result SYNCHRONOUSLY -- there is no ~10s cloud
# gateway ceiling and no 504 while the app self-recompiles (the watchdog app is
# a different running app, so installing the MCP package does NOT reload the app
# answering the request). That removes the entire reason the old scripts needed
# 5xx tolerance, get_app_source length polling, lastSelfDeploy recovery, and a
# separate byte-compare verify pass. VERIFY here is simply: parse each tool
# response and fail loudly -- surfacing the hub's verbatim errorMessage -- if
# success != true, or (for the app) if the app's code version did not advance.
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
# blob never has to cross the MCP transport as an inline argument (same rationale
# as the old importUrl deploy, issue #228); each library/bundle importUrl is built
# the same way mcp_install_libraries.sh / mcp_install_bundle_hpm.sh built theirs.
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
# HTTP response body. Modeled on mcp_deploy_source.sh's mcp_call. `|| true` is
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
# `set -e` before the caller inspects the (empty) result. Mirrors the call_tool
# helper in mcp_install_libraries.sh.
call_tool() {
  local resp
  resp=$(mcp_call "$1" || true)
  printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null || true
}

# Echo "true"/"false" for a tool result's .success field (mcp_install_libraries.sh::ok_of).
ok_of() { printf '%s' "$1" | jq -r '.success // false' 2>/dev/null || echo false; }

# Echo the hub's VERBATIM error/note text from a tool result, for loud surfacing.
err_of() { printf '%s' "$1" | jq -r '.error // .message // .errorMessage // empty' 2>/dev/null || true; }

# Resolve the source-bearing Apps Code CLASS id for (namespace, name) via
# hub_list_apps scope=types. The class id (NOT the running instance id) is what
# hub_update_app's appId must reference. Modeled on the class-id lookup in
# mcp_deploy_source.sh / mcp_install_bundle_hpm.sh. Echoes the id; exits 1 loudly
# if the watchdog response is unusable or the class isn't found.
resolve_class_id() {
  local list_text class_id
  list_text=$(call_tool '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}')
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
# given class id, or empty if it can't be read. Used to assert the APP version
# advanced (the source actually changed) after the importUrl install. A 1-byte
# read is enough -- totalLength comes back in the first chunk's metadata.
app_total_length() {
  local id="$1" rpc text
  rpc=$(jq -nc --arg id "$id" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1}}}')
  text=$(call_tool "$rpc")
  printf '%s' "$text" | jq -r '.totalLength // empty' 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Destructive WRITE tools (hub_update_library / hub_create_library / hub_install_bundle /
# hub_update_app) are confirm-gated: confirm:true PLUS a hub backup within 24h. Refresh
# the timestamp best-effort; the gate is timestamp-cached so a failure here is tolerated
# (CI keeps the window warm). Same posture as the scripts this replaces.
echo "Triggering hub backup via watchdog (best-effort; the <24h destructive gate is timestamp-cached)..."
mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' >/dev/null \
  || echo "::warning::hub_create_backup call failed/timed out; relying on the cached <24h backup timestamp"

# ===========================================================================
# 1) LIBRARIES FIRST -- install/update every library the app #includes so the
#    `#include` directives resolve when the app compiles (issue #209). Matches
#    each #include to its library file by the (namespace, name) declared in the
#    library() block (NOT by filename, mirroring the hub), exactly as
#    mcp_install_libraries.sh did.
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
#    ~1.5MB raw source itself; issue #228). The watchdog returns the hub's
#    VERBATIM compile/save result synchronously, so there is NO 504 to tolerate:
#    a rejected save comes back as success:false WITH the real errorMessage, and
#    a clean compile comes back success:true. VERIFY = success:true AND the app's
#    code version (source totalLength) advanced from the pre-deploy baseline.
# ===========================================================================
CLASS_ID="$(resolve_class_id)"
echo "Resolved Apps Code class id for ${APP_NAMESPACE}:${APP_NAME}: ${CLASS_ID}"

APP_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/$(basename "$APP_FILE")"
NEW_BYTES=$(wc -c < "$APP_FILE")
# The hub's totalLength is a CHARACTER count (Groovy String.length() = UTF-16 code units), not bytes.
# wc -m gives codepoints, which equals UTF-16 units for all BMP characters (everything but rare non-BMP
# like emoji) -- a good-enough no-op approximation for Groovy source. success=true is the primary signal.
NEW_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$APP_FILE" | tr -d '[:space:]')

# Baseline the live app source length BEFORE the deploy. The post-deploy assertion
# is "totalLength changed", proving the new source actually landed -- not just that
# the tool returned success:true. A read failure here leaves the baseline empty,
# which only relaxes that one extra assertion (success:true is still required); it
# never turns a real failure into a pass.
PRE_LEN="$(app_total_length "$CLASS_ID")"
echo "Pre-deploy app source totalLength baseline: ${PRE_LEN:-<unreadable>}"

echo "Deploying app class ${CLASS_ID} via importUrl (hub fetches ${NEW_BYTES} bytes from ${APP_URL}) through the watchdog endpoint..."
DEPLOY_RPC=$(jq -nc --arg id "$CLASS_ID" --arg url "$APP_URL" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,confirm:true}}}')
DEPLOY_TEXT=$(call_tool "$DEPLOY_RPC")

if [ -z "$DEPLOY_TEXT" ]; then
  echo "::error::hub_update_app returned no parseable MCP content from the watchdog endpoint. The watchdog answers synchronously (no 504), so an empty body is a transient transport failure -- re-run the job."
  exit 1
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

# success:true. Confirm the app code version actually advanced (source changed),
# so a stale-but-"ok" response can't pass the gate. Only enforced when the
# baseline was readable AND the source genuinely differs in length.
POST_LEN="$(app_total_length "$CLASS_ID")"
echo "Post-deploy app source totalLength: ${POST_LEN:-<unreadable>} (baseline ${PRE_LEN:-<unreadable>})"

if [ -n "$PRE_LEN" ] && [ -n "$POST_LEN" ] && [ "$POST_LEN" != "0" ]; then
  if [ "$POST_LEN" = "$PRE_LEN" ]; then
    # Identical length CAN be a legitimate no-op (the hub already held byte-identical
    # source, or a whitespace-only / coincidentally length-matching diff). Distinguish
    # the two: if this PR's local source length matches the hub's, it's a real no-op
    # (already deployed); otherwise the version did NOT advance and the deploy is bad.
    if [ "$POST_LEN" = "$NEW_CHARS" ]; then
      echo "::notice::App source totalLength unchanged (${POST_LEN}) but character-length-identical to this PR's source -- a legitimate no-op (the hub already held this PR's app). Treating as deployed."
    else
      echo "::error::hub_update_app reported success=true but the app's code version did NOT advance: source totalLength is still ${POST_LEN} (baseline ${PRE_LEN}), and it does not match this PR's local source character length (${NEW_CHARS}). The new source did not take effect on the hub."
      exit 1
    fi
  else
    echo "App version advanced: source totalLength ${PRE_LEN} -> ${POST_LEN}."
  fi
else
  echo "::warning::Could not read both pre/post source lengths to confirm the version advanced; relying on hub_update_app success=true alone (the watchdog returned the hub's synchronous compile result). The independent landed-source check was skipped due to an unreadable pre/post length."
fi

echo "Deploy succeeded via watchdog: PR app source (${NEW_BYTES} bytes) compiled and is live on the hub at class ${CLASS_ID}."
echo "WATCHDOG_DEPLOY_OK classId=${CLASS_ID} libraries=${#INCLUDES[@]} appUrl=${APP_URL}"
