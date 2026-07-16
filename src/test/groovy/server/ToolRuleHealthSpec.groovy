package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * Spec for hub_get_rule_health (toolCheckRuleHealth -> _rmCheckRuleHealth) after
 * issue #254: the rule's compiled atomicState (GET /app/ruleBuilderJson) becomes
 * the PREFERRED source for the `broken` verdict, with the HTML configure-json
 * render scan RETAINED as a cross-check and as the fallback when the JSON source
 * is unavailable. Also guards the error-response integration: every RM mutation
 * failure envelope (_rmBuildUpdateErrorResponse) now carries the compiled-state
 * health, not just the pre-flight-refusal path.
 *
 * Mocking: hubInternalGet is routed to hubGet.register(path) by HarnessSpec, and
 * the closure return value MUST be a JSON STRING (production parses it with
 * JsonSlurper). Unstubbed paths throw IllegalStateException inside the helper's
 * try/catch — which is itself the "source unavailable -> fall back" path.
 */
class ToolRuleHealthSpec extends ToolSpecBase {

    // ---------- fixtures ----------

    /** Classic RM compiled-state shape (the ~45-key /app/ruleBuilderJson body, trimmed). */
    private String ruleBuilderJson(Map overrides = [:]) {
        def base = [
            installed: true, running: false, broken: false, condOper: "cond",
            trigCustoms: ["switch"], capabstrue: [:], capabsfalse: [:],
            eval: [:], parens: [:], inUseConds: [], unusedConds: []
        ]
        JsonOutput.toJson(base + overrides)
    }

    /** configure/json shape used by the retained HTML path. */
    private String configJson(int id, String label = "BAT-RH-test", List paragraphs = [], List inputs = [], def configError = null) {
        JsonOutput.toJson([
            app: [id: id, label: label, name: "Rule-5.1", installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: configError,
                         sections: [[title: "", input: inputs, paragraphs: paragraphs]]],
            settings: [:], childApps: []
        ])
    }

    /** statusJson shape consumed by _rmFetchSettingsByName (the multiple-flag + structural checks). */
    private String statusJson(int id, List appSettings = []) {
        JsonOutput.toJson([
            installedApp: [id: id], appSettings: appSettings,
            eventSubscriptions: [], scheduledJobs: [], appState: [:]
        ])
    }

    private void seedHealthy(int id, Map rbOverrides = [:]) {
        settingsMap.enableRead = true
        hubGet.register("/app/ruleBuilderJson/${id}".toString()) { ruleBuilderJson(rbOverrides) }
        hubGet.register("/installedapp/configure/json/${id}".toString()) { configJson(id) }
        hubGet.register("/installedapp/statusJson/${id}".toString()) { statusJson(id) }
    }

    // ---------- preferred source: ruleBuilderJson broken boolean ----------

    def "auto: healthy rule -> ok=true, broken=false, both sources contributed"() {
        given:
        seedHealthy(100)

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == true
        h.broken == false
        h.source == "ruleBuilderJson+configPage"
        h.issues.isEmpty()
    }

    def "auto: when NEITHER source is readable the verdict is flagged unreadable -- couldn't check, not checked-and-broken"() {
        given: 'no hub paths registered: both the compiled-state and HTML reads throw'
        settingsMap.enableRead = true

        when:
        def h = script._rmCheckRuleHealth(123)

        then: 'ok stays false (no positive evidence of health), but unreadable marks the transient'
        h.ok == false
        h.unreadable == true
        h.source == 'none'
        h.broken == null
        h.brokenMarkers.isEmpty()
    }

    def "auto: a genuine broken verdict is NOT unreadable"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: true]) }
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.unreadable == false
        h.broken == true
    }

    def "auto: ruleBuilderJson broken:true is the authoritative verdict (even when HTML shows nothing)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') {
            ruleBuilderJson([broken: true, capabsfalse: ["1": "Temperature of BAT-Motion1(70) is ≠ Alice's Room T&H(73.9)"]])
        }
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }   // no *BROKEN*, no markers
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.broken == true
        h.issues.any { it.contains("broken:true") }
        h.issues.any { it.contains("False conditions:") }
        // the JSON caught a break the render scan missed -> cross-check fires
        h.issues.any { it.contains("cross-check") }
    }

    def "auto: predicate summary is surfaced from ruleBuilderJson when present"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([hasPredicate: true, predCapabs: [1]]) }
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.predicate == [hasPredicate: true, predCapabs: [1]]
    }

    // ---------- shape-check (not status-check) ----------

    def "auto: empty {} (nonexistent id) is ignored as a JSON source and falls back to HTML"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { "{}" }                      // nonexistent id => {}
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == null          // JSON source not usable
        h.source == "configPage"  // only HTML contributed
        h.ok == true
    }

    def "auto: a classic Visual Rule (whenNodes/thenNodes) is recognized as vrb-classic, not mistaken for an RM rule"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') {
            JsonOutput.toJson([name: "vrb", rulePaused: false, whenNodes: [], thenNodes: [], elseNodes: []])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == "vrb-classic"
        h.broken == null               // classic VRB has no compiled broken boolean
        h.source == "ruleBuilderJson"  // recognized; the RM HTML path is skipped for VRB
        // RM HTML scan must not run for a Visual Rule (it has no *BROKEN*/actType protocol)
        hubGet.calls.every { it.path != "/installedapp/configure/json/100" }
    }

    // ---------- VRB graph rules: validationErrors are the broken-equivalent ----------

    def "auto: a graph Visual Rule with validationErrors reports broken:true (ruleFormat vrb-graph)"() {
        given:
        settingsMap.enableRead = true
        // ruleBuilderJson returns the graph app's raw state (no broken/whenNodes) -> unrecognized,
        // so the reader falls through to the graph endpoint.
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([someGraphAppState: true]) }
        hubGet.register('/app/ruleBuilder20Json/100') {
            JsonOutput.toJson([name: "g", rulePaused: false, ruleJson: "{}", validationErrors: ["Trigger device missing"]])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == "vrb-graph"
        h.broken == true
        h.source == "ruleBuilder20Json"
        h.validationErrors == ["Trigger device missing"]
        h.ok == false
        h.issues.any { it.contains("validation errors") }
        // VRB rules don't speak the RM HTML protocol -> that path is skipped
        hubGet.calls.every { it.path != "/installedapp/configure/json/100" }
    }

    def "auto: a healthy graph Visual Rule (no validationErrors) reports broken:false"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([someGraphAppState: true]) }
        hubGet.register('/app/ruleBuilder20Json/100') {
            JsonOutput.toJson([name: "g", rulePaused: false, ruleJson: '{"version":1,"nodes":[],"edges":[]}', validationErrors: []])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == "vrb-graph"
        h.broken == false
        h.ok == true
        h.source == "ruleBuilder20Json"
    }

    def "auto: an unrecognized compiled state (not rm/vrb, graph not-a-graph) falls back to the HTML path"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([someAppState: true]) }      // not rm / vrb-classic
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false,"message":"not found"}' }  // not a graph rule (no throw)
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }                       // Rule-5.1 type
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.source == "configPage"   // compiled-state path found nothing -> HTML fallback ran
        h.ruleFormat == "rm"       // and the HTML path classified the configPage app type
        h.ok == true
    }

    @spock.lang.Unroll
    def "auto: a classic app (no compiled state) is classified by configPage app type: #typeName -> #expected"() {
        // Button Controller / Basic Rule and other classic apps share RM's configPage protocol;
        // health covers them via the configPage checks and names the app type in ruleFormat.
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([someAppState: true]) }  // not rm / vrb-classic
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }                    // not a graph rule
        hubGet.register('/installedapp/configure/json/100') {
            JsonOutput.toJson([
                app: [id: 100, label: "BAT-x", name: typeName, installed: true,
                      appType: [name: typeName, namespace: "hubitat"]],
                configPage: [name: "mainPage", error: null, sections: [[title: "", input: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == expected
        h.broken == null            // classic apps have no compiled broken boolean
        h.source == "configPage"
        h.ok == true

        where:
        typeName                | expected
        "Basic Rule-1.0"        | "basic-rule"
        "Button Controller-5.1" | "button-controller"
        "Notifier-1.0"          | "classic-app"
    }

    def "auto: a configPage fetch failure surfaces as 'health check failed' even when ruleBuilderJson succeeded"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }
        // configure/json unregistered -> _rmFetchConfigJson throws inside the HTML path

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.issues.any { it.contains("health check failed") }
        h.ok == false
    }

    def "auto: ruleBuilderJson broken:true AND an HTML marker agree -> no cross-check issue"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: true]) }
        hubGet.register('/installedapp/configure/json/100') { configJson(100, "BAT-RH-test", ["**Broken Action**"]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == true
        h.brokenMarkers.contains("**Broken Action**")
        h.ok == false
        h.issues.every { !it.contains("cross-check") }   // sources agree -> no cross-check noise
    }

    def "auto: multiple-flag poison is detected from statusJson vs schema (RM HTML path)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }
        hubGet.register('/installedapp/configure/json/100') {
            configJson(100, "BAT-RH-test", [], [[name: "dev1", type: "capability.switch", multiple: true]])
        }
        hubGet.register('/installedapp/statusJson/100') {
            statusJson(100, [[name: "dev1", type: "capability.switch", multiple: false, value: "8"]])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.multipleFlagPoison.contains("dev1")
        h.ok == false
        h.issues.any { it.contains("multiple-flag poison") }
    }

    def "an RM rule without a predicate omits the predicate field"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }  // no hasPredicate/predCapabs
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        !h.containsKey("predicate")
    }

    // ---------- retained HTML path: fallback + its own detections ----------

    def "auto: when ruleBuilderJson is unavailable, the HTML *BROKEN* label still flags the rule"() {
        given:
        settingsMap.enableRead = true
        // ruleBuilderJson NOT registered -> helper catches the throw and returns null
        hubGet.register('/installedapp/configure/json/100') { configJson(100, "BAT-RH-test *BROKEN*") }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == null
        h.source == "configPage"
        h.ok == false
        h.issues.any { it.contains("*BROKEN*") }
    }

    def "auto: HTML markers with ruleBuilderJson broken:false -> cross-check flags the disagreement"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }
        hubGet.register('/installedapp/configure/json/100') { configJson(100, "BAT-RH-test", ["**Broken Action**"]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.brokenMarkers.contains("**Broken Action**")
        h.issues.any { it.contains("render text disagrees") }
        h.ok == false
    }

    def "auto: a broken marker in the body-element format (live UI renderer shape) is caught"() {
        // Regression guard: the live hub serves '**Broken Trigger**' in the section.body[]
        // element format (sect.body[].description), not the paragraphs-array. A paragraphs-only
        // scan missed it on fw 2.5.0.143 (verified live). The health scan reads BOTH formats.
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }
        hubGet.register('/installedapp/configure/json/100') {
            JsonOutput.toJson([
                app: [id: 100, label: "BAT-RH-test", name: "Rule-5.1", installed: true,
                      appType: [name: "Rule-5.1", namespace: "hubitat"]],
                configPage: [name: "mainPage", error: null, sections: [
                    [title: "", input: [], body: [
                        [element: "paragraph", description: "**Broken Trigger**"],
                        [element: "paragraph", description: "Define Actions"]
                    ]]
                ]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.brokenMarkers.contains("**Broken Trigger**")
        h.ok == false
        // ruleBuilderJson broken:false but an HTML marker is present -> cross-check fires
        h.issues.any { it.contains("render text disagrees") }
    }

    // ---------- source selection (param) ----------

    def "source=configPage forces the HTML path only (ruleBuilderJson never read)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100, "configPage")

        then:
        h.source == "configPage"
        h.broken == null
        hubGet.calls.every { !(it.path == "/app/ruleBuilderJson/100") }
    }

    def "source=ruleBuilderJson forces the JSON path only (configure/json never read)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: false]) }

        when:
        def h = script._rmCheckRuleHealth(100, "ruleBuilderJson")

        then:
        h.source == "ruleBuilderJson"
        h.broken == false
        h.ok == true
        hubGet.calls.every { !(it.path == "/installedapp/configure/json/100") }
    }

    def "source=ruleBuilderJson, clean negative ({} + not-a-graph) -> ok=false with unavailable guidance"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { "{}" }                       // nonexistent id => {}
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false,"message":"not found"}' }  // not a graph rule (no throw)

        when:
        def h = script._rmCheckRuleHealth(100, "ruleBuilderJson")

        then:
        h.ok == false
        h.broken == null
        h.source == "none"
        h.issues.any { it.contains("unavailable") && it.contains("source='auto'") }
    }

    def "source=ruleBuilderJson, a THROWN read is reported as a read failure, not a missing rule (silent-failure review)"() {
        given:
        settingsMap.enableRead = true
        // Both endpoints unregistered -> the mock throws (stands in for auth/hub-down); the
        // compiled-state read must surface as a read FAILURE, not 'nonexistent / wrong firmware'.

        when:
        def h = script._rmCheckRuleHealth(100, "ruleBuilderJson")

        then:
        h.ok == false
        h.broken == null
        h.issues.any { it.contains("read FAILED") && it.contains("hub read error") }
        h.issues.every { !it.contains("nonexistent id") }   // must NOT misattribute to a missing rule
    }

    def "source=ruleBuilderJson, a non-JSON 200 (login page) is a read failure, not a missing rule (codex review)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { "<html><body>Please log in</body></html>" }   // bad 200, not JSON
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }                        // not a graph rule

        when:
        def h = script._rmCheckRuleHealth(100, "ruleBuilderJson")

        then:
        h.ok == false
        h.issues.any { it.contains("read FAILED") }
        h.issues.every { !it.contains("nonexistent id") }
    }

    def "source=ruleBuilderJson, a non-JSON 200 on the graph endpoint is also a read failure (final-pass review)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([someAppState: true]) }   // valid JSON, not a rule
        hubGet.register('/app/ruleBuilder20Json/100') { "<html><body>Please log in</body></html>" }  // bad 200 on the 2nd GET

        when:
        def h = script._rmCheckRuleHealth(100, "ruleBuilderJson")

        then:
        h.ok == false
        h.issues.any { it.contains("read FAILED") }
        h.issues.every { !it.contains("nonexistent id") }
    }

    def "auto: an app state with only one VRB node array (not both) is NOT classified as vrb-classic (codex review)"() {
        given:
        settingsMap.enableRead = true
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([whenNodes: [], somethingElse: true]) }  // lone key
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat != "vrb-classic"   // a lone whenNodes key must not be misread as a VRB rule
        h.source == "configPage"        // falls through to the RM/HTML path
    }

    def "null appId short-circuits to a clean unhealthy verdict without any HTTP calls (Gemini review)"() {
        given:
        settingsMap.enableRead = true

        when:
        def h = script._rmCheckRuleHealth(null)

        then:
        h.ok == false
        h.unreadable == true   // nothing was checked -- the ok||unreadable gates must read couldn't-check, not broken
        h.broken == null
        h.source == "none"
        h.issues.any { it.contains("appId is null") }
        h.brokenMarkerCounts == [:]   // faithful subset of the normal shape (the gate reads this key)
        hubGet.calls.isEmpty()   // no /app/ruleBuilderJson/null or /installedapp/configure/json/null
    }

    def "_rmHealthGatePass: only a checked-and-broken verdict fails committed work (the no-evidence polarity table)"() {
        expect: 'ONE definition, pinned as a truth table so no gate site can silently invert either no-evidence state'
        script._rmHealthGatePass([ok: true, unreadable: false])       // healthy
        script._rmHealthGatePass([ok: true, skipped: true])           // probe shed under the time budget
        script._rmHealthGatePass([ok: false, unreadable: true])       // couldn't check -- no evidence either way
        !script._rmHealthGatePass([ok: false, unreadable: false])     // checked and broken -- the ONLY failing verdict
        !script._rmHealthGatePass(null)                               // no verdict object at all is not a pass
    }

    // ---------- error-response integration (issue #254, task: fold health into RM error replies) ----------

    def "_rmBuildUpdateErrorResponse attaches compiled-state health on a non-preflight mutation failure"() {
        given:
        seedHealthy(100, [broken: true, capabsfalse: ["1": "X is wrong"]])

        when:
        def r = script._rmBuildUpdateErrorResponse(100, "addAction failed: relay 504", [backupKey: "rm-rule_100_x"])

        then:
        r.success == false
        r.health != null
        r.health.broken == true
        r.health.source.contains("ruleBuilderJson")
        r.health.issues.any { it.contains("broken:true") }
    }

    def "_rmBuildUpdateErrorResponse still attaches health on a pre-flight refusal"() {
        given:
        seedHealthy(100)

        when:
        def r = script._rmBuildUpdateErrorResponse(100, "addAction would create imbalance -- RM is not touched", [backupKey: "k"])

        then:
        r.restoreHint.contains("Pre-flight refusal")
        r.health != null
        r.health.broken == false
    }

    // ---------- dispatch envelope ----------

    @spock.lang.Unroll
    def "hub_get_rule_health via dispatch returns the enriched shape (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seedHealthy(100)

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.ok == true
        inner.broken == false
        inner.source == "ruleBuilderJson+configPage"

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_rule_health via dispatch rejects an invalid source with -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100, source: "bogus"])

        then:
        response.error.code == -32602
        response.error.message.contains("source must be one of")

        where:
        useGateways << [true, false]
    }
}
