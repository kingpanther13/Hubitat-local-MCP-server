# E2E watchdog v3.1

`e2e-deadman-watchdog-v3-1.groovy` is a clone of v3 (`e2e-deadman-watchdog-v3.groovy`) that can
install the built package of issue #522. Everything in [watchdog-v3.md](watchdog-v3.md) applies to
it unchanged except what is listed here.

## Differences from v3

- Identity: app name `E2E Dead-Man Watchdog v3.1`, `serverInfo` `e2e-deadman-watchdog-v3-1` / `3.1`,
  and `hub_get_info.watchdogVersion` is the string `"3.1"` (v3 answers the number `3`). It is a
  separate Apps Code class and instance, so it can run beside v3 on the same hub.
- `hub_update_package` accepts a built package: when the manifest at `ref` declares no bundle and
  its apps live on the `bundle-artifacts` branch, pass `apps` (the `sha256` of `MCP Rule` and
  `MCP Rule Server` as built by `tools/build-release-app.py`) instead of `libraries`. V3.1 fetches
  both from `bundle-artifacts/shas/<ref>/`, refuses a hash mismatch or a parent that still has
  `#include` lines, and skips the library step. The request ID binds the app hashes. A
  `libraries` request against a bundle manifest behaves exactly as in v3.
- Its code-protection check (the refusal to delete, or to replace without a live MCP peer, a
  watchdog's own code) covers both v3 and v3.1 code.

## Wiring

- E2E, `install_main`, the probe, the fixture setup workflow, and `watchdog_update` maintenance use
  the `WATCHDOG31_MCP_URL` secret. `watchdog_v3.py` keeps its file name and accepts only an
  endpoint that answers as v3.1; if the secret points elsewhere it reads the v3.1 endpoint from the
  v3.1 app page, as v3 once did for v2.
- Maintenance self-updates the `E2E Dead-Man Watchdog v3.1` class from
  `e2e-deadman-watchdog-v3-1.groovy` at the dispatched ref.
- Specs: `WatchdogV31ParitySpec`, `WatchdogV31PackageDeploySpec`, `WatchdogV31ManualSpec`, and
  `WatchdogV31PurgeFixturesSpec` cover v3.1, including the behavior inherited from v3. They share
  one compilation per test JVM through `WatchdogSpec`, with fresh state between tests.

## v3 retirement

V3 is retired from E2E; the workflows use `WATCHDOG31_MCP_URL`. Its source file remains for
reference, but its unit-test coverage has been consolidated into the v3.1 suites.
