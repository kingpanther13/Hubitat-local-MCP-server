# Sandbox Map access validation and repair plan

> For agentic workers: use superpowers:executing-plans to implement this plan task by task. Preserve the evidence distinctions below.

**Goal:** Repair demonstrated Hubitat Map-access failures while preserving public keys, values, validation and ordinary rule/device behavior.

**Architecture:** Use explicit Map.get/put at external-key boundaries. Keep the scanner independent of helper names, detect reads as well as writes, and distinguish a source candidate from a demonstrated reachable runtime failure. Do not import the device-configuration feature from #411.

**Tech stack:** Hubitat Groovy on firmware 2.5.1.181, Python source lint, remote Spock/Python/Groovy CI.

**Spec:** https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/415

## Preparation branch scope

The maintainer requested investigation and a repair plan before opening a PR. This branch carries only the two original rendering/canonicalization fixes and three snapshot fixes from the referenced work, scanner improvements, focused regressions and the investigation artifacts. It does not claim to close issue 415. Other repairs below remain proposed work.

Do not open a PR without the maintainer's approval. On approval create it as draft with `e2e:skip`. No PR or branch package has been deployed on the personal hub during this preparation; the exact future PR SHA must be tested there afterward with owned fixtures. Do not dispatch heavy tests locally.

Dynamic scan matches remain explicitly nonblocking candidates because the scanner does not prove caller bounds. Measured literal collisions are blocking. This is an interim investigation guard, not completion of the issue's final enforcement requirement. The repair phase must add evidence-backed enforcement for confirmed dynamic boundaries without blanket method exemptions.

## Constraints

- Base: upstream main `ec7127d8cc6046c77882046cd99fa31f5421d9a9`.
- Inventory reference: `f3c99d9334551af8d7d473605dc6527d7121039c`; its 24 rows are candidates, not a bug count.
- Initial personal-hub deployment: `325d472ffbdcca85bd640532c18ce2907609b9fd` (#411). Parent, child and nine affected libraries were individually SHA-256 matched to this commit.
- Read/write probes may mutate only resources created by this investigation. Existing household resources may be read. Package deployment is separately authorized by the user.
- Sandbox lint may run locally. All other CI tests run on GitHub; no local Gradle, unit suites, or Docker.
- Keep the PR draft, based on main, and merge it before #411. No merge is authorized by this plan.

## Measured contract

On the recorded firmware, untyped Map bracket reads AND writes with lowercase `fields`, `class`, and `metaClass` throw `java.lang.SecurityException: Subscript property '<key>' is not allowed from app or driver code`. Uppercase `Fields` and literal data key `getClass` pass. These observations are case-sensitive and are not an exhaustive forbidden-key list.

Explicitly typed Map receivers accept the tested `fields`/`class` bracket reads and writes. Typed `metaClass` writes instead attempt a MetaClass cast and fail. Map.get/put preserves all tested keys and null/false/zero/list/nested values. Therefore a blanket claim that all bracket access to `fields` fails, or that the name itself is invalid, is false.

The native platform accepts a bool input named `fields`, stores its false value, accepts a driver attribute named `fields`, and accepts an RM local variable and Custom Attribute condition using that name. Source-level key provenance still matters: a local variable's name appears in embedded buttons and does not thereby become a schema input name.

Both List and Map enum definitions are normalized by this firmware into a List of Maps on the config JSON wire. The List options failure is reachable; the direct-Map helper branch fails under a direct probe but has not been reached by these native definitions.

## Per-row investigation ledger

The live snippet matrix tests the exact assignment/declaration/closure forms, independently of upstream failures that can mask a later row. A failing isolated copy does not by itself establish production reachability.

| Row | Current disposition | Evidence and required treatment |
|---|---|---|
| 1 | Bounded safe | Typed Map write passes; only producer uses `mrtr-` UUID IDs and resumed IDs are regex constrained. Preserve registry semantics. |
| 2 | Bounded safe | Generic untyped copy fails for fields, but registry keys come from row 1's constrained producer. Ordinary IDs work during live connector writes. |
| 3 | Bounded safe for this assignment | The assignment executes only with typeHintOverride. Actual non-null callers use generated/literal stays-N, durChoice.N and useST names; raw external settings callers pass null. Scratch Required Expression successfully exercised useST. |
| 4 | Unsafe reachable device-setting copy | Owned app declares capability.actuator input fields, selects only the scratch device, and native config preserves that name and device ID map. Exact fragment fails; savedSchema fails earlier in the full restore path. Full restore after repair remains required. |
| 5 | Reproduced reachable bug | Reading scratch listOptions through hub_get_app_config throws on fields. Raw native options retain the name and HTML label. |
| 6 | Unsafe helper; direct-Map wire reachability unproven | Direct Map fragment fails. Native Map enum definition was normalized to List, reaching row 5 instead. Do not count this as a second reproduced tool failure. |
| 7 | Unsafe reachable input copy | Native input and stored fields value exist; exact explicit-value branch fails. Full flow can fail earlier while collecting schema. |
| 8 | Unsafe reachable input copy | Native raw defaultValue=false/0 observed; exact default branch fails on fields and preserves zero with ordinary key. |
| 9 | Unsafe reachable setting copy | Native stored fields value observed; exact chosen-value branch fails. Include liveSettings/cfgSettings reads in repair. |
| 10 | Unsafe reachable fallback copy | Exact pageValue read/write fragment fails; preserve empty fallback and false/zero. |
| 11 | Bounded safe | Literal deviceId branch; exact helper maps owned ID and preserves no-mapping fallback. |
| 12 | Bounded safe | Literal deviceIds branch; exact helper preserves list shape, null and unmapped entries. |
| 13 | Reproduced helper with reachable import data | Exact recursive helper rejects nested fields; source import passes supplied trigger/condition/action data to it before rule creation. Legacy engine is disabled on personal hub, so its public write gateway was not enabled. |
| 14 | Reproduced reachable bug | Owned legacy dashboard updateTiles with fields throws; ordinary clock tile creation succeeds. |
| 15 | Bounded safe | Numeric IDs cross-checked against live inventory; owned RM rule appeared through hub_list_rules. |
| 16 | Unsafe native-page copy | Exact read/write fragment fails; schema permits fields in owned app. Confirm repair through owned sub-page Done path, separating schema-collection failure. |
| 17 | Unsafe native-page copy | Exact read/write fragment fails; generic app edit uses this distinct main-page Done path. Preserve the full settings body. |
| 18 | Unsafe helper; RM schema collision not reproduced | Exact stored-value fragment fails. Only production call is selectActions clearActions; owned rule with local variable fields cleared its comment successfully. |
| 19 | Unsafe helper; RM schema collision not reproduced | Exact button-name fragment fails; fields local-variable button appears under embeddedActions, not collected input schema. |
| 20 | Unsafe helper; RM schema collision not reproduced | Exact missing-value fragment fails; same selectActions reachability limitation. Preserve blankedInputs semantics. |
| 21 | Bounded safe | Only call supplies extraSettings=[trashActs:stringIndices]. Scratch clearActions succeeded. Do not call the helper's arbitrary-key probe a production bug. |
| 22 | Reproduced native edit failure; downstream assignment isolated | Owned app fields edit fails; native membership accepts name, but schema collector can fail first. Exact knownSettings assignment independently fails. |
| 23 | Reproduced reachable silent data loss | Scratch driver reports fields=owned-fields. Device details preserve it; room read silently omits it while returning ordinary/getClass states. |
| 24 | Bounded safe | Only latitude/longitude callers; exact helper coerces both zero strings to numbers locally inside scratch app. No location settings changed. |

## Additional controls and scanner gaps

- Original renderer: main source fails for nested fields; referenced Map.put variant succeeds and retains false/zero/null/list values. Installed #411 device catalogs both succeed and retain public fields schema properties.
- Argument canonicalizer: main source fails for fields selections; Map.put variant succeeds. Installed connector accepts legitimate fields projections. Record negotiated transport separately before claiming a modern-transport end-to-end proof.
- Device normalizer: unsafe variant fails; exact referenced implementation and a renamed copy both pass. This helper belongs to #411, not main; retain its scanner regression without importing the feature.
- All three snapshots: exact before sources return null with the fields SecurityException; referenced after sources retain real scratch driver values. The supportedAttributes branch uses an adapter forcing currentStates empty; the bypass adapter reads only the selected scratch device and returns native fullJson. Date normalization is not asserted by the bypass adapter.
- Additional gaps: savedSchema[n], layout[key] in setOptions, and `_rmCollectInputSchema` writes need coverage. Owned setOptions fields reproduces a connector failure. Schema collector fails before several later form writes.
- Fixed literal attribute loops in device/virtual-device info remain negative controls.

## Repair tasks

### 1. Read/write lint contract

Files: tests/sandbox_lint.py; tests/test_sandbox_lint.py.

- [ ] Add remote regression cases for untyped reads/writes, aliases, Map-returning helpers, implicit return types and renamed helpers.
- [ ] Remove getClass as an assumed colliding data key; add measured lowercase fields/class/metaClass cases and uppercase Fields controls.
- [ ] Preserve typed Map read/ordinary-write controls; test typed metaClass write separately.
- [ ] Keep bounded branches/list indices/comments/strings unflagged. For interprocedural bounds the scanner cannot prove, report an explicit candidate rather than a demonstrated bug; avoid blanket method-name exemptions.
- [ ] Run the self-tests on a GitHub verification branch before and after changes. Run only the source lint locally.

### 2. External-key boundaries

Files: hubitat-mcp-server.groovy; libraries/mcp-code-management-lib.groovy; libraries/mcp-app-cloner-lib.groovy; libraries/mcp-custom-rules-lib.groovy; libraries/mcp-native-rules-lib.groovy; libraries/mcp-dashboards-lib.groovy; libraries/mcp-rooms-lib.groovy; libraries/mcp-devices-lib.groovy.

- [ ] Replace demonstrated external-key bracket writes with receiver.put(key,value) and corresponding reads with receiver.get(key).
- [ ] Start with `_rmCollectInputSchema`, renderer and canonicalizer, so later form paths can be tested through their real connector flow.
- [ ] Preserve schema membership, null/false/zero selection precedence, device-list CSV encoding, omitted fields and backup semantics.
- [ ] Carry the three independently proven snapshot fixes from the immutable reference onto main. Do not import #411 device-edit features.
- [ ] Treat direct-Map options and the three RM-only full-form branches as defensive hardening, explicitly separate from reproduced end-to-end bugs if they are changed.
- [ ] Preserve bounded cases; prefer focused call-contract tests over cosmetic sweeping of safe indexing.

### 3. Regression validation and integration

Files: focused src/test/groovy/server specs; tests/e2e_test.py; tests/BAT-v2.md; resources/hub2-source/README.md; this ledger.

- [ ] Remote checks: sandbox scan/self-tests, Python, Ruff, normal/strict x gateway/flat Spock, supported Groovy compatibility lanes.
- [ ] Add owned-fixture E2E for field-bearing native options/settings, driver attributes, room reads, snapshots and legacy layout edits. Verify exact values and rejection paths, not only success booleans.
- [ ] After explicit approval, create a draft PR against main with e2e:skip and no release/full-E2E labels. Batch changes and wait for any active E2E teardown before pushing again.
- [ ] Deploy the exact PR SHA to the authorized personal hub, verify installed source/library hashes, and replay scratch-only connector checks. Record before/after firmware and SHA.
- [ ] Restore the prior #411 deployment after personal-hub validation unless the user chooses another final revision. Verify household inventories/settings and remove all investigation resources and owned artifacts.
- [ ] Before proposing merge, agree the final E2E gate with the maintainer (current instruction is e2e:skip). Merge and changing that label require authorization. After eventual merge, merge main into #411 and retain the final evidence-backed lint.

## Evidence location

Raw connector responses and household baseline inventories are retained only in local `validation/issue-415/`; they must not be published in the PR. Publish sanitized probe matrices, source hashes and this ledger. Probe infrastructure is disposable on the personal hub; any infrastructure installed for the shared CI hub must follow its persistent-fixture lifecycle.
