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
    if (!state.pendingDurationTriggers) {
        state.pendingDurationTriggers = [:]
    }
    if (!state.delayedActionGroups) {
        state.delayedActionGroups = [:]
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
            version: "0.0.3"
        ],
        instructions: """Hubitat MCP Server with Rule Engine.

Available tools:
- Device: list_devices, get_device, get_attribute, send_command, get_device_events
- Rules: list_rules, get_rule, create_rule, update_rule, delete_rule, enable_rule, disable_rule, test_rule
- System: get_hub_info, get_modes, set_mode, get_hsm_status, set_hsm, list_variables, get_variable, set_variable

Rule Engine supports:
TRIGGERS: device_event (with duration for debouncing), button_event (pushed/held/doubleTapped), time (HH:mm or sunrise/sunset with offset), mode_change, hsm_change
CONDITIONS: device_state, device_was (state for X seconds), time_range (supports sunrise/sunset), mode, variable, days_of_week, sun_position, hsm_status
ACTIONS: device_command, toggle_device, activate_scene, set_variable, set_local_variable, set_mode, set_hsm, delay (with ID for targeted cancel), if_then_else, cancel_delayed, repeat, stop, log"""
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
  "actions": [{"type": "device_command", "deviceId": "789", "command": "on"}],
  "localVariables": {"myVar": "initialValue"}
}

TRIGGER TYPES:
- device_event: {deviceId, attribute, value?, operator?, duration?} - duration in seconds for debouncing (e.g., "temp > 78 for 300s")
- button_event: {deviceId, buttonNumber?, action: "pushed"|"held"|"doubleTapped"|"released"}
- time: {time: "HH:mm"} or {sunrise: true, offset?: -30} or {sunset: true, offset?: 30}
- periodic: {interval: minutes, unit?: "minutes"|"hours"|"days"} - recurring schedule
- mode_change: {toMode?, fromMode?}
- hsm_change: {status: "armedAway"|"armedHome"|"armedNight"|"disarmed"|"intrusion"}

CONDITION TYPES:
- device_state: {deviceId, attribute, operator, value}
- time_range: {startTime, endTime} - supports "sunrise"/"sunset" keywords
- mode: {modes: ["Home", "Away"], operator?: "in"|"not_in"}
- variable: {variableName, operator, value}
- days_of_week: {days: ["Monday", "Tuesday"]}
- device_was: {deviceId, attribute, value, forSeconds} - "device has been X for Y seconds"
- sun_position: {position: "up"|"down"} - is it day or night
- presence: {deviceId, status: "present"|"not present"} - presence sensor check
- lock: {deviceId, status: "locked"|"unlocked"} - lock status check
- thermostat_mode: {deviceId, mode: "heat"|"cool"|"auto"|"off"} - HVAC mode
- thermostat_state: {deviceId, state: "heating"|"cooling"|"idle"} - HVAC operating state
- illuminance: {deviceId, operator, value} - light level check
- power: {deviceId, operator, value} - power meter check

ACTION TYPES:
- device_command: {deviceId, command, parameters?}
- toggle_device: {deviceId, attribute?: "switch"} - toggle on/off
- activate_scene: {sceneDeviceId} - activate a scene device
- set_level: {deviceId, level, duration?} - set dimmer level with optional fade time
- set_color: {deviceId, hue, saturation, level?} - set color bulb
- set_color_temperature: {deviceId, colorTemperature, level?} - set CT bulb
- lock: {deviceId} - lock a lock device
- unlock: {deviceId} - unlock a lock device
- capture_state: {devices: [deviceIds], stateId} - save current state of devices
- restore_state: {stateId} - restore previously captured state
- send_notification: {message, title?} - send push notification via hub
- set_variable: {variableName, value, scope?: "rule"|"global"}
- set_local_variable: {variableName, value} - rule-scoped variable
- set_mode: {mode}
- delay: {seconds, delayId?} - optional delayId for targeted cancellation
- if_then_else: {condition, thenActions, elseActions?}
- cancel_delayed: {delayId?: "all"} - cancel specific delay or all
- repeat: {times, actions, delayBetween?} - repeat actions N times
- stop: {} - stop rule execution
- log: {message, level?: "info"|"warn"|"debug"}""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Rule name"],
                    description: [type: "string", description: "Rule description"],
                    triggers: [type: "array", description: "Array of trigger objects"],
                    conditions: [type: "array", description: "Array of condition objects"],
                    conditionLogic: [type: "string", enum: ["all", "any"], default: "all"],
                    actions: [type: "array", description: "Array of action objects"],
                    localVariables: [type: "object", description: "Initial values for rule-local variables"],
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
        ],
        [
            name: "get_hsm_status",
            description: "Get current HSM (Home Security Monitor) status and available arm modes",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "set_hsm",
            description: "Set HSM arm mode (armedAway, armedHome, armedNight, disarm, armAll, disarmAll)",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", description: "HSM mode: armedAway, armedHome, armedNight, disarm, armAll, disarmAll"]
                ],
                required: ["mode"]
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
        case "get_hsm_status": return toolGetHsmStatus()
        case "set_hsm": return toolSetHsm(args.mode)
        case "get_attribute": return toolGetAttribute(args.deviceId, args.attribute)

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
        localVariables: args.localVariables ?: [:],
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

def toolGetHsmStatus() {
    def hsmStatus = location.hsmStatus
    def hsmAlerts = location.hsmAlert

    return [
        status: hsmStatus ?: "unconfigured",
        alerts: hsmAlerts,
        availableModes: ["armedAway", "armedHome", "armedNight", "disarmed"],
        armCommands: ["armAway", "armHome", "armNight", "disarm", "armAll", "disarmAll", "cancelAlerts"]
    ]
}

def toolSetHsm(mode) {
    def validModes = ["armAway", "armHome", "armNight", "disarm", "armAll", "disarmAll", "cancelAlerts",
                      "armedAway", "armedHome", "armedNight", "disarmed"]

    if (!validModes.contains(mode)) {
        throw new IllegalArgumentException("Invalid HSM mode: ${mode}. Valid: ${validModes}")
    }

    // Convert status names to command names if needed
    def command = mode
    if (mode == "armedAway") command = "armAway"
    if (mode == "armedHome") command = "armHome"
    if (mode == "armedNight") command = "armNight"
    if (mode == "disarmed") command = "disarm"

    sendLocationEvent(name: "hsmSetArm", value: command)

    return [
        success: true,
        command: command,
        message: "HSM command '${command}' sent"
    ]
}

def toolGetAttribute(deviceId, attribute) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    def value = device.currentValue(attribute)
    def supportedAttrs = device.supportedAttributes?.collect { it.name }

    if (!supportedAttrs?.contains(attribute)) {
        throw new IllegalArgumentException("Attribute '${attribute}' not found on device. Available: ${supportedAttrs}")
    }

    return [
        device: device.label,
        deviceId: device.id.toString(),
        attribute: attribute,
        value: value,
        dataType: device.supportedAttributes?.find { it.name == attribute }?.dataType
    ]
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
                    subscribe(device, trigger.attribute, "handleDeviceEvent")
                    logDebug("Subscribed to ${device.label} ${trigger.attribute} for rule ${rule.name}")
                }
                break
            case "button_event":
                def device = findDevice(trigger.deviceId)
                if (device) {
                    def buttonAction = trigger.action ?: "pushed"
                    subscribe(device, buttonAction, "handleButtonEvent")
                    logDebug("Subscribed to ${device.label} ${buttonAction} for rule ${rule.name}")
                }
                break
            case "time":
                if (trigger.time) {
                    schedule(trigger.time, "handleTimeTrigger", [data: [ruleId: ruleId]])
                    logDebug("Scheduled time trigger at ${trigger.time} for rule ${rule.name}")
                } else if (trigger.sunrise) {
                    def offset = trigger.offset ?: 0
                    def sunriseTime = getSunriseAndSunset().sunrise
                    def triggerTime = new Date(sunriseTime.time + (offset * 60 * 1000))
                    schedule(triggerTime, "handleTimeTrigger", [data: [ruleId: ruleId, type: "sunrise"]])
                    logDebug("Scheduled sunrise trigger (offset ${offset}min) for rule ${rule.name}")
                } else if (trigger.sunset) {
                    def offset = trigger.offset ?: 0
                    def sunsetTime = getSunriseAndSunset().sunset
                    def triggerTime = new Date(sunsetTime.time + (offset * 60 * 1000))
                    schedule(triggerTime, "handleTimeTrigger", [data: [ruleId: ruleId, type: "sunset"]])
                    logDebug("Scheduled sunset trigger (offset ${offset}min) for rule ${rule.name}")
                }
                break
            case "mode_change":
                subscribe(location, "mode", "handleModeChange")
                logDebug("Subscribed to mode changes for rule ${rule.name}")
                break
            case "hsm_change":
                subscribe(location, "hsmStatus", "handleHsmChange")
                subscribe(location, "hsmAlert", "handleHsmAlert")
                logDebug("Subscribed to HSM changes for rule ${rule.name}")
                break
            case "periodic":
                def interval = trigger.interval ?: 60
                def unit = trigger.unit ?: "minutes"
                def cronExpr
                switch (unit) {
                    case "minutes":
                        cronExpr = "0 0/${interval} * ? * *"
                        break
                    case "hours":
                        cronExpr = "0 0 0/${interval} ? * *"
                        break
                    case "days":
                        cronExpr = "0 0 0 1/${interval} * ?"
                        break
                    default:
                        cronExpr = "0 0/${interval} * ? * *"
                }
                schedule(cronExpr, "handlePeriodicTrigger", [data: [ruleId: ruleId]])
                logDebug("Scheduled periodic trigger every ${interval} ${unit} for rule ${rule.name}")
                break
        }
    }
}

def handlePeriodicTrigger(data) {
    def ruleId = data.ruleId
    def rule = state.rules?.get(ruleId)

    if (rule?.enabled) {
        logDebug("Periodic trigger fired for rule: ${rule.name}")
        evaluateAndExecuteRule(ruleId, rule, null)
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
                    // Check for duration requirement (debouncing)
                    if (trigger.duration && trigger.duration > 0) {
                        handleDurationTrigger(ruleId, rule, trigger, evt)
                    } else {
                        logDebug("Trigger matched for rule: ${rule.name}")
                        evaluateAndExecuteRule(ruleId, rule, evt)
                    }
                } else {
                    // Value didn't match - cancel any pending duration trigger
                    cancelDurationTrigger(ruleId, trigger)
                }
            }
        }
    }
}

def handleButtonEvent(evt) {
    logDebug("Button event: ${evt.device.label} ${evt.name} = ${evt.value}")

    state.rules?.each { ruleId, rule ->
        if (!rule.enabled) return

        rule.triggers?.each { trigger ->
            if (trigger.type == "button_event" &&
                trigger.deviceId?.toString() == evt.device.id.toString() &&
                trigger.action == evt.name) {

                // Check button number if specified
                def buttonMatches = !trigger.buttonNumber ||
                    trigger.buttonNumber.toString() == evt.value?.toString()

                if (buttonMatches) {
                    logDebug("Button trigger matched for rule: ${rule.name}")
                    evaluateAndExecuteRule(ruleId, rule, evt)
                }
            }
        }
    }
}

def handleHsmChange(evt) {
    logDebug("HSM status changed: ${evt.value}")

    state.rules?.each { ruleId, rule ->
        if (!rule.enabled) return

        rule.triggers?.each { trigger ->
            if (trigger.type == "hsm_change") {
                def matches = !trigger.status || trigger.status == evt.value
                if (matches) {
                    logDebug("HSM trigger matched for rule: ${rule.name}")
                    evaluateAndExecuteRule(ruleId, rule, evt)
                }
            }
        }
    }
}

def handleHsmAlert(evt) {
    logDebug("HSM alert: ${evt.value}")

    state.rules?.each { ruleId, rule ->
        if (!rule.enabled) return

        rule.triggers?.each { trigger ->
            if (trigger.type == "hsm_change" && trigger.status == "intrusion") {
                if (evt.value == "intrusion") {
                    logDebug("HSM intrusion trigger matched for rule: ${rule.name}")
                    evaluateAndExecuteRule(ruleId, rule, evt)
                }
            }
        }
    }
}

// Duration trigger handling (debouncing)
def handleDurationTrigger(ruleId, rule, trigger, evt) {
    def triggerId = "${ruleId}-${trigger.deviceId}-${trigger.attribute}"

    // Check if we already have a pending trigger
    if (state.pendingDurationTriggers?.containsKey(triggerId)) {
        logDebug("Duration trigger already pending for ${rule.name}, waiting...")
        return
    }

    // Start the duration timer
    if (!state.pendingDurationTriggers) state.pendingDurationTriggers = [:]
    state.pendingDurationTriggers[triggerId] = [
        ruleId: ruleId,
        startTime: now(),
        requiredValue: trigger.value,
        attribute: trigger.attribute,
        deviceId: trigger.deviceId
    ]

    logDebug("Starting ${trigger.duration}s duration timer for rule: ${rule.name}")
    runIn(trigger.duration, "checkDurationTrigger", [data: [triggerId: triggerId, ruleId: ruleId]])
}

def cancelDurationTrigger(ruleId, trigger) {
    def triggerId = "${ruleId}-${trigger.deviceId}-${trigger.attribute}"
    if (state.pendingDurationTriggers?.containsKey(triggerId)) {
        state.pendingDurationTriggers.remove(triggerId)
        logDebug("Cancelled duration trigger ${triggerId} - condition no longer met")
    }
}

def checkDurationTrigger(data) {
    def triggerId = data.triggerId
    def ruleId = data.ruleId

    def pending = state.pendingDurationTriggers?.get(triggerId)
    if (!pending) {
        logDebug("Duration trigger ${triggerId} was cancelled")
        return
    }

    def rule = state.rules?.get(ruleId)
    if (!rule?.enabled) {
        state.pendingDurationTriggers.remove(triggerId)
        return
    }

    // Verify the condition is still met
    def device = findDevice(pending.deviceId)
    if (!device) {
        state.pendingDurationTriggers.remove(triggerId)
        return
    }

    def currentValue = device.currentValue(pending.attribute)
    def trigger = rule.triggers?.find {
        it.type == "device_event" &&
        it.deviceId?.toString() == pending.deviceId?.toString() &&
        it.attribute == pending.attribute
    }

    if (trigger && evaluateOperator(currentValue, trigger.operator ?: "equals", trigger.value)) {
        logDebug("Duration condition still met after ${trigger.duration}s - executing rule: ${rule.name}")
        state.pendingDurationTriggers.remove(triggerId)
        evaluateAndExecuteRule(ruleId, rule, null)
    } else {
        logDebug("Duration condition no longer met for rule: ${rule.name}")
        state.pendingDurationTriggers.remove(triggerId)
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

def evaluateCondition(condition, ruleId = null) {
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
                def varValue
                // Check rule-local variable first if ruleId provided
                if (ruleId && state.rules?.get(ruleId)?.localVariables?.containsKey(condition.variableName)) {
                    varValue = state.rules.get(ruleId).localVariables.get(condition.variableName)
                } else {
                    varValue = toolGetVariable(condition.variableName)?.value
                }
                return evaluateOperator(varValue, condition.operator ?: "equals", condition.value)

            case "days_of_week":
                def today = new Date().format("EEEE")
                return condition.days?.contains(today)

            case "device_was":
                // Check if device has been in a state for a certain time
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def currentValue = device.currentValue(condition.attribute)

                // First check current state matches
                if (!evaluateOperator(currentValue, condition.operator ?: "equals", condition.value)) {
                    return false
                }

                // Then check how long it's been in this state
                def events = device.events(max: 10)?.findAll { it.name == condition.attribute }
                if (events && events.size() > 0) {
                    def lastChange = events.find { it.value?.toString() != condition.value?.toString() }
                    if (lastChange) {
                        def secondsSinceChange = (now() - lastChange.date.time) / 1000
                        return secondsSinceChange >= (condition.forSeconds ?: 0)
                    }
                }
                // If no contrary event found, assume it's been in this state long enough
                return true

            case "sun_position":
                def sunTimes = getSunriseAndSunset()
                def nowTime = now()
                def isDay = nowTime >= sunTimes.sunrise.time && nowTime < sunTimes.sunset.time
                return condition.position == "up" ? isDay : !isDay

            case "hsm_status":
                def hsmStatus = location.hsmStatus
                if (condition.operator == "not_equals" || condition.operator == "!=") {
                    return hsmStatus != condition.status
                }
                return hsmStatus == condition.status

            case "presence":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def presenceVal = device.currentValue("presence")
                return presenceVal == condition.status

            case "lock":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def lockVal = device.currentValue("lock")
                return lockVal == condition.status

            case "thermostat_mode":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def modeVal = device.currentValue("thermostatMode")
                return modeVal == condition.mode

            case "thermostat_state":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def stateVal = device.currentValue("thermostatOperatingState")
                return stateVal == condition.state

            case "illuminance":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def luxVal = device.currentValue("illuminance")
                return evaluateOperator(luxVal, condition.operator ?: "equals", condition.value)

            case "power":
                def device = findDevice(condition.deviceId)
                if (!device) return false
                def powerVal = device.currentValue("power")
                return evaluateOperator(powerVal, condition.operator ?: "equals", condition.value)

            default:
                logDebug("Unknown condition type: ${condition.type}")
                return true
        }
    } catch (Exception e) {
        log.warn "Error evaluating condition: ${e.message}"
        return false
    }
}

def executeActions(ruleId, actions, evt, executionContext = [:]) {
    def shouldStop = false

    actions?.eachWithIndex { action, index ->
        if (shouldStop) return

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

                case "toggle_device":
                    def device = findDevice(action.deviceId)
                    if (device) {
                        def attr = action.attribute ?: "switch"
                        def currentState = device.currentValue(attr)
                        if (currentState == "on") {
                            device.off()
                            logDebug("Toggled ${device.label} OFF")
                        } else {
                            device.on()
                            logDebug("Toggled ${device.label} ON")
                        }
                    }
                    break

                case "activate_scene":
                    def sceneDevice = findDevice(action.sceneDeviceId)
                    if (sceneDevice) {
                        if (sceneDevice.hasCommand("on")) {
                            sceneDevice.on()
                        } else if (sceneDevice.hasCommand("push")) {
                            sceneDevice.push()
                        } else if (sceneDevice.hasCommand("activate")) {
                            sceneDevice.activate()
                        }
                        logDebug("Activated scene ${sceneDevice.label}")
                    }
                    break

                case "set_level":
                    def device = findDevice(action.deviceId)
                    if (device) {
                        def level = action.level instanceof Number ? action.level : action.level?.toInteger() ?: 100
                        if (action.duration && device.hasCommand("setLevel")) {
                            device.setLevel(level, action.duration)
                        } else {
                            device.setLevel(level)
                        }
                        logDebug("Set ${device.label} level to ${level}")
                    }
                    break

                case "set_color":
                    def device = findDevice(action.deviceId)
                    if (device && device.hasCommand("setColor")) {
                        def colorMap = [hue: action.hue, saturation: action.saturation]
                        if (action.level) colorMap.level = action.level
                        device.setColor(colorMap)
                        logDebug("Set ${device.label} color to H:${action.hue} S:${action.saturation}")
                    }
                    break

                case "set_color_temperature":
                    def device = findDevice(action.deviceId)
                    if (device && device.hasCommand("setColorTemperature")) {
                        device.setColorTemperature(action.colorTemperature)
                        if (action.level && device.hasCommand("setLevel")) {
                            device.setLevel(action.level)
                        }
                        logDebug("Set ${device.label} CT to ${action.colorTemperature}K")
                    }
                    break

                case "lock":
                    def device = findDevice(action.deviceId)
                    if (device && device.hasCommand("lock")) {
                        device.lock()
                        logDebug("Locked ${device.label}")
                    }
                    break

                case "unlock":
                    def device = findDevice(action.deviceId)
                    if (device && device.hasCommand("unlock")) {
                        device.unlock()
                        logDebug("Unlocked ${device.label}")
                    }
                    break

                case "capture_state":
                    def stateId = action.stateId ?: "default"
                    def devices = action.devices?.collect { findDevice(it) }?.findAll { it != null }
                    if (devices) {
                        if (!state.capturedStates) state.capturedStates = [:]
                        state.capturedStates[stateId] = devices.collect { dev ->
                            [
                                deviceId: dev.id.toString(),
                                switch: dev.currentValue("switch"),
                                level: dev.currentValue("level")
                            ]
                        }
                        logDebug("Captured state of ${devices.size()} devices as '${stateId}'")
                    }
                    break

                case "restore_state":
                    def stateId = action.stateId ?: "default"
                    def captured = state.capturedStates?.get(stateId)
                    if (captured) {
                        captured.each { cap ->
                            def device = findDevice(cap.deviceId)
                            if (device) {
                                if (cap.switch == "on") {
                                    device.on()
                                    if (cap.level && device.hasCommand("setLevel")) {
                                        device.setLevel(cap.level)
                                    }
                                } else {
                                    device.off()
                                }
                            }
                        }
                        logDebug("Restored state '${stateId}' for ${captured.size()} devices")
                    }
                    break

                case "send_notification":
                    def msg = action.message ?: "Notification from MCP Rule"
                    def title = action.title ?: "MCP Rule"
                    sendPush("${title}: ${msg}")
                    logDebug("Sent notification: ${msg}")
                    break

                case "set_variable":
                    if (action.scope == "rule" || action.scope == "local") {
                        setRuleLocalVariable(ruleId, action.variableName, action.value)
                    } else {
                        toolSetVariable(action.variableName, action.value)
                    }
                    logDebug("Set variable ${action.variableName} = ${action.value}")
                    break

                case "set_local_variable":
                    setRuleLocalVariable(ruleId, action.variableName, action.value)
                    logDebug("Set local variable ${action.variableName} = ${action.value}")
                    break

                case "set_mode":
                    location.setMode(action.mode)
                    logDebug("Set mode to ${action.mode}")
                    break

                case "delay":
                    def remainingActions = actions.drop(index + 1)
                    if (remainingActions.size() > 0) {
                        def delayId = action.delayId ?: "default-${ruleId}-${now()}"

                        // Store delay info for potential cancellation
                        if (!state.delayedActionGroups) state.delayedActionGroups = [:]
                        state.delayedActionGroups[delayId] = [
                            ruleId: ruleId,
                            scheduledAt: now()
                        ]

                        runIn(action.seconds ?: 1, "executeDelayedActions",
                              [data: [ruleId: ruleId, actions: remainingActions, delayId: delayId]])
                        logDebug("Scheduled ${remainingActions.size()} actions after ${action.seconds}s delay (id: ${delayId})")
                    }
                    return // Exit loop, remaining actions are scheduled

                case "if_then_else":
                    def conditionMet = evaluateCondition(action.condition, ruleId)
                    if (conditionMet && action.thenActions) {
                        executeActions(ruleId, action.thenActions, evt, executionContext)
                    } else if (!conditionMet && action.elseActions) {
                        executeActions(ruleId, action.elseActions, evt, executionContext)
                    }
                    break

                case "cancel_delayed":
                    if (action.delayId && action.delayId != "all") {
                        // Cancel specific delay
                        state.delayedActionGroups?.remove(action.delayId)
                        logDebug("Cancelled delayed action group: ${action.delayId}")
                    } else {
                        // Cancel all delays for this rule
                        state.delayedActionGroups?.keySet()?.findAll {
                            it.startsWith("default-${ruleId}") || state.delayedActionGroups[it]?.ruleId == ruleId
                        }?.each { state.delayedActionGroups.remove(it) }
                        unschedule("executeDelayedActions")
                        logDebug("Cancelled all delayed actions")
                    }
                    break

                case "repeat":
                    def times = action.times ?: 1
                    def delayBetween = action.delayBetween ?: 0

                    if (delayBetween > 0) {
                        // Schedule repeated executions
                        (1..times).each { iteration ->
                            def delaySeconds = delayBetween * (iteration - 1)
                            if (delaySeconds == 0) {
                                executeActions(ruleId, action.actions, evt, executionContext)
                            } else {
                                runIn(delaySeconds, "executeDelayedActions",
                                      [data: [ruleId: ruleId, actions: action.actions, delayId: "repeat-${ruleId}-${iteration}"]])
                            }
                        }
                    } else {
                        // Execute immediately
                        (1..times).each {
                            executeActions(ruleId, action.actions, evt, executionContext)
                        }
                    }
                    logDebug("Repeated actions ${times} times")
                    break

                case "stop":
                    logDebug("Stopping rule execution")
                    shouldStop = true
                    return

                case "log":
                    def level = action.level ?: "info"
                    def message = action.message ?: "Rule ${ruleId} log"
                    switch (level) {
                        case "debug": log.debug message; break
                        case "warn": log.warn message; break
                        case "error": log.error message; break
                        default: log.info message
                    }
                    break

                case "set_hsm":
                    sendLocationEvent(name: "hsmSetArm", value: action.status)
                    logDebug("Set HSM to ${action.status}")
                    break

                default:
                    logDebug("Unknown action type: ${action.type}")
            }
        } catch (Exception e) {
            log.error "Error executing action: ${e.message}"
        }
    }
}

def setRuleLocalVariable(ruleId, varName, value) {
    def rule = state.rules?.get(ruleId)
    if (rule) {
        if (!rule.localVariables) rule.localVariables = [:]
        rule.localVariables[varName] = value
        state.rules[ruleId] = rule
    }
}

def getRuleLocalVariable(ruleId, varName) {
    return state.rules?.get(ruleId)?.localVariables?.get(varName)
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
            if (trigger.duration && trigger.duration < 0) {
                throw new IllegalArgumentException("Duration must be positive")
            }
            break
        case "button_event":
            if (!trigger.deviceId) {
                throw new IllegalArgumentException("Device ID is required for button_event trigger")
            }
            if (!findDevice(trigger.deviceId)) {
                throw new IllegalArgumentException("Device not found or not authorized: ${trigger.deviceId}")
            }
            if (!trigger.action) {
                throw new IllegalArgumentException("Action is required for button_event (pushed, held, doubleTapped, released)")
            }
            if (!["pushed", "held", "doubleTapped", "released"].contains(trigger.action)) {
                throw new IllegalArgumentException("Invalid button action: ${trigger.action}")
            }
            break
        case "time":
            if (!trigger.time && !trigger.sunrise && !trigger.sunset) {
                throw new IllegalArgumentException("Time, sunrise, or sunset is required for time trigger")
            }
            break
        case "mode_change":
            // No additional validation needed
            break
        case "hsm_change":
            // Optional status filter
            if (trigger.status && !["armedAway", "armedHome", "armedNight", "disarmed", "intrusion", "allDisarmed"].contains(trigger.status)) {
                throw new IllegalArgumentException("Invalid HSM status: ${trigger.status}")
            }
            break
        case "periodic":
            if (!trigger.interval || trigger.interval < 1) {
                throw new IllegalArgumentException("Interval (positive integer) required for periodic trigger")
            }
            if (trigger.unit && !["minutes", "hours", "days"].contains(trigger.unit)) {
                throw new IllegalArgumentException("Invalid unit for periodic trigger: ${trigger.unit}")
            }
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
        case "device_was":
            if (!condition.deviceId || !condition.attribute) {
                throw new IllegalArgumentException("Device ID and attribute required for device_was condition")
            }
            if (!condition.forSeconds || condition.forSeconds <= 0) {
                throw new IllegalArgumentException("forSeconds (positive integer) required for device_was condition")
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
        case "days_of_week":
            if (!condition.days || condition.days.size() == 0) {
                throw new IllegalArgumentException("Days array required for days_of_week condition")
            }
            break
        case "sun_position":
            if (!condition.position || !["up", "down"].contains(condition.position)) {
                throw new IllegalArgumentException("Position ('up' or 'down') required for sun_position condition")
            }
            break
        case "hsm_status":
            if (!condition.status) {
                throw new IllegalArgumentException("Status required for hsm_status condition")
            }
            break
        case "presence":
            if (!condition.deviceId) {
                throw new IllegalArgumentException("Device ID required for presence condition")
            }
            if (!condition.status || !["present", "not present"].contains(condition.status)) {
                throw new IllegalArgumentException("Status ('present' or 'not present') required for presence condition")
            }
            break
        case "lock":
            if (!condition.deviceId) {
                throw new IllegalArgumentException("Device ID required for lock condition")
            }
            if (!condition.status || !["locked", "unlocked"].contains(condition.status)) {
                throw new IllegalArgumentException("Status ('locked' or 'unlocked') required for lock condition")
            }
            break
        case "thermostat_mode":
            if (!condition.deviceId || !condition.mode) {
                throw new IllegalArgumentException("Device ID and mode required for thermostat_mode condition")
            }
            break
        case "thermostat_state":
            if (!condition.deviceId || !condition.state) {
                throw new IllegalArgumentException("Device ID and state required for thermostat_state condition")
            }
            break
        case "illuminance":
        case "power":
            if (!condition.deviceId) {
                throw new IllegalArgumentException("Device ID required for ${condition.type} condition")
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
        case "toggle_device":
            if (!action.deviceId) {
                throw new IllegalArgumentException("Device ID required for toggle_device action")
            }
            if (!findDevice(action.deviceId)) {
                throw new IllegalArgumentException("Device not found or not authorized: ${action.deviceId}")
            }
            break
        case "activate_scene":
            if (!action.sceneDeviceId) {
                throw new IllegalArgumentException("Scene device ID required for activate_scene action")
            }
            if (!findDevice(action.sceneDeviceId)) {
                throw new IllegalArgumentException("Scene device not found or not authorized: ${action.sceneDeviceId}")
            }
            break
        case "set_variable":
        case "set_local_variable":
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
            if (action.thenActions) {
                action.thenActions.each { validateAction(it) }
            }
            if (action.elseActions) {
                action.elseActions.each { validateAction(it) }
            }
            break
        case "cancel_delayed":
            // Optional delayId
            break
        case "repeat":
            if (!action.times || action.times < 1) {
                throw new IllegalArgumentException("times (positive integer) required for repeat action")
            }
            if (!action.actions || action.actions.size() == 0) {
                throw new IllegalArgumentException("actions array required for repeat action")
            }
            action.actions.each { validateAction(it) }
            break
        case "stop":
        case "log":
            // No additional validation
            break
        case "set_hsm":
            if (!action.status) {
                throw new IllegalArgumentException("Status required for set_hsm action")
            }
            break
        case "set_level":
            if (!action.deviceId) {
                throw new IllegalArgumentException("Device ID required for set_level action")
            }
            if (action.level == null) {
                throw new IllegalArgumentException("Level required for set_level action")
            }
            break
        case "set_color":
            if (!action.deviceId) {
                throw new IllegalArgumentException("Device ID required for set_color action")
            }
            if (action.hue == null || action.saturation == null) {
                throw new IllegalArgumentException("Hue and saturation required for set_color action")
            }
            break
        case "set_color_temperature":
            if (!action.deviceId || !action.colorTemperature) {
                throw new IllegalArgumentException("Device ID and colorTemperature required for set_color_temperature action")
            }
            break
        case "lock":
        case "unlock":
            if (!action.deviceId) {
                throw new IllegalArgumentException("Device ID required for ${action.type} action")
            }
            break
        case "capture_state":
            if (!action.devices || action.devices.size() == 0) {
                throw new IllegalArgumentException("Devices array required for capture_state action")
            }
            break
        case "restore_state":
            // stateId is optional, defaults to "default"
            break
        case "send_notification":
            if (!action.message) {
                throw new IllegalArgumentException("Message required for send_notification action")
            }
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
        def nowDate = new Date()
        def sunTimes = getSunriseAndSunset()

        // Parse start time (supports "sunrise", "sunset", or "HH:mm")
        def start
        if (startTime?.toLowerCase() == "sunrise") {
            start = sunTimes.sunrise
        } else if (startTime?.toLowerCase() == "sunset") {
            start = sunTimes.sunset
        } else {
            start = Date.parse("HH:mm", startTime)
            def cal = Calendar.getInstance()
            cal.setTime(nowDate)
            def today = cal.get(Calendar.DAY_OF_YEAR)
            cal.setTime(start)
            cal.set(Calendar.DAY_OF_YEAR, today)
            cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
            start = cal.getTime()
        }

        // Parse end time (supports "sunrise", "sunset", or "HH:mm")
        def end
        if (endTime?.toLowerCase() == "sunrise") {
            end = sunTimes.sunrise
        } else if (endTime?.toLowerCase() == "sunset") {
            end = sunTimes.sunset
        } else {
            end = Date.parse("HH:mm", endTime)
            def cal = Calendar.getInstance()
            cal.setTime(nowDate)
            def today = cal.get(Calendar.DAY_OF_YEAR)
            cal.setTime(end)
            cal.set(Calendar.DAY_OF_YEAR, today)
            cal.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
            end = cal.getTime()
        }

        // Handle overnight ranges
        if (end.before(start)) {
            return nowDate.after(start) || nowDate.before(end)
        }

        return nowDate.after(start) && nowDate.before(end)
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
        case "device_was":
            def device = findDevice(condition.deviceId)
            return "Device '${device?.label ?: condition.deviceId}' ${condition.attribute} has been '${condition.value}' for ${condition.forSeconds}s"
        case "time_range":
            return "Time between ${condition.startTime} and ${condition.endTime}"
        case "mode":
            return "Mode ${condition.operator == 'not_in' ? 'not in' : 'in'} [${condition.modes?.join(', ')}]"
        case "variable":
            return "Variable '${condition.variableName}' ${condition.operator ?: 'equals'} '${condition.value}'"
        case "days_of_week":
            return "Day is in [${condition.days?.join(', ')}]"
        case "sun_position":
            return "Sun is ${condition.position}"
        case "hsm_status":
            return "HSM status ${condition.operator ?: 'equals'} '${condition.status}'"
        case "presence":
            def device = findDevice(condition.deviceId)
            return "Presence '${device?.label ?: condition.deviceId}' is '${condition.status}'"
        case "lock":
            def device = findDevice(condition.deviceId)
            return "Lock '${device?.label ?: condition.deviceId}' is '${condition.status}'"
        case "thermostat_mode":
            def device = findDevice(condition.deviceId)
            return "Thermostat '${device?.label ?: condition.deviceId}' mode is '${condition.mode}'"
        case "thermostat_state":
            def device = findDevice(condition.deviceId)
            return "Thermostat '${device?.label ?: condition.deviceId}' is '${condition.state}'"
        case "illuminance":
            def device = findDevice(condition.deviceId)
            return "Illuminance '${device?.label ?: condition.deviceId}' ${condition.operator ?: 'equals'} ${condition.value} lux"
        case "power":
            def device = findDevice(condition.deviceId)
            return "Power '${device?.label ?: condition.deviceId}' ${condition.operator ?: 'equals'} ${condition.value} W"
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
        case "toggle_device":
            def device = findDevice(action.deviceId)
            return "Toggle '${device?.label ?: action.deviceId}'"
        case "activate_scene":
            def scene = findDevice(action.sceneDeviceId)
            return "Activate scene '${scene?.label ?: action.sceneDeviceId}'"
        case "set_variable":
            def scope = action.scope == "rule" || action.scope == "local" ? " (local)" : ""
            return "Set variable '${action.variableName}'${scope} to '${action.value}'"
        case "set_local_variable":
            return "Set local variable '${action.variableName}' to '${action.value}'"
        case "set_mode":
            return "Set mode to '${action.mode}'"
        case "delay":
            def idInfo = action.delayId ? " (id: ${action.delayId})" : ""
            return "Wait ${action.seconds} seconds${idInfo}"
        case "if_then_else":
            return "IF ${describeCondition(action.condition)} THEN (${action.thenActions?.size() ?: 0} actions) ELSE (${action.elseActions?.size() ?: 0} actions)"
        case "cancel_delayed":
            return action.delayId ? "Cancel delayed action '${action.delayId}'" : "Cancel all delayed actions"
        case "repeat":
            return "Repeat ${action.times} times: (${action.actions?.size() ?: 0} actions)"
        case "stop":
            return "Stop rule execution"
        case "log":
            return "Log [${action.level ?: 'info'}]: '${action.message}'"
        case "set_hsm":
            return "Set HSM to '${action.status}'"
        case "set_level":
            def device = findDevice(action.deviceId)
            def duration = action.duration ? " over ${action.duration}s" : ""
            return "Set '${device?.label ?: action.deviceId}' level to ${action.level}%${duration}"
        case "set_color":
            def device = findDevice(action.deviceId)
            return "Set '${device?.label ?: action.deviceId}' color to hue:${action.hue}, sat:${action.saturation}, level:${action.level}"
        case "set_color_temperature":
            def device = findDevice(action.deviceId)
            def level = action.level ? " at ${action.level}%" : ""
            return "Set '${device?.label ?: action.deviceId}' color temp to ${action.temperature}K${level}"
        case "lock":
            def device = findDevice(action.deviceId)
            return "Lock '${device?.label ?: action.deviceId}'"
        case "unlock":
            def device = findDevice(action.deviceId)
            return "Unlock '${device?.label ?: action.deviceId}'"
        case "capture_state":
            def devices = action.deviceIds?.collect { id -> findDevice(id)?.label ?: id }?.join(", ") ?: "none"
            return "Capture state of: ${devices}"
        case "restore_state":
            return "Restore previously captured state"
        case "send_notification":
            def target = action.deviceId ? "device ${findDevice(action.deviceId)?.label ?: action.deviceId}" : "all notification devices"
            return "Send notification to ${target}: '${action.message}'"
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
