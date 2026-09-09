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
or driver code`. Explicit get/put retained false, zero, null, lists, and nested
Maps in the tested copy paths. Case matters: `Fields` is not `fields`. Names such
as `properties`, `empty`, and `keys` are not part of this measured collision set.

Native enum options preserved colliding names in list-of-Map entries. Both native
List and Map enum definitions arrived as that list shape; direct-Map option
branches failed in isolation but their production wire reachability was not
established. Across the original 24 investigation rows, eight had bounded keys,
twelve had reachable unsafe inputs (sometimes behind earlier failures), and four
had isolated failures without proven reachability of the exact production branch.
This is not a claim of 24 reproduced end-to-end bugs.

## What the checks establish

- `check_sandbox_map_subscripts` is a **partial, advisory source scanner** for
  dynamic keys. Measured literal collisions are errors and fail CI; dynamic-key
  candidates are warnings and **do not fail CI**. Zero warnings is not proof that
  every Map access in the repository is safe. This PR improves identified paths;
  it does not complete class-wide enforcement for issue #415.
- Receiver inference recognizes Map declarations (including script fields),
  literals, casts, checked Maps, empty-Map fallbacks, and aliases of these or of
  explicitly Map-returning methods. It does not infer an arbitrary untyped
  helper's return type or prove interprocedural key bounds. Single-parameter
  closures and untyped parameters can supply candidate keys; fixed literal
  attribute loops remain controls. This is a regex scan, not Groovy type analysis.
- Explicitly typed Map reads are intentionally exempt under the table above;
  typed writes still have the `metaClass` hazard. List indexing is not Map access.
  Dynamic dot expressions such as `schema?."${key}"` are outside this subscript
  rule. They were not converted here; this is not a claim that arbitrary dot
  access is universally safe.
- Python fixtures pin the scanner's policy and inference. Their acceptance of
  `Fields` and `getClass` records the measured exemption; it does not independently
  test firmware behavior.
- Spock runs plain Groovy without Hubitat's `SandboxSubscriptGuard`. The value,
  shape, and error-contract assertions detect semantic regressions, but can pass
  with the original bracket access. They do not prove the sandbox fix.
- The existing live E2E public-result and MRTR scenarios carry nested
  `fields` / `Fields` / `getClass` data and verify value types and continuation
  binding on the test hub. They exercise those repaired paths, not every scanner
  exemption or every repaired boundary. No additional legacy-rule E2E/BAT
  scenarios are added; necessary legacy maintenance is covered with unit tests.
