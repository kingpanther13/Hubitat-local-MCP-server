'use strict';
// Lane-gate decision: should this event re-run e2e? A pure function of (eventName, event payload),
// called by the `lane-gate` job in .github/workflows/hub-e2e.yml and unit-tested by
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
// This MUST stay in lockstep with the concurrency GROUP expression in hub-e2e.yml: its "shares the
// per-branch group" test keys on the same first-full-label set and must remain a strict SUBSET of
// this decision (so a shared run never cancels a run that then skips). cancel-in-progress is `true`.
const FULL = ['release:patch', 'release:minor', 'release:major', 'e2e:full'];

function decide(eventName, payload) {
  payload = payload || {};
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
  const run = decide(process.env.GITHUB_EVENT_NAME, payload) ? 'true' : 'false';
  console.log(`lane-gate: event=${process.env.GITHUB_EVENT_NAME} action=${payload.action} label=${payload.label && payload.label.name} -> run=${run}`);
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `run=${run}\n`);
}
