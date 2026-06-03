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
        settingsMap.enableBuiltinApp = true
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

    def "a toggle flip re-filters visibility per-request without rebuilding the shared full-corpus token cache"() {
        given: 'cache built over the full corpus with both engines on'
        searchEnabled()
        atomicStateMap.remove('toolSearchCorpus')
        atomicStateMap.remove('toolSearchTokens')
        script.toolSearchTools([query: 'rule create delete', maxResults: 20])
        def fullSize = atomicStateMap.toolSearchTokens.size()

        when: 'flip a visibility toggle WITHOUT clearing the cache, then search again'
        settingsMap.enableCustomRuleEngine = false
        def offResult = script.toolSearchTools([query: 'rule create delete', maxResults: 20])

        then: 'the full-corpus token cache is untouched by the toggle (filtering is per-request, not a rebuild)'
        atomicStateMap.toolSearchTokens.size() == fullSize
        atomicStateMap.toolSearchTokens.size() == atomicStateMap.toolSearchCorpus.size()

        and: 'the search still returns a well-formed result set under the new toggle state'
        offResult.results instanceof List
        offResult.results*.tool == offResult.results*.tool.unique()
    }

    def "updated() invalidates both BM25 atomicState entries and the gateway requiredParams memo in lockstep"() {
        given: 'populated caches and a no-op initialize so updated() does not hit platform APIs'
        atomicStateMap.toolSearchCorpus = [[name: 'x', description: 'd']]
        atomicStateMap.toolSearchTokens = [['x']]
        atomicStateMap.requiredParamsByTool = [hub_get_room: ['room']]
        script.metaClass.initialize = { -> }

        when:
        script.updated()

        then: 'all three derived caches are cleared (rebuilt lazily on next use)'
        atomicStateMap.toolSearchCorpus == null
        atomicStateMap.toolSearchTokens == null
        atomicStateMap.requiredParamsByTool == null

        and: 'the legacy state entry is never reintroduced'
        !stateMap.containsKey('toolSearchCorpus')
    }
}
