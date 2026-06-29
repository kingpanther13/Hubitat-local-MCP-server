library(name: "McpDebugLoggingLib", namespace: "mcp", author: "kingpanther13", description: "MCP debug-log + bug-report tool implementations (hub_get_debug_logs/hub_delete_debug_logs/hub_set_log_level/hub_report_issue) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

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

    def materialized = logs.collect { entry ->
        def e = [
            timestamp: entry.timestamp,
            time: formatTimestamp(entry.timestamp),
            level: entry.level,
            component: entry.component,
            message: entry.message
        ]
        if (entry.ruleId) e.ruleId = entry.ruleId
        if (entry.ruleName) e.ruleName = entry.ruleName
        if (entry.duration) e.durationMs = entry.duration
        if (entry.stackTrace) e.stackTrace = entry.stackTrace
        if (entry.details) e.details = entry.details
        return e
    }
    def cursor = args?.cursor
    def paged = _paginateList(materialized, cursor, 100, "hub_get_debug_logs")
    def result = [
        entries: paged.page,
        count: paged.page.size(),
        totalStored: state.debugLogs.entries?.size() ?: 0,
        maxEntries: state.debugLogs.config?.maxEntries ?: 100,
        currentLogLevel: getConfiguredLogLevel()
    ]
    if (cursor != null) {
        result.total = materialized.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    return result
}

def toolClearDebugLogs(args) {
    initDebugLogs()
    def count = state.debugLogs.entries?.size() ?: 0
    state.debugLogs.entries = []
    mcpLog("info", "server", "Debug logs cleared (${count} entries removed)")
    return [success: true, clearedCount: count]
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
    def issueType = _bugReportNormalizeIssueType(args.issueType)
    def privacyMode = args.privacyMode?.toString()?.toLowerCase() == "public" ? "public" : "private"
    def includeRawLogs = args.includeRawLogs == null ? (privacyMode == "private") : (args.includeRawLogs == true)
    def windowMs = ((args.logWindowSeconds == null ? 120 : args.logWindowSeconds) as Integer) * 1000L

    initDebugLogs()
    def allEntries = (state.debugLogs.entries ?: []).findAll { it.level == "error" || it.level == "warn" }
    def anchor = _bugReportResolveAnchor(args, allEntries)
    def scopedLogs = _bugReportScopedLogs(args, allEntries, anchor, windowMs)
    def env = _bugReportEnvironmentSummary(args, privacyMode)
    def ruleInfo = _bugReportRuleInfo(args)
    def suggestedTitle = _bugReportSuggestedTitle(args, issueType)
    def submitUrl = _bugReportSubmitUrl(issueType, suggestedTitle)
    def report = _bugReportBuildMarkdown(
        args: args,
        issueType: issueType,
        privacyMode: privacyMode,
        includeRawLogs: includeRawLogs,
        env: env,
        ruleInfo: ruleInfo,
        scopedLogs: scopedLogs
    )

    def result = [
        success: true,
        issueType: issueType,
        privacyMode: privacyMode,
        suggestedTitle: suggestedTitle,
        submitUrl: submitUrl,
        report: report,
        logs: [
            scoped: scopedLogs.scoped,
            relevantCount: scopedLogs.relevant.size(),
            otherRecentLogCount: scopedLogs.scoped && !scopedLogs.includedUnrelated ? scopedLogs.otherCount : 0
        ],
        instructions: "Click submitUrl — the GitHub issue title is pre-filled. In the issue form, type a short description of what you were doing in the 'What happened' field, then paste the 'report' content into the 'Agent report output' field. If you are an LLM, attempt to replace any identifiable hub names, rule names, device names, app IDs, hub variable names, IPs, and filenames with placeholders before sharing this report. Either way, the user MUST review the final report for sensitive details before submitting — public mode is a best-effort assist, not a guarantee."
    ]
    if (scopedLogs.scoped && !scopedLogs.includedUnrelated && scopedLogs.otherCount > 0) {
        result.logs.hint = "Pass includeUnrelatedRecentLogs=true to include the ${scopedLogs.otherCount} omitted recent log entr${scopedLogs.otherCount == 1 ? 'y' : 'ies'}."
    }
    if (state.updateCheck?.updateAvailable) {
        result.updateAvailable = state.updateCheck.latestVersion
    }
    return result
}

private String _bugReportNormalizeIssueType(raw) {
    def s = raw?.toString()?.toLowerCase()?.trim()
    if (s in ["bug", "enhancement", "agent_behavior"]) return s
    if (s in ["feature", "feature_request", "feat"]) return "enhancement"
    if (s in ["agent-behavior", "agent", "tool_description"]) return "agent_behavior"
    return "bug"
}

private Map _bugReportResolveAnchor(args, List entries) {
    if (!entries) return [entry: null, matchedOn: "none"]
    def reversed = entries.reverse()
    if (args.failingTool) {
        def hit = reversed.find { it.details?.tool == args.failingTool }
        if (hit) return [entry: hit, matchedOn: "tool"]
    }
    if (args.ruleId) {
        def hit = reversed.find { it.ruleId?.toString() == args.ruleId?.toString() }
        if (hit) return [entry: hit, matchedOn: "ruleId"]
    }
    if (args.nativeAppId) {
        def hit = reversed.find { it.details?.appId?.toString() == args.nativeAppId?.toString() }
        if (hit) return [entry: hit, matchedOn: "nativeAppId"]
    }
    return [entry: null, matchedOn: "none"]
}

private Map _bugReportScopedLogs(args, List entries, Map anchor, long windowMs) {
    def lastN = entries.takeRight(20)
    def safeTs = { entry ->
        try { return entry?.timestamp as Long } catch (Throwable ignored) { return null }
    }
    if (anchor.entry == null) {
        return [
            relevant: lastN,
            other: [],
            otherCount: 0,
            scoped: false,
            includedUnrelated: true
        ]
    }
    def anchorTs = safeTs(anchor.entry)
    if (anchorTs == null) {
        return [
            relevant: lastN,
            other: [],
            otherCount: 0,
            scoped: false,
            includedUnrelated: true
        ]
    }
    def windowStart = anchorTs - windowMs
    def windowEnd = anchorTs + windowMs
    def matchesContext = { entry ->
        if (args.failingTool && entry.details?.tool == args.failingTool) return true
        if (args.ruleId && entry.ruleId?.toString() == args.ruleId?.toString()) return true
        if (args.nativeAppId && entry.details?.appId?.toString() == args.nativeAppId?.toString()) return true
        return false
    }
    def relevant = []
    def other = []
    entries.each { entry ->
        def ts = safeTs(entry)
        if (ts == null) return
        if (ts >= windowStart && ts <= windowEnd && matchesContext(entry)) {
            relevant << entry
        } else {
            other << entry
        }
    }
    return [
        relevant: relevant.takeRight(20),
        other: other.takeRight(20),
        otherCount: other.size(),
        scoped: true,
        includedUnrelated: (args.includeUnrelatedRecentLogs == true)
    ]
}

private Map _bugReportEnvironmentSummary(args, String privacyMode) {
    def hubName = "Unknown"
    def hubModel = "Unknown"
    def hubFirmware = "Unknown"
    def timeZone = "Unknown"
    try {
        hubName = location.hub?.name?.toString() ?: "Unknown"
        hubModel = location.hub?.hardwareID?.toString() ?: location.hub?.type?.toString() ?: "Unknown"
        hubFirmware = location.hub?.firmwareVersionString?.toString() ?: "Unknown"
        timeZone = location.timeZone?.ID?.toString() ?: "Unknown"
    } catch (Throwable e) {
        mcpLog("warn", "bug-report", "_bugReportEnvironmentSummary: location access threw (${e.message}); env fields may be incomplete")
    }
    return [
        version: currentVersion(),
        hubName: privacyMode == "public" ? "<hub-name>" : hubName,
        hubModel: hubModel,
        hubFirmware: hubFirmware,
        timeZone: privacyMode == "public" ? "<time-zone>" : timeZone,
        logLevel: getConfiguredLogLevel(),
        customMcpRuleCount: getChildApps()?.size() ?: 0,
        nativeRm: _bugReportNativeRmStatus(),
        deviceCount: selectedDevices?.size() ?: 0,
        llmClient: args.llmClient?.toString() ?: "Not provided"
    ]
}

private Map _bugReportNativeRmStatus() {
    def ids = [] as Set
    def v4Error = null
    def v5Error = null
    try {
        def v4 = hubitat.helper.RMUtils.getRuleList() ?: []
        v4.each { r -> if (r?.id != null) ids << r.id.toString() }
    } catch (Throwable e) {
        v4Error = e.toString()
    }
    try {
        def v5 = hubitat.helper.RMUtils.getRuleList("5.0") ?: []
        v5.each { r -> if (r?.id != null) ids << r.id.toString() }
    } catch (Throwable e) {
        v5Error = e.toString()
    }
    def classMissingHint = { String msg ->
        if (!msg) return false
        if (msg.contains("NoClassDefFoundError") || msg.contains("ClassNotFoundException") || msg.contains("unable to resolve class")) return true
        if (msg.contains("Cannot get property") && msg.contains("'helper'")) return true
        if ((msg.contains("MissingMethodException") || msg.contains("No signature of method")) && msg.contains("getRuleList")) return true
        return false
    }
    def bothMissing = v4Error && v5Error && classMissingHint(v4Error) && classMissingHint(v5Error)
    if (bothMissing) {
        return [installed: false, count: 0]
    }
    def hardErrors = []
    if (v4Error && !classMissingHint(v4Error)) hardErrors << "v4=${v4Error}"
    if (v5Error && !classMissingHint(v5Error)) hardErrors << "v5=${v5Error}"
    if (hardErrors) {
        mcpLog("warn", "bug-report", "_bugReportNativeRmStatus: RMUtils errors — ${hardErrors.join('; ')}; count may be inaccurate")
        return [installed: true, count: ids.size(), error: hardErrors.join("; ")]
    }
    return [installed: true, count: ids.size()]
}

private Map _bugReportRuleInfo(args) {
    if (!args.ruleId) return null
    try {
        def childApp = getChildAppById(args.ruleId)
        if (!childApp) return null
        def ruleData = childApp.getRuleData()
        return [
            id: args.ruleId,
            name: ruleData.name,
            enabled: ruleData.enabled,
            triggerCount: ruleData.triggers?.size() ?: 0,
            conditionCount: ruleData.conditions?.size() ?: 0,
            actionCount: ruleData.actions?.size() ?: 0,
            lastTriggered: ruleData.lastTriggered ? formatTimestamp(ruleData.lastTriggered) : "Never",
            executionCount: ruleData.executionCount ?: 0
        ]
    } catch (Throwable e) {
        mcpLog("warn", "bug-report", "_bugReportRuleInfo: getRuleData(${args.ruleId}) failed (${e.message}) — id may not refer to a custom MCP rule")
        return [id: args.ruleId, lookupError: e.message ?: e.toString()]
    }
}

private String _bugReportSuggestedTitle(args, String issueType) {
    def prefix = ["bug": "[bug]", "enhancement": "[feature]", "agent_behavior": "[agent-behavior]"][issueType]
    def toolCtx = args.failingTool?.toString()?.trim()
    def userTitle = args.title?.toString()?.trim() ?: "Issue report"
    def body = toolCtx ? "${toolCtx}: ${userTitle}" : userTitle
    def full = "${prefix} ${body}"
    return full.length() > 140 ? (full.take(137) + "...") : full
}

private String _bugReportSubmitUrl(String issueType, String suggestedTitle) {
    def template = ["bug": "bug_report.yml", "enhancement": "enhancement.yml", "agent_behavior": "agent_behavior.yml"][issueType]
    def base = "https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/new"
    def encodedTitle = URLEncoder.encode(suggestedTitle ?: "", "UTF-8")
    return "${base}?template=${template}&title=${encodedTitle}"
}

private String _bugReportFormatLogEntry(entry) {
    def ts = formatTimestamp(entry.timestamp)
    def lvl = entry.level?.toString()?.toUpperCase()
    def tool = entry.details?.tool ? " [tool=${entry.details.tool}]" : ""
    def ruleRef = entry.ruleId ? " (Rule: ${entry.ruleId})" : ""
    // Tag known-benign RM-internal noise so a maintainer reading this report
    // doesn't chase it as a real failure (see _isBenignRmInternalNoise).
    def benignTag = _isBenignRmInternalNoise(entry.message) ? " [KNOWN-BENIGN RM-internal noise — non-fatal, not an MCP bug]" : ""
    return "[${ts}] ${lvl}${tool}: ${entry.message}${ruleRef}${benignTag}"
}

private boolean _isBenignRmInternalNoise(message) {
    def m = message?.toString()
    if (m == null) return false
    // RM periodic-render NPE: "...Cannot get property 'n' on null object ... (method periodic)"
    return m.contains("method periodic") && m.contains("Cannot get property 'n' on null")
}

private String _bugReportBuildMarkdown(Map params) {
    def args = params.args
    def issueType = params.issueType
    def privacyMode = params.privacyMode
    def includeRawLogs = params.includeRawLogs
    def env = params.env
    def ruleInfo = params.ruleInfo
    def scopedLogs = params.scopedLogs
    def heading = ["bug": "Bug Report", "enhancement": "Feature Request", "agent_behavior": "Agent-Behavior Report"][issueType]
    def expectedActualHeader = issueType == "enhancement" ? "## Request" : (issueType == "agent_behavior" ? "## Agent Behavior" : "## Bug Description")
    def relevantLines = scopedLogs.relevant.collect { _bugReportFormatLogEntry(it) }
    def otherLines = scopedLogs.includedUnrelated ? scopedLogs.other.collect { _bugReportFormatLogEntry(it) } : []
    def failingToolLine = args.failingTool ? "- **Failing tool:** ${args.failingTool}\n" : ""
    def nativeAppLine = args.nativeAppId ? "- **Native RM app id:** ${args.nativeAppId}\n" : ""
    def reproSection = args.stepsToReproduce ? "\n### Steps to Reproduce\n${args.stepsToReproduce}\n" : ""
    def ruleSection
    if (!ruleInfo) {
        ruleSection = ""
    } else if (ruleInfo.lookupError) {
        ruleSection = """
## Related Rule (lookup failed)
- **Rule ID:** ${ruleInfo.id}
- **Lookup error:** ${ruleInfo.lookupError}
- **Note:** This id may not refer to a custom MCP rule (e.g. it's a native RM rule or Notifier — those don't expose getRuleData). If you meant a native rule, pass it as `nativeAppId` instead.
"""
    } else {
        ruleSection = """
## Related Custom MCP Rule
- **Rule ID:** ${ruleInfo.id}
- **Rule Name:** ${ruleInfo.name ?: 'Unknown'}
- **Enabled:** ${ruleInfo.enabled}
- **Triggers:** ${ruleInfo.triggerCount}
- **Conditions:** ${ruleInfo.conditionCount}
- **Actions:** ${ruleInfo.actionCount}
- **Last Triggered:** ${ruleInfo.lastTriggered}
- **Execution Count:** ${ruleInfo.executionCount}
"""
    }
    def logSection
    if (!includeRawLogs) {
        def n = relevantLines.size()
        def stand = n > 0 ?
            "_${n} relevant entr${n == 1 ? 'y' : 'ies'} (raw text omitted in public mode — re-run with privacyMode='private' or pass includeRawLogs=true to see them)._" :
            "_No relevant errors logged (raw text omitted in public mode)._"
        logSection = "## Recent Error/Warning Logs\n${stand}"
    } else {
        def relevantBlock = relevantLines ? "```\n" + relevantLines.join("\n") + "\n```" : "_No relevant errors logged_"
        logSection = "## Recent Error/Warning Logs\n${relevantBlock}"
        if (otherLines) {
            logSection += "\n\n### Other Recent Logs\n```\n" + otherLines.join("\n") + "\n```"
        } else if (scopedLogs.scoped && scopedLogs.otherCount > 0) {
            logSection += "\n\n_${scopedLogs.otherCount} other recent log entr${scopedLogs.otherCount == 1 ? 'y' : 'ies'} omitted — pass includeUnrelatedRecentLogs=true to include them._"
        }
    }

    return """# ${heading}: ${args.title}

**Generated:** ${formatTimestamp(now())}
**MCP Server Version:** ${env.version}
**Issue type:** ${issueType}
**Privacy mode:** ${privacyMode}

## Environment
- **Hub name:** ${env.hubName}
- **Hub model:** ${env.hubModel}
- **Hub firmware:** ${env.hubFirmware}
- **Time zone:** ${env.timeZone}
- **MCP log level:** ${env.logLevel}
- **Rules in legacy custom rule engine:** ${env.customMcpRuleCount}
- ${env.nativeRm.installed == false ? "**Native Rule Machine:** not installed (Rule Machine not detected on this hub)" : "**Native Rule Machine rules:** ${env.nativeRm.count}${env.nativeRm.error ? ' (RMUtils partial failure — count may be inaccurate)' : ''}"}
- **Devices exposed to MCP:** ${env.deviceCount}
- **LLM / client:** ${env.llmClient}
${failingToolLine}${nativeAppLine}
${expectedActualHeader}

### Expected
${args.expected}

### Actual
${args.actual}
${reproSection}${ruleSection}
${logSection}

## Additional Context
_Add any other context, screenshots, or transcripts when filing._
"""
}

def _getAllToolDefinitions_partDebugLogging() {
    return [
        // Debug Logging Tools
        [
            name: "hub_get_debug_logs",
            description: "Read the MCP debug-log system (stored in app state). mode='logs' (default) returns stored entries with level/component/ruleId filters + pagination; mode='status' returns the logging system's status: current log level, per-severity entry counts, and capacity.",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", enum: ["logs", "status"], description: "logs = stored entries (default); status = current log level + counts + capacity.", default: "logs"],
                    limit: [type: "integer", description: "logs mode: max entries to return (default: 50, max: 200)"],
                    level: [type: "string", enum: ["debug", "info", "warn", "error", "all"], description: "logs mode: filter by log level (default: all)"],
                    component: [type: "string", description: "logs mode: filter by component (e.g., 'server', 'rule')"],
                    ruleId: [type: "string", description: "logs mode: filter by specific rule ID"],
                    cursor: [type: "string", description: "logs mode: opt-in pagination cursor. Filters and limit apply first; cursor pages within the filtered result. Pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    entries: [type: "array", description: "logs mode: stored log entries", items: [type: "object", properties: [
                        timestamp: [type: "integer", description: "Epoch millis"],
                        time: [type: "string", description: "Formatted timestamp"],
                        level: [type: "string", description: "Log level"],
                        component: [type: "string", description: "Source component"],
                        message: [type: "string", description: "Log message"],
                        ruleId: [type: "string", description: "Associated rule ID, when present"],
                        ruleName: [type: "string", description: "Associated rule name, when present"]
                    ]]],
                    count: [type: "integer", description: "logs mode: entries on this page"],
                    totalStored: [type: "integer", description: "logs mode: total entries stored"],
                    maxEntries: [type: "integer", description: "Buffer capacity"],
                    currentLogLevel: [type: "string", description: "Current minimum log level"],
                    total: [type: "integer", description: "logs mode: filtered total; present in cursor mode"],
                    nextCursor: [type: "string", description: "logs mode: present when more results remain"],
                    version: [type: "string", description: "status mode: app version"],
                    availableLevels: [type: "array", description: "status mode: valid log levels", items: [type: "string"]],
                    totalEntries: [type: "integer", description: "status mode: total entries stored"],
                    entriesByLevel: [type: "object", description: "status mode: per-severity counts"],
                    oldestEntry: [type: "string", description: "status mode: oldest entry timestamp"],
                    newestEntry: [type: "string", description: "status mode: newest entry timestamp"],
                    updateAvailable: [type: "string", description: "Newer version, when one exists"]
                ]
            ]
        ],
        [
            name: "hub_delete_debug_logs",
            description: "Clear all entries from the MCP debug-log buffer (the in-app state log read by hub_get_debug_logs). Use to reset that buffer before reproducing an issue or to free space. Does NOT touch Hubitat system logs (hub_get_logs) or captured device states (hub_delete_captured_state). Cannot be undone.",
            inputSchema: [type: "object", properties: [:]],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the clear succeeded"],
                    clearedCount: [type: "integer", description: "Number of entries removed"]
                ],
                required: ["success", "clearedCount"]
            ]
        ],
        [
            name: "hub_set_log_level",
            description: "Set the minimum log level threshold. Logs below this level won't be stored. Levels in order: debug < info < warn < error",
            inputSchema: [
                type: "object",
                properties: [
                    level: [type: "string", enum: ["debug", "info", "warn", "error"], description: "Minimum log level to store"]
                ],
                required: ["level"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the level was set"],
                    previousLevel: [type: "string", description: "Log level before the change"],
                    newLevel: [type: "string", description: "Log level after the change"]
                ],
                required: ["success", "previousLevel", "newLevel"]
            ]
        ],
        [
            name: "hub_report_issue",
            description: "File a bug, report a bug, open an issue, open a github issue, request a feature/enhancement, or flag agent-behavior issues against this MCP server. Does NOT submit the issue itself: it gathers context (scoped recent logs, hub/version info) and returns a prefilled GitHub issue link (template + title) plus the report body for the user to open and post.",
            inputSchema: [
                type: "object",
                properties: [
                    title: [type: "string", description: "Short bug/issue narrative. Seeds GitHub title."],
                    expected: [type: "string", description: "What should have happened."],
                    actual: [type: "string", description: "What actually happened."],
                    stepsToReproduce: [type: "string"],
                    issueType: [type: "string", enum: ["bug", "enhancement", "agent_behavior"], description: "Default bug."],
                    failingTool: [type: "string", description: "MCP tool that failed; scopes logs + titles issue."],
                    ruleId: [type: "string", description: "Legacy custom MCP rule-engine rule id; scopes logs to it. A native Rule Machine rule goes in nativeAppId, not here."],
                    nativeAppId: [type: "string", description: "Native Rule Machine app id; scopes logs to that app. A legacy custom MCP rule goes in ruleId."],
                    llmClient: [type: "string", description: "Claude / ChatGPT / Gemini / etc."],
                    privacyMode: [type: "string", enum: ["private", "public"], description: "'public' placeholders hub name, suppresses raw logs."],
                    includeRawLogs: [type: "boolean", description: "Default: true private, false public."],
                    includeUnrelatedRecentLogs: [type: "boolean", description: "When scoped (failingTool/ruleId/nativeAppId set), also attach recent logs outside that scope; default false, no-op when unscoped."],
                    logWindowSeconds: [type: "integer", description: "Default 120."]
                ],
                required: ["title", "expected", "actual"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the report was generated"],
                    issueType: [type: "string", description: "Normalized issue type (bug/enhancement/agent_behavior)"],
                    privacyMode: [type: "string", description: "Resolved privacy mode (private/public)"],
                    suggestedTitle: [type: "string", description: "Pre-filled GitHub issue title"],
                    submitUrl: [type: "string", description: "Prefilled GitHub issue link to open"],
                    report: [type: "string", description: "Markdown issue report body to paste into the form"],
                    logs: [type: "object", description: "Scoped log summary", properties: [
                        scoped: [type: "boolean", description: "Whether logs were narrowed to a context anchor"],
                        relevantCount: [type: "integer", description: "Count of context-relevant log entries"],
                        otherRecentLogCount: [type: "integer", description: "Count of omitted unrelated recent entries"],
                        hint: [type: "string", description: "Guidance to include omitted entries, when applicable"]
                    ]],
                    instructions: [type: "string", description: "How to submit the report"],
                    updateAvailable: [type: "string", description: "Latest available version, present when an update exists"]
                ],
                required: ["success", "submitUrl", "report"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partDebugLogging() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Diagnostics + logs (read)
        "hub_get_debug_logs", "hub_report_issue"
    ]
}

def _idempotentWriteToolNames_partDebugLogging() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // MCP self-admin + logging
        "hub_set_log_level", "hub_delete_debug_logs"
    ]
}

def _toolDisplayMeta_partDebugLogging() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_report_issue: [title: "Generate Diagnostic Report", summary: "Generate a comprehensive diagnostic report for bug reports."],
        hub_get_debug_logs: [title: "Get MCP Debug Logs", summary: "MCP debug log entries, or logging-system status."],
        hub_delete_debug_logs: [title: "Clear MCP Debug Logs", summary: "Clear all MCP debug log entries."],
        hub_set_log_level: [title: "Set MCP Log Level", summary: "Set the MCP log level (debug, info, warn, error)."]
    ]
}
