# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub, with a built-in custom rule engine.

> **BETA SOFTWARE**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress and may have bugs, edge cases, or issues. Use at your own risk. Contributions and bug reports welcome!
>
> **Found a bug?** Please report issues at [GitHub Issues](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues). For easier bug reporting:
> 1. Set debug log level: Settings → MCP Debug Log Level → "Debug", or ask your AI to `set_log_level` to "debug"
> 2. Reproduce the issue
> 3. Ask your AI to use the `generate_bug_report` tool - it will gather diagnostics and format a ready-to-submit report

## What is this?

This Hubitat app exposes an MCP server that allows AI assistants (like Claude) to:
- **Control devices** - Turn lights on/off, set levels, trigger scenes
- **Create automations** - Build rules with triggers, conditions, and actions
- **Manage rooms** - List, create, delete, and rename rooms; assign devices to rooms
- **Query system state** - Get device status, hub info, modes, variables, HSM status
- **Administer the hub** - View hub health, manage apps/drivers, create backups, and more

**New in v0.7.1:**
- **Automatic backup for `delete_rule`** — Rules are automatically backed up to File Manager (`mcp_rule_backup_*.json`) before deletion. Restore via `read_file` + `import_rule`.
- **Test rule flag** — Mark rules as `testRule: true` in `create_rule` or `update_rule` to skip backup on deletion. Use for temporary/experimental rules.
- **Bug fixes and code quality improvements:**
  - Add bounds checking to time trigger format parsing (prevents crash on malformed HH:mm)
  - Add null validation for `set_mode` and `set_hsm` actions (log error instead of crash)
  - Add try-catch to button handler integer parsing (prevents crash on invalid input)
  - Fix version header description (was showing v0.6.13 features)

**New in v0.7.0:**
- **Room management tools** — 5 new tools for full room CRUD:
  - `list_rooms` — list all rooms with device counts
  - `get_room` — get room details with all device info and current states
  - `create_room` — create new rooms (optionally assign devices at creation)
  - `delete_room` — delete rooms (devices become unassigned)
  - `rename_room` — rename existing rooms
- **Room assignment fixed** — `update_device` room property now works reliably via `POST /room/save` with JSON
- 73 MCP tools total (up from 68 in v0.6.x)

**New in v0.6.15:**
- **Room assignment fix** — API response `{"roomId":null,"error":"Invalid room id"}` revealed the field name is `roomId` not `id`. Now sends correct JSON body to `POST /room/save`. Also removes device from old room before adding to new room.

**New in v0.6.14:**
- **Room assignment** — `/room/save` returned 500 (endpoint exists, wrong format). Now tries `POST /room/save` with explicit JSON content type first (Vue.js backend), then form-encoded, then `/hub2/room/save` prefix, then Grails command object pattern (`room.id`, `room.name`, `room.deviceIds[]`). Captures response bodies from all attempts for diagnosis.

**New in v0.6.13:**
- **Room assignment** — new approach: tries `PUT /room/<id>` (Grails RESTful update), `POST /room/save`, `POST /room/update`, plus GET fallback. Probes `/room/list` HTML and logs its JavaScript to discover the actual room management endpoints. Post-save verification via `getRooms()`.

**New in v0.6.12:**
- **Fix room assignment** — tried 7 GET parameter combinations for `/room/addDevice` plus 3 POST fallbacks. Added post-save verification via `getRooms()`.

**New in v0.6.11:**
- **Fix room assignment** — `/device/save` returns 200 but silently ignores roomId. Rooms in Hubitat are managed from the room side (a room has a device list). Now uses `/room/` controller endpoints.

**New in v0.6.10:**
- **Fix room assignment** — diagnostic revealed device edit page is a Vue.js SPA with zero HTML form fields. Now fetches device data from `/device/fullJson/<id>`, builds proper POST body with all required fields (id, version, name, label, deviceNetworkId, deviceTypeId, roomId, etc.), and tries 4 different save strategies (form-encoded flat, JSON POST, /device/updateRoom, Grails device.* prefix).

**New in v0.6.9:**
- **Room assignment: new approach** — instead of guessing form fields, now fetches the actual device edit page HTML (`/device/edit/<id>`), scrapes all form `<input>` and `<select>` fields, overrides the room field, and resubmits exactly what the browser would submit.

**New in v0.6.8:**
- **Room assignment diagnostic** — capture the actual HTTP 500 response body from `/device/save` to see the Grails error/stack trace instead of just "500 Server Error"

**New in v0.6.7:**
- **Fix room assignment** — add Grails `version` field for optimistic locking. The `/device/save` endpoint requires the current device version number to prevent stale object exceptions.

**New in v0.6.6:**
- **Room assignment diagnostic build** — dumps full device JSON fields for debugging `/device/save` requirements. Also tries `device.*` prefixed form fields.

**New in v0.6.5:**
- **Fix room assignment (still 500 in v0.6.4)** — the device JSON field is `deviceTypeId`, not `typeId`. Was still null after the v0.6.4 nesting fix.

**New in v0.6.4:**
- **Fix room assignment (still 500 in v0.6.3)** — the `/device/fullJson` endpoint nests device data under a `device` key. Now extracts from `fullJson.device`.

**New in v0.6.3:**
- **Fix `update_device` room assignment and enable/disable** — room assignment was returning HTTP 500 (wrong form field names for Hubitat's Grails backend), enable/disable was returning HTTP 404 (endpoint requires POST, not GET). Both fixed.
- **Case-insensitive room matching** — room names no longer need to match exact case
- **Debug logging** — comprehensive debug logs throughout `update_device` for troubleshooting

**New in v0.6.2:**
- **New `update_device` tool** — modify label, name, room, enable/disable, preferences, data values, and DNI on any accessible device. Works on both selected devices and MCP-managed virtual devices. Room and enable/disable use hub internal API; all other properties use official Hubitat API.

**New in v0.6.1:**
- **Fix BigDecimal.round() crash** — daily version update checker was crashing nightly at 3 AM due to `BigDecimal.round(1)` in Hubitat's Groovy sandbox. Replaced with pure integer math.
- **Rule app version sync** — `hubitat-mcp-rule.groovy` version updated from 0.4.7 to 0.6.1 to stay in sync with the server

**New in v0.6.0:**
- **3 new tools** (67 total) — virtual device creation and management
- **`create_virtual_device`** — create virtual switches, buttons, contact sensors, motion sensors, and 11 other types as MCP-managed child devices
- **`list_virtual_devices`** — list all MCP-managed virtual devices with current states
- **`delete_virtual_device`** — remove MCP-managed virtual devices
- **Auto-accessible** — virtual devices work with all MCP device tools (`send_command`, `get_device`, etc.) without manual device selection
- **Shareable** — virtual devices appear in Hubitat's device list for use with Maker API, Dashboard, Rule Machine, Home Assistant, etc.

**New in v0.5.4:**
- **Fix BigDecimal arithmetic** — replace all floating-point division with pure integer math in `device_health_check` and `delete_device` to avoid Hubitat Groovy's `BigDecimal` incompatibilities

**New in v0.5.3:**
- **Fix `device_health_check`** — use `Math.round()` instead of `BigDecimal.round()` for Hubitat Groovy compatibility

**New in v0.5.2:**
- **Fix `device_health_check`** — robust error handling for devices with unusual lastActivity values

**New in v0.5.1:**
- **Fix `get_hub_logs`** — correctly parse JSON array response from hub's `/logs/past/json` endpoint

**New in v0.5.0:**
- **5 new tools** (64 total) — monitoring, device health, and device management
- **`get_hub_logs`** — access Hubitat's built-in system logs with filtering by level (trace/debug/info/warn/error) and source/app name
- **`get_device_history`** — time-range event queries using `eventsSince()` with attribute filtering, up to 7 days of history
- **`get_hub_performance`** — comprehensive performance snapshots with CSV time-series trend tracking in File Manager for historical analysis
- **`device_health_check`** — scan all selected devices and flag stale/offline devices that haven't reported within a configurable threshold
- **`delete_device`** — force-delete ghost/orphaned devices with the strictest safeguards of any tool: Z-Wave/Zigbee radio activity checks, MCP rule reference checks, recent event checks, full audit logging, and deletion verification

**New in v0.4.8:**
- **Z-Wave/Zigbee endpoint fix** — `get_zwave_details` and `get_zigbee_details` now work on firmware 2.3.7.1+ by trying the new `/hub/zwaveDetails/json` and `/hub/zigbeeDetails/json` endpoints first, with automatic fallback to legacy `/hub2/` endpoints for older firmware

**New in v0.4.7:**
- **10 bug fixes** from comprehensive code review — state persistence, auth retry, time parsing, locale handling, variable substitution, restore flash prevention, delay action reliability, and more

**New in v0.4.6:**
- **Fix version mismatch bug** — `update_app_code`/`update_driver_code` now fetch fresh version for optimistic locking instead of relying on potentially stale backup cache

**New in v0.4.5:**
- **Chunked reading** — `get_app_source`, `get_driver_source`, and `read_file` support `offset`/`length` for reading large sources in segments
- **Smart large-source handling** — sources over 64KB are auto-saved to File Manager, enabling cloud-free updates
- **`resave` mode** — re-save current source entirely on-hub (no cloud round-trip needed)
- **`sourceFile` mode** — read source from a File Manager file (bypasses 64KB cloud limit)

**New in v0.4.4:**
- **Critical bug fixes** from live testing — InputStreamReader handling, delete response parsing, list_files reliability
- **Backup chain prevention** — deleting backup files no longer creates infinite backup-of-backup chains

**New in v0.4.3:**
- **Comprehensive bug fixes** — null safety, race conditions, memory leaks, validation improvements
- **File-based backups** — source code backups stored in hub's File Manager (no truncation, survives MCP uninstall)
- **3 backup tools** — `list_item_backups`, `get_item_backup`, `restore_item_backup`
- **4 File Manager tools** — `list_files`, `read_file`, `write_file`, `delete_file` with auto-backup safeguards

**New in v0.4.1:**
- **Bug fixes** for `get_app_source`, `get_driver_source`, and `create_hub_backup` — all Hub Admin tools now functional
- **Two-tier backup system** — full hub backup (24h) + automatic item-level source backup before modify/delete
- **SKILL.md** - Claude Code development skill for project conventions

**New in v0.4.0:**
- **18 new Hub Admin tools** - Hub details, health monitoring, backup, reboot, shutdown, Z-Wave repair, app/driver source retrieval, install, update, and delete
- **Hub Security support** - Automatic cookie-based authentication for hubs with Hub Security enabled
- **52 MCP tools total** (up from 34 in v0.3.x)

**New in v0.3.0:**
- **Rule export/import/clone** - Export rules as portable JSON, import them with device remapping, or clone existing rules
- **Conditional triggers** - Add per-trigger condition gates so individual triggers only fire when their condition is met
- **8 new action types** - `set_thermostat`, `http_request`, `speak` (TTS), `comment`, `set_valve`, `set_fan_speed`, `set_shade`, `variable_math`
- **Variable math** - Perform arithmetic on local/global variables (add, subtract, multiply, divide, modulo, set)
- **Version update check** - Automatic daily check for new versions with UI banner and MCP tool
- **Sunrise/sunset normalization** - Accepts many trigger formats and auto-normalizes to canonical form
- **Debug logging overhaul** - 45+ silent failure points now route through MCP debug logs

**v0.2.0:** MCP-accessible debug logging system with 6 diagnostic tools.

**v0.1.0:** Parent/Child architecture with isolated child app settings.

Instead of running a separate Node.js MCP server on another machine, this runs natively on the Hubitat hub itself.

## Features

### Rule Engine UI

Manage your automation rules directly in the Hubitat web interface:

- **View all rules** with status (enabled/disabled) and last triggered time
- **Create new rules** as child apps (like Rule Machine)
- **Edit triggers** - Device events, button presses, time schedules, mode changes, and more
- **Edit conditions** - Device state, time range, mode, variables, presence, and 14 condition types total
- **Edit actions** - Device commands, scenes, delays, notifications, and 29 action types total
- **Enable/disable rules** with a single tap
- **Test rules** (dry run) to see what would happen without executing
- **Delete rules** with confirmation

### MCP Tools (73 total)

| Category | Tools |
|----------|-------|
| **Devices** (5) | `list_devices`, `get_device`, `get_attribute`, `send_command`, `get_device_events` |
| **Virtual Devices** (4) | `create_virtual_device`, `list_virtual_devices`, `delete_virtual_device`, `update_device` |
| **Rooms** (5) | `list_rooms`, `get_room`, `create_room`, `delete_room`, `rename_room` |
| **Rules** (11) | `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`, `enable_rule`, `disable_rule`, `test_rule`, `export_rule`, `import_rule`, `clone_rule` |
| **System** (9) | `get_hub_info`, `get_modes`, `set_mode`, `get_hsm_status`, `set_hsm`, `list_variables`, `get_variable`, `set_variable`, `check_for_update` |
| **State Capture** (3) | `list_captured_states`, `delete_captured_state`, `clear_captured_states` |
| **Debug/Diagnostics** (6) | `get_debug_logs`, `clear_debug_logs`, `get_rule_diagnostics`, `set_log_level`, `get_logging_status`, `generate_bug_report` |
| **Monitoring** (4) | `get_hub_logs`, `get_device_history`, `get_hub_performance`, `device_health_check` |
| **Hub Admin Read** (8) | `get_hub_details`, `list_hub_apps`, `list_hub_drivers`, `get_zwave_details`, `get_zigbee_details`, `get_hub_health`, `get_app_source`, `get_driver_source` |
| **Hub Admin Write** (10) | `create_hub_backup`, `reboot_hub`, `shutdown_hub`, `zwave_repair`, `install_app`, `install_driver`, `update_app_code`, `update_driver_code`, `delete_app`, `delete_driver` |
| **Device Admin** (1) | `delete_device` |
| **Item Backups** (3) | `list_item_backups`, `get_item_backup`, `restore_item_backup` |
| **File Manager** (4) | `list_files`, `read_file`, `write_file`, `delete_file` |

### Rule Engine

Create automations via natural language through Claude:

**Supported Triggers:**
- `device_event` - When a device attribute changes (with optional duration for debouncing, e.g., "temp > 78 for 5 minutes")
- `button_event` - Button pressed, held, double-tapped, or released
- `time` - At a specific time (HH:mm), or relative to sunrise/sunset with offset
- `periodic` - Repeat at intervals (minutes, hours, or days)
- `mode_change` - When hub mode changes
- `hsm_change` - When HSM (Home Security Monitor) status changes

**Supported Conditions:**
- `device_state` - Check current device attribute value
- `device_was` - Check if device has been in a state for X seconds (anti-cycling)
- `time_range` - Check if within a time window (supports sunrise/sunset keywords)
- `mode` - Check current hub mode
- `variable` - Check hub or rule-local variable value
- `days_of_week` - Check day of week
- `sun_position` - Check if sun is up or down
- `hsm_status` - Check HSM arm status
- `presence` - Check presence sensor status (present/not present)
- `lock` - Check lock status (locked/unlocked)
- `thermostat_mode` - Check thermostat operating mode
- `thermostat_state` - Check thermostat operating state (idle/heating/cooling)
- `illuminance` - Check light level (lux) with comparison operators
- `power` - Check power consumption (watts) with comparison operators

**Supported Actions:**
- `device_command` - Send command to device
- `toggle_device` - Toggle device on/off
- `activate_scene` - Activate a scene device
- `set_level` - Set dimmer level with optional duration
- `set_color` - Set color (hue, saturation, level) on RGB devices
- `set_color_temperature` - Set color temperature on CT bulbs
- `lock` / `unlock` - Lock or unlock a lock device
- `set_variable` - Set a global or rule-local variable
- `set_local_variable` - Set a rule-scoped variable
- `set_mode` - Change hub mode
- `set_hsm` - Change HSM arm mode
- `delay` - Wait before next action (with optional ID for targeted cancellation)
- `if_then_else` - Conditional branching
- `cancel_delayed` - Cancel pending delayed actions (specific or all)
- `repeat` - Repeat actions N times
- `stop` - Stop rule execution
- `log` - Write to Hubitat logs
- `capture_state` / `restore_state` - Save and restore device states
- `send_notification` - Send push notification to notification devices
- `set_thermostat` - Set thermostat mode, heating/cooling setpoints, and fan mode
- `http_request` - Make HTTP GET/POST requests (webhooks, external APIs)
- `speak` - Text-to-speech on speech synthesis devices (with optional volume)
- `comment` - Documentation-only action (no-op, for annotating rule logic)
- `set_valve` - Open or close a valve device
- `set_fan_speed` - Set fan speed (low/medium-low/medium/medium-high/high/on/off/auto)
- `set_shade` - Open, close, or set position (0-100) of window shades
- `variable_math` - Arithmetic on local/global variables (add, subtract, multiply, divide, modulo, set)

### Hub Admin Tools (v0.4.0+)

Manage and monitor your Hubitat hub directly through MCP. Both Hub Admin Read and Hub Admin Write access are **disabled by default** and must be explicitly enabled in the app settings.

#### Enabling Hub Admin Tools

1. Open **Apps** → **MCP Rule Server** in the Hubitat web UI
2. Under **Hub Admin Access**, toggle:
   - **Enable Hub Admin Read Tools** — for read-only hub information
   - **Enable Hub Admin Write Tools** — for backup, reboot, shutdown, Z-Wave repair, and app/driver management
3. If your hub has **Hub Security** enabled, also configure:
   - **Hub Security Username** and **Password** under the Hub Security section

#### Hub Admin Read Tools

| Tool | Description |
|------|-------------|
| `get_hub_details` | Hub model, firmware, IP, uptime, free memory, CPU temperature, database size, Z-Wave/Zigbee info |
| `list_hub_apps` | List all user-installed apps (ID, name, namespace) |
| `list_hub_drivers` | List all user-installed drivers (ID, name, namespace) |
| `get_zwave_details` | Z-Wave radio firmware, frequency, device details |
| `get_zigbee_details` | Zigbee channel, PAN ID, radio details |
| `get_hub_health` | Health dashboard with memory/temperature/database warnings, uptime, MCP statistics |
| `get_app_source` | Retrieve the full Groovy source code of an installed app by ID |
| `get_driver_source` | Retrieve the full Groovy source code of an installed driver by ID |

#### Hub Admin Write Tools

All write tools enforce a **three-layer safety gate**:
1. Hub Admin Write must be **enabled** in settings
2. The AI must pass `confirm=true` explicitly
3. A full hub **backup must exist within the last 24 hours** (enforced automatically)

Additionally, tools that **modify or delete** existing apps/drivers automatically back up the item's source code before making changes (1-hour window — preserves the original pre-edit source across multiple edits).

| Tool | Description |
|------|-------------|
| `create_hub_backup` | Create a hub database backup (required before any other write operation) |
| `reboot_hub` | Reboot the hub (will be unreachable for 1-3 minutes) |
| `shutdown_hub` | Shut down the hub (requires manual power cycle to restart) |
| `zwave_repair` | Start a Z-Wave network repair (runs in background for 5-30 minutes) |
| `install_app` | Install a new Groovy app from source code |
| `install_driver` | Install a new Groovy driver from source code |
| `update_app_code` | Update an existing app's source code (uses optimistic locking) |
| `update_driver_code` | Update an existing driver's source code (uses optimistic locking) |
| `delete_app` | Permanently delete an installed app |
| `delete_driver` | Permanently delete an installed driver |

#### Item Backup & Restore Tools

These tools let you view and restore the automatic source code backups that are created before any modify/delete operation. They work even if Hub Admin Read/Write is disabled (except `restore_item_backup` which needs write access).

| Tool | Description |
|------|-------------|
| `list_item_backups` | List all saved source code backups with timestamps and sizes |
| `get_item_backup` | Retrieve the full source code from a specific backup |
| `restore_item_backup` | Restore an app/driver to its backed-up version |

**How item backups work:**
- When you use `update_app_code`, `update_driver_code`, `delete_app`, or `delete_driver`, the server automatically saves the **original source code** before making changes
- Backups are stored as `.groovy` files in the hub's local **File Manager** — no cloud involvement, no size limits
- Files are named `mcp-backup-app-<id>.groovy` or `mcp-backup-driver-<id>.groovy`
- Backups persist even if the MCP app is uninstalled or deleted — they live in the hub's file system
- Files are directly downloadable at `http://<your-hub-ip>/local/<filename>`
- Max 20 backups kept; oldest file is automatically deleted when the limit is exceeded
- Within a 1-hour window, the **first** backup is preserved — multiple edits won't overwrite the pre-edit original

**How to restore if something goes wrong:**

Via MCP:
1. Call `list_item_backups` to see available backups
2. Call `restore_item_backup` with the backup key (e.g., `app_123`) and `confirm=true`

Via MCP (for deleted items):
1. Call `get_item_backup` to retrieve the source code
2. Call `install_app` or `install_driver` with that source code to re-install it

Manually (without MCP — backups survive even if MCP is deleted):
1. Go to your Hubitat web UI > **Settings** > **File Manager**
2. Find the backup file (e.g., `mcp-backup-app-123.groovy`) and download it
3. Or navigate directly to `http://<your-hub-ip>/local/mcp-backup-app-123.groovy`
4. Go to Hubitat > **Apps Code** (or **Drivers Code**) > select the app/driver (or click "New App"/"New Driver" for deleted items)
5. Paste the source code and click **Save**

#### File Manager Tools

These tools provide direct read/write access to the hub's local File Manager — the same storage area accessible via Hubitat > Settings > File Manager. Files are stored locally on the hub (~1GB capacity) and accessible at `http://<your-hub-ip>/local/<filename>`.

| Tool | Description |
|------|-------------|
| `list_files` | List all files in File Manager with sizes and download URLs |
| `read_file` | Read the contents of a file (returns text inline or download URL for large files) |
| `write_file` | Create or update a file (auto-backs up existing file before overwriting) |
| `delete_file` | Delete a file (auto-backs up file before deletion) |

**Safety features:**
- `write_file` on an existing file automatically creates a backup copy first (named `<original>_backup_<timestamp>.<ext>`)
- `delete_file` automatically creates a backup copy before deletion
- Both `write_file` and `delete_file` require Hub Admin Write access and `confirm=true`
- `list_files` and `read_file` are always available (no access gates)
- File name validation: only letters, numbers, hyphens, underscores, and periods allowed

#### Hub Security Support

If your hub has Hub Security enabled (login required for the web UI), the MCP server handles authentication automatically:
- Configure your Hub Security username and password in the app settings
- The server caches the session cookie for 30 minutes
- Stale cookies are automatically cleared and re-authenticated on the next request
- If Hub Security is not enabled, no credentials are needed

#### Example Usage

```
"What's the hub's health status? Check memory and temperature."
→ Uses get_hub_health

"List all installed apps and show me the source code for app ID 42"
→ Uses list_hub_apps, then get_app_source

"Create a backup, then install this driver code on my hub: [code]"
→ Uses create_hub_backup, then install_driver

"My Z-Wave devices are responding slowly. Run a Z-Wave repair."
→ Uses create_hub_backup (mandatory), then zwave_repair
```

## Requirements

- Hubitat Elevation C-7, C-8, or C-8 Pro
- Hubitat firmware 2.3.0+ (for OAuth and internal API support)

## Installation

### Option A: Hubitat Package Manager (Recommended)

If you have [Hubitat Package Manager (HPM)](https://hubitatpackagemanager.hubitatcommunity.com/) installed:

**First, add the custom repository:**
1. Open HPM → **Package Manager Settings**
2. Click **Add a custom repository**
3. Paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/repository.json
   ```
4. Click **Save**

**Then install the package:**
1. Go to HPM → **Install** → **Search by Keywords**
2. Search for "MCP Rule Server"
3. Select it and install

> **Note**: If it doesn't appear in search, you can also use HPM → **Install** → **From a URL** with the packageManifest.json URL. After initial install, updates will work normally.

HPM will install both the parent app and child app automatically and notify you when updates are available.

### Option B: Manual Installation

You need to install **two** app files:

**1. Install the Parent App (MCP Rule Server):**
1. Go to Hubitat web UI → **Apps Code** → **+ New App**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy
   ```
3. Click **Import** → **OK** → **Save**
4. Click **OAuth** → **Enable OAuth in App** → **Save**

**2. Install the Child App (MCP Rule):**
1. Go to **Apps Code** → **+ New App**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-rule.groovy
   ```
3. Click **Import** → **OK** → **Save**
4. (No OAuth needed for the child app)

## Quick Start

### 1. Add the App

1. Go to **Apps** → **+ Add User App** → **MCP Rule Server**
2. Select devices you want accessible via MCP
3. Click **Done**
4. Open the app to see your endpoint URLs and manage rules

### 2. Get Your Endpoint URL

The app shows two endpoint URLs:

- **Local Endpoint** - For use on your local network:
  ```
  http://192.168.1.100/apps/api/123/mcp?access_token=YOUR_TOKEN
  ```

- **Cloud Endpoint** - For remote access (requires Hubitat Cloud subscription):
  ```
  https://cloud.hubitat.com/api/YOUR_HUB_ID/apps/123/mcp?access_token=YOUR_TOKEN
  ```

## MCP Client Setup

### Claude Code (CLI)

Add to your Claude Code MCP settings file (`~/.claude/claude_desktop_config.json` on Mac/Linux or `%APPDATA%\Claude\claude_desktop_config.json` on Windows):

**For Local Network:**
```json
{
  "mcpServers": {
    "hubitat": {
      "type": "url",
      "url": "http://192.168.1.100/apps/api/123/mcp?access_token=YOUR_TOKEN"
    }
  }
}
```

**For Remote Access (Hubitat Cloud):**
```json
{
  "mcpServers": {
    "hubitat": {
      "type": "url",
      "url": "https://cloud.hubitat.com/api/YOUR_HUB_ID/apps/123/mcp?access_token=YOUR_TOKEN"
    }
  }
}
```

### Claude Desktop

Same configuration as above. Add to your Claude Desktop config file:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "hubitat": {
      "type": "url",
      "url": "http://YOUR_HUB_IP/apps/api/123/mcp?access_token=YOUR_TOKEN"
    }
  }
}
```

### Claude.ai (Connectors)

Claude.ai supports MCP servers through **Connectors**. You can add this Hubitat MCP server as a connector:

1. Go to [claude.ai](https://claude.ai) → **Settings** → **Connectors**
2. Add a new connector with your Hubitat endpoint URL
3. Use the **Cloud Endpoint** URL for remote access (requires Hubitat Cloud subscription), or use a Cloudflare Tunnel URL

With Hubitat Cloud, this means you can control your smart home from claude.ai anywhere - no local setup required!

### Other AI Services

Any AI service that supports MCP servers via HTTP URL can use this server. Use either:

- **Hubitat Cloud URL** - No additional setup needed if you have a Hubitat Cloud subscription
- **Cloudflare Tunnel** - For self-hosted remote access (see below)

## Remote Access Options

### Option 1: Hubitat Cloud (Easiest)

If you have a [Hubitat Cloud](https://hubitat.com/pages/remote-admin) subscription:

1. The cloud endpoint URL is shown directly in the app
2. Use that URL in your MCP client configuration
3. No additional setup required!

### Option 2: Cloudflare Tunnel (Free, Self-Hosted)

For free remote access without a Hubitat Cloud subscription:

1. Install [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
2. Create a tunnel:
   ```yaml
   # cloudflared config.yml
   ingress:
     - hostname: hubitat-mcp.yourdomain.com
       service: http://YOUR_HUB_IP:80
     - service: http_status:404
   ```
3. Use your tunnel URL:
   ```
   https://hubitat-mcp.yourdomain.com/apps/api/123/mcp?access_token=YOUR_TOKEN
   ```

## Example Usage

Once connected, you can ask Claude things like:

> "Turn on the living room lights"

> "Create a rule that turns off all lights at midnight"

> "When motion is detected in the hallway, turn on the hallway light for 5 minutes"

> "Create a rule: when temperature stays above 78 for 5 minutes, turn on the AC"

> "When the bedroom button is double-tapped, toggle the bedroom lights"

> "Turn on outdoor lights at sunset"

> "What's the temperature in the bedroom?"

> "List all my rules"

## Rule Structure

Rules are JSON objects with triggers, conditions, and actions:

### Basic Example
```json
{
  "name": "Motion Light",
  "triggers": [
    {
      "type": "device_event",
      "deviceId": "123",
      "attribute": "motion",
      "value": "active"
    }
  ],
  "conditions": [
    {
      "type": "time_range",
      "startTime": "sunset",
      "endTime": "sunrise"
    }
  ],
  "conditionLogic": "all",
  "actions": [
    { "type": "device_command", "deviceId": "456", "command": "on" },
    { "type": "delay", "seconds": 300, "delayId": "motion-off" },
    { "type": "device_command", "deviceId": "456", "command": "off" }
  ]
}
```

### Button with Local Variables (State Machine)
```json
{
  "name": "Smart Button Toggle",
  "localVariables": { "lastScene": "natural" },
  "triggers": [
    { "type": "button_event", "deviceId": "80", "action": "pushed" }
  ],
  "actions": [
    {
      "type": "if_then_else",
      "condition": { "type": "variable", "variableName": "lastScene", "operator": "equals", "value": "natural" },
      "thenActions": [
        { "type": "activate_scene", "sceneDeviceId": "nightlight-scene" },
        { "type": "set_local_variable", "variableName": "lastScene", "value": "nightlight" }
      ],
      "elseActions": [
        { "type": "activate_scene", "sceneDeviceId": "natural-scene" },
        { "type": "set_local_variable", "variableName": "lastScene", "value": "natural" }
      ]
    }
  ]
}
```

### Temperature with Debouncing (Anti-Flapping)
```json
{
  "name": "AC On When Hot",
  "triggers": [
    {
      "type": "device_event",
      "deviceId": "1",
      "attribute": "temperature",
      "operator": ">",
      "value": "78",
      "duration": 300
    }
  ],
  "conditions": [
    { "type": "device_was", "deviceId": "8", "attribute": "switch", "value": "off", "forSeconds": 600 }
  ],
  "actions": [
    { "type": "device_command", "deviceId": "8", "command": "on" }
  ]
}
```

## Performance Considerations

### Hub Hardware

| Hub Model | Recommendation |
|-----------|----------------|
| **C-7** | Older/slower hardware. Works fine for basic use, but may experience delays with large device lists or complex rules. |
| **C-8** | Good for most users. Handles moderate device counts well. |
| **C-8 Pro** | Best option for heavy use, large device counts (100+), or complex automations. |

### Known Limits

- **`list_devices` with `detailed=true`** - Can be slow on 50+ devices. Use pagination:
  ```
  list_devices(detailed=true, limit=25, offset=0)
  ```
- **Duration triggers** - Maximum of 2 hours (7200 seconds). For longer durations, consider alternative approaches.
- **Captured states** - Default limit of 20 unique state IDs (configurable in app settings, range 1-100). When limit is reached, the oldest capture is automatically deleted. Use `list_captured_states` to monitor usage.
- **Hubitat Cloud responses** - 128KB maximum (AWS MQTT limit). Use pagination for large device lists.

## Debug Logging System

**New in v0.2.0:** The MCP server includes a built-in debug logging system that stores logs in app state, making them accessible via MCP tools. Unlike Hubitat's built-in logs (which require UI access), these logs can be retrieved directly through MCP.

### Debug Logging Tools

| Tool | Description |
|------|-------------|
| `get_debug_logs` | Retrieve recent log entries with optional filters |
| `clear_debug_logs` | Clear all stored log entries |
| `get_rule_diagnostics` | Get comprehensive diagnostic info for a specific rule |
| `set_log_level` | Set minimum log level (debug/info/warn/error) |
| `get_logging_status` | View logging system statistics |

### Usage Examples

**Get recent logs:**
```json
{"tool": "get_debug_logs", "arguments": {"limit": 50, "level": "error"}}
```

**Get diagnostics for a problematic rule:**
```json
{"tool": "get_rule_diagnostics", "arguments": {"ruleId": "123"}}
```

**Set log level to debug for detailed troubleshooting:**
```json
{"tool": "set_log_level", "arguments": {"level": "debug"}}
```

### What Gets Logged

- Rule creation with verification of stored triggers/actions
- Rule execution events
- Errors with context information
- Timing data for performance analysis
- State persistence verification

The logging system stores up to 100 entries in a circular buffer (oldest entries are removed when limit is reached).

## Troubleshooting

### Device not found
Make sure the device is selected in the app's "Select Devices for MCP Access" setting.

### OAuth token not working
1. Open Apps Code → MCP Rule Server
2. Click OAuth → Enable OAuth in App
3. Save
4. Re-open the app in Apps to get the new token

### Rules not triggering
- Check that "Enable Rule Engine" is on in app settings
- Enable "Debug Logging" and check Hubitat Logs
- Verify the trigger device is selected for MCP access
- For duration-based triggers, ensure the condition stays true for the full duration

### Button events not working
- Make sure you're using `button_event` trigger type (not `device_event`)
- Verify the button action type: `pushed`, `held`, `doubleTapped`, or `released`

### Rules from v0.0.x not showing
Version 0.1.0 uses a new parent/child architecture. Old rules stored in `state.rules` are not migrated automatically. You'll need to recreate rules either through the UI or via MCP.

### list_devices(detailed=true) fails over Hubitat Cloud
Hubitat Cloud has a **128KB response size limit** (AWS MQTT limitation). With many devices, `detailed=true` can exceed this. Use pagination:

```
list_devices(detailed=true, limit=25, offset=0)   // First 25 devices
list_devices(detailed=true, limit=25, offset=25)  // Next 25 devices
```

The response includes `total`, `hasMore`, and `nextOffset` to help with pagination.

## Limitations

- **Hubitat Cloud 128KB limit** - Large responses fail over cloud; use pagination for `list_devices(detailed=true)`
- No real-time event streaming (MCP responses only, no push notifications)
- Time triggers use Hubitat's `schedule()` which has some limitations
- Sunrise/sunset times are recalculated daily

## Future Plans

> **Blue-sky ideas** — everything below is speculative and needs further research to determine feasibility. None of these features are guaranteed or committed to. They represent potential directions the project could go.

### Rule Engine — Trigger Enhancements
- **Endpoint/webhook triggers** — create local LAN and/or cloud URLs that trigger a rule when hit (like Rule Machine's Local/Cloud End Point triggers)
- **Hub variable change triggers** — trigger a rule when a hub variable or rule engine variable value changes
- **Conditional triggers** — evaluate a condition at the moment a trigger fires; rule only executes if the condition is true (Rule Machine 5.1 feature — different from separate conditions which are evaluated independently)
- **Sticky/duration triggers** — trigger only when a device state persists for N seconds (debounce built into the trigger itself, not just via conditions)
- **System start trigger** — fire actions on hub boot/restart
- **Date range triggers** — trigger between two calendar dates (e.g., seasonal automations)
- **Cron/periodic triggers** — interval-based recurring triggers (e.g., every 5 minutes, every hour) beyond simple time-of-day schedules

### Rule Engine — Condition Enhancements
- **Required Expressions (rule gates)** — a prerequisite expression that must be true for the rule to run at all, with an option to cancel in-flight actions (pending delays, repeats) when the gate expression becomes false mid-execution
- **Full boolean expression builder** — support AND/OR/XOR/NOT with nested parenthetical grouping for complex condition logic (currently only supports all/any)
- **Private Boolean** — per-rule built-in boolean variable that can be read/set by other rules, usable as a condition or restriction

### Rule Engine — Action Enhancements
- **Fade dimmer over time** — gradually ramp a dimmer level from current to target over a specified duration
- **Change color temperature over time** — gradually transition color temperature (e.g., warm to cool over 30 minutes)
- **Per-mode actions** — different action parameters depending on current hub mode (e.g., set level to 100 in Day mode, 30 in Night mode)
- **Wait for Event** — pause execution until a specific device event occurs, with configurable timeout
- **Wait for Expression** — pause until a boolean expression becomes true, with timeout and optional duration requirement (expression must remain true for N seconds)
- **Repeat While / Repeat Until** — loop with expression evaluation (current `repeat` only does count-based)
- **Rule-to-rule control** — run another rule's actions, pause/resume other rules
- **Cancel Rule Timers** — cancel pending timers (delays, waits) on other rules remotely
- **File write/append/delete** — write to hub's local File Manager from within rule actions
- **Ping IP address** — ping a host and store results (packet loss, latency) in a variable
- **Custom Action** — run any arbitrary command on any device by selecting capability + command + parameters
- **Music/siren control** — play sound, set volume, mute/unmute, control media players, sound chime/siren
- **Disable/Enable a device** — programmatically disable or re-enable a Hubitat device
- **Ramp actions** — start continuously raising/lowering a dimmer level, stop on command

### Rule Engine — Variable System Enhancements
- **Hub Variable Connectors** — expose hub variables as virtual device attributes so any app can read them
- **Variable change events** — variables generating location events when they change, enabling triggers and conditions based on variable state
- **Local variable triggers** — allow rule-scoped local variables to trigger re-evaluation when changed

### Built-in Automation Equivalents
- **Room Lighting** — room-centric lighting automation with motion triggers, scene management, vacancy mode (lights only turn off, never auto-on), per-mode settings, and scene transitions over time (Rule Machine covers most of this but Room Lighting is a dedicated streamlined UX)
- **Zone Motion Controller** — combine multiple motion sensors into zones with aggregation logic, false-motion filtering, and configurable activation/deactivation behavior
- **Mode Manager** — dedicated tool/rule template for automating mode changes based on time of day, presence sensors, sunrise/sunset (currently possible via rules but no dedicated tool)
- **Button Controller** — streamlined button-to-action mapping (essentially the same as button_event triggers + actions but with a simplified single-purpose UX)
- **Thermostat Scheduler** — schedule-based thermostat automation with per-time-slot, per-day, per-mode setpoints (currently partial via set_thermostat action)
- **Lock Code Manager** — manage lock user codes, access schedules, and lock/unlock notifications
- **Groups and Scenes** — Zigbee group messaging (eliminates the "popcorn effect" of staggered device commands), scene capture/recall with transition timing

### HPM Integration
- **Search HPM repositories** — tool to search Hubitat Package Manager for available packages by keyword
- **Install via HPM** — trigger HPM to install a package (app + driver bundles) without manual UI steps
- **Uninstall via HPM** — remove packages cleanly through HPM's uninstall process
- **Check for updates** — query HPM for available updates across all installed packages

### App/Integration Discovery & Install (Outside HPM)
- **Search for official integrations** — find and install built-in Hubitat apps and integrations that aren't yet enabled
- **Search for custom apps** — discover and install community apps/drivers from sources outside HPM (GitHub repos, community forums, etc.)
- **Browse available integrations** — list official integrations available on the hub that haven't been activated yet

### Dashboard Management
- **Create dashboards** — programmatically create new dashboards with device tiles and layouts
- **Modify dashboards** — add/remove/rearrange tiles, change tile templates, update dashboard settings
- **Delete dashboards** — remove dashboards that are no longer needed
- **Official dashboard support preferred** — ideally interact with Hubitat's native dashboard system so dashboards appear on the home screen and mobile app; if not feasible, explore alternative dashboard solutions that can be set as defaults

### Rule Machine Interoperability
> **Feasibility researched** — programmatically creating or modifying Rule Machine rules is **not possible**. RM is closed-source, the export format is an undocumented internal data dump (not guaranteed valid JSON), and the Groovy sandbox prevents cross-app state access. However, controlling existing RM rules IS feasible via `RMUtils` and internal endpoints.

- **List all RM rules** — enumerate Rule Machine rules with name, ID, and enabled/disabled status via `/hub2/appsList` or `RMUtils.getRuleList("5.0")`
- **Enable/disable RM rules** — programmatically enable or disable individual RM rules via `/installedapp/disable?id={ID}&disable={true|false}`
- **Trigger RM rule actions** — execute an existing RM rule's actions via `RMUtils.sendAction()` with `runRuleAct`
- **Pause/resume RM rules** — pause or resume existing RM rules via `RMUtils.sendAction()` with `pauseRule`/`resumeRule`
- **Set RM Private Booleans** — set a rule's Private Boolean true/false via `RMUtils.sendAction()` with `setRuleBooleanTrue`/`setRuleBooleanFalse`
- **Hub variable bridge** — set hub variables that RM rules react to, and vice versa, enabling cross-engine coordination between MCP rules and RM rules

### Integration & Streaming
- **MQTT client** — publish and subscribe to MQTT topics from within rules or as a standalone tool (connect to an external MQTT broker for bridging to Node-RED, Home Assistant, etc.)
- **Event streaming / webhooks** — real-time POST of device events to external URLs as they happen (similar to Maker API's postURL feature but integrated into the MCP server)

### Advanced Automation Patterns
- **Occupancy / room state machine** — rooms with states like occupied, vacant, engaged, checking — going beyond simple motion-on/motion-off to track true room occupancy using multiple sensor types (motion, contact, power meters, presence)
- **Presence-based automation** — geofencing triggers with first-to-arrive and last-to-leave logic, arrival/departure actions, presence-based mode changes
- **Weather-based triggers** — respond to weather conditions (rain, temperature thresholds, wind speed, humidity) from weather station devices or external weather APIs
- **Vacation mode** — random light cycling to simulate occupancy, timed on/off patterns, lock all doors, energy-saving thermostat presets

### Monitoring & Diagnostics
- **Device health watchdog** — monitor last check-in time for all devices, alert on stale/offline devices that haven't reported in a configurable period
- **Z-Wave ghost device detection** — identify orphaned Z-Wave nodes (no routes, no linked device) and assist with removal workflow
- **Event history / analytics** — aggregate device event data over time periods, generate summary reports (e.g., how many times a door opened today, average temperature over the last week)
- **Hub performance monitoring** — track memory usage, temperature, and database size trends over time, alert on degradation
- **Access to Hubitat logs/events** — read the hub's native log entries (app logs, device logs, location events) via MCP tools for diagnostics and troubleshooting without needing the web UI

### Notification Enhancements
- **Pushover integration** — native Pushover notifications from rules with support for priority levels (quiet, normal, high, emergency with repeat-until-acknowledged)
- **Email notifications** — send emails via SendGrid or similar API from within rules
- **Rate limiting / throttling** — configurable maximum notifications per timeframe to prevent notification storms
- **Notification routing** — different notification targets per severity or event type (e.g., critical alerts to Pushover, informational to email)

### Additional Ideas
- **Device creation** — *partially implemented in v0.6.0* (MCP-managed child virtual devices via `create_virtual_device`). Future: create standalone virtual devices that appear in the regular Devices section independent of the MCP app, and support device pairing for Z-Wave, Zigbee, and cloud-connected devices
- **Scene management** — create, modify, and manage scenes (device state groups) beyond the current `activate_scene`
- **Energy monitoring dashboard** — aggregate power/energy data from devices into summary reports
- **Scheduled report generation** — periodic automated reports on hub health, device status, rule execution history

## Version History

- **v0.7.1** - Auto-backup for delete_rule (File Manager), testRule flag to skip backup, bug fixes
- **v0.7.0** - Room management: list_rooms, get_room, create_room, delete_room, rename_room (73 tools)
- **v0.6.15** - Room assignment fix: use 'roomId' field (not 'id'), remove from old room before adding to new
- **v0.6.14** - Room assignment: POST /room/save with JSON content type, form-encoded, hub2/ prefix, Grails command object
- **v0.6.13** - Room assignment: try PUT /room (Grails RESTful update), POST /room/save, POST /room/update, probe /room/list for endpoint discovery
- **v0.6.12** - Fix room assignment: use GET /room/addDevice with query params + verification
- **v0.6.11** - Fix room assignment: use /room/ controller endpoints (add device to room)
- **v0.6.10** - Fix room assignment: use fullJson device data for /device/save (Vue.js SPA has no HTML forms)
- **v0.6.9** - Room assignment: scrape device edit page HTML for correct form fields
- **v0.6.8** - Room assignment: capture 500 error response body for diagnosis
- **v0.6.7** - Fix room assignment: add Grails `version` field for optimistic locking
- **v0.6.6** - Room assignment: diagnostic build with device JSON dump
- **v0.6.5** - Fix room assignment: use `deviceTypeId` field (not `typeId`)
- **v0.6.4** - Fix room assignment: extract device data from nested `fullJson.device`
- **v0.6.3** - Fix `update_device` room assignment (500) and enable/disable (404) bugs + debug logging
- **v0.6.2** - Add `update_device` tool (68 tools)
  - New `update_device` — modify label, name, room, enable/disable, preferences, data values, and DNI on any accessible device
  - Room assignment via hub internal `/device/save` endpoint with room ID resolution via `getRooms()`
  - Enable/disable via hub internal `/device/disable` endpoint
  - Label, name, DNI, data values, preferences via official Hubitat API (`setLabel`, `setName`, `setDeviceNetworkId`, `updateDataValue`, `updateSetting`)
- **v0.6.1** - Fix BigDecimal.round() crash in version update checker (67 tools)
  - Fix `checkForUpdate()` crashing nightly at 3 AM — `BigDecimal.round(1)` replaced with pure integer math
  - Full audit of all division/rounding patterns across both apps — no other instances found
  - Sync `hubitat-mcp-rule.groovy` version from 0.4.7 to 0.6.1
- **v0.6.0** - Virtual device creation and management (67 tools)
  - New `create_virtual_device` — create virtual switches, buttons, sensors, and more as MCP-managed child devices using `addChildDevice()`
  - New `list_virtual_devices` — list all MCP-managed virtual devices with current states and capabilities
  - New `delete_virtual_device` — remove MCP-managed virtual devices
  - `findDevice()` and `list_devices` now include MCP-managed child devices automatically
  - Supports 15 virtual device types: Switch, Button, Contact Sensor, Motion Sensor, Presence Sensor, Lock, Temperature Sensor, Humidity Sensor, Dimmer, RGBW Light, Shade, Garage Door Opener, Water Sensor, Omni Sensor, Fan Controller
- **v0.5.4** - Fix BigDecimal arithmetic with pure integer math in `device_health_check` and `delete_device` (64 tools)
- **v0.5.3** - Fix `BigDecimal.round()` in `device_health_check` (64 tools)
- **v0.5.2** - Fix `device_health_check` error handling (64 tools)
- **v0.5.1** - Fix `get_hub_logs` JSON array parsing (64 tools)
- **v0.5.0** - Monitoring tools and device management (64 tools)
  - New `get_hub_logs` — access Hubitat's built-in system logs with level and source filtering
  - New `get_device_history` — time-range event queries via `eventsSince()` with attribute filtering (up to 7 days)
  - New `get_hub_performance` — performance snapshots with CSV time-series trend tracking in File Manager
  - New `device_health_check` — scan all selected devices, flag stale/offline devices by configurable threshold
  - New `delete_device` — force-delete ghost/orphaned devices with comprehensive safeguards (radio checks, rule references, audit logging, deletion verification)
- **v0.4.8** - Fix Z-Wave and Zigbee endpoint compatibility (59 tools)
  - Fix `get_zwave_details` returning 404 on firmware 2.3.7.1+ — tries `/hub/zwaveDetails/json` first, falls back to `/hub2/zwaveInfo`
  - Fix `get_zigbee_details` returning 404 on firmware 2.3.7.1+ — tries `/hub/zigbeeDetails/json` first, falls back to `/hub2/zigbeeInfo`
- **v0.4.7** - Comprehensive bug fixes from code review (59 tools)
  - Fix `mcpLog` nested state mutation — entries now persist reliably via top-level reassignment
  - Fix Hub Security auth retry — all `hubInternalGet`/`hubInternalPost`/`hubInternalPostForm` retry once on cookie expiry
  - Fix `collectDeviceIds` — now handles `deviceIds` plural array (multi-device triggers, capture_state)
  - Fix `time_range` condition — handles bare "HH:mm" strings that `toDateTime()` rejects
  - Fix `days_of_week` condition — uses `Locale.US` for consistent English day names
  - Fix `substituteVariables` — resolves rule engine variables, not just hub connector globals
  - Fix `restore_state` flash — devices restoring to "off" no longer briefly flash on from setLevel
  - Fix delay action overwrite — multiple delays in same rule no longer clobber each other via runIn
  - Fix parameter conversion — handles edge cases (overflow, scientific notation) with try-catch
  - Fix HSM `previousStatus` — captured before `sendLocationEvent` instead of after
- **v0.4.6** - Fix version mismatch bug in optimistic locking (59 tools)
  - `update_app_code`/`update_driver_code` now fetch fresh version from hub instead of using potentially stale backup cache
- **v0.4.5** - Smart large-file handling (59 tools)
  - Chunked reading for `get_app_source`, `get_driver_source`, `read_file` via `offset`/`length` parameters
  - Large sources (>64KB) auto-saved to File Manager as `mcp-source-{type}-{id}.groovy`
  - New `resave` mode for `update_app_code`/`update_driver_code` — re-saves current source entirely on-hub, no cloud round-trip
  - New `sourceFile` mode — reads source from File Manager file, bypasses 64KB cloud response limit
  - Refactored get/update source tools into shared `toolGetItemSource`/`toolUpdateItemCode` helpers
- **v0.4.4** - Fix bugs found during live Claude.ai testing (59 tools)
  - **CRITICAL**: Fixed `hubInternalGet`/`hubInternalPostForm` not reading `InputStreamReader` — `textParser: true` returns a Reader/InputStream that must be read with `.text`, not `.toString()`
  - **MAJOR**: Fixed `list_files` returning empty array — rewritten with multi-endpoint fallback and multi-format parsing (JSON list, JSON object, HTML href extraction)
  - **MAJOR**: Fixed `delete_app`/`delete_driver` returning `status: false` when deletion succeeded — now handles both boolean and string status values
  - **DESIGN**: Prevent infinite backup chain — `delete_file` now detects backup files (`_backup_`, `mcp-backup-`, `mcp-prerestore-`) and skips auto-backup for them
  - Added debug logging for delete responses and file manager operations
- **v0.4.3** - Comprehensive bug fixes + item backup & file manager tools (59 tools total)
  - **CRITICAL**: Fixed `evaluateComparison()` NullPointerException when device attribute returns null — numeric comparisons now fail closed instead of crashing
  - **CRITICAL**: Migrated `durationTimers` and `durationFired` from `state` to `atomicState` — fixes race condition where scheduled callbacks could read stale duration data
  - **CRITICAL**: Fixed unbounded `cancelledDelayIds` memory leak — now cleared on initialize/disable when scheduled callbacks are cancelled
  - **HIGH**: Fixed lost event context after delay actions — `%device%`, `%value%`, `%name%` substitutions now work after delays by serializing event fields
  - **HIGH**: Fixed sunrise/sunset rescheduling drift — now uses `getSunriseAndSunset()` for accurate next-day times instead of +24h
  - **HIGH**: Fixed `applyDeviceMapping` missing `deviceIds` arrays — multi-device triggers are now properly remapped during rule import
  - **HIGH**: Fixed response size guard using char count instead of byte count — now uses `getBytes("UTF-8").length` for accurate sizing
  - **HIGH**: Fixed non-JSON update responses incorrectly assumed successful — now warns instead of silently assuming success
  - **HIGH**: Item backup cache invalidated after successful code updates — prevents stale version numbers for optimistic locking
  - **HIGH**: Wrapped all action types in outer try-catch — one action failure no longer aborts remaining actions in the chain
  - **HIGH**: Migrated item backups from app state to hub File Manager — full source stored as .groovy files, no truncation, survives MCP uninstall
  - **MEDIUM**: Added range validation for `set_level` (0-100), `set_color` hue/saturation/level (0-100), `delay` (1-86400s), `repeat` (1-100)
  - **MEDIUM**: Fixed `formatTimeInput` to validate HH:mm format — prevents malformed cron expressions from invalid time strings
  - **MEDIUM**: Fixed `device_was` event window with 2-second margin — accounts for event timestamp vs wall-clock differences
  - **MEDIUM**: Fixed Elvis operator on `limit:0` for `get_device_events` — passing `limit=0` now correctly returns 0 events
  - **MEDIUM**: Added `textParser: true` to `hubInternalPostForm` — handles non-JSON responses without parse exceptions
  - **MEDIUM**: Install tools now warn when new app/driver ID cannot be extracted from hub response
  - **LOW**: Fixed comment/code mismatch in `backupItemSource` (said 100KB, code used 64KB)
  - **NEW**: 3 item backup tools — `list_item_backups`, `get_item_backup`, `restore_item_backup` — view and restore automatic pre-edit source code backups stored in File Manager
  - **NEW**: Backup tools include manual restore instructions and direct download URLs in every response, so users can recover even if MCP is unavailable
  - **NEW**: 4 File Manager tools — `list_files`, `read_file`, `write_file`, `delete_file` — full read/write access to the hub's local file system with automatic backup-before-modify safeguards
- **v0.4.2** - Response size safety limits (hub enforces 128KB cap)
  - **64KB source truncation** on `get_app_source` and `get_driver_source` — keeps total JSON response under hub's 128KB limit after encoding
  - **Global response size guard** in `handleMcpRequest` — catches ANY oversized response (>124KB) and returns a clean error instead of crashing the hub
  - **Item-level backups** introduced — automatic pre-edit source backup for modify/delete operations
  - **Debug log truncation** — large responses no longer spam the debug log (capped at 500 chars)
  - Returns `sourceLength`, `truncated` flag, and warning when output is incomplete
- **v0.4.1** - Bug fixes for Hub Admin tools + two-tier backup system
  - **Fixed `get_app_source` and `get_driver_source`** returning 404: Query parameters now passed via `query` map instead of embedded in path string
  - **Fixed `create_hub_backup`** returning 405 Method Not Allowed: Changed from `POST /hub/backup` to `GET /hub/backupDB?fileName=latest`
  - **Fixed `update_app_code` and `update_driver_code`** version fetch (same query parameter fix)
  - **Unblocked all Hub Admin Write tools**: Backup creation was failing, which blocked all 9 write operations behind the safety gate
  - **Two-tier backup system**: Full hub backup required within 24 hours (was 1 hour); individual item source code automatically backed up before any modify/delete operation (1-hour window preserves pre-edit original)
  - **Backup timeout** increased to 300 seconds (5 minutes) for larger hubs
  - **SKILL.md**: Claude Code development skill documenting all project conventions and architecture
- **v0.4.0** - Hub Admin Tools with Hub Security support (52 tools total)
  - **18 new Hub Admin tools**: Full hub administration through MCP
  - **Hub Admin Read Tools** (8): `get_hub_details`, `list_hub_apps`, `list_hub_drivers`, `get_zwave_details`, `get_zigbee_details`, `get_hub_health`, `get_app_source`, `get_driver_source`
  - **Hub Admin Write Tools** (10): `create_hub_backup`, `reboot_hub`, `shutdown_hub`, `zwave_repair`, `install_app`, `install_driver`, `update_app_code`, `update_driver_code`, `delete_app`, `delete_driver`
  - **Hub Security support**: Automatic cookie-based authentication for hubs with Hub Security enabled; 30-minute cookie caching with auto-renewal
  - **Three-layer safety gate** for write tools: settings toggle + explicit `confirm=true` + mandatory hub backup within last 24 hours
  - **UI toggles**: Independent enable/disable for Hub Admin Read and Write access in app settings
  - **Graceful degradation**: All Hub Admin tools handle unavailable endpoints, non-JSON responses, and firmware variations
- **v0.3.3** - Multi-device trigger support and validation fixes
  - Fixed multi-device triggers: rules can now trigger from multiple devices for the same attribute
  - Validation improvements for trigger and condition edge cases
  - Minor stability fixes
- **v0.3.2** - Comprehensive bug fixes (25 bugs verified and fixed)
  - Systematic testing and fixing of 25 confirmed bugs across rule creation, trigger handling, condition evaluation, action execution, and MCP tool responses
  - Improved error messages and edge case handling throughout
- **v0.3.1** - Bug fixes from comprehensive v0.3.0 testing:
  - Fixed `test_rule` dry run showing "Unknown action: variable_math" (missing `describeAction()` case in child app)
  - Fixed `test_rule` dry run showing "Log [null]" for log actions without explicit level (now defaults to "info")
  - Fixed `set_log_level` not persisting between MCP requests (nested `state` mutation + enum setting type fix)
- **v0.3.0** - Major feature release: Rule portability, new action types, conditional triggers, and reliability improvements
  - **Rule export/import/clone**: `export_rule` exports rules as portable JSON with a device manifest; `import_rule` imports with optional device ID remapping; `clone_rule` duplicates an existing rule (disabled by default)
  - **Conditional triggers**: Each trigger can now have an inline `condition` gate — the trigger only fires if its condition evaluates to true
  - **8 new action types**: `set_thermostat` (mode/setpoints/fan), `http_request` (GET/POST), `speak` (TTS with optional volume), `comment` (documentation-only no-op), `set_valve` (open/close), `set_fan_speed` (low through auto), `set_shade` (open/close/position), `variable_math` (arithmetic on local/global variables)
  - **Variable math**: Perform `add`, `subtract`, `multiply`, `divide`, `modulo`, or `set` operations on local or global variables within rule actions
  - **String substitution**: Actions like `send_notification` now support `%device%`, `%value%`, `%name%`, `%time%`, `%date%`, `%now%`, `%mode%`, and variable references
  - **Version update check**: Automatic daily check against GitHub; UI shows an orange banner when outdated; `check_for_update` MCP tool for on-demand checks; update status included in MCP `initialize` response
  - **Sunrise/sunset trigger normalization**: Accepts 6+ input formats (e.g., `{"type":"sunrise"}`, `{"type":"time","time":"sunrise"}`, `{"type":"sun","event":"sunset"}`) and auto-normalizes to canonical form on create/update
  - **Debug logging overhaul**: 45+ silent failure points across both parent and child apps now route through the MCP debug log system; child app uses `ruleLog()` bridge to parent's `mcpLog()`
  - 34 MCP tools total (up from 30)
- **v0.2.12** - Fourth code review: Critical UI bug fixes + 16 additional bug fixes: UI rule creation blocked by cross-page `required` validation (sub-page inputs now `required:false`), UI trigger dropdown resetting on every render (`clearTriggerSettings` ran on `submitOnChange` re-renders), UI cancel button trapped by validation popup, sunrise/sunset `runOnce` overwrite bug (shared handler name), missing past-time check for sunrise/sunset, `update_rule` not syncing name/description/label, mode condition singular/plural key, `time_range` rejecting sunrise/sunset, `evaluateCondition` default true→false, `eventsSince` limit 10→100 with attribute filter, duplicate subscriptions on rule update, `forSeconds` type coercion, per-condition error isolation, negative offset/limit clamping, JSON-RPC batch null check, `set_color_temperature` falsy check
- **v0.2.11** - Third code review (16 fixes): `settings.ruleEnabled` stale after `updateSetting()` (new enabled rules didn't subscribe), `schedule()` crash with Long for sunrise/sunset (now uses `runOnce()` + daily reschedule), `schedule()` crash with bare HH:mm (now converts to cron), added `singleThreaded:true` to child app, `send_notification` missing deviceId validation, `send_command` param type crash, `get_attribute` false "not found" for null-valued attributes, reject `expression` condition (always `true` since v0.1.18), `device_command` rule action param type conversion, `state.previousMode` init for mode_change triggers, repeat validation `times`/`count` roundtrip, `get_debug_logs` limit=0 falsy bug, log level confirmation ordering, delay warning in nested blocks, periodic trigger interval validation, dead code cleanup
- **v0.2.10** - Fixed operator handling: `equals`/`not_equals` rejected by MCP validation, `!=` had inverted logic at runtime. Now accepts both word and symbolic forms (`equals`/`==`, `not_equals`/`!=`), normalizes to word form before storing.
- **v0.2.9** - Critical bug fixes from second thorough review: infinite loop in debug log buffer cleanup, periodic triggers crashing (missing method), UI-based edits not persisting (atomicState in-place mutations), `set_local_variable` not persisting, `confirmDeletePage` return value, null check for `if_then_else` condition, stale header version, corrected tool count (30 not 28)
- **v0.2.8** - Thorough code review fixes: `get_logging_status` crash on empty logs, `test_rule` returning hardcoded results, `List.remove()` wrong overload, try-catch for trigger subscriptions and time parsing
- **v0.2.7** - Fixed StackOverflowError on app install/open (thanks [@ashwinma14](https://github.com/ashwinma14) - [#14](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/14), [#15](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/15))
- **v0.2.6** - Added `generate_bug_report` tool for easy issue submission, updated README version history
- **v0.2.5** - Added UI control for MCP debug log level (dropdown in Settings), defaults to "error" for production
- **v0.2.4** - Added version field to `get_logging_status` for MCP version checking
- **v0.2.3** - Version bump for HPM release
- **v0.2.2** - **CRITICAL FIX**: Rules with `enabled=true` now persist correctly (switched to atomicState for immediate persistence)
- **v0.2.1** - Fixed duplicate `formatTimestamp` method compilation error
- **v0.2.0** - MCP Debug Logging System with 5 new diagnostic tools (`get_debug_logs`, `clear_debug_logs`, `get_rule_diagnostics`, `set_log_level`, `get_logging_status`)
- **v0.1.23** - Critical fix for rule creation order (enabled status set after data persistence)
- **v0.1.22** - Major bug fixes: `if_then_else`/`repeat` action returns, `device_was` validation, type coercion, button 0 support
- **v0.1.21** - Fixed negative index vulnerabilities, hue value coercion, previousMode capture, null safety improvements
- **v0.1.20** - Added `handlePeriodicEvent()` handler, fixed `cancel_delayed`, added 7 missing condition types to UI
- **v0.1.19** - Fixed `time_range` field name compatibility (accepts both `start`/`end` and `startTime`/`endTime`)
- **v0.1.18** - Removed `expression` condition type (not allowed in Hubitat sandbox)
- **v0.1.17** - UI/MCP parity: added 6 missing condition types and 12 missing action types to UI
- **v0.1.16** - Fixed duration trigger re-arming, added `clearDurationState()` to lifecycle methods
- **v0.1.15** - Fixed "required fields" validation error on rule creation
- **v0.1.14** - Fixed child app label not updating in Hubitat Apps list
- **v0.1.13** - Fixed Hubitat sandbox compatibility (removed private/static modifiers)
- **v0.1.12** - Performance improvements, configurable captured states limit (1-100), new state management tools
- **v0.1.11** - Added verification reminders to tool descriptions (verify state after commands/rules)
- **v0.1.10** - Fixed device label returning null in send_command and get_attribute responses
- **v0.1.9** - Fixed missing condition type validations (presence, lock, thermostat_mode, thermostat_state, illuminance, power)
- **v0.1.8** - Fixed duration triggers firing repeatedly (now waits for condition to go false before re-arming)
- **v0.1.7** - Fixed duration-based `device_event` triggers (now properly waits for condition to stay true)
- **v0.1.6** - Fixed `repeat` action parameter name (`times` now works correctly)
- **v0.1.5** - Fixed `capture_state`/`restore_state` to work across different rules
- **v0.1.4** - Added all remaining documented actions: `set_color`, `set_color_temperature`, `lock`/`unlock`, `capture_state`/`restore_state`, `send_notification`, `repeat`
- **v0.1.3** - Major rule engine fixes: `delay` now uses `runIn()`, added `variable` condition, `device_was` condition, and more condition types
- **v0.1.2** - Fixed missing action types: `if_then_else`, `cancel_delayed`, `set_local_variable`, `activate_scene`
- **v0.1.1** - Added pagination for `list_devices` (fixes cloud 128KB limit issue)
- **v0.1.0** - Parent/Child architecture (rules are now child apps with isolated settings)
- **v0.0.6** - Fixed trigger/condition/action save flow
- **v0.0.5** - Bug fixes for device and variable tools, UI improvements
- **v0.0.4** - Added full Rule Engine UI
- **v0.0.3** - Initial release

## Manual Testing Checklist

The following features require manual testing through the Hubitat web UI:

### UI Rule Management
- [ ] Create a new rule via Hubitat Apps → MCP Rule Server → Add Rule
- [ ] Edit existing rule triggers through the UI
- [ ] Edit existing rule conditions through the UI
- [ ] Edit existing rule actions through the UI
- [ ] Enable/disable rules via the UI toggle
- [ ] Delete a rule with confirmation dialog
- [ ] Use "Test Rule" button (dry run) to verify rule logic
- [ ] Verify rule list displays correctly with status and last triggered time

### Trigger Configuration UI
- [ ] Add device_event trigger and select device/attribute
- [ ] Add button_event trigger with button number selection
- [ ] Add time trigger with time picker
- [ ] Add sunrise/sunset trigger with offset input
- [ ] Add periodic trigger with interval configuration
- [ ] Add mode_change trigger with mode selection
- [ ] Add hsm_change trigger with status selection

### Condition Configuration UI
- [ ] Add device_state condition with operator selection
- [ ] Add time_range condition with start/end time pickers
- [ ] Add mode condition with multi-mode selection
- [ ] Add days_of_week condition with day checkboxes
- [ ] Add variable condition with variable name input
- [ ] Test conditionLogic toggle between "all" and "any"

### Action Configuration UI
- [ ] Add device_command action with command dropdown
- [ ] Add delay action with seconds input
- [ ] Add set_level action with level slider
- [ ] Add if_then_else action with nested action configuration
- [ ] Add set_variable action with scope selection
- [ ] Verify action reordering works correctly

## Contributing

This is a work in progress! Contributions welcome:

1. Fork the repo
2. Create a feature branch
3. Make your changes
4. Submit a pull request

Please include:
- Description of changes
- Any testing you've done
- Screenshots if applicable

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- Built with assistance from [Claude](https://claude.ai) (Anthropic)
- Inspired by the [Model Context Protocol](https://modelcontextprotocol.io/)
- Thanks to the Hubitat community for documentation and examples
- [@ashwinma14](https://github.com/ashwinma14) - Fix for StackOverflowError on app install ([#15](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/15))

## Disclaimer

This software is provided "as is", without warranty of any kind. This is an AI-assisted project and may contain bugs or unexpected behavior. Always test automations carefully, especially those controlling critical devices. The authors are not responsible for any damage or issues caused by using this software.
