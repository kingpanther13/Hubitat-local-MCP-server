package server

import spock.lang.Unroll
import support.ToolSpecBase

class RelayBudgetSpec extends ToolSpecBase {

    private static final long FIXED_NOW = 1234567890000L

    def "_relayBudgetMs defaults to 6000 when the setting is unset"() {
        expect:
        script._relayBudgetMs() == 6000L
    }

    def "_relayBudgetMs honours a supported settings.relayBudgetMs"() {
        given:
        settingsMap.relayBudgetMs = 7000

        expect:
        script._relayBudgetMs() == 7000L
    }

    @Unroll
    def "both transport budgets preserve off and floor positive #configured to #effective"() {
        given:
        settingsMap.relayBudgetMs = configured
        settingsMap.lanBudgetMs = configured

        expect:
        script._relayBudgetMs() == effective
        script._lanBudgetMs() == effective

        where:
        configured | effective
        0          | 0L
        1          | 6000L
        1500       | 6000L
        1501       | 6000L
        5999       | 6000L
        6000       | 6000L
        12000      | 12000L
    }

    def "_isCloudRequest defaults to false when no cloud request marker is present"() {
        // The harness request has no requestSource, so the try/catch returns the
        // safe LAN default -- LAN behaviour must be identical to today.
        expect:
        script._isCloudRequest() == false
    }

    def "_lanBudgetMs defaults to 0 (off) when the setting is unset"() {
        expect:
        script._lanBudgetMs() == 0L
    }

    def "_lanBudgetMs honours settings.lanBudgetMs"() {
        given:
        settingsMap.lanBudgetMs = 45000

        expect:
        script._lanBudgetMs() == 45000L
    }

    @spock.lang.Unroll
    def "invalid stored write cap #value falls back to the safe default"() {
        given:
        settingsMap.maxConcurrentWrites = value

        expect:
        script._maxConcurrentWrites() == 2

        where:
        value << [4294967296L, Long.MAX_VALUE, -1, 101, 1.5G, 'invalid', true]
    }

    @spock.lang.Unroll
    def "valid stored write cap #value retains the configured limit #expected"() {
        given:
        settingsMap.maxConcurrentWrites = value

        expect:
        script._maxConcurrentWrites() == expected

        where:
        value | expected
        1     | 1
        100   | 100
        '3'   | 3
        3.0G  | 3
        '1E2' | 100
    }

    def "_maxConcurrentWrites defaults to 2 and honours zero as unlimited"() {
        expect:
        script._maxConcurrentWrites() == 2

        when:
        settingsMap.maxConcurrentWrites = 0

        then:
        script._maxConcurrentWrites() == 0
    }

    def "hub_call_device_replace(list_options) answers the READ master, not the write gates"() {
        given: 'writes disabled and the best-practice gate ON -- the read mode must still run'
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        def ran = 0
        script.metaClass.toolCallDeviceReplace = { Map a -> ran++; [success: true, listOptions: true, options: []] }

        when:
        def result = script.executeTool('hub_call_device_replace', [old_device_id: '12', list_options: true])

        then: 'it executed: no Write-master block, no bestPracticeKey demanded'
        ran == 1
        result.success == true

        when: 'the same tool WITHOUT the read mode is still gated as the write it is'
        script.executeTool('hub_call_device_replace', [old_device_id: '12', new_device_id: '13', confirm: true])

        then: 'refused by the Write master -- named, so the best-practice gate cannot stand in for it'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
        ex.message.contains('hub_call_device_replace')
    }

    @Unroll
    def "_timeBudgetExceeded fires only past the live budget for the request's source (#desc)"() {
        given:
        settingsMap.relayBudgetMs = relayMs
        if (lanMs != null) settingsMap.lanBudgetMs = lanMs
        script.metaClass._isCloudRequest = { -> cloud }

        expect:
        script._timeBudgetExceeded(t0) == expected

        where:
        desc                          | cloud | relayMs | lanMs | t0                | expected
        'cloud, at minimum budget'    | true  | 100     | null  | FIXED_NOW - 6000L | true
        'cloud, under minimum budget' | true  | 100     | null  | FIXED_NOW - 5999L | false
        'cloud, at larger budget'     | true  | 8000    | null  | FIXED_NOW - 8000L | true
        'cloud, under larger budget'  | true  | 8000    | null  | FIXED_NOW - 7999L | false
        'cloud, null t0'              | true  | 100     | null  | null              | false
        'cloud, budget disabled 0'    | true  | 0       | null  | FIXED_NOW - 200L  | false
        'LAN, lan budget unset (off)' | false | 100     | null  | FIXED_NOW - 999L  | false
        'LAN, at minimum budget'      | false | 100     | 200   | FIXED_NOW - 6000L | true
        'LAN, under minimum budget'   | false | 100     | 200   | FIXED_NOW - 5999L | false
        'LAN, at larger budget'       | false | 100     | 8000  | FIXED_NOW - 8000L | true
        'LAN, under larger budget'    | false | 100     | 8000  | FIXED_NOW - 7999L | false
        'LAN, lan budget disabled 0'  | false | 100     | 0     | FIXED_NOW - 300L  | false
        'cloud ignores the LAN knob'  | true  | 0       | 50    | FIXED_NOW - 200L  | false
    }

    def "new document envelope preserves ordered changes without leaking a legacy budget argument"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        Map received
        script.metaClass.toolUpdateRuleDocument = { Map args -> received = args; [success: true] }
        def changes = [[part: 'required', value: [tokens: ['1']]], [part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]
        when:
        def response = mcpDriver.callTool('hub_manage_rule_machine', [tool: 'hub_set_rule', args: [operation: 'update', rule_id: 1, confirm: true, args: [changes: changes]]])
        then:
        response.error == null
        received.changes == changes
        !received.containsKey('__reqT0')
    }

    @Unroll
    def "a poll on a budgeted transport ends by the request deadline: #label"() {
        given:
        script.metaClass._isCloudRequest = { -> cloud }
        settingsMap.lanBudgetMs = lanBudget
        def captured = [:]
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> captured.args = a; captured.deadline = d; [success: false, timedOut: true, elapsedMs: 10] }

        when:
        def r = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on',
                                               timeoutMs: asked, __reqT0: FIXED_NOW - spent])

        then:
        captured.args.timeoutMs == waited
        captured.deadline == deadline
        (r.budgetLimited == true) == limited
        !limited || r.requestedTimeoutMs == asked

        where:
        label                       | cloud | lanBudget | asked | spent || waited | limited | deadline
        'cloud, long poll clamped'  | true  | null      | 25000 | 500   || 5500   | true    | FIXED_NOW + 5500
        'cloud, short poll as asked'| true  | null      | 4000  | 500   || 4000   | false   | FIXED_NOW + 5500
        'LAN, no budget'            | false | null      | 25000 | 500   || 25000  | false   | null
        'LAN with a budget'         | false | 8000      | 25000 | 1000  || 7000   | true    | FIXED_NOW + 7000
    }

    def "the engine reporting a deadline stop marks the result budget-limited even when the ask fit"() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> [success: false, timedOut: true, elapsedMs: 3000, budgetStopped: true] }

        when:
        def r = script.toolPollUntilAttribute([deviceIds: ['10', '11'], attribute: 'switch', expectedValue: 'on',
                                               timeoutMs: 4000, __reqT0: FIXED_NOW - 500])

        then:
        r.budgetLimited == true
        r.requestedTimeoutMs == 4000
        !r.containsKey('budgetStopped')
        r.budgetNote.contains('3000 ms')
    }

    def "a poll that converged under the clamp is reported as converged, not as stopped"() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> [success: true, finalValue: 'off', elapsedMs: 1] }

        when:
        def r = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'off',
                                               timeoutMs: 25000, __reqT0: FIXED_NOW - 500])

        then:
        r.success == true
        !r.containsKey('budgetLimited')
        !r.containsKey('budgetNote')
    }

    def "a stableForMs that cannot settle in the budget left is refused before any read"() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        boolean engineRan = false
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> engineRan = true; [success: true] }

        when:
        script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on',
                                       timeoutMs: 40000, stableForMs: 30000, __reqT0: FIXED_NOW - 500])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('stableForMs 30000')
        !engineRan
    }

    def "a request with under 100 ms left answers without polling"() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        boolean engineRan = false
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> engineRan = true; [success: true] }

        when:
        def r = script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on',
                                               timeoutMs: 5000, __reqT0: FIXED_NOW - 5950])

        then:
        r.success == false
        r.budgetLimited == true
        r.timeoutMs == 0
        !engineRan
    }

    @Unroll
    def "the wrapper hands #label straight to the engine without a deadline"() {
        given:
        script.metaClass._isCloudRequest = { -> true }
        def captured = [:]
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> captured.args = a; captured.deadline = d; [success: true] }

        when:
        script.toolPollUntilAttribute([deviceId: '10', attribute: 'switch', expectedValue: 'on'] + extra)

        then:
        captured.deadline == null
        !captured.args.containsKey('__reqT0')

        where:
        label                     | extra
        'a call with no clock'    | [timeoutMs: 25000]
        'an out-of-range timeout' | [timeoutMs: 50, __reqT0: FIXED_NOW - 500]
    }

    // The other leaves in _budgetAwareTools alongside hub_set_rule. Each owns a
    // partial-commit loop that consumes the clock (hub_call_rule's per-rule stop/start
    // toggle; the cloner's stageDisabled disable loop; hub_call_device_command's serial
    // batch dispatch), and each is reached through the SAME two-hop injection as the pin
    // above -- a leaf missing from the allowlist never sees the key, so its loop silently
    // runs unbounded. The loop behaviour itself is pinned in the tools' own specs; these
    // pin only DELIVERY.

    def "the budget clock reaches hub_get_device_attribute polls through dispatch and the gateway"() {
        given:
        settingsMap.useGateways = true
        def captured = [:]
        script.metaClass._pollUntilAttributeCore = { Map a, Long d = null -> captured.args = a; [success: true, finalValue: 'on'] }

        when:
        def response = mcpDriver.callTool('hub_read_devices', [tool: 'hub_get_device_attribute',
            args: [deviceId: '10', attribute: 'switch', expectedValue: 'on', timeoutMs: 2000]])

        then: 'the wrapper consumed the clock; the engine never sees it'
        response.error == null
        captured.args.timeoutMs == 2000
        !captured.args.containsKey('__reqT0')
    }
    def "the budget clock reaches hub_call_rule through dispatch and the gateway"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass.toolRunRmRule = { Map a -> captured.args = a; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_native_rules_and_apps',
            [tool: 'hub_call_rule', args: [ruleId: [1, 2], action: 'stop']])

        then:
        response.error == null
        captured.args.__reqT0 instanceof Long
        captured.args.ruleId == [1, 2]
    }

    def "the budget clock reaches hub_clone_native_app through dispatch and the gateway"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass.toolCloneNativeApp = { Map a -> captured.args = a; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_native_rules_and_apps',
            [tool: 'hub_clone_native_app', args: [sourceAppId: 1, stageDisabled: true, confirm: true]])

        then:
        response.error == null
        captured.args.__reqT0 instanceof Long
        captured.args.stageDisabled == true
    }

    def "the budget clock reaches hub_import_native_app through dispatch and the gateway"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass.toolImportNativeApp = { Map a -> captured.args = a; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_native_rules_and_apps',
            [tool: 'hub_import_native_app',
             args: [jsonContent: '{}', parentHintAppId: 1, stageDisabled: true, confirm: true]])

        then:
        response.error == null
        captured.args.__reqT0 instanceof Long
        captured.args.stageDisabled == true
    }

    def "the budget clock reaches hub_call_device_command through dispatch and the gateway"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass.toolSendCommand = { d, c, p = null, w = null, cmds = null, t0 = null, st = true ->
            captured.reqT0 = t0
            captured.commands = cmds
            [success: true, count: 1, sentCount: 1, failedCount: 0, results: []]
        }

        when: 'the commands form -- the shape whose serial dispatch loop consumes the clock'
        def response = mcpDriver.callTool('hub_manage_devices',
            [tool: 'hub_call_device_command', args: [commands: [[deviceId: '10', command: 'on']]]])

        then: 'the clock arrived as the sixth positional argument, not dropped by the gateway hop'
        response.error == null
        captured.reqT0 instanceof Long
        captured.commands.size() == 1
    }

    @spock.lang.Unroll
    def "_hubRtLog warns only when a completed internal request meets the relay budget (#elapsed ms, budget #budget)"() {
        given:
        settingsMap.relayBudgetMs = budget
        settingsMap.mcpLogLevel = 'debug'
        seedDebugLogHistory([entries: [], config: [logLevel: 'debug', maxEntries: 100]])

        when:
        script._hubRtLog('GET', '/hub/wifi/join?psk=hunter2', elapsed as long, outcome)
        def warns = script.getDebugLogEntries().findAll { it.level == 'warn' && it.message?.contains('[hubrt] slow internal') }

        then:
        warns.size() == expectedWarns
        warns.every { it.message.contains("(${outcome})") && it.message.contains("${elapsed}ms") }
        warns.every { !it.message.contains('hunter2') && it.message.contains('psk=') }

        where:
        elapsed | budget | outcome                 | expectedWarns
        5999    | 6000   | 'ok'                    | 0
        6000    | 6000   | 'ok'                    | 1
        9000    | 6000   | 'SocketTimeoutException'| 1
        9000    | 0      | 'ok'                    | 0
    }
}
