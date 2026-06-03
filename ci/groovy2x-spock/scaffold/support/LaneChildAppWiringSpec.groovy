package support

/**
 * Lane self-test (issue #230). Proves the joelwetzel `@Delegate`→AppExecutor
 * child-app routing this lane relies on actually flows through the AppExecutor
 * mock under Groovy 2.5.
 *
 * The root (eighty20results) harness intercepts getChildApps/addChildApp/
 * deleteChildApp by reflectively replacing private childAppFactory/
 * childAppAccessor closures; this lane stubs them on the mock instead, and the
 * whole corpus's correctness rests on biocomp routing those calls through its
 * `@Delegate`. Rather than leave that as an unverified comment, assert it
 * directly — if a future joelwetzel bump stops delegating one of these, this
 * spec fails loudly instead of letting tool specs pass against a no-op stub.
 */
class LaneChildAppWiringSpec extends HarnessSpec {

    def "getChildApps() routes through the AppExecutor mock to the fixture list"() {
        given:
        childAppsList << new TestChildApp(id: 42L, label: 'Probe')

        expect:
        script.getChildApps()*.id == [42L]
    }

    def "addChildApp(...) routes through the mock and returns the spec fixture"() {
        given:
        mockChildAppForCreate = new TestChildApp(id: 99L, label: 'Created')

        expect:
        script.addChildApp('ns', 'name', 'label').is(mockChildAppForCreate)
    }

    def "deleteChildApp(id) routes through the mock and removes the matching child"() {
        given:
        childAppsList << new TestChildApp(id: 7L, label: 'Doomed')
        childAppsList << new TestChildApp(id: 8L, label: 'Survivor')

        when:
        script.deleteChildApp(7L)

        then:
        childAppsList*.id == [8L]
    }
}
