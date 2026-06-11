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
# 12x10s (~2+ min of retry window): the arm is PRE-test, off the suite's critical path, and a
# post-teardown async-recompile blackout can swallow 60-130s mid-arm (observed live) -- a healthy
# hub answers on attempt 1, so the extra patience costs nothing when things are fine.
RPC_ATTEMPTS=12
RPC_RETRY_SLEEP=10
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

# --- 0) Hub-readiness gate ------------------------------------------------------------------------
# Back-to-back runs can land the arm inside a post-teardown blackout: the previous run's bundle
# restore queues ASYNC dependent-recompile work that can briefly stop the hub answering anything
# (observed live: ~70+s of 'No response from hub' starting minutes after the restore marker matched,
# swallowing the arm's 5x6s retry budget). One cheap poll on a healthy hub; ride out a blackout
# otherwise. HALT only if the hub stays dark for ~5 minutes -- that is a real outage, not settling.
echo "Hub-readiness gate: polling the watchdog before the arm's hub work..."
READY=""
for attempt in $(seq 1 30); do
  if mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' 2>/dev/null \
      | jq -e '.result.content[0].text // empty' >/dev/null 2>&1; then
    READY="yes"
    echo "  Watchdog responsive (attempt ${attempt})."
    break
  fi
  echo "  ...hub not answering (attempt ${attempt}/30); waiting 10s (post-teardown recompile settling)"
  sleep 10
done
if [ -z "$READY" ]; then
  echo "::error::e2e HALT: the watchdog endpoint stayed unresponsive for ~5 minutes -- a real outage, not post-teardown settling. Investigate."
  exit 1
fi

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
# main's BUNDLE set (namespace+name as the hub registers them) for the restore-time orphan cleanup + the
# disarm no-stale assertion. Derived from each main bundle .zip's own install.txt (line 1 = namespace,
# line 2 = name) -- the authoritative hub-registered identity -- NOT from listing the hub (a prior run's
# leftover bundle still on the hub would otherwise be misrecorded as "main's" and never cleaned up). "[]"
# when MAIN_* is absent or the manifest/zip can't be read -> the restore cleanup + assertion skip (safe).
MAIN_BUNDLES_JSON="[]"
if [ -n "${MAIN_CHARS:-}" ] && [ -n "${MAIN_SOURCE_URL:-}" ] && [ -n "${MAIN_SHA:-}" ]; then
  # THE ARM NEVER INSTALLS ANYTHING PRE-RUN (maintainer's flow): it only guarantees canonical main's
  # FILES are cached on the hub -- bundle zip(s) below, app + child sources further down, all fetched
  # by the hub from GitHub raw at MAIN_SHA via hub_cache_url_to_file. The PR deploy fully overwrites
  # whatever is live (HPM repair), and the teardown restore installs cached main the same way -- so a
  # hub that is NOT currently on main (e.g. a prior failed restore) needs no pre-run fixing, and the
  # old pre-run main refresh (bundle + parent + child installs = several ~1.8MB recompiles before the
  # PR even landed) is gone entirely. Cache freshness is one marker: the MAIN_SHA the cache files
  # were taken from.
  CACHE_SHA_FILE="mcp-main-cache-sha.txt"
  STORED_CACHE_SHA=$(mcp_tool_call_text "hub_read_file (main-cache sha marker)" \
    "$(jq -nc --arg fn "$CACHE_SHA_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')" \
    | jq -r '.content // ""' 2>/dev/null | tr -d '[:space:]' || true)
  CACHE_FRESH="false"
  if [ "$STORED_CACHE_SHA" = "$MAIN_SHA" ]; then CACHE_FRESH="true"; fi
  echo "Main cache marker: stored=${STORED_CACHE_SHA:-<none>} main=${MAIN_SHA:0:12} -> fresh=${CACHE_FRESH}"

  # (2c-i) Ensure main's BUNDLE zip(s) are CACHED on the hub (no install -- the restore installs).
  # main's bundle URL(s) come from main's packageManifest.json at MAIN_SHA.
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
        # Identity first (CI-side download; no hub impact): the zip's own install.txt (line 1 =
        # namespace, line 2 = name) is exactly what the hub registers in /hub2/userBundles -- the
        # restore KEEPS this identity and deletes everything else. Reading the hub list instead would
        # misclassify a leftover as main's and never clean it.
        ZIP_TMP="$(mktemp)"
        if ! curl -fsSL "$BURL" -o "$ZIP_TMP" 2>/dev/null; then
          echo "::error::e2e HALT: could not download ${BURL} to read main's bundle identity -- the restore keep-set would be incomplete. Re-run."
          exit 1
        fi
        B_INSTALL_TXT="$(unzip -p "$ZIP_TMP" install.txt 2>/dev/null || true)"
        B_NS="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '1p' | tr -d '[:space:]')"
        B_NM="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '2p' | tr -d '[:space:]')"
        rm -f "$ZIP_TMP"
        if [ -z "$B_NS" ] || [ -z "$B_NM" ]; then
          echo "::error::e2e HALT: could not parse install.txt namespace/name from ${BREL} -- the restore keep-set would be incomplete. Re-run."
          exit 1
        fi
        # Record the bundle identity + its CANONICAL https URL at MAIN_SHA. No zip caching, no
        # arm-time install: the restore installs straight from this URL -- the exact HPM repair
        # path. (A hub-local /local/ URL was tried and is BANNED: registering the hub's own
        # loopback URL as a bundle source is off the platform's tested path and coincided with
        # /hub2/userLibraries wedging hub-wide.)
        MAIN_BUNDLES_JSON="$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -c --arg ns "$B_NS" --arg nm "$B_NM" --arg url "$BURL" '. + [{namespace:$ns, name:$nm, url:$url}]')"
        echo "Recorded main bundle ${B_NS}/${B_NM} -> restore installs from ${BURL}"
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

  # (2c-ii) Cache canonical main's APP sources from GitHub raw at MAIN_SHA -- NO hub install. The hub
  # fetches each file itself (hub_cache_url_to_file), so the cache is byte-exact main regardless of
  # what is currently live on the hub. Skipped entirely when the cache marker already matches
  # MAIN_SHA and the files exist (asserted in section 3 below). The marker is written ONLY after
  # every file (bundle zips above + parent + children here) cached successfully, so a partial cache
  # can never masquerade as fresh.
  if [ "$CACHE_FRESH" = "true" ]; then
    echo "Main source cache already at ${MAIN_SHA:0:12} (marker match) -- no downloads needed."
  else
    echo "Caching canonical main's app sources from GitHub at ${MAIN_SHA:0:12} (no hub install)..."
    APP_CACHE_RPC=$(jq -nc --arg url "$MAIN_SOURCE_URL" --arg fn "$APP_RESTORE_FILE"       '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_cache_url_to_file",arguments:{url:$url,fileName:$fn,confirm:true}}}')
    if ! APP_CACHE_TEXT=$(mcp_tool_call_text "hub_cache_url_to_file (cache main parent app)" "$APP_CACHE_RPC"); then
      echo "::error::e2e HALT: could not cache main's parent app source on the hub (non-JSON ${RPC_ATTEMPTS} times). Re-run."
      exit 1
    fi
    if [ "$(printf '%s' "$APP_CACHE_TEXT" | jq -r '.success // false' 2>/dev/null)" != "true" ]; then
      echo "::error::e2e HALT: hub_cache_url_to_file did not report success for the parent app: $(printf '%s' "$APP_CACHE_TEXT" | head -c 300)."
      exit 1
    fi
    echo "Cached main parent app -> ${APP_RESTORE_FILE} ($(printf '%s' "$APP_CACHE_TEXT" | jq -r '.byteLength') bytes)."

    # Cache every NON-server mcp app (the child) the same way. The class id comes from the same
    # hub_list_apps types listing the arm already fetched; the cache file name must match what
    # section 3e records in the manifest (mcp-source-app-<classId>.groovy).
    MAIN_CHILD_RECS=$(printf '%s' "$MAIN_PKG_MANIFEST" | jq -r       '.apps[]? | select(.namespace == "mcp" and .name != "MCP Rule Server") | "\(.namespace)	\(.name)	\(.location)"' 2>/dev/null || true)
    if [ -z "$MAIN_CHILD_RECS" ]; then
      echo "::error::e2e HALT: main's packageManifest.json declared no non-server mcp app, but the package ships a child app (mcp:MCP Rule). Filter/manifest drift -- refusing to arm a partial cache."
      exit 1
    fi
    while IFS=$'	' read -r C_NS C_NAME C_LOC; do
      [ -z "$C_NS" ] && continue
      if [ -z "$C_LOC" ] || [ "$C_LOC" = "null" ]; then
        echo "::error::e2e HALT: main manifest app ${C_NS}:${C_NAME} has no usable location -- cannot cache it. Refusing to arm a partial cache."
        exit 1
      fi
      C_REL=$(printf '%s' "$C_LOC" | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
      C_URL="${MAIN_RAW_PREFIX}/${C_REL}"
      C_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$C_NS" --arg name "$C_NAME"         '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
      if [ -z "$C_ID" ] || [ "$C_ID" = "null" ]; then
        echo "::error::e2e HALT: no Apps Code class for child ${C_NS}:${C_NAME} via hub_list_apps -- cannot name its cache file. Refusing to arm a partial cache."
        exit 1
      fi
      C_CACHE_RPC=$(jq -nc --arg url "$C_URL" --arg fn "mcp-source-app-${C_ID}.groovy"         '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_cache_url_to_file",arguments:{url:$url,fileName:$fn,confirm:true}}}')
      if ! C_CACHE_TEXT=$(mcp_tool_call_text "hub_cache_url_to_file (cache main child ${C_NAME})" "$C_CACHE_RPC"); then
        echo "::error::e2e HALT: could not cache main child app ${C_NS}:${C_NAME} (non-JSON ${RPC_ATTEMPTS} times). Re-run."
        exit 1
      fi
      if [ "$(printf '%s' "$C_CACHE_TEXT" | jq -r '.success // false' 2>/dev/null)" != "true" ]; then
        echo "::error::e2e HALT: hub_cache_url_to_file did not report success for child ${C_NS}:${C_NAME}: $(printf '%s' "$C_CACHE_TEXT" | head -c 300)."
        exit 1
      fi
      echo "Cached main child ${C_NS}:${C_NAME} -> mcp-source-app-${C_ID}.groovy ($(printf '%s' "$C_CACHE_TEXT" | jq -r '.byteLength') bytes)."
    done <<< "$MAIN_CHILD_RECS"

    # ALL cache files (bundle zips + parent + children) landed -- stamp the cache marker.
    mcp_tool_call_text "hub_write_file (record main-cache sha)"       "$(jq -nc --arg fn "$CACHE_SHA_FILE" --arg c "$MAIN_SHA" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')" >/dev/null       || echo "::warning::could not record the main-cache SHA; the next arm re-caches (harmless, just slower)."
    echo "WATCHDOG_MAIN_CACHED sha=${MAIN_SHA} chars=${MAIN_CHARS}"
  fi
else
  echo "::error::e2e HALT: MAIN_SHA/MAIN_SOURCE_URL/MAIN_CHARS not provided -- the cache is GitHub-direct now, so without them there is nothing to cache and the dead-man would have no restore source. (The workflow's compute step always provides them; their absence is a workflow bug.)"
  exit 1
fi

# main's BUNDLE set for the restore cleanup was derived above from each bundle zip's own install.txt
# (the hub-registered namespace+name), so it is immune to a prior run's leftover bundle still on the hub.
echo "Main bundle set for restore cleanup (from main's bundle manifests): ${MAIN_BUNDLES_JSON}"

# --- 3) Assert the cache + assemble the restore manifest --------------------------------------------
# The cache itself was taken in 2c (GitHub-direct via hub_cache_url_to_file -- byte-exact canonical
# main regardless of what is live on the hub). This section only ASSERTS the files are real and
# assembles the manifest restorePackage() consumes.

# 3a) Parse main's #include directives from a CI-side head fetch of the canonical source (the
# directives live at the top of the file; a ranged GET keeps it cheap). These are MAIN's includes --
# the restore keep-set -- so they must come from MAIN_SOURCE_URL, never the PR checkout.
APP_SRC_CHUNK=$(curl -fsSL -r 0-65000 "$MAIN_SOURCE_URL" 2>/dev/null || true)
if [ -z "$APP_SRC_CHUNK" ]; then
  echo "::error::e2e HALT: could not fetch main's source head from ${MAIN_SOURCE_URL} to parse its #include set. Re-run."
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

# 3d) Record each #include'd library for the manifest. Since the restore is BUNDLE-driven (the
# cached main zip from 2c-i delivers every library in one install), the arm no longer caches each
# library's SOURCE individually -- that was 1-2 extra calls + a File-Manager write per library per
# run, and the per-library restore it fed recompiled the ~1.8MB dependent app once per write (the
# load profile that tripped the platform's per-app limiter). The manifest still records id +
# namespace + name: the keep-set for the stale-library reconcile, and the id re-resolution for the
# legacy fallback. Source caching (file:) happens ONLY when no bundle cache exists (a main with
# #includes but no bundle), where the legacy per-library restore is still the only path.
HAVE_BUNDLE_CACHE=$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -r 'map(select(.url)) | length > 0')
LIB_MANIFEST_JSON="[]"   # JSON array of {id,namespace,name[,file]} accumulated for the manifest
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
  if [ "$HAVE_BUNDLE_CACHE" = "true" ]; then
    echo "Library $TOKEN (id=$LIB_ID) recorded for the manifest (restored via the cached main bundle; no per-library source cache)."
    LIB_MANIFEST_JSON=$(jq -nc \
      --argjson acc "$LIB_MANIFEST_JSON" \
      --arg id "$LIB_ID" --arg ns "$NS" --arg name "$NM" \
      '$acc + [{id:$id, namespace:$ns, name:$name}]')
    continue
  fi
  # No cached bundle (main with #includes but no bundle) -- the legacy per-library restore is the only
  # path, so cache the library SOURCE the old way.
  LIB_RESTORE_FILE="mcp-source-library-${LIB_ID}.groovy"
  echo "Backing up library $TOKEN (id=$LIB_ID): watchdog hub_get_source(type=library, id=$LIB_ID)..."
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
  LIB_MANIFEST_JSON=$(jq -nc \
    --argjson acc "$LIB_MANIFEST_JSON" \
    --arg id "$LIB_ID" \
    --arg file "$LIB_RESTORE_FILE" \
    --arg ns "$NS" \
    --arg name "$NM" \
    '$acc + [{id:$id, file:$file, namespace:$ns, name:$name}]')
done

# 3e) Assert the CHILD app cache (mcp:MCP Rule). Full HPM repair redeploys EVERY manifest app, so the
# restore must restore the child too -- otherwise a PR that changes the child app would leave the PR's
# child live on the hub after the run (an incomplete restore). The cache itself was taken in 2c-ii
# (GitHub-direct); this resolves the class id (the cache file's name) and asserts the file is real.
CHILD_NAMESPACE="mcp"
CHILD_NAME="MCP Rule"
CHILD_CLASS_ID=$(echo "$LIST_TEXT" | jq -r --arg ns "$CHILD_NAMESPACE" --arg name "$CHILD_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
if [ -z "$CHILD_CLASS_ID" ] || [ "$CHILD_CLASS_ID" = "null" ]; then
  echo "::error::e2e HALT: child Apps Code class for $CHILD_NAMESPACE:$CHILD_NAME not found via hub_list_apps -- full repair redeploys it, so it must be cached to restore. Refusing to arm."
  exit 1
fi
CHILD_RESTORE_FILE="mcp-source-app-${CHILD_CLASS_ID}.groovy"
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
# this run -- a forensic breadcrumb on a fire. The cached sources ARE canonical main at MAIN_SHA
# (GitHub-direct cache, 2c-ii), which is what canonicalMainSha records.
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
