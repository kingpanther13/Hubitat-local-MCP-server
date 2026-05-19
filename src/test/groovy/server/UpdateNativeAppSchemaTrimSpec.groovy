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

    // 121,000 = ~3 KB headroom under the hub's 124,000-byte JSON-RPC response cap
    // (matches the "~3 KB headroom" goal in issue #181). The strict ≤120,000 number
    // from the issue body assumed a pre-trim catalog of ~123 KB, which has since
    // grown ~7 KB on main (PRs #168/#169/#174/#180/#182/#186/#188 all added schema
    // content after the issue was written). The trim recovers ~10 KB at the
    // update_native_app description level; that puts the flat catalog comfortably
    // back under-cap, even though the strict 120,000 target now needs additional
    // tool trims beyond this PR's "lift capability enumerations" scope.
    private static final int FLAT_CATALOG_BYTE_BUDGET = 121_000
    private static final String OPEN_MARKER = '[[FLAT_TRIM]]'
    private static final String CLOSE_MARKER = '[[/FLAT_TRIM]]'

    private void enableEveryToggle() {
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true
    }

    def "flat-mode tools/list full-catalog size keeps ~3 KB headroom under the 124,000-byte cap"() {
        given: 'every feature toggle on so the flat catalog is at its widest'
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        // Defense-in-depth on issue #181's headroom goal. handleToolsList paginates at
        // page size 50, so no single wire response carries this whole catalog -- this
        // test guards the total catalog footprint, not a wire-cap. Keeping the catalog
        // sub-124 KB protects flat-mode clients that fetch the whole catalog at once
        // (custom MCP wrappers, debug dumps) and protects pagination-fetch clients
        // from per-page bloat that would still degrade hub response time.
        def tools = script.getToolDefinitions()
        def catalogBytes = JsonOutput.toJson(tools).getBytes('UTF-8').length

        then: 'budget keeps ~3 KB headroom under the hub 124,000-byte JSON-RPC cap'
        // Diagnostic on failure -- a future regression that pushes the catalog over
        // budget should land with a list of the heaviest tools so the author knows
        // where to start trimming. Spock's verifyAll-style block keeps the assertion
        // shape compatible with the existing dispatch test's `assert <= N` pattern.
        if (catalogBytes > FLAT_CATALOG_BYTE_BUDGET) {
            def topFive = tools.collect { [name: it.name, bytes: JsonOutput.toJson(it).getBytes('UTF-8').length] }
                               .sort { -it.bytes }
                               .take(5)
                               .collect { "  ${it.name}: ${it.bytes} B" }
                               .join('\n')
            throw new AssertionError(
                "flat-mode tools/list catalog ${catalogBytes} B exceeds budget ${FLAT_CATALOG_BYTE_BUDGET} B.\n" +
                "Top 5 tools by JSON size:\n${topFive}\n" +
                "Consider wrapping more prose in [[FLAT_TRIM]] markers (issue #181)." as String
            )
        }
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

        when: 'empty content between own-line markers: block removed cleanly'
        def emptyOwnLine = script.stripFlatTrim("before\n\n[[FLAT_TRIM]]\n\n[[/FLAT_TRIM]]\n\nafter", true)

        then:
        emptyOwnLine == 'before\n\nafter'

        when: 'unmatched open marker, dropContent=true: passes through unchanged so CI leak guard trips loud'
        def lonelyOpenDrop = script.stripFlatTrim('before [[FLAT_TRIM]] tail with no close', true)

        then: 'paired markers required; a stray open is left visible (fail-loud, not silent data loss)'
        lonelyOpenDrop == 'before [[FLAT_TRIM]] tail with no close'

        when: 'unmatched open marker, dropContent=false: silently stripped (intentional asymmetry)'
        def lonelyOpenKeep = script.stripFlatTrim('before [[FLAT_TRIM]] tail with no close', false)

        then: 'token-strip mode strips ANY lone marker since the marker is itself the bug to prevent'
        lonelyOpenKeep == 'before  tail with no close'

        when: 'unmatched close marker, dropContent=false: same lone-token strip applies'
        def lonelyCloseKeep = script.stripFlatTrim('before [[/FLAT_TRIM]] tail with no open', false)

        then:
        lonelyCloseKeep == 'before  tail with no open'
    }

    def "search_tools BM25 corpus contains no marker tokens in any top-level description"() {
        given:
        enableEveryToggle()

        when:
        def corpus = script.buildToolSearchCorpus()

        then: 'no marker tokens leak into the indexed corpus text'
        // SCOPE NOTE: `buildToolSearchCorpus` only feeds `tool.description` (core
        // branch) or `summary + gateway.description` (gateway branch) into BM25 --
        // it does NOT index `inputSchema.properties.*.description`. So today every
        // [[FLAT_TRIM]] marker in the schema (all of them in `update_native_app`'s
        // property descriptions) is structurally outside this guard's reach.
        //
        // The guard is defensive against a future core tool wrapping prose in its
        // top-level description: if `applyDescriptionTransform` were skipped on the
        // core branch (`hubitat-mcp-server.groovy:21825`), the marker would surface
        // here as a BM25 token. We also don't assert on `params` (property names
        // only -- structurally can't carry markers) or `hints` (author-controlled
        // gatewayConfig text -- structurally can't carry markers either).
        corpus.every {
            !((String) (it.description ?: '')).contains(OPEN_MARKER) &&
            !((String) (it.description ?: '')).contains(CLOSE_MARKER)
        }
    }

    def "applyDescriptionTransform is idempotent -- a second call leaves the descriptions unchanged"() {
        given: 'fresh tool defs with FLAT_TRIM markers in place'
        def first = script.applyDescriptionTransform(script.getAllToolDefinitions(), true)
        def afterFirst = JsonOutput.toJson(first)

        when: 'apply the same transform a second time on the same in-place mutated list'
        def second = script.applyDescriptionTransform(first, true)
        def afterSecond = JsonOutput.toJson(second)

        then: 'the second pass is a no-op -- second call must produce identical bytes'
        // Today no consumer caches the list, but a future memoization refactor that
        // does would silently double-strip without this guard.
        afterFirst == afterSecond

        when: 'same check for dropContent=false'
        def firstFalse = script.applyDescriptionTransform(script.getAllToolDefinitions(), false)
        def secondFalse = script.applyDescriptionTransform(firstFalse, false)

        then:
        JsonOutput.toJson(firstFalse) == JsonOutput.toJson(secondFalse)
    }

    def "schema description pointers match TOOL_GUIDE.md anchor names"() {
        given: 'the three update_native_app schema descriptions name-drop specific TOOL_GUIDE subsections'
        def updateNativeDef = script.getAllToolDefinitions().find { it.name == 'update_native_app' }
        def addTriggerDesc = updateNativeDef.inputSchema.properties.addTrigger.description as String
        def addActionDesc = updateNativeDef.inputSchema.properties.addAction.description as String
        def addRequiredExprDesc = updateNativeDef.inputSchema.properties.addRequiredExpression.description as String

        when:
        // Resolve TOOL_GUIDE.md relative to the project root (gradle runs from there).
        def toolGuide = new File('TOOL_GUIDE.md').text

        then: 'every TOOL_GUIDE anchor cited in the schema actually exists as a section heading'
        // Guards against a silent renaming of a TOOL_GUIDE subsection breaking the
        // in-schema pointer that flat-mode callers rely on after the trim.
        addTriggerDesc.contains('TOOL_GUIDE.md')
        addActionDesc.contains('docs/rm_action_subtype_schemas.md')
        addRequiredExprDesc.contains('TOOL_GUIDE.md')

        and: 'the four anchors the schema names exist as markdown headings'
        toolGuide.contains('#### `update_native_app` capability reference')
        toolGuide.contains('##### `addTrigger` capability families')
        toolGuide.contains('##### `addAction` capability families')
        toolGuide.contains('##### `addRequiredExpression` STPage capability list')

        and: 'rm_action_subtype_schemas.md (referenced from addAction) still exists'
        new File('docs/rm_action_subtype_schemas.md').exists()
    }

    def "missing-param hint surface for update_native_app strips marker tokens but keeps wrapped capability prose"() {
        given: 'gateway dispatch to update_native_app without required appId triggers the missing-param hint path'
        settingsMap.useGateways = true
        enableEveryToggle()

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'update_native_app', [:])

        then: 'pre-validation surfaces a structured error envelope'
        result instanceof Map
        result.isError == true
        result.error?.contains('Missing required parameter')

        and: 'parameter description text in the hint has no leaking marker tokens'
        result.parameters instanceof String
        !((String) result.parameters).contains(OPEN_MARKER)
        !((String) result.parameters).contains(CLOSE_MARKER)

        and: 'wrapped capability prose from BOTH addTrigger and addRequiredExpression survives'
        // The strip-tokens-only contract on this surface should keep both wrapped
        // blocks intact -- an OR-based assertion would silently mask one description
        // going stale while the other survives.
        ((String) result.parameters).contains('Periodic Schedule')      // from addTrigger FLAT_TRIM block
        ((String) result.parameters).contains("RM's STPage capability list")  // from addRequiredExpression inline FLAT_TRIM
    }
}
