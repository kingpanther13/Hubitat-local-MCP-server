#!/usr/bin/env bash
# Regression guard for the e2e lease scripts: acquire-side response parsing + release-side
# compare-and-clear. Two bug classes it locks down:
#   1. ACQUIRE false-empty -- a JSON-RPC ERROR envelope (e.g. -32603 the main app emits while its
#      class is recompiled by another run's deploy) must NEVER read as "released". That false-empty
#      let a run claim over a still-valid lease and double-booked the single shared test hub.
#   2. RELEASE over-clear -- lease_release.sh must blank _TEST_HUB_LEASED_BY ONLY when WE still hold
#      it. A degraded read or a lease now held by ANOTHER run must be left alone (else a slow/cancelled
#      run could wipe the new holder's live lease -- a milder replay of the same double-book).
#
# get_lease_value is extracted from lease_acquire.sh and run directly (re-extracted every run, so the
# test can't drift from the shipped parser); lease_release.sh is driven end-to-end with a curl stub
# on PATH. Pure shell + jq; no hub, no secrets.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../.github/scripts/lease_acquire.sh"
SRC_REL="$HERE/../.github/scripts/lease_release.sh"
[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }
[ -f "$SRC_REL" ] || { echo "missing $SRC_REL"; exit 1; }

# Load the shipped get_lease_value (its closing brace is the only `}` at column 0 in the function).
eval "$(sed -n '/^get_lease_value()/,/^}/p' "$SRC")"
type get_lease_value >/dev/null 2>&1 || { echo "could not load get_lease_value from $SRC"; exit 1; }

fail=0

# ---------- acquire-side parse guard ----------
check() {  # check <name> <canned-response> <HELD|RELEASED|POLL>
  local name="$1" want="$3" out got
  CANNED_RESP="$2"                              # global: read by the stub (avoids colliding with
  mcp_call() { printf '%s' "$CANNED_RESP"; }    # get_lease_value's own `local resp`)
  if out="$(get_lease_value 2>/dev/null)"; then
    [ -z "$out" ] && got=RELEASED || got=HELD
  else
    got=POLL
  fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-26s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-26s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}

echo "acquire parse (get_lease_value):"
held_text="$(jq -nc '{name:"_TEST_HUB_LEASED_BY",type:"string",value:(({by:"ci-run-X",until:1}|tojson))}')"
check "held lease"          "$(jq -nc --arg t "$held_text" '{result:{content:[{text:$t}]}}')"   HELD
check "released (value '')" "$(jq -nc '{result:{content:[{text:({value:""}|tojson)}]}}')"        RELEASED
check "-32603 recompile"    '{"error":{"code":-32603,"message":"Internal error"}}'               POLL
check "-32602 not found"    '{"error":{"code":-32602,"message":"Variable not found: X"}}'         RELEASED
check "-32602 other"        '{"error":{"code":-32602,"message":"Invalid params"}}'                POLL
check "non-JSON 504 body"   '<html>504 Gateway Timeout</html>'                                    POLL

# ---------- release-side compare-and-clear guard ----------
# Drive the REAL lease_release.sh end-to-end with a curl stub on PATH: the GET (hub_get_variable)
# returns a canned lease (or fails for __FAIL__), and a clear POST (hub_set_variable, value:"") is
# recorded. We assert WHO gets cleared. The held fixtures use the real wire shape -- {by,until} is a
# JSON STRING inside .value -- so the test exercises the same parse the script ships.
STUBDIR="$(mktemp -d)"
cat > "$STUBDIR/curl" <<'STUB'
#!/usr/bin/env bash
payload=""
while [ $# -gt 0 ]; do [ "$1" = "-d" ] && { payload="$2"; shift; }; shift; done
if printf '%s' "$payload" | grep -q hub_get_variable; then
  [ "$REL_CANNED_GET" = "__FAIL__" ] && exit 22   # simulate curl --fail (e.g. 504)
  printf '%s' "$REL_CANNED_GET"
elif printf '%s' "$payload" | grep -q hub_set_variable; then
  echo CLEAR >> "$REL_CLEAR_LOG"                  # record that a clear was attempted
  printf '{"result":{}}'
fi
exit 0
STUB
chmod +x "$STUBDIR/curl"

rel_check() {  # rel_check <name> <canned-get> <by-arg> <CLEAR|KEEP>
  local name="$1" want="$4" got
  REL_CLEAR_LOG="$(mktemp)"; export REL_CLEAR_LOG
  REL_CANNED_GET="$2"; export REL_CANNED_GET
  PATH="$STUBDIR:$PATH" MCP_URL="stub://x" bash "$SRC_REL" "$3" >/dev/null 2>&1
  [ -s "$REL_CLEAR_LOG" ] && got=CLEAR || got=KEEP
  rm -f "$REL_CLEAR_LOG"
  if [ "$got" = "$want" ]; then
    printf '  ok    %-26s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-26s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}

mk_held() { jq -nc --arg by "$1" '{result:{content:[{text:({value:(({by:$by,until:99}|tojson))}|tojson)}]}}'; }
rel_empty="$(jq -nc '{result:{content:[{text:({value:""}|tojson)}]}}')"

echo
echo "release compare-and-clear (lease_release.sh):"
rel_check "held by us -> clear"     "$(mk_held ci-run-7)"     ci-run-7  CLEAR
rel_check "held by other -> keep"   "$(mk_held ci-run-OTHER)" ci-run-7  KEEP
rel_check "already empty -> keep"   "$rel_empty"              ci-run-7  KEEP
rel_check "-32603 read -> keep"     '{"error":{"code":-32603,"message":"Internal error"}}' ci-run-7 KEEP
rel_check "504 read-fail -> keep"   '__FAIL__'                ci-run-7  KEEP
rm -rf "$STUBDIR"

# ---------- acquire-side dead-holder reclaim guard ----------
# holder_run_dead() gates a DESTRUCTIVE reclaim, so it must be FAIL-CLOSED: return 0 (reclaim) ONLY
# for a ci-run-<N> holder whose gh run status is exactly "completed". A live run (in_progress/queued),
# a human hold, an unparseable id, a missing token, or a gh API error must return 1 (keep waiting) --
# stealing a LIVE run's hub would double-book the single shared test hub (the same bug class above).
# Extracted from the shipped script (re-extracted each run, can't drift) and run with a gh stub.
eval "$(sed -n '/^holder_run_dead()/,/^}/p' "$SRC")"
type holder_run_dead >/dev/null 2>&1 || { echo "could not load holder_run_dead from $SRC"; exit 1; }

GHDIR="$(mktemp -d)"
cat > "$GHDIR/gh" <<'STUB'
#!/usr/bin/env bash
[ "${GH_CANNED:-}" = "__FAIL__" ] && exit 1   # simulate a gh API error (non-zero exit)
printf '%s' "${GH_CANNED:-}"                   # else echo the canned `gh run view ... --jq .status`
exit 0
STUB
chmod +x "$GHDIR/gh"

hrd_check() {  # hrd_check <name> <holder> <gh-status> <DEAD|ALIVE> [notoken]
  local name="$1" holder="$2" canned="$3" want="$4" tok="t" got
  [ "${5:-}" = "notoken" ] && tok=""
  if ( PATH="$GHDIR:$PATH" GH_CANNED="$canned" GH_TOKEN="$tok" GITHUB_TOKEN="$tok" \
       GITHUB_REPOSITORY="o/r" holder_run_dead "$holder" ) 2>/dev/null; then got=DEAD; else got=ALIVE; fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-30s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-30s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}

echo
echo "acquire dead-holder reclaim (holder_run_dead):"
hrd_check "ci-run completed -> reclaim" ci-run-7   completed   DEAD
hrd_check "ci-run in_progress -> wait"  ci-run-7   in_progress ALIVE
hrd_check "ci-run queued -> wait"       ci-run-7   queued      ALIVE
hrd_check "human hold -> wait"          human-hold completed   ALIVE
hrd_check "unparseable id -> wait"      ci-run-abc completed   ALIVE
hrd_check "gh API error -> wait"        ci-run-7   __FAIL__    ALIVE
hrd_check "no token -> wait"            ci-run-7   completed   ALIVE notoken
rm -rf "$GHDIR"

echo
if [ "$fail" -eq 0 ]; then
  echo "lease scripts guard: PASS"
else
  echo "lease scripts guard: FAIL -- a response shape parsed unsafely (see above). The double-book bug is back."
  exit 1
fi
