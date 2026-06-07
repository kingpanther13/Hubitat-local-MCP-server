#!/usr/bin/env bash
# Arm the standing "E2E Dead-Man Watchdog" for THIS run (issue #243 install fix + e2e safety net).
#
# The watchdog is a STANDALONE on-hub app whose only job is to auto-restore the MCP Rule Server's
# code from a known-good File Manager backup if an e2e session bricks the hub and can't disarm. This
# script (1) ensures the watchdog is installed AND has a live runEvery1Minute schedule, (2) asserts a
# real >1MB known-good backup exists to restore from, then (3) writes the armed flag with the ARM_WINDOW_MS
# deadline (35 min). The matching "Disarm dead-man watchdog (final)" always() step writes {armed:false}; if a
# run crashes before that, the watchdog fires and restores the server on its own.
#
# All hub I/O is through the cloud MCP endpoint ($MCP_URL). Every flag write is READ BACK and
# asserted -- a cloud 504 can no-op a write while returning ambiguously, so we never trust a write we
# didn't verify. We refuse to arm against a missing/truncated backup (arming with no good restore
# target would be worse than not arming at all).
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

# The MCP server app's Apps Code CLASS id (resolved by namespace+name, exactly as
# mcp_deploy_source.sh does). The watchdog's restore targets this CODE CLASS id, NOT the running
# instance id the MCP URL carries.
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

WATCHDOG_NAME="E2E Dead-Man Watchdog"
WATCHDOG_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/e2e-deadman-watchdog.groovy"
FLAG_FILE="e2e-deadman.json"
ARM_WINDOW_MS=2100000   # 35 minutes -- deliberately > the 30-min job timeout-minutes, so the
                        # watchdog can only fire AFTER GitHub has already killed a hung/dead job,
                        # never mid-live-run. The always() disarm clears it in seconds on every
                        # normal run, so the long window only matters when the session truly dies.

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

# The watchdog restores from this File Manager file. hub_get_source on a >1MB source writes
# mcp-source-app-<CLASS_ID>.groovy = the CURRENT full source as a side effect -- a reliable hub-side
# copy (no 1.6MB cloud transfer), unlike mcp-backup-app-* which a 1-hour backupItemSource cache can
# leave absent. We refresh it just below so it is guaranteed present + current at arm time.
RESTORE_FILE="mcp-source-app-${CLASS_ID}.groovy"

# --- 2) Ensure the watchdog is installed AND has a live checkDeadman schedule (idempotent) --------
# Look for the standing install by name. If present, do NOT reinstall -- just assert its
# runEvery1Minute schedule survived. If absent (first time), install the code class + create the
# instance and assert the #243 install fix committed (committed==true, scheduledJobCount>=1).
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
    echo "::error::'$WATCHDOG_NAME' is installed (instance $WATCHDOG_INSTANCE_ID) but has NO live checkDeadman scheduled job -- its runEvery1Minute did not survive. Something touched the watchdog; refusing to arm a dead watchdog."
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
  # Gate on success==true FIRST: the hub returns a non-empty appId on several
  # success:false partial-failure paths (unparseable/empty verify body, compile
  # error), so trusting appId alone would proceed against a broken code class.
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
  # This is the live validation of the #243 install fix: a shell install would report committed
  # !=true / scheduledJobCount 0 (no runEvery1Minute fired). Fail loudly on either.
  if [ "$COMMITTED" != "true" ]; then
    echo "::error::Watchdog install did NOT commit (committed=$COMMITTED). The #243 install fix regressed -- installAsUserApp returned a shell, not a live instance. Response: $(printf '%s' "$CREATE_INST_TEXT" | head -c 600)"
    exit 1
  fi
  # Fail-CLOSED on any non-numeric scheduledJobCount (the `! [ -ge 1 ]` idiom
  # treats a "null"/garbage value as a failure, unlike `[ -lt 1 ] || ...`).
  if ! [ "${SCHED_COUNT:-0}" -ge 1 ] 2>/dev/null; then
    echo "::error::Watchdog install committed but scheduledJobCount=$SCHED_COUNT (<1 or non-numeric). Its runEvery1Minute did not register, so the dead-man check would never run. Response: $(printf '%s' "$CREATE_INST_TEXT" | head -c 600)"
    exit 1
  fi
  echo "WATCHDOG_INSTALLED instanceAppId=${INSTANCE_APP_ID:-unknown} scheduledJobCount=$SCHED_COUNT"
fi

# --- 3) Refresh the known-good backup, then assert it is real (>1MB) -------------------------------
# hub_get_source(CLASS_ID) writes mcp-source-app-<CLASS_ID>.groovy = the current (just-deployed +
# verified-working) source. Confirm the side-effect file matches RESTORE_FILE; if the source were too
# small to trigger the File-Manager save, sourceFile would be empty/different and we fail loudly.
echo "Refreshing known-good backup '$RESTORE_FILE' via hub_get_source($CLASS_ID)..."
REFRESH_RPC=$(jq -nc --arg id "$CLASS_ID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1}}}')
if ! REFRESH_TEXT=$(mcp_tool_call_text "hub_get_source (refresh known-good backup)" "$REFRESH_RPC"); then
  echo "::error::hub_get_source($CLASS_ID) returned non-JSON $RPC_ATTEMPTS times -- could not refresh the known-good backup. Re-run the job."
  exit 1
fi
REFRESH_OK=$(echo "$REFRESH_TEXT" | jq -r '.success // false')
REFRESH_SRCFILE=$(echo "$REFRESH_TEXT" | jq -r '.sourceFile // empty')
if [ "$REFRESH_OK" != "true" ] || [ "$REFRESH_SRCFILE" != "$RESTORE_FILE" ]; then
  echo "::error::hub_get_source($CLASS_ID) did not write the expected backup (success=$REFRESH_OK sourceFile=$REFRESH_SRCFILE, expected $RESTORE_FILE). Response: $(printf '%s' "$REFRESH_TEXT" | head -c 600)"
  exit 1
fi
echo "Asserting known-good backup '$RESTORE_FILE' exists and is >1MB..."
READ_BACKUP_RPC=$(jq -nc --arg fn "$RESTORE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! BACKUP_TEXT=$(mcp_tool_call_text "hub_read_file (backup existence check)" "$READ_BACKUP_RPC"); then
  echo "::error::hub_read_file('$RESTORE_FILE') returned non-JSON $RPC_ATTEMPTS times -- transient relay/timeout. Re-run the job."
  exit 1
fi
BACKUP_LEN=$(echo "$BACKUP_TEXT" | jq -r '.totalLength // 0')
if ! [ "$BACKUP_LEN" -gt 1000000 ] 2>/dev/null; then
  echo "::error::No known-good backup to restore from: '$RESTORE_FILE' is missing or too small (totalLength=$BACKUP_LEN, need >1000000). Refusing to arm against a truncated/absent backup."
  exit 1
fi
echo "Backup '$RESTORE_FILE' present (totalLength=$BACKUP_LEN bytes)."

# --- 4) Write the armed flag, then READ IT BACK and assert -------------------------------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; this stays portable (the second-
# granularity is irrelevant for a 35-minute deadline).
DEADLINE_MS=$(( $(date +%s) * 1000 + ARM_WINDOW_MS ))
FLAG_JSON=$(jq -nc \
  --argjson deadline "$DEADLINE_MS" \
  --arg classId "$CLASS_ID" \
  --arg restoreFileName "$RESTORE_FILE" \
  --arg runId "$GITHUB_RUN_ID" \
  '{armed:true, deadline:$deadline, classId:$classId, restoreFileName:$restoreFileName, runId:$runId}')

echo "Writing armed flag to '$FLAG_FILE' (deadline=$DEADLINE_MS, ~35min)..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (arm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm write. Re-run the job."
  exit 1
fi

# Read-back-and-assert: a cloud 504 can no-op a write while returning ambiguously.
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
  echo "::error::Arm flag did not land as expected (read back armed=$RB_ARMED classId=$RB_CLASS, expected armed=true classId=$CLASS_ID). The write may have silently no-opped. Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

echo "WATCHDOG_ARMED classId=$CLASS_ID deadline=$DEADLINE_MS runId=$GITHUB_RUN_ID backup=$RESTORE_FILE"
