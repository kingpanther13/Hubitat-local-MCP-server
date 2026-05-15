package server

import spock.lang.Shared
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the reworked generate_bug_report tool (#171).
 *
 * Covers:
 *   - default invocation returns the expected result shape (bug + private mode)
 *   - split environment counts (custom MCP / native RM / devices)
 *   - issueType=bug | enhancement | agent_behavior route to the right title prefix + template
 *   - submitUrl URL-encodes the suggested title
 *   - log scoping kicks in ONLY when failingTool/ruleId/nativeAppId is passed
 *   - includeUnrelatedRecentLogs folds omitted entries back into the report
 *   - privacyMode=public substitutes <hub-name>, suppresses raw log text,
 *     and the instructions field carries both an LLM-directed sanitization
 *     ask and a user-directed review warning
 *   - includeRawLogs=true in public mode shows raw lines but still hides hub name
 *
 * Log entries follow the shape produced by mcpLog(): a Map with timestamp,
 * level, component, message, and an optional details Map carrying tool / appId
 * context (see mcpLog at hubitat-mcp-server.groovy:5740 in main).
 */
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
        Map entry = [
            timestamp: fields.timestamp ?: 1_700_000_000_000L,
            level    : fields.level ?: 'error',
            component: fields.component ?: 'server',
            message  : fields.message ?: 'boom',
            details  : fields.details ?: [:],
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
        result.report.contains('Custom MCP rules:')
        result.report.contains('Native Rule Machine rules:')
        result.report.contains('Devices exposed to MCP:')
        result.logs.scoped == false
        result.logs.relevantCount == 0
        result.logs.otherRecentLogCount == 0
        result.logs.hint == null
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

    // ---------- log scoping ----------

    def "log scoping with failingTool keeps matching entries and exposes hint for omitted ones"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor - 500_000, level: 'error', details: [tool: 'other_tool'],                       message: 'unrelated_old_log'),
            logEntry(timestamp: anchor,           level: 'error', details: [tool: 'manage_native_rules_and_apps'],     message: 'the_real_failure'),
            logEntry(timestamp: anchor + 5_000,   level: 'warn',  details: [tool: 'manage_native_rules_and_apps'],     message: 'followup_warn'),
            logEntry(timestamp: anchor + 10_000,  level: 'error', details: [tool: 'different_tool'],                   message: 'noise_after'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([failingTool: 'manage_native_rules_and_apps']))

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

    def "includeUnrelatedRecentLogs=true folds the omitted entries into the report and drops the hint"() {
        given:
        sharedLocation.hub = new TestHub()
        long anchor = 1_700_000_000_000L
        seedLogs([
            logEntry(timestamp: anchor,         level: 'error', details: [tool: 'manage_native_rules_and_apps'], message: 'real_failure'),
            logEntry(timestamp: anchor + 5_000, level: 'error', details: [tool: 'different_tool'],               message: 'noise_after_real'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool                : 'manage_native_rules_and_apps',
            includeUnrelatedRecentLogs : true,
        ]))

        then:
        result.report.contains('real_failure')
        result.report.contains('noise_after_real')
        result.logs.hint == null
    }

    // ---------- privacy / public-safe mode ----------

    def "privacyMode=public suppresses raw log text, placeholders hub name, and instructs LLM + user"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([
            logEntry(timestamp: 1_700_000_000_000L, level: 'error', details: [tool: 'manage_native_rules_and_apps'], message: 'secret_message_in_log'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool : 'manage_native_rules_and_apps',
            privacyMode : 'public',
        ]))

        then:
        result.privacyMode == 'public'
        result.sanitizationApplied == true
        result.report.contains('<hub-name>')
        !result.report.contains('secret_message_in_log')
        result.report.contains('raw text omitted in public mode')
        result.instructions.toLowerCase().contains('if you are an llm')
        result.instructions.toLowerCase().contains('review')
    }

    def "includeRawLogs=true in public mode still hides hub name but shows raw log text"() {
        given:
        sharedLocation.hub = new TestHub()
        seedLogs([
            logEntry(timestamp: 1_700_000_000_000L, level: 'error', details: [tool: 'manage_native_rules_and_apps'], message: 'shown_anyway_in_public_mode'),
        ])

        when:
        def result = script.toolGenerateBugReport(baseArgs([
            failingTool    : 'manage_native_rules_and_apps',
            privacyMode    : 'public',
            includeRawLogs : true,
        ]))

        then:
        result.report.contains('<hub-name>')
        result.report.contains('shown_anyway_in_public_mode')
    }
}
