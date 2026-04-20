package support

import spock.lang.Specification

class RMUtilsMockSpec extends Specification {
    def "RMUtils mock records calls and returns stubbed rule list"() {
        given:
        def mock = new RMUtilsMock()
        mock.stubRuleList = [[id: 1, label: 'Test Rule']]
        mock.install()

        when:
        def rules = hubitat.helper.RMUtils.getRuleList('5.0')
        hubitat.helper.RMUtils.sendAction(1L, 'rule')

        then:
        rules == [[id: 1, label: 'Test Rule']]
        mock.calls.size() == 2
        mock.calls[0] == [method: 'getRuleList', version: '5.0']
        mock.calls[1] == [method: 'sendAction', ruleId: 1L, action: 'rule']

        cleanup:
        mock.uninstall()
    }
}
