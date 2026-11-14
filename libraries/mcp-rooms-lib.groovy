library(name: "McpRoomsLib", namespace: "mcp", author: "kingpanther13", description: "Room tools: hub_list_rooms, hub_get_room, hub_create_room, hub_update_room, hub_delete_room.")

// A room update reads the room and then saves it; holding this across both keeps one update from
// saving over another. It is not WRITE_RESERVATION_LOCK, which every write takes briefly to reserve
// its slot and must not wait on a hub request.
@groovy.transform.Field static final Object ROOM_UPDATE_LOCK = new Object()

def toolListRooms(args = null) {
    return _listRoomSummaries(args?.cursor)
}

// One room's devices, read from the Devices page.
def toolGetRoom(args) {
    def room = _requireRoom(args?.roomId).room
    List roomIds = (room.deviceIds ?: []).collect { it.toString() }
    Map pageById = [:]
    if (roomIds) {
        try {
            for (pageRec in _readDevicesPage(roomIds as Set)) pageById.put(pageRec.id, pageRec)
        } catch (IllegalStateException e) {
            return [success: false, error: "The hub's device list (/hub2/devicesList) could not be read: ${e.message}".toString(),
                    note: "Retry; if it persists check the hub's Devices page. See hub_get_tool_guide(section='rooms')."]
        }
    }
    Set authorized = _mcpAuthorizedIds(getChildDevices() ?: [])
    boolean redact = _redactsCredentials()
    def devices = []
    for (id in roomIds) {
        if (authorized != null && !authorized.contains(id)) {
            devices << [id: id, label: "(device not accessible via MCP)", name: "unknown", accessible: false]
            continue
        }
        Map rec = pageById.get(id)
        if (!rec) {
            devices << [id: id, label: "(device not on the hub's Devices page)", name: "unknown", accessible: true, metadataUnavailable: true]
            continue
        }
        def devInfo = [id: id, label: rec.label, name: rec.name]
        Map states = [:]
        for (st in rec.currentStates) states.put(st.key, _listStateValue(st.key, st.value, redact))
        if (states) devInfo.currentStates = states
        if ("currentStates" in rec.missingFields) devInfo.metadataUnavailable = true
        devices << devInfo
    }
    return [
        id: room.id?.toString(),
        name: room.name,
        deviceCount: devices.size(),
        devices: devices.sort { (it.label ?: "").toLowerCase() }
    ]
}

private Map _listRoomSummaries(cursor) {
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
    }.sort { (it.name ?: '').toLowerCase() }
    def paged = _paginateList(roomList, cursor, 100, "hub_list_rooms")
    def result = [rooms: paged.page, count: paged.page.size()]
    if (cursor != null) {
        result.total = roomList.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    return result
}

// getRooms() is the in-process room list, not a hub request. Every tool that takes a roomId checks
// it here first: GET /room/delete/<id> answers 200 for an unknown id, and /room/save answers success
// for one without changing anything.
private Map _requireRoom(roomId) {
    String rid = roomId?.toString()?.trim()
    if (!rid) throw new IllegalArgumentException("roomId is required: the room id from hub_list_rooms.")
    def rooms = (getRooms() ?: []).findAll { it != null }
    def room = rooms.find { it.id?.toString() == rid }
    if (!room) throw new IllegalArgumentException("No room has id '${rid}'. Call hub_list_rooms for room ids.")
    return [room: room, rooms: rooms]
}

private List _roomDeviceIds(deviceIds) {
    if (!(deviceIds instanceof List)) {
        throw new IllegalArgumentException("deviceIds must be an array of device ids, e.g. [\"12\",\"34\"].")
    }
    return deviceIds.collect {
        String s = it?.toString()?.trim()
        // The hub keys devices by int; a longer digit string would fail the conversion with a parser message.
        if (!(s ==~ /\d{1,10}/) || (s as Long) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("deviceIds must be numeric device ids; got '${it}'. Use hub_list_devices to find device ids.")
        }
        s as Integer
    }.unique()
}

// While the allowlist bypass is off, a room write may move only devices MCP may configure, the same
// boundary hub_update_device keeps. On update only ids whose membership changes are checked, so a
// rename or a re-send that keeps an unselected device in its room still works.
private void _requireRoomDevicesSelected(Collection ids) {
    if (!ids) return
    Set authorized = _mcpAuthorizedIds(getChildDevices() ?: [])
    if (authorized == null) return
    List outside = ids.collect { it.toString() }.unique().findAll { !authorized.contains(it) }
    if (outside) {
        throw new IllegalArgumentException("deviceIds ${outside.join(', ')} ${outside.size() == 1 ? 'is' : 'are'} outside the MCP device selection, so this room write cannot add or remove ${outside.size() == 1 ? 'it' : 'them'}. Select ${outside.size() == 1 ? 'it' : 'them'} in the MCP app's device list, or turn on Bypass Device Allowlist. A device hub_get_room shows with accessible=false stays in the list you send.")
    }
}

// Each listed device already in another room, with the room it leaves.
private List _roomMovedDevices(List rooms, Collection ids, String exceptRoomId) {
    List moved = []
    for (id in ids) {
        String did = id.toString()
        def from = rooms.find { r -> r.id?.toString() != exceptRoomId && r.deviceIds?.any { it.toString() == did } }
        if (from) moved << [deviceId: did, fromRoomId: from.id.toString(), fromRoom: from.name]
    }
    return moved
}

// The hub allows duplicate room names; the tools refuse them so a room name stays unambiguous.
private void _requireUniqueRoomName(List rooms, String name, exceptId) {
    if (rooms.find { it.name?.toLowerCase() == name.toLowerCase() && it.id?.toString() != exceptId?.toString() }) {
        throw new IllegalArgumentException("A room named '${name}' already exists.")
    }
}

private Map _roomFailure(String error, String note) {
    mcpLog("warn", "room", error)
    return [success: false, error: error,
            note: "${note} See hub_get_tool_guide(section='rooms').".toString()]
}

// One POST /room/save, the call the hub's Rooms page makes. The hub answers {roomId} on success
// and {error} (HTTP 200) for a bad device id. Returns [roomId] or [failure: <structured error>].
private Map _saveRoom(roomId, String name, List deviceIds, String action) {
    def parsed
    try {
        parsed = hubInternalPostJson("/room/save", groovy.json.JsonOutput.toJson([roomId: roomId, name: name, deviceIds: deviceIds]), 30)
    } catch (Exception e) {
        return [failure: _roomFailure("Could not ${action}: ${e.message}".toString(),
            "The hub may still have saved it; check with hub_list_rooms before retrying.")]
    }
    if (parsed instanceof Map && parsed.error) {
        return [failure: _roomFailure("The hub refused to ${action}: ${parsed.error}".toString(),
            "Nothing was saved. Check the device ids with hub_list_devices.")]
    }
    def savedId = (parsed instanceof Map && !parsed._unparseable) ? parsed.roomId : null
    if (savedId == null) {
        String body = (parsed instanceof Map && parsed._unparseable) ? parsed.message : (parsed == null ? "an empty body" : parsed.toString().take(200))
        return [failure: _roomFailure("The hub's answer to ${action} carried no roomId (${body}).".toString(),
            "The save may have landed; check with hub_list_rooms before retrying.")]
    }
    return [roomId: savedId.toString()]
}

def toolCreateRoom(args) {
    requireDestructiveConfirm(args.confirm)
    String name = args.name?.toString()?.trim()
    if (!name) throw new IllegalArgumentException("Room name is required")
    List deviceIds = args.deviceIds == null ? [] : _roomDeviceIds(args.deviceIds)
    List rooms = (getRooms() ?: []).findAll { it != null }
    _requireUniqueRoomName(rooms, name, null)
    _requireRoomDevicesSelected(deviceIds)
    List moved = _roomMovedDevices(rooms, deviceIds, null)

    def saved = _saveRoom(0, name, deviceIds, "create room '${name}'")
    if (saved.failure) return saved.failure
    mcpLog("info", "room", "Created room '${name}' (ID: ${saved.roomId})")
    Map created = [id: saved.roomId, name: name, deviceCount: deviceIds.size(), deviceIds: deviceIds.collect { it.toString() }]
    if (moved) created.movedDevices = moved
    return [success: true, room: created, message: "Room '${name}' created."]
}

// GET /room/updateName, the hub's rename that carries no device list, so it cannot undo a device
// change made meanwhile. The hub answers {success, message}. Returns null or a structured failure.
private Map _renameRoom(String roomId, String name) {
    def raw
    try {
        raw = hubInternalGet("/room/updateName", [id: roomId, name: name])
    } catch (Exception e) {
        return _roomFailure("Could not rename room ${roomId}: ${e.message}".toString(),
            "The hub may still have renamed it; check with hub_list_rooms before retrying.")
    }
    def parsed = null
    try {
        parsed = raw?.trim() ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception ignored) {
        parsed = null
    }
    if (parsed instanceof Map && parsed.success == true) return null
    if (parsed instanceof Map && parsed.success == false) {
        return _roomFailure("The hub refused to rename room ${roomId}: ${parsed.message ?: 'no reason given'}".toString(),
            "Nothing was saved.")
    }
    String body = raw?.trim() ? raw.toString().take(200) : "an empty body"
    return _roomFailure("The hub's answer to rename room ${roomId} carried no success flag (${body}).".toString(),
        "The rename may have landed; check with hub_list_rooms before retrying.")
}

def toolUpdateRoom(args) {
    requireDestructiveConfirm(args.confirm)
    boolean hasName = args.name != null
    boolean hasDevices = args.deviceIds != null
    if (!hasName && !hasDevices) throw new IllegalArgumentException("Pass name, deviceIds, or both.")
    String newName = hasName ? args.name.toString().trim() : null
    if (hasName && !newName) throw new IllegalArgumentException("name must not be blank.")
    List newIds = hasDevices ? _roomDeviceIds(args.deviceIds) : null
    synchronized (ROOM_UPDATE_LOCK) {
        return _updateRoomLocked(args.roomId, newName, newIds)
    }
}

// newName or newIds is null when that field keeps its value.
private Map _updateRoomLocked(roomIdArg, String newName, List newIds) {
    Map found = _requireRoom(roomIdArg)
    def room = found.room
    String roomId = room.id.toString()
    String oldName = room.name
    if (newName != null) _requireUniqueRoomName(found.rooms, newName, roomId)
    List currentIds = (room.deviceIds ?: []).collect { it as Integer }
    String finalName = newName ?: oldName
    Map updated = [id: roomId, name: finalName]
    if (finalName != oldName) updated.previousName = oldName
    if (newIds == null) {
        Map failure = _renameRoom(roomId, finalName)
        if (failure) return failure
    } else {
        List added = newIds - currentIds
        List dropped = currentIds - newIds
        _requireRoomDevicesSelected(added + dropped)
        List moved = _roomMovedDevices(found.rooms, added, roomId)
        def saved = _saveRoom(roomId as Integer, finalName, newIds, "update room ${roomId}")
        if (saved.failure) return saved.failure
        updated.deviceIds = newIds.collect { it.toString() }
        updated.deviceCount = newIds.size()
        if (dropped) updated.unassignedDeviceIds = dropped.collect { it.toString() }
        if (moved) updated.movedDevices = moved
    }
    mcpLog("info", "room", "Updated room ${roomId}: '${oldName}' -> '${finalName}'${newIds != null ? ", ${newIds.size()} devices" : ''}")
    return [success: true, room: updated, message: "Room '${finalName}' updated."]
}

def toolDeleteRoom(args) {
    requireDestructiveConfirm(args.confirm)
    def room = _requireRoom(args.roomId).room
    String roomId = room.id.toString()
    String roomName = room.name
    int deviceCount = room.deviceIds?.size() ?: 0

    // GET, as the hub's Rooms page does (POST answers 405); the hub answers an empty 200.
    try {
        hubInternalGet("/room/delete/${roomId}")
    } catch (Exception e) {
        return _roomFailure("Could not delete room '${roomName}' (${roomId}): ${e.message}".toString(),
            "Check with hub_list_rooms whether it is gone before retrying.")
    }

    mcpLog("info", "room", "Deleted room '${roomName}' (ID: ${roomId}), ${deviceCount} devices unassigned")
    return [
        success: true,
        deletedRoom: [id: roomId, name: roomName],
        devicesUnassigned: deviceCount,
        message: "Room '${roomName}' deleted. ${deviceCount} ${deviceCount == 1 ? 'device is' : 'devices are'} now unassigned."
    ]
}

def _getAllToolDefinitions_partRooms() {
    return [
        [
            name: "hub_list_rooms",
            description: "List rooms with id, name, deviceCount and deviceIds. Read-only. For one room's devices and their states use hub_get_room.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination. Omit for all; pass \"\" for the first page, then nextCursor (page size 100)."]
                ]
            ]
        ],
        [
            name: "hub_get_room",
            description: "Read one room by id: its name and each device's label and current states. Read-only.[[FLAT_TRIM]] A device outside the MCP device selection comes back with accessible=false and no states. See hub_get_tool_guide(section='rooms').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    roomId: [type: "string", description: "Room id from hub_list_rooms, e.g. \"5\""]
                ],
                required: ["roomId"]
            ]
        ],
        [
            name: "hub_create_room",
            description: "Create a room, optionally with devices (each moves out of its current room). Requires Write master, backup <24h, confirm=true.[[FLAT_TRIM]] Returns the new room's id, and movedDevices for devices taken from another room. Room names must be unique. Devices outside the MCP device selection are refused unless Bypass Device Allowlist is on. To move devices into an existing room use hub_update_room instead. See hub_get_tool_guide(section='rooms').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Name for the new room, e.g. \"Garage\""],
                    deviceIds: [type: "array", description: "Optional device ids, e.g. [\"12\",\"34\"].", items: [type: "string"]],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved creating this room."]
                ],
                required: ["name", "confirm"]
            ]
        ],
        [
            name: "hub_update_room",
            description: "Rename a room and/or replace its device list, by id; an omitted field keeps its value. deviceIds is the FULL list: a device left out becomes unassigned, an added one leaves its previous room. Requires Write master, backup <24h, confirm=true.[[FLAT_TRIM]] Send room updates one at a time, never in parallel. Start from hub_get_room(roomId) and keep its accessible=false devices in the list: adding or removing a device outside the MCP device selection is refused unless Bypass Device Allowlist is on. After a rename, automations or dashboards that name the room may need updating. See hub_get_tool_guide(section='rooms').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    roomId: [type: "string", description: "Room id, e.g. \"5\""],
                    name: [type: "string", description: "New room name"],
                    deviceIds: [type: "array", description: "Complete device list, e.g. [\"12\",\"34\"]; [] empties the room.", items: [type: "string"]],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved the change."]
                ],
                required: ["roomId", "confirm"]
            ]
        ],
        [
            name: "hub_delete_room",
            description: """⚠️ DESTRUCTIVE: Permanently deletes a room by id. Its devices become unassigned (not deleted). Requires Write master, backup <24h, confirm=true.[[FLAT_TRIM]]

PRE-FLIGHT: 1) Backup <24h 2) Verify the room with hub_get_room(roomId) 3) List affected devices to user 4) Get explicit confirmation 5) Set confirm=true[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    roomId: [type: "string", description: "Room id, e.g. \"5\""],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user explicitly approved the deletion."]
                ],
                required: ["roomId", "confirm"]
            ]
        ]
    ]
}

def _readOnlyToolNames_partRooms() {
    return ["hub_list_rooms", "hub_get_room"]
}

def _idempotentWriteToolNames_partRooms() {
    // hub_update_room sets the name and device list wholesale, so a repeat lands the same state.
    // A repeat hub_delete_room is refused before any request (the id is no longer a room).
    return ["hub_update_room", "hub_delete_room"]
}

def _toolDisplayMeta_partRooms() {
    return [
        hub_list_rooms: [title: "List Rooms", summary: "List all rooms with their ids, names, and device ids."],
        hub_get_room: [title: "Get Room Details", summary: "Get one room's details with its devices and their current states."],
        hub_create_room: [title: "Create Room", summary: "Create a new room, optionally assigning devices."],
        hub_update_room: [title: "Update Room", summary: "Rename a room or replace its device list."],
        hub_delete_room: [title: "Delete Room", summary: "Permanently delete a room."]
    ]
}
