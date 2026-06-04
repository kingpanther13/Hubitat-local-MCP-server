# Additive Groovy 2.5 Spock lane (issues #227 + #230)

Canonical design note for the `ci/groovy2x-spock/` CI lane. Pairs with
[#227](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/227) (parser divergence)
and [#230](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/230) (runtime divergence).

## The problem: CI Groovy ≠ hub Groovy

Hubitat Elevation runs **Groovy 2.4.x** (antlr2 parser). The unit-test harness runs **Groovy 3.0**
(`eighty20results/hubitat_ci` + `spock-core:2.3-groovy-3.0`). Groovy 3.0's Parrot parser and runtime
are more permissive than the hub's, so code can be fully green in CI yet fail on a real hub. Two
distinct failure classes:

- **Load-time / syntax** (issue #227) — e.g. a bare `{ ... }` block after a `case X:` label compiles
  under Parrot but is rejected by the hub's antlr2 parser. Already gated by the **Groovy 2.4 Parse
  Check** lane (`ci/groovy24-parse/`, shipped in PR2a), which parses the two production `.groovy`
  files under stock Groovy 2.4.21.
- **Runtime behaviour** (issue #230) — 2.x-vs-3.0 differences that only surface when the code *runs*
  (coercions, GDK method differences, default-parameter and metaclass dispatch). The parse lane
  cannot see these.

This lane closes the runtime gap, and reinforces the parse gap: running the specs under Groovy 2.5
compiles and loads the production files through the sandbox under the 2.5 (default antlr2) grammar,
so it is a strict superset of the parse concern.

## The three lanes (all additive, none replace each other)

| Lane | Groovy | hubitat_ci fork | Catches | Gating |
|---|---|---|---|---|
| Unit Tests (`unit-tests.yml`) | 3.0 | eighty20results | full behaviour on 3.0 | **required** (`test`) |
| Groovy 2.4 Parse (`groovy24-parse.yml`) | 2.4.21 | none (parse only) | load-time/syntax (#227) | required-adjacent |
| **Groovy 2.5 Spock (`groovy2x-spock.yml`)** | 2.5 | joelwetzel | **runtime divergence (#230)** + parse | **allow-failure** |

## Why joelwetzel/hubitat_ci on Groovy 2.5

The hub is Groovy 2.4, but there is no `spock-core:*-groovy-2.4` for Spock 2.x; **Groovy 2.5 is the
closest viable runtime** (it still defaults to the antlr2 parser, unlike 3.0's Parrot). Fork survey
(local git history + a fresh web/JitPack audit):

- **joelwetzel/hubitat_ci** — fork of biocomp, retains biocomp's public API exactly, stays on Groovy
  2.5. Consumed via JitPack by commit SHA (`com.github.joelwetzel:hubitat_ci:a62a351eed`); JitPack
  build for that SHA is verified green. This is the harness the repo itself used before migrating to
  eighty20results, so the configuration is proven in-repo.
- **biocomp/hubitat_ci** (original) — correct target stack but JitPack publish is broken for every
  tag (artifact lands at `build:unspecified`); not on Maven Central. Unusable as-is.
- **eighty20results/hubitat_ci** — the root lane's fork; Groovy 3.0, wrong runtime for this lane.

`spock-core:2.3-groovy-2.5` is the **same Spock version (2.3)** the root lane uses, just the
Groovy-2.5 binding — so the existing Spock 2.x spec corpus compiles unchanged (no JUnit4 down-port,
no `@TempDir` rewrite). The only real differences are the Groovy 2.5-vs-3.0 language/runtime and the
joelwetzel-vs-eighty20results harness API.

## Architecture: additive, zero-touch (issue #230 hard requirement)

Everything new lives under `ci/groovy2x-spock/`. The root `build.gradle`, `src/test/groovy/support/`
scaffold, and all existing workflows stay **byte-untouched**.

- **Isolated Gradle build** (`ci/groovy2x-spock/build.gradle`, own `settings.gradle`) — joelwetzel +
  `groovy-all:2.5.23` + `spock-core:2.3-groovy-2.5` + the same transitive mirrors the root build
  needs (`http-builder`, `quartz`, `chromecast`), JDK 11 toolchain, and the Groovy-2.5 `--add-opens`
  block (which eighty20results dropped because Groovy 3 is module-aware).
- **Live specs, referenced read-only** — an `importSpecs` `Sync` task copies the entire
  `src/test/groovy` corpus into the lane's build dir at build time (originals never edited;
  "written once" per #230), so the lane always runs the same evolving specs.
- **Lane-local scaffold variant** (`ci/groovy2x-spock/scaffold/`) — only the two harness bases whose
  fork API diverges: `support/HarnessSpec.groovy` and `rules/RuleHarnessSpec.groovy`. joelwetzel uses
  biocomp's `@Delegate`→`AppExecutor` wiring for `getChildApps`/`addChildApp`/`getParent`, so these
  wire through the AppExecutor mock instead of eighty20results' reflective
  `childAppFactory`/`childAppAccessor` replacement, `childAppResolver`, and `setParent`.
- **PassThrough is kept, not dropped.** joelwetzel doesn't *remap* `hubitat.helper.*`, but the sandbox
  classloader still needs `PassThroughAppValidator` + `sandbox.run()` to force those names to resolve
  from the parent classloader (the `src/main/groovy/` stubs) — otherwise `RMUtilsMock`'s
  static-metaclass injection lands on a different class instance than the one the sandbox loads
  (RM tools silently see no rules) and `hubitat.helper.NetworkUtils` isn't visible at all (NCDFE in
  the diagnostics tools). All other support classes (`RMUtilsMock`, `McpRequestDriver`, `TestDevice`,
  the PassThrough pair, …) are shared read-only.
- **Two lane self-tests** (`scaffold/support/LaneChildAppWiringSpec`, `scaffold/rules/RuleParentWiringSpec`)
  assert the fork-specific wiring the lane depends on — that `getChildApps`/`addChildApp`/`deleteChildApp`
  and `parent` actually route through the AppExecutor `@Delegate` under joelwetzel — so a future fork
  bump that broke that routing fails loudly here instead of letting tool specs pass against a no-op stub.
- **Two specs are excluded** as not fork-portable (each hardcodes eighty20results-only sandbox API;
  the Groovy 3.0 lane covers both): `server/ToolManageVirtualDeviceSpec.groovy` reflects the private
  `childDeviceFactory` field, and `support/RMUtilsSandboxInterceptionSpec.groovy` passes the
  `childAppResolver:` option that joelwetzel's `sandbox.run()` rejects (its self-test value — that
  sandbox-loaded `hubitat.helper.RMUtils` calls reach `RMUtilsMock` — is instead proven by the RM
  tool specs passing).
- **Allow-failure** — `continue-on-error: true`; the lane is informational until the 2.5 corpus is
  fully green and is **not** the ruleset's required `test` check, so it never blocks merge.

## Running locally

```bash
./gradlew -p ci/groovy2x-spock test          # full corpus under Groovy 2.5
./gradlew -p ci/groovy2x-spock test --tests "server.ToolListDevicesSpec"
```

JDK 11 must be discoverable by the toolchain resolver (the lane targets Java 11; Groovy 2.5 is
unhappy on JDK 17/21).

## Maintenance

The joelwetzel SHA is a **manual pin**: the existing `.github/workflows/hubitat-ci-version-check.yml`
only tracks the root build's eighty20results *tag* (joelwetzel cuts no tags), so bump
`ci/groovy2x-spock/build.gradle` by hand when adopting fork fixes. If a spec fails only on the 2.5
lane, it is a real 2.x-vs-3.0 runtime divergence (or a hub-relevant fork-behaviour gap) — investigate
before dismissing.

The corpus already passes fully under Groovy 2.5 (locally and in the lane's first CI run); the lane is
kept on `continue-on-error` deliberately, since Actions runners differ from a dev box and the fork's
stability there is young. Once it has a track record, flip `continue-on-error` off to promote it to a
gate.
