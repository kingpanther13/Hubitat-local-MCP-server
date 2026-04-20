package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for getChildAppById() — hubitat-mcp-server.groovy line 218.
 *
 * One-liner: getChildApps()?.find { it.id.toString() == appId?.toString() }
 * Accepts either Integer or String ids; returns null on miss.
 */
class GetChildAppByIdSpec extends ToolSpecBase {

    def "finds child app by integer id"() {
        given:
        def app = new TestChildApp(id: 42L, label: 'A')
        childAppsList << app

        expect:
        script.getChildAppById(42)?.is(app)
    }

    def "finds child app by string id"() {
        given:
        def app = new TestChildApp(id: 42L, label: 'A')
        childAppsList << app

        expect:
        script.getChildAppById('42')?.is(app)
    }

    def "returns null when id is not found"() {
        given:
        childAppsList.clear()

        expect:
        script.getChildAppById('999') == null
    }

    def "returns null for null id without throwing"() {
        given:
        childAppsList << new TestChildApp(id: 42L, label: 'A')

        expect:
        script.getChildAppById(null) == null
    }

    def "returns first match when multiple apps share an id (degenerate state)"() {
        // The impl uses .find which returns the first match; documents
        // what happens if the hub state is degenerate.
        given:
        def first = new TestChildApp(id: 5L, label: 'First')
        def second = new TestChildApp(id: 5L, label: 'Second')
        childAppsList << first
        childAppsList << second

        expect:
        script.getChildAppById('5')?.is(first)
    }
}
