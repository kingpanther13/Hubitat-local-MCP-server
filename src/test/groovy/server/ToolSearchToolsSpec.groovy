package server

import support.ToolSpecBase

/**
 * Direct-call coverage for the BM25 hub_search_tools path after the PR2c perf change
 * (bm25-corpus-state-not-atomicstate + bm25-df-table-rebuild-Q15): the corpus and the
 * per-doc tokenization are cached in the class-static TOOL_SEARCH_INDEX, keyed by the corpus
 * fingerprint (index-aligned to the FULL corpus) and served from there -- NOT in atomicState,
 * where the two lists were ~244 KB the hub re-serialised on every execution. df/avgDl are
 * recomputed over the visible subset so ranking stays byte-identical. These pin: a stable
 * ranked shape, cache-hit (no rebuild), per-request visibility filtering over the shared
 * full-corpus token cache, updated() invalidation in lockstep with the gateway requiredParams
 * memo, and the shedding of the legacy atomicState keys an older build left behind.
 */
class ToolSearchToolsSpec extends ToolSpecBase {

    private void searchEnabled() {
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
    }

    def "search_tools returns a well-formed ranked result list (sorted descending, deduped)"() {
        given:
        searchEnabled()

        when:
        def result = script.toolSearchTools([query: 'switch motion contact', maxResults: 5])

        then: 'non-empty, bounded, deduped by tool name, sorted by descending relevance'
        result.results.size() > 0
        result.results.size() <= 5
        def names = result.results*.tool
        names == names.unique()
        def rel = result.results*.relevance
        rel == rel.sort { -it }
    }

    def "advanced-disabled tools are excluded from hub_search_tools results (#114)"() {
        given:
        searchEnabled()
        settingsMap.disabled_tools = ["hub_manage_mode"]

        when:
        def result = script.toolSearchTools([query: 'set hub mode location', maxResults: 25])

        then: 'the deny-list filter (getHiddenToolNames) removes it from the searchable corpus'
        !(result.results*.tool.contains("hub_manage_mode"))
    }

    def "the corpus + tokens are built once into the class static -- never atomicState -- and a second identical query is served from it"() {
        given:
        searchEnabled()
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).clear()

        when: 'first query builds the index'
        def first = script.toolSearchTools([query: 'switch motion contact', maxResults: 5])
        def index = scriptStaticField('TOOL_SEARCH_INDEX') as Map
        def cachedCorpus = index.corpus
        def cachedTokens = index.tokens

        then: 'the index lives in the static, index-aligned to the full corpus, and nothing lands in app state'
        cachedCorpus != null
        cachedTokens != null
        cachedTokens.size() == cachedCorpus.size()
        index.fingerprint == script.toolSearchCorpusFingerprint()
        !atomicStateMap.containsKey('toolSearchCorpus')
        !atomicStateMap.containsKey('toolSearchTokens')
        !stateMap.containsKey('toolSearchCorpus')

        when: 'a second identical query'
        def second = script.toolSearchTools([query: 'switch motion contact', maxResults: 5])

        then: 'the index was NOT rebuilt (same objects) and results are identical'
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus.is(cachedCorpus)
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens.is(cachedTokens)
        second.results == first.results
    }

    def "the first search on an upgraded hub sheds the persisted index an older build left in atomicState"() {
        given: 'the legacy keys are present, as they are on every hub that ran the previous build'
        searchEnabled()
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).clear()
        atomicStateMap.toolSearchCorpus = [[name: 'hub_list_rooms', description: 'old', params: '', gateway: 'hub_read_rooms']]
        atomicStateMap.toolSearchTokens = [['hub', 'list', 'rooms', 'old']]
        atomicStateMap.toolSearchCorpusFingerprint = 'stale'
        atomicStateMap.toolSearchCorpusVersion = 'stale'

        when:
        def result = script.toolSearchTools([query: 'list rooms', maxResults: 5])

        then: 'served from the freshly built static, and the ~244 KB of dead state is gone without a settings save'
        result.results.find { it.tool == 'hub_list_rooms' }?.title == 'List Rooms'
        !atomicStateMap.containsKey('toolSearchCorpus')
        !atomicStateMap.containsKey('toolSearchTokens')
        !atomicStateMap.containsKey('toolSearchCorpusFingerprint')
        !atomicStateMap.containsKey('toolSearchCorpusVersion')
    }

    def "a toggle flip hides the right tools per-request without rebuilding the shared full-corpus token cache"() {
        given: 'a clean cache with the custom rule engine ON'
        searchEnabled()
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).clear()

        when: 'search with the engine ON'
        def onResult = script.toolSearchTools([query: 'custom rule create delete clone', maxResults: 25])
        def fullSize = (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens.size()
        def cachedCorpus = (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus
        def cachedTokens = (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens

        then: 'a custom_* tool is visible'
        onResult.results*.tool.contains('hub_create_custom_rule')

        when: 'flip the engine OFF WITHOUT clearing the cache, then search again'
        settingsMap.enableCustomRuleEngine = false
        def offResult = script.toolSearchTools([query: 'custom rule create delete clone', maxResults: 25])

        then: 'the custom_* tool is now hidden (per-request visibility filter, NOT a cache rebuild)'
        !offResult.results*.tool.contains('hub_create_custom_rule')

        and: 'the full-corpus cache is the SAME objects, not a rebuild that happens to match in size'
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus.is(cachedCorpus)
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens.is(cachedTokens)
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens.size() == fullSize
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).tokens.size() == (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus.size()

        and: 'results remain well-formed (deduped)'
        offResult.results*.tool == offResult.results*.tool.unique()

        and: 'the reported count tracks the VISIBLE corpus -- it shrinks when custom_* tools are hidden, proving it is not counted over the full corpus'
        offResult.totalToolsSearched < onResult.totalToolsSearched
    }

    def "a query semantically anchors to the right corpus entry (proves token<->corpus alignment)"() {
        given:
        searchEnabled()

        when: 'a query whose strongest matches are the room tools'
        def result = script.toolSearchTools([query: 'room rooms list', maxResults: 8])

        then: 'a room tool surfaces with positive relevance -- the score is tied to the right corpus entry'
        def roomHit = result.results.find { it.tool.contains('room') }
        roomHit != null
        roomHit.relevance > 0
    }

    def "totalToolsSearched counts DISTINCT tools, not multi-gateway corpus rows"() {
        given: 'a clean cache so the corpus is freshly built'
        searchEnabled()
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).clear()

        when:
        def result = script.toolSearchTools([query: 'list device room variable rule file', maxResults: 5])
        // Mirror the production visibility filter (getHiddenToolNames is the single
        // source of truth toolSearchTools uses) so the expected counts match exactly.
        def hidden = (script.getHiddenToolNames() ?: []) as Set
        def visibleRows = (scriptStaticField('TOOL_SEARCH_INDEX') as Map).corpus*.name.findAll { !hidden.contains(it) }
        // unique(false) is the NON-mutating overload -- bare unique() dedups visibleRows
        // in place, which would collapse the visibleRows-vs-visibleDistinct check below.
        def visibleDistinct = visibleRows.unique(false)

        then: 'the corpus genuinely carries multi-gateway duplicate rows (read/write split lists reads in both gateways)'
        visibleRows.size() > visibleDistinct.size()

        and: 'the reported count is the distinct tool count, never the inflated row count'
        result.totalToolsSearched == visibleDistinct.size()
        result.totalToolsSearched < visibleRows.size()
    }

    def "disabling a multi-gateway tool drops totalToolsSearched by exactly one (count is post-dedup, not per-row)"() {
        given: 'hub_get_device lives in BOTH hub_read_devices and hub_manage_devices -- two corpus rows'
        searchEnabled()
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).clear()
        def before = script.toolSearchTools([query: 'device get attribute', maxResults: 25])
        assert before.results*.tool.contains('hub_get_device')   // precondition: the multi-gateway tool is present

        when: 'it is disabled by name, removing BOTH of its gateway rows from the visible corpus'
        settingsMap.disabled_tools = ['hub_get_device']
        def after = script.toolSearchTools([query: 'device get attribute', maxResults: 25])

        then: 'the distinct count falls by exactly one -- counting rows would have dropped two'
        before.totalToolsSearched - after.totalToolsSearched == 1
        !after.results*.tool.contains('hub_get_device')
    }

    def "updated() invalidates the in-JVM BM25 index, the legacy atomicState entries, and the gateway requiredParams memo in lockstep"() {
        given: 'populated caches and a no-op initialize so updated() does not hit platform APIs'
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).putAll([fingerprint: 'fp-test', corpus: [[name: 'x']], tokens: [['x']]])
        atomicStateMap.toolSearchCorpus = [[name: 'x', description: 'd']]
        atomicStateMap.toolSearchTokens = [['x']]
        atomicStateMap.toolSearchCorpusVersion = 'v-test'
        atomicStateMap.toolSearchCorpusFingerprint = 'fp-corpus-test'
        atomicStateMap.requiredParamsByTool = [hub_get_room: ['room']]
        atomicStateMap.requiredParamsByToolFingerprint = 'fp-test'
        script.metaClass.initialize = { -> }

        when:
        script.updated()

        then: 'all derived caches are cleared (rebuilt lazily on next use)'
        (scriptStaticField('TOOL_SEARCH_INDEX') as Map).isEmpty()
        atomicStateMap.toolSearchCorpus == null
        atomicStateMap.toolSearchTokens == null
        atomicStateMap.toolSearchCorpusVersion == null
        atomicStateMap.requiredParamsByTool == null

        and: 'both content fingerprints are cleared in lockstep -- a stranded fingerprint would let the next rebuild miscompare'
        atomicStateMap.toolSearchCorpusFingerprint == null
        atomicStateMap.requiredParamsByToolFingerprint == null

        and: 'the legacy state entry is never reintroduced'
        !stateMap.containsKey('toolSearchCorpus')
    }

    def "hub_list_files advertises its filter argument on both discovery surfaces"() {
        given: 'the summary is the only description riding tools/list every turn -- the full inputSchema IS reachable, but only by spending a catalog call on the gateway first, so an arg named nowhere in the summary costs a round trip to discover'
        searchEnabled()

        when: 'each gateway carrying hub_list_files is checked for the filter arg in its summary'
        int checked = 0
        script.getGatewayConfig().each { gwName, gw ->
            if (gw?.summaries?.containsKey('hub_list_files')) {
                checked++
                assert gw.summaries.hub_list_files.contains('filter'),
                    "${gwName} summary hides the filter arg: ${gw.summaries.hub_list_files}"
            }
        }

        then: 'BOTH carrying gateways were actually reached -- a guarded loop that never enters passes vacuously, so the count is what makes this a guard'
        checked == 2

        and: 'a hints-ONLY term retrieves the tool. Querying "filter" would prove nothing: the corpus derives params from inputSchema.properties, so that token was already indexed before any summary or hint changed. The sweep is scoped to hub_list_files OWN gateway row and to exactly the fields buildToolSearchCorpus indexes for such a row -- name, title, "summary [gateway description]", param keys -- deliberately NOT the leaf tool description, which a gateway row never indexes. Per-entry scoping is the point: BM25 scores each row, so another tool carrying the token (hub_get_logs summary says "source (substring)") cannot make THIS one rank'
        String listFilesTitle = script.getToolDisplayMeta()['hub_list_files']?.title ?: ''
        String listFilesParams = script.getAllToolDefinitions()
            .find { it.name == 'hub_list_files' }?.inputSchema?.properties?.keySet()?.join(' ') ?: ''
        script.getGatewayConfig().findAll { _, gw -> gw?.summaries?.containsKey('hub_list_files') }
            .every { gwName, gw ->
                !("hub_list_files ${listFilesTitle} ${gw.summaries.hub_list_files ?: ''} "
                  + "${gw.description ?: ''} ${listFilesParams}").toLowerCase().contains('substring')
            }

        and: 'so searchHints is the only field the token can come from'
        script.getGatewayConfig().any { _, gw ->
            (gw?.searchHints?.hub_list_files ?: '').toLowerCase().contains('substring')
        }
        script.toolSearchTools([query: 'substring', maxResults: 10])
            .results*.tool.contains('hub_list_files')
    }

    def "the fingerprint memo is actually populated by a search"() {
        given: 'TOOL_SEARCH_CORPUS_FP is the only non-final @Field static and the only bare-identifier WRITE to an app field from library text. If that assignment ever resolved to the script Binding instead of the field, every other gate stays green and the sole symptom is a permanently cold path re-walking the catalog on every call'
        searchEnabled()

        expect: 'the memo starts cold -- asserted, not stated: an expression in a given: block is not auto-asserted by Spock, so writing it there would leave the premise unenforced and this test green even if the harness reset regressed'
        scriptStaticField('TOOL_SEARCH_CORPUS_FP') == null

        when:
        script.toolSearchTools([query: 'list rooms', maxResults: 3])

        then: 'the memo warmed, and holds the live catalog fingerprint'
        scriptStaticField('TOOL_SEARCH_CORPUS_FP') == script.toolSearchCorpusFingerprint()
    }

    def "the BM25 map key is NAMESPACED, not the bare token"() {
        // The lint pins that every df/tf subscript goes through _bm25Key; nothing pinned what
        // _bm25Key RETURNS. Making it `return token` passes the lint and the search tests alike,
        // and the platform's SandboxSubscriptGuard rejection of the real corpus token "fields"
        // comes straight back -- with it, every hub_search_tools call throws.
        expect:
        script._bm25Key('fields') != 'fields'
        script._bm25Key('fields').startsWith('t_')
        script._bm25Key('switch') == 't_switch'
    }

    def "the corpus fingerprint actually discriminates -- a content change moves it"() {
        given: 'the sibling requiredParams memo ships this exact guard, because every other fingerprint test passes just as well against a function that returns a constant'
        searchEnabled()
        def defs = script.getAllToolDefinitions()
        String fpA = script.toolSearchCorpusFingerprint(defs)

        when: 'one indexed field changes -- a single description edit, the smallest thing this cache must notice'
        def mutated = defs.collect { d ->
            d.name == 'hub_list_files' ? (d + [description: "${d.description} zzz-probe"]) : d
        }
        String fpB = script.toolSearchCorpusFingerprint(mutated)

        then: 'the fingerprint moves; if it did not, a stale corpus would be served forever with nothing red'
        fpA != fpB

        and: 'and it is stable for identical input, so it cannot thrash the cache on every call'
        script.toolSearchCorpusFingerprint(defs) == fpA
    }

    def "a plain comma-separated Args: list names only real inputSchema properties"() {
        given: 'the Args: tail hand-restates a schema the summary cannot see, and nothing else stops the two drifting -- an invented argument sends the model to call it, surfacing as a rejected tool call rather than a red build'
        searchEnabled()
        def schemaProps = script.getAllToolDefinitions().collectEntries {
            [(it.name as String): (it.inputSchema?.properties?.keySet() ?: [] as Set)]
        }
        // Scoped DELIBERATELY to the plain "Args: a, b, c" form. Across the gateways the tail
        // is not one convention: it also carries alternatives (source|sourceFile|importUrl),
        // slash groups (since/until) and action VALUES (repair_node, pair), none of which are
        // property names. Those forms need a convention decision, not a regex; this guard
        // covers the unambiguous subset and must not be widened by loosening the match.
        def plainList = ~/^[A-Za-z_][A-Za-z0-9_]*(\s*,\s*[A-Za-z_][A-Za-z0-9_]*)+$/

        when: 'every plain comma-separated Args: tail is parsed back into argument names'
        def phantom = []
        int summariesChecked = 0
        int listFilesChecked = 0
        script.getGatewayConfig().each { gwName, gw ->
            (gw?.summaries ?: [:]).each { toolName, summary ->
                def m = (summary as String) =~ /Args:\s*([^.]*)/
                if (!m.find()) return
                String tail = m.group(1).trim()
                if (!plainList.matcher(tail).matches()) return
                summariesChecked++
                if (toolName == 'hub_list_files') listFilesChecked++
                tail.split(',').each { raw ->
                    String arg = raw.trim()
                    if (!(schemaProps[toolName as String]?.contains(arg))) {
                        phantom << "${gwName}/${toolName}: '${arg}' is not in inputSchema.properties"
                    }
                }
            }
        }

        then: 'no such summary advertises an argument its tool does not accept'
        phantom == []

        and: 'the scan reached real summaries -- an empty sweep would pass vacuously'
        summariesChecked >= 2

        and: 'and BOTH hub_list_files rows are specifically in scope: a bare total is satisfied by two unrelated summaries while the rows this PR edited are skipped by the regex'
        listFilesChecked == 2
    }

    // The BM25 maps are subscripted with a COMPUTED key -- a corpus token -- and the platform
    // sandbox rejects a computed key that collides with a reflection-ish property name. The
    // catalog really does produce one: hub_list_devices' `fields` parameter is joined into the
    // corpus text, so an unprefixed key made every search throw. These pin that the token is
    // present AND that searching for it still scores, which is what the prefix buys.

    def "the live corpus contains the sandbox-reserved token that broke raw-key scoring"() {
        when:
        def corpus = script.buildToolSearchCorpus(null)
        def tokens = corpus.collectMany { script.bm25Tokenize(script._bm25DocText(it)) } as Set

        then: 'the token is real, so a regression here is not hypothetical'
        tokens.contains('fields')
    }

    def "a query for a sandbox-reserved token still ranks its tool"() {
        when:
        def result = script.toolSearchTools([query: 'fields', maxResults: 25])

        then: 'scoring completes and the tool whose parameter contributes the token is found'
        result.results != null
        result.results*.tool.contains('hub_list_devices')
    }
}
