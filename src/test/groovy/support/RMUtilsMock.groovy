package support

/**
 * `install()` mutates the static metaClass on `hubitat.helper.RMUtils`
 * — the main-source-set stub at
 * `src/main/groovy/hubitat/helper/RMUtils.groovy`. Both test-side
 * direct calls (e.g. `RMUtilsMockSpec`) and sandbox-loaded production
 * calls (e.g. PR #79's `manage_rule_machine` gateway tools, resolved
 * through `support.PassThroughSandboxClassLoader`) land on the mock.
 *
 * Specs using this mock must run sequentially — `install()` mutates
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
        hubitat.helper.RMUtils.metaClass.static.getRuleList = { String v = '5.0' -> self.getRuleList(v) }
        hubitat.helper.RMUtils.metaClass.static.sendAction = { Long id, String a -> self.sendAction(id, a) }
        hubitat.helper.RMUtils.metaClass.static.pauseRule = { Long id -> self.pauseRule(id) }
        hubitat.helper.RMUtils.metaClass.static.resumeRule = { Long id -> self.resumeRule(id) }
        hubitat.helper.RMUtils.metaClass.static.setRuleBoolean = { Long id, Boolean v -> self.setRuleBoolean(id, v) }
    }

    void uninstall() {
        GroovySystem.metaClassRegistry.removeMetaClass(hubitat.helper.RMUtils)
    }
}
