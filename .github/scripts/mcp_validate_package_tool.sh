#!/usr/bin/env bash
# Validate the hub_update_package dev tool end-to-end on the live test hub, in
# dryRun mode (ZERO writes). Proves: the tool is present (Developer Mode is on),
# it fetches the PR app source, parses its #include directives, and resolves the
# MCP server's own Apps Code class id -- the full discovery path -- without the
# flaky self-update recompile a real deploy would trigger.
#
# This runs AFTER mcp_deploy_source.sh has landed THIS PR's app on the hub, so
# hub_update_package exists there to call.
#
# Usage: mcp_validate_package_tool.sh
# Env:   MCP_URL               -- full cloud OAuth URL with access_token
#        PR_RAW_BASE           -- e.g. https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA (the deploy ref)
#
# Non-blocking by design (the workflow step is continue-on-error): a regression
# here is surfaced loudly but must not mask the main e2e suite.

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (raw GitHub base for the PR head repo)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (PR head SHA)}"

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

echo "Calling hub_update_package(ref=${PR_HEAD_SHA_RESOLVED}, baseUrl=${PR_RAW_BASE}, dryRun=true)..."
RPC=$(jq -nc \
  --arg ref "$PR_HEAD_SHA_RESOLVED" \
  --arg base "$PR_RAW_BASE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_update_package",arguments:{ref:$ref,baseUrl:$base,dryRun:true}}}')

# One call, evaluated honestly -- no retry. The dryRun does NO writes and installs
# nothing, so there is no "did it land?" state to confirm; a blind retry would just
# re-issue the same hub-side fetch and tell us nothing new. A transient GitHub-fetch
# drop surfaces cleanly as success=false / abortReason=app_source_fetch_failed, and a
# real defect surfaces as any other non-success -- both fail the gate loudly.
echo "Calling hub_update_package(ref=${PR_HEAD_SHA_RESOLVED}, baseUrl=${PR_RAW_BASE}, dryRun=true)..."
RESP=$(mcp_call "$RPC")
TEXT=$(echo "$RESP" | jq -r '.result.content[0].text // empty')
if [ -z "$TEXT" ]; then
  echo "::error::hub_update_package returned no MCP content (cloud gateway error or empty body). resp head: $(echo "$RESP" | head -c 600)"
  exit 1
fi

OK=$(echo "$TEXT" | jq -r '.success // false')
DRY=$(echo "$TEXT" | jq -r '.dryRun // false')
APP_CLASS=$(echo "$TEXT" | jq -r '.appClassId // empty')
N_INCLUDES=$(echo "$TEXT" | jq -r '(.includes // []) | length')
N_PLANNED=$(echo "$TEXT" | jq -r '(.plannedLibraries // []) | length')
echo "Tool response: success=$OK dryRun=$DRY appClassId=$APP_CLASS includes=$N_INCLUDES plannedLibraries=$N_PLANNED abortReason=$(echo "$TEXT" | jq -r '.abortReason // "none"')"

if [ "$OK" != "true" ] || [ "$DRY" != "true" ]; then
  echo "::error::hub_update_package dryRun did not succeed: $(echo "$TEXT" | jq -c '{success,dryRun,aborted,abortReason,error}')"
  exit 1
fi

if [ -z "$APP_CLASS" ] || [ "$APP_CLASS" = "null" ]; then
  echo "::error::hub_update_package dryRun succeeded but did not resolve the self app-class id"
  exit 1
fi

echo "hub_update_package dryRun validated: tool present, source fetched/parsed, self app-class ${APP_CLASS} resolved."
