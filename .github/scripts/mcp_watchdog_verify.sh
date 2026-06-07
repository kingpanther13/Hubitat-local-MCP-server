#!/usr/bin/env bash
# ONE-TIME real-server fire test for the E2E Dead-Man Watchdog (issue #243 safety net).
#
# Unlike mcp_arm_watchdog.sh (which arms a 20-minute window and trusts the disarm step to clear it),
# this script PROVES the whole dead-man loop end to end against the live hub: it arms the watchdog
# with an ALREADY-EXPIRED deadline (now-5s) so the next runEvery1Minute checkDeadman fires
# immediately, then polls until the MCP server's app-class version bumps (proving the watchdog
# actually re-pushed the known-good backup) and the flag records fireResult=="restored". Finally it
# confirms the server is still ALIVE (hub_get_info), i.e. the restore did NOT brick it.
#
# Safety: the restore target is the >1MB known-good pre-deploy backup (asserted before arming); a
# compile-fail would just keep the old code, never brick the server. This step runs AFTER all PR-code
# steps. The workflow's always() "Restore previous app source" step then pushes back the PRE-DEPLOY
# snapshot ($RUNNER_TEMP/mcp_pre_source.groovy -- the source that was live BEFORE this run, NOT this
# PR's code), so leaving the server on the pre-deploy backup here is expected and harmless.
#
# >>> TEMPORARY: this whole script + its workflow step exist to validate the watchdog ONCE on a real
# >>> hub. DELETE both after the first green run -- the standing arm/disarm wiring is the permanent
# >>> safety net; re-firing the watchdog on every PR is wasteful and reverts the server needlessly.
#
# Env:  MCP_URL               -- full cloud OAuth URL with access_token
#       PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#       PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA (the watchdog source lives on the PR head)
#       GITHUB_RUN_ID         -- this run's id (stamped into the flag for traceability)
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required -- https://raw.githubusercontent.com/<owner>/<repo>}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required -- 40-hex PR head SHA}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

WATCHDOG_NAME="E2E Dead-Man Watchdog"
WATCHDOG_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/e2e-deadman-watchdog.groovy"
FLAG_FILE="e2e-deadman.json"
# Immediate-expired window: deadline = now-5s, so the next minute's checkDeadman fires at once.
EXPIRE_OFFSET_MS=-5000
# Poll the version + flag for up to 180s (the watchdog checks every minute; allow ~3 cycles + the
# ~10-25s hub-side recompile the restore push triggers).
POLL_TIMEOUT_S=180
POLL_INTERVAL_S=15

# --- helpers copied verbatim from mcp_deploy_source.sh (the proven cloud-gateway wrappers) -------

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Prints the inner tool text on stdout; returns 0 on a parseable envelope (text may be empty ->
# caller checks), 1 only if every attempt was non-JSON (the ~10s cloud-gateway timeout). A valid
# JSON envelope -- including a real hub error -- is returned immediately and never retried.
RPC_ATTEMPTS=5
RPC_RETRY_SLEEP=6
mcp_tool_call_text() {
  local label="$1" rpc="$2" attempt=1 resp text
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    resp=$(mcp_call "$rpc" || true)
    if text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // ""' 2>/dev/null); then
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
echo "Looking up Apps Code class ID for $APP_NAMESPACE:$APP_NAME via hub_list_apps(scope=types)..."
LIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}'
if ! LIST_TEXT=$(mcp_tool_call_text "hub_list_apps (verify class-ID lookup)" "$LIST_RPC"); then
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

RESTORE_FILE="mcp-backup-app-${CLASS_ID}.groovy"

# --- 2) Ensure the watchdog is installed AND has a live checkDeadman schedule (same as arm) --------
echo "Checking for the standing '$WATCHDOG_NAME' install..."
INST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"instances"}}}'
if ! INST_TEXT=$(mcp_tool_call_text "hub_list_apps (watchdog instance lookup)" "$INST_RPC"); then
  echo "::error::hub_list_apps(scope=instances): non-JSON gateway response $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
  exit 1
fi
WATCHDOG_INSTANCE_ID=$(echo "$INST_TEXT" | jq -r \
  --arg name "$WATCHDOG_NAME" \
  '.apps[]? | select((.name // .label) == $name) | .id' | head -n1)

# hub_get_jobs lists scheduledJobs[].method; require a checkDeadman entry to prove the standing
# watchdog's runEvery1Minute survived. Returns 0 if the method is scheduled, 1 otherwise.
watchdog_schedule_alive() {
  local jobs_text
  jobs_text=$(mcp_tool_call_text "hub_get_jobs (watchdog schedule check)" \
    '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_jobs","arguments":{}}}') || return 1
  printf '%s' "$jobs_text" | jq -e \
    '[(.scheduledJobs.jobs // .scheduledJobs // [])[]? | (.method // .name // "")] | any(. == "checkDeadman")' \
    >/dev/null 2>&1
}

if [ -n "$WATCHDOG_INSTANCE_ID" ] && [ "$WATCHDOG_INSTANCE_ID" != "null" ]; then
  echo "Standing watchdog present (instance $WATCHDOG_INSTANCE_ID). Asserting its checkDeadman schedule is live..."
  if ! watchdog_schedule_alive; then
    echo "::error::'$WATCHDOG_NAME' is installed (instance $WATCHDOG_INSTANCE_ID) but has NO live checkDeadman scheduled job -- its runEvery1Minute did not survive. The fire test can't run against a dead watchdog."
    exit 1
  fi
  echo "WATCHDOG_PRESENT instanceAppId=$WATCHDOG_INSTANCE_ID scheduleAlive=true"
else
  echo "No standing watchdog found -- first-time install from $WATCHDOG_URL ..."
  CREATE_CODE_RPC=$(jq -nc --arg url "$WATCHDOG_URL" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_app",arguments:{importUrl:$url,confirm:true}}}')
  if ! CREATE_CODE_TEXT=$(mcp_tool_call_text "hub_create_app (install watchdog code class)" "$CREATE_CODE_RPC"); then
    echo "::error::hub_create_app(importUrl) for the watchdog returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
    exit 1
  fi
  # Gate on success==true FIRST (the hub returns a non-empty appId on several
  # success:false partial-failure paths -- trusting appId alone proceeds against a broken class).
  CREATE_CODE_OK=$(echo "$CREATE_CODE_TEXT" | jq -r '.success // false')
  if [ "$CREATE_CODE_OK" != "true" ]; then
    echo "::error::hub_create_app(importUrl) for the watchdog reported failure (success=$CREATE_CODE_OK): $(echo "$CREATE_CODE_TEXT" | jq -r '.error // .message // empty'). Response: $(printf '%s' "$CREATE_CODE_TEXT" | head -c 600)"
    exit 1
  fi
  CODE_APP_ID=$(echo "$CREATE_CODE_TEXT" | jq -r '.appId // empty')
  if [ -z "$CODE_APP_ID" ]; then
    echo "::error::hub_create_app(importUrl) did not return an appId for the watchdog code class. Response: $(printf '%s' "$CREATE_CODE_TEXT" | head -c 600)"
    exit 1
  fi
  echo "Watchdog code class installed (codeAppId=$CODE_APP_ID). Creating the user-app instance..."
  CREATE_INST_RPC=$(jq -nc --argjson code "$CODE_APP_ID" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_create_app",arguments:{installAsUserApp:$code,confirm:true}}}')
  if ! CREATE_INST_TEXT=$(mcp_tool_call_text "hub_create_app (install watchdog instance)" "$CREATE_INST_RPC"); then
    echo "::error::hub_create_app(installAsUserApp) for the watchdog returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
    exit 1
  fi
  COMMITTED=$(echo "$CREATE_INST_TEXT" | jq -r '.committed // false')
  SCHED_COUNT=$(echo "$CREATE_INST_TEXT" | jq -r '.scheduledJobCount // 0')
  INSTANCE_APP_ID=$(echo "$CREATE_INST_TEXT" | jq -r '.instanceAppId // empty')
  # The live validation of the #243 install fix: a shell install reports committed!=true /
  # scheduledJobCount 0. Fail loudly on either.
  if [ "$COMMITTED" != "true" ]; then
    echo "::error::Watchdog install did NOT commit (committed=$COMMITTED). The #243 install fix regressed -- installAsUserApp returned a shell, not a live instance. Response: $(printf '%s' "$CREATE_INST_TEXT" | head -c 600)"
    exit 1
  fi
  # Fail-CLOSED on any non-numeric scheduledJobCount.
  if ! [ "${SCHED_COUNT:-0}" -ge 1 ] 2>/dev/null; then
    echo "::error::Watchdog install committed but scheduledJobCount=$SCHED_COUNT (<1 or non-numeric). Its runEvery1Minute did not register, so the dead-man check would never run. Response: $(printf '%s' "$CREATE_INST_TEXT" | head -c 600)"
    exit 1
  fi
  echo "WATCHDOG_INSTALLED instanceAppId=${INSTANCE_APP_ID:-unknown} scheduledJobCount=$SCHED_COUNT"
fi

# --- 3) Assert a real >1MB known-good backup exists to restore from -------------------------------
echo "Asserting known-good backup '$RESTORE_FILE' exists and is >1MB..."
READ_BACKUP_RPC=$(jq -nc --arg fn "$RESTORE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! BACKUP_TEXT=$(mcp_tool_call_text "hub_read_file (backup existence check)" "$READ_BACKUP_RPC"); then
  echo "::error::hub_read_file('$RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
  exit 1
fi
BACKUP_LEN=$(echo "$BACKUP_TEXT" | jq -r '.totalLength // 0')
if ! [ "$BACKUP_LEN" -gt 1000000 ] 2>/dev/null; then
  echo "::error::No known-good backup to restore from: '$RESTORE_FILE' is missing or too small (totalLength=$BACKUP_LEN, need >1000000). Refusing to fire the watchdog against a truncated/absent backup."
  exit 1
fi
echo "Backup '$RESTORE_FILE' present (totalLength=$BACKUP_LEN bytes)."

# --- 4) Record the current app-class version (the restore must BUMP it) ---------------------------
# hub_get_source(type=app, id=CLASS_ID, offset:0, length:1) returns {version, totalLength}. The
# watchdog's restoreApp re-pushes the backup via /app/ajax/update, which advances the version -- the
# independent confirmation that the push actually landed (a no-op save would not bump it).
read_app_version() {
  local rpc text
  rpc=$(jq -nc --arg id "$CLASS_ID" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1}}}')
  text=$(mcp_tool_call_text "hub_get_source (version probe)" "$rpc") || return 1
  printf '%s' "$text" | jq -r '.version // empty'
}

echo "Recording current app-class version (V) for class $CLASS_ID..."
if ! V=$(read_app_version) || [ -z "$V" ]; then
  echo "::error::Could not read the current version of app class $CLASS_ID before arming -- cannot prove a version bump. Re-run the job."
  exit 1
fi
echo "Pre-fire version V=$V"

# Defense-in-depth: from here on the flag is armed with an ALREADY-EXPIRED deadline, so if this
# script is killed (job hard-timeout) DURING the poll, an armed-expired flag would linger and the
# watchdog would fire on its next minute tick. A best-effort EXIT trap clears it. (The always()
# "Disarm dead-man watchdog (final)" workflow step is the real backstop; this just narrows the
# window if the hard timeout pre-empts that step.)
disarm_flag_best_effort() {
  local fj rpc
  fj=$(jq -nc --arg runId "$GITHUB_RUN_ID" '{armed:false, runId:$runId, disarmedBy:"verify-trap"}') || return 0
  rpc=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$fj" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}') || return 0
  mcp_tool_call_text "verify-trap disarm (best-effort)" "$rpc" >/dev/null 2>&1 || \
    echo "::warning::verify-trap best-effort disarm failed; the always() Disarm step is the backstop." >&2
  return 0
}
trap disarm_flag_best_effort EXIT

# --- 5) Arm with an IMMEDIATE-expired deadline so the next checkDeadman fires at once --------------
DEADLINE_MS=$(( $(date +%s%3N) + EXPIRE_OFFSET_MS ))
FLAG_JSON=$(jq -nc \
  --argjson deadline "$DEADLINE_MS" \
  --arg classId "$CLASS_ID" \
  --arg restoreFileName "$RESTORE_FILE" \
  --arg runId "$GITHUB_RUN_ID" \
  '{armed:true, deadline:$deadline, classId:$classId, restoreFileName:$restoreFileName, runId:$runId}')

echo "Arming watchdog with an already-expired deadline ($DEADLINE_MS, now-5s) to force an immediate fire..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (arm expired flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm write. Re-run the job."
  exit 1
fi

# Read-back-and-assert the arm landed (a cloud 504 can no-op a write while returning ambiguously).
echo "Reading the flag back to confirm it armed..."
READBACK_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! READBACK_TEXT=$(mcp_tool_call_text "hub_read_file (arm read-back)" "$READBACK_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') read-back returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm landed. Re-run the job."
  exit 1
fi
RB_ARMED=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .armed' 2>/dev/null || echo "")
RB_CLASS=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .classId' 2>/dev/null || echo "")
if [ "$RB_ARMED" != "true" ] || [ "$RB_CLASS" != "$CLASS_ID" ]; then
  echo "::error::Arm flag did not land as expected (read back armed=$RB_ARMED classId=$RB_CLASS, expected armed=true classId=$CLASS_ID). Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi
echo "Watchdog armed with expired deadline. Polling up to ${POLL_TIMEOUT_S}s for the fire+restore..."

# --- 6) Poll for: NV > V AND flag.fireResult == "restored" ----------------------------------------
ELAPSED=0
NV=""
FIRE_RESULT=""
FIRED_AT=""
LAST_FLAG=""
while [ "$ELAPSED" -lt "$POLL_TIMEOUT_S" ]; do
  sleep "$POLL_INTERVAL_S"
  ELAPSED=$(( ELAPSED + POLL_INTERVAL_S ))

  # Current app-class version.
  NV=$(read_app_version || echo "")

  # Flag firedAt / fireResult.
  POLL_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
  POLL_TEXT=$(mcp_tool_call_text "hub_read_file (fire poll)" "$POLL_RPC") || POLL_TEXT=""
  LAST_FLAG=$(echo "$POLL_TEXT" | jq -r '.content // empty' 2>/dev/null || echo "")
  FIRE_RESULT=$(printf '%s' "$LAST_FLAG" | jq -r '.fireResult // empty' 2>/dev/null || echo "")
  FIRED_AT=$(printf '%s' "$LAST_FLAG" | jq -r '.firedAt // empty' 2>/dev/null || echo "")

  echo "Poll @${ELAPSED}s: version V=$V NV=${NV:-?} firedAt=${FIRED_AT:-none} fireResult=${FIRE_RESULT:-none}"

  if [ -n "$NV" ] && [ -n "$V" ] && [ "$NV" -gt "$V" ] 2>/dev/null && [ "$FIRE_RESULT" = "restored" ]; then
    echo "WATCHDOG_VERIFY_FIRED version V=$V NV=$NV"
    # Server must still be ALIVE after the restore -- hub_get_info proves the MCP app reloaded and
    # answers, i.e. the restore did NOT brick it.
    if ! INFO_TEXT=$(mcp_tool_call_text "hub_get_info (post-restore liveness)" \
        '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}'); then
      echo "::error::Watchdog fired and bumped the version, but hub_get_info returned non-JSON $RPC_ATTEMPTS times -- could not confirm the server is alive after the restore. WATCHDOG_VERIFY_FAIL"
      exit 1
    fi
    if [ -z "$INFO_TEXT" ]; then
      echo "::error::Watchdog fired and bumped the version, but hub_get_info returned an empty body -- the MCP server may not be alive after the restore. WATCHDOG_VERIFY_FAIL"
      exit 1
    fi
    echo "WATCHDOG_VERIFY_SERVER_ALIVE"
    echo "WATCHDOG_VERIFY_PASS"
    exit 0
  fi
done

# --- timeout / mismatch ---------------------------------------------------------------------------
echo "::error::Watchdog did not fire+restore within ${POLL_TIMEOUT_S}s. Expected NV>$V AND fireResult==restored; last seen NV=${NV:-?} fireResult=${FIRE_RESULT:-none} firedAt=${FIRED_AT:-none}."
echo "Last flag contents: ${LAST_FLAG:-<none>}"
echo "WATCHDOG_VERIFY_FAIL"
exit 1
