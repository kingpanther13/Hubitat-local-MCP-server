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

RESTORE_RESP=$(curl -sS --max-time 120 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  --data-binary "@$RESTORE_RPC_FILE")
rm -f "$RESTORE_RPC_FILE"

RESTORE_TEXT=$(echo "$RESTORE_RESP" | jq -r '.result.content[0].text // empty')

if [ -z "$RESTORE_TEXT" ]; then
  # `if: always()` step -- log loudly but don't fail the workflow on
  # restore failure, since lease release and env restore still need to run.
  # Maintainer will see the warning in the run summary and can manually
  # redeploy from .github/scripts/mcp_deploy_source.sh against main.
  echo "::warning::Restore failed: update_app_code returned empty MCP content"
  echo "Full response head: $(echo "$RESTORE_RESP" | head -c 1000)"
  exit 0
fi

RESTORE_OK=$(echo "$RESTORE_TEXT" | jq -r '.success // false')
if [ "$RESTORE_OK" != "true" ]; then
  echo "::warning::Restore failed: $(echo "$RESTORE_TEXT" | jq -r '.error // .message // empty')"
  echo "Tool response: $RESTORE_TEXT" | head -c 2000
  exit 0
fi

echo "Restore succeeded. Hub class $CLASS_ID source returned to pre-run state."
