library(name: "McpCustomRulesLib", namespace: "mcp", author: "kingpanther13", description: "Legacy custom-rule engine parent-side tool implementations (get/create/update/delete/test/export/import/clone custom rules) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListRules(args = null) {
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
            executionCount: ruleData.executionCount ?: 0,
            source: "mcp_custom_engine"
        ]
    }?.findAll { it != null } ?: []

    def cursor = args?.cursor
    def paged = _paginateList(rules, cursor, 50, "hub_get_custom_rule")
    def result = [rules: paged.page, count: paged.page.size()]
    if (cursor != null) {
        result.total = rules.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    return result
}

def toolGetRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "read")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    def ruleData = childApp.getRuleData()
    // Inject source marker so callers can distinguish MCP-managed rules from
    // native RM rules returned by hub_list_rules / hub_manage_native_rules_and_apps.
    if (ruleData instanceof Map) {
        ruleData = ruleData + [source: "mcp_custom_engine"]
    }
    return ruleData
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

def toolUpdateRule(ruleId, args, String customEngineMode = "full") {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "write")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    // Read-only mode gate: only the 'enabled' field may be updated when the
    // Custom Rule Engine toggle is OFF (engine in read-only mode). Any
    // structural field (triggers, conditions, actions, name, etc.) requires
    // the engine to be fully enabled.
    if (customEngineMode == "readonly") {
        // Fields in args besides 'enabled' (ruleId is passed as a separate param, not in args)
        def structuralKeys = (args?.keySet() ?: []).findAll { it != "enabled" && it != "ruleId" }
        if (!structuralKeys.isEmpty()) {
            throw new IllegalArgumentException("In read-only mode (Custom Rule Engine toggle OFF), only the 'enabled' field can be updated. Structural fields provided: ${structuralKeys.sort().join(', ')}. To modify rule structure (triggers/conditions/actions), turn ON the Custom Rule Engine toggle in MCP server settings. The custom rule engine is legacy -- for new rule structure work, use hub_manage_rule_machine hub_set_rule instead.")
        }
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
        def redirect = findRuleAppRedirect(args.ruleId, "delete")
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

def toolTestRule(ruleId) {
    def childApp = getChildAppById(ruleId)
    if (!childApp) {
        def redirect = findRuleAppRedirect(ruleId, "test")
        def msg = "Rule not found: ${ruleId}"
        if (redirect) msg += ". ${redirect}"
        throw new IllegalArgumentException(msg)
    }

    return childApp.testRuleFromParent()
}

private String findRuleAppRedirect(ruleId, String verb) {
    // Soft gate: only enrich the error when the Read master is on. Prevents
    // leaking that an app exists at this id (or its type) when the operator has
    // intentionally restricted read access.
    if (settings.enableRead == false) return null

    // Type-string fragments that identify rule-like built-in apps. Matched case-insensitively
    // as substrings of the app's type field. "visual rule" (singular) covers both
    // "Visual Rule Builder" (child instances) and "Visual Rules Builder" (parent app).
    def ruleTypeFragments = [
        "rule machine", "rule-5", "rule-4", "room light", "basic rules", "visual rule"
    ]

    // Subset of ruleTypeFragments that are RM-specific. hub_call_rule (RMUtils) only works for
    // Rule Machine rules -- pointing Room Lighting / Basic Rules / VRB ids there would fail.
    def rmTypeFragments = ["rule machine", "rule-5", "rule-4"]

    def idStr = ruleId?.toString()?.trim()
    if (!idStr || !idStr.isInteger()) return null

    try {
        def responseText = hubInternalGet("/hub2/appsList")
        if (!responseText) return null

        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof Map)) return null

        // Walk the apps[] tree with an early-exit iterative DFS to find the app instance
        // with matching id. Iterative (explicit stack) avoids StackOverflowError on pathological
        // hub responses with deeply-nested children (which would bypass the outer Exception catch).
        def foundData = null
        def apps = parsed.apps
        if (!(apps instanceof List)) return null
        def stack = []
        stack.addAll(apps)
        while (!stack.isEmpty()) {
            def node = stack.remove(stack.size() - 1)
            if (!(node instanceof Map)) continue
            def d = node.data
            if (d instanceof Map && d.id?.toString() == idStr) {
                foundData = d
                break
            }
            if (node.children instanceof List) {
                stack.addAll(node.children)
            }
        }
        if (!foundData) return null

        def appTypeName = foundData.type?.toString() ?: "built-in app"
        def appTypeNameLower = appTypeName.toLowerCase()

        boolean isRuleLike = ruleTypeFragments.any { frag -> appTypeNameLower.contains(frag) }
        if (!isRuleLike) return null

        // Is this app a Rule Machine instance? Gates the hub_call_rule pointer in the test-verb hint.
        boolean isRmRule = rmTypeFragments.any { frag -> appTypeNameLower.contains(frag) }

        // Build verb-appropriate redirect hint.
        if (verb == "read") {
            return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                "Use `hub_read_apps_code -> hub_get_app_config(appId=${idStr})` to read its configuration. " +
                "`hub_get_custom_rule`, `hub_export_custom_rule`, and `hub_clone_custom_rule` only handle MCP's own rule engine, not Hubitat built-in apps."
        } else if (verb == "delete") {
            return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                "Use `hub_read_apps_code -> hub_get_app_config(appId=${idStr})` for read-only inspection. " +
                "`hub_delete_custom_rule` only handles MCP's own rule engine, not Hubitat built-in apps. " +
                "Use `hub_manage_native_rules_and_apps -> hub_delete_native_app(appId=${idStr}, confirm=true)` to delete it programmatically " +
                "(requires the Write master)."
        } else if (verb == "test") {
            // hub_call_rule routes through RMUtils and is RM-only. Point non-RM rule-likes at hub_get_app_config instead.
            if (isRmRule) {
                return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                    "Use `hub_read_apps_code -> hub_get_app_config(appId=${idStr})` for read-only inspection. " +
                    "`hub_test_custom_rule` only handles MCP's own rule engine, not Hubitat built-in apps. " +
                    "Use `hub_manage_native_rules_and_apps -> hub_call_rule(ruleId=${idStr})` to trigger it " +
                    "(requires the Read master)."
            } else {
                return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                    "Use `hub_read_apps_code -> hub_get_app_config(appId=${idStr})` for read-only inspection. " +
                    "`hub_test_custom_rule` only handles MCP's own rule engine, not Hubitat built-in apps."
            }
        } else {
            // Catch-all for "write" verb (hub_update_custom_rule) and any future write verbs.
            if (verb != "write") {
                mcpLog("debug", "rules", "findRuleAppRedirect: unrecognized verb '${verb}', defaulting to write-verb message")
            }
            return "Rule ${idStr} is a Hubitat built-in ${appTypeName} app. " +
                "Use `hub_read_apps_code -> hub_get_app_config(appId=${idStr})` for read-only inspection. " +
                "`hub_update_custom_rule` only handles MCP's own rule engine, not Hubitat built-in apps. " +
                "Use `hub_manage_rule_machine -> hub_set_rule(appId=${idStr})` to modify it programmatically " +
                "(requires the Write master)."
        }
    } catch (Exception e) {
        mcpLog("error", "rules", "findRuleAppRedirect lookup failed for id ${idStr}: ${e.message ?: e.toString()}")
        return null
    }
}

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

    // hub_export_custom_rule ALWAYS persists the export JSON to the hub File Manager --
    // that write side-effect is why it is classified a write tool. saveAs sets the filename.
    def exportFileName = (args.saveAs ?: "mcp-rule-export-${args.ruleId}-${ruleData.name}").toString()
        .replaceAll(/[^A-Za-z0-9._-]/, "_")
    if (!exportFileName.toLowerCase().endsWith(".json")) exportFileName += ".json"
    def jsonContent = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(exportData))
    uploadHubFile(exportFileName, jsonContent.getBytes("UTF-8"))
    exportData.savedToFile = exportFileName
    mcpLog("info", "server", "Saved rule export to File Manager: ${exportFileName}", args.ruleId)

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
        source: "mcp_custom_engine",
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

def _getAllToolDefinitions_partCustomRules() {
    return [
        // Rule Management
        [
            name: "hub_get_custom_rule",
            description: "Read MCP custom-engine automation rules. Omit ruleId to LIST all rules (summaries; supports cursor pagination). Provide ruleId for one rule's full detail. Add detailed=true (requires ruleId) for comprehensive diagnostics (config + execution history + recent logs + errors). NOTE: when the Custom Rule Engine toggle is OFF, this operates read-only -- you can list/inspect existing custom rules, but create/modify/delete are hidden. The custom MCP rule engine is legacy; for new rule work prefer native Rule Machine via hub_manage_native_rules_and_apps.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID. Omit to list all rules; provide for one rule's detail."],
                    detailed: [type: "boolean", description: "Requires ruleId. Returns comprehensive diagnostics (execution history, recent logs, errors) instead of plain rule data. Rejected if set without a ruleId.", default: false],
                    cursor: [type: "string", description: "List mode only (ruleId omitted): opt-in pagination cursor. Pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    rules: [type: "array", description: "List mode (ruleId omitted): rule summaries", items: [type: "object", properties: [
                        id: [type: "string", description: "Rule ID"],
                        name: [type: "string", description: "Rule name"],
                        description: [type: "string", description: "Rule description"],
                        enabled: [type: "boolean", description: "Whether the rule is enabled"],
                        triggerCount: [type: "integer", description: "Number of triggers"],
                        conditionCount: [type: "integer", description: "Number of conditions"],
                        actionCount: [type: "integer", description: "Number of actions"],
                        lastTriggered: [description: "Last trigger timestamp"],
                        executionCount: [type: "integer", description: "Times executed"],
                        source: [type: "string", description: "Always 'mcp_custom_engine'"]
                    ]]],
                    count: [type: "integer", description: "List mode: rules in this page"],
                    total: [type: "integer", description: "List mode (paginated): total rule count"],
                    nextCursor: [type: "string", description: "List mode: present when more results remain"],
                    id: [type: "string", description: "Single-rule mode: rule ID"],
                    name: [type: "string", description: "Single-rule mode: rule name"],
                    description: [type: "string", description: "Single-rule mode: rule description"],
                    enabled: [type: "boolean", description: "Single-rule mode: enabled state"],
                    testRule: [type: "boolean", description: "Single-rule mode: skips backup on deletion"],
                    triggers: [type: "array", description: "Single-rule mode: trigger definitions"],
                    conditions: [type: "array", description: "Single-rule mode: condition definitions"],
                    conditionLogic: [type: "string", description: "Single-rule mode: 'all' or 'any'"],
                    actions: [type: "array", description: "Single-rule mode: action definitions"],
                    localVariables: [type: "object", description: "Single-rule mode: local variables"],
                    createdAt: [description: "Single-rule mode: creation timestamp"],
                    updatedAt: [description: "Single-rule mode: last update timestamp"],
                    lastTriggered: [description: "Single-rule mode: last trigger timestamp"],
                    executionCount: [type: "integer", description: "Single-rule mode: times executed"],
                    source: [type: "string", description: "Single/detailed mode: always 'mcp_custom_engine'"],
                    rule: [type: "object", description: "Detailed mode (detailed=true): rule identity", properties: [
                        id: [type: "string", description: "Rule ID"],
                        name: [type: "string", description: "Rule name"],
                        description: [type: "string", description: "Rule description"],
                        enabled: [type: "boolean", description: "Enabled state"],
                        createdAt: [type: "string", description: "Creation timestamp"],
                        updatedAt: [type: "string", description: "Last update timestamp"]
                    ]],
                    execution: [type: "object", description: "Detailed mode: execution stats", properties: [
                        count: [type: "integer", description: "Times executed"],
                        lastTriggered: [type: "string", description: "Last trigger timestamp"]
                    ]],
                    structure: [type: "object", description: "Detailed mode: trigger/condition/action structure", properties: [
                        triggerCount: [type: "integer", description: "Number of triggers"],
                        conditionCount: [type: "integer", description: "Number of conditions"],
                        actionCount: [type: "integer", description: "Number of actions"],
                        triggers: [type: "array", description: "Trigger definitions"],
                        conditions: [type: "array", description: "Condition definitions"],
                        actions: [type: "array", description: "Action definitions"],
                        conditionLogic: [type: "string", description: "'all' or 'any'"]
                    ]],
                    state: [type: "object", description: "Detailed mode: rule state (localVariables)"],
                    logs: [type: "object", description: "Detailed mode: recent logs and errors", properties: [
                        recentCount: [type: "integer", description: "Recent log entries returned"],
                        errorCount: [type: "integer", description: "Total error log entries"],
                        recent: [type: "array", description: "Recent log entries"],
                        errors: [type: "array", description: "Recent error log entries"]
                    ]]
                ]
            ]
        ],
        [
            name: "hub_create_custom_rule",
            description: """*** LEGACY (MCP sandbox engine): for "create a Rule Machine rule" / "Hubitat rule" / anything the user wants visible in Hubitat's RM UI, use hub_manage_rule_machine hub_set_rule instead. ***

Create a new automation rule (MCP sandbox engine). Call `hub_get_tool_guide(section='rules')` for the trigger/condition/action type lists, per-type fields, structure, syntax, and examples.

Verify rule after creation.""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Rule name, e.g. \"Porch light at sunset\""],
                    description: [type: "string", description: "Optional human-readable rule description"],
                    enabled: [type: "boolean", description: "Enable rule immediately on creation", default: true],
                    testRule: [type: "boolean", description: "Mark as test rule - will NOT be backed up on deletion. Use for temporary/experimental rules.", default: false],
                    triggers: [type: "array", items: [type: "object", properties: [type: [type: "string", enum: ["device_event", "button_event", "time", "sunrise", "sunset", "sun", "periodic", "mode_change", "hsm_change"]]]], description: "Trigger objects (at least one required), each a {type, ...} object — the `type` enum lists the kinds (sunrise/sunset/sun are shortcuts for a time trigger)."],
                    conditions: [type: "array", items: [type: "object", properties: [type: [type: "string", enum: ["device_state", "device_was", "time_range", "mode", "variable", "days_of_week", "sun_position", "hsm_status", "presence", "lock", "thermostat_mode", "thermostat_state", "illuminance", "power"]]]], description: "Optional condition objects gating the actions, each {type, ...}."],
                    conditionLogic: [type: "string", enum: ["all", "any"], description: "How to combine multiple conditions: 'all' = AND, 'any' = OR.", default: "all"],
                    actions: [type: "array", items: [type: "object", properties: [type: [type: "string", enum: ["device_command", "toggle_device", "activate_scene", "set_variable", "set_local_variable", "set_mode", "set_hsm", "delay", "if_then_else", "cancel_delayed", "repeat", "stop", "log", "set_level", "set_color", "set_color_temperature", "lock", "unlock", "capture_state", "restore_state", "send_notification", "set_thermostat", "http_request", "speak", "comment", "set_valve", "set_fan_speed", "set_shade", "variable_math"]]]], description: "Action objects to run when triggered (at least one required), each {type, ...}."]
                ],
                required: ["name", "triggers", "actions"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the rule was created"],
                    ruleId: [type: "string", description: "ID of the new rule"],
                    message: [type: "string", description: "Human-readable result"],
                    diagnostics: [type: "object", description: "Persistence verification", properties: [
                        storedTriggers: [type: "integer", description: "Triggers persisted"],
                        storedActions: [type: "integer", description: "Actions persisted"],
                        durationMs: [type: "integer", description: "Creation duration in ms"]
                    ]]
                ],
                required: ["success", "ruleId"]
            ]
        ],
        [
            name: "hub_update_custom_rule",
            description: "Update an existing MCP custom-engine rule in place; only the fields you supply are changed. Use enabled=true/false to enable or disable.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "ID of the rule to update (from hub_get_custom_rule)"],
                    name: [type: "string", description: "New rule name"],
                    description: [type: "string", description: "New rule description"],
                    enabled: [type: "boolean", description: "Enable (true) or disable (false) the rule"],
                    testRule: [type: "boolean", description: "Mark as test rule - will NOT be backed up on deletion"],
                    triggers: [type: "array", description: "Replacement trigger objects (overwrites all triggers); see the rules guide for structure"],
                    conditions: [type: "array", description: "Replacement condition objects (overwrites all conditions); see the rules guide for structure"],
                    conditionLogic: [type: "string", enum: ["all", "any"], description: "How to combine conditions: 'all' = AND, 'any' = OR"],
                    actions: [type: "array", description: "Replacement action objects (overwrites all actions); see the rules guide for structure"]
                ],
                required: ["ruleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update succeeded"],
                    ruleId: [type: "string", description: "ID of the updated rule"],
                    message: [type: "string", description: "Human-readable result"]
                ],
                required: ["success", "ruleId"]
            ]
        ],
        [
            name: "hub_delete_custom_rule",
            description: "DESTRUCTIVE: Permanently delete a rule. Automatically saves a backup to File Manager (mcp_rule_backup_*.json) before deletion. Rules marked as testRule=true skip backup automatically.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID"],
                    confirm: [type: "boolean", description: "REQUIRED: Set to true to confirm deletion."],
                    skipBackupCheck: [type: "boolean", description: "Force skip backup even for non-test rules. Rarely needed since testRule flag handles this. Default: false."]
                ],
                required: ["ruleId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the rule was deleted"],
                    message: [type: "string", description: "Human-readable result"],
                    backupFile: [type: "string", description: "File Manager backup filename (present when a backup was written)"]
                ],
                required: ["success"]
            ]
        ],
        // enable_rule and disable_rule merged into hub_update_custom_rule (use enabled=true/false)
        [
            name: "hub_test_custom_rule",
            description: "Dry-run an MCP custom-engine rule: evaluate its conditions against current device/hub state and report whether it would fire, WITHOUT executing any actions (no devices change, no side effects).",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "ID of the custom rule to dry-run (from hub_get_custom_rule)"]
                ],
                required: ["ruleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID tested"],
                    ruleName: [type: "string", description: "Rule name"],
                    conditionsMet: [type: "boolean", description: "Whether all conditions evaluated true"],
                    wouldExecute: [type: "boolean", description: "Whether the rule would fire its actions"],
                    conditionResults: [type: "array", description: "Per-condition evaluation results"],
                    actions: [type: "array", description: "Actions that would run (none executed)"]
                ],
                required: ["ruleId", "wouldExecute"]
            ]
        ],
        // Rule Export/Import/Clone Tools
        [
            name: "hub_export_custom_rule",
            description: "Export a custom rule to JSON and save it to the hub File Manager for backup or sharing.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to export"],
                    saveAs: [type: "string", description: "File Manager filename to write the export JSON to (\".json\" appended if missing). Omit to use a generated name based on the rule."]
                ],
                required: ["ruleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    exportVersion: [type: "string", description: "Export format version"],
                    exportedAt: [type: "string", description: "Export timestamp"],
                    serverVersion: [type: "string", description: "MCP server version at export"],
                    rule: [type: "object", description: "Exported rule definition", properties: [
                        name: [type: "string", description: "Rule name"],
                        description: [type: "string", description: "Rule description"],
                        enabled: [type: "boolean", description: "Enabled state"],
                        conditionLogic: [type: "string", description: "'all' or 'any'"],
                        triggers: [type: "array", description: "Trigger definitions"],
                        conditions: [type: "array", description: "Condition definitions"],
                        actions: [type: "array", description: "Action definitions"],
                        localVariables: [type: "object", description: "Local variables"]
                    ]],
                    deviceManifest: [type: "array", description: "Referenced devices", items: [type: "object", properties: [
                        deviceId: [type: "string", description: "Device ID"],
                        usedIn: [type: "array", description: "Sections referencing the device", items: [type: "string"]],
                        label: [type: "string", description: "Device label or fallback"],
                        capabilities: [type: "array", description: "Device capabilities", items: [type: "string"]]
                    ]]],
                    savedToFile: [type: "string", description: "File Manager filename the export was written to"]
                ],
                required: ["exportVersion", "rule", "deviceManifest", "savedToFile"]
            ]
        ],
        [
            name: "hub_import_custom_rule",
            description: """Import a custom rule from exported JSON (produced by hub_export_custom_rule), creating a NEW rule with a fresh ruleId (it does not overwrite an existing rule).""",
            inputSchema: [
                type: "object",
                properties: [
                    exportData: [type: "object", description: "The full export JSON object from hub_export_custom_rule"],
                    name: [type: "string", description: "Override the rule name (optional)"],
                    deviceMapping: [type: "object", description: "Map old device IDs to new ones: {\"old_id\": \"new_id\"} (optional)"]
                ],
                required: ["exportData"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the rule was imported"],
                    ruleId: [type: "string", description: "ID of the newly created rule"],
                    message: [type: "string", description: "Human-readable result"],
                    diagnostics: [type: "object", description: "Persistence verification", properties: [
                        storedTriggers: [type: "integer", description: "Triggers persisted"],
                        storedActions: [type: "integer", description: "Actions persisted"],
                        durationMs: [type: "integer", description: "Creation duration in ms"]
                    ]],
                    imported: [type: "boolean", description: "Always true on success"],
                    sourceExportVersion: [type: "string", description: "Export format version of the source"],
                    devicesMapped: [type: "integer", description: "Device IDs remapped (present when deviceMapping supplied)"]
                ],
                required: ["success", "ruleId"]
            ]
        ],
        [
            name: "hub_clone_custom_rule",
            description: "Duplicate an existing MCP custom-engine rule into a new, independent rule with its own ruleId (same triggers/conditions/actions and device references as the source).",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "string", description: "Rule ID to clone"],
                    name: [type: "string", description: "Name for the clone (defaults to 'Copy of <original>')"]
                ],
                required: ["ruleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the clone was created"],
                    ruleId: [type: "string", description: "ID of the new cloned rule"],
                    message: [type: "string", description: "Human-readable result"],
                    clonedFrom: [type: "string", description: "Source rule ID"],
                    diagnostics: [type: "object", description: "Persistence verification", properties: [
                        storedTriggers: [type: "integer", description: "Triggers persisted"],
                        storedActions: [type: "integer", description: "Actions persisted"],
                        durationMs: [type: "integer", description: "Creation duration in ms"]
                    ]],
                    imported: [type: "boolean", description: "Always true on success (clone routes through import)"],
                    sourceExportVersion: [type: "string", description: "Export format version of the source"]
                ],
                required: ["success", "ruleId", "clonedFrom"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partCustomRules() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Custom rule reads (legacy MCP engine) -- test_rule is dry-run, no
        // actions fire. (hub_export_custom_rule now persists to File Manager -> write.)
        "hub_get_custom_rule", "hub_test_custom_rule"
    ]
}

def _idempotentWriteToolNames_partCustomRules() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Custom rules (legacy engine)
        "hub_update_custom_rule", "hub_delete_custom_rule"
    ]
}

def _toolDisplayMeta_partCustomRules() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Custom rules (legacy MCP engine)
        hub_get_custom_rule: [title: "Get Custom Rule", summary: "List custom-engine rules or get one rule's details and diagnostics."],
        hub_create_custom_rule: [title: "Create Custom Rule", summary: "Create a new custom-engine automation rule."],
        hub_update_custom_rule: [title: "Update Custom Rule", summary: "Update a custom-engine rule's triggers, conditions, actions, or enabled state."],
        hub_delete_custom_rule: [title: "Delete Custom Rule", summary: "Permanently delete a custom-engine rule (auto-backs up first)."],
        hub_test_custom_rule: [title: "Test Custom Rule", summary: "Dry-run a custom-engine rule without executing its actions."],
        hub_export_custom_rule: [title: "Export Custom Rule", summary: "Export a custom-engine rule to JSON in the hub File Manager."],
        hub_import_custom_rule: [title: "Import Custom Rule", summary: "Import a custom-engine rule from exported JSON as a new rule."],
        hub_clone_custom_rule: [title: "Clone Custom Rule", summary: "Clone an existing custom-engine rule (the copy starts disabled)."]
    ]
}
