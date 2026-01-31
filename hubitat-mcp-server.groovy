/**
 * MCP Rule Server for Hubitat
 *
 * A native MCP (Model Context Protocol) server that runs directly on Hubitat
 * with a built-in custom rule engine for creating automations via Claude.
 *
 * Version: 0.4.1 - Bug fixes for Hub Admin tools (source retrieval, backup creation)
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
                paragraph "<b>Version:</b> 0.4.1"
                if (state.updateCheck?.updateAvailable) {
                    paragraph "<b style='color: orange;'>&#9888; Update available: v${state.updateCheck.latestVersion}</b> (you have v0.4.1). Update via <a href='https://github.com/kingpanther13/Hubitat-local-MCP-server' target='_blank'>GitHub</a> or Hubitat Package Manager."
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
            def date = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", timestamp)
            return date.format("yyyy-MM-dd HH:mm:ss")
        }
        return "Unknown"
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
    logDebug("MCP Response: ${jsonResponse}")
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
        log.error "MCP Error: ${e.message}", e
        return jsonRpcError(msg.id, -32603, "Internal error: ${e.message}")
    }
}

def handleNotification(msg) {
    logDebug("MCP Notification: ${msg.method}")
}

def handleInitialize(msg) {
    def info = [
        name: "hubitat-mcp-rule-server",
        version: "0.4.1"
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
        log.error "Tool execution error: ${e.message}", e
        return jsonRpcError(msg.id, -32603, "Tool error: ${e.message}")
    }
}

def getToolDefinitions() {
    return [
        // Device Tools
        [
            name: "list_devices",
            description: """List all devices available to MCP with their current states. IMPORTANT: When user requests a device by name, verify it exists in this list by exact label match. Do NOT guess or assume device mappings - if a requested device is not found, report 'device not found' rather than substituting a similar device.

PERFORMANCE WARNING:
- Use detailed=false for initial device discovery (returns only common attributes)
- With detailed=true, use pagination: 20-30 devices per request is recommended
- Queries with 100+ devices and detailed=true can cause temporary hub slowdown
- For large device lists, make multiple paginated requests rather than one large request
- IMPORTANT: Make MCP tool calls sequentially, not in parallel - concurrent requests may cause issues""",
            inputSchema: [
                type: "object",
                properties: [
                    detailed: [type: "boolean", description: "Include full device details (capabilities, all attributes, commands). WARNING: Resource-intensive for large device counts. Use with pagination (limit parameter) for best performance."],
                    offset: [type: "integer", description: "Start from device at this index (0-based). Use for pagination.", default: 0],
                    limit: [type: "integer", description: "Maximum number of devices to return. Recommended: 20-30 for detailed=true, higher values may slow hub.", default: 0]
                ]
            ]
        ],
        [
            name: "get_device",
            description: "Get detailed information about a specific device",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"]
                ],
                required: ["deviceId"]
            ]
        ],
        [
            name: "get_attribute",
            description: "Get a specific attribute value from a device",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    attribute: [type: "string", description: "Attribute name"]
                ],
                required: ["deviceId", "attribute"]
            ]
        ],
        [
            name: "send_command",
            description: "Send a command to a device. Always verify state changed after.",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    command: [type: "string", description: "Command name"],
                    parameters: [type: "array", description: "Command parameters", items: [type: "string"]]
                ],
                required: ["deviceId", "command"]
            ]
        ],
        [
            name: "get_device_events",
            description: """Get recent events for a device.

PERFORMANCE WARNING:
- Default limit is 10 events, which is optimal for most use cases
- Requesting more than 50 events may slow hub response time
- Very high limits (100+) can cause significant delays on busy devices
- For historical analysis, make multiple smaller requests rather than one large request""",
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
            description: """List all custom automation rules.

PERFORMANCE NOTE: Returns summary information for all rules. For hubs with many rules (50+), response time may be slightly longer. Use get_rule for detailed information about specific rules.""",
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
            description: """Create a new automation rule. Rule structure:
{
  "name": "Rule name",
  "description": "Optional description",
  "enabled": true,
  "triggers": [...],
  "conditions": [...],
  "conditionLogic": "all|any",
  "actions": [...]
}

TRIGGERS: device_event (with duration for debouncing; supports multi-device via deviceIds array with matchMode any/all), button_event (pushed/held/doubleTapped), time (HH:mm or sunrise/sunset with offset), periodic (interval-based), mode_change, hsm_change
MULTI-DEVICE TRIGGER: {"type":"device_event","deviceIds":["id1","id2"],"attribute":"switch","value":"on","matchMode":"all"} - triggers when any device changes, optionally requires all to match
TIME TRIGGER EXAMPLES: {"type":"time","time":"08:30"}, {"type":"time","sunrise":true,"offset":30}, {"type":"time","sunset":true,"offset":-15}
  sunrise/sunset offset is in minutes (positive=after, negative=before). Many formats accepted and auto-normalized.
CONDITIONS: device_state, device_was (state for X seconds), time_range (supports sunrise/sunset), mode, variable, days_of_week, sun_position, hsm_status
ACTIONS: device_command, toggle_device, activate_scene, set_variable, set_local_variable, set_mode, set_hsm, delay (with ID for targeted cancel), if_then_else, cancel_delayed, repeat, stop, log, set_thermostat (mode/setpoints/fan), http_request (GET/POST), speak (TTS with optional volume), comment (documentation only), set_valve (open/close), set_fan_speed (low/medium/high/auto), set_shade (open/close/position), variable_math (arithmetic on variables: add/subtract/multiply/divide/modulo/set with variableName, operation, operand, scope local|global)

Always verify rule created correctly after.""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Rule name"],
                    description: [type: "string", description: "Rule description"],
                    enabled: [type: "boolean", description: "Enable rule immediately", default: true],
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
            description: "Update an existing rule. Always verify changes after.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"],
                    name: [type: "string"],
                    description: [type: "string"],
                    enabled: [type: "boolean"],
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
            description: "Delete a rule. Always verify deletion after.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "enable_rule",
            description: "Enable a rule. Always verify enabled after.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "disable_rule",
            description: "Disable a rule. Always verify disabled after.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"]
                ],
                required: ["ruleId"]
            ]
        ],
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
            description: "Get information about the Hubitat hub",
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
            description: "List all captured device states with metadata (stateId, device count, timestamp). The storage limit is configurable in app settings (default: 20). When limit is reached, new captures automatically delete the oldest entry. Check the 'warning' field in response when approaching capacity. Use delete_captured_state or clear_captured_states to free up slots.",
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
            description: """Retrieve debug log entries from the MCP server. These logs are stored in app state and accessible via MCP, unlike Hubitat's built-in logs which require UI access.

Use this to debug rule creation issues, execution failures, and timing problems. Logs include timestamps, severity levels, component info, and optional stack traces for errors.""",
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
            description: """Get comprehensive diagnostic information about a specific rule including:
- Rule metadata and configuration
- Execution history and last trigger time
- Full trigger/condition/action structure
- Recent log entries for this rule
- Error history with stack traces

Use this when a rule isn't working as expected to understand its state and recent activity.""",
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
            description: """Generate a formatted bug report for submitting issues to the GitHub repository.

This tool gathers diagnostic information and formats it into a complete bug report. Use this when:
- Something isn't working as expected
- You encounter an error
- A rule or device command behaves unexpectedly

The tool will:
1. Collect system info (version, hub details, log level)
2. Gather recent error logs
3. Include the description of what should have happened vs what actually happened
4. Format everything into a GitHub-ready issue report
5. Provide a link to submit the issue

After generating, provide the report to the user so they can submit it.""",
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
            description: """Import a rule from exported JSON data. Provide the full export object from export_rule.

Optionally provide a deviceMapping to remap old device IDs to new ones (useful when importing to a different hub).
The deviceMapping is an object where keys are old device IDs and values are new device IDs, e.g. {"123": "456", "789": "101"}.""",
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
        [
            name: "get_hub_details",
            description: """Get extended hub information including model, firmware, memory usage, internal temperature, network configuration, and radio details.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
If Hub Security is enabled on the hub, credentials must be configured in app settings.

Returns more detailed information than get_hub_info, including live memory and temperature data from the hub's internal API.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "list_hub_apps",
            description: """List all installed apps on the Hubitat hub, not just MCP-managed rules.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Returns app names, IDs, and types. Uses the hub's internal API endpoint.
Note: Results depend on hub firmware version. Some older firmware may not support this endpoint.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "list_hub_drivers",
            description: """List all installed drivers on the Hubitat hub.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Returns driver names, IDs, and types. Uses the hub's internal API endpoint.
Note: Results depend on hub firmware version. Some older firmware may not support this endpoint.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_zwave_details",
            description: """Get Z-Wave radio information including firmware version, home ID, and device node details.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Uses the hub's internal API to fetch Z-Wave radio status and device table.
Note: Results depend on hub firmware and Z-Wave radio availability.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_zigbee_details",
            description: """Get Zigbee radio information including channel, PAN ID, firmware version, and device details.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Uses the hub's internal API to fetch Zigbee radio status.
Note: Results depend on hub firmware and Zigbee radio availability.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_hub_health",
            description: """Get hub health metrics including free memory, internal temperature, uptime, and database size.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Provides a quick health check snapshot useful for diagnosing performance issues.""",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],

        // ==================== HUB ADMIN WRITE TOOLS ====================
        [
            name: "create_hub_backup",
            description: """Create a backup of the Hubitat hub database.

Requires 'Enable Hub Admin Write Tools' to be turned on in the MCP Rule Server app settings.

NOTE: This is the ONLY Hub Admin Write tool that does NOT require a prior backup (since it IS the backup tool).
However, this tool still requires the confirm parameter to be true and Hub Admin Write to be enabled.

⚠️ IMPORTANT: You MUST call this tool and verify success BEFORE using ANY other Hub Admin Write tool (reboot_hub, shutdown_hub, zwave_repair).
Store the backup timestamp from the response — other write tools will verify a recent backup exists.""",
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
            description: """⚠️⚠️⚠️ CRITICAL WARNING — DESTRUCTIVE OPERATION ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST — You MUST complete ALL steps IN ORDER before calling this tool:
1. Call 'create_hub_backup' and verify the response shows success=true
2. Note the backup timestamp from the response
3. Tell the user: "I have created a hub backup at [timestamp]. I am about to reboot your Hubitat hub."
4. Get EXPLICIT user confirmation to proceed (the user must say yes/confirm/proceed)
5. Set the 'confirm' parameter to true

Reboots the Hubitat hub. Effects:
- Hub will be UNREACHABLE for 1-3 minutes during reboot
- ALL automations, rules, and device communications STOP during reboot
- ALL scheduled jobs are lost and must be re-initialized
- Z-Wave and Zigbee radios restart (devices may take additional time to reconnect)

NEVER call this tool without completing ALL steps in the checklist above.
NEVER call this tool unless the user specifically requested a reboot.
If the backup failed, DO NOT proceed — inform the user instead.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
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
            description: """⚠️⚠️⚠️ EXTREME CAUTION — THIS WILL POWER OFF THE HUB ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST — You MUST complete ALL steps IN ORDER before calling this tool:
1. Call 'create_hub_backup' and verify the response shows success=true
2. Note the backup timestamp from the response
3. Tell the user: "I have created a hub backup at [timestamp]. I am about to SHUT DOWN your Hubitat hub. It will NOT restart automatically — you must physically unplug and replug the hub to restart it."
4. Get EXPLICIT user confirmation to proceed (the user must say yes/confirm/proceed)
5. Set the 'confirm' parameter to true

Shuts down the Hubitat hub COMPLETELY. Effects:
- Hub will POWER OFF and will NOT restart automatically
- ALL automations, rules, and device communications PERMANENTLY STOP until manually restarted
- User must physically power-cycle the hub to bring it back online
- ALL smart home functionality ceases until hub is restarted

This is NOT a reboot — the hub stays off. Only use when user explicitly wants to shut down.
NEVER call this tool without completing ALL steps in the checklist above.
NEVER call this tool unless the user specifically requested a shutdown.
If the backup failed, DO NOT proceed — inform the user instead.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
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
            description: """⚠️ WARNING — NETWORK-DISRUPTIVE OPERATION ⚠️

MANDATORY PRE-FLIGHT CHECKLIST — You MUST complete ALL steps IN ORDER before calling this tool:
1. Call 'create_hub_backup' and verify the response shows success=true
2. Note the backup timestamp from the response
3. Tell the user: "I have created a hub backup at [timestamp]. I am about to start a Z-Wave network repair. This can take 5-30 minutes depending on network size and may cause temporary device communication issues."
4. Get EXPLICIT user confirmation to proceed
5. Set the 'confirm' parameter to true

Starts a Z-Wave network repair/optimization. Effects:
- Takes 5-30+ minutes depending on Z-Wave network size
- Z-Wave devices may be temporarily unresponsive during repair
- Automations using Z-Wave devices may fail during the process
- Best run during off-peak hours when device reliability is less critical

NEVER call this tool without completing ALL steps in the checklist above.
NEVER call this tool unless the user specifically requested a Z-Wave repair.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the Z-Wave repair."]
                ],
                required: ["confirm"]
            ]
        ],
        // Hub Admin App/Driver Source Read Tools
        [
            name: "get_app_source",
            description: """Get the Groovy source code of an installed app by its ID.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Use list_hub_apps to find app IDs first. Returns the full source code text and the internal version number.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID (from list_hub_apps)"]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "get_driver_source",
            description: """Get the Groovy source code of an installed driver by its ID.

Requires 'Enable Hub Admin Read Tools' to be turned on in the MCP Rule Server app settings.
Use list_hub_drivers to find driver IDs first. Returns the full source code text and the internal version number.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID (from list_hub_drivers)"]
                ],
                required: ["driverId"]
            ]
        ],
        // Hub Admin App/Driver Management Write Tools
        [
            name: "install_app",
            description: """⚠️ WARNING — INSTALLS CODE ON THE HUB ⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Tell the user what app you are about to install and show them the source code
3. Get EXPLICIT user confirmation to proceed
4. Set confirm=true

Installs a new Groovy app on the Hubitat hub from source code. The source code must be valid Hubitat app Groovy code.

Returns the new app ID on success. After installation, the app still needs to be added via Apps > Add User App in the Hubitat web UI.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
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
            description: """⚠️ WARNING — INSTALLS CODE ON THE HUB ⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Tell the user what driver you are about to install and show them the source code
3. Get EXPLICIT user confirmation to proceed
4. Set confirm=true

Installs a new Groovy driver on the Hubitat hub from source code. The source code must be valid Hubitat driver Groovy code.

Returns the new driver ID on success. After installation, devices can be assigned to use this driver.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
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
            description: """⚠️⚠️⚠️ CRITICAL WARNING — MODIFIES EXISTING APP CODE ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Use get_app_source to read the CURRENT code first
3. Tell the user what changes you are making
4. Get EXPLICIT user confirmation to proceed
5. Set confirm=true

Updates the Groovy source code of an existing app. Uses optimistic locking — the current version is fetched automatically to prevent conflicts.

WARNING: Incorrect code can break the app and any automations depending on it.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID to update"],
                    source: [type: "string", description: "The full new Groovy source code"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["appId", "source", "confirm"]
            ]
        ],
        [
            name: "update_driver_code",
            description: """⚠️⚠️⚠️ CRITICAL WARNING — MODIFIES EXISTING DRIVER CODE ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Use get_driver_source to read the CURRENT code first
3. Tell the user what changes you are making
4. Get EXPLICIT user confirmation to proceed
5. Set confirm=true

Updates the Groovy source code of an existing driver. Uses optimistic locking — the current version is fetched automatically to prevent conflicts.

WARNING: Incorrect code can break the driver and all devices using it.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID to update"],
                    source: [type: "string", description: "The full new Groovy source code"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["driverId", "source", "confirm"]
            ]
        ],
        [
            name: "delete_app",
            description: """⚠️⚠️⚠️ CRITICAL WARNING — PERMANENTLY DELETES AN APP ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Tell the user which app (by name and ID) you are about to delete
3. Warn that this is PERMANENT and cannot be undone
4. Get EXPLICIT user confirmation to proceed
5. Set confirm=true

Permanently deletes an installed app from the hub. This removes the app code — any app instances using this code must be removed first via the Hubitat web UI.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
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
            description: """⚠️⚠️⚠️ CRITICAL WARNING — PERMANENTLY DELETES A DRIVER ⚠️⚠️⚠️

MANDATORY PRE-FLIGHT CHECKLIST:
1. Call 'create_hub_backup' and verify success
2. Tell the user which driver (by name and ID) you are about to delete
3. Warn that this is PERMANENT and cannot be undone — all devices using this driver will be affected
4. Get EXPLICIT user confirmation to proceed
5. Set confirm=true

Permanently deletes an installed driver from the hub. Devices using this driver must be changed to a different driver first.

Requires 'Enable Hub Admin Write Tools' to be turned on in MCP Rule Server app settings.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["driverId", "confirm"]
            ]
        ]
    ]
}

def executeTool(toolName, args) {
    switch (toolName) {
        // Device Tools
        case "list_devices": return toolListDevices(args.detailed, args.offset ?: 0, args.limit ?: 0)
        case "get_device": return toolGetDevice(args.deviceId)
        case "send_command": return toolSendCommand(args.deviceId, args.command, args.parameters)
        case "get_device_events": return toolGetDeviceEvents(args.deviceId, args.limit ?: 10)
        case "get_attribute": return toolGetAttribute(args.deviceId, args.attribute)

        // Rule Management - now using child apps
        case "list_rules": return toolListRules()
        case "get_rule": return toolGetRule(args.ruleId)
        case "create_rule": return toolCreateRule(args)
        case "update_rule": return toolUpdateRule(args.ruleId, args)
        case "delete_rule": return toolDeleteRule(args.ruleId)
        case "enable_rule": return toolEnableRule(args.ruleId)
        case "disable_rule": return toolDisableRule(args.ruleId)
        case "test_rule": return toolTestRule(args.ruleId)

        // System Tools
        case "get_hub_info": return toolGetHubInfo()
        case "get_modes": return toolGetModes()
        case "set_mode": return toolSetMode(args.mode)
        case "list_variables": return toolListVariables()
        case "get_variable": return toolGetVariable(args.name)
        case "set_variable": return toolSetVariable(args.name, args.value)
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
        case "get_hub_details": return toolGetHubDetails(args)
        case "list_hub_apps": return toolListHubApps(args)
        case "list_hub_drivers": return toolListHubDrivers(args)
        case "get_zwave_details": return toolGetZwaveDetails(args)
        case "get_zigbee_details": return toolGetZigbeeDetails(args)
        case "get_hub_health": return toolGetHubHealth(args)

        // Hub Admin Write Tools
        case "create_hub_backup": return toolCreateHubBackup(args)
        case "reboot_hub": return toolRebootHub(args)
        case "shutdown_hub": return toolShutdownHub(args)
        case "zwave_repair": return toolZwaveRepair(args)

        // Hub Admin App/Driver Management
        case "get_app_source": return toolGetAppSource(args)
        case "get_driver_source": return toolGetDriverSource(args)
        case "install_app": return toolInstallApp(args)
        case "install_driver": return toolInstallDriver(args)
        case "update_app_code": return toolUpdateAppCode(args)
        case "update_driver_code": return toolUpdateDriverCode(args)
        case "delete_app": return toolDeleteApp(args)
        case "delete_driver": return toolDeleteDriver(args)

        default:
            throw new IllegalArgumentException("Unknown tool: ${toolName}")
    }
}

// ==================== DEVICE TOOLS ====================

def toolListDevices(detailed, offset, limit) {
    if (!selectedDevices) {
        return [devices: [], message: "No devices selected for MCP access", total: 0]
    }

    def allDevices = selectedDevices.toList()
    def totalCount = allDevices.size()

    // Apply pagination
    def startIndex = offset ?: 0
    if (startIndex < 0) startIndex = 0
    def endIndex = totalCount
    if (limit && limit > 0) {
        endIndex = Math.min(startIndex + limit, totalCount)
    }

    // Validate offset
    if (startIndex >= totalCount) {
        return [
            devices: [],
            total: totalCount,
            offset: startIndex,
            limit: limit ?: 0,
            message: "Offset ${startIndex} exceeds total device count ${totalCount}"
        ]
    }

    def pagedDevices = allDevices.subList(startIndex, endIndex)

    def devices = pagedDevices.collect { device ->
        def info = [
            id: device.id.toString(),
            name: device.name,
            label: device.label ?: device.name,
            room: device.roomName
        ]

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
        def convertedParams = parameters.collect { param ->
            def s = param.toString()
            if (s.isNumber()) {
                return s.contains(".") ? s.toDouble() : s.toInteger()
            }
            return param
        }
        device."${command}"(*convertedParams)
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
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
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
        enabled: args.enabled != false  // Set enabled AFTER data is stored
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
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
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

def toolDeleteRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    deleteChildApp(childApp.id)

    return [
        success: true,
        message: "Rule '${ruleName}' deleted successfully"
    ]
}

def toolEnableRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    childApp.enableRule()

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    return [
        success: true,
        message: "Rule '${ruleName}' enabled"
    ]
}

def toolDisableRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    childApp.disableRule()

    def ruleName = childApp.getSetting("ruleName") ?: "Unnamed Rule"
    return [
        success: true,
        message: "Rule '${ruleName}' disabled"
    ]
}

def toolTestRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    return childApp.testRuleFromParent()
}

// ==================== RULE EXPORT/IMPORT/CLONE TOOLS ====================

def toolExportRule(args) {
    if (!args.ruleId) {
        throw new IllegalArgumentException("ruleId is required")
    }

    def childApp = getChildAppById(args.ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${args.ruleId}")
    }

    def ruleData = childApp.getRuleData()
    if (!ruleData) {
        throw new IllegalArgumentException("Unable to read rule data for rule: ${args.ruleId}")
    }

    // Build the portable rule object (exclude runtime state like id, lastTriggered, executionCount)
    def ruleExport = [
        name: ruleData.name,
        description: ruleData.description ?: "",
        enabled: ruleData.enabled,
        conditionLogic: ruleData.conditionLogic ?: "all",
        triggers: ruleData.triggers ?: [],
        conditions: ruleData.conditions ?: [],
        actions: ruleData.actions ?: [],
        localVariables: ruleData.localVariables ?: [:]
    ]

    // Build device manifest by scanning all rule components
    def deviceManifest = buildDeviceManifest(ruleData)

    def exportData = [
        exportVersion: "1.0",
        exportedAt: new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
        serverVersion: "0.4.1",
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

    // Check for deviceId field
    if (component.deviceId) {
        def id = component.deviceId.toString()
        if (!deviceUsage.containsKey(id)) {
            deviceUsage[id] = new LinkedHashSet()
        }
        deviceUsage[id] << section
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
        name: hub?.name,
        localIP: hub?.localIP,
        timeZone: location.timeZone?.ID,
        temperatureScale: location.temperatureScale,
        latitude: location.latitude,
        longitude: location.longitude
    ]

    try { info.model = hub?.hardwareID } catch (Exception e) { info.model = null }
    try { info.firmwareVersion = hub?.firmwareVersionString } catch (Exception e) { info.firmwareVersion = null }
    try { info.uptime = hub?.uptime } catch (Exception e) { info.uptime = null }
    try { info.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { info.zigbeeChannel = null }
    try { info.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { info.zwaveVersion = null }

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

// Helper method for child apps to get variable values
def getVariableValue(name) {
    try {
        def hubVar = getGlobalConnectorVariable(name)
        if (hubVar != null) return hubVar
    } catch (Exception e) {
        // Hub connector variable not found
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

    sendLocationEvent(name: "hsmSetArm", value: mode)

    return [
        success: true,
        previousStatus: location.hsmStatus,
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
    return settings.selectedDevices?.find { it.id.toString() == deviceId.toString() }
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
 * Make an authenticated GET request to the hub's internal API.
 * Automatically includes Hub Security cookie if configured.
 * Returns the response body as text.
 */
def hubInternalGet(String path, Map query = null, int timeout = 30) {
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
            responseText = resp.data?.text?.toString() ?: resp.data?.toString()
        }
    } catch (Exception e) {
        // Clear cached cookie on auth failures so next call re-authenticates
        if (settings.hubSecurityEnabled && (e.message?.contains("401") || e.message?.contains("403") || e.message?.contains("Unauthorized"))) {
            state.hubSecurityCookie = null
            state.hubSecurityCookieExpiry = null
            mcpLog("debug", "hub-admin", "Cleared stale Hub Security cookie after auth failure on ${path}")
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
def hubInternalPost(String path, Map body = null) {
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
        // Clear cached cookie on auth failures so next call re-authenticates
        if (settings.hubSecurityEnabled && (e.message?.contains("401") || e.message?.contains("403") || e.message?.contains("Unauthorized"))) {
            state.hubSecurityCookie = null
            state.hubSecurityCookieExpiry = null
            mcpLog("debug", "hub-admin", "Cleared stale Hub Security cookie after auth failure on ${path}")
        }
        throw e
    }
    return responseText
}

/**
 * Make an authenticated POST request to the hub's internal API with form-encoded body.
 * Used for app/driver management endpoints that require application/x-www-form-urlencoded.
 */
def hubInternalPostForm(String path, Map body, int timeout = 420) {
    def cookie = getHubSecurityCookie()
    def params = [
        uri: "http://127.0.0.1:8080",
        path: path,
        requestContentType: "application/x-www-form-urlencoded",
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
            result = [
                status: resp.status,
                location: resp.headers?."Location"?.toString(),
                data: resp.data
            ]
        }
    } catch (Exception e) {
        if (settings.hubSecurityEnabled && (e.message?.contains("401") || e.message?.contains("403") || e.message?.contains("Unauthorized"))) {
            state.hubSecurityCookie = null
            state.hubSecurityCookieExpiry = null
            mcpLog("debug", "hub-admin", "Cleared stale Hub Security cookie after auth failure on ${path}")
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
 * Check if Hub Admin Write access is enabled and a recent backup exists. Throws if not.
 */
def requireHubAdminWrite(Boolean confirmParam) {
    if (!settings.enableHubAdminWrite) {
        throw new IllegalArgumentException("Hub Admin Write access is disabled. Enable 'Enable Hub Admin Write Tools' in MCP Rule Server app settings to use this tool.")
    }
    if (!confirmParam) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: You must set confirm=true to use this tool. Did you create a backup with create_hub_backup first? Review the tool description for the mandatory pre-flight checklist.")
    }
    // Check for recent backup (within 1 hour)
    if (!state.lastBackupTimestamp || (now() - state.lastBackupTimestamp) > 3600000) {
        throw new IllegalArgumentException("BACKUP REQUIRED: No backup found within the last hour. You MUST call create_hub_backup FIRST and verify it succeeds before using any Hub Admin Write tool. Last backup: ${state.lastBackupTimestamp ? formatTimestamp(state.lastBackupTimestamp) : 'Never'}")
    }
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
        version: "0.4.1",
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
    def version = "0.4.1"  // NOTE: Keep in sync with serverInfo version
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

    details.mcpServerVersion = "0.4.1"
    details.selectedDeviceCount = settings.selectedDevices?.size() ?: 0
    details.ruleCount = getChildApps()?.size() ?: 0
    details.hubSecurityConfigured = settings.hubSecurityEnabled ?: false
    details.hubAdminReadEnabled = settings.enableHubAdminRead ?: false
    details.hubAdminWriteEnabled = settings.enableHubAdminWrite ?: false

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
    try {
        def responseText = hubInternalGet("/hub2/zwaveInfo")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.zwaveData = parsed
                result.source = "hub_api"
            } catch (Exception parseErr) {
                result.rawResponse = responseText?.take(3000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON format"
            }
        } else {
            result.source = "hub_api"
            result.note = "Empty response from Z-Wave info endpoint"
        }
    } catch (Exception e) {
        mcpLog("debug", "hub-admin", "Z-Wave info API call failed: ${e.message}")
        result.source = "sdk_only"
        result.note = "Extended Z-Wave info unavailable (${e.message}). Showing basic info from hub SDK."
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
    try {
        def responseText = hubInternalGet("/hub2/zigbeeInfo")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.zigbeeData = parsed
                result.source = "hub_api"
            } catch (Exception parseErr) {
                result.rawResponse = responseText?.take(3000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON format"
            }
        } else {
            result.source = "hub_api"
            result.note = "Empty response from Zigbee info endpoint"
        }
    } catch (Exception e) {
        mcpLog("debug", "hub-admin", "Zigbee info API call failed: ${e.message}")
        result.source = "sdk_only"
        result.note = "Extended Zigbee info unavailable (${e.message}). Showing basic info from hub SDK."
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

def toolGetAppSource(args) {
    requireHubAdminRead()
    if (!args.appId) throw new IllegalArgumentException("appId is required")

    try {
        def responseText = hubInternalGet("/app/ajax/code", [id: args.appId])
        if (responseText) {
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            if (parsed.status == "error") {
                return [success: false, error: parsed.errorMessage ?: "Failed to get app source"]
            }
            mcpLog("info", "hub-admin", "Retrieved source code for app ID: ${args.appId}")
            return [
                success: true,
                appId: args.appId,
                source: parsed.source,
                version: parsed.version,
                status: parsed.status
            ]
        }
        return [success: false, error: "Empty response from hub"]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Failed to get app source: ${e.message}")
        return [success: false, error: "Failed to get app source: ${e.message}"]
    }
}

def toolGetDriverSource(args) {
    requireHubAdminRead()
    if (!args.driverId) throw new IllegalArgumentException("driverId is required")

    try {
        def responseText = hubInternalGet("/driver/ajax/code", [id: args.driverId])
        if (responseText) {
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            if (parsed.status == "error") {
                return [success: false, error: parsed.errorMessage ?: "Failed to get driver source"]
            }
            mcpLog("info", "hub-admin", "Retrieved source code for driver ID: ${args.driverId}")
            return [
                success: true,
                driverId: args.driverId,
                source: parsed.source,
                version: parsed.version,
                status: parsed.status
            ]
        }
        return [success: false, error: "Empty response from hub"]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Failed to get driver source: ${e.message}")
        return [success: false, error: "Failed to get driver source: ${e.message}"]
    }
}

def toolInstallApp(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.source) throw new IllegalArgumentException("source (Groovy code) is required")

    mcpLog("info", "hub-admin", "Installing new app...")
    try {
        def result = hubInternalPostForm("/app/save", [
            id: "",
            version: "",
            create: "",
            source: args.source
        ])

        def newAppId = null
        if (result?.location) {
            // Extract app ID from redirect Location header: /app/editor/123
            newAppId = result.location.replaceAll(".*?/app/editor/", "").replaceAll("[^0-9]", "")
        }

        mcpLog("info", "hub-admin", "App installed successfully${newAppId ? ' (ID: ' + newAppId + ')' : ''}")
        return [
            success: true,
            message: "App installed successfully",
            appId: newAppId,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "App installation failed: ${e.message}")
        return [
            success: false,
            error: "App installation failed: ${e.message}",
            note: "Check that the Groovy source code is valid and doesn't have syntax errors."
        ]
    }
}

def toolInstallDriver(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.source) throw new IllegalArgumentException("source (Groovy code) is required")

    mcpLog("info", "hub-admin", "Installing new driver...")
    try {
        def result = hubInternalPostForm("/driver/save", [
            id: "",
            version: "",
            create: "",
            source: args.source
        ])

        def newDriverId = null
        if (result?.location) {
            newDriverId = result.location.replaceAll(".*?/driver/editor/", "").replaceAll("[^0-9]", "")
        }

        mcpLog("info", "hub-admin", "Driver installed successfully${newDriverId ? ' (ID: ' + newDriverId + ')' : ''}")
        return [
            success: true,
            message: "Driver installed successfully",
            driverId: newDriverId,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Driver installation failed: ${e.message}")
        return [
            success: false,
            error: "Driver installation failed: ${e.message}",
            note: "Check that the Groovy source code is valid and doesn't have syntax errors."
        ]
    }
}

def toolUpdateAppCode(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.appId) throw new IllegalArgumentException("appId is required")
    if (!args.source) throw new IllegalArgumentException("source (Groovy code) is required")

    // First get the current version (required for optimistic locking)
    def currentVersion = null
    try {
        def codeResp = hubInternalGet("/app/ajax/code", [id: args.appId])
        if (codeResp) {
            def parsed = new groovy.json.JsonSlurper().parseText(codeResp)
            currentVersion = parsed.version
        }
    } catch (Exception e) {
        throw new IllegalArgumentException("Could not retrieve current app version for ID ${args.appId}: ${e.message}")
    }

    if (currentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for app ID ${args.appId}. The app may not exist.")
    }

    mcpLog("info", "hub-admin", "Updating app ID: ${args.appId} (current version: ${currentVersion})")
    try {
        def result = hubInternalPostForm("/app/ajax/update", [
            id: args.appId,
            version: currentVersion,
            source: args.source
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
                // Response was not JSON
                success = true // Assume success if no error thrown
            }
        } else {
            success = true
        }

        if (success) {
            mcpLog("info", "hub-admin", "App ID ${args.appId} updated successfully")
            return [
                success: true,
                message: "App code updated successfully",
                appId: args.appId,
                previousVersion: currentVersion,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        } else {
            return [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                appId: args.appId,
                note: "Check the Groovy source code for syntax errors or compilation issues."
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "App update failed: ${e.message}")
        return [success: false, error: "App update failed: ${e.message}"]
    }
}

def toolUpdateDriverCode(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.driverId) throw new IllegalArgumentException("driverId is required")
    if (!args.source) throw new IllegalArgumentException("source (Groovy code) is required")

    def currentVersion = null
    try {
        def codeResp = hubInternalGet("/driver/ajax/code", [id: args.driverId])
        if (codeResp) {
            def parsed = new groovy.json.JsonSlurper().parseText(codeResp)
            currentVersion = parsed.version
        }
    } catch (Exception e) {
        throw new IllegalArgumentException("Could not retrieve current driver version for ID ${args.driverId}: ${e.message}")
    }

    if (currentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for driver ID ${args.driverId}. The driver may not exist.")
    }

    mcpLog("info", "hub-admin", "Updating driver ID: ${args.driverId} (current version: ${currentVersion})")
    try {
        def result = hubInternalPostForm("/driver/ajax/update", [
            id: args.driverId,
            version: currentVersion,
            source: args.source
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
                success = true
            }
        } else {
            success = true
        }

        if (success) {
            mcpLog("info", "hub-admin", "Driver ID ${args.driverId} updated successfully")
            return [
                success: true,
                message: "Driver code updated successfully",
                driverId: args.driverId,
                previousVersion: currentVersion,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        } else {
            return [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                driverId: args.driverId,
                note: "Check the Groovy source code for syntax errors or compilation issues."
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Driver update failed: ${e.message}")
        return [success: false, error: "Driver update failed: ${e.message}"]
    }
}

def toolDeleteApp(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.appId) throw new IllegalArgumentException("appId is required")

    mcpLog("warn", "hub-admin", "Deleting app ID: ${args.appId}")
    try {
        def responseText = hubInternalGet("/app/edit/deleteJsonSafe/${args.appId}")
        def success = false
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                success = parsed.status == true
            } catch (Exception parseErr) {
                // If not JSON, check if it contains error indicators
                success = !responseText.contains("error")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "App ID ${args.appId} deleted successfully")
            return [
                success: true,
                message: "App deleted successfully",
                appId: args.appId,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        } else {
            return [
                success: false,
                error: "Delete may have failed - check the Hubitat web UI to verify",
                appId: args.appId,
                response: responseText?.take(500)
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "App deletion failed: ${e.message}")
        return [success: false, error: "App deletion failed: ${e.message}"]
    }
}

def toolDeleteDriver(args) {
    requireHubAdminWrite(args.confirm)
    if (!args.driverId) throw new IllegalArgumentException("driverId is required")

    mcpLog("warn", "hub-admin", "Deleting driver ID: ${args.driverId}")
    try {
        def responseText = hubInternalGet("/driver/editor/deleteJson/${args.driverId}")
        def success = false
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                success = parsed.status == true
            } catch (Exception parseErr) {
                success = !responseText.contains("error")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "Driver ID ${args.driverId} deleted successfully")
            return [
                success: true,
                message: "Driver deleted successfully",
                driverId: args.driverId,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        } else {
            return [
                success: false,
                error: "Delete may have failed - check the Hubitat web UI to verify",
                driverId: args.driverId,
                response: responseText?.take(500)
            ]
        }
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "Driver deletion failed: ${e.message}")
        return [success: false, error: "Driver deletion failed: ${e.message}"]
    }
}

// ==================== VERSION UPDATE CHECK ====================

def currentVersion() {
    return "0.4.1"
}

def isNewerVersion(String remote, String local) {
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
            def hoursSinceCheck = (now() - state.updateCheck.checkedAt) / (1000 * 60 * 60)
            if (hoursSinceCheck < 24) {
                logDebug("Version check skipped - last checked ${hoursSinceCheck.round(1)} hours ago")
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
