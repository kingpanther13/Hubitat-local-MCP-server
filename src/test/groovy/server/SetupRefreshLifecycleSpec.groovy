package server

import spock.lang.Shared
import support.TestChildApp
import support.TestLocation
import support.ToolSpecBase

class SetupRefreshLifecycleSpec extends ToolSpecBase {
    @Shared private TestLocation sharedLocation = new TestLocation()
    @Shared private boolean failSubscribe
    @Shared private List subscriptionAttempts = []
    private long clock
    private int inventoryReads

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
        appExecutor.subscribe(_, _ as String, _ as String) >> { args ->
            subscriptionAttempts << args[1]
            if (failSubscribe) throw new RuntimeException('subscribe unavailable')
        }
    }

    def setup() {
        failSubscribe = false
        subscriptionAttempts.clear()
        clock = 1234567890000L
        NOW_OVERRIDE.set({ -> clock })
        setFresh(false)
        // Reset the retry deadline when present; old code has no backoff field.
        try {
            def field = script.getClass().getDeclaredField('SETUP_RETRY_AT')
            field.accessible = true
            field.set(null, 0L)
        } catch (NoSuchFieldException ignored) { }
        UNSUBSCRIBE_CALL_COUNT.set(0)
        stateMap.accessToken = 'fixture'
        stateMap.updateCheck = [checked: true]
        stateMap.setupVersion = '0.0.1'
        inventoryReads = 0
        script.metaClass.getAllGlobalVars = { -> inventoryReads++; [:] }
    }

    private void setFresh(boolean value) {
        def field = script.getClass().getDeclaredField('SETUP_CURRENT')
        field.accessible = true
        field.set(null, value)
    }

    private boolean fresh() {
        def field = script.getClass().getDeclaredField('SETUP_CURRENT')
        field.accessible = true
        field.get(null)
    }

    def "real initialization records completion on the first request and does not repeat"() {
        when:
        def first = mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        first.result.isError != true
        stateMap.setupVersion == script.currentVersion()
        fresh()
        UNSUBSCRIBE_CALL_COUNT.get() == 1
        inventoryReads == 2

        when:
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        UNSUBSCRIBE_CALL_COUNT.get() == 1
        inventoryReads == 2
    }

    def "failed variable inventory remains retryable without rebuilding setup on every request"() {
        given:
        script.metaClass.getAllGlobalVars = { ->
            inventoryReads++
            if (inventoryReads == 1) throw new RuntimeException('temporary inventory failure')
            [:]
        }

        when:
        def response = mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        response.result.isError != true
        stateMap.setupVersion != script.currentVersion()
        !fresh()
        UNSUBSCRIBE_CALL_COUNT.get() == 1

        when: 'ordinary requests remain usable during the retry delay'
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        UNSUBSCRIBE_CALL_COUNT.get() == 1

        when: 'a later request retries the actual initializer'
        clock += 60001L
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        fresh()
        stateMap.setupVersion == script.currentVersion()
        UNSUBSCRIBE_CALL_COUNT.get() == 2
    }

    def "failed subscriptions do not complete setup and are retried after backoff"() {
        given:
        failSubscribe = true
        script.metaClass.getAllGlobalVars = { -> [sample: [name: 'sample']] }

        when:
        script._refreshSetupAfterUpdate()

        then:
        !fresh()
        stateMap.setupVersion != script.currentVersion()

        when:
        failSubscribe = false
        clock += 60001L
        script._refreshSetupAfterUpdate()

        then:
        fresh()
        stateMap.setupVersion == script.currentVersion()
        subscriptionAttempts == ['variable:sample', 'variable:sample']
    }

    def "a lifecycle initialization failure invalidates an already-current marker"() {
        given:
        stateMap.setupVersion = script.currentVersion()
        setFresh(true)
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException('inventory unavailable') }

        when:
        script.initialize()

        then:
        !fresh()
        stateMap.setupVersion != script.currentVersion()
    }

    def "in-use refresh retains only successful operations so failed work is retried"() {
        given:
        atomicStateMap.inUseHubVars = ['old']
        script.metaClass.getAllGlobalVars = { -> [needed: [name: 'needed'], old: [name: 'old']] }
        def consumer = new TestChildApp(id: 11L, label: 'Needs variable')
        consumer.ruleData = [conditions: [[type: 'variable', name: 'needed']]]
        childAppsList << consumer
        def added = []
        def removed = []
        boolean fail = true
        script.metaClass.addInUseGlobalVar = { String name ->
            added << name
            if (fail) throw new RuntimeException('add unavailable')
            true
        }
        script.metaClass.removeInUseGlobalVar = { String name ->
            removed << name
            if (fail) throw new RuntimeException('remove unavailable')
            true
        }

        when:
        script._refreshHubVarInUseRegistrations()

        then:
        atomicStateMap.inUseHubVars == ['old']

        when:
        fail = false
        script._refreshHubVarInUseRegistrations()

        then:
        added == ['needed', 'needed']
        removed == ['old', 'old']
        atomicStateMap.inUseHubVars == ['needed']
    }

    def "empty inventory does not discard a failed stale-registration removal"() {
        given:
        atomicStateMap.inUseHubVars = ['old']
        script.metaClass.removeInUseGlobalVar = { String name -> throw new RuntimeException('remove unavailable') }

        when:
        script._refreshHubVarInUseRegistrations()

        then:
        atomicStateMap.inUseHubVars == ['old']
    }
}
