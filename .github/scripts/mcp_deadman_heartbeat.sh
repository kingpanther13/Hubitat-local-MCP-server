#!/usr/bin/env bash
# Dead-man heartbeat (PR-side script; launched by mcp_watchdog_deploy.sh, killed by
# mcp_disarm_watchdog.sh via PID file). While the e2e run is alive it keeps extending the
# armed flag's deadline, so the on-hub dead-man can only fire when heartbeats STOP (the
# runner died) -- never because a healthy run outlasted a fixed arm window. A fixed window
# is run-length-sensitive: the full lane grew past the old 35-min window on 2026-07-12 and
# the dead-man silently restored canonical main MID-SUITE on every full lane after, so late
# test groups ran against old code (PR #362's protocol false-red uncovered it).
#
# Zero watchdog-app changes: checkDeadman re-reads flag.deadline every poll, and the
# watchdog's adminWriteFile fires a deadmanKick after each flag write, so every extension
# takes effect within seconds.
#
# Resilience contract (each beat rides the cloud relay, which drops/504s under load):
#   - the FIRST beat fires immediately at launch (not after the first interval), so the
#     15-min arm window only ever has to cover arm -> launch;
#   - the flag read retries (call_tool_retry, 5 attempts) and the write retries once
#     in-beat (idempotent: re-extending the deadline), so one flaky call never skips a beat;
#   - each beat extends the deadline by 30 min against a 5-min interval -- SIX consecutive
#     failed beats (30+ min of sustained relay failure, retries included) before a lapse,
#     and a runner that dead-stops still fires within ~30 min.
#   - a beat that reads FIRE EVIDENCE (fireAttempts/restoreTrigger) or armed!=true never
#     writes, so a fired/disarmed flag is not resurrected and the disarm's fire detection
#     is not clobbered by a stale read-modify-write. (A fire landing inside a beat's
#     read->write gap can still be overwritten -- no CAS exists on File Manager writes --
#     which is why the disarm also prints this script's log: a >30-min gap between
#     "extended" lines is un-clobberable evidence of a lapse.)
set -u
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint) -- without it every beat would silently fail and the dead-man would fire mid-run}"
source "$(dirname "$0")/mcp_watchdog_lib.sh"   # call_tool/call_tool_retry/ok_of

FLAG_FILE="e2e-deadman-v2.json"
HEARTBEAT_INTERVAL_S="${HEARTBEAT_INTERVAL_S:-300}"     # beat every 5 minutes
HEARTBEAT_EXTEND_MS="${HEARTBEAT_EXTEND_MS:-1800000}"   # each beat: deadline = now + 30 minutes

beat() {
  READ_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
  TEXT=$(call_tool_retry "$READ_RPC")
  [ -n "$TEXT" ] || { echo "[heartbeat] $(date -u +%H:%M:%S) flag read dropped after retries -- will retry next beat"; return 0; }
  CONTENT=$(printf '%s' "$TEXT" | jq -r '.content // empty' 2>/dev/null || true)
  [ -n "$CONTENT" ] || return 0
  ARMED=$(printf '%s' "$CONTENT" | jq -r '.armed // false' 2>/dev/null || echo false)
  FIRED=$(printf '%s' "$CONTENT" | jq -r '((.fireAttempts // 0) > 0) or ((.restoreTrigger // "") == "deadline")' 2>/dev/null || echo false)
  if [ "$FIRED" = "true" ]; then
    echo "[heartbeat] $(date -u +%H:%M:%S) flag shows the dead-man FIRED (fireAttempts/restoreTrigger present) -- NOT writing (preserving the evidence); the run's results after the fire are suspect"
    return 0
  fi
  if [ "$ARMED" != "true" ]; then
    echo "[heartbeat] $(date -u +%H:%M:%S) flag not armed (disarmed) -- skipping extension"
    return 0
  fi
  NOW_MS=$(( $(date +%s) * 1000 ))
  NEW_DEADLINE=$(( NOW_MS + HEARTBEAT_EXTEND_MS ))
  NEW_CONTENT=$(printf '%s' "$CONTENT" | jq -c --argjson d "$NEW_DEADLINE" --argjson hb "$NOW_MS" \
    '.deadline = $d | .lastHeartbeatAt = $hb' 2>/dev/null || true)
  [ -n "$NEW_CONTENT" ] || return 0
  WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg c "$NEW_CONTENT" \
    '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')
  # One in-beat retry: the write is idempotent (re-extends the same deadline), and a single
  # relay drop must not cost a whole 5-min interval against the lapse budget.
  for w in 1 2; do
    OUT=$(call_tool "$WRITE_RPC")
    if [ "$(ok_of "$OUT")" = "true" ]; then
      echo "[heartbeat] $(date -u +%H:%M:%S) deadline extended to $NEW_DEADLINE"
      return 0
    fi
    [ "$w" = "1" ] && sleep 5
  done
  echo "[heartbeat] $(date -u +%H:%M:%S) extension write failed twice -- will retry next beat"
  return 0
}

echo "[heartbeat] started (interval ${HEARTBEAT_INTERVAL_S}s, extend ${HEARTBEAT_EXTEND_MS}ms)"
beat   # immediate first beat: proves end-to-end connectivity in the log and converts the
       # short arm window into a full extend right away
while true; do
  sleep "$HEARTBEAT_INTERVAL_S"
  beat
done
