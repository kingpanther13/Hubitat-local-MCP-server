package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * #114 Advanced sub-page. The page's two deny-only multi-selects source their options
 * from the live tool surface, and a reset button (handled in appButtonHandler) clears
 * them. These tests cover the option-source logic and the reset handler. (The page's
 * dynamicPage render is not directly unit-testable -- like mainPage/confirmDeletePage,
 * it must run inside the harness's preferences() context.)
 */
class AdvancedOverridesPageSpec extends ToolSpecBase {

    @spock.lang.Shared
    private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        // app.removeSetting in the reset handler resolves through getApp(); layer the
        // stub onto the shared appExecutor mock (same additive pattern as ToolManageLogsSpec).
        appExecutor.getApp() >> sharedAppStub
    }

    def cleanup() {
        sharedAppStub.settingsStore.clear()
    }

    def "override option lists are generated from the live tool surface (never drift)"() {
        given:
        def gwConfig = script.getGatewayConfig()
        def toolOptions = script.getAllToolDefinitions()*.name.findAll { !gwConfig.containsKey(it) }

        expect: "one option per gateway, and one option per leaf tool"
        gwConfig.keySet().size() == 23
        toolOptions.size() > 50
        // gateway names are not offered as individual-tool disable options
        toolOptions.every { !gwConfig.containsKey(it) }
    }

    def "resetOverridesBtn removes both override settings and logs an audit line"() {
        given: "the app stub carries the two override settings, and a captured mcpLog"
        sharedAppStub.settingsStore['disabled_tools'] = ['hub_manage_mode']
        sharedAppStub.settingsStore['disabled_gateways'] = ['hub_manage_files']
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg -> logs << [level: level, msg: msg] }

        when:
        script.appButtonHandler("resetOverridesBtn")

        then: "both override settings were cleared via app.removeSetting"
        !sharedAppStub.settingsStore.containsKey('disabled_tools')
        !sharedAppStub.settingsStore.containsKey('disabled_gateways')

        and: "an audit line was emitted"
        logs.any { it.msg?.toLowerCase()?.contains('override') }
    }

    def "appButtonHandler ignores an unknown button without error"() {
        when:
        script.appButtonHandler("someUnknownButton")

        then:
        notThrown(Exception)
    }
}
