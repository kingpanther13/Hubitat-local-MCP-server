package server

import support.ToolSpecBase

/**
 * Spec for the manage_hub_variables gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolListVariables    -> list_variables
 * - toolGetVariable      -> get_variable
 * - toolSetVariable      -> set_variable
 * - toolDeleteHubVariable -> delete_variable
 *
 * Migrated to the modern Hub Variable API (issue #92): getAllGlobalVars /
 * getGlobalVar / setGlobalVar see ALL hub variables, not just the
 * connector-exposed subset that getAllGlobalConnectorVariables saw. The
 * modern API also returns richer metadata: each entry is shaped like
 * [name, type, value, deviceId, attribute] so callers can tell whether a
 * connector exists (deviceId/attribute populated) and what type the var
 * is (Number/Decimal/String/Boolean/DateTime).
 *
 * These APIs are dynamic — not on AppExecutor — so they're stubbed
 * per-test via script.metaClass in given: blocks. See docs/testing.md
 * "Which interception point to use" for the general pattern.
 *
 * delete_variable is gated by requireHubAdminWrite (3-layer: setting flag,
 * args.confirm, 24h backup window). Helper enableHubAdminWrite() seeds those.
 */
class ToolHubVariablesSpec extends ToolSpecBase {

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- toolListVariables --------

    def "list_variables returns both hub and rule-engine variables with correct total"() {
        given: 'hub exposes two variables — one connector-backed, one not'
        script.metaClass.getAllGlobalVars = { ->
            [
                'temp_setpoint': [name: 'temp_setpoint', type: 'Number',  value: 72,    deviceId: 305, attribute: 'variable'],
                'vacation_mode': [name: 'vacation_mode', type: 'Boolean', value: false, deviceId: null, attribute: null]
            ]
        }

        and: 'the rule engine has one variable stored in state'
        stateMap.ruleVariables = [last_motion: '2026-04-21T10:00:00']

        when:
        def result = script.toolListVariables()

        then: 'hub variables come back with the modern shape including connector linkage'
        result.hubVariables.size() == 2
        def temp = result.hubVariables.find { it.name == 'temp_setpoint' }
        temp.value == 72
        temp.type == 'Number'
        temp.source == 'hub'
        temp.deviceId == 305
        temp.attribute == 'variable'
        def vac = result.hubVariables.find { it.name == 'vacation_mode' }
        vac.deviceId == null
        vac.attribute == null

        and: 'rule-engine variables come back unchanged'
        result.ruleVariables.size() == 1
        result.ruleVariables[0].name == 'last_motion'
        result.ruleVariables[0].source == 'rule_engine'
        result.total == 3
    }

    def "list_variables falls back to rule variables when the hub API throws"() {
        given:
        script.metaClass.getAllGlobalVars = { ->
            throw new RuntimeException('Hub variable API not available')
        }
        stateMap.ruleVariables = [counter: 5]

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.ruleVariables.size() == 1
        result.total == 1
    }

    def "list_variables returns empty collections when no variables are defined"() {
        given:
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.ruleVariables == []
        result.total == 0
    }

    // -------- toolGetVariable --------

    def "get_variable returns the modern shape with type and connector linkage"() {
        given:
        script.metaClass.getGlobalVar = { String name ->
            if (name != 'outdoor_temp') return null
            [name: 'outdoor_temp', type: 'Decimal', value: 68.4, deviceId: 312, attribute: 'variable']
        }

        when:
        def result = script.toolGetVariable('outdoor_temp')

        then:
        result.name == 'outdoor_temp'
        result.value == 68.4
        result.type == 'Decimal'
        result.deviceId == 312
        result.attribute == 'variable'
        result.source == 'hub'
    }

    def "get_variable returns hub var without connector linkage when no connector exists"() {
        given: 'a Boolean var without a connector — deviceId/attribute null'
        script.metaClass.getGlobalVar = { String name ->
            [name: 'vacation_mode', type: 'Boolean', value: true, deviceId: null, attribute: null]
        }

        when:
        def result = script.toolGetVariable('vacation_mode')

        then:
        result.name == 'vacation_mode'
        result.value == true
        result.type == 'Boolean'
        result.deviceId == null
        result.attribute == null
        result.source == 'hub'
    }

    def "get_variable falls back to rule-engine value when hub API throws"() {
        given:
        script.metaClass.getGlobalVar = { String name ->
            throw new RuntimeException('Not supported')
        }
        stateMap.ruleVariables = [porch_light_pref: 'bright']

        when:
        def result = script.toolGetVariable('porch_light_pref')

        then:
        result.name == 'porch_light_pref'
        result.value == 'bright'
        result.source == 'rule_engine'
    }

    def "get_variable throws when variable exists in neither source"() {
        given:
        script.metaClass.getGlobalVar = { String name -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolGetVariable('nonexistent')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Variable not found')
        ex.message.contains('nonexistent')
    }

    // -------- toolSetVariable --------

    def "set_variable writes via setGlobalVar and reports source 'hub' on success"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def result = script.toolSetVariable('vacation_mode', true)

        then:
        captured == [vacation_mode: true]
        result.success == true
        result.name == 'vacation_mode'
        result.value == true
        result.source == 'hub'
    }

    def "set_variable falls back to rule engine when setGlobalVar returns false (var not found)"() {
        given: 'setGlobalVar returns false when the hub var does not exist'
        script.metaClass.setGlobalVar = { String name, Object value -> false }

        when:
        def result = script.toolSetVariable('new_rule_var', 42)

        then: 'rule-engine state was updated'
        stateMap.ruleVariables == [new_rule_var: 42]

        and: 'result reports rule_engine source'
        result.success == true
        result.source == 'rule_engine'
        result.value == 42
    }

    def "set_variable falls back to rule engine when setGlobalVar throws"() {
        given:
        script.metaClass.setGlobalVar = { String name, Object value ->
            throw new RuntimeException('Hub variable API unavailable')
        }

        when:
        def result = script.toolSetVariable('weather_state', 'sunny')

        then:
        stateMap.ruleVariables == [weather_state: 'sunny']
        result.success == true
        result.value == 'sunny'
        result.source == 'rule_engine'
    }

    def "set_variable passes string values through to setGlobalVar unchanged"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def result = script.toolSetVariable('weather_state', 'sunny')

        then:
        captured == [weather_state: 'sunny']
        result.value == 'sunny'
        result.source == 'hub'
    }

    // -------- toolDeleteHubVariable --------

    def "delete_variable removes an existing rule_engine variable and reports the previous value"() {
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [scratch_var: 'to-be-removed', keep_me: 42]

        when:
        def result = script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        result.success == true
        result.name == 'scratch_var'
        result.deleted == true
        result.source == 'rule_engine'
        result.previousValue == 'to-be-removed'

        and: 'only the targeted variable is gone — siblings preserved'
        stateMap.ruleVariables == [keep_me: 42]
    }

    def "delete_variable throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var'])

        then:
        thrown(IllegalArgumentException)
        // Variable should NOT be deleted when the gate refuses
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when no recent backup exists (Hub Admin Write 24h gate)"() {
        given: 'enableHubAdminWrite is true but no lastBackupTimestamp seeded'
        settingsMap.enableHubAdminWrite = true
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        thrown(IllegalArgumentException)
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when enableHubAdminWrite setting is off"() {
        given:
        // No settingsMap.enableHubAdminWrite seed — gate refuses on the first layer
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        thrown(IllegalArgumentException)
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when name is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteHubVariable([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('name is required')
    }

    def "delete_variable throws when variable exists in neither hub nor rule_engine namespace"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolDeleteHubVariable([name: 'nonexistent', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'nonexistent'")
        ex.message.contains('hub-variables namespace')
        ex.message.contains('rule_engine namespace')
    }

    def "delete_variable throws when ruleVariables map is null and var is not a hub var"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        // stateMap.ruleVariables not seeded at all — null check path

        when:
        script.toolDeleteHubVariable([name: 'never_set', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'never_set'")
    }

    def "delete_variable reassigns the top-level state.ruleVariables map (Hubitat persistence quirk)"() {
        // Hubitat's state Map serialization only catches mutations when the top-level
        // key is reassigned — a bare .remove() on the nested Map silently fails to
        // persist across hub reboot / app restart. Asserts that toolDeleteHubVariable
        // emits a NEW Map object on stateMap.ruleVariables (not just mutates in place).
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [target_var: 'will-go', sibling: 'stays']
        def originalMapRef = stateMap.ruleVariables

        when:
        script.toolDeleteHubVariable([name: 'target_var', confirm: true])

        then: 'sibling is preserved'
        stateMap.ruleVariables == [sibling: 'stays']

        and: 'state.ruleVariables points to a NEW map instance — the read-modify-write pattern Hubitat needs to persist'
        !stateMap.ruleVariables.is(originalMapRef)
    }

    // -------- Reference-scan safety (delete_variable refuses to silently break consumers) --------

    def "delete_variable refuses when a child rule app references the variable (no force)"() {
        // A child rule's serialized triggers/conditions/actions contains the variable
        // name. Without force=true, deletion would null-out the consumer's lookup and
        // silently break the rule. The tool must surface the breakage and require opt-in.
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']

        and: 'a child rule that references shared_var in a condition'
        def consumer = new support.TestChildApp(id: 99L, label: 'Rule Using Shared Var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var', operator: '=', value: 'on']],
            actions: []
        ]
        childAppsList << consumer

        when:
        script.toolDeleteHubVariable([name: 'shared_var', confirm: true])

        then: 'refused with consumer details so the caller knows what would break'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'shared_var'")
        ex.message.contains('1 rule(s)')
        ex.message.contains('Rule Using Shared Var')
        ex.message.contains('id=99')
        ex.message.contains('force=true')

        and: 'the variable is still present — refusal must not partially mutate state'
        stateMap.ruleVariables == [shared_var: 'in-use']
    }

    def "delete_variable proceeds with force=true and reports brokenConsumers"() {
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']
        def consumer = new support.TestChildApp(id: 42L, label: 'Acknowledged Breakage')
        consumer.ruleData = [
            triggers: [[type: 'variable_change', variable: 'shared_var']],
            conditions: [],
            actions: []
        ]
        childAppsList << consumer

        when:
        def result = script.toolDeleteHubVariable([name: 'shared_var', confirm: true, force: true])

        then: 'deletion proceeds'
        result.success == true
        result.deleted == true
        stateMap.ruleVariables == [:]

        and: 'response surfaces the consumers that are now broken'
        result.brokenConsumers?.size() == 1
        result.brokenConsumers[0].id == 42L
        result.brokenConsumers[0].label == 'Acknowledged Breakage'
    }

    def "delete_variable proceeds normally when no child rules reference the variable"() {
        given: 'a child rule that does NOT mention the variable being deleted'
        enableHubAdminWrite()
        stateMap.ruleVariables = [orphan_var: 'safe-to-delete']
        def unrelated = new support.TestChildApp(id: 7L, label: 'Unrelated Rule')
        unrelated.ruleData = [
            triggers: [[type: 'device_event', deviceId: '1', attribute: 'switch']],
            conditions: [],
            actions: []
        ]
        childAppsList << unrelated

        when:
        def result = script.toolDeleteHubVariable([name: 'orphan_var', confirm: true])

        then: 'no force needed, no consumers reported'
        result.success == true
        result.deleted == true
        result.brokenConsumers == null
    }

    // -------- toolCreateVariable validation (issue #92) --------

    def "create_variable rejects forbidden characters in name"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateVariable([name: 'has[brackets]', type: 'String', value: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('forbidden character')
    }

    def "create_variable rejects unknown type"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateVariable([name: 'goodName', type: 'NotAType', value: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('NotAType')
        ex.message.contains('Number, Decimal, String, Boolean, DateTime')
    }

    def "create_variable rejects null/missing initial value"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolCreateVariable([name: 'goodName', type: 'String', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Initial value is required')
    }

    def "create_variable refuses to overwrite an existing hub variable"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n ->
            n == 'taken' ? [name: 'taken', type: 'String', value: 'already here', deviceId: null, attribute: null] : null
        }

        when:
        script.toolCreateVariable([name: 'taken', type: 'String', value: 'new', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("already exists")
        ex.message.contains('set_variable')
    }

    def "create_variable requires confirm"() {
        when:
        script.toolCreateVariable([name: 'goodName', type: 'String', value: 'x'])

        then:
        thrown(IllegalArgumentException)
    }

    // -------- toolCreateConnector / toolRemoveConnector validation --------

    def "create_connector throws when the variable does not exist"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n -> null }

        when:
        script.toolCreateConnector([name: 'missing', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('does not exist')
    }

    def "create_connector is a no-op when a connector already exists"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n ->
            [name: 'has_conn', type: 'Boolean', value: true, deviceId: 305, attribute: 'switch']
        }

        when:
        def result = script.toolCreateConnector([name: 'has_conn', confirm: true])

        then:
        result.success == true
        result.alreadyExists == true
        result.deviceId == 305
    }

    def "remove_connector throws when the variable does not exist"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n -> null }

        when:
        script.toolRemoveConnector([name: 'missing', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('does not exist')
    }

    def "remove_connector is a no-op when no connector exists"() {
        given:
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n ->
            [name: 'no_conn', type: 'String', value: 'x', deviceId: null, attribute: null]
        }

        when:
        def result = script.toolRemoveConnector([name: 'no_conn', confirm: true])

        then:
        result.success == true
        result.alreadyRemoved == true
    }

    // -------- Wizard-sequencing coverage (issue #92) --------
    //
    // The create/delete/connector tools drive the system Hub Variables app's UI
    // by chaining `_rmClickAppButton` and `_rmWriteSettingOnPage` calls. Both
    // are app-defined private methods (class 1 on the dispatch cheat sheet —
    // not declared on AppExecutor / BaseExecutor / HubitatAppScript), so per-
    // instance `script.metaClass` overrides win the dispatch and we can record
    // the call args without hitting the network. `_findHubVariablesAppId` is
    // also stubbed so we don't need to fake the /hub2/appsList tree.
    //
    // `getGlobalVar` is dynamic (also class 1) and we use a counter so the
    // *first* call in each tool (the pre-flight existence check) sees one
    // state of the world, and the *post-wizard verification* call sees the
    // post-write state — this is what the production code expects when the
    // wizard auto-commits on the final setting write / button click.

    def "create_variable wizard sequence: one button click then three settings in order"() {
        given:
        enableHubAdminWrite()

        and: 'getGlobalVar: null pre-flight, populated after wizard commits'
        def callCount = 0
        script.metaClass.getGlobalVar = { String n ->
            callCount++
            return callCount == 1 ? null : [name: 'newVar', type: 'String', value: 'hi', deviceId: null, attribute: null]
        }

        and: 'wizard primitives recorded instead of hitting the hub'
        def buttonClicks = []
        script.metaClass._rmClickAppButton = { Integer appId, String btnName, String stateAttr, String pageName ->
            buttonClicks << [appId: appId, btn: btnName, stateAttr: stateAttr, pageName: pageName]
            return [status: 200]
        }
        def settingWrites = []
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null ->
            settingWrites << [appId: appId, pageName: pageName, key: key, value: value, typeHint: typeHint]
            if (applied != null) applied << key
        }
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        // backupItemSource is called as a wizard-priming side effect on this firmware (see
        // toolDeleteHubVariable / toolCreateConnector comments in hubitat-mcp-server.groovy).
        // Stub to no-op so it doesn't try to fetch /app/ajax/code in tests.
        script.metaClass.backupItemSource = { String type, String id -> [:] }

        when:
        def result = script.toolCreateVariable([name: 'newVar', type: 'String', value: 'hi', confirm: true])

        then: 'exactly one button click — opens the create form on the hubVar page'
        buttonClicks.size() == 1
        buttonClicks[0] == [appId: 1424, btn: 'moreVar', stateAttr: 'moreVar', pageName: 'hubVar']

        and: 'three setting writes in order: name, type, value'
        settingWrites.size() == 3
        settingWrites[0] == [appId: 1424, pageName: 'hubVar', key: 'hbVar',    value: 'newVar', typeHint: 'text']
        settingWrites[1] == [appId: 1424, pageName: 'hubVar', key: 'varType',  value: 'String', typeHint: 'enum']
        settingWrites[2] == [appId: 1424, pageName: 'hubVar', key: 'varValue', value: 'hi',     typeHint: 'textarea']

        and: 'tool reports success with hub source'
        result.success == true
        result.source == 'hub'
        result.name == 'newVar'
        result.type == 'String'
        result.value == 'hi'
    }

    def "create_variable post-wizard verification fails when getGlobalVar still returns null"() {
        given:
        enableHubAdminWrite()

        and: 'getGlobalVar returns null both pre-flight AND after the wizard supposedly committed'
        script.metaClass.getGlobalVar = { String n -> null }

        and: 'wizard primitives recorded but no real effect'
        script.metaClass._rmClickAppButton = { Integer appId, String btnName, String stateAttr, String pageName -> [status: 200] }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null ->
            if (applied != null) applied << key
        }
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        script.metaClass.backupItemSource = { String type, String id -> [:] }

        when:
        script.toolCreateVariable([name: 'ghostVar', type: 'String', value: 'x', confirm: true])

        then: 'IllegalStateException — wizard ran but the var is not visible'
        def ex = thrown(IllegalStateException)
        ex.message.contains('wizard completed but')
        ex.message.contains('ghostVar')
    }

    def "delete_variable hub-namespace wizard sequence: deleteGV then delConfirm"() {
        given:
        enableHubAdminWrite()

        and: 'getGlobalVar: returns hub var pre-flight, null after delete commits'
        def callCount = 0
        script.metaClass.getGlobalVar = { String n ->
            callCount++
            return callCount == 1 ? [name: 'condemned', type: 'String', value: 'bye', deviceId: null, attribute: null] : null
        }

        and: 'wizard primitives recorded'
        def buttonClicks = []
        script.metaClass._rmClickAppButton = { Integer appId, String btnName, String stateAttr, String pageName ->
            buttonClicks << [appId: appId, btn: btnName, stateAttr: stateAttr, pageName: pageName]
            return [status: 200]
        }
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        // backupItemSource is called between clicks as wizard priming on this firmware.
        script.metaClass.backupItemSource = { String type, String id -> [:] }

        when:
        def result = script.toolDeleteHubVariable([name: 'condemned', confirm: true])

        then: 'two clicks: open the delete prompt, then commit it'
        buttonClicks.size() == 2
        buttonClicks[0] == [appId: 1424, btn: 'condemned',  stateAttr: 'deleteGV',   pageName: 'hubVar']
        buttonClicks[1] == [appId: 1424, btn: 'delConfirm', stateAttr: null,         pageName: 'hubVar']

        and: 'response reports hub-namespace deletion'
        result.success == true
        result.source == 'hub'
        result.deleted == true
        result.name == 'condemned'
    }

    def "delete_variable hub-namespace post-wizard verification fails when var still exists"() {
        given:
        enableHubAdminWrite()

        and: 'getGlobalVar always returns the var — delete wizard ran but did not commit'
        script.metaClass.getGlobalVar = { String n ->
            [name: 'stuck', type: 'String', value: 'still here', deviceId: null, attribute: null]
        }

        and: 'wizard primitives recorded but no real effect'
        script.metaClass._rmClickAppButton = { Integer appId, String btnName, String stateAttr, String pageName -> [status: 200] }
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        script.metaClass.backupItemSource = { String type, String id -> [:] }

        when:
        script.toolDeleteHubVariable([name: 'stuck', confirm: true])

        then: 'IllegalStateException — wizard claimed success but the var is still there'
        def ex = thrown(IllegalStateException)
        ex.message.contains('wizard completed but')
        ex.message.contains('stuck')
    }

    def "create_connector wizard sequence: createCon click + capab commit via _rmWriteSettingOnPage"() {
        given:
        enableHubAdminWrite()

        and: 'getGlobalVar: deviceId=null pre-flight, deviceId=305 after wizard commits'
        def callCount = 0
        script.metaClass.getGlobalVar = { String n ->
            callCount++
            return callCount == 1 ?
                [name: 'foo', type: 'Boolean', value: true, deviceId: null, attribute: null] :
                [name: 'foo', type: 'Boolean', value: true, deviceId: 305,  attribute: 'switch']
        }

        and: 'wizard primitives recorded'
        def buttonClicks = []
        script.metaClass._rmClickAppButton = { Integer appId, String btnName, String stateAttr, String pageName ->
            buttonClicks << [appId: appId, btn: btnName, stateAttr: stateAttr, pageName: pageName]
            return [status: 200]
        }
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        script.metaClass._primeHubVarsWizard = { Integer appId, String context -> /* no-op */ }
        def settingWrites = []
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String settingName, val, List applied, String fieldType, List skipped ->
            settingWrites << [appId: appId, pageName: pageName, name: settingName, value: val, fieldType: fieldType]
        }

        when:
        def result = script.toolCreateConnector([name: 'foo', confirm: true])

        then: 'one createCon click on the row'
        buttonClicks.size() == 1
        buttonClicks[0] == [appId: 1424, btn: 'foo', stateAttr: 'createCon', pageName: 'hubVar']

        and: 'chooser capab commit via _rmWriteSettingOnPage (no-op for Boolean vars but called unconditionally)'
        settingWrites.size() == 1
        settingWrites[0].appId == 1424
        settingWrites[0].pageName == 'hubVar'
        settingWrites[0].name == 'capab'
        settingWrites[0].value == 'Variable'  // default when no connectorType arg
        settingWrites[0].fieldType == 'enum'

        and: 'response surfaces the new connector deviceId'
        result.success == true
        result.deviceId == 305
        result.attribute == 'switch'
        result.name == 'foo'
    }

    // -------- handleHubVariableEvent + toolGetVariableHistory --------

    def "handleHubVariableEvent strips the 'variable:' prefix and appends to atomicState.variableHistory"() {
        given:
        atomicStateMap.variableHistory = []
        def evt = [name: 'variable:porch_light_pref', value: 'bright', descriptionText: 'changed by user']

        when:
        script.handleHubVariableEvent(evt)

        then:
        atomicStateMap.variableHistory.size() == 1
        atomicStateMap.variableHistory[0].name == 'porch_light_pref'
        atomicStateMap.variableHistory[0].value == 'bright'
        atomicStateMap.variableHistory[0].timestamp == 1234567890000L  // HarnessSpec's fixed now()
        atomicStateMap.variableHistory[0].descriptionText == 'changed by user'
    }

    def "handleHubVariableEvent caps the buffer at 200 entries — oldest dropped"() {
        given: 'history pre-loaded with 200 entries'
        atomicStateMap.variableHistory = (1..200).collect { i -> [name: 'var', value: i, timestamp: i] }
        def evt = [name: 'variable:var', value: 999]

        when:
        script.handleHubVariableEvent(evt)

        then: 'still 200 entries; the oldest (value=1) is gone, newest is at the tail'
        atomicStateMap.variableHistory.size() == 200
        atomicStateMap.variableHistory[0].value == 2
        atomicStateMap.variableHistory[-1].value == 999
    }

    def "handleHubVariableEvent ignores null events and missing names"() {
        given:
        atomicStateMap.variableHistory = []

        when:
        script.handleHubVariableEvent(null)
        script.handleHubVariableEvent([name: null, value: 'x'])

        then:
        atomicStateMap.variableHistory == []
    }

    def "get_variable_history returns most-recent-first capped at limit"() {
        given:
        atomicStateMap.variableHistory = (1..10).collect { i -> [name: 'v', value: i, timestamp: i] }

        when:
        def result = script.toolGetVariableHistory([limit: 3])

        then: 'newest three, reverse-chronological'
        result.entries.size() == 3
        result.entries[0].value == 10
        result.entries[1].value == 9
        result.entries[2].value == 8
        result.bufferSize == 10
        result.bufferCap == 200
    }

    def "get_variable_history filters by name when provided"() {
        given:
        atomicStateMap.variableHistory = [
            [name: 'foo', value: 1, timestamp: 1],
            [name: 'bar', value: 2, timestamp: 2],
            [name: 'foo', value: 3, timestamp: 3]
        ]

        when:
        def result = script.toolGetVariableHistory([name: 'foo'])

        then:
        result.entries.size() == 2
        result.entries.every { it.name == 'foo' }
        result.entries[0].value == 3  // newest first
    }

    def "get_variable_history filters by sinceMs when provided"() {
        given:
        atomicStateMap.variableHistory = [
            [name: 'v', value: 1, timestamp: 100],
            [name: 'v', value: 2, timestamp: 200],
            [name: 'v', value: 3, timestamp: 300]
        ]

        when:
        def result = script.toolGetVariableHistory([sinceMs: 200])

        then: 'only entries with timestamp >= 200'
        result.entries.size() == 2
        result.entries[0].timestamp == 300
        result.entries[1].timestamp == 200
    }

    def "get_variable_history returns empty entries when buffer is empty"() {
        given:
        atomicStateMap.variableHistory = []

        when:
        def result = script.toolGetVariableHistory([:])

        then:
        result.entries == []
        result.bufferSize == 0
    }

    // -------- renameVariable callback --------

    def "renameVariable rewrites matching history entries to the new name"() {
        given:
        atomicStateMap.variableHistory = [
            [name: 'old', value: 'a', timestamp: 1],
            [name: 'unrelated', value: 'x', timestamp: 2],
            [name: 'old', value: 'b', timestamp: 3]
        ]
        // subscribe is on AppExecutor — already stubbed in HarnessSpec

        when:
        script.renameVariable('old', 'new')

        then: 'old -> new for matching entries; others untouched'
        atomicStateMap.variableHistory[0].name == 'new'
        atomicStateMap.variableHistory[1].name == 'unrelated'
        atomicStateMap.variableHistory[2].name == 'new'
        // Values preserved
        atomicStateMap.variableHistory[0].value == 'a'
        atomicStateMap.variableHistory[2].value == 'b'
    }

    def "renameVariable is a no-op on history when no entries match"() {
        given:
        atomicStateMap.variableHistory = [[name: 'untouched', value: 'x', timestamp: 1]]

        when:
        script.renameVariable('absent', 'replacement')

        then: 'history unchanged'
        atomicStateMap.variableHistory[0].name == 'untouched'
    }

    // -------- _refreshHubVarInUseRegistrations (issue #96 gap 1) --------

    def "in-use refresh registers vars referenced by child rules"() {
        given: 'two hub vars on the hub'
        script.metaClass.getAllGlobalVars = { ->
            [shared_var: [name: 'shared_var', type: 'String', value: 'x'],
             unused_var: [name: 'unused_var', type: 'String', value: 'y']]
        }
        and: 'a child rule that references shared_var'
        def consumer = new support.TestChildApp(id: 11L, label: 'Uses shared_var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var']],
            actions: []
        ]
        childAppsList << consumer

        and: 'recorders for the in-use API calls'
        def added = []
        def removed = []
        script.metaClass.addInUseGlobalVar    = { String n -> added    << n; true }
        script.metaClass.removeInUseGlobalVar = { String n -> removed  << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'only the referenced var was registered; the unreferenced one was not'
        added == ['shared_var']
        removed == []
        atomicStateMap.inUseHubVars == ['shared_var']
    }

    def "in-use refresh removes registrations for vars no longer referenced"() {
        given: 'previous run registered two vars'
        atomicStateMap.inUseHubVars = ['old_a', 'old_b']
        script.metaClass.getAllGlobalVars = { ->
            [old_a: [name: 'old_a'], old_b: [name: 'old_b'], still_used: [name: 'still_used']]
        }
        and: 'a child rule that now only references still_used'
        def consumer = new support.TestChildApp(id: 22L, label: 'Migrated')
        consumer.ruleData = [conditions: [[type: 'variable', name: 'still_used']]]
        childAppsList << consumer

        def added = []
        def removed = []
        script.metaClass.addInUseGlobalVar    = { String n -> added   << n; true }
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'still_used is newly registered; old_a + old_b are unregistered'
        added == ['still_used']
        removed.toSet() == ['old_a', 'old_b'] as Set
        atomicStateMap.inUseHubVars == ['still_used']
    }

    def "in-use refresh clears all registrations when there are no hub vars at all"() {
        given:
        atomicStateMap.inUseHubVars = ['stale']
        script.metaClass.getAllGlobalVars = { -> [:] }

        def removed = []
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then:
        removed == ['stale']
        atomicStateMap.inUseHubVars == []
    }

    def "in-use refresh tolerates getAllGlobalVars throwing — does not mutate state"() {
        given:
        atomicStateMap.inUseHubVars = ['existing']
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException('hub api down') }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'state unchanged — better to keep stale registration than wipe it on a transient error'
        atomicStateMap.inUseHubVars == ['existing']
        noExceptionThrown()
    }

    // -------- Dispatch-envelope counterparts (#187, #121) --------
    // Parallel coverage exercising callTool() so the JSON-RPC envelope, gateway
    // routing toggles, and error mapping (IAE -> -32602, generic -> isError) are
    // verified end-to-end alongside the direct-call golden paths above. Tools
    // live in the manage_hub_variables gateway; dispatched directly by snake-
    // case name through executeTool() per hubitat-mcp-server.groovy:3098-3105.

    @spock.lang.Unroll
    def "list_variables via dispatch returns hub + rule vars (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAllGlobalVars = { ->
            ['temp_setpoint': [name: 'temp_setpoint', type: 'Number', value: 72, deviceId: 305, attribute: 'variable']]
        }
        stateMap.ruleVariables = [last_motion: '2026-04-21T10:00:00']

        when:
        def response = mcpDriver.callTool('list_variables', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.hubVariables.size() == 1
        inner.hubVariables[0].name == 'temp_setpoint'
        inner.ruleVariables.size() == 1
        inner.total == 2

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "list_variables via dispatch returns empty collections when no vars (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def response = mcpDriver.callTool('list_variables', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.hubVariables == []
        inner.ruleVariables == []
        inner.total == 0

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_variable via dispatch returns hub var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String name ->
            [name: 'outdoor_temp', type: 'Decimal', value: 68.4, deviceId: 312, attribute: 'variable']
        }

        when:
        def response = mcpDriver.callTool('get_variable', [name: 'outdoor_temp'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.name == 'outdoor_temp'
        inner.value == 68.4
        inner.type == 'Decimal'
        inner.source == 'hub'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_variable via dispatch maps unknown to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        def response = mcpDriver.callTool('get_variable', [name: 'nonexistent'])

        then:
        response.error?.code == -32602
        response.error.message.contains('Variable not found')
        response.error.message.contains('nonexistent')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "set_variable via dispatch writes hub var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def response = mcpDriver.callTool('set_variable', [name: 'vacation_mode', value: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.name == 'vacation_mode'
        inner.value == true
        inner.source == 'hub'
        captured == [vacation_mode: true]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "set_variable via dispatch falls back to rule_engine when hub var missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.setGlobalVar = { String name, Object value -> false }

        when:
        def response = mcpDriver.callTool('set_variable', [name: 'new_rule_var', value: 42])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.source == 'rule_engine'
        inner.value == 42
        stateMap.ruleVariables == [new_rule_var: 42]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "delete_variable via dispatch removes rule var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        stateMap.ruleVariables = [scratch_var: 'to-be-removed', keep_me: 42]

        when:
        def response = mcpDriver.callTool('delete_variable', [name: 'scratch_var', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deleted == true
        inner.source == 'rule_engine'
        inner.previousValue == 'to-be-removed'
        stateMap.ruleVariables == [keep_me: 42]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "delete_variable via dispatch maps missing-confirm to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        def response = mcpDriver.callTool('delete_variable', [name: 'scratch_var'])

        then:
        response.error?.code == -32602
        stateMap.ruleVariables == [scratch_var: 'value']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "delete_variable via dispatch maps missing-name to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('delete_variable', [confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains('name is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "delete_variable via dispatch maps unknown var to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        def response = mcpDriver.callTool('delete_variable', [name: 'nonexistent', confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains("'nonexistent'")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "delete_variable via dispatch refuses on consumer ref without force (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']
        def consumer = new support.TestChildApp(id: 99L, label: 'Rule Using Shared Var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var', operator: '=', value: 'on']],
            actions: []
        ]
        childAppsList << consumer

        when:
        def response = mcpDriver.callTool('delete_variable', [name: 'shared_var', confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains("'shared_var'")
        response.error.message.contains('force=true')
        stateMap.ruleVariables == [shared_var: 'in-use']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_variable via dispatch maps forbidden-character to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('create_variable', [name: 'has[brackets]', type: 'String', value: 'x', confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains('forbidden character')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_variable via dispatch maps unknown-type to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('create_variable', [name: 'goodName', type: 'NotAType', value: 'x', confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains('NotAType')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_variable via dispatch maps missing-confirm to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('create_variable', [name: 'goodName', type: 'String', value: 'x'])

        then:
        response.error?.code == -32602

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "create_variable via dispatch maps existing hub var to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.getGlobalVar = { String n ->
            n == 'taken' ? [name: 'taken', type: 'String', value: 'already here', deviceId: null, attribute: null] : null
        }

        when:
        def response = mcpDriver.callTool('create_variable', [name: 'taken', type: 'String', value: 'new', confirm: true])

        then:
        response.error?.code == -32602
        response.error.message.contains('already exists')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_variable_history via dispatch caps at limit (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.variableHistory = (1..10).collect { i -> [name: 'v', value: i, timestamp: i] }

        when:
        def response = mcpDriver.callTool('get_variable_history', [limit: 3])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries.size() == 3
        inner.entries[0].value == 10
        inner.bufferSize == 10

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_variable_history via dispatch filters by name (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.variableHistory = [
            [name: 'foo', value: 1, timestamp: 1],
            [name: 'bar', value: 2, timestamp: 2],
            [name: 'foo', value: 3, timestamp: 3]
        ]

        when:
        def response = mcpDriver.callTool('get_variable_history', [name: 'foo'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries.size() == 2
        inner.entries.every { it.name == 'foo' }

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "get_variable_history via dispatch returns empty when buffer is empty (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.variableHistory = []

        when:
        def response = mcpDriver.callTool('get_variable_history', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.entries == []
        inner.bufferSize == 0

        where:
        useGateways << [true, false]
    }
}
