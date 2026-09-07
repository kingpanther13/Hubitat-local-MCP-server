package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Issue #392: the documented no-section hub_get_tool_guide call -- "omit only to fetch the full
 * guide / discover the available keys" -- could never return content. The guide is ~188 KB against
 * the hub's 120,000-byte tools/call cap, so that call tripped the response-size guard and the
 * caller got NOTHING: no content, no key list, no way forward except reading the enum.
 *
 * It now pages instead. A payload that fits one response is unchanged (every section call today);
 * anything larger returns a first page plus nextCursor, so a caller always gets real content and
 * can walk the rest. Nothing this tool can return trips the guard any more.
 */
class ToolGuidePaginationSpec extends ToolSpecBase {

    // handleToolsCall's guard: hubResponseCapBytes() - 11072. Every page must land under it after
    // the DOUBLE JSON encoding the wire does -- the result map is serialized, embedded as a text
    // content block, then serialized again.
    private static final int WIRE_LIMIT = 131072 - 11072

    private int wireBytes(Map result) {
        def envelope = [jsonrpc: '2.0', id: 1,
                        result: [content: [[type: 'text', text: JsonOutput.toJson(result)]]]]
        return JsonOutput.toJson(envelope).getBytes('UTF-8').length
    }

    def "the no-section call returns content instead of tripping the size guard"() {
        when:
        def first = script.toolGetToolGuide(null)

        then: 'real content, not a size-guard dead end'
        first.success == true
        first.section == 'full'
        (first.content as String).length() > 0
        wireBytes(first) < WIRE_LIMIT

        and: 'the documented purpose of the no-section call -- discovering the key space -- is served on the very first page'
        (first.availableSections as List).contains('set_rule_reference')
        (first.availableSubSections['set_rule_reference'] as List).contains('set_rule_reference_conditions')

        and: 'the guide is bigger than one page, so the caller is told there is more and how to get it'
        first.truncated == true
        first.nextCursor != null
        first.offset == 0
        (first.totalChars as Integer) > (first.content as String).length()
    }

    def "walking nextCursor reassembles the whole guide, every page under the wire cap"() {
        given:
        def pages = []
        def cursor = null
        def guard = 0

        when: 'iterate until the tool stops handing back a cursor'
        while (guard++ < 50) {
            def page = script.toolGetToolGuide(null, cursor)
            assert page.success == true
            assert wireBytes(page) < WIRE_LIMIT :
                "page at offset ${page.offset} is ${wireBytes(page)} wire bytes, over the ${WIRE_LIMIT} guard"
            pages << (page.content as String)
            cursor = page.nextCursor
            if (cursor == null) break
        }

        then: 'the traversal terminates and loses nothing -- concatenated pages are the whole guide, byte for byte'
        cursor == null
        pages.size() > 1
        pages.join('') == script.getToolGuideSections().collect { k, v -> v }.join('\n\n---\n\n')
    }

    def "pages break on line boundaries and resume exactly where the previous page stopped"() {
        when:
        def first = script.toolGetToolGuide(null)
        def second = script.toolGetToolGuide(null, first.nextCursor)

        then: 'a page never splits a markdown line'
        (first.content as String).endsWith('\n')

        and: 'no gap and no overlap at the seam'
        second.offset == (first.content as String).length()
        first.nextCursor == second.offset.toString()
        second.totalChars == first.totalChars
    }

    def "a section that fits one page is unchanged -- no cursor, no pagination fields"() {
        when: 'the largest section, comfortably inside one page'
        def result = script.toolGetToolGuide('set_rule_reference')

        then: 'whole section, and none of the pagination bookkeeping'
        (result.content as String) == (script.getToolGuideSections()['set_rule_reference'] as String)
        result.nextCursor == null
        !result.containsKey('offset')
        !result.containsKey('totalChars')
        !result.containsKey('truncated')
        wireBytes(result) < WIRE_LIMIT
    }

    def "every section and sub-section returns whole in one response"() {
        given:
        def keys = (script.getToolGuideSections().keySet() as List) +
                   script.getToolGuideSubSections().values().collectMany { it.keySet().toList() }

        when:
        def paged = keys.findAll { script.toolGetToolGuide(it).nextCursor != null }
        def oversized = keys.findAll { wireBytes(script.toolGetToolGuide(it)) >= WIRE_LIMIT }

        then: 'the split keeps every named key a single-response fetch; pagination is the backstop, not the norm'
        paged == []
        oversized == []
    }

    def "a malformed cursor is a caller error, not a silent full response"() {
        when:
        script.toolGetToolGuide(null, 'not-a-number')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('cursor')

        when: 'a cursor past the end of the guide'
        script.toolGetToolGuide(null, '99999999')

        then:
        def outOfRange = thrown(IllegalArgumentException)
        outOfRange.message.contains('out of range')
    }

    def "an empty cursor is the documented first page"() {
        expect:
        script.toolGetToolGuide(null, '').content == script.toolGetToolGuide(null).content
    }
}
