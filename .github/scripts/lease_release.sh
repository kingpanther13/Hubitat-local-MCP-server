#!/usr/bin/env bash
# Release test hub lease.
#
# Usage:  lease_release.sh [<by-identifier>]
# Env:    MCP_URL — full cloud OAuth URL with access_token.
#
# With a <by-identifier> (the same "ci-run-<run_id>" the acquire used) this does a
# COMPARE-AND-CLEAR: it reads the current holder and only blanks the variable if WE still
# hold it (or it is already empty). A run must NEVER clear a DIFFERENT run's live lease --
# e.g. a slow or cancelled run releasing after another run has legitimately claimed would
# otherwise blank the new holder's live value. A degraded/error read (`jq -e`) falls to
# "unreadable" -> leave it alone; the 30-min
# TTL bounds it. With NO argument (a base-workflow call that predates this arg) it falls back
# to the old UNCONDITIONAL clear, so a pull_request_target run executing main's workflow file
# still releases its own lease.
#
# Always exits 0 even if the call fails — the lease has a 30-min TTL safety net, and this
# script runs in `if: always()` steps where a non-zero exit would mask the real test failure.

set -uo pipefail

: "${MCP_URL:?MCP_URL env var required}"
BY="${1:-}"

clear_lease() {
  if curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
      -H "Content-Type: application/json" \
      -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"hub_manage_variables","arguments":{"tool":"hub_set_variable","args":{"name":"_TEST_HUB_LEASED_BY","value":""}}}}' \
      >/dev/null; then
    echo "Lease released."
  else
    echo "::warning::Lease release failed — relying on 30-min TTL to clear it."
  fi
}

# Legacy / no-identity call (base-workflow before the arg is wired in): unconditional clear.
if [ -z "$BY" ]; then
  clear_lease
  exit 0
fi

# Compare-and-clear. Read the current lease value defensively; `jq -e` makes an error
# envelope or any 200 lacking .result.content fall to "__unreadable__" (never a false-empty).
LEASE="$(curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"hub_manage_variables","arguments":{"tool":"hub_get_variable","args":{"name":"_TEST_HUB_LEASED_BY"}}}}' 2>/dev/null \
  | jq -e -r '.result.content[0].text' 2>/dev/null | jq -e -r '.value // empty' 2>/dev/null)" || LEASE="__unreadable__"

if [ "$LEASE" = "__unreadable__" ]; then
  echo "::warning::Could not read the lease before release; NOT clearing it (the 30-min TTL will bound it)."
elif [ -z "$LEASE" ]; then
  echo "Lease already empty; nothing to release."
else
  # read-then-clear is not atomic (the MCP set_variable surface has no compare-and-swap). If our
  # TTL lapsed between this read and the clear, another run could reclaim and our clear would
  # blank ITS fresh lease -- a milder replay of the bug this fixes. The window is ~2 back-to-back
  # POSTs and the 30-min TTL bounds the worst case; fully closing it needs hub-side CAS.
  HOLDER="$(printf '%s' "$LEASE" | jq -r '.by // ""' 2>/dev/null || echo "")"
  if [ "$HOLDER" = "$BY" ]; then
    clear_lease
  else
    echo "::warning::Lease now held by '$HOLDER', not us ('$BY') — NOT clearing it (another run owns it; its TTL bounds it)."
  fi
fi
