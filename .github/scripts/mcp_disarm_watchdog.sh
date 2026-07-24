#!/usr/bin/env bash
# Disarm the E2E Dead-Man Watchdog v2 at the end of a run (the dead-man's "I'm alive" signal) and
# FIRE-AND-FORGET its clean-finish restore of main.
#
# v2's disarm is not just "stop the timer": the watchdog treats intent=disarm as a request to restore
# the whole MCP package from the canonical main install URLs in the flag manifest ONCE per runId (it
# stamps restoreFor=runId so it never repeats). This step writes {armed:false, intent:"disarm", runId,
# ...manifest...} for the watchdog to install from. The manifest is NORMALLY re-targeted at CURRENT main
# first (section 1.5 re-resolves main's SHA and rewrites the app/bundle URLs + app chars + canonicalMainSha,
# because main moves while runs are in flight); only on a re-target obstacle does it fall back to the
# arm-time manifest + canonicalMainSha unchanged. Either way the flag carries a self-consistent
# manifest + canonicalMainSha so the watchdog knows what to install and how to stamp the canonical-main
# marker. The step then reads the flag back to assert
# armed=false + intent=disarm landed AND that the restore-critical manifest survived the write, reaps
# deferred native-rule fixtures, and EXITS. CI does NOT wait for the restore: the watchdog runs it
# asynchronously (its adminWriteFile kick fires checkDeadman ~2s after the flag write) -- it installs
# main's bundle + every app from the canonical https URLs in the manifest and stamps the canonical-main
# SHA marker itself on success. Restore-to-main fidelity is deliberately not a CI gate -- the next run's
# PR install is a full HPM-style repair that unconditionally overwrites the parent + child apps on the
# hub. If a restore install fails the watchdog endpoint itself stays reachable as the recovery path.
# What MUST be deleted (BAT fixtures) is handled by the deferred sweep below plus the separate
# fail-closed --cleanup-only step.
#
# CRITICAL: every hub I/O here goes through the WATCHDOG's own MCP endpoint ($WATCHDOG_URL, from the
# WATCHDOG_MCP_URL secret), NOT the main server's $MCP_URL -- the watchdog is the driver of the restore
# plumbing and may be the only live server if the run bricked main. A silently-failed disarm is the
# dangerous case (the watchdog would otherwise fire mid-next-run), so the disarm WRITE is read back and
# asserted -- this step succeeds only when armed==false landed.
#
# Env:  WATCHDOG_URL   -- full cloud OAuth URL for the WATCHDOG's /mcp endpoint (WATCHDOG_MCP_URL secret)
#       GITHUB_RUN_ID  -- this run's id (stamped into the flag; the watchdog echoes it as restoreFor)
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint, from secret WATCHDOG_MCP_URL)}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

FLAG_FILE="e2e-deadman-v2.json"

# Stop the dead-man heartbeat BEFORE touching the flag: a beat mid-disarm would read the
# still-armed flag and write it back armed after this step disarms it (resurrecting the
# deadline). Kill + WAIT first (the wait reaps an in-flight beat's process so its write
# cannot land after this point); a straggler that already dispatched is additionally
# blunted by the beat's own armed==true guard on its NEXT read.
HB_PID_FILE="${RUNNER_TEMP:-/tmp}/deadman-heartbeat.pid"
HB_LOG="${RUNNER_TEMP:-/tmp}/deadman-heartbeat.log"
if [ -f "$HB_PID_FILE" ]; then
  HB_PID=$(cat "$HB_PID_FILE" 2>/dev/null || echo "")
  if [ -n "$HB_PID" ] && kill "$HB_PID" 2>/dev/null; then
    wait "$HB_PID" 2>/dev/null || true
    echo "Stopped dead-man heartbeat (pid $HB_PID)."
  else
    echo "Dead-man heartbeat pid '${HB_PID:-?}' already gone."
  fi
  rm -f "$HB_PID_FILE"
else
  echo "No heartbeat PID file found -- run predates the heartbeat launcher or the deploy step was skipped."
fi
# Surface the beat history: a >30-min gap between "extended" lines is evidence of a
# deadline lapse (and possible mid-run fire) that a flag rewrite cannot erase.
if [ -f "$HB_LOG" ]; then
  echo "=== dead-man heartbeat log (tail) ==="
  tail -n 12 "$HB_LOG" || true
fi

# --- cloud-gateway wrappers (target $WATCHDOG_URL, the watchdog's own MCP endpoint, not $MCP_URL) ----

mcp_call() {
  curl -sS --max-time 120 -X POST "$WATCHDOG_URL" \
    -H "Content-Type: application/json" \
    --data-binary "$1"
}

RPC_ATTEMPTS=5
RPC_RETRY_SLEEP=6
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

# --- 1) Read the current (armed) flag to reuse its manifest -----------------------------------------
# The watchdog's disarm-path restore reads flag.manifest, so the disarm flag MUST carry the same
# manifest the arm wrote. Rather than re-deriving it, we read the armed flag and reuse its manifest +
# armPrSha, flipping armed:false / intent:"disarm". If no flag/manifest is present (arm never ran or it
# was wiped), we cannot drive a restore -- fail loudly.
echo "Reading the current flag '$FLAG_FILE' to reuse its restore manifest..."
READ_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! CUR_TEXT=$(mcp_tool_call_text "hub_read_file (read armed flag for manifest)" "$READ_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not read the armed flag to disarm. The watchdog may still be ARMED. Investigate / re-run."
  exit 1
fi
CUR_CONTENT=$(echo "$CUR_TEXT" | jq -r '.content // ""')
# Surface the pre-disarm state BEFORE this script rewrites the flag: a dead-man that fired
# mid-run (armed already false, fireAttempts > 0, restoreTrigger "deadline") is otherwise
# invisible -- the disarm's rewrite erases the evidence and the run reads as inexplicably
# testing OLD code past the deadline (how PR #362's mid-suite restore hid for a full run).
PRE_STATE=$(printf '%s' "$CUR_CONTENT" | jq -c '{armed, fireAttempts, deadline, restoreTrigger, restoredAt}' 2>/dev/null || echo "{}")
echo "Pre-disarm flag state: $PRE_STATE"
PRE_FIRED=$(printf '%s' "$CUR_CONTENT" | jq -r '(.fireAttempts // 0) > 0 or ((.restoreTrigger // "") == "deadline")' 2>/dev/null || echo "false")
if [ "$PRE_FIRED" = "true" ]; then
  echo "::warning::The dead-man FIRED before this disarm (see pre-disarm flag state above) -- the hub was restored to canonical main MID-RUN, so any test executed after the deadline ran against OLD code. Treat this run's results as invalid and re-run."
fi
MANIFEST_JSON=$(printf '%s' "$CUR_CONTENT" | jq -c '.manifest // empty' 2>/dev/null || echo "")
ARM_PR_SHA=$(printf '%s' "$CUR_CONTENT" | jq -r '.armPrSha // .mainSha // ""' 2>/dev/null || echo "")
# The canonical-main SHA the hub was confirmed on at arm. Forwarded into the disarm flag so the
# WATCHDOG can stamp the on-hub marker after ITS restore succeeds (CI no longer waits for the restore);
# a failed restore leaves the marker cleared (by the install), and the next run's full-repair PR install
# overwrites the apps on the hub regardless of the marker.
CANONICAL_MAIN_SHA=$(printf '%s' "$CUR_CONTENT" | jq -r '.canonicalMainSha // ""' 2>/dev/null || echo "")
FLAG_RUN_ID=$(printf '%s' "$CUR_CONTENT" | jq -r '.runId // ""' 2>/dev/null || echo "")
if [ -z "$MANIFEST_JSON" ] || [ "$MANIFEST_JSON" = "null" ]; then
  echo "::error::The flag '$FLAG_FILE' has no manifest -- arm never ran or the flag was wiped. The watchdog cannot restore main on disarm. Response: $(printf '%s' "$CUR_TEXT" | head -c 600)"
  exit 1
fi
# Defense-in-depth: the flag we are about to reuse should belong to THIS run (the lease serializes runs,
# so a mismatch means arm didn't run this run or a stale flag survived). The manifest still points at the
# canonical main install URLs, so we proceed (restoring SOME main beats failing the teardown), but flag it.
if [ -n "$FLAG_RUN_ID" ] && [ "$FLAG_RUN_ID" != "$GITHUB_RUN_ID" ]; then
  echo "::warning::Disarm: the flag's runId ($FLAG_RUN_ID) != this run ($GITHUB_RUN_ID). Reusing its manifest to restore main anyway, but arm may not have run this run -- investigate if this recurs."
fi
# --- 1.5) Re-target the restore at CURRENT canonical main (main moves while runs are in flight) -----
# The arm pins the restore to main-as-of-arm via immutable shas/ URLs (a mid-run merge cannot tear the
# restore's bytes mid-download). But when a merge DOES land mid-run, restoring the arm-time main leaves
# the hub one merge behind current main. The disarm is CI-driven and runs at teardown, so it can do what
# the hub-side dead-man cannot: re-resolve main NOW and rewrite the manifest's app/bundle URLs (plus the
# expected char counts and canonicalMainSha) to the current main SHA before flipping the flag -- the
# restore then lands what canonical main IS, not what it WAS at arm. Every rewrite obstacle falls back
# to the arm-time manifest unchanged: restoring SOME main beats failing the teardown, and the next run's
# deploy probe verifies-or-heals any residual gap. The dead-man CRASH path keeps the arm-time manifest
# by design -- when CI is dead nothing can re-resolve, and the next run's arm heal + deploy probe cover
# it. The libraries keep-set stays arm-time either way: a library NEW at current main gets its id only
# when the restore's bundle install creates it, the reconcile's delete of it is refused by the hub's
# "library in use" guard (the restored app #includes it), and the next run's arm records it properly.
RETARGETED="false"
ARM_APP_URL=$(printf '%s' "$MANIFEST_JSON" | jq -r '.app.url // empty')
if [[ "$ARM_APP_URL" =~ ^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([0-9a-f]{40})/ ]]; then
  GH_OWNER="${BASH_REMATCH[1]}"; GH_REPO="${BASH_REMATCH[2]}"; ARM_MAIN_SHA="${BASH_REMATCH[3]}"
  # SSRF guard: every URL we fetch below comes from the hub flag manifest (read back, not trusted),
  # so each app/bundle entry is pinned to this exact raw.githubusercontent.com/<owner>/<repo>/ prefix
  # (the host+owner+repo the FIRST app url proved) before any curl, and every curl runs --max-redirs 0
  # so a 3xx cannot bounce the fetch off-host. A manifest entry pointing anywhere else abandons the
  # re-target and keeps the arm-time manifest. The whole prefix is quoted in the case pattern so
  # owner/repo are matched LITERALLY (never as a glob) and only the trailing path is wildcarded.
  EXPECT_PREFIX="https://raw.githubusercontent.com/${GH_OWNER}/${GH_REPO}/"
  CUR_MAIN_SHA=$(git ls-remote "https://github.com/${GH_OWNER}/${GH_REPO}.git" refs/heads/main 2>/dev/null | awk 'NR==1{print $1}' || true)
  if ! printf '%s' "$CUR_MAIN_SHA" | grep -qE '^[0-9a-f]{40}$'; then
    echo "::warning::Disarm re-target: could not resolve current main's SHA (git ls-remote failed) -- restoring the arm-time main (${ARM_MAIN_SHA}); the next run's deploy probe heals any gap."
  elif [ "$CUR_MAIN_SHA" = "$ARM_MAIN_SHA" ]; then
    echo "Disarm re-target: main has not moved since arm (${ARM_MAIN_SHA}) -- the arm-time manifest IS current main."
  else
    echo "Main moved while this run was in flight: arm-time ${ARM_MAIN_SHA} -> current ${CUR_MAIN_SHA}. Rewriting the restore manifest to current main..."
    RT_OK="true"
    # (a) Apps: same path re-anchored at the new SHA, expected chars re-measured CI-side (the
    # watchdog's landing assert compares against them). A 404/short fetch (file renamed at the new
    # main?) abandons the whole rewrite -- a torn manifest must never reach the watchdog.
    NEW_APPS="[]"
    while IFS= read -r APP_REC; do
      [ -z "$APP_REC" ] && continue
      A_ID=$(printf '%s' "$APP_REC" | jq -r '.classId')
      A_URL=$(printf '%s' "$APP_REC" | jq -r '.url')
      case "$A_URL" in
        "$EXPECT_PREFIX"*) ;;
        *) echo "::warning::Disarm re-target: app ${A_ID} url is not under the expected ${EXPECT_PREFIX} (${A_URL}) -- keeping the arm-time manifest."; RT_OK="false"; break ;;
      esac
      case "$A_URL" in
        *"$ARM_MAIN_SHA"*) ;;
        *) echo "::warning::Disarm re-target: app ${A_ID} url does not embed the arm-time SHA (${A_URL}) -- cannot re-anchor; keeping the arm-time manifest."; RT_OK="false"; break ;;
      esac
      N_URL="${A_URL//$ARM_MAIN_SHA/$CUR_MAIN_SHA}"
      N_CHARS=$(curl -fsSL --max-redirs 0 --max-time 60 "$N_URL" 2>/dev/null | LC_ALL=C.UTF-8 wc -m | tr -d '[:space:]' || true)
      if ! printf '%s' "$N_CHARS" | grep -qE '^[0-9]+$' || [ "$N_CHARS" -lt 1000 ]; then
        echo "::warning::Disarm re-target: could not measure ${N_URL} (chars=${N_CHARS:-<none>}) -- keeping the arm-time manifest."
        RT_OK="false"; break
      fi
      NEW_APPS=$(jq -nc --argjson acc "$NEW_APPS" --arg id "$A_ID" --arg url "$N_URL" --arg chars "$N_CHARS" '$acc + [{classId:$id, url:$url, mainChars:$chars}]')
    done < <(printf '%s' "$MANIFEST_JSON" | jq -c '.apps[]?')
    if [ "$RT_OK" = "true" ] && [ "$(printf '%s' "$NEW_APPS" | jq -r 'length')" -lt 1 ]; then
      echo "::warning::Disarm re-target: the arm-time manifest carries no apps[] entries (old-format flag?) -- keeping it unchanged."
      RT_OK="false"
    fi
    # (b) Bundles: re-resolve each on the bundle-artifacts branch at the new SHA (branches/main
    # fallback for a publish race), and byte-compare old vs new so (c) knows whether the bundle
    # leg actually moved with main.
    NEW_BUNDLES="[]"
    BUNDLE_BYTES_MOVED="false"
    if [ "$RT_OK" = "true" ]; then
      while IFS= read -r B_REC; do
        [ -z "$B_REC" ] && continue
        B_URL=$(printf '%s' "$B_REC" | jq -r '.url')
        case "$B_URL" in
          "$EXPECT_PREFIX"*) ;;
          *) echo "::warning::Disarm re-target: bundle url is not under the expected ${EXPECT_PREFIX} (${B_URL}) -- keeping the arm-time manifest."; RT_OK="false"; break ;;
        esac
        B_BASE="${B_URL##*/}"
        REPO_BASE="https://raw.githubusercontent.com/${GH_OWNER}/${GH_REPO}"
        N_BURL=""
        for CAND in "${REPO_BASE}/bundle-artifacts/shas/${CUR_MAIN_SHA}/${B_BASE}" "${REPO_BASE}/bundle-artifacts/branches/main/${B_BASE}"; do
          if curl -fsSL --max-redirs 0 --max-time 30 -o /dev/null "${CAND}.size" 2>/dev/null; then N_BURL="$CAND"; break; fi
        done
        if [ -z "$N_BURL" ]; then
          echo "::warning::Disarm re-target: no bundle-artifacts entry for ${B_BASE} at ${CUR_MAIN_SHA} (publish race?) -- keeping the arm-time manifest."
          RT_OK="false"; break
        fi
        OLD_TMP="$(mktemp)"; NEW_TMP="$(mktemp)"
        if ! curl -fsSL --max-redirs 0 --max-time 60 "$B_URL" -o "$OLD_TMP" 2>/dev/null || ! curl -fsSL --max-redirs 0 --max-time 60 "$N_BURL" -o "$NEW_TMP" 2>/dev/null; then
          rm -f "$OLD_TMP" "$NEW_TMP"
          echo "::warning::Disarm re-target: could not download the arm-time/current bundle to compare -- keeping the arm-time manifest."
          RT_OK="false"; break
        fi
        cmp -s "$OLD_TMP" "$NEW_TMP" || BUNDLE_BYTES_MOVED="true"
        rm -f "$OLD_TMP" "$NEW_TMP"
        NEW_BUNDLES=$(jq -nc --argjson acc "$NEW_BUNDLES" --argjson b "$B_REC" --arg url "$N_BURL" '$acc + [($b + {url:$url})]')
      done < <(printf '%s' "$MANIFEST_JSON" | jq -c '.bundles[]?')
    fi
    # (c) The deploy's run-scoped "<runId>:unchanged" marker means "the hub's libraries == main-at-
    # DEPLOY-time bytes". If the bundle bytes moved with main, that marker would let the restore skip
    # the bundle leg while the apps land at the NEW main -- recreating the very apps-new/libraries-old
    # staleness this re-target exists to close. Force it to "changed" BEFORE flipping the flag; if the
    # force does not confirm, fall back to the arm-time manifest (a consistent old-main restore beats
    # a torn one).
    if [ "$RT_OK" = "true" ] && [ "$BUNDLE_BYTES_MOVED" = "true" ]; then
      FORCE_RPC=$(jq -nc --arg c "${GITHUB_RUN_ID}:changed" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"e2e-pr-bundle-state.txt",content:$c,confirm:true}}}')
      # mcp_tool_call_text returns rc 0 on a TOOL-level failure -- it only returns nonzero on a relay
      # drop (5 non-JSON retries) or a JSON-RPC error envelope, NOT on a hub_write_file that returned
      # {success:false} inside a valid result body. So `if ! ...` alone would let a silent write
      # rejection (confirm gate, File Manager IO/quota, hub busy) pass while the marker stays
      # '<runId>:unchanged' -- the watchdog restore would then skip the bundle leg under the stale
      # marker while the apps land at the NEW main, recreating the apps-new/libraries-old staleness
      # this force exists to close. Capture the body and assert tool-level .success, mirroring the
      # deploy's matching marker write and the disarm flag read-back below.
      FORCE_TEXT=$(mcp_tool_call_text "hub_write_file (force bundle-state changed)" "$FORCE_RPC" || true)
      if [ "$(printf '%s' "$FORCE_TEXT" | jq -r '.success // false' 2>/dev/null || echo false)" != "true" ]; then
        echo "::warning::Disarm re-target: could not force the bundle-state marker to 'changed' (hub verbatim: $(printf '%s' "$FORCE_TEXT" | jq -r '.error // .message // empty' 2>/dev/null || true)) -- keeping the arm-time manifest (a consistent arm-time restore beats apps-new/libraries-old)."
        RT_OK="false"
      fi
    fi
    if [ "$RT_OK" = "true" ]; then
      MANIFEST_JSON=$(jq -nc --argjson m "$MANIFEST_JSON" --argjson apps "$NEW_APPS" --argjson bundles "$NEW_BUNDLES" '$m + {apps:$apps, app:($apps[0]), bundles:$bundles}')
      CANONICAL_MAIN_SHA="$CUR_MAIN_SHA"
      RETARGETED="true"
      echo "Restore manifest re-targeted at current main ${CUR_MAIN_SHA} (bundle bytes moved: ${BUNDLE_BYTES_MOVED})."
    fi
  fi
else
  echo "::warning::Disarm re-target: manifest app url is not a SHA-anchored raw.githubusercontent URL (${ARM_APP_URL:-<empty>}) -- keeping the arm-time manifest."
fi

# --- 2) Write {armed:false, intent:"disarm", ...manifest...}, then READ IT BACK and assert ----------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; portable + sub-second precision is moot.
DISARMED_AT_MS=$(( $(date +%s) * 1000 ))
FLAG_JSON=$(jq -nc \
  --arg runId "$GITHUB_RUN_ID" \
  --argjson disarmedAt "$DISARMED_AT_MS" \
  --arg armPrSha "$ARM_PR_SHA" \
  --arg canonicalMainSha "$CANONICAL_MAIN_SHA" \
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:false, intent:"disarm", runId:$runId, disarmedAt:$disarmedAt, armPrSha:$armPrSha, canonicalMainSha:$canonicalMainSha, manifest:$manifest}')

echo "Disarming the watchdog: writing {armed:false, intent:\"disarm\"} to '$FLAG_FILE'..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (disarm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the disarm write. The watchdog may still be ARMED and could fire its auto-restore (within ~30 min of the last heartbeat). Investigate / re-run."
  exit 1
fi

echo "Reading the flag back to confirm it disarmed..."
READBACK_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
if ! READBACK_TEXT=$(mcp_tool_call_text "hub_read_file (disarm read-back)" "$READBACK_RPC"); then
  echo "::error::hub_read_file('$FLAG_FILE') read-back returned non-JSON $RPC_ATTEMPTS times -- could not confirm the disarm landed. The watchdog may still be ARMED. Investigate / re-run."
  exit 1
fi
RB_ARMED=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .armed' 2>/dev/null || echo "")
RB_INTENT=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .intent' 2>/dev/null || echo "")
if [ "$RB_ARMED" != "false" ] || [ "$RB_INTENT" != "disarm" ]; then
  echo "::error::Disarm did NOT land: flag read back armed=$RB_ARMED intent=$RB_INTENT (expected armed=false intent=disarm). The write may have silently no-opped, leaving the watchdog ARMED to fire (within ~30 min of the last heartbeat). Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi
# CI no longer polls for restore completion, so this read-back is the ONLY pre-restore verification. The
# async restore consumes manifest.app.classId (restorePackage fails with "no manifest"/"app entry missing
# classId" without it), so a 504 that flipped armed:false but dropped the manifest would leave a flag the
# watchdog cannot restore from -- and that surfaces only in the watchdog log, which CI never reads. Assert
# the restore-critical field round-tripped, mirroring the arm's own classId read-back.
EXPECT_CLASS=$(printf '%s' "$MANIFEST_JSON" | jq -r '.app.classId // empty' 2>/dev/null || echo "")
RB_CLASS=$(echo "$READBACK_TEXT" | jq -r '.content | fromjson | .manifest.app.classId' 2>/dev/null || echo "")
if [ -z "$RB_CLASS" ] || [ "$RB_CLASS" = "null" ] || { [ -n "$EXPECT_CLASS" ] && [ "$RB_CLASS" != "$EXPECT_CLASS" ]; }; then
  echo "::error::Disarm read-back: the restore-critical manifest did NOT survive the write (manifest.app.classId read back '$RB_CLASS', expected '${EXPECT_CLASS:-<any non-empty>}'). The watchdog has no usable restore target -- the disarm write likely no-opped the manifest. Investigate / re-run. Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

# --- 2.5) Purge BAT_E2E_ fixtures (rules + variables) LOCALLY over WATCHDOG_URL --------------------
# ONE call to the watchdog's hub_purge_e2e_artifacts enumerates every BAT_E2E_ app instance + hub
# variable and deletes them LOOPBACK-LOCAL on the hub -- no per-item cloud round-trips, so this
# replaces the old per-id force-delete loop (and now also reaps vars). Prefix-scoped, which is safe on
# the sacrificial test hub (BAT_E2E_ == test fixtures by convention). Best-effort + SEQUENTIAL: a
# hiccup NEVER fails the restore -- the post-restore --cleanup-only step is the idempotent prefix
# backstop (and is what reaps BAT_E2E_ DEVICES, which the watchdog can't delete). The test step still
# defers rule deletes (E2E_DEFER_NATIVE_DELETES) so they stay off the test critical path; this one
# call cleans the whole accumulation at once.
if PURGE_TEXT=$(mcp_tool_call_text "hub_purge_e2e_artifacts" \
    "$(jq -nc '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_purge_e2e_artifacts",arguments:{confirm:true}}}')" 2>/dev/null); then
  echo "Local purge: $(printf '%s' "$PURGE_TEXT" | jq -c '{deletedCount,failedCount,variablesDeletedCount,variablesFailedCount}' 2>/dev/null | head -c 200)"
  PURGE_FAILS=$(printf '%s' "$PURGE_TEXT" | jq -r '((.failedCount // 0) + (.variablesFailedCount // 0))' 2>/dev/null || echo "?")
  if [ "$PURGE_FAILS" != "0" ]; then
    echo "::warning::Local purge reported ${PURGE_FAILS} failure(s) -- the post-restore --cleanup-only prefix sweep is the backstop. Detail: $(printf '%s' "$PURGE_TEXT" | jq -c '{failed,variablesFailed}' 2>/dev/null | head -c 300)"
  fi
else
  echo "::warning::Local purge (hub_purge_e2e_artifacts) returned no parseable result (tool missing on the watchdog / endpoint down?) -- relying on the post-restore --cleanup-only prefix sweep to reap BAT_E2E_ fixtures."
fi

# --- 3) Fire-and-forget: the watchdog restores main asynchronously --------------------------------
# The flag write above already KICKED a one-shot checkDeadman (~2s); the watchdog installs main's bundle
# + every app from the canonical https URLs in the flag manifest, stamps restoreResult/restoreFor into
# the flag, and on success writes the canonical-main SHA marker itself. CI deliberately does NOT wait:
# restore-to-main fidelity is not a gate here -- the next run's PR install is a full HPM-style repair
# that unconditionally overwrites the parent + child apps on the hub regardless of the marker (the
# bundle is re-established only when the PR bundle differs from main, so a byte-identical-to-main bundle
# stranded on the hub is the one residual gap). A dead watchdog surfaces loudly next run (the arm's
# health check), not by burning minutes in every teardown.
echo "WATCHDOG_DISARMED runId=$GITHUB_RUN_ID retargeted=$RETARGETED (restore fired asynchronously; not polling -- the watchdog stamps the canonical-main marker on success, and the next run's full-repair install overwrites the apps regardless)."
exit 0
