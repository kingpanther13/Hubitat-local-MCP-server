# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub, with a built-in custom rule engine.

> **ALPHA SOFTWARE**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress and may have bugs, edge cases, or issues. Use at your own risk. Contributions and bug reports welcome!
>
> **Found a bug?** Please report issues at [GitHub Issues](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues). For easier bug reporting:
> 1. Set debug log level: Settings → MCP Debug Log Level → "Debug", or ask your AI to `set_log_level` to "debug"
> 2. Reproduce the issue
> 3. Ask your AI to use the `generate_bug_report` tool - it will gather diagnostics and format a ready-to-submit report

## What is this?

This Hubitat app exposes an MCP server that allows AI assistants (like Claude) to:
- **Control devices** - Turn lights on/off, set levels, trigger scenes
- **Create automations** - Build rules with triggers, conditions, and actions
- **Query system state** - Get device status, hub info, modes, variables, HSM status

**New in v0.2.0:** MCP-accessible debug logging system! Debug rules and troubleshoot issues directly through MCP without needing to access the Hubitat UI. Includes 5 new diagnostic tools.

**v0.1.0:** Parent/Child architecture! Rules are now separate child apps with isolated settings, just like Hubitat's native Rule Machine.

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

### MCP Tools (30 total)

| Category | Tools |
|----------|-------|
| **Devices** | `list_devices`, `get_device`, `get_attribute`, `send_command`, `get_device_events` |
| **Rules** | `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`, `enable_rule`, `disable_rule`, `test_rule` |
| **System** | `get_hub_info`, `get_modes`, `set_mode`, `get_hsm_status`, `set_hsm`, `list_variables`, `get_variable`, `set_variable` |
| **State Capture** | `list_captured_states`, `delete_captured_state`, `clear_captured_states` |
| **Debug/Diagnostics** | `get_debug_logs`, `clear_debug_logs`, `get_rule_diagnostics`, `set_log_level`, `get_logging_status`, `generate_bug_report` |

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

## Version History

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
