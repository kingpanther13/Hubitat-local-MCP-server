#!/usr/bin/env bash
# Install THIS PR's package on the test hub through watchdog v3 (`watchdog_v3.py deploy-pr`), then
# reset the hub's per-app runtime statistics and check the server serves. The libraries ship
# inlined into the parent: both apps are built here from the checkout and must equal the published
# bundle-artifacts entry for this SHA.
#
# Usage: mcp_watchdog_deploy.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL               -- the MCP server under test
#        WATCHDOG_URL          -- the watchdog endpoint (secret WATCHDOG31_MCP_URL)
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA
#        RUNNER_TEMP           -- GHA temp dir; falls back to /tmp
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full watchdog MCP endpoint URL with access_token; from secret WATCHDOG31_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (https://raw.githubusercontent.com/<owner>/<repo>)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (40-hex PR head SHA)}"


APP_FILE="${1:-hubitat-mcp-server.groovy}"
if [ ! -f "$APP_FILE" ]; then
  echo "::error::App file not found: $APP_FILE (wrong working directory or bad path arg). Refusing to silently report 'nothing to install'."
  exit 1
fi

# ---------------------------------------------------------------------------
# Shared watchdog JSON-RPC helpers (mcp_call / call_tool / ok_of / err_of), used by the
# runtime-statistics reset below.
source "$(dirname "$0")/mcp_watchdog_lib.sh"

# The build inlines every #include; watchdog_v3.py refuses a built app that still has one.
mapfile -t INCLUDES < <(
  grep -hoE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' "$APP_FILE" \
    | sed -E 's/^[[:space:]]*#include[[:space:]]+//' | sort -u || true
)
echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]:-<none>} -- inlined into the built parent."


# ---------------------------------------------------------------------------
# mcp_probe <url> <json-body> <max-time> -- one POST to the server's /mcp endpoint. Sets PROBE_BODY
# (the response body) and PROBE_DIAG, one line saying what actually came back: curl's exit code and
# error, HTTP status, bytes, time, content type, and the body's first 300 chars. A failed readiness or
# bind-check attempt logs it, so the run records what the runner received rather than only that it
# was unusable. Never fails the script (the caller judges the body); substrings instead of `| head`
# so no pipeline can die of SIGPIPE under pipefail.
# ---------------------------------------------------------------------------
mcp_probe() {
  local url="$1" payload="$2" max_time="$3" body_file err_file meta curl_err head rc=0
  body_file=$(mktemp); err_file=$(mktemp)
  meta=$(curl -sS --max-time "$max_time" -X POST "$url" -H "Content-Type: application/json" \
    --data-binary "$payload" -o "$body_file" \
    -w 'http=%{http_code} bytes=%{size_download} time=%{time_total}s type=%{content_type}' 2>"$err_file") || rc=$?
  PROBE_BODY=$(cat "$body_file")
  curl_err=$(tr '\r\n' '  ' < "$err_file")
  head=${PROBE_BODY:0:300}; head=${head//$'\r'/ }; head=${head//$'\n'/ }
  PROBE_DIAG="curl exit ${rc}${curl_err:+ (${curl_err:0:200})}; ${meta:-no response metadata}; body: ${head:-<empty>}"
  rm -f "$body_file" "$err_file"
}


# ===========================================================================
# INSTALL -- build both apps from the checkout, then hand the whole package to v3.
# ===========================================================================
REPO_DIR="$(dirname "$APP_FILE")"
if [ ! -f "$REPO_DIR/tools/build-release-app.py" ]; then
  echo "::error::tools/build-release-app.py not found in the checkout -- cannot build the PR's apps."
  exit 1
fi
echo "Building the PR's apps from the checkout (libraries inlined into the parent) ..."
( cd "$REPO_DIR" && python3 tools/build-release-app.py )
for BUILT in hubitat-mcp-server.groovy hubitat-mcp-rule.groovy; do
  if [ ! -f "$REPO_DIR/dist/$BUILT" ]; then
    echo "::error::The builder did not produce dist/$BUILT -- v3 installs it from /bundle-artifacts/shas/<sha>/$BUILT."
    exit 1
  fi
done
python3 "$(dirname "$0")/watchdog_v3.py" deploy-pr --dist "$REPO_DIR/dist"

# The checks below drive manual watchdog tools, which v3 serves on its own endpoint.
V3_OUT="$(python3 "$(dirname "$0")/watchdog_v3.py" endpoint)"
printf '%s\n' "$V3_OUT" | grep '^::add-mask::' || true
WATCHDOG_URL="$(printf '%s\n' "$V3_OUT" | tail -n1)"
export WATCHDOG_URL
echo "::add-mask::${WATCHDOG_URL##*access_token=}"


# ===========================================================================
# RESET THE PER-APP RUNTIME STATISTICS -- hub_delete_app_runtime_stats through the watchdog.
#    The hub's per-app load limiter refuses an app whose busy time (Logs > App Stats total), counted
#    twice for its own events, passes uptime x threshold (stock 33 %). When Hub Login Security is off
#    the server routes plain hub reads through the hub's LAN address, which the hub does not charge;
#    the 1,400,000 ms warning below is derived from the loopback-era cost of a full run (~700 s busy).
#    Disabling and re-enabling the app does not change that counter; a reboot zeroes it and uptime.
#    The reset zeroes every app's total while apps keep serving and leaves uptime alone.
# ===========================================================================
# reset_runtime_stats <app-id> -- an empty answer or a busy refusal is retried (a busy refusal scheduled
# nothing; a lost answer may have run, harmless because the reset is idempotent); if that persists, or
# the watchdog predates the tool, the run continues with a warning (the suite resets in-run on a limiter
# trip). Returns 1 only on an explicit failure.
reset_runtime_stats() {
  local app_id="$1" rpc text rpc_err err_file busy why attempt=1 summary limit_ms
  rpc=$(jq -nc --arg id "$app_id" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_delete_app_runtime_stats",arguments:{appId:$id,confirm:true}}}')
  while :; do
    # call_tool reports a JSON-RPC error on stderr with empty stdout; capture that line to read it and
    # pass any other stderr (a curl timeout, which is an empty answer) through.
    err_file=$(mktemp)
    text=$(call_tool "$rpc" 2>"$err_file" || true)
    rpc_err=$(grep '^::error::watchdog JSON-RPC error' "$err_file" || true)
    grep -v '^::error::watchdog JSON-RPC error' "$err_file" >&2 || true
    rm -f "$err_file"
    busy=$(printf '%s' "$text" | jq -r '.busy == true' 2>/dev/null || echo false)
    if [ -n "$rpc_err" ] || { [ -n "$text" ] && [ "$busy" != "true" ]; }; then break; fi
    if [ "$attempt" -ge "$WD_RPC_ATTEMPTS" ]; then break; fi
    if [ -n "$text" ]; then why="watchdog busy: $(err_of "$text")"; else why="empty answer"; fi
    echo "  ...reset attempt ${attempt}/${WD_RPC_ATTEMPTS}: ${why}; retrying in ${WD_RPC_RETRY_SLEEP}s..."
    attempt=$((attempt + 1)); sleep "$WD_RPC_RETRY_SLEEP"
  done
  if printf '%s\n%s' "$text" "$rpc_err" | grep -q 'Unknown tool'; then
    echo "::warning::The standing watchdog predates hub_delete_app_runtime_stats -- run watchdog maintenance (hub-e2e.yml watchdog_update=true). Continuing without the reset."
    return 0
  fi
  if [ -n "$rpc_err" ]; then
    echo "::error::Runtime-statistics reset for app ${app_id} failed -- the run would start exposed to the load limiter. ${rpc_err#::error::}"
    return 1
  fi
  if [ -z "$text" ] || [ "$busy" = "true" ]; then
    if [ -n "$text" ]; then why="watchdog busy: $(err_of "$text")"; else why="empty answer"; fi
    echo "::warning::Runtime-statistics reset for app ${app_id} got no usable answer after ${attempt} attempt(s) (${why}) -- continuing; the suite resets the statistics if the limiter trips."
    return 0
  fi
  if [ "$(ok_of "$text")" != "true" ]; then
    echo "::error::Runtime-statistics reset for app ${app_id} failed -- the run would start exposed to the load limiter. $(err_of "$text") Response: ${text:0:300}"
    return 1
  fi
  summary=$(printf '%s' "$text" | jq -r '"before \(.before.totalMs) ms, after \(.after.totalMs) ms, uptime \(.uptimeSeconds) s, threshold \(.thresholdPct) %, " + (if (.limitMs | type) == "number" then "limit \(.limitMs) ms, margin \((.limitMs - 2 * ((.after.totalMs | numbers) // 0)) | floor) ms" else "busy budget unknown (threshold/uptime unreadable: \([.thresholdError, .uptimeError] | map(strings) | join("; ") | if . == "" then "no error reported" else . end))" end)' 2>/dev/null || true)
  limit_ms=$(printf '%s' "$text" | jq -r 'if (.limitMs | type) == "number" then (.limitMs | floor) else "" end' 2>/dev/null || true)
  echo "Runtime statistics reset for app ${app_id}: ${summary:-${text:0:300}}"
  if [ "$(printf '%s' "$text" | jq -r '.verified == false' 2>/dev/null || echo false)" = "true" ]; then
    echo "::warning::Runtime-statistics reset for app ${app_id} was accepted but not verified: $(printf '%s' "$text" | jq -r '.note // "stats read-back unavailable"' 2>/dev/null || true)"
  fi
  if [ -n "$limit_ms" ] && [ "$limit_ms" -lt 1400000 ]; then
    echo "::warning::Busy budget after the reset is ${limit_ms} ms (< 1,400,000 ms, twice a full run's ~700 s) -- hub uptime is too short for a full run; the suite resets the statistics again if the limiter trips."
  fi
  return 0
}

SERVER_APP_ID="${HUBITAT_APP_ID:-}"
if [ -z "$SERVER_APP_ID" ]; then
  echo "::warning::HUBITAT_APP_ID not set -- skipping the runtime-statistics reset (this run starts with the busy time prior activity left on the server app)."
else
  echo "Resetting the hub's per-app runtime statistics through the watchdog (server app ${SERVER_APP_ID})..."
  if ! reset_runtime_stats "$SERVER_APP_ID"; then exit 1; fi

  # Readiness: prove the server's /mcp endpoint actually ANSWERS after the install before handing off
  # to the tests. The first request absorbs any lazy-recompile/warmup latency here instead of inside
  # the first test, and a dead endpoint fails THIS step with a precise message rather than 100 tests
  # with a misleading one. deploy-pr has already returned, i.e. v3 verified each app's source hash and
  # advanced code version and both endpoints answered.
  if [ -n "${HUBITAT_HUB_URL:-}" ] && [ -n "${HUBITAT_ACCESS_TOKEN:-}" ]; then
    MAIN_MCP_URL="${HUBITAT_HUB_URL}/apps/${SERVER_APP_ID}/mcp?access_token=${HUBITAT_ACCESS_TOKEN}"
    READY="false"
    for ATTEMPT in 1 2 3 4 5 6 7 8 9; do
      mcp_probe "$MAIN_MCP_URL" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"e2e-deploy-readiness","version":"1"}}}' 30
      INIT_RESP=$PROBE_BODY
      if printf '%s' "$INIT_RESP" | jq -e '.result.protocolVersion // empty' >/dev/null 2>&1; then
        READY="true"
        echo "Server endpoint answered initialize on readiness attempt ${ATTEMPT} -- app ${SERVER_APP_ID} is serving."
        break
      fi
      echo "  ...readiness attempt ${ATTEMPT}/9: no initialize result (${PROBE_DIAG}); retrying in 10s..."
      sleep 10
    done
    if [ "$READY" != "true" ]; then
      echo "::error::Server app ${SERVER_APP_ID}'s /mcp endpoint never answered initialize within ~90s after the install. Investigate before the tests bury this signal. Last attempt: ${PROBE_DIAG}"
      exit 1
    fi
  else
    echo "::warning::HUBITAT_HUB_URL/HUBITAT_ACCESS_TOKEN not set -- skipping the post-install endpoint readiness check (the test runner's own connectivity check still gates)."
  fi
fi

# Post-deploy BIND-CHECK (fail-fast). V3 verified the built parent's source hash on the hub; that
# does NOT prove every library made it into the build. A library left out of it leaves its
# part-methods (_readOnlyToolNames_part<X>, _getAllToolDefinitions_part<X>, ...)
# undefined on the compiled app class -- then getToolDefinitions() throws MissingMethodException from one
# of the catalog aggregators (getReadOnlyToolNames/getAllToolDefinitions/...) and EVERY tool is dead.
# initialize does NOT exercise that path (the readiness check above can't catch it); tools/list does.
# Probe tools/list and DISTINGUISH the two failure classes so we never mislabel one as the other:
#   - a real inline failure is a JSON-RPC error naming a missing _part<X> aggregator method -> fail FAST,
#     named, before the suite runs;
#   - an empty / non-JSON / transient response (relay drop, post-install warmup, load limiter) is NOT a
#     proven bind failure -> retry, then fail with a cause-neutral message (not "library didn't inline").
if [ -n "${HUBITAT_HUB_URL:-}" ] && [ -n "${HUBITAT_ACCESS_TOKEN:-}" ] && [ -n "${SERVER_APP_ID:-}" ]; then
  BIND_MCP_URL="${HUBITAT_HUB_URL}/apps/${SERVER_APP_ID}/mcp?access_token=${HUBITAT_ACCESS_TOKEN}"
  BIND_STATE="pending"; TL_RESP=""; BAD_LIB=""
  for ATTEMPT in 1 2 3 4 5 6; do
    mcp_probe "$BIND_MCP_URL" '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' 45
    TL_RESP=$PROBE_BODY
    TL_TOOLS=$(printf '%s' "$TL_RESP" | jq -r '.result.tools | length' 2>/dev/null || echo "")
    case "$TL_TOOLS" in ''|*[!0-9]*) TL_TOOLS="" ;; esac
    if [ -n "$TL_TOOLS" ] && [ "$TL_TOOLS" -gt 0 ]; then
      echo "Post-deploy bind-check OK on attempt ${ATTEMPT}: tools/list served ${TL_TOOLS} tools -- all ${#INCLUDES[@]} #include'd library(ies) inlined."
      BIND_STATE="ok"; break
    fi
    # A genuine inline failure names a missing aggregator part-method -- stop and fail fast. `|| true`:
    # grep exits 1 on no match, which under pipefail + errexit would end the script silently here,
    # skipping the retries and every message below.
    BAD_LIB=$(printf '%s' "$TL_RESP" | grep -oiE '_(getAllToolDefinitions|readOnlyToolNames|idempotentWriteToolNames|openWorldToolNames|toolDisplayMeta)_part[A-Za-z0-9_]+' | head -1) || true
    if [ -n "$BAD_LIB" ]; then BIND_STATE="unbound"; break; fi
    # Otherwise not a proven bind failure; log what came back and retry.
    TL_JSON="empty body"
    if [ -n "$TL_RESP" ]; then TL_JSON=$(printf '%s' "$TL_RESP" | jq -e . 2>&1 >/dev/null) || true; TL_JSON=${TL_JSON:-parses}; fi
    echo "  bind-check attempt ${ATTEMPT}/6: no usable catalog and no inline-failure signature -- ${PROBE_DIAG}; json: ${TL_JSON}; retrying in 10s..."
    sleep 10
  done
  if [ "$BIND_STATE" = "unbound" ]; then
    BIND_ERR=$(printf '%s' "$TL_RESP" | jq -r '.error.message? // (.error|strings) // (.error|tojson) // empty' 2>/dev/null || true)
    echo "::error::Post-deploy BIND-CHECK FAILED -- a library is missing from the built app (${BAD_LIB}() is undefined), so its part-methods are uncallable and EVERY tool is dead. Failing the deploy now instead of running the whole suite against a broken app. tools/list error: ${BIND_ERR:-<none>}"
    exit 1
  elif [ "$BIND_STATE" != "ok" ]; then
    echo "::error::Post-deploy BIND-CHECK could not get a usable tools/list after 6 attempts, with NO library-inline-failure signature -- so not a proven bind failure; each attempt's line above records what came back. Last attempt: ${PROBE_DIAG}"
    exit 1
  fi
else
  echo "::warning::HUBITAT_HUB_URL / HUBITAT_ACCESS_TOKEN / SERVER_APP_ID not all set -- skipping the post-deploy bind-check (the test runner's own setup still gates)."
fi

echo "Package installed through watchdog v3: the built parent (libraries inlined) and child are live on the hub."
echo "WATCHDOG_DEPLOY_OK libraries=${#INCLUDES[@]}"
