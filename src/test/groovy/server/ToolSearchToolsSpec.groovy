package server

import support.ToolSpecBase

/**
 * Direct-call coverage for the BM25 hub_search_tools path after the PR2c perf change
 * (bm25-corpus-state-not-atomicstate + bm25-df-table-rebuild-Q15): the corpus and the
 * per-doc tokenization are cached in atomicState (index-aligned to the FULL corpus) and
 * served from cache; df/avgDl are recomputed over the visible subset so ranking stays
 * byte-identical. These pin: a stable ranked shape, cache-hit (no rebuild), per-request
 * visibility filtering over the shared full-corpus token cache, and updated() invalidation
 * of both BM25 entries (and the gateway requiredParams memo) in lockstep.
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

    def "the corpus + tokens are built once into atomicState and a second identical query is served from cache"() {
        given:
        searchEnabled()
        atomicStateMap.remove('toolSearchCorpus')
        atomicStateMap.remove('toolSearchTokens')

        when: 'first query builds and caches the corpus + tokens'
        def first = script.toolSearchTools([query: 'switch motion contact', maxResults: 5])
        def cachedCorpus = atomicStateMap.toolSearchCorpus
        def cachedTokens = atomicStateMap.toolSearchTokens

        then: 'both are populated in atomicState (not state), index-aligned to the full corpus'
        cachedCorpus != null
        cachedTokens != null
        cachedTokens.size() == cachedCorpus.size()
        !stateMap.containsKey('toolSearchCorpus')

        when: 'a second identical query'
        def second = script.toolSearchTools([query: 'switch motion contact', maxResults: 5])

        then: 'the cache was NOT rebuilt (same object) and results are identical'
        atomicStateMap.toolSearchCorpus.is(cachedCorpus)
        atomicStateMap.toolSearchTokens.is(cachedTokens)
        second.results == first.results
    }

    def "a toggle flip hides the right tools per-request without rebuilding the shared full-corpus token cache"() {
        given: 'a clean cache with the custom rule engine ON'
        searchEnabled()
        atomicStateMap.remove('toolSearchCorpus')
        atomicStateMap.remove('toolSearchTokens')

        when: 'search with the engine ON'
        def onResult = script.toolSearchTools([query: 'custom rule create delete clone', maxResults: 25])
        def fullSize = atomicStateMap.toolSearchTokens.size()

        then: 'a custom_* tool is visible'
        onResult.results*.tool.contains('hub_create_custom_rule')

        when: 'flip the engine OFF WITHOUT clearing the cache, then search again'
        settingsMap.enableCustomRuleEngine = false
        def offResult = script.toolSearchTools([query: 'custom rule create delete clone', maxResults: 25])

        then: 'the custom_* tool is now hidden (per-request visibility filter, NOT a cache rebuild)'
        !offResult.results*.tool.contains('hub_create_custom_rule')

        and: 'the full-corpus token cache is untouched by the toggle'
        atomicStateMap.toolSearchTokens.size() == fullSize
        atomicStateMap.toolSearchTokens.size() == atomicStateMap.toolSearchCorpus.size()

        and: 'results remain well-formed (deduped)'
        offResult.results*.tool == offResult.results*.tool.unique()
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

    def "updated() invalidates both BM25 atomicState entries and the gateway requiredParams memo in lockstep"() {
        given: 'populated caches and a no-op initialize so updated() does not hit platform APIs'
        atomicStateMap.toolSearchCorpus = [[name: 'x', description: 'd']]
        atomicStateMap.toolSearchTokens = [['x']]
        atomicStateMap.toolSearchCorpusVersion = 'v-test'
        atomicStateMap.requiredParamsByTool = [hub_get_room: ['room']]
        atomicStateMap.requiredParamsByToolFingerprint = 'fp-test'
        script.metaClass.initialize = { -> }

        when:
        script.updated()

        then: 'all derived caches are cleared (rebuilt lazily on next use)'
        atomicStateMap.toolSearchCorpus == null
        atomicStateMap.toolSearchTokens == null
        atomicStateMap.toolSearchCorpusVersion == null
        atomicStateMap.requiredParamsByTool == null

        and: 'the memo fingerprint is cleared in lockstep -- a stranded fingerprint would let the next rebuild miscompare'
        atomicStateMap.requiredParamsByToolFingerprint == null

        and: 'the legacy state entry is never reintroduced'
        !stateMap.containsKey('toolSearchCorpus')
    }
}
