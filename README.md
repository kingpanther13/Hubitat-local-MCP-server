# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub, with a built-in custom rule engine.

> **DISCLAIMER**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress and may have bugs, edge cases, or issues. Use at your own risk. Contributions and bug reports welcome!

## What is this?

This Hubitat app exposes an MCP server that allows AI assistants (like Claude) to:
- **Control devices** - Turn lights on/off, set levels, trigger scenes
- **Create automations** - Build rules with triggers, conditions, and actions
- **Query system state** - Get device status, hub info, modes, variables, HSM status

**New in v0.1.0:** Parent/Child architecture! Rules are now separate child apps with isolated settings, just like Hubitat's native Rule Machine. This fixes settings cross-contamination between rules.

Instead of running a separate Node.js MCP server on another machine, this runs natively on the Hubitat hub itself.

## Features

### Rule Engine UI

Manage your automation rules directly in the Hubitat web interface:

- **View all rules** with status (enabled/disabled) and last triggered time
- **Create new rules** as child apps (like Rule Machine)
- **Edit triggers** - Device events, button presses, time schedules, mode changes, and more
- **Edit conditions** - Device state, time range, mode, variables, presence, and 14 condition types total
- **Edit actions** - Device commands, scenes, delays, notifications, and 18 action types total
- **Enable/disable rules** with a single tap
- **Test rules** (dry run) to see what would happen without executing
- **Delete rules** with confirmation

### MCP Tools (19 total)

| Category | Tools |
|----------|-------|
| **Devices** | `list_devices`, `get_device`, `get_attribute`, `send_command`, `get_device_events` |
| **Rules** | `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`, `enable_rule`, `disable_rule`, `test_rule` |
| **System** | `get_hub_info`, `get_modes`, `set_mode`, `get_hsm_status`, `set_hsm`, `list_variables`, `get_variable`, `set_variable` |

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

## Requirements

- Hubitat Elevation C-7, C-8, or C-8 Pro
- Hubitat firmware with OAuth support (any recent version)

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

## Version History

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

## Disclaimer

This software is provided "as is", without warranty of any kind. This is an AI-assisted project and may contain bugs or unexpected behavior. Always test automations carefully, especially those controlling critical devices. The authors are not responsible for any damage or issues caused by using this software.
