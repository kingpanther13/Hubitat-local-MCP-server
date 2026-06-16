library(name: "McpDiagnosticsLib", namespace: "mcp", author: "kingpanther13", description: "Diagnostics + maintenance tool implementations (hub logs/performance/jobs/metrics/memory/radio/device-health/GC/Z-Wave repair/captured states) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// Radio-details dispatch helper. Extracted from the executeTool switch so the
// case body is a plain method call: a bare `{ ... }` block right after a
// `case X:` label is rejected by the hub's Groovy parser (ambiguous
// parameterless-closure vs open-block) even though hubitat_ci's parser accepts it.
def toolGetRadioDetails(args) {
    def radio = args.radio
    if (radio != null && !(radio in ["zwave", "zigbee"]))
        throw new IllegalArgumentException("radio must be 'zwave' or 'zigbee' (or omit for both)")
    if (radio == "zwave") return toolGetZwaveDetails(args)
    if (radio == "zigbee") return toolGetZigbeeDetails(args)
    return [zwave: toolGetZwaveDetails(args), zigbee: toolGetZigbeeDetails(args)]
}

def toolGetZwaveDetails(args) {

    def hub = location.hub
    def result = [:]

    // Basic Z-Wave info from hub object
    try { result.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { result.zwaveVersion = "unavailable" }

    // Extended Z-Wave info via internal API
    // Firmware 2.3.7.1+ uses /hub/zwaveDetails/json; older uses /hub2/zwaveInfo
    def zwaveEndpoints = ["/hub/zwaveDetails/json", "/hub2/zwaveInfo"]
    def zwaveSuccess = false
    for (endpoint in zwaveEndpoints) {
        try {
            def responseText = hubInternalGet(endpoint)
            if (responseText) {
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                    result.zwaveData = parsed
                    result.source = "hub_api"
                    result.endpoint = endpoint
                    zwaveSuccess = true
                } catch (Exception parseErr) {
                    result.rawResponse = responseText?.take(3000)
                    result.source = "hub_api_raw"
                    result.endpoint = endpoint
                    result.note = "Response was not JSON format"
                    zwaveSuccess = true
                }
            }
            if (zwaveSuccess) break
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Z-Wave endpoint ${endpoint} failed: ${e.message}")
            // Try next endpoint
        }
    }

    if (!zwaveSuccess) {
        result.source = "sdk_only"
        result.note = "Extended Z-Wave info unavailable from all endpoints. Showing basic info from hub SDK."
    }

    if (args?.include_topology) result.topology = _fetchRadioTopology("zwave")

    mcpLog("info", "hub-admin", "Retrieved Z-Wave details")
    return result
}

// Read-only mesh route/topology for a radio. Z-Wave: /hub/zwave/getChildAndRouteInfoJson
// (nodes + source->target connectors) + /hub/zwaveTopology (raw route table). Zigbee:
// /hub/zigbee/getChildAndRouteInfoJson (children + neighbors + routes with status/age/nextHopId).
// All GET/read-only -- no radio mutation, so this does not touch the no-radio-ops guardrail.
private Map _fetchRadioTopology(String radio) {
    def topo = [:]
    def jsonEndpoint = (radio == "zwave") ? "/hub/zwave/getChildAndRouteInfoJson" : "/hub/zigbee/getChildAndRouteInfoJson"
    topo.endpoint = jsonEndpoint
    try {
        def txt = hubInternalGet(jsonEndpoint)
        if (txt) {
            try { topo.routes = new groovy.json.JsonSlurper().parseText(txt) }
            catch (Exception parseErr) { topo.rawRoutes = txt?.take(8000); topo.note = "Route info was not JSON format" }
        }
    } catch (Exception e) {
        topo.error = "Failed to fetch ${jsonEndpoint}: ${e.message}"
    }
    if (radio == "zwave") {
        try {
            def t = hubInternalGet("/hub/zwaveTopology")
            if (t) topo.zwaveTopologyTable = t.take(8000)
        } catch (Exception e) {
            // The raw route table is optional context on top of the JSON route info above.
        }
    }
    return topo
}

def toolGetZigbeeDetails(args) {

    def hub = location.hub
    def result = [:]

    // Basic Zigbee info from hub object
    try { result.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { result.zigbeeChannel = "unavailable" }
    try { result.zigbeeId = hub?.zigbeeId } catch (Exception e) { result.zigbeeId = "unavailable" }

    // Extended Zigbee info via internal API
    // Firmware 2.3.7.1+ uses /hub/zigbeeDetails/json; older uses /hub2/zigbeeInfo
    def zigbeeEndpoints = ["/hub/zigbeeDetails/json", "/hub2/zigbeeInfo"]
    def zigbeeSuccess = false
    for (endpoint in zigbeeEndpoints) {
        try {
            def responseText = hubInternalGet(endpoint)
            if (responseText) {
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                    result.zigbeeData = parsed
                    result.source = "hub_api"
                    result.endpoint = endpoint
                    zigbeeSuccess = true
                } catch (Exception parseErr) {
                    result.rawResponse = responseText?.take(3000)
                    result.source = "hub_api_raw"
                    result.endpoint = endpoint
                    result.note = "Response was not JSON format"
                    zigbeeSuccess = true
                }
            }
            if (zigbeeSuccess) break
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Zigbee endpoint ${endpoint} failed: ${e.message}")
            // Try next endpoint
        }
    }

    if (!zigbeeSuccess) {
        result.source = "sdk_only"
        result.note = "Extended Zigbee info unavailable from all endpoints. Showing basic info from hub SDK."
    }

    if (args?.include_topology) result.topology = _fetchRadioTopology("zigbee")

    mcpLog("info", "hub-admin", "Retrieved Zigbee details")
    return result
}

def toolGetHubLogs(args) {

    def maxLimit = 500
    def limit = Math.min(args.limit ?: 100, maxLimit)
    def levelFilter = args.level
    def sourceFilter = args.source
    def deviceIdFilter = args.deviceId?.toString()?.trim()
    def appIdFilter = args.appId?.toString()?.trim()

    if (deviceIdFilter && appIdFilter) {
        throw new IllegalArgumentException("deviceId and appId are mutually exclusive: set only one")
    }

    // --- Type-shape validation: catch wrong-type args before any parsing or regex compile ---
    // pattern must be a String; List callers occasionally pass ['foo'] expecting a substring search
    if (args.pattern != null && !(args.pattern instanceof String)) {
        throw new IllegalArgumentException("pattern must be a string (got ${args.pattern instanceof List ? 'list' : 'non-string'})")
    }
    // patterns must be a List; a bare String is never silently treated as a single-element list
    if (args.patterns != null && !(args.patterns instanceof List)) {
        throw new IllegalArgumentException("patterns must be a list of strings (got ${args.patterns instanceof String ? 'string' : 'non-list'})")
    }
    // All elements inside patterns must be strings
    if (args.patterns instanceof List) {
        for (int i = 0; i < args.patterns.size(); i++) {
            def pi = args.patterns[i]
            if (pi != null && !(pi instanceof String)) {
                throw new IllegalArgumentException("patterns[${i}] must be a string (got ${pi instanceof List ? 'list' : pi instanceof Number ? 'number' : 'non-string'})")
            }
        }
    }
    // since / until must be Strings (ISO-8601 or relative offset); numeric ms would require a
    // different parsing path and silently no-op the time-window filter if passed as a number
    if (args.since != null && !(args.since instanceof String)) {
        throw new IllegalArgumentException("since must be a string (ISO-8601 timestamp or relative offset like '30m', '2h', '1d')")
    }
    if (args.until != null && !(args.until instanceof String)) {
        throw new IllegalArgumentException("until must be a string (same format as since)")
    }

    // --- Compile regex patterns before the loop (once, not per entry) ---

    // Single pattern (case-insensitive substring regex against message field)
    def compiledPattern = null
    if (args.pattern != null) {
        def rawPat = args.pattern.toString()
        if (rawPat.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be empty (got empty string); omit pattern arg to skip pattern filter")
        }
        if (rawPat.length() > 100) {
            throw new IllegalArgumentException("pattern exceeds 100 char limit (was ${rawPat.length()} chars)")
        }
        try {
            compiledPattern = java.util.regex.Pattern.compile(rawPat, java.util.regex.Pattern.CASE_INSENSITIVE)
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern '${rawPat}': ${e.message}")
        }
    }

    // Multi-pattern (case-insensitive regex list; patternMode controls AND/OR)
    def compiledPatterns = []
    def patternModeAll = (args.patternMode?.toString()?.toLowerCase() == "all")
    if (args.patternMode != null) {
        def pm = args.patternMode.toString().toLowerCase()
        if (pm != 'any' && pm != 'all') {
            throw new IllegalArgumentException("patternMode must be 'any' or 'all' (got '${args.patternMode}')")
        }
    }
    if (args.patterns instanceof List && args.patterns) {
        for (int pi = 0; pi < args.patterns.size(); pi++) {
            def rawPat = args.patterns[pi]
            if (rawPat == null) {
                throw new IllegalArgumentException("patterns[${pi}] must not be null or empty")
            }
            rawPat = rawPat.toString()
            if (rawPat.isEmpty()) {
                throw new IllegalArgumentException("patterns[${pi}] must not be null or empty")
            }
            if (rawPat.length() > 100) {
                throw new IllegalArgumentException("patterns[${pi}] exceeds 100 char limit (was ${rawPat.length()} chars)")
            }
            try {
                compiledPatterns << java.util.regex.Pattern.compile(rawPat, java.util.regex.Pattern.CASE_INSENSITIVE)
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regex pattern '${rawPat}' (patterns[${pi}]): ${e.message}")
            }
        }
    }

    // --- Parse since/until time bounds before the loop ---

    // Relative-offset regex: <N><unit> where unit in m, h, d. Capped at 30d.
    def maxRelativeMs = 30L * 24 * 60 * 60 * 1000  // 30 days in ms

    // Supported ISO-8601 and hub-native timestamp formats for log entry times
    def logTimeFmts = [
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss"
    ]

    Closure parseTimeArg = { String argName, String val ->
        if (!val) return null
        // Try relative offset: <N>m / <N>h / <N>d
        def relMatcher = val =~ /^(\d+)([mhd])$/
        if (relMatcher.matches()) {
            long n = relMatcher.group(1).toLong()
            def unit = relMatcher.group(2)
            long ms
            switch (unit) {
                case 'm': ms = n * 60L * 1000; break
                case 'h': ms = n * 3600L * 1000; break
                case 'd': ms = n * 86400L * 1000; break
                default: ms = 0
            }
            if (ms > maxRelativeMs) {
                throw new IllegalArgumentException("${argName} exceeds 30d cap (got '${val}'); use ISO-8601 for longer ranges (e.g. '2024-01-15T00:00:00Z')")
            }
            return new Date(now() - ms)
        }
        // Try ISO-8601 / timestamp formats.
        // Values ending with literal 'Z' (UTC designator) must be parsed in UTC;
        // Date.parse with a 'Z'-literal format uses JVM default TZ, which shifts
        // the epoch by the hub's local offset. Detect the Z-suffix and use an explicit
        // UTC-anchored SimpleDateFormat so "2024-01-15T00:00:00Z" resolves to UTC midnight.
        if (val.endsWith("Z")) {
            def utcTz = TimeZone.getTimeZone("UTC")
            // Strip trailing Z; try ISO patterns without the Z suffix against the bare value.
            def bare = val[0..-2]
            def isoFmtsNoZ = ["yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"]
            for (fmt in isoFmtsNoZ) {
                try {
                    def sdf2 = new java.text.SimpleDateFormat(fmt)
                    sdf2.setTimeZone(utcTz)
                    sdf2.setLenient(false)
                    return sdf2.parse(bare)
                } catch (java.text.ParseException ignored) {}
            }
        }
        // Naked-T ISO form without a TZ designator (e.g. '2024-01-15T10:30:00' or
        // '2024-01-15T10:30:00.000'): treat as UTC to match the hub's log timestamp
        // convention. Date.parse() uses JVM default TZ for these formats, which silently
        // shifts the intended epoch on hubs running in non-UTC timezones.
        if (val.contains('T') && !val.endsWith('Z')) {
            def utcTz = TimeZone.getTimeZone("UTC")
            def isoNoTzFmts = ["yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"]
            for (fmt in isoNoTzFmts) {
                try {
                    def sdf = new java.text.SimpleDateFormat(fmt)
                    sdf.setTimeZone(utcTz)
                    sdf.setLenient(false)
                    return sdf.parse(val)
                } catch (java.text.ParseException ignored) {}
            }
        }
        // Space-separated hub-native formats (e.g. 'yyyy-MM-dd HH:mm:ss.SSS') carry no TZ
        // designator. Date.parse() interprets them in JVM default TZ, which shifts the epoch
        // on non-UTC hubs. Use explicit UTC SimpleDateFormat for all non-Z formats so a user
        // copying a hub log timestamp as a since/until value gets the same UTC interpretation
        // the entry-side parser uses.
        def utcTzFallback = TimeZone.getTimeZone("UTC")
        for (fmt in logTimeFmts) {
            try {
                if (!fmt.contains("Z") && !fmt.contains("'Z'")) {
                    def sdf = new java.text.SimpleDateFormat(fmt)
                    sdf.setTimeZone(utcTzFallback)
                    sdf.setLenient(false)
                    return sdf.parse(val)
                }
                return Date.parse(fmt, val)
            } catch (java.text.ParseException ignored) {}
        }
        throw new IllegalArgumentException("Cannot parse ${argName}='${val}' -- use ISO-8601 (e.g. '2024-01-15T10:30:00Z') or a relative offset like '30m', '2h', '1d'")
    }

    def sinceDate = parseTimeArg("since", args.since?.toString()?.trim())
    def untilDate = parseTimeArg("until", args.until?.toString()?.trim())

    // Guard against inverted time window. With relative offsets, since='1h' means
    // "1 hour ago" and until='2h' means "2 hours ago", making since > until -- a
    // window with no entries that is indistinguishable from a genuine empty result.
    if (sinceDate != null && untilDate != null && sinceDate.after(untilDate)) {
        throw new IllegalArgumentException("since='${args.since}' resolves later than until='${args.until}' -- window is empty (relative offsets are subtracted from now, so since='2h' means 2 hours ago)")
    }

    // Server-side scoping: the hub's /logs/past/json endpoint accepts ?type=dev&id=<N>
    // or ?type=app&id=<N> to filter at the source (same mechanism the UI's device- and
    // app-specific log pages use). Much cheaper than returning the whole buffer and
    // filtering client-side when the caller only wants one device/app. The level and
    // source filters plus the limit below still apply client-side on top of the scoped
    // result; they are not replaced by deviceId/appId.
    //
    // Both ids must be validated before the HTTP call. The hub returns 200 OK with an
    // empty array for unknown or non-numeric ids, which would otherwise be indistinguishable
    // from a real device that simply has no log entries.
    def query = null
    if (deviceIdFilter) {
        def device = findDevice(deviceIdFilter)
        if (!device) {
            throw new IllegalArgumentException("Device not found: ${deviceIdFilter}")
        }
        query = [type: "dev", id: deviceIdFilter]
    } else if (appIdFilter) {
        if (!appIdFilter.isInteger()) {
            throw new IllegalArgumentException("appId must be numeric: ${appIdFilter}")
        }
        query = [type: "app", id: appIdFilter]
    }

    mcpLog("info", "monitoring", "Fetching hub logs (level=${levelFilter}, source=${sourceFilter}, deviceId=${deviceIdFilter}, appId=${appIdFilter}, limit=${limit})")

    def responseText = null
    try {
        responseText = hubInternalGet("/logs/past/json", query, 30)
    } catch (Exception e) {
        mcpLogError("monitoring", "Failed to fetch hub logs", e)
        throw new IllegalStateException("Failed to fetch hub logs: ${e.message}")
    }

    if (!responseText) {
        return [logs: [], message: "No log data returned from hub", count: 0]
    }

    // The /logs/past/json endpoint returns a JSON array of tab-delimited strings:
    // ["name\tlevel\tmessage\ttime\ttype", ...]
    def logs = []
    def logArray = []
    try {
        logArray = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        // If not JSON, fall back to splitting by newlines (older firmware)
        mcpLog("debug", "monitoring", "Hub logs response not JSON, falling back to line-split: ${e.message}")
        logArray = responseText.split("\n").toList()
    }

    // Hub returns chronological order (oldest-first). Callers overwhelmingly want
    // the most recent N entries — reverse so the limit trims the tail of the buffer
    // rather than the head. Guard against non-List parse results (a String or Map
    // from the newline-split fallback or a firmware variant) since List.reverse()
    // only makes sense on the array case.
    if (!(logArray instanceof List)) {
        return [logs: [], error: "Unexpected log format from hub", count: 0]
    }
    logArray = logArray.reverse()

    def totalParsed = logArray.size()

    // Hub log timestamps from /logs/past/json carry no TZ marker but represent UTC.
    // Parsing them with Date.parse() (JVM default TZ) would shift them by the hub's
    // local offset, causing the time-window comparison to silently no-op on hubs in
    // non-UTC timezones. Build reusable UTC-anchored parsers once per call, outside
    // the per-entry loop, to avoid constructing SimpleDateFormat on every iteration.
    def hubLogUtcTz = TimeZone.getTimeZone("UTC")
    def hubLogSdfs = logTimeFmts
        .findAll { !it.contains("Z") && !it.contains("'Z'") }
        .collect { fmt ->
            def sdf = new java.text.SimpleDateFormat(fmt)
            sdf.setTimeZone(hubLogUtcTz)
            sdf.setLenient(false)
            sdf
        }
    // Formats with a real Z offset marker (no quotes) carry explicit TZ info and Date.parse
    // handles them correctly. Formats with a literal 'Z' in quotes are intentionally excluded
    // here: Date.parse treats 'Z' as a literal character match, not a TZ marker, so it
    // interprets the value in JVM default TZ -- the same TZ bug hubLogSdfs was built to avoid.
    // Hub /logs/past/json is confirmed not to emit 'T'-with-quoted-'Z' timestamps on any known
    // firmware; keeping them in the fallback list would silently no-op the time-window filter
    // on non-UTC hubs if a future firmware ever did emit them.
    def hubLogIsoFmts = logTimeFmts.findAll { it.contains("Z") && !it.contains("'Z'") }

    // Counter for entries that passed through the time-window filter due to unparseable timestamps.
    // Populated only when since or until is active; surfaced in the response as timeFilterUnparseable.
    def timeFilterUnparseable = 0

    // Count entries excluded by the active filter set (level / source / pattern / patterns /
    // time-window). Does NOT include entries truncated by the limit parameter or malformed entries
    // (empty lines or too-few tab fields) -- those are pre-filter dropouts, not filter exclusions.
    def filterExcluded = 0

    for (logEntry in logArray) {
        def line = logEntry?.toString()
        if (!line?.trim()) continue
        def parts = line.split("\t", -1)
        if (parts.size() < 2) continue

        def entry = [
            name: parts[0]?.trim(),
            level: parts.size() > 1 ? parts[1]?.trim() : "",
            message: parts.size() > 2 ? parts[2]?.trim() : "",
            time: parts.size() > 3 ? parts[3]?.trim() : "",
            type: parts.size() > 4 ? parts[4]?.trim() : ""
        ]

        // If message field contains tabs (extra fields), rejoin the middle parts
        if (parts.size() > 5) {
            try {
                entry.message = parts[2..(parts.size() - 3)].join("\t")
                entry.time = parts[-2]?.trim()
                entry.type = parts[-1]?.trim()
            } catch (Exception e) {
                // Fall back to simple parsing
            }
        }

        // Apply filters in pipeline order:
        // scope (hub-side, done above) -> level -> source -> pattern -> patterns -> time window -> limit

        if (levelFilter && entry.level?.toLowerCase() != levelFilter.toLowerCase()) { filterExcluded++; continue }
        if (sourceFilter) {
            def src = sourceFilter.toLowerCase()
            // Source info is in the message field (format: "app|ID|AppName|..." or "dev|ID|DevName|...")
            if (!entry.message?.toLowerCase()?.contains(src) && !entry.name?.toLowerCase()?.contains(src)) { filterExcluded++; continue }
        }

        // Single-pattern regex against the message field
        if (compiledPattern != null) {
            if (!compiledPattern.matcher(entry.message ?: "").find()) { filterExcluded++; continue }
        }

        // Multi-pattern: patternMode='all' requires every pattern to match; 'any' (default) requires at least one
        if (compiledPatterns) {
            def msg = entry.message ?: ""
            if (patternModeAll) {
                if (!compiledPatterns.every { it.matcher(msg).find() }) { filterExcluded++; continue }
            } else {
                if (!compiledPatterns.any { it.matcher(msg).find() }) { filterExcluded++; continue }
            }
        }

        // Time-window filter: parse entry.time lazily; if empty (firmware 2.5.0.126+ puts the
        // timestamp in parts[0]/entry.name), fall back to entry.name before giving up.
        // Entries with unparseable timestamps are kept (not excluded) and counted separately
        // so callers know they exist in the result alongside filtered entries.
        if (sinceDate != null || untilDate != null) {
            def entryTime = null
            def timeStr = entry.time?.trim() ?: entry.name?.trim()
            if (timeStr) {
                // Try UTC-anchored parsers first (hub format has no TZ marker but is UTC).
                for (sdf in hubLogSdfs) {
                    try {
                        entryTime = sdf.parse(timeStr)
                        break
                    } catch (java.text.ParseException ignored) {}
                }
                // Fall through to ISO-8601 formats that carry their own TZ marker.
                if (entryTime == null) {
                    for (fmt in hubLogIsoFmts) {
                        try {
                            entryTime = Date.parse(fmt, timeStr)
                            break
                        } catch (java.text.ParseException ignored) {}
                    }
                }
                if (entryTime == null) {
                    mcpLog("debug", "monitoring", "Could not parse log entry time '${timeStr?.take(30)}' for time-window filter -- entry not excluded")
                    timeFilterUnparseable++
                }
            } else {
                // No time field at all -- count as unparseable and pass through
                timeFilterUnparseable++
            }
            // If entryTime resolved: enforce bounds. If entryTime is null (no time field or unparseable): pass through.
            if (entryTime != null) {
                if (sinceDate != null && entryTime.before(sinceDate)) { filterExcluded++; continue }
                if (untilDate != null && entryTime.after(untilDate)) { filterExcluded++; continue }
            }
        }

        logs << entry
        if (logs.size() >= limit) break
    }

    // Truncation safety for 128KB cloud limit
    def cursor = args?.cursor
    def fullLogs = logs.toList()
    def paged = _paginateList(fullLogs, cursor, 100, "hub_get_logs")
    // appliedLimit surfaces the limit-truncated entry count so a cursor caller can
    // tell whether they're iterating within the full ring buffer or within a
    // user-specified ceiling. Default limit is 100; max 500. Pair with limit=500
    // for the largest practical full-buffer page.
    def result = [logs: paged.page, count: paged.page.size(), totalParsed: totalParsed, appliedLimit: limit]
    if (cursor != null) {
        result.total = fullLogs.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    // Per-entry truncation is independent of cursor pagination: cursor bounds the entry
    // count, the per-message take(200) bounds the message body so a single oversized
    // entry can't push the page past the cap on its own.
    def estimatedJsonSize = paged.page.sum(0) { (it.message?.length() ?: 0) + (it.name?.length() ?: 0) + 120 }
    if (estimatedJsonSize > hubResponseCapBytes() - 11072) {  // =120000; matches handleToolsCall responseSizeLimit
        paged.page.each { it.message = it.message?.take(200) }
        result.truncated = true
        result.note = "Log messages truncated to fit response size limit"
    }

    // Expose filter metadata so callers can distinguish "no matching logs" from "no logs exist".
    // filteredOut: entries excluded by the active filter set (level / source / pattern / patterns /
    //   time-window). Does NOT include entries truncated by the limit parameter or malformed entries.
    //   Omitted when hasFilters is false or when every parsed entry matched (filterExcluded == 0).
    // appliedFilters: echo of every non-null filter arg (omitted when no filters were active).
    def hasFilters = levelFilter || sourceFilter || compiledPattern != null || compiledPatterns || sinceDate != null || untilDate != null
    if (hasFilters) {
        if (filterExcluded > 0) result.filteredOut = filterExcluded
        def applied = [:]
        if (levelFilter)                applied.level       = levelFilter
        if (sourceFilter)               applied.source      = sourceFilter
        if (args.pattern != null)       applied.pattern     = args.pattern
        if (args.patterns instanceof List && args.patterns) applied.patterns = args.patterns
        if (compiledPatterns)           applied.patternMode = args.patternMode ?: 'any'
        if (args.since != null)         applied.since       = args.since
        if (args.until != null)         applied.until       = args.until
        result.appliedFilters = applied
    }
    // Surface unparseable-timestamp count only when a time-window was active and at least one entry was affected.
    if ((sinceDate != null || untilDate != null) && timeFilterUnparseable > 0) {
        result.timeFilterUnparseable = timeFilterUnparseable
    }

    // Flag known-benign RM-internal noise so callers don't read it as a real
    // error. RM 5.1's own `periodic` page method logs an unguarded NPE on
    // params.n (against the rule app, not us) while RM renders the periodic
    // sub-page during a periodic-trigger build -- non-fatal; the trigger bakes.
    def benignRmNoiseCount = paged.page.count { _isBenignRmInternalNoise(it.message) }
    if (benignRmNoiseCount > 0) {
        result.benignRmNoiseCount = benignRmNoiseCount
        result.benignRmNoiseNote = "${benignRmNoiseCount} of these entr${benignRmNoiseCount == 1 ? 'y is' : 'ies are'} known-benign RM-internal noise (RM 5.1's own 'periodic' method logging an NPE on params.n while it renders the periodic sub-page during a periodic-trigger build). NON-FATAL, NOT an MCP-tool failure -- the trigger bakes correctly. Safe to ignore."
    }

    mcpLog("info", "monitoring", "Retrieved ${logs.size()} hub log entries (${totalParsed} total parsed)")
    return result
}

// Shared helper: fetch /logs/json from hub internal API
def fetchLogsJson() {
    def responseText = hubInternalGet("/logs/json", null, 30)
    if (!responseText) throw new RuntimeException("No data returned from /logs/json")
    return new groovy.json.JsonSlurper().parseText(responseText)
}

def toolGetPerformanceStats(args) {
    def type = args.type ?: "device"
    def sortBy = args.sortBy ?: "pct"
    def limit = args.limit != null ? args.limit : 20

    mcpLog("info", "monitoring", "Fetching performance stats (type=${type}, sortBy=${sortBy}, limit=${limit})")

    def data
    try {
        data = fetchLogsJson()
    } catch (Exception e) {
        mcpLogError("monitoring", "Failed to fetch performance stats", e)
        return [error: "Failed to fetch performance stats: ${e.message}"]
    }

    def result = [
        uptime: data.uptime
    ]

    def formatStats = { statsList ->
        if (!statsList) return []
        // Sort
        switch (sortBy) {
            case "count": statsList = statsList.sort { -(it.count ?: 0) }; break
            case "stateSize": statsList = statsList.sort { -(it.stateSize ?: 0) }; break
            case "totalMs": statsList = statsList.sort { -(it.total ?: 0) }; break
            case "name": statsList = statsList.sort { (it.name ?: "").toLowerCase() }; break
            default: statsList = statsList.sort { -(it.pct ?: 0) }; break
        }
        // Limit
        if (limit > 0 && statsList.size() > limit) {
            statsList = statsList.take(limit)
        }
        // Slim down to essential fields + useful diagnostics
        return statsList.collect { entry ->
            def item = [
                id: entry.id,
                name: entry.name,
                count: entry.count,
                pctBusy: entry.formattedPct,
                pctTotal: entry.formattedPctTotal,
                stateSize: entry.stateSize,
                totalMs: entry.total,
                averageMs: entry.average != null ? Math.round(entry.average * 100) / 100.0 : null,
                totalEvents: entry.customAttributes?.eventsCount,
                states: entry.customAttributes?.statesCount,
                hubActions: entry.hubActionCount,
                pendingEvents: entry.pendingEventsCount,
                cloudCalls: entry.cloudCallCount
            ]
            if (entry.largeState) item.largeState = true
            return item
        }
    }

    if (type == "device" || type == "both") {
        result.deviceSummary = [
            totalRuntime: data.totalDevicesRuntime,
            pctOfUptime: data.devicePct,
            deviceCount: data.deviceStats?.size() ?: 0
        ]
        result.deviceStats = formatStats(data.deviceStats)
    }

    if (type == "app" || type == "both") {
        result.appSummary = [
            totalRuntime: data.totalAppsRuntime,
            pctOfUptime: data.appPct,
            appCount: data.appStats?.size() ?: 0
        ]
        result.appStats = formatStats(data.appStats)
    }

    // Size guard: estimate response and warn if large
    def statsCount = (result.deviceStats?.size() ?: 0) + (result.appStats?.size() ?: 0)
    if (limit == 0) {
        result.note = "Returning all ${statsCount} entries. Use limit parameter to reduce response size."
    }

    mcpLog("info", "monitoring", "Retrieved performance stats: ${statsCount} entries (type=${type})")
    return result
}

def toolGetHubJobs(args) {
    mcpLog("info", "monitoring", "Fetching hub jobs")

    def data
    try {
        data = fetchLogsJson()
    } catch (Exception e) {
        mcpLogError("monitoring", "Failed to fetch hub jobs", e)
        return [error: "Failed to fetch hub jobs: ${e.message}"]
    }

    def scheduledJobs = (data.jobs ?: []).collect { job ->
        [
            id: job.id,
            name: job.name,
            recurring: job.recurring,
            method: job.methodName,
            nextRun: job.nextRun
        ]
    }

    def runningJobs = (data.runningJobs ?: []).collect { job ->
        [
            id: job.id,
            name: job.name,
            method: job.methodName
        ]
    }

    def hubActions = data.hubCommands ?: []

    return [
        uptime: data.uptime,
        scheduledJobs: [
            count: scheduledJobs.size(),
            jobs: scheduledJobs
        ],
        runningJobs: [
            count: runningJobs.size(),
            jobs: runningJobs
        ],
        hubActions: [
            count: hubActions.size(),
            actions: hubActions
        ]
    ]
}

def toolGetHubPerformance(args) {

    def recordSnapshot = args.recordSnapshot == true
    def trendPoints = Math.min(args.trendPoints ?: 10, 50)

    // Gather current metrics
    def current = [timestamp: formatTimestamp(now()), timestampEpoch: now()]

    try {
        current.freeMemoryKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim()
        try {
            def memKB = current.freeMemoryKB as Integer
            if (memKB < 50000) current.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
            else if (memKB < 100000) current.memoryNote = "Memory is moderate: ${memKB}KB free."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.freeMemoryKB = "unavailable" }

    try {
        current.internalTempC = hubInternalGet("/hub/advanced/internalTempCelsius")?.trim()
        try {
            def temp = current.internalTempC as Double
            if (temp > 70) current.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
            else if (temp > 60) current.temperatureNote = "Temperature is warm: ${temp}°C."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.internalTempC = "unavailable" }

    try {
        current.databaseSizeKB = hubInternalGet("/hub/advanced/databaseSize")?.trim()
        try {
            def dbKB = current.databaseSizeKB as Integer
            if (dbKB > 500000) current.databaseWarning = "LARGE DATABASE: ${(dbKB / 1024).toInteger()}MB. Consider cleaning up old data."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.databaseSizeKB = "unavailable" }

    try { current.uptimeSeconds = location.hub?.uptime } catch (Exception e) { current.uptimeSeconds = "unavailable" }
    if (current.uptimeSeconds && current.uptimeSeconds instanceof Number) {
        def days = (current.uptimeSeconds / 86400).toInteger()
        def hours = ((current.uptimeSeconds % 86400) / 3600).toInteger()
        def mins = ((current.uptimeSeconds % 3600) / 60).toInteger()
        current.uptimeFormatted = "${days}d ${hours}h ${mins}m"
    }

    // CSV history management
    def csvFileName = "mcp-performance-history.csv"
    def csvHeader = "timestamp,freeMemoryKB,internalTempC,databaseSizeKB,uptimeSeconds"
    def history = []

    // Read existing CSV from File Manager
    try {
        def existingBytes = downloadHubFile(csvFileName)
        if (existingBytes) {
            def csvText = new String(existingBytes, "UTF-8")
            def csvLines = csvText.split("\n")
            for (int i = 1; i < csvLines.size(); i++) {
                if (csvLines[i]?.trim()) history << csvLines[i].trim()
            }
        }
    } catch (Exception e) {
        // File doesn't exist yet, that's fine
        mcpLog("debug", "monitoring", "No existing performance CSV: ${e.message}")
    }

    // Record current snapshot to CSV
    if (recordSnapshot) {
        def csvRow = "${now()},${current.freeMemoryKB},${current.internalTempC},${current.databaseSizeKB},${current.uptimeSeconds}"
        history << csvRow

        // Trim to 500 rows (rolling window)
        if (history.size() > 500) {
            history = history.drop(history.size() - 500)
        }

        // Write back to File Manager
        def csvContent = csvHeader + "\n" + history.join("\n") + "\n"
        try {
            uploadHubFile(csvFileName, csvContent.getBytes("UTF-8"))
        } catch (Exception e) {
            mcpLog("warn", "monitoring", "Failed to write performance CSV: ${e.message}")
        }
    }

    // Parse recent trend points for response
    def trends = []
    def startIdx = Math.max(0, history.size() - trendPoints)
    for (int i = startIdx; i < history.size(); i++) {
        def parts = history[i].split(",", -1)
        if (parts.size() >= 5) {
            try {
                trends << [
                    timestamp: formatTimestamp(parts[0] as Long),
                    freeMemoryKB: parts[1],
                    internalTempC: parts[2],
                    databaseSizeKB: parts[3],
                    uptimeSeconds: parts[4]
                ]
            } catch (Exception e) {
                // Skip malformed rows
            }
        }
    }

    mcpLog("info", "monitoring", "Hub performance snapshot recorded=${recordSnapshot}, trendPoints=${trends.size()}")
    return [
        current: current,
        // The hub's OWN active health alerts (from /hub2/hubData) sit alongside the locally-derived
        // memory/temp/DB warnings on `current` -- e.g. the hub's hubLargeDatabase/hubLowMemory flags
        // complement (and may differ in threshold from) the databaseWarning/memoryWarning above. null
        // when /hub2/hubData is unreadable.
        healthAlerts: _healthAlertsFromHub2(_getHub2HubData()),
        trends: trends,
        trendPointsAvailable: history.size(),
        historyFile: csvFileName
    ]
}

def toolGetMemoryHistory(args) {

    def limit = args.limit != null ? args.limit : 100

    def rawText = hubInternalGet("/hub/advanced/freeOSMemoryHistory")
    if (!rawText) {
        return [entries: [], summary: [message: "No memory history data available"]]
    }

    def lines = rawText.trim().split("\n")
    def allEntries = []
    def memValues = []

    for (line in lines) {
        def trimmed = line?.trim()
        if (!trimmed) continue

        // Format: "Date/time,Free OS,5m CPU avg,Total Java,Free Java,Direct Java"
        def parts = trimmed.split(",", -1)
        if (parts.size() >= 3) {
            // Skip header/non-numeric lines by parsing memory value first
            def memKB = null
            try {
                memKB = parts[1]?.trim() as Integer
            } catch (Exception e) {
                // Header or non-numeric line — skip
                continue
            }

            def entry = [
                timestamp: parts[0]?.trim(),
                freeMemoryKB: memKB,
                cpuLoad5min: parts[2]?.trim()
            ]

            // Parse Java heap and direct memory columns if present
            if (parts.size() >= 6) {
                try { entry.totalJavaKB = parts[3]?.trim() as Integer } catch (Exception e) {}
                try { entry.freeJavaKB = parts[4]?.trim() as Integer } catch (Exception e) {}
                try { entry.directJavaKB = parts[5]?.trim() as Integer } catch (Exception e) {}
            }

            allEntries << entry
            memValues << memKB
        }
    }

    // Summary is computed from ALL entries regardless of limit
    def summary = [totalEntries: allEntries.size()]
    if (memValues) {
        summary.currentMemoryKB = memValues[-1]
        summary.minMemoryKB = memValues.min()
        summary.maxMemoryKB = memValues.max()
        summary.avgMemoryKB = (memValues.sum() / memValues.size()).toInteger()

        if (summary.currentMemoryKB < 50000) {
            summary.memoryWarning = "LOW MEMORY: ${summary.currentMemoryKB}KB free. Consider rebooting or running hub_call_gc."
        }

        // Java heap and direct memory summary from latest entry
        def latest = allEntries[-1]
        if (latest.totalJavaKB != null) summary.totalJavaKB = latest.totalJavaKB
        if (latest.freeJavaKB != null) summary.freeJavaKB = latest.freeJavaKB
        if (latest.directJavaKB != null) {
            summary.directJavaKB = latest.directJavaKB
            // Track direct memory growth (potential NIO buffer leak indicator)
            def directValues = allEntries.findAll { it.directJavaKB != null }.collect { it.directJavaKB }
            if (directValues.size() >= 2) {
                summary.directJavaMinKB = directValues.min()
                summary.directJavaMaxKB = directValues.max()
            }
        }
    }

    // Apply limit — return most recent entries
    def entries = allEntries
    if (limit > 0 && allEntries.size() > limit) {
        entries = allEntries.takeRight(limit)
        summary.truncated = true
        summary.showing = "${entries.size()} of ${allEntries.size()} (most recent)"
    }

    // Cursor pages within the limit-trimmed candidate pool; limit=0 + cursor pages the full ring buffer.
    def cursor = args?.cursor
    def paged = _paginateList(entries, cursor, 100, "hub_get_memory_history")
    def result = [entries: paged.page, summary: summary]
    if (cursor != null) {
        result.total = entries.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    mcpLog("info", "server", "Memory history retrieved: ${paged.page.size()} entries (${allEntries.size()} total)")
    return result
}

def toolForceGarbageCollection(args) {

    // Read free memory before GC
    def beforeKB = null
    try {
        beforeKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim() as Integer
    } catch (Exception e) {
        beforeKB = null
    }

    // Trigger garbage collection
    hubInternalGet("/hub/forceGC")

    // Brief pause to let GC complete
    pauseExecution(1000)

    // Read free memory after GC
    def afterKB = null
    try {
        afterKB = hubInternalGet("/hub/advanced/freeOSMemory")?.trim() as Integer
    } catch (Exception e) {
        afterKB = null
    }

    def result = [
        beforeFreeMemoryKB: beforeKB,
        afterFreeMemoryKB: afterKB,
        timestamp: formatTimestamp(now())
    ]

    if (beforeKB != null && afterKB != null) {
        result.deltaKB = afterKB - beforeKB
        result.memoryReclaimed = result.deltaKB > 0
        result.summary = "GC complete: ${beforeKB}KB → ${afterKB}KB (${result.deltaKB > 0 ? '+' : ''}${result.deltaKB}KB)"
    } else {
        result.summary = "GC triggered but could not read memory values for comparison"
    }

    mcpLog("info", "server", "Forced GC: before=${beforeKB}KB, after=${afterKB}KB")
    return result
}

def toolDeviceHealthCheck(args) {
    def staleHours = args.staleHours ?: 24
    def includeHealthy = args.includeHealthy ?: false
    def cursor = args.cursor
    def pingHosts = (args.pingHosts ?: []) as List
    // ?: treats explicit 0 as absent, so distinguish null from 0 here. Accept Number to keep
    // the friendly "between 1 and 5" message for non-numeric input instead of a cast exception.
    def pingCount
    if (args.pingCount == null) {
        pingCount = 3
    } else if (args.pingCount instanceof Number) {
        pingCount = ((Number) args.pingCount).intValue()
    } else {
        throw new IllegalArgumentException("pingCount must be an integer between 1 and 5 (got ${args.pingCount})")
    }

    if (pingHosts.size() > 5) {
        throw new IllegalArgumentException("pingHosts is limited to 5 entries per call (got ${pingHosts.size()})")
    }
    if (pingCount < 1 || pingCount > 5) {
        throw new IllegalArgumentException("pingCount must be between 1 and 5 (got ${pingCount})")
    }

    def pingResults = pingHosts ? runPingChecks(pingHosts, pingCount) : null

    Map identifyHubFields = null
    if (args?.identifyHub == true) {
        try {
            hubInternalGet("/hub/advanced/blinkLED")
            identifyHubFields = [identifyHubTriggered: true]
        } catch (Exception e) {
            def msg = e.message ?: e.toString()
            identifyHubFields = [identifyHubTriggered: false, identifyHubError: msg]
            mcpLog("warn", "monitoring", "hub_get_device_health identifyHub blinkLED request failed [${e.class.simpleName}]: ${msg}")
        }
    }

    if (!settings.selectedDevices) {
        def emptyResult = [
            message: "No devices selected for MCP access",
            summary: [totalDevices: 0, healthyCount: 0, staleCount: 0, unknownCount: 0]
        ]
        if (pingResults != null) emptyResult.pingResults = pingResults
        if (identifyHubFields != null) emptyResult.putAll(identifyHubFields)
        return emptyResult
    }

    def staleThreshold = now() - (staleHours * 3600000L)

    def healthy = []
    def stale = []
    def unknown = []

    settings.selectedDevices.each { device ->
        try {
            def deviceLabel = device.label ?: device.name ?: "Device ${device.id}"
            def entry = [
                id: device.id.toString(),
                name: deviceLabel
            ]

            def lastActivity = null
            try {
                lastActivity = device.lastActivity
            } catch (MissingPropertyException ignored) {
                // Expected: some device types don't expose lastActivity. Fall through to "never".
            } catch (Exception e) {
                // Unexpected -- a sandbox tightening or device-proxy change rather than the
                // documented MissingPropertyException case. Log so a "why are all my devices
                // unknown" report has a breadcrumb to chase; still fall through to "never".
                mcpLog("debug", "monitoring", "hub_get_device_health could not read lastActivity for device ${device.id}: ${e.class.simpleName}: ${e.message}")
            }

            if (lastActivity) {
                try {
                    entry.lastActivity = lastActivity.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
                    def activityTime = lastActivity.getTime()
                    // `as double`: Groovy decimal literals are BigDecimal, so 0/10.0
                    // renders as "0E+1" for fresh devices -- coerce to a plain double.
                    entry.hoursAgo = (Math.round((now() - activityTime) / 3600000.0 * 10) / 10.0) as double

                    if (activityTime < staleThreshold) {
                        stale << entry
                    } else {
                        healthy << entry
                    }
                } catch (Exception e) {
                    entry.lastActivity = "error: ${e.message}"
                    unknown << entry
                }
            } else {
                entry.lastActivity = "never"
                entry.hoursAgo = null
                unknown << entry
            }
        } catch (Exception e) {
            // Skip device entirely if we can't even get basic info. Log so the failure
            // shows up in hub_get_debug_logs / hub_report_issue; surface errorClass on the
            // entry so an LLM triaging the result can distinguish transient (NPE) from
            // systemic (MissingMethodException) without re-running.
            mcpLog("warn", "monitoring", "hub_get_device_health failed to inspect device ${device?.id}: ${e.class.simpleName}: ${e.message}")
            unknown << [id: device.id?.toString() ?: "unknown", name: "Error: ${e.message}", lastActivity: "error", errorClass: e.class.simpleName]
        }
    }

    // Sort stale by most-stale first
    stale.sort { a, b -> (b.hoursAgo ?: 0) <=> (a.hoursAgo ?: 0) }

    // Only stale is paged; unknown/healthy stay in full so the summary call still resolves in one request.
    def paged = _paginateList(stale, cursor, 100, "hub_get_device_health")

    def result = [
        summary: [
            totalDevices: settings.selectedDevices.size(),
            healthyCount: healthy.size(),
            staleCount: stale.size(),
            unknownCount: unknown.size(),
            staleThresholdHours: staleHours,
            checkedAt: formatTimestamp(now())
        ],
        staleDevices: paged.page,
        unknownDevices: unknown
    ]
    if (cursor != null) {
        result.total = stale.size()
        // staleDevicesInPage distinguishes the page slice from summary.staleCount (the
        // full count) on size-equal hubs.
        result.summary.staleDevicesInPage = paged.page.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    if (includeHealthy) {
        result.healthyDevices = healthy
    }

    if (stale.size() > 0 || unknown.size() > 0) {
        result.recommendation = "Found ${stale.size()} stale and ${unknown.size()} unknown devices. " +
            "Stale devices may have dead batteries, be out of range, or be orphaned/ghost devices. " +
            "Use 'hub_get_device' on individual devices for more details."
    }

    if (pingResults != null) {
        result.pingResults = pingResults
    }

    if (identifyHubFields != null) {
        result.putAll(identifyHubFields)
    }

    mcpLog("info", "monitoring", "Device health check: ${healthy.size()} healthy, ${stale.size()} stale, ${unknown.size()} unknown (threshold: ${staleHours}h)")
    return result
}

def runPingChecks(List rawHosts, Integer count) {
    def results = []
    rawHosts.each { rawHost ->
        if (rawHost == null || !(rawHost instanceof CharSequence)) {
            results << [ipAddress: rawHost, reachable: false, error: "missing or non-string host"]
            return
        }
        def host = rawHost.toString().trim()
        // Range-validated IPv4 dotted-quad. Hostnames are not supported by NetworkUtils.ping.
        if (!(host ==~ /^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$/)) {
            results << [ipAddress: host, reachable: false, error: "not a dotted-quad IPv4 literal (hostnames not supported, pass an IP)"]
            return
        }
        try {
            def pd = hubitat.helper.NetworkUtils.ping(host, count)
            // Explicit null guards (not ?:) so a real platform-reported 0 is preserved.
            def transmitted = (pd?.packetsTransmitted == null ? count : pd.packetsTransmitted) as Integer
            def received = (pd?.packetsReceived == null ? 0 : pd.packetsReceived) as Integer
            results << [
                ipAddress: host,
                reachable: transmitted > 0 && received > 0,
                packetsTransmitted: transmitted,
                packetsReceived: received,
                packetLoss: pd?.packetLoss,
                rttAvg: pd?.rttAvg,
                rttMin: pd?.rttMin,
                rttMax: pd?.rttMax
            ]
        } catch (Exception e) {
            def errorType
            if (e instanceof java.net.UnknownHostException) errorType = "unknown_host"
            else if (e instanceof java.net.SocketException) errorType = "socket"
            else if (e instanceof SecurityException) errorType = "security"
            else errorType = "other"
            mcpLog("warn", "monitoring", "ping failed for ${host} (count=${count}, type=${errorType}): ${e.message}")
            results << [ipAddress: host, reachable: false, errorType: errorType, error: e.message ?: e.toString()]
        }
    }
    return results
}

def toolZwaveRepair(args) {
    requireDestructiveConfirm(args.confirm)

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
        mcpLogError("hub-admin", "Z-Wave repair failed to start", e)
        return [
            success: false,
            error: "Z-Wave repair failed: ${e.message}",
            note: "The Z-Wave repair could not be started. Check Hub Security credentials or try starting it manually from the Hubitat web UI at Settings → Z-Wave Details → Repair."
        ]
    }
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
    // atomicState (not state): the prior in-place mutation of `state` did not reliably persist
    // (`state` only flushes at handler end, and subscribed event handlers run concurrently), so
    // captures and evictions were lost. Read into a local, mutate, then write the whole map back
    // in one assignment -- in-place mutation of a nested atomicState map is not reliably persisted
    // either. This narrows but does not fully close the race: atomicState has no compare-and-swap,
    // so two truly-concurrent saves can still lose one. Each write is durable, though -- strictly
    // better than `state`.
    def stored = atomicState.capturedDeviceStates ?: [:]

    // Add timestamp to the captured state
    def stateEntry = [
        devices: capturedStates,
        timestamp: now(),
        deviceCount: capturedStates.size()
    ]

    def deletedStates = []

    // Check if we need to remove old entries (only if this is a new stateId)
    if (!stored.containsKey(stateId)) {
        while (stored.size() >= getMaxCapturedStates()) {
            // Find and remove the oldest entry
            def oldestId = null
            def oldestTime = Long.MAX_VALUE
            stored.each { id, entry ->
                def entryTime = entry.timestamp ?: 0
                if (entryTime < oldestTime) {
                    oldestTime = entryTime
                    oldestId = id
                }
            }
            if (oldestId) {
                log.warn "Captured states at limit (${getMaxCapturedStates()}): Removing oldest state '${oldestId}' to make room for '${stateId}'"
                deletedStates << oldestId
                stored.remove(oldestId)
            } else {
                break // Safety: avoid infinite loop
            }
        }
    }

    stored[stateId] = stateEntry
    atomicState.capturedDeviceStates = stored
    def totalStored = stored.size()
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
    def entry = atomicState.capturedDeviceStates?.get(stateId)
    // Return the devices array for backward compatibility
    return entry?.devices ?: entry
}

// Helper method to list all captured states with metadata
def listCapturedStates() {
    def stored = atomicState.capturedDeviceStates
    if (!stored) return []

    return stored.collect { stateId, entry ->
        [
            stateId: stateId,
            deviceCount: entry.deviceCount ?: entry.devices?.size() ?: (entry instanceof List ? entry.size() : 0),
            timestamp: entry.timestamp ?: null,
            capturedAt: formatTimestamp(entry.timestamp)
        ]
    }.sort { a, b -> (b.timestamp ?: 0) <=> (a.timestamp ?: 0) } // Sort newest first
}

// Helper method to delete a specific captured state
def deleteCapturedState(stateId) {
    def stored = atomicState.capturedDeviceStates
    if (!stored) {
        return [success: false, message: "No captured states exist"]
    }

    if (!stored.containsKey(stateId)) {
        return [success: false, message: "Captured state '${stateId}' not found"]
    }

    stored.remove(stateId)
    atomicState.capturedDeviceStates = stored
    log.debug "Deleted captured state '${stateId}' (remaining: ${stored.size()})"
    return [success: true, message: "Captured state '${stateId}' deleted", remaining: stored.size()]
}

// Helper method to clear all captured states
def clearAllCapturedStates() {
    def count = atomicState.capturedDeviceStates?.size() ?: 0
    atomicState.capturedDeviceStates = [:]
    log.debug "Cleared all ${count} captured states"
    return [success: true, message: "Cleared ${count} captured state(s)", cleared: count]
}

def toolListCapturedStates(args = null) {
    def states = listCapturedStates()
    def count = states.size()
    def cursor = args?.cursor
    def paged = _paginateList(states, cursor, 50, "hub_list_captured_states")
    def result = [
        capturedStates: paged.page,
        count: paged.page.size(),
        maxLimit: getMaxCapturedStates()
    ]
    if (cursor != null) {
        result.total = count
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    // Add warnings when approaching or at limit (always reported regardless of pagination)
    if (count >= getMaxCapturedStates()) {
        result.warning = "At maximum capacity (${getMaxCapturedStates()}). New captures will delete the oldest entry."
    } else if (count >= getMaxCapturedStates() - 4) {
        result.warning = "Approaching limit: ${count}/${getMaxCapturedStates()} slots used. Consider cleaning up unused captures."
    }

    return result
}

// hub_delete_captured_state: stateId present -> delete that one; omitted -> delete all
// (no-ID = delete all, per the verb vocabulary). Accepts either an args map or a
// raw stateId for backward-compatible internal calls.
def toolDeleteCapturedState(args) {
    def stateId = (args instanceof Map) ? args.stateId : args
    return stateId ? deleteCapturedState(stateId) : clearAllCapturedStates()
}

def _getAllToolDefinitions_partDiagnostics() {
    return [
        [
            name: "hub_get_logs",
            description: "Get Hubitat system logs, most recent first. Filter pipeline (in order): scope (deviceId/appId, server-side) -> level -> source -> pattern -> patterns -> time window (since/until) -> limit. Default 100 entries, max 500. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    level: [type: "string", description: "Filter by log level: trace, debug, info, warn, error. Default: all levels.", enum: ["trace", "debug", "info", "warn", "error"]],
                    source: [type: "string", description: "Filter by source/app name (case-insensitive substring match against the log entry)"],
                    deviceId: [type: "string", description: "Scope to a single device's log entries (server-side filter, mutually exclusive with appId)"],
                    appId: [type: "string", description: "Scope to a single app's log entries (server-side filter, mutually exclusive with deviceId)"],
                    limit: [type: "integer", description: "Max entries to return. Default: 100, max: 500.", default: 100],
                    pattern: [type: "string", description: "Case-insensitive regex applied to the log message field only -- use source for app/device-name substring matching.[[FLAT_TRIM]] Entry is kept when it matches. Compiled once before the loop. Throws on invalid regex syntax. Note: pathological regex like (.*)*  may hang the matcher; prefer simple alternation (error|fail) or anchored prefixes.[[/FLAT_TRIM]]"],
                    patterns: [type: "array", items: [type: "string"], description: "Multiple regex patterns, same matching rules and caveats as `pattern`[[FLAT_TRIM]] (message-field only; throws on invalid regex)[[/FLAT_TRIM]]. Combine via patternMode ('any'=OR, default / 'all'=AND).[[FLAT_TRIM]] Compatible with `pattern` (both apply).[[/FLAT_TRIM]]"],
                    patternMode: [type: "string", description: "How patterns array is combined: 'any' (default) = OR; 'all' = AND.[[FLAT_TRIM]] 'any' keeps an entry if any pattern matches; 'all' only if every pattern matches. Case-insensitive ('ANY' and 'any' both work).[[/FLAT_TRIM]]", enum: ["any", "all"]],
                    since: [type: "string", description: "Return only entries at or after this time. Accepts ISO-8601 timestamp (e.g. '2024-01-15T10:30:00Z') or relative offset (e.g. '30m', '2h', '1d', '7d').[[FLAT_TRIM]] Relative offset is subtracted from now.[[/FLAT_TRIM]] Max relative offset: 30d[[FLAT_TRIM]] (throws if exceeded -- use ISO-8601 for longer ranges)[[/FLAT_TRIM]].[[FLAT_TRIM]] Timestamps without a TZ marker (e.g. '2024-01-15T10:30:00' or '2024-01-15 10:30:00.000') are parsed as UTC. Use '0m' / '0d' as a degenerate since to filter out everything older than now -- useful for testing harnesses but rarely otherwise.[[/FLAT_TRIM]]"],
                    until: [type: "string", description: "Return only entries at or before this time. Same format as since[[FLAT_TRIM]] (relative offsets are subtracted from now, same as since; max 30d)[[/FLAT_TRIM]]. Default: now (no upper bound).[[FLAT_TRIM]] Use since='2h', until='1h' to mean '1 to 2 hours ago'.[[/FLAT_TRIM]]"],
                    cursor: [type: "string", description: "Opt-in pagination cursor.[[FLAT_TRIM]] Filters + limit apply first; cursor pages within the filtered result.[[/FLAT_TRIM]] Pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    logs: [type: "array", description: "Log entries, most recent first", items: [type: "object", properties: [
                        name: [type: "string", description: "Source name"],
                        level: [type: "string", description: "Log level"],
                        message: [type: "string", description: "Log message"],
                        time: [type: "string", description: "Entry timestamp"],
                        type: [type: "string", description: "Entry type"]
                    ]]],
                    count: [type: "integer", description: "Entries on this page"],
                    totalParsed: [type: "integer", description: "Total entries parsed from the hub"],
                    appliedLimit: [type: "integer", description: "Limit applied before pagination"],
                    total: [type: "integer", description: "Filtered total; present in cursor mode"],
                    nextCursor: [type: "string", description: "Present when more results remain"],
                    truncated: [type: "boolean", description: "Present when messages were trimmed for size"],
                    note: [type: "string", description: "Present when truncated"],
                    filteredOut: [type: "integer", description: "Entries excluded by active filters"],
                    appliedFilters: [type: "object", description: "Echo of active filter args"],
                    timeFilterUnparseable: [type: "integer", description: "Entries kept despite unparseable timestamps"],
                    benignRmNoiseCount: [type: "integer", description: "Count of returned entries that are known-benign RM-internal noise (non-fatal, not an MCP bug); present only when >0"],
                    benignRmNoiseNote: [type: "string", description: "Explanation of the benign RM-internal noise; present only when benignRmNoiseCount>0"]
                ],
                required: ["logs", "count"]
            ]
        ],
        // ==================== MONITORING TOOLS ====================
        [
            name: "hub_get_performance_stats",
            description: "Get device and/or app performance stats from the hub's logs page. Shows method call counts, % busy, state size, events, states, hub actions, pending events per device/app. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", description: "Which stats to return: device, app, or both. Default: device.", enum: ["device", "app", "both"], default: "device"],
                    sortBy: [type: "string", description: "Sort results by field. Default: pct (% busy).", enum: ["pct", "count", "stateSize", "totalMs", "name"], default: "pct"],
                    limit: [type: "integer", description: "Max entries to return. Default: 20, 0 for all.", default: 20]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    uptime: [description: "Hub uptime"],
                    deviceSummary: [type: "object", description: "Device totals; present for type device/both", properties: [
                        totalRuntime: [description: "Total device runtime"],
                        pctOfUptime: [description: "Device % of uptime"],
                        deviceCount: [type: "integer", description: "Devices reported"]
                    ]],
                    deviceStats: [type: "array", description: "Per-device stats; present for type device/both", items: [type: "object", properties: [
                        id: [description: "Device ID"],
                        name: [type: "string", description: "Device name"],
                        count: [description: "Method call count"],
                        pctBusy: [description: "% busy"],
                        stateSize: [description: "State size"],
                        totalMs: [description: "Total ms"]
                    ]]],
                    appSummary: [type: "object", description: "App totals; present for type app/both", properties: [
                        totalRuntime: [description: "Total app runtime"],
                        pctOfUptime: [description: "App % of uptime"],
                        appCount: [type: "integer", description: "Apps reported"]
                    ]],
                    appStats: [type: "array", description: "Per-app stats; present for type app/both", items: [type: "object"]],
                    note: [type: "string", description: "Present when limit=0"]
                ],
                required: ["uptime"]
            ]
        ],
        [
            name: "hub_get_jobs",
            description: "Get scheduled jobs, running jobs, and hub actions from the hub's logs page. Shows what's scheduled to run and when. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [:]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    uptime: [description: "Hub uptime"],
                    scheduledJobs: [type: "object", description: "Scheduled jobs", properties: [
                        count: [type: "integer", description: "Number of scheduled jobs"],
                        jobs: [type: "array", items: [type: "object", properties: [
                            id: [description: "Job ID"],
                            name: [type: "string", description: "Job name"],
                            recurring: [description: "Whether the job recurs"],
                            method: [type: "string", description: "Method invoked"],
                            nextRun: [description: "Next scheduled run"]
                        ]]]
                    ]],
                    runningJobs: [type: "object", description: "Currently running jobs", properties: [
                        count: [type: "integer", description: "Number of running jobs"],
                        jobs: [type: "array", items: [type: "object"]]
                    ]],
                    hubActions: [type: "object", description: "Pending hub actions", properties: [
                        count: [type: "integer", description: "Number of hub actions"],
                        actions: [type: "array", items: [type: "object"]]
                    ]]
                ],
                required: ["scheduledJobs", "runningJobs", "hubActions"]
            ]
        ],
        [
            name: "hub_get_metrics",
            description: "Retrieve hub metrics (memory, temp, DB size) with CSV trend history. The trend reflects ONLY previously-recorded snapshots — the hub does not auto-sample, so it can be sparse or stale (and resets if the CSV is cleared) unless recordSnapshot=true is called periodically. Read-only by default; pass recordSnapshot=true to ALSO append the current snapshot to the performance-history CSV in the hub File Manager (the only write side-effect).[[FLAT_TRIM]] Also folds in the hub's own health alerts under healthAlerts (radio offline, backup failures, low memory, DB bloat, weak mesh, safeMode) from /hub2/hubData.[[/FLAT_TRIM]] Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    recordSnapshot: [type: "boolean", description: "If true, also append this snapshot to the performance-history CSV in the hub File Manager (a write side-effect). Default: false (read-only).", default: false],
                    trendPoints: [type: "integer", description: "Number of recent historical data points to include. Default: 10, max: 50.", default: 10]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    current: [type: "object", description: "Current snapshot", properties: [
                        timestamp: [type: "string", description: "Snapshot time"],
                        timestampEpoch: [type: "integer", description: "Snapshot time in epoch millis"],
                        freeMemoryKB: [description: "Free OS memory (KB)"],
                        internalTempC: [description: "Internal temperature (C)"],
                        databaseSizeKB: [description: "Database size (KB)"],
                        uptimeSeconds: [description: "Hub uptime in seconds"],
                        uptimeFormatted: [type: "string", description: "Human-readable uptime"]
                    ]],
                    trends: [type: "array", description: "Recent historical data points", items: [type: "object", properties: [
                        timestamp: [type: "string", description: "Point time"],
                        freeMemoryKB: [description: "Free OS memory (KB)"],
                        internalTempC: [description: "Internal temperature (C)"],
                        databaseSizeKB: [description: "Database size (KB)"],
                        uptimeSeconds: [description: "Uptime in seconds"]
                    ]]],
                    healthAlerts: [type: "object", description: "The hub's own health alerts from /hub2/hubData -- {safeMode, active (list of currently-firing alert flags like hubLowMemory/hubLargeDatabase/zwaveOffline/localBackupFailed/weakZigbee), details (full alert flag map + the hub's message strings)}. Complements the locally-derived warnings on `current`. null if /hub2/hubData was unreadable."],
                    trendPointsAvailable: [type: "integer", description: "Total history rows available"],
                    historyFile: [type: "string", description: "CSV history filename in File Manager"]
                ],
                required: ["current", "trends"]
            ]
        ],
        [
            name: "hub_get_memory_history",
            description: "Get the hub's free-memory and CPU-load history (the platform's own timestamped ring buffer, each entry with freeMemoryKB and cpuLoad5min). Use to diagnose memory leaks or load trends over time. For a single current snapshot plus temp/DB-size, use hub_get_metrics instead. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    limit: [type: "integer", description: "Max entries to return (most recent). Default: 100, 0 for all. Hub may have thousands of entries.", default: 100],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Pages within the limit-filtered entries (limit=0 + cursor pages the full ring buffer). Pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    entries: [type: "array", description: "Memory history entries", items: [type: "object", properties: [
                        timestamp: [type: "string", description: "Entry timestamp"],
                        freeMemoryKB: [type: "integer", description: "Free OS memory (KB)"],
                        cpuLoad5min: [type: "string", description: "5-minute CPU load average"],
                        totalJavaKB: [type: "integer", description: "Total Java heap (KB), when present"],
                        freeJavaKB: [type: "integer", description: "Free Java heap (KB), when present"],
                        directJavaKB: [type: "integer", description: "Direct Java memory (KB), when present"]
                    ]]],
                    summary: [type: "object", description: "Aggregate stats over all entries", properties: [
                        totalEntries: [type: "integer", description: "Total entries available"],
                        currentMemoryKB: [type: "integer", description: "Most recent free memory (KB)"],
                        minMemoryKB: [type: "integer", description: "Minimum free memory (KB)"],
                        maxMemoryKB: [type: "integer", description: "Maximum free memory (KB)"],
                        avgMemoryKB: [type: "integer", description: "Average free memory (KB)"],
                        memoryWarning: [type: "string", description: "Present when memory is low"],
                        truncated: [type: "boolean", description: "Present when entries were limited"],
                        message: [type: "string", description: "Present when no history available"]
                    ]],
                    total: [type: "integer", description: "Candidate entry count; present in cursor mode"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["entries", "summary"]
            ]
        ],
        [
            name: "hub_get_device_health",
            description: "Check device staleness and (optionally) ICMP-ping arbitrary hosts. Stale check covers only devices authorized for MCP access (the app's selected device list) with no activity in staleHours; MCP-managed virtual/child devices (from hub_create_virtual_device) are a SEPARATE population and are NOT included here — list those via hub_list_devices(filter='virtual'). Ping check uses hubitat.helper.NetworkUtils.ping() to verify network reachability of any IPs in pingHosts (router, NAS, server, LAN-attached devices). Either or both may be used in a single call. Pass cursor (opaque string from a prior nextCursor) to page the staleDevices list at 100 per page when the full response would be too large.",
            inputSchema: [
                type: "object",
                properties: [
                    staleHours: [type: "integer", description: "Flag devices with no activity in this many hours. Default: 24.", default: 24],
                    includeHealthy: [type: "boolean", description: "Include healthy devices in the response (can be large). Default: false.", default: false],
                    pingHosts: [type: "array", items: [type: "string"], description: "Optional IPv4 addresses to ICMP-ping (max 5 per call). Each entry is sent through hubitat.helper.NetworkUtils.ping() and reported under pingResults with reachable/rttAvg/packetLoss. Hostnames are not resolved — pass IPs only."],
                    pingCount: [type: "integer", description: "Packets to send per host (1-5). Default: 3.", default: 3],
                    identifyHub: [type: "boolean", description: "Blink hub LED to identify hub. Default: false.", default: false],
                    cursor: [type: "string", description: "Opt-in pagination cursor for the staleDevices array. Omit to get all stale devices in one response (subject to the universal response-size guard). Pass nextCursor from a prior call to fetch the next page (page size 100). unknownDevices and healthyDevices are always returned in full alongside the page."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    summary: [type: "object", description: "Health counts", properties: [
                        totalDevices: [type: "integer", description: "Devices checked"],
                        healthyCount: [type: "integer", description: "Healthy devices"],
                        staleCount: [type: "integer", description: "Stale devices"],
                        unknownCount: [type: "integer", description: "Devices with no/unreadable activity"],
                        staleThresholdHours: [type: "integer", description: "Staleness threshold used"],
                        checkedAt: [type: "string", description: "Check timestamp"],
                        staleDevicesInPage: [type: "integer", description: "Stale devices on this page; present in cursor mode"]
                    ]],
                    staleDevices: [type: "array", description: "Stale device entries (paginated)", items: [type: "object", properties: [
                        id: [type: "string", description: "Device ID"],
                        name: [type: "string", description: "Device label"],
                        lastActivity: [type: "string", description: "Last-activity ISO timestamp or 'never'"],
                        hoursAgo: [type: "number", description: "Hours since last activity, or null"]
                    ]]],
                    unknownDevices: [type: "array", description: "Devices with no readable activity", items: [type: "object"]],
                    healthyDevices: [type: "array", description: "Present when includeHealthy=true", items: [type: "object"]],
                    pingResults: [type: "array", description: "Present when pingHosts supplied", items: [type: "object", properties: [
                        ipAddress: [type: "string", description: "Target IP"],
                        reachable: [type: "boolean", description: "Whether the host responded"],
                        packetLoss: [description: "Packet-loss percentage"],
                        rttAvg: [description: "Average round-trip time"]
                    ]]],
                    recommendation: [type: "string", description: "Present when stale/unknown devices exist"],
                    total: [type: "integer", description: "Total stale devices; present in cursor mode"],
                    nextCursor: [type: "string", description: "Pagination cursor; present when more stale devices remain"],
                    identifyHubTriggered: [type: "boolean", description: "Present when identifyHub=true: LED blink result"],
                    identifyHubError: [type: "string", description: "Present when the LED-blink request failed"],
                    message: [type: "string", description: "Present when no devices are selected"]
                ],
                required: ["summary"]
            ]
        ],
        [
            name: "hub_get_radio_details",
            description: "Get Z-Wave and/or Zigbee radio info (firmware, home/PAN ID, channel, device nodes); omit radio to return both. include_topology=true adds the read-only mesh route map for diagnosing weak or unresponsive nodes. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    radio: [type: "string", enum: ["zwave", "zigbee"], description: "Which radio to query. Omit to return both."],
                    include_topology: [type: "boolean", description: "Also include the mesh route/topology map (Z-Wave nodes+connectors and raw route table; Zigbee children+neighbors+routes). Read-only. Default false."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    zwave: [type: "object", description: "Z-Wave details; present when both radios requested"],
                    zigbee: [type: "object", description: "Zigbee details; present when both radios requested"],
                    zwaveVersion: [description: "Z-Wave SDK version; present for radio='zwave'"],
                    zwaveData: [type: "object", description: "Parsed Z-Wave info; present for radio='zwave'"],
                    zigbeeChannel: [description: "Zigbee channel; present for radio='zigbee'"],
                    zigbeeId: [description: "Zigbee ID; present for radio='zigbee'"],
                    zigbeeData: [type: "object", description: "Parsed Zigbee info; present for radio='zigbee'"],
                    topology: [type: "object", description: "Mesh route/topology; present only when include_topology=true. {endpoint, routes (parsed node/route graph), zwaveTopologyTable (raw, Z-Wave only), error?}"],
                    source: [type: "string", description: "Where data came from: hub_api, hub_api_raw, or sdk_only"],
                    endpoint: [type: "string", description: "Internal API endpoint used"],
                    rawResponse: [type: "string", description: "Raw body when response was not JSON"],
                    note: [type: "string", description: "Status note when extended info unavailable"]
                ]
            ]
        ],
        [
            name: "hub_call_gc",
            description: "Force JVM garbage collection to reclaim memory. Returns before/after free memory and delta. Non-destructive but may cause a brief pause. Requires the Write master.",
            inputSchema: [
                type: "object",
                properties: [:]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    beforeFreeMemoryKB: [type: "integer", description: "Free memory before GC (KB), or null"],
                    afterFreeMemoryKB: [type: "integer", description: "Free memory after GC (KB), or null"],
                    timestamp: [type: "string", description: "When GC ran"],
                    deltaKB: [type: "integer", description: "Memory delta (KB); present when both readings succeeded"],
                    memoryReclaimed: [type: "boolean", description: "Whether free memory increased; present when both readings succeeded"],
                    summary: [type: "string", description: "Human-readable GC result"]
                ],
                required: ["timestamp", "summary"]
            ]
        ],
        [
            name: "hub_call_zwave_repair",
            description: """⚠️ DISRUPTIVE: Z-Wave network repair. All Z-Wave devices may become unresponsive for 5-30 minutes.

WARNING: During repair, Z-Wave automations will be unreliable. Locks, garage doors, and security devices on Z-Wave may not respond. Schedule during off-peak hours when critical Z-Wave devices are not actively needed.

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user about duration/impact and which devices will be affected 3) Get explicit confirmation 4) Set confirm=true
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the Z-Wave repair."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the repair was started"],
                    message: [type: "string", description: "Human-readable result"],
                    duration: [type: "string", description: "Expected repair duration"],
                    lastBackup: [type: "string", description: "Formatted timestamp of last backup"],
                    warning: [type: "string", description: "Disruption warning during repair"],
                    note: [type: "string", description: "Where to check repair progress"],
                    response: [type: "string", description: "Truncated hub response body"]
                ],
                required: ["success"]
            ]
        ],
        // Captured State Management
        [
            name: "hub_list_captured_states",
            description: "List saved device-state snapshots (point-in-time captures of device attributes used to restore or compare state later). Returns each entry's stateId for use with hub_delete_captured_state. Storage limit configurable (default 20); oldest auto-deleted when full.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    capturedStates: [type: "array", description: "Captured state entries", items: [type: "object"]],
                    count: [type: "integer", description: "Entries on this page"],
                    maxLimit: [type: "integer", description: "Max captured states retained"],
                    total: [type: "integer", description: "Total entries; present in cursor mode"],
                    nextCursor: [type: "string", description: "Present when more results remain"],
                    warning: [type: "string", description: "Present at/near capacity"]
                ],
                required: ["capturedStates", "count"]
            ]
        ],
        [
            name: "hub_delete_captured_state",
            description: "Delete a saved device-state snapshot by its stateId, OR delete ALL captured states when stateId is omitted. Get stateIds from hub_list_captured_states. Cannot be undone; use the all-delete (omitted stateId) with caution.",
            inputSchema: [
                type: "object",
                properties: [
                    stateId: [type: "string", description: "The ID of the captured state to delete. Omit to delete ALL captured states."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the delete succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    remaining: [type: "integer", description: "States remaining; present on single delete"],
                    cleared: [type: "integer", description: "States cleared; present on delete-all"]
                ],
                required: ["success", "message"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partDiagnostics() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Captured states (read)
        "hub_list_captured_states",
        // Diagnostics + logs (read)
        "hub_get_logs", "hub_get_performance_stats", "hub_get_jobs", "hub_get_memory_history", "hub_get_radio_details",
        // hub_get_metrics is read by default (recordSnapshot defaults false;
        // pass recordSnapshot=true to also persist a CSV snapshot to File Manager).
        "hub_get_metrics",
        // hub_get_device_health has an optional identifyHub LED blink, but its
        // primary mode is staleness + ICMP-ping observation; treating as read
        // matches user expectation for a "health check" tool.
        "hub_get_device_health"
    ]
}

def _idempotentWriteToolNames_partDiagnostics() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Diagnostics
        "hub_delete_captured_state"
    ]
}

def _openWorldToolNames_partDiagnostics() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        // pingHosts sends caller-directed ICMP to ANY routable IPv4 (the tool's
        // own description says "arbitrary hosts"), which can leave the LAN.
        "hub_get_device_health"
    ]
}

def _toolDisplayMeta_partDiagnostics() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Diagnostics + logs
        hub_get_logs: [title: "Get Hub Logs", summary: "Hub log entries with level, source, regex, and time-window filters."],
        hub_get_performance_stats: [title: "Get Performance Stats", summary: "Device and app performance statistics."],
        hub_get_jobs: [title: "Get Scheduled Jobs", summary: "Scheduled jobs, running jobs, and hub actions."],
        hub_get_metrics: [title: "Get Hub Metrics", summary: "Hub metrics with CSV trend history."],
        hub_get_memory_history: [title: "Get Memory History", summary: "Free-memory and CPU-load history with summary stats."],
        hub_call_gc: [title: "Force Garbage Collection", summary: "Force JVM garbage collection and report freed memory."],
        hub_get_device_health: [title: "Get Device Health", summary: "Find stale devices, ICMP-ping LAN hosts, and optionally blink the hub identify LED."],
        hub_get_radio_details: [title: "Get Radio Details", summary: "Z-Wave and Zigbee radio details and device tables."],
        hub_call_zwave_repair: [title: "Run Z-Wave Repair", summary: "Start a Z-Wave network repair (5-30 minutes)."],
        hub_list_captured_states: [title: "List Captured States", summary: "List saved device state snapshots."],
        hub_delete_captured_state: [title: "Delete Captured State", summary: "Delete one or all captured device state snapshots."]
    ]
}
