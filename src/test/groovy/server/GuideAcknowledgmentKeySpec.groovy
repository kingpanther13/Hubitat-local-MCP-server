package server

import support.ToolSpecBase

/**
 * Per-section acknowledgment keys (issue #476): which guide sections publish one, how
 * hub_get_tool_guide serves it, and the drift guard between best_practice_reference's static
 * tool list and _guideSectionForTool. The gate side lives in ExecuteToolMandatoryBpsGateSpec.
 */
class GuideAcknowledgmentKeySpec extends ToolSpecBase {

    static final String KEY_LINE = /^Acknowledgment key: I-HAVE-READ-THE-GUIDE-[a-z_]+-[0-9a-f]{8}$/
    static final String NOTE_PREFIX = 'Pass this exact value as the bestPracticeKey argument'

    private Set servedKeys() {
        (script.getToolGuideSections().keySet() as Set) +
            (script.getToolGuideSubSections().values().collectMany { it.keySet().toList() } as Set)
    }

    /** write tool -> its dedicated section, for every write tool that has one. */
    private Map writeToolSections() {
        def readOnly = script.getReadOnlyToolNames()
        script.getAllToolDefinitions().collect { it.name as String }
            .findAll { !readOnly.contains(it) && script._guideSectionForTool(it) }
            .collectEntries { [(it): script._guideSectionForTool(it)] }
    }

    private String rawBody(String key) {
        script.getToolGuideSections()[key] ?: script.guideSubSectionLookup(key).content
    }

    private String keyLine(String section, boolean named) {
        (named ? "Acknowledgment key (${section}): " : 'Acknowledgment key: ') + script.hubBpsGuideKey(section)
    }

    def "every write tool's section is a real section or sub-section, and publishes a key"() {
        given:
        def mapped = writeToolSections()

        expect:
        mapped.findAll { tool, section -> !(section in servedKeys()) } == [:]
        script._bpsGatedSections() == (mapped.values() as Set) + ['best_practice_reference']
    }

    def "best_practice_reference lists exactly the write tools with a dedicated section (drift guard)"() {
        given:
        def body = script.getToolGuideSections()['best_practice_reference'] as String
        def pairs = body.readLines().collect { it =~ /^- (hub_\w+) -> ([a-z_]+)$/ }
            .findAll { it.matches() }
            .collect { [it.group(1), it.group(2)] }

        expect: 'the static list is the live map, one line per tool, sorted by section then tool'
        pairs.collectEntries { [(it[0]): it[1]] } == writeToolSections()
        pairs.size() == writeToolSections().size()
        pairs == pairs.sort(false) { a, b -> a[1] <=> b[1] ?: a[0] <=> b[0] }

        and: 'the list is introduced and closed as documented, and the body carries no key'
        body.contains("If you are calling one of these tools, you must read its section for that section's key:")
        body.contains("Every other write tool uses this section's key.")
        !body.contains('I-HAVE-READ-THE-GUIDE')
    }

    def "a gated section or sub-section serves its key on top -- #section"() {
        when:
        def content = script.toolGetToolGuide(section).content as String
        def lines = content.readLines()

        then:
        lines[0] ==~ KEY_LINE
        lines[0] == keyLine(section, false)
        lines[1].startsWith(NOTE_PREFIX)
        lines[2] == ''
        content.endsWith(rawBody(section))

        where:
        section << ['best_practice_reference', 'set_rule_reference', 'device_authorization',
                    'builtin_app_tools_crud', 'hub_admin_write_radios']
    }

    def "a parent serves one named key per gated sub-section -- #parent"() {
        when:
        def content = script.toolGetToolGuide(parent).content as String
        def lines = content.readLines()

        then:
        lines.take(subs.size()) == subs.collect { keyLine(it, true) }
        lines[subs.size()].startsWith(NOTE_PREFIX)
        lines[subs.size() + 1] == ''
        content.endsWith(rawBody(parent))

        where:
        parent              | subs
        'hub_admin_write'   | ['hub_admin_write_destructive', 'hub_admin_write_radios', 'hub_admin_write_devices']
        'builtin_app_tools' | ['builtin_app_tools_rules', 'builtin_app_tools_crud']
    }

    def "an ungated section is served verbatim -- #section"() {
        expect:
        script.toolGetToolGuide(section).content == rawBody(section)

        where:
        section << ['tool_access', 'performance', 'set_rule_reference_conditions', 'hub_admin_write_code']
    }

    def "the full guide publishes no key and says where keys are"() {
        when:
        def content = script.toolGetToolGuide(null).content as String

        then:
        content.startsWith('Acknowledgment keys are published only when a single section is read.\n\n')
        !content.contains('I-HAVE-READ-THE-GUIDE')
    }

    def "a served key is the gate's current key for that section"() {
        given:
        settingsMap.enableMandatoryBPS = true
        settingsMap.enableWrite = true
        script.metaClass.toolSetRule = { m -> [success: true, stubbed: true] }
        def served = (script.toolGetToolGuide('set_rule_reference').content as String).readLines()[0] - 'Acknowledgment key: '

        expect:
        script.executeTool('hub_set_rule', [appId: 5, addTrigger: [capability: 'Switch'], confirm: true,
                                            bestPracticeKey: served]).stubbed == true
    }
}
