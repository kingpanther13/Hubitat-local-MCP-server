#!/usr/bin/env bash
# Acquire test hub lease for a CI run.
#
# Usage:  lease_acquire.sh <by-identifier>
# Env:    MCP_URL — full cloud OAuth URL with access_token
#
# Exits 0 on successful claim. Exits 1 if the lease is held by someone else
# (active and not us), or if the post-write race-check fails.
#
# Lease shape (JSON, written into Hubitat Hub Variable `_TEST_HUB_LEASED_BY`):
#   {"by":"<who>","since":<epoch_ms>,"until":<epoch_ms>}
# Empty string = released. See protocol in CLAUDE.md / issue #77 for context.

set -euo pipefail

BY="${1:?Usage: $0 <by-identifier>}"
: "${MCP_URL:?MCP_URL env var required (full cloud OAuth URL with access_token)}"

NOW_MS=$(($(date +%s%N) / 1000000))
LEASE_DURATION_MIN=30
EXPIRES_MS=$((NOW_MS + LEASE_DURATION_MIN * 60 * 1000))
VERIFY_SLEEP_S=2

mcp_call() {
  curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d "$1"
}

get_lease_value() {
  mcp_call '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"manage_hub_variables","arguments":{"tool":"get_variable","args":{"name":"_TEST_HUB_LEASED_BY"}}}}' \
    | jq -r '.result.content[0].text' \
    | jq -r '.value // ""'
}

set_lease_value() {
  # Arg is the JSON-stringified value (already double-quoted + escaped).
  local value_json="$1"
  mcp_call "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"manage_hub_variables\",\"arguments\":{\"tool\":\"set_variable\",\"args\":{\"name\":\"_TEST_HUB_LEASED_BY\",\"value\":${value_json}}}}}" >/dev/null
}

CURRENT="$(get_lease_value)"

if [ -n "$CURRENT" ]; then
  CURRENT_BY="$(echo "$CURRENT" | jq -r '.by // ""' 2>/dev/null || echo "")"
  CURRENT_UNTIL="$(echo "$CURRENT" | jq -r '.until // 0' 2>/dev/null || echo 0)"
  if [ "$CURRENT_UNTIL" -gt "$NOW_MS" ] && [ "$CURRENT_BY" != "$BY" ]; then
    echo "::error::Test hub leased by '$CURRENT_BY' until $(date -u -d "@$((CURRENT_UNTIL / 1000))" +%FT%TZ). Aborting."
    exit 1
  fi
fi

# Build the lease JSON, then JSON-stringify it for the set_variable arg.
CLAIM_JSON="$(jq -nc \
  --arg  by    "$BY" \
  --argjson since "$NOW_MS" \
  --argjson until "$EXPIRES_MS" \
  '{by: $by, since: $since, until: $until}')"
CLAIM_AS_STRING="$(printf '%s' "$CLAIM_JSON" | jq -Rs .)"

set_lease_value "$CLAIM_AS_STRING"

# Post-write race-check: re-read after a short delay. If "by" isn't us,
# someone else's claim landed last and stole the lease — abort.
sleep "$VERIFY_SLEEP_S"
AFTER="$(get_lease_value)"
AFTER_BY="$(echo "$AFTER" | jq -r '.by // ""' 2>/dev/null || echo "")"

if [ "$AFTER_BY" != "$BY" ]; then
  echo "::error::Lease stolen by '$AFTER_BY' before our verify check. Aborting."
  exit 1
fi

echo "Lease acquired: by=$BY, until=$(date -u -d "@$((EXPIRES_MS / 1000))" +%FT%TZ) (${LEASE_DURATION_MIN} min TTL)"
