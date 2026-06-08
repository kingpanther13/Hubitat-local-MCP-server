#!/usr/bin/env bash
# Self-update the dead-man watchdog to THIS PR's e2e-deadman-watchdog-v2.groovy, over the watchdog's OWN
# /mcp endpoint ($WATCHDOG_URL), BEFORE arm/install. level99 only ever does the initial import; from then
# on every e2e run pulls the PR's watchdog version itself -- so e2e always exercises the watchdog the PR
# ships, not whatever copy happens to be installed.
#
# ALWAYS deploy (NO length-skip -- a length-preserving edit must not leave the OLD watchdog live and
# validate the wrong code green). The deploy is CONFIRMED via a FRESH lastSelfDeploy success (shared
# helper), which survives the self-reload's dropped response AND a same-length change -- so a rejected or
# never-started update can't false-green. Brick-safe: a compile error is rejected and the OLD watchdog
# keeps running (Hubitat never replaces non-compiling code). After confirmation we cross-check the live
# source length is this PR's, and assert the /mcp transport (tools/list) so a compiled-but-broke-its-
# endpoint watchdog fails the run loudly.
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL must be set (from the WATCHDOG_MCP_URL secret)}"
: "${PR_RAW_BASE:?PR_RAW_BASE must be set}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED must be set}"

WD_NAMESPACE="mcp"
WD_NAME="E2E Dead-Man Watchdog v2"
WD_FILE="e2e-deadman-watchdog-v2.groovy"
WD_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/${WD_FILE}"

source "$(dirname "$0")/mcp_watchdog_lib.sh"

# Resolve the watchdog's OWN Apps Code CLASS id.
WD_CLASS="$(resolve_class_id "$WD_NAMESPACE" "$WD_NAME")"
echo "Watchdog own Apps Code class id: ${WD_CLASS}"

PR_WD_CHARS=$(LC_ALL=C.UTF-8 wc -m < "$WD_FILE" | tr -d '[:space:]')

# Deploy this PR's watchdog onto ITSELF and confirm via a fresh lastSelfDeploy. self_class=WD_CLASS arms
# the issue #237 self-reload handling so the reload's null response is treated as success, and the
# lastSelfDeploy confirmation is what proves it actually landed (not just the length).
deploy_app_via_watchdog "$WD_CLASS" "$WD_URL" "watchdog self-update" "$WD_CLASS"

# Cross-check the RIGHT version is live (live source length == this PR's), tolerating a still-settling
# reload. noSave probe (app_total_length) so it can't poison the restore cache.
DEADLINE=$(( $(date +%s) + 120 ))
CONFIRMED=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  CUR="$(app_total_length "$WD_CLASS")"
  if [ "$CUR" = "$PR_WD_CHARS" ]; then CONFIRMED="yes"; echo "Watchdog source length now ${CUR} (matches this PR)."; break; fi
  echo "  ...watchdog length ${CUR:-<reloading>} != ${PR_WD_CHARS}; waiting for the reload to settle..."
  sleep 10
done
if [ -z "$CONFIRMED" ]; then
  echo "::error::Watchdog self-update confirmed by lastSelfDeploy, but the live source length never reached this PR's ${PR_WD_CHARS} within 120s -- a different revision may be live. Investigate."
  exit 1
fi

# Assert the /mcp transport works (a watchdog that compiled but broke its endpoint must fail the run).
TOOLS_N=$(printf '%s' "$(mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' || true)" | jq -r '.result.tools | length' 2>/dev/null || echo 0)
if ! [ "${TOOLS_N:-0}" -gt 0 ] 2>/dev/null; then
  echo "::error::Watchdog self-update compiled but its /mcp transport is not answering tools/list (got ${TOOLS_N:-0} tools). Investigate before relying on it."
  exit 1
fi
echo "WATCHDOG_SELF_UPDATE_OK class=${WD_CLASS} length=${PR_WD_CHARS} tools=${TOOLS_N}"
