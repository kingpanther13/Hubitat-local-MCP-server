# Watchdog v3: manual administration and package deployment

`e2e-deadman-watchdog-v3.groovy` is E2E test-hub tooling: a small MCP server with
its own OAuth `/mcp` endpoint, separate from the MCP app under test. It exposes
v2's 26 manual tools plus package start, live status, and hold release. It
is never in the HPM manifest and never on a user hub.

## What v3 does on its own

V3 never restores a package, arms a deadline, or reads a flag file. Writing a
file never starts recovery. A package is deployed only when a caller asks.

The one automatic action is the wedge reboot. A hub whose web stack is wedged
cannot serve the watchdog's endpoint, so no remote caller can request the
reboot. A once-a-minute tick probes loopback HTTP; after 8 consecutive failures
with no success for 4 minutes, and a live probe that also fails, v3 reboots the
hub. It does so at most once per 30 minutes and never into a reboot or platform
update it was asked to start. The `autoRebootOnWedge` setting turns it off.

V3 refuses to disable or force-delete its own instance, or to delete its own
code class. It also refuses to replace its own code unless the MCP server's
endpoint answers (checked over loopback: one installed, enabled instance whose
`tools/list` offers a code-update tool). `hub_get_info` with `peer:true` reports the same check.

## Manual capabilities

V3 retains app/source and library management, bundle management, file access,
hub backups, hub variables, Developer Mode setup, fixture cleanup, diagnostics,
and explicitly requested reboot/platform updates, under v2's tool names.
`hub_purge_e2e_artifacts` is the whole e2e teardown: it removes every `BAT_E2E_`
app, hub variable, device, room and file plus the `mcptest` throwaway code, so
teardown never depends on the MCP app under test. `BAT_E2E_KEEP_` scaffolds are
kept.
`hub_get_source` saves a large source to File Manager unless `noSave:true`.

## Package deployment

A background worker installs the pinned libraries bundle, then saves the existing
MCP child and parent code classes. It preserves their code IDs, running instances,
and OAuth settings. Status contains the component, elapsed time, the latest
verification finding (`detail`), the error, and stage history; Hubitat does not
expose compiler percentages through this operation.

Like HPM, v3 keeps that progress in memory and persists only the hold: the
operation record is written at admission, when it comes to rest (`stopped` or
`awaiting_verification`), and at release.

- `hub_update_package`: caller-chosen `requestId`, immutable 40-character `ref`,
  expected library `name`/`sha256` pairs, and `confirm:true`. Optional `baseUrl`
  selects a raw GitHub source repository; `bundleBaseUrl` selects the repository
  hosting the SHA-specific bundle. This permits fork source with a bundle
  published by the upstream workflow. Both default to the upstream repository.
  Independently build and compare the published bundle before submitting once.
- `hub_get_package_deployment`: reads status for `requestId` without hub HTTP.
  Reconnect using the same ID after a lost response.
- `hub_set_package_deployment`: releases the hold, with `confirm:true`.
  - Complete: `endpointVerified:true`, once the operation is
    `awaiting_verification` and the caller has checked that the original MCP and
    v3 endpoint URLs and tokens still answer and the app instances are unchanged.
    V3 rechecks installed hashes first. A failed recheck keeps the hold and the
    phase, so completion can be retried.
  - Abandon: `abandon:true` plus `writesSettled:true` gives up a held operation
    in any phase, once the operator has confirmed every submitted hub write has
    finished. This is the exit when the installed MCP is broken and a known-good
    ref must be deployed over it. Abandonment never marks the deployment
    successful or asserts that MCP is healthy.

The request ID binds the source repositories, commit, and expected libraries.
Matching libraries stay in place; otherwise the bundle is submitted once and
all library sources are verified. A library the commit adds is created by the
bundle; a library installed more than once stops the operation. Lost app-save
responses lead to read-only verification of the full source hash and an advanced
code version, without replaying the save.

## Concurrency and recovery

Reserve the hub through the existing exclusive lease and record app/code
identities before deployment. The write guards below do not
coordinate independent controllers: the lease remains necessary.

An active or held package operation blocks other manual writes through v3,
including source reads that would autosave. Read-only diagnostics and source
reads with `noSave:true` remain available. A manual write also prevents package
admission until that request returns; the refusal carries `busy:true` and the
running tool. Two exceptions: a repeated `hub_purge_e2e_artifacts` reaches the
purge's own single-flight latch, and `hub_reboot` with `force:true` is never
blocked.

A stopped operation retains its hold until it is abandoned. A hub restart or a
v3 code load during a deployment empties the in-memory progress, so the status
reports `interrupted` at once: nothing is running, and v3 never resumes
uncertain writes. Abandon it once the submitted writes have settled. A worker
that hangs without a restart reports `workerStale:true` after 15 minutes without
progress and can then be abandoned. Saving the app's settings page does not
cancel a pending verification poll.

If MCP fails, deploy a known-good ref through v3. Do not update, disable, restart,
or reboot the working controller while investigating the other app. Each app is
the other's repair path. V3 enforces only part of that rule: it will not disable
or delete itself, and it replaces its own code only when the MCP check passes.
It does not block a reboot (`hub_reboot` with `force:true` always runs), and the
MCP server can still update or remove v3.
