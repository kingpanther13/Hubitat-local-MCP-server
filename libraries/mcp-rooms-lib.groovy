library(name: "McpRoomsLib", namespace: "mcp", author: "kingpanther13", description: "Room management tool implementations for the MCP Rule Server (hub_list_rooms/hub_get_room/hub_create_room/hub_delete_room/hub_update_room); #include'd by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

def toolListRooms(args = null) {
    def rooms = getRooms()
    if (!rooms) {
        return [rooms: [], count: 0, message: "No rooms configured on this hub."]
    }
    def roomList = (((rooms).findAll((this.&_bat529Cb_cfcfe2bd_4))).collect((this.&_bat529Cb_cfcfe2bd_28))).sort((this.&_bat529Cb_cfcfe2bd_29))
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
    def room = (rooms).find(((this.&_bat529Cb_cfcfe2bd_11).curry([roomIdentifier] as Object[])))?:
               (rooms).find(((this.&_bat529Cb_cfcfe2bd_21).curry([roomIdentifier] as Object[])))

    if (!room) {
        def available = (rooms).collect((this.&_bat529Cb_cfcfe2bd_1)).sort()
        throw new IllegalArgumentException("Room '${roomIdentifier}' not found. Available rooms: ${available.join(', ')}")
    }

    // Get device details for each device in the room
    def devices = []
    def allDevices = (settings.selectedDevices ?: []).toList()
    def childDevs = getChildDevices() ?: []
    def selectedIds = (allDevices).collect((this.&_bat529Cb_cfcfe2bd_5))as Set
    (childDevs).each(((this.&_bat529Cb_cfcfe2bd_26).curry([allDevices, selectedIds] as Object[])))

    (room.deviceIds)?.each(((this.&_bat529Cb_cfcfe2bd_31).curry([allDevices, devices] as Object[])))

    return [
        id: room.id?.toString(),
        name: room.name,
        deviceCount: devices.size(),
        devices: (devices).sort((this.&_bat529Cb_cfcfe2bd_12))]
}

def toolCreateRoom(args) {
    requireDestructiveConfirm(args.confirm)
    if (!args.name?.trim()) {
        throw new IllegalArgumentException("Room name is required")
    }

    def roomName = args.name.trim()

    // Check for duplicate name
    def rooms = getRooms()
    if ((rooms)?.find(((this.&_bat529Cb_cfcfe2bd_20).curry([roomName] as Object[])))) {
        throw new IllegalArgumentException("A room named '${roomName}' already exists")
    }

    // Build device IDs list. Validate each element before coercing so a non-numeric id is a clean
    // IllegalArgumentException (an isError validation result) naming the offending value, not an opaque NumberFormatException.
    def deviceIds = ((args.deviceIds ?: [])).collect((this.&_bat529Cb_cfcfe2bd_30))

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
    def newRoom = (updatedRooms)?.find(((this.&_bat529Cb_cfcfe2bd_22).curry([roomName] as Object[])))
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

    def room = (rooms).find(((this.&_bat529Cb_cfcfe2bd_14).curry([args] as Object[])))?:
               (rooms).find(((this.&_bat529Cb_cfcfe2bd_23).curry([args] as Object[])))
    if (!room) {
        def available = (rooms).collect((this.&_bat529Cb_cfcfe2bd_2)).sort()
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
    def stillExists = (updatedRooms)?.find(((this.&_bat529Cb_cfcfe2bd_19).curry([roomId] as Object[])))
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

    def room = (rooms).find(((this.&_bat529Cb_cfcfe2bd_16).curry([args] as Object[])))?:
               (rooms).find(((this.&_bat529Cb_cfcfe2bd_24).curry([args] as Object[])))
    if (!room) {
        def available = (rooms).collect((this.&_bat529Cb_cfcfe2bd_3)).sort()
        throw new IllegalArgumentException("Room '${args.room}' not found. Available rooms: ${available.join(', ')}")
    }

    // Check for name conflict
    if ((rooms).find(((this.&_bat529Cb_cfcfe2bd_25).curry([newName, room] as Object[])))) {
        throw new IllegalArgumentException("A room named '${newName}' already exists")
    }

    def oldName = room.name
    def roomId = room.id
    def deviceIds = (room.deviceIds)?.collect((this.&_bat529Cb_cfcfe2bd_9))?: []

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
    def updatedRoom = (updatedRooms)?.find(((this.&_bat529Cb_cfcfe2bd_18).curry([roomId] as Object[])))
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
    return _bat529EmbeddedCatalogRead(['["l",[["m",[["name","hub_list_rooms"],["description","List all rooms on the hub, each with its ID, name, device count, and assigned device IDs. Use to discover available rooms or resolve a room name to its ID before calling hub_get_room/hub_update_room/hub_delete_room. Read-only and parallel-safe. Returns summaries only \\u2014 call hub_get_room for per-device states."],["inputSchema",["m",[["type","object"],["properties",["m",[["cursor",["m",[["type","string"],["description","Opt-in pagination cursor. Omit for unbounded; pass \\"\\" for the first page, iterate nextCursor (page size 100)."]]]]]]]]]]]],["m",[["name","hub_get_room"],["description","Get one room\'s details: its ID, name, and the full list of assigned devices with each device\'s current attribute states. Use when you need device-level detail for a single room; for a name-and-count overview of all rooms use hub_list_rooms instead. Read-only and parallel-safe.[[FLAT_TRIM]] Devices unreachable via MCP are returned with accessible=false and no states.[[/FLAT_TRIM]]"],["inputSchema",["m",[["type","object"],["properties",["m",[["room",["m",[["type","string"],["description","Room name (case-insensitive) or room ID, e.g. \\"Living Room\\" or \\"5\\""]]]]]]],["required",["l",["room"]]]]]]]],["m",[["name","hub_create_room"],["description","Create a new room on the hub, optionally assigning devices to it at creation.[[FLAT_TRIM]] Use when a needed room does not yet exist; to only move devices into an existing room, set the device\'s room with hub_update_device instead.[[/FLAT_TRIM]] Requires Write master, backup <24h, confirm=true."],["inputSchema",["m",[["type","object"],["properties",["m",[["name",["m",[["type","string"],["description","Name for the new room, e.g. \\"Garage\\""]]]],["deviceIds",["m",[["type","array"],["description","Optional device IDs to assign to the room at creation, e.g. [\\"12\\",\\"34\\"]."],["items",["m",[["type","string"]]]]]]],["confirm",["m",[["type","boolean"],["description","REQUIRED: must be true. Confirms a recent backup exists and the user approved creating this room."]]]]]]],["required",["l",["name","confirm"]]]]]]]],["m",[["name","hub_delete_room"],["description","\\u26a0\\ufe0f DESTRUCTIVE: Permanently deletes a room. Devices become unassigned (not deleted). Requires Write master, backup <24h, confirm=true.[[FLAT_TRIM]]\\n\\nPRE-FLIGHT: 1) Backup <24h 2) Verify correct room 3) List affected devices to user 4) Get explicit confirmation 5) Set confirm=true[[/FLAT_TRIM]]"],["inputSchema",["m",[["type","object"],["properties",["m",[["room",["m",[["type","string"],["description","Room name (case-insensitive) or room ID to delete, e.g. \\"Garage\\" or \\"5\\""]]]],["confirm",["m",[["type","boolean"],["description","REQUIRED: Must be true. Confirms backup was created and user explicitly approved the deletion."]]]]]]],["required",["l",["room","confirm"]]]]]]]],["m",[["name","hub_update_room"],["description","Rename a room. Device assignments preserved.[[FLAT_TRIM]] Automations/dashboards referencing the room by name may need updating.[[/FLAT_TRIM]] Requires Write master + confirm + backup <24h."],["inputSchema",["m",[["type","object"],["properties",["m",[["room",["m",[["type","string"],["description","Current room name (case-insensitive) or room ID, e.g. \\"Garage\\" or \\"5\\""]]]],["newName",["m",[["type","string"],["description","New name for the room, e.g. \\"Workshop\\""]]]],["confirm",["m",[["type","boolean"],["description","REQUIRED: Must be true. Confirms backup was created and user approved."]]]]]]],["required",["l",["room","newName","confirm"]]]]]]]]]]'])
}

def _readOnlyToolNames_partRooms() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return _bat529EmbeddedCatalogRead(['["l",["hub_list_rooms","hub_get_room"]]'])
}

def _idempotentWriteToolNames_partRooms() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return _bat529EmbeddedCatalogRead(['["l",["hub_update_room","hub_delete_room"]]'])
}

def _toolDisplayMeta_partRooms() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return _bat529EmbeddedCatalogRead(['["m",[["hub_list_rooms",["m",[["title","List Rooms"],["summary","List all rooms with IDs, names, and device counts."]]]],["hub_get_room",["m",[["title","Get Room Details"],["summary","Get one room\'s details with its assigned devices."]]]],["hub_create_room",["m",[["title","Create Room"],["summary","Create a new room, optionally assigning devices."]]]],["hub_delete_room",["m",[["title","Delete Room"],["summary","Permanently delete a room."]]]],["hub_update_room",["m",[["title","Rename Room"],["summary","Rename an existing room."]]]]]]'])
}


def _bat529Cb_cfcfe2bd_1(def it) { it?.name 
}

def _bat529Cb_cfcfe2bd_2(def it) { it?.name 
}

def _bat529Cb_cfcfe2bd_3(def it) { it?.name 
}

def _bat529Cb_cfcfe2bd_4(def it) { it != null 
}

def _bat529Cb_cfcfe2bd_5(def it) { it.id.toString() 
}

def _bat529Cb_cfcfe2bd_6(def it) { it.toString() 
}

def _bat529Cb_cfcfe2bd_7(def it) { it.toString() 
}

def _bat529Cb_cfcfe2bd_8(def it) { it as Integer 
}

def _bat529Cb_cfcfe2bd_9(def it) { it as Integer 
}

def _bat529Cb_cfcfe2bd_10(def roomIdentifier, def it) { it?.id?.toString() == roomIdentifier 
}

def _bat529Cb_cfcfe2bd_11(def roomIdentifier, def it) { it?.id?.toString() == roomIdentifier 
}

def _bat529Cb_cfcfe2bd_12(def it) { (it.label ?: "").toLowerCase() 
}

def _bat529Cb_cfcfe2bd_13(def args, def it) { it?.id?.toString() == args.room.trim() 
}

def _bat529Cb_cfcfe2bd_14(def args, def it) { it?.id?.toString() == args.room.trim() 
}

def _bat529Cb_cfcfe2bd_15(def args, def it) { it?.id?.toString() == args.room.trim() 
}

def _bat529Cb_cfcfe2bd_16(def args, def it) { it?.id?.toString() == args.room.trim() 
}

def _bat529Cb_cfcfe2bd_17(def devId, def it) { it?.id?.toString() == devId.toString() 
}

def _bat529Cb_cfcfe2bd_18(def roomId, def it) { it?.id?.toString() == roomId.toString() 
}

def _bat529Cb_cfcfe2bd_19(def roomId, def it) { it?.id?.toString() == roomId.toString() 
}

def _bat529Cb_cfcfe2bd_20(def roomName, def it) { it?.name?.toLowerCase() == roomName.toLowerCase() 
}

def _bat529Cb_cfcfe2bd_21(def roomIdentifier, def it) { it?.name?.toLowerCase() == roomIdentifier.toLowerCase() 
}

def _bat529Cb_cfcfe2bd_22(def roomName, def it) { it?.name?.toLowerCase() == roomName.toLowerCase() 
}

def _bat529Cb_cfcfe2bd_23(def args, def it) { it?.name?.toLowerCase() == args.room.trim().toLowerCase() 
}

def _bat529Cb_cfcfe2bd_24(def args, def it) { it?.name?.toLowerCase() == args.room.trim().toLowerCase() 
}

def _bat529Cb_cfcfe2bd_25(def newName, def room, def it) { it?.name?.toLowerCase() == newName.toLowerCase() && it.id != room.id 
}

def _bat529Cb_cfcfe2bd_26(def allDevices, def selectedIds, def cd) { if (!selectedIds.contains(cd.id.toString())) { allDevices.add(cd) } 
}

def _bat529Cb_cfcfe2bd_27(def states, def st) {
                    states.put(st.name, st.value)
                
}

def _bat529Cb_cfcfe2bd_28(def room) {
        [
            id: room.id?.toString(),
            name: room.name,
            deviceCount: room.deviceIds?.size() ?: 0,
            deviceIds: (room.deviceIds)?.collect((this.&_bat529Cb_cfcfe2bd_7))?: []
        ]
    
}

def _bat529Cb_cfcfe2bd_29(def it) { it.name 
}

def _bat529Cb_cfcfe2bd_30(def it) {
        def s = it?.toString()?.trim()
        if (!(s ==~ /\d+/)) {
            throw new IllegalArgumentException("deviceIds must be numeric device ids; got '${it}'. Use hub_list_devices to find device ids.")
        }
        s as Integer
    
}

def _bat529Cb_cfcfe2bd_31(def allDevices, def devices, def devId) {
        def device = (allDevices)?.find(((this.&_bat529Cb_cfcfe2bd_17).curry([devId] as Object[])))
        if (device) {
            def devInfo = [
                id: device.id.toString(),
                label: device.label ?: device.name ?: "unknown",
                name: device.name ?: "unknown"
            ]
            // Add common current states
            def states = [:]
            try {
                (device.currentStates)?.each(((this.&_bat529Cb_cfcfe2bd_27).curry([states] as Object[])))
            } catch (Exception e) {
                mcpLog("debug", "room", "hub_get_room: currentStates read failed for device ${device.id}: ${e.message}")
            }
            if (states) devInfo.currentStates = states
            devices << devInfo
        } else {
            devices << [id: devId.toString(), label: "(device not accessible via MCP)", name: "unknown", accessible: false]
        }
    
}
