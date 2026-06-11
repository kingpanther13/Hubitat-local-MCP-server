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
# Metrics / logs / app-inventory sections go through the WATCHDOG endpoint: it is a separate
# always-alive app, so the probe reads hub health even while the main server is recompiling or
# throttled (these sections used to 504 against a busy main app). Sections that are main-DOMAIN
# (devices, RM/VRB rules, variables, files) or that deliberately exercise the main app (the live
# wedge matrix) stay on MCP_URL.
section "hub_get_info (MAIN server -- also proves the app under test answers)" "$MCP_URL" "$(tool_rpc hub_get_info '{}')"
section "hub_get_jobs via watchdog (uptime / scheduled / RUNNING jobs)" "$WATCHDOG_URL" "$(tool_rpc hub_get_jobs '{}')"
section "hub_get_metrics via watchdog (free memory + the hub's own health alerts)" "$WATCHDOG_URL" "$(tool_rpc hub_get_metrics '{}')"
section "hub_get_memory_history via watchdog (last 60 entries)" "$WATCHDOG_URL" "$(tool_rpc hub_get_memory_history '{"limit":60}')"
section "hub system logs via watchdog: ERRORS (newest 60)" "$WATCHDOG_URL" "$(tool_rpc hub_get_hub_logs '{"level":"error","limit":60}')"
section "hub system logs via watchdog: WARNINGS (newest 40)" "$WATCHDOG_URL" "$(tool_rpc hub_get_hub_logs '{"level":"warn","limit":40}')"
section "hub_list_app_instances via watchdog (ALL running app instances)" "$WATCHDOG_URL" "$(tool_rpc hub_list_app_instances '{}')"
section "hub_list_libraries via watchdog (duplicate name+namespace = the #include hazard)" "$WATCHDOG_URL" "$(tool_rpc hub_list_libraries '{}')"
section "hub_list_bundles via watchdog (stale bundle containers)" "$WATCHDOG_URL" "$(tool_rpc hub_list_bundles '{}')"

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

# LIVE WEDGE MATRIX -- the probe's deliberate writes, all against sacrificial test
# devices. Four differential checks split the failure domain conclusively:
#   A) the persistent BAT scaffold (an MCP-app child device created AFTER the last
#      boot) -- the device the suite keeps failing on;
#   B) an OLD MCP-app child device (created in an earlier era, survived many app
#      source swaps) -- if A fails and B works, device CREATION is what broke;
#   C) a NON-MCP-child device (the _CI_ Virtual Thermostat) -- if A+B fail and C
#      works, the wedge is scoped to the MCP app's children; if C also fails the
#      hub's whole device-event subsystem is stalled;
#   D) a FRESH device created right here -- replicates exactly what the failing
#      e2e test does, then deletes it.
echo "######## LIVE wedge matrix (sacrificial test devices only) ########"

wedge_check_switch() { # $1 check label, $2 device id
  local label="$1" did="$2" cur val target
  cur=$(mcp_text "$MCP_URL" "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${did}\",\"attribute\":\"switch\"}")")
  echo "[$label] device ${did} current state: ${cur}"
  val=$(printf '%s' "$cur" | jq -r '.value // empty' 2>/dev/null)
  if [ "$val" = "on" ]; then target="off"; else target="on"; fi
  curl -sS --max-time 30 -X POST "$MCP_URL" -H "Content-Type: application/json" \
    --data-binary "$(tool_rpc hub_call_device_command "{\"deviceId\":\"${did}\",\"command\":\"${target}\"}")" >/dev/null
  section "[$label] poll device ${did} -> ${target} (timedOut=true = wedged)" "$MCP_URL" \
    "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${did}\",\"attribute\":\"switch\",\"expectedValue\":\"${target}\",\"timeoutMs\":4000}")"
}

# A) the persistent BAT scaffold
SCAFFOLD_ID=$(printf '%s' "$ALL_DEVICES" | jq -r '.[] | select((.label // .name // "") | endswith("Action_Switch")) | .id' 2>/dev/null | head -1)
if [ -n "$SCAFFOLD_ID" ]; then
  wedge_check_switch "A: BAT scaffold (new MCP child)" "$SCAFFOLD_ID"
else
  echo "(A: no Action_Switch scaffold found -- skipped)"
fi

# B) an OLD MCP-managed child switch from an earlier test era
OLD_MCP_ID=$(printf '%s' "$ALL_DEVICES" | jq -r '.[] | select(.mcpManaged == true) | select((.label // "") | test("Test Plug|_CI_Virtual Switch")) | .id' 2>/dev/null | head -1)
if [ -n "$OLD_MCP_ID" ]; then
  wedge_check_switch "B: old MCP child" "$OLD_MCP_ID"
else
  echo "(B: no old MCP-managed switch found -- skipped)"
fi

# C) a NON-MCP-child device: the _CI_ Virtual Thermostat (system driver, different
# parent). Toggle its heating setpoint between 68 and 69 and poll for the change.
THERMO_ID=$(printf '%s' "$ALL_DEVICES" | jq -r '.[] | select((.label // "") == "_CI_Virtual Thermostat") | .id' 2>/dev/null | head -1)
if [ -n "$THERMO_ID" ]; then
  CUR_SP=$(mcp_text "$MCP_URL" "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${THERMO_ID}\",\"attribute\":\"heatingSetpoint\"}")")
  echo "[C: non-MCP thermostat] device ${THERMO_ID} current heatingSetpoint: ${CUR_SP}"
  SP_VAL=$(printf '%s' "$CUR_SP" | jq -r '.value // empty' 2>/dev/null | cut -d. -f1)
  if [ "$SP_VAL" = "69" ]; then SP_TARGET="68"; else SP_TARGET="69"; fi
  curl -sS --max-time 30 -X POST "$MCP_URL" -H "Content-Type: application/json" \
    --data-binary "$(tool_rpc hub_call_device_command "{\"deviceId\":\"${THERMO_ID}\",\"command\":\"setHeatingSetpoint\",\"parameters\":[\"${SP_TARGET}\"]}")" >/dev/null
  section "[C: non-MCP thermostat] poll heatingSetpoint -> ${SP_TARGET} (timedOut=true = wedged)" "$MCP_URL" \
    "$(tool_rpc hub_get_device_attribute "{\"deviceId\":\"${THERMO_ID}\",\"attribute\":\"heatingSetpoint\",\"expectedValues\":[\"${SP_TARGET}\",\"${SP_TARGET}.0\"],\"timeoutMs\":4000}")"
else
  echo "(C: no _CI_Virtual Thermostat found -- skipped)"
fi

# D) a fresh MCP child created right now (exactly what the failing test does), then deleted.
FRESH=$(mcp_text "$MCP_URL" "$(tool_rpc hub_manage_virtual_device '{"action":"create","deviceType":"Virtual Switch","deviceLabel":"BAT_E2E_ProbeFresh","confirm":true}')")
FRESH_ID=$(printf '%s' "$FRESH" | jq -r '.device.id // empty' 2>/dev/null)
FRESH_DNI=$(printf '%s' "$FRESH" | jq -r '.device.deviceNetworkId // empty' 2>/dev/null)
if [ -n "$FRESH_ID" ]; then
  echo "[D: fresh MCP child] created device ${FRESH_ID} (DNI ${FRESH_DNI})"
  wedge_check_switch "D: fresh MCP child" "$FRESH_ID"
  if [ -n "$FRESH_DNI" ]; then
    curl -sS --max-time 30 -X POST "$MCP_URL" -H "Content-Type: application/json" \
      --data-binary "$(tool_rpc hub_manage_virtual_device "{\"action\":\"delete\",\"deviceNetworkId\":\"${FRESH_DNI}\",\"confirm\":true}")" >/dev/null
    echo "[D] fresh device deleted"
  fi
else
  echo "(D: fresh-device create did not return an id: $(printf '%s' "$FRESH" | head -c 300))"
fi

echo "######## Watchdog flag / marker files ########"
for f in e2e-deadman-v2.json e2e-deferred-native-rules.json mcp-main-deployed-sha.txt; do
  section "watchdog read ${f}" "$WATCHDOG_URL" "$(tool_rpc hub_read_file "{\"fileName\":\"${f}\"}")"
done

echo "######## PROBE COMPLETE ########"
