#!/usr/bin/env bash
# Restore the test hub's MCP settings to whatever mcp_setup_env.sh captured
# pre-run. Tolerant of missing pre-state file (early-exit no-op) so it can
# safely live in an `if: always()` workflow step alongside lease release.
#
# Usage:  mcp_restore_env.sh
# Env:    MCP_URL — full cloud OAuth URL with access_token
#         RUNNER_TEMP — GHA-provided temp dir; falls back to /tmp

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
PRE_STATE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_state.json"

if [ ! -f "$PRE_STATE_FILE" ]; then
  echo "No pre-state file at $PRE_STATE_FILE — setup likely failed before capture. Skipping restore."
  exit 0
fi

PRE_STATE="$(cat "$PRE_STATE_FILE")"
echo "Restoring pre-run state: $PRE_STATE"

# update_mcp_settings expects a Map<key, value>. The pre-state file is
# already in the right shape — wrap it in the tool's argument envelope.
SETTINGS_PAYLOAD="$(jq -nc --argjson s "$PRE_STATE" '{settings: $s, confirm: true}')"
RPC_BODY="$(jq -nc --argjson p "$SETTINGS_PAYLOAD" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"manage_mcp_self",arguments:{tool:"update_mcp_settings",args:$p}}}')"

curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -d "$RPC_BODY" \
  | jq -e '.result.content[0].text | fromjson | .success == true' >/dev/null

echo "Test environment restored from pre-run state."
