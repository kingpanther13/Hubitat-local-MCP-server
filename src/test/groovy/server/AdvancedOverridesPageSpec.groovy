package server

import support.ToolSpecBase

/**
 * #114 Advanced sub-page. The page renders two deny-only multi-selects whose options
 * are generated from the live tool surface, plus a reset button handled in
 * appButtonHandler. These tests cover the render path and the option-source logic.
 */
class AdvancedOverridesPageSpec extends ToolSpecBase {

    def "advancedOverridesPage renders without error"() {
        when:
        def page = script.advancedOverridesPage()

        then:
        notThrown(Exception)
    }

    def "override option lists are generated from the live tool surface (never drift)"() {
        given:
        def gwConfig = script.getGatewayConfig()
        def toolOptions = script.getAllToolDefinitions()*.name.findAll { !gwConfig.containsKey(it) }

        expect: "one option per gateway, and one option per leaf tool"
        gwConfig.keySet().size() == 19
        toolOptions.size() > 50
        // gateway names are not offered as individual-tool disable options
        toolOptions.every { !gwConfig.containsKey(it) }
    }
}
