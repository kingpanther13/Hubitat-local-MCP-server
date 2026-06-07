library(name: "McpRoomsLib", namespace: "mcp", author: "kingpanther13", description: "Room management tool implementations for the MCP Rule Server (hub_list_rooms/hub_get_room/hub_create_room/hub_delete_room/hub_update_room); #include'd by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

def toolListRooms(args = null) {
    def rooms = getRooms()
    if (!rooms) {
        return [rooms: [], count: 0, message: "No rooms configured on this hub."]
    }
    def roomList = rooms.findAll { it != null }.collect { room ->
        [
            id: room.id?.toString(),
            name: room.name,
            deviceCount: room.deviceIds?.size() ?: 0,
            deviceIds: room.deviceIds?.collect { it.toString() } ?: []
        ]
    }.sort { it.name }
    def cursor = args?.cursor
    def paged = _paginateList(roomList, cursor, 100, "hub_list_rooms")
    def result = [rooms: paged.page, count: paged.page.size()]
    if (cursor != null) {
        result.total = roomList.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    return result
}

def toolGetRoom(String roomIdentifier) {
    if (!roomIdentifier) throw new IllegalArgumentException("Room name or ID is required")

    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    // Find room by ID or name (case-insensitive)
    def room = rooms.find { it?.id?.toString() == roomIdentifier } ?:
               rooms.find { it?.name?.toLowerCase() == roomIdentifier.toLowerCase() }

    if (!room) {
        def available = rooms.collect { it?.name }.sort()
        throw new IllegalArgumentException("Room '${roomIdentifier}' not found. Available rooms: ${available.join(', ')}")
    }

    // Get device details for each device in the room
    def devices = []
    def allDevices = (settings.selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    def selectedIds = allDevices.collect { it.id.toString() } as Set
    childDevs.each { cd -> if (!selectedIds.contains(cd.id.toString())) { allDevices.add(cd) } }

    room.deviceIds?.each { devId ->
        def device = allDevices?.find { it?.id?.toString() == devId.toString() }
        if (device) {
            def devInfo = [
                id: device.id.toString(),
                label: device.label ?: device.name ?: "unknown",
                name: device.name ?: "unknown"
            ]
            // Add common current states
            def states = [:]
            try {
                device.currentStates?.each { st ->
                    states[st.name] = st.value
                }
            } catch (Exception e) {
                mcpLog("debug", "room", "hub_get_room: currentStates read failed for device ${device.id}: ${e.message}")
            }
            if (states) devInfo.currentStates = states
            devices << devInfo
        } else {
            devices << [id: devId.toString(), label: "(device not accessible via MCP)", name: "unknown", accessible: false]
        }
    }

    return [
        id: room.id?.toString(),
        name: room.name,
        deviceCount: devices.size(),
        devices: devices.sort { (it.label ?: "").toLowerCase() }
    ]
}

def toolCreateRoom(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.name?.trim()) {
        throw new IllegalArgumentException("Room name is required")
    }

    def roomName = args.name.trim()

    // Check for duplicate name
    def rooms = getRooms()
    if (rooms?.find { it?.name?.toLowerCase() == roomName.toLowerCase() }) {
        throw new IllegalArgumentException("A room named '${roomName}' already exists")
    }

    // Build device IDs list
    def deviceIds = args.deviceIds?.collect { it as Integer } ?: []

    // POST /room/save with roomId: 0 to create (Grails convention). Routed through
    // hubInternalPostJson for the shared Hub Security cookie-refresh retry; the
    // creation is confirmed against getRooms() below.
    def body = [roomId: 0, name: roomName, deviceIds: deviceIds]
    def jsonStr = groovy.json.JsonOutput.toJson(body)
    mcpLog("debug", "room", "hub_create_room: POST /room/save body: ${jsonStr}")

    def parsed
    try {
        parsed = hubInternalPostJson("/room/save", jsonStr, 30)
    } catch (Exception httpErr) {
        throw new RuntimeException("Failed to create room '${roomName}': ${httpErr.message}")
    }
    if (parsed?.error) {
        throw new RuntimeException("Failed to create room: ${parsed.error}")
    }

    // Verify creation (case-insensitive to handle any normalization)
    def updatedRooms = getRooms()
    def newRoom = updatedRooms?.find { it?.name?.toLowerCase() == roomName.toLowerCase() }
    if (!newRoom) {
        throw new RuntimeException("Room creation endpoint returned success but room '${roomName}' not found in rooms list")
    }

    mcpLog("info", "room", "Created room '${roomName}' (ID: ${newRoom.id})")
    return [
        success: true,
        room: [id: newRoom.id?.toString(), name: newRoom.name, deviceCount: newRoom.deviceIds?.size() ?: 0],
        message: "Room '${roomName}' created successfully."
    ]
}

def toolDeleteRoom(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.room?.trim()) {
        throw new IllegalArgumentException("Room name or ID is required")
    }

    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    def room = rooms.find { it?.id?.toString() == args.room.trim() } ?:
               rooms.find { it?.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it?.name }.sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    def roomId = room.id
    def roomName = room.name
    def deviceCount = room.deviceIds?.size() ?: 0
    mcpLog("debug", "room", "hub_delete_room: deleting room '${roomName}' (ID: ${roomId}), ${deviceCount} devices will be unassigned")

    def deleteSuccess = false
    def deleteError = null

    // Try POST /room/delete/<id> first, then GET /room/delete/<id>
    def attempts = [
        [desc: "POST /room/delete/${roomId}", method: "POST"],
        [desc: "GET /room/delete/${roomId}", method: "GET"],
    ]
    for (def att : attempts) {
        if (deleteSuccess) break
        try {
            if (att.method == "POST") {
                // Routed through hubInternalPostJson for the shared cookie-refresh retry.
                def parsed = hubInternalPostJson("/room/delete/${roomId}", groovy.json.JsonOutput.toJson([roomId: roomId as Integer]), 30)
                if (parsed?.error) {
                    throw new RuntimeException("API error: ${parsed.error}")
                }
                deleteSuccess = true
            } else {
                hubInternalGet("/room/delete/${roomId}")
                mcpLog("debug", "room", "hub_delete_room: ${att.desc} succeeded")
                deleteSuccess = true
            }
        } catch (Exception e) {
            mcpLog("debug", "room", "hub_delete_room: ${att.desc} failed: ${e.message}")
            deleteError = e.message
        }
    }

    if (!deleteSuccess) {
        throw new RuntimeException("Failed to delete room '${roomName}'. Last error: ${deleteError}")
    }

    // Verify deletion
    def updatedRooms = getRooms()
    def stillExists = updatedRooms?.find { it?.id?.toString() == roomId.toString() }
    if (stillExists) {
        throw new RuntimeException("Delete endpoint returned success but room '${roomName}' still exists")
    }

    mcpLog("info", "room", "Deleted room '${roomName}' (ID: ${roomId}), ${deviceCount} devices unassigned")
    def deviceWord = deviceCount == 1 ? "device" : "devices"
    def verbForm = deviceCount == 1 ? "is" : "are"
    return [
        success: true,
        deletedRoom: [id: roomId.toString(), name: roomName],
        devicesUnassigned: deviceCount,
        message: "Room '${roomName}' deleted. ${deviceCount} ${deviceWord} ${verbForm} now unassigned."
    ]
}

def toolRenameRoom(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.room?.trim()) {
        throw new IllegalArgumentException("Room name or ID is required")
    }
    if (!args.newName?.trim()) {
        throw new IllegalArgumentException("New room name is required")
    }

    def newName = args.newName.trim()
    def rooms = getRooms()
    if (!rooms) throw new IllegalArgumentException("No rooms configured on this hub.")

    def room = rooms.find { it?.id?.toString() == args.room.trim() } ?:
               rooms.find { it?.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it?.name }.sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    // Check for name conflict
    if (rooms.find { it?.name?.toLowerCase() == newName.toLowerCase() && it.id != room.id }) {
        throw new IllegalArgumentException("A room named '${newName}' already exists")
    }

    def oldName = room.name
    def roomId = room.id
    def deviceIds = room.deviceIds?.collect { it as Integer } ?: []

    // POST /room/save with existing roomId and new name. Routed through
    // hubInternalPostJson for the shared cookie-refresh retry; the rename is
    // confirmed against getRooms() below.
    def body = [roomId: roomId as Integer, name: newName, deviceIds: deviceIds]
    def jsonStr = groovy.json.JsonOutput.toJson(body)
    mcpLog("debug", "room", "hub_update_room: POST /room/save body: ${jsonStr}")

    def parsed
    try {
        parsed = hubInternalPostJson("/room/save", jsonStr, 30)
    } catch (Exception httpErr) {
        throw new RuntimeException("Failed to rename room '${oldName}': ${httpErr.message}")
    }
    if (parsed?.error) {
        throw new RuntimeException("Failed to rename room: ${parsed.error}")
    }

    // Verify rename
    def updatedRooms = getRooms()
    def updatedRoom = updatedRooms?.find { it?.id?.toString() == roomId.toString() }
    if (!updatedRoom || updatedRoom.name != newName) {
        throw new RuntimeException("Rename endpoint returned success but room name did not change")
    }

    mcpLog("info", "room", "Renamed room '${oldName}' -> '${newName}' (ID: ${roomId})")
    return [
        success: true,
        room: [id: roomId.toString(), name: newName, previousName: oldName],
        message: "Room renamed from '${oldName}' to '${newName}'."
    ]
}

// Tool DEFINITIONS for the room tools (issue #209: schema lives with the impl). Concatenated
// into getAllToolDefinitions() in the main app; gateway membership + dispatch stay in main.
def _getAllToolDefinitions_partRooms() {
    return [
        [
            name: "hub_list_rooms",
            description: "List all rooms on the hub, each with its ID, name, device count, and assigned device IDs. Use to discover available rooms or resolve a room name to its ID before calling hub_get_room/hub_update_room/hub_delete_room. Read-only and parallel-safe. Returns summaries only — call hub_get_room for per-device states.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    rooms: [type: "array", description: "Rooms on the hub", items: [type: "object", properties: [
                        id: [type: "string", description: "Room ID"],
                        name: [type: "string", description: "Room name"],
                        deviceCount: [type: "integer", description: "Devices assigned"],
                        deviceIds: [type: "array", description: "Assigned device IDs", items: [type: "string"]]
                    ]]],
                    count: [type: "integer", description: "Rooms returned this page"],
                    total: [type: "integer", description: "Total rooms; present only in paginated mode"],
                    nextCursor: [type: "string", description: "Pagination cursor; present when more results remain"],
                    message: [type: "string", description: "Present when no rooms configured"]
                ],
                required: ["rooms", "count"]
            ]
        ],
        [
            name: "hub_get_room",
            description: "Get one room's details: its ID, name, and the full list of assigned devices with each device's current attribute states. Use when you need device-level detail for a single room; for a name-and-count overview of all rooms use hub_list_rooms instead. Read-only and parallel-safe. Devices unreachable via MCP are returned with accessible=false and no states.",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Room name (case-insensitive) or room ID, e.g. \"Living Room\" or \"5\""]
                ],
                required: ["room"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Room ID"],
                    name: [type: "string", description: "Room name"],
                    deviceCount: [type: "integer", description: "Devices in room"],
                    devices: [type: "array", description: "Assigned devices", items: [type: "object", properties: [
                        id: [type: "string", description: "Device ID"],
                        label: [type: "string", description: "Device label"],
                        name: [type: "string", description: "Device name"],
                        currentStates: [type: "object", description: "Current attribute values; present when accessible"],
                        accessible: [type: "boolean", description: "False when device not reachable via MCP"]
                    ]]]
                ],
                required: ["id", "name", "deviceCount", "devices"]
            ]
        ],
        [
            name: "hub_create_room",
            description: "Create a new room on the hub, optionally assigning devices to it at creation. Use when a needed room does not yet exist; to only move devices into an existing room, use hub_update_room/room-assignment flows instead. Write operation: requires Write master, a backup taken within the last 24h, and confirm=true. Returns the new room's ID and assigned device count.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Name for the new room, e.g. \"Garage\""],
                    deviceIds: [type: "array", description: "Optional device IDs to assign to the room at creation, e.g. [\"12\",\"34\"]. Omit to create an empty room.", items: [type: "string"]],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved creating this room."]
                ],
                required: ["name", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether creation succeeded"],
                    room: [type: "object", description: "Created room", properties: [
                        id: [type: "string", description: "New room ID"],
                        name: [type: "string", description: "Room name"],
                        deviceCount: [type: "integer", description: "Devices assigned"]
                    ]],
                    message: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_room",
            description: """⚠️ DESTRUCTIVE: Permanently deletes a room. Devices become unassigned (not deleted).

PRE-FLIGHT: 1) Backup <24h 2) Verify correct room 3) List affected devices to user 4) Get explicit confirmation 5) Set confirm=true
Requires Write master.""",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Room name (case-insensitive) or room ID to delete, e.g. \"Garage\" or \"5\""],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user explicitly approved the deletion."]
                ],
                required: ["room", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether deletion succeeded"],
                    deletedRoom: [type: "object", description: "Deleted room", properties: [
                        id: [type: "string", description: "Room ID"],
                        name: [type: "string", description: "Room name"]
                    ]],
                    devicesUnassigned: [type: "integer", description: "Devices now unassigned"],
                    message: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_room",
            description: "Rename a room. Device assignments preserved. Automations/dashboards referencing room by name may need updating. Requires Write master + confirm + backup <24h.",
            inputSchema: [
                type: "object",
                properties: [
                    room: [type: "string", description: "Current room name (case-insensitive) or room ID, e.g. \"Garage\" or \"5\""],
                    newName: [type: "string", description: "New name for the room, e.g. \"Workshop\""],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["room", "newName", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether rename succeeded"],
                    room: [type: "object", description: "Renamed room", properties: [
                        id: [type: "string", description: "Room ID"],
                        name: [type: "string", description: "New room name"],
                        previousName: [type: "string", description: "Prior room name"]
                    ]],
                    message: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ]
    ]
}
