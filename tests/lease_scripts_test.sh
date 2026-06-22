#!/usr/bin/env bash
# Regression guard for the e2e lease scripts' response parsing.
#
# The bug this locks down: a JSON-RPC ERROR envelope (e.g. -32603 the main app emits while its
# class is recompiled by another run's deploy) must NEVER read as "released". That false-empty
# let a run claim over a still-valid lease and double-booked the single shared test hub.
#
# It extracts the REAL get_lease_value out of lease_acquire.sh and runs it (re-extracted every
# run, so the test can't drift from the shipped parser), feeding each response shape through a
# stubbed network call. Pure shell + jq; no hub, no secrets.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../.github/scripts/lease_acquire.sh"
[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }

# Load the shipped get_lease_value (its closing brace is the only `}` at column 0 in the function).
eval "$(sed -n '/^get_lease_value()/,/^}/p' "$SRC")"
type get_lease_value >/dev/null 2>&1 || { echo "could not load get_lease_value from $SRC"; exit 1; }

fail=0
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
    printf '  ok    %-24s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-24s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}

held_text="$(jq -nc '{name:"_TEST_HUB_LEASED_BY",type:"string",value:(({by:"ci-run-X",until:1}|tojson))}')"
check "held lease"          "$(jq -nc --arg t "$held_text" '{result:{content:[{text:$t}]}}')"   HELD
check "released (value '')" "$(jq -nc '{result:{content:[{text:({value:""}|tojson)}]}}')"        RELEASED
check "-32603 recompile"    '{"error":{"code":-32603,"message":"Internal error"}}'               POLL
check "-32602 not found"    '{"error":{"code":-32602,"message":"Variable not found: X"}}'         RELEASED
check "-32602 other"        '{"error":{"code":-32602,"message":"Invalid params"}}'                POLL
check "non-JSON 504 body"   '<html>504 Gateway Timeout</html>'                                    POLL

# --- fifo_my_turn: GitHub-side FIFO ordering (the lowest in-progress run_number claims a free lease) ---
eval "$(sed -n '/^fifo_my_turn()/,/^}/p' "$SRC")"
type fifo_my_turn >/dev/null 2>&1 || { echo "could not load fifo_my_turn from $SRC"; exit 1; }
export GITHUB_REPOSITORY="owner/repo"
fifo_check() {  # fifo_check <name> <my_run_number> <stub-min-run_number> <TURN|WAIT>
  local name="$1" want="$4" got
  export GITHUB_RUN_NUMBER="$2"
  FIFO_MIN="$3"
  gh() { printf '%s' "$FIFO_MIN"; }     # stub `gh api ... --jq` -> the min in-progress run_number
  if fifo_my_turn; then got=TURN; else got=WAIT; fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-24s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-24s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}
echo
echo "fifo_my_turn (FIFO ordering):"
fifo_check "earliest in line"      100 100 TURN
fifo_check "earlier still running" 101 100 WAIT
fifo_check "api empty -> proceed"  100 ""  TURN

echo
if [ "$fail" -eq 0 ]; then
  echo "lease parse guard: PASS"
else
  echo "lease parse guard: FAIL -- a response shape parsed unsafely (see above). The double-book bug is back."
  exit 1
fi
