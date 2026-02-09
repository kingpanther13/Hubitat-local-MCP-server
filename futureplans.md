# Future Plans

> **Blue-sky ideas** — everything below is speculative and needs further research to determine feasibility. None of these features are guaranteed or committed to.
>
> **Status key:** `[ ]` = not started | `[~]` = in progress / partially done | `[x]` = completed | `[?]` = needs research / feasibility unknown

---

## Rule Engine Enhancements

### Trigger Enhancements
- [ ] Endpoint/webhook triggers
- [ ] Hub variable change triggers
- [ ] Conditional triggers (evaluate at trigger time)
- [ ] System start trigger
- [ ] Date range triggers
- [ ] Cron/periodic triggers

### Condition Enhancements
- [ ] Required Expressions (rule gates) with in-flight action cancellation
- [ ] Full boolean expression builder (AND/OR/XOR/NOT with nesting)
- [ ] Private Boolean per rule

### Action Enhancements
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

### Variable System
- [ ] Hub Variable Connectors (expose as device attributes)
- [ ] Variable change events
- [ ] Local variable triggers

---

## Built-in Automation Equivalents
- [ ] Room Lighting (room-centric lighting with vacancy mode)
- [ ] Zone Motion Controller (multi-sensor zones)
- [ ] Mode Manager (automated mode changes)
- [ ] Button Controller (streamlined button-to-action mapping)
- [ ] Thermostat Scheduler (schedule-based setpoints)
- [ ] Lock Code Manager
- [ ] Groups and Scenes (Zigbee group messaging)

---

## HPM & App/Integration Management
- [ ] Search HPM repositories by keyword
- [ ] Install/uninstall packages via HPM
- [ ] Check for updates across installed packages
- [ ] Search for official integrations not yet enabled
- [ ] Discover community apps/drivers from GitHub, forums, etc.

---

## Dashboard Management
- [ ] Create, modify, delete dashboards programmatically
- [ ] Prefer official Hubitat dashboard system for home screen and mobile app visibility

---

## Rule Machine Interoperability

> **Feasibility researched** — creating/modifying RM rules is not possible (closed-source, undocumented format). However, controlling existing RM rules IS feasible.

- [ ] List all RM rules via `RMUtils.getRuleList()`
- [ ] Enable/disable RM rules
- [ ] Trigger RM rule actions via `RMUtils.sendAction()`
- [ ] Pause/resume RM rules
- [ ] Set RM Private Booleans
- [ ] Hub variable bridge for cross-engine coordination

---

## Integration & Streaming
- [ ] MQTT client (bridge to Node-RED, Home Assistant, etc.)
- [ ] Event streaming / webhooks (real-time POST of device events)

---

## Advanced Automation Patterns
- [ ] Occupancy / room state machine
- [ ] Presence-based automation (first-to-arrive, last-to-leave)
- [ ] Weather-based triggers
- [ ] Vacation mode (random light cycling, auto-lock, energy savings)

---

## Monitoring & Diagnostics
- [ ] Device health watchdog
- [ ] Z-Wave ghost device detection
- [ ] Event history / analytics
- [ ] Hub performance trend monitoring

---

## Notification Enhancements
- [ ] Pushover integration with priority levels
- [ ] Email notifications via SendGrid
- [ ] Rate limiting / throttling
- [ ] Notification routing by severity

---

## Additional Ideas
- [ ] Standalone virtual device creation (independent of MCP app)
- [ ] Device pairing assistance (Z-Wave, Zigbee, cloud)
- [ ] Scene management (create/modify beyond activate_scene)
- [ ] Energy monitoring dashboard
- [ ] Scheduled automated reports
