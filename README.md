# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub. Instead of running a separate Node.js server on another machine, this runs natively on the hub itself — with a built-in rule engine and 74 MCP tools.

> **BETA SOFTWARE**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress — contributions and [bug reports](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues) are welcome!

## What Is This?

This app lets AI assistants like Claude control your Hubitat smart home through natural language. Just talk to it:

> "Turn on the living room lights"

> "What's the temperature in the bedroom?"

> "Create a rule that turns off all lights at midnight"

> "When motion is detected in the hallway, turn on the hallway light for 5 minutes"

> "When the temperature stays above 78 for 5 minutes, turn on the AC"

> "Turn on outdoor lights at sunset"

> "When the bedroom button is double-tapped, toggle the bedroom lights"

> "What's the hub's health status?"

Behind the scenes, the AI uses MCP tools to control devices, create automation rules, manage rooms, query system state, and administer the hub.

## Requirements

- Hubitat Elevation C-7, C-8, or C-8 Pro
- Hubitat firmware 2.3.0+ (for OAuth and internal API support)

## Installation

### Option A: Hubitat Package Manager (Recommended)

If you don't have Hubitat Package Manager (HPM) installed yet, follow the [HPM installation instructions](https://hubitatpackagemanager.hubitatcommunity.com/installing) to set it up first.

Once HPM is installed:

1. Open HPM > **Install**
2. Search for **"MCP"**
3. Select **MCP Rule Server** and install

That's it! HPM will install both the parent app and child app automatically and notify you when updates are available.

> **Alternate HPM method**: You can also use HPM > **Install** > **From a URL** and paste:
> ```
> https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/packageManifest.json
> ```

### Option B: Manual Installation

You need to install **two** app files:

**1. Install the Parent App (MCP Rule Server):**
1. Go to Hubitat web UI > **Apps Code** > **+ New App**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy
   ```
3. Click **Import** > **OK** > **Save**
4. Click **OAuth** > **Enable OAuth in App** > **Save**

**2. Install the Child App (MCP Rule):**
1. Go to **Apps Code** > **+ New App**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-rule.groovy
   ```
3. Click **Import** > **OK** > **Save**
4. (No OAuth needed for the child app)

## Quick Start

### 1. Add the App

1. Go to **Apps** > **+ Add User App** > **MCP Rule Server**
2. Select devices you want accessible via MCP
3. Click **Done**
4. Open the app to see your endpoint URLs and manage rules

### 2. Get Your Endpoint URL

The app shows two endpoint URLs:

- **Local Endpoint** — for use on your local network:
  ```
  http://192.168.1.100/apps/api/123/mcp?access_token=YOUR_TOKEN
  ```

- **Cloud Endpoint** — for remote access (requires Hubitat Cloud subscription):
  ```
  https://cloud.hubitat.com/api/YOUR_HUB_ID/apps/123/mcp?access_token=YOUR_TOKEN
  ```

### 3. Connect Your AI Client

<details>
<summary><b>Claude Code (CLI)</b></summary>

Add to your MCP settings file (`~/.claude.json` or project `.mcp.json`):

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

For remote access, use the Hubitat Cloud URL instead.

</details>

<details>
<summary><b>Claude Desktop</b></summary>

Add to your Claude Desktop config file:
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

</details>

<details>
<summary><b>Claude.ai (Connectors)</b></summary>

Claude.ai supports MCP servers through **Connectors**:

1. Go to [claude.ai](https://claude.ai) > **Settings** > **Connectors**
2. Add a new connector with your Hubitat endpoint URL
3. Use the **Cloud Endpoint** URL for remote access, or use a Cloudflare Tunnel URL

With Hubitat Cloud, you can control your smart home from claude.ai anywhere — no local setup required!

</details>

<details>
<summary><b>Other AI Services</b></summary>

Any AI service that supports MCP servers via HTTP URL can use this server. Use either:

- **Hubitat Cloud URL** — no additional setup needed with a Hubitat Cloud subscription
- **Cloudflare Tunnel** — for free self-hosted remote access (see [Remote Access](#remote-access-options))

</details>

## Agent Skill for Claude.ai (Optional)

An **Agent Skill** is a knowledge pack that teaches Claude best practices for using this MCP server — device safety protocols, rule creation patterns, tool usage tips, and more. It's not required (Claude works fine without it), but it helps Claude make better decisions, especially around safety-critical operations like device authorization and hub admin tools.

**To install:**
1. Download the `agent-skill/hubitat-mcp/` folder from this repository
2. Zip it so the folder is the root: `hubitat-mcp.zip` > `hubitat-mcp/` > `SKILL.md`, etc.
3. Go to [claude.ai](https://claude.ai) > **Settings** > **Features** > **Skills**
4. Upload the zip file

The skill works alongside the MCP connector — the connector gives Claude the tools, and the skill teaches Claude how to use them well.

> **For Claude Code users**: You can also copy the skill folder to `~/.claude/skills/hubitat-mcp/` for automatic loading.

## Remote Access Options

<details>
<summary><b>Option 1: Hubitat Cloud (Easiest)</b></summary>

If you have a [Hubitat Cloud](https://hubitat.com/pages/remote-admin) subscription:

1. The cloud endpoint URL is shown directly in the app
2. Use that URL in your MCP client configuration
3. No additional setup required!

</details>

<details>
<summary><b>Option 2: Cloudflare Tunnel (Free, Self-Hosted)</b></summary>

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

</details>

---

## Features

### MCP Tools (74 total)

<details>
<summary><b>Devices</b> (5) — Control and query physical devices</summary>

| Tool | Description |
|------|-------------|
| `list_devices` | List accessible devices (supports pagination) |
| `get_device` | Full device details: attributes, commands, capabilities |
| `get_attribute` | Get a specific attribute value |
| `send_command` | Send a command (on, off, setLevel, etc.) |
| `get_device_events` | Recent events for a device |

</details>

<details>
<summary><b>Virtual Devices</b> (4) — Create and manage MCP-managed virtual devices</summary>

| Tool | Description |
|------|-------------|
| `create_virtual_device` | Create a virtual device (15 types: switch, dimmer, sensor, etc.) |
| `list_virtual_devices` | List all MCP-managed virtual devices |
| `delete_virtual_device` | Delete an MCP-managed virtual device |
| `update_device` | Update device properties (label, room, preferences, etc.) |

</details>

<details>
<summary><b>Rooms</b> (5) — Manage room organization</summary>

| Tool | Description |
|------|-------------|
| `list_rooms` | List all rooms with device counts |
| `get_room` | Room details with full device info |
| `create_room` | Create a new room |
| `delete_room` | Delete a room (devices become unassigned) |
| `rename_room` | Rename an existing room |

</details>

<details>
<summary><b>Rules</b> (11) — Create and manage automation rules</summary>

| Tool | Description |
|------|-------------|
| `list_rules` | List all rules with status |
| `get_rule` | Full rule details (triggers, conditions, actions) |
| `create_rule` | Create a new automation rule |
| `update_rule` | Update rule triggers, conditions, or actions |
| `delete_rule` | Delete a rule (auto-backs up first) |
| `enable_rule` | Enable a disabled rule |
| `disable_rule` | Disable a rule without deleting |
| `test_rule` | Dry-run: see what would happen without executing |
| `export_rule` | Export rule as portable JSON |
| `import_rule` | Import a rule from exported JSON |
| `clone_rule` | Duplicate an existing rule |

</details>

<details>
<summary><b>System</b> (9) — Hub modes, variables, and HSM</summary>

| Tool | Description |
|------|-------------|
| `get_hub_info` | Basic hub information |
| `get_modes` | List location modes |
| `set_mode` | Change location mode (Home, Away, Night, etc.) |
| `get_hsm_status` | Get Home Security Monitor status |
| `set_hsm` | Change HSM arm mode |
| `list_variables` | List all hub variables |
| `get_variable` | Get a specific variable value |
| `set_variable` | Set a variable value |
| `check_for_update` | Check for MCP server updates |

</details>

<details>
<summary><b>State Capture</b> (3) — Save and restore device states</summary>

| Tool | Description |
|------|-------------|
| `list_captured_states` | List saved device state snapshots |
| `delete_captured_state` | Delete a specific state snapshot |
| `clear_captured_states` | Delete all state snapshots |

</details>

<details>
<summary><b>Debug & Diagnostics</b> (6) — MCP-specific logging and diagnostics</summary>

| Tool | Description |
|------|-------------|
| `get_debug_logs` | Retrieve MCP debug log entries |
| `clear_debug_logs` | Clear all MCP debug logs |
| `get_rule_diagnostics` | Comprehensive diagnostics for a specific rule |
| `set_log_level` | Set MCP log level (debug/info/warn/error) |
| `get_logging_status` | View logging system statistics |
| `generate_bug_report` | Generate comprehensive diagnostic report |

</details>

<details>
<summary><b>Monitoring</b> (4) — Hub logs, device history, and health checks</summary>

| Tool | Description |
|------|-------------|
| `get_hub_logs` | Hub log entries with level/source filtering |
| `get_device_history` | Up to 7 days of device event history |
| `get_hub_performance` | Memory, temperature, database size |
| `device_health_check` | Find stale/offline devices |

Requires Hub Admin Read to be enabled.

</details>

<details>
<summary><b>Hub Admin Read</b> (8) — Read-only hub system information</summary>

| Tool | Description |
|------|-------------|
| `get_hub_details` | Model, firmware, IP, uptime, memory, temp, database |
| `list_hub_apps` | List all user-installed apps |
| `list_hub_drivers` | List all user-installed drivers |
| `get_zwave_details` | Z-Wave radio info |
| `get_zigbee_details` | Zigbee radio info |
| `get_hub_health` | Health dashboard with warnings |
| `get_app_source` | Retrieve app source code |
| `get_driver_source` | Retrieve driver source code |

Disabled by default. Enable in app settings under **Hub Admin Access**.

</details>

<details>
<summary><b>Hub Admin Write</b> (10) — Backup, reboot, install/update apps and drivers</summary>

| Tool | Description |
|------|-------------|
| `create_hub_backup` | Create full hub database backup |
| `reboot_hub` | Reboot the hub (1-3 min downtime) |
| `shutdown_hub` | Power off hub (needs manual restart) |
| `zwave_repair` | Start Z-Wave network repair (5-30 min) |
| `install_app` | Install a new Groovy app from source |
| `install_driver` | Install a new Groovy driver from source |
| `update_app_code` | Update existing app source code |
| `update_driver_code` | Update existing driver source code |
| `delete_app` | Delete an installed app (auto-backs up) |
| `delete_driver` | Delete an installed driver (auto-backs up) |

All write tools enforce a **three-layer safety gate**: Hub Admin Write must be enabled + a hub backup within 24 hours + explicit `confirm=true`.

</details>

<details>
<summary><b>Device Admin</b> (1) — Device deletion for ghost/orphaned devices</summary>

| Tool | Description |
|------|-------------|
| `delete_device` | Permanently delete a device (**no undo**) |

Intended for ghost/orphaned devices only. Requires Hub Admin Write.

</details>

<details>
<summary><b>Item Backups</b> (3) — View and restore automatic source code backups</summary>

| Tool | Description |
|------|-------------|
| `list_item_backups` | List all saved source code backups |
| `get_item_backup` | Retrieve source from a backup |
| `restore_item_backup` | Restore app/driver to backed-up version |

Source code is automatically backed up before any modify/delete operation.

</details>

<details>
<summary><b>File Manager</b> (4) — Read/write files on the hub</summary>

| Tool | Description |
|------|-------------|
| `list_files` | List all files in File Manager |
| `read_file` | Read a file's contents |
| `write_file` | Create or update a file (auto-backs up existing) |
| `delete_file` | Delete a file (auto-backs up first) |

</details>

<details>
<summary><b>Reference</b> (1) — On-demand tool documentation</summary>

| Tool | Description |
|------|-------------|
| `get_tool_guide` | Full tool reference from the MCP server itself |

</details>

### Rule Engine

Create automations via natural language — the AI translates your request into rules with triggers, conditions, and actions. You can also manage rules through the Hubitat web UI.

<details>
<summary><b>Supported Triggers</b> (6 types)</summary>

| Type | Description |
|------|-------------|
| `device_event` | When a device attribute changes (with optional duration for debouncing) |
| `button_event` | Button pressed, held, double-tapped, or released |
| `time` | At a specific time, or relative to sunrise/sunset with offset |
| `periodic` | Repeat at intervals (minutes, hours, or days) |
| `mode_change` | When hub mode changes |
| `hsm_change` | When HSM status changes |

</details>

<details>
<summary><b>Supported Conditions</b> (14 types)</summary>

| Type | Description |
|------|-------------|
| `device_state` | Check current device attribute value |
| `device_was` | Device has been in state for X seconds (anti-cycling) |
| `time_range` | Within a time window (supports sunrise/sunset) |
| `mode` | Current hub mode |
| `variable` | Hub or rule-local variable value |
| `days_of_week` | Specific days |
| `sun_position` | Sun above or below horizon |
| `hsm_status` | Current HSM arm status |
| `presence` | Presence sensor status |
| `lock` | Lock status |
| `thermostat_mode` | Thermostat operating mode |
| `thermostat_state` | Thermostat operating state |
| `illuminance` | Light level (lux) with comparison |
| `power` | Power consumption (watts) with comparison |

</details>

<details>
<summary><b>Supported Actions</b> (29 types)</summary>

| Type | Description |
|------|-------------|
| `device_command` | Send command to device |
| `toggle_device` | Toggle device on/off |
| `activate_scene` | Activate a scene device |
| `set_level` | Set dimmer level with optional duration |
| `set_color` | Set color on RGB devices |
| `set_color_temperature` | Set color temperature |
| `lock` / `unlock` | Lock or unlock a device |
| `set_variable` | Set a global variable |
| `set_local_variable` | Set a rule-scoped variable |
| `set_mode` | Change hub mode |
| `set_hsm` | Change HSM arm mode |
| `delay` | Wait before next action (with optional ID for cancellation) |
| `if_then_else` | Conditional branching |
| `cancel_delayed` | Cancel pending delayed actions |
| `repeat` | Repeat actions N times |
| `stop` | Stop rule execution |
| `log` | Write to Hubitat logs |
| `capture_state` / `restore_state` | Save and restore device states |
| `send_notification` | Push notification |
| `set_thermostat` | Thermostat mode, setpoints, fan mode |
| `http_request` | HTTP GET/POST (webhooks, external APIs) |
| `speak` | Text-to-speech with optional volume |
| `comment` | Documentation-only (no-op) |
| `set_valve` | Open or close a valve |
| `set_fan_speed` | Set fan speed |
| `set_shade` | Open, close, or position window shades |
| `variable_math` | Arithmetic on variables |

</details>

<details>
<summary><b>Rule Examples</b></summary>

**Motion-activated light:**
```json
{
  "name": "Motion Light",
  "triggers": [
    { "type": "device_event", "deviceId": "123", "attribute": "motion", "value": "active" }
  ],
  "conditions": [
    { "type": "time_range", "startTime": "sunset", "endTime": "sunrise" }
  ],
  "actions": [
    { "type": "device_command", "deviceId": "456", "command": "on" },
    { "type": "delay", "seconds": 300, "delayId": "motion-off" },
    { "type": "device_command", "deviceId": "456", "command": "off" }
  ]
}
```

**Temperature with debouncing:**
```json
{
  "name": "AC On When Hot",
  "triggers": [
    { "type": "device_event", "deviceId": "1", "attribute": "temperature",
      "operator": ">", "value": "78", "duration": 300 }
  ],
  "actions": [
    { "type": "device_command", "deviceId": "8", "command": "on" }
  ]
}
```

**Button state machine with local variables:**
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
      "condition": { "type": "variable", "variableName": "lastScene",
                     "operator": "equals", "value": "natural" },
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

</details>

---

## Hub Admin Tools

Both Hub Admin Read and Hub Admin Write access are **disabled by default** and must be explicitly enabled in app settings.

<details>
<summary><b>Enabling Hub Admin Tools</b></summary>

1. Open **Apps** > **MCP Rule Server** in the Hubitat web UI
2. Under **Hub Admin Access**, toggle:
   - **Enable Hub Admin Read Tools** — for read-only hub information
   - **Enable Hub Admin Write Tools** — for backup, reboot, shutdown, Z-Wave repair, and app/driver management
3. If your hub has **Hub Security** enabled, also configure:
   - **Hub Security Username** and **Password** under the Hub Security section

</details>

<details>
<summary><b>Safety Gates</b></summary>

All Hub Admin Write tools enforce a **three-layer safety gate**:
1. Hub Admin Write must be **enabled** in settings
2. The AI must pass `confirm=true` explicitly
3. A full hub **backup must exist within the last 24 hours** (enforced automatically)

Additionally, tools that modify or delete existing apps/drivers automatically back up the item's source code before making changes.

</details>

<details>
<summary><b>Item Backup & Restore</b></summary>

When you use `update_app_code`, `update_driver_code`, `delete_app`, or `delete_driver`, the server automatically saves the **original source code** before making changes.

- Backups stored as `.groovy` files in the hub's local **File Manager**
- Named `mcp-backup-app-<id>.groovy` or `mcp-backup-driver-<id>.groovy`
- Persist even if the MCP app is uninstalled
- Downloadable at `http://<your-hub-ip>/local/<filename>`
- Max 20 kept; oldest pruned automatically
- 1-hour protection window: multiple edits preserve the pre-edit original

**Restore via MCP:**
1. `list_item_backups` to see available backups
2. `restore_item_backup` with the backup key and `confirm=true`

**Restore manually (without MCP):**
1. Go to Hubitat web UI > **Settings** > **File Manager**
2. Download the backup file (e.g., `mcp-backup-app-123.groovy`)
3. Go to **Apps Code** (or **Drivers Code**) > select the app > paste source > **Save**

</details>

<details>
<summary><b>Hub Security Support</b></summary>

If your hub has Hub Security enabled (login required for the web UI), the MCP server handles authentication automatically:
- Configure your Hub Security username and password in the app settings
- The server caches the session cookie for 30 minutes
- Stale cookies are automatically cleared and re-authenticated
- If Hub Security is not enabled, no credentials are needed

</details>

---

## Performance & Limits

<details>
<summary><b>Hub Hardware Recommendations</b></summary>

| Hub Model | Recommendation |
|-----------|----------------|
| **C-7** | Works for basic use, may be slow with large device lists or complex rules |
| **C-8** | Good for most users |
| **C-8 Pro** | Best for heavy use, large device counts (100+), or complex automations |

</details>

<details>
<summary><b>Known Limits</b></summary>

- **`list_devices` with `detailed=true`** — Can be slow on 50+ devices. Use pagination: `list_devices(detailed=true, limit=25, offset=0)`
- **Duration triggers** — Maximum of 2 hours (7200 seconds)
- **Captured states** — Default limit of 20 (configurable 1-100 in settings)
- **Hubitat Cloud responses** — 128KB maximum (AWS MQTT limit). Use pagination for large device lists.
- No real-time event streaming (MCP responses only)
- Sunrise/sunset times are recalculated daily

</details>

---

## Troubleshooting

<details>
<summary><b>Device not found</b></summary>

Make sure the device is selected in the app's "Select Devices for MCP Access" setting.

</details>

<details>
<summary><b>OAuth token not working</b></summary>

1. Open Apps Code > MCP Rule Server
2. Click OAuth > Enable OAuth in App
3. Save
4. Re-open the app in Apps to get the new token

</details>

<details>
<summary><b>Rules not triggering</b></summary>

- Check that "Enable Rule Engine" is on in app settings
- Enable "Debug Logging" and check Hubitat Logs
- Verify the trigger device is selected for MCP access
- For duration-based triggers, ensure the condition stays true for the full duration

</details>

<details>
<summary><b>Button events not working</b></summary>

- Make sure you're using `button_event` trigger type (not `device_event`)
- Verify the button action type: `pushed`, `held`, `doubleTapped`, or `released`

</details>

<details>
<summary><b>list_devices(detailed=true) fails over Hubitat Cloud</b></summary>

Hubitat Cloud has a **128KB response size limit** (AWS MQTT limitation). Use pagination:

```
list_devices(detailed=true, limit=25, offset=0)   // First 25 devices
list_devices(detailed=true, limit=25, offset=25)  // Next 25 devices
```

The response includes `total`, `hasMore`, and `nextOffset` to help with pagination.

</details>

<details>
<summary><b>Rules from v0.0.x not showing</b></summary>

Version 0.1.0 uses a new parent/child architecture. Old rules stored in `state.rules` are not migrated automatically. You'll need to recreate rules either through the UI or via MCP.

</details>

<details>
<summary><b>Reporting bugs</b></summary>

For easier bug reporting:
1. Set debug log level: Settings > MCP Debug Log Level > "Debug", or ask your AI to `set_log_level` to "debug"
2. Reproduce the issue
3. Ask your AI to use the `generate_bug_report` tool — it will gather diagnostics and format a ready-to-submit report
4. Submit at [GitHub Issues](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues)

</details>

---

<!-- FUTURE_PLANS_START -->
## Future Plans


> **Blue-sky ideas** — everything below is speculative and needs further research to determine feasibility. None of these features are guaranteed or committed to.
>
> **Status key:** `[ ]` = not started | `[~]` = in progress / partially done | `[x]` = completed | `[?]` = needs research / feasibility unknown

---

#### Rule Engine Enhancements

#### Trigger Enhancements
- [ ] Endpoint/webhook triggers
- [ ] Hub variable change triggers
- [ ] Conditional triggers (evaluate at trigger time)
- [ ] System start trigger
- [ ] Date range triggers
- [ ] Cron/periodic triggers

#### Condition Enhancements
- [ ] Required Expressions (rule gates) with in-flight action cancellation
- [ ] Full boolean expression builder (AND/OR/XOR/NOT with nesting)
- [ ] Private Boolean per rule

#### Action Enhancements
- [ ] Fade dimmer over time
- [ ] Change color temperature over time
- [ ] Per-mode actions
- [ ] Wait for Event / Wait for Expression
- [ ] Repeat While / Repeat Until
- [ ] Rule-to-rule control
- [ ] File write/append/delete
- [ ] Ping IP address
- [ ] Custom Action (any capability + command)
- [ ] Music/siren control
- [ ] Disable/Enable a device
- [ ] Ramp actions (continuous raise/lower)

#### Variable System
- [ ] Hub Variable Connectors (expose as device attributes)
- [ ] Variable change events
- [ ] Local variable triggers

---

#### Built-in Automation Equivalents
- [ ] Room Lighting (room-centric lighting with vacancy mode)
- [ ] Zone Motion Controller (multi-sensor zones)
- [ ] Mode Manager (automated mode changes)
- [ ] Button Controller (streamlined button-to-action mapping)
- [ ] Thermostat Scheduler (schedule-based setpoints)
- [ ] Lock Code Manager
- [ ] Groups and Scenes (Zigbee group messaging)

---

#### HPM & App/Integration Management
- [ ] Search HPM repositories by keyword
- [ ] Install/uninstall packages via HPM
- [ ] Check for updates across installed packages
- [ ] Search for official integrations not yet enabled
- [ ] Discover community apps/drivers from GitHub, forums, etc.

---

#### Dashboard Management
- [ ] Create, modify, delete dashboards programmatically
- [ ] Prefer official Hubitat dashboard system for home screen and mobile app visibility

---

#### Rule Machine Interoperability

> **Feasibility researched** — creating/modifying RM rules is not possible (closed-source, undocumented format). However, controlling existing RM rules IS feasible.

- [ ] List all RM rules via `RMUtils.getRuleList()`
- [ ] Enable/disable RM rules
- [ ] Trigger RM rule actions via `RMUtils.sendAction()`
- [ ] Pause/resume RM rules
- [ ] Set RM Private Booleans
- [ ] Hub variable bridge for cross-engine coordination

---

#### Integration & Streaming
- [ ] MQTT client (bridge to Node-RED, Home Assistant, etc.)
- [ ] Event streaming / webhooks (real-time POST of device events)

---

#### Advanced Automation Patterns
- [ ] Occupancy / room state machine
- [ ] Presence-based automation (first-to-arrive, last-to-leave)
- [ ] Weather-based triggers
- [ ] Vacation mode (random light cycling, auto-lock, energy savings)

---

#### Monitoring & Diagnostics
- [ ] Device health watchdog
- [ ] Z-Wave ghost device detection
- [ ] Event history / analytics
- [ ] Hub performance trend monitoring

---

#### Notification Enhancements
- [ ] Pushover integration with priority levels
- [ ] Email notifications via SendGrid
- [ ] Rate limiting / throttling
- [ ] Notification routing by severity

---

#### Additional Ideas
- [ ] Standalone virtual device creation (independent of MCP app)
- [ ] Device pairing assistance (Z-Wave, Zigbee, cloud)
- [ ] Scene management (create/modify beyond activate_scene)
- [ ] Energy monitoring dashboard
- [ ] Scheduled automated reports
<!-- FUTURE_PLANS_END -->


---

## Version History

<details>
<summary><b>Recent versions (v0.7.0 – v0.7.6)</b></summary>

- **v0.7.6** - Code review: fix hoursAgo calculation bug, fix variable shadowing, centralize version string, extract shared helpers (~90 lines reduced)
- **v0.7.5** - Token efficiency: lean tool descriptions with progressive disclosure via `get_tool_guide` (~27% token reduction)
- **v0.7.4** - Stability: configurable execution loop guard with push notifications, safe room move, resilient date parsing
- **v0.7.3** - Documentation sync (SKILL.md section names match source code structure)
- **v0.7.2** - Device authorization safety + optimized tool descriptions + get_tool_guide (74 tools)
- **v0.7.1** - Auto-backup for delete_rule, testRule flag, bug fixes
- **v0.7.0** - Room management: list_rooms, get_room, create_room, delete_room, rename_room (73 tools)

</details>

<details>
<summary><b>Older versions (v0.0.3 – v0.6.15)</b></summary>

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
- **v0.6.1** - Fix BigDecimal.round() crash in version update checker (67 tools)
- **v0.6.0** - Virtual device creation and management (67 tools)
- **v0.5.4** - Fix BigDecimal arithmetic with pure integer math in `device_health_check` and `delete_device` (64 tools)
- **v0.5.3** - Fix `BigDecimal.round()` in `device_health_check` (64 tools)
- **v0.5.2** - Fix `device_health_check` error handling (64 tools)
- **v0.5.1** - Fix `get_hub_logs` JSON array parsing (64 tools)
- **v0.5.0** - Monitoring tools and device management (64 tools)
- **v0.4.8** - Fix Z-Wave and Zigbee endpoint compatibility (59 tools)
- **v0.4.7** - Comprehensive bug fixes from code review (59 tools)
- **v0.4.6** - Fix version mismatch bug in optimistic locking (59 tools)
- **v0.4.5** - Smart large-file handling (59 tools)
- **v0.4.4** - Fix bugs found during live Claude.ai testing (59 tools)
- **v0.4.3** - Comprehensive bug fixes + item backup & file manager tools (59 tools)
- **v0.4.2** - Response size safety limits (hub enforces 128KB cap)
- **v0.4.1** - Bug fixes for Hub Admin tools + two-tier backup system
- **v0.4.0** - Hub Admin Tools with Hub Security support (52 tools)
- **v0.3.3** - Multi-device trigger support and validation fixes
- **v0.3.2** - Comprehensive bug fixes (25 bugs verified and fixed)
- **v0.3.1** - Bug fixes from comprehensive v0.3.0 testing
- **v0.3.0** - Rule portability, new action types, conditional triggers (34 tools)
- **v0.2.12** - Fourth code review: Critical UI bug fixes + 16 additional bug fixes
- **v0.2.11** - Third code review (16 fixes)
- **v0.2.10** - Fixed operator handling
- **v0.2.9** - Critical bug fixes from second thorough review
- **v0.2.8** - Thorough code review fixes
- **v0.2.7** - Fixed StackOverflowError on app install/open
- **v0.2.6** - Added `generate_bug_report` tool
- **v0.2.5** - Added UI control for MCP debug log level
- **v0.2.4** - Added version field to `get_logging_status`
- **v0.2.3** - Version bump for HPM release
- **v0.2.2** - **CRITICAL FIX**: Rules with `enabled=true` now persist correctly
- **v0.2.1** - Fixed duplicate `formatTimestamp` method compilation error
- **v0.2.0** - MCP Debug Logging System (5 new diagnostic tools)
- **v0.1.23** - Critical fix for rule creation order
- **v0.1.22** - Major bug fixes: action returns, validation, type coercion
- **v0.1.21** - Fixed negative index vulnerabilities, null safety improvements
- **v0.1.20** - Added `handlePeriodicEvent()`, fixed `cancel_delayed`, 7 missing condition types
- **v0.1.19** - Fixed `time_range` field name compatibility
- **v0.1.18** - Removed `expression` condition type (not allowed in sandbox)
- **v0.1.17** - UI/MCP parity: 6 condition types + 12 action types added to UI
- **v0.1.16** - Fixed duration trigger re-arming
- **v0.1.15** - Fixed "required fields" validation error on rule creation
- **v0.1.14** - Fixed child app label not updating
- **v0.1.13** - Fixed Hubitat sandbox compatibility
- **v0.1.12** - Performance improvements, configurable captured states limit
- **v0.1.11** - Added verification reminders to tool descriptions
- **v0.1.10** - Fixed device label returning null
- **v0.1.9** - Fixed missing condition type validations
- **v0.1.8** - Fixed duration triggers firing repeatedly
- **v0.1.7** - Fixed duration-based `device_event` triggers
- **v0.1.6** - Fixed `repeat` action parameter name
- **v0.1.5** - Fixed `capture_state`/`restore_state` across rules
- **v0.1.4** - Added remaining documented actions
- **v0.1.3** - Major rule engine fixes
- **v0.1.2** - Fixed missing action types
- **v0.1.1** - Added pagination for `list_devices`
- **v0.1.0** - Parent/Child architecture
- **v0.0.6** - Fixed trigger/condition/action save flow
- **v0.0.5** - Bug fixes for device and variable tools
- **v0.0.4** - Added full Rule Engine UI
- **v0.0.3** - Initial release

</details>

---

<details>
<summary><b>Manual Testing Checklist</b></summary>

### UI Rule Management
- [ ] Create a new rule via Hubitat Apps > MCP Rule Server > Add Rule
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

</details>

## Contributing

Contributions welcome! Fork the repo, create a feature branch, make your changes, and submit a pull request. Please include a description of changes and any testing you've done.

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- Built with assistance from [Claude](https://claude.ai) (Anthropic)
- Inspired by the [Model Context Protocol](https://modelcontextprotocol.io/)
- Inspired by the [Home Assistant MCP](https://github.com/homeassistant-ai/ha-mcp/)
- Thanks to the Hubitat community for documentation and examples
- [@ashwinma14](https://github.com/ashwinma14) - Fix for StackOverflowError on app install ([#15](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/15))

## Disclaimer

This software is provided "as is", without warranty of any kind. This is an AI-assisted project and may contain bugs or unexpected behavior. Always test automations carefully, especially those controlling critical devices. The authors are not responsible for any damage or issues caused by using this software.
