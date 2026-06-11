#!/usr/bin/env bash
# READ-ONLY test-hub inventory probe, plus ONE labeled command round-trip against the
# persistent BAT scaffold switch. Dispatched via hub-e2e.yml with probe_only=true to
# diagnose the device-event wedge (commands accepted, no event, no state change) by
# dumping exact hub status: info, jobs (incl. RUNNING jobs), metrics + health alerts,
# memory history, every device, every running app instance (covers RM rules, Basic
# Rules, Button Controllers/Rules, Visual Rules, Notifiers, Room Lighting -- every app
# type, as one parent/child tree), hub variables, File Manager contents, location
# events (lowMemory/systemStart/mode/HSM), per-BAT-device events/subscribers, and the
# watchdog's flag/marker files. Probes the MAIN MCP server ($MCP_URL) directly -- the
# watchdog endpoint lacks the device-reading tools -- with watchdog reads only for its
# own state files. NOTHING IS TRUNCATED: sections print their full tool response (each
# is already bounded by the server's ~120KB response cap), so one probe run carries the
# whole picture. Every call was validated 1:1 against a live hub before this landed.
#
# Env:  MCP_URL       -- main server cloud OAuth URL (LEVEL99_TEST_HUB_MCP_URL secret)
#       WATCHDOG_URL  -- watchdog cloud OAuth URL (WATCHDOG_MCP_URL secret)
set -uo pipefail   # deliberately NOT -e: one failed section must not hide the rest

: "${MCP_URL:?MCP_URL env var required}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required}"

mcp_text() { # $1 endpoint url, $2 rpc json -> prints result text ('' on failure); retries x3
  local url="$1" rpc="$2" resp text attempt
  for attempt in 1 2 3; do
    resp=$(curl -sS --max-time 60 -X POST "$url" -H "Content-Type: application/json" --data-binary "$rpc" 2>/dev/null)
    text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // empty' 2>/dev/null)
    if [ -n "$text" ]; then
      printf '%s' "$text"
      return 0
    fi
    sleep 5
  done
  echo "PROBE-SECTION-FAILED after 3 attempts; last body head: $(printf '%s' "$resp" | head -c 200 | tr '\n' ' ')" >&2
  return 1
}

section() { # $1 section label, $2 endpoint url, $3 rpc json -- prints the FULL response, no truncation
  local label="$1" url="$2" rpc="$3" text
  echo "=== $label ==="
  if text=$(mcp_text "$url" "$rpc"); then
    printf '%s\n' "$text"
  else
    echo "(section failed -- see stderr note above)"
  fi
  echo ""
}

tool_rpc() { # $1 flat tool name, $2 arguments json
  jq -nc --arg n "$1" --argjson a "$2" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:$n,arguments:$a}}'
}
gw_rpc() { # $1 gateway, $2 sub-tool, $3 args json
  jq -nc --arg g "$1" --arg t "$2" --argjson a "$3" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:$g,arguments:{tool:$t,args:$a}}}'
}

echo "######## HUB PROBE: read-only inventory ########"
section "hub_get_info" "$MCP_URL" "$(tool_rpc hub_get_info '{}')"
section "hub_get_jobs (uptime / scheduled / RUNNING jobs / hub actions)" "$MCP_URL" "$(gw_rpc hub_manage_logs hub_get_jobs '{}')"
section "hub_get_metrics (free memory + the hub's own health alerts)" "$MCP_URL" "$(gw_rpc hub_manage_diagnostics hub_get_metrics '{}')"
section "hub_get_memory_history (last 60 entries)" "$MCP_URL" "$(gw_rpc hub_manage_diagnostics hub_get_memory_history '{"limit":60}')"

# Device list, paginated via hasMore/nextOffset so NOTHING is silently dropped. Also
# kept in $ALL_DEVICES for the BAT deep-dive + scaffold lookup below.
echo "=== hub_list_devices (ALL devices, paginated) ==="
ALL_DEVICES="[]"
OFFSET=0
for page in 1 2 3 4 5 6 7 8 9 10; do
  PAGE_TEXT=$(mcp_text "$MCP_URL" "$(tool_rpc hub_list_devices "{\"limit\":200,\"offset\":${OFFSET}}")") || break
  printf '%s\n' "$PAGE_TEXT"
  ALL_DEVICES=$(jq -nc --argjson acc "$ALL_DEVICES" --argjson page "$(printf '%s' "$PAGE_TEXT" | jq -c '.devices // .')" '$acc + $page' 2>/dev/null || printf '%s' "$ALL_DEVICES")
  HAS_MORE=$(printf '%s' "$PAGE_TEXT" | jq -r '.hasMore // false' 2>/dev/null)
  OFFSET=$(printf '%s' "$PAGE_TEXT" | jq -r '.nextOffset // 0' 2>/dev/null)
  if [ "$HAS_MORE" != "true" ]; then break; fi
  if [ "$page" = "10" ]; then echo "!! DEVICE LIST STILL hasMore=true AFTER 10 PAGES -- raise the page cap !!"; fi
done
echo ""

section "hub_list_rules (RM rules via RMUtils)" "$MCP_URL" "$(gw_rpc hub_manage_rule_machine hub_list_rules '{}')"
section "hub_get_visual_rule (Visual Rules list)" "$MCP_URL" "$(gw_rpc hub_read_rules hub_get_visual_rule '{}')"
section "hub_list_apps (ALL running app instances: RM/Basic/Button/VRB/Notifier/everything)" "$MCP_URL" "$(gw_rpc hub_read_apps_code hub_list_apps '{"scope":"instances"}')"
section "hub_list_variables (hub + rule-engine)" "$MCP_URL" "$(gw_rpc hub_manage_variables hub_list_variables '{}')"
section "hub_list_files (File Manager)" "$MCP_URL" "$(gw_rpc hub_read_files hub_list_files '{}')"
section "location events (lowMemory / systemStart / mode / HSM, last 24h)" "$MCP_URL" "$(tool_rpc hub_list_device_events '{"limit":50}')"

echo "######## BAT_E2E_ device deep-dive (events + subscribers) ########"
BAT_IDS=$(printf '%s' "$ALL_DEVICES" | jq -r '.[] | select((.label // .name // "") | contains("BAT_E2E_")) | "\(.id)\t\(.label // .name)"' 2>/dev/null)
if [ -z "$BAT_IDS" ]; then echo "(no BAT_E2E_ devices found)"; fi
while IFS=$'\t' read -r did dlabel; do
  [ -z "$did" ] && continue
  section "events for ${dlabel} (${did})" "$MCP_URL" "$(tool_rpc hub_list_device_events "{\"deviceId\":\"${did}\",\"limit\":10}")"
  section "apps subscribed to ${dlabel} (${did})" "$MCP_URL" "$(gw_rpc hub_read_apps_code hub_list_device_dependents "{\"deviceId\":\"${did}\"}")"
done <<< "$BAT_IDS"

# LIVE WEDGE CHECK -- the probe's one deliberate write: command the persistent BAT
# scaffold and see whether the event processes RIGHT NOW, outside any e2e run.
echo "######## LIVE wedge check (commands the BAT scaffold only) ########"
SCAFFOLD_ID=$(printf '%s' "$ALL_DEVICES" | jq -r '.[] | select((.label // .name // "") | endswith("Action_Switch")) | .id' 2>/dev/null | head -1)
if [ -n "$SCAFFOLD_ID" ]; then
  CUR=$(mcp_text "$MCP_URL" "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${SCAFFOLD_ID}\",\"attribute\":\"switch\"}")")
  echo "scaffold ${SCAFFOLD_ID} current state: ${CUR}"
  VAL=$(printf '%s' "$CUR" | jq -r '.value // empty' 2>/dev/null)
  if [ "$VAL" = "on" ]; then TARGET="off"; else TARGET="on"; fi
  curl -sS --max-time 30 -X POST "$MCP_URL" -H "Content-Type: application/json" \
    --data-binary "$(tool_rpc hub_call_device_command "{\"deviceId\":\"${SCAFFOLD_ID}\",\"command\":\"${TARGET}\"}")" >/dev/null
  section "poll scaffold -> ${TARGET} (timedOut=true here = the wedge is live right now)" "$MCP_URL" \
    "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${SCAFFOLD_ID}\",\"attribute\":\"switch\",\"expectedValue\":\"${TARGET}\",\"timeoutMs\":4000}")"
else
  echo "(no Action_Switch scaffold found -- skipping live check)"
fi

echo "######## Watchdog flag / marker files ########"
for f in e2e-deadman-v2.json e2e-deferred-native-rules.json mcp-main-deployed-sha.txt; do
  section "watchdog read ${f}" "$WATCHDOG_URL" "$(tool_rpc hub_read_file "{\"fileName\":\"${f}\"}")"
done

echo "######## PROBE COMPLETE ########"
