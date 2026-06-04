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

jq -nc \
  --argjson re  "$PRE_RULE_ENGINE" \
  '{enableCustomRuleEngine: $re}' \
  > "$PRE_STATE_FILE"

echo "Captured pre-run state -> $PRE_STATE_FILE"
cat "$PRE_STATE_FILE"

# Enable the one toggle the e2e suite needs that is not ON by default. Read/Write
# are masters (default ON in the deployed PR app); enableCustomRuleEngine is the
# only stable key this pre-deploy step sets, and it persists through the deploy.
mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"hub_manage_mcp","arguments":{"tool":"hub_update_mcp_settings","args":{"settings":{"enableCustomRuleEngine":true},"confirm":true}}}}' \
  | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null

echo "Test environment configured: enableCustomRuleEngine=true (Read/Write masters are ON by default in the deployed app)"
