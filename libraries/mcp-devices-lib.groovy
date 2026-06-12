library(name: "McpDevicesLib", namespace: "mcp", author: "kingpanther13", description: "Device tool implementations (list/get/attribute/events/command/update/delete) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListDevices(detailed, offset, limit, filter = null, labelFilter = null, capabilityFilter = null, format = null, fields = null, cursor = null) {
    // Opt-in cursor pagination decodes onto the existing offset/limit mechanics. The
    // real range check against the filtered total happens further down (line ~3585)
    // because we don't have the filtered count yet; pass Integer.MAX_VALUE so the
    // helper only does the parse + negative check. cursor='' / null returns offset 0
    // exactly like every other paginated tool.
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
    // Combine selected devices and MCP-managed child devices (virtual devices)
    def allDevices = (selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    // Add child devices that aren't already in the selected list (avoid duplicates)
    def selectedIds = allDevices.collect { it.id.toString() } as Set
    childDevs.each { cd ->
        if (!selectedIds.contains(cd.id.toString())) {
            allDevices.add(cd)
        }
    }

    if (!allDevices) {
        return [devices: [], message: "No devices selected for MCP access and no MCP-managed virtual devices", total: 0]
    }

    // Capture the full set BEFORE any filter/labelFilter/capabilityFilter narrows it. Used
    // by the capabilityFilterMatchedKnownCapability typo-vs-absence diagnostic so a
    // mistyped capability can be distinguished from a real-but-excluded one even when
    // labelFilter has already removed every device that would have matched.
    def unfilteredDevices = allDevices

    def unfilteredTotal = allDevices.size()

    // Type validation for caller-supplied filter args. Groovy coercion would otherwise surface
    // as MissingMethodException deep in the filter logic rather than a clear -32602 error.
    if (labelFilter != null && !(labelFilter instanceof String)) {
        throw new IllegalArgumentException("labelFilter must be a string")
    }
    if (capabilityFilter != null && !(capabilityFilter instanceof String)) {
        throw new IllegalArgumentException("capabilityFilter must be a string")
    }
    if (fields != null && !(fields instanceof List)) {
        throw new IllegalArgumentException("fields must be an array")
    }

    // Parse and apply server-side filter BEFORE pagination so limit/offset respect the filtered set.
    // Supported filters: null/"all" (default), "enabled", "disabled", "stale:<hours>" (e.g. "stale:24").
    // Filtering happens in-memory against device properties already loaded, no extra hub API calls.
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
            def lbl = (d.label ?: d.name)?.toString()?.toLowerCase()
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

    def totalCount = allDevices.size()

    // Apply pagination (post-filter)
    def startIndex = offset ?: 0
    if (startIndex < 0) startIndex = 0
    def endIndex = totalCount
    if (limit && limit > 0) {
        endIndex = Math.min(startIndex + limit, totalCount)
    }

    // Validate format before any early-return so invalid format always throws.
    def resolvedFormat = format ?: "summary"
    if (format && !["summary", "detailed", "ids"].contains(resolvedFormat)) {
        throw new IllegalArgumentException("Invalid format '${format}'. Must be one of: summary, detailed, ids")
    }

    // Validate offset (after format validation so callers always get the format error first).
    if (totalCount > 0 && startIndex >= totalCount) {
        if (resolvedFormat == "ids") {
            def earlyResult = [deviceIds: [], count: 0, total: totalCount, hasMore: false, nextOffset: null]
            def anyFilterActive = (filter && filter != "all") || labelFilter || capabilityFilter
            if (filter && filter != "all") earlyResult.filter = filter
            if (anyFilterActive) earlyResult.unfilteredTotal = unfilteredTotal
            if (labelFilter) earlyResult.labelFilter = labelFilter
            if (capabilityFilter) earlyResult.capabilityFilter = capabilityFilter
            return earlyResult
        }
        return [
            devices: [],
            total: totalCount,
            unfilteredTotal: unfilteredTotal,
            offset: startIndex,
            limit: limit ?: 0,
            filter: filter ?: "all",
            message: "Offset ${startIndex} exceeds filtered device count ${totalCount}"
        ]
    }

    // format="ids" returns a flat array of integer IDs; ignores detailed/fields
    if (resolvedFormat == "ids") {
        def pagedDevices = totalCount > 0 ? allDevices.subList(startIndex, endIndex) : []
        def ids = pagedDevices.collect { it.id as Integer }
        def result = [deviceIds: ids, count: ids.size(), total: totalCount]
        def anyFilterActive = (filter && filter != "all") || labelFilter || capabilityFilter
        if (filter && filter != "all") result.filter = filter
        if (anyFilterActive) result.unfilteredTotal = unfilteredTotal
        if (labelFilter) result.labelFilter = labelFilter
        if (capabilityFilter) result.capabilityFilter = capabilityFilter
        if (capabilityFilter && totalCount == 0) {
            def allCaps = unfilteredDevices.collectMany { d -> d.capabilities?.collect { cap -> cap.name?.toLowerCase() } ?: [] } as Set
            result.capabilityFilterMatchedKnownCapability = allCaps.contains(capabilityFilter.toLowerCase())
        }
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

    def devices = pagedDevices.collect { device ->
        def deviceIdStr = device.id.toString()

        // Per-field gating avoids calling expensive hub APIs (currentValue, supportedAttributes,
        // supportedCommands) for omitted fields. id is always emitted -- without it callers
        // get a list of objects with no correlation key; use format='ids' for id-only results.
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
                info.attributes = device.supportedAttributes?.collect { attr ->
                    [name: attr.name, value: device.currentValue(attr.name)]
                }
            }
            if (fieldSet == null || fieldSet.contains("commands")) {
                info.commands = device.supportedCommands?.collect { it.name }
            }
        } else {
            // Summary mode: populate currentStates only when requested (or when no projection active)
            if (fieldSet == null || fieldSet.contains("currentStates")) {
                info.currentStates = [:]
                ["switch", "level", "motion", "contact", "temperature", "humidity", "battery"].each { attr ->
                    def val = device.currentValue(attr)
                    if (val != null) info.currentStates[attr] = val
                }
            }
        }

        return info
    }

    def result = [
        devices: devices,
        count: devices.size(),
        total: totalCount
    ]
    // Report unfilteredTotal whenever any filter narrowed the set (filter, labelFilter, or capabilityFilter).
    def anyFilterActive = (filter && filter != "all") || labelFilter || capabilityFilter
    if (filter && filter != "all") result.filter = filter
    if (anyFilterActive) result.unfilteredTotal = unfilteredTotal
    if (labelFilter) result.labelFilter = labelFilter
    if (capabilityFilter) result.capabilityFilter = capabilityFilter
    if (capabilityFilter && totalCount == 0) {
        def allCaps = unfilteredDevices.collectMany { d -> d.capabilities?.collect { cap -> cap.name?.toLowerCase() } ?: [] } as Set
        result.capabilityFilterMatchedKnownCapability = allCaps.contains(capabilityFilter.toLowerCase())
    }

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
    try {
        if (device.hasProperty("disabled") && device.disabled != null) return device.disabled == true
    } catch (Exception ignore) {}
    try {
        return device.isDisabled() == true
    } catch (Exception ignore) {}
    try {
        if (device.hasProperty("status") && device.status?.toString()?.toLowerCase() == "disabled") return true
    } catch (Exception ignore) {}
    return false
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
    try {
        return device.getLastActivity()
    } catch (Exception ignore) {
        return null
    }
}

private String formatLastActivity(Date d) {
    if (d == null) return null
    try {
        return d.format("yyyy-MM-dd'T'HH:mm:ssXXX")
    } catch (Exception ignore) {
        return d.toString()
    }
}

def toolGetDevice(deviceId) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    def attributes = []
    try {
        attributes = device.supportedAttributes?.collect { attr ->
            [name: attr.name, dataType: attr.dataType?.toString(), value: device.currentValue(attr.name)]
        } ?: []
    } catch (Exception e) {
        logDebug("Error getting attributes for device ${deviceId}: ${e.message}")
    }

    def commands = []
    try {
        commands = device.supportedCommands?.collect { cmd ->
            def args = null
            try {
                args = cmd.arguments?.collect { arg ->
                    if (arg instanceof Map) {
                        [name: arg.name ?: "arg", type: arg.type ?: "unknown"]
                    } else if (arg.respondsTo("getName")) {
                        [name: arg.getName() ?: "arg", type: arg.getType()?.toString() ?: "unknown"]
                    } else {
                        [name: arg.toString(), type: "unknown"]
                    }
                }
            } catch (Exception e) {
                args = null
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

    // Capture label before command execution to avoid serialization issues
    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"

    def supportedCommands = device.supportedCommands?.collect { it.name }
    if (!supportedCommands?.contains(command)) {
        throw new IllegalArgumentException("Device ${deviceLabel} does not support command: ${command}. Available: ${supportedCommands}")
    }

    if (parameters && parameters.size() > 0) {
        // Normalize parameters to a flat List of properly typed values
        parameters = normalizeCommandParams(parameters)
        device."${command}"(*parameters)
    } else {
        device."${command}"()
    }

    return [
        success: true,
        device: deviceLabel,
        command: command,
        parameters: parameters
    ]
}

def normalizeCommandParams(params) {
    // Case 1: Already a List (Hubitat parsed it successfully) — go straight to element conversion
    if (params instanceof List) {
        return convertParamElements(params)
    }

    // Case 2: String (Hubitat parser failed on nested JSON)
    // Example: '["{"hue":0,"saturation":100,"level":50}"]'
    def s = params.toString().trim()

    // Try to extract an embedded JSON object between first { and last }
    def firstBrace = s.indexOf("{")
    def lastBrace = s.lastIndexOf("}")
    if (firstBrace >= 0 && lastBrace > firstBrace) {
        def jsonContent = s.substring(firstBrace, lastBrace + 1)
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(jsonContent)
            return [parsed]
        } catch (Exception e) {
            // Not valid JSON object, fall through
        }
    }

    // No JSON object found — strip outer ["..."] wrapper and split into string params
    if (s.startsWith("[\"") && s.endsWith("\"]")) {
        def inner = s.substring(2, s.length() - 2)
        return convertParamElements(inner.split('","').toList())
    }

    // Last resort: treat the whole string as a single parameter
    return convertParamElements([s])
}

def convertParamElements(List params) {
    return params.collect { param ->
        if (param == null) return param
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

def toolGetAttribute(deviceId, attribute) {
    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

    // Capture label before operations to avoid serialization issues
    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"

    // Check if attribute exists on this device before reading its value
    def supportedAttrs = device.supportedAttributes?.collect { it.name } ?: []
    if (!supportedAttrs.contains(attribute)) {
        throw new IllegalArgumentException("Attribute '${attribute}' not found on device '${deviceLabel}'. Available: ${supportedAttrs}")
    }

    def value = device.currentValue(attribute)
    return [
        device: deviceLabel,
        attribute: attribute,
        value: value
    ]
}

def toolPollUntilAttribute(args) {
    // 0. Reject unknown args early to surface caller mistakes (e.g., timeoutSeconds vs timeoutMs).
    def validArgKeys = ["deviceId", "attribute", "expectedValue", "expectedValues", "timeoutMs", "pollIntervalMs"] as Set
    if (args instanceof Map) {
        def unknownKeys = (args.keySet() - validArgKeys).sort()
        if (unknownKeys) {
            throw new IllegalArgumentException("Unknown arg(s): ${unknownKeys}. Valid args: ${validArgKeys.sort()}. Common gotcha: timeout is 'timeoutMs' in milliseconds, not 'timeoutSeconds'.")
        }
    }

    // 1. Validate deviceId and look up device
    if (args.containsKey("deviceId") && args.deviceId == null) {
        throw new IllegalArgumentException("deviceId must not be null (omit the arg or pass a non-empty string)")
    }
    if (!(args.deviceId instanceof String) || !args.deviceId) {
        throw new IllegalArgumentException("deviceId is required and must be a non-empty string")
    }
    def device = findDevice(args.deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${args.deviceId}")
    }

    // 2. Validate attribute
    if (args.containsKey("attribute") && args.attribute == null) {
        throw new IllegalArgumentException("attribute must not be null (omit the arg or pass a non-empty string)")
    }
    if (!(args.attribute instanceof String) || !args.attribute) {
        throw new IllegalArgumentException("attribute is required and must be a non-empty string")
    }
    def deviceLabel = device.label ?: device.name ?: "Device ${args.deviceId}"
    def supportedAttrs = device.supportedAttributes?.collect { it.name } ?: []
    if (!supportedAttrs.contains(args.attribute)) {
        throw new IllegalArgumentException("Attribute '${args.attribute}' not found on device '${deviceLabel}'. Available: ${supportedAttrs}")
    }

    // 3. Validate expectedValue / expectedValues (at least one required)
    if (args.containsKey("expectedValue") && args.expectedValue == null) {
        throw new IllegalArgumentException("expectedValue must not be null (omit the arg or pass a non-empty string)")
    }
    if (args.containsKey("expectedValues") && args.expectedValues == null) {
        throw new IllegalArgumentException("expectedValues must not be null (omit the arg or pass a non-empty list)")
    }
    def hasExpectedValue  = (args.expectedValue  != null)
    def hasExpectedValues = (args.expectedValues != null)
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
                throw new IllegalArgumentException("expectedValues[${i}] must be a string, got: ${v}")
            }
        }
    }

    // 4. Validate timeoutMs
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

    // 5. Validate pollIntervalMs, clamp to timeoutMs if larger
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

    // 6. Build the expected-value set for match checking (OR semantics)
    def matchSet = [] as Set
    if (hasExpectedValue)  matchSet << args.expectedValue
    if (hasExpectedValues) matchSet.addAll(args.expectedValues)

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

    while (true) {
        finalValue = device.currentValue(args.attribute)
        polledCount++
        if (finalValue != null) everNonNull = true
        def elapsedMs = (now() - startMs) as Integer

        // String match first.
        // Numeric fallback handles BigDecimal/Double "50.0" vs "50" quirk in both directions:
        //   - Number attribute (e.g. BigDecimal 50.0) matched by String expectedValue "50"
        //   - String attribute "50.0" (some drivers return numeric-typed attributes as String)
        //     matched by String expectedValue "50"
        def matched = matchSet.contains(finalValue?.toString()) ||
            (finalValue instanceof Number && matchSet.any { it instanceof String && it.isNumber() && (it as BigDecimal) == (finalValue as BigDecimal) }) ||
            (finalValue instanceof String && finalValue.isNumber() && matchSet.any { it instanceof String && it.isNumber() && (it as BigDecimal) == (finalValue as BigDecimal) })
        if (matched) {
            return [
                success     : true,
                finalValue  : finalValue,
                elapsedMs   : elapsedMs,
                polledCount : polledCount,
                timedOut    : false
            ]
        }

        if (elapsedMs >= timeoutMs || polledCount >= maxPolls) {
            def response = [
                success     : false,
                finalValue  : finalValue,
                elapsedMs   : elapsedMs,
                polledCount : polledCount,
                timedOut    : true
            ]
            // Attribute exists in supportedAttributes but never reported a value during
            // the entire poll window -- driver has not yet emitted a reading.
            if (!everNonNull) response.neverReported = true
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
            return [
                success     : false,
                interrupted : true,
                finalValue  : finalValue,
                elapsedMs   : elapsedMs,
                polledCount : polledCount
            ]
        }
    }
}

def toolGetDeviceHistory(args) {
    def hoursBack = Math.min(args.hoursBack ?: 24, 168)
    def limit = Math.min(args.limit ?: 100, 500)
    def attributeFilter = args.attribute
    def sinceDate = new Date(now() - (hoursBack * 3600000L))

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
            // Best-effort hoursBack window, mirroring the location branch: drop
            // events older than sinceDate when the date parses; keep them if it
            // doesn't (don't silently lose history), and count the unparseable
            // rows so the caller can see the window was not fully enforced.
            def evtDate = null
            try { evtDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", evt.date?.toString()) }
            catch (Exception ignored) { timeFilterUnparseable++ }
            if (evtDate != null && evtDate.before(sinceDate)) continue
            appResults << [
                name: evt.name,
                value: evt.value,
                description: evt.descriptionText,
                date: evt.date
            ]
            if (appResults.size() >= limit) break
        }

        mcpLog("info", "monitoring", "Retrieved ${appResults.size()} app history events for app ${appIdStr} (${hoursBack}h back) from /installedapp/eventsJson")
        def appResult = [
            source: "app",
            appId: appIdStr as Integer,
            hoursBack: hoursBack,
            attributeFilter: attributeFilter,
            events: appResults,
            count: appResults.size(),
            sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        ]
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
            // Best-effort hoursBack window: drop events older than sinceDate when the
            // ISO+offset date parses; keep them if it doesn't (don't silently lose
            // history), counting the unparseable rows. The numeric offset (e.g.
            // -0400) parses via the 'Z' pattern.
            def evtDate = null
            try { evtDate = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", evt.date?.toString()) }
            catch (Exception ignored) { timeFilterUnparseable++ }
            if (evtDate != null && evtDate.before(sinceDate)) continue
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

        mcpLog("info", "monitoring", "Retrieved ${locResults.size()} location history events (${hoursBack}h back) from /logs/eventsJson")
        def locResult = [
            source: "location",
            hoursBack: hoursBack,
            attributeFilter: attributeFilter,
            events: locResults,
            count: locResults.size(),
            sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        ]
        // Mirror the hub-log path: surface rows that escaped the always-active
        // time window because their date would not parse.
        if (timeFilterUnparseable > 0) locResult.timeFilterUnparseable = timeFilterUnparseable
        return locResult
    }

    def device = findDevice(args.deviceId)
    if (!device) throw new IllegalArgumentException("Device not found: ${args.deviceId}. Device must be selected in MCP Rule Server app settings.")

    def deviceLabel = device.label ?: device.name ?: "Device ${args.deviceId}"

    def events
    try {
        events = device.eventsSince(sinceDate, [max: limit])
    } catch (Exception e) {
        mcpLogError("monitoring", "eventsSince failed for ${deviceLabel}", e)
        return [success: false, error: "eventsSince not supported or failed: ${e.message}", device: deviceLabel, deviceId: args.deviceId,
                note: "Retry; if persistent, drop hoursBack/attribute to read the most-recent events instead, or check the device's Events page in the hub UI."]
    }

    def results = events?.collect { evt ->
        [
            name: evt.name,
            value: evt.value,
            unit: evt.unit,
            description: evt.descriptionText,
            date: evt.date?.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
            isStateChange: evt.isStateChange
        ]
    } ?: []

    if (attributeFilter) {
        results = results.findAll { it.name == attributeFilter }
    }

    mcpLog("info", "monitoring", "Retrieved ${results.size()} history events for ${deviceLabel} (${hoursBack}h back)")
    return [
        source: "device",
        device: deviceLabel,
        deviceId: args.deviceId,
        hoursBack: hoursBack,
        attributeFilter: attributeFilter,
        events: results,
        count: results.size(),
        sinceTimestamp: sinceDate.format("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    ]
}

def toolUpdateDevice(args) {
    def deviceId = args.deviceId
    if (!deviceId) throw new IllegalArgumentException("deviceId is required")

    def device = findDevice(deviceId)
    if (!device) {
        throw new IllegalArgumentException("Device not found: ${deviceId}. The device must be in your selected devices or be an MCP-managed virtual device.")
    }

    def deviceLabel = device.label ?: device.name ?: "Device ${deviceId}"
    def changes = []
    def errors = []

    def requestedProps = []
    if (args.label != null) requestedProps << "label"
    if (args.name != null) requestedProps << "name"
    if (args.deviceNetworkId != null) requestedProps << "deviceNetworkId"
    if (args.dataValues) requestedProps << "dataValues(${args.dataValues.size()})"
    if (args.preferences) requestedProps << "preferences(${args.preferences.size()})"
    if (args.room != null) requestedProps << "room"
    if (args.enabled != null) requestedProps << "enabled"
    mcpLog("debug", "device", "hub_update_device called for '${deviceLabel}' (ID: ${deviceId}), properties: ${requestedProps.join(', ')}")

    // Label (official API)
    if (args.label != null) {
        try {
            def oldLabel = deviceLabel
            device.setLabel(args.label)
            changes << [property: "label", oldValue: oldLabel, newValue: args.label]
            deviceLabel = args.label
            mcpLog("debug", "device", "hub_update_device label: '${oldLabel}' -> '${args.label}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "hub_update_device label: error: ${e.message}")
            errors << [property: "label", error: e.message]
        }
    }

    // Name (official API)
    if (args.name != null) {
        try {
            def oldName = device.name
            device.setName(args.name)
            changes << [property: "name", oldValue: oldName, newValue: args.name]
            mcpLog("debug", "device", "hub_update_device name: '${oldName}' -> '${args.name}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "hub_update_device name: error: ${e.message}")
            errors << [property: "name", error: e.message]
        }
    }

    // Device Network ID (official API)
    if (args.deviceNetworkId != null) {
        try {
            def oldDni = device.deviceNetworkId
            device.setDeviceNetworkId(args.deviceNetworkId)
            changes << [property: "deviceNetworkId", oldValue: oldDni, newValue: args.deviceNetworkId]
            mcpLog("debug", "device", "hub_update_device DNI: '${oldDni}' -> '${args.deviceNetworkId}'")
        } catch (Exception e) {
            mcpLog("debug", "device", "hub_update_device DNI: error: ${e.message}")
            errors << [property: "deviceNetworkId", error: e.message]
        }
    }

    // Data Values (official API)
    if (args.dataValues) {
        args.dataValues.each { key, value ->
            try {
                device.updateDataValue(key.toString(), value?.toString())
                changes << [property: "dataValue.${key}", newValue: value?.toString()]
                mcpLog("debug", "device", "hub_update_device dataValue: ${key}='${value}'")
            } catch (Exception e) {
                mcpLog("debug", "device", "hub_update_device dataValue ${key}: error: ${e.message}")
                errors << [property: "dataValue.${key}", error: e.message]
            }
        }
    }

    // Preferences (official API)
    if (args.preferences) {
        args.preferences.each { key, setting ->
            try {
                if (setting instanceof Map && setting.type && setting.containsKey("value")) {
                    device.updateSetting(key.toString(), [type: setting.type.toString(), value: setting.value])
                    mcpLog("debug", "device", "hub_update_device preference: ${key}={type:${setting.type}, value:${setting.value}}")
                } else {
                    device.updateSetting(key.toString(), setting?.toString())
                    mcpLog("debug", "device", "hub_update_device preference: ${key}='${setting}'")
                }
                changes << [property: "preference.${key}", newValue: setting]
            } catch (Exception e) {
                mcpLog("debug", "device", "hub_update_device preference ${key}: error: ${e.message}")
                errors << [property: "preference.${key}", error: e.message]
            }
        }
    }

    // Room (internal API — write; Write master enforced centrally in executeTool)
    if (args.room != null) {
        if (settings.enableWrite == false) {
            errors << [property: "room", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
        } else {
            try {
                mcpLog("debug", "device", "hub_update_device room: starting room assignment for device ${deviceId}")

                // Find room ID by name
                def targetRoomId = null
                if (args.room == "" || args.room == "none" || args.room == "null") {
                    targetRoomId = "0"
                    mcpLog("debug", "device", "hub_update_device room: unassigning device from room")
                } else {
                    def cachedRooms = null
                    try {
                        cachedRooms = getRooms()
                        mcpLog("debug", "device", "hub_update_device room: getRooms() returned ${cachedRooms?.size() ?: 0} rooms")
                        if (cachedRooms) {
                            def targetRoom = cachedRooms.find { it.name?.toString()?.toLowerCase() == args.room?.toString()?.toLowerCase() }
                            if (targetRoom) {
                                targetRoomId = targetRoom.id?.toString()
                                mcpLog("debug", "device", "hub_update_device room: resolved '${args.room}' -> roomId=${targetRoomId}")
                            }
                        }
                    } catch (Exception e) {
                        mcpLog("debug", "device", "hub_update_device room: getRooms() failed: ${e.message}")
                    }

                    if (targetRoomId == null) {
                        def allRoomNames = cachedRooms ? cachedRooms.collect { it.name } : []
                        throw new RuntimeException("Room '${args.room}' not found.${allRoomNames ? ' Available rooms: ' + allRoomNames.join(', ') : ''}")
                    }
                }

                // Room assignment via POST /room/save with JSON body.
                // API uses "roomId" field (not "id"). Content-Type must be application/json.

                def saveSuccess = false
                def saveError = null
                def deviceIdLong = deviceId as Long
                def deviceIdInt = deviceId as Integer

                // Helper: POST JSON to /room/save and check for errors
                // Routed through hubInternalPostJson so room writes share the Hub Security
                // cookie-refresh retry (a stale cookie no longer fails the save outright).
                // It returns the parsed body (or null on empty/non-JSON); the
                // verify-after-write step below is the real safety net for this path.
                def roomSavePost = { Map bodyMap ->
                    def jsonStr = groovy.json.JsonOutput.toJson(bodyMap)
                    def parsed = hubInternalPostJson("/room/save", jsonStr, 30)
                    if (parsed?.error) {
                        throw new RuntimeException("Room API error: ${parsed.error}")
                    }
                    return parsed
                }

                // Helper: check if device is in a room's device list
                def deviceInRoom = { room ->
                    room?.deviceIds?.contains(deviceIdLong) || room?.deviceIds?.contains(deviceIdInt)
                }

                // Get current room data
                def allRooms = getRooms()
                mcpLog("debug", "device", "hub_update_device room: getRooms() returned ${allRooms?.size() ?: 0} rooms")

                if (targetRoomId == "0") {
                    // --- UNASSIGN: remove device from its current room ---
                    def currentRoom = allRooms?.find { deviceInRoom(it) }
                    if (!currentRoom) {
                        saveSuccess = true
                        mcpLog("debug", "device", "hub_update_device room: device not in any room, nothing to unassign")
                    } else {
                        mcpLog("debug", "device", "hub_update_device room: removing device ${deviceId} from room '${currentRoom.name}' (${currentRoom.id})")
                        def updatedDeviceIds = currentRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
                        def body = [roomId: currentRoom.id as Integer, name: currentRoom.name, deviceIds: updatedDeviceIds]
                        mcpLog("debug", "device", "hub_update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(body)}")
                        try {
                            roomSavePost(body)
                            saveSuccess = true
                        } catch (Exception e) {
                            mcpLog("debug", "device", "hub_update_device room: remove failed: ${e.message}")
                            saveError = e.message
                        }
                    }
                } else {
                    // --- ASSIGN: add device to target room ---
                    mcpLog("debug", "device", "hub_update_device room: assigning device ${deviceId} to room ${targetRoomId}")

                    // Check if device is already in the target room
                    def targetRoom = allRooms?.find { it.id?.toString() == targetRoomId }
                    if (targetRoom && deviceInRoom(targetRoom)) {
                        mcpLog("debug", "device", "hub_update_device room: device already in target room '${targetRoom.name}'")
                        saveSuccess = true
                    } else {
                        // Safe Move pattern: add to new room FIRST, then remove from old room.
                        // This prevents "device limbo" where a device ends up in no room if
                        // the second API call fails after the first succeeds.
                        // Worst case (remove fails): device appears in both rooms temporarily,
                        // which is recoverable. The old pattern (remove first) could orphan the device.

                        // Locate old room (if any) before mutations
                        def oldRoom = allRooms?.find { room ->
                            deviceInRoom(room) && room.id?.toString() != targetRoomId
                        }

                        // Step 1: Add device to target room
                        def freshTarget = allRooms?.find { it.id?.toString() == targetRoomId }
                        def targetDeviceIds = freshTarget?.deviceIds?.collect { it as Integer } ?: []
                        def devIdInt = deviceId as Integer
                        if (!targetDeviceIds.contains(devIdInt)) {
                            targetDeviceIds << devIdInt
                        }

                        def roomData = [roomId: targetRoomId as Integer, name: freshTarget?.name ?: targetRoom?.name ?: "", deviceIds: targetDeviceIds]
                        mcpLog("debug", "device", "hub_update_device room: POST /room/save (add) body: ${groovy.json.JsonOutput.toJson(roomData)}")
                        try {
                            roomSavePost(roomData)
                            mcpLog("debug", "device", "hub_update_device room: added to target room '${freshTarget?.name ?: targetRoomId}'")
                            saveSuccess = true
                        } catch (Exception e) {
                            // Add failed — device stays safely in its old room (no change made)
                            mcpLog("debug", "device", "hub_update_device room: add to room failed: ${e.message}")
                            saveError = e.message
                        }

                        // Step 2: Remove from old room (only if add succeeded)
                        if (saveSuccess && oldRoom) {
                            mcpLog("debug", "device", "hub_update_device room: removing from old room '${oldRoom.name}' (${oldRoom.id})")
                            // Re-fetch rooms to get fresh data after the add mutation
                            def freshRooms = getRooms()
                            def freshOldRoom = freshRooms?.find { it.id?.toString() == oldRoom.id?.toString() }
                            if (freshOldRoom) {
                                def oldDeviceIds = freshOldRoom.deviceIds?.findAll { it != deviceIdLong && it != deviceIdInt }?.collect { it as Integer } ?: []
                                def oldBody = [roomId: freshOldRoom.id as Integer, name: freshOldRoom.name, deviceIds: oldDeviceIds]
                                mcpLog("debug", "device", "hub_update_device room: POST /room/save (remove) body: ${groovy.json.JsonOutput.toJson(oldBody)}")
                                try {
                                    roomSavePost(oldBody)
                                    mcpLog("debug", "device", "hub_update_device room: removed from old room '${oldRoom.name}'")
                                } catch (Exception oldErr) {
                                    // Device is in both rooms — not ideal but it IS in the target room.
                                    // Log a warning so the user is aware.
                                    mcpLog("warn", "device", "hub_update_device room: device added to new room but removal from old room '${oldRoom.name}' failed: ${oldErr.message}. Device may appear in both rooms.")
                                }
                            }
                        }
                    }
                }

                // Verify the room actually changed
                if (saveSuccess) {
                    def verified = false
                    try {
                        def verifyRooms = getRooms()
                        if (targetRoomId == "0") {
                            def stillInRoom = verifyRooms?.find { room -> deviceInRoom(room) }
                            verified = (stillInRoom == null)
                            if (!verified) {
                                mcpLog("debug", "device", "hub_update_device room: VERIFICATION FAILED - device still in room '${stillInRoom?.name}'")
                            }
                        } else {
                            def tRoom = verifyRooms?.find { it.id?.toString() == targetRoomId }
                            verified = deviceInRoom(tRoom)
                            if (!verified) {
                                mcpLog("debug", "device", "hub_update_device room: VERIFICATION FAILED - device not in target room '${tRoom?.name}' deviceIds: ${tRoom?.deviceIds}")
                            }
                            // Also verify device is NOT still in the old room
                            if (verified) {
                                def dualRoom = verifyRooms?.find { room -> deviceInRoom(room) && room.id?.toString() != targetRoomId }
                                if (dualRoom) {
                                    mcpLog("warn", "device", "hub_update_device room: WARNING - device also still in room '${dualRoom.name}' (dual-room state)")
                                }
                            }
                        }
                    } catch (Exception verErr) {
                        mcpLog("debug", "device", "hub_update_device room: verification error: ${verErr.message}")
                    }

                    if (verified) {
                        def oldRoomName = device.roomName ?: "none"
                        changes << [property: "room", oldValue: oldRoomName, newValue: args.room ?: "none"]
                        mcpLog("info", "device", "Room changed for '${deviceLabel}': ${oldRoomName} -> ${args.room ?: 'none'} (VERIFIED)")
                    } else {
                        throw new RuntimeException("Room assignment endpoint returned success but room did not actually change.")
                    }
                } else {
                    throw new RuntimeException("Room assignment failed. Last error: ${saveError}")
                }
            } catch (Exception e) {
                mcpLog("debug", "device", "hub_update_device room: error: ${e.message}")
                errors << [property: "room", error: e.message]
            }
        }
    }

    // Enable/Disable (internal API — write; Write master enforced centrally in executeTool)
    // Hubitat's /device/disable endpoint requires POST with body params, not GET with query params
    if (args.enabled != null) {
        if (settings.enableWrite == false) {
            errors << [property: "enabled", error: "Requires 'Enable Write Tools' to be turned on in MCP Rule Server app settings"]
        } else {
            try {
                def disableValue = args.enabled ? "false" : "true"
                mcpLog("debug", "device", "hub_update_device enabled: POSTing to /device/disable with id=${deviceId}, disable=${disableValue}")
                hubInternalPost("/device/disable", [id: deviceId, disable: disableValue])
                changes << [property: "enabled", newValue: args.enabled]
                mcpLog("info", "device", "Device '${deviceLabel}' ${args.enabled ? 'enabled' : 'disabled'}")
            } catch (Exception e) {
                mcpLog("debug", "device", "hub_update_device enabled: error: ${e.message}")
                errors << [property: "enabled", error: e.message]
            }
        }
    }

    if (!changes && !errors) {
        return [
            success: true,
            device: deviceLabel,
            deviceId: deviceId,
            message: "No properties were provided to update. Specify at least one property: label, name, deviceNetworkId, room, enabled, dataValues, or preferences."
        ]
    }

    mcpLog("info", "device", "Updated device '${deviceLabel}' (ID: ${deviceId}): ${changes.size()} changes, ${errors.size()} errors")
    if (errors) {
        mcpLog("debug", "device", "hub_update_device errors: ${errors.collect { "${it.property}: ${it.error}" }.join('; ')}")
    }

    return [
        success: errors.isEmpty(),
        device: deviceLabel,
        deviceId: deviceId,
        changes: changes,
        errors: errors.isEmpty() ? null : errors,
        message: errors.isEmpty()
            ? "Successfully updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} on device '${deviceLabel}'."
            : "Updated ${changes.size()} ${changes.size() == 1 ? 'property' : 'properties'} with ${errors.size()} ${errors.size() == 1 ? 'error' : 'errors'} on device '${deviceLabel}'."
    ]
}

def toolDeleteDevice(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.deviceId) throw new IllegalArgumentException("deviceId is required")

    def deviceId = args.deviceId.toString()

    // Step 1: Gather device information for audit trail via hub internal API
    // We intentionally do NOT restrict to findDevice() (selectedDevices only) because
    // ghost/orphaned devices may not be in the selected device list
    def deviceInfo = null
    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (responseText) {
            deviceInfo = new groovy.json.JsonSlurper().parseText(responseText)
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Could not fetch device info for ${deviceId}: ${e.message}")
    }

    if (!deviceInfo) {
        throw new IllegalArgumentException("Device ${deviceId} not found on hub. Verify the device ID is correct.")
    }

    def deviceName = deviceInfo.label ?: deviceInfo.name ?: "Unknown"
    def deviceDNI = deviceInfo.deviceNetworkId ?: "unknown"
    def deviceType = deviceInfo.typeName ?: deviceInfo.type ?: "unknown"
    def warnings = []

    // Step 2: Check for recent activity (active device warning)
    try {
        def selectedDevice = findDevice(deviceId)
        if (selectedDevice) {
            def lastActivity = selectedDevice.lastActivity
            if (lastActivity) {
                def hoursAgo = (Math.round((now() - lastActivity.time) / 3600000.0 * 10) / 10.0) as double
                if (hoursAgo < 24) {
                    warnings << "ACTIVE DEVICE: Last activity was ${hoursAgo} hours ago at ${lastActivity.format("yyyy-MM-dd'T'HH:mm:ss")}. This device may still be functional."
                }
            }
            def recentEvents = selectedDevice.events(max: 3)
            if (recentEvents && recentEvents.size() > 0) {
                def lastEvent = recentEvents[0]
                warnings << "HAS RECENT EVENTS: Last event was ${lastEvent.name}=${lastEvent.value} at ${lastEvent.date?.format("yyyy-MM-dd'T'HH:mm:ss")}"
            }
        }
    } catch (Exception e) {
        // Device not in selected list or events unavailable — skip
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
            try {
                def checkParsed = new groovy.json.JsonSlurper().parseText(checkResponse)
                verified = !checkParsed?.id
            } catch (Exception parseErr) {
                // Non-JSON response or error page = likely deleted
                verified = true
            }
        } else {
            verified = true
        }
    } catch (Exception e) {
        // 404 or error = device is gone = success
        verified = true
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
    def fromDevice = findDevice(fromId)
    if (!fromDevice) throw new IllegalArgumentException("Device not found: ${fromId}")
    def toDevice = findDevice(toId)
    if (!toDevice) throw new IllegalArgumentException("Device not found: ${toId}")

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
            description: """List all devices available to MCP with current states.

DEVICE AUTHORIZATION: Exact name match -> use directly. No exact match -> suggest similar, ASK USER before using. NEVER control unconfirmed devices (HVAC/locks risk). Report tool failures; don't silently fall back to existing devices.

Use detailed=false for discovery; detailed=true with limit=20-30. Sequential calls only.

[[FLAT_TRIM]]
Summary mode returns currentStates; detailed mode replaces that with capabilities, attributes, and commands (field list in outputSchema). Server-side filtering (all applied before pagination) is configured via the filter / labelFilter / capabilityFilter params (documented on those params). format='ids' is the cheapest shape; fields=[...] projects named fields and skips expensive hub reads. To count a parent's children, group the response by parentDeviceId.
[[/FLAT_TRIM]]
Call `hub_get_tool_guide(section='performance')` for response-shape details, filter/projection semantics, and field-name reference.""",
            inputSchema: [
                type: "object",
                properties: [
                    detailed: [type: "boolean", description: "Include full device details (capabilities, all attributes, commands). WARNING: Resource-intensive for large device counts.[[FLAT_TRIM]] Use with pagination (limit parameter) for best performance.[[/FLAT_TRIM]]"],
                    offset: [type: "integer", description: "Start from device at this index (0-based). Use for pagination.", default: 0],
                    limit: [type: "integer", description: "Maximum number of devices to return. Recommended: 20-30 for detailed=true, higher values may slow hub.", default: 0],
                    filter: [type: "string", description: "Server-side filter (applied before pagination). 'all' (default) | 'enabled' | 'disabled' | 'stale:<hours>' | 'virtual' (this MCP app's own virtual devices; use to find their IDs/DNIs).[[FLAT_TRIM]] stale example: 'stale:24' = no activity in the last 24 hours; never-reported devices count as stale. 'virtual' returns a different population and shape from the other filters, with driver namespace/type.[[/FLAT_TRIM]]"],
                    labelFilter: [type: "string", description: "Case-insensitive substring match against device label; falls back to name for devices without a label set.[[FLAT_TRIM]] Applied after filter, before pagination.[[/FLAT_TRIM]]"],
                    capabilityFilter: [type: "string", description: "Case-insensitive exact match against capability name. Capability names are camelCase (e.g. 'ColorControl', 'TemperatureMeasurement').[[FLAT_TRIM]] Applied after labelFilter, before pagination. When count=0, response includes `capabilityFilterMatchedKnownCapability` to distinguish 'no devices have this capability' from a typo.[[/FLAT_TRIM]]"],
                    format: [type: "string", enum: ["summary", "detailed", "ids"], description: "Response shape. 'summary' (default) = standard fields + currentStates. 'detailed' = capabilities/attributes/commands[[FLAT_TRIM]] (same as detailed=true)[[/FLAT_TRIM]]. 'ids' = flat array of device ID integers (cheapest, ignores fields arg).[[FLAT_TRIM]] detailed=true overrides format='summary'.[[/FLAT_TRIM]]"],
                    fields: [type: "array", items: [type: "string"], description: "Field projection: only include named fields in each device object.[[FLAT_TRIM]] Valid names: id, name, label, room, disabled, deviceNetworkId, lastActivity, parentDeviceId, mcpManaged, currentStates, capabilities, attributes, commands. Throws if any field name is unknown. Omitted or empty = all default fields for the active format. Ignored when format='ids'. id is always included regardless of projection (use format='ids' for id-only results). Including capabilities, attributes, or commands auto-promotes the response to detailed mode (those fields require detailed-mode device introspection). Project out currentStates and attributes to skip expensive hub reads; capabilities and commands are in-memory and cheap.[[/FLAT_TRIM]] Call `hub_get_tool_guide(section='performance')` for valid field names and projection semantics."],
                    cursor: [type: "string", description: "Opt-in opaque cursor (alias to offset). Pass \"\" for the first page (page size 50 when limit is unset), then iterate nextCursor[[FLAT_TRIM]] returned alongside nextOffset[[/FLAT_TRIM]]."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    devices: [type: "array", description: "Device objects (summary/detailed modes). Per-field projection applies", items: [type: "object", properties: [
                        id: [type: "string", description: "Device ID (always present)"],
                        name: [type: "string", description: "Driver type / device name"],
                        label: [type: "string", description: "User-assigned label"],
                        room: [type: "string", description: "Assigned room name"],
                        disabled: [type: "boolean", description: "Device disabled"],
                        deviceNetworkId: [type: "string", description: "Device network ID"],
                        lastActivity: [type: "string", description: "Last-activity ISO timestamp, or null"],
                        parentDeviceId: [type: "string", description: "Parent device ID, or null"],
                        mcpManaged: [type: "boolean", description: "Present and true for this app's virtual devices"],
                        currentStates: [type: "object", description: "Summary mode: common attribute values"],
                        capabilities: [type: "array", description: "Detailed mode: capability names", items: [type: "string"]],
                        attributes: [type: "array", description: "Detailed mode: attribute name/value pairs", items: [type: "object"]],
                        commands: [type: "array", description: "Detailed mode: command names", items: [type: "string"]]
                    ]]],
                    deviceIds: [type: "array", description: "format='ids' mode: flat array of integer device IDs", items: [type: "integer"]],
                    count: [type: "integer", description: "Devices in this response"],
                    total: [type: "integer", description: "Total devices after filtering"],
                    unfilteredTotal: [type: "integer", description: "Total before filters; present when a filter is active"],
                    filter: [type: "string", description: "Echoed filter; present when non-default"],
                    labelFilter: [type: "string", description: "Echoed labelFilter; present when set"],
                    capabilityFilter: [type: "string", description: "Echoed capabilityFilter; present when set"],
                    capabilityFilterMatchedKnownCapability: [type: "boolean", description: "When capabilityFilter yields 0: whether the capability exists on any device"],
                    offset: [type: "integer", description: "Page start index; present when paginated"],
                    limit: [type: "integer", description: "Page size; present when paginated"],
                    hasMore: [type: "boolean", description: "More pages remain; present when paginated"],
                    nextOffset: [type: "integer", description: "Next page offset; present when more remain"],
                    nextCursor: [type: "string", description: "Opaque cursor; present in cursor mode when more remain"],
                    message: [type: "string", description: "Present when no devices or offset out of range"]
                ]
            ]
        ],
        [
            name: "hub_get_device",
            description: """Get one device's full detail: capabilities, all attributes with current values, and supported commands (with argument types). Use when you need a single device's complete profile — e.g. to discover which commands/attributes it supports before calling hub_call_device_command or hub_get_device_attribute. For a multi-device listing use hub_list_devices instead.

Only query devices the user has mentioned or that are relevant to their request. Do not probe random devices.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices, e.g. \"42\""]
                ],
                required: ["deviceId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Device ID"],
                    name: [type: "string", description: "Driver type / device name"],
                    label: [type: "string", description: "User-assigned label"],
                    room: [type: "string", description: "Assigned room name"],
                    capabilities: [type: "array", description: "Capability names", items: [type: "string"]],
                    attributes: [type: "array", description: "Attributes with current values", items: [type: "object", properties: [
                        name: [type: "string", description: "Attribute name"],
                        dataType: [type: "string", description: "Attribute data type"],
                        value: [description: "Current value"]
                    ]]],
                    commands: [type: "array", description: "Supported commands", items: [type: "object", properties: [
                        name: [type: "string", description: "Command name"],
                        arguments: [type: "array", description: "Argument name/type pairs, or null. Each `type` is Hubitat's raw declared arg type (e.g. NUMBER, STRING, ENUM, DATE) or 'unknown' when the driver doesn't declare one.", items: [type: "object"]]
                    ]]]
                ],
                required: ["id", "name", "label", "capabilities", "attributes", "commands"]
            ]
        ],
        [
            name: "hub_get_device_attribute",
            description: """Get a device attribute's current value, or block-poll until it reaches an expected value.

One-shot read by default (deviceId + attribute). Provide expectedValue and/or expectedValues to block-poll until currentValue matches (OR semantics), returning immediately on match or when timeoutMs elapses — a single round-trip that replaces N client-side reads + sleeps (verify a command took effect, wait for a sensor threshold, detect Z-Wave inclusion finished). Poll mode BLOCKS up to timeoutMs (default 5000ms, max 60000ms) and queues concurrent MCP requests; prefer event-driven flows where possible. First read fires immediately; subsequent reads are spaced by pollIntervalMs.

Only query devices the user has mentioned or that are relevant to their request.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices"],
                    attribute: [type: "string", description: "Attribute name"],
                    expectedValue: [type: "string", description: "If set, block-poll until currentValue equals this string. Enables poll mode. At least one of expectedValue/expectedValues enables polling."],
                    expectedValues: [type: "array", items: [type: "string"], description: "If set, block-poll until currentValue is any of these strings (OR with expectedValue). Enables poll mode."],
                    timeoutMs: [type: "integer", description: "Poll mode only: max wait in MILLISECONDS. Default 5000, min 100, max 60000. Requires expectedValue/expectedValues — passing a timeout without one is rejected.", default: 5000, minimum: 100, maximum: 60000],
                    pollIntervalMs: [type: "integer", description: "Poll mode: re-check interval in MILLISECONDS. Default 200, min 50, max 5000. Clamped to timeoutMs if larger.", default: 200, minimum: 50, maximum: 5000]
                ],
                required: ["deviceId", "attribute"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    device: [type: "string", description: "One-shot mode: device label"],
                    attribute: [type: "string", description: "One-shot mode: attribute name"],
                    value: [description: "One-shot mode: current attribute value"],
                    success: [type: "boolean", description: "Poll mode: true if a matching value was observed"],
                    finalValue: [description: "Poll mode: last value read"],
                    elapsedMs: [type: "integer", description: "Poll mode: elapsed time in milliseconds"],
                    polledCount: [type: "integer", description: "Poll mode: number of reads performed"],
                    timedOut: [type: "boolean", description: "Poll mode: true if the timeout elapsed without a match"],
                    neverReported: [type: "boolean", description: "Poll mode: present and true if the attribute never reported a value in the window"],
                    interrupted: [type: "boolean", description: "Poll mode: present and true if the poll was interrupted (hub reload)"]
                ]
            ]
        ],
        [
            name: "hub_call_device_command",
            description: """Send a command (e.g. on, off, setLevel) to a device. Use to actuate or control a device; for read-only checks use hub_get_device_attribute instead. Always verify the state changed after sending (commands are fire-and-forget — the hub returns acceptance, not effect).

If no exact device match: suggest similar devices and get user confirmation before sending any command.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices - must be confirmed by user if not an exact match"],
                    command: [type: "string", description: "Command name, e.g. \"setLevel\". Must be one of the device's supported commands (see hub_get_device)."],
                    parameters: [type: "array", description: "Ordered command arguments as an array of strings, in the order the command declares them, e.g. [\"75\"] for setLevel or [\"#FF0000\"] for setColor. Omit for no-arg commands like on/off. Each element is a string; numbers and JSON-object values are passed as strings (e.g. [\"{\\\"hue\\\":0,\\\"saturation\\\":100,\\\"level\\\":50}\"]) and coerced hub-side.", items: [type: "string"]]
                ],
                required: ["deviceId", "command"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the command was sent"],
                    device: [type: "string", description: "Device label"],
                    command: [type: "string", description: "Command sent"],
                    parameters: [type: "array", description: "Normalized parameters passed to the command"]
                ],
                required: ["success", "device", "command"]
            ]
        ],
        [
            name: "hub_list_device_events",
            description: """Get event history for a device, an APP (app events: events emitted by an app or rule -- automation events), or the location.

Default: most-recent events for a device (deviceId + optional limit). Add hoursBack to widen/narrow the window (default 24h for app/location modes, max 168h). appId returns an installed app's events instead. Omit deviceId/appId for location-level events.[[FLAT_TRIM]] attribute filters by event name. Higher limits (50+) may slow the hub.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID. Mutually exclusive with appId; omit both for location-level events (mode/HSM/hub variable)."],
                    appId: [type: "integer", description: "Installed-app ID for per-app events (what the app/rule emitted). Rows: {name, value, description, date}. Mutually exclusive with deviceId."],
                    hoursBack: [type: "integer", description: "If set, return up to this many hours of history (max 168 = 7 days) instead of just the most recent events."],
                    attribute: [type: "string", description: "Event-name filter. Device: an attribute (e.g. 'switch'). Location: 'mode', 'hsmStatus', 'hsmAlert', or a hub-variable name."],
                    limit: [type: "integer", description: "Max events to return. Recent mode default 10; history mode default 100 (max 500). Higher values may slow hub.", default: 10]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    events: [type: "array", description: "Event rows (most recent first)", items: [type: "object", properties: [
                        name: [type: "string", description: "Event/attribute name"],
                        value: [description: "Event value"],
                        unit: [type: "string", description: "Unit of measure, if any"],
                        description: [type: "string", description: "Human-readable description text"],
                        date: [type: "string", description: "Event timestamp (ISO)"],
                        type: [type: "string", description: "Location mode only: event type"],
                        isStateChange: [type: "boolean", description: "Whether this event was a state change"]
                    ]]],
                    count: [type: "integer", description: "Events returned"],
                    device: [type: "string", description: "Device label; present in device modes"],
                    deviceId: [type: "string", description: "Device ID; present in history mode"],
                    appId: [type: "integer", description: "App ID; present in app mode"],
                    source: [type: "string", description: "'device', 'app', or 'location'; present in history mode"],
                    hoursBack: [type: "integer", description: "History window in hours; present in history mode"],
                    attributeFilter: [type: "string", description: "Echoed attribute filter; present in history mode"],
                    sinceTimestamp: [type: "string", description: "Window start (ISO); present in history mode"],
                    timeFilterUnparseable: [type: "integer", description: "App/location modes: rows kept despite unparseable dates (window not enforced for them); present when > 0"]
                ]
            ]
        ],
        [
            name: "hub_update_device",
            description: """Update device properties: label, name, deviceNetworkId, room, enabled, dataValues, preferences.

Only modify devices user explicitly requested. Room/enabled require Write master. Call `hub_get_tool_guide(section='update_device')` for preferences format.""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "The device ID to update (from hub_list_devices or hub_list_devices(filter='virtual'))"],
                    label: [type: "string", description: "New display label for the device"],
                    name: [type: "string", description: "New device name"],
                    deviceNetworkId: [type: "string", description: "New device network ID (must be unique across all hub devices)"],
                    room: [type: "string", description: "Room name to assign the device to (case-sensitive, must match an existing room)"],
                    enabled: [type: "boolean", description: "Set to true to enable or false to disable the device"],
                    dataValues: [type: "object", description: "Key-value pairs to set in the device's Data section. Example: {\"firmware\": \"1.2.3\", \"model\": \"ABC\"}",
                        additionalProperties: [type: "string"]],
                    preferences: [type: "object", description: "Device preferences to update. Each value must be an object with 'type' and 'value'. Example: {\"pollInterval\": {\"type\": \"number\", \"value\": 30}}"]
                ],
                required: ["deviceId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "True when all requested changes applied without error"],
                    device: [type: "string", description: "Device label"],
                    deviceId: [type: "string", description: "Device ID"],
                    changes: [type: "array", description: "Applied changes", items: [type: "object", properties: [
                        property: [type: "string", description: "Property changed"],
                        oldValue: [description: "Prior value, when known"],
                        newValue: [description: "New value"]
                    ]]],
                    errors: [type: "array", description: "Per-property failures, or null when none", items: [type: "object", properties: [
                        property: [type: "string", description: "Property that failed"],
                        error: [type: "string", description: "Failure reason"]
                    ]]],
                    message: [type: "string", description: "Human-readable summary"]
                ],
                required: ["success", "device", "deviceId", "message"]
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
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether deletion was verified"],
                    deviceId: [type: "string", description: "Deleted device ID"],
                    deviceName: [type: "string", description: "Device label/name"],
                    message: [type: "string", description: "Human-readable result"],
                    warnings: [type: "array", description: "Pre-delete warnings (active device, radio membership, rule references)", items: [type: "string"]],
                    auditInfo: [type: "object", description: "Audit trail", properties: [
                        deletedAt: [type: "string", description: "Deletion timestamp"],
                        deviceType: [type: "string", description: "Driver/type name"],
                        deviceNetworkId: [type: "string", description: "Deleted device's DNI"],
                        driverName: [type: "string", description: "Driver name"],
                        lastHubBackup: [type: "string", description: "Last hub backup timestamp"]
                    ]]
                ],
                required: ["success", "deviceId", "deviceName", "message"]
            ]
        ],
        [
            name: "hub_call_device_swap",
            description: """⚠️ DESTRUCTIVE: Swap a device — replace from_device_id with to_device_id across ALL apps and rules that reference it, in one operation.[[FLAT_TRIM]] Drives the hub's built-in Swap Device tool; use to migrate device references to new hardware or swap out a failing device without editing each automation.[[/FLAT_TRIM]]

Pre-flight (mandatory): 1) hub backup <24h (hub_create_backup); 2) preview the blast radius with hub_list_device_dependents(deviceId=from_device_id) — every app listed gets rewired; 3) confirm with the user.[[FLAT_TRIM]]

The hub only offers compatible replacement devices: an incompatible to_device_id fails with a structured error listing the compatible options.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    from_device_id: [type: "string", description: "Device ID whose references will be replaced everywhere (from hub_list_devices)."],
                    to_device_id: [type: "string", description: "Replacement device ID. Must be capability-compatible — on mismatch the error lists the compatible candidates."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms a hub backup exists (<24h) and the user approved the swap."]
                ],
                required: ["from_device_id", "to_device_id", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the swap completed"],
                    swapped: [type: "object", description: "The committed swap", properties: [
                        from: [type: "string", description: "Replaced device ID"],
                        to: [type: "string", description: "Replacement device ID"]
                    ]],
                    verified: [type: "boolean", description: "true when the before/after dependent counts were both read and confirmed the swap; false = swap clicked but count verification was degraded"],
                    appsRewired: [type: "integer", description: "Apps that referenced from_device_id before the swap (the rewired set); absent when the pre-count was unavailable"],
                    remainingDependents: [type: "integer", description: "Apps still referencing from_device_id after the swap (0 expected)"],
                    note: [type: "string", description: "Verification guidance"],
                    error: [type: "string", description: "Failure reason (success=false)"],
                    compatibleOptions: [type: "array", description: "Incompatible-target failure: compatible replacement devices the hub offered (first 30)", items: [type: "object", properties: [
                        id: [type: "string", description: "Device ID"],
                        label: [type: "string", description: "Device label"]
                    ]]],
                    compatibleOptionCount: [type: "integer", description: "Total compatible options offered (may exceed the 30 listed)"],
                    buttonsFound: [type: "array", description: "Button-discovery failure: action button names found on the swap page", items: [type: "string"]]
                ],
                required: ["success"]
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
        "hub_list_devices", "hub_get_device", "hub_get_device_attribute", "hub_list_device_events"
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
        hub_get_device: [title: "Get Device Details", summary: "Get one device's full details: attributes, commands, capabilities."],
        hub_get_device_attribute: [title: "Get Device Attribute", summary: "Read one attribute value, optionally waiting until it matches an expected value."],
        hub_list_device_events: [title: "List Device Events", summary: "Recent events for a device or app, or location events when neither is given."],
        hub_call_device_command: [title: "Send Device Command", summary: "Send a command like on, off, or setLevel to a device."],
        hub_call_device_swap: [title: "Swap Device", summary: "Replace a device across all apps and rules that reference it, in one operation."],
        hub_update_device: [title: "Update Device Properties", summary: "Update a device's label, room, or preferences."],
        hub_delete_device: [title: "Delete Device", summary: "Permanently delete a device from the hub (no undo)."]
    ]
}
