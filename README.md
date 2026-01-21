# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub, with a built-in custom rule engine.

> **DISCLAIMER**: This project is ~99% AI-generated ("vibe coded") using Claude. It's a work in progress and may have bugs, edge cases, or issues. Use at your own risk. Contributions and bug reports welcome!

## What is this?

This Hubitat app exposes an MCP server that allows AI assistants (like Claude) to:
- **Control devices** - Turn lights on/off, set levels, trigger scenes
- **Create automations** - Build rules with triggers, conditions, and actions
- **Query system state** - Get device status, hub info, modes, variables

Instead of running a separate Node.js MCP server on another machine, this runs natively on the Hubitat hub itself.

## Features

### MCP Tools (16 total)

| Category | Tools |
|----------|-------|
| **Devices** | `list_devices`, `get_device`, `send_command`, `get_device_events` |
| **Rules** | `list_rules`, `get_rule`, `create_rule`, `update_rule`, `delete_rule`, `enable_rule`, `disable_rule`, `test_rule` |
| **System** | `get_hub_info`, `get_modes`, `set_mode`, `list_variables`, `get_variable`, `set_variable` |

### Rule Engine

Create automations via natural language through Claude:

**Supported Triggers:**
- `device_event` - When a device attribute changes (motion detected, button pushed, etc.)
- `time` - At a specific time
- `mode_change` - When hub mode changes

**Supported Conditions:**
- `device_state` - Check current device attribute value
- `time_range` - Check if within a time window
- `mode` - Check current hub mode
- `variable` - Check hub variable value
- `days_of_week` - Check day of week

**Supported Actions:**
- `device_command` - Send command to device
- `set_variable` - Set a variable
- `set_mode` - Change hub mode
- `delay` - Wait before next action
- `if_then_else` - Conditional branching
- `cancel_delayed` - Cancel pending delayed actions

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

> "What's the temperature in the bedroom?"

> "List all my rules"

## Rule Structure

Rules are JSON objects with triggers, conditions, and actions:

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
      "startTime": "18:00",
      "endTime": "06:00"
    }
  ],
  "conditionLogic": "all",
  "actions": [
    {
      "type": "device_command",
      "deviceId": "456",
      "command": "on"
    },
    {
      "type": "delay",
      "seconds": 300
    },
    {
      "type": "device_command",
      "deviceId": "456",
      "command": "off"
    }
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

## Limitations

- Rules are stored in app state (~1MB limit), so very large numbers of complex rules may hit storage limits
- No real-time event streaming (MCP responses only, no push notifications)
- Time triggers use Hubitat's `schedule()` which has some limitations

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
