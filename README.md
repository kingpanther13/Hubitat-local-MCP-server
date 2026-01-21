# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub, with a built-in custom rule engine.

> **DISCLAIMER**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress and may have bugs, edge cases, or issues. Use at your own risk. Contributions and bug reports welcome!

## What is this?

This Hubitat app exposes an MCP server that allows AI assistants (like Claude) to:
- **Control devices** - Turn lights on/off, set levels, trigger scenes
- **Create automations** - Build rules with triggers, conditions, and actions
- **Query system state** - Get device status, hub info, modes, variables, HSM status

Instead of running a separate Node.js MCP server on another machine, this runs natively on the Hubitat hub itself.

## Features

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

## Quick Start

### 1. Install the App

1. Go to Hubitat web UI → **Apps Code** → **+ New App**
2. Copy contents of [`hubitat-mcp-server.groovy`](hubitat-mcp-server.groovy)
3. Paste and click **Save**
4. Click **OAuth** → **Enable OAuth in App** → **Save**

### 2. Add the App

1. Go to **Apps** → **+ Add User App** → **MCP Rule Server**
2. Select devices you want accessible via MCP
3. Click **Done**
4. Open the app to see your endpoint URL

### 3. Configure Your MCP Client

Your endpoint URL will look like:
```
http://YOUR_HUB_IP/apps/api/123/mcp?access_token=YOUR_TOKEN
```

Add to your MCP client configuration (e.g., Claude Desktop):
```json
{
  "mcpServers": {
    "hubitat": {
      "type": "url",
      "url": "http://192.168.1.100/apps/api/123/mcp?access_token=abc123..."
    }
  }
}
```

## Remote Access (Optional)

For remote access, you can expose the endpoint via [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/):

```yaml
# cloudflared config.yml
ingress:
  - hostname: hubitat-mcp.yourdomain.com
    service: http://YOUR_HUB_IP:80
  - service: http_status:404
```

Then use:
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

## Limitations

- Rules are stored in app state (~1MB limit), so very large numbers of complex rules may hit storage limits
- No real-time event streaming (MCP responses only, no push notifications)
- Time triggers use Hubitat's `schedule()` which has some limitations
- Sunrise/sunset times are recalculated daily

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
