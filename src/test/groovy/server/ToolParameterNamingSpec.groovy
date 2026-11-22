package server

import support.ToolSpecBase

/**
 * Tool parameters are camelCase, matching the hub's own MCP tools (ruleId, appId, deviceId).
 * Walks every tool's inputSchema, nested objects and array items included, plus the
 * per-operation `args` schemas that hub_set_rule and hub_set_native_app validate against,
 * since those keys sit inside a free-form `args` object the top-level schema cannot show.
 */
class ToolParameterNamingSpec extends ToolSpecBase {

    private static List<String> snakeKeys(String where, def schema) {
        List<String> found = []
        if (!(schema instanceof Map)) return found
        (schema.properties ?: [:]).each { key, sub ->
            String path = "${where}.${key}"
            if (key.toString().contains('_')) found << path
            found.addAll(snakeKeys(path, sub))
        }
        if (schema.items) found.addAll(snakeKeys("${where}[]", schema.items))
        if (schema.additionalProperties instanceof Map) found.addAll(snakeKeys("${where}{}", schema.additionalProperties))
        ['oneOf', 'anyOf', 'allOf'].each { kw -> (schema[kw] ?: []).each { found.addAll(snakeKeys(where, it)) } }
        found
    }

    def "no tool input schema has a snake_case property name"() {
        when:
        def offenders = script.getAllToolDefinitions().collectMany { tool -> snakeKeys(tool.name, tool.inputSchema) }

        then: 'rename to camelCase; there are no aliases, so update callers, guide, specs and e2e too'
        offenders == []
    }

    def "the args schemas behind hub_set_rule and hub_set_native_app have no snake_case keys"() {
        when:
        List<String> offenders = []
        ['read', 'create', 'update', 'form'].each { op ->
            offenders.addAll(snakeKeys("hub_set_rule(${op}).args", script._ndOperationSchema(op, 'hub_set_rule')))
        }
        ['create', 'update', 'form'].each { op ->
            offenders.addAll(snakeKeys("hub_set_native_app(${op}).args", script._naOperationSchema(op)))
        }

        then:
        offenders == []
    }

    def "the walker reports a nested snake_case key"() {
        expect: 'guards the guard: a walker that silently stopped recursing would pass everything'
        snakeKeys('t', [type: 'object', properties: [okKey: [type: 'object', properties: [bad_key: [type: 'string']]],
                                                     list: [type: 'array', items: [type: 'object', properties: [also_bad: [type: 'string']]]]]]) ==
            ['t.okKey.bad_key', 't.list[].also_bad']
    }
}
