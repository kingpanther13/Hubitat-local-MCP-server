#!/usr/bin/env bash
# Hub-less unit test for the self-deploy error-recovery extraction logic (issue #237).
#
# Guards against the jq `// empty` false-coercion bug that silently broke
# recover_self_deploy_error: jq's `//` operator treats a `false` boolean like null,
# so a REJECTED deploy (success:false -- the exact case to surface) read as empty,
# the `== "false"` check never matched, and the hub's verbatim compile error never
# reached the e2e logs. Spock can't catch this (it tests the Groovy, not the bash
# consumer), so this pins the correct extraction here. Needs no hub.
set -euo pipefail

fail=0
check() { # desc, expected, actual
  if [ "$2" = "$3" ]; then
    echo "ok: $1"
  else
    echo "FAIL: $1 -- expected [$2] got [$3]"; fail=1
  fi
}

# The exact expression recover_self_deploy_error (mcp_deploy_source.sh) uses to read
# the success flag. Must yield 'false'/'true' for booleans and '' for null/absent.
success_flag() { jq -r '.lastSelfDeploy.success | if type=="boolean" then tostring else "" end'; }

check "success:false -> 'false' (the rejected-deploy case)" "false" "$(printf '%s' '{"lastSelfDeploy":{"success":false}}' | success_flag)"
check "success:true  -> 'true'"                              "true"  "$(printf '%s' '{"lastSelfDeploy":{"success":true}}'  | success_flag)"
check "success:null  -> ''"                                  ""      "$(printf '%s' '{"lastSelfDeploy":{"success":null}}'  | success_flag)"
check "no lastSelfDeploy -> ''"                              ""      "$(printf '%s' '{}' | success_flag)"

# Regression marker: the OLD buggy form ('// empty') is DEMONSTRABLY wrong on false.
# If someone "tidies" the extraction back to this, the feature silently breaks -- this
# documents and proves why it must not return.
buggy="$(printf '%s' '{"lastSelfDeploy":{"success":false}}' | jq -r '.lastSelfDeploy.success // empty')"
check "buggy '// empty' returns '' for false (do NOT reintroduce)" "" "$buggy"

# The SAME extraction in deploy_app_via_watchdog (mcp_watchdog_lib.sh), which reads the
# confirmation poll's success flag. It carried the buggy form until 2026-09-06, so a rejected
# deploy timed out at 420s instead of printing the hub's error; pin it here too.
lib_flag() { jq -r '.lastSelfDeploy.success | if type=="boolean" then tostring else "" end'; }
check "watchdog-lib poll: success:false -> 'false'" "false" "$(printf '%s' '{"lastSelfDeploy":{"success":false}}' | lib_flag)"
check "watchdog-lib poll: success:true  -> 'true'"  "true"  "$(printf '%s' '{"lastSelfDeploy":{"success":true}}'  | lib_flag)"
LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mcp_watchdog_lib.sh"
if [ ! -f "$LIB" ]; then
  echo "FAIL: cannot find mcp_watchdog_lib.sh next to this test (looked at $LIB)"; fail=1
elif grep -q 'lastSelfDeploy.success // empty' "$LIB"; then
  echo "FAIL: mcp_watchdog_lib.sh reintroduced '// empty' on the success flag"; fail=1
else
  echo "ok: mcp_watchdog_lib.sh does not use '// empty' on the success flag"
fi

if [ "$fail" -eq 0 ]; then
  echo "ALL SELF-DEPLOY RECOVERY EXTRACTION TESTS PASSED"
else
  echo "::error::self-deploy recovery extraction tests FAILED -- the success-flag jq must map booleans explicitly, not use '// empty' (which coerces false to empty)."
  exit 1
fi
