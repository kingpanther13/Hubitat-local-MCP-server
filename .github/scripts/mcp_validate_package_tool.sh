#!/usr/bin/env bash
# Validate the hub_update_package dev tool end-to-end on the live test hub, in
# dryRun mode (ZERO writes). Proves: the tool is present (Developer Mode is on),
# it fetches the PR app source, parses its #include directives, and resolves the
# MCP server's own Apps Code class id -- the full discovery path -- without the
# flaky self-update recompile a real deploy would trigger.
#
# This runs AFTER mcp_watchdog_deploy.sh has landed THIS PR's app on the hub, so
# hub_update_package exists there to call.
#
# Usage: mcp_validate_package_tool.sh
# Env:   MCP_URL               -- full cloud OAuth URL with access_token
#        PR_RAW_BASE           -- e.g. https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA (the deploy ref)
#
# Blocking gate: the workflow step that runs this has NO continue-on-error, so a
# non-success (exit 1) here fails the e2e job -- e2e passing is what shows the tool
# works. It runs AFTER the e2e suite (workflow step ordering) so a tool regression
# can't skip or mask that suite.

set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (raw GitHub base for the PR head repo)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (PR head SHA)}"

mcp_call() {
  curl -sS --max-time 120 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

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
# Issue #250 output shape: plannedBundles[] + plannedApps[] (the self app carries isSelf=true);
# the old registry tool's appClassId / plannedLibraries are gone.
SELF_CLASS=$(echo "$TEXT" | jq -r 'first(.plannedApps[]? | select(.isSelf == true) | .classId) // empty')
N_INCLUDES=$(echo "$TEXT" | jq -r '(.includes // []) | length')
N_BUNDLES=$(echo "$TEXT" | jq -r '(.plannedBundles // []) | length')
N_APPS=$(echo "$TEXT" | jq -r '(.plannedApps // []) | length')
echo "Tool response: success=$OK dryRun=$DRY selfAppClass=$SELF_CLASS includes=$N_INCLUDES plannedBundles=$N_BUNDLES plannedApps=$N_APPS abortReason=$(echo "$TEXT" | jq -r '.abortReason // "none"')"

if [ "$OK" != "true" ] || [ "$DRY" != "true" ]; then
  echo "::error::hub_update_package dryRun did not succeed: $(echo "$TEXT" | jq -c '{success,dryRun,aborted,abortReason,error}')"
  exit 1
fi

if [ -z "$SELF_CLASS" ] || [ "$SELF_CLASS" = "null" ]; then
  echo "::error::hub_update_package dryRun succeeded but plannedApps carried no self app (isSelf=true) with a class id: $(echo "$TEXT" | jq -c '.plannedApps')"
  exit 1
fi

# The PR app #includes libraries, so the full-repair plan MUST carry a bundle to deliver them
# (issue #250 -- the registry-free, bundle-based deploy). 0 bundles with >0 #includes is a regression.
if [ "$N_INCLUDES" -gt 0 ] && [ "$N_BUNDLES" -lt 1 ]; then
  echo "::error::hub_update_package dryRun planned $N_INCLUDES #include(s) but 0 bundles -- the bundle that delivers them is missing from the plan"
  exit 1
fi

echo "hub_update_package dryRun validated: tool present, manifest fetched, ${N_BUNDLES} bundle(s) + ${N_APPS} app(s) planned, self app-class ${SELF_CLASS} resolved (deployed last)."
