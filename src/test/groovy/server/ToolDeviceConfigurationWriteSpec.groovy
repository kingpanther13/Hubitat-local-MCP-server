package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.TestDevice
import support.ToolSpecBase

/**
 * hub_update_device write contract: each device-page section is one native save (Device Info form,
 * Preferences pane, assistants, enable/disable, data values), every refusal fires before any write,
 * and one fullJson read-back after the saves decides each field's change or error.
 */
class ToolDeviceConfigurationWriteSpec extends ToolSpecBase {

    def setup() {
        script.metaClass.getRooms = { -> [[id: 7, name: 'Foyer']] }
    }

    private static Map fixture() {
        [device: [id: 10, version: 0, name: 'Fixture', label: 'Fixture', deviceNetworkId: 'fixture-10',
                  deviceTypeId: 100, deviceTypeReadableType: 'User', controllerType: 'VIRTUAL',
                  zigbeeId: '0011223344556677', roomId: 0, roomName: null, locationId: 1, hubId: 1, groupId: 0,
                  maxEvents: 10, maxStates: 20, spammyThreshold: 100, notes: '', tags: '',
                  defaultIcon: '', icon: 'fa-lightbulb', meshEnabled: false, retryEnabled: false,
                  meshFullSync: false, meshSelectionEnabled: true, linkedDevice: false,
                  showOnHome: false, defaultCurrentState: '', isComponent: false, disabled: false,
                  currentStates: [temperature: [value: '21'], switch: [value: 'on']],
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

    // The Device Info form the fixture produces when nothing is overridden.
    private static final Map BASE_FORM = [name: 'Fixture', label: 'Fixture', zigbeeId: '0011223344556677',
        maxEvents: '10', maxStates: '20', spammyThreshold: '100', deviceNetworkId: 'fixture-10',
        deviceTypeId: '100', deviceTypeReadableType: 'User', roomId: '0', meshEnabled: 'false',
        retryEnabled: 'false', meshFullSync: 'false', homeKitEnabled: 'on', locationId: '1', hubId: '1',
        groupId: '0', dashboardIds: '1', tags: '', defaultIcon: '', notes: '', id: '10', version: '0',
        controllerType: 'VIRTUAL']

    private void registerFixture(Map model, boolean bypass) {
        settingsMap.bypassDeviceAllowlist = bypass
        if (!bypass) {
            def device = new TestDevice(id: 10, name: 'Fixture', label: 'Fixture', deviceNetworkId: 'fixture-10')
            device.metaClass.updateSetting = { String name, setting -> throw new AssertionError('SDK preference setter must not execute') }
            device.metaClass.setLabel = { String value -> throw new AssertionError('SDK identity setter must not execute') }
            device.metaClass.updateDataValue = { String key, String value -> throw new AssertionError('SDK data writer must not execute') }
            childDevicesList << device
        }
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

    // Records every native save as [path, body]; onPost may apply it to the model and may return
    // the native response (a Map), otherwise the save is answered as accepted.
    private List stubPosts(Closure onPost = { path, body -> null }) {
        def posts = []
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean r = false ->
            def form = decodeForm(body)
            posts << [path: path, body: form]
            def response = onPost(path, form)
            response instanceof Map ? response : [status: 200, data: '']
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            def json = new JsonSlurper().parseText(body)
            posts << [path: path, body: json]
            def response = onPost(path, json)
            response instanceof Map ? response : [success: true]
        }
        posts
    }

    private int fullJsonReads() {
        hubGet.calls.count { it.path == '/device/fullJson/10' }
    }

    // ---- Device Info form ------------------------------------------------------------------

    @Unroll
    def 'Device Info save sends #property=#wire in one complete form that keeps every other value'() {
        given:
        def model = fixture()
        if (property == 'meshFullSync') model.device.linkedDevice = true
        registerFixture(model, false)
        def posts = stubPosts { path, form ->
            if (property == 'dashboardIds') model.dashboards.each { it.selected = target.contains(it.id) }
            else model.device.put(property, property == 'tags' ? form.tags : target)
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [(property): target], confirm: true])

        then:
        result.success == true
        result.changes*.property == [property]
        result.changes[0].newValue == target
        !result.errors
        posts.size() == 1
        posts[0].path == '/device/update'
        posts[0].body == BASE_FORM + [(property): wire]
        fullJsonReads() == 2
        model.device.data.integrationToken == 'fixture-token'

        where:
        property          | target                 | wire
        'label'           | ''                     | ''
        'name'            | 'Renamed'              | 'Renamed'
        'deviceNetworkId' | 'new-dni'              | 'new-dni'
        'notes'           | 'A & B\nC'             | 'A & B\nC'
        'maxEvents'       | 1                      | '1'
        'maxStates'       | 2000                   | '2000'
        'spammyThreshold' | 2000                   | '2000'
        'defaultIcon'     | 'fa-star'              | 'fa-star'
        'deviceTypeId'    | 101                    | '101'
        'zigbeeId'        | '8899AABBCCDDEEFF'     | '8899AABBCCDDEEFF'
        'meshEnabled'     | true                   | 'on'
        'meshFullSync'    | true                   | 'on'
        'dashboardIds'    | [2]                    | '2'
        'dashboardIds'    | []                     | ''
        'tags'            | ['kitchen', ' den ']   | 'kitchen,den'
    }

    def 'Device Info save leaves null values out and keeps explicit false and zero'() {
        given:
        def model = fixture()
        ['zigbeeId', 'roomId', 'label', 'defaultIcon', 'notes', 'tags', 'groupId', 'controllerType'].each { model.device.put(it, null) }
        model.homeKitEnabled = false
        model.dashboards = []
        registerFixture(model, true)
        def posts = stubPosts { path, form -> model.device.maxEvents = 100; null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [maxEvents: 100]])

        then:
        result.success == true
        def form = posts[0].body
        ['zigbeeId', 'roomId', 'label', 'defaultIcon', 'notes', 'tags', 'groupId', 'controllerType'].every { !form.containsKey(it) }
        form.dashboardIds == ''
        form.homeKitEnabled == 'false'
        form.meshEnabled == 'false'
        form.version == '0'
        form.maxEvents == '100'
    }

    @Unroll
    def 'room #room is saved as roomId #wire and verified by its room name'() {
        given:
        def model = fixture()
        model.device.roomId = 3
        model.device.roomName = 'Den'
        registerFixture(model, false)
        script.metaClass.getRooms = { -> [[id: 3, name: 'Den'], [id: 7, name: 'Foyer']] }
        def posts = stubPosts { path, form ->
            model.device.roomId = form.roomId.toInteger()
            model.device.roomName = [3: 'Den', 7: 'Foyer'][model.device.roomId]
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [room: room]])

        then:
        result.success == true
        posts*.path == ['/device/update']
        posts[0].body.roomId == wire
        !posts[0].body.containsKey('room')
        result.changes == [[property: 'room', oldValue: 'Den', newValue: newValue]]

        where:
        room    | wire | newValue
        'foyer' | '7'  | 'Foyer'
        '7'     | '7'  | 'Foyer'
        ''      | '0'  | 'none'
    }

    @Unroll
    def 'a Device Info save that #outcome marks every field in it as an error'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        def posts = stubPosts { path, form ->
            if (outcome == 'throws') throw new RuntimeException('connection reset')
            [status: 200, data: '{"status":"failure"}']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [label: 'Fixture', notes: '']])

        then: 'both fields already read back as requested, yet neither is reported as changed'
        posts.size() == 1
        result.success == false
        result.isError == true
        result.changes.empty
        result.errors*.property == ['label', 'notes']
        result.errors.every { it.error.contains(message) }
        !JsonOutput.toJson(result).contains('connection reset')

        where:
        outcome          | message
        'reports failure' | 'The hub refused the Device Info save'
        'throws'          | 'Device Info save failed'
    }

    @Unroll
    def 'incomplete native model (#field) refuses a Device Info save before any write'() {
        given:
        def model = fixture()
        mutate(model)
        registerFixture(model, false)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', fields: [notes: 'New note']])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)
        posts.empty

        where:
        field              | mutate                                                  | fragment
        'version'          | { Map m -> m.device.remove('version') }                 | 'missing version'
        'controllerType'   | { Map m -> m.device.remove('controllerType') }          | 'missing controllerType'
        'label'            | { Map m -> m.device.remove('label') }                   | 'missing label'
        'roomId'           | { Map m -> m.device.remove('roomId') }                  | 'missing roomId'
        'groupId'          | { Map m -> m.device.remove('groupId') }                 | 'missing groupId'
        'tags'             | { Map m -> m.device.remove('tags') }                    | 'missing tags'
        'null version'     | { Map m -> m.device.version = null }                    | 'incomplete'
        'homeKitEnabled'   | { Map m -> m.remove('homeKitEnabled') }                 | 'incomplete'
        'dashboards'       | { Map m -> m.remove('dashboards') }                     | 'incomplete'
        'dashboard id 0'   | { Map m -> m.dashboards = [[id: 0, selected: true]] }   | 'incomplete'
        'dashboard flag'   | { Map m -> m.dashboards = [[id: 1]] }                   | 'incomplete'
        'dashboard row'    | { Map m -> m.dashboards = ['1'] }                       | 'incomplete'
    }

    // ---- refusals before any write -------------------------------------------------------

    @Unroll
    def '#fragment is refused before the device is read'() {
        given:
        registerFixture(fixture(), false)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10'] + args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)
        posts.empty
        fullJsonReads() == 0

        where:
        args                                                      | fragment
        [label: 'Old top-level shape']                            | 'Unknown argument label'
        [:]                                                       | 'Nothing to update'
        [fields: [:], preferences: [:]]                           | 'Nothing to update'
        [fields: 'label']                                         | 'fields must be an object'
        [preferences: ['logEnable']]                              | 'preferences must be an object'
        [fields: [label: 'x'], confirm: 'yes']                    | 'confirm must be a boolean'
        [fields: [colour: 'red']]                                 | "Unknown field 'colour'"
        [fields: [maxEvents: 0]]                                  | 'maxEvents must be an integer from 1 to 2000'
        [fields: [maxStates: 1.5]]                                | 'maxStates must be an integer'
        [fields: [spammyThreshold: 99]]                           | 'spammyThreshold must be an integer from 100 to 2000'
        [fields: [deviceTypeId: 0], confirm: true]                | 'deviceTypeId must be a positive integer'
        [fields: [meshEnabled: 'false'], confirm: true]           | 'meshEnabled must be a boolean'
        [fields: [label: 5]]                                      | 'label must be a string'
        [fields: [name: ' ']]                                     | 'name cannot be empty'
        [fields: [tags: ['a,b']]]                                 | 'tags must be an array of strings without commas'
        [fields: [dashboardIds: ['one']], confirm: true]          | 'dashboardIds must be an array of dashboard IDs'
        [fields: [dataValues: [foo: 7]]]                          | 'dataValues must be a non-empty object of string values'
        [fields: [dataValues: [:]]]                               | 'dataValues must be a non-empty object of string values'
        [fields: [dataValues: [' ': 'x']]]                        | 'dataValues must be a non-empty object of string values'
        [fields: [room: true]]                                    | 'room must be a room name or id'
    }

    @Unroll
    def 'requiresConfirmation field #field needs confirm before the device is read'() {
        given:
        registerFixture(fixture(), false)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', fields: [label: 'Must not apply', (field): value]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        posts.empty
        fullJsonReads() == 0

        where:
        field                | value
        'deviceTypeId'       | 101
        'deviceNetworkId'    | 'new-identity'
        'zigbeeId'           | '8899AABBCCDDEEFF'
        'dashboardIds'       | [2]
        'meshEnabled'        | true
        'meshFullSync'       | true
        'homeKitEnabled'     | false
        'amazonAlexaEnabled' | true
        'googleHomeEnabled'  | false
    }

    @Unroll
    def '#fragment is refused against the native model before any write'() {
        given:
        def model = fixture()
        mutate(model)
        registerFixture(model, false)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', confirm: true, fields: [label: 'Must not apply'] + fields] + (prefs ? [preferences: prefs] : [:]))

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)
        posts.empty
        fullJsonReads() == 1

        where:
        fields                           | prefs                 | mutate                                        | fragment
        [dashboardIds: [999]]            | null                  | { Map m -> }                                  | 'unknown dashboard ID'
        [deviceTypeId: 999]              | null                  | { Map m -> }                                  | 'not an available driver ID'
        [defaultCurrentState: 'humidity'] | null                 | { Map m -> }                                  | "one of the device's current-state attributes"
        [defaultCurrentState: 'switch']  | null                  | { Map m -> m.device.remove('currentStates') } | 'Unable to read authoritative current-state attributes'
        [defaultCurrentState: 'switch']  | null                  | { Map m -> m.device.currentStates = [] }      | 'Unable to read authoritative current-state attributes'
        [room: 'Nowhere']                | null                  | { Map m -> }                                  | "Room 'Nowhere' not found"
        [homeKitEnabled: false]          | null                  | { Map m -> m.remove('googleHomeEnabled') }    | 'current assistant assignments'
        [:]                              | [doesNotExist: true]  | { Map m -> }                                  | "Unknown preference 'doesNotExist'"
        [:]                              | [logEnable: 'perhaps'] | { Map m -> }                                 | 'requires a boolean value'
        [:]                              | [offset: 11]          | { Map m -> }                                  | 'above its declared range'
        [deviceTypeId: 101]              | [logEnable: false]    | { Map m -> }                                  | 'Change the driver first'
    }

    @Unroll
    def '#field unavailable on this device is refused with the reason configuration reports'() {
        given:
        def model = fixture()
        mutate(model)
        registerFixture(model, true)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', confirm: true, fields: [notes: 'Must not apply', (field): value]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)
        posts.empty

        and: 'configuration reports the same field as not writable, with that reason'
        def entry = script.toolGetDevice('10', 'configuration').editableFields.find { it.name == field }
        entry.writable == false
        ex.message == "${field} cannot be changed: ${entry.reason}"

        where:
        field                | value              | mutate                                                                  | fragment
        'meshEnabled'        | true               | { Map m -> m.device.meshSelectionEnabled = false }                      | 'Hub Mesh sharing'
        'meshFullSync'       | true               | { Map m -> m.device.linkedDevice = true; m.hubMeshRefreshEnabled = false } | 'Full sync'
        'retryEnabled'       | true               | { Map m -> m.commandRetrySelectionEnabled = false }                     | 'Command retry'
        'dashboardIds'       | [2]                | { Map m -> m.hasDashboards = false }                                    | 'no dashboards'
        'homeKitEnabled'     | false              | { Map m -> m.homeKitSelectionEnabled = false }                          | 'HomeKit'
        'amazonAlexaEnabled' | true               | { Map m -> m.amazonAlexaInstalled = false }                             | 'Amazon Alexa'
        'googleHomeEnabled'  | false              | { Map m -> m.googleHomeSupported = false }                              | 'Google Home'
        'name'               | 'Renamed'          | { Map m -> m.device.isComponent = true }                                | 'component or linked'
        'label'              | 'Renamed'          | { Map m -> m.device.linkedAndDisabled = true }                          | 'keeps its label'
        'zigbeeId'           | '8899AABBCCDDEEFF' | { Map m -> m.device.zigbeeId = null }                                   | 'no Zigbee ID'
        'deviceNetworkId'    | 'new-dni'          | { Map m -> m.device.isComponent = true }                                | 'network ID from its parent'
    }

    def 'a linked device refuses driver preferences before any write'() {
        given:
        def model = fixture()
        model.device.linkedDevice = true
        registerFixture(model, true)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', fields: [notes: 'Must not apply'], preferences: [logEnable: false]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source hub')
        posts.empty
    }

    // ---- Preferences pane --------------------------------------------------------------

    @Unroll
    def 'pane save for #operation carries the other pane controls at their current values'() {
        given:
        def model = fixture()
        model.device.showOnHome = true
        model.device.retryEnabled = true
        model.device.defaultCurrentState = 'temperature'
        if (operation == 'retryEnabled') {
            model.commandRetrySelectionEnabled = false
            model.device.retryAvailable = true
        }
        registerFixture(model, false)
        def posts = stubPosts { path, body ->
            model.device.showOnHome = body.showOnHome
            model.device.retryEnabled = body.commandRetry
            model.device.defaultCurrentState = body.defaultCurrentState
            body.preferences.each { pref -> model.settings.find { it.name == pref.name }.value = pref.value.toString() }
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10'] + request)

        then:
        result.success == true
        !result.errors
        posts == [[path: '/device/preference/save', body: [deviceId: 10, defaultCurrentState: state,
                   commandRetry: retry, showOnHome: show, preferences: rows]]]
        fullJsonReads() == 2

        where:
        operation             | request                                       | state         | retry | show  | rows
        'showOnHome'          | [fields: [showOnHome: false]]                 | 'temperature' | true  | false | []
        'defaultCurrentState' | [fields: [defaultCurrentState: 'switch']]     | 'switch'      | true  | true  | []
        'a Status clear'      | [fields: [defaultCurrentState: '']]           | ''            | true  | true  | []
        'retryEnabled'        | [fields: [retryEnabled: false]]               | 'temperature' | false | true  | []
        'a preference'        | [preferences: [logEnable: false]]             | 'temperature' | true  | true  | [[name: 'logEnable', type: 'bool', value: false]]
    }

    @Unroll
    def 'unreadable current pane control #field=#invalid refuses the update before any write'() {
        given:
        def model = fixture()
        if (invalid == 'REMOVE') model.device.remove(field)
        else model.device.put(field, invalid)
        registerFixture(model, true)
        def posts = stubPosts()

        when: 'a Device Info field rides along, so a late check would already have posted the form'
        script.toolUpdateDevice([deviceId: '10', fields: [notes: 'New note'], preferences: [logEnable: false]])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('no update sent')
        posts.empty

        where:
        field                 | invalid
        'showOnHome'          | null
        'showOnHome'          | 'true'
        'showOnHome'          | 1
        'retryEnabled'        | 'false'
        'retryEnabled'        | []
        'defaultCurrentState' | 'REMOVE'
    }

    @Unroll
    def 'a pane save that #outcome is an error for every field in it, even when the values already match'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        settingsMap.mcpLogLevel = 'error'
        script.log.messages.clear()
        def posts = stubPosts { path, body ->
            if (outcome == 'throws') throw new RuntimeException('connection reset')
            outcome == 'is refused' ? [success: false, message: 'fixture-secret-in-response'] :
                [_unparseable: true, message: 'fixture-secret-in-response']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [showOnHome: false], preferences: [offset: 0]])

        then:
        posts*.path == ['/device/preference/save']
        result.success == false
        result.changes.empty
        result.errors*.property == ['showOnHome', 'preference.offset']
        result.errors.every { it.error.contains(message) }
        !JsonOutput.toJson(result).contains('fixture-secret-in-response')
        !script.log.messages.join('\n').contains('fixture-secret-in-response')
        script.log.messages.any { it.startsWith('error:[MCP1] ') && it.contains('ID: 10') }

        where:
        outcome            | message
        'is refused'       | 'The hub refused the preference save'
        'is unreadable'    | 'unreadable response'
        'throws'           | 'preference save failed'
    }

    @Unroll
    def 'enum preference #name=#value is sent as #wire'() {
        given:
        def model = fixture()
        model.settings << [name: 'profile', type: 'enum', multiple: false, options: [a: 'A', b: 'B'], value: 'a']
        registerFixture(model, true)
        def posts = stubPosts { path, body ->
            model.settings.find { it.name == name }.value = body.preferences[0].value
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [(name): [type: 'enum', value: value]]])

        then:
        posts[0].body.preferences == [[name: name, type: 'enum', value: wire]]
        result.success == true
        result.changes*.property == ["preference.${name}"]
        result.changes[0].newValue == [type: 'enum', value: value]

        where:
        name      | value      | wire
        'modes'   | ['a']      | '["a"]'
        'modes'   | ['a', 'b'] | '["a","b"]'
        'profile' | 'b'        | 'b'
    }

    // ---- assistants, data values ---------------------------------------------------------

    @Unroll
    def 'assistant #property save keeps the other assignments'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        def posts = stubPosts { path, body ->
            model.put(property, target)
            [success: true, assistants: [(property.replace('Enabled', '')): [success: true, enabled: target]]]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true, fields: [(property): target]])

        then:
        result.success == true
        result.changes*.property == [property]
        posts == [[path: '/device/updateAssistants',
                   body: [deviceId: 10, homeKitEnabled: true, amazonAlexaEnabled: false, googleHomeEnabled: true] + [(property): target]]]

        where:
        property             | target
        'homeKitEnabled'     | false
        'amazonAlexaEnabled' | true
        'googleHomeEnabled'  | false
    }

    def 'an assistant the integration refuses reports its reason while the other assignment lands'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        stubPosts { path, body ->
            model.homeKitEnabled = false
            [success: false, assistants: [homeKit: [success: true, enabled: false], amazonAlexa: [success: false, reason: 'integrationError']]]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true, fields: [homeKitEnabled: false, amazonAlexaEnabled: true]])

        then:
        result.success == false
        result.changes*.property == ['homeKitEnabled']
        result.errors*.property == ['amazonAlexaEnabled']
        result.errors[0].error == 'Assistant update failed: integrationError'
    }

    @Unroll
    def 'data values save through the driver for a #ownership device with bypass #bypass'() {
        given:
        def model = fixture()
        model.device.data.existing = 'unchanged'
        settingsMap.bypassDeviceAllowlist = bypass
        def sdk = new TestDevice(id: 10, name: 'Fixture')
        sdk.metaClass.updateDataValue = { String key, String value -> throw new AssertionError('SDK writer must not execute') }
        if (ownership == 'selected') settingsMap.selectedDevices = [sdk]
        if (ownership == 'child') childDevicesList << sdk
        hubGet.register('/device/fullJson/10') { JsonOutput.toJson(model) }
        def posts = stubPosts { path, body ->
            model.device.data.put(body.args[0].value, body.args[1].value)
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [dataValues: [probe: '0007']]])

        then:
        result.success == true
        posts == [[path: '/device/runmethod', body: [id: 10, method: 'updateDataValue',
                   args: [[type: 'STRING', value: 'probe'], [type: 'STRING', value: '0007']]]]]
        result.changes == [[property: 'dataValue.probe', newValue: '0007']]
        !JsonOutput.toJson(result).contains('fixture-token')
        fullJsonReads() == 2

        where:
        ownership  | bypass
        'selected' | false
        'child'    | false
        'unlisted' | true
    }

    def 'a data value the driver accepts without storing is an error that keeps secrets redacted'() {
        given:
        registerFixture(fixture(), false)
        stubPosts()

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [dataValues: [probe: '0007']]])

        then:
        result.success == false
        result.changes.empty
        result.errors*.property == ['dataValue.probe']
        result.errors[0].error.contains('reads back as')
        !JsonOutput.toJson(result).contains('fixture-token')
    }

    // ---- the single read-back ------------------------------------------------------------

    @Unroll
    def 'an accepted save that did not change #property is reported as a read-back error'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        stubPosts()

        when:
        def result = script.toolUpdateDevice([deviceId: '10', confirm: true, fields: [(property): target]])

        then:
        result.success == false
        result.isError == true
        result.changes.empty
        result.errors*.property == [property]
        result.errors[0].error.contains("${property} reads back as")
        result.message.contains('Updated 0 of 1')

        where:
        property          | target
        'notes'           | 'New note'
        'maxEvents'       | 100
        'deviceTypeId'    | 101
        'dashboardIds'    | [2]
        'tags'            | ['kitchen']
        'showOnHome'      | true
        'retryEnabled'    | true
        'defaultCurrentState' | 'switch'
    }

    @Unroll
    def 'a read-back that #failure confirms nothing'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        def reads = 0
        hubGet.register('/device/fullJson/10') {
            if (++reads == 1) return JsonOutput.toJson(model)
            if (failure == 'is empty') return ''
            if (failure == 'has no device') return '{"device":null}'
            if (failure == 'names another device') return JsonOutput.toJson(model + [device: model.device + [id: 11]])
            throw new IOException('unreachable')
        }
        def posts = stubPosts { path, body ->
            if (path == '/device/update') model.device.notes = 'New note'
            else model.device.showOnHome = true
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [notes: 'New note', showOnHome: true], preferences: [offset: 1]])

        then:
        posts*.path == ['/device/update', '/device/preference/save']
        reads == 2
        result.success == false
        result.changes.empty
        result.errors*.property == ['notes', 'showOnHome', 'preference.offset']
        result.errors.every { it.error.contains('could not be read back') }

        where:
        failure << ['is empty', 'has no device', 'names another device', 'throws']
    }

    @Unroll
    def 'a read-back whose #field is #shape does not confirm #wanted'() {
        given:
        def model = fixture()
        model.device.roomName = 'Foyer'
        model.device.roomId = 7
        registerFixture(model, false)
        script.metaClass.getRooms = { -> [[id: 7, name: 'Foyer']] }
        def reads = 0
        hubGet.register('/device/fullJson/10') {
            if (++reads == 1) return JsonOutput.toJson(model)
            def after = model + [device: new LinkedHashMap(model.device)]
            if (shape == 'missing') after.device.remove(field)
            else after.device.put(field, shape == 'garbage' ? 'unknown' : [:])
            JsonOutput.toJson(after)
        }
        stubPosts()

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [(property): wanted]])

        then:
        result.success == false
        result.changes.empty
        result.errors*.property == [property]
        result.errors[0].error.contains('could not be read back')

        where:
        property  | wanted | field      | shape
        'room'    | ''     | 'roomName' | 'missing'
        'room'    | ''     | 'roomName' | 'a map'
        'tags'    | []     | 'tags'     | 'missing'
        'enabled' | true   | 'disabled' | 'garbage'
        'enabled' | true   | 'disabled' | 'missing'
    }

    // ---- driver preferences ------------------------------------------------------------

    @Unroll
    def 'boolean preference declared #declaredType accepts supplied type #suppliedType'() {
        given:
        def model = fixture()
        model.settings.find { it.name == 'logEnable' }.type = declaredType
        registerFixture(model, false)
        def posts = stubPosts { path, body ->
            body.preferences.each { pref -> model.settings.find { it.name == pref.name }.value = pref.value.toString() }
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: [type: suppliedType, value: false]]])
        def read = script.toolGetDevice('10', 'configuration').preferences.find { it.name == 'logEnable' }

        then:
        result.success == true
        posts[0].body.preferences == [[name: 'logEnable', type: 'bool', value: false]]
        read.value == false
        read.valueStatus == 'stored'

        where:
        declaredType | suppliedType
        'boolean'    | 'boolean'
        'boolean'    | 'bool'
        'bool'       | 'boolean'
    }

    def 'several preferences ride one pane save and one read-back'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        def events = []
        hubGet.register('/device/fullJson/10') { events << 'read'; JsonOutput.toJson(model) }
        def posts = stubPosts { path, body ->
            events << 'post'
            body.preferences.each { pref -> model.settings.find { it.name == pref.name }.value = pref.value.toString() }
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: false, offset: 2]])

        then:
        result.success == true
        result.changes*.property == ['preference.logEnable', 'preference.offset']
        events == ['read', 'post', 'read']
        posts[0].body.preferences == [[name: 'logEnable', type: 'bool', value: false], [name: 'offset', type: 'number', value: 2]]
    }

    def 'a preference the save did not store is the only error among stored ones'() {
        given:
        def model = fixture()
        model.settings << [name: 'probeText', type: 'text', value: 'old']
        registerFixture(model, false)
        stubPosts { path, body ->
            body.preferences.each { pref ->
                if (pref.name != 'logEnable') model.settings.find { it.name == pref.name }.value = pref.value.toString()
            }
            [success: true, message: 'fixture-secret-in-response']
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: false, offset: 2, probeText: 'new']])

        then:
        result.success == false
        result.changes*.property == ['preference.offset', 'preference.probeText']
        result.errors*.property == ['preference.logEnable']
        result.errors[0].status == 'mismatch'
        !JsonOutput.toJson(result).contains('fixture-secret-in-response')
    }

    @Unroll
    def 'valid #name write that reads back as #savedValue reports a mismatch'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        stubPosts { path, body -> model.settings.find { it.name == name }.value = savedValue; null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [(name): wanted]])

        then:
        result.success == false
        result.changes.empty
        result.errors.find { it.property == "preference.${name}" }?.status == 'mismatch'

        where:
        name     | wanted | savedValue
        'offset' | 1      | '11'
        'modes'  | ['a']  | '["retired-option"]'
    }

    def 'an optional preference clear sends an empty value and is confirmed only once storage is unset'() {
        given:
        def model = fixture()
        model.settings << [name: 'probeText', type: 'text', value: 'original saved text', required: false]
        model.inputValues << [name: 'probeText', type: 'text', inputValue: 'original saved text']
        registerFixture(model, false)
        def posts = stubPosts { path, body ->
            def row = model.settings.find { it.name == 'probeText' }
            row.putAll([value: null, deviceId: null, id: null])
            model.inputValues.find { it.name == 'probeText' }.inputValue = 'declaration default'
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [probeText: [clear: true]]])

        then:
        posts == [[path: '/device/preference/save', body: [deviceId: 10, defaultCurrentState: '', commandRetry: false,
                   showOnHome: false, preferences: [[name: 'probeText', type: 'text', value: '']]]]]
        result.success == true
        result.changes*.property == ['preference.probeText']
        def after = script.toolGetDevice('10', 'configuration').preferences.find { it.name == 'probeText' }
        after.valuePresent == false
        after.valueStatus == 'unset'
    }

    @Unroll
    def 'a clear cannot be confirmed when the read-back preference storage #storage'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        def posts = stubPosts { path, body -> loseStorage(model); null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: [clear: true]]])

        then:
        posts.size() == 1
        result.success == false
        result.changes.empty
        result.errors.find { it.property == 'preference.logEnable' }?.status == 'unavailable'

        where:
        storage            | loseStorage
        'is missing'       | { Map m -> m.remove('settings'); m.remove('inputValues') }
        'is malformed'     | { Map m -> m.settings.find { it.name == 'logEnable' }.remove('value'); m.inputValues = [logEnable: 'ambiguous'] }
    }

    @Unroll
    def 'ambiguous #caseName preference patch is refused before any write'() {
        given:
        def model = fixture()
        model.settings << [name: 'optionalText', type: 'text', value: 'must remain', required: false]
        registerFixture(model, true)
        def posts = stubPosts()

        when:
        script.toolUpdateDevice([deviceId: '10', fields: [label: 'Must not apply'], preferences: [logEnable: false] + patch])

        then:
        thrown(IllegalArgumentException)
        posts.empty

        where:
        caseName                | patch
        'bare-null'             | [optionalText: null]
        'bare-empty-text'       | [optionalText: '']
        'bare-whitespace-text'  | [optionalText: ' \t ']
        'bare-empty-array'      | [modes: []]
        'typed-null'            | [optionalText: [type: 'text', value: null]]
        'typed-empty-array'     | [modes: [type: 'enum', value: []]]
        'wrong-declared-type'   | [optionalText: [type: 'number', value: '1']]
        'extra-key'             | [optionalText: [value: 'x', title: 'y']]
        'clear-and-value'       | [optionalText: [clear: true, value: 'replacement']]
        'clear-and-null'        | [optionalText: [clear: true, value: null]]
        'false-clear-and-value' | [optionalText: [clear: false, value: 'replacement']]
    }

    @Unroll
    def 'explicit #name clear is accepted while optional and refused while required'() {
        given:
        def model = fixture()
        def declaration = model.settings.find { it.name == name }
        declaration.required = false

        when:
        def planned = script._planDevicePreferences(model, [(name): [clear: true]], [:])

        then:
        planned.get(name).clear == true

        when:
        declaration.required = true
        script._planDevicePreferences(model, [(name): [clear: true]], [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Required preference')

        where:
        name << ['logEnable', 'offset', 'modes']
    }

    @Unroll
    def 'open-ended range 1..* accepts #value and still refuses its lower bound'() {
        given:
        def model = fixture()
        model.settings.find { it.name == 'offset' }.putAll([range: '1..*', value: '1'])

        when:
        def planned = script._planDevicePreferences(model, [offset: value], [:])

        then:
        planned.offset.value == value

        when:
        script._planDevicePreferences(model, [offset: 0], [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('below')

        where:
        value << [1, 1000000]
    }

    def 'an unset enum has unknown cardinality and a write without a hint is refused'() {
        given:
        def model = fixture()
        model.settings.find { it.name == 'modes' }.putAll([id: null, deviceId: null, value: null, multiple: false])
        registerFixture(model, false)
        def posts = stubPosts()

        when:
        def metadata = script._readDevicePreferenceModel(model).entries.find { it.name == 'modes' }

        then:
        metadata.multiple == null
        metadata.multipleStatus == 'unavailable'
        metadata.valueStatus == 'unset'

        when:
        script.toolUpdateDevice([deviceId: '10', fields: [label: 'must not change'], preferences: [modes: [value: ['a']]]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('multiple:true or multiple:false')
        posts.empty
    }

    @Unroll
    def 'explicit enum cardinality #multiple sets an unset preference without guessing'() {
        given:
        def model = fixture()
        model.settings.find { it.name == 'modes' }.putAll([id: null, deviceId: null, value: null, multiple: false])

        expect:
        script._planDevicePreferences(model, [modes: [value: value, multiple: multiple]], [:]).modes.value == value

        where:
        multiple | value
        true     | ['a', 'b']
        false    | 'a'
    }

    @Unroll
    def 'invalid cardinality hint #hint on #name is refused'() {
        when:
        script._planDevicePreferences(fixture(), [(name): hint], [:])

        then:
        thrown(IllegalArgumentException)

        where:
        name     | hint
        'modes'  | [value: ['a'], multiple: false]
        'modes'  | [value: ['a'], multiple: 'true']
        'modes'  | [clear: true, multiple: true]
        'modes'  | [value: ['unknown'], multiple: true]
        'offset' | [value: 1, multiple: true]
    }

    @Unroll
    def 'a #type clear is confirmed only when saved storage #storage is unset'() {
        given:
        def model = fixture()
        model.settings << ([name: 'clearTarget', type: type, multiple: multiple, required: false] + storage)
        def changes = []
        def errors = []

        when:
        script._verifyDevicePreferenceWrite('clearTarget', [type: type, clear: true, value: null], changes, errors,
            script._readDevicePreferenceModel(model))

        then:
        if (unset) {
            assert changes*.property == ['preference.clearTarget']
            assert errors.empty
        } else {
            assert changes.empty
            assert errors.find { it.property == 'preference.clearTarget' }?.status == 'mismatch'
        }

        where:
        type   | multiple | storage                                 | unset
        'text' | false    | [value: null]                           | false
        'text' | false    | [value: '']                             | false
        'text' | false    | [:]                                     | true
        'text' | false    | [value: null, id: null, deviceId: null] | true
        'enum' | true     | [value: null]                           | false
        'enum' | true     | [value: '[]']                           | false
        'enum' | true     | [value: '']                             | false
        'enum' | true     | [:]                                     | true
        'enum' | true     | [value: null, id: null, deviceId: null] | true
    }

    @Unroll
    def 'an empty multiselect is not confirmed by storage #storage'() {
        given:
        def model = fixture()
        def row = model.settings.find { it.name == 'modes' }
        row.remove('value')
        row.putAll(storage)
        def changes = []
        def errors = []

        when:
        script._verifyDevicePreferenceWrite('modes', [type: 'enum', value: []], changes, errors, script._readDevicePreferenceModel(model))

        then:
        changes.empty
        errors.find { it.property == 'preference.modes' }?.status == 'mismatch'

        where:
        storage << [[value: null], [:], [value: null, id: null, deviceId: null]]
    }

    def 'an undeclared stored row does not block a declared preference'() {
        given:
        def model = fixture()
        model.inputValues << [name: 'removedDriverPreference', type: 'text', inputValue: 'retain orphan']
        registerFixture(model, false)
        def posts = stubPosts { path, body ->
            body.preferences.each { pref -> model.settings.find { it.name == pref.name }.value = pref.value.toString() }
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [logEnable: false]])

        then:
        result.success == true
        posts[0].body.preferences*.name == ['logEnable']
        model.inputValues == [[name: 'removedDriverPreference', type: 'text', inputValue: 'retain orphan']]
    }

    def 'a secret preference value is never echoed in the result'() {
        given:
        def model = fixture()
        model.settings << [name: 'apiToken', type: 'password', value: 'old-fixture-secret']
        registerFixture(model, true)
        stubPosts { path, body -> model.settings.find { it.name == 'apiToken' }.value = 'new-fixture-secret'; null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [apiToken: [type: 'password', value: 'new-fixture-secret']]])

        then:
        result.success == true
        result.changes*.property == ['preference.apiToken']
        result.changes[0].newValue == [type: 'password', value: '[REDACTED]']
        !JsonOutput.toJson(result).contains('new-fixture-secret')
        !JsonOutput.toJson(result).contains('old-fixture-secret')
    }

    def 'with Hide passwords and credentials off, the configuration read shows a password preference as stored'() {
        given:
        def model = fixture()
        model.settings << [name: 'apiToken', type: 'password', value: 'fixture-secret']
        registerFixture(model, true)
        settingsMap.redactCredentials = false

        when:
        def result = script.toolGetDevice('10', 'configuration')

        then:
        def entry = result.preferences.find { it.name == 'apiToken' }
        entry.value == 'fixture-secret'
        !entry.containsKey('redacted')
    }

    def 'a failed secret preference update logs a safe ERROR summary'() {
        given:
        def model = fixture()
        model.settings << [name: 'apiToken', type: 'password', value: 'fixture-old-secret']
        registerFixture(model, true)
        settingsMap.mcpLogLevel = 'error'
        script.log.messages.clear()
        stubPosts { path, body -> throw new RuntimeException('fixture-exception-secret') }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [apiToken: 'fixture-new-secret']])
        def entries = script.log.messages.findAll { it.startsWith('error:[MCP1] ') }.collect {
            new JsonSlurper().parseText(it.substring(it.indexOf('{'))).entry
        }

        then:
        result.success == false
        entries.find { it.component == 'device' && it.message.contains('ID: 10') && it.message.contains('errors') }
        ['fixture-old-secret', 'fixture-new-secret', 'fixture-exception-secret'].every { !script.log.messages.join('\n').contains(it) }
    }

    // ---- schema ------------------------------------------------------------------------

    // ---- review follow-ups -------------------------------------------------------------------

    def 'a whole number sent as a decimal is posted as that integer'() {
        given:
        def model = fixture()
        registerFixture(model, false)
        def posts = stubPosts { path, form -> model.device.maxEvents = form.maxEvents as Integer; null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [maxEvents: 150.0]])

        then:
        posts*.body*.maxEvents == ['150']
        result.success == true
        result.changes*.property == ['maxEvents']
        result.changes[0].newValue == 150
    }

    @Unroll
    def 'room #room resolves by name before id, to #name'() {
        given:
        Map names = [1: 'Kitchen', 7: '1', 9: 'Den']
        script.metaClass.getRooms = { -> names.collect { id, name -> [id: id, name: name] } }
        def model = fixture()
        registerFixture(model, false)
        def posts = stubPosts { path, form ->
            model.device.roomId = form.roomId as Integer
            model.device.roomName = names[form.roomId as Integer]
            null
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [room: room]])

        then:
        posts[0].body.roomId == roomId
        result.success == true
        result.changes == [[property: 'room', oldValue: null, newValue: name]]

        where:
        room  | roomId | name
        '1'   | '7'    | '1'
        9     | '9'    | 'Den'
        'den' | '9'    | 'Den'
    }

    def "keeping a linked device's link ('0') is reported unchanged"() {
        given:
        def model = fixture()
        model.device.linkedDevice = true
        model.device.deviceNetworkId = 'hub-11'
        registerFixture(model, true)
        def posts = stubPosts()

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [deviceNetworkId: '0'], confirm: true])

        then:
        posts[0].body.deviceNetworkId == '0'
        result.success == true
        result.changes.empty
        result.unchanged == ['deviceNetworkId']
    }

    def 'a credential-named data value is echoed hidden'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        stubPosts { path, body -> model.device.data.put(body.args[0].value, body.args[1].value); null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [dataValues: [userCode: '4321', firmware: '1.2']]])

        then:
        result.success == true
        result.changes as Set == [[property: 'dataValue.userCode', newValue: '***redacted (password)***'],
                                  [property: 'dataValue.firmware', newValue: '1.2']] as Set
        !groovy.json.JsonOutput.toJson(result).contains('4321')
    }

    def 'a refused section fails only its own fields'() {
        given:
        def model = fixture()
        registerFixture(model, true)
        stubPosts { path, body ->
            if (path == '/device/update') { model.device.notes = body.notes; return null }
            [success: false]
        }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', fields: [notes: 'kept', showOnHome: true]])

        then:
        result.success == false
        result.changes*.property == ['notes']
        result.errors*.property == ['showOnHome']
        result.errors[0].error.contains('The hub refused the preference save')
    }

    @Unroll
    def 'preference #name already stored as #stored is #outcome'() {
        given:
        def model = fixture()
        model.settings.find { it.name == name }.value = stored
        registerFixture(model, true)
        stubPosts { path, body -> model.settings.find { it.name == name }.value = body.preferences[0].value.toString(); null }

        when:
        def result = script.toolUpdateDevice([deviceId: '10', preferences: [(name): [type: type, value: value]]])

        then:
        result.success == true
        (result.unchanged ?: []) == (outcome == 'unchanged' ? ["preference.${name}".toString()] : [])
        result.changes*.property == (outcome == 'unchanged' ? [] : ["preference.${name}".toString()])

        where:
        name        | type   | value  | stored  | outcome
        'logEnable' | 'bool' | false  | 'false' | 'unchanged'
        'modes'     | 'enum' | ['a']  | '["a"]' | 'unchanged'
        'modes'     | 'enum' | ['a']  | 'a'     | 'changed, since the driver reads a comma-joined value as one String'
    }

    def 'the write schema takes fields and preferences, and configuration lists every editable field'() {
        when:
        def schema = script.getAllToolDefinitions().find { it.name == 'hub_update_device' }.inputSchema
        def editable = script._deviceConfigurationEditableFields(fixture())
        def described = (schema.properties.fields.description =~ /editableFields name: (.*)\. Omitted/)[0][1]
        def listed = described.split(', ').collect { it.replaceAll(/ \(.*\)$/, '') } as Set

        then:
        schema.properties.keySet() == ['deviceId', 'fields', 'preferences', 'confirm'] as Set
        !schema.containsKey('additionalProperties')
        schema.required == ['deviceId']
        editable*.name as Set == script._deviceEditFields().keySet()
        listed == script._deviceEditFields().keySet()
        editable.findAll { it.requiresConfirmation }*.name as Set ==
            ['deviceTypeId', 'deviceNetworkId', 'zigbeeId', 'dashboardIds', 'meshEnabled', 'meshFullSync',
             'homeKitEnabled', 'amazonAlexaEnabled', 'googleHomeEnabled'] as Set
        editable.every { it.keySet().every { key -> key in ['name', 'type', 'minimum', 'maximum', 'requiresConfirmation',
            'writable', 'reason', 'value', 'options', 'optionsReference', 'optionsStatus'] } }
        editable.find { it.name == 'maxEvents' }.subMap(['minimum', 'maximum']) == [minimum: 1, maximum: 2000]
    }
}
