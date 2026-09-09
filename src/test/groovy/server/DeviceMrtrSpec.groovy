package server

import java.util.concurrent.atomic.AtomicLong
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
        'hub_update_device'         | [deviceId: '88', label: 'Changed']
    }

    def "slow #leaf reads continue without Write permission and each new call is fresh"() {
        given:
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        int reads = 0
        String label = 'Before'
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor ->
            reads++; clock.addAndGet(12000L)
            [id: '88', label: label, nextCursor: 'unchanged-cursor', preferences: [token: 'private-pref']]
        }
        script.metaClass.toolListDevices = { detailed, offset, limit, filter, labelFilter, capabilityFilter,
                format, fields, cursor, scope, roomFilter, onlyOn, changedSince, attributeNames ->
            reads++; clock.addAndGet(12000L); [devices: [[id: '88', label: label]]]
        }
        script.metaClass.toolListVirtualDevices = { args ->
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
        'hub_list_devices' | [filter: 'virtual']
    }

    def "device read cannot replay a cached profile after scope is revoked"() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 88, label: 'Private')]
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor ->
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
        denied.error != null || denied.result.isError == true
        !groovy.json.JsonOutput.toJson(denied).contains('private-device-profile')
    }

    def "a quickly completed device read needs one HTTP call but the next call is fresh"() {
        given:
        int reads = 0
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor ->
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
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor ->
            reads++; [id: '88']
        }
        RUN_IN_MILLIS_OVERRIDE.set({ List scheduled -> throw new IllegalStateException('scheduler unavailable') })

        when:
        def failed = call('hub_get_device', [deviceId: '88'])

        then:
        failed.error != null || failed.result.isError == true
        reads == 0
        script._activeWrites().isEmpty()
    }

    def "a lost device snapshot fails its continuation without starting another fetch"() {
        given:
        int reads = 0
        script.metaClass.toolGetDevice = { id, mode, sections, fields, cursor -> reads++; [id: '88'] }
        String token = call('hub_get_device', [deviceId: '88']).result.requestState
        assert token != null
        (scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map).clear()

        when:
        def failed = call('hub_get_device', [deviceId: '88'], token)
        finish(runInMillisCalls[0])

        then:
        failed.error.code == -32602
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
        failed.error.code == -32602
        failed.error.message.contains('No MCP-managed virtual device found')
        failed.error.message.contains('virtual_devices')
        replay.error == failed.error
        attempts == 1
        script.initDebugLogs().entries.any {
            it.level == 'error' && it.details?.tool == 'hub_manage_virtual_device' &&
                it.details.error == 'No MCP-managed virtual device found'
        }
    }
}
