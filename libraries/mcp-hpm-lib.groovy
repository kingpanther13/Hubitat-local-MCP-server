library(name: "McpHpmLib", namespace: "mcp", author: "kingpanther13", description: "Hubitat Package Manager read tool implementations (hub_list_hpm_packages + drift detection) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

private Map _fetchAppsListTree(String caller) {
    def responseText
    try {
        responseText = hubInternalGet("/hub2/appsList")
    } catch (Exception e) {
        throw new IllegalArgumentException("Failed to fetch installed apps for ${caller} [${e.class.simpleName}]: ${e.message ?: e.toString()}")
    }
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from /hub2/appsList during ${caller} -- hub internal API may be unavailable")
    }
    try {
        return new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        throw new IllegalArgumentException("Failed to parse /hub2/appsList for ${caller}: ${e.message ?: e.toString()}")
    }
}

private String _hpmDiscoverAppId() {
    def parsed = _fetchAppsListTree("HPM discovery")
    // Walk the installed-app instance tree (apps[]) using a plain List as a BFS work queue.
    // Collects ALL matches unconditionally so duplicate HPM installs are surfaced as an error
    // rather than silently returning the first. BFS walks children[] at every node to handle
    // HPM nested under a parent app.
    def hpmMatches = []
    def workQueue = [] + (parsed?.apps ?: [])
    int qi = 0
    while (qi < workQueue.size()) {
        def node = workQueue[qi++]
        def d = node?.data ?: [:]
        if (d.type?.toString() == "Hubitat Package Manager") {
            if (d.id == null) throw new IllegalArgumentException("HPM entry found but has no id field -- cannot determine hpmAppId; pass hpmAppId explicitly")
            hpmMatches << d.id.toString()
        }
        (node?.children ?: []).each { workQueue << it }
    }
    if (hpmMatches.isEmpty()) {
        throw new IllegalArgumentException("HPM not found in installed apps -- Hubitat Package Manager does not appear to be installed")
    }
    if (hpmMatches.size() > 1) {
        def cap = 10
        def shown = hpmMatches.take(cap).join(', ')
        def extra = hpmMatches.size() > cap ? " and ${hpmMatches.size() - cap} more (total ${hpmMatches.size()})" : ""
        throw new IllegalArgumentException("Multiple HPM instances found: [${shown}]${extra} -- pass hpmAppId explicitly to select one")
    }
    return hpmMatches[0]
}

private void _hpmAssertAppIsHpm(String explicitAppId) {
    def parsed = _fetchAppsListTree("hpmAppId validation")
    // Walk the installed-app instance tree looking for the entry with the given id.
    // Early-exit on match: children[] are only enqueued when the current node does NOT match,
    // avoiding unnecessary BFS expansion after the target is found.
    def workQueue = [] + (parsed?.apps ?: [])
    int qi = 0
    def foundEntry = null
    while (qi < workQueue.size() && foundEntry == null) {
        def node = workQueue[qi++]
        def d = node?.data ?: [:]
        if (d.id?.toString() == explicitAppId) {
            foundEntry = d
        } else {
            (node?.children ?: []).each { workQueue << it }
        }
    }
    if (foundEntry == null) {
        throw new IllegalArgumentException("hpmAppId ${explicitAppId} not found in installed apps -- verify the ID or omit hpmAppId to use auto-discovery")
    }
    def actualType = foundEntry.type?.toString() ?: "unknown"
    if (actualType != "Hubitat Package Manager") {
        throw new IllegalArgumentException("hpmAppId ${explicitAppId} is not Hubitat Package Manager (actual type: ${actualType}) -- verify the ID or omit hpmAppId to use auto-discovery")
    }
}

private Map _hpmFetchManifests(String hpmAppId) {
    def responseText
    try {
        responseText = hubInternalGet("/installedapp/statusJson/${hpmAppId}")
    } catch (Exception e) {
        throw new IllegalArgumentException("Failed to fetch HPM statusJson [${e.class.simpleName}]: ${e.message ?: e.toString()}")
    }
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from /installedapp/statusJson/${hpmAppId} -- app may not exist or hub internal API is unavailable")
    }
    def outer
    try {
        outer = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        throw new IllegalArgumentException("Failed to parse HPM statusJson [${e.class.simpleName}]: ${e.message ?: e.toString()}")
    }
    if (!(outer instanceof Map)) {
        // actualTypeName diagnostic labels: "List", "null", or "non-object" (any non-Map, non-List, non-null scalar).
        def actualTypeName = (outer instanceof List) ? "List" : (outer == null ? "null" : "non-object")
        throw new IllegalArgumentException("Unexpected HPM statusJson shape: expected a JSON object, got ${actualTypeName}")
    }
    // Find the 'manifests' entry in appState[].
    // appState[].value shape varies by firmware: live hubs typically return the value already
    // as a Map (JsonSlurper recursively parsed the inner JSON). Older firmware or large payloads
    // may leave it as a JSON-encoded String. Handle both: if it's already a Map, use it directly.
    def appState = outer.appState ?: []
    def manifestsEntry = appState.find { it?.name?.toString() == "manifests" }
    if (manifestsEntry == null) {
        // HPM installed but no packages tracked yet.
        return [:]
    }
    def rawValue = manifestsEntry.value
    if (rawValue == null) {
        return [:]
    }
    def manifests
    if (rawValue instanceof Map) {
        // JsonSlurper already parsed the inner JSON string into a Map.
        manifests = rawValue
    } else {
        def rawStr = rawValue.toString().trim()
        if (rawStr.isEmpty()) return [:]
        try {
            manifests = new groovy.json.JsonSlurper().parseText(rawStr)
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse HPM manifests value [${e.class.simpleName}]: ${e.message ?: e.toString()}")
        }
    }
    if (!(manifests instanceof Map)) {
        def actualTypeName = (manifests instanceof List) ? "List" : (manifests == null ? "null" : "non-object")
        throw new IllegalArgumentException("Unexpected HPM manifests shape: expected a JSON object keyed by manifest URL, got ${actualTypeName}")
    }
    return manifests
}

def toolListHpmPackages(args) {

    def hpmAppId
    if (args?.hpmAppId != null && args.hpmAppId.toString().trim() != "") {
        hpmAppId = args.hpmAppId.toString().trim()
        if (!hpmAppId.isInteger()) {
            throw new IllegalArgumentException("hpmAppId must be numeric: ${hpmAppId}")
        }
        _hpmAssertAppIsHpm(hpmAppId)
    } else {
        hpmAppId = _hpmDiscoverAppId()
    }

    mcpLog("info", "hpm", "hub_list_hpm_packages hpmAppId=${hpmAppId}")

    def manifests
    try {
        manifests = _hpmFetchManifests(hpmAppId)
    } catch (IllegalArgumentException e) {
        return [success: false, error: e.message, hpmAppId: hpmAppId]
    }

    def skippedMalformed = []
    def packages = manifests.collect { manifestUrl, manifest ->
        if (!(manifest instanceof Map)) {
            mcpLog("warn", "hpm", "hub_list_hpm_packages: skipping malformed manifest entry for URL ${manifestUrl} -- value is not a Map")
            skippedMalformed << manifestUrl?.toString()
            return null
        }
        int skippedAppCount = 0
        int skippedDriverCount = 0
        int skippedFileCount = 0
        def apps = (manifest.apps ?: []).collect { a ->
            if (!(a instanceof Map)) { skippedAppCount++; mcpLog("warn", "hpm", "hub_list_hpm_packages: non-Map app component in '${manifest.packageName}' (value: ${a?.toString()?.take(60) ?: 'null'}) -- skipped"); return null }
            def heId = a.heID
            def heIdStr = null
            def heIdWarning = null
            if (heId instanceof String && heId.trim() == "") {
                // Empty/whitespace-only String heID normalized to null -- surfaces the data quality
                // issue without breaking the consumer (matches drift treatment).
                heIdWarning = "empty heID string '${heId}' normalized to null"
                heId = null
                mcpLog("warn", "hpm", "hub_list_hpm_packages: app '${a.name}' in '${manifest.packageName}' heID is empty/whitespace -- normalized to null")
            }
            // Whitespace-padded String heID (e.g. " 142 ") is normalized to the trimmed value so
            // that heID surfaces correctly to the consumer (matches get_hpm_drift treatment).
            if (heId instanceof String && heId.trim() != heId) {
                def trimmed = heId.trim()
                heIdWarning = "whitespace-padded heID '${heId}' normalized to '${trimmed}'"
                mcpLog("warn", "hpm", "hub_list_hpm_packages: app '${a.name}' in '${manifest.packageName}' heID has surrounding whitespace -- normalized to '${trimmed}'")
                heId = trimmed
            }
            if (heId != null) {
                if (heId instanceof Number || heId instanceof String) {
                    heIdStr = heId.toString()
                } else {
                    heIdWarning = "non-scalar heID (not Number or String) -- heID cleared"
                    mcpLog("warn", "hpm", "hub_list_hpm_packages: app '${a.name}' in '${manifest.packageName}' heID is not a Number or String -- skipping heID")
                }
            }
            def entry = [
                id      : a.id?.toString(),
                name    : a.name?.toString(),
                required: a.required == true,
                version : a.version?.toString(),
                heID    : heIdStr
            ]
            if (heIdWarning) entry._warning = heIdWarning
            entry
        }.findAll { it != null }

        def drivers = (manifest.drivers ?: []).collect { d ->
            if (!(d instanceof Map)) { skippedDriverCount++; mcpLog("warn", "hpm", "hub_list_hpm_packages: non-Map driver component in '${manifest.packageName}' (value: ${d?.toString()?.take(60) ?: 'null'}) -- skipped"); return null }
            def heId = d.heID
            def heIdStr = null
            def heIdWarning = null
            if (heId instanceof String && heId.trim() == "") {
                // Empty/whitespace-only String heID normalized to null -- surfaces the data quality
                // issue without breaking the consumer (matches drift treatment).
                heIdWarning = "empty heID string '${heId}' normalized to null"
                heId = null
                mcpLog("warn", "hpm", "hub_list_hpm_packages: driver '${d.name}' in '${manifest.packageName}' heID is empty/whitespace -- normalized to null")
            }
            // Whitespace-padded String heID normalized to trimmed value (matches app treatment above).
            if (heId instanceof String && heId.trim() != heId) {
                def trimmed = heId.trim()
                heIdWarning = "whitespace-padded heID '${heId}' normalized to '${trimmed}'"
                mcpLog("warn", "hpm", "hub_list_hpm_packages: driver '${d.name}' in '${manifest.packageName}' heID has surrounding whitespace -- normalized to '${trimmed}'")
                heId = trimmed
            }
            if (heId != null) {
                if (heId instanceof Number || heId instanceof String) {
                    heIdStr = heId.toString()
                } else {
                    heIdWarning = "non-scalar heID (not Number or String) -- heID cleared"
                    mcpLog("warn", "hpm", "hub_list_hpm_packages: driver '${d.name}' in '${manifest.packageName}' heID is not a Number or String -- skipping heID")
                }
            }
            def entry = [
                id      : d.id?.toString(),
                name    : d.name?.toString(),
                required: d.required == true,
                version : d.version?.toString(),
                heID    : heIdStr
            ]
            if (heIdWarning) entry._warning = heIdWarning
            entry
        }.findAll { it != null }

        def files = (manifest.files ?: []).collect { f ->
            if (!(f instanceof Map)) { skippedFileCount++; mcpLog("warn", "hpm", "hub_list_hpm_packages: non-Map file component in '${manifest.packageName}' (value: ${f?.toString()?.take(60) ?: 'null'}) -- skipped"); return null }
            [
                id  : f.id?.toString(),
                name: f.name?.toString()
            ]
        }.findAll { it != null }

        def pkg = [
            manifestUrl : manifestUrl?.toString(),
            packageName : manifest.packageName?.toString(),
            version     : manifest.version?.toString(),
            beta        : manifest.beta == true,
            author      : manifest.author?.toString(),
            apps        : apps,
            drivers     : drivers,
            files       : files
        ]
        if (skippedAppCount > 0)    pkg.skippedAppCount    = skippedAppCount
        if (skippedDriverCount > 0) pkg.skippedDriverCount = skippedDriverCount
        if (skippedFileCount > 0)   pkg.skippedFileCount   = skippedFileCount
        pkg
    }.findAll { it != null }

    def cursor = args?.cursor
    def paged = _paginateList(packages, cursor, 25, "hub_list_hpm_packages")
    def result = [
        success   : true,
        hpmAppId  : hpmAppId,
        count     : paged.page.size(),
        packages  : paged.page
    ]
    if (cursor != null) {
        result.total = packages.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    if (skippedMalformed) result.skippedMalformed = skippedMalformed
    return result
}

def toolGetHpmDrift(args) {

    def hpmAppId
    if (args?.hpmAppId != null && args.hpmAppId.toString().trim() != "") {
        hpmAppId = args.hpmAppId.toString().trim()
        if (!hpmAppId.isInteger()) {
            throw new IllegalArgumentException("hpmAppId must be numeric: ${hpmAppId}")
        }
        _hpmAssertAppIsHpm(hpmAppId)
    } else {
        hpmAppId = _hpmDiscoverAppId()
    }

    def packageFilterRaw = args?.packageFilter?.toString()?.trim()
    def packageFilter = packageFilterRaw ?: null

    mcpLog("info", "hpm", "get_hpm_drift hpmAppId=${hpmAppId} packageFilter=${packageFilter ?: 'none'}")

    def manifests
    try {
        manifests = _hpmFetchManifests(hpmAppId)
    } catch (IllegalArgumentException e) {
        return [success: false, error: e.message, hpmAppId: hpmAppId]
    }

    // Build the set of Apps Code definition IDs for orphan-app detection.
    // /hub2/userAppTypes is the dedicated Apps Code registry endpoint -- each entry
    // represents a code definition (whether or not it has any running instances).
    // This is distinct from the userAppTypes[] array embedded in /hub2/appsList,
    // which represents installed instances. Child-app templates (e.g. "MCP Rule")
    // have code definitions here but zero installed instances in the apps[] tree,
    // so orphan detection MUST use this endpoint to avoid false positives on
    // HPM-managed child-app-template packages.
    def installedAppCodeIds = [] as Set
    def orphanDetection = [enabled: true]
    try {
        def userAppTypesText = hubInternalGet("/hub2/userAppTypes")
        if (!userAppTypesText) {
            orphanDetection = [enabled: false, reason: "Empty response from /hub2/userAppTypes -- orphan-app signals were not evaluated this call"]
            mcpLog("warn", "hpm", "get_hpm_drift: empty /hub2/userAppTypes response -- orphan detection disabled")
        } else {
            def userAppTypesParsed = new groovy.json.JsonSlurper().parseText(userAppTypesText)
            if (!(userAppTypesParsed instanceof List)) {
                def actualTypeName = (userAppTypesParsed instanceof Map) ? "Map" : (userAppTypesParsed == null ? "null" : "unknown")
                def rawPreview = userAppTypesParsed?.toString() ?: ""
                def actualPreview = rawPreview.length() > 200 ? rawPreview.take(200) + " (truncated)" : rawPreview
                orphanDetection = [enabled: false, reason: "Unexpected /hub2/userAppTypes response shape (expected JSON array, got ${actualTypeName}: ${actualPreview}) -- orphan-app signals were not evaluated this call"]
                mcpLog("warn", "hpm", "get_hpm_drift: /hub2/userAppTypes returned non-List shape (${actualTypeName}) -- orphan detection disabled")
            } else {
                userAppTypesParsed.each { t ->
                    def typeId = t?.id?.toString()
                    if (typeId) installedAppCodeIds << typeId
                }
            }
        }
    } catch (Exception e) {
        orphanDetection = [enabled: false, reason: "Failed to fetch /hub2/userAppTypes [${e.class.simpleName}]: ${e.message ?: e.toString()} -- orphan-app signals were not evaluated this call"]
        mcpLog("warn", "hpm", "get_hpm_drift: could not fetch /hub2/userAppTypes for orphan-app detection: ${e.message ?: e.toString()}")
    }

    // Build the set of Drivers Code definition IDs for orphan-driver detection.
    // /hub2/userDeviceTypes is the Drivers Code registry endpoint -- each entry
    // represents a driver code definition (whether or not any devices use it).
    // Note: the endpoint name follows the hub's naming convention (userDeviceTypes, not
    // userDriverTypes); this is the same endpoint used by hub_list_drivers.
    def installedDriverCodeIds = [] as Set
    def orphanDriverDetection = [enabled: true]
    try {
        def userDeviceTypesText = hubInternalGet("/hub2/userDeviceTypes")
        if (!userDeviceTypesText) {
            orphanDriverDetection = [enabled: false, reason: "Empty response from /hub2/userDeviceTypes -- orphan-driver signals were not evaluated this call"]
            mcpLog("warn", "hpm", "get_hpm_drift: empty /hub2/userDeviceTypes response -- orphan driver detection disabled")
        } else {
            def userDeviceTypesParsed = new groovy.json.JsonSlurper().parseText(userDeviceTypesText)
            if (!(userDeviceTypesParsed instanceof List)) {
                def actualTypeName = (userDeviceTypesParsed instanceof Map) ? "Map" : (userDeviceTypesParsed == null ? "null" : "unknown")
                def rawPreview = userDeviceTypesParsed?.toString() ?: ""
                def actualPreview = rawPreview.length() > 200 ? rawPreview.take(200) + " (truncated)" : rawPreview
                orphanDriverDetection = [enabled: false, reason: "Unexpected /hub2/userDeviceTypes response shape (expected JSON array, got ${actualTypeName}: ${actualPreview}) -- orphan-driver signals were not evaluated this call"]
                mcpLog("warn", "hpm", "get_hpm_drift: /hub2/userDeviceTypes returned non-List shape (${actualTypeName}) -- orphan driver detection disabled")
            } else {
                userDeviceTypesParsed.each { t ->
                    def typeId = t?.id?.toString()
                    if (typeId) installedDriverCodeIds << typeId
                }
            }
        }
    } catch (Exception e) {
        orphanDriverDetection = [enabled: false, reason: "Failed to fetch /hub2/userDeviceTypes [${e.class.simpleName}]: ${e.message ?: e.toString()} -- orphan-driver signals were not evaluated this call"]
        mcpLog("warn", "hpm", "get_hpm_drift: could not fetch /hub2/userDeviceTypes for orphan-driver detection: ${e.message ?: e.toString()}")
    }

    // Apply optional package filter before drift analysis.
    def filteredManifests = packageFilter
        ? manifests.findAll { url, m -> m instanceof Map && m.packageName?.toString()?.toLowerCase()?.contains(packageFilter.toLowerCase()) }
        : manifests

    def driftEntries = []
    def driftSkippedMalformed = []
    def driftDataQualityWarnings = []
    int totalSignals = 0

    filteredManifests.each { manifestUrl, manifest ->
        if (!(manifest instanceof Map)) {
            mcpLog("warn", "hpm", "get_hpm_drift: skipping malformed manifest entry for URL ${manifestUrl} -- value is not a Map")
            driftSkippedMalformed << manifestUrl?.toString()
            return
        }
        def signals = []
        // Data-quality warnings are collected separately and do NOT roll up into totalDriftSignals.
        // This keeps the contract clean: totalDriftSignals counts actionable drift (missing-required,
        // orphan-app, orphan-driver) and does not inflate for data-quality issues in the manifest.
        def dataQualityWarnings = []
        int skippedAppCount = 0
        int skippedDriverCount = 0

        // missing-required: required=true AND heID is null/absent
        // orphan-app: heID present but not in Apps Code registry (/hub2/userAppTypes endpoint)
        // Both checks share the per-component heID resolution loop below.
        (manifest.apps ?: []).each { a ->
            if (!(a instanceof Map)) {
                skippedAppCount++
                dataQualityWarnings << [
                    type         : "skipped-malformed-component",
                    componentType: "app",
                    _warning     : "app component entry is not a Map -- skipped"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: non-Map app component in '${manifest.packageName}' (value: ${a?.toString()?.take(60) ?: 'null'}) -- skipped")
                return
            }
            def heId = a.heID
            // Normalize empty/whitespace-only String heID to null and surface as data-quality warning
            // so the consumer knows the manifest has a blank heID (matches hub_list_hpm_packages treatment).
            if (heId instanceof String && heId.trim() == "") {
                dataQualityWarnings << [
                    type         : "empty-heid",
                    componentType: "app",
                    componentName: a.name?.toString(),
                    componentId  : a.id?.toString(),
                    _warning     : "empty heID string '${heId}' normalized to null"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: app '${a.name}' in '${manifest.packageName}' heID is empty/whitespace -- normalized to null")
                heId = null
            }
            // Whitespace-padded String heID (e.g. " 142 ") would match nothing in the registry
            // via verbatim .toString() lookup. Surface as a data-quality warning and normalize
            // to the trimmed value so the lookup proceeds correctly. The component is KEPT --
            // drift checks continue against the trimmed heID.
            if (heId instanceof String && heId.trim() != heId) {
                def trimmed = heId.trim()
                dataQualityWarnings << [
                    type         : "heid-whitespace-normalized",
                    componentType: "app",
                    componentName: a.name?.toString(),
                    componentId  : a.id?.toString(),
                    _warning     : "whitespace-padded heID '${heId}' normalized to '${trimmed}'"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: app '${a.name}' in '${manifest.packageName}' heID has surrounding whitespace ('${heId}') -- normalized to '${trimmed}'")
                heId = trimmed
            }
            // Type-validate heID at the boundary: Number and String are the only valid scalar types.
            // Non-scalar heID (List, Map, Boolean) cannot be matched against Apps Code IDs and
            // would produce guaranteed false-positive orphan signals via .toString() coercion.
            // The component is DROPPED -- drift checks do not run for this entry.
            if (heId != null && !(heId instanceof Number) && !(heId instanceof String)) {
                mcpLog("warn", "hpm", "get_hpm_drift: app '${a.name}' in '${manifest.packageName}' heID is not a Number or String (value: ${heId?.toString()?.take(60) ?: 'null'}) -- skipping component")
                dataQualityWarnings << [
                    type         : "heid-non-scalar-dropped",
                    componentType: "app",
                    componentName: a.name?.toString(),
                    componentId  : a.id?.toString(),
                    _warning     : "non-scalar heID (not Number or String) -- component skipped"
                ]
                return
            }
            def heIdNull = heId == null
            // signals[] field-shape convention: orphan-* entries carry `heID` (the orphaned id);
            // missing-required entries omit `heID` because the value is null by definition of the signal.
            if (a.required == true && heIdNull) {
                signals << [
                    type         : "missing-required",
                    componentType: "app",
                    componentName: a.name?.toString(),
                    componentId  : a.id?.toString(),
                    note         : "Component is required but heID is null/absent -- install never completed or component was removed."
                ]
            }
            if (!heIdNull && orphanDetection.enabled) {
                def heIdStr = heId.toString()
                if (!installedAppCodeIds.contains(heIdStr)) {
                    signals << [
                        type         : "orphan-app",
                        componentType: "app",
                        componentName: a.name?.toString(),
                        componentId  : a.id?.toString(),
                        heID         : heIdStr,
                        note         : "HPM tracks heID ${heIdStr} but the app code definition is no longer in Apps Code -- likely deleted via Apps Code without using HPM Uninstall."
                    ]
                }
            }
        }

        // missing-required: required=true AND heID is null/absent (drivers)
        // orphan-driver: heID present but not in Drivers Code registry (/hub2/userDeviceTypes endpoint)
        // signals[] field-shape convention identical to apps loop above: orphan-* carries heID, missing-required omits it.
        (manifest.drivers ?: []).each { d ->
            if (!(d instanceof Map)) {
                skippedDriverCount++
                dataQualityWarnings << [
                    type         : "skipped-malformed-component",
                    componentType: "driver",
                    _warning     : "driver component entry is not a Map -- skipped"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: non-Map driver component in '${manifest.packageName}' (value: ${d?.toString()?.take(60) ?: 'null'}) -- skipped")
                return
            }
            def heId = d.heID
            // Normalize empty/whitespace-only String heID to null and surface as data-quality warning.
            if (heId instanceof String && heId.trim() == "") {
                dataQualityWarnings << [
                    type         : "empty-heid",
                    componentType: "driver",
                    componentName: d.name?.toString(),
                    componentId  : d.id?.toString(),
                    _warning     : "empty heID string '${heId}' normalized to null"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: driver '${d.name}' in '${manifest.packageName}' heID is empty/whitespace -- normalized to null")
                heId = null
            }
            // Whitespace-padded String heID (e.g. " 89 ") would produce a guaranteed false-positive
            // orphan-driver signal. Surface as a data-quality warning and normalize to trimmed value.
            // The component is KEPT -- drift checks continue against the trimmed heID.
            if (heId instanceof String && heId.trim() != heId) {
                def trimmed = heId.trim()
                dataQualityWarnings << [
                    type         : "heid-whitespace-normalized",
                    componentType: "driver",
                    componentName: d.name?.toString(),
                    componentId  : d.id?.toString(),
                    _warning     : "whitespace-padded heID '${heId}' normalized to '${trimmed}'"
                ]
                mcpLog("warn", "hpm", "get_hpm_drift: driver '${d.name}' in '${manifest.packageName}' heID has surrounding whitespace ('${heId}') -- normalized to '${trimmed}'")
                heId = trimmed
            }
            // Non-scalar heID cannot be matched against Drivers Code IDs. The component is DROPPED
            // -- drift checks do not run for this entry.
            if (heId != null && !(heId instanceof Number) && !(heId instanceof String)) {
                mcpLog("warn", "hpm", "get_hpm_drift: driver '${d.name}' in '${manifest.packageName}' heID is not a Number or String (value: ${heId?.toString()?.take(60) ?: 'null'}) -- skipping component")
                dataQualityWarnings << [
                    type         : "heid-non-scalar-dropped",
                    componentType: "driver",
                    componentName: d.name?.toString(),
                    componentId  : d.id?.toString(),
                    _warning     : "non-scalar heID (not Number or String) -- component skipped"
                ]
                return
            }
            def heIdNull = heId == null
            if (d.required == true && heIdNull) {
                signals << [
                    type         : "missing-required",
                    componentType: "driver",
                    componentName: d.name?.toString(),
                    componentId  : d.id?.toString(),
                    note         : "Component is required but heID is null/absent -- install never completed or component was removed."
                ]
            }
            if (!heIdNull && orphanDriverDetection.enabled) {
                def heIdStr = heId.toString()
                if (!installedDriverCodeIds.contains(heIdStr)) {
                    signals << [
                        type         : "orphan-driver",
                        componentType: "driver",
                        componentName: d.name?.toString(),
                        componentId  : d.id?.toString(),
                        heID         : heIdStr,
                        note         : "HPM tracks heID ${heIdStr} but the driver code definition is no longer in Drivers Code -- likely deleted via Drivers Code without using HPM Uninstall."
                    ]
                }
            }
        }

        if (signals || dataQualityWarnings) {
            def entry = [
                manifestUrl : manifestUrl?.toString(),
                packageName : manifest.packageName?.toString(),
                version     : manifest.version?.toString(),
                signals     : signals
            ]
            if (dataQualityWarnings) entry.dataQualityWarnings = dataQualityWarnings
            if (skippedAppCount > 0)    entry.skippedAppCount    = skippedAppCount
            if (skippedDriverCount > 0) entry.skippedDriverCount = skippedDriverCount
            driftEntries << entry
            totalSignals += signals.size()
            if (dataQualityWarnings) driftDataQualityWarnings.addAll(dataQualityWarnings)
        }
    }

    int checked = filteredManifests.size()
    // Count only packages with actionable drift signals (not data-quality-only entries).
    // A package with only dataQualityWarnings contributes to driftEntries[] for visibility
    // but must not appear in the summary as "showing drift" when totalDriftSignals is 0.
    // Note: drift[].length may exceed packagesWithActionableDrift when data-quality-only
    // packages are present -- those entries are included for visibility but not in the count.
    int packagesWithActionableDrift = driftEntries.count { it.signals }
    def summary = packagesWithActionableDrift == 0
        ? "No drift detected across ${checked} tracked package${checked == 1 ? '' : 's'}."
        : "${packagesWithActionableDrift} of ${checked} tracked package${checked == 1 ? '' : 's'} ${packagesWithActionableDrift == 1 ? 'shows' : 'show'} drift (${totalSignals} total signal${totalSignals == 1 ? '' : 's'})."
    // When one or both detection systems were disabled this call, the summary could mislead a
    // consumer that surfaces only the summary string. Append a partial-detection suffix so the
    // human-readable summary matches the structured orphanDetection / orphanDriverDetection fields.
    def partialSuffixParts = []
    if (!orphanDetection.enabled)       partialSuffixParts << "orphanDetection"
    if (!orphanDriverDetection.enabled) partialSuffixParts << "orphanDriverDetection"
    if (partialSuffixParts) {
        def reasonNoun = partialSuffixParts.size() == 1 ? 'reason' : 'reasons'
        summary += " (partial: ${partialSuffixParts.join('/')} disabled this call -- see ${partialSuffixParts.join('/')} ${reasonNoun})"
    }

    def result = [
        success                     : true,
        hpmAppId                    : hpmAppId,
        packagesChecked             : checked,
        packagesWithActionableDrift : packagesWithActionableDrift,
        drift                       : driftEntries,
        totalDriftSignals           : totalSignals,
        summary                     : summary,
        orphanDetection             : orphanDetection,
        orphanDriverDetection       : orphanDriverDetection,
        limitations                 : "Drift detection is heID-presence-only. Per-component source drift (e.g., post-hub_update_app edits) is NOT detected -- HPM stores no source hashes."
    ]
    if (driftSkippedMalformed) result.skippedMalformed = driftSkippedMalformed
    if (driftDataQualityWarnings) result.dataQualityWarnings = driftDataQualityWarnings
    // When a packageFilter was supplied but matched nothing, surface that explicitly so
    // the caller can distinguish "clean hub" from "typo in filter".
    if (packageFilter && checked == 0) {
        result.filterMatchedZero = true
        result.availablePackages = manifests.findAll { url, m -> m instanceof Map }
            .collect { url, m -> m.packageName?.toString() }
            .findAll { it }
    }
    return result
}

// HPM-packages dispatch helper (same case-block-extraction reason as above):
// optionally attaches per-component drift signals when includeDrift=true.
def toolListHpmPackagesWithDrift(args) {
    def listResult = toolListHpmPackages(args)
    if (args.includeDrift == true && listResult instanceof Map) {
        listResult = listResult + [drift: toolGetHpmDrift(args)]
    }
    return listResult
}

def _getAllToolDefinitions_partHpm() {
    return [
        // HPM Package State Tools
        [
            name: "hub_list_hpm_packages",
            description: """List all packages tracked by Hubitat Package Manager (HPM): name, version, beta flag, author, and component inventory (apps/drivers/files) as HPM recorded at install/update time. Requires Read master; HPM must be installed.[[FLAT_TRIM]]
Set includeDrift=true to ALSO cross-reference tracked state against what is actually installed and attach a `drift` block (missing-required / orphan signals). See `hub_get_tool_guide(section='builtin_app_tools')` for the full drift-signal taxonomy, response-field reference, and caveats.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    hpmAppId: [type: "string", description: "HPM's installed-app ID (decimal). Auto-discovered if omitted.[[FLAT_TRIM]] Scans installed apps for type='Hubitat Package Manager'; pass explicitly to skip that discovery call.[[/FLAT_TRIM]]"],
                    includeDrift: [type: "boolean", description: "Also attach a drift cross-reference under a `drift` key.[[FLAT_TRIM]] Signal types: missing-required/orphan-app/orphan-driver.[[/FLAT_TRIM]] Default false; adds 1-2 hub calls.", default: false],
                    packageFilter: [type: "string", description: "Drift mode only (includeDrift=true): case-insensitive substring filter on packageName."],
                    cursor: [type: "string", description: "Opt-in pagination cursor for the packages list. Omit for unbounded; pass \"\" for the first page, iterate nextCursor.[[FLAT_TRIM]] Page size 25 -- HPM entries carry full app/driver/file inventories so each entry can be large.[[/FLAT_TRIM]]"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the package list was built"],
                    hpmAppId: [description: "HPM installed-app ID (echo for caching)"],
                    count: [type: "integer", description: "Packages returned"],
                    packages: [type: "array", description: "Tracked HPM packages", items: [type: "object", properties: [
                        manifestUrl: [type: "string", description: "Manifest URL"],
                        packageName: [type: "string", description: "Package name"],
                        version: [type: "string", description: "Package version"],
                        beta: [type: "boolean", description: "Beta flag"],
                        author: [type: "string", description: "Author"],
                        apps: [type: "array", description: "App components", items: [type: "object", properties: [
                            id: [type: "string", description: "Manifest-internal component ID"],
                            name: [type: "string", description: "Component name"],
                            required: [type: "boolean", description: "Component is required"],
                            version: [type: "string", description: "Component version, when present"],
                            heID: [type: "string", description: "Hubitat internal code ID; null if never installed"],
                            _warning: [type: "string", description: "heID normalization note, when applied"]
                        ]]],
                        drivers: [type: "array", description: "Driver components", items: [type: "object", properties: [
                            id: [type: "string", description: "Manifest-internal component ID"],
                            name: [type: "string", description: "Component name"],
                            required: [type: "boolean", description: "Component is required"],
                            version: [type: "string", description: "Component version, when present"],
                            heID: [type: "string", description: "Hubitat internal code ID; null if never installed"],
                            _warning: [type: "string", description: "heID normalization note, when applied"]
                        ]]],
                        files: [type: "array", description: "File components", items: [type: "object", properties: [
                            id: [type: "string", description: "Component ID"],
                            name: [type: "string", description: "File name"]
                        ]]],
                        skippedAppCount: [type: "integer", description: "Non-Map app entries skipped (omitted when 0)"],
                        skippedDriverCount: [type: "integer", description: "Non-Map driver entries skipped (omitted when 0)"],
                        skippedFileCount: [type: "integer", description: "Non-Map file entries skipped (omitted when 0)"]
                    ]]],
                    skippedMalformed: [type: "array", description: "Manifest URLs skipped entirely (non-Map top level)", items: [type: "string"]],
                    drift: [type: "object", description: "Drift cross-reference block; present when includeDrift=true"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partHpm() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // HPM (read)
        "hub_list_hpm_packages"
    ]
}

def _toolDisplayMeta_partHpm() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_list_hpm_packages: [title: "List HPM Packages", summary: "List packages tracked by Hubitat Package Manager, optionally with drift detection."]
    ]
}
