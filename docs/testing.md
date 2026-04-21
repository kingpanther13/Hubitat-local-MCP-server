# Testing

Groovy unit tests run under Spock + HubitatCI via the Gradle wrapper. CI runs `./gradlew test` on every PR and push to `main` (`.github/workflows/unit-tests.yml`).

- **Test framework:** Spock 2.3 on Groovy 2.5 (matches the Hubitat hub runtime)
- **Hubitat sandbox:** [joelwetzel/hubitat_ci](https://github.com/joelwetzel/hubitat_ci) — an actively-maintained fork of the original [biocomp/hubitat_ci](https://github.com/biocomp/hubitat_ci) (Apache 2.0). Consumed as `com.github.joelwetzel:hubitat_ci:<sha>` via JitPack; the pinned SHA in `build.gradle` is bumped by tracking issues from `.github/workflows/hubitat-ci-version-check.yml`.
- **JVM:** OpenJDK 17 in CI; locally, JDK 11+ via the Gradle toolchain
- **Build:** Gradle 8.10 via wrapper (`./gradlew test`)

## Running locally

```bash
./gradlew test                                # all tests
./gradlew test --tests "*ToolGetHubLogsSpec"  # a single spec
./gradlew test --info                         # verbose output
```

HTML report at `build/reports/tests/test/index.html`. CI uploads it as the `test-report` artifact on failure.

## Architecture

### `HarnessSpec` — base for server tool tests

`src/test/groovy/support/HarnessSpec.groovy`. On each spec's `setup()`:

1. `HubitatAppSandbox.compile()` compiles `hubitat-mcp-server.groovy` without running its `preferences { }` / `definition { }` blocks. (Those blocks use the multi-page form `page(name: "mainPage")` which HubitatCI's `AppPreferencesReader` mishandles — `compile()` sidesteps the issue cleanly. All `def method() { }` handlers remain callable on the returned script.)
2. `AppExecutor` is Spock-mocked to expose `state` and `atomicState`.
3. Groovy metaclass overrides on the loaded script bind `Map`-backed `stateMap` / `atomicStateMap` and the `HubInternalGetMock` to `state` / `atomicState` / `hubInternalGet`.

Tool specs extend `ToolSpecBase` (which extends `HarnessSpec`) and add fixtures for `settings`, `getChildDevices()`, `getChildApps()`, and a deterministic `now()`.

### `RuleHarnessSpec` — base for rule-engine tests

`src/test/groovy/rules/RuleHarnessSpec.groovy`. Same shape as `HarnessSpec` but loads `hubitat-mcp-rule.groovy` instead of the server file, and exposes a mockable `parent` (the server reference the rule engine reads from for `findDevice` etc.).

### Mocks

- **`HubInternalGetMock`** — `register(path) { params -> response }`. Unstubbed paths throw `IllegalStateException` so tests fail loudly rather than silently returning null.
- **`NetworkUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.NetworkUtils.sendHubitatCommand(Map)`.
- **`RMUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.RMUtils.{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}`.

### Build-time stubs for Hubitat platform helper classes

`src/main/groovy/hubitat/helper/{NetworkUtils,RMUtils}.groovy` are stub classes that exist so production code that references those Hubitat platform classes can compile and load under both the test JVM and HubitatCI's sandbox classloader. They live on the **main** source-set (not `src/test/groovy/support/stubs/`) because HubitatCI's `SandboxClassLoader` parent chain only sees main outputs via AppClassLoader. **Not deployed**: `packageManifest.json` ships only the two top-level `.groovy` files via raw GitHub URLs; nothing under `src/main/groovy/` reaches a real hub.

Mirror stubs at `src/main/groovy/me/biocomp/hubitat_ci/api/helper/*` exist as fallbacks for code paths that go through `SandboxClassLoader.mapClassName`'s default catch-all (`hubitat.X` → `me.biocomp.hubitat_ci.api.X`). They delegate to the literal `hubitat.helper.*` versions so `RMUtilsMock`'s metaClass interception captures calls regardless of which path entered.

### `PassThroughSandboxClassLoader` + `PassThroughAppValidator` (sandbox-loaded helper resolution)

When sandbox-loaded production code statically references `hubitat.helper.RMUtils` (e.g., PR #79's `toolListRmRules`), the stock `SandboxClassLoader` remaps the lookup to `me.biocomp.hubitat_ci.api.helper.RMUtils` — and the JVM (hotspot's `SystemDictionary::load_instance_class`) rejects the result with `NoClassDefFoundError` because the returned class's name doesn't match the requested one. `support.PassThroughSandboxClassLoader` subclasses `SandboxClassLoader` and bypasses `mapClassName` for our specific helper-class names; `support.PassThroughAppValidator` threads it into HubitatCI's compile path via the `validator:` option.

**Critical usage gotcha**: must use `sandbox.run(...)`, NOT `sandbox.compile(...)`. `compile()` calls `addFlags(options, [Flags.DontRunScript])` which makes `options.validationFlags` non-empty, and `HubitatAppSandbox.readValidator` then takes its first branch and silently constructs a fresh default `AppValidator` — discarding the user-supplied `validator:`. Effectively a HubitatCI bug; upstream tests never combine `compile()` with `validator:`. Include `Flags.DontRunScript` in the validator's own constructor flags so `setupImpl`'s `hasFlag(DontRunScript)` check still skips the auto-`script.run()`. See `RMUtilsSandboxResolutionSpec` for the worked example and `PassThroughAppValidator`'s class doc for the full analysis.

## Adding a new server tool spec

1. Create `src/test/groovy/server/Tool<Name>Spec.groovy` extending `support.ToolSpecBase`.
2. In `given:`, seed any device/child-app fixtures and settings flags the tool reads. Example: `settingsMap.enableHubAdminRead = true`, `childDevicesList << myMockDevice`.
3. Stub `hubGet.register(path) { ... }` for every internal endpoint the tool calls.
4. Call `script.tool<Name>(args)` in `when:`.
5. Assert on the return value AND any state mutations or mock interactions.
6. Always include at least one error-path test alongside the golden path.

## Recipe: testing PR #79's `manage_rule_machine` tools (RMUtils)

The `RMUtilsMock` is wired into the harness explicitly so PR #79's `manage_rule_machine` gateway tools can be unit-tested without a real hub. Pattern:

```groovy
import support.RMUtilsMock
import support.ToolSpecBase

class ToolListRmRulesSpec extends ToolSpecBase {
    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.stubRuleList = [[id: 1L, label: 'Test Rule']]
        rmUtils.install()
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "list_rm_rules returns mocked rule list"() {
        given:
        settingsMap.enableBuiltinAppTools = true  // PR #79's opt-in gate

        when:
        def result = script.handleGateway('manage_rule_machine', 'list_rm_rules', [:])

        then:
        rmUtils.calls.any { it.method == 'getRuleList' }
    }
}
```

`RMUtilsMock.install()` uses Groovy metaclass static-method injection to intercept calls to `hubitat.helper.RMUtils.{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}`. Always pair it with `uninstall()` in `cleanup()` — cleanup uses `GroovySystem.metaClassRegistry.removeMetaClass()` for reliable teardown between specs (simply setting `metaClass = null` is unreliable because of class-metaclass caching).

## Native Rule Machine access

Issue #69 originally asked whether HubitatCI could install and drive the real `RuleMachine.groovy` app as a mock app for deeper coverage. Two subsequent developments settle the question without needing a probe:

1. **PR #79 delivers the practical read + trigger surface** via `hubitat.helper.RMUtils` (list, run, pause, resume, set-boolean on RM 4.x and 5.x rules). `RMUtilsMock` is sufficient to unit-test those tools.
2. **Structured-data read access to native RM rules exists** via `/installedapp/configure/json/<id>[/<pageName>]` — the SDK-level config-page serializer Hubitat's Web UI consumes. Same stability tier as `/hub2/appsList`. Returns triggers/conditions/actions/settings/childApps as structured JSON for any built-in app using `dynamicPage()` (RM 5.0/5.1, Room Lighting, HPM, Mode Manager, Button Controllers, Basic Rules). Future MCP tools that surface full RM rule definitions would stub this endpoint via the existing `HubInternalGetMock` — no new harness work needed.

Mock-installing the real `RuleMachine.groovy` under HubitatCI is moot: Hubitat's built-in apps ship as compiled bytecode in the hub firmware; source code is not published by Hubitat, so there's nothing for `HubitatAppSandbox` to load. The two paths above cover the actual capability need.

## CI

- Workflow: `.github/workflows/unit-tests.yml`
- Triggers on `pull_request` targeting `main` and `push` to `main`
- JDK 17 (Temurin) via `actions/setup-java@v4`
- Gradle wrapper cached by `gradle/actions/setup-gradle@v4`
- Runs `./gradlew test --info`
- Uploads `build/reports/tests/test/` as the `test-report` artifact on failure
