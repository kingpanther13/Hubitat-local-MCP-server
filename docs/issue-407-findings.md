# Issue 407 findings and verification

Reviewed the [issue body and all seven comments](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407) against `c7f8dbee` on 2026-09-24. The table covers every proposed item, including proposals that are not bugs. "Confirmed" describes the evidence stated, not an assertion that every error branch was reproduced on a hub. Hub Mesh is excluded by the owner's explicit instruction.

## Issue body

| Item | Disposition and evidence |
| --- | --- |
| 1. General AST novelty gate | **Dismiss general gate; retain specific guard.** Live compile-only app: `{ -> 42 }` returned bare HTTP 500 and created no code entry; identical source with `{ 42 }` compiled. Updating that control to the zero-parameter form also failed, leaving version 1/source unchanged. This isolates the syntax on firmware 2.5.1.181, not the private compiler mechanism. A novelty inventory has no demonstrated benefit beyond the existing precise guard and live deployment check. Add the actual `parse24` command to agent guidance; Python lint does not run that AST check. |
| 2. Consolidate device-inventory returns | **Dismiss style-only proposal.** `_fetchAllHubDeviceRecords` has early returns for different provenance/completeness contracts. No incorrect result identified. |
| 3. Run wizard matrix for recovery | **Dismiss proposed proof.** `wizard_probe_matrix.yaml` contains no walkStep navigate operation; A10 substitutes getAppConfig for unimplemented introspection. Running it cannot test `_rmRecoverEmptyNavRender`. Existing disposable-rule E2E covers normal walkStep navigation. The old matrix also names existing devices and cannot safely be run verbatim under the preservation constraint. |
| 4. Force pageError/navRetried E2E | **Dismiss conditional request.** No live trigger established. Preserve branch-contract unit coverage; do not manufacture hub failures or claim those tests reproduce a user failure. |

## Round-six review comment

Numbering follows [the first comment](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5568954280).

| Item | Disposition and evidence |
| --- | --- |
| 1. pageError overrides commit | **Correct contradictory guidance only.** The returned positive commit signal must not be accompanied by “not because the op committed.” Explicitly prohibit repeating that committed operation. Keep failure gating: later wizard steps must stop when the page is unusable. No live duplicate-action failure established. |
| 2. Paused activation failure hidden | **Fix diagnostic contradiction.** The saved metadata's explicit activation failure must bypass the normal paused note. Regression tests preserve failure details. Live paused-save control confirms `activated:false` can coexist normally with `activatedSuccessfully:true`; failure metadata is a separate condition. |
| 3. Pause-only refusal inconsistency | **Fix inconsistent handling.** A redundant pause/resume refusal with confirmed matching readback is successful for pause-only calls as for definition/rename calls. A refused state change still fails. Live repeated-pause control succeeds normally; an actual refusal was not induced. |
| 4. Missing pre-read pause flag causes false success | **Dismiss alleged false success.** Even with the proposed missing field, a paused readback makes `pauseOk` false. No live producer of the missing-field shape established. Do not add speculative handling. |
| 5. Malformed children silently dropped | **Dismiss unproven producer.** Vendored hub tree contract has list-valued children; no evidence of a map-valued child payload. Arbitrary malformed mock input alone does not establish a hub bug. |
| 6. Tiny budgets exhaust log-read slices | **Fix.** Positive relay/LAN budgets automatically floor at 6000 ms; zero still disables. Eight 4500 ms observation windows cover the native 30-second fetch timeout. Tests distinguish floor, off, and larger values. Owner selected automatic minimum. |
| 7. Label retries mask gh failures | **Fix.** Explicit Bash enables pipefail for both live-label read steps. Extracted-loop tests verify failure retries and eventual success without network. Validate changed workflow by branch dispatch because automatic E2E uses main's workflow. |
| 8. Focused-lane coverage gaps | **Fix remaining gaps.** Current main already maps devices/system_tools. Add developer_mode, watchdog/deadman mappings, and error_verification smoke coverage. |
| 9. Missing client-visible E2E assertions | **Add.** Exercise paused save/repeated pause and activation metadata on suite-owned rules; check optional createRouteNote when present. Do not claim a rare fallback route was forced on modern firmware. |
| 10. Classfile walker needs bidirectional fixtures | **Dismiss stale mechanism.** No classfile walker remains. Existing Groovy AST closure visitor already has positive/negative syntax, library, GString and missing-file fixtures. |
| 11. Anti-degradation assertion on wrong fixtures | **Fix.** Apply it to all parse fixtures; negative fixtures reach canonicalization and must not silently skip the sandbox check. |
| 12. Invalid AST locations map to plausible lines | **Fix.** Invalid line/column/EOF positions retain an unresolved-source location rather than mapping to unrelated source. |
| 13. Guide lint rejects valid delegated methods | **Fix.** Accept def/String methods and intervening comments; analyzer failure no longer claims the runtime guide is absent. |
| 14. jq false coercion in watchdog arm | **Fix pattern, not alleged fail-open.** Preserve false explicitly and scan all sibling shell scripts. The old comparison with literal true was already fail-closed. |
| 15. Re-add authorized device under degraded inventory | **Fix.** In incomplete inventory only, exempt already-selected ids from unknown-id rejection for add. Complete inventory still rejects genuinely stale ids; replace/remove validation remains intact. |
| 16. Successful redirect logged as exception | **Fix.** `_hubRequest` resets telemetry outcome to ok when it handles a 3xx as successful creation. HTTP failure paths remain errors. |
| 17. LAN test cannot distinguish cap | **Fix test.** LAN budget 10000 yields cap 6000, distinct from cloud cap 4500. |
| 18. Snapshot provenance conflates fetch and request | **Fix test.** `background` describes the cached fetch; `budgeted` describes this request. Check them independently and allow legitimate refetch. |
| 19. Cursor test assumes stable jobs beyond TTL | **Fix test.** Compare cross-page counts only when snapshot timestamps match; always check each page's shape/count. Remove unconditional 31-second sleep, which cannot guarantee a cold cache on a shared hub. Deterministic fixtures exercise multiple pages and refetch. |
| 20. Repeated compilation/file reads | **Fix residual duplication.** Main already reduced three compilation units to two. Continue the same unit from conversion to canonicalization; load each source once in the snapshot lint guard. Keep existing fixtures as regression checks. |

The ancillary claim that the parse lane cannot run without a PR is stale: it already has workflow_dispatch. Compiler error capture is also already implemented: the watchdog records save `errorMessage` in `lastSelfDeploy`, and deployment scripts report fresh failures. The isolated probe still produced only a bare 500, so there was no compiler message to recover. Do not replace this with an invented diagnostic.

## Other comments

| Comment/item | Disposition and evidence |
| --- | --- |
| [Historical closure incident](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5573413479) | **Verified history.** Original commit `e4aa8a7d1a7689eadb3d616af847d37a892cd7e9` is +24/-15 and removes the entire deferred closure list. Historical evidence alone did not isolate the syntax; the new live probe above does. The two nits mentioned as fixed in `f8c869ac` require no new change. |
| [405/501 create-route coverage](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5573582801), item 1 | **Add set-membership regression.** Parameterize definitive fallback statuses 404/405/501; assert one legacy child and no duplicate. Same branch, but an omitted status would change behavior. |
| Same comment, SkillSpector items 2–4 | **Dismiss false positives.** At `f8c869ac`, line 303 describes backup-file size, 611 is an OAuth placeholder/header example, and 619 is origin-validation prose. None executes unbounded output or accesses credential files. |
| [Device/rule E2E speed proposal](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5634622257) | **Remove proven waste only.** Remove the 31-second cache sleep and sleeps after final failed status reads. Device configuration already polls an independent nonce observer and exits immediately. Other waits reconcile actual dropped responses. MRTR does not prove all 504 risks absent or justify merging small per-concern rules. |
| [Backup comment](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5669225388), item 1 | **Dismiss unproven kill window/fix.** No live execution-kill trigger established. Removing the committed mirror before persistence can reintroduce stale execution-snapshot loss when the write fails; current code deliberately preserves that committed view. Do not trade a known concurrency contract for an unverified crash model. |
| Same comment, item 2 | **Fix misleading recovery text.** Publication purges eligible pending entries and files regardless of readability; current entry/protected baseline are exceptions. Recovery must happen before that publication. Behavior remains unchanged. |
| Same comment, item 3 | **Dismiss observation as bug.** One bounded 300 ms retry verifies a reusable baseline while holding the same lock as file I/O. No measured harmful contention; moving verification outside the lock would weaken its consistency. |
| [Retained error reports](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5745488488) | **Implement.** Keep at most ten bounded, scrubbed errors in state, written only on the error path. Report them before rolling native history; infer an omitted failingTool from retained explicit context within caller scope. Privacy suppression and log clearing apply. Add state inventory, unit/dispatch coverage and live E2E error→report check. |
| [Hub Mesh items 1–4](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/407#issuecomment-5792583929) | **Excluded by owner instruction.** No Mesh changes. A read found Mesh disabled and no peers; no Mesh writes or device sharing performed. This is not a claim that the findings are disproved. |

## Live evidence and validation boundaries

Personal hub: MCP 4.4.1, firmware 2.5.1.181. A full backup preceded writes. Only a new uninstantiated app-code probe and a new device-free, paused Visual Rule were created; neither referenced existing devices. App code 327 and Visual Rule 2471 were deleted and freshly verified absent. The probe's auto-backup was deleted; all 18 original item-backup records were unchanged on readback. Existing devices, rules, app code, Mesh settings and radios were not modified.

These probes tested the installed baseline, not the patched server. Patch verification runs on the upstream GitHub branch, including unit, Python, sandbox, Groovy 2.4 and E2E lanes. No further local tests were run after the owner prohibited them. GitHub results and final tested revision are recorded in the PR.
