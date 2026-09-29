# Experimental watchdog v3 package deployment

Install `e2e-deadman-watchdog-v3.groovy` as a **new Apps Code class and app instance**
on the E2E hub. It has its own OAuth endpoint. Keep watchdog v2 installed and its
source, instance, token, settings, and GitHub secret unchanged.

V3 offers a background package update with persisted stage messages. It installs
the pinned libraries bundle, then saves the existing MCP child and parent code
classes. It preserves their code IDs, running instances, and OAuth settings.
Status reports the current component, elapsed time, and bounded stage history.
It cannot report compiler percentages or internal Hubitat progress.

## Isolation and recovery

V3 exposes package start/status/release and read-only app inventory. It has no
reboot, disable, delete, self-update, or autonomous restore path. V2 remains the
independent recovery controller; v3's hold only prevents further v3 deployments.

Before a probe, reserve the E2E hub through the existing maintenance queue/lease,
take a fresh backup, verify all three endpoints, and record app/code identities.
The v2 flag must be disarmed with any previous restore completed. Do not run E2E,
purge, or other maintenance alongside the probe. V3 reads the v2 flag but never
writes it. Its interlock does not replace the exclusive lease.

If MCP becomes unavailable, stop v3 work and use the unchanged v2 to restore the
known-good MCP package. First establish that the v3 worker and any submitted hub
save have finished, so recovery cannot overlap compilation. Do not update,
restart, disable, or reboot the surviving controller. Preserve the lease while
investigating an uncertain result.

## Deployment contract

- `hub_update_package`: caller-chosen `requestId`, immutable 40-character commit
  `ref`, expected library `name`/`sha256` pairs, and `confirm:true`. Prepare the
  hashes from that commit's GitHub-built libraries-only bundle and compare it
  with the published artifact before submitting. Submit once.
- `hub_get_package_deployment`: `requestId`. Reads persisted status without hub
  HTTP. Reconnect using the same ID after a lost response; never repeat a save.
- `hub_set_package_deployment`: matching `requestId`, `confirm:true`, and
  `endpointVerified:true`. Acknowledge only after the original MCP, v2, and v3
  URLs/tokens work and the app identity snapshot is unchanged. V3 rechecks all
  installed source hashes before releasing its hold. `abandon:true` can release
  a stopped operation after independent recovery and endpoint verification.

A lost save response enters read-only verification, requiring an advanced code
version and the full expected source hash. Matching libraries are left in place;
otherwise bundle installation is submitted once and all library sources verified.
Verification has a ten-minute deadline per component, after the HTTP wait. A
stopped job retains its hold. A restart during a worker can leave
`workerActive:true`; v3 deliberately does not resume uncertain writes or expire
that hold automatically.

## Validation

New tests, the manual probe, and any workflow adjustments belong on the temporary
`test/watchdog-v3-validation` branch until live validation proves this approach.
The development PR changes neither permanent tests nor the normal E2E installer
and carries `e2e:skip`. GitHub simulation tests cannot prove hub scheduling or
persistence; record live stage timings, unchanged identities, working original
tokens, and reconnect-without-replay behavior before adopting v3 in E2E.
