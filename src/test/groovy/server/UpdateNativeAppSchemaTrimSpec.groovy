package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Issue #181: the flat tools/list catalog (useGateways=false) sits ~360 bytes
 * under the hub's 124,000-byte response cap. update_native_app alone is ~33 KB
 * (27% of the catalog), most of which is static capability enumeration that's
 * either (a) covered by the tool's {discover: true} mode or (b) documented in
 * TOOL_GUIDE.md / docs/rm_action_subtype_schemas.md.
 *
 * The fix is a flat-mode-only transform: `[[FLAT_TRIM]] ... [[/FLAT_TRIM]]`
 * markers in the schema descriptions are STRIPPED WITH CONTENT in flat mode
 * (recover the bytes) and STRIPPED WITHOUT CONTENT (just the tokens) in every
 * other client-visible surface (gateway-catalog mode, search_tools corpus,
 * missing-param error hints).
 *
 * These tests pin the contract end-to-end so a future author who adds new
 * markers, removes them, or breaks the transform learns about it from CI.
 */
class UpdateNativeAppSchemaTrimSpec extends ToolSpecBase {

    private static final int FLAT_CATALOG_BYTE_BUDGET = 120_000
    private static final String OPEN_MARKER = '[[FLAT_TRIM]]'
    private static final String CLOSE_MARKER = '[[/FLAT_TRIM]]'

    private void enableEveryToggle() {
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true
    }

    def "flat-mode tools/list catalog fits under the 120,000-byte response budget"() {
        given: 'every feature toggle on so the flat catalog is at its widest'
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        def tools = script.getToolDefinitions()
        def catalogBytes = JsonOutput.toJson(tools).getBytes('UTF-8').length

        then: 'budget leaves headroom for the JSON-RPC envelope (4 KB) under the 124,000-byte cap'
        catalogBytes <= FLAT_CATALOG_BYTE_BUDGET
    }

    def "flat-mode tools/list catalog does not leak [[FLAT_TRIM]] tokens"() {
        given:
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        def catalogJson = JsonOutput.toJson(script.getToolDefinitions())

        then: 'neither opening nor closing marker appears in the wire payload'
        !catalogJson.contains(OPEN_MARKER)
        !catalogJson.contains(CLOSE_MARKER)
    }

    def "gateway-mode tools/list catalog does not leak [[FLAT_TRIM]] tokens"() {
        given: 'gateway-mode entries are short prose + sub-tool summaries (no markers expected today, but pin this anyway)'
        settingsMap.useGateways = true
        enableEveryToggle()

        when:
        def catalogJson = JsonOutput.toJson(script.getToolDefinitions())

        then:
        !catalogJson.contains(OPEN_MARKER)
        !catalogJson.contains(CLOSE_MARKER)
    }

    def "update_native_app description is materially smaller in flat mode than in gateway-catalog mode"() {
        given: 'gateway-catalog mode is the disclosure surface for full per-tool schemas'
        enableEveryToggle()

        when: 'flat-mode tools/list entry'
        settingsMap.useGateways = false
        def flatDef = script.getToolDefinitions().find { it.name == 'update_native_app' }
        def flatBytes = JsonOutput.toJson(flatDef).getBytes('UTF-8').length

        and: 'gateway-catalog response from handleGateway (no toolName) -- this is where gateway-mode callers see full descriptions'
        settingsMap.useGateways = true
        def gatewayResp = script.handleGateway('manage_native_rules_and_apps', null, [:])
        def gwUpdateNative = gatewayResp.tools.find { it.name == 'update_native_app' }
        def gwBytes = JsonOutput.toJson(gwUpdateNative).getBytes('UTF-8').length

        then: 'flat trim recovers significant bytes -- target trim is ~10 KB+ for issue #181 headroom'
        flatBytes < gwBytes
        (gwBytes - flatBytes) >= 8_000  // conservative floor; actual savings are ~10-12 KB
    }

    def "update_native_app flat-mode description keeps the discover/TOOL_GUIDE pointer for trimmed sections"() {
        given:
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        def def_ = script.getToolDefinitions().find { it.name == 'update_native_app' }
        def addTrigger = def_.inputSchema.properties.addTrigger.description
        def addAction = def_.inputSchema.properties.addAction.description
        def addRequiredExpression = def_.inputSchema.properties.addRequiredExpression.description

        then: 'addTrigger trimmed -- per-capability families gone, but pointers remain'
        !addTrigger.contains('Capability families and the spec fields each accepts')
        addTrigger.contains('{discover: true}')
        addTrigger.contains('TOOL_GUIDE.md')

        and: 'addAction trimmed -- capability list gone, but pointers remain'
        !addAction.contains('Capability families and the spec fields each accepts')
        addAction.contains('docs/rm_action_subtype_schemas.md')
        addAction.contains('{discover: true}')

        and: 'addRequiredExpression trimmed -- STPage enum gone, TOOL_GUIDE pointer remains'
        !addRequiredExpression.contains("RM's STPage capability list")
        addRequiredExpression.contains('TOOL_GUIDE.md')
    }

    def "gateway-catalog mode preserves the full update_native_app description (content survives, only tokens stripped)"() {
        given:
        settingsMap.useGateways = true
        enableEveryToggle()

        when: 'gateway-catalog disclosure (caller invokes the gateway with no toolName)'
        def gatewayResp = script.handleGateway('manage_native_rules_and_apps', null, [:])
        def updateNative = gatewayResp.tools.find { it.name == 'update_native_app' }
        def addTrigger = updateNative.inputSchema.properties.addTrigger.description
        def addAction = updateNative.inputSchema.properties.addAction.description
        def addRequiredExpression = updateNative.inputSchema.properties.addRequiredExpression.description

        then: 'capability enumerations are still present -- gateway mode is the full-disclosure surface'
        addTrigger.contains('Capability families and the spec fields each accepts')
        addTrigger.contains('Periodic Schedule')
        addAction.contains('Capability families and the spec fields each accepts')
        addAction.contains('capability=\'ifThen\'')
        addRequiredExpression.contains("RM's STPage capability list")

        and: 'no marker tokens leaked into the response'
        !addTrigger.contains(OPEN_MARKER) && !addTrigger.contains(CLOSE_MARKER)
        !addAction.contains(OPEN_MARKER) && !addAction.contains(CLOSE_MARKER)
        !addRequiredExpression.contains(OPEN_MARKER) && !addRequiredExpression.contains(CLOSE_MARKER)
    }

    def "stripFlatTrim helper handles own-line and inline markers correctly"() {
        when: 'own-line block, dropContent=true: marker lines + content removed, surrounding paragraphs separated by one blank line'
        def ownLineDropped = script.stripFlatTrim(
            "alpha\n\n[[FLAT_TRIM]]\ndrop this\nand this\n[[/FLAT_TRIM]]\n\nbeta",
            true
        )

        then:
        ownLineDropped == 'alpha\n\nbeta'

        when: 'own-line block, dropContent=false: marker lines stripped, content kept'
        def ownLineKept = script.stripFlatTrim(
            "alpha\n\n[[FLAT_TRIM]]\nkeep this\nand this\n[[/FLAT_TRIM]]\n\nbeta",
            false
        )

        then:
        ownLineKept == 'alpha\n\nkeep this\nand this\n\nbeta'

        when: 'inline markers, dropContent=true: mid-sentence content removed, line structure preserved'
        def inlineDropped = script.stripFlatTrim(
            'prefix.[[FLAT_TRIM]] drop me[[/FLAT_TRIM]]\nnext line',
            true
        )

        then:
        inlineDropped == 'prefix.\nnext line'

        when: 'inline markers, dropContent=false: just tokens removed, content stays'
        def inlineKept = script.stripFlatTrim(
            'prefix.[[FLAT_TRIM]] keep me[[/FLAT_TRIM]]\nnext line',
            false
        )

        then:
        inlineKept == 'prefix. keep me\nnext line'

        when: 'null input returns null'
        def nullOut = script.stripFlatTrim(null, true)

        then:
        nullOut == null

        when: 'no markers: text unchanged'
        def noMarkers = script.stripFlatTrim('just plain text\nno markers here', true)

        then:
        noMarkers == 'just plain text\nno markers here'
    }
}
