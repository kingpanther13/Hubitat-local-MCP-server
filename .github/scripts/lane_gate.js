'use strict';
// Lane-gate decision: what should this event do about e2e? A pure function of (eventName, event
// payload, run attempt), called by the `lane-gate` job in .github/workflows/hub-e2e.yml and
// unit-tested by tests/lane_gate_test.js. It returns one of six verdicts:
//   'run'     -> run the full suite (the e2e job re-checks the live labels before booking the hub)
//   'pending' -> no full-run label: book nothing and post the required gate PENDING
//   'not-required' -> the PR changes no hub-relevant file: book nothing; gate SUCCESS (not run)
//   'noop'    -> do nothing; the prior run's posted gate status stands
//   'skip'    -> the PR carries e2e:skip: run NOTHING and post the required gate PENDING
//   'dependency-skip' -> verified dependency-only bot PR: no approval/hub; gate SUCCESS (not run)
// A wrong decision either LOSES a full run (the required "Full e2e (runs with label)" gate never
// re-posts, blocking merge) or RE-RUNS when it shouldn't and double-books the single shared test
// hub -- so it gets a regression guard like the other .github/scripts logic.
//
// The shared hub runs only for a full-run label (release:patch|minor|major or e2e:full): a non-label
// PR event runs with one and is 'pending' without one. The FIRST full-run label runs; a redundant
// full-run label, a non-lane label, and removing the LAST full-run label are no-ops (the posted gate
// stands). The label set is normalized (event label forced IN for `labeled`, OUT for `unlabeled`) so
// the decision doesn't depend on whether the webhook's pull_request.labels already reflects this
// event. workflow_dispatch always runs.
//
// E2E:SKIP wins over everything else, including the manual re-run override below: while the label
// is on, EVERY pull_request_target event (push, reopen, any label toggle) is 'skip' -- nothing books
// the hub and the gate is re-posted pending so the PR can never merge on a stale status. Unlike
// 'pending', adding it also cancels the in-flight run. Removing it runs when a full-run label remains
// and is 'pending' otherwise.
//
// MANUAL RE-RUN OVERRIDE (attempt > 1): the no-op decision above is a pure function of the event
// payload, which is FROZEN at trigger time -- so re-running a skipped no-op label run re-derives
// skip, forever. That is a trap: the PR checks tab shows the NEWEST check run per name, so after a
// redundant-label event the visible "e2e (run)" is the skipped one, and its Re-run button re-fires
// the same frozen payload. A human clicking "Re-run all jobs" is an explicit request for a run,
// not a label webhook; honor it when the PR carries a full-run label (GITHUB_RUN_ATTEMPT > 1 -> run,
// else pending). This needs "Re-run all jobs" -- re-running only the skipped e2e job keeps the
// prior lane-gate outputs and still skips. Concurrency stays safe: hub-e2e.yml routes
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

// The one source of what each hub lane must exercise: `both`, plus `physical` (hub-e2e.yml) or
// `ephemeral` (hub-e2e-ephemeral*.yml). A rule ending in "/" matches everything under it.
const RELEVANT = JSON.parse(require('fs').readFileSync(require('path').join(__dirname, 'hub_relevant_paths.json'), 'utf8'));

function isHubRelevantPath(path, lane = 'physical') {
  return typeof path !== 'string'
    || [...RELEVANT.both, ...RELEVANT[lane]].some(rule => (rule.endsWith('/') ? path.startsWith(rule) : path === rule));
}

// Fails safe: an unread or empty file list, or an entry without a name, counts as relevant.
function touchesLane(changedFiles, lane) {
  return !Array.isArray(changedFiles) || changedFiles.length === 0
    || changedFiles.some(file => !file || isHubRelevantPath(file.filename, lane)
      || (file.previous_filename && isHubRelevantPath(file.previous_filename, lane)));
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
  // remove the label instead). Non-PR events never carry PR labels.
  if (isPR && labels.includes(SKIP)) return 'skip';
  // Use the PR author, not the actor: a maintainer's label/re-run must not request approval
  // for the same dependency-only PR. An explicit workflow_dispatch still opts into E2E.
  if (isPR && isDependencyBot(payload) && Array.isArray(changedFiles) && changedFiles.length > 0
      && changedFiles.every(file => file && isDependencyPath(file.filename)
        && (!file.previous_filename || isDependencyPath(file.previous_filename)))) return 'dependency-skip';
  if (!isPR) return 'run';
  const fullCount = labels.filter(l => FULL.includes(l)).length;
  let verdict;
  // Manual re-run override: attempt > 1 means this run was manually re-run (EVERY re-run type
  // increments the counter; any re-run that re-executes this gate is an explicit ask for e2e that
  // the frozen event payload could otherwise never grant -- see header). Non-label events
  // (opened/synchronize/reopened) and removing e2e:skip follow the labels the same way.
  if (Number(runAttempt) > 1 || (action !== 'labeled' && action !== 'unlabeled')
      || (action === 'unlabeled' && evLabel === SKIP)) verdict = fullCount > 0 ? 'run' : 'pending';
  // Only the FIRST full-run label runs; any other label event leaves the posted gate alone.
  else verdict = action === 'labeled' && FULL.includes(evLabel) && fullCount === 1 ? 'run' : 'noop';
  return verdict !== 'noop' && !touchesLane(changedFiles, 'physical') ? 'not-required' : verdict;
}

module.exports = { decide, isHubRelevantPath, touchesLane, FULL, SKIP };

// CLI: read only GitHub metadata from the trusted base checkout, never execute PR code.
if (require.main === module) {
  const fs = require('fs');
  const { execFileSync } = require('child_process');
  const payload = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const attempt = Number(process.env.GITHUB_RUN_ATTEMPT || '1');
  let changedFiles;
  if (process.env.GITHUB_EVENT_NAME === 'pull_request_target') {
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
      // That event's own run judges the newer head.
      const current = api([endpoint]);
      if (!pr.head || !pr.head.sha || !current.head || current.head.sha !== pr.head.sha
          || files.length !== current.changed_files) throw new Error('PR head changed or file list incomplete');
      changedFiles = files;
    } catch (error) {
      console.warn('::warning::Could not verify the PR files; treating the PR as hub-relevant and not dependency-only.');
    }
  }
  const verdict = decide(process.env.GITHUB_EVENT_NAME, payload, attempt, changedFiles);
  const outputs = { run: 'run', pending: 'pending', not_required: 'not-required', skip: 'skip', dependency_skip: 'dependency-skip' };
  const lines = Object.entries(outputs).map(([name, value]) => `${name}=${verdict === value}`);
  // hub-e2e-ephemeral-request.yml gates its lane on this.
  lines.push(`ephemeral_relevant=${touchesLane(changedFiles, 'ephemeral')}`);
  console.log(`lane-gate: event=${process.env.GITHUB_EVENT_NAME} action=${payload.action} label=${payload.label && payload.label.name} attempt=${attempt} -> ${verdict} (${lines.join(' ')})`);
  fs.appendFileSync(process.env.GITHUB_OUTPUT, `${lines.join('\n')}\n`);
}
