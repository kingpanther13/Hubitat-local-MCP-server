# Rule Patterns Reference

Complete reference for automation rule structure in the Hubitat MCP Rule Server.

## Rule JSON Structure

```json
{
  "name": "Rule Name (required)",
  "triggers": [],
  "conditions": [],
  "conditionLogic": "all",
  "actions": [],
  "localVariables": {},
  "testRule": false
}
```

- `conditionLogic`: `"all"` (AND) or `"any"` (OR)
- `localVariables`: Optional key-value pairs scoped to this rule
- `testRule`: Set `true` to skip backup on deletion (for throwaway test rules)

---

## Trigger Types

### device_event
When a device attribute changes value.

```json
{
  "type": "device_event",
  "deviceId": "123",
  "attribute": "switch",
  "value": "on"
}
```

With operator and duration (debounce):
```json
{
  "type": "device_event",
  "deviceId": "1",
  "attribute": "temperature",
  "operator": ">",
  "value": "78",
  "duration": 300
}
```

Multi-device trigger:
```json
{
  "type": "device_event",
  "deviceIds": ["id1", "id2"],
  "attribute": "switch",
  "value": "on",
  "matchMode": "all"
}
```

Duration is in seconds (max 7200 = 2 hours). Operators: `==`, `!=`, `>`, `<`, `>=`, `<=`.

### button_event
Button presses on button devices.

```json
{
  "type": "button_event",
  "deviceId": "80",
  "action": "pushed",
  "buttonNumber": 1
}
```

Actions: `pushed`, `held`, `doubleTapped`, `released`.

### time
At a specific clock time or relative to sunrise/sunset.

```json
{"type": "time", "time": "08:30"}
{"type": "time", "sunrise": true, "offset": 30}
{"type": "time", "sunset": true, "offset": -15}
```

Offset is in minutes. Positive = after, negative = before.

### periodic
Recurring interval.

```json
{"type": "periodic", "interval": 30, "unit": "minutes"}
```

Units: `minutes`, `hours`, `days`.

### mode_change
When the hub's location mode changes.

```json
{"type": "mode_change", "mode": "Away"}
```

### hsm_change
When Home Security Monitor status changes.

```json
{"type": "hsm_change", "status": "armedAway"}
```

Values: `armedAway`, `armedHome`, `armedNight`, `disarmed`, `allDisarmed`.

---

## Condition Types

### device_state
Check a device's current attribute value.

```json
{
  "type": "device_state",
  "deviceId": "123",
  "attribute": "switch",
  "operator": "==",
  "value": "on"
}
```

### device_was
Device has been in a state for at least N seconds (anti-cycling).

```json
{
  "type": "device_was",
  "deviceId": "8",
  "attribute": "switch",
  "value": "off",
  "forSeconds": 600
}
```

### time_range
Current time is within a window. Supports sunrise/sunset keywords.

```json
{"type": "time_range", "startTime": "sunset", "endTime": "sunrise"}
{"type": "time_range", "startTime": "22:00", "endTime": "06:00"}
```

### mode
Current location mode matches.

```json
{"type": "mode", "mode": "Home"}
```

### variable
Hub or local variable comparison.

```json
{
  "type": "variable",
  "variableName": "myVar",
  "operator": "equals",
  "value": "someValue"
}
```

Operators: `equals`, `not_equals`, `greater_than`, `less_than`, `greater_equal`, `less_equal`, `contains`.

### days_of_week
Current day matches.

```json
{"type": "days_of_week", "days": ["Monday", "Wednesday", "Friday"]}
```

### sun_position
Sun above or below horizon.

```json
{"type": "sun_position", "position": "below"}
```

Values: `above`, `below`.

### hsm_status
Current HSM arm status.

```json
{"type": "hsm_status", "status": "disarmed"}
```

### presence
Presence sensor state.

```json
{"type": "presence", "deviceId": "42", "value": "present"}
```

Values: `present`, `not present`.

### lock
Lock device state.

```json
{"type": "lock", "deviceId": "55", "value": "locked"}
```

Values: `locked`, `unlocked`.

### thermostat_mode / thermostat_state
```json
{"type": "thermostat_mode", "deviceId": "60", "value": "heat"}
{"type": "thermostat_state", "deviceId": "60", "value": "idle"}
```

### illuminance / power
Numeric comparisons with operators.

```json
{"type": "illuminance", "deviceId": "70", "operator": "<", "value": "100"}
{"type": "power", "deviceId": "71", "operator": ">", "value": "500"}
```

---

## Action Types

### device_command
Send a command to a device.

```json
{"type": "device_command", "deviceId": "456", "command": "on"}
{"type": "device_command", "deviceId": "456", "command": "setLevel", "params": [75]}
{"type": "device_command", "deviceId": "456", "command": "setLevel", "params": [75, 2]}
```

### toggle_device
Toggle a switch on/off.

```json
{"type": "toggle_device", "deviceId": "456"}
```

### activate_scene
Activate a scene device.

```json
{"type": "activate_scene", "sceneDeviceId": "scene-123"}
```

### set_level
Set dimmer level with optional transition duration.

```json
{"type": "set_level", "deviceId": "456", "level": 75, "duration": 2}
```

### set_color
Set color on RGB devices (hue 0-100, saturation 0-100, level 0-100).

```json
{"type": "set_color", "deviceId": "456", "hue": 66, "saturation": 100, "level": 80}
```

### set_color_temperature
```json
{"type": "set_color_temperature", "deviceId": "456", "temperature": 3500}
```

### lock / unlock
```json
{"type": "lock", "deviceId": "55"}
{"type": "unlock", "deviceId": "55"}
```

### set_variable / set_local_variable
```json
{"type": "set_variable", "variableName": "globalVar", "value": "newValue"}
{"type": "set_local_variable", "variableName": "localVar", "value": "newValue"}
```

### set_mode / set_hsm
```json
{"type": "set_mode", "mode": "Away"}
{"type": "set_hsm", "status": "armAway"}
```

### delay
Wait before the next action. Use `delayId` for targeted cancellation.

```json
{"type": "delay", "seconds": 300, "delayId": "motion-off"}
```

### cancel_delayed
Cancel pending delayed actions.

```json
{"type": "cancel_delayed", "delayId": "motion-off"}
{"type": "cancel_delayed"}
```

Without `delayId`, cancels ALL pending delays for the rule.

### if_then_else
Conditional branching within actions.

```json
{
  "type": "if_then_else",
  "condition": {"type": "device_state", "deviceId": "123", "attribute": "switch", "operator": "==", "value": "on"},
  "thenActions": [{"type": "device_command", "deviceId": "123", "command": "off"}],
  "elseActions": [{"type": "device_command", "deviceId": "123", "command": "on"}]
}
```

### repeat
Loop actions N times.

```json
{
  "type": "repeat",
  "count": 3,
  "actions": [
    {"type": "device_command", "deviceId": "456", "command": "on"},
    {"type": "delay", "seconds": 1},
    {"type": "device_command", "deviceId": "456", "command": "off"},
    {"type": "delay", "seconds": 1}
  ]
}
```

### stop
Stop rule execution.

```json
{"type": "stop"}
```

### log
Write to Hubitat logs.

```json
{"type": "log", "message": "Rule fired: temperature is ${temperature}"}
```

### capture_state / restore_state
Save and restore device states.

```json
{"type": "capture_state", "deviceIds": ["1", "2", "3"], "stateId": "before-movie"}
{"type": "restore_state", "stateId": "before-movie"}
```

### send_notification
Push notification to notification devices.

```json
{"type": "send_notification", "message": "Motion detected in garage", "deviceId": "notifier-1"}
```

### set_thermostat
```json
{
  "type": "set_thermostat",
  "deviceId": "60",
  "mode": "cool",
  "coolingSetpoint": 72,
  "fanMode": "auto"
}
```

### http_request
```json
{"type": "http_request", "method": "POST", "url": "https://example.com/webhook", "body": "{\"event\": \"motion\"}"}
```

### speak
Text-to-speech.

```json
{"type": "speak", "deviceId": "speaker-1", "text": "Welcome home", "volume": 50}
```

### comment
Documentation-only (no-op).

```json
{"type": "comment", "text": "This section handles nighttime logic"}
```

### set_valve / set_fan_speed / set_shade
```json
{"type": "set_valve", "deviceId": "72", "command": "open"}
{"type": "set_fan_speed", "deviceId": "73", "speed": "medium"}
{"type": "set_shade", "deviceId": "74", "command": "setPosition", "position": 50}
```

### variable_math
Arithmetic on variables.

```json
{"type": "variable_math", "variableName": "counter", "operation": "add", "operand": 1}
```

Operations: `add`, `subtract`, `multiply`, `divide`, `modulo`, `set`.

---

## Common Patterns

### Motion Light with Auto-Off
```json
{
  "name": "Hallway Motion Light",
  "triggers": [{"type": "device_event", "deviceId": "MOTION_ID", "attribute": "motion", "value": "active"}],
  "conditions": [{"type": "time_range", "startTime": "sunset", "endTime": "sunrise"}],
  "actions": [
    {"type": "cancel_delayed", "delayId": "hall-off"},
    {"type": "device_command", "deviceId": "LIGHT_ID", "command": "on"},
    {"type": "delay", "seconds": 300, "delayId": "hall-off"},
    {"type": "device_command", "deviceId": "LIGHT_ID", "command": "off"}
  ]
}
```

The `cancel_delayed` at the start resets the timer if motion fires again, preventing premature off.

### Arrival/Departure Mode Change
```json
{
  "name": "Away When Everyone Leaves",
  "triggers": [{"type": "device_event", "deviceIds": ["PRESENCE_1", "PRESENCE_2"], "attribute": "presence", "value": "not present", "matchMode": "all"}],
  "actions": [
    {"type": "set_mode", "mode": "Away"},
    {"type": "device_command", "deviceId": "LOCK_ID", "command": "lock"}
  ]
}
```

### Sunset/Sunrise Automation
```json
{
  "name": "Outdoor Lights at Sunset",
  "triggers": [{"type": "time", "sunset": true, "offset": -15}],
  "actions": [{"type": "device_command", "deviceId": "OUTDOOR_LIGHTS", "command": "on"}]
}
```
