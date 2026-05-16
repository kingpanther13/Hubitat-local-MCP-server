#!/usr/bin/env bash
# Push the pre-deploy parent-app source snapshot back to the test hub so
# the hub is returned to whatever was live before this run mutated it.
# Reads the Apps Code class ID that mcp_deploy_source.sh resolved (the
# installed-instance ID from MCP_URL is NOT the same as the source-class
# ID; they live in separate Hubitat namespaces) from the sibling file
# alongside the snapshot. Tolerant of a missing pre-source file (early-
# exit no-op) so it can live in an `if: always()` workflow step alongside
# the env restore + lease release.
#
# Usage:  mcp_restore_source.sh
# Env:    MCP_URL              -- full cloud OAuth URL with access_token
#         RUNNER_TEMP          -- GHA-provided temp dir; falls back to /tmp

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

PRE_SOURCE_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source.groovy"
CLASS_ID_FILE="${RUNNER_TEMP:-/tmp}/mcp_pre_source_class_id"

if [ ! -f "$PRE_SOURCE_FILE" ]; then
  echo "No pre-source snapshot at $PRE_SOURCE_FILE -- deploy step likely failed before capture. Skipping restore."
  exit 0
fi

if [ ! -f "$CLASS_ID_FILE" ]; then
  echo "::warning::No class-ID sidecar at $CLASS_ID_FILE -- can't target the right Apps Code entry. Skipping restore."
  exit 0
fi

CLASS_ID=$(cat "$CLASS_ID_FILE")
if [ -z "$CLASS_ID" ]; then
  echo "::warning::Class-ID sidecar is empty. Skipping restore."
  exit 0
fi

PRE_BYTES=$(wc -c < "$PRE_SOURCE_FILE")
if [ "$PRE_BYTES" -lt 1000 ]; then
  echo "::warning::Pre-source snapshot is suspiciously small ($PRE_BYTES bytes); refusing to push it back as the hub source"
  exit 0
fi

echo "Restoring class $CLASS_ID source from snapshot ($PRE_BYTES bytes)..."

RESTORE_RPC_FILE="${RUNNER_TEMP:-/tmp}/mcp_restore_rpc.json"
jq -nc \
  --arg id "$CLASS_ID" \
  --rawfile src "$PRE_SOURCE_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"update_app_code",arguments:{appId:$id,source:$src,confirm:true}}}' \
  > "$RESTORE_RPC_FILE"

RESTORE_RESP_FILE="${RUNNER_TEMP:-/tmp}/mcp_restore_resp.txt"
curl -sS --max-time 120 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -w '\n%{http_code}' \
  --data-binary "@$RESTORE_RPC_FILE" > "$RESTORE_RESP_FILE" || true
rm -f "$RESTORE_RPC_FILE"

HTTP_CODE=$(tail -n1 "$RESTORE_RESP_FILE")
RESTORE_BODY=$(head -c -$((${#HTTP_CODE} + 1)) "$RESTORE_RESP_FILE")
echo "Hub responded HTTP $HTTP_CODE; body length=$(printf '%s' "$RESTORE_BODY" | wc -c)"

# Restore is in `if: always()` -- a failure here must not abort the
# remaining cleanup (lease release, env restore), so all error paths
# fall through with a warning and exit 0.
if [ "$HTTP_CODE" != "200" ]; then
  echo "::warning::Restore got HTTP $HTTP_CODE -- hub source NOT returned to pre-run state"
  echo "Body head: $(printf '%s' "$RESTORE_BODY" | head -c 800)"
  rm -f "$RESTORE_RESP_FILE"
  exit 0
fi

RESTORE_TEXT=$(printf '%s' "$RESTORE_BODY" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
rm -f "$RESTORE_RESP_FILE"

if [ -z "$RESTORE_TEXT" ]; then
  echo "::warning::Restore got 200 but body was not parseable MCP JSON -- hub source NOT returned to pre-run state"
  echo "Body head: $(printf '%s' "$RESTORE_BODY" | head -c 800)"
  exit 0
fi

RESTORE_OK=$(echo "$RESTORE_TEXT" | jq -r '.success // false')
if [ "$RESTORE_OK" != "true" ]; then
  echo "::warning::Restore failed: $(echo "$RESTORE_TEXT" | jq -r '.error // .message // empty')"
  echo "Tool response: $RESTORE_TEXT" | head -c 2000
  exit 0
fi

echo "Restore succeeded. Hub class $CLASS_ID source returned to pre-run state."
