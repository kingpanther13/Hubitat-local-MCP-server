#!/usr/bin/env bash
# Release test hub lease (write empty string to `_TEST_HUB_LEASED_BY`).
#
# Env: MCP_URL — full cloud OAuth URL with access_token.
#
# Always exits 0 even if the release call fails — the lease has a 30-min
# TTL safety net, and this script runs in `if: always()` workflow steps
# where a non-zero exit would mask the real test failure.

set -uo pipefail

: "${MCP_URL:?MCP_URL env var required}"

if curl -sS --fail --max-time 30 -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"manage_hub_variables","arguments":{"tool":"set_variable","args":{"name":"_TEST_HUB_LEASED_BY","value":""}}}}' \
    >/dev/null; then
  echo "Lease released."
else
  echo "::warning::Lease release failed — relying on 30-min TTL to clear it."
fi
