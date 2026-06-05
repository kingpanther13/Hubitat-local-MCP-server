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

# The dryRun fetches the full ~1.5MB PR source hub-side to parse #includes; that can
# exceed the cloud OAuth gateway's ~10s window and bounce back a 5xx / empty body even
# though the hub is fine. The call does NO writes, so retrying is safe. Retry transient
# (empty body / app_source_fetch_failed) responses; fail fast on a definitive tool abort.
MAX_ATTEMPTS=4
RETRY_WAIT=15
RESP=""
TEXT=""
attempt=1
while [ "$attempt" -le "$MAX_ATTEMPTS" ]; do
  echo "Attempt ${attempt}/${MAX_ATTEMPTS}: calling hub_update_package(ref=${PR_HEAD_SHA_RESOLVED}, baseUrl=${PR_RAW_BASE}, dryRun=true)..."
  RESP=$(mcp_call "$RPC" || true)
  TEXT=$(echo "$RESP" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

  if [ -n "$TEXT" ]; then
    OK=$(echo "$TEXT" | jq -r '.success // false')
    DRY=$(echo "$TEXT" | jq -r '.dryRun // false')
    APP_CLASS=$(echo "$TEXT" | jq -r '.appClassId // empty')
    REASON=$(echo "$TEXT" | jq -r '.abortReason // empty')
    N_INCLUDES=$(echo "$TEXT" | jq -r '(.includes // []) | length')
    N_PLANNED=$(echo "$TEXT" | jq -r '(.plannedLibraries // []) | length')
    echo "Tool response: success=$OK dryRun=$DRY appClassId=$APP_CLASS includes=$N_INCLUDES plannedLibraries=$N_PLANNED abortReason=${REASON:-none}"

    if [ "$OK" = "true" ] && [ "$DRY" = "true" ] && [ -n "$APP_CLASS" ] && [ "$APP_CLASS" != "null" ]; then
      echo "hub_update_package dryRun validated: tool present, source fetched/parsed, self app-class ${APP_CLASS} resolved."
      exit 0
    fi

    # Definitive (non-transient) failures -- don't burn retries on these.
    if [ "$REASON" = "self_app_unresolved" ] || [ "$REASON" = "unmapped_include" ]; then
      echo "::error::hub_update_package dryRun failed (non-transient): $(echo "$TEXT" | jq -c '{success,dryRun,aborted,abortReason,error}')"
      exit 1
    fi
    echo "::notice::non-success / transient response (abortReason=${REASON:-none}); will retry."
  else
    echo "::notice::empty MCP content (likely cloud gateway timeout during the hub-side source fetch); will retry. resp head: $(echo "$RESP" | head -c 300)"
  fi

  attempt=$((attempt + 1))
  [ "$attempt" -le "$MAX_ATTEMPTS" ] && sleep "$RETRY_WAIT"
done

echo "::error::hub_update_package dryRun did not validate after ${MAX_ATTEMPTS} attempts. Last response head: $(echo "$RESP" | head -c 800)"
exit 1
