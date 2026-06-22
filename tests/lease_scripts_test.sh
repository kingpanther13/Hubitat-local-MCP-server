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

# --- fifo_my_turn: FIFO ordering by e2e-job START time (earliest in line claims a free lease) ---
eval "$(sed -n '/^fifo_my_turn()/,/^}/p' "$SRC")"
type fifo_my_turn >/dev/null 2>&1 || { echo "could not load fifo_my_turn from $SRC"; exit 1; }
export GITHUB_REPOSITORY="owner/repo"
declare -A STUB_TS
fifo_check() {  # fifo_check <name> <my_run_id> <TURN|WAIT>  (globals: STUB_IDS, STUB_TS[id]=e2e_started_at)
  local name="$1" want="$3" got rid
  export GITHUB_RUN_ID="$2"
  gh() {  # stub: emulate `gh api --jq` for the two endpoints fifo_my_turn calls
    case "$*" in
      *"workflows/hub-e2e.yml/runs"*) printf '%s\n' $STUB_IDS ;;
      *"/actions/runs/"*"/jobs"*) rid="$(printf '%s' "$*" | sed -E 's#.*/actions/runs/([0-9]+)/jobs.*#\1#')"; printf '%s' "${STUB_TS[$rid]:-}" ;;
      *) printf '' ;;
    esac
  }
  if fifo_my_turn; then got=TURN; else got=WAIT; fi
  if [ "$got" = "$want" ]; then
    printf '  ok    %-30s -> %s\n' "$name" "$got"
  else
    printf '  FAIL  %-30s -> got %s, want %s\n' "$name" "$got" "$want"
    fail=1
  fi
}
echo
echo "fifo_my_turn (FIFO by e2e-job start time):"
STUB_IDS="100 101"; STUB_TS[100]="2026-06-22T10:00:00Z"; STUB_TS[101]="2026-06-22T10:05:00Z"
fifo_check "earliest e2e-start (100)" 100 TURN
fifo_check "later e2e-start (101)"    101 WAIT
# approved fork: low run id 50 but its e2e job started LAST (approved late) -> earlier-started 99 goes first
STUB_IDS="50 99"; STUB_TS[50]="2026-06-22T12:00:00Z"; STUB_TS[99]="2026-06-22T11:00:00Z"
fifo_check "fork approved late (50)"  50 WAIT
fifo_check "earlier e2e-start (99)"   99 TURN
STUB_IDS=""
fifo_check "api empty -> proceed"     100 TURN

echo
if [ "$fail" -eq 0 ]; then
  echo "lease parse guard: PASS"
else
  echo "lease parse guard: FAIL -- a response shape parsed unsafely (see above). The double-book bug is back."
  exit 1
fi
