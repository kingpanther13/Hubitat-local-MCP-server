package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import support.TestLocation
import support.ToolSpecBase

class NativeHubVariablesSpec extends ToolSpecBase {
    @Shared private TestLocation sharedLocation = new TestLocation()
    private Map variables = [:]
    private List requests = []
    private boolean persistWrites = true
    private boolean endpointAvailable = true
    private boolean endpointNotFound = false
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
            if (path == '/hub2/hubMeshJson') return JsonOutput.toJson([localLinkedHubVariables: [], availableLinkedHubVariables: [], sharedHubVariables: []])
            if (path != '/hub2/variables') throw new AssertionError("Unexpected GET ${path}")
            if (endpointNotFound) throw new Http404()
            endpointAvailable ? JsonOutput.toJson([variables: variables.values().collect { it.nativeRow }]) : null
        }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 ->
            Map body = new JsonSlurper().parseText(json) as Map
            requests << [method: 'POST', path: path, body: body]
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

    private void seed(String name, String type = 'Number', value = 1, List usedBy = []) {
        String storedType = [Number: 'integer', Decimal: 'bigdecimal', String: 'string', Boolean: 'boolean', DateTime: 'datetime'][type] ?: type
        variables[name] = [sdk: [name: name, type: storedType, value: value, deviceId: null, attribute: null],
            nativeRow: [name: name, type: storedType, value: value, connectorId: null, connectorLabel: null,
                connectorType: null, connectorOptions: ['Variable', 'Dimmer'], usedBy: usedBy, connectorUsedBy: []]]
    }

    private List posts() { requests.findAll { it.method == 'POST' } }

    static class Http404 extends RuntimeException {
        Map response = [status: 404]
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
        script.toolCreateVariable([confirm: true, name: 'Counter', type: 'Number', value: 17])
        then:
        def error = thrown(IllegalStateException)
        error.message.contains("'Counter' exists")
        error.message.contains('type=string, value=observed')
        error.message.contains('requested type=integer, value=17')
        error.message.contains('hub_set_variable')
        error.message.contains('hub_delete_variable')
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
        posts()[0].body.value == variables.Schedule.sdk.value
    }

    def 'successful POST without persisted variable fails readback'() {
        given:
        persistWrites = false
        when:
        script.toolCreateVariable([confirm: true, name: 'Missing', type: 'Number', value: 3])
        then:
        thrown(IllegalStateException)
        posts().size() == 1
        !variables.containsKey('Missing')
    }

    def 'POST error never retries through the legacy wizard'() {
        given:
        postResponse = [success: false, error: 'Rejected']
        when:
        script.toolCreateVariable([confirm: true, name: 'Rejected', type: 'Number', value: 3])
        then:
        thrown(IllegalStateException)
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
        variables.Counter.sdk.deviceId == null
        posts()[1] == [method: 'POST', path: '/hub2/variables/removeConnector', body: [name: 'Counter', acknowledgeUse: false]]
    }

    def 'connector creation must persist linkage even when POST succeeds'() {
        given:
        seed('Counter')
        persistWrites = false
        when:
        script.toolCreateConnector([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalStateException)
        variables.Counter.sdk.deviceId == null
        posts().size() == 1
    }

    def 'native dependents preserve app id and label without opening a wizard'() {
        given:
        seed('Counter', 'Number', 1, [[id: 81, label: 'Heating']])
        when:
        def result = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        result.appsUsing.collect { it.id.toString() } == ['81']
        result.appsUsing*.label == ['Heating']
        result.count == 1
        posts().isEmpty()
    }

    def 'unknown native consumers are reported and deletion refuses to write'() {
        given:
        seed('Counter')
        variables.Counter.nativeRow.usedBy = null
        when:
        def result = script.toolGetVariable([name: 'Counter', includeDependents: true])
        then:
        result.dependentsError
        !result.containsKey('appsUsing')
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalArgumentException)
        posts().isEmpty()
        variables.containsKey('Counter')
    }

    def 'connector consumers prevent removal'() {
        given:
        seed('Counter')
        variables.Counter.sdk.deviceId = 905
        variables.Counter.nativeRow.connectorId = 905
        variables.Counter.nativeRow.connectorUsedBy = [[id: 82, label: 'Dashboard']]
        when:
        script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalArgumentException)
        posts().isEmpty()
        variables.Counter.sdk.deviceId == 905
    }

    def 'unknown connector consumers prevent removal'() {
        given:
        seed('Counter')
        variables.Counter.sdk.deviceId = 905
        variables.Counter.nativeRow.connectorId = 905
        variables.Counter.nativeRow.remove('connectorUsedBy')
        when:
        script.toolRemoveConnector([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalStateException)
        posts().isEmpty()
        variables.Counter.sdk.deviceId == 905
    }

    def 'unreadable SDK deletion readback cannot prove success'() {
        given:
        seed('Counter')
        script.metaClass.getGlobalVar = { String name ->
            if (posts()) throw new IllegalStateException('SDK read unavailable')
            variables[name]?.sdk
        }
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalStateException)
        posts().size() == 1
    }

    def 'idle deletion sends false acknowledgement and requires actual removal'() {
        given:
        seed('Counter')
        persistWrites = false
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalStateException)
        variables.containsKey('Counter')
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: 'Counter', acknowledgeUse: false]]]
    }

    def 'in use deletion refuses without force then sends explicit acknowledgeUse'() {
        given:
        seed('Counter', 'Number', 1, [[id: 81, label: 'Heating']])
        when:
        script.toolDeleteHubVariable([confirm: true, name: 'Counter'])
        then:
        thrown(IllegalArgumentException)
        posts().isEmpty()
        variables.containsKey('Counter')
        when:
        def result = script.toolDeleteHubVariable([confirm: true, name: 'Counter', force: true])
        then:
        result.success
        !variables.containsKey('Counter')
        posts() == [[method: 'POST', path: '/hub2/variables/remove', body: [name: 'Counter', acknowledgeUse: true]]]
    }

    def 'endpoint unavailable creates through existing wizard primitives (#notFound)'() {
        given:
        endpointAvailable = false
        endpointNotFound = notFound
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

        where:
        notFound << [false, true]
    }
}
