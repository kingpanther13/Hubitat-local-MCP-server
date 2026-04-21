package support

/**
 * `install()` mutates the static metaClass on `hubitat.helper.RMUtils`
 * -- the main-source-set stub at
 * `src/main/groovy/hubitat/helper/RMUtils.groovy`. Both test-side
 * direct calls (e.g. `RMUtilsMockSpec`) and sandbox-loaded production
 * calls (e.g. PR #79's `manage_rule_machine` gateway tools, resolved
 * through `support.PassThroughSandboxClassLoader`) land on the mock.
 *
 * Specs using this mock must run sequentially -- `install()` mutates
 * the shared class metaclass. `build.gradle` pins
 * `maxParallelForks = 1` for this reason; do not enable parallel test
 * execution without moving these statics off the shared class metaclass
 * first.
 *
 * `RMUtilsSandboxInterceptionSpec` is the end-to-end regression
 * proving sandbox-loaded code reaches the mock via the PassThrough
 * classloader path. If that spec fails after an eighty20results bump,
 * the PassThrough scaffold needs attention before PR #79-style specs
 * can trust this mock.
 *
 * Coverage: PR #79's `manage_rule_machine` gateway tools call
 * `RMUtils.getRuleList()` (no-arg for RM 4.x) AND
 * `RMUtils.getRuleList("5.0")` (RM 5.x) as a dual probe, plus
 * `RMUtils.sendAction([ruleId], action, appLabel, "5.0")` 4-arg with
 * a 3-arg fallback. This mock supports all those signatures plus
 * per-version stubs and per-method throw-injection for modelling
 * partial-failure shapes.
 */
class RMUtilsMock {
    final List<Map> calls = []
    // Separate stubs per RM version so tests can model "v4 installed, v5 absent"
    // (or vice-versa) independently. Set either to null to make that version's
    // call raise a MissingMethodException-like error via throwOnGetRuleList*,
    // exercising the production classifier that distinguishes missing-class
    // from real failures.
    List<Map> stubRuleList4 = []
    List<Map> stubRuleList5 = []
    // Legacy single-list alias retained for specs that don't care about the
    // version split. If a test sets stubRuleList, both version-specific
    // lookups return it.
    List<Map> stubRuleList = null
    // Optional throwables per method for modelling RM-not-installed failures
    // or mid-pipeline errors. The classifier in toolListRmRules decides
    // which substring shapes are quiet-missing vs surfaced-as-warning.
    Throwable throwOnGetRuleList4 = null
    Throwable throwOnGetRuleList5 = null
    Throwable throwOnSendAction = null

    List getRuleList() {
        if (throwOnGetRuleList4 != null) throw throwOnGetRuleList4
        calls << [method: 'getRuleList', version: null]
        return stubRuleList != null ? stubRuleList : stubRuleList4
    }

    List getRuleList(String version) {
        def relevantThrow = (version == '5.0') ? throwOnGetRuleList5 : throwOnGetRuleList4
        if (relevantThrow != null) throw relevantThrow
        calls << [method: 'getRuleList', version: version]
        if (stubRuleList != null) return stubRuleList
        return version == '5.0' ? stubRuleList5 : stubRuleList4
    }

    // Legacy 2-arg form retained for pre-PR-79 specs that used it.
    void sendAction(Long ruleId, String action) {
        if (throwOnSendAction != null) throw throwOnSendAction
        calls << [method: 'sendAction', ruleId: ruleId, action: action]
    }

    // Production (PR #79) preferred form: sendAction([ids], action, appLabel, "5.0").
    void sendAction(List ruleIds, String action, String appLabel, String version) {
        if (throwOnSendAction != null) throw throwOnSendAction
        calls << [method: 'sendAction', ruleIds: ruleIds, action: action, appLabel: appLabel, version: version]
    }

    // Production 3-arg fallback form (fires only on MissingMethodException /
    // NoSuchMethodError from the 4-arg attempt).
    void sendAction(List ruleIds, String action, String appLabel) {
        if (throwOnSendAction != null) throw throwOnSendAction
        calls << [method: 'sendAction', ruleIds: ruleIds, action: action, appLabel: appLabel]
    }

    void pauseRule(Long ruleId) {
        calls << [method: 'pauseRule', ruleId: ruleId]
    }

    void resumeRule(Long ruleId) {
        calls << [method: 'resumeRule', ruleId: ruleId]
    }

    void setRuleBoolean(Long ruleId, Boolean value) {
        calls << [method: 'setRuleBoolean', ruleId: ruleId, value: value]
    }

    void install() {
        def self = this
        // Methods exist on the stub class (src/main/groovy/hubitat/helper/RMUtils.groovy)
        // so ExpandoMetaClass `<<` rejects them as "already exists". Using `=`
        // REPLACES the stub's bytecode method with our closure. Multiple `=`
        // assignments to the SAME name but DIFFERENT parameter signatures each
        // replace only their matching bytecode overload (Groovy treats each
        // arity as its own metaclass entry), giving us the overload dispatch
        // we need.
        //
        // getRuleList: no-arg (RM 4.x) AND String-version (RM 5.x).
        hubitat.helper.RMUtils.metaClass.static.getRuleList = { -> self.getRuleList() }
        hubitat.helper.RMUtils.metaClass.static.getRuleList = { String v -> self.getRuleList(v) }
        // sendAction: three production forms
        //   (Long, String)                       -- legacy simple form
        //   (List, String, String)               -- PR-79 3-arg fallback
        //   (List, String, String, String)       -- PR-79 preferred 4-arg form
        hubitat.helper.RMUtils.metaClass.static.sendAction = { Long id, String a -> self.sendAction(id, a) }
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List ids, String a, String lbl -> self.sendAction(ids, a, lbl) }
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List ids, String a, String lbl, String v -> self.sendAction(ids, a, lbl, v) }
        hubitat.helper.RMUtils.metaClass.static.pauseRule = { Long id -> self.pauseRule(id) }
        hubitat.helper.RMUtils.metaClass.static.resumeRule = { Long id -> self.resumeRule(id) }
        hubitat.helper.RMUtils.metaClass.static.setRuleBoolean = { Long id, Boolean v -> self.setRuleBoolean(id, v) }
    }

    void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(hubitat.helper.RMUtils)
    }
}
