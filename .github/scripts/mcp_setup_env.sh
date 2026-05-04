#!/usr/bin/env bash
# Configure the test hub for E2E by enabling the toggles tests/e2e_test.py
# depends on. Captures pre-run state to a file so mcp_restore_env.sh can
# put things back the way they were.
#
# Usage:  mcp_setup_env.sh
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         RUNNER_TEMP — GHA-provided temp dir; falls back to /tmp
#
# Toggles enabled (all in update_mcp_settings allowlist):
#   - enableRuleEngine     (create_rule / update_rule / delete_rule paths)
#   - enableHubAdminRead   (get_hub_info PII fields, list_hub_apps, etc.)
#   - enableBuiltinAppRead (list_installed_apps, list_rm_rules)
#
# Not touched here:
#   - enableHubAdminWrite — excluded from update_mcp_settings allowlist by
#     design (footgun: would let the agent disable its own write path
#     mid-session). Must be enabled manually in the MCP rule app UI before
#     any write-bearing test can pass.
#   - enableDeveloperMode — same lockout protection. Must be on in the UI
#     before this script can call update_mcp_settings at all (script aborts
#     with a focused error if it isn't).

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
PRE_STATE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_state.json"

mcp_call() {
  curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d "$1"
}

# Pull the toggle state from get_hub_info. The settings-visibility block on
# lines 3026+ of hubitat-mcp-server.groovy exposes these fields without
# requiring Hub Admin Read.
PRE_INFO_JSON="$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_hub_info","arguments":{}}}' \
  | jq -r '.result.content[0].text')"

DEV_MODE="$(echo "$PRE_INFO_JSON" | jq -r '.developerModeEnabled // false')"
if [ "$DEV_MODE" != "true" ]; then
  echo "::error::Developer Mode is OFF on the test hub. Enable 'Enable Developer Mode Tools' in the MCP rule app settings (default OFF) so update_mcp_settings can configure the test environment."
  exit 1
fi

PRE_RULE_ENGINE="$(echo "$PRE_INFO_JSON"  | jq -r '.ruleEngineEnabled  // true')"
PRE_HUB_READ="$(echo    "$PRE_INFO_JSON"  | jq -r '.hubAdminReadEnabled  // false')"
PRE_BUILTIN_READ="$(echo "$PRE_INFO_JSON" | jq -r '.builtinAppReadEnabled // false')"

jq -nc \
  --argjson re  "$PRE_RULE_ENGINE" \
  --argjson hr  "$PRE_HUB_READ" \
  --argjson br  "$PRE_BUILTIN_READ" \
  '{enableRuleEngine: $re, enableHubAdminRead: $hr, enableBuiltinAppRead: $br}' \
  > "$PRE_STATE_FILE"

echo "Captured pre-run state -> $PRE_STATE_FILE"
cat "$PRE_STATE_FILE"

# Enable everything E2E needs in a single batch. update_mcp_settings is
# atomic — a single bad key would block the whole batch.
mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"manage_mcp_self","arguments":{"tool":"update_mcp_settings","args":{"settings":{"enableRuleEngine":true,"enableHubAdminRead":true,"enableBuiltinAppRead":true},"confirm":true}}}}' \
  | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null

echo "Test environment configured: enableRuleEngine=true, enableHubAdminRead=true, enableBuiltinAppRead=true"
