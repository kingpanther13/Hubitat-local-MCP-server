/**
 * MCP Rule Server for Hubitat
 *
 * A native MCP (Model Context Protocol) server that runs directly on Hubitat
 * with a built-in custom rule engine for creating automations via Claude.
 *
 * Installation:
 * 1. Go to Hubitat > Apps Code > New App
 * 2. Paste this code and click Save
 * 3. Click "OAuth" button, then "Enable OAuth in App"
 * 4. Save again
 * 5. Go to Apps > Add User App > MCP Rule Server
 * 6. Select devices to expose, click Done
 * 7. Open app to get endpoint URL with access token
 */

definition(
    name: "MCP Rule Server",
    namespace: "mcp",
    author: "Claude Generated",
    description: "MCP Server with Custom Rule Engine for Hubitat",
    category: "Automation",
    iconUrl: "",
    iconX2Url: "",
    oauth: [displayName: "MCP Rule Server", displayLink: ""]
)

preferences {
    page(name: "mainPage")
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
            }
        }
        section("Device Access") {
            input "selectedDevices", "capability.*", title: "Select Devices for MCP Access",
                  multiple: true, required: false, submitOnChange: true
            if (selectedDevices) {
                paragraph "Selected ${selectedDevices.size()} devices"
            }
        }
        section("Rule Engine") {
            def ruleCount = state.rules?.size() ?: 0
            def enabledCount = state.rules?.values()?.count { it.enabled } ?: 0
            paragraph "Rules: ${ruleCount} total, ${enabledCount} enabled"
            input "enableRuleEngine", "bool", title: "Enable Rule Engine", defaultValue: true
        }
        section("Debug") {
            input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
        }
    }
}

// ==================== HTTP MAPPINGS ====================

mappings {
    path("/mcp") {
        action: [POST: "handleMcpRequest", GET: "handleMcpGet"]
    }
    path("/health") {
        action: [GET: "handleHealth"]
    }
}

// ==================== LIFECYCLE ====================

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
    if (!state.rules) {
        state.rules = [:]
    }
    if (!state.ruleVariables) {
        state.ruleVariables = [:]
    }

    // Refresh subscriptions for enabled rules
    if (settings.enableRuleEngine != false) {
        refreshAllSubscriptions()
    }
}

// ==================== MCP REQUEST HANDLERS ====================

def handleHealth() {
    return render(contentType: "application/json", data: '{"status":"ok","server":"hubitat-mcp-rule-server"}')
}

def handleMcpGet() {
    // MCP Streamable HTTP allows GET for server-initiated messages
    // For now, return method not supported since we don't have server-initiated events
    return render(status: 405, contentType: "application/json",
                  data: '{"error":"GET not supported, use POST"}')
}

def handleMcpRequest() {
    def requestBody = request.JSON
    logDebug("MCP Request: ${requestBody}")

    def response
    if (requestBody instanceof List) {
        // Batch request
        response = requestBody.collect { msg -> processJsonRpcMessage(msg) }.findAll { it != null }
    } else {
        // Single request
        response = processJsonRpcMessage(requestBody)
    }

    if (response == null) {
        // Notification - no response needed
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

    // Handle notifications (no id = notification, no response expected)
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
        log.error "Error processing message: ${e.message}", e
        return jsonRpcError(msg.id, -32603, "Internal error: ${e.message}")
    }
}

def handleNotification(msg) {
    switch (msg.method) {
        case "notifications/initialized":
            logDebug("Client initialized notification received")
            break
        case "notifications/cancelled":
            logDebug("Request cancelled: ${msg.params?.requestId}")
            break
        default:
            logDebug("Unknown notification: ${msg.method}")
    }
}

// ==================== MCP PROTOCOL HANDLERS ====================

def handleInitialize(msg) {
    state.mcpSession = [
        clientInfo: msg.params?.clientInfo,
        protocolVersion: msg.params?.protocolVersion,
        initialized: now()
    ]

    return jsonRpcResult(msg.id, [
        protocolVersion: "2024-11-05",
        capabilities: [
            tools: [:]
        ],
        serverInfo: [
            name: "hubitat-mcp-rule-server",
            version: "1.0.0"
        ],
        instructions: """Hubitat MCP Server with Rule Engine.

Available tool categories:
- Device Management: list_devices, get_device, send_command, get_device_events
- Rule Management: list_rules, get_rule, create_rule, update_rule, delete_rule, enable_rule, disable_rule, test_rule
- System: get_hub_info, get_modes, set_mode, list_variables, get_variable, set_variable

Rules support triggers (device_event, time, mode_change), conditions (device_state, time_range, mode), and actions (device_command, set_variable, set_mode, delay, if_then_else)."""
    ])
}

def handleToolsList(msg) {
    return jsonRpcResult(msg.id, [
        tools: getAllToolDefinitions()
    ])
}

def handleToolsCall(msg) {
    def toolName = msg.params?.name
    def args = msg.params?.arguments ?: [:]

    if (!toolName) {
        return jsonRpcError(msg.id, -32602, "Invalid params: tool name required")
    }

    try {
        def result = executeTool(toolName, args)
        return jsonRpcResult(msg.id, [
            content: [[type: "text", text: groovy.json.JsonOutput.toJson(result)]],
            isError: false
        ])
    } catch (IllegalArgumentException e) {
        return jsonRpcResult(msg.id, [
            content: [[type: "text", text: groovy.json.JsonOutput.toJson([error: e.message])]],
            isError: true
        ])
    } catch (Exception e) {
        log.error "Tool execution error: ${e.message}", e
        return jsonRpcResult(msg.id, [
            content: [[type: "text", text: groovy.json.JsonOutput.toJson([error: "Internal error: ${e.message}"])]],
            isError: true
        ])
    }
}

// ==================== TOOL DEFINITIONS ====================

def getAllToolDefinitions() {
    return [
        // Device Management
        [
            name: "list_devices",
            description: "Get all devices authorized for MCP access with their current states",
            inputSchema: [
                type: "object",
                properties: [
                    detailed: [type: "boolean", description: "Include all attributes and capabilities", default: false]
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
            name: "send_command",
            description: "Send a command to a device (e.g., on, off, setLevel, setColor)",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    command: [type: "string", description: "Command name (on, off, setLevel, toggle, etc.)"],
                    parameters: [type: "array", items: [type: "string"], description: "Command parameters", default: []]
                ],
                required: ["deviceId", "command"]
            ]
        ],
        [
            name: "get_device_events",
            description: "Get recent events for a device",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID"],
                    limit: [type: "integer", description: "Maximum events to return", default: 20]
                ],
                required: ["deviceId"]
            ]
        ],

        // Rule Management
        [
            name: "list_rules",
            description: "List all custom automation rules",
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
  "name": "Rule Name",
  "triggers": [{"type": "device_event", "deviceId": "123", "attribute": "switch", "value": "on"}],
  "conditions": [{"type": "device_state", "deviceId": "456", "attribute": "switch", "operator": "equals", "value": "off"}],
  "conditionLogic": "all",
  "actions": [{"type": "device_command", "deviceId": "789", "command": "on"}]
}
Trigger types: device_event, time, mode_change
Condition types: device_state, time_range, mode, variable
Action types: device_command, set_variable, set_mode, delay, if_then_else, cancel_delayed""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Rule name"],
                    description: [type: "string", description: "Rule description"],
                    triggers: [type: "array", description: "Array of trigger objects"],
                    conditions: [type: "array", description: "Array of condition objects"],
                    conditionLogic: [type: "string", enum: ["all", "any"], default: "all"],
                    actions: [type: "array", description: "Array of action objects"],
                    enabled: [type: "boolean", default: true]
                ],
                required: ["name", "triggers", "actions"]
            ]
        ],
        [
            name: "update_rule",
            description: "Update an existing rule",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to update"],
                    name: [type: "string"],
                    description: [type: "string"],
                    triggers: [type: "array"],
                    conditions: [type: "array"],
                    conditionLogic: [type: "string"],
                    actions: [type: "array"],
                    enabled: [type: "boolean"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "delete_rule",
            description: "Delete a rule",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to delete"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "enable_rule",
            description: "Enable a disabled rule",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to enable"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "disable_rule",
            description: "Disable a rule without deleting it",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to disable"]
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "test_rule",
            description: "Test a rule by simulating its execution (dry run)",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to test"]
                ],
                required: ["ruleId"]
            ]
        ],

        // System
        [
            name: "get_hub_info",
            description: "Get Hubitat hub information",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "get_modes",
            description: "Get available hub modes and current mode",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "set_mode",
            description: "Change the hub mode",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", description: "Mode name to set"]
                ],
                required: ["mode"]
            ]
        ],
        [
            name: "list_variables",
            description: "List all hub connector variables and rule engine variables",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
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
            description: "Set a variable value",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name"],
                    value: [type: "string", description: "Variable value"]
                ],
                required: ["name", "value"]
            ]
        ]
    ]
}

// ==================== TOOL EXECUTION ====================

def executeTool(toolName, args) {
    switch (toolName) {
        // Device Management
        case "list_devices": return toolListDevices(args.detailed ?: false)
        case "get_device": return toolGetDevice(args.deviceId)
        case "send_command": return toolSendCommand(args.deviceId, args.command, args.parameters ?: [])
        case "get_device_events": return toolGetDeviceEvents(args.deviceId, args.limit ?: 20)

        // Rule Management
        case "list_rules": return toolListRules()
        case "get_rule": return toolGetRule(args.ruleId)
        case "create_rule": return toolCreateRule(args)
        case "update_rule": return toolUpdateRule(args.ruleId, args)
        case "delete_rule": return toolDeleteRule(args.ruleId)
        case "enable_rule": return toolEnableRule(args.ruleId)
        case "disable_rule": return toolDisableRule(args.ruleId)
        case "test_rule": return toolTestRule(args.ruleId)

        // System
        case "get_hub_info": return toolGetHubInfo()
        case "get_modes": return toolGetModes()
        case "set_mode": return toolSetMode(args.mode)
        case "list_variables": return toolListVariables()
        case "get_variable": return toolGetVariable(args.name)
        case "set_variable": return toolSetVariable(args.name, args.value)

        default:
            throw new IllegalArgumentException("Unknown tool: ${toolName}")
    }
}

// ==================== DEVICE TOOLS ====================

def toolListDevices(detailed) {
    if (!selectedDevices) {
        return [devices: [], message: "No devices selected for MCP access"]
    }

    def devices = selectedDevices.collect { device ->
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
            // Include key attributes
            info.currentStates = [:]
            ["switch", "level", "motion", "contact", "temperature", "humidity", "battery"].each { attr ->
                def val = device.currentValue(attr)
                if (val != null) info.currentStates[attr] = val
            }
        }

        return info
    }

    return [devices: devices, count: devices.size()]
}

def toolGetDevice(deviceId) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    return [
        id: device.id.toString(),
        name: device.name,
        label: device.label ?: device.name,
        room: device.roomName,
        capabilities: device.capabilities?.collect { it.name },
        attributes: device.supportedAttributes?.collect { attr ->
            [name: attr.name, dataType: attr.dataType, value: device.currentValue(attr.name)]
        },
        commands: device.supportedCommands?.collect { cmd ->
            [name: cmd.name, arguments: cmd.arguments?.collect { [name: it.name, type: it.type] }]
        }
    ]
}

def toolSendCommand(deviceId, command, parameters) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    // Check if command is supported
    def supportedCommands = device.supportedCommands?.collect { it.name }
    if (!supportedCommands?.contains(command)) {
        throw new IllegalArgumentException("Device ${device.label} does not support command: ${command}. Available: ${supportedCommands}")
    }

    // Execute command
    if (parameters && parameters.size() > 0) {
        // Convert parameters to appropriate types
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
        device: device.label,
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

// ==================== RULE TOOLS ====================

def toolListRules() {
    def rules = state.rules?.collect { id, rule ->
        [
            id: id,
            name: rule.name,
            description: rule.description,
            enabled: rule.enabled,
            triggerCount: rule.triggers?.size() ?: 0,
            conditionCount: rule.conditions?.size() ?: 0,
            actionCount: rule.actions?.size() ?: 0,
            lastTriggered: rule.lastTriggered,
            triggerCount: rule.executionCount ?: 0
        ]
    } ?: []

    return [rules: rules, count: rules.size()]
}

def toolGetRule(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    return [
        id: ruleId,
        name: rule.name,
        description: rule.description,
        enabled: rule.enabled,
        triggers: rule.triggers,
        conditions: rule.conditions,
        conditionLogic: rule.conditionLogic ?: "all",
        actions: rule.actions,
        createdAt: rule.createdAt,
        updatedAt: rule.updatedAt,
        lastTriggered: rule.lastTriggered,
        executionCount: rule.executionCount ?: 0
    ]
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

    // Generate ID
    def ruleId = "rule-${UUID.randomUUID().toString().substring(0, 8)}"
    def now = new Date().time

    def rule = [
        name: args.name.trim(),
        description: args.description ?: "",
        enabled: args.enabled != false,
        triggers: args.triggers,
        conditions: args.conditions ?: [],
        conditionLogic: args.conditionLogic ?: "all",
        actions: args.actions,
        createdAt: now,
        updatedAt: now,
        executionCount: 0
    ]

    if (!state.rules) state.rules = [:]
    state.rules[ruleId] = rule

    // Subscribe to triggers if enabled
    if (rule.enabled && settings.enableRuleEngine != false) {
        subscribeToRuleTriggers(ruleId, rule)
    }

    return [
        success: true,
        ruleId: ruleId,
        message: "Rule '${rule.name}' created successfully"
    ]
}

def toolUpdateRule(ruleId, args) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    // Update fields that are provided
    if (args.name != null) rule.name = args.name.trim()
    if (args.description != null) rule.description = args.description
    if (args.triggers != null) {
        args.triggers.each { validateTrigger(it) }
        rule.triggers = args.triggers
    }
    if (args.conditions != null) {
        args.conditions.each { validateCondition(it) }
        rule.conditions = args.conditions
    }
    if (args.conditionLogic != null) rule.conditionLogic = args.conditionLogic
    if (args.actions != null) {
        args.actions.each { validateAction(it) }
        rule.actions = args.actions
    }
    if (args.enabled != null) rule.enabled = args.enabled

    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    // Refresh subscriptions
    refreshAllSubscriptions()

    return [
        success: true,
        ruleId: ruleId,
        message: "Rule '${rule.name}' updated successfully"
    ]
}

def toolDeleteRule(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    def ruleName = rule.name
    state.rules.remove(ruleId)

    // Refresh subscriptions
    refreshAllSubscriptions()

    return [
        success: true,
        message: "Rule '${ruleName}' deleted successfully"
    ]
}

def toolEnableRule(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    rule.enabled = true
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    // Refresh subscriptions
    refreshAllSubscriptions()

    return [
        success: true,
        message: "Rule '${rule.name}' enabled"
    ]
}

def toolDisableRule(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    rule.enabled = false
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    // Refresh subscriptions
    refreshAllSubscriptions()

    return [
        success: true,
        message: "Rule '${rule.name}' disabled"
    ]
}

def toolTestRule(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        throw new IllegalArgumentException("Rule not found: ${ruleId}")
    }

    // Evaluate conditions
    def conditionResults = rule.conditions?.collect { condition ->
        [
            condition: condition,
            result: evaluateCondition(condition),
            description: describeCondition(condition)
        ]
    } ?: []

    def allConditionsMet = conditionResults.isEmpty() ||
        (rule.conditionLogic == "any" ? conditionResults.any { it.result } : conditionResults.every { it.result })

    // Describe what actions would execute
    def actionDescriptions = rule.actions?.collect { action ->
        describeAction(action)
    } ?: []

    return [
        ruleId: ruleId,
        ruleName: rule.name,
        conditionsMet: allConditionsMet,
        conditionResults: conditionResults,
        wouldExecute: allConditionsMet,
        actions: actionDescriptions,
        message: allConditionsMet ? "Rule would execute ${actionDescriptions.size()} actions" : "Rule would NOT execute (conditions not met)"
    ]
}

// ==================== SYSTEM TOOLS ====================

def toolGetHubInfo() {
    return [
        name: location.hub.name,
        model: location.hub.hardwareID,
        firmwareVersion: location.hub.firmwareVersionString,
        localIP: location.hub.localIP,
        uptime: location.hub.uptime,
        zigbeeChannel: location.hub.zigbeeChannel,
        zwaveVersion: location.hub.zwaveVersion,
        timeZone: location.timeZone?.ID,
        temperatureScale: location.temperatureScale,
        latitude: location.latitude,
        longitude: location.longitude
    ]
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
    // Get hub connector variables
    def hubVariables = getAllGlobalConnectorVariables()?.collect { name, var ->
        [name: name, value: var.value, type: var.type, source: "hub"]
    } ?: []

    // Get rule engine variables
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
    // Check hub connector variables first
    def hubVar = getGlobalConnectorVariable(name)
    if (hubVar != null) {
        return [name: name, value: hubVar, source: "hub"]
    }

    // Check rule engine variables
    def ruleVar = state.ruleVariables?.get(name)
    if (ruleVar != null) {
        return [name: name, value: ruleVar, source: "rule_engine"]
    }

    throw new IllegalArgumentException("Variable not found: ${name}")
}

def toolSetVariable(name, value) {
    // Try to set hub connector variable first
    try {
        setGlobalConnectorVariable(name, value)
        return [success: true, name: name, value: value, source: "hub"]
    } catch (Exception e) {
        // Fall back to rule engine variables
        if (!state.ruleVariables) state.ruleVariables = [:]
        state.ruleVariables[name] = value
        return [success: true, name: name, value: value, source: "rule_engine"]
    }
}

// ==================== RULE ENGINE ====================

def refreshAllSubscriptions() {
    unsubscribe()

    if (settings.enableRuleEngine == false) {
        logDebug("Rule engine disabled, skipping subscriptions")
        return
    }

    state.rules?.each { ruleId, rule ->
        if (rule.enabled) {
            subscribeToRuleTriggers(ruleId, rule)
        }
    }

    logDebug("Refreshed subscriptions for ${state.rules?.count { k, v -> v.enabled } ?: 0} enabled rules")
}

def subscribeToRuleTriggers(ruleId, rule) {
    rule.triggers?.each { trigger ->
        switch (trigger.type) {
            case "device_event":
                def device = findDevice(trigger.deviceId)
                if (device) {
                    subscribe(device, trigger.attribute, "handleDeviceEvent", [filterEvents: false])
                    logDebug("Subscribed to ${device.label} ${trigger.attribute} for rule ${rule.name}")
                }
                break
            case "time":
                if (trigger.time) {
                    schedule(trigger.time, "handleTimeTrigger", [data: [ruleId: ruleId]])
                    logDebug("Scheduled time trigger at ${trigger.time} for rule ${rule.name}")
                }
                break
            case "mode_change":
                subscribe(location, "mode", "handleModeChange")
                logDebug("Subscribed to mode changes for rule ${rule.name}")
                break
        }
    }
}

def handleDeviceEvent(evt) {
    logDebug("Device event: ${evt.device.label} ${evt.name} = ${evt.value}")

    state.rules?.each { ruleId, rule ->
        if (!rule.enabled) return

        rule.triggers?.each { trigger ->
            if (trigger.type == "device_event" &&
                trigger.deviceId?.toString() == evt.device.id.toString() &&
                trigger.attribute == evt.name) {

                // Check if trigger value matches (if specified)
                def valueMatches = !trigger.value ||
                    evaluateOperator(evt.value, trigger.operator ?: "equals", trigger.value)

                if (valueMatches) {
                    logDebug("Trigger matched for rule: ${rule.name}")
                    evaluateAndExecuteRule(ruleId, rule, evt)
                }
            }
        }
    }
}

def handleTimeTrigger(data) {
    def ruleId = data.ruleId
    def rule = state.rules?.get(ruleId)

    if (rule?.enabled) {
        logDebug("Time trigger fired for rule: ${rule.name}")
        evaluateAndExecuteRule(ruleId, rule, null)
    }
}

def handleModeChange(evt) {
    logDebug("Mode changed to: ${evt.value}")

    state.rules?.each { ruleId, rule ->
        if (!rule.enabled) return

        rule.triggers?.each { trigger ->
            if (trigger.type == "mode_change") {
                def matches = true
                if (trigger.toMode && trigger.toMode != evt.value) matches = false
                if (trigger.fromMode && trigger.fromMode != state.previousMode) matches = false

                if (matches) {
                    logDebug("Mode trigger matched for rule: ${rule.name}")
                    evaluateAndExecuteRule(ruleId, rule, evt)
                }
            }
        }
    }

    state.previousMode = evt.value
}

def evaluateAndExecuteRule(ruleId, rule, evt) {
    // Evaluate conditions
    def conditionsMet = evaluateConditions(rule)

    if (conditionsMet) {
        log.info "Executing rule: ${rule.name}"

        // Update execution stats
        rule.lastTriggered = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        rule.executionCount = (rule.executionCount ?: 0) + 1
        state.rules[ruleId] = rule

        // Execute actions
        executeActions(ruleId, rule.actions, evt)
    } else {
        logDebug("Rule ${rule.name} conditions not met, skipping execution")
    }
}

def evaluateConditions(rule) {
    if (!rule.conditions || rule.conditions.size() == 0) {
        return true
    }

    def results = rule.conditions.collect { condition ->
        evaluateCondition(condition)
    }

    return rule.conditionLogic == "any" ? results.any { it } : results.every { it }
}

def evaluateCondition(condition) {
    try {
        switch (condition.type) {
            case "device_state":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def currentValue = device.currentValue(condition.attribute)
                return evaluateOperator(currentValue, condition.operator ?: "equals", condition.value)

            case "time_range":
                return isTimeInRange(condition.startTime, condition.endTime)

            case "mode":
                def currentMode = location.mode
                if (condition.operator == "not_in") {
                    return !condition.modes?.contains(currentMode)
                }
                return condition.modes?.contains(currentMode)

            case "variable":
                def varValue = toolGetVariable(condition.variableName)?.value
                return evaluateOperator(varValue, condition.operator ?: "equals", condition.value)

            case "days_of_week":
                def today = new Date().format("EEEE")
                return condition.days?.contains(today)

            default:
                logDebug("Unknown condition type: ${condition.type}")
                return true
        }
    } catch (Exception e) {
        log.warn "Error evaluating condition: ${e.message}"
        return false
    }
}

def executeActions(ruleId, actions, evt) {
    actions?.eachWithIndex { action, index ->
        try {
            switch (action.type) {
                case "device_command":
                    def device = findDevice(action.deviceId)
                    if (device) {
                        if (action.parameters && action.parameters.size() > 0) {
                            def params = action.parameters.collect { convertParameter(it) }
                            device."${action.command}"(*params)
                        } else {
                            device."${action.command}"()
                        }
                        logDebug("Executed ${action.command} on ${device.label}")
                    }
                    break

                case "set_variable":
                    toolSetVariable(action.variableName, action.value)
                    logDebug("Set variable ${action.variableName} = ${action.value}")
                    break

                case "set_mode":
                    location.setMode(action.mode)
                    logDebug("Set mode to ${action.mode}")
                    break

                case "delay":
                    def remainingActions = actions.drop(index + 1)
                    if (remainingActions.size() > 0) {
                        runIn(action.seconds ?: 1, "executeDelayedActions",
                              [data: [ruleId: ruleId, actions: remainingActions]])
                        logDebug("Scheduled ${remainingActions.size()} actions after ${action.seconds}s delay")
                    }
                    return // Exit loop, remaining actions are scheduled

                case "if_then_else":
                    def conditionMet = evaluateCondition(action.condition)
                    if (conditionMet && action.thenActions) {
                        executeActions(ruleId, action.thenActions, evt)
                    } else if (!conditionMet && action.elseActions) {
                        executeActions(ruleId, action.elseActions, evt)
                    }
                    break

                case "cancel_delayed":
                    unschedule("executeDelayedActions")
                    logDebug("Cancelled all delayed actions")
                    break

                default:
                    logDebug("Unknown action type: ${action.type}")
            }
        } catch (Exception e) {
            log.error "Error executing action: ${e.message}"
        }
    }
}

def executeDelayedActions(data) {
    def ruleId = data.ruleId
    def rule = state.rules?.get(ruleId)

    if (rule?.enabled) {
        logDebug("Executing delayed actions for rule: ${rule.name}")
        executeActions(ruleId, data.actions, null)
    }
}

// ==================== VALIDATION HELPERS ====================

def validateTrigger(trigger) {
    if (!trigger.type) {
        throw new IllegalArgumentException("Trigger type is required")
    }

    switch (trigger.type) {
        case "device_event":
            if (!trigger.deviceId) {
                throw new IllegalArgumentException("Device ID is required for device_event trigger")
            }
            if (!trigger.attribute) {
                throw new IllegalArgumentException("Attribute is required for device_event trigger")
            }
            if (!findDevice(trigger.deviceId)) {
                throw new IllegalArgumentException("Device not found or not authorized: ${trigger.deviceId}")
            }
            break
        case "time":
            if (!trigger.time) {
                throw new IllegalArgumentException("Time is required for time trigger (HH:mm format)")
            }
            break
        case "mode_change":
            // No additional validation needed
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
            if (!condition.deviceId || !condition.attribute) {
                throw new IllegalArgumentException("Device ID and attribute required for device_state condition")
            }
            break
        case "time_range":
            if (!condition.startTime || !condition.endTime) {
                throw new IllegalArgumentException("Start and end time required for time_range condition")
            }
            break
        case "mode":
            if (!condition.modes || condition.modes.size() == 0) {
                throw new IllegalArgumentException("Modes array required for mode condition")
            }
            break
        case "variable":
            if (!condition.variableName) {
                throw new IllegalArgumentException("Variable name required for variable condition")
            }
            break
    }
}

def validateAction(action) {
    if (!action.type) {
        throw new IllegalArgumentException("Action type is required")
    }

    switch (action.type) {
        case "device_command":
            if (!action.deviceId || !action.command) {
                throw new IllegalArgumentException("Device ID and command required for device_command action")
            }
            if (!findDevice(action.deviceId)) {
                throw new IllegalArgumentException("Device not found or not authorized: ${action.deviceId}")
            }
            break
        case "set_variable":
            if (!action.variableName) {
                throw new IllegalArgumentException("Variable name required for set_variable action")
            }
            break
        case "set_mode":
            if (!action.mode) {
                throw new IllegalArgumentException("Mode required for set_mode action")
            }
            break
        case "delay":
            if (!action.seconds || action.seconds < 1) {
                throw new IllegalArgumentException("Seconds (positive integer) required for delay action")
            }
            break
        case "if_then_else":
            if (!action.condition) {
                throw new IllegalArgumentException("Condition required for if_then_else action")
            }
            validateCondition(action.condition)
            break
    }
}

// ==================== UTILITY HELPERS ====================

def findDevice(deviceId) {
    return selectedDevices?.find { it.id.toString() == deviceId?.toString() }
}

def evaluateOperator(actual, operator, expected) {
    def actualStr = actual?.toString()
    def expectedStr = expected?.toString()

    switch (operator) {
        case "equals":
        case "==":
            return actualStr == expectedStr
        case "not_equals":
        case "!=":
            return actualStr != expectedStr
        case "greater_than":
        case ">":
            return toNumber(actual) > toNumber(expected)
        case "less_than":
        case "<":
            return toNumber(actual) < toNumber(expected)
        case "greater_or_equal":
        case ">=":
            return toNumber(actual) >= toNumber(expected)
        case "less_or_equal":
        case "<=":
            return toNumber(actual) <= toNumber(expected)
        case "contains":
            return actualStr?.contains(expectedStr)
        case "starts_with":
            return actualStr?.startsWith(expectedStr)
        case "ends_with":
            return actualStr?.endsWith(expectedStr)
        default:
            return actualStr == expectedStr
    }
}

def toNumber(value) {
    if (value == null) return 0
    if (value instanceof Number) return value
    try {
        return value.toString().toDouble()
    } catch (Exception e) {
        return 0
    }
}

def convertParameter(param) {
    if (param == null) return null
    def str = param.toString()

    // Try to convert to number
    if (str.isNumber()) {
        return str.contains(".") ? str.toDouble() : str.toInteger()
    }

    // Boolean
    if (str.equalsIgnoreCase("true")) return true
    if (str.equalsIgnoreCase("false")) return false

    return str
}

def isTimeInRange(startTime, endTime) {
    try {
        def now = new Date()
        def start = Date.parse("HH:mm", startTime)
        def end = Date.parse("HH:mm", endTime)

        // Set to today's date
        def cal = Calendar.getInstance()
        cal.setTime(now)
        def today = cal.get(Calendar.DAY_OF_YEAR)

        cal.setTime(start)
        cal.set(Calendar.DAY_OF_YEAR, today)
        start = cal.getTime()

        cal.setTime(end)
        cal.set(Calendar.DAY_OF_YEAR, today)
        end = cal.getTime()

        // Handle overnight ranges
        if (end.before(start)) {
            return now.after(start) || now.before(end)
        }

        return now.after(start) && now.before(end)
    } catch (Exception e) {
        log.warn "Error parsing time range: ${e.message}"
        return true
    }
}

def describeCondition(condition) {
    switch (condition.type) {
        case "device_state":
            def device = findDevice(condition.deviceId)
            return "Device '${device?.label ?: condition.deviceId}' ${condition.attribute} ${condition.operator ?: 'equals'} '${condition.value}'"
        case "time_range":
            return "Time between ${condition.startTime} and ${condition.endTime}"
        case "mode":
            return "Mode ${condition.operator == 'not_in' ? 'not in' : 'in'} [${condition.modes?.join(', ')}]"
        case "variable":
            return "Variable '${condition.variableName}' ${condition.operator ?: 'equals'} '${condition.value}'"
        default:
            return "Unknown condition: ${condition.type}"
    }
}

def describeAction(action) {
    switch (action.type) {
        case "device_command":
            def device = findDevice(action.deviceId)
            def params = action.parameters ? "(${action.parameters.join(', ')})" : ""
            return "Send '${action.command}${params}' to '${device?.label ?: action.deviceId}'"
        case "set_variable":
            return "Set variable '${action.variableName}' to '${action.value}'"
        case "set_mode":
            return "Set mode to '${action.mode}'"
        case "delay":
            return "Wait ${action.seconds} seconds"
        case "if_then_else":
            return "IF ${describeCondition(action.condition)} THEN (${action.thenActions?.size() ?: 0} actions) ELSE (${action.elseActions?.size() ?: 0} actions)"
        case "cancel_delayed":
            return "Cancel all delayed actions"
        default:
            return "Unknown action: ${action.type}"
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
