package server

import support.ToolSpecBase

/**
 * hub_set_rule flat self-gateway. In flat mode (useGateways=false) hub_set_rule is
 * emitted as a thin {operation, appId, args, confirm} selector; the per-operation
 * argument schema is fetched in-band via an args-omitted/confirm-less PROBE, and a
 * confirm:true call EXECUTEs by re-keying the envelope into the canonical handler
 * shape. Gateway mode keeps the fat schema (untouched). These guards pin the
 * contract so a future edit can't silently break the re-keying, the probe/execute
 * discriminator, the no-drift operation enum, or the write-master gate.
 */
class SetRuleSelfGatewaySpec extends ToolSpecBase {

    def "flat-mode operation enum is single-sourced and covers every create + edit op (drift guard)"() {
        when:
        def flatEnum = script._setRuleFlatTool().inputSchema.properties.operation.enum

        then: 'the flat enum IS _setRuleOperations() -- one source of truth'
        flatEnum == script._setRuleOperations()

        and: 'and _setRuleOperations() offers exactly what the handler accepts: every create-honored + edit-only op + create/buttonRule/guide/discover'
        script._setRuleOperations().containsAll(script._setRuleCreateHonored())
        script._setRuleOperations().containsAll(script._setRuleEditOnly())
        script._setRuleOperations().containsAll(['create', 'buttonRule', 'guide', 'discover'])
        // no extra op is advertised that the handler doesn't know about
        script._setRuleOperations() as Set == (['create', 'buttonRule', 'guide', 'discover'] + script._setRuleCreateHonored() + script._setRuleEditOnly()) as Set
    }

    def "flat-mode hub_set_rule is the thin selector, gateway-mode keeps the fat schema"() {
        given:
        settingsMap.enableCustomRuleEngine = true

        when: 'flat mode'
        settingsMap.useGateways = false
        def flat = script.getToolDefinitions().find { it.name == 'hub_set_rule' }

        then: 'thin {operation,appId,args,confirm} selector'
        flat.inputSchema.properties.keySet() == ['operation', 'appId', 'args', 'confirm'] as Set

        when: 'gateway mode -- hub_set_rule is a sub-tool of hub_manage_rule_machine, disclosed fat'
        settingsMap.useGateways = true
        def disclosed = script.handleGateway('hub_manage_rule_machine', null, [:]).tools.find { it.name == 'hub_set_rule' }

        then: 'the fat schema still carries the real authoring params (untouched in gateway mode)'
        disclosed.inputSchema.properties.containsKey('addAction')
        disclosed.inputSchema.properties.containsKey('addTrigger')
    }

    def "a confirm-less call is a PROBE that returns the op schema and mutates nothing"() {
        expect:
        ['addAction', 'addTrigger', 'addRequiredExpression', 'create', 'clearActions'].each { op ->
            def r = script.toolSetRule([operation: op])
            assert r.note?.toString()?.contains('no rule was changed')
            assert r.operation == op
            assert r.argsSchema != null
        }
    }

    def "confirm:false is still a probe (the discriminator is confirm==true, not args-emptiness)"() {
        when:
        def r = script.toolSetRule([operation: 'addAction', appId: 5, args: [capability: 'switch'], confirm: false])

        then: 'no confirm -> schema returned, no mutation, even with a full args payload'
        r.note?.toString()?.contains('no rule was changed')
        r.argsSchema != null
    }

    def "execute re-keys the envelope into the canonical handler shape"() {
        expect:
        // Map-spec edit op -> args is the bare inner spec under the op key
        script._setRuleFromEnvelope([operation: 'addAction', appId: 5, args: [capability: 'switch', action: 'on', deviceIds: [3]], confirm: true]).args ==
            [appId: 5, confirm: true, addAction: [capability: 'switch', action: 'on', deviceIds: [3]]]

        // boolean op (clearActions) -> legacy.clearActions = true, presence == intent, takes no payload
        script._setRuleFromEnvelope([operation: 'clearActions', appId: 5, confirm: true]).args ==
            [appId: 5, confirm: true, clearActions: true]

        // string op (button) -> bare string passes straight through
        script._setRuleFromEnvelope([operation: 'button', appId: 5, args: 'updateRule', confirm: true]).args ==
            [appId: 5, confirm: true, button: 'updateRule']

        // tolerant unwrap: an accidental {op: value} wrap is unwrapped to the bare value
        script._setRuleFromEnvelope([operation: 'removeAction', appId: 5, args: [index: 2], confirm: true]).args ==
            [appId: 5, confirm: true, removeAction: [index: 2]]
        script._setRuleFromEnvelope([operation: 'removeAction', appId: 5, args: [removeAction: [index: 2]], confirm: true]).args ==
            [appId: 5, confirm: true, removeAction: [index: 2]]
    }

    def "list ops accept a bare array, unwrap an accidental single-key wrap, and reject a non-array"() {
        expect: 'a bare array passes straight through for every list-shaped op'
        ['addActions', 'addTriggers', 'replaceActions', 'patches'].every { op ->
            script._setRuleFromEnvelope([operation: op, appId: 5, args: [[capability: 'switch']], confirm: true]).args[op] == [[capability: 'switch']]
        }

        and: 'an accidental single-key wrap of the array is unwrapped to the bare array -- whether the key is the op name or any other (e.g. {actions:[...]})'
        script._setRuleFromEnvelope([operation: 'addActions', appId: 5, args: [actions: [[capability: 'switch']]], confirm: true]).args.addActions == [[capability: 'switch']]
        script._setRuleFromEnvelope([operation: 'addTriggers', appId: 5, args: [addTriggers: [[capability: 'Switch']]], confirm: true]).args.addTriggers == [[capability: 'Switch']]

        when: 'a list op gets a non-array object, it is rejected with a targeted bare-array error (not the generic "nothing provided")'
        script._setRuleFromEnvelope([operation: 'addActions', appId: 5, args: [capability: 'switch'], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('bare array')
        e.message.contains('addActions')
    }

    def "patches is NOT permissively unwrapped -- only a bare array or {patches:[...]}, never {otherKey:[...]}"() {
        expect: 'a bare array and the op-name wrap {patches:[...]} both pass straight to the handler'
        script._setRuleFromEnvelope([operation: 'patches', appId: 5, args: [[addAction: [capability: 'switch']]], confirm: true]).args.patches == [[addAction: [capability: 'switch']]]
        script._setRuleFromEnvelope([operation: 'patches', appId: 5, args: [patches: [[addAction: [capability: 'switch']]]], confirm: true]).args.patches == [[addAction: [capability: 'switch']]]

        when: 'a non-{patches:...} single-key map (e.g. {addActions:[...]}) is a malformed patches payload, NOT a wrapper -> targeted bare-array error, not a silent unwrap into the inner action list'
        script._setRuleFromEnvelope([operation: 'patches', appId: 5, args: [addActions: [[capability: 'switch']]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('bare array')
        e.message.contains('patches')
    }

    def "the flat-mode args schema admits array + boolean payloads (list ops / clearActions), not only object"() {
        given:
        settingsMap.useGateways = false

        when:
        def flat = script.getToolDefinitions().find { it.name == 'hub_set_rule' }
        def argsType = flat.inputSchema.properties.args.type

        then: 'a union type so the documented bare-array (and clearActions=true) shapes are schema-valid for strict clients'
        argsType instanceof List
        ('array' in argsType) && ('object' in argsType) && ('boolean' in argsType)
    }

    def "create execute lifts name + bundle from args and omits appId"() {
        when:
        def r = script._setRuleFromEnvelope([operation: 'create', args: [name: 'My Rule', addTriggers: [[capability: 'Switch']], addActions: [[capability: 'switch']]], confirm: true])

        then:
        !r.args.containsKey('appId')
        r.args.name == 'My Rule'
        r.args.confirm == true
        r.args.addTriggers == [[capability: 'Switch']]
        r.args.addActions == [[capability: 'switch']]
    }

    def "guide and discover map to the existing meta paths"() {
        expect:
        script._setRuleFromEnvelope([operation: 'guide']).args == [guide: true]
        script._setRuleFromEnvelope([operation: 'discover']).args == [addAction: [discover: true]]
        script._setRuleFromEnvelope([operation: 'discover', args: [kind: 'trigger']]).args == [addTrigger: [discover: true]]
    }

    def "an unknown operation is rejected with the valid list"() {
        when:
        script._setRuleFromEnvelope([operation: 'frobnicate', confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('unknown operation')
    }

    def "_isSetRuleSchemaOnlyCall: probe/guide/discover are read-only; a confirmed write is not"() {
        expect:
        script._isSetRuleSchemaOnlyCall([operation: 'addAction'])                                   // probe
        script._isSetRuleSchemaOnlyCall([operation: 'addAction', appId: 5, args: [capability: 'switch']]) // no confirm => probe
        script._isSetRuleSchemaOnlyCall([operation: 'guide'])
        script._isSetRuleSchemaOnlyCall([operation: 'discover', args: [kind: 'trigger']])
        script._isSetRuleSchemaOnlyCall([guide: true])                                              // legacy form
        !script._isSetRuleSchemaOnlyCall([operation: 'addAction', appId: 5, args: [capability: 'switch'], confirm: true]) // real write
        !script._isSetRuleSchemaOnlyCall([operation: 'clearActions', appId: 5, confirm: true])      // real write (no payload)
    }

    def "write master OFF: a schema probe still returns, but a confirmed write is blocked"() {
        given:
        settingsMap.enableWrite = false

        when: 'probe (no confirm) reaches the handler and returns schema -- no mutation, allowed read-only'
        def probe = script.executeTool('hub_set_rule', [operation: 'addAction'])

        then:
        probe.note?.toString()?.contains('no rule was changed')

        when: 'a confirmed write is blocked at the master gate before the handler runs'
        script.executeTool('hub_set_rule', [operation: 'addAction', appId: 1, args: [capability: 'switch', action: 'on', deviceIds: [1]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Write tools are disabled')
    }

    def "dispatch-envelope: executeTool routes the flat envelope through to the canonical handler"() {
        given: 'stub the downstream so we can capture the re-keyed args without the full wizard mock'
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true] }
        script.metaClass._createNativeAppShell = { Map a -> captured.create = a; [success: true] }

        when: 'an edit envelope'
        script.executeTool('hub_set_rule', [operation: 'addAction', appId: 5, args: [capability: 'switch', action: 'on', deviceIds: [3]], confirm: true])

        then: 'reaches _applyNativeAppEdit with the legacy-shaped args'
        captured.edit == [appId: 5, confirm: true, addAction: [capability: 'switch', action: 'on', deviceIds: [3]]]

        when: 'a clearActions envelope (boolean op, no payload)'
        captured.clear()
        script.executeTool('hub_set_rule', [operation: 'clearActions', appId: 5, confirm: true])

        then:
        captured.edit == [appId: 5, confirm: true, clearActions: true]

        when: 'a create envelope -- reaches _createNativeAppShell, no appId, name + bundle lifted from args'
        captured.clear()
        script.executeTool('hub_set_rule', [operation: 'create', args: [name: 'My Rule', addTriggers: [[capability: 'Switch']]], confirm: true])

        then:
        captured.create != null
        captured.create.appType == 'rule_machine'
        captured.create.name == 'My Rule'
        captured.create.triggers == [[capability: 'Switch']]
    }

    def "dispatch-envelope: a LEGACY (no-operation) call still routes through unchanged"() {
        given:
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true] }

        when: 'a cached client that never learned the self-gateway shape'
        script.executeTool('hub_set_rule', [appId: 5, addAction: [capability: 'switch'], confirm: true])

        then: 'operation absent => no normalizer rewrite; passes straight through'
        captured.edit == [appId: 5, addAction: [capability: 'switch'], confirm: true]
    }

    def "probe schema is sourced from the fat def (no drift), not a hand-maintained copy"() {
        given:
        def fat = script.getAllToolDefinitions().find { it.name == 'hub_set_rule' }.inputSchema.properties

        expect: 'a single-op probe returns the bare fat property for that op'
        script.toolSetRule([operation: 'addAction']).argsSchema == fat.addAction
        script.toolSetRule([operation: 'walkStep']).argsSchema == fat.walkStep

        and: 'create probe returns the name + create-honored bundle slice from the fat def'
        def createSchema = script.toolSetRule([operation: 'create']).argsSchema
        createSchema.keySet() == (['name'] + script._setRuleCreateHonored()) as Set
        createSchema.addAction == fat.addAction
    }

    def "write-master OFF: a stray guide flag cannot mask an executing envelope (regression)"() {
        given:
        settingsMap.enableWrite = false
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true] }

        when: 'an execute envelope smuggling a top-level guide:true -- must NOT read as schema-only'
        script.executeTool('hub_set_rule', [guide: true, operation: 'addAction', appId: 5, args: [capability: 'switch'], confirm: true])

        then: 'blocked by the Write master before the handler runs; nothing mutated'
        def e = thrown(IllegalArgumentException)
        e.message.contains('Write tools are disabled')
        captured.isEmpty()
    }

    def "_isSetRuleSchemaOnlyCall: an executing envelope is never schema-only, even with a stray meta flag"() {
        expect: 'real writes are write-gated'
        !script._isSetRuleSchemaOnlyCall([operation: 'addAction', appId: 5, args: [capability: 'switch'], confirm: true])
        !script._isSetRuleSchemaOnlyCall([guide: true, operation: 'addAction', confirm: true])
        !script._isSetRuleSchemaOnlyCall([addAction: [discover: true], operation: 'addAction', confirm: true])

        and: 'genuine schema-only calls still classify as read-only'
        script._isSetRuleSchemaOnlyCall([operation: 'addAction'])
        script._isSetRuleSchemaOnlyCall([operation: 'guide', confirm: true])
        script._isSetRuleSchemaOnlyCall([guide: true])
    }

    def "a malformed JSON-string args is rejected with a clear error, not swallowed"() {
        when:
        script._setRuleFromEnvelope([operation: 'addAction', appId: 5, args: '{not valid json', confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('not valid JSON')
    }

    def "a valid JSON-string args is parsed transparently"() {
        expect:
        script._setRuleFromEnvelope([operation: 'addAction', appId: 5, args: '{"capability":"switch"}', confirm: true]).args ==
            [appId: 5, confirm: true, addAction: [capability: 'switch']]
    }

    def "buttonRule probe usage omits appId (controllerId rides in args, not appId)"() {
        when:
        def r = script.toolSetRule([operation: 'buttonRule'])

        then: 'buttonRule creates under a controller -- usage must NOT tell the caller to pass appId=<ruleId>'
        r.usage.contains('omit appId')
        r.usage.contains('controllerId')
        !r.usage.contains('appId=<ruleId>')
    }

    def "the settings op is NOT unwrapped (a single-key {settings: ...} payload is preserved)"() {
        expect: 'every other single edit op tolerantly unwraps {op: value}, but settings must not -- its payload is a raw {inputName: value} map that may legitimately have a key named settings'
        script._setRuleFromEnvelope([operation: 'settings', appId: 5, args: [settings: 'x'], confirm: true]).args ==
            [appId: 5, confirm: true, settings: [settings: 'x']]
    }

    def "discover rejects an unknown kind instead of silently defaulting to action"() {
        when:
        script._setRuleFromEnvelope([operation: 'discover', args: [kind: 'triggerz']])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("must be 'trigger' or 'action'")
    }

    def "envelope probe via the gateway bypasses the required-param pre-check and returns schema"() {
        when: 'a flat-style envelope probe arrives through the gateway (no confirm)'
        def r = script.handleGateway('hub_manage_rule_machine', 'hub_set_rule', [operation: 'addAction'])

        then: 'isGatedMetaCall lets it past the required:[confirm] pre-check; the probe schema returns, nothing mutated'
        r.note?.toString()?.contains('no rule was changed')
        r.argsSchema != null
    }

    def "create rejects a stray appId (it would otherwise route to an EDIT of that rule)"() {
        when:
        script._setRuleFromEnvelope([operation: 'create', appId: 5, args: [name: 'X', addActions: [[capability: 'log']]], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('must OMIT appId')
    }

    def "create rejects an edit-only key in args instead of silently dropping it"() {
        when:
        script._setRuleFromEnvelope([operation: 'create', args: [name: 'X', replaceActions: []], confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('require an existing rule')
    }
}
