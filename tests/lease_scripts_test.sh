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

echo
if [ "$fail" -eq 0 ]; then
  echo "lease scripts guard: PASS"
else
  echo "lease scripts guard: FAIL -- a response shape parsed unsafely (see above). The double-book bug is back."
  exit 1
fi
