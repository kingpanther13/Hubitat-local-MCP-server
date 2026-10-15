# Ephemeral hub E2E

`.github/workflows/hub-e2e-ephemeral.yml` ("Hub E2E (ephemeral test hub)") runs the full
`tests/e2e_test.py` registry and then `tests/sdk_conformance_test.py` against a disposable test
hub. An external workflow boots that hub for one run and ends it when the run completes. The lane
runs on every pull request, forks included, on pushes to `main`, and on manual dispatch with an
optional `pr_number`. It has no approval environment and no label gate. `e2e:skip` holds only the
physical-hub lane. Docs-only PRs and verified dependency-only bot PRs skip it.

## How a hub is requested

1. `gate` runs base code. It applies `.github/scripts/lane_gate.js`, checks that both secrets are
   set, skips PRs that change no hub-relevant file, and resolves the head SHA and repository.
2. `e2e` generates a throwaway RSA-4096 keypair and uploads only the public key as an artifact.
3. `request-hub` runs base code. For a fork head it first publishes the head's release artifact
   with `.github/scripts/publish_fork_bundle.sh`. It then dispatches the external workflow with
   the public key, head SHA, head repository and this run's ID, and waits up to 25 minutes for
   the hub's session artifact. It relays that artifact into this run without decrypting it.
4. `e2e` decrypts the session, masks it, reads `hub_get_info` once to mask the hub's identity,
   checks out the head, runs both scripts and uploads their redacted logs.

There is no teardown call. To retry, use **Re-run all jobs**: re-running only the failed `e2e`
job has no hub, because the earlier attempt's hub ended with that attempt.

## Why fork PRs run without approval

The hub is disposable and serves only the run that requested it. The job that runs PR code holds
no secrets: its token can read this run's artifacts and job status, and it uses that token only
before checkout. The two secrets reach only `request-hub`, which never executes PR code. A fork
can therefore reach nothing beyond its own throwaway hub. The repository's workflows use no
Actions caches, so a cache write from PR code has no consumer. Keep it that way, and never add a
secret, a write permission or a cache to the `e2e` job.

Under `pull_request_target` the workflow file comes from `main`. Validate a change to it with
`gh workflow run hub-e2e-ephemeral.yml --ref <branch>`.

## Repository secrets

- `PRIVATE_CI_TOKEN` can dispatch the external workflow, list its runs and download its artifacts.
- `SOFTWARE_HUB_PROVIDER` names the repository (`owner/name`) that hosts that workflow.

When either is empty, `gate` prints `Ephemeral hub lane not configured.` and the lane skips
without failing.

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
