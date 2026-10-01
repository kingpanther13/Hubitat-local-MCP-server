# Watchdog v3 manual administration and package deployment

V3 is intended to replace v2's manual administration surface. It exposes all 26
v2 tools plus package start, persisted status, and explicit hold release. Keep
v2's source, instance, token, settings, and GitHub secret unchanged while v3 is
being validated. The intended merge outcome is full replacement of v2 by v3,
including E2E workflow adoption. Existing workflows stay on v2 during this
validation stage.

## Manual capabilities

V3 retains app/source and library management, bundle management, file access,
hub backups, hub variables, Developer Mode setup, fixture cleanup, diagnostics,
and explicitly requested reboot/platform updates. Existing tool names and input
schemas are preserved. The source-read tool still autosaves large sources unless
`noSave:true` is supplied.

V3 has no deadman timer, flag-driven restoration, automatic reboot, or scheduled
health polling. Writing a file never starts recovery. Initialization preserves
an existing OAuth token and schedules no work. Recovery happens only when a
caller explicitly deploys a known-good revision or invokes a manual tool.

## Package deployment

A background worker installs the pinned libraries bundle, then saves the existing
MCP child and parent code classes. It preserves their code IDs, running instances,
and OAuth settings. Status contains the component, elapsed time, error, and stage
history; Hubitat does not expose compiler percentages through this operation.

- `hub_update_package`: caller-chosen `requestId`, immutable 40-character `ref`,
  expected library `name`/`sha256` pairs, and `confirm:true`. Optional `baseUrl`
  selects a raw GitHub source repository; `bundleBaseUrl` selects the repository
  hosting the SHA-specific bundle. This permits fork source with a bundle
  published by the upstream workflow. Both default to the upstream repository.
  Independently build and compare the published bundle before submitting once.
- `hub_get_package_deployment`: reads persisted status for `requestId` without
  hub HTTP. Reconnect using the same ID after a lost response.
- `hub_set_package_deployment`: acknowledge the matching operation with
  `confirm:true` and `endpointVerified:true` after checking the original MCP and
  v3 endpoints/tokens and unchanged app instances. V3 rechecks installed hashes
  before releasing its hold. For repair while MCP is unavailable, `abandon:true`
  plus `writesSettled:true` can release a stopped, inactive job only after the
  operator independently confirms every submitted hub write has finished.
  Abandonment does not mark deployment successful or assert that MCP is healthy.

The request ID binds the source repositories, commit, and expected libraries.
Matching libraries stay in place; otherwise the bundle is submitted once and
all library sources are verified. Lost app-save responses lead to read-only
verification of the full source hash and an advanced code version, without
replaying the save.

## Concurrency and recovery

Reserve the hub through the existing exclusive lease, take a fresh backup, and
record app/code identities before deployment. V3 does not read or write v2's
control flag as part of deployment. During coexistence, the operator must verify
v2 is idle before starting; its independent recovery behavior still exists.

An active or held package operation blocks other manual writes through v3,
including source reads that would autosave. Read-only diagnostics and source
reads with `noSave:true` remain available. A manual write also prevents package
admission until that request returns. These guards do not coordinate independent
controllers: the exclusive hub lease remains necessary.

A stopped operation retains its hold. A restart during a worker can leave
`workerActive:true`; v3 does not resume uncertain writes or expire that hold.
Establish that any submitted save has finished before explicit recovery. If MCP
fails, preserve the working controller. Do not update, disable, restart, or reboot
that controller while investigating the other app.

## Validation and rollout

New tests remain on the temporary `test/watchdog-v3-validation` branch and run on
GitHub. V2 and permanent tests/workflows are unchanged. The PR retains `e2e:skip`.

The earlier four-tool prototype was installed separately as code class `1925`,
instance `48028`. It completed live package updates and explicit main restoration,
including PR #452 and a one-character variant. Those runs validate the prototype's
deployment worker, not the expanded manual tool surface in this revision.

Before merging this PR, install and check the expanded app on the E2E hub, then
use a temporary workflow to exercise the full suite, explicit main restoration,
cleanup, failure handling, and lease release through v3. Complete the permanent
workflow and endpoint configuration switch in this PR so v3 fully replaces v2
at merge. Keep v2 available until v3 is fully configured and that validation
succeeds; retire v2 only after those gates pass.
