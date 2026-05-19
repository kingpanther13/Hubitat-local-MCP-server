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

    // 118,000 = ~6 KB headroom under the hub's 124,000-byte JSON-RPC response cap
    // and ~3 KB tripwire above today's ~115 KB post-trim catalog. Issue #181 named
    // a strict ≤120,000 target; we beat it after extending the trim to the top-4
    // heaviest tools (update_native_app, get_hpm_drift, create_native_app,
    // list_devices). The lower bound (FLAT_CATALOG_BYTE_FLOOR) guards against an
    // accidental over-trim regression where someone drops content unconditionally
    // -- if the catalog ever falls below ~100 KB on a flat-mode run, something
    // important is missing.
    private static final int FLAT_CATALOG_BYTE_BUDGET = 118_000
    private static final int FLAT_CATALOG_BYTE_FLOOR = 100_000
    private static final String OPEN_MARKER = '[[FLAT_TRIM]]'
    private static final String CLOSE_MARKER = '[[/FLAT_TRIM]]'

    private void enableEveryToggle() {
        settingsMap.enableBuiltinApp = true
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
        addTrigger.contains("get_tool_guide(section='update_native_app_reference')")

        and: 'addAction trimmed -- capability list gone, but pointers remain'
        !addAction.contains('Capability families and the spec fields each accepts')
        addAction.contains('docs/rm_action_subtype_schemas.md')
        addAction.contains('{discover: true}')

        and: 'addRequiredExpression trimmed -- STPage enum gone, get_tool_guide pointer remains'
        !addRequiredExpression.contains("RM's STPage capability list")
        addRequiredExpression.contains("get_tool_guide(section='update_native_app_reference')")
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

    def "schema description pointers resolve to existing get_tool_guide sections"() {
        given: 'schema descriptions tell callers to fetch reference content via get_tool_guide(section=X)'
        def allDefs = script.getAllToolDefinitions()
        def updateNativeDef = allDefs.find { it.name == 'update_native_app' }
        def listDevicesDef = allDefs.find { it.name == 'list_devices' }
        def hpmDriftDef = allDefs.find { it.name == 'get_hpm_drift' }
        def createNativeDef = allDefs.find { it.name == 'create_native_app' }
        def addTriggerDesc = updateNativeDef.inputSchema.properties.addTrigger.description as String
        def addActionDesc = updateNativeDef.inputSchema.properties.addAction.description as String
        def addRequiredExprDesc = updateNativeDef.inputSchema.properties.addRequiredExpression.description as String
        def listDevicesFieldsDesc = listDevicesDef.inputSchema.properties.fields.description as String

        when: 'collect every section name from get_tool_guide and every pointer in the schemas'
        def validSections = script.getToolGuideSections().keySet() as Set
        def pointerPattern = ~/get_tool_guide\(section\s*=\s*['"]([a-z_]+)['"]\)/
        def descriptionsToCheck = [
            addTriggerDesc, addRequiredExprDesc,
            listDevicesDef.description as String, listDevicesFieldsDesc,
            hpmDriftDef.description as String,
            createNativeDef.description as String,
        ]
        def pointerSections = [] as Set
        descriptionsToCheck.each { desc ->
            def m = pointerPattern.matcher(desc)
            while (m.find()) {
                pointerSections << m.group(1)
            }
        }

        then: 'every get_tool_guide(section=...) pointer in the schemas references a section that actually exists'
        // Guards against silent breakage if a section is renamed in getToolGuideSections
        // or a pointer is typoed. The previous test version pinned against TOOL_GUIDE.md
        // headings -- correct as far as it went, but didn't ensure the content was
        // actually reachable through the MCP layer at runtime. This is the test that
        // proves flat-mode callers can fetch the reference content the trim points them at.
        //
        // 4 unique sections across the 5 trimmed description sites:
        // - update_native_app_reference (addTrigger + addRequiredExpression both point here)
        // - performance (list_devices top-level + .fields property)
        // - builtin_app_tools (get_hpm_drift)
        // - create_native_app_reference (create_native_app)
        pointerSections.containsAll(['update_native_app_reference', 'performance', 'builtin_app_tools', 'create_native_app_reference'])
        pointerSections.every { it in validSections }

        and: 'each of the 4 trimmed tools carries at least one valid get_tool_guide pointer in its flat-mode reachable description text'
        addTriggerDesc =~ pointerPattern
        addRequiredExprDesc =~ pointerPattern
        listDevicesDef.description =~ pointerPattern
        hpmDriftDef.description =~ pointerPattern
        createNativeDef.description =~ pointerPattern

        and: 'addAction continues to rely on {discover: true} + docs/rm_action_subtype_schemas.md (separate path; covered for completeness)'
        addActionDesc.contains('{discover: true}')
        addActionDesc.contains('docs/rm_action_subtype_schemas.md')
        new File('docs/rm_action_subtype_schemas.md').exists()

        and: 'the two new sections this PR added are present and non-empty'
        validSections.contains('update_native_app_reference')
        validSections.contains('create_native_app_reference')
        (script.getToolGuideSections()['update_native_app_reference'] as String).length() > 500
        (script.getToolGuideSections()['create_native_app_reference'] as String).length() > 200
    }

    def "get_tool_guide end-to-end: dispatcher resolves the new section keys and returns sentinel-bearing content"() {
        // The pointer-resolution test above asserts the keys exist in the
        // getToolGuideSections() map. This test exercises the actual runtime
        // dispatch path -- toolGetToolGuide() does case-folding + non-alnum
        // normalization (see hubitat-mcp-server.groovy normalization in
        // toolGetToolGuide), and the schema enum gate guards the section
        // parameter. A future refactor that wraps the dispatcher in a guard,
        // tightens the normalization, or shifts the success-envelope shape
        // would slip past the previous test but not this one.
        when: 'fetch the two PR-introduced sections through the live dispatcher'
        def updateNativeResult = script.toolGetToolGuide('update_native_app_reference')
        def createNativeResult = script.toolGetToolGuide('create_native_app_reference')

        then: 'success envelope with content matching the section names'
        updateNativeResult.success == true
        updateNativeResult.section == 'update_native_app_reference'
        createNativeResult.success == true
        createNativeResult.section == 'create_native_app_reference'

        and: 'content carries sentinel phrases unique to each section -- catches drift to a stub even if the map key survives'
        // update_native_app_reference must include all three sub-headings (catches
        // a stub-replacement that drops one of the three families).
        updateNativeResult.content.contains('`addTrigger` capability families')
        updateNativeResult.content.contains('`addAction` capability families')
        updateNativeResult.content.contains('`addRequiredExpression` STPage capability list')
        // Plus a few specific capability names from the deepest bullets so a
        // halving of the body during refactor would show up here.
        updateNativeResult.content.contains('Periodic Schedule')
        updateNativeResult.content.contains('Custom Attribute')

        and: 'create_native_app_reference must include both subsections'
        createNativeResult.content.contains('appType options')
        createNativeResult.content.contains('Partial-success protocol')
        createNativeResult.content.contains('partialTriggers')
        createNativeResult.content.contains('repairHints')

        and: 'apostrophe escape regression guard -- the bake must not introduce stray backslashes (cf review of b6fe4ab where action\\\'s appeared as a transcription error)'
        // If the bake reintroduces backslash-escaped apostrophes, the rendered
        // text would contain a literal backslash followed by an apostrophe.
        !updateNativeResult.content.contains("action\\'s")
        !createNativeResult.content.contains("action\\'s")
    }

    def "the 3 newly-trimmed tools strip their wrapped content in flat mode but keep it in gateway mode"() {
        // list_devices, get_hpm_drift, and create_native_app each wrap a block of
        // duplicate-of-TOOL_GUIDE prose. This is the per-tool counterpart to the
        // update_native_app gateway-catalog-preservation test (above) -- catches
        // a regression where a marker on just ONE of these tools is unbalanced
        // (content fails to drop in flat mode) or where the whole transform path
        // bypasses that tool's description.
        given:
        enableEveryToggle()

        when: 'flat-mode descriptions'
        settingsMap.useGateways = false
        def flatTools = script.getToolDefinitions()
        def listDevicesFlat = flatTools.find { it.name == 'list_devices' }.description as String
        def hpmDriftFlat = flatTools.find { it.name == 'get_hpm_drift' }.description as String
        def createNativeFlat = flatTools.find { it.name == 'create_native_app' }.description as String

        and: 'gateway-mode (token-only strip) descriptions via getAllToolDefinitions + applyDescriptionTransform(false)'
        def gwAll = script.applyDescriptionTransform(script.getAllToolDefinitions(), false)
        def listDevicesGw = gwAll.find { it.name == 'list_devices' }.description as String
        def hpmDriftGw = gwAll.find { it.name == 'get_hpm_drift' }.description as String
        def createNativeGw = gwAll.find { it.name == 'create_native_app' }.description as String

        then: 'flat-mode strips the wrapped sentinel prose -- specific phrases from each wrapped block must be absent'
        !listDevicesFlat.contains('Server-side filtering (all applied before pagination)')
        !hpmDriftFlat.contains('Drift signal types:')
        !hpmDriftFlat.contains('Data-quality warning types in dataQualityWarnings[]')
        !createNativeFlat.contains('PARTIAL-SUCCESS HANDLING')

        and: 'flat-mode keeps a get_tool_guide pointer for each -- callers must have a fallback'
        listDevicesFlat.contains('get_tool_guide(')
        hpmDriftFlat.contains('get_tool_guide(')
        createNativeFlat.contains('get_tool_guide(')

        and: 'gateway-mode keeps the same sentinel phrases (token-only strip preserves content)'
        listDevicesGw.contains('Server-side filtering (all applied before pagination)')
        hpmDriftGw.contains('Drift signal types:')
        hpmDriftGw.contains('Data-quality warning types in dataQualityWarnings[]')
        createNativeGw.contains('PARTIAL-SUCCESS HANDLING')

        and: 'no marker tokens leak in either mode for any of the three'
        ![listDevicesFlat, hpmDriftFlat, createNativeFlat,
          listDevicesGw, hpmDriftGw, createNativeGw].any {
            it.contains(OPEN_MARKER) || it.contains(CLOSE_MARKER)
        }
    }

    def "list_devices.fields inline-marker drops the valid-name enumeration in flat mode but keeps it in gateway mode"() {
        // list_devices isn't behind a gateway, but its `fields` property has an
        // INLINE [[FLAT_TRIM]] marker (different from update_native_app's
        // addRequiredExpression case in terms of placement). Pin both directions
        // explicitly so a regression in the inline-strip path surfaces here.
        given:
        enableEveryToggle()

        when: 'flat-mode list_devices.fields description (dropContent=true)'
        settingsMap.useGateways = false
        def flatFieldsDesc = script.getToolDefinitions()
            .find { it.name == 'list_devices' }
            .inputSchema.properties.fields.description as String

        and: 'gateway-mode list_devices.fields description (dropContent=false -- tokens-only strip)'
        def gwFieldsDesc = script.applyDescriptionTransform(script.getAllToolDefinitions(), false)
            .find { it.name == 'list_devices' }
            .inputSchema.properties.fields.description as String

        then: 'flat-mode strips the valid-name enumeration; the pointer to get_tool_guide survives'
        // 'mcpManaged' is a field name inside the wrapped block; "auto-promotes"
        // is the start of the projection-semantics paragraph also inside the
        // marker. Both must be absent in flat mode.
        !flatFieldsDesc.contains('mcpManaged')
        !flatFieldsDesc.contains('auto-promotes the response to detailed mode')
        flatFieldsDesc.contains("get_tool_guide(section='performance')")

        and: 'gateway-mode keeps the full enumeration; marker tokens stripped'
        gwFieldsDesc.contains('mcpManaged')
        gwFieldsDesc.contains('auto-promotes the response to detailed mode')
        !gwFieldsDesc.contains(OPEN_MARKER)
        !gwFieldsDesc.contains(CLOSE_MARKER)
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
