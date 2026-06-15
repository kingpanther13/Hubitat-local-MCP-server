# Hub firmware update — research notes & resilience plan

Status: **research only, not implemented.** Compiled (AI-assisted) from Hubitat community
reverse-engineering, the HPM source, and this repo's code. Captured so we can act on it later;
several exact details are flagged as needing a live-hub network capture.

## Why this matters

A real-hub e2e exists to catch breakage when the platform changes under us. Hubitat ships firmware
to users, users apply it, and our code branches on firmware behaviour in several places (radio
endpoints, `/hub2/*` shapes, a known toggle-reset quirk). So the test hub must track firmware and be
**re-validated on every bump** — to find regressions before users do. The goal is not "pin to old" —
it's "stay current and re-test."

## Current state

We have **no** firmware check / apply / rollback capability.

- The only firmware **read** is `hub_get_info.firmwareVersion`, from `hub.firmwareVersionString`
  (`hubitat-mcp-server.groovy`). It is displayed, never compared.
- `hub_get_update_status` is **not** firmware — it checks GitHub for a newer *MCP Rule Server app*
  version (`:2925`).
- All firmware-conditional code uses endpoint-existence/fallback, not version comparison.

## Hub platform-update endpoints (firmware 2.3.9.176+)

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

- **Check-for-available-update** — cloud-driven (`cloud.hubitat.com`); surfaced via the UI bell icon.
  No documented local "is an update available" JSON endpoint.
  ([notifications thread](https://community.hubitat.com/t/platform-update-notifications/163797))
- **Rollback** — UI-only via the Diagnostic Tool "Restore Previous Version"; no documented HTTP
  endpoint. Only the current build + 1–2 prior minors are retained.
  ([rollback thread](https://community.hubitat.com/t/you-can-rollback/81831))

So **apply + status are scriptable today**; check-available and rollback are cloud/UI and need a
capture (below) or stay manual. Hubitat does not publish official endpoint docs — these are
community-reverse-engineered and may change across firmware.

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
- **Phase 3 — gated tooling (~2-3h).** A read-only `hub_get_platform_update` (check) if a local
  endpoint is confirmed; and `hub_update_platform` (apply) under `hub_manage_destructive_ops` —
  confirm + 24h backup + `enableDeveloperMode` + a loud downtime warning — wrapping
  `/management/firmwareUpdate`. Reuse existing backup tools.
- **Phase 4 — rollback.** Config rollback rides on existing `hub_restore_backup`; **firmware**
  rollback is manual (see caveat).

### Integration points (existing)

- Lease: `.github/scripts/lease_acquire.sh:21-22,68-75`; serialized via `hub-e2e.yml:37-39`
  concurrency group.
- 5xx-tolerant deploy/verify pattern to mirror for reboot polling:
  `mcp_deploy_source.sh:187-213`, `mcp_verify_deploy.sh:102-118`.
- Destructive-ops gateway: `hubitat-mcp-server.groovy`.
- e2e is PR-triggered + manual only today; **no scheduled job exists** (`hub-e2e.yml:10-26`).

## Open questions (needs live hub)

- Exact `/management/firmwareUpdate` request/response and `firmwareUpdateStatus` schema.
- Whether any local "update available" endpoint exists, or it's cloud-only.
- Whether "Restore Previous Version" makes an HTTP call (automatable?) or is pure UI.
- Reboot/unreachable duration; whether MCP endpoints stay up during the update.
- Whether toggles reset post-update on current firmware.

## Sources

- Release 2.3.9 (management endpoints): https://community.hubitat.com/t/release-2-3-9-available/138312
- Platform update notifications: https://community.hubitat.com/t/platform-update-notifications/163797
- Rollback: https://community.hubitat.com/t/you-can-rollback/81831
- Update-from-dashboard / port 8080: https://community.hubitat.com/t/endpoint-to-update-hub-from-dashboard-button/145028
- Stuck checking for updates: https://community.hubitat.com/t/hub-stuck-on-checking-for-updates/49913
