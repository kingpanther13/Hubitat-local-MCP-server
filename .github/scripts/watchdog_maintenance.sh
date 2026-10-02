#!/usr/bin/env bash
# Called only by the explicitly dispatched maintenance job, never by normal E2E.
set -euo pipefail
: "${WATCHDOG_URL:?}" "${MCP_URL:?}" "${GITHUB_SHA:?}" "${GITHUB_REPOSITORY:?}" "${RUNNER_TEMP:?}"
source "$(dirname "$0")/mcp_watchdog_lib.sh"
: "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
PHASE=${1:-}
case "$PHASE" in
  prepare|deploy) ;;
  *) echo '::error::Expected prepare or deploy maintenance phase.'; exit 1 ;;
esac

# The update is a watchdog self-update, so the configured endpoint must be v3 itself, and it must
# not be holding a package deployment: v3 blocks manual writes during a hold.
INFO=$(call_tool_retry '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}')
printf '%s' "$INFO" | jq -e '.watchdogVersion == 3' >/dev/null || {
  echo '::error::WATCHDOG_MCP_URL does not answer as watchdog v3; no code changed.'; exit 1;
}
printf '%s' "$INFO" | jq -e '.packageDeployment == null or .packageDeployment.hold == false' >/dev/null || {
  echo '::error::A package deployment is held; release it before watchdog maintenance. No code changed.'; exit 1;
}
CLASS_ID=$(resolve_class_id mcp 'E2E Dead-Man Watchdog v3')
SOURCE_URL="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/$GITHUB_SHA/e2e-deadman-watchdog-v3.groovy"

if [ "$PHASE" = prepare ]; then
  # Keep the full prior source on the runner; noSave keeps the read off the hub's File Manager.
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
  SOURCE_SHA=$(sha256sum "$RUNNER_TEMP/watchdog-before.groovy" | cut -d ' ' -f1)
  jq -n --arg classId "$CLASS_ID" --arg repository "$GITHUB_REPOSITORY" \
    --arg targetSha "$GITHUB_SHA" --arg runId "$GITHUB_RUN_ID" \
    --arg attempt "$GITHUB_RUN_ATTEMPT" --arg sourceSha256 "$SOURCE_SHA" \
    '{classId:$classId,repository:$repository,targetSha:$targetSha,runId:$runId,attempt:$attempt,sourceSha256:$sourceSha256}' \
    > "$RUNNER_TEMP/watchdog-before.json"
  echo 'Prior source prepared; upload and download its artifact before deploying.'
  exit 0
fi

# The workflow downloads the uploaded artifact here; runner-local source is insufficient.
BACKUP_DIR="$RUNNER_TEMP/watchdog-maintenance-verified"
test -s "$BACKUP_DIR/watchdog-before.groovy"
SOURCE_SHA=$(sha256sum "$BACKUP_DIR/watchdog-before.groovy" | cut -d ' ' -f1)
jq -e --arg classId "$CLASS_ID" --arg repository "$GITHUB_REPOSITORY" \
  --arg targetSha "$GITHUB_SHA" --arg runId "$GITHUB_RUN_ID" \
  --arg attempt "$GITHUB_RUN_ATTEMPT" --arg sourceSha256 "$SOURCE_SHA" \
  '.classId == $classId and .repository == $repository and .targetSha == $targetSha and
   .runId == $runId and .attempt == $attempt and .sourceSha256 == $sourceSha256' \
  "$BACKUP_DIR/watchdog-before.json" >/dev/null
echo "Downloaded prior watchdog source verified for Apps Code $CLASS_ID."
# V3 refuses this self-update unless the MCP server's endpoint answers: that server is the only
# path that could repair a bad watchdog update.
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
