package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import support.TestLocation
import support.ToolSpecBase

class NativeHubVariablesSpec extends ToolSpecBase {
    @Shared private TestLocation sharedLocation = new TestLocation()
    private static final Object NATIVE_MODEL = new Object()
    private Map variables = [:]
    private List requests = []
    private boolean persistWrites = true
    private Object modelBody = NATIVE_MODEL
    private RuntimeException endpointFailure = null
    private Set hiddenFromModel = [] as Set
    private Set rejectNames = [] as Set
    private Map meshJson = [localLinkedHubVariables: [], availableLinkedHubVariables: [], sharedHubVariables: []]
    private Map postResponse = [success: true]

    def setupSpec() { appExecutor.getLocation() >> sharedLocation }

    def setup() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.getGlobalVar = { String name -> variables[name]?.sdk }
        script.metaClass.getAllGlobalVars = { -> variables.collectEntries { name, row -> [name, row.sdk] } }
        script.metaClass.pauseExecution = { Long millis -> }
        script.metaClass.backupItemSource = { String type, String id -> [:] }
        script.metaClass._findHubVariablesAppId = { -> throw new AssertionError('Supported native endpoint must not open the wizard') }
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30 ->
            requests << [method: 'GET', path: path]
            if (path == '/hub2/hubMeshJson') return JsonOutput.toJson(meshJson)
            if (path != '/hub2/variables') throw new AssertionError("Unexpected GET ${path}")
            if (endpointFailure != null) throw endpointFailure
            if (!modelBody.is(NATIVE_MODEL)) return modelBody
            JsonOutput.toJson([variables: variables.findAll { name, row -> !(name in hiddenFromModel) }.values().collect { it.nativeRow }])
        }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 ->
            Map body = new JsonSlurper().parseText(json) as Map
            requests << [method: 'POST', path: path, body: body]
            if (body.name in rejectNames) return [success: false, message: 'Rejected by hub']
            if (persistWrites && postResponse.success == true) {
                if (path == '/hub2/variables/create') {
                    String sdkType = [integer: 'Number', bigdecimal: 'Decimal', string: 'String', boolean: 'Boolean', datetime: 'DateTime'][body.type]
                    def value = body.type == 'integer' ? body.value.toInteger() : body.type == 'bigdecimal' ? body.value.toBigDecimal() : body.type == 'boolean' ? body.value.toBoolean() : body.value
                    seed(body.name.toString(), sdkType, value)
                } else if (path == '/hub2/variables/createConnector') {
                    variables[body.name].sdk.deviceId = 905
                    variables[body.name].sdk.attribute = 'variable'
                    variables[body.name].nativeRow.connectorId = 905
                } else if (path == '/hub2/variables/removeConnector') {
                    variables[body.name].sdk.deviceId = null
                    variables[body.name].sdk.attribute = null
                    variables[body.name].nativeRow.connectorId = null
                } else if (path == '/hub2/variables/remove') {
                    variables.remove(body.name)
                } else throw new AssertionError("Unexpected POST ${path}")
            }
            postResponse
        }
    }

    private void seed(String name, String type = 'Number', value = 1, List usedBy = [], List connectorUsedBy = []) {
        String storedType = [Number: 'integer', Decimal: 'bigdecimal', String: 'string', Boolean: 'boolean', DateTime: 'datetime'][type] ?: type
        variables[name] = [sdk: [name: name, type: storedType, value: value, deviceId: null, attribute: null],
            nativeRow: [name: name, type: storedType, value: value, connectorId: null, connectorLabel: null,
                connectorType: null, connectorOptions: ['Variable', 'Dimmer'], usedBy: usedBy, connectorUsedBy: connectorUsedBy]]
    }

    private void seedConnector(String name) {
        variables[name].sdk.deviceId = 905
        variables[name].nativeRow.connectorId = 905
    }

    private List posts() { requests.findAll { it.method == 'POST' } }

    static class HttpFailure extends RuntimeException {
        Map response
        HttpFailure(Integer status) {
            super("HTTP ${status}".toString())
            response = [status: status]
        }
    }

    def 'create mismatch reports the existing variable and repair steps without rollback'() {
        given:
        script.metaClass.getGlobalVar = { String name ->
            if (posts() && variables[name]) {
                variables[name].sdk.type = 'string'
                variables[name].sdk.value = 'observed'
            }
            variables[name]?.sdk
        }
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 17])
        then:
        !result.success
        result.error.contains("'Counter' exists")
        result.error.contains('type=string, value=observed')
        result.error.contains('requested type=integer, value=17')
        result.error.contains('hub_set_variable')
        result.error.contains('hub_delete_variable')
        result.note.contains("hub_get_tool_guide(section='variables')")
        variables.Counter.sdk.value == 'observed'
        posts()*.path == ['/hub2/variables/create']
    }
    def 'single create sends current JSON type and verifies SDK storage'() {
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 17])
        then:
        result.success
        variables.Counter.sdk.value == 17
        posts() == [[method: 'POST', path: '/hub2/variables/create', body: [name: 'Counter', type: 'integer', value: '17']]]
    }

    def 'bulk creation sends independent JSON DTOs and reads each persisted variable'() {
        when:
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'DecimalValue', type: 'Decimal', value: 1.25],
            [name: 'Flag', type: 'Boolean', value: false],
            [name: 'Text', type: 'String', value: 'hello']]])
        then:
        result.success
        result.createdCount == 3
        result.failedCount == 0
        posts()*.body == [[name: 'DecimalValue', type: 'bigdecimal', value: '1.25'],
            [name: 'Flag', type: 'boolean', value: 'false'], [name: 'Text', type: 'string', value: 'hello']]
        variables.Flag.sdk.value == false
        variables.Text.sdk.value == 'hello'
    }

    def 'DateTime creation sends date and time with normalized value'() {
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Schedule', type: 'DateTime', value: [date: '2026-05-06', time: '12:34']])
        then:
        result.success
        posts().size() == 1
        posts()[0].body.name == 'Schedule'
        posts()[0].body.type == 'datetime'
        posts()[0].body.mode == 'datetime'
        posts()[0].body.date == '2026-05-06'
        posts()[0].body.time == '12:34'
        posts()[0].body.value == '2026-05-06T12:34:00.000+0000'
        result.value == '2026-05-06T12:34:00.000+0000'
    }

    def 'successful POST without persisted variable fails readback'() {
        given:
        persistWrites = false
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Missing', type: 'Number', value: 3])
        then:
        !result.success
        result.error.contains('the hub accepted the create')
        !result.error.contains('wizard')
        result.note.contains('hub_get_variable')
        posts().size() == 1
        !variables.containsKey('Missing')
    }

    def 'POST error never retries through the legacy wizard'() {
        given:
        postResponse = [success: false, error: 'Rejected']
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Rejected', type: 'Number', value: 3])
        then:
        !result.success
        result.error == 'hub_create_variable: Hub Variables create failed: Rejected'
        posts().size() == 1
        variables.isEmpty()
    }

    def 'connector create and remove use native payloads and verify SDK linkage'() {
        given:
        seed('Counter')
        when:
        def created = script.toolCreateConnector([confirm: true, name: 'Counter', connectorType: 'Dimmer'])
        then:
        created.success
        created.deviceId == 905
        posts()[0].body == [name: 'Counter', connectorType: 'Dimmer']
        when:
        def removed = script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        removed.success
        removed.note.contains('StackOverflowError')
        removed.brokenConsumers == null
        removed.consumersUnknown == null
        variables.Counter.sdk.deviceId == null
        posts()[1] == [method: 'POST', path: '/hub2/variables/removeConnector', body: [name: 'Counter', acknowledgeUse: false]]
    }

    def 'connector creation must persist linkage even when POST succeeds'() {
        given:
        seed('Counter')
        persistWrites = false
        when:
        def result = script.toolCreateConnector([confirm: true, name: 'Counter'])
        then:
        !result.success
        result.error.contains("hub_create_connector: the hub accepted the 'Variable' connector create")
        variables.Counter.sdk.deviceId == null
        posts().size() == 1
    }

    def 'native dependents list direct and connector users once each without opening a wizard'() {
        given:
        seed('Counter', 'Number', 1, [[id: 81, label: 'Heating'], [id: 82, label: 'Dashboard']], [[id: 82, label: 'Dashboard']])
        when:
        def result = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        result.appsUsing == [[id: '81', label: 'Heating'], [id: '82', label: 'Dashboard']]
        result.connectorAppsUsing == [[id: '82', label: 'Dashboard']]
        result.count == 2
        posts().isEmpty()
    }

    def 'unknown native consumers are reported and deletion refuses to write'() {
        given:
        seed('Counter')
        variables.Counter.nativeRow.usedBy = null
        when:
        def result = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        result.dependentsError.contains("could not confirm 'Counter' consumers")
        result.dependentsNote.contains("section='variables'")
        !result.containsKey('appsUsing')
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains("could not confirm 'Counter' consumers")
        posts().isEmpty()
        variables.containsKey('Counter')
        when:
        def forced = script.toolDeleteHubVariable([confirm: true, name: 'Counter', force: true])
        then:
        forced.success
        forced.platformInUse == null
        forced.platformInUseError.contains("could not confirm 'Counter' consumers")
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: 'Counter', acknowledgeUse: true]]]
    }

    def 'connector consumers prevent removal until force acknowledges them'() {
        given:
        seed('Counter', 'Number', 1, [], [[id: 82, label: 'Dashboard']])
        seedConnector('Counter')
        when:
        script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('is in use by Dashboard (id=82)')
        refusal.message.contains('force=true')
        refusal.message.contains("hub_get_tool_guide(section='variables')")
        posts().isEmpty()
        variables.Counter.sdk.deviceId == 905
        when:
        def result = script.toolRemoveConnector([confirm: true, name: 'Counter', force: true])
        then:
        result.success
        result.brokenConsumers == [[id: '82', label: 'Dashboard']]
        result.consumersUnknown == null
        variables.Counter.sdk.deviceId == null
        posts() == [[method: 'POST', path: '/hub2/variables/removeConnector', body: [name: 'Counter', acknowledgeUse: true]]]
    }

    def 'unknown connector consumers prevent removal until force'() {
        given:
        seed('Counter')
        seedConnector('Counter')
        variables.Counter.nativeRow.remove('connectorUsedBy')
        when:
        script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains("could not confirm 'Counter' consumers")
        posts().isEmpty()
        variables.Counter.sdk.deviceId == 905
        when:
        def result = script.toolRemoveConnector([confirm: true, name: 'Counter', force: true])
        then:
        result.success
        result.brokenConsumers == null
        result.consumersUnknown == true
        posts() == [[method: 'POST', path: '/hub2/variables/removeConnector', body: [name: 'Counter', acknowledgeUse: true]]]
    }

    def 'connector removal fails when the variable cannot be read back (#mode)'() {
        given:
        seed('Counter')
        seedConnector('Counter')
        script.metaClass.getGlobalVar = { String name ->
            if (!posts()) return variables[name]?.sdk
            if (mode == 'throws') throw new IllegalStateException('SDK read unavailable')
            null
        }
        when:
        def result = script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        !result.success
        result.error.contains(expected)
        result.note.contains('hub_get_variable')
        posts()*.path == ['/hub2/variables/removeConnector']

        where:
        mode      | expected
        'missing' | 'no longer readable'
        'throws'  | 'SDK read unavailable'
    }

    def 'connector type the hub does not offer is refused before any POST'() {
        given:
        seed('Counter')
        when:
        script.toolCreateConnector([confirm: true, name: 'Counter', connectorType: 'Volume'])
        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('The hub offers: Variable, Dimmer')
        error.message.contains("hub_get_tool_guide(section='variables')")
        posts().isEmpty()
    }

    def 'connector create refuses when the hub offers no connector types'() {
        given:
        seed('Counter')
        variables.Counter.nativeRow.connectorOptions = []
        when:
        def result = script.toolCreateConnector([confirm: true, name: 'Counter'])
        then:
        !result.success
        result.error.contains("did not offer connector types for 'Counter'")
        result.note.startsWith('Nothing was changed')
        posts().isEmpty()
    }

    def 'connector create defaults to the first offered type when Variable is not offered'() {
        given:
        seed('Counter')
        variables.Counter.nativeRow.connectorOptions = ['Dimmer', 'Volume']
        when:
        def result = script.toolCreateConnector([confirm: true, name: 'Counter'])
        then:
        result.connectorType == 'Dimmer'
        posts()*.body == [[name: 'Counter', connectorType: 'Dimmer']]
    }

    def 'unreadable SDK deletion readback cannot prove success'() {
        given:
        seed('Counter')
        script.metaClass.getGlobalVar = { String name ->
            if (posts()) throw new IllegalStateException('SDK read unavailable')
            variables[name]?.sdk
        }
        when:
        def result = script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        !result.success
        result.error.contains("could not verify whether 'Counter' was deleted: SDK read unavailable")
        posts().size() == 1
    }

    def 'idle deletion sends false acknowledgement and requires actual removal'() {
        given:
        seed('Counter')
        persistWrites = false
        when:
        def result = script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        !result.success
        result.error.contains('hub_delete_variable: the hub accepted the delete')
        !result.error.contains('click-sequence')
        variables.containsKey('Counter')
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: 'Counter', acknowledgeUse: false]]]
    }

    def 'in use deletion refuses without force then sends explicit acknowledgeUse'() {
        given:
        seed('Counter', 'Number', 1, [[id: 81, label: 'Heating']])
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('is in use by Heating (id=81)')
        posts().isEmpty()
        variables.containsKey('Counter')
        when:
        def result = script.toolDeleteHubVariable([confirm: true, name: 'Counter', force: true])
        then:
        result.success
        !variables.containsKey('Counter')
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: 'Counter', acknowledgeUse: true]]]
    }

    def 'connector-only consumers block deletion and appear as dependents'() {
        given:
        seed('Counter', 'Number', 1, [], [[id: 82, label: 'Dashboard']])
        seedConnector('Counter')
        when:
        def found = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        found.appsUsing == [[id: '82', label: 'Dashboard']]
        found.connectorAppsUsing == [[id: '82', label: 'Dashboard']]
        found.count == 1
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains('Dashboard (id=82)')
        posts().isEmpty()
        variables.containsKey('Counter')
    }

    def 'clean Hub Mesh mirror unlink acknowledges the mesh link without force'() {
        given:
        String mirror = 'Counter on Peer'
        seed(mirror, 'Number', 1, [[id: 7, label: 'Hub Mesh']])
        meshJson.localLinkedHubVariables = [[name: mirror, sourceVarName: 'Counter', sourceHubName: 'Peer', inUseByApps: false]]
        when:
        def result = script.toolDeleteHubVariable([confirm: true, name: mirror])
        then:
        result.success
        result.unlinked
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: mirror, acknowledgeUse: true]]]
        !variables.containsKey(mirror)
    }

    def 'variable missing from the native model is unknown, not unused'() {
        given:
        seed('Counter')
        hiddenFromModel << 'Counter'
        when:
        def found = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        found.dependentsError.contains("does not list 'Counter'")
        !found.containsKey('appsUsing')
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.contains("does not list 'Counter'")
        posts().isEmpty()
        variables.containsKey('Counter')
    }

    def 'bulk native create reports a rejected item and keeps the others'() {
        given:
        rejectNames << 'Bad'
        when:
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'First', type: 'Number', value: 1],
            [name: 'Bad', type: 'Number', value: 2],
            [name: 'Last', type: 'Number', value: 3]]])
        then:
        !result.success
        result.createdCount == 2
        result.failedCount == 1
        result.results*.name == ['First', 'Bad', 'Last']
        result.results*.success == [true, false, true]
        result.results[1].error.contains('Rejected by hub')
        posts()*.body*.name == ['First', 'Bad', 'Last']
        variables.keySet() == ['First', 'Last'] as Set
    }

    def 'empty Hub Variables body fails instead of opening the wizard (#label)'() {
        given:
        modelBody = body
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 3])
        then:
        !result.success
        result.error == 'hub_create_variable: Could not read the Hub Variables model: Hub Variables returned an empty response'
        result.note.startsWith('Nothing was changed')
        posts().isEmpty()

        where:
        label   | body
        'null'  | null
        'empty' | ''
        'blank' | '  '
    }

    def 'Hub Variables endpoint failure other than 404 is raised, not routed to the wizard (#status)'() {
        given:
        endpointFailure = new HttpFailure(status)
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 3])
        then:
        !result.success
        result.error == "hub_create_variable: Could not read the Hub Variables model: HTTP ${status}".toString()
        posts().isEmpty()

        where:
        status << [500, 302, null]
    }

    def 'unreadable Hub Variables model is reported (#label)'() {
        given:
        modelBody = body
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 3])
        then:
        !result.success
        result.error == "hub_create_variable: Could not read the Hub Variables model: ${expected}".toString()
        posts().isEmpty()

        where:
        label            | body                    | expected
        'malformed JSON' | '{"variables": ['       | 'Could not read the Hub Variables JSON model'
        'JSON array'     | '[1, 2]'                | 'Hub Variables returned an unexpected JSON model'
        'no variables'   | '{"variables": "none"}' | 'Hub Variables returned an unexpected JSON model'
    }

    def 'endpoint 404 creates through existing wizard primitives'() {
        given:
        endpointFailure = new HttpFailure(404)
        Map pending = [:]
        List clicks = []
        script.metaClass._findHubVariablesAppId = { -> 1424 }
        script.metaClass._primeHubVarsWizard = { Integer id, String reason -> }
        script.metaClass._hubVarsFormFields = { Integer id -> ['hbVar', 'varType', 'varValue'] as Set }
        script.metaClass._rmClickAppButton = { Integer id, String button, String attribute, String page -> clicks << button; [status: 200] }
        script.metaClass._rmWriteSettingOnPage = { Integer id, String page, String key, Object value, List applied, String typeHint = null, List skipped = null ->
            if (key == 'hbVar') pending.name = value
            if (key == 'varType') pending.type = value
            if (key == 'varValue') seed(pending.name.toString(), pending.type.toString(), value)
            applied << key
        }
        when:
        def result = script.toolCreateVariable([confirm: true, name: 'Legacy', type: 'Number', value: 4])
        then:
        result.success
        variables.Legacy.sdk.value == 4
        clicks == ['moreVar']
        posts().isEmpty()
    }

    def 'via dispatch: native create and delete succeed through the tools/call envelope'() {
        when:
        def created = mcpDriver.callTool('hub_create_variable', [confirm: true, name: 'Counter', type: 'Number', value: 17])
        then:
        created.error == null
        !created.result.isError
        mcpDriver.parseInner(created).success == true
        variables.Counter.sdk.value == 17
        when:
        def deleted = mcpDriver.callTool('hub_delete_variable', [confirm: true, name: 'Counter'])
        then:
        deleted.error == null
        !deleted.result.isError
        mcpDriver.parseInner(deleted).deleted == true
        !variables.containsKey('Counter')
        posts()*.path == ['/hub2/variables/create', '/hub2/variables/remove']
    }

    def 'via dispatch: an unoffered connectorType is a validation error with no POST'() {
        given:
        seed('Counter')
        when:
        def response = mcpDriver.callTool('hub_create_connector', [confirm: true, name: 'Counter', connectorType: 'Volume'])
        then:
        response.error == null
        response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('The hub offers: Variable, Dimmer')
        posts().isEmpty()
    }

    def 'via dispatch: a connector apps use is refused without force'() {
        given:
        seed('Counter', 'Number', 1, [], [[id: 82, label: 'Dashboard']])
        seedConnector('Counter')
        when:
        def response = mcpDriver.callTool('hub_delete_connector', [confirm: true, name: 'Counter'])
        then:
        response.error == null
        response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Dashboard (id=82)')
        inner.error.contains('force=true')
        posts().isEmpty()
        variables.Counter.sdk.deviceId == 905
    }

    def 'via dispatch: a native runtime failure returns the structured error'() {
        given:
        modelBody = ''
        when:
        def response = mcpDriver.callTool('hub_create_variable', [confirm: true, name: 'Counter', type: 'Number', value: 3])
        then:
        response.error == null
        response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Hub Variables returned an empty response')
        inner.note.startsWith('Nothing was changed')
        posts().isEmpty()
    }
}
