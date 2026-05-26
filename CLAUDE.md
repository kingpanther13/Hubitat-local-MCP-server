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

These rules apply to every MCP tool added or renamed from PR1 onward. PR3 (this PR) establishes the rules; PR1 (forthcoming) renames existing tools to match. Cached MCP clients refresh their tool list on update — no deprecation aliases are shipped.

### Tool naming

- **Universal service prefix.** Every MCP tool name begins with `hub_`. Anthropic's verified guidance: prefix-namespacing tools by service has "non-trivial effects on our tool-use evaluations" — *writing-tools-for-agents* (2025-09-11). Example: `hub_list_devices`, `hub_create_room`, `hub_call_device_command`.
- **Verb-noun order.** After the `hub_` prefix, the name reads verb-noun. Verbs are drawn from the strict vocabulary table below. Nouns are the most natural English for the entity in context (drop redundant qualifiers — e.g., `rule` is preferred over `rm_rule` where context disambiguates).
- **Gateways.** `manage_` is reserved for gateway tools (e.g., `hub_manage_rooms`, `hub_manage_logs`). One narrow exception: a flat tool that exposes a small set (≤4) of action-dispatched verbs on a single noun may use `manage_` (current example: `hub_manage_virtual_device` with `action: "create"/"delete"`). Don't introduce more flat-multi-action tools without explicit maintainer sign-off.
- **Multi-gateway membership.** A tool MAY appear under more than one gateway when both gateway domains apply.
- **Gateway read/write split.** Gateways SHOULD be read-only OR write-only where the domain permits. Mixed-mode gateways are accepted only when splitting genuinely doesn't fit the domain. Pure-mode gateways enable cleaner per-gateway disable in LLM client settings and clearer mental models for AI consumers.
- **Hard rename, no aliases.** PR1 will rename every non-conforming tool in lockstep. The expectation: MCP clients refresh their cached tool list on server update. No deprecation aliases are shipped.

### Verb vocabulary

Tools use exactly one verb from the table below. The table is the canonical list — `read`/`write` (file-manager domain), `report` (locked to one tool), and `reboot`/`shutdown` (destructive-ops domain) are narrow exceptions to the otherwise-general verb vocabulary.

| Verb | Semantic | Folds in |
|---|---|---|
| `list` | Plural read, returns array of summaries | — |
| `get` | Singular read OR diagnostic verdict | `find`, `check`, `validate` |
| `search` | Read with query, returns ranked array | — |
| `test` | Dry-run with no side effects | — |
| `create` | Write that produces a new entity | `install` |
| `update` | Write that mutates one or more existing fields in place (PATCH-like) | `rename` |
| `delete` | Write that destroys an entity (no ID = delete all) | `clear`, `remove` |
| `set` | Write that assigns a value/state (`set_X_<attr>`) or replaces an entity wholesale (`set_X`, PUT-like) | `pause`, `resume`, `enable`, `disable`, `assign`, `unassign` |
| `call` | Invoke a method / service / dispatch / device command | `send`, `dispatch`, `invoke`, `request`, `evaluate`, `run`, `force` |
| `manage` | Action-dispatch — gateways + narrow flat-multi-action exception | — |
| `restore` | Re-apply a prior backup | — |
| `import` / `export` | Round-trip serialization counterpart | — |
| `clone` | Duplicate an existing entity | — |
| `read` / `write` | File-manager domain only — narrow exception | — |
| `report` | LOCKED to `hub_report_issue` only | — |
| `reboot` / `shutdown` | EXCEPTIONS — destructive-ops gateway only | — |

**Don't introduce new verbs without a justification stronger than "I felt like it."** If an existing verb fits, use it. The `set` vs `update` distinction is intentional: `set_X_<attr>` assigns one specific value or state; `set_X` replaces wholesale; `update_X` mutates a subset of fields in place. Author judgement applies per-tool.

Rule-engine action subtypes (`cancelTimers`, `cancelDelay`, etc. inside `custom_*` and `create_native_app` arguments) are separate from MCP tool names and not constrained by this vocabulary.

### Parameter naming

Name parameters unambiguously. `device_id`, not `id`. `user_id`, not `user`. Where the parameter takes a complex value, name the parameter after what it semantically IS, not how it's wired. Anthropic's verified guidance: *"instead of a parameter named user, try a parameter named user_id"* — *writing-tools-for-agents*.

### Tool consolidation guidelines

1. **Verb-pair tools.** Two tools doing opposite state mutations on the same noun (enable/disable, pause/resume, lock/unlock, mute/unmute, start/stop, open/close) → merge into a single `set_<noun>_<attribute>` tool. Name the merged tool after the boolean/enum attribute being toggled, NOT after one of the two verbs (so `hub_set_rule_paused`, not `hub_set_rule_state` — the latter reads as a generic edit). Use boolean when binary; enum only when 3+ states or values aren't naturally true/false. Concrete: `pause_rm_rule` + `resume_rm_rule` → `hub_set_rule_paused`.
2. **Flat multi-action tools.** A flat tool with an action enum is allowed ONLY when (a) actions share a noun, (b) action set is ≤4 verbs, and (c) a full `manage_` gateway would be overkill. Outside that, prefer separate tools or a real gateway. Concrete: `hub_manage_virtual_device` with `action: "create"/"delete"` fits.
3. **Tools always called in sequence.** If callers always invoke A then B with no useful intermediate point, merge them. Anthropic's verified guidance: `get_customer_context` over three separate lookups; `schedule_event` over `list_users` + `list_events` + `create_event` — *writing-tools-for-agents*.
4. **Overlapping noun + return shape.** Tools that only differ in a filter or projection should merge with an optional parameter. Recent example: PR #208 proposes a 103→95 tool reduction along these lines.
5. **Single-action edge verbs.** Don't add a new verb for a single-action tool when an existing verb fits. `clear`/`remove` fold into `delete`; `rename` into `update`; `run`/`force` into `call`; `install` into `create`.
6. **Anti-consolidation guardrails.** Don't merge tools whose error modes, safety gates, or payload shapes are fundamentally different (a Hub Admin Write tool with a no-gate read tool). Don't merge a time-critical tool with one whose schema would inflate context on every call. When in doubt, leave them separate.

### Annotations — all four hints, every new tool

Every new MCP tool MUST set all four annotation hints explicitly:

- `readOnlyHint` — true if no environment modification.
- `destructiveHint` — true if the change is destructive / irreversible.
- `idempotentHint` — true if safe to retry with identical args.
- `openWorldHint` — true if the tool interacts with external entities.

Defaults for unannotated tools are deliberately cautious (non-read-only, potentially destructive, non-idempotent, open-world). Explicit annotations are safer than implicit defaults. Source: MCP blog *Tool Annotations as Risk Vocabulary* (2026-03-16); baseline already shipped via PR #202 (merged 2026-05-19).

**Annotations are UX/risk hints, NOT a security boundary.** The MCP blog is explicit: *"They don't make the model resist prompt injection… annotations alone are not a control."* Safety in this project still requires the existing Hub Admin Read / Hub Admin Write gates and `confirm` parameter checks. Annotations are advisory metadata to the client, nothing more.

### Tool descriptions

- **First line:** concise summary of what the tool does. Tool descriptions are what the LLM reads when deciding whether to call this tool; the first line is what gets noticed.
- **Body:** usage guidance — when/how to use the tool, performance notes, pagination guidance, behavioural expectations.
- **Write tools:** safety warning + mandatory pre-flight checklist directly in the description (already required; reaffirmed).
- **Make implicit context explicit.** Terminology quirks, query-language constraints, resource relationships — write them in. Anthropic: *"Even small refinements to tool descriptions can yield dramatic improvements."*
- **Semantic names beat opaque UUIDs.** Where a tool returns an identifier, prefer human-meaningful names over raw UUIDs; if a technical ID is needed for chaining, expose both name AND id. Anthropic: *"resolving arbitrary alphanumeric UUIDs to more semantically meaningful and interpretable language… significantly improves Claude's precision in retrieval tasks by reducing hallucinations."*

### Schema design

- `inputSchema` root is `type: "object"` with `properties` (existing rule; reaffirmed).
- Use `enum` to constrain allowed values where there is a fixed set. Reduces invalid-arg errors and gives the LLM a usable hint.
- The `required` array is present only when there ARE required parameters; absent for fully-optional tools (existing rule; reaffirmed).
- **Add `outputSchema` for any new tool that returns structured content.** Servers MUST conform to a published `outputSchema`; clients SHOULD validate. Source: MCP spec 2025-06-18 — `/server/tools`.
- **Forward-looking.** The MCP specification next-revision Release Candidate (RC locked 2026-05-21, targets 2026-07-28 publication) adds `_meta` on every request and `ttlMs`/`cacheScope` cache hints on list responses. PR3 does not require adoption; flagged here so future tool work doesn't re-discover it.

### Error contracts

- **Validation errors** (caller-recoverable, bad args): throw `IllegalArgumentException`. Caught by `handleToolsCall` and mapped to JSON-RPC `-32602`. Existing pattern; reaffirmed.
- **Runtime errors** (operation tried and failed for non-arg reasons): return `[success: false, error: <human-readable>, note: <actionable guidance>]`. Don't throw — the AI needs a structured error.
- **`isError: true` envelope** for tool-execution errors per MCP spec 2025-06-18. Already adopted in v0.7.7+ (see SKILL.md § Version Management for the adoption note).
- **Specific, actionable, recovery-oriented error text.** Tell the model how to recover. Anthropic recommends steering truncation errors toward recovery strategies like *"many small and targeted searches instead of a single, broad search."*

### Pagination & response-size discipline

- Tools that can return long lists MUST support the project's universal cursor convention (`cursor` / `nextCursor`), unless the response naturally fits within the 120KB `tools/call` cap. Current cursor-paginated tools are listed in `TOOL_GUIDE.md`.
- For tools where a "concise" vs "detailed" payload distinction is useful, support a `response_format` enum or equivalent. Anthropic's worked example reduced a response from 206 tokens to 72 just by adding the enum.
- The universal 120KB response-size guard at `handleMcpRequest` remains the backstop — it is NOT a substitute for in-tool filtering, pagination, or projection.

### Testing & eval

- Every new MCP tool ships with BOTH a direct-call (unit) test AND a dispatch-envelope (integration) test under `src/test/groovy/` (existing CONTRIBUTING.md rule; reaffirmed).
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
- MCP next-revision Release Candidate (RC locked 2026-05-21, targets 2026-07-28 publication) — blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/. Forward-looking direction; not adopted in PR3.

PR #202 (merged 2026-05-19) established the annotation-hints baseline on every shipped tool. PR3 codifies the rule going forward. PR1 (forthcoming) will rename existing tools to the new naming convention; PR2 (forthcoming) will audit non-tool conventions.

## The custom MCP rule engine is legacy

`hubitat-mcp-rule.groovy` (the MCP child app, surfaced through the `custom_*` tools) is **legacy**. It still ships and still gets bug fixes, but it is **closed to new feature work** — Hubitat's native Rule Machine is the supported path now and exposes equivalent functionality through `create_native_app` / `update_native_app` / `delete_native_app` and the rest of the `manage_native_rules_and_apps` group. New rule-related capabilities should land on the parent app's native-RM tools, not on the child app. If a feature request lands on the child app, propose it for the native side instead.

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
