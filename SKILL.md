---
name: hubitat-mcp-server
description: Guide for developing and maintaining the Hubitat MCP Rule Server — a Groovy-based MCP server running natively on Hubitat Elevation hubs, exposing 52+ tools for device control, rule automation, hub admin, and app/driver management.
license: MIT
---

# Hubitat MCP Rule Server — Development Skill

## Project Overview

This project is a **Model Context Protocol (MCP) server** implemented as a native **Hubitat Elevation** Groovy app. It consists of two files:

- **`hubitat-mcp-server.groovy`** — Parent app (MCP server, tool definitions, hub admin tools, helpers)
- **`hubitat-mcp-rule.groovy`** — Child app (individual automation rules with triggers, conditions, actions)

The server exposes an OAuth-secured HTTP endpoint that speaks JSON-RPC 2.0 per the MCP protocol specification. AI assistants connect to this endpoint and invoke tools to control devices, manage automation rules, query hub state, and administer the hub.

There are **no external dependencies, build steps, or test frameworks**. Everything runs inside the Hubitat Groovy sandbox. The two Groovy files plus `packageManifest.json` and `repository.json` (for Hubitat Package Manager distribution) are the entire project.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Hubitat Hub (Groovy Sandbox)                   │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  MCP Rule Server (parent app)             │  │
│  │  - OAuth endpoint: /apps/api/<id>/mcp     │  │
│  │  - JSON-RPC 2.0 handler                   │  │
│  │  - 52 tool definitions + dispatch         │  │
│  │  - Device access gate (selectedDevices)   │  │
│  │  - Hub Admin tools (internal API calls)   │  │
│  │  - Hub Security cookie auth               │  │
│  │  - Debug logging system                   │  │
│  │  - Version update checker                 │  │
│  ├───────────────────────────────────────────┤  │
│  │  MCP Rule (child app, one per rule)       │  │
│  │  - Trigger subscriptions & evaluation     │  │
│  │  - Condition evaluation                   │  │
│  │  - Action execution                       │  │
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
// ==================== TOOL DEFINITIONS ====================
// ==================== TOOL DISPATCH ====================
// ==================== DEVICE TOOL IMPLEMENTATIONS ====================
// ==================== RULE TOOLS (Child App Based) ====================
// ==================== VALIDATION ====================
// ==================== HELPER FUNCTIONS ====================
// ==================== HUB SECURITY & INTERNAL API HELPERS ====================
// ==================== MCP DEBUG LOGGING SYSTEM ====================
// ==================== HUB ADMIN READ TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN WRITE TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN APP/DRIVER MANAGEMENT ====================
// ==================== VERSION UPDATE CHECK ====================
```

New code should be placed in the appropriate section. New sections should follow the same `// ==== NAME ====` delimiter pattern.

### Adding a New Tool (Checklist)

Every new tool requires changes in exactly three places:

1. **Tool definition** in `getToolDefinitions()` — a map with `name`, `description`, and `inputSchema` (JSON Schema format)
2. **Case statement** in `executeTool()` — dispatches `toolName` to the implementation method
3. **Implementation method** — prefixed with `tool` (e.g., `toolMyNewTool`)

Additionally, update:
4. **`packageManifest.json`** — release notes with updated tool count
5. **Version strings** — if this is a version bump, update ALL version references (search for the current version string)

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

**No gate** — Device tools, rule tools, system tools. These operate only on user-selected devices and MCP-managed rules.

**`requireHubAdminRead()`** — Checks `settings.enableHubAdminRead` is true. Used for tools that read hub system info (hub details, health, app/driver lists, source code).

**`requireHubAdminWrite(args.confirm)`** — Three-layer check:
1. `settings.enableHubAdminWrite` must be true
2. `args.confirm` must be `true` (explicit confirmation parameter)
3. `state.lastBackupTimestamp` must be within the last hour

Exception: `toolCreateHubBackup` checks the first two directly (it IS the backup operation, so it can't require a prior backup).

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
| `lastBackupTimestamp` | Long | Last backup epoch ms (used by write safety gate) |
| `updateCheck` | Map | `{latestVersion, checkedAt, updateAvailable}` |

**Child app uses `atomicState`** for `triggers`, `conditions`, `actions` arrays. This is critical — `atomicState` provides immediate persistence and prevents race conditions when the parent creates a rule and immediately enables it. Regular `state` is used for counters and timestamps.

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
    return settings.selectedDevices?.find { it.id.toString() == deviceId.toString() }
}
```

Only devices in `settings.selectedDevices` are accessible. This is the security boundary — the user explicitly selects which devices to expose to MCP.

### Version Management

Version strings appear in multiple locations. When bumping the version, update ALL of these:
- File header comment
- `mainPage()` display paragraphs (2 locations)
- `handleInitialize()` response
- `toolExportRule()` serverVersion field
- `toolGetLoggingStatus()` version field
- `toolGenerateBugReport()` version variable
- `toolGetHubDetails()` mcpServerVersion field
- `currentVersion()` return value
- `packageManifest.json` version field

Search for the current version string to find all locations.

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

**Delete endpoints (GET):**
| Path | Purpose |
|------|---------|
| `/app/edit/deleteJsonSafe/<id>` | Delete app (returns JSON with `status: true`) |
| `/driver/editor/deleteJson/<id>` | Delete driver (returns JSON with `status: true`) |

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
3. **`state` vs `atomicState`** — use `atomicState` in the child app for rule data (triggers, conditions, actions) to prevent race conditions; use `state` for simple counters and timestamps
4. **Hub properties can throw** — always wrap `hub?.propertyName` in try/catch with `"unavailable"` fallback
5. **Hub internal API responses vary by firmware** — always handle both JSON and non-JSON responses with nested try/catch for parsing
6. **Numeric parsing of API responses** — hub endpoints like `/hub/advanced/freeOSMemory` return text that might not be numeric; wrap `as Integer` / `as Double` conversions in try/catch
7. **OAuth token** — created once in `initialize()` via `createAccessToken()` and stored in `state.accessToken`; never regenerate it or users lose their MCP endpoint URL
8. **Version strings in 9+ locations** — when bumping version, search for the current version string to find all locations
