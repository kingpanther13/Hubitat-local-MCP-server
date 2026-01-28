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
    // IMPORTANT: Only initialize arrays if they don't exist yet
    // This prevents overwriting data that may have been set by updateRuleFromParent
    // during rule creation via MCP (race condition fix)
    // Using atomicState for immediate persistence - prevents race condition with enabled=true
    if (atomicState.triggers == null) atomicState.triggers = []
    if (atomicState.conditions == null) atomicState.conditions = []
    if (atomicState.actions == null) atomicState.actions = []
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
    // Clear any orphaned settings from sub-pages to prevent "required fields" validation errors
    // This ensures that partially-filled trigger/condition/action forms don't block saving
    clearAllSubPageSettings()

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

        section("Triggers (${atomicState.triggers?.size() ?: 0})") {
            if (atomicState.triggers && atomicState.triggers.size() > 0) {
                atomicState.triggers.eachWithIndex { trigger, idx ->
                    paragraph "${idx + 1}. ${describeTrigger(trigger)}"
                }
            } else {
                paragraph "<i>No triggers defined</i>"
            }
            href name: "editTriggers", page: "editTriggersPage", title: "Edit Triggers"
        }

        section("Conditions (${atomicState.conditions?.size() ?: 0})") {
            if (atomicState.conditions && atomicState.conditions.size() > 0) {
                def logic = settings.conditionLogic == "any" ? "ANY" : "ALL"
                paragraph "<i>Logic: ${logic} conditions must be true</i>"
                atomicState.conditions.eachWithIndex { condition, idx ->
                    paragraph "${idx + 1}. ${describeCondition(condition)}"
                }
            } else {
                paragraph "<i>No conditions (rule always executes when triggered)</i>"
            }
            href name: "editConditions", page: "editConditionsPage", title: "Edit Conditions"
        }

        section("Actions (${atomicState.actions?.size() ?: 0})") {
            if (atomicState.actions && atomicState.actions.size() > 0) {
                atomicState.actions.eachWithIndex { action, idx ->
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
            if (atomicState.triggers && atomicState.triggers.size() > 0) {
                atomicState.triggers.eachWithIndex { trigger, idx ->
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

    if (triggerIndex == null || triggerIndex < 0 || triggerIndex >= (atomicState.triggers?.size() ?: 0)) {
        return dynamicPage(name: "editTriggerPage", title: "Trigger Not Found") {
            section {
                paragraph "The requested trigger could not be found."
                href name: "backToTriggers", page: "editTriggersPage", title: "Back to Triggers"
            }
        }
    }

    state.editingTriggerIndex = triggerIndex
    def trigger = atomicState.triggers[triggerIndex]

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
        def list = atomicState.triggers ?: []
        if (state.editingTriggerIndex != null && state.editingTriggerIndex >= 0 && state.editingTriggerIndex < list.size()) {
            list[state.editingTriggerIndex] = trigger
            atomicState.triggers = list
            log.info "Updated trigger ${state.editingTriggerIndex + 1}"
        } else {
            list.add(trigger)
            atomicState.triggers = list
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

/**
 * Clears all sub-page settings (triggers, conditions, actions) to prevent
 * "required fields" validation errors when orphaned settings exist from
 * partially-completed forms on sub-pages.
 */
def clearAllSubPageSettings() {
    clearTriggerSettings()
    clearConditionSettings()
    clearActionSettings()
    // Clear editing state flags
    state.remove("editingTriggerIndex")
    state.remove("loadedTriggerIndex")
    state.remove("editingConditionIndex")
    state.remove("loadedConditionIndex")
    state.remove("editingActionIndex")
    state.remove("loadedActionIndex")
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
                  defaultValue: settings.conditionLogic ?: "all", submitOnChange: true
            // Persist conditionLogic to settings using app.updateSetting for proper persistence across hub restarts
            if (settings.conditionLogic) {
                app.updateSetting("conditionLogic", settings.conditionLogic)
            }
        }

        section("Current Conditions") {
            if (atomicState.conditions && atomicState.conditions.size() > 0) {
                atomicState.conditions.eachWithIndex { condition, idx ->
                    href name: "editCondition_${idx}", page: "editConditionPage",
                         title: "${idx + 1}. ${describeCondition(condition)}",
                         description: "Tap to edit",
                         params: [conditionIndex: idx]
                }
            } else {
                paragraph "<i>No conditions (rule always executes when triggered)</i>"
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

    if (conditionIndex == null || conditionIndex < 0 || conditionIndex >= (atomicState.conditions?.size() ?: 0)) {
        return dynamicPage(name: "editConditionPage", title: "Condition Not Found") {
            section {
                paragraph "The requested condition could not be found."
                href name: "backToConditions", page: "editConditionsPage", title: "Back to Conditions"
            }
        }
    }

    state.editingConditionIndex = conditionIndex
    def condition = atomicState.conditions[conditionIndex]

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
        "hsm_status": "HSM Status",
        "presence": "Presence Sensor",
        "lock": "Lock Status",
        "thermostat_mode": "Thermostat Mode",
        "thermostat_state": "Thermostat Operating State",
        "illuminance": "Illuminance Level",
        "power": "Power Level"
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

        case "presence":
            section("Presence Sensor Settings") {
                input "conditionDevice", "capability.presenceSensor", title: "Presence Sensor", required: true
                input "conditionPresenceStatus", "enum", title: "Status",
                      options: ["present": "Present", "not present": "Not Present"],
                      required: true
            }
            break

        case "lock":
            section("Lock Status Settings") {
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
                      options: ["auto": "Auto", "cool": "Cool", "heat": "Heat", "off": "Off", "emergency heat": "Emergency Heat"],
                      required: true
            }
            break

        case "thermostat_state":
            section("Thermostat Operating State Settings") {
                input "conditionDevice", "capability.thermostat", title: "Thermostat", required: true
                input "conditionThermostatState", "enum", title: "Operating State",
                      options: ["idle": "Idle", "heating": "Heating", "cooling": "Cooling", "fan only": "Fan Only", "pending heat": "Pending Heat", "pending cool": "Pending Cool"],
                      required: true
            }
            break

        case "illuminance":
            section("Illuminance Level Settings") {
                input "conditionDevice", "capability.illuminanceMeasurement", title: "Illuminance Sensor", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: ["equals": "Equals", "not_equals": "Not Equals",
                               ">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                      required: true, defaultValue: "<"
                input "conditionValue", "number", title: "Lux Value", required: true
            }
            break

        case "power":
            section("Power Level Settings") {
                input "conditionDevice", "capability.powerMeter", title: "Power Meter Device", required: true
                input "conditionOperator", "enum", title: "Comparison",
                      options: ["equals": "Equals", "not_equals": "Not Equals",
                               ">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                      required: true, defaultValue: ">"
                input "conditionValue", "number", title: "Power (Watts)", required: true
            }
            break
    }
}

def savePendingCondition() {
    def condition = buildConditionFromSettings()
    if (condition) {
        def list = atomicState.conditions ?: []
        if (state.editingConditionIndex != null && state.editingConditionIndex >= 0 && state.editingConditionIndex < list.size()) {
            list[state.editingConditionIndex] = condition
            atomicState.conditions = list
            log.info "Updated condition ${state.editingConditionIndex + 1}"
        } else {
            list.add(condition)
            atomicState.conditions = list
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
            if (!settings.conditionDevice || settings.conditionValue == null) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.operator = settings.conditionOperator ?: "<"
            condition.value = settings.conditionValue
            break

        case "power":
            if (!settings.conditionDevice || settings.conditionValue == null) return null
            condition.deviceId = settings.conditionDevice.id.toString()
            condition.operator = settings.conditionOperator ?: ">"
            condition.value = settings.conditionValue
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
            // Support both 'start'/'end' (MCP format) and 'startTime'/'endTime' (UI format) for backwards compatibility
            def startTime = condition.start ?: condition.startTime
            def endTime = condition.end ?: condition.endTime
            if (startTime) app.updateSetting("conditionStartTime", startTime)
            if (endTime) app.updateSetting("conditionEndTime", endTime)
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

        case "presence":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.presenceSensor", value: device.id])
            }
            if (condition.status) app.updateSetting("conditionPresenceStatus", condition.status)
            break

        case "lock":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.lock", value: device.id])
            }
            if (condition.status) app.updateSetting("conditionLockStatus", condition.status)
            break

        case "thermostat_mode":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.mode) app.updateSetting("conditionThermostatMode", condition.mode)
            break

        case "thermostat_state":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.state) app.updateSetting("conditionThermostatState", condition.state)
            break

        case "illuminance":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.illuminanceMeasurement", value: device.id])
            }
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value != null) app.updateSetting("conditionValue", condition.value)
            break

        case "power":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("conditionDevice", [type: "capability.powerMeter", value: device.id])
            }
            if (condition.operator) app.updateSetting("conditionOperator", condition.operator)
            if (condition.value != null) app.updateSetting("conditionValue", condition.value)
            break
    }
}

def clearConditionSettings() {
    ["conditionType", "conditionDevice", "conditionAttribute", "conditionOperator", "conditionValue",
     "conditionDuration", "conditionStartTime", "conditionEndTime", "conditionModes", "conditionModeOperator",
     "conditionVariableName", "conditionDays", "conditionSunPosition", "conditionHsmStatus",
     "conditionPresenceStatus", "conditionLockStatus", "conditionThermostatMode", "conditionThermostatState"].each {
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
            if (atomicState.actions && atomicState.actions.size() > 0) {
                atomicState.actions.eachWithIndex { action, idx ->
                    href name: "editAction_${idx}", page: "editActionPage",
                         title: "${idx + 1}. ${describeAction(action)}",
                         description: "Tap to edit",
                         params: [actionIndex: idx]
                }
            } else {
                paragraph "<i>No actions defined. Add an action for this rule to do something.</i>"
            }
        }

        if (atomicState.actions && atomicState.actions.size() > 1) {
            section("Reorder Actions") {
                atomicState.actions.eachWithIndex { action, idx ->
                    if (idx > 0) {
                        input "moveUp_${idx}", "button", title: "↑ Move ${idx + 1} Up"
                    }
                    if (idx < atomicState.actions.size() - 1) {
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

    if (actionIndex == null || actionIndex < 0 || actionIndex >= (atomicState.actions?.size() ?: 0)) {
        return dynamicPage(name: "editActionPage", title: "Action Not Found") {
            section {
                paragraph "The requested action could not be found."
                href name: "backToActions", page: "editActionsPage", title: "Back to Actions"
            }
        }
    }

    state.editingActionIndex = actionIndex
    def action = atomicState.actions[actionIndex]

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
        "set_color": "Set Color (RGB)",
        "set_color_temperature": "Set Color Temperature",
        "lock": "Lock Device",
        "unlock": "Unlock Device",
        "activate_scene": "Activate Scene",
        "set_mode": "Set Hub Mode",
        "set_hsm": "Set HSM Status",
        "set_variable": "Set Hub Variable",
        "set_local_variable": "Set Local Variable",
        "send_notification": "Send Notification",
        "capture_state": "Capture Device State",
        "restore_state": "Restore Device State",
        "delay": "Delay",
        "cancel_delayed": "Cancel Delayed Actions",
        "if_then_else": "If-Then-Else (Conditional)",
        "repeat": "Repeat Actions",
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

        case "set_color":
            section("Set Color Settings") {
                input "actionDevice", "capability.colorControl", title: "Device", required: true
                input "actionHue", "number", title: "Hue (0-100)", required: true, range: "0..100"
                input "actionSaturation", "number", title: "Saturation (0-100)", required: true, range: "0..100"
                input "actionLevel", "number", title: "Level (0-100)", required: false, range: "0..100"
            }
            break

        case "set_color_temperature":
            section("Set Color Temperature Settings") {
                input "actionDevice", "capability.colorTemperature", title: "Device", required: true
                input "actionColorTemperature", "number", title: "Color Temperature (Kelvin)", required: true, range: "1000..10000"
                input "actionLevel", "number", title: "Level (0-100, optional)", required: false, range: "0..100"
            }
            break

        case "lock":
            section("Lock Device Settings") {
                input "actionDevice", "capability.lock", title: "Lock Device", required: true
            }
            break

        case "unlock":
            section("Unlock Device Settings") {
                input "actionDevice", "capability.lock", title: "Lock Device", required: true
            }
            break

        case "activate_scene":
            section("Activate Scene Settings") {
                input "actionSceneDevice", "capability.switch", title: "Scene Device", required: true
                paragraph "<i>Select a scene activator device. When triggered, this will turn the device on to activate the scene.</i>"
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
            section("Set Hub Variable Settings") {
                input "actionVariableName", "text", title: "Variable Name", required: true
                input "actionVariableValue", "text", title: "Value", required: true
                paragraph "<i>Sets a hub-level variable that persists across rules.</i>"
            }
            break

        case "set_local_variable":
            section("Set Local Variable Settings") {
                input "actionLocalVariableName", "text", title: "Variable Name", required: true
                input "actionLocalVariableValue", "text", title: "Value", required: true
                paragraph "<i>Sets a variable local to this rule only.</i>"
            }
            break

        case "send_notification":
            section("Send Notification Settings") {
                input "actionNotificationDevice", "capability.notification", title: "Notification Device", required: true
                input "actionNotificationMessage", "text", title: "Message", required: true
            }
            break

        case "capture_state":
            section("Capture Device State Settings") {
                input "actionCaptureDevices", "capability.*", title: "Devices to Capture", required: true, multiple: true
                input "actionCaptureStateId", "text", title: "State ID (optional)", required: false, defaultValue: "default"
                paragraph "<i>Captures switch, level, color, and color temperature states. Max 20 captured states stored. Use 'Restore State' to restore later.</i>"
            }
            break

        case "restore_state":
            section("Restore Device State Settings") {
                input "actionRestoreStateId", "text", title: "State ID to Restore", required: false, defaultValue: "default"
                paragraph "<i>Restores previously captured device states.</i>"
            }
            break

        case "delay":
            section("Delay Settings") {
                input "actionDelaySeconds", "number", title: "Delay (seconds)", required: true, range: "1..86400"
                input "actionDelayId", "text", title: "Delay ID (optional)", required: false
                paragraph "<i>Optional: Give this delay an ID to cancel it later with 'Cancel Delayed Actions'.</i>"
            }
            break

        case "cancel_delayed":
            section("Cancel Delayed Actions Settings") {
                input "actionCancelDelayId", "enum", title: "What to Cancel",
                      options: ["all": "Cancel ALL Delayed Actions", "specific": "Cancel Specific Delay ID"],
                      required: true, submitOnChange: true, defaultValue: "all"
                if (settings.actionCancelDelayId == "specific") {
                    input "actionCancelSpecificId", "text", title: "Delay ID to Cancel", required: true
                }
            }
            break

        case "if_then_else":
            section("If-Then-Else Settings") {
                paragraph "<b>Condition Type:</b>"
                input "actionIfConditionType", "enum", title: "Condition Type",
                      options: getConditionTypeOptions(),
                      required: true, submitOnChange: true
                renderIfConditionFields()
                paragraph "<hr><b>Note:</b> This creates a conditional branch. Then/Else actions must be configured via MCP tools or will be empty."
            }
            break

        case "repeat":
            section("Repeat Actions Settings") {
                input "actionRepeatCount", "number", title: "Number of Times to Repeat", required: true, range: "1..100", defaultValue: 1
                paragraph "<i>Note: The actions to repeat must be configured via MCP tools. This UI creates an empty repeat container.</i>"
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

/**
 * Renders condition fields for if_then_else action type
 */
def renderIfConditionFields() {
    switch (settings.actionIfConditionType) {
        case "device_state":
            input "actionIfDevice", "capability.*", title: "Device", required: true, submitOnChange: true
            if (settings.actionIfDevice) {
                def attrs = settings.actionIfDevice.supportedAttributes?.collect { it.name }?.unique()?.sort()
                input "actionIfAttribute", "enum", title: "Attribute", options: attrs, required: true
            }
            input "actionIfOperator", "enum", title: "Comparison",
                  options: ["equals": "Equals", "not_equals": "Not Equals",
                           ">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                  required: true, defaultValue: "equals"
            input "actionIfValue", "text", title: "Value", required: true
            break

        case "mode":
            def modes = location.modes?.collect { it.name }
            input "actionIfModes", "enum", title: "Mode(s)", options: modes, multiple: true, required: true
            input "actionIfModeOperator", "enum", title: "Condition",
                  options: ["in": "Is one of", "not_in": "Is not one of"],
                  required: true, defaultValue: "in"
            break

        case "time_range":
            input "actionIfStartTime", "time", title: "Start Time", required: true
            input "actionIfEndTime", "time", title: "End Time", required: true
            break

        case "variable":
            input "actionIfVariableName", "text", title: "Variable Name", required: true
            input "actionIfOperator", "enum", title: "Comparison",
                  options: ["equals": "Equals", "not_equals": "Not Equals",
                           ">": "Greater Than", "<": "Less Than"],
                  required: true, defaultValue: "equals"
            input "actionIfValue", "text", title: "Value", required: true
            break

        case "hsm_status":
            input "actionIfHsmStatus", "enum", title: "HSM Status",
                  options: ["armedAway": "Armed Away", "armedHome": "Armed Home",
                           "armedNight": "Armed Night", "disarmed": "Disarmed"],
                  required: true
            break

        case "sun_position":
            input "actionIfSunPosition", "enum", title: "Sun is",
                  options: ["up": "Up (daytime)", "down": "Down (nighttime)"],
                  required: true
            break

        case "days_of_week":
            input "actionIfDays", "enum", title: "Days",
                  options: ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
                  multiple: true, required: true
            break

        case "device_was":
            input "actionIfDevice", "capability.*", title: "Device", required: true, submitOnChange: true
            if (settings.actionIfDevice) {
                def attrs = settings.actionIfDevice.supportedAttributes?.collect { it.name }?.unique()?.sort()
                input "actionIfAttribute", "enum", title: "Attribute", options: attrs, required: true
            }
            input "actionIfValue", "text", title: "Has Been Value", required: true
            input "actionIfDuration", "number", title: "For Seconds", required: true, range: "1..86400"
            break

        case "presence":
            input "actionIfDevice", "capability.presenceSensor", title: "Presence Sensor", required: true
            input "actionIfPresenceStatus", "enum", title: "Status",
                  options: ["present": "Present", "not present": "Not Present"],
                  required: true
            break

        case "lock":
            input "actionIfDevice", "capability.lock", title: "Lock Device", required: true
            input "actionIfLockStatus", "enum", title: "Status",
                  options: ["locked": "Locked", "unlocked": "Unlocked"],
                  required: true
            break

        case "thermostat_mode":
            input "actionIfDevice", "capability.thermostat", title: "Thermostat", required: true
            input "actionIfThermostatMode", "enum", title: "Mode",
                  options: ["auto": "Auto", "cool": "Cool", "heat": "Heat", "off": "Off", "emergency heat": "Emergency Heat"],
                  required: true
            break

        case "thermostat_state":
            input "actionIfDevice", "capability.thermostat", title: "Thermostat", required: true
            input "actionIfThermostatState", "enum", title: "Operating State",
                  options: ["idle": "Idle", "heating": "Heating", "cooling": "Cooling", "fan only": "Fan Only",
                           "pending heat": "Pending Heat", "pending cool": "Pending Cool"],
                  required: true
            break

        case "illuminance":
            input "actionIfDevice", "capability.illuminanceMeasurement", title: "Illuminance Sensor", required: true
            input "actionIfOperator", "enum", title: "Comparison",
                  options: [">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                  required: true, defaultValue: "<"
            input "actionIfValue", "number", title: "Lux Value", required: true
            break

        case "power":
            input "actionIfDevice", "capability.powerMeter", title: "Power Meter", required: true
            input "actionIfOperator", "enum", title: "Comparison",
                  options: [">": "Greater Than", "<": "Less Than", ">=": "Greater/Equal", "<=": "Less/Equal"],
                  required: true, defaultValue: ">"
            input "actionIfValue", "number", title: "Watts", required: true
            break
    }
}

def savePendingAction() {
    def action = buildActionFromSettings()
    if (action) {
        def list = atomicState.actions ?: []
        if (state.editingActionIndex != null && state.editingActionIndex >= 0 && state.editingActionIndex < list.size()) {
            list[state.editingActionIndex] = action
            atomicState.actions = list
            log.info "Updated action ${state.editingActionIndex + 1}"
        } else {
            list.add(action)
            atomicState.actions = list
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

        case "set_color":
            if (!settings.actionDevice || settings.actionHue == null || settings.actionSaturation == null) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.hue = settings.actionHue
            action.saturation = settings.actionSaturation
            if (settings.actionLevel != null) action.level = settings.actionLevel
            break

        case "set_color_temperature":
            if (!settings.actionDevice || settings.actionColorTemperature == null) return null
            action.deviceId = settings.actionDevice.id.toString()
            action.temperature = settings.actionColorTemperature
            if (settings.actionLevel != null) action.level = settings.actionLevel
            break

        case "lock":
            if (!settings.actionDevice) return null
            action.deviceId = settings.actionDevice.id.toString()
            break

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
            action.value = settings.actionVariableValue
            break

        case "set_local_variable":
            if (!settings.actionLocalVariableName) return null
            action.variableName = settings.actionLocalVariableName
            action.value = settings.actionLocalVariableValue
            break

        case "send_notification":
            if (!settings.actionNotificationDevice || !settings.actionNotificationMessage) return null
            action.deviceId = settings.actionNotificationDevice.id.toString()
            action.message = settings.actionNotificationMessage
            break

        case "capture_state":
            if (!settings.actionCaptureDevices) return null
            action.deviceIds = settings.actionCaptureDevices.collect { it.id.toString() }
            action.stateId = settings.actionCaptureStateId ?: "default"
            break

        case "restore_state":
            action.stateId = settings.actionRestoreStateId ?: "default"
            break

        case "delay":
            if (!settings.actionDelaySeconds) return null
            action.seconds = settings.actionDelaySeconds
            if (settings.actionDelayId) action.delayId = settings.actionDelayId
            break

        case "cancel_delayed":
            if (settings.actionCancelDelayId == "all") {
                action.delayId = "all"
            } else if (settings.actionCancelDelayId == "specific" && settings.actionCancelSpecificId) {
                action.delayId = settings.actionCancelSpecificId
            } else {
                action.delayId = "all"  // Default to all if not specified
            }
            break

        case "if_then_else":
            def condition = buildIfConditionFromSettings()
            if (!condition) return null
            action.condition = condition
            action.thenActions = []  // Empty - must be configured via MCP tools
            action.elseActions = []  // Empty - must be configured via MCP tools
            break

        case "repeat":
            action.count = settings.actionRepeatCount ?: 1
            action.actions = []  // Empty - must be configured via MCP tools
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

/**
 * Builds the condition object for if_then_else actions from UI settings
 */
def buildIfConditionFromSettings() {
    if (!settings.actionIfConditionType) return null
    def condition = [type: settings.actionIfConditionType]

    switch (settings.actionIfConditionType) {
        case "device_state":
            if (!settings.actionIfDevice || !settings.actionIfAttribute) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.attribute = settings.actionIfAttribute
            condition.operator = settings.actionIfOperator ?: "equals"
            condition.value = settings.actionIfValue
            break

        case "mode":
            if (!settings.actionIfModes) return null
            condition.modes = settings.actionIfModes
            condition.operator = settings.actionIfModeOperator ?: "in"
            break

        case "time_range":
            if (!settings.actionIfStartTime || !settings.actionIfEndTime) return null
            condition.startTime = formatTimeInput(settings.actionIfStartTime)
            condition.endTime = formatTimeInput(settings.actionIfEndTime)
            break

        case "variable":
            if (!settings.actionIfVariableName) return null
            condition.variableName = settings.actionIfVariableName
            condition.operator = settings.actionIfOperator ?: "equals"
            condition.value = settings.actionIfValue
            break

        case "hsm_status":
            if (!settings.actionIfHsmStatus) return null
            condition.status = settings.actionIfHsmStatus
            break

        case "sun_position":
            if (!settings.actionIfSunPosition) return null
            condition.position = settings.actionIfSunPosition
            break

        case "days_of_week":
            if (!settings.actionIfDays) return null
            condition.days = settings.actionIfDays
            break

        case "device_was":
            if (!settings.actionIfDevice || !settings.actionIfAttribute) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.attribute = settings.actionIfAttribute
            condition.value = settings.actionIfValue
            condition.forSeconds = settings.actionIfDuration
            break

        case "presence":
            if (!settings.actionIfDevice) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.status = settings.actionIfPresenceStatus
            break

        case "lock":
            if (!settings.actionIfDevice) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.status = settings.actionIfLockStatus
            break

        case "thermostat_mode":
            if (!settings.actionIfDevice) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.mode = settings.actionIfThermostatMode
            break

        case "thermostat_state":
            if (!settings.actionIfDevice) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.state = settings.actionIfThermostatState
            break

        case "illuminance":
            if (!settings.actionIfDevice || settings.actionIfValue == null) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.operator = settings.actionIfOperator ?: "<"
            condition.value = settings.actionIfValue
            break

        case "power":
            if (!settings.actionIfDevice || settings.actionIfValue == null) return null
            condition.deviceId = settings.actionIfDevice.id.toString()
            condition.operator = settings.actionIfOperator ?: ">"
            condition.value = settings.actionIfValue
            break
    }

    return condition
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

        case "set_color":
            if (action.deviceId) {
                def device = parent.findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.colorControl", value: device.id])
            }
            if (action.hue != null) app.updateSetting("actionHue", action.hue)
            if (action.saturation != null) app.updateSetting("actionSaturation", action.saturation)
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            break

        case "set_color_temperature":
            if (action.deviceId) {
                def device = parent.findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.colorTemperature", value: device.id])
            }
            if (action.temperature != null) app.updateSetting("actionColorTemperature", action.temperature)
            if (action.level != null) app.updateSetting("actionLevel", action.level)
            break

        case "lock":
        case "unlock":
            if (action.deviceId) {
                def device = parent.findDevice(action.deviceId)
                if (device) app.updateSetting("actionDevice", [type: "capability.lock", value: device.id])
            }
            break

        case "activate_scene":
            if (action.sceneDeviceId) {
                def device = parent.findDevice(action.sceneDeviceId)
                if (device) app.updateSetting("actionSceneDevice", [type: "capability.switch", value: device.id])
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
            break

        case "set_local_variable":
            if (action.variableName) app.updateSetting("actionLocalVariableName", action.variableName)
            if (action.value) app.updateSetting("actionLocalVariableValue", action.value)
            break

        case "send_notification":
            if (action.deviceId) {
                def device = parent.findDevice(action.deviceId)
                if (device) app.updateSetting("actionNotificationDevice", [type: "capability.notification", value: device.id])
            }
            if (action.message) app.updateSetting("actionNotificationMessage", action.message)
            break

        case "capture_state":
            if (action.deviceIds) {
                // For multiple devices, we need to load them as a list
                def devices = action.deviceIds.collect { parent.findDevice(it) }.findAll { it != null }
                if (devices) app.updateSetting("actionCaptureDevices", [type: "capability.*", value: devices.collect { it.id }])
            }
            if (action.stateId) app.updateSetting("actionCaptureStateId", action.stateId)
            break

        case "restore_state":
            if (action.stateId) app.updateSetting("actionRestoreStateId", action.stateId)
            break

        case "delay":
            if (action.seconds) app.updateSetting("actionDelaySeconds", action.seconds)
            if (action.delayId) app.updateSetting("actionDelayId", action.delayId)
            break

        case "cancel_delayed":
            if (action.delayId == "all") {
                app.updateSetting("actionCancelDelayId", "all")
            } else if (action.delayId) {
                app.updateSetting("actionCancelDelayId", "specific")
                app.updateSetting("actionCancelSpecificId", action.delayId)
            }
            break

        case "if_then_else":
            if (action.condition) {
                loadIfConditionSettings(action.condition)
            }
            break

        case "repeat":
            if (action.count) app.updateSetting("actionRepeatCount", action.count)
            else if (action.times) app.updateSetting("actionRepeatCount", action.times)
            break

        case "log":
            if (action.message) app.updateSetting("actionLogMessage", action.message)
            if (action.level) app.updateSetting("actionLogLevel", action.level)
            break
    }
}

/**
 * Loads condition settings for if_then_else action type
 */
def loadIfConditionSettings(condition) {
    if (!condition?.type) return
    app.updateSetting("actionIfConditionType", condition.type)

    switch (condition.type) {
        case "device_state":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.*", value: device.id])
            }
            if (condition.attribute) app.updateSetting("actionIfAttribute", condition.attribute)
            if (condition.operator) app.updateSetting("actionIfOperator", condition.operator)
            if (condition.value) app.updateSetting("actionIfValue", condition.value)
            break

        case "mode":
            if (condition.modes) app.updateSetting("actionIfModes", condition.modes)
            if (condition.operator) app.updateSetting("actionIfModeOperator", condition.operator)
            break

        case "time_range":
            if (condition.startTime) app.updateSetting("actionIfStartTime", condition.startTime)
            if (condition.endTime) app.updateSetting("actionIfEndTime", condition.endTime)
            break

        case "variable":
            if (condition.variableName) app.updateSetting("actionIfVariableName", condition.variableName)
            if (condition.operator) app.updateSetting("actionIfOperator", condition.operator)
            if (condition.value) app.updateSetting("actionIfValue", condition.value)
            break

        case "hsm_status":
            if (condition.status) app.updateSetting("actionIfHsmStatus", condition.status)
            break

        case "sun_position":
            if (condition.position) app.updateSetting("actionIfSunPosition", condition.position)
            break

        case "days_of_week":
            if (condition.days) app.updateSetting("actionIfDays", condition.days)
            break

        case "device_was":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.*", value: device.id])
            }
            if (condition.attribute) app.updateSetting("actionIfAttribute", condition.attribute)
            if (condition.value) app.updateSetting("actionIfValue", condition.value)
            if (condition.forSeconds) app.updateSetting("actionIfDuration", condition.forSeconds)
            break

        case "presence":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.presenceSensor", value: device.id])
            }
            if (condition.status) app.updateSetting("actionIfPresenceStatus", condition.status)
            break

        case "lock":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.lock", value: device.id])
            }
            if (condition.status) app.updateSetting("actionIfLockStatus", condition.status)
            break

        case "thermostat_mode":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.mode) app.updateSetting("actionIfThermostatMode", condition.mode)
            break

        case "thermostat_state":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.thermostat", value: device.id])
            }
            if (condition.state) app.updateSetting("actionIfThermostatState", condition.state)
            break

        case "illuminance":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.illuminanceMeasurement", value: device.id])
            }
            if (condition.operator) app.updateSetting("actionIfOperator", condition.operator)
            if (condition.value != null) app.updateSetting("actionIfValue", condition.value)
            break

        case "power":
            if (condition.deviceId) {
                def device = parent.findDevice(condition.deviceId)
                if (device) app.updateSetting("actionIfDevice", [type: "capability.powerMeter", value: device.id])
            }
            if (condition.operator) app.updateSetting("actionIfOperator", condition.operator)
            if (condition.value != null) app.updateSetting("actionIfValue", condition.value)
            break
    }
}

def clearActionSettings() {
    ["actionType", "actionDevice", "actionCommand", "actionParams", "actionLevel", "actionDuration",
     "actionMode", "actionHsmStatus", "actionVariableName", "actionVariableValue",
     "actionDelaySeconds", "actionDelayId", "actionLogMessage", "actionLogLevel",
     // New action type settings
     "actionHue", "actionSaturation", "actionColorTemperature",
     "actionSceneDevice", "actionLocalVariableName", "actionLocalVariableValue",
     "actionNotificationDevice", "actionNotificationMessage",
     "actionCaptureDevices", "actionCaptureStateId", "actionRestoreStateId",
     "actionCancelDelayId", "actionCancelSpecificId", "actionRepeatCount",
     // If-then-else condition settings
     "actionIfConditionType", "actionIfDevice", "actionIfAttribute", "actionIfOperator", "actionIfValue",
     "actionIfModes", "actionIfModeOperator", "actionIfStartTime", "actionIfEndTime",
     "actionIfVariableName", "actionIfHsmStatus", "actionIfSunPosition", "actionIfDays",
     // Additional if_then_else condition settings for all 14 condition types
     "actionIfDuration", "actionIfPresenceStatus", "actionIfLockStatus",
     "actionIfThermostatMode", "actionIfThermostatState"].each {
        app.removeSetting(it)
    }
}

// ==================== BUTTON HANDLER ====================

def appButtonHandler(btn) {
    if (btn == "testRuleBtn") {
        testRule()
    } else if (btn == "deleteTriggerBtn" && state.editingTriggerIndex != null) {
        def list = atomicState.triggers ?: []
        list.remove((int) state.editingTriggerIndex)
        atomicState.triggers = list
        state.updatedAt = now()
        clearTriggerSettings()
        state.remove("editingTriggerIndex")
    } else if (btn == "deleteConditionBtn" && state.editingConditionIndex != null) {
        def list = atomicState.conditions ?: []
        list.remove((int) state.editingConditionIndex)
        atomicState.conditions = list
        state.updatedAt = now()
        clearConditionSettings()
        state.remove("editingConditionIndex")
    } else if (btn == "deleteActionBtn" && state.editingActionIndex != null) {
        def list = atomicState.actions ?: []
        list.remove((int) state.editingActionIndex)
        atomicState.actions = list
        state.updatedAt = now()
        clearActionSettings()
        state.remove("editingActionIndex")
    } else if (btn.startsWith("moveUp_")) {
        def idx = btn.replace("moveUp_", "").toInteger()
        def list = atomicState.actions ?: []
        if (idx > 0 && idx < list.size()) {
            def temp = list[idx]
            list[idx] = list[idx - 1]
            list[idx - 1] = temp
            atomicState.actions = list
            state.updatedAt = now()
        }
    } else if (btn.startsWith("moveDown_")) {
        def idx = btn.replace("moveDown_", "").toInteger()
        def list = atomicState.actions ?: []
        if (idx >= 0 && idx < list.size() - 1) {
            def temp = list[idx]
            list[idx] = list[idx + 1]
            list[idx + 1] = temp
            atomicState.actions = list
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
            // Support both 'start'/'end' (MCP format) and 'startTime'/'endTime' (UI format) for backwards compatibility
            def startTime = condition.start ?: condition.startTime
            def endTime = condition.end ?: condition.endTime
            return "Time is between ${startTime} and ${endTime}"

        case "mode":
            def op = condition.operator == "not_in" ? "is not" : "is"
            return "Mode ${op} ${condition.modes ? condition.modes.join(' or ') : '(none)'}"

        case "variable":
            return "Variable '${condition.variableName}' ${condition.operator} '${condition.value}'"

        case "days_of_week":
            return "Day is ${condition.days ? condition.days.join(', ') : '(none)'}"

        case "sun_position":
            return "Sun is ${condition.position}"

        case "hsm_status":
            return "HSM is ${condition.status}"

        case "presence":
            def presenceDevice = parent.findDevice(condition.deviceId)
            def presenceDeviceName = presenceDevice?.label ?: condition.deviceId
            return "${presenceDeviceName} is ${condition.status}"

        case "lock":
            def lockDevice = parent.findDevice(condition.deviceId)
            def lockDeviceName = lockDevice?.label ?: condition.deviceId
            return "${lockDeviceName} is ${condition.status}"

        case "thermostat_mode":
            def thermostatDevice = parent.findDevice(condition.deviceId)
            def thermostatDeviceName = thermostatDevice?.label ?: condition.deviceId
            return "${thermostatDeviceName} mode is ${condition.mode}"

        case "thermostat_state":
            def thermostatStateDevice = parent.findDevice(condition.deviceId)
            def thermostatStateDeviceName = thermostatStateDevice?.label ?: condition.deviceId
            return "${thermostatStateDeviceName} is ${condition.state}"

        case "illuminance":
            def illuminanceDevice = parent.findDevice(condition.deviceId)
            def illuminanceDeviceName = illuminanceDevice?.label ?: condition.deviceId
            return "${illuminanceDeviceName} illuminance ${condition.operator} ${condition.value} lux"

        case "power":
            def powerDevice = parent.findDevice(condition.deviceId)
            def powerDeviceName = powerDevice?.label ?: condition.deviceId
            return "${powerDeviceName} power ${condition.operator} ${condition.value}W"

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
            return "Capture state of ${captureCount} device(s) (id: ${captureId})"

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
    atomicState.triggers?.each { trigger ->
        try {
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
                        if (location.sunrise) {
                            def offset = trigger.offset ?: 0
                            schedule(location.sunrise.time + (offset * 60000), "handleTimeEvent")
                        } else {
                            log.warn "Cannot schedule sunrise trigger: sunrise time not available for this location"
                        }
                    } else if (trigger.sunset) {
                        if (location.sunset) {
                            def offset = trigger.offset ?: 0
                            schedule(location.sunset.time + (offset * 60000), "handleTimeEvent")
                        } else {
                            log.warn "Cannot schedule sunset trigger: sunset time not available for this location"
                        }
                    }
                    break

                case "mode_change":
                    subscribe(location, "mode", "handleModeEvent")
                    break

                case "hsm_change":
                    subscribe(location, "hsmStatus", "handleHsmEvent")
                    break

                case "periodic":
                    def interval = trigger.interval ?: 1
                    def unit = trigger.unit ?: "minutes"
                    def cronExpr
                    switch (unit) {
                        case "minutes":
                            cronExpr = "0 */${interval} * ? * *"
                            break
                        case "hours":
                            cronExpr = "0 0 */${interval} ? * *"
                            break
                        case "days":
                            cronExpr = "0 0 0 */${interval} * ?"
                            break
                        default:
                            cronExpr = "0 */${interval} * ? * *"
                    }
                    schedule(cronExpr, "handlePeriodicEvent")
                    break
            }
        } catch (Exception e) {
            log.error "Failed to subscribe to trigger (type=${trigger.type}): ${e.message}"
        }
    }
}

def handlePeriodicEvent() {
    if (!settings.ruleEnabled) return
    log.debug "Periodic event triggered"

    def matchingTrigger = atomicState.triggers?.find { t -> t.type == "periodic" }
    if (matchingTrigger) {
        executeRule("periodic")
    }
}

def handleDeviceEvent(evt) {
    if (!settings.ruleEnabled) return
    log.debug "Device event: ${evt.device.label} ${evt.name} = ${evt.value}"

    def matchingTrigger = atomicState.triggers?.find { t ->
        t.type == "device_event" &&
        t.deviceId == evt.device.id.toString() &&
        t.attribute == evt.name &&
        (t.value == null || evaluateComparison(evt.value, t.operator ?: "equals", t.value))
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
        def triggersForDevice = atomicState.triggers?.findAll { t ->
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
                // Note: We don't call unschedule("checkDurationTrigger") here because:
                // 1. It would cancel ALL duration trigger timers, not just this one
                // 2. checkDurationTrigger already handles missing timer data gracefully
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
    def stillMet = trigger.value == null || evaluateComparison(currentValue, trigger.operator ?: "equals", trigger.value)

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

    def matchingTrigger = atomicState.triggers?.find { t ->
        t.type == "button_event" &&
        t.deviceId == evt.device.id.toString() &&
        t.action == evt.name &&
        (t.buttonNumber == null || t.buttonNumber.toString() == evt.value)
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
    def matchingTrigger = atomicState.triggers?.find { t ->
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
    def matchingTrigger = atomicState.triggers?.find { t ->
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
    if (atomicState.conditions && atomicState.conditions.size() > 0) {
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
    def logic = settings.conditionLogic ?: "all"
    def results = atomicState.conditions.collect { evaluateCondition(it) }

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
            def inModes = condition.modes ? condition.modes.contains(currentMode) : false
            return condition.operator == "not_in" ? !inModes : inModes

        case "time_range":
            // Support both 'start'/'end' (MCP format) and 'startTime'/'endTime' (UI format) for backwards compatibility
            def startTime = condition.start ?: condition.startTime
            def endTime = condition.end ?: condition.endTime
            try {
                return timeOfDayIsBetween(toDateTime(startTime), toDateTime(endTime), new Date())
            } catch (Exception e) {
                log.warn "time_range condition failed to parse times (start=${startTime}, end=${endTime}): ${e.message}"
                return false
            }

        case "days_of_week":
            def today = new Date().format("EEEE")
            return condition.days ? condition.days.contains(today) : false

        case "sun_position":
            def sunriseTime = location.sunrise
            def sunsetTime = location.sunset
            if (!sunriseTime || !sunsetTime) {
                log.warn "Cannot evaluate sun_position: sunrise/sunset times not available for this location"
                return false
            }
            def now = new Date()
            def isSunUp = now.after(sunriseTime) && now.before(sunsetTime)
            return condition.position == "up" ? isSunUp : !isSunUp

        case "hsm_status":
            return location.hsmStatus == condition.status

        case "variable":
            // Check local variables first, then global
            def varValue = atomicState.localVariables?."${condition.variableName}"
            if (varValue == null) {
                // Try global variable from parent
                varValue = parent.getVariableValue(condition.variableName)
            }
            return evaluateComparison(varValue, condition.operator, condition.value)

        case "device_was":
            def device = parent.findDevice(condition.deviceId)
            if (!device) return false
            if (condition.forSeconds == null) return false
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

        // Note: "expression" condition type removed - Eval.me() not allowed in Hubitat sandbox

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
    def actions = atomicState.actions ?: []
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
    // Check if this specific delay was cancelled
    if (data.delayId && state.cancelledDelayIds?.containsKey(data.delayId)) {
        log.debug "Delay '${data.delayId}' was cancelled, skipping execution"
        state.cancelledDelayIds.remove(data.delayId) // Clean up
        return
    }
    log.debug "Resuming actions from index ${data.nextIndex} (delayId: ${data.delayId})"
    executeActionsFromIndex(data.nextIndex)
}

def executeAction(action, actionIndex = null) {
    log.debug "Executing action: ${describeAction(action)}"

    switch (action.type) {
        case "device_command":
            def device = parent.findDevice(action.deviceId)
            if (device) {
                try {
                    if (action.parameters) {
                        device."${action.command}"(*action.parameters)
                    } else {
                        device."${action.command}"()
                    }
                } catch (Exception e) {
                    log.error "Error executing command '${action.command}' on device ${device.label}: ${e.message}"
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
            if (!action.condition) {
                log.warn "if_then_else action has no condition, skipping"
                break
            }
            def conditionResult = evaluateCondition(action.condition)
            log.debug "if_then_else condition result: ${conditionResult}"
            if (conditionResult) {
                def thenList = action.thenActions ?: []
                for (int i = 0; i < thenList.size(); i++) {
                    if (!executeAction(thenList[i])) return false
                }
            } else if (action.elseActions) {
                def elseList = action.elseActions ?: []
                for (int i = 0; i < elseList.size(); i++) {
                    if (!executeAction(elseList[i])) return false
                }
            }
            break

        case "cancel_delayed":
            if (action.delayId == "all") {
                // Cancel all pending delayed actions
                unschedule("resumeDelayedActions")
                state.cancelledDelayIds = [:] // Clear cancelled IDs since we cancelled everything
                log.debug "Cancelled all delayed actions"
            } else if (action.delayId) {
                // Mark this specific delay ID as cancelled - will be checked in resumeDelayedActions
                if (!state.cancelledDelayIds) state.cancelledDelayIds = [:]
                state.cancelledDelayIds[action.delayId] = true
                log.debug "Marked delay '${action.delayId}' for cancellation"
            }
            break

        case "set_local_variable":
            def vars = atomicState.localVariables ?: [:]
            vars[action.variableName] = action.value
            atomicState.localVariables = vars
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
                        if (devState.level != null && devState.hue == null) {
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
            def repeatActions = action.actions ?: []
            for (int r = 0; r < repeatCount; r++) {
                for (int i = 0; i < repeatActions.size(); i++) {
                    if (!executeAction(repeatActions[i])) return false
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

    if (atomicState.conditions && atomicState.conditions.size() > 0) {
        atomicState.conditions.each { condition ->
            def result = evaluateCondition(condition)
            results.conditionResults << [
                condition: describeCondition(condition),
                result: result
            ]
        }

        def logic = settings.conditionLogic ?: "all"
        if (logic == "all") {
            results.conditionsMet = results.conditionResults.every { it.result }
        } else {
            results.conditionsMet = results.conditionResults.any { it.result }
        }
        results.wouldExecute = results.conditionsMet
    }

    if (results.wouldExecute) {
        results.actions = atomicState.actions?.collect { describeAction(it) } ?: []
    }

    log.info "Test results: ${results}"
    return results
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
        triggers: atomicState.triggers ?: [],
        conditions: atomicState.conditions ?: [],
        conditionLogic: settings.conditionLogic ?: "all",
        actions: atomicState.actions ?: [],
        localVariables: atomicState.localVariables ?: [:],
        createdAt: state.createdAt,
        updatedAt: state.updatedAt,
        lastTriggered: state.lastTriggered,
        executionCount: state.executionCount ?: 0
    ]
}

def updateRuleFromParent(data) {
    // CRITICAL FIX v0.2.2: Use atomicState for immediate persistence
    // Regular state is only persisted when app execution ends, but atomicState
    // persists immediately. When enabled=true, updateSetting triggers lifecycle
    // methods that may start a new execution context which reads stale state
    // from database. atomicState ensures data is persisted before any lifecycle
    // methods can run.

    log.debug "updateRuleFromParent: Received ${data.triggers?.size() ?: 0} triggers, ${data.conditions?.size() ?: 0} conditions, ${data.actions?.size() ?: 0} actions (enabled=${data.enabled})"

    // Step 1: Store all rule data using atomicState (persists immediately to database)
    if (data.triggers != null) atomicState.triggers = data.triggers
    if (data.conditions != null) atomicState.conditions = data.conditions
    if (data.actions != null) atomicState.actions = data.actions
    if (data.localVariables != null) atomicState.localVariables = data.localVariables
    state.updatedAt = now()

    log.debug "updateRuleFromParent: atomicState now has ${atomicState.triggers?.size() ?: 0} triggers, ${atomicState.actions?.size() ?: 0} actions"

    // Step 2: NOW update settings (these may trigger updated() lifecycle)
    if (data.name != null) {
        app.updateSetting("ruleName", data.name)
        // Update the app label to match (for display in Apps list)
        app.updateLabel(data.name)
    }
    if (data.description != null) app.updateSetting("ruleDescription", data.description)
    if (data.conditionLogic != null) app.updateSetting("conditionLogic", data.conditionLogic)

    // Step 3: Set enabled status last (this is most likely to trigger subscriptions)
    if (data.enabled != null) app.updateSetting("ruleEnabled", data.enabled)

    // Re-subscribe based on current enabled state
    unsubscribe()
    unschedule()
    clearDurationState()  // Clear duration state when rule is updated to prevent orphaned triggers
    if (settings.ruleEnabled) {
        subscribeToTriggers()
    }
}

def enableRule() {
    app.updateSetting("ruleEnabled", true)
    state.updatedAt = now()
    clearDurationState()  // Clear orphaned duration state from previous disable
    unsubscribe()
    subscribeToTriggers()
}

def disableRule() {
    app.updateSetting("ruleEnabled", false)
    state.updatedAt = now()
    clearDurationState()  // Clear duration state to prevent orphaned durationFired flags
    unsubscribe()
    unschedule()
}

def testRuleFromParent() {
    def results = testRule()
    return [
        ruleId: app.id.toString(),
        ruleName: settings.ruleName,
        conditionsMet: results.conditionsMet,
        wouldExecute: results.wouldExecute,
        conditionResults: results.conditionResults,
        actions: results.actions ?: []
    ]
}
