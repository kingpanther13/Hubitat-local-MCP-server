package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolGetHubLogs (hubitat-mcp-server.groovy line 5181).
 *
 * Covers: most-recent-first ordering (golden), Hub Admin Read gate, and
 * PR #64 regressions (appId numeric validation + deviceId/appId mutual
 * exclusion).
 */
class ToolGetHubLogsSpec extends ToolSpecBase {

    def "returns most-recent-first log entries (PR #64 ordering regression)"() {
        given: 'Hub Admin Read is enabled'
        settingsMap.enableHubAdminRead = true

        and: 'hub returns 3 log lines in chronological order (oldest first)'
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([
                'App 1\tinfo\tOldest message\t2026-04-19 10:00:00.000\ttype',
                'App 1\tinfo\tMiddle message\t2026-04-19 10:00:01.000\ttype',
                'App 1\tinfo\tNewest message\t2026-04-19 10:00:02.000\ttype'
            ])
        }

        when:
        def result = script.toolGetHubLogs([:])

        then:
        result.logs.size() == 3
        result.logs[0].message == 'Newest message'
        result.logs[1].message == 'Middle message'
        result.logs[2].message == 'Oldest message'
        result.count == 3
    }

    def "throws when Hub Admin Read is disabled"() {
        given:
        settingsMap.enableHubAdminRead = false

        when:
        script.toolGetHubLogs([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "rejects non-integer appId (PR #64 numeric-validation regression)"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([appId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('numeric')
    }

    def "rejects deviceId and appId together (PR #64 mutual-exclusion regression)"() {
        given:
        settingsMap.enableHubAdminRead = true

        when:
        script.toolGetHubLogs([deviceId: '1', appId: '2'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('mutually exclusive')
    }
}
