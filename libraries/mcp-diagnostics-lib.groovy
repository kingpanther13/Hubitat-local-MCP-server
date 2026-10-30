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
    def zwData = null
    if (result.zwaveData instanceof Map) zwData = result.zwaveData
    else if (result.zwave instanceof Map && result.zwave.zwaveData instanceof Map) zwData = result.zwave.zwaveData
    boolean zwaveJs = zwData?.zwaveJS == true
    if (nodeId) {
        if (radio == "matter") {
            result.matterPairStatus = _radioGetSafe("/hub/matterPairDeviceStatus", [nodeId: nodeId])
        } else {
            result.nodeState = _radioGetSafe("/hub/zwave2/getNodeState", [node: nodeId])
            // Z-Wave JS only: the interview/command-class detail and the link-reliability test status.
            if (zwaveJs) {
                result.nodeDetails = _radioGetSafe("/hub/zwave2/nodeDetails", [node: nodeId])
                result.linkReliability = _radioGetSafe("/hub/zwave2/linkReliability/status", [node: nodeId])
            } else if (radio != "zigbee") {
                result.nodeDetailsNote = (zwData == null) ? "nodeDetails and linkReliability need the Z-Wave details, which could not be read." :
                                                            "nodeDetails and linkReliability need the Z-Wave JS stack; this hub runs the legacy stack."
            }
        }
    }

    if (radio == "matter") result.wifiCredentials = _radioGetSafe("/hub/matter/wifiCredentials")

    if (args?.include_devices && radio != "matter" && radio != "zwave") result.zigbeeDevices = _zigbeeDeviceActivity()

    if (args?.backup_job_id) {
        result.zwaveBackupJob = _radioGetSafe("/hub/zwave/localBackup/job/${URLEncoder.encode(args.backup_job_id.toString(), 'UTF-8')}")
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
            zigbee: _radioGetSafe("/hub/zigbeeInfo/status"),
            // Z-Wave JS stack readiness + interview progress, the Z-Wave local-backup availability
            // ({available, entitled, firmwareVersion, zwaveJSVersion}) and a batch firmware run.
            zwaveJs: _radioGetSafe("/hub/zwave2/updateStatus"),
            zwaveLocalBackup: _radioGetSafe("/hub/zwave/localBackup/status"),
            zwaveFirmwareBatch: _radioGetSafe("/hub/zwave/deviceFirmware/batchProgress")
        ]
    }

    // Matter chip-tool logs ({text}, ANSI).
    if (args?.include_logs) result.matterLogs = _radioGetSafe("/hub/matterLogs/json")

    // Zigbee channel energy-scan results.
    if (args?.include_channel_scan) result.channelScan = _radioGetSafe("/hub/zigbeeChannelScanJson")

    // SmartStart provisioning entries (cache-bust the list like the UI does).
    if (args?.include_smartstart) result.smartStart = _radioGetSafe("/mobileapi/zwave/smartstart/list", [t: now()])

    // Firmware-eligible Z-Wave devices + available files.
    if (args?.include_firmware) {
        result.firmware = [
            devices: _radioGetSafe("/hub/zwave/deviceFirmware/devices"),
            files: _radioGetSafe("/hub/zwave/deviceFirmware/files")
        ]
        // Per node: the device's firmware targets, any update the hub's firmware service offers
        // for it (updateId feeds device_firmware_start_available), the flash progress, and the
        // other nodes a batch run could include (Z-Wave JS).
        if (nodeId && radio != "matter") {
            def q = [nodeId: nodeId]
            result.firmware.node = [
                details: _radioGetSafe("/hub/zwave/deviceFirmware/details", q),
                available: _radioGetSafe("/hub/zwave/deviceFirmware/available", q),
                progress: _radioGetSafe("/hub/zwave/deviceFirmware/progress", q),
                batchCandidates: _radioGetSafe("/hub/zwave/deviceFirmware/batchCandidates", q)
            ]
        }
    }
}

// Zigbee devices with the time of each one's last radio message (GET /hub/zigbee/getDevicesJson).
// lastMessage is epoch ms or null when the hub has heard nothing since its last start. Silent
// devices sort first, then the longest quiet.
private Map _zigbeeDeviceActivity() {
    def raw = _radioGetSafe("/hub/zigbee/getDevicesJson")
    if (!(raw instanceof Map) || !(raw.devices instanceof List)) {
        return (raw instanceof Map && raw.error) ? raw : [error: "/hub/zigbee/getDevicesJson returned an unexpected shape."]
    }
    long nowMs = now()
    def devices = (((raw.devices).findAll((this.&_bat529Cb_7399a956_9))).collect(((this.&_bat529Cb_7399a956_45).curry([nowMs] as Object[])))).sort((this.&_bat529Cb_7399a956_46))
    return [devices: devices, count: devices.size(),
            note: "lastMessage is the last Zigbee radio message the hub received from the device; null means none since the hub last started."]
}

// Shared radio GET: authenticated internal GET, parsed as JSON when the body is
// JSON, returned as a trimmed raw string otherwise (several radio endpoints return
// plain text -- getNodeState, the topology table). THROWS on a hub fault (4xx/5xx/
// timeout) so a WRITE path's existing catch reports success:false rather than a
// fabricated success:true with the error buried in the response. Resilient READS
// (the hub_get_radio_details includes) use _radioGetSafe instead.
// `query` rides hubInternalGet's query MAP -- an embedded '?' in the path is escaped into the
// literal path by the platform client, which 404s these exact routes (see the _hubRequest guard).
private _radioGet(String path, Map query = null) {
    def txt = hubInternalGet(path, query)
    if (!txt) return null
    try { return new groovy.json.JsonSlurper().parseText(txt) }
    catch (Exception parseErr) { return txt.take(8000) }
}

// Non-throwing read variant: a hub fault becomes an {error} map instead of
// propagating, so one bad include (an absent radio or older firmware) does not
// fail the whole hub_get_radio_details read -- the miss stays visible per-include.
private _radioGetSafe(String path, Map query = null) {
    try { return _radioGet(path, query) }
    catch (IllegalStateException guardErr) {
        // The ?-in-path guard is a server coding bug, not a hub fault -- rethrow it so it cannot
        // be laundered into an {error} map that a caller reports as a per-include miss (see
        // _hubRequest's rethrow contract).
        throw guardErr
    }
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
    try { result.zwaveVersion = hub?.zwaveVersion } catch (Exception e) { result.zwaveVersion = null }

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
    // The Hub object has no zwaveVersion property on current firmware; the details JSON carries the radio firmware.
    if (result.zwaveVersion == null) result.zwaveVersion = (result.zwaveData instanceof Map ? result.zwaveData.firmwareVersion : null) ?: "unavailable"

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
    try { result.zigbeeChannel = hub?.zigbeeChannel } catch (Exception e) { result.zigbeeChannel = null }
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
    if (result.zigbeeChannel == null) result.zigbeeChannel = (result.zigbeeData instanceof Map ? result.zigbeeData.channel : null) ?: "unavailable"

    if (args?.include_topology) result.topology = _fetchRadioTopology("zigbee")

    mcpLog("info", "hub-admin", "Retrieved Zigbee details")
    return result
}

private Map _parseHubLogLine(String line) {
    if (!line?.trim()) return null
    def parts = line.split("\t", -1)
    if (parts.size() < 2) return null
    if (parts.size() >= 3 && (parts.size() == 3 || parts[2].startsWith('app|') || parts[2].startsWith('dev|')) &&
        (parts[0] ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?/)) {
        String message = parts[2..-1].join("\t").trim()
        def source = message.split("\\|", 4)
        boolean identified = source.size() == 4 && (source[0] in ["app", "dev"])
        return [name: identified ? source[2] : "", level: parts[1].trim(), message: message,
                time: parts[0].trim(), type: identified ? source[0] : "",
                sourceId: identified ? source[1] : null, hubLocalTime: true]
    }
    def entry = [name: parts[0].trim(), level: parts[1].trim(),
                 message: parts.size() > 2 ? parts[2].trim() : "",
                 time: parts.size() > 3 ? parts[3].trim() : "",
                 type: parts.size() > 4 ? parts[4].trim() : ""]
    if (parts.size() > 5) {
        entry.message = parts[2..(parts.size() - 3)].join("\t")
        entry.time = parts[-2].trim()
        entry.type = parts[-1].trim()
    }
    return entry
}

// Scoped snapshots live only long enough for a continuation or terminal replay. Bound
// simultaneous snapshots, never their content; retain continuation snapshots until replay expires.
def _nativeLogSnapshot(Map query, Map args) {
    if (!_mrtrReadContinuationActive()) {
        return [state: "ready", text: hubInternalGet("/logs/past/json", query, 30)]
    }
    return _hubReadSnapshot(query, args, null)
}

private String _deviceReadAccessScope() {
    return groovy.json.JsonOutput.toJson([
        selected: ((settings.selectedDevices ?: [])).collect((this.&_bat529Cb_7399a956_20)).sort(),
        children: ((getChildDevices() ?: [])).collect((this.&_bat529Cb_7399a956_13)).sort(),
        bypass: _bypassEnabled()
    ])
}

private def _deviceReadSnapshot(String tool, Map args, Map context) {
    // The worker runs without the request clock: the request's budget belongs to the observing request,
    // and a leaf that honours it would cut a read the continuation exists to finish.
    Map workArgs = _mrtrCopyMap(args)
    workArgs.remove("__reqT0")
    Map work = [id: context.id, fresh: context.fresh, tool: tool,
                outerTool: context.outerTool ?: tool,
                args: workArgs, scope: _deviceReadAccessScope()]
    Map snapshot = _hubReadSnapshot(null, args, work)
    if (work.scope != _deviceReadAccessScope()) {
        throw new IllegalArgumentException("Device access changed during this read; start a fresh call.")
    }
    if (snapshot.state == "pending") return [status: "in_progress", tool: tool]
    context.fetchedAt = snapshot.fetchedAt
    return new groovy.json.JsonSlurper().parseText(snapshot.text)
}

private Map _hubReadSnapshot(Map query, Map args, Map deviceRead) {
    String owner = app?.id?.toString() ?: "0"
    String key = deviceRead != null ? "${owner}:device:${deviceRead.id}".toString() :
        "${owner}:${query?.type ?: 'all'}:${query?.id ?: ''}".toString()
    Map job = null
    String fetchId
    synchronized (NATIVE_LOG_SNAPSHOTS) {
        (((NATIVE_LOG_SNAPSHOTS.entrySet()).findAll((this.&_bat529Cb_7399a956_48))).collect((this.&_bat529Cb_7399a956_49))).each((this.&_bat529Cb_7399a956_50))
        if (deviceRead != null) {
            def current = NATIVE_LOG_SNAPSHOTS[key]
            if (current instanceof Map && current.scope != deviceRead.scope) {
                throw new IllegalArgumentException("Device access changed during this read; start a fresh call.")
            }
            if (!(current instanceof Map) && deviceRead.fresh != true) {
                throw new IllegalArgumentException("Device read snapshot expired or was lost; start a fresh call.")
            }
        }
        // Returning a continuation reserves its snapshot through terminal replay.
        // Completed one-round reads can be evicted after every active caller observes them.
        if (!(NATIVE_LOG_SNAPSHOTS[key] instanceof Map) && NATIVE_LOG_SNAPSHOTS.size() >= 8) {
            def ready = (NATIVE_LOG_SNAPSHOTS).findAll((this.&_bat529Cb_7399a956_37))
            if (ready) NATIVE_LOG_SNAPSHOTS.remove((ready).min((this.&_bat529Cb_7399a956_1)).key)
            else throw new IllegalStateException("Background read capacity is full; retry after existing snapshots expire.")
        }
        if (!(NATIVE_LOG_SNAPSHOTS[key] instanceof Map) && NATIVE_LOG_SNAPSHOTS.size() < 8) {
            fetchId = java.util.UUID.randomUUID().toString()
            NATIVE_LOG_SNAPSHOTS.put(key, [at: now(), pending: true, fetchId: fetchId])
            if (deviceRead != null) {
                NATIVE_LOG_SNAPSHOTS[key].work = deviceRead
                NATIVE_LOG_SNAPSHOTS[key].scope = deviceRead.scope
            }
            job = [key: key, owner: owner, fetchId: fetchId, query: query]
        }
        def snapshot = NATIVE_LOG_SNAPSHOTS[key]
        fetchId = snapshot.fetchId
        snapshot.readers = ((snapshot.readers ?: 0) as Integer) + 1
    }
    try {
        return _observeHubReadSnapshot(key, fetchId, job, args)
    } finally {
        synchronized (NATIVE_LOG_SNAPSHOTS) {
            def snapshot = NATIVE_LOG_SNAPSHOTS[key]
            if (snapshot instanceof Map && snapshot.fetchId == fetchId) {
                snapshot.readers = Math.max(0, ((snapshot.readers ?: 0) as Integer) - 1)
            }
        }
    }
}

private Map _observeHubReadSnapshot(String key, String fetchId, Map job, Map args) {
    if (job != null) {
        try {
            runInMillis(WORKER_START_DELAY_MS, "runNativeLogFetch", [overwrite: false, data: job])
        } catch (Exception scheduleError) {
            synchronized (NATIVE_LOG_SNAPSHOTS) {
                if (NATIVE_LOG_SNAPSHOTS[key]?.fetchId == job.fetchId) NATIVE_LOG_SNAPSHOTS.remove(key)
            }
            throw scheduleError
        }
    }
    long t0 = args?.__reqT0 instanceof Number ? args.__reqT0 as Long : now()
    long deadline = t0 + _logsJsonObserveWaitMs()
    long remainingBudget = Math.max(0L, deadline - now())
    while (true) {
        long remaining
        synchronized (NATIVE_LOG_SNAPSHOTS) {
            def snapshot = NATIVE_LOG_SNAPSHOTS[key]
            if (!(snapshot instanceof Map) || snapshot.fetchId != fetchId) {
                throw new IllegalStateException("Background read snapshot expired or was lost; start a fresh call.")
            }
            if (snapshot.pending != true) {
                if (snapshot.error) {
                    NATIVE_LOG_SNAPSHOTS.remove(key)
                    if (snapshot.invalid == true) throw new IllegalArgumentException(snapshot.error.toString())
                    throw new IllegalStateException(snapshot.error.toString())
                }
                return [state: "ready", text: snapshot.text, fetchedAt: snapshot.at]
            }
            remaining = Math.min(remainingBudget, Math.max(0L, deadline - now()))
            if (remaining <= 0L) {
                snapshot.replayProtected = true
                return [state: "pending"]
            }
        }
        long waitMs = Math.min(WORKER_POLL_MS, remaining)
        pauseExecution(waitMs)
        remainingBudget -= waitMs
    }
}

def runNativeLogFetch(Map job = [:]) {
    if (job.owner != (app?.id?.toString() ?: "0")) return
    Map work = null
    synchronized (NATIVE_LOG_SNAPSHOTS) {
        def current = NATIVE_LOG_SNAPSHOTS[job.key]
        if (!(current instanceof Map) || current.fetchId != job.fetchId || current.pending != true || current.started == true) return
        current.started = true
        work = current.work as Map
    }
    Map result
    try {
        if (work != null) {
            if (work.scope != _deviceReadAccessScope()) {
                throw new IllegalArgumentException("Device access changed during this read; start a fresh call.")
            }
            // Re-enter the original route so a gateway disabled while this job was queued
            // is checked alongside the live read master and leaf permissions.
            String outer = work.outerTool?.toString() ?: work.tool.toString()
            Map outerArgs = outer == work.tool ? work.args as Map : [tool: work.tool, args: work.args]
            def payload = _executeWithDeviceReadContext(outer, outerArgs, null)
            String text = groovy.json.JsonOutput.toJson(payload)
            int bytes = text.getBytes("UTF-8").length
            int limit = _responseSizeLimitBytes()
            if (bytes > limit) text = groovy.json.JsonOutput.toJson(
                _responseTooLargeEnvelope(work.tool.toString(), bytes, limit))
            result = [text: text, scope: work.scope]
        } else {
            result = [text: hubInternalGet("/logs/past/json", job.query as Map, 30)]
        }
    } catch (Exception fetchError) {
        boolean invalid = fetchError instanceof IllegalArgumentException
        result = [error: work != null && !invalid ? "Device read failed (${fetchError.class.simpleName})".toString() :
                    (fetchError.message ?: fetchError.toString()), invalid: invalid]
        if (work != null) result.scope = work.scope
    }
    synchronized (NATIVE_LOG_SNAPSHOTS) {
        if (NATIVE_LOG_SNAPSHOTS[job.key]?.fetchId == job.fetchId) {
            NATIVE_LOG_SNAPSHOTS.put(job.key, result + [at: now(), fetchId: job.fetchId, pending: false,
                replayProtected: NATIVE_LOG_SNAPSHOTS[job.key].replayProtected == true,
                readers: NATIVE_LOG_SNAPSHOTS[job.key].readers ?: 0])
        }
    }
}

// hub_get_logs reads tabs of the hub's Logs page (mode = the page's own tab key, or a list of them), or
// the MCP app's own log. What each mode accepts; a list of modes also takes pageSize for mcp and
// status (see toolGetHubLogs).
private Map _logsModeArgs() {
    List shared = ["pattern", "patterns", "patternMode", "limit", "cursor", "pageSize"]
    return [
        past: shared + ["level", "deviceId", "appId", "since", "until"],
        devices: shared + ["deviceId", "sortBy"],
        apps: shared + ["appId", "sortBy"],
        cloudCalls: shared + ["appId"],
        locationEvents: shared + ["since", "until"],
        hubEvents: shared + ["since", "until"],
        scheduledJobs: shared + ["deviceId", "appId"],
        runningJobs: shared + ["deviceId", "appId"],
        hubActions: shared + ["deviceId", "appId"],
        mcp: ["level", "component", "ruleId", "limit", "cursor"],
        status: []
    ]
}

// The fields each tab's own filter box searches.
private List _logsSearchFields(String mode) {
    switch (mode) {
        case "past": return ["name", "message"]
        case "scheduledJobs": case "runningJobs": return ["name", "methodName"]
        case "locationEvents": case "hubEvents": return ["name", "descriptionText", "value"]
        default: return ["name"]
    }
}

// Jobs, running jobs and hub actions link to their owner's page; the owner is read from that link.
private Map _logsOwner(link) {
    def m = (link?.toString() ?: "") =~ /^\/(installedapp\/configure|device\/edit)\/(\d+)$/
    if (!m.matches()) return null
    return [type: m.group(1) == "device/edit" ? "device" : "app", id: m.group(2) as Integer]
}

// pattern must match; patterns combine by patternMode ('any' default, 'all'). A row matches a regex
// when any of its tab's searchable fields does.
private Map _logsPatterns(Map args) {
    if (args.pattern != null && !(args.pattern instanceof String)) {
        throw new IllegalArgumentException("pattern must be a string (got ${args.pattern instanceof List ? 'list' : 'non-string'})")
    }
    if (args.patterns != null && !(args.patterns instanceof List)) {
        throw new IllegalArgumentException("patterns must be a list of strings (got ${args.patterns instanceof String ? 'string' : 'non-list'})")
    }
    String patternMode = args.patternMode?.toString()?.toLowerCase()
    if (patternMode != null && !(patternMode in ["any", "all"])) {
        throw new IllegalArgumentException("patternMode must be 'any' or 'all' (got '${args.patternMode}')")
    }
    def compile = { String label, p ->
        if (p != null && !(p instanceof String)) throw new IllegalArgumentException("${label} must be a string")
        if (!p) throw new IllegalArgumentException("${label} must not be null or empty")
        if (p.length() > 100) throw new IllegalArgumentException("${label} exceeds 100 char limit (was ${p.length()} chars)")
        try {
            return java.util.regex.Pattern.compile(p, java.util.regex.Pattern.CASE_INSENSITIVE)
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex ${label} '${p}': ${e.message}")
        }
    }
    List list = []
    ((args.patterns ?: [])).eachWithIndex(((this.&_bat529Cb_7399a956_29).curry([compile, list] as Object[])))
    return [single: args.pattern != null ? compile("pattern", args.pattern) : null, list: list, all: patternMode == "all"]
}

private boolean _logsRowMatches(Map row, List fields, Map patterns) {
    List texts = (fields).collect(((this.&_bat529Cb_7399a956_19).curry([row] as Object[])))
    def hit = { java.util.regex.Pattern p -> (texts).any(((this.&_bat529Cb_7399a956_3).curry([p] as Object[])))}
    if (patterns.single != null && !hit(patterns.single)) return false
    if (!patterns.list) return true
    return patterns.all ? patterns.list.every(hit) : patterns.list.any(hit)
}

// since is exclusive and until inclusive, so a row's time passed back as since never returns it again.
private boolean _logsOutsideWindow(Date at, Map query) {
    return (query.since != null && !at.after(query.since)) || (query.until != null && at.after(query.until))
}

private Long _logsRowTime(Map row) {
    return row.date != null ? _parseSinceArg(row.date)?.time : null
}

// since/until for past and the event tabs: a relative age ('30m', '2h', '7d'; at most 30 days),
// ISO-8601 with an offset or Z, epoch milliseconds, or a time without a zone, read in the hub's own
// time zone because that is how current past rows give their time.
private Date _logsTimeArg(String name, value) {
    if (value == null) return null
    if (!(value instanceof String) && !(value instanceof Number)) {
        throw new IllegalArgumentException("${name} must be a string or epoch milliseconds")
    }
    String s = value.toString().trim()
    String formats = "use a relative age like '30m', '2h' or '1d', a time like '2026-06-23 10:00:00' (hub time) or '2026-06-23T16:00:00Z', or epoch milliseconds"
    def rel = s =~ /^(\d+)([mhd])$/
    if (rel.matches()) {
        long unitMs = [m: 60000L, h: 3600000L, d: 86400000L][rel.group(2)] as Long
        // Compare the count before multiplying: a huge count would overflow into a future time.
        if (rel.group(1).length() > 9 || rel.group(1).toLong() > (30L * 86400000L).intdiv(unitMs)) {
            throw new IllegalArgumentException("${name} exceeds the 30d cap (got '${s}'); use an ISO-8601 time for older bounds")
        }
        return new Date(now() - rel.group(1).toLong() * unitMs)
    }
    if (s.isLong()) {
        // A small bare number is a missing unit ('30' for '30m'), not a time in 1970.
        if (s.toLong() < 946684800000L) throw new IllegalArgumentException("Cannot read ${name}='${s}' as a time: ${formats}")
        return new Date(s.toLong())
    }
    def stamp = s =~ /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})(\.\d{1,3})?(.*)$/
    if (!stamp.matches()) throw new IllegalArgumentException("Cannot parse ${name}='${s}': ${formats}")
    // The date and time must exist: a lenient parse would roll '2026-02-30' into March.
    def strict = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    strict.setLenient(false)
    String zone = stamp.group(4)
    strict.setTimeZone(zone ? TimeZone.getTimeZone("UTC") : _hubTimeZone())
    Date local
    try {
        local = strict.parse("${stamp.group(1)} ${stamp.group(2)}".toString())
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot parse ${name}='${s}': no such date and time")
    }
    if (!zone) {
        // '.5' is 500 ms: pad the fraction to three digits.
        long fractionMs = stamp.group(3) ? (stamp.group(3).substring(1) + "00").take(3).toLong() : 0L
        return new Date(local.time + fractionMs)
    }
    Date parsed = _parseSinceArg(s)
    if (parsed == null) throw new IllegalArgumentException("Cannot parse ${name}='${s}': ${formats}")
    return parsed
}

// Validate one log tab's arguments and build its query. Throws before anything is read.
private Map _logsQuery(Map args, String mode) {
    if (args.deviceId != null && args.appId != null) {
        throw new IllegalArgumentException("deviceId and appId are mutually exclusive: set only one")
    }
    String deviceId = args.deviceId?.toString()?.trim() ?: null
    String appId = args.appId?.toString()?.trim() ?: null
    if (deviceId) _validateNativeDeviceId(deviceId)
    if (appId && !appId.isInteger()) throw new IllegalArgumentException("appId must be numeric: ${appId}")
    if (args.sortBy != null && !(args.sortBy in ["pct", "count", "total", "stateSize", "name"])) {
        throw new IllegalArgumentException("sortBy must be one of pct, count, total, stateSize, name (got '${args.sortBy}')")
    }
    if (args.level != null && !(args.level.toString().toLowerCase() in ["trace", "debug", "info", "warn", "error", "all"])) {
        throw new IllegalArgumentException("level must be one of trace, debug, info, warn, error, all (got '${args.level}')")
    }
    Map patterns = _logsPatterns(args)
    Date since = _logsTimeArg("since", args.since)
    Date until = _logsTimeArg("until", args.until)
    if (since != null && until != null && !until.after(since)) {
        throw new IllegalArgumentException("until must be later than since (relative ages count back from now, so since='2h' is earlier than until='1h')")
    }
    if (args.limit != null && !_isWholeNumberIn(args.limit, 0L, Integer.MAX_VALUE)) {
        throw new IllegalArgumentException("limit must be a whole number: 0 for every row, or the most rows to return (got '${args.limit}')")
    }
    _logsCheckPageSize(args.pageSize)
    // Defaults: the newest 100 log lines and events, the 20 busiest stat rows, every other row.
    int limit
    if (args.limit != null) limit = args.limit as Integer
    else if (mode in ["devices", "apps"]) limit = 20
    else if (mode in ["past", "locationEvents", "hubEvents"]) limit = 100
    else limit = 0
    return [patterns: patterns, since: since, until: until, limit: limit, deviceId: deviceId, appId: appId,
            sortBy: args.sortBy ?: "pct", level: args.level?.toString()?.toLowerCase(),
            // pageSize alone starts paging from the first row.
            cursor: args.cursor != null ? args.cursor : (args.pageSize != null ? "" : null),
            pageSize: args.pageSize != null ? (args.pageSize as Integer) : 100]
}

private void _logsCheckPageSize(pageSize) {
    if (pageSize != null && !_isWholeNumberIn(pageSize, 1L, 100L)) {
        throw new IllegalArgumentException("pageSize must be a whole number from 1 to 100 (got '${pageSize}')")
    }
}

private TimeZone _hubTimeZone() {
    return location?.timeZone ?: TimeZone.getTimeZone("UTC")
}

def toolGetHubLogs(args) {
    Map modeArgs = _logsModeArgs()
    List allModeArgs = modeArgs.values().flatten().unique()
    // Internal request keys start with "__". Any other unknown key is refused, so a filter a client
    // still sends from an older catalog (source, type) is never silently ignored.
    List unknown = args.keySet()*.toString().findAll(this.&_logsUnknownArg.curry(allModeArgs))
    if (unknown) {
        throw new IllegalArgumentException("Unknown parameter${unknown.size() == 1 ? '' : 's'} for hub_get_logs: ${unknown.join(', ')}. Search a row's name and text with pattern, and scope with deviceId or appId.")
    }
    List modes = args.mode instanceof List ? args.mode*.toString().unique() : [args.mode == null ? "past" : args.mode.toString()]
    if (!modes) throw new IllegalArgumentException("mode must name at least one tab")
    for (m in modes) {
        if (!modeArgs.containsKey(m)) {
            throw new IllegalArgumentException("Invalid log mode: ${m}. Valid modes: ${modeArgs.keySet().join(', ')}")
        }
        def supplied = (allModeArgs - modeArgs[m]).findAll(this.&_argPresent.curry(args))
        // In a list, pageSize sets every tab's row count: the MCP log takes it as its limit, status ignores it.
        if (args.mode instanceof List && m in ["mcp", "status"]) supplied -= ["pageSize"]
        if (supplied) throw new IllegalArgumentException("Parameters ${supplied.join(', ')} do not apply to log mode '${m}'")
    }
    if (args.mode instanceof List) {
        if (args.cursor != null) throw new IllegalArgumentException("cursor pages one mode: each listed tab returns its first page; call that mode alone with its nextCursor")
        // Validate the list's pageSize and every Logs-page tab before reading any of them; mcp and
        // status check their own arguments when they run.
        _logsCheckPageSize(args.pageSize)
        (modes - ["mcp", "status"]).each(this.&_logsQuery.curry(args))
        // Every tab is answered under its own key, in the listed order, each paged tab as its first page with
        // its own nextCursor. Once a background read is pending the call answers in_progress, so the tabs
        // that can go pending run first and the inline tabs are read only on the leg that answers.
        List inline = ["cloudCalls", "locationEvents", "hubEvents", "status"]
        Map results = [:]
        for (m in modes) results.put(m, null)
        boolean pending = false
        for (m in (modes - inline) + (modes - (modes - inline))) {
            if (pending && m in inline) continue
            Map paging = [:]
            if (modeArgs[m].contains("pageSize")) paging = [cursor: "", pageSize: args.pageSize != null ? args.pageSize : 50]
            else if (modeArgs[m].contains("cursor")) paging = [cursor: ""]
            Map tabArgs = args
            if (m in ["mcp", "status"] && args.pageSize != null) {
                tabArgs = args.subMap(args.keySet() - ["pageSize"])
                if (m == "mcp" && args.limit == null) tabArgs.limit = args.pageSize
            }
            Map r
            try {
                r = toolGetHubLogs(tabArgs + [mode: m] + paging)
            } catch (IllegalArgumentException e) {
                throw e
            } catch (Exception e) {
                mcpLogError("monitoring", "hub_get_logs tab ${m} failed", e)
                r = [success: false, isError: true, error: e.message ?: e.class.simpleName, note: "Call hub_get_logs(mode='${m}') alone to retry it."]
            }
            if (r.status == "in_progress") pending = true
            r.remove("mode")
            results.put(m, r)
        }
        if (pending) {
            return [status: "in_progress", tool: "hub_get_logs", retryable: true,
                    note: "Some tabs are still loading on the hub. Continue with requestState, or repeat the same call on a legacy client."]
        }
        Map out = [modes: results]
        // The Logs page tabs share one fetch: state its uptime and snapshot once, not per tab.
        List shared = results.values().findAll(this.&_logsHasFetchSnapshot)
        if (shared.size() > 1 && shared*.snapshot*.fetchedAt.unique(false).size() == 1 && shared*.uptime.unique(false).size() == 1) {
            out.uptime = shared[0].uptime
            out.snapshot = shared[0].snapshot
            shared*.remove("uptime")
            shared*.remove("snapshot")
        }
        List failed = results.findAll(this.&_logsTabFailed).keySet() as List
        if (failed) {
            out.failedModes = failed
            if (failed.size() == results.size()) {
                out.success = false
                out.isError = true
                out.error = "Every listed tab failed: ${failed.join(', ')}"
            }
        }
        return out
    }
    String mode = modes[0]
    if (mode == "mcp") return toolGetDebugLogs(args)
    if (mode == "status") return toolGetLoggingStatus(args)
    Map query = _logsQuery(args, mode)
    if (mode == "past") {
        Map past = _pastLogs(args, query)
        if (query.deviceId && past.status != "in_progress") {
            // The hub answers [] for an unknown id and for a quiet device alike; an empty scoped read
            // checks the id once so a typo does not read as a silent device.
            past.deviceIdResolved = past.matched > 0 || _fetchDeviceFullJson(query.deviceId)?.device?.id?.toString() == query.deviceId
        }
        return past
    }

    List rows
    Map result = [mode: mode]
    Map logsJsonTables = [devices: "deviceStats", apps: "appStats", scheduledJobs: "jobs", runningJobs: "runningJobs", hubActions: "hubCommands"]
    if (logsJsonTables.containsKey(mode)) {
        def snap
        try {
            snap = _logsJsonSnapshot(args)
        } catch (Exception e) {
            mcpLogError("monitoring", "Failed to read the Logs page", e)
            return _logsJsonFailure("hub_get_logs", e.message ?: e.class.simpleName)
        }
        if (snap.state == "failed") return _logsJsonFailure("hub_get_logs", snap.error)
        if (snap.state == "pending") return _logsJsonInProgress("hub_get_logs")
        Map data = snap.snapshot
        rows = data[logsJsonTables[mode]] ?: []
        result.uptime = data.uptime
        if (mode == "devices") result.summary = [devicesUptime: data.devicesUptime, totalDevicesRuntime: data.totalDevicesRuntime, devicePct: data.devicePct]
        if (mode == "apps") result.summary = [appsUptime: data.appsUptime, totalAppsRuntime: data.totalAppsRuntime, appPct: data.appPct]
        result.snapshot = _logsJsonProvenance(data)
    } else {
        // The other tabs are small enough to read inline.
        String path = [cloudCalls: "/logs/cloudCalls/json", locationEvents: "/logs/eventsJson", hubEvents: "/hub/eventsJson"][mode]
        def parsed
        try {
            def text = hubInternalGet(path, null, 30)
            parsed = text?.toString()?.trim() ? new groovy.json.JsonSlurper().parseText(text.toString()) : null
        } catch (Exception e) {
            mcpLogError("monitoring", "${path} read failed", e)
            return [success: false, isError: true, error: "Could not read ${path}: ${e.message ?: e.class.simpleName}",
                    note: "Retry once; the same endpoint feeds the hub's Logs page."]
        }
        boolean shaped = mode == "cloudCalls" ? parsed instanceof Map : parsed instanceof List && !(parsed).any((this.&_bat529Cb_7399a956_4))
        if (!shaped) {
            return [success: false, isError: true, error: "${path} returned an unexpected shape",
                    note: "The hub's Logs page format may have changed; report it with hub_report_issue."]
        }
        rows = mode == "cloudCalls" ? _cloudCallRows(parsed) : parsed
        if (mode == "cloudCalls") {
            result.timeZone = parsed.timeZone
            if (parsed.startedAt != null) result.countingSince = formatTimestamp(parsed.startedAt as Long)
        }
    }
    return result + _logsPage(mode, rows, query)
}

// Method references over small, fixed-size lists only; see the note above _snapshotFetchedAt.
private boolean _argPresent(Map args, key) {
    return args[key] != null
}

private boolean _logsUnknownArg(List allowed, String key) {
    return !(key in allowed) && key != "mode" && !key.startsWith("__")
}

private boolean _logsHasFetchSnapshot(r) {
    return r.snapshot instanceof Map && r.snapshot.background != null
}

// Map.findAll passes key and value, so the unused tab name stays in the signature.
private boolean _logsTabFailed(mode, r) {
    return r.isError == true
}

// Apps from /logs/cloudCalls/json, each with its hourly counts (newest first, at most 48),
// busiest first.
private List _cloudCallRows(Map raw) {
    Map hoursByApp = [:]
    ((raw.hours instanceof List ? raw.hours : [])).each(((this.&_bat529Cb_7399a956_42).curry([hoursByApp] as Object[])))
    return ((((raw.apps instanceof List ? raw.apps : [])).findAll((this.&_bat529Cb_7399a956_25))).collect(((this.&_bat529Cb_7399a956_41).curry([hoursByApp] as Object[])))).sort((this.&_bat529Cb_7399a956_44))
}

// Filter, sort, limit and page one tab's native rows. Rows keep the hub's own field names; jobs,
// running jobs and hub actions gain an owner read from their link.
private Map _logsPage(String mode, List rows, Map query) {
    List fields = _logsSearchFields(mode)
    boolean owned = mode in ["scheduledJobs", "runningJobs", "hubActions"]
    boolean timed = mode in ["locationEvents", "hubEvents"]
    int unparseable = 0
    List matched = []
    String wantId = query.deviceId ?: query.appId
    String wantType = query.deviceId ? "device" : "app"
    String statType = mode == "devices" ? "device" : "app"
    for (row in rows) {
        if (!(row instanceof Map)) continue
        Map owner = owned ? _logsOwner(row.link) : null
        Map entry = owner != null ? row + [owner: owner] : row
        if (wantId) {
            Map scope = owned ? owner : [type: statType, id: row.id]
            if (scope?.type != wantType || scope.id?.toString() != wantId) continue
        }
        if (!_logsRowMatches(entry, fields, query.patterns)) continue
        if (timed && (query.since != null || query.until != null)) {
            Date at = row.date != null ? _parseSinceArg(row.date) : null
            if (at == null) unparseable++
            else if (_logsOutsideWindow(at, query)) continue
        }
        matched << entry
    }
    if (mode in ["devices", "apps"]) {
        if (query.sortBy == "name") (matched).sort((this.&_bat529Cb_7399a956_17))
        else (matched).sort(((this.&_bat529Cb_7399a956_12).curry([query] as Object[])))
    }
    // The event tabs are re-read on every page, newest first, so an event arriving between pages would
    // shift an offset. Their cursor also carries the newest row's time; later pages skip newer rows.
    def cursor = query.cursor
    Long anchor = null
    if (timed && cursor != null) {
        def keyed = cursor.toString() =~ /^(\d+)@(\d+)$/
        if (keyed.matches()) {
            cursor = keyed.group(1)
            anchor = keyed.group(2).toLong()
            matched = matched.findAll { Map row -> Long at = _logsRowTime(row); at == null || at <= anchor }
        } else {
            anchor = ((matched).collect((this.&_bat529Cb_7399a956_11))).findAll((this.&_bat529Cb_7399a956_24)).max()
        }
    }
    Map out = [total: rows.size(), matched: matched.size()]
    if (query.limit > 0 && matched.size() > query.limit) matched = matched.take(query.limit)
    def paged = _paginateList(matched, cursor, query.pageSize as int, "hub_get_logs")
    if (paged.nextCursor != null && anchor != null) paged.nextCursor = "${paged.nextCursor}@${anchor}".toString()
    // Null fields are left out of each row and named once in nullFields, so a row without a listed
    // field had it null (location events, for example, have no device fields).
    Set nullFields = new TreeSet()
    out.entries = (paged.page).collect(((this.&_bat529Cb_7399a956_38).curry([nullFields] as Object[])))
    if (nullFields) out.nullFields = nullFields as List
    out.count = paged.page.size()
    if (paged.nextCursor != null) out.nextCursor = paged.nextCursor
    if (unparseable > 0) out.timeFilterUnparseable = unparseable
    return out
}

// Past logs: the hub's log history, newest first. deviceId/appId scope the read on the hub, the
// way the Logs page opened from a device or app does.
private Map _pastLogs(Map args, Map query) {
    Map scope = null
    if (query.deviceId) scope = [type: "dev", id: query.deviceId]
    else if (query.appId) scope = [type: "app", id: query.appId]
    def responseText
    def fetchedAt = null
    try {
        def snapshot = _nativeLogSnapshot(scope, args)
        if (snapshot.state == "pending") {
            return [status: "in_progress", tool: "hub_get_logs", retryable: true,
                    note: "Native log history is still loading. Continue with requestState, or repeat the same call on a legacy client."]
        }
        responseText = snapshot.text
        fetchedAt = snapshot.fetchedAt
    } catch (Exception e) {
        mcpLogError("monitoring", "Failed to fetch hub logs", e)
        throw new IllegalStateException("Failed to fetch hub logs: ${e.message}")
    }
    def rows = []
    if (responseText) {
        try {
            rows = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception e) {
            // Older firmware answers newline-separated lines instead of a JSON array.
            rows = responseText.toString().split("\n").toList()
        }
    }
    if (!(rows instanceof List)) throw new IllegalStateException("Unexpected /logs/past/json response: expected a JSON array")
    // Current rows carry hub-local time; legacy five-column rows carry UTC.
    TimeZone hubZone = _hubTimeZone()
    List fields = _logsSearchFields("past")
    List entries = []
    int unparseable = 0
    for (line in rows.reverse()) {
        Map entry = _parseHubLogLine(line?.toString())
        if (entry == null) continue
        boolean hubLocal = entry.remove("hubLocalTime") == true
        entry.remove("sourceId")
        if (query.level && query.level != "all" && entry.level?.toLowerCase() != query.level) continue
        if (!_logsRowMatches(entry, fields, query.patterns)) continue
        if (query.since != null || query.until != null) {
            Date at = _pastLogTime(entry, hubLocal ? hubZone : TimeZone.getTimeZone("UTC"))
            if (at == null) unparseable++
            else if (_logsOutsideWindow(at, query)) continue
        }
        entries << entry
    }
    // The rows are already filtered above (the hub scoped the read), so _logsPage only limits and
    // pages them here.
    Map out = [mode: "past"] + _logsPage("past", entries, query + [patterns: [single: null, list: []], since: null, until: null, deviceId: null, appId: null])
    out.total = rows.size()
    if (unparseable > 0) out.timeFilterUnparseable = unparseable
    if (fetchedAt != null) out.snapshot = [fetchedAt: fetchedAt, ageMs: Math.max(0L, now() - (fetchedAt as Long))]
    if (((out.entries).sum(0, (this.&_bat529Cb_7399a956_28))as int) > _responseSizeLimitBytes()) {
        (out.entries).each((this.&_bat529Cb_7399a956_15))
        out.truncated = true
        out.note = "Log messages truncated to fit response size limit"
    }
    int noise = (out.entries).count((this.&_bat529Cb_7399a956_18))
    if (noise > 0) {
        out.benignRmNoiseCount = noise
        out.benignRmNoiseNote = "${noise} of these entr${noise == 1 ? 'y is' : 'ies are'} known-benign RM-internal noise (RM 5.1's own 'periodic' method logging an NPE on params.n while it renders the periodic sub-page during a periodic-trigger build). NON-FATAL, NOT an MCP-tool failure -- the trigger bakes correctly. Safe to ignore."
    }
    return out
}

private Date _pastLogTime(Map entry, TimeZone zone) {
    // Some firmware puts the timestamp in the first column and leaves time empty.
    String text = entry.time?.toString()?.trim() ?: entry.name?.toString()?.trim()
    if (!text) return null
    def m = text =~ /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})(\.\d{1,3})?$/
    if (!m.matches()) return _parseSinceArg(text)
    def sdf = new java.text.SimpleDateFormat(m.group(2) ? "yyyy-MM-dd HH:mm:ss.SSS" : "yyyy-MM-dd HH:mm:ss")
    sdf.setTimeZone(zone)
    return sdf.parse(text)
}

// hub_delete_logs: clear the MCP app's own log, or delete one scheduled job the way the Logs page's
// Delete button does (/hub/advanced/deleteScheduledJob, which removes that one Quartz job). The page
// offers no Delete on system jobs, so neither does this.
def toolDeleteLogs(args) {
    String mode = args.mode?.toString()
    if (!(mode in ["mcp", "scheduledJobs"])) {
        throw new IllegalArgumentException("mode must be 'mcp' (clear the MCP log) or 'scheduledJobs' (delete one scheduled job). The hub's past logs have no delete; its Reboot dialog offers a log purge: hub_reboot(confirm=true, purgeLogs=true).")
    }
    if (mode == "mcp") {
        def stray = ["jobId", "confirm"].findAll(this.&_argPresent.curry(args))
        if (stray) throw new IllegalArgumentException("Parameters ${stray.join(', ')} apply to mode='scheduledJobs' only")
        Map cleared = clearDebugLogEntries()
        mcpLog("info", "server", "MCP log cleared (${cleared.clearedCount != null ? "${cleared.clearedCount} entries" : 'entry count not loaded'})")
        return [success: true, mode: mode] + cleared
    }
    if (args.jobId != null && !(args.jobId instanceof String)) throw new IllegalArgumentException("jobId must be a string, e.g. app12Recur.poll")
    String jobId = args.jobId?.trim()
    if (!jobId) throw new IllegalArgumentException("jobId is required: the id of a row from hub_get_logs(mode='scheduledJobs'), e.g. app12Recur.poll")
    def key = jobId =~ /^([^.]+)\.(.+)$/
    if (!key.matches()) throw new IllegalArgumentException("jobId '${jobId}' is not a scheduled-job id (group.name, e.g. app12Recur.poll)")
    if (jobId.startsWith("system.")) throw new IllegalArgumentException("System jobs cannot be deleted; the Logs page offers no Delete for them either")
    def ownerMatch = key.group(1) =~ /^(app|dev)(\d+)(Recur|Once)$/
    if (ownerMatch.matches() && ownerMatch.group(2).length() > 9) {
        throw new IllegalArgumentException("jobId '${jobId}' names no app or device on this hub; read current ids with hub_get_logs(mode='scheduledJobs')")
    }
    Map owner = ownerMatch.matches() ? [type: ownerMatch.group(1) == "dev" ? "device" : "app", id: ownerMatch.group(2) as Integer] : null
    // A job's owner is guarded like the owner itself: a device outside this server's device access,
    // a protected app, and (for a client token) the MCP app's own background workers.
    if (owner?.type == "device") {
        try {
            _requireDeviceToolAccess(owner.id.toString())
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Job ${jobId} belongs to device ${owner.id}, which is outside this server's device access, so it cannot be deleted here.")
        }
    } else if (owner?.type == "app") {
        String ownerId = owner.id.toString()
        if (callerClient != null && ownerId == app.id?.toString()) {
            throw new IllegalArgumentException("A per-client access token cannot delete the MCP Rule Server app's own scheduled jobs.")
        }
        if (_protectedAppIds().contains(ownerId)) {
            throw new IllegalArgumentException("App ${ownerId} is protected, so its scheduled jobs cannot be deleted. Manage Protected apps on the Advanced page of the MCP server's Hubitat app.")
        }
    }
    if (args.confirm != true) throw new IllegalArgumentException("Deleting scheduled job ${jobId} needs confirm=true")
    String reply
    try {
        reply = hubInternalGet("/hub/advanced/deleteScheduledJob", [id: jobId], 30)?.toString()?.trim()
    } catch (Exception e) {
        mcpLogError("monitoring", "deleteScheduledJob ${jobId} failed", e)
        return [success: false, isError: true, jobId: jobId, error: "The delete request failed: ${e.message ?: e.class.simpleName}",
                note: "Check hub_get_logs(mode='scheduledJobs') before retrying: the job may already be gone."]
    } finally {
        // Whatever the hub answered, the cached jobs table may now be wrong, and so may the copy a
        // fetch already in flight is about to publish.
        _logsJsonInvalidate()
    }
    if (reply == "done") {
        mcpLog("info", "monitoring", "Deleted scheduled job ${jobId}")
        Map out = [success: true, mode: mode, jobId: jobId,
                   note: "Only this scheduled run is gone. Its owner is unchanged and schedules it again if its own code does, for example when its settings are saved."]
        if (owner) out.owner = owner
        return out
    }
    if (reply == "job does not exist") {
        return [success: false, isError: true, jobId: jobId, error: "No scheduled job ${jobId} exists",
                note: "A one-time job is gone once it has run, and an earlier delete of it may already have worked. Read current ids with hub_get_logs(mode='scheduledJobs')."]
    }
    return [success: false, isError: true, jobId: jobId, error: "The hub did not delete ${jobId}: ${reply ?: 'empty reply'}"]
}

// /logs/json is the hub's whole live Logs page in one document: every device and app stat and
// the job tables behind five of hub_get_logs' modes. It grows with device and app count, so on a
// large hub one fetch can outrun the cloud relay's ceiling, and a client then sees a 502 with
// nothing to retry. When the request's transport carries a time budget (relayBudgetMs over the
// cloud, lanBudgetMs on the LAN) the fetch therefore runs in a scheduled worker, and its tables
// are held in the JVM for a short window so every mode and cursor page answers from one fetch.
// Keep any new /logs/json consumer on _logsJsonSnapshot (sandbox_lint enforces it).
def _logsJsonSnapshotTtlMs() { 30000L }
// A fetch marker or failure older than this is treated as stale: the marker no longer blocks a
// new worker, and the failure is no longer reported. It is a bound, not proof the worker died,
// so every publish is fenced by the fetchId the worker was scheduled with.
def _logsJsonFetchStaleMs() { 45000L }

// How long a budgeted request may wait for the worker before handing back in_progress. The
// leg does nothing else, so it keeps 1500 ms of headroom under the budget (the write
// observer's cloud path keeps 2000 ms), capped so eight continuation slices still cover the
// fetch's own 30 s timeout.
def _logsJsonObserveWaitMs() {
    boolean cloud = _isCloudRequest()
    long cap = cloud ? 4500L : 6000L
    long budget = cloud ? _relayBudgetMs() : _lanBudgetMs()
    if (budget <= 0L) return cap
    return Math.max(1L, Math.min(cap, budget - 1500L))
}

def _logsJsonUsesBackgroundFetch() { _mrtrReadContinuationActive() }

// The page's formatted* columns are display copies of the raw values beside them.
private List _logsJsonStats(List statsList) {
    return (statsList).collect((this.&_bat529Cb_7399a956_32))
}

// A present table that is not an array, or a table row that is not an object, is a changed
// contract and must fail loudly rather than be reported as an empty hub.
private List _logsJsonTable(Map data, String key) {
    def table = data[key]
    if (table == null) return []
    if (!(table instanceof List)) throw new IllegalStateException("Unexpected /logs/json response: '${key}' is not an array")
    if ((table).any((this.&_bat529Cb_7399a956_2))) {
        throw new IllegalStateException("Unexpected /logs/json response: '${key}' contains a non-object entry")
    }
    return table
}

// Fetch, keep the page's tables and summary fields, and publish. Runs inline on an unbudgeted request
// and inside runLogsJsonFetch for budgeted ones. A worker passes the fetchId it was scheduled
// with and an inline fetch takes the current one; a fetch whose id was replaced meanwhile (a
// newer worker, or a job delete) parses for nothing and publishes nothing.
def _logsJsonFetchAndPublish(Long fetchId = null, boolean background = false) {
    if (fetchId == null) fetchId = _logsJsonFetchId()
    long t0 = now()
    def responseText = hubInternalGet("/logs/json", null, 30)
    if (!responseText) throw new RuntimeException("No data returned from /logs/json")
    def data = new groovy.json.JsonSlurper().parseText(responseText)
    if (!(data instanceof Map)) throw new IllegalStateException("Unexpected /logs/json response: expected a JSON object")
    if (data.jobs == null && data.deviceStats == null && data.appStats == null) {
        throw new IllegalStateException("Unexpected /logs/json response: no jobs or stats tables (page shape changed?)")
    }
    def snap = [
        at: now(), fetchMs: now() - t0, background: background,
        uptime: data.uptime,
        devicesUptime: data.devicesUptime, totalDevicesRuntime: data.totalDevicesRuntime, devicePct: data.devicePct,
        appsUptime: data.appsUptime, totalAppsRuntime: data.totalAppsRuntime, appPct: data.appPct,
        deviceStats: _logsJsonStats(_logsJsonTable(data, "deviceStats")),
        appStats: _logsJsonStats(_logsJsonTable(data, "appStats")),
        jobs: _logsJsonTable(data, "jobs"),
        runningJobs: _logsJsonTable(data, "runningJobs"),
        hubCommands: _logsJsonTable(data, "hubCommands")
    ]
    synchronized (LOGS_JSON_SNAPSHOT) {
        if (_logsJsonFetchId() != fetchId) return snap
        LOGS_JSON_SNAPSHOT.snapshot = snap
        LOGS_JSON_SNAPSHOT.remove("fetchError")
    }
    return snap
}

// Provenance a client can read back: whether this answer came from a cached snapshot, how old
// it is, and whether the fetch ran in the background worker. Makes cache reuse observable.
private Map _logsJsonProvenance(Map snap) {
    return [fetchedAt: snap.at, ageMs: Math.max(0L, now() - (snap.at as Long)),
            fetchMs: snap.fetchMs, background: snap.background == true,
            budgeted: _logsJsonUsesBackgroundFetch()]
}

private Map _logsJsonFreshSnapshot() {
    synchronized (LOGS_JSON_SNAPSHOT) {
        def snap = LOGS_JSON_SNAPSHOT.snapshot
        if (snap instanceof Map && snap.at instanceof Long
                && now() - (snap.at as Long) < _logsJsonSnapshotTtlMs()) return snap
        return null
    }
}

private long _logsJsonFetchId() {
    synchronized (LOGS_JSON_SNAPSHOT) {
        return (LOGS_JSON_SNAPSHOT.fetchId instanceof Long) ? (LOGS_JSON_SNAPSHOT.fetchId as Long) : 0L
    }
}

// Drop the snapshot and fence off any fetch in flight: advancing fetchId keeps it from
// publishing, and clearing the worker's marker lets the next read schedule a new one at once.
private void _logsJsonInvalidate() {
    synchronized (LOGS_JSON_SNAPSHOT) {
        LOGS_JSON_SNAPSHOT.remove("snapshot")
        LOGS_JSON_SNAPSHOT.remove("fetchStartedAt")
        LOGS_JSON_SNAPSHOT.fetchId = _logsJsonFetchId() + 1L
    }
}

// Schedule the worker unless a non-stale fetch is already in flight. The marker is owned by a
// fetchId; a scheduling failure rolls back exactly that marker and propagates.
private void _logsJsonEnsureFetchScheduled() {
    long fetchId
    synchronized (LOGS_JSON_SNAPSHOT) {
        def startedAt = LOGS_JSON_SNAPSHOT.fetchStartedAt
        if (startedAt instanceof Long && now() - (startedAt as Long) < _logsJsonFetchStaleMs()) return
        fetchId = _logsJsonFetchId() + 1L
        LOGS_JSON_SNAPSHOT.fetchId = fetchId
        LOGS_JSON_SNAPSHOT.fetchStartedAt = now()
    }
    try {
        runInMillis(WORKER_START_DELAY_MS, "runLogsJsonFetch", [overwrite: false, data: [fetchId: fetchId]])
    } catch (Exception scheduleErr) {
        synchronized (LOGS_JSON_SNAPSHOT) {
            if (LOGS_JSON_SNAPSHOT.fetchId == fetchId) LOGS_JSON_SNAPSHOT.remove("fetchStartedAt")
        }
        throw scheduleErr
    }
}

def runLogsJsonFetch(Map job = [:]) {
    Long fetchId = null
    try { fetchId = job?.fetchId as Long } catch (Exception ignored) { fetchId = null }
    try {
        _logsJsonFetchAndPublish(fetchId, true)
    } catch (Exception e) {
        mcpLogError("monitoring", "Background /logs/json fetch failed", e)
        String detail = e.message?.toString()?.trim()
        if (!detail) detail = "${e.class.simpleName} while fetching /logs/json".toString()
        synchronized (LOGS_JSON_SNAPSHOT) {
            if (fetchId == null || LOGS_JSON_SNAPSHOT.fetchId == fetchId) {
                LOGS_JSON_SNAPSHOT.fetchError = [at: now(), message: detail]
            }
        }
    } finally {
        synchronized (LOGS_JSON_SNAPSHOT) {
            if (fetchId == null || LOGS_JSON_SNAPSHOT.fetchId == fetchId) LOGS_JSON_SNAPSHOT.remove("fetchStartedAt")
        }
    }
}

// The most recent worker failure, while it is younger than the stale bound. It stays visible to
// every caller until a later fetch succeeds (publishing clears it) or it ages out, so a legacy
// client repeating the call sees the same error and stops instead of polling.
private Map _logsJsonRecentFetchError() {
    synchronized (LOGS_JSON_SNAPSHOT) {
        def failure = LOGS_JSON_SNAPSHOT.fetchError
        if (!(failure instanceof Map)) return null
        if (!(failure.at instanceof Long) || now() - (failure.at as Long) >= _logsJsonFetchStaleMs()) {
            LOGS_JSON_SNAPSHOT.remove("fetchError")
            return null
        }
        return [:] + failure
    }
}

// Resolve the snapshot for one request. Exactly one of:
//   [state: "ready", snapshot: ...]   a snapshot inside its TTL (fetched inline when unbudgeted)
//   [state: "pending"]                a budgeted request whose worker has not landed yet
//   [state: "failed", error: ...]     the most recent worker failure; a retry is already scheduled
def _logsJsonSnapshot(Map args) {
    def fresh = _logsJsonFreshSnapshot()
    if (fresh != null) return [state: "ready", snapshot: fresh]
    if (!_logsJsonUsesBackgroundFetch()) return [state: "ready", snapshot: _logsJsonFetchAndPublish()]
    def failure = _logsJsonRecentFetchError()
    if (failure != null) {
        _logsJsonEnsureFetchScheduled()
        return [state: "failed", error: failure.message]
    }
    long t0 = (args?.__reqT0 instanceof Number) ? (args.__reqT0 as Long) : now()
    _logsJsonEnsureFetchScheduled()
    long deadline = t0 + _logsJsonObserveWaitMs()
    // remainingBudget bounds the loop even when the clock does not advance between pauses.
    long remainingBudget = Math.max(0L, deadline - now())
    while (true) {
        fresh = _logsJsonFreshSnapshot()
        if (fresh != null) return [state: "ready", snapshot: fresh]
        failure = _logsJsonRecentFetchError()
        if (failure != null) return [state: "failed", error: failure.message]
        // A job delete while this request waits fences off its fetch; start the replacement now.
        _logsJsonEnsureFetchScheduled()
        long remaining = Math.min(remainingBudget, Math.max(0L, deadline - now()))
        if (remaining <= 0L) return [state: "pending"]
        long sleepMs = Math.min(WORKER_POLL_MS, remaining)
        try {
            pauseExecution(sleepMs as Long)
        } catch (Exception waitErr) {
            // Not an ordinary timeout: name the exception so a scheduler fault does not hide
            // behind normal-looking in_progress traffic.
            mcpLog("warn", "monitoring", "Snapshot wait interrupted (${waitErr.class.simpleName}): ${waitErr.message}")
            return [state: "pending"]
        }
        remainingBudget -= sleepMs
    }
}

// The remainder envelope for a budgeted request whose fetch is still running. A modern client
// continues it through requestState; a legacy client repeats the identical call.
private Map _logsJsonInProgress(String tool) {
    return [
        status: "in_progress", tool: tool, retryable: true,
        note: "The hub is still producing its Logs page payload (every device and app stat plus the job tables, which grows with hub size). Call ${tool} again with the same arguments; once the fetch finishes, its result is served from a ${(_logsJsonSnapshotTtlMs() / 1000L) as Long} s cache."
    ]
}

// The runtime-error contract for a Logs-page read that could not be served.
private Map _logsJsonFailure(String tool, String detail) {
    String reason = detail?.toString()?.trim() ?: "unknown /logs/json fetch failure"
    return [
        success: false, isError: true, tool: tool,
        error: "${tool} could not read the hub's Logs page: ${reason}",
        note: "Repeat the identical call once; a fresh fetch is already scheduled. If it fails again, read hub_get_logs(mode='mcp') for the hub-side error."
    ]
}

def toolGetHubPerformance(args) {

    def recordSnapshot = args.recordSnapshot == true
    def trendPoints = Math.min(args.trendPoints ?: 10, 50)

    // Gather current metrics
    def current = [timestamp: formatTimestamp(now()), timestampEpoch: now()]

    try {
        current.freeMemoryKB = _hubNumber(hubInternalGet("/hub/advanced/freeOSMemory"))
        try {
            def memKB = current.freeMemoryKB as Integer
            if (memKB < 50000) current.memoryWarning = "LOW MEMORY: ${memKB}KB free. Consider rebooting the hub."
            else if (memKB < 100000) current.memoryNote = "Memory is moderate: ${memKB}KB free."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.freeMemoryKB = "unavailable" }

    try {
        current.internalTempC = _hubNumber(hubInternalGet("/hub/advanced/internalTempCelsius"))
        try {
            def temp = current.internalTempC as Double
            if (temp > 70) current.temperatureWarning = "HIGH TEMPERATURE: ${temp}°C. Hub may need better ventilation."
            else if (temp > 60) current.temperatureNote = "Temperature is warm: ${temp}°C."
        } catch (Exception nfe) { /* non-numeric */ }
    } catch (Exception e) { current.internalTempC = "unavailable" }

    // The hub reports its database size in MB.
    try {
        current.databaseSizeMB = _hubNumber(hubInternalGet("/hub/advanced/databaseSize"))
        if (current.databaseSizeMB instanceof Number && current.databaseSizeMB > 500) current.databaseWarning = "LARGE DATABASE: ${current.databaseSizeMB} MB. Consider cleaning up old data."
    } catch (Exception e) { current.databaseSizeMB = "unavailable" }

    try { current.uptimeSeconds = location.hub?.uptime } catch (Exception e) { current.uptimeSeconds = "unavailable" }
    if (current.uptimeSeconds && current.uptimeSeconds instanceof Number) {
        def days = (current.uptimeSeconds / 86400).toInteger()
        def hours = ((current.uptimeSeconds % 86400) / 3600).toInteger()
        def mins = ((current.uptimeSeconds % 3600) / 60).toInteger()
        current.uptimeFormatted = "${days}d ${hours}h ${mins}m"
    }

    // CSV history management. The database column holds the hub's MB figure; the reader skips the
    // header line, so a file written under the old databaseSizeKB header reads the same.
    def csvFileName = "mcp-performance-history.csv"
    def csvHeader = "timestamp,freeMemoryKB,internalTempC,databaseSizeMB,uptimeSeconds"
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
        def csvRow = "${now()},${current.freeMemoryKB},${current.internalTempC},${current.databaseSizeMB},${current.uptimeSeconds}"
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
    int skippedRows = 0
    def startIdx = Math.max(0, history.size() - trendPoints)
    for (int i = startIdx; i < history.size(); i++) {
        def parts = history[i].split(",", -1)
        if (parts.size() < 5) skippedRows++
        else {
            try {
                def point = [
                    timestamp: formatTimestamp(parts[0] as Long),
                    timestampEpoch: parts[0] as Long,
                    freeMemoryKB: _hubNumber(parts[1]),
                    internalTempC: _hubNumber(parts[2]),
                    databaseSizeMB: _hubNumber(parts[3]),
                    uptimeSeconds: _hubNumber(parts[4])
                ]
                // A reading that failed when the point was recorded was stored as text ("unavailable").
                if (!(point.freeMemoryKB instanceof Number && point.internalTempC instanceof Number && point.databaseSizeMB instanceof Number && point.uptimeSeconds instanceof Number)) point.incomplete = true
                trends << point
            } catch (Exception e) {
                skippedRows++
            }
        }
    }

    mcpLog("info", "monitoring", "Hub performance snapshot recorded=${recordSnapshot}, trendPoints=${trends.size()}")
    // Trend rows exist only where a call passed recordSnapshot=true; say so when they are missing
    // or more than a day old, so an empty or months-old trend is not read as the hub's history.
    String trendNote = null
    Long newest = trends ? trends[-1].timestampEpoch as Long : null
    if (newest == null && skippedRows > 0) trendNote = "No readable trend points: ${skippedRows} row(s) of ${csvFileName} could not be read. For the hub's own memory history use hub_get_memory_history.".toString()
    else if (newest == null) trendNote = "No trend points: they are recorded only by calls with recordSnapshot=true. For the hub's own memory history use hub_get_memory_history."
    else if (now() - newest > 86400000L) trendNote = "The newest trend point is from ${formatTimestamp(newest)}: points are recorded only by calls with recordSnapshot=true. For the hub's own memory history use hub_get_memory_history.".toString()
    return [
        current: current,
        // The hub's OWN active health alerts (from /hub2/hubData) sit alongside the locally-derived
        // memory/temp/DB warnings on `current` -- e.g. the hub's hubLargeDatabase/hubLowMemory flags
        // complement (and may differ in threshold from) the databaseWarning/memoryWarning above. null
        // when /hub2/hubData is unreadable.
        healthAlerts: _healthAlertsFromHub2(_getHub2HubData()),
        trends: trends,
        trendPointsAvailable: history.size(),
        trendNote: trendNote,
        trendRowsSkipped: skippedRows ?: null,
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
            def directValues = ((allEntries).findAll((this.&_bat529Cb_7399a956_10))).collect((this.&_bat529Cb_7399a956_27))
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

private Map _deviceHealthInventory() {
    boolean bypass = _bypassEnabled()
    def allowedIds = (((settings.selectedDevices ?: []) + (getChildDevices() ?: []))).collect((this.&_bat529Cb_7399a956_31))as Set
    if (!bypass && !allowedIds) {
        return [devices: [], message: "No devices selected for MCP access and no MCP-managed virtual devices"]
    }
    // The Devices page carries activity for the whole tree in one read, including children.
    def text = hubInternalGet("/hub2/devicesList")
    def parsed = new groovy.json.JsonSlurper().parseText(text ?: "{}")
    def records = _flattenHub2DeviceTree(parsed instanceof Map ? parsed.devices : null)
    if (!(records instanceof List)) throw new IllegalStateException("Native device tree is unavailable or malformed")
    def byId = [:]
    (records).each(((this.&_bat529Cb_7399a956_40).curry([allowedIds, byId, bypass] as Object[])))
    // A missing selected/owned device is an unknown result, not proof that it is healthy or absent.
    (allowedIds).each(((this.&_bat529Cb_7399a956_34).curry([byId] as Object[])))
    return [devices: byId.values() as List]
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
    if (args?.tracerouteHost != null) {
        def host = args.tracerouteHost.toString().trim()
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

    def inventory
    try {
        inventory = _deviceHealthInventory()
    } catch (Exception e) {
        // Native response text may contain settings; retain operation and failure class only.
        mcpLog("error", "monitoring", "hub_get_device_health native inventory failed (${e.class.simpleName})")
        inventory = [success: false, error: "Native device inventory could not be read (${e.class.simpleName})."]
    }
    if (inventory?.success == false || !(inventory?.devices instanceof List)) {
        def failedResult = [success: false,
            error: inventory?.error ?: "Native device inventory returned an unexpected response.",
            note: inventory?.note ?: "Retry the native inventory read; device health could not be assessed."]
        if (pingResults != null) failedResult.pingResults = pingResults
        if (tracerouteResult != null) failedResult.traceroute = tracerouteResult
        if (speedtestResult != null) failedResult.speedtest = speedtestResult
        if (identifyHubFields != null) failedResult.putAll(identifyHubFields)
        return failedResult
    }
    def devices = inventory.devices
    if (!devices) {
        def emptyResult = [
            message: inventory.message ?: "No devices available for MCP access",
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

    (devices).each(((this.&_bat529Cb_7399a956_52).curry([healthy, stale, staleThreshold, unknown] as Object[])))

    // Sort stale by most-stale first
    (stale).sort((this.&_bat529Cb_7399a956_21))

    // Only stale is paged; unknown/healthy stay in full so the summary call still resolves in one request.
    def paged = _paginateList(stale, cursor, 100, "hub_get_device_health")

    def result = [
        summary: [
            totalDevices: devices.size(),
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
    (rawHosts).each(((this.&_bat529Cb_7399a956_51).curry([count, results] as Object[])))
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
    def hasStack = args.zwave_js != null
    if (!hasEnabled && !hasConfig && !hasStack) {
        throw new IllegalArgumentException("Specify enabled (true/false), region + long_range_channel, or zwave_js (true/false).")
    }
    if (hasStack && (hasEnabled || hasConfig)) {
        throw new IllegalArgumentException("zwave_js switches the Z-Wave stack and reboots the hub; send it on its own call, without enabled/region/long_range_channel.")
    }

    if (hasStack) {
        if (!(args.zwave_js instanceof Boolean)) throw new IllegalArgumentException("zwave_js must be true (switch to Z-Wave JS) or false (switch back to the legacy stack).")
        requireDestructiveConfirm(args.confirm)
        boolean toJs = (args.zwave_js == true)
        // The running stack decides: the same stack is a no-op (so a repeat never reboots twice),
        // and an unreadable one is refused rather than guessed.
        def details = _radioGetSafe("/hub/zwaveDetails/json")
        if (!(details instanceof Map) || details.error || !(details.zwaveJS instanceof Boolean)) {
            return [success: false, radio: "zwave", error: "Could not read which Z-Wave stack the hub runs${(details instanceof Map && details.error) ? ': ' + details.error : '.'}",
                    note: "Nothing was sent. Check hub_get_radio_details(radio='zwave') and retry."]
        }
        if (details.zwaveJS == toJs) {
            return [success: true, radio: "zwave", zwaveJs: toJs, changed: false, message: "The hub already runs ${toJs ? 'Z-Wave JS' : 'the legacy Z-Wave stack'}; nothing was sent."]
        }
        try {
            def resp = _radioGet(toJs ? "/hub/zwave2/enable" : "/hub/zwave2/disable")
            if (_radioRefused(resp, true)) {
                return [success: false, radio: "zwave", error: "The hub refused the Z-Wave stack switch: ${_radioReason(resp)}",
                        note: "Nothing changed. Z-Wave JS needs a C-5, C-7 or C-8 on firmware 2.5.2 or later; zwaveJSAvailable in hub_get_radio_details(radio='zwave') says whether this hub offers it."]
            }
            mcpLog("warn", "hub-admin", "Z-Wave stack switch to ${toJs ? 'Z-Wave JS' : 'legacy'} requested via MCP; hub reboots")
            return [success: true, radio: "zwave", zwaveJs: toJs, changed: true, rebooting: true,
                    message: "Switching the Z-Wave stack to ${toJs ? 'Z-Wave JS' : 'the legacy stack'}. The hub reboots now.",
                    note: "After the reboot, check hub_get_radio_details(radio='zwave', include_status=true): status.zwaveJs.zwaveJSReady and interviewStatus show when the devices are re-interviewed.",
                    response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Z-Wave stack switch request failed", e)
            if (_httpStatusOf(e) != null) {
                return [success: false, radio: "zwave", error: "The hub refused the Z-Wave stack switch: ${e.message}", note: "Nothing changed."]
            }
            return [success: false, radio: "zwave", outcome: "unknown", error: "The Z-Wave stack switch request got no answer: ${e.message}",
                    note: "The hub may already be rebooting into the new stack. Wait a few minutes, then read status.zwaveJs with hub_get_radio_details(radio='zwave', include_status=true); a repeated call does nothing once the stack has switched."]
        }
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
        // Drop null params -- an absent value must not reach the hub as the string "null"
        // (e.g. region-less hub + region-omitting call).
        def resp = _radioGet("/hub/zwaveDetails/update", (params).findAll((this.&_bat529Cb_7399a956_5)))
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
        _requireDeviceToolAccess(pd.device_id)
        if (!(_fetchDeviceFullJson(pd.device_id)?.device instanceof Map)) {
            return [success: false, isError: true,
                error: "Native device identity is unavailable for ${pd.device_id}; keep-alive ping was not changed.",
                note: "Use the numeric Hubitat device ID and verify the device exists before retrying."]
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
            def resp = _radioGet("/hub/zigbee/updateSettings",
                              [rebuildNetworkOnReboot: rebuild, inactiveDevicePingEnabled: ping])
            mcpLog("info", "hub-admin", "Zigbee radio settings updated via MCP")
            return [success: true, radio: "zigbee", rebuildNetworkOnReboot: rebuild, inactiveDevicePingEnabled: ping,
                    message: "Zigbee radio settings updated.", response: resp]
        } catch (Exception e) {
            mcpLogError("hub-admin", "Zigbee settings update failed", e)
            // Duck-type the HTTP status (e.response.status names HttpResponseException NCDFEs at
            // parse time on the test classpath). A 404 on this path most often means the hub has
            // no Zigbee radio at all, not a credential failure -- steer the note accordingly.
            def resp = null
            try { resp = e.response } catch (Exception ignore) { resp = null }
            Integer st = null
            try { st = resp?.status as Integer } catch (Exception ignore) { st = null }
            def note = (st == 404)
                ? "A 404 here usually means this hub has no active Zigbee radio (absent/disabled). Verify via hub_get_radio_details(radio='zigbee')."
                : "Check Hub Security credentials."
            return [success: false, error: "Zigbee settings update failed: ${e.message}", note: note]
        }
    }

    if (args.channel == null || args.power_level == null) {
        throw new IllegalArgumentException("channel and power_level must be set together.")
    }
    try {
        def resp = _radioGet("/hub/zigbee/updateChannelAndPower",
                             [channel: args.channel, powerLevel: args.power_level])
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
                resp = _radioGetSafe("/hub/zwaveRepair2", [resetStats: false, maxHealth: 10])
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
                resp = _radioGet("/hub/zwaveNodeRepair2", [zwaveNodeId: nodeId])
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
                resp = _radioGet("/hub/zwave2/startAntennaTest", [node: nodeId])
                return [success: true, action: action, nodeId: nodeId, message: "Z-Wave antenna test started.", response: resp]
            case "antenna_test_continue":
                resp = _radioGet("/hub/zwave2/antennaTestContinue")
                return [success: true, action: action, message: "Z-Wave antenna test continued.", response: resp]
            case "reinterview":
                if (!nodeId) throw new IllegalArgumentException("reinterview requires node_id.")
                resp = _radioGet("/hub/zwave2/reinterview", [node: nodeId])
                // The UI checks only the HTTP status here, so only an explicit refusal counts.
                if (resp instanceof Map && (resp.success == false || resp.error)) return [success: false, action: action, nodeId: nodeId, error: "The hub did not start the re-interview: ${_radioReason(resp)}", response: resp]
                return [success: true, action: action, nodeId: nodeId, message: "Z-Wave JS re-interview started for node ${nodeId}.",
                        note: "Watch interviewComplete / interviewStage on the node in hub_get_radio_details(radio='zwave'). Z-Wave JS only.", response: resp]
            case "link_test_start":
                if (!nodeId) throw new IllegalArgumentException("link_test_start requires node_id.")
                def lt = _zwArgObject(args.link_test, "link_test", ["rounds", "interval_ms"])
                int rounds = (lt.rounds != null) ? _zwIntArg(lt.rounds, "link_test.rounds") : 10
                int intervalMs = (lt.interval_ms != null) ? _zwIntArg(lt.interval_ms, "link_test.interval_ms") : 1000
                if (rounds < 1 || intervalMs < 0) throw new IllegalArgumentException("rounds must be at least 1 and interval_ms 0 or more.")
                Integer ltNode = _zwNodeNumber(nodeId)
                // The test switches the device on and off, outside the device allowlist.
                requireDestructiveConfirm(args.confirm)
                resp = _radioPost("/hub/zwave2/linkReliability/start", groovy.json.JsonOutput.toJson([nodeId: ltNode, rounds: rounds, intervalMs: intervalMs]))
                if (_radioRefused(resp)) {
                    return [success: false, action: action, nodeId: nodeId, error: "The hub did not start the link test: ${(resp instanceof Map && resp.status) ? resp.status : _radioReason(resp)}", response: resp]
                }
                return [success: true, action: action, nodeId: nodeId, rounds: rounds, intervalMs: intervalMs,
                        message: "Link reliability test started for node ${nodeId}.",
                        warning: "The test switches the device on and off and may leave it in a different state.",
                        note: "Read results with hub_get_radio_details(radio='zwave', node_id=${nodeId}) (linkReliability). Z-Wave JS only.", response: resp]
            case "link_test_stop":
                if (!nodeId) throw new IllegalArgumentException("link_test_stop requires node_id.")
                resp = _radioPost("/hub/zwave2/linkReliability/abort", groovy.json.JsonOutput.toJson([nodeId: _zwNodeNumber(nodeId)]))
                if (_radioRefused(resp)) return [success: false, action: action, nodeId: nodeId, error: "The hub did not stop the link test: ${(resp instanceof Map && resp.status) ? resp.status : _radioReason(resp)}", response: resp]
                return [success: true, action: action, nodeId: nodeId, message: "Link reliability test stop requested.", response: resp]
            case "cc_command":
                if (!nodeId) throw new IllegalArgumentException("cc_command requires node_id.")
                def cc = _zwArgObject(args.cc, "cc", ["command_class", "method_name", "endpoint", "args"])
                if (cc.command_class == null || !cc.method_name) throw new IllegalArgumentException("cc_command requires cc={command_class (numeric id), method_name, endpoint?, args?}, from the node's commandClasses in hub_get_radio_details(radio='zwave', node_id=N).")
                if (cc.args != null && !(cc.args instanceof List)) throw new IllegalArgumentException("cc.args must be an array of the method's arguments, in order (or a one-element array holding an object for object-style methods).")
                def ccBody = [nodeId: _zwNodeNumber(nodeId), endpoint: (cc.endpoint != null ? _zwIntArg(cc.endpoint, "cc.endpoint") : 0),
                              commandClass: _zwIntArg(cc.command_class, "cc.command_class"), methodName: cc.method_name.toString(), args: (cc.args ?: [])]
                requireDestructiveConfirm(args.confirm)
                resp = _radioPost("/hub/zwave2/ccCommand", groovy.json.JsonOutput.toJson(ccBody))
                if (_radioRefused(resp)) {
                    return [success: false, action: action, nodeId: nodeId, error: "Command failed: ${_radioReason(resp)}", response: resp]
                }
                return [success: true, action: action, nodeId: nodeId, message: "Command ${cc.method_name} sent to node ${nodeId}.", response: resp]
            case "local_backup_create":
                resp = _radioPost("/hub/zwave/localBackup/create")
                if (!(resp instanceof Map) || resp.success != true) {
                    return [success: false, action: action, error: "The hub did not start a Z-Wave backup: ${_radioReason(resp)}",
                            note: "Z-Wave local backup needs Z-Wave JS and the Full Local Backup subscription; status.zwaveLocalBackup in hub_get_radio_details(include_status=true) shows both."]
                }
                return [success: true, action: action, jobId: resp.jobId, message: "Z-Wave network backup started.",
                        note: "Poll hub_get_radio_details(backup_job_id='${resp.jobId}') until stage is DONE, then save it with action='local_backup_download'.", response: resp]
            case "local_backup_download":
                if (!args.job_id) throw new IllegalArgumentException("local_backup_download requires job_id (the jobId from local_backup_create).")
                if (!args.confirm) throw new IllegalArgumentException("local_backup_download writes the network backup (with its security keys) to File Manager: pass confirm=true.")
                return _zwaveBackupDownload(args.job_id.toString())
            case "local_backup_import":
                if (!args.backup_url) throw new IllegalArgumentException("local_backup_import requires backup_url: an http(s) URL to a Hubitat or Z-Wave JS UI backup, archive, or raw NVM file.")
                if (!args.confirm) throw new IllegalArgumentException("local_backup_import fetches backup_url and uploads it to the hub: pass confirm=true.")
                return _zwaveBackupImport(args.backup_url.toString())
            case "local_backup_keys":
                if (!args.import_id) throw new IllegalArgumentException("local_backup_keys requires import_id (the importId from local_backup_import).")
                if (!(args.security_keys instanceof Map)) throw new IllegalArgumentException("local_backup_keys requires security_keys: {S0_Legacy, S2_Unauthenticated, S2_Authenticated, S2_AccessControl} as 32-hex-digit strings, plus optional long_range: {S2_Authenticated, S2_AccessControl}.")
                if (args.security_keys.long_range != null && !(args.security_keys.long_range instanceof Map)) {
                    throw new IllegalArgumentException("security_keys.long_range must be an object: {S2_Authenticated, S2_AccessControl}.")
                }
                def lrKeys = args.security_keys.long_range ?: [:]
                def mainKeys = (args.security_keys).findAll((this.&_bat529Cb_7399a956_16))
                _zwValidateNetworkKeys(mainKeys, ["S0_Legacy", "S2_Unauthenticated", "S2_Authenticated", "S2_AccessControl"], "security_keys")
                _zwValidateNetworkKeys(lrKeys, ["S2_Authenticated", "S2_AccessControl"], "security_keys.long_range")
                def keyBody = [securityKeys: _zwNormalizeKeys(mainKeys), securityKeysLongRange: _zwNormalizeKeys(lrKeys)]
                resp = _radioPost("/hub/zwave/localBackup/securityKeys/${URLEncoder.encode(args.import_id.toString(), 'UTF-8')}", groovy.json.JsonOutput.toJson(keyBody))
                if (!(resp instanceof Map) || resp.success != true) {
                    return [success: false, action: action, error: "The hub did not accept the security keys: ${_radioReason(resp)}"]
                }
                return [success: true, action: action, importId: args.import_id.toString(), report: resp.report,
                        message: "Security keys saved for the imported backup.",
                        note: "Restore it with hub_call_destructive_ops(target='zwave', action='local_backup_restore', import_id=...)."]
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
        def zwJsOnly = action in ["reinterview", "link_test_start", "link_test_stop", "cc_command", "local_backup_create", "local_backup_download", "local_backup_import", "local_backup_keys"]
        def note = "Check Hub Security credentials and that the radio is enabled."
        if (zwJsOnly) {
            def details = _radioGetSafe("/hub/zwaveDetails/json")
            if (details instanceof Map && details.zwaveJS == false) note = "This action needs the Z-Wave JS stack, and this hub runs the legacy stack (hub_set_zwave(zwave_js=true) switches it)."
        }
        return [success: false, action: action, error: "Z-Wave action '${action}' failed: ${e.message}", note: note]
    }
}

// Shared result for the firmware-service and batch flashes: the hub answers {success, message}.
private Map _zwFirmwareStartResult(String action, resp, nodeId) {
    if (_radioRefused(resp, true)) {
        return [success: false, target: "zwave", action: action, error: "The hub did not start the firmware update: ${_radioReason(resp)}",
                note: "Firmware 2.5.2 refuses device firmware updates over Remote Admin, and batch updates need Z-Wave JS.", response: resp]
    }
    return [success: true, target: "zwave", action: action, nodeId: nodeId?.toString(),
            message: "Z-Wave device firmware update started.",
            warning: "Do NOT power-cycle the devices or the hub during the flash; interruption can brick a device.",
            note: "Poll hub_get_radio_details(include_firmware=true, node_id=${nodeId}) (firmware.node.progress) or include_status=true (status.zwaveFirmwareBatch) for a batch.",
            response: resp]
}

private Integer _zwNodeNumber(String nodeId) {
    if (!(nodeId ==~ /\d+/)) throw new IllegalArgumentException("node_id must be a decimal Z-Wave node number, got '${nodeId}'.")
    return nodeId as Integer
}

// Whole-number argument, decimal or 0x hex (command classes are usually written in hex).
private Integer _zwIntArg(v, String name) {
    def s = v?.toString()?.trim()
    try {
        if (s ==~ /\d{1,9}/) return s as Integer
        if (s ==~ /(?i)0x[0-9a-f]{1,7}/) return Integer.parseInt(s.substring(2), 16)
    } catch (NumberFormatException ignored) { }
    throw new IllegalArgumentException("${name} must be a whole number of 0 or more (decimal or 0x hex), got '${v}'.")
}

// A nested argument object with a closed set of keys; a misspelt key is refused, not ignored.
private Map _zwArgObject(v, String name, List known) {
    if (v == null) return [:]
    if (!(v instanceof Map)) throw new IllegalArgumentException("${name} must be an object with ${known.join(', ')}.")
    def unknown = (v.keySet()).findAll(((this.&_bat529Cb_7399a956_6).curry([known] as Object[])))
    if (unknown) throw new IllegalArgumentException("Unknown ${name} field(s): ${unknown.join(', ')}. Valid: ${known.join(', ')}.")
    return v
}

private void _zwValidateNetworkKeys(Map keys, List known, String label) {
    def unknown = (keys.keySet()).findAll(((this.&_bat529Cb_7399a956_7).curry([known] as Object[])))
    if (unknown) throw new IllegalArgumentException("Unknown ${label} key(s): ${unknown.join(', ')}. Valid: ${known.join(', ')}.")
    (keys).each(((this.&_bat529Cb_7399a956_39).curry([label] as Object[])))
}

// A radio write the hub did not confirm. strict: only success:true counts (how the UI reads the
// firmware writes). Otherwise an explicit failure, an error, or an answer that is not JSON.
private boolean _radioRefused(resp, boolean strict = false) {
    if (strict) return !(resp instanceof Map) || resp.success != true
    if (resp instanceof Map) return resp.success == false || resp.error || resp._unparseable
    return resp != null
}

// The pair answer: {nodeId, error} on 2.5.2 or a bare node id (also the only shape before 2.5.2),
// as the UI reads it; node "0" or none means the hub did not start pairing.
private Map _matterPairAnswer(resp, String action, String wifiSsid) {
    def node = (resp instanceof Map) ? resp.nodeId : resp
    String nodeId = node?.toString()?.trim()
    def err = (resp instanceof Map) ? resp.error : null
    if (!(nodeId ==~ /[0-9A-Za-z_-]{1,40}/) || nodeId == "0" || err) {
        return [success: false, action: action, error: "Matter pairing did not start: ${err ?: 'the hub returned no node'}",
                note: "Check the setup code and that the device is in pairing mode. A Wi-Fi device also needs the right wifi_ssid / wifi_password.", response: resp]
    }
    def out = [success: true, action: action, nodeId: nodeId, message: "Matter commissioning started (node ${nodeId}).",
               note: "Poll hub_get_radio_details(radio='matter', node_id='${nodeId}') for progress; stop it with action='cancel_pair'.", response: resp]
    if (wifiSsid != null) out.wifiSsid = wifiSsid
    return out
}

private String _radioReason(resp) {
    if (resp == null) return "the hub returned nothing"
    if (resp instanceof Map) return (resp.message ?: resp.error ?: "no reason given").toString()
    return resp.toString().take(200)
}

// Saves a finished Z-Wave local backup to File Manager (the UI only offers it as a browser download).
private Map _zwaveBackupDownload(String jobId) {
    String name = "zwave-backup-${jobId.replaceAll(/[^A-Za-z0-9_.-]/, '_')}.tar.gz"
    try {
        def got = hubInternalBytes("GET", "/hub/zwave/localBackup/download/${URLEncoder.encode(jobId, 'UTF-8')}")
        byte[] bytes = got.bytes
        if (!bytes || bytes.length == 0) {
            return [success: false, action: "local_backup_download", error: "The hub returned no backup data for job ${jobId}${got.error ? ': ' + got.error : '.'}",
                    note: "The job must have finished (stage DONE in hub_get_radio_details(backup_job_id=...))."]
        }
        if (!_isGzip(bytes)) {
            return [success: false, action: "local_backup_download", error: "The hub did not return a backup archive for job ${jobId}: ${_bytesPreview(bytes)}",
                    note: "Nothing was saved. The job must have finished (stage DONE in hub_get_radio_details(backup_job_id=...))."]
        }
        uploadHubFile(name, bytes)
        return [success: true, action: "local_backup_download", jobId: jobId, fileName: name, sizeBytes: bytes.length,
                message: "Z-Wave backup saved to File Manager as ${name}.",
                note: "Download it from http://<HUB_IP>/local/${name} and keep a copy off the hub."]
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "Z-Wave backup download failed", e)
        return [success: false, action: "local_backup_download", error: "Could not save the Z-Wave backup: ${e.message}"]
    }
}

// Uploads a Z-Wave backup from a URL for inspection; the hub answers with an importId whose job
// reaches READY with a report (which keys it still needs) before a restore.
private Map _zwaveBackupImport(String url) {
    _requireBackupUrl(url, "backup_url")
    def got = _fetchCappedBackup(url, 8L * 1024 * 1024)
    if (got.error) {
        return [success: false, action: "local_backup_import", error: "backup_url: ${got.error}",
                note: "Import it from Settings > Z-Wave Details > Z-Wave local backup in the Hubitat web UI. Nothing was imported."]
    }
    byte[] bytes = got.bytes
    def segs = url.replaceFirst(/(?i)^https?:\/\/[^\/]*/, "").replaceFirst(/[?#].*$/, "").tokenize("/")
    String fileName = (segs && segs[-1].contains(".")) ? segs[-1] : "zwave-backup.tar.gz"
    try {
        def up = _postMultipartBackup("/hub/zwave/localBackup/upload", "uploadFile", fileName, bytes)
        if (!(up instanceof Map) || up.success != true) {
            return [success: false, action: "local_backup_import", error: "The hub rejected the upload: ${(up instanceof Map) ? (up.message ?: 'no reason given') : up}"]
        }
        return [success: true, action: "local_backup_import", importId: up.importId,
                message: "Backup uploaded for inspection.",
                note: "Poll hub_get_radio_details(backup_job_id='${up.importId}') until stage is READY and read its report. Supply any missing keys with action='local_backup_keys', then restore with hub_call_destructive_ops(target='zwave', action='local_backup_restore', import_id='${up.importId}')."]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Z-Wave backup import failed", e)
        return [success: false, action: "local_backup_import", error: "Upload failed: ${e.message}"]
    }
}

// The UI accepts keys with a 0x prefix or stray whitespace and sends bare hex.
private Map _zwNormalizeKeys(Map keys) {
    return (keys).collectEntries((this.&_bat529Cb_7399a956_35))
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

// hub_call_matter: non-idempotent Matter operations (enable/disable, pair a device by setup
// code with the network credentials firmware 2.5.2 requires, cancel a pairing, open a
// pairing/share window for a commissioned node).
def toolCallMatter(args) {
    def action = args.action?.toString()
    if (!action) throw new IllegalArgumentException("action is required: enable, disable, pair, cancel_pair, or open_pairing_window.")
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
                if (!args.setup_code) throw new IllegalArgumentException("pair requires setup_code (the 11- or 21-digit Matter setup code, or the MT: QR payload).")
                if (args.wifi_password != null && args.wifi_ssid == null) throw new IllegalArgumentException("wifi_password needs wifi_ssid.")
                if (_hubFirmwareBefore("2.5.2")) {
                    // Older firmware has no network-credentials pairing: the setup code alone.
                    resp = _radioGet("/hub/matter/pair", [setupCode: args.setup_code.toString().trim()])
                    def legacy = _matterPairAnswer(resp, action, null)
                    if (legacy.success && args.wifi_ssid != null) legacy.note += " Firmware before 2.5.2 pairs with the setup code alone; wifi_ssid and wifi_password were not sent."
                    return legacy
                }
                // The 2.5.2 UI always sends Wi-Fi credentials; Thread devices ignore them. Like the UI,
                // default to the hub's selected network, with the password placeholder (the hub reads
                // it as "use the stored password") only when that network is the stored one.
                String ssid = args.wifi_ssid?.toString()
                String password = args.wifi_password?.toString()
                if (password == null) {
                    def creds = _radioGetSafe("/hub/matter/wifiCredentials")
                    if (!(creds instanceof Map) || creds.error) {
                        return [success: false, action: action, error: "Could not read the hub's Wi-Fi network for pairing: ${(creds instanceof Map) ? creds.error : creds}",
                                note: "Retry, or pass wifi_ssid and wifi_password. Nothing was paired."]
                    }
                    if (ssid == null) ssid = (creds.selectedSsid ?: creds.storedSsid ?: "").toString()
                    if (ssid && !(creds.hasStoredPassword == true && ssid == creds.storedSsid)) {
                        throw new IllegalArgumentException("pair on network '${ssid}' needs wifi_password: the hub stores a password only for '${creds.storedSsid ?: 'no network'}'. Pass wifi_ssid='${ssid}' with wifi_password (\"\" for an open network).")
                    }
                    password = ssid ? (creds.passwordPlaceholder ?: "") : ""
                }
                resp = _radioPost("/hub/matter/pairWithNetworkCredentials",
                    groovy.json.JsonOutput.toJson([setupCode: args.setup_code.toString().trim(), ssid: ssid ?: "", password: password ?: ""]))
                return _matterPairAnswer(resp, action, ssid ?: null)
            case "cancel_pair":
                if (!args.node_id) throw new IllegalArgumentException("cancel_pair requires node_id (the nodeId pair returned).")
                resp = _radioGet("/hub/matter/cancelPair", [nodeId: args.node_id.toString()])
                if (_radioRefused(resp)) return [success: false, action: action, nodeId: args.node_id.toString(), error: "The hub did not cancel the pairing: ${_radioReason(resp)}", response: resp]
                return [success: true, action: action, nodeId: args.node_id.toString(), message: "Matter pairing cancel requested.", response: resp]
            case "open_pairing_window":
                if (!args.node_id) throw new IllegalArgumentException("open_pairing_window requires node_id (the commissioned Matter node to share).")
                resp = _radioGet("/hub/matter/openPairingWindow", [node: args.node_id.toString()])
                return [success: true, action: action, nodeId: args.node_id?.toString(),
                        message: "Matter pairing/share window opened.",
                        note: "The response carries the setup code for sharing this device to another fabric.", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}'. Valid: enable, disable, pair, cancel_pair, open_pairing_window.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_matter action '${action}' failed", e)
        return [success: false, action: action, error: "Matter action '${action}' failed: ${e.message}", note: "Matter requires a C-8/C-8 Pro on supported firmware."]
    }
}

// hub_call_destructive_ops: the single confirm-gated destructive-operations tool. Covers radio
// network/fabric wipes + firmware flashes (target=zwave|zigbee|matter), network disconnects
// (target=network), and cloud-controller disable/enable (target=cloud). Misfire-proof: explicit target
// + explicit action, no defaults, confirm=true required for every path.
def toolCallDestructiveOps(args) {
    requireDestructiveConfirm(args.confirm)
    def target = args.target?.toString()
    def action = args.action?.toString()
    if (!target) throw new IllegalArgumentException("target is required: zwave, zigbee, matter, network, or cloud.")
    if (!action) throw new IllegalArgumentException("action is required (depends on target): radio reset/firmware actions, network disconnect_wifi/disconnect_ethernet, or cloud disable/enable.")

    // Network + cloud targets are not radio ops -- route them to their own handlers (still confirm-gated above).
    if (target == "network") return _destructiveNetworkOp(action)
    if (target == "cloud") return _destructiveCloudOp(action)

    // --- Radio targets (zwave | zigbee | matter) ---
    def radio = target
    try {
        def resp
        // --- Network/fabric wipe (unpairs all devices) ---
        if (action == "reset") {
            def path
            switch (radio) {
                case "zwave": path = "/hub/zwave/resetJson"; break
                case "zigbee": path = "/hub/zigbee/reset"; break
                case "matter": path = "/hub/matter/reset"; break
                default: throw new IllegalArgumentException("target must be zwave, zigbee, or matter for reset.")
            }
            resp = _radioGet(path)
            mcpLog("warn", "hub-admin", "DESTRUCTIVE: ${radio} radio reset via MCP")
            return [success: true, target: radio, action: action,
                    message: "${radio} radio/fabric reset. ALL ${radio} devices have been unpaired.",
                    warning: "This is irreversible. Every ${radio} device must be re-paired.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp), response: resp]
        }

        // --- Firmware flash (can brick hardware) ---
        switch (action) {
            case "device_firmware_start":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_start is Z-Wave only.")
                if (args.node_id == null || !args.file_name) throw new IllegalArgumentException("device_firmware_start requires node_id and file_name (from hub_get_radio_details(include_firmware=true)).")
                def startBody = [nodeId: args.node_id, target: (args.target_index != null ? args.target_index : 0), fileName: args.file_name.toString()]
                resp = _radioPost("/hub/zwave/deviceFirmware/start", groovy.json.JsonOutput.toJson(startBody))
                return _zwFirmwareStartResult(action, resp, args.node_id)
            case "device_firmware_abort":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_abort is Z-Wave only.")
                if (args.node_id == null) throw new IllegalArgumentException("device_firmware_abort requires node_id.")
                resp = _radioPost("/hub/zwave/deviceFirmware/abort", groovy.json.JsonOutput.toJson([nodeId: args.node_id]))
                return [success: true, target: radio, action: action, message: "Z-Wave device firmware update aborted for node ${args.node_id}.", response: resp]
            case "device_firmware_start_available":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_start_available is Z-Wave only.")
                if (args.node_id == null || args.update_id == null) throw new IllegalArgumentException("device_firmware_start_available requires node_id and update_id (from hub_get_radio_details(include_firmware=true, node_id=N) firmware.node.available).")
                resp = _radioPost("/hub/zwave/deviceFirmware/startAvailable",
                    groovy.json.JsonOutput.toJson([nodeId: _zwNodeNumber(args.node_id.toString()), updateId: args.update_id]))
                return _zwFirmwareStartResult(action, resp, args.node_id)
            case "device_firmware_batch_start":
            case "device_firmware_batch_start_available":
                if (radio != "zwave") throw new IllegalArgumentException("${action} is Z-Wave only.")
                def batch = _zwArgObject(args.batch, "batch", ["node_ids", "inactivity_timeout_seconds"])
                if (args.node_id == null || !(batch.node_ids instanceof List) || batch.node_ids.isEmpty()) {
                    throw new IllegalArgumentException("${action} requires node_id (the source node) and batch.node_ids (the nodes to update, from firmware.node.batchCandidates in hub_get_radio_details).")
                }
                boolean fromService = (action == "device_firmware_batch_start_available")
                if (fromService && args.update_id == null) throw new IllegalArgumentException("device_firmware_batch_start_available requires update_id.")
                if (!fromService && !args.file_name) throw new IllegalArgumentException("device_firmware_batch_start requires file_name (a firmware file from hub_get_radio_details(include_firmware=true)).")
                def batchBody = [sourceNodeId: _zwNodeNumber(args.node_id.toString()),
                                 nodeIds: (batch.node_ids).collect((this.&_bat529Cb_7399a956_14)),
                                 inactivityTimeoutSeconds: (batch.inactivity_timeout_seconds != null ? _zwIntArg(batch.inactivity_timeout_seconds, "batch.inactivity_timeout_seconds") : 600)]
                String batchPath
                if (fromService) {
                    batchBody.updateId = args.update_id
                    batchPath = "/hub/zwave/deviceFirmware/startAvailableBatch"
                } else {
                    batchBody.target = (args.target_index != null ? args.target_index : 0)
                    batchBody.fileName = args.file_name.toString()
                    batchPath = "/hub/zwave/deviceFirmware/startBatch"
                }
                resp = _radioPost(batchPath, groovy.json.JsonOutput.toJson(batchBody))
                return _zwFirmwareStartResult(action, resp, args.node_id)
            case "device_firmware_batch_abort":
                if (radio != "zwave") throw new IllegalArgumentException("device_firmware_batch_abort is Z-Wave only.")
                resp = _radioPost("/hub/zwave/deviceFirmware/abortBatch", "{}")
                if (_radioRefused(resp, true)) {
                    return [success: false, target: radio, action: action, error: "Abort failed: ${_radioReason(resp)}", response: resp]
                }
                return [success: true, target: radio, action: action, message: "Batch Z-Wave firmware update abort requested.", response: resp]
            case "local_backup_restore":
                if (radio != "zwave") throw new IllegalArgumentException("local_backup_restore is Z-Wave only.")
                if (!args.import_id) throw new IllegalArgumentException("local_backup_restore requires import_id (from hub_call_zwave(action='local_backup_import')).")
                resp = _radioPost("/hub/zwave/localBackup/restore/${URLEncoder.encode(args.import_id.toString(), 'UTF-8')}", groovy.json.JsonOutput.toJson([confirmation: "RESTORE"]))
                if (!(resp instanceof Map) || resp.success != true) {
                    return [success: false, target: radio, action: action, error: "The hub did not start the restore: ${_radioReason(resp)}",
                            note: "The import must be READY with every security key supplied (hub_get_radio_details(backup_job_id=...))."]
                }
                mcpLog("warn", "hub-admin", "DESTRUCTIVE: Z-Wave network restore from import ${args.import_id} via MCP")
                return [success: true, target: radio, action: action, jobId: resp.jobId,
                        message: "Z-Wave network restore started. It replaces the hub's Z-Wave network with the imported one.",
                        warning: "Do not power off or reboot the hub during the restore. Z-Wave devices are unavailable while Z-Wave JS restarts.",
                        note: "Poll hub_get_radio_details(backup_job_id='${resp.jobId}') until stage is DONE.", response: resp]
            case "zwave_chip_firmware":
                if (radio != "zwave") throw new IllegalArgumentException("zwave_chip_firmware is Z-Wave only.")
                resp = _radioGet("/hub/zwave/startUpdateHubFirmware")
                return [success: true, target: radio, action: action,
                        message: "Z-Wave chip (hub radio) firmware update started.",
                        warning: "Do NOT power-cycle the hub during the flash; interruption can brick the radio.", response: resp]
            case "zigbee_firmware":
                if (radio != "zigbee") throw new IllegalArgumentException("zigbee_firmware is Zigbee only.")
                resp = _radioGet("/hub/zigbee/updateFirmware/latest")
                return [success: true, target: radio, action: action,
                        message: "Zigbee radio firmware update to latest started.",
                        warning: "Do NOT power-cycle the hub during the flash; interruption can brick the radio.", response: resp]
            default:
                throw new IllegalArgumentException("Unknown action '${action}' for target '${radio}'. Valid: reset, device_firmware_start, device_firmware_start_available, device_firmware_batch_start, device_firmware_batch_start_available, device_firmware_batch_abort, device_firmware_abort, local_backup_restore, zwave_chip_firmware, zigbee_firmware.")
        }
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_destructive_ops ${radio}/${action} failed", e)
        return [success: false, target: radio, action: action, error: "Destructive radio op '${action}' on ${radio} failed: ${e.message}", note: "Check Hub Security credentials."]
    }
}

// target=network: disconnect the hub's WiFi or Ethernet link. GET endpoints RE'd from
// resources/hub2-source/vue-hub2.min.js. Confirm-gated by the caller; a structured error on failure.
private _destructiveNetworkOp(String action) {
    def path
    switch (action) {
        case "disconnect_wifi": path = "/hub/advanced/disconnectWiFi"; break
        case "disconnect_ethernet": path = "/hub/advanced/disconnectEthernet"; break
        default: throw new IllegalArgumentException("Unknown action '${action}' for target 'network'. Valid: disconnect_wifi, disconnect_ethernet.")
    }
    try {
        // Fire-and-return GET: success == the GET returned 2xx. There is NO response-body inspection
        // or state read-back to confirm the link actually dropped, so the result reports the command
        // was accepted, not a verified disconnected state.
        def resp = _radioGet(path)
        mcpLog("warn", "hub-admin", "DESTRUCTIVE: hub ${action} via MCP")
        return [success: true, target: "network", action: action,
                message: "Hub ${action == 'disconnect_wifi' ? 'WiFi' : 'Ethernet'} disconnect command accepted.",
                warning: "The hub may become unreachable over the disconnected interface until it is reconnected.",
                note: "Command accepted (HTTP 2xx); there is no read-back to confirm the link dropped.",
                response: resp]
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_destructive_ops network/${action} failed", e)
        return [success: false, target: "network", action: action, error: "Network op '${action}' failed: ${e.message}", note: "The hub may already be unreachable over this interface. Check Hub Security credentials."]
    }
}

// target=cloud: disable or enable the hub's cloud controller. Disabling severs Alexa/Google, cloud
// dashboards, firmware updates, and subscription features. GET endpoints RE'd from vue-hub2.min.js.
private _destructiveCloudOp(String action) {
    def path
    switch (action) {
        case "disable": path = "/hub/advanced/disableCloudController"; break
        case "enable": path = "/hub/advanced/enableCloudController"; break
        default: throw new IllegalArgumentException("Unknown action '${action}' for target 'cloud'. Valid: disable, enable.")
    }
    try {
        // Fire-and-return GET: success == the GET returned 2xx. There is NO response-body inspection
        // or state read-back to confirm the controller actually flipped, so the result reports the
        // command was accepted, not a verified enabled/disabled state.
        def resp = _radioGet(path)
        mcpLog("warn", "hub-admin", "DESTRUCTIVE: cloud controller ${action} via MCP")
        def disabling = (action == "disable")
        return [success: true, target: "cloud", action: action,
                message: "Hub cloud controller ${disabling ? 'disable' : 'enable'} command accepted.",
                warning: disabling
                    ? "Cloud features are expected to go OFF: Alexa/Google voice integrations, cloud dashboards, cloud firmware updates, and Hub Protect/subscription features will not work until re-enabled."
                    : "Cloud features are being re-enabled; allow a short time for cloud services to reconnect.",
                note: "Command accepted (HTTP 2xx); there is no read-back to confirm the controller's new state.",
                response: resp]
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_call_destructive_ops cloud/${action} failed", e)
        return [success: false, target: "cloud", action: action, error: "Cloud op '${action}' failed: ${e.message}", note: "Check Hub Security credentials."]
    }
}

// Get the user-configured max captured states limit (default: 20, minimum: 1)
def getMaxCapturedStates() {
    def max = settings.maxCapturedStates ?: 20
    // Ensure minimum of 1 to prevent infinite loops in cleanup logic
    return max < 1 ? 1 : (max > 100 ? 100 : max)
}

private Map _captureStore() {
    String owner = app?.id?.toString()
    if (!owner) throw new IllegalStateException("Capture storage requires an installed app ID")
    synchronized (CAPTURE_STORES) {
        if (!CAPTURE_STORES.containsKey(owner)) {
            CAPTURE_STORES.put(owner, [entries: [:], loaded: false])
        }
        return CAPTURE_STORES[owner]
    }
}

private void _resetCaptureStore() {
    if (!app?.id) return
    synchronized (CAPTURE_STORES) { CAPTURE_STORES.remove(app.id.toString()) }
}

private Map _captureEntriesLocked(Map store) {
    if (!store.loaded) {
        // Import once per class lifetime; stale execution-local state must never
        // resurrect a snapshot deleted from the shared memory store.
        Map legacy = [:]
        if (state.capturedDeviceStates instanceof Map) legacy.putAll(state.capturedDeviceStates)
        if (atomicState.capturedDeviceStates instanceof Map) legacy.putAll(atomicState.capturedDeviceStates)
        Map entries = [:]
        (legacy).each(((this.&_bat529Cb_7399a956_47).curry([entries] as Object[])))
        store.entries = entries
        store.loaded = true
    }
    // Retry removal after a failed state write without importing stale values again.
    if (atomicState.containsKey("capturedDeviceStates")) atomicState.remove("capturedDeviceStates")
    if (state.containsKey("capturedDeviceStates")) state.remove("capturedDeviceStates")
    return store.entries
}

def countCapturedStates() {
    if (!app?.id) return 0
    Map store = _captureStore()
    synchronized (store) { return _captureEntriesLocked(store).size() }
}

def saveCapturedState(stateId, capturedStates) {
    String id = stateId?.toString()
    if (id == null || (!(capturedStates instanceof Map) && !(capturedStates instanceof List))) {
        throw new IllegalArgumentException("A state ID and device map or list are required")
    }
    // JSON detaches caller-owned maps; readers receive their own parsed copy too.
    String text = groovy.json.JsonOutput.toJson(capturedStates)
    Map store = _captureStore()
    synchronized (store) {
        Map entries = _captureEntriesLocked(store)
        entries.put(id, [text: text, timestamp: now(), deviceCount: capturedStates.size()])
        List deleted = []
        int max = getMaxCapturedStates()
        while (entries.size() > max) {
            def oldest = ((entries).findAll(((this.&_bat529Cb_7399a956_8).curry([id] as Object[])))).min((this.&_bat529Cb_7399a956_26))
            deleted << oldest.key
            entries.remove(oldest.key)
        }
        return [stateId: stateId, deviceCount: capturedStates.size(), totalStored: entries.size(),
                maxLimit: max, deletedStates: deleted, nearLimit: entries.size() >= max - 4]
    }
}

def getCapturedState(stateId) {
    Map store = _captureStore()
    String text
    synchronized (store) { text = _captureEntriesLocked(store)[stateId?.toString()]?.text }
    return text == null ? null : new groovy.json.JsonSlurper().parseText(text)
}

def listCapturedStates() {
    Map store = _captureStore()
    synchronized (store) {
        return ((_captureEntriesLocked(store).entrySet().toList()).sort((this.&_bat529Cb_7399a956_36))).collect((this.&_bat529Cb_7399a956_43))
    }
}

def deleteCapturedState(stateId) {
    Map store = _captureStore()
    synchronized (store) {
        Map entries = _captureEntriesLocked(store)
        String id = stateId?.toString()
        if (!entries) return [success: false, message: "No captured states exist"]
        if (!entries.containsKey(id)) return [success: false, message: "Captured state '${stateId}' not found"]
        entries.remove(id)
        return [success: true, message: "Captured state '${stateId}' deleted", remaining: entries.size()]
    }
}

def clearAllCapturedStates() {
    Map store = _captureStore()
    synchronized (store) {
        Map entries = _captureEntriesLocked(store)
        int count = entries.size()
        entries.clear()
        return [success: true, message: "Cleared ${count} captured state(s)", cleared: count]
    }
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
    // The list builds in a closure so its strings live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit.
    def defs = { [
        [
            name: "hub_get_logs",
            description: """Read tabs of the hub's Logs page, one or a list, or the MCP app's own log. The stats and job tabs are cached for 30 s.""",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [anyOf: [[type: "string", enum: _logsModeArgs().keySet() as List], [type: "array", items: [type: "string", enum: _logsModeArgs().keySet() as List]]], default: "past", description: "A Logs page tab, or a list of them. past = log history; devices/apps = runtime stats; locationEvents = mode, HSM and variable changes; hubEvents = reboots and low memory; mcp = this app's own log; status = its level."],
                    level: [type: "string", enum: ["trace", "debug", "info", "warn", "error", "all"], description: "past and mcp."],
                    deviceId: [type: "string", description: "Numeric device id to scope past logs, stats or jobs to."],
                    appId: [type: "string", description: "Numeric app id, the same way."],
                    pattern: [type: "string", description: "Case-insensitive regex over the row's name, and its message, methodName, descriptionText or value where it has one."],
                    patterns: [type: "array", items: [type: "string"], description: "More regexes, combined by patternMode."],
                    patternMode: [type: "string", enum: ["any", "all"], description: "Default any."],
                    since: [type: ["string", "integer"], description: "past and events: rows after this time, so a returned time never repeats. '30m', '2h', '7d', a time as rows show it (no zone = hub time), ISO-8601 with a zone, or epoch ms."],
                    until: [type: ["string", "integer"], description: "Rows at or before this time."],
                    sortBy: [type: "string", enum: ["pct", "count", "total", "stateSize", "name"], description: "devices and apps; default pct (% busy)."],
                    limit: [type: "integer", description: "Max rows; 0 = all."],
                    component: [type: "string", description: "mcp: component substring."],
                    ruleId: [type: "string", description: "mcp: custom rule id."],
                    cursor: [type: "string", description: "\"\" for the first page, then nextCursor."],
                    pageSize: [type: "integer", description: "Rows per page, 1-100."]
                ]
            ]
        ],
        [
            name: "hub_delete_logs",
            description: """Clear the MCP app's own log (mode='mcp'), or delete one scheduled job like the Logs page's Delete button (mode='scheduledJobs'). Only that timer goes; its app or device schedules it again only if its own code does. Cannot be undone. The hub's past logs have no delete; its Reboot dialog offers a log purge: hub_reboot(confirm=true, purgeLogs=true).""",
            inputSchema: [
                type: "object",
                properties: [
                    mode: [type: "string", enum: ["mcp", "scheduledJobs"]],
                    jobId: [type: "string", description: "Job id from hub_get_logs(mode='scheduledJobs')."],
                    confirm: [type: "boolean", description: "Must be true to delete a job."]
                ],
                required: ["mode"]
            ]
        ],
        [
            name: "hub_get_metrics",
            description: "Retrieve hub metrics (memory, temp, DB size) with CSV trend history. Trend history is sparse/stale[[FLAT_TRIM]] — the hub never auto-samples, so points exist only from earlier recordSnapshot=true calls[[/FLAT_TRIM]].[[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    recordSnapshot: [type: "boolean", description: "If true, also append this snapshot to the performance-history CSV in the hub File Manager — the tool's only write side-effect. Default: false (read-only).", default: false],
                    trendPoints: [type: "integer", description: "Number of recent historical data points to include. Default: 10, max: 50.", default: 10]
                ]
            ]
        ],
        [
            name: "hub_get_memory_history",
            description: "Get the hub's free-memory and CPU-load history (the platform's own timestamped ring buffer[[FLAT_TRIM]], each entry with freeMemoryKB and cpuLoad5min[[/FLAT_TRIM]]). Use to diagnose memory leaks or load trends over time.[[FLAT_TRIM]] For a single current snapshot plus temp/DB-size, use hub_get_metrics instead.[[/FLAT_TRIM]][[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    limit: [type: "integer", description: "Max entries to return (most recent); 0 for all.[[FLAT_TRIM]] Hub may have thousands of entries.[[/FLAT_TRIM]]", default: 100],
                    cursor: [type: "string", description: "Opt-in pagination cursor.[[FLAT_TRIM]] Pages within the limit-filtered entries. Pass \"\" for the first page, iterate nextCursor (page size 100).[[/FLAT_TRIM]]"]
                ]
            ]
        ],
        [
            name: "hub_get_device_health",
            description: "Hub network diagnostics + device-staleness checks. Checks selected devices and MCP-managed children, or all hub devices when allowlist bypass is enabled, for no activity in staleHours.",
            inputSchema: [
                type: "object",
                properties: [
                    staleHours: [type: "integer", description: "Flag devices with no activity in this many hours. Default: 24.", default: 24],
                    includeHealthy: [type: "boolean", description: "Include healthy devices in the response (can be large). Default: false.", default: false],
                    pingHosts: [type: "array", items: [type: "string"], description: "Optional IPv4 addresses to ICMP-ping (max 5 per call)."],
                    pingCount: [type: "integer", description: "Packets to send per host (1-5). Default: 3.", default: 3],
                    tracerouteHost: [type: "string", description: "Optional single IPv4 dotted-quad host (e.g. '8.8.8.8') to traceroute; plain-text route table returned under traceroute.output."],
                    speedtest: [type: "boolean", description: "If true, run the hub's WAN download speedtest; plain-text wget log with the measured speed returned under speedtest.output. Default: false."],
                    identifyHub: [type: "boolean", description: "Blink hub LED to identify hub. Default: false.", default: false],
                    cursor: [type: "string", description: "Opt-in pagination cursor for the staleDevices array.[[FLAT_TRIM]] Omit to get all stale devices in one response (subject to the universal response-size guard). Pass nextCursor from a prior call to fetch the next page (page size 100). unknownDevices and healthyDevices are always returned in full alongside the page.[[/FLAT_TRIM]]"]
                ]
            ]
        ],
        [
            name: "hub_get_radio_details",
            description: """Get Z-Wave/Zigbee/Matter radio info and the read-only radio surface. The include_* flags and node_id attach extra read blocks.[[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    radio: [type: "string", enum: ["zwave", "zigbee", "matter"], description: "Which radio to query. Omit for both Z-Wave and Zigbee; 'matter' for the Matter fabric.[[FLAT_TRIM]] With radio='matter' it also returns the commissioned-device list and the Wi-Fi network the hub gives Matter devices when pairing (never the password).[[/FLAT_TRIM]]"],
                    include_topology: [type: "boolean", description: "Also include the mesh route/topology map. Z-Wave/Zigbee only. Default false."],
                    node_id: [type: "string", description: "Per-node status for this id (Z-Wave, or Matter commissioning with radio='matter').[[FLAT_TRIM]] On Z-Wave JS it adds the interview details and link-test status.[[/FLAT_TRIM]]"],
                    include_status: [type: "boolean", description: "Attach lifecycle status pollers under result.status. Default false.[[FLAT_TRIM]] Repair, join, exclude, antenna test, node replace, Zigbee, Z-Wave JS readiness, Z-Wave local backup, batch firmware.[[/FLAT_TRIM]]"],
                    include_logs: [type: "boolean", description: "Matter chip-tool logs under result.matterLogs.[[FLAT_TRIM]] Default false.[[/FLAT_TRIM]]"],
                    include_channel_scan: [type: "boolean", description: "Zigbee channel-scan results under result.channelScan.[[FLAT_TRIM]] Default false.[[/FLAT_TRIM]]"],
                    include_smartstart: [type: "boolean", description: "Z-Wave SmartStart list under result.smartStart.[[FLAT_TRIM]] Default false.[[/FLAT_TRIM]]"],
                    include_firmware: [type: "boolean", description: "Attach firmware-eligible Z-Wave devices + files under result.firmware. Default false.[[FLAT_TRIM]] With node_id also that node's targets, offered updates (updateId), progress, and batch candidates.[[/FLAT_TRIM]]"],
                    include_devices: [type: "boolean", description: "[[FLAT_TRIM]]Attach Zigbee devices with their last-message time under result.zigbeeDevices. Default false.[[/FLAT_TRIM]]"],
                    backup_job_id: [type: "string", description: "[[FLAT_TRIM]]Z-Wave backup/import/restore job id to read under result.zwaveBackupJob.[[/FLAT_TRIM]]"]
                ]
            ]
        ],
        [
            name: "hub_call_gc",
            description: "Force JVM garbage collection to reclaim memory. Non-destructive but may cause a brief pause.[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "hub_set_zwave",
            description: "Configure the Z-Wave radio[[FLAT_TRIM]]: enable/disable it, set region and long-range channel, or switch between the legacy and Z-Wave JS stacks (reboots the hub)[[/FLAT_TRIM]]. Read current values with hub_get_radio_details(radio='zwave').[[FLAT_TRIM]] Requires Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    enabled: [type: "boolean", description: "Enable (true) or disable (false) the Z-Wave radio."],
                    region: [type: "string", description: "Z-Wave RF region (e.g. 'US', 'EU'). Must match a region your hub hardware supports."],
                    long_range_channel: [type: "integer", enum: [0, 1, 255], description: "Z-Wave Long Range channel: 255=Auto, 0=Channel A, 1=Channel B (US_LR hubs)."],
                    zwave_js: [type: "boolean", description: "true=Z-Wave JS, false=legacy stack. REBOOTS the hub.[[FLAT_TRIM]] Send alone with confirm=true.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "Required true to DISABLE the radio or switch the stack (backup <24h also enforced)."]
                ]
            ]
        ],
        [
            name: "hub_set_zigbee",
            description: "Configure the Zigbee radio (idempotent)[[FLAT_TRIM]]: enable/disable, channel + power, radio settings (rebuild-on-reboot / inactive-device ping), or per-device keep-alive ping[[/FLAT_TRIM]]. One operation per call. Read current values with hub_get_radio_details(radio='zigbee').[[FLAT_TRIM]] Requires Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    enabled: [type: "boolean", description: "Enable (true) or disable (false) the Zigbee radio. Disable requires confirm=true."],
                    channel: [description: "Zigbee channel (typically 11-26). Set together with power_level."],
                    power_level: [description: "Zigbee transmit power level (hub-dependent dBm scale). Set together with channel."],
                    rebuild_on_reboot: [type: "boolean", description: "Radio setting: rebuild the Zigbee network on each hub reboot."],
                    ping_inactive: [type: "boolean", description: "Radio setting: keep-alive ping inactive Zigbee devices."],
                    ping_device: [type: "object", description: "Toggle keep-alive ping for one authorized device: {device_id: numeric Hubitat device ID, enabled}. Allowlist bypass permits unselected devices."],
                    confirm: [type: "boolean", description: "Required true to DISABLE the radio (backup <24h also enforced). Not needed for the other changes."]
                ]
            ]
        ],
        [
            name: "hub_call_zwave",
            description: "Z-Wave network lifecycle operations (NOT idempotent)[[FLAT_TRIM]]: repair, device inclusion (join + S2 grants), exclusion, per-node maintenance, node replace/remove, antenna test, SmartStart delete; on Z-Wave JS also re-interview, link reliability test, raw command-class commands, and Z-Wave network backup/import[[/FLAT_TRIM]]. Pick the operation with action.[[FLAT_TRIM]] Requires Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["repair_start", "repair_cancel", "repair_node", "inclusion_start", "inclusion_stop", "grant_keys", "grant_code", "exclusion_start", "exclusion_stop", "node_refresh", "node_rediscover", "node_reinitialize", "refresh_stats", "node_replace", "node_replace_stop", "node_remove", "antenna_test_start", "antenna_test_continue", "smartstart_delete", "reinterview", "link_test_start", "link_test_stop", "cc_command", "local_backup_create", "local_backup_download", "local_backup_import", "local_backup_keys"], description: "The Z-Wave operation.[[FLAT_TRIM]] reinterview, link_test_*, cc_command and local_backup_* need the Z-Wave JS stack.[[/FLAT_TRIM]]"],
                    node_id: [type: "string", description: "Z-Wave node id for the per-node actions."],
                    security_keys: [type: "object", description: "grant_keys: S2 grant booleans; local_backup_keys: the network keys as hex.[[FLAT_TRIM]] grant_keys e.g. {S2Authenticated:true}; local_backup_keys {S0_Legacy, S2_Unauthenticated, S2_Authenticated, S2_AccessControl, long_range: {S2_Authenticated, S2_AccessControl}}.[[/FLAT_TRIM]]"],
                    security_code: [type: "object", description: "grant_code only: S2 DSK, e.g. {accept:true, securityCode:'12345'}."],
                    node_dsk: [type: "string", description: "smartstart_delete only: the DSK from hub_get_radio_details(include_smartstart=true)."],
                    backup_url: [type: "string", description: "[[FLAT_TRIM]]local_backup_import: http(s) URL of the backup (8 MB max).[[/FLAT_TRIM]]"],
                    link_test: [type: "object", description: "[[FLAT_TRIM]]link_test_start (confirm=true): {rounds? (10), interval_ms? (1000)}.[[/FLAT_TRIM]]"],
                    cc: [type: "object", description: "[[FLAT_TRIM]]cc_command: {command_class (decimal or 0x hex), method_name, endpoint?, args?}.[[/FLAT_TRIM]]"],
                    job_id: [type: "string", description: "[[FLAT_TRIM]]local_backup_download: the jobId from local_backup_create.[[/FLAT_TRIM]]"],
                    import_id: [type: "string", description: "[[FLAT_TRIM]]local_backup_keys: the importId from local_backup_import.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "Required true for exclusion_start, node_remove, link_test_start, cc_command, local_backup_download, local_backup_import.[[FLAT_TRIM]] A hub backup <24h is also enforced.[[/FLAT_TRIM]]"]
                ],
                required: ["action"]
            ]
        ],
        [
            name: "hub_call_zigbee",
            description: "Zigbee radio operations (NOT idempotent); select with action.[[FLAT_TRIM]] Requires Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["radio_reboot", "rebuild_network", "channel_scan"], description: "radio_reboot (restart the Zigbee chip), rebuild_network (rebuild the mesh), or channel_scan (trigger an energy scan)."],
                ],
                required: ["action"]
            ]
        ],
        [
            name: "hub_call_matter",
            description: "Matter radio operations (NOT idempotent); select with action.[[FLAT_TRIM]] Requires Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", enum: ["enable", "disable", "pair", "cancel_pair", "open_pairing_window"], description: "The Matter operation.[[FLAT_TRIM]] enable/disable (needs a hub reboot), pair by setup_code, cancel_pair, open_pairing_window to share a commissioned node.[[/FLAT_TRIM]]"],
                    setup_code: [type: "string", description: "pair only: the 11- or 21-digit Matter setup code, or the MT: QR payload."],
                    wifi_ssid: [type: "string", description: "[[FLAT_TRIM]]pair: Wi-Fi SSID (default: the hub's selected network). Thread devices ignore it; the stored network is in hub_get_radio_details(radio='matter') wifiCredentials.[[/FLAT_TRIM]]"],
                    wifi_password: [type: "string", description: "[[FLAT_TRIM]]pair: Wi-Fi password; omit only for the network the hub stores a password for.[[/FLAT_TRIM]]"],
                    node_id: [type: "string", description: "Node id (open_pairing_window, cancel_pair)."],
                    confirm: [type: "boolean", description: "Required true to disable Matter (backup <24h also enforced)."]
                ],
                required: ["action"]
            ]
        ],
        [
            name: "hub_call_destructive_ops",
            description: """⚠️ DESTRUCTIVE hub ops by `target` + `action` (no defaults — both required): radio WIPE/FIRMWARE, network DISCONNECT, or cloud DISABLE.

PRE-FLIGHT: 1) Backup <24h old 2) Tell the user what is affected (irreversible / can brick / disconnects) 3) Get explicit confirmation 4) Set confirm=true.
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    target: [type: "string", enum: ["zwave", "zigbee", "matter", "network", "cloud"], description: "REQUIRED: what to act on."],
                    action: [type: "string", enum: ["reset", "device_firmware_start", "device_firmware_start_available", "device_firmware_batch_start", "device_firmware_batch_start_available", "device_firmware_batch_abort", "device_firmware_abort", "local_backup_restore", "zwave_chip_firmware", "zigbee_firmware", "disconnect_wifi", "disconnect_ethernet", "disable", "enable"], description: "REQUIRED: depends on target."],
                    node_id: [description: "Z-Wave node id."],
                    file_name: [type: "string", description: "Firmware file name (device_firmware_start, batch_start)."],
                    target_index: [description: "Optional Z-Wave firmware target index."],
                    update_id: [description: "[[FLAT_TRIM]]*_start_available: updateId from firmware.node.available.[[/FLAT_TRIM]]"],
                    batch: [type: "object", description: "[[FLAT_TRIM]]Batch actions: {node_ids, inactivity_timeout_seconds? (600)}.[[/FLAT_TRIM]]"],
                    import_id: [type: "string", description: "[[FLAT_TRIM]]local_backup_restore: importId.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true.[[FLAT_TRIM]] Confirms backup was created and the user approved this destructive op.[[/FLAT_TRIM]]"]
                ],
                required: ["target", "action", "confirm"]
            ]
        ],
        // Captured State Management
        [
            name: "hub_list_captured_states",
            description: "List temporary device-state snapshots used by the legacy custom rule engine. Captures are held only in memory and are lost on hub restart or app code reload.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor.[[FLAT_TRIM]] Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50).[[/FLAT_TRIM]]"]
                ]
            ]
        ],
        [
            name: "hub_delete_captured_state",
            description: "Delete a saved device-state snapshot by its stateId, OR delete ALL captured states when stateId is omitted (get stateIds from hub_list_captured_states). Cannot be undone; use the delete-all mode with caution.",
            inputSchema: [
                type: "object",
                properties: [
                    stateId: [type: "string", description: "The ID of the captured state to delete. Omit to delete ALL captured states."],
                ]
            ]
        ],
    ] }
    return defs()
}

def _readOnlyToolNames_partDiagnostics() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Captured states (read)
        "hub_list_captured_states",
        // Diagnostics + logs (read)
        "hub_get_logs", "hub_get_memory_history", "hub_get_radio_details",
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
        // Diagnostics + logs
        "hub_delete_captured_state", "hub_delete_logs",
        // Radio config is a state assignment: identical args land the radio in the same state. The
        // zwave_js switch reads the running stack first and does nothing when it already matches.
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
        "hub_get_device_health",
        // local_backup_import fetches backup_url from any http(s) host.
        "hub_call_zwave"
    ]
}

def _toolDisplayMeta_partDiagnostics() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Diagnostics + logs
        hub_get_logs: [title: "Get Logs", summary: "Any Logs page tab (log history, stats, cloud calls, events, jobs, hub actions) or the MCP log."],
        hub_delete_logs: [title: "Delete Logs", summary: "Clear the MCP log or delete one scheduled job, as the Logs page's Delete button does."],
        hub_get_metrics: [title: "Get Hub Metrics", summary: "Hub metrics with CSV trend history."],
        hub_get_memory_history: [title: "Get Memory History", summary: "Free-memory and CPU-load history with summary stats."],
        hub_call_gc: [title: "Force Garbage Collection", summary: "Force JVM garbage collection and report freed memory."],
        hub_get_device_health: [title: "Get Device Health", summary: "Find stale devices, ICMP-ping LAN hosts, run traceroute/WAN speedtest, and optionally blink the hub identify LED."],
        hub_get_radio_details: [title: "Get Radio Details", summary: "Radio details, topology, node state, status, channel scan, SmartStart, firmware, Zigbee activity, Z-Wave backup jobs."],
        hub_set_zwave: [title: "Set Z-Wave Radio", summary: "Enable/disable the Z-Wave radio, set region and long-range channel, or switch to or from Z-Wave JS."],
        hub_set_zigbee: [title: "Set Zigbee Radio", summary: "Enable/disable the radio, set channel/power, radio settings (rebuild-on-reboot, ping-inactive), or per-device keep-alive ping."],
        hub_call_zwave: [title: "Z-Wave Operations", summary: "Z-Wave repair, inclusion, exclusion, node maintenance, link tests, CC commands, and Z-Wave network backup."],
        hub_call_zigbee: [title: "Zigbee Operations", summary: "Reboot the Zigbee radio, rebuild the network, or trigger a channel scan."],
        hub_call_matter: [title: "Matter Operations", summary: "Enable/disable Matter, pair or cancel pairing a device by setup code, or open a pairing window."],
        hub_call_destructive_ops: [title: "Destructive Hub Ops", summary: "Reset a radio, flash firmware, disconnect WiFi/Ethernet, or disable the cloud controller (irreversible)."],
        hub_list_captured_states: [title: "List Captured States", summary: "List saved device state snapshots."],
        hub_delete_captured_state: [title: "Delete Captured State", summary: "Delete one or all captured device state snapshots."]
    ]
}


def _bat529Cb_7399a956_1(def it) { it.value.at 
}

def _bat529Cb_7399a956_2(def it) { !(it instanceof Map) 
}

def _bat529Cb_7399a956_3(def p, def it) { p.matcher(it).find() 
}

def _bat529Cb_7399a956_4(def it) { !(it instanceof Map) 
}

def _bat529Cb_7399a956_5(def k, def v) { v != null 
}

def _bat529Cb_7399a956_5(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_5(*__bat529Tuple)
}

def _bat529Cb_7399a956_6(def known, def it) { !(it in known) 
}

def _bat529Cb_7399a956_7(def known, def it) { !(it in known) 
}

def _bat529Cb_7399a956_8(def id, def key, def row) { key != id 
}

def _bat529Cb_7399a956_8(def id, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_8(id, *__bat529Tuple)
}

def _bat529Cb_7399a956_9(def it) { it instanceof Map 
}

def _bat529Cb_7399a956_10(def it) { it.directJavaKB != null 
}

def _bat529Cb_7399a956_11(Map row) { _logsRowTime(row) 
}

def _bat529Cb_7399a956_12(def query, def it) { -((it[query.sortBy] ?: 0) as double) 
}

def _bat529Cb_7399a956_13(def it) { it.id.toString() 
}

def _bat529Cb_7399a956_14(def it) { _zwNodeNumber(it.toString()) 
}

def _bat529Cb_7399a956_15(def it) { it.message = it.message?.take(200) 
}

def _bat529Cb_7399a956_16(def k, def v) { k != "long_range" 
}

def _bat529Cb_7399a956_16(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_16(*__bat529Tuple)
}

def _bat529Cb_7399a956_17(def it) { (it.name ?: "").toString().toLowerCase() 
}

def _bat529Cb_7399a956_18(def it) { _isBenignRmInternalNoise(it.message) 
}

def _bat529Cb_7399a956_19(def row, def it) { row[it] == null ? "" : row[it].toString() 
}

def _bat529Cb_7399a956_20(def it) { it.id.toString() 
}

def _bat529Cb_7399a956_21(def a, def b) { (b.hoursAgo ?: 0) <=> (a.hoursAgo ?: 0) 
}

def _bat529Cb_7399a956_21(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_21(*__bat529Tuple)
}

def _bat529Cb_7399a956_22(def k, def v) { !k.toString().startsWith("formatted") 
}

def _bat529Cb_7399a956_22(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_22(*__bat529Tuple)
}

def _bat529Cb_7399a956_23(def it) { -(it.epoch as long) 
}

def _bat529Cb_7399a956_24(def it) { it != null 
}

def _bat529Cb_7399a956_25(def it) { it instanceof Map 
}

def _bat529Cb_7399a956_26(def it) { it.value.timestamp ?: 0 
}

def _bat529Cb_7399a956_27(def it) { it.directJavaKB 
}

def _bat529Cb_7399a956_28(def it) { (it.message?.length() ?: 0) + (it.name?.length() ?: 0) + 120 
}

def _bat529Cb_7399a956_29(def compile, def list, def p, int i) { list << compile("patterns[${i}]", p) 
}

def _bat529Cb_7399a956_29(def compile, def list, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_29(compile, list, *__bat529Tuple)
}

def _bat529Cb_7399a956_30(def it) { [hourStart: formatTimestamp(it.epoch as Long), count: it.count] 
}

def _bat529Cb_7399a956_31(def it) { it.id.toString() 
}

def _bat529Cb_7399a956_32(def stat) { (stat).findAll((this.&_bat529Cb_7399a956_22))
}

def _bat529Cb_7399a956_33(def nullFields, def k, def v) {
            if (v == null) nullFields << k.toString()
            v != null
        
}

def _bat529Cb_7399a956_33(def nullFields, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_33(nullFields, *__bat529Tuple)
}

def _bat529Cb_7399a956_34(def byId, def id) {
        if (!byId.containsKey(id)) byId.put(id, [id: id, metadataUnavailable: true])
    
}

def _bat529Cb_7399a956_35(def k, def v) { [(k.toString()): (v == null ? "" : v.toString().trim().replaceFirst(/(?i)^0x/, ""))] 
}

def _bat529Cb_7399a956_35(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_35(*__bat529Tuple)
}

def _bat529Cb_7399a956_36(def a, def b) {
            (b.value.timestamp ?: 0) <=> (a.value.timestamp ?: 0)
        
}

def _bat529Cb_7399a956_36(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_36(*__bat529Tuple)
}

def _bat529Cb_7399a956_37(def k, def v) {
                v.pending != true && v.replayProtected != true && ((v.readers ?: 0) as Integer) == 0
            
}

def _bat529Cb_7399a956_37(List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_37(*__bat529Tuple)
}

def _bat529Cb_7399a956_38(def nullFields, Map row) {
        (row).findAll(((this.&_bat529Cb_7399a956_33).curry([nullFields] as Object[])))
    
}

def _bat529Cb_7399a956_39(def label, def k, def v) {
        if (!(v?.toString()?.trim() ==~ /(?i)(0x)?[0-9a-f]{32}/)) throw new IllegalArgumentException("${label}.${k} must be a 32-hex-digit network key.")
    
}

def _bat529Cb_7399a956_39(def label, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_39(label, *__bat529Tuple)
}

def _bat529Cb_7399a956_40(def allowedIds, def byId, def bypass, def record) {
        String id = record.id.toString()
        if (bypass || allowedIds.contains(id)) {
            byId.put(id, [id: id, label: record.label, lastActivity: record.lastActivity,
                metadataUnavailable: !record.containsKey('lastActivity')])
        }
    
}

def _bat529Cb_7399a956_41(def hoursByApp, def a) {
        List series = ((hoursByApp.get(a.id?.toString()) ?: [])).sort((this.&_bat529Cb_7399a956_23))
        a + [hours: (series.take(48)).collect((this.&_bat529Cb_7399a956_30))]
    
}

def _bat529Cb_7399a956_42(def hoursByApp, def h) {
        if (!(h instanceof Map) || h.appId == null || h.hourStart == null) return
        String key = h.appId.toString()
        if (!hoursByApp.containsKey(key)) hoursByApp.put(key, [])
        hoursByApp.get(key) << [epoch: h.hourStart as Long, count: h.count]
    
}

def _bat529Cb_7399a956_43(def item) {
            [stateId: item.key, deviceCount: item.value.deviceCount, timestamp: item.value.timestamp,
             capturedAt: formatTimestamp(item.value.timestamp)]
        
}

def _bat529Cb_7399a956_44(def it) { -((it.total ?: 0) as long) 
}

def _bat529Cb_7399a956_45(def nowMs, def d) {
        Long last = (d.lastMessage instanceof Number) ? (d.lastMessage as Long) : null
        [id: d.id, name: d.name, zigbeeId: d.zigbeeId,
         lastMessage: last != null ? formatTimestamp(last) : null,
         minutesSinceLastMessage: last != null ? (long) ((nowMs - last) / 60000L) : null]
    
}

def _bat529Cb_7399a956_46(def it) { it.minutesSinceLastMessage == null ? Long.MIN_VALUE : -(it.minutesSinceLastMessage as long) 
}

def _bat529Cb_7399a956_47(def entries, def id, def raw) {
            def devices = raw instanceof Map && raw.containsKey("devices") ? raw.devices : raw
            if (!(devices instanceof Map) && !(devices instanceof List)) {
                throw new IllegalStateException("Legacy capture has an invalid device payload")
            }
            entries.put(id.toString(), [text: groovy.json.JsonOutput.toJson(devices),
                timestamp: raw instanceof Map ? raw.timestamp : null, deviceCount: devices.size()])
        
}

def _bat529Cb_7399a956_47(def entries, List __bat529Tuple) {
    if (__bat529Tuple == null || __bat529Tuple.size() != 2)
        throw new MissingMethodException('doCall', groovy.lang.Closure, (__bat529Tuple == null ? [null] : __bat529Tuple).toArray())
    return _bat529Cb_7399a956_47(entries, *__bat529Tuple)
}

def _bat529Cb_7399a956_48(def entry) {
            Map value = entry.value as Map
            // Health permits serial 30s traceroute + 90s speedtest + inventory/ping work.
            // Retention outlasts those HTTP deadlines; it is not a worker cancellation timer.
            long pendingTtl = value.work?.tool == "hub_get_device_health" ? 240000L : 90000L
            long ttl = value.pending == true ? pendingTtl : 30000L
            // Readers protect normal TTL expiry. A leaked counter must not reserve
            // one of the eight slots forever: recover after ten minutes in this
            // phase. Worker completion resets at, preserving the full replay TTL;
            // fetchId fencing prevents an expired worker publishing into a new slot.
            long age = now() - (value.at as Long)
            age >= 600000L || (age >= ttl && ((value.readers ?: 0) as Integer) == 0)
        
}

def _bat529Cb_7399a956_49(def it) { it.key 
}

def _bat529Cb_7399a956_50(def it) { NATIVE_LOG_SNAPSHOTS.remove(it) 
}

def _bat529Cb_7399a956_51(def count, def results, def rawHost) {
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

def _bat529Cb_7399a956_52(def healthy, def stale, def staleThreshold, def unknown, def device) {
        try {
            def deviceLabel = device.label ?: device.name ?: "Device ${device.id}"
            def entry = [
                id: device.id.toString(),
                name: deviceLabel
            ]
            if (device.metadataUnavailable == true) {
                entry.lastActivity = "unavailable"
                entry.hoursAgo = null
                entry.metadataUnavailable = true
                unknown << entry
                return
            }

            def lastActivity = device.lastActivity != null ? _parseSinceArg(device.lastActivity) : null
            if (device.lastActivity != null && lastActivity == null) {
                mcpLog("error", "monitoring", "hub_get_device_health could not parse native lastActivity for device ${device.id}")
                entry.lastActivity = "unavailable"
                entry.hoursAgo = null
                entry.metadataUnavailable = true
                unknown << entry
                return
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
            // shows up in hub_get_logs MCP mode / hub_report_issue; surface errorClass on the
            // entry so an LLM triaging the result can distinguish transient (NPE) from
            // systemic (MissingMethodException) without re-running.
            mcpLog("warn", "monitoring", "hub_get_device_health failed to inspect device ${device?.id}: ${e.class.simpleName}: ${e.message}")
            unknown << [id: device.id?.toString() ?: "unknown", name: "Error: ${e.message}", lastActivity: "error", errorClass: e.class.simpleName]
        }
    
}
