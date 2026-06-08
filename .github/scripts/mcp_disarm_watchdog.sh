#!/usr/bin/env bash
# Disarm the E2E Dead-Man Watchdog v2 at the end of a run (the dead-man's "I'm alive" signal) AND
# drive its clean-finish restore of main.
#
# v2's disarm is not just "stop the timer": the watchdog treats intent=disarm as a request to restore
# the whole MCP package from the cache ONCE per runId (it stamps restoreFor=runId so it never repeats).
# So this step writes {armed:false, intent:"disarm", runId, ...manifest...} (carrying the SAME manifest
# the arm flag holds, so the watchdog knows what to restore), then POLLS the flag until the watchdog
# writes back restoreResult=="restored" && restoreFor==runId -- and ASSERTS that. The watchdog's
# checkDeadman runs every minute, so the restore lands within a couple of minutes; we poll on a bounded
# timeout. This runs as an always() step BEFORE the lease is released, so the next run can never inherit
# an armed flag, and main is left restored to the known-good cache.
#
# CRITICAL: every hub I/O here goes through the WATCHDOG's own MCP endpoint ($WATCHDOG_URL, from the
# WATCHDOG_MCP_URL secret), NOT the main server's $MCP_URL -- the watchdog is the driver of the restore
# plumbing and may be the only live server if the run bricked main. A silently-failed disarm is the
# dangerous case (the watchdog would otherwise fire mid-next-run), so the disarm WRITE is read back and
# the restore is polled+asserted -- this step succeeds only when armed==false landed AND the watchdog
# confirmed restoreResult==restored for this runId.
#
# Env:  WATCHDOG_URL   -- full cloud OAuth URL for the WATCHDOG's /mcp endpoint (WATCHDOG_MCP_URL secret)
#       GITHUB_RUN_ID  -- this run's id (stamped into the flag; the watchdog echoes it as restoreFor)
set -euo pipefail

: "${WATCHDOG_URL:?WATCHDOG_URL env var required (full cloud OAuth URL for the watchdog /mcp endpoint, from secret WATCHDOG_MCP_URL)}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID env var required}"

FLAG_FILE="e2e-deadman-v2.json"

# Bound the wait for the watchdog to land the restore. The disarm flag-write KICKS a one-shot
# checkDeadman in ~2s (watchdog adminWriteFile -> deadmanKick), so the restore normally starts almost
# immediately; the runEvery1Minute tick is the fallback if that kick is ever missed. Poll finely (8s)
# so completion is observed promptly; ~6 min total still covers a retry storm on the periodic schedule
# and stays well under the e2e job's own timeout.
RESTORE_POLL_ATTEMPTS=45
RESTORE_POLL_SLEEP=8

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

# Post-restore length TRIPWIRE (defense-in-depth, not the primary guarantee): the watchdog only stamps
# restoreResult==restored after restoreApp's BYTE-IDENTICAL check passed, so byte-correct main is already
# proven by the time we get here. This is a cheap independent cross-check that the live app length equals
# the arm-stamped MAIN baseline (mainChars, captured BEFORE any PR deploy) -- it would catch a gross
# regression (e.g. the wrong file restored). Reads with noSave:true so the check doesn't re-cache.
assert_hub_on_main() {
  if [ -z "${MAIN_CHARS:-}" ] || [ "${MAIN_CHARS:-0}" = "0" ] || [ -z "${APP_CLASS:-}" ]; then
    echo "::warning::Post-disarm: no mainChars/classId baseline in the manifest -- skipping the independent hub-on-main length assertion (older arm flag)."
    return 0
  fi
  local live
  live=$(mcp_tool_call_text "hub_get_source (post-disarm hub-on-main check)" \
    "$(jq -nc --arg id "$APP_CLASS" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_get_source",arguments:{type:"app",id:$id,offset:0,length:1,noSave:true}}}')" \
    | jq -r '.totalLength // empty' 2>/dev/null || true)
  if [ -z "$live" ]; then
    echo "::warning::Post-disarm: could not read the live app source length to assert hub-on-main (transient). The watchdog reported restored; proceeding."
    return 0
  fi
  if [ "$live" != "$MAIN_CHARS" ]; then
    echo "::error::Post-disarm hub-on-main assertion FAILED: live app source length ${live} != arm-stamped MAIN baseline ${MAIN_CHARS} (class ${APP_CLASS}). The disarm reported 'restored' but the hub is NOT on main -- a poisoned/incorrect restore. Investigate before the next run."
    exit 1
  fi
  echo "Post-disarm hub-on-main assertion OK: live app source length ${live} == MAIN baseline ${MAIN_CHARS}."
}

# List a hub surface (hub_list_bundles / hub_list_libraries) over the watchdog, RETRYING until it returns
# the populated 'hub_api' shape. Echoes the hub_api response on success; returns 1 if the list stays
# degraded after retries. mcp_tool_call_text already retries a relay DROP (non-JSON); this additionally
# retries a degraded SHAPE (hub_api_raw / unavailable -- a valid JSON-RPC reply the transport layer won't
# re-fetch), so a momentarily-busy /hub2 endpoint gets another chance before the caller fails closed.
_nostale_list_hub_api() {
  local label="$1" rpc="$2" attempt=1 resp src
  while [ "$attempt" -le 4 ]; do
    resp=$(mcp_tool_call_text "$label" "$rpc" || true)
    src=$(printf '%s' "$resp" | jq -r '.source // empty' 2>/dev/null || true)
    if [ "$src" = "hub_api" ]; then printf '%s' "$resp"; return 0; fi
    attempt=$((attempt + 1))
    [ "$attempt" -le 4 ] && sleep 5
  done
  echo "::error::${label}: never returned the populated hub_api shape (last source=${src:-none}) after retries -- CANNOT verify the post-restore cleanup. Failing CLOSED: PR #247's no-stale guarantee must be PROVEN, not skipped on an unreadable list." >&2
  return 1
}

# Post-restore "no stale code" assertion (PR #247): after the watchdog restored main, the hub must carry
# ONLY main's bundles + libraries -- nothing left over from the PR install. The arm recorded main's
# bundle set (manifest.bundles) + library set (manifest.libraries); we list the hub now and FAIL if any
# mcp-namespace bundle/library is present that is NOT in those sets. This is the hard gate behind
# restorePackage's best-effort cleanup -- a PR bundle/lib that survived the restore fails the run loudly.
# An axis is SKIPPED only when the manifest lacks that set (an older arm flag, where there is genuinely
# nothing to compare against). A degraded live list is NOT skipped -- it is retried, then FAILS CLOSED
# (an unverifiable cleanup must not pass green for this PR's headline no-stale guarantee).
assert_no_stale_mcp_code() {
  local mainBundles mainLibs
  mainBundles=$(printf '%s' "$MANIFEST_JSON" | jq -c '[.bundles[]? | {namespace, name}]' 2>/dev/null || echo "[]")
  mainLibs=$(printf '%s' "$MANIFEST_JSON" | jq -c '[.libraries[]? | {namespace, name}]' 2>/dev/null || echo "[]")

  if [ "$(printf '%s' "$mainBundles" | jq 'length' 2>/dev/null || echo 0)" -gt 0 ]; then
    local blist stale_b
    if ! blist=$(_nostale_list_hub_api "hub_list_bundles (post-restore no-stale check)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_bundles","arguments":{}}}'); then
      exit 1
    fi
    stale_b=$(printf '%s' "$blist" | jq -c --argjson keep "$mainBundles" \
      '[.bundles[]? | select(.namespace=="mcp") | {namespace, name} | select(. as $x | ($keep | any(. == $x)) | not) | .name]' 2>/dev/null || echo "[]")
    if [ "$(printf '%s' "$stale_b" | jq 'length' 2>/dev/null || echo 0)" -gt 0 ]; then
      echo "::error::Post-restore: STALE mcp bundle(s) remain after the overwrite-with-main restore: $(printf '%s' "$stale_b" | jq -c .). restorePackage's bundle cleanup did not remove the PR's bundle. Investigate."
      exit 1
    fi
    echo "Post-restore no-stale check OK: no leftover mcp bundles (kept $(printf '%s' "$mainBundles" | jq -c '[.[].name]'))."
  else
    echo "::notice::Post-restore no-stale check: manifest has no main bundle set -- skipping the bundle assertion (older arm flag)."
  fi

  if [ "$(printf '%s' "$mainLibs" | jq 'length' 2>/dev/null || echo 0)" -gt 0 ]; then
    local llist stale_l
    if ! llist=$(_nostale_list_hub_api "hub_list_libraries (post-restore no-stale check)" '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_list_libraries","arguments":{}}}'); then
      exit 1
    fi
    stale_l=$(printf '%s' "$llist" | jq -c --argjson keep "$mainLibs" \
      '[.libraries[]? | select(.namespace=="mcp") | {namespace, name} | select(. as $x | ($keep | any(. == $x)) | not) | .name]' 2>/dev/null || echo "[]")
    if [ "$(printf '%s' "$stale_l" | jq 'length' 2>/dev/null || echo 0)" -gt 0 ]; then
      echo "::error::Post-restore: STALE mcp library(ies) remain after the overwrite-with-main restore: $(printf '%s' "$stale_l" | jq -c .). restorePackage's library cleanup did not remove the PR-only libraries. Investigate."
      exit 1
    fi
    echo "Post-restore no-stale check OK: no leftover mcp libraries (kept $(printf '%s' "$mainLibs" | jq -c '[.[].name]'))."
  else
    echo "::notice::Post-restore no-stale check: manifest has no main library set -- skipping the library assertion (older arm flag)."
  fi
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
MANIFEST_JSON=$(printf '%s' "$CUR_CONTENT" | jq -c '.manifest // empty' 2>/dev/null || echo "")
ARM_PR_SHA=$(printf '%s' "$CUR_CONTENT" | jq -r '.armPrSha // .mainSha // ""' 2>/dev/null || echo "")
# The canonical-main SHA the hub was confirmed on at arm. We rewrite the on-hub marker to this ONLY after
# a confirmed restore below, so a failed restore leaves the marker cleared (by the install) and the next
# run's arm refreshes canonical main instead of skipping on a stale marker.
CANONICAL_MAIN_SHA=$(printf '%s' "$CUR_CONTENT" | jq -r '.canonicalMainSha // ""' 2>/dev/null || echo "")
FLAG_RUN_ID=$(printf '%s' "$CUR_CONTENT" | jq -r '.runId // ""' 2>/dev/null || echo "")
if [ -z "$MANIFEST_JSON" ] || [ "$MANIFEST_JSON" = "null" ]; then
  echo "::error::The flag '$FLAG_FILE' has no manifest -- arm never ran or the flag was wiped. The watchdog cannot restore main on disarm. Response: $(printf '%s' "$CUR_TEXT" | head -c 600)"
  exit 1
fi
# Defense-in-depth: the flag we are about to reuse should belong to THIS run (the lease serializes runs,
# so a mismatch means arm didn't run this run or a stale flag survived). The manifest still points at a
# known-good main cache, so we proceed (restoring SOME main beats failing the teardown), but flag it.
if [ -n "$FLAG_RUN_ID" ] && [ "$FLAG_RUN_ID" != "$GITHUB_RUN_ID" ]; then
  echo "::warning::Disarm: the flag's runId ($FLAG_RUN_ID) != this run ($GITHUB_RUN_ID). Reusing its manifest to restore main anyway, but arm may not have run this run -- investigate if this recurs."
fi
# Arm-stamped MAIN baseline (length captured BEFORE any PR deploy) + app class, for the post-restore
# hub-on-main assertion in assert_hub_on_main.
MAIN_CHARS=$(printf '%s' "$MANIFEST_JSON" | jq -r '.app.mainChars // ""' 2>/dev/null || echo "")
APP_CLASS=$(printf '%s' "$MANIFEST_JSON" | jq -r '.app.classId // ""' 2>/dev/null || echo "")

# --- 2) Write {armed:false, intent:"disarm", ...manifest...}, then READ IT BACK and assert ----------
# `date +%s` * 1000 (not %s%3N): %3N is a GNU-only extension; portable + sub-second precision is moot.
DISARMED_AT_MS=$(( $(date +%s) * 1000 ))
FLAG_JSON=$(jq -nc \
  --arg runId "$GITHUB_RUN_ID" \
  --argjson disarmedAt "$DISARMED_AT_MS" \
  --arg armPrSha "$ARM_PR_SHA" \
  --argjson manifest "$MANIFEST_JSON" \
  '{armed:false, intent:"disarm", runId:$runId, disarmedAt:$disarmedAt, armPrSha:$armPrSha, manifest:$manifest}')

echo "Disarming the watchdog: writing {armed:false, intent:\"disarm\"} to '$FLAG_FILE'..."
WRITE_RPC=$(jq -nc --arg fn "$FLAG_FILE" --arg content "$FLAG_JSON" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:$content,confirm:true}}}')
if ! mcp_tool_call_text "hub_write_file (disarm flag)" "$WRITE_RPC" >/dev/null; then
  echo "::error::hub_write_file('$FLAG_FILE') returned non-JSON $RPC_ATTEMPTS times -- could not confirm the disarm write. The watchdog may still be ARMED and could fire its auto-restore (~35 min). Investigate / re-run."
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
  echo "::error::Disarm did NOT land: flag read back armed=$RB_ARMED intent=$RB_INTENT (expected armed=false intent=disarm). The write may have silently no-opped, leaving the watchdog ARMED to fire (~35 min). Response: $(printf '%s' "$READBACK_TEXT" | head -c 600)"
  exit 1
fi

# --- 2.5) Reap DEFERRED native rules (E2E_DEFER_NATIVE_DELETES) over WATCHDOG_URL -------------------
# If the test step deferred its per-test native-rule fixture deletes, it wrote the EXACT tracked instance
# ids to 'e2e-deferred-native-rules.json'. Force-delete each (the INSTANCE, via the watchdog's
# hub_force_delete_app) NOW -- the round-trips overlap the wall-clock the restore poll below spends
# sleeping. Precise ids only (no /hub2/appsList shape-guessing -> no wrong-app risk). Best-effort +
# SEQUENTIAL (RM concurrency rule): a hiccup NEVER fails the step (the post-restore --cleanup-only step
# is the idempotent prefix backstop). Absent file / deferral-off -> clean no-op.
DEFERRED_FILE="e2e-deferred-native-rules.json"
if DEF_TEXT=$(mcp_tool_call_text "hub_read_file (deferred native rules)" \
    "$(jq -nc --arg fn "$DEFERRED_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')" 2>/dev/null); then
  DEF_IDS=$(printf '%s' "$DEF_TEXT" | jq -r '(.content // "[]") | fromjson | .[]?' 2>/dev/null || true)
  if [ -n "$DEF_IDS" ]; then
    def_n=0
    while IFS= read -r rid; do
      [ -z "$rid" ] && continue
      def_n=$((def_n + 1))
      echo "Deferred-rule sweep: force-deleting native rule instance ${rid} (overlapping the restore)..."
      mcp_call "$(jq -nc --arg id "$rid" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_force_delete_app",arguments:{id:$id,confirm:true}}}')" >/dev/null 2>&1 \
        || echo "::warning::Deferred-rule sweep: force-delete of ${rid} hiccupped (best-effort; the --cleanup-only backstop will reap it)."
    done <<< "$DEF_IDS"
    echo "Deferred-rule sweep: requested force-delete of ${def_n} native rule instance(s)."
    # Empty the list so a re-run / the backstop never re-processes stale ids.
    mcp_tool_call_text "hub_write_file (clear deferred list)" \
      "$(jq -nc --arg fn "$DEFERRED_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:"[]",confirm:true}}}')" >/dev/null 2>&1 || true
  else
    echo "Deferred-rule sweep: list empty -- nothing to reap (deferral off or no fixtures left behind)."
  fi
else
  echo "Deferred-rule sweep: no deferred-rule list file -- skipping (deferral was off)."
fi

# --- 3) Poll for the watchdog's clean-finish restore (restoreResult==restored && restoreFor==runId) -
# checkDeadman runs every minute and processes intent=disarm exactly once per runId, stamping
# restoreFor=runId + restoreResult. We poll the flag until that stamp matches THIS run, then assert it
# is "restored". A bounded loop so a stuck/dead watchdog fails the step loudly instead of hanging.
echo "Polling '$FLAG_FILE' for the watchdog's clean-finish restore (restoreFor==$GITHUB_RUN_ID, up to $(( RESTORE_POLL_ATTEMPTS * RESTORE_POLL_SLEEP ))s)..."
POLL_RPC=$(jq -nc --arg fn "$FLAG_FILE" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_read_file",arguments:{fileName:$fn}}}')
attempt=1
RESTORE_RESULT=""
RESTORE_FOR=""
RESTORE_DETAIL=""
while [ "$attempt" -le "$RESTORE_POLL_ATTEMPTS" ]; do
  if ! POLL_TEXT=$(mcp_tool_call_text "hub_read_file (restore poll $attempt)" "$POLL_RPC"); then
    echo "::warning::Restore poll ${attempt}/${RESTORE_POLL_ATTEMPTS}: hub_read_file returned non-JSON $RPC_ATTEMPTS times -- transient; retrying."
  else
    RESTORE_RESULT=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreResult // ""' 2>/dev/null || echo "")
    RESTORE_FOR=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreFor // ""' 2>/dev/null || echo "")
    RESTORE_DETAIL=$(echo "$POLL_TEXT" | jq -r '.content | fromjson | .restoreDetail // ""' 2>/dev/null || echo "")
    if [ "$RESTORE_FOR" = "$GITHUB_RUN_ID" ]; then
      # The watchdog has acted on THIS run's disarm. Assert it restored (vs. latched "failed").
      if [ "$RESTORE_RESULT" = "restored" ]; then
        echo "WATCHDOG_DISARMED runId=$GITHUB_RUN_ID restoreResult=restored restoreFor=$RESTORE_FOR detail=$RESTORE_DETAIL"
        # Independently confirm the hub is actually back on MAIN (not a poisoned/PR restore) before we
        # let teardown + the next run proceed.
        assert_hub_on_main
        # The overwrite-with-main restore must leave NO leftover from the PR install -- assert no stale
        # mcp-namespace bundle/library remains (PR #247), the hard gate behind restorePackage's cleanup.
        assert_no_stale_mcp_code
        # Restore CONFIRMED -> the hub is canonical main again. Rewrite the SHA marker the install cleared,
        # so the next run's arm can skip the main refresh (hub known-good). A FAILED restore takes the
        # exit-1 paths below WITHOUT rewriting it, leaving it cleared -> the next run refreshes. Best-effort.
        if [ -n "$CANONICAL_MAIN_SHA" ]; then
          mcp_tool_call_text "hub_write_file (rewrite canonical-main marker after confirmed restore)" \
            "$(jq -nc --arg sha "$CANONICAL_MAIN_SHA" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:"mcp-main-deployed-sha.txt",content:$sha,confirm:true}}}')" >/dev/null \
            || echo "::warning::could not rewrite the canonical-main SHA marker after the confirmed restore; the next run will simply re-refresh main. Continuing."
        fi
        exit 0
      fi
      echo "::error::Watchdog clean-finish restore FAILED for run $GITHUB_RUN_ID (restoreResult=$RESTORE_RESULT, restoreFor=$RESTORE_FOR). Main may NOT be restored to the known-good cache. Detail: $RESTORE_DETAIL"
      exit 1
    fi
    echo "Restore poll ${attempt}/${RESTORE_POLL_ATTEMPTS}: not yet (restoreResult=${RESTORE_RESULT:-<none>}, restoreFor=${RESTORE_FOR:-<none>}); waiting for checkDeadman..."
  fi
  attempt=$((attempt + 1))
  [ "$attempt" -le "$RESTORE_POLL_ATTEMPTS" ] && sleep "$RESTORE_POLL_SLEEP"
done

echo "::error::Timed out after $(( RESTORE_POLL_ATTEMPTS * RESTORE_POLL_SLEEP ))s waiting for the watchdog to restore main for run $GITHUB_RUN_ID (last seen restoreResult=${RESTORE_RESULT:-<none>}, restoreFor=${RESTORE_FOR:-<none>}). The disarm flag landed (armed=false) but checkDeadman did not confirm the restore -- the watchdog may be dead or its schedule dropped. Investigate."
exit 1
