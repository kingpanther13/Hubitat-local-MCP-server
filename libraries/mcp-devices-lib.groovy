library(name: "McpDevicesLib", namespace: "mcp", author: "kingpanther13", description: "Device tool implementations (list/get/attribute/events/command/update/delete) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// Capability names off whatever the source hands over: a Capability object or `[name:]` map
// (read `.name`), or a bare string from a JSON feed.
private List _capabilityNames(def caps) {
    (caps instanceof List ? caps : []).collect { c ->
        (c instanceof CharSequence) ? c.toString() : (c?.name?.toString() ?: c?.toString())
    }.findAll { it != null }
}

// The hub's Devices page list, flattened depth-first in page order (children follow their parent);
// onlyIds keeps just those records. Throws IllegalStateException when the page cannot be read or
// has an unexpected shape.
private List _readDevicesPage(Set onlyIds = null) {
    def parsed
    try {
        def txt = hubInternalGet("/hub2/devicesList")
        parsed = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
    } catch (Exception e) {
        throw new IllegalStateException((e.message ?: "request failed").toString())
    }
    if (!(parsed instanceof Map) || !(parsed.devices instanceof List)) {
        throw new IllegalStateException("unexpected response shape (expected {devices: [...]})")
    }
    List out = []
    _flattenDevicesPage(parsed.devices, null, out, onlyIds)
    return out
}

private void _flattenDevicesPage(List nodes, String parentId, List out, Set onlyIds) {
    for (node in nodes) {
        def data = node instanceof Map ? node.data : null
        // Skipping a bad entry would hand back a shorter list that still reads as the whole hub.
        if (!(data instanceof Map) || data.id == null) throw new IllegalStateException("a device entry carried no id")
        String id = data.id.toString()
        if (onlyIds == null || onlyIds.contains(id)) {
            String label = data.name ? data.name.toString() : "Device ${id}".toString()
            Map states = [:]
            if (data.currentStates instanceof List) {
                for (st in data.currentStates) {
                    if (st instanceof Map && st.key != null) states.put(st.key.toString(), st.value?.toString())
                }
            }
            List tags = []
            for (t in (data.tags instanceof List ? data.tags : (data.tags ? data.tags.toString().tokenize(",") : []))) {
                if (t?.toString()?.trim()) tags << t.toString().trim()
            }
            out << [id: id, label: label, name: data.secondaryName ? data.secondaryName.toString() : label,
                driver: data.type, deviceTypeId: data.deviceTypeId, source: data.source,
                deviceNetworkId: data.dni, zigbeeId: data.zigbeeId,
                protocol: data.isZigbee == true ? "zigbee" : data.isZwave == true ? "zwave" : data.isMatter == true ? "matter" :
                    data.isNetwork == true ? "network" : data.isBluetooth == true ? "bluetooth" : data.isVirtual == true ? "virtual" : null,
                linked: data.isLinked == true, disabled: data.disabled == true, lastActivity: data.lastActivity,
                roomId: data.roomId, roomName: data.roomName ? data.roomName.toString() : null, parentDeviceId: parentId,
                tags: tags, notes: data.notes ? data.notes.toString() : null, currentStates: states,
                orphan: data.isOrphan == true, user: data.user == true, defaultCurrentState: data.defaultCurrentState ?: null,
                meshEnabled: data.hubMesh == true, homekit: data.homekit == true, showOnHome: data.showOnHome == true,
                dashboardTypes: data.dashboardTypes instanceof List ? data.dashboardTypes : [], defaultIcon: data.defaultIcon ?: null,
                remoteDeviceUrl: data.remoteDeviceUrl ?: null]
        }
        if (node.children instanceof List) _flattenDevicesPage(node.children, id, out, onlyIds)
    }
}

// The capability feed for these page records; an empty feed beside devices is a bad read (null).
private Map _capabilityFeedFor(List population, Map buttonCounts = null) {
    Map caps = _readCapabilityFeed(buttonCounts)
    return caps != null && caps.isEmpty() && population ? null : caps
}

// Capability names for every hub device by id string; null when the feed cannot be read.
// buttonCounts, when given, collects each button device's count.
private Map _readCapabilityFeed(Map buttonCounts = null) {
    try {
        def txt = hubInternalGet("/hub2/vrb/devices")
        def parsed = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        if (!(parsed instanceof List)) return null
        Map caps = [:]
        for (entry in parsed) {
            if (!(entry instanceof Map) || entry.id == null) continue
            caps.put(entry.id.toString(), _capabilityNames(entry.capabilities))
            if (buttonCounts != null && entry.buttonCount instanceof Number) buttonCounts.put(entry.id.toString(), ((Number) entry.buttonCount).intValue())
        }
        return caps
    } catch (Exception e) {
        mcpLog("warn", "device", "capability feed /hub2/vrb/devices could not be read: ${e.message}")
        return null
    }
}

// Selected devices plus this app's children.
private Set _selectedAndChildIds(List children) {
    Set ids = [] as Set
    for (d in ((settings.selectedDevices ?: []) + (children ?: []))) {
        if (d?.id != null) ids << d.id.toString()
    }
    return ids
}

// Selected devices plus this app's children; null means every device (allowlist bypass).
private Set _mcpAuthorizedIds(List children) {
    return _bypassEnabled() ? null : _selectedAndChildIds(children)
}

private List _listDeviceFieldNames() {
    ["id", "label", "name", "driver", "room", "roomId", "disabled", "deviceNetworkId", "lastActivity", "protocol",
     "parentDeviceId", "mcpManaged", "linked", "tags", "notes", "source", "currentStates", "capabilities", "attributes", "commands",
     "orphan", "user", "defaultCurrentState", "meshEnabled", "homekit", "showOnHome", "dashboardTypes", "defaultIcon",
     "remoteDeviceUrl", "buttonCount"]
}

// Every argument check, before any hub read. Returns the normalized options.
private Map _listDevicesOptions(Map args) {
    if (args.scope != null && !(args.scope in ["authorized", "all", "owned"])) {
        throw new IllegalArgumentException("scope must be one of: authorized, all, owned (got: '${args.scope}')")
    }
    if (args.format != null && !(args.format in ["summary", "detailed", "ids", "context"])) {
        throw new IllegalArgumentException("Invalid format '${args.format}'. Must be one of: summary, detailed, ids, context")
    }
    for (argName in ["labelFilter", "capabilityFilter", "roomFilter"]) {
        if (args.get(argName) != null && !(args.get(argName) instanceof String)) throw new IllegalArgumentException("${argName} must be a string")
    }
    if (args.disabled != null && !(args.disabled instanceof Boolean)) throw new IllegalArgumentException("disabled must be a boolean")
    if (args.staleHours != null && !(args.staleHours instanceof Number && args.staleHours > 0)) {
        throw new IllegalArgumentException("staleHours must be a positive number of hours")
    }
    def since = args.changedSince
    Date sinceDate = (since instanceof String || since instanceof Number) ? _parseSinceArg(since) : null
    if (since != null && sinceDate == null) {
        throw new IllegalArgumentException("Unparseable changedSince '${since}'. Pass epoch milliseconds or ISO-8601 with a numeric offset (e.g. 2026-06-23T10:00:00-0600 or -06:00; trailing Z accepted).")
    }
    Map stateFilter = null
    if (args.stateFilter != null) {
        if (!(args.stateFilter instanceof Map) || args.stateFilter.any { k, v -> !(v instanceof String || v instanceof Number || v instanceof Boolean) }) {
            throw new IllegalArgumentException("stateFilter must be an object of attribute name to value, e.g. {\"switch\":\"on\"}")
        }
        stateFilter = [:]
        args.stateFilter.each { k, v -> stateFilter.put(k.toString(), v.toString()) }
        // Matching a hidden value would confirm a guess at it, one call per guess.
        List hidden = _redactsCredentials() ? stateFilter.keySet().findAll { _deviceConfigurationSecretKey(it) } as List : []
        if (hidden) {
            throw new IllegalArgumentException("stateFilter cannot match ${hidden.join(', ')}: credential-named attributes are hidden while 'Hide passwords and credentials' is on.")
        }
    }
    for (argName in ["attributeNames", "fields"]) {
        if (args.get(argName) != null && (!(args.get(argName) instanceof List) || args.get(argName).any { !(it instanceof String) })) {
            throw new IllegalArgumentException("${argName} must be an array of strings")
        }
    }
    def unknownFields = (args.get("fields") ?: []).findAll { !(it in _listDeviceFieldNames()) }
    if (unknownFields) throw new IllegalArgumentException("Unknown fields: ${unknownFields}. Valid: ${_listDeviceFieldNames()}")
    if (args.limit != null && (!(args.limit instanceof Number) || args.limit < 0 || args.limit != Math.floor(args.limit as double))) {
        throw new IllegalArgumentException("limit must be a non-negative integer")
    }
    if (args.cursor != null) _parseListCursor(args.cursor, Integer.MAX_VALUE, "hub_list_devices")
    List deviceIds = null
    if (args.deviceIds != null) {
        if (!(args.deviceIds instanceof List) || args.deviceIds.isEmpty()) throw new IllegalArgumentException("deviceIds must be a non-empty array of device ids")
        deviceIds = []
        args.deviceIds.eachWithIndex { v, i ->
            def id = _canonicalDeviceIdArg(v)
            if (id == null) throw new IllegalArgumentException("deviceIds[${i}] must be a non-empty string or integral number (got: ${_describeValueForError(v)})")
            deviceIds << id
        }
        List dupes = deviceIds.countBy { it }.findAll { k, c -> c > 1 }.keySet().sort()
        if (dupes) throw new IllegalArgumentException("deviceIds contains duplicate entries: ${dupes} -- each device may appear once")
    }
    if (args.waitFor != null) {
        if (deviceIds == null || deviceIds.size() > 20) throw new IllegalArgumentException("waitFor requires deviceIds with 1 to 20 devices")
        if (args.cursor) throw new IllegalArgumentException("waitFor cannot be combined with cursor")
        if (args.scope == "all") throw new IllegalArgumentException("waitFor reads states, which scope='all' does not list; use scope 'authorized' or 'owned'")
    }

    String scope = args.scope ?: "authorized"
    String format = args.format ?: "summary"
    List fields = args.get("fields") ?: null
    if (scope == "all") {
        List identity = ["id", "label", "name", "driver", "room", "roomId", "protocol", "disabled", "parentDeviceId", "source", "linked", "capabilities",
                         "orphan", "user", "remoteDeviceUrl"]
        if (!(format in ["summary", "ids"])) throw new IllegalArgumentException("scope='all' supports format 'summary' or 'ids' only")
        if (stateFilter || args.staleHours != null || since != null || args.attributeNames || fields?.any { !(it in identity) }) {
            throw new IllegalArgumentException("scope='all' lists identity only (fields: ${identity.join(', ')}); states need scope='authorized'")
        }
    }
    if (args.attributeNames && !(format in ["summary", "context"])) {
        throw new IllegalArgumentException("attributeNames applies to format 'summary' or 'context' only")
    }
    if (fields && !(format in ["summary", "detailed"])) {
        throw new IllegalArgumentException("fields applies to format 'summary' or 'detailed' only")
    }

    Set fieldSet
    if (fields) fieldSet = (["id"] + fields) as LinkedHashSet
    else if (scope == "all") fieldSet = (["id", "label", "name", "driver", "room", "protocol", "disabled"] + (args.capabilityFilter ? ["capabilities"] : [])) as LinkedHashSet
    else {
        fieldSet = ["id", "label", "name", "driver", "room", "disabled", "deviceNetworkId", "lastActivity", "protocol",
                    "parentDeviceId", "mcpManaged", "linked", "tags"] as LinkedHashSet
        fieldSet.addAll(format == "detailed" ? ["capabilities", "attributes", "commands"] : ["currentStates"])
    }
    Map opts = [scope: scope, format: format, fieldSet: fieldSet,
        labelFilter: args.labelFilter ?: null, capabilityFilter: args.capabilityFilter ?: null,
        roomFilter: args.roomFilter ?: null, disabled: args.disabled, staleHours: args.staleHours,
        stateFilter: stateFilter ?: null, sinceDate: sinceDate,
        attributeNames: args.attributeNames ?: null, limit: args.limit != null ? (Math.min(args.limit as long, (long) Integer.MAX_VALUE) as int) : 0,
        cursor: args.cursor?.toString(), redact: _redactsCredentials(), deviceIds: deviceIds,
        waitFor: args.waitFor != null ? _waitForSpec(args.waitFor, true, 60000, 200) : null]
    // Epoch-ms input echoes as canonical ISO, so the echo is always a timestamp string.
    if (sinceDate != null) {
        opts.changedSince = (since instanceof Number || since.toString().trim().isLong()) ?
            sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") : since.toString().trim()
    }
    opts.filtered = [opts.labelFilter, opts.capabilityFilter, opts.roomFilter, opts.disabled, opts.staleHours,
                     opts.stateFilter, sinceDate, deviceIds].any { it != null }
    opts.wantFull = fieldSet.contains("attributes") || fieldSet.contains("commands")
    opts.wantCaps = opts.capabilityFilter != null || format == "context" || fieldSet.contains("capabilities") || fieldSet.contains("buttonCount")
    return opts
}

def toolListDevices(Map args) {
    Map opts = _listDevicesOptions(args ?: [:])
    Long reqT0 = args?.__reqT0 instanceof Number ? args.__reqT0 as Long : null
    _refuseUnsettledWait(opts.waitFor, reqT0)
    String scope = opts.scope
    String format = opts.format
    List children = getChildDevices() ?: []
    Set childIds = children.collect { it.id.toString() } as Set
    Set authorized = _mcpAuthorizedIds(children)
    Set scopeIds = scope == "owned" ? childIds : (scope == "authorized" ? authorized : null)
    boolean paged = opts.cursor != null || opts.limit > 0 || format == "context"
    int pageSize = opts.limit > 0 ? opts.limit : 50
    List deviceIds = opts.deviceIds
    if (scopeIds != null && scopeIds.isEmpty()) {
        if (opts.waitFor) throw new IllegalArgumentException("deviceIds not in scope '${scope}': ${deviceIds}. Nothing was waited for.")
        Map empty = _emptyDeviceList(format, scope == "owned" ?
            "No MCP-managed virtual devices found. Use hub_manage_virtual_device(action=\"create\") to create one." :
            "No devices selected for MCP access and no MCP-managed virtual devices")
        if (paged) empty.putAll([offset: 0, limit: pageSize, hasMore: false])
        if (deviceIds) empty.deviceIdsNotFound = deviceIds
        return empty
    }
    List population
    try {
        population = _readDevicesPage()
    } catch (IllegalStateException e) {
        return [success: false, isError: true, error: "The hub's device list (/hub2/devicesList) could not be read: ${e.message}".toString(),
                note: "Retry; if it persists check the hub's Devices page."]
    }
    if (scopeIds != null) population = population.findAll { scopeIds.contains(it.id) }
    Set idSet = deviceIds as Set
    List notFound = deviceIds ? deviceIds - population.collect { it.id } : []
    if (notFound && opts.waitFor) {
        throw new IllegalArgumentException("deviceIds not in scope '${scope}': ${notFound}. Nothing was waited for; check the ids with hub_list_devices.")
    }
    Map wait = null
    if (opts.waitFor) {
        // The scope read above is the first check; the listing then uses the wait's last read.
        Map first = [:]
        for (rec in population) if (idSet.contains(rec.id)) first.put(rec.id, rec)
        wait = _waitForRun(opts.waitFor, deviceIds, true, first, reqT0)
        population = population.collect { wait.last.get(it.id) ?: it }
    }
    Map buttons = opts.fieldSet.contains("buttonCount") ? [:] : null
    Map caps = opts.wantCaps ? _capabilityFeedFor(population, buttons) : null

    String lf = opts.labelFilter?.toLowerCase()
    String cf = opts.capabilityFilter
    String rf = opts.roomFilter
    Map stateFilter = opts.stateFilter
    Long staleMs = opts.staleHours != null ? (long) (opts.staleHours * 3600000L) : null
    Long sinceMs = opts.sinceDate?.time
    long nowMs = now()
    List matched = []
    // Devices a filter cannot judge (not in the capability feed, unparseable activity): excluded
    // and named, never guessed. The label filter runs first so a device it excludes is not reported.
    List unanswered = []
    for (candidate in population) {
        if (idSet != null && !idSet.contains(candidate.id)) continue
        if (lf != null && !candidate.label.toLowerCase().contains(lf)) continue
        if (cf != null && caps != null) {
            if (!caps.containsKey(candidate.id)) { unanswered << candidate.id; continue }
            if (!caps.get(candidate.id).any { it.equalsIgnoreCase(cf) }) continue
        } else if (cf != null) continue
        if (opts.disabled != null && candidate.disabled != opts.disabled) continue
        if (rf != null && !rf.equalsIgnoreCase(candidate.roomName ?: "")) continue
        if (stateFilter != null && stateFilter.any { k, v -> candidate.currentStates.get(k) != v }) continue
        if (staleMs != null || sinceMs != null) {
            // A device that never reported counts as stale and as not changed; an activity value
            // that does not parse is unknown, so neither filter keeps it.
            Date la = candidate.lastActivity ? _parseSinceArg(candidate.lastActivity) : null
            if (candidate.lastActivity != null && la == null) { unanswered << candidate.id; continue }
            if (staleMs != null && la != null && nowMs - la.time < staleMs) continue
            if (sinceMs != null && (la == null || la.time < sinceMs)) continue
        }
        matched << candidate
    }

    int total = matched.size()
    Map common = [:]
    if (opts.filtered) common.unfilteredTotal = population.size()
    if (notFound) common.deviceIdsNotFound = notFound
    if (wait) common.waitFor = _waitForBlock(opts.waitFor, wait, true)
    if (unanswered) {
        common.metadataUnavailableIds = unanswered
        common.partial = true
    }
    for (echoKey in ["labelFilter", "capabilityFilter", "roomFilter", "disabled", "staleHours", "stateFilter", "changedSince"]) {
        if (opts.get(echoKey) != null) common.put(echoKey, opts.get(echoKey))
    }
    if (cf != null && total == 0 && caps != null) {
        common.capabilityFilterMatchedKnownCapability = population.any { r -> caps.get(r.id)?.any { it.equalsIgnoreCase(cf) } }
    }
    if (rf != null && total == 0) common.roomFilterMatchedKnownRoom = population.any { rf.equalsIgnoreCase(it.roomName ?: "") }
    if (opts.wantCaps && caps == null) {
        common.capabilitiesUnavailable = true
        common.note = "The hub's capability feed (/hub2/vrb/devices) could not be read or listed no device, so capabilities and buttonCount are omitted and capabilityFilter matches nothing. Retry."
    }

    // A live filter can shrink the result between pages; a cursor past the end is an empty last page.
    int start = opts.cursor != null ? Math.min(_parseListCursor(opts.cursor, Integer.MAX_VALUE, "hub_list_devices"), total) : 0
    int end = paged ? (int) Math.min((long) start + pageSize, (long) total) : total
    List rows = matched.subList(start, end)

    Map result
    if (format == "ids") {
        result = [deviceIds: rows.collect { it.id.toInteger() }, count: rows.size(), total: total]
    } else if (format == "context") {
        List attrNames = opts.attributeNames ?: _contextAttributeNames()
        List lines = []
        for (pageRec in rows) lines << _contextDeviceLine(pageRec, caps, attrNames, opts.redact)
        List header = _contextHeaderLines()
        String devicesLine = "Devices: ${lines.size()} of ${total}"
        if (end < total) devicesLine += " (nextCursor=${end} -- pass cursor:'${end}' for the next page)"
        header << devicesLine.toString()
        result = [mode: location.mode?.toString(), summary: (header + lines).join("\n"), count: lines.size(), total: total]
        def hsm = _safeHsmStatus()
        if (hsm) result.hsmStatus = hsm
        if (opts.attributeNames && rows && !rows.any { r -> attrNames.any { r.currentStates.get(it) != null } }) {
            result.attributeNamesMatchedNoAttributes = true
        }
    } else {
        List attrNames = opts.attributeNames ?: _contextAttributeNames()
        List devices = []
        boolean budgetCut = false
        for (int i = 0; i < rows.size(); i++) {
            Map rec = rows[i]
            Map fj = null
            if (opts.wantFull) {
                // Only per-device reads spend the request budget; the first row always lands.
                if (i > 0 && _timeBudgetExceeded(reqT0)) {
                    budgetCut = true
                    end = start + i
                    break
                }
                fj = _fetchDeviceFullJson(rec.id)
            }
            Map row = _listDeviceRecord(rec, opts, attrNames, caps, buttons, childIds, fj)
            if (scope == "all") row.mcpAuthorized = authorized == null || authorized.contains(rec.id)
            devices << row
        }
        result = [devices: devices, count: devices.size(), total: total]
        if (devices.any { it.metadataUnavailable }) result.partial = true
        if (budgetCut) {
            result.putAll(common)
            _applyListBudgetCut(result, start, end, total, paged ? pageSize : 0)
            return result
        }
    }
    if (scope == "all") {
        int authorizedCount = authorized == null ? total : matched.count { authorized.contains(it.id) }
        result.putAll([scope: "all", mcpAuthorizedCount: authorizedCount, unauthorizedCount: total - authorizedCount])
    }
    result.putAll(common)
    if (paged) {
        result.offset = start
        result.limit = pageSize
        result.hasMore = end < total
        if (end < total) result.nextCursor = end.toString()
    }
    return result
}

private Map _listDeviceRecord(Map rec, Map opts, List attrNames, Map caps, Map buttons, Set childIds, Map fj) {
    Set f = opts.fieldSet
    Map row = [id: rec.id]
    if (f.contains("label")) row.label = rec.label
    if (f.contains("name")) row.name = rec.name
    if (f.contains("driver")) row.driver = rec.driver
    if (f.contains("room")) row.room = rec.roomName
    if (f.contains("roomId")) row.roomId = rec.roomId
    if (f.contains("disabled")) row.disabled = rec.disabled
    if (f.contains("deviceNetworkId")) row.deviceNetworkId = rec.deviceNetworkId
    if (f.contains("lastActivity")) row.lastActivity = rec.lastActivity
    if (f.contains("protocol") && rec.protocol) row.protocol = rec.protocol
    if (f.contains("parentDeviceId") && rec.parentDeviceId) row.parentDeviceId = rec.parentDeviceId
    if (f.contains("mcpManaged") && childIds.contains(rec.id)) row.mcpManaged = true
    if (f.contains("linked") && rec.linked) row.linked = true
    if (f.contains("tags") && rec.tags) row.tags = rec.tags
    if (f.contains("notes")) row.notes = rec.notes
    if (f.contains("source")) row.source = rec.source
    for (key in ["orphan", "user", "defaultCurrentState", "meshEnabled", "homekit", "showOnHome", "dashboardTypes", "defaultIcon", "remoteDeviceUrl"]) {
        if (f.contains(key)) row.put(key, rec.get(key))
    }
    if (f.contains("currentStates")) {
        Map states = [:]
        for (an in attrNames) {
            def v = rec.currentStates.get(an)
            if (v != null) states.put(an, _listStateValue(an, v, opts.redact))
        }
        row.currentStates = states
    }
    boolean capsMissing = (f.contains("capabilities") || f.contains("buttonCount")) && caps != null && !caps.containsKey(rec.id)
    if (f.contains("capabilities") && caps != null && !capsMissing) row.capabilities = caps.get(rec.id)
    if (f.contains("buttonCount") && buttons?.get(rec.id) != null) row.buttonCount = buttons.get(rec.id)
    if (capsMissing) row.metadataUnavailable = true
    if (!opts.wantFull) return row
    Map detail = null
    try {
        if (fj != null) detail = _getDeviceFromFullJson(rec.id, fj)
    } catch (IllegalStateException ignored) {
        // Listed without the detail; the response says partial.
    }
    if (detail == null) {
        row.metadataUnavailable = true
        return row
    }
    if (f.contains("attributes")) {
        Map nativeStates = fj.device.currentStates
        row.attributes = detail.attributes.collect { a ->
            Map attr = a + [value: _listStateValue(a.name?.toString(), a.value, opts.redact)]
            def st = nativeStates.get(a.name)
            if (st instanceof Map && st.unit) attr.unit = st.unit
            attr
        }
    }
    if (f.contains("commands")) row.commands = detail.commands
    def data = fj.device.data
    row.driverNamespace = (data instanceof Map && data.mcpDriverNamespace) ? data.mcpDriverNamespace : fj.device.deviceTypeNamespace
    return row
}

private _listStateValue(String name, value, boolean redact) {
    return redact ? _devicePublicAttributeValue(name, value) : value
}

private Map _emptyDeviceList(String format, String message) {
    if (format == "ids") return [deviceIds: [], count: 0, total: 0, message: message]
    if (format != "context") return [devices: [], count: 0, total: 0, message: message]
    List header = _contextHeaderLines()
    header << "Devices: 0 of 0"
    Map result = [mode: location.mode?.toString(), summary: (header + [message]).join("\n"), count: 0, total: 0, message: message]
    def hsm = _safeHsmStatus()
    if (hsm) result.hsmStatus = hsm
    return result
}

// A legacy client has no request-to-request continuation, so a page whose per-device reads reach
// the request budget ends early and says where the next page starts.
private void _applyListBudgetCut(Map result, int startIndex, int endIndex, int totalCount, int limit) {
    result.offset = startIndex
    result.limit = limit
    result.hasMore = true
    result.nextCursor = endIndex.toString()
    result.budgetLimited = true
    String cut = ("Partial page: devices ${startIndex + 1}-${endIndex} of ${totalCount}. It stopped early to answer within the " +
        "request time limit and ${totalCount - endIndex} more device(s) remain. Repeat the call with cursor '${endIndex}' " +
        "(other arguments unchanged) to continue.").toString()
    result.note = result.note ? "${result.note} ${cut}".toString() : cut
}

private Date safeLastActivity(device) {
    if (device instanceof Map && device._nativeActivityParsed == true) return device._nativeLastActivity
    def parsed = null
    try {
        if (device.lastActivityTime != null) parsed = _parseSinceArg(device.lastActivityTime)
    } catch (Exception ignored) { }
    boolean unparseable = device.lastActivityTime != null && parsed == null
    if (unparseable) {
        mcpLog("error", "device", "Native lastActivityTime could not be parsed for device ${device.id}; activity is unavailable.")
    }
    if (device instanceof Map) {
        device._nativeActivityParsed = true
        device._nativeLastActivity = parsed
        if (unparseable) device._nativeActivityUnavailable = true
    }
    return parsed
}

// Default attribute set for summary currentStates and context lines: the common state-bearing
// attributes; a device shows only the ones it reports.
private List _contextAttributeNames() {
    ["switch", "level", "motion", "contact", "presence", "lock", "temperature", "humidity",
     "illuminance", "battery", "power", "energy", "thermostatMode", "thermostatOperatingState",
     "heatingSetpoint", "coolingSetpoint", "speed", "position", "valve", "water", "smoke"]
}

// A null mode renders as "unknown": the literal "Mode: null" would read as a mode named null.
private List _contextHeaderLines() {
    def header = ["Mode: ${location.mode ?: 'unknown'}".toString()]
    def hsm = _safeHsmStatus()
    if (hsm) header << "HSM: ${hsm}".toString()
    return header
}

// "- Label (id, room) - Cap1, Cap2; attr=value, ...", values as the Devices page shows them.
private String _contextDeviceLine(Map rec, Map caps, List attrNames, boolean redact) {
    List parts = []
    for (an in attrNames) {
        def v = rec.currentStates.get(an)
        if (v != null) parts << "${an}=${_listStateValue(an, v, redact)}".toString()
    }
    String room = rec.roomName ?: "No room"
    String line = "- ${rec.label} (${rec.id}, ${room})"
    List c = caps?.get(rec.id)
    if (c) line += " - ${c.join(', ')}"
    if (parts) line += "; ${parts.join(', ')}"
    return line.toString()
}

// location.hsmStatus is not on every Location model; unreadable means no HSM line.
private String _safeHsmStatus() {
    try {
        return location.hsmStatus?.toString()
    } catch (Exception ignore) {
        return null
    }
}

// The context resources' population. Throws IllegalStateException when the page cannot be read.
private List _contextResourceDevices() {
    Set ids = _mcpAuthorizedIds(getChildDevices() ?: [])
    if (ids != null && ids.isEmpty()) return []
    List page = _readDevicesPage()
    return ids == null ? page : page.findAll { ids.contains(it.id) }
}

// Budget for the unpaginated context RESOURCES (resources/read takes only a uri, so an
// oversized body would be permanently dead behind the outer -32603 guard with no
// narrower request to retry). Measured in ESCAPED-envelope characters via _escapedLen:
// the body rides inside the JSON-RPC envelope as a JSON string, where JsonOutput
// escapes quotes AND expands every non-ASCII character to a 6-char \\uXXXX sequence
// (escaped output is pure ASCII, so envelope chars == wire bytes). Budgeting the
// escaped form is what keeps a CJK/emoji-labelled inventory under the 124,000-byte
// wire guard, not just an ASCII one; 80,000 leaves ample envelope headroom.
def _contextResourceByteBudget() { 80000 }

// The cost of `s` once embedded in the serialized envelope: its JSON-escaped length
// minus the surrounding quotes JsonOutput adds.
private int _escapedLen(String s) { s == null ? 0 : groovy.json.JsonOutput.toJson(s).length() - 2 }

// The hubitat://context-summary resource body: the format='context' snapshot without
// cursor pagination, truncated at the resource byte budget with a pointer at the tool form.
def _buildContextSummaryText() {
    List records
    try { records = _contextResourceDevices() }
    catch (IllegalStateException e) { return "Context unavailable: the hub's device list (/hub2/devicesList) could not be read: ${e.message}".toString() }
    Map caps = records ? _capabilityFeedFor(records) : null
    boolean redact = _redactsCredentials()
    def attrNames = _contextAttributeNames()
    def lines = []
    int used = 0
    for (record in records) {
        def line = _contextDeviceLine(record, caps, attrNames, redact)
        lines << line
        used += _escapedLen(line) + 2
        if (used > _contextResourceByteBudget()) break
    }
    def header = _contextHeaderLines()
    header << "Devices: ${Math.min(lines.size(), records.size())} of ${records.size()}".toString()
    if (records && caps == null) header << "Capabilities unavailable: the hub's capability feed could not be read."
    if (!records) header << "No devices selected for MCP access and no MCP-managed virtual devices"
    return _truncateContextText((header + lines).join("\n"), records.size())
}

private String _truncateContextText(String text, int totalDevices) {
    int budget = _contextResourceByteBudget()
    if (text == null || _escapedLen(text) <= budget) return text
    def kept = []
    int size = 0
    int deviceLinesKept = 0
    // split("\n"), not readLines(): the established hub-safe idiom in this codebase.
    // Per-line cost is the ESCAPED length plus the escaped newline ("\\n" = 2 chars).
    for (ln in text.split("\n")) {
        int lnCost = _escapedLen(ln) + 2
        if (size + lnCost > budget) break
        kept << ln
        size += lnCost
        if (ln.startsWith("- ")) deviceLinesKept++
    }
    kept = kept.collect { it.startsWith('Devices: ') ? "Devices: ${deviceLinesKept} of ${totalDevices}".toString() : it }
    kept << "... truncated at ${deviceLinesKept} of ${totalDevices} devices (hub response-size cap). Use the hub_list_devices tool (format='context', cursor pagination) for the full inventory.".toString()
    return kept.join("\n")
}

// The hubitat://context resource body: current mode (+ HSM), the mode list, a rooms[] index with
// deviceIds, and one compact record per MCP-visible device with its attributes projected through
// the default context set (unfiltered states carry driver-internal rows that only add noise). The
// rooms index is capped at half the budget and device records take what remains.
def _buildContextJson() {
    List allDevices
    try { allDevices = _contextResourceDevices() }
    catch (IllegalStateException e) { return [success: false, isError: true, error: "The hub's device list (/hub2/devicesList) could not be read: ${e.message}".toString()] }
    Map caps = allDevices ? _capabilityFeedFor(allDevices) : null
    boolean redact = _redactsCredentials()
    def contextAttrs = _contextAttributeNames()
    def roomIndex = [:]
    for (d in allDevices) {
        String r = d.roomName ?: "No room"
        if (!roomIndex.containsKey(r)) roomIndex.put(r, [])
        roomIndex.get(r) << d.id
    }
    // On an extreme fleet the rooms index alone could exceed the whole budget.
    int roomsCap = (int) (_contextResourceByteBudget() / 2)
    int roomsUsed = 0
    boolean roomsTruncated = false
    def rooms = []
    for (entry in roomIndex) {
        Map room = [name: entry.key, deviceIds: entry.value]
        roomsUsed += _escapedLen(groovy.json.JsonOutput.toJson(room))
        if (roomsUsed > roomsCap) {
            roomsTruncated = true
            break
        }
        rooms << room
    }
    int budget = Math.max(0, _contextResourceByteBudget() - roomsUsed - 1000)
    int used = 0
    boolean truncated = false
    def devices = []
    for (d in allDevices) {
        Map attrs = [:]
        for (an in contextAttrs) {
            def v = d.currentStates.get(an)
            if (v != null) attrs.put(an, _listStateValue(an, v, redact))
        }
        Map rec = [id: d.id, label: d.label, room: d.roomName, capabilities: caps?.get(d.id) ?: [], attributes: attrs]
        if (caps == null) rec.capabilitiesUnavailable = true
        if (caps != null && !caps.containsKey(d.id)) rec.metadataUnavailable = true
        used += _escapedLen(groovy.json.JsonOutput.toJson(rec))
        if (used > budget) {
            truncated = true
            break
        }
        devices << rec
    }
    def result = [
        currentMode: location.mode?.toString(),
        modes: location.modes?.collect { it?.name?.toString() }?.findAll { it != null } ?: [],
        deviceCount: devices.size(),
        totalDevices: allDevices.size(),
        rooms: rooms,
        devices: devices
    ]
    if (allDevices && caps == null) result.partial = true
    if (truncated) {
        result.truncated = true
        result.note = "Device records truncated at ${devices.size()} of ${allDevices.size()} (hub response-size cap). Use the hub_list_devices tool (format='context', cursor pagination) for the full inventory.".toString()
    }
    if (roomsTruncated) {
        result.roomsTruncated = true
        result.note = "${result.note ?: ''} Rooms index truncated at ${rooms.size()} of ${roomIndex.size()} rooms (hub response-size cap); use hub_list_rooms for the full room list.".toString().trim()
    }
    def hsm = _safeHsmStatus()
    if (hsm) result.hsmStatus = hsm
    return result
}

// ==================== DEVICE-ALLOWLIST BYPASS ====================
// When the operator turns ON bypassDeviceAllowlist, device tools that would otherwise throw
// "Device not found" for a device outside settings.selectedDevices fall back to the hub's
// id-keyed admin endpoints, reaching ANY device on the hub. The toggle is independent of
// Developer Mode -- once on it works in normal operation. LISTED / MCP-managed devices ALWAYS
// use native endpoints too; the toggle changes authorization only.

// True when the operator enabled the device-allowlist bypass. Default OFF (null/unset == off).
private boolean _bypassEnabled() {
    return settings.bypassDeviceAllowlist == true
}

// Selection grants access; native HTTP execution does not grant it implicitly.
private boolean _requireDeviceToolAccess(deviceId) {
    _validateNativeDeviceId(deviceId)
    boolean listed = findDevice(deviceId) != null
    if (!listed && !_bypassEnabled()) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }
    return listed
}

private void _validateNativeDeviceId(deviceId) {
    if (deviceId == null || !(deviceId.toString() ==~ /[0-9]+/)) {
        throw new IllegalArgumentException('deviceId must contain only decimal digits from hub_list_devices')
    }
}

// Fetch + parse /device/fullJson/<id>. Returns the parsed Map ({device, commands, ...}) or null
// on a fetch/parse failure or a non-object body. The bypass fallbacks read device state, the
// attribute list, and the command set from this -- the Groovy device object is unavailable for
// an unlisted device (the device model is authorization-scoped).
private Map _fetchDeviceFullJson(deviceId) {
    _validateNativeDeviceId(deviceId)
    String stage = 'fetch'
    try {
        def txt = hubInternalGet("/device/fullJson/${deviceId}")
        if (!txt) {
            mcpLog("error", "device", "bypass: /device/fullJson/${deviceId} returned an empty response")
            return null
        }
        stage = 'parse'
        def parsed = new groovy.json.JsonSlurper().parseText(txt)
        if (!(parsed instanceof Map)) {
            mcpLog("error", "device", "bypass: /device/fullJson/${deviceId} returned a non-object JSON response")
            return null
        }
        if (!(parsed.device instanceof Map) || parsed.device.id?.toString() != deviceId.toString()) {
            mcpLog("error", "device", "native: /device/fullJson/${deviceId} did not identify the requested device")
            return null
        }
        return parsed
    } catch (Exception e) {
        // Parser and transport messages can contain native settings or response bodies.
        mcpLog("error", "device", "bypass: /device/fullJson/${deviceId} ${stage} failed (${e.class.simpleName})")
        return null
    }
}

// Fetch + parse /device/eventsJson/<id> (the event-history analogue of /device/fullJson for the
// allowlist bypass). Returns the parsed List of event Maps (newest-first, no query params) or
// null on a fetch/parse failure or a non-array body. Empty history is [] (a real list), distinct
// from null (the failure sentinel).
private List _fetchBypassDeviceEvents(deviceId) {
    try {
        def txt = hubInternalGet("/device/eventsJson/${deviceId}")
        def parsed = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        if (!(parsed instanceof List) || parsed.any { !(it instanceof Map) }) {
            mcpLog("warn", "device", "native: /device/eventsJson/${deviceId} returned an invalid event list or row")
            return null
        }
        return parsed
    } catch (Exception e) {
        mcpLog("warn", "device", "native: /device/eventsJson/${deviceId} fetch/parse failed: ${e.class.simpleName}")
        return null
    }
}

// The display label for a fullJson device (label, else name, else "Device <id>"). Single source
// for the bypass paths so the fallback label is consistent everywhere.
private String _bypassDeviceLabel(Map fj, deviceId) {
    return fj?.device?.label ?: fj?.device?.name ?: "Device ${deviceId}".toString()
}

// Map one /device/eventsJson row (description <- descriptionText; the ISO date string passes
// through). Single source for both the recent-N (toolGetDeviceEvents) and the windowed
// (_deviceHistoryBypass) branches. type/producedBy/triggered are added only when the hub supplies them.
private Map _mapBypassEventRow(evt) {
    // The description text repeats a credential value, so it is hidden with the value.
    boolean hidden = _redactsCredentials() && _deviceConfigurationSecretKey(evt.name)
    def row = [
        name: evt.name,
        value: hidden && evt.value != null ? _appConfigRedactedMarker() : evt.value,
        unit: evt.unit,
        description: hidden && evt.descriptionText != null ? _appConfigRedactedMarker() : evt.descriptionText,
        date: evt.date,
        isStateChange: evt.isStateChange
    ]
    if (evt.type != null) row.type = evt.type
    def producedBy = _parseEventProducer(evt.producedBy)
    if (producedBy) row.producedBy = producedBy
    def triggered = _parseEventTriggered(evt.triggered)
    if (triggered) row.triggered = triggered
    return row
}

// producedBy arrives as hub-rendered HTML: one <a> to /installedapp/configure/<id> or
// /device/edit/<id>, or bare text such as "Unknown app". The ids are typed to match what
// hub_list_device_events / hub_get_app_config accept (appId integer, deviceId string).
private Map _parseEventProducer(html) {
    def s = html?.toString()?.trim()
    if (!s) return null
    def m = s =~ /href=['"]\/(installedapp\/configure|device\/edit)\/(\d+)['"][^>]*>(.*?)<\/a>/
    if (m.find()) {
        def entry = [name: m.group(3).trim()]
        if (m.group(1) == 'device/edit') entry.deviceId = m.group(2)
        else entry.appId = m.group(2) as Integer
        return entry
    }
    def text = s.replaceAll(/<[^>]+>/, '').trim()
    return text ? [name: text] : null
}

// triggered is a hub-rendered <ul> with one <li> per app subscription the event fired:
// "<a href='/installedapp/configure/<id>'>Name</a> (handlerMethod)", or bare text ("Unknown app").
private List _parseEventTriggered(html) {
    def s = html?.toString()
    if (!s?.trim()) return null
    def items = []
    def li = s =~ /(?s)<li[^>]*>(.*?)<\/li>/
    while (li.find()) {
        def item = li.group(1)
        def a = item =~ /href=['"]\/installedapp\/configure\/(\d+)['"][^>]*>(.*?)<\/a>\s*(?:\((\w+)\))?/
        if (a.find()) {
            def entry = [name: a.group(2).trim(), appId: a.group(1) as Integer]
            if (a.group(3)) entry.handler = a.group(3)
            items << entry
        } else {
            def text = item.replaceAll(/<[^>]+>/, '').trim()
            if (text) items << [name: text]
        }
    }
    // A markup change must not silently drop the provenance: fall back to the stripped text.
    if (!items) {
        def text = s.replaceAll(/<[^>]+>/, ' ').replaceAll(/\s+/, ' ').trim()
        if (text) items << [name: text]
    }
    return items ?: null
}

// The attribute names a fullJson device exposes. currentStates is an OBJECT keyed by attribute
// name (each value carries value/dataType/unit/date), so its key set IS the device's attribute
// list -- what the bypass paths validate a requested attribute against. NOTE: fullJson lists only
// attributes that have REPORTED a value, so a declared-but-not-yet-reported attribute is absent
// here -- bypass attribute discovery is limited to reported attributes.
private List _fullJsonAttributeNames(Map fullJson) {
    def cs = fullJson?.device?.currentStates
    return (cs instanceof Map) ? new ArrayList(cs.keySet()) : []
}

// The command names a fullJson device exposes. Top-level `commands` is an array of
// {capability, name, arguments, parameters, relatedAttribute}; its names are the supported-command
// set the bypass send-command path validates against.
private List _fullJsonCommandNames(Map fullJson) {
    def cmds = fullJson?.commands
    return (cmds instanceof List) ? cmds.collect { it?.name }.findAll { it != null } : []
}

private List _unavailableNativeDeviceCollections(Map fullJson, List required = ['currentStates', 'capabilities', 'commands']) {
    // An empty native collection is valid; an absent or malformed collection cannot prove emptiness.
    def values = [currentStates: fullJson?.device?.currentStates,
                  capabilities: fullJson?.device?.capabilities, commands: fullJson?.commands]
    return required.findAll { key ->
        key == 'currentStates' ? !(values.get(key) instanceof Map) : !(values.get(key) instanceof List)
    }
}

private Map _normalizeDevicePreferenceValue(raw, String type, boolean multiple = false) {
    if (raw == null) return [valid: true, value: null]
    if (multiple) {
        if (raw == '') return [valid: true, value: []]
        if (raw instanceof List) return [valid: true, value: raw.collect { it?.toString() }]
        if (raw instanceof String) {
            if (raw.trim().startsWith('[')) {
                try {
                    def selection = new groovy.json.JsonSlurper().parseText(raw)
                    if (selection instanceof List) return [valid: true, value: selection.collect { it?.toString() }]
                } catch (Exception ignored) {
                    // Non-JSON option names still use the native comma-separated representation.
                }
            }
            return [valid: true, value: raw.split(',').collect { it.trim() }]
        }
        return [valid: false, value: null]
    }
    if (raw == '') return [valid: true, value: raw]
    if (type in ['bool', 'boolean']) {
        if (raw == true || raw?.toString() == 'true') return [valid: true, value: true]
        if (raw == false || raw?.toString() == 'false') return [valid: true, value: false]
        return [valid: false, value: null]
    }
    if (type in ['number', 'decimal']) {
        if (raw instanceof Number) return [valid: true, value: raw]
        if (raw instanceof String) {
            try { return [valid: true, value: new BigDecimal(raw.trim())] }
            catch (Exception ignored) { return [valid: false, value: null] }
        }
        return [valid: false, value: null]
    }
    return [valid: !(raw instanceof Map || raw instanceof List), value: raw?.toString()]
}

// Definitions and stored values are siblings of device in the current native response.
private Map _readDevicePreferenceModel(Map fullJson) {
    def definitions = fullJson?.get('settings')
    if (!(definitions instanceof List)) {
        return [status: 'unavailable', source: 'settings', entries: [],
                reason: 'Native device details did not contain a recognized top-level settings array.']
    }
    def model = [status: 'complete', source: 'settings', entries: [], writeSafe: true]
    def inputs = [:]
    def duplicates = []
    def rawInputs = fullJson.get('inputValues')
    if (rawInputs != null && !(rawInputs instanceof List)) {
        model.writeSafe = false
        model.status = 'partial'
        model.reason = 'Native inputValues is not a recognized array; stored values may be incomplete.'
    } else {
        (rawInputs ?: []).each { row ->
            if (!(row instanceof Map) || row.name == null) {
                model.writeSafe = false
                model.status = 'partial'
                model.reason = 'Native inputValues contains an unnamed or malformed value.'
            } else {
                String key = row.name.toString()
                if (inputs.containsKey(key)) duplicates << key
                inputs.put(key, row)
            }
        }
    }
    def names = []
    definitions.each { row ->
        if (!(row instanceof Map) || row.name == null || row.type == null) {
            model.writeSafe = false
            model.status = 'partial'
            model.reason = 'Native settings contains an unnamed or malformed declaration.'
            return
        }
        String name = row.name.toString()
        String type = row.type.toString()
        if (names.contains(name)) {
            model.writeSafe = false
            model.status = 'partial'
            model.reason = 'Native settings contains duplicate preference names.'
            model.entries.removeAll { it.name == name }
            return
        }
        names << name
        if (type in ['paragraph', 'hidden', 'button', 'image']) return
        def input = inputs.get(name)
        // Cleared native settings lose their storage identity; inputValues can still prefill the UI default.
        boolean nativeUnset = row.containsKey('id') && row.get('id') == null &&
            row.containsKey('deviceId') && row.get('deviceId') == null && row.containsKey('value') && row.get('value') == null
        boolean present = !nativeUnset && ((input instanceof Map && input.containsKey('inputValue')) || row.containsKey('value'))
        def raw = nativeUnset ? null : ((input instanceof Map && input.containsKey('inputValue')) ? input.get('inputValue') : row.get('value'))
        // Unset enum rows lose stored cardinality even when the driver declares multiple:true.
        Boolean multiple = type == 'enum' && nativeUnset && !_deviceFlag(row.multiple) ? null : _deviceFlag(row.multiple)
        def normalized = _normalizeDevicePreferenceValue(raw, type, multiple == true)
        def entry = [name: name, type: type, declared: true, multiple: multiple,
                     valuePresent: present, valueStatus: present ? 'stored' : 'unset',
                     rawValue: raw, value: normalized.value]
        if (multiple == null) {
            entry.multipleStatus = 'unavailable'
            entry.multipleReason = 'Native unset enum metadata does not identify single or multiple selection. Check driverSource or previously read metadata and pass an explicit multiple boolean when setting it.'
        }
        ['title', 'description', 'options', 'range', 'required'].each { key ->
            if (row.containsKey(key)) entry.put(key, row.get(key))
        }
        if (row.containsKey('defaultValue')) {
            def defaultValue = _normalizeDevicePreferenceValue(row.get('defaultValue'), type, multiple == true)
            entry.defaultValue = defaultValue.valid ? defaultValue.value : row.get('defaultValue')
        }
        if (!normalized.valid || duplicates.contains(name)) {
            model.writeSafe = false
            entry.valueStatus = duplicates.contains(name) ? 'unknown' : 'invalid'
            entry.value = null
            model.status = 'partial'
            model.reason = 'A stored preference value is ambiguous or does not match its declared type.'
        }
        model.entries << entry
    }
    if (duplicates || inputs.keySet().any { key -> !names.contains(key) }) {
        if (duplicates) model.writeSafe = false
        model.status = 'partial'
        model.reason = 'Native inputValues contains duplicate or undeclared preference names.'
    }
    return model
}

private Map _lookupDevicePreference(Map model, name) {
    return model?.entries?.find { it.name == name?.toString() }
}

private boolean _devicePreferenceIsSecret(Map entry) {
    return _redactsCredentials() && (entry?.type?.toString() == 'password' || _deviceConfigurationSecretKey(entry?.name))
}

private boolean _deviceConfigurationSecretKey(key) {
    String normalized = key?.toString()?.toLowerCase()?.replaceAll(/[^a-z0-9]/, '') ?: ''
    return normalized.contains('password') || normalized.contains('token') || normalized.contains('secret') ||
        normalized.contains('credential') || normalized.contains('apikey') || normalized.contains('privatekey') ||
        normalized.contains('accesskey') || normalized in ['psk', 'psw'] || _appConfigSecretName(key)
}

private _deviceConfigurationPublicValue(value, key = '') {
    if (!_redactsCredentials()) return value
    if (_deviceConfigurationSecretKey(key)) return _appConfigRedactedMarker()
    if (value instanceof Map) {
        def copy = [:]
        value.each { k, v -> copy.put(k, _deviceConfigurationPublicValue(v, k)) }
        return copy
    }
    if (value instanceof List) return value.collect { _deviceConfigurationPublicValue(it) }
    return value
}

// Z-Wave Alliance manufacturer IDs. Canonical form is hex (e.g. 0x027A = Zooz), but Hubitat
// surfaces device.data.manufacturer as a bare DECIMAL string ("634" = 0x027A). Curated set of
// common home-automation brands, keyed by the integer id. Runtime data in a helper -- it does
// not touch the flat tool-catalog budget. An unlisted id resolves to null (no name invented).
// Returned via a getter because the Hubitat sandbox rejects `private static final` at script scope.
private Map getZwaveManufacturers() {
    return [
        0x0000: 'Silicon Labs', 0x000C: 'HomeSeer', 0x001D: 'Leviton', 0x0039: 'Honeywell',
        0x003B: 'Schlage', 0x0060: 'Everspring', 0x0063: 'GE/Jasco', 0x0086: 'Aeotec',
        0x0090: 'Kwikset', 0x0109: 'Vision Security', 0x010F: 'Fibaro', 0x0129: 'Yale',
        0x0131: 'Zipato', 0x013C: 'Philio', 0x0138: 'First Alert', 0x014A: 'Ecolink',
        0x014F: 'GoControl/Linear', 0x0154: 'POPP', 0x0159: 'Qubino', 0x0184: 'Dragon Tech',
        0x0234: 'Logic Group', 0x0258: 'NEO Coolcam', 0x027A: 'Zooz', 0x031E: 'Inovelli',
        0x0346: 'Ring', 0x0371: 'Aeotec', 0x0460: 'Shelly'
    ]
}

// Resolve a Z-Wave manufacturer id to a brand name, or null when it is unknown or not a bare
// numeric id (an already-resolved brand string is left for the caller to keep untouched).
// Handles the Hubitat decimal wire form ("634") and "0x"-prefixed hex ("0x027A"). A bare,
// unprefixed string is read as hex ONLY when it contains an a-f digit ("027A"); an all-digit
// string ("0063") is read as decimal, not hex.
private String _zwaveManufacturerName(id) {
    if (id == null) return null
    String s = id.toString().trim()
    if (!s) return null
    Integer code = null
    try {
        if (s.length() > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) code = Integer.parseInt(s.substring(2), 16)
        else if (s ==~ /(?i)[0-9a-f]*[a-f][0-9a-f]*/) code = Integer.parseInt(s, 16)
        else if (s ==~ /\d+/) code = Integer.parseInt(s, 10)
        else return null
    } catch (Exception ignored) {
        return null
    }
    return getZwaveManufacturers()[code]
}

// Add a resolved manufacturerName beside a bare numeric manufacturer id in a device data map,
// preserving the raw value for chaining. Non-maps, missing/name-form manufacturer values, and
// unknown ids pass through unchanged; an existing manufacturerName is never overwritten. The id
// table is Z-Wave Alliance, so resolve only when the data carries a Z-Wave marker -- otherwise a
// non-Z-Wave device whose data happens to hold a numeric 'manufacturer' (e.g. "0") is mislabelled.
private _withResolvedManufacturer(value) {
    if (!(value instanceof Map) || !value.containsKey('manufacturer') || value.containsKey('manufacturerName')) return value
    if (!value.containsKey('zwNodeInfo') && !value.containsKey('inClusters')) return value
    String name = _zwaveManufacturerName(value.get('manufacturer'))
    if (name == null) return value
    def copy = [:]
    value.each { k, v ->
        copy.put(k, v)
        if (k == 'manufacturer') copy.put('manufacturerName', name)
    }
    return copy
}

private Map _publicDevicePreference(Map entry) {
    def copy = [:]
    entry.each { key, value ->
        if (!(key in ['rawValue', 'declared'])) copy.put(key, _deviceConfigurationPublicValue(value))
    }
    if (_devicePreferenceIsSecret(entry)) {
        if (copy.containsKey('value')) copy.value = _appConfigRedactedMarker()
        if (copy.containsKey('defaultValue')) copy.defaultValue = _appConfigRedactedMarker()
        copy.redacted = true
    }
    copy.writable = !(entry.valueStatus in ['unknown', 'invalid'])
    return copy
}

private Map _deviceConfigurationProjection(Map source, List keys) {
    def result = [:]
    keys.each { key ->
        if (source?.containsKey(key)) result.put(key, _deviceConfigurationPublicValue(source.get(key), key))
    }
    return result
}

// One entry per _deviceEditFields() entry, so the listed fields and the accepted ones cannot drift.
private List _deviceConfigurationEditableFields(Map fj) {
    Map d = (fj?.device instanceof Map) ? fj.device : [:]
    def fields = []
    for (spec in _deviceEditFields()) {
        String name = spec.key
        def field = [name: name, type: spec.value.type]
        if (spec.value.minimum != null && spec.value.type == 'integer') field.minimum = spec.value.minimum
        if (spec.value.maximum != null) field.maximum = spec.value.maximum
        if (spec.value.confirm) field.requiresConfirmation = true
        String unavailable = d ? _deviceEditUnavailable(name, fj) : 'The native device details could not be read.'
        field.writable = unavailable == null
        if (unavailable) field.reason = unavailable
        if (d) field.value = _deviceConfigurationPublicValue(_deviceEditCurrentValue(name, fj), name)
        if (name == 'room') field.optionsReference = [gateway: 'hub_read_rooms', tool: 'hub_list_rooms', args: [:]]
        if (name == 'deviceTypeId') field.optionsReference = [gateway: 'hub_read_apps_code', tool: 'hub_list_drivers', args: [include: 'all']]
        if (name == 'dashboardIds' && fj?.dashboards instanceof List) field.options = fj.dashboards.collect { [id: it.id, name: it.name] }
        if (name == 'defaultCurrentState') field.options = [''] + _fullJsonAttributeNames(fj)
        if (name == 'deviceNetworkId' && _deviceFlag(d.linkedDevice)) _addLinkedDeviceOptions(field, d.id)
        fields << field
    }
    return fields
}

private void _addLinkedDeviceOptions(Map field, deviceId) {
    field.options = [[value: '0', label: 'Keep current device link']]
    try {
        def text = hubInternalGet('/device/accessibleLinkedDevices')
        def available = text ? new groovy.json.JsonSlurper().parseText(text) : null
        if (!(available?.devices instanceof List)) throw new IllegalStateException('Unrecognized linked-device choices')
        for (row in available.devices) {
            if (row instanceof Map && row.hubId != null && row.deviceId != null) {
                field.options << [value: "${row.hubId}-${row.deviceId}".toString(),
                    label: row.label ?: row.name ?: row.deviceId.toString(), disabled: _deviceFlag(row.linkedLocally)]
            }
        }
    } catch (Exception ignored) {
        mcpLog('error', 'device', "Device ${deviceId}: /device/accessibleLinkedDevices choices could not be read; retry configuration discovery.")
        field.optionsStatus = 'unavailable'
        field.reason = 'Native linked-device choices could not be read; retry configuration discovery before retargeting.'
    }
}

private Map _deviceConfigurationDriverSource(Map d, deviceId) {
    def lookup = [gateway: 'hub_read_apps_code', tool: 'hub_list_drivers', args: [include: 'user']]
    if (d.systemDeviceType == true || (d.driverType ?: d.deviceTypeType) in ['sys', 'system']) {
        return [status: 'unavailable', reason: 'Built-in driver source is not exposed by the user driver-code endpoint.', lookup: lookup]
    }
    if ((d.driverType ?: d.deviceTypeType) != 'usr' || !d.deviceTypeName || !d.deviceTypeNamespace) {
        return [status: 'unresolved', reason: 'Native device details did not identify a resolvable user driver.', lookup: lookup]
    }
    try {
        def text = hubInternalGet('/hub2/userDeviceTypes')
        def catalog = text ? new groovy.json.JsonSlurper().parseText(text) : null
        if (!(catalog instanceof List)) throw new IllegalStateException('Unrecognized user driver catalog')
        def matches = catalog.findAll { row -> row instanceof Map && row.name == d.deviceTypeName && row.namespace == d.deviceTypeNamespace }
        if (matches.size() > 1) {
            matches = matches.findAll { row ->
                row.usedBy instanceof List && row.usedBy.any { use ->
                    use instanceof Map && use.id != null && deviceId != null && use.id.toString() == deviceId.toString()
                }
            }
        }
        if (matches.size() == 1 && matches[0].id != null) {
            return [status: 'available', gateway: 'hub_read_apps_code', tool: 'hub_get_source',
                    args: [type: 'driver', id: matches[0].id.toString()]]
        }
    } catch (Exception ignored) {
        mcpLog('error', 'device', "Device ${deviceId}: /hub2/userDeviceTypes catalog could not be read; retry driver discovery.")
        return [status: 'unavailable', reason: 'The user driver catalog could not be read. Retry driver discovery.', lookup: lookup]
    }
    return [status: 'unresolved', reason: 'No unique user driver-code entry matched the native driver name and namespace.', lookup: lookup]
}

private Map _deviceConfigurationInfo(Map fj) {
    Map d = fj?.device instanceof Map ? fj.device : [:]
    def info = _deviceConfigurationProjection(d, ['disabled', 'showOnHome', 'deviceNetworkId', 'roomId', 'roomName',
        'parentDeviceId', 'parentAppId', 'isComponent', 'linkedDevice', 'lastActivityTime', 'defaultCurrentState',
        'tags', 'notes', 'maxEvents', 'maxStates', 'spammyThreshold', 'defaultIcon', 'retryEnabled',
        'meshEnabled', 'meshFullSync', 'createTime', 'updateTime', 'version'])
    info.driver = [name: d.deviceTypeName, namespace: d.deviceTypeNamespace, deviceTypeId: d.deviceTypeId,
                   type: d.driverType ?: d.deviceTypeType, readableType: d.deviceTypeReadableType,
                   system: d.systemDeviceType]
    return info
}

private Map _deviceConfigurationResult(deviceId, Map identity, Map fj, fields = null) {
    def model = _readDevicePreferenceModel(fj)
    boolean linkedDevice = (fj?.device instanceof Map) && _deviceFlag(fj.device.linkedDevice)
    def read = [status: model.status, source: "/device/fullJson/${deviceId}".toString()]
    if (model.reason) read.reason = model.reason
    boolean available = fj?.device instanceof Map && !fj.device.isEmpty()
    def infoRead = [status: available ? 'complete' : 'unavailable', source: "/device/fullJson/${deviceId}".toString()]
    if (!available) infoRead.reason = 'Native device information could not be fetched or recognized.'
    def result = [id: deviceId.toString(), name: identity.name, label: identity.label, mode: 'configuration',
            editableFields: _deviceConfigurationEditableFields(fj),
            preferences: model.entries.collect {
                def entry = _publicDevicePreference(it)
                entry.writable = !linkedDevice && model.writeSafe == true && entry.writable
                if (linkedDevice) {
                    entry.applicable = false
                    entry.reason = 'Driver preferences cannot be saved on a linked device; edit the source device.'
                }
                entry
            }, preferenceRead: read,
            deviceInfo: _deviceConfigurationInfo(fj), deviceInfoRead: infoRead,
            driverSource: _deviceConfigurationDriverSource(available ? fj.device : [:], deviceId)]
    if (fields != null) {
        result.availableFields = [editableFields: result.editableFields.collect { it.name },
                                  preferences: result.preferences.collect { it.name },
                                  deviceInfo: result.deviceInfo.keySet().toList()]
        result.editableFields = result.editableFields.findAll { fields.contains(it.name) }
        result.preferences = result.preferences.findAll { fields.contains(it.name) }
        result.deviceInfo = _deviceConfigurationProjection(result.deviceInfo, fields)
    }
    return result
}

// Read one attribute's current value from a fullJson device model (currentStates keyed by name).
// Returns the String value or null when the attribute has not reported.
private _nativeDeviceStateValue(entry) {
    if (!(entry instanceof Map)) return entry
    if (entry.dataType?.toString()?.toUpperCase() == 'NUMBER') {
        if (entry.numberValue instanceof Number) return entry.numberValue
        def numeric = _parseBigDecimalOrNull(entry.value)
        if (numeric != null) return numeric
    }
    return entry.value
}

private _readBypassAttrValueFrom(Map fullJson, attribute) {
    def st = fullJson?.device?.currentStates
    if (!(st instanceof Map)) return null
    def entry = st.get(attribute)
    return _nativeDeviceStateValue(entry)
}

// A credential-named attribute's value as a read returns it: masked while credentials are hidden,
// when a wait on one is refused instead.
private _devicePublicAttributeValue(name, value) {
    return value != null && _redactsCredentials() && _deviceConfigurationSecretKey(name) ? _appConfigRedactedMarker() : value
}

private List _nativeReportedDeviceAttributes(Map d) {
    def attributes = []
    def cs = d?.currentStates
    if (cs instanceof Map) {
        cs.each { name, st ->
            if (name != null) {
                def dataType = (st instanceof Map) ? st.dataType?.toString() : null
                def value = _devicePublicAttributeValue(name, _nativeDeviceStateValue(st))
                attributes << [name: name, dataType: dataType, value: value]
            }
        }
    }
    return attributes
}

// Preserve the summary shape using reported native states and native command definitions.
private Map _getDeviceFromFullJson(deviceId, Map fj) {
    def unavailable = _unavailableNativeDeviceCollections(fj)
    if (unavailable) {
        throw new IllegalStateException("Native device collections are unavailable for device ${deviceId}: ${unavailable.join(', ')}.")
    }
    def d = fj.device
    def commands = []
    if (fj.commands instanceof List) {
        fj.commands.each { c ->
            if (c?.name != null) commands << [name: c.name, arguments: _fullJsonCommandArgs(c)]
        }
    }
    def caps = (d.capabilities instanceof List) ? d.capabilities.collect { (it instanceof Map) ? it.name : it } : []
    return [
        id: deviceId.toString(),
        name: d.name,
        label: d.label ?: d.name,
        room: d.roomName,
        capabilities: caps,
        attributes: _nativeReportedDeviceAttributes(d),
        commands: commands
    ]
}

// Map a fullJson command's declared arguments to the listed-device {name, type} shape, or null
// when it declares none. fullJson exposes arg metadata as `parameters` (typed maps) and/or
// `arguments` (type tokens) -- prefer the richer `parameters` when present.
private _fullJsonCommandArgs(c) {
    if (c?.parameters instanceof List && !c.parameters.isEmpty()) {
        return c.parameters.collect { p ->
            (p instanceof Map) ? [name: (p.name ?: "arg"), type: (p.type ?: "unknown")?.toString()]
                               : [name: p?.toString(), type: "unknown"]
        }
    }
    if (c?.arguments instanceof List && !c.arguments.isEmpty()) {
        return c.arguments.collect { a -> [name: "arg", type: a?.toString()] }
    }
    return null
}

private List _deviceDetailSections() {
    return ['configuration', 'identity', 'attributes', 'commands', 'data', 'state', 'relationships', 'jobs', 'integrations', 'metadata']
}

private Map _deviceDetailSourceCoverage(Map fj) {
    def rootKeys = ['device', 'settings', 'inputValues', 'commands', 'deviceState', 'scheduledJobs',
        'parentApp', 'childDevices', 'hasChildren', 'appsUsing', 'appsUsingCount', 'appsUsingForDialog',
        'appsUsingForDialogMore', 'amazonAlexaEnabled', 'amazonAlexaInstalled', 'amazonAlexaSupported',
        'googleHomeEnabled', 'googleHomeInstalled', 'googleHomeSupported', 'homeKitEnabled',
        'homeKitSelectionEnabled', 'dashboards', 'hasDashboards', 'dashboardTypes',
        'commandRetrySelectionEnabled', 'hubMeshRefreshEnabled', 'showInstructionSearchLink',
        'extraBreadcrumb', 'virtualFirst', 'tags']
    def deviceKeys = ['id', 'deviceId', 'name', 'label', 'displayName', 'deviceNetworkId', 'zigbeeId',
        'lanId', 'endpointId', 'network', 'controllerType', 'roomId', 'roomName', 'roomAssigned',
        'hubId', 'hubName', 'locationId', 'locationName', 'groupId', 'groupName', 'virtual', 'isComponent',
        'disabled', 'status', 'orphan', 'createTime', 'updateTime', 'lastActivityTime', 'version',
        'capabilities', 'currentStates', 'displayAttributes', 'defaultCurrentState', 'data', 'dataJson',
        'parentDeviceId', 'parentAppId', 'displayAsChild', 'compatibleDeviceId', 'linkedDevice',
        'linkedAndDisabled', 'linkedLocally', 'meshEnabled', 'meshFullSync', 'meshSelectionEnabled',
        'retryEnabled', 'retryAvailable', 'homeKitCompatible', 'remoteDeviceUrl', 'ZWave', 'zigbee',
        'matter', 'bluetooth', 'notes', 'tags', 'maxEvents', 'maxStates', 'spammyThreshold', 'defaultIcon',
        'icon', 'showOnHome', 'deviceTypeClassLocation', 'deviceTypePopulated', 'deviceTypeSingleThreaded',
        'deviceTypeReadableType', 'deviceTypeType', 'driverType', 'systemDeviceType', 'deviceTypeId',
        'deviceTypeName', 'deviceTypeNamespace']
    def unknownRoot = fj?.keySet()?.findAll { !rootKeys.contains(it) }?.toList() ?: []
    def unknownDevice = fj?.device instanceof Map ? fj.device.keySet().findAll { !deviceKeys.contains(it) }.toList() : []
    return [status: fj?.device instanceof Map ? (unknownRoot || unknownDevice ? 'partial' : 'complete') : 'unavailable',
            unmappedRootFields: unknownRoot, unmappedDeviceFields: unknownDevice,
            representedSeparately: [settings: 'configuration.preferences', inputValues: 'configuration.preferences',
                                   'device.dataJson': 'data (parsed native data map; raw duplicate omitted)']]
}

private Map _deviceDetailReadStatus(String section, Map fj, Map d, value, deviceId) {
    def status = [status: 'complete', source: "/device/fullJson/${deviceId}".toString()]
    if (d.isEmpty()) return status + [status: 'unavailable', reason: 'Native device details could not be fetched or recognized.']
    if (section == 'configuration') {
        return status + [status: value.preferenceRead.status] + (value.preferenceRead.reason ? [reason: value.preferenceRead.reason] : [:])
    }
    def contracts = [
        identity: [device: [id: 'scalar', name: 'scalar', label: 'scalar', deviceTypeId: 'scalar']],
        attributes: [device: [capabilities: 'list', currentStates: 'map', displayAttributes: 'list', defaultCurrentState: 'scalar']],
        commands: [root: [commands: 'list']], data: [device: [data: 'map']],
        state: [root: [deviceState: 'map']], jobs: [root: [scheduledJobs: 'list']],
        relationships: [root: [parentApp: 'map', childDevices: 'map', hasChildren: 'scalar',
            appsUsing: 'list', appsUsingCount: 'scalar', appsUsingForDialog: 'list', appsUsingForDialogMore: 'scalar']],
        integrations: [root: [amazonAlexaEnabled: 'scalar', amazonAlexaInstalled: 'scalar', amazonAlexaSupported: 'scalar',
            googleHomeEnabled: 'scalar', googleHomeInstalled: 'scalar', googleHomeSupported: 'scalar',
            homeKitEnabled: 'scalar', homeKitSelectionEnabled: 'scalar', dashboards: 'list', hasDashboards: 'scalar',
            dashboardTypes: 'list', commandRetrySelectionEnabled: 'scalar', hubMeshRefreshEnabled: 'scalar']],
        metadata: [device: [notes: 'scalar', tags: 'stringOrList', maxEvents: 'scalar', maxStates: 'scalar',
            spammyThreshold: 'scalar', defaultIcon: 'scalar', showOnHome: 'scalar']]
    ]
    def problems = []
    contracts.get(section)?.each { scope, definitions ->
        Map source = scope == 'device' ? d : fj
        definitions.each { key, shape ->
            def nativeValue = source.get(key)
            boolean valid = nativeValue == null || (shape == 'map' ? nativeValue instanceof Map :
                shape == 'list' ? nativeValue instanceof List : shape == 'stringOrList' ? nativeValue instanceof String || nativeValue instanceof List :
                !(nativeValue instanceof Map || nativeValue instanceof List))
            if (!source.containsKey(key) || !valid) problems << "${scope}.${key}".toString()
        }
    }
    if (problems) return status + [status: 'partial', reason: 'Native section fields are missing or malformed.', incompleteFields: problems]
    return status
}

private List _deviceDetailFieldNames(String section, value) {
    if (value instanceof Map) {
        def names = value.keySet().toList()
        if (section == 'attributes' && value.currentStates instanceof Map) names.addAll(value.currentStates.keySet())
        if (section == 'attributes' && value.declaredAttributes instanceof List) names.addAll(value.declaredAttributes.collect { it.name })
        return names.findAll { it != null }.unique()
    }
    if (value instanceof List) {
        def names = []
        value.eachWithIndex { row, index -> names << index.toString() }
        return names
    }
    return []
}

private _deviceDetailSelectedFields(String section, value, List fields) {
    if (value instanceof Map) {
        def selected = _deviceConfigurationProjection(value, fields)
        if (section == 'attributes') {
            if (value.currentStates instanceof Map && !fields.contains('currentStates')) {
                def states = _deviceConfigurationProjection(value.currentStates, fields)
                if (states) selected.currentStates = states
            }
            if (value.declaredAttributes instanceof List && !fields.contains('declaredAttributes')) {
                def attributes = value.declaredAttributes.findAll { fields.contains(it.name) }
                if (attributes) selected.declaredAttributes = attributes
            }
        }
        return selected
    }
    if (value instanceof List) {
        def selected = []
        value.eachWithIndex { row, index -> if (fields.contains(index.toString())) selected << row }
        return selected
    }
    return value
}

private Map _deviceExpandedResult(deviceId, Map identity, Map fj, String mode, sections, fields = null) {
    if (mode == 'configuration') return _deviceConfigurationResult(deviceId, identity, fj, fields)
    Map d = fj?.device instanceof Map ? fj.device : [:]
    def selected = sections == null ? _deviceDetailSections() : sections
    def result = [id: deviceId.toString(), name: identity.name, label: identity.label, mode: 'details',
                  sections: [:], sectionRead: [:], availableSections: _deviceDetailSections(),
                  sourceCoverage: _deviceDetailSourceCoverage(fj),
                  references: [events: [gateway: 'hub_read_devices', tool: 'hub_list_device_events',
                                       args: [deviceId: deviceId.toString(), limit: 50]],
                               dependents: [gateway: 'hub_read_apps_code', tool: 'hub_list_device_dependents',
                                            args: [deviceId: deviceId.toString()]]]]
    selected.each { section ->
        def value
        def keys = []
        switch (section) {
            case 'configuration':
                value = _deviceConfigurationResult(deviceId, identity, fj, fields)
                break
            case 'identity':
                value = _deviceConfigurationProjection(d, ['id', 'deviceId', 'name', 'label', 'displayName',
                    'deviceNetworkId', 'zigbeeId', 'lanId', 'endpointId', 'network', 'controllerType',
                    'roomId', 'roomName', 'roomAssigned', 'hubId', 'hubName', 'locationId', 'locationName',
                    'groupId', 'groupName', 'virtual', 'isComponent', 'disabled', 'status', 'orphan',
                    'createTime', 'updateTime', 'lastActivityTime', 'version'])
                value.driver = _deviceConfigurationInfo(fj).driver
                break
            case 'attributes':
                value = _deviceConfigurationProjection(d, ['capabilities', 'currentStates', 'displayAttributes', 'defaultCurrentState'])
                // Keep the historical key while identifying its native, reported-state coverage.
                value.attributeCoverage = [source: 'device.currentStates', declarationsComplete: false,
                    note: 'Unset or cleared attributes can be absent; absence does not establish an unsupported attribute.']
                if (d.currentStates instanceof Map) value.declaredAttributes = _nativeReportedDeviceAttributes(d).collect { _deviceConfigurationPublicValue(it) }
                break
            case 'commands':
                value = _deviceConfigurationPublicValue(fj?.commands)
                break
            case 'data':
                value = _withResolvedManufacturer(_deviceConfigurationPublicValue(d.get('data')))
                break
            case 'state':
                value = _deviceConfigurationPublicValue(fj?.deviceState)
                break
            case 'relationships':
                keys = ['parentApp', 'childDevices', 'hasChildren', 'appsUsing', 'appsUsingCount', 'appsUsingForDialog', 'appsUsingForDialogMore']
                value = _deviceConfigurationProjection(fj, keys)
                value.device = _deviceConfigurationProjection(d, ['parentDeviceId', 'parentAppId', 'displayAsChild', 'compatibleDeviceId', 'linkedDevice', 'linkedAndDisabled'])
                break
            case 'jobs':
                value = _deviceConfigurationPublicValue(fj?.scheduledJobs)
                break
            case 'integrations':
                keys = ['amazonAlexaEnabled', 'amazonAlexaInstalled', 'amazonAlexaSupported', 'googleHomeEnabled',
                    'googleHomeInstalled', 'googleHomeSupported', 'homeKitEnabled', 'homeKitSelectionEnabled',
                    'dashboards', 'hasDashboards', 'dashboardTypes', 'commandRetrySelectionEnabled', 'hubMeshRefreshEnabled']
                value = _deviceConfigurationProjection(fj, keys)
                value.device = _deviceConfigurationProjection(d, ['meshEnabled', 'meshFullSync', 'meshSelectionEnabled',
                    'retryEnabled', 'retryAvailable', 'homeKitCompatible', 'remoteDeviceUrl', 'linkedDevice',
                    'linkedAndDisabled', 'linkedLocally', 'ZWave', 'zigbee', 'matter', 'bluetooth'])
                break
            case 'metadata':
                value = _deviceConfigurationProjection(d, ['notes', 'tags', 'maxEvents', 'maxStates', 'spammyThreshold',
                    'defaultIcon', 'icon', 'showOnHome', 'deviceTypeClassLocation', 'deviceTypePopulated',
                    'deviceTypeSingleThreaded', 'deviceTypeReadableType', 'deviceTypeType', 'driverType',
                    'systemDeviceType', 'deviceTypeId', 'deviceTypeName', 'deviceTypeNamespace'])
                value.ui = _deviceConfigurationProjection(fj, ['showInstructionSearchLink', 'extraBreadcrumb', 'virtualFirst', 'tags'])
                break
        }
        result.sectionRead.put(section, _deviceDetailReadStatus(section, fj, d, value, deviceId))
        if (fields != null && section != 'configuration') {
            if (!result.containsKey('availableFields')) result.availableFields = [:]
            result.availableFields.put(section, _deviceDetailFieldNames(section, value))
            value = _deviceDetailSelectedFields(section, value, fields)
        }
        result.sections.put(section, value)
    }
    return result
}

private Map _deviceReadFragment(Map snapshot, String token, int start) {
    String serialized = snapshot.content
    int end = Math.min(start + 18000, serialized.length())
    return [id: snapshot.id, mode: snapshot.mode, contentFormat: 'json-fragment',
            content: serialized.substring(start, end), offset: start, totalCharacters: serialized.length(),
            nextCursor: end < serialized.length() ? "v2:${token}:${end}".toString() : null,
            note: 'Concatenate content fragments in order, then parse the joined JSON. Repeat with nextCursor and unchanged arguments within five minutes; fields=[] discovers smaller selections.']
}

private String _deviceReadSelection(deviceId, String mode, sections, fields) {
    return _mrtrSha256(groovy.json.JsonOutput.toJson([app?.id?.toString(), callerClient?.id?.toString(), deviceId.toString(), mode, sections, fields]))
}

private Map _deviceReadContinuation(String cursor, String selection) {
    if (!(cursor ==~ /v2:[a-f0-9-]{36}:[0-9]+/)) {
        throw new IllegalArgumentException('cursor must be a prior hub_get_device nextCursor; keep the same mode, sections and fields.')
    }
    def parts = cursor.split(':')
    Map snapshot
    synchronized (DEVICE_READ_SNAPSHOTS) {
        snapshot = DEVICE_READ_SNAPSHOTS.get(parts[1])
        if (snapshot && now() - (snapshot.at as Long) >= 300000L) {
            DEVICE_READ_SNAPSHOTS.remove(parts[1])
            snapshot = null
        }
    }
    if (!snapshot) throw new IllegalArgumentException('Device read snapshot expired or was evicted. Restart without cursor, or select fewer fields.')
    if (snapshot.selection != selection) throw new IllegalArgumentException('Device or selection changed. Restart without cursor and keep the same arguments for subsequent pages.')
    int start = _parseListCursor(parts[2], snapshot.content.length(), 'hub_get_device')
    return _deviceReadFragment(snapshot, parts[1], start)
}

// A lower bound, not an encoded size: stop before copying obviously oversized strings
// into the JSON encoder. The exact encoded bound below still accounts for escaping.
private int _deviceReadRawCharacters(value, int remaining) {
    if (value instanceof CharSequence) return Math.min(value.length(), remaining + 1)
    int count = 0
    if (value instanceof Map) {
        for (def entry : value.entrySet()) {
            count += entry.key.toString().length()
            if (count > remaining) return remaining + 1
            count += _deviceReadRawCharacters(entry.value, remaining - count)
            if (count > remaining) return remaining + 1
        }
    } else if (value instanceof List) {
        for (def item : value) {
            count += _deviceReadRawCharacters(item, remaining - count)
            if (count > remaining) return remaining + 1
        }
    }
    return count
}

private Map _deviceReadPage(Map result, String selection) {
    if (_deviceReadRawCharacters(result, 2097152) > 2097152) {
        throw new IllegalArgumentException('Device information exceeds the snapshot budget. Select fewer sections or fields, then read each selection separately.')
    }
    // Size the actual text-content envelope, including escaped JSON, before the shared guard.
    String serialized = groovy.json.JsonOutput.toJson(result)
    // Bound retained JSON to 2,097,152 UTF-16 code units across at most eight snapshots.
    if (serialized.length() > 2097152) throw new IllegalArgumentException('Device information exceeds the snapshot budget. Select fewer sections or fields, then read each selection separately.')
    if (serialized.length() < 95000) {
        def envelope = [jsonrpc: '2.0', id: 1, result: [content: [[type: 'text', text: serialized]]]]
        if (groovy.json.JsonOutput.toJson(envelope).getBytes('UTF-8').length < 95000) return result
    }
    String token = java.util.UUID.randomUUID().toString()
    Map snapshot = [id: result.id, mode: result.mode, content: serialized, selection: selection, at: now()]
    synchronized (DEVICE_READ_SNAPSHOTS) {
        DEVICE_READ_SNAPSHOTS.entrySet().findAll { now() - (it.value.at as Long) >= 300000L }
            .collect { it.key }.each { DEVICE_READ_SNAPSHOTS.remove(it) }
        int retained = DEVICE_READ_SNAPSHOTS.values().sum { it.content.length() } ?: 0
        while (DEVICE_READ_SNAPSHOTS.size() >= 8 || retained + serialized.length() > 2097152) {
            def oldest = DEVICE_READ_SNAPSHOTS.keySet().iterator().next()
            retained -= DEVICE_READ_SNAPSHOTS.remove(oldest).content.length()
        }
        DEVICE_READ_SNAPSHOTS.put(token, snapshot)
    }
    return _deviceReadFragment(snapshot, token, 0)
}

def toolGetDevice(deviceId, mode = 'summary', sections = null, fields = null, cursor = null, waitFor = null, reqT0 = null) {
    def canonicalId = _canonicalDeviceIdArg(deviceId)
    if (canonicalId == null) {
        throw new IllegalArgumentException("deviceId must be a non-empty string or integral number (got: ${_describeValueForError(deviceId)})")
    }
    deviceId = canonicalId
    String selectedMode = mode == null ? 'summary' : mode.toString()
    if (!(selectedMode in ['summary', 'configuration', 'details'])) {
        throw new IllegalArgumentException("mode must be summary, configuration, or details.")
    }
    if (waitFor != null && (selectedMode != 'summary' || cursor != null)) {
        throw new IllegalArgumentException("waitFor applies to summary mode only, without cursor.")
    }
    Map waitSpec = waitFor != null ? _waitForSpec(waitFor, false, 60000, 200) : null
    _refuseUnsettledWait(waitSpec, reqT0)
    if (sections != null && (selectedMode != 'details' || !(sections instanceof List) || sections.isEmpty() ||
        sections.any { !(it instanceof String) || !_deviceDetailSections().contains(it) })) {
        throw new IllegalArgumentException("sections is a non-empty array for mode=details; use ${_deviceDetailSections().join(', ')}.")
    }
    if (fields != null && (selectedMode == 'summary' || !(fields instanceof List) || fields.any { !(it instanceof String) })) {
        throw new IllegalArgumentException('fields is an array of field names for configuration/details; use [] to discover names.')
    }
    if (cursor != null && (selectedMode == 'summary' || !(cursor instanceof String))) {
        throw new IllegalArgumentException('cursor is a string continuation for configuration/details mode only.')
    }
    boolean listed = _requireDeviceToolAccess(deviceId)
    String selection = selectedMode == 'summary' ? null : _deviceReadSelection(deviceId, selectedMode, sections, fields)
    if (cursor) return _deviceReadContinuation(cursor, selection)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) {
        return [success: false, isError: true, error: "Device metadata fetch failed (/device/fullJson/${deviceId})",
                note: 'Check the native Devices page and retry.']
    }
    if (selectedMode == 'summary') {
        // The summary comes from the wait's last read, so it agrees with waitFor.finalValue.
        Map wait = waitSpec ? _waitForRun(waitSpec, [deviceId.toString()], false, full, reqT0) : null
        Map result
        try {
            result = _getDeviceFromFullJson(deviceId, wait?.last ?: full)
        } catch (IllegalStateException e) {
            result = [success: false, isError: true, error: e.message, note: 'Check the native Devices page and retry.']
        }
        if (wait) result.waitFor = _waitForBlock(waitSpec, wait, false)
        return result
    }
    def identity = [name: full?.device?.name, label: _bypassDeviceLabel(full, deviceId)]
    return _deviceReadPage(_deviceExpandedResult(deviceId, identity, full, selectedMode, sections, fields), selection)

}

def toolSendCommand(deviceId, command, parameters, waitFor = null, commands = null, reqT0 = null, includeState = true) {
    // Batch form: one call carrying several {deviceId, command, parameters?} entries, mutually
    // exclusive with the single-device arguments.
    if (commands != null) {
        def conflicting = []
        if (deviceId != null) conflicting << "deviceId"
        if (command != null) conflicting << "command"
        // A top-level parameters with commands would otherwise be SILENTLY dropped -- the batch
        // executes without the caller's arguments, which is worse than refusing the mixed shape.
        if (parameters != null) conflicting << "parameters"
        if (conflicting) {
            throw new IllegalArgumentException("commands is mutually exclusive with ${conflicting.join(' and ')} -- pass EITHER a single deviceId+command OR a commands array, not both (per-entry parameters go inside each commands entry)")
        }
        if (waitFor != null) {
            throw new IllegalArgumentException("waitFor is not supported with commands: it blocks a hub thread per device. Send the batch, then confirm the whole set in one call with hub_list_devices using deviceIds + waitFor")
        }
        return _sendCommandBatch(commands, reqT0)
    }

    if (deviceId == null) throw new IllegalArgumentException("deviceId is required (or pass a commands array to send several at once)")
    if (command == null) throw new IllegalArgumentException("command is required (or pass a commands array to send several at once)")

    // Canonicalize BEFORE resolution: a fractional or non-scalar id must fail validation here,
    // not spend a bypass fullJson fetch on a value that can never name a device.
    def canonicalId = _canonicalDeviceIdArg(deviceId)
    if (canonicalId == null) {
        throw new IllegalArgumentException("deviceId must be a non-empty string or integral number (got: ${_describeValueForError(deviceId)})")
    }
    deviceId = canonicalId


    _requireDeviceToolAccess(deviceId)
    def fullJson = _fetchDeviceFullJson(deviceId)
    if (!(fullJson?.device instanceof Map) || _unavailableNativeDeviceCollections(fullJson, ['commands'])) {
        return [success: false, isError: true, deviceId: deviceId,
                error: "Device command metadata fetch failed (/device/fullJson/${deviceId})".toString(),
                note: "The native device metadata could not be read; no command was sent. Verify the device exists and retry."]
    }
    def deviceLabel = _bypassDeviceLabel(fullJson, deviceId)
    def supportedCommands = _fullJsonCommandNames(fullJson)
    if (!supportedCommands?.contains(command)) {
        throw new IllegalArgumentException("Device ${deviceLabel} does not support command: ${command}. Available: ${supportedCommands}")
    }

    // waitFor is validated BEFORE the command fires so a malformed spec fails without a side
    // effect. The attribute NAME is the deliberate exception: fullJson lists reported states
    // only, so a declared-but-unreported attribute cannot be told from a typo and rejecting it
    // would block a legitimate command. A mistyped name therefore fires the command and then
    // times out with neverReported -- do not restore a pre-fire attribute-existence check here.
    Map waitSpec = (waitFor != null) ? _waitForSpec(waitFor, false, 30000, 250) : null
    // A waitFor that cannot settle in the request time left is refused before the command fires.
    _refuseUnsettledWait(waitSpec, reqT0)
    if (parameters instanceof String && !parameters.trim()) parameters = null
    if (parameters && parameters.size() > 0) {
        def declaration = fullJson.commands?.find { it?.name == command }
        parameters = normalizeCommandParams(parameters, _commandParamTypes(declaration))
    }
    def fireErr = _fireBypassCommand(deviceId, command, (parameters && parameters.size() > 0) ? parameters : [], fullJson)
    if (fireErr != null) return fireErr

    def result = [
        success: true,
        device: deviceLabel,
        command: command,
        parameters: parameters
    ]

    if (waitSpec != null) {
        // Poll before taking the post-dispatch snapshot; immediate native reads may show either the
        // prior or resulting value.
        try {
            result.waitFor = _waitForBlock(waitSpec, _waitForRun(waitSpec, [deviceId.toString()], false, null, reqT0), false)
        } catch (Throwable e) {
            // The command already fired, so a failed wait (Throwable included) reports
            // non-convergence instead of losing the response; the snapshot is still taken below.
            def cls = e.class.simpleName
            // Log at error: a warn is below Hubitat's default log level and returns before
            // writing, so this degraded-path failure would land in neither buffer nor hub log.
            mcpLog("error", "send-command", "waitFor poll failed for ${deviceLabel}: ${cls}: ${e.message ?: '(no message)'}")
            result.waitFor = [attribute: waitSpec.attribute, expected: waitSpec.expected, converged: false, finalValue: null,
                              error: "waitFor poll failed: ${cls}: ${e.message ?: '(no message)'}".toString()]
            result.partial = true
        }
    }

    // Batch entries skip the read-back entirely: their response drops state anyway, and the
    // snapshot is a SECOND /device/fullJson fetch per entry -- up to 20 wasted reads
    // against the same relay budget the batch is trying to stay inside.
    if (!includeState) return result

    // Snapshot after dispatch and any waitFor poll. The native read may show the
    // prior or resulting value; waitFor.converged reports whether polling confirmed it.
    // Null is the read-back FAILURE sentinel (distinct from empty [:]); surface why so the agent
    // can tell a failed confirmation read apart from a device with no readable attributes.
    def stateErr = []
    // def snap = bypass ? _snapshotBypassDeviceState(deviceId, deviceLabel, stateErr)
    //                   : _snapshotDeviceState(device, deviceLabel, stateErr)
    def snap = _snapshotBypassDeviceState(deviceId, deviceLabel, stateErr)
    if (snap == null) {
        // Genuine read-back failure: state is empty AND stateError says why, so the agent
        // distinguishes this from a device that legitimately has no readable attributes
        // (which returns an empty map and NO stateError).
        result.state = [:]
        result.stateError = "device-state read-back failed: ${stateErr ? stateErr[0] : '(no detail)'}".toString()
        // The command fired but the confirmation snapshot failed -- flag the partial result.
        result.partial = true
    } else {
        result.state = snap
    }
    return result
}

// Canonical string form of a device-id argument. A String passes through; an INTEGRAL Number is
// stringified (ids arrive numeric from hub_list_devices format='ids'); anything else -- a
// fractional number included -- returns null for the caller to reject before any side effect.
private _canonicalDeviceIdArg(value) {
    // CharSequence, not String: an internal caller may hand a GString and must not be rejected.
    if (value instanceof CharSequence) return value.toString().trim() ?: null
    if (value instanceof Number) return (value == value.longValue()) ? value.longValue().toString() : null
    return null
}

// The commands-array form of hub_call_device_command. Not a tool of its own: it is reached only
// through that tool's `commands` parameter. Batching exists because the per-call round trip, not
// the hub actuating the device, dominates wall clock. The measured figures, the bridged-device
// exception and the group/scene trade-off live in hub_get_tool_guide(section='performance_overview').
private _sendCommandBatch(commands, reqT0 = null) {
    if (!(commands instanceof List) || commands.isEmpty()) {
        throw new IllegalArgumentException("commands must be a non-empty array of {deviceId, command, parameters?} objects")
    }
    // Same ceiling as hub_list_devices' waitFor; this bound keeps one request's serial dispatch
    // inside the relay window.
    def MAX_BATCH_COMMANDS = 20
    if (commands.size() > MAX_BATCH_COMMANDS) {
        throw new IllegalArgumentException("commands may contain at most ${MAX_BATCH_COMMANDS} entries (got ${commands.size()})")
    }

    // Validate the whole batch BEFORE firing any of it. A malformed entry half way down would
    // otherwise leave the earlier devices actuated and the caller holding an error with no way to
    // know how far it got -- the same no-side-effect-on-a-bad-spec guarantee waitFor already
    // gives on the single-device path.
    def validEntryKeys = ["deviceId", "command", "parameters"] as Set
    def entryDeviceIds = []
    commands.eachWithIndex { entry, i ->
        if (!(entry instanceof Map)) {
            throw new IllegalArgumentException("commands[${i}] must be an object with deviceId and command")
        }
        // A hub device id is numeric, so a caller that sends it as a JSON number is not wrong --
        // coerce to the string form every downstream comparison uses instead of rejecting it.
        def id = _canonicalDeviceIdArg(entry.deviceId)
        if (!(id instanceof String) || !id.trim()) {
            throw new IllegalArgumentException("commands[${i}].deviceId is required and must be a non-empty string or integral number (got: ${_describeValueForError(entry.deviceId)})")
        }
        if (!(entry.command instanceof String) || !entry.command.trim()) {
            throw new IllegalArgumentException("commands[${i}].command is required and must be a non-empty string (got: ${_describeValueForError(entry.command)})")
        }
        // A String parameters value is passed straight through: normalizeCommandParams repairs the
        // shape the hub's JSON parser produces when a nested object defeats it.
        if (entry.parameters != null && !(entry.parameters instanceof List) && !(entry.parameters instanceof String)) {
            throw new IllegalArgumentException("commands[${i}].parameters must be an array when present (got: ${_describeValueForError(entry.parameters)})")
        }
        def unknown = (entry.keySet() - validEntryKeys).sort()
        if (unknown) {
            throw new IllegalArgumentException("commands[${i}] has unknown keys: ${unknown.join(', ')}. Valid keys: ${validEntryKeys.sort().join(', ')}")
        }
        entryDeviceIds << id
    }

    // One entry failing must not abandon the rest. A batch is a list of independent intents, and
    // a caller turning off six lights would rather five went off with one reported failure than
    // have the whole thing refused because a sixth device had been removed. Per-entry outcomes
    // are reported individually so the caller can tell exactly which. "Failing" means an
    // Exception: a JVM-level Error (OOM, StackOverflow) deliberately escapes the loop and aborts
    // the batch -- a broken VM should stop actuating hardware, not soldier on.
    def results = []
    def remaining = []
    long t0 = (reqT0 instanceof Number) ? ((Number) reqT0).longValue() : now()
    for (int i = 0; i < commands.size(); i++) {
        // Hand the untried tail back rather than let a long batch of slow devices run the request
        // past the relay's response window, where a dropped response leaves the caller unable to
        // tell what actuated. Never on the first entry: a batch always attempts at least one.
        if (i > 0 && _timeBudgetExceeded(t0)) {
            remaining = commands.subList(i, commands.size()).collect { it }
            break
        }
        def e = commands[i]
        def deviceId = entryDeviceIds[i]
        try {
            // Delegates to the single-device tool, so allowlist handling, the bypass path,
            // command-support validation and parameter normalisation stay in one place and cannot
            // drift between the two entry points.
            def one = toolSendCommand(deviceId, e.command, e.parameters, null, null, null, false)

            // A bypass-path failure arrives as a returned [success:false, error, note], not a
            // throw, so the aggregate below derives from results[] rather than counting throws.
            // That returned map carries no command, hence the backfill (a real command from the
            // delegate wins). The per-device state snapshot is dropped: a batch confirms through
            // hub_list_devices' deviceIds + waitFor, not through N inline snapshots.
            def entry = [deviceId: deviceId, command: e.command] + one
            entry.remove("state")
            entry.remove("stateError")
            entry.remove("partial")
            if (entry.success == false) {
                mcpLog("error", "send-command", "batch entry failed for ${deviceId} (${e.command}): ${entry.error ?: '(no error text)'}")
            }
            results << entry
        } catch (Exception ex) {
            def cls = ex.class.simpleName
            mcpLog("error", "send-command", "batch entry threw for ${deviceId} (${e.command}): ${cls}: ${ex.message ?: '(no message)'}")
            results << [
                success : false,
                deviceId: deviceId,
                command : e.command,
                error   : "${cls}: ${ex.message ?: '(no message)'}".toString(),
                note    : "This entry was not confirmed to actuate; the other attempted entries were still sent (entries the relay budget stopped are in remainingCommands, unsent). Verify the deviceId with hub_list_devices and the command with hub_get_device -- and if the failure happened mid-command, confirm the device state before re-sending this entry."
            ]
        }
    }

    def failed = results.count { it.success == false }
    def sent = results.size() - failed
    def result = [
        success    : failed == 0,
        count      : results.size(),
        sentCount  : sent,
        failedCount: failed,
        results    : results
    ]
    if (failed > 0) {
        result.failedDeviceIds = results.findAll { it.success == false }.collect { it.deviceId }
        if (failed < results.size()) result.partial = true
        result.error = "${failed} of ${results.size()} device command(s) failed; ${sent} were sent. See results[] for the per-device cause.".toString()
        result.note = "The entries that succeeded ALREADY actuated -- do NOT re-send the whole batch. Before re-sending a failed entry, read its error/note: an outcome the hub did not CONFIRM may still have actuated, so verify with hub_get_device first. See hub_get_tool_guide(section='device_authorization') for what makes an entry fail."
    }
    if (remaining) {
        result.success = false
        result.stoppedEarly = true
        result.remainingCommands = remaining
        result.error = ("Stopped after attempting ${results.size()} of ${commands.size()} entries, nearing the relay time budget; the remaining ${remaining.size()} were NOT sent." +
                        (failed > 0 ? " ${failed} of the attempted entries also failed -- see results[]." : "")).toString()
        result.note = "The ${results.size()} attempted entries were already dispatched (see results[]); re-send ONLY remainingCommands in a new call.".toString()
    }
    return result
}

// The one waitFor check, run before any read or command: hub_call_device_command caps timeoutMs at
// 30000 and defaults the interval to 250 (a waitFor follows a write); the read tools allow 60000
// and 200. Only hub_list_devices (multi) takes mode. Returns the normalized spec the engine runs.
private Map _waitForSpec(waitFor, boolean multi, int maxTimeoutMs, int defaultIntervalMs) {
    if (!(waitFor instanceof Map)) {
        throw new IllegalArgumentException("waitFor must be an object with attribute and expectedValue or expectedValues")
    }
    List validKeys = ["attribute", "comparator", "expectedValue", "expectedValues", "pollIntervalMs", "stableForMs", "timeoutMs"] + (multi ? ["mode"] : [])
    List unknownKeys = (waitFor.keySet().collect { it.toString() } - validKeys).sort()
    if (unknownKeys) {
        throw new IllegalArgumentException("waitFor has ${unknownKeys.size() == 1 ? 'an unknown key' : 'unknown keys'}: ${unknownKeys.join(', ')}. Valid keys: ${validKeys.join(', ')}")
    }
    if (!(waitFor.attribute instanceof String) || !waitFor.attribute.trim()) {
        throw new IllegalArgumentException("waitFor.attribute is required and must be a non-empty string")
    }
    _refuseCredentialPoll(waitFor.attribute)
    boolean hasValue = waitFor.containsKey("expectedValue")
    boolean hasValues = waitFor.containsKey("expectedValues")
    if (hasValue && hasValues) {
        throw new IllegalArgumentException("waitFor: provide exactly one of expectedValue or expectedValues, not both")
    }
    if (!hasValue && !hasValues) {
        throw new IllegalArgumentException("waitFor: exactly one of expectedValue or expectedValues is required")
    }
    if (hasValue && (!(waitFor.expectedValue instanceof String) || !waitFor.expectedValue.trim())) {
        throw new IllegalArgumentException("waitFor.expectedValue must be a non-empty string (got: ${_describeValueForError(waitFor.expectedValue)})")
    }
    if (hasValues) {
        if (!(waitFor.expectedValues instanceof List) || waitFor.expectedValues.isEmpty()) {
            throw new IllegalArgumentException("waitFor.expectedValues must be a non-empty list of strings")
        }
        waitFor.expectedValues.eachWithIndex { v, i ->
            if (!(v instanceof String)) throw new IllegalArgumentException("waitFor.expectedValues[${i}] must be a string, got: ${_describeValueForError(v)}")
        }
    }
    Map bounds = [timeoutMs: [100, maxTimeoutMs, 5000], pollIntervalMs: [50, 5000, defaultIntervalMs], stableForMs: [0, null, 0]]
    Map ms = [:]
    bounds.each { key, b ->
        def v = waitFor.containsKey(key) ? waitFor.get(key) : b[2]
        if (!(v instanceof Number) || v < b[0] || (b[1] != null && v > b[1])) {
            String range = b[1] != null ? "between ${b[0]} and ${b[1]}" : ">= 0"
            throw new IllegalArgumentException("waitFor.${key} must be a number ${range} (got: ${_describeValueForError(v)})")
        }
        ms.put(key, v as Integer)
    }
    if (ms.stableForMs >= ms.timeoutMs) {
        throw new IllegalArgumentException("waitFor.stableForMs (${ms.stableForMs}) must be less than timeoutMs (${ms.timeoutMs}) -- the condition could never hold long enough to converge")
    }
    def comparator = waitFor.containsKey("comparator") ? waitFor.comparator : "eq"
    if (!(comparator in ["eq", "ne", "gt", "gte", "lt", "lte", "between"])) {
        throw new IllegalArgumentException("waitFor.comparator must be one of eq, ne, gt, gte, lt, lte, between (got: ${_describeValueForError(comparator)})")
    }
    Map spec = [attribute: waitFor.attribute, comparator: comparator, expected: hasValues ? waitFor.expectedValues : waitFor.expectedValue,
                matchSet: (hasValues ? waitFor.expectedValues : [waitFor.expectedValue]) as Set,
                timeoutMs: ms.timeoutMs, pollIntervalMs: Math.min(ms.pollIntervalMs, ms.timeoutMs), stableForMs: ms.stableForMs]
    if (comparator in ["gt", "gte", "lt", "lte"]) {
        if (hasValues) throw new IllegalArgumentException("waitFor.comparator '${comparator}' takes a single numeric threshold via expectedValue, not expectedValues")
        spec.numericThreshold = _parseBigDecimalOrNull(waitFor.expectedValue)
        if (spec.numericThreshold == null) {
            throw new IllegalArgumentException("waitFor.comparator '${comparator}' requires expectedValue to be a numeric-parseable string (got: ${_describeValueForError(waitFor.expectedValue)})")
        }
    } else if (comparator == "between") {
        if (hasValue) throw new IllegalArgumentException("waitFor.comparator 'between' takes two numeric bounds via expectedValues, not expectedValue")
        if (waitFor.expectedValues.size() != 2) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires expectedValues to have exactly 2 numeric bounds [low, high] (got ${waitFor.expectedValues.size()})")
        }
        spec.betweenLow = _parseBigDecimalOrNull(waitFor.expectedValues[0])
        spec.betweenHigh = _parseBigDecimalOrNull(waitFor.expectedValues[1])
        if (spec.betweenLow == null || spec.betweenHigh == null) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires both expectedValues bounds to be numeric-parseable strings (got: ${waitFor.expectedValues})")
        }
        if (spec.betweenLow > spec.betweenHigh) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires low <= high (got low=${waitFor.expectedValues[0]}, high=${waitFor.expectedValues[1]})")
        }
    }
    if (multi) {
        def mode = waitFor.containsKey("mode") ? waitFor.mode : "all"
        if (!(mode in ["any", "all"])) throw new IllegalArgumentException("waitFor.mode must be any or all (got: ${_describeValueForError(mode)})")
        spec.mode = mode
    }
    return spec
}

// Parse a value as BigDecimal for numeric comparator math, or null if it is not numeric.
// Accepts a Number directly and a numeric-parseable CharSequence (a String or a GString --
// the form device states report); anything else (null, non-numeric string, list, map) yields
// null so the caller treats it as "no match" rather than throwing. A GString is not a String
// subtype in Groovy, so match CharSequence and normalize via toString() before parsing.
// NumberFormatException is the only expected failure.
private BigDecimal _parseBigDecimalOrNull(v) {
    if (v instanceof Number) return v as BigDecimal
    if (v instanceof CharSequence && v.isNumber()) {
        try { return new BigDecimal(v.toString()) } catch (NumberFormatException ignored) { return null }
    }
    return null
}

// Compact native current-state snapshot for a command response: values and timestamps only.
// NOTE: this is an IMMEDIATE read taken in the same request that fired the command, so without
// waitFor it may show the prior or the resulting value. The timestamp is the freshness signal;
// waitFor (block-poll) is what makes this snapshot reflect the converged state.

// Fire an access-permitted device command via the hub's id-keyed native endpoint.
// POST /device/runmethod with
// {id, method, args:[{type, value}, ...]}; empty args for a no-parameter command. Each arg's
// type comes from the command's declared parameters in fullJson when available, else inferred
// from the value. Returns null on success, or a structured [success:false, error, note] runtime-
// error map (the AGENTS.md runtime-error contract) on a non-success/non-JSON body or a hub-call
// failure -- the caller surfaces it directly rather than a thrown exception.
private Map _fireBypassCommand(deviceId, command, List params, Map fullJson) {
    def args = _buildRunMethodArgs(command, params, fullJson)
    def body = groovy.json.JsonOutput.toJson([id: _runMethodDeviceId(deviceId), method: command, args: args])
    def resp
    def unknownOutcome = [success: false, isError: true, outcomeUnknown: true,
        note: "The command may already have executed. Inspect device state and events before deciding whether to repeat it; do not automatically replay non-idempotent commands."]
    try {
        resp = hubInternalPostJson("/device/runmethod", body)
    } catch (Exception e) {
        // Log at error: a warn is below Hubitat's default log level, so this failed-fire would
        // land in neither the hub log nor the buffer.
        mcpLog("error", "send-command", "native: /device/runmethod for '${command}' on ${deviceId} threw: ${e.class.simpleName}")
        return unknownOutcome + [error: "runmethod call failed for '${command}': ${e.message ?: e.toString()}"]
    }
    // FAIL-CLOSED on anything that is not a positive confirmation. A null/empty body (dropped
    // response on a write -> unknown commit), a non-JSON body, a non-Map, or a Map that does not
    // carry success==true (e.g. {}) all mean "not confirmed" -- never silently treat them as success.
    if (resp == null) {
        mcpLog("error", "send-command", "native: /device/runmethod for '${command}' on ${deviceId} returned an empty/dropped response")
        return unknownOutcome + [error: "runmethod returned an empty/dropped response for '${command}'"]
    }
    if (resp instanceof Map && resp._unparseable) {
        mcpLog("error", "send-command", "native: /device/runmethod for '${command}' on ${deviceId} returned a non-JSON body: ${resp.message}")
        return unknownOutcome + [error: "runmethod returned a non-JSON body for '${command}': ${resp.message}"]
    }
    if (!(resp instanceof Map) || resp.success != true) {
        mcpLog("error", "send-command", "native: /device/runmethod for '${command}' on ${deviceId} did not confirm success: ${resp}")
        if (!(resp instanceof Map) || resp.success?.toString() != 'false') {
            return unknownOutcome + [error: "runmethod did not confirm success for '${command}': ${resp}"]
        }
        return [success: false, isError: true, error: "runmethod did not confirm success for '${command}': ${resp}",
                note: "The hub rejected or did not confirm the command. Verify the command and arguments against hub_get_device."]
    }
    return null
}

// Coerce a device id to the integer the runmethod endpoint expects, passing a non-numeric id
// through unchanged (fullJson already proved the device exists, so the id is well-formed).
private _runMethodDeviceId(deviceId) {
    def s = deviceId?.toString()
    return (s != null && s.isInteger()) ? s.toInteger() : deviceId
}

// Build the runmethod args list ([{type, value}, ...]) from the normalized parameter values,
// typing each positionally from the command's declared parameters in fullJson, else inferring.
private List _buildRunMethodArgs(command, List params, Map fullJson) {
    if (!params) return []
    def cmdDef = (fullJson?.commands instanceof List) ? fullJson.commands.find { it?.name == command } : null
    def declaredTypes = _commandParamTypes(cmdDef)
    def out = []
    params.eachWithIndex { v, i ->
        def t = (i < declaredTypes.size() && declaredTypes[i]) ? declaredTypes[i] : _inferRunMethodArgType(v)
        out << [type: t, value: v]
    }
    return out
}

// Positional arg-type tokens declared by a fullJson command, from `parameters` (typed maps) or
// `arguments` (type tokens). Empty when the command declares no typed parameters.
private List _commandParamTypes(cmdDef) {
    if (cmdDef?.parameters instanceof List && !cmdDef.parameters.isEmpty()) {
        return cmdDef.parameters.collect { p -> (p instanceof Map) ? p.type?.toString() : p?.toString() }
    }
    if (cmdDef?.arguments instanceof List && !cmdDef.arguments.isEmpty()) {
        return cmdDef.arguments.collect { a -> (a instanceof Map) ? a.type?.toString() : a?.toString() }
    }
    return []
}

// Fallback runmethod arg type when the command declares none: a Map/List value is a
// JSON_OBJECT (e.g. setColor), a Number is a NUMBER, everything else a STRING.
private String _inferRunMethodArgType(v) {
    if (v instanceof Map || v instanceof List) return "JSON_OBJECT"
    if (v instanceof Number) return "NUMBER"
    return "STRING"
}

// Compact native current-state snapshot for a device command response, preserving
// _snapshotDeviceState's {attr: {value, timestamp}} shape but sourced from /device/fullJson
// currentStates. Returns null (the read-back FAILURE sentinel) when fullJson is unavailable.
private Map _snapshotBypassDeviceState(deviceId, deviceLabel, errOut = null) {
    try {
        def fj = _fetchDeviceFullJson(deviceId)
        def cs = fj?.device?.currentStates
        if (!(cs instanceof Map)) {
            mcpLog("error", "send-command", "native: fullJson currentStates unavailable for device ${deviceId}")
            if (errOut != null) errOut << "fullJson currentStates unavailable for ${deviceId}"
            return null
        }
        def snapshot = [:]
        cs.each { name, st ->
            if (name != null) {
                def val = _devicePublicAttributeValue(name, _nativeDeviceStateValue(st))
                def rawDate = (st instanceof Map) ? st.date : null
                snapshot.put(name, [value: val, timestamp: _formatBypassStateDate(rawDate)])
            }
        }
        return snapshot
    } catch (Throwable t) {
        def detail = "${t.class.simpleName}: ${t.message ?: '(no message)'}".toString()
        mcpLog("error", "send-command", "Failed to snapshot native device state for ${deviceLabel}: ${detail}")
        if (errOut != null) errOut << detail
        return null
    }
}

// Normalize a fullJson currentState date (an ISO-8601 String) to the snapshot's
// "yyyy-MM-dd HH:mm:ss" form for parity with the listed-device snapshot, falling back to the
// raw string when it does not parse. null in -> null out (no event timestamp).
private String _formatBypassStateDate(rawDate) {
    if (rawDate == null) return null
    def s = rawDate.toString()
    def parsed = _parseSinceArg(s)
    if (parsed == null) return s
    try {
        return parsed.format("yyyy-MM-dd HH:mm:ss")
    } catch (Throwable t) {
        return s
    }
}

def normalizeCommandParams(params, List declaredTypes = []) {
    // Case 1: Already a List (Hubitat parsed it successfully) — go straight to element conversion
    if (params instanceof List) {
        return convertParamElements(params, declaredTypes)
    }

    // Case 2: String (Hubitat parser failed on nested JSON)
    // Example: '["{"hue":0,"saturation":100,"level":50}"]'
    def s = params.toString().trim()

    if (declaredTypes) {
        // Decode a valid outer array before legacy repair so literal JSON text stays text.
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(s)
            if (parsed instanceof List) return convertParamElements(parsed, declaredTypes)
        } catch (Exception ignored) {}
        if (declaredTypes[0]?.toString()?.toUpperCase() in ['STRING', 'ENUM']) {
            if (s.startsWith('["') && s.endsWith('"]')) {
                return convertParamElements(s.substring(2, s.length() - 2).split('","').toList(), declaredTypes)
            }
            return convertParamElements([s], declaredTypes)
        }
    }

    // Try to extract an embedded JSON object between first { and last }
    def firstBrace = s.indexOf("{")
    def lastBrace = s.lastIndexOf("}")
    if (firstBrace >= 0 && lastBrace > firstBrace) {
        def jsonContent = s.substring(firstBrace, lastBrace + 1)
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(jsonContent)
            return convertParamElements([parsed], declaredTypes)
        } catch (Exception e) {
            // Not valid JSON object, fall through
        }
    }

    // No JSON object found — strip outer ["..."] wrapper and split into string params
    if (s.startsWith("[\"") && s.endsWith("\"]")) {
        def inner = s.substring(2, s.length() - 2)
        return convertParamElements(inner.split('","').toList(), declaredTypes)
    }

    // Last resort: treat the whole string as a single parameter
    return convertParamElements([s], declaredTypes)
}

def convertParamElements(List params, List declaredTypes = []) {
    return params.withIndex().collect { param, index ->
        if (param == null) return param
        def declaredType = index < declaredTypes.size() ? declaredTypes[index]?.toString()?.toUpperCase() : null
        if (declaredType in ['STRING', 'ENUM'] && param instanceof CharSequence) return param.toString()
        if (param instanceof Map || param instanceof List) return param
        def s = param.toString()
        // Numeric conversion
        try {
            if (s.isNumber()) {
                return s.contains(".") ? s.toDouble() : s.toInteger()
            }
        } catch (Exception e) {}
        // JSON object/array string → parse to Map/List
        if ((s.startsWith("{") || s.startsWith("[")) && s.length() > 1) {
            try {
                return new groovy.json.JsonSlurper().parseText(s)
            } catch (Exception e) {}
        }
        return param
    }
}

def toolGetDeviceEvents(deviceId, limit) {
    if (limit == null || limit < 1) limit = 10
    _requireDeviceToolAccess(deviceId)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) return [success: false, isError: true, error: "Device metadata fetch failed (/device/fullJson/${deviceId})", note: 'Check native device details and retry.']
    def label = _bypassDeviceLabel(full, deviceId)
    def rows = _fetchBypassDeviceEvents(deviceId)
    if (rows == null) return [success: false, isError: true, error: "Device event history fetch failed (/device/eventsJson/${deviceId})", device: label, note: 'Check the native device Events page and retry.']
    def events = rows.take(limit as Integer).collect { _mapBypassEventRow(it) }
    return [device: label, events: events, count: events.size()]
}

// The per-value match logic of every waitFor. Returns [matched, numeric]: matched is the boolean
// condition result (eq = value in matchSet; ne = a non-null value NOT in matchSet; gt/gte/lt/lte/
// between = numeric, a null/non-numeric value never matches); numeric is true only when THIS value
// parsed as a number under a numeric comparator (false for eq/ne), letting the caller latch the
// per-device sawNumeric signal.
def _evalAttrCondition(value, comparator, matchSet, numericThreshold, betweenLow, betweenHigh) {
    if (comparator == "eq" || comparator == "ne") {
        def inSet = matchSet.contains(value?.toString()) ||
            (value instanceof Number && matchSet.any { it instanceof String && it.isNumber() && (it as BigDecimal) == (value as BigDecimal) }) ||
            (value instanceof String && value.isNumber() && matchSet.any { it instanceof String && it.isNumber() && (it as BigDecimal) == (value as BigDecimal) })
        def matched = (comparator == "eq") ? inSet : (value != null && !inSet)
        return [matched, false]
    }
    def num = _parseBigDecimalOrNull(value)
    if (num == null) {
        return [false, false]   // null/non-numeric never satisfies a numeric comparator
    }
    def matched
    if (comparator == "gt")            matched = num >  numericThreshold
    else if (comparator == "gte")      matched = num >= numericThreshold
    else if (comparator == "lt")       matched = num <  numericThreshold
    else if (comparator == "lte")      matched = num <= numericThreshold
    else if (comparator == "between")  matched = (num >= betweenLow && num <= betweenHigh)
    else throw new IllegalArgumentException("Unsupported comparator: ${comparator}")
    return [matched, true]
}

// A wait for a hidden value would confirm a guess at it, one call per guess.
private void _refuseCredentialPoll(attribute) {
    if (attribute != null && _redactsCredentials() && _deviceConfigurationSecretKey(attribute)) {
        throw new IllegalArgumentException("Cannot wait for a value of ${attribute}: credential-named attributes are hidden while 'Hide passwords and credentials' is on.")
    }
}

// Refuses, before any read or command, a waitFor whose stableForMs cannot settle in the request time left.
private void _refuseUnsettledWait(Map spec, reqT0) {
    String refusal = spec != null ? _pollStableRefusal(spec, reqT0) : null
    if (refusal) throw new IllegalArgumentException("waitFor.${refusal}")
}

// The refusal a budgeted poll gives a stableForMs that cannot settle in the request time left, else null.
private String _pollStableRefusal(Map pollArgs, t0) {
    long budget = _isCloudRequest() ? _relayBudgetMs() : _lanBudgetMs()
    def asked = pollArgs.containsKey("timeoutMs") ? pollArgs.timeoutMs : 5000
    def stable = pollArgs.stableForMs
    if (!(t0 instanceof Number) || budget <= 0L || !(asked instanceof Number) || !(stable instanceof Number)) return null
    long left = (t0 as long) + budget - now()
    if (left >= (asked as long) || (stable as long) < left) return null
    String why = _isCloudRequest() ? 'the cloud relay drops a request that runs much longer' : 'the LAN time budget is set'
    return "stableForMs ${stable} cannot settle in the ${Math.max(0L, left)} ms this request has left (${why}); use a shorter stableForMs.".toString()
}

// Runs a validated waitFor. On a budgeted transport (the cloud relay; LAN only when a LAN budget is
// set) the wait ends by the request's deadline, reads included, so the relay never drops the answer.
private Map _waitForRun(Map spec, List ids, boolean page, first, reqT0) {
    long budget = _isCloudRequest() ? _relayBudgetMs() : _lanBudgetMs()
    if (!(reqT0 instanceof Number) || budget <= 0L) return _waitForCore(spec, ids, page, first, null)
    String refusal = _pollStableRefusal(spec, reqT0)
    if (refusal) throw new IllegalArgumentException("waitFor.${refusal}")
    long deadline = (reqT0 as long) + budget
    long left = deadline - now()
    int asked = spec.timeoutMs
    String why = _isCloudRequest() ? 'the cloud relay drops a request that runs much longer' : 'the LAN time budget is set'
    if (left < 100L) {
        return [converged: false, timedOut: true, elapsedMs: 0, polledCount: 0, convergedCount: 0, last: first,
                devices: ids.collect { [deviceId: it, finalValue: null, matched: false] },
                budgetLimited: true, requestedTimeoutMs: asked, timeoutMs: 0,
                budgetNote: "Not polled: the request had ${Math.max(0L, left)} ms left (${why}). Wait again in a new call.".toString()]
    }
    Map run = left < asked ? spec + [timeoutMs: left as Integer, pollIntervalMs: Math.min(spec.pollIntervalMs as long, left) as Integer] : spec
    Map result = _waitForCore(run, ids, page, first, deadline)
    // A wait that converged is reported as it is; only one that stopped short says so.
    if ((result.remove("budgetStopped") == true || left < asked) && !result.converged) {
        result.requestedTimeoutMs = asked
        result.timeoutMs = Math.min(left, asked as long)
        result.budgetLimited = true
        result.budgetNote = "The wait stopped after ${result.elapsedMs} ms instead of ${asked} ms: ${why}. Wait again to keep waiting.".toString()
    }
    return result
}

// One device reads /device/fullJson per check; page=true reads the Devices page once per check for
// every id. `first` is a read the caller already holds (fullJson, or page records by id) and serves
// as the first check. `last` in the result is the newest successful read in the same form.
def _waitForCore(Map spec, List ids, boolean page, first, Long deadlineMs) {
    int n = ids.size()
    int timeoutMs = spec.timeoutMs
    boolean budgetCut = false
    if (deadlineMs != null && deadlineMs - now() < timeoutMs) {
        timeoutMs = Math.max(0L, deadlineMs - now()) as Integer
        budgetCut = true
    }
    String attribute = spec.attribute
    String comparator = spec.comparator
    int maxPolls = ((timeoutMs / (spec.pollIntervalMs as Integer)) as Integer) + 1
    long startMs = now()
    int polledCount = 0
    int elapsedMs = 0
    List values = (0..<n).collect { null }
    List matched = (0..<n).collect { false }
    // Per device: ever non-null (else neverReported), parsed numeric under a numeric comparator
    // (else nonNumericAttribute), value moved between checks (transitioning), a read failed.
    List everNonNull = (0..<n).collect { false }
    List sawNumeric = (0..<n).collect { false }
    List sawChange = (0..<n).collect { false }
    List lastNonNull = (0..<n).collect { null }
    List readError = (0..<n).collect { false }
    def last = page ? [:] : first
    def pending = first
    // The whole any/all condition must hold continuously for stableForMs; a miss restarts the window.
    Long conditionTrueSince = null
    boolean pastDeadline = false
    String outcome
    while (true) {
        long tickStartMs = now()
        if (page) {
            Map records = pending
            pending = null
            if (records == null) {
                records = [:]
                try {
                    for (rec in _readDevicesPage(ids as Set)) records.put(rec.id, rec)
                } catch (IllegalStateException e) {
                    if (!readError.any()) mcpLog("warn", "device", "waitFor Devices page read failed on check ${polledCount + 1}: ${e.message}")
                }
            }
            last.putAll(records)
            for (int i = 0; i < n; i++) {
                Map rec = records.get(ids[i])
                if (rec == null) readError[i] = true
                values[i] = rec?.currentStates?.get(attribute)
            }
        } else {
            Map fj = pending ?: _fetchDeviceFullJson(ids[0])
            pending = null
            if (fj?.device?.currentStates instanceof Map) {
                last = fj
                values[0] = _readBypassAttrValueFrom(fj, attribute)
            } else {
                values[0] = null
                readError[0] = true
            }
        }
        polledCount++
        for (int i = 0; i < n; i++) {
            def v = values[i]
            if (v != null) {
                everNonNull[i] = true
                String cur = v.toString()
                if (lastNonNull[i] != null && cur != lastNonNull[i]) sawChange[i] = true
                lastNonNull[i] = cur
            }
            def eval = _evalAttrCondition(v, comparator, spec.matchSet, spec.numericThreshold, spec.betweenLow, spec.betweenHigh)
            matched[i] = eval[0]
            if (eval[1]) sawNumeric[i] = true
        }
        elapsedMs = (now() - startMs) as Integer
        int count = matched.count { it }
        if (spec.mode == "any" ? count > 0 : count == n) {
            if (conditionTrueSince == null) conditionTrueSince = now()
            if (now() - conditionTrueSince >= (spec.stableForMs as long)) { outcome = "converged"; break }
        } else {
            conditionTrueSince = null
        }
        long tickMs = now() - tickStartMs
        int sleepMs = _pollSleepMs(spec.pollIntervalMs, timeoutMs - elapsedMs, tickMs)
        // The next check is this sleep plus a read like the last one; it must end by the deadline.
        pastDeadline = deadlineMs != null && now() + sleepMs + tickMs >= deadlineMs
        if (elapsedMs >= timeoutMs || polledCount >= maxPolls || pastDeadline) { outcome = "timedOut"; break }
        try {
            if (sleepMs > 0) pauseExecution(sleepMs)
        } catch (InterruptedException e) {
            // pauseExecution throws when the hub restarts or the app reloads.
            elapsedMs = (now() - startMs) as Integer
            mcpLog("warn", "device", "waitFor interrupted after ${polledCount} check(s) (${elapsedMs} ms): ${e.message}")
            outcome = "interrupted"
            break
        }
    }
    boolean timedOut = outcome == "timedOut"
    boolean numericComparator = !(comparator in ["eq", "ne"])
    int nonNumeric = 0
    boolean transitioning = false
    List rows = (0..<n).collect { i ->
        Map row = [deviceId: ids[i], finalValue: values[i], matched: matched[i]]
        if (readError[i]) row.readError = true
        if (timedOut) {
            if (!everNonNull[i]) row.neverReported = true
            // A numeric comparator on a value that never parsed can never match: not "still settling".
            if (numericComparator && everNonNull[i] && !sawNumeric[i]) {
                row.nonNumericAttribute = true
                nonNumeric++
            } else if (sawChange[i]) {
                transitioning = true
            }
        }
        row
    }
    Map result = [converged: outcome == "converged", elapsedMs: elapsedMs, polledCount: polledCount,
                  convergedCount: matched.count { it }, devices: rows, last: last]
    if (timedOut) {
        result.timedOut = true
        result.transitioning = transitioning
        if (budgetCut || pastDeadline) result.budgetStopped = true
    }
    if (outcome == "interrupted") result.interrupted = true
    if (readError.any()) result.readError = true
    if (nonNumeric > 0) {
        result.note = "comparator '${comparator}' can never match attribute '${attribute}' on ${nonNumeric} device(s) that reported a non-numeric value the whole window (use eq/ne for a string attribute)".toString()
    }
    return result
}

// The waitFor block a tool returns. One device reports its value at the top level; several report
// devices[] and convergedCount.
private Map _waitForBlock(Map spec, Map r, boolean multi) {
    Map block = [attribute: spec.attribute, expected: spec.expected]
    if (multi) block.mode = spec.mode
    block.converged = r.converged == true
    Map row = r.devices ? r.devices[0] : [:]
    if (multi) block.convergedCount = r.convergedCount
    else block.finalValue = row.finalValue
    block.elapsedMs = r.elapsedMs
    block.polledCount = r.polledCount
    if (multi) block.devices = r.devices
    for (k in ["timedOut", "transitioning", "interrupted", "readError", "neverReported", "nonNumericAttribute",
               "note", "budgetLimited", "budgetNote", "requestedTimeoutMs", "timeoutMs"]) {
        def v = multi || r.containsKey(k) ? r.get(k) : row.get(k)
        if (v != null && v != false || (k == "transitioning" && r.containsKey(k))) block.put(k, v)
    }
    return block
}

// The sleep that keeps ticks on the requested cadence: the interval minus the time the tick's
// native read already took, never past the poll's remaining window. Without this subtraction a
// 200 ms poll of a 100 ms read samples every 300 ms and polledCount silently drops.
// FLOOR: reads slower than the interval must not turn the poll into back-to-back native reads
// (a slow hub would then be sampled HARDER than a fast one), so the sleep never drops below half
// the requested interval -- ticks are then spaced by the reads plus that half-interval.
// WINDOW CLAMP: the final tick is the exception. Math.min(interval, remaining) can return LESS
// than the floor when less than the floor remains in the poll window, because the window has
// to win -- the floor bounds cadence between ticks, not how long the last sleep is.
private Integer _pollSleepMs(pollIntervalMs, remainingMs, tickElapsedMs) {
    long requested = pollIntervalMs as Long
    long floor = (long) Math.ceil(requested / 2.0d)
    long interval = Math.max(requested - (tickElapsedMs as Long), floor)
    long remaining = remainingMs > 0 ? (remainingMs as Long) : interval
    return Math.min(interval, remaining) as Integer
}

// Resolve the history window start. `since` (absolute bookmark -- ISO-8601 in the
// same format this tool emits in `date`/`sinceTimestamp`, or epoch milliseconds)
// takes precedence over `hoursBack` (relative) when both are supplied. Returns
// [sinceDate, sinceMode, effectiveHoursBack, sinceEcho]: sinceMode is "explicit"
// when `since` drove the window (effectiveHoursBack null -- it did not bound
// anything) or "relative" when hoursBack did. sinceEcho (explicit mode only) is
// the value to surface back to the caller: the caller's String verbatim (so a
// round-tripped ISO bookmark comes back byte-for-byte, not reformatted into the
// JVM-local TZ), or canonical ISO when the caller passed epoch-ms (keeps the echo
// a presentable string regardless of input type). An unparseable `since` is a
// caller error.
def _resolveSinceWindow(args, hoursBack) {
    if (args.since == null) {
        return [new Date(now() - (hoursBack * 3600000L)), "relative", hoursBack, null]
    }
    def parsed = _parseSinceArg(args.since)
    if (parsed == null) {
        throw new IllegalArgumentException("since is not a valid timestamp: '${args.since}'. " +
            "Use the same ISO-8601 form this tool emits in 'date'/'sinceTimestamp' -- a NUMERIC " +
            "offset, e.g. 2026-06-23T10:00:00.000-0600 or -06:00 (a trailing 'Z' for UTC and a " +
            "millis-less variant are also accepted), or epoch milliseconds (an integer).")
    }
    // Echo a real ISO bookmark String verbatim (the round-trip case, trimmed so a
    // space-padded input does not echo back contaminated); format any epoch-ms input
    // -- Number OR all-digit String -- into canonical ISO so the echo is always a
    // presentable timestamp, never raw digits.
    def echo = (args.since instanceof Number || args.since.toString().trim().isLong()) ?
        parsed.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") : args.since.toString().trim()
    return [parsed, "explicit", null, echo]
}

def toolGetDeviceHistory(args) {
    def hoursBack = Math.min(args.hoursBack ?: 24, 168)
    def limit = Math.min(args.limit ?: 100, 500)
    def attributeFilter = args.attribute
    def (sinceDate, sinceMode, effectiveHoursBack, sinceEcho) = _resolveSinceWindow(args, hoursBack)
    // Optional window END (inclusive), in the same formats as since: a bounded history slice.
    Date untilDate = null
    if (args.until != null) {
        untilDate = _parseSinceArg(args.until)
        if (untilDate == null) throw new IllegalArgumentException("until is not a valid timestamp: '${args.until}'. Use the ISO-8601 form this tool emits in 'date' or epoch milliseconds.")
        if (!untilDate.after(sinceDate)) throw new IllegalArgumentException("until must be later than the window start (${sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")}).")
    }
    def untilEcho = (untilDate != null) ? untilDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") : null

    // App-scope branch: events an installed app/rule emitted, read from the same
    // endpoint the admin UI's per-app Events page uses. The endpoint takes no
    // query params and its row cap is server-side, so attribute/hoursBack/limit
    // are applied client-side exactly like the location branch below -- limit
    // enforced while collecting so an oversized event store can't balloon the
    // response. Rows carry {name, value, descriptionText, date}.
    if (args.appId != null) {
        def appIdStr = args.appId.toString().trim()
        if (!appIdStr.isInteger()) {
            throw new IllegalArgumentException("appId must be numeric: ${appIdStr}")
        }
        def rawJson
        try {
            rawJson = hubInternalGet("/installedapp/eventsJson/${appIdStr}")
        } catch (Exception e) {
            mcpLogError("monitoring", "/installedapp/eventsJson/${appIdStr} fetch failed", e)
            return [success: false, error: "App event history fetch failed: ${e.message}", source: "app", appId: appIdStr as Integer,
                    note: "Likely a transient hub blip -- retry. Verify the appId with hub_list_apps if it persists."]
        }
        def appRows
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(rawJson?.toString() ?: "[]")
            appRows = (parsed instanceof List) ? parsed : []
        } catch (Exception e) {
            mcpLogError("monitoring", "/installedapp/eventsJson/${appIdStr} parse failed", e)
            return [success: false, error: "App event history parse failed: ${e.message}", source: "app", appId: appIdStr as Integer,
                    note: "Retry; if persistent, firmware may have changed the /installedapp/eventsJson format -- report with hub_report_issue."]
        }

        def appResults = []
        def timeFilterUnparseable = 0
        for (evt in appRows) {
            if (!(evt instanceof Map)) continue
            if (attributeFilter && evt.name != attributeFilter) continue
            // Strictly-after window, mirroring the location branch: drop events at or
            // before sinceDate when the date parses (so re-passing a returned `date`
            // as `since` never replays that same event); keep rows whose date doesn't
            // parse (don't silently lose history), counting them so the caller can see
            // the window was not fully enforced.
            def evtDate = null
            try { evtDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", evt.date?.toString()) }
            catch (Exception ignored) { timeFilterUnparseable++ }
            // Strictly-after (exclusive) -- uniform across relative and explicit windows so a
            // returned `date` fed back as `since` never replays itself. At the exact-millisecond
            // boundary this is a deliberate tightening from the prior inclusive behaviour.
            if (evtDate != null && !evtDate.after(sinceDate)) continue
            if (untilDate != null && evtDate != null && evtDate.after(untilDate)) continue
            appResults << [
                name: evt.name,
                value: evt.value,
                description: evt.descriptionText,
                date: evt.date
            ]
            if (appResults.size() >= limit) break
        }

        mcpLog("info", "monitoring", "Retrieved ${appResults.size()} app history event${appResults.size() == 1 ? '' : 's'} for app ${appIdStr} (${sinceMode} window since ${sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")}) from /installedapp/eventsJson")
        def appResult = [
            source: "app",
            appId: appIdStr as Integer,
            attributeFilter: attributeFilter,
            events: appResults,
            count: appResults.size(),
            sinceMode: sinceMode,
            sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        ]
        // Echo only the field that actually bounded the window: hoursBack for a
        // relative window, the verbatim caller bookmark for an absolute one. Mixing
        // both would imply hoursBack bounded the result when `since` did.
        if (sinceMode == "relative") appResult.hoursBack = effectiveHoursBack
        else appResult.since = sinceEcho
        if (untilEcho) appResult.untilTimestamp = untilEcho
        // Mirror the hub-log path: surface rows that escaped the time window
        // because their date would not parse (the window is always active here).
        if (timeFilterUnparseable > 0) appResult.timeFilterUnparseable = timeFilterUnparseable
        return appResult
    }

    // Location-scope branch: when deviceId is omitted, return location history
    // (mode / HSM / hub-variable / sunrise-sunset / system events). There is NO
    // Groovy accessor for this -- neither location.eventsSince(Date, Map) nor
    // getLocationEventsSince(Date) exist on the hub (both NoSuchMethod live). The
    // hub's own Logs page reads it from /logs/eventsJson (per the hub2 frontend),
    // so we hit the same endpoint and parse it. Each row is
    // {name, value, unit, descriptionText, isStateChange, type, date(ISO+offset)}.
    if (args.deviceId == null) {
        def rawJson
        try {
            rawJson = hubInternalGet("/logs/eventsJson")
        } catch (Exception e) {
            mcpLogError("monitoring", "/logs/eventsJson fetch failed", e)
            return [success: false, error: "Location event history fetch failed: ${e.message}", source: "location",
                    note: "Likely a transient hub blip (the same endpoint feeds the hub's Logs page) -- retry."]
        }
        def rows
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(rawJson?.toString() ?: "[]")
            rows = (parsed instanceof List) ? parsed : []
        } catch (Exception e) {
            mcpLogError("monitoring", "/logs/eventsJson parse failed", e)
            return [success: false, error: "Location event history parse failed: ${e.message}", source: "location",
                    note: "Retry; if persistent, firmware may have changed the /logs/eventsJson format -- report with hub_report_issue."]
        }

        // The Logs page's Hub events tab (/hub/eventsJson: reboots, lowMemory, startup) shares
        // the row shape; those rows are merged in newest first and carry kind:'hub'. A failed
        // read of it leaves the location events intact and says so.
        def hubRows = []
        String hubEventsError = null
        try {
            String hubText = hubInternalGet("/hub/eventsJson")?.toString()
            def parsedHub = hubText?.trim() ? new groovy.json.JsonSlurper().parseText(hubText) : null
            if (parsedHub == null) hubEventsError = "the hub returned an empty response"
            else if (parsedHub instanceof List) hubRows = parsedHub
            else hubEventsError = "the hub events list was not a JSON array"
        } catch (Exception e) {
            hubEventsError = (e.message ?: e.toString()).take(200)
        }

        def locResults = []
        def timeFilterUnparseable = 0
        def addRows = { List source, String kind ->
            for (evt in source) {
                if (!(evt instanceof Map)) continue
                if (attributeFilter && evt.name != attributeFilter) continue
                // Strictly-after window: drop events at or before sinceDate when the
                // ISO+offset date parses (so re-passing a returned `date` as `since` never
                // replays that same event); keep rows whose date doesn't parse (don't
                // silently lose history), counting them. The numeric offset (e.g. -0400)
                // parses via the 'Z' pattern.
                def evtDate = null
                try { evtDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", evt.date?.toString()) }
                catch (Exception ignored) { timeFilterUnparseable++ }
                if (evtDate != null && !evtDate.after(sinceDate)) continue
                if (untilDate != null && evtDate != null && evtDate.after(untilDate)) continue
                locResults << [
                    name: evt.name,
                    value: evt.value,
                    unit: evt.unit,
                    description: evt.descriptionText,
                    date: evt.date,
                    type: evt.type,
                    isStateChange: evt.isStateChange,
                    kind: kind,
                    _at: evtDate?.time
                ]
            }
        }
        addRows(rows, "location")
        int hubCount = locResults.size()
        addRows(hubRows, "hub")
        hubCount = locResults.size() - hubCount
        if (hubCount > 0) locResults = locResults.sort { a, b -> (b._at ?: 0L) <=> (a._at ?: 0L) }
        locResults.each { it.remove("_at") }
        int matchedCount = locResults.size()
        if (matchedCount > limit) locResults = locResults.take(limit)

        mcpLog("info", "monitoring", "Retrieved ${locResults.size()} location history event${locResults.size() == 1 ? '' : 's'} (${sinceMode} window since ${sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")}) from /logs/eventsJson")
        def locResult = [
            source: "location",
            attributeFilter: attributeFilter,
            events: locResults,
            count: locResults.size(),
            sinceMode: sinceMode,
            sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        ]
        if (sinceMode == "relative") locResult.hoursBack = effectiveHoursBack
        else locResult.since = sinceEcho
        if (untilEcho) locResult.untilTimestamp = untilEcho
        if (matchedCount > locResults.size()) {
            locResult.truncated = true
            locResult.matchedCount = matchedCount
        }
        // Mirror the hub-log path: surface rows that escaped the always-active
        // time window because their date would not parse.
        if (timeFilterUnparseable > 0) locResult.timeFilterUnparseable = timeFilterUnparseable
        if (hubEventsError != null) locResult.hubEventsError = "Hub events (reboots, low memory) could not be read: ${hubEventsError}".toString()
        return locResult
    }

    def deviceId = _canonicalDeviceIdArg(args.deviceId)
    if (deviceId == null) {
        throw new IllegalArgumentException("deviceId must be a non-empty string or integral number (got: ${_describeValueForError(args.deviceId)})")
    }
    args = args + [deviceId: deviceId]
    _requireDeviceToolAccess(deviceId)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) return [success: false, isError: true, error: "Device metadata fetch failed (/device/fullJson/${deviceId})", note: 'Check native device details and retry.']
    return _deviceHistoryBypass(args, full, sinceDate, sinceMode, effectiveHoursBack, sinceEcho, attributeFilter, limit, untilDate)

}

private Map _deviceHistoryBypass(args, Map fj, sinceDate, sinceMode, effectiveHoursBack, sinceEcho, attributeFilter, limit, Date untilDate = null) {
    def deviceLabel = _bypassDeviceLabel(fj, args.deviceId)
    def rows = _fetchBypassDeviceEvents(args.deviceId)
    if (rows == null) {
        return [success: false, isError: true, error: "Device event history fetch failed (/device/eventsJson/${args.deviceId})", source: "device", device: deviceLabel, deviceId: args.deviceId,
                note: "The native device event store could not be read. Check the device Events page and retry."]
    }
    def results = []
    def timeFilterUnparseable = 0
    for (evt in rows) {
        if (!(evt instanceof Map)) continue
        if (attributeFilter && evt.name != attributeFilter) continue
        // Strictly-after window, mirroring the app/location branches: drop events at or before
        // sinceDate when the ISO+offset date parses; keep (and count) rows whose date doesn't.
        def evtDate = null
        try { evtDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", evt.date?.toString()) }
        catch (Exception ignored) { timeFilterUnparseable++ }
        if (evtDate != null && !evtDate.after(sinceDate)) continue
        if (untilDate != null && evtDate != null && evtDate.after(untilDate)) continue
        results << _mapBypassEventRow(evt)
        if (results.size() >= limit) break
    }
    mcpLog("info", "monitoring", "Retrieved ${results.size()} history event${results.size() == 1 ? '' : 's'} for ${deviceLabel} via allowlist bypass (${sinceMode} window since ${sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")})")
    def deviceResult = [
        source: "device",
        device: deviceLabel,
        deviceId: args.deviceId,
        attributeFilter: attributeFilter,
        events: results,
        count: results.size(),
        sinceMode: sinceMode,
        sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    ]
    if (sinceMode == "relative") deviceResult.hoursBack = effectiveHoursBack
    else deviceResult.since = sinceEcho
    if (untilDate != null) deviceResult.untilTimestamp = untilDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    if (timeFilterUnparseable > 0) deviceResult.timeFilterUnparseable = timeFilterUnparseable
    return deviceResult
}

// /device/preference/save expects a numeric deviceId in its JSON body (the Vue posts
// this.device.id, a number). Coerce the string id to a Long; leave a non-numeric id as-is.
def _prefSaveDeviceId(deviceId) {
    try { return (deviceId != null) ? (deviceId.toString() as Long) : deviceId }
    catch (Exception ignored) { return deviceId }
}

// The device page's editable fields and where each is saved: 'form' is its Device Info save
// (/device/update), 'pane' its Preferences save, 'assistants' /device/updateAssistants, 'enabled'
// /device/disable, 'data' the driver's updateDataValue. confirm fields need confirm and a backup.
private Map _deviceEditFields() {
    return [
        label: [type: 'string', save: 'form'], name: [type: 'string', save: 'form'],
        deviceNetworkId: [type: 'string', save: 'form', confirm: true],
        room: [type: 'string', save: 'form'], tags: [type: 'array', save: 'form'],
        notes: [type: 'string', save: 'form'], defaultIcon: [type: 'string', save: 'form'],
        maxEvents: [type: 'integer', minimum: 1, maximum: 2000, save: 'form'],
        maxStates: [type: 'integer', minimum: 1, maximum: 2000, save: 'form'],
        spammyThreshold: [type: 'integer', minimum: 100, maximum: 2000, save: 'form'],
        deviceTypeId: [type: 'integer', minimum: 1, save: 'form', confirm: true],
        zigbeeId: [type: 'string', save: 'form', confirm: true],
        dashboardIds: [type: 'array', save: 'form', confirm: true],
        meshEnabled: [type: 'boolean', save: 'form', confirm: true],
        meshFullSync: [type: 'boolean', save: 'form', confirm: true],
        showOnHome: [type: 'boolean', save: 'pane'], defaultCurrentState: [type: 'string', save: 'pane'],
        retryEnabled: [type: 'boolean', save: 'pane'],
        homeKitEnabled: [type: 'boolean', save: 'assistants', confirm: true],
        amazonAlexaEnabled: [type: 'boolean', save: 'assistants', confirm: true],
        googleHomeEnabled: [type: 'boolean', save: 'assistants', confirm: true],
        enabled: [type: 'boolean', save: 'enabled'], dataValues: [type: 'object', save: 'data']
    ]
}

// Why the device page offers no control for a field on this device, or null when it does.
private String _deviceEditUnavailable(String field, Map fj) {
    Map d = fj.device
    boolean component = _deviceFlag(d.isComponent)
    boolean linked = _deviceFlag(d.linkedDevice)
    switch (field) {
        case 'label': return _deviceFlag(d.linkedAndDisabled) ? 'A disabled linked device keeps its label.' : null
        case 'name': case 'deviceTypeId':
            return component || linked ? 'A component or linked device takes its name and driver from its parent or source device.' : null
        case 'zigbeeId':
            if (component || linked) return 'A component or linked device takes its Zigbee ID from its parent or source device.'
            return d.zigbeeId ? null : 'The device has no Zigbee ID.'
        case 'deviceNetworkId':
            return (component && !linked) || _deviceFlag(d.linkedLocally) ? 'This device takes its network ID from its parent.' : null
        case 'dashboardIds': return _deviceFlag(fj.hasDashboards) && fj.dashboards instanceof List ? null : 'The hub has no Hubitat Dashboards (legacy) to assign. An Easy Dashboard picks its devices with hub_update_dashboard deviceIds.'
        case 'meshEnabled': return _deviceFlag(d.meshSelectionEnabled) ? null : 'Hub Mesh sharing is not available for this device.'
        case 'meshFullSync': return linked && _deviceFlag(fj.hubMeshRefreshEnabled) ? null : 'Full sync applies to a linked device while Hub Mesh refresh is enabled.'
        case 'retryEnabled': return _deviceFlag(fj.commandRetrySelectionEnabled) || _deviceFlag(d.retryAvailable) ? null : 'Command retry is not available for this device.'
        case 'homeKitEnabled': return _deviceFlag(fj.homeKitSelectionEnabled) ? null : 'HomeKit is not enabled or does not support this device.'
        case 'amazonAlexaEnabled': return _deviceFlag(fj.amazonAlexaInstalled) && _deviceFlag(fj.amazonAlexaSupported) ? null : 'Amazon Alexa is not installed or does not support this device.'
        case 'googleHomeEnabled': return _deviceFlag(fj.googleHomeInstalled) && _deviceFlag(fj.googleHomeSupported) ? null : 'Google Home is not installed or does not support this device.'
        case 'preferences': return linked ? 'A linked device saves its driver preferences on the source hub.' : null
    }
    return null
}

private boolean _deviceFlag(value) {
    return value == true || value?.toString() == "true"
}

private boolean _deviceEditInteger(value, Map spec) {
    if (!(value instanceof Number) || new BigDecimal(value.toString()).stripTrailingZeros().scale() > 0) return false
    return value >= (spec.minimum ?: 1) && (spec.maximum == null || value <= spec.maximum)
}

private void _checkDeviceEditValue(String field, Map spec, value) {
    String bad = null
    if (field == 'room' && !(value instanceof String) && !_deviceEditInteger(value, [:])) bad = 'must be a room name or id'
    else if (spec.type == 'string' && field != 'room' && !(value instanceof String)) bad = 'must be a string'
    else if (spec.type == 'boolean' && !(value instanceof Boolean)) bad = 'must be a boolean'
    else if (spec.type == 'integer' && !_deviceEditInteger(value, spec)) bad = spec.maximum ? "must be an integer from ${spec.minimum} to ${spec.maximum}" : "must be a positive integer"
    else if (field == 'tags' && (!(value instanceof List) || value.any { !(it instanceof String) || it.contains(',') })) bad = 'must be an array of strings without commas'
    else if (field == 'dashboardIds' && (!(value instanceof List) || value.any { !_deviceEditInteger(it, [:]) })) bad = 'must be an array of dashboard IDs'
    else if (field == 'dataValues' && (!(value instanceof Map) || !value || value.any { k, v -> !k?.toString()?.trim() || !(v instanceof String) })) bad = 'must be a non-empty object of string values keyed by data name'
    else if (field == 'dataValues' && value.any { k, v -> _isRedactionMarker(v) }) bad = 'cannot take the redaction marker as a value; omit the key to keep the stored one'
    else if (field in ['name', 'deviceNetworkId'] && !value.trim()) bad = 'cannot be empty'
    if (bad) throw new IllegalArgumentException("${field} ${bad}")
}

private _normalizedDevicePreferenceValue(Map entry, value) {
    if (value == null || (value instanceof String && !value.trim()) || (value instanceof List && value.isEmpty())) {
        throw new IllegalArgumentException("Preference '${entry.name}' cannot use a blank value; omit it to preserve the setting or use {clear:true} to remove an optional setting")
    }
    if (_isRedactionMarker(value)) throw new IllegalArgumentException("Preference '${entry.name}' is the redaction marker, not a value; omit it to keep the stored one")
    def type = entry.type?.toString()
    if (type in ["bool", "boolean"]) {
        if (value instanceof Boolean) return value
        if (value?.toString() in ["true", "false"]) return value.toString() == "true"
        throw new IllegalArgumentException("Preference '${entry.name}' requires a boolean value")
    }
    if (type in ["number", "decimal"]) {
        def number
        try { number = new BigDecimal(value.toString().trim()) }
        catch (Exception ignored) { throw new IllegalArgumentException("Preference '${entry.name}' requires a numeric value") }
        def range = entry.range?.toString()
        if (range && range.contains("..")) {
            def bounds = range.split("\\.\\.", -1)
            if (bounds.size() != 2) throw new IllegalArgumentException("Preference '${entry.name}' has an unsupported numeric range; inspect its configuration")
            def limits = []
            for (bound in bounds) {
                def token = bound.trim()
                if (!token || token == '*') limits << null
                else {
                    try { limits << new BigDecimal(token) }
                    catch (Exception ignored) { throw new IllegalArgumentException("Preference '${entry.name}' has an unsupported numeric range; inspect its configuration") }
                }
            }
            if (limits[0] != null && number < limits[0]) throw new IllegalArgumentException("Preference '${entry.name}' is below its declared range ${range}")
            if (limits[1] != null && number > limits[1]) throw new IllegalArgumentException("Preference '${entry.name}' is above its declared range ${range}")
        }
        return number
    }
    if (type == "enum") {
        def multiple = _deviceFlag(entry.multiple)
        if (!multiple && value instanceof List) throw new IllegalArgumentException("Preference '${entry.name}' accepts a single option")
        def values = multiple ? (value instanceof List ? value : value.toString().split(",").toList()) : [value]
        def allowed = []
        def options = entry.options
        if (options instanceof Map) allowed = options.keySet().collect { it.toString() }
        else if (options instanceof List) {
            for (option in options) {
                if (option instanceof Map) allowed.addAll(option.keySet().collect { it.toString() })
                else if (option != null) allowed << option.toString()
            }
        }
        def normalized = values.collect { it?.toString() }
        if (allowed && normalized.any { !allowed.contains(it) }) throw new IllegalArgumentException("Preference '${entry.name}' contains an unknown option; read its configuration options first")
        return multiple ? normalized : normalized[0]
    }
    if (value instanceof Map || value instanceof List || value instanceof Boolean) {
        throw new IllegalArgumentException("Preference '${entry.name}' requires a text value")
    }
    return value.toString()
}

// Each preference becomes [type, value] or [type, clear:true], checked against the driver's
// declarations in the device's current settings.
private Map _planDevicePreferences(Map fj, Map preferences, Map fields) {
    String unavailable = _deviceEditUnavailable('preferences', fj)
    if (unavailable) throw new IllegalArgumentException(unavailable)
    if (fields.containsKey('deviceTypeId') && fields.deviceTypeId.toString() != fj.device.deviceTypeId?.toString()) {
        throw new IllegalArgumentException("Change the driver first, then read its new preference definitions before updating preferences")
    }
    def model = _readDevicePreferenceModel(fj)
    if (model.writeSafe != true) throw new IllegalArgumentException("Unable to read complete preference definitions/storage before updating; inspect hub_get_device(mode='configuration')")
    def planned = [:]
    for (pref in preferences) {
        String name = pref.key.toString()
        def setting = pref.value
        def entry = _lookupDevicePreference(model, name)
        if (!entry) throw new IllegalArgumentException("Unknown preference '${name}'; read hub_get_device(mode='configuration') for declared names")
        def type = entry.type in ['bool', 'boolean'] ? 'bool' : entry.type
        boolean clear = setting instanceof Map && setting.get('clear') == true
        if (setting instanceof Map) {
            def suppliedType = setting.type in ['bool', 'boolean'] ? 'bool' : setting.type
            if ((setting.containsKey('clear') && (!clear || setting.containsKey('value'))) ||
                (!clear && !setting.containsKey('value')) ||
                (suppliedType != null && suppliedType.toString() != type) ||
                setting.keySet().any { !(it in ['type', 'value', 'clear', 'multiple']) }) {
                throw new IllegalArgumentException("Preference '${name}' takes a value of its declared type, {value, multiple} or {clear:true}; clear and value cannot be combined")
            }
            if (setting.containsKey('multiple')) {
                if (clear || type != 'enum' || !(setting.multiple instanceof Boolean)) {
                    throw new IllegalArgumentException("Preference '${name}' accepts a multiple boolean only with an enum value")
                }
                if (entry.multiple != null && setting.multiple != entry.multiple) {
                    throw new IllegalArgumentException("Preference '${name}' multiple conflicts with its current native metadata")
                }
                entry = entry + [multiple: setting.multiple]
            }
        }
        if (!clear && type == 'enum' && entry.multiple == null) {
            throw new IllegalArgumentException("Preference '${name}' has unavailable selection cardinality; check driverSource or previously read metadata and supply multiple:true or multiple:false with the value")
        }
        if (clear && _deviceFlag(entry.required)) throw new IllegalArgumentException("Required preference '${name}' cannot be cleared")
        def raw = setting instanceof Map ? setting.value : setting
        planned.put(name, clear ? [type: type, clear: true, value: null] : [type: type, value: _normalizedDevicePreferenceValue(entry, raw)])
    }
    return planned
}

// Checks that need the device's current native model, before anything is written. Returns the
// room as the id the form sends and the name it reads back as.
private Map _planDeviceEdit(Map fj, Map fields, Map preferences = [:]) {
    Map d = fj.device
    def send = [:]
    for (field in fields) {
        String name = field.key
        def value = field.value
        String unavailable = _deviceEditUnavailable(name, fj)
        if (unavailable) throw new IllegalArgumentException("${name} cannot be changed: ${unavailable}")
        if (name == 'room') {
            def room = value == '' ? [id: 0, name: null] : _resolveDeviceRoom(value.toString(), value instanceof Number)
            send.roomId = room.id
            send.roomName = room.name
        } else if (name == 'defaultCurrentState' && value) {
            if (!(d.currentStates instanceof Map)) throw new IllegalArgumentException("Unable to read authoritative current-state attributes before updating defaultCurrentState; refresh device configuration first")
            if (!d.currentStates.containsKey(value)) throw new IllegalArgumentException("defaultCurrentState must name one of the device's current-state attributes")
        } else if (name == 'dashboardIds') {
            def known = fj.dashboards.collect { it.id?.toString() }
            if (value.any { !known.contains(it.toString()) }) throw new IllegalArgumentException("dashboardIds contains an unknown dashboard ID; read configuration options first")
        } else if (name == 'deviceTypeId' && value.toString() != d.deviceTypeId?.toString()) {
            def driversText = hubInternalGet("/device/drivers")
            def drivers = driversText ? new groovy.json.JsonSlurper().parseText(driversText)?.drivers : null
            if (!(drivers instanceof List)) throw new IllegalArgumentException("Unable to discover driver IDs from /device/drivers")
            if (!drivers.any { it.id?.toString() == value.toString() && it.type != "dep" && it.category != "Hidden" }) {
                throw new IllegalArgumentException("deviceTypeId is not an available driver ID; read configuration driver options first")
            }
        } else if (name == 'deviceNetworkId' && _deviceFlag(d.linkedDevice) && value != '0') {
            // '0' keeps the current link; any other value must be a listed target.
            def targetsText = hubInternalGet("/device/accessibleLinkedDevices")
            def targets = targetsText ? new groovy.json.JsonSlurper().parseText(targetsText)?.devices : null
            if (!(targets instanceof List) || !targets.any { !_deviceFlag(it.linkedLocally) && "${it.hubId}-${it.deviceId}" == value }) {
                throw new IllegalArgumentException("deviceNetworkId is not an available linked-device target; read hub_get_device(mode='configuration') options first")
            }
        }
    }
    if (fields.keySet().any { _deviceEditFields()[it].save == 'assistants' } &&
        !['homeKitEnabled', 'amazonAlexaEnabled', 'googleHomeEnabled'].every { fj.get(it) instanceof Boolean }) {
        throw new IllegalArgumentException("Unable to read all current assistant assignments before updating; refresh device configuration first")
    }
    if (fields.keySet().any { _deviceEditFields()[it].save == 'form' }) _requireCompleteDeviceFormSource(fj, d.id)
    if ((preferences || fields.keySet().any { _deviceEditFields()[it].save == 'pane' }) &&
        (!(d.showOnHome instanceof Boolean) || !(d.retryEnabled instanceof Boolean) || !d.containsKey('defaultCurrentState'))) {
        throw new IllegalArgumentException("Unable to read the device's current Home visibility, Status attribute and command retry; no update sent. Refresh hub_get_device(mode='configuration') and retry.")
    }
    return send
}

// Text: room name (case-insensitive), else id, so a room named "3" wins over room id 3. A number is an id.
private Map _resolveDeviceRoom(String room, boolean byId = false) {
    def rooms
    try {
        rooms = getRooms()
    } catch (Exception e) {
        throw new IllegalArgumentException("Unable to list rooms to resolve '${room}': ${e.message ?: e.toString()}")
    }
    def match = (byId ? null : rooms?.find { it.name?.toString()?.toLowerCase() == room.toLowerCase() }) ?: rooms?.find { it.id?.toString() == room }
    if (match == null) {
        def names = rooms ? rooms.collect { it.name } : []
        throw new IllegalArgumentException("Room '${room}' not found.${names ? ' Available rooms: ' + names.join(', ') : ''}")
    }
    return [id: match.id, name: match.name?.toString()]
}

def toolUpdateDevice(args) {
    def deviceId = _canonicalDeviceIdArg(args.deviceId)
    if (deviceId == null) throw new IllegalArgumentException("deviceId is required")
    def fieldsArg = args.get('fields')
    if (fieldsArg != null && !(fieldsArg instanceof Map)) throw new IllegalArgumentException("fields must be an object keyed by editable field name")
    if (args.preferences != null && !(args.preferences instanceof Map)) throw new IllegalArgumentException("preferences must be an object keyed by declared preference name")
    if (args.confirm != null && !(args.confirm instanceof Boolean)) throw new IllegalArgumentException("confirm must be a boolean")
    Map fields = fieldsArg ?: [:]
    Map preferences = args.preferences ?: [:]
    if (!fields && !preferences) throw new IllegalArgumentException("Nothing to update: pass fields and/or preferences; hub_get_device(mode='configuration') lists them")
    Map specs = _deviceEditFields()
    for (field in fields) {
        def spec = specs[field.key]
        if (spec == null) throw new IllegalArgumentException("Unknown field '${field.key}'; hub_get_device(mode='configuration') lists the editable fields")
        _checkDeviceEditValue(field.key, spec, field.value)
    }
    // A whole number sent as 100.0 would post "100.0", which the hub stores as no value.
    fields = fields.collectEntries { k, v ->
        [(k): specs[k].type == 'integer' || (k == 'room' && v instanceof Number) ? new BigDecimal(v.toString()).toBigInteger() :
              k == 'dashboardIds' ? v.collect { new BigDecimal(it.toString()).toBigInteger() } : v]
    }
    if (fields.keySet().any { specs[it].confirm }) requireDestructiveConfirm(args.confirm)
    _requireDeviceToolAccess(deviceId)
    def fj = _fetchDeviceFullJson(deviceId)
    if (!(fj?.device instanceof Map)) {
        return [success: false, isError: true, error: "Unable to read /device/fullJson before updating device ${deviceId}; no update sent.",
                note: "Read hub_get_device(mode='configuration') and retry when native device details are available."]
    }
    if (fields.dataValues && fields.enabled != true && _deviceFlag(fj.device.disabled)) {
        throw new IllegalArgumentException("Device ${deviceId} is disabled, and data values never land on a disabled device. Send enabled: true with the data values (the device is enabled first), or enable it first.")
    }
    Map send = _planDeviceEdit(fj, fields, preferences)
    Map planned = preferences ? _planDevicePreferences(fj, preferences, fields) : [:]
    return _applyDeviceEdit(deviceId, fj, fields, send, planned)
}

// One native save per page section (data values: one call per key), in the page's order, then one
// read-back verifies every field. Data values never land on a disabled device, so an enable goes
// before them and a disable after them.
private Map _applyDeviceEdit(deviceId, Map fj, Map fields, Map send, Map preferences) {
    Map d = fj.device
    String deviceLabel = _bypassDeviceLabel(fj, deviceId)
    Map specs = _deviceEditFields()
    def requested = fields.keySet() + preferences.keySet().collect { "preference.${it}".toString() }
    mcpLog("warn", "device", "hub_update_device for '${deviceLabel}' (ID: ${deviceId}): ${requested.join(', ')}")
    def failed = [:]

    def form = fields.findAll { specs[it.key].save == 'form' }
    if (form) {
        def overrides = new LinkedHashMap(form)
        overrides.remove('room')
        if (send.containsKey('roomId')) overrides.roomId = send.roomId
        if (overrides.containsKey('tags')) overrides.tags = overrides.tags.collect { it.trim() }.findAll { it }.join(',')
        String error = _saveDeviceInfoForm(fj, overrides)
        if (error) form.keySet().each { failed.put(it, error) }
    }
    def pane = fields.findAll { specs[it.key].save == 'pane' }
    if (pane || preferences) {
        String error = _saveDevicePreferencesPane(deviceId, fj, pane, preferences)
        if (error) (pane.keySet() + preferences.keySet().collect { "preference.${it}".toString() }).each { failed.put(it, error) }
    }
    def assistants = fields.findAll { specs[it.key].save == 'assistants' }
    def assistantResults = null
    if (assistants) {
        def payload = [deviceId: _prefSaveDeviceId(deviceId), homeKitEnabled: fj.homeKitEnabled,
                       amazonAlexaEnabled: fj.amazonAlexaEnabled, googleHomeEnabled: fj.googleHomeEnabled] + assistants
        try {
            assistantResults = hubInternalPostJson("/device/updateAssistants", groovy.json.JsonOutput.toJson(payload))?.assistants
        } catch (Exception e) {
            mcpLog("error", "device", "hub_update_device: assistant save for ${deviceId} failed (${e.class.simpleName})")
            assistants.keySet().each { failed.put(it, "The assistant save failed; inspect the device before retrying.") }
        }
    }
    Closure setEnabled = {
        try {
            hubInternalPostJson("/device/disable", groovy.json.JsonOutput.toJson([id: _prefSaveDeviceId(deviceId), disable: !fields.enabled]))
        } catch (Exception e) {
            mcpLog("error", "device", "hub_update_device: enable/disable for ${deviceId} failed (${e.class.simpleName})")
            failed.put('enabled', "The enable/disable request failed; inspect the device before retrying.")
        }
    }
    if (fields.enabled == true) setEnabled()
    if (fields.dataValues && failed.containsKey('enabled')) {
        failed.put('dataValues', "Not written: the enable failed, and data values never land on a disabled device.")
    } else if (fields.dataValues) {
        def dataErrors = []
        _applyNativeDeviceDataValues(deviceId, fields.dataValues, [], dataErrors, false)
        if (dataErrors) failed.put('dataValues', dataErrors.collect { it.error }.unique().join(' '))
    }
    if (fields.enabled == false) setEnabled()

    def after = _fetchDeviceFullJson(deviceId)
    def changes = []
    def unchanged = []
    def errors = []
    for (field in fields) {
        String name = field.key
        if (failed.containsKey(name)) { errors << [property: name, error: failed.get(name)]; continue }
        if (!(after?.device instanceof Map)) { errors << [property: name, error: "Saved, but the device could not be read back to confirm it."]; continue }
        if (!_deviceEditReadable(name, after)) { errors << [property: name, error: "Saved, but ${name} could not be read back to confirm it."]; continue }
        def actual = _deviceEditCurrentValue(name, after)
        def wanted = field.value
        if (name == 'dataValues') {
            for (item in wanted) {
                if (actual instanceof Map && actual.get(item.key)?.toString() == item.value) {
                    if (d.data instanceof Map && d.data.get(item.key)?.toString() == item.value) unchanged << "dataValue.${item.key}".toString()
                    else changes << [property: "dataValue.${item.key}", newValue: _deviceConfigurationPublicValue(item.value, item.key)]
                }
                else errors << [property: "dataValue.${item.key}", error: "Saved, but the data value reads back as ${groovy.json.JsonOutput.toJson(_deviceConfigurationPublicValue(actual.get(item.key), item.key))}; inspect device data before retrying."]
            }
            continue
        }
        if (name == 'room') wanted = send.roomName
        boolean matches = _deviceEditValuesMatch(name, wanted, actual)
        boolean keepLink = name == 'deviceNetworkId' && wanted == '0' && _deviceFlag(d.linkedDevice)
        if (keepLink) matches = actual == d.deviceNetworkId
        if (matches && (keepLink || _deviceEditReadable(name, fj) && _deviceEditValuesMatch(name, wanted, _deviceEditCurrentValue(name, fj)))) {
            unchanged << name
        } else if (matches) {
            changes << [property: name, oldValue: _deviceConfigurationPublicValue(_deviceEditCurrentValue(name, fj), name),
                        newValue: _deviceConfigurationPublicValue(name == 'room' ? (wanted ?: 'none') : wanted, name)]
        } else {
            def assistantError = specs[name].save == 'assistants' ?
                assistantResults?.get(name.replace('Enabled', ''))?.reason : null
            errors << [property: name, error: assistantError ? "Assistant update failed: ${assistantError}" :
                "Saved, but ${name} reads back as ${groovy.json.JsonOutput.toJson(_deviceConfigurationPublicValue(actual, name))}; inspect the device before retrying."]
        }
    }
    if (preferences) {
        def model = after?.device instanceof Map ? _readDevicePreferenceModel(after) : null
        def beforeModel = _readDevicePreferenceModel(fj)
        for (pref in preferences) {
            String key = "preference.${pref.key}".toString()
            if (failed.containsKey(key)) errors << [property: key, error: failed.get(key)]
            else if (_devicePreferenceUnchanged(beforeModel, pref.key.toString(), pref.value) &&
                     _devicePreferenceMatches(model, pref.key.toString(), pref.value)) unchanged << key
            else _verifyDevicePreferenceWrite(pref.key.toString(), pref.value, changes, errors, model)
        }
    }
    if (changes.any { it.property == 'label' }) deviceLabel = after.device.label ?: after.device.name ?: deviceLabel
    mcpLog(errors ? "error" : "info", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}): ${changes.size()} changes, ${unchanged.size()} unchanged, ${errors.size()} errors")
    def result = [success: errors.isEmpty(), device: deviceLabel, deviceId: deviceId, changes: changes]
    // Already as requested before the save: saved and read back, but nothing changed.
    if (unchanged) result.unchanged = unchanged
    if (errors) {
        result.errors = errors
        result.isError = true
        result.message = "Updated ${changes.size()} of ${changes.size() + unchanged.size() + errors.size()}; inspect errors and the device before retrying."
    }
    return result
}

// Whether the read-back carries a well-formed value for the field, so a missing one never confirms a clear.
private boolean _deviceEditReadable(String name, Map fj) {
    Map d = fj.device
    switch (name) {
        case 'room': return d.containsKey('roomName') && (d.roomName == null || d.roomName instanceof String)
        case 'enabled': return d.disabled instanceof Boolean || d.disabled?.toString() in ['true', 'false']
        case 'tags': return d.containsKey('tags') && _normalizedDeviceTags(d.tags) != null
        case 'dataValues': return d.data instanceof Map
        case 'dashboardIds': return fj.dashboards instanceof List
        case 'homeKitEnabled': case 'amazonAlexaEnabled': case 'googleHomeEnabled': return fj.get(name) instanceof Boolean
    }
    return d.containsKey(name)
}

// An editable field's current value as the device model holds it.
private _deviceEditCurrentValue(String name, Map fj) {
    Map d = fj.device
    switch (name) {
        case 'room': return d.roomName ?: null
        case 'enabled': return d.disabled == null ? null : !_deviceFlag(d.disabled)
        case 'tags': return _normalizedDeviceTags(d.tags)
        case 'dataValues': return d.data
        case 'dashboardIds': return fj.dashboards instanceof List ? fj.dashboards.findAll { _deviceFlag(it.selected) }.collect { it.id } : null
        case 'homeKitEnabled': case 'amazonAlexaEnabled': case 'googleHomeEnabled': return fj.get(name)
    }
    return d.get(name)
}

private boolean _deviceEditValuesMatch(String name, wanted, actual) {
    if (name == 'tags') return _normalizedDeviceTags(actual) == _normalizedDeviceTags(wanted)
    if (name == 'dashboardIds') return actual instanceof List && actual.collect { it.toString() }.sort() == wanted.collect { it.toString() }.sort()
    if (wanted instanceof Boolean) return (actual instanceof Boolean || actual?.toString() in ['true', 'false']) && _deviceFlag(actual) == wanted
    // A cleared text field reads back as null.
    return (actual == null ? '' : actual.toString()) == (wanted == null ? '' : wanted.toString())
}

// The device page's Device Info save. It rebuilds the whole device from the posted fields, so
// every current value is sent; a null is left out, which keeps it null.
private String _saveDeviceInfoForm(Map fj, Map overrides) {
    Map d = fj.device
    def model = [
        name: d.name, label: d.label, zigbeeId: d.zigbeeId,
        maxEvents: d.maxEvents, maxStates: d.maxStates, spammyThreshold: d.spammyThreshold,
        deviceNetworkId: d.deviceNetworkId, deviceTypeId: d.deviceTypeId,
        deviceTypeReadableType: d.deviceTypeReadableType, roomId: d.roomId,
        meshEnabled: d.meshEnabled, retryEnabled: d.retryEnabled, meshFullSync: d.meshFullSync,
        homeKitEnabled: fj.homeKitEnabled, locationId: d.locationId, hubId: d.hubId, groupId: d.groupId,
        dashboardIds: fj.dashboards.findAll { _deviceFlag(it?.selected) }.collect { it.id }.join(','),
        tags: d.tags, defaultIcon: d.defaultIcon, notes: d.notes,
        id: d.id, version: d.version, controllerType: d.controllerType
    ]
    model.putAll(overrides)
    if (overrides.dashboardIds instanceof List) model.dashboardIds = overrides.dashboardIds.join(',')
    def parts = []
    for (entry in model) {
        if (entry.value == null) continue
        def value = entry.value == true ? 'on' : entry.value.toString()
        parts << "${java.net.URLEncoder.encode(entry.key, 'UTF-8')}=${java.net.URLEncoder.encode(value, 'UTF-8')}"
    }
    try {
        def resp = hubInternalPostFormRaw("/device/update", parts.join('&'))
        if (resp?.data instanceof String && resp.data.contains('"failure"')) return "The hub refused the Device Info save; none of its fields changed."
    } catch (Exception e) {
        mcpLog("error", "device", "hub_update_device: Device Info save for ${d.id} failed (${e.class.simpleName})")
        return "The Device Info save failed; inspect the device before retrying."
    }
    return null
}

// The device page's Preferences save. It also stores Home visibility, the Status attribute and
// command retry, so their current values are sent unless the update sets them, and it runs the
// driver's updated().
private String _saveDevicePreferencesPane(deviceId, Map fj, Map pane, Map preferences) {
    Map d = fj.device
    def rows = []
    for (pref in preferences) {
        def setting = pref.value
        // A multi-select list goes as its JSON text; the hub stores a raw list comma-joined, which drivers read as one string.
        def value = setting.clear == true ? '' : (setting.value instanceof List ? groovy.json.JsonOutput.toJson(setting.value) : setting.value)
        rows << [name: pref.key, type: setting.type, value: value]
    }
    def payload = [deviceId: _prefSaveDeviceId(deviceId),
        defaultCurrentState: pane.containsKey('defaultCurrentState') ? pane.defaultCurrentState : (d.defaultCurrentState ?: ''),
        commandRetry: pane.containsKey('retryEnabled') ? pane.retryEnabled : d.retryEnabled,
        showOnHome: pane.containsKey('showOnHome') ? pane.showOnHome : d.showOnHome, preferences: rows]
    try {
        def response = hubInternalPostJson('/device/preference/save', groovy.json.JsonOutput.toJson(payload))
        if (response instanceof Map && (response.success == false || response._unparseable == true)) {
            return response._unparseable == true ? 'Preference save returned an unreadable response; inspect the device before retrying.' :
                'The hub refused the preference save; inspect the device before retrying.'
        }
    } catch (Exception e) {
        mcpLog("error", "device", "hub_update_device: preference save for ${deviceId} failed (${e.class.simpleName})")
        return "The preference save failed; inspect the device before retrying."
    }
    return null
}

private boolean _devicePreferenceMatches(Map model, String name, Map setting) {
    def entry = model == null ? null : _lookupDevicePreference(model, name)
    if (model?.writeSafe != true || entry == null || entry.valueStatus in ["unknown", "invalid"]) return false
    return setting.clear == true ? (!entry.valuePresent && entry.valueStatus == 'unset') :
        (entry.valuePresent && entry.valueStatus == 'stored' && entry.value == setting.value)
}

// A multi-select stored comma-joined reads the same but reaches the driver as one String, so only
// the JSON form the save writes counts as already set.
private boolean _devicePreferenceUnchanged(Map model, String name, Map setting) {
    if (!_devicePreferenceMatches(model, name, setting)) return false
    def entry = _lookupDevicePreference(model, name)
    return entry.multiple != true || setting.clear == true || entry.rawValue == groovy.json.JsonOutput.toJson(setting.value)
}

private void _verifyDevicePreferenceWrite(String name, Map setting, List changes, List errors, Map model) {
    def entry = model == null ? null : _lookupDevicePreference(model, name)
    if (model?.writeSafe != true || entry == null || entry.valueStatus in ["unknown", "invalid"]) {
        errors << [property: "preference.${name}", stage: "verify", status: entry?.valueStatus == "invalid" ? "invalid" : "unavailable", error: "Saved, but the preference could not be read back to confirm it."]
        return
    }
    if (_devicePreferenceMatches(model, name, setting)) {
        changes << [property: "preference.${name}", newValue: _devicePreferenceIsSecret(entry) ? [type: entry.type, value: _appConfigRedactedMarker()] : setting]
    } else {
        errors << [property: "preference.${name}", stage: "verify", status: "mismatch", error: "Saved, but the preference reads back as a different value. Inspect the device configuration before retrying."]
    }
}

// The driver's updateDataValue, one call per key. verify=false skips the read-back; the update tool confirms
// data values from its own single read-back.
private void _applyNativeDeviceDataValues(deviceId, Map values, List changes, List errors, boolean verify = true) {
    for (item in values) {
        try {
            def payload = [id: _prefSaveDeviceId(deviceId), method: 'updateDataValue',
                           args: [[type: 'STRING', value: item.key], [type: 'STRING', value: item.value]]]
            def result = hubInternalPostJson('/device/runmethod', groovy.json.JsonOutput.toJson(payload))
            if (!(result instanceof Map) || result.success != true) {
                errors << [property: "dataValue.${item.key}", error: 'Native data-value update was not accepted; inspect device data before retrying.']
            }
        } catch (Exception ignored) {
            errors << [property: "dataValue.${item.key}", error: 'Native data-value update failed; inspect device data before retrying.']
        }
    }
    if (!verify || errors) return
    def data = _fetchDeviceFullJson(deviceId)?.device?.data
    for (item in values) {
        if (data instanceof Map && data.get(item.key)?.toString() == item.value) changes << [property: "dataValue.${item.key}", newValue: item.value]
        else errors << [property: "dataValue.${item.key}", error: 'Native update accepted but the data value could not be confirmed; inspect device data before retrying.']
    }
}

private void _requireCompleteDeviceFormSource(Map full, deviceId) {
    def d = full.device
    // Every field the device page's Device Info save sends; nullable ones must still be present.
    def required = ["id", "version", "controllerType", "name", "label", "zigbeeId", "maxEvents", "maxStates",
        "spammyThreshold", "deviceNetworkId", "deviceTypeId", "deviceTypeReadableType", "roomId",
        "meshEnabled", "retryEnabled", "meshFullSync", "locationId", "hubId", "groupId", "tags", "defaultIcon", "notes"]
    def missing = required.findAll { !d.containsKey(it) }
    if (missing) throw new IllegalArgumentException("The device's native model is missing ${missing.join(', ')}; no update sent. Refresh hub_get_device(mode='configuration') and retry.")
    if (d.id?.toString() != deviceId?.toString() || d.version == null || !(full.homeKitEnabled instanceof Boolean) ||
        !(full.dashboards instanceof List) || full.dashboards.any { !(it instanceof Map) || !(it.selected instanceof Boolean) || !_deviceEditInteger(it.id, [:]) }) {
        throw new IllegalArgumentException("The device's native model is incomplete (id, version, HomeKit or dashboard assignments); no update sent. Refresh hub_get_device(mode='configuration') and retry.")
    }
}

private List _normalizedDeviceTags(value) {
    if (value == null) return []
    def tags = value instanceof String ? value.split(',').toList() : value
    if (!(tags instanceof List) || tags.any { !(it instanceof String) }) return null
    return tags.collect { it.trim() }.findAll { it }
}

def toolCreateDevice(args) {
    // Resolve the native driver type before choosing one creation endpoint; a failed mutation
    // cannot safely be retried through another endpoint because its device may already exist.
    // This creates a device without radio pairing -- useful for LAN/integration/cloud and
    // software/component drivers that have no pairing flow. Radio drivers created this way
    // are orphan shells (no node), so we warn. MCP-managed virtual devices have their own
    // tool (hub_manage_virtual_device); this is the broader catalog path.
    args = args ?: [:]
    if (args.confirm != true) {
        throw new IllegalArgumentException("confirm=true is required to create a device.")
    }

    // Hub Mesh LINK vs driver-based CREATE are two mutually-exclusive shapes of this tool.
    // Validation runs before any hub call so a bad shape can be corrected and retried cleanly.
    // "Wants to link" is decided by a NON-BLANK mesh source value, not key presence: a caller passing
    // meshSourceHubId:null on a normal driver create must not be pushed onto the link path.
    String meshHubId = args.meshSourceHubId?.toString()?.trim()
    String meshDeviceId = args.meshSourceDeviceId?.toString()?.trim()
    boolean wantsMeshLink = meshHubId || meshDeviceId
    boolean hasType = args.deviceTypeId != null && args.deviceTypeId.toString().trim() != ""
    if (wantsMeshLink) {
        if (hasType) {
            throw new IllegalArgumentException(
                "Provide EITHER deviceTypeId (driver-based create) OR meshSourceHubId + meshSourceDeviceId (Hub Mesh link) -- not both.")
        }
        // The link form takes only the source pair (+ confirm); create-only fields (label, ...) do not
        // apply to a linked proxy -- its identity mirrors the peer's source device.
        def createOnly = ["label"].findAll { args.get(it) != null && args.get(it).toString().trim() != "" }
        if (createOnly) {
            throw new IllegalArgumentException(
                "${createOnly.join(', ')} cannot be set when linking a Hub Mesh device -- a linked proxy mirrors the peer's source device. Drop it and pass only meshSourceHubId + meshSourceDeviceId.")
        }
        if (!meshHubId || !meshDeviceId) {
            throw new IllegalArgumentException(
                "Linking a Hub Mesh device needs BOTH meshSourceHubId and meshSourceDeviceId " +
                "(the hubId + deviceId of one hub_get_hub_mesh availableLinkedDevices[] row).")
        }
        return _createLinkedMeshDevice(meshHubId, meshDeviceId)
    }

    def typeId = args.deviceTypeId
    if (typeId == null || typeId.toString().trim() == "") {
        throw new IllegalArgumentException("deviceTypeId is required -- the driver-type id from hub_list_drivers(include='all') (the 'id' field). To link a device shared by a peer hub instead, pass meshSourceHubId + meshSourceDeviceId.")
    }
    typeId = typeId.toString().trim()

    String driverType
    try {
        def catalogText = hubInternalGet('/device/drivers')
        def catalog = catalogText ? new groovy.json.JsonSlurper().parseText(catalogText) : null
        if (!(catalog instanceof Map) || !(catalog.drivers instanceof List)) {
            return [success: false, isError: true, error: 'The native driver catalog is unavailable or invalid; no device create request sent.',
                    note: "Verify the deviceTypeId via hub_list_drivers(include='all', nameFilter='<driver name>')."]
        }
        def matches = catalog.drivers.findAll { row -> row instanceof Map && row.id?.toString() == typeId }
        if (matches.size() != 1 || !(matches[0].type in ['sys', 'usr'])) {
            return [success: false, isError: true, error: "Could not resolve one supported native driver type for ${typeId}; no device create request sent.",
                    note: "Verify the deviceTypeId via hub_list_drivers(include='all', nameFilter='<driver name>')."]
        }
        driverType = matches[0].type.toString()
    } catch (Exception e) {
        return [success: false, isError: true, error: 'Hub call failed reading the driver catalog; no device create request sent.',
                note: "Verify the deviceTypeId via hub_list_drivers(include='all', nameFilter='<driver name>')."]
    }

    // The user-driver create takes the label; a built-in driver's create is labelled afterwards.
    String wantLabel = args?.label != null && args.label.toString().trim() ? args.label.toString() : null
    def resp
    try {
        def respText = driverType == 'usr'
            ? hubInternalGet('/device/createVirtual', [deviceTypeId: typeId] + (wantLabel ? [deviceLabel: wantLabel] : [:]), 30)
            : hubInternalGet("/device/sysDriverByIdJson/${java.net.URLEncoder.encode(typeId, 'UTF-8')}", null, 30)
        // Parse INSIDE the try: a 200 carrying an HTML/login body (not JSON) makes parseText
        // throw, and it must return the same structured runtime-error shape as a fetch failure
        // rather than escaping as an unstructured tool error.
        resp = respText ? new groovy.json.JsonSlurper().parseText(respText) : null
    } catch (Exception e) {
        return [success: false, isError: true, error: "Hub call failed creating device from driver-type ${typeId}: ${e.message}",
                note: "Verify the deviceTypeId via hub_list_drivers(include='all', nameFilter='<driver name>')."]
    }
    // Vue's /device/createVirtual response identifies success by deviceId and omits success.
    if (resp?.deviceId != null && resp?.success == null) resp.put("success", true)
    if (resp?.success != true || resp?.deviceId == null) {
        return [success: false, isError: true, error: resp?.errorMessage ?: "Hub did not create a device for driver-type ${typeId}",
                note: "Verify the deviceTypeId via hub_list_drivers(include='all', nameFilter='<driver name>')."]
    }
    def newId = resp.deviceId.toString()

    def warnings = []

    // Inspect what was created (driver type, radio-ness) and optionally apply a label.
    def info = null
    try {
        info = _fetchDeviceFullJson(newId)?.device
    } catch (Exception e) {
        mcpLog("warn", "device", "hub_create_device: could not read back device ${newId} to confirm type: ${e.message}")
    }
    // A null read-back is non-fatal but must not be fully silent: without the type we cannot
    // tell whether this is a radio orphan shell, so the radio-shell warning would be lost.
    if (info == null) {
        warnings << "Created device ${newId} but could not read it back to confirm type -- if this is a radio driver it may be a non-functional orphan shell; verify with hub_get_device."
    }

    def appliedLabel = wantLabel != null && info?.label?.toString() == wantLabel ? wantLabel : null
    if (wantLabel != null && appliedLabel == null) {
        def fresh = _fetchDeviceFullJson(newId)
        String error = null
        try {
            if (!(fresh?.device instanceof Map)) error = 'the new device could not be read'
            else {
                _requireCompleteDeviceFormSource(fresh, newId)
                error = _saveDeviceInfoForm(fresh, [label: wantLabel])
            }
        } catch (IllegalArgumentException e) {
            error = e.message
        }
        if (error == null && _fetchDeviceFullJson(newId)?.device?.label?.toString() == wantLabel) appliedLabel = wantLabel
        else {
            warnings << "Device created but label '${wantLabel}' could not be applied (${error ?: 'the label did not read back'}). Set it with hub_update_device(fields={label:...})."
            mcpLog("warn", "device", "hub_create_device: label not applied to ${newId}: ${error ?: 'no read-back match'}")
        }
    }

    def readable = (info?.deviceTypeReadableType ?: "").toString().toLowerCase()
    def ctype = (info?.controllerType ?: "").toString().toUpperCase()
    if (ctype in ["ZWV", "ZGB"] || readable.contains("z-wave") || readable.contains("zwave") || readable.contains("zigbee") || readable.contains("matter")) {
        warnings << "This is a radio-type driver. Creating it here makes a non-functional orphan shell (no paired node). Add real Z-Wave/Zigbee/Matter devices by PAIRING (hub_call_zwave / hub_call_zigbee / hub_call_matter), then delete this shell with hub_delete_device."
    }

    def selectWarning = _selectCreatedDevice(newId)
    if (selectWarning) warnings << selectWarning

    mcpLog("info", "device", "hub_create_device: created device ${newId} from driver-type ${typeId}${appliedLabel ? " labeled '${appliedLabel}'" : ''}")
    return [
        success: true,
        deviceId: newId,
        label: appliedLabel ?: info?.label,
        name: info?.displayName ?: info?.name,
        deviceTypeId: typeId,
        deviceTypeName: info?.deviceTypeName,
        virtual: info?.virtual,
        capabilities: info?.capabilities,
        warnings: warnings ?: null,
        message: "Created device ${newId} from driver-type ${typeId}${appliedLabel ? " labeled '${appliedLabel}'" : ''}." + (warnings ? " WARNING: see warnings." : ""),
        note: "Set its room, preferences and other fields with hub_update_device, or delete it with hub_delete_device."
    ]
}

// A device this app creates joins its device selection, so the device tools reach it at once.
private String _selectCreatedDevice(String deviceId) {
    try {
        def ids = (settings.selectedDevices ?: []).collect { it.id.toString() }
        if (!ids.contains(deviceId)) app.updateSetting("selectedDevices", [type: "capability.*", value: ids + [deviceId]])
        return null
    } catch (Exception e) {
        mcpLog("warn", "device", "Could not add device ${deviceId} to the selected devices: ${e.message}")
        return "Device ${deviceId} was created but could not be added to this app's selected devices (${e.message}); add it in the app's device picker.".toString()
    }
}

// Hub Mesh: create a LOCAL linked device from a device a peer hub shares (GET
// /device/createLinked/<hubId>/<deviceId>). A 200 is NOT proof of success -- the endpoint returns an
// empty body even when the link silently no-ops (seen live while the mesh was still re-establishing a
// recently-rebooted peer). So the read-back keys on the SOURCE pair and classifies three outcomes:
// linked (success), a confirmed no-op (FAILURE), and an unreadable mesh list (the only warn-not-fail).
// The id/name are passed literally: the platform HTTP layer encodes the path, so pre-encoding here
// would double-encode.
private Map _createLinkedMeshDevice(String meshHubId, String meshDeviceId) {
    Map beforeJson = _meshJson()
    def beforeList = (beforeJson?.localLinkedDevices instanceof List) ? beforeJson.localLinkedDevices : null
    def before = (beforeList != null) ? (beforeList.collect { it.id?.toString() } as Set) : null
    def availBefore = (beforeJson?.availableLinkedDevices instanceof List) ? beforeJson.availableLinkedDevices : null

    // Best-effort resolve of the local proxy row for this source, for DISPLAY only (the id in an
    // already-linked result). localLinkedDevices rows carry sourceHubId but NOT a source-device-id, so
    // a specific source device cannot be matched reliably when a peer shares several: match a
    // source-device-id field when one is present, else accept a lone same-hub row only when no such
    // field exists to contradict it, else null. Never used to DECIDE already-linked (see below).
    def resolveLocal = { List rows ->
        if (!(rows instanceof List)) return null
        def bySrc = rows.findAll { it instanceof Map && it.sourceHubId?.toString() == meshHubId }
        def devKeys = ['sourceDeviceId', 'sourceId', 'deviceId']
        def byDev = bySrc.findAll { r -> devKeys.any { k -> r.containsKey(k) && r[k]?.toString() == meshDeviceId } }
        if (byDev.size() == 1) return byDev[0]
        if (bySrc.size() == 1 && !devKeys.any { k -> bySrc[0].containsKey(k) }) return bySrc[0]
        return null
    }

    def srcBefore = (availBefore != null)
        ? availBefore.find { it.hubId?.toString() == meshHubId && it.deviceId?.toString() == meshDeviceId }
        : null
    // Already linked is decided ONLY by the offered row's authoritative linkedLocally flag -- NOT by a
    // local-row match: local device rows lack a source-device-id, so a same-hub proxy cannot prove that
    // THIS source device is the linked one (that would mislink a second device from the peer, and mask a
    // mistyped id). A linked device leaves availableLinkedDevices, so a source that is simply absent is
    // rejected as not-offered (below) rather than assumed already-linked.
    if (srcBefore != null && srcBefore.linkedLocally == true) {
        def existing = resolveLocal(beforeList)
        mcpLog("info", "device", "hub_create_device: Hub Mesh device (hub ${meshHubId}, device ${meshDeviceId}) is already linked${existing ? " as local ${existing.id}" : ''}")
        def already = [
            success: true,
            alreadyLinked: true,
            deviceId: existing?.id?.toString(),
            name: existing?.name?.toString(),
            sourceHubId: meshHubId,
            sourceDeviceId: meshDeviceId,
            linkedDevice: true,
            message: "Hub Mesh device shared by hub ${meshHubId} was already linked here${existing ? " as local device ${existing.id}" : ''}.",
            note: "This is a local proxy for a device on the peer hub; unlink it (local only) with hub_delete_device. Read all linked devices via hub_get_hub_mesh."
        ]
        if (existing == null) already.warnings = ["Already linked, but could not resolve the existing local id from hub_get_hub_mesh localLinkedDevices. Find it with hub_get_hub_mesh."]
        return already
    }
    if (availBefore != null && srcBefore == null) {
        throw new IllegalArgumentException(
            "No shared device with hubId ${meshHubId} + deviceId ${meshDeviceId} is offered in " +
            "hub_get_hub_mesh availableLinkedDevices[]. Copy the hubId + deviceId from an " +
            "availableLinkedDevices[] row and retry; a device that is already linked no longer appears " +
            "there -- find it in hub_get_hub_mesh localLinkedDevices instead.")
    }

    // A 30s read timeout can fire AFTER the hub applied the link, so a throw is an UNKNOWN outcome, not
    // a definite failure: capture it and let the read-back decide (like the no-op path).
    String linkThrew = null
    try {
        hubInternalGet("/device/createLinked/${meshHubId}/${meshDeviceId}", null, 30)
    } catch (Exception e) {
        mcpLogError("device", "hub_create_device Hub Mesh link GET errored (hub ${meshHubId}, device ${meshDeviceId})", e)
        linkThrew = e.message ?: e.toString()
    }

    // Read-back with a short backoff for propagation lag. `linked` is a tri-state -- true (proven
    // linked), false (proven still unlinked = silent no-op), null (mesh list unreadable / inconclusive).
    Boolean linked = null
    String newId = null
    String newName = null
    boolean newIdFromSource = false
    for (int attempt = 0; attempt < 3; attempt++) {
        Map j = _meshJson()
        def after = (j?.localLinkedDevices instanceof List) ? j.localLinkedDevices : null
        def avail = (j?.availableLinkedDevices instanceof List) ? j.availableLinkedDevices : null
        if (newId == null && before != null && after != null) {
            def added = after.findAll { !before.contains(it.id?.toString()) }
            def fromSource = added.findAll { it.sourceHubId?.toString() == meshHubId }
            def pick = (fromSource.size() == 1) ? fromSource[0] : (added.size() == 1 ? added[0] : null)
            if (pick != null) { newId = pick.id?.toString(); newName = pick.name?.toString(); newIdFromSource = fromSource.size() == 1 }
        }
        if (avail != null) {
            // The source's own row is authoritative: still present-and-unlinked means the link did
            // NOT take. Its ABSENCE means "took" only when the pair was offered before this call
            // (srcBefore != null) -- otherwise the absence is inconclusive (null), not a false success.
            def srcRow = avail.find { it.hubId?.toString() == meshHubId && it.deviceId?.toString() == meshDeviceId }
            if (srcRow == null) {
                linked = (srcBefore != null) ? true : null
            } else {
                linked = (srcRow.linkedLocally == true)
            }
        } else if (after != null && newId != null) {
            // available list unreadable, but a new local link for THIS source showed up.
            linked = true
        }
        if (linked == true && (newId != null || before == null)) break
        if (attempt < 2) pauseExecution(500)
    }

    // Outcome 2: confirmed silent no-op -- the hub accepted the GET but no link appeared.
    if (linked == false) {
        mcpLogError("device", "hub_create_device Hub Mesh link was a silent no-op (hub ${meshHubId}, device ${meshDeviceId}): source still in availableLinkedDevices with linkedLocally=false", null)
        return [success: false, isError: true,
                error: "Linking the shared device (hub ${meshHubId}, device ${meshDeviceId}) did not take: the hub accepted the request but the device is still unlinked (availableLinkedDevices shows linkedLocally=false).",
                note: "This is usually transient: Hub Mesh takes time to re-establish a peer connection after that peer " +
                      "reboots or updates, and a link briefly no-ops during that window before clearing itself. Wait a bit " +
                      "and retry. Check the peer in hub_get_hub_mesh peers[] (offline=false, warning=null). If it persists, " +
                      "confirm this hub holds the peer's mesh token: get it from the peer's OWN hub_get_hub_mesh(includeToken=true) " +
                      "and store it with hub_update_hub_mesh(peerHubId, peerToken)."]
    }

    // Outcome 3a: the GET errored AND the read-back could not prove it linked -- unknown, not a definite
    // failure (the timeout may have fired after the hub applied it). Fail, but say it MAY have applied.
    if (linked == null && linkThrew != null) {
        return [success: false, isError: true,
                error: "Hub call errored linking the shared device (hub ${meshHubId}, device ${meshDeviceId}): ${linkThrew}",
                note: "The link MAY have applied (a read timeout can fire after the hub commits) -- verify with " +
                      "hub_get_hub_mesh before retrying. If it did not take, this is usually transient: Hub Mesh takes " +
                      "time to re-establish a peer connection after that peer reboots or updates. If it persists, confirm " +
                      "the peer in hub_get_hub_mesh peers[] (offline=false, warning=null) and that this hub holds its mesh " +
                      "token (store it with hub_update_hub_mesh(peerHubId, peerToken), read from the peer's OWN " +
                      "hub_get_hub_mesh(includeToken=true))."]
    }

    // Outcome 3b: could not prove linked OR not-linked (mesh list unreadable / ambiguous) -- warn, don't fail.
    def warnings = []
    if (linked == null) {
        warnings << "Sent the link request, but could not confirm it against hub_get_hub_mesh availableLinkedDevices (read-back lag or an unreadable mesh list). Verify with hub_get_hub_mesh."
    } else if (newId == null) {
        warnings << "Linked the shared device, but could not resolve its new local id from hub_get_hub_mesh localLinkedDevices (read-back lag or an ambiguous diff). Find it with hub_get_hub_mesh."
    }

    // Only a local link the read-back ties to the requested source joins the device selection.
    if (newId != null && newIdFromSource) {
        def selectWarning = _selectCreatedDevice(newId)
        if (selectWarning) warnings << selectWarning
    } else if (newId != null) {
        warnings << "Linked as local device ${newId}, but the read-back could not tie it to hub ${meshHubId}, so it was not added to this app's selected devices; add it in the app's device picker once confirmed with hub_get_hub_mesh.".toString()
    }
    mcpLog("info", "device", "hub_create_device: linked Hub Mesh device (hub ${meshHubId}, device ${meshDeviceId})${newId ? " as local ${newId}" : ''}")
    return [
        success: true,
        deviceId: newId,
        name: newName,
        sourceHubId: meshHubId,
        sourceDeviceId: meshDeviceId,
        linkedDevice: true,
        warnings: warnings ?: null,
        message: "Linked the Hub Mesh device shared by hub ${meshHubId}${newId ? " as local device ${newId}" : ''}." + (warnings ? " WARNING: see warnings." : ""),
        note: "This is a local proxy for a device on the peer hub; unlink it (local only) with hub_delete_device. Read all linked devices via hub_get_hub_mesh."
    ]
}

def toolGetCompatibleDevices(args) {
    // Hubitat's static "Compatible Devices" catalog (GET /hub/compatibleDevices): brands +
    // models with pairing/exclude/factory-reset instructions and the Hubitat driver each
    // maps to. ~1MB full, so this filters + paginates + projects -- never the whole blast.
    def cursor = args?.cursor
    def includeInstructions = (args?.includeInstructions == true)
    def q = args?.query?.toString()?.toLowerCase()
    def brandF = args?.brand?.toString()?.toLowerCase()
    def protoF = args?.protocol?.toString()?.toLowerCase()
    def typeF = args?.deviceType?.toString()?.toLowerCase()

    def list
    try {
        def txt = hubInternalGet("/hub/compatibleDevices", null, 30)
        list = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
    } catch (Exception e) {
        return [success: false, error: "Could not read /hub/compatibleDevices: ${e.message}",
                note: "This is Hubitat's static compatibility catalog; it needs hub connectivity."]
    }
    if (!(list instanceof List)) {
        return [success: false, error: "Unexpected response shape from /hub/compatibleDevices.", devices: []]
    }

    def stripHtml = { s ->
        if (s == null) return null
        // Decode &amp; LAST (after &lt;/&gt;/&quot;/&#39;) so a single-encoded entity doesn't
        // double-decode (e.g. "&amp;lt;" must not collapse to "<").
        s.toString().replaceAll(/(?s)<[^>]+>/, " ")
            .replaceAll(/&nbsp;/, " ")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&#39;", "'")
            .replace("&amp;", "&")
            .replaceAll(/\s+/, " ").trim()
    }
    def matched = list.findAll { r ->
        if (brandF && !((r?.brand ?: "").toString().toLowerCase().contains(brandF))) return false
        if (protoF && !((r?.protocol ?: "").toString().toLowerCase().contains(protoF))) return false
        if (typeF && !((r?.deviceType ?: "").toString().toLowerCase().contains(typeF))) return false
        if (q) {
            // ?: '' on each field so a null doesn't interpolate the literal "null" into the haystack.
            def hay = "${r?.brand ?: ''} ${r?.name ?: ''} ${r?.productNumber ?: ''} ${r?.deviceType ?: ''} ${r?.driverName ?: ''}".toLowerCase()
            if (!hay.contains(q)) return false
        }
        return true
    }
    def project = { r ->
        def m = [brand: r?.brand, name: r?.name, deviceType: r?.deviceType, productNumber: r?.productNumber,
                 protocol: r?.protocol, driverName: r?.driverName, deviceTypeId: r?.deviceTypeId, id: r?.id]
        if (includeInstructions) {
            m.joinInstructions = stripHtml(r?.joinInstructions)
            m.excludeInstructions = stripHtml(r?.excludeInstructions)
            m.factoryResetInstructions = stripHtml(r?.factoryResetInstructions)
            m.additionalHardware = r?.additionalHardware
            m.notes = r?.notes
        } else {
            m.hasInstructions = (r?.joinInstructions || r?.excludeInstructions || r?.factoryResetInstructions) ? true : false
        }
        return m
    }

    def pageSize = includeInstructions ? 12 : 40
    def effCursor = (cursor == null) ? "" : cursor
    def paged = _paginateList(matched, effCursor, pageSize, "hub_get_compatible_devices")
    def result = [success: true, total: matched.size(), count: paged.page.size(),
                  devices: paged.page.collect(project)]
    if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    if (matched.isEmpty()) {
        result.note = "No compatible-device records matched. Loosen the brand/protocol/deviceType/query filter."
    } else if (paged.nextCursor != null) {
        result.note = "Page of ${result.count} of ${result.total} matches. Iterate nextCursor, or narrow the filter. Set includeInstructions=true for pairing/reset steps."
    }
    return result
}

def toolDeleteDevice(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.deviceId) throw new IllegalArgumentException("deviceId is required")

    def deviceId = args.deviceId.toString()
    _validateNativeDeviceId(deviceId)

    // Step 1: Gather device information for audit trail via hub internal API
    // We intentionally do NOT restrict to findDevice() (selectedDevices only) because
    // ghost/orphaned devices may not be in the selected device list
    def deviceInfo = null
    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (responseText) {
            def fullJson = new groovy.json.JsonSlurper().parseText(responseText)
            if (fullJson instanceof Map && fullJson.device instanceof Map &&
                fullJson.device.id?.toString() == deviceId) {
                deviceInfo = fullJson.device
            }
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Could not fetch device info for ${deviceId}: ${e.message}")
    }

    if (!deviceInfo) {
        throw new IllegalArgumentException("Device ${deviceId} not found on hub or its native identity could not be verified. Verify the device ID and retry; nothing was deleted.")
    }

    def deviceName = deviceInfo.label ?: deviceInfo.name ?: "Unknown"
    def deviceDNI = deviceInfo.deviceNetworkId ?: "unknown"
    def deviceType = deviceInfo.deviceTypeName ?: deviceInfo.typeName ?: deviceInfo.type ?: "unknown"
    def warnings = []

    // Step 2: Check for recent activity (active device warning)
    try {
        def lastActivity = safeLastActivity(deviceInfo)
        if (deviceInfo.lastActivityTime != null && lastActivity == null) warnings << "ACTIVITY UNAVAILABLE: Native lastActivityTime could not be parsed; do not assume this device is inactive."
        if (lastActivity) {
            def hoursAgo = (Math.round((now() - lastActivity.time) / 3600000.0 * 10) / 10.0) as double
            if (hoursAgo < 24) {
                warnings << "ACTIVE DEVICE: Last activity was ${hoursAgo} hours ago at ${lastActivity.format("yyyy-MM-dd'T'HH:mm:ss")}. This device may still be functional."
            }
        }
    } catch (Exception e) {
        warnings << "ACTIVITY UNAVAILABLE: Could not assess native last activity; do not assume this device is inactive."
        mcpLog("error", "hub-admin", "Device ${deviceId} activity audit failed: ${e.class.simpleName}")
    }
    def recentEvents = _fetchBypassDeviceEvents(deviceId)
    if (recentEvents == null) {
        warnings << "EVENT HISTORY UNAVAILABLE: Recent events could not be read; do not assume this device is quiet."
    } else if (recentEvents) {
        def lastEvent = recentEvents[0]
        warnings << "HAS RECENT EVENTS: Last event was ${lastEvent.name}=${_devicePublicAttributeValue(lastEvent.name, lastEvent.value)} at ${lastEvent.date}"
    }


    // Step 3: Check Z-Wave/Zigbee radio membership
    def isRadioDevice = false
    try {
        // Check if device has a zigbeeId (Zigbee device)
        if (deviceInfo.zigbeeId) {
            isRadioDevice = true
            warnings << "ZIGBEE DEVICE: This device has Zigbee ID '${deviceInfo.zigbeeId}'. Force-deleting without proper Zigbee removal may leave an orphaned node on the mesh."
        }
        // Check if device network ID looks like a Z-Wave node (2-digit hex)
        if (deviceDNI && deviceDNI.matches(/^[0-9A-Fa-f]{2}$/)) {
            isRadioDevice = true
            warnings << "Z-WAVE DEVICE: Network ID '${deviceDNI}' suggests this is a Z-Wave node. Force-deleting without proper Z-Wave exclusion will leave a ghost node that degrades mesh performance."
        }
    } catch (Exception e) {
        // Skip radio check on error
    }

    // Step 4: Check if device is active on the Z-Wave/Zigbee radio node tables
    if (isRadioDevice) {
        try {
            // Check Z-Wave node table
            def zwaveEndpoints = ["/hub/zwaveDetails/json", "/hub2/zwaveInfo"]
            for (endpoint in zwaveEndpoints) {
                try {
                    def zwResponse = hubInternalGet(endpoint)
                    if (zwResponse) {
                        def zwData = new groovy.json.JsonSlurper().parseText(zwResponse)
                        def nodes = zwData?.nodes
                        if (nodes) {
                            def activeNode = nodes.find { it.deviceId?.toString() == deviceId }
                            if (activeNode) {
                                warnings << "ACTIVE ON Z-WAVE RADIO: Device is node ${activeNode.nodeId} with state '${activeNode.nodeState}'. It should be Z-Wave excluded BEFORE deletion."
                            }
                        }
                    }
                    break
                } catch (Exception e) { /* try next endpoint */ }
            }
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Could not check Z-Wave radio for device ${deviceId}: ${e.message}")
        }
        try {
            // Check Zigbee device table
            def zigEndpoints = ["/hub/zigbeeDetails/json", "/hub2/zigbeeInfo"]
            for (endpoint in zigEndpoints) {
                try {
                    def zigResponse = hubInternalGet(endpoint)
                    if (zigResponse) {
                        def zigData = new groovy.json.JsonSlurper().parseText(zigResponse)
                        def devices = zigData?.devices
                        if (devices) {
                            def activeDevice = devices.find { it.id?.toString() == deviceId }
                            if (activeDevice && activeDevice.active) {
                                warnings << "ACTIVE ON ZIGBEE RADIO: Device '${activeDevice.name}' is active on the Zigbee mesh. It should be removed via Zigbee BEFORE deletion."
                            }
                        }
                    }
                    break
                } catch (Exception e) { /* try next endpoint */ }
            }
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Could not check Zigbee radio for device ${deviceId}: ${e.message}")
        }
    }

    // Step 4b: Hub Mesh linked device. Deleting a linked device removes ONLY the local proxy;
    // the source device on the peer hub is untouched. Mirror the Vue page's appsUsing warning so
    // the caller knows which local apps reference the link and will break.
    if (_deviceFlag(deviceInfo.linkedDevice)) {
        warnings << "LINKED MESH DEVICE: This is a Hub Mesh linked device (a local proxy for a device shared by a peer hub). Deleting it removes ONLY the local link; the source device on the peer hub is untouched."
        try {
            // A mesh read that fails (null) OR a missing row is NOT "no apps use it": the appsUsing check
            // simply did not run, so warn distinctly rather than let an unchecked delete look safe.
            Map meshJson = _meshJson()
            def meshRows = (meshJson?.localLinkedDevices instanceof List) ? meshJson.localLinkedDevices : null
            def linkedRow = (meshRows != null) ? meshRows.find { it.id?.toString() == deviceId } : null
            if (meshRows == null || linkedRow == null) {
                warnings << "COULD NOT CHECK which local apps use this linked device (mesh read ${meshRows == null ? 'failed' : 'did not list this device'}); deletion may break apps that reference it. Review hub_get_hub_mesh localLinkedDevices before proceeding."
            } else {
                def apps = (linkedRow.appsUsing instanceof List) ? linkedRow.appsUsing : []
                if (apps) {
                    warnings << "IN USE BY ${apps.size()} APP(S): ${apps.join(', ')}. These apps reference the linked device and WILL BREAK after deletion."
                }
            }
        } catch (Exception e) {
            mcpLog("debug", "hub-admin", "Could not read Hub Mesh appsUsing for linked device ${deviceId}: ${e.message}")
            warnings << "COULD NOT CHECK which local apps use this linked device (mesh read failed); deletion may break apps that reference it. Review hub_get_hub_mesh localLinkedDevices before proceeding."
        }
    }

    // Step 5: Check if any MCP rules reference this device
    try {
        def childApps = getChildApps()
        def referencingRules = []
        // Recursive search for device ID references without serializing to JSON
        def containsDeviceRef
        containsDeviceRef = { obj ->
            if (obj == null) return false
            if (obj instanceof String) return obj == deviceId
            if (obj instanceof Number) return obj.toString() == deviceId
            if (obj instanceof Map) return obj.values().any { containsDeviceRef(it) }
            if (obj instanceof Collection) return obj.any { containsDeviceRef(it) }
            return obj.toString() == deviceId
        }
        childApps?.each { childApp ->
            try {
                def ruleData = childApp.getRuleData()
                if (ruleData && containsDeviceRef(ruleData)) {
                    referencingRules << [id: ruleData.id, name: ruleData.name ?: "Unnamed"]
                }
            } catch (Exception e) { /* skip rule */ }
        }
        if (referencingRules) {
            warnings << "REFERENCED BY ${referencingRules.size()} MCP RULE(S): ${referencingRules.collect { "${it.name} (ID: ${it.id})" }.join(', ')}. These rules WILL BREAK after deletion."
        }
    } catch (Exception e) {
        mcpLog("debug", "hub-admin", "Could not check MCP rules for device ${deviceId}: ${e.message}")
    }

    // Step 6: Full audit log BEFORE deletion
    mcpLog("warn", "hub-admin", "DELETE DEVICE AUDIT: Deleting '${deviceName}' (ID: ${deviceId}, DNI: ${deviceDNI}, Type: ${deviceType}). Warnings: ${warnings.size() > 0 ? warnings.join(' | ') : 'none'}")

    // Step 7: Execute force delete via hub internal API. The /yes variant unlinks a Hub Mesh LINKED
    // device too (live-verified: deleting a linked proxy via /yes removes only the local link and
    // returns the source to availableLinkedDevices; the peer's source device is untouched), so the
    // linked case needs no separate /json branch.
    try {
        def responseText = hubInternalGet("/device/forceDelete/${deviceId}/yes", null, 30)
        mcpLog("debug", "hub-admin", "Force delete response for device ${deviceId}: ${responseText?.take(500)}")
    } catch (Exception e) {
        mcpLogError("hub-admin", "Device force delete FAILED for '${deviceName}' (${deviceId})", e)
        return [
            success: false,
            error: "Force delete failed: ${e.message}",
            deviceId: deviceId,
            deviceName: deviceName,
            warnings: warnings
        ]
    }

    // Step 8: Verify deletion by attempting to re-fetch the device
    def verified = false
    try {
        def checkResponse = hubInternalGet("/device/fullJson/${deviceId}")
        if (checkResponse) {
            def checkParsed = new groovy.json.JsonSlurper().parseText(checkResponse)
            verified = checkParsed instanceof Map && checkParsed.containsKey('device') &&
                (checkParsed.device == null || (checkParsed.device instanceof Map && checkParsed.device.isEmpty()))
        }
    } catch (Exception e) {
        // A timeout or error page cannot prove the device was removed.
        verified = _httpStatusOf(e) == 404
    }
    if (!verified) {
        warnings << "DELETE UNVERIFIED: The native device lookup did not confirm absence. Check the device in Hubitat before retrying deletion."
    }

    mcpLog(verified ? "info" : "warn", "hub-admin", "Device delete ${verified ? 'VERIFIED' : 'UNVERIFIED'}: '${deviceName}' (ID: ${deviceId})")

    return [
        success: verified,
        deviceId: deviceId,
        deviceName: deviceName,
        message: verified
            ? "Device '${deviceName}' (ID: ${deviceId}) has been permanently deleted."
            : "Delete command was sent but device may still exist. Check Hubitat web UI to verify.",
        warnings: warnings,
        auditInfo: [
            deletedAt: formatTimestamp(now()),
            deviceType: deviceType,
            deviceNetworkId: deviceDNI,
            driverName: deviceType,
            lastHubBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    ]
}

def toolCallDeviceSwap(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def fromId = args?.fromDeviceId?.toString()?.trim()
    def toId = args?.toDeviceId?.toString()?.trim()
    if (!fromId) throw new IllegalArgumentException("fromDeviceId is required")
    if (!toId) throw new IllegalArgumentException("toDeviceId is required")
    if (fromId == toId) throw new IllegalArgumentException("fromDeviceId and toDeviceId must be different devices")
    boolean fromListed = _requireDeviceToolAccess(fromId)
    boolean toListed = _requireDeviceToolAccess(toId)
    // Resolve both permissions before any native request, and verify bypass-only
    // identities before the direct alias creates a transient Swap Device instance.
    for (def entry in [[id: fromId, listed: fromListed], [id: toId, listed: toListed]]) {
        if (!entry.listed) {
            def metadata = _fetchDeviceFullJson(entry.id)
            if (!(metadata?.device instanceof Map) || metadata.device.id?.toString() != entry.id) {
                return [success: false, error: "Device metadata could not be verified for ${entry.id}.",
                        note: "Verify the device exists in the native Devices page and retry. Nothing was swapped."]
            }
        }
    }

    // The hub's Swap Device exchanges the two device records' identities (label, name, DNI,
    // state, create time) and leaves every app reference on its id, so verification compares
    // each device's identity before and after the click. The before-count is the reported
    // blast radius: the apps that referenced fromDeviceId now drive the replacement.
    def fromBefore = _deviceSwapSnapshot(fromId)
    def toBefore = _deviceSwapSnapshot(toId)
    // Without both identities the outcome cannot be checked, and an unchecked swap cannot be safely retried.
    def unread = [[fromId, fromBefore], [toId, toBefore]]
        .findAll { it[1] == null || (it[1].dni == null && it[1].createTime == null) }.collect { it[0] }
    if (unread) {
        return [success: false,
                error: "Device(s) ${unread.join(', ')} could not be read before the swap, so its outcome could not be verified. Nothing was swapped.".toString(),
                note: "Check the device(s) with hub_get_device, then retry."]
    }
    def beforeCount = fromBefore?.dependents
    mcpLog("info", "device-swap", "Swap requested: ${fromId} -> ${toId}; ${beforeCount == null ? 'unknown' : beforeCount} dependent app(s) before swap")

    def appId = _resolveDirectAppId("swapDevice")
    if (appId == null) {
        return [success: false,
                error: "Could not open the built-in Swap Device tool (direct/swapDevice did not resolve to an app instance).",
                note: "Likely causes: the firmware does not expose the direct/swapDevice alias, the redirect chain shape changed, or the hub auto-followed an absolute Location (200 with no Location header). A hub-admin warn log entry summarizes which; hub_set_log_level(level=info) captures per-hop detail on retry. Verify Settings > Swap Apps Device exists in the hub UI, then retry. Nothing was swapped."]
    }
    mcpLog("info", "device-swap", "Swap Device transient instance ${appId} opened")

    try {
        // Eligibility pre-check: the Swap Device page only offers oldDev candidates
        // that are referenced by at least one app AND not owned as another app's
        // child/component device (verified live on fw 2.5.0.143). Every MCP-created
        // virtual device is a child device of this app, so it never appears.
        def oldDevOptions = _deviceSwapEnumOptions(_deviceSwapFindInput(_rmFetchConfigJson(appId, "mainPage"), "oldDev"))
        if (!oldDevOptions.any { it.id == fromId }) {
            mcpLog("warn", "device-swap", "fromDeviceId ${fromId} not among ${oldDevOptions.size()} swappable oldDev option(s) -- closing instance ${appId}")
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: "The hub's Swap Device tool does not offer device ${fromId} as swappable.",
                    oldDevOptionCount: oldDevOptions.size(),
                    note: "Swap Device only offers devices that are referenced by at least one app AND not owned as another app's child/component device. MCP-created virtual devices (hub_manage_virtual_device) are child devices and are always ineligible. Pick a free-standing fromDeviceId, or use hub_list_device_dependents(deviceId=${fromId}) to confirm it is referenced by an app. Nothing was swapped; the transient instance was closed."]
        }

        def applied = []
        def skipped = []
        _rmWriteSettingOnPage(appId, "mainPage", "oldDev", fromId, applied, null, skipped)
        def oldDevSkip = skipped.find { it.key == "oldDev" }
        if (oldDevSkip) {
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: "Swap Device page did not accept the oldDev selection (${oldDevSkip.reason}).",
                    note: "Hub firmware may have renamed the device pickers on the Swap Device page. Nothing was swapped; the transient instance was closed."]
        }
        mcpLog("info", "device-swap", "oldDev=${fromId} written on instance ${appId}")

        // The hub fills newDev's options with compatible replacements only
        // after oldDev lands — this re-fetch IS the compatibility check.
        def cfg = _rmFetchConfigJson(appId, "mainPage")
        def options = _deviceSwapEnumOptions(_deviceSwapFindInput(cfg, "newDev"))
        if (!options.any { it.id == toId }) {
            mcpLog("warn", "device-swap", "toDeviceId ${toId} not among ${options.size()} compatible replacement(s) for ${fromId} -- closing instance ${appId}")
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: options.isEmpty()
                        ? "The hub offered NO compatible replacement devices for ${fromId}."
                        : "Device ${toId} is not a compatible replacement for ${fromId} -- the hub did not offer it.",
                    compatibleOptions: options.take(30),
                    compatibleOptionCount: options.size(),
                    note: "The Swap Device tool only offers devices with compatible capabilities, and excludes devices owned as another app's child/component device -- toDeviceId may be an app child device (every MCP-created virtual device is one). Pick a toDeviceId from compatibleOptions${options.size() > 30 ? " (showing first 30 of ${options.size()})" : ""}, or choose free-standing replacement hardware of the same device class. Nothing was swapped."]
        }

        _rmWriteSettingOnPage(appId, "mainPage", "newDev", toId, applied, null, skipped)
        def newDevSkip = skipped.find { it.key == "newDev" }
        if (newDevSkip) {
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: "Swap Device page did not accept the newDev selection (${newDevSkip.reason}).",
                    note: "The replacement was offered but the write did not land -- likely a transient hub blip. Nothing was swapped; the transient instance was closed. Retry the call."]
        }
        mcpLog("info", "device-swap", "newDev=${toId} written on instance ${appId}")

        // The swap-action button only renders once both pickers are set, and
        // its name is firmware-defined — discover it instead of hardcoding.
        def buttons = _deviceSwapActionButtons(_rmFetchConfigJson(appId, "mainPage"))
        if (buttons.size() != 1) {
            mcpLog("warn", "device-swap", "Expected exactly one swap-action button, found ${buttons.size()} ${buttons} -- closing instance ${appId}")
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: buttons.isEmpty()
                        ? "No swap-action button appeared after selecting both devices -- the hub did not unlock the swap."
                        : "Ambiguous Swap Device page: ${buttons.size()} action buttons found (${buttons.join(', ')}); refusing to click blindly.",
                    buttonsFound: buttons,
                    note: "Hub firmware may have changed the Swap Device page layout. Nothing was swapped; the transient instance was closed. Verify the swap manually via Settings > Swap Apps Device and report the button names with hub_report_issue if this persists."]
        }
        mcpLog("info", "device-swap", "Clicking swap action '${buttons[0]}' on instance ${appId}")
        _rmClickAppButton(appId, buttons[0], null, "mainPage")

        // This render PERFORMS the swap: the click only arms it, and every render of the instance
        // swaps the two devices again (live, fw 2.5.2.129: render 1 swapped, render 2 swapped back).
        // Render exactly once, then delete the instance before anything can render it again.
        def instanceGone = false
        try {
            _rmFetchConfigJson(appId, "mainPage")
        } catch (Exception e) {
            instanceGone = true
            mcpLog("warn", "device-swap", "post-click render threw ${e.class.simpleName}: ${e.message} -- the identity read-back below decides whether the swap ran")
        }
        boolean instanceDeleted = _deviceSwapCleanup(appId)

        def fromAfter = _deviceSwapSnapshot(fromId)
        def toAfter = _deviceSwapSnapshot(toId)
        def outcome = _deviceSwapIdentityOutcome(fromBefore, toBefore, fromAfter, toAfter)
        def leftover = { Map r ->
            if (!instanceDeleted) {
                r.leftoverSwapInstance = appId
                r.warning = "The transient Swap Device instance ${appId} could not be deleted. Do NOT open it in the hub UI: every render of its page swaps the two devices again. Delete it with hub_delete_native_app(appId=${appId}, force=true).".toString()
            }
            return r
        }
        if (outcome == "unchanged") {
            return leftover([success: false, verified: true,
                    error: "The Swap Device action was clicked but neither device changed identity -- nothing was swapped.",
                    note: "Both devices kept their own label and network id. Check that both still exist and are compatible (hub_get_device), then retry."])
        }
        if (outcome != "exchanged") {
            return leftover([success: false, verified: false,
                    error: "The Swap Device action was clicked but the swap could not be confirmed (${outcome == 'unreadable' ? 'a device could not be read back' : 'the two devices did not cleanly trade identities'}).",
                    note: "Inspect devices ${fromId} and ${toId} with hub_get_device before doing anything else. Do NOT simply retry: if the swap did take, running it again swaps the devices back."])
        }
        mcpLog("info", "device-swap", "Swap ${fromId} -> ${toId} complete; identities exchanged; post-click render ${instanceGone ? 'threw' : 'ran'}; instance ${instanceDeleted ? 'deleted' : 'NOT deleted'}")
        def toDeps = toBefore?.dependents
        String toNote = (toDeps == 0) ?
            "Device ${toId} now carries the old device's identity ('${toAfter.label}'); nothing used it before the swap, so it can be removed once you confirm the automations behave." :
            (toBefore?.onlySelf ?
                "Device ${toId} now carries the old device's identity ('${toAfter.label}'); only this MCP server's device selection used it, nothing else, so it can be removed once you confirm the automations behave." :
            (toDeps == null ?
                "Device ${toId} now carries the old device's identity ('${toAfter.label}'); apps that used it now run on the OLD hardware -- check hub_list_device_dependents(deviceId=${toId}) before removing it." :
                "Device ${toId} now carries the old device's identity ('${toAfter.label}'), and the ${toDeps} app(s) that used it now run on the OLD hardware -- re-point them (hub_list_device_dependents(deviceId=${toId})) before removing it."))
        def result = [success: true,
                      swapped: [from: fromId, to: toId],
                      identityExchanged: true,
                      verified: true,
                      fromDevice: [id: fromId, label: fromAfter.label, deviceNetworkId: fromAfter.dni],
                      toDevice: [id: toId, label: toAfter.label, deviceNetworkId: toAfter.dni],
                      fromDeviceDependents: beforeCount,
                      toDeviceDependents: toDeps,
                      note: "The hub exchanged the two devices' identities (name, label, network id, driver, room, current state; event history stays with the id). Every app that referenced device ${fromId} still references id ${fromId}, which now carries the replacement's identity ('${fromAfter.label}') and hardware. ${toNote} Running the swap again would swap them back.".toString()]
        return leftover(result)
    } catch (Exception e) {
        mcpLogError("device-swap", "hub_call_device_swap ${fromId} -> ${toId} failed", e)
        boolean closed = _deviceSwapCleanup(appId)
        def failed = [success: false,
                error: "Device swap failed: ${e.message}",
                note: "The swap may or may not have committed: app references stay on their ids either way, so compare devices ${fromId} and ${toId} with hub_get_device (label and network id) -- if they traded, the swap ran. Do NOT retry before checking; a second swap swaps them back.".toString()]
        if (!closed) {
            failed.leftoverSwapInstance = appId
            failed.warning = "The transient Swap Device instance ${appId} could not be deleted. Do NOT open it in the hub UI: every render of its page swaps the two devices again. Delete it with hub_delete_native_app(appId=${appId}, force=true).".toString()
        }
        return failed
    }
}

def toolCallDeviceReplace(args) {
    def oldId = args?.oldDeviceId?.toString()?.trim()
    if (!oldId) throw new IllegalArgumentException("oldDeviceId is required")
    def newId = args?.newDeviceId?.toString()?.trim()
    if (args?.listOptions != true) {
        if (!newId) throw new IllegalArgumentException("newDeviceId is required to replace (or pass listOptions=true to list compatible candidates)")
        if (oldId == newId) throw new IllegalArgumentException("oldDeviceId and newDeviceId must be different devices")
    }
    boolean oldListed = _requireDeviceToolAccess(oldId)
    boolean newListed = newId ? _requireDeviceToolAccess(newId) : true
    // A bypass-only id passed the gate on format alone. Confirm it names a real device before
    // any native request -- the same pre-flight swap runs -- so an unverified id never reaches
    // /device/replace or the replacement-options read.
    for (def entry in [[id: oldId, listed: oldListed], [id: newId, listed: newListed]]) {
        if (entry.id && !entry.listed) {
            def metadata = _fetchDeviceFullJson(entry.id)
            if (!(metadata?.device instanceof Map) || metadata.device.id?.toString() != entry.id) {
                return [success: false, error: "Device metadata could not be verified for ${entry.id}.",
                        note: "Verify the device exists in the native Devices page and retry. Nothing was replaced."]
            }
        }
    }

    // Read-only mode: list the hub's compatible replacement candidates (no write, no confirm).
    if (args?.listOptions == true) {
        try {
            def raw = hubInternalGet("/device/getReplacementOptions/${java.net.URLEncoder.encode(oldId, 'UTF-8')}")
            def parsed = raw?.trim() ? new groovy.json.JsonSlurper().parseText(raw) : null
            // A genuine "no candidates" answer is an empty JSON array (a List). Anything else --
            // empty body, an HTML error page, a {error:...} object -- is a FAILED read, not "no
            // replacements"; reporting it as an empty success would mislead the caller into thinking
            // the device is unreplaceable. Distinguish the two rather than coalescing both to [].
            if (!(parsed instanceof List)) {
                mcpLog("warn", "device-replace", "getReplacementOptions for ${oldId} returned a non-list response: ${raw?.take(200)}")
                return [success: false, error: "Replacement-options read returned an unexpected (non-list) response.", response: raw?.take(300),
                        note: "Verify oldDeviceId (from hub_list_devices) and Hub Security credentials; the hub did not return a candidate list."]
            }
            def allowedIds = _bypassEnabled() ? null : (((selectedDevices ?: []) + (getChildDevices() ?: [])).collect { it.id?.toString() } as Set)
            def candidates = allowedIds == null ? parsed : parsed.findAll { it instanceof Map && allowedIds.contains(it.id?.toString()) }
            def options = candidates.collect { [id: it?.id?.toString(), name: it?.name, deviceTypes: it?.deviceTypes] }
            def out = [success: true, listOptions: true, oldDeviceId: oldId, options: options, optionCount: options.size(),
                    note: options ? "Pick an option's id as newDeviceId, then call again with confirm=true to replace." : "No compatible replacement devices found within current MCP device access."]
            if (newId) out.compatible = options.any { it.id == newId }
            return out
        } catch (Exception e) {
            mcpLogError("device-replace", "getReplacementOptions for ${oldId} failed", e)
            return [success: false, error: "Could not read replacement options: ${e.message}",
                    note: "Verify oldDeviceId (from hub_list_devices) and Hub Security credentials."]
        }
    }

    requireDestructiveConfirm(args.confirm)

    mcpLog("warn", "device-replace", "Replacing device ${oldId} with ${newId} via /device/replace (old id + references preserved)")
    try {
        def raw = hubInternalGet("/device/replace", [oldId: oldId, newId: newId])
        def parsed = null
        try { parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null } catch (Exception ignore) { }
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, replaced: [oldDeviceId: oldId, newDeviceId: newId], preservedDeviceId: oldId,
                    message: "Device ${oldId} now uses ${newId}'s hardware; its id and all app/rule references are preserved.",
                    note: "Verify with hub_get_device(deviceId=${oldId})."]
        }
        def errText = (parsed instanceof Map) ? (parsed.message ?: parsed.error) : null
        return [success: false, error: errText ?: "/device/replace did not report success", response: raw?.take(300),
                note: "Nothing was replaced. The hub answers \"Incompatible devices\" when the two share no device type, which listOptions already checks (with newDeviceId it reports compatible); \"Failed to replace device\" is its answer when its own swap of the two devices failed, with no reason given. Its Replace is built for newly paired hardware taking over an existing device."]
    } catch (Exception e) {
        mcpLogError("device-replace", "replace ${oldId} -> ${newId} failed", e)
        return [success: false, error: "Device replace failed: ${e.message}",
                note: "Verify the device ids and Hub Security credentials. The replace may not have committed -- check hub_get_device(deviceId=${oldId})."]
    }
}

// One /device/fullJson read per device: the dependent count (blast radius) plus the identity
// fields the swap exchanges. Null when the read fails.
private Map _deviceSwapSnapshot(String deviceId) {
    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (!responseText) return null
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof Map)) return null
        // Unknown stays null: a missing count must not read as "nothing uses this device".
        Integer dependents = (parsed.appsUsing instanceof List) ? (parsed.appsUsing as List).size() : null
        try {
            if (parsed.appsUsingCount != null) dependents = parsed.appsUsingCount as Integer
        } catch (NumberFormatException ignored) { }
        // True when the only app using the device is this server, through its own device selection.
        def selfId = app?.id?.toString()
        boolean onlySelf = dependents == 1 && selfId && (parsed.appsUsing instanceof List) &&
            (parsed.appsUsing as List).any { it instanceof Map && it.id?.toString() == selfId }
        def dev = (parsed.device instanceof Map) ? parsed.device : [:]
        return [dependents: dependents, onlySelf: onlySelf, dni: dev.deviceNetworkId?.toString(),
                createTime: dev.createTime?.toString(), label: dev.label?.toString()]
    } catch (Exception e) {
        mcpLog("warn", "device-swap", "snapshot read failed for device ${deviceId} (${e.message}) -- swap verification degraded")
        return null
    }
}

// exchanged | unchanged | unreadable | inconsistent. Network ids are unique per device, so a
// clean swap is each device now carrying the other's network id (create time as the fallback
// identity when a network id is missing).
private String _deviceSwapIdentityOutcome(Map fromBefore, Map toBefore, Map fromAfter, Map toAfter) {
    if (fromBefore == null || toBefore == null || fromAfter == null || toAfter == null) return "unreadable"
    for (key in ["dni", "createTime"]) {
        def fb = fromBefore[key], tb = toBefore[key], fa = fromAfter[key], ta = toAfter[key]
        if (fb == null || tb == null || fa == null || ta == null || fb == tb) continue
        if (fa == tb && ta == fb) return "exchanged"
        if (fa == fb && ta == tb) return "unchanged"
        return "inconsistent"
    }
    return "unreadable"
}

// Raw input descriptor lookup on a configure/json page. _rmCollectInputSchema
// drops `options`, which the compatibility check needs.
private Map _deviceSwapFindInput(Map cfg, String name) {
    for (s in (cfg?.configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (i instanceof Map && i.name?.toString() == name) return i
        }
    }
    return null
}

// Normalize an enum input's options to [[id, label], ...]. The hub renders
// device options as a list of single-entry maps ([{<deviceId>: <label>}, ...]);
// tolerate a plain map too in case firmware flattens the shape.
private List _deviceSwapEnumOptions(Map input) {
    def out = []
    def opts = input?.options
    if (opts instanceof Map) {
        opts.each { k, v -> out << [id: k?.toString(), label: v?.toString()] }
    } else if (opts instanceof List) {
        for (opt in opts) {
            if (opt instanceof Map) {
                opt.each { k, v -> out << [id: k?.toString(), label: v?.toString()] }
            }
        }
    }
    return out
}

// All type:button inputs on the page except the Cancel button (closeApp).
// After both pickers are set the hub reveals exactly one swap-action button.
private List _deviceSwapActionButtons(Map cfg) {
    def names = []
    for (s in (cfg?.configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (i instanceof Map && i.type?.toString() == "button" && i.name && i.name.toString() != "closeApp") {
                names << i.name.toString()
            }
        }
    }
    return names
}

// Remove the transient Swap Device instance. The Cancel button (closeApp) does NOT
// reap a pending (installed:false) instance -- verified live on fw 2.5.0.143; only
// /installedapp/delete/<id> does. Never throws: cleanup runs on failure paths where
// the original error must win.
private boolean _deviceSwapCleanup(Integer appId) {
    try {
        hubInternalGetRaw("/installedapp/delete/${appId}")
        mcpLog("info", "device-swap", "Transient Swap Device instance ${appId} deleted")
        return true
    } catch (Exception e) {
        // error (not warn) so the orphan-leak case is queryable at the default error-only MCP log level.
        // Not harmless: rendering a leftover instance's page performs the swap again.
        mcpLogError("device-swap", "Delete of Swap Device instance ${appId} failed -- opening it in the hub UI would swap the devices again", e)
        return false
    }
}

// The waitFor sub-schema of hub_get_device and hub_list_devices (multi adds mode); defaults are in
// the descriptions, which _waitForSpec applies.
private Map _waitForSchema(boolean multi) {
    Map props = [attribute: [type: "string"],
        expectedValue: [type: "string", description: "Value or numeric threshold"],
        expectedValues: [type: "array", items: [type: "string"], description: "Value set, or between [low, high]"],
        comparator: [type: "string", enum: ["eq", "ne", "gt", "gte", "lt", "lte", "between"], description: "Default eq"],
        stableForMs: [type: "integer", minimum: 0, description: "Default 0"],
        timeoutMs: [type: "integer", minimum: 100, maximum: 60000, description: "Default 5000"],
        pollIntervalMs: [type: "integer", minimum: 50, maximum: 5000, description: "Default 200"]]
    if (multi) props.mode = [type: "string", enum: ["any", "all"], description: "Default all"]
    return [type: "object", description: multi ? "Wait until deviceIds (max 20) match" : "Wait until it matches; summary mode",
            properties: props, required: ["attribute"]]
}

def _getAllToolDefinitions_partDevices() {
    // The list builds in a closure so its strings live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit.
    def defs = { [
        // Device Tools
        [
            name: "hub_list_devices",
            description: """List devices with current states from the hub's Devices page, filtered and paged on the hub. format='context' = plain-text house snapshot, the best first call for "what's going on". unfilteredTotal = devices in scope before filters. deviceIds + waitFor blocks until an attribute matches, e.g. to confirm a command batch. Control only devices the user named exactly. Guide: hub_get_tool_guide(section='performance_devices').""",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["authorized", "all", "owned"], description: "all = every hub device; owned = this app's virtual devices"],
                    format: [type: "string", enum: ["summary", "detailed", "ids", "context"], description: "detailed adds attributes, commands"],
                    labelFilter: [type: "string", description: "Label substring"],
                    capabilityFilter: [type: "string", description: "Exact capability, e.g. Switch"],
                    roomFilter: [type: "string", description: "Exact room name"],
                    disabled: [type: "boolean", description: "true = only disabled"],
                    staleHours: [type: "number", description: "Inactive this many hours"],
                    changedSince: [type: ["string", "integer"], description: "Active since: epoch ms or ISO-8601 with offset"],
                    stateFilter: [type: "object", description: "e.g. {\"switch\":\"on\"}"],
                    attributeNames: [type: "array", items: [type: "string"], description: "Attributes shown; summary/context only"],
                    fields: [type: "array", items: [type: "string"], description: "Record fields (names in guide); summary/detailed only"],
                    limit: [type: "integer", description: "Page size; omit for all"],
                    cursor: [type: "string", description: "\"\" first, then nextCursor"],
                    deviceIds: [type: "array", items: [type: ["string", "integer"]], description: "Only these devices"],
                    waitFor: _waitForSchema(true)
                ]
            ]
        ],
        [
            name: "hub_get_device",
            description: """Inspect one device. Default summary gives capabilities, current attributes and commands; waitFor blocks until an attribute matches. Use mode='configuration' before updates[[FLAT_TRIM]] to discover editable fields, saved preferences, types/defaults/options and driver information; mode='details' exposes configuration and other device information by section[[/FLAT_TRIM]].[[FLAT_TRIM]] Read status distinguishes unavailable information from unset values.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices, e.g. \"42\""],
                    mode: [type: "string", enum: ["summary", "configuration", "details"], default: "summary",
                           description: "summary: concise capabilities/attributes/commands; configuration: valid editable fields and preference definitions/current values; details: all available information in selectable sections."],
                    sections: [type: "array", items: [type: "string", enum: _deviceDetailSections()],
                               description: "details mode only: non-empty section selection. Omit for all sections.[[FLAT_TRIM]] Large histories remain reachable through read-tool references.[[/FLAT_TRIM]]"],
                    fields: [type: "array", items: [type: "string"], description: "configuration/details only: omit for all values, [] for availableFields name discovery, or select exact field/preference names.[[FLAT_TRIM]] For commands/jobs use row indices from availableFields; attributes also accepts individual attribute names.[[/FLAT_TRIM]]"],
                    cursor: [type: "string", description: "Oversized reads return contentFormat=json-fragment and nextCursor. Repeat with unchanged arguments within five minutes, concatenate content, then parse JSON.[[FLAT_TRIM]] Expired/evicted snapshots require restarting. Prefer fields/sections for smaller reads.[[/FLAT_TRIM]]"],
                    waitFor: _waitForSchema(false)
                ],
                required: ["deviceId"]
            ]
        ],
        [
            name: "hub_call_device_command",
            description: """Send a command (e.g. on, off, setLevel) to one device, or to SEVERAL at once via `commands`. Use to actuate or control a device; for read-only checks use hub_get_device instead.

For more than one device pass `commands` (max 20) rather than calling this repeatedly: one round trip instead of N.[[FLAT_TRIM]] Entries are independent -- one failing entry does not stop the others; check results[] for per-entry success. Confirm a batch with hub_list_devices' deviceIds + waitFor. The round trip, not the hub actuating the device, is most of the wall-clock time, so this is the biggest win available on a multi-device intent; mixed devices and mixed commands go in one batch. For a set commanded repeatedly a group or scene is better still - one device to command, with the hub fanning out; `commands` is for the ad-hoc set nobody defined in advance.[[/FLAT_TRIM]]

If no exact device match: suggest similar devices and get user confirmation before sending any command.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: ["string", "integer"], description: "Device ID from hub_list_devices - must be confirmed by user if not an exact match.[[FLAT_TRIM]] Omit when using commands.[[/FLAT_TRIM]]"],
                    command: [type: "string", description: "Command name, e.g. \"setLevel\". Must be one of the device's supported commands (see hub_get_device).[[FLAT_TRIM]] Omit when using commands.[[/FLAT_TRIM]]"],

                    // The nested item descriptions are terse by design: the three fields are
                    // described in full at the top level, and restating them here doubled this
                    // tool's schema bytes against the flat catalog's ~124KB cap.
                    commands: [
                        type: "array",
                        description: "Several devices in ONE call. Mutually exclusive with deviceId/command; no waitFor. Entries return no state snapshot. A bad entry is reported in its own results[] slot while the rest still go; malformed input is rejected before anything is sent.[[FLAT_TRIM]] Entries are sent in the order given.[[/FLAT_TRIM]]",
                        minItems: 1,
                        maxItems: 20,
                        items: [
                            type: "object",
                            properties: [
                                deviceId: [type: ["string", "integer"], description: "As deviceId above"],
                                command: [type: "string", description: "As command above"],
                                parameters: [type: "array", description: "As parameters above", items: [type: "string"]]
                            ],
                            required: ["deviceId", "command"]
                        ]
                    ],
                    parameters: [type: "array", description: "Ordered command arguments as an array of strings, in the order the command declares them, e.g. [\"75\"] for setLevel[[FLAT_TRIM]] or [\"#FF0000\"] for setColor[[/FLAT_TRIM]].", items: [type: "string"]],
                    includeState: [type: "boolean", description: "false skips the state read-back after the command. Default true."],
                    waitFor: [type: "object", description: "Optional: after firing the command, block-poll an attribute for an expected value; check waitFor.converged to confirm the resulting state.[[FLAT_TRIM]] The native `state` snapshot is read after dispatch and any poll. Without waitFor it is an immediate read that may show the prior or resulting value.[[/FLAT_TRIM]]", properties: [
                        attribute: [type: "string", description: "Attribute to poll until it converges, e.g. \"switch\". NOT checked before the command fires (native metadata lists reported attributes only): a name the device never reports times out with neverReported after the command has already executed."],
                        expectedValue: [type: "string", description: "Awaited value: eq/ne in-set, or gt/gte/lt/lte numeric threshold."],
                        expectedValues: [type: "array", items: [type: "string"], description: "Awaited set: eq/ne value list (OR), or between's two bounds [low, high]."],
                        comparator: [type: "string", enum: ["eq", "ne", "gt", "gte", "lt", "lte", "between"], description: "Match operator. Default eq.", default: "eq"],
                        stableForMs: [type: "integer", description: "Debounce ms; match must hold this long before converging. Default 0, < timeoutMs.", default: 0, minimum: 0],
                        timeoutMs: [type: "integer", description: "Max wait in MILLISECONDS. Default 5000, min 100, max 30000. BLOCKS a hub thread for the full timeout, so keep it tight.", default: 5000, minimum: 100, maximum: 30000],
                        pollIntervalMs: [type: "integer", description: "Re-check interval in MILLISECONDS. Default 250, min 50, max 5000. Clamped to timeoutMs if larger.[[FLAT_TRIM]] Target cadence: read latency is subtracted from the sleep down to a floor of half the interval; the final tick can still sleep less than that floor because the remaining timeoutMs window clamps it.[[/FLAT_TRIM]]", default: 250, minimum: 50, maximum: 5000]
                    ], required: ["attribute"]]
                ]

                // No `required` array: the two forms require different arguments (deviceId+command,
                // or commands), which JSON Schema cannot express here without oneOf. Enforced at
                // runtime instead, with a message naming the conflict.
            ]
        ],
        [
            name: "hub_list_device_events",
            description: """Event history for one device (deviceId), the events one installed app or rule emitted (appId), or with neither the location and hub events (mode, HSM, hub variables, reboots, low memory).[[FLAT_TRIM]] Device rows carry producedBy (what caused the event) and triggered (the apps it fired); attribute 'command-on' shows who sent a command. Without hoursBack, since, until or attribute, a device call returns its most recent events. Location rows carry kind 'location' or 'hub'.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: ["string", "integer"], description: "Exclusive with appId; omit both for location and hub events"],
                    appId: [type: "integer", description: "Installed app or rule; exclusive with deviceId"],
                    hoursBack: [type: "integer", description: "Max 168; ignored with since"],
                    since: [type: ["string", "integer"], description: "Window start (exclusive): ISO-8601 with offset or epoch ms"],
                    until: [type: ["string", "integer"], description: "Window end (inclusive), same formats"],
                    attribute: [type: "string", description: "Device attribute, e.g. switch; location: mode, hsmStatus, hsmAlert or a hub-variable name"],
                    limit: [type: "integer", description: "Default 10 recent, 100 for a window; max 500", default: 10]
                ]
            ]
        ],
        [
            name: "hub_update_device",
            description: """Change a device's settings the way its device page saves them: Device Info fields, driver preferences, assistant sharing and enabled state. Pre-flight: read hub_get_tool_guide(section='update_device') and hub_get_device(mode='configuration'); the configuration's editableFields and preferences give each name's type, options, current value and whether this device offers it. Only change devices the user asked about. Fields marked requiresConfirmation (driver, network or Zigbee ID, dashboards, Hub Mesh, assistants) need confirm=true and a backup under 24 hours old. Saving preferences, showOnHome, defaultCurrentState or retryEnabled runs the driver's updated(). The result lists each change and error from one read-back; inspect it before retrying.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices."],
                    fields: [type: "object", description: "Fields to change, by editableFields name: label, name, deviceNetworkId, room (name or id; \"\" clears), enabled, tags (replaces the set), notes, defaultIcon, maxEvents, maxStates, spammyThreshold, deviceTypeId, zigbeeId, dashboardIds, meshEnabled, meshFullSync, retryEnabled, showOnHome, defaultCurrentState, dataValues, homeKitEnabled, amazonAlexaEnabled, googleHomeEnabled. Omitted fields keep their values."],
                    preferences: [type: "object", description: "Driver preferences by declared name: a value of the declared type, {value, multiple} for an enum whose configuration reports multiple:null, or {clear:true} to remove an optional saved value."],
                    confirm: [type: "boolean", description: "Approval for requiresConfirmation fields; also needs a hub backup under 24 hours old."]
                ],
                required: ["deviceId"]
            ]
        ],
        // Device Admin
        [
            name: "hub_delete_device",
            description: """⚠️ MOST DESTRUCTIVE: Permanently delete a device. NO UNDO.[[FLAT_TRIM]] Requires Write master + confirm. For ghost/orphaned/stuck devices only.[[/FLAT_TRIM]]

PRE-FLIGHT: 1) Backup <24h 2) hub_get_device to verify 3) Warn user 4) Z-Wave/Zigbee → exclusion first 5) Get confirmation. Device + history lost, automations break.[[FLAT_TRIM]] A Hub Mesh LINKED device unlinks locally only (the peer's source device is untouched) and warns on appsUsing. See hub_get_tool_guide(section='hub_admin_write_destructive').[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID to delete."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true.[[FLAT_TRIM]] Confirms a backup exists (<24h), the device was verified, and the user approved the deletion.[[/FLAT_TRIM]]"]
                ],
                required: ["deviceId", "confirm"]
            ]
        ],
        [
            name: "hub_call_device_swap",
            description: """⚠️ DESTRUCTIVE: Swap a device — apps and rules using fromDeviceId run on toDeviceId's hardware afterwards. Running it again swaps them back.[[FLAT_TRIM]] The hub exchanges the two devices' identities (name, label, network id, driver, room, current state; event history stays): app references stay on both ids, so fromDeviceId's apps run on the replacement and toDeviceId's apps on the old device.[[/FLAT_TRIM]]

Pre-flight (mandatory): 1) hub backup <24h (hub_create_backup); 2) hub_list_device_dependents on both ids — fromDeviceId's apps move to the new hardware, toDeviceId's to the old; 3) confirm with the user.""",
            inputSchema: [
                type: "object",
                properties: [
                    fromDeviceId: [type: "string", description: "Device the apps use today (from hub_list_devices).[[FLAT_TRIM]] Its id keeps every app reference and takes on the replacement's identity.[[/FLAT_TRIM]]"],
                    toDeviceId: [type: "string", description: "Replacement device ID. Must be capability-compatible — on mismatch the error lists the compatible candidates."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms a hub backup exists (<24h) and the user approved the swap."]
                ],
                required: ["fromDeviceId", "toDeviceId", "confirm"]
            ]
        ],
        [
            name: "hub_call_device_replace",
            description: """⚠️ DESTRUCTIVE: Replace a device's hardware, KEEPING its id + all app/rule references.

[[FLAT_TRIM]]
Two-step: listOptions=true to list candidates (with newDeviceId it also reports compatible), then apply with confirm=true. Built for newly paired hardware taking over an existing device.
[[/FLAT_TRIM]]
Pre-flight: backup <24h (hub_create_backup) + user OK.""",
            inputSchema: [
                type: "object",
                properties: [
                    oldDeviceId: [type: "string", description: "Device to replace; its id is preserved."],
                    newDeviceId: [type: "string", description: "Compatible replacement device."],
                    listOptions: [type: "boolean", description: "Read-only: list compatible replacement candidates (no confirm)."],
                    confirm: [type: "boolean", description: "REQUIRED to apply (omit for listOptions): confirms backup <24h + user approval."]
                ],
                required: ["oldDeviceId"]
            ]
        ],
        [
            name: "hub_create_device",
            description: """Create a device from a driver TYPE id. Requires Write master + confirm.[[FLAT_TRIM]] For LAN/integration/software drivers, NOT radio pairing (Z-Wave/Zigbee/Matter); for virtual devices use hub_manage_virtual_device. Or LINK a peer hub's shared device over Hub Mesh: pass meshSourceHubId + meshSourceDeviceId from hub_get_hub_mesh availableLinkedDevices[] instead of deviceTypeId; see hub_get_tool_guide(section='update_device').[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceTypeId: [type: "string", description: "Driver-type id to instantiate (the 'id' from hub_list_drivers(include='all')).[[FLAT_TRIM]] Omit when linking a Hub Mesh device.[[/FLAT_TRIM]]"],
                    label: [type: "string", description: "Optional display label for the new device.[[FLAT_TRIM]] Driver-based create only.[[/FLAT_TRIM]]"],
                    meshSourceHubId: [type: "string", description: "Hub Mesh: peer hubId.[[FLAT_TRIM]] From hub_get_hub_mesh availableLinkedDevices[]; send with meshSourceDeviceId, omit deviceTypeId.[[/FLAT_TRIM]]"],
                    meshSourceDeviceId: [type: "string", description: "Hub Mesh: peer device id.[[FLAT_TRIM]] From the same availableLinkedDevices[] row.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to create the device."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_get_compatible_devices",
            description: """Search Hubitat's official compatible-devices catalog of brands/models[[FLAT_TRIM]] with pairing/exclusion/factory-reset instructions and the Hubitat driver each maps to[[/FLAT_TRIM]]. Read-only reference -- NOT your installed devices.""",
            inputSchema: [
                type: "object",
                properties: [
                    query: [type: "string", description: "Free-text match across brand, name, product number, device type, and driver name."],
                    brand: [type: "string", description: "Filter by brand (substring, case-insensitive)."],
                    protocol: [type: "string", description: "Filter by protocol (substring), e.g. 'Zigbee'."],
                    deviceType: [type: "string", description: "Filter by device type (substring), e.g. 'Dimmer'."],
                    includeInstructions: [type: "boolean", description: "Include join/exclude/factory-reset instructions[[FLAT_TRIM]] (HTML stripped) + notes[[/FLAT_TRIM]]. Default false (summaries)."],
                    cursor: [type: "string", description: "Pagination cursor. Pass \"\" (or omit) for the first page; iterate nextCursor."]
                ]
            ]
        ],
    ] }
    return defs()
}

def _readOnlyToolNames_partDevices() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Device introspection
        "hub_list_devices", "hub_get_device", "hub_list_device_events",
        // Compatible-devices catalog (static reference read)
        "hub_get_compatible_devices"
    ]
}

def _idempotentWriteToolNames_partDevices() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Devices
        "hub_update_device", "hub_delete_device"
    ]
}

def _toolDisplayMeta_partDevices() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Devices
        hub_list_devices: [title: "List Devices", summary: "List devices from the hub's Devices page with state filters, projection, a house snapshot and paging."],
        hub_get_device: [title: "Get Device Details", summary: "Inspect a device summary, configuration and preferences, or detailed information by section."],
        hub_list_device_events: [title: "List Device Events", summary: "Recent events for a device or app, or location and hub events when neither is given."],
        hub_call_device_command: [title: "Send Device Command", summary: "Send a command like on, off, or setLevel to one device, or up to 20 in one call."],
        hub_call_device_swap: [title: "Swap Device", summary: "Exchange two devices' identities so the apps on one run on the other's hardware."],
        hub_call_device_replace: [title: "Replace Device Hardware", summary: "Re-point a device to replacement hardware, keeping its id and all references."],
        hub_update_device: [title: "Update Device Settings", summary: "Change a device's Device Info fields, driver preferences, assistant sharing or enabled state."],
        hub_create_device: [title: "Create Device From Driver", summary: "Create a device from a driver-type id, or link a device shared by a peer hub over Hub Mesh."],
        hub_get_compatible_devices: [title: "Search Compatible Devices", summary: "Search Hubitat's compatible-device catalog for models and pairing/reset instructions."],
        hub_delete_device: [title: "Delete Device", summary: "Permanently delete a device from the hub (no undo)."]
    ]
}
