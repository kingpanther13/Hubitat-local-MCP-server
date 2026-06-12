#!/usr/bin/env bash
# Disarm the E2E Dead-Man Watchdog v2 at the end of a run (the dead-man's "I'm alive" signal) and
# FIRE-AND-FORGET its clean-finish restore of main.
#
# v2's disarm is not just "stop the timer": the watchdog treats intent=disarm as a request to restore
# the whole MCP package from the canonical main install URLs in the flag manifest ONCE per runId (it
# stamps restoreFor=runId so it never repeats). This step writes {armed:false, intent:"disarm", runId,
# ...manifest...} (carrying the SAME manifest + canonicalMainSha the arm flag holds, so the watchdog
# knows what to install and how to stamp the canonical-main marker), reads the flag back to assert
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
  # Split a parse failure from a valid (possibly empty) array. hub_read_file returns the file body
  # under .content; jq '.[]' errors (exit != 0) on truncated/corrupt JSON but yields empty output on a
  # valid []. A SWALLOWED parse error must NOT collapse to "nothing to reap" -- that would silently
  # skip the exact-id sweep and leave the rules to only the non-gating prefix backstop.
  DEF_CONTENT=$(printf '%s' "$DEF_TEXT" | jq -r '.content // ""' 2>/dev/null || true)
  if [ -z "$DEF_CONTENT" ]; then
    echo "Deferred-rule sweep: deferred-id file had no content (deferral off, or no native fixtures left behind)."
  elif ! DEF_IDS=$(printf '%s' "$DEF_CONTENT" | jq -r '.[]' 2>/dev/null); then
    echo "::error::Deferred-rule sweep: ${DEFERRED_FILE} is not a valid JSON array (truncated/corrupt write?) -- exact-id sweep SKIPPED; the post-restore --cleanup-only prefix sweep is now the ONLY backstop for these ids. Raw head: $(printf '%s' "$DEF_CONTENT" | head -c 200 | tr '\n' ' ')"
  elif [ -z "$DEF_IDS" ]; then
    echo "Deferred-rule sweep: deferred-id list is an empty array -- nothing to reap (no native fixtures left behind)."
  else
    def_n=0
    def_fail=0
    while IFS= read -r rid; do
      [ -z "$rid" ] && continue
      def_n=$((def_n + 1))
      echo "Deferred-rule sweep: force-deleting native rule instance ${rid} (overlapping the restore)..."
      # mcp_tool_call_text PARSES the JSON-RPC result so we can check tool-level success -- force-delete is
      # idempotent (deleting a gone rule is a no-op), so its retry is safe. Raw mcp_call would discard a
      # tool-level error (tool missing, auth fail, 4xx/5xx) and look fine, masking a real failure.
      if FD_TEXT=$(mcp_tool_call_text "hub_force_delete_app ${rid}" \
          "$(jq -nc --arg id "$rid" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_force_delete_app",arguments:{id:$id,confirm:true}}}')" 2>/dev/null); then
        # mcp_tool_call_text already unwraps .result.content[0].text, which the watchdog builds by
        # JSON-serializing the tool's return map directly -- so success is TOP-LEVEL here ('.success'),
        # NOT under a '.content' key. (The '.content | fromjson' shape applies only to hub_read_file,
        # whose payload IS a file body under .content -- see the DEF_IDS read above.)
        if [ "$(printf '%s' "$FD_TEXT" | jq -r '.success' 2>/dev/null)" = "true" ]; then
          continue
        fi
        echo "::warning::Deferred-rule sweep: hub_force_delete_app(${rid}) did not report success: $(printf '%s' "$FD_TEXT" | jq -c '{success,error}' 2>/dev/null | head -c 200)"
      else
        echo "::warning::Deferred-rule sweep: hub_force_delete_app(${rid}) returned no parseable result (tool missing on the watchdog / endpoint down?)."
      fi
      def_fail=$((def_fail + 1))
    done <<< "$DEF_IDS"
    if [ "$def_fail" -eq 0 ]; then
      echo "Deferred-rule sweep: force-deleted ${def_n} native rule instance(s); clearing the deferred-id list."
      mcp_tool_call_text "hub_write_file (clear deferred list)" \
        "$(jq -nc --arg fn "$DEFERRED_FILE" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"hub_write_file",arguments:{fileName:$fn,content:"[]",confirm:true}}}')" >/dev/null 2>&1 \
        || echo "::warning::Deferred-rule sweep: could not clear ${DEFERRED_FILE} (the arm step truncates it next run; re-deleting already-gone ids is a harmless no-op)."
    else
      echo "::warning::Deferred-rule sweep: ${def_fail}/${def_n} force-delete(s) did NOT confirm -- KEEPING ${DEFERRED_FILE} so the post-restore --cleanup-only prefix sweep (and the next run) can still reap them. Not failing the restore (best-effort)."
    fi
  fi
else
  echo "::warning::Deferred-rule sweep: could not READ ${DEFERRED_FILE} after retries (transient transport, NOT necessarily deferral-off) -- relying on the post-restore --cleanup-only prefix sweep to reap any BAT_E2E_ rules."
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
echo "WATCHDOG_DISARMED runId=$GITHUB_RUN_ID (restore fired asynchronously; not polling -- the watchdog stamps the canonical-main marker on success, and the next run's full-repair install overwrites the apps regardless)."
exit 0
