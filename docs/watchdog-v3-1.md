# E2E watchdog v3.2 (file `e2e-deadman-watchdog-v3-1.groovy`)

Watchdog v3.2 keeps the v3.1 file name, so it can be reverted to 3.1 by reinstalling the older source.
`e2e-deadman-watchdog-v3-1.groovy` is a clone of v3 (`e2e-deadman-watchdog-v3.groovy`) that can
install the built package of issue #522. Everything in [watchdog-v3.md](watchdog-v3.md) applies to
it unchanged except what is listed here.

## Differences from v3

- Identity: app name `E2E Dead-Man Watchdog v3.2` (`v3.1` before 3.2), `serverInfo`
  `e2e-deadman-watchdog-v3-1` / `3.2`, and `hub_get_info.watchdogVersion` is the string `"3.2"`
  (v3 answers the number `3`). It is a separate Apps Code class and instance, so it can run beside
  v3 on the same hub.
- `hub_update_package` accepts a built package: when the manifest at `ref` declares no bundle and
  its apps live on the `bundle-artifacts` branch, pass `apps` (the `sha256` of `MCP Rule` and
  `MCP Rule Server` as built by `tools/build-release-app.py`) instead of `libraries`. V3.1 fetches
  both from `bundle-artifacts/shas/<ref>/`, refuses a hash mismatch or a parent that still has
  `#include` lines, and skips the library step. The request ID binds the app hashes. A
  `libraries` request against a bundle manifest behaves exactly as in v3.
- Its code-protection check (the refusal to delete, or to replace without a live MCP peer, a
  watchdog's own code) covers v3 code and this app's code under either name (v3.1 or v3.2).
- `hub_delete_app_runtime_stats` resets the hub's per-app runtime statistics (Logs > App Stats),
  the busy-time counters the per-app load limiter compares against hub uptime. CI calls it at the
  start of every run and when the limiter trips mid-run, because disabling and re-enabling the app
  does not clear them. `verified` is true only when the named app's total was read before and
  after the reset and dropped (or was already 0), and `limitMs` is the busy budget
  (hub uptime x the excessive-load threshold %) the limiter compares that total against, counted
  twice when the app produces its own events.

3.2 adds hub_delete_app_runtime_stats; nothing else changed from 3.1.

## Wiring

- E2E, `install_main`, the probe, the fixture setup workflow, and `watchdog_update` maintenance use
  the `WATCHDOG31_MCP_URL` secret. `watchdog_v3.py` keeps its file name and accepts only an
  endpoint that answers `watchdogVersion` `"3.2"` or `"3.1"`; if the secret points elsewhere it reads the
  endpoint from the app page of the instance named v3.2 or v3.1, as v3 once did for v2.
- Maintenance self-updates the `E2E Dead-Man Watchdog v3.2` class (named v3.1 until its first 3.2
  install) from
  `e2e-deadman-watchdog-v3-1.groovy` at the dispatched ref.
- Specs: `WatchdogV31ParitySpec`, `WatchdogV31PackageDeploySpec`, `WatchdogV31ManualSpec`, and
  `WatchdogV31PurgeFixturesSpec` cover v3.2, including the behavior inherited from v3. They share
  one compilation per test JVM through `WatchdogSpec`, with fresh state between tests.

## v3 retirement

V3 is retired from E2E; the workflows use `WATCHDOG31_MCP_URL`. Its source file remains for
reference, but its unit-test coverage has been consolidated into the v3.2 suites.
