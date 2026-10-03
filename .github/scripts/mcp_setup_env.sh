#!/usr/bin/env bash
# Configure the test hub for E2E by enabling the toggles tests/e2e_test.py
# depends on. Nothing restores them afterwards: the hub is dedicated to e2e.
#
# Usage:  mcp_setup_env.sh
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         WATCHDOG_URL / HUBITAT_APP_ID — watchdog endpoint and MCP instance ID (bootstrap)
#
# Runs AFTER the watchdog installs the PR (hub-e2e.yml: install -> setup -> tests -> purge), so it
# talks to the app under test, never a previous run's possibly broken one. Developer Mode is a
# standing test-hub prerequisite: enable it through the independent watchdog when necessary, then
# verify it through the main server before configuring the remaining toggles.
#
# Not touched here:
#   - Read / Write access — under the universal Read/Write masters (PR #113) both
#     default ON in the deployed app, so read- and write-bearing tests pass without
#     any setup.
#   - maxConcurrentWrites and the #299 gate — tests/e2e_test.py main() pins those for the run.

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

mcp_call() {
  local tool_name="$2"
  curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -H "MCP-Protocol-Version: 2026-07-28" \
    -H "Mcp-Method: tools/call" \
    -H "Mcp-Name: ${tool_name}" \
    -d "$1"
}

# Pull the toggle state from hub_get_info. The settings-visibility block on
# lines 3026+ of hubitat-mcp-server.groovy exposes these fields without
# requiring Hub Admin Read.
read_info() {
  mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' hub_get_info \
    | jq -er 'if .error != null or .result.isError == true then error("hub_get_info failed") else
        .result.content[0].text | fromjson |
        if type == "object" and (.developerModeEnabled | type) == "boolean" then .
        else error("hub_get_info did not return a boolean developerModeEnabled") end end'
}
PRE_INFO_JSON="$(read_info)"

DEV_MODE="$(echo "$PRE_INFO_JSON" | jq -r '.developerModeEnabled // false')"
if [ "$DEV_MODE" != "true" ]; then
  : "${WATCHDOG_URL:?WATCHDOG_URL is required to enable Developer Mode on the test hub}"
  : "${HUBITAT_APP_ID:?HUBITAT_APP_ID is required to identify the MCP server instance}"
  echo "Developer Mode is OFF; enabling it through the test hub watchdog..."
  BOOTSTRAP_RPC="$(jq -nc --arg id "$HUBITAT_APP_ID" \
    '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_mcp_developer_mode",arguments:{appId:$id,enabled:true,confirm:true}}}')"
  # Submit once, then check the main server even if the relay loses the response.
  curl -sS --fail --max-time 30 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" --data-binary "$BOOTSTRAP_RPC" >/dev/null || true
  for attempt in 1 2 3 4 5; do
    VERIFIED_INFO="$(read_info 2>/dev/null || true)"
    if printf '%s' "$VERIFIED_INFO" | jq -e '.developerModeEnabled == true' >/dev/null 2>&1; then
      DEV_MODE=true
      break
    fi
    [ "$attempt" -eq 5 ] || sleep 2
  done
  if [ "$DEV_MODE" != "true" ]; then
    echo "::error::Could not verify Developer Mode enabled. Ensure the standing watchdog supports hub_set_mcp_developer_mode; update the watchdog out-of-band before retrying."
    exit 1
  fi
  echo "Developer Mode verified ON (retained as a standing E2E prerequisite)."
fi

# Record what hardware/firmware/server version this e2e run actually exercised.
# Different firmware can react differently to the same tool call, so every run
# stamps this into the log. Reads the already-fetched PRE_INFO_JSON (no extra hub
# call); // "unknown" keeps a missing field from aborting setup.
FW_VERSION="$(echo "$PRE_INFO_JSON" | jq -r '.firmwareVersion // "unknown"')"
HUB_MODEL="$(echo "$PRE_INFO_JSON"  | jq -r '.model // "unknown"')"
MCP_VER="$(echo "$PRE_INFO_JSON"    | jq -r '.mcpServerVersion // "unknown"')"
echo "::notice::E2E hub firmware=${FW_VERSION} model=${HUB_MODEL} mcpServerVersion=${MCP_VER}"

# Stamp a hub backup FIRST. The hub_update_mcp_settings call below is destructive-confirm-gated
# (confirm:true requires a hub backup within the last 24h). tests/e2e_test.py stamps a mock backup
# too, but only LATER in the run (after this configure step), so a >24h gap since the previous e2e
# run leaves the gate unsatisfied and configure fails ("BACKUP REQUIRED: No hub backup found within
# the last 24 hours") before the test run ever gets to stamp it. Stamping here makes configure
# self-sufficient regardless of the gap. The MOCK backup stamps only the 24h gate record, no real
# backupDB write; a real backup is the fallback when the mock is refused.
echo "Stamping a backup to satisfy the destructive-confirm 24h gate before enabling toggles..."
# bestPracticeKey on both: hub_create_backup is a WRITE, and the #299 gate ships ON. The runner
# pins it off, but only AFTER this step -- so a run that died between the test that re-enables the
# gate and its restore leaves it on, and every backup here is refused. A refusal is -32602 on
# HTTP 200, which surfaced as a bare `jq: null (null)` and read as "the hub can't back up".
BACKUP_RESP="$(mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true,"mock":true,"bestPracticeKey":"bps-ack-299"}}}' hub_create_backup 2>/dev/null || true)"
if printf '%s' "$BACKUP_RESP" | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null 2>&1; then
  echo "  Backup gate stamped (MOCK -- no real backupDB write)."
else
  echo "::notice::Mock backup refused -- falling back to a real backup."
  REAL_BACKUP_RESP="$(mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true,"bestPracticeKey":"bps-ack-299"}}}' hub_create_backup 2>/dev/null || true)"
  if ! printf '%s' "$REAL_BACKUP_RESP" | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null 2>&1; then
    RB_ERR="$(printf '%s' "$REAL_BACKUP_RESP" | jq -r '
      .error.message //
      (.result.content[0].text | fromjson? | .error) //
      (.result.content[0].text | fromjson? | .message) //
      empty
    ' 2>/dev/null || echo "")"
    [ -n "$RB_ERR" ] && echo "::error::Backup call refused by the hub: ${RB_ERR}"
    echo "::error::Could not create a hub backup (mock AND real failed). hub_update_mcp_settings below needs one (destructive-confirm 24h gate). Check the test hub."
    exit 1
  fi
  echo "  Backup gate stamped (REAL backup)."
fi

# Enable the toggles the e2e suite needs. Read/Write are masters (default ON in the deployed PR
# app). enableCustomRuleEngine supports the remaining legacy rule tests;
# useGateways pins GATEWAY MODE ON for the
# e2e hub: the suite is meant to exercise the production gateway-routed surface (the catalog real
# clients see), so we set it explicitly rather than relying on the null->on default in case a prior
# run left it off.
mcp_call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hub_manage_mcp","arguments":{"tool":"hub_update_mcp_settings","args":{"settings":{"enableCustomRuleEngine":true,"useGateways":true},"confirm":true}}}}' hub_manage_mcp \
  | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null

echo "Test environment configured: enableCustomRuleEngine=true, useGateways=true (gateway mode ON; Read/Write masters default ON)"
