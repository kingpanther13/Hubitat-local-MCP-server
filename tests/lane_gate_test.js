#!/usr/bin/env node
// Regression guard for the lane-gate decision (.github/scripts/lane_gate.js) that the `lane-gate`
// job in hub-e2e.yml runs. A wrong decision either LOSES a full run (the required "Full e2e (runs
// with label)" gate never re-posts, blocking merge) or RE-RUNS when it shouldn't and double-books
// the single shared test hub. Pure node; no hub, no secrets.
//
// NOTE: this covers the lane_gate.js `run` decision. The cancel-in-progress GitHub-expression in
// hub-e2e.yml's concurrency block is the OTHER half and MUST stay a strict SUBSET of this decision;
// it's a GitHub expression (can't be eval'd locally), so it's guarded by the inline comment + manual
// cross-check, not by this test.
'use strict';
const { decide } = require('../.github/scripts/lane_gate.js');

function run(eventName, action, prLabels, evLabel, attempt) {
  return decide(eventName, {
    action,
    label: evLabel ? { name: evLabel } : null,
    pull_request: { labels: (prLabels || []).map(n => ({ name: n })) },
  }, attempt) ? 'true' : 'false';
}

let fail = 0;
function check(name, got, want) {
  const ok = got === want;
  if (!ok) fail++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name.padEnd(40)} -> ${got} (want ${want})`);
}

check('opened (non-label)',               run('pull_request_target', 'opened', [], null), 'true');
check('synchronize (commit)',             run('pull_request_target', 'synchronize', ['e2e:full'], null), 'true');
check('reopened',                         run('pull_request_target', 'reopened', [], null), 'true');
check('workflow_dispatch',                run('workflow_dispatch', '', [], null), 'true');
check('labeled FIRST full e2e:full',      run('pull_request_target', 'labeled', ['e2e:full'], 'e2e:full'), 'true');
check('labeled FIRST full release:patch', run('pull_request_target', 'labeled', ['release:patch'], 'release:patch'), 'true');
check('labeled REDUNDANT 2nd full',       run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch'), 'false');
check('labeled REDUNDANT webhook-excl',   run('pull_request_target', 'labeled', ['e2e:full'], 'release:patch'), 'false');
check('labeled non-lane (bug) focused',   run('pull_request_target', 'labeled', ['bug'], 'bug'), 'false');
check('labeled non-lane (bug) on full',   run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug'), 'false');
check('unlabeled LAST full -> demote',    run('pull_request_target', 'unlabeled', [], 'e2e:full'), 'true');
check('unlabeled LAST full webhook-incl', run('pull_request_target', 'unlabeled', ['e2e:full'], 'e2e:full'), 'true');
check('unlabeled one-of-many remains',    run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full'), 'false');
check('unlabeled non-lane (bug)',         run('pull_request_target', 'unlabeled', ['e2e:full'], 'bug'), 'false');

// Manual re-run override (attempt > 1): a human clicking "Re-run all jobs" on a skipped no-op
// label run is an explicit ask for e2e -- the frozen event payload must not re-derive skip.
// Both-ways: the attempt-1 rows above pin that the override does NOT leak into first runs.
check('RERUN attempt=2 redundant 2nd full',  run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 2), 'true');
check('RERUN attempt=2 non-lane (bug)',      run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug', 2), 'true');
check('RERUN attempt=3 unlabeled remains',   run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full', 3), 'true');
check('attempt=1 explicit stays no-op',      run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 1), 'false');

console.log(fail === 0 ? '\nlane-gate decision guard: PASS' : `\nlane-gate decision guard: FAIL (${fail})`);
process.exit(fail === 0 ? 0 : 1);
