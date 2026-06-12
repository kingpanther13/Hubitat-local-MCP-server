# Hubitat MCP Server

A native [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server that runs directly on your Hubitat Elevation hub. Instead of running a separate Node.js server on another machine, this runs natively on the hub itself — with a built-in rule engine and 98 MCP tools (30 on `tools/list` via category gateways).

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

Behind the scenes, the AI uses MCP tools to control devices, create automation rules, manage rooms, query system state, and administer the hub. The server exposes 98 tools total — 11 core tools are always visible, while the rest are organized behind 19 domain-named gateways to keep the tool list manageable. If your client handles long tool lists well, you can disable the gateways via the **Consolidate tools behind category gateways** setting and every tool is exposed individually instead. (Counts here describe the shipped catalog; the runtime count on `tools/list` varies based on enabled settings.)

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

That's it! HPM will install the parent app, the child app, and the required Groovy **libraries** (delivered as a bundle, shown under **Libraries Code**) automatically in the same install/update, and notify you when updates are available.

> **Alternate HPM method**: You can also use HPM > **Install** > **From a URL** and paste:
> ```
> https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/packageManifest.json
> ```

### Option B: Manual Installation

The parent app `#include`s Groovy **libraries**, which are all shipped together in one **bundle** (`mcp-libraries.zip`). Install that bundle **first** — otherwise the parent app fails to compile when you Save it. Install in this order: the libraries bundle, then the parent app, then the child app.

**1. Install the libraries bundle:**

In the Hubitat web UI go to **Bundles** > **Import**, and import the bundle from this repo:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/bundles/mcp-libraries.zip
   ```
   If your hub's Bundle Manager only accepts a file upload, download that `.zip` first and upload it. Importing the bundle installs **every** library the app needs in one step (they appear under **Libraries Code**) — there's no need to add libraries individually. (HPM / Option A does this automatically.)

**2. Install the Parent App (MCP Rule Server):**
1. Go to Hubitat web UI > **Apps Code** > **+ New App**
2. Click **Import** and paste this URL:
   ```
   https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy
   ```
3. Click **Import** > **OK** > **Save**
4. Click **OAuth** > **Enable OAuth in App** > **Save**

**3. Install the Child App (MCP Rule):**
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

> **Transport**: This server uses **Streamable HTTP** (not SSE or stdio). Your MCP client must support HTTP transport — most do by default.

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

Alternatively, some people have had luck just simply giving Claude access to its own directory, giving it the URL and asking it to set up its own connection.

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
Alternatively, some people have had luck just simply giving Claude access to its own directory, giving it the URL and asking it to set up its own connection.

</details>

<details>
<summary><b>Claude.ai (Connectors)</b></summary>

Claude.ai supports MCP servers through **Connectors**:

1. Go to [claude.ai](https://claude.ai) > **Settings** > **Connectors**
2. Add a new connector with your Hubitat endpoint URL
3. Use the **Cloud Endpoint** URL for remote access, or use a Cloudflare Tunnel URL

With Hubitat Cloud, you can control your smart home from claude.ai anywhere — no local setup required!

**NOTE**: when connecting on claude.ai, you will see a message stating "Couldn't reach the MCP server. You can check the server URL and verify the server is running. If this persists, share this reference with support: "ofid_1234"".

This is a known bug with Claude.ai's UI. Click on "configure" after you see this message and you will find that you are actually connected. Try asking claude to check the health of your Hubitat in a chat, and you will see it work its magic!

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

### MCP Tools (98 total — 30 on tools/list)

The server has 98 tools total. To keep the MCP `tools/list` manageable, **11 core tools** are always visible and the remaining tools are organized behind **19 domain-named gateways** (7 read-only `hub_read_*` gateways + 12 write-bearing `hub_manage_*` gateways). The AI sees 30 items on `tools/list` (11 + 19 gateways). A tool may appear under more than one gateway — read tools inside a mixed `hub_manage_*` gateway are also surfaced in a pure-read `hub_read_*` gateway. Each gateway's description includes tool summaries (always visible to the AI), and calling a gateway with no arguments returns full parameter schemas on demand.

#### Core Tools (11) — Always visible on tools/list

<details>
<summary><b>System</b> (5) — Hub modes, HSM, and info</summary>

| Tool | Description |
|------|-------------|
| `hub_get_info` | Comprehensive hub info: hardware, health, MCP stats. PII (name, IP, location) is included whenever the Read master is ON (the default) |
| `hub_list_modes` | List location modes |
| `hub_set_mode` | Change location mode (Home, Away, Night, etc.) |
| `hub_get_hsm_status` | Get Home Security Monitor status |
| `hub_set_hsm` | Change HSM arm mode |

</details>

<details>
<summary><b>Virtual Devices</b> (1) — MCP-managed virtual devices</summary>

| Tool | Description |
|------|-------------|
| `hub_manage_virtual_device` | Create or delete an MCP-managed virtual device (`action`: "create", "delete") -- supports built-in `deviceType` OR `customDriver={namespace, name}` (Write master). To list MCP-managed virtual devices with states, use `hub_list_devices` with `filter='virtual'`. |

</details>

<details>
<summary><b>Hub Utilities</b> (3) — Backup, updates, and diagnostics</summary>

| Tool | Description |
|------|-------------|
| `hub_create_backup` | Create full hub backup (required before admin writes) |
| `hub_get_update_status` | Check if a newer MCP server version is available |
| `hub_report_issue` | Generate comprehensive diagnostic report |

</details>

<details>
<summary><b>Reference</b> (2)</summary>

| Tool | Description |
|------|-------------|
| `hub_get_tool_guide` | Full tool reference from the MCP server itself |
| `hub_search_tools` | Natural-language search across all tools (BM25 ranking); returns matches with their gateway location |

</details>

#### Gateway Tools (19) — Each gateway proxies multiple tools

Call a gateway with no arguments to see full parameter schemas. Call with `tool='<name>'` and `args={...}` to execute a specific tool. Gateways split into 7 read-only `hub_read_*` gateways (every sub-tool is read-only) and 12 write-bearing `hub_manage_*` gateways (mixed read+write or write-only). A tool may be listed in more than one gateway — reads inside a mixed `hub_manage_*` gateway are also surfaced in a `hub_read_*` gateway.

##### Read-only gateways (7)

<details>
<summary><b>hub_read_apps_code</b> (11) — App/driver/library listing, source, backups, and HPM (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_apps` | List apps on the hub. `scope='types'` lists installed app types (code); `scope='instances'` enumerates all app instances (built-in + user) with parent/child tree, filterable by builtin/user/disabled/parents/children. |
| `hub_list_drivers` | List all installed drivers on the hub |
| `hub_get_source` | Get Groovy source code for an app, driver, or library (`type`: "app", "driver", "library"; `id`). Supports chunked reading via `offset`/`length`. |
| `hub_list_libraries` | List all installed Groovy libraries (id, name, namespace) |
| `hub_list_bundles` | List installed code bundles — the Bundles page, distinct from Libraries Code (id, name, namespace, contents) |
| `hub_list_backups` | List auto-created source code backups |
| `hub_get_backup` | Get source from a backup |
| `hub_list_device_dependents` | Find all apps that reference a specific device (Room Lighting, Rule Machine, Groups, Mode Manager, dashboards, Maker API, etc.) |
| `hub_get_app_config` | Read an installed app's configuration page (Rule Machine, Room Lighting, Basic Rules, HPM, etc.) — sections, inputs, values. Multi-page apps via `pageName`. Read-only. Read master. |
| `hub_list_app_pages` | List known page names for a multi-page app (HPM, Room Lighting, etc.). Returns curated directory + live primary page. Use before `hub_get_app_config` on multi-page apps to avoid guessing page names. Read master. |
| `hub_list_hpm_packages` | List all packages tracked by HPM — name, version, beta flag, author, and full component inventory (apps, drivers, files with heIDs). Top-level `count` and echoed `hpmAppId`. Auto-discovers HPM's app ID. Pass `includeDrift=true` to also cross-reference HPM-tracked packages against installed apps and drivers (surfacing missing-required components, orphan apps, and orphan drivers under a `drift` key; optional `packageFilter` substring; surfaces `orphanDetection` / `orphanDriverDetection` when registry fetches fail; data-quality warning types: `heid-whitespace-normalized`, `heid-non-scalar-dropped`, `empty-heid`, `skipped-malformed-component` — see `hub_get_tool_guide` for full details). Read master. |

`hub_list_device_dependents`, `hub_get_app_config`, `hub_list_app_pages`, and `hub_list_hpm_packages` are gated by the Read master (ON by default). HPM itself must be installed on the hub.

</details>

<details>
<summary><b>hub_read_devices</b> (4) — Query devices (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_devices` | List accessible devices (pagination, server-side labelFilter/capabilityFilter, format=ids, field projection; `filter='virtual'` lists only MCP-managed virtual devices) |
| `hub_get_device` | Full device details: attributes, commands, capabilities |
| `hub_get_device_attribute` | Get a specific attribute value. Pass `expectedValue` (or `expectedValues`) to block-poll the attribute until it matches or times out — `timeoutMs` in MILLISECONDS (default 5000ms = 5 seconds, max 60000ms). Polling BLOCKS the MCP request; use sparingly and prefer event-driven flows when available. |
| `hub_list_device_events` | Recent events for a device. Add `hoursBack` for a time window (up to 7 days of device or location event history); omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events. |

</details>

<details>
<summary><b>hub_read_diagnostics</b> (9) — Diagnostics, metrics, memory, radio details (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_get_logs` | Hub log entries (most recent first) with level/source/regex filters, multi-pattern AND/OR, time-window (since/until, max 30d relative -- throws if exceeded; use ISO-8601 for longer ranges), and server-side deviceId/appId scoping. `pattern` matches the message field only; pathological regex like `(.*)*` may hang the matcher. (Device/location event *history* is in `hub_list_device_events` via `hoursBack`.) |
| `hub_get_performance_stats` | Device/app performance stats (count, % busy, total ms, state size, events, large-state flag) |
| `hub_get_jobs` | Scheduled jobs, running jobs, and hub actions |
| `hub_get_debug_logs` | Retrieve MCP debug log entries. Pass `mode='status'` to get logging system status and capacity instead. |
| `hub_get_metrics` | Retrieve hub metrics with CSV trend history (read-only by default; does not record a new snapshot) |
| `hub_get_memory_history` | Free OS memory and CPU load history with summary stats (Read master) |
| `hub_get_device_health` | Find stale/offline devices |
| `hub_get_radio_details` | Radio info — Z-Wave (firmware, devices) or Zigbee (channel, PAN ID, devices). `radio`: "zwave" or "zigbee"; omit for both. |
| `hub_list_captured_states` | List saved device state snapshots |

Monitoring tools are gated by the Read master (ON by default).

</details>

<details>
<summary><b>hub_read_files</b> (2) — Hub File Manager (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_files` | List all files in File Manager |
| `hub_read_file` | Read a file's contents |

</details>

<details>
<summary><b>hub_read_rooms</b> (2) — Room listing (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_rooms` | List all rooms with IDs, names, and device counts |
| `hub_get_room` | Get room details with assigned devices |

</details>

<details>
<summary><b>hub_read_rules</b> (5) — Rule introspection (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_get_custom_rule` | Full custom-engine rule details (triggers, conditions, actions). Omit `ruleId` to list all custom-engine rules with status; pass `detailed=true` for comprehensive diagnostics on a specific rule. |
| `hub_test_custom_rule` | Dry-run a custom-engine rule without executing actions |
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x) via official `hubitat.helper.RMUtils` API |
| `hub_get_rule_health` | Read-only health check on any installed app — surfaces broken markers, multiple-flag poison, configPage errors. |
| `hub_get_visual_rule` | List Visual Rules Builder rules (omit `appId`) or read one rule's full JSON definition + format (`classic` whenNodes/thenNodes/elseNodes or `graph` nodes/edges). |

</details>

<details>
<summary><b>hub_read_variables</b> (3) — Hub variable reads (read-only)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_variables` | List all hub connector and rule engine variables |
| `hub_get_variable` | Get a variable value and metadata |
| `hub_list_variable_changes` | Recent hub-variable changes since the MCP app last started |

</details>

##### Write-bearing gateways (12)

<details>
<summary><b>hub_manage_custom_rules</b> (8) — Custom rule administration</summary>

| Tool | Description |
|------|-------------|
| `hub_get_custom_rule` | Full custom-engine rule details (triggers, conditions, actions). Omit `ruleId` to list all custom-engine rules with status; pass `detailed=true` for comprehensive diagnostics on a specific rule. (Also in `hub_read_rules`.) |
| `hub_create_custom_rule` | Create a new custom-engine automation rule (separate from native Rule Machine) |
| `hub_update_custom_rule` | Update custom-engine rule triggers, conditions, actions, or enabled state (`enabled=true/false`) |
| `hub_delete_custom_rule` | Permanently delete a custom-engine rule (auto-backs up first) |
| `hub_test_custom_rule` | Dry-run a custom-engine rule without executing actions (also in `hub_read_rules`) |
| `hub_export_custom_rule` | Export custom-engine rule to JSON and persist it to File Manager via saveAs (write) |
| `hub_import_custom_rule` | Import custom-engine rule from exported JSON |
| `hub_clone_custom_rule` | Clone an existing custom-engine rule (starts disabled) |

</details>

<details>
<summary><b>hub_manage_devices</b> (7) — Control and query devices</summary>

| Tool | Description |
|------|-------------|
| `hub_call_device_command` | Send a command (on, off, setLevel, etc.) |
| `hub_call_device_swap` | Replace a device across ALL apps/rules that reference it (built-in Swap Device tool; compatible replacements only) |
| `hub_update_device` | Update device properties (label, room, preferences, etc.) |
| `hub_list_devices` | List accessible devices (pagination, server-side labelFilter/capabilityFilter, format=ids, field projection; `filter='virtual'` lists only MCP-managed virtual devices) (also in `hub_read_devices`) |
| `hub_get_device` | Full device details: attributes, commands, capabilities (also in `hub_read_devices`) |
| `hub_get_device_attribute` | Get a specific attribute value. Pass `expectedValue` (or `expectedValues`) to block-poll the attribute until it matches or times out — `timeoutMs` in MILLISECONDS (default 5000ms = 5 seconds, max 60000ms). Polling BLOCKS the MCP request; use sparingly and prefer event-driven flows when available. (also in `hub_read_devices`) |
| `hub_list_device_events` | Recent events for a device. Add `hoursBack` for a time window (up to 7 days of device or location event history); omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events. (also in `hub_read_devices`) |

</details>

<details>
<summary><b>hub_manage_variables</b> (8) — Hub variables</summary>

| Tool | Description |
|------|-------------|
| `hub_list_variables` | List all hub connector and rule engine variables (also in `hub_read_variables`) |
| `hub_get_variable` | Get a variable value and metadata (also in `hub_read_variables`) |
| `hub_set_variable` | Set a variable value |
| `hub_create_variable` | Create a new hub variable |
| `hub_delete_variable` | Permanently delete a hub variable (DESTRUCTIVE) |
| `hub_create_connector` | Create a virtual-device connector for a hub variable |
| `hub_delete_connector` | Remove the connector device for a hub variable |
| `hub_list_variable_changes` | Recent hub-variable changes since the MCP app last started (also in `hub_read_variables`) |

</details>

<details>
<summary><b>hub_manage_rooms</b> (5) — Room management</summary>

| Tool | Description |
|------|-------------|
| `hub_list_rooms` | List all rooms with IDs, names, and device counts (also in `hub_read_rooms`) |
| `hub_get_room` | Get room details with assigned devices (also in `hub_read_rooms`) |
| `hub_create_room` | Create a new room (Write master + confirm) |
| `hub_delete_room` | Permanently delete a room (Write master + confirm) |
| `hub_update_room` | Rename a room (Write master + confirm) |

</details>

<details>
<summary><b>hub_manage_destructive_ops</b> (3) — Destructive hub operations</summary>

| Tool | Description |
|------|-------------|
| `hub_reboot` | Reboot the hub (1-3 min downtime) |
| `hub_shutdown` | Power OFF the hub (requires physical restart) |
| `hub_delete_device` | Permanently delete any device (**no undo**) |

All operations are disruptive. These tools are gated by the Write master (ON by default) and enforce a **three-layer safety gate**: Write master enabled + hub backup within 24 hours + explicit `confirm=true`.

</details>

<details>
<summary><b>hub_manage_code</b> (11) — Install, update, delete apps/drivers/libraries/bundles and restore backups</summary>

| Tool | Description |
|------|-------------|
| `hub_create_app` | Install new app from Groovy source or File Manager file (`source` or `sourceFile`). Verifies install succeeded. |
| `hub_create_driver` | Install new driver from Groovy source or File Manager file (`source` or `sourceFile`). Bulk mode: `installs=[{sourceFile},...]`. Verifies each install succeeded. |
| `hub_update_app` | Modify existing app code (source, sourceFile, or resave) |
| `hub_update_driver` | Modify existing driver code (single-driver or bulk `updates` array) |
| `hub_delete_item` | Permanently delete an app, driver, or library (`type`: "app", "driver", "library"; auto-backs up first) |
| `hub_restore_backup` | Restore app/driver to backed-up version (libraries: see `hub_update_library`). Rule snapshots (incl. Visual Rules) recreate a deleted rule. |
| `hub_create_library` | Install new Groovy library (#include namespace.Name) |
| `hub_update_library` | Modify existing library code |
| `hub_install_bundle` | Install a code bundle (.zip) from a URL the HPM way — unpacks into Libraries/Apps/Drivers Code |
| `hub_delete_bundle` | Delete an installed code bundle by id (the container; delivered code may remain in Code) |
| `hub_export_bundle` | Export an installed bundle's .zip to the hub File Manager (downloadable at `/local/<fileName>`) |

Source code is automatically backed up before any modify/delete operation.

</details>

<details>
<summary><b>hub_manage_logs</b> (6) — Logs, performance stats, and log configuration</summary>

| Tool | Description |
|------|-------------|
| `hub_get_logs` | Hub log entries (most recent first) with level/source/regex filters, multi-pattern AND/OR, time-window (since/until, max 30d relative -- throws if exceeded; use ISO-8601 for longer ranges), and server-side deviceId/appId scoping. `pattern` matches the message field only; pathological regex like `(.*)*` may hang the matcher. (Device/location event *history* is in `hub_list_device_events` via `hoursBack`.) (also in `hub_read_diagnostics`) |
| `hub_get_performance_stats` | Device/app performance stats (count, % busy, total ms, state size, events, large-state flag) (also in `hub_read_diagnostics`) |
| `hub_get_jobs` | Scheduled jobs, running jobs, and hub actions (also in `hub_read_diagnostics`) |
| `hub_get_debug_logs` | Retrieve MCP debug log entries. Pass `mode='status'` to get logging system status and capacity instead. (also in `hub_read_diagnostics`) |
| `hub_delete_debug_logs` | Clear all MCP debug logs |
| `hub_set_log_level` | Set MCP log level (debug/info/warn/error) |

Read tools are gated by the Read master; clear/set-level writes by the Write master (both ON by default).

</details>

<details>
<summary><b>hub_manage_diagnostics</b> (8) — Diagnostics, memory, radio details, and state capture</summary>

| Tool | Description |
|------|-------------|
| `hub_get_metrics` | Retrieve hub metrics with CSV trend history (read-only by default; pass `recordSnapshot=true` to also record a new snapshot). (also in `hub_read_diagnostics`) |
| `hub_get_memory_history` | Free OS memory and CPU load history with summary stats (Read master) (also in `hub_read_diagnostics`) |
| `hub_call_gc` | Force JVM garbage collection; returns before/after free memory (Write master) |
| `hub_get_device_health` | Find stale/offline devices (also in `hub_read_diagnostics`) |
| `hub_get_radio_details` | Radio info — Z-Wave (firmware, devices) or Zigbee (channel, PAN ID, devices). `radio`: "zwave" or "zigbee"; omit for both. (also in `hub_read_diagnostics`) |
| `hub_call_zwave_repair` | Z-Wave network repair (5-30 min) |
| `hub_list_captured_states` | List saved device state snapshots (also in `hub_read_diagnostics`) |
| `hub_delete_captured_state` | Delete a captured device state snapshot. Omit `stateId` to delete all snapshots. |

</details>

<details>
<summary><b>hub_manage_files</b> (4) — Hub File Manager</summary>

| Tool | Description |
|------|-------------|
| `hub_list_files` | List all files in File Manager (also in `hub_read_files`) |
| `hub_read_file` | Read a file's contents (also in `hub_read_files`) |
| `hub_write_file` | Create or update a file (auto-backs up existing) |
| `hub_delete_file` | Delete a file (auto-backs up first) |

Write/delete require the Write master + confirm + a recent backup.

</details>

<details>
<summary><b>hub_manage_rule_machine</b> (10) — Rule authoring (Rule Machine + Visual Rules Builder) and RM runtime control</summary>

| Tool | Description |
|------|-------------|
| `hub_set_rule` | Create or edit a Rule Machine rule (RM 5.1) — the full authoring surface (omit `appId` to create). Structured shortcuts: addTrigger / addAction / addRequiredExpression / walkStep / patches and more. Auto-snapshots before every write. |
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x) via official `hubitat.helper.RMUtils` API (also in `hub_read_rules`) |
| `hub_call_rule` | Trigger an RM rule (`action`: "rule"/"actions"/"stop") |
| `hub_set_rule_paused` | Pause or resume an RM rule (`value=true` pauses, `value=false` resumes; reversible) |
| `hub_set_rule_private_boolean` | Set an RM rule's private boolean variable |
| `hub_get_rule_health` | Read-only health check on any installed app — surfaces broken markers, multiple-flag poison, configPage errors. (also in `hub_read_rules`) |
| `hub_delete_native_app` | Delete any classic native app incl. RM rules (auto-snapshot first; `force=true` for hard delete; also in `hub_manage_native_rules_and_apps`) |
| `hub_get_visual_rule` | List Visual Rules Builder rules (omit `appId`) or read one rule's full JSON definition + format (also in `hub_read_rules`) |
| `hub_set_visual_rule` | Create or update a Visual Rules Builder rule — VRB is the primary rule engine; one JSON write with if/then/else gating. Use `hub_set_rule` only for complex automations (nested logic/loops/variables). |
| `hub_delete_visual_rule` | Delete a Visual Rules Builder rule (type-gated; returns the pre-delete definition for recovery) |

Reads (`hub_list_rules`, `hub_get_rule_health`, `hub_get_visual_rule`) are gated by the Read master; the writes by the Write master (both ON by default). `hub_set_rule`, `hub_delete_native_app`, `hub_set_visual_rule`, and `hub_delete_visual_rule` additionally require `confirm=true` + a backup within 24h.

</details>

<details>
<summary><b>hub_manage_native_rules_and_apps</b> (11) — Rule Machine interop (RMUtils) + native CRUD on any classic SmartApp (RM, Room Lighting, Button Controllers, Basic Rules, Notifier, etc.)</summary>

| Tool | Description |
|------|-------------|
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x) via official `hubitat.helper.RMUtils` API (also in `hub_read_rules`) |
| `hub_call_rule` | Trigger an RM rule (`action`: "rule"/"actions"/"stop") |
| `hub_set_rule_paused` | Pause or resume an RM rule (`value=true` pauses, `value=false` resumes; reversible) |
| `hub_set_rule_private_boolean` | Set an RM rule's private boolean variable |
| `hub_set_native_app` | Create or edit any classic native app (omit `appId` to create; `appType` enum covers Button Controllers / Notifier / Groups+Scenes / Visual Rule / Basic Rules; default `rule_machine`). Create a Button Rule under its controller via `buttonRule`. Returns `appId`. Generic upsert; `walkStep` (generic classic-page walker) also works here. |
| `hub_set_rule` | Author a Rule Machine rule by appId (omit `appId` to create) — triggers, actions, required expressions, settings, structured shortcuts. Auto-snapshots before every write. `clearActions` / `replaceActions` commit the delete synchronously via a full selectActions page-form submit (runs RM's trashActs handler in-band), so the actions are gone when the call returns. A thin defensive verify-retry remains: on the rare residual it returns `partial:true, asyncCommitLikely:true` with `stage` + `safeRecovery` -- verify via `hub_get_app_config` rather than rolling back. |
| `hub_delete_native_app` | Delete a classic native app (auto-snapshot to File Manager before deleting). |
| `hub_clone_native_app` | Clone an existing classic SmartApp via Hubitat's `appCloner` endpoint. Returns the new `appId`. |
| `hub_export_native_app` | Export a classic SmartApp to JSON and persist it to File Manager (round-trippable with `hub_import_native_app`). |
| `hub_import_native_app` | Import previously-exported app JSON into a new instance. Returns the new `appId`. |
| `hub_get_rule_health` | Read-only health check on any installed app — surfaces broken markers, multiple-flag poison, configPage errors. (also in `hub_read_rules`) |

Reads are gated by the Read master; create/update/delete by the Write master (with `confirm=true` + a recent backup). Both masters are ON by default.

</details>

<details>
<summary><b>hub_manage_mcp</b> (1) — Developer Mode self-administration</summary>

| Tool | Description |
|------|-------------|
| `hub_update_mcp_settings` | Update one or more of the MCP rule app's own settings (toggles, log level, tuning params). Allowlist-gated. |

First gateway under the **Developer Mode** pattern — for LLM-agent and CI/CD pipelines that need to manage the MCP rule app's own configuration without manual UI intervention. Additional self-admin tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same toggle. Requires opt-in **Enable Developer Mode Tools** setting (default OFF). Each successful write is logged at WARN level for audit.

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

## Permission Model

Every MCP tool is gated by two universal masters — **Read** and **Write** — both **ON by default**. Turn one OFF to remove that entire class of tools from the MCP client and reject any cached call.

<details>
<summary><b>The Read / Write masters</b></summary>

1. Open **Apps** > **MCP Rule Server** in the Hubitat web UI
2. Under **Tool Access (Read / Write masters)**, toggle:
   - **Enable Read Tools** — exposes every read-only / non-destructive tool (list/get/search/diagnostics, hub info, app/driver/source reads). With it OFF, those tools vanish from the client and a cached call is rejected with "Read tools are disabled…".
   - **Enable Write Tools** — exposes every state-changing tool (device control, modes, variables, rooms, files, native rules, hub admin). With it OFF, those tools vanish and a cached call is rejected with "Write tools are disabled…".
3. If your hub has **Hub Security** enabled, also configure:
   - **Hub Security Username** and **Password** under the Hub Security section

Both masters default ON — only an explicit OFF hides or blocks a class. (This replaces the former separate "Enable Hub Admin Read/Write Tools" and "Enable Built-in App Tools" toggles.)

</details>

<details>
<summary><b>Destructive write safety gate</b></summary>

Beyond the Write master, the destructive/sensitive write tools (backup, reboot, shutdown, Z-Wave repair, delete-device, file write/delete, app/driver/native-rule CRUD, etc.) enforce a **three-layer safety gate**:
1. The **Write master** must be enabled
2. The AI must pass `confirm=true` explicitly
3. A full hub **backup must exist within the last 24 hours** (enforced automatically)

Additionally, tools that modify or delete existing apps/drivers automatically back up the item's source code before making changes.

</details>

<details>
<summary><b>Advanced: Per-tool Overrides (deny-only)</b></summary>

Under **Settings > Advanced: Per-tool Overrides**, you can disable individual tools or whole gateways **below** the masters — these only turn things OFF, never re-enable. A disabled tool (or every tool inside a disabled gateway, including tools shared across gateways) drops from `tools/list` and `hub_search_tools`, and a cached call returns a distinct error: "…is disabled in Advanced settings (Per-tool Overrides)…". A disabled tool stays documented in `hub_get_tool_guide`. Use **Reset all overrides** to clear them.

Each picker entry shows the bare tool name, its friendly name, a `[read]`/`[write]` marker, and a one-sentence description — so you can tell what you're disabling without decoding the bare names. The same friendly names are published on `tools/list` as MCP `annotations.title`, so clients that honor that field — claude.ai among them — display them in their tool lists too.

</details>

<details>
<summary><b>Item Backup & Restore</b></summary>

When you use `hub_update_app`, `hub_update_driver`, or `hub_delete_item` (type: app|driver), the server automatically saves the **original source code** before making changes.

- Backups stored as `.groovy` files in the hub's local **File Manager**
- Named `mcp-backup-app-<id>.groovy` or `mcp-backup-driver-<id>.groovy`
- Persist even if the MCP app is uninstalled
- Downloadable at `http://<your-hub-ip>/local/<filename>`
- Max 20 kept; oldest pruned automatically
- 1-hour protection window: multiple edits preserve the pre-edit original

**Restore via MCP:**
1. `hub_list_backups` to see available backups
2. `hub_restore_backup` with the backup key and `confirm=true`

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

- **`hub_list_devices` with `detailed=true`** — Can be slow on 50+ devices. Use pagination: `hub_list_devices(detailed=true, limit=25, offset=0)`. Use `labelFilter` or `capabilityFilter` to narrow server-side before pagination. Use `fields=[...]` to skip expensive hub reads -- `currentStates` and `attributes` trigger per-device hub reads and are the ones worth projecting out; `capabilities` and `commands` are in-memory and cheap.
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
<summary><b>hub_list_devices(detailed=true) fails over Hubitat Cloud</b></summary>

Hubitat Cloud has a **128KB response size limit** (AWS MQTT limitation). Use pagination and server-side filtering to stay under the limit:

```
hub_list_devices(detailed=true, limit=25, offset=0)               // First 25 devices
hub_list_devices(detailed=true, limit=25, offset=25)              // Next 25 devices
hub_list_devices(capabilityFilter='Switch', limit=25, offset=0)   // Only Switch devices
hub_list_devices(fields=['id','label','currentStates'], limit=50) // Slim payload
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
1. Set debug log level: Settings > MCP Debug Log Level > "Debug", or ask your AI to `hub_set_log_level` to "debug"
2. Reproduce the issue
3. Ask your AI to use the `hub_report_issue` tool — it will gather diagnostics and format a ready-to-submit report
4. Submit at [GitHub Issues](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues)

</details>

---

<!-- FUTURE_PLANS_START -->
<details>
<summary><h2>Future Plans</h2></summary>


> **Blue-sky ideas** — everything below is speculative and needs further research to determine feasibility. None of these features are guaranteed or committed to.
>
> **Status key:** `[ ]` = not started | `[~]` = in progress / partially done | `[x]` = completed | `[?]` = needs research / feasibility unknown
>
> **Feasibility research conducted February 2025.** Each item now includes a difficulty rating (1–5), effort estimate, and implementation notes based on a thorough codebase analysis.
>
> **Difficulty key:** `1` = trivial | `2` = straightforward | `3` = moderate | `4` = complex | `5` = extremely complex
>
> **Effort key:** `S` = small (hours) | `M` = medium (1–3 days) | `L` = large (4+ days)

---

### Rule Engine Enhancements

#### Trigger Enhancements

- [x] **Conditional triggers (evaluate at trigger time)** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The `evaluateTriggerCondition()` method evaluates a per-trigger `condition` field using the full condition system. Every trigger handler already calls this. May benefit from better documentation and MCP tool schema updates to make the feature more discoverable.

- [x] **Cron/periodic triggers** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The `periodic` trigger type internally generates cron expressions via `schedule()`. To expose raw cron support, add a `cron` sub-type that passes user-provided cron expressions directly. Hubitat accepts standard 7-field cron expressions. User-provided strings would need validation.

- [ ] **Endpoint/webhook triggers** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Add a single dispatcher endpoint (e.g., `/mcp/webhook?ruleKey=<key>`) in the parent app's `mappings` block. The parent looks up the child rule by key and calls `executeRule("webhook", webhookEvt)` with the request body/params as event data. Per-rule dynamic paths are not possible since `mappings` are compile-time, but a shared endpoint with a query parameter works. The OAuth access token already protects the path.
  >
  > **Implementation plan:**
  > 1. Add `GET/POST /webhook` mapping to parent app
  > 2. Add `webhook` trigger type to child app's `subscribeToTriggers()`
  > 3. Parent dispatches to matching child rule via key lookup
  > 4. Package request body, headers, and query params into pseudo-event for variable substitution

- [x] **Hub variable change triggers** — *Closed differently than originally planned (issue #92).*
  > Originally scoped as a `variable_change` trigger type for the legacy MCP rule engine. With the legacy engine now frozen and native Rule Machine providing variable triggers natively, the equivalent capability for MCP/AI consumers ships as observation tooling instead: the parent app subscribes to `variable:*` location events on install/update, buffers the last 200 changes in `atomicState.variableHistory`, and exposes them via `hub_list_variable_changes`. The `renameVariable(oldName, newName)` callback keeps the buffer consistent across UI renames. See PR closing #92 / #96.

- [ ] **System start trigger** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Hubitat supports `subscribe(location, "systemStart", handler)`. Add a `system_start` trigger type. After hub reboot, the app restores, `initialize()` → `subscribeToTriggers()` runs, and the systemStart event fires the rule. Minor edge case: the event may fire before all apps finish restoring — needs testing on hardware.
  >
  > **Implementation plan:**
  > 1. Add `system_start` trigger type to child app
  > 2. Subscribe to `location "systemStart"` event in `subscribeToTriggers()`
  > 3. Handler calls `executeRule("system_start")`

- [ ] **Date range triggers** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Better implemented as a condition than a trigger. A `date_range` condition type checks `new Date()` against start/end dates, following the same pattern as `time_range` and `days_of_week`. If implemented as a trigger, use `schedule()` to fire at range start. `java.util.Calendar` is available in the sandbox.
  >
  > **Implementation plan:**
  > 1. Add `date_range` condition type to `evaluateCondition()`
  > 2. Accept `startDate` and `endDate` (ISO format)
  > 3. Optionally add a `date_range` trigger that schedules a one-time event at range start

#### Condition Enhancements

- [ ] **Required Expressions (rule gates) with in-flight action cancellation** — `Difficulty: 4 | Effort: L`
  > *Feasible but complex.* A "gate" is a condition continuously monitored during action execution. If it becomes false, in-flight delayed actions are cancelled. The existing `cancelledDelayIds` mechanism provides the foundation for cancellation. The gate needs its own `subscribe()` calls for relevant devices, with a handler that checks the gate condition and marks all pending delay IDs as cancelled. This is the most architecturally complex enhancement — it requires asynchronous monitoring during delayed action chains and careful state management.
  >
  > **Implementation plan:**
  > 1. Add `requiredExpressions` array to rule structure alongside `conditions`
  > 2. Subscribe to gate-relevant device events separately
  > 3. Gate handler evaluates conditions and cancels all active delays if false
  > 4. Track all active delay IDs per rule in `atomicState.activeDelayIds`
  > 5. Add cleanup logic when rule execution completes normally

- [ ] **Full boolean expression builder (AND/OR/XOR/NOT with nesting)** — `Difficulty: 4 | Effort: L`
  > *Feasible.* Replace the flat conditions array + `conditionLogic` toggle with a recursive tree structure: `{operator: "AND", operands: [...]}`. Rewrite `evaluateConditions()` as a tree walker. NOT wraps a single operand; XOR checks exactly one true. The existing `evaluateCondition()` for leaf nodes is already modular. Main challenge is MCP tool ergonomics and backward compatibility — the flat array format would need migration logic.
  >
  > **Implementation plan:**
  > 1. Define tree data structure with `operator` and `operands` fields
  > 2. Implement recursive `evaluateConditionTree()` method
  > 3. Support both legacy flat format and new tree format (migration path)
  > 4. Update `hub_create_custom_rule`/`hub_update_custom_rule` tool schemas
  > 5. Update `describeCondition()` for recursive formatting

- [ ] **Private Boolean per rule** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Already achievable via `atomicState.localVariables`. Add a dedicated `set_rule_boolean` action type that calls `parent.setRuleBooleanOnChild(targetRuleId, boolName, value)`, which updates the target child's local variables. Add a `rule_boolean` condition type that reads a specific rule's local variables. The parent mediates cross-rule access via `getChildAppById()`.
  >
  > **Implementation plan:**
  > 1. Add `set_rule_boolean` action type to child app
  > 2. Add `rule_boolean` condition type to `evaluateCondition()`
  > 3. Add `setRuleBooleanOnChild()` method to parent app
  > 4. Update MCP tool schemas

#### Action Enhancements

- [ ] **Fade dimmer over time** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Many dimmers natively support `setLevel(level, duration)` which the codebase already uses. For a software fade: calculate step size and interval, then use `runIn()` with incremental steps. Example: 0→100 over 60s = 5% every 3 seconds (20 steps). Limit steps to ~20–30 to avoid overwhelming the hub's scheduler. Share the stepping utility with color temperature fading and ramp actions.
  >
  > **Implementation plan:**
  > 1. Add `fade_dimmer` action type with `startLevel`, `endLevel`, `durationSeconds`
  > 2. Implement `rampValue()` utility for stepped `runIn()` scheduling
  > 3. Use `[overwrite: false]` on `runIn()` for non-conflicting callbacks
  > 4. Fall back to native `setLevel(level, duration)` when device supports it

- [ ] **Change color temperature over time** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Same pattern as fade dimmer. Use the shared `rampValue()` utility with `setColorTemperature()` calls at each step. Some devices support `setColorTemperature(temp, level, duration)` natively. Temperature ranges vary by device (typically 2000K–6500K).
  >
  > **Implementation plan:**
  > 1. Add `fade_color_temperature` action type with `startTemp`, `endTemp`, `durationSeconds`
  > 2. Reuse the `rampValue()` utility from fade dimmer
  > 3. Handle integer rounding for temperature steps

- [ ] **Per-mode actions** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Add a `per_mode` action type containing a map of mode-name to action-list. At execution time, check `location.mode` and execute the matching action list. Structurally similar to `if_then_else` but with multiple branches. The existing `if_then_else` with a `mode` condition already provides this capability less ergonomically.
  >
  > **Implementation plan:**
  > 1. Add `per_mode` action type with `modeActions` map
  > 2. Look up `location.mode` at execution time
  > 3. Execute matching action list using existing `executeAction()` infrastructure

- [ ] **Wait for Event / Wait for Expression** — `Difficulty: 5 | Effort: L`
  > *Partially feasible.* Requires pausing execution mid-stream until an external event occurs. In Hubitat's single-threaded model, there is no blocking wait. Implementation: save current execution state (action index, context) to `atomicState`, subscribe to the target event, return from execution, then resume when the event fires — similar to the `delay` pattern but event-triggered. A mandatory timeout is essential. Race conditions between the wait subscription and other triggers are possible.
  >
  > **Implementation plan:**
  > 1. Add `wait_for_event` action type with target device/attribute and mandatory timeout
  > 2. Save execution state to `atomicState` (same pattern as `delay`)
  > 3. Create temporary subscription for the target event
  > 4. On event fire or timeout, resume execution from saved state
  > 5. Clean up subscription after resume

- [ ] **Repeat While / Repeat Until** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* Extend the existing `repeat` action to evaluate conditions before/after each iteration. Critical safety: hard cap at 100 iterations (matching existing `repeat` cap), mandatory max iteration parameter, and loop guard protection. If the loop body contains delays, each iteration needs the save-state/resume pattern, making implementation extremely complex. Recommend supporting synchronous loops only (no delays in body) initially.
  >
  > **Implementation plan:**
  > 1. Add `repeat_while` and `repeat_until` action types
  > 2. Evaluate condition via `evaluateCondition()` each iteration
  > 3. Enforce mandatory `maxIterations` cap (≤100)
  > 4. Phase 1: synchronous loops only (no delay actions in body)
  > 5. Phase 2 (future): delay-compatible loops with state persistence

- [ ] **Rule-to-rule control** — `Difficulty: 2 | Effort: M`
  > *Feasible.* Add `enable_rule`, `disable_rule`, and `trigger_rule` action types. Child calls parent, which looks up the target child and invokes existing `enableRule()`/`disableRule()`/`executeRule()`. To prevent cross-rule ping-pong loops, pass a "trigger chain depth" counter and refuse execution beyond depth 5.
  >
  > **Implementation plan:**
  > 1. Add `enable_rule`, `disable_rule`, `trigger_rule` action types
  > 2. Implement `triggerChildRule(targetRuleId, depth)` in parent
  > 3. Add depth counter to `executeRule()` to prevent cascading loops
  > 4. Validate target rule exists via `getChildAppById()`

- [ ] **File write/append/delete** — `Difficulty: 2 | Effort: S`
  > *Feasible.* The parent already has `uploadHubFile()`/`downloadHubFile()` wrappers. New action types `file_write`, `file_append`, `file_delete` call parent methods. Append does a read-modify-write cycle (not atomic). Requires the Write master gate. Variable substitution in content enables dynamic log files.
  >
  > **Implementation plan:**
  > 1. Add `file_write`, `file_append`, `file_delete` action types
  > 2. Child calls `parent.writeFileFromRule(fileName, content, mode)`
  > 3. Validate filenames against `[A-Za-z0-9._-]` pattern
  > 4. Apply the Write master gate for safety

- [ ] **Music/siren control** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Convenience wrappers around existing `device_command`. Hubitat has `capability.musicPlayer` (play, pause, stop, setVolume, playTrack) and `capability.alarm` (both, siren, strobe, off). The existing `speak` action demonstrates the pattern for TTS with volume. These would be ergonomic action types with built-in validation for the right capabilities.
  >
  > **Implementation plan:**
  > 1. Add `play_music` action type with device, command, volume, track params
  > 2. Add `activate_siren` action type with device, mode (siren/strobe/both/off)
  > 3. Validate device has required capability before execution

- [x] **Custom Action (any capability + command)** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The existing `device_command` action type accepts any device ID, command, and parameters via dynamic invocation (`device."${command}"(*params)`). This is the "any capability + command" feature.

- [ ] **Disable/Enable a device** — `Difficulty: 1 | Effort: S`
  > *Feasible (partially done).* The `hub_update_device` MCP tool already supports the `enabled` property via the internal `/device/disable` endpoint. A new `set_device_enabled` rule action type wraps the same call. Requires the Write master. Should warn if the target device is used in active rule triggers.
  >
  > **Implementation plan:**
  > 1. Add `set_device_enabled` action type with `deviceId` and `enabled` boolean
  > 2. Call `parent.setDeviceEnabled(deviceId, enabled)`
  > 3. Check if device is used in any rule triggers and warn

- [ ] **Ramp actions (continuous raise/lower)** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Same software-stepping pattern as fade dimmer/color temp. A generic `ramp` action targets any numeric attribute. True continuous raise/lower (like holding a physical dimmer button) uses `startLevelChange(direction)` / `stopLevelChange()` but can't be time-bounded from software. Shares the `rampValue()` utility with fade actions.
  >
  > **Implementation plan:**
  > 1. Add `ramp` action type with device, attribute, start, end, duration, steps
  > 2. Reuse `rampValue()` utility from fade actions
  > 3. For devices with `startLevelChange`/`stopLevelChange`, offer a hardware ramp option

- [x] **Ping IP address (ICMP)** — folded into `hub_get_device_health` (issue #91).
  > Rather than a standalone `ping_host` tool, ICMP ping was integrated into the existing `hub_get_device_health` tool via `pingHosts` (max 5 IPv4) and `pingCount` (1–5) parameters. Each host is pinged through `hubitat.helper.NetworkUtils.ping()` and reported under `pingResults` with `reachable`, `rttAvg`, `rttMin`, `rttMax`, `packetsTransmitted`, `packetsReceived`, `packetLoss`. The custom MCP rule engine is legacy-only, so no rule-action half was added.

- [ ] **HTTP reachability check** — `Difficulty: 3 | Effort: M`
  > *Feasible — complementary to ICMP ping above.* An HTTP GET against a target URL still has value for hosts that don't respond to ICMP or when you need to verify HTTP-layer health, not just network reachability. Keep as a secondary action type alongside the native ping.
  >
  > **Ordering caveat.** `asynchttpGet()` is non-blocking: any actions after `http_check` in a rule's action list would otherwise run before the response arrived and before the result variables (`reachable`, `statusCode`, `responseTimeMs`) were populated. A correct implementation must save rule execution state on the dispatch and resume from the async callback — the same save-state/resume pattern used by the existing `delay` action. Mandatory timeout required so a stalled request doesn't leave the rule suspended.
  >
  > **Implementation plan:**
  > 1. Add `http_check` action type with target URL, optional expected status code, and mandatory timeout
  > 2. Dispatch via `asynchttpGet()` (non-blocking, does not tie up the hub executor)
  > 3. Save action-index + context to `atomicState` before dispatch, mirroring the `delay` action's resume pattern
  > 4. In the async callback, populate result fields (`reachable`, `statusCode`, `responseTimeMs`) into rule/local variables and resume action execution from the saved index
  > 5. Enforce a hard timeout that resumes with `reachable=false` if no response arrives in time
  > 6. Synchronous `httpGet()` is not an acceptable shortcut — it blocks the hub event executor and can stall other apps

#### Variable System

- [ ] **Hub Variable Connectors (expose as device attributes)** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* Hubitat's built-in Variable Connector feature (firmware ≥ 2.3.4) already handles hub variables. For MCP rule engine variables, exposing them as device attributes requires: (1) a custom virtual device driver, (2) the parent creating an instance via `addChildDevice()`, (3) the parent updating attributes via `sendEvent()` on variable writes. The custom driver adds a third file to the project and install complexity.
  >
  > **Implementation plan:**
  > 1. Create `MCP Variable Connector` driver (new Groovy file)
  > 2. Parent auto-creates a child device on first variable write (or on demand)
  > 3. Extend `setRuleVariable()` to also `sendEvent()` on the connector device
  > 4. Add driver to HPM package manifest
  > 5. Document that hub variables should use Hubitat's built-in connectors instead

- [~] **Variable change events** — *Hub-variable half closed under issue #92; MCP-rule-engine half deferred (legacy engine frozen).*
  > Hub-variable change observation now ships via `hub_list_variable_changes` (see "Hub variable change triggers" above). The MCP-rule-engine half — sending a `ruleVariableChanged` location event when `setRuleVariable()` writes — would require new code in the legacy child app, which is no longer being extended. New rule-variable consumers should use native Rule Machine, which has variable triggers built in.

- [ ] **Local variable triggers** — `Difficulty: 2 | Effort: S`
  > *Feasible.* After `set_local_variable` or `variable_math` modifies a local variable, check for matching `local_variable_change` triggers and re-trigger asynchronously via `runIn(0, handler)`. High risk of infinite loops if a rule triggers itself — recommend only firing from external changes (another rule setting this rule's local variable via rule-to-rule control). The loop guard provides a safety net.
  >
  > **Implementation plan:**
  > 1. Add `local_variable_change` trigger type
  > 2. After local variable modification, schedule async re-evaluation
  > 3. Add `triggerSource` tracking to prevent self-triggering loops
  > 4. Rely on loop guard as safety net

---

### Built-in Automation Equivalents

> **Philosophy: prefer native Hubitat apps.** The MCP server was built to complement Hubitat, not replace it. These native apps (Room Lighting, Mode Manager, Button Controller, etc.) are well-maintained, have proper UIs, and are battle-tested. The MCP can already interact with the *effects* of these apps — it can read/set modes, control devices, trigger on device events, and see virtual devices they create.
>
> **The AI assistant is the wizard.** Rather than building dedicated wizard tools that generate MCP rules to replicate what native apps already do, the AI can compose rules on the fly using existing `hub_create_custom_rule` and the full rule engine. Dedicated MCP tooling for these patterns is **low priority** and would only be implemented if the MCP genuinely cannot interact with the native app's functionality in some way. Each item will be reviewed on a case-by-case basis.

- [ ] **Room Lighting (room-centric lighting with vacancy mode)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Room Lighting app handles this well. The MCP can already control all the same devices, trigger on motion events, and use `if_then_else` / `delay` / `cancel_delayed` to build equivalent logic via `hub_create_custom_rule` if needed. No dedicated MCP tool required unless a gap is identified where MCP cannot interact with Room Lighting's behavior.

- [ ] **Zone Motion Controller (multi-sensor zones)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Zone Motion Controller creates a virtual motion device that aggregates multiple sensors. If the user adds this virtual device to MCP's selected devices, MCP can already see and trigger on it. The AI can also replicate the logic using `create_virtual_device` + `hub_create_custom_rule` with multi-device triggers if needed. Only implement if MCP cannot adequately interact with the native app's output device.

- [ ] **Mode Manager (automated mode changes)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Mode Manager handles time-based and presence-based mode changes. The MCP can already read/set modes via `hub_list_modes`/`hub_set_mode`, trigger on `mode_change`, and build time/presence-triggered rules that call `set_mode`. No dedicated tool needed unless a specific interaction gap is found.

- [ ] **Button Controller (streamlined button-to-action mapping)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Button Controller handles this natively. The MCP rule engine already has `button_event` triggers with full support for button numbers (1–20) and action types (pushed/held/doubleTapped/released). The AI can create these rules directly via `hub_create_custom_rule`. No dedicated tool needed.

- [ ] **Thermostat Scheduler (schedule-based setpoints)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Thermostat Scheduler handles schedule-based setpoints. The MCP rule engine already has `time` triggers, `set_thermostat` actions, `mode` and `days_of_week` conditions — the AI can compose schedule rules directly. No dedicated tool needed unless MCP cannot interact with the native scheduler's effects.

- [ ] **Lock Code Manager** — `Low priority — review needed`
  > *May warrant a dedicated tool.* Hubitat's built-in Lock Code Manager handles code management via a UI. The MCP can already send lock code commands via `hub_call_device_command` (`setCode`, `deleteCode`) and read `lockCodes`/`lastCodeName` attributes, so basic interaction is possible today. However, the native app's internal code inventory and temporary code scheduling are not directly accessible. A dedicated tool may add value for programmatic code management if the native app's outputs prove insufficient. **Needs case-by-case review.**

- ~~[ ] **Groups and Scenes (Zigbee group messaging)**~~ — `Not feasible`
  > *Not feasible.* The `zigbee` object and `sendHubCommand()` with `Protocol.ZIGBEE` are only available in drivers, not apps. The MCP server is an app and cannot send raw Zigbee commands or manage Zigbee group IDs. Zigbee group management is handled by closed-source platform internals with no documented HTTP API endpoints.
  >
  > **Alternatives already available:**
  > - **Leverage built-in Groups and Scenes app**: Guide users to create Zigbee groups via the built-in app, then control the resulting group activator device through MCP's `hub_call_device_command` (group activator devices are regular switch/dimmer devices that MCP can already control)
  > - **Software-level group control**: Create rules that send commands to multiple devices sequentially — already possible via multi-device rules or `device_command` actions
  > - **Scene capture/restore**: The existing `capture_state`/`restore_state` actions provide scene-like functionality across multiple devices

---

### HPM & App/Integration Management

- [ ] **Search HPM repositories by keyword** — `Difficulty: 2 | Effort: S`
  > *Feasible.* HPM uses a public GraphQL API at `https://hubitatpackagemanager.azurewebsites.net/graphql`. A `search_hpm_packages` tool sends a GraphQL query via `httpPost()` and returns matching packages with name, description, author, and manifest URL. The master repository list at `https://raw.githubusercontent.com/HubitatCommunity/hubitat-packagerepositories/master/repositories.json` provides offline browsing.
  >
  > **Implementation plan:**
  > 1. Create `search_hpm_packages` MCP tool
  > 2. Send GraphQL `Search` query to the Azure endpoint
  > 3. Parse and return results with pagination for large result sets
  > 4. Cache results in `state` to reduce API calls

- [ ] **Install/uninstall packages via HPM** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* HPM has no programmatic API — it's purely UI-driven. **Bypass approach**: fetch the package manifest JSON, download each app/driver source, and install via existing `hub_create_app`/`hub_create_driver` tools. However, packages installed this way won't appear in HPM's "Installed" list, creating a fragmented experience. Uninstall requires removing running app instances (not just code) via poorly documented `/installedapp/` endpoints.
  >
  > **Implementation plan:**
  > 1. Create `install_package` MCP tool using the bypass approach
  > 2. Fetch manifest → download sources → install via existing tools
  > 3. Track installed packages in File Manager for update checking
  > 4. Document the limitation: HPM won't know about these installations
  > 5. For uninstall: `hub_delete_item` (type: app|driver) for code, investigate `/installedapp/disable` for instances

- [ ] **Check for updates across installed packages** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* For MCP-tracked packages (from the install tool above): fetch each manifest URL and compare versions -- same pattern as the existing `checkForUpdate()` for the MCP server itself. For HPM-managed packages: HPM's installed-package state IS readable via hub-internal endpoints (`/installedapp/statusJson/` + `/hub2/appsList`), as demonstrated by `hub_list_hpm_packages` (includeDrift=true; drift nests under a `drift` key). A `check_package_updates` tool could cross-reference HPM's recorded manifest URLs and versions against live manifest files to detect available updates.
  >
  > **Implementation plan:**
  > 1. Create `check_package_updates` MCP tool
  > 2. Read MCP-tracked package list from File Manager (for MCP-installed packages)
  > 3. For HPM-managed packages: use `_hpmFetchManifests` to get recorded manifest URLs and versions; fetch each live URL and compare version fields
  > 4. Return list of packages with available updates
  > 5. Handle fetch failures gracefully (GitHub rate limiting, network issues)

- [ ] **Search for official integrations not yet enabled** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* No documented endpoint for enumerating available built-in apps. The `hub_list_apps` tool returns user-installed app types, not built-in ones. **Practical approach**: maintain a hardcoded catalog of known official integrations (Hue Bridge, Sonos, Alexa, Google Home, HomeKit, etc.) and check which ones have running instances. The list only changes with firmware updates.
  >
  > **Implementation plan:**
  > 1. Create `list_available_integrations` MCP tool
  > 2. Maintain hardcoded catalog of official integrations with descriptions
  > 3. Check installed app instances to identify which are already enabled
  > 4. Return available-but-not-enabled integrations with setup guidance
  > 5. Update catalog with each MCP server release

- [ ] **Discover community apps/drivers from GitHub, forums, etc.** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Primary mechanism: HPM repository search (above). GitHub API search (`https://api.github.com/search/repositories?q=hubitat+driver`) works but is rate-limited to 10 req/min unauthenticated. The Hubitat community forum has no search API. A curated list in File Manager is a practical fallback.
  >
  > **Implementation plan:**
  > 1. Create `search_community_packages` MCP tool
  > 2. Primary: search HPM repositories via GraphQL
  > 3. Secondary: optional GitHub API search with rate-limit handling
  > 4. Return combined results with source attribution
  > 5. Optionally maintain a curated popular-packages list in File Manager

---

### Dashboard Management

- [ ] **Create, modify, delete dashboards programmatically** — `Difficulty: 4 | Effort: L`
  > *Feasible via internal HTTP endpoints (needs empirical testing).* Hubitat's dashboard system uses a parent-child app pattern: "Hubitat Dashboard" is the parent, each individual dashboard is a child app. Deep research uncovered the `/installedapp/createchild/` internal endpoint that the web UI uses.
  >
  > **Key discoveries:**
  > - **Dashboard listing**: `GET /dashboard/all?pinToken=<token>` or via `GET /hub2/appsList` filtering for dashboard children
  > - **Dashboard creation**: `GET /installedapp/createchild/hubitat/Dashboard/parent/{dashboardParentAppId}` — creates a new child dashboard under the parent app. Returns a redirect to `/installedapp/configure/{newChildId}`
  > - **Dashboard layout read**: `GET /apps/api/<parentAppId>/dashboard/<childAppId>/layout?access_token=<token>`
  > - **Dashboard layout write**: `POST /apps/api/<parentAppId>/dashboard/<childAppId>/layout` with Bearer token auth
  > - **Dashboard deletion**: Likely `POST /installedapp/configure/{childId}` with remove action, or `GET /installedapp/remove/{childId}` (exact endpoint needs testing)
  > - **Child app type**: namespace `hubitat`, name `Dashboard` (confirmed from error messages in community forums)
  >
  > **Important caveats:**
  > - `addChildApp()` in Groovy **cannot** create dashboard children from the MCP app (parent mismatch), so the HTTP endpoint approach is required
  > - The `createchild` endpoint returns an HTTP redirect (302), not JSON — need to extract new child ID from the Location header
  > - Post-creation configuration (dashboard name, authorized devices) requires a separate POST to `/installedapp/configure/{id}` with form-encoded data
  > - The Dashboard parent app must already be installed on the hub
  > - The `pinToken` for `/dashboard/all` needs to be obtained from the Dashboard parent app or may not be required for local API calls
  > - Firmware ≥ 2.3.9 introduced "Easy Dashboard" as an alternative — its internal structure may differ
  > - All endpoints are undocumented and may change across firmware versions
  >
  > **Implementation plan:**
  > 1. Discover Dashboard parent app ID via `GET /hub2/appsList` (filter by app name)
  > 2. Create `create_dashboard` tool: call `GET /installedapp/createchild/hubitat/Dashboard/parent/{parentId}`, extract new child ID from redirect
  > 3. Configure new dashboard: `POST /installedapp/configure/{newId}` with name and authorized devices
  > 4. Create `list_dashboards` tool via `/dashboard/all` or `/hub2/appsList`
  > 5. Create `get_dashboard_layout` / `update_dashboard_layout` tools for layout JSON read/write
  > 6. Create `delete_dashboard` tool: test `/installedapp/remove/{childId}` endpoint
  > 7. Add Dashboard parent app ID and access token to MCP app preferences
  > 8. Requires the Write master gate for safety
  > 9. **Phase 1**: Implement list + read/modify layout (known-working endpoints)
  > 10. **Phase 2**: Implement create/delete (requires empirical testing on hub hardware)

---

### Rule Machine Interoperability

> **Feasibility confirmed** — creating/modifying RM rules is not possible (closed-source, undocumented format). However, controlling existing RM rules IS feasible via the `hubitat.helper.RMUtils` class, which is available in the Hubitat app sandbox.

- [ ] **List all RM rules via `RMUtils.getRuleList()`** — `Difficulty: 1 | Effort: S`
  > *Feasible.* Confirmed working. `RMUtils.getRuleList("5.0")` returns RM 5.x rules; `getRuleList()` returns legacy rules. Returns a list suitable for enum options with rule IDs and names.
  >
  > **Implementation plan:**
  > 1. Add `import hubitat.helper.RMUtils` to parent app
  > 2. Create `hub_list_rules` MCP tool
  > 3. Call both `getRuleList("5.0")` and `getRuleList()` for full coverage
  > 4. Handle the case where Rule Machine is not installed

- [ ] **Enable/disable RM rules** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Uses `RMUtils.sendAction(ruleIds, "pauseRule"/"resumeRule", app.label, "5.0")`. "Pause" is equivalent to "disable" in RM terminology.
  >
  > **Implementation plan:**
  > 1. Create `control_rm_rule` MCP tool with `action` parameter
  > 2. Support actions: `pause` (disable), `resume` (enable)
  > 3. Validate rule ID exists via `getRuleList()` first

- [ ] **Trigger RM rule actions via `RMUtils.sendAction()`** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Supported actions: `"runRuleAct"` (execute actions, skip conditions), `"runRule"` (evaluate conditions then run), `"stopRuleAct"` (cancel delayed/repeating actions). Note: `"runRule"` not applicable to Rule 4.x+.
  >
  > **Implementation plan:**
  > 1. Add `run_actions`, `evaluate`, `stop_actions` to the `control_rm_rule` tool
  > 2. Map to RM action strings internally
  > 3. Document which actions work with which RM versions

- [ ] **Pause/resume RM rules** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Same mechanism as enable/disable above. Can be combined into the unified `control_rm_rule` tool.

- [ ] **Set RM Private Booleans** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Uses `RMUtils.sendAction(ruleIds, "setRuleBooleanTrue"/"setRuleBooleanFalse", app.label, "5.0")`. Straightforward API.
  >
  > **Implementation plan:**
  > 1. Add `set_boolean_true` and `set_boolean_false` to `control_rm_rule` tool
  > 2. Accept rule ID and boolean value, map to appropriate sendAction call

- [x] **Hub variable bridge for cross-engine coordination** — `Difficulty: 2 | Effort: S`
  > *Already ~90% implemented.* The existing `hub_set_variable`/`hub_get_variable` tools work with Hubitat's global connector variables via `getGlobalConnectorVariable()`/`setGlobalConnectorVariable()`. These are the same variables Rule Machine reads/writes. Variables set via MCP are immediately visible to RM and vice versa. To formalize: document the convention that shared variables should use hub connector variables.

---

### Integration & Streaming

- [ ] **Event streaming / webhooks (real-time POST of device events)** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Subscribe to device events and `asynchttpPost()` payloads to registered URLs. The rule engine already implements `http_request` actions via `httpPost()`. Use `asynchttpPost` (non-blocking) to avoid blocking the event queue during bursts. Rate limiting is important.
  >
  > **Implementation plan:**
  > 1. Create `configure_webhook` MCP tool to register endpoint URLs and event filters
  > 2. Store webhook configs in `state.webhookSubscriptions`
  > 3. Subscribe to relevant device events in `initialize()`
  > 4. Event handler checks filters, formats payload, and POSTs asynchronously
  > 5. Include rate limiting (configurable events per minute per webhook)
  > 6. Payload format: `{deviceId, label, attribute, value, timestamp, description}`

- ~~[ ] **MQTT client (bridge to Node-RED, Home Assistant, etc.)**~~ — `Difficulty: 4 | Effort: L`
  > *Not directly feasible from an app.* Hubitat's `interfaces.mqtt` API is only available in drivers, not apps. The Groovy sandbox also doesn't allow `java.net.Socket` or arbitrary Java imports for a custom MQTT implementation.
  >
  > **Alternative: Companion driver approach** *(added below)*

- [ ] **MQTT via companion driver** *(alternative to direct MQTT)* — `Difficulty: 4 | Effort: L`
  > *Feasible via workaround.* Create a custom `MCP MQTT Bridge Driver` that uses `interfaces.mqtt` to connect to a broker. The MCP app creates this driver as a child device via `addChildDevice()`. Communication flows through device commands (app→driver: `publish(topic, message)`) and events (driver→app: `sendEvent()` + `subscribe()`). This adds a third Groovy file to the project.
  >
  > **Implementation plan:**
  > 1. Create `MCP MQTT Bridge` driver with `interfaces.mqtt`
  > 2. Driver exposes commands: `connect`, `disconnect`, `publish`, `subscribe`
  > 3. Driver fires events on incoming messages and connection status
  > 4. Parent app creates driver instance via `addChildDevice()`
  > 5. Add `mqtt_publish`, `mqtt_subscribe`, `mqtt_status` MCP tools
  > 6. Add driver to HPM package manifest
  >
  > **Alternative (simpler):** Use the existing `http_request` action to bridge via HTTP-to-MQTT gateways (Node-RED HTTP-in nodes, HiveMQ REST API, EMQX REST API).

---

### Advanced Automation Patterns

> These patterns don't require new MCP tools — the AI assistant can already compose them using existing `hub_create_custom_rule`, `hub_set_variable`, `create_virtual_device`, and other tools. They're documented here as reference patterns showing what's achievable today with the current rule engine. No dedicated wizard tools are planned unless a specific gap is identified.

- [ ] **Occupancy / room state machine** — `No new tools needed`
  > *Already achievable.* The AI can compose this using existing primitives: a hub variable `roomState_<room>` holds state (vacant/occupied/engaged/checking). `device_event` triggers on motion/contact sensors feed into `if_then_else` chains with `set_variable` actions for state transitions. Duration-based triggers handle timeouts. Other rules check room state via `variable` conditions. No dedicated tool required — the AI can build this pattern on request using `hub_create_custom_rule` and `hub_set_variable`.

- [ ] **Presence-based automation (first-to-arrive, last-to-leave)** — `No new tools needed`
  > *Already achievable.* The AI can compose this: a hub variable `homeCount` tracks present people. `device_event` triggers on presence sensors increment/decrement via `variable_math`. Rules with `variable` conditions fire when `homeCount` transitions 0→1 (first arrive) or 1→0 (last leave). All building blocks exist today.

- [ ] **Weather-based triggers** — `No new tools needed`
  > *Already achievable with weather device drivers.* Many Hubitat users have weather drivers installed (OpenWeatherMap, Weather Underground, etc.) that expose weather data as device attributes. If the user selects the weather device in MCP, it's already triggerable via `device_event` triggers and `device_state` conditions — zero code changes needed. For users without a weather driver, the AI could create a `periodic` rule with `http_request` actions to poll a weather API and store results in hub variables.

- [ ] **Vacation mode (random light cycling, auto-lock, energy savings)** — `No new tools needed`
  > *Already achievable.* The AI can compose this using existing primitives: `mode_change` trigger → `capture_state` + lock commands + thermostat setpoints. A `periodic` trigger with `mode` condition cycles random lights (the rule engine has `repeat` actions and `delay` with variable offsets). Mode return triggers `restore_state`. `new Random()` is available in the sandbox for randomized timing. All building blocks exist today.

---

### Monitoring & Diagnostics

- [ ] **Device health watchdog** — `Difficulty: 2 | Effort: S`
  > *Feasible.* The existing `hub_get_device_health` tool is on-demand only. Enhancement: add a scheduled background check (every 4–6 hours) that proactively detects stale/offline devices and low batteries. Push alerts via notification devices. Write results to a CSV for trend analysis. Fire a `mcpDeviceHealthAlert` location event for rule integration.
  >
  > **Implementation plan:**
  > 1. Add `schedule("0 0 */6 ? * *", "runHealthWatchdog")` to parent app
  > 2. Reuse `toolDeviceHealthCheck()` logic
  > 3. Add battery monitoring: check `device.currentValue("battery")` for low levels
  > 4. Send notifications for new stale/low-battery devices
  > 5. Fire `sendLocationEvent(name: "mcpDeviceHealthAlert")` for rule integration
  > 6. Log to CSV for trend tracking

- [ ] **Z-Wave ghost device detection** — `Difficulty: 3 | Effort: M`
  > *Partially feasible (detection only).* Fetch Z-Wave node table via `/hub/zwaveDetails/json`, cross-reference with the device list, and identify nodes with no matching device or with failed states. Automated removal is not possible — ghost removal requires the hub's web UI Z-Wave Details page.
  >
  > **Implementation plan:**
  > 1. Create `detect_zwave_ghosts` MCP tool (requires the Read master)
  > 2. Fetch Z-Wave node table and device list
  > 3. Cross-reference: nodes with no deviceId or failed states are ghosts
  > 4. Return report with ghost nodes and recommended manual actions
  > 5. Link to hub UI Z-Wave Details page for remediation

- [ ] **Event history / analytics** — `Difficulty: 3 | Effort: M–L`
  > *Partially feasible.* The 7-day limit on `eventsSince()` is a platform constraint. For longer history: add a background scheduler that periodically samples device states and writes to CSV files in File Manager. For analytics: compute event frequency, state duration percentages, min/max/avg for numeric attributes from available history data. Cross-device correlation is computationally expensive in the sandbox.
  >
  > **Implementation plan:**
  > 1. Create `analyze_device_history` MCP tool for analytics on existing 7-day data
  > 2. Add scheduled CSV logging for long-term device state sampling
  > 3. Compute statistics: event frequency, state duration %, numeric min/max/avg
  > 4. Return structured analytics per device
  > 5. Note: computation-heavy analytics may time out with many devices

- [x] **Hub performance trend monitoring** — `Difficulty: 1 | Effort: S`
  > *Mostly already implemented.* The `hub_get_metrics` tool records snapshots to CSV, maintains a 500-point rolling window, returns configurable trend points, and includes threshold warnings. **Incremental enhancement:** add scheduled periodic sampling (every 4 hours) instead of only recording when the AI calls the tool. Add trend direction analysis (rate of change, declining memory detection).
  >
  > **Implementation plan (incremental):**
  > 1. Add `schedule("0 0 */4 ? * *", "recordPerformanceSnapshot")` to parent
  > 2. Add trend analysis: compare latest N points for direction/rate of change
  > 3. Alert on sustained declining trends (not just current thresholds)

---

### Notification Enhancements

- [ ] **Pushover integration with priority levels** — `Difficulty: 2 | Effort: M`
  > *Feasible.* Simple `httpPost()` to `https://api.pushover.net/1/messages.json`. The Hubitat ecosystem already has a built-in Pushover driver, but a direct API integration gives more control (priority levels -2 to 2, sounds, supplementary URL, emergency retry/expire).
  >
  > **Implementation plan:**
  > 1. Add Pushover API key and User key to app preferences
  > 2. Create `send_pushover` MCP tool with message, title, priority, sound params
  > 3. `httpPost()` to Pushover API with form-encoded body
  > 4. For emergency priority (2), include `retry` and `expire` parameters
  > 5. Add as a notification channel for rule actions

- [ ] **Email notifications via SendGrid** — `Difficulty: 2 | Effort: M`
  > *Feasible.* JSON POST to `https://api.sendgrid.com/v3/mail/send` with Bearer token auth. The existing code already does JSON POST with custom headers (room save code uses `requestContentType: "application/json"`).
  >
  > **Implementation plan:**
  > 1. Add SendGrid API key, sender email, default recipient to app preferences
  > 2. Create `send_email` MCP tool with to, subject, body, optional html params
  > 3. `httpPost()` to SendGrid v3 API with JSON body and Bearer auth header
  > 4. Add as a notification channel for rule actions

- [ ] **Rate limiting / throttling** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Pure in-app logic using `state`/`atomicState` and `now()`. Store timestamps of recent notifications per channel. Check cooldown before sending. Follow the existing loop guard pattern for sliding-window rate limiting. Configurable per-channel cooldown and max-per-hour limits.
  >
  > **Implementation plan:**
  > 1. Add `state.notificationHistory` tracking per-channel timestamps
  > 2. Check cooldown before each notification send
  > 3. Add configurable thresholds in app preferences
  > 4. Return throttle status in tool response when rate-limited

- [ ] **Notification routing by severity** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Define severity levels (info/warning/critical/emergency). Map each severity to notification channels in app preferences. Dispatch to appropriate channels. Depends on Pushover and SendGrid being implemented first.
  >
  > **Implementation plan:**
  > 1. Define severity levels: info, warning, critical, emergency
  > 2. Add severity-to-channel routing in app preferences
  > 3. Create `send_alert` MCP tool with message and severity params
  > 4. Routing logic reads config and dispatches to matching channels
  > 5. Each channel (Pushover, SendGrid, hub notification device) is a separate method

---

### Additional Ideas

- [ ] **Standalone virtual device creation (independent of MCP app)** — `Difficulty: 2 | Effort: S–M`
  > *Feasible but unverified.* The hub internal API `POST /device/save` may support device creation with `id=""` (Grails convention). The created device would appear in the regular Devices section and persist even if MCP is uninstalled. However: this endpoint is documented for updates, not creation — needs empirical testing. Built-in driver type IDs may not be discoverable via `/hub2/userDeviceTypes`.
  >
  > **Implementation plan:**
  > 1. Test `/device/save` with `id=""` on actual hub hardware
  > 2. Discover built-in driver type IDs for Virtual Switch, Virtual Dimmer, etc.
  > 3. Create `create_standalone_device` MCP tool (Write master required)
  > 4. Note: device won't auto-appear in MCP's device list without user selection
  > 5. Fallback: use existing `create_virtual_device` with `isComponent: false`

- [ ] **Scene management (create/modify beyond activate_scene)** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Native Groups and Scenes CRUD is not possible (no API). However, the existing `capture_state`/`restore_state` system is already a de facto scene manager. Enhancement: wrap with scene-oriented terminology — `create_scene` (capture), `list_scenes` (list captures), `activate_scene` (restore), `delete_scene` (delete capture). Extend captured attributes beyond switch/level/color to include fan speed, shade position, thermostat setpoints.
  >
  > **Implementation plan:**
  > 1. Create scene alias tools wrapping existing captured state tools
  > 2. Extend `capture_state` to save additional attributes (fan speed, shade position, thermostat)
  > 3. Add `modify_scene` tool that re-captures specific devices in an existing scene
  > 4. Document that these are MCP-managed scenes, not native Hubitat scenes

- [ ] **Energy monitoring dashboard** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Create a `get_energy_summary` tool that aggregates `power` and `energy` attributes across all PowerMeter/EnergyMeter capable devices. Add scheduled CSV logging for trend data. "Dashboard" is a JSON summary in MCP context (no UI rendering), but could optionally write an HTML file to File Manager accessible at `http://<HUB_IP>/local/energy-dashboard.html`.
  >
  > **Implementation plan:**
  > 1. Create `get_energy_summary` MCP tool
  > 2. Find all devices with PowerMeter/EnergyMeter capabilities
  > 3. Aggregate current power (W) and cumulative energy (kWh)
  > 4. Return per-device breakdown and totals
  > 5. Add scheduled CSV logger for energy trend data
  > 6. Optionally: generate static HTML file for visual dashboard

- [ ] **Scheduled automated reports** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Scheduled via `schedule()` with cron expressions. Reports aggregate data from existing monitoring tools (device health, hub performance, rule activity). Save to File Manager as JSON. Push summary via notification devices. For email delivery, use SendGrid integration (if implemented) or `httpPost()` to external services.
  >
  > **Implementation plan:**
  > 1. Add `configure_report_schedule` MCP tool
  > 2. Define report templates: hub health, device status, rule activity, energy
  > 3. Schedule via `schedule()` with user-configured cron expression
  > 4. Aggregate data from existing tool methods
  > 5. Save full report to File Manager as JSON
  > 6. Push summary notification via configured channels

- ~~[ ] **Device pairing assistance (Z-Wave, Zigbee, cloud)**~~ — `Difficulty: 4 | Effort: L`
  > *Not feasible for active pairing.* Device pairing (Z-Wave inclusion, Zigbee pairing) is an interactive, real-time, radio-level operation. The hub's Z-Wave/Zigbee inclusion mode endpoints are undocumented and pairing is a multi-step, timing-sensitive process incompatible with MCP's request-response model. Cloud integrations require OAuth UI flows.
  >
  > **Alternative: Pairing guidance tool** *(added below)*

- [ ] **Device pairing guidance** *(alternative to active pairing)* — `Difficulty: 2 | Effort: S`
  > *Feasible.* A `guide_device_pairing` tool that provides step-by-step textual instructions for using the Hubitat web UI to pair devices. After pairing, the AI can help configure the device (driver selection, room assignment, label, preferences) using existing MCP tools like `hub_update_device`, `hub_call_device_command`, and room management tools.
  >
  > **Implementation plan:**
  > 1. Create `guide_device_pairing` MCP tool
  > 2. Accept device type (Z-Wave, Zigbee, cloud) and optional model info
  > 3. Return step-by-step instructions with direct links to hub UI pages
  > 4. After pairing, offer to configure via existing MCP tools

---

### Infeasible Items Summary

> The following items have been determined to be not achievable due to platform constraints. They are listed here with explanations and the alternatives that have been added to the plan above.

- ~~**Groups and Scenes (Zigbee group messaging)**~~ — The `zigbee` object and `sendHubCommand(Protocol.ZIGBEE)` are driver-only APIs. No HTTP endpoint exists for Zigbee group management. **→ Use software group commands or the built-in Groups and Scenes app's activator devices via `hub_call_device_command`**

- ~~**MQTT client (direct)**~~ — `interfaces.mqtt` is driver-only. No raw TCP sockets in apps. **→ Companion driver approach added as alternative**

- ~~**Device pairing assistance (active)**~~ — Radio inclusion is interactive and undocumented. MCP's request-response model can't handle multi-step pairing flows. **→ Pairing guidance tool added as alternative**

---

### Recommended Implementation Priority

> Based on the ratio of user value to implementation effort. Excludes items that the AI can already compose using existing tools (occupancy, presence, vacation mode, weather, and native app equivalents like Mode Manager, Button Controller, etc.).

#### Phase 1: Quick Wins (Small effort, high value)
1. **Rule Machine Interoperability** (list, control, trigger, booleans) — All use `RMUtils`, implement as 1–2 tools
2. **Native hub variable change triggers** — `subscribe(location, "variable:<name>", handler)` + `addInUseGlobalVar()` registration
3. **ICMP ping** — done; folded into `hub_get_device_health` via `pingHosts`/`pingCount` (issue #91)
4. **Search HPM repositories** — Public GraphQL API, immediate discovery value
5. **Rate limiting / throttling** — Pure in-app logic, enables safer notifications
6. **System start trigger** — Single `subscribe()` call
7. **Date range condition** — Follows existing condition patterns
8. **Device health watchdog** — Add scheduled task to existing tool
9. **Private Boolean per rule** — Cross-rule coordination via parent mediation
10. **Disable/Enable a device action** — Wraps existing `hub_update_device` capability
11. **File write/append/delete actions** — Wraps existing parent file methods
12. **Music/siren control actions** — Convenience wrappers around `device_command`

#### Phase 2: Core Enhancements (Medium effort, high value)
13. **Webhook triggers** — New endpoint + trigger type
14. **Event streaming / webhooks** — Subscribe + async POST
15. **Pushover integration** — Simple API integration
16. **Email via SendGrid** — Simple API integration
17. **Fade dimmer / color temp** — Shared ramp utility
18. **Rule-to-rule control** — Parent mediation, existing methods
19. **Notification routing** — Layer on top of Pushover/SendGrid
20. **Z-Wave ghost detection** — Node table cross-reference
21. **Variable change events** — Location event on variable write
22. **Per-mode actions** — Multi-branch action type

#### Phase 3: Advanced Features (Large effort, specialized value)
23. **Boolean expression builder** — Recursive tree evaluation + migration
24. **Required expressions / gates** — Continuous monitoring architecture
25. **MQTT via companion driver** — Third file, driver development
26. **Dashboard create/modify/delete** — Internal endpoint approach, needs hub testing
27. **Scheduled reports** — Aggregation + scheduling + delivery
28. **Energy monitoring** — Aggregate power/energy across devices
29. **Scene management** — Scene-oriented wrappers around captured state

#### Phase 4: Exploratory (Needs testing or has significant limitations)
30. **Wait for Event / Expression** — Complex state persistence
31. **Repeat While / Until** — Loop safety concerns
32. **Hub Variable Connectors** — Custom driver dependency
33. **Install packages via HPM** — HPM sync fragmentation
34. **Standalone virtual devices** — API endpoint unverified
35. **Event history / analytics** — Platform 7-day limit

#### Low Priority: Native App Equivalents (only if MCP interaction gaps found)
36. **Room Lighting** — Use native app; review if MCP can't interact with its effects
37. **Zone Motion Controller** — Use native app; MCP can already see its virtual device
38. **Mode Manager** — Use native app; MCP already reads/sets modes
39. **Button Controller** — Use native app; MCP already has `button_event` triggers
40. **Thermostat Scheduler** — Use native app; MCP already has `set_thermostat` actions
41. **Lock Code Manager** — Use native app; review if `hub_call_device_command` proves insufficient

</details>
<!-- FUTURE_PLANS_END -->








---

## Version History

- **v2.2.2** - fix: VRB-first rule guidance; rebuild e2e restore on the HPM importUrl path. PRs: [#268](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/268)
- **v2.2.1** - feat: per-app JSON reads, direct-alias app resolution, and hub_call_device_swap. PRs: [#267](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/267)
- **v2.2.0** - docs: list string/decimal for runCommand parameter types; feat: add Visual Rules Builder tools to the rule machine gateway. PRs: [#266](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/266), [#265](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/265)
- **v2.1.7** - feat: friendly tool titles, descriptive overrides menu, and the full four-hint annotation surface. PRs: [#264](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/264)
- **v2.1.6** - feat: update app/driver code via saveOrUpdateJson instead of legacy ajax/update. PRs: [#263](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/263)
- **v2.1.5** - Wire device-relative compareToDevice to the real RM 5.1.8 control (isDev_<N>). PRs: [#262](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/262)
- **v2.1.4** - feat: register basic_rule, add Button Rule creation + walkStep to the native-app tool. PRs: [#260](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/260)
- **v2.1.3** - feat: hub_update_package full HPM-repair deploy, top-level dev-mode tool. PRs: [#261](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/261)
- **v2.1.2** - fix: enum-recognized Custom Attribute false-partial across all RM 5.1 wizard surfaces. PRs: [#244](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/244)
- **v2.1.1** - chore: vendor classic-UI dynamicPage engine as RM wire-format reference; ci: speed up the watchdog e2e ~23% (bundle-only deploy, batched fixtures, deferred deletes); docs: point AGENTS.md at resources/hub2-source as the reverse-engineering reference; feat: surface pending hub firmware update + health alerts from /hub2/hubData. PRs: [#253](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/253), [#251](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/251), [#255](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/255), [#256](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/256)
- **v2.1.0** - ci: auto-run e2e on trusted fork PRs, gate other contributors; lease waits for a busy hub; ci: e2e dead-man watchdog v2 as a 2nd MCP server — drive deploy through it (kills self-update 504s); docs: standalone-watchdog e2e architecture + pull_request_target trigger gotcha; feat: modularize Rooms + bundle tools into #include libraries + bundle-management tools (#209). PRs: [#246](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/246), [#248](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/248), [#249](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/249), [#247](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/247)
- **v2.0.4** - feat: installAsUserApp install-commit fix + #include smoke test + e2e dead-man watchdog (#209). PRs: [#243](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/243)
- **v2.0.3** - feat: hub_update_package dev tool — one-call app+library deploy at a git ref (#209). PRs: [#242](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/242)
- **v2.0.2** - feat: add hub_list_libraries read tool. PRs: [#241](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/241)
- **v2.0.1** - docs: hub firmware-update research notes + resilience plan; chore: HPM bundle smoke-test library for issue #209 modularization. PRs: [#240](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/240), [#239](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/239)
- **v2.0.0** - build(deps): bump the gradle-dependencies group with 2 updates ([#223](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/223), @app/dependabot); refactor: hub_ rename + consolidation of MCP tool surface (issue #105 PR1A); docs: add issue #105 PR2 backend/server audit game plan; feat: split MCP tool gateways into read-only (hub_read_*) and write-bearing (hub_manage_*); feat: add outputSchema to every MCP tool (issue #105 PR1C); fix: PR2a — correctness & security fixes for the MCP server backend (issue #105); ci: add additive Groovy 2.5 Spock runtime lane (issues #227, #230); feat: PR2b/2c backend robustness, performance, and cleanup (issue #105); feat: universal Read/Write permission masters + per-tool/gateway overrides; fix: PR2-legacy — child-app bug fixes + state hygiene (issue #105); feat: split native-app CRUD into hub_set_rule (RM) + hub_set_native_app (generic) (#137). PRs: [#223](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/223), [#224](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/224), [#226](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/226), [#225](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/225), [#229](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/229), [#231](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/231), [#232](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/232), [#233](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/233), [#235](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/235), [#234](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/234), [#236](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/236)
- **v1.4.5** - fix: complete RM 5.1 Periodic Schedule trigger support. PRs: [#222](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/222)
- **v1.4.4** - chore: pin commons-collections to 3.2.2 (test-only deserialization fix); fix: compareToVariable on the RM condition walker + addTrigger partial-filter + compareToDevice guard. PRs: [#221](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/221), [#220](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/220)
- **v1.4.3** - docs: introducing security policy; Potential fix for code scanning alerts: Workflow does not contain permissions; fix: commit clearActions/replaceActions delete synchronously via full page-form submit (closes #172). PRs: [#218](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/218), [#219](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/219), [#217](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/217)
- **v1.4.2** - feat: importUrl + installAsUserApp + triggerUpdated for app/driver/library install + update tools. PRs: [#213](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/213)
- **v1.4.1** - Revert "refactor: flat-mode tool surface reduction (103 → 95 tools)". PRs: [#216](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/216)
- **v1.4.0** - refactor: flat-mode tool surface reduction (103 → 95 tools). PRs: [#208](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/208)
- **v1.3.15** - docs: codify MCP tool design rules in AGENTS.md + derivative docs (part of #105); docs: vendor hub2 Vue SPA source as reference resource; docs: add PR1 (issue #105 tool audit) game plan to docs/; feat: per-capability reveal walker fixes Required Expression & ifThen Broken Conditions (issue #195 Group A). PRs: [#210](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/210), [#211](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/211), [#214](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/214), [#203](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/203)
- **v1.3.14** - fix: prevent (and detect) IF/END-IF + Repeat structural imbalance (#178). PRs: [#206](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/206)
- **v1.3.13** - fix: drop top-level anyOf from import_native_app input_schema (Haiku 4.5 compat). PRs: [#205](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/205)
- **v1.3.12** - feat: addAction Set-Variable, runCommand variable parameters, Set-Mode modeName. PRs: [#196](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/196)
- **v1.3.11** - feat: MCP readOnlyHint + destructiveHint annotations on every tool. PRs: [#202](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/202)
- **v1.3.10** - fix(get_tool_guide): expose schema-referenced reference sections + add drift lint. PRs: [#201](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/201)
- **v1.3.9** - fix: release-notes generator was picking issue refs instead of PR numbers. PRs: [#200](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/200)
- **v1.3.8** - docs: add CONTRIBUTING.md and link it from styleguide; fix: end beta-status paragraph with bug-report guidance, not dev jargon; PR #181. PRs: [#197](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/197), [#199](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/199)
- **v1.3.7** - test: close issue #141 Section A Spock coverage gaps; ci(hub-e2e): deploy PR source to test hub before running tests; PR #169. PRs: [#193](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/193), [#192](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/192)
- **v1.3.6** - PR #187; PR #174
- **v1.3.5** - feat: optimistic-lock + self-update guard for update_app_code / update_driver_code. PRs: [#189](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/189)
- **v1.3.4** - feat: fold location event history into get_device_history. PRs: [#188](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/188)
- **v1.3.3** - feat(manage_virtual_device): allow custom-driver instantiation via {namespace, name}. PRs: [#168](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/168)
- **v1.3.2** - test: cut gradle test suite time ~87% via per-JVM compile cache + strict-mode CI matrix; feat: add identify-hub LED option to get_hub_info and device_health_check; feat(tools/list, manage_hpm): cursor pagination + R7 doc/spec follow-up. PRs: [#184](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/184), [#186](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/186), [#180](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/180)
- **v1.3.1** - feat: rework generate_bug_report for issue templates, scoped logs, public-safe mode. PRs: [#182](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/182)
- **v1.3.0** - chore(sandbox_lint): enforce tool-count consistency + sync doc drift; feat(manage_hpm): HPM read-only gateway — list_hpm_packages + get_hpm_drift. PRs: [#165](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/165), [#167](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/167)
- **v1.2.1** - feat(manage_app_driver_code): library management (install/update/delete/get_source). PRs: [#164](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/164)
- **v1.2.0** - feat: add clone/export/import_native_app via Hubitat appCloner. PRs: [#158](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/158)
- **v1.1.2** - docs(futureplans): align rule-tool names with PR #134 custom_ rename; feat(manage_app_driver_code): driver-code lifecycle improvements -- sourceFile + bulk + token-economy. PRs: [#162](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/162), [#163](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/163)
- **v1.1.1** - ci: consolidate post-merge automation into release workflow (closes race). PRs: [#160](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/160)
- **v1.1.0** - feat(devices): add poll_until_attribute -- block-poll until attribute matches; PR #92. PRs: [#157](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/157)
- **v1.0.5** - docs: correct AGENTS.md falsehoods and auto-sync CLAUDE.md; feat(get-hub-logs): server-side regex / multi-pattern / time-window filters. PRs: [#156](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/156), [#155](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/155)
- **v1.0.4** - feat(list-devices): server-side label/capability filters + format/fields projection. PRs: [#153](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/153)
- **v1.0.3** - feat(rule-tools): redirect hint when caller passes a built-in RM rule id (addresses #118 Option A). PRs: [#135](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/135)
- **v1.0.2** - docs: add AGENTS.md for AI contributors; PR #91. PRs: [#149](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/149)
- **v1.0.1** - feat: optional flat tool-list mode (toggle off category gateways). PRs: [#136](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/136)
- **v1.0.0** - ci(hub-e2e): self-configuring CI workflow against test hub (closes #77); feat(rm-native): native Rule Machine tools and classic-app CRUD + custom_ rename of MCP rule engine tools. PRs: [#148](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/148), [#134](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/134)
- **v0.11.1** - build(deps): bump gradle-wrapper from 9.4.1 to 9.5.0 in the gradle-dependencies group ([#143](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/143), @app/dependabot); feat(release): author-curated, minor-scoped HPM release notes + PR review tooling. PRs: [#143](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/143), [#146](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/146)
- **v0.11.0** - fix(release): positive-match the skip cascade so recursion guard short-circuits cleanly; PR #120; tests: RM 5.1 native BAT suite — acceptance gate for #120; docs: add Gemini testing results for PR #134; fix(manage_virtual_device): rename "Virtual Presence Sensor" enum entry to "Virtual Presence"; feat(developer-mode): add manage_mcp_self gateway + delete_variable. PRs: [#123](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/123), [#133](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/133), [#138](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/138), [#144](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/144), [#145](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/145)
- **v0.10.1** - test(server): unit-test manage_destructive_hub_ops / manage_apps_drivers / manage_app_driver_code gateways; test(server): unit-test manage_logs / manage_diagnostics / manage_files gateways; PR #75; test(rules): breadth coverage for conditions, actions, triggers, loop guard, error paths (closes #75); test: backfill regression specs from CHANGELOG / release-notes history (closes #76); PR #76; Add get_app_config + list_app_pages (manage_installed_apps gateway); test: backfill sunrise/sunset silent-failure fix + broader silent-device-not-found coverage (#76); PR #77; test(integration): in-harness dispatch drive-through for handleMcpRequest + subscribe/fire (#77); fix(release): push via deploy key to bypass main-branch ruleset. PRs: [#110](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/110), [#111](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/111), [#115](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/115), [#116](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/116), [#112](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/112), [#117](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/117), [#119](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/119), [#122](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/122)
- **v0.10.0** - docs: re-collapse Future Plans + refresh MCP tools list; build(deps): bump the gradle-dependencies group with 2 updates ([#101](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/101), @app/dependabot); fix(lint): scan GString interpolations for sandbox violations; perf(test): cache HubitatAppSandbox parse per spec class (5m → 1.5m); Built-in app visibility + Rule Machine interop (2 new gateways, 7 tools); test(server): unit-test manage_rules_admin / manage_hub_variables / manage_rooms gateways; test(rm-interop): pin registerRmRule warn-log emission and type classification; fix(release): trigger on push to main (fork-PR bot-permission workaround); fix(release): cascade skip flags + retry PR lookup for indexing lag. PRs: [#102](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/102), [#101](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/101), [#103](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/103), [#104](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/104), [#79](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/79), [#106](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/106), [#107](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/107), [#108](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/108), [#109](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/109)
- **v0.9.7** - build: add Groovy/Spock/HubitatCI test harness (#69); test: gateway proxy dispatch + JSON-RPC envelope + resolution paths; ci: silence Groovy 2.5 reflective warnings + run Gradle daemon on JDK 17; chore: migrate hubitat_ci to joelwetzel fork + Dependabot + version-check; docs: credit biocomp and joelwetzel for the test harness; build(deps): bump the github-actions group with 5 updates ([#87](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/87), @app/dependabot); build(deps): bump gradle-wrapper from 8.10 to 9.4.1 in the gradle-dependencies group ([#86](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/86), @app/dependabot); build: assignment syntax for url + exceptionFormat (Gradle 10 prep); docs(futureplans): correct two 'infeasible' claims contradicted by Hubitat docs; test: rule-engine primitive specs (closes #71); chore: migrate test harness from joelwetzel to eighty20results/hubitat_ci. PRs: [#81](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/81), [#82](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/82), [#83](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/83), [#85](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/85), [#88](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/88), [#87](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/87), [#86](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/86), [#89](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/89), [#90](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/90), [#98](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/98), [#100](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/100)
- **v0.9.6** - fix: drop PR reference from packageManifest.json releaseNotes. PRs: [#80](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/80)
- **v0.9.5** - feat: include PR main-commit extended description in release notes. PRs: [#78](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/78)
- **v0.9.4** - fix: release workflow pushes the version tag explicitly; chore: drop unused json import in sandbox_lint.py. PRs: [#67](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/67), [#68](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/68)
- **v0.9.3** - Release automation: bot-driven version bumps + CHANGELOG + release notes sync. PRs: [#66](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/66)
- **v0.9.2** - Enriched `list_devices` summary (new fields: `disabled`, `deviceNetworkId`, `lastActivity`, `parentDeviceId`) + server-side `filter` arg (`enabled` / `disabled` / `stale:<hours>`) applied before pagination — closes the N+1 roundtrip problem for common bulk questions. Fix `get_hub_logs` ordering (now returns most recent entries first; previously returned oldest from the ring buffer) + new `deviceId` / `appId` server-side scope args (~93% payload reduction when scoped).
- **v0.9.1** - New `search_tools`: BM25 natural language search across all 74 MCP tools (core + gateway sub-tools). Searches tool names, descriptions, and parameter names. Returns matching tools ranked by relevance with gateway attribution so the LLM knows how to call them. Inspired by FastMCP 3.1 Tool Search transform. 74 MCP tools total (31 on `tools/list`).
- **v0.9.0** - New tools: `get_performance_stats` (device/app performance stats — method call counts, % busy, cumulative total ms, state size, events, large state flag; sortable by `pct`/`count`/`stateSize`/`totalMs`/`name`) and `get_hub_jobs` (scheduled/running jobs, hub actions), both in `manage_logs` gateway. Enhanced `get_memory_history`: `limit` parameter (default 100) to prevent response-too-large errors, now includes Java heap (`totalJavaKB`, `freeJavaKB`) and direct/NIO buffer memory (`directJavaKB`) per entry with min/max tracking in summary for leak detection. 73 MCP tools total (30 on `tools/list`).
- **v0.8.7** - Add memory diagnostic tools: `get_memory_history` and `force_garbage_collection`, both in `manage_diagnostics` gateway. 71 MCP tools total.
- **v0.8.6** - Bug fix: `days_of_week` condition crash (`Date.format(String, Locale)` not available in Hubitat sandbox).

<details>
<summary><b>Older versions (v0.7.0 – v0.8.5)</b></summary>

- **v0.8.5** - Bug fixes: fix `send_command` Map-parameter handling (Hubitat's JSON parser chokes on nested JSON objects in parameter arrays, falling back to raw String — now extracts embedded JSON objects by brace-matching), fix `get_hub_logs` source filter (was checking timestamp field instead of message field — source searches never matched app/device names), add JSON string-to-Map parsing in rule action parameter converter
- **v0.8.2** - Critical bug fixes: fix rule action execution crash (`log.isDebugEnabled()` not available in Hubitat sandbox — all rule actions were silently failing), fix `send_command` setColor/map-parameter handling (JSON string parameters now auto-parsed into Maps for commands like `setColor` that expect Map arguments)
- **v0.8.1** - Bug fixes: remove dead code (toolEnableRule/toolDisableRule/toolToggleRule), fix stale tool references in messages and docs, update BAT-v2 for final 9-gateway architecture
- **v0.8.0** - Category gateway proxy: consolidate 48 tools behind 9 domain-named gateways, reducing `tools/list` from 69 to 30. 21 core tools stay ungrouped (devices, rules, modes, HSM, update_device, manage_virtual_device, list_virtual_devices, get_hub_info, create_hub_backup, check_for_update, generate_bug_report, get_tool_guide). Merged get_hub_health and get_hub_details into get_hub_info — comprehensive hub info (hardware, health, MCP stats) always available; PII/location data (name, IP, timezone, coordinates, zip code) gated behind Hub Admin Read. Merged enable_rule/disable_rule into update_rule (use `enabled=true/false`). Merged create_virtual_device/delete_virtual_device into manage_virtual_device (with `action` enum). Promoted create_hub_backup, check_for_update, and generate_bug_report to core tools. Dissolved manage_hub_info gateway (radio details moved to manage_diagnostics). Renamed manage_hub_maintenance to manage_destructive_hub_ops, manage_code_changes to manage_app_driver_code. Each gateway shows tool summaries with parameter hints in its description (always visible to LLMs) and returns full schemas on demand. Gateways: manage_rules_admin, manage_hub_variables, manage_rooms, manage_destructive_hub_ops, manage_apps_drivers (read-only), manage_app_driver_code, manage_logs, manage_diagnostics, manage_files. Modeled after ha-mcp PR #637. Breaking change: proxied tools removed from `tools/list` but accessible via gateways.
- **v0.7.7** - Code review round 2: MCP protocol fix (tool errors use isError flag per spec), fix formatAge() singular grammar, short-circuit condition evaluation, fix CI sed double-demotion, consolidate redundant API calls, guard eager debug logging, deduplicate sunrise/sunset reschedule, fix variable_math double atomicState read, efficiency improvements
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

## Testing

The `tests/` directory contains:

- **`tests/BAT-v2.md`** — Behavior Acceptance Tests (BAT): scripted scenarios for hand-run validation against a live hub. Includes the `wizard_probe` usage docs and the wizard-state regression appendix.
- **`tests/sandbox_lint.py`** — fast structural lint of the Groovy sandbox patterns (forbidden calls, version-string consistency). Run via `uv run --python 3.12 tests/sandbox_lint.py`.
- **`tests/e2e_test.py`** — end-to-end smoke test against a live hub. Requires `tests/e2e_config.json` (gitignored). Run via `uv run --python 3.12 --with requests tests/e2e_test.py`.
- **`tests/wizard_probe.py`** — systematic Rule Machine wizard-state regression probe. Runs a 25-probe matrix that exercises suspected wizard-state-leak paths, and exposes a `quick_probe()` helper for one-off diagnostic investigation. See the wizard_probe appendix in `tests/BAT-v2.md` for full usage.
- **Spock unit tests** under `src/test/groovy/` — run via the Gradle wrapper:

```bash
./gradlew test
```

See [docs/testing.md](docs/testing.md) for the full Spock harness overview, how to add new specs, and the RMUtils mocking recipe for `hub_manage_native_rules_and_apps` tools.

## Contributing

Contributions welcome! Fork the repo, create a feature branch, make your changes, and submit a pull request.

**New MCP tools must ship with unit tests** — both golden-path and error-path coverage. Tool handler tests go under `src/test/groovy/server/`; rule-engine tests under `src/test/groovy/rules/`. See [docs/testing.md](docs/testing.md) for the harness overview, the recipe for adding a new tool spec, and the RMUtils mocking pattern for `hub_manage_native_rules_and_apps`-style tools.

PRs that add tools without tests will be asked to add them before merge. CI (`./gradlew test`) runs on every PR via `.github/workflows/unit-tests.yml`.

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

- Built with assistance from [Claude](https://claude.ai) (Anthropic)
- Inspired by the [Model Context Protocol](https://modelcontextprotocol.io/)
- Inspired by the [Home Assistant MCP](https://github.com/homeassistant-ai/ha-mcp/)
- Thanks to the Hubitat community for documentation and examples
- [biocomp/hubitat_ci](https://github.com/biocomp/hubitat_ci) ([@biocomp](https://github.com/biocomp)) — original Hubitat Groovy unit-testing framework our harness is built on (Apache 2.0)
- [eighty20results/hubitat_ci](https://github.com/eighty20results/hubitat_ci) ([@eighty20results](https://github.com/eighty20results)) — actively-maintained Groovy 3.0 fork of the above, consumed as our test dependency via JitPack (Apache 2.0); previously we used [joelwetzel/hubitat_ci](https://github.com/joelwetzel/hubitat_ci) ([@joelwetzel](https://github.com/joelwetzel)) and migrated to eighty20results for Groovy 3 support and native sandbox mapping of `hubitat.helper.*` helpers
- [@ashwinma14](https://github.com/ashwinma14) - Fix for StackOverflowError on app install ([#15](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/15))
- [@level99](https://github.com/level99) - Enriched `hub_list_devices` summary + server-side `filter` arg ([#63](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/63)) and `hub_get_logs` ordering fix + `deviceId`/`appId` server-side scope ([#64](https://github.com/kingpanther13/Hubitat-local-MCP-server/pull/64))

## Disclaimer

This software is provided "as is", without warranty of any kind. This is an AI-assisted project and may contain bugs or unexpected behavior. Always test automations carefully, especially those controlling critical devices. The authors are not responsible for any damage or issues caused by using this software.
