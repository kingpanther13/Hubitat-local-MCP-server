package server

import support.TestChildApp
import support.ToolSpecBase
import spock.lang.Unroll

/**
 * Issue #476 part 1: every write surface leads with the guide-first sentence, every read surface
 * does not. Covers tools/list in both catalog shapes, the gateway no-arg catalog, hub_search_tools
 * results, both server-instruction variants, and the hub_get_tool_guide description.
 */
class GuideFirstSentenceSpec extends ToolSpecBase {

    private static final String SENTENCE = 'MUST call hub_get_tool_guide first.'

    def setupSpec() {
        // hub_search_tools sheds retired app settings through the app object.
        appExecutor.getApp() >> new TestChildApp(id: 1L, label: 'MCP')
    }

    private void widestCatalog(boolean gateways) {
        settingsMap.useGateways = gateways
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableDeveloperMode = true
    }

    @Unroll
    def "tools/list (useGateways=#gateways): write leaves and write gateways lead with the sentence, reads do not"() {
        given:
        widestCatalog(gateways)
        Set readOnly = script.getReadOnlyToolNames()
        Map gatewayConfig = script.getGatewayConfig()

        when:
        def tools = script.getToolDefinitions()
        def gatewayEntries = tools.findAll { gatewayConfig.containsKey(it.name) }
        def leaves = tools - gatewayEntries
        def writeGateways = gatewayEntries.findAll { gw -> gw.inputSchema.properties.tool.enum.any { !readOnly.contains(it) } }
        def readGateways = gatewayEntries - writeGateways

        then: 'leaves follow their readOnlyHint'
        leaves.findAll { it.annotations.readOnlyHint == false && !(it.description as String).startsWith(SENTENCE + ' ') }*.name == []
        leaves.findAll { it.annotations.readOnlyHint == true && (it.description as String).startsWith(SENTENCE) }*.name == []
        leaves.any { it.annotations.readOnlyHint == false }
        leaves.any { it.annotations.readOnlyHint == true }

        and: 'gateways follow their visible sub-tool enum'
        writeGateways.findAll { !(it.description as String).startsWith(SENTENCE + ' ') }*.name == []
        readGateways.findAll { (it.description as String).startsWith(SENTENCE) }*.name == []
        gateways ? (writeGateways && readGateways) : gatewayEntries.isEmpty()

        and: 'the sentence appears once even where a description passes the transform twice'
        tools.findAll { (it.description as String).count(SENTENCE) > 1 }*.name == []

        where:
        gateways << [false, true]
    }

    def "flat hub_set_rule selector is classified as the write tool it fronts"() {
        given:
        widestCatalog(false)

        when:
        def setRule = script.getToolDefinitions().find { it.name == 'hub_set_rule' }

        then:
        setRule.inputSchema.properties.keySet() == ['operation', 'appId', 'args', 'confirm'] as Set
        (setRule.description as String).startsWith(SENTENCE + ' Create or edit a Hubitat Rule Machine rule')
    }

    def "a gateway whose write sub-tools are all hidden drops the sentence"() {
        given:
        widestCatalog(true)
        settingsMap.enableWrite = false
        Set readOnly = script.getReadOnlyToolNames()

        when:
        def devices = script.getToolDefinitions().find { it.name == 'hub_manage_devices' }

        then:
        devices != null
        devices.inputSchema.properties.tool.enum.every { readOnly.contains(it) }
        !(devices.description as String).startsWith(SENTENCE)
    }

    def "gateway no-arg catalog carries the sentence on write leaves only"() {
        given:
        widestCatalog(true)
        Set readOnly = script.getReadOnlyToolNames()

        when:
        def entries = script.getGatewayConfig().keySet().collectMany { gw ->
            script.handleGateway(gw, null, null).tools as List
        }

        then:
        entries.findAll { !readOnly.contains(it.name) && !(it.description as String).startsWith(SENTENCE + ' ') }*.name == []
        entries.findAll { readOnly.contains(it.name) && (it.description as String).startsWith(SENTENCE) }*.name == []
        entries.any { !readOnly.contains(it.name) }
        entries.any { readOnly.contains(it.name) }
    }

    def "hub_search_tools results carry the sentence on write leaves only, and the ranking corpus never does"() {
        given:
        widestCatalog(true)
        Set readOnly = script.getReadOnlyToolNames()

        when: "every tool name tokenizes to 'hub', so this query returns the whole visible corpus"
        def results = script.toolSearchTools([query: 'hub', maxResults: 500]).results as List
        def corpus = (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus as List

        then:
        results.findAll { !readOnly.contains(it.tool) && !(it.description as String).startsWith(SENTENCE + ' ') }*.tool == []
        results.findAll { readOnly.contains(it.tool) && (it.description as String).startsWith(SENTENCE) }*.tool == []
        results.any { !readOnly.contains(it.tool) && it.gateway }
        results.any { !readOnly.contains(it.tool) && !it.gateway }
        results.any { readOnly.contains(it.tool) }

        and: 'BM25 ranks on the unprefixed text'
        corpus.findAll { (it.description as String).contains(SENTENCE) }*.name == []
    }

    @Unroll
    def "server instructions (useGateways=#gateways) open with the sentence"() {
        given:
        settingsMap.useGateways = gateways

        expect:
        (script.serverInstructions() as String).startsWith(SENTENCE + ' ')

        where:
        gateways << [false, true]
    }

    def "hub_get_tool_guide description points write tools at their section instead of calling itself a supplement"() {
        when:
        def description = script.getAllToolDefinitions().find { it.name == 'hub_get_tool_guide' }.description as String

        then:
        !description.contains('Supplement only')
        !description.contains("when a tool's own description and parameter descriptions are not enough")
        description.contains("Read a write tool's section before calling that tool")
        description.contains('acknowledgment key')
    }
}
