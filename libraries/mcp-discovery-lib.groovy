library(name: "McpDiscoveryLib", namespace: "mcp", author: "kingpanther13", description: "Tool discovery implementations (hub_search_tools BM25 search + the hub_get_tool_guide dispatcher) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolSearchTools(args) {
    def query = args.query
    if (!query?.trim()) return [error: "query is required"]
    def maxResults = args.maxResults != null ? Math.max(0, args.maxResults as Integer) : 5

    // The index (full corpus + per-doc tokens, index-aligned) is a pure function of the static
    // tool surface (definitions, gateway config, display meta) and lives in a class static, not in
    // atomicState: persisted, the two lists were ~244 KB of app state, and Hubitat re-serialises app
    // state on every execution, so every tool call paid for the search index. A static rebuilds
    // once per class load (a code deploy, a hub reboot) or when updated() clears it on a settings
    // save, and is otherwise free. Visibility filtering is applied per request so toggle changes
    // take effect without touching the index.
    //
    // The content fingerprint is what decides freshness: a code deploy recompiles the class
    // without firing updated(), contributors cannot bump currentVersion() (bot-only), so a version
    // stamp cannot tell a changed description from an unchanged one. On a warm class the
    // fingerprint is memoized, so the hit path does no catalog work at all; when it must be
    // computed, the catalog is walked ONCE and handed to both the fingerprint and the rebuild.
    def allDefs = null
    String corpusFp = TOOL_SEARCH_CORPUS_FP
    if (corpusFp == null) {
        allDefs = getAllToolDefinitions()
        corpusFp = toolSearchCorpusFingerprint(allDefs)
        TOOL_SEARCH_CORPUS_FP = corpusFp
    }
    def corpus = null
    def docTokensAll = null
    synchronized (TOOL_SEARCH_INDEX) {
        if (TOOL_SEARCH_INDEX.fingerprint != corpusFp || !(TOOL_SEARCH_INDEX.corpus instanceof List) ||
                !(TOOL_SEARCH_INDEX.tokens instanceof List) || TOOL_SEARCH_INDEX.tokens.size() != TOOL_SEARCH_INDEX.corpus.size()) {
            def built = buildToolSearchCorpus(allDefs)
            // Tokenize every corpus entry once, in corpus order, so tokens[i] is the tokenization
            // of corpus[i]. Plain List<List<String>> (sandbox-safe; whole-value single assignment).
            def tokens = built.collect { bm25Tokenize(_bm25DocText(it)) }
            TOOL_SEARCH_INDEX.clear()
            TOOL_SEARCH_INDEX.putAll([fingerprint: corpusFp, corpus: built, tokens: tokens])
            // An upgraded hub still carries the persisted copy an older build wrote, and updated()
            // only runs on a settings save: shed it here, once, so the dead state does not ride
            // every execution until someone happens to save settings.
            try {
                if (atomicState.toolSearchCorpus != null || atomicState.toolSearchTokens != null || atomicState.toolSearchCorpusFingerprint != null) {
                    atomicState.remove("toolSearchCorpus")
                    atomicState.remove("toolSearchTokens")
                    atomicState.remove("toolSearchCorpusVersion")
                    atomicState.remove("toolSearchCorpusFingerprint")
                }
            } catch (Exception ignore) { }
        }
        corpus = TOOL_SEARCH_INDEX.corpus
        docTokensAll = TOOL_SEARCH_INDEX.tokens
    }

    // Apply the SAME visibility filter that getToolDefinitions() uses so that
    // hub_search_tools never surfaces tools the LLM cannot actually invoke. Both
    // consume getHiddenToolNames() -- the single source of truth covering the
    // Read/Write masters, the custom-engine mode, and the #114 advanced overrides --
    // so the catalog and the search corpus cannot drift (this also closes the
    // pre-#113 gap where built-in-app-gated tools leaked into search).
    def searchHideByName = getHiddenToolNames()
    def searchHideGwSubTools = [:].withDefault { [] as Set }
    // Filter corpus to only tools the current toggle state allows. Co-filter the cached
    // full-corpus tokens in the SAME pass so docTokens[k] stays aligned with visibleCorpus[k]
    // (docTokensAll[i] is the tokenization of corpus[i]; selecting both by the same surviving
    // index i preserves the pairing). A plain findAll would drop the original index, so
    // iterate with eachWithIndex and push both lists together.
    def visibleCorpus = []
    def docTokens = []
    corpus.eachWithIndex { entry, i ->
        if (searchHideByName.contains(entry.name)) return
        if (entry.gateway) {
            def hiddenInGw = searchHideGwSubTools[entry.gateway]
            if (hiddenInGw && hiddenInGw.contains(entry.name)) return
        }
        visibleCorpus << entry
        docTokens << docTokensAll[i]
    }

    // Tokenize the query (docs already tokenized from the cache above)
    def queryTokens = bm25Tokenize(query)

    if (!queryTokens) return [results: [], message: "No searchable terms in query"]

    // BM25 scoring
    def scores = bm25Score(docTokens, queryTokens)

    // Rank and return top results
    def ranked = []
    scores.eachWithIndex { score, idx ->
        if (score > 0) ranked << [index: idx, score: score]
    }
    ranked.sort { -it.score }
    // Dedup by tool name: a tool listed in more than one gateway (multi-gateway
    // membership) yields one corpus entry per membership and would otherwise
    // occupy several result slots for the same tool. Keep the highest-scoring
    // entry per tool (ranked is already sorted by descending score).
    def seenSearchNames = [] as Set
    ranked = ranked.findAll { r ->
        def nm = visibleCorpus[r.index].name
        if (seenSearchNames.contains(nm)) return false
        seenSearchNames << nm
        return true
    }
    if (ranked.size() > maxResults) ranked = ranked.take(maxResults)

    def results = ranked.collect { r ->
        def tool = visibleCorpus[r.index]
        def entry = [
            tool: tool.name,
            description: tool.description,
            relevance: Math.round(r.score * 100) / 100.0
        ]
        // Defensive: a corpus entry can lack a title if a gateway lists a tool name
        // with no display-meta entry (the rebuild guard above already self-heals the
        // pre-title cached-corpus case).
        if (tool.title) entry.title = tool.title
        if (tool.gateway) {
            entry.gateway = tool.gateway
            entry.callAs = "Call via ${tool.gateway}(tool=\"${tool.name}\", args={...})"
        } else {
            entry.callAs = "Call directly: ${tool.name}({...})"
        }
        return entry
    }

    return [
        query: query,
        resultsCount: results.size(),
        // Count DISTINCT tools, not corpus rows: a tool in N gateways (the
        // read/write split lists every read in both a hub_read_* and a
        // hub_manage_* gateway) yields N corpus entries, so visibleCorpus.size()
        // over-reports the tool count. results is deduped by name the same way.
        totalToolsSearched: visibleCorpus*.name.toSet().size(),
        results: results
    ]
}

// Content fingerprint of everything the BM25 corpus tokenizes -- tool names, descriptions,
// param keys, friendly titles, gateway summaries and search hints. Walks the same three
// sources buildToolSearchCorpus does, folding each field into a 64-bit rolling hash rather
// than materializing the ~98 KB concatenation of this catalog, and skipping the per-entry
// regex tokenize and corpus map allocation. Returns the hash as a decimal String.
//
// A hash admits collisions that exact string equality would not: two different catalogs can
// in principle fold to the same value, and no field framing changes that. Accepted here --
// the cost of a collision is one stale corpus until the next code deploy, against walking
// and holding ~98 KB on a memory-constrained hub. The length-prefixing in _fpField is a
// separate concern: it stops adjacent fields from being reordered or re-split into the same
// input, which is a structural ambiguity rather than a hash property.
//
// NOT pure: applyDescriptionTransform rewrites the
// descriptions of the defs list IN PLACE. A caller that passes its own list gets it back
// already stripped, and a later applyDescriptionTransform(defs, true) on that same list is a
// no-op -- which would ship every [[FLAT_TRIM]] block inline and blow the flat catalog's
// size cap. Pass a list you do not intend to strip again, or fetch a fresh one.
def toolSearchCorpusFingerprint(List defs = null) {
    long h = 17L
    def displayMeta = getToolDisplayMeta()
    applyDescriptionTransform(defs ?: getAllToolDefinitions(), false).each { toolDef ->
        h = _fpField(h, toolDef.name as String)
        h = _fpField(h, displayMeta[toolDef.name]?.title)
        h = _fpField(h, toolDef.description)
        h = _fpField(h, toolDef.inputSchema?.properties?.keySet()?.join(','))
    }
    getGatewayConfig().each { gwName, config ->
        h = _fpField(h, gwName as String)
        h = _fpField(h, config.description)
        config.tools.each { toolName ->
            h = _fpField(h, toolName as String)
            h = _fpField(h, config.summaries?."${toolName}")
            h = _fpField(h, config.searchHints?."${toolName}")
        }
    }
    // Include representative field-template and tokenizer output in the cold-build fingerprint.
    // A bare tokenizer input would omit the template's choice of searchable fields.
    h = _fpField(h, bm25Tokenize(_bm25DocText(
        [name: 'hub_x-1', title: 'A_b', description: 'cd', params: 'ef', hints: 'gh',
         gateway: 'ij'])).join(','))
    return Long.toString(h)
}

// The exact text a corpus entry contributes to its tokens. Shared by the tokenize line and
// the fingerprint probe so the two cannot drift -- if this template changes, the fingerprint
// changes with it.
private String _bm25DocText(entry) {
    return "${entry.name} ${entry.title ?: ''} ${entry.description} ${entry.params ?: ''} ${entry.hints ?: ''}"
}

// Cold builds fold fields without allocating a concatenated catalog. Include each field's
// length so delimiter text such as "source|sourceFile|importUrl" cannot erase field boundaries.
private long _fpField(long h, value) {
    String s = (value == null) ? "" : value.toString()
    // String.hashCode(), not a hand-rolled character loop. Nothing here is @CompileStatic,
    // so every charAt/cast/multiply is a sandbox-intercepted dynamic dispatch -- spelling
    // this out by hand over the ~98 KB catalog is order 10^5 dispatches in one request, on
    // the first search after every deploy, in a tool with no budget or continuation escape
    // hatch. The JDK's own hash has the same collision profile for ~2 dispatches per field.
    h = h * 31L + s.length()
    return h * 31L + s.hashCode()
}

// Build a flat list of all tools (core + proxied) with gateway attribution
private buildToolSearchCorpus(List defs = null) {
    def gatewayConfig = getGatewayConfig()
    def proxiedNames = gatewayConfig.values().collectMany { it.tools } as Set
    // Strip [[FLAT_TRIM]] marker tokens before BM25 corpus build -- the markers
    // shouldn't show up as searchable tokens, but the wrapped capability lists
    // SHOULD (so hub_search_tools still matches "switch motion contact").
    def allDefs = applyDescriptionTransform(defs ?: getAllToolDefinitions(), false)
    def allDefsMap = allDefs.collectEntries { [(it.name): it] }
    // Friendly names join the searchable text: titles add tokens the bare
    // name/description lack (hub_set_rule gains 'author' from "Author Rule
    // Machine Rule" -- its corpus text otherwise only has 'authoring', and
    // the tokenizer does not stem).
    def displayMeta = getToolDisplayMeta()

    def corpus = []

    // Core tools (not behind a gateway)
    allDefs.each { toolDef ->
        if (!proxiedNames.contains(toolDef.name)) {
            def params = toolDef.inputSchema?.properties?.keySet()?.join(" ") ?: ""
            corpus << [name: toolDef.name, title: displayMeta[toolDef.name]?.title, description: toolDef.description?.replaceAll(/\n+/, ' ')?.trim(), params: params, gateway: null]
        }
    }

    // Gateway sub-tools (with search hints for synonym matching)
    gatewayConfig.each { gwName, config ->
        config.tools.each { toolName ->
            def summary = config.summaries[toolName] ?: ""
            def hints = config.searchHints?."${toolName}" ?: ""
            def fullDef = allDefsMap[toolName]
            def params = fullDef?.inputSchema?.properties?.keySet()?.join(" ") ?: ""
            corpus << [name: toolName, title: displayMeta[toolName]?.title, description: "${summary} [${config.description}]", params: params, hints: hints, gateway: gwName]
        }
    }

    return corpus
}

// BM25 tokenizer: lowercase, split on non-alphanumeric, drop tokens < 2 chars
private bm25Tokenize(String text) {
    if (!text) return []
    return text.toLowerCase().split(/[^a-z0-9]+/).findAll { it.length() > 1 }
}

// BM25 Okapi scoring
private String _bm25Key(String token) {
    // The ONLY way a corpus token becomes a map key in bm25Score. Every df/tf/query subscript goes
    // through here so the namespacing cannot be dropped at one site and kept at another --
    // tests/sandbox_lint.py checks that bm25Score's body has no bare df[...] / tf[...] subscript.
    // The prefix exists because the platform's SandboxSubscriptGuard rejects a COMPUTED map key that
    // collides with a reflection-ish property name, and one real corpus token ("fields") does.
    return "t_${token}".toString()
}

private bm25Score(List<List<String>> docTokens, List<String> queryTokens) {
    def k1 = 1.5
    def b = 0.75
    def n = docTokens.size()

    if (n == 0) return []

    // Document lengths and average
    def docLengths = docTokens.collect { it.size() }
    def avgDl = docLengths.sum() / (double) n
    if (avgDl == 0) return new double[n] as List

    // Document frequency: how many docs contain each token.
    // Keys are namespaced because these maps are subscripted with a COMPUTED key --
    // a corpus token -- and the platform's sandbox rejects a computed key that
    // collides with a reflection-ish property name. One real token does collide:
    // hub_list_devices' `fields` parameter joins the corpus text, so a raw-token
    // key made every search throw SecurityException. The prefix cannot collide.
    def df = [:]
    docTokens.each { tokens ->
        tokens.toSet().each { token ->
            def k = _bm25Key(token)
            df[k] = (df[k] ?: 0) + 1
        }
    }

    // Score each document
    def scores = new double[n]
    docTokens.eachWithIndex { tokens, docIdx ->
        // Term frequency for this doc
        def tf = [:]
        tokens.each { t -> def k = _bm25Key(t); tf[k] = (tf[k] ?: 0) + 1 }

        def dl = docLengths[docIdx]
        double score = 0.0

        queryTokens.each { rawQt ->
            def qt = _bm25Key(rawQt)
            def termFreq = tf[qt] ?: 0
            if (termFreq > 0) {
                def docFreq = df[qt] ?: 0
                def idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1.0)
                def num = termFreq * (k1 + 1)
                def den = termFreq + k1 * (1 - b + b * dl / avgDl)
                score += idf * num / den
            }
        }

        scores[docIdx] = score
    }

    return scores as List
}

def toolGetToolGuide(section, cursor = null) {
    def sections = getToolGuideSections()
    def subSections = getToolGuideSubSections()

    if (section) {
        def key = section.toLowerCase().replaceAll(/[^a-z_]/, "_")
        if (sections.containsKey(key)) {
            def result = [success: true, section: key]
            // Sub-keys of an oversized parent (issue #392): advertise them on the parent's own
            // response so the next call can be the narrow one, without a trip to the schema.
            if (subSections.containsKey(key)) result.subSections = subSections[key].keySet().toList()
            return _withGuidePage(result, sections[key], cursor)
        }
        def sub = guideSubSectionLookup(key)
        if (sub) {
            // Siblings, not just the parent: some reference material is shared between shortcuts
            // (the extended condition shapes serve addRequiredExpression, addAction.expression AND
            // addTrigger.condition), so a caller that landed on the wrong sub-key needs the narrow
            // alternatives -- naming only the parent sends it back to the fetch this split avoids.
            def result = [
                success: true,
                section: key,
                parentSection: sub.parent,
                subSections: subSections[sub.parent].keySet().toList(),
                note: "Part of the '${sub.parent}' section. A sibling sub-key may hold what you need; hub_get_tool_guide(section='${sub.parent}') returns all of it.".toString()
            ]
            return _withGuidePage(result, sub.content, cursor)
        }
        return [
            success: false,
            error: "Unknown section: ${section}",
            availableSections: sections.keySet().toList(),
            availableSubSections: subSections.collectEntries { k, v -> [(k): v.keySet().toList()] }
        ]
    }

    // Full guide. It does not fit one response and never has (issue #392), so this pages instead
    // of tripping the size guard -- the caller gets real content plus the whole key space to
    // narrow with, and nextCursor when it wants the rest.
    def fullGuide = sections.collect { k, v -> v }.join("\n\n---\n\n")
    def result = [
        success: true,
        section: "full",
        availableSections: sections.keySet().toList(),
        availableSubSections: subSections.collectEntries { k, v -> [(k): v.keySet().toList()] }
    ]
    return _withGuidePage(result, fullGuide, cursor)
}

// Attach `content` to a guide result, paging it when it cannot fit one response. A payload that
// fits comes back whole and keeps the shape every section call has today -- no nextCursor, no
// offset. Only an actually-split payload gains the pagination fields.
private Map _withGuidePage(Map result, content, cursor) {
    def paged = paginateGuideContent(content, cursor)
    result.content = paged.content
    if (paged.nextCursor != null || paged.offset > 0) {
        result.offset = paged.offset
        result.totalChars = paged.totalChars
    }
    // ABSENT, never present-and-null, on the last page: the repo's cursor contract is "iterate
    // nextCursor until absent", and a client that tests key presence would hand null back, land on
    // page 1 again, and loop forever with no error.
    if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    return result
}

def _getAllToolDefinitions_partDiscovery() {
    return [
        // Tool Guide
        [
            name: "hub_get_tool_guide",
            description: "Get the deep-reference guide for an MCP tool topic[[FLAT_TRIM]] (exhaustive capability tables, wire formats, worked examples)[[/FLAT_TRIM]] when a tool's own description and parameter descriptions are not enough. Supplement only - reach for it just for the named sections. A `<parent>_<part>` key (e.g. set_rule_reference_conditions) returns just that part of its parent section; the bare parent key returns all of it.[[FLAT_TRIM]] A parent's response lists its own sub-keys.[[/FLAT_TRIM]] Prefer a section to minimize tokens: omitting it returns the section + sub-section key list plus the first page of the whole guide, paging onward via nextCursor -- that first page is the largest response this tool produces, so on a timeout retry a specific section instead.",
            inputSchema: [
                type: "object",
                properties: [
                    section: [type: "string", description: "One section key from the enum. Omit to get the key list plus the first page of the full guide.", enum: ["device_authorization", "best_practice_reference", "hub_admin_write", "hub_admin_write_overview", "hub_admin_write_destructive", "hub_admin_write_radios", "hub_admin_write_devices", "hub_admin_write_code", "hub_admin_write_system", "virtual_devices", "update_device", "rules", "backup", "file_manager", "performance", "performance_overview", "performance_devices", "performance_diagnostics", "builtin_app_tools", "builtin_app_tools_overview", "builtin_app_tools_apps", "builtin_app_tools_rules", "builtin_app_tools_crud", "set_rule_reference", "set_rule_reference_overview", "set_rule_reference_triggers", "set_rule_reference_actions", "set_rule_reference_conditions", "set_rule_reference_walkstep", "set_rule_reference_responses", "set_rule_reference_guards", "set_rule_create_reference", "visual_rule_reference", "variables", "dashboards", "bundles", "rooms", "slow_ops"]],
                    cursor: [type: "string", description: "Continue a paged payload: pass the prior call's nextCursor. Omit otherwise -- a cursor is rejected on a payload that fit one response, and only the no-section full-guide call exceeds one."]
                ]
            ]
        ],
        // Tool Search (BM25)
        [
            name: "hub_search_tools",
            description: "Search all MCP tools by natural language query (BM25 ranking). Searches tool names, friendly titles, descriptions, and parameter names. Returns matching tools with their gateway location so you know how to call them. Use when unsure which gateway contains the tool you need.",
            inputSchema: [
                type: "object",
                properties: [
                    query: [type: "string", description: "Natural language search query (e.g. 'zigbee radio', 'delete app', 'memory leak', 'room management')"],
                    maxResults: [type: "integer", description: "Max results to return. Default: 5.", default: 5]
                ],
                required: ["query"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partDiscovery() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Meta
        "hub_get_tool_guide", "hub_search_tools"
    ]
}

def _toolDisplayMeta_partDiscovery() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Reference
        hub_get_tool_guide: [title: "Get Tool Guide", summary: "Deep-reference guide for MCP tool topics beyond the tool descriptions."],
        hub_search_tools: [title: "Search Tools", summary: "Search all MCP tools by natural-language query."]
    ]
}
