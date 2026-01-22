/**
 * MCP Rule Server for Hubitat
 *
 * A native MCP (Model Context Protocol) server that runs directly on Hubitat
 * with a built-in custom rule engine for creating automations via Claude.
 *
 * Version: 0.1.11 - Added verification reminders to tool descriptions
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
                paragraph "<b>Version:</b> 0.1.1"
            }
        }

        section("Device Access") {
            input "selectedDevices", "capability.*", title: "Select Devices for MCP Access",
                  multiple: true, required: false, submitOnChange: true
            if (selectedDevices) {
                paragraph "Selected ${selectedDevices.size()} devices"
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
            input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
            input "maxCapturedStates", "number", title: "Max Captured States",
                  description: "Maximum number of unique state captures to store (default: 20)",
                  defaultValue: 20, range: "1..100", required: false
        }
    }
}

def formatTimestamp(timestamp) {
    try {
        if (timestamp instanceof Number) {
            def date = new Date(timestamp)
            return date.format("MM/dd HH:mm")
        } else if (timestamp instanceof String) {
            def date = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", timestamp)
            return date.format("MM/dd HH:mm")
        }
        return "Unknown"
    } catch (Exception e) {
        return timestamp?.toString()?.take(16) ?: "Unknown"
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

    state.ruleToDelete = ruleId
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
    return render(contentType: "application/json", data: '{"status":"ok","server":"hubitat-mcp-rule-server","version":"0.1.12"}')
}

def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: '{"error":"GET not supported, use POST"}')
}

def handleMcpRequest() {
    def requestBody = request.JSON
    logDebug("MCP Request: ${requestBody}")

    def response
    if (requestBody instanceof List) {
        response = requestBody.collect { msg -> processJsonRpcMessage(msg) }.findAll { it != null }
    } else {
        response = processJsonRpcMessage(requestBody)
    }

    if (response == null) {
        return render(status: 202, contentType: "application/json", data: "")
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

    if (msg.id == null && msg.method) {
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
    return jsonRpcResult(msg.id, [
        protocolVersion: "2024-11-05",
        capabilities: [
            tools: [:]
        ],
        serverInfo: [
            name: "hubitat-mcp-rule-server",
            version: "0.1.12"
        ]
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
        return jsonRpcError(msg.id, -32602, "Invalid params: ${e.message}")
    } catch (Exception e) {
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
- For large device lists, make multiple paginated requests rather than one large request""",
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

TRIGGERS: device_event (with duration for debouncing), button_event (pushed/held/doubleTapped), time (HH:mm or sunrise/sunset with offset), mode_change, hsm_change
CONDITIONS: device_state, device_was (state for X seconds), time_range (supports sunrise/sunset), mode, variable, days_of_week, sun_position, hsm_status
ACTIONS: device_command, toggle_device, activate_scene, set_variable, set_local_variable, set_mode, set_hsm, delay (with ID for targeted cancel), if_then_else, cancel_delayed, repeat, stop, log

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
                    value: [description: "Variable value"]
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
            if (param.isNumber()) {
                return param.contains(".") ? param.toDouble() : param.toInteger()
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

    def value = device.currentValue(attribute)
    if (value == null) {
        def supportedAttrs = device.supportedAttributes?.collect { it.name }
        throw new IllegalArgumentException("Attribute '${attribute}' not found. Available: ${supportedAttrs}")
    }

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
    } ?: []

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

    // Create child app
    def childApp = addChildApp("mcp", "MCP Rule", args.name.trim())

    // Configure the child app
    childApp.updateSetting("ruleName", args.name.trim())
    childApp.updateSetting("ruleDescription", args.description ?: "")
    childApp.updateSetting("ruleEnabled", args.enabled != false)

    // Set rule data via the child's API
    childApp.updateRuleFromParent([
        triggers: args.triggers,
        conditions: args.conditions ?: [],
        conditionLogic: args.conditionLogic ?: "all",
        actions: args.actions,
        localVariables: args.localVariables ?: [:]
    ])

    return [
        success: true,
        ruleId: childApp.id.toString(),
        message: "Rule '${args.name}' created successfully"
    ]
}

def toolUpdateRule(ruleId, args) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    // Validate any provided triggers
    if (args.triggers != null) {
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

    // Update via child app API
    def updateData = [:]
    if (args.name != null) updateData.name = args.name.trim()
    if (args.description != null) updateData.description = args.description
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

    location.setMode(mode.name)

    return [
        success: true,
        previousMode: location.mode,
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

// Get the user-configured max captured states limit (default: 20)
def getMaxCapturedStates() {
    return settings.maxCapturedStates ?: 20
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

def validateTrigger(trigger) {
    if (!trigger.type) {
        throw new IllegalArgumentException("Trigger type is required")
    }

    switch (trigger.type) {
        case "device_event":
            if (!trigger.deviceId) throw new IllegalArgumentException("device_event trigger requires deviceId")
            if (!trigger.attribute) throw new IllegalArgumentException("device_event trigger requires attribute")
            if (!findDevice(trigger.deviceId)) throw new IllegalArgumentException("Device not found: ${trigger.deviceId}")
            break
        case "button_event":
            if (!trigger.deviceId) throw new IllegalArgumentException("button_event trigger requires deviceId")
            if (!findDevice(trigger.deviceId)) throw new IllegalArgumentException("Device not found: ${trigger.deviceId}")
            break
        case "time":
            if (!trigger.time && !trigger.sunrise && !trigger.sunset) {
                throw new IllegalArgumentException("time trigger requires time (HH:mm), sunrise, or sunset")
            }
            break
        case "periodic":
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
        case "device_was":
            if (!condition.deviceId) throw new IllegalArgumentException("${condition.type} condition requires deviceId")
            if (!condition.attribute) throw new IllegalArgumentException("${condition.type} condition requires attribute")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "time_range":
            if (!condition.start && !condition.startSunrise && !condition.startSunset) {
                throw new IllegalArgumentException("time_range condition requires start time")
            }
            if (!condition.end && !condition.endSunrise && !condition.endSunset) {
                throw new IllegalArgumentException("time_range condition requires end time")
            }
            break
        case "mode":
            if (!condition.mode && !condition.modes) {
                throw new IllegalArgumentException("mode condition requires mode or modes")
            }
            break
        case "variable":
            if (!condition.variableName) throw new IllegalArgumentException("variable condition requires variableName")
            break
        case "days_of_week":
            if (!condition.days) throw new IllegalArgumentException("days_of_week condition requires days array")
            break
        case "sun_position":
            if (!condition.position) throw new IllegalArgumentException("sun_position condition requires position (day/night)")
            break
        case "hsm_status":
            if (!condition.status) throw new IllegalArgumentException("hsm_status condition requires status")
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
            break
        case "power":
            if (!condition.deviceId) throw new IllegalArgumentException("power condition requires deviceId")
            if (!findDevice(condition.deviceId)) throw new IllegalArgumentException("Device not found: ${condition.deviceId}")
            break
        case "expression":
            if (!condition.expression) throw new IllegalArgumentException("expression condition requires expression")
            break
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
            break
        case "set_variable":
            if (!action.variableName) throw new IllegalArgumentException("set_variable action requires variableName")
            break
        case "set_local_variable":
            if (!action.variableName) throw new IllegalArgumentException("set_local_variable action requires variableName")
            break
        case "set_mode":
            if (!action.mode) throw new IllegalArgumentException("set_mode action requires mode")
            break
        case "set_hsm":
            if (!action.status) throw new IllegalArgumentException("set_hsm action requires status")
            break
        case "delay":
            if (!action.seconds && action.seconds != 0) throw new IllegalArgumentException("delay action requires seconds")
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
            if (!action.times) throw new IllegalArgumentException("repeat action requires times")
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
            if (action.level == null) throw new IllegalArgumentException("set_level action requires level")
            break
        case "set_color":
            if (!action.deviceId) throw new IllegalArgumentException("set_color action requires deviceId")
            break
        case "set_color_temperature":
            if (!action.deviceId) throw new IllegalArgumentException("set_color_temperature action requires deviceId")
            if (!action.temperature) throw new IllegalArgumentException("set_color_temperature action requires temperature")
            break
        case "lock":
        case "unlock":
            if (!action.deviceId) throw new IllegalArgumentException("${action.type} action requires deviceId")
            break
        case "capture_state":
            if (!action.deviceIds) throw new IllegalArgumentException("capture_state action requires deviceIds")
            break
        case "restore_state":
            break
        case "send_notification":
            if (!action.message) throw new IllegalArgumentException("send_notification action requires message")
            break
        default:
            throw new IllegalArgumentException("Unknown action type: ${action.type}")
    }
}

// ==================== HELPER FUNCTIONS ====================

def findDevice(deviceId) {
    if (!deviceId) return null
    return selectedDevices?.find { it.id.toString() == deviceId.toString() }
}

// Expose devices to child apps
def getSelectedDevices() {
    return selectedDevices
}

// Allow child apps to find devices
def findDeviceForChild(deviceId) {
    return findDevice(deviceId)
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
