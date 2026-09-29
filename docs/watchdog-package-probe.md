# Watchdog package deployment probe

This is an opt-in replacement candidate for the E2E package install. Normal E2E
continues to use its existing installer until the standing watchdog is updated
and this path is validated on the E2E hub. Keep `e2e:skip` on the development PR.

## Safety model

The watchdog remains standalone and updates only the existing MCP parent and
child Apps Code IDs. It does not create apps, edit OAuth settings, update itself,
disable either app, or reboot the hub. The library bundle is pinned to the same
commit as the app sources and the manual client checks that it contains only
libraries. All required libraries must already exist uniquely on the hub.

An accepted deployment holds conflicting watchdog writes, automatic deadman
restore, and automatic reboot. The hold is persistent, including after a stopped
job, observer timeout, or watchdog restart. There is no automatic expiry that
could start another save while a previous one is still running. This deliberately
parks the existing recovery loop during the experiment; retain the hub lease and
monitor the job until it has a verified outcome.

The worker records each stage before starting its I/O. A missing save response
enters read-only verification against the full expected source hash. It never
replays a save. Library installation is likewise submitted once and verified
against every expected library's complete source. Verification has a ten-minute
deadline per component, separate from the original HTTP wait. An error or an
expired verification deadline stops the job and leaves the hold in place.

## Before changing the watchdog

1. Finish the branch's non-E2E GitHub checks and record its exact commit SHA.
2. Reserve the E2E hub exclusively using its existing lease/maintenance queue.
   Verify no E2E, purge, restore, or other maintenance is active. The flag must be
   disarmed with any previous disarm restore successfully completed.
3. Verify both original MCP URLs with `initialize` and `tools/list`. Record code
   IDs, instance IDs and parent relationships. Preserve OAuth identity privately;
   never print tokens into logs or PR comments.
4. Preserve and verify the previous watchdog source using the existing maintenance
   prepare/upload/download/checksum procedure. Take a fresh hub backup.
5. Update only the existing watchdog code class, keeping its instance and OAuth
   configuration. The main MCP server is the standby controller during this step.
6. Verify the watchdog's original endpoint and the three deployment tools below,
   then verify the main endpoint again. If either is unavailable, stop. Do not
   update, disable, restart, or reboot the remaining controller.

Watchdog installation is a separate maintenance operation. This probe does not
perform it, and running normal E2E does not install the watchdog from the PR.

## Probe the MCP update

Use the GitHub-built `mcp-libraries.zip` for the validated SHA. The client compares
it with that SHA's published artifact before contacting the hub. Set `WATCHDOG_URL`
and `MCP_URL` to the original E2E endpoints in the private environment, then run:

```sh
python .github/scripts/watchdog_package_deploy.py \
  --ref <validated-full-sha> \
  --bundle <github-built-mcp-libraries.zip> \
  --lease-held
```

`--lease-held` is an operator assertion, not a lease acquisition implementation.
Keep the lease alive independently while the probe runs. Do not run the normal
E2E arm/disarm scripts alongside this probe.

The client prints the operation ID before submitting once. A lost start response
is followed only by status reads for that ID. Each status includes the component,
phase, phase duration, and bounded history. Long app compilation stays on
`updating_app`; no percentage or heartbeat is inferred from unchanged text.

After the worker reaches `awaiting_verification`, the client checks both original
URLs/tokens with initialization and tool discovery and compares the complete
installed-app identity snapshot. It acknowledges completion only after those
checks. The watchdog rechecks source/library hashes before releasing the hold.

## Tool contract

- `hub_update_package`: `requestId`, full commit SHA in `ref`, expected library
  `name`/`sha256` pairs, and `confirm:true`. Caller-chosen IDs make a lost response
  recoverable. Reusing an ID with different inputs is refused.
- `hub_get_package_deployment`: `requestId`. Reads persisted status without hub
  HTTP or state writes; no source, token, or internal plan is returned.
- `hub_set_package_deployment`: matching `requestId`, `confirm:true`, and
  `endpointVerified:true`. This is the caller's attestation that both original
  endpoints and the instance snapshot passed. It releases a verified completed
  deployment. An explicit `abandon:true` may release a stopped job only after the
  operator has independently restored and verified both controllers; the normal
  probe never sends it.

If observation fails, record the operation ID and keep the lease. Inspect status
and logs through the surviving controller. Do not rerun the installer, bounce an
app, self-update the watchdog, or reboot as a response to a timeout. A stopped job
does not authorize modifying the remaining controller. Recovery requires an
explicit operator decision after checking which endpoint is healthy.

## Acceptance evidence still required

On the E2E hub, record stage timings for a same-version repair and a different
revision, exact source/library verification, unchanged app/code identities and
OAuth values, and both original endpoints remaining usable. Exercise observer
disconnection without interrupting the apps and confirm reconnecting status does
not submit another install. GitHub tests cover simulated timeout/error/duplicate
paths; they cannot establish Hubitat scheduling, concurrency, or persistence.

No live disruption or deliberate failure of either controller is needed for the
first probe. Keep normal E2E adoption and recovery changes gated on this evidence.
