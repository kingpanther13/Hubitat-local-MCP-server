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
# takes effect within seconds. Every write is guarded on armed==true, so a straggler tick
# after the disarm is a no-op; the deliberate no-write path also means a fired/disarmed
# flag is never resurrected by a stale read-modify-write (the disarm kills this process
# BEFORE touching the flag, closing the mid-flight race).
set -u
source "$(dirname "$0")/mcp_watchdog_lib.sh"   # call_tool/ok_of + WATCHDOG_URL contract

FLAG_FILE="e2e-deadman-v2.json"
HEARTBEAT_INTERVAL_S="${HEARTBEAT_INTERVAL_S:-300}"    # beat every 5 minutes
HEARTBEAT_EXTEND_MS="${HEARTBEAT_EXTEND_MS:-900000}"   # each beat: deadline = now + 15 minutes

echo "[heartbeat] started (interval ${HEARTBEAT_INTERVAL_S}s, extend ${HEARTBEAT_EXTEND_MS}ms)"
while true; do
  sleep "$HEARTBEAT_INTERVAL_S"
  READ_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
  TEXT=$(call_tool "$READ_RPC")
  [ -n "$TEXT" ] || { echo "[heartbeat] $(date -u +%H:%M:%S) flag read dropped -- will retry next beat"; continue; }
  CONTENT=$(printf '%s' "$TEXT" | jq -r '.content // empty' 2>/dev/null || true)
  [ -n "$CONTENT" ] || continue
  ARMED=$(printf '%s' "$CONTENT" | jq -r '.armed // false' 2>/dev/null || echo false)
  if [ "$ARMED" != "true" ]; then
    echo "[heartbeat] $(date -u +%H:%M:%S) flag not armed (disarmed or fired) -- skipping extension"
    continue
  fi
  NOW_MS=$(( $(date +%s) * 1000 ))
  NEW_DEADLINE=$(( NOW_MS + HEARTBEAT_EXTEND_MS ))
  NEW_CONTENT=$(printf '%s' "$CONTENT" | jq -c --argjson d "$NEW_DEADLINE" --argjson hb "$NOW_MS" \
    '.deadline = $d | .lastHeartbeatAt = $hb' 2>/dev/null || true)
  [ -n "$NEW_CONTENT" ] || continue
  WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg c "$NEW_CONTENT" \
    '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$c,confirm:true}}}')
  OUT=$(call_tool "$WRITE_RPC")
  echo "[heartbeat] $(date -u +%H:%M:%S) deadline extended to $NEW_DEADLINE (write ok=$(ok_of "$OUT"))"
done
