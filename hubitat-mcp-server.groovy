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
    page(name: "viewRulePage")
    page(name: "addRulePage")
    page(name: "confirmDeletePage")
    page(name: "editTriggersPage")
    page(name: "addTriggerPage")
    page(name: "editTriggerPage")
    page(name: "editConditionsPage")
    page(name: "addConditionPage")
    page(name: "editConditionPage")
    page(name: "editActionsPage")
    page(name: "addActionPage")
    page(name: "editActionPage")
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

        // Rule List Section
        section("Automation Rules") {
            def ruleCount = state.rules?.size() ?: 0
            def enabledCount = state.rules?.values()?.count { it.enabled } ?: 0
            paragraph "<b>${ruleCount}</b> rules total, <b>${enabledCount}</b> enabled"

            if (state.rules && state.rules.size() > 0) {
                state.rules.each { ruleId, rule ->
                    def statusIcon = rule.enabled ? "✓" : "○"
                    def statusText = rule.enabled ? "Enabled" : "Disabled"
                    def lastRun = rule.lastTriggered ? formatTimestamp(rule.lastTriggered) : "Never"
                    def triggerCount = rule.triggers?.size() ?: 0
                    def actionCount = rule.actions?.size() ?: 0

                    href name: "viewRule_${ruleId}", page: "viewRulePage",
                         title: "${statusIcon} ${rule.name}",
                         description: "${statusText} | ${triggerCount} triggers, ${actionCount} actions | Last: ${lastRun}",
                         params: [ruleId: ruleId]
                }
            } else {
                paragraph "<i>No rules created yet. Add a rule to get started.</i>"
            }

            href name: "addNewRule", page: "addRulePage",
                 title: "+ Add New Rule",
                 description: "Create a new automation rule"
        }

        section("Settings") {
            input "enableRuleEngine", "bool", title: "Enable Rule Engine", defaultValue: true
            input "debugLogging", "bool", title: "Enable Debug Logging", defaultValue: false
        }
    }
}

def formatTimestamp(timestamp) {
    try {
        if (timestamp instanceof String) {
            def date = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", timestamp)
            return date.format("MM/dd HH:mm")
        }
        return "Unknown"
    } catch (Exception e) {
        return timestamp?.toString()?.take(16) ?: "Unknown"
    }
}

// ==================== RULE UI PAGES ====================

def viewRulePage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "viewRulePage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    dynamicPage(name: "viewRulePage", title: rule.name) {
        section("Rule Settings") {
            input "editRuleName_${ruleId}", "text", title: "Rule Name", defaultValue: rule.name, submitOnChange: true
            input "editRuleDescription_${ruleId}", "text", title: "Description", defaultValue: rule.description ?: "", submitOnChange: true

            // Auto-save name/description changes
            def newName = settings["editRuleName_${ruleId}"]
            def newDesc = settings["editRuleDescription_${ruleId}"]
            if (newName && newName != rule.name) {
                rule.name = newName
                rule.updatedAt = new Date().time
                state.rules[ruleId] = rule
            }
            if (newDesc != null && newDesc != rule.description) {
                rule.description = newDesc
                rule.updatedAt = new Date().time
                state.rules[ruleId] = rule
            }
        }

        section("Status") {
            def statusText = rule.enabled ? "✓ Enabled" : "○ Disabled"
            def lastRun = rule.lastTriggered ? formatTimestamp(rule.lastTriggered) : "Never"
            paragraph "<b>Status:</b> ${statusText}"
            paragraph "<b>Last Triggered:</b> ${lastRun}"
            paragraph "<b>Execution Count:</b> ${rule.executionCount ?: 0}"
        }

        section("Triggers (${rule.triggers?.size() ?: 0})") {
            if (rule.triggers && rule.triggers.size() > 0) {
                rule.triggers.eachWithIndex { trigger, idx ->
                    paragraph "${idx + 1}. ${describeTrigger(trigger)}"
                }
            } else {
                paragraph "<i>No triggers defined</i>"
            }
            href name: "editTriggers", page: "editTriggersPage",
                 title: "Edit Triggers",
                 params: [ruleId: ruleId]
        }

        section("Conditions (${rule.conditions?.size() ?: 0})") {
            if (rule.conditions && rule.conditions.size() > 0) {
                def logic = rule.conditionLogic == "any" ? "ANY" : "ALL"
                paragraph "<i>Logic: ${logic} conditions must be true</i>"
                rule.conditions.eachWithIndex { condition, idx ->
                    paragraph "${idx + 1}. ${describeCondition(condition)}"
                }
            } else {
                paragraph "<i>No conditions (rule always executes when triggered)</i>"
            }
            href name: "editConditions", page: "editConditionsPage",
                 title: "Edit Conditions",
                 params: [ruleId: ruleId]
        }

        section("Actions (${rule.actions?.size() ?: 0})") {
            if (rule.actions && rule.actions.size() > 0) {
                rule.actions.eachWithIndex { action, idx ->
                    paragraph "${idx + 1}. ${describeAction(action)}"
                }
            } else {
                paragraph "<i>No actions defined</i>"
            }
            href name: "editActions", page: "editActionsPage",
                 title: "Edit Actions",
                 params: [ruleId: ruleId]
        }

        section("Rule Actions") {
            input "toggleRuleEnabled_${ruleId}", "button",
                  title: rule.enabled ? "Disable Rule" : "Enable Rule"
            input "testRule_${ruleId}", "button", title: "Test Rule (Dry Run)"
            href name: "deleteRule", page: "confirmDeletePage",
                 title: "Delete Rule",
                 description: "Permanently delete this rule",
                 params: [ruleId: ruleId]
        }

        section {
            href name: "backToMain", page: "mainPage", title: "← Back to Rules"
        }
    }
}

def addRulePage(params) {
    // Clear any previous editing state
    state.remove("editingTrigger")
    state.remove("editingCondition")
    state.remove("editingAction")

    // Check if a rule was just created (state.justCreatedRuleId is set by createRuleFromUI)
    def justCreatedId = state.justCreatedRuleId
    def justCreatedRule = justCreatedId ? state.rules?.get(justCreatedId) : null

    if (justCreatedRule) {
        // Rule was just created - show success and link to configure it
        state.remove("justCreatedRuleId")

        return dynamicPage(name: "addRulePage", title: "Rule Created!") {
            section {
                paragraph "<b>✓ Rule '${justCreatedRule.name}' created successfully!</b>"
                paragraph "Your new rule is currently disabled and has no triggers or actions yet."
            }

            section {
                href name: "configureNewRule", page: "viewRulePage",
                     title: "→ Configure This Rule",
                     description: "Add triggers, conditions, and actions",
                     params: [ruleId: justCreatedId]
                href name: "createAnother", page: "addRulePage",
                     title: "+ Create Another Rule"
                href name: "backToMain", page: "mainPage",
                     title: "← Back to Rules List"
            }
        }
    }

    dynamicPage(name: "addRulePage", title: "Create New Rule") {
        section("Rule Details") {
            input "newRuleName", "text", title: "Rule Name", required: true, submitOnChange: true
            input "newRuleDescription", "text", title: "Description (optional)", required: false
        }

        section {
            if (settings.newRuleName?.trim()) {
                input "createRuleBtn", "button", title: "Create Rule"
            } else {
                paragraph "<i>Enter a rule name to continue</i>"
            }
            href name: "cancelAdd", page: "mainPage", title: "Cancel"
        }
    }
}

def confirmDeletePage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    def rule = state.rules?.get(ruleId)

    if (!rule) {
        return dynamicPage(name: "confirmDeletePage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    state.ruleToDelete = ruleId

    dynamicPage(name: "confirmDeletePage", title: "Delete Rule?") {
        section {
            paragraph "<b>Are you sure you want to delete this rule?</b>"
            paragraph "Rule: <b>${rule.name}</b>"
            paragraph "This action cannot be undone."
        }

        section {
            input "confirmDeleteBtn", "button", title: "Yes, Delete Rule"
            href name: "cancelDelete", page: "viewRulePage",
                 title: "Cancel",
                 params: [ruleId: ruleId]
        }
    }
}

def describeTrigger(trigger) {
    switch (trigger.type) {
        case "device_event":
            def device = findDevice(trigger.deviceId)
            def deviceName = device?.label ?: trigger.deviceId
            def valueMatch = trigger.value ? " ${trigger.operator ?: '=='} '${trigger.value}'" : ""
            def duration = trigger.duration ? " for ${trigger.duration}s" : ""
            return "When ${deviceName} ${trigger.attribute}${valueMatch}${duration}"
        case "button_event":
            def device = findDevice(trigger.deviceId)
            def deviceName = device?.label ?: trigger.deviceId
            def btn = trigger.buttonNumber ? "button ${trigger.buttonNumber}" : "any button"
            return "When ${deviceName} ${btn} is ${trigger.action}"
        case "time":
            if (trigger.time) {
                return "At ${trigger.time}"
            } else if (trigger.sunrise) {
                def offset = trigger.offset ? " (${trigger.offset > 0 ? '+' : ''}${trigger.offset} min)" : ""
                return "At sunrise${offset}"
            } else if (trigger.sunset) {
                def offset = trigger.offset ? " (${trigger.offset > 0 ? '+' : ''}${trigger.offset} min)" : ""
                return "At sunset${offset}"
            }
            return "Time trigger (unknown)"
        case "periodic":
            return "Every ${trigger.interval} ${trigger.unit ?: 'minutes'}"
        case "mode_change":
            def from = trigger.fromMode ? "from ${trigger.fromMode}" : ""
            def to = trigger.toMode ? "to ${trigger.toMode}" : ""
            return "When mode changes ${from} ${to}".trim()
        case "hsm_change":
            def status = trigger.status ? "to ${trigger.status}" : ""
            return "When HSM changes ${status}".trim()
        default:
            return "Unknown trigger: ${trigger.type}"
    }
}

// Button handlers
def appButtonHandler(btn) {
    logDebug("Button pressed: ${btn}")

    if (btn == "createRuleBtn") {
        createRuleFromUI()
    } else if (btn == "confirmDeleteBtn") {
        deleteRuleFromUI()
    } else if (btn.startsWith("toggleRuleEnabled_")) {
        def ruleId = btn.replace("toggleRuleEnabled_", "")
        toggleRuleEnabledFromUI(ruleId)
    } else if (btn.startsWith("testRule_")) {
        def ruleId = btn.replace("testRule_", "")
        testRuleFromUI(ruleId)
    } else if (btn == "saveTriggerBtn") {
        saveTriggerFromUI()
    } else if (btn.startsWith("deleteTrigger_")) {
        def index = btn.replace("deleteTrigger_", "").toInteger()
        deleteTriggerFromUI(index)
    } else if (btn == "saveConditionBtn") {
        saveConditionFromUI()
    } else if (btn.startsWith("deleteCondition_")) {
        def index = btn.replace("deleteCondition_", "").toInteger()
        deleteConditionFromUI(index)
    } else if (btn == "saveActionBtn") {
        saveActionFromUI()
    } else if (btn.startsWith("deleteAction_")) {
        def index = btn.replace("deleteAction_", "").toInteger()
        deleteActionFromUI(index)
    } else if (btn.startsWith("moveActionUp_")) {
        def index = btn.replace("moveActionUp_", "").toInteger()
        moveActionFromUI(index, -1)
    } else if (btn.startsWith("moveActionDown_")) {
        def index = btn.replace("moveActionDown_", "").toInteger()
        moveActionFromUI(index, 1)
    }
}

def createRuleFromUI() {
    def ruleName = settings.newRuleName?.trim()
    if (!ruleName) {
        log.warn "Cannot create rule: name is required"
        return
    }

    def ruleId = "rule-${UUID.randomUUID().toString().substring(0, 8)}"
    def now = new Date().time

    def rule = [
        name: ruleName,
        description: settings.newRuleDescription ?: "",
        enabled: false, // Start disabled until triggers/actions added
        triggers: [],
        conditions: [],
        conditionLogic: "all",
        actions: [],
        localVariables: [:],
        createdAt: now,
        updatedAt: now,
        executionCount: 0
    ]

    if (!state.rules) state.rules = [:]
    state.rules[ruleId] = rule

    // Clear the input
    app.removeSetting("newRuleName")
    app.removeSetting("newRuleDescription")

    // Set flag so addRulePage shows success message and link
    state.justCreatedRuleId = ruleId
    state.currentRuleId = ruleId

    log.info "Created new rule: ${ruleName} (${ruleId})"
}

def deleteRuleFromUI() {
    def ruleId = state.ruleToDelete
    if (!ruleId || !state.rules?.containsKey(ruleId)) {
        log.warn "Cannot delete rule: not found"
        return
    }

    def ruleName = state.rules[ruleId].name
    state.rules.remove(ruleId)
    state.remove("ruleToDelete")
    state.remove("currentRuleId")

    refreshAllSubscriptions()
    log.info "Deleted rule: ${ruleName}"
}

def toggleRuleEnabledFromUI(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) return

    rule.enabled = !rule.enabled
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    refreshAllSubscriptions()
    log.info "Rule '${rule.name}' ${rule.enabled ? 'enabled' : 'disabled'}"
}

def testRuleFromUI(ruleId) {
    def rule = state.rules?.get(ruleId)
    if (!rule) return

    def result = toolTestRule(ruleId)
    log.info "Test rule '${rule.name}': ${result.message}"
}

// ==================== TRIGGER MANAGEMENT PAGES ====================

def editTriggersPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "editTriggersPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    // Auto-save any pending trigger when entering this page (unless cancelled)
    if (settings.triggerType && params?.cancel != true) {
        def trigger = buildTriggerFromSettings()
        if (trigger) {
            if (!rule.triggers) rule.triggers = []
            if (state.editingTriggerIndex != null && state.editingTriggerIndex < rule.triggers.size()) {
                rule.triggers[state.editingTriggerIndex] = trigger
                log.info "Updated trigger ${state.editingTriggerIndex + 1} in rule '${rule.name}'"
            } else {
                rule.triggers.add(trigger)
                log.info "Added new trigger to rule '${rule.name}'"
            }
            rule.updatedAt = new Date().time
            state.rules[ruleId] = rule
            refreshAllSubscriptions()
        }
    }
    // Always clear settings when returning to this page
    clearTriggerSettings()
    state.remove("editingTriggerIndex")
    state.remove("triggerSettingsLoaded")
    state.remove("loadedTriggerIndex")

    dynamicPage(name: "editTriggersPage", title: "Edit Triggers: ${rule.name}") {
        section("Current Triggers") {
            if (rule.triggers && rule.triggers.size() > 0) {
                rule.triggers.eachWithIndex { trigger, idx ->
                    href name: "editTrigger_${idx}", page: "editTriggerPage",
                         title: "${idx + 1}. ${describeTrigger(trigger)}",
                         description: "Tap to edit",
                         params: [ruleId: ruleId, triggerIndex: idx]
                }
            } else {
                paragraph "<i>No triggers defined. Add a trigger to make this rule responsive.</i>"
            }
        }

        section {
            href name: "addTrigger", page: "addTriggerPage",
                 title: "+ Add Trigger",
                 params: [ruleId: ruleId]
        }

        section {
            href name: "backToRule", page: "viewRulePage",
                 title: "← Done",
                 params: [ruleId: ruleId]
        }
    }
}

def addTriggerPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId
    state.editingTriggerIndex = null // Adding new trigger

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "addTriggerPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    dynamicPage(name: "addTriggerPage", title: "Add Trigger") {
        section("Trigger Type") {
            input "triggerType", "enum", title: "When should this rule trigger?",
                  options: [
                      "device_event": "Device Event (attribute changes)",
                      "button_event": "Button Press",
                      "time": "Specific Time",
                      "periodic": "Periodic Schedule",
                      "mode_change": "Mode Change",
                      "hsm_change": "HSM Status Change"
                  ],
                  required: true, submitOnChange: true
        }

        renderTriggerFields(settings.triggerType)

        section {
            if (settings.triggerType) {
                href name: "saveTrigger", page: "editTriggersPage",
                     title: "Save Trigger",
                     description: "Save and return to triggers list",
                     params: [ruleId: ruleId]
            }
            href name: "cancelTrigger", page: "editTriggersPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId, cancel: true]
        }
    }
}

def editTriggerPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    def triggerIndex = params?.triggerIndex != null ? params.triggerIndex.toInteger() : state.editingTriggerIndex
    state.currentRuleId = ruleId
    state.editingTriggerIndex = triggerIndex

    def rule = state.rules?.get(ruleId)
    if (!rule || triggerIndex == null || triggerIndex >= (rule.triggers?.size() ?: 0)) {
        return dynamicPage(name: "editTriggerPage", title: "Trigger Not Found") {
            section {
                paragraph "The requested trigger could not be found."
                href name: "backToTriggers", page: "editTriggersPage", title: "Back to Triggers"
            }
        }
    }

    def trigger = rule.triggers[triggerIndex]

    // Pre-populate settings from existing trigger
    if (!state.triggerSettingsLoaded || state.loadedTriggerIndex != triggerIndex) {
        loadTriggerSettings(trigger)
        state.triggerSettingsLoaded = true
        state.loadedTriggerIndex = triggerIndex
    }

    dynamicPage(name: "editTriggerPage", title: "Edit Trigger ${triggerIndex + 1}") {
        section("Trigger Type") {
            input "triggerType", "enum", title: "When should this rule trigger?",
                  options: [
                      "device_event": "Device Event (attribute changes)",
                      "button_event": "Button Press",
                      "time": "Specific Time",
                      "periodic": "Periodic Schedule",
                      "mode_change": "Mode Change",
                      "hsm_change": "HSM Status Change"
                  ],
                  required: true, submitOnChange: true,
                  defaultValue: trigger.type
        }

        renderTriggerFields(settings.triggerType ?: trigger.type)

        section {
            href name: "saveTriggerEdit", page: "editTriggersPage",
                 title: "Save Changes",
                 description: "Save and return to triggers list",
                 params: [ruleId: ruleId]
            input "deleteTrigger_${triggerIndex}", "button", title: "Delete Trigger"
            href name: "cancelTriggerEdit", page: "editTriggersPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId]
        }
    }
}

def renderTriggerFields(triggerType) {
    switch (triggerType) {
        case "device_event":
            section("Device Event Settings") {
                input "triggerDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.triggerDevice) {
                    def device = settings.triggerDevice
                    def attrs = device.supportedAttributes?.collect { it.name }?.sort()
                    input "triggerAttribute", "enum", title: "Attribute",
                          options: attrs, required: true, submitOnChange: true
                }
                input "triggerOperator", "enum", title: "Comparison",
                      options: [
                          "equals": "Equals",
                          "not_equals": "Not Equals",
                          ">": "Greater Than",
                          "<": "Less Than",
                          ">=": "Greater or Equal",
                          "<=": "Less or Equal",
                          "contains": "Contains"
                      ],
                      defaultValue: "equals", required: false
                input "triggerValue", "text", title: "Value (leave empty for any change)", required: false
                input "triggerDuration", "number", title: "Duration (seconds, optional)",
                      description: "Trigger only if condition persists for this duration",
                      required: false, range: "0..86400"
            }
            break

        case "button_event":
            section("Button Event Settings") {
                input "triggerDevice", "capability.pushableButton", title: "Button Device", required: true
                input "triggerButtonNumber", "number", title: "Button Number (leave empty for any)",
                      required: false, range: "1..20"
                input "triggerButtonAction", "enum", title: "Button Action",
                      options: ["pushed": "Pushed", "held": "Held", "doubleTapped": "Double Tapped", "released": "Released"],
                      required: true, defaultValue: "pushed"
            }
            break

        case "time":
            section("Time Settings") {
                input "triggerTimeType", "enum", title: "Time Type",
                      options: ["specific": "Specific Time", "sunrise": "Sunrise", "sunset": "Sunset"],
                      required: true, submitOnChange: true, defaultValue: "specific"

                if (settings.triggerTimeType == "specific") {
                    input "triggerTime", "time", title: "Time", required: true
                } else if (settings.triggerTimeType in ["sunrise", "sunset"]) {
                    input "triggerOffset", "number", title: "Offset (minutes)",
                          description: "Negative = before, Positive = after",
                          required: false, range: "-180..180", defaultValue: 0
                }
            }
            break

        case "periodic":
            section("Periodic Schedule") {
                input "triggerInterval", "number", title: "Every", required: true, range: "1..999"
                input "triggerUnit", "enum", title: "Unit",
                      options: ["minutes": "Minutes", "hours": "Hours", "days": "Days"],
                      required: true, defaultValue: "minutes"
            }
            break

        case "mode_change":
            section("Mode Change Settings") {
                def modes = location.modes?.collect { it.name }
                input "triggerFromMode", "enum", title: "From Mode (optional)",
                      options: modes, required: false
                input "triggerToMode", "enum", title: "To Mode (optional)",
                      options: modes, required: false
                paragraph "<i>Leave both empty to trigger on any mode change</i>"
            }
            break

        case "hsm_change":
            section("HSM Change Settings") {
                input "triggerHsmStatus", "enum", title: "HSM Status (optional)",
                      options: [
                          "armedAway": "Armed Away",
                          "armedHome": "Armed Home",
                          "armedNight": "Armed Night",
                          "disarmed": "Disarmed",
                          "intrusion": "Intrusion Alert"
                      ],
                      required: false
                paragraph "<i>Leave empty to trigger on any HSM change</i>"
            }
            break
    }
}

def loadTriggerSettings(trigger) {
    app.updateSetting("triggerType", trigger.type)

    switch (trigger.type) {
        case "device_event":
            if (trigger.deviceId) {
                def device = findDevice(trigger.deviceId)
                if (device) app.updateSetting("triggerDevice", [type: "capability.*", value: device.id])
            }
            if (trigger.attribute) app.updateSetting("triggerAttribute", trigger.attribute)
            if (trigger.operator) app.updateSetting("triggerOperator", trigger.operator)
            if (trigger.value) app.updateSetting("triggerValue", trigger.value)
            if (trigger.duration) app.updateSetting("triggerDuration", trigger.duration)
            break

        case "button_event":
            if (trigger.deviceId) {
                def device = findDevice(trigger.deviceId)
                if (device) app.updateSetting("triggerDevice", [type: "capability.pushableButton", value: device.id])
            }
            if (trigger.buttonNumber) app.updateSetting("triggerButtonNumber", trigger.buttonNumber)
            if (trigger.action) app.updateSetting("triggerButtonAction", trigger.action)
            break

        case "time":
            if (trigger.time) {
                app.updateSetting("triggerTimeType", "specific")
                app.updateSetting("triggerTime", trigger.time)
            } else if (trigger.sunrise) {
                app.updateSetting("triggerTimeType", "sunrise")
                app.updateSetting("triggerOffset", trigger.offset ?: 0)
            } else if (trigger.sunset) {
                app.updateSetting("triggerTimeType", "sunset")
                app.updateSetting("triggerOffset", trigger.offset ?: 0)
            }
            break

        case "periodic":
            if (trigger.interval) app.updateSetting("triggerInterval", trigger.interval)
            if (trigger.unit) app.updateSetting("triggerUnit", trigger.unit)
            break

        case "mode_change":
            if (trigger.fromMode) app.updateSetting("triggerFromMode", trigger.fromMode)
            if (trigger.toMode) app.updateSetting("triggerToMode", trigger.toMode)
            break

        case "hsm_change":
            if (trigger.status) app.updateSetting("triggerHsmStatus", trigger.status)
            break
    }
}

def saveTriggerFromUI() {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        log.warn "Cannot save trigger: rule not found"
        return
    }

    def trigger = buildTriggerFromSettings()
    if (!trigger) {
        log.warn "Cannot save trigger: invalid settings"
        return
    }

    if (!rule.triggers) rule.triggers = []

    if (state.editingTriggerIndex != null && state.editingTriggerIndex < rule.triggers.size()) {
        // Editing existing trigger
        rule.triggers[state.editingTriggerIndex] = trigger
        log.info "Updated trigger ${state.editingTriggerIndex + 1} in rule '${rule.name}'"
    } else {
        // Adding new trigger
        rule.triggers.add(trigger)
        log.info "Added new trigger to rule '${rule.name}'"
    }

    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    // Clear trigger settings
    clearTriggerSettings()
    state.remove("editingTriggerIndex")
    state.remove("triggerSettingsLoaded")
    state.remove("loadedTriggerIndex")

    refreshAllSubscriptions()
}

def buildTriggerFromSettings() {
    def trigger = [type: settings.triggerType]

    switch (settings.triggerType) {
        case "device_event":
            if (!settings.triggerDevice || !settings.triggerAttribute) return null
            trigger.deviceId = settings.triggerDevice.id.toString()
            trigger.attribute = settings.triggerAttribute
            if (settings.triggerOperator) trigger.operator = settings.triggerOperator
            if (settings.triggerValue) trigger.value = settings.triggerValue
            if (settings.triggerDuration) trigger.duration = settings.triggerDuration
            break

        case "button_event":
            if (!settings.triggerDevice) return null
            trigger.deviceId = settings.triggerDevice.id.toString()
            trigger.action = settings.triggerButtonAction ?: "pushed"
            if (settings.triggerButtonNumber) trigger.buttonNumber = settings.triggerButtonNumber
            break

        case "time":
            if (settings.triggerTimeType == "specific") {
                if (!settings.triggerTime) return null
                // Convert time input to HH:mm format
                trigger.time = formatTimeInput(settings.triggerTime)
            } else if (settings.triggerTimeType == "sunrise") {
                trigger.sunrise = true
                if (settings.triggerOffset) trigger.offset = settings.triggerOffset
            } else if (settings.triggerTimeType == "sunset") {
                trigger.sunset = true
                if (settings.triggerOffset) trigger.offset = settings.triggerOffset
            } else {
                return null
            }
            break

        case "periodic":
            if (!settings.triggerInterval) return null
            trigger.interval = settings.triggerInterval
            trigger.unit = settings.triggerUnit ?: "minutes"
            break

        case "mode_change":
            if (settings.triggerFromMode) trigger.fromMode = settings.triggerFromMode
            if (settings.triggerToMode) trigger.toMode = settings.triggerToMode
            break

        case "hsm_change":
            if (settings.triggerHsmStatus) trigger.status = settings.triggerHsmStatus
            break

        default:
            return null
    }

    return trigger
}

def formatTimeInput(timeInput) {
    try {
        if (timeInput instanceof Date) {
            return timeInput.format("HH:mm")
        } else if (timeInput instanceof String) {
            // Handle various time string formats
            if (timeInput.contains("T")) {
                def date = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", timeInput)
                return date.format("HH:mm")
            }
            return timeInput
        }
        return timeInput.toString()
    } catch (Exception e) {
        return timeInput.toString()
    }
}

def clearTriggerSettings() {
    ["triggerType", "triggerDevice", "triggerAttribute", "triggerOperator", "triggerValue",
     "triggerDuration", "triggerButtonNumber", "triggerButtonAction", "triggerTimeType",
     "triggerTime", "triggerOffset", "triggerInterval", "triggerUnit", "triggerFromMode",
     "triggerToMode", "triggerHsmStatus"].each { app.removeSetting(it) }
}

def deleteTriggerFromUI(index) {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule || !rule.triggers || index >= rule.triggers.size()) {
        log.warn "Cannot delete trigger: not found"
        return
    }

    rule.triggers.remove(index)
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    clearTriggerSettings()
    state.remove("editingTriggerIndex")
    state.remove("triggerSettingsLoaded")
    state.remove("loadedTriggerIndex")

    refreshAllSubscriptions()
    log.info "Deleted trigger ${index + 1} from rule '${rule.name}'"
}

// ==================== CONDITION MANAGEMENT PAGES ====================

def editConditionsPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "editConditionsPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    // Auto-save any pending condition when entering this page (unless cancelled)
    if (settings.conditionType && params?.cancel != true) {
        def condition = buildConditionFromSettings()
        if (condition) {
            if (!rule.conditions) rule.conditions = []
            if (state.editingConditionIndex != null && state.editingConditionIndex < rule.conditions.size()) {
                rule.conditions[state.editingConditionIndex] = condition
                log.info "Updated condition ${state.editingConditionIndex + 1} in rule '${rule.name}'"
            } else {
                rule.conditions.add(condition)
                log.info "Added new condition to rule '${rule.name}'"
            }
            rule.updatedAt = new Date().time
            state.rules[ruleId] = rule
        }
    }
    // Always clear settings when returning to this page
    clearConditionSettings()
    state.remove("editingConditionIndex")
    state.remove("conditionSettingsLoaded")
    state.remove("loadedConditionIndex")

    dynamicPage(name: "editConditionsPage", title: "Edit Conditions: ${rule.name}") {
        section("Condition Logic") {
            input "conditionLogic", "enum", title: "How should conditions be evaluated?",
                  options: ["all": "ALL conditions must be true", "any": "ANY condition must be true"],
                  defaultValue: rule.conditionLogic ?: "all",
                  submitOnChange: true

            // Update rule if logic changed
            if (settings.conditionLogic && settings.conditionLogic != rule.conditionLogic) {
                rule.conditionLogic = settings.conditionLogic
                rule.updatedAt = new Date().time
                state.rules[ruleId] = rule
            }
        }

        section("Current Conditions") {
            if (rule.conditions && rule.conditions.size() > 0) {
                rule.conditions.eachWithIndex { condition, idx ->
                    href name: "editCondition_${idx}", page: "editConditionPage",
                         title: "${idx + 1}. ${describeCondition(condition)}",
                         description: "Tap to edit",
                         params: [ruleId: ruleId, conditionIndex: idx]
                }
            } else {
                paragraph "<i>No conditions defined. Rule will execute whenever triggered.</i>"
            }
        }

        section {
            href name: "addCondition", page: "addConditionPage",
                 title: "+ Add Condition",
                 params: [ruleId: ruleId]
        }

        section {
            href name: "backToRule", page: "viewRulePage",
                 title: "← Done",
                 params: [ruleId: ruleId]
        }
    }
}

def addConditionPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId
    state.editingConditionIndex = null // Adding new condition

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "addConditionPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    dynamicPage(name: "addConditionPage", title: "Add Condition") {
        section("Condition Type") {
            input "conditionType", "enum", title: "What should be checked?",
                  options: [
                      "device_state": "Device State",
                      "device_was": "Device Was (for duration)",
                      "time_range": "Time Range",
                      "mode": "Hub Mode",
                      "variable": "Variable Value",
                      "days_of_week": "Days of Week",
                      "sun_position": "Sun Position (day/night)",
                      "hsm_status": "HSM Status",
                      "presence": "Presence Sensor",
                      "lock": "Lock Status",
                      "thermostat_mode": "Thermostat Mode",
                      "thermostat_state": "Thermostat State",
                      "illuminance": "Illuminance Level",
                      "power": "Power Level"
                  ],
                  required: true, submitOnChange: true
        }

        renderConditionFields(settings.conditionType)

        section {
            if (settings.conditionType) {
                href name: "saveCondition", page: "editConditionsPage",
                     title: "Save Condition",
                     description: "Save and return to conditions list",
                     params: [ruleId: ruleId]
            }
            href name: "cancelCondition", page: "editConditionsPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId, cancel: true]
        }
    }
}

def editConditionPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    def conditionIndex = params?.conditionIndex != null ? params.conditionIndex.toInteger() : state.editingConditionIndex
    state.currentRuleId = ruleId
    state.editingConditionIndex = conditionIndex

    def rule = state.rules?.get(ruleId)
    if (!rule || conditionIndex == null || conditionIndex >= (rule.conditions?.size() ?: 0)) {
        return dynamicPage(name: "editConditionPage", title: "Condition Not Found") {
            section {
                paragraph "The requested condition could not be found."
                href name: "backToConditions", page: "editConditionsPage", title: "Back to Conditions"
            }
        }
    }

    def condition = rule.conditions[conditionIndex]

    // Pre-populate settings from existing condition
    if (!state.conditionSettingsLoaded || state.loadedConditionIndex != conditionIndex) {
        loadConditionSettings(condition)
        state.conditionSettingsLoaded = true
        state.loadedConditionIndex = conditionIndex
    }

    dynamicPage(name: "editConditionPage", title: "Edit Condition ${conditionIndex + 1}") {
        section("Condition Type") {
            input "conditionType", "enum", title: "What should be checked?",
                  options: [
                      "device_state": "Device State",
                      "device_was": "Device Was (for duration)",
                      "time_range": "Time Range",
                      "mode": "Hub Mode",
                      "variable": "Variable Value",
                      "days_of_week": "Days of Week",
                      "sun_position": "Sun Position (day/night)",
                      "hsm_status": "HSM Status",
                      "presence": "Presence Sensor",
                      "lock": "Lock Status",
                      "thermostat_mode": "Thermostat Mode",
                      "thermostat_state": "Thermostat State",
                      "illuminance": "Illuminance Level",
                      "power": "Power Level"
                  ],
                  required: true, submitOnChange: true,
                  defaultValue: condition.type
        }

        renderConditionFields(settings.conditionType ?: condition.type)

        section {
            href name: "saveConditionEdit", page: "editConditionsPage",
                 title: "Save Changes",
                 description: "Save and return to conditions list",
                 params: [ruleId: ruleId]
            input "deleteCondition_${conditionIndex}", "button", title: "Delete Condition"
            href name: "cancelConditionEdit", page: "editConditionsPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId, cancel: true]
        }
    }
}

def renderConditionFields(conditionType) {
    switch (conditionType) {
        case "device_state":
            section("Device State Settings") {
                input "conditionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.conditionDevice) {
                    def device = settings.conditionDevice
                    def attrs = device.supportedAttributes?.collect { it.name }?.sort()
                    input "conditionAttribute", "enum", title: "Attribute",
                          options: attrs, required: true
                }
                input "conditionOperator", "enum", title: "Comparison",
                      options: getOperatorOptions(), required: true, defaultValue: "equals"
                input "conditionValue", "text", title: "Value", required: true
            }
            break

        case "device_was":
            section("Device Was Settings") {
                input "conditionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.conditionDevice) {
                    def device = settings.conditionDevice
                    def attrs = device.supportedAttributes?.collect { it.name }?.sort()
                    input "conditionAttribute", "enum", title: "Attribute",
                          options: attrs, required: true
                }
                input "conditionOperator", "enum", title: "Comparison",
                      options: getOperatorOptions(), required: true, defaultValue: "equals"
                input "conditionValue", "text", title: "Value", required: true
                input "conditionForSeconds", "number", title: "For how many seconds?",
                      required: true, range: "1..86400"
            }
            break

        case "time_range":
            section("Time Range Settings") {
                paragraph "<i>Use HH:mm format or 'sunrise'/'sunset'</i>"
                input "conditionStartTime", "text", title: "Start Time", required: true,
                      description: "e.g., 08:00 or sunrise"
                input "conditionEndTime", "text", title: "End Time", required: true,
                      description: "e.g., 22:00 or sunset"
            }
            break

        case "mode":
            section("Mode Settings") {
                def modes = location.modes?.collect { it.name }
                input "conditionModes", "enum", title: "Modes",
                      options: modes, required: true, multiple: true
                input "conditionModeOperator", "enum", title: "Logic",
                      options: ["in": "Mode IS one of these", "not_in": "Mode is NOT one of these"],
                      required: true, defaultValue: "in"
            }
            break

        case "variable":
            section("Variable Settings") {
                input "conditionVariableName", "text", title: "Variable Name", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: getOperatorOptions(), required: true, defaultValue: "equals"
                input "conditionValue", "text", title: "Value", required: true
            }
            break

        case "days_of_week":
            section("Days of Week Settings") {
                input "conditionDays", "enum", title: "Days",
                      options: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
                      required: true, multiple: true
            }
            break

        case "sun_position":
            section("Sun Position Settings") {
                input "conditionSunPosition", "enum", title: "Sun Position",
                      options: ["up": "Sun is Up (daytime)", "down": "Sun is Down (nighttime)"],
                      required: true
            }
            break

        case "hsm_status":
            section("HSM Status Settings") {
                input "conditionHsmStatus", "enum", title: "HSM Status",
                      options: [
                          "armedAway": "Armed Away",
                          "armedHome": "Armed Home",
                          "armedNight": "Armed Night",
                          "disarmed": "Disarmed"
                      ],
                      required: true
                input "conditionHsmOperator", "enum", title: "Logic",
                      options: ["equals": "Equals", "not_equals": "Not Equals"],
                      required: true, defaultValue: "equals"
            }
            break

        case "presence":
            section("Presence Settings") {
                input "conditionDevice", "capability.presenceSensor", title: "Presence Sensor", required: true
                input "conditionPresenceStatus", "enum", title: "Status",
                      options: ["present": "Present", "not present": "Not Present"],
                      required: true
            }
            break

        case "lock":
            section("Lock Settings") {
                input "conditionDevice", "capability.lock", title: "Lock Device", required: true
                input "conditionLockStatus", "enum", title: "Status",
                      options: ["locked": "Locked", "unlocked": "Unlocked"],
                      required: true
            }
            break

        case "thermostat_mode":
            section("Thermostat Mode Settings") {
                input "conditionDevice", "capability.thermostat", title: "Thermostat", required: true
                input "conditionThermostatMode", "enum", title: "Mode",
                      options: ["heat": "Heat", "cool": "Cool", "auto": "Auto", "off": "Off", "emergency heat": "Emergency Heat"],
                      required: true
            }
            break

        case "thermostat_state":
            section("Thermostat State Settings") {
                input "conditionDevice", "capability.thermostat", title: "Thermostat", required: true
                input "conditionThermostatState", "enum", title: "Operating State",
                      options: ["heating": "Heating", "cooling": "Cooling", "idle": "Idle", "fan only": "Fan Only"],
                      required: true
            }
            break

        case "illuminance":
            section("Illuminance Settings") {
                input "conditionDevice", "capability.illuminanceMeasurement", title: "Illuminance Sensor", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: getOperatorOptions(), required: true, defaultValue: "<"
                input "conditionValue", "number", title: "Lux Value", required: true
            }
            break

        case "power":
            section("Power Settings") {
                input "conditionDevice", "capability.powerMeter", title: "Power Meter", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: getOperatorOptions(), required: true, defaultValue: ">"
                input "conditionValue", "number", title: "Watts", required: true
            }
            break
    }
}

def getOperatorOptions() {
    return [
        "equals": "Equals",
        "not_equals": "Not Equals",
        ">": "Greater Than",
        "<": "Less Than",
        ">=": "Greater or Equal",
        "<=": "Less or Equal",
        "contains": "Contains"
    ]
}

def loadConditionSettings(condition) {
    app.updateSetting("conditionType", condition.type)

    switch (condition.type) {
        case "device_state":
        case "device_was":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.*", value: device.id])
            }
            if (condition.attribute) app.updateSetting("conditionAttribute", condition.attribute)
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value) app.updateSetting("conditionValue", condition.value)
            if (condition.forSeconds) app.updateSetting("conditionForSeconds", condition.forSeconds)
            break

        case "time_range":
            if (condition.startTime) app.updateSetting("conditionStartTime", condition.startTime)
            if (condition.endTime) app.updateSetting("conditionEndTime", condition.endTime)
            break

        case "mode":
            if (condition.modes) app.updateSetting("conditionModes", condition.modes)
            if (condition.operator) app.updateSetting("conditionModeOperator", condition.operator)
            break

        case "variable":
            if (condition.variableName) app.updateSetting("conditionVariableName", condition.variableName)
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value) app.updateSetting("conditionValue", condition.value)
            break

        case "days_of_week":
            if (condition.days) app.updateSetting("conditionDays", condition.days)
            break

        case "sun_position":
            if (condition.position) app.updateSetting("conditionSunPosition", condition.position)
            break

        case "hsm_status":
            if (condition.status) app.updateSetting("conditionHsmStatus", condition.status)
            if (condition.operator) app.updateSetting("conditionHsmOperator", condition.operator)
            break

        case "presence":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.presenceSensor", value: device.id])
            }
            if (condition.status) app.updateSetting("conditionPresenceStatus", condition.status)
            break

        case "lock":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.lock", value: device.id])
            }
            if (condition.status) app.updateSetting("conditionLockStatus", condition.status)
            break

        case "thermostat_mode":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.mode) app.updateSetting("conditionThermostatMode", condition.mode)
            break

        case "thermostat_state":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.state) app.updateSetting("conditionThermostatState", condition.state)
            break

        case "illuminance":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.illuminanceMeasurement", value: device.id])
            }
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value) app.updateSetting("conditionValue", condition.value)
            break

        case "power":
            if (condition.deviceId) {
                def device = findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.powerMeter", value: device.id])
            }
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value) app.updateSetting("conditionValue", condition.value)
            break
    }
}

def saveConditionFromUI() {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        log.warn "Cannot save condition: rule not found"
        return
    }

    def condition = buildConditionFromSettings()
    if (!condition) {
        log.warn "Cannot save condition: invalid settings"
        return
    }

    if (!rule.conditions) rule.conditions = []

    if (state.editingConditionIndex != null && state.editingConditionIndex < rule.conditions.size()) {
        rule.conditions[state.editingConditionIndex] = condition
        log.info "Updated condition ${state.editingConditionIndex + 1} in rule '${rule.name}'"
    } else {
        rule.conditions.add(condition)
        log.info "Added new condition to rule '${rule.name}'"
    }

    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    clearConditionSettings()
    state.remove("editingConditionIndex")
    state.remove("conditionSettingsLoaded")
    state.remove("loadedConditionIndex")
}

def buildConditionFromSettings() {
    def condition = [type: settings.conditionType]

    switch (settings.conditionType) {
        case "device_state":
            if (!settings.conditionDevice || !settings.conditionAttribute) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.attribute = settings.conditionAttribute
            condition.operator = settings.conditionOperator ?: "equals"
            condition.value = settings.conditionValue
            break

        case "device_was":
            if (!settings.conditionDevice || !settings.conditionAttribute || !settings.conditionForSeconds) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.attribute = settings.conditionAttribute
            condition.operator = settings.conditionOperator ?: "equals"
            condition.value = settings.conditionValue
            condition.forSeconds = settings.conditionForSeconds
            break

        case "time_range":
            if (!settings.conditionStartTime || !settings.conditionEndTime) return null
            condition.startTime = settings.conditionStartTime
            condition.endTime = settings.conditionEndTime
            break

        case "mode":
            if (!settings.conditionModes) return null
            condition.modes = settings.conditionModes
            condition.operator = settings.conditionModeOperator ?: "in"
            break

        case "variable":
            if (!settings.conditionVariableName) return null
            condition.variableName = settings.conditionVariableName
            condition.operator = settings.conditionOperator ?: "equals"
            condition.value = settings.conditionValue
            break

        case "days_of_week":
            if (!settings.conditionDays) return null
            condition.days = settings.conditionDays
            break

        case "sun_position":
            if (!settings.conditionSunPosition) return null
            condition.position = settings.conditionSunPosition
            break

        case "hsm_status":
            if (!settings.conditionHsmStatus) return null
            condition.status = settings.conditionHsmStatus
            condition.operator = settings.conditionHsmOperator ?: "equals"
            break

        case "presence":
            if (!settings.conditionDevice || !settings.conditionPresenceStatus) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.status = settings.conditionPresenceStatus
            break

        case "lock":
            if (!settings.conditionDevice || !settings.conditionLockStatus) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.status = settings.conditionLockStatus
            break

        case "thermostat_mode":
            if (!settings.conditionDevice || !settings.conditionThermostatMode) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.mode = settings.conditionThermostatMode
            break

        case "thermostat_state":
            if (!settings.conditionDevice || !settings.conditionThermostatState) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.state = settings.conditionThermostatState
            break

        case "illuminance":
        case "power":
            if (!settings.conditionDevice) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.operator = settings.conditionOperator ?: "equals"
            condition.value = settings.conditionValue
            break

        default:
            return null
    }

    return condition
}

def clearConditionSettings() {
    ["conditionType", "conditionDevice", "conditionAttribute", "conditionOperator", "conditionValue",
     "conditionForSeconds", "conditionStartTime", "conditionEndTime", "conditionModes",
     "conditionModeOperator", "conditionVariableName", "conditionDays", "conditionSunPosition",
     "conditionHsmStatus", "conditionHsmOperator", "conditionPresenceStatus", "conditionLockStatus",
     "conditionThermostatMode", "conditionThermostatState", "conditionLogic"].each { app.removeSetting(it) }
}

def deleteConditionFromUI(index) {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule || !rule.conditions || index >= rule.conditions.size()) {
        log.warn "Cannot delete condition: not found"
        return
    }

    rule.conditions.remove(index)
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    clearConditionSettings()
    state.remove("editingConditionIndex")
    state.remove("conditionSettingsLoaded")
    state.remove("loadedConditionIndex")

    log.info "Deleted condition ${index + 1} from rule '${rule.name}'"
}

// ==================== ACTION MANAGEMENT PAGES ====================

def editActionsPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "editActionsPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    // Auto-save any pending action when entering this page (unless cancelled)
    if (settings.actionType && params?.cancel != true) {
        def action = buildActionFromSettings()
        if (action) {
            if (!rule.actions) rule.actions = []
            if (state.editingActionIndex != null && state.editingActionIndex < rule.actions.size()) {
                rule.actions[state.editingActionIndex] = action
                log.info "Updated action ${state.editingActionIndex + 1} in rule '${rule.name}'"
            } else {
                rule.actions.add(action)
                log.info "Added new action to rule '${rule.name}'"
            }
            rule.updatedAt = new Date().time
            state.rules[ruleId] = rule
        }
    }
    // Always clear settings when returning to this page
    clearActionSettings()
    state.remove("editingActionIndex")
    state.remove("actionSettingsLoaded")
    state.remove("loadedActionIndex")

    dynamicPage(name: "editActionsPage", title: "Edit Actions: ${rule.name}") {
        section("Actions (executed in order)") {
            if (rule.actions && rule.actions.size() > 0) {
                rule.actions.eachWithIndex { action, idx ->
                    def canMoveUp = idx > 0
                    def canMoveDown = idx < rule.actions.size() - 1

                    href name: "editAction_${idx}", page: "editActionPage",
                         title: "${idx + 1}. ${describeAction(action)}",
                         description: "Tap to edit",
                         params: [ruleId: ruleId, actionIndex: idx]

                    // Reorder buttons
                    if (canMoveUp || canMoveDown) {
                        def moveButtons = []
                        if (canMoveUp) moveButtons << "moveActionUp_${idx}"
                        if (canMoveDown) moveButtons << "moveActionDown_${idx}"
                    }
                }
            } else {
                paragraph "<i>No actions defined. Add an action for this rule to do something.</i>"
            }
        }

        if (rule.actions && rule.actions.size() > 1) {
            section("Reorder Actions") {
                paragraph "<i>Tap up/down buttons below to reorder:</i>"
                rule.actions.eachWithIndex { action, idx ->
                    def label = "${idx + 1}. ${describeAction(action).take(30)}..."
                    if (idx > 0) {
                        input "moveActionUp_${idx}", "button", title: "↑ Move ${idx + 1} Up"
                    }
                    if (idx < rule.actions.size() - 1) {
                        input "moveActionDown_${idx}", "button", title: "↓ Move ${idx + 1} Down"
                    }
                }
            }
        }

        section {
            href name: "addAction", page: "addActionPage",
                 title: "+ Add Action",
                 params: [ruleId: ruleId]
        }

        section {
            href name: "backToRule", page: "viewRulePage",
                 title: "← Done",
                 params: [ruleId: ruleId]
        }
    }
}

def addActionPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    state.currentRuleId = ruleId
    state.editingActionIndex = null // Adding new action

    def rule = state.rules?.get(ruleId)
    if (!rule) {
        return dynamicPage(name: "addActionPage", title: "Rule Not Found") {
            section {
                paragraph "The requested rule could not be found."
                href name: "backToMain", page: "mainPage", title: "Back to Rules"
            }
        }
    }

    dynamicPage(name: "addActionPage", title: "Add Action") {
        section("Action Type") {
            input "actionType", "enum", title: "What should happen?",
                  options: getActionTypeOptions(),
                  required: true, submitOnChange: true
        }

        renderActionFields(settings.actionType)

        section {
            if (settings.actionType) {
                href name: "saveAction", page: "editActionsPage",
                     title: "Save Action",
                     description: "Save and return to actions list",
                     params: [ruleId: ruleId]
            }
            href name: "cancelAction", page: "editActionsPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId, cancel: true]
        }
    }
}

def editActionPage(params) {
    def ruleId = params?.ruleId ?: state.currentRuleId
    def actionIndex = params?.actionIndex != null ? params.actionIndex.toInteger() : state.editingActionIndex
    state.currentRuleId = ruleId
    state.editingActionIndex = actionIndex

    def rule = state.rules?.get(ruleId)
    if (!rule || actionIndex == null || actionIndex >= (rule.actions?.size() ?: 0)) {
        return dynamicPage(name: "editActionPage", title: "Action Not Found") {
            section {
                paragraph "The requested action could not be found."
                href name: "backToActions", page: "editActionsPage", title: "Back to Actions"
            }
        }
    }

    def action = rule.actions[actionIndex]

    // Pre-populate settings from existing action
    if (!state.actionSettingsLoaded || state.loadedActionIndex != actionIndex) {
        loadActionSettings(action)
        state.actionSettingsLoaded = true
        state.loadedActionIndex = actionIndex
    }

    dynamicPage(name: "editActionPage", title: "Edit Action ${actionIndex + 1}") {
        section("Action Type") {
            input "actionType", "enum", title: "What should happen?",
                  options: getActionTypeOptions(),
                  required: true, submitOnChange: true,
                  defaultValue: action.type
        }

        renderActionFields(settings.actionType ?: action.type)

        section {
            href name: "saveActionEdit", page: "editActionsPage",
                 title: "Save Changes",
                 description: "Save and return to actions list",
                 params: [ruleId: ruleId]
            input "deleteAction_${actionIndex}", "button", title: "Delete Action"
            href name: "cancelActionEdit", page: "editActionsPage",
                 title: "Cancel",
                 description: "Discard changes",
                 params: [ruleId: ruleId, cancel: true]
        }
    }
}

def getActionTypeOptions() {
    return [
        "device_command": "Device Command",
        "toggle_device": "Toggle Device On/Off",
        "set_level": "Set Dimmer Level",
        "set_color": "Set Light Color",
        "set_color_temperature": "Set Color Temperature",
        "lock": "Lock",
        "unlock": "Unlock",
        "activate_scene": "Activate Scene",
        "set_mode": "Set Hub Mode",
        "set_hsm": "Set HSM Status",
        "set_variable": "Set Variable",
        "delay": "Delay",
        "cancel_delayed": "Cancel Delayed Actions",
        "log": "Log Message",
        "send_notification": "Send Notification",
        "capture_state": "Capture Device State",
        "restore_state": "Restore Device State",
        "stop": "Stop Rule Execution"
    ]
}

def renderActionFields(actionType) {
    switch (actionType) {
        case "device_command":
            section("Device Command Settings") {
                input "actionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.actionDevice) {
                    def device = settings.actionDevice
                    def cmds = device.supportedCommands?.collect { it.name }?.sort()
                    input "actionCommand", "enum", title: "Command",
                          options: cmds, required: true, submitOnChange: true

                    if (settings.actionCommand) {
                        def cmd = device.supportedCommands?.find { it.name == settings.actionCommand }
                        if (cmd?.arguments?.size() > 0) {
                            paragraph "<i>Command parameters (comma-separated):</i>"
                            input "actionParameters", "text", title: "Parameters",
                                  description: "e.g., 50 for setLevel", required: false
                        }
                    }
                }
            }
            break

        case "toggle_device":
            section("Toggle Device Settings") {
                input "actionDevice", "capability.switch", title: "Switch Device", required: true
            }
            break

        case "set_level":
            section("Set Level Settings") {
                input "actionDevice", "capability.switchLevel", title: "Dimmer Device", required: true
                input "actionLevel", "number", title: "Level (%)", required: true, range: "0..100"
                input "actionDuration", "number", title: "Fade Duration (seconds, optional)",
                      required: false, range: "0..3600"
            }
            break

        case "set_color":
            section("Set Color Settings") {
                input "actionDevice", "capability.colorControl", title: "Color Light", required: true
                input "actionHue", "number", title: "Hue (0-100)", required: true, range: "0..100"
                input "actionSaturation", "number", title: "Saturation (0-100)", required: true, range: "0..100"
                input "actionLevel", "number", title: "Level (%, optional)", required: false, range: "0..100"
            }
            break

        case "set_color_temperature":
            section("Set Color Temperature Settings") {
                input "actionDevice", "capability.colorTemperature", title: "CT Light", required: true
                input "actionColorTemp", "number", title: "Color Temperature (K)",
                      required: true, range: "2000..6500"
                input "actionLevel", "number", title: "Level (%, optional)", required: false, range: "0..100"
            }
            break

        case "lock":
        case "unlock":
            section("Lock Settings") {
                input "actionDevice", "capability.lock", title: "Lock Device", required: true
            }
            break

        case "activate_scene":
            section("Scene Settings") {
                input "actionSceneDevice", "capability.*", title: "Scene Device", required: true
            }
            break

        case "set_mode":
            section("Mode Settings") {
                def modes = location.modes?.collect { it.name }
                input "actionMode", "enum", title: "Mode", options: modes, required: true
            }
            break

        case "set_hsm":
            section("HSM Settings") {
                input "actionHsmStatus", "enum", title: "HSM Status",
                      options: [
                          "armAway": "Arm Away",
                          "armHome": "Arm Home",
                          "armNight": "Arm Night",
                          "disarm": "Disarm"
                      ],
                      required: true
            }
            break

        case "set_variable":
            section("Variable Settings") {
                input "actionVariableName", "text", title: "Variable Name", required: true
                input "actionVariableValue", "text", title: "Value", required: true
                input "actionVariableScope", "enum", title: "Scope",
                      options: ["global": "Global (hub/rule engine)", "rule": "Rule Local"],
                      required: true, defaultValue: "global"
            }
            break

        case "delay":
            section("Delay Settings") {
                input "actionDelaySeconds", "number", title: "Delay (seconds)",
                      required: true, range: "1..86400"
                input "actionDelayId", "text", title: "Delay ID (optional)",
                      description: "For cancelling specific delays", required: false
            }
            break

        case "cancel_delayed":
            section("Cancel Delayed Settings") {
                input "actionCancelDelayId", "text", title: "Delay ID to Cancel",
                      description: "Leave empty to cancel all delays for this rule", required: false
            }
            break

        case "log":
            section("Log Settings") {
                input "actionLogMessage", "text", title: "Message", required: true
                input "actionLogLevel", "enum", title: "Level",
                      options: ["debug": "Debug", "info": "Info", "warn": "Warning", "error": "Error"],
                      required: true, defaultValue: "info"
            }
            break

        case "send_notification":
            section("Notification Settings") {
                input "actionNotificationMessage", "text", title: "Message", required: true
                input "actionNotificationTitle", "text", title: "Title (optional)", required: false
            }
            break

        case "capture_state":
            section("Capture State Settings") {
                input "actionCaptureDevices", "capability.*", title: "Devices to Capture",
                      required: true, multiple: true
                input "actionStateId", "text", title: "State ID",
                      description: "Identifier for this saved state", required: false, defaultValue: "default"
            }
            break

        case "restore_state":
            section("Restore State Settings") {
                input "actionStateId", "text", title: "State ID to Restore",
                      description: "Leave empty for 'default'", required: false, defaultValue: "default"
            }
            break

        case "stop":
            section {
                paragraph "<i>This action stops rule execution. No further actions will run.</i>"
            }
            break
    }
}

def loadActionSettings(action) {
    app.updateSetting("actionType", action.type)

    switch (action.type) {
        case "device_command":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.*", value: device.id])
            }
            if (action.command) app.updateSetting("actionCommand", action.command)
            if (action.parameters) app.updateSetting("actionParameters", action.parameters.join(","))
            break

        case "toggle_device":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.switch", value: device.id])
            }
            break

        case "set_level":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.switchLevel", value: device.id])
            }
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            if (action.duration) app.updateSetting("actionDuration", action.duration)
            break

        case "set_color":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.colorControl", value: device.id])
            }
            if (action.hue != null) app.updateSetting("actionHue", action.hue)
            if (action.saturation != null) app.updateSetting("actionSaturation", action.saturation)
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            break

        case "set_color_temperature":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.colorTemperature", value: device.id])
            }
            if (action.colorTemperature) app.updateSetting("actionColorTemp", action.colorTemperature)
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            break

        case "lock":
        case "unlock":
            if (action.deviceId) {
                def device = findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.lock", value: device.id])
            }
            break

        case "activate_scene":
            if (action.sceneDeviceId) {
                def device = findDevice(action.sceneDeviceId)
                if (device) app.updateSetting("actionSceneDevice", [type: "capability.*", value: device.id])
            }
            break

        case "set_mode":
            if (action.mode) app.updateSetting("actionMode", action.mode)
            break

        case "set_hsm":
            if (action.status) app.updateSetting("actionHsmStatus", action.status)
            break

        case "set_variable":
            if (action.variableName) app.updateSetting("actionVariableName", action.variableName)
            if (action.value) app.updateSetting("actionVariableValue", action.value)
            if (action.scope) app.updateSetting("actionVariableScope", action.scope)
            break

        case "delay":
            if (action.seconds) app.updateSetting("actionDelaySeconds", action.seconds)
            if (action.delayId) app.updateSetting("actionDelayId", action.delayId)
            break

        case "cancel_delayed":
            if (action.delayId) app.updateSetting("actionCancelDelayId", action.delayId)
            break

        case "log":
            if (action.message) app.updateSetting("actionLogMessage", action.message)
            if (action.level) app.updateSetting("actionLogLevel", action.level)
            break

        case "send_notification":
            if (action.message) app.updateSetting("actionNotificationMessage", action.message)
            if (action.title) app.updateSetting("actionNotificationTitle", action.title)
            break

        case "capture_state":
            if (action.devices) {
                def devices = action.devices.collect { id -> findDevice(id) }.findAll { it != null }
                if (devices) app.updateSetting("actionCaptureDevices", [type: "capability.*", value: devices*.id])
            }
            if (action.stateId) app.updateSetting("actionStateId", action.stateId)
            break

        case "restore_state":
            if (action.stateId) app.updateSetting("actionStateId", action.stateId)
            break
    }
}

def saveActionFromUI() {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule) {
        log.warn "Cannot save action: rule not found"
        return
    }

    def action = buildActionFromSettings()
    if (!action) {
        log.warn "Cannot save action: invalid settings"
        return
    }

    if (!rule.actions) rule.actions = []

    if (state.editingActionIndex != null && state.editingActionIndex < rule.actions.size()) {
        rule.actions[state.editingActionIndex] = action
        log.info "Updated action ${state.editingActionIndex + 1} in rule '${rule.name}'"
    } else {
        rule.actions.add(action)
        log.info "Added new action to rule '${rule.name}'"
    }

    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    clearActionSettings()
    state.remove("editingActionIndex")
    state.remove("actionSettingsLoaded")
    state.remove("loadedActionIndex")
}

def buildActionFromSettings() {
    def action = [type: settings.actionType]

    switch (settings.actionType) {
        case "device_command":
            if (!settings.actionDevice || !settings.actionCommand) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.command = settings.actionCommand
            if (settings.actionParameters?.trim()) {
                action.parameters = settings.actionParameters.split(",")*.trim()
            }
            break

        case "toggle_device":
            if (!settings.actionDevice) return null
            action.deviceId = settings.actionDevice.id.toString()
            break

        case "set_level":
            if (!settings.actionDevice || settings.actionLevel == null) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.level = settings.actionLevel
            if (settings.actionDuration) action.duration = settings.actionDuration
            break

        case "set_color":
            if (!settings.actionDevice || settings.actionHue == null || settings.actionSaturation == null) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.hue = settings.actionHue
            action.saturation = settings.actionSaturation
            if (settings.actionLevel != null) action.level = settings.actionLevel
            break

        case "set_color_temperature":
            if (!settings.actionDevice || !settings.actionColorTemp) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.colorTemperature = settings.actionColorTemp
            if (settings.actionLevel != null) action.level = settings.actionLevel
            break

        case "lock":
        case "unlock":
            if (!settings.actionDevice) return null
            action.deviceId = settings.actionDevice.id.toString()
            break

        case "activate_scene":
            if (!settings.actionSceneDevice) return null
            action.sceneDeviceId = settings.actionSceneDevice.id.toString()
            break

        case "set_mode":
            if (!settings.actionMode) return null
            action.mode = settings.actionMode
            break

        case "set_hsm":
            if (!settings.actionHsmStatus) return null
            action.status = settings.actionHsmStatus
            break

        case "set_variable":
            if (!settings.actionVariableName) return null
            action.variableName = settings.actionVariableName
            action.value = settings.actionVariableValue ?: ""
            action.scope = settings.actionVariableScope ?: "global"
            break

        case "delay":
            if (!settings.actionDelaySeconds) return null
            action.seconds = settings.actionDelaySeconds
            if (settings.actionDelayId?.trim()) action.delayId = settings.actionDelayId
            break

        case "cancel_delayed":
            if (settings.actionCancelDelayId?.trim()) {
                action.delayId = settings.actionCancelDelayId
            } else {
                action.delayId = "all"
            }
            break

        case "log":
            if (!settings.actionLogMessage) return null
            action.message = settings.actionLogMessage
            action.level = settings.actionLogLevel ?: "info"
            break

        case "send_notification":
            if (!settings.actionNotificationMessage) return null
            action.message = settings.actionNotificationMessage
            if (settings.actionNotificationTitle) action.title = settings.actionNotificationTitle
            break

        case "capture_state":
            if (!settings.actionCaptureDevices) return null
            action.devices = settings.actionCaptureDevices.collect { it.id.toString() }
            action.stateId = settings.actionStateId ?: "default"
            break

        case "restore_state":
            action.stateId = settings.actionStateId ?: "default"
            break

        case "stop":
            // No additional fields
            break

        default:
            return null
    }

    return action
}

def clearActionSettings() {
    ["actionType", "actionDevice", "actionCommand", "actionParameters", "actionLevel",
     "actionDuration", "actionHue", "actionSaturation", "actionColorTemp", "actionSceneDevice",
     "actionMode", "actionHsmStatus", "actionVariableName", "actionVariableValue",
     "actionVariableScope", "actionDelaySeconds", "actionDelayId", "actionCancelDelayId",
     "actionLogMessage", "actionLogLevel", "actionNotificationMessage", "actionNotificationTitle",
     "actionCaptureDevices", "actionStateId"].each { app.removeSetting(it) }
}

def deleteActionFromUI(index) {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule || !rule.actions || index >= rule.actions.size()) {
        log.warn "Cannot delete action: not found"
        return
    }

    rule.actions.remove(index)
    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    clearActionSettings()
    state.remove("editingActionIndex")
    state.remove("actionSettingsLoaded")
    state.remove("loadedActionIndex")

    log.info "Deleted action ${index + 1} from rule '${rule.name}'"
}

def moveActionFromUI(index, direction) {
    def ruleId = state.currentRuleId
    def rule = state.rules?.get(ruleId)
    if (!rule || !rule.actions) return

    def newIndex = index + direction
    if (newIndex < 0 || newIndex >= rule.actions.size()) return

    // Swap actions
    def temp = rule.actions[index]
    rule.actions[index] = rule.actions[newIndex]
    rule.actions[newIndex] = temp

    rule.updatedAt = new Date().time
    state.rules[ruleId] = rule

    log.info "Moved action from position ${index + 1} to ${newIndex + 1} in rule '${rule.name}'"
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
            version: "0.0.6"
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

    // Safely collect attributes
    def attributes = []
    try {
        attributes = device.supportedAttributes?.collect { attr ->
            [name: attr.name, dataType: attr.dataType?.toString(), value: device.currentValue(attr.name)]
        } ?: []
    } catch (Exception e) {
        logDebug("Error getting attributes for device ${deviceId}: ${e.message}")
    }

    // Safely collect commands - some devices have unusual argument structures
    def commands = []
    try {
        commands = device.supportedCommands?.collect { cmd ->
            def args = null
            try {
                args = cmd.arguments?.collect { arg ->
                    // Handle different argument formats
                    if (arg instanceof Map) {
                        [name: arg.name ?: "arg", type: arg.type ?: "unknown"]
                    } else if (arg.respondsTo("getName")) {
                        [name: arg.getName() ?: "arg", type: arg.getType()?.toString() ?: "unknown"]
                    } else {
                        [name: arg.toString(), type: "unknown"]
                    }
                }
            } catch (Exception e) {
                args = null // If we can't parse arguments, just skip them
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
    def hub = location.hub
    def info = [
        name: hub?.name,
        localIP: hub?.localIP,
        timeZone: location.timeZone?.ID,
        temperatureScale: location.temperatureScale,
        latitude: location.latitude,
        longitude: location.longitude
    ]

    // These properties may not be available on all hub models
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
    // Get hub connector variables (may not be available on all setups)
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
    try {
        def hubVar = getGlobalConnectorVariable(name)
        if (hubVar != null) {
            return [name: name, value: hubVar, source: "hub"]
        }
    } catch (Exception e) {
        logDebug("Hub connector variable '${name}' not found or not accessible: ${e.message}")
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
