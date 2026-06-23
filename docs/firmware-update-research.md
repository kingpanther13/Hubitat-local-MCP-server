# Hub firmware update — research notes & resilience plan

Status: **apply + status IMPLEMENTED** as `hub_update_firmware` (#259); rollback + a richer
check remain unbuilt. Originally compiled (AI-assisted) from Hubitat community reverse-engineering,
the HPM source, and this repo's code.

> **UPDATE (#259, live-verified on FW 2.5.0.157):** the `/hub/cloud/*` family is the working,
> token-free path and is what `hub_update_firmware` uses — this supersedes the `/management/*`
> token-gated guess and the "no local check endpoint" gap noted below:
> - `GET /hub/cloud/checkForUpdate` → `{version, upgrade, status:"UPDATE_AVAILABLE"|..., releaseNotesUrl, beta, hubCount, accountEmails[...]}` (a working local availability check; `hub_update_firmware` returns this verbatim under `available` — `accountEmails` is the hub owner's own email).
> - `GET /hub/cloud/updatePlatform` → applies (downloads, installs, self-reboots).
> - `GET /hub/cloud/checkUpdateStatus` → `{status:"IDLE"|...}` (install progress; `hub_update_firmware(statusOnly=true)` polls it).
> Note: `/hub/cloud/checkForUpdate` was MORE current than `/hub2/hubData.alerts.platformUpdateAvailable` (cloud said UPDATE_AVAILABLE 2.5.0.159 while hubData still read false). Rollback is still UI-only.

## Why this matters

A real-hub e2e exists to catch breakage when the platform changes under us. Hubitat ships firmware
to users, users apply it, and our code branches on firmware behaviour in several places (radio
endpoints, `/hub2/*` shapes, a known toggle-reset quirk). So the test hub must track firmware and be
**re-validated on every bump** — to find regressions before users do. The goal is not "pin to old" —
it's "stay current and re-test."

## Current state

Firmware **apply + status-poll** now exist as `hub_update_firmware` (#259). Rollback and
version-comparison logic remain unbuilt.

- Firmware **reads** live on `hub_get_info`: `firmwareVersion` (from `hub.firmwareVersionString`)
  plus `platformUpdate` (pending hub update, from `/hub2/hubData`). The MCP-server-app GitHub check
  is `hub_get_info(includeAppUpdate=true)` → `appUpdate` (this folded in the former standalone
  `hub_get_update_status`).
- Firmware **install** is `hub_update_firmware` (confirm-gated; `statusOnly=true` polls progress).
- All firmware-conditional code still uses endpoint-existence/fallback, not version comparison.

## Hub platform-update endpoints (firmware 2.3.9.176+)

> **SUPERSEDED by the UPDATE note at the top.** `hub_update_firmware` ships on the token-free
> `/hub/cloud/*` family, NOT the `/management/*` family in the table below. The `/management/*`
> rows are retained for the historical record (and the remote-reboot/clone capabilities, which are
> unbuilt); they are NOT the apply path used.

Local HTTP, **port 8080** (port 80 is blocked), management endpoints are **token-gated**.

| Endpoint | Method | Purpose | Source |
|---|---|---|---|
| `/hub/advanced/getManagementToken` | GET | Returns the hub's management token (required by `/management/*`) | [release 2.3.9](https://community.hubitat.com/t/release-2-3-9-available/138312) |
| `/management/firmwareUpdate?token=…` | POST | **Apply** a platform update | release 2.3.9 |
| `/management/firmwareUpdateStatus?token=…` | GET | Update status | release 2.3.9 |
| `/management/reboot?token=…` | GET | Remote reboot (added 2.3.9.180) | release 2.3.9 p2 |
| `/management/clone?token=…&source=…` | — | Copy DB config between hubs | release 2.3.9 |
| `/hub/advanced/freeOSMemory`, `/internalTempCelsius`, `/databaseSize` | GET | Health (no auth) — useful for "is the hub back up" polling | repo `SKILL.md` |

**Gaps (not clean local endpoints):**

- ~~**Check-for-available-update** — no documented local endpoint.~~ **RESOLVED (#259):**
  `GET /hub/cloud/checkForUpdate` is a working token-free local check (see the UPDATE note at top).
- **Rollback** — still UI-only via the Diagnostic Tool "Restore Previous Version"; no documented HTTP
  endpoint. Only the current build + 1–2 prior minors are retained.
  ([rollback thread](https://community.hubitat.com/t/you-can-rollback/81831))

So **check + apply + status are all scriptable today** (via `/hub/cloud/*`, shipped as
`hub_update_firmware`); only rollback stays UI/manual. Hubitat does not publish official endpoint
docs — these are community-reverse-engineered and may change across firmware.

## Live-hub captures needed (only the maintainer can get these)

Open DevTools (F12) → Network, against the hub on **:8080**, then:

1. **Settings → Check for Updates** — capture the call(s). Is there a local check endpoint, or is it
   purely cloud?
2. **Update Hub** — capture `POST /management/firmwareUpdate` exact params/body, and the
   `firmwareUpdateStatus` response schema (what fields mean in-progress / done / failed).
3. **Diagnostic Tool → Restore Previous Version** — does it make any HTTP call, or is it pure UI?
   (Decides whether rollback can ever be automated.)
4. Rough hub-unreachable duration during an update (sets the poll window), and whether
   `enableCustomRuleEngine` / other toggles reset after an update.

## Firmware-sensitive code surface (the regression surface to re-test)

Priority order — what a post-update e2e must assert (all degrade *gracefully* today; the risk is a
future firmware silently changing a shape so the wrong path is taken):

1. **CRITICAL — radio dual-endpoint fallback.** Z-Wave `/hub/zwaveDetails/json` (FW ≥ 2.3.7.1) vs
   `/hub2/zwaveInfo`; zigbee analog. `:10446-10465`, `:10493-10527`. Re-test `hub_get_radio_details`.
2. **HIGH — `/hub2/appsList` shape** (app listing + Hub Variables discovery). `:7115-7210`,
   `:14597-14606`. Re-test `hub_list_apps`, hub-variable tools.
3. **HIGH — Boolean toggle reset on upgrade** (`enableRuleEngine`→`enableCustomRuleEngine` flip; the
   one the code already warns about). `:342-363`, `:164`. Verify the one-time migration guard + that
   the toggle stays as the user set it.
4. **MEDIUM — `/hub2/userAppTypes` shape** (HPM drift). `:15116-15143`. Re-test `hub_get_hpm_drift`.
5. **MEDIUM — timestamp parsing** (6 ISO-8601 variants). `:196-218`.
6. **LOW — device radio-state warnings** `:13557-13589`; **HPM statusJson double-decode** `:14879`;
   **File Manager availability** `:9398-9403`.

## Rollback caveat (important)

A Hubitat **hub backup restores the database/config, not the platform version.** So
`hub_create_backup`/`hub_restore_backup` cannot downgrade firmware. If a new firmware *itself* breaks
the MCP surface, that's a **manual** Diagnostic-Tool rollback + a loud alert — not something we can
automate today. Any maintenance job's auto-rollback protects config, **not** the firmware.

## Proposed resilience plan (phased, ~10h total)

Builds on the existing 30-min lease (`lease_acquire.sh`) + importUrl deploy model.

- **Phase 1 — firmware visibility (~1-2h, no captures needed).** Record `hub_get_info.firmwareVersion`
  on every e2e run; track in a git-tracked JSON (e.g. `docs/e2e-firmware-versions.json`); warn on
  drift in `mcp_setup_env.sh`. Always know what firmware we tested against. Integrate in
  `hub-e2e.yml` (post-test) + `tests/e2e_test.py` (startup).
- **Phase 2 — scheduled firmware-maintenance job (~4-6h).** New
  `.github/workflows/firmware-maintenance.yml` (weekly cron + `workflow_dispatch`, **separate
  concurrency group** from PR e2e, ~120-min timeout to cover lease + multi-minute reboot): acquire →
  `hub_create_backup` → apply update → poll `/hub/advanced/freeOSMemory`/`hub_get_info` with backoff
  until the hub is back → run the **full e2e** → on failure, restore config + **alert** (firmware
  rollback stays manual) → release lease. **Auto-apply on schedule** is the right model: firmware
  reaches users regardless, so deferring only delays *our* detection.
- **Phase 3 — gated tooling — SHIPPED (#259) as `hub_update_firmware`.** Apply + status-poll, flat
  in the hub-self-management cluster, confirm + 24h-backup gated, on the token-free `/hub/cloud/*`
  family (NOT the `/management/firmwareUpdate` this phase originally guessed). The check read is
  `hub_get_info` (`platformUpdate`) plus the tool's own live `checkForUpdate`.
- **Phase 4 — rollback.** Config rollback rides on existing `hub_restore_backup`; **firmware**
  rollback is manual (see caveat).

### Integration points (existing)

- Lease: `.github/scripts/lease_acquire.sh` (`get_lease_value` strict-parse); cross-PR runs are
  serialized by the `hub-e2e-serialized` concurrency group (`hub-e2e.yml` e2e job, `queue: max`)
  plus the workflow-level per-branch cancel group. The lease is the hub-side lock for a human-held hub.
- 5xx-tolerant deploy/verify pattern to mirror for reboot polling:
  `mcp_deploy_source.sh:187-213`, `mcp_verify_deploy.sh:102-118`.
- Destructive-ops gateway: `hubitat-mcp-server.groovy`.
- e2e is PR-triggered + manual only today; **no scheduled job exists** (`hub-e2e.yml:10-26`).

## Open questions (needs live hub)

- ~~Exact apply request/response + status schema~~ — RESOLVED (#259): the `/hub/cloud/*` family
  (token-free) is the shipped path; `checkForUpdate` → `{version,upgrade,status,releaseNotesUrl,beta,hubCount,accountEmails}`, `checkUpdateStatus` → `{status:"IDLE"|...}`. (`/management/*` is unused.)
- ~~Whether any local "update available" endpoint exists~~ — RESOLVED: `GET /hub/cloud/checkForUpdate`.
- Whether "Restore Previous Version" makes an HTTP call (automatable?) or is pure UI.
- Reboot/unreachable duration; whether MCP endpoints stay up during the update.
- Whether toggles reset post-update on current firmware.

## Sources

- Release 2.3.9 (management endpoints): https://community.hubitat.com/t/release-2-3-9-available/138312
- Platform update notifications: https://community.hubitat.com/t/platform-update-notifications/163797
- Rollback: https://community.hubitat.com/t/you-can-rollback/81831
- Update-from-dashboard / port 8080: https://community.hubitat.com/t/endpoint-to-update-hub-from-dashboard-button/145028
- Stuck checking for updates: https://community.hubitat.com/t/hub-stuck-on-checking-for-updates/49913
