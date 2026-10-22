# Ephemeral hub E2E

The ephemeral lane runs the full `tests/e2e_test.py` registry and then
`tests/sdk_conformance_test.py` against a disposable test hub. An external workflow boots that hub
for one run and ends it when the run completes. The lane runs on every pull request, forks
included, on pushes to `main`, and on manual dispatch of a branch. It has no approval environment
and no label gate of its own. `e2e:skip` holds only the physical-hub lane. Docs-only PRs and
verified dependency-only bot PRs skip it.

## Two hub kinds

Every run tests two kinds of hub side by side, one matrix job per kind:

- `os` is the more complete hub. It is slower, and its verdict matters most.
- `jvm` is a lighter hub that finishes in about ten minutes. It is the early signal.

Each kind has its own request job, `Request ephemeral hub (<kind>)`, and its own test job,
`e2e (ephemeral hub, <kind>)`. Every per-run artifact name ends in `-<kind>`, so each test job
waits only for its own kind's hub. A failure in one kind does not cancel the other. Both
workflows list the kinds as `matrix: { kind: [os, jvm] }` and must keep the lists equal.

## Two workflows

- `.github/workflows/hub-e2e-ephemeral.yml` ("Hub E2E (ephemeral test hub)") is the test
  workflow. It runs on `pull_request`, push and dispatch, holds no secrets in the job that runs
  the head's code, and is the only place in this repository's Actions where PR code runs.
- `.github/workflows/hub-e2e-ephemeral-request.yml` ("Hub E2E (ephemeral test hub) request") is
  the trusted side for pull requests. It runs on `pull_request_target`, checks out the base branch
  only, and never decrypts anything.

The split exists because a `pull_request_target` job runs in the base branch's Actions cache
scope. A job there that checked out and ran PR code could write a cache entry that `main`'s
workflows later restore, which CodeQL reports as cache poisoning. Under `pull_request`, PR code
gets a read-only token, no secrets and its own cache scope.

`.github/scripts/request_ephemeral_hub.sh` holds the steps both sides share: waiting for the
revision's published release files, finding the test run, fetching its public key, dispatching
the external workflow and downloading the encrypted session.

## How a hub is requested

These steps describe hold mode, where the test run drives the hub. The default
[provider-run suite](#provider-run-suite) shares steps 1 to 3.

1. The test workflow's `e2e` job generates a throwaway RSA-4096 keypair and uploads only the
   public key as artifact `ephemeral-hub-public-key-<attempt>-<kind>`.
2. For a pull request, the request workflow's `gate` job applies the dependency-only bot
   exemption from `.github/scripts/lane_gate.js`, checks that both secrets are set and skips PRs
   that change no file `.github/scripts/hub_relevant_paths.json` lists for this lane. When the lane should not run, it uploads artifact
   `ephemeral-hub-unavailable` with the reason, once for both kinds. Its `approve` job then
   holds a first-time contributor's PR for [approval](#first-time-contributors). For a push or dispatch, the test workflow's own `request-hub` job does the secrets check
   and uploads `ephemeral-hub-unavailable-<kind>`.
3. `request-hub` publishes a fork head's release files with `.github/scripts/publish_fork_bundle.sh`
   and waits until the revision's files are served. For a pull request it then finds the running
   `pull_request` test run for the same head SHA, waiting up to 10 minutes.
4. It dispatches the external workflow with the public key, head SHA, head repository, the
   test run's ID and its kind, and finds that provider run by the run ID and kind in its name. It
   waits up to 25 minutes for the hub's session and relays it, still encrypted, as artifact
   `ephemeral-hub-session-<test run id>-<attempt>-<kind>`.
5. `e2e` waits up to 45 minutes for its own kind's artifact, in its own run or in the request run
   for its head SHA. A request run that reports the lane unavailable makes `e2e` skip with that reason.
   A request run that ends without either artifact fails `e2e`. While the default branch has no
   request workflow the lane skips on PRs, because `pull_request_target` always runs the default
   branch's workflows and nothing would request the hub, whatever the PR's base branch.
6. `e2e` decrypts the session, masks it, reads `hub_get_info` once to mask the hub's identity,
   checks out the head, runs both scripts and uploads their redacted logs.

There is no teardown call: the hub ends with the test run. To retry a pull request, push a commit
or close and reopen the PR so both workflows start again. Re-running only the test run gets no
hub. To retry a push or dispatch, use **Re-run all jobs**.

## Provider-run suite

The workflow-level value `EPHEMERAL_RUN_SUITE` selects the mode for both kinds. Both workflows
carry it and must keep it equal. It is `'true'` by default, and `'false'` restores hold mode.

The shared script receives it as `RUN_SUITE` and the job's matrix kind as `HUB_KIND`, and
dispatches them as the provider's `run_suite` and `kind` inputs.

With `EPHEMERAL_RUN_SUITE: 'true'`, the provider runs `tests/e2e_test.py` and
`tests/sdk_conformance_test.py` at the head revision next to the hub, so the suite no longer
crosses a tunnel. The provider run ends with the suite, and `request-hub` waits up to 40 minutes
for it to complete. It then downloads the artifact `ephemeral-hub-results-<provider run id>` and
relays it as `ephemeral-hub-results-<test run id>-<attempt>-<kind>`. The artifact holds:

- `results.json`, with `schema_version`, `kind`, `public_sha`, `public_repo`, `started_at`,
  `finished_at`, and an `e2e` and an `sdk` object. Each carries `status`, `exit_code`, the
  counts and `failed_tests`.
- `e2e.log` and `sdk.log`, the scrubbed output of the two scripts.

`request-hub` rejects the artifact when it holds any other entry or a name outside
`[A-Za-z0-9._-]`. It then replaces its own secret values in each expected file, because an
artifact bypasses log masking.

The `e2e` job waits up to 90 minutes for that artifact and applies the same entry check. It prints
the three logs with workflow commands disabled, writes the counts and failed tests to the step
summary, and uploads the files as its results. It passes only when `e2e.status` and `sdk.status`
are both `passed`. An SDK leg that did not run fails the job, because conformance may not skip.
The job also fails when `results.json` is missing, has another schema version or names another
revision or another kind. In this mode it does not check out the head, install Python or
read `hub_get_info`. It still scrubs tunnel hostnames and MAC-shaped values from the files, as the
session path masks them.

Under the provider-run suite, PR test code runs inside the provider's isolated environment, which
holds no credentials, and only scrubbed logs come back. The request side still runs base code
only, and the `e2e` job still holds no secrets. The `e2e` job takes whichever artifact arrives, so
a PR keeps working while the default branch still requests the other mode.

## First-time contributors

The repository's setting for approving first-time contributors holds the `pull_request` test
run, but `pull_request_target` runs regardless of it, so the request workflow gates itself. Its
`approve` job uses the `e2e-auto` environment when the PR's author association is `OWNER`,
`MEMBER`, `COLLABORATOR` or `CONTRIBUTOR` (a change already merged here), and the
reviewer-protected `e2e-approval` environment otherwise. No hub boots and none of the PR's code
runs until a maintainer approves both: **Approve workflows to run** on the PR, then **Review
deployments** on the request run. The request side waits 10 minutes for the test run to start and
the test run 90 minutes for results, so approve the two together; after a timeout, push a commit
or close and reopen the PR. A rejected deployment fails `e2e`.

The hub is disposable and serves only the test run that requested it. The job that runs PR code
holds no secrets. Its token can only read this repository's contents and Actions artifacts and
run status. The two secrets reach only `request-hub`, which never executes PR code. Never add a
secret, a write permission or a cache to the `e2e` job, and never check out or run PR code in the
request workflow.

## Validating changes

Under `pull_request` the test workflow comes from the PR head, so its changes run in-PR. The
request workflow and the copy of the shared script it runs come from `main`. A dispatch,
`gh workflow run hub-e2e-ephemeral.yml --ref <branch>`, exercises the branch's test workflow,
its copy of the shared script and the provider contract through the push/dispatch request job;
it never runs `hub-e2e-ephemeral-request.yml`, whose only trigger is `pull_request_target`,
which always runs `main`'s copy. A change to that file is therefore proven only after it is on `main`, by the
next PR's run.

## Repository secrets

- `PRIVATE_CI_TOKEN` can dispatch the external workflow, list its runs and download its artifacts.
- `SOFTWARE_HUB_PROVIDER` names the repository (`owner/name`) that hosts that workflow.

When either is empty, the lane prints `Ephemeral hub lane not configured.` and skips without
failing.

## Masking

The `e2e` job masks the hub URL, its hostname, the access token and the gate password from the
session. It then reads `hub_get_info` with health alerts once and masks the hub's `hubUID`,
`zigbeeId`, `localIP`, every MAC-shaped value and every health-alert message text. No hub
value is written into the workflow source, because the runner prints step scripts to the public
log. Uploaded logs bypass log masking, so the job replaces the same values in them before upload.

`E2E_LANE=ephemeral` changes only what the scripts print. The suite labels the hub "ephemeral test
hub", prints health alerts as counts, and drops `localIP`, `hubData`, `hubUID`, `zigbeeId` and the
network block from failure messages. A stream filter also removes MAC addresses, tunnel hostnames
and the identity values read at startup from every line. Test selection and assertions are the
same as in the physical lane.

## Coverage

The lane proves the head revision's MCP app over HTTP with the full E2E registry and the official
SDK. It does not cover radios, physical devices, the cloud relay, or the watchdog install and
purge path, whose tests skip without `WATCHDOG_URL`. The physical-hub lane in `hub-e2e.yml` keeps
that evidence and its required `Full e2e (runs with label)` gate.

The lane sets `E2E_MRTR_ACTION_COUNT=36` and `E2E_WRITE_CAP_VARIABLE_COUNT=120` so the MRTR
continuation and write-cap proofs outlast one HTTP leg on a fast hub, and the watchdog-dependent
tests, `test_endpoint_access_toggles_block_their_own_transport` and
`test_bps_refusal_is_error_logged_at_error_and_debug_thresholds`, skip.
