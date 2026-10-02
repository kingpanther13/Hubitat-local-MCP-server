#!/usr/bin/env bash
# Prepare the test hub for a run: `watchdog_v3.py prepare` (flow: AGENTS.md, e2e architecture).
# The file keeps its historical name because hub-e2e.yml on main calls every open PR's scripts by name.
#
# Env: MCP_URL, WATCHDOG_URL (secret WATCHDOG_MCP_URL), RUNNER_TEMP
set -euo pipefail
: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (the watchdog endpoint, from secret WATCHDOG_MCP_URL)}"
exec python3 "$(dirname "$0")/watchdog_v3.py" prepare
