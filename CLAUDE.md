# AGENTS.md

Conventions for AI coding agents working on this repo (OpenAI Codex, Cursor, Aider, Windsurf, Zed, etc.). Claude Code reads `CLAUDE.md`, which is kept byte-identical to this file — the PR Guard CI workflow flags drift. **AGENTS.md is the source of truth.** Edit it, then `cp AGENTS.md CLAUDE.md` before committing. (GitHub Copilot uses `.github/copilot-instructions.md`; Gemini Code Assist uses `.gemini/styleguide.md`.)

This file is for AI agents. Human contributors follow `.github/pull_request_template.md` and `.gemini/styleguide.md`.

## Commands

```bash
./gradlew test                     # full Spock suite (~1 min)
./gradlew test --tests "<spec>"    # single spec
python tests/sandbox_lint.py       # Groovy sandbox lint
```

Run both before pushing. CI runs the same.

## Code style

**Hubitat Groovy sandbox** (`hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`) blocks several JVM features the runtime would otherwise expose. Highlights — see `tests/sandbox_lint.py` for the full set:

- `Eval.*`, `GroovyShell`, `Class.forName`, `Runtime.exec`, `new Thread`, `new File` / `java.io.File` — not allowed
- `getClass()` — reflection blocked
- `log.isDebugEnabled()` — not exposed
- `Date.format(String, Locale)` — only the no-Locale overload works
- Filesystem only via the hub File Manager API (`/hub/fileManager`); MCP-tool surface is `list_files` / `read_file` / `write_file` / `delete_file`

Use `atomicState` for thread-safe persistence, `state` for UI/counters. Compare device IDs as strings (`.toString()`).

**Comments**: only when the WHY is non-obvious. No multi-paragraph docblocks. Don't reference the current PR/issue/caller.

## Tool design rules

These rules apply to every MCP tool added or renamed. Cached MCP clients refresh their tool list on update — no deprecation aliases are shipped.

### Tool naming

- **Universal service prefix.** Every MCP tool name begins with `hub_`. Anthropic's verified guidance: prefix-namespacing tools by service has "non-trivial effects on our tool-use evaluations" — *writing-tools-for-agents* (2025-09-11). Example: `hub_list_devices`, `hub_create_room`, `hub_call_device_command`.
- **Verb-noun order.** After the `hub_` prefix, the name reads verb-noun. Verbs are drawn from the strict vocabulary table below. Nouns are the most natural English for the entity in context (drop redundant qualifiers — e.g., `rule` is preferred over `rm_rule` where context disambiguates).
- **Gateway verbs — `manage_` (write-bearing) and `read_` (pure-read).** A gateway's verb encodes whether it can mutate. **`manage_`** names any gateway that contains at least one write tool, whether mixed read+write or write-only (e.g., `hub_manage_rooms`, `hub_manage_code`, `hub_manage_destructive_ops`). **`read_`** names a gateway whose every sub-tool is read-only (e.g., `hub_read_apps_code`, `hub_read_diagnostics`). This lets an AI consumer enable the `hub_read_*` gateways for safe read-only access while gating every write-bearing `hub_manage_*` gateway behind a write permission. `manage_` MAY also be used by a **flat-only tool** — one that never sits behind any gateway and is always kept top-level on `tools/list` — even though it is not itself a gateway. The canonical case is a flat tool that action-dispatches a small set (≤4) of verbs on a single noun (current example: `hub_manage_virtual_device` with `action: "create"/"delete"`). Don't introduce more flat `manage_` tools without explicit maintainer sign-off. A gateway `read_` prefix is distinct from the file-manager **leaf** tools `hub_read_file`/`hub_write_file` (see the verb table) — those keep their own `read`/`write` verbs; the noun (`file` vs a gateway domain) disambiguates.
- **Multi-gateway membership.** A tool MAY appear under more than one gateway when both gateway domains apply.
- **Gateway read/write split.** A read-only tool MUST be reachable from a `hub_read_*` gateway (or be a flat top-level tool) — it may never be unique to a `hub_manage_*` gateway. A `hub_manage_*` gateway MAY be mixed (carry its read tools too, for workflow cohesion) as long as those reads are *also* surfaced in a `hub_read_*` gateway via multi-gateway membership (same tool listed in both; no code duplication). This guarantees an AI consumer can reach every read through a pure-read surface while disabling every write-bearing `manage_` gateway. Pure-read gateways enable cleaner per-gateway disable in LLM client settings and clearer mental models for AI consumers.
- **Hard rename, no aliases.** Non-conforming tools are renamed in lockstep when the convention requires it. The expectation: MCP clients refresh their cached tool list on server update. No deprecation aliases are shipped.

### Verb vocabulary

Tools use exactly one verb from the table below. The table is the canonical list — `write` (file-manager leaf tools), `report` (locked to one tool), and `reboot`/`shutdown` (destructive-ops domain) are narrow exceptions to the otherwise-general verb vocabulary. `read` is dual-purpose: the file-manager leaf verb (`hub_read_file`) AND the verb for pure-read **gateways** (`hub_read_*`). `manage` is the verb for write-bearing gateways (see Tool naming).

| Verb | Semantic | Folds in |
|---|---|---|
| `list` | Plural read, returns array of summaries | — |
| `get` | Singular read OR diagnostic verdict | `find`, `check`, `validate` |
| `search` | Read with query, returns ranked array | — |
| `test` | Dry-run with no side effects | — |
| `create` | Write that produces a new entity | `install` |
| `update` | Write that mutates one or more existing fields in place (PATCH-like) | `rename` |
| `delete` | Write that destroys an entity (no ID = delete all) | `clear`, `remove` |
| `set` | Write that assigns a value/state (`set_X_<attr>`) or replaces an entity wholesale (`set_X`, PUT-like) | `pause`, `resume`, `enable`, `disable`, `lock`, `unlock`, `mute`, `unmute`, `start`, `stop`, `open`, `close`, `assign`, `unassign` |
| `call` | Invoke a method / service / dispatch / device command | `send`, `dispatch`, `invoke`, `request`, `evaluate`, `run`, `force` |
| `manage` | Action-dispatch gateway that contains writes (mixed read+write or write-only), plus flat-only top-level tools (see Tool naming) | — |
| `restore` | Re-apply a prior backup | — |
| `import` / `export` | Round-trip serialization counterpart | — |
| `clone` | Duplicate an existing entity | — |
| `read` / `write` | File-manager **leaf** tools (`hub_read_file`, `hub_write_file`) — narrow exception. `read` is ALSO the verb for pure-read **gateways** (`hub_read_*`, every sub-tool read-only); the noun disambiguates a gateway domain from the `file` leaf | — |
| `report` | LOCKED to `hub_report_issue` only | — |
| `reboot` / `shutdown` | EXCEPTIONS — destructive-ops gateway only | — |

**Don't introduce new verbs without a justification stronger than "I felt like it."** If an existing verb fits, use it. The `set` vs `update` distinction is intentional: `set_X_<attr>` assigns one specific value or state; `set_X` replaces wholesale; `update_X` mutates a subset of fields in place. Author judgement applies per-tool.

Rule-engine action subtypes (`cancelTimers`, `cancelDelay`, etc. inside `custom_*` and `hub_set_rule` arguments) are separate from MCP tool names and not constrained by this vocabulary.

### Parameter naming

Name parameters unambiguously. `device_id`, not `id`. `user_id`, not `user`. Where the parameter takes a complex value, name the parameter after what it semantically IS, not how it's wired. Anthropic's verified guidance: *"instead of a parameter named user, try a parameter named user_id"* — *writing-tools-for-agents*.

### Tool consolidation guidelines

1. **Verb-pair tools.** Two tools doing opposite state mutations on the same noun (enable/disable, pause/resume, lock/unlock, mute/unmute, start/stop, open/close) → merge into a single `set_<noun>_<attribute>` tool. Name the merged tool after the boolean/enum attribute being toggled, NOT after one of the two verbs (so `hub_set_rule_paused`, not `hub_set_rule_state` — the latter reads as a generic edit). Use boolean when binary; enum only when 3+ states or values aren't naturally true/false. Concrete: `pause_rm_rule` + `resume_rm_rule` → `hub_set_rule_paused`.
2. **Flat multi-action tools.** A flat tool with an action enum is allowed ONLY when (a) actions share a noun, (b) action set is ≤4 verbs, and (c) a full `manage_` gateway would be overkill. Outside that, prefer separate tools or a real gateway. Concrete: `hub_manage_virtual_device` with `action: "create"/"delete"` fits.
3. **Tools always called in sequence.** If callers always invoke A then B with no useful intermediate point, merge them. Anthropic's verified guidance: `get_customer_context` over three separate lookups; `schedule_event` over `list_users` + `list_events` + `create_event` — *writing-tools-for-agents*.
4. **Overlapping noun + return shape.** Tools that only differ in a filter or projection should merge with an optional parameter. Recent example: PR #208 proposes a 103→95 tool reduction along these lines.
5. **Single-action edge verbs.** Don't add a new verb for a single-action tool when an existing verb fits. `clear`/`remove` fold into `delete`; `rename` into `update`; `run`/`force` into `call`; `install` into `create`.
6. **Anti-consolidation guardrails.** Don't merge tools whose error modes, safety gates, or payload shapes are fundamentally different (a write tool that demands `confirm`+backup with a no-confirm read tool). Don't merge a time-critical tool with one whose schema would inflate context on every call. When in doubt, leave them separate.

### Annotations — all four hints, every new tool

Every new MCP tool MUST set all four annotation hints explicitly:

- `readOnlyHint` — true if no environment modification.
- `destructiveHint` — true if the change is destructive / irreversible.
- `idempotentHint` — true if safe to retry with identical args.
- `openWorldHint` — true if the tool interacts with external entities.

Defaults for unannotated tools are deliberately cautious (non-read-only, potentially destructive, non-idempotent, open-world). Explicit annotations are safer than implicit defaults. Source: MCP blog *Tool Annotations as Risk Vocabulary* (2026-03-16); baseline already shipped via PR #202 (merged 2026-05-19).

**Annotations are UX/risk hints, NOT a security boundary.** The MCP blog is explicit: *"They don't make the model resist prompt injection… annotations alone are not a control."* Safety in this project still requires the universal Read / Write master gates and `confirm` parameter checks. Annotations are advisory metadata to the client, nothing more. The annotation is the *declaration* of a tool's read/write nature; the masters are the *enforcement* of it (see Permission model below).

### Permission model

Every MCP tool is gated. The layers, from broadest to narrowest:

- **Two universal masters — Read and Write (both default ON).** `enableRead` exposes every read-only / non-destructive tool (those in `getReadOnlyToolNames()`); `enableWrite` exposes every other (state-changing) tool. Only an explicit `== false` hides/blocks a class — null/unset means ON. Enforcement is **central**: a single classification-driven gate at the top of `executeTool()` (the dispatch chokepoint) rejects a blocked call ("Read tools are disabled…" / "Write tools are disabled…"). Gateway *names* are not classified here — they re-enter `executeTool()` per sub-tool, so each sub-tool is gated on re-entry. `getReadOnlyToolNames()` is the read/write partition; every leaf tool falls under exactly one master (guarded by a completeness spec). The annotation `readOnlyHint` is the per-tool *declaration*; the masters are the *enforcement*.
- **Destructive confirmation (orthogonal to the masters).** The destructive/sensitive write tools additionally call `requireDestructiveConfirm(confirm)` — `confirm=true` plus a hub backup within 24h. This is the same set that required it before, now independent of any toggle (the Write master gates them centrally first).
- **Developer Mode + Custom Rule Engine — additional layered gates.** `enableDeveloperMode` gates self-admin (`hub_update_mcp_settings`) and is deliberately UI-only to disable (lockout protection). The legacy Custom Rule Engine toggle drives `getCustomEngineMode()`: `full` (engine ON, all `custom_*` shown) / `readonly` (engine OFF + Read master ON: read `custom_*` shown, write `custom_*` hidden) / `off` (engine OFF + Read master OFF: all `custom_*` hidden).
- **Gateway mode — catalog *shape*, not a permission.** `useGateways` folds leaf tools under `hub_read_*` / `hub_manage_*` gateways vs. a flat `tools/list`; it changes how tools are surfaced, not whether they're allowed. The `hub_read_*` (pure-read) / `hub_manage_*` (write-bearing) split (see Tool naming) still holds — it lets a client enable read gateways while disabling write ones, complementing the Read/Write masters.
- **#114 advanced per-tool / per-gateway overrides (deny-only).** `disabled_tools` / `disabled_gateways` (set under *Advanced: Per-tool Overrides*) feed `getEffectiveDisabledTools()` → `getHiddenToolNames()`. They apply *below* the masters — they can only turn things OFF, never re-enable. A disabled tool disappears from `tools/list` and `hub_search_tools` everywhere it appears (including a tool shared across gateways, and tools inside a disabled gateway), and a cached call returns a **distinct** error: "…is disabled in Advanced settings (Per-tool Overrides)…". It stays documented in `hub_get_tool_guide`.

`getHiddenToolNames()` is the single source of truth consumed by both `getToolDefinitions()` (catalog) and `toolSearchTools()` (search corpus), so the two surfaces cannot drift.

### Tool descriptions

- **First line:** concise summary of what the tool does. Tool descriptions are what the LLM reads when deciding whether to call this tool; the first line is what gets noticed.
- **Body:** usage guidance — when/how to use the tool, performance notes, pagination guidance, behavioural expectations.
- **Write tools:** safety warning + mandatory pre-flight checklist directly in the description (already required; reaffirmed).
- **Make implicit context explicit.** Terminology quirks, query-language constraints, resource relationships — write them in. Anthropic: *"Even small refinements to tool descriptions can yield dramatic improvements."*
- **Semantic names beat opaque UUIDs.** Where a tool returns an identifier, prefer human-meaningful names over raw UUIDs; if a technical ID is needed for chaining, expose both name AND id. Anthropic: *"resolving arbitrary alphanumeric UUIDs to more semantically meaningful and interpretable language… significantly improves Claude's precision in retrieval tasks by reducing hallucinations."*
- **Length: match it to complexity; cut fluff, not substance.** ~3-4 sentences for a complex tool (Anthropic floor), 1-2 for a simple one (OpenAI). Tool defs are re-sent to the model every turn, so trim redundancy and padding — but NEVER drop load-bearing content (purpose, scope, critical parameter formats, safety, behavioural quirks) to save bytes. Sources: Anthropic *define-tools* (*"at least 3-4 sentences… more if complex"*); OpenAI *GPT-5.2 guide* (*"1-2 sentences for what they do and when to use them"*, 2025-12-11).
- **`get_tool_guide` is a supplement, not an offload target.** Many agents won't call it even when told to, so anything an agent NEEDS to use the tool correctly stays in the description / parameter descriptions. Route only DEEP reference (exhaustive capability tables, long worked examples) into `get_tool_guide` / `[[FLAT_TRIM]]`.
- **Examples: not stuffed in the description body.** Put a concrete format example in the relevant *parameter's* description (e.g. `"…e.g. 2026-02-04T14:00"`); route full worked examples to `get_tool_guide`. (`input_examples` is the spec-native home but is incompatible with the Tool Search Tool, so gateway/deferred tools keep example content in description/param text.) Sources: Anthropic *advanced-tool-use* (2025-11-24); OpenAI *GPT-4.1 / GPT-5.2 guides*.
- **Deferred/gateway tools are also a retrieval surface.** The `tools/list` name + description + parameter descriptions feed the BM25 tool-search index, so include semantic keywords matching how users phrase the task — not just mechanics. Source: Anthropic *tool-search-tool* (matches this repo's gateway / `hub_search_tools` retrieval model).

### Schema design

- `inputSchema` root is `type: "object"` with `properties` (existing rule; reaffirmed).
- Use `enum` to constrain allowed values where there is a fixed set. Reduces invalid-arg errors and gives the LLM a usable hint.
- The `required` array is present only when there ARE required parameters; absent for fully-optional tools (existing rule; reaffirmed).
- **`outputSchema` — add one to every new tool, for consistency (a project convention, NOT a spec requirement).** The MCP spec marks `outputSchema` **optional**; its only normative `MUST` is conditional — *if* a server publishes one, its structured results must conform (clients SHOULD validate). We add one everywhere anyway so the tool surface is uniform and LLM clients can see each tool's return shape up front — genuinely useful for the many tools whose output varies by mode/condition, marginal for fixed-shape ones. **Do NOT add `structuredContent`:** this server returns tool results as a text block only, so `outputSchema` here is a discovery/documentation aid, not a wire-format contract to fulfill. Model the *success* shape; for multi-mode tools use a superset object (union of possible keys, none `required`). Strip it in the size-constrained flat `tools/list` (it is published in gateway mode + the gateway catalog). Source: MCP spec — `/server/tools` (`outputSchema` is Optional).
- **Forward-looking.** The MCP specification next-revision Release Candidate (RC locked 2026-05-21, targets 2026-07-28 publication) adds `_meta` on every request and `ttlMs`/`cacheScope` cache hints on list responses. Not required today; flagged here so future tool work doesn't re-discover it.

### Error contracts

- **Validation errors** (caller-recoverable, bad args): throw `IllegalArgumentException`. Caught by `handleToolsCall` and mapped to JSON-RPC `-32602`. Existing pattern; reaffirmed.
- **Runtime errors** (operation tried and failed for non-arg reasons): return `[success: false, error: <human-readable>, note: <actionable guidance>]`. Don't throw — the AI needs a structured error.
- **`isError: true` envelope** for tool-execution errors per MCP spec 2025-06-18. Already adopted in v0.7.7+ (see SKILL.md § Version Management for the adoption note).
- **Specific, actionable, recovery-oriented error text.** Tell the model how to recover. Anthropic recommends steering truncation errors toward recovery strategies like *"many small and targeted searches instead of a single, broad search."*
- **Point error/recovery text at `get_tool_guide`.** When a tool has a `get_tool_guide` section, its `note` / error text SHOULD reference it (e.g. *"…see `hub_get_tool_guide(section='X')` for the full reference"*). Many agents skip the guide otherwise — an in-context error pointer is an extra nudge to actually consult it, and it lets the live description stay lean without losing the deep reference.

### Pagination & response-size discipline

- Tools that can return long lists MUST support the project's universal cursor convention (`cursor` / `nextCursor`), unless the response naturally fits within the 120KB `tools/call` cap. Current cursor-paginated tools are listed in `TOOL_GUIDE.md`.
- For tools where a "concise" vs "detailed" payload distinction is useful, support a `response_format` enum or equivalent. Anthropic's worked example reduced a response from 206 tokens to 72 just by adding the enum.
- The universal 120KB response-size guard at `handleMcpRequest` remains the backstop — it is NOT a substitute for in-tool filtering, pagination, or projection.

### Testing & eval

- Every new MCP tool ships with BOTH a direct-call (unit) test AND a dispatch-envelope (integration) test under `src/test/groovy/` (existing CONTRIBUTING.md rule; reaffirmed).
- **e2e coverage is mandatory for changes and bug fixes, not just new tools.** Any change to tool behaviour, dispatch/gateway routing, the transport/protocol layer, or any bug fix that a live-hub MCP client could observe MUST add or update a `tests/e2e_test.py` scenario in the same PR, alongside the Spock unit/integration tests. The Spock suite proves the logic in isolation against the `hubitat_ci` harness; the `e2e` job proves it against a real hub through the cloud MCP endpoint. A green Spock matrix is necessary but not sufficient — if a client could see the difference, e2e must cover it. When adding or renaming a tool, keep `tests/e2e_test.py` and the e2e deploy scripts under `.github/scripts/` in lockstep with the tool surface — stale tool names there are a silent e2e-only break.
- If the tool's behaviour affects live-hub flows, add a `tests/BAT-v2.md` scenario in the same PR.
- Track tool errors during eval. High invalid-parameter rates usually indicate the description or examples are the bug, not the model. Anthropic's example: Claude was appending `2025` to web-search queries until the description was fixed.

### Sources

All rules above cite verified sources, re-checked on 2026-05-26.

- Anthropic — *Writing effective tools for agents* — anthropic.com/engineering/writing-tools-for-agents (2025-09-11).
- Anthropic — *Code execution with MCP* — anthropic.com/engineering/code-execution-with-mcp (2025-11-04).
- Anthropic — *Advanced tool use* — anthropic.com/engineering/advanced-tool-use (2025-11-24).
- MCP Specification 2025-06-18 — `/server/tools` and `/client/elicitation` — modelcontextprotocol.io/specification/2025-06-18/.
- MCP Blog — *Tool Annotations as Risk Vocabulary* — blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/ (2026-03-16).
- OpenAI — *Function calling guide* — developers.openai.com/api/docs/guides/function-calling.
- Cloudflare — *Code Mode* — blog.cloudflare.com/code-mode-mcp/ (2026-02-20).
- FastMCP — gofastmcp.com/servers/tools (v3 docs). **Peer reference only — ideas worth considering, not enforced rules. The project is Groovy on the Hubitat sandbox; FastMCP is Python. Not every pattern transfers.**
- MCP next-revision Release Candidate (RC locked 2026-05-21, targets 2026-07-28 publication) — blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/. Forward-looking direction; not adopted yet.

PR #202 (merged 2026-05-19) established the annotation-hints baseline on every shipped tool.

## The custom MCP rule engine is legacy

`hubitat-mcp-rule.groovy` (the MCP child app, surfaced through the `custom_*` tools) is **legacy**. It still ships and still gets bug fixes, but it is **closed to new feature work** — Hubitat's native Rule Machine is the supported path now and exposes equivalent functionality through `hub_set_rule` (in the `hub_manage_rule_machine` gateway) plus `hub_set_native_app` / `hub_delete_native_app` and the rest of the `hub_manage_native_rules_and_apps` group. New rule-related capabilities should land on the parent app's native-RM tools, not on the child app. If a feature request lands on the child app, propose it for the native side instead.

## Library modules (`#include`) — the modularization path (issue #209)

The server is being split out of the single `hubitat-mcp-server.groovy` monolith into Groovy `#include` libraries under `libraries/`. `#include` is a **textual paste**: the library body is inlined into the app's compiled class at parse time (no method boundary, no separate runtime), so library methods, `state`/`atomicState`, and string-literal `subscribe`/`schedule` handlers all resolve as if written in the app. Read this before adding or moving code into a library.

### What moves to the library vs stays in the app

When you extract a tool group, **both** its tool **definitions** and its **implementation methods** (plus any domain-private helpers) move into the library. The definitions go in a `_getAllToolDefinitions_part<Name>()` chunk method that `getAllToolDefinitions()` in the main app concatenates. What **stays in `hubitat-mcp-server.groovy`**: the **gateway config** entries in `getGatewayConfig()` (gateway membership is cross-domain), the `executeTool()` **dispatch cases** (one line per tool), and the `getReadOnlyToolNames()` membership. So the per-tool split is: **library** = definition + implementation (+ domain-private helpers); **main** = gateway membership + the one-line dispatch case + readonly membership.

`tests/sandbox_lint.py` resolves the `#include`'d library modules before its TOOL_COUNT / read-write-split checks (so library-contributed defs are counted), and the Spock harness re-inlines the library so its specs compile unchanged. Shared **generic** helpers (`hubInternalGet`/`hubInternalGetRaw`/the `hubInternal*` family, `mcpLog`, `requireDestructiveConfirm`, `_paginateList`, `formatTimestamp`, `getHubSecurityCookie`, `findDevice`, etc.) stay in main; libraries call them (resolved after the textual inline). Do NOT cross-`#include` one library from another.

New tools MAY be authored **library-first**: definition + impl in a library from day one, with only the gateway entry + dispatch case added to the main file (the bundle-management tools — `McpBundlesLib` — were built this way).

### Library granularity + helper placement

- **Group by cohesion + shared domain helpers, not one library per tiny group.** A library is a cohesive *domain* (e.g. all room tools, all bundle tools). Tools that share a domain-specific helper belong in the SAME library so the helper isn't duplicated or cross-`#include`d. Multiple small tool groups MAY share one library when they're cohesive.
- **Helper placement:** a **generic** helper used across many domains (`hubInternalGet`, `mcpLog`, `requireDestructiveConfirm`, `_paginateList`, `formatTimestamp`, …) stays in **main** so any library can call it (textual paste means library code calls main methods, and vice versa, within the one compiled class). A helper used by only **one** library's tools lives **in that library** (e.g. `_parseBundleContent` in `McpBundlesLib`). Place each helper where it minimizes confusion; don't relocate a shared helper into a library that makes other callers reach across modules.

### HPM delivery — bundles, not `libraries[]`

Libraries are delivered to real hubs via an HPM **bundle** (a `.zip`), declared in `packageManifest.json` `bundles[]`. Do NOT use the manifest `libraries[]` array — HPM silently drops it on update (verified upstream). One bundle, `bundles/mcp-libraries.zip`, carries every library; it is built deterministically by `tools/build-bundle.py` and committed. The committed zip is the artifact real users (and the CI e2e job) install, so it must never drift from `libraries/*.groovy`. Three things keep it honest: the `.githooks/pre-commit` hook **auto-rebuilds and stages** the zip whenever a library is committed (enable once per clone with `git config core.hooksPath .githooks`); the `sandbox-lint` workflow re-runs the builder and fails if `bundles/` drifts; and the `hub-e2e` job re-verifies freshness before install so e2e always tests the real bundle. With the hook on, the rebuild is automatic — you only rebuild by hand (`python tools/build-bundle.py`) if you skip the hook.

### BP20 — library file hygiene (avoids a Hubitat parser `Internal error`)

- The `library(...)` declaration MUST be the **first line** of the file. **Zero file-scope commentary before it.**
- Keep comments **inside method bodies**. Avoid file-scope `/* */` / `/** */` blocks and any file-scope comment containing the literal text `library(` or `/* */` — these can fail the hub's parser on save.
- Use **string-literal** handler names for `subscribe`/`schedule` (never bare identifiers).
- Do NOT move `preferences {}`, `mappings {}`, or the RM wizard-walker closures into a library (root-level DSL / unverified closure binding under `#include`).

### Adding (or extracting into) a library — checklist

1. Create `libraries/<name>.groovy` starting with `library(name: "<Name>", namespace: "mcp", author: "...", description: "...")`, then the impl methods.
2. Add `#include mcp.<Name>` near the top of `hubitat-mcp-server.groovy`.
3. Register it in `getPackageLibraryRegistry()` (the `hub_update_package` dev-deploy leg).
4. Add it to `LIBS` in `tools/build-bundle.py`.
5. Rebuild + **commit** `bundles/*.zip`. With the `.githooks` pre-commit hook enabled (`git config core.hooksPath .githooks`) this is automatic on commit; otherwise run `python tools/build-bundle.py` yourself. The `sandbox-lint` drift gate enforces it either way.
6. The Spock harness auto-resolves `#include` via `src/test/groovy/support/IncludeResolver.groovy` — no harness edit needed. Add an `IncludeResolverSpec` case asserting the real library resolves.
7. Keep the gateway membership, the `executeTool` dispatch case, and `getReadOnlyToolNames()` entry in main; the tool **definitions** move to the library next to the impl (see "What moves to the library vs stays in the app" above). `check_include_library_lockstep` (sandbox_lint) verifies the `#include` ⇄ library file ⇄ `LIBS` ⇄ registry quartet stays in sync.

Steps 3 + 4 must stay in lockstep (an unmapped `#include` aborts `hub_update_package`; a missing `LIBS` entry ships a stale bundle). When updating a library on a hub by hand during dev, **update the existing library, don't install a second copy** — a duplicate name+namespace creates a second library at a new id and `#include` binds to only one (HPM's normal install flow avoids this).

## PR workflow

Use `.github/pull_request_template.md` — keep every section.

- **Title prefix** matches the ticked `## Type of change` box: `feat:` / `fix:` / `chore:` / `refactor:` / `docs:` / `test:` / `ci:` (`build:` reserved for Dependabot).
- **`## Release Notes`** drives `packageManifest.json` `releaseNotes` (what HPM users see in the update prompt) via `.github/scripts/release_bump.py`. Strongly recommended; write user-facing bullets. If you skip the section the PR title is the fallback. The bot warns on a present-but-unbulleted section.
- **Open as draft** (`gh pr create --draft`); the maintainer flips to ready-for-review.
- **Verify the PR body has both required headings** before claiming the PR is ready (`<N>` = PR number):

  ```bash
  gh pr view <N> --json body --jq '.body' | grep -iE "^#+\s*(Type of change|Release Notes)\s*:?\s*$"
  ```

  Lenient match (case-insensitive, any heading level, optional trailing colon) — same shape `release_bump.py` parses with. Run on rebase/edit too; pre-#146 PRs may not satisfy the template.

## Boy Scout rule

**"Always leave the code better than you found it."**

When you touch a file, you fix the adjacent small stuff you notice — broken comments, stale references, dead pointers, weakly-asserting tests, mis-named variables, redundant code. Not because the PR title told you to, but because you were already in the file and you saw it. The accumulating effect over many PRs is what keeps the codebase from rotting. Skipping the small fix and walking past it is the antipattern, even when it's "not what the PR is about."

This applies to anything within the file or function you're already editing, and to anything one or two file-jumps away that the fix you're making clearly implicates (e.g. a stale comment that references the function you just rewrote, a test assertion that pins the old behaviour, a pointer that points at something you renamed).

## Scope discipline

Scope discipline is the partner rule to Boy Scout — they're different things. Boy Scout governs the small adjacent fixes you make without asking. Scope discipline governs the larger ones you don't.

- **Small or medium fix surfaced during the work** — Boy Scout it. Roll it into the current PR. The PR's commit messages document the extra fix; the PR body's Changes section names it.
- **Large fix** (touches a broad surface, a separate subsystem, or would substantially expand the PR's review surface) — **ASK the user**. Present the option as: roll it in / open a follow-up PR / file a tracking issue / drop it. **The user decides scope, not the AI.** Example: while fixing one dead `.md` pointer you notice the entire rules engine has stale comments referencing renamed functions — that's a wholly separate sweep, ask before rolling in. Another example: the bug fix you came to make requires touching a build script, and the build script has a different conceptual scope (CI vs runtime) — ask.
- **Banned phrasing** — "deferred for follow-up", "out of scope for this fix", "tracking issue", "separate concern", or any variant that defers a real problem the AI just discovered, without the user explicitly saying so. The deflection IS the antipattern; it shifts the cost of remembering onto the maintainer and lets known problems pile up.

When in doubt, treat the problem as in-scope, name it, and surface the trade-off to the user. The user can always shrink the scope; only the user can grow it.

## Boundaries

**🚫 Never edit** — `pr_guard.py` (CI: `.github/workflows/pr-guard.yml`) flags any of these on every PR:
- Version strings in tracked locations (server header comment, `currentVersion()`, rule header, manifest `version`)
- `packageManifest.json` `releaseNotes` or `dateReleased`
- `README.md` `## Version History` section
- `CHANGELOG.md`

All bookkeeping is bot-only. Communicate user-facing changes through your PR's `## Release Notes` section; the post-merge bot handles every file. See [docs/release-automation-design.md](docs/release-automation-design.md).

**⚠️ Ask first** — destructive ops (force-push, branch deletion, hook bypass with `--no-verify` / `--no-gpg-sign`), edits to other contributors' PR descriptions, anything that touches Z-Wave or Zigbee radios on a live test hub.

**✅ Default-OK** — local edits, running tests, opening draft PRs, updating your own PR's description, refactors and bug fixes covered by tests.

## Reference

- [`.github/pull_request_template.md`](.github/pull_request_template.md) — PR body structure (source of truth)
- [`.gemini/styleguide.md`](.gemini/styleguide.md) — Gemini Code Assist's review checks
- [`docs/release-automation-design.md`](docs/release-automation-design.md) — full release-pipeline design and the bookkeeping rule
- [`docs/testing.md`](docs/testing.md) — Spock + hubitat_ci harness, dispatch interception cheat sheet
