library(name: "McpVirtualDevicesLib", namespace: "mcp", author: "kingpanther13", description: "MCP-managed virtual device tool implementations (hub_manage_virtual_device + the hub_list_devices filter=virtual listing) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolManageVirtualDevice(args) {
    def action = args.action
    if (!action) {
        throw new IllegalArgumentException("action is required. Use 'create' or 'delete'.")
    }
    switch (action) {
        case "create":
            // Treat blank-after-trim deviceType as absent (Groovy truthiness treats "" as false but "  " as true).
            def deviceTypeRaw = args.deviceType
            def deviceTypeTrimmed = (deviceTypeRaw instanceof String) ? deviceTypeRaw.trim() : (deviceTypeRaw ? deviceTypeRaw.toString().trim() : null)
            def hasDeviceType = deviceTypeTrimmed as boolean
            def hasCustomDriver = args.customDriver != null
            if (hasDeviceType && hasCustomDriver) {
                throw new IllegalArgumentException("deviceType and customDriver are mutually exclusive. Provide ONE: deviceType for built-in virtual drivers, or customDriver={namespace, name} for user-installed drivers.")
            }
            // Blank-after-trim deviceType with customDriver present: reject with mutex error rather than silently routing.
            if (!hasDeviceType && deviceTypeRaw != null && hasCustomDriver) {
                throw new IllegalArgumentException("deviceType and customDriver are mutually exclusive. Provide ONE: deviceType for built-in virtual drivers, or customDriver={namespace, name} for user-installed drivers.")
            }
            if (!hasDeviceType && !hasCustomDriver) {
                throw new IllegalArgumentException("Either deviceType or customDriver is required for action='create'. Supported deviceType values: ${getSupportedVirtualDeviceTypes().join(', ')}. For user-installed drivers use customDriver={namespace, name}.")
            }
            if (!args.deviceLabel) throw new IllegalArgumentException("deviceLabel is required for action='create'.")
            return toolCreateVirtualDevice(args)
        case "delete":
            if (!args.deviceNetworkId) throw new IllegalArgumentException("deviceNetworkId is required for action='delete'. Use hub_list_devices(filter='virtual') to find the DNI.")
            return toolDeleteVirtualDevice(args)
        default:
            throw new IllegalArgumentException("Unknown action '${action}'. Use 'create' or 'delete'.")
    }
}

// Single source of truth: the tool-schema enum, the missing-arg error,
// and the create-time validator must all agree on this list.
def getSupportedVirtualDeviceTypes() {
    [
        "Virtual Switch", "Virtual Button", "Virtual Contact Sensor",
        "Virtual Motion Sensor", "Virtual Presence", "Virtual Lock",
        "Virtual Temperature Sensor", "Virtual Humidity Sensor", "Virtual Dimmer",
        "Virtual RGBW Light", "Virtual Shade", "Virtual Garage Door Opener",
        "Virtual Water Sensor", "Virtual Omni Sensor", "Virtual Fan Controller"
    ]
}

private Map resolveDriverSpec(args) {
    if (args.customDriver != null) {
        def cd = args.customDriver
        if (!(cd instanceof Map)) {
            throw new IllegalArgumentException("customDriver must be an object with 'namespace' and 'name' fields.")
        }
        // Coerce to String then trim so both numeric values (123) and whitespace-only strings ("  ")
        // are rejected cleanly rather than producing an opaque hub error downstream.
        def nsRaw   = cd.namespace
        def nameRaw = cd.name
        def nsTrimmed   = nsRaw   != null ? nsRaw.toString().trim()   : null
        def nameTrimmed = nameRaw != null ? nameRaw.toString().trim() : null
        if (!nsTrimmed || !nameTrimmed) {
            throw new IllegalArgumentException("customDriver requires both 'namespace' and 'name' fields. Both must be non-empty strings.")
        }
        return [namespace: nsTrimmed, typeName: nameTrimmed, displayType: "${nsTrimmed}:${nameTrimmed}"]
    } else {
        def deviceType = args.deviceType
        if (!deviceType) throw new IllegalArgumentException("deviceType is required")  // defensive; dispatch already validated
        def supportedTypes = getSupportedVirtualDeviceTypes()
        if (!supportedTypes.contains(deviceType)) {
            throw new IllegalArgumentException("Unsupported device type: '${deviceType}'. Supported types: ${supportedTypes.join(', ')}")
        }
        return [namespace: "hubitat", typeName: deviceType, displayType: deviceType]
    }
}

def toolCreateVirtualDevice(args) {
    requireDestructiveConfirm(args.confirm)

    def deviceLabel = args.deviceLabel
    def dni = args.deviceNetworkId

    if (!deviceLabel) throw new IllegalArgumentException("deviceLabel is required")  // defensive; dispatch already validated

    def spec = resolveDriverSpec(args)
    def namespace   = spec.namespace
    def typeName    = spec.typeName
    def displayType = spec.displayType

    if (args.customDriver != null) {
        mcpLog("info", "device", "Creating virtual device with custom driver: namespace='${namespace}', name='${typeName}', label='${deviceLabel}', dni='${dni ?: "(auto-generate)"}'")
    } else {
        mcpLog("info", "device", "Creating virtual device: type='${typeName}', label='${deviceLabel}', dni='${dni ?: "(auto-generate)"}'")
    }

    // Child lifecycle identities establish ownership; device metadata is read through native HTTP.
    def childDevs = getChildDevices() ?: []

    // Auto-generate DNI if not provided, with uniqueness retry
    if (!dni) {
        def existingDnis = childDevs.collect { it.deviceNetworkId } as Set
        def attempts = 0
        while (attempts < 5) {
            def timestamp = Long.toString(now(), 16).toUpperCase()
            def rand = Integer.toString(new Random().nextInt(0xFFFF), 16).toUpperCase().padLeft(4, '0')
            dni = "mcp-virtual-${timestamp}-${rand}"
            if (!existingDnis.contains(dni)) break
            attempts++
            pauseExecution(1) // ensure different now() on retry
        }
    }

    // Validate DNI uniqueness against existing child devices
    def existingChild = childDevs.find { it.deviceNetworkId == dni }
    if (existingChild) {
        def nativeIdentity = _fetchDeviceFullJson(existingChild.id)?.device
        def existingLabel = nativeIdentity instanceof Map ? (nativeIdentity.label ?: nativeIdentity.name) : null
        throw new IllegalArgumentException("A device with network ID '${dni}' already exists: '${existingLabel ?: 'MCP-managed virtual device'}' (ID: ${existingChild.id})")
        // Retained SDK duplicate identity read for deliberate rollback.
        // throw new IllegalArgumentException("A device with network ID '${dni}' already exists: '${existingChild.label ?: existingChild.name}' (ID: ${existingChild.id})")
    }

    def newDevice = null
    // Exception-class distinction: customDriver throws IllegalArgumentException because the bad driver spec is caller-supplied
    // (recoverable by fixing args); built-in throws RuntimeException because the hub firmware not including a built-in driver
    // is a platform condition, not a caller error.
    try {
        newDevice = addChildDevice(namespace, typeName, dni, null, [
            name: typeName,
            label: deviceLabel,
            isComponent: false
        ])
    } catch (Exception e) {
        mcpLog("error", "device", "Failed to create virtual device '${deviceLabel}' (${displayType}): ${e.class.simpleName}: ${e.message}")
        if (args.customDriver != null) {
            // Custom driver path: always surface the actionable hint regardless of how the hub phrased the error.
            throw new IllegalArgumentException("Failed to create virtual device with custom driver '${namespace}:${typeName}'. If the driver is not installed, use hub_list_drivers to verify the namespace and name match. (Hub reported: ${e.message})", e)
        }
        if (e.message?.contains("UnknownDeviceTypeException") || e.message?.contains("not found")) {
            throw new RuntimeException("Driver '${typeName}' not found on this hub. The hub firmware may not include this built-in driver -- verify deviceType is one of the supported values (see hub_get_tool_guide) and check the Hubitat docs for built-in virtual driver availability on your firmware version. (Hub reported: ${e.message})", e)
        }
        if (e.message?.contains("already exists") || e.message?.contains("unique")) {
            throw new RuntimeException("A device with network ID '${dni}' already exists or conflicts with an existing device. (Hub reported: ${e.message})", e)
        }
        throw new RuntimeException("Failed to create virtual device: ${e.message}", e)
    }

    if (!newDevice) {
        throw new RuntimeException("Failed to create virtual device -- addChildDevice returned null. A device with DNI '${dni}' may have been partially registered. Check hub_list_devices(filter='virtual') and Hubitat UI before retrying with the same DNI.")
    }

    def deviceId = newDevice.id.toString()
    def warnings = []
    def dataChanges = []
    def dataErrors = []
    _applyNativeDeviceDataValues(deviceId, [mcpDriverNamespace: namespace], dataChanges, dataErrors)
    dataErrors.each { failure ->
        def warning = "mcpDriverNamespace: ${failure.error}".toString()
        warnings << warning
        mcpLog("warn", "device", "Device ${deviceId} was created, but ${warning}")
    }

    // The child already exists. Keep its identity even when the follow-up native read fails.
    def deviceInfo = [id: deviceId, deviceNetworkId: dni, driverNamespace: namespace,
        driverType: typeName, typeName: typeName]
    try {
        def fullJson = _fetchDeviceFullJson(deviceId)
        if (!(fullJson?.device instanceof Map) || !fullJson.device) {
            warnings << "Native device information could not be read from /device/fullJson/${deviceId}.".toString()
        } else {
            def summary = _getDeviceFromFullJson(deviceId, fullJson)
            deviceInfo.putAll([name: summary.name, label: summary.label,
                deviceNetworkId: fullJson.device.deviceNetworkId ?: dni,
                capabilities: summary.capabilities, commands: summary.commands.collect { it.name },
                attributes: summary.attributes.findAll { it.value != null }.collect { [name: it.name, value: it.value] }])
        }
    } catch (Exception ignored) {
        warnings << "Native device information could not be interpreted from /device/fullJson/${deviceId}.".toString()
    }

    mcpLog("info", "device", "Virtual device created: '${deviceLabel}' (ID: ${deviceId}, DNI: ${dni})")
    def result = [
        success: true,
        message: "Virtual device '${deviceLabel}' created successfully. It is now accessible via all MCP device tools (hub_call_device_command, hub_get_device, etc.) without needing to be added to the device selection list. It also appears in the Hubitat device list and can be shared with other apps like Maker API.",
        device: deviceInfo,
        tips: [
            "Use hub_call_device_command with deviceId '${deviceId}' to control this device",
            "The device is visible in Hubitat web UI under Devices for sharing with other apps",
            "To add it to Maker API: open Maker API app settings and select this device"
        ]
    ]
    if (warnings) {
        result.partialSuccess = true
        result.warnings = warnings
        result.message = "Virtual device '${deviceLabel}' was created, but its namespace persistence or native information could not be fully verified."
        result.note = "Inspect the existing device ID ${deviceId} (DNI '${dni}') with hub_get_device or the Hubitat UI. Do not recreate it to recover missing metadata."
    }
    return result

    // Retained SDK namespace persistence and metadata reads for deliberate rollback.
    //     // Persist the authoritative namespace as a device data value so hub_list_devices(filter='virtual') can
    //     // read it back reliably. getDriverType()?.namespace returns null on real hubs for custom-driver
    //     // virtual devices (confirmed on Hubitat 2.5.0.126), making the list path's derivation unreliable.
    //     // Persisting here at create time -- when the namespace is unambiguously known -- gives the
    //     // list path one authoritative read path for all MCP-created devices.
    //     try {
    //         newDevice.updateDataValue("mcpDriverNamespace", namespace)
    //     } catch (Exception e) {
    //         mcpLog("warn", "device", "Could not persist mcpDriverNamespace data value on device ${newDevice.id}: ${e.class.simpleName}: ${e.message ?: e.toString()} -- hub_list_devices(filter='virtual') will fall back to best-effort derivation for this device")
    //     }
    //
    //     // Read back device info
    //     def deviceInfo = [
    //         id: newDevice.id.toString(),
    //         name: newDevice.name,
    //         label: newDevice.label ?: newDevice.name,
    //         deviceNetworkId: newDevice.deviceNetworkId,
    //         driverNamespace: namespace,
    //         driverType: typeName,
    //         typeName: typeName,  // deprecated alias for driverType; retained so callers reading result.device.typeName after create do not break -- prefer driverType
    //         capabilities: newDevice.capabilities?.collect { it.name } ?: [],
    //         commands: newDevice.supportedCommands?.collect { it.name } ?: [],
    //         attributes: newDevice.supportedAttributes?.collect { attr ->
    //             [name: attr.name, value: newDevice.currentValue(attr.name)]
    //         } ?: []
    //     ]
    //
    //     mcpLog("info", "device", "Virtual device created successfully: '${deviceLabel}' (ID: ${newDevice.id}, DNI: ${dni})")
    //
    //     return [
    //         success: true,
    //         message: "Virtual device '${deviceLabel}' created successfully. It is now accessible via all MCP device tools (hub_call_device_command, hub_get_device, etc.) without needing to be added to the device selection list. It also appears in the Hubitat device list and can be shared with other apps like Maker API.",
    //         device: deviceInfo,
    //         tips: [
    //             "Use hub_call_device_command with deviceId '${newDevice.id}' to control this device",
    //             "The device is visible in Hubitat web UI under Devices for sharing with other apps",
    //             "To add it to Maker API: open Maker API app settings and select this device"
    //         ]
    //     ]

}

def toolListVirtualDevices(args) {
    def childDevs = getChildDevices() ?: []
    def cursor = args?.cursor
    int offset = args?.offset != null ? (args.offset as Integer) : 0
    int limit = args?.limit != null ? (args.limit as Integer) : 0
    if (cursor != null) {
        if (offset > 0) {
            throw new IllegalArgumentException("cursor and offset are mutually exclusive (got cursor=${cursor}, offset=${offset}); pick one")
        }
        offset = _parseListCursor(cursor, childDevs.size(), "hub_list_devices(filter='virtual')")
        if (limit <= 0) limit = 50
    }
    if (offset < 0) offset = 0

    if (!childDevs) {
        def empty = [
            devices: [],
            count: 0,
            total: 0,
            message: "No MCP-managed virtual devices found. Use hub_manage_virtual_device(action=\"create\") to create one."
        ]
        if (limit > 0) {
            empty.offset = 0
            empty.limit = limit
            empty.hasMore = false
        }
        return empty
    }

    int startIndex = Math.min(offset, childDevs.size())
    int endIndex = limit > 0
        ? (int) Math.min(((long) startIndex) + limit, childDevs.size())
        : childDevs.size()
    def page = childDevs.subList(startIndex, endIndex).collect { device ->
        def deviceId = device.id.toString()
        try {
            def fullJson = _fetchDeviceFullJson(deviceId)
            if (!(fullJson?.device instanceof Map) || !fullJson.device) {
                return [id: deviceId, success: false,
                    error: "Native device information unavailable from /device/fullJson/${deviceId}.",
                    note: "The device remains MCP-owned. Inspect this device in the Hubitat UI and retry the read."]
            }
            def nativeDevice = fullJson.device
            def summary = _getDeviceFromFullJson(deviceId, fullJson)
            def persistedNamespace = nativeDevice.data instanceof Map ? nativeDevice.data.mcpDriverNamespace : null
            def namespace = persistedNamespace ?: nativeDevice.deviceTypeNamespace
            def typeName = nativeDevice.deviceTypeName
            def info = [id: deviceId, name: summary.name, label: summary.label,
                deviceNetworkId: nativeDevice.deviceNetworkId,
                driverNamespace: namespace, driverType: typeName, typeName: typeName,
                capabilities: summary.capabilities, commands: summary.commands.collect { it.name },
                currentStates: [:]]
            summary.attributes.each { attr ->
                if (attr.value != null) info.currentStates[attr.name] = attr.value
            }
            if (!namespace || !typeName) {
                info.warnings = ['Native driver namespace or type metadata is unavailable; no driver identity was inferred.']
            }
            return info
        } catch (Exception ignored) {
            return [id: deviceId, success: false,
                error: "Native device information could not be interpreted from /device/fullJson/${deviceId}.",
                note: "The device remains MCP-owned. Inspect this device in the Hubitat UI and retry the read."]
        }
    }
    def result = [
        devices: page,
        count: page.size(),
        total: childDevs.size(),
        message: "Found ${childDevs.size()} MCP-managed virtual ${childDevs.size() == 1 ? 'device' : 'devices'}. These are automatically accessible to all MCP device tools."
    ]
    if (page.any { it.success == false }) {
        result.success = false
        result.error = 'Native information could not be read for one or more MCP-managed virtual devices.'
        result.note = 'Device IDs and counts include unreadable devices. Inspect the failed entries and retry the read.'
    }
    if (page.any { it.warnings } || (page.any { it.success == false } && page.any { it.success != false })) {
        result.partialSuccess = true
    }
    if (limit > 0) {
        result.offset = startIndex
        result.limit = limit
        result.hasMore = endIndex < childDevs.size()
        if (endIndex < childDevs.size()) {
            result.nextOffset = endIndex
            if (cursor != null) result.nextCursor = endIndex.toString()
        }
    }
    return result

    // Retained SDK virtual inventory for deliberate rollback.
    //     def devices = childDevs.collect { device ->
    //         // driverNamespace: authoritative for MCP-created devices via the mcpDriverNamespace data value
    //         // persisted at create time. For devices created before this version or by other means, falls back
    //         // to getDriverType()?.namespace (which returns null on real hubs for custom-driver virtual
    //         // devices -- confirmed on Hubitat 2.5.0.126), then to "hubitat" as the last resort.
    //         // driverType: the driver type name. typeName kept as deprecated alias -- prefer driverType in new code.
    //         def devNamespace = device.getDataValue("mcpDriverNamespace")
    //         if (!devNamespace) {
    //             // Backward-compat fallback for devices not created by this version
    //             try {
    //                 devNamespace = device.getDriverType()?.namespace
    //             } catch (Exception e) {
    //                 devNamespace = null
    //                 mcpLog("debug", "device", "getDriverType() unavailable for ${device.id}: ${e.class.simpleName}: ${e.message}")
    //             }
    //         }
    //         devNamespace = devNamespace ?: "hubitat"  // final fallback when both data value and getDriverType() yield null.
    //         def devTypeName  = device.typeName ?: device.name
    //         def info = [
    //             id: device.id.toString(),
    //             name: device.name,
    //             label: device.label ?: device.name,
    //             deviceNetworkId: device.deviceNetworkId,
    //             driverNamespace: devNamespace,
    //             driverType: devTypeName,
    //             typeName: devTypeName,  // deprecated alias; use driverType
    //             capabilities: device.capabilities?.collect { it.name } ?: [],
    //             commands: device.supportedCommands?.collect { it.name } ?: [],
    //             currentStates: [:]
    //         ]
    //         // Gather common attribute values
    //         ["switch", "level", "contact", "motion", "temperature", "humidity",
    //          "presence", "lock", "water", "button", "speed", "position"].each { attr ->
    //             def val = device.currentValue(attr)
    //             if (val != null) info.currentStates[attr] = val
    //         }
    //         return info
    //     }
    //
    //     int startIndex = Math.min(offset, devices.size())
    //     int endIndex = limit > 0
    //         ? (int) Math.min(((long) startIndex) + limit, devices.size())
    //         : devices.size()
    //     def page = devices.subList(startIndex, endIndex)
    //     def result = [
    //         devices: page,
    //         count: page.size(),
    //         total: devices.size(),
    //         message: "Found ${devices.size()} MCP-managed virtual ${devices.size() == 1 ? 'device' : 'devices'}. These are automatically accessible to all MCP device tools."
    //     ]
    //     if (limit > 0) {
    //         result.offset = startIndex
    //         result.limit = limit
    //         result.hasMore = endIndex < devices.size()
    //         if (endIndex < devices.size()) {
    //             result.nextOffset = endIndex
    //             if (cursor != null) result.nextCursor = endIndex.toString()
    //         }
    //     }
    //     return result

}

def toolDeleteVirtualDevice(args) {
    requireDestructiveConfirm(args.confirm)

    def dni = args.deviceNetworkId
    if (!dni) throw new IllegalArgumentException("deviceNetworkId is required")

    def childDevice = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (!childDevice) {
        throw new IllegalArgumentException("No MCP-managed virtual device found with network ID '${dni}'. Use hub_list_devices(filter='virtual') to see available devices.")
    }

    def deviceId = childDevice.id.toString()
    def fullJson = _fetchDeviceFullJson(deviceId)
    if (!(fullJson?.device instanceof Map) || !fullJson.device) {
        return [success: false, deviceId: deviceId, deviceNetworkId: dni,
            error: "Native device identity unavailable from /device/fullJson/${deviceId}.",
            note: "No deletion was attempted. Inspect this device in the Hubitat UI and retry once native information is available."]
    }
    def deviceLabel = fullJson.device.label ?: fullJson.device.name ?: "Unknown"
    // Retained SDK identity read for deliberate rollback.
    // def deviceLabel = childDevice.label ?: childDevice.name ?: "Unknown"

    mcpLog("warn", "device", "DELETE VIRTUAL DEVICE: Deleting '${deviceLabel}' (ID: ${deviceId}, DNI: ${dni})")

    try {
        deleteChildDevice(dni)
    } catch (Exception e) {
        mcpLogError("device", "Failed to delete virtual device '${deviceLabel}' (DNI: ${dni})", e)
        throw new RuntimeException("Failed to delete virtual device: ${e.message}")
    }

    // Verify deletion
    def stillExists = getChildDevices()?.find { it.deviceNetworkId == dni }
    def verified = !stillExists

    mcpLog(verified ? "info" : "warn", "device", "Virtual device delete ${verified ? 'VERIFIED' : 'UNVERIFIED'}: '${deviceLabel}' (DNI: ${dni})")

    return [
        success: verified,
        deviceId: deviceId,
        deviceNetworkId: dni,
        deviceLabel: deviceLabel,
        message: verified
            ? "Virtual device '${deviceLabel}' (DNI: ${dni}) has been permanently deleted."
            : "Delete command was sent but device may still exist. Check Hubitat web UI to verify."
    ]
}

def _getAllToolDefinitions_partVirtualDevices() {
    return [
        // Virtual Device Management
        [
            name: "hub_manage_virtual_device",
            description: """Create or delete MCP-managed virtual devices. Requires Write master + confirm; see hub_get_tool_guide(section='virtual_devices') for device types and response shapes.
[[FLAT_TRIM]]
action="create": provide EITHER deviceType (built-in virtual type, see enum) OR customDriver={namespace, name} (user-installed driver) -- exactly one -- plus deviceLabel; deviceNetworkId auto-generates when omitted. Discover custom-driver namespace+name via hub_read_apps_code(tool="hub_list_drivers").
action="delete": provide the target deviceNetworkId.
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", description: "Operation to perform", enum: ["create", "delete"]],
                    deviceType: [type: "string", description: "Built-in virtual driver type for create; mutually exclusive with customDriver.",
                        enum: getSupportedVirtualDeviceTypes()],
                    customDriver: [type: "object", description: "User-installed custom driver for create; mutually exclusive with deviceType.",
                        properties: [
                            namespace: [type: "string", description: "Driver namespace (e.g., 'level99-vesync')."],
                            name: [type: "string", description: "Driver type name as registered on the hub (e.g., 'Levoit Classic 200S Humidifier')."]
                        ],
                        required: ["namespace", "name"]
                    ],
                    deviceLabel: [type: "string", description: "Display label (required for create)"],
                    deviceNetworkId: [type: "string", description: "Device network ID; auto-generated on create when omitted, REQUIRED for delete (find via hub_list_devices(filter='virtual'))."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true to confirm the operation."]
                ],
                required: ["action", "confirm"]
                // deviceType/customDriver XOR is enforced at runtime in toolManageVirtualDevice
                // (IllegalArgumentException -> -32602), NOT in the schema: this is an
                // action-discriminated tool and a top-level oneOf would also reject valid delete
                // calls (which carry neither field). Consistent with every other manage_* tool,
                // which enforce action-conditional args at runtime.
            ]
        ],
    ]
}

def _toolDisplayMeta_partVirtualDevices() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_manage_virtual_device: [title: "Manage Virtual Device", summary: "Create or delete an MCP-managed virtual device."]
    ]
}
