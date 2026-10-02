#!/usr/bin/env bash
# Prepare the test hub for a run. The file keeps its historical name because hub-e2e.yml on main
# calls it by name, and that workflow file drives the scripts of every open PR.
#
# Watchdog v3 has nothing to arm: it never restores on its own, so there is no flag and no
# deadline. This step confirms v3 answers, reports whether it can see the MCP endpoint, and
# releases any deployment hold an earlier run left behind (a cancelled or crashed run, or a hub
# restart mid-install). It takes no backup: a hub backup is a heavy operation the platform's
# load limiter punishes, and the restore source is GitHub, not the hub.
#
# Env: MCP_URL, WATCHDOG_URL (secret WATCHDOG_MCP_URL), RUNNER_TEMP
set -euo pipefail
: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (the watchdog endpoint, from secret WATCHDOG_MCP_URL)}"
exec python3 "$(dirname "$0")/watchdog_v3.py" prepare
