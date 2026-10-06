package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * addRequiredExpression switches "Required Expression" (useST) on before it walks the conditions.
 * An add that fails with nothing committed switches it back off when it was the one to switch it
 * on, so a refused add never leaves the rule with an empty gate enabled.
 */
class AddRequiredExpressionUndoSpec extends ToolSpecBase {

    private List writes

    private void wire(List<String> useSTReads, List tokens, Map walkResult, Exception walkThrows = null) {
        writes = []
        def reads = new ArrayList(useSTReads)
        hubGet.register('/installedapp/configure/json/100') { params ->
            def v = reads.size() > 1 ? reads.remove(0) : reads[0]
            JsonOutput.toJson([app: [id: 100], configPage: [name: "mainPage", sections: []], settings: (v == null ? [:] : [useST: v])])
        }
        hubGet.register('/app/ruleBuilderJson/100') { params -> JsonOutput.toJson([eval: tokens ? ["0": tokens] : [:]]) }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String page, String key, Object value, List applied, String hint = null, List skipped = null, Map cache = null ->
            writes << [page, key, value]
        }
        script.metaClass._rmAddRequiredExpressionWalk = { Integer appId, Map spec ->
            if (walkThrows) throw walkThrows
            walkResult
        }
    }

    def "a failed add that switched the gate on switches it back off"() {
        given:
        wire([null, "true", ""], [], [success: false, error: "condition refused"])

        when:
        def out = script._rmAddRequiredExpression(100, [conditions: []])

        then:
        out.success == false
        writes == [["mainPage", "useST", false]]
        !out.containsKey('useSTLeftOn')
    }

    def "a gate that will not switch back off is reported, since the rule then runs ungated"() {
        given:
        wire([null, "true"], [], [success: false, error: "condition refused"])

        when:
        def out = script._rmAddRequiredExpression(100, [conditions: []])

        then:
        writes == [["mainPage", "useST", false]]
        out.useSTLeftOn == true
        out.error.contains("could not be switched back off")
    }

    def "a validation throw before any write leaves the gate as it found it"() {
        given:
        wire([null, null], [], null, new IllegalArgumentException("bad spec"))

        when:
        script._rmAddRequiredExpression(100, [conditions: []])

        then:
        thrown(IllegalArgumentException)
        writes.isEmpty()
    }

    def "the gate stays on when #label"() {
        given:
        wire(useST, tokens, [success: false, error: "x"])

        when:
        script._rmAddRequiredExpression(100, [conditions: []])

        then:
        writes.isEmpty()

        where:
        label                                   | useST            | tokens
        'it was already on before the add'      | ["true", "true"] | []
        'part of the expression was committed'  | [null, "true"]   | [1]
    }

    def "a successful add is left alone"() {
        given:
        wire([null, "true"], [1], [success: true])

        when:
        def out = script._rmAddRequiredExpression(100, [conditions: []])

        then:
        out.success == true
        writes.isEmpty()
    }
}
