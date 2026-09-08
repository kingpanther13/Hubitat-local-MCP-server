package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

class ToolDeviceConfigurationWriteSpec extends ToolSpecBase {
    private static Map fixture() {
        [device: [id: 10, version: 0, name: 'Fixture', label: 'Fixture', deviceNetworkId: 'fixture-10',
                  deviceTypeId: 100, deviceTypeReadableType: 'User', controllerType: 'VIRTUAL',
                  zigbeeId: '0011223344556677', roomId: 0, locationId: 1, hubId: 1, groupId: 0,
                  maxEvents: 10, maxStates: 20, spammyThreshold: 100, notes: '', tags: '',
                  defaultIcon: '', icon: 'fa-lightbulb', meshEnabled: false, retryEnabled: false,
                  meshFullSync: false, meshSelectionEnabled: true, linkedDevice: false,
                  showOnHome: false, defaultCurrentState: '', isComponent: false,
                  data: [integrationToken: 'fixture-token']],
         settings: [[name: 'logEnable', type: 'bool', value: 'true', defaultValue: 'false', required: false],
                    [name: 'offset', type: 'number', value: '0', range: '-10..10', required: false],
                    [name: 'modes', type: 'enum', multiple: true, options: [a: 'A', b: 'B'], value: 'a']],
         inputValues: [], dashboards: [[id: 1, name: 'One', selected: true], [id: 2, name: 'Two', selected: false]],
         hasDashboards: true, commandRetrySelectionEnabled: true, hubMeshRefreshEnabled: true,
         homeKitSelectionEnabled: true, homeKitEnabled: true,
         amazonAlexaInstalled: true, amazonAlexaSupported: true, amazonAlexaEnabled: false,
         googleHomeInstalled: true, googleHomeSupported: true, googleHomeEnabled: true]
    }

    private void registerFixture(Map model, boolean bypass) {
        settingsMap.bypassDeviceAllowlist = bypass
        if (!bypass) childDevicesList << new TestDevice(id: 10, name: 'Fixture', label: 'Fixture', deviceNetworkId: 'fixture-10')
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(model) }
        hubGet.register('/device/drivers') { JsonOutput.toJson([drivers: [[id: 100, name: 'Fixture', type: 'usr'], [id: 101, name: 'Other', type: 'usr']]]) }
        stateMap.lastBackupTimestamp = System.currentTimeMillis()
    }

    private static Map decodeForm(String body) {
        def result = [:]
        body.split('&').each { pair ->
            def parts = pair.split('=', 2)
            result.put(URLDecoder.decode(parts[0], 'UTF-8'), URLDecoder.decode(parts.length > 1 ? parts[1] : '', 'UTF-8'))
        }
        result
    }

    @Unroll
    def 'configuration form writes #property with Vue encoding in bypass=#bypass'() {
        given:
        def model = fixture()
        if (property == 'meshFullSync') model.device.linkedDevice = true
        registerFixture(model, bypass)
        def posts = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 30, boolean retry = false ->
            posts << [path: path, form: decodeForm(body)]
            if (property == 'dashboardIds') model.dashboards.each { it.selected = target.contains(it.id) }
            else model.device.put(property, target)
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true] + [(property): target])

        then:
        result.success == true
        result.changes.find { it.property == property }?.newValue == target
        posts.size() == 1
        posts[0].path == '/device/update'
        posts[0].form.get(property) == wire
        posts[0].form.version == '0'
        posts[0].form.groupId == '0'
        posts[0].form.deviceNetworkId == 'fixture-10'
        posts[0].form.homeKitEnabled == 'on'
        posts[0].form.notes == (property == 'notes' ? target : '')
        model.device.data.integrationToken == 'fixture-token'

        where:
        [bypass, row] << [ [false, true], [
            ['notes', 'A & B\nC', 'A & B\nC'], ['notes', '', ''],
            ['maxEvents', 1, '1'], ['maxStates', 2000, '2000'], ['spammyThreshold', 2000, '2000'],
            ['defaultIcon', '', ''], ['deviceTypeId', 101, '101'],
            ['zigbeeId', '8899AABBCCDDEEFF', '8899AABBCCDDEEFF'],
            ['meshEnabled', true, 'on'], ['meshEnabled', false, 'false'],
            ['retryEnabled', true, 'on'], ['meshFullSync', true, 'on'],
            ['dashboardIds', [2], '2'], ['dashboardIds', [], '']
        ] ].combinations()
        property = row[0]
        target = row[1]
        wire = row[2]
    }

    @Unroll
    def 'invalid #property is rejected before an earlier label write in bypass=#bypass'() {
        given:
        def model = fixture()
        registerFixture(model, bypass)
        def posts = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> posts << path; '' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> posts << path; [success: true] }
        def device = childDevicesList.find { it.id == 10 }
        if (device) device.metaClass.setLabel = { String value -> posts << 'setLabel' }

        when:
        script.toolUpdateDevice([deviceId: '10', label: 'Must remain unchanged', confirm: true] + [(property): target])

        then:
        thrown(IllegalArgumentException)
        posts.empty

        where:
        [bypass, row] << [[false, true], [
            ['maxEvents', 0], ['maxStates', 2001], ['maxStates', 1.5], ['spammyThreshold', 99],
            ['meshEnabled', 'false'], ['dashboardIds', [999]], ['deviceTypeId', 999],
            ['preferences', [doesNotExist: [type: 'bool', value: true]]],
            ['preferences', [logEnable: [type: 'bool', value: 'perhaps']]],
            ['preferences', [offset: [type: 'number', value: 11]]]
        ]].combinations()
        property = row[0]
        target = row[1]
    }

    @Unroll
    def 'sensitive #property requires confirmation before mutation'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        def posts = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> posts << path; '' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> posts << path; [success: true] }

        when:
        script.toolUpdateDevice([deviceId: '10'] + [(property): target])

        then:
        thrown(IllegalArgumentException)
        posts.empty

        where:
        property             | target
        'deviceTypeId'       | 101
        'deviceNetworkId'    | 'new-identity'
        'zigbeeId'           | '8899AABBCCDDEEFF'
        'meshEnabled'        | true
        'dashboardIds'       | [2]
        'homeKitEnabled'     | false
        'amazonAlexaEnabled' | true
        'googleHomeEnabled'  | false
    }

    @Unroll
    def 'native assistant #property write preserves other assignments in bypass=#bypass'() {
        given:
        def model = fixture()
        registerFixture(model, bypass)
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            def decoded = new JsonSlurper().parseText(body)
            posts << [path: path, body: decoded]
            model.put(property, target)
            [success: true, assistants: [(assistant): [success: true, enabled: target]]]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true] + [(property): target])

        then:
        result.success == true
        result.changes.find { it.property == property }?.newValue == target
        posts.size() == 1
        posts[0].path == '/device/updateAssistants'
        posts[0].body == ([deviceId: 10, homeKitEnabled: true, amazonAlexaEnabled: false, googleHomeEnabled: true] + [(property): target])

        where:
        [bypass, row] << [[false, true], [['homeKitEnabled', false, 'homeKit'], ['amazonAlexaEnabled', true, 'amazonAlexa'], ['googleHomeEnabled', false, 'googleHome']]].combinations()
        property = row[0]
        target = row[1]
        assistant = row[2]
    }

    def 'assistant partial failure preserves confirmed changes and native reason'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            model.homeKitEnabled = false
            [success: false, assistants: [homeKit: [success: true, enabled: false], amazonAlexa: [success: false, reason: 'integrationError']]]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true, homeKitEnabled: false, amazonAlexaEnabled: true])

        then:
        result.success == false
        result.changes.find { it.property == 'homeKitEnabled' }
        result.errors.find { it.property == 'amazonAlexaEnabled' }?.error?.contains('integrationError')
    }

    @Unroll
    def 'preference root settings readback confirms #target for bypass=#bypass'() {
        given:
        def model = fixture()
        registerFixture(model, bypass)
        def applyPreference = { String key, setting ->
            def value = setting instanceof Map ? setting.value : setting
            model.settings.find { it.name == key }.value = value == null ? null : value.toString()
        }
        if (!bypass) childDevicesList[0].metaClass.updateSetting = applyPreference
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            new JsonSlurper().parseText(body).preferences.each { applyPreference(it.name, it) }
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: [type: 'bool', value: target]]])

        then:
        result.success == true
        result.changes.find { it.property == 'preference.logEnable' }
        model.settings.find { it.name == 'logEnable' }.value in [target?.toString(), target == null ? '' : target.toString()]

        where:
        [bypass, target] << [[false, true], [false, true, null]].combinations()
    }

    def 'clear cannot be confirmed after storage disappears from a successful fullJson fetch'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            model.remove('settings')
            model.remove('inputValues')
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: [type: 'bool', value: null]]])

        then:
        result.success == false
        !result.changes.find { it.property == 'preference.logEnable' }
        result.errors.find { it.property == 'preference.logEnable' }?.error?.toLowerCase()?.contains('confirm')
    }

    def 'all added device configuration properties are discoverable in the write schema'() {
        when:
        def definition = script.getAllToolDefinitions().find { it.name == 'hub_update_device' }

        then:
        definition.inputSchema.properties.keySet().containsAll(['deviceTypeId', 'zigbeeId', 'notes', 'maxEvents', 'maxStates',
            'spammyThreshold', 'defaultIcon', 'dashboardIds', 'meshEnabled', 'retryEnabled', 'meshFullSync',
            'homeKitEnabled', 'amazonAlexaEnabled', 'googleHomeEnabled', 'confirm'])
    }

    @Unroll
    def 'unavailable #property is rejected before any mixed-patch write'() {
        given:
        def model = fixture()
        if (scope == 'device') model.device.put(flag, false)
        else model.put(flag, false)
        registerFixture(model, true)
        def posts = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> posts << path; '' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> posts << path; [success: true] }

        when:
        script.toolUpdateDevice([deviceId: '10', label: 'Must not apply', confirm: true] + [(property): target])

        then:
        thrown(IllegalArgumentException)
        posts.empty

        where:
        property             | target | scope    | flag
        'meshEnabled'        | true   | 'device' | 'meshSelectionEnabled'
        'meshFullSync'       | true   | 'root'   | 'hubMeshRefreshEnabled'
        'retryEnabled'       | true   | 'root'   | 'commandRetrySelectionEnabled'
        'dashboardIds'       | [2]    | 'root'   | 'hasDashboards'
        'homeKitEnabled'     | false  | 'root'   | 'homeKitSelectionEnabled'
        'amazonAlexaEnabled' | true   | 'root'   | 'amazonAlexaInstalled'
        'googleHomeEnabled'  | false  | 'root'   | 'googleHomeSupported'
    }

    def 'multiple enum preferences use native JSON arrays and verify comma-separated saved values'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        def sent
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            sent = new JsonSlurper().parseText(body)
            model.settings.find { it.name == 'modes' }.value = sent.preferences[0].value.join(',')
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [modes: [type: 'enum', value: ['a', 'b']]]])

        then:
        sent.preferences[0].value == ['a', 'b']
        result.success == true
        result.changes.find { it.property == 'preference.modes' }
    }

    @Unroll
    def 'new form #property cannot claim success on an HTTP-success no-op'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true] + [(property): target])

        then:
        result.success == false
        !result.changes.find { it.property == property }
        result.errors.find { it.property == property }?.error?.contains('read back')

        where:
        property        | target
        'notes'         | 'New note'
        'maxEvents'     | 100
        'maxStates'     | 100
        'spammyThreshold' | 200
        'deviceTypeId'  | 101
        'defaultIcon'   | 'fa-star'
        'meshEnabled'   | true
        'retryEnabled'  | true
        'dashboardIds'  | [2]
    }

    @Unroll
    def 'bypass supports preference-pane #property with independent readback'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        def sent
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            sent = new JsonSlurper().parseText(body)
            model.device.put(property, target)
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10'] + [(property): target])

        then:
        result.success == true
        result.changes.find { it.property == property }?.newValue == target
        sent.get(property) == target

        where:
        property              | target
        'showOnHome'          | true
        'defaultCurrentState' | ''
    }

    def 'preference-only native retry availability uses commandRetry JSON key'() {
        given:
        def model = fixture()
        model.commandRetrySelectionEnabled = false
        model.device.retryAvailable = true
        registerFixture(model, true)
        def sent
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            sent = new JsonSlurper().parseText(body)
            model.device.retryEnabled = sent.commandRetry
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', retryEnabled: true])

        then:
        sent == [deviceId: 10, commandRetry: true]
        result.success == true
        result.changes.find { it.property == 'retryEnabled' }
    }

    @Unroll
    def 'clear cannot be confirmed from partial malformed preference storage in bypass=#bypass'() {
        given:
        def model = fixture()
        registerFixture(model, bypass)
        def writes = []
        def loseStorage = {
            writes << 'preference'
            model.settings.find { it.name == 'logEnable' }.remove('value')
            model.inputValues = [logEnable: 'ambiguous storage']
        }
        if (!bypass) childDevicesList[0].metaClass.updateSetting = { String name, setting -> loseStorage() }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            loseStorage()
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: [type: 'bool', value: null]]])

        then:
        writes == ['preference']
        result.success == false
        !result.changes.find { it.property == 'preference.logEnable' }
        result.errors.find { it.property == 'preference.logEnable' }?.status == 'unavailable'

        where:
        bypass << [false, true]
    }

    @Unroll
    def 'fresh form model missing #scope.#field is refused before POST in bypass=#bypass'() {
        given:
        def model = fixture()
        registerFixture(model, bypass)
        def reads = 0
        hubGet.register('/device/fullJson/10') {
            if (++reads == 2) {
                if (scope == 'device') model.device.remove(field)
                else model.remove(field)
            }
            JsonOutput.toJson(model)
        }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false ->
            forms << decodeForm(body)
            model.device.notes = 'New note'
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', notes: 'New note'])

        then:
        forms.empty
        result.success == false
        !result.changes.find { it.property == 'notes' }
        result.errors.find { it.property == 'notes' }?.error?.contains(field)

        where:
        [bypass, row] << [[false, true], [
            ['device', 'id'], ['device', 'version'], ['device', 'controllerType'],
            ['device', 'name'], ['device', 'label'], ['device', 'deviceNetworkId'],
            ['device', 'deviceTypeId'], ['device', 'deviceTypeReadableType'], ['device', 'zigbeeId'],
            ['device', 'roomId'], ['device', 'locationId'], ['device', 'hubId'], ['device', 'groupId'],
            ['device', 'maxEvents'], ['device', 'maxStates'], ['device', 'spammyThreshold'],
            ['device', 'meshEnabled'], ['device', 'retryEnabled'], ['device', 'meshFullSync'],
            ['device', 'tags'], ['device', 'defaultIcon'], ['device', 'notes'],
            ['root', 'homeKitEnabled'], ['root', 'dashboards']
        ]].combinations()
        scope = row[0]
        field = row[1]
    }

    @Unroll
    def 'invalid dashboard preservation source #dashboards is refused before a notes POST'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        def reads = 0
        hubGet.register('/device/fullJson/10') {
            if (++reads == 2) model.dashboards = dashboards
            JsonOutput.toJson(model)
        }
        def forms = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> forms << body; '' }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', notes: 'New note'])

        then:
        forms.empty
        result.success == false
        result.errors.find { it.property == 'notes' }?.error?.contains('dashboards')

        where:
        dashboards << [null, [:], [[id: 1]], [[selected: true]]]
    }

    def 'explicit nullable fields false and version zero survive a complete form save'() {
        given:
        def model = fixture()
        model.device.zigbeeId = null
        model.device.roomId = null
        model.device.label = null
        model.device.defaultIcon = null
        model.device.notes = null
        model.homeKitEnabled = false
        model.dashboards = []
        registerFixture(model, true)
        def form
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false ->
            form = decodeForm(body)
            model.device.maxEvents = 100
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', maxEvents: 100])

        then:
        result.success == true
        form.zigbeeId == ''
        form.roomId == ''
        form.label == ''
        form.defaultIcon == ''
        form.notes == ''
        form.dashboardIds == ''
        form.homeKitEnabled == 'false'
        form.meshEnabled == 'false'
        form.version == '0'
        form.groupId == '0'
    }

    @Unroll
    def 'nonempty defaultCurrentState needs authoritative attributes before a mixed update in bypass=#bypass'() {
        given:
        def model = fixture()
        if (shape != 'absent') model.device.currentStates = shape == 'null' ? null : []
        registerFixture(model, bypass)
        def writes = []
        if (!bypass) childDevicesList[0].metaClass.setLabel = { String label -> writes << 'label' }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false -> writes << path; '' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> writes << path; [success: true] }
        hubGet.register('/device/setDefaultCurrentState?id=10&currentState=switch') { writes << 'defaultCurrentState'; 'true' }

        when:
        script.toolUpdateDevice([deviceId: '10', label: 'Must not apply', defaultCurrentState: 'switch'])

        then:
        thrown(IllegalArgumentException)
        writes.empty

        where:
        [bypass, shape] << [[false, true], ['absent', 'null', 'list']].combinations()
    }

    @Unroll
    def 'linked component exposes native linked-target DNI selection in bypass=#bypass'() {
        given:
        def model = fixture()
        model.device.linkedDevice = true
        model.device.isComponent = true
        registerFixture(model, bypass)
        hubGet.register('/device/accessibleLinkedDevices') { JsonOutput.toJson([devices: [[hubId: 'source-hub', deviceId: 11, linkedLocally: false]]]) }
        def form
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 30, boolean r = false ->
            form = decodeForm(body)
            model.device.deviceNetworkId = form.deviceNetworkId
            ''
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true, deviceNetworkId: 'source-hub-11'])

        then:
        result.success == true
        form.deviceNetworkId == 'source-hub-11'
        result.changes.find { it.property == 'deviceNetworkId' }?.newValue == 'source-hub-11'

        where:
        bypass << [false, true]
    }

    def 'secret preference saved value is never echoed in the changes response'() {
        given:
        def model = fixture()
        model.settings << [name: 'apiToken', type: 'password', value: 'old-fixture-secret']
        registerFixture(model, true)
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            model.settings.find { it.name == 'apiToken' }.value = 'new-fixture-secret'
            [success: true]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [apiToken: [type: 'password', value: 'new-fixture-secret']]])

        then:
        result.success == true
        result.changes.find { it.property == 'preference.apiToken' }
        !JsonOutput.toJson(result).contains('new-fixture-secret')
        !JsonOutput.toJson(result).contains('old-fixture-secret')
    }
}
