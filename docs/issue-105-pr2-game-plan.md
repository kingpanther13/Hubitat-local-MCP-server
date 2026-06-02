# Issue #105 — PR2 game plan

> **Canonical home** of the PR2 (backend / server best-practices audit) game plan, paired with [Issue #105](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/105). The highlights are also mirrored to a comment on #105 for the issue thread; this file is the authoritative version, pickup-able from any computer.

## PR2 game plan — pre-audit and execution strategy

_Generated 2026-06-02 from a multi-agent backend audit: 12 server/infrastructure dimensions, one agent each, every high/medium finding adversarially verified against the source (39 of 40 held; 1 refuted — see Appendix). Implementation does NOT start until PR1b + PR1c merge; PR2 then rebases onto post-#PR1 main. This document captures the audit + plan so it isn't lost in the gap._

### Context & sequencing

- **Umbrella #105 has three workstreams.** PR1 = the *tool* audit (names, verb vocabulary, schemas, gateway read/write splits — **in progress: PR1b + PR1c**). PR2 = **THIS** — everything the server has *outside* the specific tools (backend, transport, protocol, infrastructure). PR3 = docs / AGENTS.md / styleguide — **already merged (#210)**.
- **Roadmap order: PR1 → PR2 → #209 (file split) → done.** PR3 is finished, so PR2 is the active head of the #105 line once PR1b/PR1c land.
- **PR2 is logically independent of PR1.** The audit tagged every finding for `pr1_overlap` — i.e. whether the fix must touch the dispatch / tool-definition region PR1 owns (`getGatewayConfig()`, `handleGateway()`, `getAllToolDefinitions()`, `getToolDefinitions()`, or any tool's `inputSchema`/`description` block). **Zero of the 78 findings overlap.** The only coupling is *physical* — all of this lives in the one 24,920-line `hubitat-mcp-server.groovy`. So PR2 **rebases onto post-PR1 main**; it never depends on PR1's logic. Implementation begins once PR1b + PR1c merge, to avoid churning the same file under PR1's in-flight renames.
- **#209 (monolith → `#include` libraries) is its own issue. PR2 does NOT perform the split.** PR2c does the safe *in-file* cleanups only (dead-code removal, helper extraction, memoization, comment/constant hygiene). The file-split itself is deferred to #209, whose Path-A (`#include`) viability still needs a spike (RM wizard-walker closure binding under textual paste — flagged unverified by @level99). That spike can run in parallel and blocks nothing here. **PR2b's HTTP-client consolidation deliberately cuts a clean `HubHttpClient` seam that #209 can later extract** — PR2 prepares the split rather than competing with it.

### Audit method

12 dimensions, one agent each, reading the cited code regions of `hubitat-mcp-server.groovy` (and `hubitat-mcp-rule.groovy` for the legacy dimension). Every high- and medium-severity finding was handed to a second, adversarial agent told to *refute* it against the real code. Result: **78 findings — 6 high, 34 medium, 38 low**; of the 40 high/medium verified, **39 confirmed/nuanced, 1 refuted**. Full per-finding detail (current behaviour, gap, proposed fix, verification nuance) is in the audit working file; this plan is the actionable distillation.

### PR2 sub-structure (maintainer-approved 2026-06-02)

- **PR2a — Correctness & security.** The 6 high-severity bugs + the tied silent-failure / error-contract findings + the secret-handling findings. Highest review rigour; merges first; should not be held up by polish.
- **PR2b — Robustness & infrastructure hygiene.** Hub HTTP client consolidation, logging/observability, state & concurrency, app lifecycle, MCP protocol conformance.
- **PR2c — Performance & in-file cleanup.** Memoization, redundant serialization, dead code, magic-number/comment hygiene. Defers the file-split to #209.
- **PR2-legacy — Legacy rule child-app (`hubitat-mcp-rule.groovy`).** Two bug-fixes + state-hygiene cleanups (maintainer chose **option C**). Separate file, separate review, under the AGENTS.md "still gets bug fixes" carve-out.

Sub-PRs may be batched at the maintainer's discretion, but PR2a is the one that must stand alone (security + correctness review rigour).

---

### PR2a — Correctness & security

Lead with the six high-severity bugs; the tied medium/low items share the same code paths and review context.

#### High-severity bugs

| # | Finding | Location | What's wrong / fix |
|---|---|---|---|
| 1 | **Outer size-guard under-measures multibyte UTF-8** | `:609-610` (`handleMcpRequest`) | Guard compares **char** length, not UTF-8 **bytes** — only measures bytes when char-length already exceeds 116000. A batch/catalog of multibyte strings (device/room/rule names, log messages) sails past the 124KB cap and the hub silently truncates it. This is the **sole** guard on `tools/list` and batch responses. **Fix:** measure `getBytes("UTF-8").length` whenever `length() > maxResponseSize/3`; short-circuit to char length only when `length()*4 <= maxResponseSize`. Mechanical, no decision. |
| 2 | **`tools/list` catalog has no byte-accurate backstop** | `:704-706` (`handleToolsList`), history comment `:686-699` | The unpaginated catalog delegates its *only* safety net to the #1 guard. **Subsumed by fixing #1** — once the outer guard is byte-accurate, this closes. Latent today (catalog is mostly ASCII); listed so the dependency is explicit. No separate code. |
| 3 | **`get_app_config` returns `type=password` values unredacted** | `:10624-10630` **and** `:10569-10570` (`toolGetAppConfig`) | De-masks secrets the Hubitat UI hides, straight to the client/transcript. **Two paths leak:** the raw `settings` map (gated by `includeSettings`) **and** the structured `inputs[].value` copy (NOT gated — leaks even with `includeSettings=false`). **Fix:** walk `configPage.sections[].input[]`, collect `type=='password'` names, replace those values with a `***redacted***` sentinel (key retained) in **both** paths. See **Q1** for redaction breadth. |
| 4 | **`_rmUpdateAppSettings` reports a hub-rejected write as success** | `:20243-20262` | The central native-RM settings writer (6 call sites) discards the POST status. `hubInternalPostForm` returns a Map with a `status` field and does NOT throw on 4xx; siblings (`_rmClickAppButton:14759`, `_rmSubmitFullPageForm:20221`, `forcedelete:20372`) all guard `resp?.status >= 400` and throw — this one does not. A stale-version-token rejection comes back as `success`. Worst case in the AGENTS.md error model. **Fix:** add the same `status >= 400` guard + value echo; reuse the sibling pattern. |
| 5 | **`saveCapturedState` read-modify-write race on non-atomic `state`** | `:6090-6137` (called from `rule:3551`) | Hubitat runs event handlers concurrently. Two rules firing at once each read their own `state.capturedDeviceStates` snapshot, mutate, write back — last writer wins; one rule's capture (and its eviction) is silently lost. Also defeats the `getMaxCapturedStates` size cap (concurrent inserts each see `size < max`). Backs `restore_state` → **lost/stale capture causes a wrong device restore.** **Fix:** move to `atomicState` with a single read-mutate-write, or serialize through an atomic accessor; enforce the cap inside the atomic section. |
| 6 | **`initialize()` re-subscribes to every hub variable without `unsubscribe()` first** | `:323-327`, `:426-448` | The in-code comment misstates Hubitat semantics: `updated()` does **not** implicitly unsubscribe. Every settings save stacks another duplicate subscription per variable → `handleHubVariableEvent` fires N× → N duplicate `atomicState.variableHistory` writes per event. Classic subscription leak. **Fix:** call `unsubscribe()` before `_subscribeToAllHubVariables()` in `initialize()`; correct the comment. |

#### Tied silent-failure / error-contract findings (medium/low)

| Finding | Location | Fix |
|---|---|---|
| **Batch oversize collapses to one `id:null` -32603** (3 related findings merged) | `:588-618` (`handleMcpRequest` batch path) | Over-cap batch replaces the whole array with a single error whose id is `null` (because `response instanceof Map ? response.id : null` is null for a List) — destroys every per-item result that individually fit, uncorrelatable by the client. **Fix per Q2.** |
| `_hpmFetchManifests` throws `IllegalArgumentException` for runtime (fetch/parse) failures | `:13394-13413` | Wrong side of the validation/runtime split — fetch failures are runtime, must return `[success:false, error, note]`, not throw -32602. **Fix per Q3.** |
| `set_variable` converts `setGlobalVar()==false` into a shadow-namespace "success" | `:5776-5792` | A "that hub variable doesn't exist" signal is swallowed; writes to a parallel `state.ruleVariables` map and reports success. Documented/intentional fallback, but the model isn't told. **Fix:** surface a warning/note when the native set fails, even if the shadow write proceeds. |
| Sibling native-RM write POSTs discard status | `_rmRemoveTrigger:16611`, `walkStep` href-context `:19799` | Same class as #4, backstopped by verify loops but inconsistent. **Fix:** add the `status >= 400` guard for consistency. |
| `toolCheckForUpdate` runtime-error return omits the contract-required `note` field | `:24271-24277` | **Fix:** add an actionable `note`. |

#### Secret-handling findings

| Finding | Location | Fix |
|---|---|---|
| Authenticated hub-session cookie cached in plaintext `state` (backup/state-dump recoverable) | `:6860-6861`, read `:6838-6839` | Live admin-session credential in durably-exported state. No tool echoes it (verified), so exposure is via state/backup dumps. **Fix:** see **Q8** (cookie lifetime) — pairs with the `atomicState` migration in PR2b. |
| `importUrl` logged verbatim into the MCP-readable debug buffer | `:7019,7029,7042,7046,7056,7058` | URL-embedded credentials leak into the buffer. **Fix:** redact `://user:pass@` and common token query-params before logging (sandbox-safe regex). |
| Raw hub internal-API response body logged at WARN | `:7335` | Potential sensitive-content leak into buffer. **Fix:** bound/elide the logged body. |
| `access_token` carried as `?access_token=` query param | `:44-46` | Proxy/access-log exposure on the cloud path. Platform-standard for Hubitat local OAuth, so **document the posture**; pairs with token rotation (**Q9**). |

---

### PR2b — Robustness & infrastructure hygiene

#### Hub HTTP client (`hubInternal*`)

| Finding | Location | Fix |
|---|---|---|
| Six `hubInternal*` variants duplicate base-URI + cookie + retry + parse (~25 lines × 6) | `:6889, 6937, 7178, 7220, 7255, 7304` | Consolidate onto one core helper; the four orthogonal concerns (base URI, cookie auth, SSL/timeout, refresh-retry) live in one place. **This is the seam #209 extracts as `HubHttpClientLib`.** |
| Four ad-hoc `httpPost`/`httpGet` room sites bypass the cookie-refresh retry entirely | `:12562, 12881, 12959, 13054` | `update_device` room-assign, `create_room`, `delete_room`, `rename_room` hard-fail on an expired-cookie window instead of transparently re-authenticating. **Fix:** route through the consolidated client. |
| Auth-failure retry detection is a fragile substring match on `e.message` | `:6874-6882` (`shouldRetryWithFreshCookie`) | False-positives (any error embedding '401'/'403') and false-negatives. **Fix:** prefer the duck-typed `resp.status` pattern already proven in-file. |
| `resp.data?.toString()` body-read fallback can return reader/stream junk strings | 5 of 6 variants (`:6911, 7197, 7237, 7280, 7326`) | The deploy path was hardened to re-throw; the six variants weren't. Junk flows downstream as a real body (e.g. library-update false success). **Fix:** mirror the hardened re-throw-on-read-failure. |
| Cookie cache stored in `state`, not thread-safe `atomicState` | `:6838-6839, 6860-6861, 6877-6878` | Concurrent tool calls can null the cache while another reads/repopulates. **Fix:** `atomicState` (pairs with the PR2a plaintext finding + **Q8**). |
| `http://127.0.0.1:8080` base URL repeated 11× | `:6846, 6892, …, 13054` | **Fix:** one named constant (sandbox-safe method-based constant, not `private static final`). |
| Timeouts hardcoded/inconsistent (30s GET/POST vs 420s form/json) | `:6889 …` | **Fix per Q5.** |
| **No unit test exercises the retry / cookie-refresh path** | tests under `src/test/groovy/` | Highest-risk logic in this layer (wipes shared auth state, recurses) is untested. **Fix:** add direct + dispatch tests for the consolidated client; prerequisite for the consolidation landing safely. |

#### Logging & observability

| Finding | Location | Fix |
|---|---|---|
| Debug-log ring buffer in non-thread-safe `state`, written by request handlers **and** the 03:00 `checkForUpdate` job | `:8116-8194`, `:320`, `:24194-24250` | Overlapping writers drop/corrupt entries. Observability-integrity (not primary-state) risk. **Fix per Q6.** |
| Every logged line re-serializes the entire 100-entry buffer to `state` | `:8176-8185` | O(buffer) write per log line — a perf cliff under verbose/bulk ops. **Fix:** with the `atomicState`/Q6 decision, write incrementally where possible. |
| Native warn/error emission coupled to the buffer threshold (default `error`) | `:8159, 8188`, defaults `:8120/8142` | warn/info operational events hidden from the native Logs UI by default. (Code comments argue this is intentional.) **Fix per Q7.** |
| `mcpLogError` (structured exception capture) used in only 5 of ~70 error sites | `:8199-8205` | Most errors lose stack-trace/class capture. **Fix:** route error sites through `mcpLogError`. |
| Inconsistent `component` tag vocabulary undermines the documented filter | mcpLog sites | **Fix:** normalize the component tag set. |
| `mcpLog` silently drops entries for an unrecognized level | `:8148-8153` | **Fix:** default-case + warn. |
| Full inbound request body logged at debug | `:585` | Caps at 500 chars like the response at `:620`. Token is NOT here (it's a query param). **Fix:** `requestBody.toString().take(500)`. |

#### State & concurrency

| Finding | Location | Fix |
|---|---|---|
| `handleHubVariableEvent` read-append-cap-write on `atomicState` is still non-transactional | `:491-515` | `atomicState` gives per-access atomicity, not multi-step transactionality. **Fix:** minimize the RMW window / single write. |
| BM25 corpus cached in `state`, only invalidated on `updated()`; re-tokenized every query | `:280, :24290, :24331` | DB serialization on every access; df-table rebuilt per query. **Fix per Q15** (also a PR2c perf angle). |
| `listCapturedStates` re-implements timestamp formatting instead of `formatTimestamp()` | `:6155` | **Fix:** reuse `formatTimestamp()`. |

#### App lifecycle

| Finding | Location | Fix |
|---|---|---|
| `uninstalled()` does no teardown — leaks `addInUseGlobalVar` registrations, leaves subscriptions/schedule | `:307-309` | The `addInUseGlobalVar` leak is genuinely unrecoverable (Hubitat doesn't auto-clear; phantom "in use by an app" marks persist after removal). **Fix:** `unsubscribe()`, `unschedule()`, and `removeAllInUseGlobalVar()` in `uninstalled()`. |
| `schedule()` re-registered on every `updated()` with no `unschedule()` | `:320` | **Fix:** unschedule before reschedule (or guard idempotently). |
| `initialize()` fires a synchronous outbound GitHub version check on every settings save | `:320-321` | Blocks the save round-trip on an external HTTP call. **Fix:** move to the scheduled path / async. |
| `accessToken` created once, never rotatable; no path to regenerate a leaked token | `:311-315, :40/44/46` | Credential-hygiene affordance. **Fix per Q9.** |

#### MCP protocol conformance

| Finding | Location | Fix |
|---|---|---|
| `initialize` hardcodes `protocolVersion` "2024-11-05", ignores the client's requested version | `:669-684` (hardcode `:678`) | The code already targets 2025-06-18 semantics (`isError`, `outputSchema`) but advertises 2024-11-05. Spec-legal fallback, but a strict newer-only client needlessly downgrades, and there's no single source of truth. **Fix per Q10** (move the 3 `HandleMcpRequestDispatchSpec` assertions in lockstep). |
| No inbound bound before dispatch | `:570-583` | `request.JSON` is already buffered by the servlet (can't pre-parse-guard from the sandbox), but each batch element triggers a full `processJsonRpcMessage` (possibly hub HTTP). **Fix:** cap batch element count → -32600. Token-gated trusted-principal path, so robustness not DoS. **Value per Q12.** |
| GET `/mcp` → blanket 405 with no JSON-RPC envelope / no SSE | `:565-568` | **Fix:** keep 405 (SSE impractical on the HEM endpoint) but return a JSON-RPC-shaped body + comment the POST-only contract. |
| No OPTIONS / CORS handling | `:548-558` | **Fix per Q13** (document the posture; CORS isn't fully achievable via `render()`). |
| JSON-RPC error envelopes returned with implicit HTTP 200, inconsistent with the 405/204 paths | `:576-582, 604, 621` | Correct per spec, but the invariant is undocumented. **Fix:** one comment locking the transport contract. |
| Inbound `Content-Type` never validated before treating the body as JSON | `:573` | **Fix:** if the header is reliably exposed in-sandbox, return a clearer -32700 naming `application/json`; else comment why not. |
| All-notifications POST returns 204; MCP Streamable HTTP prescribes 202 | `:599-601` | **Fix:** 204 → 202 (move the spec assertion). Trivial; leave 204 if maximal client compat is preferred. |
| `initialize` result omits the optional `instructions` field | `:669-684` | **Fix per Q11** (PR2 owns the field; PR3 owns the prose). |
| Forward-looking RC items (`_meta`, `ttlMs`/`cacheScope`, `tools/list` `nextCursor`) not implemented | `:686-706` | **Confirm deferral per Q14.** The `tools/list` "no nextCursor" choice is deliberate and sound — do NOT revert it. |
| `/health` builds JSON via raw string interpolation of `currentVersion()` | `:560-563` | Latent escaping risk; only hand-rolled JSON in the transport layer. **Fix:** `JsonOutput.toJson([...])` (does not touch the version string — boundary-safe). |

---

### PR2c — Performance & in-file cleanup

| Finding | Location | Fix |
|---|---|---|
| `handleGateway` rebuilds the entire ~100-tool catalog on **every** gateway tool execution just to read one tool's `required` array | `:1323` → dispatch `:3695` | Hundreds of Map allocations + regex passes per call. **Fix per Q16** (lightweight `name→requiredParams` map is the safe option). |
| `getAllToolDefinitions()` has no memoization; full catalog rebuilt on every `tools/list` | `:1539` (`:1472/:1494`, `:704`) | Content changes only on update/toggle. **Caution:** naive caching is unsafe (toggle/version-aware invalidation required) — see the verification nuance. **Decision in Q16.** |
| `getGatewayConfig()` (13-gateway literal) rebuilt multiple times per request | `:881` | **Fix:** build once per request / memoize with the catalog. |
| `tools/call` success path JSON-serializes the result up to 3× | `:732, :758, :604` | Inner guard serializes to measure, then re-serializes to send; outer serializes again. **Fix per Q17** (single-Map verbatim passthrough; batch keeps double-encode). |
| BM25 df-table rebuilt on every `search_tools` query | `:24331` | **Fix:** cache the df-table (pairs with Q15). |
| Three size-cap literals with no single source (124000 `:607`, 120000 `:759`, 120000 `:9436`) | `:607, :759, :9436` | Inner-must-stay-below-outer invariant is hand-maintained. **Fix:** one `hubResponseCapBytes()` method-constant feeding all three (keep inner at exactly 120000 — 4 dispatch-spec assertions pin it). |
| Headroom comments arithmetically wrong (128KB ≠ 128000; real cap 131072) | `:607, :759` (+ `TOOL_GUIDE.md:31`) | **Fix:** correct to ~7KB / ~11KB headroom under 131072. |
| Two dead handlers (`toolGetHubDetails`, `toolGetHubHealth`) remain after merge into `get_hub_info` | `:8699-8762, :8953-9041` | ~150 lines unreachable. **Caution:** specs DO reference them — removal breaks the Spock suite unless those specs are updated in lockstep. **Decision in Q18.** |
| `freeOSMemory`/`internalTempCelsius`/`databaseSize` fetch-and-trim block duplicated across 4 tools | `:5180/5195/5210` | **Fix:** extract one helper. |
| Several native-RM functions are 1,000–1,650 lines (`toolCreateNativeApp`, `toolUpdateNativeApp`, `_rmAddAction`) | `:17320-18970, :20413-22057, :22057-23343` | Beyond review/test granularity. **Decision in Q19** (extract now / defer to #209 / leave — these are exactly #209's split candidates). |
| **Monolith file-split** | whole file | **RESOLVED: deferred to #209.** PR2 does in-file cleanups only; PR2b's HTTP-client core is a pre-cut seam for the split. |

---

### PR2-legacy — Legacy rule child-app (`hubitat-mcp-rule.groovy`)

Maintainer chose **option C** (2026-06-02): land the two bug-fixes **and** the state-hygiene cleanups, under the AGENTS.md "the custom MCP rule engine… still gets bug fixes" carve-out. The file's backend is otherwise in good shape (lifecycle tears down correctly, atomicState usage is deliberate, conditions fail closed, the loop-guard self-prunes). This is a contained, separate-file PR — do **not** broaden it into feature work on the frozen engine.

| Finding | Location | Class | Fix |
|---|---|---|---|
| `set_mode`/`set_hsm` validation error returns `false` — which is the rule-STOP signal — silently aborting all remaining actions | `:3390-3403` (consumed at `:3290, 3450, 3455, 3611`) | **bug-fix** | A misconfigured `set_mode`/`set_hsm` (no mode/status) aborts the entire remaining action list and bubbles up through if/then/else + repeat. **Fix:** skip-and-continue for the validation-error case, not stop (confirm intent in **Q20**). |
| `http_request` action logs the full request URL on the error path | `:3635-3653` (esp. `:3651`) | **bug-fix** | Surfaces into the parent's MCP-visible buffer at default config; URL-embedded credentials (basic-auth, token query-params) leak. **Fix:** redact before logging (sandbox-safe regex). |
| `localVariables` atomicState map grows unbounded via `set_local_variable` / `variable_math` | `:3475-3479` | state-hygiene | **Fix:** add a size cap with eviction or a passive size warning. |
| `durationFired` / `cancelledDelayIds` retain entries keyed to triggers/delays that no longer exist after a partial in-place edit | `:2779-2884` | state-hygiene | Self-heals on next re-init; bounded to one session. **Fix:** prune orphaned keys on edit, or document the self-heal as intentional. |
| `recentExecutions` loop-guard counter not cleared by `disableRule()`/`updated()`/`updateRuleFromParent()` (only by the auto-disable path) | `:2998-3016` | state-hygiene | **Fix:** clear on the disable/update paths for symmetry. |

---

### Decisions pending maintainer input

Numbered like PR1's Q1–Q8. Answer each as the owning sub-PR is reached (not all up front). **Two are already resolved** and recorded for the file; the rest are open.

**Resolved**
- **R1 — Legacy child app:** option C — land the 2 bug-fixes + the 3 state-hygiene cleanups (PR2-legacy above).
- **R2 — File split:** deferred to #209; PR2 does in-file cleanups only.

**PR2a (correctness & security)**
- **Q1 — `get_app_config` redaction breadth.** Redact only `type=="password"` inputs (precise), or also heuristically redact keys matching `/pass|secret|token|apikey|api_key/i` (catches secrets stashed in text inputs, may over-redact)?
- **Q2 — Batch oversize handling.** Per-id error elements (one -32603 per non-notification message, best correlation, more code), a single aggregate array element, or the minimal fix (keep the collapse but key it to the first non-null id + log dropped ids)?
- **Q3 — HPM runtime errors.** Reclassify `_hpmFetchManifests` fetch/parse failures as runtime errors (`success:false`+`note`), or keep the throw-IAE-then-callers-rewrap pattern since both callers already convert it?

**PR2b (robustness & hygiene)**
- **Q5 — Timeouts.** Is `hubInternalPost` intentionally 30s while form/json are 420s, or should it take a `timeout` param / default to 420s?
- **Q6 — Debug-log buffer.** Move `debugLogs` to `atomicState` (fixes the cross-thread clobber, adds a per-line flush on the hottest write path, ~466 call sites), or keep it on `state` and accept that concurrently-emitted log lines may be dropped (telemetry, not correctness)?
- **Q7 — Native log threshold.** Always emit warn/error to the native Hubitat Logs page regardless of `mcpLogLevel` (decouple native emission from buffer retention), or keep the current coupling (intentional per code comments)?
- **Q8 — Cookie cache lifetime.** Clear the cached cookie on every `updated()`/`initialize()` (one re-login per save/reboot), or keep the 30-min TTL and only shorten it? (Pairs with the plaintext-state + `atomicState` fixes.)
- **Q9 — Token rotation surface.** UI-only button (`mainPage` + `appButtonHandler`, pure infra), or also a Developer-Mode MCP tool so an agent/CI can rotate (PR1-adjacent — adds a tool)?
- **Q10 — Protocol version.** (a) Echo the client's requested version when in an allowlist, default 2024-11-05; (b) bump the advertised default to 2025-06-18 to match shipped features; or (c) leave pinned at 2024-11-05?
- **Q11 — `instructions` field.** Add the optional `initialize` `instructions` string (gateways, no-arg discovery, 120KB cap, cursor pagination), or omit and rely on per-tool descriptions?
- **Q12 — Inbound batch cap.** Max batch-array element count before -32600 (proposed 50)?
- **Q13 — CORS posture.** Are browser/cross-origin MCP clients in scope (document the `render()` CORS limitation), or comment the no-OPTIONS posture as intentional (server-to-server only)?
- **Q14 — RC deferral.** Confirm PR2 defers `_meta`, `ttlMs`/`cacheScope`, and `tools/list` `nextCursor` until the next-revision spec publishes and a client negotiates it?
- **Q15 — BM25 corpus.** `atomicState` persistent cache, process-lifetime lazy memo, or drop the persistent cache entirely?

**PR2c (performance & cleanup)**
- **Q16 — Catalog memoization.** Cache the full catalog Map list (large in state), cache only a lightweight `name→requiredParams` map for the dispatch-path check and keep rebuilding the full catalog for `tools/list`, or leave as-is? (Naive full caching needs toggle/version-aware invalidation.)
- **Q17 — Serialize-once.** Thread a pre-serialized sentinel from `handleToolsCall` that only the single-Map branch of `handleMcpRequest` renders verbatim (batch keeps the double-encode), or keep the clean object-return contract and accept the cost?
- **Q18 — Dead-handler removal.** Confirm removing `toolGetHubDetails`/`toolGetHubHealth` **and** updating the Spock specs that reference them in the same commit (the "no test breaks" assumption is false — specs do reference them)?
- **Q19 — Giant native-RM functions.** Test-guarded helper extraction now, defer to #209's split, or leave as-is given the legacy proximity?

**PR2-legacy**
- **Q20 — `set_mode`/`set_hsm` semantics.** Confirm the intended fix is skip-and-continue (not stop) for a misconfigured action.

### Implementation plan — sub-PR dispatch

When PR1b + PR1c land and the rebased baseline is stable:

1. Re-run the audit's high/medium findings against the post-PR1 surface; reconcile any line drift before implementation.
2. Branch `issue-105-pr2a-correctness-security` off then-current main. Land PR2a first (6 high-sev bugs + tied silent-failure + secret handling), each fix with both a direct-call and a dispatch-envelope test, and a `tests/BAT-v2.md` scenario where behaviour on a live hub changes.
3. PR2b next on a fresh branch — HTTP-client consolidation lands with its retry/cookie tests as a prerequisite, then logging/state/lifecycle/protocol.
4. PR2c last — perf + in-file cleanup; explicitly cross-reference #209 for the split.
5. PR2-legacy is independent (separate file) and can land anytime after PR2a — keep it scoped to the five items above.
6. Each sub-PR opens as draft; the maintainer flips to ready.

### Hard rules for PR2 implementation agents

1. **The findings above are the authoritative output; AGENTS.md § "Error contracts", "Pagination & response-size discipline", "Schema design", and "Boundaries" are the rule set behind them.** Read the relevant section before touching a finding.
2. **Stay inside the Hubitat sandbox.** No `Eval`/`GroovyShell`/`Class.forName`/`Runtime.exec`/`new Thread`/`new File`/`getClass()`/`log.isDebugEnabled()`. Constants are method-based (`def hubResponseCapBytes() { 131072 }`), not `private static final` (script-scope rejects it). Verify any `request.*` header API exists before relying on it — do not invent one.
3. **Never touch boundary-protected files/strings** — version strings (server header, `currentVersion()`, rule header, manifest `version`), `packageManifest.json` `releaseNotes`/`dateReleased`, `README.md` Version History, `CHANGELOG.md`. `pr_guard.py` blocks them. The protocol-version work (Q10) touches the *protocol* version, NOT the app version — different thing.
4. **Never `cd && git` compound commands.** Use `git -C <path>`.
5. **Tests move in lockstep.** Several fixes are pinned by `HandleMcpRequestDispatchSpec` (protocol version `:53/523/552`, the 204 `:500-502`, size literals `:241/263/293/294`) and `ToolListDevicesSpec` (`:924-962`). The dead-handler removal (Q18) requires updating the specs that reference `toolGetHubDetails`/`toolGetHubHealth` in the same commit. A fix that changes a pinned assertion updates that assertion in the same commit.
6. **`pr1_overlap` is zero by design — keep it that way.** No PR2 change should touch `getGatewayConfig()`, `handleGateway()`, `getAllToolDefinitions()`, `getToolDefinitions()`, or any tool's `inputSchema`/`description`. (The Q16 dispatch-path memoization reads `required` from the catalog but must not restructure the catalog builder.) If a fix seems to require touching PR1's region, STOP and report — it's a rebase/coordination issue, not a silent merge.
7. **Boy-Scout the adjacent small stuff; ASK before large scope.** The legacy PR is the boundary case — keep it to the five listed items; do not refactor the frozen engine.
8. **AGENTS.md and CLAUDE.md stay byte-identical.** PR2 shouldn't need to touch them; if a description-quality drift is spotted, surface it, mirror in the same commit.
9. **Surface contradictions, don't paper over them.** Pre-existing content that contradicts a fix gets called out in the PR description.

### Appendix — refuted finding (for the record)

One high-confidence finding was **refuted** by the adversarial pass and is NOT actioned:

- **"`hubInternalGet`/`hubInternalPost` discard HTTP status, so callers can't distinguish transient from real failures."** *Refuted:* HTTPBuilder invokes the success closure **only** on 2xx and throws on non-2xx (the code asserts this itself at `:6964`; the plain GET auto-follows 3xx to its final 2xx). So a returned non-null string already **is** a verified-2xx body; the transient-vs-real distinction is preserved at every call site via try/catch + `shouldRetryWithFreshCookie`. The only residual truth — text variants return `String` while `*Raw`/`*Form`/`*Json` return `[status, location, data]` — is a deliberate split (the struct variants exist to read the 302 `Location` header the text path can't see). That's a low-value return-shape consistency nit (tracked as **Q4** below if the maintainer wants uniformity), not a correctness gap.

- **Q4 (optional, low) — HTTP client return shape.** Migrate the two text-returning variants to the `[status, location, data]` struct for uniformity (touches every caller), or keep text-only with the throws-on-non-2xx contract merely documented? Recommendation: keep text-only + document; the struct split is intentional.
