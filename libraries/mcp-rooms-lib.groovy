library(name: "McpRoomsLib", namespace: "mcp", author: "kingpanther13", description: "Room management tool implementations for the MCP Rule Server (hub_list_rooms/hub_get_room/hub_create_room/hub_delete_room/hub_update_room); #include'd by the main app. Tool definitions, gateway entries, and dispatch stay in the app.")

def toolListRooms(args = null) {
    def rooms = getRooms()
    if (!rooms) {
        return [rooms: [], count: 0, message: "No rooms configured on this hub."]
    }
    def roomList = rooms.collect { room ->
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
    def room = rooms.find { it.id?.toString() == roomIdentifier } ?:
               rooms.find { it.name?.toLowerCase() == roomIdentifier.toLowerCase() }

    if (!room) {
        def available = rooms.collect { it.name }.sort()
        throw new IllegalArgumentException("Room '${roomIdentifier}' not found. Available rooms: ${available.join(', ')}")
    }

    // Get device details for each device in the room
    def devices = []
    def allDevices = (settings.selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    def selectedIds = allDevices.collect { it.id.toString() } as Set
    childDevs.each { cd -> if (!selectedIds.contains(cd.id.toString())) { allDevices.add(cd) } }

    room.deviceIds?.each { devId ->
        def device = allDevices?.find { it.id?.toString() == devId.toString() }
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
            } catch (Exception ignored) {}
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
    if (rooms?.find { it.name?.toLowerCase() == roomName.toLowerCase() }) {
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
    def newRoom = updatedRooms?.find { it.name?.toLowerCase() == roomName.toLowerCase() }
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

    def room = rooms.find { it.id?.toString() == args.room.trim() } ?:
               rooms.find { it.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it.name }.sort()
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
    def stillExists = updatedRooms?.find { it.id?.toString() == roomId.toString() }
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

    def room = rooms.find { it.id?.toString() == args.room.trim() } ?:
               rooms.find { it.name?.toLowerCase() == args.room.trim().toLowerCase() }
    if (!room) {
        def available = rooms.collect { it.name }.sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    // Check for name conflict
    if (rooms.find { it.name?.toLowerCase() == newName.toLowerCase() && it.id != room.id }) {
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
    def updatedRoom = updatedRooms?.find { it.id?.toString() == roomId.toString() }
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
