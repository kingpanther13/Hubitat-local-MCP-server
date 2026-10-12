'use strict';
// Lane-gate decision: what should this event do about e2e? A pure function of (eventName, event
// payload, run attempt), called by the `lane-gate` job in .github/workflows/hub-e2e.yml and
// unit-tested by tests/lane_gate_test.js. It returns one of four verdicts:
//   'run'  -> run e2e (the gate step picks the lane from the live labels)
//   'noop' -> do nothing; the prior run's posted gate status stands
//   'skip' -> the PR carries e2e:skip: run NOTHING and post the required gate PENDING
//   'dependency-skip' -> verified dependency-only bot PR: no approval/hub; gate SUCCESS (not run)
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

function isDependencyBot(payload) {
  const author = payload.pull_request && payload.pull_request.user;
  return author && author.type === 'Bot' && ['dependabot[bot]', 'renovate[bot]'].includes(author.login);
}

// Only these dependency/CI inputs are exempt. Shipped sources, packageManifest.json,
// tools that generate the shipped apps, and unknown paths retain the normal E2E flow.
function isDependencyPath(path) {
  return typeof path === 'string' && (
    /^(?:(?:ci\/groovy2x-spock|ci\/groovy24-parse)\/)?(?:build|settings)\.gradle$/.test(path)
    || /^(?:gradlew(?:\.bat)?|gradle\/wrapper\/gradle-wrapper\.(?:jar|properties))$/.test(path)
    || /^\.github\/workflows\/[^/]+\.ya?ml$/.test(path)
    || ['.github/dependabot.yml', '.github/renovate.json', 'renovate.json'].includes(path)
  );
}

function isDependencyFile(file) {
  if (!file || !isDependencyPath(file.filename)
      || (file.previous_filename && !isDependencyPath(file.previous_filename))) return false;
  if (!file.filename.startsWith('.github/workflows/')) return true;
  // Workflow commands can alter generated/shipped code. Exempt only complete pin replacements,
  // never a missing/truncated patch, a new action, or a changed build/publish command.
  if (typeof file.patch !== 'string') return false;
  const lines = file.patch.split('\n').filter(line => /^[+-]/.test(line));
  if (lines.length === 0 || lines.length !== file.changes) return false;
  const pins = { '+': [], '-': [] };
  for (const line of lines) {
    const action = line.slice(1).match(/^\s*(?:-\s+)?uses:\s*(['"]?)([\w./-]+)@[\w./-]+\1(?:\s+#.*)?\s*$/);
    const validator = file.filename === '.github/workflows/dependency-updates-check.yml'
      && /^\s*RENOVATE_VERSION:\s*['"]?[0-9.]+['"]?\s*$/.test(line.slice(1));
    if (!action && !validator) return false;
    pins[line[0]].push(action ? `uses:${action[2]}` : 'RENOVATE_VERSION');
  }
  return JSON.stringify(pins['+'].sort()) === JSON.stringify(pins['-'].sort());
}

function decide(eventName, payload, runAttempt, changedFiles) {
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
  // Use the PR author, not the actor: a maintainer's label/re-run must not request approval
  // for the same dependency-only PR. An explicit workflow_dispatch still opts into E2E.
  if (isPR && isDependencyBot(payload) && Array.isArray(changedFiles) && changedFiles.length > 0
      && changedFiles.every(isDependencyFile)) return 'dependency-skip';
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

// CLI: read only GitHub metadata from the trusted base checkout, never execute PR code.
if (require.main === module) {
  const fs = require('fs');
  const { execFileSync } = require('child_process');
  const payload = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const attempt = Number(process.env.GITHUB_RUN_ATTEMPT || '1');
  let changedFiles;
  if (process.env.GITHUB_EVENT_NAME === 'pull_request_target' && isDependencyBot(payload)) {
    try {
      const pr = payload.pull_request;
      const endpoint = `repos/${process.env.GITHUB_REPOSITORY}/pulls/${pr.number}`;
      const api = args => JSON.parse(execFileSync('gh', ['api', ...args], {
        encoding: 'utf8', timeout: 60000, maxBuffer: 32 * 1024 * 1024,
      }));
      const pages = api(['--paginate', '--slurp', `${endpoint}/files?per_page=100`]);
      if (!Array.isArray(pages) || !pages.every(Array.isArray)) throw new Error('Invalid file-list response');
      const files = pages.flat();
      // Verify after pagination: a newer head or a truncated response cannot exempt this event's SHA.
      const current = api([endpoint]);
      if (!pr.head || !pr.head.sha || !current.head || current.head.sha !== pr.head.sha
          || files.length !== current.changed_files) throw new Error('PR head changed or file list incomplete');
      changedFiles = files;
    } catch (error) {
      console.warn('::warning::Could not verify dependency-only PR files; retaining normal E2E approval.');
    }
  }
  const verdict = decide(process.env.GITHUB_EVENT_NAME, payload, attempt, changedFiles);
  const run = verdict === 'run' ? 'true' : 'false';
  const skip = verdict === 'skip' ? 'true' : 'false';
  const dependencySkip = verdict === 'dependency-skip' ? 'true' : 'false';
  console.log(`lane-gate: event=${process.env.GITHUB_EVENT_NAME} action=${payload.action} label=${payload.label && payload.label.name} attempt=${attempt} -> ${verdict} (run=${run} skip=${skip} dependency_skip=${dependencySkip})`);
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `run=${run}\nskip=${skip}\ndependency_skip=${dependencySkip}\n`);
}
