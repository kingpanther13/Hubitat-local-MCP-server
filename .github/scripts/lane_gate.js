'use strict';
// Lane-gate decision: what should this event do about e2e? A pure function of (eventName, event
// payload, run attempt), called by the `lane-gate` job in .github/workflows/hub-e2e.yml and
// unit-tested by tests/lane_gate_test.js. It returns one of three verdicts:
//   'run'  -> run e2e (the gate step picks the lane from the live labels)
//   'noop' -> do nothing; the prior run's posted gate status stands
//   'skip' -> the PR carries e2e:skip: run NOTHING and post the required gate PENDING
// A wrong decision either LOSES a full run (the required "Full e2e (runs with label)" gate never
// re-posts, blocking merge) or RE-RUNS when it shouldn't and double-books the single shared test
// hub -- so it gets a regression guard like the other .github/scripts logic.
//
// E2E:SKIP wins over everything else, including the manual re-run override below: while the label
// is on, EVERY pull_request_target event (push, reopen, any label toggle) is 'skip' -- nothing books
// the hub and the gate is re-posted pending so the PR can never merge on a stale status. Removing
// e2e:skip is a lane flip (skipped -> live) and re-runs in whatever lane the remaining labels select.
// workflow_dispatch is NOT subject to the label (an explicit maintainer ask always runs).
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
// keeps the prior lane-gate outputs and still skips. Concurrency stays safe: hub-e2e.yml routes
// EVERY re-run (github.run_attempt > 1) to the isolated per-run group, so a re-run can never
// cancel or evict another run, and the e2e job still queues FIFO in hub-e2e-serialized.
//
// This MUST stay in lockstep with the concurrency GROUP expression in hub-e2e.yml: its "shares the
// per-branch group" test keys on the same first-full-label set (plus the e2e:skip add) and must
// remain a strict SUBSET of this decision's non-'noop' set (so a shared run never cancels a run and
// then does nothing) -- the re-run override only WIDENS the decision, and the group expression's
// attempt==1 guard only SHRINKS the shared set, so the subset invariant holds. cancel-in-progress
// is `true`.
const FULL = ['release:patch', 'release:minor', 'release:major', 'e2e:full'];
const SKIP = 'e2e:skip';

function decide(eventName, payload, runAttempt) {
  payload = payload || {};
  const action = payload.action;
  const isPR = eventName === 'pull_request_target';
  const evLabel = payload.label && payload.label.name;
  // Normalize the label set: the event label is forced IN for `labeled` and OUT for `unlabeled`, so
  // the decision doesn't depend on whether the webhook's pull_request.labels already reflects it.
  let labels = ((payload.pull_request && payload.pull_request.labels) || []).map(l => l.name);
  if (action === 'labeled' && evLabel && !labels.includes(evLabel)) labels.push(evLabel);
  if (action === 'unlabeled') labels = labels.filter(l => l !== evLabel);
  // e2e:skip on the PR: nothing runs, the gate is re-posted pending. Checked BEFORE the re-run
  // override so a human re-running a skipped run cannot book the hub while the label is on (they
  // remove the label instead, which is itself the re-run). Non-PR events never carry PR labels.
  if (isPR && labels.includes(SKIP)) return 'skip';
  // Manual re-run override: attempt > 1 means this run was manually re-run (EVERY re-run type
  // increments the counter; any re-run that re-executes this gate is an explicit ask for e2e that
  // the frozen event payload could otherwise never grant -- see header).
  if (Number(runAttempt) > 1) return 'run';
  // Non-label events (opened/synchronize/reopened/dispatch) always run.
  if (!isPR || (action !== 'labeled' && action !== 'unlabeled')) return 'run';
  // Removing e2e:skip flips the lane skipped -> live: re-run in whatever lane the labels now select.
  if (action === 'unlabeled' && evLabel === SKIP) return 'run';
  const fullCount = labels.filter(l => FULL.includes(l)).length;
  // Lane flips only when this label is the FIRST full label (labeled -> count 1) or the LAST one
  // removed (unlabeled -> count 0). Otherwise the lane is unchanged -> no re-run.
  return FULL.includes(evLabel) && fullCount === (action === 'labeled' ? 1 : 0) ? 'run' : 'noop';
}

module.exports = { decide, FULL, SKIP };

// CLI: read the triggering event, print the verdict, and emit it as the `run` / `skip` step outputs.
if (require.main === module) {
  const fs = require('fs');
  const payload = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const attempt = Number(process.env.GITHUB_RUN_ATTEMPT || '1');
  const verdict = decide(process.env.GITHUB_EVENT_NAME, payload, attempt);
  const run = verdict === 'run' ? 'true' : 'false';
  const skip = verdict === 'skip' ? 'true' : 'false';
  console.log(`lane-gate: event=${process.env.GITHUB_EVENT_NAME} action=${payload.action} label=${payload.label && payload.label.name} attempt=${attempt} -> ${verdict} (run=${run} skip=${skip})`);
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `run=${run}\nskip=${skip}\n`);
}
