package server

import support.ToolSpecBase

/**
 * Issue #392: hub_get_tool_guide's four biggest sections (set_rule_reference,
 * builtin_app_tools, hub_admin_write, performance) carry ~70% of the guide, so a caller
 * after one fact -- the wire shape of a Mode condition, say -- had to pull all 57 KB of
 * set_rule_reference. getToolGuideSubSections() splits those four into narrower keys the
 * same `section` parameter accepts; the parent keys are untouched.
 *
 * The load-bearing property is that the split is LOSSLESS and UNAMBIGUOUS: every "### "
 * block of a parent is claimed by exactly one sub-key, so no reference material becomes
 * unreachable and none is served twice. A "### " heading added later without a home fails
 * here rather than silently disappearing from the sub-section surface.
 */
class ToolGuideSubSectionsSpec extends ToolSpecBase {

    private String headingOf(String block) {
        return block.split('\n', -1)[0].substring(4)
    }

    /** Sub-keys that own the parent's preamble -- declared with an empty prefix list. */
    private List preambleOwners(Map subs) {
        return subs.findAll { k, prefixes -> !prefixes }.keySet().toList()
    }

    def "every sub-section parent is a real section, and no sub-key collides with a section name"() {
        given:
        def sections = script.getToolGuideSections()
        def subSections = script.getToolGuideSubSections()
        def allSubKeys = subSections.values().collectMany { it.keySet().toList() }

        expect: 'each parent key is served by getToolGuideSections()'
        (subSections.keySet() - sections.keySet()).isEmpty()

        and: 'sub-keys never shadow a section name -- toolGetToolGuide tries sections first, so a collision would make the sub-section unreachable'
        allSubKeys.findAll { sections.containsKey(it) } == []

        and: 'sub-keys are globally unique across parents (they share one flat namespace on the `section` parameter)'
        allSubKeys.size() == (allSubKeys as Set).size()
    }

    def "the split is lossless: every block of every parent is claimed by exactly one sub-key"() {
        given:
        def sections = script.getToolGuideSections()
        def subSections = script.getToolGuideSubSections()

        when: 'walk each parent block-by-block and record every ownership defect'
        def problems = []
        subSections.each { parentKey, subs ->
            def owners = preambleOwners(subs)
            if (owners.size() != 1) {
                problems << "'${parentKey}' declares ${owners.size()} preamble sub-keys (empty prefix list) ${owners}; expected exactly 1"
                return
            }
            def claimed = subs.collectEntries { k, v -> [(k): 0] }
            script.guideSectionBlocks(sections[parentKey]).eachWithIndex { block, idx ->
                def label = idx == 0 ? 'preamble' : headingOf(block as String)
                def blockOwners = idx == 0
                    ? owners
                    : subs.findAll { k, prefixes -> prefixes.any { label.startsWith(it) } }.keySet().toList()
                if (blockOwners.size() != 1) {
                    problems << "'${parentKey}' block ${idx} (${label}) is claimed by ${blockOwners.size()} sub-keys ${blockOwners}; expected exactly 1"
                }
                blockOwners.each { claimed[it] = claimed[it] + 1 }
            }
            claimed.findAll { k, n -> n == 0 }.each { k, n ->
                problems << "sub-key '${k}' of '${parentKey}' claims no blocks -- stale prefixes after a heading rename?"
            }
        }

        then: 'no orphaned block, no double-claimed block, no dead sub-key'
        problems == []
    }

    def "each sub-section is materially smaller than its parent and stays well under the response cap"() {
        given:
        def sections = script.getToolGuideSections()
        def subSections = script.getToolGuideSubSections()

        when:
        def problems = []
        subSections.each { parentKey, subs ->
            def parentLen = (sections[parentKey] as String).length()
            subs.keySet().each { subKey ->
                def len = (script.toolGetToolGuide(subKey).content as String).length()
                if (len >= parentLen) problems << "'${subKey}' (${len}) is not smaller than parent '${parentKey}' (${parentLen})"
                // The point of the split: the hub's JSON-RPC response cap is 128 KiB, and the
                // largest sub-section today is ~14 KB. Well before one nears the cap it should
                // be split again rather than paginated.
                if (len >= 40000) problems << "'${subKey}' is ${len} chars -- split it further before it nears the hub response cap"
            }
        }

        then:
        problems == []
    }

    def "each sub-key serves the block it is named for, and not a sibling's -- #subKey"() {
        // The losslessness test proves every block has exactly ONE owner; it cannot prove the owner
        // is the RIGHT one. Moving hub_call_zwave from _radios to _devices, or the addAction guards
        // back into _triggers, would keep every structural assertion green while the library
        // pointers send callers to a sub-key that does not answer them. This is that check: one
        // sentinel it MUST carry, one a sibling owns that it MUST NOT.
        given:
        def content = script.toolGetToolGuide(subKey).content as String

        expect:
        content.contains(present)
        !content.contains(absent)

        where:
        subKey                          | present                                                       | absent
        'hub_admin_write_overview'      | '## Admin, System & Destructive Write Tools'                  | '### hub_get_info'
        'hub_admin_write_destructive'   | '### Destructive Write Tools - Pre-Flight Checklist'          | '### hub_call_zwave'
        'hub_admin_write_radios'        | '### hub_call_zwave'                                          | '### hub_create_device'
        'hub_admin_write_devices'       | '### hub_call_device_command'                                 | '### hub_set_zwave'
        'hub_admin_write_code'          | '### hub_create_app'                                          | '### hub_update_firmware'
        'hub_admin_write_system'        | '### hub_set_system_settings'                                 | '### hub_update_package'
        'performance_overview'          | '## Performance Tips'                                         | '### hub_get_logs'
        'performance_devices'           | '### hub_list_devices'                                        | '### hub_get_metrics'
        'performance_diagnostics'       | '### hub_get_logs'                                            | '### hub_list_devices'
        'builtin_app_tools_overview'    | '## Installed-App & Native-Rule Tools'                        | '### hub_call_rule'
        'builtin_app_tools_apps'        | '### hub_list_hpm_packages'                                   | '### hub_set_native_app'
        'builtin_app_tools_rules'       | '### hub_get_rule_health'                                     | '### hub_list_drivers'
        'builtin_app_tools_crud'        | '### Safety model for native CRUD'                            | '### hub_get_rule_health'
        'set_rule_reference_overview'   | '## `hub_set_rule` capability reference'                      | '### Shapes come from discover'
        'set_rule_reference_shapes'     | '### Shapes come from discover'                               | '### `walkStep` schema-aware wizard walker'
        'set_rule_reference_conditions' | '### `addRequiredExpression` operator contract'               | '### Shapes come from discover'
        'set_rule_reference_walkstep'   | '### `walkStep` schema-aware wizard walker'                   | '### `addRequiredExpression` operator contract'
        'set_rule_reference_responses'  | '### Responses: applied, activated, verified'                 | '### Raw `settings`/`button` mode'
    }

    def "the shapes page points at discover for every kind"() {
        expect:
        (script.toolGetToolGuide('set_rule_reference_shapes').content as String)
            .contains("kind:'trigger'|'condition'|'action'")
    }

    def "the outcome flags are documented for hub_set_rule responses, not every write"() {
        given:
        def content = script.toolGetToolGuide('set_rule_reference_responses').content as String

        expect:
        content.contains("Every `hub_set_rule` response answers `success`, `partial` and three outcome flags")
        content.contains("`hub_set_native_app` responses do not carry them")
        !content.contains("Every write answers")
    }

    def "raw mode documents that a pageName button is checked after the call's settings are written"() {
        given:
        def content = script.getToolGuideSections()['set_rule_reference'] as String

        expect:
        content.contains("checked against the page as re-rendered after the call's `settings` land")
        content.contains("after a settings write the result is `partial` with `settingsApplied`")
        !content.contains("before any `settings` are written")
    }

    def "toolGetToolGuide serves a sub-section with its parent named, and the parent still serves the whole section"() {
        when: 'a sub-key goes through the live dispatcher'
        def sub = script.toolGetToolGuide('set_rule_reference_conditions')

        then: 'success envelope carries the parent pointer and the condition reference only'
        sub.success == true
        sub.section == 'set_rule_reference_conditions'
        sub.parentSection == 'set_rule_reference'
        (sub.note as String).contains("section='set_rule_reference'")
        (sub.content as String).contains('`addRequiredExpression` operator contract')
        (sub.content as String).contains('`replaceRequiredExpression`')
        !(sub.content as String).contains('Shapes come from discover')

        when: 'the parent key goes through the same dispatcher'
        def parent = script.toolGetToolGuide('set_rule_reference')

        then: 'whole section behind its acknowledgment key, no parentSection -- and it advertises its sub-keys'
        parent.success == true
        parent.section == 'set_rule_reference'
        parent.parentSection == null
        (parent.content as String) == script._guideSectionServed('set_rule_reference', script.getToolGuideSections()['set_rule_reference'] as String)
        (parent.content as String).endsWith(script.getToolGuideSections()['set_rule_reference'] as String)
        (parent.subSections as List).contains('set_rule_reference_conditions')

        and: 'every block of the sub-section body is verbatim parent text (the served key header is not body), and the sub-section costs under half of it'
        script.guideSectionBlocks(script.guideSubSectionLookup('set_rule_reference_conditions').content).every { (parent.content as String).contains(it as String) }
        (sub.content as String).length() < (parent.content as String).length() / 2
    }

    def "section normalization reaches a sub-key through the dotted form used in issue 392 too"() {
        // toolGetToolGuide folds case and maps every non [a-z_] char to '_', so the
        // `set_rule_reference.conditions` spelling lands on the same key.
        expect:
        script.toolGetToolGuide('set_rule_reference.conditions').section == 'set_rule_reference_conditions'
        script.toolGetToolGuide('SET_RULE_REFERENCE_SHAPES').section == 'set_rule_reference_shapes'
    }

    def "an unknown section lists the sub-sections alongside the sections"() {
        when:
        def result = script.toolGetToolGuide('set_rule_reference_nope')

        then: 'the error hands the caller both levels of the key space'
        result.success == false
        (result.availableSections as List).contains('set_rule_reference')
        (result.availableSubSections['set_rule_reference'] as List).contains('set_rule_reference_shapes')
    }

    def "the preamble sub-key serves the section intro and none of the ### blocks"() {
        when:
        def overview = script.toolGetToolGuide('performance_overview')

        then:
        overview.success == true
        (overview.content as String).startsWith('## Performance Tips')
        !(overview.content as String).contains('### hub_get_logs')
    }
}
