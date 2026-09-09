# Manual sandbox investigation fixtures

These disposable fixtures are for an explicitly authorized live-hub investigation,
not automatic installation by E2E. Create new Apps Code/Driver Code and uniquely
named scratch instances. Never replace an existing app/driver or select an existing
household device. The driver only emits its own attribute events; it has no physical
device or network effects. Delete owned instances before deleting their code.

- `sandbox-map-probe.groovy`: small typed/untyped read/write/get/put matrix.
- `sandbox-map-candidates.groovy`: assignment/declaration/closure forms for all
  24 issue candidates plus adjacent access controls. Input names and list/default
  cases are available on separate pages. Numeric inventory IDs inside isolated
  methods are synthetic data; those methods do not look up or mutate an app.
- `sandbox-map-attributes.groovy`: inert attribute source for room and snapshot
  checks. Select only a newly created device using this driver.
- `sandbox-map-branch-proof.groovy`: exact before/after option and full-form
  helper branches with inert form-capture adapters, plus dynamic-dot controls.

Keep raw investigation results and resource identifiers in local evidence files.
A fragment failure is not proof of a reachable tool failure.
Snapshots and canonicalization were also compared using exact before/after helper
sources inside an owned app; the fallback snapshot used an adapter forcing an
empty currentStates collection. Raw hub responses and installed code-ID mappings
are private local evidence, not repository fixtures.

## Measured access contract

The following results were measured on **Hubitat firmware 2.5.1.181 on
2026-09-09**, using owned, inert app/driver fixtures. They describe these source
forms on that firmware, not an exhaustive list of reserved names or a promise
about later firmware. The installed server remained the #411 revision
`325d472ffbdcca85bd640532c18ce2907609b9fd`; isolated before/after source probes
are not an exact-PR-package deployment test.

| Receiver and access | `fields` / `class` | `metaClass` | `Fields` / `getClass` |
| --- | --- | --- | --- |
| Untyped Map, bracket read | `SecurityException` | `SecurityException` | Accepted as data |
| Untyped Map, bracket write | `SecurityException` | `SecurityException` | Accepted as data |
| Explicit `Map` receiver, bracket read | Accepted | Accepted | Accepted as data |
| Explicit `Map` receiver, bracket write | Accepted | MetaClass cast failure | Accepted as data |
| Explicit `get` / `put` | Accepted as data | Accepted as data | Accepted as data |

The SecurityException reports `Subscript property '<key>' is not allowed from app
or driver code`. This rejects an access form, not the public data name: `fields`
is a valid device projection parameter, schema property, native input name,
driver attribute name, and Rule Machine local-variable name on the tested hub.
Both untyped bracket reads and writes failed. Explicit get/put retained the
original names and false, zero, null, lists, and nested Maps in the tested copy
paths. Case matters: `Fields` is not `fields`. Names such as `properties`,
`empty`, and `keys` are not part of this measured collision set.

Native enum options preserved colliding names in list-of-Map entries. Both native
List and Map enum definitions arrived as that list shape; direct-Map option
branches failed in isolation but their production wire reachability was not
established. Across the original 24 investigation rows, eight had bounded keys,
twelve had reachable unsafe inputs (sometimes behind earlier failures), and four
had isolated failures without proven reachability of the exact production branch.
This is not a claim of 24 reproduced end-to-end bugs.

## Numbered finding dispositions

The numbering and original assignments come from the immutable
[`f3c99d9334551af8d7d473605dc6527d7121039c` inventory](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/415).
The Map-operation repairs are present in the merged
[`a4b968f5` source](https://github.com/kingpanther13/Hubitat-local-MCP-server/commit/a4b968f5ff5bb01425e9b4c47e0145b36c179e15).
"Reachable" below means that native or caller data can supply the implicated
key; a failure in an isolated source fragment is distinguished from a failed
connector call. "Bounded" describes the original production caller or branch,
even where the implementation also adopted explicit Map operations.

| Row | Original location | Disposition and evidence |
| --- | --- | --- |
| 1 | `_writeStatePutLocked` registry write | **Bounded.** The producer creates `mrtr-` UUID IDs and resumed IDs are regex constrained. The typed Map write accepted ordinary, `fields`, and `class` controls; `metaClass` produced a cast failure. Production IDs cannot be those collision names. |
| 2 | `_mrtrSweepLocked` registry copy | **Bounded.** Keys originate in row 1's registry. An arbitrary-key copy failed in isolation, but no production producer admitting those names was found. Ordinary registry behavior was exercised by live connector writes. |
| 3 | `_rmWriteSettingOnPage` schema override | **Bounded assignment.** This write runs only with a non-null type override. Those callers use fixed UI field names or wizard-indexed names: `stays-N`, `durChoice.N`, `useST`, generated `uVar<P>.<N>`, and Hub Variables fields `hbVar`, `varType`, `varDate`, `varTime`, and `varValue`. Raw external-setting callers pass null. Scratch Required Expression exercised `useST`. Schema reads and the write now use explicit Map operations where repaired. |
| 4 | `_rmRestoreFromBackup` device-setting copy | **Reachable, repaired.** An owned app accepted a capability input named `fields` and preserved its selected owned-device list on the native wire. The original copy failed in isolation; schema collection could fail earlier in the complete restore. Preserve device-list shape and mapping fallback. |
| 5 | `stripOptionsHtml` List branch | **Reproduced connector failure, repaired.** Reading the owned List-options page failed on `fields`; the native List retained that name and its HTML label. Cleaning preserves keys and non-string values. |
| 6 | `stripOptionsHtml` Map branch | **Defensive repair; production collision not demonstrated.** The original direct-Map fragment failed. Both tested native List and Map enum definitions normalized to List-of-Maps, reaching row 5. That observation does not prove all direct-Map responses impossible. Exact repaired-branch operation proof passed below. |
| 7 | `_submitAppDoneForm` explicit page value | **Reachable, repaired.** Native input `fields` and its stored false value were observed; the original explicit-value fragment failed. Earlier schema collection could mask this assignment. |
| 8 | `_submitAppDoneForm` default page value | **Reachable, repaired.** Native false/zero defaults were observed. The original fragment failed on `fields`; an ordinary key preserved zero. The repair retains non-null default selection. |
| 9 | `_submitAppDoneForm` chosen stored value | **Reachable, repaired.** Native stored `fields` supplies the key. The original chosen-value fragment failed; corresponding `liveSettings` and `cfgSettings` reads also use explicit Map access. |
| 10 | `_submitAppDoneForm` page/empty fallback | **Reachable, repaired.** The original page-value read/write fragment failed. The repair preserves membership checks, false/zero page values, and the existing empty fallback. |
| 11 | `applyDeviceMapping` scalar device ID | **Bounded.** The branch requires literal `deviceId`. The exact helper mapped the owned identifier and preserved the no-mapping fallback. |
| 12 | `applyDeviceMapping` device ID list | **Bounded.** The branch requires literal `deviceIds` and a List. Exact helper controls preserved list shape, null, and unmapped entries. |
| 13 | `applyDeviceMapping` recursive data | **Reachable, repaired.** The exact original helper rejected nested `fields`; import source passes supplied rule data into it. The legacy write gateway remained disabled during investigation. Necessary legacy maintenance uses unit coverage; legacy E2E/BAT coverage is frozen. |
| 14 | `_applyLegacyLayoutOps` tile patch | **Reproduced connector failure, repaired.** An owned dashboard rejected a `fields` tile patch while ordinary clock-tile creation succeeded. The repair preserves the exclusion of `id`. |
| 15 | `_rmCollectFilteredRmRules` inventory map | **Bounded.** IDs must convert to Integer and occur in the live app inventory. The owned scratch RM rule appeared in the normal rule list. |
| 16 | `_rmSubmitSubPageDone` settings copy | **Reachable, repaired.** Native page schema permits `fields`; the exact original read/write fragment failed. This is the distinct subpage payload path, with schema collection an earlier possible blocker. |
| 17 | `_rmSubmitMainPageDone` settings copy | **Reachable, repaired.** The original read/write fragment failed for a native input name. Generic app editing uses this separate main-page Done payload; complete settings preservation remains its semantic contract. |
| 18 | `_rmSubmitFullPageForm` stored value | **Defensive repair; production collision not demonstrated.** The original fragment failed on collision names. The only production caller submits RM `selectActions` for action deletion; its owned-rule control succeeded. Both stored-value read and copy now use explicit operations. Exact repaired-branch operation proof passed below. |
| 19 | `_rmSubmitFullPageForm` button value | **Defensive repair; production collision not demonstrated.** The original button-name fragment failed. A local variable named `fields` produced `embeddedActions`, not a collected schema input, so it did not establish this branch's collision reachability. Exact repaired-branch operation proof passed below. |
| 20 | `_rmSubmitFullPageForm` missing value | **Defensive repair; production collision not demonstrated.** The original fragment failed; the same sole RM caller limits the observed production schema. The repair retains empty values and `blankedInputs` reporting. Exact repaired-branch operation proof passed below. |
| 21 | `_rmSubmitFullPageForm` extra settings | **Bounded.** The sole caller supplies `extraSettings=[trashActs:stringIndices]`. Scratch clearActions succeeded. An arbitrary extra-settings snippet is not evidence of an exposed arbitrary-key caller. |
| 22 | `_applyNativeAppEdit` known settings | **Reproduced native edit failure, repaired.** Native schema accepts `fields`, but schema collection could fail before the original `knownSettings` assignment. The isolated assignment independently failed; membership filtering remains intact. |
| 23 | `toolGetRoom` current-state copy | **Reproduced silent omission, repaired.** The owned driver emitted `fields`; device details preserved it while room output omitted it and retained ordinary/`getClass` states. The room copy now preserves driver attribute names. |
| 24 | `_validateCoordinate` normalized argument | **Bounded.** Production callers supply only `latitude` and `longitude`. Exact helper controls converted both zero strings to numbers without changing location settings. |

These are eight bounded cases, twelve with reachable unsafe inputs, and four
defensive repairs whose exact production collision was not demonstrated. The
four defensive cases are not classified as safe or unreachable.

The exact-source before/after probe for rows 6 and 18-20 passed **96/96 checks**
on firmware **2.5.1.181** on 2026-09-09. Its 48 repaired-source cases preserved
ordinary, `fields`, `class`, `metaClass`, `Fields`, and `getClass` keys; 24
original-source collision cases produced the expected SecurityException, and
24 ordinary/noncolliding original-source controls succeeded. It checked HTML
label cleaning, false/zero/null/List values, empty buttons, missing-value
`blankedInputs`, and extra-settings overrides. Form construction and HTTP
submission were inert capture adapters; no real form was posted. The repaired
method sources came from `e07eb1f2a5b588afecbfd53f91b01f99d784ddee` and are unchanged
from merged `a4b968f5` at capture; original methods came from the immutable inventory baseline. The committed After fixture now has corrected failure/recovery wording; the measured Map operations are unchanged.
This closes the operation-proof gap without claiming unobserved native reachability.
## Original controls and adjacent paths

| Path | Recorded evidence and limits |
| --- | --- |
| `_publicToolResultValue` and device catalogs | Exact before source failed on nested `fields`; explicit-Map after source retained keys and false/zero/null/List values. Both installed device gateway catalogs succeeded with public `fields` schema properties intact. Existing E2E also checks those catalog schemas. |
| `_mrtrCanonicalArgs` | Exact before source failed on `fields` selections; the after source succeeded. The installed connector accepted legitimate projection selections, but the historical selection result alone does not establish negotiated modern transport. Existing modern MRTR E2E carries nested `fields`/`Fields`/`getClass` and rejects type-changing continuation replays. |
| `_deviceConfigurationPublicValue` and renamed equivalent | The deliberately unsafe bracket variant failed; exact explicit-Map implementation and renamed copy both retained nested values. This device-configuration helper lives in #411; integration must preserve its repair and the helper-independent scanner regression. |
| `_snapshotDeviceState`, currentStates | Exact before source returned null with the `fields` SecurityException; exact after source retained the owned driver's actual attribute names and values. |
| `_snapshotDeviceState`, supportedAttributes fallback | Same before/after outcome using the owned driver through an adapter forcing an empty currentStates collection. This proves the fallback copy operation with real attribute values, not that this driver naturally lacks currentStates. |
| `_snapshotBypassDeviceState` | Exact before source failed and after source retained owned native fullJson attribute values. The adapter restricted reads to the owned device. Its date helper returned strings, so this control does not prove production timestamp normalization. |
| `_rmRestoreFromBackup` saved schema | Additional `savedSchema[n]` write identified beyond the numbered device-list copy. It and the adjacent device-map reads were repaired; the earlier schema collector could mask later restore operations. |
| `_rmCollectInputSchema` | An owned native input named `fields` established key reachability and the original collector failure. Explicit Map writes remove this earlier blocker for app-edit and Done-form paths. |
| Dashboard `setOptions` | Owned `fields` option reproduced a connector failure independently of tile patch row 14. `layout[key]` now uses explicit Map access while retaining option restrictions. |
| Corresponding settings and attribute reads | `liveSettings`, `cfgSettings`, page values, restore maps, and bypass attributes were reviewed alongside assignments; read safety is not inferred from a clean write-only scan. |
| Fixed device/virtual-device attribute loops | These iterate finite literal attribute names, not arbitrary driver names. They remain bounded negative controls, as do Lists and numeric indices. |

Dynamic dot access was checked independently on the same firmware: **24/24**
controls returned the stored `false` value for typed/untyped reads and writes
using all six names. Therefore the observed bracket restriction must not be
extended to `map?."${key}"` or `map."${key}" = value`. Source inspection found
these forms in schema/settings reads, bounded sunrise/sunset reads, and local
variable reads. Dynamic device command invocation is a method call, not a Map
subscript. These are supported dismissals of the proposed bracket-to-dot
extrapolation, not uncompleted Map repairs.
The exact-branch investigation is cleaned up: its owned app instance and app
code were deleted, and all six owned source/backup files were removed. Readback
confirmed the original 113 app IDs remained unchanged, with no added or missing
apps and no investigation files left. No household device or rule was modified.

## Enforced scanner policy and validation

`check_sandbox_map_subscripts` enforces a source invariant: detected dynamic Map
subscripts are **errors that fail CI**, unless an applicable measured typed-read
exception or a local bounded-key proof applies. Measured literal collisions are
also blocking. This replaces the temporary nonblocking dynamic-warning policy.
A blocker means that the source must use explicit Map operations or establish
the supported exception; it does not label every match a reproduced live bug.

Receiver inference recognizes Map declarations and script fields, Map literals,
casts, checked Maps, empty-Map fallbacks, aliases, and observable Map returns
from explicitly typed and untyped helpers. It follows inferred helper returns
and aliases without depending on helper names, while separating parent/library
scope from the child app. Regression fixtures include implicit-return and
renamed helpers. Local literal branches and finite literal loops exclude
measured collision names before receiving a bounded-key exception; entire
methods are not exempted by name. Explicitly typed Map reads follow the measured
exception above; dynamic typed writes retain the `metaClass` hazard.

This source scanner is not an exhaustive Groovy type checker and cannot prove
arbitrary external helper return types or every interprocedural key constraint.
List indexing, numeric indices, comments, strings, and bounded-key controls must
remain free of false positives. Zero findings establishes the checked source
invariant, not universal safety of every possible Groovy expression.

The measured contract and rejection fixtures are distinct from semantic tests.
Python fixtures verify scanner policy; they do not run firmware. Spock runs
plain Groovy without Hubitat's `SandboxSubscriptGuard`: its value, shape, and
error assertions can pass with an original unsafe bracket operation. Existing
live E2E public-result and modern MRTR scenarios provide runtime evidence for
their exercised paths. No new legacy-rule E2E/BAT scenarios are added; necessary
legacy maintenance uses unit tests.

The predecessor revision `bf9e8e66b16e7ae87a1ec538a88f694e550c4533` completed
[full E2E](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/34400363202),
[the normal/strict × gateway/flat unit matrix](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/34400363297),
and [sandbox lint](https://github.com/kingpanther13/Hubitat-local-MCP-server/actions/runs/34400363307)
successfully. Those runs validate that revision and its then-current advisory
scanner, not the new blocking guard.

Exact-head CI results and the merge simulation with #411 are recorded in the
closing PR's Testing section. Test-hub deployment, fixture restoration, and
lease release are recorded there with the E2E run; they are distinct from the
manual source-copy evidence above. The merge order remains this prerequisite
first, then main into #411 with the blocking guard retained.
