#!/usr/bin/env bash
# Arm the standing "E2E Dead-Man Watchdog v2" for THIS run (issue #243 install fix + e2e safety net).
#
# v2 is a STANDALONE on-hub app that doubles as a small MCP server: its dead-man timer auto-restores
# the WHOLE MCP package (the MCP Rule Server app + every library it #includes) from a known-good File
# Manager cache if an e2e session bricks the hub and can't disarm. This script (1) ensures the v2
# watchdog is installed AND has a live runEvery1Minute schedule, (2) BACKS UP main by reading the
# app class + each #include'd library through the WATCHDOG's own hub_get_source (whose File-Manager
# auto-save side effect writes the cache files), assembling the restore manifest, (3) asserts the app
# cache is real (>1MB) -- if main is not cached, e2e HALTS -- then (4) writes the manifest-shaped
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
MAIN_SHA_FILE="mcp-main-deployed-sha.txt"
if [ -n "${MAIN_CHARS:-}" ] && [ -n "${MAIN_SOURCE_URL:-}" ] && [ -n "${MAIN_SHA:-}" ]; then
  LIVE_MAIN_LEN=$(mcp_tool_call_text "hub_get_source (live main length, noSave)" \
    "$(jq -nc --arg id "$CLASS_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')" \
    | jq -r '.totalLength // empty' 2>/dev/null || true)
  STORED_MAIN_SHA=$(mcp_tool_call_text "hub_read_file (last deployed-main sha)" \
    "$(jq -nc --arg fn "$MAIN_SHA_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')" \
    | jq -r '.content // ""' 2>/dev/null | tr -d '[:space:]' || true)
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
    mcp_tool_call_text "hub_update_app (refresh canonical main)" "$DM_RPC" >/dev/null || true
    DM_LANDED=""
    DM_DEADLINE=$(( $(date +%s) + 420 ))
    while [ "$(date +%s)" -lt "$DM_DEADLINE" ]; do
      sleep 15
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
    mcp_tool_call_text "hub_write_file (record deployed-main sha)" \
      "$(jq -nc --arg fn "$MAIN_SHA_FILE" --arg c "$MAIN_SHA" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')" >/dev/null \
      || echo "::warning::could not record the deployed-main SHA; next run will re-evaluate by length/sha. Continuing."
    echo "WATCHDOG_MAIN_REFRESHED sha=${MAIN_SHA} chars=${MAIN_CHARS}"
  fi
else
  echo "::notice::MAIN_* not provided -- skipping the canonical-main refresh (the cache will be whatever is live at arm)."
fi

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

# Assemble the restore manifest: {app:{classId,file}, libraries:[{id,file,namespace,name}]} -- the
# exact shape restorePackage() reads (libraries first, app last; namespace/name let it re-resolve a
# library id that the deploy changed via delete+recreate).
MANIFEST_JSON=$(jq -nc \
  --arg classId "$CLASS_ID" \
  --arg appFile "$APP_RESTORE_FILE" \
  --arg mainChars "$APP_BACKUP_LEN" \
  --argjson libs "$LIB_MANIFEST_JSON" \
  '{app:{classId:$classId, file:$appFile, mainChars:$mainChars}, libraries:$libs}')
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
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:true, deadline:$deadline, runId:$runId, intent:"arm", armPrSha:$armPrSha, manifest:$manifest}')

echo "Writing armed flag to '$FLAG_FILE' (deadline=$DEADLINE_MS, ~35min)..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (arm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm write. Re-run the job."
  exit 1
fi

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
