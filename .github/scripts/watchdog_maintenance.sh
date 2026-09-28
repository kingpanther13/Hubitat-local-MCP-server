#!/usr/bin/env bash
# Called only by the explicitly dispatched maintenance job, never by normal E2E.
set -euo pipefail
: "${WATCHDOG_URL:?}" "${MCP_URL:?}" "${GITHUB_SHA:?}" "${GITHUB_REPOSITORY:?}" "${RUNNER_TEMP:?}"
source "$(dirname "$0")/mcp_watchdog_lib.sh"

# An armed restore must finish before its watchdog can be replaced.
FLAG=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_read_file","arguments":{"fileName":"e2e-deadman-v2.json"}}}')
printf '%s' "$FLAG" | jq -e '.success == true and .hasMore == false and (.content | fromjson | .armed == false)' >/dev/null || {
  echo '::error::Cannot verify a disarmed watchdog; no code changed.'; exit 1;
}
CLASS_ID=$(resolve_class_id mcp 'E2E Dead-Man Watchdog v2')
SOURCE_URL="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/$GITHUB_SHA/e2e-deadman-watchdog-v2.groovy"

# Keep the full prior source on the runner without overwriting any hub restore cache.
OFFSET=0
: > "$RUNNER_TEMP/watchdog-before.groovy"
while :; do
  RPC=$(jq -nc --arg id "$CLASS_ID" --argjson offset "$OFFSET" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:$offset,length:32000,noSave:true}}}')
  CHUNK=$(call_tool_retry "$RPC")
  printf '%s' "$CHUNK" | jq -e --argjson offset "$OFFSET" '.success == true and .offset == $offset and (.source | type == "string") and (.hasMore | type == "boolean")' >/dev/null
  printf '%s' "$CHUNK" | jq -j '.source' >> "$RUNNER_TEMP/watchdog-before.groovy"
  [ "$(printf '%s' "$CHUNK" | jq -r '.hasMore')" = true ] || break
  NEXT=$(printf '%s' "$CHUNK" | jq -er '.nextOffset')
  [ "$NEXT" -gt "$OFFSET" ]
  OFFSET=$NEXT
done
test -s "$RUNNER_TEMP/watchdog-before.groovy"
deploy_app_via_watchdog "$CLASS_ID" "$SOURCE_URL" watchdog "$CLASS_ID"

# Prove the new tool exists and that the main endpoint observes the setting.
CATALOG=$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')
printf '%s' "$CATALOG" | jq -e 'any(.result.tools[]; .name == "hub_set_mcp_developer_mode")' >/dev/null
APP_ID=$(printf '%s' "$MCP_URL" | sed -nE 's|.*/apps/([0-9]+)/mcp\?.*|\1|p')
[[ "$APP_ID" =~ ^[0-9]+$ ]]
RPC=$(jq -nc --arg id "$APP_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_mcp_developer_mode",arguments:{appId:$id,enabled:true,confirm:true}}}')
# Submit once; a relay timeout does not mean that the write failed.
call_tool "$RPC" >/dev/null
for attempt in 1 2 3 4 5; do
  INFO=$(curl -sS --fail --max-time 30 -X POST "$MCP_URL" -H 'Content-Type: application/json' \
    -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: hub_get_info' \
    --data-binary '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' || true)
  if printf '%s' "$INFO" | jq -e '.error == null and .result.isError != true and (.result.content[0].text | fromjson | .developerModeEnabled == true)' >/dev/null 2>&1; then
    echo "Watchdog updated from $GITHUB_SHA; main server verified Developer Mode ON."
    exit 0
  fi
  [ "$attempt" -eq 5 ] || sleep 2
done
echo '::error::Developer Mode was not verified ON after watchdog bootstrap.'
exit 1
