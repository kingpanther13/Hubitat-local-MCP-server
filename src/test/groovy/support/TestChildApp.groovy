package support

import me.biocomp.hubitat_ci.api.common_api.EventSubscriptionWrapper
import me.biocomp.hubitat_ci.api.common_api.InstalledAppWrapper
import me.biocomp.hubitat_ci.api.hub.AppAtomicState

/**
 * Child-app-like class for server rule tool specs. Implements the
 * InstalledAppWrapper trait — addChildApp() on AppExecutor returns that
 * type, so the Mock's stub response needs to satisfy the cast.
 *
 * Exposes the MCP Rule surface the server reads: updateRuleFromParent,
 * getRuleData. Standard InstalledAppWrapper methods are given no-op
 * defaults (overridable via Spy/Spock when a spec cares).
 */
class TestChildApp implements InstalledAppWrapper {
    Long id
    String label
    String name = 'MCP Rule'
    Map<String, Object> settingsStore = [:]
    Map<String, Object> ruleData = [triggers: [], conditions: [], actions: []]
    Map<String, Object> stateStore = [:]

    // --- MCP Rule child-app surface (called by the server) ---

    void updateRuleFromParent(Map data) {
        if (data?.triggers != null) ruleData.triggers = data.triggers
        if (data?.conditions != null) ruleData.conditions = data.conditions
        if (data?.actions != null) ruleData.actions = data.actions
    }

    Map getRuleData() {
        return ruleData
    }

    // --- InstalledAppWrapper trait — minimal defaults ---

    @Override Long getAppTypeId() { return 0L }
    @Override String getInstallationState() { return 'complete' }
    @Override Long getParentAppId() { return null }
    @Override List<EventSubscriptionWrapper> getSubscriptions() { return [] }
    @Override void updateLabel(String newLabel) { this.label = newLabel }
    @Override void clearSetting(String settingName) { settingsStore.remove(settingName) }
    @Override void removeSetting(String settingName) { settingsStore.remove(settingName) }
    @Override void updateSetting(String settingName, Boolean val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, Date val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, Double val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, List val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, Long val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, Map val) { settingsStore[settingName] = val }
    @Override void updateSetting(String settingName, String val) { settingsStore[settingName] = val }
    @Override AppAtomicState getAtomicState() { return null }
    @Override Object getSetting(String name) { return settingsStore[name] }
    @Override Map getState() { return stateStore }
    @Override void saveState() {}
    @Override void setAtomicState(AppAtomicState s) {}
    @Override void setState(Map s) { this.stateStore = s }
}
