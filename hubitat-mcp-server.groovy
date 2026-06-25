/**
 * MCP Rule Server for Hubitat
 *
 * A native MCP (Model Context Protocol) server that runs directly on Hubitat
 * with a built-in custom rule engine for creating automations via Claude.
 *
 * Version: 2.9.0 - Enriched list_devices summary + server-side filter (disabled, enabled, stale:N)
 *
 * Installation:
 * 1. Go to Hubitat > Apps Code > New App
 * 2. Paste this code and click Save
 * 3. Click "OAuth" button, then "Enable OAuth in App"
 * 4. Save again
 * 5. Add MCP Rule (child app) code as well
 * 6. Go to Apps > Add User App > MCP Rule Server
 * 7. Select devices to expose, click Done
 * 8. Open app to get endpoint URL with access token
 */

definition(
    name: "MCP Rule Server",
    namespace: "mcp",
    author: "kingpanther13",
    description: "MCP Server with Custom Rule Engine for Hubitat",
    category: "Automation",
    iconUrl: "",
    iconX2Url: "",
    oauth: [displayName: "MCP Rule Server", displayLink: ""],
    singleInstance: true
)

// issue #209 modularization smoke test: pulls in mcpSmokeTestMarker() from the McpSmokeTestLib
// library. The library is delivered to real hubs by the required HPM bundle (and is installable
// on the fly via hub_update_package); CI installs it ahead of the app (the e2e library-install
// step; the unit/parse lanes resolve the #include via support.IncludeResolver). Proves the
// #include load path works end to end -- the marker is surfaced in hub_get_info. Throwaway
// canary; removed once the split architecture is validated.
#include mcp.McpSmokeTestLib
#include mcp.McpSmokeTest2Lib

// issue #209 modularization: room-management tool implementations live in the McpRoomsLib
// library (libraries/mcp-rooms-lib.groovy), delivered to real hubs by the required HPM bundle
// and installable on the fly via hub_update_package. The gateway entries and dispatch cases stay
// in this file; the tool definitions (_getAllToolDefinitions_partRooms) and impl methods live in
// the library. First real module of the split.
#include mcp.McpRoomsLib

// issue #209 modularization: bundle-management tool implementations (hub_list_bundles /
// hub_delete_bundle / hub_export_bundle) live in the McpBundlesLib library
// (libraries/mcp-bundles-lib.groovy). New tools authored library-first -- their gateway entries
// and dispatch cases stay in this file; the tool definitions (_getAllToolDefinitions_partBundles)
// and impl methods live in the library.
#include mcp.McpBundlesLib

// issue #209 modularization: Visual Rules Builder tool implementations (hub_get_visual_rule /
// hub_set_visual_rule / hub_delete_visual_rule) live in the McpVisualRulesLib library
// (libraries/mcp-visual-rules-lib.groovy), authored library-first. The gateway entries
// and dispatch cases stay in this file; the tool definitions
// (_getAllToolDefinitions_partVisualRules) and impl methods live in the library.
#include mcp.McpVisualRulesLib

// File Manager tools (issue #209 modularization). Gateway entries and dispatch
// cases stay in this file; definitions + impls + per-tool metadata live in the library.
#include mcp.McpFilesLib

// Item-backup tools (issue #209 modularization). The shared backupItemSource
// primitive stays in this file (used by code management + native RM too).
#include mcp.McpItemBackupsLib

// Debug-log + bug-report tools (issue #209 modularization). The logging ENGINE
// (mcpLog/mcpLogError/initDebugLogs/shouldLog) is generic spine and stays in this file.
#include mcp.McpDebugLoggingLib

// Diagnostics + maintenance tools (issue #209 modularization). Captured-state
// accessors move too -- the rule child app reaches them via parent.* dispatch,
// which resolves on the compiled class regardless of source file.
#include mcp.McpDiagnosticsLib

// Hub system tools (issue #209 modularization): info, modes, HSM, backup, power,
// update check. currentVersion() stays in this file (release-bot bump anchor).
#include mcp.McpSystemLib

// Device tools (issue #209 modularization): reads, commands, history, update,
// delete. findDevice/getSelectedDevices stay in this file (generic spine).
#include mcp.McpDevicesLib

// Virtual-device tools (issue #209 modularization). hub_list_devices'
// filter=virtual path routes into this library's toolListVirtualDevices.
#include mcp.McpVirtualDevicesLib

// Hub variable + connector tools (issue #209 modularization). The subscription
// handlers move too: string-literal subscribe()/schedule() handler names resolve
// on the compiled class after the #include paste (AGENTS.md library notes).
#include mcp.McpVariablesLib

// Legacy custom-rule engine tools (issue #209 modularization). The child app
// (hubitat-mcp-rule.groovy) and the shared validation functions stay in place.
#include mcp.McpCustomRulesLib

// Code-management tools (issue #209 modularization): apps, drivers, libraries
// CRUD + source reads + app-config introspection. backupItemSource and the
// URL-fetch helpers stay in this file (shared across domains).
#include mcp.McpCodeManagementLib

// HPM package tools (issue #209 modularization).
#include mcp.McpHpmLib

// MCP self-admin tools (issue #209 modularization): settings updates + the
// Developer Mode package deploy.
#include mcp.McpSelfAdminLib

// App cloner / export / import tools (issue #209 modularization). Closure-free
// and separable from the native-RM wizard cluster (now in McpNativeRulesLib).
#include mcp.McpAppClonerLib

// Discovery tools (issue #209 modularization): BM25 tool search + the tool-guide
// dispatcher. getToolGuideSections() content stays in this file (sandbox-lint's
// guide-pointer/TOOL_GUIDE.md parity checks anchor on it).
#include mcp.McpDiscoveryLib
// Native Rule Machine + classic-app tools (issue #209): the RM 5.1 wizard authoring
// surface (hub_set_rule) + native-app CRUD. The shared classic-dynamicPage wizard
// primitives stay in this file (used by other libraries).
#include mcp.McpNativeRulesLib

preferences {
    page(name: "mainPage")
    page(name: "confirmDeletePage")
    page(name: "confirmRegenerateTokenPage")
    page(name: "advancedOverridesPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "MCP Rule Server", install: true, uninstall: true) {
        section("MCP Endpoint") {
            if (!state.accessToken) {
                paragraph "Click 'Done' to generate access token, then reopen app to see endpoint URLs."
            } else {
                paragraph "<b>Local Endpoint:</b>"
                paragraph "<code>${getFullLocalApiServerUrl()}/mcp?access_token=${state.accessToken}</code>"
                paragraph "<b>Cloud Endpoint:</b>"
                paragraph "<code>${getFullApiServerUrl()}/mcp?access_token=${state.accessToken}</code>"
                paragraph "<b>App ID:</b> ${app.id}"
                paragraph "<b>Version:</b> ${currentVersion()}"
                if (state.updateCheck?.updateAvailable) {
                    paragraph "<b style='color: orange;'>&#9888; Update available: v${state.updateCheck.latestVersion}</b> (you have v${currentVersion()}). Update via <a href='https://github.com/kingpanther13/Hubitat-local-MCP-server' target='_blank'>GitHub</a> or Hubitat Package Manager."
                }
                href name: "regenerateToken", page: "confirmRegenerateTokenPage",
                     title: "Regenerate access token",
                     description: "Issue a new token if the current one may be compromised. WARNING: the token is part of the endpoint URL above, so regenerating CHANGES both endpoint URLs -- you must re-copy the new URL into every MCP client afterward."
            }
        }

        section("Device Access") {
            input "selectedDevices", "capability.*", title: "Select Devices for MCP Access",
                  multiple: true, required: false, submitOnChange: true
            if (selectedDevices) {
                paragraph "Selected ${selectedDevices.size()} devices"
            }
        }

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
            href name: "advancedOverrides", page: "advancedOverridesPage",
                 title: "Advanced: Per-tool Overrides",
                 description: "Disable individual tools or whole gateways below the Read/Write masters (deny-only)."
        }

        section("Best-Practice Guidance") {
            paragraph "Surfaces this project's best practices to the AI. Reactive hints are always on: a failed write tool's error gains a pointer to that tool's own guide section. The acknowledgment gate below is ON by default."
            input "enableMandatoryBPS", "bool", title: "Require Best-Practice Guide Acknowledgment (write tools)",
                  description: "ON by default. When ON, every write tool is blocked until the AI reads hub_get_tool_guide(section='best_practice_reference') and passes the acknowledgment key it publishes as the bestPracticeKey argument. Reads, the guide, and this settings tool stay reachable, so the AI can never lock itself out. Turn OFF for clients that can't carry the extra context.",
                  defaultValue: true, submitOnChange: true
        }

        section("Developer Mode") {
            paragraph "<b>Developer Mode</b> exposes self-administration capabilities — tools that let an LLM agent or CI/CD pipeline manage the MCP's own configuration, scope, and operational state without requiring manual UI intervention."
            paragraph "<i>Capability categories under this mode (current + planned):</i>"
            paragraph "<ul>" +
                      "<li>Configuration management — toggle states, log levels, loop guards, tuning parameters</li>" +
                      "<li>Device-access scope — add/remove devices from MCP visibility</li>" +
                      "<li>Hub-variable management — true Hub Variables namespace (Settings → Hub Variables)</li>" +
                      "<li>Artifact cleanup — sweep ephemeral CI/test devices, variables, rules</li>" +
                      "<li>Operational diagnostics + self-healing routines</li>" +
                      "</ul>"
            paragraph "<i>Useful for end-to-end CI/CD automation, agent-driven configuration, and workflows where manual UI ops would be impractical.</i>"
            input "enableDeveloperMode", "bool", title: "Enable Developer Mode Tools",
                  description: "Exposes self-administration tools for MCP-managed configuration and lifecycle changes.",
                  defaultValue: false, submitOnChange: true
            if (settings.enableDeveloperMode) {
                paragraph "<b style='color: red;'>⚠ WARNING: Developer Mode allows the AI assistant to modify which tools it can access (via toggle changes), what device scope it has, how verbose its logging is, and other operational settings. Only enable if you understand and trust the agent's authorization model. Every write is logged at WARN level for audit.</b>"
            }
        }

        section("Hub Security") {
            paragraph "If <b>Hub Security</b> is enabled on your hub, provide credentials here so Hub Admin tools can authenticate. " +
                      "If Hub Security is NOT enabled, leave this off — Hub Admin tools will work without credentials."
            input "hubSecurityEnabled", "bool", title: "Hub Security Enabled",
                  description: "Turn on if your hub has Hub Security (login) enabled",
                  defaultValue: false, submitOnChange: true
            if (settings.hubSecurityEnabled) {
                input "hubSecurityUser", "text", title: "Hub Security Username", required: false
                input "hubSecurityPassword", "password", title: "Hub Security Password", required: false
            }
        }

        // Rule List Section - now using child apps
        section("Automation Rules") {
            def childApps = getChildApps()
            def ruleCount = childApps?.size() ?: 0
            def enabledCount = childApps?.count { it.getSetting("ruleEnabled") } ?: 0
            paragraph "<b>${ruleCount}</b> rules total, <b>${enabledCount}</b> enabled"

            if (childApps && childApps.size() > 0) {
                childApps.each { childApp ->
                    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
                    def isEnabled = childApp.getSetting("ruleEnabled") ?: false
                    def statusIcon = isEnabled ? "✓" : "○"
                    def statusText = isEnabled ? "Enabled" : "Disabled"
                    def ruleData = childApp.getRuleData()
                    def triggerCount = ruleData?.triggers?.size() ?: 0
                    def actionCount = ruleData?.actions?.size() ?: 0
                    def lastRun = ruleData?.lastTriggered ? formatTimestamp(ruleData.lastTriggered) : "Never"

                    href name: "viewRule_${childApp.id}",
                         title: "${statusIcon} ${ruleName}",
                         description: "${statusText} | ${triggerCount} triggers, ${actionCount} actions | Last: ${lastRun}",
                         url: "/installedapp/configure/${childApp.id}"
                }
            } else {
                paragraph "<i>No rules created yet. Add a rule to get started.</i>"
            }

            // Child app to add new rules
            app(name: "rules", appName: "MCP Rule", namespace: "mcp", title: "+ Add New Rule", multiple: true)
        }

        section("Settings") {
            // Migration warning: the legacy 'enableRuleEngine' toggle (default ON)
            // was renamed to 'enableCustomRuleEngine' (default OFF). Existing users
            // who had child rules created via the MCP custom rule engine will see
            // their rules become unreachable via tools/list until they explicitly
            // re-enable the toggle. Surface this prominently when we detect the
            // mismatch (child apps exist but the new toggle is off/null).
            def existingRuleCount = getChildApps()?.size() ?: 0
            def customEngineExplicitlyOn = settings.enableCustomRuleEngine == true
            def readEnabled = settings.enableRead != false
            if (existingRuleCount > 0 && !customEngineExplicitlyOn) {
                def readonlyNote = readEnabled ? " your AI can still SEE these rules (<code>hub_get_custom_rule</code>) and toggle them enabled/disabled, but cannot create, modify structure, or delete." : " With the Read master also OFF, all custom_* tools are hidden from your AI."
                paragraph "<b>NOTICE: ${existingRuleCount} existing custom MCP rule(s)</b><br>" +
                          "Your ${existingRuleCount} custom MCP rule(s) still fire and work normally. The Custom Rule Engine setting used to be ON by default; it now defaults OFF because the custom MCP rule engine is legacy -- it will continue to receive bug fixes but new feature work goes to native Rule Machine.<br>" +
                          "<b>Current state (toggle OFF):</b>${readonlyNote} Recommended: leave OFF if you have migrated to native Rule Machine. Turn ON only if you actively use your AI to fully manage these rules.<br>" +
                          "For new rule creation, prefer <code>hub_manage_rule_machine</code> hub_set_rule -- those rules are visible in Hubitat's Rule Machine app list and web UI."
            }
            input "enableCustomRuleEngine", "bool", title: "Enable Custom Rule Engine (legacy)",
                  description: "Controls the legacy MCP-managed rule engine (custom_* tools). OFF + Read master ON = read-only mode: hub_get_custom_rule (list/get/diagnostics modes), hub_update_custom_rule(enabled only), hub_test_custom_rule are visible; create/delete/export/import/clone are hidden. OFF + Read master OFF = all custom_* tools hidden. ON = all custom_* tools shown (full mode). The native Hubitat Rule Machine (governed by the Read/Write masters) is independent of this. Note: Hubitat firmware upgrades may briefly reset Boolean toggles -- verify this stays OFF after each firmware upgrade if you've migrated to native Rule Machine.",
                  defaultValue: false, submitOnChange: true
            input "useGateways", "bool", title: "Consolidate tools behind category gateways",
                  description: "When ON (default): tools are organized behind domain-named category gateways so tools/list stays compact for clients that struggle with long tool lists. When OFF: every tool is exposed individually as a top-level MCP tool and hub_search_tools is hidden because its only purpose is finding tools hidden behind gateways. Most LLM clients perform better with the gateway list; turn this off only if your client has its own progressive-disclosure / tool-search layer. Note: other settings (the Read/Write masters, the Custom Rule Engine, and Advanced per-tool/per-gateway overrides) also add or remove entries from tools/list independently of this setting.",
                  defaultValue: true
            input "publishOutputSchemas", "bool", title: "Publish tool output schemas (advanced)",
                  description: "Leave OFF (default). When OFF, the server never advertises a tool's outputSchema on any tools/list surface or the gateway catalog, so strict MCP clients (e.g. Claude Desktop) that require structured content work normally. When ON, outputSchema is re-added to gateway-mode base tools and the gateway catalog as a documentation aid -- but because this server returns text-only results, strict clients will then reject every tool call with JSON-RPC -32600 ('has an output schema but did not return structured content'). The flat tool list never advertises outputSchema regardless of this setting.",
                  defaultValue: false
            input "mcpLogLevel", "enum", title: "MCP Debug Log Level",
                  description: "Controls MCP-accessible debug logs (default: errors only)",
                  options: ["debug": "Debug (verbose)", "info": "Info (normal)", "warn": "Warnings only", "error": "Errors only (recommended)"],
                  defaultValue: "error", required: false
            input "debugLogging", "bool", title: "Enable Hubitat Console Logging", defaultValue: false,
                  description: "Logs to Hubitat's built-in log viewer"
            input "maxCapturedStates", "number", title: "Max Captured States",
                  description: "Maximum number of unique state captures to store (default: 20)",
                  defaultValue: 20, range: "1..100", required: false
            input "loopGuardMax", "number", title: "Loop Guard: Max Executions",
                  description: "Auto-disable a rule after this many executions within the time window (default: 30)",
                  defaultValue: 30, range: "5..200", required: false
            input "loopGuardWindowSec", "number", title: "Loop Guard: Window (seconds)",
                  description: "Sliding time window for the execution count (default: 60)",
                  defaultValue: 60, range: "10..300", required: false
        }
    }
}

def formatTimestamp(timestamp) {
    if (!timestamp) return "Never"
    try {
        if (timestamp instanceof Number) {
            def date = new Date(timestamp)
            return date.format("yyyy-MM-dd HH:mm:ss")
        } else if (timestamp instanceof String) {
            // Try multiple ISO 8601 formats to handle variations from
            // different firmware versions or upstream APIs
            def formats = [
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",   // Full with millis and offset: 2025-01-15T10:30:00.000+0000
                "yyyy-MM-dd'T'HH:mm:ssZ",         // No millis with offset:      2025-01-15T10:30:00+0000
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",   // Full with millis and Z:     2025-01-15T10:30:00.000Z
                "yyyy-MM-dd'T'HH:mm:ss'Z'",       // No millis with Z:           2025-01-15T10:30:00Z
                "yyyy-MM-dd'T'HH:mm:ss",          // No millis, no timezone:     2025-01-15T10:30:00
                "yyyy-MM-dd HH:mm:ss",            // Space-separated:            2025-01-15 10:30:00
            ]
            for (fmt in formats) {
                try {
                    def date = Date.parse(fmt, timestamp)
                    return date.format("yyyy-MM-dd HH:mm:ss")
                } catch (Exception ignored) {
                    // Try next format
                }
            }
            // No format matched — fall through to raw string truncation below
        }
        return timestamp?.toString()?.take(20) ?: "Unknown"
    } catch (Exception e) {
        return timestamp?.toString()?.take(20) ?: "Unknown"
    }
}

def confirmDeletePage(params) {
    def ruleId = params?.ruleId
    def childApp = getChildAppById(ruleId)

    if (!childApp) {
        return dynamicPage(name: "confirmDeletePage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    state.ruleToDelete = ruleId

    dynamicPage(name: "confirmDeletePage", title: "Delete Rule?") {
        section {
            paragraph "<b>Are you sure you want to delete this rule?</b>"
            paragraph "Rule: <b>${ruleName}</b>"
            paragraph "This action cannot be undone."
        }

        section {
            input "confirmDeleteBtn", "button", title: "Yes, Delete Rule"
            href name: "cancelDelete", page: "mainPage", title: "Cancel"
        }
    }
}

def confirmRegenerateTokenPage() {
    dynamicPage(name: "confirmRegenerateTokenPage", title: "Regenerate access token?") {
        section {
            paragraph "<b>Are you sure you want to regenerate the MCP access token?</b>"
            paragraph "The access token is part of the MCP endpoint URL. Regenerating it <b>immediately changes both the Local and Cloud endpoint URLs</b>."
            paragraph "<b style='color: red;'>&#9888; Every MCP client using the old URL will stop working until you re-copy the new URL.</b> After regenerating, reopen this app and copy the new endpoint URL into each client."
            paragraph "Use this only if the current token may be compromised. This action cannot be undone."
        }

        section {
            input "regenerateTokenBtn", "button", title: "Yes, Regenerate Token"
            href name: "cancelRegenerate", page: "mainPage", title: "Cancel"
        }
    }
}

// #114 Advanced sub-page: deny-only per-tool / per-gateway overrides applied BELOW
// the Read/Write masters. Option lists are generated from the live tool surface so
// they never drift. The two list settings (disabled_tools / disabled_gateways) feed
// getHiddenToolNames() (catalog + search) and the executeTool dispatch guard.
def advancedOverridesPage() {
    dynamicPage(name: "advancedOverridesPage", title: "Advanced: Per-tool Overrides") {
        section {
            // Enum multi-select inputs render through the SumoSelect picker, whose
            // stylesheet clamps every dropdown option to one ellipsized line
            // (.options li label { text-overflow:ellipsis; white-space:nowrap;
            // overflow:hidden }) -- unusable for the rich labels on this page,
            // especially on narrow mobile viewports. Page-scoped override: open
            // dropdown options wrap and grow (the multiple-mode padding-left keeps
            // wrapped lines clear of the checkbox); the CLOSED field's caption
            // keeps its one-line ellipsis on purpose (it is just a summary).
            paragraph "<style>" +
                ".SumoSelect > .optWrapper > .options li label { white-space: normal !important; overflow: visible !important; text-overflow: clip !important; word-break: break-word; overflow-wrap: anywhere; line-height: 1.35; padding-top: 2px; padding-bottom: 2px; } " +
                ".SumoSelect > .optWrapper > .options li.opt { height: auto !important; }" +
                "</style>" +
                "Deny-only fine-grained control. These selections are applied <b>below</b> the Read/Write masters: they can only turn things OFF, never re-enable something a master already hid. A disabled tool disappears from tools/list and hub_search_tools everywhere it appears (including shared tools in multiple gateways) and returns a clear error if a cached client still calls it; it remains documented in hub_get_tool_guide."
        }
        def overrideOptions = buildOverrideOptions()
        section("Disable whole gateways") {
            input "disabled_gateways", "enum", title: "Gateways to disable",
                  description: "Every tool inside a disabled gateway is hidden (including tools shared with other gateways).",
                  options: overrideOptions.gateways, multiple: true, required: false, submitOnChange: true
        }
        section("Disable individual tools") {
            input "disabled_tools", "enum", title: "Tools to disable",
                  description: "Each tool is listed once; disabling it removes it from every gateway it belongs to.",
                  options: overrideOptions.tools, multiple: true, required: false, submitOnChange: true
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

// Builds the value->label option maps for the Advanced per-tool overrides
// pickers. The KEYS must stay the bare gateway/tool names -- the stored
// disabled_gateways/disabled_tools settings are matched by name in
// getEffectiveDisabledTools(); only the labels are display sugar. A gateway
// is tagged [read] only when EVERY member tool is read-only.
def buildOverrideOptions() {
    def displayMeta = getToolDisplayMeta()
    def readOnlyNames = getReadOnlyToolNames()
    def gwConfig = getGatewayConfig()
    def gateways = gwConfig.keySet().sort().collectEntries { gwName ->
        def pureRead = gwConfig[gwName].tools.every { readOnlyNames.contains(it) }
        [(gwName): overrideOptionLabel(gwName, displayMeta[gwName], pureRead)]
    }
    def tools = getAllToolDefinitions()*.name.findAll { !gwConfig.containsKey(it) }.sort().collectEntries { toolName ->
        [(toolName): overrideOptionLabel(toolName, displayMeta[toolName], readOnlyNames.contains(toolName))]
    }
    return [gateways: gateways, tools: tools]
}

// One option label for the Advanced per-tool overrides pickers: bare name,
// friendly name, read/write marker, then a one-sentence summary -- scannable
// without having to decode the bare tool names. Falls back to the bare name
// when a display-meta entry is missing so the picker never renders blank.
def overrideOptionLabel(String name, Map meta, boolean isReadOnly) {
    def title = meta?.title ?: name
    def tag = isReadOnly ? "read" : "write"
    def summary = meta?.summary ? " — ${meta.summary}" : ""
    return "${name} — ${title} [${tag}]${summary}"
}

def appButtonHandler(btn) {
    if (btn == "confirmDeleteBtn" && state.ruleToDelete) {
        def childApp = getChildAppById(state.ruleToDelete)
        if (childApp) {
            def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
            deleteChildApp(state.ruleToDelete)
            log.info "Deleted rule: ${ruleName}"
        }
        state.remove("ruleToDelete")
    } else if (btn == "regenerateTokenBtn") {
        // User-initiated token rotation. Clearing state.accessToken then calling
        // createAccessToken() re-issues a fresh token, which changes both endpoint
        // URLs (the token is in the URL); the user must re-copy the new URL into
        // every MCP client. initialize()'s !state.accessToken guard is the only
        // other caller, so the token is otherwise stable (never auto-rotated).
        state.remove("accessToken")
        createAccessToken()
        mcpLog("warn", "server", "MCP access token regenerated via UI; endpoint URLs changed, clients must re-copy the new URL")
    } else if (btn == "resetOverridesBtn") {
        app.removeSetting("disabled_tools")
        app.removeSetting("disabled_gateways")
        mcpLog("info", "server", "Advanced per-tool overrides reset (disabled_tools + disabled_gateways cleared)")
    }
}

def getChildAppById(appId) {
    return getChildApps()?.find { it.id.toString() == appId?.toString() }
}

// ==================== APP LIFECYCLE ====================

def installed() {
    log.info "MCP Rule Server installed"
    initialize()
}

def updated() {
    log.info "MCP Rule Server updated"
    atomicState.remove("toolSearchCorpus")        // Invalidate BM25 corpus cache on app update
    atomicState.remove("toolSearchTokens")        // ...and the paired BM25 token cache in lockstep
    atomicState.remove("toolSearchCorpusVersion")  // ...and the corpus version stamp
    atomicState.remove("requiredParamsByTool")    // ...and the gateway required-param memo
    atomicState.remove("requiredParamsByToolFingerprint")  // ...and its content fingerprint in lockstep
    initialize()

    // ===== One-time custom-engine rename migration =====
    // Legacy users had `enableRuleEngine: true` (default ON). When that setting
    // was renamed to `enableCustomRuleEngine` (default OFF), firmware upgrades
    // on 2.5.0.x re-evaluate the renamed Boolean against defaultValue and may
    // flip a user-set `false` back to `true`. Force OFF once: when the legacy
    // setting is still present (proves this is a pre-rename install) AND we've
    // never run this migration AND the new setting is not already false.
    //
    // After this fires once, `state.customEngineMigrated` locks it to a single
    // firing. If a user explicitly toggles ON after migration, their choice
    // persists -- we only correct the firmware-induced flip, not a deliberate
    // user toggle. Subsequent firmware-upgrade re-flips (after the initial
    // migration) are a known quirk the user must spot-check; we don't
    // auto-correct those because updated() fires before mainPage() re-renders
    // the new settings value, and we'd race with user-driven toggle events.
    if (state.customEngineMigrated != true
            && settings.enableRuleEngine != null
            && settings.enableCustomRuleEngine == null) {
        app.updateSetting("enableCustomRuleEngine", [type: "bool", value: false])
        mcpLog("info", "engine-migration", "Forced enableCustomRuleEngine=false (one-time rename migration; legacy enableRuleEngine present)")
    }
    state.customEngineMigrated = true

    // ===== One-time captured-states state -> atomicState migration =====
    // Captured device states moved from `state` to `atomicState`. Carry any
    // pre-existing captures across so a restore_state that worked before the
    // update still finds them -- otherwise they'd be orphaned in `state` and
    // silently disappear. One-shot: only copies when atomicState is still empty.
    if (state.capturedDeviceStates && !atomicState.capturedDeviceStates) {
        atomicState.capturedDeviceStates = state.capturedDeviceStates
        def migratedCount = atomicState.capturedDeviceStates.size()
        state.remove("capturedDeviceStates")
        mcpLog("info", "capture-migration", "Migrated ${migratedCount} captured state(s) from state to atomicState")
    }
}

def uninstalled() {
    log.info "MCP Rule Server uninstalled"

    // Clean up this app's hub-variable in-use registrations so deleting the
    // app doesn't leave Hubitat warning users about vars no rule references
    // anymore. Diff against our tracked set (NOT removeAllInUseGlobalVar) so we
    // only clear registrations this app made. Idempotent, mirrors the
    // _refreshHubVarInUseRegistrations try/catch pattern.
    ((atomicState.inUseHubVars ?: []) as List).each { name ->
        try { removeInUseGlobalVar(name) } catch (Exception e) { /* idempotent */ }
    }
    atomicState.remove('inUseHubVars')

    // Drop the variable subscriptions and the daily checkForUpdate schedule.
    try { unsubscribe() } catch (Exception e) { /* best-effort teardown */ }
    try { unschedule() } catch (Exception e) { /* best-effort teardown */ }
}

def initialize() {
    if (!state.accessToken) {
        createAccessToken()
        log.info "Created access token"
    }
    if (!state.ruleVariables) {
        state.ruleVariables = [:]
    }
    // Schedule daily version update check at 3am and run immediately.
    // unschedule() first so each updated()->initialize() cycle declaratively
    // rebuilds the schedule set instead of stacking duplicate cron jobs
    // (mirrors the unsubscribe() symmetry below). Must precede schedule() and
    // checkForUpdate() so the immediate run still fires.
    try { unschedule() }
    catch (Exception e) { mcpLog("warn", "server", "unschedule() before re-schedule failed: ${e.message} -- duplicate schedules may persist") }
    schedule("0 0 3 ? * *", "checkForUpdate")
    // Only egress to GitHub immediately on first install. state.updateCheck is
    // null until the first check completes; once set, routine settings saves
    // skip the immediate call and rely on the daily schedule + the in-function
    // 24h guard for steady-state freshness.
    if (state.updateCheck == null) checkForUpdate()

    // Issue #92: subscribe to every hub variable's location event
    // ("variable:NAME") so the AI can see what changed and when via
    // hub_list_variable_changes. Hubitat does NOT implicitly unsubscribe between
    // updated() invocations, so unsubscribe first -- otherwise every settings save
    // stacks another duplicate subscription per variable, firing
    // handleHubVariableEvent N times per change and inflating variableHistory.
    try { unsubscribe() }
    catch (Exception e) { mcpLog("warn", "hub-vars", "unsubscribe() before re-subscribe failed: ${e.message} -- duplicate subscriptions may persist") }
    _subscribeToAllHubVariables()

    // Issue #96 gap 1: register addInUseGlobalVar for every hub variable
    // referenced by any child rule. Hubitat then warns users before they
    // delete/rename a variable that would break a rule. Diff against the
    // previously-tracked set so we removeInUseGlobalVar for vars no
    // longer referenced (rule edited away from the var, rule deleted).
    _refreshHubVarInUseRegistrations()
}


// ==================== MCP REQUEST HANDLERS ====================

mappings {
    // Server-to-server only; no CORS/OPTIONS by design (token-in-query local
    // endpoint). Browser/cross-origin clients are out of scope, and Hubitat
    // render() cannot emit Access-Control-* headers from a mapped endpoint, so
    // no OPTIONS handler is registered (a stub would only pretend to do CORS).
    path("/mcp") {
        action: [
            GET: "handleMcpGet",
            POST: "handleMcpRequest"
        ]
    }
    path("/health") {
        action: [GET: "handleHealth"]
    }
}

// Single source of truth for the hub's hard JSON-RPC response cap (131072 = 128 KiB).
// Method-constant, not `private static final` (script-scope rejects the field in the
// Hubitat sandbox). The two response-size guards and toolGetHubLogs derive their
// thresholds from this with explicit headroom so the cap lives in exactly one place.
def hubResponseCapBytes() { 131072 }

def handleHealth() {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson([
        status: "ok",
        server: "hubitat-mcp-rule-server",
        version: currentVersion()
    ]))
}

// POST-only: this MCP endpoint is request-response over POST. No SSE/GET
// streaming is supported (intentional -- SSE is impractical on the Hubitat
// HEM endpoint). GET returns a JSON-RPC-shaped 405 so a JSON-RPC client sees
// a coherent error rather than an ad-hoc body.
def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: groovy.json.JsonOutput.toJson(jsonRpcError(null, -32600,
                      "This MCP endpoint is request-response only (POST). SSE/GET streaming is not supported.")))
}

// Transport contract: JSON-RPC application errors (parse / invalid-request /
// method-not-found / internal) are returned with HTTP 200 and an error envelope
// per JSON-RPC 2.0. Only transport-level conditions set a non-200 status:
// 405 for GET (handleMcpGet), and 204/202 for an all-notifications POST (no
// response objects). Do NOT convert application-level JSON-RPC errors to 4xx --
// spec-compliant clients expect the error inside a 200 body.
def handleMcpRequest() {
    def requestBody
    try {
        // Content-Type is intentionally not validated: Hubitat's mapped-endpoint
        // inbound request object does not reliably expose the header in the
        // sandbox, and a wrong content-type already degrades to the -32700
        // parse-error path below (request.JSON throws or returns null).
        requestBody = request.JSON
    } catch (Exception e) {
        // Bug fix: return proper JSON-RPC parse error (-32700)
        def errResp = jsonRpcError(null, -32700, "Parse error: invalid JSON")
        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(errResp))
    }

    if (requestBody == null) {
        def errResp = jsonRpcError(null, -32700, "Parse error: empty or invalid JSON body")
        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(errResp))
    }

    logDebug("MCP Request: ${requestBody.toString().take(500)}${requestBody.toString().length() > 500 ? '...[truncated]' : ''}")

    def response
    if (requestBody instanceof List) {
        // Bug fix: empty batch array must return error per JSON-RPC 2.0 spec
        if (requestBody.isEmpty()) {
            response = jsonRpcError(null, -32600, "Invalid Request: empty batch array")
        } else if (requestBody.size() > 50) {
            // Inbound batch cap: reject oversized batches before per-element
            // dispatch so a single request can't fan out unbounded hub work.
            return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(
                jsonRpcError(null, -32600, "Invalid Request: batch too large (${requestBody.size()} elements, max 50)")))
        } else {
            // Batch members must serialize normally. handleToolsCall hands back a
            // {__preserialized: <json string>} sentinel on the single-message fast path;
            // unwrap any such element back to a parsed object here so a sentinel can never
            // leak into the batch JSON array (the rare batch tools/call accepts a re-parse).
            response = requestBody.collect { msg -> processJsonRpcMessage(msg) }.findAll { it != null }.collect { _unwrapPreserialized(it) }
        }
    } else {
        response = processJsonRpcMessage(requestBody)
    }

    // Per JSON-RPC 2.0 spec: if no response objects (all notifications), return
    // nothing. MCP Streamable HTTP prescribes 202 Accepted for this case.
    if (response == null || (response instanceof List && response.isEmpty())) {
        return render(status: 202, contentType: "application/json", data: "")
    }

    // Single-message verbatim-passthrough: when handleToolsCall already produced the wire
    // JSON (the common under-cap tools/call path), it returns a {__preserialized: <string>}
    // sentinel. Render that string as-is rather than re-encoding the object a second time.
    // Only the exact sentinel shape takes this branch -- every normal response is
    // {jsonrpc, id, result|error} and falls through to the standard encode.
    def jsonResponse
    if (response instanceof Map && response.containsKey("__preserialized")) {
        jsonResponse = response.__preserialized
    } else {
        jsonResponse = groovy.json.JsonOutput.toJson(response)
    }

    // Safety guard: hub enforces 128KB response limit — use byte length for accurate sizing
    def maxResponseSize = hubResponseCapBytes() - 7072 // =124000; ~7 KB headroom under the 131072-byte (128 KiB) hub cap
    // Only compute byte length for large responses (avoid byte array allocation for small ones)
    def responseBytes = jsonResponse.length() > (maxResponseSize - 8000) ? jsonResponse.getBytes("UTF-8").length : jsonResponse.length()
    if (responseBytes > maxResponseSize) {
        mcpLog("error", "server", "MCP response too large: ${responseBytes} bytes (limit ${maxResponseSize}). Returning error instead.")
        // On the preserialized fast path `response` is the sentinel, not a JSON-RPC object,
        // so there is no id to echo -- fall back to null (matches the prior non-Map behaviour).
        def echoId = (response instanceof Map && !response.containsKey("__preserialized")) ? response.id : null
        def errResp = jsonRpcError(
            echoId,
            -32603,
            "Response too large (${responseBytes} bytes exceeds hub's 128KB limit). Try requesting less data or use a more specific query."
        )
        jsonResponse = groovy.json.JsonOutput.toJson(errResp)
    }

    logDebug("MCP Response: ${jsonResponse.take(500)}${jsonResponse.length() > 500 ? '...[' + jsonResponse.length() + ' bytes total]' : ''}")
    return render(contentType: "application/json", data: jsonResponse)
}

def processJsonRpcMessage(msg) {
    if (!msg) {
        return jsonRpcError(null, -32600, "Invalid Request: empty message")
    }

    if (msg.jsonrpc != "2.0") {
        return jsonRpcError(msg?.id, -32600, "Invalid Request: must use JSON-RPC 2.0")
    }

    // Bug fix: missing method is Invalid Request (-32600), not Method not found (-32601)
    if (!msg.method) {
        if (msg.id == null) return null  // Notification without method — ignore
        return jsonRpcError(msg.id, -32600, "Invalid Request: missing method field")
    }

    if (msg.id == null) {
        handleNotification(msg)
        return null
    }

    try {
        switch (msg.method) {
            case "initialize":
                return handleInitialize(msg)
            case "tools/list":
                return handleToolsList(msg)
            case "tools/call":
                return handleToolsCall(msg)
            case "ping":
                return jsonRpcResult(msg.id, [:])
            default:
                return jsonRpcError(msg.id, -32601, "Method not found: ${msg.method}")
        }
    } catch (Exception e) {
        // Hubitat's LogWrapper.error() does NOT accept (String, Throwable). Use string-only.
        // Stack trace would only be visible in mcpLog details (which this top-level catch lacks).
        log.error "MCP Error: ${e.message} (${e.class.simpleName})"
        return jsonRpcError(msg.id, -32603, "Internal error: ${e.message}")
    }
}

// Unwrap the {__preserialized: <json string>} sentinel handleToolsCall emits on the
// single-message fast path back into a parsed object, so batch members serialize normally
// and no sentinel key leaks into the batch JSON array. Non-sentinel values pass through.
def _unwrapPreserialized(item) {
    if (item instanceof Map && item.containsKey("__preserialized")) {
        return new groovy.json.JsonSlurper().parseText(item.__preserialized)
    }
    return item
}

def handleNotification(msg) {
    logDebug("MCP Notification: ${msg.method}")
}

def serverInstructions() {
    "Gateway tools (hub_manage_* / hub_read_*) expose sub-tools -- call a gateway with no arguments to list its sub-tools and their schemas. Tool responses are capped near 120KB; on large lists use cursor pagination (pass the returned nextCursor to fetch the next page)."
}

// Protocol versions this server can speak, newest first. Echo-allowlist:
// handleInitialize honors the client's requested version when it is one of
// these, else falls back to the default. outputSchema (a 2025-06-18 feature) is
// declared on every tool but, by default, NOT advertised on the wire (issue #290:
// strict clients reject an advertised schema returned without structuredContent);
// enable publishOutputSchemas to advertise it.
def supportedProtocolVersions() { ["2025-06-18", "2025-03-26", "2024-11-05"] }
def defaultProtocolVersion() { "2024-11-05" }

def handleInitialize(msg) {
    def info = [
        name: "hubitat-mcp-rule-server",
        version: currentVersion()
    ]
    if (state.updateCheck?.updateAvailable) {
        info.updateAvailable = state.updateCheck.latestVersion
    }
    // Echo the client's requested protocolVersion when supported; otherwise the
    // default. A client that omits it (or sends an unknown one) gets the default.
    def requested = msg.params?.protocolVersion
    def negotiated = supportedProtocolVersions().contains(requested) ? requested : defaultProtocolVersion()
    return jsonRpcResult(msg.id, [
        protocolVersion: negotiated,
        capabilities: [
            tools: [:]
        ],
        serverInfo: info,
        instructions: serverInstructions()
    ])
}

def handleToolsList(msg) {
    // tools/list returns the full catalog in a single response. Pagination was
    // attempted in #180 (page size 50, cursor-based; ported via #190), but in
    // practice many MCP clients -- including Claude.ai's connector -- do NOT
    // iterate `nextCursor` automatically, so any client that ignored pagination
    // only ever saw the first 50 tools (silent catalog truncation, ~50% of the
    // flat-mode catalog invisible to those clients). The MCP protocol allows
    // but does not require server-side pagination of tools/list; the safer
    // default is "send the whole catalog and let the universal response-size
    // guard at handleMcpRequest() backstop oversized responses with a loud
    // -32603 envelope" rather than "split silently and hope the client iterates."
    //
    // Stale clients that pass a `cursor` param get the full catalog regardless;
    // there is no longer a nextCursor in the response, so any iteration loop
    // terminates after one call. Cursor pagination on tools/call (hub_list_devices,
    // hub_list_apps, hub_list_rules, etc. via _paginateList) is unchanged
    // -- that is opt-in and the size guard's "suggestion" hints already point
    // callers at it when needed.
    def all = getToolDefinitions()
    return jsonRpcResult(msg.id, [tools: all])
}

def handleToolsCall(msg) {
    def toolName = msg.params?.name
    def args = msg.params?.arguments ?: [:]
    // For a gateway call (hub_manage_*/hub_read_*) the FAILING tool is args.tool, not the gateway
    // name -- resolve it so the reactive best-practice hint (issue #299) maps to the right tool's
    // guide section. A flat call has no gateway entry, so reactiveToolName stays the leaf name.
    // `args instanceof Map` guards a malformed non-object `arguments` (e.g. a JSON string): probing
    // .tool on a String here (before the try below) would throw and surface as a -32603 instead of
    // the handled path -- fall back to the gateway name and let the try block report it cleanly.
    def reactiveToolName = (getGatewayConfig().containsKey(toolName) && args instanceof Map && args.tool instanceof String) ? args.tool : toolName

    if (!toolName) {
        return jsonRpcError(msg.id, -32602, "Invalid params: tool name required")
    }

    try {
        def result = executeTool(toolName, args)
        // Null result is always an internal tool bug -- surface it as a structured
        // isError envelope instead of letting JsonOutput render the literal string
        // "null" into the wire payload (which looks like a normal tool result).
        if (result == null) {
            mcpLog("error", "server", "Tool ${toolName} returned null -- internal tool bug", null, [details: [tool: toolName]])
            return jsonRpcResult(msg.id, [
                content: [[type: "text", text: groovy.json.JsonOutput.toJson([
                    isError: true, error: "Tool ${toolName} returned no result", tool: toolName
                ])]],
                isError: true
            ])
        }
        // ---- Reactive best-practice hint on a returned error (issue #299, always on) ----
        // The runtime-error contract is [success:false, error:.., note:..]; some refusals also set
        // isError:true. Surface a one-line pointer to the FAILING tool's own guide section on either
        // error shape by mutating the result Map with a bp_warning field, which serializes naturally
        // below. The detector is a pure function that returns null on success-shaped or
        // no-section/permission errors, so there is no meaningful overhead on the hot path.
        if (result instanceof Map && (result.isError == true || result.success == false)) {
            // A best-practice HINT must never mask the genuine tool error: if attaching it ever throws
            // (e.g. a future tool returns an immutable result Map), log and fall through with the
            // original result intact.
            try {
                _applyReactiveBpsWarning(reactiveToolName, args, result)
            } catch (Exception bpErr) {
                mcpLog("warn", "server", "Reactive BPS hint failed for ${toolName}: ${bpErr.message}", null, [details: [tool: toolName]])
            }
        }
        def jsonText
        try {
            jsonText = groovy.json.JsonOutput.toJson(result)
        } catch (Exception serErr) {
            // Tool returned a value JsonOutput cannot encode (Closure, java.util.regex.Pattern,
            // circular Map, etc.). Surface it as a tool bug rather than letting it look like
            // a generic execution failure under the bottom catch.
            mcpLog("error", "server", "Tool ${toolName} returned a non-serializable result: ${serErr.message}", null, [
                details: [tool: toolName, resultType: result?.class?.name, error: serErr.message]
            ])
            return jsonRpcResult(msg.id, [
                content: [[type: "text", text: groovy.json.JsonOutput.toJson([
                    isError: true,
                    error: "Tool ${toolName} returned a result the JSON serializer cannot encode",
                    cause: serErr.message,
                    resultType: result?.class?.name,
                    note: "Internal tool bug -- report with the tool name and arguments used."
                ])]],
                isError: true
            ])
        }
        // Measure the wire-encoded response (text-escape + content/JSON-RPC envelope)
        // rather than the raw inner result, so the guard threshold maps directly onto
        // what handleMcpRequest's outer 128KB check measures. Sizing the inner result
        // alone left a gap zone where escape-heavy payloads (every `"` becomes `\"`)
        // could slip the inner guard yet still trip the outer -32603 fallback that
        // #174 was filed to eliminate.
        // File-wide error contract: a tool that RETURNS a result with isError:true (the gateway
        // disabled/missing-required-param refusals, toolCloneNativeApp / native-wizard soft failures,
        // etc.) MUST surface that on the JSON-RPC envelope so MCP clients route retries -- this
        // function is documented to "flag isError on the JSON-RPC envelope", but until now only the
        // throw / null / non-serializable branches set it, so a *returned* isError stayed invisible
        // top-level (a refused destructive call read as success to a spec-compliant client).
        def envelopeBody = [content: [[type: "text", text: jsonText]]]
        if (result instanceof Map && result.isError == true) {
            envelopeBody.isError = true
        }
        def candidateResponse = jsonRpcResult(msg.id, envelopeBody)
        // Serialize the wire form ONCE here. We measure its byte length for the inner cap,
        // then (on the common under-limit path) hand the already-built string to
        // handleMcpRequest via a __preserialized sentinel so it renders verbatim instead of
        // re-encoding the same object a second time. KEEP byte-accurate getBytes("UTF-8")
        // sizing -- do NOT regress to char length.
        String candidateJson = groovy.json.JsonOutput.toJson(candidateResponse)
        int wireBytes = candidateJson.getBytes("UTF-8").length
        final int responseSizeLimit = hubResponseCapBytes() - 11072  // =120000; ~11 KB headroom under the 131072-byte (128 KiB) hub cap
        if (wireBytes > responseSizeLimit) {
            // Gateway calls (manage_*) carry the real sub-tool name in args.tool; the
            // gateway-config whitelist check rules out a non-gateway caller who happens
            // to pass a stray `tool` arg, which would otherwise mis-route the suggestion.
            boolean isGateway = getGatewayConfig().containsKey(toolName)
            String hintTool = (isGateway && args?.tool instanceof String && args.tool) ? args.tool : toolName
            mcpLog("warn", "server", "Tool ${hintTool} response too large (${wireBytes} > ${responseSizeLimit} bytes) -- returning response_too_large envelope", null, [
                details: [tool: hintTool, gateway: (hintTool != toolName) ? toolName : null, bytes: wireBytes, limit: responseSizeLimit]
            ])
            return jsonRpcResult(msg.id, [content: [[type: "text", text: groovy.json.JsonOutput.toJson(_responseTooLargeEnvelope(hintTool, wireBytes, responseSizeLimit))]]])
        }
        // Single-message verbatim-passthrough: handleMcpRequest's single-Map branch detects
        // this sentinel and renders __preserialized as-is (no re-encode). The batch-collect
        // path re-parses it back to an object so it never leaks into the batch JSON array.
        // The sentinel is internal -- never visible on the wire. The oversize branch above
        // deliberately returns a normal Map (not a sentinel); re-encoding that rare path is fine.
        return [__preserialized: candidateJson]
    } catch (IllegalArgumentException e) {
        mcpLog("warn", "server", "Validation error in ${toolName}: ${e.message}", null, [
            details: [tool: toolName, error: e.message]
        ])
        // Reactive best-practice hint on a thrown validation error (issue #299, always on). Most
        // recoverable tool errors (device-not-found, unsupported command, missing confirm) throw
        // IllegalArgumentException -> -32602, so this is the primary reactive surface. _reactiveBpsWarning
        // already excludes the gate's own refusal + permission errors and returns null when the tool has
        // no dedicated section, so no extra guard is needed here beyond the null-message check.
        def msgText = e.message
        if (e.message) {
            // Same guard as the returned-error path: a hint failure must not lose the validation error.
            try {
                def w = _reactiveBpsWarning(reactiveToolName, args, e.message)
                if (w) msgText = "${e.message} ${w}"
            } catch (Exception bpErr) {
                mcpLog("warn", "server", "Reactive BPS hint failed for ${toolName}: ${bpErr.message}", null, [details: [tool: toolName]])
            }
        }
        return jsonRpcError(msg.id, -32602, "Invalid params: ${msgText}")
    } catch (Exception e) {
        mcpLog("error", "server", "Tool execution error in ${toolName}: ${e.message}", null, [
            details: [tool: toolName, error: e.message],
            stackTrace: e.getStackTrace()?.take(5)?.collect { it.toString() }?.join("\n")
        ])
        // Hubitat's LogWrapper.error() does NOT accept (String, Throwable) — passing the
        // exception object as a 2nd arg throws MissingMethodException, masking the real error
        // (and creating cascading log.error failures). mcpLog above already captured the stack trace.
        log.error "Tool execution error: ${e.message} (${e.class.simpleName})"
        // MCP spec: tool execution errors are returned as successful results with isError flag
        return jsonRpcResult(msg.id, [content: [[type: "text", text: "Tool error: ${e.message}"]], isError: true])
    }
}

// Returned in place of the real result when handleToolsCall trips the size guard. Shape
// is the wire contract for the response_too_large case; keep the field names stable.
def _responseTooLargeEnvelope(String toolName, int actualBytes, int limitBytes) {
    return [
        response_too_large: true,
        truncated: true,
        estimatedBytes: actualBytes,
        sizeLimitBytes: limitBytes,
        tool: toolName,
        suggestion: _responseTooLargeSuggestion(toolName)
    ]
}

// Tool-specific retry hints for the response_too_large envelope. New tools fall through
// to the default branch -- only add a case when the generic hint is misleading.
def _responseTooLargeSuggestion(String toolName) {
    switch (toolName) {
        case "hub_list_devices":
            return "Narrow with filter/labelFilter/capabilityFilter, project fields=['id','label',...], pass a smaller limit, or page with offset+limit. format='ids' is the cheapest shape."
        case "hub_list_apps":
            return "For scope='instances': set includeHidden=false (the default), narrow via filter (builtin / user / disabled / parents / children), or pass cursor to page through the apps list."
        case "hub_get_app_config":
            return "Omit includeSettings -- Room Lighting / RM 5.1 apps can have 500-1000 settings keys. For multi-page apps, call hub_list_app_pages then hub_get_app_config with a specific pageName. If you only need identity, pass summary=true."
        case "hub_get_device_health":
            return "Set includeHealthy=false (the default), narrow staleHours, or pass cursor to page through staleDevices."
        case "hub_get_memory_history":
            return "Pass a smaller limit (e.g. 100). limit=0 returns the entire hub ring buffer which can be thousands of entries."
        case "hub_get_logs":
            return "Narrow with deviceId/appId/level/source/pattern, set a smaller limit, or filter by time window (since/until). The tool already truncates per-entry messages but cannot trim the entry count below the requested limit."
        case "hub_export_native_app":
            return "Large app payloads can exceed the inline cap. Pass saveAs=<filename.json> to write the export to the hub File Manager instead of returning it inline."
        case "hub_get_info":
        case "hub_get_jobs":
        case "hub_get_performance_stats":
        case "hub_get_metrics":
            return "Hub status payload is unusually large -- consider polling at a lower frequency or fetching a single subsection via the matching sub-tool if available."
        case "hub_get_source":
            return "Source file exceeds the inline cap. Use offset/length to read it in chunks, use hub_list_files / hub_read_file via the File Manager bridge, or fetch the source from version control instead."
        default:
            // Default-branch hits are interesting telemetry -- they're the tools we should
            // be adding specific suggestions for. info level so it only surfaces when log
            // level is elevated for debugging.
            mcpLog("info", "server", "response_too_large for tool ${toolName} hit the generic suggestion branch -- consider adding a specific case", null, [details: [tool: toolName]])
            return "Narrow your query (filters, smaller limit, or projection of fields) or pass cursor if this tool supports pagination."
    }
}

// Shared cursor decoder for opt-in tool-level pagination. Cursor is the opaque numeric
// offset returned in a prior call's nextCursor; null/"" means "start at 0". Anything else
// throws IllegalArgumentException so the dispatch layer surfaces -32602. Raw cursor is
// sanitized before being echoed back so a defective client can't pollute the hub log.
def _parseListCursor(cursor, int totalSize, String toolName) {
    if (cursor == null || cursor == "") return 0
    def safeCursor = (cursor?.toString() ?: "").replaceAll(/[\r\n]/, " ").take(80)
    int offset
    try {
        offset = (cursor as String).toInteger()
    } catch (NumberFormatException ignored) {
        throw new IllegalArgumentException("cursor must be the opaque string returned by a prior ${toolName} nextCursor (got: ${safeCursor})")
    }
    if (offset < 0) {
        throw new IllegalArgumentException("cursor ${safeCursor} is out of range (must be >= 0)")
    }
    // offset==0 on an empty list is the well-defined "first page of nothing" case and is
    // allowed; any positive cursor on an empty or fully-paged list is out-of-range. Without
    // the `offset > 0` clause, cursor='999' against an empty list would slip through here
    // and surface as a cryptic IllegalArgumentException from subList(999, 0) downstream.
    if (offset > 0 && offset >= totalSize) {
        throw new IllegalArgumentException("cursor ${safeCursor} is out of range (size=${totalSize})")
    }
    return offset
}

// Compose cursor decoding + subList + nextCursor into one place so each paginated tool
// is a single call. cursor=null returns the whole list with no nextCursor (the opt-in
// contract: callers who didn't ask for pagination get the legacy shape).
def _paginateList(List fullList, cursor, int pageSize, String toolName) {
    if (cursor == null) return [page: fullList, nextCursor: null]
    int start = _parseListCursor(cursor, fullList.size(), toolName)
    int end = Math.min(start + pageSize, fullList.size())
    return [page: fullList.subList(start, end), nextCursor: end < fullList.size() ? end.toString() : null]
}

// ==================== CATEGORY GATEWAY PROXY ====================
// Domain-named gateways that consolidate lesser-used tools behind a single MCP tool per domain.
// Each gateway: call with no args → catalog of tool schemas; call with tool + args → execute.
// Modeled after ha-mcp PR #637 (category gateway proxy pattern).

def getGatewayConfig() {
    return [
        hub_manage_custom_rules: [
            description: "Legacy MCP custom-rule engine (sandbox rules that fire as installed apps but are NOT visible in Hubitat's RM UI): create, read, update, delete, test, export, import, and clone. Write ops (create/delete/export/import/clone) require the Custom Rule Engine toggle ON in MCP settings; when OFF only get/test (and the enabled toggle via update) work. For native Rule Machine rules visible in the hub UI use hub_manage_rule_machine / hub_manage_native_rules_and_apps instead. Read-only views are also in hub_read_rules.",
            tools: ["hub_get_custom_rule", "hub_create_custom_rule", "hub_update_custom_rule", "hub_delete_custom_rule", "hub_test_custom_rule", "hub_export_custom_rule", "hub_import_custom_rule", "hub_clone_custom_rule"],
            summaries: [
                hub_get_custom_rule: "List custom rules (omit ruleId) or get one rule's detail; detailed=true (with ruleId) adds diagnostics. Args: ruleId?, detailed?, cursor?",
                hub_create_custom_rule: "Create a new MCP custom (sandbox) rule. Args: name, triggers, actions, conditions?, enabled?",
                hub_update_custom_rule: "Update a custom rule (enabled toggle, or structural changes when the engine toggle is ON). Args: ruleId, enabled?|name?|triggers?|conditions?|actions?",
                hub_delete_custom_rule: "Permanently delete a custom rule (auto-backs up first). Args: ruleId, confirm=true",
                hub_test_custom_rule: "Dry-run a custom rule without executing actions. Args: ruleId",
                hub_export_custom_rule: "Export a custom rule to JSON and save it to the hub File Manager. Args: ruleId, saveAs? (filename)",
                hub_import_custom_rule: "Import a custom rule from exported JSON (creates a NEW rule, fresh ruleId). Args: exportData (the export OBJECT from hub_export_custom_rule), name? (override), deviceMapping? (remap old->new device IDs for cross-hub import)",
                hub_clone_custom_rule: "Clone an existing custom rule (starts disabled). Args: ruleId, name? (name for the clone; defaults to 'Copy of <original>')"
            ],
            // BM25 search hints — extra keywords that don't appear in summaries but help discovery
            searchHints: [
                hub_get_custom_rule: "read fetch inspect list show custom mcp sandbox rule automation diagnostics",
                hub_create_custom_rule: "add new custom mcp sandbox rule automation",
                hub_update_custom_rule: "modify edit change enable disable custom mcp sandbox rule automation",
                hub_delete_custom_rule: "remove automation custom mcp sandbox",
                hub_test_custom_rule: "simulate preview validate check automation custom",
                hub_export_custom_rule: "save download share automation custom file manager persist",
                hub_import_custom_rule: "load upload restore automation custom",
                hub_clone_custom_rule: "copy duplicate automation custom"
            ]
        ],
        hub_manage_variables: [
            description: "Manage hub variables (every type: Number, Decimal, String, Boolean, DateTime), their connector devices, and rule-engine variables. Issue #92: full read/write CRUD via the modern Hub Variable API + wizard; observe changes via hub_list_variable_changes.",
            tools: ["hub_list_variables", "hub_get_variable", "hub_set_variable", "hub_create_variable", "hub_delete_variable", "hub_create_connector", "hub_delete_connector", "hub_list_variable_changes"],
            summaries: [
                hub_list_variables: "List all hub variables (with type/connector linkage) and rule-engine variables.",
                hub_get_variable: "Get a variable's value + metadata (type, deviceId, attribute). Args: name",
                hub_set_variable: "Set an existing variable's value. Falls back to rule_engine namespace when no hub var matches. Args: name, value",
                hub_create_variable: "Create a new hub variable, or several at once. Single: name, type (Number|Decimal|String|Boolean|DateTime), value, confirm=true. Bulk: variables=[{name,type,value},...], confirm=true (mutually exclusive with the single form). A String value must be non-empty",
                hub_delete_variable: "Permanently delete a variable (DESTRUCTIVE — also removes its connector if any). Args: name, confirm=true, [force=true if rules reference it]",
                hub_create_connector: "Create a virtual-device connector for an existing hub variable. For Number/Decimal vars, connectorType picks the device type (Dimmer|Variable|Volume|ColorTemp|Humidity|Illuminance, default Variable). Args: name, connectorType?, confirm=true",
                hub_delete_connector: "Remove the connector device for a hub variable (variable itself unchanged). Args: name, confirm=true",
                hub_list_variable_changes: "Recent hub-variable changes since the MCP app last started. Args: name (optional filter), sinceMs (optional), limit (optional)"
            ],
            searchHints: [
                hub_list_variables: "show all global state connector",
                hub_get_variable: "read fetch lookup global state",
                hub_set_variable: "write update change store global state",
                hub_create_variable: "add new hub variable global",
                hub_delete_variable: "remove drop destroy purge cleanup orphan stranded BAT_ stale variable",
                hub_create_connector: "expose hub variable as virtual device switch dimmer",
                hub_delete_connector: "unlink delete connector device variable",
                hub_list_variable_changes: "watch observe changes events recent variable timeline"
            ]
        ],
        hub_manage_rooms: [
            description: "Manage hub rooms: list, view details, create, delete, and rename rooms.",
            tools: ["hub_list_rooms", "hub_get_room", "hub_create_room", "hub_delete_room", "hub_update_room"],
            summaries: [
                hub_list_rooms: "List all rooms with IDs, names, and device counts",
                hub_get_room: "Get room details with assigned devices. Args: room (name or ID)",
                hub_create_room: "Create a new room, optionally assigning devices at creation. Args: name, deviceIds? (device IDs to assign), confirm=true",
                hub_delete_room: "Permanently delete a room. Args: room (name or ID), confirm=true",
                hub_update_room: "Rename a room. Args: room (name or ID), newName, confirm=true"
            ],
            searchHints: [
                hub_list_rooms: "show all locations areas groups",
                hub_get_room: "view location area group",
                hub_create_room: "add new location area group",
                hub_delete_room: "remove location area group",
                hub_update_room: "change name location area group"
            ]
        ],
        // Option A: Virtual device tools moved to core tools/list (full inputSchema visible)
        // manage_hub_info dissolved — zwave/zigbee moved to hub_manage_diagnostics; the update-status read folded into hub_get_info (includeAppUpdate) and the firmware INSTALL is the core hub_update_firmware
        // hub_create_backup promoted to core; the old hub_call_zwave_repair was absorbed into hub_call_zwave (hub_manage_radio)
        hub_manage_destructive_ops: [
            description: "DESTRUCTIVE hub operations: reboot, shutdown, permanent device deletion, and radio network/fabric resets + firmware flashes. All operations are irreversible or cause significant downtime — confirm with user first.",
            tools: ["hub_reboot", "hub_shutdown", "hub_delete_device", "hub_call_destructive_radio"],
            summaries: [
                hub_reboot: "Reboot the hub (DISRUPTIVE, 1-3 min downtime). To install a pending hub firmware update instead, use hub_update_firmware. Args: confirm=true",
                hub_shutdown: "Power OFF the hub (EXTREME, requires physical restart). Args: confirm=true",
                hub_delete_device: "Permanently delete any device (MOST DESTRUCTIVE, no undo). Args: deviceId, confirm=true",
                hub_call_destructive_radio: "Reset a radio's network/fabric (unpairs ALL devices) or flash firmware (can brick hardware). Args: radio (zwave|zigbee|matter), action (reset|device_firmware_start|device_firmware_abort|zwave_chip_firmware|zigbee_firmware), confirm=true"
            ],
            searchHints: [
                hub_reboot: "restart reset power cycle boot",
                hub_shutdown: "power off turn off stop halt",
                hub_delete_device: "remove ghost orphan zwave zigbee stuck failed pairing",
                hub_call_destructive_radio: "reset wipe zwave zigbee matter network fabric exclude all firmware flash chip radio ota brick factory"
            ]
        ],
        hub_read_apps_code: [
            description: "Read-only inspection of installed apps, drivers, libraries, code bundles, code backups, and HPM packages: list apps (by code type or running instance), list drivers, view Groovy source, list installed bundles, browse code backups, inspect an installed app's config/pages, and list HPM-tracked packages. All operations are read-only; writes live in hub_manage_code.",
            tools: ["hub_list_apps", "hub_list_drivers", "hub_get_source", "hub_list_libraries", "hub_list_bundles", "hub_list_backups", "hub_get_backup", "hub_list_device_dependents", "hub_get_app_config", "hub_list_app_pages", "hub_list_hpm_packages"],
            summaries: [
                hub_list_apps: "List installed apps. scope='types' (installed app code library) or 'instances' (running apps with parent/child tree). Args: scope, filter?, includeHidden?, cursor?",
                hub_list_drivers: "List all installed drivers on the hub",
                hub_get_source: "Get app/driver/library Groovy source with chunked reading. Args: type (app|driver|library), id, offset?, length?",
                hub_list_libraries: "List installed Groovy libraries (id, name, namespace, version). Pair with hub_get_source(type='library', id) to read source. Args: cursor?",
                hub_list_bundles: "List installed code bundles (the Bundle-Manager containers HPM delivers code in; distinct from Libraries Code). Returns id, name, namespace, private, and a contains summary. Find a bundle id for hub_delete_bundle/hub_export_bundle. Args: cursor?",
                hub_list_backups: "List auto-created source code backups",
                hub_get_backup: "Get source from a backup. Args: backupKey",
                hub_list_device_dependents: "List all apps that reference a device (Room Lighting, Rule Machine, Groups, etc.). Args: deviceId",
                hub_get_app_config: "Read an installed app's configuration page (sections, inputs, current values). Works for Rule Machine, Room Lighting, Basic Rules, HPM, etc. Args: appId, pageName?, includeSettings?",
                hub_list_app_pages: "List known page names for a multi-page app (HPM, Room Lighting, etc.). Args: appId",
                hub_list_hpm_packages: "List all HPM-tracked packages (name, version, beta flag, apps, drivers, files). includeDrift=true surfaces missing-required/orphan components. Args: hpmAppId?, includeDrift?, packageFilter?"
            ],
            searchHints: [
                hub_list_apps: "show installed applications integrations apps list code types running instances builtin user parent child tree",
                hub_list_drivers: "show installed device handlers types",
                hub_get_source: "view read application driver library groovy code namespace include",
                hub_list_libraries: "list show installed groovy libraries code namespace include shared modules discover library id",
                hub_list_bundles: "list show installed bundles bundle manager hpm package zip containers code delivery discover bundle id apps drivers libraries",
                hub_list_backups: "show saved previous versions revisions",
                hub_get_backup: "view read saved previous version revision",
                hub_list_device_dependents: "which apps use device reference inUseBy appsUsing dependencies affected by",
                hub_get_app_config: "read inspect app configuration page settings inputs values rule machine room lighting hpm mode manager",
                hub_list_app_pages: "page names sub-pages pageName multi-page hpm navigation discover",
                hub_list_hpm_packages: "package manager HPM tracked installed manifest version inventory community apps drivers drift orphan missing required"
            ]
        ],
        hub_manage_backup: [
            description: "Hub-database backup management plus source-code backup restore (issue #259 item #1): list/restore/delete local + cloud whole-hub backups, restore an uploaded external backup, and restore source-code backups. Creating a backup and setting the automatic-backup schedule is the core hub_create_backup tool (kept top-level as the pre-flight for destructive ops). Hub-DB restore/delete are destructive — a hub-DB restore REBOOTS the hub — and need confirm + a recent backup. The read tools (hub_list_backups/hub_get_backup) are also in hub_read_apps_code.",
            tools: ["hub_list_backups", "hub_get_backup", "hub_restore_backup", "hub_delete_backup"],
            summaries: [
                hub_list_backups: "List backups. scope=source (code) | hub_local | hub_cloud | hub | all. Args: scope?, cursor?",
                hub_get_backup: "Get source from a code backup. Args: backupKey",
                hub_restore_backup: "Restore a code/rule backup (scope=source + backupKey) OR the whole hub DB (scope=hub_local + fileName | hub_cloud + cloudBackupPassword | hub_uploaded + backupUrl -- REBOOTS). Args: scope?, backupKey?/fileName?/cloudBackupPassword?/backupUrl?, confirm",
                hub_delete_backup: "Delete a whole-hub DB backup. Args: location (local|cloud), fileName?/path?, confirm"
            ],
            searchHints: [
                hub_list_backups: "list show backups code source whole hub database local cloud restore points",
                hub_get_backup: "view read saved previous version revision source",
                hub_restore_backup: "restore revert roll back code rule whole hub database disaster recovery migration upload reboot",
                hub_delete_backup: "delete remove prune hub database backup local cloud free space recovery point"
            ]
        ],
        hub_manage_code: [
            description: "Install, update, and delete hub apps, drivers, libraries, and code bundles (install/delete/export). All operations modify hub code and require Write master. Read-only counterparts (hub_get_source, list_*) live in the hub_read_apps_code gateway.",
            tools: ["hub_create_app", "hub_create_driver", "hub_update_app", "hub_update_driver", "hub_delete_item", "hub_create_library", "hub_update_library", "hub_install_bundle", "hub_delete_bundle", "hub_export_bundle"],
            summaries: [
                hub_create_app: "Install new app code (source|sourceFile|importUrl), OR with installAsUserApp=<codeAppId> create a running instance from already-installed code (mutually exclusive). To save context prefer importUrl (hub fetches the source itself) or hub_write_file + sourceFile; inline source for stubs only. confirm=true",
                hub_create_driver: "Install new driver. To save context prefer importUrl (hub fetches the source) or hub_write_file + sourceFile; inline source for stubs only. For 1: source|sourceFile|importUrl. For >1: USE BULK (single round-trip: installs=[{source|sourceFile|importUrl},...]). confirm=true",
                hub_update_app: "Modify existing app code (CRITICAL), and/or enable OAuth on it. To save context prefer importUrl (hub fetches the source itself) or hub_write_file + sourceFile over inline source. Args: appId, source|sourceFile|importUrl|resave, oauth ({enabled,client_id?,client_secret?,refresh_secret?} -- enable/configure OAuth, returns the clientId/secret), confirm=true",
                hub_update_driver: "Modify existing driver code (CRITICAL). For 1 driver: driverId+source|sourceFile|importUrl|resave. For >1 drivers: USE BULK (single round-trip: updates=[{driverId,sourceFile|importUrl},...]). To save context prefer importUrl (hub fetches) or hub_write_file + sourceFile over inline. confirm=true",
                hub_delete_item: "Permanently delete an app/driver/library (DESTRUCTIVE, auto-backs up). Args: type (app|driver|library), id, confirm=true",
                hub_create_library: "Install new Groovy library (#include namespace.Name). To save context prefer importUrl (hub fetches the source) or hub_write_file + sourceFile; inline source for stubs only. Args: source|sourceFile|importUrl, confirm=true",
                hub_update_library: "Modify existing library code. To save context prefer importUrl (hub fetches) or hub_write_file + sourceFile over inline. Args: libraryId, source|sourceFile|importUrl|resave, confirm=true",
                hub_install_bundle: "Install a code bundle (.zip) from a URL the way HPM does (hub fetches+unpacks into Libraries/Apps/Drivers Code). Args: importUrl (zip), primary?, confirm=true",
                hub_delete_bundle: "Delete an installed code bundle container by id (DESTRUCTIVE; verifies via re-list). Code it delivered may remain in Code -- delete separately. Args: bundleId (from hub_list_bundles), confirm=true",
                hub_export_bundle: "Export an installed bundle's .zip to the File Manager (downloadable at /local/<file>). Args: bundleId (from hub_list_bundles), saveAs?"
            ],
            searchHints: [
                hub_create_app: "add new application integration groovy",
                hub_create_driver: "add new device handler type groovy",
                hub_update_app: "modify change edit application groovy push deploy oauth enable client id secret access token endpoint",
                hub_update_driver: "modify change edit device handler type groovy push deploy",
                hub_delete_item: "remove uninstall application integration device handler driver type groovy library shared",
                hub_create_library: "add new shared groovy library include namespace",
                hub_update_library: "modify change edit groovy library shared code push deploy",
                hub_install_bundle: "install bundle zip package hpm hubitat package manager uploadZipFromUrl library delivery deploy code",
                hub_delete_bundle: "delete remove uninstall bundle container bundle manager code zip package by id",
                hub_export_bundle: "export download save bundle zip to file manager backup copy archive container"
            ]
        ],
        // Option B: manage_logs_diagnostics split into logs + diagnostics
        hub_manage_logs: [
            description: "System logs, performance stats, and log settings: hub logs, device/app performance stats, scheduled jobs, MCP debug logs, and log level configuration. (Device/app/location event history: use the core hub_list_device_events tool.)",
            tools: ["hub_get_logs", "hub_get_performance_stats", "hub_get_jobs", "hub_get_debug_logs", "hub_delete_debug_logs", "hub_set_log_level"],
            summaries: [
                hub_get_logs: "Get Hubitat system logs, most recent first. Args: level (trace/debug/info/warn/error), source (substring), pattern (regex), patterns + patternMode (multi-regex any/all), since/until (ISO-8601 or '30m'/'2h'/'1d'), deviceId or appId (server-side scope), limit",
                hub_get_performance_stats: "Get device/app performance stats (count, % busy, total ms, state size, events, large state flag). Args: type (device/app/both), sortBy (pct/count/stateSize/totalMs/name), limit",
                hub_get_jobs: "Get scheduled jobs, running jobs, and hub actions",
                hub_get_debug_logs: "Get MCP internal debug logs (mode='logs', default) or logging status (mode='status'). Args: mode, level, component (e.g. server/rule), ruleId, limit",
                hub_delete_debug_logs: "Clear all MCP debug log entries",
                hub_set_log_level: "Set minimum log level threshold. Args: level (debug/info/warn/error)"
            ],
            searchHints: [
                hub_get_logs: "errors warnings messages trace syslog output print recent latest newest device app scope regex pattern filter time window since until last hour minute",
                hub_get_performance_stats: "slow cpu busy resource usage hog bottleneck",
                hub_get_jobs: "scheduled cron timer recurring what is running next automation",
                hub_get_debug_logs: "mcp internal troubleshoot trace logging status buffer capacity how many level",
                hub_delete_debug_logs: "wipe reset mcp internal",
                hub_set_log_level: "verbosity debug trace quiet"
            ]
        ],
        hub_manage_diagnostics: [
            description: "Health monitoring, diagnostics, and radio details: hub metrics, memory history, garbage collection, device health, radio info, and state snapshots. (Z-Wave/Zigbee/Matter radio writes — repair, inclusion, config, reset — live in hub_manage_radio. Custom-rule diagnostics: use hub_get_custom_rule with detailed=true.)",
            tools: ["hub_get_metrics", "hub_get_memory_history", "hub_call_gc", "hub_get_device_health", "hub_get_radio_details", "hub_list_captured_states", "hub_delete_captured_state"],
            summaries: [
                hub_get_metrics: "Get hub metrics (memory, temp, DB) with CSV trend history + the hub's own health alerts (radio offline, backup failures, low memory, DB bloat, safeMode). Read-only by default; recordSnapshot=true also persists a snapshot. Args: recordSnapshot, trendPoints",
                hub_get_memory_history: "Get free OS memory and CPU load history. Returns most recent entries with summary stats. Args: limit (default 100, 0 for all). Requires Read master",
                hub_call_gc: "Force JVM garbage collection to reclaim memory. Returns before/after free memory. Requires the Write master",
                hub_get_device_health: "Check device staleness; run network diagnostics: ICMP-ping arbitrary IPs (router, NAS, server), traceroute to one IPv4, WAN download speedtest; and/or blink the hub identify-LED. Args: staleHours, includeHealthy, pingHosts (max 5 IPv4), pingCount (1-5), traceroute (IPv4), speedtest (bool), identifyHub",
                hub_get_radio_details: "Z-Wave/Zigbee/Matter radio info + the read-only radio surface (topology, per-node state, status pollers, channel scan, SmartStart, firmware lists). Args: radio (zwave|zigbee|matter, omit for Z-Wave+Zigbee), node_id?, include_topology/status/logs/channel_scan/smartstart/firmware?. Requires Read master",
                hub_list_captured_states: "List saved device state snapshots",
                hub_delete_captured_state: "Delete a captured state by stateId, or ALL captured states when stateId is omitted. Args: stateId (optional)"
            ],
            searchHints: [
                hub_get_metrics: "temperature database size trending monitoring over time health alerts safe mode radio offline backup failed low memory",
                hub_get_memory_history: "ram free used leak trending over time java heap nio",
                hub_call_gc: "gc garbage collection free reclaim ram cleanup java heap memory",
                hub_get_device_health: "stale offline dead unresponsive battery not reporting ping icmp reachable network ip lan host router gateway traceroute route hops speedtest bandwidth wan download internet speed identify led blink locate physical hub",
                hub_get_radio_details: "zwave zigbee matter thread fabric mesh network frequency firmware 908mhz 700 800 series channel pan coordinator 2400mhz radio commissioned node topology smartstart status",
                hub_list_captured_states: "saved snapshot bookmark remember device values",
                hub_delete_captured_state: "remove delete clear saved snapshot bookmark all"
            ]
        ],
        hub_manage_radio: [
            description: "Manage the Z-Wave, Zigbee, and Matter radios: configure (enable/disable, region, channel, power) and run lifecycle operations (repair, inclusion/exclusion, node maintenance, replace/remove, Zigbee reboot/rebuild/scan, Matter pair/window). Reads live in hub_get_radio_details (also in hub_read_diagnostics). DESTRUCTIVE resets + firmware flashes live in hub_manage_destructive_ops (hub_call_destructive_radio).",
            tools: ["hub_get_radio_details", "hub_set_zwave", "hub_set_zigbee", "hub_call_zwave", "hub_call_zigbee", "hub_call_matter"],
            summaries: [
                hub_get_radio_details: "Z-Wave/Zigbee/Matter radio info + read-only radio surface (topology, per-node state, status pollers, channel scan, SmartStart, firmware lists). Args: radio?, node_id?, include_topology/status/logs/channel_scan/smartstart/firmware?",
                hub_set_zwave: "Configure the Z-Wave radio (idempotent): enable/disable, region, long-range channel. Args: enabled?, region?, long_range_channel?, confirm (to disable)",
                hub_set_zigbee: "Configure the Zigbee radio (idempotent): enable/disable, channel + power, radio settings (rebuild-on-reboot, ping-inactive), per-device keep-alive ping. Args: enabled?, channel?, power_level?, rebuild_on_reboot?, ping_inactive?, ping_device?, confirm (to disable)",
                hub_call_zwave: "Z-Wave lifecycle ops. Args: action (repair_start/cancel, repair_node, inclusion_start/stop, grant_keys/grant_code, exclusion_start/stop ⚠️, node_refresh/rediscover/reinitialize, refresh_stats, node_replace, node_replace_stop, node_remove ⚠️, antenna_test_start/continue, smartstart_delete), node_id? (per-node), confirm (exclusion_start/node_remove)",
                hub_call_zigbee: "Zigbee ops. Args: action (radio_reboot, rebuild_network, channel_scan)",
                hub_call_matter: "Matter ops. Args: action (enable/disable — needs hub reboot, pair, open_pairing_window), setup_code? (pair), node_id? (open_pairing_window), confirm (disable)"
            ],
            searchHints: [
                hub_get_radio_details: "zwave zigbee matter thread fabric mesh network firmware channel pan coordinator radio commissioned node topology smartstart status read",
                hub_set_zwave: "zwave radio enable disable turn on off region rf frequency long range channel configure settings idempotent",
                hub_set_zigbee: "zigbee radio enable disable turn on off channel power level transmit configure settings idempotent rebuild reboot ping inactive keep-alive device",
                hub_call_zwave: "zwave repair heal rebuild mesh include pair join exclude unpair remove failed node refresh rediscover reinitialize reinit replace stop abort antenna test smartstart s2 dsk security grant secure",
                hub_call_zigbee: "zigbee reboot restart radio rebuild network mesh channel scan energy",
                hub_call_matter: "matter enable disable thread pair commission setup code open pairing window share fabric node"
            ]
        ],
        hub_manage_files: [
            description: "Manage hub File Manager: list, read, write, and delete files stored on the hub.",
            tools: ["hub_list_files", "hub_read_file", "hub_write_file", "hub_delete_file"],
            summaries: [
                hub_list_files: "List files in File Manager (names, sizes, URLs)",
                hub_read_file: "Read file content. Args: fileName, offset, length",
                hub_write_file: "Write file to File Manager. Args: fileName, content, confirm=true",
                hub_delete_file: "Delete file from File Manager (auto-backs up first to <name>_backup_<ts>, unless it's already a backup). Args: fileName, confirm=true"
            ],
            searchHints: [
                hub_list_files: "show uploaded stored csv json text data",
                hub_read_file: "view open contents download stored data",
                hub_write_file: "upload save store create csv json text data",
                hub_delete_file: "remove clean up stored data"
            ]
        ],
        hub_read_diagnostics: [
            description: "Read-only hub health, logs, and diagnostics: system logs, performance stats, scheduled jobs, MCP debug logs, hub metrics, free-memory/CPU history, device health/staleness, Z-Wave/Zigbee radio details, and saved state snapshots. All operations are read-only; the matching writes (gc, Z-Wave repair, clear logs, set log level, delete snapshots) live in hub_manage_logs / hub_manage_diagnostics.",
            tools: ["hub_get_logs", "hub_get_performance_stats", "hub_get_jobs", "hub_get_debug_logs", "hub_get_metrics", "hub_get_memory_history", "hub_get_device_health", "hub_get_radio_details", "hub_list_captured_states"],
            summaries: [
                hub_get_logs: "Get Hubitat system logs, most recent first. Args: level, source, pattern/patterns, since/until, deviceId|appId, limit",
                hub_get_performance_stats: "Get device/app performance stats (count, % busy, total ms, state size, events). Args: type, sortBy, limit",
                hub_get_jobs: "Get scheduled jobs, running jobs, and hub actions",
                hub_get_debug_logs: "Get MCP internal debug logs (mode='logs') or logging status (mode='status'). Args: mode, level, component (e.g. server/rule), ruleId, limit",
                hub_get_metrics: "Get hub metrics (memory, temp, DB) with CSV trend history + the hub's own health alerts (radio offline, backup failures, low memory, DB bloat, safeMode). Read-only by default; pass recordSnapshot=true to also append a snapshot to the File Manager. Args: recordSnapshot?, trendPoints?",
                hub_get_memory_history: "Get free OS memory and CPU load history with summary stats. Args: limit",
                hub_get_device_health: "Check device staleness; run network diagnostics (ICMP-ping arbitrary IPs, traceroute to one IPv4, WAN download speedtest); and/or blink the hub identify-LED. Args: staleHours, includeHealthy, pingHosts, pingCount, traceroute, speedtest, identifyHub",
                hub_get_radio_details: "Z-Wave and/or Zigbee radio info (firmware, channel, PAN/home ID, device count), or Matter fabric/device details. Args: radio (zwave|zigbee|matter, omit for Z-Wave+Zigbee)",
                hub_list_captured_states: "List saved device state snapshots"
            ],
            searchHints: [
                hub_get_logs: "errors warnings messages trace syslog output recent latest device app scope regex pattern filter time window since until",
                hub_get_performance_stats: "slow cpu busy resource usage hog bottleneck",
                hub_get_jobs: "scheduled cron timer recurring what is running next automation",
                hub_get_debug_logs: "mcp internal troubleshoot trace logging status buffer capacity level",
                hub_get_metrics: "temperature database size trending monitoring memory over time snapshot history health alerts safe mode radio offline backup failed weak mesh",
                hub_get_memory_history: "ram free used leak trending over time java heap nio",
                hub_get_device_health: "stale offline dead unresponsive battery not reporting ping icmp reachable network ip lan host router traceroute route hops speedtest bandwidth wan download internet speed identify led blink locate",
                hub_get_radio_details: "zwave zigbee matter thread fabric mesh network frequency firmware channel pan coordinator radio commissioned node",
                hub_list_captured_states: "saved snapshot bookmark remember device values"
            ]
        ],
        hub_read_rules: [
            description: "Read-only inspection of automation rules: list/inspect MCP custom rules (legacy engine), list native Rule Machine rules + check rule health, and list/read Visual Rules Builder rules. All operations are read-only; rule writes live in hub_manage_custom_rules, hub_manage_rule_machine, and hub_manage_native_rules_and_apps.",
            tools: ["hub_get_custom_rule", "hub_test_custom_rule", "hub_list_rules", "hub_get_rule_health", "hub_list_rule_local_variables", "hub_get_visual_rule"],
            summaries: [
                hub_get_custom_rule: "List MCP custom rules (omit ruleId) or get one rule's detail; detailed=true (with ruleId) adds diagnostics. Args: ruleId?, detailed?, cursor?",
                hub_test_custom_rule: "Dry-run an MCP custom rule without executing actions. Args: ruleId",
                hub_list_rules: "List all native Rule Machine rules (RM 4.x + 5.x) with IDs and labels",
                hub_get_rule_health: "Inspect a rule (Rule Machine OR Visual Rules Builder) for broken state — compiled `broken` boolean / graph validationErrors, plus BROKEN markers, configPage errors, multiple-flag corruption. Args: appId, source",
                hub_list_rule_local_variables: "List a Rule Machine rule's local variables (name/type/value) from state.allLocalVars. Distinct from hub_list_variables (hub globals). Args: appId",
                hub_get_visual_rule: "List Visual Rules Builder rules (omit appId) or read one rule's full JSON definition + format. Args: appId?"
            ],
            searchHints: [
                hub_get_custom_rule: "read fetch inspect list show custom mcp sandbox rule automation diagnostics",
                hub_test_custom_rule: "simulate preview validate check automation custom dry run",
                hub_list_rules: "rule machine rules native builtin automation list enumerate",
                hub_get_rule_health: "broken validate inspect rule health diagnostic broken trigger broken action multiple flag corruption visual rules builder button controller basic rule classic app validationErrors",
                hub_list_rule_local_variables: "rule local variables list allLocalVars per-rule variable name type value rule machine RM setLocalVariable",
                hub_get_visual_rule: "visual rules builder VRB read list show inspect automation json definition when then else nodes graph"
            ]
        ],
        hub_manage_native_rules_and_apps: [
            description: "Native classic-app CRUD + Rule Machine runtime control. Use for: create/edit any non-RM classic SmartApp (Room Lighting, Button Controller, Notifier, Groups+Scenes) via hub_set_native_app (Visual Rules use hub_set_visual_rule); delete/clone/export/import any classic app by appId; and RMUtils runtime control of RM rules (list/run, pause/resume, set private boolean, health). To author a Rule Machine rule's triggers/actions/conditions — 'create a rule machine rule', 'make a Hubitat rule' — use the dedicated hub_manage_rule_machine gateway (hub_set_rule) instead; that is the default rule-authoring path. Not the legacy custom_* sandbox engine. Writes snapshot first (restore via hub_list_backups + hub_restore_backup); destructive ops need confirm=true + a recent backup. RM 5.1 writes are async — on success:false / partial:true, verify via hub_get_app_config(appId) before retrying.",
            tools: ["hub_list_rules", "hub_call_rule", "hub_set_rule_paused", "hub_set_rule_private_boolean", "hub_set_native_app", "hub_set_app_disabled", "hub_delete_native_app", "hub_clone_native_app", "hub_export_native_app", "hub_import_native_app", "hub_get_rule_health"],
            summaries: [
                hub_list_rules: "List all Rule Machine rules (RM 4.x + 5.x) with IDs and labels (uses RMUtils — RM only)",
                hub_call_rule: "Trigger an RM rule lifecycle verb. Args: ruleId, action (rule/actions/stop/start, default rule). rule/actions use RMUtils; stop/start toggle the stopRule button (start also resets private boolean).",
                hub_set_rule_paused: "Pause or resume an RM rule (RMUtils). Args: ruleId, value (true=pause, false=resume)",
                hub_set_rule_private_boolean: "Set an RM rule's private boolean (RMUtils). Args: ruleId, value (bool)",
                hub_set_native_app: "Create or edit any classic native app (Room Lighting, Button Controller, Basic Rule, Notifier, Groups+Scenes, etc.) — generic upsert. Omit appId to create (appType, name); provide appId to edit via settings/button/walkStep. buttonRule={controllerId, buttonNumber, event} creates a Button Rule through its parent controller. Auto-backs-up before edits. For Rule Machine RULES use hub_set_rule (in hub_manage_rule_machine). Args: appId (omit=create), appType, name, settings|button|walkStep|buttonRule, pageName (opt), stateAttribute (opt), confirm.",
                hub_delete_native_app: "Delete any classic native app (soft by default, force=true for hard). Auto-backs-up first. Args: appId, force (opt), confirm",
                hub_set_app_disabled: "Enable or disable any installed app without deleting it (reversible red-X). Args: appId, disabled (bool). Read-back verified. For RM rules prefer hub_set_rule_paused.",
                hub_clone_native_app: "Clone an existing rule/app via Hubitat's first-party appCloner. Cheaper than rebuilding from scratch via the wizard. Args: appId (alias sourceAppId), newName (opt), confirm. Returns newAppId.",
                hub_export_native_app: "Export a rule/app to its canonical JSON shape via Hubitat's first-party appCloner. Args: appId (alias sourceAppId), saveAs (opt File Manager filename). Returns the JSON content (and writes to File Manager if saveAs given).",
                hub_import_native_app: "Create a new rule/app from a previously-exported JSON via Hubitat's first-party appCloner. Args: jsonContent | fromFile, parentHintAppId (existing rule under the target parent — used to seed the cloner), newName (opt), confirm. Returns newAppId.",
                hub_get_rule_health: "Inspect a rule (Rule Machine OR Visual Rules Builder) for broken state — compiled `broken` boolean / graph validationErrors, label *BROKEN*, **Broken Trigger** markers, configPage errors, multiple-flag corruption. Args: appId, source. Returns {ok, broken, source, ruleFormat, issues, ...}. Auto-attached to hub_set_rule and hub_set_visual_rule responses too."
            ],
            searchHints: [
                hub_list_rules: "rule machine rules native builtin automation list enumerate",
                hub_call_rule: "trigger fire execute native rule machine rule",
                hub_set_rule_paused: "pause resume disable enable stop unpause temporarily rule machine rule",
                hub_set_rule_private_boolean: "private boolean flag rule machine rule condition",
                hub_set_native_app: "create edit modify change native room lighting button controller notifier groups scenes basic rule visual rule classic smartapp settings button upsert app",
                hub_delete_native_app: "remove delete destroy native rule machine room lighting button controller basic rule notifier app",
                hub_set_app_disabled: "disable enable pause stop park red-x toggle installed app room lighting notifier groups scenes without deleting reversible",
                hub_clone_native_app: "copy duplicate clone existing rule app appCloner template surgical edit",
                hub_export_native_app: "export serialize download rule app json appCloner backup transfer canonical shape",
                hub_import_native_app: "import restore upload create rule app from json appCloner backup transfer round trip",
                hub_get_rule_health: "broken validate inspect rule health diagnostic broken trigger broken action multiple flag corruption visual rules builder button controller basic rule classic app validationErrors"
            ]
        ],
        hub_manage_mcp: [
            description: "Developer Mode self-administration: tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration, scope, and operational state without manual UI intervention. Requires `enableDeveloperMode` toggle in the MCP rule app settings (default OFF). Each write is logged at WARN level for audit. First gateway under the Developer Mode pattern — additional self-admin tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same toggle.",
            tools: ["hub_update_mcp_settings"],
            summaries: [
                hub_update_mcp_settings: "Update one or more of the MCP rule app's own settings (toggles, log level, tuning params). Args: settings (map of key→value), confirm=true. Allowlist-gated."
            ],
            searchHints: [
                hub_update_mcp_settings: "self-admin developer mode toggle setting log level tuning loopGuard maxCapturedStates enableRead enableCustomRuleEngine useGateways publishOutputSchemas outputSchema output schema structured content claude desktop gateway mode consolidate flat tools ci automation enableMandatoryBPS best practice acknowledgment gate"
            ]
        ],
        hub_read_devices: [
            description: "Read-only device inspection: list devices with current states, get one device's full detail, read or block-poll a single attribute, and read device/location event history. All operations are read-only; device commands and updates live in hub_manage_devices.",
            tools: ["hub_list_devices", "hub_get_device", "hub_get_device_attribute", "hub_list_device_events"],
            summaries: [
                hub_list_devices: "List devices with current states. Args: detailed?, filter (enabled/disabled/stale:N/virtual), labelFilter?, capabilityFilter?, format (summary/detailed/ids), fields?, limit?, cursor?",
                hub_get_device: "Get one device's full detail (capabilities, attributes, commands). Args: deviceId",
                hub_get_device_attribute: "Read one attribute's value, or block-poll one OR several devices (deviceIds + mode any/all) until it reaches expectedValue/expectedValues. Args: deviceId | deviceIds (max 20), mode? (any/all), attribute, expectedValue?, expectedValues?, timeoutMs?, pollIntervalMs?, comparator?, stableForMs?",
                hub_list_device_events: "Recent device events, a time-windowed history (hoursBack, max 168), an absolute bookmark (since -- events after an exact timestamp; round-trip a returned date), per-app events (appId), or location events (mode/HSM/hub-variable; omit deviceId/appId). Args: deviceId?, appId?, hoursBack?, since?, attribute?, limit?"
            ],
            searchHints: [
                hub_list_devices: "show all devices switches lights sensors locks state inventory enumerate",
                hub_get_device: "device detail capabilities attributes commands info inspect one",
                hub_get_device_attribute: "read attribute value poll wait until threshold sensor verify state changed inclusion compare numeric range debounce stable multiple devices deviceIds any all converge across",
                hub_list_device_events: "device history events timeline recent location mode hsm variable activity app rule automation emitted since bookmark timestamp after new events change watch"
            ]
        ],
        hub_read_rooms: [
            description: "Read-only room inspection: list rooms and view a room's assigned devices. All operations are read-only; room create/delete/rename live in hub_manage_rooms.",
            tools: ["hub_list_rooms", "hub_get_room"],
            summaries: [
                hub_list_rooms: "List all rooms with IDs, names, and device counts",
                hub_get_room: "Get room details with assigned devices. Args: room (name or ID)"
            ],
            searchHints: [
                hub_list_rooms: "show all locations areas groups rooms",
                hub_get_room: "view location area group room devices"
            ]
        ],
        hub_read_files: [
            description: "Read-only hub File Manager access: list files and read file content. All operations are read-only; write/delete live in hub_manage_files.",
            tools: ["hub_list_files", "hub_read_file"],
            summaries: [
                hub_list_files: "List files in File Manager (names, sizes, URLs)",
                hub_read_file: "Read file content. Args: fileName, offset, length"
            ],
            searchHints: [
                hub_list_files: "show uploaded stored csv json text data files",
                hub_read_file: "view open contents download stored data file"
            ]
        ],
        hub_read_variables: [
            description: "Read-only hub-variable inspection: list all variables (with type/connector linkage), get one variable's value + metadata, and watch the recent change timeline. All operations are read-only; variable create/set/delete and connectors live in hub_manage_variables.",
            tools: ["hub_list_variables", "hub_get_variable", "hub_list_variable_changes"],
            summaries: [
                hub_list_variables: "List all hub variables (with type/connector linkage) and rule-engine variables.",
                hub_get_variable: "Get a variable's value + metadata (type, deviceId, attribute). Args: name",
                hub_list_variable_changes: "Recent hub-variable changes since the MCP app last started. Args: name?, sinceMs?, limit?"
            ],
            searchHints: [
                hub_list_variables: "show all global state connector variables",
                hub_get_variable: "read fetch lookup global state variable",
                hub_list_variable_changes: "watch observe changes events recent variable timeline"
            ]
        ],
        hub_manage_devices: [
            description: "Control and inspect devices: send commands, update a device, and swap/replace a device across all referencing apps, plus read-only inspection (list/get/attribute/events). Device reads are also in hub_read_devices.",
            tools: ["hub_call_device_command", "hub_call_device_swap", "hub_call_device_replace", "hub_update_device", "hub_list_devices", "hub_get_device", "hub_get_device_attribute", "hub_list_device_events"],
            summaries: [
                hub_call_device_command: "Send a command to a device (verify state after). Args: deviceId, command, parameters?, waitFor?",
                hub_call_device_swap: "Replace a device across ALL apps/rules that reference it (built-in Swap Device tool). Args: from_device_id, to_device_id, confirm",
                hub_call_device_replace: "Replace a dead device's hardware while KEEPING its id + all app/rule references (re-points to new_device_id; list_options=true reads compatible candidates). Args: old_device_id, new_device_id?, list_options?, confirm",
                hub_update_device: "Update a device's properties: label, name, room, deviceNetworkId, enabled (enable/disable), dataValues, preferences. Args: deviceId, label?, name?, room?, deviceNetworkId?, enabled?, dataValues?, preferences?",
                hub_list_devices: "List devices with current states. Args: detailed?, filter, labelFilter?, capabilityFilter?, format, fields?, limit?, cursor?",
                hub_get_device: "Get one device's full detail (capabilities, attributes, commands). Args: deviceId",
                hub_get_device_attribute: "Read one attribute's value, or block-poll one OR several devices (deviceIds + mode any/all) until it reaches expectedValue/expectedValues. Args: deviceId | deviceIds (max 20), mode? (any/all), attribute, expectedValue?, expectedValues?, timeoutMs?, pollIntervalMs?, comparator?, stableForMs?",
                hub_list_device_events: "Recent device events, a time-windowed history, an absolute bookmark (since), per-app events (appId), or location events. Args: deviceId?, appId?, hoursBack?, since?, attribute?, limit?"
            ],
            searchHints: [
                hub_call_device_command: "send command control turn on off set level dim lock unlock device run",
                hub_call_device_swap: "swap replace device migrate references substitute rewire apps rules everywhere retire failing hardware",
                hub_call_device_replace: "replace device hardware failed dead broken re-point preserve keep id references rules dashboard compatible replacement candidates getReplacementOptions",
                hub_update_device: "rename relabel move room device edit",
                hub_list_devices: "show all devices switches lights sensors locks state inventory",
                hub_get_device: "device detail capabilities attributes commands info inspect one",
                hub_get_device_attribute: "read attribute value poll wait until threshold sensor verify state changed compare numeric range debounce stable multiple devices deviceIds any all converge across",
                hub_list_device_events: "device history events timeline recent location mode hsm variable activity app rule automation emitted since bookmark timestamp after new events change watch"
            ]
        ],
        hub_manage_rule_machine: [
            description: "Dedicated rule-authoring gateway. Visual Rules Builder is the primary engine for new automations (hub_set_visual_rule / hub_get_visual_rule / hub_delete_visual_rule) — one clean JSON write with if/then/else gating. Use hub_set_rule to create/edit a full RM rule (triggers, actions, conditions, required expressions, IF/THEN/ELSE, local variables, walkStep) when the automation needs nested logic, loops, variables, or arbitrary device commands; delete RM rules with hub_delete_native_app; plus RMUtils runtime control (list/run, pause/resume, private boolean, health). This is the path for 'create a rule' / 'make a Hubitat automation'. For non-RM classic apps (Room Lighting, Button Controllers, Notifier, Groups+Scenes) use hub_manage_native_rules_and_apps. Read-only views are in hub_read_rules.",
            tools: ["hub_set_rule", "hub_list_rules", "hub_call_rule", "hub_set_rule_paused", "hub_set_rule_private_boolean", "hub_get_rule_health", "hub_list_rule_local_variables", "hub_delete_native_app", "hub_get_visual_rule", "hub_set_visual_rule", "hub_delete_visual_rule"],
            summaries: [
                hub_set_rule: "Create or edit a Rule Machine rule (RM 5.1) — the full authoring surface. Omit appId to create (name; optionally bundle addTriggers/addActions); provide appId to edit via addTrigger / addAction / addRequiredExpression / replaceRequiredExpression / addTriggers / addActions / replaceActions / removeAction / clearActions / moveAction / removeTrigger / modifyTrigger / addLocalVariable / removeLocalVariable / patches / walkStep, or raw settings/button. Auto-backs-up first. Args: appId (omit=create), name, <shortcut>|settings|button, confirm.",
                hub_list_rules: "List all Rule Machine rules (RM 4.x + 5.x) with IDs and labels (RMUtils — RM only)",
                hub_call_rule: "Trigger an RM rule lifecycle verb. Args: ruleId, action (rule/actions/stop/start, default rule)",
                hub_set_rule_paused: "Pause or resume an RM rule. Args: ruleId, value (true=pause, false=resume)",
                hub_set_rule_private_boolean: "Set an RM rule's private boolean. Args: ruleId, value (bool)",
                hub_get_rule_health: "Inspect a rule (Rule Machine OR Visual Rules Builder) for broken state — compiled `broken` boolean / graph validationErrors, BROKEN markers, configPage errors, multiple-flag corruption. Args: appId, source",
                hub_list_rule_local_variables: "List a Rule Machine rule's local variables (name/type/value) from state.allLocalVars. Distinct from hub_list_variables (hub globals). Args: appId",
                hub_delete_native_app: "Delete any classic native app incl. RM rules (soft by default, force=true for hard). Auto-backs-up first. Args: appId, force (opt), confirm.",
                hub_get_visual_rule: "List Visual Rules Builder rules (omit appId) or read one rule's full JSON definition + format. Args: appId?",
                hub_set_visual_rule: "Create or update a Visual Rules Builder rule — VRB is the primary rule engine; one JSON write with if/then/else gating. Use hub_set_rule only for complex automations (nested logic/loops/variables). Args: appId (omit=create), name, definition, paused (opt), confirm.",
                hub_delete_visual_rule: "Delete a Visual Rules Builder rule (type-gated; returns the pre-delete definition for recovery). Args: appId, confirm."
            ],
            searchHints: [
                hub_set_rule: "create edit modify make rule machine rule trigger action condition required expression walkStep RM authoring native automation hubitat rule upsert",
                hub_list_rules: "rule machine rules native builtin automation list enumerate RM",
                hub_call_rule: "trigger fire execute run native rule machine rule stop start",
                hub_set_rule_paused: "pause resume disable enable stop unpause rule machine rule",
                hub_set_rule_private_boolean: "private boolean flag rule machine rule condition",
                hub_get_rule_health: "broken validate inspect rule health diagnostic broken trigger multiple flag corruption visual rules builder button controller basic rule classic app validationErrors",
                hub_list_rule_local_variables: "rule local variables list allLocalVars per-rule variable name type value rule machine RM setLocalVariable",
                hub_delete_native_app: "remove delete destroy rule machine rule native app classic smartapp",
                hub_get_visual_rule: "visual rules builder VRB read list show inspect automation json definition when then else nodes graph",
                hub_set_visual_rule: "visual rules builder VRB create edit update make automation rule motion light contact alert schedule json primary engine if then else",
                hub_delete_visual_rule: "visual rules builder VRB remove delete destroy automation rule"
            ]
        ]
    ]
}

// ==================== MCP TOOL ANNOTATIONS ====================
// MCP spec `annotations.readOnlyHint` / `destructiveHint` drive client-side
// grouping. Claude.ai's connector UI splits a server's catalog into Read /
// Write blocks from readOnlyHint; entries missing it land in a generic
// "Other tools" bucket.
//
// Classification model (kept simple and conservative):
//   * read-only = does not modify hub or device state. Anything else is
//     write+destructive. There is no non-destructive-write subset --
//     this matches the MCP spec default (destructiveHint defaults to true
//     when readOnlyHint=false) and gets every write the more cautious
//     permission prompt in clients that surface destructiveHint.
//   * All four hint keys ship explicitly (AGENTS.md: all four hints, every
//     tool) -- readOnlyHint + idempotentHint + openWorldHint always,
//     destructiveHint on every write -- so clients do not need to rely on
//     spec defaults. idempotentHint = read-only OR classified retry-safe in
//     getIdempotentWriteToolNames(); openWorldHint = reaches the open
//     internet per getOpenWorldToolNames() (the hub itself is closed-world).
//   * Every classification set is POSITIVE. For read/write and idempotency an
//     unlisted tool falls to the cautious side (write+destructive,
//     non-idempotent). For openWorldHint the unlisted default is closed-world
//     -- an ACCURACY statement (the hub and its devices ARE the system), not
//     caution: the MCP spec default for an OMITTED openWorldHint is true,
//     which is why the key is always emitted explicitly. The snapshot specs
//     force every classification through code review.

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
    // Developer-Mode-only tools: catalog-hidden ENTIRELY when Developer Mode is off
    // (stricter than the runtime-refusal the older dev tools use), so a low-context
    // agent can't even see them unless the toggle is explicitly on. getAllToolDefinitions()
    // still lists them, so dispatch + classification + the canonical tool count are
    // unaffected -- only the live tools/list + search corpus drop them.
    if (!settings.enableDeveloperMode) {
        hide.addAll(getDeveloperModeOnlyToolNames())
    }
    // #114 advanced per-tool / per-gateway overrides (deny-only).
    hide.addAll(getEffectiveDisabledTools())
    return hide
}

// Tools that vanish from the catalog (tools/list + search corpus) whenever Developer
// Mode is off -- not merely runtime-refused. hub_update_package is the first: a self-
// deploy tool that full-repairs the package (apps + library bundle) at a git ref, only
// meaningful (and only safe to expose) during dev work with the toggle on. Returned as
// String names; getHiddenToolNames folds them into `hide` when settings.enableDeveloperMode
// is falsy.
def getDeveloperModeOnlyToolNames() {
    return ([
    ]
        + _developerModeOnlyToolNames_partSelfAdmin()
    ) as Set
}

def getReadOnlyToolNames() {
    return ([
    ]
        + _readOnlyToolNames_partNativeRM()
        + _readOnlyToolNames_partRooms()
        + _readOnlyToolNames_partBundles()
        + _readOnlyToolNames_partVisualRules()
        + _readOnlyToolNames_partFiles()
        + _readOnlyToolNames_partItemBackups()
        + _readOnlyToolNames_partDebugLogging()
        + _readOnlyToolNames_partDiagnostics()
        + _readOnlyToolNames_partSystem()
        + _readOnlyToolNames_partDevices()
        + _readOnlyToolNames_partVariables()
        + _readOnlyToolNames_partCustomRules()
        + _readOnlyToolNames_partCodeManagement()
        + _readOnlyToolNames_partHpm()
        + _readOnlyToolNames_partDiscovery()
    ) as Set
}

// Write tools that are SAFE TO RETRY with identical args (MCP `idempotentHint`):
// a repeat call converges to the same hub state with no additional side effects.
// Read-only tools are implicitly idempotent and are NOT listed here. POSITIVE
// set: an unlisted write defaults to non-idempotent (the cautious default).
// Classification rules applied:
//   * set/update-style writes (assign a value, PATCH fields, replace source)
//     are idempotent -- same args, same end state. Code saves bump the hub's
//     internal version counter, but the retry-safety signal is what matters:
//     a client that lost the response (the #237 recompile drop) SHOULD retry
//     these, and the code/content lands identical.
//   * delete-style writes are idempotent (second call finds nothing to do).
//     EXCEPTION: hub_delete_native_app -- a retry after success throws a
//     misleading pre-delete-snapshot error instead of already-deleted, and the
//     soft-delete-refused path mints a fresh snapshot per repeat call.
//   * create/clone/import-style writes are NOT (each call makes another one).
//     EXCEPTION: hub_create_connector IS -- a connector is keyed 1:1 to its
//     variable and the repeat call short-circuits to alreadyExists success.
//   * exports are idempotent only when the artifact is a pure function of the
//     source: hub_export_custom_rule stamps a fresh exportedAt per call, so it
//     is NOT; hub_export_native_app / hub_export_bundle are timestamp-free.
//   * invoke-style writes (device commands, rule runs, GC, repair, reboot)
//     are NOT -- a retry re-fires the action.
//   * hub_set_rule / hub_set_native_app are upserts whose no-appId mode
//     CREATES -- classified non-idempotent for that mode.
def getIdempotentWriteToolNames() {
    return ([
    ]
        + _idempotentWriteToolNames_partNativeRM()
        + _idempotentWriteToolNames_partRooms()
        + _idempotentWriteToolNames_partBundles()
        + _idempotentWriteToolNames_partVisualRules()
        + _idempotentWriteToolNames_partFiles()
        + _idempotentWriteToolNames_partItemBackups()
        + _idempotentWriteToolNames_partDebugLogging()
        + _idempotentWriteToolNames_partDiagnostics()
        + _idempotentWriteToolNames_partSystem()
        + _idempotentWriteToolNames_partDevices()
        + _idempotentWriteToolNames_partVariables()
        + _idempotentWriteToolNames_partCustomRules()
        + _idempotentWriteToolNames_partCodeManagement()
        + _idempotentWriteToolNames_partSelfAdmin()
        + _idempotentWriteToolNames_partAppCloner()
    ) as Set
}

// The COMPLETE idempotent surface consumed by the annotation helpers: every
// read-only tool plus the retry-safe writes, hoisted once per catalog build
// alongside the other classification sets. Optional telemetry side effects of
// read tools (e.g. hub_get_metrics' recordSnapshot CSV trend row) are by
// maintainer decision NOT writes and do not break the read classification.
def getIdempotentToolNames() {
    return getReadOnlyToolNames() + getIdempotentWriteToolNames()
}

// Tools that reach BEYOND the hub to the open internet (MCP `openWorldHint`):
// GitHub raw fetches, HPM-style bundle downloads, and the importUrl source
// modes where the HUB fetches an arbitrary URL. Everything else is
// closed-world -- the hub, its devices, and its radios ARE the system, and
// hub-local HTTP endpoints (/hub2/*, File Manager) do not leave it.
def getOpenWorldToolNames() {
    return ([
    ]
        + _openWorldToolNames_partBundles()
        + _openWorldToolNames_partDiagnostics()
        + _openWorldToolNames_partSystem()
        + _openWorldToolNames_partCodeManagement()
        + _openWorldToolNames_partSelfAdmin()
        + _openWorldToolNames_partItemBackups()
    ) as Set
}

// Human-facing display metadata for every leaf tool AND every gateway:
// `title` is the friendly name (MCP `annotations.title` -- what claude.ai's
// tool list renders instead of the bare name; also surfaced in the gateway
// catalog disclosure and tokenized into the hub_search_tools BM25 corpus, so
// editing a title changes search ranking), `summary` is a one-sentence
// plain-English description for the Advanced per-tool overrides menu.
// Summaries are deliberately NOT the LLM-facing tool descriptions -- those
// stay in the tool definitions; these are for humans scanning a settings UI.
// Completeness (every tool + gateway covered, no stale entries) is spec-guarded.
def getToolDisplayMeta() {
    // Every extracted library contributes its own tools' entries via
    // _toolDisplayMeta_part<Name>() (issue #209: per-tool metadata lives with the tool);
    // this file keeps only the gateway entries (gateway membership is cross-domain
    // and lives in getGatewayConfig).
    def meta = [:]
    [_toolDisplayMeta_partNativeRM(),
     _toolDisplayMeta_partRooms(),
     _toolDisplayMeta_partBundles(),
     _toolDisplayMeta_partVisualRules(),
     _toolDisplayMeta_partFiles(),
     _toolDisplayMeta_partItemBackups(),
     _toolDisplayMeta_partDebugLogging(),
     _toolDisplayMeta_partDiagnostics(),
     _toolDisplayMeta_partSystem(),
     _toolDisplayMeta_partDevices(),
     _toolDisplayMeta_partVirtualDevices(),
     _toolDisplayMeta_partVariables(),
     _toolDisplayMeta_partCustomRules(),
     _toolDisplayMeta_partCodeManagement(),
     _toolDisplayMeta_partHpm(),
     _toolDisplayMeta_partSelfAdmin(),
     _toolDisplayMeta_partAppCloner(),
     _toolDisplayMeta_partDiscovery()].each { meta.putAll(it) }
    meta.putAll([
        // Gateways
        hub_read_apps_code: [title: "Read Apps and Code", summary: "Read-only: apps, drivers, libraries, source code, backups, and HPM packages."],
        hub_read_devices: [title: "Read Devices", summary: "Read-only device queries: list, details, attributes, events."],
        hub_read_diagnostics: [title: "Read Diagnostics", summary: "Read-only diagnostics: logs, performance, memory, radios, device health."],
        hub_read_files: [title: "Read Files", summary: "Read-only File Manager access: list and read files."],
        hub_read_rooms: [title: "Read Rooms", summary: "Read-only room queries: list rooms and room details."],
        hub_read_rules: [title: "Read Rules", summary: "Read-only rule introspection: custom rules, Rule Machine rules, Visual Rules, rule health."],
        hub_read_variables: [title: "Read Variables", summary: "Read-only hub-variable queries: list, get, recent changes."],
        hub_manage_custom_rules: [title: "Manage Custom Rules", summary: "Create, update, delete, test, export, import, and clone custom-engine rules."],
        hub_manage_devices: [title: "Manage Devices", summary: "Control devices and update device properties, plus device queries."],
        hub_manage_variables: [title: "Manage Variables", summary: "Create, set, and delete hub variables and their connectors."],
        hub_manage_rooms: [title: "Manage Rooms", summary: "Create, rename, and delete rooms."],
        hub_manage_destructive_ops: [title: "Manage Destructive Ops", summary: "Reboot or shut down the hub, or permanently delete devices."],
        hub_manage_backup: [title: "Manage Backups", summary: "List, restore, and delete hub-database backups, and restore code backups (create + schedule is the core hub_create_backup)."],
        hub_manage_code: [title: "Manage Code", summary: "Install, update, and delete apps, drivers, libraries, and code bundles."],
        hub_manage_logs: [title: "Manage Logs", summary: "Read hub logs and performance stats; clear MCP debug logs and set log level."],
        hub_manage_diagnostics: [title: "Manage Diagnostics", summary: "Diagnostics plus maintenance actions: GC and state snapshots."],
        hub_manage_radio: [title: "Manage Radio", summary: "Configure and operate the Z-Wave, Zigbee, and Matter radios: repair, inclusion, exclusion, channels."],
        hub_manage_files: [title: "Manage Files", summary: "List, read, write, and delete File Manager files."],
        hub_manage_rule_machine: [title: "Manage Rule Machine", summary: "Author, trigger, pause, inspect, and delete Visual Rules Builder and Rule Machine rules."],
        hub_manage_native_rules_and_apps: [title: "Manage Native Rules and Apps", summary: "Runtime control of Rule Machine rules plus create, edit, clone, export, import, and delete classic native apps."],
        hub_manage_mcp: [title: "Manage MCP Server", summary: "Self-administer the MCP app's own settings (Developer Mode)."]
    ])
    return meta
}

// Returns the MCP `annotations` map for a leaf tool name. readOnlyHint,
// idempotentHint, and openWorldHint are always emitted explicitly,
// destructiveHint on every write, so the wire payload is unambiguous
// regardless of which spec-default a given client honours (AGENTS.md: all
// four hints, every tool). The classification params are REQUIRED (pass null
// displayMeta only to deliberately skip the title, e.g. in unit tests) so a
// new wire surface cannot silently ship incomplete annotations by forgetting
// an argument; the friendly name rides along as `annotations.title` (the
// field claude.ai and other MCP clients render in place of the bare name).
def annotationsForLeaf(String toolName, Set readOnlyNames, Map displayMeta, Set idempotentNames, Set openWorldNames) {
    def isReadOnly = readOnlyNames.contains(toolName)
    def ann = [:]
    def title = displayMeta?.get(toolName)?.title
    if (title) {
        ann.title = title as String
    }
    ann.readOnlyHint = isReadOnly
    if (!isReadOnly) {
        // destructiveHint is meaningful only when readOnlyHint=false (per spec).
        // Every write is treated as destructive -- matches the spec default and
        // gets clients the cautious permission prompt.
        ann.destructiveHint = true
    }
    // idempotentNames is the COMPLETE idempotent surface (getIdempotentToolNames):
    // reads minus the documented carve-outs, plus the retry-safe writes.
    ann.idempotentHint = idempotentNames.contains(toolName)
    ann.openWorldHint = openWorldNames.contains(toolName)
    return ann
}

// Aggregates annotations for a gateway entry from its currently-visible
// sub-tools. Read-only iff every visible sub-tool is read-only; otherwise
// write+destructive. Idempotent iff EVERY visible sub-tool is idempotent;
// open-world if ANY visible sub-tool reaches the open internet (the cautious
// roll-up direction for each hint). Callers pass `visibleSubTools` so
// feature-toggle hiding (a Read/Write master OFF, custom engine readonly, or
// an Advanced override) propagates into the gateway label.
def annotationsForGateway(List visibleSubTools, Set readOnlyNames, Set idempotentNames, Set openWorldNames) {
    if (!visibleSubTools) {
        throw new IllegalArgumentException(
            "annotationsForGateway requires at least one visible sub-tool"
        )
    }
    def anyWrite = visibleSubTools.any { !readOnlyNames.contains(it) }
    def ann = [readOnlyHint: !anyWrite]
    if (anyWrite) {
        ann.destructiveHint = true
    }
    ann.idempotentHint = visibleSubTools.every { idempotentNames.contains(it) }
    ann.openWorldHint = visibleSubTools.any { openWorldNames.contains(it) }
    return ann
}

def handleGateway(gatewayName, toolName, toolArgs) {
    def gwConfig = getGatewayConfig()
    def config = gwConfig[gatewayName]
    if (!config) {
        throw new IllegalArgumentException("Unknown gateway: ${gatewayName}")
    }

    if (!toolName) {
        // Catalog mode: return full schemas for the VISIBLE tools in this gateway.
        // Filter config.tools through getHiddenToolNames() -- the same single source
        // getToolDefinitions() and toolSearchTools() use -- so a sub-tool hidden by a
        // Read/Write master or by an Advanced #114 override never leaks (with its full
        // schema) on this surface either. The dispatch path (toolName set) is already
        // gated centrally in executeTool on re-entry; this closes the catalog surface.
        // Strip [[FLAT_TRIM]] marker tokens but KEEP the content -- gateway catalog
        // mode is the disclosure surface where full descriptions belong (size cap
        // does not apply per-tool here, only the per-response cap).
        def hidden = getHiddenToolNames()
        def visibleSubTools = config.tools.findAll { !hidden.contains(it) }
        def defMap = applyDescriptionTransform(getAllToolDefinitions(), false)
            .collectEntries { [(it.name): it] }
        def displayMeta = getToolDisplayMeta()

        return [
            gateway: gatewayName,
            mode: "catalog",
            message: "Call again with tool='<name>' and args={...} to execute a tool.",
            tools: visibleSubTools.collect { name ->
                def d = defMap[name]
                def entry = [name: name, description: d?.description, inputSchema: d?.inputSchema]
                def title = displayMeta[name]?.title
                if (title) entry.title = title as String
                // Forward outputSchema only when the advanced publishOutputSchemas
                // setting is on (issue #290) -- OFF by default so strict clients (e.g.
                // Claude Desktop) that reject an outputSchema returned without
                // structuredContent work. The flat tools/list path never emits it (size).
                if (settings.publishOutputSchemas == true && d?.outputSchema != null) entry.outputSchema = d.outputSchema
                entry
            }
        ]
    }

    if (!config.tools.contains(toolName)) {
        throw new IllegalArgumentException("Unknown tool '${toolName}' in ${gatewayName}. Available: ${config.tools.join(', ')}")
    }

    // Defensive: unreachable with current configs — gateway names and tool
    // names are disjoint namespaces, so the unknown-tool check above always
    // fires first if toolName matches a registered gateway. Kept as a guard
    // in case a future gateway config ever lists another gateway's name in
    // its tools array.
    if (gwConfig.containsKey(toolName)) {
        throw new IllegalArgumentException("Cannot call a gateway from within a gateway")
    }

    // Defensive: some MCP clients (e.g. Sonnet subagents) serialize inner `args`
    // as a JSON-encoded string instead of a Map. Parse it transparently so the
    // gateway dispatch is not brittle to that serialization quirk.
    // Non-string args (Map, null) fall through the `instanceof String` check unchanged.
    if (toolArgs instanceof String) {
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(toolArgs as String)
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Gateway arg 'args' was a String but not valid JSON. " +
                "Expected either a JSON object or a JSON-encoded string of an object. " +
                "Parse error: ${e.message ?: e.toString()}"
            )
        }
        if (!(parsed instanceof Map)) {
            def parsedType = (parsed instanceof List) ? "Array" : (parsed == null ? "null" : "non-object")
            throw new IllegalArgumentException(
                "Gateway arg 'args' was a String that parsed to a JSON ${parsedType}, not a JSON object. " +
                "Expected either a JSON object or a JSON-encoded string of an object."
            )
        }
        toolArgs = parsed as Map
    }

    // Option D: Pre-validate required parameters and return a helpful error.
    def safeArgs = toolArgs ?: [:]
    // Read this tool's required-param list from the memoized map. Computing the
    // memo key walks the catalog once per gateway call; the memo saves the per-tool
    // map re-derivation, not the catalog walk. A missing key means the tool has no
    // required params. The full catalog (with the [[FLAT_TRIM]]-stripped param
    // descriptions for the hint) is rebuilt lazily only inside the if (missing)
    // branch below, which fires rarely.
    def required = requiredParamsByTool()[toolName]
    // Gate-bypassing meta-calls return pure static content with NO hub mutation and
    // short-circuit at the very top of their handler (before any gate / appId check),
    // so they must also bypass this required-param pre-validation -- otherwise the
    // gateway rejects them for missing appId/confirm before the handler ever runs.
    // hub_set_rule(guide:true) returns the capability reference inline;
    // addTrigger/addAction {discover:true} return the live machine-readable schema.
    // Schema-only calls (legacy guide:true / addTrigger|addAction discover, OR the
    // self-gateway guide/discover op / args-omitted probe) return content with no
    // mutation and short-circuit at the top of toolSetRule, so they bypass the
    // required-param pre-check (else the gateway rejects them for missing confirm).
    def isGatedMetaCall = toolName == "hub_set_rule" && _isSetRuleSchemaOnlyCall(safeArgs)
    if (required && !isGatedMetaCall) {
        def missing = required.findAll { !safeArgs.containsKey(it) }
        if (missing) {
            // Missing-param hint only (rare path): rebuild the full catalog here so
            // param descriptions are available. Strip [[FLAT_TRIM]] marker tokens --
            // this error is a client-visible surface where markers would leak.
            def defMap = applyDescriptionTransform(getAllToolDefinitions(), false)
                .collectEntries { [(it.name): it] }
            def toolDef = defMap[toolName]
            def props = toolDef?.inputSchema?.properties ?: [:]
            def paramList = props.collect { pName, pDef ->
                def req = required.contains(pName) ? "REQUIRED" : "optional"
                def hint = "  ${pName} (${pDef.type ?: 'any'}, ${req})"
                if (pDef.enum) hint += " — one of: ${pDef.enum.join(', ')}"
                else if (pDef.description) hint += " — ${pDef.description}"
                hint
            }.join("\n")
            def paramWord = (missing.size() == 1) ? "parameter" : "parameters"
            return [
                isError: true,
                error: "Missing required ${paramWord}: ${missing.join(', ')}",
                tool: toolName,
                parameters: paramList
            ]
        }
    }

    return executeTool(toolName, safeArgs)
}

// Flat-mode schema trim (issue #181). Heavy tool descriptions can wrap prose
// in `[[FLAT_TRIM]] ... [[/FLAT_TRIM]]` markers to signal "drop this in flat
// mode, keep it everywhere else". Flat `tools/list` (useGateways=false) emits
// every tool individually and pushes against the hub's 124,000-byte cap; we
// recover headroom by stripping the marker BLOCKS in that one path. All other
// emission surfaces (gateway-catalog mode via `handleGateway`, the hub_search_tools
// corpus, missing-param error hints) strip just the marker TOKENS so the
// content stays available where size isn't the constraint.
//
// The transform operates in place on the fresh Map literals returned by
// `getAllToolDefinitions()`; no caching means each call gets a clean copy.
def stripFlatTrim(String text, boolean dropContent) {
    if (text == null) return null
    // Markers must be balanced and non-nested. The two branches handle the
    // unbalanced case asymmetrically by design:
    //
    //   dropContent=true (flat-mode tools/list): both regexes require BOTH markers
    //   to match. An unmatched open or close survives unchanged so the CI
    //   marker-leakage test on the rendered JSON trips loud -- silently dropping
    //   content for an unmatched marker would be dangerous (data loss).
    //
    //   dropContent=false (gateway catalog, search corpus, missing-param hint):
    //   each regex strips any lone marker token (the `\/?` makes the slash
    //   optional). A stray marker token in these surfaces would itself be the bug
    //   to prevent, so silent removal is the right behaviour -- the wrapped
    //   content survives, only the noise tokens disappear.
    //
    // Own-line markers also require at least one preceding character in the
    // description; today every wrapped block has paragraph prose before it. A
    // first-char marker placement would fall to the inline pass and leak a
    // leading newline -- not enforced in code, would surface as a JSON-leak test
    // failure for the (today unused) first-char placement.
    if (dropContent) {
        return text
            // Own-line block first: eats the leading newline + marker line + content
            // + closing marker line + its trailing newline. (?s) = dotall so .*? spans
            // newlines. Greedy newline consumption keeps paragraph spacing tidy.
            .replaceAll(/(?s)\n\[\[FLAT_TRIM\]\]\n.*?\n\[\[\/FLAT_TRIM\]\]\n/, "")
            // Then catches any remaining (mid-line/inline) marker pair. Drops content
            // and both markers, leaving surrounding text characters intact.
            .replaceAll(/(?s)\[\[FLAT_TRIM\]\].*?\[\[\/FLAT_TRIM\]\]/, "")
    }
    return text
        // Own-line marker first: strip the marker line entirely (token + its trailing
        // newline). (?m) makes ^ match at every line start.
        .replaceAll(/(?m)^\[\[\/?FLAT_TRIM\]\]\n/, "")
        // Then any remaining inline marker token: strip the token only, preserving
        // any surrounding whitespace.
        .replaceAll(/\[\[\/?FLAT_TRIM\]\]/, "")
}

def applyDescriptionTransform(List tools, boolean dropContent) {
    tools.each { tool ->
        if (tool?.description instanceof String) {
            tool.description = stripFlatTrim(tool.description as String, dropContent)
        }
        def props = tool?.inputSchema?.properties
        if (props instanceof Map) {
            (props as Map).each { _propName, propDef ->
                if (propDef instanceof Map && propDef.description instanceof String) {
                    propDef.description = stripFlatTrim(propDef.description as String, dropContent)
                }
            }
        }
    }
    return tools
}

// When a feature toggle is off, its tools are REMOVED from tools/list — not just gated
// at call time. The hide rules live in the biTools / customEngineMode blocks below;
// useGateways=false additionally flattens the catalog (every tool individually) and
// hides hub_search_tools, whose only purpose is finding gateway-hidden tools. Null/unset
// useGateways preserves gateway behavior so existing installs are unaffected on update.
def getToolDefinitions() {
    // Single source of truth for hidden tools: the two universal masters, the
    // legacy custom-engine mode, and the #114 advanced overrides. Drives BOTH
    // flat-mode base-tool filtering AND gateway-mode sub-tool catalog filtering
    // (see visibleSubTools below); toolSearchTools consumes the same set so the
    // catalog and the search corpus cannot drift.
    def hideByName = getHiddenToolNames()

    // Hoist annotation source-of-truth once per call.
    def readOnlyNames = getReadOnlyToolNames()
    def displayMeta = getToolDisplayMeta()
    def idempotentNames = getIdempotentToolNames()
    def openWorldNames = getOpenWorldToolNames()

    // Flat mode: every tool advertised individually under its real name; hub_search_tools
    // is dropped because it only helps navigate gateway-hidden tools.
    if (settings.useGateways == false) {
        def all = getAllToolDefinitions()
        def filtered = all.findAll { it.name != 'hub_search_tools' && !hideByName.contains(it.name) }
        // Loud guard: if hub_search_tools is ever renamed/removed, the prose ("hub_search_tools is
        // hidden in flat mode") becomes a lie and the filter silently no-ops. Fail visibly.
        if (!all.any { it.name == 'hub_search_tools' }) {
            throw new IllegalStateException(
                "Flat-mode filter expected to drop 'hub_search_tools' but it was not found in " +
                "getAllToolDefinitions(). Update getToolDefinitions() if the tool was renamed."
            )
        }
        // Flat-mode tools/list is the size-constrained path -- drop content inside
        // [[FLAT_TRIM]] markers to recover headroom under the hub's 124,000-byte cap.
        def transformed = applyDescriptionTransform(filtered, true)
        return transformed.collect { tool ->
            // Flat mode ALWAYS drops outputSchema to protect the 124,000-byte tools/list
            // cap (this is the all-tools-individually surface) -- independent of the
            // publishOutputSchemas setting (issue #290), which only gates the gateway-mode
            // base tools and the gateway catalog disclosure, where the budget has headroom.
            def base = tool.findAll { it.key != 'outputSchema' }
            // hub_set_rule self-gateway: in flat mode its 25-param fat inputSchema is the
            // biggest single consumer of the tools/list budget, so fold it to a thin
            // {operation,args} selector (the agent probes for an operation's real schema
            // on demand -- see toolSetRule's envelope normalizer). Gateway mode keeps the
            // fat schema (already lazily disclosed by its gateway).
            if (base.name == 'hub_set_rule') {
                def flatTool = _setRuleFlatTool()
                base = base + [description: flatTool.description, inputSchema: flatTool.inputSchema]
            }
            base + [annotations: annotationsForLeaf(tool.name as String, readOnlyNames, displayMeta, idempotentNames, openWorldNames)]
        }
    }

    def gatewayConfig = getGatewayConfig()
    def proxiedNames = gatewayConfig.values().collectMany { it.tools } as Set

    // Base tools: all tools NOT behind a gateway, minus any hidden by toggles.
    def baseTools = getAllToolDefinitions().findAll {
        !proxiedNames.contains(it.name) && !hideByName.contains(it.name)
    }

    // Gateway tools: one tool per gateway, with sub-tool list filtered through
    // the same hideByName the base-tool path uses. Sharing the filter means a
    // toggle that hides a tool hides it on every surface (base + gateway sub-tool
    // + flat-mode entry) with no chance of the two lists drifting. If a gateway
    // ends up with zero remaining sub-tools, drop the gateway entry entirely.
    def gatewayTools = gatewayConfig.collectMany { gwName, config ->
        def visibleSubTools = config.tools.findAll { !hideByName.contains(it) }
        if (!visibleSubTools) return []
        def catalog = visibleSubTools.collect { toolName ->
            "- ${toolName}: ${config.summaries[toolName]}"
        }.join("\n")
        [[
            name: gwName,
            description: "${config.description}\n\nCall with no args to see full parameter schemas. Call with tool='<name>' and args={...} to execute.\n\nAvailable tools:\n${catalog}",
            inputSchema: [
                type: "object",
                properties: [
                    tool: [type: "string", description: "Tool to execute. Omit to see full schemas for all tools in this group.", enum: visibleSubTools],
                    args: [type: "object", description: "Arguments for the tool. Call with just tool name first to see required parameters."]
                ]
            ],
            // Gateway entries get their friendly name from the same display-meta
            // map as the leaves; map-add keeps title first in the wire payload.
            annotations: (displayMeta[gwName]?.title ? [title: displayMeta[gwName].title as String] : [:]) +
                annotationsForGateway(visibleSubTools, readOnlyNames, idempotentNames, openWorldNames)
        ]]
    }

    // Gateway-mode tools/list returns the gateway entries (short prose + sub-tool
    // summaries) plus any base tools. None of those descriptions currently carry
    // [[FLAT_TRIM]] markers, but strip-tokens-only is cheap and keeps us honest
    // if a future author adds one to a base-tool description.
    def transformed = applyDescriptionTransform(baseTools + gatewayTools, false)
    // outputSchema is opt-in (issue #290): the flat path above always strips it; on this
    // gateway-mode base-tool surface (and the gateway catalog) it is emitted only when the
    // advanced publishOutputSchemas setting is on. OFF by default so strict clients (e.g.
    // Claude Desktop) that reject an outputSchema returned without structuredContent work.
    boolean publishSchemas = settings.publishOutputSchemas == true
    return transformed.collect { tool ->
        // Gateway entries already carry annotations (incl. readOnlyHint) from the
        // collectMany above and never carry outputSchema, so return them untouched. Leaf
        // base tools get their annotations from the canonical set here -- the presence of
        // readOnlyHint (not just the annotations map) is the load-bearing signal -- and
        // have their outputSchema stripped unless publishOutputSchemas is on.
        if (tool.annotations?.containsKey('readOnlyHint')) return tool
        def leaf = publishSchemas ? tool : tool.findAll { it.key != 'outputSchema' }
        leaf + [annotations: (leaf.annotations ?: [:]) + annotationsForLeaf(leaf.name as String, readOnlyNames, displayMeta, idempotentNames, openWorldNames)]
    }
}

// Returns ALL tool definitions (used internally by gateway catalog and executeTool dispatch)
def getAllToolDefinitions() {
    // _partRooms / _partBundles / _partVisualRules are contributed by the McpRoomsLib /
    // McpBundlesLib / McpVisualRulesLib #include libraries (issue #209 modularization -- a
    // domain's tool DEFINITIONS live with its impl in the library; only the gateway membership
    // + dispatch case stay in this file).
    return _getAllToolDefinitions_partNativeRM() + _getAllToolDefinitions_partRooms() + _getAllToolDefinitions_partBundles() + _getAllToolDefinitions_partVisualRules() + _getAllToolDefinitions_partDiscovery() + _getAllToolDefinitions_partAppCloner() + _getAllToolDefinitions_partSelfAdmin() + _getAllToolDefinitions_partHpm() + _getAllToolDefinitions_partCodeManagement() + _getAllToolDefinitions_partCustomRules() + _getAllToolDefinitions_partVariables() + _getAllToolDefinitions_partVirtualDevices() + _getAllToolDefinitions_partDevices() + _getAllToolDefinitions_partSystem() + _getAllToolDefinitions_partDiagnostics() + _getAllToolDefinitions_partDebugLogging() + _getAllToolDefinitions_partItemBackups() + _getAllToolDefinitions_partFiles()
}

// Content fingerprint of the catalog's name -> required-params shape, used as
// the memo key in requiredParamsByTool(). A code deploy (HPM update,
// hub_update_app) recompiles the class without firing updated() or bumping
// currentVersion() (PRs ride the same version), so neither updated() invalidation
// nor a version stamp catches a same-version required-array change -- a content
// fingerprint does. Operates on a pre-fetched defs list so the caller can build
// both the key and the memo from one catalog walk; kept as the raw string (no
// sandbox digest API assumed, String equality is cheap).
def requiredParamsCatalogFingerprint(List defs) {
    def sb = new StringBuilder()
    defs.each { tool ->
        def req = tool?.inputSchema?.required
        if (req instanceof List && !req.isEmpty()) {
            sb.append(tool.name as String).append(':').append(req.join(',')).append(';')
        }
    }
    return sb.toString()
}

// Memo of each tool's required-params array (fresh String copies, never the
// mutable raw def list) for the gateway missing-param pre-check. The full catalog
// is walked once per call to compute the fingerprint key; the memo saves the
// per-tool map re-derivation (the String-copy allocation), not the catalog build.
// Keyed on the catalog fingerprint so it self-heals on a same-version code deploy,
// and cleared in updated() alongside the BM25 corpus. Tools with no/empty
// inputSchema.required are omitted, so a miss == "no required params".
def requiredParamsByTool() {
    def defs = getAllToolDefinitions()
    def fp = requiredParamsCatalogFingerprint(defs)
    def cached = atomicState.requiredParamsByTool
    if (cached instanceof Map && atomicState.requiredParamsByToolFingerprint == fp) {
        return cached
    }
    def built = [:]
    defs.each { tool ->
        def req = tool?.inputSchema?.required
        if (req instanceof List && !req.isEmpty()) {
            built[tool.name as String] = req.collect { it as String }
        }
    }
    atomicState.requiredParamsByTool = built
    atomicState.requiredParamsByToolFingerprint = fp
    return built
}

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
        } else if (settings.enableWrite == false && !(toolName == 'hub_set_rule' && _isSetRuleSchemaOnlyCall(args))) {
            // hub_set_rule schema-only calls (guide/discover/args-omitted probe) return
            // reference content and mutate nothing, so they stay reachable when writes
            // are disabled; every actual write still hits this gate.
            throw new IllegalArgumentException("Write tools are disabled. Enable 'Write Tools' in MCP Rule Server app settings to use ${toolName}.")
        }
    }

    // ---- Mandatory best-practice acknowledgment gate (issue #299) ----
    // When enableMandatoryBPS is ON, every write tool requires the caller to first read
    // hub_get_tool_guide(section='best_practice_reference') and pass the acknowledgment key
    // it publishes as the bestPracticeKey argument. The block message names ONLY how to get
    // the key, never the key itself, so the LLM must actually read the guide. ON by default:
    // `!= false` so null/unset/true = active and only an explicit false disables it, mirroring
    // the #113 master-gate convention (the Spock harness + the e2e env setup pin it false so the
    // suites' keyless writes run). Reuses the isGatewayName + read/write partition already
    // computed above -- gateway names short-circuit (sub-tools gate on re-entry). Two tools are
    // exempt so the gate can NEVER lock the caller out: hub_get_tool_guide (read-only; the only
    // way to discover the key) and hub_update_mcp_settings (the toggle-off escape hatch).
    // hub_set_rule schema-only probes stay reachable like the Write master above.
    if (!isGatewayName && settings.enableMandatoryBPS != false
            && !getReadOnlyToolNames().contains(toolName)
            && !(toolName in ['hub_get_tool_guide', 'hub_update_mcp_settings'])
            && !(toolName == 'hub_set_rule' && _isSetRuleSchemaOnlyCall(args ?: [:]))) {
        if (args?.bestPracticeKey?.toString() != hubBpsGuideKey()) {
            throw new IllegalArgumentException("Mandatory best-practice acknowledgment is enabled for write tools. Read hub_get_tool_guide(section='best_practice_reference') to obtain the required acknowledgment key, then pass it as the bestPracticeKey argument on this call. The key appears only in that guide section.")
        }
    }

    // ---- Advanced per-tool/per-gateway overrides (issue #114) ----
    if (isGatewayName) {
        if ((settings.disabled_gateways ?: []).contains(toolName)) {
            throw new IllegalArgumentException("${toolName} is disabled in Advanced settings (Per-tool Overrides). Re-enable it in MCP Rule Server app settings.")
        }
    } else if (getEffectiveDisabledTools().contains(toolName)) {
        throw new IllegalArgumentException("${toolName} is disabled in Advanced settings (Per-tool Overrides). Re-enable it in MCP Rule Server app settings.")
    }

    // Custom Rule Engine gate. The tools also disappear from tools/list
    // (see getToolDefinitions), but a stale client cache could still call
    // them -- fail clearly here. See getCustomEngineMode() for the three modes.
    def customEngineMode = getCustomEngineMode()
    def customReadonlyTools = ["hub_get_custom_rule", "hub_test_custom_rule",
                               "hub_update_custom_rule"] as Set
    // Legacy custom-rule tools are named hub_<verb>_custom_rule (the `custom`
    // qualifier moved into the noun during the issue #105 hub_ rename), so detect
    // them by the _custom_rule suffix rather than a custom_ prefix. Use endsWith,
    // NOT contains: the gateway name `hub_manage_custom_rules` (plural) contains the
    // substring `_custom_rule`, so `contains` mis-fired this read-only gate on the
    // gateway itself -- bricking the entire hub_manage_custom_rules gateway in
    // readonly mode (engine OFF) before handleGateway could dispatch its allowed
    // read sub-tools (get/test/update). All 8 leaf tools end with `_custom_rule`;
    // the gateway ends with `_custom_rules`, so endsWith cleanly excludes it.
    if (toolName?.endsWith("_custom_rule")) {
        if (customEngineMode == "off") {
            throw new IllegalArgumentException("${toolName} is not available. 'Enable Custom Rule Engine' is OFF and the Read master is OFF. Turn on Custom Rule Engine to use the legacy custom-rule tools (hub_*_custom_rule), or use native Hubitat Rule Machine via hub_manage_native_rules_and_apps.")
        }
        if (customEngineMode == "readonly" && !customReadonlyTools.contains(toolName)) {
            throw new IllegalArgumentException("${toolName} is not available in read-only mode. The Custom Rule Engine toggle is OFF. Turn it ON in MCP Rule Server settings to use create/delete/export/import/clone operations. NOTE: the custom MCP rule engine is legacy -- for new rule work prefer hub_manage_native_rules_and_apps.")
        }
    }
    switch (toolName) {
        // Device Tools
        case "hub_list_devices":
            // filter='virtual' routes to the MCP-managed virtual-device listing (a distinct
            // population -- this app's child devices -- with a richer driver-namespace shape).
            if (args.filter == "virtual") return toolListVirtualDevices(args)
            return toolListDevices(args.detailed, args.offset ?: 0, args.limit ?: 0, args.filter, args.labelFilter, args.capabilityFilter, args.format, args.fields, args.cursor, args.scope)
        case "hub_get_device": return toolGetDevice(args.deviceId)
        case "hub_call_device_command": return toolSendCommand(args.deviceId, args.command, args.parameters, args.waitFor)
        case "hub_call_device_swap": return toolCallDeviceSwap(args)
        case "hub_call_device_replace": return toolCallDeviceReplace(args)
        case "hub_list_device_events":
            // Recent-N for one device when no window/filter given; otherwise windowed
            // device history, per-app events (appId), or location-level events when
            // both deviceId and appId are omitted. Reject deviceId+appId loudly --
            // the recent-N route below would otherwise silently drop appId.
            if (args.deviceId != null && args.appId != null)
                throw new IllegalArgumentException("deviceId and appId are mutually exclusive. Pass deviceId for device events, appId for events emitted by an installed app/rule, or neither for location events.")
            // since is an absolute window bookmark -- like hoursBack/attribute it
            // routes to windowed history mode, not the recent-N path.
            if (args.deviceId != null && args.hoursBack == null && args.attribute == null && args.since == null)
                return toolGetDeviceEvents(args.deviceId, args.limit != null ? args.limit : 10)
            return toolGetDeviceHistory(args)
        case "hub_get_device_attribute":
            // Poll mode when ANY poll arg is supplied (expectedValue/expectedValues,
            // timeoutMs/pollIntervalMs/comparator/stableForMs, or the multi-device
            // deviceIds/mode); otherwise a one-shot read. Routing on the timeout/interval
            // too preserves the old contract where a timeout without an expected value is
            // rejected rather than silently read once. The new deviceIds/mode args route on
            // KEY PRESENCE (containsKey), not != null: a present-but-null deviceIds/mode should
            // reach the engine's actionable null-guard IAE rather than silently falling to a
            // single-device one-shot read. (The pre-existing args keep their != null checks --
            // the asymmetry is intentional; only the new args have null-guards worth reaching.)
            if (args.expectedValue != null || args.expectedValues != null || args.timeoutMs != null || args.pollIntervalMs != null || args.comparator != null || args.stableForMs != null || args.containsKey("deviceIds") || args.containsKey("mode")) {
                return toolPollUntilAttribute(args)
            }
            // One-shot read. deviceId is not in the schema's required array (deviceIds is the
            // poll-only alternative), so guard the bare {attribute} call here for an actionable
            // message instead of letting toolGetAttribute hit "Device not found: <blank>". Reject
            // a null OR empty/blank deviceId -- an empty string would otherwise slip through to a
            // "Device not found: " miss.
            if (!(args.deviceId instanceof String) || !args.deviceId.trim()) {
                throw new IllegalArgumentException("deviceId is required (or use deviceIds for multi-device polling)")
            }
            return toolGetAttribute(args.deviceId, args.attribute)

        // Rule Management - now using child apps
        case "hub_get_custom_rule":
            // ruleId omitted = list mode; detailed=true (requires ruleId) = diagnostics; else single rule.
            // Reject detailed-without-ruleId loudly rather than silently dropping detailed and listing.
            if (args.detailed == true && args.ruleId == null)
                throw new IllegalArgumentException("detailed=true requires a ruleId (it returns per-rule diagnostics). Omit detailed to list all rules.")
            if (args.ruleId == null) return toolListRules(args)
            if (args.detailed == true) return toolGetRuleDiagnostics(args)
            return toolGetRule(args.ruleId)
        case "hub_create_custom_rule": return toolCreateRule(args)
        case "hub_update_custom_rule": return toolUpdateRule(args.ruleId, args, customEngineMode)
        case "hub_delete_custom_rule": return toolDeleteRule(args)
        // enable_rule/disable_rule merged into hub_update_custom_rule
        case "hub_test_custom_rule": return toolTestRule(args.ruleId)

        // System Tools
        case "hub_get_info": return toolGetHubInfo(args)
        case "hub_list_modes": return toolGetModes()
        case "hub_manage_mode": return toolManageMode(args)
        case "hub_set_mode_manager": return toolSetModeManager(args)
        case "hub_list_variables": return toolListVariables(args)
        case "hub_get_variable": return toolGetVariable(args.name)
        case "hub_set_variable": return toolSetVariable(args.name, args.value)
        case "hub_create_variable": return toolCreateVariable(args)
        case "hub_delete_variable": return toolDeleteHubVariable(args)
        case "hub_create_connector": return toolCreateConnector(args)
        case "hub_delete_connector": return toolRemoveConnector(args)
        case "hub_list_variable_changes": return toolGetVariableHistory(args)
        case "hub_update_mcp_settings": return toolUpdateMcpSettings(args)
        case "hub_update_package": return toolUpdatePackage(args)
        case "hub_get_hsm_status": return toolGetHsmStatus()
        case "hub_set_hsm": return toolSetHsm(args.mode)
        case "hub_set_system_settings": return toolSetSystemSettings(args)

        // Captured State Management
        case "hub_list_captured_states": return toolListCapturedStates(args)
        case "hub_delete_captured_state": return toolDeleteCapturedState(args)

        // Debug Logging Tools
        case "hub_get_debug_logs":
            return (args.mode == "status") ? toolGetLoggingStatus(args) : toolGetDebugLogs(args)
        case "hub_delete_debug_logs": return toolClearDebugLogs(args)
        case "hub_set_log_level": return toolSetLogLevel(args)
        case "hub_report_issue": return toolGenerateBugReport(args)

        // Rule Export/Import/Clone
        case "hub_export_custom_rule": return toolExportRule(args)
        case "hub_import_custom_rule": return toolImportRule(args)
        case "hub_clone_custom_rule": return toolCloneRule(args)

        // Hub platform/firmware install (the app-version + pending-firmware READS fold into hub_get_info)
        case "hub_update_firmware": return toolUpdateFirmware(args)

        // Read master Tools (hub_get_details + hub_get_health merged into hub_get_info)
        case "hub_list_apps": return (args?.scope == "types") ? toolListHubApps(args) : toolListInstalledApps(args)
        case "hub_list_drivers": return toolListHubDrivers(args)
        case "hub_list_libraries": return toolListLibraries(args)
        case "hub_get_radio_details": return toolGetRadioDetails(args)

        // Monitoring Tools
        case "hub_get_logs": return toolGetHubLogs(args)
        case "hub_get_performance_stats": return toolGetPerformanceStats(args)
        case "hub_get_jobs": return toolGetHubJobs(args)
        case "hub_get_metrics": return toolGetHubPerformance(args)
        case "hub_get_device_health": return toolDeviceHealthCheck(args)
        case "hub_get_memory_history": return toolGetMemoryHistory(args)
        case "hub_call_gc": return toolForceGarbageCollection(args)

        // Write master Tools
        case "hub_create_backup": return toolCreateHubBackup(args)
        case "hub_delete_backup": return toolDeleteHubBackup(args)
        case "hub_reboot": return toolRebootHub(args)
        case "hub_shutdown": return toolShutdownHub(args)

        // Radio management (hub_manage_radio + destructive radio in hub_manage_destructive_ops)
        case "hub_set_zwave": return toolSetZwave(args)
        case "hub_set_zigbee": return toolSetZigbee(args)
        case "hub_call_zwave": return toolCallZwave(args)
        case "hub_call_zigbee": return toolCallZigbee(args)
        case "hub_call_matter": return toolCallMatter(args)
        case "hub_call_destructive_radio": return toolCallDestructiveRadio(args)

        // Device Admin
        case "hub_delete_device": return toolDeleteDevice(args)

        // Virtual Device Management
        case "hub_manage_virtual_device": return toolManageVirtualDevice(args)
        case "hub_update_device": return toolUpdateDevice(args)

        // Room Management
        case "hub_list_rooms": return toolListRooms(args)
        case "hub_get_room": return toolGetRoom(args.room)
        case "hub_create_room": return toolCreateRoom(args)
        case "hub_delete_room": return toolDeleteRoom(args)
        case "hub_update_room": return toolRenameRoom(args)

        // Hub Admin App Configuration Read
        case "hub_get_app_config": return toolGetAppConfig(args)
        case "hub_list_app_pages": return toolListAppPages(args)

        // Hub Admin App/Driver Management
        case "hub_get_source": return toolGetSource(args)
        case "hub_create_app": return toolInstallApp(args)
        case "hub_create_driver": return toolInstallDriver(args)
        case "hub_update_app": return toolUpdateAppCode(args)
        case "hub_update_driver": return toolUpdateDriverCode(args)
        case "hub_delete_item": return toolDeleteItem(args)

        // Hub Admin Library Management
        case "hub_create_library": return toolInstallLibrary(args)
        case "hub_update_library": return toolUpdateLibraryCode(args)
        case "hub_install_bundle": return toolInstallBundle(args)
        case "hub_list_bundles": return toolListBundles(args)
        case "hub_delete_bundle": return toolDeleteBundle(args)
        case "hub_export_bundle": return toolExportBundle(args)

        // Item Backup Tools
        case "hub_list_backups": return toolListItemBackups(args)
        case "hub_get_backup": return toolGetItemBackup(args)
        case "hub_restore_backup": return toolRestoreItemBackup(args)

        // File Manager Tools
        case "hub_list_files": return toolListFiles(args)
        case "hub_read_file": return toolReadFile(args)
        case "hub_write_file": return toolWriteFile(args)
        case "hub_delete_file": return toolDeleteFile(args)

        // Installed Apps Integration
        case "hub_list_device_dependents": return toolGetDeviceInUseBy(args)

        // HPM Package State
        case "hub_list_hpm_packages": return toolListHpmPackagesWithDrift(args)

        // Rule Machine Integration (via RMUtils)
        case "hub_list_rules": return toolListRmRules(args)
        case "hub_call_rule": return toolRunRmRule(args)
        case "hub_set_rule_paused": return toolSetRulePaused(args)
        case "hub_set_rule_private_boolean": return toolSetRmRuleBoolean(args)

        // Native Rule Machine CRUD (hub admin-layer; backups flow through
        // hub_list_backups (hub_read_apps_code) + hub_restore_backup (hub_manage_backup))
        case "hub_set_rule": return toolSetRule(args)
        case "hub_set_native_app": return toolSetNativeApp(args)
        case "hub_set_app_disabled": return toolSetAppDisabled(args)
        case "hub_delete_native_app": return toolDeleteNativeApp(args)
        case "hub_clone_native_app": return toolCloneNativeApp(args)
        case "hub_export_native_app": return toolExportNativeApp(args)
        case "hub_import_native_app": return toolImportNativeApp(args)
        case "hub_get_rule_health": return toolCheckRuleHealth(args)
        case "hub_list_rule_local_variables": return toolListRuleLocalVariables(args)

        // Visual Rules Builder (Vue-JSON apps; impl in McpVisualRulesLib)
        case "hub_get_visual_rule": return toolGetVisualRule(args)
        case "hub_set_visual_rule": return toolSetVisualRule(args)
        case "hub_delete_visual_rule": return toolDeleteVisualRule(args)

        // Tool Guide
        case "hub_get_tool_guide": return toolGetToolGuide(args.section)

        // Tool Search (BM25)
        case "hub_search_tools": return toolSearchTools(args)

        // Category Gateway Proxy Tools
        case "hub_read_apps_code":
        case "hub_read_devices":
        case "hub_read_diagnostics":
        case "hub_read_files":
        case "hub_read_rooms":
        case "hub_read_rules":
        case "hub_read_variables":
        case "hub_manage_backup":
        case "hub_manage_code":
        case "hub_manage_custom_rules":
        case "hub_manage_destructive_ops":
        case "hub_manage_devices":
        case "hub_manage_diagnostics":
        case "hub_manage_files":
        case "hub_manage_logs":
        case "hub_manage_mcp":
        case "hub_manage_native_rules_and_apps":
        case "hub_manage_radio":
        case "hub_manage_rooms":
        case "hub_manage_rule_machine":
        case "hub_manage_variables":
            // Flat-mode guard: gateways are not advertised on tools/list when useGateways=false,
            // so a gateway-name call here is almost certainly a stale/cached client. Returning
            // the gateway catalog would silently contradict the user's intent — fail loud with
            // a hint pointing at the real sub-tools instead.
            if (settings.useGateways == false) {
                // Filter the hint against the live flat catalog so we don't recommend tools
                // that other settings (Read/Write masters or Custom Rule Engine) have also hidden.
                def visibleNames = getToolDefinitions()*.name as Set
                def subTools = (getGatewayConfig()[toolName]?.tools ?: []).findAll { visibleNames.contains(it) }
                def hint = subTools
                    ? "Call the underlying tool directly: ${subTools.join(', ')}. Refresh tools/list to see the flat catalog."
                    : "All sub-tools of this gateway are also disabled by other server toggles (Read/Write masters or Custom Rule Engine). Enable those toggles or refresh tools/list."
                return [
                    isError: true,
                    error: "Gateway tool '${toolName}' is disabled — useGateways is OFF in this server's preferences.",
                    hint: hint
                ]
            }
            return handleGateway(toolName, args.tool, args.args)

        default:
            throw new IllegalArgumentException("Unknown tool: ${toolName}")
    }
}


// ==================== RULE TOOLS (Child App Based) ====================


// toolEnableRule/toolDisableRule/toolToggleRule removed in v0.8.1 (dead code since v0.8.0 merged into hub_update_custom_rule)


// ===== /hub2/hubData diagnostics: pending platform update + hub health alerts =====


/**
 * hub_delete_connector: delete the connector device that backs a hub variable.
 * Reuses the existing hub_delete_device path (the connector is a regular hub
 * device once created) -- Hubitat's UI does the same: open the connector
 * device's page, click Remove Device.
 *
 * The hub variable itself is NOT deleted; only the connector linkage.
 */


// Helper method for child apps to get variable values. Issue #92: switched
// to the modern getGlobalVar API so this sees every hub variable, not just
// connector-exposed ones.
def getVariableValue(name) {
    try {
        def hubVar = getGlobalVar(name)
        if (hubVar != null) return hubVar.value
    } catch (Exception e) {
        // Hub variable lookup failed — fall through to rule_engine namespace.
        // Logged at DEBUG so investigators can tell whether the lookup
        // genuinely missed (no such variable) or errored for some other reason.
        logDebug("getVariableValue: hub lookup for '${name}' threw ${e.class.simpleName}: ${e.message}")
    }
    return state.ruleVariables?.get(name)
}

// Helper method for child apps to set rule-scoped variables
def setRuleVariable(name, value) {
    if (!state.ruleVariables) state.ruleVariables = [:]
    state.ruleVariables[name] = value
}


// ==================== VALIDATION FUNCTIONS ====================

// Valid comparison operators for triggers and conditions
// Accepts both symbolic ("==","!=") and word ("equals","not_equals") forms
def getValidOperators() {
    return ["==", "!=", ">", "<", ">=", "<=", "equals", "not_equals"]
}

// Normalize operator to the word form used by the runtime evaluator
// Accepts both "==" and "equals" (and "!=" / "not_equals")
def normalizeOperator(operator) {
    if (operator == null) return null
    switch (operator) {
        case "==": return "equals"
        case "!=": return "not_equals"
        default: return operator
    }
}

// Normalize all operators in a rule's triggers, conditions, and actions
// Converts symbolic operators ("==", "!=") to word form ("equals", "not_equals")
// so they match the evaluateComparison() switch cases in the child app
// Normalize trigger format - converts common sunrise/sunset trigger variations to canonical form
// Canonical form: {"type": "time", "sunrise": true, "offset": N} or {"type": "time", "sunset": true, "offset": N}
// Accepted variations:
//   {"type": "time", "time": "sunrise", "offset": 30}  -> {"type": "time", "sunrise": true, "offset": 30}
//   {"type": "time", "time": "sunset"}                  -> {"type": "time", "sunset": true}
//   {"type": "sunrise", "offset": 30}                   -> {"type": "time", "sunrise": true, "offset": 30}
//   {"type": "sunset"}                                  -> {"type": "time", "sunset": true}
//   {"type": "sun", "event": "sunrise", "offset": 30}   -> {"type": "time", "sunrise": true, "offset": 30}
//   {"type": "time", "sunEvent": "sunrise", "offsetMinutes": 30} -> {"type": "time", "sunrise": true, "offset": 30}
def normalizeTrigger(trigger) {
    def normalized = new LinkedHashMap(trigger)

    // Handle {"type": "sunrise"} or {"type": "sunset"} - convert type to "time" and set flag
    if (normalized.type in ["sunrise", "sunset"]) {
        def sunType = normalized.type
        normalized.type = "time"
        normalized[sunType] = true
        return normalized
    }

    // Handle {"type": "sun", "event": "sunrise/sunset"}
    if (normalized.type == "sun" && normalized.event in ["sunrise", "sunset"]) {
        normalized.type = "time"
        normalized[normalized.event] = true
        normalized.remove("event")
        return normalized
    }

    // Handle {"type": "time", "time": "sunrise/sunset"} - time field has sun event name instead of HH:mm
    if (normalized.type == "time" && normalized.time in ["sunrise", "sunset"]) {
        def sunType = normalized.time
        normalized.remove("time")
        normalized[sunType] = true
        return normalized
    }

    // Handle {"type": "time", "sunEvent": "sunrise/sunset", "offsetMinutes": N}
    if (normalized.type == "time" && normalized.sunEvent in ["sunrise", "sunset"]) {
        normalized[normalized.sunEvent] = true
        if (normalized.offsetMinutes != null && normalized.offset == null) {
            normalized.offset = normalized.offsetMinutes
        }
        normalized.remove("sunEvent")
        normalized.remove("offsetMinutes")
        return normalized
    }

    return normalized
}

def normalizeRuleOperators(args) {
    args.triggers?.each { trigger ->
        if (trigger.operator) trigger.operator = normalizeOperator(trigger.operator)
    }
    args.conditions?.each { condition ->
        if (condition.operator) condition.operator = normalizeOperator(condition.operator)
    }
    args.actions?.each { action ->
        normalizeActionOperators(action)
    }
}

// Recursively normalize operators in actions (handles nested if_then_else and repeat)
def normalizeActionOperators(action) {
    if (action.type == "if_then_else") {
        if (action.condition?.operator) action.condition.operator = normalizeOperator(action.condition.operator)
        action.thenActions?.each { normalizeActionOperators(it) }
        action.elseActions?.each { normalizeActionOperators(it) }
    } else if (action.type == "repeat") {
        action.actions?.each { normalizeActionOperators(it) }
    }
}

// Valid button actions
def getValidButtonActions() {
    return ["pushed", "held", "doubleTapped", "released"]
}

// Maximum duration in seconds (2 hours)
def getMaxDurationSeconds() {
    return 7200
}

// Validate time format HH:mm
def isValidTimeFormat(timeStr) {
    if (!timeStr) return false
    def pattern = /^([01]?[0-9]|2[0-3]):[0-5][0-9]$/
    return timeStr ==~ pattern
}

// Validate and normalize operator field
def validateOperator(operator, context) {
    if (operator != null && !getValidOperators().contains(operator)) {
        throw new IllegalArgumentException("${context}: Invalid operator '${operator}'. Valid operators: ${getValidOperators().join(', ')}")
    }
}

// Validate duration field
def validateDuration(duration, context) {
    if (duration != null) {
        def durationValue
        try {
            durationValue = duration as Integer
        } catch (Exception e) {
            throw new IllegalArgumentException("${context}: Duration must be a valid number")
        }
        if (durationValue < 0) {
            throw new IllegalArgumentException("${context}: Duration cannot be negative")
        }
        if (durationValue > getMaxDurationSeconds()) {
            throw new IllegalArgumentException("${context}: Duration cannot exceed ${getMaxDurationSeconds()} seconds (2 hours). Provided: ${durationValue} seconds")
        }
    }
}

// Validate button action field
def validateButtonAction(action, context) {
    if (action != null && !getValidButtonActions().contains(action)) {
        throw new IllegalArgumentException("${context}: Invalid button action '${action}'. Valid actions: ${getValidButtonActions().join(', ')}")
    }
}

// Validate time string format (HH:mm)
def validateTimeFormat(timeStr, context) {
    if (timeStr != null && !isValidTimeFormat(timeStr)) {
        throw new IllegalArgumentException("${context}: Invalid time format '${timeStr}'. Expected format: HH:mm (e.g., 08:30, 23:45)")
    }
}

def validateTrigger(trigger) {
    if (!trigger.type) {
        throw new IllegalArgumentException("Trigger type is required")
    }

    switch (trigger.type) {
        case "device_event":
            // Support single device (deviceId) or multi-device (deviceIds array)
            if (!trigger.deviceId && !trigger.deviceIds) throw new IllegalArgumentException("device_event trigger requires deviceId or deviceIds")
            if (!trigger.attribute) throw new IllegalArgumentException("device_event trigger requires attribute")
            if (trigger.deviceId) {
                if (!findDevice(trigger.deviceId)) throw new IllegalArgumentException("Device not found: ${trigger.deviceId}")
            }
            if (trigger.deviceIds) {
                if (!(trigger.deviceIds instanceof List) || trigger.deviceIds.size() == 0) {
                    throw new IllegalArgumentException("device_event trigger deviceIds must be a non-empty list")
                }
                trigger.deviceIds.each { devId ->
                    if (!findDevice(devId)) throw new IllegalArgumentException("Device not found: ${devId}")
                }
                // Validate matchMode if present
                if (trigger.matchMode && !["any", "all"].contains(trigger.matchMode)) {
                    throw new IllegalArgumentException("device_event trigger matchMode must be 'any' or 'all' (got '${trigger.matchMode}')")
                }
            }
            // Validate operator if present
            validateOperator(trigger.operator, "device_event trigger")
            // Validate duration if present (for debouncing)
            validateDuration(trigger.duration, "device_event trigger")
            break
        case "button_event":
            if (!trigger.deviceId) throw new IllegalArgumentException("button_event trigger requires deviceId")
            if (!findDevice(trigger.deviceId)) throw new IllegalArgumentException("Device not found: ${trigger.deviceId}")
            // Validate button action if present
            validateButtonAction(trigger.action, "button_event trigger")
            break
        case "time":
            if (!trigger.time && !trigger.sunrise && !trigger.sunset) {
                throw new IllegalArgumentException("time trigger requires time (HH:mm), sunrise, or sunset. Examples: {\"type\":\"time\",\"time\":\"08:30\"}, {\"type\":\"time\",\"sunrise\":true,\"offset\":30}")
            }
            // Validate time format if time is specified (not sunrise/sunset)
            if (trigger.time) {
                validateTimeFormat(trigger.time, "time trigger")
            }
            // Validate offset for sunrise/sunset triggers
            if ((trigger.sunrise || trigger.sunset) && trigger.offset != null) {
                def offsetValue
                try {
                    offsetValue = trigger.offset as Integer
                } catch (Exception e) {
                    throw new IllegalArgumentException("time trigger: offset must be a number (minutes), got '${trigger.offset}'")
                }
                if (offsetValue < -180 || offsetValue > 180) {
                    throw new IllegalArgumentException("time trigger: offset must be between -180 and 180 minutes, got ${offsetValue}")
                }
            }
            break
        case "periodic":
            if (trigger.interval == null) {
                throw new IllegalArgumentException("periodic trigger requires interval")
            }
            def periodicInterval = trigger.interval as Integer
            def periodicUnit = trigger.unit ?: "minutes"
            if (periodicInterval < 1) {
                throw new IllegalArgumentException("periodic trigger interval must be at least 1")
            }
            switch (periodicUnit) {
                case "minutes":
                    if (periodicInterval > 59) throw new IllegalArgumentException("periodic trigger interval for minutes must be 1-59 (got ${periodicInterval}). Use hours for larger intervals.")
                    break
                case "hours":
                    if (periodicInterval > 23) throw new IllegalArgumentException("periodic trigger interval for hours must be 1-23 (got ${periodicInterval}). Use days for larger intervals.")
                    break
                case "days":
                    if (periodicInterval > 31) throw new IllegalArgumentException("periodic trigger interval for days must be 1-31 (got ${periodicInterval})")
                    break
                default:
                    throw new IllegalArgumentException("periodic trigger unit must be minutes, hours, or days (got ${periodicUnit})")
            }
            break
        case "mode_change":
            break
        case "hsm_change":
            break
        default:
            throw new IllegalArgumentException("Unknown trigger type: ${trigger.type}")
    }
}

def validateCondition(condition) {
    if (!condition.type) {
        throw new IllegalArgumentException("Condition type is required")
    }

    switch (condition.type) {
        case "device_state":
            if (!condition.deviceId) throw new IllegalArgumentException("device_state condition requires deviceId")
            if (!condition.attribute) throw new IllegalArgumentException("device_state condition requires attribute")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            // Validate operator if present
            validateOperator(condition.operator, "device_state condition")
            // Bug fix: require value when an operator is specified
            if (condition.operator && condition.value == null) {
                throw new IllegalArgumentException("device_state condition requires value when operator is specified")
            }
            break
        case "device_was":
            if (!condition.deviceId) throw new IllegalArgumentException("device_was condition requires deviceId")
            if (!condition.attribute) throw new IllegalArgumentException("device_was condition requires attribute")
            if (condition.forSeconds == null) throw new IllegalArgumentException("device_was condition requires forSeconds")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            // Validate operator if present
            validateOperator(condition.operator, "device_was condition")
            // Require value when operator is specified (same as device_state)
            if (condition.operator && condition.value == null) {
                throw new IllegalArgumentException("device_was condition requires value when operator is specified")
            }
            // Validate forSeconds duration (for "state for X seconds" checks)
            validateDuration(condition.forSeconds, "device_was condition")
            break
        case "time_range":
            // Accept both new (start/end) and old (startTime/endTime) field names for compatibility
            def startVal = condition.start ?: condition.startTime
            def endVal = condition.end ?: condition.endTime
            // Sunrise/sunset boundaries are not implemented in the rule engine — reject them
            if (condition.startSunrise || condition.startSunset || condition.endSunrise || condition.endSunset) {
                throw new IllegalArgumentException("time_range condition does not support sunrise/sunset boundaries. Use fixed HH:mm times for start and end.")
            }
            if (!startVal) {
                throw new IllegalArgumentException("time_range condition requires start time")
            }
            if (!endVal) {
                throw new IllegalArgumentException("time_range condition requires end time")
            }
            // Validate time format for start/end if specified (not sunrise/sunset)
            if (startVal) {
                validateTimeFormat(startVal, "time_range condition start")
            }
            if (endVal) {
                validateTimeFormat(endVal, "time_range condition end")
            }
            break
        case "mode":
            if (!condition.mode && !condition.modes) {
                throw new IllegalArgumentException("mode condition requires mode or modes")
            }
            // Validate operator if present (mode supports 'in' and 'not_in')
            if (condition.operator && !["in", "not_in"].contains(condition.operator)) {
                throw new IllegalArgumentException("mode condition: Invalid operator '${condition.operator}'. Valid operators: in, not_in")
            }
            break
        case "variable":
            if (!condition.variableName) throw new IllegalArgumentException("variable condition requires variableName")
            // Validate operator if present
            validateOperator(condition.operator, "variable condition")
            // Bug fix: require value when an operator is specified
            if (condition.operator && condition.value == null) {
                throw new IllegalArgumentException("variable condition requires value when operator is specified")
            }
            break
        case "days_of_week":
            if (!condition.days) throw new IllegalArgumentException("days_of_week condition requires days array")
            // Validate day names
            def validDays = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
            condition.days.each { day ->
                if (!validDays.contains(day)) {
                    throw new IllegalArgumentException("days_of_week condition: Invalid day '${day}'. Valid days: ${validDays.join(', ')}")
                }
            }
            break
        case "sun_position":
            if (!condition.position) throw new IllegalArgumentException("sun_position condition requires position (up/down)")
            def validPositions = ["up", "down"]
            if (!validPositions.contains(condition.position)) {
                throw new IllegalArgumentException("sun_position condition: Invalid position '${condition.position}'. Valid positions: ${validPositions.join(', ')}")
            }
            break
        case "hsm_status":
            if (!condition.status) throw new IllegalArgumentException("hsm_status condition requires status")
            def validHsmStatuses = ["disarmed", "armedAway", "armedHome", "armedNight", "armingAway", "armingHome", "armingNight"]
            if (!validHsmStatuses.contains(condition.status)) {
                throw new IllegalArgumentException("hsm_status condition: Invalid status '${condition.status}'. Valid statuses: ${validHsmStatuses.join(', ')}")
            }
            break
        case "presence":
            if (!condition.deviceId) throw new IllegalArgumentException("presence condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "lock":
            if (!condition.deviceId) throw new IllegalArgumentException("lock condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "thermostat_mode":
            if (!condition.deviceId) throw new IllegalArgumentException("thermostat_mode condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "thermostat_state":
            if (!condition.deviceId) throw new IllegalArgumentException("thermostat_state condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "illuminance":
            if (!condition.deviceId) throw new IllegalArgumentException("illuminance condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            // Validate operator if present (for threshold comparisons)
            validateOperator(condition.operator, "illuminance condition")
            break
        case "power":
            if (!condition.deviceId) throw new IllegalArgumentException("power condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            // Validate operator if present (for threshold comparisons)
            validateOperator(condition.operator, "power condition")
            break
        case "expression":
            throw new IllegalArgumentException("expression condition type is not supported (Eval.me() is not allowed in Hubitat sandbox)")

        default:
            throw new IllegalArgumentException("Unknown condition type: ${condition.type}")
    }
}

def validateAction(action) {
    if (!action.type) {
        throw new IllegalArgumentException("Action type is required")
    }

    switch (action.type) {
        case "device_command":
            if (!action.deviceId) throw new IllegalArgumentException("device_command action requires deviceId")
            if (!action.command) throw new IllegalArgumentException("device_command action requires command")
            def device = findDevice(action.deviceId)
            if (!device) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            def supportedCommands = device.supportedCommands?.collect { it.name }
            if (!supportedCommands?.contains(action.command)) {
                throw new IllegalArgumentException("Device ${device.label} does not support command: ${action.command}")
            }
            break
        case "toggle_device":
            if (!action.deviceId) throw new IllegalArgumentException("toggle_device action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            break
        case "activate_scene":
            if (!action.sceneDeviceId) throw new IllegalArgumentException("activate_scene action requires sceneDeviceId")
            if (!findDevice(action.sceneDeviceId)) throw new IllegalArgumentException("Device not found: ${action.sceneDeviceId}")
            break
        case "set_variable":
            if (!action.variableName) throw new IllegalArgumentException("set_variable action requires variableName")
            break
        case "set_local_variable":
            if (!action.variableName) throw new IllegalArgumentException("set_local_variable action requires variableName")
            break
        case "set_mode":
            if (!action.mode) throw new IllegalArgumentException("set_mode action requires mode")
            def validModes = location.modes?.collect { it.name }
            if (validModes && !validModes.contains(action.mode)) {
                throw new IllegalArgumentException("set_mode: invalid mode '${action.mode}'. Valid modes: ${validModes.join(', ')}")
            }
            break
        case "set_hsm":
            if (!action.status) throw new IllegalArgumentException("set_hsm action requires status")
            def validHsmActions = ["armAway", "armHome", "armNight", "disarm"]
            if (!validHsmActions.contains(action.status)) {
                throw new IllegalArgumentException("set_hsm: invalid status '${action.status}'. Valid values: ${validHsmActions.join(', ')}")
            }
            break
        case "delay":
            if (action.seconds == null) throw new IllegalArgumentException("delay action requires seconds")
            if (action.seconds < 0) throw new IllegalArgumentException("delay action: seconds cannot be negative")
            break
        case "if_then_else":
            if (!action.condition) throw new IllegalArgumentException("if_then_else action requires condition")
            if (!action.thenActions) throw new IllegalArgumentException("if_then_else action requires thenActions")
            validateCondition(action.condition)
            action.thenActions.each { validateAction(it) }
            action.elseActions?.each { validateAction(it) }
            break
        case "cancel_delayed":
            break
        case "repeat":
            def repeatTimes = action.times != null ? action.times : action.count
            if (repeatTimes == null) throw new IllegalArgumentException("repeat action requires times (or count)")
            if (repeatTimes < 1) throw new IllegalArgumentException("repeat action: times must be at least 1")
            if (!action.actions) throw new IllegalArgumentException("repeat action requires actions")
            action.actions.each { validateAction(it) }
            break
        case "stop":
            break
        case "log":
            if (!action.message) throw new IllegalArgumentException("log action requires message")
            break
        case "set_level":
            if (!action.deviceId) throw new IllegalArgumentException("set_level action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (action.level == null) throw new IllegalArgumentException("set_level action requires level")
            break
        case "set_color":
            if (!action.deviceId) throw new IllegalArgumentException("set_color action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            break
        case "set_color_temperature":
            if (!action.deviceId) throw new IllegalArgumentException("set_color_temperature action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (action.temperature == null) throw new IllegalArgumentException("set_color_temperature action requires temperature")
            break
        case "lock":
        case "unlock":
            if (!action.deviceId) throw new IllegalArgumentException("${action.type} action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            break
        case "capture_state":
            if (!action.deviceIds) throw new IllegalArgumentException("capture_state action requires deviceIds")
            break
        case "restore_state":
            break
        case "send_notification":
            if (!action.deviceId) throw new IllegalArgumentException("send_notification action requires deviceId")
            if (!action.message) throw new IllegalArgumentException("send_notification action requires message")
            break
        case "set_thermostat":
            if (!action.deviceId) throw new IllegalArgumentException("set_thermostat action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (!action.thermostatMode && action.heatingSetpoint == null && action.coolingSetpoint == null && !action.fanMode) {
                throw new IllegalArgumentException("set_thermostat requires at least one of: thermostatMode, heatingSetpoint, coolingSetpoint, fanMode")
            }
            if (action.thermostatMode && !["heat", "cool", "auto", "off", "emergency heat"].contains(action.thermostatMode)) {
                throw new IllegalArgumentException("set_thermostat: invalid thermostatMode '${action.thermostatMode}'")
            }
            def isCelsius = location.temperatureScale == "C"
            def minSetpoint = isCelsius ? 4 : 40
            def maxSetpoint = isCelsius ? 38 : 100
            if (action.heatingSetpoint != null && (action.heatingSetpoint < minSetpoint || action.heatingSetpoint > maxSetpoint)) {
                throw new IllegalArgumentException("set_thermostat: heatingSetpoint must be ${minSetpoint}-${maxSetpoint}")
            }
            if (action.coolingSetpoint != null && (action.coolingSetpoint < minSetpoint || action.coolingSetpoint > maxSetpoint)) {
                throw new IllegalArgumentException("set_thermostat: coolingSetpoint must be ${minSetpoint}-${maxSetpoint}")
            }
            if (action.fanMode && !["auto", "on", "circulate"].contains(action.fanMode)) {
                throw new IllegalArgumentException("set_thermostat: invalid fanMode '${action.fanMode}'")
            }
            break
        case "http_request":
            if (!action.url) throw new IllegalArgumentException("http_request action requires url")
            if (!(action.url.startsWith("http://") || action.url.startsWith("https://"))) {
                throw new IllegalArgumentException("http_request: url must start with http:// or https://")
            }
            if (action.method && !["GET", "POST"].contains(action.method)) {
                throw new IllegalArgumentException("http_request: method must be GET or POST")
            }
            if (action.method == "POST" && !action.body) {
                throw new IllegalArgumentException("http_request: body is required for POST requests")
            }
            break
        case "speak":
            if (!action.deviceId) throw new IllegalArgumentException("speak action requires deviceId")
            if (!action.message) throw new IllegalArgumentException("speak action requires message")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            break
        case "comment":
            if (!action.text) throw new IllegalArgumentException("comment action requires text")
            break
        case "set_valve":
            if (!action.deviceId) throw new IllegalArgumentException("set_valve action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (!action.command) throw new IllegalArgumentException("set_valve action requires command")
            if (!["open", "close"].contains(action.command)) {
                throw new IllegalArgumentException("set_valve: command must be 'open' or 'close'")
            }
            break
        case "set_fan_speed":
            if (!action.deviceId) throw new IllegalArgumentException("set_fan_speed action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (!action.speed) throw new IllegalArgumentException("set_fan_speed action requires speed")
            if (!["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"].contains(action.speed)) {
                throw new IllegalArgumentException("set_fan_speed: invalid speed '${action.speed}'")
            }
            break
        case "set_shade":
            if (!action.deviceId) throw new IllegalArgumentException("set_shade action requires deviceId")
            if (!findDevice(action.deviceId)) throw new IllegalArgumentException("Device not found: ${action.deviceId}")
            if (action.command == null && action.position == null) {
                throw new IllegalArgumentException("set_shade action requires command or position")
            }
            if (action.command && !["open", "close"].contains(action.command)) {
                throw new IllegalArgumentException("set_shade: command must be 'open' or 'close'")
            }
            if (action.position != null && (action.position < 0 || action.position > 100)) {
                throw new IllegalArgumentException("set_shade: position must be 0-100")
            }
            break
        case "variable_math":
            if (!action.variableName) throw new IllegalArgumentException("variable_math action requires variableName")
            if (!action.operation) throw new IllegalArgumentException("variable_math action requires operation")
            if (!["add", "subtract", "multiply", "divide", "modulo", "set"].contains(action.operation)) {
                throw new IllegalArgumentException("variable_math: operation must be one of: add, subtract, multiply, divide, modulo, set")
            }
            if (action.operand == null) throw new IllegalArgumentException("variable_math action requires operand")
            if (action.scope && !["local", "global"].contains(action.scope)) {
                throw new IllegalArgumentException("variable_math: scope must be 'local' or 'global'")
            }
            break
        default:
            throw new IllegalArgumentException("Unknown action type: ${action.type}")
    }
}

// ==================== HELPER FUNCTIONS ====================

def findDevice(deviceId) {
    if (!deviceId) return null
    // Search selected devices first, then MCP-managed child devices (virtual devices)
    def device = settings.selectedDevices?.find { it.id.toString() == deviceId.toString() }
    if (!device) {
        device = getChildDevices()?.find { it.id.toString() == deviceId.toString() }
    }
    return device
}

// Expose devices to child apps
def getSelectedDevices() {
    return settings.selectedDevices
}


// ==================== HUB SECURITY & INTERNAL API HELPERS ====================

/**
 * Authenticate with Hub Security and return a session cookie.
 * Returns null if Hub Security is not enabled or credentials are not configured.
 * Caches the cookie for 30 minutes to avoid excessive login requests.
 */
// Single source for the hub's internal API base URI (was an 11x-repeated literal).
def hubBaseUri() { "http://127.0.0.1:8080" }

// Timeout rationale: reads are fast localhost fetches; writes (native-RM wizard steps,
// large app/driver/library save+compile) can legitimately take minutes.
def hubReadTimeoutSec() { 30 }
def hubWriteTimeoutSec() { 420 }

def getHubSecurityCookie() {
    if (!settings.hubSecurityEnabled) return null
    if (!settings.hubSecurityUser || !settings.hubSecurityPassword) {
        mcpLog("warn", "hub-admin", "Hub Security is enabled but credentials are not configured")
        return null
    }

    // Cached cookie lives in atomicState (thread-safe): it authorizes every hubInternal* call
    // and is touched concurrently by overlapping requests and the cookie-refresh retry path.
    if (atomicState.hubSecurityCookie && atomicState.hubSecurityCookieExpiry && atomicState.hubSecurityCookieExpiry > now()) {
        return atomicState.hubSecurityCookie
    }

    // Authenticate
    def cookie = null
    try {
        httpPost([
            uri: hubBaseUri(),
            path: "/login",
            body: [username: settings.hubSecurityUser, password: settings.hubSecurityPassword],
            textParser: true,
            ignoreSSLIssues: true
        ]) { resp ->
            cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub Security authentication failed", e)
        throw new RuntimeException("Hub Security authentication failed. Check your username and password in MCP Rule Server settings.")
    }

    if (cookie) {
        atomicState.hubSecurityCookie = cookie
        atomicState.hubSecurityCookieExpiry = now() + (30 * 60 * 1000) // 30 minutes
        mcpLog("debug", "hub-admin", "Hub Security authentication successful")
    } else {
        mcpLog("warn", "hub-admin", "Hub Security authentication returned no cookie")
    }

    return cookie
}

/**
 * Check if an exception indicates an auth failure that should be retried with a fresh cookie.
 * If so, clears the cached cookie and returns true.
 */
private boolean shouldRetryWithFreshCookie(Exception e, boolean isRetry) {
    if (isRetry || !settings.hubSecurityEnabled) return false
    // Prefer the HTTP status the exception carries (duck-typed e.response.status -- naming
    // HttpResponseException NCDFEs at parse time on the test classpath). Fall back to a
    // message substring only when no status is available.
    def resp = null
    try { resp = e.response } catch (Exception ignore) { resp = null }
    Integer status = null
    try { status = resp?.status as Integer } catch (Exception ignore) { status = null }
    boolean authFail = (status == 401 || status == 403) ||
        (status == null && (e.message?.contains("401") || e.message?.contains("403") || e.message?.contains("Unauthorized")))
    if (authFail) {
        atomicState.hubSecurityCookie = null
        atomicState.hubSecurityCookieExpiry = null
        return true
    }
    return false
}

/**
 * Read an HTTPBuilder response body as text. With textParser:true the hub hands back a Reader,
 * but some paths (and test stubs) hand back a plain String. A CharSequence is already its own
 * text, so we take it as-is -- we never call toString() on a half-consumed Reader/InputStream,
 * which would yield junk like "java.io.BufferedReader@1a2b3c" that downstream code would treat
 * as a real body. A genuine Reader/stream read failure (socket reset mid-stream, gzip CRC,
 * decode error) propagates so the caller can re-throw rather than swallow junk.
 */
private String _readRespText(resp) {
    def d = resp?.data
    if (d == null) return null
    if (d instanceof CharSequence) return d.toString()
    return d.text  // Reader/InputStream -- may throw on a mid-stream read failure
}

/**
 * Shared core for the six hubInternal* variants: cookie attach, request, duck-typed body read
 * (read failures are re-thrown, never swallowed into a Reader.toString() junk string), and the
 * single cookie-refresh retry. The thin public wrappers below project their distinguishing
 * options + return shape. This is the clean seam #209 can later lift into a HubHttpClientLib.
 */
private _hubRequest(String method, String path, Map opts = [:]) {
    def cookie = getHubSecurityCookie()
    def params = [
        uri: hubBaseUri(),
        path: path,
        textParser: true,
        ignoreSSLIssues: true,
        timeout: (opts.timeout != null ? opts.timeout : hubReadTimeoutSec())
    ]
    if (opts.query) params.query = opts.query
    if (opts.requestContentType) params.requestContentType = opts.requestContentType
    if (opts.followRedirects != null) params.followRedirects = opts.followRedirects
    def headers = [:]
    if (opts.keepAlive) headers["Connection"] = "keep-alive"
    if (cookie) headers["Cookie"] = cookie
    if (headers) params.headers = headers
    if (opts.body != null) params.body = opts.body

    def result = null
    def readError = null
    def reader = { resp ->
        def bodyText = null
        try { bodyText = _readRespText(resp) }
        catch (Exception re) { readError = re }
        if (opts.returnShape == 'struct') {
            // Struct callers (form/raw/getRaw) key on .status; the HTTPBuilder closure
            // only runs for a non-error (2xx) response (4xx/5xx throw into the catch
            // below), so a body-read failure here is a write that COMMITTED with an
            // unreadable body -- surface status + null data rather than failing the
            // operation for a status-only caller (e.g. _rmClickAppButton). The read
            // error is consumed here; text callers, whose body IS the result, still
            // re-throw it after the closure.
            result = [status: resp.status, location: resp.headers?."Location"?.toString(), data: (readError != null ? null : bodyText)]
            readError = null
        } else if (readError == null) {
            result = bodyText
        }
    }
    long _hubRtT0 = now()
    try {
        if (method == 'GET') httpGet(params, reader)
        else httpPost(params, reader)
        // [hubrt] per-call diagnostic: every internal hub round-trip funnels through here, so one
        // debug line profiles a heavy RM wizard build (count + latency of each GET/POST). Debug-gated.
        logDebug("[hubrt] ${method} ${path} (${now() - _hubRtT0}ms)")
    } catch (Exception e) {
        // hubInternalGetRaw path: a 3xx with followRedirects=false is the success case (read the
        // Location header), not an error.
        if (opts.handle3xx) {
            def resp = null
            try { resp = e.response } catch (Exception ignore) { resp = null }
            Integer st = null
            try { st = resp?.status as Integer } catch (Exception ignore) { st = null }
            if (resp != null && st != null && st >= 300 && st < 400) {
                def b = null
                try { b = _readRespText(resp) } catch (Exception ignore) { b = null }
                return [status: st, location: resp.headers?."Location"?.toString(), data: b]
            }
        }
        if (shouldRetryWithFreshCookie(e, opts.isRetry)) {
            mcpLog("debug", "hub-admin", "Retrying with fresh cookie after auth failure on ${method} ${path}")
            return _hubRequest(method, path, opts + [isRetry: true])
        }
        throw e
    }
    // Re-throw a mid-stream read failure rather than returning a Reader/stream toString() junk
    // string that downstream code would treat as a real body (matches the hardened deploy path).
    if (readError != null) throw readError
    return result
}

/**
 * Make an authenticated GET request to the hub's internal API.
 * Automatically includes Hub Security cookie if configured.
 * Returns the response body as text.
 */
def hubInternalGet(String path, Map query = null, int timeout = 30, boolean isRetry = false) {
    _hubRequest('GET', path, [query: query, timeout: timeout, returnShape: 'text', isRetry: isRetry])
}

/**
 * Authenticated GET that captures status + Location header + body without
 * following redirects. Needed for /installedapp/createchild/<ns>/<app>/parent/<pid>
 * which responds with a 302 pointing at /installedapp/configure/<newId>; the
 * new child id lives in that Location header and is lost if the client auto-
 * follows. Shape matches hubInternalPostForm's return for consistency.
 * Caveat: ABSOLUTE Location values may still be auto-followed by the platform
 * client (observed live on /installedapp/sysApp), returning 200 with no
 * Location -- callers must not depend on the 302 shape.
 *
 * Exception handling is duck-typed rather than referencing
 * groovyx.net.http.HttpResponseException by name — that class isn't on the
 * Spock test classpath, and naming it would NCDFE at parse time.
 */
def hubInternalGetRaw(String path, Map query = null, int timeout = 30, boolean isRetry = false) {
    // followRedirects:false + handle3xx so the 302 Location header (new-child id) is readable.
    _hubRequest('GET', path, [query: query, timeout: timeout, returnShape: 'struct',
                              followRedirects: false, handle3xx: true, isRetry: isRetry])
}

/**
 * Resolve a name-addressed installed-app alias to its instance id via the
 * hub's /installedapp/direct/<alias> redirect chain (two explicit hops):
 *   GET /installedapp/direct/<alias>  -> 302 Location: /installedapp/create/<typeId>
 *   GET /installedapp/create/<typeId> -> 302 Location: /installedapp/configure/<instanceId>
 * Returns the instance id, or null on any failure (non-redirect status,
 * missing/unparseable Location, exception) so callers can fall back to other
 * discovery. The configure page itself is never fetched -- the id lives in
 * the Location header, and following further would render HTML for nothing.
 *
 * Get-or-create caveat: the create hop is the hub's "open this app" flow.
 * For singleton system apps (e.g. hubVariables) it returns the EXISTING
 * instance every time, so probing is side-effect free. For transient tool
 * apps (e.g. swapDevice) every call CREATES a fresh instance -- callers own
 * cleanup of any instance they don't drive to completion.
 */
private Integer _resolveDirectAppId(String alias) {
    def path = "/installedapp/direct/${alias}"
    // Two hops max: direct -> create -> configure. Each hop re-validates the
    // redirect shape so a hub that auto-followed an absolute Location (200
    // with no Location header -- see hubInternalGetRaw's caveat) degrades to
    // null instead of mis-parsing an HTML body.
    for (int hop = 1; hop <= 2; hop++) {
        def resp = null
        try {
            resp = hubInternalGetRaw(path)
        } catch (Exception e) {
            logDebug("_resolveDirectAppId(${alias}): hop ${hop} GET ${path} threw ${e.toString()}")
            mcpLog("warn", "hub-admin", "_resolveDirectAppId(${alias}) -> null: hop ${hop} GET threw ${e.class.simpleName}: ${e.message}")
            return null
        }
        Integer status = null
        try { status = resp?.status as Integer } catch (Exception ignore) { status = null }
        def location = resp?.location?.toString()
        if (status == null || status < 300 || status >= 400 || !location) {
            logDebug("_resolveDirectAppId(${alias}): hop ${hop} ${path} status=${status} location=${location} -- not a redirect")
            mcpLog("warn", "hub-admin", "_resolveDirectAppId(${alias}) -> null: hop ${hop} returned status=${status} instead of a redirect -- a 200 with no Location usually means the hub auto-followed an absolute Location (hubInternalGetRaw caveat)")
            return null
        }
        def cfg = (location =~ /\/installedapp\/configure\/(\d+)/)
        if (cfg) return cfg[0][1] as Integer
        def create = (location =~ /\/installedapp\/create\/(\d+)/)
        if (create && hop == 1) {
            // Rebuild the hop-2 path from the captured type id rather than
            // following the Location verbatim -- normalizes absolute URLs and
            // guarantees we never follow past the expected chain.
            path = "/installedapp/create/${create[0][1]}"
            continue
        }
        logDebug("_resolveDirectAppId(${alias}): hop ${hop} unexpected Location ${location}")
        mcpLog("warn", "hub-admin", "_resolveDirectAppId(${alias}) -> null: hop ${hop} redirected to an unexpected Location shape -- the direct/create/configure chain may have changed on this firmware (the debug-logging toggle surfaces the raw Location in the hub logs)")
        return null
    }
    return null
}

/**
 * Fetch Groovy source from an external URL. Mirrors the editor's "Import
 * Code from Website" + Save flow, relocated server-side so MCP callers
 * can deploy from a URL in one tool call.
 *
 * Throws IllegalArgumentException with a structured message + class name on
 * bad scheme, non-200 status, fetch exception, or empty body. mcpLogs each
 * failure at error level so the MCP log buffer carries the URL + cause.
 *
 * (Live-deployed via importUrl: marker comment to verify end-to-end smoke.)
 */
private String _fetchSourceFromUrl(urlArg) {
    // Accept Object so we can validate at the boundary; a typed `String url`
    // signature would let Groovy reject non-Strings with MissingMethodException
    // before our structured IAE could fire.
    if (urlArg == null) {
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl called with null url")
        throw new IllegalArgumentException("importUrl is required")
    }
    if (!(urlArg instanceof String)) {
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl: importUrl is not a String (got ${urlArg})")
        throw new IllegalArgumentException("importUrl must be a String")
    }
    String url = (String) urlArg
    def lower = url.toLowerCase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl: bad scheme in ${url.take(40)}")
        throw new IllegalArgumentException("importUrl scheme must be http or https (got '${url.take(40)}')")
    }
    def resp
    try {
        resp = _httpFetchUrl(url)
    } catch (Exception e) {
        // Include class via toString() since sandbox forbids getClass().
        // e.message can be null on SSL/socket exceptions; toString() always returns something.
        def cause = e.toString()
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl ${url}: ${cause}")
        throw new IllegalArgumentException("importUrl fetch failed: ${cause}")
    }
    def status = resp?.status
    def body = resp?.body
    if (status == null) {
        // httpGet returned without invoking the closure -- shouldn't happen on the
        // synchronous path, but if it does the misleading "HTTP null" error helps
        // distinguish "no response" from "non-200 response".
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl ${url}: httpGet returned without status (closure never invoked)")
        throw new IllegalArgumentException("importUrl fetch returned no response (status null) -- httpGet closure never invoked for ${url}")
    }
    if (status != 200) {
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl ${url}: HTTP ${status}")
        throw new IllegalArgumentException("importUrl returned HTTP ${status} for ${url}")
    }
    if (!body) {
        mcpLog("error", "hub-admin", "_fetchSourceFromUrl ${url}: empty body")
        throw new IllegalArgumentException("importUrl returned empty body from ${url}")
    }
    // Surface content-type at info level so a user debugging "import succeeds but
    // hub returns syntax error pointing at line 1" can see if the URL returned HTML
    // or JSON instead of Groovy. Not load-bearing -- we still let the hub be the
    // arbiter of valid source -- but it's the difference between "unexpected token: <"
    // being mysterious vs the log line saying contentType=text/html;charset=utf-8.
    def ct = resp.contentType
    if (ct) {
        mcpLog("info", "hub-admin", "_fetchSourceFromUrl ${url}: ${body.length()} bytes, contentType=${ct}")
    } else {
        mcpLog("info", "hub-admin", "_fetchSourceFromUrl ${url}: ${body.length()} bytes")
    }
    return body
}

/**
 * Synchronous httpGet wrapped as a plain Map-returning method.
 * Returns [status: int, body: String, contentType: String].
 *
 * Body read failures (network reset mid-stream, gzip CRC, encoding decode)
 * are re-thrown rather than swallowed. The previous "fall back to toString()
 * on a Reader" pattern produces strings like "java.io.BufferedReader@..." that
 * would silently pass downstream as source code -- not safe for a deploy path.
 *
 * Cert validation is NOT disabled for external URLs (unlike hubInternalGet
 * which targets localhost). A hub-side fetch of executable code over a
 * trusted-CA-signed connection is the floor; self-signed or MITM-d URLs
 * fail the handshake.
 */
private Map _httpFetchUrl(String url) {
    def status = null
    def body = null
    def contentType = null
    def readError = null
    httpGet([
        uri: url,
        textParser: true,
        timeout: 60
    ]) { resp ->
        status = resp.status
        contentType = resp.headers?.'Content-Type'?.toString()
        try { body = resp.data.text }
        catch (Exception readErr) { readError = readErr }
    }
    if (readError != null) {
        // Surface the read error to _fetchSourceFromUrl's outer catch, which wraps
        // with class name + mcpLogs. Don't swallow with toString() junk.
        throw readError
    }
    return [status: status, body: body, contentType: contentType]
}


/**
 * Make an authenticated POST request to the hub's internal API.
 * Automatically includes Hub Security cookie if configured.
 * Returns the response body as text.
 */
def hubInternalPost(String path, Map body = null, int timeout = 30, boolean isRetry = false) {
    _hubRequest('POST', path, [body: body, timeout: timeout, returnShape: 'text', isRetry: isRetry])
}

/**
 * POST a pre-encoded form-urlencoded body to the hub's internal API. Use
 * this instead of `hubInternalPostForm` when the body contains values
 * HTTPBuilder's auto-encoder mangles (notably backslash + quote sequences
 * inside JSON values). Caller is responsible for URL-encoding keys/values
 * themselves; this method passes the body string straight through.
 */
def hubInternalPostFormRaw(String path, String encodedBody, int timeout = 420, boolean isRetry = false) {
    _hubRequest('POST', path, [body: encodedBody, timeout: timeout,
                              requestContentType: "application/x-www-form-urlencoded", keepAlive: true,
                              returnShape: 'struct', isRetry: isRetry])
}

/**
 * Form-encoded POST to the hub's internal API. Used by the classic dynamicPage surfaces
 * (/installedapp/* settings submits, button clicks, lifecycle fires) and other endpoints
 * that require application/x-www-form-urlencoded.
 */
def hubInternalPostForm(String path, Map body, int timeout = 420, boolean isRetry = false) {
    _hubRequest('POST', path, [body: body, timeout: timeout,
                              requestContentType: "application/x-www-form-urlencoded", keepAlive: true,
                              returnShape: 'struct', isRetry: isRetry])
}

/**
 * POST to the hub's internal API with a JSON body. Used by the saveOrUpdateJson family
 * (app/driver/library create and update) and other Content-Type: application/json endpoints.
 * Returns a parsed Map/List from the JSON response body, null on an EMPTY body, or an
 * [_unparseable: true, message: ...] sentinel Map when the body wasn't JSON (e.g. an HTML
 * login page) -- callers must not conflate the last two: an empty body can be a legitimate
 * dropped-response signature, a non-JSON body never is.
 */
def hubInternalPostJson(String path, String jsonBody, int timeout = 420, boolean isRetry = false) {
    def bodyText = _hubRequest('POST', path, [body: jsonBody, timeout: timeout,
                              requestContentType: "application/json", keepAlive: true,
                              returnShape: 'text', isRetry: isRetry])
    if (bodyText) {
        try {
            return new groovy.json.JsonSlurper().parseText(bodyText)
        } catch (Exception parseErr) {
            mcpLog("error", "hub-admin", "hubInternalPostJson ${path}: response not JSON: ${bodyText?.take(200)}")
            return [_unparseable: true, message: "hub returned a non-JSON body from ${path}: ${bodyText?.take(200)}"]
        }
    }
    return null
}

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

/**
 * Automatically back up an individual item's source code before modifying or deleting it.
 * Saves the source code as a .groovy file in the hub's local File Manager using uploadHubFile().
 * Metadata (timestamp, version, etc.) is stored in atomicState.itemBackupManifest.
 * Files are accessible at http://<HUB_IP>/local/<filename> even if MCP fails.
 * If a backup of this item already exists within the last hour, skips (preserves the pre-edit original).
 * Returns the manifest entry on success, or throws if the source cannot be retrieved.
 */
def backupItemSource(String type, String id) {
    // atomicState read-modify-write: read the full manifest map, mutate locally,
    // write back atomically. Direct nested writes to state silently fail on Hubitat.
    def manifest = atomicState.itemBackupManifest ?: [:]

    def key = "${type}_${id}"
    def existing = manifest[key]

    // If a backup exists within the last hour, keep it (preserves the original before a series of edits)
    if (existing?.timestamp && (now() - existing.timestamp) < 3600000) {
        mcpLog("debug", "hub-admin", "Item backup for ${key} already exists (${formatTimestamp(existing.timestamp)}), skipping")
        return existing
    }

    // Fetch the current source
    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
    def responseText = hubInternalGet(ajaxPath, [id: id])
    if (!responseText) {
        throw new IllegalArgumentException("Cannot back up ${type} ID ${id}: empty response from hub")
    }

    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
    if (parsed.status == "error" || !parsed.source) {
        throw new IllegalArgumentException("Cannot back up ${type} ID ${id}: ${parsed.errorMessage ?: 'no source code returned'}")
    }

    // Save full source code to hub's local File Manager (no cloud, no size limit)
    def fileName = "mcp-backup-${type}-${id}.groovy"
    try {
        uploadHubFile(fileName, parsed.source.getBytes("UTF-8"))
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to save backup file '${fileName}'", e)
        throw new IllegalArgumentException("Cannot back up ${type} ID ${id}: file upload failed -- ${e.message}")
    }

    def entry = [
        type: type,
        id: id,
        fileName: fileName,
        version: parsed.version,
        timestamp: now(),
        sourceLength: parsed.source.length()
    ]
    manifest[key] = entry

    // Prune old backups -- keep at most 20 entries, remove oldest if over limit
    if (manifest.size() > 20) {
        def oldest = manifest.min { it.value.timestamp }
        if (oldest) {
            mcpLog("debug", "hub-admin", "Pruning oldest backup: ${oldest.key} (${oldest.value.fileName}, from ${formatTimestamp(oldest.value.timestamp)})")
            try { deleteHubFile(oldest.value.fileName) } catch (Exception e) {
                mcpLog("warn", "hub-admin", "Could not delete pruned backup file '${oldest.value.fileName}': ${e.message}")
            }
            manifest.remove(oldest.key)
        }
    }

    atomicState.itemBackupManifest = manifest
    mcpLog("info", "hub-admin", "Backed up ${type} ID ${id} source code to File Manager: ${fileName} (version ${parsed.version}, ${parsed.source.length()} chars)")
    return entry
}

// ==================== FILE MANAGER TOOLS ====================


/**
 * Formats an epoch timestamp into a human-readable age string (e.g., "5 minutes ago").
 */
def formatAge(Long timestamp) {
    if (!timestamp) return "unknown"
    def elapsed = now() - timestamp
    if (elapsed < 60000) return "just now"
    def minutes = (elapsed / 60000) as Integer
    if (elapsed < 3600000) return "${minutes} ${minutes == 1 ? 'minute' : 'minutes'} ago"
    def hours = (elapsed / 3600000) as Integer
    if (elapsed < 86400000) return "${hours} ${hours == 1 ? 'hour' : 'hours'} ago"
    def days = (elapsed / 86400000) as Integer
    return "${days} ${days == 1 ? 'day' : 'days'} ago"
}

// RC-only protocol items (_meta request echo, ttlMs/cacheScope list-cache
// hints, tools/list nextCursor) are intentionally NOT implemented -- they are
// next-revision RC features and no shipped client negotiates that
// protocolVersion yet. Revisit when the spec publishes and a client uses it.
def jsonRpcResult(id, result) {
    return [jsonrpc: "2.0", id: id, result: result]
}

def jsonRpcError(id, code, message, data = null) {
    def error = [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    if (data) error.error.data = data
    return error
}

def logDebug(msg) {
    if (settings.debugLogging) {
        log.debug msg
    }
}

// ==================== MCP DEBUG LOGGING SYSTEM ====================

/**
 * Initialize the debug logging state structure
 */
def initDebugLogs() {
    if (!state.debugLogs) {
        state.debugLogs = [
            entries: [],
            config: [logLevel: "error", maxEntries: 100]
        ]
    }
    if (!state.debugLogs.entries) state.debugLogs.entries = []
    if (!state.debugLogs.config) state.debugLogs.config = [logLevel: "error", maxEntries: 100]
}

/**
 * Get available log levels in priority order
 */
def getLogLevels() {
    return ["debug", "info", "warn", "error"]
}

/**
 * Get configured log level threshold
 * Checks settings first (UI), then state (MCP hub_set_log_level), then defaults to "error"
 */
def getConfiguredLogLevel() {
    // Settings take priority (can be set via UI)
    if (settings.mcpLogLevel) return settings.mcpLogLevel
    // Fall back to state (can be set via MCP hub_set_log_level tool)
    return state.debugLogs?.config?.logLevel ?: "error"
}

/**
 * Check if a log level should be recorded based on threshold
 */
def shouldLog(level) {
    def levels = getLogLevels()
    def currentIndex = levels.indexOf(getConfiguredLogLevel())
    def logIndex = levels.indexOf(level)
    // Fail open on an unrecognized level so a typo'd level is never silently
    // swallowed -- the mcpLog switch default below emits a self-diagnosing warn.
    if (logIndex == -1) return true
    return logIndex >= currentIndex
}

/**
 * Add a log entry to the MCP-accessible debug buffer
 */
def mcpLog(String level, String component, String message, String ruleId = null, Map extraData = null) {
    if (!shouldLog(level)) return

    initDebugLogs()

    def entry = [
        timestamp: now(),
        level: level,
        component: component,
        // Cap stored payload so each buffer entry stays bounded -- the
        // `state.debugLogs = state.debugLogs` writeback below re-serializes the
        // whole buffer on every log line, so an uncapped caller message/trace
        // would inflate every subsequent write. Mirrors the 500-char response
        // cap idiom in handleMcpRequest. details stays a structured Map (every
        // caller passes a small bounded Map, not unbounded text).
        message: message?.take(500)
    ]

    if (ruleId) entry.ruleId = ruleId
    if (extraData?.duration) entry.duration = extraData.duration
    if (extraData?.ruleName) entry.ruleName = extraData.ruleName
    if (extraData?.details) entry.details = extraData.details
    if (extraData?.stackTrace) entry.stackTrace = extraData.stackTrace?.toString()?.take(1000)

    state.debugLogs.entries << entry

    // Enforce max entries limit (circular buffer)
    def maxEntries = state.debugLogs.config?.maxEntries ?: 100
    while (state.debugLogs.entries.size() > maxEntries) {
        state.debugLogs.entries.remove((int)0)
    }

    // Force top-level state reassignment to ensure nested mutations are persisted
    state.debugLogs = state.debugLogs

    // Also log to Hubitat logs. Append the structured stackTrace (class + message,
    // set by mcpLogError) to the warn/error native lines so the exception detail
    // stays visible on the Hubitat Logs page, not only in the MCP debug buffer.
    def traceSuffix = extraData?.stackTrace ? " -- ${extraData.stackTrace.toString().take(1000)}" : ""
    switch (level) {
        case "debug": log.debug "[${component}] ${message}"; break
        case "info": log.info "[${component}] ${message}"; break
        case "warn": log.warn "[${component}] ${message}${traceSuffix}"; break
        case "error": log.error "[${component}] ${message}${traceSuffix}"; break
        default: log.warn "[${component}] (unknown level '${level}') ${message}${traceSuffix}"; break
    }
}

/**
 * Log an error with optional exception details
 */
def mcpLogError(String component, String message, Exception e = null, String ruleId = null) {
    def extraData = [:]
    if (e) {
        extraData.stackTrace = "${e.class.name}: ${e.message}"
    }
    mcpLog("error", component, message, ruleId, extraData)
}

// ==================== ROOM MANAGEMENT ====================
// Tool implementations (toolListRooms / toolGetRoom / toolCreateRoom / toolDeleteRoom /
// toolRenameRoom) live in the McpRoomsLib library (libraries/mcp-rooms-lib.groovy),
// #include'd near the top of this file. The hub_manage_rooms / hub_read_rooms gateway entries
// and the executeTool dispatch cases stay here in the app; the tool definitions
// (_getAllToolDefinitions_partRooms) live alongside the impl in the library.

// ==================== HPM PACKAGE STATE TOOL IMPLEMENTATIONS ====================


/**
 * Coerce a ruleId argument to Integer. Accepts Number or numeric String.
 * Narrow the catch to NumberFormatException only — it's the sole expected
 * failure shape for `String as Integer`; anything else is a real bug and
 * should propagate rather than be rewrapped as an IllegalArgumentException.
 */
private Integer normalizeRuleId(def ruleId) {
    if (ruleId instanceof Number) return ruleId.toInteger()
    try {
        return ruleId.toString().trim() as Integer
    } catch (NumberFormatException e) {
        throw new IllegalArgumentException("ruleId must be an integer, got: ${ruleId}")
    }
}

// ============ SHARED CLASSIC-DYNAMICPAGE WIZARD PRIMITIVES ============
//
// These helpers drive Hubitat's classic dynamicPage / submitOnChange admin-UI
// protocol and are SHARED across domains -- the native-RM tools (now in
// McpNativeRulesLib) plus the devices, variables, app-cloner, code-management,
// and visual-rules libraries all call them. They stay in the host app because a
// library-private home would force a forbidden cross-#include (AGENTS.md).
//
// The wire-format contract works around Hubitat's SmartApp parent-type check
// (addChildApp('hubitat', 'Rule-5.1', ...) is blocked) by hitting the hub's
// admin-layer endpoints directly via session cookie:
//
//   Create:   GET  /installedapp/createchild/<ns>/<appName>/parent/<pid>  → 302
//   Read:     GET  /installedapp/configure/json/<id>[/<subpage>]
//   Status:   GET  /installedapp/statusJson/<id>
//   Update:   POST /installedapp/update/json  (x-www-form-urlencoded)
//   Button:   POST /installedapp/btn
//   Delete:   GET  /installedapp/forcedelete/<id>/quiet
//
// The capability-multiple contract: multi-device capability inputs need
// THREE paired fields in the same POST (settings[name]=csv, name.type=
// capability.X, name.multiple=true). Omitting `.multiple=true` silently
// rewrites the AppSetting DB flag to false and every subsequent page
// render throws `Command 'size' is not supported by device` against RM's
// list-of-devices code paths. _rmBuildSettingsBody emits the full group
// from the input schema automatically, so callers never have to remember.

/**
 * Registry of native automation app types. Each entry tells the create
 * path which namespace + appName + parent type to use for createchild.
 *
 * Adding a new entry here is the only change needed to support a new
 * native app type — the edit + delete + backup paths are app-type-
 * agnostic because they operate on appIds against the generic
 * /installedapp/* endpoint family.
 *
 * Verified live on firmware 2.4.4.135 / 2.5.0.123:
 *   - rule_machine: namespace=hubitat appName=Rule-5.1 parentType="Rule Machine"
 *
 * Sources for additional entries (per #120 scope expansion notes —
 * confirm namespace+appName via hub_list_apps (scope='instances') before enabling):
 *   - button_controller (parent),  Button Controller-5.1, parentType="Button Controllers"
 *   - button_rule (under controller), Button Rule-5.1, parentType=<a specific Button Controller>
 *   - basic_rule, parentType="Basic Rules"
 *   - room_lighting, parentType="Room Lighting"
 *   - groups_scenes (Group-2.1 / Scene-2.1), parentType="Groups and Scenes"
 *   - notifier (Notifier), parentType="Notifications"
 *   - visual_rule (Visual Rule Builder), parentType="Visual Rules Builder"
 *
 * Edit + delete already work on these today — call hub_set_native_app /
 * hub_delete_native_app with the appId of any existing classic-app instance
 * (read appId via hub_list_apps (scope='instances') + hub_get_app_config).
 */
private Map _appTypeRegistry() {
    // Optional `commitButton` field: the page-transition button clicked after a
    // create/edit to commit + re-initialize. Defaults to "updateRule" (RM's
    // framework-default) when absent. Set explicitly to null for submitOnChange
    // apps that have NO commit button -- their inputs auto-commit on change, and
    // clicking a non-existent "updateRule" button poisons the page render with
    // "For input string: updateRule" (verified live on Basic Rule).
    return [
        rule_machine: [namespace: "hubitat", appName: "Rule-5.1", parentTypeName: "Rule Machine"],
        // Button Controller-5.1's mainPage is submitOnChange (selecting buttonDev
        // re-renders to reveal the button-action table) with NO updateRule button,
        // so commitButton is null -- same class as basic_rule. (The buttonDev-wipe
        // failure mode on these app types is a separate mechanism, documented and
        // fixed at _rmLiveSettingsFromStatus.)
        button_controller: [namespace: "hubitat", appName: "Button Controller-5.1", parentTypeName: "Button Controllers", commitButton: null],
        groups_scenes: [namespace: "hubitat", appName: "Group-2.1", parentTypeName: "Groups and Scenes"],
        notifier: [namespace: "hubitat", appName: "Notifier", parentTypeName: "Notifications"],
        // visual_rule stays registered so appType detection (_rmBackupRuleSnapshot's
        // reverse-map) and parentTypeName lookups keep working, but neither classic creation
        // path uses it: the wizard create rejects it in _createNativeAppShell, and
        // _rmRestoreFromBackup routes visual_rule snapshots to _vrbRestoreFromSnapshot --
        // VRB children are Vue-JSON apps (live-probed: /installedapp/configure renders no
        // classic configPage), served by the hub_*_visual_rule tools instead.
        visual_rule: [namespace: "hubitat", appName: "Visual Rule Builder", parentTypeName: "Visual Rules Builder"],
        // Basic Rule is a classic dynamicPage app (configure/json renders a real
        // configPage and generic createchild works), NOT a Vue SPA like Visual
        // Rule. But its inputs are submitOnChange with no updateRule button, so
        // commitButton is null. Verified live: appName="Basic Rule-1.0",
        // parentType="Basic Rules", generic createchild -> 302 configure/<id>.
        basic_rule: [namespace: "hubitat", appName: "Basic Rule-1.0", parentTypeName: "Basic Rules", commitButton: null],
        // Button Rule-5.1's page graph shifts one level (root page named
        // selectActions, not mainPage) and it is submitOnChange with no
        // updateRule button, so commitButton is null -- _resolveCommitButton
        // then returns null (real verdict) instead of defaulting to "updateRule".
        button_rule: [namespace: "hubitat", appName: "Button Rule-5.1", parentTypeName: "Button Controllers", commitButton: null]
        // button_controller, groups_scenes, notifier child appName values were
        // verified on the live hub. Room Lighting parent exists but has no
        // probed children yet -- add when needed.
    ]
}

/**
 * Discover and cache the parent app id for the given native-app type.
 * Required by _createNativeAppShell (the create path of both hub_set_rule and
 * hub_set_native_app): createchild is addressed
 * `/installedapp/createchild/<ns>/<appName>/parent/<parentId>`, and the
 * parent id is per-hub.
 *
 * Cache in atomicState.parentAppIds[<appType>] -- one network call per type per
 * fresh install. If the app type's built-in parent is not installed yet (e.g. RM
 * or Button Controllers was never enabled on this hub), bootstrap it via the
 * "Add Built-In App" endpoint (GET /installedapp/sysApp/<parentTypeName>) and
 * re-discover; throws a user-actionable error only if that bootstrap fails.
 */
private Integer _discoverParentAppId(String appType) {
    // atomicState read-modify-write: direct nested-map writes to state silently
    // fail on Hubitat because state serializes/deserializes the whole map on each
    // access. Always read the full map, mutate the local copy, then write back.
    def ids = atomicState.parentAppIds ?: [:]
    // Backward-compat shim: pre-rename code wrote parentAppIds.rm.
    // Migrate to the new key name on first read so cached values survive.
    // If both keys exist, prefer the newer one and drop the legacy entry.
    if (appType == "rule_machine" && ids.rm != null && ids.rule_machine == null) {
        ids.rule_machine = ids.rm
    }
    if (ids.rm != null && ids.rule_machine != null) {
        ids.remove("rm")
        atomicState.parentAppIds = ids
    }
    def cached = ids[appType]
    if (cached != null) {
        try { return cached.toString().toInteger() } catch (NumberFormatException e) {
            mcpLog("warn", "rm-native", "Invalid cached parentAppId for '${appType}' ('${cached}') -- rediscovering")
            ids.remove(appType)
            atomicState.parentAppIds = ids
        }
    }

    def reg = _appTypeRegistry()[appType]
    if (!reg) {
        throw new IllegalArgumentException("Unknown appType '${appType}'. Supported: ${_appTypeRegistry().keySet().join(', ')}")
    }
    def parentTypeName = reg.parentTypeName

    // Search /hub2/appsList for the (non-hidden) built-in parent node by type name.
    def findParentNode = {
        def responseText = hubInternalGet("/hub2/appsList")
        if (!responseText) {
            throw new IllegalArgumentException("Cannot discover '${parentTypeName}' parent: empty response from /hub2/appsList")
        }
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        def found = null
        def recurse
        recurse = { node ->
            if (found != null) return
            def d = node?.data
            if (d?.type == parentTypeName && d?.hidden != true) { found = d; return }
            node?.children?.each { c -> recurse(c) }
        }
        (parsed?.apps ?: []).each { a -> recurse(a) }
        return found
    }

    def parentNode = findParentNode()
    def bootstrapDiag = null
    def commitUnverified = false
    if (parentNode?.id == null) {
        // The built-in parent app is not installed yet. Hubitat's "Add Built-In App"
        // link is GET /installedapp/sysApp/<display name> (parentTypeName IS that name);
        // verified live, it CREATES the parent server-side and 302-redirects to its
        // configure page, and the parent appears in /hub2/appsList right away. The redirect
        // Location is ABSOLUTE, so the HTTP client may follow it and return 200 with no
        // Location -- so don't depend on the response shape: fire the GET and RE-DISCOVER
        // by type. Only fires when the parent is absent (never duplicates a singleton).
        // Falls back to parsing the new id from the response + a Done commit for firmware
        // that lists the parent only once the install commits.
        // Pass the display name with LITERAL spaces (no pre-encoding) -- the HTTP layer
        // encodes the path, exactly like the createchild call does with "Basic Rule-1.0".
        // Pre-encoding to %20 here makes the client double-encode it to %2520, which the
        // hub decodes to the literal "%20" -> no app matches -> a 34-byte stub, no create.
        def sysAppPath = "/installedapp/sysApp/" + parentTypeName
        mcpLog("info", "rm-native", "'${parentTypeName}' parent not installed -- bootstrapping via GET ${sysAppPath}")
        def created = null
        try {
            created = hubInternalGetRaw(sysAppPath)
            // Front-load the most diagnostic bits (body length + context around
            // `appId`) -- downstream surfaces truncate long error strings.
            def _body = created?.data?.toString() ?: ""
            def _ai = _body.indexOf("appId")
            def _ctx = _ai >= 0 ? _body.substring(_ai, Math.min(_ai + 36, _body.length())).replaceAll(/\s+/, ' ') : "noAppId"
            bootstrapDiag = "dl=${_body.length()} ctx='${_ctx}' st=${created?.status} loc=${created?.location}"
        } catch (Exception e) {
            bootstrapDiag = "sysApp threw: ${e.message}"
            mcpLog("warn", "rm-native", "sysApp bootstrap GET for '${parentTypeName}' threw (continuing to re-discover): ${e.message}")
        }
        // Primary: the GET creates the parent server-side -- re-discover it by type.
        parentNode = findParentNode()
        bootstrapDiag += " rediscovered=${parentNode?.id}"
        if (parentNode?.id == null && created != null) {
            // /hub2/appsList can lag right after creation, so extract the new id from the
            // response and use it DIRECTLY. 302: id is in the absolute Location. 200 (the
            // client followed the redirect to the configure page): the classic appUI page
            // injects `appId = <id>` (verified live).
            def newId = null
            def firstPage = null
            if (created.location) {
                def lm = (created.location.toString() =~ /\/installedapp\/configure\/(\d+)(?:\/([^\/?#]+))?/)
                if (lm.find()) { newId = lm.group(1) as Integer; firstPage = lm.group(2) }
            }
            if (newId == null && created.data) {
                def bs = created.data.toString()
                def am = (bs =~ /appId\s*=\s*(\d+)/)
                if (am.find()) newId = am.group(1) as Integer
                else {
                    def cm = (bs =~ /\/installedapp\/configure(?:\/json)?\/(\d+)/)
                    if (cm.find()) newId = cm.group(1) as Integer
                }
            }
            bootstrapDiag += " newId=${newId}"
            if (newId != null) {
                // Commit via Done so the parent is fully installed, then trust the id
                // directly rather than re-reading the (possibly cache-lagging) appsList.
                // A commit that MEASURABLY did not take (app.installed reads false)
                // must not be papered over with a fabricated installed:true -- the
                // cached id would send every future createchild at an inert shell
                // with a misleading registry-blame error.
                try {
                    def commit = _commitUserAppInstall(newId, firstPage)
                    bootstrapDiag += " committed=${commit?.success}"
                    if (commit?.success == false) {
                        throw new IllegalArgumentException(
                            "[bootstrap ${bootstrapDiag}] '${parentTypeName}' parent was created (id=${newId}) but its install commit did not take (app.installed reads false). Open it once in the Hubitat UI (Apps > ${parentTypeName}, press Done) and retry.")
                    }
                } catch (IllegalArgumentException iae) {
                    throw iae
                } catch (Exception ce) {
                    // Transient commit failure: state unknown -- proceed (createchild may
                    // still work) but flag it so the id is NOT cached below.
                    bootstrapDiag += " commitThrew=${ce.message}"
                    commitUnverified = true
                }
                parentNode = [id: newId, installed: true]
            }
        } else if (parentNode?.id != null && parentNode.installed != true) {
            // Surfaced but PENDING (installed != true) -- commit via Done so it's usable.
            try {
                def commit = _commitUserAppInstall(parentNode.id.toString().toInteger(), null)
                bootstrapDiag += " committed=${commit?.success}"
                if (commit?.success == false) {
                    throw new IllegalArgumentException(
                        "[bootstrap ${bootstrapDiag}] '${parentTypeName}' parent (id=${parentNode.id}) is install-pending and its install commit did not take (app.installed reads false). Open it once in the Hubitat UI (Apps > ${parentTypeName}, press Done) and retry.")
                }
            } catch (IllegalArgumentException iae) {
                throw iae
            } catch (Exception ce) {
                bootstrapDiag += " commitThrew=${ce.message}"
                commitUnverified = true
            }
            parentNode = findParentNode()
        }
        // The diag evidence (st/loc/newId/committed) previously evaporated on
        // every non-throw path -- keep a record of what the bootstrap did.
        mcpLog(commitUnverified ? "warn" : "info", "rm-native", "sysApp bootstrap for '${parentTypeName}': ${bootstrapDiag}")
    }

    if (parentNode?.id == null) {
        throw new IllegalArgumentException(
            "[bootstrap ${bootstrapDiag}] '${parentTypeName}' parent not surfaced by /installedapp/sysApp (appType=${appType}); install via Apps > Add Built-In App.")
    }
    def id = parentNode.id.toString().toInteger()
    if (commitUnverified) {
        // Don't poison the permanent cache with an id whose install commit is
        // unconfirmed -- the next call re-discovers (cheap) and re-verifies.
        mcpLog("warn", "rm-native", "Parent ${parentTypeName} id ${id}: install commit unverified (commit call threw) -- id NOT cached; the next create re-discovers")
    } else {
        ids[appType] = id
        atomicState.parentAppIds = ids
    }
    mcpLog("info", "rm-native", "Discovered ${parentTypeName} parent app id: ${id} (appType=${appType})")
    return id
}

/**
 * Hit /installedapp/createchild/<ns>/<app>/parent/<pid> via a raw GET that
 * preserves the 302 Location header. Returns the new child app id as Integer.
 *
 * The UI's "Create New Rule" is a plain anchor — no CSRF, no prior page
 * fetch needed. Tested live on firmware 2.4.4.135 and 2.5.0.123.
 */
private Integer _rmCreateChildApp(Integer parentAppId, String namespace = "hubitat", String appName = "Rule-5.1") {
    def path = "/installedapp/createchild/${namespace}/${appName}/parent/${parentAppId}"
    def resp = hubInternalGetRaw(path)
    if (resp == null) {
        throw new IllegalArgumentException("createchild returned null response for ${path}")
    }
    def loc = resp.location
    if (!loc) {
        throw new IllegalArgumentException(
            "createchild response had no Location header (status=${resp.status}). Body: ${resp.data?.take(200)}")
    }
    // Expected shape: /installedapp/configure/<newId>  (may be absolute URL)
    def m = loc =~ /\/installedapp\/configure\/(\d+)/
    if (!m.find()) {
        throw new IllegalArgumentException("Could not extract new app id from Location: ${loc}")
    }
    def newId = m.group(1).toInteger()
    mcpLog("info", "rm-native", "Created ${namespace}:${appName} under parent ${parentAppId} → new app id ${newId}")
    return newId
}

/**
 * Click a button on an app's config page via /installedapp/btn. Used for
 * RM's page-transition buttons: updateRule, pausRule, runAction, editCond,
 * editAct, hasAll, etc. Stable across RM 5.0 and 5.1.
 *
 * Body format captured live from the Hubitat web UI's hasAll click on
 * firmware 2.5.0.123 (network panel):
 *   id=<appId>
 *   name=<buttonName>
 *   settings[<buttonName>]=clicked       <-- key bracket-form (NOT bare `<buttonName>=clicked`)
 *   <buttonName>.type=button
 *   formAction=update                    <-- form-context: load-bearing for wizard-Done buttons
 *   version=<app version>                <-- ditto
 *   currentPage=<pageName>               <-- ditto
 *   pageBreadcrumbs=["mainPage", ...]    <-- navigation history
 *   stateAttribute=<value>               <-- only when caller passes one (e.g. moreCond, editCond)
 *
 * Earlier versions of this helper sent bare `<buttonName>=clicked`
 * without the `settings[]` wrapper AND omitted formAction/version/
 * currentPage/pageBreadcrumbs, which the hub's button handler accepted
 * with HTTP 200 but did NOT fully process for wizard-Done buttons —
 * manifested as the "first hasAll click leaves editor scaffold open"
 * bug that required a second click to commit. With the full form-
 * context body, a single hasAll click commits the trigger cleanly:
 * editor closes, no residual isCondTrig prompt, no phantom trigger.
 *
 * `pageName` is optional. When omitted, formAction/version/currentPage
 * are also omitted (the minimal POST works fine for top-level buttons
 * like updateRule, pausRule, stopRule that operate on the main page).
 * Pass pageName for sub-page wizard buttons (hasAll on selectTriggers,
 * actionDone on selectActions, etc.) so the form-context fields fire.
 */
// Non-private so test specs can override via script.metaClass — internal
// Groovy-script dispatch to private methods bypasses the per-instance
// metaClass override.
def _rmClickAppButton(Integer appId, String buttonName, String stateAttribute = null, String pageName = null, Map cache = null) {
    def body = [
        id: appId.toString(),
        name: buttonName,
        ("settings[${buttonName}]".toString()): "clicked",
        ("${buttonName}.type".toString()): "button"
    ]
    if (stateAttribute) body.stateAttribute = stateAttribute
    if (pageName) {
        body.formAction = "update"
        body.currentPage = pageName
        // Breadcrumb depth is correct for this path: _rmClickAppButton only
        // clicks buttons on pages that are DIRECT children of mainPage
        // (hasAll on selectTriggers, actionDone on selectActions), so a single
        // mainPage ancestor is right. RM DOES nest deeper sub-pages today
        // (Periodic Schedule, Cron String, etc.) -- but those commit through
        // _rmSubmitSubPageDone, which emits the correct '["mainPage",parent]'
        // depth (live-captured fw 2.5.0.123). The only thing that would break
        // this hardcode is a future button-click directly on a depth-2 page;
        // verify against a network capture if a new wizard level rejects clicks.
        body.pageBreadcrumbs = '["mainPage"]'
        // The hub uses `version` to detect concurrent edits. Fetch the
        // current value so we replay the exact one the UI would send.
        try {
            def cfg = _rmFetchConfigJson(appId, pageName, cache)
            def v = cfg?.app?.version
            if (v != null) body.version = v.toString()
        } catch (Exception verExc) {
            mcpLog("debug", "rm-native", "_rmClickAppButton: version fetch on ${pageName} failed for app ${appId} (${verExc.message}) -- sending POST without version field")
            // version fetch failure is recoverable — the button click
            // works without it for top-level buttons; for wizard-Done
            // buttons the hub may need a second click. Don't fail the
            // whole call here.
        }
    }
    def resp = hubInternalPostForm("/installedapp/btn", body)
    // A button click (hasAll / updateRule / doneST / cancelCapab / editCond ...) re-renders the
    // page and bumps app.version, so every cached page for this app is now stale.
    _rmCacheInvalidate(cache, appId)
    if (resp?.status != null && resp.status >= 400) {
        throw new IllegalArgumentException("Button click '${buttonName}' on app ${appId} failed: status=${resp.status}")
    }
    return resp
}


/**
 * Strip dynamic substrings from a configPage's serialized sections so the
 * render-shift hash compares only the structural content. RM 5.1 renders
 * "Last activity: <timestamp>", "fired N times", and ISO timestamps in
 * mainPage / selectTriggers paragraphs that change on every fetch
 * regardless of whether a write landed — without sanitization, those
 * dynamic values would cause renderShifted=true on EVERY write and the
 * silent-rejection detector would never fire (false negatives).
 *
 * Patterns stripped:
 *   - ISO 8601 / "yyyy-MM-dd HH:mm:ss[.SSS]" timestamps
 *   - "fired <N> time(s)" counters
 *   - "last activity:", "last run:", "last fired:" prefixes + their value
 *   - Bare epoch-ms numbers (13+ digits)
 */
private String _rmSanitizeRenderForHash(Object sections) {
    def raw = sections?.toString() ?: ""
    if (!raw) return ""
    return raw
        .replaceAll(/\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d{1,3})?/, "<TS>")
        .replaceAll(/fired\s+\d+\s+time/, "fired <N> time")
        .replaceAll(/(?i)last\s+(activity|run|fired)\s*:?\s*[^,\]]+/, "last \$1: <TS>")
        .replaceAll(/\b\d{13,}\b/, "<EPOCH>")
}

/**
 * Single-setting write to a sub-page that no-ops if the key isn't in the
 * current schema. Used by _rmAddTrigger to walk the wizard's incremental
 * schema progression without surfacing settingsSkipped warnings on every
 * field that hasn't appeared yet.
 *
 * Post-write verification: after the POST, the page is re-fetched and
 * the new schema is compared to the pre-write schema. The write counts as
 * "persisted" if EITHER (a) the schema's keys changed (wizard advanced —
 * e.g. cond=a unlocks rCapab_<N>, or `key` was consumed and removed), OR
 * (b) the field's serialized value reflects the new value (mainPage-style
 * persistent setting). Otherwise the write is treated as silently rejected
 * and routed to `skipped` instead of `applied` — RM 5.1 returns 200 for
 * many wizard-context writes that never land (e.g. cond=a on doActPage
 * without `currentPage`/`pageBreadcrumbs` in the body) and the optimistic
 * append-on-applied bookkeeping was hiding these failures.
 *
 * The `applied` accumulator collects every key that actually landed on
 * the page so the caller can include it in the response.
 */
// Non-private so test specs can override via script.metaClass — see
// _rmClickAppButton for the same rationale.
def _rmWriteSettingOnPage(Integer appId, String pageName, String key, Object value, List applied, String typeHintOverride = null, List skipped = null, Map cache = null) {
    def config = _rmFetchConfigJson(appId, pageName, cache)
    def schema = _rmCollectInputSchema(config?.configPage)
    if (!schema?.containsKey(key)) {
        // Field not in current schema. This is normal for incremental
        // wizards (writing tCapab1 unlocks tDev1 next), so it's not
        // necessarily a bug — but caller wants visibility. Surface the
        // skip via the `skipped` list when caller provided one.
        if (skipped != null) {
            skipped << [key: key, reason: "not_in_schema", value: value, available: schema?.keySet()?.toList()?.sort()]
        }
        return
    }
    def settingsMap = [(key): value]
    def schemaForBuild = schema
    if (typeHintOverride && schema?."${key}" != null) {
        // Allow caller to override the inferred type (rare; mostly for
        // raw-settings escape hatch where the schema's declared type is
        // wrong). Clone so we don't mutate the cached schema map.
        schemaForBuild = [:] + schema
        schemaForBuild[key] = ([:] + schema[key]) << [type: typeHintOverride]
    }
    def beforeKeys = (schema.keySet() ?: []) as Set
    def beforeValueStr = schema?."${key}"?.value?.toString()
    // Full sections render-hash captures any rendered shift (paragraphs, input titles, descriptions, options sets). Wizard-consumed pickers reset their own field on advance, so before/after schema keys + field value can look identical even on success; the rendered configPage is always different.
    def beforeRenderHash = (config?.configPage?.sections?.toString() ?: "").hashCode()
    // For sub-page wizard writes (doActPage's `cond`, STPage's `cond`,
    // periodic sub-page writes) RM needs `formAction=update`,
    // `currentPage=<page>`, and `pageBreadcrumbs=["mainPage"]` in the
    // body — without those, transient wizard fields like `cond` are
    // silently rejected (verified live: writing cond=a on
    // doActPage without page context returned silentRejection=true,
    // schema unchanged; same write WITH page context committed).
    // Include the context whenever pageName is non-null and isn't the
    // default mainPage.
    def writeResp = null
    if (pageName && pageName != "mainPage") {
        def body = _rmBuildSettingsBody(appId, settingsMap, schemaForBuild)
        body.formAction = "update"
        body.currentPage = pageName
        body.pageBreadcrumbs = '["mainPage"]'
        if (config?.app?.version != null) body.version = config.app.version.toString()
        writeResp = _rmPostSettings(appId, body, cache)
    } else {
        _rmUpdateAppSettings(appId, settingsMap, schemaForBuild, cache)
    }
    // Verify the write took. On the sub-page path the POST response IS the re-rendered page model
    // (consume it -- no verify-GET; appUI.js consumes the same body), and it has the same {app,
    // configPage, settings} shape a verify-GET would. The mainPage path goes through
    // _rmUpdateAppSettings (which may re-POST for sticky multiple-flags), so its post-state is read
    // fresh. The write above already invalidated the cache; consuming re-stores the post-write page.
    def afterCfg = _rmPagePostResponse(writeResp)
    def verifyFetchErr = null
    if (afterCfg != null) {
        _rmCacheStore(cache, appId, pageName, afterCfg)
    } else {
        try { afterCfg = _rmFetchConfigJson(appId, pageName, cache) }
        catch (Exception fetchExc) {
            verifyFetchErr = fetchExc.message
            mcpLog("warn", "rm-native", "_rmWriteSettingOnPage: post-write fetch on ${pageName} failed for app ${appId} key=${key} (${fetchExc.message}) -- write status is unverified")
        }
    }
    if (afterCfg == null) {
        // Verification fetch failed — we cannot confirm persistence. Surface
        // as a distinct skipped reason rather than falsely declaring applied
        // (the comparison-against-empty-schema would otherwise produce
        // schemaShifted=true, route to applied, hiding the unverified state).
        if (skipped != null) {
            skipped << [key: key, reason: "verification_fetch_failed", value: value, verifyError: verifyFetchErr]
        } else {
            applied << key  // legacy callers — preserve old optimistic behaviour
        }
        return
    }
    def afterSchema = _rmCollectInputSchema(afterCfg?.configPage)
    def afterKeys = (afterSchema?.keySet() ?: []) as Set
    def afterValueStr = afterSchema?."${key}"?.value?.toString()
    def afterRenderHash = _rmSanitizeRenderForHash(afterCfg?.configPage?.sections).hashCode()
    // Stringify list values deterministically so idempotent List writes
    // (already-set multi-enum re-applied) match cleanly via valueLanded.
    def newValueStr
    if (value instanceof List) {
        newValueStr = ((value as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else {
        newValueStr = value?.toString()
    }
    def afterValueNorm = afterValueStr
    if (afterValueStr && value instanceof List && afterValueStr.contains(",")) {
        afterValueNorm = afterValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
    }
    def schemaShifted = (beforeKeys != afterKeys) || (beforeValueStr != afterValueStr)
    def valueLanded = (newValueStr != null) && (afterValueNorm == newValueStr)
    // Wizard-consumed: many sub-page enum pickers reset their field on advance
    // (e.g. doActPage cond, STPage cond/oper). Before/after schema look
    // identical but RM's rendered paragraph text shifts to reflect the new
    // wizard state. The render hash catches that case.
    def renderShifted = (beforeRenderHash != afterRenderHash)
    // Wizard-consumed / submitOnChange field families (4th detection mechanism).
    //
    // WHY this case exists: certain RM 5.1 fields are consumed by the wizard
    // immediately on submitOnChange -- the wizard advances its internal state
    // and the field disappears from the configPage schema (it is no longer
    // rendered as a UI input), EVEN THOUGH the value persists correctly in
    // the app's settings map. The three schema-based mechanisms above all
    // check configPage (input descriptors), so they miss this pattern:
    //   schemaShifted: keys may be unchanged (wizard is at same step)
    //   valueLanded:   key absent from afterSchema -> afterValueStr is null
    //   renderShifted: paragraphs may not shift for this specific write
    //
    // WHICH field families this catches (verified live, zero-context
    // validation 2026-05-02):
    //   RelrDev_N  -- Custom Attribute condition's comparator (=, !=, etc.),
    //                 written inside addRequiredExpression's STPage wizard walk.
    //                 Field disappears from schema after write but persists in
    //                 settings as a plain enum string.
    //   useLastDev.N -- runCommand's "use last device" checkbox written during
    //                   addAction. Disappears from doActPage schema after write
    //                   but persists in settings as "true"/"false".
    //   time1      -- Certain Time trigger's time/mode label written during
    //                 addTrigger. Disappears from selectTriggers schema after
    //                 the wizard advances past it, but persists in settings.
    //   Any other submitOnChange-but-not-re-rendered field in RM 5.1.
    //
    // HOW it works: the /installedapp/configure/json response includes BOTH
    // configPage (current rendered inputs) AND settings (all persisted app
    // settings). When a field disappears from configPage but the value is in
    // settings, the write succeeded -- route to applied, not skipped.
    //
    // The comparison uses the same string-normalization as valueLanded (above)
    // so List values compare correctly against comma-joined settings strings.
    def settingsValue = afterCfg?.settings?."${key}"
    // Normalize the settings value to a comparable string using the same
    // strategy as valueLanded above. Settings entries can be:
    //   - a String (simple scalar, e.g. "=" for RelrDev_N)
    //   - a comma-joined String (e.g. "3,5" for multi-select written as CSV)
    //   - a List (e.g. ["3","5"] when Hubitat stores multi-select as a JSON array)
    // Flatten List entries to a sorted comma-joined string so they compare
    // cleanly against newValueStr (which is also sorted-comma-joined for Lists).
    def settingsValueNorm
    if (settingsValue instanceof List) {
        settingsValueNorm = ((settingsValue as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else if (settingsValue != null) {
        def settingsValueStr = settingsValue.toString()
        if (value instanceof List && settingsValueStr.contains(",")) {
            settingsValueNorm = settingsValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
        } else {
            settingsValueNorm = settingsValueStr
        }
    }
    def settingsLanded = (newValueStr != null) && (settingsValueNorm != null) && (settingsValueNorm == newValueStr)
    if (schemaShifted || valueLanded || renderShifted || settingsLanded) {
        applied << key
    } else if (skipped != null) {
        skipped << [key: key, reason: "silent_rejection", value: value, schemaUnchanged: true, available: afterKeys.toList().sort()]
    } else {
        applied << key  // legacy callers without a skipped list -- preserve old optimistic behavior
    }
}

/**
 * Fetch /installedapp/configure/json/<appId>[/<pageName>] and parse.
 * Returns the raw map (app, configPage, settings, childApps, ...).
 * Callers (hub_get_app_config, hub_set_rule) use this to discover the input
 * schema (names + types + multiple flags) before issuing a write.
 */
private Map _rmFetchConfigJson(Integer appId, String pageName = null, Map cache = null) {
    // Request-scoped page-schema cache (threaded by the RM condition builders; null for every
    // other caller -> unchanged behaviour). Keyed strictly on (appId, pageName); only a real
    // page is cached -- a root read (pageName == null) carries the volatile app.version token
    // and MUST stay live. A HIT returns exactly what a live fetch would, because every
    // wizard-page WRITE clears cache[appId] (see _rmCacheInvalidate / _rmCacheStore), so a
    // cached page is provably current.
    if (cache != null && pageName != null && cache[appId] instanceof Map && cache[appId].containsKey(pageName)) {
        return cache[appId][pageName]
    }
    def path = "/installedapp/configure/json/${appId}"
    if (pageName) path += "/${pageName}"
    def responseText = hubInternalGet(path)
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from ${path} -- app ${appId} may not exist")
    }
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception pe) {
        throw new IllegalArgumentException("Failed to parse ${path} response: ${pe.message}", pe)
    }
    if (!(parsed instanceof Map) || !parsed.app) {
        throw new IllegalArgumentException("Unexpected response shape from ${path}: missing app object")
    }
    if (cache != null && pageName != null) {
        if (!(cache[appId] instanceof Map)) cache[appId] = [:]
        cache[appId][pageName] = parsed
    }
    return parsed
}

// Drop ALL cached pages for an app. Every wizard-page WRITE (any POST to
// /installedapp/update/json or /installedapp/btn) calls this: cross-page render coupling
// means one page's write can change how sibling pages render, AND the app.version token
// shifts, so per-page invalidation would be unsafe. No-op when cache is null.
private void _rmCacheInvalidate(Map cache, Integer appId) {
    if (cache != null) cache[appId] = [:]
}

// Invalidate the app, then store a freshly-rendered page model -- used after a write whose
// POST response IS the re-rendered page (the hub returns the configPage inline, exactly as
// the browser consumes it; see _rmPagePostResponse), so the next read is a HIT with no
// verify-GET. pageModel must be a parsed {app, configPage, ...} Map; if null/invalid the
// app is just invalidated (next read re-fetches live).
private void _rmCacheStore(Map cache, Integer appId, String pageName, Map pageModel) {
    if (cache == null) return
    cache[appId] = [:]
    if (pageName != null && pageModel != null && pageModel.configPage != null) cache[appId][pageName] = pageModel
}

// Parse the page model the hub returns INLINE from an /installedapp/update/json POST. The
// classic dynamicPage submit returns the re-rendered {app, configPage, settings, ...} in the
// POST response body (appUI.js jsonSubmit consumes data.configPage directly, with NO
// follow-up GET). hubInternalPostForm returns a struct {status, data:<body text>}. Returns
// the parsed Map, or null when the body is empty / non-JSON / lacks app+configPage -- callers
// fall back to a verify-fetch then (defensive: worst case == today's separate-GET behaviour).
private Map _rmPagePostResponse(postResp) {
    try {
        def data = (postResp instanceof Map) ? postResp.data : postResp
        if (!data) return null
        def parsed = (data instanceof String) ? new groovy.json.JsonSlurper().parseText(data) : data
        if (parsed instanceof Map && parsed.app && parsed.configPage) return parsed
    } catch (Exception ignore) { }
    return null
}

/**
 * Fetch /installedapp/statusJson/<appId> — returns runtime state including
 * appSettings[] with marshal flags, eventSubscriptions[], scheduledJobs[],
 * appState[]. This is the ground-truth post-write verification surface.
 */
private Map _rmFetchStatusJson(Integer appId) {
    def responseText = hubInternalGet("/installedapp/statusJson/${appId}")
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from /installedapp/statusJson/${appId}")
    }
    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
    if (!(parsed instanceof Map)) {
        throw new IllegalArgumentException("Unexpected statusJson shape for app ${appId}")
    }
    return parsed
}

/**
 * Read a rule's compiled state from its builder-JSON endpoint — the PREFERRED
 * health source across EVERY rule engine (issue #254 + the VRB follow-up).
 * Returns a normalized map or null when appId is not a recognized rule shape:
 *
 *   - classic Rule Machine -> [ruleFormat:"rm", broken:<bool>, predicate, capabsfalse]
 *     from GET /app/ruleBuilderJson (the real `broken` boolean + predicate/condition
 *     structure, instead of scraping rendered HTML).
 *   - graph Visual Rule (VRB 2.0) -> [ruleFormat:"vrb-graph", broken:<validationErrors
 *     non-empty>, validationErrors] from GET /app/ruleBuilder20Json. VRB rules ARE
 *     rules — their validationErrors are the engine-native equivalent of RM's broken.
 *   - classic Visual Rule -> [ruleFormat:"vrb-classic", broken:null] (the when/then/else
 *     shape carries no error field, so there is no structured boolean to report).
 *
 * SHAPE-CHECK, never status-check: /app/ruleBuilderJson serializes the raw state of
 * ANY installed app and answers HTTP 200 regardless (a nonexistent id returns {}, a
 * non-rule app returns its own state map), and /app/ruleBuilder20Json answers
 * {success:false} for any non-graph id. Read ruleBuilderJson first so the common RM
 * case is a single GET; only fall through to the graph endpoint when the shape is
 * unrecognized. (Endpoint inventory: resources/hub2-source/README.md.)
 */
private Map _ruleCompiledState(Integer appId) {
    // readError captures a THROWN read (auth 401/403, hub-down 5xx, timeout) so the caller can
    // distinguish "this read failed" from "this is a clean non-rule shape". Without it, a
    // source='ruleBuilderJson' call (which has no HTML fallback) would mis-report a 403 / hub-down
    // as "nonexistent id / non-rule / old firmware" and misdirect recovery (silent-failure review).
    def text
    def readError = null
    try { text = hubInternalGet("/app/ruleBuilderJson/${appId}") } catch (Exception e) { text = null; readError = e.message }
    if (text) {
        def parsed = null
        // A non-JSON 200 (a login/redirect page, a proxy error body) is itself a read failure, not
        // a clean "not a rule" — capture it so a source='ruleBuilderJson' caller isn't told the
        // rule is missing when auth/connectivity returned junk (codex review).
        try { parsed = new groovy.json.JsonSlurper().parseText(text) }
        catch (Exception e) { parsed = null; if (readError == null) readError = "ruleBuilderJson response was not JSON: ${e.message}" }
        if (parsed instanceof Map && !parsed.isEmpty()) {
            // Only the combined whenNodes+thenNodes shape identifies a classic Visual Rule (matches
            // _vrbFetchClassic and the endpoint inventory); a lone key on some other app's state
            // must NOT be misread as a healthy VRB rule (codex review).
            if (parsed.containsKey("whenNodes") && parsed.containsKey("thenNodes")) {
                return [ruleFormat: "vrb-classic", broken: null, validationErrors: [], endpoint: "ruleBuilderJson"]
            }
            if (parsed.containsKey("broken")) {
                def pred = (parsed.containsKey("hasPredicate") || parsed.containsKey("predCapabs")) ?
                    [hasPredicate: parsed.hasPredicate == true, predCapabs: parsed.predCapabs ?: []] : null
                return [ruleFormat: "rm", broken: parsed.broken == true, validationErrors: [],
                        predicate: pred,
                        capabsfalse: (parsed.capabsfalse instanceof Map ? parsed.capabsfalse : null),
                        endpoint: "ruleBuilderJson"]
            }
        }
    }
    // Not a classic RM / classic VRB shape — try the graph Visual Rule endpoint, whose
    // validationErrors are that engine's health signal. Read it directly (rather than via
    // _vrbFetchGraph, which maps a non-JSON 200 to null without distinguishing it) so a bad-200
    // login/proxy body on THIS GET is also captured as readError, not swallowed as a clean
    // negative. {success:false} is the genuine "not a graph rule" answer and stays a clean null.
    def graphText
    try { graphText = hubInternalGet("/app/ruleBuilder20Json/${appId}") } catch (Exception e) { graphText = null; if (readError == null) readError = e.message }
    if (graphText) {
        def gp = null
        try { gp = new groovy.json.JsonSlurper().parseText(graphText) }
        catch (Exception e) { gp = null; if (readError == null) readError = "ruleBuilder20Json response was not JSON: ${e.message}" }
        if (gp instanceof Map && gp.success != false) {
            def ve = (gp.validationErrors ?: []).collect { it?.toString() }
            return [ruleFormat: "vrb-graph", broken: !ve.isEmpty(), validationErrors: ve, endpoint: "ruleBuilder20Json"]
        }
    }
    // No recognized rule shape. Distinguish a clean negative (null) from a read failure so the
    // caller doesn't assert non-existence over a transient/auth error.
    return readError != null ? [ruleFormat: null, readError: readError] : null
}

/**
 * Walk a sequence of [idx, actType, actSubType] entries (in numerical
 * order) tracking IF / Repeat block depth via a stack. Returns the list
 * of structural issue strings (empty list = balanced).
 *
 * Used by _rmCheckRuleHealth for post-mutation detection AND by the
 * pre-flight refusal paths in _rmDeleteAction / _rmAddAction /
 * _applyNativeAppEdit's replaceActions handler — they all build a
 * projected sequence (current minus removals plus additions) and compare
 * projected against current with `projected - current` set diff. Any
 * NEW issue not present in current means the mutation introduces damage
 * and is refused. (Size-only comparison would allow damage-shuffling on
 * already-broken rules — e.g. adding an END-IF to a rule with an open
 * Repeat keeps the issue count flat but swaps "Repeat never closed" for
 * "END-IF closes a Repeat block — mismatched closer".)
 *
 * Asymmetric refusal at the call sites: openers (ifThen / repeat /
 * repeatWhile) added alone are allowed (normal multi-step build state).
 * Branch keywords (elseIf / else) and bare closers (endIf / stopRepeat)
 * are refused if they would render orphaned, because they have no valid
 * follow-up step the caller would naturally do next.
 *
 * Partial-commit handling: an entry with `actType` set but `actSubType`
 * null is treated as a leaf (skipped from the walk), and a `partial:
 * true` flag on the entry is also accepted as a hint that the writer
 * knew the entry is incomplete. The intent is to keep the walker silent
 * on the actType-only halfway state that the #172 false-fail race can
 * leave behind, rather than treating it as a leaf and silently masking
 * the imbalance.
 *
 * Groovy's List.pop() removes from the FRONT of a List; the walker uses
 * `<<` for append and `removeAt(size-1)` for tail-pop to preserve the
 * conventional stack semantics.
 */
private List _rmStructuralIssuesFromSequence(List<Map> sequence) {
    def issues = []
    def stack = []
    sequence.each { entry ->
        def idx = entry?.idx
        def aType = entry?.actType?.toString()
        def sType = entry?.actSubType?.toString()
        if (entry?.partial == true || (aType in ["condActs", "repeatActs"] && (sType == null || sType == ""))) {
            issues << ("action ${idx} is in a partial-commit state (actType set, actSubType missing) — likely from an interrupted wizard write where the actType landed but the actSubType did not. The walker treats this as an opaque block boundary; restore from a recent backup or finish the wizard via hub_set_rule(walkStep=...).".toString())
            return
        }
        if (aType == "condActs") {
            if (sType == "getIfThen") {
                stack << [kind: "if", openIdx: idx]
            } else if (sType == "getElseIf" || sType == "getElse") {
                if (stack.isEmpty() || stack[-1].kind != "if") {
                    issues << ("action ${idx} (${sType == 'getElse' ? 'ELSE' : 'ELSE-IF'}) is outside any IF block — orphaned branch keyword".toString())
                }
            } else if (sType == "getEndIf") {
                if (stack.isEmpty()) {
                    issues << ("action ${idx} (END-IF) has no matching IF — orphaned closer (too many END-IFs)".toString())
                } else if (stack[-1].kind != "if") {
                    def open = stack[-1]
                    issues << ("action ${idx} (END-IF) closes a Repeat block opened at action ${open.openIdx} — mismatched block closer".toString())
                    stack.removeAt(stack.size() - 1)
                } else {
                    stack.removeAt(stack.size() - 1)
                }
            }
        } else if (aType == "repeatActs") {
            if (sType == "getRepeat" || sType == "getWhile") {
                stack << [kind: "repeat", openIdx: idx]
            } else if (sType == "getStopRepeat") {
                if (stack.isEmpty()) {
                    issues << ("action ${idx} (End Repeat) has no matching Repeat — orphaned closer".toString())
                } else if (stack[-1].kind != "repeat") {
                    def open = stack[-1]
                    issues << ("action ${idx} (End Repeat) closes an IF block opened at action ${open.openIdx} — mismatched block closer".toString())
                    stack.removeAt(stack.size() - 1)
                } else {
                    stack.removeAt(stack.size() - 1)
                }
            }
        }
    }
    stack.each { open ->
        def label = open.kind == "if" ? "IF" : "Repeat"
        def closer = open.kind == "if" ? "END-IF" : "End-Repeat"
        issues << ("action ${open.openIdx} (${label}) opened a block that was never closed — rule is missing an ${closer}".toString())
    }
    issues
}

/**
 * Build the structural-balance sequence from a rule's live appSettings
 * map (keyed by name). Collects actType.<N>/actSubType.<N> pairs in
 * numerical order and marks any actType-without-actSubType entry with
 * `partial: true` so the walker can surface it as a #172-class
 * half-commit rather than silently treating it as a leaf. Optional
 * excludeIndices (used by removeAction pre-flight) skip listed action
 * indices so the walker sees the post-deletion state.
 */
private List _rmStructuralSequenceFromSettings(Map settingsByName, Set excludeIndices = ([] as Set)) {
    def indices = [] as TreeSet
    settingsByName.keySet().each { name ->
        def m = name?.toString() =~ /^actType\.(\d+)$/
        if (m.matches()) indices << ((m[0] as List)[1] as Integer)
    }
    def sequence = []
    indices.each { idx ->
        if (excludeIndices.contains(idx)) return
        def aType = settingsByName["actType.${idx}".toString()]?.value?.toString()
        def sType = settingsByName["actSubType.${idx}".toString()]?.value?.toString()
        def entry = [idx: idx, actType: aType, actSubType: sType]
        if (aType in ["condActs", "repeatActs"] && (sType == null || sType == "")) {
            entry.partial = true
        }
        sequence << entry
    }
    sequence
}

/**
 * Fetch the rule's appSettings and return it keyed by setting name.
 * Shared by every site that needs to read the rule's current state
 * (the structural-balance walker, the multiple-flag-poison check, and
 * the pre-flight refusal paths in _rmDeleteAction / _rmAddAction). One
 * statusJson GET per call site instead of three back-to-back.
 */
private Map _rmFetchSettingsByName(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
}

/**
 * Classify a classic app from its configPage app-type so the health report names
 * what it inspected (ruleFormat) instead of leaving it null. Button Controller and
 * Basic Rule use the same classic configPage protocol as Rule Machine, so the
 * generic health detections (configPage.error, multiple-flag poison) apply to them.
 * Uses app.appType.name (the stable type) — NOT app.label, which becomes the user's
 * chosen name. Unknown classic apps fall to "classic-app" (honest: inspected via
 * configPage, no compiled broken boolean).
 */
private String _classicAppFormat(Map cfg) {
    def t = cfg?.app?.appType?.name?.toString()?.toLowerCase() ?: ""
    if (t.startsWith("rule-") || t.contains("rule machine")) return "rm"
    if (t.contains("basic rule")) return "basic-rule"
    if (t.contains("button controller")) return "button-controller"
    return "classic-app"
}

/**
 * Inspect a rule's current state and return a structured health report —
 * works across EVERY rule engine (issue #254 + VRB follow-up). Surfaces
 * problems an LLM caller needs to see and act on without re-investigating
 * via curl.
 *
 *   PREFERRED — the rule's compiled state via _ruleCompiledState(): the
 *     classic RM `broken` boolean (+ predicate) from /app/ruleBuilderJson,
 *     OR a graph Visual Rule's validationErrors from /app/ruleBuilder20Json
 *     (VRB rules are rules too — their validationErrors are that engine's
 *     `broken` equivalent), OR a recognized classic Visual Rule (no boolean).
 *     `ruleFormat` in the result says which engine answered.
 *
 *   RETAINED (HTML / configure-json) — classic RM only: kept as a cross-check
 *     and the fallback when the compiled state is unavailable (older firmware
 *     or a different shape), AND because it detects classes the boolean does
 *     not: configPage.error (page render failure), the '*BROKEN*' label
 *     suffix, '**Broken Trigger/Action/Condition**' paragraph markers,
 *     multiple-flag DB poisoning (schema multiple vs statusJson marshal
 *     flag), and IF/Repeat block imbalance from actType.<N>/actSubType.<N>.
 *     Skipped for Visual Rules (they don't speak this protocol).
 *
 * `source` selects which paths run: "auto" (default — preferred verdict plus
 * the RM HTML detections + a cross-check), "ruleBuilderJson" (compiled state
 * only), or "configPage" (RM HTML only). Neither path is ever dropped.
 *
 * Result shape is backward-compatible with the pre-#254 contract (the RM
 * detection arrays are always present) plus the cross-engine fields broken /
 * source / ruleFormat / validationErrors; predicate is added only when read.
 *
 * Callers (_applyNativeAppEdit, _createNativeAppShell, toolCheckRuleHealth,
 * _rmBuildUpdateErrorResponse, toolSetVisualRule) attach this report to
 * mutation success AND error responses so an LLM sees broken state immediately.
 */
private Map _rmCheckRuleHealth(Integer appId, String source = "auto") {
    // Defensive guard: error paths (_rmBuildUpdateErrorResponse and friends) can call in with a
    // null appId if the failure happened before the id resolved. Reading rule state for a null id
    // would fire redundant HTTP calls (/app/ruleBuilderJson/null, /installedapp/configure/json/null)
    // that just throw — short-circuit to a clean unhealthy verdict instead. (Gemini review, PR #276.)
    if (appId == null) {
        return [ok: false, broken: null, source: "none", ruleFormat: null,
                label: null, configPageError: null, brokenMarkers: [], brokenMarkerCounts: [:],
                multipleFlagPoison: [], structuralIssues: [], validationErrors: [],
                issues: ["health check failed: appId is null"]]
    }
    def issues = []
    def label = null
    def configPageError = null
    def brokenMarkers = []
    def multipleFlagPoison = []
    def structuralIssues = []
    def validationErrors = []      // VRB graph-rule validation problems (its `broken` equivalent)
    Boolean broken = null          // authoritative boolean: RM compiled state, or VRB validationErrors non-empty
    def predicate = null           // compact {hasPredicate, predCapabs} from ruleBuilderJson (RM)
    String ruleFormat = null       // rm | vrb-graph | vrb-classic | basic-rule | button-controller | classic-app — what was inspected
    def sourcesUsed = []
    def compiledReadError = null   // a thrown/bad-200 compiled-state read, surfaced if the HTML path also fails
    boolean useRuleBuilder = (source != "configPage")
    boolean useConfigPage = (source != "ruleBuilderJson")

    // PREFERRED structured source — the compiled-state verdict for ANY rule engine
    // (classic RM `broken` boolean, graph Visual Rule validationErrors, classic Visual Rule).
    if (useRuleBuilder) {
        def cs = _ruleCompiledState(appId)
        if (cs != null && cs.ruleFormat == null && cs.readError) compiledReadError = cs.readError
        if (cs != null && cs.ruleFormat != null) {
            ruleFormat = cs.ruleFormat
            sourcesUsed << cs.endpoint
            broken = cs.broken
            if (cs.predicate != null) predicate = cs.predicate
            if (cs.validationErrors) validationErrors = cs.validationErrors
            if (ruleFormat == "rm" && broken == true) {
                // capabsfalse renders the live false-condition text (with current
                // values) — it points at what is wrong.
                def detail = (cs.capabsfalse instanceof Map && !cs.capabsfalse.isEmpty()) ?
                    " False conditions: ${cs.capabsfalse.values().join('; ')}".toString() : ""
                issues << "ruleBuilderJson reports broken:true (compiled-state boolean — authoritative).${detail}".toString()
            } else if (ruleFormat == "vrb-graph" && !validationErrors.isEmpty()) {
                issues << "Visual Rule (graph) has validation errors: ${validationErrors.join('; ')}".toString()
            }
        } else if (source == "ruleBuilderJson") {
            // Distinguish a genuine read failure (auth/connectivity) from a clean negative so we
            // don't misdirect recovery toward "the rule doesn't exist / wrong firmware".
            if (cs?.readError) {
                issues << "source='ruleBuilderJson' requested but the compiled-state read FAILED for app ${appId}: ${cs.readError}. This is likely a hub read error (Hub Security auth or connectivity), not a missing rule — retry with source='auto' for the HTML fallback.".toString()
            } else {
                issues << "source='ruleBuilderJson' requested but the compiled-state source is unavailable for app ${appId} (empty {} for a nonexistent id, a non-rule app, or older firmware). Retry with source='auto' to use the HTML fallback.".toString()
            }
        }
    }

    // RETAINED HTML / configure-json path — RM-specific detections (label *BROKEN*, render
    // markers, multiple-flag poison, structural imbalance) + the cross-check + the fallback
    // for the preferred source. Visual Rules don't speak this protocol (no *BROKEN* label, no
    // actType settings), so their validationErrors above ARE the health signal — skip the RM
    // scans for a known VRB rule.
    boolean runHtml = useConfigPage && ruleFormat != "vrb-classic" && ruleFormat != "vrb-graph"
    if (runHtml) {
        try {
            def cfg = _rmFetchConfigJson(appId)
            sourcesUsed << "configPage"
            label = cfg?.app?.label?.toString()
            // Recognize the classic app type so the report names what it inspected instead of
            // leaving ruleFormat null. Button Controller / Basic Rule (and other classic apps)
            // share RM's configPage protocol, so the generic detections below (configPage.error,
            // multiple-flag poison) apply to them; only RM has the compiled `broken` boolean and
            // the actType structural model, so broken stays null for the others.
            if (ruleFormat == null) ruleFormat = _classicAppFormat(cfg)
            configPageError = cfg?.configPage?.error
            if (configPageError) {
                issues << "configPage.error: ${configPageError}".toString()
            }
            if (label?.contains("*BROKEN*")) {
                issues << "label contains *BROKEN* marker — rule has at least one malformed trigger or action".toString()
            }
            // Scan for the broken-state strings RM emits in its rendered output. Read BOTH
            // formats the hub serves: the body-element format the live UI renderer uses
            // (sect.body[].description where element is "paragraph"/"href") AND the
            // paragraphs-array format. Live on fw 2.5.0.143 a deleted-trigger rule's direct
            // /configure/json puts '**Broken Trigger**' in the body-element format, which the
            // old paragraphs-only scan missed — the bug this dual-read fixes (mirrors the
            // dual-read in _rmAddTrigger's not-baked check).
            def paragraphTexts = (cfg?.configPage?.sections ?: []).collectMany { sect ->
                def fromBody = (sect?.body ?: [])
                    .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
                    .collect { it.description?.toString() ?: "" }
                def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
                fromBody + fromParagraphs
            }
            paragraphTexts.each { text ->
                ["**Broken Trigger**", "**Broken Action**", "**Broken Condition**"].each { marker ->
                    if (text.contains(marker)) brokenMarkers << marker
                }
            }
            if (brokenMarkers) {
                issues << "broken markers in render: ${brokenMarkers.unique().join(', ')}".toString()
            }
            // Multiple-flag corruption check. Compare schema declaration vs
            // statusJson appSettings record for each setting that the schema
            // says is multi.
            def settingsByName = _rmFetchSettingsByName(appId)
            def schema = _rmCollectInputSchema(cfg?.configPage)
            schema.each { name, meta ->
                if (meta?.multiple == true) {
                    def rec = settingsByName[name]
                    if (rec != null && rec.multiple != true) {
                        multipleFlagPoison << name.toString()
                    }
                }
            }
            if (multipleFlagPoison) {
                issues << "multiple-flag poison on settings: ${multipleFlagPoison.join(', ')} — re-POST with the 3-field group to recover".toString()
            }
            // Structural balance check (defense in depth — the pre-flight refusals
            // in _rmDeleteAction / _rmAddAction / replaceActions block most
            // imbalance at the source; this catches raw settings writes and the
            // post-response-commit race for non-structural deletes).
            structuralIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(settingsByName))
            if (structuralIssues) {
                issues << ("structural imbalance in action block nesting: ${structuralIssues.join('; ')} — if you are still building this rule (adding an IF/ELSE or Repeat block across separate calls), this is EXPECTED until you add the closer, and the fix is simply to add it via addAction(capability='endIf'|'stopRepeat') — do NOT restore. Only if the rule was already complete does this indicate damage (a raw settings write or a mutation that committed post-response), in which case use hub_restore_backup to roll back.".toString())
            }
        } catch (Exception e) {
            // Both sources down: include the compiled-state read failure too so the dual-failure
            // diagnostic is complete (auth/connectivity often breaks both localhost reads at once).
            def also = compiledReadError ? " (compiled-state read also failed: ${compiledReadError})" : ""
            issues << "health check failed: ${e.message}${also}".toString()
        }
    }
    // Cross-check the two RM sources when both ran. They can legitimately disagree in a
    // transient window: live on fw 2.5.0.143, deleting a rule's trigger device sets the
    // '*BROKEN*' label immediately while the compiled `broken` boolean stays false until the
    // rule re-validates (e.g. its config page is rendered), after which `broken` flips true and
    // the two agree. Surfacing the disagreement (rather than trusting either source alone) is
    // exactly why issue #254 keeps both paths instead of replacing the HTML scan. VRB rules
    // have no HTML markers, so the cross-check only applies to classic RM.
    if (ruleFormat == "rm" || ruleFormat == null) {
        boolean htmlBroken = (!brokenMarkers.isEmpty()) || (label != null && label.contains("*BROKEN*"))
        if (broken == true && !htmlBroken && sourcesUsed.contains("configPage")) {
            issues << "cross-check: ruleBuilderJson broken:true but the HTML render showed no broken markers — the structured source caught a break the render scan missed.".toString()
        } else if (broken == false && htmlBroken) {
            issues << "cross-check: HTML broken markers present but ruleBuilderJson broken:false — render text disagrees with the compiled state; treat as suspect and re-read.".toString()
        }
    }

    // Per-marker occurrence COUNT (computed before the unique() below loses it). The
    // deduped brokenMarkers list and the single collapsed "broken markers in render" issue
    // string both lose multiplicity, so a baseline already carrying one **Broken Condition**
    // would set-diff to empty against a render with TWO of them and a genuinely-new broken
    // instance would slip through a string-set delta. Callers comparing two health verdicts
    // (the replace restore gate) use this count map to detect a NEW broken instance.
    def brokenMarkerCounts = [:]
    brokenMarkers.each { m -> brokenMarkerCounts[m] = (brokenMarkerCounts[m] ?: 0) + 1 }

    // Stable report shape (backward-compatible with the pre-#254 contract): the RM detection
    // arrays are always present so existing consumers can read them unconditionally. The new
    // cross-engine fields (broken / source / ruleFormat / validationErrors) are added alongside;
    // predicate is included only when the compiled state carried one. The dual-path cost is one
    // extra localhost GET, not response size — the empty arrays are a few bytes.
    def result = [
        ok: issues.isEmpty() && broken != true && validationErrors.isEmpty(),
        broken: broken,
        source: (sourcesUsed ? sourcesUsed.join("+") : "none"),
        ruleFormat: ruleFormat,
        label: label,
        configPageError: configPageError,
        brokenMarkers: brokenMarkers.unique(),
        brokenMarkerCounts: brokenMarkerCounts,
        multipleFlagPoison: multipleFlagPoison,
        structuralIssues: structuralIssues,
        validationErrors: validationErrors,
        issues: issues
    ]
    if (predicate != null) result.predicate = predicate
    return result
}

/**
 * Collect input schema from a configPage's sections[].input[] into a
 * name → metadata map. Used to decide which settings need the .type +
 * .multiple sidecar fields.
 */
private Map _rmCollectInputSchema(Map configPage) {
    def schema = [:]
    for (s in (configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (i instanceof Map && i.name) {
                schema[i.name.toString()] = [
                    name: i.name.toString(),
                    type: i.type?.toString(),
                    multiple: i.multiple == true,
                    required: i.required == true
                ]
            }
        }
    }
    return schema
}

/**
 * Build the form body for /installedapp/update/json from a flat settings
 * map. For each key, emit:
 *   settings[<key>] = <value>      (List → CSV for capability-multi, JSON-array for enum-multi)
 *   <key>.type     = <input type>  (if schema says so)
 *   <key>.multiple = true          (if multi)
 *
 * Wire-format rules verified live against firmware 2.5.0.123:
 *
 *   capability.X multiple=true → CSV: "8,9". JSON-array shape errors HTTP 500.
 *   enum         multiple=true → JSON-array: "[\"X\",\"Y\"]". CSV stores raw
 *       string after the next updateRule click (looks correct in storage
 *       but downstream readers expecting List get a String). The native UI
 *       uses JSON-array exclusively for any <select multiple> element
 *       (appUI.js:579 `JSON.stringify($(this).val())`), so matching that is
 *       the canonical path.
 *
 * Omitting the .multiple=true sidecar on capability.* silently flips the
 * AppSetting DB flag to false and every subsequent rule render throws
 * `Command 'size' is not supported by device`. This function emits the
 * full 3-field group for every multi input in the schema, whether the
 * caller remembered or not.
 *
 * settingsMap values: String/Number/Boolean for scalars; List for
 * multi-value (device-id list for capability, option list for enum).
 */
private Map _rmBuildSettingsBody(Integer appId, Map settingsMap, Map schema) {
    def body = [id: appId.toString()]
    settingsMap.each { rawKey, rawVal ->
        def key = rawKey.toString()
        def meta = schema?."${key}"
        def typeHint = meta?.type
        def isCapability = typeHint?.startsWith("capability.")
        def isEnum = typeHint == "enum"
        // ALWAYS trust the schema's multiple flag. The earlier code coerced
        // isMulti=true whenever value was a List for capability.* fields,
        // which broke single-device pickers (e.g. pushButton.1 schema says
        // multiple=false; passing deviceIds=["288"] flipped it to true and
        // mismatch crashed RM's render with the opaque "Command 'hasCapability'
        // is not supported" error). Verified live.
        def isMulti = meta?.multiple == true

        // Serialize value: branch by input type for multi-value writes.
        // Capability multi: CSV ("8,9"). Enum multi: JSON-array ('["X","Y"]').
        // Everything else: toString.
        def serialized
        if (rawVal instanceof List) {
            if (isEnum) {
                serialized = groovy.json.JsonOutput.toJson(rawVal.collect { it?.toString() }.findAll { it != null })
            } else {
                serialized = rawVal.collect { it?.toString() }.findAll { it != null }.join(",")
            }
        } else if (rawVal == null) {
            serialized = ""
        } else {
            serialized = rawVal.toString()
        }
        body["settings[${key}]".toString()] = serialized

        // Sidecar fields. `.type` always needed for non-bool inputs so the
        // hub knows how to marshal the update; `.multiple` MUST always be
        // explicit (true OR false) — verified live from the UI's
        // capture of a button-push action: omitting `.multiple=false` on
        // non-multi capability writes triggered RM's "Command 'hasCapability'
        // is not supported" render error on doActPage. The render path RM
        // takes for capability fields differs based on whether .multiple is
        // present, and the path it falls into without it is buggy for some
        // capabilities (button.pushableButton among them).
        if (typeHint) {
            body["${key}.type".toString()] = typeHint
        }
        body["${key}.multiple".toString()] = isMulti ? "true" : "false"

        // For capability.* writes the UI also emits `deviceList=<keyname>`
        // — a marker telling RM which form field is the device list being
        // modified. Without it, certain capabilities (notably
        // capability.pushableButton on button.push actions) fall into a
        // render path that errors with hasCapability not supported.
        if (isCapability) {
            body["deviceList".toString()] = key
        }
    }
    return body
}

/**
 * Verify post-write that every touched capability.* setting with multiple=true
 * in the schema still has multiple=true in the hub's live appSettings record.
 * If any have been flipped to false, the DB has been poisoned and the rule
 * will render with `Command 'size' is not supported` errors. Callers catch
 * MarshalFlagDivergenceException and re-POST with the full 3-field group.
 *
 * Throws IllegalStateException (sandbox-friendly alias for the divergence
 * condition) with a specific message listing the poisoned setting names.
 */
private void _rmVerifyMultipleFlags(Integer appId, Map schema, List<String> touchedNames) {
    def status = _rmFetchStatusJson(appId)
    def live = (status?.appSettings ?: []).collectEntries { s ->
        [(s?.name?.toString()): s]
    }
    def poisoned = []
    touchedNames.each { name ->
        def declared = schema?."${name}"
        if (declared?.multiple == true) {
            def rec = live?."${name}"
            if (rec != null && rec.multiple != true) {
                poisoned << name
            }
        }
    }
    if (poisoned) {
        def settingWord = (poisoned.size() == 1) ? "setting" : "settings"
        throw new IllegalStateException(
            "MarshalFlagDivergenceException: multiple=true flag flipped to false on ${settingWord} ${poisoned} " +
            "for app ${appId}. This corrupts RM's device-list rendering. Caller should re-POST with the full " +
            "3-field group (settings[name], name.type, name.multiple=true) to recover.")
    }
}

// Central settings-write POST + 4xx guard. hubInternalPostForm returns a status Map and does
// NOT throw on 4xx, so a rejected write -- typically a stale version token -- must be detected
// here or it silently reports success. Mirrors the status guard in _rmSubmitFullPageForm
// (same IllegalStateException runtime-error contract).
private Map _rmPostSettings(Integer appId, Map body, Map cache = null) {
    def resp = hubInternalPostForm("/installedapp/update/json", body)
    // The settings POST re-renders the page and bumps app.version -> drop all cached pages.
    _rmCacheInvalidate(cache, appId)
    if (resp?.status != null && resp.status >= 400) {
        def bodyPreview = resp.data?.toString()?.take(200)
        throw new IllegalStateException("Settings write for app ${appId} failed: status=${resp.status}${bodyPreview ? "; body=" + bodyPreview : ""}. The write was rejected so nothing was committed (a 4xx is usually a stale version token -- re-fetch via hub_get_app_config(appId=${appId}) and retry).")
    }
    return resp
}

/**
 * Write a settings map to an RM rule with the 3-field capability contract
 * enforced automatically. After the POST, verify the multiple flags survive
 * and re-POST once if they were flipped (known sticky-bug behavior). Throw
 * if still divergent after retry — the caller should surface this and
 * suggest hub_restore_backup.
 */
private Map _rmUpdateAppSettings(Integer appId, Map settingsMap, Map schema = null, Map cache = null) {
    if (schema == null) {
        schema = _rmCollectInputSchema(_rmFetchConfigJson(appId)?.configPage)
    }
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    def resp = _rmPostSettings(appId, body, cache)

    def touched = settingsMap.keySet().collect { it.toString() }
    try {
        _rmVerifyMultipleFlags(appId, schema, touched)
    } catch (IllegalStateException divergence) {
        // Sticky-flag recovery: one forced re-POST with the full group.
        // Verified live to un-poison on the second attempt. The schema
        // already carries the .multiple=true sidecar intent from the
        // initial build, so the same body is correct to resend.
        mcpLog("warn", "rm-native", "Marshal divergence on app ${appId} -- retrying: ${divergence.message}")
        _rmPostSettings(appId, body, cache)
        _rmVerifyMultipleFlags(appId, schema, touched)
    }
    return resp
}

/**
 * Force-delete an app via /installedapp/forcedelete/<id>/quiet. Same path
 * RM uses internally for its own "Delete Rule" button — bypasses child/
 * device reference checks. Caller MUST have called _rmBackupRuleSnapshot
 * first; hub_delete_native_app enforces this.
 */
private Map _rmForceDeleteApp(Integer appId) {
    def resp = hubInternalGetRaw("/installedapp/forcedelete/${appId}/quiet")
    // Success = 302 redirect to installedapps list. Accept anything 2xx/3xx.
    if (resp?.status != null && resp.status >= 400) {
        throw new IllegalArgumentException("forcedelete failed for app ${appId}: status=${resp.status}")
    }
    return resp
}


def currentVersion() {
    return "2.9.0"
}


// ==================== TOOL GUIDE ====================


// ---- Best-practice acknowledgment + reactive hints (issue #299) ----
// Single source of truth for the acknowledgment key the enableMandatoryBPS gate validates.
// The same literal is ALSO typed into the best_practice_reference guide body below (a Groovy
// '''-string cannot interpolate ${...}, and switching it to a """ string would hide the key
// from sandbox-lint's section-key parser) -- ExecuteToolMandatoryBpsGateSpec asserts the two copies stay in sync.
def hubBpsGuideKey() { 'bps-ack-299' }

// Map a (write) tool to the hub_get_tool_guide section that documents IT (issue #299). This is the
// reactive hint's whole point: on an error, point the LLM at the FAILING tool's own reference, not
// a generic page. Sections are the real keys in getToolGuideSections(); the groupings mirror where
// each family already cites hub_get_tool_guide(section=...) in its descriptions/errors. Returns null
// for tools with no dedicated section -- those get NO reactive hint (a generic pointer is exactly
// what this feature must avoid).
def _guideSectionForTool(toolName) {
    def t = (toolName ?: '').toString()
    if (t == 'hub_set_rule') return 'set_rule_reference'
    if (t == 'hub_set_visual_rule' || t == 'hub_delete_visual_rule') return 'visual_rule_reference'
    if (t.endsWith('_custom_rule')) return 'rules'
    if (t in ['hub_set_native_app', 'hub_delete_native_app', 'hub_clone_native_app',
              'hub_export_native_app', 'hub_import_native_app', 'hub_set_app_disabled',
              'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean']) return 'builtin_app_tools'
    if (t == 'hub_update_device') return 'update_device'
    if (t == 'hub_manage_virtual_device') return 'virtual_devices'
    if (t in ['hub_create_backup', 'hub_restore_backup']) return 'backup'
    if (t in ['hub_write_file', 'hub_delete_file']) return 'file_manager'
    if (t in ['hub_delete_device', 'hub_delete_room', 'hub_delete_item', 'hub_reboot', 'hub_shutdown',
              'hub_update_firmware', 'hub_call_destructive_radio', 'hub_call_zwave', 'hub_call_zigbee',
              'hub_call_matter', 'hub_call_device_swap', 'hub_call_device_replace']) return 'hub_admin_write'
    if (t in ['hub_call_device_command', 'hub_get_device_attribute']) return 'device_authorization'
    return null
}

// Reactive best-practice hint (issue #299, always on). On a write-tool error, return a one-line
// pointer to the FAILING tool's own guide section (via _guideSectionForTool) so the LLM can recover
// from the tool's reference. Pure function, no hub I/O. Returns null when: the tool has no dedicated
// section (no generic fallback by design); the error is a permission/config refusal, not a tool-
// domain error (the fix there is a toggle, not the guide); or the error already points at the guide
// (idempotent -- many tools self-cite, so a retry can't stack hints). (args reserved for future
// arg-shape hints, e.g. "hub_set_rule created with no trigger".)
def _reactiveBpsWarning(toolName, args, errorText) {
    def txt = (errorText ?: '').toString()
    if (txt.contains('get_tool_guide')) return null
    if (txt =~ /(?i)tools are disabled|Developer Mode tools are disabled|disabled in Advanced settings|^Mandatory best-practice/) return null
    // Gateway-ENVELOPE errors: the sub-tool never ran (handleGateway rejected the call before
    // dispatch), so the resolved sub-tool's section is irrelevant -- the caller must fix the
    // gateway call, not read the tool guide. Stay quiet, same as the config refusals above.
    if (txt =~ /Unknown tool|Unknown gateway|Cannot call a gateway|Gateway arg|Missing required parameter|useGateways is OFF/) return null
    def section = _guideSectionForTool(toolName)
    if (!section) return null
    return "See hub_get_tool_guide(section=\"${section}\") for ${toolName}'s reference and best practices."
}

// Attach a reactive best-practice hint to a returned-error result Map in place (issue #299).
// No-op when a hint was already attached -- keeps the warning idempotent across retries.
def _applyReactiveBpsWarning(toolName, args, result) {
    if (!(result instanceof Map) || result.containsKey('bp_warning')) return
    def w = _reactiveBpsWarning(toolName, args, (result.error ?: result.note ?: '').toString())
    if (w) result.bp_warning = w
}

def getToolGuideSections() {
    return [
        device_authorization: '''## Device Authorization (CRITICAL)

**Exact match rule:**
- If user specifies a device name that EXACTLY matches a device label (case-insensitive OK), use it directly
- Example: User says "turn on Kitchen Light" and device "Kitchen Light" exists → use it

**Non-exact match rule:**
- If no exact match exists, search for similar devices
- Present options to user and WAIT FOR EXPLICIT CONFIRMATION before using any device
- Example: User says "use test switch" but only "Virtual Test Switch" exists → ask "Did you mean 'Virtual Test Switch'?"

**Tool failure rule:**
- If a tool fails (e.g., hub_manage_virtual_device returns an error), report the failure to the user
- Do NOT silently fall back to using existing devices as a workaround
- Example: If creating a virtual device fails, don't just grab an existing device to use instead

**Why this matters:**
- Wrong device could control critical systems (HVAC, locks, security)
- User trust depends on AI only controlling what they explicitly authorized''',

        best_practice_reference: '''## Best-Practice Reference

Acknowledgment key: bps-ack-299

The "Require Best-Practice Guide Acknowledgment" gate is ON by default. While it is on, every write
tool requires you to pass this exact key as the `bestPracticeKey` argument on the call --
e.g. `bestPracticeKey: "bps-ack-299"`. Read this section once, then include that argument on each
write for the rest of the session. Reads, hub_get_tool_guide, and hub_update_mcp_settings are
never gated, so you can always reach this guide and (if needed) toggle the gate off. The key is
published only here, so supplying it proves you consulted these practices before writing.

Reactive hints are always on (no toggle): when a write tool errors, the error gains a one-line
pointer to THAT tool's own guide section -- follow it for the failing tool's reference.

### Write-tool best practices

- Rules: prefer native Rule Machine (hub_manage_native_rules_and_apps / hub_set_rule) over the
  legacy custom_* MCP rule engine for new automation work -- the custom engine is legacy and
  closed to new feature work.
- Devices: resolve the exact target with hub_list_devices before acting; device IDs compare as
  strings, and a wrong device could control a critical system (HVAC, locks, security).
- Destructive writes: create a backup with hub_create_backup within 24h and pass confirm=true;
  destructive tools refuse otherwise.''',

        hub_admin_write: '''## Destructive Write Tools - Pre-Flight Checklist

All Write master tools require these steps:
1. Backup check: Ensure hub_create_backup was called within the last 24 hours
2. Inform user: Tell them what you're about to do
3. Get confirmation: Wait for explicit "yes", "confirm", or "proceed"
4. Set confirm=true: Pass the confirm parameter

### Tool-Specific Requirements

**hub_reboot** - 1-3 min downtime, all automations stop, scheduled jobs lost, radios restart. Only when user explicitly requests.

**hub_update_firmware** - Installs the hub's pending platform/firmware update, then the hub self-reboots (5-10 min full downtime). Confirm a pending update via hub_get_info (platformUpdate) first; backup <24h + confirm=true required to apply; poll progress with statusOnly=true. Only when user explicitly requests.

**hub_shutdown** - Powers OFF completely, requires physical restart. NOT a reboot. Only when user explicitly requests.

**hub_call_zwave (action=repair_start)** - 5-30 min duration, Z-Wave devices may be unresponsive. Best during off-peak hours. exclusion_start and node_remove unpair/disrupt devices (confirm=true).

**hub_call_destructive_radio** - IRREVERSIBLE. reset unpairs EVERY device on a radio; a firmware flash can brick hardware if interrupted. Backup <24h, explicit radio+action+confirm=true, never power-cycle during a flash.

**hub_delete_device** - MOST DESTRUCTIVE, NO UNDO. For ghost/orphaned devices, stale DB records, stuck virtual devices.
- Use hub_get_device to verify correct device
- Warn if recent activity or Z-Wave/Zigbee (do exclusion first)
- All details logged to MCP debug logs for audit

**hub_delete_room** - Devices become unassigned (not deleted). List affected devices first.

**hub_delete_item (type=app|driver|library)** - Remove app instances via Hubitat UI first (apps). Change devices to different driver first (drivers). For libraries, check that no apps/drivers reference the library via #include namespace.Name before deleting -- deletion breaks any code that still includes it. Auto-backs up before deletion.''',

        virtual_devices: '''## Virtual Device Types

| Type | Description | Use Case |
|------|-------------|----------|
| Virtual Switch | on/off toggle | Boolean flags, triggers |
| Virtual Button | pushable button | Triggering automations |
| Virtual Contact Sensor | open/closed | Simulate door/window |
| Virtual Motion Sensor | active/inactive | Simulate motion |
| Virtual Presence | present/not present | Presence simulation |
| Virtual Lock | lock/unlock | Lock state simulation |
| Virtual Temperature Sensor | numeric temp | Temperature reporting |
| Virtual Humidity Sensor | numeric humidity | Humidity reporting |
| Virtual Dimmer | switch + level 0-100 | Dimmable light simulation |
| Virtual RGBW Light | color-controllable | Color light simulation |
| Virtual Shade | open/close + position | Window shade control |
| Virtual Garage Door Opener | open/close | Garage door state |
| Virtual Water Sensor | wet/dry | Water leak simulation |
| Virtual Omni Sensor | multi-purpose | Combined sensor types |
| Virtual Fan Controller | fan speed | Fan simulation |

### Custom drivers

Use `customDriver={namespace, name}` instead of `deviceType` to instantiate any user-installed driver:
- `namespace` and `name` must match exactly as the driver is registered on the hub
- Use `hub_read_apps_code(tool="hub_list_drivers")` to discover installed driver namespace + name values
- Mutually exclusive with `deviceType` -- supply exactly one
- On failure, the tool surfaces an `IllegalArgumentException` with a `hub_list_drivers` hint

MCP-managed virtual devices:
- Auto-accessible to all MCP tools without manual selection
- Appear in Hubitat UI for Maker API, Dashboard, Rule Machine
- Use hub_manage_virtual_device(action="delete") to remove (not hub_delete_device)''',

        update_device: '''## hub_update_device Properties

| Property | Requires Write master |
|----------|-------------------------|
| label | No |
| name | No |
| deviceNetworkId | No |
| dataValues | No |
| preferences | No |
| room | Yes |
| enabled | Yes |

**Preferences format:**
{"pollInterval": {"type": "number", "value": 30}, "debugLogging": {"type": "bool", "value": true}}

**Valid preference types:** bool, number, string, enum, decimal, text

**Room assignment:** Use exact room name (case-sensitive)''',

        rules: '''## Rule Structure Reference

NOTE: this section describes the LEGACY custom MCP rule engine (the custom_* tools). For native Rule Machine rules built via hub_set_rule (addTrigger), the periodic shape is DIFFERENT -- use periodic={frequency, everyN, ...}, NOT {type, interval, unit}. See hub_get_tool_guide section "set_rule_reference" for the native addTrigger periodic field shape.

### Rule JSON Structure
{"name": "Rule name", "description": "Optional", "enabled": true, "triggers": [...], "conditions": [...], "conditionLogic": "all|any", "actions": [...]}

### Triggers
- device_event: {"type":"device_event","deviceId":"id","attribute":"switch","value":"on","operator":"equals"} — supports duration (seconds) for debouncing, multi-device via deviceIds array with matchMode any/all
- Multi-device: {"type":"device_event","deviceIds":["id1","id2"],"attribute":"switch","value":"on","matchMode":"all"}
- button_event: {"type":"button_event","deviceId":"id","action":"pushed|held|doubleTapped","buttonNumber":1}
- time: {"type":"time","time":"08:30"} or {"type":"time","sunrise":true,"offset":30} or {"type":"time","sunset":true,"offset":-15} — offset in minutes (positive=after, negative=before)
- periodic: {"type":"periodic","interval":5,"unit":"minutes|hours|days"}
- mode_change: {"type":"mode_change","fromMode":"Away","toMode":"Home"} — both optional
- hsm_change: {"type":"hsm_change","status":"armedAway|armedHome|armedNight|disarmed|intrusion"} — optional

### Conditions
- device_state: Current device attribute value
- device_was: Device was in state for X seconds
- time_range: Time window (supports sunrise/sunset)
- mode: Current location mode
- variable: Hub variable value
- days_of_week: Specific days
- sun_position: Sun above/below horizon
- hsm_status: Current HSM state
- presence: Presence sensor state (deviceId + status: present|not present)
- lock: Lock state (deviceId + status: locked|unlocked)
- thermostat_mode: Thermostat mode (deviceId + mode: auto|cool|heat|off|emergency heat)
- thermostat_state: Thermostat operating state (deviceId + state: idle|heating|cooling|fan only|pending heat|pending cool)
- illuminance: Illuminance threshold (deviceId + operator + value)
- power: Power-meter threshold (deviceId + operator + value)

### Actions
- device_command: Send command to device
- toggle_device: Toggle device state
- activate_scene: Activate a scene
- set_variable/set_local_variable: Set variable value
- set_mode: Change location mode
- set_hsm: Change HSM state
- delay: Wait with optional ID for targeted cancel via cancel_delayed
- if_then_else: Conditional logic within actions
- cancel_delayed: Cancel pending delayed actions by ID
- repeat: Loop actions N times or until condition
- stop: Stop rule execution
- log: Log message to MCP debug logs
- set_thermostat: Set mode/setpoints/fan
- http_request: GET/POST to URL
- speak: TTS with optional volume
- comment: Documentation only, not executed
- set_valve: Open/close valve
- set_fan_speed: Set fan to low/medium/high/auto
- set_shade: Open/close/position shade
- set_level: Set dimmer level — {deviceId, level (0-100)}
- set_color: Set color via hue/saturation/level — {deviceId, hue (0-100), saturation (0-100), level (0-100, optional)}
- set_color_temperature: Set color temperature — {deviceId, temperature (Kelvin)}
- lock / unlock: Lock or unlock a lock — {deviceId}
- capture_state: Capture device states for later restore — {deviceIds, stateId? (optional, default "default")}
- restore_state: Restore previously captured states — {stateId? (optional, default "default")}
- send_notification: Send a notification to a device — {deviceId, message}
- variable_math: Arithmetic on variables — {variableName, operation: add|subtract|multiply|divide|modulo|set, operand, scope: local|global}''',

        backup: '''## Backup System

### Hub Backups
- hub_create_backup creates full hub database backup
- Required within 24 hours before any Write master operation
- Only write tool that doesn't require a prior backup

### Source Code Backups (Automatic)
- Created when using hub_update_app, hub_update_driver, hub_update_library, hub_delete_item
- Stored in File Manager as .groovy files
- Persist even if MCP uninstalled
- Max 20 kept, oldest pruned
- Rapid edits preserve original (1-hour protection)

### Rule Backups (Automatic)
- hub_delete_custom_rule auto-backs up to File Manager as mcp_rule_backup_<name>_<timestamp>.json
- Restore via: hub_read_file → hub_import_custom_rule
- Skip backup: set testRule=true when creating/updating''',

        file_manager: '''## File Manager

Files stored at http://<HUB_IP>/local/<filename>

**File name rules:**
- Must match ^[A-Za-z0-9][A-Za-z0-9._-]*$
- No spaces, no leading period
- Valid: my-config.json, backup_2024.txt
- Invalid: .hidden, my file.txt

**Chunked reading:**
- Use offset and length for files >60KB
- Each chunk must be <60KB''',

        performance: '''## Performance Tips

**hub_list_devices:**
- Use detailed=false for initial discovery
- With detailed=true, paginate: 20-30 devices per request
- Make tool calls sequentially, not in parallel
- Server-side label/capability filtering: use labelFilter (substring) and capabilityFilter (exact capability name) instead of fetching all devices and filtering client-side
- format='ids' returns a flat integer array (cheapest for "which devices exist" queries)
- fields=[...] projects named fields only: currentStates and attributes are the expensive ones (per-device hub reads) -- project those out to save hub CPU; capabilities and commands are in-memory and cheap. id is always included regardless of projection. Unknown field names throw.

**hub_list_device_events:**
- Default limit 10, recommended max 50
- Higher values (100+) may cause delays

**hub_get_logs:**
- Default 100 entries, max 500
- Use level and source filters to narrow results

**hub_list_device_events:**
- Default: most-recent events for a device (deviceId + limit)
- Add hoursBack for up to 7 days of relative history; omit deviceId for location-level events (mode/HSM/hub variable)
- Add since for an absolute bookmark -- return only events AFTER an exact timestamp (ISO-8601 in the same format the tool emits in date/sinceTimestamp -- a numeric offset with no colon, e.g. 2026-06-23T10:00:00.000-0600; a trailing Z for UTC and a millis-less variant are also accepted -- or epoch milliseconds). since takes precedence over hoursBack; a future since yields an empty list. Both since and hoursBack route to history mode
- Change-watching loop: record a returned event date, run your action, then pass that date back as since to get exactly the new events. The response echoes sinceMode ("explicit" when since drove it, "relative" for hoursBack) and the bounding field (since or hoursBack)
- appId (mutually exclusive with deviceId) returns the events an installed app/rule emitted; rows are {name, value, description, date}
- Use the attribute filter to reduce data volume''',

        builtin_app_tools: '''## Installed-App & Native-Rule Tools

Tools in the hub_read_apps_code and hub_manage_native_rules_and_apps gateways are gated by the two universal masters. The read tools (hub_list_apps any scope, hub_list_device_dependents, hub_get_app_config, hub_list_app_pages, hub_list_hpm_packages with optional includeDrift) require the Read master (ON by default). The hub_manage_native_rules_and_apps write tools require the Write master; the destructive CRUD tools (hub_set_rule / hub_set_native_app / hub_delete_native_app) ALSO require confirm=true + a recent backup (requireDestructiveConfirm). If the user sees "Read tools are disabled" or "Write tools are disabled" errors, direct them to the Read/Write toggles on the MCP Rule Server app settings page.

**hub_read_apps_code (4 tools):**

- **hub_list_apps (scope='instances')** — enumerate ALL running app instances on the hub (built-in + user) with parent/child tree
  - filter="all" (default) | "builtin" | "user" | "disabled" | "parents" | "children"
  - Each entry: id, name, type, disabled, user, hidden, parentId, hasChildren, childCount
  - Built-in apps have user=false (Rule Machine, Room Lighting, Groups and Scenes, Mode Manager, HSM, Dashboards, Maker API, etc.)
  - User apps have user=true (Awair, Ecobee, HPM, etc.)
  - Parent/child tree is flattened with parentId pointers. Hidden parents are excluded from output but their children are promoted to the nearest visible ancestor.

- **hub_list_device_dependents** — find apps that reference a specific device
  - Use BEFORE deleting a device, disabling a device, or troubleshooting unexpected behavior
  - Returns appsUsing array with each app's id, name (type like "Room Lights" or "Rule-5.1"), label (user-visible), trueLabel (HTML-stripped), disabled
  - Answers "if I delete this device, which automations break?"

- **hub_get_app_config** — read an installed app's configuration page (Read master required)
  - Returns app identity (label, type, disabled), config page sections/inputs/values, and child apps
  - summary=true is a fast identity-only mode: the hub's thin app record (id, name, type, disabled, user) with no config-page render -- use it for existence/identity checks on expensive apps
  - Multi-page apps expose sub-pages via pageName. For HPM: use pageName="prefPkgUninstall" for the FULL installed-package list; pageName="prefPkgModify" returns only the subset with optional components; pageName="prefOptions" is the main-menu navigation (no package data). RM 5.x and Room Lighting use a single mainPage (no pageName needed). Call hub_list_app_pages first to discover available page names for any multi-page app.
  - includeSettings=true adds the raw internal settings map (large apps: 500-1000 keys with app-specific encoding)
  - Workflow: hub_list_apps (scope='instances'; or hub_list_rules for RM rules specifically -- note that hub_get_custom_rule handles only MCP-native rules, not Hubitat's built-in Rule Machine) to find appId, then hub_get_app_config to inspect. For multi-page apps, consider hub_list_app_pages first.

- **hub_list_app_pages** — discover what pageNames a given app accepts (Read master required)
  - Input: appId
  - Returns curated page directory for known app types (HPM, RM 5.x, Room Lighting, Mode Manager) plus an introspected primary page for unknown app types
  - Cuts the page-name guessing cycle for multi-page apps. Especially useful for HPM which exposes multiple sub-pages (prefPkgUninstall / prefPkgModify / prefPkgInstall / prefPkgMatchUp) for different operations.

**hub_read_apps_code (2 tools) — HPM package state introspection (Read master required):**

- **hub_list_hpm_packages** — return all packages tracked by Hubitat Package Manager with full component inventory
  - If hpmAppId is omitted, HPM is auto-discovered by scanning installed apps for type="Hubitat Package Manager"
  - Each package: manifestUrl, packageName, version, beta, author, apps[], drivers[], files[]
  - Each app/driver component: id (UUID), name, heID (Hubitat code ID or null), required, version
  - files[] entries have no heID (File Manager assets tracked by name only)

- **hub_list_hpm_packages with includeDrift=true** — also cross-reference HPM tracked state against what is installed on the hub (attached under a `drift` key)
  - Surfaces missing-required (required=true but heID null), orphan-app (heID recorded but code no longer in Apps Code registry), and orphan-driver (heID recorded but code no longer in Drivers Code registry) signals
  - Optional packageFilter (case-insensitive substring) narrows to specific packages
  - Response: packagesChecked, packagesWithActionableDrift (packages with at least one actionable signal), totalDriftSignals (actionable drift only -- not data-quality warnings), drift[] array (one entry per package with signals or dataQualityWarnings; drift[].length may exceed packagesWithActionableDrift when data-quality-only packages exist), summary sentence, orphanDetection ({enabled, reason?}), orphanDriverDetection ({enabled, reason?}), limitations note
  - Data-quality warning types in dataQualityWarnings[]: heid-whitespace-normalized (padded heID normalized; component KEPT), heid-non-scalar-dropped (non-scalar heID; component DROPPED), empty-heid, skipped-malformed-component
  - Limitation: heID-presence-only; HPM stores no source hashes so post-install edits via hub_update_app are not detectable

**hub_manage_native_rules_and_apps (11 tools) — read, trigger, AND full CRUD on native RM rules:**

RMUtils-based control surface (hub_list_rules = Read master; trigger/pause/private-boolean = Write master):
- **hub_list_rules** — enumerate Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id)
- **hub_call_rule** — trigger an existing RM rule
  - action="rule" (default): full evaluation (triggers + conditions + actions)
  - action="actions": run actions only, skip conditions
  - action="stop": stop running actions
- **hub_set_rule_paused** — pause (value=true) or resume (value=false) a rule; reversible
- **hub_set_rule_private_boolean** — set private boolean (Boolean or lowercase "true"/"false" only)

Native CRUD (hub admin-layer, additionally requires the Write master):
- **hub_set_native_app** — create or edit any classic SmartApp (Button Controller, Notifier, Groups+Scenes, Basic Rules; edits Visual Rules by appId too). Omit appId to create (appType enum: rule_machine / button_controller / groups_scenes / notifier / basic_rule; name); provide appId to edit via settings/button. Visual Rules are created with hub_set_visual_rule, not this appType enum. Create a Button Rule under its controller via buttonRule={controllerId, buttonNumber, event} (returns buttonRuleId; author its actions via hub_set_rule). walkStep (generic classic-page walker) works here too. Returns appId on create. (In the hub_manage_native_rules_and_apps gateway.)
- **hub_set_rule** — create or edit a Rule Machine rule. Omit appId to create (name; optionally bundle addTriggers=[...] / addActions=[...] to populate in one call); provide appId to edit via the structured shortcuts (addTrigger / addAction / addRequiredExpression / walkStep / ...). (In the hub_manage_rule_machine gateway.)
- **hub_set_rule** (edit detail) — edit an existing Rule Machine rule (appId required). Two raw modes (settings (Map) OR button (String)) plus 16 structured shortcuts (addTrigger, addTriggers, addAction, addActions, addRequiredExpression, replaceRequiredExpression, addLocalVariable, removeLocalVariable, removeAction, clearActions, replaceActions, moveAction, removeTrigger, modifyTrigger, patches, walkStep). Args: appId + one of those shortcut keys, plus optional pageName, stateAttribute, confirm. Auto-backs-up before writing; emits the multiple=true 3-field capability contract automatically. removeTrigger={index:N} deletes a trigger; modifyTrigger={index:N, mods:{state:'...'}} changes the state field of an existing trigger (capability/deviceIds changes require removeTrigger + addTrigger).
- **hub_set_app_disabled** — enable or disable any installed app (red-X) via POST /installedapp/disable; reversible. Args: appId, disabled (bool). Read-back verified.
- **hub_delete_native_app** — soft delete (default; refuses if children exist) or force=true. Args: appId, force, confirm. Auto-backs-up before deleting.
- **hub_clone_native_app** — clone any classic SmartApp via Hubitat's first-party appCloner. Args: sourceAppId, newName (opt), confirm. Returns newAppId. Drives the appCloner's 4-step wizard (cloneRuleButton -> confirmation -> importRule sub-page -> importNow); typical clones complete in tens of seconds.
- **hub_export_native_app** — export any classic SmartApp to its canonical JSON shape via Hubitat's first-party appCloner. Args: sourceAppId, saveAs (opt File Manager filename). Returns jsonContent. Self-contained document with appReplacements + deviceReplacements + full rule state; round-trips through hub_import_native_app.
- **hub_import_native_app** — re-create a rule/app from a previously-exported JSON via Hubitat's first-party appCloner. Args: jsonContent | fromFile, parentHintAppId, newName (opt), confirm. Returns newAppId. The cloner needs an existing rule under the target parent to seed itself (parentHintAppId).
- **hub_get_rule_health** — read-only health check on any installed app (Rule Machine AND Visual Rules Builder). Args: appId, source (auto|ruleBuilderJson|configPage, default auto). Prefers the compiled-state verdict: the classic RM `broken` boolean (/app/ruleBuilderJson) or a graph Visual Rule's validationErrors (/app/ruleBuilder20Json); for classic RM the HTML render scan is retained as cross-check + fallback. Returns ok / broken / source / ruleFormat / label / configPageError / brokenMarkers / multipleFlagPoison / structuralIssues / validationErrors / issues (+ predicate when read).

For READING an RM rule's current state, use **hub_get_app_config** in the hub_read_apps_code gateway — it works on any installed app including RM rules and returns the same configPage shape that hub_set_rule expects to see.

For BACKUP enumeration and restore, use the unified **hub_list_backups** (in hub_read_apps_code) + **hub_restore_backup** (in hub_manage_backup) — RM rule snapshots have type="rm-rule" in those tools' output and hub_restore_backup auto-dispatches the rule-restore path.

**Safety model for native CRUD:**
1. Every write is preceded by a full snapshot (configure/json + statusJson) saved to File Manager; the response's backup.backupKey is the restore handle.
2. Multi-device capability inputs (capability.X with multiple=true) require a 3-field POST payload group (settings[name]=csv, name.type=capability.X, name.multiple=true). Omitting name.multiple=true poisons the AppSetting DB flag and every render throws `Command 'size' is not supported by device`. hub_set_rule emits the full group automatically from the input schema — callers never have to think about this.
3. After every write, the multiple flags in the live appSettings are verified. If any flipped, one automatic retry fires with the full group. Persistent divergence throws and the response surfaces hub_restore_backup as the next step.
4. delete is soft by default. Pass force=true only when you know the rule has children you also want gone.

**CRUD workflow example:**
  hub_set_rule(name="BAT-RM-demo", confirm=true) → {appId: 974, ...}
  hub_get_app_config(appId=974, includeSettings=true) → input schema + current settings
  hub_set_rule(appId=974, addTrigger={capability: "Switch", deviceIds: [8, 9], state: "on"}, confirm=true)
  hub_set_rule(appId=974, addAction={capability: "switch", action: "off", deviceIds: [10]}, confirm=true)
  hub_get_rule_health(appId=974) → verify ok=true, no configPageError or brokenMarkers
  hub_delete_native_app(appId=974, force=true, confirm=true) → {backup: {backupKey: "rm-rule_974_..."}}''',

        set_rule_reference: '''## `hub_set_rule` capability reference

Reference for the `hub_set_rule` structured shortcuts (`addTrigger`, `addAction`, `addRequiredExpression`), the lower-level `walkStep` walker, and the raw `settings`/`button` wizard flow. The tool's schema descriptions point here so BOTH the flat and gateway `tools/list` catalogs stay lean (issue #181) without losing this reference. Get this whole section back inline at call time with `hub_set_rule(guide: true)` (no separate tool call), or pass `{discover: true}` on `addTrigger`/`addAction` for the live machine-readable schema.

### `addTrigger` capability families

- **Device-state** (Switch / Motion / Contact / Lock / Garage / Door / Valve / Window Shade / Presence / Power source): `capability`, `deviceIds`, `state` (`'on'`, `'active'`, `'open'`, `'unlocked'`, etc.)
- **Multi-device "all of these"**: add `allOfThese=true` to the device-state spec
- **Numeric** (Temperature / Humidity / Battery / Illuminance / Power / Energy / CO2 / Dimmer / Thermostat setpoints): `capability`, `deviceIds`, `comparator` (`=`, `<`, `>`, `<=`, `>=`, `*changed*`), `value`
- **Button** (`capability='Button'`): `deviceIds`, `buttonNumber`, `state` (`pushed` | `held` | `doubleTapped` | `released`)
- **Custom Attribute** (`capability='Custom Attribute'`): `deviceIds`, `attribute` (the attribute name), `comparator`, `value`
- **And-stays sticky modifier** (any device-state or numeric trigger): add `andStays={hours, minutes, seconds}` to the spec
- **Time / Sunrise / Sunset** (`capability='Certain Time (and optional date)'`): `time` (`'A specific time'` | `'Sunrise'` | `'Sunset'`), `atTime`, `offset` (minutes, for sunrise/sunset)
  - `atTime` semantic: `'HH:mm'` form (e.g. `'17:00'`) = **DAILY-recurring** trigger that fires every day at that wall-clock time. Full ISO datetime (e.g. `'2026-04-29T17:00:00'` or `'2026-04-29T17:00:00.000-0500'`) = **ONE-SHOT dated** trigger that fires once on that specific date. Forms without timezone are auto-normalized to hub local tz; explicit-offset and Zulu forms are normalized to UTC equivalent.
- **Mode** (`capability='Mode'`): `state='Night'` OR `state=['Away','Night']` (mode names, case-insensitive) OR `modeIds=['3']` OR `modeIds=['3','5']` (IDs directly, from `hub_list_modes`).
  - **IMPORTANT:** writes `modesX<N>` internally — do NOT pass `tstate` or `rawSettings.tstate` for Mode triggers (silently ignored; renders as Broken Trigger). Use `hub_list_modes` to list valid mode names/IDs.
- **Periodic Schedule** (`capability='Periodic Schedule'`): recurring schedule via the dedicated periodic sub-page. Spec:
  ```
  periodic={
    frequency: 'Seconds'|'Minutes'|'Hourly'|'Daily'|'Weekly'|'Monthly'|'Yearly'|'Cron String',
    everyN: <int>,                 // "every N <unit>" mode (Seconds/Minutes/Hourly/Daily)
                                   //   REQUIRED even when =1 for Daily AND Hourly (omitting renders null)
                                   //   Seconds/Minutes: whole number from [1,2,3,4,5,6,10,12,15,20,30] (firmware-imposed; Hourly/Daily accept any positive integer; fractional truncates, 5.5->5)
    startingTime: 'HH:mm',         // start-time (Hourly/Daily/Weekly/Monthly/Yearly; Seconds has none); for Hourly-everyN, pass it (omitting renders a cosmetic trailing "starting at " blank)
    weekdaysOnly: <bool>,          // Daily-only
    selectedHours: [9,12],         // Hourly-only, alternative to everyN
    selectedMinutes: [0,30],       // Minutes-only, "at specific minutes", alternative to everyN
    selectedDaysOfMonth: [1,15],   // Daily-only, alternative to everyN/weekdays
    daysOfWeek: ['Monday','Friday'], // Weekly-only, MULTI day-of-week
    dayOfWeek: 'Monday',           // Monthly/Yearly nth-weekday, SINGLE day-of-week (distinct from daysOfWeek)
    dayOfMonth: <int>,             // Monthly by-day "on day number" (pair with everyNMonths; exclusive with weekOfMonth)
    everyNMonths: <int>,           // "of every N months" (Monthly, both modes; free integer)
    months: 'December',            // Yearly only -- single nth-weekday month (String); Monthly does NOT take months
    weekOfMonth: 'First',          // Monthly/Yearly nth-weekday: First|Second|Third|Fourth|Last (presence selects nth-weekday)
    minutesOffset: <int>,          // Hourly-only, when not using everyN (startingHCX<n>)
    cronString: '0 * * * *',       // Cron String mode
    rawSettings: {…}               // escape hatch for periodic-page fields not yet mapped
  }
  ```
  Monthly has TWO mutually-exclusive modes: by-day (`dayOfMonth` + `everyNMonths` -- BOTH required or renders null) and nth-weekday (`weekOfMonth` + `dayOfWeek` + `everyNMonths`). Passing both `dayOfMonth` and `weekOfMonth` is rejected. Monthly "specific months" ("on day N of selected months") is NOT yet supported (an order-sensitive third sub-mode) -- use `rawSettings`. Yearly is ALWAYS nth-weekday (`weekOfMonth` + `dayOfWeek` + single `months`) because RM 5.1 exposes no by-day calendar-day field for Yearly -- only the nth-weekday picker. Without `periodic`, RM commits a phantom row with description `?`. The tool walks the periodic sub-page (`whichPeriod<N>` → `everyN`/select → time → Done, where `<N>` is the per-trigger sub-page index) so the trigger description bakes correctly. Seconds/Minutes `everyN` outside the restricted enum (and Monthly dayOfMonth+weekOfMonth) is rejected with `success=false` and a structured error.

### `addAction` capability families

For the live machine-readable per-field schema (action enums, required and optional fields), pass `addAction: {discover: true}`. The repo-side `docs/rm_action_subtype_schemas.md` is a human-readable copy of the same content generated from `_rmActionSchemaForDiscover()`; it is not fetchable from the hub.

- **Switch** (`capability='switch'`): `action='on'`/`'off'`/`'toggle'`/`'flash'` + `deviceIds`. `action='setPerMode' + deviceIds + perMode={modeIdOrName: 'on'|'off', ...}`. `action='choosePerMode' + perMode={modeIdOrName: {on: [devIds], off: [devIds]}, ...}`.
  - **NOTE:** `action='flash'` starts a flash schedule on devices that support `.flash()` (Hue groups, many Z-Wave/Zigbee dimmer modules). RM 5.1 has NO native "stop flash" action subtype — calling `switch.on`/`.off` afterward does NOT cancel the flash schedule. To stop a running flash from within a rule, use `capability='runCommand'` with `command='flashOff'` on the same device list.
- **Dimmer** (`capability='dimmer'`):
  - `setLevel` + `deviceIds` + `level` (0–100) [required] + optional `fadeSeconds`
  - `toggle` + `deviceIds` + `level` (0–100) [required — the level to set when toggling from off to on] + optional `fadeSeconds`
  - `adjust` + `deviceIds` + `adjustBy` (-100..100) [required] + optional `fadeSeconds`
  - `fade` + `deviceIds` + `targetLevel` [required] + `minutes` [required] + `direction='raise'|'lower'` + optional `intervalSeconds`
  - `stopFade` (no fields)
  - `startRaiseLower` + `deviceIds` + `direction='raise'|'lower'`
  - `stopChanging` + `deviceIds`
  - `setLevelPerMode` + `deviceIds` + `perMode={modeIdOrName: level, ...}` + optional `fadeSeconds`
- **Color** (`capability='color'`, RGBW bulbs):
  - `setColor` + `deviceIds` + `colorName` + optional `level`
  - `toggleColor` + `deviceIds` + `colorName` + optional `level`
  - `setColorPerMode` + `deviceIds` + `perMode={modeIdOrName: {color: 'Red', level: 70}, ...}`
- **Color Temperature** (`capability='colorTemp'`):
  - `setColorTemp` + `deviceIds` + `kelvin` + optional `level`
  - `toggleColorTemp` + `deviceIds` + `kelvin` + optional `level`
  - `fadeColorTemp` + `deviceIds` + `targetKelvin` + `minutes` + `direction='raise'|'lower'`
  - `stopColorTempFade` (no fields)
  - `setColorTempPerMode` + `deviceIds` + `perMode={modeIdOrName: {kelvin: 2700, level: 70}, ...}`
- **Button** (`capability='button'`, pushable-button devices): `push` + `deviceIds` + `buttonNumber`. `pushPerMode` + `deviceIds` + `perMode={modeIdOrName: buttonNumber, ...}`. `choosePerMode` + `buttonNumber` + `perMode={modeIdOrName: [deviceIds], ...}`.
- **Run Custom Action** (`capability='runCommand'`): `command` + `deviceIds` + `capabilityFilter` (default `'Switch'`) + optional `parameters=[{type:'number',value:75},...]` + optional `useLastEventDevice`. Each parameter entry may be a literal (`{type:'number', value:75}`) or variable-sourced (`{type:'number', variable:'myVar'}`); the two forms may be mixed across slots. The `type` field is lowercase (`number`, `decimal`, `string`) -- the validator at `_rmAddAction` only accepts lowercase. Calls any device-driver command (`off`, `on`, `setLevel`, `flashOff`, `refresh`, custom-driver verbs, etc.) on the device list. Use this to call commands not exposed by the higher-level capability mappings.
- **File IO** (`capability='fileWrite'`/`'fileAppend'`/`'fileDelete'`): `fileWrite` + `fileName` + `content` (overwrites). `fileAppend` + `fileName` + `content` (file must exist; `localFile` is an enum picker). `fileDelete` + `fileName`.
- **Z-Wave Polling** (`capability='zwavePoll'`): `action='start'`/`'stop'` + `deviceIds` (Z-Wave switches/dimmers only) + `target='switches'|'dimmers'`.
- **Lock** (`capability='lock'`): `action='lock'`/`'unlock'` + `deviceIds`.
- **Thermostat** (`capability='thermostat'`): `action=(any)` + `deviceIds` + optional `mode`/`fanMode`/`heatingSetpoint`/`coolingSetpoint`/`adjustHeating`/`adjustCooling`.
- **Shade/blind** (`capability='shade'`): `open`/`close`/`stop` + `deviceIds`. `setPosition` + `deviceIds` + `position` (0–100).
- **Fan** (`capability='fan'`): `setSpeed` + `deviceIds` + `speed` (low/med/high/auto/etc.). `cycle` + `deviceIds`.
  - **NOTE:** fan `setSpeed` takes a fixed enum speed only (low / medium-low / medium / medium-high / high / on / off / auto); RM has no variable-sourced fan speed (unlike dimmer `setLevel`'s `levelVariable`) because the classic wizard exposes a variable toggle only for numeric/text value fields, not enum pickers. For a variable-driven speed, use `capability='runCommand'` with `command='setSpeed'` + `parameters=[{type:'string', variable:'<varName>'}]` (per-parameter variable sourcing).
- **Mode** (`capability='mode'`): `action='setMode'` + `modeId` (Integer) OR `modeName` (String, case-insensitive). When `modeName` is supplied it is resolved to the numeric mode ID via `location.modes` before the write; an unknown name fails fast with the list of valid mode names. Use `hub_list_modes` to inspect available modes first. Note: `addAction` mode uses the `modeName` field for explicit name-based resolution; `addTrigger` mode uses the generic `state` field instead because triggers cover a superset of device-state events where a single field serves multiple capability types -- `modeName` vs `state` is an intentional surface difference, not a typo.
- **Hub Variable** (`capability='setVariable'`, alias `'variable'`): `variable` (target) + exactly ONE source mode -- `value` (numeric constant), `sourceVariable` (copy from another hub variable), `fromDevice` (`{deviceId, attribute}` -- read a device attribute), or `math` (`{left, op, right}` -- structured variable math). All variable names (`variable`, `sourceVariable`, `math` var-operands) must be existing hub variable names -- unknown names are rejected before any write. The four source modes are mutually exclusive; providing more than one is rejected. `math` binary operators (`+ - * / %`) require `right`; unary operators (`negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees`) reject `right`. A `math` operand that is a number becomes a literal constant; a string operand is a variable name. `fromDevice` reads from any hub device (not just MCP-selected); an attribute not in the device's filtered enum is rejected with `success=false` and the device's available-attribute list. See `addAction setVariable` in `docs/rm_action_subtype_schemas.md` for the full field reference.
- **Rule-local Variable** (`capability='setLocalVariable'`): identical shape and source modes to `setVariable` (`variable` target + exactly one of `value`/`sourceVariable`/`fromDevice`/`math`), EXCEPT the `variable` target is validated against the rule's LOCAL variables (`state.allLocalVars`) instead of hub globals. Use this -- not `setVariable` -- when a local and a hub variable share a name and you mean the local; it cannot silently target the global. `sourceVariable`/`math` operands may be either local or hub (RM's source picker spans both; validated against the live revealed enum). Create a local first via `addLocalVariable`; list current locals via `hub_list_rule_local_variables` (in `hub_read_rules`). The picker section headers ` --LOCAL VARIABLES--` / ` --HUB VARIABLES--` are rejected as targets.
- **Logging / Messaging**: `capability='log' + message`. `capability='notification' + deviceIds + message`. `capability='httpGet' + url`. `capability='httpPost' + url + body + optional contentType`. `capability='ping' + ip`.
- **Music/Sound** (`capability='volume'`/`'mute'`/`'chime'`/`'siren'`): `volume + deviceIds + level`. `mute + action='mute'/'unmute' + deviceIds`. `chime + deviceIds + optional playStop/soundNumber`. `siren + deviceIds + optional sirenAction`.
- **Rules** (`capability='privateBoolean'`/`'runRule'`/`'cancelTimers'`/`'pauseRule'`): `privateBoolean + ruleIds + value (Boolean)`. `runRule + ruleIds` (runs actions). `cancelTimers + ruleIds`. `pauseRule + action='pause'/'resume' + ruleIds`.
- **Activate a Scene / Room Lighting group**: RM 5.1 has no dedicated activate-scene action subtype. Each Scene / Room Lighting instance spawns an activator device with the switch capability -- activate it via the Switch action: `capability='switch' + action='on' + deviceIds=[<activatorDeviceId>]` (use `action='off'` to send an off/deactivate command, whose effect is configuration-dependent). The `activate_scene` action lives ONLY on the legacy custom rule engine (the `hub_*_custom_rule` tools / `hub_get_tool_guide(section='rules')`), not on this native addAction surface.
- **Device control**: `capability='capture' + deviceIds`. `capability='restore'` (no fields). `capability='refresh' + deviceIds`. `capability='poll' + deviceIds`. `capability='disableDevice' + action='disable'/'enable' + deviceIds`.
- **Flow control** (delay/wait/repeat/exit/comment/conditional):
  - `delay` + `hours`/`minutes`/`seconds` + optional `cancelable`/`random` OR `variable=<varName>` (variable-sourced seconds)
  - `delayPerMode` + `perMode={modeIdOrName: {hours, minutes, seconds}, ...}`
  - `cancelDelay`, `exitRule`, `stopRepeat` (no fields)
  - `comment` + `text`
  - `repeat` + `hours`/`minutes`/`seconds` + optional `times` + `stoppable`
  - `repeatWhile` + `expression={conditions:[...], operator?:..., operators?:[...]}` + optional `hours`/`minutes`/`seconds`/`times`/`stoppable`
  - `waitExpression` + `expression={...}` + optional `delay={hours,minutes,seconds}` + `useDuration=true|false`
  - `waitEvents` + `events=[{capability, deviceIds, state, andStays?}, ...]`. **LIMIT**: only ONE `waitEvents` action per rule; RM 5.1 stores wait events in global per-rule settings (not per-action), so a second `waitEvents` action silently overwrites the first. Combine multiple waits into one action's `events` array, or split into chained sub-rules.
  - `ifThen` + `expression={...}` (opens IF block; close with `endIf`)
  - `elseIf` + `expression={...}` (continues IF block; needs preceding `ifThen`)
  - `else` (no fields; needs preceding `ifThen` or `elseIf`)
  - `endIf` (no fields; closes the IF block)

### `addRequiredExpression` STPage capability list

RM 5.1 Required Expression conditions accept these `capability` values (per-condition):

- **Device-state**: `Switch`, `Motion`, `Contact`, `Lock`, `Presence`, `Smoke detector`, `Water sensor`, `Tamper alert`, `Acceleration`, `Carbon monoxide detector`, `Carbon dioxide sensor`, `Power source`, `Window Shade`
- **Numeric**: `Battery`, `Dimmer`, `Energy meter`, `Fan Speed`, `Humidity`, `Illuminance`, `Power meter`, `Temperature`, `Thermostat cool setpoint`, `Thermostat fan mode`, `Thermostat heat setpoint`, `Thermostat mode`, `Thermostat state`
- **Time-based**: `Days of week`, `Between two dates`, `Between two times`, `On a Day`
- **Hub state**: `Mode`, `Private Boolean`
- **Variable comparison**: `Variable`
- **Custom / other**: `Custom Attribute`, `Last Event Device`, `Lock codes`

Note: `Private Boolean` is only valid in Required Expressions -- it does NOT appear in the IF-expression capability list used by `ifThen`/`elseIf`/`repeatWhile`/`waitExpression`.

Note: some sensor capabilities (Water sensor, Smoke detector, Carbon monoxide detector, Tamper alert, Acceleration) report discrete events rather than a continuous enum state. Pass `state: 'wet'` / `state: 'dry'` for Water sensor, `state: 'detected'` / `state: 'clear'` for detector types (Smoke, CO, Tamper), `state: 'active'` / `state: 'inactive'` for Acceleration -- NOT a comparator-based numeric condition. Carbon dioxide sensor is intentionally EXCLUDED from the discrete-event list: the `CarbonDioxideMeasurement` capability is numeric ppm (use comparator + value), not a discrete enum; the names look superficially symmetric to Carbon monoxide detector but RM 5.1 treats them differently. See `docs/rm_action_subtype_schemas.md` for the full state-value table.

### Extended per-capability spec shapes

Applies to `addRequiredExpression.conditions[]` (STPage) and `addAction.expression.conditions[]` (doActPage); the shared walker `_rmWalkConditionReveal` handles every per-capability reveal sequence below. `addTrigger.condition` has a narrower support list (see selectTriggers note below).

- **Mode**: `{capability:'Mode', state:'Night'}` or `{capability:'Mode', modeIds:['3']}`. Walker resolves mode names to IDs via `location.modes` and writes the firmware-assigned `modes<N>` picker discovered from the live schema.
- **Between two times**: `{capability:'Between two times', start:{type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:<minutes>}, end:{...same shape}}`. Precondition: hub `location.timeZone` must be configured.
- **Variable comparison**: `{capability:'Variable', variable:'<hubVarName>', comparator:'=', value:<v>}` for a constant RHS, OR `{capability:'Variable', variable:'<hubVarName>', comparator:'=', compareToVariable:'<otherHubVarName>'}` for a variable-vs-variable RHS. For value-comparison comparators supply exactly one of `value`/`compareToVariable` -- they are mutually exclusive (passing both is rejected); omit the RHS entirely for state-change comparators (`*changed*`/`*became*`). For the variable RHS the walker toggles `isVar_<N>=true` and discovers the firmware-assigned right-hand picker from the live schema -- it does NOT hardcode `xVarR_<N>` because `selectTriggers` consistently exposes `xVarR` but the walker pages (STPage/doActPage) can expose a differently-suffixed field, so the walker resolves whatever the live schema reveals. Fail-loud when a variable name is not in the schema enum AND the option list is non-empty; degrades with an `api_unavailable` sentinel (`variable-validation` for the LHS picker, `compareToVariable-validation` for the RHS picker) when the enum is empty, flipping `partial`.
- **Device-relative comparison**: `{capability:'Temperature', deviceIds:[N], comparator:'>', compareToDevice:{deviceId:M, attribute?:'temperature', offset?:-2}}`. The RHS is another device's reading on the SAME capability, optionally offset. The walker writes the comparator `RelrDev_<N>`, toggles `isDev_<N>=true` to reveal the SINGLE reference-device picker `relDevice_<N>`, writes the reference id, then writes the optional decimal offset to `state_<N>` (omit -> offset 0). `relDevice_<N>` is a capability.* device picker locked to the LHS capability; on normal firmware RM populates its dropdown client-side, so the schema exposes no options and the empty option list is normal. The reference `deviceId` is existence-validated hub-wide before any write; a nonexistent id is rejected up front. On the rare firmware variant that DOES surface device-picker options, the walker additionally defensively rejects a reference id not in that list. Mutually exclusive with a literal RHS (`state`/`value`) and with `compareToVariable` -- supply exactly one RHS shape. There is NO separate reference-attribute picker: the compared attribute is implied by the shared capability, so `compareToDevice.attribute` is OPTIONAL and informational (no wire consumer; neither validated nor written). Passing compareToDevice on a non-numeric capability (Mode / Between two times / Variable / Custom Attribute) is rejected up front with a fail-loud error naming the capability -- it is NOT silently dropped. **Intentional isDev/isVar asymmetry (do not "fix"):** an EMPTY option list is NORMAL for `compareToDevice`'s `relDevice_<N>` because it is a capability.* DEVICE picker (RM fills it client-side), so no options, no sentinel, no partial. This deliberately differs from `compareToVariable`, whose right-hand picker is an ENUM picker where an empty option list IS an anomaly and emits an `api_unavailable` sentinel with `partial:true`. The divergence reflects picker type (device vs enum), not an oversight.
- **Sub-expression (parens) -- addRequiredExpression-only**: `{subExpression:{conditions:[...], operator?:'AND'|'OR'|'XOR', operators?:[...]}}`. The STPage walker recursively handles nesting of arbitrary depth. **`addAction` (ifThen/elseIf/repeatWhile/waitExpression) REJECTS nested subExpression** with `"nested subExpression on this row is not yet supported"`. Flatten the conditions list, or move the nested expression to a Required Expression.

`addTrigger.condition` supports a narrower subset: Variable (incl. `compareToVariable`), Custom Attribute, and enum/numeric device-state. Mode-via-picker / Between two times / compareToDevice are NOT yet supported on `selectTriggers` -- the `_rmBuildCondition` helper is a static direct-write path, not the shared `_rmWalkConditionReveal` walker.

### Supported comparison shapes for a numeric condition

A numeric device condition's right-hand side can be one of these shapes (the RM 5.1 wizard exposes the `isDev_` device-RHS toggle but no `isVar_` toggle on a numeric device condition, so "device attribute vs a hub variable" is NOT a directly-supported shape):

- a) **Device attribute vs literal value** -- `{capability:'Temperature', deviceIds:[N], comparator:'>', value:72}`.
- b) **Device attribute vs another device's same attribute** (`compareToDevice`, numeric capabilities only) -- `{capability:'Temperature', deviceIds:[N], comparator:'>', compareToDevice:{deviceId:M, offset?:-2}}`. The reference reads the SAME capability; `offset` is optional.
- c) **Variable vs variable** (`compareToVariable`, Variable-capability LHS only) -- `{capability:'Variable', variable:'<hubVarName>', comparator:'=', compareToVariable:'<otherHubVarName>'}`.

To compare a **device attribute against a hub variable**, there is no direct shape -- read the attribute into a variable first with an `addAction setVariable` using `fromDevice` (`{deviceId, attribute}`), then compare variable-vs-variable per shape (c).

### `addRequiredExpression` operator contract

Combine multiple conditions with `operator: 'AND'|'OR'|'XOR'` (one operator applied to every gap) OR `operators: ['AND','OR', ...]` (one per gap; length = `conditions.size()-1`) for mixed expressions like `P1 AND P2 OR P3 XOR P4`. RM 5.1: AND/OR/XOR have equal precedence, evaluated left-to-right.

### `replaceRequiredExpression` -- change an existing Required Expression in place

`addRequiredExpression` refuses (`requiredExpressionAlreadyExists:true`) when the rule already has a committed Required Expression. To CHANGE it, use `replaceRequiredExpression` -- same `appId`, no clone. The spec shape is IDENTICAL to `addRequiredExpression` (`{conditions:[...], operator|operators}`, all the same per-condition fields and extended per-capability shapes, including nested `subExpression`), so the replacement may be single-condition, multi-condition, or nested. Semantics are WHOLE-expression replace (the entire formula is cleared), matching `addRequiredExpression`'s add semantics.

Mechanism: clicks `cancelST` ("Delete Required Expression") to remove the whole committed expression, then builds the new condition(s) by delegating to the same `addRequiredExpression` walker (which navigates fresh from `mainPage`, sets `useST`, reaches the `cond` new-condition selector, and seals via `hasRule`/`doneST` + the sub-page Done), and fires `updateRule`.

- Precondition: a committed Required Expression MUST already exist. If none does, returns `success:false, requiredExpressionMissing:true` steering you to `addRequiredExpression` -- a replace never silently becomes an add.
- Destructive-window contract: the `cancelST` delete is immediately destructive (the committed gate is gone the instant it is clicked). Protections: (1) the ENTIRE spec is validated BEFORE the click (conditions/operator/operators rules, deviceId existence), so a malformed spec fails with the OLD expression intact; (2) after the delete succeeds, ANY failure auto-restores the pre-op backup -- INCLUDING a post-commit health flip (the rebuild baked but left the rule unhealthy, e.g. a ghost-`ifThen` clear wrapped it in `IF(**Broken Condition**)`) OR a rejected trailing `updateRule` click, because the trailing finalize runs inside the same restore window as the delete. The result then carries `requiredExpressionReplaced:false` + `requiredExpressionRestored:true` (original restored from backup) OR `requiredExpressionRestored:false` (DELETED and auto-restore also failed -- the error names the `hub_restore_backup(backupKey=...)` recovery) OR `requiredExpressionRestored:false` + `requiredExpressionRestoredAs:<newId>` (auto-restore could not reuse the original appId and recreated the rule under a NEW id -- the original appId is dead; use the new id and delete the husk). A post-delete failure is NEVER a benign no-op.
- Fail-loud: if the `cancelST` delete is silently rejected (STPage still shows the committed-expression controls), the helper restores the pre-op backup and returns `success:false` naming the step; the existing expression is preserved. Inspect via `hub_get_app_config(appId)`.
- Success envelope: `requiredExpressionReplaced:true` (a NEW expression was COMMITTED, not merely the old one deleted; may not be live yet if `updateRuleFailed` -- check `expressionNotLive`) plus the same `conditionIndices`/`settingsApplied`/`settingsSkipped`/`partial`/`repairHints` envelope and trailing-updateRule slots (`updateRuleFailed`/`expressionNotLive`/`updateRuleError`) as `addRequiredExpression`.
- Deleted-condition residue: on a SUCCESSFUL replace the deleted condition's underlying settings linger in the pool but are NOT part of the active formula -- harmless, renders cleanly, no cleanup write issued. New slot indices continue past the deleted slot.
- Committed-RE detection: a committed expression is detected by the `cancelST` + `editST` control pair (a two-field tell, intentionally narrower than `addRequiredExpression`'s three-field check because that pair is firmware-stable on 5.1.8 while `stopOnST` varies across revisions).

Also a valid `patches[]` op (reported as `op: 'replaceRequiredExpression'`). Inside a `patches[]` batch the auto-restore is scoped to a per-op snapshot taken just before the op, so a failed replace op does NOT revert earlier successful ops in the same batch.

### `addAction` variable-sourced values, not-yet-mapped capabilities, wire-format quirks

- **Variable-sourced values**: `dimmer setLevel` accepts `levelVariable:'<hubVarName>'` instead of `level`; `delay` accepts `variable:'<hubVarName>'` instead of `hours`/`minutes`/`seconds`. Both write the wizard's `uVar=true` + `xVar=<varName>` pair so the value resolves at fire time from a hub variable.
- **Not yet mapped -- use the `rawSettings` escape hatch with the `@N` index token**: HSM Arm/Disarm/Cancel-All-Alerts (separate actSubType, only present when HSM is installed and may need a different actType), Garage door open/close, Valve open/close (different lockActs subtypes, only visible with the corresponding device).
- **Wire-format quirks the helper handles for you**: (1) the 'Create New Action' button (`name=N`) requires `stateAttribute='doActN'` concatenated, not `'doAct'` -- sending `'doAct'` alone leaves `state.doActN` null and `doActPage` NPEs. (2) `doActPage`'s schema is incremental -- `actionDone` only appears after all required type-specific fields are set; the helper re-fetches the schema before each write. (3) `selectActions` initializes `state.actNdx`; on a freshly created zero-action rule `state.actNdx` is null and `doActPage` renders `actType.null` (broken), so the helper fires an idempotent empty POST to `selectActions` first.

### `walkStep` schema-aware wizard walker

`walkStep` is the lowest-level escape hatch: drive the RM wizard when the high-level `addTrigger`/`addAction` helpers don't cover the capability you need (Periodic Schedule sub-pages, conditional-trigger binding, IF/THEN/ELSE flow control, features added in a later firmware). Each single-step call returns a structured snapshot -- schema before/after, schema diff (inputs appeared/disappeared), value-echo (catches silent enum case normalization), sub-page hrefs, action/trigger list-count change (disambiguates 'committed' from 'broke and lost the row'), and a health check.

Spec: `{page, operation, write?:{<field>:<value>}, click?:{name,stateAttribute?}, navigate?:{targetPage}, validateEnum?:<bool>, hrefContext?:{fromPage,hrefName,hrefParams?,hrefIndex?}, steps?:[...]}` where `page` is e.g. `selectTriggers`/`selectActions`/`doActPage`/`mainPage`/`periodic` and `operation` is one of:
- `drive` -- **preferred**: run an ordered `steps=[...]` list (each item a single-step spec) in ONE call. The tool performs them in sequence, carrying the page forward across `navigate`/`done`, and stops at the first failed step (`stopOnError=false` to continue). A step that omits `page` inherits the page the previous step ended on. Returns `{steps:[{step, operation, page, success, diff, valueEcho, silentRejection, commitSignal, opResult, health}, ...], stepsRequested, stepsRun, lastStepOperation, success, health}`; on a halt the aggregate also carries a top-level `error` + `repairHints` naming the failed step. End the drive with a `done` step to fire the mainPage Done finalize (the `updateRule`-equivalent that re-initializes subscriptions) — the same finalize a single-step `done` gets. This automates the manual loop below.
- `introspect` -- fetch schema; no mutation.
- `write` -- write one field's value (exactly one key per call; `hrefContext` for sub-pages).
- `click` -- click a regular button (`cancelCapab`, `hasAll`, `moreCond`, ...).
- `navigate` -- forward into a sub-page via its href.
- `done` -- BACK-navigate from a sub-page to its parent (`_action_previous=Done`), carrying ALL the sub-page's current settings. REQUIRED for sub-pages (Periodic, etc.) whose parent row otherwise renders `?`. Pass `hrefContext={fromPage:<parent>, hrefParams:{n:<idx>}}`.

The loop `drive` automates (and the sequence to put in `steps[]`): `introspect` to see the page's fields -> `navigate` into a sub-page if one is exposed -> `write` each required field (with `hrefContext` on sub-pages) -> inspect `diff.appeared`/`valueEcho.match`/`silentRejection` between writes -> `done` to back out of a sub-page (this bakes the trigger/action description) -> `click` `hasAll`/`actionDone` on the parent to finalize the row. Always check `silentRejection`, `valueEcho.match`, and `health.ok` in each step's snapshot -- they are the fail-loud signals.

Worked `drive` example (a multi-device switch trigger committed in one call, the `steps[]` form of the raw-mode example below). The trailing `done` is what runs the mainPage Done finalize (raw-mode step 6, `updateRule`); without it the trigger is written to settings but never subscribed, so the rule looks created yet never fires:
```
hub_set_rule(appId=N, confirm=true, walkStep={operation:'drive', steps:[
  {page:'selectTriggers', operation:'click', click:{name:'true', stateAttribute:'moreCond'}},
  {page:'selectTriggers', operation:'write', write:{tCapab1:'Switch'}},
  {page:'selectTriggers', operation:'write', write:{tDev1:[<deviceId>, ...]}},
  {page:'selectTriggers', operation:'write', write:{tstate1:'on'}},
  {page:'selectTriggers', operation:'click', click:{name:'hasAll'}},
  {page:'selectTriggers', operation:'done'}]})
```

### Raw `settings`/`button` mode (manual wizard flow)

Prefer the structured shortcuts above. Raw mode is the unstructured escape hatch: write page inputs via `settings` and click page-transition buttons via `button` directly.

- **Auto-updateRule**: main-page `settings` writes are auto-followed by an implicit `updateRule` click so `initialize()` re-fires. Sub-page writes (`pageName=selectTriggers`/`selectActions`/...) SKIP the auto-click so the wizard's `stateAttribute` (`moreCond`, `editCond`, `editAct`, ...) survives -- commit the wizard via its own Done button (RM triggers: `hasAll`; RM actions: `actionDone`), then issue a final `hub_set_rule(button='updateRule')` yourself to re-initialize.
- **Wizard-Done auto-finalize**: clicking `hasAll` on `selectTriggers` commits the trigger but RM 5.1 leaves a residual `isCondTrig.<N>` ("Conditional Trigger?") prompt; the tool auto-writes `isCondTrig.<N>=false` to clear it without consuming a trigger index, reported as `wizardDoneAutoRetry: 'OK' | 'OK after finalize ...' | 'WARN: ...'`. (Earlier versions clicked `hasAll` twice, which allocated phantom **Broken Trigger** rows; the finalize-via-`isCondTrig` path keeps indices contiguous 1, 2, 3.)
- **Worked example -- multi-device switch trigger via raw mode**:
  1. `hub_set_rule(appId, button='true', stateAttribute='moreCond', pageName='selectTriggers')` -- opens the trigger editor.
  2. `hub_set_rule(appId, settings={tCapab1:'Switch'}, pageName='selectTriggers')` -- picks the capability; page re-renders the device picker.
  3. `hub_set_rule(appId, settings={tDev1:[<deviceId>, ...]}, pageName='selectTriggers')` -- writes devices (multi-device 3-field contract automatic).
  4. `hub_set_rule(appId, settings={tstate1:'on'}, pageName='selectTriggers')` -- sets the attribute/value.
  5. `hub_set_rule(appId, button='hasAll', pageName='selectTriggers')` -- commits; residual Conditional? prompt auto-finalized.
  6. `hub_set_rule(appId, button='updateRule')` -- re-initialize so subscriptions populate.
  The `addTrigger={...}` shortcut performs steps 1-6 automatically. A second trigger uses index 2 (`tCapab2`/`tDev2`/`tstate2`), a third index 3, etc.

### Partial-success and trailing-updateRule response slots

`settingsSkipped[]` sentinel reasons callers may see:
- `offset_field_not_revealed` -- compareToDevice optional offset field (`state_<N>`) absent after the reference-device write (firmware may not expose the offset slot for this capability); the offset is dropped but the device-relative comparison is otherwise complete. Flips `partial:true`.
- `api_unavailable` paired with `key: "variable-validation"` (LHS Variable picker) OR `key: "compareToVariable-validation"` (RHS variable picker for `compareToVariable`) -- the ENUM picker returned an empty option list; write proceeds unvalidated. Flips `partial:true`. NOTE: `compareToDevice` does NOT emit this -- its `relDevice_<N>` reference picker is a capability.* DEVICE picker that exposes no options client-side, so an empty option list is normal and is NOT treated as a validation gap (no sentinel, no partial). A wrong-capability reference device surfaces in the rendered/broken state, not a pre-write option check.
- `not_in_schema` -- a written field was absent from the current page schema, so the value did not land. Genuine degradation on addTrigger, the condition wizard, AND the walker pages (STPage/doActPage); flips `partial:true`. A state-change comparator like `*changed*` is written as a value into the comparator field on a free-valued attribute (where the comparator IS exposed), so a clean trigger produces no `not_in_schema` skip on a real field. Two exempt cases do NOT flip `partial`. (1) The cosmetic `isCondTrig.<N>` post-commit finalize toggle on addTrigger -- its absence is a clean exit, and the skip that would otherwise be produced is exempted. (2) A VALUE comparator (`=`/`<`/etc.) on the enum-recognized Custom Attribute across all FOUR wizard surfaces -- the trigger row (`ReltDev<N>`), the conditional-trigger condition wizard (`RelrDev_<N>`), the STPage walker, and the doActPage walker. Here the comparator is deliberately NOT written, so NO skip is produced in the first place (this case is exempt from `partial` by construction, not by exempting a produced skip): when the hub treats the attribute as an ENUM (switch, motion, contact, lock, ...) the re-render reveals the value picker (`tstate<N>` / `state_<N>`) and HIDES the comparator, the helper detects the picker is exposed and writes only the value, and partial stays false. The exception is a no-RHS state-change comparator (`*changed*` / `*became*`) on an enum attribute: there is no comparator slot AND no value for the picker, so unless the picker offers a change-equivalent option the comparator is unrepresentable and the helper emits a `comparator_not_representable_for_enum_attribute` skip (flips `partial`) instead of a false clean success. A free-valued attribute still reveals and writes the comparator normally. The walker's two Custom Attribute sites diverge on the neither-rendered edge case: the dedicated capability-block (Site A) throws because its reveal-step contract has no field to write into without a revealed target, whereas the default enum/numeric block (Site B) still attempts the write because its `writeST` POSTs-then-verifies (no schema-containment pre-gate) -- on a hidden field the post-write verify records a `silent_rejection` skip that flips `partial`, surfacing the degradation without hard-failing the wizard, which is less strict than Site A's throw but still honest about the value loss. (Site B normalizes the comparator and writes it comparator-first: whenever `RelrDev_<N>` is exposed, OR when neither field rendered; it suppresses the comparator only for the positively-detected enum case.) On the trigger row and condition wizard the analogous neither-rendered write goes through `_rmWriteSettingOnPage`, which DOES schema-gate: the comparator is not POSTed and a `not_in_schema` skip flips `partial` instead. On a TRANSIENT exposure-probe re-fetch failure (empty/unparseable hub response after the attribute write), all four surfaces now degrade gracefully rather than aborting: the comparator is force-written best-effort and a `comparator_force_written_unverified` skip flips `partial` (verify via `hub_get_app_config`).
- `reveal_fallback_to_existing_field` -- walker matched an already-visible field instead of a newly-revealed one (static-schema firmware). INFORMATIONAL -- does NOT flip `partial` by itself.
- `useST_idempotent_noop` -- the idempotent `useST=true` mainPage toggle (Step 1 of addRequiredExpression) was already set, so the write did not advance the schema. INFORMATIONAL -- does NOT flip `partial` by itself, because the toggle write is idempotent and the schema rejection is cosmetic (the required-expression href is already exposed), not a lost value.
- `comparator_force_written_unverified` -- on a Custom Attribute add, the exposure-probe re-fetch (issued after writing the attribute to decide whether the comparator is still exposed) failed transiently, so the comparator was force-written straight to the page as a fallback. The value is in `settingsApplied` and `success` stays true, but it could not be schema-confirmed -- flips `partial:true`. Verify via `hub_get_app_config`.
- `comparator_force_write_failed` -- the force-write fallback above ALSO failed (the hub rejected the POST, e.g. a stale version token). The comparator did not land. Genuine degradation -- flips `partial:true`. The rest of the trigger/condition still committed; re-add the comparator via `hub_set_rule(walkStep=...)` or rebuild the row.
- `comparator_not_representable_for_enum_attribute` -- a no-RHS state-change comparator (`*changed*` / the `*became*` family) was requested on a Custom Attribute the hub recognizes as an ENUM (switch/motion/contact/lock/...). RM exposes only the value picker (e.g. on/off) for such an attribute, with no comparator slot, so a no-value change comparator cannot be represented through this path. Genuine degradation -- flips `partial:true` with a repair hint. To express "this attribute changed", trigger on the device's native capability instead (e.g. `capability:'Switch'`), or use a non-built-in attribute name (RM treats those as free-valued and exposes a real comparator). Applies across all four wizard surfaces. If the value picker happens to offer a change-equivalent option, the helper routes it there and no skip is produced.

Trailing-updateRule failure slots (`addRequiredExpression`, `addTrigger`, `addLocalVariable`, `removeLocalVariable`, bulk `addTriggers`/`addActions`, `patches`, and the action/trigger mutation dispatchers):
- `addRequiredExpression` / `replaceRequiredExpression`: `updateRuleFailed: true` + `expressionNotLive: true` + `updateRuleError: <message>` when the post-commit `updateRule` click is rejected. `success` flips false and `partial` flips true. `repairHints` adds a recovery line pointing at `hub_set_rule(button='updateRule', confirm=true)`. `replaceRequiredExpression` additionally returns `requiredExpressionReplaced:true` on success and `requiredExpressionMissing:true` (success:false) when there is no committed expression to replace.
- `addTrigger`: `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The trigger row IS in the rule's appSettings but the running rule instance never re-subscribed to its device events -- retry `updateRule` to populate subscriptions.
- `addLocalVariable`: `updateRuleFailed: true` + `variableNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The variable IS created on the hub but the rule's action map never re-evaluates against the new variable until updateRule fires -- retry as above.
- `removeLocalVariable`: removes a local variable via RM's `deleteGV`/`delConfirm` wizard, then verifies it left `state.allLocalVars`. A verify miss returns `success: false` + `partial: true` + `repairHints` (the `delConfirm` commit is the fragile step; or the variable is still referenced by an action/expression -- remove those refs first). On a rejected trailing `updateRule`: `updateRuleFailed: true` + `variableNotLive: true` + `updateRuleError: <message>` -- retry as above. List current locals via `hub_list_rule_local_variables` (in `hub_read_rules`).
- `addTriggers` / `addActions` (bulk path): `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The per-item adds IS committed (triggers/actions arrays still surface on the success-shape keys) but the running rule instance never re-subscribed -- retry as above.
- `patches`: `updateRuleFailed: true` + `patchesNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The patch ops landed but the rule will not re-evaluate / re-subscribe until updateRule fires -- retry as above.
- `removeTrigger` / `modifyTrigger` / `removeAction` / `clearActions` / `replaceActions` / `moveAction`: `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The mutation IS committed but the rule never re-subscribed -- retry as above.

### deviceId vs deviceIds normalization (all condition writes)

Conditions accept either `deviceIds: [N]` (array) or singular `deviceId: N`; the dispatcher normalizes the singular form to the array RM 5.1 expects in `rDev_<N>`. A bare integer passed where the array is expected bypasses pre-validation and silently stores `{N: null}` (the rule renders but never fires), so prefer `deviceIds`. If both are supplied, `deviceIds` (array) wins. Applies recursively inside nested `subExpression.conditions[]`.

### Action-mutation defensive recovery (clearActions / replaceActions)

The action-clear path commits synchronously, but a thin verify-retry guards against a stuck `state.editAct` or a rare firmware commit lag. If the verify still sees the actions present, the response carries `asyncCommitLikely: true, partial: true` plus a `safeRecovery` block. clearActions adds `stage: 'clearActions.verify_absent', httpWriteStatus: 200, wizardStuck: false` and `actionsRequestedForRemoval` / `actionsStillPresent` / `possibleStateEditAct`. replaceActions, on a late inner-clear, sets `stage: 'replaceActions.clear_committed_late_no_add'`, does NOT attempt the add half (prevents a double-write if the clear did commit), echoes the original specs as `pendingActionsToAdd`, and exposes the inner clear fingerprint via `clearActionsResult`. Recovery for both: call `hub_get_app_config(appId)` to check whether the clear committed -- if the actions are absent it committed (for replaceActions, then call `addAction`/`addActions` with the echoed specs to finish). Do NOT call `cancelTrash`: in trash-confirmation mode it may commit pending deletes rather than abort.''',

        set_rule_create_reference: '''## `hub_set_rule` create reference

### appType options

`appType` selects which class of native app to create. NOTE: this selector belongs to `hub_set_native_app` -- `hub_set_rule` always creates `rule_machine` rules. Default: `rule_machine`.

- `rule_machine` — Rule Machine 5.1 (verified live; the only FULLY-supported type — the others have partial label/config handling).
- `button_controller`, `groups_scenes`, `notifier`, `basic_rule` — registered classic types using the same endpoint family. Other classic SmartApps (e.g. Room Lighting) can be registered in `_appTypeRegistry` to enable creation. `hub_set_native_app` / `hub_delete_native_app` already work on them today via their `appId`.
- Visual Rules are NOT created here — they are Vue-JSON apps; use `hub_set_visual_rule` (see `hub_get_tool_guide(section='visual_rule_reference')`).

### Partial-success protocol

The tool ALWAYS creates the rule shell (you get an `appId` back) even if some triggers/actions fail to fully bake. Inspect the result:

- `partial: true` + `partialTriggers: [N, ...]` / `partialActions: [N, ...]` → some pieces are incomplete (this includes any per-item result with `partial: true` OR `success: false`).
- `repairHints: [...]` → concrete next-step instructions.
- Each per-trigger / per-action result has its own `success`, `partial`, `settingsSkipped`, `repairHints`, and `health` block. `success: true, partial: true` on an inner result means the row was written but needs repair.

The right move when `partial: true` is to follow the `repairHints`, NOT to delete the rule and retry from scratch. Tool-only repair via `hub_set_rule(walkStep={...})` / `replaceActions` / `removeAction` can usually finish the job. Only declare failure after exhausting those repair attempts.''',

        visual_rule_reference: '''## Visual Rules Builder reference (`hub_get_visual_rule` / `hub_set_visual_rule` / `hub_delete_visual_rule`)

Visual Rules Builder (VRB) is the PRIMARY rule engine for new automations; each rule is stored as ONE clean JSON definition (no wizard, no settings[] protocol). A VRB rule is: one or more trigger events, an optional condition gate, and then/else action branches — if/then/else logic is fully supported (a condition node routes execution to thenNodes or elseNodes). Pretty much everything can be done with it; use `hub_set_rule` (Rule Machine) when something complex is needed — nested or multiple condition blocks, loops, variables and expressions, capture/restore, waiting on a device-state expression (VRB's `wait` waits a fixed duration), or device commands outside the action catalog below.

### Two serializations (`format` in every single-rule success response)

A VRB rule speaks exactly one of two wire formats, decided by the hub firmware at creation. `hub_get_visual_rule` reports which; an edit's `definition` must match it.

**classic** — `{whenNodes: [...], thenNodes: [...], elseNodes: [...]}` (the when/then/else editor; what current firmware creates):
- Every node: `triggerType` (or `actionType`), `deviceIds` (ALWAYS present; mirrors the per-type device array), `index` (int, 0-based per list), `type` ("when"/"then"/"else"), optional `description` (HTML label).
- whenNode example (switch trigger): `{"triggerType": "switch", "switches": [59], "deviceIds": [59], "switchEvent": "Turns off", "index": 0, "type": "when"}`
- thenNode example (turn off): `{"actionType": "turnOff", "switches": [122], "deviceIds": [122], "index": 0, "type": "then"}`
- At least one whenNode must be a REAL trigger (the builder refuses rules whose only triggers are `timeIsBetween`/`daysOfWeek`).

**graph** — `{version: 1, nodes: [...], edges: [...]}` (the dormant 2.0 graph editor):
- Node: `{id, type: "trigger"|"condition"|"action", deviceIds: [...]}` + `triggerType`/`actionType` + per-type fields. Stored graph nodes put the node KIND in `triggerCondition` and the sub-condition in `condition`.
- Edge: `{from, to, port}`. Ports: `next` (trigger/action source), `true`/`false` (condition source). Triggers have no incoming edges; conditions/actions exactly one. No cycles.
- On the wire the graph travels as a JSON STRING inside `{name, ruleJson}` — the tool handles the double-encoding for you; always pass `definition` as a normal JSON object.

### Field catalog (classic + graph dialogs share these)

Triggers (`triggerType` → device array + event field):
- `switch` → `switches`, `switchEvent`: "Turns on" | "Turns off" | "Turns on and stays on for..." | "Turns off and stays off for..." (+ `switchStaysMinutes`/`switchStaysSeconds` on the stays variants)
- `motion` → `motionSensors`, `motionSensorEvent`: "Motion starts" | "Motion stops" | "Motion stops and stays inactive for..." (+ `motionStaysMinutes`/`motionStaysSeconds`)
- `contact` → `contactSensors`, `contactSensorEvent`: "Contact opens" | "Contact closes" | "...and stays open/closed for..." (+ `contactStaysMinutes`/`contactStaysSeconds`)
- `presence` → `presenceSensors`, `presenceSensorEvent`: "Everyone leaves" | "Someone arrives"
- `lock` → `locks`, `lockEvent`: "Locked" | "Unlocked"
- `button` → `buttons`, `buttonEvent`: "Pushed" | "Held" | "Released" | "Double tapped", `buttonIndex` (int)
- `temperature`/`humidity`/`illuminance` → `temperatureSensors`/`humiditySensors`/`illuminanceSensors`, `<type>SensorEvent`: "<Type> has risen above..." | "<Type> has fallen below...", value in `temperature`/`humidity`/`illuminance`
- `power` → `powerMeters`, `powerMeterEvent` (risen above / fallen below / become and stayed above|below + `power`, `powerStaysMinutes`/`Seconds`)
- `water`/`smoke`/`co`/`acceleration`/`shock` → `<type>Sensors` + `<type>SensorEvent` (exact English sentences from the builder UI)
- `timeOfDay` → `timeOfDay`: "HHMM" colon-less string (e.g. "0730")
- `sunriseSunset` → sub-condition beforeSunrise/sunrise/afterSunrise/beforeSunset/sunset/afterSunset + `minutesBefore/AfterSunrise|Sunset`
- `systemMode` → `modes`: [mode ids from hub_list_modes]

Conditions (classic: appear as whenNodes with condition `triggerType`s; graph: `type:"condition"` nodes): `switchCondition` (`switchState`: "Turned on"|"Turned off"), `motionCondition` (`motionSensorState`: "Motion is active"|"Motion is inactive"), `contactCondition`, `presenceCondition`, `lockCondition` (`lockState`), `temperatureCondition`/`humidityCondition`/`illuminanceCondition`/`powerCondition` ("... is above..."|"... is below..." + value), `systemModeCondition` (`modes`), `timeIsBetween` (specificTimes + `startTime`/`endTime` "HHMM", or sunriseToSunset/sunsetToSunrise), `daysOfWeek` (`daysOfWeek`: [0-6], 0=Sunday).

Actions (`actionType`): `turnOn`/`turnOff`/`toggle` (`switches`), `setBrightness` (`dimmers`, `brightness` 0-100), `setColorTemp` (`colorTempBulbs`, `colorTemp` Kelvin), `setColor` (`colorBulbs`, `color` {h,s,b}), `lock`/`unlock` (`locks`), `openValve`/`closeValve`, `openGarageDoor`/`closeGarageDoor`, `openWindowShade`/`closeWindowShade`, `pushButton` (`button` single id, `buttonIndex`), `sendNotification` (`notificationDevices`, `notificationMessage`), `speakNotification` (`speechDevices`, `speakMessage`), `controlPlayer` (`musicPlayers`, `musicPlayerAction`), `controlThermostat` (`thermostats`, setMode/mode, setFanMode/fanMode, setHeatingSetpoint/heatingSetpoint, setCoolingSetpoint/coolingSetpoint), `setMode`/`setModeUnlessAway` (`mode` single id), `exitAwayMode`, `wait` (`minutes`, `seconds` — cancelable), `cancelWait`.

Gotchas: event/state strings are EXACT English sentences including the trailing "..."; `deviceIds` must mirror the per-type device array; device ids are integers from hub_list_devices; times are colon-less "HHMM" strings.

### Worked example (classic create)

hub_set_visual_rule(name="Hallway motion light", confirm=true, definition={
  "whenNodes": [{"triggerType": "motion", "motionSensors": [42], "deviceIds": [42], "motionSensorEvent": "Motion starts", "index": 0, "type": "when"}],
  "thenNodes": [{"actionType": "turnOn", "switches": [17], "deviceIds": [17], "index": 0, "type": "then"}],
  "elseNodes": []
})

Then verify with hub_get_visual_rule(appId=<returned appId>) — the response echoes the persisted definition. Pause/resume with hub_set_visual_rule(appId=N, paused=true|false, confirm=true).'''
    ]
}
