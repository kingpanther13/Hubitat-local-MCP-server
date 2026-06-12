#!/usr/bin/env bash
# Watchdog self-update was REMOVED from e2e. The dead-man watchdog app is now managed out-of-band: the
# maintainer deploys e2e-deadman-watchdog-v2.groovy to the test hub via direct hub access, and e2e must
# never write to the watchdog app (it only drives the standing watchdog's endpoint to arm/install/restore).
#
# This file still exists ONLY because e2e runs under pull_request_target, where the WORKFLOW file comes
# from MAIN. Until this PR merges, pre-merge runs execute main's hub-e2e.yml, which still calls this
# script. Once merged, main's workflow no longer references it and the file can be deleted.
set -euo pipefail

echo "watchdog self-update removed -- no-op (the watchdog app is managed out-of-band, not by e2e)"
exit 0
