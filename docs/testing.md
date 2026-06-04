# Testing

Groovy unit tests run under Spock + HubitatCI via the Gradle wrapper. CI runs `./gradlew test` on every PR and push to `main` (`.github/workflows/unit-tests.yml`).

- **Test framework:** Spock 2.3 on Groovy 3.0 (Hubitat's hub runtime is Groovy 2.4, and `eighty20results/hubitat_ci` actively tests its behaviour against that runtime — case-insensitive enum matching, locale-safe `toLowerCase`, etc. — so Groovy 3.0 stays the closest practical match).
- **Hubitat sandbox:** [eighty20results/hubitat_ci](https://github.com/eighty20results/hubitat_ci) — an actively-maintained Groovy 3.0 fork of the original [biocomp/hubitat_ci](https://github.com/biocomp/hubitat_ci) (Apache 2.0). Consumed as `com.github.eighty20results:hubitat_ci:<tag>` via JitPack; the pinned release tag in `build.gradle` is bumped by tracking issues from `.github/workflows/hubitat-ci-version-check.yml`. Sandbox-loaded production code that references `hubitat.helper.{RMUtils, NetworkUtils}` is routed through `support.PassThroughSandboxClassLoader` (installed via `support.PassThroughAppValidator`) so our literal-named stubs at `src/main/groovy/hubitat/helper/` resolve without hitting the JVM's §5.3.5 name-equality check — the raw-vs-remapped class mismatch that would otherwise NCDFE at runtime. Groovy 3 is module-aware, so the JDK 11+ `--add-opens` workaround from the Groovy-2.5 era is no longer needed.
- **JVM:** OpenJDK 17 in CI; locally, JDK 11+ via the Gradle toolchain
- **Build:** Gradle 9.5.1 via wrapper (`./gradlew test`)

### Additive hub-closer lanes

Two standalone lanes complement the primary Groovy 3.0 lane without touching it — Hubitat's hub
runtime is Groovy 2.4.x, so a 3.0-green can still hide hub failures:

- **Groovy 2.4 Parse Check** (`ci/groovy24-parse/`) — parses the two production `.groovy` files under
  stock Groovy 2.4.21 (antlr2), catching 3.0-only syntax that would fail to load on the hub (issue #227).
- **Groovy 2.5 Spock** (`ci/groovy2x-spock/`) — runs this same spec corpus against a Groovy 2.5
  runtime via [joelwetzel/hubitat_ci](https://github.com/joelwetzel/hubitat_ci) (the biocomp-API fork
  the harness used before the eighty20results migration; Apache 2.0), catching 2.x-vs-3.0 **runtime**
  divergence (issue #230). Allow-failure; references the specs read-only and carries its own
  joelwetzel-shaped `HarnessSpec`/`RuleHarnessSpec` under `ci/groovy2x-spock/scaffold/`. See
  [docs/groovy2x-spock-lane.md](groovy2x-spock-lane.md). Run locally with
  `./gradlew -p ci/groovy2x-spock test`.

## Running locally

```bash
./gradlew test                                # all tests
./gradlew test --tests "*ToolGetHubLogsSpec"  # a single spec
./gradlew test --info                         # verbose output
```

HTML report at `build/reports/tests/test/index.html`. CI uploads it as the `test-report` artifact on failure.

## Architecture

### `HarnessSpec` — base for server tool tests

`src/test/groovy/support/HarnessSpec.groovy`. Lifecycle:

1. **First spec class to run** triggers `HubitatAppSandbox.run()` once and caches the compiled script in a JVM-static `SHARED_SCRIPT`. The compile uses `support.PassThroughAppValidator` providing `Flags.DontValidatePreferences / DontValidateDefinition / DontRestrictGroovy / DontRunScript`. We use `run()` rather than `compile()` because `compile()` eagerly adds `DontRunScript` to `validationFlags`, which flips HubitatCI's `readValidator` precedence and silently discards the `validator:` option — meaning a custom `PassThroughAppValidator` would never fire. The validator carries `DontRunScript` itself so `setupImpl` still skips the auto-`script.run()` call; the multi-page `preferences { page(name: "mainPage") }` body HubitatCI's `AppPreferencesReader` mishandles stays unexecuted. All `def method() { }` handlers remain callable on the returned script.
2. **Every spec's `setupSpec`** builds its own `AppExecutor` Mock (Spock 2.x ties Mocks to their creating Spec's MockController via thread-local registration, so a JVM-shared Mock can't accept setupSpec stub additions from subsequent specs — a Mock created in spec A's setupSpec has no controller wired up when spec B's setupSpec runs). The fresh Mock is reflectively rebound onto `SHARED_SCRIPT.api` via the `HubitatAppScript.api` private field, replacing the previous spec's Mock. The script's `userSettingsMap` / `preferencesReader` wiring stays valid because the underlying Map references are JVM-shared (`SHARED_STATE_MAP`, `SHARED_SETTINGS_MAP`, etc.).
3. The Mock exposes `state`, `atomicState`, `getChildDevices()`, `now()`, `log`, and `getSettings()` — eighty20results' `AppChildExecutor` leaves these on the `@Delegate` path to the supplied `AppExecutor`. `getSettings()` must be stubbed explicitly on the per-spec Mock because sandbox.run()'s implicit wiring of `api.getSettings()` only applies to the originally-bound Mock; rebinding swaps that wiring out.
4. **Every test's `setup()`** clears the JVM-shared collection refs (`stateMap`, `atomicStateMap`, etc.), resets `mcpDriver` / `hubGet`, and wipes **both** the class-level metaClass (`GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())`) **and** the per-instance metaClass (`script.setMetaClass(null)`). Both wipes matter when `SHARED_SCRIPT` is reused across spec classes: per-instance ExpandoMetaClass overrides like `script.metaClass.mcpLog = closure` survive class-level removal and would otherwise leak into subsequent specs. `wireScriptOverrides()` then re-installs the standard per-test hooks.
5. `hubInternalGet` isn't part of `AppExecutor`'s interface, so it's metaclass-injected on the loaded script in `wireScriptOverrides()` along with the `Map`-backed `stateMap` / `atomicStateMap` / `HubInternalGetMock` wiring.
6. `addChildApp`, `getChildApps`, `getChildAppById`, `getChildAppByLabel`, and `deleteChildApp` are intercepted by reflecting into `HubitatAppScript`'s private `childAppFactory` and `childAppAccessor` closure fields and replacing them with harness closures that route to spec-controlled fixtures (`mockChildAppForCreate`, `childAppsList`). eighty20results' `HubitatAppScript` defines these as concrete methods that call those private closures internally, and per-instance `metaClass` overrides are bypassed by the script body's intra-class dispatch — verified empirically in PR #100's first CI run before the reflective approach replaced the metaClass attempt.
7. A `childAppResolver` closure is passed into `sandbox.run()` so eighty20results' options validator accepts the sandbox options; the closure throws `IllegalStateException` on call, because the reflective `childAppFactory` replacement should short-circuit first. If the resolver ever fires, it means an eighty20results API change broke the harness and the error points the spec author at the right place to look.

Spec authors don't see most of this — extend `ToolSpecBase`, use `appExecutor`/`script`/`stateMap` as usual, and the per-JVM caching is transparent. The one rule for harness maintainers: don't add `getApp() >> X` to the base Mock; several specs already layer their own additive `getApp() >> sharedAppStub` in setupSpec and a base stub would stack against them with Spock-version-dependent resolution order.

Tool specs extend `ToolSpecBase` (which extends `HarnessSpec`). `ToolSpecBase` is currently an empty marker class — the `settings` / `getChildDevices()` / `getChildApps()` / `now()` fixtures all live on `HarnessSpec` itself. The marker exists to keep the "this is a server tool spec" distinction explicit and to give us a place to add tool-test-specific helpers later.

### `RuleHarnessSpec` — base for rule-engine tests

`src/test/groovy/rules/RuleHarnessSpec.groovy`. Same JVM-cache + per-spec-Mock + reflective-api-rebind pattern as `HarnessSpec`, but loads `hubitat-mcp-rule.groovy` instead of the server file, and exposes a mockable `parent` (the server reference the rule engine reads from for `findDevice` etc.).

One asymmetry to know about: the mutable collection fields (`settingsMap` / `stateMap` / `atomicStateMap`) are per-spec-instance `@Shared` fields on `RuleHarnessSpec` rather than JVM-static like `HarnessSpec`'s `SHARED_*` constants — the script-cache and per-spec-Mock rebind pattern is the same. `sandbox.run()` captures the FIRST subclass's `settingsMap` into eighty20results' `AppPreferencesReader` for the JVM's lifetime; subsequent specs' settings reads flow through `api.getSettings()` (stubbed per-spec to the current instance's `settingsMap`), so the orphan reference is unreachable today. If a future eighty20results change ever routes `script.settings` through `preferencesReader` directly, those fields should be hoisted to JVM-static to keep cross-spec isolation honest.

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

## Test pyramid: direct-call vs dispatch-envelope

Every tool feature should have coverage at **both** layers:

| Layer | Entry point | What it covers | When to use |
|---|---|---|---|
| **Direct-call (unit)** | `script.toolFoo(args)` — invokes the tool method on the loaded script directly. | The tool's internal logic: argument validation, state reads/mutations, hub-call shapes, return value. Fast and pinpoints the exact line that regressed. | Always. This is the default for any new tool feature or bug fix; one direct-call test per branch (golden path + each error condition). |
| **Dispatch-envelope (integration)** | `mcpDriver.callTool('tool_name', args)` — drives the full production path: `request.JSON → handleMcpRequest → tools/call dispatch → gateway routing (when applicable) → tool method → error mapping → render(Map)` envelope. | Wiring: that the tool is reachable by its public name from the JSON-RPC catalog, that gateway routing resolves correctly, that thrown exceptions land in the right JSON-RPC envelope shape, and that the response wrapper produces a parsable body. | At least one per tool feature (the happy path), plus an error-path counterpart for any tool that throws `IllegalArgumentException` (validation) or other exceptions (runtime). A direct-call-only suite can pass while a tool is silently unreachable through the production envelope. |

A regression in the dispatch layer (gateway routing changes, render envelope tweaks, JSON-RPC error mapping) shows up in dispatch tests without affecting direct-call tests. A regression in tool internals (bad state mutation, wrong hub endpoint) shows up in direct-call tests first. Keeping both layers makes the failure surface unambiguous.

### `mcpDriver.callTool(toolName, args)` helper

Signature: `Map callTool(String toolName, Map args)`. Returns the parsed JSON-RPC response Map. Internally:

1. Generates a fresh monotonic `id` (readable via `mcpDriver.lastSentId` for `response.id == mcpDriver.lastSentId` assertions).
2. Builds the canonical envelope `[jsonrpc: '2.0', id: <id>, method: 'tools/call', params: [name: toolName, arguments: args ?: [:]]]`.
3. Pushes it through `mcpDriver.pushBody(...)` so the next `request.JSON` read returns it.
4. Calls `script.handleMcpRequest()` — production code runs end-to-end.
5. Captures the `render(Map)` payload and parses `data` as JSON.

`args` may be `null` or `[:]` for tools that take no arguments. For deliberately-malformed envelopes (missing `name`, batch shape, throwing `request.JSON`), bypass the helper and use `mcpDriver.pushBody(...)` / `pushBodyThrowing(...)` directly — see `HandleToolsCallSpec` "missing tool name returns -32602".

### `@Unroll where: useGateways << [true, false]` pattern

Each dispatch test pins the gateway toggle explicitly via a data-driven Spock feature so the same envelope path runs under both routing modes in one feature method:

```groovy
import spock.lang.Unroll

@Unroll
def "create_room dispatches successfully through the envelope (useGateways=#useGateways)"() {
    given:
    settingsMap.useGateways = useGateways
    httpPostHandler = { Map params, Closure responseCb ->
        responseCb.call([status: 200, data: ''])
    }

    when:
    def response = mcpDriver.callTool('create_room', [name: 'Garage', confirm: true])

    then:
    response.jsonrpc == '2.0'
    response.id == mcpDriver.lastSentId
    response.result.isError != true

    where:
    useGateways << [true, false]
}
```

The CI matrix (see "CI" below) runs the full suite under both default modes, but pinning per-feature keeps the dispatch test honest even under the single-mode matrix entry the developer happens to run locally.

### Error-path envelope shapes

Two distinct production paths produce different envelope shapes — assert the right one:

- **`IllegalArgumentException` from a tool → JSON-RPC error `-32602`.** Validation errors surface in the `response.error` slot:

  ```groovy
  response.error.code == -32602
  response.error.message.startsWith('Invalid params:')
  ```

- **Generic `Exception` from a tool → success envelope with `isError: true`.** Per the MCP spec, tool *execution* errors stay in the success channel so clients can present them as tool output:

  ```groovy
  response.error == null
  response.result.isError == true
  response.result.content[0].type == 'text'
  response.result.content[0].text.startsWith('Tool error:')
  ```

`HandleToolsCallSpec` is the canonical reference for both shapes.

## Adding a new server tool spec

1. Create `src/test/groovy/server/Tool<Name>Spec.groovy` extending `support.ToolSpecBase`.
2. In `given:`, seed any device/child-app fixtures and settings flags the tool reads. Example: `settingsMap.enableHubAdminRead = true`, `childDevicesList << myMockDevice`.
3. Stub `hubGet.register(path) { ... }` for every internal endpoint the tool calls.
4. **Add direct-call tests** that call `script.tool<Name>(args)` in `when:` and assert on the return value AND any state mutations or mock interactions. Always include at least one error-path test alongside the golden path.
5. **Add dispatch-counterpart tests** that drive `mcpDriver.callTool('<tool_name>', args)` for the happy path and for each error condition the tool can produce. Use `@Unroll where: useGateways << [true, false]` so the dispatch feature runs under both routing modes. Assert envelope shape (`response.jsonrpc`, `response.id == mcpDriver.lastSentId`) plus the layer-specific shape from the error-path table above.
6. If the tool is reached through a gateway (`manage_rooms`, `manage_rule_machine`, etc.), the dispatch test catches gateway-routing regressions automatically by virtue of running with `useGateways=true` — no separate gateway-routing test needed.

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

- **Tier 1 — unit / direct-call specs.** Call tool / rule-primitive methods directly (`script.toolFoo(args)`, `script.handleToolsCall(msg)`, `script.handleDeviceEvent(evt)`). Fast, focused, and pinpoints regressions at tool granularity. This is the right default for the "tool internals" half of the pyramid (see "Test pyramid" above).
- **Tier 2 — integration dispatch specs (this section).** Drive the full in-process request-dispatch path (`request.JSON → handleMcpRequest → tools/call dispatch → gateway routing → tool method → error mapping → render`) via `mcpDriver.callTool('tool_name', args)`, or the full rule install loop (`initialize → subscribeToTriggers → subscribe → fireEvent → handler → action`). Still one JVM, Spock Mocks for the hub runtime, no real HTTP. Every tool feature should have at least one dispatch counterpart paired with its direct-call test.
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
- Runs `./gradlew test --info --warning-mode all` under the matrix below
- Uploads `build/reports/tests/test/` per matrix entry as `test-report-<mode>-<dispatch-mode>` on failure

### Matrix (4 parallel lanes)

The Unit Tests workflow expands a 2×2 strategy matrix — every PR runs the full Spock suite four times in parallel, with wall-clock unchanged versus a single run:

| `mode` | `dispatch-mode` | Gradle flags | What it isolates |
|---|---|---|---|
| `normal` | `gateway` | (none) | Baseline. Default settings: lenient metaClass check, `useGateways=true`. |
| `strict` | `gateway` | `-PharnessStrictMetaClass=true` | Flags any per-instance ExpandoMetaClass entries that survive the dual wipe in `HarnessSpec.setup()`. A `strict`-only failure attributes the regression to a metaClass-wipe escape rather than tool behaviour. See `HarnessSpec#checkMetaClassClean`. |
| `normal` | `flat` | `-Pharness.useGateways=false` | `HarnessSpec.setup()` reads the system property and seeds `settingsMap.useGateways = false` before each test, so any spec that doesn't explicitly pin `useGateways` in `given:` runs under the flat dispatch path (tests that do pin it still win). A `flat`-only failure attributes the regression to the gateway-toggle code path rather than tool behaviour. |
| `strict` | `flat` | both | Combined corner. Catches metaClass-wipe escapes that only surface under flat dispatch. |

A status-check summary job named `test` aggregates the four matrix entries into a single check the branch ruleset can gate on (the matrix expansion produces `test (normal, gateway)` / `test (normal, flat)` / `test (strict, gateway)` / `test (strict, flat)` — none of which match the literal `test` check name on their own).

Local equivalents of the non-baseline lanes:

```bash
./gradlew test -PharnessStrictMetaClass=true              # strict-only
./gradlew test -Pharness.useGateways=false                # flat-only
./gradlew test -PharnessStrictMetaClass=true -Pharness.useGateways=false  # both
```
