package server

import java.util.concurrent.atomic.AtomicLong
import support.NativeInventoryFixture
import support.ToolSpecBase
import support.TestDevice

class DeviceMrtrSpec extends ToolSpecBase {
    AtomicLong clock

    def setup() {
        clock = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> clock.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> clock.addAndGet(ms) })
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
    }

    private Map call(String name, Map args, String continuation = null) {
        def params = [name: name, arguments: args]
        if (continuation != null) params.requestState = continuation
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call', 'Mcp-Name': name])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId,
            method: 'tools/call', params: params])
        script.handleMcpRequest()
        mcpDriver.parseResponseJson() as Map
    }

    private void finish(List scheduled) {
        script."${scheduled[1]}"(new LinkedHashMap(scheduled[2].data as Map))
    }

    def "pending #leaf read gives device-specific recovery advice at the continuation cap"() {
        given:
        Map args = [tool: leaf, args: leafArgs]
        String token = call('hub_read_devices', args).result.requestState
        int cap = script._mrtrMaxContinuationSlices() as Integer

        when:
        def legs = (1..cap).collect { this.call('hub_read_devices', args, token) }
        def result = mcpDriver.parseInner(legs[-1])

        then:
        legs[-1].result.resultType == 'complete'
        result.status == 'slow_read_timeout'
        result.error.contains(leaf)
        !result.error.contains('Logs')
        !result.note.contains('Logs')
        result.note.contains('fresh')
        result.note.contains('No hub state was changed')
        runInMillisCalls.size() == 1

        where:
        leaf               | leafArgs
        'hub_get_device'   | [deviceId: '88', mode: 'details']
        'hub_list_devices' | [scope: 'all']
    }

    def "slow #leaf completes outside the relay and retries never repeat the write"() {
        given:
        int writes = 0
        script.metaClass.toolManageVirtualDevice = { actual ->
            writes++; clock.addAndGet(12000L); [success: true, deviceId: '88']
        }
        script.metaClass.toolUpdateDevice = { actual ->
            writes++; clock.addAndGet(12000L); [success: true, deviceId: '88']
        }
        String outer = leaf == 'hub_update_device' ? 'hub_manage_devices' : leaf
        Map args = outer == leaf ? leafArgs : [tool: leaf, args: leafArgs]
        long started = clock.get()

        when:
        def first = call(outer, args)
        String token = first.result.requestState

        then:
        first.result.resultType == 'input_required'
        writes == 0
        clock.get() - started < 10000L

        when:
        def pending = call(outer, args, token)

        then:
        pending.result.resultType == 'input_required'
        writes == 0
        runInMillisCalls.size() == 1

        when:
        finish(runInMillisCalls[0])
        finish(runInMillisCalls[0])
        def completed = call(outer, args, token)
        def replay = call(outer, args, token)

        then:
        completed.result.resultType == 'complete'
        mcpDriver.parseInner(completed).deviceId == '88'
        mcpDriver.parseInner(replay).deviceId == '88'
        writes == 1
        script._activeWrites().isEmpty()

        where:
        leaf                        | leafArgs
        'hub_manage_virtual_device' | [action: 'create', deviceType: 'Virtual Switch', confirm: true]
        'hub_manage_virtual_device' | [action: 'delete', deviceNetworkId: 'test-dni', confirm: true]
        'hub_update_device'         | [deviceId: '88', fields: [label: 'Changed']]
    }

    def "slow #leaf reads continue without Write permission and each new call is fresh"() {
        given:
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        int reads = 0
        String label = 'Before'
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor, waitFor, reqT0 ->
            reads++; clock.addAndGet(12000L)
            [id: '88', label: label, nextCursor: 'unchanged-cursor', preferences: [token: 'private-pref']]
        }
        script.metaClass.toolListDevices = { Map listArgs ->
            reads++; clock.addAndGet(12000L); [devices: [[id: '88', label: label]]]
        }
        Map args = [tool: leaf, args: leafArgs]
        long started = clock.get()

        when:
        def first = call('hub_read_devices', args)
        String token = first.result.requestState

        then:
        first.result.resultType == 'input_required'
        clock.get() - started < 10000L
        reads == 0
        runInMillisCalls.size() == 1
        script._activeWrites().isEmpty()

        when:
        finish(runInMillisCalls[0])
        def complete = call('hub_read_devices', args, token)
        def replay = call('hub_read_devices', args, token)

        then:
        complete.result.resultType == 'complete'
        def result = mcpDriver.parseInner(complete)
        (leaf == 'hub_get_device' ? result.label : result.devices[0].label) == 'Before'
        def replayed = mcpDriver.parseInner(replay)
        (leaf == 'hub_get_device' ? replayed.label : replayed.devices[0].label) == 'Before'
        leaf != 'hub_get_device' || replayed.nextCursor == result.nextCursor
        reads == 1
        !groovy.json.JsonOutput.toJson(atomicStateMap).contains('private-pref')
        !groovy.json.JsonOutput.toJson(runInMillisCalls).contains('private-pref')
        script._activeWrites().isEmpty()

        when: 'an independent call after an external change must not reuse the old state'
        label = 'After'
        def next = call('hub_read_devices', args)
        finish(runInMillisCalls.last())
        def fresh = mcpDriver.parseInner(call('hub_read_devices', args, next.result.requestState))

        then:
        (leaf == 'hub_get_device' ? fresh.label : fresh.devices[0].label) == 'After'
        reads == 2

        where:
        leaf               | leafArgs
        'hub_get_device'   | [deviceId: '88', mode: 'configuration']
        'hub_list_devices' | [labelFilter: 'Before']
        'hub_list_devices' | [scope: 'owned']
    }

    def "a slow hub_list_rules read continues from its background snapshot and the next call is fresh"() {
        given:
        int reads = 0
        String label = 'Before'
        script.metaClass.toolListRmRules = { args ->
            reads++; clock.addAndGet(12000L); [rules: [[id: 7, label: label]], count: 1]
        }
        Map args = [tool: 'hub_list_rules', args: [:]]
        long started = clock.get()

        when:
        def first = call('hub_read_rules', args)

        then:
        first.result.resultType == 'input_required'
        clock.get() - started < 10000L
        reads == 0
        runInMillisCalls.size() == 1

        when:
        finish(runInMillisCalls[0])
        def complete = call('hub_read_rules', args, first.result.requestState)

        then:
        complete.result.resultType == 'complete'
        mcpDriver.parseInner(complete).rules[0].label == 'Before'
        reads == 1

        when: 'an independent call after a change reads again'
        label = 'After'
        def next = call('hub_read_rules', args)
        finish(runInMillisCalls.last())
        def fresh = mcpDriver.parseInner(call('hub_read_rules', args, next.result.requestState))

        then:
        fresh.rules[0].label == 'After'
        reads == 2
    }

    def "device read cannot replay a cached profile after scope is revoked"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 88, label: 'Private')]
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor, waitFor, reqT0 ->
            [id: '88', label: 'private-device-profile']
        }
        Map args = [deviceId: '88']
        String token = call('hub_get_device', args).result.requestState
        assert token != null
        finish(runInMillisCalls[0])
        settingsMap.selectedDevices = []

        when:
        def denied = call('hub_get_device', args, token)

        then:
        denied.error == null
        denied.result.isError == true
        !groovy.json.JsonOutput.toJson(denied).contains('private-device-profile')
    }

    def "a quickly completed device read needs one HTTP call but the next call is fresh"() {
        given:
        int reads = 0
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor, waitFor, reqT0 ->
            [id: '88', label: "Read ${++reads}".toString()]
        }
        RUN_IN_MILLIS_OVERRIDE.set({ List scheduled ->
            runInMillisCalls << scheduled
            finish(scheduled)
        })

        when:
        def first = call('hub_get_device', [deviceId: '88'])
        def second = call('hub_get_device', [deviceId: '88'])

        then:
        first.result.resultType == 'complete'
        second.result.resultType == 'complete'
        mcpDriver.parseInner(first).label == 'Read 1'
        mcpDriver.parseInner(second).label == 'Read 2'
        reads == 2
        runInMillisCalls.size() == 2
    }

    def "device read scheduler failure is an error with no inline fallback"() {
        given:
        int reads = 0
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor, waitFor, reqT0 ->
            reads++; [id: '88']
        }
        RUN_IN_MILLIS_OVERRIDE.set({ List scheduled -> throw new IllegalStateException('scheduler unavailable') })

        when:
        def failed = call('hub_get_device', [deviceId: '88'])

        then:
        failed.error == null
        failed.result.isError == true
        reads == 0
        script._activeWrites().isEmpty()
    }

    def "a lost device snapshot fails its continuation without starting another fetch"() {
        given:
        int reads = 0
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor, waitFor, reqT0 -> reads++; [id: '88'] }
        String token = call('hub_get_device', [deviceId: '88']).result.requestState
        assert token != null
        (scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map).clear()

        when:
        def failed = call('hub_get_device', [deviceId: '88'], token)
        finish(runInMillisCalls[0])

        then:
        failed.error == null
        failed.result.isError == true
        reads == 0
        runInMillisCalls.size() == 1
    }

    def "device worker validation retains the JSON RPC error and guide hint on replay"() {
        given:
        settingsMap.enableMandatoryBPS = false
        Map args = [action: 'delete', deviceNetworkId: 'unknown', confirm: true]
        int attempts = 0
        script.metaClass.toolManageVirtualDevice = { actual ->
            attempts++
            throw new IllegalArgumentException('No MCP-managed virtual device found')
        }
        String token = call('hub_manage_virtual_device', args).result.requestState
        call('hub_manage_virtual_device', args, token)
        finish(runInMillisCalls[0])

        when:
        def failed = call('hub_manage_virtual_device', args, token)
        def replay = call('hub_manage_virtual_device', args, token)

        then:
        failed.error == null
        failed.result.isError == true
        mcpDriver.parseInner(failed).error.contains('No MCP-managed virtual device found')
        mcpDriver.parseInner(failed).error.contains('virtual_devices')
        replay.error == failed.error
        attempts == 1
        script.getDebugLogEntries().any {
            it.level == 'error' && it.details?.tool == 'hub_manage_virtual_device' &&
                it.details.error == 'No MCP-managed virtual device found'
        }
    }

    def "a legacy client's hub_list_devices page ends at the relay budget with a cursor to continue"() {
        given: 'each detail read costs 2500 ms of the request clock'
        settingsMap.selectedDevices = (1..10).collect { i ->
            def d = new TestDevice(id: i, name: "D${i}", label: "Device-${i}")
            NativeInventoryFixture.register(hubGet, i.toString()) { clock.addAndGet(2500L); d }
            d
        }
        NativeInventoryFixture.registerDevicesPage(hubGet) { -> settingsMap.selectedDevices }

        when: 'a 2025-11-25 client lists every device in detail in one request'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-11-25'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId, method: 'tools/call',
            params: [name: 'hub_read_devices', arguments: [tool: 'hub_list_devices', args: [format: 'detailed']]]])
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson() as Map
        def page = mcpDriver.parseInner(response)

        then: 'the page stops once the 6000 ms budget is spent instead of outlasting the relay'
        !response.result.containsKey('requestState')
        page.devices*.id == ['1', '2', '3']
        page.total == 10
        page.budgetLimited == true
        page.hasMore == true
        page.nextCursor == '3'
    }

    def "a modern hub_list_devices background read runs without the request clock"() {
        given:
        def clocks = []
        script.metaClass.toolListDevices = { Map listArgs ->
            clocks << listArgs.__reqT0; clock.addAndGet(12000L); [devices: [], count: 0, total: 0]
        }
        Map args = [tool: 'hub_list_devices', args: [:]]

        when:
        String token = call('hub_read_devices', args).result.requestState
        finish(runInMillisCalls[0])
        def completed = call('hub_read_devices', args, token)

        then: 'the worker gets no request clock, so continuation, not a cut, carries it past the budget'
        completed.result.resultType == 'complete'
        clocks == [null]
    }

    // A waitFor runs inline on the request clock, never in the background read worker: on a budgeted
    // transport it must end by the deadline and say so, for modern and legacy clients alike.
    private Map waitCall(String era, boolean gateway, String leaf, Map leafArgs) {
        settingsMap.useGateways = gateway
        String name = gateway ? 'hub_read_devices' : leaf
        Map args = gateway ? [tool: leaf, args: leafArgs] : leafArgs
        if (era == 'modern') return call(name, args)
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-11-25'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId, method: 'tools/call',
            params: [name: name, arguments: args]])
        script.handleMcpRequest()
        mcpDriver.parseResponseJson() as Map
    }

    private TestDevice waitFixture(Closure switchValue) {
        def device = Spy(TestDevice)
        device.id = 88
        device.label = 'Budgeted Switch'
        device.getCurrentStates() >> { [[name: 'switch', value: switchValue()]] }
        childDevicesList << device
        NativeInventoryFixture.register(hubGet, '88') { device }
        NativeInventoryFixture.registerDevicesPage(hubGet) { -> childDevicesList }
        device
    }

    def "a #era #route #leaf waitFor ends at the relay budget and says so"() {
        given:
        waitFixture { 'off' }
        long started = clock.get()

        when:
        def response = waitCall(era, route == 'gateway', leaf, leafArgs + [waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 15000]])
        def waited = mcpDriver.parseInner(response).waitFor

        then: 'answered in this request, inline: no background worker, no continuation'
        !response.result.containsKey('requestState')
        !response.result.isError
        runInMillisCalls.isEmpty()

        and: 'the wait stopped by the 6000 ms deadline and reports the cut'
        waited.converged == false
        waited.timedOut == true
        waited.budgetLimited == true
        waited.requestedTimeoutMs == 15000
        waited.timeoutMs <= 6000
        waited.budgetNote
        clock.get() - started <= 6000L

        where:
        [era, route, leaf, leafArgs] << [['modern', 'legacy'], ['gateway', 'flat'],
            [['hub_get_device', [deviceId: '88']], ['hub_list_devices', [deviceIds: ['88']]]]].combinations().collect { it[0..1] + it[2] }
    }

    def "a #era #leaf waitFor that converges inside the budget reports converged without budget fields"() {
        given: 'the switch turns on at its third read'
        int reads = 0
        waitFixture { ++reads >= 3 ? 'on' : 'off' }

        when:
        def response = waitCall(era, true, leaf, leafArgs + [waitFor: [attribute: 'switch', expectedValue: 'on', timeoutMs: 15000]])
        def waited = mcpDriver.parseInner(response).waitFor

        then:
        !response.result.containsKey('requestState')
        waited.converged == true
        (leaf == 'hub_get_device' ? waited.finalValue : waited.devices[0].finalValue) == 'on'
        !waited.containsKey('budgetLimited')
        runInMillisCalls.isEmpty()

        where:
        [era, leaf, leafArgs] << [['modern', 'legacy'],
            [['hub_get_device', [deviceId: '88']], ['hub_list_devices', [deviceIds: ['88']]]]].combinations().collect { [it[0]] + it[1] }
    }

    def "a #era waitFor whose stableForMs cannot settle in the budget is refused before any read"() {
        given:
        waitFixture { 'on' }

        when:
        def response = waitCall(era, true, 'hub_get_device', [deviceId: '88',
            waitFor: [attribute: 'switch', expectedValue: 'on', stableForMs: 7000, timeoutMs: 15000]])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('cannot settle')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
        runInMillisCalls.isEmpty()

        where:
        era << ['modern', 'legacy']
    }

    def "a modern hub_get_device read without waitFor still goes to the background snapshot"() {
        given:
        waitFixture { 'off' }

        when:
        def first = call('hub_read_devices', [tool: 'hub_get_device', args: [deviceId: '88']])

        then:
        first.result.resultType == 'input_required'
        first.result.requestState
        runInMillisCalls.size() == 1
    }
}
