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
  }, attempt);
}

let fail = 0;
function check(name, got, want) {
  const ok = got === want;
  if (!ok) fail++;
  console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${name.padEnd(40)} -> ${got} (want ${want})`);
}

check('opened (non-label)',               run('pull_request_target', 'opened', [], null), 'run');
check('synchronize (commit)',             run('pull_request_target', 'synchronize', ['e2e:full'], null), 'run');
check('reopened',                         run('pull_request_target', 'reopened', [], null), 'run');
check('workflow_dispatch',                run('workflow_dispatch', '', [], null), 'run');
check('labeled FIRST full e2e:full',      run('pull_request_target', 'labeled', ['e2e:full'], 'e2e:full'), 'run');
check('labeled FIRST full release:patch', run('pull_request_target', 'labeled', ['release:patch'], 'release:patch'), 'run');
check('labeled REDUNDANT 2nd full',       run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch'), 'noop');
check('labeled REDUNDANT webhook-excl',   run('pull_request_target', 'labeled', ['e2e:full'], 'release:patch'), 'noop');
check('labeled non-lane (bug) focused',   run('pull_request_target', 'labeled', ['bug'], 'bug'), 'noop');
check('labeled non-lane (bug) on full',   run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug'), 'noop');
check('unlabeled LAST full -> demote',    run('pull_request_target', 'unlabeled', [], 'e2e:full'), 'run');
check('unlabeled LAST full webhook-incl', run('pull_request_target', 'unlabeled', ['e2e:full'], 'e2e:full'), 'run');
check('unlabeled one-of-many remains',    run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full'), 'noop');
check('unlabeled non-lane (bug)',         run('pull_request_target', 'unlabeled', ['e2e:full'], 'bug'), 'noop');

// Manual re-run override (attempt > 1): a human clicking "Re-run all jobs" on a skipped no-op
// label run is an explicit ask for e2e -- the frozen event payload must not re-derive skip.
// Both-ways: the attempt-1 rows above pin that the override does NOT leak into first runs.
check('RERUN attempt=2 redundant 2nd full',  run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 2), 'run');
check('RERUN attempt=2 non-lane (bug)',      run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug', 2), 'run');
check('RERUN attempt=3 unlabeled remains',   run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full', 3), 'run');
check('attempt=1 explicit stays no-op',      run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 1), 'noop');

// e2e:skip: while the label is on, EVERY PR event is 'skip' (nothing runs; gate re-posted pending) --
// including the re-run override and the full-label flips. Removing it is the lane flip back to live.
check('labeled e2e:skip (cancels in-flight)',   run('pull_request_target', 'labeled', ['e2e:skip'], 'e2e:skip'), 'skip');
check('labeled e2e:skip webhook-excl',          run('pull_request_target', 'labeled', ['e2e:full'], 'e2e:skip'), 'skip');
check('synchronize while skipped',              run('pull_request_target', 'synchronize', ['e2e:skip'], null), 'skip');
check('reopened while skipped',                 run('pull_request_target', 'reopened', ['e2e:skip', 'e2e:full'], null), 'skip');
check('labeled FIRST full while skipped',       run('pull_request_target', 'labeled', ['e2e:skip', 'e2e:full'], 'e2e:full'), 'skip');
check('labeled non-lane while skipped',         run('pull_request_target', 'labeled', ['e2e:skip', 'bug'], 'bug'), 'skip');
check('unlabeled LAST full while skipped',      run('pull_request_target', 'unlabeled', ['e2e:skip'], 'e2e:full'), 'skip');
check('RERUN attempt=2 while skipped',          run('pull_request_target', 'synchronize', ['e2e:skip'], null, 2), 'skip');
check('unlabeled e2e:skip -> live (focused)',   run('pull_request_target', 'unlabeled', [], 'e2e:skip'), 'run');
check('unlabeled e2e:skip webhook-incl',        run('pull_request_target', 'unlabeled', ['e2e:skip', 'e2e:full'], 'e2e:skip'), 'run');
check('workflow_dispatch ignores the label',    run('workflow_dispatch', '', ['e2e:skip'], null), 'run');

console.log(fail === 0 ? '\nlane-gate decision guard: PASS' : `\nlane-gate decision guard: FAIL (${fail})`);
process.exit(fail === 0 ? 0 : 1);
