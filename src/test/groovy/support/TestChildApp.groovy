package support

/**
 * Child-app-like class for server rule tool specs. Responds to the
 * MCP Rule child app surface the server reads: id, updateSetting,
 * updateLabel, updateRuleFromParent, getSetting, getRuleData.
 *
 * Spy(TestChildApp) lets specs both invoke real behaviour and verify
 * interactions via `1 * childApp.updateRuleFromParent(_)` etc.
 */
class TestChildApp {
    Integer id
    Map<String, Object> settings = [:]
    Map<String, Object> ruleData = [triggers: [], conditions: [], actions: []]

    void updateSetting(String name, Object value) {
        settings[name] = value
    }

    void updateLabel(String newLabel) {
        settings['_label'] = newLabel
    }

    void updateRuleFromParent(Map data) {
        if (data?.triggers != null) ruleData.triggers = data.triggers
        if (data?.conditions != null) ruleData.conditions = data.conditions
        if (data?.actions != null) ruleData.actions = data.actions
    }

    Object getSetting(String name) {
        settings[name]
    }

    Map getRuleData() {
        return ruleData
    }
}
