package server

import support.ToolSpecBase

/**
 * Pins the human-facing display metadata (issue #245): getToolDisplayMeta()
 * gives every leaf tool AND every gateway a friendly `title` (emitted on the
 * wire as MCP `annotations.title` — what claude.ai renders in place of the
 * bare name) and a one-sentence `summary` (rendered in the Advanced per-tool
 * overrides menu). Covers map completeness/shape, the three wire surfaces
 * (flat tools/list, gateway-mode tools/list, gateway catalog disclosure),
 * the overrides-menu label format, and the search-corpus title enrichment.
 */
class ToolDisplayMetaSpec extends ToolSpecBase {

    def "every leaf tool and every gateway has a display-meta entry, with no stale extras"() {
        when:
        def meta = script.getToolDisplayMeta()
        def leafNames = script.getAllToolDefinitions()*.name as Set
        def gatewayNames = script.getGatewayConfig().keySet() as Set
        def expected = (leafNames + gatewayNames) as Set

        then:
        !meta.isEmpty()
        (expected - meta.keySet()) == [] as Set   // every tool/gateway covered
        (meta.keySet() - expected) == [] as Set   // no entry for a renamed/removed tool
    }

    def "every display-meta entry has a non-empty title and a short single-line summary"() {
        when:
        def meta = script.getToolDisplayMeta()

        then:
        !meta.isEmpty()
        meta.every { name, m ->
            assert m.title instanceof String && m.title.trim() : "${name} is missing a title"
            assert m.summary instanceof String && m.summary.trim() : "${name} is missing a summary"
            assert m.summary.length() <= 140 : "${name} summary exceeds 140 chars (${m.summary.length()})"
            assert !m.summary.contains('\n') : "${name} summary must be a single line"
            assert m.summary.endsWith('.') : "${name} summary must end with a period"
            true
        }
    }

    def "titles are unique across leaf tools and gateways"() {
        // Two tools sharing a friendly name would be indistinguishable in a
        // client UI that renders titles instead of bare names.
        when:
        def titles = script.getToolDisplayMeta().values()*.title

        then:
        titles.size() == (titles as Set).size()
    }

    def "every gateway-mode tools/list entry carries a non-empty annotations.title"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        !tools.isEmpty()
        tools.every {
            it.annotations?.title instanceof String && it.annotations.title.trim()
        }
    }

    def "every flat-mode tools/list entry carries a non-empty annotations.title"() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        !tools.isEmpty()
        tools.every {
            it.annotations?.title instanceof String && it.annotations.title.trim()
        }
    }

    def "known titles render as expected on the wire (leaf, gateway, dev-mode tool)"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableDeveloperMode = true

        when:
        def tools = script.getToolDefinitions()

        then:
        tools.find { it.name == 'hub_get_info' }.annotations.title == 'Get Hub Info'
        tools.find { it.name == 'hub_read_devices' }.annotations.title == 'Read Devices'
        tools.find { it.name == 'hub_manage_destructive_ops' }.annotations.title == 'Manage Destructive Ops'
        tools.find { it.name == 'hub_update_package' }.annotations.title == 'Deploy MCP Package'
    }

    def "annotationsForLeaf emits the title only when display meta is provided"() {
        when:
        def readOnly = ['hub_list_rooms'] as Set
        def withMeta = script.annotationsForLeaf('hub_list_rooms', readOnly,
            [hub_list_rooms: [title: 'List Rooms', summary: 'List all rooms.']])
        def withoutMeta = script.annotationsForLeaf('hub_list_rooms', readOnly)

        then: 'title rides along with the unchanged hint contract'
        withMeta.title == 'List Rooms'
        withMeta.readOnlyHint == true
        !withMeta.containsKey('destructiveHint')

        and: 'the two-arg form (no meta) emits no title key at all'
        !withoutMeta.containsKey('title')
        withoutMeta.readOnlyHint == true
    }

    def "gateway catalog disclosure entries carry the friendly title"() {
        when:
        def result = script.handleGateway('hub_read_rooms', null, null)

        then:
        result.mode == 'catalog'
        !result.tools.isEmpty()
        result.tools.every { it.title instanceof String && it.title.trim() }
        result.tools.find { it.name == 'hub_list_rooms' }.title == 'List Rooms'
    }

    def "overrideOptionLabel formats bare name, friendly name, read-write tag, and summary"() {
        expect:
        script.overrideOptionLabel('hub_list_rooms',
            [title: 'List Rooms', summary: 'List all rooms.'], true) ==
            'hub_list_rooms — List Rooms [read] — List all rooms.'
        script.overrideOptionLabel('hub_delete_room',
            [title: 'Delete Room', summary: 'Permanently delete a room.'], false) ==
            'hub_delete_room — Delete Room [write] — Permanently delete a room.'

        and: 'a missing meta entry falls back to the bare name with no summary tail'
        script.overrideOptionLabel('hub_mystery_tool', null, false) ==
            'hub_mystery_tool — hub_mystery_tool [write]'
    }

    def "search corpus entries carry titles so friendly phrasing is searchable"() {
        given: 'a clean cache'
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
        atomicStateMap.remove('toolSearchCorpus')
        atomicStateMap.remove('toolSearchTokens')

        when: 'a query phrased like the friendly name, not the bare name'
        def result = script.toolSearchTools([query: 'force garbage collection', maxResults: 5])

        then: 'the corpus was enriched with titles and the arcane bare name ranks'
        atomicStateMap.toolSearchCorpus.every { it.title }
        result.results*.tool.contains('hub_call_gc')

        and: 'results surface the friendly title alongside the bare name'
        result.results.find { it.tool == 'hub_call_gc' }.title == 'Force Garbage Collection'

        and: 'the TITLE itself is tokenized into the BM25 doc: hub_set_rule gains the token "author", which appears nowhere in its corpus text (only "authoring" does, and the tokenizer does not stem)'
        def corpus = atomicStateMap.toolSearchCorpus
        def idx = corpus.findIndexOf { it.name == 'hub_set_rule' }
        idx >= 0
        atomicStateMap.toolSearchTokens[idx].contains('author')
    }

    def "a pre-title cached corpus (deployed-over previous version) serves results without titles and without crashing"() {
        // On a live hub this change lands on an atomicState corpus cached by the
        // previous version (entries have no title key) until updated() invalidates.
        // The rebuild guard only checks size alignment, so the stale shape IS served;
        // the result builder must tolerate it.
        given: 'an old-shape cache: one entry, no title key, tokens size-aligned'
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
        atomicStateMap.toolSearchCorpus = [
            [name: 'hub_list_rooms', description: 'List all rooms with IDs, names, and device counts', params: 'cursor', gateway: 'hub_read_rooms']
        ]
        atomicStateMap.toolSearchTokens = [
            ['hub', 'list', 'rooms', 'list', 'all', 'rooms', 'with', 'ids', 'names', 'and', 'device', 'counts', 'cursor']
        ]

        when:
        def result = script.toolSearchTools([query: 'list rooms', maxResults: 5])

        then: 'the stale cache is served as-is: a clean result with no title key'
        result.results.size() == 1
        result.results[0].tool == 'hub_list_rooms'
        !result.results[0].containsKey('title')
    }

    def "buildOverrideOptions keys stay the bare names and labels carry the friendly format"() {
        // The stored disabled_gateways/disabled_tools settings are matched by bare
        // name in getEffectiveDisabledTools() -- a swapped key/label map would
        // silently break every existing override. Pin keys AND label format.
        when:
        def opts = script.buildOverrideOptions()
        def gwConfig = script.getGatewayConfig()
        def expectedToolKeys = script.getAllToolDefinitions()*.name.findAll { !gwConfig.containsKey(it) } as Set

        then: 'option keys are exactly the bare gateway / leaf-tool names'
        opts.gateways.keySet() == gwConfig.keySet()
        (opts.tools.keySet() as Set) == expectedToolKeys

        and: 'labels follow the bare name / friendly name / tag / summary format'
        opts.gateways['hub_read_rooms'] == 'hub_read_rooms — Read Rooms [read] — Read-only room queries: list rooms and room details.'
        opts.tools['hub_delete_device'] == 'hub_delete_device — Delete Device [write] — Permanently delete a device from the hub (no undo).'

        and: 'pure-read gateways are tagged [read], write-bearing ones [write]'
        opts.gateways.findAll { it.key.startsWith('hub_read_') }.values().every { it.contains('[read]') }
        opts.gateways.findAll { it.key.startsWith('hub_manage_') }.values().every { it.contains('[write]') }
    }
}
