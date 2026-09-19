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
        seedDebugLogHistory([entries: entries, config: [logLevel: 'error', maxEntries: 100]])
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


    private Map clientRecord(Map overrides = [:]) {
        return [
            name                     : 'claude-ai',
            version                  : '1.4.2',
            title                    : 'Claude',
            protocolVersion          : '2025-11-25',
            requestedProtocolVersion : '2025-11-25',
            era                      : 'legacy',
            source                   : 'cloud',
            wrapper                  : false,
            seenAt                   : 1_700_000_000_000L,
        ] + overrides
    }

    private void seedClient(Map record) {
        atomicStateMap.mcpClientLastSeen = record
        atomicStateMap.mcpClientsRecent = record == null ? [] : [record]
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

    def "env summary reports gateway tool-mode by default (useGateways unset)"() {
        given: 'a genuinely-unset useGateways (the flat CI matrix presets it false in setup)'
        settingsMap.remove('useGateways')
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('**Tool mode:** gateway')
        !result.report.contains('outputSchemas advertised')
    }

    def "env summary reports flat tool-mode when useGateways=false"() {
        given:
        sharedLocation.hub = new TestHub()
        settingsMap.useGateways = false
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('**Tool mode:** flat')
    }

    def "_stripLibraryMarkers removes HPM include-library line markers that a multi-line string literal captures"() {
        expect:
        script._stripLibraryMarkers(input) == expected

        where:
        input                                                                      | expected
        'foo // library marker mcp.McpDebugLoggingLib, line 417'                    | 'foo'
        '# Bug Report: x // library marker mcp.McpDebugLoggingLib, line 419\nnext'  | '# Bug Report: x\nnext'
        'no markers here'                                                           | 'no markers here'
        null                                                                       | null
    }

    def "generated report never contains a leaked library marker"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs(ruleId: '99'))

        then:
        !result.report.contains('library marker')
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
        result.report.contains('<time-zone>')
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


    // ---------- client identity + connection ----------

    def "environment renders the client self-report and protocol version from the recorded identity"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord())

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Client (MCP self-report):** claude-ai 1.4.2 (Claude)')
        result.report.contains('- **Protocol version:** 2025-11-25 (legacy)')
    }

    def "environment omits the parenthesised title when the client reported none"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(title: null, version: '3.0', name: 'cursor', era: 'modern', protocolVersion: '2026-07-28'))

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Client (MCP self-report):** cursor 3.0')
        !result.report.contains('cursor 3.0 (')
        result.report.contains('- **Protocol version:** 2026-07-28 (modern)')
    }

    def "environment falls back to a not-reported marker when no identity was recorded"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Client (MCP self-report):** not reported on this request')
        result.report.contains('- **Protocol version:** not reported by client')
    }

    def "environment names the recently seen clients when this request declared none"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = clientRecord(name: null, version: null, title: null)
        atomicStateMap.mcpClientsRecent = [clientRecord()]

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        def line = result.report.readLines().find { it.startsWith('- **Client (MCP self-report):**') }
        line.startsWith('- **Client (MCP self-report):** not reported on this request ' +
                        '(recent clients: claude-ai 1.4.2 [legacy, cloud, seen ')
        line.endsWith('])')
    }

    def "environment reports a local connection by default"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Connection:** local')
    }

    def "environment reports a cloud connection when the request arrived over the relay"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        script.metaClass._isCloudRequest = { -> true }

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Connection:** cloud')
    }

    // ---------- MCP Server Settings section ----------

    def "settings section renders configured values and marks unset ones as default"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.enableDeveloperMode = true
        settingsMap.relayBudgetMs = 9000
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('## MCP Server Settings')
        result.report.contains('- **Developer mode:** true')
        result.report.contains('- **Bypass device allowlist:** true')
        result.report.contains('- **Cloud-relay budget (ms):** 9000')

        and: 'unset settings show their shipped default'
        result.report.contains('- **Read tools:** true (default)')
        result.report.contains('- **Max concurrent writes:** 2 (default)')
        result.report.contains('- **LAN budget (ms):** 0 (default)')
        result.report.contains('- **Loop guard max executions:** 30 (default)')
        result.report.contains('- **Loop guard window (sec):** 60 (default)')
        result.report.contains('- **Max captured states:** 20 (default)')
        result.report.contains('- **Disabled tools:** none (default)')
        result.report.contains('- **Disabled gateways:** none (default)')
    }

    def "the UI log-level line says not set when mcpLogLevel is unset"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.remove('mcpLogLevel')

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'the effective level already has its own line, so naming a default here would mislead'
        result.report.contains('- **MCP log level (UI setting):** not set (effective level above)')
    }

    def "the UI log-level line reports the configured level when mcpLogLevel is set"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.mcpLogLevel = 'debug'

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **MCP log level (UI setting):** debug')
    }

    def "settings section reports hub security as a boolean and never leaks the credentials"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.hubSecurityEnabled = true
        settingsMap.hubSecurityUser = 'hubadminlogin'
        settingsMap.hubSecurityPassword = 'correcthorsebatterystaple'

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Hub security enabled:** true')
        !result.report.contains('hubadminlogin')
        !result.report.contains('correcthorsebatterystaple')
        !result.report.toLowerCase().contains('password')
    }

    def "settings section names the disabled tools and gateways"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.disabled_tools = ['hub_list_devices', 'hub_get_info']
        settingsMap.disabled_gateways = ['hub_manage_rooms']

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Disabled tools:** hub_list_devices, hub_get_info')
        result.report.contains('- **Disabled gateways:** hub_manage_rooms')
    }

    def "settings section lists extra allowed origin hostnames in private mode"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.additionalAllowedOrigins = 'mcp.example.com, proxy.internal'

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('- **Extra allowed origins:** mcp.example.com, proxy.internal')
    }

    def "settings section reduces extra allowed origins to a count in public mode"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.additionalAllowedOrigins = 'mcp.example.com, proxy.internal'

        when:
        def result = script.toolGenerateBugReport(baseArgs([privacyMode: 'public']))

        then:
        result.report.contains('- **Extra allowed origins:** 2 configured')
        !result.report.contains('mcp.example.com')
        !result.report.contains('proxy.internal')
    }

    def "public mode keeps the client name and version"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord())

        when:
        def result = script.toolGenerateBugReport(baseArgs([privacyMode: 'public']))

        then: 'a client product name is not PII, so it survives the public-mode scrub'
        result.report.contains('- **Client (MCP self-report):** claude-ai 1.4.2 (Claude)')
        result.report.contains('<hub-name>')
    }

    // ---------- model / verbatim tool calls / client logs ----------

    def "llmModel, verbatimToolCalls and clientLogs render in their own sections"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord())

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            llmClient         : 'Claude Code 2.1',
            llmModel          : 'Claude Opus 5',
            stepsToReproduce  : '1. call hub_set_rule',
            verbatimToolCalls : 'hub_set_rule({"ruleId":"7"}) -> HTTP 500 Internal error',
            clientLogs        : '2026-09-18T10:00:01 ERROR mcp-server-hubitat: transport closed',
        ]))

        then:
        result.report.contains('- **LLM / client:** Claude Code 2.1')
        result.report.contains('- **Model:** Claude Opus 5')
        result.report.contains('## Verbatim Tool Calls')
        result.report.contains('hub_set_rule({"ruleId":"7"}) -> HTTP 500 Internal error')
        result.report.contains('## Client-Side Logs')
        result.report.contains('2026-09-18T10:00:01 ERROR mcp-server-hubitat: transport closed')
        result.report.contains('```text')
        result.missingContext*.field == []
    }

    def "a bug report marks the missing verbatim and client-log sections as not provided"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.report.contains('## Verbatim Tool Calls')
        result.report.contains('## Client-Side Logs')
        result.report.count('_Not provided_') == 2
        result.report.contains('- **Model:** Not provided')
    }

    def "a verbatim paste carrying its own fence opens the block on a longer backtick run"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            verbatimToolCalls: 'hub_set_rule(...) ->\n```\nHTTP 500\n```',
        ]))

        then: 'a three-backtick opener would be closed early by the paste\'s own fence'
        result.report.contains('## Verbatim Tool Calls\n````text\n')
        result.report.contains('\nHTTP 500\n')
    }

    def "an enhancement omits the verbatim and client-log sections entirely"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'enhancement']))

        then:
        !result.report.contains('## Verbatim Tool Calls')
        !result.report.contains('## Client-Side Logs')
        !result.report.contains('_Not provided_')
    }

    // ---------- word wrap ----------

    def "_bugReportWrap breaks a long line at word boundaries without exceeding the width"() {
        given:
        def line = ('word ' * 60).trim()

        when:
        def wrapped = script._bugReportWrap(line)

        then:
        line.length() > 290
        wrapped.split('\n').size() > 2
        wrapped.split('\n').every { it.length() <= 100 }

        and: 'wrapping only replaces the chosen spaces, so no text is lost'
        wrapped.replaceAll('\n', ' ') == line
    }

    def "_bugReportWrap preserves blank lines and existing line breaks"() {
        expect:
        script._bugReportWrap('first\n\nsecond') == 'first\n\nsecond'
        script._bugReportWrap('  indented short line') == '  indented short line'
        script._bugReportWrap(null) == null
    }

    def "_bugReportWrap never breaks a single word longer than the width"() {
        given:
        def word = 'z' * 150

        expect:
        script._bugReportWrap(word) == word
    }

    def "_bugReportWrap leaves lines inside a fenced block untouched"() {
        given:
        def fenced = '```\n' + ('x' * 300) + '\n```'

        expect:
        script._bugReportWrap(fenced) == fenced
    }

    def "an indented preformatted line is left unwrapped"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def indented = '    ' + ('alpha ' * 24) + 'ab'

        when:
        def result = script.toolGenerateBugReport(baseArgs([actual: indented]))

        then: 'the words would wrap on their own; the leading indent is what marks it preformatted'
        indented.length() == 150
        result.report.contains(indented)
    }

    def "report wraps expected, actual and stepsToReproduce but not the title"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def longTitle = 'T' * 130
        def longProse = ('alpha ' * 60).trim()

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            title            : longTitle,
            expected         : longProse,
            actual           : longProse,
            stepsToReproduce : longProse,
        ]))

        then: 'the title stays on one line; the prose fields are wrapped'
        result.report.contains('# Bug Report: ' + longTitle)
        !result.report.contains(longProse)
        result.report.split('\n').findAll { it.startsWith('alpha ') }.every { it.length() <= 100 }
    }

    // ---------- missingContext / preflight ----------

    def "missingContext asks for every absent field on a bug report"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.missingContext*.field == ['llmClient', 'llmModel', 'stepsToReproduce', 'verbatimToolCalls', 'clientLogs']
        result.missingContext.every { it.ask?.trim() }
        result.missingContext.find { it.field == 'llmClient' }.ask.contains('llmClient')
        result.missingContext.find { it.field == 'llmModel' }.ask.endsWith('-- do not guess.')
    }

    def "an unidentified client turns the blank llmClient ask into an explicit do-not-guess"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        def ask = result.missingContext.find { it.field == 'llmClient' }.ask
        ask.contains('do NOT guess or infer it')
        ask.contains('the client sent no self-report on this request')
    }

    def "a transport wrapper self-report asks the user to confirm the supplied llmClient"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(name: 'mcp-remote', version: '0.1.29', title: null, wrapper: true))

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Desktop']))

        then:
        def entry = result.missingContext.find { it.field == 'llmClient' }
        entry != null
        entry.ask.startsWith("Confirm with the user that 'Claude Desktop' is the host app")
        entry.ask.contains('transport wrapper')

        and: 'the environment line marks the wrapper so a maintainer does not read it as the host app'
        result.report.contains('- **Client (MCP self-report):** mcp-remote 0.1.29 (transport wrapper -- host app unknown)')
    }

    def "the Python SDK default identity mcp 0.1.0 is treated as a bridge, a real mcp version is not"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(name: 'mcp', version: version, title: null, wrapper: bridge))

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Desktop']))

        then:
        (result.missingContext.find { it.field == 'llmClient' } != null) == bridge
        result.report.contains('(transport wrapper -- host app unknown)') == bridge

        where:
        version | bridge
        '0.1.0' | true
        '1.2.0' | false
    }

    @Unroll
    def "a client named '#clientName' is #verdict as a transport wrapper"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(name: clientName, version: '1.0', title: null, wrapper: wrapper))

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Desktop']))

        then:
        (result.missingContext.find { it.field == 'llmClient' } != null) == wrapper
        result.report.contains('(transport wrapper -- host app unknown)') == wrapper

        where:
        clientName       || wrapper
        'mcp-proxy'      || true
        'fastmcp-remote' || true
        'supergateway'   || true
        'MCP-Remote'     || true
        'claude-ai'      || false

        verdict = wrapper ? 'detected' : 'not detected'
    }

    def "a blank llmClient with an identified non-wrapper client gets the plain ask"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(name: 'claude-ai', version: '1.4.0', title: null))

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'the server knows who it is talking to, so the do-not-guess escalation does not apply'
        def ask = result.missingContext.find { it.field == 'llmClient' }.ask
        ask == 'Ask the user which app they run (Claude Code, Claude Desktop, Claude.ai web, ChatGPT desktop, Cursor, ...) and pass it as llmClient.'
        !ask.contains('do NOT guess or infer')
    }

    def "a wrapper seen on an earlier request is still called out when this one named nobody"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = clientRecord(name: null, version: null, title: null)
        atomicStateMap.mcpClientsRecent = [clientRecord(name: 'mcp-remote', version: '0.1.29', title: null, wrapper: true)]

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Desktop']))

        then:
        def ask = result.missingContext.find { it.field == 'llmClient' }.ask
        ask.contains('transport wrapper')
        ask.contains('mcp-remote 0.1.29')

        and: 'history speaks for a past request, so the ask says which one and when'
        ask.contains('the most recent named client')
        ask.contains('seen ')

        and: 'the environment line names the bridge and says the host app behind it is unknown'
        def line = result.report.readLines().find { it.startsWith('- **Client (MCP self-report):**') }
        line.contains('mcp-remote 0.1.29 [')
        line.contains('(transport wrapper)')
        line.endsWith('-- host app unknown behind a transport wrapper')
    }

    def "a named non-wrapper client is not re-flagged by a wrapper left in the history"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = clientRecord(name: 'claude-code', version: '2.1.274', title: 'Claude Code', era: 'modern')
        atomicStateMap.mcpClientsRecent = [clientRecord(name: 'mcp-remote', version: '0.1.29', title: null, wrapper: true)]

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Code 2.1.274']))

        then:
        result.missingContext.every { it.field != 'llmClient' }
        result.report.contains('- **Client (MCP self-report):** claude-code 2.1.274 (Claude Code)')
        !result.report.contains('host app unknown behind a transport wrapper')
    }

    def "a named non-wrapper client leaves a supplied llmClient unquestioned"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord(name: 'claude-ai', version: '1.4.0', title: null))

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude.ai web']))

        then:
        result.missingContext.every { it.field != 'llmClient' }
    }

    def "missingContext on an enhancement asks only for the client and model"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'enhancement']))

        then:
        result.missingContext*.field == ['llmClient', 'llmModel']
    }

    def "missingContext drops a supplied field and still flags a blank one"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord())

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            llmClient        : 'Claude Desktop',
            llmModel         : '   ',
            stepsToReproduce : 'Call hub_set_rule twice.',
        ]))

        then:
        result.missingContext*.field == ['llmModel', 'verbatimToolCalls', 'clientLogs']
    }

    def "preflight tells the agent to raise the log level when it is not debug"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.preflight.size() == 3
        result.preflight[0].startsWith('MCP log level is error.')
        result.preflight[0].contains("hub_set_log_level(level='debug')")
        result.preflight[0].contains("hub_get_logs(mode='mcp')")
        result.preflight[0].contains('clientLogs')
        result.preflight[1].contains("hub_get_logs(mode='hub')")
        result.preflight[1].contains('clientLogs')
        result.preflight[2].contains('verbatimToolCalls')
    }

    def "preflight omits the log-level step when the configured level is already debug"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.mcpLogLevel = 'debug'

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.preflight.size() == 2
        !result.preflight.any { it.startsWith('MCP log level is') }
    }

    def "an agent_behavior report asks for the same evidence and renders the same gaps as a bug"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'agent_behavior']))

        then:
        result.missingContext*.field == ['llmClient', 'llmModel', 'stepsToReproduce', 'verbatimToolCalls', 'clientLogs']

        and: 'the transcript is the whole evidence, so the server-side log-level step does not apply'
        result.preflight == ['Paste the exact tool calls and raw responses in verbatimToolCalls -- do not paraphrase.']

        and:
        result.report.contains('## Verbatim Tool Calls')
        result.report.contains('## Client-Side Logs')
        result.report.count('_Not provided_') == 2
    }

    def "preflight is empty for a non-bug report"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([issueType: 'enhancement']))

        then:
        result.preflight == []
    }

    def "instructions lead with the preflight checklist and keep the privacy caveat"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then:
        result.instructions.startsWith('1. Resolve every preflight step')
        result.instructions.contains('Never guess llmClient or llmModel -- ask the user.')
        result.instructions.contains('submitUrl')
        result.instructions.contains("'What happened'")
        result.instructions.contains("'Agent report output'")
        result.instructions.toLowerCase().contains('if you are an llm')
        result.instructions.contains('MUST review')
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

    @Unroll
    def "hub_report_issue via dispatch carries preflight, missingContext and the settings section (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        sharedLocation.hub = new TestHub()
        seedLogs([])
        seedClient(clientRecord())

        when:
        def response = mcpDriver.callTool('hub_report_issue', baseArgs([llmClient: 'Claude Code 2.1']))

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.preflight.size() == 3
        inner.missingContext*.field == ['llmClient', 'llmModel', 'stepsToReproduce', 'verbatimToolCalls', 'clientLogs']

        and: 'the dispatched call itself declared no clientInfo, so the supplied llmClient must be confirmed, not trusted'
        inner.missingContext[0].ask.startsWith("Confirm with the user that 'Claude Code 2.1' is the host app")
        inner.report.contains('## MCP Server Settings')
        inner.report.contains('- **Client (MCP self-report):** not reported on this request (recent clients: claude-ai 1.4.2 [legacy, cloud, seen ')
        inner.report.contains('- **Connection:** ')

        where:
        useGateways << [true, false]
    }

    // ---------- secret scrubbing + withheld raw sections ----------

    def "access tokens and Authorization values are redacted out of the pasted sections"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            verbatimToolCalls : 'GET /apps/api/228/mcp?access_token=abc&limit=1',
            clientLogs        : 'Authorization: Bearer x',
        ]))

        then: 'the report is headed for a public tracker, so private mode scrubs too'
        result.report.contains('access_token=<redacted>')
        result.report.contains('Authorization: <redacted>')
        !result.report.contains('access_token=abc')
        !result.report.contains('Bearer x')
    }

    @Unroll
    def "the redaction covers the common credential shape: #label"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([verbatimToolCalls: payload]))

        then:
        result.report.contains(expected)
        !result.report.contains(secret)

        where:
        label                 | payload                                        | secret     | expected
        'quoted JSON token'   | '{"authToken":"s3cretA","x":1}'                | 's3cretA'  | '"authToken":"<redacted>"'
        'quoted JSON password'| '{"password": "s3cretB"}'                      | 's3cretB'  | '"password": "<redacted>"'
        'form password'       | 'user=bob&password=s3cretC&x=1'                | 's3cretC'  | 'password=<redacted>'
        'api key'             | 'api_key=s3cretD'                              | 's3cretD'  | 'api_key=<redacted>'
        'client secret'       | 'client_secret=s3cretE'                        | 's3cretE'  | 'client_secret=<redacted>'
        'yaml token'          | "token: 's3cretF'"                             | 's3cretF'  | "token: '<redacted>'"
        'basic scheme'        | 'Proxy: Basic dXNlcjpzM2NyZXRH'                | 'dXNlcjpzM2NyZXRH' | 'Basic <redacted>'
        'cookie header'       | 'Cookie: HUBSESSION=s3cretH; other=1'          | 's3cretH'  | 'Cookie: <redacted>'
    }

    def "a sentinel pasted into verbatimToolCalls is not treated as the client-log slot"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            verbatimToolCalls : 'payload mentions @@MCP_CLIENTLOGS@@ literally',
            clientLogs        : 'real client log line',
        ]))

        then: 'the two sections land once each, in order, and the pasted text survives verbatim'
        result.report.contains('payload mentions @@MCP_CLIENTLOGS@@ literally')
        result.report.count('## Client-Side Logs') == 1
        result.report.count('real client log line') == 1
        result.report.indexOf('## Verbatim Tool Calls') < result.report.indexOf('## Client-Side Logs')
    }

    def "public mode withholds both pasted sections and names how much was held back"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            privacyMode       : 'public',
            verbatimToolCalls : 'call A\ncall B',
            clientLogs        : 'a single log line',
        ]))

        then:
        result.report.contains('## Verbatim Tool Calls\n_2 line(s) omitted in public mode')
        result.report.contains('## Client-Side Logs\n_1 line(s) omitted in public mode')
        !result.report.contains('call A')
        !result.report.contains('a single log line')
    }

    def "a library marker pasted into verbatimToolCalls survives into the report"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        def pasted = 'ERROR: foo // library marker mcp.McpDebugLoggingLib, line 417'

        when:
        def result = script.toolGenerateBugReport(baseArgs([verbatimToolCalls: pasted]))

        then: 'the marker strip cleans the template, never the evidence pasted into it'
        result.report.contains(pasted)
    }

    // ---------- wrapper history: newest named entry only ----------

    def "a newer named non-wrapper client retires an older bridge in the history"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = clientRecord(name: null, version: null, title: null)
        atomicStateMap.mcpClientsRecent = [
            clientRecord(name: 'claude-code', version: '2.1.274', title: null),
            clientRecord(name: 'mcp-remote', version: '0.1.29', title: null, wrapper: true),
        ]

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Code 2.1.274']))

        then: 'only the newest sighting can still be on the other end of this connection'
        def line = result.report.readLines().find { it.startsWith('- **Client (MCP self-report):**') }
        !line.endsWith('-- host app unknown behind a transport wrapper')
        result.missingContext.find { it.field == 'llmClient' }.ask.contains('sent no self-report')
        !result.missingContext.find { it.field == 'llmClient' }.ask.contains('transport wrapper')
    }

    def "a bridge as the newest named entry still speaks for a nameless request"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = clientRecord(name: null, version: null, title: null)
        atomicStateMap.mcpClientsRecent = [
            clientRecord(name: 'mcp-remote', version: '0.1.29', title: null, wrapper: true),
            clientRecord(name: 'claude-code', version: '2.1.274', title: null),
        ]

        when:
        def result = script.toolGenerateBugReport(baseArgs([llmClient: 'Claude Code 2.1.274']))

        then:
        def line = result.report.readLines().find { it.startsWith('- **Client (MCP self-report):**') }
        line.endsWith('-- host app unknown behind a transport wrapper')

        and:
        def ask = result.missingContext.find { it.field == 'llmClient' }.ask
        ask.contains('the most recent named client')
        ask.contains('seen ')
    }

    // ---------- identity read failure ----------

    def "an unreadable identity is reported as unavailable rather than as an unnamed client"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        atomicStateMap.mcpClientLastSeen = new ExplodingRecord()

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'a read failure and a silent client are different diagnoses'
        result.report.contains('- **Client (MCP self-report):** unavailable (server-side identity read failed: IllegalStateException')
        result.report.contains('- **Protocol version:** unavailable (server-side identity read failed: IllegalStateException')

        and:
        def ask = result.missingContext.find { it.field == 'llmClient' }.ask
        ask.contains('the server could not read the client identity (IllegalStateException')
    }

    // ---------- effective settings ----------

    def "a numeric setting the accessor overrides prints the effective value and names the raw one"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.loopGuardMax = 0

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'a report showing only the raw 0 would explain the wrong behaviour'
        result.report.contains('- **Loop guard max executions:** 30 (configured 0)')
        result.report.contains('- **Cloud-relay budget (ms):** 6000 (default)')
    }

    def "the engine line reports the resolved mode alongside the raw toggle"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'the toggle is off by default yet the read custom_* tools are still served'
        result.report.contains('- **Legacy custom rule engine:** readonly (toggle unset)')
    }

    def "the settings section names the tools actually hidden from this client"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.disabled_tools = ['hub_list_devices']

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'the deny list is the input; what the client cannot see is the symptom'
        def line = result.report.readLines().find { it.startsWith('- **Tools hidden from this client:**') }
        line != null
        line.contains('hub_list_devices')
    }

    def "preflight points at the app settings when hub_set_log_level is hidden from the client"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([])
        settingsMap.disabled_tools = ['hub_set_log_level']

        when:
        def result = script.toolGenerateBugReport(baseArgs())

        then: 'telling an agent to call a tool it cannot see is a dead end'
        result.preflight[0].contains('hub_set_log_level is not available to this client')
        result.preflight[0].contains("ask the user to raise it in the app's settings")
        !result.preflight[0].contains("hub_set_log_level(level='debug')")
    }

    // ---------- wrap edge cases ----------

    def "_bugReportWrap keeps a run of spaces intact across a break"() {
        given:
        def line = ('alpha ' * 18) + 'a  b ' + ('beta ' * 18).trim()

        when:
        def wrapped = script._bugReportWrap(line)

        then:
        line.length() > 100
        wrapped.contains('\n')
        wrapped.replaceAll('\n', ' ') == line
        wrapped.readLines().every { it.trim() && !it.startsWith(' ') }
    }

    def "_bugReportWrap leaves a markdown table row unwrapped"() {
        given:
        def row = '| ' + ('cell | ' * 30)

        expect:
        row.length() > 100
        script._bugReportWrap(row) == row
    }

    // ---------- response-size recovery hint ----------

    def "the response-too-large hint for hub_report_issue names the fields to shrink"() {
        expect:
        def hint = script._responseTooLargeSuggestion('hub_report_issue')
        hint.contains('includeRawLogs')
        hint.contains('verbatimToolCalls')
        hint.contains('clientLogs')
    }

    /**
     * A stored record that throws the moment production copies it out of atomicState, so the
     * report sees a real identity READ failure rather than an absent record.
     */
    static class ExplodingRecord extends LinkedHashMap {
        ExplodingRecord() { super.put('name', 'exploding') }
        @Override
        Set entrySet() { throw new IllegalStateException('boom') }
    }

}
