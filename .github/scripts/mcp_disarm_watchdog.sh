#!/usr/bin/env bash
# Put main back on the test hub after a run: `watchdog_v3.py restore-main`, fire and forget
# (`--cancelled` deploys nothing). Flow: AGENTS.md, e2e architecture.
# The file keeps its historical name because hub-e2e.yml on main calls every open PR's scripts by name.
#
# Env: MCP_URL, WATCHDOG_URL (secret WATCHDOG_MCP_URL), GITHUB_REPOSITORY, MAIN_SHA, RUNNER_TEMP
set -euo pipefail
: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (the watchdog endpoint, from secret WATCHDOG_MCP_URL)}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY env var required}"
exec python3 "$(dirname "$0")/watchdog_v3.py" restore-main "$@"
