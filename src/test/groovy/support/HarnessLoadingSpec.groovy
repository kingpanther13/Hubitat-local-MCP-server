package support

class HarnessLoadingSpec extends HarnessSpec {
    def "server app compiles and loads"() {
        expect:
        script != null
    }

    def "state and atomicState are writable Maps"() {
        when:
        script.state.foo = 'bar'
        script.atomicState.baz = 'qux'

        then:
        stateMap == [foo: 'bar']
        atomicStateMap == [baz: 'qux']
    }

    def "hubInternalGet is stubbable"() {
        given:
        hubGet.register('/test/path') { params -> [ok: true, echo: params] }

        when:
        def result = script.hubInternalGet('/test/path', [key: 'value'])

        then:
        result == [ok: true, echo: [key: 'value']]
        hubGet.calls[0] == [path: '/test/path', params: [key: 'value']]
    }
}
