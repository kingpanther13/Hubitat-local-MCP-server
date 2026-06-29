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

    // Fetch child devices once for DNI generation and validation
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
        throw new IllegalArgumentException("A device with network ID '${dni}' already exists: '${existingChild.label ?: existingChild.name}' (ID: ${existingChild.id})")
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

    // Persist the authoritative namespace as a device data value so hub_list_devices(filter='virtual') can
    // read it back reliably. getDriverType()?.namespace returns null on real hubs for custom-driver
    // virtual devices (confirmed on Hubitat 2.5.0.126), making the list path's derivation unreliable.
    // Persisting here at create time -- when the namespace is unambiguously known -- gives the
    // list path one authoritative read path for all MCP-created devices.
    try {
        newDevice.updateDataValue("mcpDriverNamespace", namespace)
    } catch (Exception e) {
        mcpLog("warn", "device", "Could not persist mcpDriverNamespace data value on device ${newDevice.id}: ${e.class.simpleName}: ${e.message ?: e.toString()} -- hub_list_devices(filter='virtual') will fall back to best-effort derivation for this device")
    }

    // Read back device info
    def deviceInfo = [
        id: newDevice.id.toString(),
        name: newDevice.name,
        label: newDevice.label ?: newDevice.name,
        deviceNetworkId: newDevice.deviceNetworkId,
        driverNamespace: namespace,
        driverType: typeName,
        typeName: typeName,  // deprecated alias for driverType; retained so callers reading result.device.typeName after create do not break -- prefer driverType
        capabilities: newDevice.capabilities?.collect { it.name } ?: [],
        commands: newDevice.supportedCommands?.collect { it.name } ?: [],
        attributes: newDevice.supportedAttributes?.collect { attr ->
            [name: attr.name, value: newDevice.currentValue(attr.name)]
        } ?: []
    ]

    mcpLog("info", "device", "Virtual device created successfully: '${deviceLabel}' (ID: ${newDevice.id}, DNI: ${dni})")

    return [
        success: true,
        message: "Virtual device '${deviceLabel}' created successfully. It is now accessible via all MCP device tools (hub_call_device_command, hub_get_device, etc.) without needing to be added to the device selection list. It also appears in the Hubitat device list and can be shared with other apps like Maker API.",
        device: deviceInfo,
        tips: [
            "Use hub_call_device_command with deviceId '${newDevice.id}' to control this device",
            "The device is visible in Hubitat web UI under Devices for sharing with other apps",
            "To add it to Maker API: open Maker API app settings and select this device"
        ]
    ]
}

def toolListVirtualDevices(args) {
    def childDevs = getChildDevices() ?: []

    if (!childDevs) {
        return [
            devices: [],
            count: 0,
            message: "No MCP-managed virtual devices found. Use hub_manage_virtual_device(action=\"create\") to create one."
        ]
    }

    def devices = childDevs.collect { device ->
        // driverNamespace: authoritative for MCP-created devices via the mcpDriverNamespace data value
        // persisted at create time. For devices created before this version or by other means, falls back
        // to getDriverType()?.namespace (which returns null on real hubs for custom-driver virtual
        // devices -- confirmed on Hubitat 2.5.0.126), then to "hubitat" as the last resort.
        // driverType: the driver type name. typeName kept as deprecated alias -- prefer driverType in new code.
        def devNamespace = device.getDataValue("mcpDriverNamespace")
        if (!devNamespace) {
            // Backward-compat fallback for devices not created by this version
            try {
                devNamespace = device.getDriverType()?.namespace
            } catch (Exception e) {
                devNamespace = null
                mcpLog("debug", "device", "getDriverType() unavailable for ${device.id}: ${e.class.simpleName}: ${e.message}")
            }
        }
        devNamespace = devNamespace ?: "hubitat"  // final fallback when both data value and getDriverType() yield null.
        def devTypeName  = device.typeName ?: device.name
        def info = [
            id: device.id.toString(),
            name: device.name,
            label: device.label ?: device.name,
            deviceNetworkId: device.deviceNetworkId,
            driverNamespace: devNamespace,
            driverType: devTypeName,
            typeName: devTypeName,  // deprecated alias; use driverType
            capabilities: device.capabilities?.collect { it.name } ?: [],
            commands: device.supportedCommands?.collect { it.name } ?: [],
            currentStates: [:]
        ]
        // Gather common attribute values
        ["switch", "level", "contact", "motion", "temperature", "humidity",
         "presence", "lock", "water", "button", "speed", "position"].each { attr ->
            def val = device.currentValue(attr)
            if (val != null) info.currentStates[attr] = val
        }
        return info
    }

    def cursor = args?.cursor
    def paged = _paginateList(devices, cursor, 50, "list_devices_virtual")
    def result = [
        devices: paged.page,
        count: paged.page.size(),
        message: "Found ${devices.size()} MCP-managed virtual ${devices.size() == 1 ? 'device' : 'devices'}. These are automatically accessible to all MCP device tools."
    ]
    if (cursor != null) {
        result.total = devices.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    return result
}

def toolDeleteVirtualDevice(args) {
    requireDestructiveConfirm(args.confirm)

    def dni = args.deviceNetworkId
    if (!dni) throw new IllegalArgumentException("deviceNetworkId is required")

    def childDevice = getChildDevices()?.find { it.deviceNetworkId == dni }
    if (!childDevice) {
        throw new IllegalArgumentException("No MCP-managed virtual device found with network ID '${dni}'. Use hub_list_devices(filter='virtual') to see available devices.")
    }

    def deviceLabel = childDevice.label ?: childDevice.name ?: "Unknown"
    def deviceId = childDevice.id.toString()

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
            description: """Create or delete MCP-managed virtual devices. Requires Write master + confirm.

action="create": Provide EITHER deviceType (built-in virtual types, see enum) OR customDriver={namespace, name} (user-installed driver), plus deviceLabel and optional deviceNetworkId.
action="delete": Provide deviceNetworkId of device to delete. Use hub_list_devices(filter='virtual') to find DNIs.""",
            inputSchema: [
                type: "object",
                properties: [
                    action: [type: "string", description: "Operation to perform", enum: ["create", "delete"]],
                    deviceType: [type: "string", description: "Built-in virtual device driver type (for create). Mutually exclusive with customDriver -- provide exactly one.",
                        enum: getSupportedVirtualDeviceTypes()],
                    customDriver: [type: "object", description: "Alternative to deviceType: a user-installed custom driver (mutually exclusive with deviceType).",
                        properties: [
                            namespace: [type: "string", description: "Driver namespace (e.g., 'level99-vesync')."],
                            name: [type: "string", description: "Driver type name as registered on the hub (e.g., 'Levoit Classic 200S Humidifier')."]
                        ],
                        required: ["namespace", "name"]
                    ],
                    deviceLabel: [type: "string", description: "Display label (required for create)"],
                    deviceNetworkId: [type: "string", description: "Device network ID. Auto-generated for create if omitted. REQUIRED for delete."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true to confirm the operation."]
                ],
                required: ["action", "confirm"]
                // deviceType/customDriver XOR is enforced at runtime in toolManageVirtualDevice
                // (IllegalArgumentException -> -32602), NOT in the schema: this is an
                // action-discriminated tool and a top-level oneOf would also reject valid delete
                // calls (which carry neither field). Consistent with every other manage_* tool,
                // which enforce action-conditional args at runtime.
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the operation succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    device: [type: "object", description: "create only: the new virtual device", properties: [
                        id: [type: "string", description: "New device ID"],
                        name: [type: "string", description: "Driver type / device name"],
                        label: [type: "string", description: "Display label"],
                        deviceNetworkId: [type: "string", description: "Device network ID"],
                        driverNamespace: [type: "string", description: "Driver namespace"],
                        driverType: [type: "string", description: "Driver type name"],
                        typeName: [type: "string", description: "Deprecated alias for driverType"],
                        capabilities: [type: "array", description: "Capability names", items: [type: "string"]],
                        commands: [type: "array", description: "Command names", items: [type: "string"]],
                        attributes: [type: "array", description: "Attribute name/value pairs", items: [type: "object"]]
                    ]],
                    tips: [type: "array", description: "create only: usage tips", items: [type: "string"]],
                    deviceId: [type: "string", description: "delete only: deleted device ID"],
                    deviceNetworkId: [type: "string", description: "delete only: deleted device DNI"],
                    deviceLabel: [type: "string", description: "delete only: deleted device label"]
                ],
                required: ["success", "message"]
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
