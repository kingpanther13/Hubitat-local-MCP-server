package server

import spock.lang.Shared
import spock.lang.Unroll
import support.RMUtilsMock
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

class ToolGenerateBugReportSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.hub = null
    }

    // ---------- helpers ----------

    private void seedLogs(List entries) {
        stateMap.debugLogs = [entries: entries, config: [logLevel: 'error', maxEntries: 100]]
    }

    private Map baseArgs(Map overrides = [:]) {
        return [
            title    : 'Trigger schema rejected my native rule',
            expected : 'Trigger was created',
            actual   : 'addTrigger errored',
        ] + overrides
    }

    private Map logEntry(Map fields) {
        Map details = (fields.details ?: [:]) + (fields.nativeAppId ? [appId: fields.nativeAppId] : [:])
        Map entry = [
            timestamp: fields.timestamp ?: 1_700_000_000_000L,
            level    : fields.level ?: 'error',
            component: fields.component ?: 'server',
            message  : fields.message ?: 'boom',
            details  : details,
        ]
        if (fields.ruleId) entry.ruleId = fields.ruleId
        return entry
    }

    // ---------- default invocation ----------

    def "default invocation returns success with split env counts and a [bug] title"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.success == true
        result.issueType == 'bug'
        result.privacyMode == 'private'
        result.suggestedTitle.startsWith('[bug] ')
        result.submitUrl.contains('?template=bug_report.yml')
        result.submitUrl.contains('&title=')
        result.report.contains('## Environment')
        result.report.contains('**Rules in legacy custom rule engine:** 0')
        result.report.contains('**Native Rule Machine rules:** 0')
        result.report.contains('**Devices exposed to MCP:** 0')
        result.logs.scoped == false
        result.logs.relevantCount == 0
        result.logs.otherRecentLogCount == 0
        result.logs.hint == null
    }

    def "env summary uses 'Rules in legacy custom rule engine' (not 'Custom MCP rules') so users don't read it as 'MCP can't see my RM rules'"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'the label clarifies this is the legacy custom rule engine, not the full MCP-visible rule set'
        result.report.contains('**Rules in legacy custom rule engine:**')
        !result.report.contains('**Custom MCP rules:**')
    }

    def "env summary shows non-zero customMcpRuleCount when child apps are present"() {
        given:
        sharedLocation.hub = new TestHub()
        childAppsList << new TestChildApp(id: 1L, label: 'Rule A')
        childAppsList << new TestChildApp(id: 2L, label: 'Rule B')
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('**Rules in legacy custom rule engine:** 2')
    }

    // ---------- native RM status (F1/F4 — installed vs not, count distinction) ----------

    def "env summary renders 'Native Rule Machine: not installed' when RMUtils throws the class-missing absence pattern"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def rmMock = new RMUtilsMock(
            throwOnGetRuleList4: new NoClassDefFoundError('hubitat.helper.RMUtils'),
            throwOnGetRuleList5: new NoClassDefFoundError('hubitat.helper.RMUtils'),
        )
        rmMock.install()

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('**Native Rule Machine:** not installed')
        !result.report.contains('**Native Rule Machine rules:** 0')

        cleanup:
        rmMock.uninstall()
    }

    def "env summary renders 'Native Rule Machine rules: N' when RMUtils returns N rules"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def rmMock = new RMUtilsMock(
            stubRuleList4: [[id: 101], [id: 102], [id: 103]],
            stubRuleList5: [],
        )
        rmMock.install()

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('**Native Rule Machine rules:** 3')
        !result.report.contains('not installed')

        cleanup:
        rmMock.uninstall()
    }

    def "env summary marks partial failure when one RMUtils version throws a non-absence error"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def rmMock = new RMUtilsMock(
            stubRuleList4: [[id: 1]],
            throwOnGetRuleList5: new RuntimeException('RMUtils v5 internal error'),
        )
        rmMock.install()

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'count from the surviving version is still surfaced, but the report flags partial failure'
        result.report.contains('**Native Rule Machine rules:** 1')
        result.report.contains('RMUtils partial failure')

        cleanup:
        rmMock.uninstall()
    }

    // ---------- issueType routing ----------

    def "issueType=enhancement uses [feature] prefix and enhancement.yml template"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'enhancement']))

        then:
        result.issueType == 'enhancement'
        result.suggestedTitle.startsWith('[feature] ')
        result.submitUrl.contains('?template=enhancement.yml')
    }

    def "issueType=agent_behavior uses [agent-behavior] prefix and agent_behavior.yml template"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'agent_behavior']))

        then:
        result.issueType == 'agent_behavior'
        result.suggestedTitle.startsWith('[agent-behavior] ')
        result.submitUrl.contains('?template=agent_behavior.yml')
    }

    @Unroll
    def "issueType '#raw' normalizes to '#expected' (template=#template)"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: raw]))

        then:
        result.issueType == expected
        result.submitUrl.contains("?template=${template}")

        where:
        raw                 || expected         | template
        'feature'           || 'enhancement'    | 'enhancement.yml'
        'feat'              || 'enhancement'    | 'enhancement.yml'
        'feature_request'   || 'enhancement'    | 'enhancement.yml'
        'agent-behavior'    || 'agent_behavior' | 'agent_behavior.yml'
        'tool_description'  || 'agent_behavior' | 'agent_behavior.yml'
        'BUG'               || 'bug'            | 'bug_report.yml'
        'garbage'           || 'bug'            | 'bug_report.yml'
        null                || 'bug'            | 'bug_report.yml'
    }

    // ---------- URL encoding ----------

    def "submitUrl URL-encodes the suggested title (no raw spaces or ampersands)"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([title: 'spaces & ampersands need encoding']))

        then:
        result.submitUrl.contains('&title=')
        def titleQuery = result.submitUrl.substring(result.submitUrl.indexOf('&title=') + '&title='.length())
        !titleQuery.contains(' ')
        !titleQuery.contains('&')
    }

    // ---------- title truncation ----------

    def "suggested title truncates to 140 chars with ellipsis when prefix+tool+title overflows"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def longTitle = 'x' * 200

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            title       : longTitle,
            failingTool : 'hub_manage_native_rules_and_apps',
        ]))

        then:
        result.suggestedTitle.length() == 140
        result.suggestedTitle.endsWith('...')
        result.suggestedTitle.startsWith('[bug] hub_manage_native_rules_and_apps:')
    }

    def "suggested title left untouched when under 140 chars"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([title: 'short title']))

        then:
        result.suggestedTitle == '[bug] short title'
        !result.suggestedTitle.endsWith('...')
    }

    // ---------- log scoping ----------

    def "log scoping with failingTool keeps matching entries and exposes hint for omitted ones"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor - 500_000, level: 'error', details: [tool: 'other_tool'],                       message: 'unrelated_old_log'),
            logEntry(timestamp: anchor,           level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'],     message: 'the_real_failure'),
            logEntry(timestamp: anchor + 5_000,   level: 'warn',  details: [tool: 'hub_manage_native_rules_and_apps'],     message: 'followup_warn'),
            logEntry(timestamp: anchor + 10_000,  level: 'error', details: [tool: 'different_tool'],                   message: 'noise_after'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'hub_manage_native_rules_and_apps']))

        then:
        result.logs.scoped == true
        result.logs.relevantCount == 2
        result.logs.otherRecentLogCount == 2
        result.logs.hint != null
        result.logs.hint.contains('includeUnrelatedRecentLogs=true')
        result.report.contains('the_real_failure')
        result.report.contains('followup_warn')
        !result.report.contains('unrelated_old_log')
        !result.report.contains('noise_after')
    }

    def "log scoping anchored on ruleId keeps entries whose ruleId matches"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', ruleId: '42',     message: 'rule_42_failure'),
            logEntry(timestamp: anchor + 1_000, level: 'warn',  ruleId: '42',     message: 'rule_42_warn'),
            logEntry(timestamp: anchor + 5_000, level: 'error', ruleId: '999',    message: 'unrelated_rule_999'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([ruleId: '42']))

        then:
        result.logs.scoped == true
        result.logs.relevantCount == 2
        result.report.contains('rule_42_failure')
        result.report.contains('rule_42_warn')
        !result.report.contains('unrelated_rule_999')
    }

    def "log scoping anchored on nativeAppId keeps entries whose details.appId matches"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', nativeAppId: '1234', message: 'native_app_1234_failure'),
            logEntry(timestamp: anchor + 1_000, level: 'warn',  nativeAppId: '1234', message: 'native_app_1234_warn'),
            logEntry(timestamp: anchor + 5_000, level: 'error', nativeAppId: '5678', message: 'unrelated_app_5678'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([nativeAppId: '1234']))

        then:
        result.logs.scoped == true
        result.logs.relevantCount == 2
        result.report.contains('native_app_1234_failure')
        result.report.contains('native_app_1234_warn')
        !result.report.contains('unrelated_app_5678')
    }

    def "failingTool with no matching log entries falls through to unscoped (lastN) output"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', details: [tool: 'tool_a'], message: 'unrelated_a'),
            logEntry(timestamp: anchor + 1_000, level: 'warn',  details: [tool: 'tool_b'], message: 'unrelated_b'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'tool_that_never_logged']))

        then: 'no anchor → unscoped path; both lastN entries returned, no hint'
        result.logs.scoped == false
        result.logs.relevantCount == 2
        result.logs.otherRecentLogCount == 0
        result.logs.hint == null
        result.report.contains('unrelated_a')
        result.report.contains('unrelated_b')
    }

    def "no failingTool / ruleId / nativeAppId means no scoping (all entries returned)"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,        level: 'error', details: [tool: 'tool_a'], message: 'aaa'),
            logEntry(timestamp: anchor + 1000, level: 'warn',  details: [tool: 'tool_b'], message: 'bbb'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.logs.scoped == false
        result.logs.otherRecentLogCount == 0
        result.logs.hint == null
        result.report.contains('aaa')
        result.report.contains('bbb')
    }

    def "empty log buffer with scoping requested returns scoped=false, no crash"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'hub_manage_native_rules_and_apps']))

        then: 'anchor is null (empty buffer), so fallback path is the unscoped lastN (which is also empty)'
        result.success == true
        result.logs.scoped == false
        result.logs.relevantCount == 0
        result.logs.otherRecentLogCount == 0
        result.logs.hint == null
    }

    def "includeUnrelatedRecentLogs=true folds the omitted entries into the report and drops the hint"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'], message: 'real_failure'),
            logEntry(timestamp: anchor + 5_000, level: 'error', details: [tool: 'different_tool'],               message: 'noise_after_real'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool                : 'hub_manage_native_rules_and_apps',
            includeUnrelatedRecentLogs : true,
        ]))

        then:
        result.report.contains('real_failure')
        result.report.contains('noise_after_real')
        result.logs.hint == null
    }

    def "logWindowSeconds window boundary — entries at the edge are in, just past are out"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        // The most-recent matching entry becomes the anchor (resolveAnchor returns the first
        // hit from the reversed list). With anchor = the latest entry, the window extends
        // backwards ±logWindowSeconds. windowStart = anchor - 30s, windowEnd = anchor + 30s.
        seedLogs([
            logEntry(timestamp: anchor - 30_001, level: 'warn',  details: [tool: 'X'], message: 'just_before'),  // out of window
            logEntry(timestamp: anchor - 30_000, level: 'warn',  details: [tool: 'X'], message: 'edge_in'),       // exactly at window start
            logEntry(timestamp: anchor,           level: 'error', details: [tool: 'X'], message: 'anchor_entry'), // anchor itself
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'X', logWindowSeconds: 30]))

        then:
        result.logs.scoped == true
        result.logs.relevantCount == 2
        result.report.contains('anchor_entry')
        result.report.contains('edge_in')
        !result.report.contains('just_before')
    }

    def "hint singular grammar fires when exactly one unrelated entry is omitted"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', details: [tool: 'X'], message: 'matched'),
            logEntry(timestamp: anchor + 1_000, level: 'error', details: [tool: 'Y'], message: 'unrelated_single'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'X']))

        then:
        result.logs.otherRecentLogCount == 1
        result.logs.hint.endsWith('entry.')
        !result.logs.hint.endsWith('entries.')
    }

    // ---------- ruleId rule-info section ----------

    def "ruleId pointing at a real custom MCP rule renders the related-rule section"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def child = new TestChildApp(id: 42L, label: 'My Test Rule')
        child.ruleData = [
            name: 'My Test Rule', enabled: true,
            triggers: [[type: 'time']], conditions: [], actions: [[type: 'on']],
            lastTriggered: 1_700_000_000_000L, executionCount: 7,
        ]
        childAppsList << child

        when:
        def result = script.toolGenerateBugReport(baseArgs([ruleId: '42']))

        then:
        result.report.contains('## Related Custom MCP Rule')
        result.report.contains('**Rule ID:** 42')
        result.report.contains('**Rule Name:** My Test Rule')
        result.report.contains('**Enabled:** true')
        result.report.contains('**Triggers:** 1')
        result.report.contains('**Actions:** 1')
        result.report.contains('**Execution Count:** 7')
        !result.report.contains('**Last Triggered:** Never')
    }

    def "ruleId with no matching child app omits the related-rule section entirely"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([ruleId: '999']))

        then:
        !result.report.contains('## Related Custom MCP Rule')
        !result.report.contains('## Related Rule (lookup failed)')
        result.success == true
    }

    def "ruleId matching a child whose getRuleData throws renders a 'lookup failed' section instead of nulls"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def child = new TestChildApp(id: 77L, label: 'Native-RM-style child')
        child.metaClass.getRuleData = { throw new MissingMethodException('getRuleData', child.getClass(), [] as Object[]) }
        childAppsList << child

        when:
        def result = script.toolGenerateBugReport(baseArgs([ruleId: '77']))

        then:
        result.report.contains('## Related Rule (lookup failed)')
        result.report.contains('**Rule ID:** 77')
        result.report.contains('**Lookup error:**')
        result.report.contains('pass it as `nativeAppId` instead')
        !result.report.contains('**Rule Name:** Unknown')
    }

    // ---------- privacy / public-safe mode ----------

    def "privacyMode=public suppresses raw log text, placeholders hub name, and instructs LLM + user"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([
            logEntry(timestamp: 1_700_000_000_000L, level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'], message: 'secret_message_in_log'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool : 'hub_manage_native_rules_and_apps',
            privacyMode : 'public',
        ]))

        then:
        result.privacyMode == 'public'
        result.report.contains('<hub-name>')
        !result.report.contains('secret_message_in_log')
        result.report.contains('public mode')
        result.instructions.toLowerCase().contains('if you are an llm')
        result.instructions.toLowerCase().contains('review')
    }

    def "includeRawLogs=true in public mode still hides hub name but shows raw log text"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([
            logEntry(timestamp: 1_700_000_000_000L, level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'], message: 'shown_anyway_in_public_mode'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool    : 'hub_manage_native_rules_and_apps',
            privacyMode    : 'public',
            includeRawLogs : true,
        ]))

        then:
        result.report.contains('<hub-name>')
        result.report.contains('shown_anyway_in_public_mode')
    }

    // ---------------------------------------------------------------------------
    // Dispatch-envelope counterparts (issue #187)
    //
    // hub_report_issue is routed through the executeTool switch (no gateway
    // group), so useGateways doesn't change tool resolution — the parameter is
    // varied to assert envelope behaviour is identical in both modes. The tool
    // has no IAE paths exercised by the direct features (all features here
    // build a report and return success), so dispatch counterparts focus on
    // distinct success-envelope shapes:
    //   - default invocation (bug template, success envelope with inner result)
    //   - issueType routing (enhancement -> [feature] prefix + template)
    //   - log scoping (relevantCount + hint surfaces through the envelope)
    //   - privacy mode (public-safe report body suppresses raw logs)
    //   - ruleId related-rule section
    // ---------------------------------------------------------------------------

    @Unroll
    def "hub_report_issue via dispatch returns success envelope with bug report fields (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs())

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.issueType == 'bug'
        inner.privacyMode == 'private'
        inner.suggestedTitle.startsWith('[bug] ')
        inner.submitUrl.contains('?template=bug_report.yml')
        inner.report.contains('## Environment')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_report_issue via dispatch routes issueType=enhancement to [feature] + enhancement.yml template (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs([issueType: 'enhancement']))

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.issueType == 'enhancement'
        inner.suggestedTitle.startsWith('[feature] ')
        inner.submitUrl.contains('?template=enhancement.yml')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_report_issue via dispatch surfaces log scoping fields on the envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor - 500_000, level: 'error', details: [tool: 'other_tool'],                       message: 'unrelated_old_log'),
            logEntry(timestamp: anchor,           level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'],     message: 'the_real_failure'),
            logEntry(timestamp: anchor + 5_000,   level: 'warn',  details: [tool: 'hub_manage_native_rules_and_apps'],     message: 'followup_warn'),
            logEntry(timestamp: anchor + 10_000,  level: 'error', details: [tool: 'different_tool'],                   message: 'noise_after'),
        ])

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs([failingTool: 'hub_manage_native_rules_and_apps']))

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.logs.scoped == true
        inner.logs.relevantCount == 2
        inner.logs.otherRecentLogCount == 2
        inner.logs.hint != null
        inner.logs.hint.contains('includeUnrelatedRecentLogs=true')
        inner.report.contains('the_real_failure')
        inner.report.contains('followup_warn')
        !inner.report.contains('unrelated_old_log')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_report_issue via dispatch in privacyMode=public suppresses raw log text in the report body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        seedLogs([
            logEntry(timestamp: 1_700_000_000_000L, level: 'error', details: [tool: 'hub_manage_native_rules_and_apps'], message: 'secret_message_in_log'),
        ])

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs([
            failingTool : 'hub_manage_native_rules_and_apps',
            privacyMode : 'public',
        ]))

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.privacyMode == 'public'
        inner.report.contains('<hub-name>')
        !inner.report.contains('secret_message_in_log')
        inner.instructions.toLowerCase().contains('if you are an llm')

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_report_issue via dispatch renders Related Custom MCP Rule section when ruleId resolves (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def child = new TestChildApp(id: 42L, label: 'My Test Rule')
        child.ruleData = [
            name: 'My Test Rule', enabled: true,
            triggers: [[type: 'time']], conditions: [], actions: [[type: 'on']],
            lastTriggered: 1_700_000_000_000L, executionCount: 7,
        ]
        childAppsList << child

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs([ruleId: '42']))

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.report.contains('## Related Custom MCP Rule')
        inner.report.contains('**Rule ID:** 42')
        inner.report.contains('**Rule Name:** My Test Rule')
        inner.report.contains('**Execution Count:** 7')

        where:
        useGateways << [true, false]
    }

}
