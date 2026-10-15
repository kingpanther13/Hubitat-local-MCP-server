# Ephemeral hub E2E

The ephemeral lane runs the full `tests/e2e_test.py` registry and then
`tests/sdk_conformance_test.py` against a disposable test hub. An external workflow boots that hub
for one run and ends it when the run completes. The lane runs on every pull request, forks
included, on pushes to `main`, and on manual dispatch of a branch. It has no approval environment
and no label gate of its own. `e2e:skip` holds only the physical-hub lane. Docs-only PRs and
verified dependency-only bot PRs skip it.

## Two workflows

- `.github/workflows/hub-e2e-ephemeral.yml` ("Hub E2E (ephemeral test hub)") is the test
  workflow. It runs on `pull_request`, push and dispatch, holds no secrets in the job that runs
  the head's code, and is the only place PR code runs.
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

1. The test workflow's `e2e` job generates a throwaway RSA-4096 keypair and uploads only the
   public key as artifact `ephemeral-hub-public-key-<attempt>`.
2. For a pull request, the request workflow's `gate` job applies `.github/scripts/lane_gate.js`,
   checks that both secrets are set and skips PRs that change no hub-relevant file. When the lane
   should not run, it uploads artifact `ephemeral-hub-unavailable` with the reason. For a push or
   dispatch, the test workflow's own `request-hub` job does the secrets check.
3. `request-hub` publishes a fork head's release files with `.github/scripts/publish_fork_bundle.sh`
   and waits until the revision's files are served. For a pull request it then finds the running
   `pull_request` test run for the same head SHA, waiting up to 10 minutes.
4. It dispatches the external workflow with the public key, head SHA, head repository and the
   test run's ID, and waits up to 25 minutes for the hub's session. It relays that session, still
   encrypted, as artifact `ephemeral-hub-session-<test run id>-<attempt>`.
5. `e2e` waits up to 45 minutes for that artifact, in its own run or in the request run for its
   head SHA. A request run that reports the lane unavailable makes `e2e` skip with that reason.
   A request run that ends without either artifact fails `e2e`. A PR whose base branch has no
   request workflow skips the lane, because nothing would request its hub.
6. `e2e` decrypts the session, masks it, reads `hub_get_info` once to mask the hub's identity,
   checks out the head, runs both scripts and uploads their redacted logs.

There is no teardown call: the hub ends with the test run. To retry a pull request, push a commit
or close and reopen the PR so both workflows start again. Re-running only the test run gets no
hub. To retry a push or dispatch, use **Re-run all jobs**.

## Why fork PRs run without approval

The hub is disposable and serves only the test run that requested it. The job that runs PR code
holds no secrets. Its token can only read this repository's contents and Actions artifacts and
run status. The two secrets reach only `request-hub`, which never executes PR code. A fork can
therefore reach nothing beyond its own throwaway hub. Never add a secret, a write permission or a
cache to the `e2e` job, and never check out or run PR code in the request workflow. The
repository's own setting for approving first-time contributors' `pull_request` runs still
applies; a run held for approval past the request workflow's 10-minute wait needs a retry.

## Validating changes

Under `pull_request` the test workflow comes from the PR head, so its changes run in-PR. The
request workflow and the copy of the shared script it runs come from `main`. Validate a change
to either with `gh workflow run hub-e2e-ephemeral.yml --ref <branch>`, which runs the branch's
own request path.

## Repository secrets

- `PRIVATE_CI_TOKEN` can dispatch the external workflow, list its runs and download its artifacts.
- `SOFTWARE_HUB_PROVIDER` names the repository (`owner/name`) that hosts that workflow.

When either is empty, the lane prints `Ephemeral hub lane not configured.` and skips without
failing.

## Masking

The `e2e` job masks the hub URL, its hostname, the access token, the gate password, the hub's
`hubUID`, `zigbeeId`, `localIP`, name and every MAC-shaped value, plus the hub's fixed placeholder
address and alert texts. Uploaded logs bypass log masking, so the job replaces the same values in
them before upload.

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

The lane sets `E2E_MRTR_ACTION_COUNT=72` and `E2E_WRITE_CAP_VARIABLE_COUNT=120` so the MRTR
continuation and write-cap proofs outlast one HTTP leg on a fast hub, and the watchdog-dependent
tests, `test_endpoint_access_toggles_block_their_own_transport` and
`test_bps_refusal_is_error_logged_at_error_and_debug_thresholds`, skip.
