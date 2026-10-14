# Software Hubitat integration CI

The **Emulated Hubitat E2E (software)** workflow runs the production MCP app against
an in-memory Hubitat platform model through a loopback HTTP endpoint. It is an
additive integration lane: `.github/workflows/hub-e2e.yml` remains the physical-hub
test workflow.

## Trigger and result

`.github/workflows/emulated-hub.yml` runs on every PR open, new commit, and reopen,
on pushes to `main`, `ci/**`, and `feat/emulated-e2e*` branches, and by manual
dispatch. It has no label or changed-path filters. `e2e:skip` and release/E2E labels
cannot disable this lane. Fork PRs use the normal `pull_request` event and GitHub's
usual workflow-approval policy.

The job uses Java 17 for the Gradle daemon, the Java 11 test toolchain, and Python
3.12 with the existing E2E MCP client. Its GitHub runner command is:

```sh
./gradlew -p ci/emulated-hub clean test --no-daemon --console=plain
```

Each run starts with clean test outputs and fresh in-memory platform state.
`server.EmulatedHubHttpSpec` must execute at least five scenarios: the HTTP
client flows in flat and gateway mode, instance isolation, refusal of unmodeled
executor APIs and timers, and shutdown of abandoned requests. Missing, empty,
failed, or skipped test reports fail the job. JUnit XML, HTML reports, Gradle
problem reports, and public scenario logs are uploaded as
`emulated-hub-report-<attempt>` for seven days,
including on failure when diagnostics exist. An upload with no diagnostic files
also fails instead of publishing an empty artifact. Problem reports or logs
cannot substitute for the required passing JUnit scenarios.

## Trust boundary and coverage limits

The public lane uses `contents: read`, disables persisted checkout credentials,
and reads dependency caches without publishing them. It receives no physical-hub
URL or watchdog credential.
It does not deploy to either physical hub. Only public software test diagnostics
are uploaded.

The production MCP request handler and tool dispatch run inside the existing
Groovy 3/HubitatCI harness. A loopback HTTP adapter supplies the request/response
binding; the platform model supplies supported platform responses and mutable
fixture state. The scenarios cover:

- MCP initialization, catalog/dispatch, and modern protocol metadata errors.
- Virtual switch creation/deletion, native commands, state readback, and pagination.
- A seeded native hub variable set/get/list round trip.
- Persisted device-allowlist bypass permission changes and Read-master refusal/re-enablement.
- UTF-8 file creation, readback, overwrite with backup, and deletion.
- Independent instance state and explicit failures for unimplemented platform operations,
  executor APIs, and timers.
- Loopback server shutdown releases pending response futures for queued and polled requests.

A green result proves these software scenarios against that model. It does not
prove Hubitat's proprietary Groovy sandbox, native application wizards, cloud
relay/authentication, radios, hardware, delayed scheduling, filesystem recovery,
or the full physical-hub E2E suite. This model covers the listed operations and
does not implement the complete Hubitat platform.
