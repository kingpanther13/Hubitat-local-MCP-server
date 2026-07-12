package server

import support.ToolSpecBase

/**
 * Spec for hub_get_op_result (issue #348, section B) -- the read tool that
 * fetches the buffered result of a tokened write when the transport dropped the
 * original response. Impl lives in libraries/mcp-diagnostics-lib.groovy
 * (toolGetOpResult); it is reachable directly and through the hub_read_diagnostics
 * gateway. Read-only, idempotent, closed-world.
 *
 * Tri-state contract (keyed on atomicState.opTokens[opToken]):
 *   - absent      -> status:"unknown" (the call never arrived; safe to retry)
 *   - running     -> status:"running" + elapsedMs, do NOT re-issue the write
 *   - complete    -> status:"complete" + result:<parsed original result>
 */
class ToolGetOpResultSpec extends ToolSpecBase {

    private static final String FILE_PREFIX = 'mcp-op-result-'
    private static final long FIXED_NOW = 1234567890000L

    // -------------------- direct-call tri-state --------------------

    def "unknown: a token that never started reports status unknown and that it is safe to retry"() {
        given:
        settingsMap.enableRead = true

        when:
        def result = script.toolGetOpResult([opToken: 'neverused1'])

        then:
        result.status == 'unknown'
        result.opToken == 'neverused1'
        result.note instanceof String && result.note.toLowerCase().contains('retry')
    }

    def "running: an in-flight token reports status running with elapsedMs and a do-not-reissue note"() {
        given:
        settingsMap.enableRead = true
        atomicStateMap.opTokens = [
            runtok9999: [state: 'running', tool: 'hub_set_rule', startedAt: FIXED_NOW - 5000L]
        ]

        when:
        def result = script.toolGetOpResult([opToken: 'runtok9999'])

        then:
        result.status == 'running'
        result.opToken == 'runtok9999'
        result.tool == 'hub_set_rule'
        result.startedAt == FIXED_NOW - 5000L
        result.elapsedMs == 5000L
        result.note instanceof String && !result.note.trim().isEmpty()
    }

    def "complete: a finished token reports status complete and returns the parsed buffered result"() {
        given:
        settingsMap.enableRead = true
        script.metaClass.downloadHubFile = { String name ->
            name == FILE_PREFIX + 'donetok999.json' ? '{"success":true,"roomId":7}'.getBytes('UTF-8') : null
        }
        atomicStateMap.opTokens = [
            donetok999: [state: 'complete', tool: 'hub_create_room', isError: false,
                         finishedAt: FIXED_NOW - 2000L, file: FILE_PREFIX + 'donetok999.json']
        ]

        when:
        def result = script.toolGetOpResult([opToken: 'donetok999'])

        then:
        result.status == 'complete'
        result.opToken == 'donetok999'
        result.tool == 'hub_create_room'
        result.isError == false
        result.finishedAt == FIXED_NOW - 2000L
        result.result instanceof Map
        result.result.roomId == 7
    }

    def "hub_get_op_result throws when opToken is missing"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolGetOpResult([:])

        then:
        thrown(IllegalArgumentException)
    }

    def "hub_get_op_result rejects a malformed token"() {
        given:
        settingsMap.enableRead = true

        when:
        script.toolGetOpResult([opToken: 'bad!'])

        then:
        thrown(IllegalArgumentException)
    }

    // -------------------- dispatch envelope --------------------

    @spock.lang.Unroll
    def "hub_get_op_result dispatches in both catalog modes (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        // Gateway mode routes through hub_read_diagnostics; flat mode disables the
        // gateway tools by design, so the leaf is called by its real name.
        def response = useGateways
            ? mcpDriver.callTool('hub_read_diagnostics',
                [tool: 'hub_get_op_result', args: [opToken: 'neverused1']])
            : mcpDriver.callTool('hub_get_op_result', [opToken: 'neverused1'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.status == 'unknown'
        inner.opToken == 'neverused1'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_op_result maps a missing-token IAE to -32602 through the envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = true

        when:
        def response = mcpDriver.callTool('hub_get_op_result', [:])

        then:
        response.error != null
        response.error.code == -32602

        where:
        useGateways << [true, false]
    }

    // -------------------- classification / membership --------------------

    def "hub_get_op_result is classified read-only, idempotent, and closed-world"() {
        expect:
        script.getReadOnlyToolNames().contains('hub_get_op_result')
        script.getIdempotentToolNames().contains('hub_get_op_result')
        !script.getOpenWorldToolNames().contains('hub_get_op_result')
    }

    def "hub_get_op_result is a member of the hub_read_diagnostics gateway"() {
        expect:
        script.getGatewayConfig()['hub_read_diagnostics'].tools.contains('hub_get_op_result')
    }

    def "hub_get_op_result has a display-meta entry with a friendly title"() {
        when:
        def meta = script.getToolDisplayMeta()['hub_get_op_result']

        then:
        meta?.title == 'Get Operation Result'
        meta.summary instanceof String && meta.summary.endsWith('.') && meta.summary.length() <= 140
    }
}
