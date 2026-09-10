library(name: "McpDevicesLib", namespace: "mcp", author: "kingpanther13", description: "Device tool implementations (list/get/attribute/events/command/update/delete) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// Capability names off whatever the source hands over: the Groovy device model's Capability
// objects (read `.name`, as every other reader in this library does), a spec's `[name:]` map, or
// a bare string from a JSON inventory.
private List _capabilityNames(def caps) {
    (caps instanceof List ? caps : []).collect { c ->
        (c instanceof CharSequence) ? c.toString() : (c?.name?.toString() ?: c?.toString())
    }.findAll { it != null }
}

def toolListDevices(detailed, offset, limit, filter = null, labelFilter = null, capabilityFilter = null, format = null, fields = null, cursor = null, scope = null, roomFilter = null, onlyOn = null, changedSince = null, attributeNames = null) {
    // Opt-in cursor pagination decodes onto the existing offset/limit mechanics. The
    // real range check against the filtered total happens further down (the
    // offset-exceeds early return) because we don't have the filtered count yet; pass
    // Integer.MAX_VALUE so the helper only does the parse + negative check.
    // cursor='' / null returns offset 0 exactly like every other paginated tool.
    if (cursor != null) {
        // Reject ambiguous combinations -- a caller passing both cursor and a non-default
        // offset is asking for two different starting points; pick one.
        if (offset != null && (offset as Integer) > 0) {
            throw new IllegalArgumentException("cursor and offset are mutually exclusive (got cursor=${cursor}, offset=${offset}); pick one")
        }
        offset = _parseListCursor(cursor, Integer.MAX_VALUE, "hub_list_devices")
        // Default page size in cursor mode so nextCursor arithmetic is deterministic.
        // Callers who want a different page size set limit explicitly (cursor still wins
        // on offset; limit just sizes the page).
        if (!limit || limit <= 0) limit = 50
    }
    // context format defaults to a 50-device page (same size cursor mode uses) so an
    // unpaginated call on a large hub stays token-cheap; limit is read at pagination time,
    // so defaulting here (before resolvedFormat exists) is safe.
    if (format == "context" && (!limit || limit <= 0)) limit = 50
    // Type/format validity for the state-filter args, BEFORE the scope='all' route below
    // (and called by the filter='virtual' route in the dispatch case): a malformed value
    // must be a -32602 on every path, never silently carried into a specialized listing.
    _validateListDeviceStateArgTypes(roomFilter, onlyOn, changedSince, attributeNames, format)
    // scope='all' lists EVERY hub device (not just MCP-authorized), tagging each mcpAuthorized
    // true/false so a caller who can't control a device sees it must be added to the MCP list.
    // Distinct lightweight path (plain endpoint maps, not Groovy device objects).
    if (scope != null && !(scope in ["authorized", "all"])) {
        throw new IllegalArgumentException("scope must be 'authorized' (default) or 'all' (got: '${scope}')")
    }
    if (scope == "all") {
        if (detailed) {
            throw new IllegalArgumentException("scope='all' does not support detailed=true (attributes/commands/currentStates require MCP-authorized devices); use scope='authorized' for detail.")
        }
        // The scope='all' records are lightweight endpoint maps with no room/attribute/
        // activity data, so none of the state-based filters (or the state-bearing context
        // format) can be evaluated for them.
        // onlyOn compares == true and roomFilter by truthiness: false / empty string are
        // the documented no-op values everywhere else (the filter='virtual' guard in the
        // dispatch case matches), so they must not become errors only under scope='all'.
        if (format == "context" || roomFilter || onlyOn == true || changedSince != null || attributeNames != null) {
            throw new IllegalArgumentException("scope='all' does not support format='context', roomFilter, onlyOn, changedSince, or attributeNames (those need MCP-authorized device state); use scope='authorized' (default).")
        }
        return _listAllHubDevices(offset, limit, labelFilter, capabilityFilter, format, cursor)
    }
    // Child identities supply ownership only; the native inventory supplies device content.
    // Keep this membership snapshot for mcpManaged and selection deduplication.
    def childDevs = getChildDevices() ?: []

    // Remaining validation for the classic args, BEFORE the empty-inventory early return
    // so a bad argument is a -32602 even on a hub with no authorized devices. Groovy
    // coercion would otherwise surface as MissingMethodException deep in the filter
    // logic rather than a clear -32602 error. (The state-filter arg types were already
    // validated above, before the scope='all' route.)
    if (labelFilter != null && !(labelFilter instanceof String)) {
        throw new IllegalArgumentException("labelFilter must be a string")
    }
    if (capabilityFilter != null && !(capabilityFilter instanceof String)) {
        throw new IllegalArgumentException("capabilityFilter must be a string")
    }
    if (fields != null && !(fields instanceof List)) {
        throw new IllegalArgumentException("fields must be an array")
    }
    def resolvedFormat = format ?: "summary"
    // attributeNames only shapes the context lines; on any other format it would be
    // silently ignored, and the caller would believe the projection applied. (Semantic
    // check, deliberately AFTER the scope='all' route so that route's own rejection
    // message wins for scope='all' callers.)
    if (attributeNames != null && resolvedFormat != "context") {
        throw new IllegalArgumentException("attributeNames applies only to format='context' (got format '${resolvedFormat}'); it would be silently ignored otherwise.")
    }
    // Re-parse changedSince to the Date the filter needs (validity was proven above).
    def changedSinceDate = changedSince != null ? _parseSinceArg(changedSince) : null

    def allDevices
    try {
        allDevices = _mcpVisibleDevices(childDevs)
    } catch (IllegalStateException e) {
        return [success: false, isError: true, error: e.message, note: "Retry the native device inventory read."]
    }

    if (!allDevices) {
        def emptyMsg = "No devices selected for MCP access and no MCP-managed virtual devices"
        // format='context' keeps its shape contract even on an empty install: a caller
        // reading `summary` must never get a bare devices-array shape instead.
        if (resolvedFormat == "context") {
            def header = _contextHeaderLines()
            header << "Devices: 0 of 0"
            def r = [mode: location.mode?.toString(), summary: (header + [emptyMsg]).join("\n"), count: 0, total: 0, message: emptyMsg]
            def hsm = _safeHsmStatus()
            if (hsm) r.hsmStatus = hsm
            return r
        }
        return [devices: [], message: emptyMsg, total: 0]
    }

    // Capture the full set BEFORE any filter narrows it. Consumed by the zero-match
    // typo-vs-absence diagnostics (capabilityFilterMatchedKnownCapability and
    // roomFilterMatchedKnownRoom) so a mistyped value can be distinguished from a
    // real-but-excluded one even when an earlier filter already emptied the set.
    def unfilteredDevices = allDevices

    def unfilteredTotal = allDevices.size()

    // Parse and apply server-side filter BEFORE pagination so limit/offset respect the filtered set.
    // Supported filters: null/"all" (default), "enabled", "disabled", "stale:<hours>" (e.g. "stale:24").
    // Native records are loaded once before filters that need their metadata or state.
    def filterType = null
    def staleMs = 0L
    if (filter && filter != "all") {
        if (filter == "enabled" || filter == "disabled") {
            filterType = filter
        } else if (filter.startsWith("stale:")) {
            def hoursStr = filter.substring(6).trim()
            def hours
            try {
                hours = hoursStr as Double
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid stale filter '${filter}'. Expected format: stale:<hours> (e.g. stale:24)")
            }
            if (hours <= 0) {
                throw new IllegalArgumentException("stale filter hours must be positive, got: ${hours}")
            }
            filterType = "stale"
            staleMs = (long)(hours * 3600000L)
        } else {
            throw new IllegalArgumentException("Invalid filter '${filter}'. Must be one of: all, enabled, disabled, stale:<hours>")
        }
    }

    // Native bulk labels can narrow a label-only query without hydrating excluded devices.
    boolean labelOnly = labelFilter && !filterType && !capabilityFilter && !roomFilter && onlyOn != true && changedSinceDate == null
    if (labelOnly) {
        if (allDevices.any { !(it._nativeFilterLabel instanceof String) || !it._nativeFilterLabel }) {
            def inventory = _fetchAllHubDeviceRecords('devices', 'Native label filter')
            def labelsById = [:]
            if (!inventory.failure && inventory.records instanceof List) {
                inventory.records.each { d ->
                    if (d instanceof Map && d.id != null && d.label instanceof String && d.label) labelsById.put(d.id.toString(), d.label)
                }
            }
            allDevices.each { d -> if (labelsById.containsKey(d.id)) d._nativeFilterLabel = labelsById.get(d.id) }
        }
        try {
            _hydrateNativeInventory(allDevices.findAll { !(it._nativeFilterLabel instanceof String) || !it._nativeFilterLabel }, [])
        } catch (IllegalStateException e) {
            return [success: false, isError: true, error: e.message, note: "Retry the native device inventory read."]
        }
    } else if (filterType || labelFilter || capabilityFilter || roomFilter || onlyOn == true || changedSinceDate != null) {
        try {
            _hydrateNativeInventory(allDevices, (onlyOn == true ? ['currentStates'] : []) +
                (capabilityFilter ? ['capabilities'] : []))
        } catch (IllegalStateException e) {
            return [success: false, isError: true, error: e.message, note: "Retry the native device inventory read."]
        }
    }

    if (filterType) {
        def nowMs = now()
        allDevices = allDevices.findAll { d ->
            switch (filterType) {
                case "enabled": return !isDeviceDisabled(d)
                case "disabled": return isDeviceDisabled(d)
                case "stale":
                    def la = safeLastActivity(d)
                    if (la == null) return true  // never-reported device counts as stale
                    return (nowMs - la.time) >= staleMs
                default: return true
            }
        }
    }

    // Apply labelFilter (case-insensitive substring match on device label)
    if (labelFilter) {
        def lf = labelFilter.toLowerCase()
        allDevices = allDevices.findAll { d ->
            def lbl = (labelOnly ? (d._nativeFilterLabel ?: d.label ?: d.name) : (d.label ?: d.name))?.toString()?.toLowerCase()
            lbl != null && lbl.contains(lf)
        }
    }

    // Apply capabilityFilter (case-insensitive exact match on capability name).
    if (capabilityFilter) {
        def cf = capabilityFilter.toLowerCase()
        allDevices = allDevices.findAll { d ->
            d.capabilities?.any { cap -> cap.name?.toLowerCase() == cf }
        }
    }

    // Apply roomFilter (case-insensitive exact match on the device's assigned room).
    if (roomFilter) {
        allDevices = allDevices.findAll { d -> d.roomName?.toString()?.equalsIgnoreCase(roomFilter) }
    }

    // onlyOn keeps devices whose switch attribute currently reads "on" -- "what's on right
    // now" in one call. onlyOn=false is a no-op (not "only off"): absence of the filter.
    if (onlyOn == true) {
        // allDevices = allDevices.findAll { d -> d.currentValue("switch")?.toString() == "on" }
        allDevices = allDevices.findAll { d -> d.currentStates?.find { it.name == "switch" }?.value?.toString() == "on" }
    }

    // changedSince keeps devices ACTIVE since the timestamp -- the inverse of filter=stale:N.
    // A device with no readable lastActivity is excluded here (it cannot prove it changed),
    // where stale:N KEEPS the same device (counts it stale) -- both treat unproven as
    // not-recently-active, which lands on opposite sides of inclusion.
    if (changedSinceDate != null) {
        allDevices = allDevices.findAll { d ->
            def la = safeLastActivity(d)
            la != null && la.time >= changedSinceDate.time
        }
    }

    def totalCount = allDevices.size()

    // Apply pagination (post-filter)
    def startIndex = offset ?: 0
    if (startIndex < 0) startIndex = 0
    def endIndex = totalCount
    if (limit && limit > 0) {
        endIndex = Math.min(startIndex + limit, totalCount)
    }

    // Shared filter-echo block for every result shape (summary/detailed/ids/context and the
    // early returns): echo each active filter, report unfilteredTotal when anything narrowed
    // the set, and attach the zero-match typo-vs-absence diagnostics.
    def anyFilterActive = (filter && filter != "all") || labelFilter || capabilityFilter ||
        roomFilter || (onlyOn == true) || changedSinceDate != null
    def applyFilterEchoes = { Map r ->
        if (filter && filter != "all") r.filter = filter
        if (anyFilterActive) r.unfilteredTotal = unfilteredTotal
        if (labelFilter) r.labelFilter = labelFilter
        if (capabilityFilter) r.capabilityFilter = capabilityFilter
        if (roomFilter) r.roomFilter = roomFilter
        if (onlyOn == true) r.onlyOn = true
        if (changedSinceDate != null) {
            // Epoch-ms input echoes as canonical ISO (same policy as _resolveSinceWindow) so
            // the echo always uses a canonical timestamp string.
            r.changedSince = (changedSince instanceof Number || changedSince.toString().trim().isLong()) ?
                changedSinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ") : changedSince.toString().trim()
        }
        if (capabilityFilter && totalCount == 0) {
            def allCaps = unfilteredDevices.collectMany { d -> d.capabilities?.collect { cap -> cap.name?.toLowerCase() } ?: [] } as Set
            r.capabilityFilterMatchedKnownCapability = allCaps.contains(capabilityFilter.toLowerCase())
        }
        if (roomFilter && totalCount == 0) {
            def allRooms = unfilteredDevices.collect { d -> d.roomName?.toString()?.toLowerCase() }.findAll { it != null } as Set
            r.roomFilterMatchedKnownRoom = allRooms.contains(roomFilter.toLowerCase())
        }
        return r
    }

    // Validate offset (after the argument validation above, so callers always get the
    // format/type errors first). Every branch keeps its format's shape contract and the
    // shared filter echoes.
    if (totalCount > 0 && startIndex >= totalCount) {
        def offsetMsg = "Offset ${startIndex} exceeds filtered device count ${totalCount}".toString()
        if (resolvedFormat == "ids") {
            return applyFilterEchoes([deviceIds: [], count: 0, total: totalCount, hasMore: false, nextOffset: null])
        }
        if (resolvedFormat == "context") {
            def header = _contextHeaderLines()
            header << "Devices: 0 of ${totalCount}".toString()
            def r = applyFilterEchoes([mode: location.mode?.toString(), summary: (header + [offsetMsg]).join("\n"), count: 0, total: totalCount, message: offsetMsg])
            def hsm = _safeHsmStatus()
            if (hsm) r.hsmStatus = hsm
            r.offset = startIndex
            r.limit = limit ?: 0
            return r
        }
        def r = applyFilterEchoes([devices: [], total: totalCount, offset: startIndex, limit: limit ?: 0, message: offsetMsg])
        // Preserve this branch's historical unconditional echoes alongside the shared block.
        if (!r.containsKey("filter")) r.filter = filter ?: "all"
        if (!r.containsKey("unfilteredTotal")) r.unfilteredTotal = unfilteredTotal
        return r
    }

    // format="ids" returns a flat array of integer IDs; ignores detailed/fields
    if (resolvedFormat == "ids") {
        def pagedDevices = totalCount > 0 ? allDevices.subList(startIndex, endIndex) : []
        def ids = pagedDevices.collect { it.id as Integer }
        def result = applyFilterEchoes([deviceIds: ids, count: ids.size(), total: totalCount])
        if (limit && limit > 0) {
            result.offset = startIndex
            result.limit = limit
            result.hasMore = endIndex < totalCount
            if (endIndex < totalCount) result.nextOffset = endIndex
            // Cursor mode also emits nextCursor (opaque string) alongside nextOffset so a
            // client iterating cursor sees the same shape used by other paginated tools.
            if (cursor != null && endIndex < totalCount) result.nextCursor = endIndex.toString()
        }
        return result
    }

    // format="context" (issue #366): a token-cheap plain-text house snapshot -- mode + one
    // line per device ("Label (id, room) - capabilities; attr=value, ...") with the
    // standard filters and pagination applied. Unit-suffixed values (72.5°F) keep each
    // line compact and unambiguous for a model reader. The summary string is
    // self-contained (header + lines) so a client can drop it straight into model
    // context; structured fields ride alongside for chaining. Ignores detailed/fields
    // like format='ids'.
    if (resolvedFormat == "context") {
        def pagedDevices = totalCount > 0 ? allDevices.subList(startIndex, endIndex) : []
        try {
            _hydrateNativeInventory(pagedDevices, ['currentStates', 'capabilities'], true)
        } catch (IllegalStateException e) {
            return [success: false, isError: true, error: e.message, note: "Retry the native context read."]
        }
        def attrNames = (attributeNames && !attributeNames.isEmpty()) ? attributeNames.collect { it.toString() } : _contextAttributeNames()
        def lines = pagedDevices.collect { d -> _contextDeviceLine(d, attrNames) }
        def header = _contextHeaderLines()
        def hsm = _safeHsmStatus()
        def deviceLine = "Devices: ${lines.size()} of ${totalCount}"
        if (endIndex < totalCount) deviceLine += " (nextCursor=${endIndex} -- pass cursor:'${endIndex}' for the next page)"
        header << deviceLine.toString()
        def result = applyFilterEchoes([
            mode: location.mode?.toString(),
            summary: (header + lines).join("\n"),
            count: lines.size(),
            total: totalCount
        ])
        if (hsm) result.hsmStatus = hsm
        if (pagedDevices.any { it._nativeReadError || it._nativeUnavailableCollections?.any { it in ['currentStates', 'capabilities'] } }) result.partial = true
        // Typo-vs-absence for the projection, mirroring roomFilter/capabilityFilter: an
        // explicit attributeNames that matched nothing on any line would otherwise be
        // indistinguishable from genuinely attribute-less devices.
        if (attributeNames && !attributeNames.isEmpty() && lines.size() > 0 && lines.every { !it.contains("; ") }) {
            result.attributeNamesMatchedNoAttributes = true
        }
        if (limit && limit > 0) {
            result.offset = startIndex
            result.limit = limit
            result.hasMore = endIndex < totalCount
            if (endIndex < totalCount) {
                result.nextOffset = endIndex
                result.nextCursor = endIndex.toString()
            }
        }
        return result
    }

    def pagedDevices = totalCount > 0 ? allDevices.subList(startIndex, endIndex) : []
    def childDeviceIds = childDevs.collect { it.id.toString() } as Set

    // Build the requested field set. null/empty means "all fields for this format mode".
    def fieldSet = (fields && !fields.isEmpty()) ? (fields as Set) : null

    // Validate field names against the documented whitelist. Unknown names would silently
    // produce empty device objects (a typo gives {id: '1'} instead of {id: '1', label: 'X'})
    // -- catching it here gives the caller a recoverable -32602 instead of bad data.
    if (fieldSet) {
        def validFieldNames = ["id", "name", "label", "room", "disabled", "deviceNetworkId",
            "lastActivity", "parentDeviceId", "mcpManaged", "currentStates",
            "capabilities", "attributes", "commands"] as Set
        def unknownFields = fieldSet - validFieldNames
        if (unknownFields) {
            throw new IllegalArgumentException("Unknown fields: ${unknownFields.sort()}. Valid: ${validFieldNames.sort()}")
        }
    }

    // Resolve whether to use detailed mode: explicit format="detailed", detailed=true, or any
    // detail-only field requested via the fields projection (capabilities, attributes, commands).
    // Auto-promote so fields=['id','capabilities'] works without requiring detailed=true.
    def detailFields = ['capabilities', 'attributes', 'commands'] as Set
    def useDetailed = (resolvedFormat == "detailed") || detailed ||
        (fieldSet != null && fieldSet.any { detailFields.contains(it) })

    if (fieldSet == null || fieldSet.any { !(it in ["id", "mcpManaged"]) }) {
        try {
            def requiredCollections = []
            if (useDetailed) {
                if (fieldSet == null || fieldSet.contains('capabilities')) requiredCollections << 'capabilities'
                if (fieldSet == null || fieldSet.contains('commands')) requiredCollections << 'commands'
                if (fieldSet == null || fieldSet.contains('attributes')) requiredCollections << 'currentStates'
            } else if (fieldSet == null || fieldSet.contains('currentStates')) {
                requiredCollections << 'currentStates'
            }
            _hydrateNativeInventory(pagedDevices, requiredCollections)
        } catch (IllegalStateException e) {
            return [success: false, isError: true, error: e.message, note: "Retry the native device inventory read."]
        }
    }

    def devices = pagedDevices.collect { device ->
        def deviceIdStr = device.id.toString()

        // Metadata hydration is page-scoped; id is always emitted as the correlation key.
        def info = [:]

        info.id = deviceIdStr
        if (fieldSet == null || fieldSet.contains("name")) info.name = device.name
        if (fieldSet == null || fieldSet.contains("label")) info.label = device.label ?: device.name
        if (fieldSet == null || fieldSet.contains("room")) info.room = device.roomName
        if (fieldSet == null || fieldSet.contains("disabled")) info.disabled = isDeviceDisabled(device)
        if (fieldSet == null || fieldSet.contains("deviceNetworkId")) info.deviceNetworkId = safeDni(device)
        if (fieldSet == null || fieldSet.contains("lastActivity")) info.lastActivity = formatLastActivity(safeLastActivity(device))
        if (fieldSet == null || fieldSet.contains("parentDeviceId")) info.parentDeviceId = safeParentDeviceId(device)

        if (childDeviceIds.contains(deviceIdStr)) {
            if (fieldSet == null || fieldSet.contains("mcpManaged")) info.mcpManaged = true
        }

        if (useDetailed) {
            if (fieldSet == null || fieldSet.contains("capabilities")) {
                info.capabilities = device.capabilities?.collect { it.name }
            }
            if (fieldSet == null || fieldSet.contains("attributes")) {
                // info.attributes = device.supportedAttributes?.collect { attr ->
                //     [name: attr.name, value: device.currentValue(attr.name)]
                // }
                info.attributes = device.currentStates.collect { st -> [name: st.name, value: st.value] }
            }
            if (fieldSet == null || fieldSet.contains("commands")) {
                // info.commands = device.supportedCommands?.collect { it.name }
                info.commands = device.commands.collect { it.name }
            }
        } else {
            // Summary mode: populate currentStates only when requested (or when no projection active)
            if (fieldSet == null || fieldSet.contains("currentStates")) {
                info.currentStates = [:]
                ["switch", "level", "motion", "contact", "temperature", "humidity", "battery"].each { attr ->
                    // def val = device.currentValue(attr)
                    def val = device.currentStates?.find { it.name == attr }?.value
                    if (val != null) info.currentStates[attr] = val
                }
            }
        }

        return info
    }

    def result = applyFilterEchoes([
        devices: devices,
        count: devices.size(),
        total: totalCount
    ])

    // Include pagination info if pagination was used
    if (limit && limit > 0) {
        result.offset = startIndex
        result.limit = limit
        result.hasMore = endIndex < totalCount
        if (endIndex < totalCount) {
            result.nextOffset = endIndex
        }
        if (cursor != null && endIndex < totalCount) result.nextCursor = endIndex.toString()
    }

    return result
}

private Boolean isDeviceDisabled(device) {
    // Retained SDK disabled detection for deliberate rollback.
    // try {
    // if (device.hasProperty("disabled") && device.disabled != null) return device.disabled == true
    // } catch (Exception ignore) {}
    // try {
    // return device.isDisabled() == true
    // } catch (Exception ignore) {}
    // try {
    // if (device.hasProperty("status") && device.status?.toString()?.toLowerCase() == "disabled") return true
    // } catch (Exception ignore) {}
    // return false
    return device.disabled == true || device.disabled?.toString()?.toLowerCase() == "true" ||
        device.status?.toString()?.toLowerCase() == "disabled"
}

private String safeDni(device) {
    try {
        return device.deviceNetworkId?.toString()
    } catch (Exception ignore) {
        return null
    }
}

private String safeParentDeviceId(device) {
    try {
        return device.parentDeviceId?.toString()
    } catch (Exception ignore) {
        return null
    }
}

private Date safeLastActivity(device) {
    // return device.getLastActivity()
    if (device instanceof Map && device._nativeActivityParsed == true) return device._nativeLastActivity
    def parsed = null
    try {
        if (device.lastActivityTime != null) parsed = _parseSinceArg(device.lastActivityTime)
    } catch (Exception ignored) { }
    if (device.lastActivityTime != null && parsed == null) {
        mcpLog("error", "device", "Native lastActivityTime could not be parsed for device ${device.id}; activity is unavailable.")
    }
    if (device instanceof Map) {
        device._nativeActivityParsed = true
        device._nativeLastActivity = parsed
    }
    return parsed
}

private String formatLastActivity(Date d) {
    if (d == null) return null
    try {
        return d.format("yyyy-MM-dd'T'HH:mm:ssXXX")
    } catch (Exception ignore) {
        return d.toString()
    }
}

// Type/format validity for the issue-#366 state-filter args, shared by toolListDevices
// (before its scope='all' route) and the filter='virtual' route in the dispatch case --
// every path must reject a malformed value with -32602 instead of silently ignoring it.
def _validateListDeviceStateArgTypes(roomFilter, onlyOn, changedSince, attributeNames, format) {
    if (roomFilter != null && !(roomFilter instanceof String)) {
        throw new IllegalArgumentException("roomFilter must be a string")
    }
    if (onlyOn != null && !(onlyOn instanceof Boolean)) {
        throw new IllegalArgumentException("onlyOn must be a boolean")
    }
    if (attributeNames != null && (!(attributeNames instanceof List) || attributeNames.any { !(it instanceof String) })) {
        throw new IllegalArgumentException("attributeNames must be an array of attribute-name strings")
    }
    if (format && !["summary", "detailed", "ids", "context"].contains(format)) {
        throw new IllegalArgumentException("Invalid format '${format}'. Must be one of: summary, detailed, ids, context")
    }
    if (changedSince != null && _parseSinceArg(changedSince) == null) {
        throw new IllegalArgumentException("Unparseable changedSince '${changedSince}'. Pass epoch milliseconds or ISO-8601 with a numeric offset (e.g. 2026-06-23T10:00:00-0600 or -06:00; trailing Z accepted).")
    }
}

// Default attribute set for format='context' lines when the caller passes no attributeNames.
// The common state-bearing attributes an LLM needs to answer "what's going on in the house";
// a device reports only the ones it has, so unused entries cost nothing per device.
private List _contextAttributeNames() {
    ["switch", "level", "motion", "contact", "presence", "lock", "temperature", "humidity",
     "illuminance", "battery", "power", "energy", "thermostatMode", "thermostatOperatingState",
     "heatingSetpoint", "coolingSetpoint", "speed", "position", "valve", "water", "smoke"]
}

// The "Mode:" (+ optional "HSM:") header lines every context-format result leads with;
// each call site appends its own "Devices: N of M" line. A null mode renders as
// "unknown" -- the literal "Mode: null" would read as a mode named null.
private List _contextHeaderLines() {
    def header = ["Mode: ${location.mode ?: 'unknown'}".toString()]
    def hsm = _safeHsmStatus()
    if (hsm) header << "HSM: ${hsm}".toString()
    return header
}

// One context-summary line: "- Label (id, room) - Cap1, Cap2; attr=value<unit>, ...".
// Projects the already-loaded native currentStates in caller order. Values carry the reported unit directly
// appended (temperature=72.5°F) -- compact and unambiguous for a model reader.
private String _contextDeviceLine(device, List attrNames) {
    def states = [:]
    boolean stateReadFailed = device._nativeReadError == true || device._nativeUnavailableCollections?.contains('currentStates') == true
    try {
        device.currentStates?.each { st ->
            if (st?.name != null && st.value != null) states.put(st.name.toString(), st)
        }
    } catch (Exception e) {
        // Serve the line rather than failing the whole snapshot, but a failed read must
        // be VISIBLE in the payload itself -- byte-identical to "reports nothing" would
        // invite the model to act on absent state. Logged too, for the operator.
        stateReadFailed = true
        mcpLog("warn", "device", "context snapshot: currentStates read failed for device ${device?.id}: ${e.message}")
    }
    def attrParts = []
    attrNames.each { an ->
        def st = states.get(an)
        if (st != null) {
            def unit = null
            // Per-attribute micro-read; a failure only drops the unit suffix, so no log.
            try { unit = st.unit } catch (Exception ignore) {}
            attrParts << "${an}=${st.value}${unit ?: ''}"
        }
    }
    def caps = device.capabilities?.collect { it.name }?.findAll { it != null } ?: []
    def line = "- ${device.label ?: device.name ?: "Device ${device.id}"} (${device.id}, ${device.roomName ?: 'No room'})"
    if (caps) line += " - ${caps.join(', ')}"
    if (attrParts) line += "; ${attrParts.join(', ')}"
    if (stateReadFailed) line += " (state unavailable)"
    if (device._nativeUnavailableCollections?.contains('capabilities')) line += " (capabilities unavailable)"
    return line.toString()
}

// location.hsmStatus is a dynamic property (not on every firmware's Location model) --
// absent/unreadable degrades to "no HSM line" rather than throwing.
private String _safeHsmStatus() {
    try {
        return location.hsmStatus?.toString()
    } catch (Exception ignore) {
        return null
    }
}

// The MCP-visible population: selected/owned identities, or all native identities with bypass. Shared by toolListDevices and the context
// resource builders so the populations cannot drift. Callers that already hold the
// child-device list pass it in to avoid a second getChildDevices() hub read.
private List _mcpVisibleDevices(List childDevs = null) {
    // Retained SDK population for deliberate rollback; active records carry identities only.
    // def all = (selectedDevices ?: []).toList()
    // def ids = all.collect { it.id.toString() } as Set
    // ((childDevs != null ? childDevs : getChildDevices()) ?: []).each { cd ->
    // if (!ids.contains(cd.id.toString())) all.add(cd)
    // }
    // return all
    if (_bypassEnabled()) {
        def inventory = _fetchAllHubDeviceRecords("device", "native device inventory")
        if (inventory?.failure || !(inventory?.records instanceof List) || inventory.idsComplete == false) {
            throw new IllegalStateException("Native device inventory is unavailable or incomplete; retry before using the device list.")
        }
        if (inventory.records.any { !(it instanceof Map) || it.id == null }) {
            throw new IllegalStateException("Native device inventory contained an invalid device record; retry after checking hub firmware.")
        }
        def byId = [:]
        inventory.records.each { d -> byId.put(d.id.toString(), [id: d.id.toString(), _nativeFilterLabel: d.label]) }
        return byId.values() as List
    }
    def byId = [:]
    ((selectedDevices ?: []) + ((childDevs != null ? childDevs : getChildDevices()) ?: [])).each { d ->
        if (d?.id != null) byId.put(d.id.toString(), [id: d.id.toString()])
    }
    return byId.values() as List
}

private void _hydrateNativeInventory(List records, List requiredCollections, boolean allowPartial = false) {
    records.each { record ->
        if (record._nativeLoaded != true) {
            def fj = _fetchDeviceFullJson(record.id)
            if (!(fj?.device instanceof Map)) {
                if (!allowPartial) throw new IllegalStateException("Native device metadata is unavailable for device ${record.id}; no SDK fallback was used.")
                record.putAll([_nativeLoaded: true, _nativeReadError: true,
                    _nativeUnavailableCollections: ['currentStates', 'capabilities', 'commands']])
                return
            }
            def d = fj.device
            def states = d.currentStates instanceof Map ? [] : null
            if (states != null) d.currentStates.each { name, st ->
                states << [name: name.toString(), value: _nativeDeviceStateValue(st),
                           unit: st instanceof Map ? st.unit : null]
            }
            record.putAll([name: d.name, label: d.label, roomName: d.roomName,
                disabled: d.disabled, status: d.status, deviceNetworkId: d.deviceNetworkId,
                parentDeviceId: d.parentDeviceId, lastActivityTime: d.lastActivityTime,
                capabilities: d.capabilities instanceof List ? _capabilityNames(d.capabilities).collect { [name: it] } : null,
                currentStates: states, commands: fj.commands instanceof List ? fj.commands : null,
                _nativeUnavailableCollections: _unavailableNativeDeviceCollections(fj), _nativeLoaded: true])
        }
        // Filters and page projections can consume different collections from the same fetch.
        def unavailable = requiredCollections.findAll { record._nativeUnavailableCollections.contains(it) }
        if (unavailable && !allowPartial) {
            throw new IllegalStateException("Native device collections are unavailable for device ${record.id}: ${unavailable.join(', ')}.")
        }
    }
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
// cursor pagination, truncated at the resource byte budget with an explicit pointer at
// the paginated tool form. The shared native hydration and line projection keep
// the tool/resource content aligned; stop fetching once the emitted lines fill the budget.
def _buildContextSummaryText() {
    def records
    try { records = _mcpVisibleDevices() }
    catch (IllegalStateException e) { return "Context unavailable: ${e.message}".toString() }
    def attrNames = _contextAttributeNames()
    def lines = []
    int used = 0
    for (record in records) {
        _hydrateNativeInventory([record], ['currentStates', 'capabilities'], true)
        def line = _contextDeviceLine(record, attrNames)
        lines << line
        used += _escapedLen(line) + 2
        if (used > _contextResourceByteBudget()) break
    }
    def header = _contextHeaderLines()
    header << "Devices: ${Math.min(lines.size(), records.size())} of ${records.size()}".toString()
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

// The hubitat://context resource body: the structured counterpart of the context summary
// -- current mode (+ HSM when available), the mode list, a rooms[] index with deviceIds,
// and one compact record per MCP-visible device (id, label, room, capabilities,
// attribute values). Attributes are projected through the same default set as the text
// form but carry RAW values with no unit suffix (the text form appends units): an
// unfiltered currentStates dump drags in driver-internal rows (e.g. tile-text
// attributes like "_1") that only add noise to a context snapshot -- verified live on a
// real inventory. The rooms index is capped at half the budget and device records stop
// at whatever remains (clamped non-negative); a truncated result says so on each axis
// and points at the paginated tool form / hub_list_rooms.
def _buildContextJson() {
    def allDevices
    try { allDevices = _mcpVisibleDevices() }
    catch (IllegalStateException e) { return [success: false, isError: true, error: e.message] }
    // The native tree carries room membership for the full index without per-device state reads.
    def roomsById = [:]
    try {
        def raw = hubInternalGet('/hub2/devicesList')
        def tree = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        def records = _flattenHub2DeviceTree(tree instanceof Map ? tree.devices : null)
        records?.each { d ->
            if (d.containsKey('roomName') && (d.roomName == null || d.roomName instanceof String)) roomsById.put(d.id.toString(), d.roomName)
        }
    } catch (Exception e) {
        mcpLog("warn", "device", "Native context room inventory unavailable: ${e.class.simpleName}; reading room metadata per device.")
    }
    allDevices.each { d ->
        if (roomsById.containsKey(d.id)) d.roomName = roomsById.get(d.id)
        else _hydrateNativeInventory([d], [], true)
    }
    def contextAttrs = _contextAttributeNames() as Set
    def roomIndex = [:]
    allDevices.each { d ->
        def r = d.roomName?.toString() ?: "No room"
        if (!roomIndex.containsKey(r)) roomIndex[r] = []
        roomIndex[r] << d.id.toString()
    }
    // The rooms index scales with the inventory too, so it gets its own cap (half the
    // budget) -- on an extreme fleet it could exceed the whole budget by itself, and no
    // amount of device-record clamping would save the response.
    int roomsCap = (int) (_contextResourceByteBudget() / 2)
    int roomsUsed = 0
    boolean roomsTruncated = false
    def rooms = []
    for (entry in roomIndex.collect { name, ids -> [name: name, deviceIds: ids] }) {
        roomsUsed += _escapedLen(groovy.json.JsonOutput.toJson(entry))
        if (roomsUsed > roomsCap) {
            roomsTruncated = true
            break
        }
        rooms << entry
    }
    // Device records get whatever the budget leaves after the rooms index; all costs are
    // escaped-envelope characters (see _contextResourceByteBudget).
    int budget = Math.max(0, _contextResourceByteBudget() - roomsUsed - 1000)
    int used = 0
    boolean truncated = false
    def devices = []
    for (d in allDevices) {
        _hydrateNativeInventory([d], ['currentStates', 'capabilities'], true)
        def attrs = [:]
        boolean stateReadFailed = d._nativeReadError == true || d._nativeUnavailableCollections?.contains('currentStates') == true
        try {
            d.currentStates?.each { st ->
                if (st?.name != null && st.value != null && contextAttrs.contains(st.name.toString())) {
                    attrs.put(st.name.toString(), st.value.toString())
                }
            }
        } catch (Exception e) {
            // Mirror the text form: mark the record in-band, not just in the log.
            stateReadFailed = true
            mcpLog("warn", "device", "context snapshot: currentStates read failed for device ${d?.id}: ${e.message}")
        }
        def rec = [id: d.id.toString(), label: d.label ?: d.name, room: d.roomName,
                   capabilities: d.capabilities?.collect { it.name }?.findAll { it != null } ?: [],
                   attributes: attrs]
        if (stateReadFailed) rec.stateUnavailable = true
        if (d._nativeUnavailableCollections?.contains('capabilities')) rec.capabilitiesUnavailable = true
        if (d._nativeReadError) rec.metadataUnavailable = true
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
    if (devices.any { it.stateUnavailable || it.capabilitiesUnavailable || it.metadataUnavailable }) result.partial = true
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



// Every hub device, authorized or not -- it lives with its primary consumer
// (hub_list_devices scope='all') and the selectedDevices settings validator calls it
// cross-library.
// /device/listWithCapabilities/json carried capabilities but is gone as of platform 2.5.1.173 and
// later (404; confirmed on .173, .174 and .181). On such hubs the inventory is assembled from two
// reads, UNIONED by id: /hub2/devicesList is the SPINE (the authoritative whole-hub tree, no
// capabilities) and /hub2/vrb/devices -- the VRB 2.0 device picker feed, a flat array carrying
// {id, label, capabilities} for every device -- supplies capabilities. Spine devices come first in
// tree order; a device only the feed lists is appended after them. Neither source's omission ever
// costs a device: a spine device the feed omits (or whose entry has no capabilities list) is
// present without a `capabilities` key (the caller fills authorized devices in from native
// fullJson), a feed device the tree omits is present from the feed, and either omission flags the
// result partial with a counted note.
// `source` is the endpoint the records were built from: the tree, unless the feed supplied
// capabilities for them (the union), or the feed alone when the tree could not be used.
// Returns [source, capabilities, records] (+ partialNote when capabilities is false but records
// exist, + idsComplete:false whenever the ID SET cannot be vouched for -- the two sources
// disagree about it: the feed lists a device the tree lacks; the tree could not be read so the
// feed stands alone; the tree answered EMPTY beside a populated feed (contradictory data, the
// feed is the honest answer); or the tree answered EMPTY with no feed answer at all (an empty hub
// and a dead endpoint look identical when nothing is alive to contradict either).
// On failure records is null and `failure` is "fetch" (with fetchError) or "shape" -- a missing
// body, a missing `devices` key or a malformed node. The caller owns the wording.
private Map _fetchAllHubDeviceRecords(String logCategory, String logPrefix) {
    try {
        def txt = hubInternalGet("/device/listWithCapabilities/json")
        def parsed = new groovy.json.JsonSlurper().parseText(txt ?: "[]")
        // An empty/204 body parses to [] and would otherwise pass as a real (empty) inventory.
        if (txt && parsed instanceof List && !parsed.isEmpty()) {
            return [source: "/device/listWithCapabilities/json", capabilities: true, records: parsed]
        }
        // A 200 that is not a device list is contract drift; say so rather than fall through silently.
        mcpLog("debug", logCategory, "${logPrefix}: /device/listWithCapabilities/json answered with ${txt ? 'an empty or non-list body' : 'no body'} -- assembling the inventory from /hub2/devicesList + /hub2/vrb/devices")
    } catch (Exception e) {
        mcpLog("debug", logCategory, "${logPrefix}: /device/listWithCapabilities/json unavailable (${e.message}) -- assembling the inventory from /hub2/devicesList + /hub2/vrb/devices")
    }

    // The spine. Its failure modes are the caller's failure modes -- unless the feed can stand in.
    def spine = null
    def spineFailure = null
    try {
        def txt = hubInternalGet("/hub2/devicesList")
        def parsed = new groovy.json.JsonSlurper().parseText(txt ?: "{}")
        spine = _flattenHub2DeviceTree(parsed instanceof Map ? parsed.devices : null)
        if (!(spine instanceof List)) {
            mcpLog("warn", logCategory, "${logPrefix}: /hub2/devicesList returned an unexpected shape")
            spineFailure = [source: "/hub2/devicesList", capabilities: false, records: null, failure: "shape"]
            spine = null
        }
    } catch (Exception e) {
        mcpLog("warn", logCategory, "${logPrefix}: /hub2/devicesList fetch/parse failed: ${e.message}")
        spineFailure = [source: "/hub2/devicesList", capabilities: false, records: null, failure: "fetch", fetchError: e.message]
    }

    // The capability feed, keyed by id string in feed order. Every entry with an id is kept (its
    // raw id and label); `capabilities` is set only when the entry carries a real list, so an entry
    // without one is absorbed per record, never a reason to drop the feed.
    def feed = null
    boolean feedHasCapabilities = false
    try {
        def txt = hubInternalGet("/hub2/vrb/devices")
        def parsed = new groovy.json.JsonSlurper().parseText(txt ?: "[]")
        if (txt && parsed instanceof List && !parsed.isEmpty()) {
            feed = [:]
            parsed.each { entry ->
                if (!(entry instanceof Map) || entry.id == null) return
                def rec = [id: entry.id, label: entry.label]
                // An EMPTY list is not an answer: the feed lists devices it cannot see into with
                // `capabilities: []`, and an authorized device would then hide from capabilityFilter
                // behind a list the model could have filled. Treat it exactly like an absent list.
                if (entry.capabilities instanceof List && !entry.capabilities.isEmpty()) { rec.capabilities = entry.capabilities; feedHasCapabilities = true }
                feed.put(entry.id.toString(), rec)
            }
            if (feed.isEmpty()) {
                mcpLog("debug", logCategory, "${logPrefix}: /hub2/vrb/devices answered ${parsed.size()} entries but none carried an id")
                feed = null
            } else if (!feedHasCapabilities) {
                mcpLog("debug", logCategory, "${logPrefix}: /hub2/vrb/devices answered ${feed.size()} entries but none carried a capabilities list")
            }
        } else {
            mcpLog("debug", logCategory, "${logPrefix}: /hub2/vrb/devices answered with ${txt ? 'an empty or unrecognized body' : 'no body'}")
        }
    } catch (Exception e) {
        mcpLog("debug", logCategory, "${logPrefix}: /hub2/vrb/devices unavailable (${e.message})")
    }

    if (spine != null && feed == null) {
        if (spine.isEmpty()) {
            // Nothing is alive to contradict an empty tree: a hub with no devices and a dead
            // endpoint answering {devices: []} look identical from here, so the id set cannot be
            // vouched for. Zero records, flagged -- never zero devices passed off as the truth.
            mcpLog("warn", logCategory, "${logPrefix}: /hub2/devicesList reports no devices and /hub2/vrb/devices did not answer; the inventory cannot be vouched for")
            return [source: "/hub2/devicesList", capabilities: false, idsComplete: false, records: spine,
                    partialNote: "The whole-hub device tree (/hub2/devicesList) answered no devices and the Visual Rule Builder device feed (/hub2/vrb/devices) did not answer, so an empty hub cannot be told from a dead endpoint. Retry to cross-check."]
        }
        // Capability-less last resort: the caller fills authorized devices in from the model.
        return [source: "/hub2/devicesList", capabilities: false, records: spine]
    }
    boolean spineContradicted = false
    if (spine != null && spine.isEmpty()) {
        // The tree says "no devices" while the feed lists some. A dead endpoint can answer empty,
        // so an empty spine is only trustworthy when nothing contradicts it; here the feed does,
        // and passing zero devices off as the complete truth is the one outcome that must not
        // happen. Fall through to the feed-alone answer, flagged partial with this reason.
        mcpLog("warn", logCategory, "${logPrefix}: /hub2/devicesList answered no devices while /hub2/vrb/devices listed ${feed.size()}; using the feed alone")
        spineContradicted = true
        spine = null
    }
    if (spine != null) {
        def missingCapabilities = 0   // spine devices the feed gave no capabilities list for
        def spineIds = [] as Set
        def records = spine.collect { d ->
            def key = d.id?.toString()
            spineIds << key
            def entry = feed.get(key)
            if (entry != null && entry.containsKey("capabilities")) return [id: d.id, label: d.label, capabilities: entry.capabilities]
            missingCapabilities++
            return [id: d.id, label: d.label]   // no `capabilities` key on purpose: routes to the model fill-in
        }
        // The union's other direction: a device only the feed lists keeps its feed record. Its
        // presence PROVES the tree short, so the id set is complete only when there are none.
        def feedOnly = feed.findAll { key, rec -> !spineIds.contains(key) }.values() as List
        def feedOnlyWithoutCapabilities = feedOnly.findAll { !it.containsKey("capabilities") }.size()
        records.addAll(feedOnly)
        boolean idsComplete = feedOnly.isEmpty()
        def notes = []
        if (!feedOnly.isEmpty()) {
            notes << "The whole-hub device tree (/hub2/devicesList) omitted ${feedOnly.size()} device(s) that the Visual Rule Builder device feed (/hub2/vrb/devices) lists; they are included from the feed" +
                    (feedOnlyWithoutCapabilities > 0 ? ", ${feedOnlyWithoutCapabilities} of them without a capabilities list (an empty list there means unknown, not none)." : ".")
        }
        if (!feedHasCapabilities) {
            // A feed with no capabilities list anywhere is an id source, not a capability source:
            // it omitted nothing, the caller fills authorized devices in from the model, and the
            // no-capability-source wording applies (so no partialNote for that alone).
            mcpLog("debug", logCategory, "${logPrefix}: /hub2/vrb/devices carried no capabilities lists; inventory is /hub2/devicesList with the feed's ids unioned in")
            def out = [source: "/hub2/devicesList", capabilities: false, records: records]
            if (!idsComplete) { out.idsComplete = false; out.partialNote = notes.join(" ").toString() }
            return out
        }
        if (missingCapabilities == 0 && idsComplete) return [source: "/hub2/vrb/devices", capabilities: true, records: records]
        if (missingCapabilities > 0) {
            notes.add(0, "The Visual Rule Builder device feed (/hub2/vrb/devices) omitted ${missingCapabilities} of ${spine.size()} device(s) listed by /hub2/devicesList, or listed them without capabilities; those carry capabilities only when MCP-authorized, so capabilityFilter cannot match them otherwise.")
        }
        mcpLog("warn", logCategory, "${logPrefix}: ${notes.join(' ')}")
        def out = [source: "/hub2/vrb/devices", capabilities: false, records: records, partialNote: notes.join(" ").toString()]
        if (!idsComplete) out.idsComplete = false
        return out
    }

    // The spine could not be used (unreadable, or it contradicted the feed). The feed alone is
    // still a usable inventory, but nothing can vouch for its completeness, so it is reported
    // partial with the reason and idsComplete:false -- never as the complete capability-bearing
    // answer the successful path returns. The records ARE the feed's, so `source` names it.
    if (feed != null) {
        def why = spineContradicted ?
                "answered no devices while /hub2/vrb/devices listed ${feed.size()} and was not trusted" :
                "could not be read (${spineFailure?.fetchError ?: spineFailure?.failure})"
        mcpLog("warn", logCategory, "${logPrefix}: /hub2/devicesList ${why}; inventory is the /hub2/vrb/devices feed alone")
        return [source: "/hub2/vrb/devices", capabilities: false, idsComplete: false, records: feed.values() as List,
                partialNote: "The whole-hub device tree (/hub2/devicesList) ${why}, so this inventory is the Visual Rule Builder device feed (/hub2/vrb/devices) alone and may omit devices the feed filters out. Retry to cross-check.".toString()]
    }
    return spineFailure
}

// The capabilitiesNote for a partial scope='all' inventory: the counted note the inventory read
// produced, else the no-capability-source wording (both response shapes carry the same text).
private String _allHubCapabilitiesNote(Map inventory) {
    return inventory.partialNote ?: "No capability-bearing source was usable on this hub -- /device/listWithCapabilities/json was removed in platform 2.5.1.173 and later, and /hub2/vrb/devices either did not answer or carried no capabilities lists -- so the inventory came from /hub2/devicesList, which carries no capabilities. Capabilities are filled in for mcpAuthorized devices only; an unauthorized device shows an empty list because its capabilities cannot be read under the current device-access policy. capabilityFilter therefore matches authorized devices only."
}

// scope='all' implementation: every hub device + an mcpAuthorized flag. The Groovy device model is
// authorization-scoped, so an admin endpoint is the only way the app sees devices it isn't granted:
// /device/listWithCapabilities/json (id/label/capabilities) where it still exists; on 2.5.1.173+
// where it is gone, the /hub2/devicesList tree (every id, no capabilities) unioned with the
// /hub2/vrb/devices picker feed (capabilities) -- see _fetchAllHubDeviceRecords. `source` names
// which answered for capabilities; a device without a capabilities record is filled in from
// native fullJson when authorized, and the result says capabilitiesPartial + capabilitiesNote when
// any device lacks one or the tree could not be read. Lightweight uniform records (no
// attributes/commands/currentStates -- those use the ordinary detailed inventory route).
private Map _listAllHubDevices(offset, limit, labelFilter, capabilityFilter, format, cursor) {
    if (labelFilter != null && !(labelFilter instanceof String)) {
        throw new IllegalArgumentException("labelFilter must be a string")
    }
    if (capabilityFilter != null && !(capabilityFilter instanceof String)) {
        throw new IllegalArgumentException("capabilityFilter must be a string")
    }
    def resolvedFormat = format ?: "summary"
    if (format && !["summary", "ids"].contains(resolvedFormat)) {
        throw new IllegalArgumentException("scope='all' supports format 'summary' or 'ids' only (detailed/currentStates require MCP-authorized devices; got '${format}')")
    }
    // Read missing capabilities natively only when the current device-access policy allows it.
    def inventory = _fetchAllHubDeviceRecords("device", "hub_list_devices scope='all'")
    if (inventory.failure == "fetch") {
        return [success: false, isError: true, error: "Failed to fetch the all-hub device list (${inventory.source}): ${inventory.fetchError}", note: "Endpoint may be unavailable on this firmware; use scope='authorized' (default)."]
    }
    if (inventory.failure) {
        return [success: false, isError: true, error: "Unexpected ${inventory.source} response (expected {devices:[...]}).", note: "Hub firmware may have changed the endpoint contract."]
    }
    def raw = inventory.records
    if (!(raw instanceof List) || raw.any { !(it instanceof Map) || it.id == null }) {
        return [success: false, isError: true, error: "Native device inventory contained an invalid record.",
                note: "Retry after checking hub firmware; an incomplete device list was not returned."]
    }
    def sourceEndpoint = inventory.source
    def capabilitiesComplete = inventory.capabilities && raw.every { it.capabilities instanceof List }
    def authorizedIds = ((selectedDevices ?: []).collect { it.id?.toString() }.findAll { it != null } as Set)
    (getChildDevices() ?: []).each { def cid = it.id?.toString(); if (cid != null) authorizedIds.add(cid) }
    // // Capability lookup for the capability-less source, built once from the authorization-scoped model.
    // def capsById = [:]
    // if (!capabilitiesComplete) {
    // // Both sources that feed authorizedIds above, so every device tagged mcpAuthorized
    // // can also report its capabilities -- otherwise an MCP-managed child device would be
    // // authorized yet unmatchable by capabilityFilter.
    // (((selectedDevices ?: []) as List) + ((getChildDevices() ?: []) as List)).each { dev ->
    // def did = dev?.id?.toString()
    // if (did != null) capsById.put(did, _capabilityNames(dev.capabilities))
    // }
    // }
    if (_bypassEnabled()) raw.each { d -> if (d instanceof Map && d.id != null) authorizedIds.add(d.id.toString()) }
    def capsById = [:]
    def unavailableCapabilityIds = []
    if (!capabilitiesComplete) {
        for (d in raw) {
            def did = d instanceof Map ? d.id?.toString() : null
            if (did != null && authorizedIds.contains(did) && !(d.capabilities instanceof List)) {
                def fj = _fetchDeviceFullJson(did)
                if (!(fj?.device instanceof Map) || fj.device.id?.toString() != did || !(fj.device.capabilities instanceof List)) {
                    unavailableCapabilityIds << did
                } else {
                    capsById.put(did, _capabilityNames(fj.device.capabilities))
                }
            }
        }
    }

    // Invalid records were rejected above so the reported inventory cannot silently shrink.
    def devices = raw.collect { d ->
        def idStr = d.id?.toString()
        def caps = (d.capabilities instanceof List) ? _capabilityNames(d.capabilities)
                                                   : (idStr != null ? (capsById.get(idStr) ?: []) : [])
        def record = [id: idStr, label: d.label, capabilities: caps, mcpAuthorized: idStr != null && authorizedIds.contains(idStr)]
        if (unavailableCapabilityIds.contains(idStr)) record.capabilitiesUnavailable = true
        record
    }
    def unfilteredTotal = devices.size()
    if (labelFilter) {
        def lf = labelFilter.toLowerCase()
        devices = devices.findAll { (it.label ?: "").toString().toLowerCase().contains(lf) }
    }
    if (capabilityFilter) {
        def cf = capabilityFilter.toLowerCase()
        devices = devices.findAll { dev -> dev.capabilities.any { c -> c?.toLowerCase() == cf } }
    }
    def totalCount = devices.size()
    def startIndex = (offset && offset > 0) ? (offset as Integer) : 0
    if (startIndex > totalCount) startIndex = totalCount
    def endIndex = (limit && limit > 0) ? Math.min(startIndex + (limit as Integer), totalCount) : totalCount
    def paged = (totalCount > 0 && startIndex < endIndex) ? devices.subList(startIndex, endIndex) : []

    if (resolvedFormat == "ids") {
        def ids = paged.findAll { it.id?.isInteger() }.collect { it.id as Integer }
        def r = [deviceIds: ids, count: ids.size(), total: totalCount, scope: "all", unfilteredTotal: unfilteredTotal]
        // Same inventory metadata as the summary shape: the ids caller must also be able to see
        // that capabilityFilter matched authorized devices only.
        r.source = sourceEndpoint
        if (!capabilitiesComplete) {
            r.capabilitiesPartial = true
            r.capabilitiesNote = _allHubCapabilitiesNote(inventory)
        }
        if (unavailableCapabilityIds) {
            r.capabilitiesUnavailableIds = unavailableCapabilityIds
            r.capabilitiesNote = "${r.capabilitiesNote ?: ''} Native capabilities unavailable for device IDs ${unavailableCapabilityIds.join(', ')}; capabilityFilter cannot determine their matches.".toString().trim()
        }
        if (inventory.idsComplete == false) r.idsComplete = false
        if (labelFilter) r.labelFilter = labelFilter
        if (capabilityFilter) r.capabilityFilter = capabilityFilter
        if (limit && limit > 0) {
            r.offset = startIndex; r.limit = limit; r.hasMore = endIndex < totalCount
            if (endIndex < totalCount) r.nextOffset = endIndex
            if (cursor != null && endIndex < totalCount) r.nextCursor = endIndex.toString()
        }
        return r
    }
    def authorizedCount = devices.findAll { it.mcpAuthorized }.size()
    def result = [
        devices: paged,
        count: paged.size(),
        total: totalCount,
        scope: "all",
        unfilteredTotal: unfilteredTotal,
        mcpAuthorizedCount: authorizedCount,
        unauthorizedCount: totalCount - authorizedCount,
        note: "scope='all' lists EVERY hub device with mcpAuthorized true/false. mcpAuthorized reflects current device access: selected devices and MCP-owned children are authorized, and enabling device-allowlist bypass authorizes every existing hub device. With bypass off, add an unauthorized device in the hub UI (MCP Rule Server app > device selection) before reading or controlling it. Records are lightweight (id/label/capabilities/mcpAuthorized); use scope='authorized' (default) for full detail/currentStates. mcpAuthorizedCount/unauthorizedCount are over the full filtered set (they sum to total), not the returned page."
    ]
    result.source = sourceEndpoint
    if (!capabilitiesComplete) {
        // Say it plainly rather than let an empty list read as "this device has no capabilities":
        // capabilityFilter can only match authorized devices on this path.
        result.capabilitiesPartial = true
        result.capabilitiesNote = _allHubCapabilitiesNote(inventory)
    }
    if (unavailableCapabilityIds) {
        result.capabilitiesUnavailableIds = unavailableCapabilityIds
        result.capabilitiesNote = "${result.capabilitiesNote ?: ''} Native capabilities unavailable for device IDs ${unavailableCapabilityIds.join(', ')}; capabilityFilter cannot determine their matches.".toString().trim()
    }
    // The record SET, as distinct from its capabilities: a caller branches on this field.
    if (inventory.idsComplete == false) result.idsComplete = false
    if (labelFilter) result.labelFilter = labelFilter
    if (capabilityFilter) result.capabilityFilter = capabilityFilter
    if (limit && limit > 0) {
        result.offset = startIndex; result.limit = limit; result.hasMore = endIndex < totalCount
        if (endIndex < totalCount) result.nextOffset = endIndex
        if (cursor != null && endIndex < totalCount) result.nextCursor = endIndex.toString()
    }
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

// Confirm a device's disabled flag via a FRESH /device/fullJson re-read. NOT via the request-scoped
// Groovy device handle, whose disabled/isDisabled() is execution-cached and does NOT reflect a
// same-request /device/disable POST. Robust to the flag arriving as a Boolean or the string "true".
//   [ok:true]                          -> matches wantDisabled
//   [ok:false, fetchFailed:true]       -> read-back fetch failed (could not confirm)
//   [ok:false, actualDisabled:<bool>]  -> confirmed mismatch (flip did not land)
private Map _confirmDisabledFlip(deviceId, boolean wantDisabled) {
    def fj = _fetchDeviceFullJson(deviceId)
    if (fj?.device == null) return [ok: false, fetchFailed: true]
    def raw = fj.device.disabled
    if (!(raw instanceof Boolean) && !(raw instanceof CharSequence && raw.toString() in ['true', 'false'])) {
        return [ok: false, fetchFailed: true]
    }
    def nowDisabled = (raw == true || raw?.toString() == "true")
    return (nowDisabled == wantDisabled) ? [ok: true] : [ok: false, actualDisabled: nowDisabled]
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
        mcpLog("warn", "device", "bypass: /device/eventsJson/${deviceId} fetch/parse failed: ${e.message ?: e.toString()}")
        return null
    }
}

// The display label for a fullJson device (label, else name, else "Device <id>"). Single source
// for the bypass paths so the fallback label is consistent everywhere.
private String _bypassDeviceLabel(Map fj, deviceId) {
    return fj?.device?.label ?: fj?.device?.name ?: "Device ${deviceId}".toString()
}

// Map one /device/eventsJson row to the SAME shape the Groovy-device event paths return
// (description <- descriptionText; the ISO date string passes through). Single source for both the
// recent-N (toolGetDeviceEvents) and the windowed (_deviceHistoryBypass) bypass branches.
private Map _mapBypassEventRow(evt) {
    return [
        name: evt.name,
        value: evt.value,
        unit: evt.unit,
        description: evt.descriptionText,
        date: evt.date,
        isStateChange: evt.isStateChange
    ]
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
        key == 'currentStates' ? !(values[key] instanceof Map) : !(values[key] instanceof List)
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
    return entry?.type?.toString() == 'password' || _deviceConfigurationSecretKey(entry?.name)
}

private boolean _deviceConfigurationSecretKey(key) {
    String normalized = key?.toString()?.toLowerCase()?.replaceAll(/[^a-z0-9]/, '') ?: ''
    return normalized.contains('password') || normalized.contains('token') || normalized.contains('secret') ||
        normalized.contains('credential') || normalized.contains('apikey') || normalized.contains('privatekey') ||
        normalized.contains('accesskey') || normalized in ['psk', 'psw']
}

private _deviceConfigurationPublicValue(value, key = '') {
    if (_deviceConfigurationSecretKey(key)) return '***redacted (password)***'
    if (value instanceof Map) {
        def copy = [:]
        value.each { k, v -> copy.put(k, _deviceConfigurationPublicValue(v, k)) }
        return copy
    }
    if (value instanceof List) return value.collect { _deviceConfigurationPublicValue(it) }
    return value
}

private Map _publicDevicePreference(Map entry) {
    def copy = [:]
    entry.each { key, value ->
        if (!(key in ['rawValue', 'declared'])) copy.put(key, _deviceConfigurationPublicValue(value))
    }
    if (_devicePreferenceIsSecret(entry)) {
        if (copy.containsKey('value')) copy.value = '***redacted (password)***'
        if (copy.containsKey('defaultValue')) copy.defaultValue = '***redacted (password)***'
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

private List _deviceConfigurationFieldDefinitions() {
    return [
        [name: 'label', type: 'string'], [name: 'name', type: 'string'],
        [name: 'deviceNetworkId', type: 'string', requiresConfirmation: true],
        [name: 'room', type: 'string'], [name: 'enabled', type: 'boolean'],
        [name: 'dataValues', type: 'object'], [name: 'preferences', type: 'object'],
        [name: 'showOnHome', type: 'boolean'], [name: 'defaultCurrentState', type: 'string'],
        [name: 'tags', type: 'array', items: [type: 'string']],
        [name: 'deviceTypeId', type: 'integer', requiresConfirmation: true],
        [name: 'zigbeeId', type: 'string', requiresConfirmation: true],
        [name: 'notes', type: 'string'], [name: 'defaultIcon', type: 'string'],
        [name: 'maxEvents', type: 'integer', minimum: 1, maximum: 2000],
        [name: 'maxStates', type: 'integer', minimum: 1, maximum: 2000],
        [name: 'spammyThreshold', type: 'integer', minimum: 100, maximum: 2000],
        [name: 'dashboardIds', type: 'array', items: [type: 'integer'], requiresConfirmation: true],
        [name: 'meshEnabled', type: 'boolean', requiresConfirmation: true],
        [name: 'retryEnabled', type: 'boolean'],
        [name: 'meshFullSync', type: 'boolean', requiresConfirmation: true],
        [name: 'homeKitEnabled', type: 'boolean', requiresConfirmation: true],
        [name: 'amazonAlexaEnabled', type: 'boolean', requiresConfirmation: true],
        [name: 'googleHomeEnabled', type: 'boolean', requiresConfirmation: true]
    ]
}

// listed remains in this helper chain for the retained SDK rollback callers; native editability is independent of selection.
private List _deviceConfigurationEditableFields(Map fj, Map preferences, boolean listed) {
    Map d = (fj?.device instanceof Map) ? fj.device : [:]
    def values = _deviceConfigurationProjection(d, ['label', 'name', 'deviceNetworkId', 'showOnHome',
        'defaultCurrentState', 'deviceTypeId', 'zigbeeId', 'notes', 'defaultIcon', 'maxEvents', 'maxStates',
        'spammyThreshold', 'meshEnabled', 'retryEnabled', 'meshFullSync'])
    if (d.containsKey('roomName')) values.room = d.roomName
    if (d.containsKey('disabled')) {
        def disabled = _normalizeDevicePreferenceValue(d.disabled, 'bool')
        if (disabled.valid && disabled.value != null) values.enabled = !disabled.value
    }
    if (d.containsKey('data')) values.dataValues = _deviceConfigurationPublicValue(d.data)
    if (d.containsKey('tags')) {
        values.tags = _normalizedDeviceTags(d.tags)
    }
    if (fj?.dashboards instanceof List) values.dashboardIds = fj.dashboards.findAll { it.selected == true }.collect { it.id }
    ['homeKitEnabled', 'amazonAlexaEnabled', 'googleHomeEnabled'].each { key ->
        if (fj?.containsKey(key)) values.put(key, fj.get(key))
    }
    def applicable = [
        label: !_deviceFlag(d.linkedAndDisabled),
        name: !_deviceFlag(d.isComponent) && !_deviceFlag(d.linkedDevice),
        deviceNetworkId: (!_deviceFlag(d.isComponent) || _deviceFlag(d.linkedDevice)) && !_deviceFlag(d.linkedLocally),
        dataValues: true,
        deviceTypeId: !_deviceFlag(d.isComponent) && !_deviceFlag(d.linkedDevice),
        zigbeeId: !_deviceFlag(d.isComponent) && !_deviceFlag(d.linkedDevice) && d.zigbeeId instanceof String && !d.zigbeeId.isEmpty(),
        dashboardIds: _deviceFlag(fj?.hasDashboards),
        meshEnabled: _deviceFlag(d.meshSelectionEnabled),
        retryEnabled: _deviceFlag(fj?.commandRetrySelectionEnabled) || _deviceFlag(d.retryAvailable),
        meshFullSync: _deviceFlag(d.linkedDevice) && _deviceFlag(fj?.hubMeshRefreshEnabled),
        homeKitEnabled: _deviceFlag(fj?.homeKitSelectionEnabled),
        amazonAlexaEnabled: _deviceFlag(fj?.amazonAlexaInstalled) && _deviceFlag(fj?.amazonAlexaSupported),
        googleHomeEnabled: _deviceFlag(fj?.googleHomeInstalled) && _deviceFlag(fj?.googleHomeSupported)
    ]
    def fields = _deviceConfigurationFieldDefinitions()
    fields.each { field ->
        String name = field.name
        field.source = name in ['homeKitEnabled', 'amazonAlexaEnabled', 'googleHomeEnabled', 'dashboardIds'] ? 'fullJson' : 'fullJson.device'
        field.valuePresent = values.containsKey(name)
        if (field.valuePresent) field.value = values.get(name)
        field.readStatus = field.valuePresent ? 'complete' : 'unavailable'
        field.applicable = !applicable.containsKey(name) || applicable.get(name)
        field.writable = !d.isEmpty() && field.applicable
        if (field.requiresConfirmation) field.requiresBackup = true
        if (!field.applicable) field.reason = 'This device or installed integration does not expose this edit control.'
        else if (!field.valuePresent) field.reason = 'The native device response did not supply this current value.'
        if (name == 'preferences') {
            field.source = 'fullJson.settings'
            field.valueReference = 'preferences'
            field.readStatus = preferences.status
            field.valuePresent = preferences.status != 'unavailable'
            field.applicable = !_deviceFlag(d.linkedDevice)
            field.writable = field.applicable && preferences.writeSafe == true
            field.remove('reason')
            if (!field.applicable) field.reason = 'Driver preferences cannot be saved on a linked device; edit the source device.'
            else if (preferences.reason) field.reason = preferences.reason
        }
        if (name == 'room') field.optionsReference = [gateway: 'hub_read_rooms', tool: 'hub_list_rooms', args: [:]]
        if (name == 'deviceTypeId') field.optionsReference = [gateway: 'hub_read_apps_code', tool: 'hub_list_drivers', args: [include: 'all']]
        if (name == 'deviceNetworkId' && d.linkedDevice) {
            field.options = [[value: '0', label: 'Keep current device link']]
            try {
                def text = hubInternalGet('/device/accessibleLinkedDevices')
                def available = text ? new groovy.json.JsonSlurper().parseText(text) : null
                if (!(available?.devices instanceof List)) throw new IllegalStateException('Unrecognized linked-device choices')
                available.devices.each { row ->
                    if (row instanceof Map && row.hubId != null && row.deviceId != null) {
                        field.options << [value: "${row.hubId}-${row.deviceId}".toString(),
                            label: row.label ?: row.name ?: row.deviceId.toString(), disabled: _deviceFlag(row.linkedLocally)]
                    }
                }
            } catch (Exception ignored) {
                mcpLog('error', 'device', "Device ${d.id}: /device/accessibleLinkedDevices choices could not be read; retry configuration discovery.")
                field.optionsStatus = 'unavailable'
                field.reason = 'Native linked-device choices could not be read; retry configuration discovery before retargeting.'
            }
        }
        if (name == 'dashboardIds') field.options = _deviceConfigurationPublicValue(fj?.dashboards ?: [])
        if (name == 'defaultCurrentState') field.options = [''] + _fullJsonAttributeNames(fj)
    }
    return fields
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

private Map _deviceConfigurationResult(deviceId, Map identity, Map fj, boolean listed, fields = null) {
    def model = _readDevicePreferenceModel(fj)
    boolean linkedDevice = (fj?.device instanceof Map) && _deviceFlag(fj.device.linkedDevice)
    def read = [status: model.status, source: "/device/fullJson/${deviceId}".toString()]
    if (model.reason) read.reason = model.reason
    boolean available = fj?.device instanceof Map && !fj.device.isEmpty()
    def infoRead = [status: available ? 'complete' : 'unavailable', source: "/device/fullJson/${deviceId}".toString()]
    if (!available) infoRead.reason = 'Native device information could not be fetched or recognized.'
    def result = [id: deviceId.toString(), name: identity.name, label: identity.label, mode: 'configuration',
            editableFields: _deviceConfigurationEditableFields(fj, model, listed),
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

// Re-fetch fullJson so each poll observes fresh native state.
private _readBypassAttrValue(deviceId, attribute) {
    return _readBypassAttrValueFrom(_fetchDeviceFullJson(deviceId), attribute)
}

private List _nativeReportedDeviceAttributes(Map d) {
    def attributes = []
    def cs = d?.currentStates
    if (cs instanceof Map) {
        cs.each { name, st ->
            if (name != null) {
                def dataType = (st instanceof Map) ? st.dataType?.toString() : null
                def value = _nativeDeviceStateValue(st)
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

private Map _deviceExpandedResult(deviceId, Map identity, Map fj, boolean listed, String mode, sections, fields = null) {
    if (mode == 'configuration') return _deviceConfigurationResult(deviceId, identity, fj, listed, fields)
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
                value = _deviceConfigurationResult(deviceId, identity, fj, listed, fields)
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
                if (d.currentStates instanceof Map) value.declaredAttributes = _nativeReportedDeviceAttributes(d).collect { row ->
                    def attribute = _deviceConfigurationPublicValue(row)
                    if (row instanceof Map && _deviceConfigurationSecretKey(row.name)) {
                        attribute.value = '***redacted (password)***'
                    }
                    attribute
                }
                break
            case 'commands':
                value = _deviceConfigurationPublicValue(fj?.commands)
                break
            case 'data':
                value = _deviceConfigurationPublicValue(d.get('data'))
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

private String _deviceReadSelection(deviceId, String mode, sections, fields, boolean listed) {
    return _mrtrSha256(groovy.json.JsonOutput.toJson([app?.id?.toString(), deviceId.toString(), mode, sections, fields]))
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

def toolGetDevice(deviceId, mode = 'summary', sections = null, fields = null, cursor = null) {
    String selectedMode = mode == null ? 'summary' : mode.toString()
    if (!(selectedMode in ['summary', 'configuration', 'details'])) {
        throw new IllegalArgumentException("mode must be summary, configuration, or details.")
    }
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
    String selection = selectedMode == 'summary' ? null : _deviceReadSelection(deviceId, selectedMode, sections, fields, listed)
    if (cursor) return _deviceReadContinuation(cursor, selection)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) {
        return [success: false, isError: true, error: "Device metadata fetch failed (/device/fullJson/${deviceId})",
                note: 'Check the native Devices page and retry.']
    }
    if (selectedMode == 'summary') {
        try {
            return _getDeviceFromFullJson(deviceId, full)
        } catch (IllegalStateException e) {
            return [success: false, isError: true, error: e.message, note: 'Check the native Devices page and retry.']
        }
    }
    def identity = [name: full?.device?.name, label: _bypassDeviceLabel(full, deviceId)]
    return _deviceReadPage(_deviceExpandedResult(deviceId, identity, full, listed, selectedMode, sections, fields), selection)

    // Retained SDK implementation for deliberate rollback.
    //     def device = findDevice(deviceId)
    //     String selection = selectedMode == 'summary' ? null : _deviceReadSelection(deviceId, selectedMode, sections, fields, device != null)
    //     if (cursor) {
    //         if (!device && !_bypassEnabled()) throw new IllegalArgumentException("Device not found: ${deviceId}")
    //         return _deviceReadContinuation(cursor, selection)
    //     }
    //     if (!device) {
    //         if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(deviceId)
    //             if (fj?.device instanceof Map) {
    //                 def identity = _getDeviceFromFullJson(deviceId, fj)
    //                 return selectedMode == 'summary' ? identity : _deviceReadPage(_deviceExpandedResult(deviceId, identity, fj, false, selectedMode, sections, fields), selection)
    //             }
    //         }
    //         throw new IllegalArgumentException("Device not found: ${deviceId}")
    //     }
    //
    //     if (selectedMode == 'configuration') {
    //         return _deviceReadPage(_deviceExpandedResult(deviceId, [name: device.name, label: device.label ?: device.name],
    //                                      _fetchDeviceFullJson(deviceId), true, selectedMode, sections, fields), selection)
    //     }
    //
    //     def attributes = []
    //     try {
    //         attributes = device.supportedAttributes?.collect { attr ->
    //             [name: attr.name, dataType: attr.dataType?.toString(), value: device.currentValue(attr.name)]
    //         } ?: []
    //     } catch (Exception e) {
    //         logDebug("Error getting attributes for device ${deviceId}: ${e.message}")
    //     }
    //
    //     def commands = []
    //     try {
    //         commands = device.supportedCommands?.collect { cmd ->
    //             def args = null
    //             try {
    //                 args = cmd.arguments?.collect { arg ->
    //                     if (arg instanceof Map) {
    //                         [name: arg.name ?: "arg", type: arg.type ?: "unknown"]
    //                     } else if (arg.respondsTo("getName")) {
    //                         [name: arg.getName() ?: "arg", type: arg.getType()?.toString() ?: "unknown"]
    //                     } else {
    //                         [name: arg.toString(), type: "unknown"]
    //                     }
    //                 }
    //             } catch (Exception e) {
    //                 args = null
    //             }
    //             [name: cmd.name, arguments: args]
    //         } ?: []
    //     } catch (Exception e) {
    //         logDebug("Error getting commands for device ${deviceId}: ${e.message}")
    //     }
    //
    //     def summary = [
    //         id: device.id.toString(),
    //         name: device.name,
    //         label: device.label ?: device.name,
    //         room: device.roomName,
    //         capabilities: device.capabilities?.collect { it.name } ?: [],
    //         attributes: attributes,
    //         commands: commands
    //     ]
    //     return selectedMode == 'summary' ? summary : _deviceReadPage(_deviceExpandedResult(deviceId, summary,
    //         _fetchDeviceFullJson(deviceId), true, selectedMode, sections, fields), selection)
}

def toolSendCommand(deviceId, command, parameters, waitFor = null, commands = null, reqT0 = null, includeState = true) {
    // Batch form: one call carrying several {deviceId, command, parameters?} entries. A parameter
    // rather than a second tool, and mutually exclusive with the single-device arguments, which is
    // the same shape hub_get_device_attribute already uses for deviceId vs deviceIds.
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
            throw new IllegalArgumentException("waitFor is not supported with commands: it blocks a hub thread per device. Send the batch, then confirm the whole set in one call with hub_get_device_attribute using deviceIds + mode")
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

    // SDK dispatch retained for deliberate rollback; access membership no longer selects transport.
    // def device = findDevice(deviceId)
    // // Allowlist bypass: an unlisted device (bypass on) resolves through /device/fullJson and is
    // // commanded via the id-keyed runmethod endpoint. A LISTED device keeps the Groovy-device path
    // // unchanged (bypass stays false). fullJson==null leaves bypass off so the not-found throw fires.
    // def bypass = false
    // def fullJson = null
    // if (!device) {
    //     if (_bypassEnabled()) fullJson = _fetchDeviceFullJson(deviceId)
    //     if (fullJson?.device == null) {
    //         throw new IllegalArgumentException("Device not found: ${deviceId}")
    //     }
    //     bypass = true
    // }
    //
    // // Capture label before command execution to avoid serialization issues
    // def deviceLabel = bypass ? _bypassDeviceLabel(fullJson, deviceId)
    //                          : (device.label ?: device.name ?: "Device ${deviceId}")
    //
    // def supportedCommands = bypass ? _fullJsonCommandNames(fullJson)
    //                                : device.supportedCommands?.collect { it.name }
    // if (!supportedCommands?.contains(command)) {
    //     throw new IllegalArgumentException("Device ${deviceLabel} does not support command: ${command}. Available: ${supportedCommands}")
    // }
    //
    // // Validate waitFor BEFORE firing the command so a bad spec fails the call without
    // // a side effect (the command would otherwise have already actuated the device). The
    // // attribute set comes from the Groovy device for a listed device; under bypass it is null
    // // (NOT the fullJson reported-attribute set) so the pre-fire attribute-existence check is
    // // SKIPPED -- fullJson cannot list a declared-but-not-yet-reported attribute, and hard-failing
    // // on it would block a legitimate command. The poll then reports neverReported if it never shows.
    // def supportedAttrs = bypass ? null
    //                             : (device.supportedAttributes?.collect { it.name } ?: [])
    // def pollArgs = (waitFor != null) ? _buildWaitForPollArgs(deviceId, supportedAttrs, deviceLabel, waitFor) : null
    //
    // // Normalize parameters once (when present) so both paths see the typed values. A blank
    // // String means "no parameters" -- null it so the reported parameters stay inside the
    // // array-or-null response contract (a non-blank String always normalizes to a List).
    // if (parameters instanceof String && !parameters.trim()) parameters = null
    // if (parameters && parameters.size() > 0) {
    //     def declaration = bypass ? fullJson.commands?.find { it?.name == command }
    //                              : device.supportedCommands?.find { it.name == command }
    //     // SDK Command exposes arguments, while native JSON also carries richer parameters.
    //     def declaredTypes = _commandParamTypes(bypass ? declaration : [arguments: declaration?.arguments])
    //     parameters = normalizeCommandParams(parameters, declaredTypes)
    // }
    // if (bypass) {
    //     // Single bypass branch: fire via runmethod (empty arg list for a no-parameter command).
    //     def fireErr = _fireBypassCommand(deviceId, command, (parameters && parameters.size() > 0) ? parameters : [], fullJson)
    //     if (fireErr != null) return fireErr
    // } else if (parameters && parameters.size() > 0) {
    //     device."${command}"(*parameters)
    // } else {
    //     device."${command}"()
    // }

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

    // Native fullJson lists reported states, which cannot reject a declared but unreported
    // waitFor attribute. The poll reports neverReported if the attribute never appears.
    def pollArgs = (waitFor != null) ? _buildWaitForPollArgs(deviceId, null, deviceLabel, waitFor) : null
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

    if (pollArgs != null) {
        // Poll before taking the post-dispatch snapshot; immediate native reads may
        // show either the prior or resulting value. Record convergence separately
        // because polling can time out or fail.
        def waitForBlock = [
            attribute: pollArgs.attribute,
            expected : (pollArgs.containsKey("expectedValues") ? pollArgs.expectedValues : pollArgs.expectedValue)
        ]
        try {
            def poll = toolPollUntilAttribute(pollArgs)
            waitForBlock.converged  = poll.success == true
            waitForBlock.finalValue = poll.finalValue
            // poll.polledCount is intentionally NOT surfaced into the waitFor block: elapsedMs
            // is the caller-relevant diagnostic (how long the command-confirm blocked); the
            // raw poll count is an engine-internal detail.
            waitForBlock.elapsedMs  = poll.elapsedMs
            // Surface the engine's diagnostic flags when present so the caller can tell a
            // plain timeout apart from a hub-reload interrupt or a never-reported attribute.
            if (poll.timedOut == true)      waitForBlock.timedOut = true
            if (poll.interrupted == true)   waitForBlock.interrupted = true
            if (poll.readError == true)     waitForBlock.readError = true
            if (poll.neverReported == true) waitForBlock.neverReported = true
            if (poll.nonNumericAttribute == true) waitForBlock.nonNumericAttribute = true
            // nonNumericAttribute carries an actionable note (names the comparator/attribute,
            // suggests eq/ne) -- propagate it so a waitFor caller gets the same guidance.
            if (poll.note != null) waitForBlock.note = poll.note
            // transitioning is present on the timeout path as true OR false (both meaningful),
            // so copy it whenever the engine emitted the key -- not only when true.
            if (poll.containsKey("transitioning")) waitForBlock.transitioning = poll.transitioning
        } catch (Throwable e) {
            // The command already fired; a poll-loop failure (e.g. a malformed numeric
            // attribute) must not lose the response. Catch Throwable -- not just Exception --
            // for the same reason the snapshot does: a non-Exception Throwable after the
            // device actuated must report non-convergence, not escape as a hard error that
            // drops the state/waitFor blocks. Include the class so an NPE doesn't render as
            // "(no message)" with no other clue. Snapshot is still taken below.
            def cls = e.class.simpleName
            // Log at error: a warn is below Hubitat's default log level and returns before
            // writing, so this degraded-path failure would land in neither buffer nor hub log.
            mcpLog("error", "send-command", "waitFor poll failed for ${deviceLabel}: ${cls}: ${e.message ?: '(no message)'}")
            waitForBlock.converged  = false
            waitForBlock.finalValue = null
            waitForBlock.error      = "waitFor poll failed: ${cls}: ${e.message ?: '(no message)'}".toString()
            // The command fired but the confirmation poll degraded -- flag the partial result.
            result.partial = true
        }
        result.waitFor = waitForBlock
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
    // Same ceiling as hub_get_device_attribute's multi-device poll (MAX_POLL_DEVICES); this bound
    // keeps one request's serial dispatch inside the relay window.
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
            throw new IllegalArgumentException("commands[${i}].deviceId is required and must be a non-empty string (got: ${_describeValueForError(entry.deviceId)})")
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
            // hub_get_device_attribute's deviceIds form, not through N inline snapshots.
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
        result.note = "The entries that succeeded ALREADY actuated -- do NOT re-send the whole batch. Before re-sending a failed entry, read its error/note: an outcome the hub did not CONFIRM may still have actuated, so verify with hub_get_device_attribute first. See hub_get_tool_guide(section='device_authorization') for what makes an entry fail."
        // Only a batch where nothing landed is a tool-execution error. Flagging a partial one
        // would tell the caller the call did nothing while devices had in fact moved.
        if (failed == results.size() && !remaining) result.isError = true
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

// Validate a waitFor spec and translate it into a toolPollUntilAttribute args map, reusing
// that tool's block-poll engine for the runtime poll behavior. This validator fully covers
// shape, type, numeric-range, and attribute-existence so a bad spec is rejected BEFORE the
// command fires (the poll engine repeats these checks as defense-in-depth, but its run is
// post-fire, so completing them here is what buys the no-side-effect-on-bad-spec guarantee).
private Map _buildWaitForPollArgs(deviceId, supportedAttrs, deviceLabel, waitFor) {
    if (!(waitFor instanceof Map)) {
        throw new IllegalArgumentException("waitFor must be an object with at least attribute and expectedValue/expectedValues")
    }
    def validKeys = ["attribute", "expectedValue", "expectedValues", "timeoutMs", "pollIntervalMs", "comparator", "stableForMs"] as Set
    def unknownKeys = (waitFor.keySet() - validKeys).sort()
    if (unknownKeys) {
        def label = unknownKeys.size() == 1 ? 'an unknown key' : 'unknown keys'
        throw new IllegalArgumentException("waitFor has ${label}: ${unknownKeys.join(', ')}. Valid keys: ${validKeys.sort().join(', ')}")
    }
    if (!(waitFor.attribute instanceof String) || !waitFor.attribute.trim()) {
        throw new IllegalArgumentException("waitFor.attribute is required and must be a non-empty string")
    }
    // Native command callers pass null: fullJson cannot enumerate declared-but-unreported
    // attributes. Unknown names can therefore time out after the command has executed.
    if (supportedAttrs != null && !supportedAttrs.contains(waitFor.attribute)) {
        throw new IllegalArgumentException("waitFor.attribute '${waitFor.attribute}' not found on device '${deviceLabel}'. Available: ${supportedAttrs.join(', ')}")
    }
    def hasExpectedValue  = waitFor.containsKey("expectedValue")
    def hasExpectedValues = waitFor.containsKey("expectedValues")
    if (hasExpectedValue && hasExpectedValues) {
        throw new IllegalArgumentException("waitFor: provide exactly one of expectedValue or expectedValues, not both")
    }
    if (!hasExpectedValue && !hasExpectedValues) {
        throw new IllegalArgumentException("waitFor: exactly one of expectedValue or expectedValues is required")
    }
    // Validate every pre-checkable field HERE so a bad spec never fires the command.
    // The pollIntervalMs bound MIRRORS toolPollUntilAttribute's [50,5000]. timeoutMs is
    // STRICTER on this command-flow path: a waitFor poll pins a hub thread for the full
    // timeout, so the pre-fire cap is 30000ms (vs the engine's standalone [100,60000]).
    if (hasExpectedValue) {
        // .trim() for parity with attribute -- reject a whitespace-only value, not just "".
        if (!(waitFor.expectedValue instanceof String) || !waitFor.expectedValue.trim()) {
            throw new IllegalArgumentException("waitFor.expectedValue must be a non-empty string (got: ${_describeValueForError(waitFor.expectedValue)})")
        }
    }
    if (hasExpectedValues) {
        if (!(waitFor.expectedValues instanceof List) || waitFor.expectedValues.isEmpty()) {
            throw new IllegalArgumentException("waitFor.expectedValues must be a non-empty list of strings")
        }
        waitFor.expectedValues.eachWithIndex { v, i ->
            if (!(v instanceof String)) {
                throw new IllegalArgumentException("waitFor.expectedValues[${i}] must be a string, got: ${_describeValueForError(v)}")
            }
        }
    }
    // Accept any Number (not just Integer) so a Long/BigDecimal that the engine would
    // accept is not wrongly rejected pre-fire -- toolPollUntilAttribute validates these
    // same fields with `instanceof Number`. The comparison is direct (no `as Integer`
    // cast), so an in-range fractional passes the bounds check exactly as the engine does.
    if (waitFor.containsKey("timeoutMs")) {
        if (!(waitFor.timeoutMs instanceof Number) || waitFor.timeoutMs < 100 || waitFor.timeoutMs > 30000) {
            throw new IllegalArgumentException("waitFor.timeoutMs must be a number between 100 and 30000 (got: ${waitFor.timeoutMs})")
        }
    }
    if (waitFor.containsKey("pollIntervalMs")) {
        if (!(waitFor.pollIntervalMs instanceof Number) || waitFor.pollIntervalMs < 50 || waitFor.pollIntervalMs > 5000) {
            throw new IllegalArgumentException("waitFor.pollIntervalMs must be a number between 50 and 5000 (got: ${waitFor.pollIntervalMs})")
        }
    }
    // Comparator + stableForMs pre-fire validation: mirror the engine's comparator constraints
    // HERE so a bad spec never fires the command (the engine repeats these post-fire as
    // defense-in-depth). The effective timeout for the stableForMs upper bound is the same
    // default applied below.
    def effectiveTimeoutMs = waitFor.containsKey("timeoutMs") ? (waitFor.timeoutMs as Integer) : 5000
    def validComparators = ["eq", "ne", "gt", "gte", "lt", "lte", "between"]
    if (waitFor.containsKey("comparator") && waitFor.comparator == null) {
        throw new IllegalArgumentException("waitFor.comparator must not be null (omit the arg to use the default \"eq\")")
    }
    def comparator = waitFor.containsKey("comparator") ? waitFor.comparator : "eq"
    if (!(comparator instanceof String) || !validComparators.contains(comparator)) {
        throw new IllegalArgumentException("waitFor.comparator must be one of ${validComparators} (got: ${_describeValueForError(waitFor.comparator)})")
    }
    if (["gt", "gte", "lt", "lte"].contains(comparator)) {
        if (hasExpectedValues) {
            throw new IllegalArgumentException("waitFor.comparator '${comparator}' takes a single numeric threshold via expectedValue, not expectedValues")
        }
        if (_parseBigDecimalOrNull(waitFor.expectedValue) == null) {
            throw new IllegalArgumentException("waitFor.comparator '${comparator}' requires expectedValue to be a numeric-parseable string (got: ${_describeValueForError(waitFor.expectedValue)})")
        }
    } else if (comparator == "between") {
        if (hasExpectedValue) {
            throw new IllegalArgumentException("waitFor.comparator 'between' takes two numeric bounds via expectedValues, not expectedValue")
        }
        if (waitFor.expectedValues.size() != 2) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires expectedValues to have exactly 2 numeric bounds [low, high] (got ${waitFor.expectedValues.size()})")
        }
        def lo = _parseBigDecimalOrNull(waitFor.expectedValues[0])
        def hi = _parseBigDecimalOrNull(waitFor.expectedValues[1])
        if (lo == null || hi == null) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires both expectedValues bounds to be numeric-parseable strings (got: ${waitFor.expectedValues})")
        }
        if (lo > hi) {
            throw new IllegalArgumentException("waitFor.comparator 'between' requires low <= high (got low=${waitFor.expectedValues[0]}, high=${waitFor.expectedValues[1]})")
        }
    }
    if (waitFor.containsKey("stableForMs") && waitFor.stableForMs == null) {
        throw new IllegalArgumentException("waitFor.stableForMs must not be null (omit the arg to use default 0)")
    }
    if (waitFor.containsKey("stableForMs")) {
        if (!(waitFor.stableForMs instanceof Number)) {
            throw new IllegalArgumentException("waitFor.stableForMs must be an integer (got: ${_describeValueForError(waitFor.stableForMs)})")
        }
        if (waitFor.stableForMs < 0) {
            throw new IllegalArgumentException("waitFor.stableForMs must be >= 0 (got: ${waitFor.stableForMs})")
        }
        if ((waitFor.stableForMs as Integer) >= effectiveTimeoutMs) {
            throw new IllegalArgumentException("waitFor.stableForMs (${waitFor.stableForMs}) must be less than timeoutMs (${effectiveTimeoutMs}) -- the condition could never hold long enough to converge")
        }
    }
    def pollArgs = [deviceId: deviceId.toString(), attribute: waitFor.attribute]
    if (hasExpectedValue)  pollArgs.expectedValue  = waitFor.expectedValue
    if (hasExpectedValues) pollArgs.expectedValues = waitFor.expectedValues
    if (waitFor.containsKey("comparator"))  pollArgs.comparator  = waitFor.comparator
    if (waitFor.containsKey("stableForMs")) pollArgs.stableForMs = waitFor.stableForMs
    // Default the timeout/interval here so an omitted value uses the command-flow default
    // (5000ms) and pollIntervalMs 250ms -- the 250ms default is the post-command-flow
    // default by intent (the engine's standalone default is 200ms); explicit values pass through.
    pollArgs.timeoutMs      = waitFor.containsKey("timeoutMs")      ? waitFor.timeoutMs      : 5000
    pollArgs.pollIntervalMs = waitFor.containsKey("pollIntervalMs") ? waitFor.pollIntervalMs : 250
    return pollArgs
}

// Render a value plus a coarse runtime-type label for a validation error message. Uses
// instanceof rather than getClass() (reflection is blocked in the Hubitat sandbox), so the
// label is a small fixed vocabulary -- enough to tell a caller "you passed a number/boolean
// where a string was required" without naming the exact JVM class.
private String _describeValueForError(v) {
    def typeLabel = (v == null) ? "null"
        : (v instanceof String)  ? "string"
        : (v instanceof Boolean) ? "boolean"
        : (v instanceof Number)  ? "number"
        : (v instanceof List)    ? "list"
        : (v instanceof Map)     ? "object"
        : "value"
    if (v == null) return "null"
    // Quote strings so an empty or whitespace-only value renders as "" / "   " rather than a
    // bare gap in the message; non-string values render unquoted (e.g. 42 (number)).
    def rendered = (v instanceof String) ? "\"${v}\"" : "${v}"
    return "${rendered} (${typeLabel})"
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
// NOTE: this is an IMMEDIATE read taken in the same request that fired the command -- the
// hub commits a command's effect AFTER that request returns, so without waitFor the value
// here is the PRE-effect state even for virtual/local devices. The timestamp is the freshness
// signal; waitFor (block-poll) is what makes this snapshot reflect the converged state.
// private Map _snapshotDeviceState(device, deviceLabel, errOut = null) {
    // SDK snapshot retained for deliberate rollback. Native reads never fall back to it.
    // def snapshot = [:]
    // try {
    //     def states = device.currentStates
    //     if (states) {
    //         states.each { st ->
    //             if (st?.name != null) {
    //                 // st.date is a java.util.Date on a live hub -- format it directly
    //                 // (formatTimestamp has no Date branch and would mangle it via toString).
    //                 // Guard ONLY the date format locally: a single date that fails to format
    //                 // yields timestamp:null for THAT attribute (keeping its value) instead of
    //                 // discarding the whole snapshot. A structural read throw (currentStates /
    //                 // name / value) still falls to the outer catch and clears to [:].
    //                 def ts = null
    //                 if (st.date) {
    //                     try {
    //                         ts = st.date.format("yyyy-MM-dd HH:mm:ss")
    //                     } catch (Throwable dt) {
    //                         // Log at error so an operator can tell a date-format failure (value
    //                         // kept, timestamp null) apart from an attribute that never reported.
    //                         // Error -- not warn -- because warn is below Hubitat's default level
    //                         // and returns before writing, so this degradation would be invisible.
    //                         ts = null
    //                         mcpLog("error", "send-command", "date format failed for attribute '${st.name}' on ${deviceLabel}: ${dt.class.simpleName}")
    //                     }
    //                 }
    //                 snapshot.put(st.name, [value: st.value, timestamp: ts])
    //             }
    //         }
    //     } else {
    //         // Fallback to currentValue only because currentStates is empty -- the device has
    //         // emitted no state event at all, so there is no prior event for currentValue to be
    //         // stale against; staleness (the reason the poll engine avoids currentValue) is moot
    //         // here. Each entry carries a null timestamp since there is no event to date it.
    //         device.supportedAttributes?.each { attr ->
    //             def name = attr?.name
    //             if (name != null) {
    //                 // Per-attribute guard for parity with the currentStates branch's date guard:
    //                 // one attribute whose currentValue read throws degrades to value:null for
    //                 // THAT attribute, keeping the rest, instead of the outer catch discarding the
    //                 // whole snapshot. name is non-null here, so the entry is always present.
    //                 def val = null
    //                 try {
    //                     val = device.currentValue(name)
    //                 } catch (Throwable cv) {
    //                     // Error -- not warn -- because this guard degrades to value:null with no
    //                     // stateError/partial, so a warn (below the default level, returns before
    //                     // writing) would leave a systemic read failure fully invisible.
    //                     mcpLog("error", "send-command", "currentValue read failed for attribute '${name}' on ${deviceLabel}: ${cv.class.simpleName}")
    //                 }
    //                 snapshot.put(name, [value: val, timestamp: null])
    //             }
    //         }
    //     }
    // } catch (Throwable t) {
    //     // Read-back must never break the command (which already fired). Discard any
    //     // partial snapshot built before a mid-iteration throw and return the null FAILURE
    //     // sentinel so the caller can distinguish a failed read from a legitimately empty
    //     // device. Log at error so it writes under the hub's default log level (a warn here
    //     // is below default and would be invisible -- a silently-failed confirmation read).
    //     // t.message can be null.
    //     def detail = "${t.class.simpleName}: ${t.message ?: '(no message)'}".toString()
    //     mcpLog("error", "send-command", "Failed to snapshot device state for ${deviceLabel}: ${detail}")
    //     if (errOut != null) errOut << detail
    //     return null
    // }
    // return snapshot
// }

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
        mcpLog("error", "send-command", "native: /device/runmethod for '${command}' on ${deviceId} threw: ${e.message ?: e.toString()}")
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
        if (!(resp instanceof Map) || resp.success != false) {
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
                def val = _nativeDeviceStateValue(st)
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
    // Retained SDK implementation for deliberate rollback.
    // if (limit == null || limit < 1) limit = 10
    //     def device = findDevice(deviceId)
    //     if (!device) {
    //         if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(deviceId)
    //             if (fj?.device != null) {
    //                 def label = _bypassDeviceLabel(fj, deviceId)
    //                 // /device/eventsJson returns newest-first with no query params, so apply the
    //                 // limit client-side. Rows carry descriptionText + an ISO date string; map to the
    //                 // SAME shape the Groovy-device path returns. A null return is the FETCH-FAILURE
    //                 // sentinel (distinct from [] = real empty history) -- surface it as a structured
    //                 // error, never an empty-success that silently lies about the device having no events.
    //                 def rows = _fetchBypassDeviceEvents(deviceId)
    //                 if (rows == null) {
    //                     return [success: false, error: "Device event history fetch failed (/device/eventsJson/${deviceId})", device: label,
    //                             note: "The device is reachable via the allowlist bypass but its event store could not be read -- likely a transient hub blip; retry."]
    //                 }
    //                 def events = rows.take(limit as Integer).collect { evt -> _mapBypassEventRow(evt) }
    //                 return [device: label, events: events, count: events.size()]
    //             }
    //         }
    //         throw new IllegalArgumentException("Device not found: ${deviceId}")
    //     }
    //
    //     def events = device.events(max: limit)?.collect { evt ->
    //         [
    //             name: evt.name,
    //             value: evt.value,
    //             unit: evt.unit,
    //             description: evt.descriptionText,
    //             date: evt.date?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
    //             isStateChange: evt.isStateChange
    //         ]
    //     }
    //
    //     return [
    //         device: device.label,
    //         events: events ?: [],
    //         count: events?.size() ?: 0
    //     ]
}

def toolGetAttribute(deviceId, attribute) {
    deviceId = _canonicalDeviceIdArg(deviceId)
    if (deviceId == null) throw new IllegalArgumentException('deviceId must be a non-empty string or integral number')
    if (!(attribute instanceof String) || !attribute) throw new IllegalArgumentException('attribute is required and must be a non-empty string')
    _requireDeviceToolAccess(deviceId)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device?.currentStates instanceof Map)) {
        return [success: false, isError: true, deviceId: deviceId, attribute: attribute,
                error: 'Native device current states could not be read.', note: 'Check the native Devices page and retry.']
    }
    def result = [device: _bypassDeviceLabel(full, deviceId), attribute: attribute,
                  value: _readBypassAttrValueFrom(full, attribute)]
    if (!full.device.currentStates.containsKey(attribute)) {
        result.neverReported = true
        result.reportedAttributes = _fullJsonAttributeNames(full)
        result.note = 'Reported names can help identify a typo; an omitted name may also be a driver attribute that has never reported.'
    }
    return result

    // Retained SDK implementation for deliberate rollback.
    //
    //     // Same canonical form as every other device-id entry point: reject a fractional or
    //     // non-scalar id before it can spend a bypass fullJson fetch below.
    //     def canonicalId = _canonicalDeviceIdArg(deviceId)
    //     if (canonicalId == null) {
    //         throw new IllegalArgumentException("deviceId must be a non-empty string or integral number (got: ${_describeValueForError(deviceId)})")
    //     }
    //     deviceId = canonicalId
    //
    //     def device = findDevice(deviceId)
    //     if (!device) {
    //         if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(deviceId)
    //             if (fj?.device != null) {
    //                 def label = _bypassDeviceLabel(fj, deviceId)
    //                 // fullJson lists only REPORTED attributes, so a declared-but-unreported attribute
    //                 // reads as "not found" here -- bypass attribute discovery is limited to reported
    //                 // attributes (the message says so), unlike the listed path's supportedAttributes.
    //                 def attrs = _fullJsonAttributeNames(fj)
    //                 if (!attrs.contains(attribute)) {
    //                     throw new IllegalArgumentException("Attribute '${attribute}' not found among the reported attributes of device '${label}' (allowlist bypass sees only reported attributes). Reported: ${attrs}")
    //                 }
    //                 return [device: label, attribute: attribute, value: _readBypassAttrValueFrom(fj, attribute)]
    //             }
    //         }
    //         throw new IllegalArgumentException("Device not found: ${deviceId}")
    //     }
    //
    //     // Capture label before operations to avoid serialization issues
    //     def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"
    //
    //     // Check if attribute exists on this device before reading its value
    //     def supportedAttrs = device.supportedAttributes?.collect { it.name } ?: []
    //     if (!supportedAttrs.contains(attribute)) {
    //         throw new IllegalArgumentException("Attribute '${attribute}' not found on device '${deviceLabel}'. Available: ${supportedAttrs}")
    //     }
    //
    //     def value = device.currentValue(attribute)
    //     return [
    //         device: deviceLabel,
    //         attribute: attribute,
    //         value: value
    //     ]
}

// Single source of truth for the per-value match logic, shared by the single- and multi-device
// poll paths so they can never diverge. Returns [matched, numeric]: matched is the boolean
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

def toolPollUntilAttribute(args) {
    // 0. Reject unknown args early to surface caller mistakes (e.g., timeoutSeconds vs timeoutMs).
    def validArgKeys = ["deviceId", "deviceIds", "mode", "attribute", "expectedValue", "expectedValues", "timeoutMs", "pollIntervalMs", "comparator", "stableForMs"] as Set
    if (args instanceof Map) {
        def unknownKeys = (args.keySet() - validArgKeys).sort()
        if (unknownKeys) {
            throw new IllegalArgumentException("Unknown arg(s): ${unknownKeys}. Valid args: ${validArgKeys.sort()}. Common gotcha: timeout is 'timeoutMs' in milliseconds, not 'timeoutSeconds'.")
        }
    }

    // 1. Resolve the target device(s). Exactly one of deviceId (single) or deviceIds (multi)
    //    must be present. A present-but-null key is a distinct caller mistake from an omitted
    //    one, so it is rejected with the same null-guard style the rest of this engine uses.
    def hasDeviceId  = args.containsKey("deviceId")  && args.deviceId  != null
    def hasDeviceIds = args.containsKey("deviceIds") && args.deviceIds != null
    if (args.containsKey("deviceId") && args.deviceId == null) {
        throw new IllegalArgumentException("deviceId must not be null (omit the arg or pass a non-empty string)")
    }
    if (args.containsKey("deviceIds") && args.deviceIds == null) {
        throw new IllegalArgumentException("deviceIds must not be null (omit the arg or pass a non-empty list of device IDs)")
    }
    if (hasDeviceId && hasDeviceIds) {
        throw new IllegalArgumentException("provide exactly one of deviceId or deviceIds, not both")
    }
    if (!hasDeviceId && !hasDeviceIds) {
        throw new IllegalArgumentException("deviceId (single device) or deviceIds (a list of device IDs) is required -- provide exactly one")
    }
    def multiDevice = hasDeviceIds

    // Per-device count cap: the poll re-reads every device's state each interval, so the
    // per-interval work scales with the device count -- bound it to keep a blocking poll cheap.
    def MAX_POLL_DEVICES = 20
    def deviceIdList
    if (multiDevice) {
        if (!(args.deviceIds instanceof List) || args.deviceIds.isEmpty()) {
            throw new IllegalArgumentException("deviceIds must be a non-empty list of device-ID strings")
        }
        // A hub device id is numeric, so a caller that sends it as a JSON number is not wrong --
        // coerce to the string form the rest of the engine compares on. Coercing here also makes
        // the duplicate check see 5 and "5" as the same device.
        def coercedIds = []
        args.deviceIds.eachWithIndex { v, i ->
            def id = _canonicalDeviceIdArg(v)
            if (!(id instanceof String) || !id) {
                throw new IllegalArgumentException("deviceIds[${i}] must be a non-empty string, got: ${_describeValueForError(v)}")
            }
            coercedIds << id
        }
        if (coercedIds.size() > MAX_POLL_DEVICES) {
            throw new IllegalArgumentException("deviceIds has ${coercedIds.size()} device IDs -- the cap is ${MAX_POLL_DEVICES} per poll")
        }
        // Reject duplicates: a repeated id would double-count convergedCount and emit duplicate
        // devices[] rows. Each device may appear at most once.
        def dupes = coercedIds.countBy { it }.findAll { k, c -> c > 1 }.keySet().sort()
        if (dupes) {
            throw new IllegalArgumentException("deviceIds contains duplicate entries: ${dupes} -- each device may appear once")
        }
        deviceIdList = coercedIds
    } else {
        // Same Number coercion as the deviceIds branch: a numeric id is not wrong, just unstrung.
        def singleId = _canonicalDeviceIdArg(args.deviceId)
        if (!(singleId instanceof String) || !singleId) {
            throw new IllegalArgumentException("deviceId is required and must be a non-empty string (got: ${_describeValueForError(args.deviceId)})")
        }
        deviceIdList = [singleId]
    }

    // 2. Validate attribute (the same condition applies to every device).
    if (args.containsKey("attribute") && args.attribute == null) {
        throw new IllegalArgumentException("attribute must not be null (omit the arg or pass a non-empty string)")
    }
    if (!(args.attribute instanceof String) || !args.attribute) {
        throw new IllegalArgumentException("attribute is required and must be a non-empty string")
    }

    // The first native response supplies identity and the first poll sample. Attribute
    // absence cannot reject a declaration that has not emitted a current state.
    def devices = []
    def deviceLabels = []
    deviceIdList.each { did -> _requireDeviceToolAccess(did) }
    for (did in deviceIdList) {
        def full = _fetchDeviceFullJson(did)
        if (!(full?.device instanceof Map)) {
            return [success: false, isError: true, readError: true, deviceId: did,
                    error: "Native device identity is unavailable for device ${did}; polling was not started.",
                    note: 'Verify the device ID and native connectivity before starting a new poll.']
        }
        devices << [initial: full]
        deviceLabels << _bypassDeviceLabel(full, did)
    }
    // Retained SDK declaration preflight for deliberate rollback.
    //     // Resolve every device up front (a missing ID names WHICH one) and confirm each supports
    //     // the attribute (fail fast, naming the device that lacks it) -- the same per-device check
    //     // the single path runs, looped over the resolved set. devices[i] aligns with deviceIdList[i].
    //     def devices = []
    //     def deviceLabels = []
    //     // devices[i] aligns with deviceIdList[i]: the Groovy device for a listed/MCP device, or null
    //     // for an unlisted device reached via the allowlist bypass. The per-poll read (_readPollValue)
    //     // is source-agnostic over that pair -- a listed device reads its live currentStates list; a
    //     // bypass device re-fetches /device/fullJson each poll -- so the converge/timeout/honesty logic
    //     // below is shared unchanged.
    //     deviceIdList.each { did ->
    //         def dev = findDevice(did)
    //         if (dev) {
    //             def label = dev.label ?: dev.name ?: "Device ${did}"
    //             def supportedAttrs = dev.supportedAttributes?.collect { it.name } ?: []
    //             if (!supportedAttrs.contains(args.attribute)) {
    //                 throw new IllegalArgumentException("Attribute '${args.attribute}' not found on device '${label}'. Available: ${supportedAttrs}")
    //             }
    //             devices << dev
    //             deviceLabels << label
    //         } else if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(did)
    //             if (fj?.device == null) {
    //                 throw new IllegalArgumentException("Device not found: ${did}")
    //             }
    //             def label = _bypassDeviceLabel(fj, did)
    //             // No attribute-existence check on the bypass path: fullJson lists only reported
    //             // attributes, so a declared-but-unreported attribute would be wrongly rejected. The
    //             // per-poll read returns null until the attribute reports, so an unknown/unreported
    //             // attribute simply times out with neverReported instead of hard-failing.
    //             devices << null
    //             deviceLabels << label
    //         } else {
    //             throw new IllegalArgumentException("Device not found: ${did}")
    //         }
    //     }
    // Single-path label kept for the existing single-device return shape and the read-fault log.
    def deviceLabel = deviceLabels[0]

    // 2b. Validate mode (any/all over deviceIds). Only meaningful with deviceIds; default "all"
    //     (converge when EVERY device matches). "any" converges on the first device to match.
    //     Validate the VALUE before the single-deviceId reject so an invalid value surfaces as
    //     "must be one of [any, all]" rather than being masked by the deviceIds-only message.
    def validModes = ["any", "all"]
    if (args.containsKey("mode") && args.mode == null) {
        throw new IllegalArgumentException("mode must not be null (omit the arg to use the default \"all\")")
    }
    if (args.mode != null && !(args.mode instanceof String && validModes.contains(args.mode))) {
        throw new IllegalArgumentException("mode must be one of ${validModes} (got: ${_describeValueForError(args.mode)})")
    }
    if (args.mode != null && !multiDevice) {
        throw new IllegalArgumentException("mode applies only to deviceIds (multi-device polling); omit it for a single deviceId")
    }
    def mode = (args.mode != null) ? args.mode : "all"

    // 3. Validate expectedValue / expectedValues (at least one required)
    if (args.containsKey("expectedValue") && args.expectedValue == null) {
        throw new IllegalArgumentException("expectedValue must not be null (omit the arg or pass a non-empty string)")
    }
    if (args.containsKey("expectedValues") && args.expectedValues == null) {
        throw new IllegalArgumentException("expectedValues must not be null (omit the arg or pass a non-empty list)")
    }
    def hasExpectedValue  = (args.expectedValue  != null)
    def hasExpectedValues = (args.expectedValues != null)
    // Exactly-one, the same rule _buildWaitForPollArgs enforces on the waitFor pre-fire path: a
    // numeric comparator takes only expectedValue, between takes only expectedValues, eq/ne take
    // one or the other -- never both. The two checks share intent, not identical messages: the
    // waitFor path prefixes "waitFor." and keys on containsKey (presence of the key), while this
    // engine path keys on != null (a key present-but-null is rejected earlier above as "must not
    // be null"), so {expectedValue:'x', expectedValues:null} reaches the both-set check on the
    // waitFor path but the null-reject here. Aligned for valid specs; the edge messages differ.
    if (hasExpectedValue && hasExpectedValues) {
        throw new IllegalArgumentException("provide exactly one of expectedValue or expectedValues, not both")
    }
    if (!hasExpectedValue && !hasExpectedValues) {
        throw new IllegalArgumentException("At least one of expectedValue or expectedValues must be provided")
    }
    if (hasExpectedValue && !(args.expectedValue instanceof String)) {
        throw new IllegalArgumentException("expectedValue must be a string")
    }
    if (hasExpectedValue && args.expectedValue == "") {
        throw new IllegalArgumentException("expectedValue must not be empty (omit the arg or pass a non-empty value)")
    }
    if (hasExpectedValues) {
        if (!(args.expectedValues instanceof List)) {
            throw new IllegalArgumentException("expectedValues must be a list of strings")
        }
        if (args.expectedValues.isEmpty()) {
            throw new IllegalArgumentException("expectedValues must not be empty (omit the arg or pass at least one value)")
        }
        args.expectedValues.eachWithIndex { v, i ->
            if (!(v instanceof String)) {
                throw new IllegalArgumentException("expectedValues[${i}] must be a string, got: ${_describeValueForError(v)}")
            }
        }
    }

    // 4. Validate timeoutMs.
    // Range [100,60000] for this standalone engine path. _buildWaitForPollArgs uses a STRICTER
    // [100,30000] on the command-flow waitFor path -- this divergence is INTENTIONAL, not a
    // drift: a waitFor poll pins a hub thread for the full timeout, so its pre-fire cap is lower.
    if (args.containsKey("timeoutMs") && args.timeoutMs == null) {
        throw new IllegalArgumentException("timeoutMs must not be null (omit the arg to use default 5000ms)")
    }
    def timeoutMs = (args.timeoutMs != null) ? args.timeoutMs : 5000
    if (!(timeoutMs instanceof Number)) {
        throw new IllegalArgumentException("timeoutMs must be an integer (got: ${timeoutMs})")
    }
    timeoutMs = timeoutMs as Integer
    if (timeoutMs < 100 || timeoutMs > 60000) {
        throw new IllegalArgumentException("timeoutMs must be between 100 and 60000 (got: ${timeoutMs})")
    }

    // 5. Validate pollIntervalMs, clamp to timeoutMs if larger.
    // Range [50,5000]: _buildWaitForPollArgs uses the SAME bounds on its pre-fire path
    // (unlike timeoutMs, the pollIntervalMs range is intentionally identical) -- keep aligned.
    if (args.containsKey("pollIntervalMs") && args.pollIntervalMs == null) {
        throw new IllegalArgumentException("pollIntervalMs must not be null (omit the arg to use default 200ms)")
    }
    def pollIntervalMs = (args.pollIntervalMs != null) ? args.pollIntervalMs : 200
    if (!(pollIntervalMs instanceof Number)) {
        throw new IllegalArgumentException("pollIntervalMs must be an integer (got: ${pollIntervalMs})")
    }
    pollIntervalMs = pollIntervalMs as Integer
    if (pollIntervalMs < 50 || pollIntervalMs > 5000) {
        throw new IllegalArgumentException("pollIntervalMs must be between 50 and 5000 (got: ${pollIntervalMs})")
    }
    // Clamp poll interval so at least one poll is possible within the timeout
    if (pollIntervalMs > timeoutMs) {
        pollIntervalMs = timeoutMs
    }

    // 5b. Validate comparator (default "eq") and its expectedValue/expectedValues shape.
    //   - eq/ne: string-set semantics (existing expectedValue/expectedValues validation above).
    //   - gt/gte/lt/lte: numeric, single threshold from expectedValue; expectedValues is rejected.
    //   - between: numeric inclusive, two bounds from expectedValues (exactly 2); expectedValue rejected.
    if (args.containsKey("comparator") && args.comparator == null) {
        throw new IllegalArgumentException("comparator must not be null (omit the arg to use the default \"eq\")")
    }
    def validComparators = ["eq", "ne", "gt", "gte", "lt", "lte", "between"]
    def comparator = (args.comparator != null) ? args.comparator : "eq"
    if (!(comparator instanceof String) || !validComparators.contains(comparator)) {
        throw new IllegalArgumentException("comparator must be one of ${validComparators} (got: ${_describeValueForError(args.comparator)})")
    }
    def numericComparators = ["gt", "gte", "lt", "lte"] as Set
    def numericThreshold = null
    def betweenLow = null
    def betweenHigh = null
    if (numericComparators.contains(comparator)) {
        if (hasExpectedValues) {
            throw new IllegalArgumentException("comparator '${comparator}' takes a single numeric threshold via expectedValue, not expectedValues")
        }
        numericThreshold = _parseBigDecimalOrNull(args.expectedValue)
        if (numericThreshold == null) {
            throw new IllegalArgumentException("comparator '${comparator}' requires expectedValue to be a numeric-parseable string (got: ${_describeValueForError(args.expectedValue)})")
        }
    } else if (comparator == "between") {
        if (hasExpectedValue) {
            throw new IllegalArgumentException("comparator 'between' takes two numeric bounds via expectedValues, not expectedValue")
        }
        if (args.expectedValues.size() != 2) {
            throw new IllegalArgumentException("comparator 'between' requires expectedValues to have exactly 2 numeric bounds [low, high] (got ${args.expectedValues.size()})")
        }
        betweenLow  = _parseBigDecimalOrNull(args.expectedValues[0])
        betweenHigh = _parseBigDecimalOrNull(args.expectedValues[1])
        if (betweenLow == null || betweenHigh == null) {
            throw new IllegalArgumentException("comparator 'between' requires both expectedValues bounds to be numeric-parseable strings (got: ${args.expectedValues})")
        }
        if (betweenLow > betweenHigh) {
            throw new IllegalArgumentException("comparator 'between' requires low <= high (got low=${args.expectedValues[0]}, high=${args.expectedValues[1]})")
        }
    }

    // 5c. Validate stableForMs (debounce, default 0): the matched condition must hold
    //   continuously for this many ms before converging. Bounded [0, timeoutMs): a value
    //   >= timeoutMs could never converge, so it is rejected pre-poll rather than silently
    //   guaranteeing a timeout.
    if (args.containsKey("stableForMs") && args.stableForMs == null) {
        throw new IllegalArgumentException("stableForMs must not be null (omit the arg to use default 0)")
    }
    def stableForMs = (args.stableForMs != null) ? args.stableForMs : 0
    if (!(stableForMs instanceof Number)) {
        throw new IllegalArgumentException("stableForMs must be an integer (got: ${_describeValueForError(args.stableForMs)})")
    }
    stableForMs = stableForMs as Integer
    if (stableForMs < 0) {
        throw new IllegalArgumentException("stableForMs must be >= 0 (got: ${stableForMs})")
    }
    if (stableForMs >= timeoutMs) {
        throw new IllegalArgumentException("stableForMs (${stableForMs}) must be less than timeoutMs (${timeoutMs}) -- the condition could never hold long enough to converge")
    }

    // 6. Build the expected-value set for match checking (eq/ne comparators only). Exactly one
    //    of the two is present (enforced above), so this populates from whichever was supplied;
    //    a multi-element expectedValues set is OR (match any member).
    def matchSet = [] as Set
    if (hasExpectedValue)  matchSet << args.expectedValue
    if (hasExpectedValues) matchSet.addAll(args.expectedValues)

    // 6b. Multi-device poll: await the mode predicate (any/all) across every device, the SAME
    //     condition applied to each. The aggregate predicate drives the debounce window and the
    //     converge/timeout return -- the same control flow as the single path, just predicate-
    //     over-devices instead of one value. Returns early; the single-device loop below is the
    //     unchanged deviceId path.
    if (multiDevice) {
        return _pollMultiDevice(args, devices, deviceLabels, deviceIdList, mode, comparator,
                                matchSet, numericThreshold, betweenLow, betweenHigh,
                                timeoutMs, pollIntervalMs, stableForMs)
    }

    // 7. Poll loop.
    //    Two termination guards:
    //      (a) wall-clock: elapsedMs >= timeoutMs  (primary, production path)
    //      (b) poll count: polledCount >= maxPolls  (safety net; also the test path
    //          since now() is fixed in the test harness making elapsedMs always 0)
    //    Both guards produce the same timedOut=true result. maxPolls is the number
    //    of polls that would fit in timeoutMs at the configured pollIntervalMs, plus
    //    one to account for the initial read before the first sleep.
    def maxPolls    = ((timeoutMs / pollIntervalMs) as Integer) + 1
    def startMs     = now()
    def polledCount = 0
    def finalValue  = null
    // Track whether the attribute ever reported a non-null value during the poll window.
    // Null throughout the window means the driver has never reported the attribute,
    // which is a different condition from "reported a wrong value the whole time."
    def everNonNull = false
    // Track whether the attribute's non-null value CHANGED across polls. On a timeout, this
    // distinguishes "value was still moving between reads (likely still settling)" from "value
    // was stable at a non-target (a real mismatch)". Best-effort: only reliable when the timeout
    // spans at least one of the device's reporting jumps -- at a short timeout a stable-but-wrong
    // intermediate reads transitioning:false even though the device is physically still settling.
    def sawChange      = false
    def lastNonNull    = null
    // Track whether any poll under a NUMERIC comparator parsed the attribute to a number.
    // If the attribute reported a value the whole window but it never parsed numeric, the
    // comparator can NEVER match it (e.g. gt 5 on switch="on") -- a distinct timeout cause
    // from "reported numbers but never crossed the threshold." Surfaced as nonNumericAttribute
    // on the timeout return. Only meaningful for the numeric comparators.
    def sawNumeric     = false
    // Latches true if any poll's per-device read threw (e.g. the device was removed mid-poll, so
    // currentStates faults). A read fault degrades that tick to an unread (null) value rather than
    // aborting the whole poll, and surfaces as readError on the return so the caller knows the
    // value is unreliable -- it is not silently indistinguishable from a never-reported attribute.
    def sawReadError   = false
    // Debounce tracking: the poll-clock time the match condition first became true in the
    // current contiguous run. Reset to null on any poll where the condition is false, so a
    // value that flaps out of range restarts the stability window. null while the condition is
    // not currently held; once held it is set even when stableForMs==0 (it is set, then the
    // >= stableForMs check is immediately satisfied on that same poll, so the engine converges).
    def conditionTrueSince = null

    while (true) {
        // Each tick reads fresh native state; the preflight response supplies the first sample.
        try {
            finalValue = _readPollValue(devices[0], deviceIdList[0], args.attribute)
        } catch (Exception e) {
            // A per-device read fault (e.g. the device was removed after up-front resolution)
            // must degrade this tick to an unread value, not abort the poll. Treat as null so
            // this poll does not match; latch readError for the return.
            finalValue = null
            if (!sawReadError) mcpLog("warn", "device", "poll_until_attribute read failed for '${deviceLabel}' on poll ${polledCount + 1}: ${e.message ?: e.toString()}")
            sawReadError = true
        }
        polledCount++
        if (finalValue != null) {
            everNonNull = true
            // Compare string representations to detect movement across native numeric/string values.
            def cur = finalValue.toString()
            if (lastNonNull != null && cur != lastNonNull) sawChange = true
            lastNonNull = cur
        }
        def elapsedMs = (now() - startMs) as Integer

        // Evaluate the match condition for this poll under the active comparator (shared with
        // the multi-device path so the two can never diverge). _evalAttrCondition returns
        // [matched, numeric]: numeric latches sawNumeric for the nonNumericAttribute timeout
        // signal under a numeric comparator. See _evalAttrCondition for the per-comparator rules.
        def evalResult = _evalAttrCondition(finalValue, comparator, matchSet, numericThreshold, betweenLow, betweenHigh)
        def condition = evalResult[0]
        if (evalResult[1]) sawNumeric = true   // evalResult[1] = parsed-numeric flag

        // Debounce: converge only once the condition has held continuously for stableForMs.
        // With stableForMs==0 this returns on the first poll the condition holds (today's
        // behavior). conditionTrueSince anchors the start of the current contiguous run; any
        // poll where the condition is false clears it so a flapping value restarts the window.
        if (condition) {
            if (conditionTrueSince == null) conditionTrueSince = now()
            if ((now() - conditionTrueSince) >= stableForMs) {
                def okResponse = [
                    success     : true,
                    finalValue  : finalValue,
                    elapsedMs   : elapsedMs,
                    polledCount : polledCount,
                    timedOut    : false
                ]
                // A read fault earlier in the window did not prevent convergence, but the value
                // was unreliable at least once -- surface it so the caller knows.
                if (sawReadError) okResponse.readError = true
                return okResponse
            }
        } else {
            conditionTrueSince = null
        }

        if (elapsedMs >= timeoutMs || polledCount >= maxPolls) {
            def response = [
                success     : false,
                finalValue  : finalValue,
                elapsedMs   : elapsedMs,
                polledCount : polledCount,
                timedOut    : true,
                // TIMEOUT-only honest signal: true if the value was still moving across polls
                // (>=2 distinct non-null values seen), so the caller can tell a slow-reporting
                // device that is likely still settling from a stable real mismatch. Best-effort
                // (see sawChange declaration). Deliberately omitted from the success path.
                transitioning : sawChange
            ]
            // A per-device read threw at least once during the window (e.g. the device was
            // removed mid-poll). The value is unreliable; surface it distinctly from a
            // never-reported attribute.
            if (sawReadError) response.readError = true
            // Attribute exists in supportedAttributes but never reported a value during
            // the entire poll window -- driver has not yet emitted a reading.
            if (!everNonNull) response.neverReported = true
            // Numeric comparator on an attribute that DID report but never parsed numeric:
            // the comparator can never match it (e.g. gt 5 on switch="on"). Distinct from
            // neverReported (a numeric comparator on a never-reported attribute is neverReported,
            // not this) -- the everNonNull && !sawNumeric pair separates the two causes.
            if (comparator != "eq" && comparator != "ne" && everNonNull && !sawNumeric) {
                response.nonNumericAttribute = true
                response.note = "comparator '${comparator}' can never match attribute '${args.attribute}' -- it reported a non-numeric value the whole window (use eq/ne for a string attribute)".toString()
                // "can never match" supersedes "still settling": a flapping non-numeric value
                // would set transitioning=true (sawChange), which reads as "retry/wait" and
                // contradicts nonNumericAttribute. Force it false so the signals agree.
                response.transitioning = false
            }
            return response
        }

        // Sleep for the poll interval (or the remaining time, whichever is less)
        def remaining = timeoutMs - elapsedMs
        def sleepMs   = Math.min(pollIntervalMs, remaining > 0 ? remaining : pollIntervalMs) as Integer
        try {
            if (sleepMs > 0) pauseExecution(sleepMs)
        } catch (InterruptedException e) {
            // pauseExecution wraps Thread.sleep() and throws InterruptedException
            // when the hub is restarting or the app is being reloaded.
            elapsedMs = (now() - startMs) as Integer
            mcpLog("warn", "device", "poll_until_attribute interrupted after ${polledCount} poll(s) (elapsed=${elapsedMs}ms): ${e.message}")
            def intResponse = [
                success     : false,
                interrupted : true,
                finalValue  : finalValue,
                elapsedMs   : elapsedMs,
                polledCount : polledCount
            ]
            if (sawReadError) intResponse.readError = true
            return intResponse
        }
    }
}

// Consume the first native response once, then fetch a fresh current-state sample per tick.
private _readPollValue(dev, deviceId, attribute) {
    // Retained SDK read for deliberate rollback:
    // return (dev != null) ? dev.currentStates?.find { it.name == attribute }?.value : _readBypassAttrValue(deviceId, attribute)
    def full = dev instanceof Map && dev.containsKey('initial') ? dev.remove('initial') : _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map) || !(full.device.currentStates instanceof Map)) {
        throw new RuntimeException("Native current states unavailable for device ${deviceId}")
    }
    return _readBypassAttrValueFrom(full, attribute)
}

// Multi-device poll: await the mode predicate (any/all) across devices, the SAME condition
// applied to each. Mirrors the single-device loop's control flow -- aggregate predicate drives
// the stableForMs debounce window and the converge/timeout return -- but reports a compact
// per-device result array (never full device objects). everNonNull / sawNumeric / sawChange are
// tracked PER DEVICE so the timeout result can name which devices never reported / are non-numeric
// / are still transitioning. Called only from toolPollUntilAttribute after validation; all args
// are pre-validated there.
def _pollMultiDevice(args, devices, deviceLabels, deviceIdList, mode, comparator,
                     matchSet, numericThreshold, betweenLow, betweenHigh,
                     timeoutMs, pollIntervalMs, stableForMs) {
    // Defense-in-depth for this private helper: the caller already validates these, so this is
    // a no-op for valid input -- it just guards against a 0/null reaching the maxPolls divide.
    // Defaults match the single-device path.
    timeoutMs      = (timeoutMs && timeoutMs > 0) ? timeoutMs : 5000
    pollIntervalMs = (pollIntervalMs && pollIntervalMs > 0) ? pollIntervalMs : 200
    stableForMs    = (stableForMs && stableForMs > 0) ? stableForMs : 0
    def n           = devices.size()
    def maxPolls    = ((timeoutMs / pollIntervalMs) as Integer) + 1
    def startMs     = now()
    def polledCount = 0
    // Per-device honesty tracking, index-aligned with devices/deviceIdList/deviceLabels.
    def everNonNull = (0..<n).collect { false }
    def sawNumeric  = (0..<n).collect { false }
    def sawChange   = (0..<n).collect { false }
    def lastNonNull = (0..<n).collect { null }
    def finalValue  = (0..<n).collect { null }
    def matched     = (0..<n).collect { false }
    // Latches per device if its read threw on any poll (e.g. removed mid-poll). A read fault on
    // one device degrades only THAT device's tick to an unread value -- the poll continues and
    // every other device's converged/honest state is still returned, never discarded by a throw.
    def sawReadError = (0..<n).collect { false }
    // Aggregate-debounce anchor: the whole any/all predicate must hold continuously for stableForMs.
    def conditionTrueSince = null

    while (true) {
        // Read each device through the same fresh native sampling path as the single-device poll.
        for (int i = 0; i < n; i++) {
            def v
            try {
                v = _readPollValue(devices[i], deviceIdList[i], args.attribute)
            } catch (Exception e) {
                // Degrade only this device's tick to an unread (null) value; the loop continues
                // so the other devices' honest state is preserved. Latch readError for this device.
                v = null
                if (!sawReadError[i]) mcpLog("warn", "device", "poll_until_attribute (multi-device) read failed for '${deviceLabels[i]}' on poll ${polledCount + 1}: ${e.message ?: e.toString()}")
                sawReadError[i] = true
            }
            finalValue[i] = v
            if (v != null) {
                everNonNull[i] = true
                def cur = v.toString()
                if (lastNonNull[i] != null && cur != lastNonNull[i]) sawChange[i] = true
                lastNonNull[i] = cur
            }
            def evalResult = _evalAttrCondition(v, comparator, matchSet, numericThreshold, betweenLow, betweenHigh)
            if (evalResult[1]) sawNumeric[i] = true   // evalResult[1] = parsed-numeric flag
            matched[i] = evalResult[0]
        }
        polledCount++
        def elapsedMs = (now() - startMs) as Integer

        // mode predicate: any = at least one device matched; all = every device matched.
        def convergedCount = matched.count { it }
        def aggregate = (mode == "any") ? (convergedCount > 0) : (convergedCount == n)

        // Debounce the AGGREGATE predicate: the whole any/all condition must hold continuously
        // for stableForMs before converging. A poll where it is false clears the anchor, so a
        // value that flaps out of the condition on any device restarts the window.
        if (aggregate) {
            if (conditionTrueSince == null) conditionTrueSince = now()
            if ((now() - conditionTrueSince) >= stableForMs) {
                return [
                    success        : true,
                    mode           : mode,
                    devices        : (0..<n).collect { i ->
                        def entry = [
                            deviceId  : deviceIdList[i],
                            device    : deviceLabels[i],
                            finalValue: finalValue[i],
                            matched   : matched[i]
                        ]
                        // A read fault on this device did not block aggregate convergence, but
                        // its value was unreliable at least once -- surface it.
                        if (sawReadError[i]) entry.readError = true
                        entry
                    },
                    convergedCount : convergedCount,
                    elapsedMs      : elapsedMs,
                    polledCount    : polledCount,
                    timedOut       : false
                ]
            }
        } else {
            conditionTrueSince = null
        }

        if (elapsedMs >= timeoutMs || polledCount >= maxPolls) {
            def numericComparator = (comparator != "eq" && comparator != "ne")
            def nonNumericCount = 0
            def anyTransitioning = false
            def deviceResults = (0..<n).collect { i ->
                def entry = [
                    deviceId  : deviceIdList[i],
                    device    : deviceLabels[i],
                    finalValue: finalValue[i],
                    matched   : matched[i]
                ]
                // TIMEOUT-only per-device honesty signals, only where true.
                if (sawReadError[i]) entry.readError = true
                if (!everNonNull[i]) entry.neverReported = true
                // Numeric comparator on an attribute that reported but never parsed numeric:
                // it can never match (distinct from neverReported -- the everNonNull && !sawNumeric
                // pair separates the two), so it supersedes a transitioning signal for that device.
                def deviceNonNumeric = numericComparator && everNonNull[i] && !sawNumeric[i]
                if (deviceNonNumeric) {
                    entry.nonNumericAttribute = true
                    nonNumericCount++
                } else if (sawChange[i]) {
                    anyTransitioning = true
                }
                entry
            }
            def response = [
                success        : false,
                mode           : mode,
                devices        : deviceResults,
                convergedCount : convergedCount,
                elapsedMs      : elapsedMs,
                polledCount    : polledCount,
                timedOut       : true,
                // Aggregate still-settling signal: at least one device was still moving (and is
                // not a can-never-match non-numeric). false when no device changed OR every change
                // was a non-numeric flap (which reads as can-never-match, not retry/wait).
                transitioning  : anyTransitioning
            ]
            // Aggregate guidance present only when >=1 device is can-never-match non-numeric.
            // Count-aware (the signal fires for >=1 device), no redundant .toString() (the
            // single-device note doesn't have one -- the GString coerces on map insertion).
            if (nonNumericCount > 0) {
                response.note = "comparator '${comparator}' can never match attribute '${args.attribute}' on ${nonNumericCount} device(s) that reported a non-numeric value the whole window (use eq/ne for a string attribute)"
            }
            return response
        }

        def remaining = timeoutMs - elapsedMs
        def sleepMs   = Math.min(pollIntervalMs, remaining > 0 ? remaining : pollIntervalMs) as Integer
        try {
            if (sleepMs > 0) pauseExecution(sleepMs)
        } catch (InterruptedException e) {
            elapsedMs = (now() - startMs) as Integer
            mcpLog("warn", "device", "poll_until_attribute (multi-device, ${n}) interrupted after ${polledCount} poll(s) (elapsed=${elapsedMs}ms): ${e.message}")
            return [
                success        : false,
                interrupted    : true,
                mode           : mode,
                devices        : (0..<n).collect { i ->
                    def entry = [
                        deviceId  : deviceIdList[i],
                        device    : deviceLabels[i],
                        finalValue: finalValue[i],
                        matched   : matched[i]
                    ]
                    if (sawReadError[i]) entry.readError = true
                    entry
                },
                convergedCount : matched.count { it },
                elapsedMs      : elapsedMs,
                polledCount    : polledCount
            ]
        }
    }
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
    if (!args.deviceId) {
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

        def locResults = []
        def timeFilterUnparseable = 0
        for (evt in rows) {
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
            locResults << [
                name: evt.name,
                value: evt.value,
                unit: evt.unit,
                description: evt.descriptionText,
                date: evt.date,
                type: evt.type,
                isStateChange: evt.isStateChange
            ]
            if (locResults.size() >= limit) break
        }

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
        // Mirror the hub-log path: surface rows that escaped the always-active
        // time window because their date would not parse.
        if (timeFilterUnparseable > 0) locResult.timeFilterUnparseable = timeFilterUnparseable
        return locResult
    }

    _requireDeviceToolAccess(args.deviceId)
    def full = _fetchDeviceFullJson(args.deviceId)
    if (!(full?.device instanceof Map)) return [success: false, isError: true, error: "Device metadata fetch failed (/device/fullJson/${args.deviceId})", note: 'Check native device details and retry.']
    return _deviceHistoryBypass(args, full, sinceDate, sinceMode, effectiveHoursBack, sinceEcho, attributeFilter, limit)

    // Retained SDK history implementation for deliberate rollback.
    //     def device = findDevice(args.deviceId)
    //     if (!device) {
    //         // Allowlist bypass: read an unlisted device's windowed history from /device/eventsJson with
    //         // the SAME client-side attribute/strictly-after/limit filtering the app + location branches
    //         // use (no Groovy eventsSince available). The appId and location branches above are not
    //         // device-allowlist-gated, so only this device branch gains the fallback.
    //         if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(args.deviceId)
    //             if (fj?.device != null) {
    //                 return _deviceHistoryBypass(args, fj, sinceDate, sinceMode, effectiveHoursBack, sinceEcho, attributeFilter, limit)
    //             }
    //         }
    //         throw new IllegalArgumentException("Device not found: ${args.deviceId}. Device must be selected in MCP Rule Server app settings.")
    //     }
    //
    //     def deviceLabel = device.label ?: device.name ?: "Device ${args.deviceId}"
    //
    //     def events
    //     try {
    //         events = device.eventsSince(sinceDate, [max: limit])
    //     } catch (Exception e) {
    //         mcpLogError("monitoring", "eventsSince failed for ${deviceLabel}", e)
    //         return [success: false, error: "eventsSince not supported or failed: ${e.message}", device: deviceLabel, deviceId: args.deviceId,
    //                 note: "Retry; if persistent, drop hoursBack/attribute to read the most-recent events instead, or check the device's Events page in the hub UI."]
    //     }
    //
    //     // eventsSince inclusivity at the boundary is undocumented, so post-filter to
    //     // strictly-after sinceDate for parity with the app/location branches -- an event
    //     // whose timestamp equals `since` must not replay when a returned `date` is fed
    //     // back as the bookmark. A row with no usable date is kept (don't silently drop),
    //     // matching the other branches' parse-fail tolerance.
    //     def results = (events ?: []).findAll { evt ->
    //         evt.date == null || evt.date.after(sinceDate)
    //     }.collect { evt ->
    //         [
    //             name: evt.name,
    //             value: evt.value,
    //             unit: evt.unit,
    //             description: evt.descriptionText,
    //             date: evt.date?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
    //             isStateChange: evt.isStateChange
    //         ]
    //     }
    //
    //     if (attributeFilter) {
    //         results = results.findAll { it.name == attributeFilter }
    //     }
    //
    //     mcpLog("info", "monitoring", "Retrieved ${results.size()} history event${results.size() == 1 ? '' : 's'} for ${deviceLabel} (${sinceMode} window since ${sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")})")
    //     def deviceResult = [
    //         source: "device",
    //         device: deviceLabel,
    //         deviceId: args.deviceId,
    //         attributeFilter: attributeFilter,
    //         events: results,
    //         count: results.size(),
    //         sinceMode: sinceMode,
    //         sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    //     ]
    //     if (sinceMode == "relative") deviceResult.hoursBack = effectiveHoursBack
    //     else deviceResult.since = sinceEcho
    //     return deviceResult
}

private Map _deviceHistoryBypass(args, Map fj, sinceDate, sinceMode, effectiveHoursBack, sinceEcho, attributeFilter, limit) {
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
    if (timeFilterUnparseable > 0) deviceResult.timeFilterUnparseable = timeFilterUnparseable
    return deviceResult
}

// /device/preference/save expects a numeric deviceId in its JSON body (the Vue posts
// this.device.id, a number). Coerce the string id to a Long; leave a non-numeric id as-is.
def _prefSaveDeviceId(deviceId) {
    try { return (deviceId != null) ? (deviceId.toString() as Long) : deviceId }
    catch (Exception ignored) { return deviceId }
}

private Map _devicePreferencePanePayload(deviceId, Map overrides = [:], List preferenceRows = []) {
    def fresh = _fetchDeviceFullJson(deviceId)
    def d = fresh?.device
    def controls = d instanceof Map ? d : [:]
    def showOnHome = _normalizeDevicePreferenceValue(controls.get('showOnHome'), 'bool')
    def retryEnabled = _normalizeDevicePreferenceValue(controls.get('retryEnabled'), 'bool')
    if (!(d instanceof Map) || !showOnHome.valid || !(showOnHome.value instanceof Boolean) ||
        !retryEnabled.valid || !(retryEnabled.value instanceof Boolean) || !d.containsKey("defaultCurrentState") ||
        !(d.get("defaultCurrentState") == null || d.get("defaultCurrentState") instanceof String)) {
        throw new RuntimeException("Unable to read complete preference-pane controls before saving; no preference update sent")
    }
    def payload = [deviceId: _prefSaveDeviceId(deviceId),
        defaultCurrentState: d.get("defaultCurrentState") == null ? "" : d.get("defaultCurrentState").toString(),
        commandRetry: retryEnabled.value, showOnHome: showOnHome.value,
        preferences: preferenceRows]
    overrides.each { key, value -> payload.put(key, value) }
    return payload
}

private List _deviceExtendedFormProperties() {
    return ["deviceTypeId", "zigbeeId", "notes", "maxEvents", "maxStates", "spammyThreshold",
        "defaultIcon", "dashboardIds", "meshEnabled", "retryEnabled", "meshFullSync"]
}

private Map _deviceAssistantProperties() {
    return [homeKitEnabled: "homeKit", amazonAlexaEnabled: "amazonAlexa", googleHomeEnabled: "googleHome"]
}

private boolean _deviceFlag(value) {
    return value == true || value?.toString() == "true"
}

private _normalizedDevicePreferenceValue(Map entry, value) {
    if (value == null || (value instanceof String && !value.trim()) || (value instanceof List && value.isEmpty())) {
        throw new IllegalArgumentException("Preference '${entry.name}' cannot use a blank value; omit it to preserve the setting or use {clear:true} to remove an optional setting")
    }
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
            bounds.each { bound ->
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
        def options = entry.options
        def allowed = []
        if (options instanceof Map) allowed = options.keySet().collect { it.toString() }
        else if (options instanceof List) {
            options.each { option ->
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

// Complete caller validation precedes every setter, including mixed old/new patches.
private Map _prepareDeviceUpdatePatch(Map original, deviceId, Map suppliedFull = null) {
    def args = new LinkedHashMap(original)
    def allowed = ["deviceId", "bestPracticeKey", "label", "name", "deviceNetworkId", "room", "enabled", "dataValues", "preferences", "showOnHome", "defaultCurrentState", "tags", "confirm"] + _deviceExtendedFormProperties() + _deviceAssistantProperties().keySet().toList()
    if (args.keySet().any { !allowed.contains(it.toString()) }) throw new IllegalArgumentException("Unknown device update property; read hub_get_device(mode='configuration') for supported fields")
    def stringFields = ["label", "name", "deviceNetworkId", "room", "defaultCurrentState", "zigbeeId", "notes", "defaultIcon"]
    stringFields.each { field ->
        if (args.containsKey(field) && !(args.get(field) instanceof String)) throw new IllegalArgumentException("${field} must be a string")
    }
    def booleanFields = ["enabled", "showOnHome", "meshEnabled", "retryEnabled", "meshFullSync", "confirm"] + _deviceAssistantProperties().keySet().toList()
    booleanFields.each { field ->
        if (args.containsKey(field) && !(args.get(field) instanceof Boolean)) throw new IllegalArgumentException("${field} must be a boolean")
    }
    [maxEvents: [1, 2000], maxStates: [1, 2000], spammyThreshold: [100, 2000], deviceTypeId: [1, 2147483647]].each { field, bounds ->
        if (args.containsKey(field)) {
            def value = args.get(field)
            if (!(value instanceof Number) || value < bounds[0] || value > bounds[1] || new BigDecimal(value.toString()).stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("${field} must be an integer from ${bounds[0]} to ${bounds[1]}")
            }
        }
    }
    if (args.containsKey("tags") && (!(args.tags instanceof List) || args.tags.any { !(it instanceof String) || it.contains(",") })) {
        throw new IllegalArgumentException("tags must be an array of strings without commas")
    }
    if (args.containsKey("dashboardIds") && (!(args.dashboardIds instanceof List) || args.dashboardIds.any { !(it instanceof Number) || it < 1 || new BigDecimal(it.toString()).stripTrailingZeros().scale() > 0 })) {
        throw new IllegalArgumentException("dashboardIds must be an array of positive integer IDs")
    }
    if (args.containsKey("dataValues") && (!(args.dataValues instanceof Map) || args.dataValues.any { k, v -> !(k instanceof String) || !(v instanceof String) })) {
        throw new IllegalArgumentException("dataValues must be an object containing string values")
    }
    if (args.containsKey("preferences") && !(args.preferences instanceof Map)) throw new IllegalArgumentException("preferences must be an object keyed by declared preference name")
    if (args.name != null && !args.name.trim()) throw new IllegalArgumentException("name cannot be empty")
    if (args.deviceNetworkId != null && !args.deviceNetworkId.trim()) throw new IllegalArgumentException("deviceNetworkId cannot be empty")

    def sensitive = ["deviceTypeId", "deviceNetworkId", "zigbeeId", "meshEnabled", "meshFullSync", "dashboardIds"] + _deviceAssistantProperties().keySet().toList()
    if (sensitive.any { args.containsKey(it) }) requireDestructiveConfirm(args.confirm)
    def nativeFields = _deviceExtendedFormProperties() + _deviceAssistantProperties().keySet().toList() + ["deviceNetworkId"]
    def needModel = args.preferences || nativeFields.any { args.containsKey(it) }
    if (settings.enableWrite == false && (_deviceExtendedFormProperties() + _deviceAssistantProperties().keySet().toList()).any { args.containsKey(it) }) {
        throw new IllegalArgumentException("Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings")
    }
    def full = suppliedFull
    def inspectModel = needModel || ['name', 'label', 'defaultCurrentState', 'showOnHome', 'tags', 'dataValues'].any { args.containsKey(it) }
    if (full == null && inspectModel && settings.enableWrite != false) full = _fetchDeviceFullJson(deviceId)
    if (needModel && !(full?.device instanceof Map)) throw new IllegalArgumentException("Unable to read device configuration before updating; use hub_get_device(mode='configuration') and retry")
    def d = full?.device
    if (args.defaultCurrentState && settings.enableWrite != false) {
        if (!(d?.currentStates instanceof Map)) throw new IllegalArgumentException("Unable to read authoritative current-state attributes before updating defaultCurrentState; refresh device configuration first")
        if (!d.currentStates.containsKey(args.defaultCurrentState)) throw new IllegalArgumentException("defaultCurrentState must name one of the device's current-state attributes")
    }
    if (d instanceof Map) {
        if (_deviceFlag(d.linkedAndDisabled) && args.containsKey("label")) throw new IllegalArgumentException("label cannot be edited on a disabled linked device")
        def componentIdentityFields = ["name", "deviceTypeId", "zigbeeId"]
        if (!_deviceFlag(d.linkedDevice)) componentIdentityFields << "deviceNetworkId"
        if (_deviceFlag(d.isComponent) && componentIdentityFields.any { args.containsKey(it) }) throw new IllegalArgumentException("This component device cannot have its identity or driver edited independently from its parent")
        if (_deviceFlag(d.linkedDevice) && ["name", "deviceTypeId", "zigbeeId"].any { args.containsKey(it) }) throw new IllegalArgumentException("A linked device cannot have its source name, Zigbee ID or driver edited locally")
        if (args.containsKey("zigbeeId") && !d.zigbeeId) throw new IllegalArgumentException("zigbeeId is not editable on a device without a Zigbee ID")
        if (args.containsKey("meshEnabled") && !_deviceFlag(d.meshSelectionEnabled)) throw new IllegalArgumentException("meshEnabled is unavailable: Hub Mesh selection is disabled for this device")
        if (args.containsKey("meshFullSync") && !(_deviceFlag(d.linkedDevice) && _deviceFlag(full.hubMeshRefreshEnabled))) throw new IllegalArgumentException("meshFullSync requires a linked device and Hub Mesh refresh enabled")
        if (args.containsKey("retryEnabled") && !(_deviceFlag(full.commandRetrySelectionEnabled) || _deviceFlag(d.retryAvailable))) throw new IllegalArgumentException("retryEnabled is unavailable for this device; enable native command retry support first")
        if (args.containsKey("dashboardIds")) {
            if (!_deviceFlag(full.hasDashboards) || !(full.dashboards instanceof List)) throw new IllegalArgumentException("dashboardIds is unavailable: no native dashboard assignments are exposed")
            def known = full.dashboards.collect { it.id?.toString() }
            if (args.dashboardIds.any { !known.contains(it.toString()) }) throw new IllegalArgumentException("dashboardIds contains an unknown dashboard ID; read configuration options first")
        }
        def assistantAvailable = [homeKitEnabled: _deviceFlag(full.homeKitSelectionEnabled),
            amazonAlexaEnabled: _deviceFlag(full.amazonAlexaInstalled) && _deviceFlag(full.amazonAlexaSupported),
            googleHomeEnabled: _deviceFlag(full.googleHomeInstalled) && _deviceFlag(full.googleHomeSupported)]
        _deviceAssistantProperties().each { property, integration ->
            if (args.containsKey(property) && !assistantAvailable.get(property)) throw new IllegalArgumentException("${property} is unavailable: install/enable the native ${integration} integration and verify this device is supported")
        }
        if (_deviceAssistantProperties().keySet().any { args.containsKey(it) } && !_deviceAssistantProperties().keySet().every { full.containsKey(it) && full.get(it) instanceof Boolean }) {
            throw new IllegalArgumentException("Unable to read all current assistant assignments before updating; refresh device configuration first")
        }
        if (args.containsKey("deviceTypeId")) {
            def driversText = hubInternalGet("/device/drivers")
            def drivers = driversText ? new groovy.json.JsonSlurper().parseText(driversText)?.drivers : null
            if (!(drivers instanceof List)) throw new IllegalArgumentException("Unable to discover driver IDs from /device/drivers")
            def driver = drivers.find { it.id?.toString() == args.deviceTypeId.toString() && ((it.type != "dep" && it.category != "Hidden") || it.id?.toString() == d.deviceTypeId?.toString()) }
            if (!driver) throw new IllegalArgumentException("deviceTypeId is not an available driver ID; read configuration driver options first")
        }
        if (args.containsKey("deviceNetworkId") && _deviceFlag(d.linkedDevice)) {
            if (args.deviceNetworkId == "0") args.remove("deviceNetworkId")
            else {
                def targetsText = hubInternalGet("/device/accessibleLinkedDevices")
                def targets = targetsText ? new groovy.json.JsonSlurper().parseText(targetsText)?.devices : null
                if (!(targets instanceof List) || !targets.any { !_deviceFlag(it.linkedLocally) && "${it.hubId}-${it.deviceId}" == args.deviceNetworkId }) {
                    throw new IllegalArgumentException("deviceNetworkId is not an available linked-device target; read /device/accessibleLinkedDevices options first")
                }
            }
        }
    }
    if (args.preferences) {
        if (args.containsKey("deviceTypeId") && args.deviceTypeId?.toString() != d.deviceTypeId?.toString()) throw new IllegalArgumentException("Change the driver first, then read its new preference definitions before updating preferences")
        if (_deviceFlag(d?.linkedDevice)) throw new IllegalArgumentException("Driver preferences cannot be saved on a linked device; edit the source device")
        def model = _readDevicePreferenceModel(full)
        if (model.writeSafe != true) throw new IllegalArgumentException("Unable to read complete preference definitions/storage before updating; inspect hub_get_device(mode='configuration')")
        def normalized = [:]
        args.preferences.each { key, setting ->
            def name = key.toString()
            def entry = _lookupDevicePreference(model, name)
            if (!entry) throw new IllegalArgumentException("Unknown preference '${name}'; read hub_get_device(mode='configuration') for declared names")
            if (entry.type in ["hidden", "paragraph"]) throw new IllegalArgumentException("Preference '${name}' is not an editable input")
            def type = entry.type in ['bool', 'boolean'] ? 'bool' : entry.type
            boolean clear = setting instanceof Map && setting.get('clear') == true
            if (setting instanceof Map) {
                def suppliedType = setting.type in ['bool', 'boolean'] ? 'bool' : setting.type
                if ((setting.containsKey('clear') && (!clear || setting.containsKey('value'))) ||
                    (!clear && !setting.containsKey('value')) ||
                    (suppliedType != null && suppliedType.toString() != type) ||
                    setting.keySet().any { !(it in ['type', 'value', 'clear', 'multiple']) }) {
                    throw new IllegalArgumentException("Preference '${name}' requires its declared type and either a nonblank value or {clear:true}; clear and value cannot be combined")
                }
            }
            if (setting instanceof Map && setting.containsKey('multiple')) {
                if (clear || type != 'enum' || !(setting.multiple instanceof Boolean)) {
                    throw new IllegalArgumentException("Preference '${name}' accepts a multiple boolean only with an enum value")
                }
                if (entry.multiple != null && setting.multiple != entry.multiple) {
                    throw new IllegalArgumentException("Preference '${name}' multiple conflicts with its current native metadata")
                }
                entry = entry + [multiple: setting.multiple]
            }
            if (!clear && type == 'enum' && entry.multiple == null) {
                throw new IllegalArgumentException("Preference '${name}' has unavailable selection cardinality; check driverSource or previously read metadata and supply multiple:true or multiple:false with the value")
            }
            if (clear && _deviceFlag(entry.required)) throw new IllegalArgumentException("Required preference '${name}' cannot be cleared")
            def raw = setting instanceof Map ? setting.value : setting
            def value = clear ? null : _normalizedDevicePreferenceValue(entry, raw)
            normalized.put(name, clear ? [type: type, clear: true, value: null] : [type: type, value: value])
        }
        args.preferences = normalized
    }
    if (args.room != null && !(args.room in ["", "none", "null"])) _assertRoomExistsForBypass(args.room)
    return [args: args, fullJson: full]
}

private void _verifyDevicePreferenceWrites(deviceId, Map preferences, List changes, List errors) {
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) {
        preferences.each { name, setting ->
            errors << [property: "preference.${name}", stage: "verify", status: "unavailable", error: "Update accepted but could not confirm the preference -- the read-back fetch failed."]
        }
        return
    }
    def model = _readDevicePreferenceModel(full)
    preferences.each { name, setting ->
        _verifyDevicePreferenceWrite(deviceId, name.toString(), setting, changes, errors, model)
    }
}

private void _verifyDevicePreferenceWrite(deviceId, String name, Map setting, List changes, List errors, Map model = null) {
    if (model == null) {
        _verifyDevicePreferenceWrites(deviceId, [(name): setting], changes, errors)
        return
    }
    def entry = _lookupDevicePreference(model, name)
    if (model.writeSafe != true || entry == null || entry.valueStatus in ["unknown", "invalid"]) {
        errors << [property: "preference.${name}", stage: "verify", status: entry?.valueStatus == "invalid" ? "invalid" : "unavailable", error: "Update accepted but could not confirm the preference -- saved-value storage is unavailable or invalid."]
        return
    }
    def expected = setting.value
    def actual = entry.value
    boolean matches = setting.clear == true ? (!entry.valuePresent && entry.valueStatus == 'unset') :
        (entry.valuePresent && entry.valueStatus == 'stored' && actual == expected)
    if (matches) {
        changes << [property: "preference.${name}", newValue: _devicePreferenceIsSecret(entry) ? [type: entry.type, value: "[REDACTED]"] : setting]
    } else {
        errors << [property: "preference.${name}", stage: "verify", status: "mismatch", error: "Update accepted but the preference read back as a different saved value. Inspect configuration and retry; no driver command was invoked."]
    }
}

private void _applyNativeDeviceDataValues(deviceId, Map values, List changes, List errors) {
    values.each { key, value ->
        String stage = 'write'
        try {
            def payload = [id: _prefSaveDeviceId(deviceId), method: 'updateDataValue',
                           args: [[type: 'STRING', value: key], [type: 'STRING', value: value]]]
            def result = hubInternalPostJson('/device/runmethod', groovy.json.JsonOutput.toJson(payload))
            if (!(result instanceof Map) || result.success != true) {
                errors << [property: "dataValue.${key}", stage: stage, error: 'Native data-value update was not accepted; inspect device data before retrying.']
                return
            }
            stage = 'verify'
            def readback = _fetchDeviceFullJson(deviceId)
            def data = readback?.device?.data
            if (data instanceof Map && data.containsKey(key) && data.get(key)?.toString() == value) {
                changes << [property: "dataValue.${key}", newValue: value]
            } else {
                errors << [property: "dataValue.${key}", stage: stage, error: 'Native update accepted but the data value could not be confirmed; inspect device data before retrying.']
            }
        } catch (Exception ignored) {
            errors << [property: "dataValue.${key}", stage: stage, error: 'Native data-value update or verification failed; inspect device data before retrying.']
        }
    }
}

// The device argument is retained for the SDK rollback body below.
private void _applyDevicePreferencePatch(deviceId, device, Map preferences, List changes, List errors) {
    boolean saveAccepted = false
    def nativeSettings = new LinkedHashMap(preferences)
    // Retained SDK preference setter for deliberate rollback.
    //     def accepted = [:]
    //     def nativeSettings = [:]
    //     preferences.each { name, setting ->
    //         if (device == null || setting.clear == true) {
    //             nativeSettings.put(name, setting)
    //         } else {
    //             try {
    //                 device.updateSetting(name.toString(), [type: setting.type, value: setting.value])
    //                 accepted.put(name, setting)
    //             } catch (Exception ignored) {
    //                 errors << [property: "preference.${name}", stage: 'write', status: 'failed',
    //                     error: 'Preference update or verification failed; inspect the device configuration before retrying.']
    //             }
    //         }
    //     }
    if (nativeSettings) {
        def stage = 'prepare'
        try {
            // Validation already limits enum Lists to multiple selections. Their native wire
            // representation is a JSON string; a raw array can collapse to scalar storage.
            def rows = nativeSettings.collect { name, setting ->
                def value = setting.clear == true ? '' :
                    (setting.type == 'enum' && setting.value instanceof List ? groovy.json.JsonOutput.toJson(setting.value) : setting.value)
                [name: name.toString(), type: setting.type, value: value]
            }
            def payload = _devicePreferencePanePayload(deviceId, [:], rows)
            stage = 'write'
            def response = hubInternalPostJson('/device/preference/save', groovy.json.JsonOutput.toJson(payload))
            if (response instanceof Map && (response.success == false || response._unparseable == true)) {
                nativeSettings.each { name, setting ->
                    errors << [property: "preference.${name}", stage: 'write',
                        status: response._unparseable == true ? 'unavailable' : 'failed',
                        error: response._unparseable == true ?
                            'Preference save returned an unreadable response; inspect the device configuration before retrying.' :
                            'Native preference save was rejected; inspect the device configuration before retrying.']
                }
            } else {
                saveAccepted = true
            }
        } catch (Exception ignored) {
            nativeSettings.each { name, setting ->
                errors << [property: "preference.${name}", stage: stage, status: stage == 'write' ? 'failed' : 'unavailable',
                    error: 'Preference update or verification failed; inspect the device configuration before retrying.']
            }
        }
    }
    // Retained SDK accepted-subset verification for deliberate rollback.
    // if (accepted) {
    //     def ordered = preferences.findAll { name, setting -> accepted.containsKey(name) }
    //     try {
    //         _verifyDevicePreferenceWrites(deviceId, ordered, changes, errors)
    //     } catch (Exception ignored) {
    //         ordered.each { name, setting ->
    //             errors << [property: "preference.${name}", stage: 'verify', status: 'unavailable',
    //                 error: 'Preference update or verification failed; inspect the device configuration before retrying.']
    //         }
    //     }
    // }
    if (saveAccepted) {
        try {
            _verifyDevicePreferenceWrites(deviceId, nativeSettings, changes, errors)
        } catch (Exception ignored) {
            nativeSettings.each { name, setting ->
                errors << [property: "preference.${name}", stage: 'verify', status: 'unavailable',
                    error: 'Preference update or verification failed; inspect the device configuration before retrying.']
            }
        }
    }
}

private void _saveDevicePreferencePaneControls(deviceId, Map overrides) {
    def payload = _devicePreferencePanePayload(deviceId, overrides)
    def response = hubInternalPostJson('/device/preference/save', groovy.json.JsonOutput.toJson(payload))
    if (response instanceof Map && (response.success == false || response._unparseable == true)) {
        throw new RuntimeException(response._unparseable == true ?
            'Native preference-pane save returned an unreadable response; inspect configuration before retrying.' :
            'Native preference-pane save was rejected; inspect configuration before retrying.')
    }
}

// Native callers use bypass=true; the retained SDK rollback caller requires the false branch.
private void _applyExtendedDeviceUpdate(Map args, deviceId, Map full, boolean bypass, List changes, List errors) {
    if (settings.enableWrite == false && (_deviceExtendedFormProperties() + _deviceAssistantProperties().keySet().toList()).any { args.containsKey(it) }) {
        throw new IllegalArgumentException("Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings")
    }
    if (args.containsKey("retryEnabled") && !_deviceFlag(full?.commandRetrySelectionEnabled) && _deviceFlag(full?.device?.retryAvailable)) {
        def wanted = args.remove("retryEnabled")
        try {
            _saveDevicePreferencePaneControls(deviceId, [commandRetry: wanted])
            def readback = _fetchDeviceFullJson(deviceId)?.device
            if (readback?.retryEnabled instanceof Boolean && readback.retryEnabled == wanted) changes << [property: "retryEnabled", newValue: wanted]
            else errors << [property: "retryEnabled", error: "POST accepted but could not confirm retryEnabled; native read-back did not match."]
        } catch (Exception e) { errors << [property: "retryEnabled", error: e.message ?: e.toString()] }
    }
    def formProperties = _deviceExtendedFormProperties().findAll { args.containsKey(it) }
    def linkedIdentity = args.containsKey("deviceNetworkId") && _deviceFlag(full?.device?.linkedDevice)
    if (formProperties || (bypass && args.containsKey("tags")) || linkedIdentity) {
        def overrides = [:]
        (formProperties + ["label", "name", "deviceNetworkId", "tags"]).unique().each { property ->
            if (args.containsKey(property)) overrides.put(property, property == "tags" ? args.tags.collect { it.trim() }.findAll { it }.join(",") : args.get(property))
        }
        def targets = new LinkedHashMap(overrides)
        overrides.keySet().each { args.remove(it) }
        try {
            def updated = _postDeviceConfigurationForm(deviceId, overrides, errors)
            // Retained SDK identity recovery; the native form helper now verifies recovery for every device.
    //             if (!bypass && updated?.device instanceof Map) {
    //                 // The wholesale form can blank identity despite carrying it. Restore only
    //                 // observed blanks, never an intentional clear or an unavailable readback.
    //                 def observedIdentity = updated.device
    //                 [label: 'setLabel', name: 'setName', deviceNetworkId: 'setDeviceNetworkId'].each { property, setter ->
    //                     def original = full?.device?.get(property)
    //                     if (original && observedIdentity.containsKey(property) && !observedIdentity.get(property) &&
    //                             (!targets.containsKey(property) || targets.get(property))) {
    //                         try {
    //                             findDevice(deviceId)."${setter}"(original.toString())
    //                             updated = _fetchDeviceFullJson(deviceId)
    //                             if (updated?.device?.get(property)?.toString() != original.toString()) {
    //                                 throw new RuntimeException('Native readback did not confirm identity restoration')
    //                             }
    //                         } catch (Exception re) {
    //                             errors << [property: property, stage: 'restore', error: "Device-edit form blanked ${property}; restoring it failed: ${re.message}. Verify and re-set ${property}."]
    //                         }
    //                     }
    //                 }
    //             }
            targets.each { property, wanted ->
                def present = updated?.device instanceof Map && updated.device.containsKey(property)
                def actual = present ? updated.device.get(property) : null
                if (property == "dashboardIds") {
                    present = updated?.dashboards instanceof List
                    actual = present ? updated.dashboards.findAll { _deviceFlag(it.selected) }.collect { it.id?.toString() }.sort() : null
                }
                def expected = property == "dashboardIds" ? wanted.collect { it.toString() }.sort() : wanted
                def equal = present && (expected instanceof Boolean ? (actual instanceof Boolean || actual?.toString() in ["true", "false"]) && _deviceFlag(actual) == expected : expected instanceof List ? actual == expected : actual?.toString() == expected?.toString())
                if (property == "tags") equal = present && _normalizedDeviceTags(actual) == _normalizedDeviceTags(wanted)
                if (present && property in ["label", "notes", "defaultIcon", "tags"] && wanted == "" && actual == null) equal = true
                if (equal) changes << [property: property, oldValue: full?.device?.get(property), newValue: wanted]
                else errors << [property: property, error: present ? "POST accepted but ${property} read back as a different value; inspect configuration before retrying." : "POST accepted but could not confirm ${property}; native read-back is unavailable."]
            }
        } catch (Exception e) {
            targets.each { property, wanted -> errors << [property: property, error: e.message ?: e.toString()] }
        }
    }
    def assistants = _deviceAssistantProperties()
    def assistantRequests = assistants.keySet().findAll { args.containsKey(it) }
    if (assistantRequests) {
        def targets = [:]
        assistantRequests.each { targets.put(it, args.remove(it)) }
        try {
            def fresh = _fetchDeviceFullJson(deviceId)
            if (!(fresh?.device instanceof Map) || !assistants.keySet().every { fresh.containsKey(it) && fresh.get(it) instanceof Boolean }) throw new RuntimeException("Unable to read all current assistant assignments before saving; no assistant update sent")
            def payload = [deviceId: _prefSaveDeviceId(deviceId)]
            assistants.each { property, integration -> payload.put(property, fresh.get(property)) }
            payload.putAll(targets)
            def response = hubInternalPostJson("/device/updateAssistants", groovy.json.JsonOutput.toJson(payload))
            def readback = _fetchDeviceFullJson(deviceId)
            targets.each { property, wanted ->
                def nativeResult = response?.assistants?.get(assistants.get(property))
                if (nativeResult?.success == false) errors << [property: property, error: "Native assistant update failed: ${nativeResult.reason ?: 'unspecified integration error'}"]
                else if (response?.success == false && nativeResult?.success != true) errors << [property: property, error: "Native assistant update was rejected without a per-integration success result; inspect the native integration settings."]
                else if (readback?.containsKey(property) && readback.get(property) instanceof Boolean && readback.get(property) == wanted) changes << [property: property, newValue: wanted]
                else errors << [property: property, error: "POST accepted but could not confirm ${property}; inspect the native integration settings."]
            }
        } catch (Exception e) {
            targets.each { property, wanted -> errors << [property: property, error: e.message ?: e.toString()] }
        }
    }
    ["showOnHome", "defaultCurrentState"].each { property ->
        if (args.containsKey(property)) {
            def wanted = args.remove(property)
            try {
                boolean accepted = true
                try {
                    if (property == "showOnHome") {
                        hubInternalGet("/device/setShowOnHome", [deviceId: deviceId, show: wanted ? "true" : "false"])
                    } else {
                        def result = hubInternalGet("/device/setDefaultCurrentState", [id: deviceId, currentState: wanted])
                        accepted = result?.toString()?.trim()?.toLowerCase() == "true"
                    }
                } catch (IllegalStateException guardError) {
                    throw guardError
                } catch (Exception unavailable) {
                    mcpLog("warn", "device", "Native ${property} setter unavailable for device ${deviceId}: ${unavailable.message}; trying preference controls.")
                    _saveDevicePreferencePaneControls(deviceId, [(property): wanted])
                }
                if (!accepted) {
                    errors << [property: property, error: "Hub did not accept defaultCurrentState; use an attribute name from the device's current states."]
                    return
                }
                def readback = _fetchDeviceFullJson(deviceId)?.device
                def actual = readback?.get(property)
                def equal = readback?.containsKey(property) && (wanted instanceof Boolean ?
                    (actual instanceof Boolean || actual?.toString() in ["true", "false"]) && _deviceFlag(actual) == wanted :
                    (actual == null ? "" : actual.toString()) == wanted)
                if (equal) changes << [property: property, newValue: wanted]
                else if (!(readback instanceof Map) || !readback.containsKey(property)) {
                    errors << [property: property, error: "POST accepted but could not confirm the change; native read-back is unavailable for ${property}."]
                } else {
                    errors << [property: property, error: "POST accepted but ${property} read back as '${actual}' (expected '${wanted}')."]
                }
            } catch (Exception e) { errors << [property: property, error: e.message ?: e.toString()] }
        }
    }
}

def toolUpdateDevice(args) {
    def deviceId = _canonicalDeviceIdArg(args.deviceId)
    if (deviceId == null) throw new IllegalArgumentException("deviceId is required")
    if (settings.enableWrite == false) {
        throw new IllegalArgumentException("Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings")
    }
    _requireDeviceToolAccess(deviceId)
    def full = _fetchDeviceFullJson(deviceId)
    if (!(full?.device instanceof Map)) {
        return [success: false, isError: true, error: "Unable to read /device/fullJson before updating device ${deviceId}; no update sent.",
                note: "Read hub_get_device(mode='configuration') and retry when native device details are available."]
    }
    def prepared = _prepareDeviceUpdatePatch(args, deviceId, full)
    return _toolUpdateDeviceNative(prepared.args, deviceId, prepared.fullJson)

    // Retained SDK implementation for deliberate rollback; native execution above is authoritative.
    // def deviceId = args.deviceId
    //     if (!deviceId) throw new IllegalArgumentException("deviceId is required")
    //
    //     def device = findDevice(deviceId)
    //     if (!device) {
    //         if (_bypassEnabled()) {
    //             def fj = _fetchDeviceFullJson(deviceId)
    //             if (fj?.device != null) {
    //                 if (args.dataValues) throw new IllegalArgumentException("dataValues requires adding this device to the MCP device scope; no evidenced native bypass data writer is available")
    //                 def prepared = _prepareDeviceUpdatePatch(args, deviceId, fj)
    //                 return _toolUpdateDeviceBypass(prepared.args, deviceId, prepared.fullJson)
    //             }
    //         }
    //         throw new IllegalArgumentException("Device not found: ${deviceId}. The device must be in your selected devices or be an MCP-managed virtual device.")
    //     }
    //
    //     def prepared = _prepareDeviceUpdatePatch(args, deviceId)
    //     args = prepared.args
    //     def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"
    //     def changes = []
    //     def errors = []
    //
    //     _applyExtendedDeviceUpdate(args, deviceId, prepared.fullJson, false, changes, errors)
    //
    //     def requestedProps = []
    //     if (args.label != null) requestedProps << "label"
    //     if (args.name != null) requestedProps << "name"
    //     if (args.deviceNetworkId != null) requestedProps << "deviceNetworkId"
    //     if (args.dataValues) requestedProps << "dataValues(${args.dataValues.size()})"
    //     if (args.preferences) requestedProps << "preferences(${args.preferences.size()})"
    //     if (args.room != null) requestedProps << "room"
    //     if (args.enabled != null) requestedProps << "enabled"
    //     if (args.showOnHome != null) requestedProps << "showOnHome"
    //     if (args.defaultCurrentState != null) requestedProps << "defaultCurrentState"
    //     if (args.tags != null) requestedProps << "tags"
    //     mcpLog("debug", "device", "hub_update_device called for '${deviceLabel}' (ID: ${deviceId}), properties: ${requestedProps.join(', ')}")
    //
    //     // Label (official API)
    //     if (args.label != null) {
    //         try {
    //             def oldLabel = deviceLabel
    //             device.setLabel(args.label)
    //             changes << [property: "label", oldValue: oldLabel, newValue: args.label]
    //             deviceLabel = args.label
    //             mcpLog("debug", "device", "hub_update_device label: '${oldLabel}' -> '${args.label}'")
    //         } catch (Exception e) {
    //             mcpLog("debug", "device", "hub_update_device label: error: ${e.message}")
    //             errors << [property: "label", error: e.message]
    //         }
    //     }
    //
    //     // Name (official API)
    //     if (args.name != null) {
    //         try {
    //             def oldName = device.name
    //             device.setName(args.name)
    //             changes << [property: "name", oldValue: oldName, newValue: args.name]
    //             mcpLog("debug", "device", "hub_update_device name: '${oldName}' -> '${args.name}'")
    //         } catch (Exception e) {
    //             mcpLog("debug", "device", "hub_update_device name: error: ${e.message}")
    //             errors << [property: "name", error: e.message]
    //         }
    //     }
    //
    //     // Device Network ID (official API)
    //     if (args.deviceNetworkId != null) {
    //         try {
    //             def oldDni = device.deviceNetworkId
    //             device.setDeviceNetworkId(args.deviceNetworkId)
    //             changes << [property: "deviceNetworkId", oldValue: oldDni, newValue: args.deviceNetworkId]
    //             mcpLog("debug", "device", "hub_update_device DNI: '${oldDni}' -> '${args.deviceNetworkId}'")
    //         } catch (Exception e) {
    //             mcpLog("debug", "device", "hub_update_device DNI: error: ${e.message}")
    //             errors << [property: "deviceNetworkId", error: e.message]
    //         }
    //     }
    //
    //     // Data Values (official API)
    //     if (args.dataValues) {
    //         args.dataValues.each { key, value ->
    //             try {
    //                 device.updateDataValue(key.toString(), value?.toString())
    //                 changes << [property: "dataValue.${key}", newValue: value?.toString()]
    //                 mcpLog("debug", "device", "hub_update_device dataValue: ${key}='${value}'")
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device dataValue ${key}: error: ${e.message}")
    //                 errors << [property: "dataValue.${key}", error: e.message]
    //             }
    //         }
    //     }
    //
    //     if (args.preferences) {
    //         _applyDevicePreferencePatch(deviceId, device, args.preferences, changes, errors)
    //     }
    //
    //     // Room (internal API — write; Write master enforced centrally in executeTool)
    //     if (args.room != null) {
    //         if (settings.enableWrite == false) {
    //             errors << [property: "room", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
    //         } else {
    //             try {
    //                 mcpLog("debug", "device", "hub_update_device room: starting room assignment for device ${deviceId}")
    //
    //                 // Find room ID by name
    //                 def targetRoomId = null
    //                 // The MATCHED room's canonical name (the hub's casing). Recorded as the change's
    //                 // newValue so the listed path reports the same canonical casing the bypass path does,
    //                 // not the caller's raw casing.
    //                 def canonicalRoomName = null
    //                 if (args.room == "" || args.room == "none" || args.room == "null") {
    //                     targetRoomId = "0"
    //                     mcpLog("debug", "device", "hub_update_device room: unassigning device from room")
    //                 } else {
    //                     def cachedRooms = null
    //                     try {
    //                         cachedRooms = getRooms()
    //                         mcpLog("debug", "device", "hub_update_device room: getRooms() returned ${cachedRooms?.size() ?: 0} rooms")
    //                         if (cachedRooms) {
    //                             def targetRoom = cachedRooms.find { it.name?.toString()?.toLowerCase() == args.room?.toString()?.toLowerCase() }
    //                             if (targetRoom) {
    //                                 targetRoomId = targetRoom.id?.toString()
    //                                 canonicalRoomName = targetRoom.name?.toString()
    //                                 mcpLog("debug", "device", "hub_update_device room: resolved '${args.room}' -> roomId=${targetRoomId}")
    //                             }
    //                         }
    //                     } catch (Exception e) {
    //                         mcpLog("debug", "device", "hub_update_device room: getRooms() failed: ${e.message}")
    //                     }
    //
    //                     if (targetRoomId == null) {
    //                         def allRoomNames = cachedRooms ? cachedRooms.collect { it.name } : []
    //                         throw new RuntimeException("Room '${args.room}' not found.${allRoomNames ? ' Available rooms: ' + allRoomNames.join(', ') : ''}")
    //                     }
    //                 }
    //
    //                 // Room assignment via POST /room/save with JSON body.
    //                 // API uses "roomId" field (not "id"). Content-Type must be application/json.
    //
    //                 def saveSuccess = false
    //                 def saveError = null
    //                 def deviceIdLong = deviceId as Long
    //                 def deviceIdInt = deviceId as Integer
    //
    //                 // Helper: POST JSON to /room/save and check for errors
    //                 // Routed through hubInternalPostJson so room writes share the Hub Security
    //                 // cookie-refresh retry (a stale cookie no longer fails the save outright).
    //                 // It returns the parsed body (or null on empty/non-JSON); the
    //                 // verify-after-write step below is the real safety net for this path.
    //                 def roomSavePost = { Map bodyMap ->
    //                     def jsonStr = groovy.json.JsonOutput.toJson(bodyMap)
    //                     def parsed = hubInternalPostJson("/room/save", jsonStr, 30)
    //                     if (parsed?.error) {
    //                         throw new RuntimeException("Room API error: ${parsed.error}")
    //                     }
    //                     return parsed
    //                 }
    //
    //                 // Helper: check if device is in a room's device list
    //                 def deviceInRoom = { room ->
    //                     room?.deviceIds?.contains(deviceIdLong) || room?.deviceIds?.contains(deviceIdInt)
    //                 }
    //
    //                 // Get current room data
    //                 def allRooms = getRooms()
    //                 mcpLog("debug", "device", "hub_update_device room: getRooms() returned ${allRooms?.size() ?: 0} rooms")
    //
    //                 if (targetRoomId == "0") {
    //                     // --- UNASSIGN: remove device from its current room ---
    //                     def currentRoom = allRooms?.find { deviceInRoom(it) }
    //                     if (!currentRoom) {
    //                         saveSuccess = true
    //                         mcpLog("debug", "device", "hub_update_device room: device not in any room, nothing to unassign")
    //                     } else {
    //                         mcpLog("debug", "device", "hub_update_device room: removing device ${deviceId} from room '${currentRoom.name}' (${currentRoom.id})")
    //                         def updatedDeviceIds = currentRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
    //                         def body = [roomId: currentRoom.id as Integer, name: currentRoom.name, deviceIds: updatedDeviceIds]
    //                         mcpLog("debug", "device", "hub_update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(body)}")
    //                         try {
    //                             roomSavePost(body)
    //                             saveSuccess = true
    //                         } catch (Exception e) {
    //                             mcpLog("debug", "device", "hub_update_device room: remove failed: ${e.message}")
    //                             saveError = e.message
    //                         }
    //                     }
    //                 } else {
    //                     // --- ASSIGN: add device to target room ---
    //                     mcpLog("debug", "device", "hub_update_device room: assigning device ${deviceId} to room ${targetRoomId}")
    //
    //                     // Check if device is already in the target room
    //                     def targetRoom = allRooms?.find { it.id?.toString() == targetRoomId }
    //                     if (targetRoom && deviceInRoom(targetRoom)) {
    //                         mcpLog("debug", "device", "hub_update_device room: device already in target room '${targetRoom.name}'")
    //                         saveSuccess = true
    //                     } else {
    //                         // Safe Move pattern: add to new room FIRST, then remove from old room.
    //                         // This prevents "device limbo" where a device ends up in no room if
    //                         // the second API call fails after the first succeeds.
    //                         // Worst case (remove fails): device appears in both rooms temporarily,
    //                         // which is recoverable. The old pattern (remove first) could orphan the device.
    //
    //                         // Locate old room (if any) before mutations
    //                         def oldRoom = allRooms?.find { room ->
    //                             deviceInRoom(room) && room.id?.toString() != targetRoomId
    //                         }
    //
    //                         // Step 1: Add device to target room
    //                         def freshTarget = allRooms?.find { it.id?.toString() == targetRoomId }
    //                         def targetDeviceIds = freshTarget?.deviceIds?.collect { it as Integer } ?: []
    //                         def devIdInt = deviceId as Integer
    //                         if (!targetDeviceIds.contains(devIdInt)) {
    //                             targetDeviceIds << devIdInt
    //                         }
    //
    //                         def roomData = [roomId: targetRoomId as Integer, name: freshTarget?.name ?: targetRoom?.name ?: "", deviceIds: targetDeviceIds]
    //                         mcpLog("debug", "device", "hub_update_device room: POST /room/save (add) body: ${groovy.json.JsonOutput.toJson(roomData)}")
    //                         try {
    //                             roomSavePost(roomData)
    //                             mcpLog("debug", "device", "hub_update_device room: added to target room '${freshTarget?.name ?: targetRoomId}'")
    //                             saveSuccess = true
    //                         } catch (Exception e) {
    //                             // Add failed — device stays safely in its old room (no change made)
    //                             mcpLog("debug", "device", "hub_update_device room: add to room failed: ${e.message}")
    //                             saveError = e.message
    //                         }
    //
    //                         // Step 2: Remove from old room (only if add succeeded)
    //                         if (saveSuccess && oldRoom) {
    //                             mcpLog("debug", "device", "hub_update_device room: removing from old room '${oldRoom.name}' (${oldRoom.id})")
    //                             // Re-fetch rooms to get fresh data after the add mutation
    //                             def freshRooms = getRooms()
    //                             def freshOldRoom = freshRooms?.find { it.id?.toString() == oldRoom.id?.toString() }
    //                             if (freshOldRoom) {
    //                                 def oldDeviceIds = freshOldRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
    //                                 def oldBody = [roomId: freshOldRoom.id as Integer, name: freshOldRoom.name, deviceIds: oldDeviceIds]
    //                                 mcpLog("debug", "device", "hub_update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(oldBody)}")
    //                                 try {
    //                                     roomSavePost(oldBody)
    //                                     mcpLog("debug", "device", "hub_update_device room: removed from old room '${oldRoom.name}'")
    //                                 } catch (Exception oldErr) {
    //                                     // Device is in both rooms — not ideal but it IS in the target room.
    //                                     // Log a warning so the user is aware.
    //                                     mcpLog("warn", "device", "hub_update_device room: device added to new room but removal from old room '${oldRoom.name}' failed: ${oldErr.message}. Device may appear in both rooms.")
    //                                 }
    //                             }
    //                         }
    //                     }
    //                 }
    //
    //                 // Verify the room actually changed
    //                 if (saveSuccess) {
    //                     def verified = false
    //                     try {
    //                         def verifyRooms = getRooms()
    //                         if (targetRoomId == "0") {
    //                             def stillInRoom = verifyRooms?.find { room -> deviceInRoom(room) }
    //                             verified = (stillInRoom == null)
    //                             if (!verified) {
    //                                 mcpLog("debug", "device", "hub_update_device room: VERIFICATION FAILED - device still in room '${stillInRoom?.name}'")
    //                             }
    //                         } else {
    //                             def tRoom = verifyRooms?.find { it.id?.toString() == targetRoomId }
    //                             verified = deviceInRoom(tRoom)
    //                             if (!verified) {
    //                                 mcpLog("debug", "device", "hub_update_device room: VERIFICATION FAILED - device not in target room '${tRoom?.name}' deviceIds: ${tRoom?.deviceIds}")
    //                             }
    //                             // Also verify device is NOT still in the old room
    //                             if (verified) {
    //                                 def dualRoom = verifyRooms?.find { room -> deviceInRoom(room) && room.id?.toString() != targetRoomId }
    //                                 if (dualRoom) {
    //                                     mcpLog("warn", "device", "hub_update_device room: WARNING - device also still in room '${dualRoom.name}' (dual-room state)")
    //                                 }
    //                             }
    //                         }
    //                     } catch (Exception verErr) {
    //                         mcpLog("debug", "device", "hub_update_device room: verification error: ${verErr.message}")
    //                     }
    //
    //                     if (verified) {
    //                         def oldRoomName = device.roomName ?: "none"
    //                         def newRoomName = (targetRoomId == "0") ? "none" : (canonicalRoomName ?: args.room)
    //                         changes << [property: "room", oldValue: oldRoomName, newValue: newRoomName]
    //                         mcpLog("info", "device", "Room changed for '${deviceLabel}': ${oldRoomName} -> ${newRoomName} (VERIFIED)")
    //                     } else {
    //                         throw new RuntimeException("Room assignment endpoint returned success but room did not actually change.")
    //                     }
    //                 } else {
    //                     throw new RuntimeException("Room assignment failed. Last error: ${saveError}")
    //                 }
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device room: error: ${e.message}")
    //                 errors << [property: "room", error: e.message]
    //             }
    //         }
    //     }
    //
    //     // Enable/Disable (internal API — write; Write master enforced centrally in executeTool)
    //     // Vue posts application/json with numeric id and boolean disable; verify with a fresh read.
    //     if (args.enabled != null) {
    //         if (settings.enableWrite == false) {
    //             errors << [property: "enabled", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
    //         } else {
    //             try {
    //                 def disableValue = !args.enabled
    //                 mcpLog("debug", "device", "hub_update_device enabled: POSTing to /device/disable with id=${deviceId}, disable=${disableValue}")
    //                 hubInternalPostJson("/device/disable", groovy.json.JsonOutput.toJson([id: _prefSaveDeviceId(deviceId), disable: disableValue]))
    //                 // Confirm the flip before recording success -- a 200 from /device/disable does not
    //                 // prove the state changed, and the request-scoped device handle's disabled flag is
    //                 // execution-cached (stale to a same-request POST), so confirm via a FRESH re-read.
    //                 def res = _confirmDisabledFlip(device.id.toString(), !args.enabled)
    //                 if (res.fetchFailed) {
    //                     errors << [property: "enabled", error: "POST accepted but could not confirm the change -- the read-back fetch failed."]
    //                 } else if (res.ok) {
    //                     changes << [property: "enabled", newValue: args.enabled]
    //                     mcpLog("info", "device", "Device '${deviceLabel}' ${args.enabled ? 'enabled' : 'disabled'}")
    //                 } else {
    //                     errors << [property: "enabled", error: "POST accepted but the device read back as ${res.actualDisabled ? 'disabled' : 'enabled'} (expected ${!args.enabled ? 'disabled' : 'enabled'})."]
    //                 }
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device enabled: error: ${e.message}")
    //                 errors << [property: "enabled", error: e.message]
    //             }
    //         }
    //     }
    //
    //     // Show-on-Home flag (internal API -- no SDK setter; Write master enforced centrally).
    //     // Controls whether the device appears on the hub Home page and counts toward its quick
    //     // status-bar summaries. Prefer the dedicated GET (clean, single-purpose) but fall back to
    //     // /device/preference/save when it's absent: /device/setShowOnHome answers on some hubs and
    //     // 404s on others (observed 404 on a 2.5.0.157 hub, 200 on 2.5.0.159 -- cause not established,
    //     // and NOT attributable to any documented release-notes change), whereas /device/preference/save
    //     // was present on both. That fallback re-posts every preference-pane control because omissions reset.
    //     if (args.showOnHome != null) {
    //         if (settings.enableWrite == false) {
    //             errors << [property: "showOnHome", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
    //         } else {
    //             try {
    //                 def showVal = args.showOnHome ? "true" : "false"
    //                 try {
    //                     hubInternalGet("/device/setShowOnHome", [deviceId: deviceId, show: showVal])
    //                 } catch (IllegalStateException guardErr) {
    //                     throw guardErr   // ?-in-path guard: a coding bug, never a fallback trigger
    //                 } catch (Exception primaryErr) {
    //                     mcpLog("debug", "device", "hub_update_device showOnHome: dedicated endpoint failed (${primaryErr.message}); falling back to /device/preference/save")
    //                     _saveDevicePreferencePaneControls(deviceId, [showOnHome: args.showOnHome])
    //                 }
    //                 // Confirm via a FRESH read-back: a 200 from either endpoint does not prove the flag
    //                 // flipped, and /device/preference/save returns {success} even on a no-op. fullJson
    //                 // carries device.showOnHome as a Boolean (robust to the JSON string "true").
    //                 def fjReadback = _fetchDeviceFullJson(deviceId)
    //                 if (fjReadback?.device == null) {
    //                     errors << [property: "showOnHome", error: "POST accepted but could not confirm the change -- the read-back fetch failed."]
    //                 } else {
    //                     def rawShow = fjReadback.device.showOnHome
    //                     def gotShow = (rawShow == true || rawShow?.toString() == "true")
    //                     if (gotShow == (args.showOnHome == true)) {
    //                         changes << [property: "showOnHome", newValue: args.showOnHome]
    //                         mcpLog("info", "device", "Device '${deviceLabel}' showOnHome -> ${args.showOnHome}")
    //                     } else {
    //                         errors << [property: "showOnHome", error: "POST accepted but showOnHome read back as ${gotShow} (expected ${args.showOnHome == true})."]
    //                     }
    //                 }
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device showOnHome: error: ${e.message}")
    //                 errors << [property: "showOnHome", error: e.message]
    //             }
    //         }
    //     }
    //
    //     // Default Current State -- which Current-States attribute shows in the Status column on the
    //     // Devices/Rooms pages ("" selects None). Same endpoint-availability split as showOnHome:
    //     // prefer the dedicated GET (returns `true`), fall back to /device/preference/save where absent.
    //     if (args.defaultCurrentState != null) {
    //         if (settings.enableWrite == false) {
    //             errors << [property: "defaultCurrentState", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
    //         } else {
    //             try {
    //                 def csVal = args.defaultCurrentState.toString()
    //                 def applied = false
    //                 try {
    //                     // The dedicated endpoint returns the literal `true` on success. A 200 carrying
    //                     // anything else (e.g. `false` for an unknown attribute name) is a real rejection
    //                     // -- record an error, do NOT fall back (the endpoint exists, the value is bad).
    //                     def result = hubInternalGet("/device/setDefaultCurrentState", [id: deviceId, currentState: csVal])
    //                     if (result?.toString()?.trim()?.toLowerCase() == "true") {
    //                         applied = true
    //                     } else {
    //                         errors << [property: "defaultCurrentState", error: "Hub did not accept defaultCurrentState='${csVal}' (returned '${result?.toString()?.take(120)}'). Use an attribute name from the device's current states."]
    //                     }
    //                 } catch (IllegalStateException guardErr) {
    //                     throw guardErr   // ?-in-path guard: a coding bug, never a fallback trigger
    //                 } catch (Exception primaryErr) {
    //                     // Dedicated endpoint absent on some hubs (404) -- fall back to the Preferences-pane save.
    //                     mcpLog("debug", "device", "hub_update_device defaultCurrentState: dedicated endpoint failed (${primaryErr.message}); falling back to /device/preference/save")
    //                     _saveDevicePreferencePaneControls(deviceId, [defaultCurrentState: csVal])
    //                     applied = true
    //                 }
    //                 if (applied) {
    //                     // Confirm via a FRESH read-back before recording: the /device/preference/save
    //                     // fallback returns {success} on a no-op. fullJson carries device.defaultCurrentState
    //                     // as the attribute-name string, or null/"" for None (the empty-string request).
    //                     def fjReadback = _fetchDeviceFullJson(deviceId)
    //                     if (fjReadback?.device == null) {
    //                         errors << [property: "defaultCurrentState", error: "POST accepted but could not confirm the change -- the read-back fetch failed."]
    //                     } else {
    //                         def got = fjReadback.device.defaultCurrentState
    //                         def gotStr = (got == null) ? null : got.toString()
    //                         def cleared = (csVal == "")
    //                         def ok = cleared ? (gotStr == null || gotStr == "") : (gotStr == csVal)
    //                         if (ok) {
    //                             changes << [property: "defaultCurrentState", newValue: csVal]
    //                             mcpLog("info", "device", "Device '${deviceLabel}' defaultCurrentState -> '${csVal}'")
    //                         } else {
    //                             errors << [property: "defaultCurrentState", error: "POST accepted but defaultCurrentState read back as '${gotStr}' (expected '${cleared ? '(none)' : csVal}')."]
    //                         }
    //                     }
    //                 }
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device defaultCurrentState: error: ${e.message}")
    //                 errors << [property: "defaultCurrentState", error: e.message]
    //             }
    //         }
    //     }
    //
    //     // Tags (internal API -- no SDK setter and no dedicated endpoint; the ONLY path is the
    //     // wholesale /device/update form, which BLANKS any field it omits. So read the full
    //     // device-edit model, change only tags, re-POST the COMPLETE form, then verify the tags
    //     // landed and the identity fields survived (restoring label/name/DNI via the SDK if the
    //     // hub dropped them).
    //     if (args.tags != null) {
    //         if (settings.enableWrite == false) {
    //             errors << [property: "tags", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
    //         } else {
    //             try {
    //                 def tagsCsv = _normalizedDeviceTags(args.tags).join(",")
    //                 def fjText = hubInternalGet("/device/fullJson/${deviceId}")
    //                 def full = fjText ? new groovy.json.JsonSlurper().parseText(fjText) : null
    //                 def d = full?.device
    //                 if (!d) throw new RuntimeException("Could not read the device model from /device/fullJson to preserve fields")
    //                 def oldLabel = d.label; def oldName = d.name; def oldDni = d.deviceNetworkId
    //                 def body = _deviceConfigurationFormBody(deviceId, full, [tags: tagsCsv])
    //                 hubInternalPostFormRaw("/device/update", body)
    //                 // Verify: tags applied AND identity fields not blanked by the wholesale form
    //                 def vText = hubInternalGet("/device/fullJson/${deviceId}")
    //                 def vd = vText ? new groovy.json.JsonSlurper().parseText(vText)?.device : null
    //                 // Identity-restore runs FIRST, regardless of whether tags matched: the wholesale
    //                 // /device/update form blanks any field it omits, and that can happen on the
    //                 // tag-mismatch path too -- so restore label/name/DNI whenever the read-back shows
    //                 // them blanked, before branching on the tag result. A restore-setter failure is
    //                 // surfaced as an actionable error (not swallowed) so the user knows to re-set it.
    //                 if (oldLabel && !vd?.label) { try { device.setLabel(oldLabel) } catch (Exception re) { errors << [property: "label", error: "Tags processed but the device-edit form blanked the label and restoring it failed: ${re.message}. Verify and re-set the label."] } }
    //                 if (oldName && !vd?.name) { try { device.setName(oldName) } catch (Exception re) { errors << [property: "name", error: "Tags processed but the device-edit form blanked the name and restoring it failed: ${re.message}. Verify and re-set the name."] } }
    //                 if (oldDni && !vd?.deviceNetworkId) { try { device.setDeviceNetworkId(oldDni) } catch (Exception re) { errors << [property: "deviceNetworkId", error: "Tags processed but the device-edit form blanked the deviceNetworkId and restoring it failed: ${re.message}. Verify and re-set the deviceNetworkId."] } }
    //                 def gotTags = vd instanceof Map && vd.containsKey('tags') ? _normalizedDeviceTags(vd.tags) : null
    //                 if (gotTags != _normalizedDeviceTags(tagsCsv)) {
    //                     errors << [property: "tags", error: "POST accepted but tags read back as '${gotTags}' (expected '${tagsCsv}'). Other fields were preserved."]
    //                 } else {
    //                     changes << [property: "tags", oldValue: d.tags, newValue: tagsCsv]
    //                     mcpLog("info", "device", "Device '${deviceLabel}' tags -> '${tagsCsv}'")
    //                 }
    //             } catch (Exception e) {
    //                 mcpLog("debug", "device", "hub_update_device tags: error: ${e.message}")
    //                 errors << [property: "tags", error: e.message]
    //             }
    //         }
    //     }
    //
    //     if (!changes && !errors) {
    //         return [
    //             success: true,
    //             device: deviceLabel,
    //             deviceId: deviceId,
    //             message: "No properties were provided to update. Specify at least one property: label, name, deviceNetworkId, room, enabled, dataValues, preferences, showOnHome, defaultCurrentState, or tags."
    //         ]
    //     }
    //
    //     mcpLog(errors.isEmpty() ? "info" : "error", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}): ${changes.size()} changes, ${errors.size()} errors")
    //     if (errors) {
    //         mcpLog("debug", "device", "hub_update_device errors: ${errors.collect { "${it.property}: ${it.error}" }.join('; ')}")
    //     }
    //
    //     return [
    //         success: errors.isEmpty(),
    //         device: deviceLabel,
    //         deviceId: deviceId,
    //         changes: changes,
    //         errors: errors.isEmpty() ? null : errors,
    //         message: errors.isEmpty()
    //             ? "Successfully updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} on device '${deviceLabel}'."
    //             : "Updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} with ${errors.size()} ${errors.size() == 1 ? 'error' : 'errors'} on device '${deviceLabel}'."
    //     ]
}

// Access policy is checked before reaching these native, ID-keyed writes.
// Native bypass helper retained with the SDK caller for deliberate rollback.
// private Map _toolUpdateDeviceBypass(args, deviceId, Map fj) {
//     def d = fj.device
//     def deviceLabel = _bypassDeviceLabel(fj, deviceId)
//     def changes = []
//     def errors = []
//
//     _applyExtendedDeviceUpdate(args, deviceId, fj, true, changes, errors)
//
//     def requestedProps = []
//     if (args.label != null) requestedProps << "label"
//     if (args.name != null) requestedProps << "name"
//     if (args.deviceNetworkId != null) requestedProps << "deviceNetworkId"
//     if (args.preferences) requestedProps << "preferences(${args.preferences.size()})"
//     if (args.room != null) requestedProps << "room"
//     if (args.enabled != null) requestedProps << "enabled"
//     mcpLog("warn", "device", "hub_update_device (allowlist bypass) for '${deviceLabel}' (ID: ${deviceId}), properties: ${requestedProps.join(', ')}")
//
//     // label / name / deviceNetworkId -> ONE wholesale /device/update form POST. The form blanks any
//     // omitted field, so all three ride a single faithful device-model re-POST rebuilt from a FRESH
//     // fullJson fetch inside _postBypassDeviceModel, then each is verified by read-back. label rides
//     // this portable form rather than the dedicated GET /device/updateLabel setter: updateLabel 404s
//     // on some firmwares (confirmed on 2.5.0.157) -- the same sometimes-absent dedicated-setter class
//     // as /device/setShowOnHome and /device/setDefaultCurrentState (resources/hub2-source/README.md),
//     // for which the wholesale /device/update form (which carries a `label` field) is the fallback.
//     if (args.label != null || args.name != null || args.deviceNetworkId != null) {
//         try {
//             def overrides = [:]
//             if (args.label != null) overrides.label = args.label.toString()
//             if (args.name != null) overrides.name = args.name.toString()
//             if (args.deviceNetworkId != null) overrides.deviceNetworkId = args.deviceNetworkId.toString()
//             def vd = _postBypassDeviceModel(deviceId, overrides)
//             if (args.label != null) {
//                 if (vd?.label?.toString() == overrides.label) {
//                     changes << [property: "label", oldValue: d.label, newValue: overrides.label]
//                     deviceLabel = overrides.label
//                 } else {
//                     errors << [property: "label", error: "POST accepted but label read back as '${vd?.label}' (expected '${overrides.label}')."]
//                 }
//             }
//             if (args.name != null) {
//                 if (vd?.name?.toString() == overrides.name) changes << [property: "name", oldValue: d.name, newValue: overrides.name]
//                 else errors << [property: "name", error: "POST accepted but name read back as '${vd?.name}' (expected '${overrides.name}')."]
//             }
//             if (args.deviceNetworkId != null) {
//                 if (vd?.deviceNetworkId?.toString() == overrides.deviceNetworkId) changes << [property: "deviceNetworkId", oldValue: d.deviceNetworkId, newValue: overrides.deviceNetworkId]
//                 else errors << [property: "deviceNetworkId", error: "POST accepted but deviceNetworkId read back as '${vd?.deviceNetworkId}' (expected '${overrides.deviceNetworkId}')."]
//             }
//         } catch (Exception e) {
//             errors << [property: "deviceModel", error: "${e.message ?: e.toString()}"]
//         }
//     }
//
//     if (args.preferences) {
//         _applyDevicePreferencePatch(deviceId, null, args.preferences, changes, errors)
//     }
//
//     // Room. /device/updateRoom takes the room NAME (NOT the id -- live-verified: sending an id
//     // makes the hub CREATE a spurious room named after the number). So for an assign, validate the
//     // NAME exists first (parity with the listed path's "Room not found"; updateRoom would otherwise
//     // silently create it) then send the name. For an unassign ("" / "none" / "null", matching the
//     // listed path) DON'T send an empty name -- route through the wholesale /device/update form with
//     // roomId=0 (live-verified to clear roomId/roomName cleanly).
//     if (args.room != null) {
//         try {
//             if (args.room == "" || args.room == "none" || args.room == "null") {
//                 def vd = _postBypassDeviceModel(deviceId, [roomId: 0])
//                 if (vd == null) {
//                     errors << [property: "room", error: "POST accepted but the read-back to confirm the unassign failed (/device/fullJson)."]
//                 } else if (!vd.roomName) {
//                     changes << [property: "room", oldValue: d.roomName ?: "none", newValue: "none"]
//                 } else {
//                     errors << [property: "room", error: "POST accepted but the device is still in room '${vd.roomName}' (expected unassigned)."]
//                 }
//             } else {
//                 // Send the CANONICAL room name (the existing room's casing), not the caller's raw
//                 // casing -- /device/updateRoom is name-keyed and silently CREATES a spurious room
//                 // for a name it doesn't match exactly, so "foyer" vs an existing "Foyer" would
//                 // otherwise pass the case-insensitive existence check yet create a duplicate.
//                 def matchedRoom = _assertRoomExistsForBypass(args.room)
//                 def canonicalName = matchedRoom.name.toString()
//                 def r = hubInternalGet("/device/updateRoom", [deviceId: deviceId, room: canonicalName])
//                 if (r?.toString()?.trim()?.toLowerCase() == "true") {
//                     // A "true" return doesn't prove the assignment landed -- re-read and confirm,
//                     // mirroring the unassign leg.
//                     def vd = _fetchDeviceFullJson(deviceId)?.device
//                     if (vd == null) {
//                         errors << [property: "room", error: "POST accepted but could not confirm the room change -- the read-back fetch failed."]
//                     } else if (vd.roomName?.toString() == canonicalName) {
//                         changes << [property: "room", oldValue: d.roomName ?: "none", newValue: canonicalName]
//                     } else {
//                         errors << [property: "room", error: "POST accepted but the device is in room '${vd.roomName}' (expected '${canonicalName}')."]
//                     }
//                 } else {
//                     errors << [property: "room", error: "Hub did not accept the room update (returned '${r?.toString()?.take(120)}')."]
//                 }
//             }
//         } catch (Exception e) {
//             errors << [property: "room", error: "${e.message ?: e.toString()}"]
//         }
//     }
//
//     // Enabled -> POST /device/disable (disable:true disables, false enables). Read back the
//     // device's disabled flag from fullJson and confirm the flip before recording the change --
//     // a 200 from /device/disable does not by itself prove the state changed.
//     if (args.enabled != null) {
//         try {
//             hubInternalPostJson("/device/disable", groovy.json.JsonOutput.toJson([id: _prefSaveDeviceId(deviceId), disable: !args.enabled]))
//             def res = _confirmDisabledFlip(deviceId, !args.enabled)
//             if (res.fetchFailed) {
//                 errors << [property: "enabled", error: "POST accepted but could not confirm the change -- the read-back fetch failed."]
//             } else if (res.ok) {
//                 changes << [property: "enabled", newValue: args.enabled]
//             } else {
//                 errors << [property: "enabled", error: "POST accepted but the device read back as ${res.actualDisabled ? 'disabled' : 'enabled'} (expected ${!args.enabled ? 'disabled' : 'enabled'})."]
//             }
//         } catch (Exception e) {
//             errors << [property: "enabled", error: "${e.message ?: e.toString()}"]
//         }
//     }
//
//     if (!changes && !errors) {
//         return [
//             success: true,
//             device: deviceLabel,
//             deviceId: deviceId,
//             message: "No properties were provided to update. Specify at least one property: label, name, deviceNetworkId, room, enabled, or preferences."
//         ]
//     }
//
//     mcpLog(errors.isEmpty() ? "info" : "error", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}) via allowlist bypass: ${changes.size()} changes, ${errors.size()} errors")
//     return [
//         success: errors.isEmpty(),
//         device: deviceLabel,
//         deviceId: deviceId,
//         changes: changes,
//         errors: errors.isEmpty() ? null : errors,
//         message: errors.isEmpty()
//             ? "Successfully updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} on device '${deviceLabel}' (allowlist bypass)."
//             : "Updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} with ${errors.size()} ${errors.size() == 1 ? 'error' : 'errors'} on device '${deviceLabel}' (allowlist bypass)."
//     ]
// }

private Map _toolUpdateDeviceNative(args, deviceId, Map fj) {
    def d = fj.device
    def deviceLabel = _bypassDeviceLabel(fj, deviceId)
    def changes = []
    def errors = []

    _applyExtendedDeviceUpdate(args, deviceId, fj, true, changes, errors)

    def requestedProps = []
    if (args.label != null) requestedProps << "label"
    if (args.name != null) requestedProps << "name"
    if (args.deviceNetworkId != null) requestedProps << "deviceNetworkId"
    if (args.preferences) requestedProps << "preferences(${args.preferences.size()})"
    if (args.room != null) requestedProps << "room"
    if (args.enabled != null) requestedProps << "enabled"
    mcpLog("warn", "device", "hub_update_device (native) for '${deviceLabel}' (ID: ${deviceId}), properties: ${requestedProps.join(', ')}")

    // Prefer the narrow label setter; unavailable endpoints fall back to the complete native form.
    if (args.label != null && args.name == null && args.deviceNetworkId == null) {
        def wanted = args.remove("label").toString()
        try {
            def readback
            boolean usedForm = false
            boolean accepted = true
            try {
                def response = hubInternalGet("/device/updateLabel", [deviceId: deviceId, label: wanted])
                accepted = response?.toString()?.trim()?.toLowerCase() == "true"
            } catch (IllegalStateException guardError) {
                throw guardError
            } catch (Exception unavailable) {
                mcpLog("warn", "device", "Native label setter unavailable for device ${deviceId}: ${unavailable.message}; trying the complete form.")
                readback = _postBypassDeviceModel(deviceId, [label: wanted], errors)
                usedForm = true
            }
            if (!accepted) {
                errors << [property: "label", error: "Hub did not accept the label update; inspect the native device details before retrying."]
            } else {
                if (!usedForm) readback = _fetchDeviceFullJson(deviceId)?.device
                if (readback?.containsKey("label") && (readback.label == null ? "" : readback.label.toString()) == wanted) {
                    changes << [property: "label", oldValue: d.label, newValue: wanted]
                    deviceLabel = wanted ?: readback.name ?: "Device ${deviceId}"
                } else {
                    def reason = readback instanceof Map && readback.containsKey("label")
                        ? "label read back as '${readback.label}' (expected '${wanted}')."
                        : "could not confirm label; native read-back was unavailable."
                    errors << [property: "label", error: "Update accepted but ${reason}"]
                }
            }
        } catch (Exception e) {
            errors << [property: "label", error: e.message ?: e.toString()]
        }
    }

    // Combined identity edits share one complete form rebuilt from fresh native metadata.
    if (args.label != null || args.name != null || args.deviceNetworkId != null) {
        try {
            def overrides = [:]
            if (args.label != null) overrides.label = args.label.toString()
            if (args.name != null) overrides.name = args.name.toString()
            if (args.deviceNetworkId != null) overrides.deviceNetworkId = args.deviceNetworkId.toString()
            def vd = _postBypassDeviceModel(deviceId, overrides, errors)
            if (args.label != null) {
                if (vd?.containsKey("label") && (vd.label == null ? "" : vd.label.toString()) == overrides.label) {
                    changes << [property: "label", oldValue: d.label, newValue: overrides.label]
                    deviceLabel = overrides.label ?: vd.name ?: "Device ${deviceId}"
                } else {
                    errors << [property: "label", error: "POST accepted but label read back as '${vd?.label}' (expected '${overrides.label}')."]
                }
            }
            if (args.name != null) {
                if (vd?.name?.toString() == overrides.name) changes << [property: "name", oldValue: d.name, newValue: overrides.name]
                else errors << [property: "name", error: "POST accepted but name read back as '${vd?.name}' (expected '${overrides.name}')."]
            }
            if (args.deviceNetworkId != null) {
                if (vd?.deviceNetworkId?.toString() == overrides.deviceNetworkId) changes << [property: "deviceNetworkId", oldValue: d.deviceNetworkId, newValue: overrides.deviceNetworkId]
                else errors << [property: "deviceNetworkId", error: "POST accepted but deviceNetworkId read back as '${vd?.deviceNetworkId}' (expected '${overrides.deviceNetworkId}')."]
            }
        } catch (Exception e) {
            errors << [property: "deviceModel", error: "${e.message ?: e.toString()}"]
        }
    }

    if (args.dataValues) _applyNativeDeviceDataValues(deviceId, args.dataValues, changes, errors)

    if (args.preferences) {
        _applyDevicePreferencePatch(deviceId, null, args.preferences, changes, errors)
    }

    // Room. /device/updateRoom takes the room NAME (NOT the id -- live-verified: sending an id
    // makes the hub CREATE a spurious room named after the number). So for an assign, validate the
    // NAME exists first (parity with the listed path's "Room not found"; updateRoom would otherwise
    // silently create it) then send the name. For an unassign ("" / "none" / "null", matching the
    // listed path) DON'T send an empty name -- route through the wholesale /device/update form with
    // roomId=0 (live-verified to clear roomId/roomName cleanly).
    if (args.room != null) {
        try {
            if (args.room == "" || args.room == "none" || args.room == "null") {
                def vd = _postBypassDeviceModel(deviceId, [roomId: 0], errors)
                if (vd == null) {
                    errors << [property: "room", error: "POST accepted but the read-back to confirm the unassign failed (/device/fullJson)."]
                } else if (!vd.containsKey('roomName') ||
                    (vd.roomName != null && !(vd.roomName instanceof String))) {
                    errors << [property: "room", error: "POST accepted but roomName is unavailable or invalid; the unassign could not be confirmed."]
                } else if (vd.roomName == null || vd.roomName == '') {
                    changes << [property: "room", oldValue: d.roomName ?: "none", newValue: "none"]
                } else {
                    errors << [property: "room", error: "POST accepted but the device is still in room '${vd.roomName}' (expected unassigned)."]
                }
            } else {
                // Send the CANONICAL room name (the existing room's casing), not the caller's raw
                // casing -- /device/updateRoom is name-keyed and silently CREATES a spurious room
                // for a name it doesn't match exactly, so "foyer" vs an existing "Foyer" would
                // otherwise pass the case-insensitive existence check yet create a duplicate.
                def matchedRoom = _assertRoomExistsForBypass(args.room)
                def canonicalName = matchedRoom.name.toString()
                def r = hubInternalGet("/device/updateRoom", [deviceId: deviceId, room: canonicalName])
                if (r?.toString()?.trim()?.toLowerCase() == "true") {
                    // A "true" return doesn't prove the assignment landed -- re-read and confirm,
                    // mirroring the unassign leg.
                    def vd = _fetchDeviceFullJson(deviceId)?.device
                    if (vd == null) {
                        errors << [property: "room", error: "POST accepted but could not confirm the room change -- the read-back fetch failed."]
                    } else if (vd.roomName?.toString() == canonicalName) {
                        changes << [property: "room", oldValue: d.roomName ?: "none", newValue: canonicalName]
                    } else {
                        errors << [property: "room", error: "POST accepted but the device is in room '${vd.roomName}' (expected '${canonicalName}')."]
                    }
                } else {
                    errors << [property: "room", error: "Hub did not accept the room update (returned '${r?.toString()?.take(120)}')."]
                }
            }
        } catch (Exception e) {
            errors << [property: "room", error: "${e.message ?: e.toString()}"]
        }
    }

    // Enabled -> POST /device/disable (disable:true disables, false enables). Read back the
    // device's disabled flag from fullJson and confirm the flip before recording the change --
    // a 200 from /device/disable does not by itself prove the state changed.
    if (args.enabled != null) {
        try {
            hubInternalPostJson("/device/disable", groovy.json.JsonOutput.toJson([id: _prefSaveDeviceId(deviceId), disable: !args.enabled]))
            def res = _confirmDisabledFlip(deviceId, !args.enabled)
            if (res.fetchFailed) {
                errors << [property: "enabled", error: "POST accepted but could not confirm the change -- the read-back fetch failed."]
            } else if (res.ok) {
                changes << [property: "enabled", newValue: args.enabled]
            } else {
                errors << [property: "enabled", error: "POST accepted but the device read back as ${res.actualDisabled ? 'disabled' : 'enabled'} (expected ${!args.enabled ? 'disabled' : 'enabled'})."]
            }
        } catch (Exception e) {
            errors << [property: "enabled", error: "${e.message ?: e.toString()}"]
        }
    }

    if (!changes && !errors) {
        return [
            success: true,
            device: deviceLabel,
            deviceId: deviceId,
            message: "No properties were provided to update. Specify at least one property: label, name, deviceNetworkId, room, enabled, or preferences."
        ]
    }

    mcpLog(errors.isEmpty() ? "info" : "error", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}) via native endpoints: ${changes.size()} changes, ${errors.size()} errors")
    def result = [
        success: errors.isEmpty(),
        device: deviceLabel,
        deviceId: deviceId,
        changes: changes,
        errors: errors.isEmpty() ? null : errors,
        message: errors.isEmpty()
            ? "Successfully updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} on device '${deviceLabel}' (native)."
            : "Updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} with ${errors.size()} ${errors.size() == 1 ? 'error' : 'errors'} on device '${deviceLabel}' (native)."
    ]
    if (errors) result.isError = true
    return result
}

// Validate that a room NAME exists before the bypass /device/updateRoom call, and RETURN the
// matched room (canonical casing) so the caller sends the hub's own name, not the caller's raw
// casing. updateRoom takes the NAME, and /device/updateRoom SILENTLY CREATES a spurious room for
// a name it doesn't match exactly -- this case-insensitive existence check prevents that, giving
// the listed path's "Room not found" parity instead. Throws IllegalArgumentException (recorded as
// a per-field error). The getRooms()-failure case is reported distinctly from "room not found" so
// a hub-call blip isn't read as a bad name.
private Map _assertRoomExistsForBypass(room) {
    def rooms
    try {
        rooms = getRooms()
    } catch (Exception e) {
        throw new IllegalArgumentException("Unable to list rooms to resolve '${room}': ${e.message ?: e.toString()}")
    }
    def match = rooms?.find { it.name?.toString()?.toLowerCase() == room?.toString()?.toLowerCase() }
    if (match == null) {
        def names = rooms ? rooms.collect { it.name } : []
        throw new IllegalArgumentException("Room '${room}' not found.${names ? ' Available rooms: ' + names.join(', ') : ''}")
    }
    return match
}

// Read the model afresh so a prior write in the same call is not reverted by the full form.
// The shared encoder preserves nullable metadata according to the native field's semantics.
private Map _postBypassDeviceModel(deviceId, Map fieldOverrides, List errors = null) {
    return _postDeviceConfigurationForm(deviceId, fieldOverrides, errors)?.device
}

private void _requireCompleteDeviceFormSource(Map full, deviceId) {
    if (!(full?.device instanceof Map)) throw new RuntimeException("Incomplete /device/fullJson preservation source: missing device model; no form update sent")
    def d = full.device
    // All three current-firmware captures contain these keys, including nullable fields.
    def required = ["id", "version", "controllerType", "name", "label", "zigbeeId", "maxEvents", "maxStates",
        "spammyThreshold", "deviceNetworkId", "deviceTypeId", "deviceTypeReadableType", "roomId",
        "meshEnabled", "retryEnabled", "meshFullSync", "locationId", "hubId", "groupId", "tags", "defaultIcon", "notes"]
    def missing = required.findAll { !d.containsKey(it) }
    if (missing) throw new RuntimeException("Incomplete /device/fullJson preservation source: missing device fields ${missing.join(', ')}; no form update sent")
    if (d.id == null || d.id.toString() != deviceId.toString()) throw new RuntimeException("Invalid /device/fullJson preservation source: device id does not match; no form update sent")
    if (d.version == null) throw new RuntimeException("Incomplete /device/fullJson preservation source: version is unavailable; no form update sent")
    if (!(full.get("homeKitEnabled") instanceof Boolean)) throw new RuntimeException("Incomplete /device/fullJson preservation source: homeKitEnabled is unavailable or invalid; no form update sent")
    def dashboards = full.get("dashboards")
    if (!(dashboards instanceof List) || dashboards.any { row ->
        if (!(row instanceof Map) || !(row.get("selected") instanceof Boolean)) return true
        def dashboardId = row.get("id")
        return !(dashboardId instanceof Number) || dashboardId <= 0 || new BigDecimal(dashboardId.toString()).stripTrailingZeros().scale() > 0
    }) {
        throw new RuntimeException("Incomplete /device/fullJson preservation source: dashboards assignments are unavailable or invalid; no form update sent")
    }
}

private List _normalizedDeviceTags(value) {
    if (value == null) return []
    def tags = value instanceof String ? value.split(',').toList() : value
    if (!(tags instanceof List) || tags.any { !(it instanceof String) }) return null
    return tags.collect { it.trim() }.findAll { it }
}

private String _deviceConfigurationFormBody(deviceId, Map fj, Map fieldOverrides) {
    _requireCompleteDeviceFormSource(fj, deviceId)
    def d = fj.device
    def dashIds = (fj.dashboards ?: []).findAll { it?.selected }.collect { it?.id }
    def model = [
        name: d.name, label: d.label, zigbeeId: d.zigbeeId,
        maxEvents: d.maxEvents, maxStates: d.maxStates, spammyThreshold: d.spammyThreshold,
        deviceNetworkId: d.deviceNetworkId, deviceTypeId: d.deviceTypeId,
        deviceTypeReadableType: d.deviceTypeReadableType, roomId: d.roomId,
        meshEnabled: d.meshEnabled, retryEnabled: d.retryEnabled, meshFullSync: d.meshFullSync,
        homeKitEnabled: fj.homeKitEnabled, locationId: d.locationId, hubId: d.hubId,
        groupId: d.groupId, dashboardIds: dashIds, tags: d.tags,
        defaultIcon: d.defaultIcon, notes: d.notes
    ]
    if (d.id != null) { model.id = d.id; model.version = d.version; model.controllerType = d.controllerType }
    if (fieldOverrides) model.putAll(fieldOverrides)
    // Native readback verifies omission preserves null for these fields; blanks change stored values.
    // Apply overrides first so explicit empty-string clears and roomId=0 remain in the form.
    ["groupId", "controllerType", "roomId", "notes", "tags", "zigbeeId", "defaultIcon"].each { key ->
        if (model.get(key) == null) model.remove(key)
    }
    def enc = { v ->
        if (v == true) return "on"
        if (v == null) return ""
        if (v instanceof List) return v.collect { it?.toString() }.join(",")
        return v.toString()
    }
    return model.collect { k, v -> "${java.net.URLEncoder.encode(k.toString(), 'UTF-8')}=${java.net.URLEncoder.encode(enc(v), 'UTF-8')}" }.join("&")
}

private Map _postDeviceConfigurationForm(deviceId, Map fieldOverrides, List errors = null) {
    def recoveryErrors = errors == null ? [] : errors
    def fj = _fetchDeviceFullJson(deviceId)
    if (fj?.device == null) {
        throw new RuntimeException("Could not read the device model from /device/fullJson to rebuild the /device/update form; native device id ${deviceId} was unavailable or could not be verified")
    }
    def body = _deviceConfigurationFormBody(deviceId, fj, fieldOverrides)
    hubInternalPostFormRaw("/device/update", body)
    def updated = _fetchDeviceFullJson(deviceId)
    if (!(updated?.device instanceof Map)) return updated
    def restore = [:]
    ["label", "name", "deviceNetworkId"].each { property ->
        def original = fj.device.get(property)
        boolean intentionalClear = fieldOverrides.containsKey(property) && !fieldOverrides.get(property)
        if (original && !intentionalClear && updated.device.containsKey(property) && !updated.device.get(property)) {
            restore.put(property, original)
        }
    }
    if (restore) {
        // Restore known identity once while carrying changes already confirmed in the fresh model.
        try {
            def fresh = _fetchDeviceFullJson(deviceId)
            def restoreBody = _deviceConfigurationFormBody(deviceId, fresh, restore)
            hubInternalPostFormRaw("/device/update", restoreBody)
        } catch (Exception recoveryError) {
            // The hub may have accepted the recovery despite a lost acknowledgement.
            mcpLog("error", "device", "Native identity restoration failed for device ${deviceId}: ${recoveryError.message}")
        }
        def recovered = _fetchDeviceFullJson(deviceId)
        if (recovered?.device instanceof Map) updated = recovered
        restore.each { property, original ->
            if (!(recovered?.device instanceof Map) || recovered.device.get(property)?.toString() != original.toString()) {
                recoveryErrors << [property: property, stage: 'restore',
                    error: "Device-edit form blanked ${property}; native restoration could not be confirmed. Verify and re-set ${property} before retrying."]
            }
        }
    }
    if (errors == null && recoveryErrors) throw new RuntimeException(recoveryErrors.collect { it.error }.join(' '))
    return updated
}

def toolCreateDevice(args) {
    // Resolve the native driver type before choosing one creation endpoint; a failed mutation
    // cannot safely be retried through another endpoint because its device may already exist.
    // This creates a device without radio pairing -- useful for LAN/integration/cloud and
    // software/component drivers that have no pairing flow. Radio drivers created this way
    // are orphan shells (no node), so we warn. MCP-managed virtual devices have their own
    // tool (hub_manage_virtual_device); this is the broader catalog path.
    if (args?.confirm != true) {
        throw new IllegalArgumentException("confirm=true is required to create a device.")
    }
    def typeId = args?.deviceTypeId
    if (typeId == null || typeId.toString().trim() == "") {
        throw new IllegalArgumentException("deviceTypeId is required -- the driver-type id from hub_list_drivers(include='all') (the 'id' field).")
    }
    typeId = typeId.toString().trim()

    String driverType
    try {
        def catalogText = hubInternalGet('/device/drivers')
        def catalog = catalogText ? new groovy.json.JsonSlurper().parseText(catalogText) : null
        if (!(catalog instanceof Map) || !(catalog.drivers instanceof List)) {
            return [success: false, isError: true, error: 'The native driver catalog is unavailable or invalid; no device create request sent.',
                    note: "Verify the deviceTypeId via hub_list_drivers(include='all')."]
        }
        def matches = catalog.drivers.findAll { row -> row instanceof Map && row.id?.toString() == typeId }
        if (matches.size() != 1 || !(matches[0].type in ['sys', 'usr'])) {
            return [success: false, isError: true, error: "Could not resolve one supported native driver type for ${typeId}; no device create request sent.",
                    note: "Verify the deviceTypeId via hub_list_drivers(include='all')."]
        }
        driverType = matches[0].type.toString()
    } catch (Exception e) {
        return [success: false, isError: true, error: 'Hub call failed reading the driver catalog; no device create request sent.',
                note: "Verify the deviceTypeId via hub_list_drivers(include='all')."]
    }

    def resp
    try {
        def respText = driverType == 'usr'
            ? hubInternalGet('/device/createVirtual', [deviceTypeId: typeId], 30)
            : hubInternalGet("/device/sysDriverByIdJson/${java.net.URLEncoder.encode(typeId, 'UTF-8')}", null, 30)
        // Parse INSIDE the try: a 200 carrying an HTML/login body (not JSON) makes parseText
        // throw, and it must return the same structured runtime-error shape as a fetch failure
        // rather than escaping as an unstructured tool error.
        resp = respText ? new groovy.json.JsonSlurper().parseText(respText) : null
    } catch (Exception e) {
        return [success: false, isError: true, error: "Hub call failed creating device from driver-type ${typeId}: ${e.message}",
                note: "Verify the deviceTypeId via hub_list_drivers(include='all')."]
    }
    // Vue's /device/createVirtual response identifies success by deviceId and omits success.
    if (resp?.deviceId != null && resp?.success == null) resp.put("success", true)
    if (resp?.success != true || resp?.deviceId == null) {
        return [success: false, isError: true, error: resp?.errorMessage ?: "Hub did not create a device for driver-type ${typeId}",
                note: "Verify the deviceTypeId via hub_list_drivers(include='all')."]
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

    def appliedLabel = null
    if (args?.label != null && args.label.toString().trim()) {
        def wantLabel = args.label.toString()
        // Why the dedicated GET did not apply the label (non-"true" body or a thrown 404); drives the
        // fallback and, if that also fails, the warning text.
        def labelFailNote = null
        try {
            def labelResult = hubInternalGet("/device/updateLabel", [deviceId: newId, label: wantLabel])
            if (labelResult?.toString()?.trim()?.toLowerCase() == "true") {
                def observed = _fetchDeviceFullJson(newId)?.device
                if (observed?.label?.toString() == wantLabel) appliedLabel = wantLabel
                else labelFailNote = 'native readback did not confirm the requested label'
            } else {
                labelFailNote = "hub returned '${labelResult?.toString()?.take(120)}'"
            }
        } catch (IllegalStateException guardErr) {
            throw guardErr   // ?-in-path guard: a coding bug, never a fallback trigger
        } catch (Exception e) {
            // The dedicated GET /device/updateLabel setter 404s on some firmwares (e.g. 2.5.0.157).
            labelFailNote = "${e.message ?: e.toString()}"
        }
        // Fallback, mirroring the setShowOnHome/setDefaultCurrentState dedicated-GET -> /device/update
        // pattern: if the dedicated setter did not apply the label, re-POST the wholesale device-edit
        // form with a label override. _postBypassDeviceModel is a general "re-fetch a FRESH fullJson +
        // re-POST the COMPLETE /device/update form with field overrides" helper (despite its bypass-era
        // name) -- the fresh re-read carries every other field, so nothing is blanked. The just-created
        // device's fullJson was already proven readable (the `info` read above). Non-fatal: a warning
        // is emitted only if BOTH the dedicated GET and this wholesale fallback fail to apply the label.
        if (appliedLabel == null) {
            try {
                def recoveryErrors = []
                def vd = _postBypassDeviceModel(newId, [label: wantLabel], recoveryErrors)
                warnings.addAll(recoveryErrors.collect { it.error })
                if (vd?.label?.toString() == wantLabel) {
                    appliedLabel = wantLabel
                } else {
                    warnings << "Device created but label '${args.label}' could not be applied (${labelFailNote}; wholesale /device/update read back '${vd?.label}'). Set it with hub_update_device(label=...)."
                    mcpLog("warn", "device", "hub_create_device: label '${wantLabel}' not applied to ${newId} (${labelFailNote}; /device/update form read back '${vd?.label}')")
                }
            } catch (Exception e2) {
                warnings << "Device created but label '${args.label}' could not be applied (${labelFailNote}; fallback threw: ${e2.message ?: e2.toString()}). Set it with hub_update_device(label=...)."
                mcpLog("warn", "device", "hub_create_device: label '${wantLabel}' fallback /device/update threw for ${newId}: ${e2.message ?: e2.toString()}")
            }
        }
    }

    def readable = (info?.deviceTypeReadableType ?: "").toString().toLowerCase()
    def ctype = (info?.controllerType ?: "").toString().toUpperCase()
    if (ctype in ["ZWV", "ZGB"] || readable.contains("z-wave") || readable.contains("zwave") || readable.contains("zigbee") || readable.contains("matter")) {
        warnings << "This is a radio-type driver. Creating it here makes a non-functional orphan shell (no paired node). Add real Z-Wave/Zigbee/Matter devices by PAIRING (hub_call_zwave / hub_call_zigbee / hub_call_matter), then delete this shell with hub_delete_device."
    }

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
        note: "Set room/preferences/showOnHome with hub_update_device once the device is selectable, or delete it with hub_delete_device."
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
        warnings << "HAS RECENT EVENTS: Last event was ${lastEvent.name}=${lastEvent.value} at ${lastEvent.date}"
    }

    // SDK rollback reference; audit reads now use fresh native data for every device.
    // try {
    //     def selectedDevice = findDevice(deviceId)
    //     if (selectedDevice) {
    //         def lastActivity = selectedDevice.lastActivity
    //         if (lastActivity) {
    //             def hoursAgo = (Math.round((now() - lastActivity.time) / 3600000.0 * 10) / 10.0) as double
    //             if (hoursAgo < 24) {
    //                 warnings << "ACTIVE DEVICE: Last activity was ${hoursAgo} hours ago at ${lastActivity.format("yyyy-MM-dd'T'HH:mm:ss")}. This device may still be functional."
    //             }
    //         }
    //         def recentEvents = selectedDevice.events(max: 3)
    //         if (recentEvents && recentEvents.size() > 0) {
    //             def lastEvent = recentEvents[0]
    //             warnings << "HAS RECENT EVENTS: Last event was ${lastEvent.name}=${lastEvent.value} at ${lastEvent.date?.format("yyyy-MM-dd'T'HH:mm:ss")}"
    //         }
    //     }
    // } catch (Exception e) {
    //     // Device not in selected list or events unavailable — skip
    // }

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

    // Step 7: Execute force delete via hub internal API
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
    def fromId = args?.from_device_id?.toString()?.trim()
    def toId = args?.to_device_id?.toString()?.trim()
    if (!fromId) throw new IllegalArgumentException("from_device_id is required")
    if (!toId) throw new IllegalArgumentException("to_device_id is required")
    if (fromId == toId) throw new IllegalArgumentException("from_device_id and to_device_id must be different devices")
    // SDK handle-only access checks retained for rollback:
    // def fromDevice = findDevice(fromId)
    // if (!fromDevice) throw new IllegalArgumentException("Device not found: ${fromId}")
    // def toDevice = findDevice(toId)
    // if (!toDevice) throw new IllegalArgumentException("Device not found: ${toId}")
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

    // Before-count is the verification baseline AND the reported blast radius.
    def beforeCount = _deviceSwapDependentCount(fromId)
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
            mcpLog("warn", "device-swap", "from_device_id ${fromId} not among ${oldDevOptions.size()} swappable oldDev option(s) -- closing instance ${appId}")
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: "The hub's Swap Device tool does not offer device ${fromId} as swappable.",
                    oldDevOptionCount: oldDevOptions.size(),
                    note: "Swap Device only offers devices that are referenced by at least one app AND not owned as another app's child/component device. MCP-created virtual devices (hub_manage_virtual_device) are child devices and are always ineligible. Pick a free-standing from_device_id, or use hub_list_device_dependents(deviceId=${fromId}) to confirm it is referenced by an app. Nothing was swapped; the transient instance was closed."]
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
            mcpLog("warn", "device-swap", "to_device_id ${toId} not among ${options.size()} compatible replacement(s) for ${fromId} -- closing instance ${appId}")
            _deviceSwapCleanup(appId)
            return [success: false,
                    error: options.isEmpty()
                        ? "The hub offered NO compatible replacement devices for ${fromId}."
                        : "Device ${toId} is not a compatible replacement for ${fromId} -- the hub did not offer it.",
                    compatibleOptions: options.take(30),
                    compatibleOptionCount: options.size(),
                    note: "The Swap Device tool only offers devices with compatible capabilities, and excludes devices owned as another app's child/component device -- to_device_id may be an app child device (every MCP-created virtual device is one). Pick a to_device_id from compatibleOptions${options.size() > 30 ? " (showing first 30 of ${options.size()})" : ""}, or choose free-standing replacement hardware of the same device class. Nothing was swapped."]
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

        // Post-click: the swap action usually removes the transient instance
        // itself, but a transient read failure on the verify re-fetch is
        // indistinguishable from "instance gone", so the fetch only informs
        // LOGGING and never gates cleanup. The delete ALWAYS runs: it is
        // idempotent/harmless against an already-reaped instance, and
        // _deviceSwapCleanup swallows + logs its own failure -- skipping the
        // delete on a misread is the only way to leak an instance untraced.
        def instanceGone = false
        try {
            _rmFetchConfigJson(appId, "mainPage")
        } catch (Exception e) {
            instanceGone = true
            mcpLog("warn", "device-swap", "post-click verify fetch threw ${e.class.simpleName}: ${e.message} -- treating instance as present for cleanup (the delete is harmless if it already self-removed)")
        }
        _deviceSwapCleanup(appId)

        def afterCount = _deviceSwapDependentCount(fromId)
        if (beforeCount != null && afterCount != null && beforeCount > 0 && afterCount >= beforeCount) {
            return [success: false,
                    error: "Swap action was clicked but ${afterCount} app(s) still reference device ${fromId} (was ${beforeCount}).",
                    note: "The hub accepted the click but the dependents count did not drop. Inspect with hub_list_device_dependents(deviceId=${fromId}) before retrying -- some references may not be swappable."]
        }
        mcpLog("info", "device-swap", "Swap ${fromId} -> ${toId} complete; dependents ${beforeCount} -> ${afterCount}; transient instance ${instanceGone ? 'self-removed (verify fetch threw)' : 'survived the click'}; cleanup delete issued either way")
        def result = [success: true,
                      swapped: [from: fromId, to: toId],
                      verified: (beforeCount != null && afterCount != null),
                      note: "Every app that referenced ${fromId} now uses ${toId}. Verify with hub_list_device_dependents(deviceId=${toId}) and spot-check the most critical automations."]
        if (beforeCount != null) result.appsRewired = beforeCount
        if (afterCount != null) result.remainingDependents = afterCount
        if (beforeCount == null || afterCount == null) {
            result.note += " Before/after dependent counts could not be read from /device/fullJson, so the count verification is degraded."
        }
        return result
    } catch (Exception e) {
        mcpLogError("device-swap", "hub_call_device_swap ${fromId} -> ${toId} failed", e)
        _deviceSwapCleanup(appId)
        return [success: false,
                error: "Device swap failed: ${e.message}",
                note: "The swap may not have committed -- verify with hub_list_device_dependents(deviceId=${fromId}). The transient Swap Device instance was closed."]
    }
}

def toolCallDeviceReplace(args) {
    def oldId = args?.old_device_id?.toString()?.trim()
    if (!oldId) throw new IllegalArgumentException("old_device_id is required")
    def newId = args?.new_device_id?.toString()?.trim()
    if (args?.list_options != true) {
        if (!newId) throw new IllegalArgumentException("new_device_id is required to replace (or pass list_options=true to list compatible candidates)")
        if (oldId == newId) throw new IllegalArgumentException("old_device_id and new_device_id must be different devices")
    }
    _requireDeviceToolAccess(oldId)
    if (newId) _requireDeviceToolAccess(newId)

    // Read-only mode: list the hub's compatible replacement candidates (no write, no confirm).
    if (args?.list_options == true) {
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
                        note: "Verify old_device_id (from hub_list_devices) and Hub Security credentials; the hub did not return a candidate list."]
            }
            def allowedIds = _bypassEnabled() ? null : (((selectedDevices ?: []) + (getChildDevices() ?: [])).collect { it.id?.toString() } as Set)
            def candidates = allowedIds == null ? parsed : parsed.findAll { it instanceof Map && allowedIds.contains(it.id?.toString()) }
            def options = candidates.collect { [id: it?.id?.toString(), name: it?.name, deviceTypes: it?.deviceTypes] }
            return [success: true, listOptions: true, oldDeviceId: oldId, options: options, optionCount: options.size(),
                    note: options ? "Pick an option's id as new_device_id, then call again with confirm=true to replace." : "No compatible replacement devices found within current MCP device access."]
        } catch (Exception e) {
            mcpLogError("device-replace", "getReplacementOptions for ${oldId} failed", e)
            return [success: false, error: "Could not read replacement options: ${e.message}",
                    note: "Verify old_device_id (from hub_list_devices) and Hub Security credentials."]
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
                note: "new_device_id must be capability-compatible -- list valid candidates with list_options=true. Nothing was replaced."]
    } catch (Exception e) {
        mcpLogError("device-replace", "replace ${oldId} -> ${newId} failed", e)
        return [success: false, error: "Device replace failed: ${e.message}",
                note: "Verify the device ids and Hub Security credentials. The replace may not have committed -- check hub_get_device(deviceId=${oldId})."]
    }
}

private Integer _deviceSwapDependentCount(String deviceId) {
    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (!responseText) return null
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof Map)) return null
        def appsUsing = (parsed.appsUsing instanceof List) ? parsed.appsUsing : []
        try {
            return (parsed.appsUsingCount != null) ? (parsed.appsUsingCount as Integer) : appsUsing.size()
        } catch (NumberFormatException ignored) {
            return appsUsing.size()
        }
    } catch (Exception e) {
        mcpLog("warn", "device-swap", "dependent-count read failed for device ${deviceId} (${e.message}) -- before/after verification degraded")
        return null
    }
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
private void _deviceSwapCleanup(Integer appId) {
    try {
        hubInternalGetRaw("/installedapp/delete/${appId}")
        mcpLog("info", "device-swap", "Transient Swap Device instance ${appId} deleted")
    } catch (Exception e) {
        // error (not warn) so the orphan-leak case is queryable at the default
        // error-only MCP log level.
        mcpLogError("device-swap", "Delete of Swap Device instance ${appId} failed -- a leftover transient instance is harmless but can be removed from the hub's Apps list", e)
    }
}

def _getAllToolDefinitions_partDevices() {
    return [
        // Device Tools
        [
            name: "hub_list_devices",
            description: """List all devices available to MCP with current states. format='context' returns a token-cheap plain-text house snapshot (mode + one line per device)[[FLAT_TRIM]] -- the best first call for broad "what's going on / what's in this house" questions[[/FLAT_TRIM]].

DEVICE AUTHORIZATION: Exact name match -> use directly. No exact match -> suggest similar, ASK USER before using. NEVER control unconfirmed devices (HVAC/locks risk). Report tool failures; don't silently fall back to existing devices.

[[FLAT_TRIM]]
Use detailed=false for discovery; detailed=true with limit=20-30. Sequential calls only.
[[/FLAT_TRIM]]
Call `hub_get_tool_guide(section='performance_devices')` for response-shape details, filter/projection semantics, and field-name reference.""",
            inputSchema: [
                type: "object",
                properties: [
                    detailed: [type: "boolean", description: "Include native device details (capabilities, reported attributes, commands).[[FLAT_TRIM]] WARNING: Resource-intensive for large device counts. Unset or cleared attributes may be absent.[[/FLAT_TRIM]]"],
                    offset: [type: "integer", description: "Start from device at this index (0-based). Use for pagination.", default: 0],
                    limit: [type: "integer", description: "Maximum number of devices to return.[[FLAT_TRIM]] Recommended: 20-30 for detailed=true, higher values may slow hub.[[/FLAT_TRIM]]", default: 0],
                    filter: [type: "string", description: "Server-side filter (applied before pagination). 'all' (default) | 'enabled' | 'disabled' | 'stale:<hours>' | 'virtual'[[FLAT_TRIM]] (this MCP app's own virtual devices; use to find their IDs/DNIs)[[/FLAT_TRIM]]."],
                    labelFilter: [type: "string", description: "Case-insensitive substring match against device label; falls back to name for devices without a label set."],
                    capabilityFilter: [type: "string", description: "Case-insensitive exact match against capability name. Capability names are camelCase (e.g. 'ColorControl')."],
                    roomFilter: [type: "string", description: "Case-insensitive exact match against the device's assigned room name."],
                    onlyOn: [type: "boolean", description: "true = only devices whose switch currently reads 'on'.[[FLAT_TRIM]] \"What's on right now\" in one call; false is a no-op.[[/FLAT_TRIM]]"],
                    changedSince: [type: ["string", "integer"], description: "Only devices with activity at/after this timestamp: epoch ms, or ISO-8601 with a numeric offset (e.g. 2026-06-23T10:00:00Z; -0600 and -06:00 both accepted -- offset-less or date-only forms are rejected).[[FLAT_TRIM]] A returned lastActivity value round-trips. Inverse of filter='stale:<hours>'; devices with no readable lastActivity are excluded. Epoch-ms input echoes back as canonical ISO.[[/FLAT_TRIM]]"],
                    attributeNames: [type: "array", items: [type: "string"], description: "format='context' only (rejected on other formats): which attributes to show per device line[[FLAT_TRIM]], replacing the default set. Empty array = the default set[[/FLAT_TRIM]]."],
                    format: [type: "string", enum: ["summary", "detailed", "ids", "context"], description: "Response shape. 'summary' (default) = standard fields + currentStates. 'detailed' = capabilities/attributes/commands. 'ids' = flat array of device ID integers (cheapest, ignores fields arg). 'context' = plain-text house snapshot in `summary`[[FLAT_TRIM]] (mode + 'Label (id, room) - capabilities; attr=value' lines; page size 50 unless limit set; ignores fields arg)[[/FLAT_TRIM]]."],
                    fields: [type: "array", items: [type: "string"], description: "Field projection: only include named fields in each device object. Call `hub_get_tool_guide(section='performance_devices')` for valid field names and projection semantics."],
                    cursor: [type: "string", description: "Opt-in opaque cursor (alias to offset). Pass \"\" for the first page (page size 50 when limit is unset), then iterate nextCursor."],
                    scope: [type: "string", enum: ["authorized", "all"], description: "Which devices to list. 'authorized' (default) = selected devices plus MCP-owned children, or all devices with bypass enabled (full detail/currentStates). 'all' = EVERY device on the hub, each tagged with current effective access as mcpAuthorized true/false."]
                ]
            ]
        ],
        [
            name: "hub_get_device",
            description: """Inspect one device. Default summary gives capabilities, current attributes and commands. Use mode='configuration' before updates to discover editable fields, saved preferences, types/defaults/options and driver information; mode='details' exposes configuration and other device information by section. Read status distinguishes unavailable information from unset values.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices, e.g. \"42\""],
                    mode: [type: "string", enum: ["summary", "configuration", "details"], default: "summary",
                           description: "summary: concise capabilities/attributes/commands; configuration: valid editable fields and preference definitions/current values; details: all available information in selectable sections."],
                    sections: [type: "array", items: [type: "string", enum: _deviceDetailSections()],
                               description: "details mode only: non-empty section selection. Omit for all sections. Large histories remain reachable through read-tool references."],
                    fields: [type: "array", items: [type: "string"], description: "configuration/details only: omit for all values, [] for availableFields name discovery, or select exact field/preference names. For commands/jobs use row indices from availableFields; attributes also accepts individual attribute names."],
                    cursor: [type: "string", description: "Oversized reads return contentFormat=json-fragment and nextCursor. Repeat with unchanged arguments within five minutes, concatenate content, then parse JSON. Expired/evicted snapshots require restarting. Prefer fields/sections for smaller reads."]
                ],
                required: ["deviceId"]
            ]
        ],
        [
            name: "hub_get_device_attribute",
            description: """Get a device attribute's current value, or block-poll until it reaches an expected value.

[[FLAT_TRIM]]
One-shot read by default (deviceId + attribute). Provide expectedValue or expectedValues (exactly one) to block-poll until currentValue matches, returning immediately on match or when timeoutMs elapses.
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: ["string", "integer"], description: "Device ID from hub_list_devices."],
                    deviceIds: [type: "array", items: [type: ["string", "integer"]], description: "Array of device IDs for a multi-device poll (mutually exclusive with deviceId; max 20)."],
                    mode: [type: "string", enum: ["any", "all"], description: "Multi-device aggregate.", default: "all"],
                    attribute: [type: "string", description: "Attribute name."],
                    expectedValue: [type: "string", description: "If set, block-poll until currentValue matches per comparator (enables poll mode). Single value, e.g. \"72\".[[FLAT_TRIM]] Provide exactly one of expectedValue or expectedValues.[[/FLAT_TRIM]]"],
                    expectedValues: [type: "array", items: [type: "string"], description: "If set, block-poll until currentValue matches per comparator (enables poll mode). For between, two numeric bounds [low, high].[[FLAT_TRIM]] Provide exactly one of expectedValue or expectedValues.[[/FLAT_TRIM]]"],
                    comparator: [type: "string", enum: ["eq", "ne", "gt", "gte", "lt", "lte", "between"], description: "Match operator. Default eq (value in the expected set).", default: "eq"],
                    stableForMs: [type: "integer", description: "Debounce: the match must hold continuously for this many MILLISECONDS before converging. Default 0 (first match).", default: 0, minimum: 0],
                    timeoutMs: [type: "integer", description: "Poll mode only: max wait in MILLISECONDS. Default 5000, min 100, max 60000. Requires expectedValue/expectedValues — passing a timeout without one is rejected.", default: 5000, minimum: 100, maximum: 60000],
                    pollIntervalMs: [type: "integer", description: "Poll mode: re-check interval in MILLISECONDS. Default 200, min 50, max 5000. Clamped to timeoutMs if larger.[[FLAT_TRIM]] (hub_call_device_command's waitFor defaults to 250 instead: a post-command poll follows a write, so wider spacing reduces read contention.)[[/FLAT_TRIM]]", default: 200, minimum: 50, maximum: 5000]
                ],
                required: ["attribute"]
            ]
        ],
        [
            name: "hub_call_device_command",
            description: """Send a command (e.g. on, off, setLevel) to one device, or to SEVERAL at once via `commands`. Use to actuate or control a device; for read-only checks use hub_get_device_attribute instead.

For more than one device pass `commands` (max 20) rather than calling this repeatedly: one round trip instead of N. Entries are independent -- one failing entry does not stop the others; check results[] for per-entry success. Confirm a batch with hub_get_device_attribute's deviceIds form.[[FLAT_TRIM]] The round trip, not the hub actuating the device, is most of the wall-clock time, so this is the biggest win available on a multi-device intent; mixed devices and mixed commands go in one batch. For a set commanded repeatedly a group or scene is better still - one device to command, with the hub fanning out; `commands` is for the ad-hoc set nobody defined in advance.[[/FLAT_TRIM]]

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
                    waitFor: [type: "object", description: "Optional: after firing the command, block-poll an attribute for an expected value; check waitFor.converged to confirm the resulting state.[[FLAT_TRIM]] The native `state` snapshot is read after dispatch and any poll. Without waitFor it is an immediate read that may show the prior or resulting value.[[/FLAT_TRIM]]", properties: [
                        attribute: [type: "string", description: "Attribute to poll until it converges, e.g. \"switch\". Must be a supported attribute of the device."],
                        expectedValue: [type: "string", description: "Awaited value: eq/ne in-set, or gt/gte/lt/lte numeric threshold."],
                        expectedValues: [type: "array", items: [type: "string"], description: "Awaited set: eq/ne value list (OR), or between's two bounds [low, high]."],
                        comparator: [type: "string", enum: ["eq", "ne", "gt", "gte", "lt", "lte", "between"], description: "Match operator, as on hub_get_device_attribute. Default eq.", default: "eq"],
                        stableForMs: [type: "integer", description: "Debounce ms; match must hold this long before converging. Default 0, < timeoutMs.", default: 0, minimum: 0],
                        timeoutMs: [type: "integer", description: "Max wait in MILLISECONDS. Default 5000, min 100, max 30000. BLOCKS a hub thread for the full timeout, so keep it tight.", default: 5000, minimum: 100, maximum: 30000],
                        pollIntervalMs: [type: "integer", description: "Re-check interval in MILLISECONDS. Default 250, min 50, max 5000. Clamped to timeoutMs if larger.", default: 250, minimum: 50, maximum: 5000]
                    ], required: ["attribute"]]
                ]

                // No `required` array: the two forms require different arguments (deviceId+command,
                // or commands), which JSON Schema cannot express here without oneOf. Enforced at
                // runtime instead, with a message naming the conflict.
            ]
        ],
        [
            name: "hub_list_device_events",
            description: """Get event history for a device, an app or rule (the automation events it emitted), or the location.

[[FLAT_TRIM]]
Default: most-recent events for a device (deviceId + optional limit).
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID. Mutually exclusive with appId; omit both for location-level events (mode/HSM/hub variable)."],
                    appId: [type: "integer", description: "Installed-app ID for per-app events (what the app/rule emitted). Mutually exclusive with deviceId."],
                    hoursBack: [type: "integer", description: "If set, return up to this many hours of history (max 168 = 7 days) instead of just the most recent events.[[FLAT_TRIM]] Ignored when since is given.[[/FLAT_TRIM]]"],
                    since: [type: ["string", "integer"], description: "Absolute window start -- return only events AFTER this timestamp; ISO-8601 with a numeric offset (-0600 or -06:00; e.g. 2026-06-23T10:00:00.000-0600) or epoch milliseconds.[[FLAT_TRIM]] This is the format this tool emits in `date`/`sinceTimestamp`. Takes precedence over hoursBack; a future timestamp yields an empty list.[[/FLAT_TRIM]]"],
                    attribute: [type: "string", description: "Event-name filter. Device: an attribute (e.g. 'switch').[[FLAT_TRIM]] Location: 'mode', 'hsmStatus', 'hsmAlert', or a hub-variable name.[[/FLAT_TRIM]]"],
                    limit: [type: "integer", description: "Max events to return. Recent mode default 10; history mode default 100 (max 500).[[FLAT_TRIM]] Higher values may slow hub.[[/FLAT_TRIM]]", default: 10]
                ]
            ]
        ],
        [
            name: "hub_update_device",
            description: """Update device configuration and driver preferences. Read hub_get_device(mode='configuration') first for current values, valid options, and availability.

Only modify devices user explicitly requested. Pre-flight: read configuration, choose the exact patch, obtain the update_device guide key. Writes require Write master. Identity, driver, dashboard, mesh and assistant changes additionally require confirm=true and a backup less than 24 hours old. Saved preferences do not invoke device commands. Per-property changes/errors report partial success; inspect readback before retrying.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "The device ID to update (from hub_list_devices or hub_list_devices(filter='virtual'))"],
                    label: [type: "string", description: "New display label for the device"],
                    name: [type: "string", description: "New device name"],
                    deviceNetworkId: [type: "string", description: "New device network ID (must be unique across all hub devices)"],
                    room: [type: "string", description: "Existing room name (case-insensitive exact match); empty string removes the assignment."],
                    enabled: [type: "boolean", description: "Set to true to enable or false to disable the device"],
                    dataValues: [type: "object", description: "Key-value pairs to set in the device's Data section. Example: {\"firmware\": \"1.2.3\", \"model\": \"ABC\"}",
                        additionalProperties: [type: "string"]],
                    preferences: [type: "object", description: "Declared driver preferences; discover names/types/options using configuration mode. Use {type,value} or a compatible nonblank bare value. Use {clear:true} to remove an optional saved setting; omit a name to preserve it. Null, blank strings, empty arrays and clear combined with value are rejected. Booleans, numbers and nonempty multiple-enum arrays retain native types. If configuration reports multiple:null for an unset enum, check its driver declaration or pre-clear metadata and supply {value:...,multiple:true/false}; cardinality is never guessed."],
                    showOnHome: [type: "boolean", description: "Show this device on the hub Home page.[[FLAT_TRIM]] Also counts it in the quick status-bar summaries (climate/lights/locks/etc.)[[/FLAT_TRIM]]"],
                    defaultCurrentState: [type: "string", description: "Which attribute appears in the Status column[[FLAT_TRIM]] (Devices/Rooms pages)[[/FLAT_TRIM]], e.g. \"switch\"; \"\" selects None."],
                    tags: [type: "array", description: "Free-form device tags; REPLACES the full set ([] clears all).", items: [type: "string"]],
                    deviceTypeId: [type: "integer", minimum: 1, description: "Driver selection ID from configuration driver options. Changes behavior; requires confirm and recent backup."],
                    zigbeeId: [type: "string", description: "Existing Zigbee device identity; unavailable on components or linked devices. Requires confirm and recent backup."],
                    notes: [type: "string", description: "Device note; empty string clears it."],
                    maxEvents: [type: "integer", minimum: 1, maximum: 2000, description: "Stored events per event type. Reducing retention can remove older history."],
                    maxStates: [type: "integer", minimum: 1, maximum: 2000, description: "Stored states per attribute. Reducing retention can remove older history."],
                    spammyThreshold: [type: "integer", minimum: 100, maximum: 2000, description: "Events per hour that trigger the too-many-events alert."],
                    defaultIcon: [type: "string", description: "Native custom icon identifier; empty string removes the override."],
                    dashboardIds: [type: "array", items: [type: "integer", minimum: 1], description: "Replace native dashboard assignments using configuration option IDs; [] clears. Requires confirm and recent backup."],
                    meshEnabled: [type: "boolean", description: "Share through Hub Mesh when native selection is available. Requires confirm and recent backup."],
                    retryEnabled: [type: "boolean", description: "Enable native command retry when available for this device."],
                    meshFullSync: [type: "boolean", description: "Regularly sync a linked device when Hub Mesh refresh is enabled. Requires confirm and recent backup."],
                    homeKitEnabled: [type: "boolean", description: "Native Apple HomeKit assignment when supported/enabled. Requires confirm and recent backup."],
                    amazonAlexaEnabled: [type: "boolean", description: "Native Amazon Alexa assignment when installed and supported. Requires confirm and recent backup."],
                    googleHomeEnabled: [type: "boolean", description: "Native Google Home assignment when installed and supported. Requires confirm and recent backup."],
                    confirm: [type: "boolean", description: "Explicit approval for driver, identity, dashboard, mesh or assistant changes; also requires a hub backup within 24 hours."]
                ],
                required: ["deviceId"]
            ]
        ],
        // Device Admin
        [
            name: "hub_delete_device",
            description: """⚠️ MOST DESTRUCTIVE: Permanently delete a device. NO UNDO. For ghost/orphaned/stuck devices only.

PRE-FLIGHT: 1) Backup <24h 2) hub_get_device to verify 3) Warn user 4) Z-Wave/Zigbee → exclusion first 5) Get confirmation
Device + history lost, automations break. Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "The device ID to permanently delete"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created, device was verified, and user explicitly approved the deletion."]
                ],
                required: ["deviceId", "confirm"]
            ]
        ],
        [
            name: "hub_call_device_swap",
            description: """⚠️ DESTRUCTIVE: Swap a device — replace from_device_id with to_device_id across ALL apps and rules that reference it, in one operation.

Pre-flight (mandatory): 1) hub backup <24h (hub_create_backup); 2) preview the blast radius with hub_list_device_dependents(deviceId=from_device_id) — every app listed gets rewired; 3) confirm with the user.""",
            inputSchema: [
                type: "object",
                properties: [
                    from_device_id: [type: "string", description: "Device ID whose references will be replaced everywhere (from hub_list_devices)."],
                    to_device_id: [type: "string", description: "Replacement device ID. Must be capability-compatible — on mismatch the error lists the compatible candidates."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms a hub backup exists (<24h) and the user approved the swap."]
                ],
                required: ["from_device_id", "to_device_id", "confirm"]
            ]
        ],
        [
            name: "hub_call_device_replace",
            description: """⚠️ DESTRUCTIVE: Replace a device's hardware, KEEPING its id + all app/rule references.

[[FLAT_TRIM]]
Two-step: list_options=true to list candidates, then apply with confirm=true.
[[/FLAT_TRIM]]
Pre-flight: backup <24h (hub_create_backup) + user OK.""",
            inputSchema: [
                type: "object",
                properties: [
                    old_device_id: [type: "string", description: "Device to replace; its id is preserved."],
                    new_device_id: [type: "string", description: "Compatible replacement device."],
                    list_options: [type: "boolean", description: "Read-only: list compatible replacement candidates (no confirm)."],
                    confirm: [type: "boolean", description: "REQUIRED to apply (omit for list_options): confirms backup <24h + user approval."]
                ],
                required: ["old_device_id"]
            ]
        ],
        [
            name: "hub_create_device",
            description: """Create a device from a driver TYPE id. Requires Write master + confirm.[[FLAT_TRIM]] For LAN/integration/software drivers, NOT radio pairing (Z-Wave/Zigbee/Matter); for virtual devices use hub_manage_virtual_device.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceTypeId: [type: "string", description: "Driver-type id to instantiate (the 'id' from hub_list_drivers(include='all'))."],
                    label: [type: "string", description: "Optional display label for the new device."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to create the device."]
                ],
                required: ["deviceTypeId", "confirm"]
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
    ]
}

def _readOnlyToolNames_partDevices() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Device introspection
        "hub_list_devices", "hub_get_device", "hub_get_device_attribute", "hub_list_device_events",
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
        hub_list_devices: [title: "List Devices", summary: "List accessible devices with filtering, pagination, and field projection."],
        hub_get_device: [title: "Get Device Details", summary: "Inspect a device summary, configuration and preferences, or detailed information by section."],
        hub_get_device_attribute: [title: "Get Device Attribute", summary: "Read one attribute value, optionally waiting until it matches an expected value."],
        hub_list_device_events: [title: "List Device Events", summary: "Recent events for a device or app, or location events when neither is given."],
        hub_call_device_command: [title: "Send Device Command", summary: "Send a command like on, off, or setLevel to one device, or up to 20 in one call."],
        hub_call_device_swap: [title: "Swap Device", summary: "Replace a device across all apps and rules that reference it, in one operation."],
        hub_call_device_replace: [title: "Replace Device Hardware", summary: "Re-point a device to replacement hardware, keeping its id and all references."],
        hub_update_device: [title: "Update Device Properties", summary: "Update applicable device identity, preferences, display, driver, history limits, or integration assignments."],
        hub_create_device: [title: "Create Device From Driver", summary: "Create a device from a driver-type id (LAN/integration/software drivers; not radio hardware)."],
        hub_get_compatible_devices: [title: "Search Compatible Devices", summary: "Search Hubitat's compatible-device catalog for models and pairing/reset instructions."],
        hub_delete_device: [title: "Delete Device", summary: "Permanently delete a device from the hub (no undo)."]
    ]
}
