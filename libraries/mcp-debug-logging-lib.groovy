library(name: "McpDebugLoggingLib", namespace: "mcp", author: "kingpanther13", description: "MCP debug-log + bug-report tool implementations (hub_get_logs MCP modes/hub_delete_debug_logs/hub_set_log_level/hub_report_issue) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolGetDebugLogs(args) {
    initDebugLogs()

    def limit = args.limit != null ? Math.min(args.limit as Integer, 200) : 50
    def level = args.level ?: "all"
    def component = args.component
    def ruleId = args.ruleId

    def history = getDebugLogReadResult(args)
    if (history.status == "in_progress") return history + [tool: "hub_get_logs"]
    if (history.error) throw new IllegalStateException(history.error)
    def stored = history.entries
    def logs = stored

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
    def paged = _paginateList(materialized, cursor, 100, "hub_get_logs")
    def result = [
        entries: paged.page,
        count: paged.page.size(),
        totalStored: stored.size(),
        maxEntries: 100,
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
    def cleared = clearDebugLogEntries(args)
    if (cleared.status == "in_progress") return cleared + [tool: "hub_delete_debug_logs"]
    def detail = cleared.countIncomplete ? "previous entry count unavailable" : "${cleared.clearedCount} entries removed"
    mcpLog("info", "server", "Debug logs cleared (${detail})")
    return [success: true] + cleared
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
    setDebugLogLevel(level)
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
    def history = getDebugLogReadResult(args)
    if (history.status == "in_progress") return history + [tool: "hub_get_logs"]
    if (history.error) throw new IllegalStateException(history.error)
    def entries = history.entries

    def result = [
        version: currentVersion(),
        currentLogLevel: getConfiguredLogLevel(),
        availableLevels: getLogLevels(),
        totalEntries: entries.size(),
        maxEntries: 100,
        storage: "hub_native_logs",
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
    def history = getDebugLogReadResult(args)
    if (history.status == "in_progress") return history + [tool: "hub_report_issue"]
    def allEntries = (history.entries ?: []).findAll { it.level == "error" || it.level == "warn" }
    def anchor = _bugReportResolveAnchor(args, allEntries)
    def scopedLogs = _bugReportScopedLogs(args, allEntries, anchor, windowMs)
    def identity = mcpClientIdentity()
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
        scopedLogs: scopedLogs,
        logReadError: history.error
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
        missingContext: _bugReportMissingContext(args, issueType, identity?.lastSeen, identity?.recent),
        preflight: _bugReportPreflight(issueType),
        instructions: "1. Resolve every preflight step and missingContext item first -- a report without them usually gets sent back with questions. Never guess llmClient or llmModel -- ask the user. 2. Open submitUrl; the GitHub issue title is pre-filled. 3. Type a short description of what you were doing in the 'What happened' field. 4. Paste the 'report' content into the 'Agent report output' field. Privacy: if you are an LLM, attempt to replace any identifiable hub names, rule names, device names, app IDs, hub variable names, IPs, and filenames with placeholders before sharing this report. Either way, the user MUST review the final report for sensitive details before submitting -- public mode is a best-effort assist, not a guarantee."
    ]
    if (history.error) {
        result.logs.error = history.error
        result.logs.retryable = history.retryable
        result.logs.relevantCount = null
        result.logs.otherRecentLogCount = null
    }
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
    def identity = mcpClientIdentity()
    def client = identity?.lastSeen
    return [
        version: currentVersion(),
        hubName: privacyMode == "public" ? "<hub-name>" : hubName,
        hubModel: hubModel,
        hubFirmware: hubFirmware,
        timeZone: privacyMode == "public" ? "<time-zone>" : timeZone,
        logLevel: getConfiguredLogLevel(),
        // Tool-surface shape the client sees on tools/list: gateway (hub_manage_*/hub_read_*
        // consolidation, the default) vs flat (every tool advertised individually). A client's
        // failure mode can differ by mode, so a bug report must carry it.
        toolMode: (settings.useGateways == false) ? "flat" : "gateway",
        customMcpRuleCount: getChildApps()?.size() ?: 0,
        nativeRm: _bugReportNativeRmStatus(),
        deviceCount: selectedDevices?.size() ?: 0,
        connection: _isCloudRequest() ? "cloud" : "local",
        clientSelfReport: _bugReportClientLine(client, identity?.recent),
        protocolVersion: client?.protocolVersion ? "${client.protocolVersion} (${client.era ?: 'unknown'})" : "not reported by client",
        llmClient: args.llmClient?.toString()?.trim() ?: "Not provided",
        llmModel: args.llmModel?.toString()?.trim() ?: "Not provided",
        settingsLines: _bugReportSettingsLines(privacyMode)
    ]
}

// The client's own self-report (initialize, or the per-request _meta), not the agent-supplied
// llmClient: the two disagree often enough (a wrapper reports its transport, the user names the
// host app) that a maintainer needs both.
private String _bugReportClientLine(Map client, List recent) {
    // A request that declared nothing still narrows the field: name whoever HAS spoken to this
    // install, so a maintainer sees the candidates instead of a dead end.
    if (!client?.name) {
        def named = (recent ?: []).findAll { it instanceof Map && it["name"] }
        if (!named) return "not reported on this request"
        def listed = named.take(5).collect { entry ->
            def item = entry["name"].toString()
            if (entry["version"]) item = "${item} ${entry['version']}".toString()
            item = "${item} [${entry['era'] ?: 'unknown'}, ${entry['source'] ?: 'unknown'}]".toString()
            return _bugReportClientIsWrapper(entry) ? "${item} (transport wrapper)".toString() : item
        }
        def line = "not reported on this request (recent clients: ${listed.join(', ')})".toString()
        if (_bugReportWrapperFrom(client, recent) != null) {
            line = "${line} -- host app unknown behind a transport wrapper".toString()
        }
        return line
    }
    def line = client.name.toString()
    if (client.version) line = "${line} ${client.version}"
    if (client.title) line = "${line} (${client.title})"
    if (_bugReportClientIsWrapper(client)) line = "${line} (transport wrapper -- host app unknown)"
    return line
}

// A transport wrapper self-reports ITS OWN name on initialize, never the host app behind it,
// so a match here means the recorded identity cannot name the real client.
private boolean _bugReportClientIsWrapper(Map client) {
    def name = client?.name?.toString()?.toLowerCase()
    if (!name) return false
    // "mcp 0.1.0" is the Python MCP SDK's default clientInfo: what fastmcp-remote / mcp-proxy
    // style bridges send. A client named "mcp" with a real version is not a bridge.
    if (name == "mcp") return client.version?.toString() == "0.1.0"
    return ["mcp-remote", "mcp-proxy", "fastmcp-remote", "supergateway"].any { name.contains(it) }
}

// The current request can be nameless while a bridge named itself on an earlier one, so a wrapper
// anywhere in the recent history still stands between this server and the real host app.
private Map _bugReportWrapperFrom(Map client, List recent) {
    if (_bugReportClientIsWrapper(client)) return client
    return (recent ?: []).find { it instanceof Map && it["name"] && _bugReportClientIsWrapper(it) }
}

private List _bugReportSettingsLines(String privacyMode) {
    def eff = { raw, fallback -> raw == null ? "${fallback} (default)" : raw.toString() }
    def nameList = { raw -> (raw ?: []).collect { it.toString() } }
    def disabledGateways = nameList(settings.disabled_gateways)
    def disabledTools = nameList(settings.disabled_tools)
    def extraOrigins = _configuredExtraOriginHosts()
    // Hub Security is reported as a BOOLEAN only -- the username and password stay out of
    // every report, private mode included.
    return [
        "- **Read tools:** ${eff(settings.enableRead, true)}",
        "- **Write tools:** ${eff(settings.enableWrite, true)}",
        "- **Developer mode:** ${eff(settings.enableDeveloperMode, false)}",
        "- **Best-practice ack required:** ${eff(settings.enableMandatoryBPS, true)}",
        "- **Legacy custom rule engine:** ${eff(settings.enableCustomRuleEngine, false)}",
        "- **Bypass device allowlist:** ${eff(settings.bypassDeviceAllowlist, false)}",
        "- **Hub security enabled:** ${eff(settings.hubSecurityEnabled, false)}",
        "- **Tool mode (useGateways):** ${settings.useGateways == null ? 'gateway (default)' : (settings.useGateways == false ? 'flat' : 'gateway')}",
        "- **MCP log level (UI setting):** ${settings.mcpLogLevel == null ? 'not set (effective level above)' : settings.mcpLogLevel.toString()}",
        "- **Hubitat console logging:** ${eff(settings.debugLogging, false)}",
        "- **Disabled gateways:** ${disabledGateways ? disabledGateways.join(', ') : 'none (default)'}",
        "- **Disabled tools:** ${disabledTools ? disabledTools.join(', ') : 'none (default)'}",
        "- **Enforce Origin validation:** ${eff(settings.enforceOriginValidation, false)}",
        "- **Extra allowed origins:** ${privacyMode == 'public' ? "${extraOrigins.size()} configured" : (extraOrigins ? extraOrigins.join(', ') : 'none (default)')}",
        "- **Max concurrent writes:** ${eff(settings.maxConcurrentWrites, 2)}",
        "- **Cloud-relay budget (ms):** ${eff(settings.relayBudgetMs, 6000)}",
        "- **LAN budget (ms):** ${eff(settings.lanBudgetMs, 0)}",
        "- **Back up before every native app edit:** ${eff(settings.backupEveryRuleWrite, false)}",
        "- **Max captured states:** ${eff(settings.maxCapturedStates, 20)}",
        "- **Loop guard max executions:** ${eff(settings.loopGuardMax, 30)}",
        "- **Loop guard window (sec):** ${eff(settings.loopGuardWindowSec, 60)}"
    ]
}

// A pasted payload can carry its own fence, so the block opens on a longer backtick run than
// anything inside it -- otherwise the first inner fence closes the block early.
private String _bugReportFence(String text) {
    String source = text ?: ""
    int longest = 0
    int run = 0
    for (int i = 0; i < source.length(); i++) {
        if (source.substring(i, i + 1) == "`") {
            run++
            if (run > longest) longest = run
        } else {
            run = 0
        }
    }
    String fence = "```"
    while (fence.length() <= longest) fence = fence + "`"
    return fence
}

// Wraps only the free-prose fields. Lines inside a ``` fence and any single word longer
// than the width are left alone, so pasted payloads survive intact.
private String _bugReportWrap(String text, int width = 100) {
    if (text == null) return null
    def limit = width > 0 ? width : 100
    boolean inFence = false
    def out = []
    text.split("\n", -1).each { String line ->
        if (line.trim().startsWith("```")) {
            inFence = !inFence
            out << line
            return
        }
        // A line that opens with whitespace is preformatted (indented code), so re-wrapping it
        // would destroy the layout it was indented to keep.
        if (inFence || line.length() <= limit || line.startsWith(" ") || line.startsWith("\t")) {
            out << line
            return
        }
        String current = null
        line.split(" ").each { String word ->
            if (current == null) {
                current = word
            } else if (current.length() + 1 + word.length() <= limit) {
                current = "${current} ${word}".toString()
            } else {
                out << current
                current = word
            }
        }
        out << (current == null ? "" : current)
    }
    return out.join("\n")
}

private List _bugReportMissingContext(args, String issueType, Map client = null, List recent = null) {
    def blank = { value -> !(value?.toString()?.trim()) }
    def missing = []
    Map wrapperRecord = _bugReportWrapperFrom(client, recent)
    boolean wrapper = wrapperRecord != null
    boolean unidentified = !client?.name || wrapper
    def why = wrapper ? "the client identifies as '${wrapperRecord.name}${wrapperRecord.version ? ' ' + wrapperRecord.version : ''}', a transport wrapper (stdio-to-HTTP bridge), not the host app".toString() : "the client sent no self-report on this request"
    if (blank(args.llmClient)) {
        def ask = "Ask the user which app they run (Claude Code, Claude Desktop, Claude.ai web, ChatGPT desktop, Cursor, ...) and pass it as llmClient."
        if (unidentified) ask = "${ask} The server could not identify the client (${why}): do NOT guess or infer it -- ask the user.".toString()
        missing << [field: "llmClient", ask: ask]
    } else if (unidentified) {
        missing << [field: "llmClient", ask: "Confirm with the user that '${args.llmClient.toString().trim()}' is the host app they run: the server could not identify the client (${why}), so an inferred value must not stand.".toString()]
    }
    if (blank(args.llmModel)) {
        missing << [field: "llmModel", ask: "Ask the user which model is in use (Claude Opus 5, Sonnet 5, GPT-5, ...) and pass it as llmModel -- do not guess."]
    }
    // An agent-behavior report is diagnosed from the same evidence as a bug: what was called,
    // what came back, and what the client's own log said.
    if (issueType in ["bug", "agent_behavior"]) {
        if (blank(args.stepsToReproduce)) {
            missing << [field: "stepsToReproduce", ask: "Write the exact sequence that reproduces the failure and pass it as stepsToReproduce."]
        }
        if (blank(args.verbatimToolCalls)) {
            missing << [field: "verbatimToolCalls", ask: "Copy the exact failing tool call(s) and the raw response text out of the transcript and pass them as verbatimToolCalls."]
        }
        if (blank(args.clientLogs)) {
            missing << [field: "clientLogs", ask: "Collect the MCP client host's own log lines for the failure window and pass them as clientLogs."]
        }
    }
    return missing
}

private List _bugReportPreflight(String issueType) {
    def verbatimStep = "Paste the exact tool calls and raw responses in verbatimToolCalls -- do not paraphrase."
    // An agent-behavior report is about what the agent did, so the transcript is the whole
    // evidence; server-side log level changes nothing about it.
    if (issueType == "agent_behavior") return [verbatimStep]
    if (issueType != "bug") return []
    def steps = []
    def level = getConfiguredLogLevel()
    if (level != "debug") {
        steps << "MCP log level is ${level}. Call hub_set_log_level(level='debug'), reproduce the failure, then call hub_report_issue again so the report carries debug entries.".toString()
    }
    steps << "Attach logs from every source: hub_get_logs(mode='hub') for native hub logs around the failure, mode='mcp' for MCP entries (error/warn already attached), and your client host's own MCP logs via clientLogs."
    steps << verbatimStep
    return steps
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
    def reproSection = args.stepsToReproduce ? "\n### Steps to Reproduce\n${_bugReportWrap(args.stepsToReproduce.toString())}\n" : ""
    def settingsSection = "## MCP Server Settings\n" + (env.settingsLines ?: []).join("\n") + "\n"
    def verbatim = args.verbatimToolCalls?.toString()?.trim()
    def clientLogText = args.clientLogs?.toString()?.trim()
    // An absent field is rendered as a visible gap on the reports that need it, so the reader can
    // see the agent skipped it rather than guessing whether it had nothing to paste.
    boolean needsEvidence = issueType in ["bug", "agent_behavior"]
    def verbatimFence = _bugReportFence(verbatim)
    def clientLogFence = _bugReportFence(clientLogText)
    def verbatimSection = verbatim ? "\n## Verbatim Tool Calls\n${verbatimFence}text\n${verbatim}\n${verbatimFence}\n" : (needsEvidence ? "\n## Verbatim Tool Calls\n_Not provided_\n" : "")
    def clientLogSection = clientLogText ? "\n## Client-Side Logs\n${clientLogFence}text\n${clientLogText}\n${clientLogFence}\n" : (needsEvidence ? "\n## Client-Side Logs\n_Not provided_\n" : "")
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
    if (params.logReadError) {
        logSection = "## Recent Error/Warning Logs\n_MCP log history unavailable. Log counts and evidence could not be recovered; retry after native logging is available._"
    } else if (!includeRawLogs) {
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

    def md = """# ${heading}: ${args.title}

**Generated:** ${formatTimestamp(now())}
**MCP Server Version:** ${env.version}
**Issue type:** ${issueType}
**Privacy mode:** ${privacyMode}

## Environment
- **Hub name:** ${env.hubName}
- **Hub model:** ${env.hubModel}
- **Hub firmware:** ${env.hubFirmware}
- **Time zone:** ${env.timeZone}
- **Connection:** ${env.connection}
- **Client (MCP self-report):** ${env.clientSelfReport}
- **Protocol version:** ${env.protocolVersion}
- **MCP log level:** ${env.logLevel}
- **Tool mode:** ${env.toolMode}
- **Rules in legacy custom rule engine:** ${env.customMcpRuleCount}
- ${env.nativeRm.installed == false ? "**Native Rule Machine:** not installed (Rule Machine not detected on this hub)" : "**Native Rule Machine rules:** ${env.nativeRm.count}${env.nativeRm.error ? ' (RMUtils partial failure — count may be inaccurate)' : ''}"}
- **Devices exposed to MCP:** ${env.deviceCount}
- **LLM / client:** ${env.llmClient}
- **Model:** ${env.llmModel}
${failingToolLine}${nativeAppLine}
${settingsSection}
${expectedActualHeader}

### Expected
${_bugReportWrap(args.expected?.toString() ?: "")}

### Actual
${_bugReportWrap(args.actual?.toString() ?: "")}
${reproSection}${verbatimSection}${clientLogSection}${ruleSection}
${logSection}

## Additional Context
_Add any other context, screenshots, or transcripts when filing._
"""
    // The hub appends "// library marker mcp.<Lib>, line N" to every physical line of an
    // #include'd library at compile time; lines INSIDE this (and ruleSection's) multi-line
    // """ string literal capture those markers into the runtime text. Strip them so the
    // user-facing report is clean on bundle-deployed hubs (found via issue #342). This also
    // catches any marker that rode in through the interpolated ${ruleSection} block.
    // _stripLibraryMarkers lives in the main app (it also cleans tool descriptions).
    return _stripLibraryMarkers(md)
}

def _getAllToolDefinitions_partDebugLogging() {
    return [
        // Debug Logging Tools
        [
            name: "hub_delete_debug_logs",
            description: "Clear the structured MCP history view read by hub_get_logs(mode='mcp').[[FLAT_TRIM]] A durable clear marker keeps old native entries from reappearing after reload. Use before reproducing an issue. Does NOT touch Hubitat system logs (hub_get_logs) or captured device states (hub_delete_captured_state).[[/FLAT_TRIM]] Cannot be undone.",
            inputSchema: [type: "object", properties: [:]]
        ],
        [
            name: "hub_set_log_level",
            description: "Set the minimum log level threshold. Logs below this level won't be stored.",
            inputSchema: [
                type: "object",
                properties: [
                    level: [type: "string", enum: ["debug", "info", "warn", "error"], description: "Minimum log level to store"]
                ],
                required: ["level"]
            ]
        ],
        [
            name: "hub_report_issue",
            description: "File or report a bug, open a GitHub issue, request a feature/enhancement, or flag agent-behavior issues against this MCP server. Does NOT submit the issue itself: returns a prefilled GitHub issue link plus the report body.[[FLAT_TRIM]] It gathers scoped recent logs and hub/version info; the user opens the link and posts. Paste real tool calls and client-host log lines rather than describing them; the result's preflight and missingContext name anything still missing.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    title: [type: "string", description: "Short bug/issue narrative. Seeds GitHub title."],
                    expected: [type: "string", description: "What should have happened."],
                    actual: [type: "string", description: "What actually happened."],
                    stepsToReproduce: [type: "string", description: "Exact repro sequence."],
                    issueType: [type: "string", enum: ["bug", "enhancement", "agent_behavior"], description: "Default bug."],
                    failingTool: [type: "string", description: "MCP tool that failed; scopes logs + titles issue."],
                    ruleId: [type: "string", description: "Legacy custom MCP rule-engine rule id; scopes logs to it.[[FLAT_TRIM]] A native Rule Machine rule goes in nativeAppId, not here.[[/FLAT_TRIM]]"],
                    nativeAppId: [type: "string", description: "Native Rule Machine app id; scopes logs to that app.[[FLAT_TRIM]] A legacy custom MCP rule goes in ruleId.[[/FLAT_TRIM]]"],
                    llmClient: [type: "string", description: "Host app + version (Claude Code 2.1, Claude Desktop, Claude.ai web...); 'Claude' alone is not enough; ask, never guess."],
                    llmModel: [type: "string", description: "Model in use (Opus 5, Sonnet 5, GPT-5...); ask if unknown."],
                    verbatimToolCalls: [type: "string", description: "EXACT failing call (tool + args JSON) and EXACT raw response text, not paraphrased.[[FLAT_TRIM]] Copy from the transcript: the wording of the real error is usually the whole diagnosis.[[/FLAT_TRIM]]"],
                    clientLogs: [type: "string", description: "Raw MCP client-host log lines for the failure window.[[FLAT_TRIM]] Claude Desktop writes mcp-server-*.log; Claude Code has its own debug log. Paste the lines, not a summary.[[/FLAT_TRIM]]"],
                    privacyMode: [type: "string", enum: ["private", "public"], description: "'public' placeholders hub name, suppresses raw logs."],
                    includeRawLogs: [type: "boolean", description: "Default: true private, false public."],
                    includeUnrelatedRecentLogs: [type: "boolean", description: "When scoped (failingTool/ruleId/nativeAppId set), also attach recent logs outside that scope.[[FLAT_TRIM]] Default false, no-op when unscoped.[[/FLAT_TRIM]]"],
                    logWindowSeconds: [type: "integer", description: "Default 120."]
                ],
                required: ["title", "expected", "actual"]
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
        "hub_report_issue"
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
        hub_delete_debug_logs: [title: "Clear MCP Debug Logs", summary: "Clear all MCP debug log entries."],
        hub_set_log_level: [title: "Set MCP Log Level", summary: "Set the MCP log level (debug, info, warn, error)."]
    ]
}
