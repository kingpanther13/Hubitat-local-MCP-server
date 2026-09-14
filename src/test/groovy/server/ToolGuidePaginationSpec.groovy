package server

import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * Issue #392: the documented no-section hub_get_tool_guide call -- "omit only to fetch the full
 * guide / discover the available keys" -- could never return content. The guide is ~188 KB against
 * the hub's 120,000-byte tools/call cap, so that call tripped the response-size guard and the
 * caller got NOTHING: no content, no key list, no way forward except reading the enum.
 *
 * It now pages. The cap assertions here go through handleMcpRequest, so they are measured by the
 * REAL guard in handleToolsCall against the REAL envelope (jsonRpcResult stamps resultType and the
 * serverInfo _meta, and the result is JSON-encoded twice) rather than a stand-in built here.
 */
class ToolGuidePaginationSpec extends ToolSpecBase {

    /** Drive the tool through the production tools/call envelope, so the response-size guard,
     *  the jsonRpcResult decoration and the executeTool dispatch line are all the real ones. */
    private Map dispatch(Map args) {
        def envelope = mcpDriver.callTool('hub_get_tool_guide', args)
        def text = envelope?.result?.content?.getAt(0)?.text as String
        return [envelope: envelope,
                text: text,
                payload: text ? new JsonSlurper().parseText(text) as Map : null]
    }

    def "the no-section call returns content through the real dispatch path instead of the size guard"() {
        when: 'the documented discovery call, driven end to end'
        def first = dispatch([:])

        then: 'the size guard did not fire -- this is the whole point of the change'
        first.payload.response_too_large == null
        first.payload.success == true
        first.payload.section == 'full'
        (first.payload.content as String).length() > 0

        and: 'discovering the key space -- the documented purpose -- is served on the very first page'
        (first.payload.availableSections as List).contains('set_rule_reference')
        (first.payload.availableSubSections['set_rule_reference'] as List).contains('set_rule_reference_conditions')

        and: 'the caller is told there is more, and how to get it'
        first.payload.nextCursor != null
        first.payload.offset == 0
        (first.payload.totalChars as Integer) > (first.payload.content as String).length()
    }

    def "walking nextCursor through dispatch reassembles the guide, every page inside the real cap"() {
        given:
        def pages = []
        def cursor = null
        def guard = 0

        when: 'iterate until the tool stops handing back a cursor'
        while (guard++ < 50) {
            def page = dispatch(cursor == null ? [:] : [cursor: cursor])
            assert page.payload.response_too_large == null :
                "page at offset ${page.payload.offset} tripped the response-size guard: ${page.payload}"
            assert page.payload.success == true
            pages << (page.payload.content as String)
            cursor = page.payload.nextCursor
            if (cursor == null) break
        }

        then: 'the traversal terminates and loses nothing -- concatenated pages are the whole guide, byte for byte'
        cursor == null
        pages.size() > 1
        pages.join('') == script.getToolGuideSections().collect { k, v -> v }.join('\n\n---\n\n')
    }

    def "the last page omits nextCursor entirely -- a present-but-null key loops a contract-following client forever"() {
        given: 'walk to the final page'
        def page = dispatch([:])
        def guard = 0
        while (page.payload.nextCursor != null && guard++ < 50) {
            page = dispatch([cursor: page.payload.nextCursor])
        }

        expect: 'the key is ABSENT, not null: a client that tests presence would hand null back and get page 1 again'
        !(page.payload as Map).containsKey('nextCursor')
        !page.text.contains('nextCursor')

        and: 'it is still recognisably a later page'
        (page.payload.offset as Integer) > 0
        page.payload.totalChars == page.payload.offset + (page.payload.content as String).length()
    }

    def "pages break on line boundaries and resume exactly where the previous page stopped"() {
        when:
        def first = dispatch([:])
        def second = dispatch([cursor: first.payload.nextCursor])

        then: 'a page never splits a markdown line'
        (first.payload.content as String).endsWith('\n')

        and: 'no gap and no overlap at the seam -- and the cursor actually reached the dispatcher'
        second.payload.offset == (first.payload.content as String).length()
        first.payload.nextCursor == second.payload.offset.toString()
        second.payload.totalChars == first.payload.totalChars
    }

    def "a section that fits one page is unchanged -- no cursor, no pagination bookkeeping"() {
        when: 'the largest section, comfortably inside one page'
        def result = dispatch([section: 'set_rule_reference'])

        then: 'whole section, and none of the pagination fields'
        (result.payload.content as String) == (script.getToolGuideSections()['set_rule_reference'] as String)
        !(result.payload as Map).containsKey('nextCursor')
        !(result.payload as Map).containsKey('offset')
        !(result.payload as Map).containsKey('totalChars')
        result.payload.response_too_large == null
    }

    def "every section and sub-section returns whole in one response, under the real guard"() {
        given:
        def keys = (script.getToolGuideSections().keySet() as List) +
                   script.getToolGuideSubSections().values().collectMany { it.keySet().toList() }

        when:
        def defects = keys.findAll { key ->
            def r = dispatch([section: key])
            r.payload.response_too_large != null || r.payload.nextCursor != null
        }

        then: 'the split keeps every named key a single-response fetch; pagination is the backstop, not the norm'
        defects == []
    }

    def "a cursor aimed at a payload that was never paged is refused, not silently served headless"() {
        when: 'a cursor carried over from a full-guide walk, pointed at a section that fits one page'
        def result = dispatch([section: 'set_rule_reference', cursor: '5000'])

        then: 'refused as a caller error -- serving chars 5000.. would drop the head of the section with no signal'
        result.envelope.error == null
        result.envelope.result.isError == true
        (mcpDriver.parseInner(result.envelope).error as String).contains('fits one response')
    }

    def "a malformed cursor is a caller error, not a silent reset to page one"() {
        when:
        def bad = dispatch([cursor: 'not-a-number'])

        then:
        bad.envelope.result.isError == true
        (mcpDriver.parseInner(bad.envelope).error as String).contains('cursor')

        when: 'a cursor past the end of the guide'
        def far = dispatch([cursor: '99999999'])

        then:
        far.envelope.result.isError == true
        (mcpDriver.parseInner(far.envelope).error as String).contains('out of range')
    }

    def "guide:true returns the whole section, never a page a hub_set_rule caller cannot redeem"() {
        // hub_set_rule has no cursor parameter, so if set_rule_reference ever crosses a page the
        // inline meta-call would hand back a nextCursor with no way to spend it. It concatenates.
        when:
        def inline = script.executeTool('hub_set_rule', [guide: true])

        then:
        inline.success == true
        (inline.content as String) == (script.getToolGuideSections()['set_rule_reference'] as String)
        !(inline as Map).containsKey('nextCursor')
        !(inline as Map).containsKey('offset')
        !(inline as Map).containsKey('totalChars')

        and: 'it still names the narrower keys, so the caller can go straight to one next time'
        (inline.subSections as List).contains('set_rule_reference_conditions')
    }

    def "an empty cursor is the documented first page"() {
        expect:
        dispatch([cursor: '']).payload.content == dispatch([:]).payload.content
    }
}
