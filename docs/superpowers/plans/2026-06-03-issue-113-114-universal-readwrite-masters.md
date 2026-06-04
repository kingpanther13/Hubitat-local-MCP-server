# Universal Read/Write Masters + Per-tool Overrides — Implementation Plan (#113 + #114)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gate every MCP tool behind two universal Read/Write master toggles (default ON), remove the Built-in App toggle, and add an advanced sub-page for per-tool/per-gateway deny-only overrides.

**Architecture:** A central classification-driven gate at the top of `executeTool()` (the single dispatch chokepoint) replaces ~28 scattered per-handler gates. Three new shared helpers — `getCustomEngineMode()`, `getEffectiveDisabledTools()`, `getHiddenToolNames()` — are the single source of truth consumed by both `getToolDefinitions()` (catalog) and `toolSearchTools()` (search corpus), eliminating their current drift. `confirm`+24h-backup stays on exactly the 30 tools that have it today (via a renamed `requireDestructiveConfirm`).

**Tech Stack:** Hubitat Groovy 2.4 sandbox (`hubitat-mcp-server.groovy`), Spock 2.3 unit tests (`src/test/groovy/`), Python e2e (`tests/e2e_test.py`), `tests/sandbox_lint.py`.

**Worktree:** `C:\Users\amcca\Desktop\Homeassistant optimization\hubitat-mcp-issue113-114`, branch `feat/issue-113-114-toggle-overrides`, based on PR #233 head `dec33b26`. Commit identity is `kingpanther13 <kingpanther13@users.noreply.github.com>` (already configured). Spec: `docs/superpowers/specs/2026-06-03-issue-113-114-universal-readwrite-masters-design.md`.

**Conventions for every task:**
- Run a single focused spec while iterating: `./gradlew test --tests "<Spec>"`. Run the **full** suite only at Task 17. (Per project note: do NOT run the full gradle suite mid-flight; let the final run / CI cover it.)
- Spock harness loads the app file at runtime (HubitatAppSandbox). A bare `./gradlew test` reports cached UP-TO-DATE after app-file edits — use `./gradlew cleanTest test --tests "<Spec>"` when re-running the same spec after editing the `.groovy`.
- Commit after each task with `GIT_COMMITTER_EMAIL="kingpanther13@users.noreply.github.com" GIT_COMMITTER_NAME="kingpanther13" git commit --author="kingpanther13 <kingpanther13@users.noreply.github.com>" -m "<msg>"` and end the message with a blank line then `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Never touch version strings / CHANGELOG / `packageManifest.json` (pr_guard.py blocks them).

**Open implementation decision (confirm with maintainer before Task 1):** This plan **renames** the master setting keys `enableHubAdminRead`→`enableRead` and `enableHubAdminWrite`→`enableWrite` (per spec D2). The alternative is to **keep** those keys and only relabel the UI + rescope the gating, which avoids renaming ~125 test seeds across ~10 spec files but leaves a slightly misleading internal key name. If the maintainer prefers keep-keys, skip the key-string renames in Tasks 1 & 13–16 (the gate logic, UI labels, #114, and behavior are otherwise identical). The plan below assumes the rename.

---

## File Structure

**Modified — `hubitat-mcp-server.groovy`:**
- `preferences{}` (L32-36) — register the new `advancedOverridesPage`.
- `mainPage()` (L38-194) — replace "Hub Admin Access" section with universal Read/Write masters; remove "Built-in App Integration" section; add an "Advanced" `href` in the "Settings" section.
- New `advancedOverridesPage()` function — the #114 sub-page.
- `appButtonHandler(btn)` (L275-294) — add the reset-overrides branch.
- New helpers: `getCustomEngineMode()`, `getEffectiveDisabledTools()`, `getHiddenToolNames()`.
- `getToolDefinitions()` (L1706-1826) — replace inline `hideByName` build with `getHiddenToolNames()`.
- `toolSearchTools()` (L26252-26290) — replace inline `searchHideByName` build with `getHiddenToolNames()`.
- `executeTool()` (L5057+) — add central master gate + advanced-disabled gate; rederive `customEngineMode` via helper.
- Gate functions (L8900-8936) — delete `requireHubAdminRead`/`requireBuiltinApp`; rename `requireHubAdminWrite`→`requireDestructiveConfirm` (drop toggle check).
- 15 `requireHubAdminRead()` + 13 `requireBuiltinApp()` call sites deleted; 30 `requireHubAdminWrite(...)`→`requireDestructiveConfirm(...)`.
- `toolUpdateMcpSettings()` allowlist (L7577-7596) + tool description (L2552) + searchHints (L1317-1319).

**Modified — tests:** see Tasks 8 (new), 11-16. **Docs:** AGENTS.md/CLAUDE.md, SKILL.md, TOOL_GUIDE.md, agent-skill/hubitat-mcp/safety-guide.md, README.md, SECURITY.md (Task 14). **e2e/BAT:** `tests/e2e_test.py`, `tests/BAT-v2.md` (Tasks 15-16).

---

## Phase 1 — Universal masters + central gate (#113)

### Task 1: Rename master keys, relabel UI, default ON; rederive custom-engine mode

**Files:**
- Modify: `hubitat-mcp-server.groovy` — section "Hub Admin Access" (67-81), gate fn bodies (8903-8936 interim), add `getCustomEngineMode()`.

- [ ] **Step 1: Replace the "Hub Admin Access" section** (L67-81) with the universal masters:

```groovy
        section("Tool Access (Read / Write masters)") {
            paragraph "<b>Read</b> exposes every read-only / non-destructive MCP tool. <b>Write</b> exposes every tool that changes hub or user state. Both default ON; turn one OFF to remove that entire class of tools from the MCP client and reject any cached call. Fine-grained per-tool control lives under <i>Advanced: Per-tool Overrides</i> below."
            input "enableRead", "bool", title: "Enable Read Tools",
                  description: "Expose all read-only tools (list/get/search/diagnostics). Turn OFF for a write-only or fully-locked client.",
                  defaultValue: true, submitOnChange: true
            input "enableWrite", "bool", title: "Enable Write Tools",
                  description: "Expose all state-changing tools (device control, modes, variables, rooms, files, native rules, hub admin). Destructive tools additionally require confirm=true + a recent backup.",
                  defaultValue: true, submitOnChange: true
            if (settings.enableWrite == false) {
                paragraph "<i>Write tools are OFF — the MCP client sees only read tools.</i>"
            }
        }
```

- [ ] **Step 2: Add `getCustomEngineMode()` helper** immediately above `getReadOnlyToolNames()` (before L1435):

```groovy
// Single source of truth for the legacy custom-rule engine's visibility mode.
// "full"     -- engine ON; all custom_* tools shown.
// "readonly" -- engine OFF + Read master ON; read custom_* shown, write custom_* hidden.
// "off"      -- engine OFF + Read master OFF; all custom_* hidden.
// (Pre-#113 the "readonly" trigger was the Built-in App toggle; with that toggle
// removed it is the Read master -- if the client can read at all, it can read existing
// custom rules.) Consumed by getHiddenToolNames(), executeTool, and toolSearchTools.
def getCustomEngineMode() {
    if (settings.enableCustomRuleEngine == true) return "full"
    return (settings.enableRead != false) ? "readonly" : "off"
}
```

- [ ] **Step 3: Update the interim gate fn bodies** so the suite stays green before the central gate lands. In `requireHubAdminRead()` (L8904) change `settings.enableHubAdminRead` → `settings.enableRead == false ? false : true` is wrong — instead make it default-ON aware:

```groovy
def requireHubAdminRead() {
    if (settings.enableRead == false) {
        throw new IllegalArgumentException("Read tools are disabled. Enable 'Read Tools' in MCP Rule Server app settings to use this tool.")
    }
}
```

In `requireHubAdminWrite(Boolean confirmParam)` (L8925) change the first guard:

```groovy
    if (settings.enableWrite == false) {
        throw new IllegalArgumentException("Write tools are disabled. Enable 'Write Tools' in MCP Rule Server app settings to use this tool.")
    }
```

(Leave the `confirm`/backup checks in that function untouched for now — they move out in Task 4.)

- [ ] **Step 4: Run the spec that exercises the read gate to confirm green**

Run: `./gradlew cleanTest test --tests "HandleToolsCallSpec"`
Expected: this spec sets `settingsMap.enableHubAdminRead = false` (L37) and asserts a `-32602` with "Hub Admin Read" — it will FAIL on the renamed key + message. Update L37 to `settingsMap.enableRead = false`, L70 to `settingsMap.enableRead = true`, and the message assertion `message.contains("Hub Admin Read")` → `message.contains("Read tools are disabled")`. Re-run: PASS.

- [ ] **Step 5: Commit**

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/HandleToolsCallSpec.groovy
git commit -m "refactor(#113): rename Hub Admin Read/Write masters to universal Read/Write (default ON)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 2: Central master gate in executeTool

**Files:**
- Modify: `hubitat-mcp-server.groovy` — top of `executeTool()` (insert before the custom-engine block at L5058).
- Test: `src/test/groovy/server/ExecuteToolMasterGateSpec.groovy` (create).

- [ ] **Step 1: Write the failing test** — create `src/test/groovy/server/ExecuteToolMasterGateSpec.groovy`:

```groovy
package server

import support.ToolSpecBase

class ExecuteToolMasterGateSpec extends ToolSpecBase {

    def "read tool is blocked when Read master is OFF"() {
        given:
        settingsMap.enableRead = false
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_list_modes", [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Read tools are disabled")
    }

    def "write tool is blocked when Write master is OFF"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false

        when:
        script.executeTool("hub_set_mode", [mode: "Day"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Write tools are disabled")
    }

    def "both masters default ON (unset) -- neither read nor write is blocked by the master gate"() {
        given:
        settingsMap.remove('enableRead')
        settingsMap.remove('enableWrite')

        expect: "the master gate does not throw (a read tool dispatches past it)"
        script.getReadOnlyToolNames().contains("hub_list_modes")
        script.executeTool("hub_list_modes", [:]) != null
    }

    def "gateway name is NOT classified as a write -- read gateway dispatches with Write OFF"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false

        when: "calling a pure-read gateway with no sub-tool returns its catalog, not a write-block"
        def result = script.executeTool("hub_read_devices", [:])

        then:
        noExceptionThrown()
        result != null
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "ExecuteToolMasterGateSpec"`
Expected: FAIL — the master gate does not exist yet, so `hub_list_modes` does not throw when `enableRead=false`.

- [ ] **Step 3: Insert the central gate** at the very top of `executeTool(toolName, args)`, immediately after the `def executeTool(toolName, args) {` line (L5057) and BEFORE the existing custom-engine comment block:

```groovy
def executeTool(toolName, args) {
    // ---- Universal Read/Write master gate (issue #113) ----
    // Gateway NAMES are not leaf tools: they route to handleGateway (see switch
    // below) which re-enters executeTool per sub-tool, so the sub-tool is gated on
    // re-entry. Classifying a gateway name here would misfire (a hub_read_* gateway
    // is not in getReadOnlyToolNames()). Masters default ON -- only an explicit
    // `== false` blocks (null/unset => allowed).
    def isGatewayName = getGatewayConfig().containsKey(toolName)
    if (!isGatewayName) {
        if (getReadOnlyToolNames().contains(toolName)) {
            if (settings.enableRead == false) {
                throw new IllegalArgumentException("Read tools are disabled. Enable 'Read Tools' in MCP Rule Server app settings to use ${toolName}.")
            }
        } else if (settings.enableWrite == false) {
            throw new IllegalArgumentException("Write tools are disabled. Enable 'Write Tools' in MCP Rule Server app settings to use ${toolName}.")
        }
    }
```

(The existing `// Custom Rule Engine gate.` block continues directly after.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests "ExecuteToolMasterGateSpec"`
Expected: PASS (all four cases).

- [ ] **Step 5: Commit**

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/ExecuteToolMasterGateSpec.groovy
git commit -m "feat(#113): central Read/Write master gate at executeTool dispatch chokepoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3: Remove redundant per-handler requireHubAdminRead

**Files:**
- Modify: `hubitat-mcp-server.groovy` — delete `requireHubAdminRead()` def (L8900-8907) and its 15 call sites.

The 15 call sites (each the line `    requireHubAdminRead()`): L10293, 10343, 10388, 10435, 10485, 11028, 11166, 11277, 11373, 11725, 11931, 12154, 12992, 14900, 15056. (Lines 12150, 14897, 15053 are docblock comments — update those comment lines too: replace `requireHubAdminRead()` with `Read master` in the prose.)

- [ ] **Step 1: Delete the 15 call-site lines.** For each listed line, remove the entire `    requireHubAdminRead()` line. (`toolForceGarbageCollection` @ L11373 was gated read despite `hub_call_gc` being a write — deleting it lets the central gate correctly classify it as a Write tool. Note this in the commit.)

- [ ] **Step 2: Delete the function definition** (L8900-8907, the docblock + `def requireHubAdminRead() {...}`).

- [ ] **Step 3: Update the 3 docblock comments** at L12150, L14897, L15053 — change `Gate: requireHubAdminRead() -- ...` to `Gate: Read master (central) -- ...`.

- [ ] **Step 4: Run the read-gate OFF-state specs and fix their key + message asserts.** These specs assert "throws when Hub Admin Read is disabled" — they now rely on the central gate. Edit each: change the seed `settingsMap.enableHubAdminRead = false` → `settingsMap.enableRead = false`, any `= true` → `enableRead = true`, and message asserts `contains("Hub Admin Read")` → `contains("Read tools are disabled")`:
  - `ToolGetAppConfigSpec.groovy` (seeds L98/L112; ~50 inline `enableHubAdminRead = true`; message asserts in the two OFF tests).
  - `ToolGetHubLogsSpec.groovy` (seeds L122/L136; ~60 inline `enableHubAdminRead = true`).
  - `ToolAppsDriversSpec.groovy` (helper L32-33 body; comment L15).
  - `ToolLibraryCodeSpec.groovy` (read helper L51-52 body).
  - `RegressionsFromHistorySpec.groovy` (L194/220/261/281).
  - `McpRequestDriverSpec.groovy` (L140).
  - `HubInfoFieldContractSpec.groovy` (comment L195 only).

  Use a verified scripted replace, then inspect the two OFF-state message asserts by hand. Example for one file:

```bash
python - <<'PY'
import re,io
for f in ["src/test/groovy/server/ToolGetAppConfigSpec.groovy","src/test/groovy/server/ToolGetHubLogsSpec.groovy","src/test/groovy/server/ToolAppsDriversSpec.groovy","src/test/groovy/server/ToolLibraryCodeSpec.groovy","src/test/groovy/server/RegressionsFromHistorySpec.groovy","support/.." ]:
    pass
PY
```

(Practical approach: open each file, replace `enableHubAdminRead` → `enableRead` globally in that file, then update the two/one OFF-state `.message.contains("Hub Admin Read")` asserts to `"Read tools are disabled"`. Verify with `git grep -n "enableHubAdminRead" src/test` returning zero after all read-gate specs are done in this task.)

- [ ] **Step 5: Run the affected specs, then commit**

Run: `./gradlew cleanTest test --tests "ToolGetAppConfigSpec" --tests "ToolGetHubLogsSpec" --tests "ToolAppsDriversSpec" --tests "ToolLibraryCodeSpec" --tests "HandleToolsCallSpec"`
Expected: PASS.

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/*.groovy
git commit -m "refactor(#113): drop redundant per-handler requireHubAdminRead (central gate covers reads)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4: requireHubAdminWrite → requireDestructiveConfirm (keep confirm+backup on the 30)

**Files:**
- Modify: `hubitat-mcp-server.groovy` — rename fn (L8922-8936) + 30 call sites.
- Test: `src/test/groovy/server/ToolDestructiveHubOpsSpec.groovy`, `ToolHubVariablesSpec.groovy`.

- [ ] **Step 1: Rename the function and drop its toggle guard** (L8922-8936) to:

```groovy
/**
 * Destructive-tier confirmation gate: confirm=true + a hub backup within 24h.
 * Orthogonal to the Read/Write masters (the Write master is enforced centrally in
 * executeTool). Applied only to the destructive/sensitive write tools that required
 * it before the #113 master collapse -- ordinary writes need only the Write master.
 */
def requireDestructiveConfirm(Boolean confirmParam) {
    if (!confirmParam) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: You must set confirm=true to use this tool. Did you create a backup with hub_create_backup first? Review the tool description for the mandatory pre-flight checklist, or call hub_get_tool_guide for the tool's full reference.")
    }
    // Check for recent hub backup (within 24 hours)
    if (!state.lastBackupTimestamp || (now() - state.lastBackupTimestamp) > 86400000) {
        throw new IllegalArgumentException("BACKUP REQUIRED: No hub backup found within the last 24 hours. You MUST call hub_create_backup FIRST and verify it succeeds before using this tool. Last backup: ${state.lastBackupTimestamp ? formatTimestamp(state.lastBackupTimestamp) : 'Never'}")
    }
}
```

- [ ] **Step 2: Rename all 30 call sites** `requireHubAdminWrite(` → `requireDestructiveConfirm(` (arg unchanged). Sites: L7168, 7282, 7352, 7429, 7571, 9148, 9505, 9569, 11649, 11673, 11697, 12275, 12312, 12515, 12767, 12859, 13079, 13224, 13367, 13450, 13730, 13906, 14359, 14408, 14480, 22230, 23969, 25254, 25619, 25858. Verify with `git grep -n "requireHubAdminWrite" hubitat-mcp-server.groovy` returning zero.

- [ ] **Step 3: Update the write-gate OFF-state spec asserts.** The destructive-ops / hub-variables specs have a first-layer OFF test asserting "Hub Admin Write" in the message — that path is now the central Write master, not this function. Rework them:
  - `ToolDestructiveHubOpsSpec.groovy`: helper L28-29 body `settingsMap.enableHubAdminWrite = true` → `enableWrite = true`; the OFF-state test (~L37, no seed) asserting `message.contains("Hub Admin Write")` for `hub_reboot` → seed `settingsMap.enableWrite = false` and assert `message.contains("Write tools are disabled")`. The backup-layer test (L59, seeds write=true, no `lastBackupTimestamp`) keeps asserting the `BACKUP REQUIRED` message (now from `requireDestructiveConfirm`).
  - `ToolHubVariablesSpec.groovy`: helper L30-31 body key rename; OFF test (L360, no seed) → seed `enableWrite = false`, assert `"Write tools are disabled"`; backup-layer test (L348-349) unchanged except helper key.
  - `ToolAppDriverCodeSpec.groovy`: helper L45-46 body key rename (`settingsMap.enableHubAdminWrite = true` → `enableWrite = true`). ~158 call sites unchanged (they call the helper).
  - `ToolLibraryCodeSpec.groovy`: write helper L55-56 body key rename.
  - `RegressionsFromHistorySpec.groovy`: L363/401/504/539/592/671/708 `enableHubAdminWrite = true` → `enableWrite = true`.

- [ ] **Step 4: Run the affected specs**

Run: `./gradlew cleanTest test --tests "ToolDestructiveHubOpsSpec" --tests "ToolHubVariablesSpec" --tests "ToolAppDriverCodeSpec" --tests "ToolLibraryCodeSpec"`
Expected: PASS. Verify `git grep -n "enableHubAdminWrite" src/test` is zero.

- [ ] **Step 5: Commit**

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/*.groovy
git commit -m "refactor(#113): split requireHubAdminWrite into requireDestructiveConfirm (confirm+backup) + central Write master

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 5: Remove the Built-in App toggle

**Files:**
- Modify: `hubitat-mcp-server.groovy` — delete `requireBuiltinApp()` def (L8909-8920) + 13 call sites; delete the "Built-in App Integration" section (L83-90); fix the Settings-section `builtinAppEnabled`/`readonlyNote` prose (L154-170).
- Test: `ToolListInstalledAppsSpec`, `ToolListRmRulesSpec`, `ToolGetDeviceInUseBySpec`, `ToolPauseRmRuleSpec`, `ToolResumeRmRuleSpec`, `ToolRunRmRuleSpec`, `ToolSetRmRuleBooleanSpec`, `RuleNotFoundRedirectSpec`, `GatewayToggleSpec`, `SendRmActionFallbackSpec`, `UpdateNativeAppSchemaTrimSpec`, `ToolRmNativeCrudSpec`, `ToolCustomRuleLifecycleSpec`.

- [ ] **Step 1: Delete the 13 `requireBuiltinApp()` call sites** (L14549, 14656, 15412, 15604, 15708, 15739, 22229, 23968, 25253, 25310, 25618, 25717, 25857) and the function definition (L8909-8920). After this, those tools are governed solely by the central master gate (RM reads → Read; RM run/pause/resume/boolean + native CRUD → Write; native CRUD additionally keeps `requireDestructiveConfirm` from Task 4).

- [ ] **Step 2: Delete the "Built-in App Integration (beta)" section** (L83-90 in `mainPage()`).

- [ ] **Step 3: Fix the Settings-section custom-rule notice** (L154-170): it reads `settings.enableBuiltinApp`. Replace `def builtinAppEnabled = settings.enableBuiltinApp == true` with `def readEnabled = settings.enableRead != false` and the `readonlyNote` ternary's `builtinAppEnabled` → `readEnabled`, and reword its two branches:

```groovy
            def readEnabled = settings.enableRead != false
            if (existingRuleCount > 0 && !customEngineExplicitlyOn) {
                def readonlyNote = readEnabled ? " your AI can still SEE these rules (<code>hub_get_custom_rule</code>) and toggle them enabled/disabled, but cannot create, modify structure, or delete." : " With the Read master also OFF, all custom_* tools are hidden from your AI."
```

- [ ] **Step 4: Rework the affected specs.** For each "throws when Built-in App is disabled" gate test, re-point to the correct master per the tool's read/write nature and update the message assert to the central-gate text:
  - `ToolListInstalledAppsSpec` (L25/L45 OFF tests): `hub_list_apps` scope=instances is a **read** → seed `enableRead = false`, assert `-32602` / `"Read tools are disabled"`. Remove ~36 `enableBuiltinApp = true` seeds (harmless if left, but clean them).
  - `ToolListRmRulesSpec` (L30/L44): `hub_list_rules` is a **read** → `enableRead = false`, `"Read tools are disabled"`.
  - `ToolGetDeviceInUseBySpec` (L21/L84): `hub_list_device_dependents` is a **read** → `enableRead = false`.
  - `ToolPauseRmRuleSpec`/`ToolResumeRmRuleSpec` (toolSetRulePaused) and `ToolRunRmRuleSpec` (toolRunRmRule/`hub_call_rule`) and `ToolSetRmRuleBooleanSpec` (`hub_set_rule_private_boolean`): all **writes** → seed `enableWrite = false`, assert `"Write tools are disabled"`.
  - `RuleNotFoundRedirectSpec`: the gate-off scenario (L220/L222, `enableBuiltinApp` absent) was "built-in disabled → no redirect/info-leak". `hub_get_custom_rule` is a read; rework to seed `enableRead = false` (read master off) and keep asserting no redirect. Bulk `enableBuiltinApp = true` seeds → drop (masters default ON).
  - `GatewayToggleSpec`: the OFF-state test L203 ("useGateways=false + builtin off → native tools hidden in flat catalog") → re-point to the new hiding driver. With Built-in App removed, native tools are hidden only when the **Write master** is OFF (run/pause/etc. are writes) or Read master OFF (list_rules etc. are reads). Replace the single `enableBuiltinApp=false` seed (L209) with two cases or seed `enableWrite=false` and assert the write native tools (`hub_create_native_app`, `hub_call_rule`, ...) are absent while reads remain — re-pin the asserted name lists accordingly. The hint test L286 → re-point its seed and the `result.hint.contains("Built-in App Tools")` assertion to `contains("Write tools")` (whatever the new hidden-gateway hint emits).
  - `SendRmActionFallbackSpec` (L44), `UpdateNativeAppSchemaTrimSpec` (L38): drop the single `enableBuiltinApp = true` seed.
  - `ToolCustomRuleLifecycleSpec` (L362-365 readonly test): readonly now = engine OFF + Read ON. Replace `enableBuiltinApp=true` (L364) with `enableRead=true`; keep `enableCustomRuleEngine=false`, `useGateways=true`; assertions unchanged.
  - `ToolRmNativeCrudSpec`: collapse the 3-state custom-engine matrix. The readonly discriminator changes from `enableBuiltinApp` to `enableRead`. L9806 full (engine ON) → seed `enableCustomRuleEngine=true` (drop `enableBuiltinApp=false`), all 8 custom_* visible. L9825 readonly (engine OFF + `enableRead=true`, drop `enableBuiltinApp=true`) → 3 read visible / 5 write hidden. L9847 off (engine OFF + `enableRead=false`) → all 8 hidden. L5414 "addTrigger discover works with gates off" removes all keys (5416-5418) — drop the `enableBuiltinApp` remove (5416), keep the read/write removes renamed to `enableRead`/`enableWrite`; discover is a read so add `enableRead=true` if the test needs it visible. L10257/10278/10301/10321/10341 readonly/off executeTool+search tests: re-point `enableBuiltinApp` discriminator → `enableRead`.

- [ ] **Step 5: Run the affected specs, then commit**

Run: `./gradlew cleanTest test --tests "ToolListInstalledAppsSpec" --tests "ToolListRmRulesSpec" --tests "ToolGetDeviceInUseBySpec" --tests "ToolPauseRmRuleSpec" --tests "ToolResumeRmRuleSpec" --tests "ToolRunRmRuleSpec" --tests "ToolSetRmRuleBooleanSpec" --tests "RuleNotFoundRedirectSpec" --tests "GatewayToggleSpec" --tests "ToolCustomRuleLifecycleSpec" --tests "ToolRmNativeCrudSpec"`
Expected: PASS. Verify `git grep -n "enableBuiltinApp\|requireBuiltinApp" hubitat-mcp-server.groovy src/test` is zero.

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/*.groovy
git commit -m "refactor(#113): remove Built-in App toggle; fold its tools under Read/Write masters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 6: Extract getHiddenToolNames(); master-off hides from catalog + search

**Files:**
- Modify: `hubitat-mcp-server.groovy` — add `getHiddenToolNames()`; rewrite `getToolDefinitions()` hideByName build (L1707-1750) and `executeTool` custom-engine block (L5064-5066) and `toolSearchTools` searchHideByName build (L26257-26276) to use the helpers.
- Test: `src/test/groovy/server/MasterVisibilitySpec.groovy` (create).

- [ ] **Step 1: Write the failing test** — `src/test/groovy/server/MasterVisibilitySpec.groovy`:

```groovy
package server

import support.ToolSpecBase

class MasterVisibilitySpec extends ToolSpecBase {

    def "Read master OFF hides every read tool from the gateway catalog and collapses pure-read gateways"() {
        given:
        settingsMap.enableRead = false
        settingsMap.enableWrite = true
        settingsMap.useGateways = true

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name

        then: "pure-read gateways drop entirely; a read base tool is gone"
        !names.contains("hub_read_devices")
        !names.contains("hub_list_modes")
        and: "a write-bearing gateway remains"
        names.contains("hub_manage_destructive_ops")
    }

    def "Write master OFF hides every write tool and flips a mixed gateway to read-only annotation"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        settingsMap.useGateways = true

        when:
        def tools = script.getToolDefinitions()
        def rooms = tools.find { it.name == "hub_manage_rooms" }

        then: "the mixed rooms gateway still shows (it has reads) and is now read-only"
        rooms != null
        rooms.annotations.readOnlyHint == true
        and: "a write-only gateway drops"
        !(tools*.name.contains("hub_manage_destructive_ops"))
    }

    def "both masters ON (default) -- full catalog of 30 in the default config"() {
        given:
        settingsMap.remove('enableRead')
        settingsMap.remove('enableWrite')
        settingsMap.enableCustomRuleEngine = true
        settingsMap.useGateways = true

        expect:
        script.getToolDefinitions().size() == 30
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "MasterVisibilitySpec"`
Expected: FAIL — masters do not yet feed `hideByName`.

- [ ] **Step 3: Add `getHiddenToolNames()`** immediately below `getCustomEngineMode()`:

```groovy
// Single source of truth for which tool NAMES are hidden from the catalog
// (getToolDefinitions) AND the search corpus (toolSearchTools). Combines the two
// universal masters, the legacy custom-engine mode, and the #114 advanced overrides.
// A name in this set disappears from every surface, so the two consumers cannot drift.
def getHiddenToolNames() {
    def hide = [] as Set
    def readOnly = getReadOnlyToolNames()
    // Masters default ON: only an explicit `== false` hides a class.
    if (settings.enableRead == false) hide.addAll(readOnly)
    if (settings.enableWrite == false) {
        getAllToolDefinitions().each { if (!readOnly.contains(it.name)) hide << (it.name as String) }
    }
    // Legacy custom-rule engine visibility.
    def mode = getCustomEngineMode()
    if (mode == "off") {
        ["hub_get_custom_rule", "hub_create_custom_rule", "hub_update_custom_rule", "hub_delete_custom_rule", "hub_test_custom_rule", "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"].each { hide << it }
    } else if (mode == "readonly") {
        ["hub_create_custom_rule", "hub_delete_custom_rule", "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"].each { hide << it }
    }
    // #114 advanced per-tool / per-gateway overrides (deny-only).
    hide.addAll(getEffectiveDisabledTools())
    return hide
}
```

(`getEffectiveDisabledTools()` is added in Task 9; until then add a temporary stub `def getEffectiveDisabledTools() { return [] as Set }` directly above `getHiddenToolNames()` — Task 9 replaces the stub body and removes this note.)

- [ ] **Step 4: Replace the inline `hideByName` build in `getToolDefinitions()`** (delete L1707-1750, the `builtinAppOn`/`customEngineOn`/`customEngineMode` locals + the three `if` hide blocks) with a single line at the top of the function:

```groovy
def getToolDefinitions() {
    def hideByName = getHiddenToolNames()
```

Keep everything from `def readOnlyNames = getReadOnlyToolNames()` (L1753) onward unchanged.

Then in `executeTool` replace the custom-engine locals (L5064-5066, the `customEngineOn`/`builtinAppOn`/`customEngineMode` three lines) with:

```groovy
    def customEngineMode = getCustomEngineMode()
```

and update the two custom-engine error strings (L5079, L5082) to drop the "Built-in App Tools" wording, e.g. L5079:

```groovy
            throw new IllegalArgumentException("${toolName} is not available. 'Enable Custom Rule Engine' is OFF and the Read master is OFF. Turn on Custom Rule Engine to use the legacy custom-rule tools (hub_*_custom_rule), or use native Hubitat Rule Machine via hub_manage_native_rules_and_apps.")
```

Finally in `toolSearchTools()` replace the inline `searchHideByName` build (L26257-26276) with:

```groovy
    def searchHideByName = getHiddenToolNames()
    def searchHideGwSubTools = [:].withDefault { [] as Set }
```

(This is the Boy-Scout fix: search now hides the same set the catalog does, closing the pre-existing drift where built-in/advanced-disabled tools leaked into search.)

- [ ] **Step 5: Run the test + the annotation/dispatch specs, then commit**

Run: `./gradlew cleanTest test --tests "MasterVisibilitySpec" --tests "McpToolAnnotationsSpec" --tests "HandleMcpRequestDispatchSpec" --tests "ToolSearchToolsSpec"`
Expected: `MasterVisibilitySpec` PASS. For `McpToolAnnotationsSpec`: remove the now-invalid `settingsMap.enableBuiltinApp` seeds (L22/40/58/101/143/162/268/301/414) — replace L268's `enableBuiltinApp = false` (the toggle-driven hiding test) with `settingsMap.enableWrite = false` (drives hiding via the Write master); the hard count `names.size() == 88` (L400) is UNCHANGED (no tool removed); the read/write classification snapshot (L310-357) is unchanged (drop only the `enableBuiltinApp = true` seed at L302). Fix and re-run to PASS.

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/*.groovy
git commit -m "feat(#113): getHiddenToolNames() single-source filter; masters hide from catalog + search

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 7: Read/Write classification completeness guard

**Files:**
- Test: `src/test/groovy/server/ToolClassificationCompletenessSpec.groovy` (create).

- [ ] **Step 1: Write the test** (it should pass immediately — it pins an invariant):

```groovy
package server

import support.ToolSpecBase

class ToolClassificationCompletenessSpec extends ToolSpecBase {

    def "every leaf tool is classified read or write, and the partition is disjoint"() {
        given:
        def all = script.getAllToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()

        expect: "no tool is missing a classification (every tool falls under a master)"
        all.size() > 0
        // readOnly is a subset of all (no stale names)
        (readOnly - all).isEmpty()
        // the write set is exactly the complement -- nothing is unclassified
        def writes = all - readOnly
        (readOnly.intersect(writes)).isEmpty()
        (readOnly + writes) == all
    }

    def "no gateway NAME is in the read-only leaf set (gateways are gated per sub-tool)"() {
        given:
        def gatewayNames = script.getGatewayConfig().keySet()
        def readOnly = script.getReadOnlyToolNames()

        expect:
        gatewayNames.every { !readOnly.contains(it) }
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew cleanTest test --tests "ToolClassificationCompletenessSpec"`
Expected: PASS (the partition is total/disjoint by construction; this guards future tools).

- [ ] **Step 3: Commit**

```bash
git add src/test/groovy/server/ToolClassificationCompletenessSpec.groovy
git commit -m "test(#113): guard that every tool is classified read or write (no ungated tool)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — #114 advanced overrides

### Task 8: Advanced sub-page + disabled_tools/disabled_gateways inputs + reset

**Files:**
- Modify: `hubitat-mcp-server.groovy` — `preferences{}` (L32-36); add `href` in the Settings section; add `advancedOverridesPage()`; add reset branch in `appButtonHandler` (L275-294).
- Test: `src/test/groovy/server/AdvancedOverridesPageSpec.groovy` (create).

- [ ] **Step 1: Register the page** in `preferences{}` (L32-36):

```groovy
preferences {
    page(name: "mainPage")
    page(name: "confirmDeletePage")
    page(name: "confirmRegenerateTokenPage")
    page(name: "advancedOverridesPage")
}
```

- [ ] **Step 2: Add the `href`** into the "Settings" `section` (after the `loopGuardWindowSec` input, before the section's closing brace, ~L191):

```groovy
            href name: "advancedOverrides", page: "advancedOverridesPage",
                 title: "Advanced: Per-tool Overrides",
                 description: "Disable individual tools or whole gateways below the Read/Write masters (deny-only)."
```

- [ ] **Step 3: Add `advancedOverridesPage()`** (place after `confirmRegenerateTokenPage()`, ~L273). It builds the flat options from the live tool surface so they never drift:

```groovy
def advancedOverridesPage() {
    dynamicPage(name: "advancedOverridesPage", title: "Advanced: Per-tool Overrides") {
        section {
            paragraph "Deny-only fine-grained control. These selections are applied <b>below</b> the Read/Write masters: they can only turn things OFF, never re-enable something a master already hid. A disabled tool disappears from tools/list and hub_search_tools everywhere it appears (including shared tools in multiple gateways) and returns a clear error if a cached client still calls it; it remains documented in hub_get_tool_guide."
        }
        section("Disable whole gateways") {
            def gwNames = getGatewayConfig().keySet().sort()
            input "disabled_gateways", "enum", title: "Gateways to disable",
                  description: "Every tool inside a disabled gateway is hidden (including tools shared with other gateways).",
                  options: gwNames, multiple: true, required: false, submitOnChange: true
        }
        section("Disable individual tools") {
            def toolNames = getAllToolDefinitions()*.name.findAll { !getGatewayConfig().containsKey(it) }.sort()
            input "disabled_tools", "enum", title: "Tools to disable",
                  description: "Each tool is listed once; disabling it removes it from every gateway it belongs to.",
                  options: toolNames, multiple: true, required: false, submitOnChange: true
        }
        section {
            def dt = (settings.disabled_tools ?: []).size()
            def dg = (settings.disabled_gateways ?: []).size()
            paragraph "Currently disabling <b>${dt}</b> tool(s) and <b>${dg}</b> gateway(s)."
            input "resetOverridesBtn", "button", title: "Reset all overrides"
            href name: "backToMainFromAdvanced", page: "mainPage", title: "Back"
        }
    }
}
```

- [ ] **Step 4: Add the reset branch** to `appButtonHandler(btn)` (inside the function, as a new `else if`, ~L293):

```groovy
    } else if (btn == "resetOverridesBtn") {
        app.removeSetting("disabled_tools")
        app.removeSetting("disabled_gateways")
        mcpLog("info", "server", "Advanced per-tool overrides reset (disabled_tools + disabled_gateways cleared)")
    }
```

- [ ] **Step 5: Write + run the test, then commit** — `src/test/groovy/server/AdvancedOverridesPageSpec.groovy`:

```groovy
package server

import support.ToolSpecBase

class AdvancedOverridesPageSpec extends ToolSpecBase {

    def "advancedOverridesPage renders with tool + gateway multi-selects and a reset button"() {
        when:
        def page = script.advancedOverridesPage()

        then:
        page != null
        // The dynamicPage stub records inputs; assert the two override inputs exist.
        // (ToolSpecBase exposes capturedInputs via the page sandbox -- see docs/testing.md.)
        script.capturedInputNames().contains("disabled_tools")
        script.capturedInputNames().contains("disabled_gateways")
        script.capturedInputNames().contains("resetOverridesBtn")
    }
}
```

If `ToolSpecBase` lacks `capturedInputNames()`, instead assert the page object is non-null and the option lists are non-empty by calling the option-builder expressions directly:

```groovy
    def "override option lists are built from the live tool surface"() {
        expect:
        script.getGatewayConfig().keySet().size() == 19
        script.getAllToolDefinitions()*.name.findAll { !script.getGatewayConfig().containsKey(it) }.size() > 0
    }
```

Run: `./gradlew cleanTest test --tests "AdvancedOverridesPageSpec"`
Expected: PASS.

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/AdvancedOverridesPageSpec.groovy
git commit -m "feat(#114): advanced overrides sub-page with disabled_tools/disabled_gateways + reset

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 9: getEffectiveDisabledTools() — global semantics into the filter

**Files:**
- Modify: `hubitat-mcp-server.groovy` — replace the Task-6 stub with the real `getEffectiveDisabledTools()`.
- Test: `src/test/groovy/server/AdvancedOverridesFilterSpec.groovy` (create).

- [ ] **Step 1: Write the failing test:**

```groovy
package server

import support.ToolSpecBase

class AdvancedOverridesFilterSpec extends ToolSpecBase {

    def "disabling a tool hides it from every gateway it appears in (global)"() {
        given: "hub_list_rooms appears in both hub_read_rooms and hub_manage_rooms"
        settingsMap.disabled_tools = ["hub_list_rooms"]
        settingsMap.useGateways = true

        when:
        def hidden = script.getHiddenToolNames()
        def readGw = script.getToolDefinitions().find { it.name == "hub_read_rooms" }
        def manageGw = script.getToolDefinitions().find { it.name == "hub_manage_rooms" }

        then:
        hidden.contains("hub_list_rooms")
        !readGw.inputSchema.properties.tool.enum.contains("hub_list_rooms")
        !manageGw.inputSchema.properties.tool.enum.contains("hub_list_rooms")
    }

    def "disabling a gateway expands to all its tools, including shared ones"() {
        given:
        settingsMap.disabled_gateways = ["hub_manage_rooms"]

        when:
        def effective = script.getEffectiveDisabledTools()

        then: "every tool of hub_manage_rooms is in the effective disabled set"
        effective.containsAll(script.getGatewayConfig()["hub_manage_rooms"].tools)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "AdvancedOverridesFilterSpec"`
Expected: FAIL — the stub returns empty.

- [ ] **Step 3: Replace the stub** `getEffectiveDisabledTools()` (added in Task 6) with:

```groovy
// #114 effective deny set: explicitly-disabled tools UNION every tool of each
// disabled gateway (so shared tools disabled via a gateway are gone everywhere).
def getEffectiveDisabledTools() {
    def out = [] as Set
    (settings.disabled_tools ?: []).each { out << (it as String) }
    def gwConfig = getGatewayConfig()
    (settings.disabled_gateways ?: []).each { gw ->
        gwConfig[gw]?.tools?.each { out << (it as String) }
    }
    return out
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests "AdvancedOverridesFilterSpec" --tests "MasterVisibilitySpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/AdvancedOverridesFilterSpec.groovy
git commit -m "feat(#114): getEffectiveDisabledTools() global deny semantics (tool + gateway expansion)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 10: Distinct dispatch error for advanced-disabled tools/gateways

**Files:**
- Modify: `hubitat-mcp-server.groovy` — add the advanced-disabled checks in `executeTool` (after the master gate from Task 2).
- Test: `src/test/groovy/server/AdvancedOverridesFilterSpec.groovy` (extend).

- [ ] **Step 1: Add the failing test** to `AdvancedOverridesFilterSpec`:

```groovy
    def "calling an advanced-disabled tool returns a distinct Advanced-settings error"() {
        given:
        settingsMap.disabled_tools = ["hub_set_mode"]
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_set_mode", [mode: "Day"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Advanced settings")
        !e.message.contains("Write tools are disabled")
    }

    def "calling a disabled gateway returns the Advanced-settings error"() {
        given:
        settingsMap.disabled_gateways = ["hub_manage_rooms"]

        when:
        script.executeTool("hub_manage_rooms", [tool: "hub_list_rooms", args: [:]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Advanced settings")
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew cleanTest test --tests "AdvancedOverridesFilterSpec"`
Expected: FAIL — no advanced-disabled dispatch check yet.

- [ ] **Step 3: Add the check** in `executeTool`, immediately after the master-gate block from Task 2 and before the custom-engine block:

```groovy
    // ---- Advanced per-tool/per-gateway overrides (issue #114) ----
    if (isGatewayName) {
        if ((settings.disabled_gateways ?: []).contains(toolName)) {
            throw new IllegalArgumentException("${toolName} is disabled in Advanced settings (Per-tool Overrides). Re-enable it in MCP Rule Server app settings.")
        }
    } else if (getEffectiveDisabledTools().contains(toolName)) {
        throw new IllegalArgumentException("${toolName} is disabled in Advanced settings (Per-tool Overrides). Re-enable it in MCP Rule Server app settings.")
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew cleanTest test --tests "AdvancedOverridesFilterSpec"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/AdvancedOverridesFilterSpec.groovy
git commit -m "feat(#114): distinct Advanced-settings dispatch error for disabled tools/gateways

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 11: Search corpus excludes advanced-disabled (verify the Boy-Scout fix)

**Files:**
- Test: `src/test/groovy/server/ToolSearchToolsSpec.groovy` (extend).

(`toolSearchTools` already consumes `getHiddenToolNames()` from Task 6, so disabled tools are excluded — this task pins it.)

- [ ] **Step 1: Add the test** to `ToolSearchToolsSpec`:

```groovy
    def "advanced-disabled tools do not appear in hub_search_tools results"() {
        given:
        settingsMap.disabled_tools = ["hub_set_mode"]

        when:
        def res = script.toolSearchTools([query: "set hub mode"])

        then:
        !(res.results*.name.contains("hub_set_mode"))
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew cleanTest test --tests "ToolSearchToolsSpec"`
Expected: PASS (the Task-6 wiring already excludes it). If the result shape differs, adjust the accessor to match `toolSearchTools`'s actual return (`res.results` / `res.tools`).

- [ ] **Step 3: Commit**

```bash
git add src/test/groovy/server/ToolSearchToolsSpec.groovy
git commit -m "test(#114): pin that advanced-disabled tools are excluded from hub_search_tools

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 — Self-admin allowlist

### Task 12: Update hub_update_mcp_settings allowlist + description + searchHints

**Files:**
- Modify: `hubitat-mcp-server.groovy` — allowlist (L7577-7596), description (L2552), searchHints (L1317-1319).
- Test: `src/test/groovy/server/ToolUpdateMcpSettingsSpec.groovy`.

- [ ] **Step 1: Update the allowlist map** (L7586-7596) — rename `enableHubAdminRead`→`enableRead`, drop `enableBuiltinApp`, and update the excluded-keys comment:

```groovy
    // Excluded:
    //   enableWrite          — would footgun: could disable own write path mid-session
    //   enableDeveloperMode  — lockout protection (must remain UI-only to disable)
    //   selectedDevices      — capability multi-select, separate tool planned (Developer Mode follow-up)
    //   disabled_tools / disabled_gateways — could self-disable hub_update_mcp_settings; UI-only
    def allowedSettings = [
        "mcpLogLevel":            "enum",
        "debugLogging":           "bool",
        "maxCapturedStates":      "number",
        "loopGuardMax":           "number",
        "loopGuardWindowSec":     "number",
        "enableRead":             "bool",
        "enableCustomRuleEngine": "bool",
        "useGateways":            "bool"
    ]
```

- [ ] **Step 2: Update the tool description** (L2552) — replace the allowlist enumeration and gate phrasing:

```groovy
            description: "Update one or more of the MCP rule app's own settings (toggles, log levels, tuning parameters) in place. Use this to self-administer the MCP app without the Hubitat UI. Gated on enableDeveloperMode + the Write master + confirm=true + a recent backup; every successful write is logged at WARN for audit. Allowlisted keys only: mcpLogLevel, debugLogging, maxCapturedStates, loopGuardMax, loopGuardWindowSec, enableRead, enableCustomRuleEngine, useGateways — any other key is rejected. After changing any enable* toggle or useGateways, MCP clients (Claude Code, etc.) may need to restart their connection to refresh the cached tool schema. Deliberately NOT allowlisted: enableWrite (would disable the tool's own write path mid-session), enableDeveloperMode (lockout protection — must stay UI-only to disable), selectedDevices (different wire format, has its own tool), and disabled_tools/disabled_gateways (could self-disable this tool).",
```

- [ ] **Step 3: Update the searchHints** (L1318):

```groovy
                hub_update_mcp_settings: "self-admin developer mode toggle setting log level tuning loopGuard maxCapturedStates enableRead enableCustomRuleEngine useGateways gateway mode consolidate flat tools ci automation"
```

- [ ] **Step 4: Update `ToolUpdateMcpSettingsSpec.groovy`** — the persistence round-trip (L238-260) writes `[enableHubAdminRead:true, enableBuiltinApp:true, debugLogging:false]`. Change to `[enableRead:true, debugLogging:false]` (drop the removed `enableBuiltinApp`): update the call map (L245-247), the `result.updated.size()==3`→`==2` (L254), the `settingsStore[enableHubAdminRead]`→`settingsStore[enableRead]` assert (L255), drop the `settingsStore[enableBuiltinApp]` assert (L256), and the "Updated 3 settings."→"Updated 2 settings." message (L260). Add a rejection test that `enableWrite` is rejected:

```groovy
    def "enableWrite is rejected (not allowlisted -- footgun)"() {
        given:
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        state.lastBackupTimestamp = now()

        when:
        def res = script.toolUpdateMcpSettings([settings: [enableWrite: false], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("enableWrite")
    }
```

- [ ] **Step 5: Run + commit**

Run: `./gradlew cleanTest test --tests "ToolUpdateMcpSettingsSpec"`
Expected: PASS.

```bash
git add hubitat-mcp-server.groovy src/test/groovy/server/ToolUpdateMcpSettingsSpec.groovy
git commit -m "feat(#113): update self-admin allowlist (enableRead in; enableWrite/disabled_* excluded)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4 — Docs

### Task 13: Update AGENTS.md/CLAUDE.md + skill/safety/README/SECURITY docs

**Files:** `AGENTS.md`, `CLAUDE.md`, `SKILL.md`, `TOOL_GUIDE.md`, `agent-skill/hubitat-mcp/safety-guide.md`, `README.md`, `SECURITY.md`.

- [ ] **Step 1:** In `AGENTS.md`, add a short "Permission model" subsection under Tool design rules: two universal masters (Read/Write, default ON, every tool gated centrally), Developer Mode + Custom Rule Engine as additional layered gates, Gateway mode for catalog shape, and the #114 advanced deny-only overrides. Note the existing `hub_read_*`/`hub_manage_*` gateway split rule still holds and that annotations remain the declaration / masters the enforcement.
- [ ] **Step 2:** `cp AGENTS.md CLAUDE.md` (the PR Guard CI requires byte-identical).

```bash
cp AGENTS.md CLAUDE.md
```

- [ ] **Step 3:** In `SKILL.md`, `TOOL_GUIDE.md`, and `agent-skill/hubitat-mcp/safety-guide.md`: replace every "Built-in App Tools" reference with the Read/Write master model; document the advanced per-tool/gateway overrides + the distinct "disabled in Advanced settings" error; note that disabled tools stay in `hub_get_tool_guide` but drop from `tools/list`/`hub_search_tools`.
- [ ] **Step 4:** `README.md` (settings/permissions section) and `SECURITY.md` (permission model): reflect the 2-master taxonomy and the removal of the Built-in App toggle.
- [ ] **Step 5: Commit**

```bash
git add AGENTS.md CLAUDE.md SKILL.md TOOL_GUIDE.md agent-skill/hubitat-mcp/safety-guide.md README.md SECURITY.md
git commit -m "docs(#113,#114): document universal Read/Write masters + advanced per-tool overrides

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 5 — e2e + BAT

### Task 14: Update tests/e2e_test.py

**Files:** `tests/e2e_test.py`.

- [ ] **Step 1:** The tool-count assertion (L443, `len(tools) == 30`) is UNCHANGED — the default config already ran with built-in/custom all-on (empty `hideByName`), and masters default ON reproduce that. Leave the assertion; no edit.
- [ ] **Step 2:** Update the precondition comment block (L1106-1116): drop `enableBuiltinApp` from L1111 and reword `enableHubAdminWrite: true`→`enableWrite: true (default ON)`, `enableHubAdminRead`→`enableRead`.
- [ ] **Step 3:** Update T221 (L1132-1146) and T222 (L1148-1179): the excluded-footgun key `enableHubAdminWrite`→`enableWrite` (still excluded; the test still expects a `-32602` rejection). Update the docstring and the in-message assertion strings accordingly.
- [ ] **Step 4:** Add a master/deny e2e test (gateway mode): flip a master via the (UI-only) precondition is not callable from MCP, so instead assert the *allowlisted* path — `enableRead` is now allowlisted, so add a test that toggling `enableRead` via `hub_update_mcp_settings` succeeds and returns the reconnect hint (mirror T223's pattern, restoring `enableRead:true` after).
- [ ] **Step 5: Commit** (note `.github/scripts/mcp_setup_env.sh` also sets `enableBuiltinApp` in CI — flag in the PR body for a lockstep follow-up; it is outside `tests/e2e_test.py`).

```bash
git add tests/e2e_test.py
git commit -m "test(e2e): update toggle preconditions + allowlist tests for Read/Write masters

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 15: Add BAT-v2 scenarios

**Files:** `tests/BAT-v2.md`.

- [ ] **Step 1:** Add a "Permission masters & overrides" section with goal-prompt scenarios (gateway mode only; serialize RM writes; no Z-Wave/Zigbee): (a) Read master OFF ⇒ read tools vanish from the client; (b) Write master OFF ⇒ device control rejected; (c) disable a single tool via Advanced ⇒ it vanishes + distinct error on cached call; (d) disable a gateway ⇒ all its tools (incl. shared) vanish; (e) a destructive tool still demands confirm+backup with Write ON.
- [ ] **Step 2: Commit**

```bash
git add tests/BAT-v2.md
git commit -m "test(bat): add master kill-switch + advanced-override BAT scenarios

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 6 — Finalize

### Task 16: Full suite + lint + draft PR

- [ ] **Step 1: Run the full Spock suite + sandbox lint**

Run: `./gradlew cleanTest test` then `python tests/sandbox_lint.py`
Expected: BUILD SUCCESSFUL; lint clean. Fix any spec missed in Tasks 3-12 (search `git grep -n "enableHubAdminRead\|enableHubAdminWrite\|enableBuiltinApp\|requireBuiltinApp\|requireHubAdminRead\|requireHubAdminWrite" hubitat-mcp-server.groovy src/test` — must be zero).

- [ ] **Step 2: Push the branch**

```bash
git push -u origin feat/issue-113-114-toggle-overrides
```

- [ ] **Step 3: Open a DRAFT PR** (show the body to the maintainer first per the project's pre-create review rule). Use the repo PR template; include `## Type of change` = `refactor:`/`feat:` and a `## Release Notes` section. Title: `feat: universal Read/Write permission masters + per-tool/gateway overrides (#113, #114)`. Body must close both issues and note the lockstep `.github/scripts/mcp_setup_env.sh` `enableBuiltinApp` cleanup. Verify both required headings:

```bash
gh pr view <N> --json body --jq '.body' | grep -iE "^#+\s*(Type of change|Release Notes)\s*:?\s*$"
```

- [ ] **Step 4: Monitor CI to green; address findings.**

---

## Self-Review (author checklist — completed)

**Spec coverage:** masters (T1-2,6), built-in removal (T5), requireDestructiveConfirm/confirm+backup preserved (T4), customEngineMode rederivation (T1,6), central gate + gateway-skip (T2), completeness guard (T7), #114 page+settings (T8), global deny semantics (T9), distinct error (T10), search exclusion + guide-kept (T6,T11), allowlist (T12), docs (T13), e2e+BAT (T14-15), one combined PR (T16). All spec sections map to a task.

**Type/name consistency:** `getCustomEngineMode()`, `getEffectiveDisabledTools()`, `getHiddenToolNames()`, `requireDestructiveConfirm(Boolean)`, settings keys `enableRead`/`enableWrite`/`disabled_tools`/`disabled_gateways` used identically across all tasks. `getEffectiveDisabledTools()` is stubbed in T6 and bodied in T9 (noted inline).

**Placeholder scan:** no TBD/TODO; every code step shows real code; test code is concrete. The one parametric area (bulk test-seed renames) lists exact files + lines + the verification grep.
