library(name: "McpDiscoveryLib", namespace: "mcp", author: "kingpanther13", description: "Tool guide implementations (hub_get_tool_guide dispatcher) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolGetToolGuide(section, cursor = null, Map apiDocs = null) {
    // apiDocs: {platform_api_search?, platform_api_page?} -- the hub's own API documentation.
    if (apiDocs?.platform_api_search != null || apiDocs?.platform_api_page != null) {
        if (section) throw new IllegalArgumentException("section and platform_api_search / platform_api_page are separate lookups; send one.")
        if (apiDocs.platform_api_search != null && apiDocs.platform_api_page != null) {
            throw new IllegalArgumentException("Send platform_api_search or platform_api_page, not both: search first, then open a pageId it returns.")
        }
        return (apiDocs.platform_api_page != null) ? _platformApiPage(apiDocs.platform_api_page.toString(), cursor)
                                                    : _platformApiSearch(apiDocs.platform_api_search.toString(), cursor)
    }
    def sections = getToolGuideSections()
    def subSections = getToolGuideSubSections()

    if (section) {
        def key = section.toLowerCase().replaceAll(/[^a-z_]/, "_")
        if (sections.containsKey(key)) {
            def result = [success: true, section: key]
            // Sub-keys of an oversized parent (issue #392): advertise them on the parent's own
            // response so the next call can be the narrow one, without a trip to the schema.
            if (subSections.containsKey(key)) result.subSections = subSections[key].keySet().toList()
            return _withGuidePage(result, _guideSectionServed(key, sections[key]), cursor)
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
            return _withGuidePage(result, _guideSectionServed(key, sub.content), cursor)
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
    // narrow with, and nextCursor when it wants the rest. Keys are served per section only.
    def fullGuide = "Acknowledgment keys are published only when a single section is read.\n\n" +
        sections.collect { k, v -> v }.join("\n\n---\n\n")
    def result = [
        success: true,
        section: "full",
        availableSections: sections.keySet().toList(),
        availableSubSections: subSections.collectEntries { k, v -> [(k): v.keySet().toList()] }
    ]
    return _withGuidePage(result, fullGuide, cursor)
}

private Map _platformApiSearch(String query, cursor) {
    // The hub's own Groovy API reference (firmware 2.5.2+, Settings > For Developers > API
    // documentation), served from /developer-docs. A search downloads and parses the whole index
    // (several MB) on every call and returns only ranked matches, 25 per page; a class page returns
    // its methods 40 per page. `methods` is read with Map.get: the sandbox blocks it as a property.
    def terms = query.toLowerCase().split(/\s+/).findAll { it }
    if (!terms) throw new IllegalArgumentException("platform_api_search needs at least one word, e.g. 'eventsBetween' or 'hub variable'.")
    def index
    try {
        def raw = hubInternalGet("/developer-docs/index.json", null, 60)
        index = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception e) {
        mcpLogError("discovery", "API documentation index read failed", e)
        def out = [success: false, error: "Could not read the hub's API documentation index: ${e.message}"]
        if (_hubFirmwareBefore("2.5.2")) out.note = "The on-hub API documentation needs firmware 2.5.2 or later."
        return out
    }
    if (!(index instanceof Map) || !(index.pages instanceof List)) {
        return [success: false, error: "The hub's API documentation index has an unexpected shape."]
    }
    def hits = []
    index.pages.each { pg ->
        if (!(pg instanceof Map)) return
        // Apps, drivers and shared APIs outrank the many protocol (Z-Wave/Zigbee/Matter) pages.
        int sectionBoost = (pg.section == "protocols") ? 0 : 3
        String pageText = "${pg.id} ${pg.label} ${pg.className} ${pg.topic}".toLowerCase()
        if (terms.every { pageText.contains(it) }) {
            hits << [score: 3 + sectionBoost, pageId: pg.id, className: pg.className, label: pg.label, section: pg.section, kind: "page", usage: pg.usage]
        }
        def pgMethods = pg.get("methods")
        (pgMethods instanceof List ? pgMethods : []).each { m ->
            if (!(m instanceof Map)) return
            String name = (m.name ?: "").toString()
            String lname = name.toLowerCase()
            String text = "${name} ${m.signature} ${m.summary} ${pg.className} ${pg.label}".toLowerCase()
            if (!terms.every { text.contains(it) }) return
            // An exact method-name term ranks first, then a name holding every term.
            int score = sectionBoost
            if (terms.any { it == lname }) score += 4
            else if (terms.every { lname.contains(it) }) score += 2
            hits << [score: score, pageId: pg.id, className: pg.className, label: pg.label, section: pg.section, kind: m.kind ?: "method",
                     name: name, signature: m.signature, summary: m.summary?.toString()?.take(400)]
        }
    }
    (index.guides instanceof List ? index.guides : []).each { g ->
        if (g instanceof Map && terms.every { "${g.title} ${g.summary}".toLowerCase().contains(it) }) {
            hits << [score: 1, kind: "guide", label: g.title, summary: g.summary, url: g.url]
        }
    }
    hits = hits.sort { -(it.score as int) }.collect { h -> h.findAll { k, v -> k != "score" && v != null } }
    def paged = _paginateList(hits, cursor != null ? cursor : "", 25, "hub_get_tool_guide")
    def out = [success: true, platformApiSearch: query, contentRevision: index.contentRevision, total: hits.size(), matches: paged.page]
    out.note = hits ? "Open a class with hub_get_tool_guide(platform_api_page=<pageId>) for full method descriptions. Methods the sandbox restricts to system apps are not marked; probe before relying on one." :
                      "No match. Search a method or class name, e.g. 'getHubFiles', 'DeviceWrapper', 'hub variable'."
    if (paged.nextCursor != null) out.nextCursor = paged.nextCursor
    return out
}

private Map _platformApiPage(String pageId, cursor) {
    if (!(pageId ==~ /[a-z0-9][a-z0-9-]*/)) throw new IllegalArgumentException("platform_api_page must be a pageId from platform_api_search, e.g. 'api-com-hubitat-app-devicewrapper'.")
    def pg
    try {
        def raw = hubInternalGet("/developer-docs/${pageId}.json", null, 30)
        pg = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception e) {
        mcpLogError("discovery", "API documentation page read failed", e)
        return [success: false, error: "Could not read API documentation page '${pageId}': ${e.message}",
                note: "Check the pageId with hub_get_tool_guide(platform_api_search=...)." + (_hubFirmwareBefore("2.5.2") ? " The on-hub API documentation needs firmware 2.5.2 or later." : "")]
    }
    def pgMethods = (pg instanceof Map) ? pg.get("methods") : null
    if (!(pgMethods instanceof List)) return [success: false, error: "API documentation page '${pageId}' has an unexpected shape."]
    // A description past 20,000 characters is cut (descriptionTruncated), so a single-method page stays
    // near the ~60 KB budget.
    def methods = pgMethods.findAll { it instanceof Map }.collect { m ->
        def text = (m.descriptionMarkdown ?: m.summary)?.toString()?.trim()
        def entry = [kind: m.kind, name: m.name, signature: m.signature, description: text?.take(20000)]
        if (text && text.length() > 20000) entry.descriptionTruncated = true
        entry
    }
    // 40 methods a page, fewer when their descriptions would push a page past ~60 KB.
    def pageCursor = (cursor != null) ? cursor : ""
    def paged = _paginateList(methods, pageCursor, 40, "hub_get_tool_guide")
    while (paged.page.size() > 1 && groovy.json.JsonOutput.toJson(paged.page).length() > 60000) {
        paged = _paginateList(methods, pageCursor, paged.page.size() - 1, "hub_get_tool_guide")
    }
    def out = [success: true, pageId: pg.id, className: pg.className, label: pg.label, section: pg.section, topic: pg.topic, usage: pg.usage,
               extendsClass: pg.extendsClass, totalMethods: methods.size(), methods: paged.page]
    if (paged.nextCursor != null) out.nextCursor = paged.nextCursor
    return out
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
    return _bat529EmbeddedCatalogRead(['["l",[["m",[["name","hub_get_tool_guide"],["description","Get the deep-reference guide for an MCP tool topic[[FLAT_TRIM]] (exhaustive capability tables, wire formats, worked examples)[[/FLAT_TRIM]]. Read a write tool\'s section before calling that tool: the section publishes the acknowledgment key the best-practice gate requires, and best_practice_reference maps each write tool to its section. A `<parent>_<part>` key (e.g. set_rule_reference_conditions) returns just that part of its parent section; the bare parent key returns all of it.[[FLAT_TRIM]] A parent\'s response lists its own sub-keys.[[/FLAT_TRIM]] Prefer a section: omitting it returns the key list plus the first page of the whole guide (paged via nextCursor)[[FLAT_TRIM]], the largest response this tool produces, so on a timeout retry a specific section[[/FLAT_TRIM]].[[FLAT_TRIM]] platform_api_search / platform_api_page read the hub\'s own Groovy API documentation instead.[[/FLAT_TRIM]]"],["inputSchema",["m",[["type","object"],["properties",["m",[["section",["m",[["type","string"],["description","One section key from the enum. Omit to get the key list plus the first page of the full guide."],["enum",["l",["device_authorization","best_practice_reference","tool_access","hub_admin_write","hub_admin_write_overview","hub_admin_write_destructive","hub_admin_write_radios","hub_admin_write_devices","hub_admin_write_code","hub_admin_write_system","virtual_devices","update_device","rules","backup","file_manager","performance","performance_overview","performance_devices","performance_diagnostics","builtin_app_tools","builtin_app_tools_overview","builtin_app_tools_apps","builtin_app_tools_rules","builtin_app_tools_crud","set_rule_reference","set_rule_reference_overview","set_rule_reference_triggers","set_rule_reference_actions","set_rule_reference_conditions","set_rule_reference_walkstep","set_rule_reference_responses","set_rule_reference_guards","set_rule_create_reference","visual_rule_reference","variables","dashboards","bundles","rooms","slow_ops"]]]]]],["cursor",["m",[["type","string"],["description","Continue a paged payload: pass the prior call\'s nextCursor. Omit otherwise -- a cursor is rejected on a payload that fit one response."]]]],["platform_api_search",["m",[["type","string"],["description","[[FLAT_TRIM]]Instead of a section: search the hub\'s Groovy API docs, e.g. \'eventsBetween\'.[[/FLAT_TRIM]]"]]]],["platform_api_page",["m",[["type","string"],["description","[[FLAT_TRIM]]Instead of a section: one API docs class page by pageId.[[/FLAT_TRIM]]"]]]]]]]]]]]]]]'])
}

def _readOnlyToolNames_partDiscovery() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return _bat529EmbeddedCatalogRead(['["l",["hub_get_tool_guide"]]'])
}

def _toolDisplayMeta_partDiscovery() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return _bat529EmbeddedCatalogRead(['["m",[["hub_get_tool_guide",["m",[["title","Get Tool Guide"],["summary","Deep-reference guide for MCP tool topics, plus search of the hub\'s own Groovy API documentation."]]]]]]'])
}
