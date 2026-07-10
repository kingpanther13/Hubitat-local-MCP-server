'use strict';
// Lane-gate decision: should this event re-run e2e? A pure function of (eventName, event payload,
// run attempt), called by the `lane-gate` job in .github/workflows/hub-e2e.yml and unit-tested by
// tests/lane_gate_test.js. A wrong decision either LOSES a full run (the required "Full e2e (runs
// with label)" gate never re-posts, blocking merge) or RE-RUNS when it shouldn't and double-books
// the single shared test hub -- so it gets a regression guard like the other .github/scripts logic.
//
// Re-run ONLY when a label flips the lane: the FIRST full-run label (focused->full) or the removal
// of the LAST one (full->focused); every non-label event runs. A redundant full-run label, or a
// non-lane label, is a no-op. The label set is normalized (event label forced IN for `labeled`, OUT
// for `unlabeled`) so the decision doesn't depend on whether the webhook's pull_request.labels
// already reflects this event.
//
// MANUAL RE-RUN OVERRIDE (attempt > 1): the no-op decision above is a pure function of the event
// payload, which is FROZEN at trigger time -- so re-running a skipped no-op label run re-derives
// skip, forever. That is a trap: the PR checks tab shows the NEWEST check run per name, so after a
// redundant-label event the visible "e2e (run)" is the skipped one, and its Re-run button re-fires
// the same frozen payload. A human clicking "Re-run all jobs" is an explicit request for a run,
// not a label webhook; honor it (GITHUB_RUN_ATTEMPT > 1 -> run; the gate step still picks the lane
// from the LIVE label list). This needs "Re-run all jobs" -- re-running only the skipped e2e job
// keeps the prior lane-gate outputs and still skips. Concurrency stays safe: a no-op label run's
// group was isolated-<run_id> at trigger time and a re-run keeps its run_id, so it can never
// cancel or evict another run, and the e2e job still queues FIFO in hub-e2e-serialized.
//
// This MUST stay in lockstep with the concurrency GROUP expression in hub-e2e.yml: its "shares the
// per-branch group" test keys on the same first-full-label set and must remain a strict SUBSET of
// this decision (so a shared run never cancels a run that then skips) -- the re-run override only
// WIDENS the decision, so the subset invariant holds. cancel-in-progress is `true`.
const FULL = ['release:patch', 'release:minor', 'release:major', 'e2e:full'];

function decide(eventName, payload, runAttempt) {
  payload = payload || {};
  // Manual re-run override: attempt > 1 means this run was manually re-run (EVERY re-run type
  // increments the counter; any re-run that re-executes this gate is an explicit ask for e2e that
  // the frozen event payload could otherwise never grant -- see header).
  if (Number(runAttempt) > 1) return true;
  const action = payload.action;
  // Non-label events (opened/synchronize/reopened/dispatch) always run.
  if (eventName !== 'pull_request_target' || (action !== 'labeled' && action !== 'unlabeled')) {
    return true;
  }
  const evLabel = payload.label && payload.label.name;
  let labels = ((payload.pull_request && payload.pull_request.labels) || []).map(l => l.name);
  if (action === 'labeled' && evLabel && !labels.includes(evLabel)) labels.push(evLabel);
  if (action === 'unlabeled') labels = labels.filter(l => l !== evLabel);
  const fullCount = labels.filter(l => FULL.includes(l)).length;
  // Lane flips only when this label is the FIRST full label (labeled -> count 1) or the LAST one
  // removed (unlabeled -> count 0). Otherwise the lane is unchanged -> no re-run.
  return FULL.includes(evLabel) && fullCount === (action === 'labeled' ? 1 : 0);
}

module.exports = { decide, FULL };

// CLI: read the triggering event, print the decision, and emit it as the `run` step output.
if (require.main === module) {
  const fs = require('fs');
  const payload = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const attempt = Number(process.env.GITHUB_RUN_ATTEMPT || '1');
  const run = decide(process.env.GITHUB_EVENT_NAME, payload, attempt) ? 'true' : 'false';
  console.log(`lane-gate: event=${process.env.GITHUB_EVENT_NAME} action=${payload.action} label=${payload.label && payload.label.name} attempt=${attempt} -> run=${run}`);
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `run=${run}\n`);
}
