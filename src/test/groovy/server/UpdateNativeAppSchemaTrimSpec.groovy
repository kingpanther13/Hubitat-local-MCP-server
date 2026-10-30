package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * The flat tools/list catalog must fit the hub's 124,000-byte response cap.
 * The flat-mode transform uses `[[FLAT_TRIM]] ... [[/FLAT_TRIM]]`
 * markers in the schema descriptions are STRIPPED WITH CONTENT in flat mode
 * (recover the bytes) and STRIPPED WITHOUT CONTENT (just the tokens) in every
 * other client-visible surface (gateway-catalog mode, hub_search_tools corpus,
 * missing-param error hints).
 *
 * These tests pin the contract end-to-end so a future author who adds new
 * markers, removes them, or breaks the transform learns about it from CI.
 */
class UpdateNativeAppSchemaTrimSpec extends ToolSpecBase {

    // 122,000 = ~2 KB headroom under the hub's 124,000-byte JSON-RPC response cap.
    // The earlier 118,000 budget had ~6 KB headroom but no MCP `annotations.readOnlyHint`
    // / `destructiveHint` keys -- adding those for client-side Read/Write grouping
    // costs ~5 KB across the ~100-tool flat catalog. The 2 KB residual headroom
    // is still wider than any single description's variance. The lower bound
    // (FLAT_CATALOG_BYTE_FLOOR) guards against an accidental over-trim regression
    // where someone drops content unconditionally -- if the catalog ever falls
    // below ~100 KB on a flat-mode run, something important is missing.
    private static final int FLAT_CATALOG_BYTE_BUDGET = 122_000
    private static final int FLAT_CATALOG_BYTE_FLOOR = 100_000
    private static final String OPEN_MARKER = '[[FLAT_TRIM]]'
    private static final String CLOSE_MARKER = '[[/FLAT_TRIM]]'

    // Deliberately leaves Developer Mode OFF: the [FLOOR, BUDGET] calibration below
    // is for the default catalog a normal hub serves. The wider dev-mode-on surface
    // (adds hub_update_package) gets its own hard-cap + leak guard further down.
    private void enableEveryToggle() {
        settingsMap.enableCustomRuleEngine = true
    }

    def "flat-mode tools/list full-catalog size sits between the floor and budget"() {
        given: 'every feature toggle on so the flat catalog is at its widest'
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        // tools/list now returns the full catalog in a single response (pagination
        // was removed in this PR); this test guards the total catalog footprint
        // as the regression tripwire. The universal response-size guard at
        // handleMcpRequest's response path emits -32603 if the wire response
        // exceeds 124,000 bytes -- keep the catalog comfortably below that with
        // the budget tripwire below.
        def tools = script.getToolDefinitions()
        def catalogBytes = JsonOutput.toJson(tools).getBytes('UTF-8').length

        then: 'catalog within [FLOOR, BUDGET] -- ~6 KB cap headroom + over-trim guard'
        if (catalogBytes > FLAT_CATALOG_BYTE_BUDGET || catalogBytes < FLAT_CATALOG_BYTE_FLOOR) {
            def topFive = tools.collect { [name: it.name, bytes: JsonOutput.toJson(it).getBytes('UTF-8').length] }
                               .sort { -it.bytes }
                               .take(5)
                               .collect { "  ${it.name}: ${it.bytes} B" }
                               .join('\n')
            throw new AssertionError(
                "flat-mode tools/list catalog ${catalogBytes} B is outside [${FLAT_CATALOG_BYTE_FLOOR}, ${FLAT_CATALOG_BYTE_BUDGET}].\n" +
                "Top 5 tools by JSON size:\n${topFive}\n" +
                "Over-budget? Wrap more prose in [[FLAT_TRIM]] markers (issue #181).\n" +
                "Under-floor? Someone dropped content unconditionally -- check recent edits." as String
            )
        }
        catalogBytes <= FLAT_CATALOG_BYTE_BUDGET
        catalogBytes >= FLAT_CATALOG_BYTE_FLOOR
    }

    def "flat-mode tools/list catalog does not leak [[FLAT_TRIM]] tokens"() {
        given:
        settingsMap.useGateways = false
        enableEveryToggle()

        when:
        // One build, both assertions: a second getToolDefinitions() call would let the named
        // scan and the serialized payload disagree if a leak ever depended on build state.
        def tools = script.getToolDefinitions()
        def catalogJson = JsonOutput.toJson(tools)

        then: 'neither opening nor closing marker appears in the wire payload'
        // Named, not just counted: on a 116-tool catalog a bare contains() failure prints the
        // whole payload and never says which tool leaked.
        tools.findAll {
            def j = JsonOutput.toJson(it)
            j.contains(OPEN_MARKER) || j.contains(CLOSE_MARKER)
        }*.name == []
        !catalogJson.contains(OPEN_MARKER)
        !catalogJson.contains(CLOSE_MARKER)
    }

    def "flat-mode hub_restore_backup keeps its verify-before-retry warning"() {
        // A blind retry of a lost restore response restores and reboots the whole hub a second
        // time, so this sentence survives the flat trim even though other writes drop theirs.
        given:
        settingsMap.useGateways = false

        when:
        def restore = script.getToolDefinitions().find { it.name == 'hub_restore_backup' }

        then:
        restore.description.contains('A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying.')
    }

    def "flat-mode tools/list with Developer Mode ON stays under the hub cap and leaks no FLAT_TRIM tokens"() {
        // hub_update_package is catalog-hidden unless enableDeveloperMode is true, so
        // the default-toggles guards above never serialize it -- its description carries
        // FLAT_TRIM markers and its entry widens the flat catalog past what the budget
        // test measures. This is the only guard for the widest catalog a real hub can
        // emit; over the hard cap, flat+dev clients get -32603 and ZERO tools.
        given:
        settingsMap.useGateways = false
        enableEveryToggle()
        settingsMap.enableDeveloperMode = true

        when:
        def tools = script.getToolDefinitions()
        def catalogJson = JsonOutput.toJson([tools: tools])
        def catalogBytes = catalogJson.getBytes('UTF-8').length

        then: 'the dev-mode tool is actually present (guard is not vacuous)'
        tools*.name.contains('hub_update_package')

        and: 'under the hub hard cap with the dev-mode entry included, less an allowance for the JSON-RPC envelope (jsonrpc/id/result wrapper) the live response adds around this payload'
        assert catalogBytes < 123_500 : "dev-mode flat tools/list payload is ${catalogBytes} bytes; with the JSON-RPC envelope the live response must stay under the 124,000 hub cap"

        and: 'no marker tokens leak from the dev-mode entry (or any other)'
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

    def "native document definitions remain lean in both public catalogs"() {
        given:
        enableEveryToggle()
        settingsMap.useGateways = false
        when:
        def flat = script.getToolDefinitions().find { it.name == 'hub_set_rule' }
        settingsMap.useGateways = true
        def gateway = script.handleGateway('hub_manage_rule_machine', null, [:]).tools.find { it.name == 'hub_set_rule' }
        then:
        flat && gateway
        flat.inputSchema == gateway.inputSchema
        flat.description.length() <= gateway.description.length()
        flat.annotations.title == gateway.title
        flat.description.contains('hub_get_tool_guide')
        gateway.description.contains('hub_get_tool_guide')
        JsonOutput.toJson(flat).getBytes('UTF-8').length < 4000
        JsonOutput.toJson(gateway).getBytes('UTF-8').length < 4000
    }

    def "retired authoring tools have no catalog aliases in flat or gateway mode"() {
        given:
        enableEveryToggle()
        expect:
        !script.getAllToolDefinitions().any { it.name in ['hub_get_rule_document', 'hub_create_rule_document', 'hub_update_rule_document', 'hub_call_app_form'] }
        !script.getGatewayConfig().values().any { it.tools.any { name -> name in ['hub_get_rule_document', 'hub_create_rule_document', 'hub_update_rule_document', 'hub_call_app_form'] } }
    }

    def "native guides describe supported contracts without claiming full capability parity"() {
        expect:
        script.toolGetToolGuide('set_rule_reference').content.contains('not every capability-specific variant is supported')
        script.toolGetToolGuide('set_rule_reference').content.contains('unsupported')
        script.getToolGuideSections().containsKey('set_rule_reference')
    }

    def "stripFlatTrim strips include-library line markers on every description surface (issue #342)"() {
        // The hub appends "// library marker mcp.<Lib>, line N" to every physical line of
        // an #include'd library, so a multi-line \"\"\" string in a library file (three tool
        // descriptions had them) carries the markers into its runtime value on a real hub.
        // The harness re-inlines libraries WITHOUT markers, so catalog-level tests can't
        // see the leak -- pin the chokepoint helper in both dropContent modes instead.
        expect:
        script.stripFlatTrim('Create a device. // library marker mcp.McpVirtualDevicesLib, line 295\nNext line. // library marker mcp.McpVirtualDevicesLib, line 296', dropContent) ==
            'Create a device.\nNext line.'

        where:
        dropContent << [true, false]
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

    def "hub_search_tools BM25 corpus contains no marker tokens in any top-level description"() {
        given:
        enableEveryToggle()

        when:
        def corpus = script.buildToolSearchCorpus()

        then: 'no marker tokens leak into the indexed corpus text'
        // SCOPE NOTE: `buildToolSearchCorpus` only feeds `tool.description` (core
        // branch) or `summary + gateway.description` (gateway branch) into BM25 --
        // it does NOT index `inputSchema.properties.*.description`. So today every
        // [[FLAT_TRIM]] marker in the schema (all of them in `hub_set_rule`'s
        // property descriptions) is structurally outside this guard's reach.
        //
        // The guard is defensive against a future core tool wrapping prose in its
        // top-level description: if `applyDescriptionTransform` were skipped on the
        // core branch (`libraries/mcp-native-rules-lib.groovy`), the marker would surface
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

    def "schema description pointers resolve to existing hub_get_tool_guide sections"() {
        given:
        def validSections = (script.getToolGuideSections().keySet() as Set) +
            (script.getToolGuideSubSections().values().collectMany { it.keySet().toList() } as Set)
        def pattern = ~/hub_get_tool_guide\(section\s*=\s*['"]([a-z_]+)['"]\)/
        def pointers = [] as Set
        Closure visit
        visit = { value ->
            if (value instanceof Map) value.each { key, child -> visit(child) }
            else if (value instanceof List) value.each { visit(it) }
            else if (value instanceof String) {
                def matcher = pattern.matcher(value)
                while (matcher.find()) pointers << matcher.group(1)
            }
        }
        when:
        visit(script.getAllToolDefinitions())
        then:
        pointers.containsAll(['performance_devices', 'builtin_app_tools_apps'])
        pointers.every { it in validSections }
    }

    def "hub_get_tool_guide section enum stays in lockstep with getToolGuideSections() (#296 reachability guard)"() {
        given: 'the advertised section enum on hub_get_tool_guide, and the served section + sub-section maps'
        def guideDef = script.getAllToolDefinitions().find { it.name == 'hub_get_tool_guide' }
        def enumKeys = (guideDef.inputSchema.properties.section.enum as List) as Set
        def sectionKeys = script.getToolGuideSections().keySet() as Set
        // Issue #392: the enum also advertises the sub-keys of the four oversized sections; both
        // resolve through the same `section` parameter, so both belong in this lockstep check.
        def subSectionKeys = script.getToolGuideSubSections().values().collectMany { it.keySet() } as Set

        expect: 'every advertised enum key resolves to a real served section or sub-section -- a schema-valid client is never pointed at a missing one'
        (enumKeys - sectionKeys - subSectionKeys).isEmpty()

        and: 'every served section is advertised in the enum -- a newly added section is discoverable, not orphaned. The #296 defect this guards: dashboards/bundles/rooms/variables were served by getToolGuideSections() but absent from the enum, so schema-validating clients (and LLMs reading the enum) could not request them.'
        (sectionKeys - enumKeys).isEmpty()

        and: 'every served sub-section is advertised too -- same orphaning failure, one level down'
        (subSectionKeys - enumKeys).isEmpty()
    }

    def "hub_get_tool_guide dispatcher serves new native guides with their actual contracts"() {
        when:
        def documents = script.toolGetToolGuide('set_rule_reference')
        def forms = script.toolGetToolGuide('set_rule_reference')
        then:
        documents.success && forms.success
        documents.section == 'set_rule_reference'
        forms.section == 'set_rule_reference'
        documents.content.contains('Typed fields use exact native names')
        documents.content.contains('Partial') || documents.content.contains('partial')
        forms.content.contains('Even open renders execute app code')
        forms.content.contains('never automatically retry') || forms.content.contains('instead of replaying buttons')
    }

    def "hub_list_devices carries no deferred prose: flat and gateway modes serve the same compact definition"() {
        given:
        enableEveryToggle()
        settingsMap.useGateways = false
        def flat = script.getToolDefinitions().find { it.name == 'hub_list_devices' }
        def gateway = script.applyDescriptionTransform(script.getAllToolDefinitions(), false).find { it.name == 'hub_list_devices' }

        expect:
        flat.description == gateway.description
        flat.inputSchema == gateway.inputSchema
        flat.description.contains("hub_get_tool_guide(section='performance_devices')")
        groovy.json.JsonOutput.toJson([name: flat.name, description: flat.description, inputSchema: flat.inputSchema])
            .getBytes('UTF-8').length <= 1300
        ![flat.description, groovy.json.JsonOutput.toJson(flat.inputSchema)].any { it.contains(OPEN_MARKER) || it.contains(CLOSE_MARKER) }
    }

    def "tools/list size-guard backstop emits -32603 if the catalog ever overflows the 124,000-byte cap"() {
        given: 'a stubbed getToolDefinitions returning a synthetic catalog larger than the 124,000-byte hub cap'
        // This pins the failure-mode rationale documented in handleToolsList: with
        // tools/list no longer paginating, the universal response-size guard inside
        // handleMcpRequest is the only thing standing between an oversized catalog
        // and a silent oversized wire response. If a future PR adds enough catalog
        // content that the response crosses 124 KB, callers should see a loud
        // -32603 envelope, NOT a truncated or hub-rejected response.
        def jumbo = (1..200).collect { i ->
            [
                name: "synthetic_jumbo_${i}",
                description: 'x' * 1200,  // ~240 KB total catalog
                inputSchema: [type: "object", properties: [:]]
            ]
        }
        script.metaClass.getToolDefinitions = { -> jumbo }
        mcpDriver.pushBody([jsonrpc: '2.0', id: 999, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then: 'loud -32603 envelope with "Response too large" message; no silent truncation'
        response.error != null
        response.error.code == -32603
        response.error.message.toLowerCase().contains('response too large')

        // HarnessSpec.setup() wipes the per-instance metaClass and the class-level
        // ExpandoMetaClass before every feature, so no explicit cleanup needed
        // here -- next test gets a clean script instance.
    }

    def "native update probe exposes required fields without trim markers"() {
        when:
        def probe = script.handleGateway('hub_manage_rule_machine', 'hub_set_rule', [operation: 'update'])
        then:
        probe.confirm_required
        probe.inputSchema.required.containsAll(['rule_id', 'args', 'confirm'])
        probe.inputSchema.properties.args.required == ['changes']
        !JsonOutput.toJson(probe).contains(OPEN_MARKER)
        !JsonOutput.toJson(probe).contains(CLOSE_MARKER)
    }

    def "legacy guide flag is rejected through the rule gateway"() {
        when:
        script.handleGateway('hub_manage_rule_machine', 'hub_set_rule', [guide: true])
        then:
        thrown(IllegalArgumentException)
    }

    def "#tool intentionally carries NO hub_get_tool_guide pointer in flat mode (kept lean; pin the intent)"() {
        // These tools deliberately fold their entire guide pointer INSIDE [[FLAT_TRIM]], so the
        // flat catalog stays lean and the pointer only shows in gateway mode. That is a choice, not an
        // accident -- pin it so a future edit that either (a) surfaces a flat pointer or (b) drops the
        // gateway pointer trips CI and gets a deliberate re-decision.
        given:
        enableEveryToggle()
        settingsMap.enableDeveloperMode = true

        when: 'the flat-mode (dropContent=true) description, the gateway-mode render, and the raw authored def'
        settingsMap.useGateways = false
        def flatDesc = script.getToolDefinitions().find { it.name == tool }?.description as String
        def gwDesc = script.applyDescriptionTransform(script.getAllToolDefinitions(), false)
            .find { it.name == tool }?.description as String
        def rawDesc = script.getAllToolDefinitions().find { it.name == tool }?.description as String

        then: 'the tool is present flat, the gateway render is found, and its raw def is found'
        flatDesc != null
        gwDesc != null
        rawDesc != null

        and: 'flat has NO pointer (lean), but the pointer SURVIVES into the gateway-mode render -- deferred, not lost'
        !flatDesc.contains('hub_get_tool_guide(')
        gwDesc.contains('hub_get_tool_guide(')

        and: 'the raw def wraps that pointer in FLAT_TRIM -- which is why the flat render drops it'
        rawDesc.contains('hub_get_tool_guide(')
        rawDesc.contains('[[FLAT_TRIM]]')

        where:
        tool << ['hub_list_rules', 'hub_get_hub_mesh', 'hub_update_hub_mesh']
    }
}
