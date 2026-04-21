# Testing

Groovy unit tests run under Spock + HubitatCI via the Gradle wrapper. CI runs `./gradlew test` on every PR and push to `main` (`.github/workflows/unit-tests.yml`).

- **Test framework:** Spock 2.3 on Groovy 3.0 (Hubitat's hub runtime is Groovy 2.4, and `eighty20results/hubitat_ci` actively tests its behaviour against that runtime — case-insensitive enum matching, locale-safe `toLowerCase`, etc. — so Groovy 3.0 stays the closest practical match).
- **Hubitat sandbox:** [eighty20results/hubitat_ci](https://github.com/eighty20results/hubitat_ci) — an actively-maintained Groovy 3.0 fork of the original [biocomp/hubitat_ci](https://github.com/biocomp/hubitat_ci) (Apache 2.0). Consumed as `com.github.eighty20results:hubitat_ci:<tag>` via JitPack; the pinned release tag in `build.gradle` is bumped by tracking issues from `.github/workflows/hubitat-ci-version-check.yml`. The fork natively maps `hubitat.helper.{RMUtils, ColorUtils, HexUtils, ZigbeeUtils}` to its own `me.biocomp.hubitat_ci.api.common_api.*` namespace inside `SandboxClassLoader`, so sandbox-loaded production code (e.g. PR #79's `toolListRmRules`) resolves those Hubitat platform classes without any main-source-set stubs on our side. Groovy 3 is module-aware, so the JDK 11+ `--add-opens` workaround from the Groovy-2.5 era is no longer needed.
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
2. `AppExecutor` is Spock-mocked to expose `state`, `atomicState`, `getChildDevices()`, `now()`, and `log` — eighty20results' `AppChildExecutor` leaves these on the `@Delegate` path to the supplied `AppExecutor`.
3. `hubInternalGet` isn't part of `AppExecutor`'s interface, so it's metaclass-injected on the loaded script along with the `Map`-backed `stateMap` / `atomicStateMap` / `HubInternalGetMock` wiring.
4. `addChildApp` and `getChildApps` are overridden on `script.metaClass` rather than on the `AppExecutor` mock. eighty20results' `HubitatAppScript` defines both as concrete methods routing through a private `childAppRegistry` / factory closures, which bypasses the mock's `@Delegate` path — so only a per-instance metaClass override intercepts the script-body dynamic dispatch that production tools use.
5. A no-op `childAppResolver` closure is passed into `sandbox.compile()` so eighty20results' options validator accepts the sandbox options; its body is never executed because the `addChildApp` metaClass override short-circuits before any real child-script resolution would occur.

Tool specs extend `ToolSpecBase` (which extends `HarnessSpec`) and add fixtures for `settings`, `getChildDevices()`, `getChildApps()`, and a deterministic `now()`.

### `RuleHarnessSpec` — base for rule-engine tests

`src/test/groovy/rules/RuleHarnessSpec.groovy`. Same shape as `HarnessSpec` but loads `hubitat-mcp-rule.groovy` instead of the server file, and exposes a mockable `parent` (the server reference the rule engine reads from for `findDevice` etc.).

`parent` is a writable property on the spec: specs assign it in their `given:` block (e.g. `parent = new SmokeParent(...)`), and the setter propagates the value to the loaded script via `HubitatAppScript.setParent()`. eighty20results' `HubitatAppScript` defines `parent` as a private field accessed via its `@CompileStatic` `getProperty("parent")` override, so mocking `AppExecutor.getParent()` no longer intercepts property access — `setParent()` is the only reliable hook.

### Mocks

- **`HubInternalGetMock`** — `register(path) { params -> response }`. Unstubbed paths throw `IllegalStateException` so tests fail loudly rather than silently returning null.
- **`NetworkUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.NetworkUtils.sendHubitatCommand(Map)`.
- **`RMUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.RMUtils.{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}`. `install()` mutates the static metaClass on **both** the test-classpath `hubitat.helper.RMUtils` stub and `me.biocomp.hubitat_ci.api.common_api.RMUtils` (eighty20results' SandboxClassLoader rewrites `hubitat.helper.RMUtils` to the latter when the reference appears inside sandbox-loaded production code). Test-side direct calls and PR #79-style sandbox-resolved calls both land on the mock. `RMUtilsSandboxInterceptionSpec` is the end-to-end regression.

### Test-only classpath stubs

`src/test/groovy/support/stubs/hubitat/helper/NetworkUtils.groovy` and `.../RMUtils.groovy` exist so that test-side Groovy references to those Hubitat platform classes can load under the test JVM without `ClassNotFoundException`. They ship only on the test classpath. Production code running under the sandbox hits the class via eighty20results' `SandboxClassLoader` rewrite — `hubitat.helper.RMUtils` is mapped to `me.biocomp.hubitat_ci.api.common_api.RMUtils` explicitly (alongside `ColorUtils`, `HexUtils`, and `ZigbeeUtils`), while other `hubitat.*` references fall through the default rewrite. `RMUtilsMock` dual-installs on both target classes; a future mock for another helper should follow the same pattern if sandbox-side interception is needed.

## Adding a new server tool spec

1. Create `src/test/groovy/server/Tool<Name>Spec.groovy` extending `support.ToolSpecBase`.
2. In `given:`, seed any device/child-app fixtures and settings flags the tool reads. Example: `settingsMap.enableHubAdminRead = true`, `childDevicesList << myMockDevice`.
3. Stub `hubGet.register(path) { ... }` for every internal endpoint the tool calls.
4. Call `script.tool<Name>(args)` in `when:`.
5. Assert on the return value AND any state mutations or mock interactions.
6. Always include at least one error-path test alongside the golden path.

## Recipe: testing PR #79's `manage_rule_machine` tools (RMUtils)

The `RMUtilsMock` is wired into the harness explicitly so PR #79's `manage_rule_machine` gateway tools can be unit-tested without a real hub. `install()` covers both the test-classpath `hubitat.helper.RMUtils` stub and the sandbox-mapped `me.biocomp.hubitat_ci.api.common_api.RMUtils` — sandbox-loaded production code and test-side direct calls both reach the mock. `RMUtilsSandboxInterceptionSpec` is the regression that keeps this dual-install working across eighty20results bumps.

Pattern:

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

`RMUtilsMock.install()` uses Groovy metaclass static-method injection on both `hubitat.helper.RMUtils` and `me.biocomp.hubitat_ci.api.common_api.RMUtils` to intercept `{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}` across test-side and sandbox-side call paths. Always pair it with `uninstall()` in `cleanup()` — cleanup uses `GroovySystem.metaClassRegistry.removeMetaClass()` on both classes for reliable teardown between specs (simply setting `metaClass = null` is unreliable because of class-metaclass caching).

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
