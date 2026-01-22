/**
 * MCP Rule - Child App
 *
 * Individual automation rule with isolated settings.
 * Each rule is a separate child app instance.
 */

definition(
    name: "MCP Rule",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Individual automation rule for MCP Rule Server",
    category: "Automation",
    parent: "mcp:MCP Rule Server",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "editTriggersPage")
    page(name: "addTriggerPage")
    page(name: "editTriggerPage")
    page(name: "editConditionsPage")
    page(name: "addConditionPage")
    page(name: "editConditionPage")
    page(name: "editActionsPage")
    page(name: "addActionPage")
    page(name: "editActionPage")
    page(name: "confirmDeletePage")
}

def installed() {
    log.info "MCP Rule '${settings.ruleName}' installed"
    state.createdAt = now()
    state.updatedAt = now()
    state.executionCount = 0
    state.triggers = []
    state.conditions = []
    state.actions = []
    // Set the app label to match the rule name (for display in Apps list)
    if (settings.ruleName) {
        app.updateLabel(settings.ruleName)
    }
    initialize()
}

def updated() {
    log.info "MCP Rule '${settings.ruleName}' updated"
    state.updatedAt = now()
    // Update the app label to match the rule name (for display in Apps list)
    if (settings.ruleName) {
        app.updateLabel(settings.ruleName)
    }
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    log.info "MCP Rule '${settings.ruleName}' uninstalled"
    unsubscribe()
    unschedule()
}

def initialize() {
    // Clear any stale duration timer state (timers were canceled by unschedule() in updated())
    clearDurationState()

    if (settings.ruleEnabled) {
        subscribeToTriggers()
    }
}

/**
 * Clears all duration-related state to prevent accumulation and stale data.
 * Should be called during initialization and when rule is disabled.
 */
def clearDurationState() {
    if (state.durationTimers) {
        log.debug "Clearing ${state.durationTimers.size()} stale duration timer entries"
        state.remove("durationTimers")
    }
    if (state.durationFired) {
        log.debug "Clearing ${state.durationFired.size()} stale durationFired entries"
        state.remove("durationFired")
    }
}

// ==================== MAIN PAGE ====================

def mainPage() {
    dynamicPage(name: "mainPage", title: "Configure Rule", install: true, uninstall: true) {
        section("Rule Settings") {
            input "ruleName", "text", title: "Rule Name", required: true, submitOnChange: true
            input "ruleDescription", "text", title: "Description (optional)", required: false
            input "ruleEnabled", "bool", title: "Rule Enabled", defaultValue: false, submitOnChange: true
        }

        section("Status") {
            def lastRun = state.lastTriggered ? formatTimestamp(state.lastTriggered) : "Never"
            paragraph "<b>Status:</b> ${settings.ruleEnabled ? '✓ Enabled' : '○ Disabled'}"
            paragraph "<b>Last Triggered:</b> ${lastRun}"
            paragraph "<b>Execution Count:</b> ${state.executionCount ?: 0}"
        }

        section("Triggers (${state.triggers?.size() ?: 0})") {
            if (state.triggers && state.triggers.size() > 0) {
                state.triggers.eachWithIndex { trigger, idx ->
                    paragraph "${idx + 1}. ${describeTrigger(trigger)}"
                }
            } else {
                paragraph "<i>No triggers defined</i>"
            }
            href name: "editTriggers", page: "editTriggersPage", title: "Edit Triggers"
        }

        section("Conditions (${state.conditions?.size() ?: 0})") {
            if (state.conditions && state.conditions.size() > 0) {
                def logic = state.conditionLogic == "any" ? "ANY" : "ALL"
                paragraph "<i>Logic: ${logic} conditions must be true</i>"
                state.conditions.eachWithIndex { condition, idx ->
                    paragraph "${idx + 1}. ${describeCondition(condition)}"
                }
            } else {
                paragraph "<i>No conditions (rule always executes when triggered)</i>"
            }
            href name: "editConditions", page: "editConditionsPage", title: "Edit Conditions"
        }

        section("Actions (${state.actions?.size() ?: 0})") {
            if (state.actions && state.actions.size() > 0) {
                state.actions.eachWithIndex { action, idx ->
                    paragraph "${idx + 1}. ${describeAction(action)}"
                }
            } else {
                paragraph "<i>No actions defined</i>"
            }
            href name: "editActions", page: "editActionsPage", title: "Edit Actions"
        }

        section("Rule Actions") {
            input "testRuleBtn", "button", title: "Test Rule (Dry Run)"
        }
    }
}

// ==================== TRIGGER PAGES ====================

def editTriggersPage() {
    // Auto-save pending trigger
    if (settings.triggerType) {
        savePendingTrigger()
    }

    dynamicPage(name: "editTriggersPage", title: "Edit Triggers") {
        section("Current Triggers") {
            if (state.triggers && state.triggers.size() > 0) {
                state.triggers.eachWithIndex { trigger, idx ->
                    href name: "editTrigger_${idx}", page: "editTriggerPage",
                         title: "${idx + 1}. ${describeTrigger(trigger)}",
                         description: "Tap to edit",
                         params: [triggerIndex: idx]
                }
            } else {
                paragraph "<i>No triggers defined. Add a trigger to make this rule responsive.</i>"
            }
        }

        section {
            href name: "addTrigger", page: "addTriggerPage", title: "+ Add Trigger"
        }

        section {
            href name: "backToMain", page: "mainPage", title: "← Done"
        }
    }
}

def addTriggerPage() {
    state.editingTriggerIndex = null
    clearTriggerSettings()

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

        renderTriggerFields()

        section {
            if (settings.triggerType) {
                href name: "saveTrigger", page: "editTriggersPage",
                     title: "Save Trigger",
                     description: "Save and return to triggers list"
            }
            href name: "cancelTrigger", page: "editTriggersPage",
                 title: "Cancel"
        }
    }
}

def editTriggerPage(params) {
    def triggerIndex = params?.triggerIndex != null ? params.triggerIndex.toInteger() : state.editingTriggerIndex

    if (triggerIndex == null || triggerIndex >= (state.triggers?.size() ?: 0)) {
        return dynamicPage(name: "editTriggerPage", title: "Trigger Not Found") {
            section {
                paragraph "The requested trigger could not be found."
                href name: "backToTriggers", page: "editTriggersPage", title: "Back to Triggers"
            }
        }
    }

    state.editingTriggerIndex = triggerIndex
    def trigger = state.triggers[triggerIndex]

    // Load trigger into settings if not already loaded
    if (state.loadedTriggerIndex != triggerIndex) {
        loadTriggerSettings(trigger)
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
                  required: true, submitOnChange: true
        }

        renderTriggerFields()

        section {
            href name: "saveTriggerEdit", page: "editTriggersPage",
                 title: "Save Changes",
                 description: "Save and return to triggers list"
            input "deleteTriggerBtn", "button", title: "Delete Trigger"
            href name: "cancelTriggerEdit", page: "editTriggersPage",
                 title: "Cancel"
        }
    }
}

def renderTriggerFields() {
    switch (settings.triggerType) {
        case "device_event":
            section("Device Event Settings") {
                input "triggerDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.triggerDevice) {
                    def attrs = settings.triggerDevice.supportedAttributes?.collect { it.name }?.unique()?.sort()
                    input "triggerAttribute", "enum", title: "Attribute", options: attrs, required: true
                }
                input "triggerOperator", "enum", title: "Comparison (optional)",
                      options: ["any": "Any Change", "equals": "Equals", "not_equals": "Not Equals",
                               ">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                      required: false, defaultValue: "any"
                input "triggerValue", "text", title: "Value (if comparing)", required: false
                input "triggerDuration", "number", title: "For duration (optional)",
                      description: "Debounce - only trigger if condition persists", required: false, range: "1..999"
                input "triggerDurationUnit", "enum", title: "Duration Unit",
                      options: ["seconds": "Seconds", "minutes": "Minutes", "hours": "Hours"],
                      required: false, defaultValue: "seconds"
                paragraph "<small><b>Note:</b> Duration is limited to 2 hours (7200 seconds) max. Hubitat's runIn() scheduler uses seconds internally and longer durations may be unreliable due to hub restarts.</small>"
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
                input "triggerFromMode", "enum", title: "From Mode (optional)", options: modes, required: false
                input "triggerToMode", "enum", title: "To Mode (optional)", options: modes, required: false
                paragraph "<i>Leave both empty to trigger on any mode change</i>"
            }
            break

        case "hsm_change":
            section("HSM Change Settings") {
                input "triggerHsmStatus", "enum", title: "HSM Status (optional)",
                      options: ["armedAway": "Armed Away", "armedHome": "Armed Home",
                               "armedNight": "Armed Night", "disarmed": "Disarmed", "intrusion": "Intrusion Alert"],
                      required: false
                paragraph "<i>Leave empty to trigger on any HSM change</i>"
            }
            break
    }
}

def savePendingTrigger() {
    def trigger = buildTriggerFromSettings()
    if (trigger) {
        if (!state.triggers) state.triggers = []
        if (state.editingTriggerIndex != null && state.editingTriggerIndex < state.triggers.size()) {
            state.triggers[state.editingTriggerIndex] = trigger
            log.info "Updated trigger ${state.editingTriggerIndex + 1}"
        } else {
            state.triggers.add(trigger)
            log.info "Added new trigger"
        }
        state.updatedAt = now()
    }
    clearTriggerSettings()
    state.remove("editingTriggerIndex")
    state.remove("loadedTriggerIndex")
}

def buildTriggerFromSettings() {
    if (!settings.triggerType) return null
    def trigger = [type: settings.triggerType]

    switch (settings.triggerType) {
        case "device_event":
            if (!settings.triggerDevice || !settings.triggerAttribute) return null
            trigger.deviceId = settings.triggerDevice.id.toString()
            trigger.attribute = settings.triggerAttribute
            if (settings.triggerOperator && settings.triggerOperator != "any") {
                trigger.operator = settings.triggerOperator
            }
            if (settings.triggerValue) trigger.value = settings.triggerValue
            if (settings.triggerDuration) {
                // Convert duration to seconds based on unit
                def durationSeconds = settings.triggerDuration
                def unit = settings.triggerDurationUnit ?: "seconds"
                switch (unit) {
                    case "minutes":
                        durationSeconds = settings.triggerDuration * 60
                        break
                    case "hours":
                        durationSeconds = settings.triggerDuration * 3600
                        break
                }
                // Cap at 7200 seconds (2 hours) - runIn() is unreliable for longer durations
                def maxDuration = 7200
                if (durationSeconds > maxDuration) {
                    log.warn "Duration ${durationSeconds}s exceeds max of ${maxDuration}s, capping to ${maxDuration}s"
                    durationSeconds = maxDuration
                }
                trigger.duration = durationSeconds
                trigger.durationUnit = unit  // Store original unit for display
                trigger.durationValue = settings.triggerDuration  // Store original value for editing
            }
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
                trigger.time = formatTimeInput(settings.triggerTime)
            } else if (settings.triggerTimeType == "sunrise") {
                trigger.sunrise = true
                if (settings.triggerOffset) trigger.offset = settings.triggerOffset
            } else if (settings.triggerTimeType == "sunset") {
                trigger.sunset = true
                if (settings.triggerOffset) trigger.offset = settings.triggerOffset
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
    }

    return trigger
}

def loadTriggerSettings(trigger) {
    clearTriggerSettings()
    app.updateSetting("triggerType", trigger.type)

    switch (trigger.type) {
        case "device_event":
            if (trigger.deviceId) {
                def device = parent.findDevice(trigger.deviceId)
                if (device) app.updateSetting("triggerDevice", [type: "capability.*", value: device.id])
            }
            if (trigger.attribute) app.updateSetting("triggerAttribute", trigger.attribute)
            if (trigger.operator) app.updateSetting("triggerOperator", trigger.operator)
            if (trigger.value) app.updateSetting("triggerValue", trigger.value)
            if (trigger.duration) {
                // Load original value and unit if available, otherwise convert from seconds
                if (trigger.durationValue && trigger.durationUnit) {
                    app.updateSetting("triggerDuration", trigger.durationValue)
                    app.updateSetting("triggerDurationUnit", trigger.durationUnit)
                } else {
                    // Legacy: duration was stored in seconds only
                    app.updateSetting("triggerDuration", trigger.duration)
                    app.updateSetting("triggerDurationUnit", "seconds")
                }
            }
            break

        case "button_event":
            if (trigger.deviceId) {
                def device = parent.findDevice(trigger.deviceId)
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

def clearTriggerSettings() {
    ["triggerType", "triggerDevice", "triggerAttribute", "triggerOperator", "triggerValue",
     "triggerDuration", "triggerDurationUnit", "triggerButtonNumber", "triggerButtonAction", "triggerTimeType",
     "triggerTime", "triggerOffset", "triggerInterval", "triggerUnit", "triggerFromMode",
     "triggerToMode", "triggerHsmStatus"].each { app.removeSetting(it) }
}

def formatTimeInput(timeInput) {
    try {
        if (timeInput instanceof Date) {
            return timeInput.format("HH:mm")
        } else if (timeInput instanceof String) {
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

// ==================== CONDITION PAGES ====================

def editConditionsPage() {
    // Auto-save pending condition
    if (settings.conditionType) {
        savePendingCondition()
    }

    dynamicPage(name: "editConditionsPage", title: "Edit Conditions") {
        section("Condition Logic") {
            input "conditionLogic", "enum", title: "How should conditions be evaluated?",
                  options: ["all": "ALL conditions must be true", "any": "ANY condition must be true"],
                  defaultValue: state.conditionLogic ?: "all", submitOnChange: true
            if (settings.conditionLogic != state.conditionLogic) {
                state.conditionLogic = settings.conditionLogic
            }
        }

        section("Current Conditions") {
            if (state.conditions && state.conditions.size() > 0) {
                state.conditions.eachWithIndex { condition, idx ->
                    href name: "editCondition_${idx}", page: "editConditionPage",
                         title: "${idx + 1}. ${describeCondition(condition)}",
                         description: "Tap to edit",
                         params: [conditionIndex: idx]
                }
            } else {
                paragraph "<i>No conditions defined. Rule will execute whenever triggered.</i>"
            }
        }

        section {
            href name: "addCondition", page: "addConditionPage", title: "+ Add Condition"
        }

        section {
            href name: "backToMain", page: "mainPage", title: "← Done"
        }
    }
}

def addConditionPage() {
    state.editingConditionIndex = null
    clearConditionSettings()

    dynamicPage(name: "addConditionPage", title: "Add Condition") {
        section("Condition Type") {
            input "conditionType", "enum", title: "What should be checked?",
                  options: getConditionTypeOptions(),
                  required: true, submitOnChange: true
        }

        renderConditionFields()

        section {
            if (settings.conditionType) {
                href name: "saveCondition", page: "editConditionsPage",
                     title: "Save Condition",
                     description: "Save and return to conditions list"
            }
            href name: "cancelCondition", page: "editConditionsPage",
                 title: "Cancel"
        }
    }
}

def editConditionPage(params) {
    def conditionIndex = params?.conditionIndex != null ? params.conditionIndex.toInteger() : state.editingConditionIndex

    if (conditionIndex == null || conditionIndex >= (state.conditions?.size() ?: 0)) {
        return dynamicPage(name: "editConditionPage", title: "Condition Not Found") {
            section {
                paragraph "The requested condition could not be found."
                href name: "backToConditions", page: "editConditionsPage", title: "Back to Conditions"
            }
        }
    }

    state.editingConditionIndex = conditionIndex
    def condition = state.conditions[conditionIndex]

    if (state.loadedConditionIndex != conditionIndex) {
        loadConditionSettings(condition)
        state.loadedConditionIndex = conditionIndex
    }

    dynamicPage(name: "editConditionPage", title: "Edit Condition ${conditionIndex + 1}") {
        section("Condition Type") {
            input "conditionType", "enum", title: "What should be checked?",
                  options: getConditionTypeOptions(),
                  required: true, submitOnChange: true
        }

        renderConditionFields()

        section {
            href name: "saveConditionEdit", page: "editConditionsPage",
                 title: "Save Changes",
                 description: "Save and return to conditions list"
            input "deleteConditionBtn", "button", title: "Delete Condition"
            href name: "cancelConditionEdit", page: "editConditionsPage",
                 title: "Cancel"
        }
    }
}

def getConditionTypeOptions() {
    return [
        "device_state": "Device State",
        "device_was": "Device Was (for duration)",
        "time_range": "Time Range",
        "mode": "Hub Mode",
        "variable": "Variable Value",
        "days_of_week": "Days of Week",
        "sun_position": "Sun Position (day/night)",
        "hsm_status": "HSM Status"
    ]
}

def renderConditionFields() {
    switch (settings.conditionType) {
        case "device_state":
            section("Device State Settings") {
                input "conditionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.conditionDevice) {
                    def attrs = settings.conditionDevice.supportedAttributes?.collect { it.name }?.unique()?.sort()
                    input "conditionAttribute", "enum", title: "Attribute", options: attrs, required: true
                }
                input "conditionOperator", "enum", title: "Comparison",
                      options: ["equals": "Equals", "not_equals": "Not Equals",
                               ">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                      required: true, defaultValue: "equals"
                input "conditionValue", "text", title: "Value", required: true
            }
            break

        case "device_was":
            section("Device Was Settings") {
                input "conditionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.conditionDevice) {
                    def attrs = settings.conditionDevice.supportedAttributes?.collect { it.name }?.unique()?.sort()
                    input "conditionAttribute", "enum", title: "Attribute", options: attrs, required: true
                }
                input "conditionValue", "text", title: "Value", required: true
                input "conditionDuration", "number", title: "For at least (seconds)", required: true, range: "1..86400"
            }
            break

        case "time_range":
            section("Time Range Settings") {
                input "conditionStartTime", "time", title: "Start Time", required: true
                input "conditionEndTime", "time", title: "End Time", required: true
            }
            break

        case "mode":
            section("Mode Settings") {
                def modes = location.modes?.collect { it.name }
                input "conditionModes", "enum", title: "Mode(s)", options: modes, multiple: true, required: true
                input "conditionModeOperator", "enum", title: "Condition",
                      options: ["in": "Is one of", "not_in": "Is not one of"],
                      required: true, defaultValue: "in"
            }
            break

        case "variable":
            section("Variable Settings") {
                input "conditionVariableName", "text", title: "Variable Name", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: ["equals": "Equals", "not_equals": "Not Equals",
                               ">": "Greater Than", "<": "Less Than"],
                      required: true, defaultValue: "equals"
                input "conditionValue", "text", title: "Value", required: true
            }
            break

        case "days_of_week":
            section("Days of Week Settings") {
                input "conditionDays", "enum", title: "Days",
                      options: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
                      multiple: true, required: true
            }
            break

        case "sun_position":
            section("Sun Position Settings") {
                input "conditionSunPosition", "enum", title: "Sun is",
                      options: ["up": "Up (daytime)", "down": "Down (nighttime)"],
                      required: true
            }
            break

        case "hsm_status":
            section("HSM Status Settings") {
                input "conditionHsmStatus", "enum", title: "HSM Status",
                      options: ["armedAway": "Armed Away", "armedHome": "Armed Home",
                               "armedNight": "Armed Night", "disarmed": "Disarmed"],
                      required: true
            }
            break
    }
}

def savePendingCondition() {
    def condition = buildConditionFromSettings()
    if (condition) {
        if (!state.conditions) state.conditions = []
        if (state.editingConditionIndex != null && state.editingConditionIndex < state.conditions.size()) {
            state.conditions[state.editingConditionIndex] = condition
            log.info "Updated condition ${state.editingConditionIndex + 1}"
        } else {
            state.conditions.add(condition)
            log.info "Added new condition"
        }
        state.updatedAt = now()
    }
    clearConditionSettings()
    state.remove("editingConditionIndex")
    state.remove("loadedConditionIndex")
}

def buildConditionFromSettings() {
    if (!settings.conditionType) return null
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
            if (!settings.conditionDevice || !settings.conditionAttribute) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.attribute = settings.conditionAttribute
            condition.value = settings.conditionValue
            condition.forSeconds = settings.conditionDuration
            break

        case "time_range":
            if (!settings.conditionStartTime || !settings.conditionEndTime) return null
            condition.startTime = formatTimeInput(settings.conditionStartTime)
            condition.endTime = formatTimeInput(settings.conditionEndTime)
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
            break
    }

    return condition
}

def loadConditionSettings(condition) {
    clearConditionSettings()
    app.updateSetting("conditionType", condition.type)

    switch (condition.type) {
        case "device_state":
        case "device_was":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.*", value: device.id])
            }
            if (condition.attribute) app.updateSetting("conditionAttribute", condition.attribute)
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value) app.updateSetting("conditionValue", condition.value)
            if (condition.forSeconds) app.updateSetting("conditionDuration", condition.forSeconds)
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
            break
    }
}

def clearConditionSettings() {
    ["conditionType", "conditionDevice", "conditionAttribute", "conditionOperator", "conditionValue",
     "conditionDuration", "conditionStartTime", "conditionEndTime", "conditionModes", "conditionModeOperator",
     "conditionVariableName", "conditionDays", "conditionSunPosition", "conditionHsmStatus"].each {
        app.removeSetting(it)
    }
}

// ==================== ACTION PAGES ====================

def editActionsPage() {
    // Auto-save pending action
    if (settings.actionType) {
        savePendingAction()
    }

    dynamicPage(name: "editActionsPage", title: "Edit Actions") {
        section("Actions (executed in order)") {
            if (state.actions && state.actions.size() > 0) {
                state.actions.eachWithIndex { action, idx ->
                    href name: "editAction_${idx}", page: "editActionPage",
                         title: "${idx + 1}. ${describeAction(action)}",
                         description: "Tap to edit",
                         params: [actionIndex: idx]
                }
            } else {
                paragraph "<i>No actions defined. Add an action for this rule to do something.</i>"
            }
        }

        if (state.actions && state.actions.size() > 1) {
            section("Reorder Actions") {
                state.actions.eachWithIndex { action, idx ->
                    if (idx > 0) {
                        input "moveUp_${idx}", "button", title: "↑ Move ${idx + 1} Up"
                    }
                    if (idx < state.actions.size() - 1) {
                        input "moveDown_${idx}", "button", title: "↓ Move ${idx + 1} Down"
                    }
                }
            }
        }

        section {
            href name: "addAction", page: "addActionPage", title: "+ Add Action"
        }

        section {
            href name: "backToMain", page: "mainPage", title: "← Done"
        }
    }
}

def addActionPage() {
    state.editingActionIndex = null
    clearActionSettings()

    dynamicPage(name: "addActionPage", title: "Add Action") {
        section("Action Type") {
            input "actionType", "enum", title: "What should happen?",
                  options: getActionTypeOptions(),
                  required: true, submitOnChange: true
        }

        renderActionFields()

        section {
            if (settings.actionType) {
                href name: "saveAction", page: "editActionsPage",
                     title: "Save Action",
                     description: "Save and return to actions list"
            }
            href name: "cancelAction", page: "editActionsPage",
                 title: "Cancel"
        }
    }
}

def editActionPage(params) {
    def actionIndex = params?.actionIndex != null ? params.actionIndex.toInteger() : state.editingActionIndex

    if (actionIndex == null || actionIndex >= (state.actions?.size() ?: 0)) {
        return dynamicPage(name: "editActionPage", title: "Action Not Found") {
            section {
                paragraph "The requested action could not be found."
                href name: "backToActions", page: "editActionsPage", title: "Back to Actions"
            }
        }
    }

    state.editingActionIndex = actionIndex
    def action = state.actions[actionIndex]

    if (state.loadedActionIndex != actionIndex) {
        loadActionSettings(action)
        state.loadedActionIndex = actionIndex
    }

    dynamicPage(name: "editActionPage", title: "Edit Action ${actionIndex + 1}") {
        section("Action Type") {
            input "actionType", "enum", title: "What should happen?",
                  options: getActionTypeOptions(),
                  required: true, submitOnChange: true
        }

        renderActionFields()

        section {
            href name: "saveActionEdit", page: "editActionsPage",
                 title: "Save Changes",
                 description: "Save and return to actions list"
            input "deleteActionBtn", "button", title: "Delete Action"
            href name: "cancelActionEdit", page: "editActionsPage",
                 title: "Cancel"
        }
    }
}

def getActionTypeOptions() {
    return [
        "device_command": "Device Command",
        "toggle_device": "Toggle Device On/Off",
        "set_level": "Set Dimmer Level",
        "set_mode": "Set Hub Mode",
        "set_hsm": "Set HSM Status",
        "set_variable": "Set Variable",
        "delay": "Delay",
        "log": "Log Message",
        "stop": "Stop Rule Execution"
    ]
}

def renderActionFields() {
    switch (settings.actionType) {
        case "device_command":
            section("Device Command Settings") {
                input "actionDevice", "capability.*", title: "Device", required: true, submitOnChange: true
                if (settings.actionDevice) {
                    def cmds = settings.actionDevice.supportedCommands?.collect { it.name }?.sort()
                    input "actionCommand", "enum", title: "Command", options: cmds, required: true
                }
                input "actionParams", "text", title: "Parameters (comma separated, optional)", required: false
            }
            break

        case "toggle_device":
            section("Toggle Device Settings") {
                input "actionDevice", "capability.switch", title: "Device", required: true
            }
            break

        case "set_level":
            section("Set Level Settings") {
                input "actionDevice", "capability.switchLevel", title: "Device", required: true
                input "actionLevel", "number", title: "Level (0-100)", required: true, range: "0..100"
                input "actionDuration", "number", title: "Fade Duration (seconds, optional)", required: false
            }
            break

        case "set_mode":
            section("Set Mode Settings") {
                def modes = location.modes?.collect { it.name }
                input "actionMode", "enum", title: "Mode", options: modes, required: true
            }
            break

        case "set_hsm":
            section("Set HSM Settings") {
                input "actionHsmStatus", "enum", title: "HSM Status",
                      options: ["armAway": "Arm Away", "armHome": "Arm Home",
                               "armNight": "Arm Night", "disarm": "Disarm"],
                      required: true
            }
            break

        case "set_variable":
            section("Set Variable Settings") {
                input "actionVariableName", "text", title: "Variable Name", required: true
                input "actionVariableValue", "text", title: "Value", required: true
            }
            break

        case "delay":
            section("Delay Settings") {
                input "actionDelaySeconds", "number", title: "Delay (seconds)", required: true, range: "1..86400"
            }
            break

        case "log":
            section("Log Settings") {
                input "actionLogMessage", "text", title: "Message", required: true
                input "actionLogLevel", "enum", title: "Level",
                      options: ["info": "Info", "warn": "Warning", "debug": "Debug"],
                      required: true, defaultValue: "info"
            }
            break

        case "stop":
            section {
                paragraph "This action will stop rule execution. Any actions after this will not run."
            }
            break
    }
}

def savePendingAction() {
    def action = buildActionFromSettings()
    if (action) {
        if (!state.actions) state.actions = []
        if (state.editingActionIndex != null && state.editingActionIndex < state.actions.size()) {
            state.actions[state.editingActionIndex] = action
            log.info "Updated action ${state.editingActionIndex + 1}"
        } else {
            state.actions.add(action)
            log.info "Added new action"
        }
        state.updatedAt = now()
    }
    clearActionSettings()
    state.remove("editingActionIndex")
    state.remove("loadedActionIndex")
}

def buildActionFromSettings() {
    if (!settings.actionType) return null
    def action = [type: settings.actionType]

    switch (settings.actionType) {
        case "device_command":
            if (!settings.actionDevice || !settings.actionCommand) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.command = settings.actionCommand
            if (settings.actionParams) {
                action.parameters = settings.actionParams.split(",").collect { it.trim() }
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
            action.value = settings.actionVariableValue
            break

        case "delay":
            if (!settings.actionDelaySeconds) return null
            action.seconds = settings.actionDelaySeconds
            break

        case "log":
            if (!settings.actionLogMessage) return null
            action.message = settings.actionLogMessage
            action.level = settings.actionLogLevel ?: "info"
            break

        case "stop":
            // No additional fields needed
            break
    }

    return action
}

def loadActionSettings(action) {
    clearActionSettings()
    app.updateSetting("actionType", action.type)

    switch (action.type) {
        case "device_command":
        case "toggle_device":
        case "set_level":
            if (action.deviceId) {
                def device = parent.findDevice(action.deviceId)
                if (device) {
                    def capType = action.type == "set_level" ? "capability.switchLevel" :
                                  action.type == "toggle_device" ? "capability.switch" : "capability.*"
                    app.updateSetting("actionDevice", [type: capType, value: device.id])
                }
            }
            if (action.command) app.updateSetting("actionCommand", action.command)
            if (action.parameters) app.updateSetting("actionParams", action.parameters.join(", "))
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            if (action.duration) app.updateSetting("actionDuration", action.duration)
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
            break

        case "delay":
            if (action.seconds) app.updateSetting("actionDelaySeconds", action.seconds)
            break

        case "log":
            if (action.message) app.updateSetting("actionLogMessage", action.message)
            if (action.level) app.updateSetting("actionLogLevel", action.level)
            break
    }
}

def clearActionSettings() {
    ["actionType", "actionDevice", "actionCommand", "actionParams", "actionLevel", "actionDuration",
     "actionMode", "actionHsmStatus", "actionVariableName", "actionVariableValue",
     "actionDelaySeconds", "actionLogMessage", "actionLogLevel"].each {
        app.removeSetting(it)
    }
}

// ==================== BUTTON HANDLER ====================

def appButtonHandler(btn) {
    if (btn == "testRuleBtn") {
        testRule()
    } else if (btn == "deleteTriggerBtn" && state.editingTriggerIndex != null) {
        state.triggers.remove(state.editingTriggerIndex)
        state.updatedAt = now()
        clearTriggerSettings()
        state.remove("editingTriggerIndex")
    } else if (btn == "deleteConditionBtn" && state.editingConditionIndex != null) {
        state.conditions.remove(state.editingConditionIndex)
        state.updatedAt = now()
        clearConditionSettings()
        state.remove("editingConditionIndex")
    } else if (btn == "deleteActionBtn" && state.editingActionIndex != null) {
        state.actions.remove(state.editingActionIndex)
        state.updatedAt = now()
        clearActionSettings()
        state.remove("editingActionIndex")
    } else if (btn.startsWith("moveUp_")) {
        def idx = btn.replace("moveUp_", "").toInteger()
        if (idx > 0 && idx < state.actions.size()) {
            def temp = state.actions[idx]
            state.actions[idx] = state.actions[idx - 1]
            state.actions[idx - 1] = temp
            state.updatedAt = now()
        }
    } else if (btn.startsWith("moveDown_")) {
        def idx = btn.replace("moveDown_", "").toInteger()
        if (idx >= 0 && idx < state.actions.size() - 1) {
            def temp = state.actions[idx]
            state.actions[idx] = state.actions[idx + 1]
            state.actions[idx + 1] = temp
            state.updatedAt = now()
        }
    }
}

// ==================== DESCRIPTION HELPERS ====================

def describeTrigger(trigger) {
    switch (trigger.type) {
        case "device_event":
            def device = parent.findDevice(trigger.deviceId)
            def deviceName = device?.label ?: trigger.deviceId
            def valueMatch = trigger.value ? " ${trigger.operator ?: '=='} '${trigger.value}'" : ""
            def duration = ""
            if (trigger.duration) {
                // Use stored original unit/value if available, otherwise format seconds nicely
                if (trigger.durationValue && trigger.durationUnit) {
                    def unitLabel = trigger.durationUnit == "seconds" ? "s" : (trigger.durationUnit == "minutes" ? "m" : "h")
                    duration = " for ${trigger.durationValue}${unitLabel}"
                } else {
                    // Legacy: just seconds
                    duration = " for ${trigger.duration}s"
                }
            }
            return "When ${deviceName} ${trigger.attribute} changes${valueMatch}${duration}"

        case "button_event":
            def device = parent.findDevice(trigger.deviceId)
            def deviceName = device?.label ?: trigger.deviceId
            def btn = trigger.buttonNumber ? " button ${trigger.buttonNumber}" : ""
            return "When ${deviceName}${btn} is ${trigger.action}"

        case "time":
            if (trigger.time) return "At ${trigger.time}"
            if (trigger.sunrise) return "At sunrise${trigger.offset ? " ${trigger.offset > 0 ? '+' : ''}${trigger.offset}min" : ''}"
            if (trigger.sunset) return "At sunset${trigger.offset ? " ${trigger.offset > 0 ? '+' : ''}${trigger.offset}min" : ''}"
            return "Time trigger"

        case "periodic":
            return "Every ${trigger.interval} ${trigger.unit}"

        case "mode_change":
            def from = trigger.fromMode ? "from ${trigger.fromMode} " : ""
            def to = trigger.toMode ? "to ${trigger.toMode}" : "changes"
            return "When mode ${from}${to}"

        case "hsm_change":
            return trigger.status ? "When HSM becomes ${trigger.status}" : "When HSM changes"

        default:
            return "Unknown trigger: ${trigger.type}"
    }
}

/**
 * Formats a duration trigger for display in logs and messages.
 * Uses original unit/value if available, otherwise displays in seconds.
 */
def formatDurationForDisplay(trigger) {
    if (!trigger?.duration) return ""
    if (trigger.durationValue && trigger.durationUnit) {
        def unitLabel = trigger.durationUnit == "seconds" ? "s" : (trigger.durationUnit == "minutes" ? "m" : "h")
        return "${trigger.durationValue}${unitLabel}"
    }
    // Legacy: just seconds
    return "${trigger.duration}s"
}

def describeCondition(condition) {
    switch (condition.type) {
        case "device_state":
            def device = parent.findDevice(condition.deviceId)
            def deviceName = device?.label ?: condition.deviceId
            return "${deviceName} ${condition.attribute} ${condition.operator} '${condition.value}'"

        case "device_was":
            def device = parent.findDevice(condition.deviceId)
            def deviceName = device?.label ?: condition.deviceId
            return "${deviceName} ${condition.attribute} was '${condition.value}' for ${condition.forSeconds}s"

        case "time_range":
            return "Time is between ${condition.startTime} and ${condition.endTime}"

        case "mode":
            def op = condition.operator == "not_in" ? "is not" : "is"
            return "Mode ${op} ${condition.modes.join(' or ')}"

        case "variable":
            return "Variable '${condition.variableName}' ${condition.operator} '${condition.value}'"

        case "days_of_week":
            return "Day is ${condition.days.join(', ')}"

        case "sun_position":
            return "Sun is ${condition.position}"

        case "hsm_status":
            return "HSM is ${condition.status}"

        default:
            return "Unknown condition: ${condition.type}"
    }
}

def describeAction(action) {
    switch (action.type) {
        case "device_command":
            def device = parent.findDevice(action.deviceId)
            def deviceName = device?.label ?: action.deviceId
            def params = action.parameters ? "(${action.parameters.join(', ')})" : ""
            return "Send '${action.command}${params}' to ${deviceName}"

        case "toggle_device":
            def device = parent.findDevice(action.deviceId)
            def deviceName = device?.label ?: action.deviceId
            return "Toggle ${deviceName}"

        case "set_level":
            def device = parent.findDevice(action.deviceId)
            def deviceName = device?.label ?: action.deviceId
            def duration = action.duration ? " over ${action.duration}s" : ""
            return "Set ${deviceName} to ${action.level}%${duration}"

        case "set_mode":
            return "Set mode to ${action.mode}"

        case "set_hsm":
            return "Set HSM to ${action.status}"

        case "set_variable":
            return "Set variable '${action.variableName}' to '${action.value}'"

        case "delay":
            return "Wait ${action.seconds} seconds"

        case "log":
            return "Log [${action.level}]: '${action.message}'"

        case "stop":
            return "Stop rule execution"

        case "if_then_else":
            def condDesc = action.condition ? describeCondition(action.condition) : "condition"
            def thenCount = action.thenActions?.size() ?: 0
            def elseCount = action.elseActions?.size() ?: 0
            return "If ${condDesc}: then ${thenCount} action(s)${elseCount > 0 ? ', else ' + elseCount + ' action(s)' : ''}"

        case "cancel_delayed":
            return action.delayId == "all" ? "Cancel all delayed actions" : "Cancel delayed '${action.delayId}'"

        case "set_local_variable":
            return "Set local variable '${action.variableName}' to '${action.value}'"

        case "activate_scene":
            def device = parent.findDevice(action.sceneDeviceId)
            def deviceName = device?.label ?: action.sceneDeviceId
            return "Activate scene ${deviceName}"

        case "set_color":
            def colorDev = parent.findDevice(action.deviceId)
            def colorDevName = colorDev?.label ?: action.deviceId
            return "Set ${colorDevName} color to hue:${action.hue}, sat:${action.saturation}, level:${action.level}"

        case "set_color_temperature":
            def ctDev = parent.findDevice(action.deviceId)
            def ctDevName = ctDev?.label ?: action.deviceId
            def ctLevel = action.level ? " at ${action.level}%" : ""
            return "Set ${ctDevName} color temperature to ${action.temperature}K${ctLevel}"

        case "lock":
            def lockDev = parent.findDevice(action.deviceId)
            def lockDevName = lockDev?.label ?: action.deviceId
            return "Lock ${lockDevName}"

        case "unlock":
            def unlockDev = parent.findDevice(action.deviceId)
            def unlockDevName = unlockDev?.label ?: action.deviceId
            return "Unlock ${unlockDevName}"

        case "capture_state":
            def captureCount = action.deviceIds?.size() ?: 0
            def captureId = action.stateId ?: "default"
            return "Capture state of ${captureCount} device(s) (id: ${captureId}) [max 20 captures]"

        case "restore_state":
            def restoreId = action.stateId ?: "default"
            return "Restore state (id: ${restoreId})"

        case "send_notification":
            def notifyDev = parent.findDevice(action.deviceId)
            def notifyDevName = notifyDev?.label ?: action.deviceId
            return "Send notification to ${notifyDevName}: '${action.message}'"

        case "repeat":
            def repeatActions = action.actions?.size() ?: 0
            return "Repeat ${repeatActions} action(s) ${action.times ?: action.count ?: 1} time(s)"

        default:
            return "Unknown action: ${action.type}"
    }
}

// ==================== RULE EXECUTION ====================

def subscribeToTriggers() {
    state.triggers?.each { trigger ->
        switch (trigger.type) {
            case "device_event":
                def device = parent.findDevice(trigger.deviceId)
                if (device) {
                    subscribe(device, trigger.attribute, "handleDeviceEvent")
                }
                break

            case "button_event":
                def device = parent.findDevice(trigger.deviceId)
                if (device) {
                    subscribe(device, trigger.action, "handleButtonEvent")
                }
                break

            case "time":
                if (trigger.time) {
                    schedule(trigger.time, "handleTimeEvent")
                } else if (trigger.sunrise) {
                    def offset = trigger.offset ?: 0
                    schedule(location.sunrise.time + (offset * 60000), "handleTimeEvent")
                } else if (trigger.sunset) {
                    def offset = trigger.offset ?: 0
                    schedule(location.sunset.time + (offset * 60000), "handleTimeEvent")
                }
                break

            case "mode_change":
                subscribe(location, "mode", "handleModeEvent")
                break

            case "hsm_change":
                subscribe(location, "hsmStatus", "handleHsmEvent")
                break
        }
    }
}

def handleDeviceEvent(evt) {
    if (!settings.ruleEnabled) return
    log.debug "Device event: ${evt.device.label} ${evt.name} = ${evt.value}"

    def matchingTrigger = state.triggers?.find { t ->
        t.type == "device_event" &&
        t.deviceId == evt.device.id.toString() &&
        t.attribute == evt.name &&
        (!t.value || evaluateComparison(evt.value, t.operator ?: "equals", t.value))
    }

    if (matchingTrigger) {
        // Check if this trigger has a duration requirement
        if (matchingTrigger.duration && matchingTrigger.duration > 0) {
            def triggerKey = "duration_${matchingTrigger.deviceId}_${matchingTrigger.attribute}"

            // Initialize state maps if needed
            if (!state.durationTimers) state.durationTimers = [:]
            if (!state.durationFired) state.durationFired = [:]

            // Check if this trigger already fired and is waiting for condition to go false
            if (state.durationFired[triggerKey]) {
                log.debug "Duration trigger: already fired, waiting for condition to go false before re-arming"
                return
            }

            if (!state.durationTimers[triggerKey]) {
                // First time condition met - start the timer
                def durationDisplay = formatDurationForDisplay(matchingTrigger)
                log.debug "Duration trigger: condition met, starting ${durationDisplay} timer for ${evt.device.label} ${evt.name}"
                state.durationTimers[triggerKey] = [startTime: now(), trigger: matchingTrigger]
                runIn(matchingTrigger.duration, "checkDurationTrigger", [data: [triggerKey: triggerKey, deviceLabel: evt.device.label, attribute: evt.name]])
            }
            // If timer already running, just let it continue
        } else {
            // No duration - trigger immediately
            executeRule("device_event: ${evt.device.label} ${evt.name}")
        }
    } else {
        // Condition no longer met - cancel any pending duration timer and reset fired state
        def triggersForDevice = state.triggers?.findAll { t ->
            t.type == "device_event" &&
            t.deviceId == evt.device.id.toString() &&
            t.attribute == evt.name &&
            t.duration && t.duration > 0
        }

        triggersForDevice?.each { t ->
            def triggerKey = "duration_${t.deviceId}_${t.attribute}"
            if (state.durationTimers?.get(triggerKey)) {
                log.debug "Duration trigger: condition no longer met, canceling timer for ${evt.device.label} ${evt.name}"
                state.durationTimers.remove(triggerKey)
                unschedule("checkDurationTrigger")
            }
            // Reset the fired flag so it can trigger again next time condition is met
            if (state.durationFired?.get(triggerKey)) {
                log.debug "Duration trigger: condition false, re-arming trigger for ${evt.device.label} ${evt.name}"
                state.durationFired.remove(triggerKey)
            }
        }
    }
}

def checkDurationTrigger(data) {
    def triggerKey = data.triggerKey
    def timerData = state.durationTimers?.get(triggerKey)

    if (!timerData) {
        log.debug "Duration trigger: timer was canceled for ${triggerKey}"
        return
    }

    // Re-check that the condition is still met
    def trigger = timerData.trigger
    def device = parent.findDevice(trigger.deviceId)
    if (!device) {
        state.durationTimers.remove(triggerKey)
        return
    }

    def currentValue = device.currentValue(trigger.attribute)
    def stillMet = !trigger.value || evaluateComparison(currentValue, trigger.operator ?: "equals", trigger.value)

    if (stillMet) {
        def durationDisplay = formatDurationForDisplay(trigger)
        log.debug "Duration trigger: condition still met after ${durationDisplay}, executing rule"
        state.durationTimers.remove(triggerKey)
        // Mark as fired - won't fire again until condition goes false
        if (!state.durationFired) state.durationFired = [:]
        state.durationFired[triggerKey] = true
        executeRule("device_event: ${data.deviceLabel} ${data.attribute} (held for ${durationDisplay})")
    } else {
        log.debug "Duration trigger: condition no longer met at check time"
        state.durationTimers.remove(triggerKey)
    }
}

def handleButtonEvent(evt) {
    if (!settings.ruleEnabled) return
    log.debug "Button event: ${evt.device.label} ${evt.name} = ${evt.value}"

    def matchingTrigger = state.triggers?.find { t ->
        t.type == "button_event" &&
        t.deviceId == evt.device.id.toString() &&
        t.action == evt.name &&
        (!t.buttonNumber || t.buttonNumber.toString() == evt.value)
    }

    if (matchingTrigger) {
        executeRule("button_event: ${evt.device.label} ${evt.name}")
    }
}

def handleTimeEvent() {
    if (!settings.ruleEnabled) return
    executeRule("time trigger")
}

def handleModeEvent(evt) {
    if (!settings.ruleEnabled) return
    def matchingTrigger = state.triggers?.find { t ->
        t.type == "mode_change" &&
        (!t.toMode || t.toMode == evt.value) &&
        (!t.fromMode || t.fromMode == state.previousMode)
    }
    state.previousMode = evt.value

    if (matchingTrigger) {
        executeRule("mode_change: ${evt.value}")
    }
}

def handleHsmEvent(evt) {
    if (!settings.ruleEnabled) return
    def matchingTrigger = state.triggers?.find { t ->
        t.type == "hsm_change" &&
        (!t.status || t.status == evt.value)
    }

    if (matchingTrigger) {
        executeRule("hsm_change: ${evt.value}")
    }
}

def executeRule(triggerSource) {
    log.info "Rule '${settings.ruleName}' triggered by ${triggerSource}"

    // Check conditions
    if (state.conditions && state.conditions.size() > 0) {
        def conditionsMet = evaluateConditions()
        if (!conditionsMet) {
            log.info "Rule '${settings.ruleName}' conditions not met, skipping actions"
            return
        }
    }

    // Execute actions
    state.lastTriggered = now()
    state.executionCount = (state.executionCount ?: 0) + 1
    executeActions()
}

def evaluateConditions() {
    def logic = state.conditionLogic ?: "all"
    def results = state.conditions.collect { evaluateCondition(it) }

    if (logic == "all") {
        return results.every { it }
    } else {
        return results.any { it }
    }
}

def evaluateCondition(condition) {
    switch (condition.type) {
        case "device_state":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentValue = device.currentValue(condition.attribute)
            return evaluateComparison(currentValue, condition.operator, condition.value)

        case "mode":
            def currentMode = location.mode
            def inModes = condition.modes.contains(currentMode)
            return condition.operator == "not_in" ? !inModes : inModes

        case "time_range":
            return timeOfDayIsBetween(toDateTime(condition.startTime), toDateTime(condition.endTime), new Date())

        case "days_of_week":
            def today = new Date().format("EEEE")
            return condition.days.contains(today)

        case "sun_position":
            def sunriseTime = location.sunrise
            def sunsetTime = location.sunset
            def now = new Date()
            def isSunUp = now.after(sunriseTime) && now.before(sunsetTime)
            return condition.position == "up" ? isSunUp : !isSunUp

        case "hsm_status":
            return location.hsmStatus == condition.status

        case "variable":
            // Check local variables first, then global
            def varValue = state.localVariables?."${condition.variableName}"
            if (varValue == null) {
                // Try global variable from parent
                varValue = parent.getVariableValue(condition.variableName)
            }
            return evaluateComparison(varValue, condition.operator, condition.value)

        case "device_was":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentValue = device.currentValue(condition.attribute)
            if (currentValue?.toString() != condition.value?.toString()) return false
            // Check how long it's been in this state
            def events = device.eventsSince(new Date(now() - (condition.forSeconds * 1000)), [max: 10])
            def recentChange = events?.find { it.name == condition.attribute && it.value?.toString() != condition.value?.toString() }
            return recentChange == null

        case "presence":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentPresence = device.currentValue("presence")
            return currentPresence == condition.status

        case "lock":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentLock = device.currentValue("lock")
            return currentLock == condition.status

        case "thermostat_mode":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentMode = device.currentValue("thermostatMode")
            return currentMode == condition.mode

        case "thermostat_state":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentState = device.currentValue("thermostatOperatingState")
            return currentState == condition.state

        case "illuminance":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentLux = device.currentValue("illuminance")
            return evaluateComparison(currentLux, condition.operator, condition.value)

        case "power":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            def currentPower = device.currentValue("power")
            return evaluateComparison(currentPower, condition.operator, condition.value)

        default:
            return true
    }
}

def evaluateComparison(current, operator, target) {
    try {
        switch (operator) {
            case "equals":
                return current?.toString() == target?.toString()
            case "not_equals":
                return current?.toString() != target?.toString()
            case ">":
                return current?.toBigDecimal() > target?.toBigDecimal()
            case "<":
                return current?.toBigDecimal() < target?.toBigDecimal()
            case ">=":
                return current?.toBigDecimal() >= target?.toBigDecimal()
            case "<=":
                return current?.toBigDecimal() <= target?.toBigDecimal()
            default:
                return current?.toString() == target?.toString()
        }
    } catch (Exception e) {
        return current?.toString() == target?.toString()
    }
}

def executeActions() {
    executeActionsFromIndex(0)
}

def executeActionsFromIndex(startIndex) {
    def actions = state.actions ?: []
    for (int i = startIndex; i < actions.size(); i++) {
        def action = actions[i]
        def result = executeAction(action, i)
        if (result == false) {
            break // Stop if action returns false (e.g., stop action)
        } else if (result == "delayed") {
            break // Delay scheduled, will resume later
        }
    }
}

def resumeDelayedActions(data) {
    log.debug "Resuming actions from index ${data.nextIndex} (delayId: ${data.delayId})"
    executeActionsFromIndex(data.nextIndex)
}

def executeAction(action, actionIndex = null) {
    log.debug "Executing action: ${describeAction(action)}"

    switch (action.type) {
        case "device_command":
            def device = parent.findDevice(action.deviceId)
            if (device) {
                if (action.parameters) {
                    device."${action.command}"(*action.parameters)
                } else {
                    device."${action.command}"()
                }
            }
            break

        case "toggle_device":
            def device = parent.findDevice(action.deviceId)
            if (device) {
                if (device.currentValue("switch") == "on") {
                    device.off()
                } else {
                    device.on()
                }
            }
            break

        case "set_level":
            def device = parent.findDevice(action.deviceId)
            if (device) {
                if (action.duration) {
                    device.setLevel(action.level, action.duration)
                } else {
                    device.setLevel(action.level)
                }
            }
            break

        case "set_mode":
            location.setMode(action.mode)
            break

        case "set_hsm":
            sendLocationEvent(name: "hsmSetArm", value: action.status)
            break

        case "set_variable":
            parent.setRuleVariable(action.variableName, action.value)
            break

        case "delay":
            if (actionIndex != null) {
                def delayId = action.delayId ?: "delay_${now()}"
                def handlerName = "resumeDelayedActions"
                log.debug "Scheduling delayed continuation in ${action.seconds} seconds (delayId: ${delayId})"
                runIn(action.seconds, handlerName, [data: [nextIndex: actionIndex + 1, delayId: delayId]])
                return "delayed" // Signal to stop current execution, will resume via scheduled handler
            }
            break

        case "log":
            switch (action.level) {
                case "warn": log.warn action.message; break
                case "debug": log.debug action.message; break
                default: log.info action.message
            }
            break

        case "if_then_else":
            def conditionResult = evaluateCondition(action.condition)
            log.debug "if_then_else condition result: ${conditionResult}"
            if (conditionResult) {
                action.thenActions?.each { thenAction ->
                    if (!executeAction(thenAction)) return false
                }
            } else if (action.elseActions) {
                action.elseActions?.each { elseAction ->
                    if (!executeAction(elseAction)) return false
                }
            }
            break

        case "cancel_delayed":
            if (action.delayId == "all") {
                unschedule()
            } else if (action.delayId) {
                unschedule("delayedAction_${action.delayId}")
            }
            break

        case "set_local_variable":
            if (!state.localVariables) state.localVariables = [:]
            state.localVariables[action.variableName] = action.value
            break

        case "activate_scene":
            def sceneDevice = parent.findDevice(action.sceneDeviceId)
            if (sceneDevice) {
                sceneDevice.on()
            }
            break

        case "set_color":
            def colorDevice = parent.findDevice(action.deviceId)
            if (colorDevice) {
                def colorMap = [:]
                if (action.hue != null) colorMap.hue = action.hue
                if (action.saturation != null) colorMap.saturation = action.saturation
                if (action.level != null) colorMap.level = action.level
                colorDevice.setColor(colorMap)
            }
            break

        case "set_color_temperature":
            def ctDevice = parent.findDevice(action.deviceId)
            if (ctDevice) {
                if (action.level != null) {
                    ctDevice.setColorTemperature(action.temperature, action.level)
                } else {
                    ctDevice.setColorTemperature(action.temperature)
                }
            }
            break

        case "lock":
            def lockDevice = parent.findDevice(action.deviceId)
            if (lockDevice) {
                lockDevice.lock()
            }
            break

        case "unlock":
            def unlockDevice = parent.findDevice(action.deviceId)
            if (unlockDevice) {
                unlockDevice.unlock()
            }
            break

        case "capture_state":
            def captureDevices = action.deviceIds?.collect { parent.findDevice(it) }?.findAll { it != null }
            if (captureDevices) {
                def capturedStates = [:]
                captureDevices.each { dev ->
                    def devState = [:]
                    if (dev.hasCapability("Switch")) devState.switch = dev.currentValue("switch")
                    if (dev.hasCapability("SwitchLevel")) devState.level = dev.currentValue("level")
                    if (dev.hasCapability("ColorControl")) {
                        devState.hue = dev.currentValue("hue")
                        devState.saturation = dev.currentValue("saturation")
                    }
                    if (dev.hasCapability("ColorTemperature")) devState.colorTemperature = dev.currentValue("colorTemperature")
                    capturedStates[dev.id.toString()] = devState
                }
                def stateKey = action.stateId ?: "default"
                // Store in parent app so other rules can access it
                def saveResult = parent.saveCapturedState(stateKey, capturedStates)
                log.debug "Captured states for ${captureDevices.size()} devices (stateId: ${stateKey}, total: ${saveResult?.totalStored}/${saveResult?.maxLimit})"

                // Log warnings about capacity
                if (saveResult?.deletedStates) {
                    log.warn "Captured state limit reached: Deleted old state(s) '${saveResult.deletedStates.join(', ')}' to make room"
                }
                if (saveResult?.nearLimit) {
                    log.warn "Captured states nearing limit: ${saveResult.totalStored}/${saveResult.maxLimit} slots used"
                }
            }
            break

        case "restore_state":
            def stateKey = action.stateId ?: "default"
            // Get from parent app so any rule can restore states captured by any other rule
            def savedStates = parent.getCapturedState(stateKey)
            if (savedStates) {
                savedStates.each { deviceId, devState ->
                    def dev = parent.findDevice(deviceId)
                    if (dev) {
                        if (devState.hue != null && devState.saturation != null) {
                            dev.setColor([hue: devState.hue, saturation: devState.saturation, level: devState.level ?: 100])
                        } else if (devState.colorTemperature != null) {
                            dev.setColorTemperature(devState.colorTemperature)
                        }
                        if (devState.level != null && !devState.hue) {
                            dev.setLevel(devState.level)
                        }
                        if (devState.switch == "on") {
                            dev.on()
                        } else if (devState.switch == "off") {
                            dev.off()
                        }
                    }
                }
                log.debug "Restored states for ${savedStates.size()} devices (stateId: ${stateKey})"
            } else {
                log.warn "No captured state found for stateId: ${stateKey}"
            }
            break

        case "send_notification":
            def notifyDevice = parent.findDevice(action.deviceId)
            if (notifyDevice) {
                notifyDevice.deviceNotification(action.message)
            }
            break

        case "repeat":
            def repeatCount = action.times ?: action.count ?: 1
            for (int r = 0; r < repeatCount; r++) {
                action.actions?.each { repeatAction ->
                    def result = executeAction(repeatAction)
                    if (result == false) return false
                }
            }
            break

        case "stop":
            return false // Signal to stop execution
    }

    return true // Continue execution
}

def testRule() {
    log.info "Testing rule '${settings.ruleName}' (dry run)"

    def results = [
        ruleName: settings.ruleName,
        conditionsMet: true,
        conditionResults: [],
        wouldExecute: true,
        actions: []
    ]

    if (state.conditions && state.conditions.size() > 0) {
        state.conditions.each { condition ->
            def result = evaluateCondition(condition)
            results.conditionResults << [
                condition: describeCondition(condition),
                result: result
            ]
        }

        def logic = state.conditionLogic ?: "all"
        if (logic == "all") {
            results.conditionsMet = results.conditionResults.every { it.result }
        } else {
            results.conditionsMet = results.conditionResults.any { it.result }
        }
        results.wouldExecute = results.conditionsMet
    }

    if (results.wouldExecute) {
        results.actions = state.actions.collect { describeAction(it) }
    }

    log.info "Test results: ${results}"
}

def formatTimestamp(timestamp) {
    if (!timestamp) return "Never"
    try {
        return new Date(timestamp).format("yyyy-MM-dd HH:mm:ss")
    } catch (Exception e) {
        return timestamp.toString()
    }
}

// ==================== API FOR PARENT ====================

def getRuleData() {
    return [
        id: app.id.toString(),
        name: settings.ruleName,
        description: settings.ruleDescription,
        enabled: settings.ruleEnabled ?: false,
        triggers: state.triggers ?: [],
        conditions: state.conditions ?: [],
        conditionLogic: state.conditionLogic ?: "all",
        actions: state.actions ?: [],
        localVariables: state.localVariables ?: [:],
        createdAt: state.createdAt,
        updatedAt: state.updatedAt,
        lastTriggered: state.lastTriggered,
        executionCount: state.executionCount ?: 0
    ]
}

def updateRuleFromParent(data) {
    if (data.name != null) {
        app.updateSetting("ruleName", data.name)
        // Update the app label to match (for display in Apps list)
        app.updateLabel(data.name)
    }
    if (data.description != null) app.updateSetting("ruleDescription", data.description)
    if (data.enabled != null) app.updateSetting("ruleEnabled", data.enabled)
    if (data.triggers != null) state.triggers = data.triggers
    if (data.conditions != null) state.conditions = data.conditions
    if (data.conditionLogic != null) state.conditionLogic = data.conditionLogic
    if (data.actions != null) state.actions = data.actions
    if (data.localVariables != null) state.localVariables = data.localVariables
    state.updatedAt = now()

    unsubscribe()
    unschedule()
    if (settings.ruleEnabled) {
        subscribeToTriggers()
    }
}

def enableRule() {
    app.updateSetting("ruleEnabled", true)
    state.updatedAt = now()
    unsubscribe()
    subscribeToTriggers()
}

def disableRule() {
    app.updateSetting("ruleEnabled", false)
    state.updatedAt = now()
    unsubscribe()
    unschedule()
}

def testRuleFromParent() {
    testRule()
    return [
        ruleId: app.id.toString(),
        ruleName: settings.ruleName,
        conditionsMet: true,
        wouldExecute: true,
        actions: state.actions?.collect { describeAction(it) } ?: []
    ]
}
