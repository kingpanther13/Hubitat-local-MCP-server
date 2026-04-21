package support

/**
 * install() mutates the global metaclass on BOTH
 * `hubitat.helper.RMUtils` (the test-classpath stub at
 * `src/test/groovy/support/stubs/hubitat/helper/RMUtils.groovy`, which
 * direct test-code calls resolve against) AND
 * `me.biocomp.hubitat_ci.api.common_api.RMUtils` (the class eighty20results'
 * SandboxClassLoader remaps `hubitat.helper.RMUtils` to when the reference
 * appears inside sandbox-loaded production code, e.g. PR #79's
 * `manage_rule_machine` gateway tools).
 *
 * The fork's own `common_api.RMUtils` only ships `getRule(String)` as a
 * real method; Groovy's `metaClass.static.*` assignment registers the
 * remaining methods (`getRuleList`, `sendAction`, `pauseRule`, `resumeRule`,
 * `setRuleBoolean`) dynamically for the duration of install(), and
 * dispatches sandbox-emitted calls to this mock.
 *
 * Specs using this mock must run sequentially — install() mutates shared
 * class metaclasses. `build.gradle` pins `maxParallelForks = 1` for this
 * reason; do not enable parallel test execution without moving these
 * statics off the shared class metaclasses first.
 *
 * `RMUtilsSandboxInterceptionSpec` is the end-to-end regression proving
 * sandbox-mapped calls reach this mock. If a future eighty20results bump
 * changes the sandbox mapping target, that spec will fail loudly rather
 * than PR #79's tests silently returning defaults.
 */
class RMUtilsMock {
    final List<Map> calls = []
    List<Map> stubRuleList = []

    List getRuleList(String version = '5.0') {
        calls << [method: 'getRuleList', version: version]
        return stubRuleList
    }

    void sendAction(Long ruleId, String action) {
        calls << [method: 'sendAction', ruleId: ruleId, action: action]
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
        [hubitat.helper.RMUtils, me.biocomp.hubitat_ci.api.common_api.RMUtils].each { Class cls ->
            cls.metaClass.static.getRuleList = { String v = '5.0' -> self.getRuleList(v) }
            cls.metaClass.static.sendAction = { Long id, String a -> self.sendAction(id, a) }
            cls.metaClass.static.pauseRule = { Long id -> self.pauseRule(id) }
            cls.metaClass.static.resumeRule = { Long id -> self.resumeRule(id) }
            cls.metaClass.static.setRuleBoolean = { Long id, Boolean v -> self.setRuleBoolean(id, v) }
        }
    }

    void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(hubitat.helper.RMUtils)
        GroovySystem.metaClassRegistry.removeMetaClass(me.biocomp.hubitat_ci.api.common_api.RMUtils)
    }
}
