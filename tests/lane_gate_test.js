#!/usr/bin/env node
// Regression guard for the lane-gate decision (.github/scripts/lane_gate.js) that the `lane-gate`
// job in hub-e2e.yml runs. A wrong decision either LOSES a full run (the required "Full e2e (runs
// with label)" gate never re-posts, blocking merge) or RE-RUNS when it shouldn't and double-books
// the single shared test hub. Pure node; no hub, no secrets.
//
// NOTE: this covers the lane_gate.js decision. The cancel-in-progress GitHub-expression in
// hub-e2e.yml's concurrency block is the OTHER half and MUST stay a strict SUBSET of this decision;
// it's a GitHub expression (can't be eval'd locally), so it's guarded by the inline comment + manual
// cross-check, not by this test.
'use strict';
const { decide, touchesLane } = require('../.github/scripts/lane_gate.js');

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

check('opened, no label',                 run('pull_request_target', 'opened', [], null), 'pending');
check('opened with full label',           run('pull_request_target', 'opened', ['release:minor'], null), 'run');
check('synchronize (commit)',             run('pull_request_target', 'synchronize', ['e2e:full'], null), 'run');
check('synchronize, no full label',       run('pull_request_target', 'synchronize', ['bug'], null), 'pending');
check('reopened, no label',               run('pull_request_target', 'reopened', [], null), 'pending');
check('workflow_dispatch',                run('workflow_dispatch', '', [], null), 'run');
check('labeled FIRST full e2e:full',      run('pull_request_target', 'labeled', ['e2e:full'], 'e2e:full'), 'run');
check('labeled FIRST full release:patch', run('pull_request_target', 'labeled', ['release:patch'], 'release:patch'), 'run');
check('labeled FIRST full webhook-excl',  run('pull_request_target', 'labeled', [], 'release:major'), 'run');
check('labeled REDUNDANT 2nd full',       run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch'), 'noop');
check('labeled REDUNDANT webhook-excl',   run('pull_request_target', 'labeled', ['e2e:full'], 'release:patch'), 'noop');
check('labeled non-lane (bug), no full',  run('pull_request_target', 'labeled', ['bug'], 'bug'), 'noop');
check('labeled non-lane (bug) on full',   run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug'), 'noop');
check('unlabeled LAST full -> nothing',   run('pull_request_target', 'unlabeled', [], 'e2e:full'), 'noop');
check('unlabeled LAST full webhook-incl', run('pull_request_target', 'unlabeled', ['e2e:full'], 'e2e:full'), 'noop');
check('unlabeled one-of-many remains',    run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full'), 'noop');
check('unlabeled non-lane (bug)',         run('pull_request_target', 'unlabeled', ['e2e:full'], 'bug'), 'noop');

// Manual re-run override (attempt > 1): a human clicking "Re-run all jobs" on a skipped no-op
// label run is an explicit ask for e2e -- the frozen event payload must not re-derive skip. It
// still books the hub only with a full-run label. Both-ways: the attempt-1 rows above pin that the
// override does NOT leak into first runs.
check('RERUN attempt=2 redundant 2nd full',  run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 2), 'run');
check('RERUN attempt=2 non-lane (bug)',      run('pull_request_target', 'labeled', ['e2e:full', 'bug'], 'bug', 2), 'run');
check('RERUN attempt=3 unlabeled remains',   run('pull_request_target', 'unlabeled', ['release:patch'], 'e2e:full', 3), 'run');
check('RERUN attempt=2 without full label',  run('pull_request_target', 'unlabeled', [], 'e2e:full', 2), 'pending');
check('RERUN attempt=2 opened, no label',    run('pull_request_target', 'opened', ['bug'], null, 2), 'pending');
check('attempt=1 explicit stays no-op',      run('pull_request_target', 'labeled', ['e2e:full', 'release:patch'], 'release:patch', 1), 'noop');

// e2e:skip: while the label is on, EVERY PR event is 'skip' (nothing runs; gate re-posted pending) --
// including the re-run override and the full-label flips. Removing it runs only with a full-run label.
check('labeled e2e:skip (cancels in-flight)',   run('pull_request_target', 'labeled', ['e2e:skip'], 'e2e:skip'), 'skip');
check('labeled e2e:skip webhook-excl',          run('pull_request_target', 'labeled', ['e2e:full'], 'e2e:skip'), 'skip');
check('synchronize while skipped',              run('pull_request_target', 'synchronize', ['e2e:skip'], null), 'skip');
check('reopened while skipped',                 run('pull_request_target', 'reopened', ['e2e:skip', 'e2e:full'], null), 'skip');
check('labeled FIRST full while skipped',       run('pull_request_target', 'labeled', ['e2e:skip', 'e2e:full'], 'e2e:full'), 'skip');
check('labeled non-lane while skipped',         run('pull_request_target', 'labeled', ['e2e:skip', 'bug'], 'bug'), 'skip');
check('unlabeled LAST full while skipped',      run('pull_request_target', 'unlabeled', ['e2e:skip'], 'e2e:full'), 'skip');
check('RERUN attempt=2 while skipped',          run('pull_request_target', 'synchronize', ['e2e:skip'], null, 2), 'skip');
check('unlabeled e2e:skip, no full label',      run('pull_request_target', 'unlabeled', [], 'e2e:skip'), 'pending');
check('unlabeled e2e:skip webhook-incl, full',  run('pull_request_target', 'unlabeled', ['e2e:skip', 'e2e:full'], 'e2e:skip'), 'run');
check('workflow_dispatch ignores the label',    run('workflow_dispatch', '', ['e2e:skip'], null), 'run');

// A PR that changes no hub-relevant file needs no hub, labeled or not; an unread list or an
// unnamed entry counts as relevant.
function filesDecision(files, action, prLabels, evLabel) {
  return decide('pull_request_target', {
    action,
    label: evLabel ? { name: evLabel } : null,
    pull_request: { labels: prLabels.map(n => ({ name: n })) },
  }, 1, files);
}
const docsOnly = [{ filename: 'README.md' }, { filename: 'docs/testing.md' }, { filename: '.github/workflows/sandbox-lint.yml' }];
check('docs-only, no label',             filesDecision(docsOnly, 'opened', []), 'not-required');
check('docs-only, FIRST full label',     filesDecision(docsOnly, 'labeled', ['e2e:full'], 'e2e:full'), 'not-required');
check('docs-only, redundant label',      filesDecision(docsOnly, 'labeled', ['e2e:full', 'bug'], 'bug'), 'noop');
check('docs-only, e2e:skip',             filesDecision(docsOnly, 'synchronize', ['e2e:skip']), 'skip');
check('docs-only dispatch',              decide('workflow_dispatch', {}, 1, docsOnly), 'run');
for (const filename of ['libraries/mcp-rooms-lib.groovy', 'hubitat-mcp-server.groovy', 'tests/e2e_test.py',
  '.github/workflows/hub-e2e.yml', '.github/scripts/watchdog_v3.py', 'packageManifest.json', 'tools/groovy_lex.py']) {
  check(`relevant ${filename}`,          filesDecision([...docsOnly, { filename }], 'opened', []), 'pending');
}
check('relevant, full label',            filesDecision([{ filename: 'libraries/x.groovy' }], 'opened', ['e2e:full']), 'run');
check('renamed out of libraries/',       filesDecision([{ filename: 'docs/x.groovy', previous_filename: 'libraries/x.groovy' }], 'opened', []), 'pending');
check('unnamed entry',                   filesDecision([{}], 'opened', []), 'pending');
check('empty file list',                 filesDecision([], 'opened', []), 'pending');

// Both lanes' relevance comes from hub_relevant_paths.json: every rule is relevant to its own lane,
// `both` rules to both, and a lane-specific rule never leaks into the other lane.
const RULES = require('../.github/scripts/hub_relevant_paths.json');
const sample = rule => (rule.endsWith('/') ? `${rule}nested/x.groovy` : rule);
for (const [group, rules] of Object.entries(RULES)) {
  for (const rule of rules) {
    const files = [{ filename: sample(rule) }];
    check(`physical ${group} ${rule}`,  String(touchesLane(files, 'physical')),  String(group !== 'ephemeral'));
    check(`ephemeral ${group} ${rule}`, String(touchesLane(files, 'ephemeral')), String(group !== 'physical'));
    check(`verdict ${group} ${rule}`,   filesDecision(files, 'opened', []), group === 'ephemeral' ? 'not-required' : 'pending');
  }
}
for (const lane of ['physical', 'ephemeral']) {
  check(`${lane} unlisted path`,  String(touchesLane([{ filename: 'docs/testing.md' }], lane)), 'false');
  check(`${lane} unread list`,    String(touchesLane(undefined, lane)), 'true');
  check(`${lane} empty list`,     String(touchesLane([], lane)), 'true');
  check(`${lane} unnamed entry`,  String(touchesLane([{}], lane)), 'true');
  check(`${lane} renamed from listed`, String(touchesLane([{ filename: 'docs/x', previous_filename: 'hubitat-mcp-server.groovy' }], lane)), 'true');
}

// Bot identity alone must never exempt shipped code or an unverified file list.
function botDecision(login, files, options = {}) {
  return decide(options.event || 'pull_request_target', {
    action: options.action || 'synchronize',
    sender: { login: options.sender || 'kingpanther13' },
    label: options.label ? { name: options.label } : null,
    pull_request: {
      user: { login, type: options.type || 'Bot' },
      labels: (options.labels || []).map(name => ({ name })),
    },
  }, options.attempt || 1, files);
}

const dependencyFiles = [
  { filename: 'build.gradle' },
  { filename: 'ci/groovy2x-spock/build.gradle' },
  { filename: 'ci/groovy24-parse/build.gradle' },
  { filename: 'gradle/wrapper/gradle-wrapper.properties' },
  { filename: '.github/workflows/hub-e2e.yml', changes: 2, patch: '@@ -1 +1 @@\n-      - uses: actions/checkout@v6\n+      - uses: actions/checkout@v7' },
  { filename: '.github/workflows/dependency-updates-check.yml', changes: 2, patch: "@@ -1 +1 @@\n-      RENOVATE_VERSION: '44.151.5'\n+      RENOVATE_VERSION: '44.152.0'" },
];
for (const bot of ['renovate[bot]', 'dependabot[bot]']) {
  check(`${bot} dependencies only`, botDecision(bot, dependencyFiles), 'dependency-skip');
  check(`${bot} human rerun`, botDecision(bot, dependencyFiles, { attempt: 2 }), 'dependency-skip');
  check(`${bot} full label`, botDecision(bot, dependencyFiles, {
    action: 'labeled', label: 'e2e:full', labels: ['e2e:full'],
  }), 'dependency-skip');
  check(`${bot} explicit hold`, botDecision(bot, dependencyFiles, { labels: ['e2e:skip'] }), 'skip');
  check(`${bot} explicit dispatch`, botDecision(bot, dependencyFiles, { event: 'workflow_dispatch' }), 'run');
  for (const filename of [
    'hubitat-mcp-server.groovy', 'hubitat-mcp-rule.groovy', 'libraries/mcp-code-management-lib.groovy',
    'packageManifest.json', 'tools/build-release-app.py', 'tools/strip_source.py',
    'tools/groovy_lex.py', 'e2e-deadman-watchdog-v3-1.groovy', 'unknown-file',
  ]) {
    check(`${bot} mixed ${filename}`, botDecision(bot, [...dependencyFiles, { filename }]), 'pending');
  }
  check(`${bot} renamed shipped file`, botDecision(bot, [{
    filename: 'build.gradle', previous_filename: 'hubitat-mcp-server.groovy',
  }]), 'pending');
  check(`${bot} workflow-only edit`, botDecision(bot, [{
    filename: '.github/workflows/hub-e2e.yml',
  }]), 'dependency-skip');
  for (const files of [undefined, null, [], [{}]]) {
    check(`${bot} missing/invalid files`, botDecision(bot, files), 'pending');
  }
  // Not a dependency path, so no dependency skip; not hub-relevant either.
  check(`${bot} outside both allowlists`, botDecision(bot, [{ filename: '../build.gradle' }]), 'not-required');
}
check('human dependency PR still normal', botDecision('kingpanther13', dependencyFiles, { type: 'User' }), 'pending');
check('human dependency PR with full label', botDecision('kingpanther13', dependencyFiles, {
  type: 'User', labels: ['e2e:full'],
}), 'run');
check('other bot still normal', botDecision('some-other[bot]', dependencyFiles), 'pending');
check('bot login without bot type', botDecision('renovate[bot]', dependencyFiles, { type: 'User' }), 'pending');

console.log(fail === 0 ? '\nlane-gate decision guard: PASS' : `\nlane-gate decision guard: FAIL (${fail})`);
process.exit(fail === 0 ? 0 : 1);
