package server

import support.ToolSpecBase

class RequiredExpressionMarkerSpec extends ToolSpecBase {

    def "restore distinguishes a committed required expression from an open condition editor"() {
        expect:
        script._rmIsCommittedRETell(names as Set, requireStopOnST) == expected

        where:
        names                                      | requireStopOnST || expected
        ["cancelST", "editST", "doneST"]           | false           || true
        ["cancelST", "editST", "cond"]             | false           || false
        ["cancelST", "editST", "rCapab_2"]         | false           || false
        ["doneST"]                                 | false           || false
        null                                       | false           || false
        ["cancelST", "editST"]                     | true            || false
        ["cancelST", "editST", "stopOnST"]         | true            || true
        ["cancelST", "editST", "stopOnST", "cond"] | true            || false
    }
}
