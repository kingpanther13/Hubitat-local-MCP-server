#!/usr/bin/env bash
# Put canonical main back on the test hub after a run. The file keeps its historical name because
# hub-e2e.yml on main calls it by name, and that workflow file drives the scripts of every open PR.
#
# Watchdog v3 restores nothing on its own, so this step asks for it explicitly: release any hold
# the PR install left, purge the BAT_E2E_ fixtures in one hub-local sweep, then submit a deployment
# of main as it is NOW (main can move while a run is in flight). It is fire and forget: whether
# main comes back has no bearing on the PR, so this never fails the run and does not wait.
# `watchdog_v3.py wait-restore` is the bounded, equally non-fatal wait that follows it.
#
# With --cancelled it only releases the hold and purges: GitHub ends a cancelled job after about
# five minutes, which is less than a deployment takes, and the next run installs its own code.
#
# Env: MCP_URL, WATCHDOG_URL (secret WATCHDOG_MCP_URL), GITHUB_REPOSITORY, MAIN_SHA, RUNNER_TEMP
set -euo pipefail
: "${MCP_URL:?MCP_URL env var required (the MCP server under test)}"
: "${WATCHDOG_URL:?WATCHDOG_URL env var required (the watchdog endpoint, from secret WATCHDOG_MCP_URL)}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY env var required}"
exec python3 "$(dirname "$0")/watchdog_v3.py" restore-main "$@"
