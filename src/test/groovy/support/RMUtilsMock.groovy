package support

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
