#!/usr/bin/env bash
# Configure the test hub for E2E by enabling the toggles tests/e2e_test.py
# depends on. Captures pre-run state to a file so mcp_restore_env.sh can
# put things back the way they were.
#
# Usage:  mcp_setup_env.sh
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         RUNNER_TEMP — GHA-provided temp dir; falls back to /tmp
#
# IMPORTANT: this script runs BEFORE the PR source is deployed (see hub-e2e.yml:
# setup -> deploy -> tests -> restore), so it talks to the PRE-DEPLOY baseline app.
# It therefore only touches `enableCustomRuleEngine`, the one toggle key that is
# stable across server versions AND is needed by the custom_* e2e tests. The setting
# persists through the source swap, so the deployed PR app inherits it.
#
# Toggles enabled:
#   - enableCustomRuleEngine (custom_create_rule / custom_update_rule / custom_delete_rule paths)
#
# Not touched here:
#   - Read / Write access — under the universal Read/Write masters (PR #113) both
#     default ON in the deployed app, so read- and write-bearing tests pass without
#     any setup. (Under older server versions the equivalent Hub Admin / Built-in App
#     toggles are likewise irrelevant to this pre-deploy step.)
#   - enableDeveloperMode — lockout protection. Must be on in the UI before this
#     script can call update_mcp_settings at all (script aborts with a focused
#     error if it isn't).

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
PRE_STATE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_state.json"

mcp_call() {
  curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d "$1"
}

# Pull the toggle state from hub_get_info. The settings-visibility block on
# lines 3026+ of hubitat-mcp-server.groovy exposes these fields without
# requiring Hub Admin Read.
PRE_INFO_JSON="$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' \
  | jq -r '.result.content[0].text')"

DEV_MODE="$(echo "$PRE_INFO_JSON" | jq -r '.developerModeEnabled // false')"
if [ "$DEV_MODE" != "true" ]; then
  echo "::error::Developer Mode is OFF on the test hub. Enable 'Enable Developer Mode Tools' in the MCP rule app settings (default OFF) so update_mcp_settings can configure the test environment."
  exit 1
fi

PRE_RULE_ENGINE="$(echo "$PRE_INFO_JSON"  | jq -r '.customRuleEngineEnabled // false')"

# Record what hardware/firmware/server version this e2e run actually exercised.
# Different firmware can react differently to the same tool call, so every run
# stamps this into the log. Reads the already-fetched PRE_INFO_JSON (no extra hub
# call); // "unknown" keeps a missing field from aborting setup.
FW_VERSION="$(echo "$PRE_INFO_JSON" | jq -r '.firmwareVersion // "unknown"')"
HUB_MODEL="$(echo "$PRE_INFO_JSON"  | jq -r '.model // "unknown"')"
MCP_VER="$(echo "$PRE_INFO_JSON"    | jq -r '.mcpServerVersion // "unknown"')"
echo "::notice::E2E hub firmware=${FW_VERSION} model=${HUB_MODEL} mcpServerVersion=${MCP_VER}"

jq -nc \
  --argjson re  "$PRE_RULE_ENGINE" \
  '{enableCustomRuleEngine: $re}' \
  > "$PRE_STATE_FILE"

echo "Captured pre-run state -> $PRE_STATE_FILE"
cat "$PRE_STATE_FILE"

# Stamp a hub backup FIRST. The hub_update_mcp_settings call below is destructive-confirm-gated
# (confirm:true requires a hub backup within the last 24h). tests/e2e_test.py stamps a mock backup
# too, but only LATER in the run (after this configure step), so a >24h gap since the previous e2e
# run leaves the gate unsatisfied and configure fails ("BACKUP REQUIRED: No hub backup found within
# the last 24 hours") before the test run ever gets to stamp it. Stamping here makes configure
# self-sufficient regardless of the gap. Prefer the MOCK backup (stamps only the 24h gate record, no
# real backupDB write); fall back to a real backup on an older server that lacks mock support.
echo "Stamping a backup to satisfy the destructive-confirm 24h gate before enabling toggles..."
BACKUP_RESP="$(mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true,"mock":true}}}' 2>/dev/null || true)"
if printf '%s' "$BACKUP_RESP" | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null 2>&1; then
  echo "  Backup gate stamped (MOCK -- no real backupDB write)."
else
  echo "::notice::Mock backup unsupported/failed on the baseline app -- falling back to a real backup."
  mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hub_create_backup","arguments":{"confirm":true}}}' \
    | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null || {
    echo "::error::Could not create a hub backup (mock AND real failed). hub_update_mcp_settings below needs one (destructive-confirm 24h gate). Check the test hub."; exit 1; }
  echo "  Backup gate stamped (REAL backup)."
fi

# Configure the toggles the e2e suite needs. enableCustomRuleEngine ON (custom_* tests).
# enableMandatoryBPS OFF: the best-practice gate (issue #299) ships ON by default, which would
# block every keyless write in the suite; the best_practice_gating tests flip it on/off themselves.
# hub_update_mcp_settings is itself gate-exempt, so this disable lands even with the gate ON.
# Read/Write are masters (default ON in the deployed PR app).
mcp_call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hub_manage_mcp","arguments":{"tool":"hub_update_mcp_settings","args":{"settings":{"enableCustomRuleEngine":true,"enableMandatoryBPS":false},"confirm":true}}}}' \
  | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null

echo "Test environment configured: enableCustomRuleEngine=true, enableMandatoryBPS=false (Read/Write masters ON by default in the deployed app)"
