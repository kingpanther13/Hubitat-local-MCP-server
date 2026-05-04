/**
 * MCP Rule Server for Hubitat
 *
 * A native MCP (Model Context Protocol) server that runs directly on Hubitat
 * with a built-in custom rule engine for creating automations via Claude.
 *
 * Version: 0.11.0 - Enriched list_devices summary + server-side filter (disabled, enabled, stale:N)
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

preferences {
    page(name: "mainPage")
    page(name: "confirmDeletePage")
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
            }
        }

        section("Device Access") {
            input "selectedDevices", "capability.*", title: "Select Devices for MCP Access",
                  multiple: true, required: false, submitOnChange: true
            if (selectedDevices) {
                paragraph "Selected ${selectedDevices.size()} devices"
            }
        }

        section("Hub Admin Access") {
            paragraph "<b>Hub Admin Tools</b> provide read and write access to hub configuration, installed apps/drivers, Z-Wave/Zigbee radios, and hub management operations."
            paragraph "<i>These tools use the hub's internal API and may require Hub Security credentials if Hub Security is enabled.</i>"
            input "enableHubAdminRead", "bool", title: "Enable Hub Admin Read Tools",
                  description: "Allows MCP to read hub details, installed apps/drivers, Z-Wave/Zigbee info, and hub health metrics",
                  defaultValue: false, submitOnChange: true
            input "enableHubAdminWrite", "bool", title: "Enable Hub Admin Write Tools",
                  description: "Allows MCP to reboot, shutdown, create backups, and run Z-Wave repair",
                  defaultValue: false, submitOnChange: true
            if (settings.enableHubAdminWrite) {
                paragraph "<b style='color: red;'>⚠ WARNING: Hub Admin Write tools can reboot, shut down, or modify your hub. " +
                          "A backup is MANDATORY before any write operation. The AI assistant is instructed to create a backup " +
                          "before every write operation and will refuse to proceed without one.</b>"
            }
        }

        section("Built-in App Integration") {
            paragraph "<b>Built-in App Tools</b> expose read-only visibility into Hubitat's built-in apps (Rule Machine, Room Lighting, Scenes, Mode Manager, etc.) and allow controlling Rule Machine rules via the official <code>hubitat.helper.RMUtils</code> API."
            paragraph "<i>Hubitat's platform blocks creating, modifying, or deleting built-in app instances from third-party apps. Use the native UI for configuration. These tools are read + trigger only.</i>"
            input "enableBuiltinAppRead", "bool", title: "Enable Built-in App Tools",
                  description: "Allows MCP to list all installed apps (built-in + user), find apps using a device, list Rule Machine rules, and trigger/pause/resume RM rules",
                  defaultValue: false, submitOnChange: true
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
            input "enableRuleEngine", "bool", title: "Enable Rule Engine", defaultValue: true
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

def appButtonHandler(btn) {
    if (btn == "confirmDeleteBtn" && state.ruleToDelete) {
        def childApp = getChildAppById(state.ruleToDelete)
        if (childApp) {
            def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
            deleteChildApp(state.ruleToDelete)
            log.info "Deleted rule: ${ruleName}"
        }
        state.remove("ruleToDelete")
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
    state.remove("toolSearchCorpus")  // Invalidate BM25 search cache on app update
    initialize()
}

def uninstalled() {
    log.info "MCP Rule Server uninstalled"
}

def initialize() {
    if (!state.accessToken) {
        createAccessToken()
        log.info "Created access token"
    }
    if (!state.ruleVariables) {
        state.ruleVariables = [:]
    }
    // Schedule daily version update check at 3am and run immediately
    schedule("0 0 3 ? * *", "checkForUpdate")
    checkForUpdate()
}

// ==================== MCP REQUEST HANDLERS ====================

mappings {
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

def handleHealth() {
    def ver = currentVersion()
    return render(contentType: "application/json", data: """{"status":"ok","server":"hubitat-mcp-rule-server","version":"${ver}"}""")
}

def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: '{"error":"GET not supported, use POST"}')
}

def handleMcpRequest() {
    def requestBody
    try {
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

    logDebug("MCP Request: ${requestBody}")

    def response
    if (requestBody instanceof List) {
        // Bug fix: empty batch array must return error per JSON-RPC 2.0 spec
        if (requestBody.isEmpty()) {
            response = jsonRpcError(null, -32600, "Invalid Request: empty batch array")
        } else {
            response = requestBody.collect { msg -> processJsonRpcMessage(msg) }.findAll { it != null }
        }
    } else {
        response = processJsonRpcMessage(requestBody)
    }

    // Per JSON-RPC 2.0 spec: if no response objects (all notifications), return nothing
    if (response == null || (response instanceof List && response.isEmpty())) {
        return render(status: 204, contentType: "application/json", data: "")
    }

    def jsonResponse = groovy.json.JsonOutput.toJson(response)

    // Safety guard: hub enforces 128KB response limit — use byte length for accurate sizing
    def maxResponseSize = 124000 // Leave 4KB headroom under 128KB limit
    // Only compute byte length for large responses (avoid byte array allocation for small ones)
    def responseBytes = jsonResponse.length() > (maxResponseSize - 8000) ? jsonResponse.getBytes("UTF-8").length : jsonResponse.length()
    if (responseBytes > maxResponseSize) {
        mcpLog("error", "system", "MCP response too large: ${responseBytes} bytes (limit ${maxResponseSize}). Returning error instead.")
        def errResp = jsonRpcError(
            (response instanceof Map) ? response.id : null,
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

def handleNotification(msg) {
    logDebug("MCP Notification: ${msg.method}")
}

def handleInitialize(msg) {
    def info = [
        name: "hubitat-mcp-rule-server",
        version: currentVersion()
    ]
    if (state.updateCheck?.updateAvailable) {
        info.updateAvailable = state.updateCheck.latestVersion
    }
    return jsonRpcResult(msg.id, [
        protocolVersion: "2024-11-05",
        capabilities: [
            tools: [:]
        ],
        serverInfo: info
    ])
}

def handleToolsList(msg) {
    return jsonRpcResult(msg.id, [tools: getToolDefinitions()])
}

def handleToolsCall(msg) {
    def toolName = msg.params?.name
    def args = msg.params?.arguments ?: [:]

    if (!toolName) {
        return jsonRpcError(msg.id, -32602, "Invalid params: tool name required")
    }

    try {
        def result = executeTool(toolName, args)
        return jsonRpcResult(msg.id, [content: [[type: "text", text: groovy.json.JsonOutput.toJson(result)]]])
    } catch (IllegalArgumentException e) {
        mcpLog("warn", "server", "Validation error in ${toolName}: ${e.message}", null, [
            details: [tool: toolName, error: e.message]
        ])
        return jsonRpcError(msg.id, -32602, "Invalid params: ${e.message}")
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

// ==================== CATEGORY GATEWAY PROXY ====================
// Domain-named gateways that consolidate lesser-used tools behind a single MCP tool per domain.
// Each gateway: call with no args → catalog of tool schemas; call with tool + args → execute.
// Modeled after ha-mcp PR #637 (category gateway proxy pattern).

def getGatewayConfig() {
    return [
        manage_rules_admin: [
            description: "Rule administration: delete, test, export, import, and clone rules.",
            tools: ["delete_rule", "test_rule", "export_rule", "import_rule", "clone_rule"],
            summaries: [
                delete_rule: "Permanently delete a rule (auto-backs up first). Args: ruleId",
                test_rule: "Dry-run a rule without executing actions. Args: ruleId",
                export_rule: "Export rule to JSON for backup/sharing. Args: ruleId",
                import_rule: "Import rule from exported JSON. Args: exportData (JSON string)",
                clone_rule: "Clone an existing rule (starts disabled). Args: ruleId"
            ],
            // BM25 search hints — extra keywords that don't appear in summaries but help discovery
            searchHints: [
                delete_rule: "remove automation",
                test_rule: "simulate preview validate check automation",
                export_rule: "save download share automation",
                import_rule: "load upload restore automation",
                clone_rule: "copy duplicate automation"
            ]
        ],
        manage_hub_variables: [
            description: "Manage hub connector and rule engine variables.",
            tools: ["list_variables", "get_variable", "set_variable", "delete_variable"],
            summaries: [
                list_variables: "List all hub connector and rule engine variables",
                get_variable: "Get a variable value. Args: name",
                set_variable: "Set a variable value (creates if doesn't exist). Args: name, value",
                delete_variable: "Permanently delete a rule engine variable (DESTRUCTIVE). Args: name, confirm=true, [force=true if rules reference it]"
            ],
            searchHints: [
                list_variables: "show all global state connector",
                get_variable: "read fetch lookup global state",
                set_variable: "write update change store global state",
                delete_variable: "remove drop destroy purge cleanup orphan stranded BAT_ stale variable"
            ]
        ],
        manage_rooms: [
            description: "Manage hub rooms: list, view details, create, delete, and rename rooms.",
            tools: ["list_rooms", "get_room", "create_room", "delete_room", "rename_room"],
            summaries: [
                list_rooms: "List all rooms with IDs, names, and device counts",
                get_room: "Get room details with assigned devices. Args: room (name or ID)",
                create_room: "Create a new room. Args: name, confirm=true",
                delete_room: "Permanently delete a room. Args: room (name or ID), confirm=true",
                rename_room: "Rename a room. Args: room (name or ID), newName, confirm=true"
            ],
            searchHints: [
                list_rooms: "show all locations areas groups",
                get_room: "view location area group",
                create_room: "add new location area group",
                delete_room: "remove location area group",
                rename_room: "change name location area group"
            ]
        ],
        // Option A: Virtual device tools moved to core tools/list (full inputSchema visible)
        // manage_hub_info dissolved — zwave/zigbee moved to manage_diagnostics, check_for_update promoted to core
        // create_hub_backup promoted to core, zwave_repair moved to manage_diagnostics
        manage_destructive_hub_ops: [
            description: "DESTRUCTIVE hub operations: reboot, shutdown, and permanent device deletion. All operations are irreversible or cause significant downtime — confirm with user first.",
            tools: ["reboot_hub", "shutdown_hub", "delete_device"],
            summaries: [
                reboot_hub: "Reboot the hub (DISRUPTIVE, 1-3 min downtime). Args: confirm=true",
                shutdown_hub: "Power OFF the hub (EXTREME, requires physical restart). Args: confirm=true",
                delete_device: "Permanently delete any device (MOST DESTRUCTIVE, no undo). Args: deviceId, confirm=true"
            ],
            searchHints: [
                reboot_hub: "restart reset power cycle",
                shutdown_hub: "power off turn off stop halt",
                delete_device: "remove ghost orphan zwave zigbee stuck failed pairing"
            ]
        ],
        // Option B: manage_apps_drivers split into browse (read) + changes (write)
        manage_apps_drivers: [
            description: "Browse installed apps and drivers: list, view source code, and view code backups.",
            tools: ["list_hub_apps", "list_hub_drivers", "get_app_source", "get_driver_source", "list_item_backups", "get_item_backup"],
            summaries: [
                list_hub_apps: "List all installed apps on the hub",
                list_hub_drivers: "List all installed drivers on the hub",
                get_app_source: "Get app Groovy source code. Args: appId",
                get_driver_source: "Get driver Groovy source code. Args: driverId",
                list_item_backups: "List auto-created source code backups",
                get_item_backup: "Get source from a backup. Args: backupId"
            ],
            searchHints: [
                list_hub_apps: "show installed applications integrations",
                list_hub_drivers: "show installed device handlers types",
                get_app_source: "view read application groovy code",
                get_driver_source: "view read device handler type groovy code",
                list_item_backups: "show saved previous versions revisions",
                get_item_backup: "view read saved previous version revision"
            ]
        ],
        manage_app_driver_code: [
            description: "Install, update, and delete hub apps and drivers. All operations modify hub code and require Hub Admin Write.",
            tools: ["install_app", "install_driver", "update_app_code", "update_driver_code", "delete_app", "delete_driver", "restore_item_backup"],
            summaries: [
                install_app: "Install new app from Groovy source. Args: source, confirm=true",
                install_driver: "Install new driver from Groovy source. Args: source, confirm=true",
                update_app_code: "Modify existing app code (CRITICAL). Args: appId, source|sourceFile|resave, confirm=true",
                update_driver_code: "Modify existing driver code (CRITICAL). Args: driverId, source|sourceFile|resave, confirm=true",
                delete_app: "Permanently delete an app (DESTRUCTIVE). Args: appId, confirm=true",
                delete_driver: "Permanently delete a driver (DESTRUCTIVE). Args: driverId, confirm=true",
                restore_item_backup: "Restore app/driver to backed-up version. Args: backupId, confirm=true"
            ],
            searchHints: [
                install_app: "add new application integration groovy",
                install_driver: "add new device handler type groovy",
                update_app_code: "modify change edit application groovy push deploy",
                update_driver_code: "modify change edit device handler type groovy push deploy",
                delete_app: "remove uninstall application integration",
                delete_driver: "remove uninstall device handler type",
                restore_item_backup: "rollback revert undo previous version"
            ]
        ],
        // Option B: manage_logs_diagnostics split into logs + diagnostics
        manage_logs: [
            description: "System logs, performance stats, and log settings: hub logs, device/app performance stats, scheduled jobs, device event history, MCP debug logs, and log level configuration.",
            tools: ["get_hub_logs", "get_device_history", "get_performance_stats", "get_hub_jobs", "get_debug_logs", "clear_debug_logs", "set_log_level", "get_logging_status"],
            summaries: [
                get_hub_logs: "Get Hubitat system logs, most recent first. Args: level (debug/info/warn/error), source (substring), deviceId or appId (server-side scope), limit",
                get_device_history: "Get device event history (up to 7 days). Args: deviceId, hours, attribute",
                get_performance_stats: "Get device/app performance stats (count, % busy, total ms, state size, events, large state flag). Args: type (device/app/both), sortBy (pct/count/stateSize/totalMs/name), limit",
                get_hub_jobs: "Get scheduled jobs, running jobs, and hub actions",
                get_debug_logs: "Get MCP internal debug logs. Args: level, limit",
                clear_debug_logs: "Clear all MCP debug log entries",
                set_log_level: "Set minimum log level threshold. Args: level (debug/info/warn/error)",
                get_logging_status: "Get logging system status and capacity"
            ],
            searchHints: [
                get_hub_logs: "errors warnings messages trace syslog output print recent latest newest device app scope",
                get_device_history: "events timeline past activity what happened sensor",
                get_performance_stats: "slow cpu busy resource usage hog bottleneck",
                get_hub_jobs: "scheduled cron timer recurring what is running next automation",
                get_debug_logs: "mcp internal troubleshoot trace",
                clear_debug_logs: "wipe reset mcp internal",
                set_log_level: "verbosity debug trace quiet",
                get_logging_status: "buffer capacity how many"
            ]
        ],
        manage_diagnostics: [
            description: "Health monitoring, diagnostics, and radio details: hub metrics, memory history, garbage collection, device health, rule diagnostics, radio info, Z-Wave repair, and state snapshots.",
            tools: ["get_set_hub_metrics", "get_memory_history", "force_garbage_collection", "device_health_check", "get_rule_diagnostics", "get_zwave_details", "get_zigbee_details", "zwave_repair", "list_captured_states", "delete_captured_state", "clear_captured_states"],
            summaries: [
                get_set_hub_metrics: "Record/retrieve hub metrics (memory, temp, DB) with CSV trend history. Args: recordSnapshot, trendPoints",
                get_memory_history: "Get free OS memory and CPU load history. Returns most recent entries with summary stats. Args: limit (default 100, 0 for all). Requires Hub Admin Read",
                force_garbage_collection: "Force JVM garbage collection to reclaim memory. Returns before/after free memory. Requires Hub Admin Read",
                device_health_check: "Check all devices for stale/offline status",
                get_rule_diagnostics: "Comprehensive rule diagnostics. Args: ruleId",
                get_zwave_details: "Z-Wave radio info (firmware, SDK, device count). Requires Hub Admin Read",
                get_zigbee_details: "Zigbee radio info (channel, PAN ID, device count). Requires Hub Admin Read",
                zwave_repair: "Z-Wave network repair (⚠️ DISRUPTIVE, 5-30 min, devices unresponsive). Args: confirm=true",
                list_captured_states: "List saved device state snapshots",
                delete_captured_state: "Delete a specific captured state. Args: stateId",
                clear_captured_states: "Clear all captured device states"
            ],
            searchHints: [
                get_set_hub_metrics: "temperature database size trending monitoring over time",
                get_memory_history: "ram free used leak trending over time java heap nio",
                force_garbage_collection: "free reclaim ram cleanup java heap",
                device_health_check: "stale offline dead unresponsive battery not reporting",
                get_rule_diagnostics: "automation troubleshoot broken not working debug why",
                get_zwave_details: "zwave mesh network frequency firmware 908mhz 700 800 series",
                get_zigbee_details: "zigbee mesh network channel pan coordinator 2400mhz",
                zwave_repair: "fix heal network mesh routing neighbor rebuild",
                list_captured_states: "saved snapshot bookmark remember device values",
                delete_captured_state: "remove saved snapshot bookmark",
                clear_captured_states: "remove all saved snapshots bookmarks"
            ]
        ],
        manage_files: [
            description: "Manage hub File Manager: list, read, write, and delete files stored on the hub.",
            tools: ["list_files", "read_file", "write_file", "delete_file"],
            summaries: [
                list_files: "List files in File Manager (names, sizes, URLs)",
                read_file: "Read file content. Args: fileName, offset, limit",
                write_file: "Write file to File Manager. Args: fileName, content, confirm=true",
                delete_file: "Delete file from File Manager. Args: fileName, confirm=true"
            ],
            searchHints: [
                list_files: "show uploaded stored csv json text data",
                read_file: "view open contents download stored data",
                write_file: "upload save store create csv json text data",
                delete_file: "remove clean up stored data"
            ]
        ],
        manage_installed_apps: [
            description: "Read-only visibility into all installed apps (built-in + user): enumerate apps with parent/child tree, find apps using a device, inspect an app's configuration page, list page names for multi-page apps. Requires Built-in App Tools enabled in MCP app settings (list/device-in-use-by); get_app_config and list_app_pages require Hub Admin Read.",
            tools: ["list_installed_apps", "get_device_in_use_by", "get_app_config", "list_app_pages"],
            summaries: [
                list_installed_apps: "List all installed apps with parent/child tree. Args: filter (all/builtin/user/disabled/parents/children), includeHidden",
                get_device_in_use_by: "List all apps that reference a device (Room Lighting, Rule Machine, Groups, etc.). Args: deviceId",
                get_app_config: "Read an installed app's configuration page (sections, inputs, current values). Works for Rule Machine, Room Lighting, Basic Rules, HPM, etc. Args: appId, pageName (optional), includeSettings (optional)",
                list_app_pages: "List known page names for a multi-page app (HPM, Room Lighting, etc.). Curated directory + live primary page. Args: appId"
            ],
            searchHints: [
                list_installed_apps: "rule machine room lighting scenes mode manager hsm dashboards groups button controllers native builtin",
                get_device_in_use_by: "which apps use device reference inUseBy appsUsing dependencies affected by",
                get_app_config: "read inspect app configuration page settings inputs values rule machine room lighting hpm mode manager",
                list_app_pages: "page names sub-pages pageName multi-page hpm prefPkgUninstall prefPkgModify prefOptions navigation discover"
            ]
        ],
        manage_rule_machine: [
            description: "Rule Machine interoperability: list, trigger, pause/resume, and set boolean variables on existing RM rules via the official RMUtils API. Cannot create, modify, or delete RM rules (platform blocks this — use the RM native UI). Requires Built-in App Tools enabled.",
            tools: ["list_rm_rules", "run_rm_rule", "pause_rm_rule", "resume_rm_rule", "set_rm_rule_boolean"],
            summaries: [
                list_rm_rules: "List all Rule Machine rules (RM 4.x + 5.x) with IDs and labels",
                run_rm_rule: "Trigger a Rule Machine rule. Args: ruleId, action (rule/actions/stop, default rule)",
                pause_rm_rule: "Pause a Rule Machine rule. Args: ruleId",
                resume_rm_rule: "Resume a paused Rule Machine rule. Args: ruleId",
                set_rm_rule_boolean: "Set an RM rule's private boolean to true or false. Args: ruleId, value (bool)"
            ],
            searchHints: [
                list_rm_rules: "rule machine rules native builtin automation",
                run_rm_rule: "trigger fire execute native rule machine rule",
                pause_rm_rule: "disable stop temporarily rule machine rule",
                resume_rm_rule: "enable unpause restart rule machine rule",
                set_rm_rule_boolean: "private boolean flag rule machine rule condition"
            ]
        ],
        manage_mcp_self: [
            description: "Developer Mode self-administration: tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration, scope, and operational state without manual UI intervention. Requires `enableDeveloperMode` toggle in the MCP rule app settings (default OFF). Each write is logged at WARN level for audit. First gateway under the Developer Mode pattern — additional self-admin tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same toggle.",
            tools: ["update_mcp_settings"],
            summaries: [
                update_mcp_settings: "Update one or more of the MCP rule app's own settings (toggles, log level, tuning params). Args: settings (map of key→value), confirm=true. Allowlist-gated."
            ],
            searchHints: [
                update_mcp_settings: "self-admin developer mode toggle setting log level tuning loopGuard maxCapturedStates enableHubAdminRead enableBuiltinAppRead enableRuleEngine ci automation"
            ]
        ]
    ]
}

def handleGateway(gatewayName, toolName, toolArgs) {
    def config = getGatewayConfig()[gatewayName]
    if (!config) {
        throw new IllegalArgumentException("Unknown gateway: ${gatewayName}")
    }

    if (!toolName) {
        // Catalog mode: return full schemas for all tools in this gateway
        def defMap = getAllToolDefinitions().collectEntries { [(it.name): it] }

        return [
            gateway: gatewayName,
            mode: "catalog",
            message: "Call again with tool='<name>' and args={...} to execute a tool.",
            tools: config.tools.collect { name ->
                def d = defMap[name]
                [name: name, description: d?.description, inputSchema: d?.inputSchema]
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
    if (getGatewayConfig().containsKey(toolName)) {
        throw new IllegalArgumentException("Cannot call a gateway from within a gateway")
    }

    // Option D: Pre-validate required parameters and return helpful error with full schema
    def safeArgs = toolArgs ?: [:]
    def defMap = getAllToolDefinitions().collectEntries { [(it.name): it] }
    def toolDef = defMap[toolName]
    if (toolDef?.inputSchema?.required) {
        def missing = toolDef.inputSchema.required.findAll { !safeArgs.containsKey(it) }
        if (missing) {
            def props = toolDef.inputSchema.properties ?: [:]
            def paramList = props.collect { pName, pDef ->
                def req = toolDef.inputSchema.required.contains(pName) ? "REQUIRED" : "optional"
                def hint = "  ${pName} (${pDef.type ?: 'any'}, ${req})"
                if (pDef.enum) hint += " — one of: ${pDef.enum.join(', ')}"
                else if (pDef.description) hint += " — ${pDef.description}"
                hint
            }.join("\n")
            return [
                isError: true,
                error: "Missing required parameter(s): ${missing.join(', ')}",
                tool: toolName,
                parameters: paramList
            ]
        }
    }

    return executeTool(toolName, safeArgs)
}

// Returns tool definitions visible to the MCP client (base tools + gateway tools)
def getToolDefinitions() {
    def gatewayConfig = getGatewayConfig()
    def proxiedNames = gatewayConfig.values().collectMany { it.tools } as Set

    // Base tools: all tools NOT behind a gateway
    def baseTools = getAllToolDefinitions().findAll { !proxiedNames.contains(it.name) }

    // Gateway tools: one tool per gateway
    def gatewayTools = gatewayConfig.collect { gwName, config ->
        def catalog = config.tools.collect { toolName ->
            "- ${toolName}: ${config.summaries[toolName]}"
        }.join("\n")

        [
            name: gwName,
            description: "${config.description}\n\nCall with no args to see full parameter schemas. Call with tool='<name>' and args={...} to execute.\n\nAvailable tools:\n${catalog}",
            inputSchema: [
                type: "object",
                properties: [
                    tool: [type: "string", description: "Tool to execute. Omit to see full schemas for all tools in this group.", enum: config.tools],
                    args: [type: "object", description: "Arguments for the tool. Call with just tool name first to see required parameters."]
                ]
            ]
        ]
    }

    return baseTools + gatewayTools
}

// Returns ALL tool definitions (used internally by gateway catalog and executeTool dispatch)
def getAllToolDefinitions() {
    return [
        // Device Tools
        [
            name: "list_devices",
            description: """List all devices available to MCP with current states.

DEVICE AUTHORIZATION: Exact name match → use directly. No exact match → suggest similar, ASK USER before using. NEVER control unconfirmed devices (HVAC/locks risk). Report tool failures; don't silently fall back to existing devices.

Use detailed=false for discovery; detailed=true with limit=20-30. Sequential calls only.

Summary response always includes: id, name (driver type), label (user name), room, disabled (bool), deviceNetworkId, lastActivity (ISO timestamp), parentDeviceId (or null). Summary mode also returns currentStates (dict); detailed mode replaces currentStates with capabilities, attributes, and commands. Use filter to narrow on common patterns (much more efficient than fetching all and client-side filtering). To count children of a parent device, group the response by parentDeviceId.""",
            inputSchema: [
                type: "object",
                properties: [
                    detailed: [type: "boolean", description: "Include full device details (capabilities, all attributes, commands). WARNING: Resource-intensive for large device counts. Use with pagination (limit parameter) for best performance."],
                    offset: [type: "integer", description: "Start from device at this index (0-based). Use for pagination.", default: 0],
                    limit: [type: "integer", description: "Maximum number of devices to return. Recommended: 20-30 for detailed=true, higher values may slow hub.", default: 0],
                    filter: [type: "string", description: "Server-side filter (applied before pagination). 'all' (default) | 'enabled' | 'disabled' | 'stale:<hours>' (e.g. 'stale:24' for devices with no activity in the last 24 hours; never-reported devices count as stale). For filtering by room/label/capability, omit filter and use client-side logic on the returned list."]
                ]
            ]
        ],
        [
            name: "get_device",
            description: """Get detailed information about a specific device.

Only query devices the user has mentioned or that are relevant to their request. Do not probe random devices.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from list_devices"]
                ],
                required: ["deviceId"]
            ]
        ],
        [
            name: "get_attribute",
            description: """Get a specific attribute value from a device.

Only query devices the user has mentioned or that are relevant to their request.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from list_devices"],
                    attribute: [type: "string", description: "Attribute name"]
                ],
                required: ["deviceId", "attribute"]
            ]
        ],
        [
            name: "send_command",
            description: """Send a command to a device. Always verify state changed after.

If no exact device match: suggest similar devices and get user confirmation before sending any command.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from list_devices - must be confirmed by user if not an exact match"],
                    command: [type: "string", description: "Command name"],
                    parameters: [type: "array", description: "Command parameters", items: [type: "string"]]
                ],
                required: ["deviceId", "command"]
            ]
        ],
        [
            name: "get_device_events",
            description: "Get recent events for a device. Default limit 10; higher values (50+) may slow hub.",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    limit: [type: "integer", description: "Max events to return. Recommended: 10-50 for best performance. Higher values may slow hub.", default: 10]
                ],
                required: ["deviceId"]
            ]
        ],
        // Rule Management
        [
            name: "list_rules",
            description: "List all MCP automation rules. Returns summary; use get_rule for details.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_rule",
            description: "Get detailed information about a specific rule",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "create_rule",
            description: """Create a new automation rule. Use get_tool_guide section=rules for structure, syntax, and examples.

Trigger types: device_event (supports duration, multi-device), button_event, time (HH:mm/sunrise/sunset+offset), periodic, mode_change, hsm_change
Condition types: device_state, device_was, time_range, mode, variable, days_of_week, sun_position, hsm_status
Action types: device_command, toggle_device, activate_scene, set_variable, set_local_variable, set_mode, set_hsm, delay, if_then_else, cancel_delayed, repeat, stop, log, set_thermostat, http_request, speak, comment, set_valve, set_fan_speed, set_shade, variable_math

Verify rule after creation.""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Rule name"],
                    description: [type: "string", description: "Rule description"],
                    enabled: [type: "boolean", description: "Enable rule immediately", default: true],
                    testRule: [type: "boolean", description: "Mark as test rule - will NOT be backed up on deletion. Use for temporary/experimental rules.", default: false],
                    triggers: [type: "array", description: "List of triggers"],
                    conditions: [type: "array", description: "List of conditions"],
                    conditionLogic: [type: "string", enum: ["all", "any"], default: "all"],
                    actions: [type: "array", description: "List of actions"]
                ],
                required: ["name", "triggers", "actions"]
            ]
        ],
        [
            name: "update_rule",
            description: "Update an existing rule. Use enabled=true/false to enable/disable. Always verify changes after.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"],
                    name: [type: "string"],
                    description: [type: "string"],
                    enabled: [type: "boolean"],
                    testRule: [type: "boolean", description: "Mark as test rule - will NOT be backed up on deletion"],
                    triggers: [type: "array"],
                    conditions: [type: "array"],
                    conditionLogic: [type: "string", enum: ["all", "any"]],
                    actions: [type: "array"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "delete_rule",
            description: "DESTRUCTIVE: Permanently delete a rule. Automatically saves a backup to File Manager (mcp_rule_backup_*.json) before deletion. Rules marked as testRule=true skip backup automatically.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"],
                    confirm: [type: "boolean", description: "REQUIRED: Set to true to confirm deletion."],
                    skipBackupCheck: [type: "boolean", description: "Force skip backup even for non-test rules. Rarely needed since testRule flag handles this. Default: false."]
                ],
                required: ["ruleId", "confirm"]
            ]
        ],
        // enable_rule and disable_rule merged into update_rule (use enabled=true/false)
        [
            name: "test_rule",
            description: "Test a rule without executing actions (dry run)",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"]
                ],
                required: ["ruleId"]
            ]
        ],
        // System Tools
        [
            name: "get_hub_info",
            description: "Get comprehensive hub info: model, firmware, uptime, memory, temperature, database size, MCP stats, and settings. Location/PII data (name, IP, timezone, coordinates, zip code) requires Hub Admin Read.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "get_modes",
            description: "Get available location modes and current mode",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "set_mode",
            description: "Set the location mode. Always verify mode changed after.",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", description: "Mode name"]
                ],
                required: ["mode"]
            ]
        ],
        [
            name: "list_variables",
            description: "List all hub connector and rule engine variables",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "get_variable",
            description: "Get a variable value",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name"]
                ],
                required: ["name"]
            ]
        ],
        [
            name: "set_variable",
            description: "Set a variable value (creates if doesn't exist). Always verify value after.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name"],
                    value: [type: "string", description: "Variable value (string, number, or boolean as string)"]
                ],
                required: ["name", "value"]
            ]
        ],
        [
            name: "delete_variable",
            description: "Permanently delete a rule engine variable (DESTRUCTIVE — no undo). Variable must exist. Use the Settings → Hub Variables UI for connector-namespace variable deletion (separate namespace, not yet exposed via MCP).\n\nGated on requireHubAdminWrite + recent backup. Useful for sweeping orphaned BAT_E2E_* artifacts after CI runs, removing stale lease variables, or general cleanup.\n\n**Reference safety:** the tool scans every child rule app for serialized references to this variable name (in triggers/conditions/actions) and refuses by default if any are found — deletion would silently break those rules (null lookups → false conditions, literal `%varname%` left in substitutions). To proceed anyway, pass `force=true` after acknowledging the breakage. The response includes a `brokenConsumers` field listing the affected rules when force=true.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to confirm the deletion"],
                    force: [type: "boolean", description: "OPTIONAL: must be true to proceed when one or more child rule apps reference this variable. Without force, the tool refuses and lists the consumers."]
                ],
                required: ["name", "confirm"]
            ]
        ],
        [
            name: "update_mcp_settings",
            description: "Update one or more of the MCP rule app's own settings (toggles, log levels, tuning parameters). First tool under the Developer Mode self-administration surface — additional Developer Mode tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same `enableDeveloperMode` gate.\n\nGated on `enableDeveloperMode` + requireHubAdminWrite + recent backup. Every successful write is logged at WARN level for audit.\n\nAllowlisted settings (intentionally conservative for v1): mcpLogLevel, debugLogging, maxCapturedStates, loopGuardMax, loopGuardWindowSec, enableHubAdminRead, enableBuiltinAppRead, enableRuleEngine.\n\nExcluded from v1 allowlist (require future explicit security-model discussion): enableHubAdminWrite (footgun: would disable own write path mid-session), enableDeveloperMode (lockout protection — must remain UI-only to disable), selectedDevices (different wire format, will get its own tool).\n\nAfter changing any enable* toggle, MCP clients (Claude Code, etc.) may need to restart their connection to refresh the cached tool schema.",
            inputSchema: [
                type: "object",
                properties: [
                    settings: [type: "object", description: "Map of setting key → new value (e.g., {\"mcpLogLevel\":\"warn\",\"enableRuleEngine\":true})"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to confirm the operation"]
                ],
                required: ["settings", "confirm"]
            ]
        ],
        [
            name: "get_hsm_status",
            description: "Get the current HSM (Hubitat Safety Monitor) status",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "set_hsm",
            description: "Set HSM mode (armAway, armHome, armNight, disarm). Always verify HSM changed after.",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", description: "HSM mode: armAway, armHome, armNight, disarm"]
                ],
                required: ["mode"]
            ]
        ],
        // Captured State Management
        [
            name: "list_captured_states",
            description: "List captured device states. Storage limit configurable (default 20); oldest auto-deleted when full.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "delete_captured_state",
            description: "Delete a specific captured device state by its stateId.",
            inputSchema: [
                type: "object",
                properties: [
                    stateId: [type: "string", description: "The ID of the captured state to delete"]
                ],
                required: ["stateId"]
            ]
        ],
        [
            name: "clear_captured_states",
            description: "Clear all captured device states. Use with caution.",
            inputSchema: [type: "object", properties: [:]]
        ],

        // Debug Logging Tools
        [
            name: "get_debug_logs",
            description: "Get MCP debug logs (stored in app state). Useful for debugging rule issues.",
            inputSchema: [
                type: "object",
                properties: [
                    limit: [type: "integer", description: "Max entries to return (default: 50, max: 200)"],
                    level: [type: "string", enum: ["debug", "info", "warn", "error", "all"], description: "Filter by log level (default: all)"],
                    component: [type: "string", description: "Filter by component (e.g., 'server', 'rule')"],
                    ruleId: [type: "string", description: "Filter by specific rule ID"]
                ]
            ]
        ],
        [
            name: "clear_debug_logs",
            description: "Clear all stored debug log entries. Cannot be undone.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "get_rule_diagnostics",
            description: "Get comprehensive diagnostics for a rule: config, execution history, triggers/conditions/actions, logs, errors.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to diagnose"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "set_log_level",
            description: "Set the minimum log level threshold. Logs below this level won't be stored. Levels in order: debug < info < warn < error",
            inputSchema: [
                type: "object",
                properties: [
                    level: [type: "string", enum: ["debug", "info", "warn", "error"], description: "Minimum log level to store"]
                ],
                required: ["level"]
            ]
        ],
        [
            name: "get_logging_status",
            description: "Get status of the debug logging system including current log level, entry counts by severity, and capacity information.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "generate_bug_report",
            description: "Generate a formatted GitHub bug report with system info, error logs, and issue description.",
            inputSchema: [
                type: "object",
                properties: [
                    title: [type: "string", description: "Brief title describing the bug (e.g., 'Rule not triggering when motion detected')"],
                    expected: [type: "string", description: "What should have happened"],
                    actual: [type: "string", description: "What actually happened"],
                    stepsToReproduce: [type: "string", description: "Steps to reproduce the issue (optional)"],
                    ruleId: [type: "string", description: "If related to a specific rule, provide the rule ID (optional)"]
                ],
                required: ["title", "expected", "actual"]
            ]
        ],
        // Rule Export/Import/Clone Tools
        [
            name: "export_rule",
            description: "Export a rule to JSON for backup or sharing. Returns full rule data plus a device manifest listing all referenced devices.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to export"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "import_rule",
            description: """Import a rule from exported JSON (from export_rule). Optional deviceMapping remaps old device IDs to new: {"oldId": "newId"}.""",
            inputSchema: [
                type: "object",
                properties: [
                    exportData: [type: "object", description: "The full export JSON object from export_rule"],
                    name: [type: "string", description: "Override the rule name (optional)"],
                    deviceMapping: [type: "object", description: "Map old device IDs to new ones: {\"old_id\": \"new_id\"} (optional)"]
                ],
                required: ["exportData"]
            ]
        ],
        [
            name: "clone_rule",
            description: "Clone an existing rule. The cloned rule starts disabled to allow review before activation.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to clone"],
                    name: [type: "string", description: "Name for the clone (defaults to 'Copy of <original>')"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "check_for_update",
            description: "Check if a newer version of MCP Rule Server is available on GitHub",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],

        // ==================== HUB ADMIN READ TOOLS ====================
        // get_hub_details merged into get_hub_info (core tool)
        [
            name: "list_hub_apps",
            description: "List all installed apps on the hub (not just MCP rules). Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "list_hub_drivers",
            description: "List all installed drivers on the hub. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_zwave_details",
            description: "Get Z-Wave radio info: firmware, home ID, device nodes. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_zigbee_details",
            description: "Get Zigbee radio info: channel, PAN ID, firmware, devices. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        // ==================== MONITORING TOOLS ====================
        [
            name: "get_performance_stats",
            description: "Get device and/or app performance stats from the hub's logs page. Shows method call counts, % busy, state size, events, states, hub actions, pending events per device/app. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", description: "Which stats to return: device, app, or both. Default: device.", enum: ["device", "app", "both"], default: "device"],
                    sortBy: [type: "string", description: "Sort results by field. Default: pct (% busy).", enum: ["pct", "count", "stateSize", "totalMs", "name"], default: "pct"],
                    limit: [type: "integer", description: "Max entries to return. Default: 20, 0 for all.", default: 20]
                ]
            ]
        ],
        [
            name: "get_hub_jobs",
            description: "Get scheduled jobs, running jobs, and hub actions from the hub's logs page. Shows what's scheduled to run and when. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_hub_logs",
            description: "Get Hubitat system logs, most recent first. Filter by level, source substring, or scope server-side to a single device or app. Default 100 entries, max 500. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    level: [type: "string", description: "Filter by log level: trace, debug, info, warn, error. Default: all levels.", enum: ["trace", "debug", "info", "warn", "error"]],
                    source: [type: "string", description: "Filter by source/app name (case-insensitive substring match against the log entry)"],
                    deviceId: [type: "string", description: "Scope to a single device's log entries (server-side filter, mutually exclusive with appId)"],
                    appId: [type: "string", description: "Scope to a single app's log entries (server-side filter, mutually exclusive with deviceId)"],
                    limit: [type: "integer", description: "Max entries to return. Default: 100, max: 500.", default: 100]
                ]
            ]
        ],
        [
            name: "get_device_history",
            description: "Get device event history over a time range (up to 7 days). Supports attribute filtering.",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    hoursBack: [type: "integer", description: "How many hours of history to retrieve. Default: 24, max: 168 (7 days).", default: 24],
                    attribute: [type: "string", description: "Filter to a specific attribute name (e.g., 'temperature', 'switch')"],
                    limit: [type: "integer", description: "Max events to return. Default: 100, max: 500.", default: 100]
                ],
                required: ["deviceId"]
            ]
        ],
        [
            name: "get_set_hub_metrics",
            description: "Record and retrieve hub metrics (memory, temp, DB size) with CSV trend history. Use recordSnapshot=false to read without recording. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    recordSnapshot: [type: "boolean", description: "Record this snapshot to the performance history CSV. Default: true.", default: true],
                    trendPoints: [type: "integer", description: "Number of recent historical data points to include. Default: 10, max: 50.", default: 10]
                ]
            ]
        ],
        [
            name: "device_health_check",
            description: "Check all MCP devices for stale/offline status based on last activity threshold.",
            inputSchema: [
                type: "object",
                properties: [
                    staleHours: [type: "integer", description: "Flag devices with no activity in this many hours. Default: 24.", default: 24],
                    includeHealthy: [type: "boolean", description: "Include healthy devices in the response (can be large). Default: false.", default: false]
                ]
            ]
        ],
        [
            name: "get_memory_history",
            description: "Get free OS memory and CPU load history. Returns timestamped entries with freeMemoryKB and cpuLoad5min. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    limit: [type: "integer", description: "Max entries to return (most recent). Default: 100, 0 for all. Hub may have thousands of entries.", default: 100]
                ]
            ]
        ],
        [
            name: "force_garbage_collection",
            description: "Force JVM garbage collection to reclaim memory. Returns before/after free memory and delta. Non-destructive but may cause a brief pause. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],

        // ==================== HUB ADMIN WRITE TOOLS ====================
        [
            name: "create_hub_backup",
            description: """Create a full hub backup. REQUIRED before any Hub Admin Write operation (24h validity).

Requires Hub Admin Write + confirm. This is the only write tool that doesn't require a prior backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "Must be true to confirm you want to create a backup"]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "reboot_hub",
            description: """⚠️ DESTRUCTIVE: Reboots the hub (1-3 min downtime, all automations stop).

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user 3) Get explicit confirmation 4) Set confirm=true
Requires Hub Admin Write.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the reboot."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "shutdown_hub",
            description: """⚠️ EXTREME: Powers OFF the hub (requires physical restart). NOT a reboot.

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user it won't restart automatically 3) Get explicit confirmation 4) Set confirm=true
Requires Hub Admin Write.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the shutdown."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "zwave_repair",
            description: """⚠️ DISRUPTIVE: Z-Wave network repair. All Z-Wave devices may become unresponsive for 5-30 minutes.

WARNING: During repair, Z-Wave automations will be unreliable. Locks, garage doors, and security devices on Z-Wave may not respond. Schedule during off-peak hours when critical Z-Wave devices are not actively needed.

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user about duration/impact and which devices will be affected 3) Get explicit confirmation 4) Set confirm=true
Requires Hub Admin Write.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the Z-Wave repair."]
                ],
                required: ["confirm"]
            ]
        ],
        // Device Admin
        [
            name: "delete_device",
            description: """⚠️ MOST DESTRUCTIVE: Permanently delete a device. NO UNDO. For ghost/orphaned/stuck devices only.

PRE-FLIGHT: 1) Backup <24h 2) get_device to verify 3) Warn user 4) Z-Wave/Zigbee → exclusion first 5) Get confirmation
Device + history lost, automations break. Requires Hub Admin Write.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "The device ID to permanently delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created, device was verified, and user explicitly approved the deletion."]
                ],
                required: ["deviceId", "confirm"]
            ]
        ],

        // Virtual Device Management
        [
            name: "manage_virtual_device",
            description: """Create or delete MCP-managed virtual devices. Requires Hub Admin Write + confirm.

action="create": Provide deviceType (see enum), deviceLabel, optional deviceNetworkId.
action="delete": Provide deviceNetworkId of device to delete. Use list_virtual_devices to find DNIs.""",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", description: "Operation to perform", enum: ["create", "delete"]],
                    deviceType: [type: "string", description: "Virtual device driver type (required for create)",
                        enum: getSupportedVirtualDeviceTypes()],
                    deviceLabel: [type: "string", description: "Display label (required for create)"],
                    deviceNetworkId: [type: "string", description: "Device network ID. Auto-generated for create if omitted. REQUIRED for delete."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true to confirm the operation."]
                ],
                required: ["action", "confirm"]
            ]
        ],
        [
            name: "list_virtual_devices",
            description: "List MCP-managed virtual devices with IDs, labels, types, states, and capabilities.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "update_device",
            description: """Update device properties: label, name, deviceNetworkId, room, enabled, dataValues, preferences.

Only modify devices user explicitly requested. Room/enabled require Hub Admin Write. See get_tool_guide section=update_device for preferences format.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "The device ID to update (from list_devices or list_virtual_devices)"],
                    label: [type: "string", description: "New display label for the device"],
                    name: [type: "string", description: "New device name"],
                    deviceNetworkId: [type: "string", description: "New device network ID (must be unique across all hub devices)"],
                    room: [type: "string", description: "Room name to assign the device to (case-sensitive, must match an existing room)"],
                    enabled: [type: "boolean", description: "Set to true to enable or false to disable the device"],
                    dataValues: [type: "object", description: "Key-value pairs to set in the device's Data section. Example: {\"firmware\": \"1.2.3\", \"model\": \"ABC\"}",
                        additionalProperties: [type: "string"]],
                    preferences: [type: "object", description: "Device preferences to update. Each value must be an object with 'type' and 'value'. Example: {\"pollInterval\": {\"type\": \"number\", \"value\": 30}}"]
                ],
                required: ["deviceId"]
            ]
        ],

        // Room Management Tools
        [
            name: "list_rooms",
            description: "List all rooms with IDs, names, and device counts.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "get_room",
            description: "Get room details with assigned devices and their states. Specify by name or ID.",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Room name (case-insensitive) or room ID"]
                ],
                required: ["room"]
            ]
        ],
        [
            name: "create_room",
            description: "Create a new room. Optionally assign devices at creation. Requires Hub Admin Write + confirm + backup <24h.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Name for the new room"],
                    deviceIds: [type: "array", description: "Optional list of device IDs to assign to the room", items: [type: "string"]],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["name", "confirm"]
            ]
        ],
        [
            name: "delete_room",
            description: """⚠️ DESTRUCTIVE: Permanently deletes a room. Devices become unassigned (not deleted).

PRE-FLIGHT: 1) Backup <24h 2) Verify correct room 3) List affected devices to user 4) Get explicit confirmation 5) Set confirm=true
Requires Hub Admin Write.""",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Room name (case-insensitive) or room ID"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user explicitly approved the deletion."]
                ],
                required: ["room", "confirm"]
            ]
        ],
        [
            name: "rename_room",
            description: "Rename a room. Device assignments preserved. Automations/dashboards referencing room by name may need updating. Requires Hub Admin Write + confirm + backup <24h.",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Current room name (case-insensitive) or room ID"],
                    newName: [type: "string", description: "New name for the room"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["room", "newName", "confirm"]
            ]
        ],

        // Hub Admin App/Driver Source Read Tools
        [
            name: "get_app_source",
            description: "Get app Groovy source. Supports chunked reading (offset/length). Large files auto-saved to File Manager for use with update_app_code sourceFile mode. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID (from list_hub_apps)"],
                    offset: [type: "integer", description: "Character offset to start reading from (for chunked reading of large sources). Default: 0"],
                    length: [type: "integer", description: "Max characters to return in this chunk. Default/max: 64000"]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "get_driver_source",
            description: "Get driver Groovy source. Supports chunked reading (offset/length). Large files auto-saved to File Manager for use with update_driver_code sourceFile mode. Requires Hub Admin Read.",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID (from list_hub_drivers)"],
                    offset: [type: "integer", description: "Character offset to start reading from (for chunked reading of large sources). Default: 0"],
                    length: [type: "integer", description: "Max characters to return in this chunk. Default/max: 64000"]
                ],
                required: ["driverId"]
            ]
        ],
        // Hub Admin App/Driver Management Write Tools
        [
            name: "install_app",
            description: """⚠️ Install new app from Groovy source. Show code to user and get confirmation first.

Requires Hub Admin Write + confirm + backup <24h. Returns new app ID. After install, add via Apps > Add User App in Hubitat UI.""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "The full Groovy source code for the app"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["source", "confirm"]
            ]
        ],
        [
            name: "install_driver",
            description: """⚠️ Install new driver from Groovy source. Show code to user and get confirmation first.

Requires Hub Admin Write + confirm + backup <24h. Returns new driver ID.""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "The full Groovy source code for the driver"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["source", "confirm"]
            ]
        ],
        [
            name: "update_app_code",
            description: """⚠️ CRITICAL: Modify existing app code. Read current source first, explain changes, get confirmation.

Modes: source (direct), sourceFile (from File Manager), resave (recompile without changes).
Auto-backs up before modifying. Requires Hub Admin Write + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID to update"],
                    source: [type: "string", description: "The full new Groovy source code (for apps under 64KB)"],
                    sourceFile: [type: "string", description: "File Manager file name containing the source code (e.g., 'mcp-source-app-467.groovy'). Use this for large apps to avoid cloud size limits."],
                    resave: [type: "boolean", description: "Re-save the current source code without changes. Runs entirely on-hub — no cloud round-trip needed."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["appId", "confirm"]
            ]
        ],
        [
            name: "update_driver_code",
            description: """⚠️ CRITICAL: Modify existing driver code. Read current source first, explain changes, get confirmation.

Modes: source (direct), sourceFile (from File Manager), resave (recompile without changes).
Auto-backs up before modifying. Requires Hub Admin Write + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID to update"],
                    source: [type: "string", description: "The full new Groovy source code (for drivers under 64KB)"],
                    sourceFile: [type: "string", description: "File Manager file name containing the source code (e.g., 'mcp-source-driver-747.groovy'). Use this for large drivers to avoid cloud size limits."],
                    resave: [type: "boolean", description: "Re-save the current source code without changes. Runs entirely on-hub — no cloud round-trip needed."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["driverId", "confirm"]
            ]
        ],
        [
            name: "delete_app",
            description: """⚠️ DESTRUCTIVE: Permanently delete an app. Auto-backs up before deletion. Remove app instances via Hubitat UI first.

Tell user app name/ID, warn it's permanent, get confirmation. Requires Hub Admin Write + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["appId", "confirm"]
            ]
        ],
        [
            name: "delete_driver",
            description: """⚠️ DESTRUCTIVE: Permanently delete a driver. Auto-backs up before deletion. Devices using it must change drivers first.

Tell user driver name/ID, warn it's permanent, get confirmation. Requires Hub Admin Write + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["driverId", "confirm"]
            ]
        ],

        // ==================== Item Backup Tools ====================
        [
            name: "list_item_backups",
            description: "List auto-created source backups from app/driver modifications. Stored in File Manager, max 20 kept.",
            inputSchema: [
                type: "object",
                properties: [:],
                required: []
            ]
        ],
        [
            name: "get_item_backup",
            description: "Get source code from a backup. Use list_item_backups to find backup keys (e.g., 'app_123').",
            inputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "The backup key from list_item_backups (e.g., 'app_123' or 'driver_456')"]
                ],
                required: ["backupKey"]
            ]
        ],
        [
            name: "restore_item_backup",
            description: "⚠️ Restore app/driver to backed-up version. Tell user first. If item was DELETED, use install_app/install_driver instead. Requires Hub Admin Write + confirm.",
            inputSchema: [
                type: "object",
                properties: [
                    backupKey: [type: "string", description: "The backup key from list_item_backups (e.g., 'app_123' or 'driver_456')"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the restore."]
                ],
                required: ["backupKey", "confirm"]
            ]
        ],
        // File Manager Tools
        [
            name: "list_files",
            description: "List files in hub's File Manager. Returns names, sizes, download URLs.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "read_file",
            description: "Read file from File Manager. Supports chunked reading (offset/length) for large files.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact file name (e.g., 'dashboard-backup.json', 'mcp-backup-app-123.groovy')"],
                    offset: [type: "integer", description: "Character offset to start reading from (for chunked reading of large files). Default: 0"],
                    length: [type: "integer", description: "Max characters to return in this chunk. Default/max: 60000"]
                ],
                required: ["fileName"]
            ]
        ],
        [
            name: "write_file",
            description: "⚠️ Write file to File Manager. Auto-backs up existing files. Requires Hub Admin Write + confirm.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The file name to write (e.g., 'my-config.json'). Only A-Za-z0-9, hyphens, underscores, and periods allowed."],
                    content: [type: "string", description: "The text content to write to the file"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the write."]
                ],
                required: ["fileName", "content", "confirm"]
            ]
        ],
        [
            name: "delete_file",
            description: "⚠️ Delete file from File Manager. Auto-backs up before deletion. Tell user first. Requires Hub Admin Write + confirm.",
            inputSchema: [
                type: "object",
                properties: [
                    fileName: [type: "string", description: "The exact file name to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms user approved the deletion."]
                ],
                required: ["fileName", "confirm"]
            ]
        ],
        // Installed Apps Integration (built-in + user app visibility)
        [
            name: "list_installed_apps",
            description: """List all installed apps on the hub (built-in + user) with parent/child tree. Requires Built-in App Tools enabled.

Each app entry returns: id, name, type, disabled, user (true=user-installed Groovy app, false=built-in), hidden, parentId (null for top-level), hasChildren, childCount.

Use filter to narrow results: 'all' (default), 'builtin' (Hubitat native apps), 'user' (custom Groovy apps), 'disabled' (paused/disabled), 'parents' (apps with children like Rule Machine, Room Lighting, Groups and Scenes), 'children' (individual rules, scenes, etc.).""",
            inputSchema: [
                type: "object",
                properties: [
                    filter: [type: "string", enum: ["all", "builtin", "user", "disabled", "parents", "children"], description: "Filter apps by category. Default: all"],
                    includeHidden: [type: "boolean", description: "Include hidden apps (typically Hubitat internal). Default: false", default: false]
                ]
            ]
        ],
        [
            name: "get_device_in_use_by",
            description: """List all apps that reference a specific device (Room Lighting instances, Rule Machine rules, Groups and Scenes, Mode Manager, dashboards, Maker API, Echo Skill, etc.). Requires Built-in App Tools enabled.

Answers \"which apps would break or change behavior if I disable/delete this device?\" — critical before device cleanup, troubleshooting, or reassignment.

Returns: deviceId, deviceName, appsUsing array (each entry: id, name=app type, label=user-visible name, trueLabel=label without HTML decoration, disabled), count, parentApp.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from list_devices"]
                ],
                required: ["deviceId"]
            ]
        ],
        // Hub Admin App Configuration Read (grouped with installed-apps peers)
        [
            name: "get_app_config",
            description: """Read an installed app's configuration — the same structured data the Hubitat Web UI shows on each app's settings page. Works for Rule Machine rules, Room Lighting instances, Basic Rules, Button Controllers, Hubitat Package Manager, Mode Manager, and any other legacy SmartApp.

Returns the app's identity (label, type, parent, disabled state) and its current config page: sections, inputs (name, type, title, description, options, current value). Multi-page apps (e.g. RM 5.1) expose sub-pages by name — pass pageName to navigate into them. Read-only; does not modify anything.

Use to: understand what an existing automation actually does, audit rules for best-practice issues, diff two similar apps, generate human-readable summaries, or answer "which app is doing X" after list_installed_apps / get_device_in_use_by narrows the field.

Workflow: (1) Get the appId from list_installed_apps (all apps), list_rm_rules (RM rules specifically -- these are Rule-5.x appIds under parent Rule Machine; use this, not list_rules / get_rule, which only handle MCP-native rules), or list_installed_apps with filter=parents to explore app hierarchy. (2) Call get_app_config with the appId. (3) For multi-page apps, optionally pass pageName -- call list_app_pages first to discover available page names. Common multi-page names: HPM uses prefPkgUninstall (full installed-package list), prefPkgModify (modifiable subset only), prefOptions (main menu / navigation); RM and Room Lighting use a single mainPage (no pageName needed).

Requires Hub Admin Read.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "Installed-app ID (decimal). From list_installed_apps, list_rm_rules, or the numeric id in the Hubitat UI URL (/installedapp/configure/<id>)."],
                    pageName: [type: "string", description: "Optional sub-page name for multi-page apps. Main page is used when omitted. Call list_app_pages to discover available pages. HPM: prefPkgUninstall (full installed-package list), prefPkgModify (modifiable subset), prefOptions (main menu). RM / Room Lighting: mainPage only."],
                    includeSettings: [type: "boolean", description: "Include the raw app-internal settings key-value map. Default false -- large apps can have 500-1000 keys with app-specific encoding (e.g. Room Lighting's dm~<deviceId>~<scene>). Set true only for power-user inspection.", default: false]
                ],
                required: ["appId"]
            ]
        ],
        // Hub Admin App Pages Directory
        [
            name: "list_app_pages",
            description: """List known page names for a multi-page installed app. Returns the primary page (introspected live from the hub) plus a curated directory of known sub-pages for well-known app types.

Curated directories: HPM (prefOptions main menu, prefPkgUninstall full installed-package list, prefPkgModify modifiable subset, prefPkgInstall install flow, prefPkgMatchUp match-up flow); Rule Machine rules (mainPage only -- rules are single-page); Room Lighting (mainPage); Mode Manager (mainPage).

Unknown app types return the primary page only, plus a note directing you to consult the app's source or Web UI navigation for additional page names.

Use this before get_app_config on multi-page apps to avoid guessing page names.

Requires Hub Admin Read.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "Installed-app ID (decimal). From list_installed_apps, list_rm_rules, or the Hubitat UI URL (/installedapp/configure/<id>)."]
                ],
                required: ["appId"]
            ]
        ],
        // Rule Machine Integration (read + trigger + pause/resume only — platform blocks CRUD)
        [
            name: "list_rm_rules",
            description: "List all Rule Machine rules (RM 4.x + 5.x, deduplicated by id). Returns rule IDs and labels. Requires Built-in App Tools enabled. See get_tool_guide section=builtin_app_tools for details and platform limitations on RM rule internals.",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "run_rm_rule",
            description: "Trigger a Rule Machine rule. Not destructive (invokes existing user-configured automation). Requires Built-in App Tools enabled. See get_tool_guide section=builtin_app_tools for action semantics.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from list_rm_rules"],
                    action: [type: "string", enum: ["rule", "actions", "stop"], description: "Which RM action to invoke. Default: rule"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "pause_rm_rule",
            description: "Pause a Rule Machine rule (paused rules don't fire on triggers). Reversible via resume_rm_rule. Requires Built-in App Tools enabled.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from list_rm_rules"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "resume_rm_rule",
            description: "Resume a paused Rule Machine rule. Requires Built-in App Tools enabled.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from list_rm_rules"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "set_rm_rule_boolean",
            description: "Set a Rule Machine rule's private boolean to true or false (strict: accepts Boolean or lowercase string 'true'/'false' only). Requires Built-in App Tools enabled. See get_tool_guide section=builtin_app_tools for pattern and coercion policy.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from list_rm_rules"],
                    value: [type: "boolean", description: "true sets the boolean to TRUE, false sets it to FALSE"]
                ],
                required: ["ruleId", "value"]
            ]
        ],
        // Tool Guide
        [
            name: "get_tool_guide",
            description: "Get detailed reference for MCP tools. USE SPARINGLY - tool descriptions should suffice for most cases. When needed, ALWAYS specify a section to minimize token usage.",
            inputSchema: [
                type: "object",
                properties: [
                    section: [type: "string", description: "REQUIRED for efficiency: device_authorization, hub_admin_write, virtual_devices, update_device, rules, backup, file_manager, performance, builtin_app_tools. Full guide only if absolutely necessary."]
                ]
            ]
        ],
        // Tool Search (BM25)
        [
            name: "search_tools",
            description: "Search all MCP tools by natural language query (BM25 ranking). Searches tool names, descriptions, and parameter names. Returns matching tools with their gateway location so you know how to call them. Use when unsure which gateway contains the tool you need.",
            inputSchema: [
                type: "object",
                properties: [
                    query: [type: "string", description: "Natural language search query (e.g. 'zigbee radio', 'delete app', 'memory leak', 'room management')"],
                    maxResults: [type: "integer", description: "Max results to return. Default: 5.", default: 5]
                ],
                required: ["query"]
            ]
        ]
    ]
}

def executeTool(toolName, args) {
    switch (toolName) {
        // Device Tools
        case "list_devices": return toolListDevices(args.detailed, args.offset ?: 0, args.limit ?: 0, args.filter)
        case "get_device": return toolGetDevice(args.deviceId)
        case "send_command": return toolSendCommand(args.deviceId, args.command, args.parameters)
        case "get_device_events": return toolGetDeviceEvents(args.deviceId, args.limit != null ? args.limit : 10)
        case "get_attribute": return toolGetAttribute(args.deviceId, args.attribute)

        // Rule Management - now using child apps
        case "list_rules": return toolListRules()
        case "get_rule": return toolGetRule(args.ruleId)
        case "create_rule": return toolCreateRule(args)
        case "update_rule": return toolUpdateRule(args.ruleId, args)
        case "delete_rule": return toolDeleteRule(args)
        // enable_rule/disable_rule merged into update_rule
        case "test_rule": return toolTestRule(args.ruleId)

        // System Tools
        case "get_hub_info": return toolGetHubInfo()
        case "get_modes": return toolGetModes()
        case "set_mode": return toolSetMode(args.mode)
        case "list_variables": return toolListVariables()
        case "get_variable": return toolGetVariable(args.name)
        case "set_variable": return toolSetVariable(args.name, args.value)
        case "delete_variable": return toolDeleteHubVariable(args)
        case "update_mcp_settings": return toolUpdateMcpSettings(args)
        case "get_hsm_status": return toolGetHsmStatus()
        case "set_hsm": return toolSetHsm(args.mode)

        // Captured State Management
        case "list_captured_states": return toolListCapturedStates()
        case "delete_captured_state": return toolDeleteCapturedState(args.stateId)
        case "clear_captured_states": return toolClearCapturedStates()

        // Debug Logging Tools
        case "get_debug_logs": return toolGetDebugLogs(args)
        case "clear_debug_logs": return toolClearDebugLogs(args)
        case "get_rule_diagnostics": return toolGetRuleDiagnostics(args)
        case "set_log_level": return toolSetLogLevel(args)
        case "get_logging_status": return toolGetLoggingStatus(args)
        case "generate_bug_report": return toolGenerateBugReport(args)

        // Rule Export/Import/Clone
        case "export_rule": return toolExportRule(args)
        case "import_rule": return toolImportRule(args)
        case "clone_rule": return toolCloneRule(args)

        // Version Check
        case "check_for_update": return toolCheckForUpdate(args)

        // Hub Admin Read Tools
        // get_hub_details merged into get_hub_info
        case "list_hub_apps": return toolListHubApps(args)
        case "list_hub_drivers": return toolListHubDrivers(args)
        case "get_zwave_details": return toolGetZwaveDetails(args)
        case "get_zigbee_details": return toolGetZigbeeDetails(args)
        // get_hub_health merged into get_hub_info

        // Monitoring Tools
        case "get_hub_logs": return toolGetHubLogs(args)
        case "get_device_history": return toolGetDeviceHistory(args)
        case "get_performance_stats": return toolGetPerformanceStats(args)
        case "get_hub_jobs": return toolGetHubJobs(args)
        case "get_set_hub_metrics": return toolGetHubPerformance(args)
        case "device_health_check": return toolDeviceHealthCheck(args)
        case "get_memory_history": return toolGetMemoryHistory(args)
        case "force_garbage_collection": return toolForceGarbageCollection(args)

        // Hub Admin Write Tools
        case "create_hub_backup": return toolCreateHubBackup(args)
        case "reboot_hub": return toolRebootHub(args)
        case "shutdown_hub": return toolShutdownHub(args)
        case "zwave_repair": return toolZwaveRepair(args)

        // Device Admin
        case "delete_device": return toolDeleteDevice(args)

        // Virtual Device Management
        case "manage_virtual_device": return toolManageVirtualDevice(args)
        case "list_virtual_devices": return toolListVirtualDevices(args)
        case "update_device": return toolUpdateDevice(args)

        // Room Management
        case "list_rooms": return toolListRooms()
        case "get_room": return toolGetRoom(args.room)
        case "create_room": return toolCreateRoom(args)
        case "delete_room": return toolDeleteRoom(args)
        case "rename_room": return toolRenameRoom(args)

        // Hub Admin App Configuration Read
        case "get_app_config": return toolGetAppConfig(args)
        case "list_app_pages": return toolListAppPages(args)

        // Hub Admin App/Driver Management
        case "get_app_source": return toolGetAppSource(args)
        case "get_driver_source": return toolGetDriverSource(args)
        case "install_app": return toolInstallApp(args)
        case "install_driver": return toolInstallDriver(args)
        case "update_app_code": return toolUpdateAppCode(args)
        case "update_driver_code": return toolUpdateDriverCode(args)
        case "delete_app": return toolDeleteApp(args)
        case "delete_driver": return toolDeleteDriver(args)

        // Item Backup Tools
        case "list_item_backups": return toolListItemBackups()
        case "get_item_backup": return toolGetItemBackup(args)
        case "restore_item_backup": return toolRestoreItemBackup(args)

        // File Manager Tools
        case "list_files": return toolListFiles()
        case "read_file": return toolReadFile(args)
        case "write_file": return toolWriteFile(args)
        case "delete_file": return toolDeleteFile(args)

        // Installed Apps Integration
        case "list_installed_apps": return toolListInstalledApps(args)
        case "get_device_in_use_by": return toolGetDeviceInUseBy(args)

        // Rule Machine Integration (via RMUtils)
        case "list_rm_rules": return toolListRmRules(args)
        case "run_rm_rule": return toolRunRmRule(args)
        case "pause_rm_rule": return toolPauseRmRule(args)
        case "resume_rm_rule": return toolResumeRmRule(args)
        case "set_rm_rule_boolean": return toolSetRmRuleBoolean(args)

        // Tool Guide
        case "get_tool_guide": return toolGetToolGuide(args.section)

        // Tool Search (BM25)
        case "search_tools": return toolSearchTools(args)

        // Category Gateway Proxy Tools
        case "manage_rules_admin":
        case "manage_hub_variables":
        case "manage_rooms":
        case "manage_destructive_hub_ops":
        case "manage_apps_drivers":
        case "manage_app_driver_code":
        case "manage_logs":
        case "manage_diagnostics":
        case "manage_files":
        case "manage_installed_apps":
        case "manage_rule_machine":
        case "manage_mcp_self":
            return handleGateway(toolName, args.tool, args.args)

        default:
            throw new IllegalArgumentException("Unknown tool: ${toolName}")
    }
}

// ==================== DEVICE TOOLS ====================

def toolListDevices(detailed, offset, limit, filter = null) {
    // Combine selected devices and MCP-managed child devices (virtual devices)
    def allDevices = (selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    // Add child devices that aren't already in the selected list (avoid duplicates)
    def selectedIds = allDevices.collect { it.id.toString() } as Set
    childDevs.each { cd ->
        if (!selectedIds.contains(cd.id.toString())) {
            allDevices.add(cd)
        }
    }

    if (!allDevices) {
        return [devices: [], message: "No devices selected for MCP access and no MCP-managed virtual devices", total: 0]
    }

    def unfilteredTotal = allDevices.size()

    // Parse and apply server-side filter BEFORE pagination so limit/offset respect the filtered set.
    // Supported filters: null/"all" (default), "enabled", "disabled", "stale:<hours>" (e.g. "stale:24").
    // Filtering happens in-memory against device properties already loaded, no extra hub API calls.
    def filterType = null
    def staleMs = 0L
    if (filter && filter != "all") {
        if (filter == "enabled" || filter == "disabled") {
            filterType = filter
        } else if (filter.startsWith("stale:")) {
            def hoursStr = filter.substring(6).trim()
            def hours
            try {
                hours = hoursStr as Double
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid stale filter '${filter}'. Expected format: stale:<hours> (e.g. stale:24)")
            }
            if (hours <= 0) {
                throw new IllegalArgumentException("stale filter hours must be positive, got: ${hours}")
            }
            filterType = "stale"
            staleMs = (long)(hours * 3600000L)
        } else {
            throw new IllegalArgumentException("Invalid filter '${filter}'. Must be one of: all, enabled, disabled, stale:<hours>")
        }
    }

    if (filterType) {
        def nowMs = now()
        allDevices = allDevices.findAll { d ->
            switch (filterType) {
                case "enabled": return !isDeviceDisabled(d)
                case "disabled": return isDeviceDisabled(d)
                case "stale":
                    def la = safeLastActivity(d)
                    if (la == null) return true  // never-reported device counts as stale
                    return (nowMs - la.time) >= staleMs
                default: return true
            }
        }
    }

    def totalCount = allDevices.size()

    // Apply pagination (post-filter)
    def startIndex = offset ?: 0
    if (startIndex < 0) startIndex = 0
    def endIndex = totalCount
    if (limit && limit > 0) {
        endIndex = Math.min(startIndex + limit, totalCount)
    }

    // Validate offset
    if (totalCount > 0 && startIndex >= totalCount) {
        return [
            devices: [],
            total: totalCount,
            unfilteredTotal: unfilteredTotal,
            offset: startIndex,
            limit: limit ?: 0,
            filter: filter ?: "all",
            message: "Offset ${startIndex} exceeds filtered device count ${totalCount}"
        ]
    }

    def pagedDevices = totalCount > 0 ? allDevices.subList(startIndex, endIndex) : []
    def childDeviceIds = childDevs.collect { it.id.toString() } as Set

    def devices = pagedDevices.collect { device ->
        def deviceIdStr = device.id.toString()
        def info = [
            id: deviceIdStr,
            name: device.name,
            label: device.label ?: device.name,
            room: device.roomName,
            disabled: isDeviceDisabled(device),
            deviceNetworkId: safeDni(device),
            lastActivity: formatLastActivity(safeLastActivity(device)),
            parentDeviceId: safeParentDeviceId(device)
        ]
        if (childDeviceIds.contains(deviceIdStr)) {
            info.mcpManaged = true
        }

        if (detailed) {
            info.capabilities = device.capabilities?.collect { it.name }
            info.attributes = device.supportedAttributes?.collect { attr ->
                [name: attr.name, value: device.currentValue(attr.name)]
            }
            info.commands = device.supportedCommands?.collect { it.name }
        } else {
            info.currentStates = [:]
            ["switch", "level", "motion", "contact", "temperature", "humidity", "battery"].each { attr ->
                def val = device.currentValue(attr)
                if (val != null) info.currentStates[attr] = val
            }
        }

        return info
    }

    def result = [
        devices: devices,
        count: devices.size(),
        total: totalCount
    ]
    if (filter && filter != "all") {
        result.filter = filter
        result.unfilteredTotal = unfilteredTotal
    }

    // Include pagination info if pagination was used
    if (limit && limit > 0) {
        result.offset = startIndex
        result.limit = limit
        result.hasMore = endIndex < totalCount
        if (endIndex < totalCount) {
            result.nextOffset = endIndex
        }
    }

    return result
}

/**
 * Check if a device is disabled. Hubitat's device object exposes several property names
 * across firmware versions; try the most common ones and fall back to false.
 */
private Boolean isDeviceDisabled(device) {
    try {
        if (device.hasProperty("disabled") && device.disabled != null) return device.disabled == true
    } catch (Exception ignore) {}
    try {
        return device.isDisabled() == true
    } catch (Exception ignore) {}
    try {
        if (device.hasProperty("status") && device.status?.toString()?.toLowerCase() == "disabled") return true
    } catch (Exception ignore) {}
    return false
}

/**
 * Safely fetch device.deviceNetworkId — some virtual devices or mid-transition states can throw.
 */
private String safeDni(device) {
    try {
        return device.deviceNetworkId?.toString()
    } catch (Exception ignore) {
        return null
    }
}

/**
 * Safely fetch device.parentDeviceId — direct property access, not a hub call.
 * Cheap enough to include in the summary response. Consumers can derive child
 * counts client-side by grouping the devices list on this field, avoiding the
 * per-device getChildDevices() call that childCount required in earlier versions.
 */
private String safeParentDeviceId(device) {
    try {
        return device.parentDeviceId?.toString()
    } catch (Exception ignore) {
        return null
    }
}

/**
 * Safely fetch device.getLastActivity() — returns Date or null. Some drivers don't set this.
 */
private Date safeLastActivity(device) {
    try {
        return device.getLastActivity()
    } catch (Exception ignore) {
        return null
    }
}

/**
 * Format a Date as ISO 8601 string, or null if the Date is null.
 */
private String formatLastActivity(Date d) {
    if (d == null) return null
    try {
        return d.format("yyyy-MM-dd'T'HH:mm:ssXXX")
    } catch (Exception ignore) {
        return d.toString()
    }
}

def toolGetDevice(deviceId) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    def attributes = []
    try {
        attributes = device.supportedAttributes?.collect { attr ->
            [name: attr.name, dataType: attr.dataType?.toString(), value: device.currentValue(attr.name)]
        } ?: []
    } catch (Exception e) {
        logDebug("Error getting attributes for device ${deviceId}: ${e.message}")
    }

    def commands = []
    try {
        commands = device.supportedCommands?.collect { cmd ->
            def args = null
            try {
                args = cmd.arguments?.collect { arg ->
                    if (arg instanceof Map) {
                        [name: arg.name ?: "arg", type: arg.type ?: "unknown"]
                    } else if (arg.respondsTo("getName")) {
                        [name: arg.getName() ?: "arg", type: arg.getType()?.toString() ?: "unknown"]
                    } else {
                        [name: arg.toString(), type: "unknown"]
                    }
                }
            } catch (Exception e) {
                args = null
            }
            [name: cmd.name, arguments: args]
        } ?: []
    } catch (Exception e) {
        logDebug("Error getting commands for device ${deviceId}: ${e.message}")
    }

    return [
        id: device.id.toString(),
        name: device.name,
        label: device.label ?: device.name,
        room: device.roomName,
        capabilities: device.capabilities?.collect { it.name } ?: [],
        attributes: attributes,
        commands: commands
    ]
}

def toolSendCommand(deviceId, command, parameters) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    // Capture label before command execution to avoid serialization issues
    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"

    def supportedCommands = device.supportedCommands?.collect { it.name }
    if (!supportedCommands?.contains(command)) {
        throw new IllegalArgumentException("Device ${deviceLabel} does not support command: ${command}. Available: ${supportedCommands}")
    }

    if (parameters && parameters.size() > 0) {
        // Normalize parameters to a flat List of properly typed values
        parameters = normalizeCommandParams(parameters)
        device."${command}"(*parameters)
    } else {
        device."${command}"()
    }

    return [
        success: true,
        device: deviceLabel,
        command: command,
        parameters: parameters
    ]
}

/**
 * Normalize command parameters to a flat List of properly typed values.
 *
 * Hubitat's JSON parser handles simple parameter arrays like ["75"] fine (returns List [75]),
 * but chokes on nested JSON objects like ["{"hue":0,"sat":100}"] — the inner quotes break
 * the parser and it falls back to a raw String with unescaped quotes. This function handles
 * both cases: proper Lists pass through element conversion, and String fallbacks get the
 * embedded JSON object extracted by brace-matching.
 */
def normalizeCommandParams(params) {
    // Case 1: Already a List (Hubitat parsed it successfully) — go straight to element conversion
    if (params instanceof List) {
        return convertParamElements(params)
    }

    // Case 2: String (Hubitat parser failed on nested JSON)
    // Example: '["{"hue":0,"saturation":100,"level":50}"]'
    def s = params.toString().trim()

    // Try to extract an embedded JSON object between first { and last }
    def firstBrace = s.indexOf("{")
    def lastBrace = s.lastIndexOf("}")
    if (firstBrace >= 0 && lastBrace > firstBrace) {
        def jsonContent = s.substring(firstBrace, lastBrace + 1)
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(jsonContent)
            return [parsed]
        } catch (Exception e) {
            // Not valid JSON object, fall through
        }
    }

    // No JSON object found — strip outer ["..."] wrapper and split into string params
    if (s.startsWith("[\"") && s.endsWith("\"]")) {
        def inner = s.substring(2, s.length() - 2)
        return convertParamElements(inner.split('","').toList())
    }

    // Last resort: treat the whole string as a single parameter
    return convertParamElements([s])
}

/**
 * Convert a List of raw parameter values to proper Groovy types.
 * Numbers become Integer/Double, JSON strings become Maps/Lists, everything else passes through.
 */
def convertParamElements(List params) {
    return params.collect { param ->
        if (param == null) return param
        if (param instanceof Map || param instanceof List) return param
        def s = param.toString()
        // Numeric conversion
        try {
            if (s.isNumber()) {
                return s.contains(".") ? s.toDouble() : s.toInteger()
            }
        } catch (Exception e) {}
        // JSON object/array string → parse to Map/List
        if ((s.startsWith("{") || s.startsWith("[")) && s.length() > 1) {
            try {
                return new groovy.json.JsonSlurper().parseText(s)
            } catch (Exception e) {}
        }
        return param
    }
}

def toolGetDeviceEvents(deviceId, limit) {
    if (limit == null || limit < 1) limit = 10
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    def events = device.events(max: limit)?.collect { evt ->
        [
            name: evt.name,
            value: evt.value,
            unit: evt.unit,
            description: evt.descriptionText,
            date: evt.date?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
            isStateChange: evt.isStateChange
        ]
    }

    return [
        device: device.label,
        events: events ?: [],
        count: events?.size() ?: 0
    ]
}

def toolGetAttribute(deviceId, attribute) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    // Capture label before operations to avoid serialization issues
    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"

    // Check if attribute exists on this device before reading its value
    def supportedAttrs = device.supportedAttributes?.collect { it.name } ?: []
    if (!supportedAttrs.contains(attribute)) {
        throw new IllegalArgumentException("Attribute '${attribute}' not found on device '${deviceLabel}'. Available: ${supportedAttrs}")
    }

    def value = device.currentValue(attribute)
    return [
        device: deviceLabel,
        attribute: attribute,
        value: value
    ]
}

// ==================== RULE TOOLS (Child App Based) ====================

def toolListRules() {
    def childApps = getChildApps()
    def rules = childApps?.collect { childApp ->
        def ruleData = childApp.getRuleData()
        if (!ruleData) return null  // Skip rules that return null data
        [
            id: ruleData.id,
            name: ruleData.name,
            description: ruleData.description,
            enabled: ruleData.enabled,
            triggerCount: ruleData.triggers?.size() ?: 0,
            conditionCount: ruleData.conditions?.size() ?: 0,
            actionCount: ruleData.actions?.size() ?: 0,
            lastTriggered: ruleData.lastTriggered,
            executionCount: ruleData.executionCount ?: 0
        ]
    }?.findAll { it != null } ?: []

    return [rules: rules, count: rules.size()]
}

def toolGetRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "read")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    return childApp.getRuleData()
}

def toolCreateRule(args) {
    def startTime = now()
    mcpLog("info", "server", "Creating rule: '${args.name}' (enabled=${args.enabled != false})", null, [
        details: [triggerCount: args.triggers?.size(), actionCount: args.actions?.size()]
    ])

    // Validate required fields
    if (!args.name?.trim()) {
        throw new IllegalArgumentException("Rule name is required")
    }
    if (!args.triggers || args.triggers.size() == 0) {
        throw new IllegalArgumentException("At least one trigger is required")
    }
    if (!args.actions || args.actions.size() == 0) {
        throw new IllegalArgumentException("At least one action is required")
    }

    // Normalize triggers (convert common sunrise/sunset formats to canonical form)
    args.triggers = args.triggers.collect { trigger -> normalizeTrigger(trigger) }

    // Validate triggers
    args.triggers.each { trigger ->
        validateTrigger(trigger)
    }

    // Validate conditions
    args.conditions?.each { condition ->
        validateCondition(condition)
    }

    // Validate actions
    args.actions.each { action ->
        validateAction(action)
    }

    // Normalize operators (convert "==" to "equals", "!=" to "not_equals")
    normalizeRuleOperators(args)

    // Create child app
    mcpLog("debug", "server", "Creating child app for rule '${args.name}'")
    def childApp = addChildApp("mcp", "MCP Rule", args.name.trim())
    def ruleId = childApp.id.toString()
    mcpLog("debug", "server", "Child app created with ID: ${ruleId}", ruleId)

    // Configure the child app - set name and description first, but NOT enabled
    // The enabled status must be set AFTER rule data is stored to avoid Hubitat
    // triggering updated() before data is persisted
    childApp.updateSetting("ruleName", args.name.trim())
    childApp.updateSetting("ruleDescription", args.description ?: "")

    // Set rule data via the child's API - child uses atomicState for immediate persistence
    // This prevents race condition where enabled=true triggers lifecycle before data is saved
    mcpLog("debug", "server", "Calling updateRuleFromParent with ${args.triggers?.size()} triggers, ${args.actions?.size()} actions (uses atomicState)", ruleId)
    childApp.updateRuleFromParent([
        triggers: args.triggers,
        conditions: args.conditions ?: [],
        conditionLogic: args.conditionLogic ?: "all",
        actions: args.actions,
        localVariables: args.localVariables ?: [:],
        enabled: args.enabled != false,  // Set enabled AFTER data is stored
        testRule: args.testRule ?: false  // Test rules skip backup on deletion
    ])

    // Verify data was stored correctly
    def verifyData = childApp.getRuleData()
    def storedTriggers = verifyData.triggers?.size() ?: 0
    def storedActions = verifyData.actions?.size() ?: 0
    def duration = now() - startTime

    if (storedTriggers == 0 && args.triggers?.size() > 0) {
        mcpLog("error", "server", "CRITICAL: Triggers not persisted! Expected ${args.triggers.size()}, got ${storedTriggers}", ruleId)
    }
    if (storedActions == 0 && args.actions?.size() > 0) {
        mcpLog("error", "server", "CRITICAL: Actions not persisted! Expected ${args.actions.size()}, got ${storedActions}", ruleId)
    }

    mcpLog("info", "server", "Rule created: '${args.name}' (ID: ${ruleId}) - stored ${storedTriggers} triggers, ${storedActions} actions", ruleId, [
        duration: duration,
        ruleName: args.name,
        details: [
            requestedTriggers: args.triggers?.size(),
            storedTriggers: storedTriggers,
            requestedActions: args.actions?.size(),
            storedActions: storedActions,
            enabled: args.enabled != false
        ]
    ])

    return [
        success: true,
        ruleId: ruleId,
        message: "Rule '${args.name}' created successfully",
        diagnostics: [
            storedTriggers: storedTriggers,
            storedActions: storedActions,
            durationMs: duration
        ]
    ]
}

def toolUpdateRule(ruleId, args) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "write")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    // Normalize and validate any provided triggers
    if (args.triggers != null) {
        args.triggers = args.triggers.collect { trigger -> normalizeTrigger(trigger) }
        args.triggers.each { validateTrigger(it) }
    }

    // Validate any provided conditions
    if (args.conditions != null) {
        args.conditions.each { validateCondition(it) }
    }

    // Validate any provided actions
    if (args.actions != null) {
        args.actions.each { validateAction(it) }
    }

    // Normalize operators (convert "==" to "equals", "!=" to "not_equals")
    normalizeRuleOperators(args)

    // Update via child app API
    def updateData = [:]
    if (args.name != null) {
        updateData.name = args.name.trim()
        childApp.updateSetting("ruleName", args.name.trim())
        childApp.updateLabel(args.name.trim())
    }
    if (args.description != null) {
        updateData.description = args.description
        childApp.updateSetting("ruleDescription", args.description)
    }
    if (args.enabled != null) updateData.enabled = args.enabled
    if (args.testRule != null) updateData.testRule = args.testRule
    if (args.triggers != null) updateData.triggers = args.triggers
    if (args.conditions != null) updateData.conditions = args.conditions
    if (args.conditionLogic != null) updateData.conditionLogic = args.conditionLogic
    if (args.actions != null) updateData.actions = args.actions
    if (args.localVariables != null) updateData.localVariables = args.localVariables

    childApp.updateRuleFromParent(updateData)

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    return [
        success: true,
        ruleId: ruleId,
        message: "Rule '${ruleName}' updated successfully"
    ]
}

def toolDeleteRule(args) {
    // Require explicit confirmation for destructive operation
    if (!args.confirm) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: You must set confirm=true to delete a rule. This action is IRREVERSIBLE.")
    }

    def childApp = getChildAppById(args.ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(args.ruleId, "write")
        def msg = "Rule not found: ${args.ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    def backupFileName = null

    // Check if rule is marked as a test rule
    def ruleData = childApp.getRuleData()
    def isTestRule = ruleData?.testRule ?: false

    // Automatically create a backup to File Manager unless:
    // 1. skipBackupCheck=true is passed, OR
    // 2. The rule is marked as testRule=true
    if (!args.skipBackupCheck && !isTestRule) {
        try {
            // Get the rule export data (already fetched above)
            if (!ruleData) {
                throw new IllegalArgumentException("Unable to read rule data for backup")
            }

            def ruleExport = buildRuleExport(ruleData)
            def deviceManifest = buildDeviceManifest(ruleData)

            def exportData = [
                exportVersion: "1.0",
                exportedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                serverVersion: currentVersion(),
                deletedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
                originalRuleId: args.ruleId,
                rule: ruleExport,
                deviceManifest: deviceManifest
            ]

            // Create backup file name: sanitize rule name for file system
            def safeName = ruleName.replaceAll(/[^A-Za-z0-9]/, '_').take(30)
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            backupFileName = "mcp_rule_backup_${safeName}_${timestamp}.json"

            // Save to File Manager
            def jsonContent = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(exportData))
            uploadHubFile(backupFileName, jsonContent.getBytes("UTF-8"))

            mcpLog("info", "rules", "Auto-backup created: '${backupFileName}' for rule '${ruleName}' before deletion")

        } catch (Exception e) {
            throw new IllegalArgumentException("BACKUP FAILED: Could not create backup for rule '${ruleName}' before deletion: ${e.message}. Fix the issue, mark the rule as testRule=true, or set skipBackupCheck=true.")
        }
    } else {
        if (isTestRule) {
            mcpLog("info", "rules", "Backup skipped for rule '${ruleName}' - marked as testRule")
        } else {
            mcpLog("warn", "rules", "Backup skipped for rule '${ruleName}' - user set skipBackupCheck=true")
        }
    }

    mcpLog("warn", "rules", "Deleting rule '${ruleName}' (ID: ${args.ruleId}) - user confirmed deletion")
    deleteChildApp(childApp.id)

    def result = [
        success: true,
        message: "Rule '${ruleName}' deleted permanently."
    ]

    if (backupFileName) {
        result.backupFile = backupFileName
        result.message += " Backup saved to File Manager: ${backupFileName}"
    } else if (isTestRule) {
        result.message += " (No backup - test rule)"
    }

    return result
}

// toolEnableRule/toolDisableRule/toolToggleRule removed in v0.8.1 (dead code since v0.8.0 merged into update_rule)

def toolTestRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "write")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    return childApp.testRuleFromParent()
}

// ==================== RULE HELPERS ====================

/**
 * Best-effort check: given a numeric id that was not found as an MCP child-app rule, look it up
 * in the hub's installed-app list (/hub2/appsList). If the id belongs to a known rule-like
 * built-in (Rule Machine, Room Lighting, Basic Rules, Visual Rules Builder), return a
 * verb-appropriate redirect hint string. Returns null on any failure (fetch error, parse error,
 * id not present, app not rule-like) so callers always get graceful fallback.
 *
 * Rule-like detection uses two complementary signals:
 *   1. classLocation from systemAppTypes[] (authoritative SDK string: ruleApp51, roomLightsApp, etc.)
 *   2. type string from the instance data (human-readable: "Rule-5.1", "Room Lights", etc.)
 * Either signal matching is sufficient.
 *
 * @param ruleId  the ID to look up (String or numeric)
 * @param verb    "read" (get/export) or "write" (update/delete/test/clone) -- controls hint phrasing
 * @return        redirect hint String, or null if no redirect applies
 */
private String findRuleAppRedirect(ruleId, String verb) {
    // Soft gate: only enrich the error when Built-in App Tools are enabled.
    // Prevents leaking that an app exists at this id (or its type) when the
    // operator has intentionally restricted built-in app visibility.
    if (!settings.enableBuiltinAppRead) return null

    // Known classLocation values for rule-like built-in apps.
    // ruleApp51 = Rule Machine 5.1, ruleApp50 = Rule Machine 5.0,
    // ruleApp = Rule Machine 4.x (legacy), roomLightsApp = Room Lighting parent,
    // roomLightsChildApp = Room Lighting instance, basicRulesApp = Basic Rules,
    // vrb = Visual Rules Builder.
    def ruleClassLocations = [
        "ruleApp51", "ruleApp50", "ruleApp",
        "roomLightsApp", "roomLightsChildApp",
        "basicRulesApp", "vrb"
    ] as Set

    // Human-readable type-string fragments to match as a fallback when classLocation
    // is unavailable (e.g., systemAppTypes absent from the firmware's appsList response).
    def ruleTypeFragments = [
        "rule machine", "rule-5", "rule-4", "room light", "basic rules", "visual rules"
    ]

    def idStr = ruleId?.toString()?.trim()
    if (!idStr || !idStr.isInteger()) return null

    try {
        def responseText = hubInternalGet("/hub2/appsList")
        if (!responseText) return null

        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception ignored) {
            return null
        }

        if (!(parsed instanceof Map)) return null

        // Build a classLocation lookup map from systemAppTypes[]: id -> classLocation.
        def classLocationById = [:]
        def systemTypes = parsed.systemAppTypes
        if (systemTypes instanceof List) {
            systemTypes.each { t ->
                if (t instanceof Map && t.id != null && t.classLocation) {
                    classLocationById[t.id.toString()] = t.classLocation.toString()
                }
            }
        }

        // Walk the apps[] tree with an early-exit DFS to find the app instance
        // with matching id. Avoids building a full flat list when the target
        // is found partway through a large app tree.
        def foundData = null
        def findInNodes
        findInNodes = { List nodes ->
            for (node in nodes) {
                if (!(node instanceof Map)) continue
                def d = node.data
                if (d instanceof Map && d.id?.toString() == idStr) {
                    foundData = d
                    return true
                }
                if (node.children instanceof List && findInNodes(node.children)) return true
            }
            return false
        }
        def apps = parsed.apps
        if (!(apps instanceof List)) return null
        findInNodes(apps)
        if (!foundData) return null

        // Determine whether this app type is rule-like via classLocation or type string.
        def appTypeName = foundData.type?.toString() ?: "built-in app"
        def appTypeId = foundData.appTypeId?.toString()
        def classLoc = appTypeId ? classLocationById[appTypeId] : null

        boolean isRuleLike = (classLoc && ruleClassLocations.contains(classLoc)) ||
            ruleTypeFragments.any { frag -> appTypeName.toLowerCase().contains(frag) }

        if (!isRuleLike) return null

        // Build verb-appropriate redirect hint.
        if (verb == "read") {
            return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                "Use `manage_installed_apps -> get_app_config(appId=${idStr})` to read its configuration. " +
                "`get_rule`, `export_rule`, and `clone_rule` only handle MCP's own rule engine, not Hubitat built-in apps."
        } else {
            return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                "Use `manage_installed_apps -> get_app_config(appId=${idStr})` for read-only inspection. " +
                "Hubitat built-in rules cannot be programmatically modified via MCP at this time -- " +
                "use the Hubitat Rule Machine UI for changes."
        }
    } catch (Exception e) {
        mcpLog("warn", "rules", "findRuleAppRedirect lookup failed for id ${idStr}: ${e.message ?: e.toString()}")
        return null
    }
}

/**
 * Build a portable rule export object from rule data (excludes runtime state like id, lastTriggered, executionCount).
 */
private Map buildRuleExport(Map ruleData) {
    return [
        name: ruleData.name,
        description: ruleData.description ?: "",
        enabled: ruleData.enabled,
        conditionLogic: ruleData.conditionLogic ?: "all",
        triggers: ruleData.triggers ?: [],
        conditions: ruleData.conditions ?: [],
        actions: ruleData.actions ?: [],
        localVariables: ruleData.localVariables ?: [:]
    ]
}

// ==================== RULE EXPORT/IMPORT/CLONE TOOLS ====================

def toolExportRule(args) {
    if (!args.ruleId) {
        throw new IllegalArgumentException("ruleId is required")
    }

    def childApp = getChildAppById(args.ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(args.ruleId, "read")
        def msg = "Rule not found: ${args.ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    def ruleData = childApp.getRuleData()
    if (!ruleData) {
        throw new IllegalArgumentException("Unable to read rule data for rule: ${args.ruleId}")
    }

    def ruleExport = buildRuleExport(ruleData)
    def deviceManifest = buildDeviceManifest(ruleData)

    def exportData = [
        exportVersion: "1.0",
        exportedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
        serverVersion: currentVersion(),
        rule: ruleExport,
        deviceManifest: deviceManifest
    ]

    mcpLog("info", "server", "Exported rule '${ruleData.name}' (ID: ${args.ruleId}) with ${deviceManifest.size()} device references", args.ruleId)

    return exportData
}

def toolImportRule(args) {
    if (!args.exportData) {
        throw new IllegalArgumentException("exportData is required")
    }

    def exportData = args.exportData

    // Validate export data structure
    if (!exportData.rule) {
        throw new IllegalArgumentException("Invalid export data: missing 'rule' object")
    }

    def ruleSource = exportData.rule

    if (!ruleSource.triggers || ruleSource.triggers.size() == 0) {
        throw new IllegalArgumentException("Invalid export data: rule must have at least one trigger")
    }
    if (!ruleSource.actions || ruleSource.actions.size() == 0) {
        throw new IllegalArgumentException("Invalid export data: rule must have at least one action")
    }

    // Apply device mapping if provided
    def deviceMapping = args.deviceMapping
    def mappedTriggers = ruleSource.triggers
    def mappedConditions = ruleSource.conditions ?: []
    def mappedActions = ruleSource.actions

    if (deviceMapping && deviceMapping.size() > 0) {
        mappedTriggers = applyDeviceMapping(ruleSource.triggers, deviceMapping)
        mappedConditions = applyDeviceMapping(ruleSource.conditions ?: [], deviceMapping)
        mappedActions = applyDeviceMapping(ruleSource.actions, deviceMapping)
    }

    // Determine rule name
    def ruleName = args.name?.trim() ?: ruleSource.name?.trim()
    if (!ruleName) {
        throw new IllegalArgumentException("Rule name is required (either in exportData or as 'name' parameter)")
    }

    // Use existing toolCreateRule to create the rule
    def createArgs = [
        name: ruleName,
        description: ruleSource.description ?: "",
        enabled: ruleSource.enabled != false,
        triggers: mappedTriggers,
        conditions: mappedConditions,
        conditionLogic: ruleSource.conditionLogic ?: "all",
        actions: mappedActions,
        localVariables: ruleSource.localVariables ?: [:]
    ]

    def result = toolCreateRule(createArgs)

    // Add import-specific metadata to result
    result.imported = true
    result.sourceExportVersion = exportData.exportVersion
    if (deviceMapping && deviceMapping.size() > 0) {
        result.devicesMapped = deviceMapping.size()
    }

    mcpLog("info", "server", "Imported rule '${ruleName}' (new ID: ${result.ruleId})" +
        (deviceMapping ? " with ${deviceMapping.size()} device mappings" : ""), result.ruleId)

    return result
}

def toolCloneRule(args) {
    if (!args.ruleId) {
        throw new IllegalArgumentException("ruleId is required")
    }

    // Export the source rule internally
    def exportData = toolExportRule([ruleId: args.ruleId])

    // Determine clone name
    def originalName = exportData.rule.name ?: "Unnamed Rule"
    def cloneName = args.name?.trim() ?: "Copy of ${originalName}"

    // Force the clone to start disabled for safety
    exportData.rule.enabled = false

    // Import as a new rule
    def result = toolImportRule([
        exportData: exportData,
        name: cloneName
    ])

    // Add clone-specific metadata
    result.clonedFrom = args.ruleId
    result.message = "Rule '${originalName}' cloned as '${cloneName}' (disabled)"

    mcpLog("info", "server", "Cloned rule '${originalName}' (ID: ${args.ruleId}) as '${cloneName}' (new ID: ${result.ruleId})", result.ruleId)

    return result
}

// ==================== EXPORT/IMPORT HELPERS ====================

/**
 * Scan rule data for all device ID references and build a manifest.
 * Looks in triggers, conditions, and actions (including nested if_then_else).
 */
def buildDeviceManifest(ruleData) {
    // Map of deviceId -> set of sections where it's used
    def deviceUsage = [:]  // deviceId -> [sections]

    // Scan triggers
    ruleData.triggers?.each { trigger ->
        collectDeviceIds(trigger, "triggers", deviceUsage)
    }

    // Scan conditions
    ruleData.conditions?.each { condition ->
        collectDeviceIds(condition, "conditions", deviceUsage)
    }

    // Scan actions
    ruleData.actions?.each { action ->
        collectDeviceIds(action, "actions", deviceUsage)
    }

    // Build manifest entries with device info
    def manifest = []
    deviceUsage.each { deviceId, sections ->
        def entry = [
            deviceId: deviceId.toString(),
            usedIn: sections.toList().sort()
        ]

        // Try to look up device details from selected devices
        def device = findDevice(deviceId)
        if (device) {
            entry.label = device.label ?: device.name ?: "Device ${deviceId}"
            entry.capabilities = device.capabilities?.collect { it.name } ?: []
        } else {
            entry.label = "Unknown device (not in selected devices)"
            entry.capabilities = []
        }

        manifest << entry
    }

    return manifest
}

/**
 * Recursively collect device IDs from a rule component (trigger, condition, or action).
 */
private void collectDeviceIds(component, String section, Map deviceUsage) {
    if (!component) return

    // Check for deviceId field (singular)
    if (component.deviceId) {
        def id = component.deviceId.toString()
        if (!deviceUsage.containsKey(id)) {
            deviceUsage[id] = new LinkedHashSet()
        }
        deviceUsage[id] << section
    }

    // Check for deviceIds field (plural — multi-device triggers, capture_state, etc.)
    if (component.deviceIds) {
        component.deviceIds.each { did ->
            def id = did.toString()
            if (!deviceUsage.containsKey(id)) {
                deviceUsage[id] = new LinkedHashSet()
            }
            deviceUsage[id] << section
        }
    }

    // Check nested structures in if_then_else actions
    if (component.type == "if_then_else") {
        // Scan conditions inside if_then_else
        component.conditions?.each { cond ->
            collectDeviceIds(cond, section, deviceUsage)
        }
        // Scan then actions
        component.thenActions?.each { action ->
            collectDeviceIds(action, section, deviceUsage)
        }
        // Scan else actions
        component.elseActions?.each { action ->
            collectDeviceIds(action, section, deviceUsage)
        }
    }

    // Check nested actions in repeat blocks
    if (component.type == "repeat") {
        component.actions?.each { action ->
            collectDeviceIds(action, section, deviceUsage)
        }
    }
}

/**
 * Recursively apply device ID mapping to rule data structures.
 * Returns a deep copy with mapped device IDs.
 */
def applyDeviceMapping(data, Map mapping) {
    if (data == null) return null

    if (data instanceof List) {
        return data.collect { item -> applyDeviceMapping(item, mapping) }
    }

    if (data instanceof Map) {
        def result = [:]
        data.each { key, value ->
            if (key == "deviceId" && value != null) {
                def mappedId = mapping[value.toString()]
                result[key] = mappedId != null ? mappedId.toString() : value
            } else if (key == "deviceIds" && value instanceof List) {
                // Map each device ID in multi-device trigger/action arrays
                result[key] = value.collect { id ->
                    if (id != null) {
                        def mappedId = mapping[id.toString()]
                        return mappedId != null ? mappedId.toString() : id
                    }
                    return id
                }
            } else {
                result[key] = applyDeviceMapping(value, mapping)
            }
        }
        return result
    }

    // Primitive value - return as-is
    return data
}

// ==================== SYSTEM TOOLS ====================

def toolGetHubInfo() {
    def hub = location.hub
    def info = [
        temperatureScale: location.temperatureScale
    ]

    // Hub hardware and radio info (always available)
    try { info.model = hub?.hardwareID } catch (Exception e) { info.model = "unavailable" }
    try { info.firmwareVersion = hub?.firmwareVersionString } catch (Exception e) { info.firmwareVersion = "unavailable" }
    try { info.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { info.zigbeeChannel = "unavailable" }
    try { info.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { info.zwaveVersion = "unavailable" }
    try { info.zigbeeId = hub?.zigbeeId } catch (Exception e) { info.zigbeeId = "unavailable" }
    try { info.type = hub?.type } catch (Exception e) { info.type = "unavailable" }

    // Uptime (always available)
    try {
        def uptimeSec = hub?.uptime
        if (uptimeSec && uptimeSec instanceof Number) {
            def days = (uptimeSec / 86400).toInteger()
            def hours = ((uptimeSec % 86400) / 3600).toInteger()
            def mins = ((uptimeSec % 3600) / 60).toInteger()
            info.uptimeSeconds = uptimeSec
            info.uptimeFormatted = "${days}d ${hours}h ${mins}m"
        }
    } catch (Exception e) { info.uptimeSeconds = "unavailable" }

    // Health data (always available — uses internal API)
    try {
        def freeMemory = hubInternalGet("/hub/advanced/freeOSMemory")
        if (freeMemory) {
            info.freeMemoryKB = freeMemory.trim()
            try {
                def memKB = freeMemory.trim() as Integer
                if (memKB < 50000) {
                    info.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
                } else if (memKB < 100000) {
                    info.memoryNote = "Memory is moderate: ${memKB}KB free."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.freeMemoryKB = "unavailable" }

    try {
        def tempC = hubInternalGet("/hub/advanced/internalTempCelsius")
        if (tempC) {
            info.internalTempCelsius = tempC.trim()
            try {
                def temp = tempC.trim() as Double
                if (temp > 70) {
                    info.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
                } else if (temp > 60) {
                    info.temperatureNote = "Temperature is warm: ${temp}°C."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.internalTempCelsius = "unavailable" }

    try {
        def dbSize = hubInternalGet("/hub/advanced/databaseSize")
        if (dbSize) {
            info.databaseSizeKB = dbSize.trim()
            try {
                def dbKB = dbSize.trim() as Integer
                if (dbKB > 500000) {
                    info.databaseWarning = "LARGE DATABASE: ${(dbKB / 1024).toInteger()}MB. Consider cleaning up old data."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.databaseSizeKB = "unavailable" }

    // MCP-specific stats (always available)
    info.mcpServerVersion = currentVersion()
    info.mcpDeviceCount = settings.selectedDevices?.size() ?: 0
    info.mcpRuleCount = getChildApps()?.size() ?: 0
    info.mcpLogEntries = state.debugLogs?.entries?.size() ?: 0
    info.mcpCapturedStates = state.capturedDeviceStates?.size() ?: 0

    // Settings visibility (always available)
    info.hubSecurityConfigured = settings.hubSecurityEnabled ?: false
    info.hubAdminReadEnabled = settings.enableHubAdminRead ?: false
    info.hubAdminWriteEnabled = settings.enableHubAdminWrite ?: false
    info.builtinAppReadEnabled = settings.enableBuiltinAppRead ?: false
    info.developerModeEnabled = settings.enableDeveloperMode ?: false

    // PII/location data requires Hub Admin Read
    if (settings.enableHubAdminRead) {
        info.name = hub?.name
        info.localIP = hub?.localIP
        info.timeZone = location.timeZone?.ID
        info.latitude = location.latitude
        info.longitude = location.longitude
        info.zipCode = location.zipCode
        try { info.hubData = hub?.data } catch (Exception e) { info.hubData = null }
    } else {
        info.hubAdminReadRequired = "Hub Admin Read is not enabled. The following personally identifiable data is excluded: hub name, local IP, time zone, latitude, longitude, zip code, and hub data. Enable 'Enable Hub Admin Read Tools' in MCP Rule Server app settings to include this data."
    }

    return info
}

def toolGetModes() {
    def modes = location.modes?.collect { [id: it.id.toString(), name: it.name] }
    return [
        currentMode: location.mode,
        modes: modes
    ]
}

def toolSetMode(modeName) {
    def mode = location.modes?.find { it.name.equalsIgnoreCase(modeName) }
    if (!mode) {
        def available = location.modes?.collect { it.name }
        throw new IllegalArgumentException("Mode '${modeName}' not found. Available: ${available}")
    }

    // Capture current mode BEFORE changing it
    def previousMode = location.mode
    location.setMode(mode.name)

    return [
        success: true,
        previousMode: previousMode,
        newMode: mode.name
    ]
}

def toolListVariables() {
    def hubVariables = []
    try {
        def allVars = getAllGlobalConnectorVariables()
        if (allVars) {
            hubVariables = allVars.collect { name, var ->
                [name: name, value: var?.value, type: var?.type, source: "hub"]
            }
        }
    } catch (Exception e) {
        logDebug("Hub connector variables not available: ${e.message}")
    }

    def ruleVariables = state.ruleVariables?.collect { name, value ->
        [name: name, value: value, source: "rule_engine"]
    } ?: []

    return [
        hubVariables: hubVariables,
        ruleVariables: ruleVariables,
        total: hubVariables.size() + ruleVariables.size()
    ]
}

def toolGetVariable(name) {
    try {
        def hubVar = getGlobalConnectorVariable(name)
        if (hubVar != null) {
            return [name: name, value: hubVar, source: "hub"]
        }
    } catch (Exception e) {
        logDebug("Hub connector variable '${name}' not found or not accessible: ${e.message}")
    }

    def ruleVar = state.ruleVariables?.get(name)
    if (ruleVar != null) {
        return [name: name, value: ruleVar, source: "rule_engine"]
    }

    throw new IllegalArgumentException("Variable not found: ${name}")
}

def toolSetVariable(name, value) {
    try {
        setGlobalConnectorVariable(name, value)
        return [success: true, name: name, value: value, source: "hub"]
    } catch (Exception e) {
        if (!state.ruleVariables) state.ruleVariables = [:]
        state.ruleVariables[name] = value
        return [success: true, name: name, value: value, source: "rule_engine"]
    }
}

def toolDeleteHubVariable(args) {
    requireHubAdminWrite(args.confirm)
    def name = args.name
    if (!name) throw new IllegalArgumentException("name is required")

    // Currently only rule_engine variables are addressable from this MCP — connector
    // variables (Settings → Hub Variables) live in a separate namespace not yet
    // accessible from this app.
    if (!state.ruleVariables?.containsKey(name)) {
        throw new IllegalArgumentException("Variable '${name}' not found in rule_engine namespace. (Connector-namespace deletion not yet supported via MCP — use Settings → Hub Variables UI.)")
    }

    // Pre-deletion safety scan: child rule apps that reference this variable will silently
    // break on next access (null lookup → conditions flip false, %varname% substitution
    // leaves literal text). Block by default — caller must opt in via force=true after
    // acknowledging the breakage. Match heuristic: variable name appears in the rule's
    // serialized triggers/conditions/actions JSON.
    def force = args.force == true
    def consumers = []
    try {
        getChildApps()?.each { child ->
            def ruleData = null
            try { ruleData = child.getRuleData() } catch (Exception e) { /* not an MCP rule child */ }
            if (ruleData) {
                def serialized = groovy.json.JsonOutput.toJson(ruleData)
                if (serialized?.contains(name)) {
                    consumers << [id: child.id, label: child.label]
                }
            }
        }
    } catch (Exception e) {
        // Defensive: if the scan itself errors, fall through to apply normal force gate
        // rather than blocking deletion entirely. Log so investigators know the scan
        // didn't run.
        logDebug("delete_variable: getChildApps() scan failed: ${e.class.simpleName}: ${e.message}")
    }
    if (consumers && !force) {
        throw new IllegalArgumentException(
            "Variable '${name}' is referenced by ${consumers.size()} rule(s): " +
            "${consumers.collect { "${it.label} (id=${it.id})" }.join(', ')}. " +
            "Deleting will silently break those rules (null lookups, false conditions, " +
            "literal %${name}% in substitutions). Pass force=true to proceed anyway, " +
            "or update/remove the consuming rules first."
        )
    }

    def previousValue = state.ruleVariables[name]
    // Hubitat-sandbox quirk: nested-map mutations on state are not persisted across
    // hub reboot / app restart unless the top-level key is reassigned. Same pattern
    // used by toolSetLogLevel for state.debugLogs.config. Read-modify-write the whole
    // map to force serialization.
    def updated = state.ruleVariables.findAll { k, v -> k != name }
    state.ruleVariables = updated

    def prevStr = previousValue?.toString()
    def auditValue = prevStr == null ? "null" : (prevStr.length() > 80 ? "${prevStr.take(80)} [truncated, original length: ${prevStr.length()}]" : prevStr)
    def consumerNote = consumers ? " (forced; ${consumers.size()} rule(s) now broken: ${consumers.collect { "id=${it.id}" }.join(', ')})" : ""
    mcpLog("warn", "developer-mode", "delete_variable: removed '${name}' (previous value: ${auditValue})${consumerNote}")
    return [success: true, name: name, deleted: true, source: "rule_engine", previousValue: previousValue, brokenConsumers: consumers ?: null]
}

def toolUpdateMcpSettings(args) {
    // IllegalArgumentException (not IllegalStateException) so the dispatcher routes this
    // through the clean -32602 Invalid params branch in handleToolsCall — same exception
    // type all other gates throw (requireHubAdminWrite, requireBuiltinAppRead). Toggle-off
    // is a config refusal, not an unexpected runtime error worth a stack trace + ERROR log.
    if (!settings.enableDeveloperMode) {
        throw new IllegalArgumentException("Developer Mode tools are disabled. Enable 'Developer Mode Tools' in MCP rule app settings to use update_mcp_settings.")
    }
    requireHubAdminWrite(args.confirm)

    if (!args.settings || !(args.settings instanceof Map) || args.settings.isEmpty()) {
        throw new IllegalArgumentException("settings must be a non-empty map of {settingName: newValue}")
    }

    // Allowlist of settings that can be modified via this tool, with their Hubitat input
    // type (matches the input "<key>", "<type>", ... declarations in the mainPage section).
    // Excluded:
    //   enableHubAdminWrite  — would footgun: could disable own write path mid-session
    //   enableDeveloperMode  — lockout protection (must remain UI-only to disable)
    //   selectedDevices      — capability multi-select, separate tool planned (Developer Mode follow-up)
    def allowedSettings = [
        "mcpLogLevel":            "enum",
        "debugLogging":           "bool",
        "maxCapturedStates":      "number",
        "loopGuardMax":           "number",
        "loopGuardWindowSec":     "number",
        "enableHubAdminRead":     "bool",
        "enableBuiltinAppRead":   "bool",
        "enableRuleEngine":       "bool"
    ]

    // Validate, coerce, and stage each update. Validation is fully atomic — every key
    // and every value is checked BEFORE any app.updateSetting() fires, so a single bad
    // entry in a multi-key batch can't leave the hub in a half-applied state. This
    // includes per-key sub-validation that would otherwise only fire during apply
    // (e.g. mcpLogLevel against getLogLevels()).
    //
    // Type coercion is also mandatory: a JSON-RPC client (e.g. a curl-based CI script)
    // may send "true"/"false" or "50" as strings instead of native bool/number. Without
    // coercion, app.updateSetting(key, [type:'bool', value:'false']) writes the string
    // "false", which is truthy in Groovy — silently flipping the toggle to enabled
    // when the caller meant disabled.
    def updates = [:]
    args.settings.each { key, value ->
        def keyStr = key.toString()
        if (!allowedSettings.containsKey(keyStr)) {
            throw new IllegalArgumentException("Setting '${keyStr}' is not allowed for self-modification via update_mcp_settings. Allowed: ${allowedSettings.keySet().sort().join(', ')}")
        }
        def coerced = coerceSettingValue(keyStr, value, allowedSettings[keyStr])
        // Per-key sub-validation that the apply step would otherwise discover too late.
        // mcpLogLevel must be one of the configured log levels — if 'blarg' slipped through
        // the enum coerce and only failed inside toolSetLogLevel during apply, any prior
        // app.updateSetting() calls in the same batch would have already landed.
        if (keyStr == "mcpLogLevel" && !getLogLevels().contains(coerced)) {
            throw new IllegalArgumentException("Setting 'mcpLogLevel' must be one of ${getLogLevels()}, got: ${coerced}")
        }
        updates[keyStr] = coerced
    }

    // Two-phase audit so post-mortem investigators can distinguish "validation accepted N
    // keys" (attempted) from "all N writes landed" (applied). Both fire at WARN.
    mcpLog("warn", "developer-mode", "update_mcp_settings: attempted=${updates}")

    // Apply each update via app.updateSetting() — the documented Hubitat sandbox API for
    // self-modifying app settings. mcpLogLevel needs special handling because the runtime
    // log threshold is cached in state.debugLogs.config (UI display reads from settings).
    //
    // Apply order is intentional: app.updateSetting calls first, then mcpLogLevel last via
    // toolSetLogLevel. If toolSetLogLevel ever evolves to throw on an unexpected condition,
    // the rest of the batch has already landed. Per-key validation above is the primary
    // safeguard; this ordering is belt-and-suspenders.
    updates.each { key, value ->
        if (key == "mcpLogLevel") {
            // Delegate to existing helper — it updates both state cache + setting
            toolSetLogLevel([level: value.toString()])
        } else {
            app.updateSetting(key, [type: allowedSettings[key], value: value])
        }
    }

    mcpLog("warn", "developer-mode", "update_mcp_settings: applied=${updates}")

    return [
        success: true,
        updated: updates,
        message: "Updated ${updates.size()} setting(s). MCP clients (Claude Code, etc.) may need to reconnect to refresh cached tool schemas if you toggled an enable* flag."
    ]
}

// Coerce + validate a single setting value into the type the Hubitat input expects.
// Throws IllegalArgumentException for unrepresentable values (rather than silently
// substituting a default — that would mask the caller's intent and recreate the same
// kind of footgun the bool string-truthiness bug is fixing).
//
// - bool: accepts native Boolean, plus case-insensitive "true"/"false" strings.
// - number: accepts native Number, plus integer/decimal-formatted strings.
// - enum: passed through; downstream (e.g. toolSetLogLevel) does its own enum-membership
//         check against a tool-specific allowed list.
private coerceSettingValue(String key, value, String type) {
    if (value == null) {
        throw new IllegalArgumentException("Setting '${key}' value cannot be null")
    }
    switch (type) {
        case "bool":
            if (value instanceof Boolean) return value
            def s = value.toString().toLowerCase()
            if (s == "true") return true
            if (s == "false") return false
            throw new IllegalArgumentException("Setting '${key}' expects a boolean (true/false), got: ${value} (${value.class.simpleName})")

        case "number":
            if (value instanceof Number) return value
            def s = value.toString()
            if (s.isInteger()) return s.toInteger()
            if (s.isLong()) return s.toLong()
            if (s.isBigDecimal()) return s.toBigDecimal()
            throw new IllegalArgumentException("Setting '${key}' expects a number, got: ${value} (${value.class.simpleName})")

        case "enum":
            // Pass through as String — downstream validates against tool-specific enum set.
            return value.toString()

        default:
            // Defensive: unknown type from the allowlist map should not reach here.
            return value
    }
}

// Helper method for child apps to get variable values
def getVariableValue(name) {
    try {
        def hubVar = getGlobalConnectorVariable(name)
        if (hubVar != null) return hubVar
    } catch (Exception e) {
        // Hub connector variable not found — fall through to rule_engine namespace.
        // Logged at DEBUG so investigators can tell whether the connector lookup
        // genuinely missed (no such variable) or errored for some other reason.
        logDebug("getVariableValue: connector lookup for '${name}' threw ${e.class.simpleName}: ${e.message}")
    }
    return state.ruleVariables?.get(name)
}

// Helper method for child apps to set rule-scoped variables
def setRuleVariable(name, value) {
    if (!state.ruleVariables) state.ruleVariables = [:]
    state.ruleVariables[name] = value
}

// Get the user-configured max captured states limit (default: 20, minimum: 1)
def getMaxCapturedStates() {
    def max = settings.maxCapturedStates ?: 20
    // Ensure minimum of 1 to prevent infinite loops in cleanup logic
    return max < 1 ? 1 : (max > 100 ? 100 : max)
}

// Helper method for child apps to save captured device states (for capture_state action)
// Returns info about the save operation including any deleted states
def saveCapturedState(stateId, capturedStates) {
    if (!state.capturedDeviceStates) state.capturedDeviceStates = [:]

    // Add timestamp to the captured state
    def stateEntry = [
        devices: capturedStates,
        timestamp: now(),
        deviceCount: capturedStates.size()
    ]

    def deletedStates = []

    // Check if we need to remove old entries (only if this is a new stateId)
    if (!state.capturedDeviceStates.containsKey(stateId)) {
        while (state.capturedDeviceStates.size() >= getMaxCapturedStates()) {
            // Find and remove the oldest entry
            def oldestId = null
            def oldestTime = Long.MAX_VALUE
            state.capturedDeviceStates.each { id, entry ->
                def entryTime = entry.timestamp ?: 0
                if (entryTime < oldestTime) {
                    oldestTime = entryTime
                    oldestId = id
                }
            }
            if (oldestId) {
                log.warn "Captured states at limit (${getMaxCapturedStates()}): Removing oldest state '${oldestId}' to make room for '${stateId}'"
                deletedStates << oldestId
                state.capturedDeviceStates.remove(oldestId)
            } else {
                break // Safety: avoid infinite loop
            }
        }
    }

    state.capturedDeviceStates[stateId] = stateEntry
    def totalStored = state.capturedDeviceStates.size()
    log.debug "Saved captured state '${stateId}' with ${capturedStates.size()} devices (total stored: ${totalStored}/${getMaxCapturedStates()})"

    return [
        stateId: stateId,
        deviceCount: capturedStates.size(),
        totalStored: totalStored,
        maxLimit: getMaxCapturedStates(),
        deletedStates: deletedStates,
        nearLimit: totalStored >= getMaxCapturedStates() - 4
    ]
}

// Helper method for child apps to retrieve captured device states (for restore_state action)
def getCapturedState(stateId) {
    def entry = state.capturedDeviceStates?.get(stateId)
    // Return the devices array for backward compatibility
    return entry?.devices ?: entry
}

// Helper method to list all captured states with metadata
def listCapturedStates() {
    if (!state.capturedDeviceStates) return []

    return state.capturedDeviceStates.collect { stateId, entry ->
        [
            stateId: stateId,
            deviceCount: entry.deviceCount ?: entry.devices?.size() ?: (entry instanceof List ? entry.size() : 0),
            timestamp: entry.timestamp ?: null,
            capturedAt: entry.timestamp ? new Date(entry.timestamp).format("yyyy-MM-dd HH:mm:ss") : "unknown"
        ]
    }.sort { a, b -> (b.timestamp ?: 0) <=> (a.timestamp ?: 0) } // Sort newest first
}

// Helper method to delete a specific captured state
def deleteCapturedState(stateId) {
    if (!state.capturedDeviceStates) {
        return [success: false, message: "No captured states exist"]
    }

    if (!state.capturedDeviceStates.containsKey(stateId)) {
        return [success: false, message: "Captured state '${stateId}' not found"]
    }

    state.capturedDeviceStates.remove(stateId)
    log.debug "Deleted captured state '${stateId}' (remaining: ${state.capturedDeviceStates.size()})"
    return [success: true, message: "Captured state '${stateId}' deleted", remaining: state.capturedDeviceStates.size()]
}

// Helper method to clear all captured states
def clearAllCapturedStates() {
    def count = state.capturedDeviceStates?.size() ?: 0
    state.capturedDeviceStates = [:]
    log.debug "Cleared all ${count} captured states"
    return [success: true, message: "Cleared ${count} captured state(s)", cleared: count]
}

def toolGetHsmStatus() {
    def hsmStatus = location.hsmStatus
    def hsmAlerts = location.hsmAlert

    return [
        status: hsmStatus,
        alert: hsmAlerts,
        modes: ["disarm", "armAway", "armHome", "armNight"]
    ]
}

def toolSetHsm(mode) {
    def validModes = ["armAway", "armHome", "armNight", "disarm"]
    if (!validModes.contains(mode)) {
        throw new IllegalArgumentException("Invalid HSM mode: ${mode}. Valid modes: ${validModes}")
    }

    // Capture current status BEFORE sending the change event
    def previousStatus = location.hsmStatus
    sendLocationEvent(name: "hsmSetArm", value: mode)

    return [
        success: true,
        previousStatus: previousStatus,
        newMode: mode
    ]
}

// ==================== CAPTURED STATE TOOLS ====================

def toolListCapturedStates() {
    def states = listCapturedStates()
    def count = states.size()
    def result = [
        capturedStates: states,
        count: count,
        maxLimit: getMaxCapturedStates()
    ]

    // Add warnings when approaching or at limit
    if (count >= getMaxCapturedStates()) {
        result.warning = "At maximum capacity (${getMaxCapturedStates()}). New captures will delete the oldest entry."
    } else if (count >= getMaxCapturedStates() - 4) {
        result.warning = "Approaching limit: ${count}/${getMaxCapturedStates()} slots used. Consider cleaning up unused captures."
    }

    return result
}

def toolDeleteCapturedState(stateId) {
    if (!stateId) {
        throw new IllegalArgumentException("stateId is required")
    }
    return deleteCapturedState(stateId)
}

def toolClearCapturedStates() {
    return clearAllCapturedStates()
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
def getHubSecurityCookie() {
    if (!settings.hubSecurityEnabled) return null
    if (!settings.hubSecurityUser || !settings.hubSecurityPassword) {
        mcpLog("warn", "hub-admin", "Hub Security is enabled but credentials are not configured")
        return null
    }

    // Check if we have a valid cached cookie
    if (state.hubSecurityCookie && state.hubSecurityCookieExpiry && state.hubSecurityCookieExpiry > now()) {
        return state.hubSecurityCookie
    }

    // Authenticate
    def cookie = null
    try {
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/login",
            body: [username: settings.hubSecurityUser, password: settings.hubSecurityPassword],
            textParser: true,
            ignoreSSLIssues: true
        ]) { resp ->
            cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Hub Security authentication failed: ${e.message}")
        throw new RuntimeException("Hub Security authentication failed. Check your username and password in MCP Rule Server settings.")
    }

    if (cookie) {
        state.hubSecurityCookie = cookie
        state.hubSecurityCookieExpiry = now() + (30 * 60 * 1000) // 30 minutes
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
    if (!isRetry && settings.hubSecurityEnabled &&
        (e.message?.contains("401") || e.message?.contains("403") || e.message?.contains("Unauthorized"))) {
        state.hubSecurityCookie = null
        state.hubSecurityCookieExpiry = null
        return true
    }
    return false
}

/**
 * Make an authenticated GET request to the hub's internal API.
 * Automatically includes Hub Security cookie if configured.
 * Returns the response body as text.
 */
def hubInternalGet(String path, Map query = null, int timeout = 30, boolean isRetry = false) {
    def cookie = getHubSecurityCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        textParser: true,
        ignoreSSLIssues: true,
        timeout: timeout
    ]
    if (query) {
        params.query = query
    }
    if (cookie) {
        params.headers = ["Cookie": cookie]
    }

    def responseText = null
    try {
        httpGet(params) { resp ->
            // textParser: true returns a Reader/InputStream — try .text first to read it fully
            // Sandbox blocks instanceof and getClass(), so use try-catch duck typing
            try {
                responseText = resp.data.text
            } catch (Exception readErr) {
                responseText = resp.data?.toString()
            }
        }
    } catch (Exception e) {
        if (shouldRetryWithFreshCookie(e, isRetry)) {
            mcpLog("debug", "hub-admin", "Retrying with fresh cookie after auth failure on GET ${path}")
            return hubInternalGet(path, query, timeout, true)
        }
        throw e
    }
    return responseText
}

/**
 * Make an authenticated POST request to the hub's internal API.
 * Automatically includes Hub Security cookie if configured.
 * Returns the response body as text.
 */
def hubInternalPost(String path, Map body = null, boolean isRetry = false) {
    def cookie = getHubSecurityCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        textParser: true,
        ignoreSSLIssues: true,
        timeout: 30
    ]
    if (cookie) {
        params.headers = ["Cookie": cookie]
    }
    if (body) {
        params.body = body
    }

    def responseText = null
    try {
        httpPost(params) { resp ->
            responseText = resp.data?.text?.toString() ?: resp.data?.toString()
        }
    } catch (Exception e) {
        if (shouldRetryWithFreshCookie(e, isRetry)) {
            mcpLog("debug", "hub-admin", "Retrying with fresh cookie after auth failure on POST ${path}")
            return hubInternalPost(path, body, true)
        }
        throw e
    }
    return responseText
}

/**
 * Make an authenticated POST request to the hub's internal API with form-encoded body.
 * Used for app/driver management endpoints that require application/x-www-form-urlencoded.
 */
def hubInternalPostForm(String path, Map body, int timeout = 420, boolean isRetry = false) {
    def cookie = getHubSecurityCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        requestContentType: "application/x-www-form-urlencoded",
        textParser: true, // Accept any content type without parse errors
        headers: [
            "Connection": "keep-alive"
        ],
        body: body,
        timeout: timeout,
        ignoreSSLIssues: true
    ]
    if (cookie) {
        params.headers["Cookie"] = cookie
    }

    def result = null
    try {
        httpPost(params) { resp ->
            def responseData = resp.data
            // textParser: true returns a Reader/InputStream — try .text to read it fully
            try {
                responseData = responseData.text
            } catch (Exception readErr) {
                responseData = responseData?.toString()
            }
            result = [
                status: resp.status,
                location: resp.headers?."Location"?.toString(),
                data: responseData
            ]
        }
    } catch (Exception e) {
        if (shouldRetryWithFreshCookie(e, isRetry)) {
            mcpLog("debug", "hub-admin", "Retrying with fresh cookie after auth failure on POST-form ${path}")
            return hubInternalPostForm(path, body, timeout, true)
        }
        throw e
    }
    return result
}

/**
 * Check if Hub Admin Read access is enabled. Throws if not.
 */
def requireHubAdminRead() {
    if (!settings.enableHubAdminRead) {
        throw new IllegalArgumentException("Hub Admin Read access is disabled. Enable 'Enable Hub Admin Read Tools' in MCP Rule Server app settings to use this tool.")
    }
}

/**
 * Check if Built-in App Read access is enabled. Throws if not.
 * Gates installed-app enumeration, device-in-use-by lookup, and Rule Machine interop tools.
 */
def requireBuiltinAppRead() {
    if (!settings.enableBuiltinAppRead) {
        throw new IllegalArgumentException("Built-in App Tools are disabled. Enable 'Enable Built-in App Tools' in MCP Rule Server app settings to use this tool.")
    }
}

/**
 * Check if Hub Admin Write access is enabled and a recent backup exists. Throws if not.
 */
def requireHubAdminWrite(Boolean confirmParam) {
    if (!settings.enableHubAdminWrite) {
        throw new IllegalArgumentException("Hub Admin Write access is disabled. Enable 'Enable Hub Admin Write Tools' in MCP Rule Server app settings to use this tool.")
    }
    if (!confirmParam) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: You must set confirm=true to use this tool. Did you create a backup with create_hub_backup first? Review the tool description for the mandatory pre-flight checklist.")
    }
    // Check for recent hub backup (within 24 hours)
    if (!state.lastBackupTimestamp || (now() - state.lastBackupTimestamp) > 86400000) {
        throw new IllegalArgumentException("BACKUP REQUIRED: No hub backup found within the last 24 hours. You MUST call create_hub_backup FIRST and verify it succeeds before using any Hub Admin Write tool. Last backup: ${state.lastBackupTimestamp ? formatTimestamp(state.lastBackupTimestamp) : 'Never'}")
    }
}

/**
 * Automatically back up an individual item's source code before modifying or deleting it.
 * Saves the source code as a .groovy file in the hub's local File Manager using uploadHubFile().
 * Metadata (timestamp, version, etc.) is stored in state.itemBackupManifest.
 * Files are accessible at http://<HUB_IP>/local/<filename> even if MCP fails.
 * If a backup of this item already exists within the last hour, skips (preserves the pre-edit original).
 * Returns the manifest entry on success, or throws if the source cannot be retrieved.
 */
def backupItemSource(String type, String id) {
    if (!state.itemBackupManifest) state.itemBackupManifest = [:]

    def key = "${type}_${id}"
    def existing = state.itemBackupManifest[key]

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
        mcpLog("error", "hub-admin", "Failed to save backup file '${fileName}': ${e.message}")
        throw new IllegalArgumentException("Cannot back up ${type} ID ${id}: file upload failed — ${e.message}")
    }

    def manifest = [
        type: type,
        id: id,
        fileName: fileName,
        version: parsed.version,
        timestamp: now(),
        sourceLength: parsed.source.length()
    ]
    state.itemBackupManifest[key] = manifest

    // Prune old backups — keep at most 20 entries, remove oldest if over limit
    if (state.itemBackupManifest.size() > 20) {
        def oldest = state.itemBackupManifest.min { it.value.timestamp }
        if (oldest) {
            mcpLog("debug", "hub-admin", "Pruning oldest backup: ${oldest.key} (${oldest.value.fileName}, from ${formatTimestamp(oldest.value.timestamp)})")
            try { deleteHubFile(oldest.value.fileName) } catch (Exception e) {
                mcpLog("warn", "hub-admin", "Could not delete pruned backup file '${oldest.value.fileName}': ${e.message}")
            }
            state.itemBackupManifest.remove(oldest.key)
        }
    }

    mcpLog("info", "hub-admin", "Backed up ${type} ID ${id} source code to File Manager: ${fileName} (version ${parsed.version}, ${parsed.source.length()} chars)")
    return manifest
}

// ==================== ITEM BACKUP TOOLS ====================

/**
 * Lists all item backups stored in the hub's local File Manager.
 * Metadata is in state.itemBackupManifest; actual source files are in File Manager.
 * Does not require Hub Admin Read/Write — always available.
 */
def toolListItemBackups() {
    def manifest = state.itemBackupManifest ?: [:]

    if (manifest.isEmpty()) {
        return [
            backups: [],
            total: 0,
            message: "No item backups exist yet. Backups are created automatically when you use update_app_code, update_driver_code, delete_app, or delete_driver.",
            maxBackups: 20,
            storage: "Backups are stored as .groovy files in the hub's File Manager. You can access them at http://<HUB_IP>/local/<filename> or via Hubitat > Settings > File Manager.",
            howToRestore: "Use 'get_item_backup' to retrieve source code, then 'restore_item_backup' to restore. For deleted items, use 'install_app' or 'install_driver' with the backup source."
        ]
    }

    def backupList = manifest.collect { key, entry ->
        [
            backupKey: key,
            type: entry.type,
            id: entry.id,
            fileName: entry.fileName,
            version: entry.version,
            timestampEpoch: entry.timestamp ?: 0,
            timestamp: formatTimestamp(entry.timestamp),
            age: formatAge(entry.timestamp),
            sourceLength: entry.sourceLength ?: 0,
            directDownload: "http://<HUB_IP>/local/${entry.fileName}"
        ]
    }.sort { a, b -> (b.timestampEpoch <=> a.timestampEpoch) } // Newest first

    return [
        backups: backupList,
        total: backupList.size(),
        maxBackups: 20,
        storage: "Backup files are stored in the hub's local File Manager (Settings > File Manager). Files persist even if MCP is uninstalled.",
        howToRestore: "Use 'restore_item_backup' with a backupKey to restore via MCP. Or download the .groovy file from File Manager and paste it into Apps Code / Drivers Code manually.",
        manualRestore: "Go to Hubitat > Settings > File Manager to see backup files. Download a file, then go to Apps Code (or Drivers Code) > select the app/driver > paste the source > click Save."
    ]
}

/**
 * Retrieves the full source code from a specific item backup.
 * Reads the file from the hub's local File Manager using downloadHubFile().
 * Does not require Hub Admin Read/Write.
 */
def toolGetItemBackup(args) {
    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123' or 'driver_456')")

    def manifest = state.itemBackupManifest ?: [:]
    def entry = manifest[args.backupKey]

    if (!entry) {
        mcpLog("debug", "hub-admin", "Backup key '${args.backupKey}' not found in manifest")
        def availableKeys = manifest.keySet().sort()
        return [
            error: "No backup found for key '${args.backupKey}'",
            availableBackups: availableKeys.isEmpty() ? "None — no backups exist yet" : availableKeys.join(", "),
            hint: "Use 'list_item_backups' to see all available backups with details"
        ]
    }

    // Read source code from hub's local File Manager
    def source
    try {
        def bytes = downloadHubFile(entry.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        source = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Failed to read backup file '${entry.fileName}': ${e.message}")
        return [
            error: "Backup file '${entry.fileName}' could not be read: ${e.message}",
            backupKey: args.backupKey,
            suggestion: "The file may have been deleted from File Manager. Check Hubitat > Settings > File Manager.",
            directDownload: "http://<HUB_IP>/local/${entry.fileName}"
        ]
    }

    def result = [
        backupKey: args.backupKey,
        type: entry.type,
        id: entry.id,
        fileName: entry.fileName,
        version: entry.version,
        timestamp: formatTimestamp(entry.timestamp),
        age: formatAge(entry.timestamp),
        sourceLength: source.length(),
        directDownload: "http://<HUB_IP>/local/${entry.fileName}"
    ]

    // Only include source in response if it fits within the hub's response limit
    // For large files, direct the user to download from File Manager instead
    if (source.length() <= 60000) {
        result.source = source
    } else {
        result.sourceTooLargeForResponse = true
        result.message = "Source code is ${source.length()} chars — too large for an MCP response. Download it directly from File Manager instead."
        result.manualDownload = "Go to http://<HUB_IP>/local/${entry.fileName} in your browser, or find it in Hubitat > Settings > File Manager."
    }

    result.howToRestore = (entry.type == "app")
        ? "To restore via MCP: call 'restore_item_backup' with backupKey='${args.backupKey}' and confirm=true. To restore manually: download ${entry.fileName} from File Manager, go to Hubitat > Apps Code > app ID ${entry.id} > paste source > Save."
        : "To restore via MCP: call 'restore_item_backup' with backupKey='${args.backupKey}' and confirm=true. To restore manually: download ${entry.fileName} from File Manager, go to Hubitat > Drivers Code > driver ID ${entry.id} > paste source > Save."

    return result
}

/**
 * Restores an app or driver to its backed-up source code.
 * Reads the backup from File Manager and calls update_app_code or update_driver_code.
 * Both the read (downloadHubFile) and write (hubInternalPostForm) are local — no cloud involvement.
 * Requires Hub Admin Write access.
 */
def toolRestoreItemBackup(args) {
    requireHubAdminWrite(args.confirm)

    if (!args.backupKey) throw new IllegalArgumentException("backupKey is required (e.g., 'app_123' or 'driver_456')")

    def manifest = state.itemBackupManifest ?: [:]
    def entry = manifest[args.backupKey]

    if (!entry) {
        mcpLog("debug", "hub-admin", "Restore: backup key '${args.backupKey}' not found in manifest")
        def availableKeys = manifest.keySet().sort()
        return [
            success: false,
            error: "No backup found for key '${args.backupKey}'",
            availableBackups: availableKeys.isEmpty() ? "None" : availableKeys.join(", ")
        ]
    }

    // Read the backup source from File Manager
    def source
    try {
        def bytes = downloadHubFile(entry.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        source = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Failed to read backup file '${entry.fileName}' for restore: ${e.message}")
        return [
            success: false,
            error: "Backup file '${entry.fileName}' could not be read: ${e.message}",
            backupKey: args.backupKey,
            suggestion: "The file may have been deleted from File Manager. Check Hubitat > Settings > File Manager."
        ]
    }

    if (!source) {
        mcpLog("warn", "hub-admin", "Backup file '${entry.fileName}' is empty — cannot restore")
        return [
            success: false,
            error: "Backup file exists but is empty",
            backupKey: args.backupKey
        ]
    }

    mcpLog("info", "hub-admin", "Restoring ${entry.type} ID ${entry.id} from backup file ${entry.fileName} (version ${entry.version}, ${formatTimestamp(entry.timestamp)})")

    // Save a copy of the entry before modifying manifest
    def entryCopy = entry.clone()

    // Before restoring, back up the CURRENT source under a different filename so it's not overwritten
    // (the original backup file uses the same deterministic name, so backupItemSource would overwrite it)
    def preRestoreFileName = "mcp-prerestore-${entryCopy.type}-${entryCopy.id}.groovy"
    def preRestoreBackupKey = "prerestore_${entryCopy.type}_${entryCopy.id}"
    try {
        def ajaxPath = (entryCopy.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def responseText = hubInternalGet(ajaxPath, [id: entryCopy.id])
        if (responseText) {
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            if (parsed.source) {
                uploadHubFile(preRestoreFileName, parsed.source.getBytes("UTF-8"))
                if (!state.itemBackupManifest) state.itemBackupManifest = [:]
                state.itemBackupManifest[preRestoreBackupKey] = [
                    type: entryCopy.type, id: entryCopy.id, fileName: preRestoreFileName,
                    version: parsed.version, timestamp: now(), sourceLength: parsed.source.length()
                ]
                mcpLog("info", "hub-admin", "Pre-restore backup saved: ${preRestoreFileName} (version ${parsed.version}, ${parsed.source.length()} chars)")
            }
        }
    } catch (Exception preBackupErr) {
        mcpLog("warn", "hub-admin", "Could not create pre-restore backup: ${preBackupErr.message} — proceeding with restore anyway")
    }

    // Now push the backup source directly via the hub internal API (bypass toolUpdateAppCode to avoid
    // its backupItemSource call which would overwrite our original backup file)
    try {
        // Fetch current version for optimistic locking
        def ajaxPath = (entryCopy.type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def versionResp = hubInternalGet(ajaxPath, [id: entryCopy.id])
        def currentVersion = null
        if (versionResp) {
            try {
                def vParsed = new groovy.json.JsonSlurper().parseText(versionResp)
                currentVersion = vParsed.version
            } catch (Exception vErr) { /* proceed without version */ }
        }

        def updatePath = (entryCopy.type == "app") ? "/app/ajax/update" : "/driver/ajax/update"
        def result = hubInternalPostForm(updatePath, [
            id: entryCopy.id,
            version: currentVersion ?: entryCopy.version,
            source: source
        ])

        def responseData = result?.data
        def success = false
        def errorMsg = null
        if (responseData) {
            try {
                def parsed = (responseData instanceof String) ? new groovy.json.JsonSlurper().parseText(responseData) : responseData
                success = parsed.status == "success"
                errorMsg = parsed.errorMessage
            } catch (Exception parseErr) {
                mcpLog("warn", "hub-admin", "Restore update response was not JSON: ${responseData?.toString()?.take(200)}")
                errorMsg = "Unexpected response format — restore may have succeeded but could not be confirmed."
            }
        } else {
            success = true
        }

        if (success) {
            mcpLog("info", "hub-admin", "Restore succeeded: ${entryCopy.type} ID ${entryCopy.id} restored to version ${entryCopy.version}")
            return [
                success: true,
                message: "Restored ${entryCopy.type} ID ${entryCopy.id} to version ${entryCopy.version} (backup from ${formatTimestamp(entryCopy.timestamp)})",
                type: entryCopy.type,
                id: entryCopy.id,
                restoredVersion: entryCopy.version,
                preRestoreBackup: preRestoreBackupKey,
                preRestoreFile: preRestoreFileName,
                undoHint: "To undo this restore, use 'restore_item_backup' with backupKey='${preRestoreBackupKey}'"
            ]
        } else {
            mcpLog("error", "hub-admin", "Restore failed for ${entryCopy.type} ID ${entryCopy.id}: ${errorMsg ?: 'unknown error'}")
            return [
                success: false,
                error: "Restore failed: ${errorMsg ?: 'unknown error'}",
                backupKey: args.backupKey,
                message: "The backup has been preserved — you can try again or restore manually.",
                directDownload: "http://<HUB_IP>/local/${entryCopy.fileName}"
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Restore failed with exception for ${entryCopy.type} ID ${entryCopy.id}: ${e.message}")
        return [
            success: false,
            error: "Restore failed: ${e.message}",
            backupKey: args.backupKey,
            message: "The backup has been preserved — you can try again or restore manually.",
            directDownload: "http://<HUB_IP>/local/${entryCopy.fileName}"
        ]
    }
}

// ==================== FILE MANAGER TOOLS ====================

/**
 * Lists all files in the hub's local File Manager.
 * Uses the hub internal API to query the file list.
 */
def toolListFiles() {
    mcpLog("debug", "file-manager", "Listing files in File Manager")

    // Try known File Manager API endpoints (varies by firmware version)
    def endpoints = ["/hub/fileManager/json", "/hub/fileManager"]
    def responseText = null
    def endpointUsed = null

    for (endpoint in endpoints) {
        try {
            responseText = hubInternalGet(endpoint)
            if (responseText) {
                endpointUsed = endpoint
                mcpLog("debug", "file-manager", "Got response from ${endpoint} (${responseText.length()} chars): ${responseText.take(300)}")
                break
            }
        } catch (Exception e) {
            mcpLog("debug", "file-manager", "Endpoint ${endpoint} failed: ${e.message}")
        }
    }

    if (!responseText) {
        return [
            files: [],
            total: 0,
            message: "File Manager API not available on this firmware. Use Hubitat > Settings > File Manager to view files.",
            manualAccess: "Go to Hubitat > Settings > File Manager to view files in the web UI."
        ]
    }

    try {
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        def fileList = []

        if (parsed instanceof List) {
            // Direct list response: [{name: "file.txt", size: 123}, ...]
            fileList = parsed.collect { f ->
                def name = (f instanceof Map) ? (f.name ?: f.toString()) : f.toString()
                def entry = [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                if (f instanceof Map) {
                    if (f.size != null) entry.size = f.size
                    if (f.date) entry.lastModified = f.date
                }
                return entry
            }
        } else if (parsed instanceof Map) {
            // Object response: {files: [...]} or {type: [...]}
            def files = parsed.files ?: parsed.values()?.flatten()
            if (files instanceof List) {
                fileList = files.collect { f ->
                    def name = (f instanceof Map) ? (f.name ?: f.toString()) : f.toString()
                    def entry = [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                    if (f instanceof Map) {
                        if (f.size != null) entry.size = f.size
                        if (f.date) entry.lastModified = f.date
                    }
                    return entry
                }
            }
        }

        fileList = fileList.sort { a, b -> (a.name <=> b.name) }

        mcpLog("info", "file-manager", "Listed ${fileList.size()} files in File Manager (via ${endpointUsed})")
        return [
            files: fileList,
            total: fileList.size(),
            storage: "Files are stored locally on the hub's file system. Access via http://<HUB_IP>/local/<filename> or Hubitat > Settings > File Manager."
        ]
    } catch (Exception jsonErr) {
        // Response wasn't JSON — might be HTML File Manager page
        mcpLog("debug", "file-manager", "Response from ${endpointUsed} was not JSON: ${jsonErr.message}")

        // Try to extract file names from HTML response
        def fileList = []
        try {
            def matcher = responseText =~ /(?i)href=["']?\/local\/([^"'\s>]+)/
            while (matcher.find()) {
                def name = java.net.URLDecoder.decode(matcher.group(1), "UTF-8")
                if (!fileList.any { it.name == name }) {
                    fileList << [name: name, directDownload: "http://<HUB_IP>/local/${name}"]
                }
            }
        } catch (Exception htmlErr) {
            mcpLog("debug", "file-manager", "HTML parsing also failed: ${htmlErr.message}")
        }

        if (fileList) {
            fileList = fileList.sort { a, b -> (a.name <=> b.name) }
            mcpLog("info", "file-manager", "Listed ${fileList.size()} files from File Manager HTML page")
            return [
                files: fileList,
                total: fileList.size(),
                note: "File list extracted from File Manager HTML page. Sizes not available. Use Hubitat > Settings > File Manager for full details.",
                storage: "Files are stored locally on the hub's file system. Access via http://<HUB_IP>/local/<filename> or Hubitat > Settings > File Manager."
            ]
        }

        return [
            files: [],
            total: 0,
            error: "Could not parse File Manager response. The API format may have changed.",
            rawResponsePreview: responseText.take(500),
            manualAccess: "Go to Hubitat > Settings > File Manager to view files in the web UI."
        ]
    }
}

/**
 * Reads the contents of a file from the hub's local File Manager.
 * Uses downloadHubFile() — fully local, no cloud involvement.
 */
def toolReadFile(args) {
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")

    def maxChunkSize = 60000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

    mcpLog("debug", "file-manager", "Reading file: ${args.fileName} (offset: ${requestedOffset}, length: ${requestedLength})")
    def content
    try {
        def bytes = downloadHubFile(args.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        content = new String(bytes, "UTF-8")
    } catch (Exception e) {
        mcpLog("error", "file-manager", "Failed to read file '${args.fileName}': ${e.message}")
        return [
            success: false,
            error: "File '${args.fileName}' could not be read: ${e.message}",
            suggestion: "Check that the file name is correct. Go to Hubitat > Settings > File Manager to see available files.",
            directDownload: "http://<HUB_IP>/local/${args.fileName}"
        ]
    }

    def totalLength = content.length()
    def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
    def chunk = (requestedOffset < totalLength) ? content.substring(requestedOffset, endIndex) : ""
    def hasMore = endIndex < totalLength

    def result = [
        success: true,
        fileName: args.fileName,
        totalLength: totalLength,
        offset: requestedOffset,
        chunkLength: chunk.length(),
        hasMore: hasMore,
        content: chunk,
        directDownload: "http://<HUB_IP>/local/${args.fileName}"
    ]
    if (hasMore) {
        result.nextOffset = endIndex
        result.remainingChars = totalLength - endIndex
        result.hint = "Call again with offset: ${endIndex} to get the next chunk."
    }

    mcpLog("info", "file-manager", "Read file '${args.fileName}' (${totalLength} chars total, returned offset ${requestedOffset}..${endIndex}${hasMore ? ', more available' : ''})")
    return result
}

/**
 * Writes or creates a file in the hub's local File Manager.
 * If the file already exists, automatically creates a backup copy first.
 * Requires Hub Admin Write access.
 */
def toolWriteFile(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")
    if (args.content == null) throw new IllegalArgumentException("content is required")

    // Validate file name — only A-Za-z0-9, hyphens, underscores, periods allowed
    if (!(args.fileName ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
        throw new IllegalArgumentException("Invalid file name '${args.fileName}'. Only letters, numbers, hyphens, underscores, and periods are allowed. Cannot start with a period.")
    }

    // If file already exists, back it up first
    def backedUp = false
    def backupFileName = null
    try {
        def existingBytes = downloadHubFile(args.fileName)
        if (existingBytes != null) {
            // File exists — create a backup before overwriting
            def dotIndex = args.fileName.lastIndexOf('.')
            def baseName = dotIndex > 0 ? args.fileName.substring(0, dotIndex) : args.fileName
            def ext = dotIndex > 0 ? args.fileName.substring(dotIndex) : ""
            def ts = new Date().format("yyyyMMdd-HHmmss")
            backupFileName = "${baseName}_backup_${ts}${ext}"
            uploadHubFile(backupFileName, existingBytes)
            backedUp = true
            mcpLog("info", "file-manager", "Backed up existing '${args.fileName}' to '${backupFileName}' before overwriting (${existingBytes.length} bytes)")
        }
    } catch (Exception e) {
        // File doesn't exist or can't be read — that's fine, proceed with write
        mcpLog("debug", "file-manager", "No existing file '${args.fileName}' to back up: ${e.message}")
    }

    // Write the file
    try {
        uploadHubFile(args.fileName, args.content.getBytes("UTF-8"))
        mcpLog("info", "file-manager", "Wrote file '${args.fileName}' (${args.content.length()} chars)")

        def result = [
            success: true,
            message: backedUp
                ? "File '${args.fileName}' updated. Previous version backed up as '${backupFileName}'."
                : "File '${args.fileName}' created.",
            fileName: args.fileName,
            contentLength: args.content.length(),
            directDownload: "http://<HUB_IP>/local/${args.fileName}"
        ]
        if (backedUp) {
            result.backupFile = backupFileName
            result.backupDownload = "http://<HUB_IP>/local/${backupFileName}"
        }
        return result
    } catch (Exception e) {
        mcpLog("error", "file-manager", "Failed to write file '${args.fileName}': ${e.message}")
        return [
            success: false,
            error: "Failed to write file '${args.fileName}': ${e.message}"
        ]
    }
}

/**
 * Deletes a file from the hub's local File Manager.
 * Automatically creates a backup copy before deletion.
 * Requires Hub Admin Write access.
 */
def toolDeleteFile(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")

    // Skip auto-backup for files that are already backups (prevent infinite backup chains)
    def isBackupFile = args.fileName.contains("_backup_") || args.fileName.startsWith("mcp-backup-") || args.fileName.startsWith("mcp-prerestore-")

    // Back up the file before deleting (unless it's already a backup file)
    def backedUp = false
    def backupFileName = null
    if (!isBackupFile) {
        try {
            def bytes = downloadHubFile(args.fileName)
            if (bytes == null) throw new Exception("File not found")
            def dotIndex = args.fileName.lastIndexOf('.')
            def baseName = dotIndex > 0 ? args.fileName.substring(0, dotIndex) : args.fileName
            def ext = dotIndex > 0 ? args.fileName.substring(dotIndex) : ""
            def ts = new Date().format("yyyyMMdd-HHmmss")
            backupFileName = "${baseName}_backup_${ts}${ext}"
            uploadHubFile(backupFileName, bytes)
            backedUp = true
            mcpLog("info", "file-manager", "Backed up '${args.fileName}' to '${backupFileName}' before deletion (${bytes.length} bytes)")
        } catch (Exception e) {
            mcpLog("warn", "file-manager", "Could not back up '${args.fileName}' before deletion: ${e.message}")
        }
    } else {
        mcpLog("debug", "file-manager", "Skipping auto-backup for '${args.fileName}' — file is itself a backup")
    }

    // Delete the file
    try {
        deleteHubFile(args.fileName)
        mcpLog("info", "file-manager", "Deleted file '${args.fileName}'")

        def result = [
            success: true,
            message: backedUp
                ? "File '${args.fileName}' deleted. Backup saved as '${backupFileName}'."
                : isBackupFile
                    ? "Backup file '${args.fileName}' deleted permanently (no backup-of-backup created)."
                    : "File '${args.fileName}' deleted. WARNING: Could not create backup before deletion.",
            fileName: args.fileName
        ]
        if (backedUp) {
            result.backupFile = backupFileName
            result.backupDownload = "http://<HUB_IP>/local/${backupFileName}"
            result.undoHint = "To recover: use 'read_file' on '${backupFileName}' to view contents, or 'write_file' to recreate '${args.fileName}' from the backup."
        }
        if (!backedUp && !isBackupFile) {
            result.warning = "The file contents could not be backed up before deletion. The data may be permanently lost."
        }
        return result
    } catch (Exception e) {
        mcpLog("error", "file-manager", "Failed to delete file '${args.fileName}': ${e.message}")
        return [
            success: false,
            error: "Failed to delete '${args.fileName}': ${e.message}",
            suggestion: "Check that the file exists. Use 'list_files' to see available files."
        ]
    }
}

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
 * Checks settings first (UI), then state (MCP set_log_level), then defaults to "error"
 */
def getConfiguredLogLevel() {
    // Settings take priority (can be set via UI)
    if (settings.mcpLogLevel) return settings.mcpLogLevel
    // Fall back to state (can be set via MCP set_log_level tool)
    return state.debugLogs?.config?.logLevel ?: "error"
}

/**
 * Check if a log level should be recorded based on threshold
 */
def shouldLog(level) {
    def levels = getLogLevels()
    def currentIndex = levels.indexOf(getConfiguredLogLevel())
    def logIndex = levels.indexOf(level)
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
        message: message
    ]

    if (ruleId) entry.ruleId = ruleId
    if (extraData?.duration) entry.duration = extraData.duration
    if (extraData?.ruleName) entry.ruleName = extraData.ruleName
    if (extraData?.details) entry.details = extraData.details
    if (extraData?.stackTrace) entry.stackTrace = extraData.stackTrace

    state.debugLogs.entries << entry

    // Enforce max entries limit (circular buffer)
    def maxEntries = state.debugLogs.config?.maxEntries ?: 100
    while (state.debugLogs.entries.size() > maxEntries) {
        state.debugLogs.entries.remove((int)0)
    }

    // Force top-level state reassignment to ensure nested mutations are persisted
    state.debugLogs = state.debugLogs

    // Also log to Hubitat logs
    switch (level) {
        case "debug": log.debug "[${component}] ${message}"; break
        case "info": log.info "[${component}] ${message}"; break
        case "warn": log.warn "[${component}] ${message}"; break
        case "error": log.error "[${component}] ${message}"; break
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

// ==================== DEBUG TOOL IMPLEMENTATIONS ====================

def toolGetDebugLogs(args) {
    initDebugLogs()

    def limit = args.limit != null ? Math.min(args.limit as Integer, 200) : 50
    def level = args.level ?: "all"
    def component = args.component
    def ruleId = args.ruleId

    def logs = state.debugLogs.entries ?: []

    // Apply filters
    if (level && level != "all") {
        logs = logs.findAll { it.level == level }
    }
    if (component) {
        logs = logs.findAll { it.component?.contains(component) }
    }
    if (ruleId) {
        logs = logs.findAll { it.ruleId == ruleId }
    }

    // Get most recent entries
    def count = Math.min(limit, logs.size())
    logs = logs.drop(Math.max(0, logs.size() - count))

    return [
        entries: logs.collect { entry ->
            def result = [
                timestamp: entry.timestamp,
                time: formatTimestamp(entry.timestamp),
                level: entry.level,
                component: entry.component,
                message: entry.message
            ]
            if (entry.ruleId) result.ruleId = entry.ruleId
            if (entry.ruleName) result.ruleName = entry.ruleName
            if (entry.duration) result.durationMs = entry.duration
            if (entry.stackTrace) result.stackTrace = entry.stackTrace
            if (entry.details) result.details = entry.details
            return result
        },
        count: logs.size(),
        totalStored: state.debugLogs.entries?.size() ?: 0,
        maxEntries: state.debugLogs.config?.maxEntries ?: 100,
        currentLogLevel: getConfiguredLogLevel()
    ]
}

def toolClearDebugLogs(args) {
    initDebugLogs()
    def count = state.debugLogs.entries?.size() ?: 0
    state.debugLogs.entries = []
    mcpLog("info", "server", "Debug logs cleared (${count} entries removed)")
    return [success: true, clearedCount: count]
}

def toolGetRuleDiagnostics(args) {
    def ruleId = args.ruleId
    def childApp = getChildAppById(ruleId)

    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    def ruleData = childApp.getRuleData()

    // Get recent logs for this rule
    initDebugLogs()
    def ruleLogs = (state.debugLogs.entries ?: []).findAll { it.ruleId == ruleId }
    def recentLogs = ruleLogs.drop(Math.max(0, ruleLogs.size() - 10))
    def errorLogs = ruleLogs.findAll { it.level == "error" }

    return [
        rule: [
            id: ruleData.id,
            name: ruleData.name,
            description: ruleData.description,
            enabled: ruleData.enabled,
            createdAt: formatTimestamp(ruleData.createdAt),
            updatedAt: formatTimestamp(ruleData.updatedAt)
        ],
        execution: [
            count: ruleData.executionCount ?: 0,
            lastTriggered: formatTimestamp(ruleData.lastTriggered)
        ],
        structure: [
            triggerCount: ruleData.triggers?.size() ?: 0,
            conditionCount: ruleData.conditions?.size() ?: 0,
            actionCount: ruleData.actions?.size() ?: 0,
            triggers: ruleData.triggers,
            conditions: ruleData.conditions,
            actions: ruleData.actions,
            conditionLogic: ruleData.conditionLogic ?: "all"
        ],
        state: [
            localVariables: ruleData.localVariables ?: [:]
        ],
        logs: [
            recentCount: recentLogs.size(),
            errorCount: errorLogs.size(),
            recent: recentLogs.collect { [time: formatTimestamp(it.timestamp), level: it.level, message: it.message] },
            errors: errorLogs.drop(Math.max(0, errorLogs.size() - 5)).collect { [time: formatTimestamp(it.timestamp), message: it.message, stackTrace: it.stackTrace] }
        ]
    ]
}

def toolSetLogLevel(args) {
    def level = args.level
    if (!getLogLevels().contains(level)) {
        throw new IllegalArgumentException("Invalid log level: ${level}. Valid levels: ${getLogLevels().join(', ')}")
    }

    def previousLevel = getConfiguredLogLevel()

    initDebugLogs()
    // Log BEFORE changing level so confirmation isn't suppressed when raising threshold
    mcpLog("info", "server", "Log level changed from ${previousLevel} to: ${level}")
    // Use read-modify-write for state persistence (nested mutations don't persist in Hubitat)
    def config = state.debugLogs.config ?: [:]
    config.logLevel = level
    state.debugLogs = [entries: state.debugLogs.entries ?: [], config: config]
    // Update the setting so UI stays in sync (use [type, value] map for enum settings)
    app.updateSetting("mcpLogLevel", [type: "enum", value: level])

    return [
        success: true,
        previousLevel: previousLevel,
        newLevel: level
    ]
}

def toolGetLoggingStatus(args) {
    initDebugLogs()
    def entries = state.debugLogs.entries ?: []

    def result = [
        version: currentVersion(),
        currentLogLevel: getConfiguredLogLevel(),
        availableLevels: getLogLevels(),
        totalEntries: entries.size(),
        maxEntries: state.debugLogs.config?.maxEntries ?: 100,
        entriesByLevel: [
            debug: entries.count { it.level == "debug" },
            info: entries.count { it.level == "info" },
            warn: entries.count { it.level == "warn" },
            error: entries.count { it.level == "error" }
        ],
        oldestEntry: entries.size() > 0 ? formatTimestamp(entries.first().timestamp) : null,
        newestEntry: entries.size() > 0 ? formatTimestamp(entries.last().timestamp) : null
    ]
    if (state.updateCheck?.updateAvailable) {
        result.updateAvailable = state.updateCheck.latestVersion
    }
    return result
}

def toolGenerateBugReport(args) {
    def version = currentVersion()
    def timestamp = formatTimestamp(now())

    // Gather system info
    def hubInfo = [:]
    try {
        hubInfo = [
            hubModel: location.hub?.firmwareVersionString ?: "Unknown",
            hubId: location.hub?.id ?: "Unknown",
            timeZone: location.timeZone?.ID ?: "Unknown",
            zipCode: location.zipCode ?: "Not set"
        ]
    } catch (e) {
        hubInfo = [error: "Could not retrieve hub info"]
    }

    // Get recent error logs
    initDebugLogs()
    def recentErrors = (state.debugLogs.entries ?: [])
        .findAll { it.level == "error" || it.level == "warn" }
        .takeRight(10)
        .collect { entry ->
            "[${formatTimestamp(entry.timestamp)}] ${entry.level.toUpperCase()}: ${entry.message}" +
            (entry.ruleId ? " (Rule: ${entry.ruleId})" : "") +
            (entry.error ? "\n  Error: ${entry.error}" : "")
        }

    // Get rule info if provided
    def ruleInfo = null
    if (args.ruleId) {
        try {
            def childApp = getChildAppById(args.ruleId)
            if (childApp) {
                def ruleData = childApp.getRuleData()
                ruleInfo = [
                    id: args.ruleId,
                    name: ruleData.name,
                    enabled: ruleData.enabled,
                    triggerCount: ruleData.triggers?.size() ?: 0,
                    conditionCount: ruleData.conditions?.size() ?: 0,
                    actionCount: ruleData.actions?.size() ?: 0,
                    lastTriggered: ruleData.lastTriggered ? formatTimestamp(ruleData.lastTriggered) : "Never",
                    executionCount: ruleData.executionCount ?: 0
                ]
            }
        } catch (e) {
            ruleInfo = [error: "Could not retrieve rule info: ${e.message}"]
        }
    }

    // Get device count
    def deviceCount = selectedDevices?.size() ?: 0
    def ruleCount = getChildApps()?.size() ?: 0

    // Build the formatted report
    def report = """# Bug Report: ${args.title}

**Generated:** ${timestamp}
**MCP Server Version:** ${version}

## Environment
- **Hub Firmware:** ${hubInfo.hubModel ?: 'Unknown'}
- **Time Zone:** ${hubInfo.timeZone ?: 'Unknown'}
- **Devices Exposed to MCP:** ${deviceCount}
- **Total Rules:** ${ruleCount}
- **Current Log Level:** ${getConfiguredLogLevel()}

## Bug Description

### Expected Behavior
${args.expected}

### Actual Behavior
${args.actual}

${args.stepsToReproduce ? """### Steps to Reproduce
${args.stepsToReproduce}
""" : ""}
${ruleInfo ? """## Related Rule Info
- **Rule ID:** ${ruleInfo.id}
- **Rule Name:** ${ruleInfo.name ?: 'Unknown'}
- **Enabled:** ${ruleInfo.enabled}
- **Triggers:** ${ruleInfo.triggerCount}
- **Conditions:** ${ruleInfo.conditionCount}
- **Actions:** ${ruleInfo.actionCount}
- **Last Triggered:** ${ruleInfo.lastTriggered}
- **Execution Count:** ${ruleInfo.executionCount}
""" : ""}
## Recent Error/Warning Logs
${recentErrors.size() > 0 ? "```\n" + recentErrors.join("\n") + "\n```" : "_No recent errors logged_"}

## Additional Context
_Add any other context about the problem here._

---
**To submit this bug report:**
1. Go to: https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/new
2. Copy this entire report into the issue description
3. Add any additional details or screenshots
4. Submit the issue

Thank you for helping improve the MCP Rule Server!"""

    def result = [
        success: true,
        report: report,
        submitUrl: "https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/new",
        instructions: "Copy the 'report' field content and paste it into a new GitHub issue at the submitUrl. Add any additional context or screenshots that might help diagnose the issue."
    ]
    if (state.updateCheck?.updateAvailable) {
        result.updateAvailable = state.updateCheck.latestVersion
    }
    return result
}

// ==================== HUB ADMIN READ TOOL IMPLEMENTATIONS ====================

def toolGetHubDetails(args) {
    requireHubAdminRead()

    def hub = location.hub
    def details = [
        name: hub?.name,
        localIP: hub?.localIP,
        timeZone: location.timeZone?.ID,
        temperatureScale: location.temperatureScale,
        latitude: location.latitude,
        longitude: location.longitude,
        zipCode: location.zipCode
    ]

    // Safe property access for hub properties
    try { details.model = hub?.hardwareID } catch (Exception e) { details.model = "unavailable" }
    try { details.firmwareVersion = hub?.firmwareVersionString } catch (Exception e) { details.firmwareVersion = "unavailable" }
    try { details.uptime = hub?.uptime } catch (Exception e) { details.uptime = "unavailable" }
    try { details.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { details.zigbeeChannel = "unavailable" }
    try { details.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { details.zwaveVersion = "unavailable" }
    try { details.zigbeeId = hub?.zigbeeId } catch (Exception e) { details.zigbeeId = "unavailable" }
    try { details.type = hub?.type } catch (Exception e) { details.type = "unavailable" }
    try { details.hubData = hub?.data } catch (Exception e) { details.hubData = null }

    // Extended info via internal API
    try {
        def freeMemory = hubInternalGet("/hub/advanced/freeOSMemory")
        if (freeMemory) details.freeMemoryKB = freeMemory.trim()
    } catch (Exception e) {
        details.freeMemoryKB = "unavailable (${e.message})"
        mcpLog("debug", "hub-admin", "Could not get free memory: ${e.message}")
    }

    try {
        def tempC = hubInternalGet("/hub/advanced/internalTempCelsius")
        if (tempC) details.internalTempCelsius = tempC.trim()
    } catch (Exception e) {
        details.internalTempCelsius = "unavailable (${e.message})"
        mcpLog("debug", "hub-admin", "Could not get internal temperature: ${e.message}")
    }

    // Hub database size via internal API
    try {
        def dbSize = hubInternalGet("/hub/advanced/databaseSize")
        if (dbSize) details.databaseSizeKB = dbSize.trim()
    } catch (Exception e) {
        details.databaseSizeKB = "unavailable"
        mcpLog("debug", "hub-admin", "Could not get database size: ${e.message}")
    }

    details.mcpServerVersion = currentVersion()
    details.selectedDeviceCount = settings.selectedDevices?.size() ?: 0
    details.ruleCount = getChildApps()?.size() ?: 0
    details.hubSecurityConfigured = settings.hubSecurityEnabled ?: false
    details.hubAdminReadEnabled = settings.enableHubAdminRead ?: false
    details.hubAdminWriteEnabled = settings.enableHubAdminWrite ?: false
    details.builtinAppReadEnabled = settings.enableBuiltinAppRead ?: false
    details.developerModeEnabled = settings.enableDeveloperMode ?: false

    mcpLog("info", "hub-admin", "Retrieved extended hub details")
    return details
}

def toolListHubApps(args) {
    requireHubAdminRead()

    def result = [:]
    try {
        def responseText = hubInternalGet("/hub2/userAppTypes")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.apps = parsed
                result.count = parsed instanceof List ? parsed.size() : 0
                result.source = "hub_api"
            } catch (Exception parseErr) {
                // Response was not JSON - return what we can
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
            }
        } else {
            result.apps = []
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "list_hub_apps API call failed: ${e.message}")
        // Fallback: return MCP child apps as the only apps we can enumerate
        def childApps = getChildApps()
        result.apps = childApps?.collect { ca ->
            [id: ca.id.toString(), name: ca.getSetting("ruleName") ?: ca.label ?: "Unknown", type: "MCP Rule"]
        } ?: []
        result.count = result.apps.size()
        result.source = "mcp_only"
        result.note = "Hub internal API unavailable (${e.message}). Showing only MCP Rule Server apps. This may require Hub Security credentials or a firmware update."
    }

    mcpLog("info", "hub-admin", "Listed hub apps (source: ${result.source})")
    return result
}

def toolListHubDrivers(args) {
    requireHubAdminRead()

    def result = [:]
    try {
        def responseText = hubInternalGet("/hub2/userDeviceTypes")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.drivers = parsed
                result.count = parsed instanceof List ? parsed.size() : 0
                result.source = "hub_api"
            } catch (Exception parseErr) {
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
            }
        } else {
            result.drivers = []
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "list_hub_drivers API call failed: ${e.message}")
        result.drivers = []
        result.count = 0
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."
    }

    mcpLog("info", "hub-admin", "Listed hub drivers (source: ${result.source})")
    return result
}

def toolGetZwaveDetails(args) {
    requireHubAdminRead()

    def hub = location.hub
    def result = [:]

    // Basic Z-Wave info from hub object
    try { result.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { result.zwaveVersion = "unavailable" }

    // Extended Z-Wave info via internal API
    // Firmware 2.3.7.1+ uses /hub/zwaveDetails/json; older uses /hub2/zwaveInfo
    def zwaveEndpoints = ["/hub/zwaveDetails/json", "/hub2/zwaveInfo"]
    def zwaveSuccess = false
    for (endpoint in zwaveEndpoints) {
        try {
            def responseText = hubInternalGet(endpoint)
            if (responseText) {
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                    result.zwaveData = parsed
                    result.source = "hub_api"
                    result.endpoint = endpoint
                    zwaveSuccess = true
                } catch (Exception parseErr) {
                    result.rawResponse = responseText?.take(3000)
                    result.source = "hub_api_raw"
                    result.endpoint = endpoint
                    result.note = "Response was not JSON format"
                    zwaveSuccess = true
                }
            }
            if (zwaveSuccess) break
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Z-Wave endpoint ${endpoint} failed: ${e.message}")
            // Try next endpoint
        }
    }

    if (!zwaveSuccess) {
        result.source = "sdk_only"
        result.note = "Extended Z-Wave info unavailable from all endpoints. Showing basic info from hub SDK."
    }

    mcpLog("info", "hub-admin", "Retrieved Z-Wave details")
    return result
}

def toolGetZigbeeDetails(args) {
    requireHubAdminRead()

    def hub = location.hub
    def result = [:]

    // Basic Zigbee info from hub object
    try { result.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { result.zigbeeChannel = "unavailable" }
    try { result.zigbeeId = hub?.zigbeeId } catch (Exception e) { result.zigbeeId = "unavailable" }

    // Extended Zigbee info via internal API
    // Firmware 2.3.7.1+ uses /hub/zigbeeDetails/json; older uses /hub2/zigbeeInfo
    def zigbeeEndpoints = ["/hub/zigbeeDetails/json", "/hub2/zigbeeInfo"]
    def zigbeeSuccess = false
    for (endpoint in zigbeeEndpoints) {
        try {
            def responseText = hubInternalGet(endpoint)
            if (responseText) {
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                    result.zigbeeData = parsed
                    result.source = "hub_api"
                    result.endpoint = endpoint
                    zigbeeSuccess = true
                } catch (Exception parseErr) {
                    result.rawResponse = responseText?.take(3000)
                    result.source = "hub_api_raw"
                    result.endpoint = endpoint
                    result.note = "Response was not JSON format"
                    zigbeeSuccess = true
                }
            }
            if (zigbeeSuccess) break
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Zigbee endpoint ${endpoint} failed: ${e.message}")
            // Try next endpoint
        }
    }

    if (!zigbeeSuccess) {
        result.source = "sdk_only"
        result.note = "Extended Zigbee info unavailable from all endpoints. Showing basic info from hub SDK."
    }

    mcpLog("info", "hub-admin", "Retrieved Zigbee details")
    return result
}

def toolGetHubHealth(args) {
    requireHubAdminRead()

    def hub = location.hub
    def health = [
        timestamp: formatTimestamp(now())
    ]

    // Uptime
    try { health.uptimeSeconds = hub?.uptime } catch (Exception e) { health.uptimeSeconds = "unavailable" }
    if (health.uptimeSeconds && health.uptimeSeconds instanceof Number) {
        def days = (health.uptimeSeconds / 86400).toInteger()
        def hours = ((health.uptimeSeconds % 86400) / 3600).toInteger()
        def mins = ((health.uptimeSeconds % 3600) / 60).toInteger()
        health.uptimeFormatted = "${days}d ${hours}h ${mins}m"
    }

    // Free memory
    try {
        def freeMemory = hubInternalGet("/hub/advanced/freeOSMemory")
        if (freeMemory) {
            health.freeMemoryKB = freeMemory.trim()
            try {
                def memKB = freeMemory.trim() as Integer
                if (memKB < 50000) {
                    health.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
                } else if (memKB < 100000) {
                    health.memoryNote = "Memory is moderate: ${memKB}KB free."
                }
            } catch (NumberFormatException nfe) {
                mcpLog("debug", "hub-admin", "Free memory value not numeric: ${freeMemory.trim()}")
            }
        }
    } catch (Exception e) {
        health.freeMemoryKB = "unavailable"
        mcpLog("debug", "hub-admin", "Could not get free memory: ${e.message}")
    }

    // Internal temperature
    try {
        def tempC = hubInternalGet("/hub/advanced/internalTempCelsius")
        if (tempC) {
            health.internalTempCelsius = tempC.trim()
            try {
                def temp = tempC.trim() as Double
                if (temp > 70) {
                    health.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
                } else if (temp > 60) {
                    health.temperatureNote = "Temperature is warm: ${temp}°C."
                }
            } catch (NumberFormatException nfe) {
                mcpLog("debug", "hub-admin", "Temperature value not numeric: ${tempC.trim()}")
            }
        }
    } catch (Exception e) {
        health.internalTempCelsius = "unavailable"
        mcpLog("debug", "hub-admin", "Could not get internal temperature: ${e.message}")
    }

    // Database size
    try {
        def dbSize = hubInternalGet("/hub/advanced/databaseSize")
        if (dbSize) {
            health.databaseSizeKB = dbSize.trim()
            try {
                def dbKB = dbSize.trim() as Integer
                if (dbKB > 500000) {
                    health.databaseWarning = "LARGE DATABASE: ${(dbKB / 1024).toInteger()}MB. Consider cleaning up old data."
                }
            } catch (NumberFormatException nfe) {
                mcpLog("debug", "hub-admin", "Database size value not numeric: ${dbSize.trim()}")
            }
        }
    } catch (Exception e) {
        health.databaseSizeKB = "unavailable"
    }

    // MCP-specific health
    health.mcpDeviceCount = settings.selectedDevices?.size() ?: 0
    health.mcpRuleCount = getChildApps()?.size() ?: 0
    health.mcpLogEntries = state.debugLogs?.entries?.size() ?: 0
    health.mcpCapturedStates = state.capturedDeviceStates?.size() ?: 0

    mcpLog("info", "hub-admin", "Hub health check completed")
    return health
}

// ==================== MONITORING TOOL IMPLEMENTATIONS ====================

def toolGetHubLogs(args) {
    requireHubAdminRead()

    def maxLimit = 500
    def limit = Math.min(args.limit ?: 100, maxLimit)
    def levelFilter = args.level
    def sourceFilter = args.source
    def deviceIdFilter = args.deviceId?.toString()?.trim()
    def appIdFilter = args.appId?.toString()?.trim()

    if (deviceIdFilter && appIdFilter) {
        throw new IllegalArgumentException("deviceId and appId are mutually exclusive: set only one")
    }

    // Server-side scoping: the hub's /logs/past/json endpoint accepts ?type=dev&id=<N>
    // or ?type=app&id=<N> to filter at the source (same mechanism the UI's device- and
    // app-specific log pages use). Much cheaper than returning the whole buffer and
    // filtering client-side when the caller only wants one device/app. The level and
    // source filters plus the limit below still apply client-side on top of the scoped
    // result; they are not replaced by deviceId/appId.
    //
    // Both ids must be validated before the HTTP call. The hub returns 200 OK with an
    // empty array for unknown or non-numeric ids, which would otherwise be indistinguishable
    // from a real device that simply has no log entries.
    def query = null
    if (deviceIdFilter) {
        def device = findDevice(deviceIdFilter)
        if (!device) {
            throw new IllegalArgumentException("Device not found: ${deviceIdFilter}")
        }
        query = [type: "dev", id: deviceIdFilter]
    } else if (appIdFilter) {
        if (!appIdFilter.isInteger()) {
            throw new IllegalArgumentException("appId must be numeric: ${appIdFilter}")
        }
        query = [type: "app", id: appIdFilter]
    }

    mcpLog("info", "monitoring", "Fetching hub logs (level=${levelFilter}, source=${sourceFilter}, deviceId=${deviceIdFilter}, appId=${appIdFilter}, limit=${limit})")

    def responseText = null
    try {
        responseText = hubInternalGet("/logs/past/json", query, 30)
    } catch (Exception e) {
        mcpLog("error", "monitoring", "Failed to fetch hub logs: ${e.message}")
        return [logs: [], error: "Failed to fetch hub logs: ${e.message}", count: 0]
    }

    if (!responseText) {
        return [logs: [], message: "No log data returned from hub", count: 0]
    }

    // The /logs/past/json endpoint returns a JSON array of tab-delimited strings:
    // ["name\tlevel\tmessage\ttime\ttype", ...]
    def logs = []
    def logArray = []
    try {
        logArray = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        // If not JSON, fall back to splitting by newlines (older firmware)
        mcpLog("debug", "monitoring", "Hub logs response not JSON, falling back to line-split: ${e.message}")
        logArray = responseText.split("\n").toList()
    }

    // Hub returns chronological order (oldest-first). Callers overwhelmingly want
    // the most recent N entries — reverse so the limit trims the tail of the buffer
    // rather than the head. Guard against non-List parse results (a String or Map
    // from the newline-split fallback or a firmware variant) since List.reverse()
    // only makes sense on the array case.
    if (!(logArray instanceof List)) {
        return [logs: [], error: "Unexpected log format from hub", count: 0]
    }
    logArray = logArray.reverse()

    def totalParsed = logArray.size()
    for (logEntry in logArray) {
        def line = logEntry?.toString()
        if (!line?.trim()) continue
        def parts = line.split("\t", -1)
        if (parts.size() < 2) continue

        def entry = [
            name: parts[0]?.trim(),
            level: parts.size() > 1 ? parts[1]?.trim() : "",
            message: parts.size() > 2 ? parts[2]?.trim() : "",
            time: parts.size() > 3 ? parts[3]?.trim() : "",
            type: parts.size() > 4 ? parts[4]?.trim() : ""
        ]

        // If message field contains tabs (extra fields), rejoin the middle parts
        if (parts.size() > 5) {
            try {
                entry.message = parts[2..(parts.size() - 3)].join("\t")
                entry.time = parts[-2]?.trim()
                entry.type = parts[-1]?.trim()
            } catch (Exception e) {
                // Fall back to simple parsing
            }
        }

        // Apply filters
        if (levelFilter && entry.level?.toLowerCase() != levelFilter.toLowerCase()) continue
        if (sourceFilter) {
            def src = sourceFilter.toLowerCase()
            // Source info is in the message field (format: "app|ID|AppName|..." or "dev|ID|DevName|...")
            if (!entry.message?.toLowerCase()?.contains(src) && !entry.name?.toLowerCase()?.contains(src)) continue
        }

        logs << entry
        if (logs.size() >= limit) break
    }

    // Truncation safety for 128KB cloud limit
    def result = [logs: logs, count: logs.size(), totalParsed: totalParsed]
    // Estimate JSON size without serializing: ~120 bytes per log entry overhead
    def estimatedJsonSize = logs.sum(0) { (it.message?.length() ?: 0) + (it.name?.length() ?: 0) + 120 }
    if (estimatedJsonSize > 120000) {
        logs.each { it.message = it.message?.take(200) }
        result.truncated = true
        result.note = "Log messages truncated to fit response size limit"
    }

    mcpLog("info", "monitoring", "Retrieved ${logs.size()} hub log entries (${totalParsed} total parsed)")
    return result
}

def toolGetDeviceHistory(args) {
    if (!args.deviceId) throw new IllegalArgumentException("deviceId is required")
    def device = findDevice(args.deviceId)
    if (!device) throw new IllegalArgumentException("Device not found: ${args.deviceId}. Device must be selected in MCP Rule Server app settings.")

    def hoursBack = Math.min(args.hoursBack ?: 24, 168)
    def limit = Math.min(args.limit ?: 100, 500)
    def attributeFilter = args.attribute

    def deviceLabel = device.label ?: device.name ?: "Device ${args.deviceId}"
    def sinceDate = new Date(now() - (hoursBack * 3600000L))

    def events
    try {
        events = device.eventsSince(sinceDate, [max: limit])
    } catch (Exception e) {
        mcpLog("warn", "monitoring", "eventsSince failed for ${deviceLabel}: ${e.message}")
        return [error: "eventsSince not supported or failed: ${e.message}", device: deviceLabel, deviceId: args.deviceId]
    }

    def results = events?.collect { evt ->
        [
            name: evt.name,
            value: evt.value,
            unit: evt.unit,
            description: evt.descriptionText,
            date: evt.date?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
            isStateChange: evt.isStateChange
        ]
    } ?: []

    // Apply attribute filter post-query
    if (attributeFilter) {
        results = results.findAll { it.name == attributeFilter }
    }

    mcpLog("info", "monitoring", "Retrieved ${results.size()} history events for ${deviceLabel} (${hoursBack}h back)")
    return [
        device: deviceLabel,
        deviceId: args.deviceId,
        hoursBack: hoursBack,
        attributeFilter: attributeFilter,
        events: results,
        count: results.size(),
        sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    ]
}

// Shared helper: fetch /logs/json from hub internal API
def fetchLogsJson() {
    requireHubAdminRead()
    def responseText = hubInternalGet("/logs/json", null, 30)
    if (!responseText) throw new RuntimeException("No data returned from /logs/json")
    return new groovy.json.JsonSlurper().parseText(responseText)
}

def toolGetPerformanceStats(args) {
    def type = args.type ?: "device"
    def sortBy = args.sortBy ?: "pct"
    def limit = args.limit != null ? args.limit : 20

    mcpLog("info", "monitoring", "Fetching performance stats (type=${type}, sortBy=${sortBy}, limit=${limit})")

    def data
    try {
        data = fetchLogsJson()
    } catch (Exception e) {
        mcpLog("error", "monitoring", "Failed to fetch performance stats: ${e.message}")
        return [error: "Failed to fetch performance stats: ${e.message}"]
    }

    def result = [
        uptime: data.uptime
    ]

    def formatStats = { statsList ->
        if (!statsList) return []
        // Sort
        switch (sortBy) {
            case "count": statsList = statsList.sort { -(it.count ?: 0) }; break
            case "stateSize": statsList = statsList.sort { -(it.stateSize ?: 0) }; break
            case "totalMs": statsList = statsList.sort { -(it.total ?: 0) }; break
            case "name": statsList = statsList.sort { (it.name ?: "").toLowerCase() }; break
            default: statsList = statsList.sort { -(it.pct ?: 0) }; break
        }
        // Limit
        if (limit > 0 && statsList.size() > limit) {
            statsList = statsList.take(limit)
        }
        // Slim down to essential fields + useful diagnostics
        return statsList.collect { entry ->
            def item = [
                id: entry.id,
                name: entry.name,
                count: entry.count,
                pctBusy: entry.formattedPct,
                pctTotal: entry.formattedPctTotal,
                stateSize: entry.stateSize,
                totalMs: entry.total,
                averageMs: entry.average != null ? Math.round(entry.average * 100) / 100.0 : null,
                totalEvents: entry.customAttributes?.eventsCount,
                states: entry.customAttributes?.statesCount,
                hubActions: entry.hubActionCount,
                pendingEvents: entry.pendingEventsCount,
                cloudCalls: entry.cloudCallCount
            ]
            if (entry.largeState) item.largeState = true
            return item
        }
    }

    if (type == "device" || type == "both") {
        result.deviceSummary = [
            totalRuntime: data.totalDevicesRuntime,
            pctOfUptime: data.devicePct,
            deviceCount: data.deviceStats?.size() ?: 0
        ]
        result.deviceStats = formatStats(data.deviceStats)
    }

    if (type == "app" || type == "both") {
        result.appSummary = [
            totalRuntime: data.totalAppsRuntime,
            pctOfUptime: data.appPct,
            appCount: data.appStats?.size() ?: 0
        ]
        result.appStats = formatStats(data.appStats)
    }

    // Size guard: estimate response and warn if large
    def statsCount = (result.deviceStats?.size() ?: 0) + (result.appStats?.size() ?: 0)
    if (limit == 0) {
        result.note = "Returning all ${statsCount} entries. Use limit parameter to reduce response size."
    }

    mcpLog("info", "monitoring", "Retrieved performance stats: ${statsCount} entries (type=${type})")
    return result
}

def toolGetHubJobs(args) {
    mcpLog("info", "monitoring", "Fetching hub jobs")

    def data
    try {
        data = fetchLogsJson()
    } catch (Exception e) {
        mcpLog("error", "monitoring", "Failed to fetch hub jobs: ${e.message}")
        return [error: "Failed to fetch hub jobs: ${e.message}"]
    }

    def scheduledJobs = (data.jobs ?: []).collect { job ->
        [
            id: job.id,
            name: job.name,
            recurring: job.recurring,
            method: job.methodName,
            nextRun: job.nextRun
        ]
    }

    def runningJobs = (data.runningJobs ?: []).collect { job ->
        [
            id: job.id,
            name: job.name,
            method: job.methodName
        ]
    }

    def hubActions = data.hubCommands ?: []

    return [
        uptime: data.uptime,
        scheduledJobs: [
            count: scheduledJobs.size(),
            jobs: scheduledJobs
        ],
        runningJobs: [
            count: runningJobs.size(),
            jobs: runningJobs
        ],
        hubActions: [
            count: hubActions.size(),
            actions: hubActions
        ]
    ]
}

def toolGetHubPerformance(args) {
    requireHubAdminRead()

    def recordSnapshot = args.recordSnapshot != false
    def trendPoints = Math.min(args.trendPoints ?: 10, 50)

    // Gather current metrics
    def current = [timestamp: formatTimestamp(now()), timestampEpoch: now()]

    try {
        current.freeMemoryKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim()
        try {
            def memKB = current.freeMemoryKB as Integer
            if (memKB < 50000) current.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
            else if (memKB < 100000) current.memoryNote = "Memory is moderate: ${memKB}KB free."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.freeMemoryKB = "unavailable" }

    try {
        current.internalTempC = hubInternalGet("/hub/advanced/internalTempCelsius")?.trim()
        try {
            def temp = current.internalTempC as Double
            if (temp > 70) current.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
            else if (temp > 60) current.temperatureNote = "Temperature is warm: ${temp}°C."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.internalTempC = "unavailable" }

    try {
        current.databaseSizeKB = hubInternalGet("/hub/advanced/databaseSize")?.trim()
        try {
            def dbKB = current.databaseSizeKB as Integer
            if (dbKB > 500000) current.databaseWarning = "LARGE DATABASE: ${(dbKB / 1024).toInteger()}MB. Consider cleaning up old data."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.databaseSizeKB = "unavailable" }

    try { current.uptimeSeconds = location.hub?.uptime } catch (Exception e) { current.uptimeSeconds = "unavailable" }
    if (current.uptimeSeconds && current.uptimeSeconds instanceof Number) {
        def days = (current.uptimeSeconds / 86400).toInteger()
        def hours = ((current.uptimeSeconds % 86400) / 3600).toInteger()
        def mins = ((current.uptimeSeconds % 3600) / 60).toInteger()
        current.uptimeFormatted = "${days}d ${hours}h ${mins}m"
    }

    // CSV history management
    def csvFileName = "mcp-performance-history.csv"
    def csvHeader = "timestamp,freeMemoryKB,internalTempC,databaseSizeKB,uptimeSeconds"
    def history = []

    // Read existing CSV from File Manager
    try {
        def existingBytes = downloadHubFile(csvFileName)
        if (existingBytes) {
            def csvText = new String(existingBytes, "UTF-8")
            def csvLines = csvText.split("\n")
            for (int i = 1; i < csvLines.size(); i++) {
                if (csvLines[i]?.trim()) history << csvLines[i].trim()
            }
        }
    } catch (Exception e) {
        // File doesn't exist yet, that's fine
        mcpLog("debug", "monitoring", "No existing performance CSV: ${e.message}")
    }

    // Record current snapshot to CSV
    if (recordSnapshot) {
        def csvRow = "${now()},${current.freeMemoryKB},${current.internalTempC},${current.databaseSizeKB},${current.uptimeSeconds}"
        history << csvRow

        // Trim to 500 rows (rolling window)
        if (history.size() > 500) {
            history = history.drop(history.size() - 500)
        }

        // Write back to File Manager
        def csvContent = csvHeader + "\n" + history.join("\n") + "\n"
        try {
            uploadHubFile(csvFileName, csvContent.getBytes("UTF-8"))
        } catch (Exception e) {
            mcpLog("warn", "monitoring", "Failed to write performance CSV: ${e.message}")
        }
    }

    // Parse recent trend points for response
    def trends = []
    def startIdx = Math.max(0, history.size() - trendPoints)
    for (int i = startIdx; i < history.size(); i++) {
        def parts = history[i].split(",", -1)
        if (parts.size() >= 5) {
            try {
                trends << [
                    timestamp: formatTimestamp(parts[0] as Long),
                    freeMemoryKB: parts[1],
                    internalTempC: parts[2],
                    databaseSizeKB: parts[3],
                    uptimeSeconds: parts[4]
                ]
            } catch (Exception e) {
                // Skip malformed rows
            }
        }
    }

    mcpLog("info", "monitoring", "Hub performance snapshot recorded=${recordSnapshot}, trendPoints=${trends.size()}")
    return [
        current: current,
        trends: trends,
        trendPointsAvailable: history.size(),
        historyFile: csvFileName
    ]
}

def toolGetMemoryHistory(args) {
    requireHubAdminRead()

    def limit = args.limit != null ? args.limit : 100

    def rawText = hubInternalGet("/hub/advanced/freeOSMemoryHistory")
    if (!rawText) {
        return [entries: [], summary: [message: "No memory history data available"]]
    }

    def lines = rawText.trim().split("\n")
    def allEntries = []
    def memValues = []

    for (line in lines) {
        def trimmed = line?.trim()
        if (!trimmed) continue

        // Format: "Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java"
        def parts = trimmed.split(",", -1)
        if (parts.size() >= 3) {
            // Skip header/non-numeric lines by parsing memory value first
            def memKB = null
            try {
                memKB = parts[1]?.trim() as Integer
            } catch (Exception e) {
                // Header or non-numeric line — skip
                continue
            }

            def entry = [
                timestamp: parts[0]?.trim(),
                freeMemoryKB: memKB,
                cpuLoad5min: parts[2]?.trim()
            ]

            // Parse Java heap and direct memory columns if present
            if (parts.size() >= 6) {
                try { entry.totalJavaKB = parts[3]?.trim() as Integer } catch (Exception e) {}
                try { entry.freeJavaKB = parts[4]?.trim() as Integer } catch (Exception e) {}
                try { entry.directJavaKB = parts[5]?.trim() as Integer } catch (Exception e) {}
            }

            allEntries << entry
            memValues << memKB
        }
    }

    // Summary is computed from ALL entries regardless of limit
    def summary = [totalEntries: allEntries.size()]
    if (memValues) {
        summary.currentMemoryKB = memValues[-1]
        summary.minMemoryKB = memValues.min()
        summary.maxMemoryKB = memValues.max()
        summary.avgMemoryKB = (memValues.sum() / memValues.size()).toInteger()

        if (summary.currentMemoryKB < 50000) {
            summary.memoryWarning = "LOW MEMORY: ${summary.currentMemoryKB}KB free. Consider rebooting or running force_garbage_collection."
        }

        // Java heap and direct memory summary from latest entry
        def latest = allEntries[-1]
        if (latest.totalJavaKB != null) summary.totalJavaKB = latest.totalJavaKB
        if (latest.freeJavaKB != null) summary.freeJavaKB = latest.freeJavaKB
        if (latest.directJavaKB != null) {
            summary.directJavaKB = latest.directJavaKB
            // Track direct memory growth (potential NIO buffer leak indicator)
            def directValues = allEntries.findAll { it.directJavaKB != null }.collect { it.directJavaKB }
            if (directValues.size() >= 2) {
                summary.directJavaMinKB = directValues.min()
                summary.directJavaMaxKB = directValues.max()
            }
        }
    }

    // Apply limit — return most recent entries
    def entries = allEntries
    if (limit > 0 && allEntries.size() > limit) {
        entries = allEntries.takeRight(limit)
        summary.truncated = true
        summary.showing = "${entries.size()} of ${allEntries.size()} (most recent)"
    }

    mcpLog("info", "diagnostics", "Memory history retrieved: ${entries.size()} entries (${allEntries.size()} total)")
    return [entries: entries, summary: summary]
}

def toolForceGarbageCollection(args) {
    requireHubAdminRead()

    // Read free memory before GC
    def beforeKB = null
    try {
        beforeKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim() as Integer
    } catch (Exception e) {
        beforeKB = null
    }

    // Trigger garbage collection
    hubInternalGet("/hub/forceGC")

    // Brief pause to let GC complete
    pauseExecution(1000)

    // Read free memory after GC
    def afterKB = null
    try {
        afterKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim() as Integer
    } catch (Exception e) {
        afterKB = null
    }

    def result = [
        beforeFreeMemoryKB: beforeKB,
        afterFreeMemoryKB: afterKB,
        timestamp: formatTimestamp(now())
    ]

    if (beforeKB != null && afterKB != null) {
        result.deltaKB = afterKB - beforeKB
        result.memoryReclaimed = result.deltaKB > 0
        result.summary = "GC complete: ${beforeKB}KB → ${afterKB}KB (${result.deltaKB > 0 ? '+' : ''}${result.deltaKB}KB)"
    } else {
        result.summary = "GC triggered but could not read memory values for comparison"
    }

    mcpLog("info", "diagnostics", "Forced GC: before=${beforeKB}KB, after=${afterKB}KB")
    return result
}

def toolDeviceHealthCheck(args) {
    if (!settings.selectedDevices) {
        return [message: "No devices selected for MCP access", summary: [totalDevices: 0, healthyCount: 0, staleCount: 0, unknownCount: 0]]
    }

    def staleHours = args.staleHours ?: 24
    def includeHealthy = args.includeHealthy ?: false
    def staleThreshold = now() - (staleHours * 3600000L)

    def healthy = []
    def stale = []
    def unknown = []

    settings.selectedDevices.each { device ->
        try {
            def deviceLabel = device.label ?: device.name ?: "Device ${device.id}"
            def entry = [
                id: device.id.toString(),
                name: deviceLabel
            ]

            def lastActivity = null
            try {
                lastActivity = device.lastActivity
            } catch (Exception e) {
                // Some device types may not support lastActivity
            }

            if (lastActivity) {
                try {
                    entry.lastActivity = lastActivity.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    def activityTime = lastActivity.getTime()
                    entry.hoursAgo = Math.round((now() - activityTime) / 3600000.0 * 10) / 10.0

                    if (activityTime < staleThreshold) {
                        stale << entry
                    } else {
                        healthy << entry
                    }
                } catch (Exception e) {
                    entry.lastActivity = "error: ${e.message}"
                    unknown << entry
                }
            } else {
                entry.lastActivity = "never"
                entry.hoursAgo = null
                unknown << entry
            }
        } catch (Exception e) {
            // Skip device entirely if we can't even get basic info
            unknown << [id: device.id?.toString() ?: "unknown", name: "Error: ${e.message}", lastActivity: "error"]
        }
    }

    // Sort stale by most-stale first
    stale.sort { a, b -> (b.hoursAgo ?: 0) <=> (a.hoursAgo ?: 0) }

    def result = [
        summary: [
            totalDevices: settings.selectedDevices.size(),
            healthyCount: healthy.size(),
            staleCount: stale.size(),
            unknownCount: unknown.size(),
            staleThresholdHours: staleHours,
            checkedAt: formatTimestamp(now())
        ],
        staleDevices: stale,
        unknownDevices: unknown
    ]

    if (includeHealthy) {
        result.healthyDevices = healthy
    }

    if (stale.size() > 0 || unknown.size() > 0) {
        result.recommendation = "Found ${stale.size()} stale and ${unknown.size()} unknown devices. " +
            "Stale devices may have dead batteries, be out of range, or be orphaned/ghost devices. " +
            "Use 'get_device' on individual devices for more details."
    }

    mcpLog("info", "monitoring", "Device health check: ${healthy.size()} healthy, ${stale.size()} stale, ${unknown.size()} unknown (threshold: ${staleHours}h)")
    return result
}

// ==================== HUB ADMIN WRITE TOOL IMPLEMENTATIONS ====================

def toolCreateHubBackup(args) {
    if (!settings.enableHubAdminWrite) {
        throw new IllegalArgumentException("Hub Admin Write access is disabled. Enable 'Enable Hub Admin Write Tools' in MCP Rule Server app settings.")
    }
    if (!args.confirm) {
        throw new IllegalArgumentException("You must set confirm=true to create a backup.")
    }

    mcpLog("info", "hub-admin", "Creating hub backup...")

    try {
        // GET /hub/backupDB?fileName=latest triggers a fresh backup and returns the .lzf file
        // We just need the backup to be created; the binary response confirms success
        def responseText = hubInternalGet("/hub/backupDB", [fileName: "latest"], 300)
        def backupTime = now()

        state.lastBackupTimestamp = backupTime

        mcpLog("info", "hub-admin", "Hub backup created successfully at ${formatTimestamp(backupTime)}")
        return [
            success: true,
            message: "Hub backup created successfully",
            backupTimestamp: formatTimestamp(backupTime),
            backupTimestampEpoch: backupTime,
            note: "This backup is stored on the hub. You can download it from the Hubitat web UI at Settings → Backup and Restore."
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Hub backup FAILED: ${e.message}")
        return [
            success: false,
            error: "Backup failed: ${e.message}",
            note: "The backup could not be created. Do NOT proceed with any Hub Admin Write operations. " +
                  "Check Hub Security credentials if Hub Security is enabled, or try creating a backup manually from the Hubitat web UI."
        ]
    }
}

def toolRebootHub(args) {
    requireHubAdminWrite(args.confirm)

    mcpLog("warn", "hub-admin", "Hub reboot initiated by MCP")

    try {
        def responseText = hubInternalPost("/hub/reboot")
        return [
            success: true,
            message: "Hub reboot initiated. The hub will be unreachable for 1-3 minutes.",
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "All automations and device communications will stop during reboot. The hub will restart automatically.",
            response: responseText?.take(500)
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Hub reboot failed: ${e.message}")
        return [
            success: false,
            error: "Reboot failed: ${e.message}",
            note: "The reboot command could not be sent. Check Hub Security credentials or try rebooting manually from the Hubitat web UI at Settings → Reboot Hub."
        ]
    }
}

def toolShutdownHub(args) {
    requireHubAdminWrite(args.confirm)

    mcpLog("warn", "hub-admin", "Hub SHUTDOWN initiated by MCP — hub will NOT restart automatically")

    try {
        def responseText = hubInternalPost("/hub/shutdown")
        return [
            success: true,
            message: "Hub shutdown initiated. The hub will power off and will NOT restart automatically.",
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "The hub is powering down. To restart, you must physically unplug and replug the hub power cable. ALL smart home functionality will stop until the hub is manually restarted.",
            response: responseText?.take(500)
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Hub shutdown failed: ${e.message}")
        return [
            success: false,
            error: "Shutdown failed: ${e.message}",
            note: "The shutdown command could not be sent. Check Hub Security credentials or try shutting down manually from the Hubitat web UI."
        ]
    }
}

def toolZwaveRepair(args) {
    requireHubAdminWrite(args.confirm)

    mcpLog("info", "hub-admin", "Z-Wave repair initiated by MCP")

    try {
        def responseText = hubInternalPost("/hub/zwaveRepair")
        return [
            success: true,
            message: "Z-Wave network repair started. This process runs in the background.",
            duration: "Typically takes 5-30 minutes depending on Z-Wave network size",
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "Z-Wave devices may be temporarily unresponsive during the repair process. Do not initiate another repair until this one completes.",
            note: "Check the Hubitat Logs page for Z-Wave repair progress and completion status.",
            response: responseText?.take(500)
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Z-Wave repair failed to start: ${e.message}")
        return [
            success: false,
            error: "Z-Wave repair failed: ${e.message}",
            note: "The Z-Wave repair could not be started. Check Hub Security credentials or try starting it manually from the Hubitat web UI at Settings → Z-Wave Details → Repair."
        ]
    }
}

// ==================== HUB ADMIN APP/DRIVER MANAGEMENT ====================

def toolGetItemSource(String type, String idParam, args) {
    requireHubAdminRead()
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    def maxChunkSize = 64000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"

    try {
        def responseText = hubInternalGet(ajaxPath, [id: itemId])
        if (!responseText) return [success: false, error: "Empty response from hub"]

        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error") {
            return [success: false, error: parsed.errorMessage ?: "Failed to get ${type} source"]
        }

        def fullSource = parsed.source ?: ""
        def totalLength = fullSource.length()

        // For large sources, save full copy to File Manager so update can use sourceFile
        def savedToFile = null
        if (totalLength > maxChunkSize) {
            def sourceFileName = "mcp-source-${type}-${itemId}.groovy"
            try {
                uploadHubFile(sourceFileName, fullSource.getBytes("UTF-8"))
                savedToFile = sourceFileName
                mcpLog("info", "hub-admin", "Saved full ${type} ID ${itemId} source to File Manager: ${sourceFileName} (${totalLength} chars)")
            } catch (Exception saveErr) {
                mcpLog("warn", "hub-admin", "Could not save ${type} source to File Manager: ${saveErr.message}")
            }
        }

        // Extract the requested chunk
        def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
        def chunk = (requestedOffset < totalLength) ? fullSource.substring(requestedOffset, endIndex) : ""
        def hasMore = endIndex < totalLength

        mcpLog("info", "hub-admin", "Retrieved ${type} ID ${itemId} source: ${totalLength} chars total, returning offset ${requestedOffset}..${endIndex}${hasMore ? ' (more available)' : ''}")

        def result = [
            success: true,
            (idParam): itemId,
            source: chunk,
            version: parsed.version,
            status: parsed.status,
            totalLength: totalLength,
            offset: requestedOffset,
            chunkLength: chunk.length(),
            hasMore: hasMore
        ]
        if (hasMore) {
            result.nextOffset = endIndex
            result.remainingChars = totalLength - endIndex
            result.hint = "Call again with offset: ${endIndex} to get the next chunk."
        }
        if (savedToFile) {
            result.sourceFile = savedToFile
            result.sourceFileHint = "Full source saved to File Manager. Use update_${type}_code with sourceFile: '${savedToFile}' to update without cloud size limits."
        }
        return result
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Failed to get ${type} source: ${e.message}")
        return [success: false, error: "Failed to get ${type} source: ${e.message}"]
    }
}

/**
 * Strip HTML tags from a string (Hubitat frequently embeds color spans in app labels
 * and page titles). Null-safe. Does not try to be a real HTML parser — SDK-generated
 * tags are predictable (<span>, <b>, <i>) and this is adequate.
 */
private String stripAppConfigHtml(value) {
    if (value == null) return null
    def s = value.toString()
    if (!s.contains("<")) return s
    return s.replaceAll(/<[^>]+>/, "").trim()
}

/**
 * Walk an input's options structure and strip HTML from any label values. Hubitat's
 * SDK emits options in two shapes depending on input type:
 *   - List of single-entry maps: [{"<value>": "<label>"}, ...]  (enum)
 *   - Map of value-to-label: {"<value>": "<label>"}  (some capability inputs)
 * Both can contain color-span HTML on labels (e.g. state badges). Leaves unknown
 * shapes alone rather than guessing wrong and breaking them.
 */
private stripOptionsHtml(options) {
    if (options instanceof List) {
        def out = []
        for (entry in options) {
            if (entry instanceof Map) {
                def cleaned = [:]
                entry.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
                out << cleaned
            } else {
                out << entry
            }
        }
        return out
    }
    if (options instanceof Map) {
        def cleaned = [:]
        options.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
        return cleaned
    }
    return options
}

/**
 * Read an installed app's configuration via the hub's SDK-level rendering endpoint
 * /installedapp/configure/json/<appId>[/<pageName>]. Returns a normalized structure
 * covering app identity, page sections, inputs, and optionally the raw settings map.
 *
 * This endpoint is what the Hubitat Web UI itself consumes to render each app's
 * config page. The top-level shape {configPage, app, settings, childApps} is SDK-level
 * and consistent across every legacy SmartApp (Rule Machine, Room Lighting, Basic
 * Rules, HPM, Mode Manager, etc.). The runtime fingerprint check below asserts the
 * shape invariants; if Hubitat firmware drifts the contract, callers see a clear
 * error rather than malformed data.
 */
def toolGetAppConfig(args) {
    requireHubAdminRead()

    if (args?.appId == null || args.appId.toString().trim() == "") {
        throw new IllegalArgumentException("appId is required")
    }
    def appIdStr = args.appId.toString().trim()
    if (!appIdStr.isInteger()) {
        throw new IllegalArgumentException("appId must be numeric: ${appIdStr}")
    }

    def pageName = args?.pageName?.toString()?.trim()
    if (pageName && !pageName.matches(/[A-Za-z0-9_]+/)) {
        throw new IllegalArgumentException("pageName must be alphanumeric/underscore only: ${pageName}")
    }

    boolean includeSettings = args?.includeSettings == true

    def path = "/installedapp/configure/json/${appIdStr}"
    if (pageName) path += "/${pageName}"

    mcpLog("info", "hub-admin", "get_app_config appId=${appIdStr} page=${pageName ?: 'main'} includeSettings=${includeSettings}")

    def responseText
    try {
        responseText = hubInternalGet(path, null, 30)
    } catch (Exception e) {
        mcpLogError("hub-admin", "get_app_config fetch failed", e)
        return [success: false, error: "Failed to fetch app config [${e.class.simpleName}]: ${e.message}", appId: appIdStr as Integer]
    }

    if (!responseText) {
        return [success: false, error: "Empty response from ${path}. App may not exist or hub internal API is unavailable.", appId: appIdStr as Integer]
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLogError("hub-admin", "get_app_config JSON parse failed", e)
        return [success: false, error: "Failed to parse app config JSON: ${e.message}. Hubitat firmware may have changed the endpoint contract.", appId: appIdStr as Integer]
    }

    // Runtime fingerprint: confirm the SDK-level shape this tool depends on.
    // The /installedapp/configure/json/<id> endpoint returns {app, configPage, settings, childApps, ...}
    // consistently across every legacy SmartApp. If any of these top-level invariants are
    // missing, the firmware likely changed the contract — fail explicitly rather than
    // returning malformed data.
    if (!(parsed instanceof Map)) {
        return [success: false, error: "Unexpected response shape: expected a JSON object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
    }
    if (!(parsed.app instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'app' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing app"]
    }
    if (!(parsed.configPage instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'configPage' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing configPage"]
    }
    if (!(parsed.configPage.sections instanceof List)) {
        return [success: false, error: "Unexpected response shape: configPage.sections is not a list. This page may be a dynamic redirect or action-only page (common in HPM multi-step flows). Try a different pageName -- call list_app_pages for this app, or consult get_tool_guide section=builtin_app_tools for common multi-page app names.", appId: appIdStr as Integer, fingerprint: "sections not a list"]
    }

    // Hub returns app.appType as a ~30-key metadata object (author, classLocation,
    // createTime, deprecated, etc.). Extract only the useful fields — the rest is
    // either internal SDK state or duplicates what's already on app itself.
    def appTypeRaw = parsed.app.appType
    def appTypeSummary = null
    if (appTypeRaw instanceof Map) {
        appTypeSummary = [
            name: appTypeRaw.name,
            namespace: appTypeRaw.namespace,
            author: appTypeRaw.author,
            category: appTypeRaw.category,
            classLocation: appTypeRaw.classLocation,
            deprecated: appTypeRaw.deprecated == true,
            system: appTypeRaw.system == true,
            documentationLink: appTypeRaw.documentationLink
        ]
    }

    def appObj = [
        id: parsed.app.id,
        label: stripAppConfigHtml(parsed.app.trueLabel ?: parsed.app.label),
        name: parsed.app.name,
        appType: appTypeSummary,
        disabled: parsed.app.disabled == true,
        parentAppId: parsed.app.parentAppId,
        installed: parsed.app.installed == true
    ]

    def sections = []
    for (s in parsed.configPage.sections) {
        if (!(s instanceof Map)) continue
        def section = [
            title: stripAppConfigHtml(s.title),
            inputs: []
        ]
        for (i in (s.input ?: [])) {
            if (!(i instanceof Map)) continue
            def input = [
                name: i.name,
                type: i.type,
                title: stripAppConfigHtml(i.title)
            ]
            if (i.multiple == true) input.multiple = true
            if (i.required == true) input.required = true
            def desc = stripAppConfigHtml(i.description)
            if (desc && desc != "Click to set") input.description = desc
            if (i.options) input.options = stripOptionsHtml(i.options)
            // Current values: 'defaultValue' is the rendered value for most input types
            // (despite the misleading name), 'value' is used on some. Include whichever
            // is non-null and not the boolean 'true' sentinel.
            //
            // The boolean 'true' sentinel: Hubitat's legacy SmartApp SDK populates
            // defaultValue=true on capability.* and device-list input types (e.g.
            // type="capability.*", type="device.switch") to indicate "this input has a
            // configured value" rather than encoding the actual selection as defaultValue.
            // For those types the actual selected device label appears separately in the
            // rendered description. Excluding defaultValue==true prevents emitting a bare
            // true into the value field for every populated device-picker input.
            //
            // Exception for type="bool": boolean (checkbox) inputs use true/false as their
            // actual user-configured state values -- not as sentinel markers. The filter
            // is bypassed when i.type == "bool" so that both true (enabled) and false
            // (disabled) checkbox states are preserved in the output. Without this bypass,
            // enabled checkboxes (value==true) would be silently dropped, making the AI
            // believe the setting is unconfigured when it is actually set to enabled.
            // (observed: firmware 2.3.x-2.4.x; sentinel confirmed on capability.* types)
            if (i.defaultValue != null && (i.defaultValue != true || i.type == "bool")) input.value = i.defaultValue
            else if (i.value != null && (i.value != true || i.type == "bool")) input.value = i.value
            section.inputs << input
        }
        // Paragraph/body content (informational text in the config page). Keep any
        // non-"Click to set" string — including short labels like "Enabled" / "Warning!"
        // that the SDK emits as standalone body paragraphs.
        def paragraphs = []
        for (b in (s.body ?: [])) {
            if (!(b instanceof Map)) continue
            def text = stripAppConfigHtml(b.description ?: b.title)
            if (text && text != "Click to set") paragraphs << text
        }
        if (paragraphs) section.paragraphs = paragraphs
        sections << section
    }

    def children = []
    for (c in (parsed.childApps ?: [])) {
        if (!(c instanceof Map)) continue
        children << [id: c.id, label: stripAppConfigHtml(c.label ?: c.name), name: c.name]
    }

    def result = [
        success: true,
        app: appObj,
        page: [
            name: parsed.configPage.name,
            title: stripAppConfigHtml(parsed.configPage.title),
            install: parsed.configPage.install == true,
            refreshInterval: parsed.configPage.refreshInterval,
            sections: sections
        ],
        childApps: children,
        endpoint: path
    ]

    int settingsCount = (parsed.settings instanceof Map) ? parsed.settings.size() : 0
    result.settingsKeyCount = settingsCount
    if (includeSettings) {
        result.settings = parsed.settings ?: [:]
    } else if (settingsCount > 0) {
        result.settingsNote = "Raw settings omitted -- pass includeSettings=true to include. Large apps (Room Lighting, RM 5.1) may have 500-1000 keys with app-specific encoding (e.g. \"dm~<deviceId>~<scene>\" for Room Lighting dim presets) that is non-trivial to decode without app-specific knowledge."
    }

    return result
}

/**
 * List known page names for a multi-page installed app.
 *
 * Combines live introspection of the primary page (one hub API call) with a
 * curated directory of known sub-page names for well-known app types (HPM,
 * Rule Machine, Room Lighting, Mode Manager). For unknown app types the
 * response contains only the primary page and a note about finding additional
 * pages via the app source or Web UI navigation.
 *
 * Gate: requireHubAdminRead() -- read-only, reads app metadata.
 * Args: appId (required, numeric string or integer)
 */
def toolListAppPages(args) {
    requireHubAdminRead()

    if (args?.appId == null || args.appId.toString().trim() == "") {
        throw new IllegalArgumentException("appId is required")
    }
    def appIdStr = args.appId.toString().trim()
    if (!appIdStr.isInteger()) {
        throw new IllegalArgumentException("appId must be numeric: ${appIdStr}")
    }

    mcpLog("info", "hub-admin", "list_app_pages appId=${appIdStr}")

    def path = "/installedapp/configure/json/${appIdStr}"
    def responseText
    try {
        responseText = hubInternalGet(path, null, 30)
    } catch (Exception e) {
        mcpLogError("hub-admin", "list_app_pages fetch failed", e)
        return [success: false, error: "Failed to fetch app config [${e.class.simpleName}]: ${e.message}", appId: appIdStr as Integer]
    }

    if (!responseText) {
        return [success: false, error: "Empty response from ${path}. App may not exist or hub internal API is unavailable.", appId: appIdStr as Integer]
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLogError("hub-admin", "list_app_pages JSON parse failed", e)
        return [success: false, error: "Failed to parse app config JSON: ${e.message}. Hubitat firmware may have changed the endpoint contract.", appId: appIdStr as Integer]
    }

    if (!(parsed instanceof Map)) {
        return [success: false, error: "Unexpected response shape: expected a JSON object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
    }
    if (!(parsed.app instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'app' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing app"]
    }
    if (!(parsed.configPage instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'configPage' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing configPage"]
    }

    def appTypeRaw = parsed.app.appType
    def appTypeName = (appTypeRaw instanceof Map) ? (appTypeRaw.name ?: "") : ""
    def appLabel = stripAppConfigHtml(parsed.app.trueLabel ?: parsed.app.label) ?: ""

    // Primary page: introspected from the hub response (configPage guaranteed Map by fingerprint check above)
    def primaryPageName = parsed.configPage.name ?: "mainPage"
    def primaryPageTitle = stripAppConfigHtml(parsed.configPage.title)
    def primaryPage = [name: primaryPageName, title: primaryPageTitle, role: "primary"]

    def appObj = [
        id: parsed.app.id,
        label: appLabel,
        name: parsed.app.name,
        appTypeName: appTypeName
    ]

    // Curated directory dispatch -- case-insensitive substring matching for robustness.
    def appTypeNameLower = appTypeName.toLowerCase()
    def pages
    def note = null

    if (appTypeNameLower.contains("hubitat package manager")) {
        pages = [
            [name: "prefOptions",    title: "Main Menu",                role: "navigation"],
            [name: "prefPkgUninstall", title: "Uninstall / Full Package List", role: "full_package_list"],
            [name: "prefPkgModify",  title: "Modify Package (optional-components subset)", role: "modifiable_subset"],
            [name: "prefPkgInstall", title: "Install New Package",       role: "install_flow"],
            [name: "prefPkgMatchUp", title: "Match Up Packages",         role: "matching_flow"]
        ]
    } else if (appTypeNameLower.contains("rule-5") || appTypeNameLower.contains("rule machine")) {
        pages = [
            [name: "mainPage", title: appLabel ?: "Rule Settings", role: "primary"]
        ]
        note = "Rule Machine rules are single-page. No sub-pages available."
    } else if (appTypeNameLower.contains("room lights") || appTypeNameLower.contains("room lighting")) {
        pages = [
            [name: "mainPage", title: appLabel ?: "Room Lighting Settings", role: "primary"]
        ]
        note = "Room Lighting instances use a single mainPage. No named sub-pages."
    } else if (appTypeNameLower.contains("mode manager")) {
        pages = [
            [name: "mainPage", title: "Manage Setting of Modes", role: "primary"]
        ]
        note = "Mode Manager uses a single mainPage. No named sub-pages."
    } else {
        pages = [primaryPage]
        note = "App-type-specific page directory not curated; only primary page known. For multi-page apps, consult the app's Groovy source or the Web UI navigation for sub-page names."
    }

    return [
        success: true,
        app: appObj,
        primaryPage: primaryPage,
        pages: pages,
        note: note
    ]
}

def toolGetAppSource(args) {
    return toolGetItemSource("app", "appId", args)
}

def toolGetDriverSource(args) {
    return toolGetItemSource("driver", "driverId", args)
}

def toolInstallApp(args) {
    return toolInstallItem("app", args)
}

def toolInstallDriver(args) {
    return toolInstallItem("driver", args)
}

/**
 * Shared implementation for installing apps and drivers.
 */
private Map toolInstallItem(String type, args) {
    requireHubAdminWrite(args.confirm)
    if (!args.source) throw new IllegalArgumentException("source (Groovy code) is required")

    def savePath = (type == "app") ? "/app/save" : "/driver/save"
    def editorPath = (type == "app") ? "/app/editor/" : "/driver/editor/"
    def idField = (type == "app") ? "appId" : "driverId"

    mcpLog("info", "hub-admin", "Installing new ${type}...")
    try {
        def result = hubInternalPostForm(savePath, [
            id: "",
            version: "",
            create: "",
            source: args.source
        ])

        def newItemId = null
        if (result?.location) {
            newItemId = result.location.replaceAll(".*?${editorPath}", "").replaceAll("[^0-9]", "")
        }

        mcpLog("info", "hub-admin", "${type.capitalize()} installed successfully${newItemId ? ' (ID: ' + newItemId + ')' : ''}")
        def installResult = [
            success: true,
            message: "${type.capitalize()} installed successfully",
            (idField): newItemId,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
        if (!newItemId) {
            installResult.warning = "Could not extract new ${type} ID from hub response. The ${type} was installed but you may need to check the Hubitat web UI to find it."
        }
        return installResult
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "${type.capitalize()} installation failed: ${e.message}")
        return [
            success: false,
            error: "${type.capitalize()} installation failed: ${e.message}",
            note: "Check that the Groovy source code is valid and doesn't have syntax errors."
        ]
    }
}

def toolUpdateItemCode(String type, String idParam, args) {
    requireHubAdminWrite(args.confirm)
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
    def updatePath = (type == "app") ? "/app/ajax/update" : "/driver/ajax/update"

    // Resolve source from one of three modes: source, sourceFile, or resave
    def sourceCode = null
    def sourceMode = null
    def freshVersion = null  // Track version from fresh fetch (not from backup cache)

    if (args.resave) {
        // Resave mode: fetch current source locally and re-save it (no cloud round-trip)
        sourceMode = "resave"
        mcpLog("info", "hub-admin", "Resave mode: fetching current ${type} ID ${itemId} source locally")
        def responseText = hubInternalGet(ajaxPath, [id: itemId])
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for ${type} ID ${itemId}")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error" || !parsed.source) {
            throw new IllegalArgumentException("Cannot read ${type} ID ${itemId}: ${parsed.errorMessage ?: 'no source returned'}")
        }
        sourceCode = parsed.source
        freshVersion = parsed.version  // Capture fresh version for optimistic locking
    } else if (args.sourceFile) {
        // Source file mode: read source from File Manager (avoids cloud size limits)
        sourceMode = "sourceFile"
        mcpLog("info", "hub-admin", "Reading ${type} source from File Manager: ${args.sourceFile}")
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from ${args.sourceFile}")
    } else if (args.source) {
        // Direct source mode
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', or 'resave' is required")
    }

    // Back up current source for safety (may use 1-hour cache — that's fine for backup purposes)
    def itemBackup = backupItemSource(type, itemId.toString())

    // For optimistic locking, use fresh version if available (resave mode already fetched it).
    // Otherwise fetch current version fresh from hub — backup cache may have stale version.
    def currentVersion = freshVersion
    if (currentVersion == null) {
        try {
            def versionResponse = hubInternalGet(ajaxPath, [id: itemId])
            if (versionResponse) {
                def versionParsed = new groovy.json.JsonSlurper().parseText(versionResponse)
                currentVersion = versionParsed.version
            }
        } catch (Exception vErr) {
            mcpLog("warn", "hub-admin", "Could not fetch fresh version for ${type} ID ${itemId}, falling back to backup version: ${vErr.message}")
        }
        // Fall back to backup version if fresh fetch failed
        if (currentVersion == null) currentVersion = itemBackup.version
    }

    if (currentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for ${type} ID ${itemId}. The ${type} may not exist.")
    }

    mcpLog("info", "hub-admin", "Updating ${type} ID: ${itemId} (version: ${currentVersion}, mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    try {
        def result = hubInternalPostForm(updatePath, [
            id: itemId,
            version: currentVersion,
            source: sourceCode
        ])

        def responseData = result?.data
        def success = false
        def errorMsg = null

        if (responseData) {
            try {
                def responseStr = responseData?.toString()
                def parsed = new groovy.json.JsonSlurper().parseText(responseStr)
                success = parsed.status == "success"
                errorMsg = parsed.errorMessage
            } catch (Exception parseErr) {
                mcpLog("warn", "hub-admin", "${type} update response was not JSON: ${responseData?.toString()?.take(200)}")
                errorMsg = "Unexpected response format — update may have succeeded but could not be confirmed. Check the ${type} in the Hubitat web UI."
            }
        } else {
            success = true
        }

        if (success) {
            mcpLog("info", "hub-admin", "${type} ID ${itemId} updated successfully (mode: ${sourceMode})")
            def successResult = [
                success: true,
                message: "${type.capitalize()} code updated successfully",
                (idParam): itemId,
                previousVersion: currentVersion,
                sourceMode: sourceMode,
                sourceLength: sourceCode.length(),
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
            if (sourceMode == "resave") successResult.note = "Source was fetched and re-saved entirely on-hub — no cloud round-trip."
            if (sourceMode == "sourceFile") successResult.note = "Source was read from File Manager file '${args.sourceFile}' — no cloud size limits."
            return successResult
        } else {
            return [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                (idParam): itemId,
                note: "Check the Groovy source code for syntax errors or compilation issues."
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "${type} update failed: ${e.message}")
        return [success: false, error: "${type.capitalize()} update failed: ${e.message}"]
    }
}

def toolUpdateAppCode(args) {
    return toolUpdateItemCode("app", "appId", args)
}

def toolUpdateDriverCode(args) {
    return toolUpdateItemCode("driver", "driverId", args)
}

def toolDeleteApp(args) {
    return toolDeleteItem("app", "appId", "/app/edit/deleteJsonSafe/", args)
}

def toolDeleteDriver(args) {
    return toolDeleteItem("driver", "driverId", "/driver/editor/deleteJson/", args)
}

/**
 * Shared implementation for deleting apps and drivers.
 * Backs up source code before deletion, then deletes and verifies.
 */
private Map toolDeleteItem(String type, String idParam, String deletePath, args) {
    requireHubAdminWrite(args.confirm)
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    def backupSucceeded = true
    try {
        backupItemSource(type, itemId.toString())
    } catch (Exception backupErr) {
        backupSucceeded = false
        mcpLog("warn", "hub-admin", "Pre-delete backup failed for ${type} ${itemId}: ${backupErr.message} — proceeding with delete")
    }

    mcpLog("warn", "hub-admin", "Deleting ${type} ID: ${itemId}")
    try {
        def responseText = hubInternalGet("${deletePath}${itemId}")
        mcpLog("debug", "hub-admin", "Delete ${type} ${itemId} response: ${responseText?.take(200)}")
        def success = false
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                success = parsed.status?.toString() == "true"
            } catch (Exception parseErr) {
                success = !responseText.toLowerCase().contains("error")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "${type.capitalize()} ID ${itemId} deleted successfully")
            // .toString() because the stored key is a String but Map.get(GString) does not coerce (hashCode mismatch → silent null).
            def backupEntry = state.itemBackupManifest?.get("${type}_${itemId}".toString())
            def installTool = (type == "app") ? "install_app" : "install_driver"
            def result = [
                success: true,
                message: backupSucceeded ? "${type.capitalize()} deleted successfully. Source code backed up to File Manager." : "${type.capitalize()} deleted successfully. WARNING: Pre-delete backup failed — source code may not be recoverable.",
                (idParam): itemId,
                lastBackup: formatTimestamp(state.lastBackupTimestamp),
                backupFile: backupEntry?.fileName,
                restoreHint: backupEntry ? "To restore: use '${installTool}' with the backup source, or download ${backupEntry.fileName} from Hubitat > Settings > File Manager and re-install manually." : null
            ]
            if (!backupSucceeded) result.backupWarning = "Pre-delete backup could not be created. The source code may be permanently lost."
            return result
        } else {
            return [
                success: false,
                error: "Delete may have failed - check the Hubitat web UI to verify",
                (idParam): itemId,
                response: responseText?.take(500)
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "${type.capitalize()} deletion failed: ${e.message}")
        return [success: false, error: "${type.capitalize()} deletion failed: ${e.message}"]
    }
}

// ==================== DEVICE ADMIN TOOL IMPLEMENTATIONS ====================

def toolDeleteDevice(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.deviceId) throw new IllegalArgumentException("deviceId is required")

    def deviceId = args.deviceId.toString()

    // Step 1: Gather device information for audit trail via hub internal API
    // We intentionally do NOT restrict to findDevice() (selectedDevices only) because
    // ghost/orphaned devices may not be in the selected device list
    def deviceInfo = null
    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (responseText) {
            deviceInfo = new groovy.json.JsonSlurper().parseText(responseText)
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Could not fetch device info for ${deviceId}: ${e.message}")
    }

    if (!deviceInfo) {
        throw new IllegalArgumentException("Device ${deviceId} not found on hub. Verify the device ID is correct.")
    }

    def deviceName = deviceInfo.label ?: deviceInfo.name ?: "Unknown"
    def deviceDNI = deviceInfo.deviceNetworkId ?: "unknown"
    def deviceType = deviceInfo.typeName ?: deviceInfo.type ?: "unknown"
    def warnings = []

    // Step 2: Check for recent activity (active device warning)
    try {
        def selectedDevice = findDevice(deviceId)
        if (selectedDevice) {
            def lastActivity = selectedDevice.lastActivity
            if (lastActivity) {
                def hoursAgo = Math.round((now() - lastActivity.time) / 3600000.0 * 10) / 10.0
                if (hoursAgo < 24) {
                    warnings << "ACTIVE DEVICE: Last activity was ${hoursAgo} hours ago at ${lastActivity.format("yyyy-MM-dd'T'HH:mm:ss")}. This device may still be functional."
                }
            }
            def recentEvents = selectedDevice.events(max: 3)
            if (recentEvents && recentEvents.size() > 0) {
                def lastEvent = recentEvents[0]
                warnings << "HAS RECENT EVENTS: Last event was ${lastEvent.name}=${lastEvent.value} at ${lastEvent.date?.format("yyyy-MM-dd'T'HH:mm:ss")}"
            }
        }
    } catch (Exception e) {
        // Device not in selected list or events unavailable — skip
    }

    // Step 3: Check Z-Wave/Zigbee radio membership
    def isRadioDevice = false
    try {
        // Check if device has a zigbeeId (Zigbee device)
        if (deviceInfo.zigbeeId) {
            isRadioDevice = true
            warnings << "ZIGBEE DEVICE: This device has Zigbee ID '${deviceInfo.zigbeeId}'. Force-deleting without proper Zigbee removal may leave an orphaned node on the mesh."
        }
        // Check if device network ID looks like a Z-Wave node (2-digit hex)
        if (deviceDNI && deviceDNI.matches(/^[0-9A-Fa-f]{2}$/)) {
            isRadioDevice = true
            warnings << "Z-WAVE DEVICE: Network ID '${deviceDNI}' suggests this is a Z-Wave node. Force-deleting without proper Z-Wave exclusion will leave a ghost node that degrades mesh performance."
        }
    } catch (Exception e) {
        // Skip radio check on error
    }

    // Step 4: Check if device is active on the Z-Wave/Zigbee radio node tables
    if (isRadioDevice) {
        try {
            // Check Z-Wave node table
            def zwaveEndpoints = ["/hub/zwaveDetails/json", "/hub2/zwaveInfo"]
            for (endpoint in zwaveEndpoints) {
                try {
                    def zwResponse = hubInternalGet(endpoint)
                    if (zwResponse) {
                        def zwData = new groovy.json.JsonSlurper().parseText(zwResponse)
                        def nodes = zwData?.nodes
                        if (nodes) {
                            def activeNode = nodes.find { it.deviceId?.toString() == deviceId }
                            if (activeNode) {
                                warnings << "ACTIVE ON Z-WAVE RADIO: Device is node ${activeNode.nodeId} with state '${activeNode.nodeState}'. It should be Z-Wave excluded BEFORE deletion."
                            }
                        }
                    }
                    break
                } catch (Exception e) { /* try next endpoint */ }
            }
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Could not check Z-Wave radio for device ${deviceId}: ${e.message}")
        }
        try {
            // Check Zigbee device table
            def zigEndpoints = ["/hub/zigbeeDetails/json", "/hub2/zigbeeInfo"]
            for (endpoint in zigEndpoints) {
                try {
                    def zigResponse = hubInternalGet(endpoint)
                    if (zigResponse) {
                        def zigData = new groovy.json.JsonSlurper().parseText(zigResponse)
                        def devices = zigData?.devices
                        if (devices) {
                            def activeDevice = devices.find { it.id?.toString() == deviceId }
                            if (activeDevice && activeDevice.active) {
                                warnings << "ACTIVE ON ZIGBEE RADIO: Device '${activeDevice.name}' is active on the Zigbee mesh. It should be removed via Zigbee BEFORE deletion."
                            }
                        }
                    }
                    break
                } catch (Exception e) { /* try next endpoint */ }
            }
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Could not check Zigbee radio for device ${deviceId}: ${e.message}")
        }
    }

    // Step 5: Check if any MCP rules reference this device
    try {
        def childApps = getChildApps()
        def referencingRules = []
        // Recursive search for device ID references without serializing to JSON
        def containsDeviceRef
        containsDeviceRef = { obj ->
            if (obj == null) return false
            if (obj instanceof String) return obj == deviceId
            if (obj instanceof Number) return obj.toString() == deviceId
            if (obj instanceof Map) return obj.values().any { containsDeviceRef(it) }
            if (obj instanceof Collection) return obj.any { containsDeviceRef(it) }
            return obj.toString() == deviceId
        }
        childApps?.each { childApp ->
            try {
                def ruleData = childApp.getRuleData()
                if (ruleData && containsDeviceRef(ruleData)) {
                    referencingRules << [id: ruleData.id, name: ruleData.name ?: "Unnamed"]
                }
            } catch (Exception e) { /* skip rule */ }
        }
        if (referencingRules) {
            warnings << "REFERENCED BY ${referencingRules.size()} MCP RULE(S): ${referencingRules.collect { "${it.name} (ID: ${it.id})" }.join(', ')}. These rules WILL BREAK after deletion."
        }
    } catch (Exception e) {
        mcpLog("debug", "hub-admin", "Could not check MCP rules for device ${deviceId}: ${e.message}")
    }

    // Step 6: Full audit log BEFORE deletion
    mcpLog("warn", "hub-admin", "DELETE DEVICE AUDIT: Deleting '${deviceName}' (ID: ${deviceId}, DNI: ${deviceDNI}, Type: ${deviceType}). Warnings: ${warnings.size() > 0 ? warnings.join(' | ') : 'none'}")

    // Step 7: Execute force delete via hub internal API
    try {
        def responseText = hubInternalGet("/device/forceDelete/${deviceId}/yes", null, 30)
        mcpLog("debug", "hub-admin", "Force delete response for device ${deviceId}: ${responseText?.take(500)}")
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Device force delete FAILED for '${deviceName}' (${deviceId}): ${e.message}")
        return [
            success: false,
            error: "Force delete failed: ${e.message}",
            deviceId: deviceId,
            deviceName: deviceName,
            warnings: warnings
        ]
    }

    // Step 8: Verify deletion by attempting to re-fetch the device
    def verified = false
    try {
        def checkResponse = hubInternalGet("/device/fullJson/${deviceId}")
        if (checkResponse) {
            try {
                def checkParsed = new groovy.json.JsonSlurper().parseText(checkResponse)
                verified = !checkParsed?.id
            } catch (Exception parseErr) {
                // Non-JSON response or error page = likely deleted
                verified = true
            }
        } else {
            verified = true
        }
    } catch (Exception e) {
        // 404 or error = device is gone = success
        verified = true
    }

    mcpLog(verified ? "info" : "warn", "hub-admin", "Device delete ${verified ? 'VERIFIED' : 'UNVERIFIED'}: '${deviceName}' (ID: ${deviceId})")

    return [
        success: verified,
        deviceId: deviceId,
        deviceName: deviceName,
        message: verified
            ? "Device '${deviceName}' (ID: ${deviceId}) has been permanently deleted."
            : "Delete command was sent but device may still exist. Check Hubitat web UI to verify.",
        warnings: warnings,
        auditInfo: [
            deletedAt: formatTimestamp(now()),
            deviceType: deviceType,
            deviceNetworkId: deviceDNI,
            driverName: deviceType,
            lastHubBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    ]
}

// ==================== VIRTUAL DEVICE MANAGEMENT TOOL IMPLEMENTATIONS ====================

def toolManageVirtualDevice(args) {
    def action = args.action
    if (!action) {
        throw new IllegalArgumentException("action is required. Use 'create' or 'delete'.")
    }
    switch (action) {
        case "create":
            if (!args.deviceType) throw new IllegalArgumentException("deviceType is required for action='create'. Supported types: ${getSupportedVirtualDeviceTypes().join(', ')}.")
            if (!args.deviceLabel) throw new IllegalArgumentException("deviceLabel is required for action='create'.")
            return toolCreateVirtualDevice(args)
        case "delete":
            if (!args.deviceNetworkId) throw new IllegalArgumentException("deviceNetworkId is required for action='delete'. Use list_virtual_devices to find the DNI.")
            return toolDeleteVirtualDevice(args)
        default:
            throw new IllegalArgumentException("Unknown action '${action}'. Use 'create' or 'delete'.")
    }
}

// Single source of truth: the tool-schema enum, the missing-arg error,
// and the create-time validator must all agree on this list.
def getSupportedVirtualDeviceTypes() {
    [
        "Virtual Switch", "Virtual Button", "Virtual Contact Sensor",
        "Virtual Motion Sensor", "Virtual Presence", "Virtual Lock",
        "Virtual Temperature Sensor", "Virtual Humidity Sensor", "Virtual Dimmer",
        "Virtual RGBW Light", "Virtual Shade", "Virtual Garage Door Opener",
        "Virtual Water Sensor", "Virtual Omni Sensor", "Virtual Fan Controller"
    ]
}

def toolCreateVirtualDevice(args) {
    requireHubAdminWrite(args.confirm)

    def deviceType = args.deviceType
    def deviceLabel = args.deviceLabel
    def dni = args.deviceNetworkId

    if (!deviceType) throw new IllegalArgumentException("deviceType is required")
    if (!deviceLabel) throw new IllegalArgumentException("deviceLabel is required")

    def supportedTypes = getSupportedVirtualDeviceTypes()
    if (!supportedTypes.contains(deviceType)) {
        throw new IllegalArgumentException("Unsupported device type: '${deviceType}'. Supported types: ${supportedTypes.join(', ')}")
    }

    // Fetch child devices once for DNI generation and validation
    def childDevs = getChildDevices() ?: []

    // Auto-generate DNI if not provided, with uniqueness retry
    if (!dni) {
        def existingDnis = childDevs.collect { it.deviceNetworkId } as Set
        def attempts = 0
        while (attempts < 5) {
            def timestamp = Long.toString(now(), 16).toUpperCase()
            def rand = Integer.toString(new Random().nextInt(0xFFFF), 16).toUpperCase().padLeft(4, '0')
            dni = "mcp-virtual-${timestamp}-${rand}"
            if (!existingDnis.contains(dni)) break
            attempts++
            pauseExecution(1) // ensure different now() on retry
        }
    }

    // Validate DNI uniqueness against existing child devices
    def existingChild = childDevs.find { it.deviceNetworkId == dni }
    if (existingChild) {
        throw new IllegalArgumentException("A device with network ID '${dni}' already exists: '${existingChild.label ?: existingChild.name}' (ID: ${existingChild.id})")
    }

    mcpLog("info", "device", "Creating virtual device: type='${deviceType}', label='${deviceLabel}', dni='${dni}'")

    def newDevice = null
    try {
        newDevice = addChildDevice("hubitat", deviceType, dni, null, [
            name: deviceType,
            label: deviceLabel,
            isComponent: false
        ])
    } catch (Exception e) {
        mcpLog("error", "device", "Failed to create virtual device '${deviceLabel}': ${e.message}")
        if (e.message?.contains("UnknownDeviceTypeException") || e.message?.contains("not found")) {
            throw new RuntimeException("Failed to create virtual device: Driver '${deviceType}' not found on this hub. The hub firmware may not include this built-in driver.")
        }
        if (e.message?.contains("already exists") || e.message?.contains("unique")) {
            throw new RuntimeException("Failed to create virtual device: Network ID '${dni}' already exists on the hub. Try again with a different deviceNetworkId.")
        }
        throw new RuntimeException("Failed to create virtual device: ${e.message}")
    }

    if (!newDevice) {
        throw new RuntimeException("Failed to create virtual device - addChildDevice returned null")
    }

    // Read back device info
    def deviceInfo = [
        id: newDevice.id.toString(),
        name: newDevice.name,
        label: newDevice.label ?: newDevice.name,
        deviceNetworkId: newDevice.deviceNetworkId,
        typeName: deviceType,
        capabilities: newDevice.capabilities?.collect { it.name } ?: [],
        commands: newDevice.supportedCommands?.collect { it.name } ?: [],
        attributes: newDevice.supportedAttributes?.collect { attr ->
            [name: attr.name, value: newDevice.currentValue(attr.name)]
        } ?: []
    ]

    mcpLog("info", "device", "Virtual device created successfully: '${deviceLabel}' (ID: ${newDevice.id}, DNI: ${dni})")

    return [
        success: true,
        message: "Virtual device '${deviceLabel}' created successfully. It is now accessible via all MCP device tools (send_command, get_device, etc.) without needing to be added to the device selection list. It also appears in the Hubitat device list and can be shared with other apps like Maker API.",
        device: deviceInfo,
        tips: [
            "Use send_command with deviceId '${newDevice.id}' to control this device",
            "The device is visible in Hubitat web UI under Devices for sharing with other apps",
            "To add it to Maker API: open Maker API app settings and select this device"
        ]
    ]
}

def toolListVirtualDevices(args) {
    def childDevs = getChildDevices() ?: []

    if (!childDevs) {
        return [
            devices: [],
            count: 0,
            message: "No MCP-managed virtual devices found. Use manage_virtual_device(action=\"create\") to create one."
        ]
    }

    def devices = childDevs.collect { device ->
        def info = [
            id: device.id.toString(),
            name: device.name,
            label: device.label ?: device.name,
            deviceNetworkId: device.deviceNetworkId,
            typeName: device.typeName ?: device.name,
            capabilities: device.capabilities?.collect { it.name } ?: [],
            commands: device.supportedCommands?.collect { it.name } ?: [],
            currentStates: [:]
        ]
        // Gather common attribute values
        ["switch", "level", "contact", "motion", "temperature", "humidity",
         "presence", "lock", "water", "button", "speed", "position"].each { attr ->
            def val = device.currentValue(attr)
            if (val != null) info.currentStates[attr] = val
        }
        return info
    }

    return [
        devices: devices,
        count: devices.size(),
        message: "Found ${devices.size()} MCP-managed virtual device(s). These are automatically accessible to all MCP device tools."
    ]
}

def toolDeleteVirtualDevice(args) {
    requireHubAdminWrite(args.confirm)

    def dni = args.deviceNetworkId
    if (!dni) throw new IllegalArgumentException("deviceNetworkId is required")

    def childDevice = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (!childDevice) {
        throw new IllegalArgumentException("No MCP-managed virtual device found with network ID '${dni}'. Use list_virtual_devices to see available devices.")
    }

    def deviceLabel = childDevice.label ?: childDevice.name ?: "Unknown"
    def deviceId = childDevice.id.toString()

    mcpLog("warn", "device", "DELETE VIRTUAL DEVICE: Deleting '${deviceLabel}' (ID: ${deviceId}, DNI: ${dni})")

    try {
        deleteChildDevice(dni)
    } catch (Exception e) {
        mcpLog("error", "device", "Failed to delete virtual device '${deviceLabel}' (DNI: ${dni}): ${e.message}")
        throw new RuntimeException("Failed to delete virtual device: ${e.message}")
    }

    // Verify deletion
    def stillExists = getChildDevices()?.find { it.deviceNetworkId == dni }
    def verified = !stillExists

    mcpLog(verified ? "info" : "warn", "device", "Virtual device delete ${verified ? 'VERIFIED' : 'UNVERIFIED'}: '${deviceLabel}' (DNI: ${dni})")

    return [
        success: verified,
        deviceId: deviceId,
        deviceNetworkId: dni,
        deviceLabel: deviceLabel,
        message: verified
            ? "Virtual device '${deviceLabel}' (DNI: ${dni}) has been permanently deleted."
            : "Delete command was sent but device may still exist. Check Hubitat web UI to verify."
    ]
}

def toolUpdateDevice(args) {
    def deviceId = args.deviceId
    if (!deviceId) throw new IllegalArgumentException("deviceId is required")

    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}. The device must be in your selected devices or be an MCP-managed virtual device.")
    }

    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"
    def changes = []
    def errors = []

    def requestedProps = []
    if (args.label != null) requestedProps << "label"
    if (args.name != null) requestedProps << "name"
    if (args.deviceNetworkId != null) requestedProps << "deviceNetworkId"
    if (args.dataValues) requestedProps << "dataValues(${args.dataValues.size()})"
    if (args.preferences) requestedProps << "preferences(${args.preferences.size()})"
    if (args.room != null) requestedProps << "room"
    if (args.enabled != null) requestedProps << "enabled"
    mcpLog("debug", "device", "update_device called for '${deviceLabel}' (ID: ${deviceId}), properties: ${requestedProps.join(', ')}")

    // Label (official API)
    if (args.label != null) {
        try {
            def oldLabel = deviceLabel
            device.setLabel(args.label)
            changes << [property: "label", oldValue: oldLabel, newValue: args.label]
            deviceLabel = args.label
            mcpLog("debug", "device", "update_device label: '${oldLabel}' -> '${args.label}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "update_device label: error: ${e.message}")
            errors << [property: "label", error: e.message]
        }
    }

    // Name (official API)
    if (args.name != null) {
        try {
            def oldName = device.name
            device.setName(args.name)
            changes << [property: "name", oldValue: oldName, newValue: args.name]
            mcpLog("debug", "device", "update_device name: '${oldName}' -> '${args.name}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "update_device name: error: ${e.message}")
            errors << [property: "name", error: e.message]
        }
    }

    // Device Network ID (official API)
    if (args.deviceNetworkId != null) {
        try {
            def oldDni = device.deviceNetworkId
            device.setDeviceNetworkId(args.deviceNetworkId)
            changes << [property: "deviceNetworkId", oldValue: oldDni, newValue: args.deviceNetworkId]
            mcpLog("debug", "device", "update_device DNI: '${oldDni}' -> '${args.deviceNetworkId}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "update_device DNI: error: ${e.message}")
            errors << [property: "deviceNetworkId", error: e.message]
        }
    }

    // Data Values (official API)
    if (args.dataValues) {
        args.dataValues.each { key, value ->
            try {
                device.updateDataValue(key.toString(), value?.toString())
                changes << [property: "dataValue.${key}", newValue: value?.toString()]
                mcpLog("debug", "device", "update_device dataValue: ${key}='${value}'")
            } catch (Exception e) {
                mcpLog("debug", "device", "update_device dataValue ${key}: error: ${e.message}")
                errors << [property: "dataValue.${key}", error: e.message]
            }
        }
    }

    // Preferences (official API)
    if (args.preferences) {
        args.preferences.each { key, setting ->
            try {
                if (setting instanceof Map && setting.type && setting.containsKey("value")) {
                    device.updateSetting(key.toString(), [type: setting.type.toString(), value: setting.value])
                    mcpLog("debug", "device", "update_device preference: ${key}={type:${setting.type}, value:${setting.value}}")
                } else {
                    device.updateSetting(key.toString(), setting?.toString())
                    mcpLog("debug", "device", "update_device preference: ${key}='${setting}'")
                }
                changes << [property: "preference.${key}", newValue: setting]
            } catch (Exception e) {
                mcpLog("debug", "device", "update_device preference ${key}: error: ${e.message}")
                errors << [property: "preference.${key}", error: e.message]
            }
        }
    }

    // Room (internal API — requires Hub Admin Write)
    if (args.room != null) {
        if (!settings.enableHubAdminWrite) {
            errors << [property: "room", error: "Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings"]
        } else {
            try {
                mcpLog("debug", "device", "update_device room: starting room assignment for device ${deviceId}")

                // Find room ID by name
                def targetRoomId = null
                if (args.room == "" || args.room == "none" || args.room == "null") {
                    targetRoomId = "0"
                    mcpLog("debug", "device", "update_device room: unassigning device from room")
                } else {
                    def cachedRooms = null
                    try {
                        cachedRooms = getRooms()
                        mcpLog("debug", "device", "update_device room: getRooms() returned ${cachedRooms?.size() ?: 0} rooms")
                        if (cachedRooms) {
                            def targetRoom = cachedRooms.find { it.name?.toString()?.toLowerCase() == args.room?.toString()?.toLowerCase() }
                            if (targetRoom) {
                                targetRoomId = targetRoom.id?.toString()
                                mcpLog("debug", "device", "update_device room: resolved '${args.room}' -> roomId=${targetRoomId}")
                            }
                        }
                    } catch (Exception e) {
                        mcpLog("debug", "device", "update_device room: getRooms() failed: ${e.message}")
                    }

                    if (targetRoomId == null) {
                        def allRoomNames = cachedRooms ? cachedRooms.collect { it.name } : []
                        throw new RuntimeException("Room '${args.room}' not found.${allRoomNames ? ' Available rooms: ' + allRoomNames.join(', ') : ''}")
                    }
                }

                // Room assignment via POST /room/save with JSON body.
                // API uses "roomId" field (not "id"). Content-Type must be application/json.

                def saveSuccess = false
                def saveError = null
                def deviceIdLong = deviceId as Long
                def deviceIdInt = deviceId as Integer

                // Helper: POST JSON to /room/save and check for errors
                def roomSavePost = { Map bodyMap ->
                    def cookie = getHubSecurityCookie()
                    def jsonStr = groovy.json.JsonOutput.toJson(bodyMap)
                    def postParams = [
                        uri: "http://127.0.0.1:8080",
                        path: "/room/save",
                        requestContentType: "application/json",
                        body: jsonStr,
                        textParser: true,
                        timeout: 30,
                        ignoreSSLIssues: true
                    ]
                    if (cookie) { postParams.headers = ["Cookie": cookie] }
                    def respBody = null
                    httpPost(postParams) { resp ->
                        try { respBody = resp.data?.text?.toString() } catch (Exception ignored) { respBody = resp.data?.toString() }
                    }
                    // Parse JSON response to check for error field
                    if (respBody) {
                        try {
                            def parsed = new groovy.json.JsonSlurper().parseText(respBody)
                            if (parsed?.error) {
                                throw new RuntimeException("Room API error: ${parsed.error}")
                            }
                        } catch (groovy.json.JsonException ignored) {
                            // Non-JSON response — check raw text
                            if (respBody.toLowerCase().contains("error")) {
                                throw new RuntimeException("Room API error (non-JSON): ${respBody.take(500)}")
                            }
                        }
                    }
                    return respBody
                }

                // Helper: check if device is in a room's device list
                def deviceInRoom = { room ->
                    room?.deviceIds?.contains(deviceIdLong) || room?.deviceIds?.contains(deviceIdInt)
                }

                // Get current room data
                def allRooms = getRooms()
                mcpLog("debug", "device", "update_device room: getRooms() returned ${allRooms?.size() ?: 0} rooms")

                if (targetRoomId == "0") {
                    // --- UNASSIGN: remove device from its current room ---
                    def currentRoom = allRooms?.find { deviceInRoom(it) }
                    if (!currentRoom) {
                        saveSuccess = true
                        mcpLog("debug", "device", "update_device room: device not in any room, nothing to unassign")
                    } else {
                        mcpLog("debug", "device", "update_device room: removing device ${deviceId} from room '${currentRoom.name}' (${currentRoom.id})")
                        def updatedDeviceIds = currentRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
                        def body = [roomId: currentRoom.id as Integer, name: currentRoom.name, deviceIds: updatedDeviceIds]
                        mcpLog("debug", "device", "update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(body)}")
                        try {
                            roomSavePost(body)
                            saveSuccess = true
                        } catch (Exception e) {
                            mcpLog("debug", "device", "update_device room: remove failed: ${e.message}")
                            saveError = e.message
                        }
                    }
                } else {
                    // --- ASSIGN: add device to target room ---
                    mcpLog("debug", "device", "update_device room: assigning device ${deviceId} to room ${targetRoomId}")

                    // Check if device is already in the target room
                    def targetRoom = allRooms?.find { it.id?.toString() == targetRoomId }
                    if (targetRoom && deviceInRoom(targetRoom)) {
                        mcpLog("debug", "device", "update_device room: device already in target room '${targetRoom.name}'")
                        saveSuccess = true
                    } else {
                        // Safe Move pattern: add to new room FIRST, then remove from old room.
                        // This prevents "device limbo" where a device ends up in no room if
                        // the second API call fails after the first succeeds.
                        // Worst case (remove fails): device appears in both rooms temporarily,
                        // which is recoverable. The old pattern (remove first) could orphan the device.

                        // Locate old room (if any) before mutations
                        def oldRoom = allRooms?.find { room ->
                            deviceInRoom(room) && room.id?.toString() != targetRoomId
                        }

                        // Step 1: Add device to target room
                        def freshTarget = allRooms?.find { it.id?.toString() == targetRoomId }
                        def targetDeviceIds = freshTarget?.deviceIds?.collect { it as Integer } ?: []
                        def devIdInt = deviceId as Integer
                        if (!targetDeviceIds.contains(devIdInt)) {
                            targetDeviceIds << devIdInt
                        }

                        def roomData = [roomId: targetRoomId as Integer, name: freshTarget?.name ?: targetRoom?.name ?: "", deviceIds: targetDeviceIds]
                        mcpLog("debug", "device", "update_device room: POST /room/save (add) body: ${groovy.json.JsonOutput.toJson(roomData)}")
                        try {
                            roomSavePost(roomData)
                            mcpLog("debug", "device", "update_device room: added to target room '${freshTarget?.name ?: targetRoomId}'")
                            saveSuccess = true
                        } catch (Exception e) {
                            // Add failed — device stays safely in its old room (no change made)
                            mcpLog("debug", "device", "update_device room: add to room failed: ${e.message}")
                            saveError = e.message
                        }

                        // Step 2: Remove from old room (only if add succeeded)
                        if (saveSuccess && oldRoom) {
                            mcpLog("debug", "device", "update_device room: removing from old room '${oldRoom.name}' (${oldRoom.id})")
                            // Re-fetch rooms to get fresh data after the add mutation
                            def freshRooms = getRooms()
                            def freshOldRoom = freshRooms?.find { it.id?.toString() == oldRoom.id?.toString() }
                            if (freshOldRoom) {
                                def oldDeviceIds = freshOldRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
                                def oldBody = [roomId: freshOldRoom.id as Integer, name: freshOldRoom.name, deviceIds: oldDeviceIds]
                                mcpLog("debug", "device", "update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(oldBody)}")
                                try {
                                    roomSavePost(oldBody)
                                    mcpLog("debug", "device", "update_device room: removed from old room '${oldRoom.name}'")
                                } catch (Exception oldErr) {
                                    // Device is in both rooms — not ideal but it IS in the target room.
                                    // Log a warning so the user is aware.
                                    mcpLog("warn", "device", "update_device room: device added to new room but removal from old room '${oldRoom.name}' failed: ${oldErr.message}. Device may appear in both rooms.")
                                }
                            }
                        }
                    }
                }

                // Verify the room actually changed
                if (saveSuccess) {
                    def verified = false
                    try {
                        def verifyRooms = getRooms()
                        if (targetRoomId == "0") {
                            def stillInRoom = verifyRooms?.find { room -> deviceInRoom(room) }
                            verified = (stillInRoom == null)
                            if (!verified) {
                                mcpLog("debug", "device", "update_device room: VERIFICATION FAILED - device still in room '${stillInRoom?.name}'")
                            }
                        } else {
                            def tRoom = verifyRooms?.find { it.id?.toString() == targetRoomId }
                            verified = deviceInRoom(tRoom)
                            if (!verified) {
                                mcpLog("debug", "device", "update_device room: VERIFICATION FAILED - device not in target room '${tRoom?.name}' deviceIds: ${tRoom?.deviceIds}")
                            }
                            // Also verify device is NOT still in the old room
                            if (verified) {
                                def dualRoom = verifyRooms?.find { room -> deviceInRoom(room) && room.id?.toString() != targetRoomId }
                                if (dualRoom) {
                                    mcpLog("warn", "device", "update_device room: WARNING - device also still in room '${dualRoom.name}' (dual-room state)")
                                }
                            }
                        }
                    } catch (Exception verErr) {
                        mcpLog("debug", "device", "update_device room: verification error: ${verErr.message}")
                    }

                    if (verified) {
                        def oldRoomName = device.roomName ?: "none"
                        changes << [property: "room", oldValue: oldRoomName, newValue: args.room ?: "none"]
                        mcpLog("info", "device", "Room changed for '${deviceLabel}': ${oldRoomName} -> ${args.room ?: 'none'} (VERIFIED)")
                    } else {
                        throw new RuntimeException("Room assignment endpoint returned success but room did not actually change.")
                    }
                } else {
                    throw new RuntimeException("Room assignment failed. Last error: ${saveError}")
                }
            } catch (Exception e) {
                mcpLog("debug", "device", "update_device room: error: ${e.message}")
                errors << [property: "room", error: e.message]
            }
        }
    }

    // Enable/Disable (internal API — requires Hub Admin Write)
    // Hubitat's /device/disable endpoint requires POST with body params, not GET with query params
    if (args.enabled != null) {
        if (!settings.enableHubAdminWrite) {
            errors << [property: "enabled", error: "Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings"]
        } else {
            try {
                def disableValue = args.enabled ? "false" : "true"
                mcpLog("debug", "device", "update_device enabled: POSTing to /device/disable with id=${deviceId}, disable=${disableValue}")
                hubInternalPost("/device/disable", [id: deviceId, disable: disableValue])
                changes << [property: "enabled", newValue: args.enabled]
                mcpLog("info", "device", "Device '${deviceLabel}' ${args.enabled ? 'enabled' : 'disabled'}")
            } catch (Exception e) {
                mcpLog("debug", "device", "update_device enabled: error: ${e.message}")
                errors << [property: "enabled", error: e.message]
            }
        }
    }

    if (!changes && !errors) {
        return [
            success: true,
            device: deviceLabel,
            deviceId: deviceId,
            message: "No properties were provided to update. Specify at least one property: label, name, deviceNetworkId, room, enabled, dataValues, or preferences."
        ]
    }

    mcpLog("info", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}): ${changes.size()} changes, ${errors.size()} errors")
    if (errors) {
        mcpLog("debug", "device", "update_device errors: ${errors.collect { "${it.property}: ${it.error}" }.join('; ')}")
    }

    return [
        success: errors.isEmpty(),
        device: deviceLabel,
        deviceId: deviceId,
        changes: changes,
        errors: errors.isEmpty() ? null : errors,
        message: errors.isEmpty()
            ? "Successfully updated ${changes.size()} property/properties on device '${deviceLabel}'."
            : "Updated ${changes.size()} property/properties with ${errors.size()} error(s) on device '${deviceLabel}'."
    ]
}

// ==================== ROOM MANAGEMENT ====================

def toolListRooms() {
    def rooms = getRooms()
    if (!rooms) {
        return [rooms: [], count: 0, message: "No rooms configured on this hub."]
    }
    def roomList = rooms.collect { room ->
        [
            id: room.id?.toString(),
            name: room.name,
            deviceCount: room.deviceIds?.size() ?: 0,
            deviceIds: room.deviceIds?.collect { it.toString() } ?: []
        ]
    }.sort { it.name }
    return [rooms: roomList, count: roomList.size()]
}

def toolGetRoom(String roomIdentifier) {
    if (!roomIdentifier) throw new IllegalArgumentException("Room name or ID is required")

    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    // Find room by ID or name (case-insensitive)
    def room = rooms.find { it.id?.toString() == roomIdentifier } ?:
               rooms.find { it.name?.toLowerCase() == roomIdentifier.toLowerCase() }

    if (!room) {
        def available = rooms.collect { it.name }.sort()
        throw new IllegalArgumentException("Room '${roomIdentifier}' not found. Available rooms: ${available.join(', ')}")
    }

    // Get device details for each device in the room
    def devices = []
    def allDevices = (settings.selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    def selectedIds = allDevices.collect { it.id.toString() } as Set
    childDevs.each { cd -> if (!selectedIds.contains(cd.id.toString())) { allDevices.add(cd) } }

    room.deviceIds?.each { devId ->
        def device = allDevices?.find { it.id?.toString() == devId.toString() }
        if (device) {
            def devInfo = [
                id: device.id.toString(),
                label: device.label ?: device.name ?: "unknown",
                name: device.name ?: "unknown"
            ]
            // Add common current states
            def states = [:]
            try {
                device.currentStates?.each { st ->
                    states[st.name] = st.value
                }
            } catch (Exception ignored) {}
            if (states) devInfo.currentStates = states
            devices << devInfo
        } else {
            devices << [id: devId.toString(), label: "(device not accessible via MCP)", name: "unknown", accessible: false]
        }
    }

    return [
        id: room.id?.toString(),
        name: room.name,
        deviceCount: devices.size(),
        devices: devices.sort { (it.label ?: "").toLowerCase() }
    ]
}

def toolCreateRoom(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.name?.trim()) {
        throw new IllegalArgumentException("Room name is required")
    }

    def roomName = args.name.trim()

    // Check for duplicate name
    def rooms = getRooms()
    if (rooms?.find { it.name?.toLowerCase() == roomName.toLowerCase() }) {
        throw new IllegalArgumentException("A room named '${roomName}' already exists")
    }

    // Build device IDs list
    def deviceIds = args.deviceIds?.collect { it as Integer } ?: []

    // POST /room/save with roomId: 0 to create (Grails convention)
    def cookie = getHubSecurityCookie()
    def body = [roomId: 0, name: roomName, deviceIds: deviceIds]
    def jsonStr = groovy.json.JsonOutput.toJson(body)
    mcpLog("debug", "room", "create_room: POST /room/save body: ${jsonStr}")

    def respBody = null
    try {
        def postParams = [
            uri: "http://127.0.0.1:8080",
            path: "/room/save",
            requestContentType: "application/json",
            body: jsonStr,
            textParser: true,
            timeout: 30,
            ignoreSSLIssues: true
        ]
        if (cookie) { postParams.headers = ["Cookie": cookie] }
        httpPost(postParams) { resp ->
            try { respBody = resp.data?.text?.toString() } catch (Exception ignored) { respBody = resp.data?.toString() }
            mcpLog("debug", "room", "create_room: response status=${resp.status} body=${respBody?.take(500)}")
        }
    } catch (Exception httpErr) {
        throw new RuntimeException("Failed to create room '${roomName}': ${httpErr.message}")
    }

    // Parse JSON response to check for error
    if (respBody) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(respBody)
            if (parsed?.error) {
                throw new RuntimeException("Failed to create room: ${parsed.error}")
            }
        } catch (groovy.json.JsonException ignored) {}
    }

    // Verify creation (case-insensitive to handle any normalization)
    def updatedRooms = getRooms()
    def newRoom = updatedRooms?.find { it.name?.toLowerCase() == roomName.toLowerCase() }
    if (!newRoom) {
        throw new RuntimeException("Room creation endpoint returned success but room '${roomName}' not found in rooms list")
    }

    mcpLog("info", "room", "Created room '${roomName}' (ID: ${newRoom.id})")
    return [
        success: true,
        room: [id: newRoom.id?.toString(), name: newRoom.name, deviceCount: newRoom.deviceIds?.size() ?: 0],
        message: "Room '${roomName}' created successfully."
    ]
}

def toolDeleteRoom(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.room?.trim()) {
        throw new IllegalArgumentException("Room name or ID is required")
    }

    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    def room = rooms.find { it.id?.toString() == args.room.trim() } ?:
               rooms.find { it.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it.name }.sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    def roomId = room.id
    def roomName = room.name
    def deviceCount = room.deviceIds?.size() ?: 0
    mcpLog("debug", "room", "delete_room: deleting room '${roomName}' (ID: ${roomId}), ${deviceCount} devices will be unassigned")

    def cookie = getHubSecurityCookie()
    def deleteSuccess = false
    def deleteError = null

    // Try POST /room/delete/<id> first, then GET /room/delete/<id>
    def attempts = [
        [desc: "POST /room/delete/${roomId}", method: "POST"],
        [desc: "GET /room/delete/${roomId}", method: "GET"],
    ]
    for (def att : attempts) {
        if (deleteSuccess) break
        try {
            if (att.method == "POST") {
                def postParams = [
                    uri: "http://127.0.0.1:8080",
                    path: "/room/delete/${roomId}",
                    requestContentType: "application/json",
                    body: groovy.json.JsonOutput.toJson([roomId: roomId as Integer]),
                    textParser: true,
                    timeout: 30,
                    ignoreSSLIssues: true
                ]
                if (cookie) { postParams.headers = ["Cookie": cookie] }
                def postRespBody = null
                httpPost(postParams) { resp ->
                    try { postRespBody = resp.data?.text?.toString() } catch (Exception ignored) { postRespBody = resp.data?.toString() }
                    mcpLog("debug", "room", "delete_room: ${att.desc} status=${resp.status} body=${postRespBody?.take(500)}")
                }
                // Check response for error
                if (postRespBody) {
                    try {
                        def parsed = new groovy.json.JsonSlurper().parseText(postRespBody)
                        if (parsed?.error) {
                            throw new RuntimeException("API error: ${parsed.error}")
                        }
                    } catch (groovy.json.JsonException ignored) {}
                }
                deleteSuccess = true
            } else {
                hubInternalGet("/room/delete/${roomId}")
                mcpLog("debug", "room", "delete_room: ${att.desc} succeeded")
                deleteSuccess = true
            }
        } catch (Exception e) {
            mcpLog("debug", "room", "delete_room: ${att.desc} failed: ${e.message}")
            deleteError = e.message
        }
    }

    if (!deleteSuccess) {
        throw new RuntimeException("Failed to delete room '${roomName}'. Last error: ${deleteError}")
    }

    // Verify deletion
    def updatedRooms = getRooms()
    def stillExists = updatedRooms?.find { it.id?.toString() == roomId.toString() }
    if (stillExists) {
        throw new RuntimeException("Delete endpoint returned success but room '${roomName}' still exists")
    }

    mcpLog("info", "room", "Deleted room '${roomName}' (ID: ${roomId}), ${deviceCount} devices unassigned")
    return [
        success: true,
        deletedRoom: [id: roomId.toString(), name: roomName],
        devicesUnassigned: deviceCount,
        message: "Room '${roomName}' deleted. ${deviceCount} device(s) are now unassigned."
    ]
}

def toolRenameRoom(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.room?.trim()) {
        throw new IllegalArgumentException("Room name or ID is required")
    }
    if (!args.newName?.trim()) {
        throw new IllegalArgumentException("New room name is required")
    }

    def newName = args.newName.trim()
    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    def room = rooms.find { it.id?.toString() == args.room.trim() } ?:
               rooms.find { it.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it.name }.sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    // Check for name conflict
    if (rooms.find { it.name?.toLowerCase() == newName.toLowerCase() && it.id != room.id }) {
        throw new IllegalArgumentException("A room named '${newName}' already exists")
    }

    def oldName = room.name
    def roomId = room.id
    def deviceIds = room.deviceIds?.collect { it as Integer } ?: []

    // POST /room/save with existing roomId and new name
    def cookie = getHubSecurityCookie()
    def body = [roomId: roomId as Integer, name: newName, deviceIds: deviceIds]
    def jsonStr = groovy.json.JsonOutput.toJson(body)
    mcpLog("debug", "room", "rename_room: POST /room/save body: ${jsonStr}")

    def respBody = null
    try {
        def postParams = [
            uri: "http://127.0.0.1:8080",
            path: "/room/save",
            requestContentType: "application/json",
            body: jsonStr,
            textParser: true,
            timeout: 30,
            ignoreSSLIssues: true
        ]
        if (cookie) { postParams.headers = ["Cookie": cookie] }
        httpPost(postParams) { resp ->
            try { respBody = resp.data?.text?.toString() } catch (Exception ignored) { respBody = resp.data?.toString() }
            mcpLog("debug", "room", "rename_room: response status=${resp.status} body=${respBody?.take(500)}")
        }
    } catch (Exception httpErr) {
        throw new RuntimeException("Failed to rename room '${oldName}': ${httpErr.message}")
    }

    // Parse JSON response to check for error
    if (respBody) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(respBody)
            if (parsed?.error) {
                throw new RuntimeException("Failed to rename room: ${parsed.error}")
            }
        } catch (groovy.json.JsonException ignored) {}
    }

    // Verify rename
    def updatedRooms = getRooms()
    def updatedRoom = updatedRooms?.find { it.id?.toString() == roomId.toString() }
    if (!updatedRoom || updatedRoom.name != newName) {
        throw new RuntimeException("Rename endpoint returned success but room name did not change")
    }

    mcpLog("info", "room", "Renamed room '${oldName}' -> '${newName}' (ID: ${roomId})")
    return [
        success: true,
        room: [id: roomId.toString(), name: newName, previousName: oldName],
        message: "Room renamed from '${oldName}' to '${newName}'."
    ]
}

// ==================== INSTALLED APPS & RULE MACHINE INTEGRATION ====================

/**
 * List all installed apps on the hub (built-in + user), flattened with parent/child relationships.
 * Backed by /hub2/appsList -- returns the same flattened app list the Hubitat web UI renders on
 * the Apps page. Includes built-in and user apps, parent/child hierarchy, disabled state, and
 * installed-package metadata.
 */
def toolListInstalledApps(args) {
    requireBuiltinAppRead()
    def filter = args?.filter ?: "all"
    def includeHidden = args?.includeHidden == true

    def validFilters = ["all", "builtin", "user", "disabled", "parents", "children"]
    if (!validFilters.contains(filter)) {
        throw new IllegalArgumentException("Invalid filter '${filter}'. Must be one of: ${validFilters.join(', ')}")
    }

    try {
        def responseText = hubInternalGet("/hub2/appsList")
        if (!responseText) {
            return [success: false, error: "Empty response from /hub2/appsList", note: "Hub internal API may be transiently unavailable."]
        }
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseErr) {
            return [success: false, error: "Failed to parse /hub2/appsList response: ${parseErr.message}", note: "Hubitat firmware may have changed the endpoint format."]
        }
        def apps = parsed?.apps ?: []

        // Flatten tree to list with parentId.
        // If a parent is hidden and excluded from the output, its children are promoted to the
        // nearest visible ancestor (or null at root) so their parentId always references an
        // app actually present in the results — no orphan references.
        // NOTE: closure parameter is 'node' (not 'app') to avoid shadowing the Hubitat SDK's
        // `app` reference. Hubitat's `app` is used elsewhere for app.label, app.id, etc.
        def flat = []
        def recurse
        recurse = { Map node, parentId ->
            def d = node?.data ?: [:]
            def isHidden = d.hidden == true
            def included = includeHidden || !isHidden
            if (included) {
                flat << [
                    id: d.id,
                    name: d.name,
                    type: d.type,
                    disabled: d.disabled == true,
                    user: d.user == true,
                    hidden: isHidden,
                    parentId: parentId,
                    hasChildren: (node?.children?.size() ?: 0) > 0,
                    childCount: node?.children?.size() ?: 0
                ]
            }
            def childParentId = included ? d.id : parentId
            node?.children?.each { c -> recurse(c, childParentId) }
        }
        apps.each { a -> recurse(a, null) }

        def filtered = flat.findAll { entry ->
            switch (filter) {
                case "all": return true
                case "builtin": return !entry.user
                case "user": return entry.user
                case "disabled": return entry.disabled
                case "parents": return entry.hasChildren
                case "children": return entry.parentId != null
                default:
                    // Whitelist-validated above; reaching here means validFilters and this
                    // switch have drifted. Throw rather than return an over-broad result,
                    // so the mismatch is visible instead of silently shipping stale data.
                    throw new IllegalStateException("filter branch missing for '${filter}' -- validFilters and switch have drifted")
            }
        }

        return [
            apps: filtered,
            count: filtered.size(),
            filter: filter,
            totalOnHub: flat.size()
        ]
    } catch (Exception e) {
        // The Built-in-App-Tools gate has already passed by the time we reach here, so
        // do not blame it in the note. Failures at this point come from the hub transport
        // (network blip, firmware change to /hub2/appsList) or the flatten/filter pipeline
        // (unexpected shape in a child entry). The error message itself will usually
        // distinguish the two.
        mcpLog("error", "installed-apps", "list_installed_apps failed: ${e.message}")
        return [success: false, error: "Failed to list installed apps: ${e.message}", note: "Hub transport error or unexpected /hub2/appsList response shape -- retry; if persistent, inspect the raw response for firmware-side drift."]
    }
}

/**
 * List all apps that reference a specific device.
 * Backed by /device/fullJson/<id> which exposes appsUsing — the same data the Hubitat device page shows.
 */
def toolGetDeviceInUseBy(args) {
    requireBuiltinAppRead()
    if (!args?.deviceId) throw new IllegalArgumentException("deviceId is required")
    def deviceId = args.deviceId.toString().trim()
    // /device/fullJson/<id> returns a parseable non-empty body even for unknown ids
    // (empty appsUsing, null fields), which would read as "device has no apps" rather
    // than "device doesn't exist". Validate up front against the hub's device registry
    // so callers get an explicit Device-not-found error. Mirrors toolGetHubLogs.
    if (!findDevice(deviceId)) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (!responseText) {
            return [success: false, error: "Empty response from /device/fullJson/${deviceId}", note: "Device ID may not exist."]
        }
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseErr) {
            return [success: false, error: "Failed to parse device JSON: ${parseErr.message}", note: "Device ID '${deviceId}' may not exist, or firmware changed the endpoint format."]
        }

        def appsUsing = parsed?.appsUsing ?: []
        def count
        try {
            count = (parsed?.appsUsingCount != null) ? (parsed.appsUsingCount as Integer) : appsUsing.size()
        } catch (NumberFormatException ne) {
            // A non-numeric appsUsingCount is a firmware/paging signal worth recording
            // (e.g. hub returns "42+" for truncated counts); fall back to the list size
            // so the caller still gets a usable count.
            mcpLog("warn", "installed-apps", "get_device_in_use_by: non-numeric appsUsingCount '${parsed?.appsUsingCount}' for device ${deviceId}; using appsUsing.size()=${appsUsing.size()}")
            count = appsUsing.size()
        }

        return [
            deviceId: deviceId,
            // extraBreadcrumb is the canonical UI breadcrumb label; fall back to .name or
            // .label if a future firmware drops that field so callers still get a usable
            // device-name string instead of silent null.
            deviceName: parsed?.extraBreadcrumb ?: parsed?.name ?: parsed?.label,
            appsUsing: appsUsing.collect { a ->
                [
                    id: a?.id,
                    name: a?.name,         // app type name (e.g. "Room Lights", "Rule-5.1")
                    label: a?.label,       // user-visible label, may include HTML decoration
                    trueLabel: a?.trueLabel,  // label stripped of HTML (null if same as label)
                    disabled: a?.disabled == true
                ]
            },
            count: count,
            parentApp: parsed?.parentApp
        ]
    } catch (Exception e) {
        mcpLog("error", "installed-apps", "get_device_in_use_by failed for device ${deviceId}: ${e.message}")
        return [success: false, error: "Failed: ${e.message}", note: "Verify the device ID is valid and Built-in App Tools is enabled."]
    }
}

/**
 * List all Rule Machine rules via the official hubitat.helper.RMUtils API.
 * Combines RM 4.x and RM 5.x rules (deduplicated by id).
 *
 * RMUtils is an optional platform class -- absent on hubs that have never
 * installed Rule Machine. Absence manifests as NoClassDefFoundError or
 * ClassNotFoundException (both Error subclasses, uncaught by catch Exception).
 * Hence the catch Throwable + null-guarded .message across the two try blocks
 * below; the classifier further down decides which Throwables to surface vs
 * treat as a quiet "RM not installed".
 */
def toolListRmRules(args) {
    requireBuiltinAppRead()
    def combined = [:]
    def v4Error = null
    def v5Error = null

    try {
        def rules4 = hubitat.helper.RMUtils.getRuleList() ?: []
        rules4.each { r -> registerRmRule(combined, r, "4.x") }
    } catch (Throwable e) {
        v4Error = e.toString()
    }

    try {
        def rules5 = hubitat.helper.RMUtils.getRuleList("5.0") ?: []
        rules5.each { r -> registerRmRule(combined, r, "5.x") }
    } catch (Throwable e) {
        v5Error = e.toString()
    }

    def rules = combined.values().sort { it.label ?: "" }

    def result = [
        rules: rules,
        count: rules.size()
    ]
    // Classify the failures. A "missing class" error (RM not installed) is quiet whether
    // the other version succeeded OR both versions failed the same way (both-absent path).
    // A non-missing-class error (e.g. timeout, internal platform issue) is always surfaced
    // so operators can investigate rather than get silent empty results.
    // Narrow to class-resolution failures only. Earlier versions treated any
    // MissingMethodException / "No signature of method" / unqualified "Cannot get property"
    // as quiet-missing, but those substrings appear in unrelated Groovy runtime errors
    // (e.g. a shape change in RMUtils' return value would surface as MME against
    // registerRmRule and get swept under the rug). Scope each substring tightly:
    //   - NoClassDefFoundError / ClassNotFoundException / "unable to resolve class":
    //     JVM-level class-resolution errors; unambiguous "RM not installed" signal.
    //   - "Cannot get property 'helper'": the Hubitat sandbox returns null for
    //     unresolved `hubitat.helper.X` namespace lookups; scoping to 'helper'
    //     limits the match to the hubitat.helper.RMUtils path specifically.
    //   - MissingMethodException together with getRuleList: covers the old-firmware
    //     case where RMUtils exists but lacks the getRuleList("5.0") overload —
    //     unrelated MMEs no longer get swallowed.
    def classMissingHint = { String msg ->
        if (!msg) return false
        if (msg.contains("NoClassDefFoundError") ||
            msg.contains("ClassNotFoundException") ||
            msg.contains("unable to resolve class")) return true
        if (msg.contains("Cannot get property") && msg.contains("'helper'")) return true
        if ((msg.contains("MissingMethodException") || msg.contains("No signature of method"))
            && msg.contains("getRuleList")) return true
        return false
    }
    def hardErrors = []
    if (v4Error && !classMissingHint(v4Error)) hardErrors << "v4=${v4Error}"
    if (v5Error && !classMissingHint(v5Error)) hardErrors << "v5=${v5Error}"

    def bothClassMissing = v4Error && v5Error &&
                           classMissingHint(v4Error) && classMissingHint(v5Error)

    if (rules.isEmpty() && bothClassMissing) {
        // Both RMUtils lookups failed with class-resolution errors. That's the
        // expected shape on a hub that has never installed Rule Machine; it's
        // not an error the caller needs to react to. Return an empty list with
        // an informational note and leave success unset so the result still
        // reads as a normal empty response.
        result.note = "Rule Machine not detected on this hub."
    } else if (rules.isEmpty() && v4Error && v5Error) {
        // Both calls failed but at least one with a non-missing-class shape --
        // unusual enough that operators should see the raw error.
        result.success = false
        result.error = "RMUtils calls failed: v4=${v4Error} v5=${v5Error}"
        result.note = "Rule Machine may not be installed on this hub."
    } else if (hardErrors) {
        // One version succeeded but the other had a non-missing-class error — surface it
        // as a warning without blocking the successful results.
        result.warning = "Partial RMUtils failure (results from the other version shown): ${hardErrors.join('; ')}"
    }
    return result
}

/**
 * Normalize a single RMUtils rule entry into a consistent map and register under combined[id].
 *
 * RMUtils.getRuleList() returns list entries in shapes that vary by RM version:
 *   - RM 5.x: single-entry Map `[<id>: "<label>"]` -- the key is the rule ID, the value is the label.
 *     The key may arrive as String or Integer depending on how the hub built the Map.
 *   - RM 4.x: Map with explicit fields `[id: <id>, label: "<label>", name: "...", type: "..."]`.
 *   - Raw primitive (int or String ID): defensive fallback for shapes not covered above.
 */
private void registerRmRule(Map combined, def r, String version) {
    def id
    def label
    def name
    def type
    if (r instanceof Map) {
        if (r.containsKey("id")) {
            // RM 4.x explicit-fields shape
            id = r.id
            label = r.label ?: r.name
            name = r.name ?: r.label
            type = r.type
        } else if (r.size() == 1) {
            // RM 5.x single-entry shape: [<id>: "<label>"]
            def entry = r.entrySet().iterator().next()
            // entry.key may come back as String or Integer depending on how Hubitat built
            // the Map; coerce to Integer (via string) so downstream consumers get the
            // Integer id advertised in list_rm_rules' response schema.
            def rawKey = entry.key
            try {
                id = (rawKey instanceof Number) ? rawKey.toInteger() : rawKey.toString().toInteger()
            } catch (NumberFormatException coerceErr) {
                // Returning the raw key would silently violate the tool's
                // ruleId: integer schema (callers would see a String id in the
                // response and fail to dispatch run_rm_rule against it). Skip
                // the entry and surface the anomaly via logs instead.
                def keyType = rawKey instanceof Number ? 'Number' : rawKey instanceof String ? 'String' : 'other'
                mcpLog("warn", "rm-interop", "registerRmRule: non-integer ruleId '${rawKey}' (type=${keyType}) in RM ${version} list -- skipping entry")
                return
            }
            label = entry.value?.toString()
            name = label
        }
    } else if (r != null) {
        // Raw ID fallback
        id = r
    }
    if (id == null) return
    def key = id.toString()
    if (!combined.containsKey(key)) {
        combined[key] = [
            id: id,
            label: label,
            name: name,
            type: type,
            rmVersion: version
        ]
    }
}

/**
 * Trigger a Rule Machine rule via RMUtils.sendAction().
 * Not destructive — invokes existing user-configured automation.
 */
def toolRunRmRule(args) {
    requireBuiltinAppRead()
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    def ruleId = normalizeRuleId(args.ruleId)
    def action = args?.action ?: "rule"

    def rmAction
    switch (action) {
        case "rule": rmAction = "runRule"; break
        case "actions": rmAction = "runRuleAct"; break
        case "stop": rmAction = "stopRuleAct"; break
        default: throw new IllegalArgumentException("Invalid action '${action}'. Must be 'rule', 'actions', or 'stop'.")
    }

    return sendRmAction(ruleId, rmAction, "run_rm_rule action=${action}")
}

/**
 * Pause a Rule Machine rule via RMUtils.sendAction(pauseRule).
 * Idempotent on the hub side: pausing an already-paused rule is a no-op.
 */
def toolPauseRmRule(args) {
    requireBuiltinAppRead()
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    return sendRmAction(normalizeRuleId(args.ruleId), "pauseRule", "pause_rm_rule")
}

/**
 * Resume a previously paused Rule Machine rule via RMUtils.sendAction(resumeRule).
 * Idempotent on the hub side: resuming an already-active rule is a no-op.
 */
def toolResumeRmRule(args) {
    requireBuiltinAppRead()
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    return sendRmAction(normalizeRuleId(args.ruleId), "resumeRule", "resume_rm_rule")
}

/**
 * Set a Rule Machine rule's private boolean (used by its conditions) via
 * RMUtils.sendAction(setRuleBooleanTrue|setRuleBooleanFalse).
 *
 * Strict-coercion policy: accepts ONLY Boolean true/false or the canonical
 * lowercase strings "true"/"false". Other truthy-looking values — 1, 0,
 * "True", "TRUE", "yes", "no", "on", "off" — are rejected with
 * IllegalArgumentException. Reason: silently coercing "yes" to true (or 1
 * to true) risks the AI sending the wrong boolean when a rule's behaviour
 * depends on the boolean (e.g. arming a security path). The ambiguity is
 * worse than the friction of requiring explicit true/false.
 */
def toolSetRmRuleBoolean(args) {
    requireBuiltinAppRead()
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.value == null) throw new IllegalArgumentException("value (boolean) is required")
    // Accept Boolean or the canonical lowercase strings 'true'/'false' only. Reject other
    // truthy-looking values (1, 'yes', 'True') to avoid silently setting the wrong boolean.
    Boolean resolved = null
    if (args.value instanceof Boolean) {
        resolved = args.value
    } else if (args.value == "true") {
        resolved = true
    } else if (args.value == "false") {
        resolved = false
    } else {
        throw new IllegalArgumentException("value must be boolean true/false (or the string 'true'/'false'), got: ${args.value}")
    }
    def rmAction = resolved ? "setRuleBooleanTrue" : "setRuleBooleanFalse"
    return sendRmAction(normalizeRuleId(args.ruleId), rmAction, "set_rm_rule_boolean value=${resolved}")
}

/**
 * Shared sendAction wrapper. Returns a consistent success/error result map.
 *
 * Invariant: the 4-arg form `sendAction([ids], action, appLabel, "5.0")` dispatches
 * to the RM 5.x handler and is the canonical call for both RM 4.x and RM 5.x rules.
 * The 3-arg form `sendAction([ids], action, appLabel)` reaches only RM 4.x and is
 * used as a fallback for very old firmware that predates the version-discriminator
 * overload (see sendRmActionFallback). Background on the RM API shape:
 * https://community.hubitat.com/t/rule-machine-api/7104
 */
private Map sendRmAction(Integer ruleId, String rmAction, String logContext) {
    def appLabel = app?.label ?: "MCP Rule Server"
    try {
        hubitat.helper.RMUtils.sendAction([ruleId], rmAction, appLabel, "5.0")
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule ${ruleId}")
        return [success: true, ruleId: ruleId, rmAction: rmAction]
    } catch (MissingMethodException e) {
        return sendRmActionFallback(ruleId, rmAction, appLabel, logContext, e)
    } catch (NoSuchMethodError e) {
        return sendRmActionFallback(ruleId, rmAction, appLabel, logContext, e)
    } catch (Throwable e) {
        // Everything else — NoClassDefFoundError (RM not installed), timeouts, NPEs
        // inside RM internals, invalid ruleId — propagates through a single error
        // response without a second attempt. Retrying a transient failure would
        // double-fire the action if the first call partially succeeded.
        def m = e.message ?: e.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule ${ruleId}: ${m}")
        return [success: false, error: "RMUtils.sendAction failed: ${m}", note: "Verify the ruleId is valid (use list_rm_rules) and Rule Machine is installed."]
    }
}

/**
 * 3-arg fallback invoked only when the 4-arg sendAction raised a signature-mismatch
 * shape (MissingMethodException / NoSuchMethodError) — very old firmware that
 * predates the version-discriminator overload. Any other Throwable from the 4-arg
 * call propagates directly in sendRmAction above.
 */
private Map sendRmActionFallback(Integer ruleId, String rmAction, String appLabel, String logContext, Throwable original) {
    try {
        hubitat.helper.RMUtils.sendAction([ruleId], rmAction, appLabel)
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule ${ruleId} (3-arg fallback)")
        return [success: true, ruleId: ruleId, rmAction: rmAction, fallback: "3-arg"]
    } catch (Throwable e2) {
        def m1 = original.message ?: original.toString()
        def m2 = e2.message ?: e2.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule ${ruleId}: 4-arg=${m1}, 3-arg=${m2}")
        return [success: false, error: "RMUtils.sendAction failed: ${m2}", note: "4-arg attempt also failed: ${m1}. Verify the ruleId is valid (use list_rm_rules) and Rule Machine is installed."]
    }
}

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

// ==================== VERSION UPDATE CHECK ====================

def currentVersion() {
    return "0.11.0"
}

def isNewerVersion(String remote, String local) {
    // Strict semver only. Non-numeric or suffixed versions (e.g., "0.10.0-rc1",
    // "v0.10.0", whitespace) would otherwise throw NumberFormatException inside
    // the tokenize/collect below — caught but silently returning false, which
    // means users stop getting update prompts without knowing why.
    def semverPattern = ~/^\d+\.\d+\.\d+$/
    if (!(remote ==~ semverPattern)) {
        mcpLog("warn", "server", "Remote version not strict semver: '${remote}' — skipping comparison")
        return false
    }
    if (!(local ==~ semverPattern)) {
        mcpLog("warn", "server", "Local version not strict semver: '${local}' — skipping comparison")
        return false
    }
    try {
        def remoteParts = remote.tokenize('.').collect { it as int }
        def localParts = local.tokenize('.').collect { it as int }
        def maxLen = Math.max(remoteParts.size(), localParts.size())
        for (int i = 0; i < maxLen; i++) {
            def r = i < remoteParts.size() ? remoteParts[i] : 0
            def l = i < localParts.size() ? localParts[i] : 0
            if (r > l) return true
            if (r < l) return false
        }
        return false
    } catch (Exception e) {
        mcpLog("warn", "server", "Version comparison failed: ${e.message}")
        return false
    }
}

def checkForUpdate() {
    try {
        // Skip if checked within last 24 hours (unless forced)
        if (state.updateCheck?.checkedAt) {
            def msSinceCheck = now() - state.updateCheck.checkedAt
            if (msSinceCheck < 24 * 60 * 60 * 1000) {
                def hoursSinceCheck = (int)(msSinceCheck / (1000 * 60 * 60))
                logDebug("Version check skipped - last checked ${hoursSinceCheck} hours ago")
                return
            }
        }
        doUpdateCheck()
    } catch (Exception e) {
        mcpLog("warn", "server", "Version update check failed: ${e.message}")
    }
}

def doUpdateCheck() {
    try {
        def params = [
            uri: "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/packageManifest.json",
            contentType: "application/json",
            timeout: 30
        ]
        asynchttpGet("handleUpdateCheckResponse", params)
    } catch (Exception e) {
        mcpLog("warn", "server", "Failed to initiate version check: ${e.message}")
    }
}

def handleUpdateCheckResponse(resp, data) {
    try {
        if (resp.status != 200) {
            mcpLog("warn", "server", "Version check HTTP error: ${resp.status}")
            return
        }
        def json = new groovy.json.JsonSlurper().parseText(resp.data)
        def latestVersion = json.version
        if (!latestVersion) {
            mcpLog("warn", "server", "Version check: no version field in response")
            return
        }
        def installed = currentVersion()
        def updateAvailable = isNewerVersion(latestVersion, installed)
        state.updateCheck = [
            latestVersion: latestVersion,
            checkedAt: now(),
            updateAvailable: updateAvailable
        ]
        if (updateAvailable) {
            log.info "MCP Rule Server update available: v${latestVersion} (installed: v${installed})"
        } else {
            logDebug("MCP Rule Server is up to date (v${installed})")
        }
    } catch (Exception e) {
        mcpLog("warn", "server", "Version check response parsing failed: ${e.message}")
    }
}

def toolCheckForUpdate(args) {
    try {
        // Force check by clearing the checkedAt timestamp
        if (state.updateCheck) {
            state.updateCheck.checkedAt = null
        }
        doUpdateCheck()
        // Return current state (async call may not have completed yet)
        def installed = currentVersion()
        def updateInfo = state.updateCheck ?: [:]
        return [
            success: true,
            installedVersion: installed,
            latestVersion: updateInfo.latestVersion ?: "unknown (check in progress)",
            updateAvailable: updateInfo.updateAvailable ?: false,
            lastChecked: updateInfo.checkedAt ? formatTimestamp(updateInfo.checkedAt) : "checking now",
            note: "Version check is asynchronous. If latestVersion shows 'unknown', call this tool again in a few seconds to see the result."
        ]
    } catch (Exception e) {
        return [
            success: false,
            error: "Version check failed: ${e.message}",
            installedVersion: currentVersion()
        ]
    }
}

// ==================== TOOL SEARCH (BM25) ====================

def toolSearchTools(args) {
    def query = args.query
    if (!query?.trim()) return [error: "query is required"]
    def maxResults = args.maxResults != null ? Math.max(0, args.maxResults as Integer) : 5

    // Build searchable corpus (cached in state for performance on resource-constrained hub)
    def corpus = state.toolSearchCorpus
    if (!corpus) {
        corpus = buildToolSearchCorpus()
        state.toolSearchCorpus = corpus
    }

    // Tokenize all documents and the query
    def docTokens = corpus.collect { bm25Tokenize("${it.name} ${it.description} ${it.params ?: ''} ${it.hints ?: ''}") }
    def queryTokens = bm25Tokenize(query)

    if (!queryTokens) return [results: [], message: "No searchable terms in query"]

    // BM25 scoring
    def scores = bm25Score(docTokens, queryTokens)

    // Rank and return top results
    def ranked = []
    scores.eachWithIndex { score, idx ->
        if (score > 0) ranked << [index: idx, score: score]
    }
    ranked.sort { -it.score }
    if (ranked.size() > maxResults) ranked = ranked.take(maxResults)

    def results = ranked.collect { r ->
        def tool = corpus[r.index]
        def entry = [
            tool: tool.name,
            description: tool.description,
            relevance: Math.round(r.score * 100) / 100.0
        ]
        if (tool.gateway) {
            entry.gateway = tool.gateway
            entry.callAs = "Call via ${tool.gateway}(tool=\"${tool.name}\", args={...})"
        } else {
            entry.callAs = "Call directly: ${tool.name}({...})"
        }
        return entry
    }

    return [
        query: query,
        resultsCount: results.size(),
        totalToolsSearched: corpus.size(),
        results: results
    ]
}

// Build a flat list of all tools (core + proxied) with gateway attribution
private buildToolSearchCorpus() {
    def gatewayConfig = getGatewayConfig()
    def proxiedNames = gatewayConfig.values().collectMany { it.tools } as Set
    def allDefs = getAllToolDefinitions()
    def allDefsMap = allDefs.collectEntries { [(it.name): it] }

    def corpus = []

    // Core tools (not behind a gateway)
    allDefs.each { toolDef ->
        if (!proxiedNames.contains(toolDef.name)) {
            def params = toolDef.inputSchema?.properties?.keySet()?.join(" ") ?: ""
            corpus << [name: toolDef.name, description: toolDef.description?.replaceAll(/\n+/, ' ')?.trim(), params: params, gateway: null]
        }
    }

    // Gateway sub-tools (with search hints for synonym matching)
    gatewayConfig.each { gwName, config ->
        config.tools.each { toolName ->
            def summary = config.summaries[toolName] ?: ""
            def hints = config.searchHints?."${toolName}" ?: ""
            def fullDef = allDefsMap[toolName]
            def params = fullDef?.inputSchema?.properties?.keySet()?.join(" ") ?: ""
            corpus << [name: toolName, description: "${summary} [${config.description}]", params: params, hints: hints, gateway: gwName]
        }
    }

    return corpus
}

// BM25 tokenizer: lowercase, split on non-alphanumeric, drop tokens < 2 chars
private bm25Tokenize(String text) {
    if (!text) return []
    return text.toLowerCase().split(/[^a-z0-9]+/).findAll { it.length() > 1 }
}

// BM25 Okapi scoring
private bm25Score(List<List<String>> docTokens, List<String> queryTokens) {
    def k1 = 1.5
    def b = 0.75
    def n = docTokens.size()

    if (n == 0) return []

    // Document lengths and average
    def docLengths = docTokens.collect { it.size() }
    def avgDl = docLengths.sum() / (double) n
    if (avgDl == 0) return new double[n] as List

    // Document frequency: how many docs contain each token
    def df = [:]
    docTokens.each { tokens ->
        tokens.toSet().each { token ->
            df[token] = (df[token] ?: 0) + 1
        }
    }

    // Score each document
    def scores = new double[n]
    docTokens.eachWithIndex { tokens, docIdx ->
        // Term frequency for this doc
        def tf = [:]
        tokens.each { t -> tf[t] = (tf[t] ?: 0) + 1 }

        def dl = docLengths[docIdx]
        double score = 0.0

        queryTokens.each { qt ->
            def termFreq = tf[qt] ?: 0
            if (termFreq > 0) {
                def docFreq = df[qt] ?: 0
                def idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0)
                def num = termFreq * (k1 + 1)
                def den = termFreq + k1 * (1 - b + b * dl / avgDl)
                score += idf * num / den
            }
        }

        scores[docIdx] = score
    }

    return scores as List
}

// ==================== TOOL GUIDE ====================

def toolGetToolGuide(section) {
    def sections = getToolGuideSections()

    if (section) {
        def key = section.toLowerCase().replaceAll(/[^a-z_]/, "_")
        if (sections.containsKey(key)) {
            return [
                success: true,
                section: key,
                content: sections[key]
            ]
        } else {
            return [
                success: false,
                error: "Unknown section: ${section}",
                availableSections: sections.keySet().toList()
            ]
        }
    }

    // Return full guide
    def fullGuide = sections.collect { k, v -> v }.join("\n\n---\n\n")
    return [
        success: true,
        section: "full",
        availableSections: sections.keySet().toList(),
        content: fullGuide
    ]
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
- If a tool fails (e.g., manage_virtual_device returns an error), report the failure to the user
- Do NOT silently fall back to using existing devices as a workaround
- Example: If creating a virtual device fails, don't just grab an existing device to use instead

**Why this matters:**
- Wrong device could control critical systems (HVAC, locks, security)
- User trust depends on AI only controlling what they explicitly authorized''',

        hub_admin_write: '''## Hub Admin Write Tools - Pre-Flight Checklist

All Hub Admin Write tools require these steps:
1. Backup check: Ensure create_hub_backup was called within the last 24 hours
2. Inform user: Tell them what you're about to do
3. Get confirmation: Wait for explicit "yes", "confirm", or "proceed"
4. Set confirm=true: Pass the confirm parameter

### Tool-Specific Requirements

**reboot_hub** - 1-3 min downtime, all automations stop, scheduled jobs lost, radios restart. Only when user explicitly requests.

**shutdown_hub** - Powers OFF completely, requires physical restart. NOT a reboot. Only when user explicitly requests.

**zwave_repair** - 5-30 min duration, Z-Wave devices may be unresponsive. Best during off-peak hours.

**delete_device** - MOST DESTRUCTIVE, NO UNDO. For ghost/orphaned devices, stale DB records, stuck virtual devices.
- Use get_device to verify correct device
- Warn if recent activity or Z-Wave/Zigbee (do exclusion first)
- All details logged to MCP debug logs for audit

**delete_room** - Devices become unassigned (not deleted). List affected devices first.

**delete_app/delete_driver** - Remove app instances via Hubitat UI first (apps). Change devices to different driver first (drivers). Auto-backs up before deletion.''',

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

MCP-managed virtual devices:
- Auto-accessible to all MCP tools without manual selection
- Appear in Hubitat UI for Maker API, Dashboard, Rule Machine
- Use manage_virtual_device(action="delete") to remove (not delete_device)''',

        update_device: '''## update_device Properties

| Property | Requires Hub Admin Write |
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
- variable_math: Arithmetic on variables — {variableName, operation: add|subtract|multiply|divide|modulo|set, operand, scope: local|global}''',

        backup: '''## Backup System

### Hub Backups
- create_hub_backup creates full hub database backup
- Required within 24 hours before any Hub Admin Write operation
- Only write tool that doesn't require a prior backup

### Source Code Backups (Automatic)
- Created when using update_app_code, update_driver_code, delete_app, delete_driver
- Stored in File Manager as .groovy files
- Persist even if MCP uninstalled
- Max 20 kept, oldest pruned
- Rapid edits preserve original (1-hour protection)

### Rule Backups (Automatic)
- delete_rule auto-backs up to File Manager as mcp_rule_backup_<name>_<timestamp>.json
- Restore via: read_file → import_rule
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

**list_devices:**
- Use detailed=false for initial discovery
- With detailed=true, paginate: 20-30 devices per request
- Make tool calls sequentially, not in parallel

**get_device_events:**
- Default limit 10, recommended max 50
- Higher values (100+) may cause delays

**get_hub_logs:**
- Default 100 entries, max 500
- Use level and source filters to narrow results

**get_device_history:**
- Up to 7 days of history
- Use attribute filter to reduce data volume''',

        builtin_app_tools: '''## Built-in App Tools

Tools in the manage_installed_apps and manage_rule_machine gateways have mixed gate requirements. list_installed_apps and get_device_in_use_by require the "Enable Built-in App Tools" toggle (requireBuiltinAppRead). get_app_config and list_app_pages require Hub Admin Read (requireHubAdminRead). manage_rule_machine tools require the "Enable Built-in App Tools" toggle. If the user sees "Built-in App Tools are disabled" errors, direct them to the MCP Rule Server app settings page.

**manage_installed_apps (4 tools):**

- **list_installed_apps** — enumerate ALL apps on the hub (built-in + user) with parent/child tree
  - filter="all" (default) | "builtin" | "user" | "disabled" | "parents" | "children"
  - Each entry: id, name, type, disabled, user, hidden, parentId, hasChildren, childCount
  - Built-in apps have user=false (Rule Machine, Room Lighting, Groups and Scenes, Mode Manager, HSM, Dashboards, Maker API, etc.)
  - User apps have user=true (Awair, Ecobee, HPM, etc.)
  - Parent/child tree is flattened with parentId pointers. Hidden parents are excluded from output but their children are promoted to the nearest visible ancestor.

- **get_device_in_use_by** — find apps that reference a specific device
  - Use BEFORE deleting a device, disabling a device, or troubleshooting unexpected behavior
  - Returns appsUsing array with each app's id, name (type like "Room Lights" or "Rule-5.1"), label (user-visible), trueLabel (HTML-stripped), disabled
  - Answers "if I delete this device, which automations break?"

- **get_app_config** — read an installed app's configuration page (Hub Admin Read required)
  - Returns app identity (label, type, disabled), config page sections/inputs/values, and child apps
  - Multi-page apps expose sub-pages via pageName. For HPM: use pageName="prefPkgUninstall" for the FULL installed-package list; pageName="prefPkgModify" returns only the subset with optional components; pageName="prefOptions" is the main-menu navigation (no package data). RM 5.x and Room Lighting use a single mainPage (no pageName needed). Call list_app_pages first to discover available page names for any multi-page app.
  - includeSettings=true adds the raw internal settings map (large apps: 500-1000 keys with app-specific encoding)
  - Workflow: list_installed_apps (or list_rm_rules for RM rules specifically -- note that list_rules / get_rule handle only MCP-native rules, not Hubitat's built-in Rule Machine) to find appId, then get_app_config to inspect. For multi-page apps, consider list_app_pages first.

- **list_app_pages** — discover what pageNames a given app accepts (Hub Admin Read required)
  - Input: appId
  - Returns curated page directory for known app types (HPM, RM 5.x, Room Lighting, Mode Manager) plus an introspected primary page for unknown app types
  - Cuts the page-name guessing cycle for multi-page apps. Especially useful for HPM which exposes multiple sub-pages (prefPkgUninstall / prefPkgModify / prefPkgInstall / prefPkgMatchUp) for different operations.

**manage_rule_machine (5 tools) — read + trigger existing RM rules only, NO create/modify/delete:**

- **list_rm_rules** — enumerate Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id)

- **run_rm_rule** — trigger an existing RM rule via RMUtils.sendAction
  - action="rule" (default, full evaluation): runs triggers + conditions + actions as if rule fired
  - action="actions": runs only the actions, bypassing conditions (useful for manual override)
  - action="stop": stops running actions (cancels in-flight delays)

- **pause_rm_rule** / **resume_rm_rule** — reversible toggle; paused rules don't fire on triggers

- **set_rm_rule_boolean** — set an RM rule's private boolean (true or false only; strings must be lowercase "true"/"false"). RM rules can use Private Boolean in conditions — this lets MCP flip that flag from outside.

**CRITICAL LIMITATION: Cannot create, modify, or delete RM rules or Room Lighting instances.** Hubitat's platform blocks third-party apps from instantiating hubitat:Rule-5.1 or hubitat:RoomLights as children (parent-type validation on addChildApp). If the user asks to "create a new RM rule" or "set up a new Room Lighting", respond:
  1. Explain this is not possible via MCP (platform limitation, not a missing feature)
  2. Offer the alternative: create an equivalent rule using MCP's own rule engine via create_rule
  3. Or direct them to the native Rule Machine / Room Lighting UI for configuration

Do NOT invent fake tools like "create_rm_rule" or pretend to call one — this is the most important safety rule for these tools.'''
    ]
}
