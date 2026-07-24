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
# and stays queryable throughout. Most calls (get_source, get_info, the small per-library
# operations) return the hub's VERBATIM compile/save result synchronously, so VERIFY is
# simply: parse the tool response and fail loudly -- surfacing the hub's verbatim
# errorMessage -- if success != true. TWO operations instead get a dropped/EMPTY response
# (not a structured hub error); for both we tolerate that and consult an authoritative
# on-hub check rather than fail-fast, each for its own reason:
#   - the ~1.6MB APP deploy genuinely runs ~200s hub-side, so the response is dropped while
#     the deploy proceeds (like the live server's self-deploy): poll hub_get_source until
#     the new source lands, consulting lastSelfDeploy only as a secondary source for the
#     hub's verbatim compile error if it never does.
#   - the package BUNDLE install can return an empty result (no JSON-RPC error, no
#     structured tool error) for the #209 ~1.67MB bundle even though the import SUCCEEDED:
#     verify_includes_current finds McpNativeRulesLib on the hub at its exact character length
#     (wc -m vs the hub's char-based totalLength) on the FIRST probe, so this is NOT an
#     install-duration timeout -- the cause of the
#     lost envelope is unpinned (the small bundles don't trigger it). So a dropped/empty
#     install response falls through to verify_includes_current, the authoritative
#     length-check of every #include'd library on the hub. A structured hub error
#     (non-empty errorMessage) still fails loudly and fast. The hub fetches the zip itself
#     from importUrl (hubInternalGet, 300s) -- the bundle DATA never crosses the MCP
#     transport -- and HPM users install from the local hub UI, so this is an e2e-transport
#     quirk, not a user-facing one.
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
# deploy, issue #228); the bundle importUrl is the PR's bundle-artifacts entry
# (shas/<head-sha>/), byte-verified against the CI build before install.
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full watchdog MCP endpoint URL with access_token; from secret WATCHDOG_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (https://raw.githubusercontent.com/<owner>/<repo>)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (40-hex PR head SHA)}"

APP_FILE="${1:-hubitat-mcp-server.groovy}"
if [ ! -f "$APP_FILE" ]; then
  echo "::error::App file not found: $APP_FILE (wrong working directory or bad path arg). Refusing to silently report 'nothing to install'."
  exit 1
fi

# The package's bundle zip BASENAMES, DERIVED from packageManifest.json -- never hardcoded. The
# bundle name changes with the #209 modularization, and a hardcoded name would silently stop
# proving HPM delivery once it drifts. Since the unified-delivery change the manifest location
# points at the bundle-artifacts branch (not an in-tree path), so the only thing the deploy
# takes from it is the zip's basename: the deploy BUILDS that zip from the checkout's
# libraries/ (tools/build-bundle.py writes bundles/<basename>) and installs the PR's
# bundle-artifacts entry after byte-verifying it against the build.
MANIFEST_FILE="$(dirname "$APP_FILE")/packageManifest.json"
BUNDLE_BASENAMES=""
if [ -f "$MANIFEST_FILE" ]; then
  BUNDLE_BASENAMES=$(jq -r '.bundles[]?.location // empty' "$MANIFEST_FILE" | sed -E 's#.*/##')
fi

# ---------------------------------------------------------------------------
# Shared watchdog JSON-RPC + deploy-confirmation helpers (mcp_call / call_tool [surfaces JSON-RPC
# errors] / call_tool_retry / ok_of / err_of / resolve_class_id / app_total_length [noSave probe] /
# deploy_app_via_watchdog [confirms via fresh lastSelfDeploy]). One source of truth so the deploy and
# arm scripts cannot drift.
source "$(dirname "$0")/mcp_watchdog_lib.sh"

# ---------------------------------------------------------------------------
# Dead-man heartbeat: launched disowned HERE (the first PR-side script after the arm) so it
# covers the deploy AND the whole test step -- runner background processes survive step
# boundaries within a job. mcp_disarm_watchdog.sh kills it by PID file before touching the
# flag; if the runner dies, the heartbeat dies with it and the flag's deadline lapses --
# exactly when the dead-man SHOULD fire. See mcp_deadman_heartbeat.sh for why the deadline
# is heartbeat-extended instead of a fixed arm window.
HB_PID_FILE="${RUNNER_TEMP:-/tmp}/deadman-heartbeat.pid"
HB_LOG="${RUNNER_TEMP:-/tmp}/deadman-heartbeat.log"
nohup bash "$(dirname "$0")/mcp_deadman_heartbeat.sh" > "$HB_LOG" 2>&1 &
echo "$!" > "$HB_PID_FILE"
echo "Dead-man heartbeat launched (pid $(cat "$HB_PID_FILE"), log $HB_LOG)."

# ---------------------------------------------------------------------------
# NO per-run defensive backup here anymore: the hub backup is a heavy operation the platform's
# load limiter punishes (empirically: dispatch-block episodes tracked per-run backups; backup-free
# runs never tripped). The watchdog's write tools don't require one, and the test runner takes a
# REAL backup only when the destructive-confirm gate's 24h record has gone stale (>20h).

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
if [ "${#INCLUDES[@]}" -gt 0 ] && [ -z "$BUNDLE_BASENAMES" ]; then
  echo "::error::App #includes ${#INCLUDES[@]} library(ies) (${INCLUDES[*]}) but packageManifest.json declares NO bundle to deliver them. A bundle-only install would leave the #include directives unresolved and the app would not compile. Add the libraries' bundle to the manifest."
  exit 1
fi
echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]:-<none>} -- delivered via the package bundle (section 2), the HPM way (no redundant per-library install)."

# ---------------------------------------------------------------------------
# verify_includes_current <probe|enforce> -- check every #include'd library on
# the hub: exactly ONE copy per namespace+name (two = the duplicate-library
# trap; the app's #include binds to only one, so a bundle update can land in
# the wrong copy -- a hard error in BOTH modes, an install cannot heal it) and
# an on-hub source length equal to the checkout file's (the bundle stores the
# file verbatim; totalLength is a CHARACTER count = UTF-16 units, and wc -m
# matches for the repo's all-BMP/ASCII source). enforce = the post-install
# gate: any mismatch is a hard ::error:: + exit. probe = the pre-skip check:
# a mismatch (or an unreadable hub list) returns 1 so the caller installs --
# fail-safe toward installing + verifying, never toward a false skip.
# Uses the INCLUDES array (section 1) and REPO_DIR (section 2) globals.
# ---------------------------------------------------------------------------
verify_includes_current() {
  local MODE="$1"
  [ "${#INCLUDES[@]}" -eq 0 ] && return 0
  # Idempotent read via call_tool_retry, then an explicit empty-guard: a relay drop must
  # surface as "could not read the list", never masquerade as "library not on hub".
  local LIBLIST_TEXT
  LIBLIST_TEXT=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}')
  if [ -z "$LIBLIST_TEXT" ]; then
    if [ "$MODE" = "probe" ]; then
      echo "  probe: could not read hub_list_libraries (relay drop after retries) -- treating the hub as not current."
      return 1
    fi
    echo "::error::could not read hub_list_libraries to verify the #include'd libraries (relay drop after retries) -- re-run the job."
    exit 1
  fi
  local TOKEN NS NAME IDS N_IDS LIB_ID LIB_FILE EXPECTED_CHARS SRC_RPC SRC_TEXT HUB_CHARS
  for TOKEN in "${INCLUDES[@]}"; do
    NS="${TOKEN%%.*}"; NAME="${TOKEN#*.}"
    IDS=$(printf '%s' "$LIBLIST_TEXT" | jq -r --arg ns "$NS" --arg nm "$NAME" \
      '.libraries[]? | select(.namespace == $ns and .name == $nm) | .id' 2>/dev/null || true)
    N_IDS=$(printf '%s' "$IDS" | grep -c . || true)
    if [ "$N_IDS" -eq 0 ]; then
      if [ "$MODE" = "probe" ]; then
        echo "  probe: library ${TOKEN} is not on the hub."
        return 1
      fi
      echo "::error::library ${TOKEN} is NOT on the hub after the bundle step -- the app's #include cannot resolve."
      exit 1
    fi
    if [ "$N_IDS" -gt 1 ]; then
      echo "::error::library ${TOKEN} exists ${N_IDS} times on the hub (ids: $(printf '%s' "$IDS" | tr '\n' ' ')) -- the duplicate-library trap: the app's #include binds to only ONE copy, so a bundle update can land in the wrong one. Delete the extra copies on the hub (Libraries Code) and re-run."
      exit 1
    fi
    LIB_ID=$(printf '%s' "$IDS" | head -n1)
    # `|| true`: grep -l exits 1 on no match, and under `set -euo pipefail` a bare command-substitution
    # assignment with a failed pipeline aborts the script -- in enforce mode (a non-conditional call) that
    # would kill the deploy at this line BEFORE the explicit empty-LIB_FILE guard below can print its
    # precise error. The `|| true` keeps the assignment succeeding so the guard runs; 2>/dev/null drops
    # grep's no-such-file noise when the glob matches nothing.
    LIB_FILE=$(grep -l -E "^library\(.*name: \"${NAME}\"" "$REPO_DIR"/libraries/*.groovy 2>/dev/null | head -n1 || true)
    if [ -z "$LIB_FILE" ]; then
      echo "::error::no libraries/*.groovy in the checkout declares name \"${NAME}\" -- cannot verify ${TOKEN}."
      exit 1
    fi
    EXPECTED_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$LIB_FILE" | tr -d '[:space:]')
    SRC_RPC=$(jq -nc --arg id "$LIB_ID" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"library",id:($id|tonumber),length:1,noSave:true}}}')
    SRC_TEXT=$(call_tool_retry "$SRC_RPC")
    HUB_CHARS=$(printf '%s' "$SRC_TEXT" | jq -r '.totalLength // empty' 2>/dev/null || true)
    if [ "$HUB_CHARS" != "$EXPECTED_CHARS" ]; then
      if [ "$MODE" = "probe" ]; then
        echo "  probe: library ${TOKEN} (id ${LIB_ID}) differs: ${HUB_CHARS:-unknown} chars on the hub vs the checkout's ${EXPECTED_CHARS}."
        return 1
      fi
      echo "::error::library ${TOKEN} (id ${LIB_ID}) is STALE on the hub: ${HUB_CHARS:-unknown} chars vs the PR file's ${EXPECTED_CHARS} ($(basename "$LIB_FILE")). The bundle step did not land this library -- the app would compile against old library code."
      exit 1
    fi
    echo "  ${TOKEN}: id ${LIB_ID}, ${HUB_CHARS} chars -- matches the PR file."
  done
  return 0
}

# ===========================================================================
# 2) BUNDLE -- build the PR's bundle zip IN CI, then install it the HPM way
#    (HPM "install bundles", run BEFORE "install apps"). Delivery is UNIFIED on
#    the bundle-artifacts branch: the manifest's bundles[].location points at
#    branches/main/ (what HPM users fetch), publish-bundle-artifact.yml feeds it
#    on every push, and this deploy installs the PR's shas/<sha>/ entry -- the
#    SAME mechanism, branch, and filename users ride, with bytes that are
#    deterministic-build-identical to what branches/main/ will serve once the
#    PR merges. There is no committed zip. Resolution order, lightest first:
#      a. SKIP -- the built zip is byte-identical to canonical main's AND a
#         live probe proves every #include'd library on the hub matches the
#         checkout (no install, no recompile wave). Byte-equality alone is NOT
#         enough: the dead-man restore reinstates the main that was canonical
#         when its run ARMED, so a merge landing mid-run moves main ahead and
#         leaves the hub one merge behind -- a later PR whose zip equals the
#         NEW main would then skip while the hub still carries the OLD main's
#         libraries, and the app compiles against stale library code. When the
#         probe finds the hub stale, this run HEALS it: install canonical
#         main's own artifact (already byte-verified against the CI build),
#         which still counts as "unchanged" for the restore marker -- the end
#         state IS canonical main.
#      b. bundle-artifacts -- the publish-bundle-artifact workflow stored this
#         exact SHA's zip on the bot-only bundle-artifacts branch; the hub
#         fetches it straight from the raw URL (no hub-side staging at all).
#         The runner byte-compares the artifact against the CI build first, so
#         a stale/raced artifact can never be installed.
#      c. no artifact (fork PRs / publish race) -- HARD error with the remedy;
#         the hub only ever installs a bundle by fetching a URL, the HPM way.
#    Every path keeps the zip's production basename -- the hub appears to key
#    the bundle entity on the zip filename, so a renamed zip can import as a
#    SECOND entity (duplicate libraries) instead of updating the existing one.
# ===========================================================================
if [ -z "$BUNDLE_BASENAMES" ]; then
  echo "packageManifest.json declares no bundles -- skipping the bundle step (clean no-op)."
else
  REPO_DIR="$(dirname "$APP_FILE")"
  if [ ! -f "$REPO_DIR/tools/build-bundle.py" ]; then
    echo "::error::tools/build-bundle.py not found in the checkout -- cannot build the PR's bundle zip."
    exit 1
  fi
  echo "Building the PR's bundle zip from the checkout's libraries/ ..."
  ( cd "$REPO_DIR" && python3 tools/build-bundle.py )
  ANY_BUNDLE_DIFFERS="false"
  ANY_BUNDLE_INSTALLED="false"
  BUNDLE_INSTALL_UNCONFIRMED="false"
  # Every install RPC issued this run, kept so the post-install poll can RE-ISSUE the exact
  # same hub_install_bundle (same importUrl) ONCE if a library is slow to register -- a nudge,
  # not a new install path (still bundle-only). Indexed by basename so the replay names each.
  INSTALLED_BUNDLE_RPCS=()
  while IFS= read -r BASENAME; do
    [ -z "$BASENAME" ] && continue
    BUNDLE_PATH="$REPO_DIR/bundles/${BASENAME}"
    if [ ! -f "$BUNDLE_PATH" ]; then
      echo "::error::packageManifest.json declares bundle '${BASENAME}' but the builder did not produce bundles/${BASENAME}. Manifest/builder drift -- CI cannot prove HPM bundle delivery. Failing instead of passing as 'no bundle'."
      exit 1
    fi
    # (a) The bundle build is DETERMINISTIC (tools/build-bundle.py pins create_system), so
    # byte-equality of the BUILT zip against canonical main's bundle-artifacts entry proves
    # the PR ships the same libraries users already have. A resolver miss falls through to
    # the install path -- fail-safe toward installing + verifying, never toward a false skip.
    BUNDLE_SAME="false"
    MAIN_BUNDLE_URL=""
    if [ -n "${MAIN_SOURCE_URL:-}" ] && [ -n "${MAIN_SHA:-}" ] \
       && MAIN_BUNDLE_URL=$(resolve_main_bundle_artifact_url "$BASENAME"); then
      MAIN_ZIP_TMP="$(mktemp)"
      if curl -fsSL "$MAIN_BUNDLE_URL" -o "$MAIN_ZIP_TMP" 2>/dev/null \
         && cmp -s "$BUNDLE_PATH" "$MAIN_ZIP_TMP"; then
        BUNDLE_SAME="true"
      fi
      rm -f "$MAIN_ZIP_TMP"
    fi
    BUNDLE_URL=""
    if [ "$BUNDLE_SAME" = "true" ]; then
      # Byte-equality with canonical main says the PR ships nothing new -- but only the HUB
      # knows whether it actually carries those bytes (see the resolution-order note above:
      # a mid-run merge leaves a restored hub one merge behind). Probe before skipping; on a
      # stale hub, heal with canonical main's own artifact -- the cmp above just proved it
      # byte-identical to the CI build, so the install is already byte-verified. Either way
      # the end state is canonical main's bytes, so this bundle stays "unchanged" for the
      # restore marker (ANY_BUNDLE_DIFFERS is not set).
      echo "Bundle '${BASENAME}' is byte-identical to canonical main's -- probing the hub's #include'd libraries before skipping the install ..."
      if verify_includes_current probe; then
        echo "Bundle '${BASENAME}': hub libraries verify current -- skipping the install (no recompile wave)."
        continue
      fi
      echo "Bundle '${BASENAME}': hub libraries are NOT current despite byte-equality with main -- healing with canonical main's artifact."
      BUNDLE_URL="$MAIN_BUNDLE_URL"
    else
      ANY_BUNDLE_DIFFERS="true"

      # (b) bundle-artifacts: published by this SHA's push; runner-verified byte-identical to the
      # CI build before the hub is pointed at it.
      ART_URL="${PR_RAW_BASE}/bundle-artifacts/shas/${PR_HEAD_SHA_RESOLVED}/${BASENAME}"
      ART_TMP="$(mktemp)"
      if curl -fsSL "$ART_URL" -o "$ART_TMP" 2>/dev/null && cmp -s "$BUNDLE_PATH" "$ART_TMP"; then
        BUNDLE_URL="$ART_URL"
        echo "Bundle '${BASENAME}': using the bundle-artifacts zip for this SHA (byte-verified against the CI build)."
      fi
      rm -f "$ART_TMP"

      # No matching artifact: HARD error with the remedy. The hub can only install a bundle by
      # FETCHING a URL (the HPM way); without an artifact there is no URL anywhere that serves
      # this SHA's libraries (the zip committed at the SHA is stale by design now). Same-repo
      # branches get an artifact automatically on push (publish-bundle-artifact.yml); a fork PR
      # that changes libraries needs the branch pushed to the base repo by a maintainer.
      if [ -z "$BUNDLE_URL" ]; then
        echo "::error::no bundle-artifacts zip for SHA ${PR_HEAD_SHA_RESOLVED} (looked at ${ART_URL}) and the PR's libraries differ from main -- there is no URL serving this PR's bundle. Same-repo branches publish one automatically on push (publish-bundle-artifact.yml; re-run if this run raced the publish). For a fork PR, push the branch to the base repo."
        exit 1
      fi
    fi

    echo "Installing package bundle '${BASENAME}' from ${BUNDLE_URL} via hub_install_bundle ..."
    BUN_RPC=$(jq -nc --arg url "$BUNDLE_URL" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
    # Record this exact install RPC (tab-joined "basename\tRPC") so the post-install poll can
    # re-issue it verbatim ONCE as an idempotent nudge for a slow registration -- same importUrl,
    # same bundle, no new delivery path.
    INSTALLED_BUNDLE_RPCS+=("${BASENAME}"$'\t'"${BUN_RPC}")
    # Read the RAW JSON-RPC response, not call_tool's collapsed text: call_tool returns empty
    # stdout for BOTH a relay-dropped envelope (install accepted, response lost) AND a JSON-RPC
    # / tool error (auth failure, missing hub_install_bundle tool, malformed request -- the
    # install never ran). Those must NOT be treated the same: deferring a never-ran install to
    # the length-only verify could false-pass on a pre-existing/stale library (codex review,
    # PR #279). So hard-fail on a JSON-RPC error; only a genuinely empty result (no error)
    # defers to the verify.
    BUN_RAW=$(mcp_call "$BUN_RPC" || true)
    # Detect a JSON-RPC error from the PRESENCE of a non-null .error, independent of its shape:
    # `.error.message` (spec object), a bare-string `.error` (relay convention), or a message-less
    # error object all hard-fail. `.message?` is the optional operator so a bare-string .error does
    # not abort the whole jq program (which would silently route a never-ran install to the defer
    # path). type=="object" guard keeps a non-JSON 504 body from erroring.
    BUN_JSONRPC_ERR=$(printf '%s' "$BUN_RAW" | jq -r 'if (type=="object" and has("error") and .error != null) then (.error.message? // (.error|strings) // (.error|tojson)) else empty end' 2>/dev/null || true)
    BUN_TEXT=$(printf '%s' "$BUN_RAW" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
    if [ -n "$BUN_JSONRPC_ERR" ] && [ "$BUN_JSONRPC_ERR" != "null" ]; then
      echo "::error::hub_install_bundle JSON-RPC/transport error for '${BASENAME}' -- the install command did not run (auth failure, missing tool, or malformed request), so the library was NOT delivered. Verbatim: $(printf '%s' "$BUN_JSONRPC_ERR" | head -c 300)"
      exit 1
    elif [ "$(ok_of "$BUN_TEXT")" = "true" ]; then
      ANY_BUNDLE_INSTALLED="true"
      echo "Bundle '${BASENAME}' installed: $(printf '%s' "$BUN_TEXT" | jq -c '{success,endpoint,message}' 2>/dev/null || true)"
    elif [ -z "$BUN_TEXT" ]; then
      # Empty result with NO JSON-RPC error: the request was ACCEPTED but its response envelope
      # was lost (relay 504 / dropped connection) -- the same class the rule-create e2e tests
      # recover by verifying the real on-hub outcome. The install command ran, so defer to the
      # authoritative post-install verify_includes_current (every #include'd library present,
      # single copy, exact character length -- wc -m vs the hub's totalLength). This enforce
      # gate is LOAD-BEARING for the empty path: it is the only thing that catches a never-ran
      # install here, so it must never be weakened to a probe-only check. For the NEW
      # McpNativeRulesLib this is a genuine run-confirmation: it is absent from main, so the
      # verify can only pass if THIS install
      # delivered it (a stale same-length copy is implausible for a 787KB new file). The hub
      # fetches the zip itself from importUrl (300s) -- bundle DATA never crosses the transport
      # -- so HPM users (local hub UI) are unaffected. JSON-RPC errors (above) and structured
      # hub errors (below) both still hard-fail.
      echo "::warning::hub_install_bundle returned no success envelope for '${BASENAME}' (empty result, no JSON-RPC error) -- request accepted but response dropped; deferring to the authoritative post-install library verify."
      ANY_BUNDLE_INSTALLED="true"
      BUNDLE_INSTALL_UNCONFIRMED="true"
    else
      echo "::error::hub_install_bundle did not report success for '${BASENAME}' -- HPM would fail to deliver this package's bundle. Hub verbatim: $(err_of "$BUN_TEXT") -- full: $(printf '%s' "$BUN_TEXT" | jq -c '{success,error,endpoint,rawResponse}' 2>/dev/null || printf '%s' "$BUN_TEXT" | head -c 400)"
      exit 1
    fi
  done <<< "$BUNDLE_BASENAMES"
  # Run-scoped bundle-state marker for the dead-man restore: "<runId>:unchanged" lets restorePackage
  # skip reinstalling main's cached bundle (the libraries never left main's bytes this run). Any other
  # content -- different runId (stale marker from a crashed run), "changed", or a failed write -- makes
  # the restore do the install: fail-safe in the direction of restoring.
  BUNDLE_STATE=$([ "$ANY_BUNDLE_DIFFERS" = "true" ] && echo "changed" || echo "unchanged")
  MARKER_RPC=$(jq -nc --arg c "${GITHUB_RUN_ID:-unknown}:${BUNDLE_STATE}" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"e2e-pr-bundle-state.txt",content:$c,confirm:true}}}')
  # call_tool returns rc 0 even on a JSON-RPC error (signalled by empty stdout), so `|| warn` would never
  # fire -- check the result explicitly instead.
  MARKER_TEXT=$(call_tool "$MARKER_RPC")
  if [ "$(ok_of "$MARKER_TEXT")" != "true" ]; then
    echo "::warning::could not write the bundle-state marker; the restore will reinstall the cached bundle (safe, just slower)."
  fi
  echo "Bundle state marker: ${GITHUB_RUN_ID:-unknown}:${BUNDLE_STATE}"

  # -------------------------------------------------------------------------
  # 2b) VERIFY every #include'd library is CURRENT on the hub after an
  #     install. Run 27322480301 proved hub_install_bundle can report success
  #     while leaving pre-existing libraries stale (the app then compiles
  #     against old library code and fails at runtime with
  #     MissingMethodException). This enforce pass runs only when something
  #     installed this run (ANY_BUNDLE_INSTALLED); when every bundle skipped,
  #     each skip already ran `verify_includes_current probe` over the full
  #     #include set and only reached `continue` after it passed, so no second
  #     pass is needed.
  #     When the install response was lost (BUNDLE_INSTALL_UNCONFIRMED), poll the
  #     non-fatal probe FIRST -- a brand-new library can register slowly under a
  #     loaded run (it lands after the old 120s window), so the poll now waits up
  #     to ~5 min and re-issues the same bundle install ONCE if the first ~60s
  #     still shows a library missing (a dropped install response can leave a slow
  #     import un-nudged). Same URL, idempotent, still bundle-only.
  # -------------------------------------------------------------------------
  if [ "${#INCLUDES[@]}" -gt 0 ]; then
    if [ "$ANY_BUNDLE_INSTALLED" = "true" ]; then
      if [ "$BUNDLE_INSTALL_UNCONFIRMED" = "true" ]; then
        # The install came back with no success envelope (cause unpinned; in practice the
        # import succeeds fast -- the library is current on the first probe). Poll the
        # non-fatal probe anyway so that on the off chance the hub is still writing the
        # library, a not-yet-landed read isn't mistaken for a failed install before the
        # authoritative enforce gate. A NEW library under a loaded e2e run can register
        # SLOWLY -- it has landed AFTER the old 120s (8x15s) window, so the enforce gate
        # tripped while the import was still in flight. PATIENCE fixes that: poll up to
        # ~5 min (20x15s). The install RESPONSE also routinely drops on the relay, and a
        # dropped response can leave a slow/stuck import un-nudged -- so once the first
        # poll round (~60s) still shows something missing, RE-ISSUE the same
        # hub_install_bundle ONCE, same importUrl (idempotent: a re-import of an already
        # current library is a no-op, and it nudges a stuck one). Still bundle-only; no
        # per-library install. The authoritative enforce gate below is unchanged.
        VERIFY_POLL_ATTEMPTS=20            # 20 x 15s ~= 5 min: covers a slow loaded-hub registration
        VERIFY_REINSTALL_AFTER_ATTEMPT=4   # ~60s of polling before the single idempotent re-install nudge
        BUNDLE_REINSTALL_DONE="false"
        echo "Install reported no success envelope -- polling up to ${VERIFY_POLL_ATTEMPTS}x15s (~5 min) until the #include'd libraries verify current ..."
        for attempt in $(seq 1 "$VERIFY_POLL_ATTEMPTS"); do
          if verify_includes_current probe; then
            echo "  libraries landed (probe clean) after attempt ${attempt}."
            break
          fi
          # Single idempotent nudge: after the first poll round still shows a library missing,
          # re-issue each install RPC verbatim ONCE (same bundle URL) to kick a stuck/slow import.
          if [ "$BUNDLE_REINSTALL_DONE" != "true" ] && [ "$attempt" -ge "$VERIFY_REINSTALL_AFTER_ATTEMPT" ]; then
            BUNDLE_REINSTALL_DONE="true"
            echo "  still not current after ${attempt} attempts -- re-issuing hub_install_bundle ONCE (same URL, idempotent nudge for a slow/dropped import) ..."
            for entry in "${INSTALLED_BUNDLE_RPCS[@]}"; do
              RB_NAME="${entry%%$'\t'*}"; RB_RPC="${entry#*$'\t'}"
              echo "    re-issuing install for '${RB_NAME}' ..."
              mcp_call "$RB_RPC" >/dev/null 2>&1 || true
            done
          fi
          echo "  probe attempt ${attempt}/${VERIFY_POLL_ATTEMPTS}: not current yet -- waiting 15s ..."
          sleep 15
        done
      fi
      echo "Verifying all ${#INCLUDES[@]} #include'd libraries are current on the hub (single copy, exact length) ..."
      verify_includes_current enforce
      echo "All #include'd libraries verified current."
    else
      echo "All #include'd libraries already verified current by the skip-path probe (no bundle installed this run)."
    fi
  fi
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
# call_tool returns rc 0 even on a JSON-RPC error (empty stdout), so a trailing `|| true` would swallow a
# real failure silently -- check the result and warn so a stranded stale marker is at least visible in the log.
SHA_CLEAR_TEXT=$(call_tool "$(jq -nc '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"mcp-main-deployed-sha.txt",content:"",confirm:true}}}')")
if [ "$(ok_of "$SHA_CLEAR_TEXT")" != "true" ]; then
  echo "::warning::could not clear the canonical-main SHA marker; a stale marker may let the next arm skip a needed main refresh."
fi

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

# ===========================================================================
# 4) CLEAR THE PER-APP LOAD THROTTLE -- bounce (disable/enable) the server app.
#    Hubitat's platform load limiter ("LimitExceededException: App N generates
#    excessive hub load") silently blocks the app's device-method dispatch once
#    tripped -- device commands false-succeed (the exception is thrown in the
#    DEVICE's context, invisible to the calling app) -- and the block does NOT
#    lift when the load drains. A short disable/enable of the app INSTANCE clears
#    it (verified live 2026-06-11 on fw 2.5.0.143; a hub reboot is NOT required).
#    Back-to-back e2e runs are the normal cadence, so every run clears any block
#    left by prior activity before its tests start. Routed via the WATCHDOG (a
#    different app) so the toggle cannot race the server's own request handling.
#    Failing to RE-ENABLE is a hard stop: tests against a disabled app would all
#    red with a misleading signature (the watchdog stays alive for manual rescue).
# ===========================================================================
SERVER_APP_ID="${HUBITAT_APP_ID:-}"
if [ -z "$SERVER_APP_ID" ]; then
  echo "::warning::HUBITAT_APP_ID not set -- skipping the load-throttle bounce (this run stays exposed to a stale platform throttle from prior activity)."
else
  echo "Bouncing server app instance ${SERVER_APP_ID} (disable/enable via watchdog) to clear any platform load throttle..."
  BOUNCE_OFF=$(jq -nc --arg id "$SERVER_APP_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_app_disabled",arguments:{appId:$id,disable:true,confirm:true}}}')
  BOUNCE_ON=$(jq -nc --arg id "$SERVER_APP_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_app_disabled",arguments:{appId:$id,disable:false,confirm:true}}}')
  OFF_TEXT=$(call_tool "$BOUNCE_OFF" || true)
  if [ "$(ok_of "$OFF_TEXT")" != "true" ]; then
    # Never confirmed disabled -> nothing to undo; the run proceeds merely unbounced.
    echo "::warning::throttle-bounce disable did not confirm ($(err_of "$OFF_TEXT")) -- skipping the enable leg; this run stays exposed to a stale platform throttle."
  else
    sleep 3
    ENABLED="false"
    for ATTEMPT in 1 2 3 4 5; do
      ON_TEXT=$(call_tool "$BOUNCE_ON" || true)
      if [ "$(ok_of "$ON_TEXT")" = "true" ] && printf '%s' "$ON_TEXT" | grep -q '"disabled":false'; then
        ENABLED="true"
        break
      fi
      echo "re-enable attempt ${ATTEMPT}/5 not confirmed ($(err_of "$ON_TEXT")); retrying in 5s..."
      sleep 5
    done
    if [ "$ENABLED" != "true" ]; then
      echo "::error::Server app ${SERVER_APP_ID} was disabled for the load-throttle bounce and could NOT be verifiably re-enabled after 5 attempts. Re-enable it via the watchdog (hub_set_app_disabled appId=${SERVER_APP_ID} disable=false confirm=true) before re-running. Failing loudly instead of running every test against a disabled app."
      exit 1
    fi
    echo "Load-throttle bounce complete: app ${SERVER_APP_ID} disable->enable verified."

    # Post-enable readiness: prove the server's /mcp endpoint actually ANSWERS before handing off to
    # the tests. The first request after an enable absorbs any lazy-recompile/warmup latency here
    # instead of inside the first test, and a bounce that somehow left the endpoint dead fails THIS
    # step with a precise message rather than 100 tests with a misleading one. The bounce itself
    # already cannot race a compile: it only runs after every app deploy above was CONFIRMED landed
    # (fresh lastSelfDeploy + live-length cross-check), so the saves' compiles are complete.
    if [ -n "${HUBITAT_HUB_URL:-}" ] && [ -n "${HUBITAT_ACCESS_TOKEN:-}" ]; then
      MAIN_MCP_URL="${HUBITAT_HUB_URL}/apps/${SERVER_APP_ID}/mcp?access_token=${HUBITAT_ACCESS_TOKEN}"
      READY="false"
      for ATTEMPT in 1 2 3 4 5 6 7 8 9; do
        INIT_RESP=$(curl -sS --max-time 30 -X POST "$MAIN_MCP_URL" -H "Content-Type: application/json" \
          --data-binary '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"e2e-deploy-readiness","version":"1"}}}' 2>/dev/null || true)
        if printf '%s' "$INIT_RESP" | jq -e '.result.protocolVersion // empty' >/dev/null 2>&1; then
          READY="true"
          echo "Server endpoint answered initialize on readiness attempt ${ATTEMPT} -- app ${SERVER_APP_ID} is serving."
          break
        fi
        echo "  ...readiness attempt ${ATTEMPT}/9: endpoint not answering yet; retrying in 10s..."
        sleep 10
      done
      if [ "$READY" != "true" ]; then
        echo "::error::Server app ${SERVER_APP_ID} is ENABLED but its /mcp endpoint never answered initialize within ~90s after the throttle bounce. Investigate before the tests bury this signal."
        exit 1
      fi
    else
      echo "::warning::HUBITAT_HUB_URL/HUBITAT_ACCESS_TOKEN not set -- skipping the post-bounce endpoint readiness check (the test runner's own connectivity check still gates)."
    fi
  fi
fi

# Post-deploy BIND-CHECK (fail-fast). verify_includes_current above proves each #include'd library FILE
# is on the hub (correct length); it does NOT prove the app INLINED it. A library can land yet fail to
# bind, leaving its part-methods (_readOnlyToolNames_part<X>, _getAllToolDefinitions_part<X>, ...)
# undefined on the compiled app class -- then getToolDefinitions() throws MissingMethodException from one
# of the catalog aggregators (getReadOnlyToolNames/getAllToolDefinitions/...) and EVERY tool is dead.
# initialize does NOT exercise that path (the readiness check above can't catch it); tools/list does.
# Probe tools/list and DISTINGUISH the two failure classes so we never mislabel one as the other:
#   - a real inline failure is a JSON-RPC error naming a missing _part<X> aggregator method -> fail FAST,
#     named, before the suite runs;
#   - an empty / non-JSON / transient response (relay drop, post-bounce warmup, load throttle) is NOT a
#     proven bind failure -> retry, then fail with a cause-neutral message (not "library didn't inline").
if [ -n "${HUBITAT_HUB_URL:-}" ] && [ -n "${HUBITAT_ACCESS_TOKEN:-}" ] && [ -n "${SERVER_APP_ID:-}" ]; then
  BIND_MCP_URL="${HUBITAT_HUB_URL}/apps/${SERVER_APP_ID}/mcp?access_token=${HUBITAT_ACCESS_TOKEN}"
  BIND_STATE="pending"; TL_RESP=""; BAD_LIB=""
  for ATTEMPT in 1 2 3 4 5 6; do
    TL_RESP=$(curl -sS --max-time 45 -X POST "$BIND_MCP_URL" -H "Content-Type: application/json" \
      --data-binary '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' 2>/dev/null || true)
    TL_TOOLS=$(printf '%s' "$TL_RESP" | jq -r '.result.tools | length' 2>/dev/null || echo "")
    case "$TL_TOOLS" in ''|*[!0-9]*) TL_TOOLS="" ;; esac
    if [ -n "$TL_TOOLS" ] && [ "$TL_TOOLS" -gt 0 ]; then
      echo "Post-deploy bind-check OK on attempt ${ATTEMPT}: tools/list served ${TL_TOOLS} tools -- all ${#INCLUDES[@]} #include'd library(ies) inlined."
      BIND_STATE="ok"; break
    fi
    # A genuine inline failure names a missing aggregator part-method -- stop and fail fast.
    BAD_LIB=$(printf '%s' "$TL_RESP" | grep -oiE '_(getAllToolDefinitions|readOnlyToolNames|idempotentWriteToolNames|openWorldToolNames|toolDisplayMeta)_part[A-Za-z0-9_]+' | head -1)
    if [ -n "$BAD_LIB" ]; then BIND_STATE="unbound"; break; fi
    # Otherwise empty/non-JSON/transient (relay, warmup, throttle) -- not a proven bind failure; retry.
    echo "  bind-check attempt ${ATTEMPT}/6: no usable catalog yet and no inline-failure signature (relay/warmup/throttle?); retrying in 10s..."
    sleep 10
  done
  if [ "$BIND_STATE" = "unbound" ]; then
    BIND_ERR=$(printf '%s' "$TL_RESP" | jq -r '.error.message? // (.error|strings) // (.error|tojson) // empty' 2>/dev/null || true)
    echo "::error::Post-deploy BIND-CHECK FAILED -- a bundled library LANDED but did NOT inline into the app (${BAD_LIB}() is undefined), so its part-methods are uncallable and EVERY tool is dead. Failing the deploy now instead of running the whole suite against a broken app. tools/list error: ${BIND_ERR:-<none>}"
    exit 1
  elif [ "$BIND_STATE" != "ok" ]; then
    echo "::error::Post-deploy BIND-CHECK could not get a usable tools/list after 6 attempts, with NO library-inline-failure signature -- likely a relay/transport or post-bounce warmup/throttle problem rather than a bind failure. Re-run. Last response: $(printf '%s' "$TL_RESP" | head -c 300)"
    exit 1
  fi
else
  echo "::warning::HUBITAT_HUB_URL / HUBITAT_ACCESS_TOKEN / SERVER_APP_ID not all set -- skipping the post-deploy bind-check (the test runner's own setup still gates)."
fi

echo "Full-repair deploy succeeded via watchdog: ${DEPLOYED_APPS} app(s) + their library bundle(s) live on the hub."
echo "WATCHDOG_DEPLOY_OK apps=${DEPLOYED_APPS} libraries=${#INCLUDES[@]}"
