package server

import support.ToolSpecBase

/**
 * Saving new server code does not call updated(), so the first MCP request after a class load
 * compares state.setupVersion with currentVersion() and re-runs initialize() on a mismatch.
 */
class SetupRefreshSpec extends ToolSpecBase {

    private int initializeCalls

    def setup() {
        setSetupCurrent(false)
        initializeCalls = 0
        script.metaClass.initialize = { -> initializeCalls++; stateMap.setupVersion = script.currentVersion() }
    }

    private void setSetupCurrent(boolean value) {
        def f = script.getClass().getDeclaredField('SETUP_CURRENT')
        f.accessible = true
        f.set(null, value)
    }

    private boolean setupCurrent() {
        def f = script.getClass().getDeclaredField('SETUP_CURRENT')
        f.accessible = true
        return f.get(null)
    }

    def "a recorded marker for this version skips initialize and stops later checks"() {
        given:
        stateMap.setupVersion = script.currentVersion()

        when:
        script._refreshSetupAfterUpdate()

        then:
        initializeCalls == 0
        setupCurrent()
    }

    def "an older or missing marker runs initialize once, and later requests do not repeat it"() {
        given:
        stateMap.setupVersion = marker

        when:
        script._refreshSetupAfterUpdate()
        setSetupCurrent(false)   // a reload with the new marker recorded: the check reads it and stops
        script._refreshSetupAfterUpdate()
        script._refreshSetupAfterUpdate()

        then:
        initializeCalls == 1
        stateMap.setupVersion == script.currentVersion()
        setupCurrent()

        where:
        marker << ['0.0.1', null]
    }

    def "a failed refresh is not recorded and retries after backoff"() {
        given:
        stateMap.setupVersion = '0.0.1'
        long clock = 1234567890000L
        NOW_OVERRIDE.set({ -> clock })
        def attempts = 0
        script.metaClass.initialize = { ->
            attempts++
            if (attempts == 1) throw new RuntimeException('subscribe failed')
            stateMap.setupVersion = script.currentVersion()
        }

        when:
        script._refreshSetupAfterUpdate()

        then: 'the request is not failed, and the setup is not marked current'
        noExceptionThrown()
        !setupCurrent()
        stateMap.setupVersion == '0.0.1'

        when:
        script._refreshSetupAfterUpdate()

        then:
        attempts == 1

        when:
        clock += 60001L
        script._refreshSetupAfterUpdate()

        then:
        attempts == 2
        stateMap.setupVersion == script.currentVersion()
    }

    def "the first MCP request after an update runs the refresh"() {
        given:
        stateMap.setupVersion = '0.0.1'

        when:
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])
        mcpDriver.callTool('hub_get_tool_guide', [section: 'rooms'])

        then:
        initializeCalls == 1
        stateMap.setupVersion == script.currentVersion()
    }
}
