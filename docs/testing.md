# Testing

Groovy unit tests run under Spock + HubitatCI via the Gradle wrapper. CI runs `./gradlew test` on every PR and push to `main` (`.github/workflows/unit-tests.yml`).

- **Test framework:** Spock 2.3 on Groovy 3.0 (Hubitat's hub runtime is Groovy 2.4, and `eighty20results/hubitat_ci` actively tests its behaviour against that runtime — case-insensitive enum matching, locale-safe `toLowerCase`, etc. — so Groovy 3.0 stays the closest practical match).
- **Hubitat sandbox:** [eighty20results/hubitat_ci](https://github.com/eighty20results/hubitat_ci) — an actively-maintained Groovy 3.0 fork of the original [biocomp/hubitat_ci](https://github.com/biocomp/hubitat_ci) (Apache 2.0). Consumed as `com.github.eighty20results:hubitat_ci:<tag>` via JitPack; the pinned release tag in `build.gradle` is bumped by tracking issues from `.github/workflows/hubitat-ci-version-check.yml`. Sandbox-loaded production code that references `hubitat.helper.{RMUtils, NetworkUtils}` is routed through `support.PassThroughSandboxClassLoader` (installed via `support.PassThroughAppValidator`) so our literal-named stubs at `src/main/groovy/hubitat/helper/` resolve without hitting the JVM's §5.3.5 name-equality check — the raw-vs-remapped class mismatch that would otherwise NCDFE at runtime. Groovy 3 is module-aware, so the JDK 11+ `--add-opens` workaround from the Groovy-2.5 era is no longer needed.
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

1. `HubitatAppSandbox.run()` compiles `hubitat-mcp-server.groovy` with `support.PassThroughAppValidator` providing `Flags.DontValidatePreferences / DontValidateDefinition / DontRestrictGroovy / DontRunScript`. We use `run()` rather than `compile()` because `compile()` eagerly adds `DontRunScript` to `validationFlags`, which flips HubitatCI's `readValidator` precedence and silently discards the `validator:` option — meaning a custom `PassThroughAppValidator` would never fire. The validator carries `DontRunScript` itself so `setupImpl` still skips the auto-`script.run()` call; the multi-page `preferences { page(name: "mainPage") }` body HubitatCI's `AppPreferencesReader` mishandles stays unexecuted. All `def method() { }` handlers remain callable on the returned script.
2. `AppExecutor` is Spock-mocked to expose `state`, `atomicState`, `getChildDevices()`, `now()`, and `log` — eighty20results' `AppChildExecutor` leaves these on the `@Delegate` path to the supplied `AppExecutor`.
3. `hubInternalGet` isn't part of `AppExecutor`'s interface, so it's metaclass-injected on the loaded script along with the `Map`-backed `stateMap` / `atomicStateMap` / `HubInternalGetMock` wiring.
4. `addChildApp`, `getChildApps`, `getChildAppById`, `getChildAppByLabel`, and `deleteChildApp` are intercepted by reflecting into `HubitatAppScript`'s private `childAppFactory` and `childAppAccessor` closure fields and replacing them with harness closures that route to spec-controlled fixtures (`mockChildAppForCreate`, `childAppsList`). eighty20results' `HubitatAppScript` defines these as concrete methods that call those private closures internally, and per-instance `metaClass` overrides are bypassed by the script body's intra-class dispatch — verified empirically in PR #100's first CI run before the reflective approach replaced the metaClass attempt.
5. A `childAppResolver` closure is passed into `sandbox.run()` so eighty20results' options validator accepts the sandbox options; the closure throws `IllegalStateException` on call, because the reflective `childAppFactory` replacement should short-circuit first. If the resolver ever fires, it means an eighty20results API change broke the harness and the error points the spec author at the right place to look.

Tool specs extend `ToolSpecBase` (which extends `HarnessSpec`). `ToolSpecBase` is currently an empty marker class — the `settings` / `getChildDevices()` / `getChildApps()` / `now()` fixtures all live on `HarnessSpec` itself. The marker exists to keep the "this is a server tool spec" distinction explicit and to give us a place to add tool-test-specific helpers later.

### `RuleHarnessSpec` — base for rule-engine tests

`src/test/groovy/rules/RuleHarnessSpec.groovy`. Same shape as `HarnessSpec` but loads `hubitat-mcp-rule.groovy` instead of the server file, and exposes a mockable `parent` (the server reference the rule engine reads from for `findDevice` etc.).

`parent` is a writable property on the spec: specs assign it in their `given:` block (e.g. `parent = new SmokeParent(...)`), and the setter propagates the value to the loaded script via `HubitatAppScript.setParent()`. eighty20results' `HubitatAppScript` defines `parent` as a private field accessed via its `@CompileStatic` `getProperty("parent")` override, so mocking `AppExecutor.getParent()` no longer intercepts property access — `setParent()` is the only reliable hook.

### Mocks

- **`HubInternalGetMock`** — `register(path) { params -> response }`. Unstubbed paths throw `IllegalStateException` so tests fail loudly rather than silently returning null.
- **`NetworkUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.NetworkUtils.sendHubitatCommand(Map)`.
- **`RMUtilsMock`** — install/uninstall pattern. Records calls to `hubitat.helper.RMUtils.{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}`. `install()` mutates the static metaClass on `hubitat.helper.RMUtils` (the main-source-set stub at `src/main/groovy/hubitat/helper/RMUtils.groovy`); both test-side direct calls and sandbox-loaded production calls (the latter routed through `PassThroughSandboxClassLoader` bypass of eighty20results' remap) land on the mock. `RMUtilsSandboxInterceptionSpec` is the end-to-end regression proving the sandbox path works — bump that spec if an eighty20results upgrade breaks the PassThrough scaffold.
- **`McpRequestDriver`** — integration dispatch drive-through for `handleMcpRequest()`. Stages a JSON-RPC body, fires the request, captures the `render(Map)` envelope. Also supports `pushBodyThrowing(t)` to exercise the `request.JSON`-throws try/catch branch. Wired by `HarnessSpec` (see the integration dispatch section below).
- **`SubscriptionRecorder`** — records `subscribe(source, attribute, handler)` calls from the rule engine and replays them via `fireEvent(script, source, attribute, value)`. Defensive shallow-copies the event Map per handler so mutation doesn't leak across dispatches. Wired by `RuleHarnessSpec`.
- **`TestParent`** — common parent stub for rule-engine specs. Devices map + settings map + `findDevice(id as Long)`. Specs with additional lookup needs (variables, etc.) extend it.

### Main-source-set helper stubs

`src/main/groovy/hubitat/helper/RMUtils.groovy` and `.../NetworkUtils.groovy` are main-source-set stubs of Hubitat's platform helpers. They exist so both test-side Groovy references AND sandbox-loaded production code can resolve these classes under the test JVM. Sandbox-loaded references get here via `support.PassThroughSandboxClassLoader`, which bypasses eighty20results' standard `hubitat.X` → `me.biocomp.hubitat_ci.api.X` remap for the specific class names we stub. Main-source-set rather than test-source-set because `SandboxClassLoader`'s parent chain (AppClassLoader) sees Gradle's main source-set output but not the test classpath. Not deployed to the hub — `packageManifest.json` ships only `hubitat-mcp-server.groovy` and `hubitat-mcp-rule.groovy`. To add a new helper, stub at `src/main/groovy/hubitat/<pkg>/<Class>.groovy` AND add its FQN to `PassThroughSandboxClassLoader.PASSTHROUGH_NAMES`.

## Adding a new server tool spec

1. Create `src/test/groovy/server/Tool<Name>Spec.groovy` extending `support.ToolSpecBase`.
2. In `given:`, seed any device/child-app fixtures and settings flags the tool reads. Example: `settingsMap.enableHubAdminRead = true`, `childDevicesList << myMockDevice`.
3. Stub `hubGet.register(path) { ... }` for every internal endpoint the tool calls.
4. Call `script.tool<Name>(args)` in `when:`.
5. Assert on the return value AND any state mutations or mock interactions.
6. Always include at least one error-path test alongside the golden path.

## Which interception point to use (dispatch cheat sheet)

The HubitatCI / eighty20results stack has three distinct classes of method, and the right way to stub them differs by class. Using the wrong one silently no-ops and eats hours — this table is authoritative.

| Method class | Examples | How to stub | Why this one works |
|---|---|---|---|
| **Purely dynamic** (not declared anywhere in the eighty20results class hierarchy — resolved entirely through Groovy's dynamic dispatch; verified absent via `javap -p .../BaseExecutor.class` and `.../HubitatAppScript.class`) | `hubInternalGet`, `getRooms`, `getHubSecurityCookie`, `getAllGlobalConnectorVariables`, `getGlobalConnectorVariable`, `setGlobalConnectorVariable`, `uploadHubFile` | `script.metaClass.methodName = { ... }` in `given:` | No concrete inherited method exists, so Groovy's callsite dispatch has nothing to resolve except the metaClass. Per-instance EMC entries win. The harness uses this pattern for `hubInternalGet` (wired once in `wireScriptOverrides()` in `setup()`). |
| **Platform methods declared on `AppExecutor` / `BaseExecutor`** (and therefore also on every layer of eighty20results' delegate chain: `AppMappingsReader` → `AppDefinitionReader` → `AppPreferencesReader` → `AppSubscriptionReader` → `AppChildExecutor` → your `Mock(AppExecutor)`) | `httpPost`, `httpGet`, `httpPostJson`, `sendEvent`, `subscribe`, `schedule`, `runIn`, `sendSms`, `setLocationMode` | **Permanent `>>` stub in `setupSpec()`** that dispatches to a per-feature `@Shared Closure` handler (recipe below). | Two independent reasons other approaches fail:<br>• **metaClass routes are bypassed by the delegate chain** — each layer forwards via Groovy callsite to the next `delegate`; the final hop at `AppChildExecutor` uses `invokeinterface` directly on the mock proxy, skipping any per-instance or class-level metaClass overrides on the script (same intra-class-dispatch trap PR #100 hit with `addChildApp`).<br>• **`>>` stubs added in `given:` on a `@Shared` Mock are a Spock scoping issue, not a dispatch issue** — they don't propagate into the mock's interaction set across features.<br>• **`setupSpec` permanent stubs work** because they're registered in the mock's baseline interaction set before any feature runs. |
| **Concrete methods with private-closure routing in `HubitatAppScript`** | `addChildApp`, `getChildApps`, `getChildAppById`, `getChildAppByLabel`, `deleteChildApp` | Reflect into `HubitatAppScript`'s private `childAppFactory` / `childAppAccessor` closure fields and replace them — already wired by `HarnessSpec.wireScriptOverrides()`. Specs just assign `mockChildAppForCreate` and/or populate `childAppsList`. | eighty20results' `AppChildExecutor` excludes these from the `@Delegate` chain, and `HubitatAppScript` defines its own concrete implementations that route through private closure fields. Both per-instance metaClass and `AppExecutor` mock stubs added in `given:` are bypassed — reflective replacement of the closures is the only reliable path. See PR #100 discussion. |

### Recipe: stubbing `httpPost` (or any platform method on `BaseExecutor`)

```groovy
class ToolRoomsSpec extends ToolSpecBase {
    // Per-feature handler. Tests assign a closure in `given:`.
    @Shared Closure httpPostHandler = null

    def setupSpec() {
        // Permanent stub installed once, dispatches to the per-feature hook.
        // Must live on the @Shared Mock(AppExecutor) in setupSpec — per-feature
        // `>>` stubs added in given: on a @Shared mock do not fire reliably.
        appExecutor.httpPost(_, _) >> { args ->
            if (httpPostHandler) {
                httpPostHandler.call(args[0], args[1])
            }
        }
    }

    def cleanup() {
        httpPostHandler = null  // reset between features
    }

    def "create_room posts to /room/save and reports success"() {
        given:
        httpPostHandler = { Map params, Closure responseCb ->
            // emulate the hub's response shape (status + data) by calling
            // responseCb — the production code's `httpPost(params) { resp -> ... }`
            // body reads resp.data / resp.status inside that callback
            responseCb.call([status: 200, data: ''])
        }
        // ... rest of setup

        when:
        script.toolCreateRoom([name: 'Garage', confirm: true])

        then:
        // ... assertions
    }
}
```

### Anti-patterns (don't waste cycles re-trying these for `BaseExecutor` methods)

All of the below **look** plausible for `httpPost` / `httpGet` / other methods on `BaseExecutor`, but all **silently no-op**. If your stub isn't firing, first verify the method's declaration class before trying more variants. From a shell in the repo root:

```bash
cd ~/.gradle/caches/modules-2/files-2.1/com.github.eighty20results/hubitat_ci/v0.28.6/*
jar xf hubitat_ci-v0.28.6.jar
javap -p me/biocomp/hubitat_ci/api/common_api/BaseExecutor.class | grep <methodName>
javap -p me/biocomp/hubitat_ci/app/HubitatAppScript.class | grep <methodName>
```

If the method appears in either output, it's in class 2 of the cheat sheet above (platform method on `BaseExecutor` / concrete on `HubitatAppScript`) and needs the `setupSpec`-dispatcher pattern.

- `script.metaClass.httpPost = { ... }` — bypassed by intra-class dispatch.
- `HubitatAppScript.metaClass.httpPost = { ... }` — same.
- `script.getClass().metaClass.httpPost = { ... }` — same.
- `appExecutor.httpPost(_, _) >> { ... }` in `given:` — not routed into the @Shared Mock's interaction set.
- `1 * appExecutor.httpPost(_, _) >> { ... }` in `then:` — Spock `then:` interactions are consumed AFTER the `when:` block runs; they can't stub responses during the code-under-test's execution.
- Reflecting into `AppChildExecutor.delegate` and swapping it for a custom JDK `Proxy` — **works** but is a hack that bypasses hubitat_ci's intended mock surface. Use the `setupSpec` dispatcher pattern instead.

Reference spec that uses the `setupSpec` dispatcher pattern correctly: `src/test/groovy/server/ToolRoomsSpec.groovy` (covers `httpPost` for `create_room` / `delete_room` / `rename_room`).

## Recipe: testing PR #79's `manage_rule_machine` tools (RMUtils)

The `RMUtilsMock` is wired into the harness explicitly so PR #79's `manage_rule_machine` gateway tools can be unit-tested without a real hub. Sandbox-loaded production code reaches the mock via the `PassThroughSandboxClassLoader` → main-source-set stub → metaClass-installed mock chain; test-side direct calls hit the same stub and mock. `RMUtilsSandboxInterceptionSpec` keeps the full chain honest across eighty20results bumps.

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

`RMUtilsMock.install()` uses Groovy metaclass static-method injection on `hubitat.helper.RMUtils` to intercept `{getRuleList, sendAction, pauseRule, resumeRule, setRuleBoolean}`. Always pair it with `uninstall()` in `cleanup()` — cleanup uses `GroovySystem.metaClassRegistry.removeMetaClass()` for reliable teardown between specs (simply setting `metaClass = null` is unreliable because of class-metaclass caching).

## Integration dispatch drive-through (issue #77, tier-2)

The test suite is layered:

- **Tier 1 — unit specs.** Call tool / rule-primitive methods directly (`script.toolFoo(args)`, `script.handleToolsCall(msg)`, `script.handleDeviceEvent(evt)`). Fast, focused, and pinpoints regressions at tool granularity. This is the right default.
- **Tier 2 — integration dispatch specs (this section).** Drive the full in-process request-dispatch path (`request.JSON → handleMcpRequest → dispatch → render`) or the full rule install loop (`initialize → subscribeToTriggers → subscribe → fireEvent → handler → action`). Still one JVM, Spock Mocks for the hub runtime, no real HTTP.
- **Tier 3 — true E2E with a fake hub.** Out-of-process HTTP, real JSON-RPC over real HTTP, real sandbox classloader. **Not implemented.** Tracked under issue #77 as a research item.

Some seams only come alive at tier 2. Two are wired into the harness:

### 1. `handleMcpRequest()` — dispatch pipeline

`McpRequestDriver` lets a spec push a JSON-RPC body, invoke `handleMcpRequest()`, and read back the captured `render(...)` envelope. Covers the two hub-runtime seams that the JSON-RPC-layer specs skip:

- `request.JSON` — the sandbox dynamic property the hub populates with the parsed POST body. `handleMcpRequest()` wraps the read in try/catch to surface malformed bodies as JSON-RPC -32700 parse errors. The driver can stage either a staged body (`pushBody(...)`) OR a throwing access (`pushBodyThrowing(t)`) so both the `null`-body branch and the try/catch branch are reachable.
- `render(Map)` — the hub-side response writer. Production assembles status / contentType / data; driving `handleToolsCall()` directly bypasses this.

Wiring (in `HarnessSpec`):

- `render(Map)` is on `AppExecutor` (class-2 on the dispatch cheat sheet), stubbed via a `>>` dispatcher in `setupSpec()` that hands the Map to `McpRequestDriver.captureRender()`. A no-arg `render()` stub is also installed that throws — nothing in production calls it today, but if a future handler picked it up, silently dropping the call would let `parseResponseJson` read the previous test's state.
- `request` is resolved by `HubitatAppScript`'s own `@CompileStatic getProperty(String)` override, which checks `injectedMappingHandlerData['request']` before falling through to MOP (only when the *map itself* is null — not when the map lacks the `request` key). That short-circuit means a metaClass hook on the script is *never consulted* for this property. The driver's stable `ScriptRequestProxy` is installed directly into that private field via reflection in `wireScriptOverrides()`, which runs per-test in `setup()` (after the metaClass wipe). The proxy dispatches `getJSON()` dynamically at access time — tests can call `pushBody` / `pushBodyThrowing` from their `given:` block and have the change take effect without re-running the wire step. (Same dispatch trap as `parent` — see `RuleHarnessSpec`'s Javadoc for the parallel case.)

Spec pattern:

```groovy
class HandleMcpRequestDispatchSpec extends ToolSpecBase {
    def "initialize returns protocolVersion and serverInfo via render(Map)"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def response = mcpDriver.parseResponseJson()
        response.result.protocolVersion == '2024-11-05'
        response.result.serverInfo.name == 'hubitat-mcp-rule-server'
    }
}
```

`pushBody(null)` simulates a missing body (→ -32700 empty-body branch). `pushBodyThrowing(throwable)` simulates a hub-side JSON parse failure (→ -32700 try/catch branch). `pushBody([...])` (a List) exercises the batch-request branch.

### 2. `subscribe(...)` + event replay — rule-engine install loop

`SubscriptionRecorder` captures every `subscribe(source, attribute, handler)` call the rule engine makes during `subscribeToTriggers()` and exposes `fireEvent(script, source, attribute, value)` to dispatch a synthetic event back at the recorded handler. Covers gaps the direct-handler specs (`HandleDeviceEventSpec` etc.) can't reach:

- Did `subscribeToTriggers()` register the right subscription for a given trigger config? (A regression that silently dropped a subscribe call would pass every handler-body spec.)
- Does the handler route the event through to the rule's action on the right device?
- Does `initialize()`'s `settings.ruleEnabled` kill-switch actually skip subscribe-time?

The time / periodic trigger branches register via `schedule(cronExpr, handler)` and `runOnce(...)` rather than subscribe, so `RuleHarnessSpec` also records `scheduleCalls` (and `runOnceCalls`) for those assertions.

Wiring (in `RuleHarnessSpec`):

- `subscribe(_, _ as String, _ as String)` is stubbed via the `setupSpec` dispatcher pattern, forwarding into `SubscriptionRecorder.record()`. A wildcard `subscribe(*_)` stub is installed after it that throws on any other overload so unstubbed shapes fail loudly.
- `RuleHarnessSpec` adds `Flags.DontValidateSubscriptions` to the `PassThroughAppValidator` — without it the `AppSubscriptionReader` delegate layer rejects anything that isn't a full `DeviceWrapper` before the Mock's stub can fire. Note: this flag also bypasses handler-name validation, so pure wire-up asserts that check `subscriptions[0].handler == 'handleDeviceEvent'` compare a literal string to a literal string — add a `fireEvent()` call if you want the handler-name typo path covered.

Spec pattern:

```groovy
class SubscribeTriggerIntegrationSpec extends RuleHarnessSpec {
    def "firing a matching event routes through the handler to the action device"() {
        given:
        def sourceDevice = new TestDevice(id: 1, label: 'Front Door')
        def targetDevice = Spy(TestDevice) { getId() >> 99 }
        parent = new TestParent(devices: [1L: sourceDevice, 99L: targetDevice])
        settingsMap.ruleEnabled = true
        // deviceId MUST be a String — triggerMatchesDevice() does a strict == against
        // the stringified event.device.id (rule engine's triggerMatchesDevice function,
        // around the checkAllDevicesMatch block). Int deviceIds in the trigger never match.
        atomicStateMap.triggers = [[type: 'device_event', deviceId: '1', attribute: 'contact', value: 'open']]
        atomicStateMap.actions  = [[type: 'device_command', deviceId: 99, command: 'on']]
        script.subscribeToTriggers()

        when:
        subscriptions.fireEvent(script, sourceDevice, 'contact', 'open')

        then:
        1 * targetDevice.on()
    }
}
```

### When to reach for the integration dispatch layer

- **Use it when**: a behaviour depends on wiring (subscribe → handler → action, or initialize → subscribe), or on the HTTP shell (body parsing, render envelope, batch/notification semantics).
- **Don't use it when**: a tool-level spec would pinpoint the regression more tightly. Over-using this layer widens blast radius — a handler-body change breaks every integration spec that touches it, not just the one unit spec that owns that logic.

## Native Rule Machine access

Issue #69 originally asked whether HubitatCI could install and drive the real `RuleMachine.groovy` app as a mock app for deeper coverage. Two subsequent developments settle the question without needing a probe:

1. **PR #79 delivers the practical read + trigger surface** via `hubitat.helper.RMUtils` (list, run, pause, resume, set-boolean on RM 4.x and 5.x rules). `RMUtilsMock` is sufficient to unit-test those tools.
2. **Structured-data read access to native RM rules exists** via `/installedapp/configure/json/<id>[/<pageName>]` — the SDK-level config-page serializer Hubitat's Web UI consumes. Same stability tier as `/hub2/appsList`. Returns triggers/conditions/actions/settings/childApps as structured JSON for any built-in app using `dynamicPage()` (RM 5.0/5.1, Room Lighting, HPM, Mode Manager, Button Controllers, Basic Rules). Future MCP tools that surface full RM rule definitions would stub this endpoint via the existing `HubInternalGetMock` — no new harness work needed.

Mock-installing the real `RuleMachine.groovy` under HubitatCI is moot: Hubitat's built-in apps ship as compiled bytecode in the hub firmware; source code is not published by Hubitat, so there's nothing for `HubitatAppSandbox` to load. The two paths above cover the actual capability need.

## Adding a new rule-engine spec

1. Create `src/test/groovy/rules/<Feature>Spec.groovy` extending `rules.RuleHarnessSpec`.
2. In `given:`, assign `parent = new SomeParentStub(...)` if the code path uses `parent.findDevice(...)` etc. The setter propagates into the script.
3. Seed `stateMap`, `atomicStateMap`, or `settingsMap` as needed.
4. Call `script.<method>(args)` in `when:`.
5. Assert on return value, state mutations, or Spy interactions on the stubbed parent/device.

## Adding a new Hubitat helper stub

Some production code references platform helper classes like `hubitat.helper.RMUtils`. The sandbox can't resolve these at runtime out of the box — eighty20results remaps the name, but the JVM's §5.3.5 name-equality check rejects the remapped class. To make a new helper resolvable:

1. Stub the class at `src/main/groovy/hubitat/<pkg>/<Class>.groovy`. Method signatures should mirror the real Hubitat API surface your production code uses. Return stub/no-op values — real behaviour is wired per-test via a metaClass-based mock (see `RMUtilsMock` for the pattern).
2. Add the FQN (e.g. `'hubitat.helper.FooUtils'`) to `support.PassThroughSandboxClassLoader.PASSTHROUGH_NAMES`.
3. If you want per-test behaviour mocking, add a `FooUtilsMock` in `src/test/groovy/support/` mirroring `RMUtilsMock`'s shape. Use `GroovySystem.metaClassRegistry.removeMetaClass()` in `uninstall()` for reliable teardown.
4. Add a `FooUtilsSandboxInterceptionSpec` modeled on `RMUtilsSandboxInterceptionSpec` if sandbox-side interception matters for your tools; it's the bump-canary when eighty20results upgrades.

## CI

- Workflow: `.github/workflows/unit-tests.yml`
- Triggers on `pull_request` targeting `main` and `push` to `main`
- JDK 17 (Temurin) via `actions/setup-java@v5`
- Gradle wrapper cached by `gradle/actions/setup-gradle@v6`
- Runs `./gradlew test --info --warning-mode all`
- Uploads `build/reports/tests/test/` as the `test-report` artifact on failure
