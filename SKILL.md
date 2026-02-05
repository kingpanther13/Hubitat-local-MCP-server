---
name: hubitat-mcp-server
description: Guide for developing and maintaining the Hubitat MCP Rule Server — a Groovy-based MCP server running natively on Hubitat Elevation hubs, exposing 74 tools for device control, virtual device management, room management, rule automation, hub admin, file management, and app/driver management.
license: MIT
---

# Hubitat MCP Rule Server — Development Skill

## Project Overview

This project is a **Model Context Protocol (MCP) server** implemented as a native **Hubitat Elevation** Groovy app. It consists of two files:

- **`hubitat-mcp-server.groovy`** — Parent app (MCP server, tool definitions, hub admin tools, helpers)
- **`hubitat-mcp-rule.groovy`** — Child app (individual automation rules with triggers, conditions, actions)

The server exposes an OAuth-secured HTTP endpoint that speaks JSON-RPC 2.0 per the MCP protocol specification. AI assistants connect to this endpoint and invoke tools to control devices, manage automation rules, query hub state, and administer the hub.

There are **no external dependencies, build steps, or test frameworks**. Everything runs inside the Hubitat Groovy sandbox. The two Groovy files plus `packageManifest.json` and `repository.json` (for Hubitat Package Manager distribution) are the entire project.

**Documentation files:**
- `README.md` — User-facing documentation
- `SKILL.md` — Developer reference (this file)
- `TOOL_GUIDE.md` — Human-readable tool reference (same content available to AI via `get_tool_guide` MCP tool)

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Hubitat Hub (Groovy Sandbox)                   │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  MCP Rule Server (parent app)             │  │
│  │  - OAuth endpoint: /apps/api/<id>/mcp     │  │
│  │  - JSON-RPC 2.0 handler                   │  │
│  │  - 74 tool definitions + dispatch         │  │
│  │  - Device access gate (selectedDevices)   │  │
│  │  - Hub Admin tools (internal API calls)   │  │
│  │  - Hub Security cookie auth               │  │
│  │  - Virtual device mgmt (child devices)    │  │
│  │  - Debug logging system                   │  │
│  │  - Version update checker                 │  │
│  ├───────────────────────────────────────────┤  │
│  │  MCP Rule (child app, one per rule)       │  │
│  │  - Trigger subscriptions & evaluation     │  │
│  │  - Condition evaluation                   │  │
│  │  - Action execution                       │  │
│  │  - Execution loop guard (auto-disable)    │  │
│  │  - Isolated settings & state per rule     │  │
│  └───────────────────────────────────────────┘  │
│                                                 │
│  Hub Internal API (http://127.0.0.1:8080)       │
│  - /hub/advanced/* (memory, temp, db size)      │
│  - /hub2/* (apps, drivers, zwave, zigbee)       │
│  - /app/ajax/*, /driver/ajax/* (code mgmt)      │
│  - /hub/backup, /hub/reboot, /hub/shutdown      │
└─────────────────────────────────────────────────┘
```

## Key Conventions

### File Structure (Section Comments)

The server file is organized with prominent section delimiters:
```groovy
// ==================== APP LIFECYCLE ====================
// ==================== MCP REQUEST HANDLERS ====================
// ==================== DEVICE TOOLS ====================
// ==================== RULE TOOLS (Child App Based) ====================
// ==================== RULE EXPORT/IMPORT/CLONE TOOLS ====================
// ==================== EXPORT/IMPORT HELPERS ====================
// ==================== SYSTEM TOOLS ====================
// ==================== CAPTURED STATE TOOLS ====================
// ==================== VALIDATION FUNCTIONS ====================
// ==================== HELPER FUNCTIONS ====================
// ==================== HUB SECURITY & INTERNAL API HELPERS ====================
// ==================== ITEM BACKUP TOOLS ====================
// ==================== FILE MANAGER TOOLS ====================
// ==================== MCP DEBUG LOGGING SYSTEM ====================
// ==================== DEBUG TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN READ TOOL IMPLEMENTATIONS ====================
// ==================== MONITORING TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN WRITE TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN APP/DRIVER MANAGEMENT ====================
// ==================== DEVICE ADMIN TOOL IMPLEMENTATIONS ====================
// ==================== VIRTUAL DEVICE MANAGEMENT TOOL IMPLEMENTATIONS ====================
// ==================== ROOM MANAGEMENT ====================
// ==================== VERSION UPDATE CHECK ====================
// ==================== TOOL GUIDE ====================
```

New code should be placed in the appropriate section. New sections should follow the same `// ==== NAME ====` delimiter pattern.

### Adding a New Tool (Checklist)

Every new tool requires changes in exactly three places:

1. **Tool definition** in `getToolDefinitions()` — a map with `name`, `description`, and `inputSchema` (JSON Schema format)
2. **Case statement** in `executeTool()` — dispatches `toolName` to the implementation method
3. **Implementation method** — prefixed with `tool` (e.g., `toolMyNewTool`)

Additionally, update tool count in:
4. **`packageManifest.json`** — version field + releaseNotes with updated tool count
5. **`README.md`** — "MCP Tools (N total)" header + category table + version history
6. **`SKILL.md`** — description frontmatter + architecture diagram tool count
7. **Version strings** — if this is a version bump, update ALL version references (search for the current version string)

### Tool Definition Pattern

```groovy
[
    name: "tool_name",
    description: """Concise first line describing what the tool does.

Additional guidance for the AI on when/how to use this tool.
Include performance notes, pagination guidance, or behavioral expectations.

For write tools, include safety warnings and mandatory pre-flight checklists.""",
    inputSchema: [
        type: "object",
        properties: [
            requiredParam: [type: "string", description: "What this param is"],
            optionalParam: [type: "boolean", description: "What this does", default: false],
            enumParam: [type: "string", enum: ["value1", "value2"], description: "Allowed values"]
        ],
        required: ["requiredParam"]
    ]
]
```

Rules:
- `inputSchema` root is always `type: "object"` with `properties`
- `required` array is only present when there are required params
- No-argument tools use `properties: [:]`
- Descriptions should include usage guidance for the AI (this text is what the LLM sees when deciding which tool to call)
- Write tools must have strong safety warnings in their descriptions with mandatory pre-flight checklists

### executeTool() Dispatch Pattern

```groovy
case "tool_name": return toolMyNewTool(args)
```

- Arguments are destructured from `args` inline: `return toolFoo(args.deviceId, args.offset ?: 0)`
- Some tools pass the entire `args` map: `return toolBar(args)`
- Default values use the Elvis operator `?:` inline
- Tools are grouped by category with comment headers

### Tool Implementation Pattern

```groovy
def toolMyNewTool(args) {
    // 1. Safety gate (if hub admin tool)
    requireHubAdminRead()        // or requireHubAdminWrite(args.confirm)

    // 2. Input validation
    if (!args.requiredParam) throw new IllegalArgumentException("requiredParam is required")

    // 3. Implementation with error handling
    try {
        // ... do work ...
        mcpLog("info", "component-name", "Description of what happened")
        return [
            success: true,
            // ... result fields ...
        ]
    } catch (Exception e) {
        mcpLog("error", "component-name", "What failed: ${e.message}")
        return [
            success: false,
            error: "Human-readable error: ${e.message}",
            note: "Actionable guidance for the user or AI"
        ]
    }
}
```

Conventions:
- **Validation errors**: Throw `IllegalArgumentException` with descriptive message (caught by `handleToolsCall` as JSON-RPC `-32602`)
- **Runtime errors**: Return `[success: false, error: ..., note: ...]` maps (don't throw, so the AI gets useful error info)
- **Device IDs**: Always compare and return as strings: `device.id.toString()`
- **Logging**: Use `mcpLog(level, component, message)` for MCP-accessible logs
- **Hub properties**: Wrap in individual try/catch with `"unavailable"` fallback (hub properties can throw depending on firmware)

### Safety Gate Pattern

Three tiers of access control:

**No gate** — Device tools, rule tools, system tools, `list_virtual_devices`, `get_tool_guide`. These operate only on user-selected devices, MCP-managed child devices (virtual devices), MCP-managed rules, or return static reference content.

**`requireHubAdminRead()`** — Checks `settings.enableHubAdminRead` is true. Used for tools that read hub system info (hub details, health, app/driver lists, source code).

**`requireHubAdminWrite(args.confirm)`** — Three-layer check:
1. `settings.enableHubAdminWrite` must be true
2. `args.confirm` must be `true` (explicit confirmation parameter)
3. `state.lastBackupTimestamp` must be within the last 24 hours

Exception: `toolCreateHubBackup` checks the first two directly (it IS the backup operation, so it can't require a prior backup).

**`backupItemSource(type, id)`** — Automatic item-level backup for modify/delete operations:
- Called by `update_app_code`, `update_driver_code`, `delete_app`, `delete_driver` before making changes
- Fetches current source code and saves as a `.groovy` file in the hub's local File Manager via `uploadHubFile()`
- Metadata (type, id, version, timestamp, fileName, sourceLength) stored in `state.itemBackupManifest` keyed by `"app_<id>"` or `"driver_<id>"`
- 1-hour window: if a backup of the same item exists within the last hour, it is kept (preserves the pre-edit original across a series of edits)
- Prunes to max 20 entries; oldest file deleted via `deleteHubFile()` when limit exceeded
- No size limit — full source always stored (File Manager has ~1GB capacity)
- Not needed for install tools (nothing to lose when creating new)
- Files persist even if MCP app is uninstalled; accessible at `http://<HUB_IP>/local/<filename>`
- Requires firmware ≥2.3.4.132 for `uploadHubFile()` support

**Item Backup Tools** (3 tools, always available without Hub Admin Read/Write):
- `list_item_backups` — lists all backups with metadata (type, id, version, age, size) and direct download URLs
- `get_item_backup` — retrieves full source code from a backup via `downloadHubFile()` by key (e.g., `app_123`); returns source inline for files ≤60KB, otherwise provides download URL
- `restore_item_backup` — reads backup via `downloadHubFile()` and pushes source back to the hub via `update_app_code`/`update_driver_code` (requires Hub Admin Write); removes manifest entry first so the current code gets backed up during restore; on failure, puts the manifest entry back
- Every tool response includes `howToRestore` and `manualRestore` instructions for user recovery without MCP
- All operations are fully local — no cloud involvement

**File Manager Tools** (4 tools):
- `list_files` — lists all files via `/hub/fileManager/json` internal API endpoint; always available, no access gate
- `read_file` — reads file via `downloadHubFile()`; returns content inline for files ≤60KB, otherwise provides download URL; always available
- `write_file` — writes via `uploadHubFile()`; requires Hub Admin Write + confirm; automatically backs up existing file before overwriting (backup named `<original>_backup_<timestamp>.<ext>`)
- `delete_file` — deletes via `deleteHubFile()`; requires Hub Admin Write + confirm; automatically backs up file before deletion
- File name validation: must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no spaces, no leading period)

**Device Authorization Safety** (v0.7.2+):
- Device tools require AI to confirm before using non-exact device matches
- If user specifies an exact device name that matches, AI can use it directly
- If no exact match: AI must suggest similar devices and **ask user to confirm** before using any of them
- When a tool fails (e.g., `create_virtual_device`), AI must report the failure — not silently use existing devices as a workaround
- This prevents accidentally controlling critical systems (HVAC, locks) when user meant a different device
- The `delete_device` tool has its own extensive safety checklist (requires recent backup, explicit confirmation, audit logging)

**Optimized Tool Descriptions** (v0.7.2+):
- Tool descriptions reduced by ~387 lines for better token efficiency
- Based on MCP best practices from Anthropic and modelcontextprotocol.io
- All critical safety rules preserved: pre-flight checklists, confirm requirements, backup requirements
- Descriptions follow "explain like to a new hire" principle — concise but complete
- Reduces context consumption when 74 tools are loaded into AI context
- New `get_tool_guide` tool provides detailed reference on-demand (embedded in server, accessible via MCP)

**Rule Deletion Safety** (delete_rule):
- Automatically backs up rule to File Manager before deletion as `mcp_rule_backup_<name>_<timestamp>.json`
- Backup includes full rule export (triggers, conditions, actions, device manifest)
- Restore via: `read_file(fileName)` → `import_rule(exportData: <json>)`
- **Test rules**: Set `testRule: true` in `create_rule` or `update_rule` to skip backup on deletion
- `skipBackupCheck: true` parameter forces skip regardless of testRule flag (rarely needed)
- Test rule flag visible in `get_rule` and `list_rules` responses
- No Hub Admin Write required (rules are MCP-managed, not hub-level resources)

### Hub Internal API Helpers

Three helpers for calling the hub's internal HTTP API at `http://127.0.0.1:8080`:

| Helper | Use Case | Content Type |
|--------|----------|--------------|
| `hubInternalGet(path, query?, timeout?)` | Read endpoints (default 30s timeout), returns response text | text |
| `hubInternalPost(path, body?)` | Simple POST endpoints (reboot, shutdown, zwave repair) | text |
| `hubInternalPostForm(path, body, timeout?)` | Form-encoded POST (app/driver install/update) | `application/x-www-form-urlencoded` |

All three:
- Call `getHubSecurityCookie()` and attach cookie header if Hub Security is enabled
- Clear the cached cookie on 401/403 errors for automatic re-auth on next call
- Use `ignoreSSLIssues: true`

### Hub Security Cookie Pattern

When Hub Security is enabled on the hub, internal API calls require authentication:

```groovy
def getHubSecurityCookie() {
    // 1. Return null if Hub Security not enabled
    // 2. Return cached cookie if not expired (30-minute cache)
    // 3. POST to /login with username/password
    // 4. Extract cookie from Set-Cookie header
    // 5. Cache with 30-minute expiry
}
```

The cookie is cached in `state.hubSecurityCookie` with expiry in `state.hubSecurityCookieExpiry`.

### State Management

**Parent app `state.*`:**
| Key | Type | Purpose |
|-----|------|---------|
| `accessToken` | String | OAuth token for MCP endpoint |
| `ruleVariables` | Map | Global variables shared across rules |
| `capturedDeviceStates` | Map | Snapshots of device states |
| `debugLogs` | Map | `{entries: [], config: {logLevel, maxEntries}}` circular buffer |
| `hubSecurityCookie` | String | Cached auth cookie |
| `hubSecurityCookieExpiry` | Long | Cookie expiry epoch ms |
| `lastBackupTimestamp` | Long | Last hub backup epoch ms (24-hour write safety gate) |
| `itemBackupManifest` | Map | Metadata for source code backups stored in File Manager, keyed by `"app_<id>"` / `"driver_<id>"`, max 20 entries |
| `updateCheck` | Map | `{latestVersion, checkedAt, updateAvailable}` |

**Child app uses `atomicState`** for `triggers`, `conditions`, `actions`, `localVariables`, `durationTimers`, `durationFired`, and `cancelledDelayIds`. This is critical — `atomicState` provides immediate persistence and prevents race conditions when scheduled callbacks (`runIn`) fire in separate execution contexts. Always use read-modify-write pattern with atomicState maps:
```groovy
def timers = atomicState.durationTimers ?: [:]
timers[key] = value
atomicState.durationTimers = timers  // Write back entire map
```
Direct nested mutation (`atomicState.map[key] = value`) silently fails to persist. Regular `state` is used for UI editor state, counters, and timestamps. `cancelledDelayIds` is cleared on `initialize()` since `unschedule()` in `updated()` cancels all pending callbacks.

**Execution Loop Guard** — `executeRule()` tracks recent execution timestamps in `atomicState.recentExecutions`. If a rule fires 10+ times within a 60-second sliding window, it auto-disables (`ruleEnabled = false`), unsubscribes from events, and unschedules all timers. This prevents infinite event loops (e.g., "trigger on Switch A on → action: turn on Switch A") from crashing the hub. The rule must be manually re-enabled after fixing the loop. The guard uses `atomicState` for immediate persistence across rapid-fire event handlers.

### Parent-Child Communication

```groovy
// Parent -> Child
childApp.getRuleData()              // Get full rule structure
childApp.testRuleFromParent()       // Dry-run test
childApp.updateRuleFromParent(args) // Update rule data
childApp.getSetting("ruleName")     // Access child settings

// Child -> Parent
parent.getSelectedDevices()         // Access device list
parent.findDevice(deviceId)         // Device lookup
```

### Device Access

All device access goes through `findDevice(deviceId)`:
```groovy
def findDevice(deviceId) {
    if (!deviceId) return null
    // Search selected devices first, then MCP-managed child devices (virtual devices)
    def device = settings.selectedDevices?.find { it.id.toString() == deviceId.toString() }
    if (!device) {
        device = getChildDevices()?.find { it.id.toString() == deviceId.toString() }
    }
    return device
}
```

Devices are accessible from two sources:
1. **`settings.selectedDevices`** — the user explicitly selects which physical/existing devices to expose to MCP (security boundary)
2. **`getChildDevices()`** — MCP-managed virtual devices created via `create_virtual_device` are automatically accessible without manual selection

`list_devices` also combines both sources (deduplicating by ID) and marks child devices with `mcpManaged: true`.

### Virtual Device Management

Virtual devices are created as **child devices** of the MCP Rule Server app using `addChildDevice()` — the officially supported Hubitat API. Key design:

- **`addChildDevice("hubitat", driverName, dni, null, [name: ..., label: ..., isComponent: false])`** — creates a device using a built-in Hubitat driver (5-argument form with `null` hub ID for cross-firmware compatibility)
- **`isComponent: false`** — device appears independently in the Hubitat UI, can be edited/deleted, and can be shared with other apps (Maker API, Dashboard, Rule Machine, HA, etc.)
- **`getChildDevices()`** returns only child *devices* (not child apps/rules — those use `getChildApps()`)
- **`deleteChildDevice(dni)`** removes by device network ID
- Auto-generated DNIs use format `mcp-virtual-<hex-timestamp>-<hex-random>` with retry logic to avoid collisions
- Supports 15 built-in virtual device types: Virtual Switch, Virtual Button, Virtual Contact Sensor, Virtual Motion Sensor, Virtual Presence Sensor, Virtual Lock, Virtual Temperature Sensor, Virtual Humidity Sensor, Virtual Dimmer, Virtual RGBW Light, Virtual Shade, Virtual Garage Door Opener, Virtual Water Sensor, Virtual Omni Sensor, Virtual Fan Controller
- Requires Hub Admin Write access (with backup verification) for create/delete operations

#### update_device Tool

The `update_device` tool modifies properties on any accessible device (selected or MCP-managed). It accepts all parameters as optional — only specified fields are changed:

- **label** — `device.setLabel(value)` (official API)
- **name** — `device.setName(value)` (official API)
- **deviceNetworkId** — `device.setDeviceNetworkId(value)` (official API)
- **dataValues** — `device.updateDataValue(key, value)` for each entry (official API)
- **preferences** — `device.updateSetting(key, [type: type, value: value])` for each entry (official API, requires `type` field: `bool`, `number`, `decimal`, `text`, `enum`, `time`, `hub`)
- **room** — resolves room name → ID via `getRooms()` (case-insensitive), then POSTs JSON to `/room/save` with `roomId`, `name`, and `deviceIds` fields. Uses **safe move pattern**: adds device to new room first, then removes from old room. This prevents "device limbo" (device in no room) if the second API call fails — worst case, device appears in both rooms temporarily, which is recoverable. Uses `Content-Type: application/json` (NOT form-encoded — the endpoint returns 500 with form data). The API field is `roomId` (not `id` — using `id` returns `{"roomId":null,"error":"Invalid room id"}`). Post-save verification via `getRooms()`. Requires Hub Admin Write.
- **enabled** — POSTs to `/device/disable` with `id` and `disable` as body params (undocumented API, must be POST not GET; requires Hub Admin Write)

Room assignment and enable/disable use the hub's internal API at `http://127.0.0.1:8080` and require Hub Admin Write safety gate confirmation. All other properties use the official Hubitat Groovy API and work on any accessible device. Driver type cannot be changed — must delete and recreate the device.

### Room Management

5 tools for full room CRUD, using `POST /room/save` (JSON) and `getRooms()` for verification:

| Tool | Access Gate | Description |
|------|------------|-------------|
| `list_rooms` | None | Lists all rooms with IDs, names, device counts via `getRooms()` |
| `get_room` | None | Room details with full device info/states. Accepts name (case-insensitive) or ID |
| `create_room` | Hub Admin Write | Creates room via `POST /room/save` with `roomId: 0` (Grails create convention) |
| `delete_room` | Hub Admin Write | Deletes room via `POST /room/delete/<id>` or `GET /room/delete/<id>`. Devices become unassigned |
| `rename_room` | Hub Admin Write | Renames room via `POST /room/save` with existing `roomId` and new `name`. Preserves device assignments |

**Key API details:**
- All room mutations use `POST /room/save` at `http://127.0.0.1:8080` with `Content-Type: application/json`
- The JSON body uses `roomId` (not `id`) — the API returns `{"roomId":null,"error":"Invalid room id"}` if `id` is used
- Body format: `{"roomId": <int>, "name": "<string>", "deviceIds": [<int>, ...]}`
- For room creation, `roomId: 0` triggers create behavior (Grails convention)
- `getRooms()` is a built-in Hubitat SDK method returning `[[id:1, name:"Bedroom", deviceIds:[5, 6]], ...]`
- All write tools verify the operation via `getRooms()` after the API call
- Form-encoded bodies return 500 — the endpoint strictly requires JSON

### Version Management

Version strings appear in multiple locations. When bumping the version, update ALL of these:
- File header comment (`hubitat-mcp-server.groovy` line 7)
- `mainPage()` display paragraphs (2 locations: version display + update banner)
- `handleInitialize()` response
- `toolExportRule()` serverVersion field
- `toolImportRule()` serverVersion field (for imported rule metadata)
- `toolGetLoggingStatus()` version field
- `toolGenerateBugReport()` version variable
- `toolGetHubDetails()` mcpServerVersion field
- `currentVersion()` return value
- `packageManifest.json` version field + releaseNotes
- `README.md` — "New in vX.Y.Z" section + Version History table
- `SKILL.md` — description frontmatter tool count + architecture diagram tool count
- `hubitat-mcp-rule.groovy` — file header comment version

Search for the current version string across all files to find all locations.

### Groovy/Hubitat Idioms

- **Elvis operator** `?:` for defaults: `args.offset ?: 0`
- **Safe navigation** `?.` everywhere: `device.capabilities?.collect { it.name }`
- **Dynamic method invocation**: `device."${command}"(*convertedParams)` with spread operator
- **`settings.*`** for user-configured preferences (persisted by Hubitat framework)
- **`state.*`** for persistent app-level storage
- **`atomicState.*`** for thread-safe persistent storage
- **`location.*`** for hub-level info (modes, timezone, HSM, etc.)
- **`httpGet`/`httpPost`** for synchronous HTTP; `asynchttpGet` for async
- **`subscribe(device, attribute, handler)`** for device event subscriptions
- **`schedule(cron, method)`** / `runIn(seconds, method)` for scheduling
- **`render(contentType: "application/json", data: jsonString)`** for HTTP responses
- **`groovy.json.JsonOutput.toJson()`** and **`new groovy.json.JsonSlurper().parseText()`** for JSON

### Hubitat Sandbox Limitations

The Hubitat Groovy sandbox restricts:
- No `Eval.me()` or dynamic code evaluation
- No file system access
- No arbitrary network access (only `httpGet`/`httpPost` to allowed destinations)
- No thread creation
- Limited class imports (no `java.io`, limited `java.net`)
- `state` writes are eventually consistent; `atomicState` is immediately consistent
- Hub properties (e.g., `hub.uptime`, `hub.zigbeeChannel`) can throw exceptions on some firmware versions — always wrap in try/catch

### Hub Internal API Endpoints Reference

These are undocumented endpoints on the Hubitat hub at `http://127.0.0.1:8080`:

**Read endpoints (GET):**
| Path | Returns |
|------|---------|
| `/hub/advanced/freeOSMemory` | Free memory in KB (text) |
| `/hub/advanced/internalTempCelsius` | CPU temperature (text) |
| `/hub/advanced/databaseSize` | Database size in KB (text) |
| `/hub2/userAppTypes` | Installed user apps (JSON array: id, name, namespace) |
| `/hub2/userDeviceTypes` | Installed user drivers (JSON array: id, name, namespace) |
| `/hub2/zwaveInfo` | Z-Wave radio details (JSON) |
| `/hub2/zigbeeInfo` | Zigbee radio details (JSON) |
| `/app/ajax/code` with query `id=<id>` | App source code (JSON: source, version, status) |
| `/driver/ajax/code` with query `id=<id>` | Driver source code (JSON: source, version, status) |
| `/hub/backupDB` with query `fileName=latest` | Creates fresh backup and returns .lzf binary |
| `/hub/fileManager/json` | Lists all files in File Manager (JSON array: name, size, date) |
| `/hub2/roomsList` | List of rooms as JSON (alternative to `getRooms()` SDK method) |

**Write endpoints (POST):**
| Path | Body | Purpose |
|------|------|---------|
| `/hub/reboot` | none | Reboot hub |
| `/hub/shutdown` | none | Shutdown hub |
| `/hub/zwaveRepair` | none | Start Z-Wave network repair |
| `/app/save` | `id="", version="", create="", source=<code>` | Install new app |
| `/driver/save` | `id="", version="", create="", source=<code>` | Install new driver |
| `/app/ajax/update` | `id=<id>, version=<ver>, source=<code>` | Update app code |
| `/driver/ajax/update` | `id=<id>, version=<ver>, source=<code>` | Update driver code |
| `/login` | `username=<u>, password=<p>, submit=Login` | Hub Security login |
| `/device/save` | `id=<deviceId>, label=<label>, name=<name>, deviceNetworkId=<dni>, type.id=<typeId>` | Update device properties (flat field names, Grails convention). NOTE: silently ignores `roomId` — use `/room/save` instead |
| `/device/disable` | `id=<deviceId>, disable=<true\|false>` | Enable or disable a device (MUST be POST, not GET) |
| `/room/save` | JSON: `{"roomId": <int>, "name": "<str>", "deviceIds": [<int>,...]}` | Create (roomId=0) or update room. MUST use `Content-Type: application/json` — form-encoded returns 500. Field is `roomId` not `id` |

**Delete endpoints (GET or POST):**
| Path | Method | Purpose |
|------|--------|---------|
| `/app/edit/deleteJsonSafe/<id>` | GET | Delete app (returns JSON with `status: true`) |
| `/driver/editor/deleteJson/<id>` | GET | Delete driver (returns JSON with `status: true`) |
| `/room/delete/<roomId>` | POST or GET | Delete room (try POST first, fall back to GET) |

### MCP Protocol Implementation

The server implements MCP protocol version `2024-11-05`:
- **Transport**: HTTP with OAuth access token (`?access_token=<token>`)
- **Format**: JSON-RPC 2.0 (supports batch requests)
- **Methods**: `initialize`, `tools/list`, `tools/call`, `ping`
- **Notifications**: Handled silently (HTTP 204)
- **Error codes**: `-32700` (parse), `-32600` (invalid request), `-32601` (method not found), `-32602` (invalid params from `IllegalArgumentException`), `-32603` (internal error)

### Common Pitfalls

1. **Don't forget trailing commas** in `getToolDefinitions()` — it's a Groovy list literal, so every tool definition except the last needs a trailing comma after its closing `]`
2. **Device IDs are strings in MCP but integers internally** — always use `.toString()` for comparison and return values
3. **`state` vs `atomicState`** — use `atomicState` in the child app for rule data (triggers, conditions, actions) and cross-execution state (durationTimers, durationFired, cancelledDelayIds, localVariables); always use read-modify-write pattern for nested maps; use `state` only for UI editor state, counters, and timestamps
4. **Hub properties can throw** — always wrap `hub?.propertyName` in try/catch with `"unavailable"` fallback
5. **Hub internal API responses vary by firmware** — always handle both JSON and non-JSON responses with nested try/catch for parsing
6. **Numeric parsing of API responses** — hub endpoints like `/hub/advanced/freeOSMemory` return text that might not be numeric; wrap `as Integer` / `as Double` conversions in try/catch
7. **OAuth token** — created once in `initialize()` via `createAccessToken()` and stored in `state.accessToken`; never regenerate it or users lose their MCP endpoint URL
8. **Version strings in 9+ locations** — when bumping version, search for the current version string to find all locations across `hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`, `packageManifest.json`, `README.md`, and `SKILL.md`
9. **Date/timestamp parsing** — `formatTimestamp()` tries 6 ISO 8601 format variations (with/without millis, with Z/offset/no timezone, space-separated) to handle differences across firmware versions and upstream APIs. Falls back to truncated raw string if no format matches. Never use a single strict `Date.parse()` format

## Future Plans (Blue-Sky — Needs Research)

These are speculative feature ideas that need feasibility research before implementation. When the user asks "what should I work on next?" or similar, reference this list.

### HPM Integration
- Search HPM repositories for packages by keyword
- Install/uninstall packages via HPM programmatically
- Check for updates across all installed packages

### App/Integration Discovery (Outside HPM)
- Search for and install official Hubitat integrations not yet enabled
- Discover and install community apps/drivers from GitHub, forums, etc.

### Dashboard Management
- Create, modify, delete dashboards programmatically
- Prefer official Hubitat dashboards (home screen + mobile app visibility)
- If official API isn't available, explore alternatives that can be set as defaults

### Rule Machine Interoperability
- Read native RM rules via their export/import/clone UI mechanism (API unknown)
- Export MCP rules in RM-importable format
- Import directly into native Rule Machine if API exists
- Bidirectional sync between MCP rules and RM rules (long-shot)

### Additional Ideas
- Device creation — *partially implemented in v0.6.0* (MCP-managed child virtual devices via `addChildDevice()`). Future: create standalone virtual devices via hub internal API (`POST /device/save`) that appear in the regular Devices section independent of the MCP app
- Device pairing assistance (Z-Wave inclusion, Zigbee pairing, cloud device setup)
- Notification/alert management (granular routing)
- Scene management (create/modify/manage beyond activate_scene)
- Energy monitoring aggregation and reports
- Scheduled automated reports (hub health, device status, rule history)
