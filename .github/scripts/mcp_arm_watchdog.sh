#!/usr/bin/env bash
# Arm the standing "E2E Dead-Man Watchdog v2" for THIS run (issue #243 install fix + e2e safety net).
#
# v2 is a STANDALONE on-hub app that doubles as a small MCP server: its dead-man timer auto-restores
# the WHOLE MCP package (the MCP Rule Server app + the child MCP Rule app + every library the server
# #includes) from a known-good File Manager cache if an e2e session bricks the hub and can't disarm.
# Full HPM repair redeploys EVERY manifest app, so the cache must include the child app too. This script
# (1) ensures the v2 watchdog is installed AND has a live runEvery1Minute schedule, (2) BACKS UP main by
# reading each app class + each #include'd library through the WATCHDOG's own hub_get_source (whose
# File-Manager auto-save side effect writes the cache files), assembling the restore manifest, (3) asserts
# the server-app cache is real (>1MB) -- if main is not cached, e2e HALTS -- then (4) writes the manifest-shaped
# armed flag with the ARM_WINDOW_MS deadline (35 min). The matching "Disarm dead-man watchdog (final)"
# always() step flips it to {armed:false, intent:"disarm"}; if a run crashes before that, the watchdog
# fires and restores the package on its own.
#
# CRITICAL: every hub I/O here goes through the WATCHDOG's own MCP endpoint ($WATCHDOG_URL, from the
# WATCHDOG_MCP_URL secret), NOT the main server's $MCP_URL. The watchdog is the second driver of the
# same loopback restore plumbing, so arming/backing-up through it keeps the dead-man path independent
# of the (possibly bricked) main server. Every flag write is READ BACK and asserted -- a cloud 504 can
# no-op a write while returning ambiguously, so we never trust a write we didn't verify. We refuse to
# arm against a missing/truncated app backup (arming with no good restore target is worse than not
# arming at all).
#
# Env:  WATCHDOG_URL          -- full cloud OAuth URL for the WATCHDOG's /mcp endpoint (WATCHDOG_MCP_URL secret)
#       PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#       PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA (the watchdog source lives on the PR head)
#       GITHUB_RUN_ID         -- this run's id (stamped into the flag for traceability)
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint, from secret WATCHDOG_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required -- https://raw.githubusercontent.com/<owner>/<repo>}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required -- 40-hex PR head SHA}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

# The MCP server app's Apps Code CLASS id (resolved by namespace+name). The watchdog's restore targets
# this CODE CLASS id, NOT the running instance id the main-server MCP URL carries.
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

WATCHDOG_NAME="E2E Dead-Man Watchdog v2"
WATCHDOG_SOURCE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/e2e-deadman-watchdog-v2.groovy"
FLAG_FILE="e2e-deadman-v2.json"
ARM_WINDOW_MS=2100000   # 35 minutes -- deliberately > the 30-min job timeout-minutes, so the
                        # watchdog can only fire AFTER GitHub has already killed a hung/dead job,
                        # never mid-live-run. The always() disarm clears it in seconds on every
                        # normal run, so the long window only matters when the session truly dies.

# --- helpers copied verbatim from mcp_deploy_source.sh (the proven cloud-gateway wrappers) -------
# Every call here targets $WATCHDOG_URL (the watchdog's own MCP endpoint), not the main $MCP_URL.

mcp_call() {
  curl -sS --max-time 120 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Prints the inner tool-result text on stdout and returns 0 on a successful tool envelope (text may be
# empty -> the caller's .success / existence gate decides). A JSON-RPC ERROR envelope ({"error":...},
# no .result) is a real hub/protocol failure: surface it loudly and return 1 (do NOT pass it off as
# empty-success text). A non-JSON body (the ~10s cloud-gateway timeout) is retried up to RPC_ATTEMPTS.
RPC_ATTEMPTS=5
RPC_RETRY_SLEEP=6
mcp_tool_call_text() {
  local label="$1" rpc="$2" attempt=1 resp text err
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    resp=$(mcp_call "$rpc" || true)
    if printf '%s' "$resp" | jq -e . >/dev/null 2>&1; then
      err=$(printf '%s' "$resp" | jq -r '.error.message // (.error | strings) // empty' 2>/dev/null || true)
      if [ -n "$err" ] && [ "$err" != "null" ]; then
        echo "::error::${label}: JSON-RPC error envelope from the watchdog: $(printf '%s' "$err" | head -c 200)" >&2
        return 1
      fi
      text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // ""' 2>/dev/null || true)
      printf '%s' "$text"
      return 0
    fi
    echo "::warning::${label}: non-JSON gateway response (attempt ${attempt}/${RPC_ATTEMPTS}; likely the ~10s cloud-gateway timeout). Body head: $(printf '%s' "$resp" | head -c 120 | tr '\n' ' ')" >&2
    attempt=$((attempt + 1))
    [ "$attempt" -le "$RPC_ATTEMPTS" ] && sleep "$RPC_RETRY_SLEEP"
  done
  return 1
}

# --- 1) Resolve the MCP server Apps Code class id (namespace+name match) -------------------------
echo "Looking up Apps Code class ID for $APP_NAMESPACE:$APP_NAME via watchdog hub_list_apps(scope=types)..."
LIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}'
if ! LIST_TEXT=$(mcp_tool_call_text "hub_list_apps (arm class-ID lookup)" "$LIST_RPC"); then
  echo "::error::hub_list_apps: the Hubitat cloud gateway returned a non-JSON response $RPC_ATTEMPTS times -- a transient relay/timeout, NOT a real failure. Re-run the job."
  exit 1
fi
if [ -z "$LIST_TEXT" ]; then
  echo "::error::hub_list_apps returned a valid but empty MCP response -- cannot resolve the Apps Code class id."
  exit 1
fi
CLASS_ID=$(echo "$LIST_TEXT" | jq -r \
  --arg ns "$APP_NAMESPACE" \
  --arg name "$APP_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Apps Code class for $APP_NAMESPACE:$APP_NAME not found via hub_list_apps."
  echo "DEBUG hub_list_apps envelope keys: $(echo "$LIST_TEXT" | jq -r 'keys | join(",")')"
  exit 1
fi
echo "Resolved MCP server class ID: $CLASS_ID"

# The watchdog restores the app from this File Manager file. The watchdog's hub_get_source on a >1MB
# app source writes mcp-source-app-<CLASS_ID>.groovy = the CURRENT full source as a side effect -- a
# reliable hub-side copy (no 1.6MB cloud transfer). We refresh it just below so it is guaranteed
# present + current at arm time, and assemble the manifest's app entry to point at it.
APP_RESTORE_FILE="mcp-source-app-${CLASS_ID}.groovy"

# --- 2) Assert the (manually-installed) watchdog's checkDeadman schedule is live ---------------------
# The watchdog is a one-time MANUAL install by @level99, and the preceding "Watchdog health check" step
# already proved it is installed + serving (tools/list + hub_get_info answered). So we do NOT re-discover
# the instance here -- the /hub2/appsList shape varies by firmware (a plain hub_list_apps(scope=instances)
# jq lookup was brittle). We only assert the runEvery1Minute checkDeadman job survived, so the dead-man
# (and the clean-disarm restore it drives) will actually fire.
watchdog_schedule_alive() {
  local jobs_text
  jobs_text=$(mcp_tool_call_text "hub_get_jobs (watchdog schedule check)" \
    '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_jobs","arguments":{}}}') || return 1
  printf '%s' "$jobs_text" | jq -e \
    '[(.scheduledJobs.jobs // .scheduledJobs // [])[]? | (.method // .name // "")] | any(. == "checkDeadman")' \
    >/dev/null 2>&1
}

echo "Asserting the watchdog's checkDeadman schedule is live (hub_get_jobs)..."
if watchdog_schedule_alive; then
  echo "WATCHDOG_SCHEDULE_ALIVE checkDeadman=scheduled"
else
  # WARN, not HALT: hub_get_jobs is an unverified endpoint (the /hub/scheduledJobs/json shape may differ
  # by firmware), and the disarm-restore poll later is the REAL proof the timer fires. The health check
  # already proved the watchdog is serving, and it was freshly installed (initialize() ran its
  # runEvery1Minute), so proceed to arm rather than block on a possibly-false-negative schedule read.
  echo "::warning::Could not confirm a live 'checkDeadman' scheduled job via hub_get_jobs (the /hub/scheduledJobs/json shape may differ on this firmware). Proceeding to arm -- the disarm-restore poll will surface a genuinely-dead timer. If the dead-man never fires, re-check the watchdog's runEvery1Minute schedule."
fi

# --- 2c) Refresh the hub to CANONICAL main if main changed or the hub drifted -----------------------
# The cache we take below must be canonical main, NOT whatever happens to be live (a prior run could
# have left PR/broken code, or main may have advanced on GitHub). Compare the hub to the BASE repo's
# main on TWO axes: length (did the hub drift?) and SHA (did main change? -- catches a same-length edit
# a length check would miss). If either differs, deploy canonical main through the watchdog (importUrl,
# same as the install) and CONFIRM via a fresh lastSelfDeploy success (works even for a same-length
# change a length poll can't see), then record main's SHA so an UNCHANGED main is skipped next run (no
# needless 1.6MB redeploy -- the cache-and-skip design). MAIN_* absent (older workflow) -> skip silently.
#
# SCOPE: full-package canonical main (PR #247). Before the app drift-check below, this reinstalls main's
# BUNDLE (from main's packageManifest.json at MAIN_SHA) so the #include'd libraries are refreshed to
# canonical main too -- not just the app. That closes the stale-library gap: a main change touching only
# a library is caught (the app would otherwise recompile against stale libs on a coupled change), and the
# cache taken below is the whole canonical-main package. The server APP is then redeployed when it drifted
# (the 1.6MB redeploy stays gated on the length+SHA check), and the CHILD app(s) are refreshed alongside it
# -- full repair: EVERY manifest app must track canonical main, not just the server, so the cache can never
# capture a stale child. MAIN_* absent (older workflow) -> skip.
MAIN_SHA_FILE="mcp-main-deployed-sha.txt"
# main's BUNDLE set (namespace+name as the hub registers them) for the restore-time orphan cleanup + the
# disarm no-stale assertion. Derived from each main bundle .zip's own install.txt (line 1 = namespace,
# line 2 = name) -- the authoritative hub-registered identity -- NOT from listing the hub (a prior run's
# leftover bundle still on the hub would otherwise be misrecorded as "main's" and never cleaned up). "[]"
# when MAIN_* is absent or the manifest/zip can't be read -> the restore cleanup + assertion skip (safe).
MAIN_BUNDLES_JSON="[]"
if [ -n "${MAIN_CHARS:-}" ] && [ -n "${MAIN_SOURCE_URL:-}" ] && [ -n "${MAIN_SHA:-}" ]; then
  # (2c-i) Reinstall main's BUNDLE(s) so the libraries are canonical main, BEFORE the app refresh below
  # recompiles against them. main's bundle URL(s) come from main's packageManifest.json at MAIN_SHA
  # (derived from MAIN_SOURCE_URL); the hub fetches the small zip itself (importUrl). Small + fast
  # (synchronous in the relay window), so it runs every arm -- it also re-establishes main's bundle
  # ENTITY (a prior run's restore removed it), which the bundle-set recording + disarm no-stale check
  # below need. HALTs on a real install failure (cannot establish canonical-main libraries).
  MAIN_RAW_PREFIX="${MAIN_SOURCE_URL%/*}"
  MAIN_PKG_MANIFEST=$(curl -fsSL "${MAIN_RAW_PREFIX}/packageManifest.json" 2>/dev/null || true)
  if [ -n "$MAIN_PKG_MANIFEST" ]; then
    MAIN_BUNDLE_RELS=$(printf '%s' "$MAIN_PKG_MANIFEST" | jq -r '.bundles[]?.location // empty' 2>/dev/null \
      | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
    if [ -n "$MAIN_BUNDLE_RELS" ]; then
      while IFS= read -r BREL; do
        [ -z "$BREL" ] && continue
        BURL="${MAIN_RAW_PREFIX}/${BREL}"
        echo "Refreshing main's bundle onto the hub (canonical-main libraries): ${BURL} ..."
        BIN_RPC=$(jq -nc --arg url "$BURL" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
        if ! BIN_TEXT=$(mcp_tool_call_text "hub_install_bundle (refresh main bundle ${BREL})" "$BIN_RPC"); then
          echo "::error::e2e HALT: main bundle refresh (${BREL}) returned non-JSON ${RPC_ATTEMPTS} times -- transient relay/timeout. Re-run."
          exit 1
        fi
        if [ "$(printf '%s' "$BIN_TEXT" | jq -r '.success // false' 2>/dev/null)" != "true" ]; then
          echo "::error::e2e HALT: main bundle refresh did not report success for ${BREL}. Hub verbatim: $(printf '%s' "$BIN_TEXT" | jq -r '.error // empty' 2>/dev/null). Cannot establish canonical-main libraries."
          exit 1
        fi
        echo "Main bundle ${BREL} (re)installed -- its libraries are canonical main."
        # Record main's bundle identity from the zip's own install.txt (line 1 = namespace, line 2 =
        # name) -- exactly what the hub registers in /hub2/userBundles -- so the restore KEEPS this and
        # deletes everything else (the PR's bundle AND any stale leftover from a prior run). Reading the
        # hub list here instead would misclassify a leftover as main's and never clean it.
        ZIP_TMP="$(mktemp)"
        if curl -fsSL "$BURL" -o "$ZIP_TMP" 2>/dev/null; then
          B_INSTALL_TXT="$(unzip -p "$ZIP_TMP" install.txt 2>/dev/null || true)"
          B_NS="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '1p' | tr -d '[:space:]')"
          B_NM="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '2p' | tr -d '[:space:]')"
          if [ -n "$B_NS" ] && [ -n "$B_NM" ]; then
            MAIN_BUNDLES_JSON="$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -c --arg ns "$B_NS" --arg nm "$B_NM" '. + [{namespace:$ns, name:$nm}]')"
            echo "Recorded main bundle identity ${B_NS}/${B_NM} (from ${BREL} install.txt)."
          else
            # The bundle was just installed (the install above HALTs on failure), so we MUST record its
            # identity: an incomplete keep-set means the disarm no-stale gate can't protect main's bundle
            # and could even flag/delete it as an orphan. A parse miss here is a hard stop, not a warning.
            echo "::error::e2e HALT: installed main bundle ${BREL} but could not parse its install.txt namespace/name -- the restore keep-set would be incomplete. Re-run."
            exit 1
          fi
        else
          echo "::error::e2e HALT: installed main bundle ${BREL} but could not re-download ${BURL} to read its hub-registered identity -- the restore keep-set would be incomplete. Re-run."
          exit 1
        fi
        rm -f "$ZIP_TMP"
      done <<< "$MAIN_BUNDLE_RELS"
    else
      echo "::notice::main packageManifest.json declares no bundles -- app-only canonical main (no bundle to refresh)."
    fi
  else
    # The restore manifest's canonical-main bundle/library set (and the disarm no-stale gate) depend on
    # this fetch; arming app-only here would cache a possibly-stale library baseline. main's
    # packageManifest.json was just reachable (the workflow resolved MAIN_SOURCE_URL from the same repo),
    # so a failure is a transient blip -- HALT and re-run rather than arm a polluted baseline.
    echo "::error::e2e HALT: could not fetch main's packageManifest.json from ${MAIN_RAW_PREFIX} -- cannot establish the canonical-main bundle/library baseline. Refusing to arm a possibly-polluted baseline; re-run."
    exit 1
  fi

  LIVE_MAIN_LEN=$(mcp_tool_call_text "hub_get_source (live main length, noSave)" \
    "$(jq -nc --arg id "$CLASS_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')" \
    | jq -r '.totalLength // empty' 2>/dev/null || true)
  STORED_MAIN_SHA=$(mcp_tool_call_text "hub_read_file (last deployed-main sha)" \
    "$(jq -nc --arg fn "$MAIN_SHA_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')" \
    | jq -r '.content // ""' 2>/dev/null | tr -d '[:space:]' || true)
  # Fast-path skip is parent-only (server length + SHA marker), yet it skips the CHILD refresh too. That
  # is sound because the SHA marker is a WHOLE-PACKAGE token: it is written (line below) only AFTER both
  # the parent AND every child refreshed this run, and the deploy clears it before PR code lands. So a
  # marker that still matches main means the whole package (parent + child) is canonical main -- the child
  # cannot have drifted under a matching marker in normal flow (the disarm restores every manifest app and
  # only re-stamps on a confirmed full restore).
  if [ "$LIVE_MAIN_LEN" = "$MAIN_CHARS" ] && [ "$STORED_MAIN_SHA" = "$MAIN_SHA" ]; then
    echo "Hub already on canonical main (length ${LIVE_MAIN_LEN}, sha ${MAIN_SHA:0:12}) -- no download needed."
  else
    echo "Refreshing hub to canonical main: live length=${LIVE_MAIN_LEN:-<unreadable>} vs main=${MAIN_CHARS}; stored sha=${STORED_MAIN_SHA:-<none>} vs main=${MAIN_SHA}. Deploying ${MAIN_SOURCE_URL} ..."
    # Baseline lastSelfDeploy.at so we can confirm THIS deploy completed (a fresh record) -- the reliable
    # signal for a same-length change. Require the baseline read to succeed (else we can't tell a fresh
    # deploy from a stale record).
    if ! PRE_INFO=$(mcp_tool_call_text "hub_get_info (main-refresh baseline)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}'); then
      echo "::error::e2e HALT: could not read hub_get_info to baseline the main refresh. Re-run."
      exit 1
    fi
    PRE_LSD_AT=$(printf '%s' "$PRE_INFO" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
    DM_RPC=$(jq -nc --arg id "$CLASS_ID" --arg url "$MAIN_SOURCE_URL" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,confirm:true}}}')
    # Fire the deploy ONCE (single-shot mcp_call, NOT the 5x-retry mcp_tool_call_text): the ~1.6MB
    # compile relay-drops the response, and retrying just RE-SENDS the deploy (re-triggering the compile)
    # for ~80s of wasted timeouts + noisy ::warning:: lines. The fresh-lastSelfDeploy poll below is the
    # real confirmation -- it tolerates the dropped response by design.
    mcp_call "$DM_RPC" >/dev/null 2>&1 || true
    DM_LANDED=""
    DM_DEADLINE=$(( $(date +%s) + 420 ))
    while [ "$(date +%s)" -lt "$DM_DEADLINE" ]; do
      sleep 5
      DM_INFO=$(mcp_tool_call_text "hub_get_info (main-refresh poll)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' || true)
      DM_APP=$(printf '%s' "$DM_INFO" | jq -r '.lastSelfDeploy.appId // empty' 2>/dev/null || true)
      DM_AT=$(printf '%s' "$DM_INFO" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
      DM_OK=$(printf '%s' "$DM_INFO" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
      DM_ERR=$(printf '%s' "$DM_INFO" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
      if [ "$DM_APP" = "$CLASS_ID" ] && [ "${DM_AT:-0}" -gt "${PRE_LSD_AT:-0}" ] 2>/dev/null; then
        if [ "$DM_OK" = "false" ]; then
          echo "::error::e2e HALT: canonical main deploy REJECTED by the hub (fresh lastSelfDeploy). Verbatim: ${DM_ERR:-<none>}. Cannot establish a canonical-main baseline."
          exit 1
        fi
        if [ "$DM_OK" = "true" ]; then echo "Canonical main landed (fresh lastSelfDeploy success, at ${DM_AT})."; DM_LANDED="yes"; break; fi
      fi
      echo "  ...main refresh in progress (lsd.at=${DM_AT:-?}/${PRE_LSD_AT}); waiting for a fresh lastSelfDeploy..."
    done
    if [ -z "$DM_LANDED" ]; then
      echo "::error::e2e HALT: canonical main deploy did not confirm within 420s (no fresh lastSelfDeploy success for class $CLASS_ID). Refusing to arm against a non-canonical baseline."
      exit 1
    fi

    # Refresh the CHILD app(s) to canonical main too -- full repair: every manifest app must track main,
    # not just the server parent, or the cache below would capture a stale child. Deploy each non-server
    # app from main's manifest (the hub fetches via importUrl, the same path as the parent), confirmed via
    # a FRESH lastSelfDeploy for THAT app's class (the watchdog records the appId it deployed). The child is
    # ~179KB so it usually returns in the relay window, but we tolerate a dropped response and poll, exactly
    # like the parent. HALT (do NOT record the main SHA) if any child can't be confirmed, so the next run
    # re-evaluates the whole refresh rather than caching a partial-main baseline.
    MAIN_CHILD_RECS=$(printf '%s' "$MAIN_PKG_MANIFEST" | jq -r \
      '.apps[]? | select(.namespace == "mcp" and .name != "MCP Rule Server") | "\(.namespace)\t\(.name)\t\(.location)"' 2>/dev/null || true)
    # The package ships a child app (mcp:MCP Rule), so 0 non-server mcp apps means the filter/manifest
    # drifted. Without this floor the loop body would be skipped, the SHA marker stamped below, and the
    # NEXT run would skip the refresh entirely on the stale marker -- silently caching a stale child. The
    # 3e backup section hard-fails the same way; keep the two in lockstep. (Mirrors that strictness.)
    if [ -z "$MAIN_CHILD_RECS" ]; then
      echo "::error::e2e HALT: main's packageManifest.json declared no non-server mcp app to refresh, but the package ships a child app (mcp:MCP Rule). Filter/manifest drift -- refusing to stamp the canonical-main SHA marker against a server-only refresh."
      exit 1
    fi
    while IFS=$'\t' read -r C_NS C_NAME C_LOC; do
      [ -z "$C_NS" ] && continue
      if [ -z "$C_LOC" ] || [ "$C_LOC" = "null" ]; then
        echo "::error::e2e HALT: main manifest app ${C_NS}:${C_NAME} has no usable location -- cannot refresh it to canonical main. Refusing to arm a partial-main baseline."
        exit 1
      fi
      C_REL=$(printf '%s' "$C_LOC" | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
      C_URL="${MAIN_RAW_PREFIX}/${C_REL}"
      C_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$C_NS" --arg name "$C_NAME" \
        '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
      if [ -z "$C_ID" ] || [ "$C_ID" = "null" ]; then
        echo "::error::e2e HALT: canonical-main refresh needs the Apps Code class for child ${C_NS}:${C_NAME} but it was not found via hub_list_apps. Refusing to arm a partial-main baseline."
        exit 1
      fi
      echo "Refreshing child app ${C_NS}:${C_NAME} (class ${C_ID}) to canonical main: ${C_URL} ..."
      if ! C_PRE_INFO=$(mcp_tool_call_text "hub_get_info (child-refresh baseline)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}'); then
        echo "::error::e2e HALT: could not read hub_get_info to baseline the child refresh (${C_NS}:${C_NAME}). Re-run."
        exit 1
      fi
      C_PRE_AT=$(printf '%s' "$C_PRE_INFO" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
      C_DEP_RPC=$(jq -nc --arg id "$C_ID" --arg url "$C_URL" \
        '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_app",arguments:{appId:$id,importUrl:$url,confirm:true}}}')
      # The child app is small (~179KB) and the deploy goes through the WATCHDOG (a separate app that is
      # NOT recompiled by the child's save), so it usually returns SYNCHRONOUSLY. Capture that response: a
      # synchronous success:false carries the hub's verbatim compile error -- surface it NOW instead of
      # discarding it and re-deriving a generic failure from the poll (or a 300s timeout). A relay-dropped
      # (empty) response falls through to the fresh-lastSelfDeploy poll below.
      C_DEP_RESP=$(mcp_call "$C_DEP_RPC" 2>/dev/null || true)
      C_DEP_TEXT=$(printf '%s' "$C_DEP_RESP" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
      if [ -n "$C_DEP_TEXT" ] && [ "$(printf '%s' "$C_DEP_TEXT" | jq -r '.success // "unknown"' 2>/dev/null || echo unknown)" = "false" ]; then
        echo "::error::e2e HALT: canonical-main child deploy (${C_NS}:${C_NAME}) REJECTED synchronously by the hub. Verbatim: $(printf '%s' "$C_DEP_TEXT" | jq -r '.error // .errorMessage // .message // "<none>"' 2>/dev/null || echo '<none>')."
        exit 1
      fi
      C_LANDED=""
      C_DEADLINE=$(( $(date +%s) + 300 ))
      while [ "$(date +%s)" -lt "$C_DEADLINE" ]; do
        sleep 5
        C_INFO=$(mcp_tool_call_text "hub_get_info (child-refresh poll)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' || true)
        C_APP=$(printf '%s' "$C_INFO" | jq -r '.lastSelfDeploy.appId // empty' 2>/dev/null || true)
        C_AT=$(printf '%s' "$C_INFO" | jq -r '(.lastSelfDeploy.at // 0) | floor' 2>/dev/null || echo 0)
        C_OK=$(printf '%s' "$C_INFO" | jq -r '.lastSelfDeploy.success // empty' 2>/dev/null || true)
        C_ERR=$(printf '%s' "$C_INFO" | jq -r '.lastSelfDeploy.error // empty' 2>/dev/null || true)
        if [ "$C_APP" = "$C_ID" ] && [ "${C_AT:-0}" -gt "${C_PRE_AT:-0}" ] 2>/dev/null; then
          if [ "$C_OK" = "false" ]; then
            echo "::error::e2e HALT: canonical-main child deploy (${C_NS}:${C_NAME}) REJECTED by the hub (fresh lastSelfDeploy). Verbatim: ${C_ERR:-<none>}."
            exit 1
          fi
          if [ "$C_OK" = "true" ]; then echo "Child app ${C_NS}:${C_NAME} on canonical main (fresh lastSelfDeploy, at ${C_AT})."; C_LANDED="yes"; break; fi
        fi
        echo "  ...child refresh in progress (lsd.appId=${C_APP:-?} at=${C_AT:-?}/${C_PRE_AT}); waiting for a fresh lastSelfDeploy..."
      done
      if [ -z "$C_LANDED" ]; then
        echo "::error::e2e HALT: canonical-main child deploy (${C_NS}:${C_NAME}) did not confirm within 300s. Refusing to arm a partial-main baseline."
        exit 1
      fi
    done <<< "$MAIN_CHILD_RECS"

    mcp_tool_call_text "hub_write_file (record deployed-main sha)" \
      "$(jq -nc --arg fn "$MAIN_SHA_FILE" --arg c "$MAIN_SHA" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')" >/dev/null \
      || echo "::warning::could not record the deployed-main SHA; next run will re-evaluate by length/sha. Continuing."
    echo "WATCHDOG_MAIN_REFRESHED sha=${MAIN_SHA} chars=${MAIN_CHARS}"
  fi
else
  echo "::notice::MAIN_* not provided -- skipping the canonical-main refresh (the cache will be whatever is live at arm)."
fi

# main's BUNDLE set for the restore cleanup was derived above from each bundle zip's own install.txt
# (the hub-registered namespace+name), so it is immune to a prior run's leftover bundle still on the hub.
echo "Main bundle set for restore cleanup (from main's bundle manifests): ${MAIN_BUNDLES_JSON}"

# --- 3) BACK UP main: cache the app + each #include'd library, assembling the restore manifest -----
# The watchdog's hub_get_source(type,id) writes mcp-source-<type>-<id>.groovy = the current
# (canonical main, refreshed just above) source as a File-Manager side effect, returning .sourceFile when
# it cached. We back up the APP first, then resolve its #include directives to library ids and back up
# each library, so the manifest restores libraries-first/app-last exactly like restorePackage().

# 3a) Refresh + cache the APP source. The >64KB total triggers the File-Manager auto-save (full body
# saved hub-side, no 1.6MB cloud transfer), and length:65000 (the tool caps the returned chunk at
# 64000) returns the source HEAD so 3c can parse
# the #include directives (which live at the top of the file) from this same call.
echo "Backing up the MCP app: watchdog hub_get_source(type=app, id=$CLASS_ID)..."
APP_SRC_RPC=$(jq -nc --arg id "$CLASS_ID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:65000}}}')
if ! APP_SRC_TEXT=$(mcp_tool_call_text "hub_get_source (backup MCP app)" "$APP_SRC_RPC"); then
  echo "::error::hub_get_source(app $CLASS_ID) returned non-JSON $RPC_ATTEMPTS times -- could not back up the app source. Re-run the job."
  exit 1
fi
APP_SRC_OK=$(echo "$APP_SRC_TEXT" | jq -r '.success // false')
APP_SRC_FILE=$(echo "$APP_SRC_TEXT" | jq -r '.sourceFile // empty')
APP_SRC_CHUNK=$(echo "$APP_SRC_TEXT" | jq -r '.source // ""')
if [ "$APP_SRC_OK" != "true" ] || [ "$APP_SRC_FILE" != "$APP_RESTORE_FILE" ]; then
  echo "::error::hub_get_source(app $CLASS_ID) did not write the expected app cache (success=$APP_SRC_OK sourceFile=$APP_SRC_FILE, expected $APP_RESTORE_FILE). Response: $(printf '%s' "$APP_SRC_TEXT" | head -c 600)"
  exit 1
fi

# 3b) Assert the APP cache is real (>1MB). THIS IS THE HALT GATE: if main is not cached, the dead-man
# has nothing to restore from, so e2e must NOT proceed -- exit 1.
echo "Asserting app backup '$APP_RESTORE_FILE' exists and is >1MB (HALT gate)..."
READ_APP_RPC=$(jq -nc --arg fn "$APP_RESTORE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! APP_BACKUP_TEXT=$(mcp_tool_call_text "hub_read_file (app backup existence check)" "$READ_APP_RPC"); then
  echo "::error::hub_read_file('$APP_RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
  exit 1
fi
APP_BACKUP_LEN=$(echo "$APP_BACKUP_TEXT" | jq -r '.totalLength // 0')
if ! [ "$APP_BACKUP_LEN" -gt 1000000 ] 2>/dev/null; then
  echo "::error::e2e HALT: main is not cached -- app backup '$APP_RESTORE_FILE' is missing or too small (totalLength=$APP_BACKUP_LEN, need >1000000). The dead-man would have no good app to restore. Refusing to arm and halting e2e."
  exit 1
fi
echo "App backup '$APP_RESTORE_FILE' present (totalLength=$APP_BACKUP_LEN bytes)."

# 3c) Parse the app's #include directives (namespace.Name) so we back up exactly the libraries main
# depends on. #include directives live at the TOP of the file (before/around the definition block),
# so the ~64000-char head fetched in 3a (APP_SRC_CHUNK) carries all of them -- no full-file read needed.
echo "Parsing #include directives from the app source head..."
APP_FULL_SRC="$APP_SRC_CHUNK"
# tokens like:  #include mcp.McpSomeLib   -> "mcp.McpSomeLib"
mapfile -t INCLUDE_TOKENS < <(printf '%s\n' "$APP_FULL_SRC" \
  | grep -oE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' \
  | sed -E 's/^[[:space:]]*#include[[:space:]]+//' \
  | sort -u)
echo "Found ${#INCLUDE_TOKENS[@]} #include directive(s): ${INCLUDE_TOKENS[*]:-<none>}"

# Resolve namespace.Name -> library id via watchdog hub_list_libraries.
LIBS_TEXT=""
if [ "${#INCLUDE_TOKENS[@]}" -gt 0 ]; then
  LIBLIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}'
  if ! LIBLIST_TEXT=$(mcp_tool_call_text "hub_list_libraries (resolve include ids)" "$LIBLIST_RPC"); then
    echo "::error::hub_list_libraries returned non-JSON $RPC_ATTEMPTS times -- could not resolve the app's #include libraries to back up. Re-run the job."
    exit 1
  fi
fi

# 3d) Back up each #include'd library: cache it via hub_get_source(library,id), then assert the cache
# file landed. Three-way: a >64KB library auto-saves to .sourceFile; a <=64KB library comes back inline
# with NO auto-save, so we write the cache file explicitly via hub_write_file; a >64KB library that did
# NOT auto-save (can't cache in one inline read) is a HALT.
LIB_MANIFEST_JSON="[]"   # JSON array of {id,file} accumulated for the manifest
for TOKEN in "${INCLUDE_TOKENS[@]:-}"; do
  [ -z "$TOKEN" ] && continue
  NS="${TOKEN%%.*}"
  NM="${TOKEN#*.}"
  LIB_ID=$(echo "$LIBLIST_TEXT" | jq -r \
    --arg ns "$NS" --arg nm "$NM" \
    '.libraries[]? | select((.namespace == $ns) and (.name == $nm)) | .id' | head -n1)
  if [ -z "$LIB_ID" ] || [ "$LIB_ID" = "null" ]; then
    echo "::error::e2e HALT: the app #includes '$TOKEN' but no installed library matches namespace=$NS name=$NM (hub_list_libraries). Cannot back up the package; refusing to arm."
    exit 1
  fi
  LIB_RESTORE_FILE="mcp-source-library-${LIB_ID}.groovy"
  echo "Backing up library $TOKEN (id=$LIB_ID): watchdog hub_get_source(type=library, id=$LIB_ID)..."
  # Read the library source inline. Small libraries (<=64KB) come back in .source with NO File-Manager
  # auto-save, so we write the cache file explicitly; large ones (>64KB) auto-save to .sourceFile.
  LIB_SRC_RPC=$(jq -nc --arg id "$LIB_ID" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"library",id:$id,offset:0,length:64000}}}')
  if ! LIB_SRC_TEXT=$(mcp_tool_call_text "hub_get_source (backup library $LIB_ID)" "$LIB_SRC_RPC"); then
    echo "::error::hub_get_source(library $LIB_ID) returned non-JSON $RPC_ATTEMPTS times -- could not back up library $TOKEN. Re-run the job."
    exit 1
  fi
  LIB_SRC_OK=$(echo "$LIB_SRC_TEXT" | jq -r '.success // false')
  if [ "$LIB_SRC_OK" != "true" ]; then
    echo "::error::e2e HALT: hub_get_source(library $LIB_ID) failed (success=$LIB_SRC_OK). Cannot back up '$TOKEN'; refusing to arm. Response: $(printf '%s' "$LIB_SRC_TEXT" | head -c 600)"
    exit 1
  fi
  LIB_SRC_FILE=$(echo "$LIB_SRC_TEXT" | jq -r '.sourceFile // empty')
  LIB_TOTLEN=$(echo "$LIB_SRC_TEXT" | jq -r '.totalLength // 0')
  if [ "$LIB_SRC_FILE" = "$LIB_RESTORE_FILE" ]; then
    echo "Library $LIB_ID is large (>64KB); auto-saved to '$LIB_RESTORE_FILE'."
  elif [ "$LIB_TOTLEN" -gt 64000 ] 2>/dev/null; then
    echo "::error::e2e HALT: library $LIB_ID is >64KB (totalLength=$LIB_TOTLEN) but did not auto-save and the inline read caps at 64000 -- cannot cache it in one call. Refusing to arm."
    exit 1
  else
    # Small library: not auto-saved. Write the inline source to the cache file so the watchdog's
    # restoreLibrary has a file to read. (This is the fix for libraries below the 64KB auto-save floor.)
    LIB_SRC_BODY=$(echo "$LIB_SRC_TEXT" | jq -r '.source // ""')
    WRITE_LIB_RPC=$(jq -nc --arg fn "$LIB_RESTORE_FILE" --arg c "$LIB_SRC_BODY" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')
    if ! WRITE_LIB_TEXT=$(mcp_tool_call_text "hub_write_file (cache small library $LIB_ID)" "$WRITE_LIB_RPC"); then
      echo "::error::hub_write_file('$LIB_RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not cache library $TOKEN. Re-run the job."
      exit 1
    fi
    if [ "$(echo "$WRITE_LIB_TEXT" | jq -r '.success // false')" = "false" ]; then
      echo "::error::e2e HALT: hub_write_file('$LIB_RESTORE_FILE') reported failure caching library $TOKEN. Refusing to arm. Response: $(printf '%s' "$WRITE_LIB_TEXT" | head -c 400)"
      exit 1
    fi
    echo "Library $LIB_ID is small; cached to '$LIB_RESTORE_FILE' via hub_write_file (${LIB_TOTLEN} chars)."
  fi
  # Confirm the cache actually exists and is non-trivial (>20 chars is the watchdog restore floor).
  READ_LIB_RPC=$(jq -nc --arg fn "$LIB_RESTORE_FILE" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
  if ! LIB_BACKUP_TEXT=$(mcp_tool_call_text "hub_read_file (library backup existence check)" "$READ_LIB_RPC"); then
    echo "::error::hub_read_file('$LIB_RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
    exit 1
  fi
  LIB_BACKUP_LEN=$(echo "$LIB_BACKUP_TEXT" | jq -r '.totalLength // 0')
  if ! [ "$LIB_BACKUP_LEN" -gt 20 ] 2>/dev/null; then
    echo "::error::e2e HALT: library backup '$LIB_RESTORE_FILE' is missing or too small (totalLength=$LIB_BACKUP_LEN, need >20). Refusing to arm against an unrestorable library."
    exit 1
  fi
  echo "Library backup '$LIB_RESTORE_FILE' present (totalLength=$LIB_BACKUP_LEN bytes)."
  # Carry namespace+name so restorePackage can re-resolve the library's CURRENT id from live hub state
  # (the deploy may delete+recreate a bundle-managed library, changing its id out from under this manifest).
  LIB_MANIFEST_JSON=$(jq -nc \
    --argjson acc "$LIB_MANIFEST_JSON" \
    --arg id "$LIB_ID" \
    --arg file "$LIB_RESTORE_FILE" \
    --arg ns "$NS" \
    --arg name "$NM" \
    '$acc + [{id:$id, file:$file, namespace:$ns, name:$name}]')
done

# 3e) Back up the CHILD app (mcp:MCP Rule). Full HPM repair redeploys EVERY manifest app, so the restore
# must cache + restore the child too -- otherwise a PR that changes the child app would leave the PR's
# child live on the hub after the run (an incomplete restore). Resolve its class id from the same
# hub_list_apps response, cache it (the child is >64KB so hub_get_source auto-saves the full body), and
# assert the cache landed. HALT if it can't be cached: arming without it means an unrestorable child app.
CHILD_NAMESPACE="mcp"
CHILD_NAME="MCP Rule"
CHILD_CLASS_ID=$(echo "$LIST_TEXT" | jq -r --arg ns "$CHILD_NAMESPACE" --arg name "$CHILD_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
if [ -z "$CHILD_CLASS_ID" ] || [ "$CHILD_CLASS_ID" = "null" ]; then
  echo "::error::e2e HALT: child Apps Code class for $CHILD_NAMESPACE:$CHILD_NAME not found via hub_list_apps -- full repair redeploys it, so it must be cached to restore. Refusing to arm."
  exit 1
fi
CHILD_RESTORE_FILE="mcp-source-app-${CHILD_CLASS_ID}.groovy"
echo "Backing up the child app $CHILD_NAMESPACE:$CHILD_NAME (id=$CHILD_CLASS_ID): watchdog hub_get_source(type=app, id=$CHILD_CLASS_ID)..."
CHILD_SRC_RPC=$(jq -nc --arg id "$CHILD_CLASS_ID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:65000}}}')
if ! CHILD_SRC_TEXT=$(mcp_tool_call_text "hub_get_source (backup child app)" "$CHILD_SRC_RPC"); then
  echo "::error::hub_get_source(app $CHILD_CLASS_ID) returned non-JSON $RPC_ATTEMPTS times -- could not back up the child app. Re-run the job."
  exit 1
fi
CHILD_SRC_OK=$(echo "$CHILD_SRC_TEXT" | jq -r '.success // false')
CHILD_SRC_FILE=$(echo "$CHILD_SRC_TEXT" | jq -r '.sourceFile // empty')
if [ "$CHILD_SRC_OK" != "true" ] || [ "$CHILD_SRC_FILE" != "$CHILD_RESTORE_FILE" ]; then
  echo "::error::e2e HALT: hub_get_source(app $CHILD_CLASS_ID) did not write the expected child cache (success=$CHILD_SRC_OK sourceFile=$CHILD_SRC_FILE, expected $CHILD_RESTORE_FILE). Response: $(printf '%s' "$CHILD_SRC_TEXT" | head -c 600)"
  exit 1
fi
# Assert the child cache is real (>1000 chars). The child app is ~179KB, far above this floor; it is not
# the >1MB brick-critical server parent, so this gate is a sanity floor, not the dead-man HALT gate.
READ_CHILD_RPC=$(jq -nc --arg fn "$CHILD_RESTORE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! CHILD_BACKUP_TEXT=$(mcp_tool_call_text "hub_read_file (child app backup existence check)" "$READ_CHILD_RPC"); then
  echo "::error::hub_read_file('$CHILD_RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
  exit 1
fi
CHILD_BACKUP_LEN=$(echo "$CHILD_BACKUP_TEXT" | jq -r '.totalLength // 0')
if ! [ "$CHILD_BACKUP_LEN" -gt 1000 ] 2>/dev/null; then
  echo "::error::e2e HALT: child app backup '$CHILD_RESTORE_FILE' is missing or too small (totalLength=$CHILD_BACKUP_LEN, need >1000). Refusing to arm against an unrestorable child app."
  exit 1
fi
echo "Child app backup '$CHILD_RESTORE_FILE' present (totalLength=$CHILD_BACKUP_LEN bytes)."

# Assemble the restore manifest: {app:{classId,file}, apps:[{classId,file},...],
# libraries:[{id,file,namespace,name}], bundles:[{namespace,name}]} -- the exact shape restorePackage()
# reads (drop the PR's stale bundle, restore libraries first, then EVERY app in `apps`, drop the PR's
# stale libraries; namespace/name let it re-resolve a library id the deploy changed via delete+recreate,
# and identify main's bundles vs the PR's). `apps` lists the server parent + the child app (full repair
# redeploys both, so both must restore); the singular `app` is kept for the flag-readback assertion and
# back-compat -- restorePackage prefers `apps` when present.
MANIFEST_JSON=$(jq -nc \
  --arg classId "$CLASS_ID" \
  --arg appFile "$APP_RESTORE_FILE" \
  --arg mainChars "$APP_BACKUP_LEN" \
  --arg childClassId "$CHILD_CLASS_ID" \
  --arg childFile "$CHILD_RESTORE_FILE" \
  --arg childChars "$CHILD_BACKUP_LEN" \
  --argjson libs "$LIB_MANIFEST_JSON" \
  --argjson bundles "${MAIN_BUNDLES_JSON:-[]}" \
  '{app:{classId:$classId, file:$appFile, mainChars:$mainChars},
    apps:[{classId:$classId, file:$appFile, mainChars:$mainChars},
          {classId:$childClassId, file:$childFile, mainChars:$childChars}],
    libraries:$libs, bundles:$bundles}')
echo "Assembled restore manifest: $MANIFEST_JSON"

# --- 4) Write the manifest-shaped armed flag, then READ IT BACK and assert ------------------------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; this stays portable (the second-
# granularity is irrelevant for a 35-minute deadline). armPrSha records the PR head SHA being tested
# this run -- a forensic breadcrumb on a fire. It is NOT the SHA of the cached source (that is whatever
# was live on the hub at arm time; its real revision is unknown).
DEADLINE_MS=$(( $(date +%s) * 1000 + ARM_WINDOW_MS ))
FLAG_JSON=$(jq -nc \
  --argjson deadline "$DEADLINE_MS" \
  --arg runId "$GITHUB_RUN_ID" \
  --arg armPrSha "$PR_HEAD_SHA_RESOLVED" \
  --arg canonicalMainSha "${MAIN_SHA:-}" \
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:true, deadline:$deadline, runId:$runId, intent:"arm", armPrSha:$armPrSha, canonicalMainSha:$canonicalMainSha, manifest:$manifest}')

echo "Writing armed flag to '$FLAG_FILE' (deadline=$DEADLINE_MS, ~35min)..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (arm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm write. Re-run the job."
  exit 1
fi

# Run-scope the deferred-native-rule list (PR #251): reset it to [] at arm so a PRIOR run's stale instance
# ids can never be reaped by THIS run's disarm sweep. The test step overwrites it with THIS run's ids only
# when E2E_DEFER_NATIVE_DELETES is set; absent that, an empty list is the correct no-op. Best-effort -- a
# failure must not block the arm (the disarm sweep deletes only exact ids + the --cleanup-only prefix sweep
# backstops, so a stale list is harmless beyond a no-op re-delete).
DEFERRED_RULES_FILE="e2e-deferred-native-rules.json"
mcp_tool_call_text "hub_write_file (reset deferred-rule list)" \
  "$(jq -nc --arg fn "$DEFERRED_RULES_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:"[]",confirm:true}}}')" >/dev/null 2>&1 \
  || echo "::warning::Could not reset ${DEFERRED_RULES_FILE} at arm (non-fatal -- exact-id sweep + prefix backstop guard against stale ids)."

# Read-back-and-assert: a cloud 504 can no-op a write while returning ambiguously. We assert
# armed==true AND the manifest's app.classId round-tripped (the restore target landed intact).
echo "Reading the flag back to confirm it armed..."
READBACK_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! READBACK_TEXT=$(mcp_tool_call_text "hub_read_file (arm read-back)" "$READBACK_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') read-back returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm landed. Re-run the job."
  exit 1
fi
RB_ARMED=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .armed' 2>/dev/null || echo "")
RB_CLASS=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .manifest.app.classId' 2>/dev/null || echo "")
if [ "$RB_ARMED" != "true" ] || [ "$RB_CLASS" != "$CLASS_ID" ]; then
  echo "::error::Arm flag did not land as expected (read back armed=$RB_ARMED manifest.app.classId=$RB_CLASS, expected armed=true classId=$CLASS_ID). The write may have silently no-opped. Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

echo "WATCHDOG_ARMED classId=$CLASS_ID deadline=$DEADLINE_MS runId=$GITHUB_RUN_ID appBackup=$APP_RESTORE_FILE libCount=$(echo "$LIB_MANIFEST_JSON" | jq 'length')"
