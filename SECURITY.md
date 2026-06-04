# Security Policy

## Supported Versions

Security fixes are provided for the latest released Hubitat Package Manager version of MCP Rule Server.

The current release is `1.4.2`, released on `2026-05-27`. The package currently requires Hubitat firmware `2.3.0+`.

| Version | Supported |
| ------- | --------- |
| 1.4.2   | Yes       |
| < 1.4.2 | No        |

## Permission Model

Every MCP tool is gated by two universal master toggles, **Read** and **Write**, both **ON by default**:

- **Read** (`enableRead`) exposes every read-only / non-destructive tool. With it OFF, those tools are removed from the client and a cached call is rejected ("Read tools are disabled…").
- **Write** (`enableWrite`) exposes every state-changing tool (device control, modes, variables, rooms, files, native rules, hub admin). With it OFF, those tools are removed and a cached call is rejected ("Write tools are disabled…").

Both masters are enforced centrally at the dispatch chokepoint; only an explicit OFF blocks (unset = ON). The destructive/sensitive write tools additionally require `confirm=true` plus a hub backup within 24h, independent of the Write master. These two masters replace the former separate "Hub Admin Read", "Hub Admin Write", and "Built-in App Tools" toggles.

**Advanced per-tool / per-gateway overrides (deny-only).** Individual tools or whole gateways can be disabled below the masters (Settings > Advanced: Per-tool Overrides). These can only turn tools OFF; a disabled tool drops from `tools/list` and `hub_search_tools` and a cached call returns a distinct "…is disabled in Advanced settings (Per-tool Overrides)…" error.

### Intentional change: `hub_get_info` PII default

`hub_get_info` returns personally identifiable / location data — hub name, local IP, time zone, latitude, longitude, zip code, and hub data. As of the universal-masters change, this PII is returned **by default whenever the Read master is ON** (the default state). Previously it required an explicit opt-in toggle. This is an intentional change: because the local network is the trusted zone and the Read master already governs all read access, PII rides with the Read master rather than a separate gate. To withhold this PII, turn the **Read master OFF** — `hub_get_info` then omits those fields and includes a `readDisabledNote` explaining the exclusion. (Non-PII hardware/health/MCP-stats fields remain available regardless.)

## Reporting a Vulnerability

Please report security issues privately through GitHub's **Report a vulnerability** flow for this repository if it is available.

Do not include access tokens, hub IDs, endpoint URLs, or other secrets in public issues or pull requests. If private reporting is not available, open a GitHub issue with a brief, non-sensitive summary and state that you have security details to share privately.

We will review reports as time allows and publish fixes in the latest release when appropriate.
