library(name: "McpSystemLib", namespace: "mcp", author: "kingpanther13", description: "Hub system tool implementations (hub info/modes/HSM/backup/reboot/shutdown/firmware-update) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

// /hub2/hubData is the data the modern hub UI computes server-side. It carries the hub's OWN
// authoritative health alerts plus the pending-platform-update flag (what the UI "bell" reads) --
// neither is in hub.data or any /hub/advanced/* metric. The availability check is cloud-driven
// (cloud.hubitat.com); the hub caches the result here so it is readable locally. Defensive: returns
// null on any fetch/parse failure so callers degrade gracefully (older firmware may not serve it).
def _getHub2HubData() {
    try {
        def raw = hubInternalGet("/hub2/hubData")
        if (!raw) return null
        def parsed = new groovy.json.JsonSlurper().parseText(raw)
        return (parsed instanceof Map) ? parsed : null
    } catch (Exception e) {
        // warn, not debug: the default log threshold is "error", so a debug line would be invisible --
        // and a /hub2/hubData fetch/parse failure (transient 5xx, auth hiccup, or a firmware that
        // changed the JSON shape) is exactly the cause an operator needs when platformUpdate degrades.
        mcpLog("warn", "server", "_getHub2HubData read/parse failed: ${e.message}")
        return null
    }
}

// platformUpdate block: the pending HUB FIRMWARE update. Distinct from the appUpdate MCP-server-app
// version check (hub_get_info with includeAppUpdate=true). currentVersion is the hub firmware string;
// available + availableVersion come from /hub2/hubData.alerts (platformUpdateAvailable / platformUpdateVersion).
def _platformUpdateFromHub2(hub2) {
    def fw = null
    try { fw = location?.hub?.firmwareVersionString?.toString() } catch (Exception e) { }
    def hubVer = (hub2 instanceof Map) ? hub2.version?.toString() : null
    // available:null is the schema's documented "unreadable" signal -- honor it for BOTH a missing
    // /hub2/hubData AND a present-but-unrecognized shape (alerts not a Map, or a non-Boolean
    // platformUpdateAvailable), so a malformed/changed payload can never masquerade as a confident
    // "no update available".
    def alerts = (hub2 instanceof Map && hub2.alerts instanceof Map) ? hub2.alerts : null
    def pa = alerts?.platformUpdateAvailable
    if (alerts == null || (pa != null && !(pa instanceof Boolean))) {
        return [available: null, currentVersion: fw ?: hubVer,
                note: "Pending-firmware status unreadable (/hub2/hubData missing, or its alerts block has an unrecognized shape)."]
    }
    boolean avail = (pa == true)
    def out = [available: avail, currentVersion: fw ?: hubVer]
    if (avail) out.availableVersion = alerts.platformUpdateVersion?.toString()
    return out
}

// healthAlerts block: the hub's own active health determinations from /hub2/hubData -- complementary
// to, NOT duplicating, the locally-derived memory/temp/DB warnings. `active` lists the currently-
// firing alert flags; `details` is the full alert map (every flag + the hub's message strings). The
// platform-update fields are surfaced separately (platformUpdate), so they are dropped here.
def _healthAlertsFromHub2(hub2) {
    if (!(hub2 instanceof Map)) return null
    def alerts = (hub2.alerts instanceof Map) ? ([:] + hub2.alerts) : [:]
    alerts.remove("platformUpdateAvailable"); alerts.remove("platformUpdateVersion")
    def active = alerts.findAll { k, v -> v == true }.collect { k, v -> k.toString() }.sort()
    return [safeMode: hub2.safeMode == true, active: active, details: alerts]
}

def toolGetHubInfo(args = null) {
    def hub = location.hub
    def info = [
        temperatureScale: location.temperatureScale
    ]

    // Hub hardware and radio info (always available)
    try { info.model = hub?.hardwareID } catch (Exception e) { info.model = "unavailable" }
    try { info.firmwareVersion = hub?.firmwareVersionString } catch (Exception e) { info.firmwareVersion = "unavailable" }
    try { info.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { info.zigbeeChannel = "unavailable" }
    try { info.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { info.zwaveVersion = "unavailable" }
    try { info.zigbeeId = hub?.zigbeeId } catch (Exception e) { info.zigbeeId = "unavailable" }
    try { info.type = hub?.type } catch (Exception e) { info.type = "unavailable" }

    // Uptime (always available)
    try {
        def uptimeSec = hub?.uptime
        if (uptimeSec && uptimeSec instanceof Number) {
            def days = (uptimeSec / 86400).toInteger()
            def hours = ((uptimeSec % 86400) / 3600).toInteger()
            def mins = ((uptimeSec % 3600) / 60).toInteger()
            info.uptimeSeconds = uptimeSec
            info.uptimeFormatted = "${days}d ${hours}h ${mins}m"
        }
    } catch (Exception e) { info.uptimeSeconds = "unavailable" }

    // Health data (always available — uses internal API)
    try {
        def freeMemory = hubInternalGet("/hub/advanced/freeOSMemory")
        if (freeMemory) {
            info.freeMemoryKB = freeMemory.trim()
            try {
                def memKB = freeMemory.trim() as Integer
                if (memKB < 50000) {
                    info.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
                } else if (memKB < 100000) {
                    info.memoryNote = "Memory is moderate: ${memKB}KB free."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.freeMemoryKB = "unavailable" }

    try {
        def tempC = hubInternalGet("/hub/advanced/internalTempCelsius")
        if (tempC) {
            info.internalTempCelsius = tempC.trim()
            try {
                def temp = tempC.trim() as Double
                if (temp > 70) {
                    info.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
                } else if (temp > 60) {
                    info.temperatureNote = "Temperature is warm: ${temp}°C."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.internalTempCelsius = "unavailable" }

    try {
        def dbSize = hubInternalGet("/hub/advanced/databaseSize")
        if (dbSize) {
            info.databaseSizeKB = dbSize.trim()
            try {
                def dbKB = dbSize.trim() as Integer
                if (dbKB > 500000) {
                    info.databaseWarning = "LARGE DATABASE: ${(dbKB / 1024).toInteger()}MB. Consider cleaning up old data."
                }
            } catch (NumberFormatException nfe) { /* non-numeric */ }
        }
    } catch (Exception e) { info.databaseSizeKB = "unavailable" }

    // MCP-specific stats (always available)
    info.mcpServerVersion = currentVersion()
    info.mcpDeviceCount = settings.selectedDevices?.size() ?: 0
    info.mcpRuleCount = getChildApps()?.size() ?: 0
    info.mcpLogEntries = state.debugLogs?.entries?.size() ?: 0
    info.mcpCapturedStates = atomicState.capturedDeviceStates?.size() ?: 0
    // Last hub_create_backup epoch (millis): lets a client decide whether a fresh backup is
    // actually needed (the destructive-confirm gate's 24h window reads this same state key) --
    // e2e uses it to skip per-run backups, a hub-heavy op the platform's load limiter punishes.
    info.lastBackupEpoch = state.lastBackupTimestamp ?: null

    // Settings visibility (always available)
    info.hubSecurityConfigured = settings.hubSecurityEnabled ?: false
    info.readEnabled = settings.enableRead != false
    info.writeEnabled = settings.enableWrite != false
    info.customRuleEngineEnabled = settings.enableCustomRuleEngine == true
    info.developerModeEnabled = settings.enableDeveloperMode ?: false
    // issue #209 #include smoke test: this method is supplied by the McpSmokeTestLib library via
    // `#include mcp.McpSmokeTestLib` at the top of the file. Its presence here -- returning
    // "smoke-ok-v1" rather than throwing MissingMethodException -- proves the include resolved and
    // the library loaded. Throwaway canary; removed once the modularization split is validated.
    info.smokeTestMarker = mcpSmokeTestMarker()

    // Last self-deploy outcome (issue #237): hub_update_app on the MCP server's own app can't return
    // its result on the call (success reloads the app; a big-file compile failure 504s), so it records
    // the hub's verbatim outcome here for a follow-up read to recover -- e.g. CI surfacing the real
    // compile error after a failed self-deploy. Null until the first self-update.
    //
    // STALENESS (important for any consumer, e.g. the e2e recover step): this record lives in
    // atomicState and PERSISTS across app code reloads/restores -- it is NOT cleared on an app update.
    // So a read can return a record left by an EARLIER deploy (even a prior session) and be mistaken
    // for the latest outcome. Two freshness aids: `ageMs` (now - at) is added here so age is visible at
    // a glance, and a consumer comparing across its own deploy should baseline `at` first and require it
    // to advance (see .github/scripts/test_self_deploy_recovery.sh recover_self_deploy_error).
    if (atomicState.lastSelfDeploy != null) {
        def lsd = [:] + atomicState.lastSelfDeploy
        if (lsd.at instanceof Number) lsd.ageMs = now() - (lsd.at as long)
        info.lastSelfDeploy = lsd
    }

    // PII/location data requires the Read master (default ON)
    if (settings.enableRead != false) {
        info.name = hub?.name
        info.localIP = hub?.localIP
        info.timeZone = location.timeZone?.ID
        info.latitude = location.latitude
        info.longitude = location.longitude
        info.zipCode = location.zipCode
        try { info.hubData = hub?.data } catch (Exception e) { info.hubData = null }
    } else {
        info.readDisabledNote = "The Read master is OFF. The following personally identifiable data is excluded: hub name, local IP, time zone, latitude, longitude, zip code, and hub data. Enable 'Read Tools' in MCP Rule Server app settings to include this data."
    }

    if (args?.identifyHub == true) {
        try {
            hubInternalGet("/hub/advanced/blinkLED")
            info.identifyHubTriggered = true
        } catch (Exception e) {
            def msg = e.message ?: e.toString()
            info.identifyHubTriggered = false
            info.identifyHubError = msg
            mcpLog("warn", "server", "identifyHub blinkLED request failed [${e.class.simpleName}]: ${msg}")
        }
    }

    // Pending hub firmware update + hub health alerts from /hub2/hubData (one fetch; not PII-gated --
    // these are hub-health, not location data). platformUpdate + safeMode always; the full alerts
    // block only when asked (it is the diagnostics surface, lives in full on hub_get_metrics too).
    def hub2 = _getHub2HubData()
    info.platformUpdate = _platformUpdateFromHub2(hub2)
    if (hub2 instanceof Map) info.safeMode = (hub2.safeMode == true)
    if (args?.includeHealthAlerts == true) {
        def ha = _healthAlertsFromHub2(hub2)
        if (ha != null) info.healthAlerts = ha
    }

    // Opt-in MCP Rule Server APP version check on GitHub (distinct from platformUpdate, the hub's
    // own firmware). Off by default: it is asynchronous (the first call may return latestVersion
    // 'unknown (check in progress)' -- call again in a few seconds) AND reaches the open internet,
    // so it must not run on a basic info read.
    if (args?.includeAppUpdate == true) {
        try {
            if (state.updateCheck) state.updateCheck.checkedAt = null
            doUpdateCheck()
            def uc = state.updateCheck ?: [:]
            info.appUpdate = [
                installedVersion: currentVersion(),
                latestVersion: uc.latestVersion ?: "unknown (check in progress)",
                updateAvailable: uc.updateAvailable ?: false,
                lastChecked: uc.checkedAt ? formatTimestamp(uc.checkedAt) : "checking now"
            ]
        } catch (Exception e) {
            info.appUpdate = [error: "App-version check failed: ${e.message}", installedVersion: currentVersion()]
        }
    }

    return info
}

// hub_set_system_settings: write the hub-GLOBAL location settings. All params optional; pass only
// what changes. Wire format verified against resources/hub2-source/vue-hub2.min.js (do NOT re-derive):
//   hubName                                       -> GET /hub/updateName?name=<urlenc>
//   latitude/longitude/timeZone/temperatureScale/zipCode -> ONE GET /hub/updateLatLongTimezone
//        (granular wholesale endpoint: read-merge the CURRENT location.* values, override only the
//         provided args; zipCode maps to the postalCode query param)
// A timeZone change REBOOTS the hub, so that leg alone is confirm-gated (requireDestructiveConfirm);
// the other fields are Write-master-only. Arg validation throws (-> -32602); hub-call failures return
// the structured runtime-error envelope ([success:false, error, note]) -- never thrown.
def _urlEnc(value) {
    return java.net.URLEncoder.encode(value?.toString() ?: "", "UTF-8")
}

def toolSetSystemSettings(args) {
    args = args ?: [:]
    def settable = ["hubName", "timeZone", "latitude", "longitude", "zipCode", "temperatureScale"]
    if (!settable.any { args.containsKey(it) }) {
        throw new IllegalArgumentException("Provide at least one field to change: ${settable.join(', ')}. All are optional; pass only what changes.")
    }

    def tempScale = null
    if (args.containsKey("temperatureScale")) {
        tempScale = args.temperatureScale?.toString()
        if (!(tempScale in ["F", "C"])) {
            throw new IllegalArgumentException("temperatureScale must be 'F' or 'C' (uppercase), got: ${args.temperatureScale}")
        }
    }

    // latLongTimezone is the wholesale granular endpoint -- it is called iff any of its fields change.
    def llKeys = ["latitude", "longitude", "timeZone", "temperatureScale", "zipCode"]
    boolean wantsLatLong = llKeys.any { args.containsKey(it) }

    // A timeZone change reboots the hub -- gate ONLY that leg.
    if (args.containsKey("timeZone")) {
        requireDestructiveConfirm(args.confirm)
    }

    def applied = []
    try {
        if (args.containsKey("hubName")) {
            hubInternalGet("/hub/updateName?name=${_urlEnc(args.hubName)}")
            applied << "hubName"
        }

        if (wantsLatLong) {
            // Read-merge: start from the SDK's current values, override only what was passed.
            def curLat = location.latitude
            def curLon = location.longitude
            def curTz = location.timeZone?.ID
            def curScale = location.temperatureScale
            def curZip = location.zipCode

            def lat = args.containsKey("latitude") ? args.latitude : curLat
            def lon = args.containsKey("longitude") ? args.longitude : curLon
            def tz = args.containsKey("timeZone") ? args.timeZone : curTz
            def scale = args.containsKey("temperatureScale") ? tempScale : curScale
            def zip = args.containsKey("zipCode") ? args.zipCode : curZip

            def query = "latitude=${_urlEnc(lat)}&longitude=${_urlEnc(lon)}&timeZone=${_urlEnc(tz)}" +
                        "&temperatureScale=${_urlEnc(scale)}&postalCode=${_urlEnc(zip)}"
            hubInternalGet("/hub/updateLatLongTimezone?${query}")
            llKeys.each { if (args.containsKey(it)) applied << it }
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_set_system_settings hub call failed", e)
        return [
            success: false,
            error: "Failed to apply hub settings: ${e.message}",
            applied: applied,
            note: "Some fields may have been applied before the failure (see 'applied'). Check Hub Security credentials; read back current values with hub_get_info."
        ]
    }

    return [
        success: true,
        applied: applied,
        note: "Read back the current values with hub_get_info. A timeZone change reboots the hub (1-3 min downtime)."
    ]
}

def toolGetModes() {
    def currentMode = location.mode
    def modes = location.modes?.collect { [id: it.id.toString(), name: it.name] }
    def modeManager = null
    // Enrich with per-mode icon + Mode Manager state from the HTTP surface (the SDK exposes
    // neither). Best-effort: fall back to the SDK list if /modes/json can't be read.
    try {
        def raw = hubInternalGet("/modes/json")
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        if (parsed instanceof Map) {
            if (parsed.modes instanceof List) {
                modes = parsed.modes.collect { [id: it?.id?.toString(), name: it?.name, icon: it?.icon] }
            }
            // Only surface modeManager when the payload actually carries it -- a Map that lacks the
            // manager keys (an error envelope, or a firmware shape change) must NOT yield an all-null
            // block that masks the read as "manager unset".
            if (parsed.containsKey("selectedModeManager") || parsed.containsKey("modeManagerAppId")) {
                modeManager = [selected: parsed.selectedModeManager, appId: parsed.modeManagerAppId?.toString(),
                               easyModeManagerAppId: parsed.easyModeManagerAppId?.toString()]
                // The Integrated/built-in Mode Manager's per-mode conditions live at a separate endpoint
                // that's independent of which manager is selected (returns {} when none are set). Read it
                // best-effort so callers see the current automation conditions.
                try {
                    def craw = hubInternalGet("/modes/easyModeManager/json")
                    if (craw) modeManager.easyConditions = new groovy.json.JsonSlurper().parseText(craw)
                } catch (Exception ce) {
                    mcpLog("debug", "modes", "Integrated Mode Manager conditions unreadable (omitted from modeManager): ${ce.message}")
                }
            }
        }
    } catch (Exception e) {
        mcpLog("warn", "modes", "Could not read /modes/json (using SDK mode list): ${e.message}")
    }
    def result = [currentMode: currentMode, modes: modes]
    if (modeManager != null) result.modeManager = modeManager
    return result
}

// Resolve a mode id or name to its numeric SDK id; null if no match.
private _resolveModeId(idOrName) {
    def s = idOrName?.toString()?.trim()
    if (!s) return null
    def m = location.modes?.find { it?.id?.toString() == s || it?.name?.equalsIgnoreCase(s) }
    return m ? m.id : null
}

def toolManageMode(args) {
    def action = args?.action?.toString()?.trim()?.toLowerCase()
    if (!action) throw new IllegalArgumentException("action is required: one of create, rename, delete, activate")
    switch (action) {
        case "create":
            def name = args?.name?.toString()?.trim()
            if (!name) throw new IllegalArgumentException("name is required to create a mode")
            def body = args?.icon ? [name: name, icon: args.icon.toString()] : [name: name]
            return _modeWriteResult("create", _modePost("/modes/jsonCreate", body))
        case "rename":
            def name = args?.name?.toString()?.trim()
            def modeId = _resolveModeId(args?.mode)
            if (!name) throw new IllegalArgumentException("name (the new mode name) is required to rename a mode")
            if (modeId == null) throw new IllegalArgumentException("mode not found: '${args?.mode}'. Pass a mode id or current name (from hub_list_modes).")
            def body = args?.icon ? [id: modeId, name: name, icon: args.icon.toString()] : [id: modeId, name: name]
            return _modeWriteResult("rename", _modePost("/modes/jsonUpdate", body))
        case "delete":
            def modeId = _resolveModeId(args?.mode)
            if (modeId == null) throw new IllegalArgumentException("mode not found: '${args?.mode}'. Pass a mode id or name (from hub_list_modes).")
            requireDestructiveConfirm(args.confirm)
            return _modeDelete(modeId)
        case "activate":
            return _modeActivate(args?.mode?.toString()?.trim())
        default:
            throw new IllegalArgumentException("Unknown action '${action}'. Valid: create, rename, delete, activate.")
    }
}

private _modePost(String path, Map body) {
    return hubInternalPostJson(path, groovy.json.JsonOutput.toJson(body))
}

private _modeWriteResult(String op, parsed) {
    if (parsed instanceof Map && parsed.success == true) {
        // Don't re-read the full mode list here -- that's an extra /modes/json round-trip per write
        // and the hub's per-app load limiter punishes back-to-back calls. The caller reads via
        // hub_list_modes when it needs the updated list.
        return [success: true, action: op, note: "Read the updated mode list with hub_list_modes."]
    }
    def err = (parsed instanceof Map) ? (parsed.message ?: parsed.error) : null
    return [success: false, action: op, error: err ?: "/modes/${op == 'create' ? 'jsonCreate' : 'jsonUpdate'} did not report success",
            note: "Verify the mode name/id and Hub Security credentials; read current modes with hub_list_modes. Nothing was changed."]
}

private _modeDelete(modeId) {
    try {
        def raw = hubInternalGet("/modes/jsonDelete/${java.net.URLEncoder.encode(modeId.toString(), 'UTF-8')}")
        def parsed = null
        try { parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null } catch (Exception ignore) { }
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, action: "delete", deletedModeId: modeId.toString(),
                    note: "Read the updated mode list with hub_list_modes."]
        }
        def err = (parsed instanceof Map) ? (parsed.message ?: parsed.error) : null
        return [success: false, action: "delete", error: err ?: "/modes/jsonDelete did not report success", response: raw?.take(300),
                note: "A mode that is current or referenced by Mode Manager/rules may be undeletable. Nothing was deleted."]
    } catch (Exception e) {
        mcpLogError("modes", "delete mode ${modeId} failed", e)
        return [success: false, action: "delete", error: "Mode delete failed: ${e.message}",
                note: "Verify the mode id and Hub Security credentials."]
    }
}

private _modeActivate(String modeName) {
    if (!modeName) throw new IllegalArgumentException("mode (the mode name to activate) is required")
    def mode = location.modes?.find { it.name.equalsIgnoreCase(modeName) }
    if (!mode) {
        def available = location.modes?.collect { it.name }
        throw new IllegalArgumentException("Mode '${modeName}' not found. Available: ${available}")
    }
    def previousMode = location.mode
    location.setMode(mode.name)
    return [success: true, action: "activate", previousMode: previousMode, newMode: mode.name,
            note: "Verify the active mode with hub_list_modes (setMode is a fire-and-return SDK call)."]
}

def toolSetModeManager(args) {
    def manager = args?.manager?.toString()?.trim()?.toLowerCase()
    def conditions = args?.conditions
    if (!manager && conditions == null) {
        throw new IllegalArgumentException("Pass 'manager' (builtIn|legacy|app) and/or 'conditions' (Integrated Mode Manager per-mode conditions, from hub_list_modes.modeManager.easyConditions).")
    }
    def result = [success: true]
    if (manager) {
        def wireByKey = [builtin: "builtIn", legacy: "legacy", app: "app"]
        def wire = wireByKey[manager]
        if (!wire) throw new IllegalArgumentException("manager must be one of builtIn, legacy, app")
        try {
            def raw = hubInternalGet("/modes/setModeManager/${wire}")
            def parsed = null
            try { parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null } catch (Exception ignore) { }
            result.manager = wire
            if (!(parsed instanceof Map && parsed.success == true)) {
                result.success = false
                result.error = (parsed instanceof Map ? (parsed.message ?: parsed.error) : null) ?: "/modes/setModeManager did not report success"
            }
        } catch (Exception e) {
            mcpLogError("modes", "setModeManager ${wire} failed", e)
            return [success: false, error: "Set mode manager failed: ${e.message}", note: "Verify Hub Security credentials."]
        }
    }
    // If a manager switch was requested and failed, don't apply conditions into whatever manager is
    // still active -- that would be a confusing partial apply.
    if (manager && result.success != true) {
        result.note = "Manager selection failed; conditions were not applied. Read state with hub_list_modes."
        return result
    }
    if (conditions != null) {
        try {
            def cres = hubInternalPostJson("/modes/easyModeManager/json", groovy.json.JsonOutput.toJson(conditions))
            if (cres instanceof Map && cres.success == true) {
                result.conditionsUpdated = true
            } else {
                result.success = false
                result.conditionsError = (cres instanceof Map ? (cres.message ?: cres.error) : null) ?: "Integrated Mode Manager conditions update did not report success"
            }
        } catch (Exception e) {
            mcpLogError("modes", "easyModeManager update failed", e)
            result.success = false
            result.conditionsError = "Integrated Mode Manager conditions update failed: ${e.message}"
        }
    }
    result.note = "Read current manager state + conditions with hub_list_modes (modeManager block)."
    return result
}

def toolGetHsmStatus() {
    def hsmStatus = location.hsmStatus
    def hsmAlerts = location.hsmAlert

    return [
        status: hsmStatus,
        // Interpret a null/empty status so callers don't see a bare null (HSM may
        // be disabled, or hasn't reported a status yet). Known values: disarmed,
        // armedAway, armedHome, armedNight.
        statusText: hsmStatus ?: "unknown — HSM may be disabled or has not reported a status yet",
        alert: hsmAlerts,
        // Renamed from the overloaded `modes`: these are the HSM ARM commands for
        // hub_set_hsm, NOT hub Day/Night/Away location modes (a separate concept).
        armCommands: ["disarm", "armAway", "armHome", "armNight"]
    ]
}

def toolSetHsm(mode) {
    def validModes = ["armAway", "armHome", "armNight", "disarm"]
    if (!validModes.contains(mode)) {
        throw new IllegalArgumentException("Invalid HSM mode: ${mode}. Valid modes: ${validModes}")
    }

    // Capture current status BEFORE sending the change event
    def previousStatus = location.hsmStatus
    sendLocationEvent(name: "hsmSetArm", value: mode)

    return [
        success: true,
        previousStatus: previousStatus,
        newMode: mode
    ]
}

def toolCreateHubBackup(args) {
    // The Write master is enforced centrally in executeTool; this tool creates the
    // backup itself, so it cannot require a pre-existing recent one (no requireDestructiveConfirm).
    if (!args.confirm) {
        throw new IllegalArgumentException("You must set confirm=true to create a backup.")
    }

    if (args.mock == true) {
        // Test-hub lever (maintainer-directed): stamp ONLY the destructive-confirm gate record
        // without performing any real backup -- the hub backup is a heavy operation the platform's
        // load limiter punishes, and e2e needs the GATED tools tested, not the backup itself.
        // Developer-mode-gated so a production client can't silently satisfy the gate with a lie.
        if (settings.enableDeveloperMode != true) {
            throw new IllegalArgumentException("hub_create_backup mock=true requires Developer Mode (it satisfies the destructive-confirm gate WITHOUT a real backup -- test environments only).")
        }
        def backupTime = now()
        state.lastBackupTimestamp = backupTime
        mcpLog("warn", "hub-admin", "MOCK backup recorded (no real backup performed; developer mode)")
        return [
            success: true,
            mocked: true,
            message: "MOCK backup recorded: the destructive-confirm gate is satisfied but NO real backup was created.",
            backupTimestamp: formatTimestamp(backupTime),
            backupTimestampEpoch: backupTime,
            note: "Test environments only. Create a real backup before relying on restore."
        ]
    }

    mcpLog("info", "hub-admin", "Creating hub backup (async trigger; the backup file is never downloaded through this app)...")

    try {
        // GET /hub/backupDB?fileName=latest makes the hub CREATE a fresh backup and stream the
        // .lzf back. The old implementation read that multi-MB binary through this app's
        // execution just to confirm success -- a one-off load spike the platform's per-app
        // limiter punishes with a STICKY device-dispatch block ~13 minutes later (verified A/B
        // on fw 2.5.0.157: every slurping backup wedged the hub; async-triggered backups never
        // did). So: fire the request asynchronously (the hub still creates the backup; the
        // async client's truncated body is discarded) and confirm completion via the hub's own
        // /hub/backup/statusJson instead of the binary response.
        def asyncParams = [uri: hubBaseUri(), path: "/hub/backupDB", query: [fileName: "latest"], timeout: 300]
        def cookie = getHubSecurityCookie()
        if (cookie) asyncParams.headers = [Cookie: cookie]
        asynchttpGet("backupResponseSink", asyncParams)

        // Confirm via statusJson: wait for an in-progress backup to finish (small JSON reads).
        // A small-DB backup can finish before the first poll, so backupInProgress=false is
        // treated as completion rather than requiring an observed true->false transition.
        def confirmed = false
        for (int i = 0; i < 12; i++) {
            pauseExecution(3000)
            def statusText = null
            try { statusText = hubInternalGet("/hub/backup/statusJson", null, 15) } catch (Exception ignored) { }
            def parsed = null
            try { parsed = statusText ? new groovy.json.JsonSlurper().parseText(statusText) : null } catch (Exception ignored) { }
            if (parsed instanceof Map && parsed.backupInProgress == false && parsed.cloudBackupInProgress != true) {
                confirmed = true
                break
            }
            if (parsed == null && i >= 2) break   // status endpoint unreadable; stop burning time
        }

        def backupTime = now()
        state.lastBackupTimestamp = backupTime

        mcpLog("info", "hub-admin", "Hub backup ${confirmed ? 'completed' : 'triggered (completion unconfirmed)'} at ${formatTimestamp(backupTime)}")
        return [
            success: true,
            confirmed: confirmed,
            message: confirmed ? "Hub backup created successfully"
                               : "Hub backup triggered; completion could not be confirmed via /hub/backup/statusJson (best-effort).",
            backupTimestamp: formatTimestamp(backupTime),
            backupTimestampEpoch: backupTime,
            note: "This backup is stored on the hub. You can download it from the Hubitat web UI at Settings → Backup and Restore."
        ]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub backup FAILED", e)
        return [
            success: false,
            error: "Backup failed: ${e.message}",
            note: "The backup could not be created. Do NOT proceed with any Write master operations. " +
                  "Check Hub Security credentials if Hub Security is enabled, or try creating a backup manually from the Hubitat web UI."
        ]
    }
}

// asynchttpGet completion sink for the backup trigger: the .lzf body is deliberately never
// read into this app (see toolCreateHubBackup -- the whole point of the async trigger).
def backupResponseSink(response, data) {
    try { mcpLog("debug", "hub-admin", "backup async response status=${response?.status}") } catch (Exception ignored) { }
}

def toolRebootHub(args) {
    requireDestructiveConfirm(args.confirm)

    mcpLog("warn", "hub-admin", "Hub reboot initiated by MCP")

    try {
        def responseText = hubInternalPost("/hub/reboot")
        return [
            success: true,
            message: "Hub reboot initiated. The hub will be unreachable for 1-3 minutes.",
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "All automations and device communications will stop during reboot. The hub will restart automatically.",
            response: responseText?.take(500)
        ]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub reboot failed", e)
        return [
            success: false,
            error: "Reboot failed: ${e.message}",
            note: "The reboot command could not be sent. Check Hub Security credentials or try rebooting manually from the Hubitat web UI at Settings → Reboot Hub."
        ]
    }
}

def toolShutdownHub(args) {
    requireDestructiveConfirm(args.confirm)

    mcpLog("warn", "hub-admin", "Hub SHUTDOWN initiated by MCP -- hub will NOT restart automatically")

    try {
        def responseText = hubInternalPost("/hub/shutdown")
        return [
            success: true,
            message: "Hub shutdown initiated. The hub will power off and will NOT restart automatically.",
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "The hub is powering down. To restart, you must physically unplug and replug the hub power cable. ALL smart home functionality will stop until the hub is manually restarted.",
            response: responseText?.take(500)
        ]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub shutdown failed", e)
        return [
            success: false,
            error: "Shutdown failed: ${e.message}",
            note: "The shutdown command could not be sent. Check Hub Security credentials or try shutting down manually from the Hubitat web UI."
        ]
    }
}

def isNewerVersion(String remote, String local) {
    // Null guard: callers may pass null when the remote manifest fetch
    // returns no version field. Treat as "not newer" so update prompts
    // are suppressed rather than NPE-crashing the version-check path.
    if (remote == null || local == null) return false
    // Strict semver only. Non-numeric or suffixed versions (e.g., "0.10.0-rc1",
    // "v0.10.0", whitespace) would otherwise throw NumberFormatException inside
    // the tokenize/collect below -- caught but silently returning false, which
    // means users stop getting update prompts without knowing why.
    def semverPattern = ~/^\d+\.\d+\.\d+$/
    if (!(remote ==~ semverPattern)) {
        mcpLog("warn", "server", "Remote version not strict semver: '${remote}' -- skipping comparison")
        return false
    }
    if (!(local ==~ semverPattern)) {
        mcpLog("warn", "server", "Local version not strict semver: '${local}' -- skipping comparison")
        return false
    }
    try {
        def remoteParts = remote.tokenize('.').collect { it as int }
        def localParts = local.tokenize('.').collect { it as int }
        def maxLen = Math.max(remoteParts.size(), localParts.size())
        for (int i = 0; i < maxLen; i++) {
            def r = i < remoteParts.size() ? remoteParts[i] : 0
            def l = i < localParts.size() ? localParts[i] : 0
            if (r > l) return true
            if (r < l) return false
        }
        return false
    } catch (Exception e) {
        mcpLog("warn", "server", "Version comparison failed: ${e.message}")
        return false
    }
}

def checkForUpdate() {
    try {
        // Skip if checked within last 24 hours (unless forced)
        if (state.updateCheck?.checkedAt) {
            def msSinceCheck = now() - state.updateCheck.checkedAt
            if (msSinceCheck < 24 * 60 * 60 * 1000) {
                def hoursSinceCheck = (int)(msSinceCheck / (1000 * 60 * 60))
                logDebug("Version check skipped - last checked ${hoursSinceCheck} hours ago")
                return
            }
        }
        doUpdateCheck()
    } catch (Exception e) {
        mcpLog("warn", "server", "Version update check failed: ${e.message}")
    }
}

def doUpdateCheck() {
    try {
        def params = [
            uri: "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/packageManifest.json",
            contentType: "application/json",
            timeout: 30
        ]
        asynchttpGet("handleUpdateCheckResponse", params)
    } catch (Exception e) {
        mcpLog("warn", "server", "Failed to initiate version check: ${e.message}")
    }
}

def handleUpdateCheckResponse(resp, data) {
    try {
        if (resp.status != 200) {
            mcpLog("warn", "server", "Version check HTTP error: ${resp.status}")
            // Merge checkedAt + lastError onto the existing record (do NOT replace it)
            // so the first-install gate in initialize() flips and the 24h guard engages
            // even when the check never succeeds (e.g. a hub that can't reach GitHub),
            // WITHOUT clobbering a previously-known latestVersion/updateAvailable -- a
            // transient failure must not silently drop an already-surfaced update banner.
            state.updateCheck = (state.updateCheck ?: [:]) + [checkedAt: now(), lastError: "http ${resp.status}"]
            return
        }
        def json = new groovy.json.JsonSlurper().parseText(resp.data)
        def latestVersion = json.version
        if (!latestVersion) {
            mcpLog("warn", "server", "Version check: no version field in response")
            state.updateCheck = (state.updateCheck ?: [:]) + [checkedAt: now(), lastError: "no version field"]
            return
        }
        def installed = currentVersion()
        def updateAvailable = isNewerVersion(latestVersion, installed)
        state.updateCheck = [
            latestVersion: latestVersion,
            checkedAt: now(),
            updateAvailable: updateAvailable
        ]
        if (updateAvailable) {
            log.info "MCP Rule Server update available: v${latestVersion} (installed: v${installed})"
        } else {
            logDebug("MCP Rule Server is up to date (v${installed})")
        }
    } catch (Exception e) {
        mcpLog("warn", "server", "Version check response parsing failed: ${e.message}")
        state.updateCheck = (state.updateCheck ?: [:]) + [checkedAt: now(), lastError: e.message]
    }
}

// hub_update_firmware: install the hub's pending platform/firmware update via the cloud-update
// endpoints (/hub/cloud/updatePlatform downloads + installs; the hub reboots itself when the install
// completes). statusOnly polls /hub/cloud/checkUpdateStatus without applying. The pending-update read
// lives in hub_get_info (platformUpdate); this tool also runs the live /hub/cloud/checkForUpdate.
def toolUpdateFirmware(args) {
    if (args?.statusOnly == true) {
        try {
            def st = hubInternalGet("/hub/cloud/checkUpdateStatus", null, 30)
            def parsed = st?.take(200)
            try { if (st) parsed = new groovy.json.JsonSlurper().parseText(st) } catch (Exception ignore) { }
            return [
                success: true,
                statusOnly: true,
                status: parsed,
                note: "Install progress (status is IDLE when none is running). The endpoint goes dark during the reboot; confirm the new firmwareVersion via hub_get_info afterwards."
            ]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Firmware update status poll failed", e)
            return [success: false, error: "Update status poll failed: ${e.message}", note: "Check Hub Security credentials."]
        }
    }

    requireDestructiveConfirm(args.confirm)
    mcpLog("warn", "hub-admin", "Hub firmware update initiated by MCP (install + self-reboot)")
    try {
        def check = _parseFirmwareCheck(hubInternalGet("/hub/cloud/checkForUpdate", null, 60))
        def resp = hubInternalGet("/hub/cloud/updatePlatform", null, 60)
        return [
            success: true,
            message: "Firmware update initiated. The hub downloads and installs the pending update, then reboots itself (5-10 minutes total).",
            available: check,
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            warning: "All automations and device communications stop during the install and reboot. Poll progress with hub_update_firmware(statusOnly=true); confirm the new version via hub_get_info afterwards.",
            response: resp?.take(500)
        ]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Hub firmware update failed", e)
        return [
            success: false,
            error: "Firmware update failed: ${e.message}",
            note: "The update command could not be sent. Check Hub Security credentials, or apply it from the hub UI (Settings -> Check for Updates)."
        ]
    }
}

// Parse /hub/cloud/checkForUpdate. Returns the hub's own fields verbatim so the caller sees exactly
// what the cloud check reports -- {version, upgrade, status, releaseNotesUrl, beta, hubCount,
// accountEmails}. accountEmails is the hub owner's own account email (returned to that same owner; not
// redacted). Falls back to the raw text if the response is not a JSON object.
private Map _parseFirmwareCheck(rawText) {
    try {
        def p = rawText ? new groovy.json.JsonSlurper().parseText(rawText) : null
        return (p instanceof Map) ? p : [raw: rawText?.take(500)]
    } catch (Exception e) {
        return [parseError: e.message, raw: rawText?.take(500)]
    }
}

def _getAllToolDefinitions_partSystem() {
    return [
        // System Tools
        [
            name: "hub_get_info",
            description: "Get comprehensive hub diagnostics in one call: model, firmware, uptime, memory, temperature, DB size, MCP stats, and security/toggle settings.[[FLAT_TRIM]] Also surfaces the pending hub firmware/platform update (platformUpdate) + Safe Mode (safeMode). Pass includeHealthAlerts=true for the hub's full health-alerts block from /hub2/hubData (radio offline, backup failures, low memory, DB bloat, weak mesh), or includeAppUpdate=true to also check GitHub for a newer MCP server APP version (returned under appUpdate). Use this for health checks, version lookups, or when triaging hub performance. Location/PII fields (name, local IP, timezone, coordinates, zip code) are returned only when Read master is enabled; otherwise they are omitted.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    identifyHub: [type: "boolean", description: "Blink hub LED to identify hub. Default: false.", default: false],
                    includeHealthAlerts: [type: "boolean", description: "Include the full health-alerts block (default false).[[FLAT_TRIM]] Every /hub2/hubData alert flag + messages under healthAlerts; platformUpdate and safeMode are returned regardless.[[/FLAT_TRIM]]", default: false],
                    includeAppUpdate: [type: "boolean", description: "Also check GitHub for a newer MCP Rule Server APP version, returned under appUpdate (default false).[[FLAT_TRIM]] Asynchronous: the first call may return latestVersion 'unknown (check in progress)' -- call again in a few seconds. Distinct from platformUpdate (the hub's own firmware). To INSTALL a pending hub firmware update, use hub_update_firmware.[[/FLAT_TRIM]]", default: false]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    temperatureScale: [type: "string", description: "Hub temperature scale (F/C)"],
                    model: [type: "string", description: "Hardware ID/model"],
                    firmwareVersion: [type: "string", description: "Firmware version string"],
                    zigbeeChannel: [type: "string", description: "Zigbee radio channel"],
                    zwaveVersion: [type: "string", description: "Z-Wave firmware version"],
                    zigbeeId: [type: "string", description: "Zigbee ID"],
                    type: [type: "string", description: "Hub type"],
                    uptimeSeconds: [description: "Uptime in seconds (or 'unavailable' if the SDK lookup failed)"],
                    uptimeFormatted: [type: "string", description: "Human-readable uptime"],
                    freeMemoryKB: [type: "string", description: "Free OS memory in KB"],
                    memoryWarning: [type: "string", description: "Present when memory is low"],
                    memoryNote: [type: "string", description: "Present when memory is moderate"],
                    internalTempCelsius: [type: "string", description: "Internal temperature in Celsius"],
                    temperatureWarning: [type: "string", description: "Present when temperature is high"],
                    temperatureNote: [type: "string", description: "Present when temperature is warm"],
                    databaseSizeKB: [type: "string", description: "Database size in KB"],
                    databaseWarning: [type: "string", description: "Present when database is large"],
                    mcpServerVersion: [type: "string", description: "Installed MCP server version"],
                    lastBackupEpoch: [type: "integer", description: "Epoch millis of the last hub_create_backup via this app; null if never. The destructive-confirm 24h gate reads the same record."],
                    mcpDeviceCount: [type: "integer", description: "Selected device count"],
                    mcpRuleCount: [type: "integer", description: "MCP rule child-app count"],
                    mcpLogEntries: [type: "integer", description: "Buffered MCP log entry count"],
                    mcpCapturedStates: [type: "integer", description: "Captured device state count"],
                    hubSecurityConfigured: [type: "boolean", description: "Whether hub security is configured"],
                    readEnabled: [type: "boolean", description: "Read master toggle state (default ON)"],
                    writeEnabled: [type: "boolean", description: "Write master toggle state (default ON)"],
                    customRuleEngineEnabled: [type: "boolean", description: "Custom rule engine toggle state"],
                    developerModeEnabled: [type: "boolean", description: "Developer Mode toggle state"],
                    smokeTestMarker: [type: "string", description: "issue #209 #include canary -- 'smoke-ok-v1' from the McpSmokeTestLib library; throwaway, removed after the modularization split is validated"],
                    lastSelfDeploy: [type: "object", description: "issue #237: outcome of the last hub_update_app self-update (the MCP server updating its own app). Recovers the result that can't return on the deploy call (success reloads the app; a big-file compile failure 504s). Keys: success (bool), error (hub's verbatim message or null), sourceMode, importUrl, sourceLength, at (epoch ms), ageMs (ms since `at`, computed at read). PERSISTS in atomicState across app reloads -- it is NOT cleared on update, so a read can return a STALE record from an earlier deploy; check ageMs (or baseline `at` across your own deploy) for freshness before trusting it. Absent until the first self-update."],
                    name: [type: "string", description: "Hub name (Read master only)"],
                    localIP: [type: "string", description: "Hub local IP (Read master only)"],
                    timeZone: [type: "string", description: "Time zone ID (Read master only)"],
                    latitude: [type: "number", description: "Latitude (Read master only)"],
                    longitude: [type: "number", description: "Longitude (Read master only)"],
                    zipCode: [type: "string", description: "Zip code (Read master only)"],
                    hubData: [type: "object", description: "Hub data map (Read master only)"],
                    readDisabledNote: [type: "string", description: "Present when the Read master is disabled; PII excluded"],
                    identifyHubTriggered: [type: "boolean", description: "Present when identifyHub requested; LED blink result"],
                    identifyHubError: [type: "string", description: "Present when identifyHub blink failed"],
                    platformUpdate: [type: "object", description: "Pending HUB FIRMWARE/platform update from /hub2/hubData: {available (bool or null), currentVersion, availableVersion (when available), note (only when available=null)}. Distinct from the appUpdate MCP-server-app check; available=null means /hub2/hubData was unreadable/unrecognized and the note explains why. Install a pending update with hub_update_firmware."],
                    appUpdate: [type: "object", description: "MCP Rule Server APP version check; present only when includeAppUpdate=true. {installedVersion, latestVersion ('unknown (check in progress)' while the async GitHub check is pending), updateAvailable, lastChecked}. Separate from platformUpdate (the hub's own firmware)."],
                    safeMode: [type: "boolean", description: "Whether the hub is running in Safe Mode (from /hub2/hubData). Absent if /hub2/hubData was unreadable."],
                    healthAlerts: [type: "object", description: "Present only when includeHealthAlerts=true: the hub's health alerts from /hub2/hubData -- {safeMode, active (list of currently-firing alert flags), details (full alert map + message strings)}."]
                ]
            ]
        ],
        [
            name: "hub_list_modes",
            description: "List the hub's location modes (with the active one) + Mode Manager state.[[FLAT_TRIM]] Use it to get valid mode names + ids (hub-specific, e.g. Day/Night/Away) before activating/renaming/deleting a mode.[[/FLAT_TRIM]]",
            inputSchema: [type: "object", properties: [:]],
            outputSchema: [
                type: "object",
                properties: [
                    currentMode: [type: "string", description: "Current location mode name"],
                    modes: [type: "array", description: "Available modes", items: [type: "object", properties: [
                        id: [type: "string", description: "Mode ID"],
                        name: [type: "string", description: "Mode name"],
                        icon: [type: "string", description: "Mode icon name (when available)"]
                    ]]],
                    modeManager: [type: "object", description: "Mode Manager state (when readable)", properties: [
                        selected: [type: "string", description: "Active manager: builtIn | legacy | app (a 3rd-party mode-manager app)"],
                        appId: [type: "string", description: "Mode Manager app id"],
                        easyModeManagerAppId: [type: "string", description: "Integrated Mode Manager app id"],
                        easyConditions: [type: "object", description: "The Integrated Mode Manager's per-mode automation conditions, keyed by mode id ({} when none set)"]
                    ]]
                ],
                required: ["currentMode", "modes"]
            ]
        ],
        [
            name: "hub_manage_mode",
            description: """⚠️ Create, rename, delete, or activate a hub location mode.[[FLAT_TRIM]] The full mode-management surface in one tool. Modes (Day/Night/Away/…) are hub-wide states apps and rules trigger on. Read current modes + ids with hub_list_modes first. delete is irreversible and breaks any app/rule referencing that mode, so it requires confirm=true + a recent backup; create/rename/activate do not.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["create", "rename", "delete", "activate"], description: "create | rename | delete | activate a location mode."],
                    name: [type: "string", description: "New mode name (create), or the new name (rename)."],
                    mode: [type: "string", description: "Target for rename/delete/activate: id or name (from hub_list_modes)."],
                    icon: [type: "string", description: "OPTIONAL icon for create/rename.[[FLAT_TRIM]] Font Awesome name, e.g. fa-moon, fa-sun.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED for action=delete: must be true.[[FLAT_TRIM]] Confirms a backup <24h + that breaking mode references is intended.[[/FLAT_TRIM]]"]
                ],
                required: ["action"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the action succeeded"],
                    action: [type: "string", description: "The action performed"],
                    modes: [type: "array", description: "Resulting mode list (create/rename/delete)", items: [type: "object"]],
                    previousMode: [type: "string", description: "Mode before activate"],
                    newMode: [type: "string", description: "Mode after activate"],
                    deletedModeId: [type: "string", description: "The deleted mode id"],
                    error: [type: "string", description: "Failure reason (success=false)"],
                    note: [type: "string", description: "Guidance / recovery"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_mode_manager",
            description: """Configure the hub's Mode Manager — select which manager runs and/or set its per-mode conditions.[[FLAT_TRIM]] Mode Manager is the automation that changes the location mode automatically: 'manager' selects builtIn (the Integrated Mode Manager), legacy (the legacy Mode Manager app), or app (a 3rd-party mode-manager app — only valid when one is installed). 'conditions' replaces the Integrated Mode Manager's per-mode condition set (POST /modes/easyModeManager/json) — read the current shape from hub_list_modes.modeManager.easyConditions first and modify-then-write. Read state back with hub_list_modes.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    manager: [type: "string", enum: ["builtIn", "legacy", "app"], description: "Which Mode Manager to activate.[[FLAT_TRIM]] app = a 3rd-party mode-manager app, valid only when one is installed.[[/FLAT_TRIM]]"],
                    conditions: [type: "object", description: "OPTIONAL: per-mode automation conditions to set.[[FLAT_TRIM]] Same shape as hub_list_modes.modeManager.easyConditions (keyed by mode id); read-modify-write that block.[[/FLAT_TRIM]]"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the change succeeded"],
                    manager: [type: "string", description: "The manager that was selected"],
                    conditionsUpdated: [type: "boolean", description: "True if conditions were applied"],
                    error: [type: "string", description: "Manager-selection failure reason"],
                    conditionsError: [type: "string", description: "Conditions-update failure reason"],
                    note: [type: "string", description: "Guidance"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_get_hsm_status",
            description: "Get the current HSM (Hubitat Safety Monitor) armed status, any active alert, and the valid HSM arm commands.[[FLAT_TRIM]] Use this to check the security-system state or to confirm a change made via hub_set_hsm.[[/FLAT_TRIM]]",
            inputSchema: [type: "object", properties: [:]],
            outputSchema: [
                type: "object",
                properties: [
                    status: [type: "string", description: "Current HSM status (disarmed/armedAway/armedHome/armedNight); may be null if HSM is disabled or hasn't reported yet"],
                    statusText: [type: "string", description: "Human-readable status; interprets a null/empty status"],
                    alert: [type: "string", description: "Current HSM alert, if any"],
                    armCommands: [type: "array", description: "Valid arm commands for hub_set_hsm (NOT hub Day/Night/Away location modes)", items: [type: "string"]]
                ],
                required: ["status", "statusText", "armCommands"]
            ]
        ],
        [
            name: "hub_set_hsm",
            description: "Set HSM mode (armAway, armHome, armNight, disarm). Always verify HSM changed after.",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", description: "HSM mode: armAway, armHome, armNight, disarm"]
                ],
                required: ["mode"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the HSM arm event was sent"],
                    previousStatus: [type: "string", description: "HSM status before the change"],
                    newMode: [type: "string", description: "Requested HSM mode"]
                ],
                required: ["success", "previousStatus", "newMode"]
            ]
        ],
        [
            name: "hub_set_system_settings",
            description: """Set hub-GLOBAL location settings: hub name, time zone, latitude/longitude, zip code, temperature scale. All optional — pass only what changes.[[FLAT_TRIM]] lat/long/timeZone/temperatureScale/zipCode are written together via one granular endpoint that read-merges current values, so omitted fields keep their value. ⚠️ Changing timeZone REBOOTS the hub (1-3 min downtime) — it requires confirm=true + a backup <24h; the other fields need only the Write master. Read back applied values with hub_get_info. Requires Write master.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    hubName: [type: "string", description: "New hub name."],
                    timeZone: [type: "string", description: "IANA time zone ID, e.g. 'America/New_York'.[[FLAT_TRIM]] ⚠️ Changing this REBOOTS the hub — requires confirm=true + a recent backup.[[/FLAT_TRIM]]"],
                    latitude: [type: "number", description: "Latitude in decimal degrees, e.g. 40.7128."],
                    longitude: [type: "number", description: "Longitude in decimal degrees, e.g. -74.006."],
                    zipCode: [type: "string", description: "Postal/zip code, e.g. '10001'."],
                    temperatureScale: [type: "string", enum: ["F", "C"], description: "Temperature scale: F or C."],
                    confirm: [type: "boolean", description: "Required only to change timeZone (reboots the hub).[[FLAT_TRIM]] Must be true; confirms a backup <24h + that the reboot is intended.[[/FLAT_TRIM]]"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the settings were applied"],
                    applied: [type: "array", description: "The fields that were changed", items: [type: "string"]],
                    error: [type: "string", description: "Failure reason (success=false)"],
                    note: [type: "string", description: "Guidance / recovery; how to read back values"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_create_backup",
            description: """Create a full hub backup. REQUIRED before any Write master operation (24h validity).[[FLAT_TRIM]] Requires Write master + confirm. This is the only write tool that doesn't require a prior backup.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "Must be true to confirm you want to create a backup"],
                    mock: [type: "boolean", description: "Developer Mode only: stamp the 24h gate record; NO real backup (test envs)."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the backup was created"],
                    confirmed: [type: "boolean", description: "Whether completion was confirmed via the hub's backup status (false = best-effort trigger)"],
                    mocked: [type: "boolean", description: "true when mock=true stamped the gate record without a real backup"],
                    message: [type: "string", description: "Human-readable result"],
                    backupTimestamp: [type: "string", description: "Formatted backup time"],
                    backupTimestampEpoch: [type: "integer", description: "Backup time in epoch millis"],
                    note: [type: "string", description: "Where to download the backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_reboot",
            description: """⚠️ DESTRUCTIVE: Reboots the hub (1-3 min downtime, all automations stop). To install a pending hub firmware update instead, use hub_update_firmware.

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user 3) Get explicit confirmation 4) Set confirm=true
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the reboot."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the reboot was initiated"],
                    message: [type: "string", description: "Human-readable result"],
                    lastBackup: [type: "string", description: "Formatted timestamp of last backup"],
                    warning: [type: "string", description: "Downtime warning"],
                    response: [type: "string", description: "Truncated hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_shutdown",
            description: """⚠️ EXTREME: Powers OFF the hub (requires physical restart). NOT a reboot.

PRE-FLIGHT: 1) Ensure backup <24h old 2) Tell user it won't restart automatically 3) Get explicit confirmation 4) Set confirm=true
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved the shutdown."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the shutdown was initiated"],
                    message: [type: "string", description: "Human-readable result"],
                    lastBackup: [type: "string", description: "Formatted timestamp of last backup"],
                    warning: [type: "string", description: "Power-off warning; hub will not auto-restart"],
                    response: [type: "string", description: "Truncated hub response body"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_firmware",
            description: """⚠️ DESTRUCTIVE: Install the hub's pending platform/firmware update. The hub downloads + installs it and then REBOOTS ITSELF (5-10 min of full downtime; all automations and device communications stop).[[FLAT_TRIM]] Uses the hub's own cloud-update path (/hub/cloud/checkForUpdate + /hub/cloud/updatePlatform). Read whether an update is pending first with hub_get_info (platformUpdate). On apply, the `available` field returns the checkForUpdate payload verbatim (version, upgrade, status, releaseNotesUrl, beta, hubCount, and the hub owner's accountEmails). Poll install progress with statusOnly=true (status IDLE when none is running); the endpoint goes dark during the reboot, then confirm the new firmwareVersion via hub_get_info.[[/FLAT_TRIM]]

PRE-FLIGHT (apply): 1) Ensure backup <24h old 2) Confirm an update is actually pending 3) Tell user about the downtime 4) Get explicit confirmation 5) Set confirm=true
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    statusOnly: [type: "boolean", description: "Poll /hub/cloud/checkUpdateStatus only and return without applying anything. No confirm/backup needed. Default false."],
                    confirm: [type: "boolean", description: "REQUIRED to apply (omit for statusOnly): must be true. Confirms a backup <24h exists and the user approved the install + reboot."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the install was initiated (or the status poll ran)"],
                    statusOnly: [type: "boolean", description: "True when this was a status poll (no install)"],
                    status: [description: "The /hub/cloud/checkUpdateStatus payload; present for statusOnly. Usually a parsed object (e.g. {status:'IDLE'}) but can be a plain string if the hub returns a non-JSON body (e.g. during the reboot)."],
                    message: [type: "string", description: "Human-readable result; present on apply"],
                    available: [type: "object", description: "The /hub/cloud/checkForUpdate payload, returned verbatim; present on apply. Fields: version (the available firmware version), upgrade (bool, whether one is pending), status (e.g. 'UPDATE_AVAILABLE'), releaseNotesUrl, beta (bool), hubCount, and accountEmails (the hub owner's own account email)."],
                    lastBackup: [type: "string", description: "Formatted timestamp of last backup"],
                    warning: [type: "string", description: "Downtime/reboot warning"],
                    response: [type: "string", description: "Truncated hub response body"],
                    error: [type: "string", description: "Present on failure"],
                    note: [type: "string", description: "Actionable recovery guidance on failure"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partSystem() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Hub state reads
        "hub_get_info", "hub_list_modes", "hub_get_hsm_status"
    ]
}

def _idempotentWriteToolNames_partSystem() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Hub state
        "hub_set_hsm", "hub_set_mode_manager"
    ]
}

def _openWorldToolNames_partSystem() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        // hub_get_info reaches GitHub for the app-version check when includeAppUpdate=true;
        // hub_update_firmware drives the hub's cloud download/install of the platform update.
        "hub_get_info", "hub_update_firmware"
    ]
}

def _toolDisplayMeta_partSystem() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Hub state + modes
        hub_get_info: [title: "Get Hub Info", summary: "Comprehensive hub info: hardware, health, firmware/platform-update status, network, and MCP stats."],
        hub_list_modes: [title: "List Modes", summary: "List the hub's location modes, the active one, and Mode Manager state."],
        hub_manage_mode: [title: "Manage Modes", summary: "Create, rename, delete, or activate a hub location mode."],
        hub_set_mode_manager: [title: "Set Mode Manager", summary: "Pick the Mode Manager and update its per-mode conditions."],
        hub_get_hsm_status: [title: "Get HSM Status", summary: "Get the current Hubitat Safety Monitor arm status."],
        hub_set_hsm: [title: "Set HSM Arm Mode", summary: "Arm or disarm Hubitat Safety Monitor."],
        hub_set_system_settings: [title: "Set System Settings", summary: "Set hub name, time zone, latitude/longitude, zip code, or temperature scale."],
        // Hub utilities
        hub_create_backup: [title: "Create Hub Backup", summary: "Create a full hub backup (required before destructive writes)."],
        hub_update_firmware: [title: "Update Hub Firmware", summary: "Install the hub's pending platform/firmware update (downloads, installs, and reboots the hub)."],
        // Destructive hub ops
        hub_reboot: [title: "Reboot Hub", summary: "Reboot the hub (1-3 minutes of downtime)."],
        hub_shutdown: [title: "Shut Down Hub", summary: "Power the hub off; a physical restart is required afterwards."]
    ]
}
