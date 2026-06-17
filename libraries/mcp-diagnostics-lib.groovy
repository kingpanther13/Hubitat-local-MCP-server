library(name: "McpDiagnosticsLib", namespace: "mcp", author: "kingpanther13", description: "Diagnostics + maintenance tool implementations (hub logs/performance/jobs/metrics/memory/radio/device-health/GC/Z-Wave repair/captured states) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// Radio-details dispatch helper. Extracted from the executeTool switch so the
// case body is a plain method call: a bare `{ ... }` block right after a
// `case X:` label is rejected by the hub's Groovy parser (ambiguous
// parameterless-closure vs open-block) even though hubitat_ci's parser accepts it.
def toolGetRadioDetails(args) {
    // Normalize case so clients that capitalize the arg (e.g. "Matter") still dispatch.
    def radio = args.radio?.toString()?.toLowerCase()
    if (radio != null && !(radio in ["zwave", "zigbee", "matter"]))
        throw new IllegalArgumentException("radio must be 'zwave', 'zigbee', or 'matter' (or omit for both Z-Wave and Zigbee)")
    def result
    if (radio == "zwave") result = toolGetZwaveDetails(args)
    else if (radio == "zigbee") result = toolGetZigbeeDetails(args)
    else if (radio == "matter") result = toolGetMatterDetails(args)
    // OMIT (both-shape) is deliberately Z-Wave + Zigbee only -- Matter is opt-in
    // via radio="matter" so existing callers that omit radio keep their shape.
    else result = [zwave: toolGetZwaveDetails(args), zigbee: toolGetZigbeeDetails(args)]
    _attachRadioIncludes(result, args)
    return result
}

// Attach the opt-in read-only include blocks to a hub_get_radio_details result.
// Each include is a separate hub GET attached under a named key; all read-only,
// so none touch the radio-mutation guardrail. radio scopes which radio-specific
// includes are relevant, but the cross-radio ones (node_id, include_status with
// its mixed pollers) always attach when requested so a both-shape call still sees
// them. Errors are recorded per-include rather than thrown -- an absent radio or
// older firmware should not fail the whole read.
private void _attachRadioIncludes(result, args) {
    if (!(result instanceof Map)) return

    // Per-node state, radio-aware: Matter -> per-node commissioning poller; otherwise
    // the Z-Wave node-state read (plain text; "Done" when idle). Encode the id.
    def radio = args?.radio?.toString()?.toLowerCase()
    def nodeId = args?.node_id?.toString()?.trim()
    if (nodeId) {
        def encoded = java.net.URLEncoder.encode(nodeId, 'UTF-8')
        if (radio == "matter") {
            result.matterPairStatus = _radioGetSafe("/hub/matterPairDeviceStatus?nodeId=${encoded}")
        } else {
            result.nodeState = _radioGetSafe("/hub/zwave2/getNodeState?node=${encoded}")
        }
    }

    // Lifecycle status pollers -- the in-flight progress reads for the hub_call_zwave
    // operations plus Zigbee network state. (Matter commissioning status is per-node:
    // hub_get_radio_details(radio='matter', node_id=N).)
    if (args?.include_status) {
        result.status = [
            zwaveRepair: _radioGetSafe("/hub/zwaveRepair2Status"),
            zwaveRepairRunning: _radioGetSafe("/hub/checkZwaveRepairRunning"),
            zwaveExclude: _radioGetSafe("/hub/zwaveExclude/status"),
            zwaveJoinDiscovery: _radioGetSafe("/hub/searchZwaveDevices"),
            zwaveAntennaTest: _radioGetSafe("/hub/zwave2/antennaTestProgress"),
            zwaveNodeReplace: [
                status: _radioGetSafe("/hub/zwave/nodeReplace/status"),
                info: _radioGetSafe("/hub/zwave/nodeReplace/info")
            ],
            zigbee: _radioGetSafe("/hub/zigbeeInfo/status")
        ]
    }

    // Matter chip-tool logs ({text}, ANSI).
    if (args?.include_logs) result.matterLogs = _radioGetSafe("/hub/matterLogs/json")

    // Zigbee channel energy-scan results.
    if (args?.include_channel_scan) result.channelScan = _radioGetSafe("/hub/zigbeeChannelScanJson")

    // SmartStart provisioning entries (cache-bust the list like the UI does).
    if (args?.include_smartstart) result.smartStart = _radioGetSafe("/mobileapi/zwave/smartstart/list?t=${now()}")

    // Firmware-eligible Z-Wave devices + available files.
    if (args?.include_firmware) {
        result.firmware = [
            devices: _radioGetSafe("/hub/zwave/deviceFirmware/devices"),
            files: _radioGetSafe("/hub/zwave/deviceFirmware/files")
        ]
    }
}

// Shared radio GET: authenticated internal GET, parsed as JSON when the body is
// JSON, returned as a trimmed raw string otherwise (several radio endpoints return
// plain text -- getNodeState, the topology table). THROWS on a hub fault (4xx/5xx/
// timeout) so a WRITE path's existing catch reports success:false rather than a
// fabricated success:true with the error buried in the response. Resilient READS
// (the hub_get_radio_details includes) use _radioGetSafe instead.
private _radioGet(String path) {
    def txt = hubInternalGet(path)
    if (!txt) return null
    try { return new groovy.json.JsonSlurper().parseText(txt) }
    catch (Exception parseErr) { return txt.take(8000) }
}

// Non-throwing read variant: a hub fault becomes an {error} map instead of
// propagating, so one bad include (an absent radio or older firmware) does not
// fail the whole hub_get_radio_details read -- the miss stays visible per-include.
private _radioGetSafe(String path) {
    try { return _radioGet(path) }
    catch (Exception e) {
        mcpLog("debug", "hub-admin", "_radioGetSafe ${path} failed: ${e.message}")
        return [error: "Failed to fetch ${path}: ${e.message}"]
    }
}

// Shared radio POST. body=null -> bare POST (GET-style fetch endpoints accept this);
// a Map -> form-urlencoded (the zwaveNodeId maintenance ops); a String -> raw JSON
// body (the securityKeys/securityCode/nodeReplace/deviceFirmware/smartstart-delete
// endpoints). Returns the parsed-or-raw response body; rethrows so the caller's
// structured error path owns the failure shape.
private _radioPost(String path, body = null) {
    String txt
    if (body == null) {
        txt = hubInternalPost(path)
    } else if (body instanceof Map) {
        txt = hubInternalPostForm(path, body)?.data
    } else {
        // String -> JSON body. hubInternalPostJson already parses JSON for us.
        return hubInternalPostJson(path, body.toString())
    }
    if (!txt) return null
    try { return new groovy.json.JsonSlurper().parseText(txt) }
    catch (Exception parseErr) { return txt.take(2000) }
}

def toolGetMatterDetails(args) {

    def result = [:]

    // Matter fabric + commissioned-device details via internal API. The hub's
    // Matter controller exposes /hub/matterDetails/json on firmware that supports
    // a Matter radio (C-8 / C-8 Pro); older hubs / non-Matter models return nothing.
    def endpoint = "/hub/matterDetails/json"
    def matterSuccess = false
    def matterFault = null
    try {
        def responseText = hubInternalGet(endpoint)
        if (responseText) {
            try {
                result.matterData = new groovy.json.JsonSlurper().parseText(responseText)
                result.source = "hub_api"
                result.endpoint = endpoint
                matterSuccess = true
            } catch (Exception parseErr) {
                result.rawResponse = responseText?.take(3000)
                result.source = "hub_api_raw"
                result.endpoint = endpoint
                result.note = "Response was not JSON format"
                matterSuccess = true
            }
        }
    } catch (Exception e) {
        // A thrown request (timeout/500/auth) is a genuine fault, NOT the benign
        // "no Matter radio" signal (which is an empty 2xx). Log at warn so a real
        // C-8 fault is visible at the default log level, and tag the result below
        // so the caller can tell a fault apart from absent hardware.
        matterFault = e.message
        mcpLog("warn", "hub-admin", "Matter endpoint ${endpoint} failed: ${matterFault}")
    }

    if (!matterSuccess) {
        result.source = "sdk_only"
        if (matterFault) {
            result.error = "Matter query failed: ${matterFault}"
            result.note = "The Matter endpoint returned an error rather than the benign 'no Matter radio' signal. On a C-8/C-8 Pro this is a transient/connectivity fault -- retry."
        } else {
            result.note = "Matter details unavailable. Matter requires a Hubitat C-8 or C-8 Pro on supported firmware with the Matter integration enabled."
        }
    }

    mcpLog("info", "hub-admin", "Retrieved Matter details")
    return result
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
            // The raw route table is optional context on top of the JSON route info above, but
            // record the miss so an absent table can't be mistaken for an empty one.
            topo.zwaveTopologyTableError = "Failed to fetch /hub/zwaveTopology: ${e.message}"
            mcpLog("debug", "hub-admin", "_fetchRadioTopology zwaveTopology miss: ${e.message}")
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

    // Optional WAN/route network diagnostics, both read-only (GET, no hub mutation).
    Map tracerouteResult = null
    if (args?.traceroute != null) {
        def host = args.traceroute.toString().trim()
        // Same dotted-quad IPv4 validation runPingChecks uses; hostnames are rejected
        // (the hub's traceroute endpoint takes a literal IPv4 in the path).
        if (!(host ==~ /^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$/)) {
            throw new IllegalArgumentException("traceroute must be a dotted-quad IPv4 literal (hostnames not supported, pass an IP), got '${host}'")
        }
        def endpoint = "/hub/networkTest/traceroute/${host}"
        tracerouteResult = [host: host, endpoint: endpoint]
        try {
            def txt = hubInternalGet(endpoint, [:], 30)
            tracerouteResult.output = txt?.take(8000)
        } catch (Exception e) {
            tracerouteResult.error = "traceroute failed: ${e.message}"
            mcpLog("warn", "monitoring", "hub_get_device_health traceroute to ${host} failed: ${e.message}")
        }
    }

    Map speedtestResult = null
    if (args?.speedtest == true) {
        def endpoint = "/hub/networkTest/speedtest"
        speedtestResult = [endpoint: endpoint]
        try {
            // Synchronous WAN download test (~10 MB from a fixed Hubitat S3 URL). Slow links are
            // exactly what's being diagnosed, so allow 90s -- 10 MB under ~1 Mbps exceeds the 30s default.
            def txt = hubInternalGet(endpoint, [:], 90)
            speedtestResult.output = txt?.take(8000)
        } catch (Exception e) {
            speedtestResult.error = "speedtest failed: ${e.message}"
            mcpLog("warn", "monitoring", "hub_get_device_health speedtest failed: ${e.message}")
        }
    }

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
        if (tracerouteResult != null) emptyResult.traceroute = tracerouteResult
        if (speedtestResult != null) emptyResult.speedtest = speedtestResult
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

    if (tracerouteResult != null) {
        result.traceroute = tracerouteResult
    }

    if (speedtestResult != null) {
        result.speedtest = speedtestResult
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

// hub_set_zwave: enable/disable the Z-Wave radio, or set region + long-range
// channel. Idempotent. Disable is confirm-gated (radio off strands every Z-Wave
// device). Config updates preserve the radio's other current settings (enabled,
// region, secureJoin, longRangeChannel) -- the /hub/zwaveDetails/update endpoint
// is a full-replacement GET (takes the complete param set, not a partial patch),
// so we read current state first and only override what changed.
def toolSetZwave(args) {
    def hasEnabled = args.containsKey("enabled")
    def hasConfig = (args.region != null || args.long_range_channel != null)
    if (!hasEnabled && !hasConfig) {
        throw new IllegalArgumentException("Specify enabled (true/false) and/or region + long_range_channel.")
    }

    if (hasEnabled) {
        boolean enabled = (args.enabled == true)
        if (!enabled) requireDestructiveConfirm(args.confirm)
        try {
            def resp = _radioGet("/hub/zwave/enable/${enabled}")
            mcpLog("info", "hub-admin", "Z-Wave radio ${enabled ? 'enabled' : 'disabled'} via MCP")
            return [success: true, radio: "zwave", enabled: enabled,
                    message: "Z-Wave radio ${enabled ? 'enabled' : 'disabled'}.",
                    note: "Disabling the radio may require a hub reboot to fully take effect. Verify with hub_get_radio_details(radio='zwave').",
                    response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Z-Wave enable/disable failed", e)
            return [success: false, error: "Z-Wave enable/disable failed: ${e.message}", note: "Check Hub Security credentials."]
        }
    }

    // Config update: merge requested changes over current radio settings.
    try {
        def current = _radioGet("/hub/zwaveDetails/json")
        def cur = (current instanceof Map) ? current : [:]
        def params = [:]
        params.enabled = (cur.enabled != null) ? cur.enabled : true
        params.region = (args.region != null) ? args.region : cur.region
        params.secureJoin = (cur.secureJoin != null) ? cur.secureJoin : 0
        if (args.long_range_channel != null) {
            params.longRangeChannel = args.long_range_channel
        } else if (cur.longRangeChannel != null) {
            params.longRangeChannel = cur.longRangeChannel
        }
        // Drop null params so v.toString() can't NPE (e.g. region-less hub + region-omitting call).
        def query = params.findAll { k, v -> v != null }.collect { k, v -> "${k}=${java.net.URLEncoder.encode(v.toString(), 'UTF-8')}" }.join("&")
        def resp = _radioGet("/hub/zwaveDetails/update?${query}")
        mcpLog("info", "hub-admin", "Z-Wave region/long-range config updated via MCP")
        return [success: true, radio: "zwave", region: params.region, longRangeChannel: params.longRangeChannel,
                message: "Z-Wave radio configuration updated.", response: resp]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Z-Wave config update failed", e)
        return [success: false, error: "Z-Wave config update failed: ${e.message}", note: "Check region/channel values and Hub Security credentials."]
    }
}

// hub_set_zigbee: enable/disable the Zigbee radio, set channel + power, set radio
// settings (rebuild-on-reboot / inactive-device ping), or toggle keep-alive ping
// for one device. Idempotent. Disable is confirm-gated.
def toolSetZigbee(args) {
    def hasEnabled = args.containsKey("enabled")
    def hasChannel = (args.channel != null || args.power_level != null)
    def hasSettings = (args.rebuild_on_reboot != null || args.ping_inactive != null)
    def hasPingDevice = (args.ping_device != null)
    if (!hasEnabled && !hasChannel && !hasSettings && !hasPingDevice) {
        throw new IllegalArgumentException("Specify enabled, channel + power_level, rebuild_on_reboot/ping_inactive, or ping_device.")
    }

    if (hasEnabled) {
        boolean enabled = (args.enabled == true)
        if (!enabled) requireDestructiveConfirm(args.confirm)
        try {
            def resp = _radioGet("/hub/zigbee/enable/${enabled}")
            mcpLog("info", "hub-admin", "Zigbee radio ${enabled ? 'enabled' : 'disabled'} via MCP")
            return [success: true, radio: "zigbee", enabled: enabled,
                    message: "Zigbee radio ${enabled ? 'enabled' : 'disabled'}.",
                    response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Zigbee enable/disable failed", e)
            return [success: false, error: "Zigbee enable/disable failed: ${e.message}", note: "Check Hub Security credentials."]
        }
    }

    if (hasPingDevice) {
        def pd = args.ping_device
        if (!(pd instanceof Map) || pd.device_id == null || pd.enabled == null) {
            throw new IllegalArgumentException("ping_device requires {device_id, enabled} -- toggle keep-alive pinging for one Zigbee device.")
        }
        try {
            boolean on = (pd.enabled == true)
            def resp = _radioGet("/hub/zigbee/updatePingDevice/${java.net.URLEncoder.encode(pd.device_id.toString(), 'UTF-8')}/${on}")
            mcpLog("info", "hub-admin", "Zigbee keep-alive ping ${on ? 'on' : 'off'} for device ${pd.device_id} via MCP")
            return [success: true, radio: "zigbee", pingDevice: [deviceId: pd.device_id.toString(), enabled: on],
                    message: "Zigbee keep-alive ping ${on ? 'enabled' : 'disabled'} for device ${pd.device_id}.", response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Zigbee ping-device update failed", e)
            return [success: false, error: "Zigbee ping-device update failed: ${e.message}", note: "Check the device id and Hub Security credentials."]
        }
    }

    if (hasSettings) {
        // updateSettings is a full-set GET (sends both flags), so an omitted flag must be merged
        // from the current zigbeeDetails to "keep its value". If that current value can't be read
        // (non-Map response / missing key on older firmware), fabricating false would silently flip
        // a live setting -- refuse instead so the caller passes the flag explicitly.
        try {
            def current = _radioGet("/hub/zigbeeDetails/json")
            def cur = (current instanceof Map) ? current : [:]
            def rebuildCur = cur.rebuildNetworkOnReboot
            def pingCur = cur.inactiveDevicePingEnabled
            if (args.rebuild_on_reboot == null && !(rebuildCur instanceof Boolean)) {
                return [success: false, error: "Could not read the current rebuild-on-reboot setting to preserve it.",
                        note: "Pass rebuild_on_reboot explicitly, or read hub_get_radio_details(radio='zigbee') first."]
            }
            if (args.ping_inactive == null && !(pingCur instanceof Boolean)) {
                return [success: false, error: "Could not read the current ping-inactive setting to preserve it.",
                        note: "Pass ping_inactive explicitly, or read hub_get_radio_details(radio='zigbee') first."]
            }
            boolean rebuild = (args.rebuild_on_reboot != null) ? (args.rebuild_on_reboot == true) : (rebuildCur == true)
            boolean ping = (args.ping_inactive != null) ? (args.ping_inactive == true) : (pingCur == true)
            def resp = _radioGet("/hub/zigbee/updateSettings?rebuildNetworkOnReboot=${rebuild}&inactiveDevicePingEnabled=${ping}")
            mcpLog("info", "hub-admin", "Zigbee radio settings updated via MCP")
            return [success: true, radio: "zigbee", rebuildNetworkOnReboot: rebuild, inactiveDevicePingEnabled: ping,
                    message: "Zigbee radio settings updated.", response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Zigbee settings update failed", e)
            return [success: false, error: "Zigbee settings update failed: ${e.message}", note: "Check Hub Security credentials."]
        }
    }

    if (args.channel == null || args.power_level == null) {
        throw new IllegalArgumentException("channel and power_level must be set together.")
    }
    try {
        def query = "channel=${java.net.URLEncoder.encode(args.channel.toString(), 'UTF-8')}&powerLevel=${java.net.URLEncoder.encode(args.power_level.toString(), 'UTF-8')}"
        def resp = _radioGet("/hub/zigbee/updateChannelAndPower?${query}")
        mcpLog("info", "hub-admin", "Zigbee channel/power updated via MCP")
        return [success: true, radio: "zigbee", channel: args.channel, powerLevel: args.power_level,
                message: "Zigbee radio channel/power updated.",
                warning: "Changing the Zigbee channel can drop Zigbee devices that do not follow; they may need re-pairing.",
                response: resp]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Zigbee config update failed", e)
        return [success: false, error: "Zigbee config update failed: ${e.message}", note: "Check channel/power values and Hub Security credentials."]
    }
}

// hub_call_zwave: non-idempotent Z-Wave lifecycle operations (repair, inclusion,
// exclusion, node maintenance, nodeReplace, nodeRemove, antenna test, SmartStart
// delete). Absorbs the former hub_call_zwave_repair (action='repair_start').
// node_id is required for the per-node actions; exclusion-start and node-remove
// are confirm-gated (they unpair / disrupt devices).
def toolCallZwave(args) {
    def action = args.action?.toString()
    if (!action) throw new IllegalArgumentException("action is required. See hub_get_tool_guide for the full action list.")
    def nodeId = args.node_id?.toString()?.trim()
    def needsNode = action in ["repair_node", "node_refresh", "node_rediscover", "node_reinitialize", "node_remove", "node_replace"]
    if (needsNode && !nodeId) {
        throw new IllegalArgumentException("node_id is required for action '${action}'.")
    }

    try {
        def resp
        switch (action) {
            case "repair_start":
                // Modern zwaveRepair2 (C-7+) is a GET (UI sends resetStats=false&maxHealth=10);
                // fall back to legacy /hub/zwaveRepair (C-5/earlier) if it errors. Probe with
                // the non-throwing _radioGetSafe so we can detect the {error} map and fall back;
                // the legacy attempt uses throwing _radioGet so a real failure there surfaces as
                // success:false rather than a fabricated success.
                resp = _radioGetSafe("/hub/zwaveRepair2?resetStats=false&maxHealth=10")
                if (resp instanceof Map && resp.error) {
                    mcpLog("debug", "hub-admin", "zwaveRepair2 missed (${resp.error}); trying legacy /hub/zwaveRepair")
                    resp = _radioGet("/hub/zwaveRepair")
                }
                return [success: true, action: action,
                        message: "Z-Wave network repair started (runs in the background).",
                        duration: "Typically 5-30 minutes depending on network size.",
                        warning: "Z-Wave devices may be temporarily unresponsive during repair. Do not start another repair until this one completes.",
                        note: "Poll hub_get_radio_details(include_status=true) for repair stage.", response: resp]
            case "repair_cancel":
                resp = _radioGet("/hub/zwaveCancelRepair")
                return [success: true, action: action, message: "Z-Wave repair cancel requested.", response: resp]
            case "repair_node":
                resp = _radioGet("/hub/zwaveNodeRepair2?zwaveNodeId=${java.net.URLEncoder.encode(nodeId, 'UTF-8')}")
                return [success: true, action: action, nodeId: nodeId, message: "Per-node Z-Wave repair started for node ${nodeId}.", response: resp]
            case "inclusion_start":
                resp = _radioGet("/hub/startZwaveJoin")
                return [success: true, action: action,
                        message: "Z-Wave inclusion (join) started. Put the device into pairing mode now.",
                        note: "Poll hub_get_radio_details(include_status=true). For S2 devices, follow with grant_keys / grant_code once the hub requests them.", response: resp]
            case "inclusion_stop":
                resp = _radioGet("/hub/stopJoin")
                return [success: true, action: action, message: "Z-Wave inclusion stopped.", response: resp]
            case "grant_keys":
                if (!(args.security_keys instanceof Map)) throw new IllegalArgumentException("grant_keys requires security_keys: a map of S2 grant booleans (e.g. {S2AccessControl:true, S2Authenticated:true, S2Unauthenticated:false, S0Unauthenticated:false}).")
                resp = _radioPost("/hub/zwave/securityKeys", groovy.json.JsonOutput.toJson(args.security_keys))
                return [success: true, action: action, message: "S2 security key grants submitted.", response: resp]
            case "grant_code":
                if (!(args.security_code instanceof Map)) throw new IllegalArgumentException("grant_code requires security_code: a map (e.g. {accept:true, securityCode:'12345'}).")
                resp = _radioPost("/hub/zwave/securityCode", groovy.json.JsonOutput.toJson(args.security_code))
                return [success: true, action: action, message: "S2 DSK / security code submitted.", response: resp]
            case "exclusion_start":
                requireDestructiveConfirm(args.confirm)
                resp = _radioGet("/hub/zwaveExclude")
                return [success: true, action: action,
                        message: "Z-Wave exclusion started. Activate the device to remove it from the mesh.",
                        warning: "Exclusion unpairs a device from the hub. Triggering a generic exclusion can remove a device from ANOTHER controller too.",
                        note: "Poll hub_get_radio_details(include_status=true) for exclusion status.", response: resp]
            case "exclusion_stop":
                resp = _radioGet("/hub/stopZWaveExclude")
                return [success: true, action: action, message: "Z-Wave exclusion stopped.", response: resp]
            case "node_refresh":
                resp = _radioPost("/hub/zwave/refreshNodeStatus", [zwaveNodeId: nodeId])
                return [success: true, action: action, nodeId: nodeId, message: "Refreshed status for node ${nodeId}.", response: resp]
            case "node_rediscover":
                resp = _radioPost("/hub/zwave/discoverDevice", [zwaveNodeId: nodeId])
                return [success: true, action: action, nodeId: nodeId, message: "Rediscovery started for node ${nodeId}.", response: resp]
            case "node_reinitialize":
                resp = _radioPost("/hub/zwave/nodeReinitialize", [zwaveNodeId: nodeId])
                return [success: true, action: action, nodeId: nodeId, message: "Reinitialize started for node ${nodeId}.", response: resp]
            case "refresh_stats":
                // Vue uses a plain GET fetch for zwaveNodeDetailGet.
                resp = _radioGet("/hub/zwaveNodeDetailGet")
                return [success: true, action: action, message: "Z-Wave statistics refresh requested.", response: resp]
            case "node_replace":
                resp = _radioPost("/hub2/zwave/nodeReplace", groovy.json.JsonOutput.toJson([zwaveNodeId: nodeId]))
                return [success: true, action: action, nodeId: nodeId,
                        message: "Node replace started for node ${nodeId}.",
                        note: "Poll hub_get_radio_details(include_status=true) (status.zwaveNodeReplace) and add the replacement device; abort with action='node_replace_stop'.", response: resp]
            case "node_replace_stop":
                resp = _radioPost("/hub/zwave/nodeReplace/stop")   // bare POST (UI sends empty body)
                return [success: true, action: action, message: "Z-Wave node replace stopped.", response: resp]
            case "node_remove":
                requireDestructiveConfirm(args.confirm)
                resp = _radioPost("/hub/zwave/nodeRemove", [zwaveNodeId: nodeId])
                return [success: true, action: action, nodeId: nodeId,
                        message: "Failed-node removal requested for node ${nodeId}.",
                        warning: "This force-removes a non-responding node from the Z-Wave mesh; it does not gracefully exclude a live device.", response: resp]
            case "antenna_test_start":
                if (!nodeId) throw new IllegalArgumentException("antenna_test_start requires node_id (the device to test against).")
                resp = _radioGet("/hub/zwave2/startAntennaTest?node=${java.net.URLEncoder.encode(nodeId, 'UTF-8')}")
                return [success: true, action: action, nodeId: nodeId, message: "Z-Wave antenna test started.", response: resp]
            case "antenna_test_continue":
                resp = _radioGet("/hub/zwave2/antennaTestContinue")
                return [success: true, action: action, message: "Z-Wave antenna test continued.", response: resp]
            case "smartstart_delete":
                if (!args.node_dsk) throw new IllegalArgumentException("smartstart_delete requires node_dsk (the DSK from hub_get_radio_details(include_smartstart=true)).")
                resp = _radioPost("/mobileapi/zwave/smartstart/delete", groovy.json.JsonOutput.toJson([nodeDSK: args.node_dsk.toString()]))
                return [success: true, action: action, message: "SmartStart entry deleted.", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}'. See hub_get_tool_guide for valid Z-Wave actions.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_zwave action '${action}' failed", e)
        return [success: false, action: action, error: "Z-Wave action '${action}' failed: ${e.message}", note: "Check Hub Security credentials and that the radio is enabled."]
    }
}

// hub_call_zigbee: non-idempotent Zigbee operations (radio reboot, network rebuild,
// channel scan trigger).
def toolCallZigbee(args) {
    def action = args.action?.toString()
    if (!action) throw new IllegalArgumentException("action is required: radio_reboot, rebuild_network, or channel_scan.")
    try {
        def resp
        switch (action) {
            case "radio_reboot":
                // Vue uses plain GET fetch for these Zigbee ops.
                resp = _radioGet("/hub/rebootZigbeeRadio")
                return [success: true, action: action, message: "Zigbee radio reboot requested.",
                        note: "Verify with hub_get_radio_details(radio='zigbee', include_status=true).", response: resp]
            case "rebuild_network":
                resp = _radioGet("/hub/rebuildZigbeeNetwork")
                return [success: true, action: action, message: "Zigbee network rebuild started.",
                        warning: "Rebuilding takes time; Zigbee devices may be briefly unresponsive.", response: resp]
            case "channel_scan":
                resp = _radioGet("/hub/zigbeeChannelScan")
                return [success: true, action: action, message: "Zigbee channel scan triggered.",
                        note: "Read results with hub_get_radio_details(include_channel_scan=true).", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}'. Valid: radio_reboot, rebuild_network, channel_scan.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_zigbee action '${action}' failed", e)
        return [success: false, action: action, error: "Zigbee action '${action}' failed: ${e.message}", note: "Check Hub Security credentials."]
    }
}

// hub_call_matter: non-idempotent Matter operations (enable/disable, pair a device
// by setup code, open a pairing/share window for a commissioned node).
def toolCallMatter(args) {
    def action = args.action?.toString()
    if (!action) throw new IllegalArgumentException("action is required: enable, disable, pair, or open_pairing_window.")
    try {
        def resp
        switch (action) {
            case "enable":
            case "disable":
                boolean enable = (action == "enable")
                if (!enable) requireDestructiveConfirm(args.confirm)
                resp = _radioGet("/hub/matter/enable/${enable}")
                return [success: true, action: action,
                        message: "Matter ${enable ? 'enable' : 'disable'} requested.",
                        warning: "Matter enable/disable requires a HUB REBOOT to take effect. Reboot via hub_reboot when ready.", response: resp]
            case "pair":
                if (!args.setup_code) throw new IllegalArgumentException("pair requires setup_code (the 11- or 21-digit Matter setup code / pairing code).")
                resp = _radioGet("/hub/matter/pair?setupCode=${java.net.URLEncoder.encode(args.setup_code.toString(), 'UTF-8')}")
                return [success: true, action: action,
                        message: "Matter commissioning started for the given setup code.",
                        note: "Poll hub_get_radio_details(radio='matter', include_status=true) for commissioning progress.", response: resp]
            case "open_pairing_window":
                if (!args.node_id) throw new IllegalArgumentException("open_pairing_window requires node_id (the commissioned Matter node to share).")
                resp = _radioGet("/hub/matter/openPairingWindow?node=${java.net.URLEncoder.encode(args.node_id.toString(), 'UTF-8')}")
                return [success: true, action: action, nodeId: args.node_id?.toString(),
                        message: "Matter pairing/share window opened.",
                        note: "The response carries the setup code for sharing this device to another fabric.", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}'. Valid: enable, disable, pair, open_pairing_window.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_matter action '${action}' failed", e)
        return [success: false, action: action, error: "Matter action '${action}' failed: ${e.message}", note: "Matter requires a C-8/C-8 Pro on supported firmware."]
    }
}

// hub_call_destructive_radio: the single confirm-gated destructive radio tool.
// Wipes a radio's network/fabric (unpairs everything) or flashes firmware (can
// brick hardware). Misfire-proof: explicit radio + explicit action, no defaults,
// confirm=true required for every path.
def toolCallDestructiveRadio(args) {
    requireDestructiveConfirm(args.confirm)
    def radio = args.radio?.toString()
    def action = args.action?.toString()
    if (!radio) throw new IllegalArgumentException("radio is required: zwave, zigbee, or matter.")
    if (!action) throw new IllegalArgumentException("action is required: reset, or a firmware action (device_firmware_start, device_firmware_abort, zwave_chip_firmware, zigbee_firmware).")

    try {
        def resp
        // --- Network/fabric wipe (unpairs all devices) ---
        if (action == "reset") {
            def path
            switch (radio) {
                case "zwave": path = "/hub/zwave/resetJson"; break
                case "zigbee": path = "/hub/zigbee/reset"; break
                case "matter": path = "/hub/matter/reset"; break
                default: throw new IllegalArgumentException("radio must be zwave, zigbee, or matter for reset.")
            }
            resp = _radioGet(path)
            mcpLog("warn", "hub-admin", "DESTRUCTIVE: ${radio} radio reset via MCP")
            return [success: true, radio: radio, action: action,
                    message: "${radio} radio/fabric reset. ALL ${radio} devices have been unpaired.",
                    warning: "This is irreversible. Every ${radio} device must be re-paired.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp), response: resp]
        }

        // --- Firmware flash (can brick hardware) ---
        switch (action) {
            case "device_firmware_start":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_start is Z-Wave only.")
                if (args.node_id == null || !args.file_name) throw new IllegalArgumentException("device_firmware_start requires node_id and file_name (from hub_get_radio_details(include_firmware=true)).")
                def startBody = [nodeId: args.node_id, target: (args.target != null ? args.target : args.node_id), fileName: args.file_name.toString()]
                resp = _radioPost("/hub/zwave/deviceFirmware/start", groovy.json.JsonOutput.toJson(startBody))
                return [success: true, radio: radio, action: action,
                        message: "Z-Wave device firmware update started for node ${args.node_id}.",
                        warning: "Do NOT power-cycle the device or hub during the flash; interruption can brick the device.",
                        note: "Poll hub_get_radio_details(node_id=${args.node_id}). Abort with action='device_firmware_abort'.", response: resp]
            case "device_firmware_abort":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_abort is Z-Wave only.")
                if (args.node_id == null) throw new IllegalArgumentException("device_firmware_abort requires node_id.")
                resp = _radioPost("/hub/zwave/deviceFirmware/abort", groovy.json.JsonOutput.toJson([nodeId: args.node_id]))
                return [success: true, radio: radio, action: action, message: "Z-Wave device firmware update aborted for node ${args.node_id}.", response: resp]
            case "zwave_chip_firmware":
                if (radio != "zwave") throw new IllegalArgumentException("zwave_chip_firmware is Z-Wave only.")
                resp = _radioGet("/hub/zwave/startUpdateHubFirmware")
                return [success: true, radio: radio, action: action,
                        message: "Z-Wave chip (hub radio) firmware update started.",
                        warning: "Do NOT power-cycle the hub during the flash; interruption can brick the radio.", response: resp]
            case "zigbee_firmware":
                if (radio != "zigbee") throw new IllegalArgumentException("zigbee_firmware is Zigbee only.")
                resp = _radioGet("/hub/zigbee/updateFirmware/latest")
                return [success: true, radio: radio, action: action,
                        message: "Zigbee radio firmware update to latest started.",
                        warning: "Do NOT power-cycle the hub during the flash; interruption can brick the radio.", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}'. Valid: reset, device_firmware_start, device_firmware_abort, zwave_chip_firmware, zigbee_firmware.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_destructive_radio ${radio}/${action} failed", e)
        return [success: false, radio: radio, action: action, error: "Destructive radio op '${action}' on ${radio} failed: ${e.message}", note: "Check Hub Security credentials."]
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
            description: "Hub network diagnostics + device-staleness checks. Stale check covers only devices authorized for MCP access (the app's selected device list) with no activity in staleHours;[[FLAT_TRIM]] MCP-managed virtual/child devices (from hub_create_virtual_device) are a SEPARATE population and are NOT included here — list those via hub_list_devices(filter='virtual').[[/FLAT_TRIM]] Network diagnostics (any combination, all read-only): pingHosts ICMP-pings LAN IPs; traceroute runs the hub's route trace to one IPv4; speedtest runs the hub's ~10s WAN download test.[[FLAT_TRIM]] Any of these may be combined in a single call. Pass cursor (opaque string from a prior nextCursor) to page the staleDevices list at 100 per page when the full response would be too large.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    staleHours: [type: "integer", description: "Flag devices with no activity in this many hours. Default: 24.", default: 24],
                    includeHealthy: [type: "boolean", description: "Include healthy devices in the response (can be large). Default: false.", default: false],
                    pingHosts: [type: "array", items: [type: "string"], description: "Optional IPv4 addresses to ICMP-ping (max 5 per call).[[FLAT_TRIM]] Each entry is sent through hubitat.helper.NetworkUtils.ping() and reported under pingResults with reachable/rttAvg/packetLoss. Hostnames are not resolved — pass IPs only.[[/FLAT_TRIM]]"],
                    pingCount: [type: "integer", description: "Packets to send per host (1-5). Default: 3.", default: 3],
                    traceroute: [type: "string", description: "Optional single IPv4 dotted-quad host (e.g. '8.8.8.8') to traceroute; plain-text route table returned under traceroute.output.[[FLAT_TRIM]] Hostnames are rejected — pass an IP.[[/FLAT_TRIM]]"],
                    speedtest: [type: "boolean", description: "If true, run the hub's WAN download speedtest; plain-text wget log with the measured speed returned under speedtest.output.[[FLAT_TRIM]] Fixed 10 MB Hubitat S3 blob, no caller input; a few seconds on a fast link, up to ~90s on slow ones.[[/FLAT_TRIM]] Default: false."],
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
                    traceroute: [type: "object", description: "Present when traceroute supplied. {host, endpoint, output (plain-text route table), error (present on fetch failure)}", properties: [
                        host: [type: "string", description: "Target IPv4"],
                        endpoint: [type: "string", description: "Internal API endpoint used"],
                        output: [type: "string", description: "Plain-text traceroute route table (truncated to 8000 chars)"],
                        error: [type: "string", description: "Present when the traceroute fetch failed"]
                    ]],
                    speedtest: [type: "object", description: "Present when speedtest=true. {endpoint, output (plain-text wget log with WAN download speed), error (present on fetch failure)}", properties: [
                        endpoint: [type: "string", description: "Internal API endpoint used"],
                        output: [type: "string", description: "Plain-text speedtest wget log incl. WAN download speed (truncated to 8000 chars)"],
                        error: [type: "string", description: "Present when the speedtest fetch failed"]
                    ]],
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
            description: "Get Z-Wave/Zigbee/Matter radio info and the read-only side of the radio surface: details (firmware, home/PAN ID, channel, device nodes), mesh topology, per-node state, lifecycle status pollers, channel scan, SmartStart entries, and firmware-eligible devices. Omit radio for Z-Wave+Zigbee, or pass 'matter' for fabric/commissioned-device details. The include_* flags and node_id attach extra read blocks under named result keys.[[FLAT_TRIM]] Pair this with the write tools in hub_manage_radio (hub_set_zwave / hub_set_zigbee / hub_call_zwave / hub_call_zigbee / hub_call_matter) and the destructive resets/firmware in hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    radio: [type: "string", enum: ["zwave", "zigbee", "matter"], description: "Which radio to query. Omit to return both Z-Wave and Zigbee; pass 'matter' for the Matter fabric and commissioned-device list."],
                    include_topology: [type: "boolean", description: "Also include the mesh route/topology map[[FLAT_TRIM]] (Z-Wave nodes+connectors and raw route table; Zigbee children+neighbors+routes)[[/FLAT_TRIM]]. Z-Wave/Zigbee only. Read-only. Default false."],
                    node_id: [type: "string", description: "Per-node status for this id. With radio='matter' -> per-node Matter commissioning status under result.matterPairStatus; otherwise Z-Wave node state under result.nodeState (plain text; 'Done' when idle)."],
                    include_status: [type: "boolean", description: "Attach lifecycle status pollers under result.status[[FLAT_TRIM]]: Z-Wave repair stage, heal-running flag, exclusion status, join discovery, antenna-test progress, node-replace status/info, and Zigbee network status (panId/extendedPanId/networkState). (Matter commissioning status is per-node: radio='matter' + node_id.)[[/FLAT_TRIM]] Default false."],
                    include_logs: [type: "boolean", description: "Attach Matter chip-tool logs ({text}, ANSI) under result.matterLogs. Default false."],
                    include_channel_scan: [type: "boolean", description: "Attach Zigbee channel energy-scan results under result.channelScan (run a fresh scan with hub_call_zigbee action='channel_scan' first). Default false."],
                    include_smartstart: [type: "boolean", description: "Attach the Z-Wave SmartStart provisioning list under result.smartStart (each entry's nodeDSK feeds hub_call_zwave action='smartstart_delete'). Default false."],
                    include_firmware: [type: "boolean", description: "Attach firmware-eligible Z-Wave devices + available files under result.firmware ({devices:[{nodeId,label}], files}). Feeds hub_call_destructive_radio firmware actions. Default false."]
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
                    matterData: [type: "object", description: "Parsed Matter details; present for radio='matter'. {enabled, installed, networkState, ipAddresses, fabricId, devices[]}"],
                    topology: [type: "object", description: "Mesh route/topology; present only when include_topology=true. {endpoint, routes (parsed node/route graph), zwaveTopologyTable (raw, Z-Wave only), error?}"],
                    nodeState: [description: "Per-node Z-Wave state; present when node_id given (parsed JSON or plain text)"],
                    status: [type: "object", description: "Lifecycle status pollers; present when include_status=true. {zwaveRepair, zwaveRepairRunning, zwaveExclude, zwaveJoinDiscovery, zwaveAntennaTest, zwaveNodeReplace:{status,info}, zigbee}"],
                    matterPairStatus: [type: "object", description: "Per-node Matter commissioning status; present when radio='matter' and node_id is given"],
                    matterLogs: [type: "object", description: "Matter chip-tool logs; present when include_logs=true. {text}"],
                    channelScan: [description: "Zigbee channel energy-scan results; present when include_channel_scan=true"],
                    smartStart: [type: "object", description: "SmartStart provisioning entries; present when include_smartstart=true. {items:[...]}"],
                    firmware: [type: "object", description: "Firmware-eligible devices + files; present when include_firmware=true. {devices, files}"],
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
            name: "hub_set_zwave",
            description: "Configure the Z-Wave radio (idempotent): enable/disable the radio, or set the region and long-range channel. Read current values with hub_get_radio_details(radio='zwave').[[FLAT_TRIM]] Config updates preserve the radio's other current settings (a region change keeps enabled/secureJoin). Disabling strands every Z-Wave device, so it is confirm-gated. For repair/inclusion/exclusion/maintenance use hub_call_zwave; for reset/firmware use hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    enabled: [type: "boolean", description: "Enable (true) or disable (false) the Z-Wave radio. Disable requires confirm=true."],
                    region: [type: "string", description: "Z-Wave RF region (e.g. 'US', 'EU'). Must match a region your hub hardware supports."],
                    long_range_channel: [description: "Z-Wave Long Range channel: 255=Auto, 0=Channel A, 1=Channel B (US_LR hubs)."],
                    confirm: [type: "boolean", description: "Required true to DISABLE the radio (backup <24h also enforced). Not needed for enable or config-only changes."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the change applied"],
                    radio: [type: "string", description: "Always 'zwave'"],
                    enabled: [type: "boolean", description: "Resulting enabled state; present for enable/disable"],
                    region: [description: "Resulting region; present for config update"],
                    longRangeChannel: [description: "Resulting long-range channel; present for config update"],
                    message: [type: "string", description: "Human-readable result"],
                    note: [type: "string", description: "Follow-up guidance"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_zigbee",
            description: "Configure the Zigbee radio (idempotent): enable/disable, channel + power, radio settings (rebuild-on-reboot / inactive-device ping), or per-device keep-alive ping. One operation per call. Read current values with hub_get_radio_details(radio='zigbee').[[FLAT_TRIM]] Channel changes can drop devices that do not follow (they may need re-pairing). Disabling strands every Zigbee device, so it is confirm-gated. Settings merge over current values (an unspecified flag is preserved). For reboot/rebuild/channel-scan use hub_call_zigbee; for reset/firmware use hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    enabled: [type: "boolean", description: "Enable (true) or disable (false) the Zigbee radio. Disable requires confirm=true."],
                    channel: [description: "Zigbee channel (typically 11-26). Set together with power_level."],
                    power_level: [description: "Zigbee transmit power level (hub-dependent dBm scale). Set together with channel."],
                    rebuild_on_reboot: [type: "boolean", description: "Radio setting: rebuild the Zigbee network on each hub reboot.[[FLAT_TRIM]] Merged with ping_inactive over current settings.[[/FLAT_TRIM]]"],
                    ping_inactive: [type: "boolean", description: "Radio setting: keep-alive ping inactive Zigbee devices.[[FLAT_TRIM]] Merged with rebuild_on_reboot over current settings.[[/FLAT_TRIM]]"],
                    ping_device: [type: "object", description: "Toggle keep-alive ping for ONE device: {device_id, enabled}.[[FLAT_TRIM]] device_id is the Zigbee device id; enabled is a boolean.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "Required true to DISABLE the radio (backup <24h also enforced). Not needed for the other changes."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the change applied"],
                    radio: [type: "string", description: "Always 'zigbee'"],
                    enabled: [type: "boolean", description: "Resulting enabled state; present for enable/disable"],
                    channel: [description: "Resulting channel; present for channel/power update"],
                    powerLevel: [description: "Resulting power level; present for channel/power update"],
                    rebuildNetworkOnReboot: [type: "boolean", description: "Resulting rebuild-on-reboot setting; present for a settings update"],
                    inactiveDevicePingEnabled: [type: "boolean", description: "Resulting inactive-device-ping setting; present for a settings update"],
                    pingDevice: [type: "object", description: "Resulting per-device ping {deviceId, enabled}; present for ping_device"],
                    message: [type: "string", description: "Human-readable result"],
                    warning: [type: "string", description: "Channel-change disruption warning"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_call_zwave",
            description: "Z-Wave network lifecycle operations (NOT idempotent): repair, device inclusion (join + S2 grants), exclusion, per-node maintenance, node replace/remove, antenna test, and SmartStart delete. Pick the operation with action.[[FLAT_TRIM]] node_id is required for per-node actions. exclusion_start and node_remove unpair/disrupt devices and require confirm=true. Repair takes 5-30 min and devices may be briefly unresponsive. Poll progress with hub_get_radio_details(include_status=true). For enable/disable/region/channel use hub_set_zwave; for reset/firmware use hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["repair_start", "repair_cancel", "repair_node", "inclusion_start", "inclusion_stop", "grant_keys", "grant_code", "exclusion_start", "exclusion_stop", "node_refresh", "node_rediscover", "node_reinitialize", "refresh_stats", "node_replace", "node_replace_stop", "node_remove", "antenna_test_start", "antenna_test_continue", "smartstart_delete"], description: "The Z-Wave operation.[[FLAT_TRIM]] repair_start/cancel + repair_node (network rebuild); inclusion_start/stop + grant_keys/grant_code (S2 pairing); exclusion_start/stop; node_refresh/rediscover/reinitialize + refresh_stats (maintenance); node_replace + node_replace_stop; node_remove (failed-node removal); antenna_test_start/continue; smartstart_delete. Poll progress via hub_get_radio_details(include_status=true).[[/FLAT_TRIM]]"],
                    node_id: [type: "string", description: "Z-Wave node id; required for repair_node, node_refresh/rediscover/reinitialize, node_remove, node_replace, antenna_test_start."],
                    security_keys: [type: "object", description: "grant_keys only: S2 grant booleans, e.g. {S2AccessControl:true, S2Authenticated:true, S2Unauthenticated:false, S0Unauthenticated:false}."],
                    security_code: [type: "object", description: "grant_code only: S2 DSK / security code, e.g. {accept:true, securityCode:'12345'}."],
                    node_dsk: [type: "string", description: "smartstart_delete only: the DSK from hub_get_radio_details(include_smartstart=true)."],
                    confirm: [type: "boolean", description: "Required true for exclusion_start and node_remove (backup <24h also enforced)."]
                ],
                required: ["action"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation was accepted"],
                    action: [type: "string", description: "Echo of the requested action"],
                    nodeId: [type: "string", description: "Echo of node_id; present for per-node actions"],
                    message: [type: "string", description: "Human-readable result"],
                    duration: [type: "string", description: "Expected duration; present for repair_start"],
                    warning: [type: "string", description: "Disruption warning"],
                    note: [type: "string", description: "Follow-up / polling guidance"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_call_zigbee",
            description: "Zigbee radio operations (NOT idempotent): reboot the radio, rebuild the mesh network, or trigger a channel energy scan. Pick the operation with action.[[FLAT_TRIM]] Rebuild takes time and Zigbee devices may be briefly unresponsive; read scan results with hub_get_radio_details(include_channel_scan=true). For enable/disable/channel/power use hub_set_zigbee; for reset/firmware use hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["radio_reboot", "rebuild_network", "channel_scan"], description: "radio_reboot (restart the Zigbee chip), rebuild_network (rebuild the mesh), or channel_scan (trigger an energy scan)."]
                ],
                required: ["action"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation was accepted"],
                    action: [type: "string", description: "Echo of the requested action"],
                    message: [type: "string", description: "Human-readable result"],
                    warning: [type: "string", description: "Disruption warning; present for rebuild_network"],
                    note: [type: "string", description: "Follow-up guidance"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_call_matter",
            description: "Matter operations (NOT idempotent): enable/disable the Matter radio, pair (commission) a device by setup code, or open a pairing/share window for a commissioned node. Pick the operation with action.[[FLAT_TRIM]] Enable/disable requires a HUB REBOOT to take effect (reboot via hub_reboot). Poll commissioning with hub_get_radio_details(radio='matter', include_status=true). Matter requires a C-8/C-8 Pro on supported firmware. For reset use hub_call_destructive_radio.[[/FLAT_TRIM]] Requires Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["enable", "disable", "pair", "open_pairing_window"], description: "enable/disable the Matter radio (needs a hub reboot), pair a device by setup_code, or open_pairing_window to share a commissioned node_id."],
                    setup_code: [type: "string", description: "pair only: the 11- or 21-digit Matter setup/pairing code."],
                    node_id: [type: "string", description: "open_pairing_window only: the commissioned Matter node id to share."],
                    confirm: [type: "boolean", description: "Required true to disable Matter (backup <24h also enforced)."]
                ],
                required: ["action"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation was accepted"],
                    action: [type: "string", description: "Echo of the requested action"],
                    nodeId: [type: "string", description: "Echo of node_id; present for open_pairing_window"],
                    message: [type: "string", description: "Human-readable result"],
                    warning: [type: "string", description: "Reboot-required warning; present for enable/disable"],
                    note: [type: "string", description: "Follow-up guidance"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_call_destructive_radio",
            description: """⚠️ DESTRUCTIVE radio operations — network/fabric WIPE or FIRMWARE FLASH. Reset unpairs EVERY device on a radio (irreversible); a firmware flash can BRICK hardware if interrupted.

Misfire-proof: you MUST pass an explicit radio AND an explicit action AND confirm=true — there are no defaults. reset (radio=zwave|zigbee|matter) wipes that radio's network/fabric. Firmware: device_firmware_start/abort (Z-Wave device OTA, needs node_id+file_name from hub_get_radio_details(include_firmware=true)), zwave_chip_firmware (hub Z-Wave radio), zigbee_firmware (Zigbee radio to latest).

PRE-FLIGHT: 1) Backup <24h old 2) Tell the user exactly which radio/devices are affected and that reset is irreversible / firmware can brick 3) Get explicit confirmation 4) Set confirm=true. Do NOT power-cycle the hub or device during a flash.
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    radio: [type: "string", enum: ["zwave", "zigbee", "matter"], description: "REQUIRED: which radio. reset works for all three; firmware actions are radio-specific (device_firmware_*/zwave_chip_firmware=zwave, zigbee_firmware=zigbee)."],
                    action: [type: "string", enum: ["reset", "device_firmware_start", "device_firmware_abort", "zwave_chip_firmware", "zigbee_firmware"], description: "REQUIRED: reset (wipe network/fabric — unpairs all devices), or a firmware flash action."],
                    node_id: [description: "Z-Wave node id; required for device_firmware_start/abort."],
                    file_name: [type: "string", description: "Firmware file name from hub_get_radio_details(include_firmware=true); required for device_firmware_start."],
                    target: [description: "Optional Z-Wave firmware target index for device_firmware_start (defaults to node_id)."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms backup was created and the user approved this destructive radio op."]
                ],
                required: ["radio", "action", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation was accepted"],
                    radio: [type: "string", description: "Echo of the requested radio"],
                    action: [type: "string", description: "Echo of the requested action"],
                    message: [type: "string", description: "Human-readable result"],
                    warning: [type: "string", description: "Irreversibility / brick warning"],
                    lastBackup: [type: "string", description: "Formatted last-backup timestamp; present for reset"],
                    note: [type: "string", description: "Follow-up guidance"],
                    error: [type: "string", description: "Present on failure"],
                    response: [description: "Hub response body"]
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
        "hub_delete_captured_state",
        // Radio config: enable/disable/region/channel/power are state assignments --
        // re-issuing identical args lands the radio in the same state (idempotent).
        // The hub_call_* radio ops (repair/join/exclude/firmware/etc.) are NOT.
        "hub_set_zwave", "hub_set_zigbee"
    ]
}

def _openWorldToolNames_partDiagnostics() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        // pingHosts sends caller-directed ICMP to ANY routable IPv4, traceroute
        // traces a route to an arbitrary IPv4, and speedtest pulls from a fixed
        // Hubitat S3 URL -- all three reach beyond the LAN to the open internet.
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
        hub_get_device_health: [title: "Get Device Health", summary: "Find stale devices, ICMP-ping LAN hosts, run traceroute/WAN speedtest, and optionally blink the hub identify LED."],
        hub_get_radio_details: [title: "Get Radio Details", summary: "Z-Wave/Zigbee/Matter details, topology, per-node state, status, channel scan, SmartStart, firmware."],
        hub_set_zwave: [title: "Set Z-Wave Radio", summary: "Enable/disable the Z-Wave radio or set its region and long-range channel."],
        hub_set_zigbee: [title: "Set Zigbee Radio", summary: "Enable/disable the radio, set channel/power, radio settings (rebuild-on-reboot, ping-inactive), or per-device keep-alive ping."],
        hub_call_zwave: [title: "Z-Wave Operations", summary: "Z-Wave repair, inclusion, exclusion, node maintenance, replace/remove, antenna test, SmartStart delete."],
        hub_call_zigbee: [title: "Zigbee Operations", summary: "Reboot the Zigbee radio, rebuild the network, or trigger a channel scan."],
        hub_call_matter: [title: "Matter Operations", summary: "Enable/disable Matter, pair a device by setup code, or open a pairing window."],
        hub_call_destructive_radio: [title: "Destructive Radio Ops", summary: "Reset a radio's network/fabric or flash device/chip/radio firmware (irreversible)."],
        hub_list_captured_states: [title: "List Captured States", summary: "List saved device state snapshots."],
        hub_delete_captured_state: [title: "Delete Captured State", summary: "Delete one or all captured device state snapshots."]
    ]
}
