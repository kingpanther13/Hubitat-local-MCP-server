#!/usr/bin/env bash
# Arm the standing "E2E Dead-Man Watchdog v2" for THIS run (issue #243 install fix + e2e safety net).
#
# v2 is a STANDALONE on-hub app that doubles as a small MCP server: its dead-man timer auto-restores
# the WHOLE MCP package (the MCP Rule Server app + the child MCP Rule app + every library the server
# #includes) if an e2e session bricks the hub and can't disarm. Full HPM repair redeploys EVERY manifest
# app, so the restore manifest must include the child app too. NOTHING IS BACKED UP OR CACHED ON THE
# HUB. This script (1) ensures the v2 watchdog is installed AND has a live runEvery1Minute schedule,
# (2) RECORDS canonical main's install URLs -- the bundle zip + parent app + each child app, as raw
# https URLs at MAIN_SHA -- plus each app's expected char count and each #include'd library's id, into
# the restore manifest, then (3) writes the manifest-shaped armed flag with the ARM_WINDOW_MS deadline
# (35 min). The matching "Disarm dead-man watchdog (final)" always() step flips it to
# {armed:false, intent:"disarm"}; if a run crashes before that, the watchdog fires and restores the
# package on its own -- by downloading and installing main from those canonical URLs at fire time
# (GET /bundle2/uploadZipFromUrl for the bundle, POST /app/ajax/update for each app), so GitHub
# reachability is required when the restore runs.
#
# CRITICAL: every hub I/O here goes through the WATCHDOG's own MCP endpoint ($WATCHDOG_URL, from the
# WATCHDOG_MCP_URL secret), NOT the main server's $MCP_URL. The watchdog is the second driver of the
# same restore plumbing, so arming through it keeps the dead-man path independent of the (possibly
# bricked) main server. Every flag write is READ BACK and asserted -- a cloud 504 can no-op a write
# while returning ambiguously, so we never trust a write we didn't verify; the read-back asserts the
# armed flag AND that the restore-critical manifest (the app class id) round-tripped, since a flag
# with no usable restore target is worse than not arming at all.
#
# Env:  WATCHDOG_URL          -- full cloud OAuth URL for the WATCHDOG's /mcp endpoint (WATCHDOG_MCP_URL secret)
#       PR_RAW_BASE           -- https://raw.githubusercontent.com/<owner>/<repo>
#       PR_HEAD_SHA_RESOLVED  -- 40-hex PR head SHA (the watchdog source lives on the PR head)
#       GITHUB_RUN_ID         -- this run's id (stamped into the flag for traceability)
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint, from secret WATCHDOG_MCP_URL)}"
: "${PR_RAW_BASE:?PR_RAW_BASE env var required -- https://raw.githubusercontent.com/<owner>/<repo>}"
: "${PR_HEAD_SHA_RESOLVED:?PR_HEAD_SHA_RESOLVED env var required -- 40-hex PR head SHA}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

# The MCP server app's Apps Code CLASS id (resolved by namespace+name). The watchdog's restore targets
# this CODE CLASS id, NOT the running instance id the main-server MCP URL carries.
APP_NAMESPACE="mcp"
APP_NAME="MCP Rule Server"

WATCHDOG_NAME="E2E Dead-Man Watchdog v2"
WATCHDOG_SOURCE_URL="${PR_RAW_BASE}/${PR_HEAD_SHA_RESOLVED}/e2e-deadman-watchdog-v2.groovy"
FLAG_FILE="e2e-deadman-v2.json"
ARM_WINDOW_MS=900000    # 15 minutes -- a HEARTBEAT TIMEOUT, not a run-length budget. The deploy
                        # script launches mcp_deadman_heartbeat.sh, which extends flag.deadline to
                        # now+15min every 5 minutes for as long as the runner is alive, so the
                        # dead-man fires only when heartbeats STOP (runner truly died) -- run length
                        # is irrelevant. The initial window only needs to cover arm -> first beat
                        # (deploy launch + one 5-min interval, ~6-8 min observed). History: a fixed
                        # 35-min window sized against a long-gone 30-min job timeout fired MID-SUITE
                        # on every >35-min full lane once the suite grew (2026-07-12), silently
                        # restoring canonical main under still-running tests (exposed by PR #362's
                        # protocol false-red); a fixed window can always be outgrown, a heartbeat
                        # cannot. Crash recovery is now <=15 min instead of the old window size.

# Shared helpers (resolve_main_bundle_artifact_url -- the canonical-main bundle resolver this
# script and the deploy's skip-compare both use). The lib's call wrappers are NOT used here;
# this script keeps its own (below) with their longer arm-specific retry budget.
source "$(dirname "$0")/mcp_watchdog_lib.sh"

# --- helpers copied verbatim from mcp_deploy_source.sh (the proven cloud-gateway wrappers) -------
# Every call here targets $WATCHDOG_URL (the watchdog's own MCP endpoint), not the main $MCP_URL.

mcp_call() {
  curl -sS --max-time 120 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

# Prints the inner tool-result text on stdout and returns 0 on a successful tool envelope (text may be
# empty -> the caller's .success / existence gate decides). A JSON-RPC ERROR envelope ({"error":...},
# no .result) is a real hub/protocol failure: surface it loudly and return 1 (do NOT pass it off as
# empty-success text). A non-JSON body (the ~10s cloud-gateway timeout) is retried up to RPC_ATTEMPTS.
# 12x10s (~2+ min of retry window): the arm is PRE-test, off the suite's critical path, and a
# post-teardown async-recompile blackout can swallow 60-130s mid-arm (observed live) -- a healthy
# hub answers on attempt 1, so the extra patience costs nothing when things are fine.
RPC_ATTEMPTS=12
RPC_RETRY_SLEEP=10
mcp_tool_call_text() {
  local label="$1" rpc="$2" attempt=1 resp text err
  while [ "$attempt" -le "$RPC_ATTEMPTS" ]; do
    resp=$(mcp_call "$rpc" || true)
    if printf '%s' "$resp" | jq -e . >/dev/null 2>&1; then
      err=$(printf '%s' "$resp" | jq -r '.error.message // (.error | strings) // empty' 2>/dev/null || true)
      if [ -n "$err" ] && [ "$err" != "null" ]; then
        echo "::error::${label}: JSON-RPC error envelope from the watchdog: $(printf '%s' "$err" | head -c 200)" >&2
        return 1
      fi
      text=$(printf '%s' "$resp" | jq -r '.result.content[0].text // ""' 2>/dev/null || true)
      printf '%s' "$text"
      return 0
    fi
    echo "::warning::${label}: non-JSON gateway response (attempt ${attempt}/${RPC_ATTEMPTS}; likely the ~10s cloud-gateway timeout). Body head: $(printf '%s' "$resp" | head -c 120 | tr '\n' ' ')" >&2
    attempt=$((attempt + 1))
    [ "$attempt" -le "$RPC_ATTEMPTS" ] && sleep "$RPC_RETRY_SLEEP"
  done
  return 1
}

# --- 0) Hub-readiness gate ------------------------------------------------------------------------
# Back-to-back runs can land the arm inside a post-teardown blackout: the previous run's bundle
# restore queues ASYNC dependent-recompile work that can briefly stop the hub answering anything
# (observed live: ~70+s of 'No response from hub' starting minutes after the restore marker matched,
# swallowing the arm's 5x6s retry budget). One cheap poll on a healthy hub; ride out a blackout
# otherwise. HALT only if the hub stays dark for ~5 minutes -- that is a real outage, not settling.
echo "Hub-readiness gate: polling the watchdog before the arm's hub work..."
READY=""
for attempt in $(seq 1 30); do
  if mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_info","arguments":{}}}' 2>/dev/null \
      | jq -e '.result.content[0].text // empty' >/dev/null 2>&1; then
    READY="yes"
    echo "  Watchdog responsive (attempt ${attempt})."
    break
  fi
  echo "  ...hub not answering (attempt ${attempt}/30); waiting 10s (post-teardown recompile settling)"
  sleep 10
done
if [ -z "$READY" ]; then
  echo "::error::e2e HALT: the watchdog endpoint stayed unresponsive for ~5 minutes -- a real outage, not post-teardown settling. Investigate."
  exit 1
fi

# --- 1) Resolve the MCP server Apps Code class id (namespace+name match) -------------------------
echo "Looking up Apps Code class ID for $APP_NAMESPACE:$APP_NAME via watchdog hub_list_apps(scope=types)..."
LIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_apps","arguments":{"scope":"types"}}}'
if ! LIST_TEXT=$(mcp_tool_call_text "hub_list_apps (arm class-ID lookup)" "$LIST_RPC"); then
  echo "::error::hub_list_apps: the Hubitat cloud gateway returned a non-JSON response $RPC_ATTEMPTS times -- a transient relay/timeout, NOT a real failure. Re-run the job."
  exit 1
fi
if [ -z "$LIST_TEXT" ]; then
  echo "::error::hub_list_apps returned a valid but empty MCP response -- cannot resolve the Apps Code class id."
  exit 1
fi
CLASS_ID=$(echo "$LIST_TEXT" | jq -r \
  --arg ns "$APP_NAMESPACE" \
  --arg name "$APP_NAME" \
  '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
if [ -z "$CLASS_ID" ] || [ "$CLASS_ID" = "null" ]; then
  echo "::error::Apps Code class for $APP_NAMESPACE:$APP_NAME not found via hub_list_apps."
  echo "DEBUG hub_list_apps envelope keys: $(echo "$LIST_TEXT" | jq -r 'keys | join(",")')"
  exit 1
fi
echo "Resolved MCP server class ID: $CLASS_ID"


# --- 2) Assert the (manually-installed) watchdog's checkDeadman schedule is live ---------------------
# The watchdog is a one-time MANUAL install by @level99, and the preceding "Watchdog health check" step
# already proved it is installed + serving (tools/list + hub_get_info answered). So we do NOT re-discover
# the instance here -- the /hub2/appsList shape varies by firmware (a plain hub_list_apps(scope=instances)
# jq lookup was brittle). We only assert the runEvery1Minute checkDeadman job survived, so the dead-man
# (and the clean-disarm restore it drives) will actually fire.
watchdog_schedule_alive() {
  local jobs_text
  jobs_text=$(mcp_tool_call_text "hub_get_jobs (watchdog schedule check)" \
    '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_get_jobs","arguments":{}}}') || return 1
  printf '%s' "$jobs_text" | jq -e \
    '[(.scheduledJobs.jobs // .scheduledJobs // [])[]? | (.method // .name // "")] | any(. == "checkDeadman")' \
    >/dev/null 2>&1
}

echo "Asserting the watchdog's checkDeadman schedule is live (hub_get_jobs)..."
if watchdog_schedule_alive; then
  echo "WATCHDOG_SCHEDULE_ALIVE checkDeadman=scheduled"
else
  # WARN, not HALT: hub_get_jobs reads the verified /logs/json endpoint, but the disarm-restore poll
  # later is the REAL proof the timer fires. The health check already proved the watchdog is serving,
  # and it was freshly installed (initialize() ran its runEvery1Minute), so a transient empty/slow jobs
  # read shouldn't block the arm -- proceed rather than block on a possibly-false-negative schedule read.
  echo "::warning::Could not confirm a live 'checkDeadman' scheduled job via hub_get_jobs (read from /logs/json). Proceeding to arm -- the disarm-restore poll will surface a genuinely-dead timer. If the dead-man never fires, re-check the watchdog's runEvery1Minute schedule."
fi

# --- 2c) Record canonical-main install URLs + identities into the restore manifest (no hub install) ---
# The restore must reinstall canonical main, NOT whatever happens to be live (a prior run could have left
# PR/broken code, or main may have advanced on GitHub). The arm DOES NOT deploy or refresh the hub here:
# it records the canonical-main install URLs the teardown restore will install from -- main's BUNDLE
# zip(s) and every manifest app (parent + child), as raw https URLs at MAIN_SHA -- plus each app's
# expected char count and each #include'd library's id. MAIN_* absent (older workflow) -> HALT (nothing
# to record means the dead-man would have no restore source).
#
# SCOPE: full-package canonical main (PR #247). The recorded URLs cover main's BUNDLE (the #include'd
# libraries are delivered by the bundle install at restore time -- nothing is installed per-library) and
# EVERY manifest app, so the restore can reinstall the whole canonical-main package, never a stale child.
# main's BUNDLE identity (namespace+name as the hub registers them) is recorded for the restore-time
# orphan cleanup. Derived from each main bundle .zip's own install.txt (line 1 = namespace, line 2 =
# name) -- the authoritative hub-registered identity -- NOT from listing the hub (a prior run's leftover
# bundle still on the hub would otherwise be misrecorded as "main's" and never cleaned up). "[]" when
# MAIN_* is absent or the manifest/zip can't be read -> the restore cleanup skips (safe).
MAIN_BUNDLES_JSON="[]"
if [ -n "${MAIN_CHARS:-}" ] && [ -n "${MAIN_SOURCE_URL:-}" ] && [ -n "${MAIN_SHA:-}" ]; then
  # THE ARM NEVER INSTALLS OR CACHES ANYTHING (maintainer's final flow): no local backup exists on
  # the hub at all (Hubitat's own DB backup is a separate concern). The arm only RECORDS canonical
  # main's install URLs (bundle zip + parent + child raw https URLs at MAIN_SHA) in the flag
  # manifest; the teardown restore -- clean disarm OR dead-man fire -- downloads and installs main
  # at the END through the verified HPM repair endpoints (GET /bundle2/uploadZipFromUrl, then POST
  # /app/ajax/update -- confirmed against HPM's installBundle/upgradeApp source). If a restore
  # install fails (e.g. GitHub unreachable at fire time), the watchdog endpoint itself stays
  # reachable for manual recovery -- the design's safety floor.

  # (2c-i) Resolve main's BUNDLE URL(s) + identity from main's packageManifest.json at MAIN_SHA.
  MAIN_RAW_PREFIX="${MAIN_SOURCE_URL%/*}"
  MAIN_PKG_MANIFEST=$(curl -fsSL "${MAIN_RAW_PREFIX}/packageManifest.json" 2>/dev/null || true)
  if [ -n "$MAIN_PKG_MANIFEST" ]; then
    MAIN_BUNDLE_LOCS=$(printf '%s' "$MAIN_PKG_MANIFEST" | jq -r '.bundles[]?.location // empty' 2>/dev/null)
    if [ -n "$MAIN_BUNDLE_LOCS" ]; then
      while IFS= read -r BLOC; do
        [ -z "$BLOC" ] && continue
        if printf '%s' "$BLOC" | grep -q "/bundle-artifacts/"; then
          # Unified delivery: the manifest points at the bundle-artifacts branch. Pin the
          # restore to the SHA-keyed entry for MAIN_SHA (immutable -- a mid-run merge to main
          # cannot shift the restore bytes), falling back to branches/main for SHAs that
          # predate publish-on-every-push. Same resolver the deploy's skip-compare uses.
          if ! BURL=$(resolve_main_bundle_artifact_url "$(basename "$BLOC")"); then
            echo "::error::e2e HALT: no bundle-artifacts entry resolves for canonical main (tried shas/${MAIN_SHA} and branches/main for $(basename "$BLOC")) -- the restore would have no bundle source. Check the publish-bundle-artifact workflow; re-run."
            exit 1
          fi
        else
          # Legacy in-tree location (pre-unification manifest at this MAIN_SHA): re-anchor the
          # repo-relative path to MAIN_SHA -- the committed zip exists at that SHA forever.
          BREL=$(printf '%s' "$BLOC" | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
          BURL="${MAIN_RAW_PREFIX}/${BREL}"
        fi
        echo "Resolving main's bundle identity (canonical-main libraries): ${BURL} ..."
        # Identity first (CI-side download; no hub impact): the zip's own install.txt (line 1 =
        # namespace, line 2 = name) is exactly what the hub registers in /hub2/userBundles -- the
        # restore KEEPS this identity and deletes everything else. Reading the hub list instead would
        # misclassify a leftover as main's and never clean it.
        ZIP_TMP="$(mktemp)"
        if ! curl -fsSL "$BURL" -o "$ZIP_TMP" 2>/dev/null; then
          echo "::error::e2e HALT: could not download ${BURL} to read main's bundle identity -- the restore keep-set would be incomplete. Re-run."
          exit 1
        fi
        B_INSTALL_TXT="$(unzip -p "$ZIP_TMP" install.txt 2>/dev/null || true)"
        B_NS="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '1p' | tr -d '[:space:]')"
        B_NM="$(printf '%s\n' "$B_INSTALL_TXT" | sed -n '2p' | tr -d '[:space:]')"
        rm -f "$ZIP_TMP"
        if [ -z "$B_NS" ] || [ -z "$B_NM" ]; then
          echo "::error::e2e HALT: could not parse install.txt namespace/name from ${BURL} -- the restore keep-set would be incomplete. Re-run."
          exit 1
        fi
        # Record the bundle identity + its CANONICAL https URL at MAIN_SHA. No zip caching, no
        # arm-time install: the restore installs straight from this URL -- the exact HPM repair
        # path. (A hub-local /local/ URL was tried and is BANNED: registering the hub's own
        # loopback URL as a bundle source is off the platform's tested path and coincided with
        # /hub2/userLibraries wedging hub-wide.)
        MAIN_BUNDLES_JSON="$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -c --arg ns "$B_NS" --arg nm "$B_NM" --arg url "$BURL" '. + [{namespace:$ns, name:$nm, url:$url}]')"
        echo "Recorded main bundle ${B_NS}/${B_NM} -> restore installs from ${BURL}"
      done <<< "$MAIN_BUNDLE_LOCS"
    else
      echo "::notice::main packageManifest.json declares no bundles -- app-only canonical main (no bundle to record)."
    fi
  else
    # The restore manifest's canonical-main bundle/library set depends on this fetch; arming app-only here
    # would record a possibly-stale library baseline. main's packageManifest.json was just reachable (the
    # workflow resolved MAIN_SOURCE_URL from the same repo), so a failure is a transient blip -- HALT and
    # re-run rather than arm a polluted baseline.
    echo "::error::e2e HALT: could not fetch main's packageManifest.json from ${MAIN_RAW_PREFIX} -- cannot establish the canonical-main bundle/library baseline. Refusing to arm a possibly-polluted baseline; re-run."
    exit 1
  fi

  # (2c-ii) Record the CHILD app URL(s) + expected char counts. The parent's URL/chars come from
  # the workflow env (MAIN_SOURCE_URL/MAIN_CHARS); each child's URL is re-anchored from main's
  # manifest and its char count measured by a CI-side fetch (the restore's landing assert).
  MAIN_CHILD_APPS_JSON="[]"
  MAIN_CHILD_RECS=$(printf '%s' "$MAIN_PKG_MANIFEST" | jq -r     '.apps[]? | select(.namespace == "mcp" and .name != "MCP Rule Server") | "\(.namespace)	\(.name)	\(.location)"' 2>/dev/null || true)
  if [ -z "$MAIN_CHILD_RECS" ]; then
    echo "::error::e2e HALT: main's packageManifest.json declared no non-server mcp app, but the package ships a child app (mcp:MCP Rule). Filter/manifest drift -- refusing to arm a partial restore manifest."
    exit 1
  fi
  while IFS=$'	' read -r C_NS C_NAME C_LOC; do
    [ -z "$C_NS" ] && continue
    if [ -z "$C_LOC" ] || [ "$C_LOC" = "null" ]; then
      echo "::error::e2e HALT: main manifest app ${C_NS}:${C_NAME} has no usable location -- cannot record its restore URL. Refusing to arm."
      exit 1
    fi
    C_REL=$(printf '%s' "$C_LOC" | sed -E 's#^https?://raw\.githubusercontent\.com/[^/]+/[^/]+/[^/]+/##')
    C_URL="${MAIN_RAW_PREFIX}/${C_REL}"
    C_ID=$(printf '%s' "$LIST_TEXT" | jq -r --arg ns "$C_NS" --arg name "$C_NAME"       '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
    if [ -z "$C_ID" ] || [ "$C_ID" = "null" ]; then
      echo "::error::e2e HALT: no Apps Code class for child ${C_NS}:${C_NAME} via hub_list_apps -- cannot record its restore target. Refusing to arm."
      exit 1
    fi
    C_CHARS=$(curl -fsSL "$C_URL" 2>/dev/null | LC_ALL=C.UTF-8 wc -m | tr -d '[:space:]')
    if ! printf '%s' "$C_CHARS" | grep -qE '^[0-9]+$' || [ "$C_CHARS" -lt 1000 ]; then
      echo "::error::e2e HALT: could not measure child ${C_NS}:${C_NAME} source at ${C_URL} (chars=${C_CHARS:-<none>}). Refusing to arm."
      exit 1
    fi
    MAIN_CHILD_APPS_JSON=$(jq -nc --argjson acc "$MAIN_CHILD_APPS_JSON"       --arg id "$C_ID" --arg url "$C_URL" --arg chars "$C_CHARS"       '$acc + [{classId:$id, url:$url, mainChars:$chars}]')
    echo "Recorded child ${C_NS}:${C_NAME} (class ${C_ID}) -> restore installs from ${C_URL} (${C_CHARS} chars)"
  done <<< "$MAIN_CHILD_RECS"
else
  echo "::error::e2e HALT: MAIN_SHA/MAIN_SOURCE_URL/MAIN_CHARS not provided -- the cache is GitHub-direct now, so without them there is nothing to cache and the dead-man would have no restore source. (The workflow's compute step always provides them; their absence is a workflow bug.)"
  exit 1
fi

# main's BUNDLE set for the restore cleanup was derived above from each bundle zip's own install.txt
# (the hub-registered namespace+name), so it is immune to a prior run's leftover bundle still on the hub.
echo "Main bundle set for restore cleanup (from main's bundle manifests): ${MAIN_BUNDLES_JSON}"

# --- 3) Assemble the restore manifest ----------------------------------------------------------------
# Nothing is cached anywhere: this section resolves ids and records the canonical URLs the restore
# installs from at teardown.

# 3a) Parse main's #include directives from a CI-side head fetch of the canonical source (the
# directives live at the top of the file; a ranged GET keeps it cheap). These are MAIN's includes --
# the restore keep-set -- so they must come from MAIN_SOURCE_URL, never the PR checkout.
APP_SRC_CHUNK=$(curl -fsSL -r 0-65000 "$MAIN_SOURCE_URL" 2>/dev/null || true)
if [ -z "$APP_SRC_CHUNK" ]; then
  echo "::error::e2e HALT: could not fetch main's source head from ${MAIN_SOURCE_URL} to parse its #include set. Re-run."
  exit 1
fi


# 3c) Parse the app's #include directives (namespace.Name) so we record exactly the libraries main
# depends on (their ids, for the restore-time reconcile -- nothing is backed up; the bundle delivers
# them at restore time). #include directives live at the TOP of the file (before/around the definition
# block), so the ~64000-char head fetched in 3a (APP_SRC_CHUNK) carries all of them -- no full-file read.
echo "Parsing #include directives from the app source head..."
APP_FULL_SRC="$APP_SRC_CHUNK"
# tokens like:  #include mcp.McpSomeLib   -> "mcp.McpSomeLib"
mapfile -t INCLUDE_TOKENS < <(printf '%s\n' "$APP_FULL_SRC" \
  | grep -oE '^[[:space:]]*#include[[:space:]]+[A-Za-z0-9_]+\.[A-Za-z0-9_]+' \
  | sed -E 's/^[[:space:]]*#include[[:space:]]+//' \
  | sort -u)
echo "Found ${#INCLUDE_TOKENS[@]} #include directive(s): ${INCLUDE_TOKENS[*]:-<none>}"

# Resolve namespace.Name -> library id via watchdog hub_list_libraries.
LIBS_TEXT=""
if [ "${#INCLUDE_TOKENS[@]}" -gt 0 ]; then
  LIBLIST_RPC='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}'
  if ! LIBLIST_TEXT=$(mcp_tool_call_text "hub_list_libraries (resolve include ids)" "$LIBLIST_RPC"); then
    echo "::error::hub_list_libraries returned non-JSON $RPC_ATTEMPTS times -- could not resolve the app's #include libraries to back up. Re-run the job."
    exit 1
  fi
fi

# 3d) Record each #include'd library (id + namespace + name) for the manifest: the keep-set for
# the stale-library reconcile (id-aware, so same-name duplicate rows self-clean). Libraries are
# DELIVERED by the bundle install at restore time -- nothing is cached.
if [ "$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -r 'length')" = "0" ] && [ "${#INCLUDE_TOKENS[@]}" -gt 0 ]; then
  echo "::error::e2e HALT: main #includes ${#INCLUDE_TOKENS[@]} library(ies) but declares NO bundle to deliver them at restore -- packaging error."
  exit 1
fi
# Two-pass with a ONE-SHOT heal: a crashed prior run's restore can leave the hub's
# installed-library set behind main's #includes (seen live: a stale-bundle restore's
# member-sync removed libraries, after which EVERY subsequent run halted here and the
# deploy -- the only thing that installs libraries -- never got to run; a deadlock no
# amount of re-running fixes). When includes are missing, install canonical main's own
# bundle (the exact restore URL recorded above -- the HPM install path) once, then
# re-check. Still missing after the heal -> genuine packaging/hub problem -> HALT.
resolve_lib_manifest() {
  local liblist="$1" token ns nm lib_id
  MISSING_TOKENS=()
  LIB_MANIFEST_JSON="[]"
  for token in "${INCLUDE_TOKENS[@]:-}"; do
    [ -z "$token" ] && continue
    ns="${token%%.*}"
    nm="${token#*.}"
    lib_id=$(echo "$liblist" | jq -r --arg ns "$ns" --arg nm "$nm" '.libraries[]? | select((.namespace == $ns) and (.name == $nm)) | .id' | head -n1)
    if [ -z "$lib_id" ] || [ "$lib_id" = "null" ]; then
      MISSING_TOKENS+=("$token")
      continue
    fi
    LIB_MANIFEST_JSON=$(jq -nc --argjson acc "$LIB_MANIFEST_JSON" --arg id "$lib_id" --arg ns "$ns" --arg name "$nm" '$acc + [{id:$id, namespace:$ns, name:$name}]')
  done
}
resolve_lib_manifest "$LIBLIST_TEXT"
if [ "${#MISSING_TOKENS[@]}" -gt 0 ]; then
  HEAL_URL=$(printf '%s' "$MAIN_BUNDLES_JSON" | jq -r '.[0].url // empty')
  if [ -z "$HEAL_URL" ]; then
    echo "::error::e2e HALT: ${#MISSING_TOKENS[@]} #include'd library(ies) missing from the hub (${MISSING_TOKENS[*]}) and main declares no bundle to heal from. Refusing to arm."
    exit 1
  fi
  echo "::warning::${#MISSING_TOKENS[@]} of main's #include'd libraries are missing from the hub (${MISSING_TOKENS[*]}) -- a prior run's restore likely left the hub behind main. Healing once by installing canonical main's bundle: ${HEAL_URL}"
  HEAL_RPC=$(jq -nc --arg url "$HEAL_URL" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_install_bundle",arguments:{importUrl:$url,confirm:true}}}')
  if ! HEAL_TEXT=$(mcp_tool_call_text "hub_install_bundle (arm baseline heal)" "$HEAL_RPC") \
     || [ "$(printf '%s' "$HEAL_TEXT" | jq -r '.success // empty' 2>/dev/null)" != "true" ]; then
    echo "::error::e2e HALT: the baseline-heal bundle install did not report success (verbatim: $(printf '%s' "$HEAL_TEXT" | head -c 300 | tr '\n' ' ')). Refusing to arm."
    exit 1
  fi
  if ! LIBLIST_TEXT=$(mcp_tool_call_text "hub_list_libraries (post-heal re-check)" "$LIBLIST_RPC"); then
    echo "::error::e2e HALT: hub_list_libraries unreadable after the baseline heal. Re-run the job."
    exit 1
  fi
  resolve_lib_manifest "$LIBLIST_TEXT"
  if [ "${#MISSING_TOKENS[@]}" -gt 0 ]; then
    echo "::error::e2e HALT: ${#MISSING_TOKENS[@]} library(ies) STILL missing after installing canonical main's bundle (${MISSING_TOKENS[*]}) -- the bundle does not deliver what main #includes (packaging error), or the hub is wedged. Refusing to arm."
    exit 1
  fi
  echo "Baseline healed: all ${#INCLUDE_TOKENS[@]} #include'd libraries now resolve."
fi
echo "All ${#INCLUDE_TOKENS[@]} #include'd libraries recorded (delivered by the bundle at restore)."

# 3e) Resolve the CHILD app class id (mcp:MCP Rule). Full HPM repair redeploys EVERY manifest app,
# so the restore must install the child too; its URL + expected chars were recorded in 2c-ii.
CHILD_NAMESPACE="mcp"
CHILD_NAME="MCP Rule"
CHILD_CLASS_ID=$(echo "$LIST_TEXT" | jq -r --arg ns "$CHILD_NAMESPACE" --arg name "$CHILD_NAME"   '.apps[]? | select(.namespace == $ns and .name == $name) | .id' | head -n1)
if [ -z "$CHILD_CLASS_ID" ] || [ "$CHILD_CLASS_ID" = "null" ]; then
  echo "::error::e2e HALT: child Apps Code class for $CHILD_NAMESPACE:$CHILD_NAME not found via hub_list_apps -- full repair redeploys it, so its restore target must resolve. Refusing to arm."
  exit 1
fi

# Assemble the restore manifest: {apps:[{classId,url,mainChars},...],
# libraries:[{id,namespace,name}], bundles:[{namespace,name,url}]} -- the exact shape
# restorePackage() reads: drop the PR's stale bundle, install main's bundle from its url (verified
# HPM endpoint GET /bundle2/uploadZipFromUrl), install EVERY app from its url (verified HPM endpoint
# POST /app/ajax/update, fetched hub-side), drop the PR's stale/duplicate libraries (id-aware
# keep-set). The singular `app` is kept for the flag-readback assertion and back-compat.
MANIFEST_JSON=$(jq -nc   --arg classId "$CLASS_ID"   --arg appUrl "$MAIN_SOURCE_URL"   --arg mainChars "$MAIN_CHARS"   --argjson childApps "${MAIN_CHILD_APPS_JSON:-[]}"   --argjson libs "$LIB_MANIFEST_JSON"   --argjson bundles "${MAIN_BUNDLES_JSON:-[]}"   '{app:{classId:$classId, url:$appUrl, mainChars:$mainChars},
    apps:([{classId:$classId, url:$appUrl, mainChars:$mainChars}] + $childApps),
    libraries:$libs, bundles:$bundles}')
echo "Assembled restore manifest: $MANIFEST_JSON"

# --- 4) Write the manifest-shaped armed flag, then READ IT BACK and assert ------------------------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; this stays portable (the second-
# granularity is irrelevant for a 35-minute deadline). armPrSha records the PR head SHA being tested
# this run -- a forensic breadcrumb on a fire. canonicalMainSha records the MAIN_SHA whose URLs
# the manifest carries -- the revision the restore will install.
DEADLINE_MS=$(( $(date +%s) * 1000 + ARM_WINDOW_MS ))
FLAG_JSON=$(jq -nc \
  --argjson deadline "$DEADLINE_MS" \
  --arg runId "$GITHUB_RUN_ID" \
  --arg armPrSha "$PR_HEAD_SHA_RESOLVED" \
  --arg canonicalMainSha "${MAIN_SHA:-}" \
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:true, deadline:$deadline, runId:$runId, intent:"arm", armPrSha:$armPrSha, canonicalMainSha:$canonicalMainSha, manifest:$manifest}')

echo "Writing armed flag to '$FLAG_FILE' (deadline=$DEADLINE_MS, ~35min)..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (arm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm write. Re-run the job."
  exit 1
fi

# Run-scope the deferred-native-rule list (PR #251): reset it to [] at arm so a PRIOR run's stale instance
# ids can never be reaped by THIS run's disarm sweep. The test step overwrites it with THIS run's ids only
# when E2E_DEFER_NATIVE_DELETES is set; absent that, an empty list is the correct no-op. Best-effort -- a
# failure must not block the arm (the disarm sweep deletes only exact ids + the --cleanup-only prefix sweep
# backstops, so a stale list is harmless beyond a no-op re-delete).
DEFERRED_RULES_FILE="e2e-deferred-native-rules.json"
mcp_tool_call_text "hub_write_file (reset deferred-rule list)" \
  "$(jq -nc --arg fn "$DEFERRED_RULES_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:"[]",confirm:true}}}')" >/dev/null 2>&1 \
  || echo "::warning::Could not reset ${DEFERRED_RULES_FILE} at arm (non-fatal -- exact-id sweep + prefix backstop guard against stale ids)."

# Read-back-and-assert: a cloud 504 can no-op a write while returning ambiguously. We assert
# armed==true AND the manifest's app.classId round-tripped (the restore target landed intact).
echo "Reading the flag back to confirm it armed..."
READBACK_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! READBACK_TEXT=$(mcp_tool_call_text "hub_read_file (arm read-back)" "$READBACK_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') read-back returned non-JSON $RPC_ATTEMPTS times -- could not confirm the arm landed. Re-run the job."
  exit 1
fi
RB_ARMED=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .armed' 2>/dev/null || echo "")
RB_CLASS=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .manifest.app.classId' 2>/dev/null || echo "")
if [ "$RB_ARMED" != "true" ] || [ "$RB_CLASS" != "$CLASS_ID" ]; then
  echo "::error::Arm flag did not land as expected (read back armed=$RB_ARMED manifest.app.classId=$RB_CLASS, expected armed=true classId=$CLASS_ID). The write may have silently no-opped. Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

echo "WATCHDOG_ARMED classId=$CLASS_ID deadline=$DEADLINE_MS runId=$GITHUB_RUN_ID restoreUrl=$MAIN_SOURCE_URL libCount=$(echo "$LIB_MANIFEST_JSON" | jq 'length')"
