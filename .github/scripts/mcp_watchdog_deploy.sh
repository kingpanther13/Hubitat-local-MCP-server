#!/usr/bin/env bash
# Install THIS PR's package on the test hub through watchdog v3 (`watchdog_v3.py deploy-pr`), then
# bounce the server app and check it serves. Libraries are delivered ONLY by the bundle, which is
# built here from the checkout and must equal the published bundle-artifacts entry for this SHA.
#
# Usage: mcp_watchdog_deploy.sh [path/to/hubitat-mcp-server.groovy]
# Env:   MCP_URL               -- the MCP server under test
#        WATCHDOG_URL          -- the watchdog endpoint (secret WATCHDOG_MCP_URL)
#        PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#        PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA
#        RUNNER_TEMP           -- GHA temp dir; falls back to /tmp
set -euo pipefail

: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full watchdog MCP endpoint URL with access_token; from secret WATCHDOG_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required (https://raw.githubusercontent.com/<owner>/<repo>)}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required (40-hex PR head SHA)}"


APP_FILE="${1:-hubitat-mcp-server.groovy}"
if [ ! -f "$APP_FILE" ]; then
  echo "::error::App file not found: $APP_FILE (wrong working directory or bad path arg). Refusing to silently report 'nothing to install'."
  exit 1
fi

# Whether the manifest declares a bundle at all; the guard below needs it.
MANIFEST_FILE="$(dirname "$APP_FILE")/packageManifest.json"
BUNDLE_BASENAMES=""
if [ -f "$MANIFEST_FILE" ]; then
  BUNDLE_BASENAMES=$(jq -r '.bundles[]?.location // empty' "$MANIFEST_FILE" | sed -E 's#.*/##')
fi

# ---------------------------------------------------------------------------
# Shared watchdog JSON-RPC helpers (mcp_call / call_tool / ok_of / err_of), used by the
# throttle bounce below.
source "$(dirname "$0")/mcp_watchdog_lib.sh"

# The bundle is the only library delivery path, so a manifest that #includes libraries without
# declaring a bundle cannot be installed.
mapfile -t INCLUDES < <(
  grep -hoE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' "$APP_FILE" \
    | sed -E 's/^[[:space:]]*#include[[:space:]]+//' | sort -u || true
)
if [ "${#INCLUDES[@]}" -gt 0 ] && [ -z "$BUNDLE_BASENAMES" ]; then
  echo "::error::App #includes ${#INCLUDES[@]} library(ies) (${INCLUDES[*]}) but packageManifest.json declares NO bundle to deliver them. A bundle-only install would leave the #include directives unresolved and the app would not compile. Add the libraries' bundle to the manifest."
  exit 1
fi
echo "App #includes ${#INCLUDES[@]} library(ies): ${INCLUDES[*]:-<none>} -- delivered via the package bundle, the HPM way (no redundant per-library install)."


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
# INSTALL -- build the PR's bundle from the checkout, then hand the whole package to v3.
# ===========================================================================
REPO_DIR="$(dirname "$APP_FILE")"
if [ ! -f "$REPO_DIR/tools/build-bundle.py" ]; then
  echo "::error::tools/build-bundle.py not found in the checkout -- cannot build the PR's bundle zip."
  exit 1
fi
echo "Building the PR's bundle zip from the checkout's libraries/ ..."
( cd "$REPO_DIR" && python3 tools/build-bundle.py )
BUNDLE_PATH="$REPO_DIR/bundles/mcp-libraries.zip"
if [ ! -f "$BUNDLE_PATH" ]; then
  echo "::error::The builder did not produce bundles/mcp-libraries.zip -- the only bundle v3 installs (/bundle-artifacts/shas/<sha>/mcp-libraries.zip)."
  exit 1
fi
python3 "$(dirname "$0")/watchdog_v3.py" deploy-pr --bundle "$BUNDLE_PATH"

# The checks below drive manual watchdog tools, which v3 serves on its own endpoint.
V3_OUT="$(python3 "$(dirname "$0")/watchdog_v3.py" endpoint)"
printf '%s\n' "$V3_OUT" | grep '^::add-mask::' || true
WATCHDOG_URL="$(printf '%s\n' "$V3_OUT" | tail -n1)"
export WATCHDOG_URL
echo "::add-mask::${WATCHDOG_URL##*access_token=}"


# ===========================================================================
# CLEAR THE PER-APP LOAD THROTTLE -- bounce (disable/enable) the server app.
#    Hubitat's platform load limiter ("LimitExceededException: App N generates
#    excessive hub load") silently blocks the app's device-method dispatch once
#    tripped -- device commands false-succeed (the exception is thrown in the
#    DEVICE's context, invisible to the calling app) -- and the block does NOT
#    lift when the load drains. A short disable/enable of the app INSTANCE clears
#    it (verified live 2026-06-11 on fw 2.5.0.143; a hub reboot is NOT required).
#    Back-to-back e2e runs are the normal cadence, so every run clears any block
#    left by prior activity before its tests start. Routed via the WATCHDOG (a
#    different app) so the toggle cannot race the server's own request handling.
#    Failing to RE-ENABLE is a hard stop: tests against a disabled app would all
#    red with a misleading signature (the watchdog stays alive for manual rescue).
# ===========================================================================
SERVER_APP_ID="${HUBITAT_APP_ID:-}"
if [ -z "$SERVER_APP_ID" ]; then
  echo "::warning::HUBITAT_APP_ID not set -- skipping the load-throttle bounce (this run stays exposed to a stale platform throttle from prior activity)."
else
  echo "Bouncing server app instance ${SERVER_APP_ID} (disable/enable via watchdog) to clear any platform load throttle..."
  BOUNCE_OFF=$(jq -nc --arg id "$SERVER_APP_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_app_disabled",arguments:{appId:$id,disable:true,confirm:true}}}')
  BOUNCE_ON=$(jq -nc --arg id "$SERVER_APP_ID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_set_app_disabled",arguments:{appId:$id,disable:false,confirm:true}}}')
  OFF_TEXT=$(call_tool "$BOUNCE_OFF" || true)
  if [ "$(ok_of "$OFF_TEXT")" != "true" ]; then
    # Never confirmed disabled -> nothing to undo; the run proceeds merely unbounced.
    echo "::warning::throttle-bounce disable did not confirm ($(err_of "$OFF_TEXT")) -- skipping the enable leg; this run stays exposed to a stale platform throttle."
  else
    sleep 3
    ENABLED="false"
    for ATTEMPT in 1 2 3 4 5; do
      ON_TEXT=$(call_tool "$BOUNCE_ON" || true)
      if [ "$(ok_of "$ON_TEXT")" = "true" ] && printf '%s' "$ON_TEXT" | grep -q '"disabled":false'; then
        ENABLED="true"
        break
      fi
      echo "re-enable attempt ${ATTEMPT}/5 not confirmed ($(err_of "$ON_TEXT")); retrying in 5s..."
      sleep 5
    done
    if [ "$ENABLED" != "true" ]; then
      echo "::error::Server app ${SERVER_APP_ID} was disabled for the load-throttle bounce and could NOT be verifiably re-enabled after 5 attempts. Re-enable it via the watchdog (hub_set_app_disabled appId=${SERVER_APP_ID} disable=false confirm=true) before re-running. Failing loudly instead of running every test against a disabled app."
      exit 1
    fi
    echo "Load-throttle bounce complete: app ${SERVER_APP_ID} disable->enable verified."

    # Post-enable readiness: prove the server's /mcp endpoint actually ANSWERS before handing off to
    # the tests. The first request after an enable absorbs any lazy-recompile/warmup latency here
    # instead of inside the first test, and a bounce that somehow left the endpoint dead fails THIS
    # step with a precise message rather than 100 tests with a misleading one. The bounce itself
    # cannot race a compile: it runs only after deploy-pr returned, i.e. v3 verified each app's
    # source hash and advanced code version and both endpoints answered.
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
        echo "::error::Server app ${SERVER_APP_ID} is ENABLED but its /mcp endpoint never answered initialize within ~90s after the throttle bounce. Investigate before the tests bury this signal. Last attempt: ${PROBE_DIAG}"
        exit 1
      fi
    else
      echo "::warning::HUBITAT_HUB_URL/HUBITAT_ACCESS_TOKEN not set -- skipping the post-bounce endpoint readiness check (the test runner's own connectivity check still gates)."
    fi
  fi
fi

# Post-deploy BIND-CHECK (fail-fast). V3 verified each #include'd library's source hash on the hub;
# that does NOT prove the app INLINED it. A library can land yet fail to
# bind, leaving its part-methods (_readOnlyToolNames_part<X>, _getAllToolDefinitions_part<X>, ...)
# undefined on the compiled app class -- then getToolDefinitions() throws MissingMethodException from one
# of the catalog aggregators (getReadOnlyToolNames/getAllToolDefinitions/...) and EVERY tool is dead.
# initialize does NOT exercise that path (the readiness check above can't catch it); tools/list does.
# Probe tools/list and DISTINGUISH the two failure classes so we never mislabel one as the other:
#   - a real inline failure is a JSON-RPC error naming a missing _part<X> aggregator method -> fail FAST,
#     named, before the suite runs;
#   - an empty / non-JSON / transient response (relay drop, post-bounce warmup, load throttle) is NOT a
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
    echo "::error::Post-deploy BIND-CHECK FAILED -- a bundled library LANDED but did NOT inline into the app (${BAD_LIB}() is undefined), so its part-methods are uncallable and EVERY tool is dead. Failing the deploy now instead of running the whole suite against a broken app. tools/list error: ${BIND_ERR:-<none>}"
    exit 1
  elif [ "$BIND_STATE" != "ok" ]; then
    echo "::error::Post-deploy BIND-CHECK could not get a usable tools/list after 6 attempts, with NO library-inline-failure signature -- so not a proven bind failure; each attempt's line above records what came back. Last attempt: ${PROBE_DIAG}"
    exit 1
  fi
else
  echo "::warning::HUBITAT_HUB_URL / HUBITAT_ACCESS_TOKEN / SERVER_APP_ID not all set -- skipping the post-deploy bind-check (the test runner's own setup still gates)."
fi

echo "Package installed through watchdog v3: parent, child and their library bundle are live on the hub."
echo "WATCHDOG_DEPLOY_OK libraries=${#INCLUDES[@]}"
