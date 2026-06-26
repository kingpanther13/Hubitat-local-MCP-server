library(name: "McpSelfAdminLib", namespace: "mcp", author: "kingpanther13", description: "MCP self-administration tool implementations (hub_update_mcp_settings + the hub_update_package Developer Mode deploy) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolUpdateMcpSettings(args) {
    // IllegalArgumentException (not IllegalStateException) so the dispatcher routes this
    // through the clean -32602 Invalid params branch in handleToolsCall — same exception
    // type the other gates throw (requireDestructiveConfirm, the central master gate).
    // Toggle-off is a config refusal, not an unexpected runtime error worth a stack trace.
    if (!settings.enableDeveloperMode) {
        throw new IllegalArgumentException("Developer Mode tools are disabled. Enable 'Developer Mode Tools' in MCP rule app settings to use hub_update_mcp_settings.")
    }
    requireDestructiveConfirm(args.confirm)

    if (!args.settings || !(args.settings instanceof Map) || args.settings.isEmpty()) {
        throw new IllegalArgumentException("settings must be a non-empty map of {settingName: newValue}")
    }

    // Allowlist of SCALAR settings that can be modified via this tool, with their Hubitat input
    // type (matches the input "<key>", "<type>", ... declarations in the mainPage section).
    // useGateways and publishOutputSchemas are allowlisted: they only reshape tools/list
    // (gateway vs flat; whether outputSchema is advertised) — same class as
    // enableCustomRuleEngine, no write-path or lockout risk; clients must reconnect
    // afterward to pick up the new tool surface.
    // enableMandatoryBPS (issue #299) is allowlisted as an ESCAPE HATCH: hub_update_mcp_settings is
    // itself exempt from the best-practice gate, so letting the AI self-disable the (default-ON) gate
    // here is the documented un-lock path, not a footgun. (The reactive hint has no toggle -- always on.)
    // selectedDevices is ALSO allowed but is NOT in this scalar map: it is the MCP device-access
    // scope (a capability.* multi-select), so it routes to _validateMcpDeviceScope (atomic id
    // validation + lockout guard + the capability.* List write) rather than the scalar coerce path.
    // Excluded:
    //   enableWrite          — would footgun: could disable own write path mid-session
    //   enableDeveloperMode  — lockout protection (must remain UI-only to disable)
    //   disabled_tools / disabled_gateways — could self-disable this tool; UI-only (Advanced page)
    def allowedSettings = [
        "mcpLogLevel":            "enum",
        "debugLogging":           "bool",
        "maxCapturedStates":      "number",
        "loopGuardMax":           "number",
        "loopGuardWindowSec":     "number",
        "enableRead":             "bool",
        "enableCustomRuleEngine": "bool",
        "useGateways":            "bool",
        "publishOutputSchemas":   "bool",
        "enableMandatoryBPS":     "bool"
    ]
    // Allowed keys for the not-allowed error message = scalar allowlist + the special selectedDevices key.
    def allowedKeyNames = ((allowedSettings.keySet() + ["selectedDevices"]) as List).sort()

    // Validate, coerce, and stage each update. Validation is fully atomic — every key
    // and every value is checked BEFORE any app.updateSetting() fires, so a single bad
    // entry in a multi-key batch can't leave the hub in a half-applied state. This
    // includes per-key sub-validation that would otherwise only fire during apply
    // (e.g. mcpLogLevel against getLogLevels()).
    //
    // Type coercion is also mandatory: a JSON-RPC client (e.g. a curl-based CI script)
    // may send "true"/"false" or "50" as strings instead of native bool/number. Without
    // coercion, app.updateSetting(key, [type:'bool', value:'false']) writes the string
    // "false", which is truthy in Groovy — silently flipping the toggle to enabled
    // when the caller meant disabled.
    def updates = [:]
    boolean hasDeviceScope = false
    def deviceScopeRaw = null
    args.settings.each { key, value ->
        def keyStr = key.toString()
        if (keyStr == "selectedDevices") {
            // Special-cased BECAUSE the device-access scope is not a scalar setting: it is a
            // capability.* multi-select whose value must be written as a List
            // (app.updateSetting("selectedDevices", [type:"capability.*", value:<List>])), which
            // coerceSettingValue cannot produce, and it needs atomic id validation against the hub
            // plus a lockout guard that the scalar keys have no analogue for. So defer its full
            // validation to _validateMcpDeviceScope -- but capture it here so the scalar loop skips it.
            hasDeviceScope = true
            deviceScopeRaw = value
            return  // continue the each-closure
        }
        if (!allowedSettings.containsKey(keyStr)) {
            throw new IllegalArgumentException("Setting '${keyStr}' is not allowed for self-modification via hub_update_mcp_settings. Allowed: ${allowedKeyNames.join(', ')}")
        }
        def coerced = coerceSettingValue(keyStr, value, allowedSettings[keyStr])
        // Per-key sub-validation that the apply step would otherwise discover too late.
        // mcpLogLevel must be one of the configured log levels — if 'blarg' slipped through
        // the enum coerce and only failed inside toolSetLogLevel during apply, any prior
        // app.updateSetting() calls in the same batch would have already landed.
        if (keyStr == "mcpLogLevel" && !getLogLevels().contains(coerced)) {
            throw new IllegalArgumentException("Setting 'mcpLogLevel' must be one of ${getLogLevels()}, got: ${coerced}")
        }
        updates[keyStr] = coerced
    }

    // selectedDevices is VALIDATED FIRST (still no write): _validateMcpDeviceScope does its own
    // atomic id validation (one unknown id throws) and the lockout guard up front, so a bad device
    // list rejects the whole batch before ANY write -- scalar or scope -- lands. A runtime fetch
    // failure returns a [success:false] envelope (no throw); surface it as-is so nothing is written.
    def deviceScopeResult = null
    if (hasDeviceScope) {
        deviceScopeResult = _validateMcpDeviceScope(deviceScopeRaw)
        if (deviceScopeResult?.success != true) {
            return deviceScopeResult
        }
    }

    // ===== APPLY PHASE =====
    // Reached only after ALL validation (scalar coerce/sub-validation + scope) has passed, so this
    // is validate-everything-then-apply: nothing above this line writes hub state.
    //
    // NOT fully atomic: Hubitat exposes no rollback primitive, so once the apply phase begins, a
    // later write throwing leaves earlier writes persisted (e.g. the scope write lands, then a scalar
    // write throws -> the scope change stays). This is a narrow window BECAUSE all validation is
    // already complete by here -- the remaining app.updateSetting calls write pre-validated, type-
    // coerced values. The audit ordering below reflects this: the "applied" lines fire only AFTER all
    // writes complete, so an audit can never over-claim success on a partially-applied batch.

    // Two-phase audit so post-mortem investigators can distinguish "validation accepted N
    // keys" (attempted) from "all N writes landed" (applied). Both fire at WARN.
    mcpLog("warn", "developer-mode", "hub_update_mcp_settings: attempted=${updates}")
    if (hasDeviceScope) {
        mcpLog("warn", "developer-mode", "hub_update_mcp_settings selectedDevices: attempted mode=${deviceScopeResult.mode} resulting=${deviceScopeResult.resultIds} added=${deviceScopeResult.added} removed=${deviceScopeResult.removed}")
        // WRITE the device-access scope: re-scope the selectedDevices capability multi-select. The
        // single-device wizard paths write [type:"capability.*", value:<id>]; the multi-select takes
        // the SAME type with a List value. Ids are the String form the hub accepts. Done here (in the
        // apply phase) rather than inside the validate helper so no write happens until every
        // validation passed. The "applied" audit for this is deferred to AFTER the scalar loop below.
        app.updateSetting("selectedDevices", [type: "capability.*", value: deviceScopeResult.resultIds])
    }

    // Apply each scalar update via app.updateSetting() — the documented Hubitat sandbox API for
    // self-modifying app settings. mcpLogLevel needs special handling because the runtime
    // log threshold is cached in state.debugLogs.config (UI display reads from settings).
    //
    // Apply order is intentional: app.updateSetting calls first, then mcpLogLevel last via
    // toolSetLogLevel. If toolSetLogLevel ever evolves to throw on an unexpected condition,
    // the rest of the batch has already landed. Per-key validation above is the primary
    // safeguard; this ordering is belt-and-suspenders.
    updates.each { key, value ->
        if (key == "mcpLogLevel") {
            // Delegate to existing helper — it updates both state cache + setting
            toolSetLogLevel([level: value.toString()])
        } else {
            app.updateSetting(key, [type: allowedSettings[key], value: value])
        }
    }

    // "applied" audits fire only now -- after EVERY write (scope + scalars) has completed -- so a
    // scalar write that throws mid-loop can never leave a falsely-reassuring "applied" line behind.
    mcpLog("warn", "developer-mode", "hub_update_mcp_settings: applied=${updates}")
    if (hasDeviceScope) {
        mcpLog("warn", "developer-mode", "hub_update_mcp_settings selectedDevices: applied mode=${deviceScopeResult.mode} authorizedCount=${deviceScopeResult.resultIds.size()}")
    }

    def updateCount = updates.size() + (hasDeviceScope ? 1 : 0)
    def settingWord = updateCount == 1 ? "setting" : "settings"
    // The base "...may need to reconnect to refresh cached tool schemas..." advisory only applies
    // when a scalar key that reshapes tools/list actually changed. Append it conditionally so a
    // device-scope-only (or non-schema scalar) batch doesn't carry a spurious schema-reconnect
    // note -- and so a combined scalar+scope batch doesn't double up (the device-scope message
    // already carries its own device-visibility reconnect advisory).
    // Keep this set in sync with any allowlisted setting that reshapes tools/list (the catalog or
    // its advertised schemas). Only allowlisted keys can appear in `updates`, so enableWrite /
    // enableDeveloperMode are intentionally omitted -- they are excluded from allowedSettings and
    // can never land here.
    def schemaAffectingKeys = ["enableRead", "enableCustomRuleEngine", "useGateways", "publishOutputSchemas"] as Set
    def touchedSchemaKey = updates.keySet().any { schemaAffectingKeys.contains(it) }
    def message = "Updated ${updateCount} ${settingWord}."
    if (touchedSchemaKey) {
        message += " MCP clients (Claude Code, etc.) may need to reconnect to refresh cached tool schemas if you toggled an enable* flag, useGateways, or publishOutputSchemas."
    }
    def result = [
        success: true,
        updated: updates,
        message: message
    ]
    if (hasDeviceScope) {
        // Fold the device-scope outcome under its own sub-key so a caller sees the resulting
        // authorized set + added/removed diff (and the applied mode) alongside the scalar updates.
        result.selectedDevices = [
            mode: deviceScopeResult.mode,
            authorizedDeviceIds: deviceScopeResult.authorizedDeviceIds,
            authorizedCount: deviceScopeResult.authorizedCount,
            added: deviceScopeResult.added,
            removed: deviceScopeResult.removed
        ]
        result.message = result.message + " " + deviceScopeResult.message
    }
    return result
}

// VALIDATE + COMPUTE a device-access scope change to selectedDevices (no write). Used by
// toolUpdateMcpSettings when the settings batch carries a selectedDevices key. selectedDevices is
// one of the self-admin settings, but it routes here instead of the generic scalar coerce/allowlist
// path BECAUSE its wire format is a capability.* multi-select (it needs a List write,
// app.updateSetting("selectedDevices", [type:"capability.*", value:<List>]), which coerceSettingValue
// cannot produce) and its safety semantics (atomic id validation against the hub + a self-lockout
// guard) have no analogue among the scalar settings. NO dev-mode/confirm gate inside -- the caller
// already gated those.
//
// This function ONLY validates + computes; it does NOT call app.updateSetting and does NOT emit the
// "applied" audit. The caller performs the write in its apply phase, so nothing is written until
// EVERY validation (scalar + scope) has passed (true all-validate-then-all-apply atomicity).
//
// Accepts the structured value {mode: "replace"|"add"|"remove" (default "replace"),
// ids: [<id strings>] (required), allowEmpty: <bool, default false>}, OR a bare array shorthand
// (selectedDevices: ["1","2"] == {mode:"replace", ids:["1","2"]}). replace sets the authorized set
// to exactly ids; add unions; remove subtracts. All three are idempotent for fixed ids.
//
// Returns [success:true, mode, resultIds, added, removed, authorizedDeviceIds, authorizedCount,
// message] on success; returns [success:false, error, note] for a runtime fetch failure; throws
// IllegalArgumentException for caller-recoverable validation errors. Touches no hub state.
private Map _validateMcpDeviceScope(scopeValue) {
    // Accept a bare array (replace shorthand) or a structured {mode, ids, allowEmpty} object.
    def mode = "replace"
    def rawIds
    def allowEmpty = false
    if (scopeValue instanceof List) {
        rawIds = scopeValue
    } else if (scopeValue instanceof Map) {
        mode = (scopeValue.mode == null) ? "replace" : scopeValue.mode.toString().trim()
        rawIds = scopeValue.ids
        allowEmpty = (scopeValue.allowEmpty == true)
    } else {
        throw new IllegalArgumentException("selectedDevices must be an array of device ID strings, or an object {mode, ids, allowEmpty}")
    }

    if (!(mode in ["replace", "add", "remove"])) {
        throw new IllegalArgumentException("selectedDevices mode must be one of: replace, add, remove (got: '${mode}')")
    }
    if (rawIds == null || !(rawIds instanceof List)) {
        throw new IllegalArgumentException("selectedDevices ids is required and must be an array of device ID strings")
    }
    // Normalize every requested id to its String form up front (the hub returns Long ids; MCP
    // callers send strings/ints). Reject a non-scalar or blank-after-trim element before any hub call.
    def requestedIds = []
    rawIds.each { raw ->
        if (raw == null || raw instanceof Map || raw instanceof List) {
            throw new IllegalArgumentException("selectedDevices ids entries must be device ID strings or integers, got: ${raw}")
        }
        def s = raw.toString().trim()
        if (!s) {
            throw new IllegalArgumentException("selectedDevices ids entries must be non-empty device IDs")
        }
        if (!requestedIds.contains(s)) requestedIds << s
    }

    // ATOMIC validation: for replace/add, every requested id must resolve to a real hub device
    // (validated against /device/listWithCapabilities/json -- the only view of every device,
    // authorized or not). Validate ALL before any write so a single bad id can't leave a
    // half-applied scope. remove does NOT validate membership BECAUSE removing an id that isn't
    // present (or no longer exists on the hub) is a harmless no-op, and forcing an unknown-id read
    // fetch there would block a legitimate cleanup of a since-deleted device.
    if (mode in ["replace", "add"] && !requestedIds.isEmpty()) {
        def raw
        try {
            def txt = hubInternalGet("/device/listWithCapabilities/json")
            raw = new groovy.json.JsonSlurper().parseText(txt ?: "[]")
        } catch (Exception e) {
            mcpLog("warn", "developer-mode", "hub_update_mcp_settings selectedDevices: /device/listWithCapabilities/json fetch/parse failed: ${e.message}")
            // isError:true so handleToolsCall hoists this onto the JSON-RPC envelope -- a failed
            // validation that wrote nothing must reach the client AS an error, not a quiet result.
            return [success: false, isError: true, error: "Failed to fetch the all-hub device list (/device/listWithCapabilities/json) to validate selectedDevices: ${e.message}", note: "Endpoint may be unavailable on this firmware; nothing was changed."]
        }
        if (!(raw instanceof List)) {
            mcpLog("warn", "developer-mode", "hub_update_mcp_settings selectedDevices: /device/listWithCapabilities/json returned a non-array response")
            return [success: false, isError: true, error: "Unexpected /device/listWithCapabilities/json response (expected a JSON array); cannot validate selectedDevices.", note: "Hub firmware may have changed the endpoint contract; nothing was changed."]
        }
        def hubDeviceIds = (raw.findAll { it instanceof Map }.collect { it.id?.toString() }.findAll { it != null }) as Set
        def unknown = requestedIds.findAll { !hubDeviceIds.contains(it) }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown device id(s): ${unknown.join(', ')}. Use hub_list_devices(scope='all') to see valid ids. Nothing was changed.")
        }
    }

    // Current authorized set = the ids on the selectedDevices input. Normally a
    // List<DeviceWrapper>; tolerate raw String/Number ids defensively too. FAIL LOUD on an element
    // that resolves to neither (no .id, not a scalar) -- silently dropping it would shrink the scope
    // without telling anyone (this is the current-scope read, so it guards against corrupt stored
    // state). Per the AGENTS.md error contract: surface, don't swallow.
    def currentIds = (settings.selectedDevices ?: []).collect { dev ->
        def id = (dev instanceof String || dev instanceof Number) ? dev.toString() : dev?.id?.toString()
        if (id == null) {
            throw new IllegalArgumentException("settings.selectedDevices contains an unrecognized element (neither a device with an id nor a String/Number id): ${dev}. Nothing was changed.")
        }
        id
    } as List
    def currentSet = currentIds as Set

    // Compute the resulting set per mode. add uses a Set-backed union (LinkedHashSet) so the dedupe
    // is O(n) instead of O(n*m) -- current ids first (insertion order), then the new requested ids.
    def resultIds
    switch (mode) {
        case "replace": resultIds = new ArrayList(requestedIds); break
        case "add":     def u = new LinkedHashSet(currentIds); u.addAll(requestedIds); resultIds = new ArrayList(u); break
        case "remove":  resultIds = currentIds.findAll { !requestedIds.contains(it) }; break
    }
    def resultSet = resultIds as Set

    // Self-lockout guard: an empty resulting set blinds the MCP server to every selected device
    // (only MCP-managed virtual devices would remain reachable). Same footgun class as why
    // enableWrite/enableDeveloperMode are excluded from the scalar allowlist -- require an explicit
    // allowEmpty:true to proceed.
    if (resultSet.isEmpty() && !allowEmpty) {
        throw new IllegalArgumentException("Refusing to empty the MCP device-access scope: the resulting set is empty, which blinds the MCP server to every selected device. Pass selectedDevices.allowEmpty:true to confirm you intend to clear the scope.")
    }

    def added = (resultSet - currentSet) as List
    def removed = (currentSet - resultSet) as List

    // Proper singular/plural for the human-readable message (1 device vs 2 devices).
    def dw = { n -> n == 1 ? "device" : "devices" }
    return [
        success: true,
        mode: mode,
        resultIds: resultIds,
        authorizedDeviceIds: resultIds,
        authorizedCount: resultIds.size(),
        added: added,
        removed: removed,
        message: "MCP device-access scope updated (mode=${mode}): ${resultIds.size()} ${dw(resultIds.size())} authorized, ${added.size()} ${dw(added.size())} added, ${removed.size()} ${dw(removed.size())} removed. Device visibility changed -- MCP clients may need to reconnect to refresh which devices are exposed."
    ]
}

// Coerce + validate a single setting value into the type the Hubitat input expects.
// Throws IllegalArgumentException for unrepresentable values (rather than silently
// substituting a default — that would mask the caller's intent and recreate the same
// kind of footgun the bool string-truthiness bug is fixing).
//
// - bool: accepts native Boolean, plus case-insensitive "true"/"false" strings.
// - number: accepts native Number, plus integer/decimal-formatted strings.
// - enum: passed through; downstream (e.g. toolSetLogLevel) does its own enum-membership
//         check against a tool-specific allowed list.
private coerceSettingValue(String key, value, String type) {
    if (value == null) {
        throw new IllegalArgumentException("Setting '${key}' value cannot be null")
    }
    switch (type) {
        case "bool":
            if (value instanceof Boolean) return value
            def s = value.toString().toLowerCase()
            if (s == "true") return true
            if (s == "false") return false
            throw new IllegalArgumentException("Setting '${key}' expects a boolean (true/false), got: ${value} (${value.class.simpleName})")

        case "number":
            if (value instanceof Number) return value
            def s = value.toString()
            if (s.isInteger()) return s.toInteger()
            if (s.isLong()) return s.toLong()
            if (s.isBigDecimal()) return s.toBigDecimal()
            throw new IllegalArgumentException("Setting '${key}' expects a number, got: ${value} (${value.class.simpleName})")

        case "enum":
            // Pass through as String — downstream validates against tool-specific enum set.
            return value.toString()

        default:
            // Defensive: unknown type from the allowlist map should not reach here.
            return value
    }
}

// Canonical raw-source base for the MCP package. Per-call URLs are "${base}/${ref}/${path}";
// mirrors packageManifest.json's location (.../Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy).
// Overridable per call via baseUrl for forks / CI branches on a different remote.
def getPackageSourceBase() {
    return "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server"
}

// Re-anchor a packageManifest.json item location to the deploy `ref`. Manifest locations
// are full raw URLs committed against a fixed branch (e.g. .../main/bundles/mcp-libraries.zip);
// strip the scheme://host/owner/repo/<branch>/ prefix to the repo-relative path, then rebuild
// against base+ref. This is what lets hub_update_package install an UNMERGED PR's artifacts --
// HPM repair trusts the manifest's branch-pinned URL; we can't. Same 4-segment prefix-strip as
// .github/scripts/mcp_watchdog_deploy.sh (that one hard-codes the raw.githubusercontent.com host;
// this regex is host-agnostic). Returns null when the location is not in the expected
// scheme://host/owner/repo/ref/<path> shape, so the caller fails closed.
def _reanchorToRef(location, String base, String ref) {
    if (!(location instanceof String) || !location.trim()) return null
    def loc = location.trim()
    def rel = loc.replaceFirst(/^https?:\/\/[^\/]+\/[^\/]+\/[^\/]+\/[^\/]+\//, '')
    if (rel == loc || !rel) return null
    return "${base}/${ref}/${rel}".toString()
}

// Bot-published bundle artifact URL for a ref (the bundle-artifacts branch). PRs stopped
// rebuilding the committed bundles/*.zip (the post-merge workflow owns it on main), so the
// publish-bundle-artifact workflow builds the zip on every library-touching push and stores
// it under shas/<full-sha>/ and branches/<branch>/ -- the fresh-zip source that lets this
// tool install an unmerged PR's libraries commit by commit. The zip KEEPS its manifest
// basename inside the artifact path: the hub appears to key the bundle entity on the zip
// filename, so a renamed zip would import as a SECOND entity (duplicate libraries) instead
// of updating the existing one. Returns null when the manifest location shape is unusable.
def _bundleArtifactUrlForRef(location, String base, String ref) {
    if (!(location instanceof String) || !location.trim() || !ref) return null
    def loc = location.trim()
    def rel = loc.replaceFirst(/^https?:\/\/[^\/]+\/[^\/]+\/[^\/]+\/[^\/]+\//, '')
    if (rel == loc || !rel) return null
    def baseName = rel.tokenize('/').last()
    // Full 40-hex refs key by SHA; everything else is treated as a branch name. A SHORT
    // sha has no artifact path (the workflow stores full SHAs only) -- it probes the
    // branches/ path, misses, and falls back to the committed zip.
    def keyPath = (ref ==~ /[0-9a-f]{40}/) ? "shas/${ref}" : "branches/${ref}"
    return "${base}/bundle-artifacts/${keyPath}/${baseName}".toString()
}

// Probe the artifact's tiny .size marker (written atomically alongside the zip by the
// publish workflow) instead of downloading the ~1MB zip just to check existence. An
// integer body means the zip is there; a 404 (throws), an HTML error page, or any
// non-integer body means no artifact -- the caller falls back to the committed zip.
def _bundleArtifactExists(String artifactUrl) {
    try {
        def r = _httpFetchUrl("${artifactUrl}.size")
        return ((r?.status as Integer) == 200) && (r?.body?.toString()?.trim() ==~ /\d+/)
    } catch (Exception e) {
        // A legitimate miss (404) and a transient probe failure (rate limit, network blip) both
        // land here; log so a fallback caused by a TRANSIENT failure is at least diagnosable
        // instead of silently indistinguishable from "no artifact published for this ref".
        mcpLog("warn", "developer-mode", "_bundleArtifactExists: probe of ${artifactUrl}.size failed (${e.toString()}) -- treating as no artifact; the bundle leg falls back to the committed zip")
        return false
    }
}

// Parse "#include namespace.Name" directives from Groovy source. Returns an ordered,
// de-duplicated list of include tokens ("namespace.Name") in first-seen order so the
// install order is deterministic across runs.
def _parseIncludeDirectives(String source) {
    def tokens = []
    def seen = [] as Set
    def matcher = (source =~ /(?m)^[ \t]*#include[ \t]+([A-Za-z0-9_]+\.[A-Za-z0-9_]+)[ \t]*$/)
    while (matcher.find()) {
        def tok = matcher.group(1)
        if (!seen.contains(tok)) { seen << tok; tokens << tok }
    }
    return tokens
}

// Resolve the MCP server's own Apps Code CLASS id (distinct from the running INSTANCE
// id) by matching definition()'s namespace+name against /hub2/userAppTypes -- the same
// lookup the e2e deploy script does. Returns the id String, or null when it can't be
// resolved (the caller fails closed and writes nothing).
def _resolveSelfAppClassId() {
    try {
        def responseText = hubInternalGet("/hub2/userAppTypes")
        if (!responseText) return null
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof List)) return null
        def match = parsed.find { it?.namespace == "mcp" && it?.name == "MCP Rule Server" }
        return match?.id?.toString()
    } catch (Exception e) {
        // Reached from the code-update path too (not just hub_update_package); keep the label neutral.
        mcpLog("warn", "hub-admin", "_resolveSelfAppClassId: self app-class lookup failed (${e.toString()}) -- self-deploy detection / #237 compile-error capture is skipped for this update")
        return null
    }
}

def toolUpdatePackage(args) {
    // Developer-Mode gate (mirrors toolUpdateMcpSettings): IllegalArgumentException so
    // the dispatcher routes a toggle-off refusal through the clean -32602 Invalid params
    // branch rather than a 500. The tool is ALSO catalog-hidden when dev mode is off
    // (getDeveloperModeOnlyToolNames) -- this is belt-and-suspenders for a direct call.
    if (!settings.enableDeveloperMode) {
        throw new IllegalArgumentException("Developer Mode tools are disabled. Enable 'Developer Mode Tools' in MCP rule app settings to use hub_update_package.")
    }

    def ref = args?.ref
    if (!(ref instanceof String) || !ref.trim()) {
        throw new IllegalArgumentException("ref is required: a branch, tag, or commit SHA to deploy (e.g. 'main' or a PR head SHA).")
    }
    ref = ref.trim()
    def dryRun = (args?.dryRun == true)

    // Non-dry-run = a real write: enforce confirm + a <24h backup ONCE here (fail fast,
    // before any network I/O). The inner library/app calls re-check the same gate and
    // pass cleanly since the timestamp is already fresh. Dry run writes nothing, so it
    // skips the gate -- it only fetches + parses + plans.
    if (!dryRun) {
        requireDestructiveConfirm(args?.confirm)
    }

    def base = (args?.baseUrl instanceof String && args.baseUrl.trim())
        ? args.baseUrl.trim().replaceAll('/+$', '')
        : getPackageSourceBase()
    // .toString() throughout: _fetchSourceFromUrl / hub_install_bundle / hub_update_app
    // reject a GString importUrl (instanceof String is false for GStringImpl).
    def appUrl = "${base}/${ref}/hubitat-mcp-server.groovy".toString()

    // Fetch the SELF app source at ref first, ONLY to read its #include directives for the
    // bundle-coverage guard below. A fetch failure is a clean abort -- nothing written.
    def appSource
    try {
        appSource = _fetchSourceFromUrl(appUrl)
    } catch (Exception e) {
        return [
            success: false, aborted: true, abortReason: "app_source_fetch_failed", ref: ref, appUrl: appUrl,
            error: "Failed to fetch app source at ref '${ref}' (${appUrl}): ${e.message ?: e.toString()}. Nothing was changed."
        ]
    }
    def includeTokens = _parseIncludeDirectives(appSource)

    // Fetch packageManifest.json AT THE REF -- the authoritative list of what to deploy
    // (HPM's manifest, but at the PR ref so an unmerged PR installs). Fail closed on a
    // fetch or parse error: deploying without the manifest could miss a bundle or app.
    def manifestUrl = "${base}/${ref}/packageManifest.json".toString()
    def manifestText
    try {
        manifestText = _fetchSourceFromUrl(manifestUrl)
    } catch (Exception e) {
        return [
            success: false, aborted: true, abortReason: "manifest_fetch_failed", ref: ref, manifestUrl: manifestUrl,
            error: "Failed to fetch packageManifest.json at ref '${ref}' (${manifestUrl}): ${e.message ?: e.toString()}. Nothing was changed."
        ]
    }
    def manifest
    try {
        manifest = new groovy.json.JsonSlurper().parseText(manifestText)
    } catch (Exception e) {
        manifest = null
    }
    if (!(manifest instanceof Map)) {
        return [
            success: false, aborted: true, abortReason: "manifest_unparseable", ref: ref, manifestUrl: manifestUrl,
            error: "packageManifest.json at ref '${ref}' was not a JSON object. Nothing was changed."
        ]
    }

    // Plan the bundle leg: prefer the bot-published per-ref artifact (fresh for any
    // same-repo ref -- see _bundleArtifactUrlForRef). The fallback depends on the
    // MANIFEST SHAPE AT THE REF, which is what keeps old refs working unchanged:
    //   * unified-delivery manifests (location on the bundle-artifacts branch) fall
    //     back to that location AS-IS -- the branches/main zip, exactly what HPM users
    //     currently install; correct whenever the ref's libraries match current main,
    //     surfaced via bundleFreshnessWarning otherwise.
    //   * legacy manifests (in-tree location) fall back to the zip COMMITTED at the
    //     ref via _reanchorToRef -- correct whenever the ref did not change libraries/
    //     relative to its base.
    def manifestBundles = (manifest.bundles instanceof List) ? manifest.bundles : []
    def plannedBundles = []
    for (b in manifestBundles) {
        def loc = (b?.location instanceof String) ? b.location.trim() : null
        def entry
        if (loc && loc.contains("/bundle-artifacts/")) {
            entry = [name: (b?.name ?: b?.id), url: loc, source: "manifest-current"]
        } else {
            def url = _reanchorToRef(loc, base, ref)
            if (!url) {
                return [
                    success: false, aborted: true, abortReason: "bundle_location_unusable", ref: ref,
                    error: "Bundle '${b?.name ?: b?.id ?: '?'}' in packageManifest.json has an unusable location '${b?.location}' (expected a scheme://host/owner/repo/ref/<path> raw URL or a bundle-artifacts URL). Nothing was changed."
                ]
            }
            entry = [name: (b?.name ?: b?.id), url: url, source: "committed-at-ref"]
        }
        def artifactUrl = _bundleArtifactUrlForRef(loc, base, ref)
        if (artifactUrl && _bundleArtifactExists(artifactUrl)) {
            entry.url = artifactUrl
            entry.source = "bundle-artifacts"
        }
        // SHA guard: a SHA-shaped ref (hex) with NO per-ref artifact would fall back to the manifest's
        // branches/main zip -- i.e. deliver MAIN's libraries, not this commit's (silently wrong, and
        // exactly how an abbreviated/typo'd SHA loads the wrong bundle). A pushed commit ALWAYS has a
        // per-SHA artifact (publish-bundle-artifact builds one per push, keyed by FULL sha), so a
        // SHA-shaped ref with none is abbreviated, unpushed, or typo'd -- fail loudly instead of
        // installing the wrong bundle. (Branch/tag refs legitimately fall back to branches/main with a
        // freshness warning; only commit-SHA refs are guarded, since that's where a hallucinated value bites.)
        if (entry.source == "manifest-current" && ref != null && (ref.toString().trim() ==~ /(?i)^[0-9a-f]{7,40}$/)) {
            return [
                success: false, aborted: true, abortReason: "no_bundle_artifact_for_ref", ref: ref,
                bundle: (b?.name ?: b?.id), artifactUrlProbed: artifactUrl,
                error: "No per-ref bundle artifact exists for ref '${ref}' (probed ${artifactUrl ?: 'n/a'}); the manifest's bundle points at branches/main, so installing it would deliver MAIN's libraries, not this ref's. Pass the FULL 40-char commit SHA of a PUSHED commit (not an abbreviation), or push the branch so its bundle artifact is built. Nothing was changed."
            ]
        }
        plannedBundles << entry
    }

    // Coverage guard (mirrors mcp_watchdog_deploy.sh): if the app #includes libraries but
    // the manifest declares NO bundle to deliver them, a deploy would leave the #includes
    // unresolved and the app would not compile. Refuse before any write.
    if (!includeTokens.isEmpty() && plannedBundles.isEmpty()) {
        return [
            success: false, aborted: true, abortReason: "bundle_required_but_undeclared", ref: ref, includes: includeTokens,
            error: "App source #includes ${includeTokens.size()} library(ies) (${includeTokens.join(', ')}) but packageManifest.json at ref '${ref}' declares no bundle to deliver them. A bundle-less deploy would leave the #includes unresolved and the app would not compile. Nothing was changed."
        ]
    }

    // Plan the app leg: resolve every manifest app's Apps Code CLASS id by namespace+name
    // (one /hub2/userAppTypes fetch). The SELF app (mcp / "MCP Rule Server", the running
    // parent) is flagged so it can be deployed LAST -- its recompile drops the in-flight
    // response (#237), so it must be the final act with the rest already in place.
    def manifestApps = (manifest.apps instanceof List) ? manifest.apps : []
    def appTypes
    try {
        def typesText = hubInternalGet("/hub2/userAppTypes")
        appTypes = typesText ? new groovy.json.JsonSlurper().parseText(typesText) : null
    } catch (Exception e) {
        appTypes = null
    }
    if (!(appTypes instanceof List)) {
        return [
            success: false, aborted: true, abortReason: "app_class_unresolved", ref: ref,
            error: "Could not list Apps Code classes via /hub2/userAppTypes to resolve the manifest's apps. Nothing was changed; the app remains updatable via hub_update_app."
        ]
    }
    def plannedApps = []
    for (a in manifestApps) {
        def match = appTypes.find { it?.namespace == a?.namespace && it?.name == a?.name }
        def classId = match?.id?.toString()
        def url = _reanchorToRef(a?.location, base, ref)
        if (!classId || !url) {
            return [
                success: false, aborted: true, abortReason: "app_class_unresolved", ref: ref,
                error: "Could not resolve app '${a?.namespace}:${a?.name}' (class id: ${classId ?: 'unresolved'}, url: ${url ?: 'unusable location'}). Nothing was changed; the app remains updatable via hub_update_app."
            ]
        }
        plannedApps << [
            name: a?.name, namespace: a?.namespace, classId: classId, url: url,
            isSelf: (a?.namespace == "mcp" && a?.name == "MCP Rule Server")
        ]
    }
    // Non-self apps first, the self app last (so the self recompile is the final act).
    def orderedApps = plannedApps.findAll { !it.isSelf } + plannedApps.findAll { it.isSelf }

    if (dryRun) {
        return [
            success: true, dryRun: true, ref: ref, appUrl: appUrl, includes: includeTokens,
            plannedBundles: plannedBundles, plannedApps: orderedApps,
            message: "Dry run: would install ${plannedBundles.size()} bundle(s) then deploy ${orderedApps.size()} app(s) (self app last) to ref ${ref}. No changes made."
        ]
    }

    // BUNDLES FIRST (override). HPM repair installs every manifest bundle via the hub's
    // uploadZipFromUrl endpoint, which overwrites the libraries in place. Abort the WHOLE
    // deploy on the first failure, BEFORE touching any app, so an app is never saved
    // against a missing/stale library. We trust the hub's success signal (no separate
    // library-presence re-read): uploadZipFromUrl unpacks + registers the libraries
    // SYNCHRONOUSLY before returning success -- the same signal HPM relies on -- and the
    // self app #includes them, so the final backstop is its compile: a missing #include
    // makes the self app save fail (app_update_failed/threw, reported), never silently land.
    def bundleResults = []
    for (b in plannedBundles) {
        def r
        try {
            r = toolInstallBundle([importUrl: b.url, confirm: true])
        } catch (Exception e) {
            mcpLog("warn", "developer-mode", "hub_update_package: bundle ${b.url} install threw: ${e.toString()}")
            return [
                success: false, aborted: true, abortReason: "bundle_install_threw", ref: ref, bundles: bundleResults,
                error: "Bundle ${b.name ?: b.url} failed to install: ${e.message ?: e.toString()}. Apps NOT touched -- they remain as-is and updatable via hub_update_app."
            ]
        }
        def ok = (r?.success == true)
        bundleResults << [name: b.name, url: b.url, source: b.source, success: ok, error: (ok ? null : (r?.error ?: r?.message))]
        if (!ok) {
            return [
                success: false, aborted: true, abortReason: "bundle_install_failed", ref: ref, bundles: bundleResults,
                error: "Bundle ${b.name ?: b.url} install reported failure: ${r?.error ?: r?.message ?: 'unknown'}. Apps NOT touched -- they remain as-is and updatable via hub_update_app."
            ]
        }
    }

    // APPS LAST, the self app last of all. Each non-self app must succeed before the self
    // app is touched (fail-closed: a child-app failure never advances to the self deploy,
    // so the running server is left as-is and updatable). Reuse hub_update_app's exact
    // update path (auto-backup + post-save verify + #237 compile-error capture).
    def appResults = []
    for (a in orderedApps) {
        def r
        try {
            r = toolUpdateAppCode([appId: a.classId, importUrl: a.url, confirm: true])
        } catch (Exception e) {
            if (a.isSelf) {
                // A self-update recompiles the running server mid-call, so the response can
                // be lost even though the save landed. Surface it: the bundle(s) and other
                // apps are already in place, and hub_update_app remains available to retry.
                appResults << [name: a.name, namespace: a.namespace, classId: a.classId, isSelf: true, success: false, error: (e.message ?: e.toString())]
                return [
                    success: false, partial: true, abortReason: "app_update_threw", ref: ref,
                    bundles: bundleResults, apps: appResults,
                    error: "Bundle(s) and other apps installed, but the self app update call did not return cleanly (likely the self-update recompile): ${e.message ?: e.toString()}. Verify with hub_get_source; re-run hub_update_app(importUrl) if needed."
                ]
            }
            mcpLog("warn", "developer-mode", "hub_update_package: app ${a.namespace}:${a.name} update threw: ${e.toString()}")
            return [
                success: false, aborted: true, abortReason: "app_update_threw", ref: ref,
                bundles: bundleResults, apps: appResults,
                error: "App ${a.namespace}:${a.name} update threw before the self app was touched: ${e.message ?: e.toString()}. The self (server) app was NOT touched -- it remains as-is and updatable via hub_update_app."
            ]
        }
        def ok = (r?.success == true)
        appResults << [name: a.name, namespace: a.namespace, classId: a.classId, isSelf: a.isSelf, success: ok, app: r]
        if (!ok) {
            if (a.isSelf) {
                // Self app is last; a clean failure return (not a throw) is surfaced as-is. Same
                // partial state as the throw path above (bundles + other apps landed, self did not
                // update), so flag partial=true too -- the difference is only threw vs returned-false.
                mcpLog("warn", "developer-mode", "hub_update_package: ref=${ref} self app update reported failure")
                return [
                    success: false, partial: true, ref: ref, includes: includeTokens, bundles: bundleResults, apps: appResults, app: r,
                    message: "Bundle(s) + other apps installed; the self (server) app update reported failure -- see apps[].app.error. hub_update_app remains available to retry."
                ]
            }
            return [
                success: false, aborted: true, abortReason: "app_update_failed", ref: ref,
                bundles: bundleResults, apps: appResults,
                error: "App ${a.namespace}:${a.name} update reported failure: ${r?.error ?: r?.message ?: 'unknown'}. The self (server) app was NOT touched -- it remains as-is and updatable via hub_update_app."
            ]
        }
    }

    mcpLog("info", "developer-mode", "hub_update_package: ref=${ref} bundles=${bundleResults.size()} apps=${appResults.size()} repaired")
    def result = [
        success: true, ref: ref, includes: includeTokens, bundles: bundleResults, apps: appResults,
        message: "Package repaired to ref ${ref}: ${bundleResults.size()} bundle(s) + ${appResults.size()} app(s) deployed (self app last)."
    ]
    // Whenever a bundle leg FELL BACK from the per-ref artifact, say so. The wording keys
    // on what was actually installed: "manifest-current" = the manifest's bundle-artifacts
    // branches/main zip (exactly what HPM users install; only wrong if this ref's libraries
    // differ from current main); "committed-at-ref" = a legacy ref's in-tree zip (only
    // wrong if the ref changed library code relative to its base).
    if (bundleResults.any { it.source == "manifest-current" }) {
        if (ref == "main") {
            result.bundleFreshnessWarning = "The per-ref artifact probe failed, so the bundle leg installed the manifest's branches/main zip directly -- for ref=main that is the correct, current bundle (the same bytes HPM users install). Noted only because the probe failing may indicate a transient raw.githubusercontent issue."
            mcpLog("warn", "developer-mode", "hub_update_package: ref=main artifact probe failed; installed the manifest's branches/main zip directly (correct bytes for main)")
        } else {
            result.bundleFreshnessWarning = "Ref '${ref}': no bundle-artifacts zip was found for this ref, so the bundle leg installed CURRENT MAIN's bundle (the manifest's branches/main zip). That is only correct if this ref's libraries match current main. If this ref changed library code, push it to this repo (publish-bundle-artifact stores a fresh zip per push, keyed by branch and full SHA) and re-run."
            mcpLog("warn", "developer-mode", "hub_update_package: non-main ref '${ref}' fell back to current main's bundle -- wrong if the ref changed library code")
        }
    } else if (bundleResults.any { it.source == "committed-at-ref" }) {
        result.bundleFreshnessWarning = "Ref '${ref}' (legacy manifest): no bundle-artifacts zip was found for this ref, so the bundle leg installed the zip COMMITTED at the ref. That is only stale if this ref CHANGED library code without rebuilding the zip."
        mcpLog("warn", "developer-mode", "hub_update_package: legacy ref '${ref}' fell back to the committed bundle zip -- stale if the ref changed library code")
    }
    return result
}

def _getAllToolDefinitions_partSelfAdmin() {
    return [
        [
            name: "hub_update_mcp_settings",
            description: "Update one or more of the MCP rule app's own settings (toggles, log levels, tuning, the device-access scope) in place — self-administer the app without the Hubitat UI. Gated on enableDeveloperMode + the Write master + confirm=true + a recent backup; every successful write is logged at WARN for audit. Changing an enable* toggle, useGateways, or publishOutputSchemas reshapes tools/list, and changing selectedDevices changes which devices are visible, so MCP clients may need to reconnect to refresh cached schemas / device visibility.",
            inputSchema: [
                type: "object",
                properties: [
                    settings: [type: "object", description: "Map of setting key → new value (e.g. {\"mcpLogLevel\":\"warn\",\"enableCustomRuleEngine\":true}). Allowlisted keys: mcpLogLevel, debugLogging, maxCapturedStates, loopGuardMax, loopGuardWindowSec, enableRead, enableCustomRuleEngine, useGateways, publishOutputSchemas, enableMandatoryBPS, and selectedDevices — any other key is rejected.[[FLAT_TRIM]] selectedDevices is the MCP device-access scope. Pass {\"mode\":\"replace\"|\"add\"|\"remove\", \"ids\":[<device id strings>], \"allowEmpty\":<bool>} -- or a bare array as shorthand for replace ({\"selectedDevices\":[\"42\",\"108\"]} == {mode:\"replace\", ids:[\"42\",\"108\"]}). 'replace' sets the authorized set to exactly ids; 'add' unions ids with the current set (safest for \"grant one device\" -- no need to re-enumerate the whole list); 'remove' subtracts ids. For replace/add every id is validated against the full hub device list (discover ids via hub_list_devices(scope='all'), each carries an mcpAuthorized flag) -- one unknown id rejects the whole batch and nothing is written; 'remove' does not validate (removing an absent/since-deleted id is a no-op). Refuses to empty the scope unless allowEmpty:true. Deliberately NOT allowlisted: enableWrite (would disable this tool's own write path mid-session), enableDeveloperMode (lockout protection — must stay UI-only to disable), disabled_tools/disabled_gateways (could self-disable this tool).[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to confirm the operation"]
                ],
                required: ["settings", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation succeeded"],
                    updated: [type: "object", description: "Map of applied scalar setting key → coerced new value (excludes selectedDevices, reported under its own key). Present on success; absent when `success: false` (a device-scope runtime fetch failure)"],
                    selectedDevices: [
                        type: "object",
                        description: "Present only when the settings batch changed the device-access scope: the resulting set for selectedDevices",
                        properties: [
                            mode: [type: "string", description: "The mode applied (replace/add/remove)"],
                            authorizedDeviceIds: [type: "array", items: [type: "string"], description: "The resulting authorized device ID set"],
                            authorizedCount: [type: "integer", description: "Size of the resulting authorized set"],
                            added: [type: "array", items: [type: "string"], description: "Device IDs newly added to the scope"],
                            removed: [type: "array", items: [type: "string"], description: "Device IDs removed from the scope"]
                        ]
                    ],
                    message: [type: "string", description: "Human-readable result, including reconnect note. Present on success; absent when `success: false`"],
                    error: [type: "string", description: "Failure detail; present only on a runtime failure (e.g. the device-list fetch for selectedDevices failed)"],
                    note: [type: "string", description: "Actionable guidance; present only on a runtime failure"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_package",
            description: """Developer Mode self-deploy: full HPM-repair of the MCP package at a git ref in one call -- OVERRIDES whatever is installed, the same way Hubitat Package Manager's Repair does, but anchored to packageManifest.json AT `ref` so an UNMERGED PR installs (HPM repair only reads the published manifest).[[FLAT_TRIM]]

Deploys every declared library bundle + app from the manifest at `ref`, saving the running self app LAST (its recompile can drop the response, #237). Does NOT touch app instances, undeclared drivers, or anything outside this package's manifest.

Brick-safe: if ANYTHING before the self app save fails (app/manifest fetch, an unresolved app class, a bundle install, a non-self app), it aborts BEFORE touching the self app -- the running server is left exactly as-is and still updatable via hub_update_app, the always-available escape hatch. Self-modification is gated by this tool's own enableDeveloperMode check (it deploys by Apps Code CLASS id, so hub_update_app's instance-id self-update guard does not fire here).[[/FLAT_TRIM]]

Gated on enableDeveloperMode (the tool is hidden from tools/list when Developer Mode is off) + the Write master + confirm=true + a recent backup. Use dryRun=true to fetch + parse + plan with ZERO writes (no confirm/backup needed) and see exactly which bundles and apps would deploy.""",
            inputSchema: [
                type: "object",
                properties: [
                    ref: [type: "string", description: "Branch, tag, or commit SHA to deploy (e.g. 'main' or a PR head SHA). Source is fetched from the canonical repo raw base at this ref."],
                    dryRun: [type: "boolean", description: "OPTIONAL. When true, fetch + parse + resolve + report the deploy plan with NO writes (skips the confirm/backup gate). Default false."],
                    baseUrl: [type: "string", description: "OPTIONAL raw-source base override (no trailing slash); URLs are built as <baseUrl>/<ref>/<path>. Defaults to the canonical repo. Use for forks / CI branches on a different remote."],
                    confirm: [type: "boolean", description: "REQUIRED for a real deploy (omit for dryRun). Must be true; confirms a recent backup exists and the user approved the self-deploy."]
                ],
                required: ["ref"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "True when the deploy (or dry-run plan) completed; false on abort or app-update failure"],
                    ref: [type: "string", description: "The git ref deployed"],
                    dryRun: [type: "boolean", description: "True when this was a plan-only run (no writes)"],
                    aborted: [type: "boolean", description: "True when the deploy stopped before the self app save; the running server was left untouched"],
                    partial: [type: "boolean", description: "True when bundle(s) + other apps landed but the self app did not update -- its call threw (likely the self-update recompile dropped the response) OR returned a failure"],
                    abortReason: [type: "string", description: "Machine-readable abort cause (app_source_fetch_failed / manifest_fetch_failed / manifest_unparseable / bundle_location_unusable / bundle_required_but_undeclared / app_class_unresolved / bundle_install_failed / bundle_install_threw / app_update_failed / app_update_threw)"],
                    appUrl: [type: "string", description: "Raw URL the self app source was fetched from (for the #include coverage check)"],
                    includes: [type: "array", description: "Parsed #include tokens (namespace.Name) from the self app source", items: [type: "string"]],
                    plannedBundles: [type: "array", description: "dryRun: library bundles that would be installed (name, ref-anchored url)", items: [type: "object"]],
                    plannedApps: [type: "array", description: "dryRun: apps that would be deployed (name, namespace, classId, url, isSelf); self app last", items: [type: "object"]],
                    bundles: [type: "array", description: "Per-bundle install results (name, url, success, error)", items: [type: "object"]],
                    apps: [type: "array", description: "Per-app deploy results (name, namespace, classId, isSelf, success, app); self app last", items: [type: "object"]],
                    app: [type: "object", description: "The hub_update_app result for the self app leg (present only when the self app update RETURNED a failure, not on the threw/dropped-response path)"],
                    message: [type: "string", description: "Human-readable summary"],
                    error: [type: "string", description: "Failure detail; present on abort / failure"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _idempotentWriteToolNames_partSelfAdmin() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // MCP self-admin + logging (selectedDevices (replace/add/remove), when carried in the
        // settings batch, is idempotent too -- re-delivery converges on the same authorized set
        // for all three modes)
        "hub_update_mcp_settings",
        // Developer Mode self-deploy (full repair to a ref converges; retrying
        // after a dropped response is its designed recovery path)
        "hub_update_package"
    ]
}

def _openWorldToolNames_partSelfAdmin() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        "hub_update_package"
    ]
}

def _developerModeOnlyToolNames_partSelfAdmin() {
    // Developer-Mode-only tools in this library (catalog-hidden until the toggle is on) --
    // contributed to the app's getDeveloperModeOnlyToolNames() aggregator.
    //
    // Invariant: exactly ONE self-admin tool stays catalog-VISIBLE (runtime-refused when the
    // toggle is off) to serve as the hub_manage_mcp gateway anchor -- it keeps the gateway
    // present on tools/list and gives the operator the "Developer Mode is off; enable it"
    // discoverability surface. hub_update_mcp_settings is that anchor, so it is deliberately
    // absent from this list. Every OTHER self-admin tool hides when the toggle is off (the
    // anchor already provides discoverability), so they go here.
    return [
        "hub_update_package"
    ]
}

def _toolDisplayMeta_partSelfAdmin() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // MCP self-administration (Developer Mode)
        hub_update_mcp_settings: [title: "Update MCP Settings", summary: "Update the MCP app's own settings + device-access scope (Developer Mode)."],
        hub_update_package: [title: "Deploy MCP Package", summary: "Full repair-install of the MCP package at a git ref (Developer Mode)."]
    ]
}
