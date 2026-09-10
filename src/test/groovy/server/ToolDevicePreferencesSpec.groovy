package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.TestDevice
import support.ToolSpecBase

/**
 * Read-only device configuration/details contract. Fixtures are synthetic, but retain the
 * current-firmware fullJson nesting and scalar encodings captured for issue 403.
 */
class ToolDevicePreferencesSpec extends ToolSpecBase {

    private static final String DEVICE_ID = '901'

    private static Map fixture(String name = 'current-root-settings.json') {
        def stream = ToolDevicePreferencesSpec.class.getResourceAsStream("/device-details/${name}")
        assert stream != null: "Missing device-details fixture: ${name}"
        try {
            return new JsonSlurper().parseText(stream.getText('UTF-8')) as Map
        } finally {
            stream.close()
        }
    }

    private void addListedDevice() {
        childDevicesList << new TestDevice(
            id: 901,
            name: 'Synthetic Mutable Device Name',
            label: 'Synthetic Hall Climate',
            roomName: 'Synthetic Hall',
            capabilities: [[name: 'TemperatureMeasurement'], [name: 'Refresh']],
            supportedAttributes: [[name: 'temperature', dataType: 'NUMBER']],
            supportedCommands: [[name: 'refresh', arguments: null]],
            attributeValues: [temperature: '21.5']
        )
    }

    private void registerFixture(String id = DEVICE_ID, Map model = fixture()) {
        hubGet.register("/device/fullJson/${id}") { params -> JsonOutput.toJson(model) }
    }

    def "omitted and explicit summary preserve the seven-key response through native reads"() {
        given:
        addListedDevice()
        registerFixture()

        when:
        def omitted = script.toolGetDevice(DEVICE_ID)
        def explicit = script.toolGetDevice(DEVICE_ID, 'summary')

        then:
        omitted == explicit
        omitted.keySet() == ['id', 'name', 'label', 'room', 'capabilities', 'attributes', 'commands'] as Set
        !omitted.containsKey('mode')
        !omitted.containsKey('preferences')
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 2
    }

    def "unknown mode is rejected before authorization detail is fetched"() {
        given:
        addListedDevice()
        registerFixture()

        when:
        script.toolGetDevice(DEVICE_ID, 'expanded')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('summary')
        ex.message.contains('configuration')
        ex.message.contains('details')
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
    }

    def "configuration reads top-level declarations and preserves false zero empty unset and explicit null"() {
        given:
        addListedDevice()
        registerFixture()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.id == DEVICE_ID
        result.name == 'Synthetic Mutable Device Name'
        result.label == 'Synthetic Hall Climate'
        result.mode == 'configuration'
        result.preferenceRead == [status: 'complete', source: "/device/fullJson/${DEVICE_ID}"]
        result.deviceInfoRead.status == 'complete'
        result.deviceInfo.driver.name == 'Synthetic Environmental Driver'
        result.deviceInfo.driver.namespace == 'synthetic.example'
        result.name != result.deviceInfo.driver.name

        and: 'stored scalar values retain their declared types and presence'
        def prefs = result.preferences.collectEntries { [(it.name): it] }
        prefs.descriptionLogging.value == false
        prefs.descriptionLogging.valuePresent == true
        prefs.descriptionLogging.valueStatus == 'stored'
        prefs.temperatureOffset.value == 0
        prefs.temperatureOffset.valuePresent == true
        prefs.operatingProfile.value == ''
        prefs.operatingProfile.valuePresent == true
        prefs.operatingProfile.options == [eco: 'Economy', comfort: 'Comfort']
        prefs.declarationOnly.value == null
        prefs.declarationOnly.valuePresent == false
        prefs.declarationOnly.valueStatus == 'unset'
        prefs.declarationOnly.defaultValue == 'factory-default'
        prefs.explicitNull.value == null
        prefs.explicitNull.valuePresent == true
        prefs.explicitNull.valueStatus == 'stored'
        !prefs.containsKey('fixtureSection')

        and: 'one authorized configuration fetch is reused'
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "public configuration redacts password values and defaults while the internal model retains raw verification data"() {
        given:
        def fullJson = fixture()
        addListedDevice()
        registerFixture(DEVICE_ID, fullJson)

        when:
        def model = script._readDevicePreferenceModel(fullJson)
        def internal = script._lookupDevicePreference(model, 'apiToken')
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def exposed = result.preferences.find { it.name == 'apiToken' }

        then:
        internal.rawValue == 'synthetic-password-value'
        internal.value == 'synthetic-password-value'
        internal.defaultValue == 'synthetic-password-default'
        internal.valuePresent == true
        internal.valueStatus == 'stored'

        and:
        exposed.value == '***redacted (password)***'
        exposed.defaultValue == '***redacted (password)***'
        !result.toString().contains('synthetic-password-value')
        !result.toString().contains('synthetic-password-default')
    }

    def "preference reader prefers current top-level settings and rejects a nested-only speculative shape"() {
        given:
        def topLevel = fixture()
        topLevel.device.settings = [[name: 'descriptionLogging', type: 'bool', value: 'true']]
        def nested = fixture('unsupported-nested-device-settings.json')

        when:
        def preferred = script._readDevicePreferenceModel(topLevel)
        def fallback = script._readDevicePreferenceModel(nested)

        then:
        preferred.status == 'complete'
        preferred.source == 'settings'
        script._lookupDevicePreference(preferred, 'descriptionLogging').value == false
        fallback.status == 'unavailable'
        fallback.reason
        fallback.entries == []
    }

    def "known empty preferences are complete while absent or malformed sources are diagnostic failures"() {
        when:
        def empty = script._readDevicePreferenceModel([device: [id: 1], settings: [], inputValues: []])
        def absent = script._readDevicePreferenceModel([device: [id: 1], inputValues: []])
        def malformed = script._readDevicePreferenceModel([device: [id: 1], settings: [unexpected: true], inputValues: []])

        then:
        empty.status == 'complete'
        empty.entries == []
        absent.status == 'unavailable'
        absent.reason
        malformed.status in ['partial', 'unavailable']
        malformed.reason
        malformed.status != 'complete'
    }

    def "configuration authorizes a listed device before fetching fullJson"() {
        given:
        settingsMap.bypassDeviceAllowlist = false
        registerFixture()

        when:
        script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Device not found: ${DEVICE_ID}"
        !hubGet.calls.any { it.path.startsWith('/device/fullJson/') }
    }

    def "configuration reuses the bypass authorization fullJson instead of fetching it twice"() {
        given:
        settingsMap.bypassDeviceAllowlist = true
        registerFixture()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.id == DEVICE_ID
        result.preferenceRead.status == 'complete'
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "configuration remains reachable through the read gateway with Write off and no BPS key"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        addListedDevice()
        registerFixture()

        when:
        def response = mcpDriver.callTool('hub_get_device', [deviceId: DEVICE_ID, mode: 'configuration'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.mode == 'configuration'
        inner.preferenceRead.status == 'complete'
        inner.preferences.find { it.name == 'descriptionLogging' }.value == false
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "details returns only selected sections and configuration shares the normalized preference model"() {
        given:
        addListedDevice()
        registerFixture()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details', ['configuration', 'identity'])

        then:
        result.id == DEVICE_ID
        result.mode == 'details'
        result.sections.keySet() == ['configuration', 'identity'] as Set
        !result.containsKey('preferences')
        !result.containsKey('editableFields')
        result.sections.configuration.preferenceRead.status == 'complete'
        result.sections.configuration.preferences.find { it.name == 'descriptionLogging' }.value == false
        result.sections.identity.driver.name == 'Synthetic Environmental Driver'
        !result.sections.containsKey('attributes')
        !result.sections.containsKey('commands')
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "every writable property has explicit read coverage without losing false empty or redacted values"() {
        given:
        addListedDevice()
        registerFixture()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def schema = script.getAllToolDefinitions().find { it.name == 'hub_update_device' }.inputSchema.properties
        def fields = result.editableFields.collectEntries { [(it.name): it] }

        then:
        fields.keySet() == (schema.keySet() - ['deviceId', 'confirm', 'bestPracticeKey']) as Set
        fields.label.value == 'Synthetic Hall Climate'
        fields.deviceNetworkId.value == 'SYNTHETIC-DNI-901'
        fields.room.value == 'Synthetic Hall'
        fields.enabled.value == true
        fields.showOnHome.value == false
        fields.defaultCurrentState.value == ''
        fields.tags.value == []
        fields.preferences.valueReference == 'preferences'
        fields.deviceTypeId.optionsReference.args.include == 'all'
        fields.deviceTypeId.requiresConfirmation == true
        fields.meshEnabled.value == false
        fields.googleHomeEnabled.value == false
        !result.toString().contains('synthetic-device-data-secret')
    }

    def "linked device preferences remain readable but point writes to the source device"() {
        given:
        addListedDevice()
        def full = fixture()
        full.device.linkedDevice = true
        registerFixture(DEVICE_ID, full)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def preferenceField = result.editableFields.find { it.name == 'preferences' }
        def preferences = result.preferences.collectEntries { [(it.name): it] }

        then:
        preferenceField.valueReference == 'preferences'
        preferenceField.valuePresent
        preferenceField.readStatus == 'complete'
        !preferenceField.applicable
        !preferenceField.writable
        preferenceField.reason == 'Driver preferences cannot be saved on a linked device; edit the source device.'

        and: 'stored values, types, and defaults remain available for inspection'
        preferences.descriptionLogging.value == false
        preferences.descriptionLogging.type == 'bool'
        preferences.descriptionLogging.defaultValue == true
        preferences.declarationOnly.valueStatus == 'unset'
        preferences.declarationOnly.defaultValue == 'factory-default'
        preferences.values().every {
            it.applicable == false && it.writable == false &&
                it.reason == 'Driver preferences cannot be saved on a linked device; edit the source device.'
        }
    }

    def "linked device target options expose native local-link exclusions"() {
        given:
        addListedDevice()
        def full = fixture()
        full.device.linkedDevice = true
        registerFixture(DEVICE_ID, full)
        hubGet.register('/device/accessibleLinkedDevices') { params ->
            JsonOutput.toJson([devices: [
                [hubId: 2001, deviceId: 41, label: 'Already linked here', linkedLocally: true],
                [hubId: 2001, deviceId: 42, name: 'Available remote target', linkedLocally: false]
            ]])
        }

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def networkId = result.editableFields.find { it.name == 'deviceNetworkId' }

        then:
        networkId.options == [
            [value: '0', label: 'Keep current device link'],
            [value: '2001-41', label: 'Already linked here', disabled: true],
            [value: '2001-42', label: 'Available remote target', disabled: false]
        ]
    }

    @spock.lang.Unroll
    def "linked target choices #failureKind remain unavailable with a safe ERROR"() {
        given:
        addListedDevice()
        def full = fixture()
        full.device.linkedDevice = true
        registerFixture(DEVICE_ID, full)
        hubGet.register('/hub2/userDeviceTypes') { '[]' }
        hubGet.register('/device/accessibleLinkedDevices') {
            if (failureKind == 'fetch') throw new RuntimeException('fixture-linked-secret')
            body
        }
        settingsMap.mcpLogLevel = 'error'
        script.log.messages.clear()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def networkId = result.editableFields.find { it.name == 'deviceNetworkId' }
        def errors = script.log.messages.findAll { it.startsWith('error:[MCP1] ') }

        then:
        noExceptionThrown()
        networkId.optionsStatus == 'unavailable'
        networkId.reason.contains('retry')
        errors.any { it.contains('/device/accessibleLinkedDevices') && it.contains(DEVICE_ID) }
        !JsonOutput.toJson(result).contains('fixture-linked-secret')
        !script.log.messages.join('\n').contains('fixture-linked-secret')

        where:
        failureKind   | body
        'fetch'       | null
        'invalid-json'| '<html>fixture-linked-secret</html>'
        'wrong-shape' | '{"devices":"fixture-linked-secret"}'
    }

    def "native fetch failure reports unavailable discovery without falling back to SDK identity"() {
        given:
        addListedDevice()
        hubGet.register("/device/fullJson/${DEVICE_ID}") { throw new RuntimeException('offline') }

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.id == DEVICE_ID
        result.name == null
        result.label == "Device ${DEVICE_ID}"
        result.preferenceRead.status == 'unavailable'
        result.preferenceRead.reason
        result.deviceInfoRead.status == 'unavailable'
        result.editableFields.every { !it.writable }
    }

    def "native unset storage does not promote the UI default into a saved #kind preference"() {
        given:
        def full = [device: [id: 901], settings: [[name: 'cleared', type: kind,
            id: null, deviceId: null, value: null, defaultValue: nativeDefault]],
            inputValues: [[name: 'cleared', inputValue: nativeDefault]]]

        when:
        def model = script._readDevicePreferenceModel(full)
        def entry = script._lookupDevicePreference(model, 'cleared')

        then:
        model.status == 'complete'
        entry.valuePresent == false
        entry.valueStatus == 'unset'
        entry.value == null
        entry.defaultValue == typedDefault

        where:
        kind     | nativeDefault         | typedDefault
        'text'   | 'declaration default' | 'declaration default'
        'bool'   | 'true'                | true
        'number' | '7'                   | 7
    }

    def "input values match by name and conflicting malformed storage never reports complete"() {
        given:
        def full = [device: [id: 1], settings: [
            [name: 'number', type: 'number', value: '9.5'], [name: 'boolean', type: 'bool', value: 'true']],
            inputValues: [[name: 'boolean', inputValue: 'false'], [name: 'number', inputValue: '0']]]

        when:
        def model = script._readDevicePreferenceModel(full)
        full.inputValues << [name: 'boolean', inputValue: 'true']
        def ambiguous = script._readDevicePreferenceModel(full)

        then:
        model.status == 'complete'
        script._lookupDevicePreference(model, 'number').value == 0
        script._lookupDevicePreference(model, 'boolean').value == false
        ambiguous.status == 'partial'
        script._lookupDevicePreference(ambiguous, 'boolean').valueStatus == 'unknown'
    }

    def "details exposes non-editable metadata and a verified source ID distinct from the device type ID"() {
        given:
        addListedDevice()
        registerFixture()
        hubGet.register('/hub2/userDeviceTypes') {
            '[{"id":808,"name":"Synthetic Environmental Driver","namespace":"synthetic.example"}]'
        }

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details')

        then:
        result.sections.identity.driver.deviceTypeId == 77
        result.sections.configuration.driverSource == [status: 'available', gateway: 'hub_read_apps_code',
            tool: 'hub_get_source', args: [type: 'driver', id: '808']]
        result.sections.identity.lastActivityTime == '2026-09-08T09:00:00.000-0400'
        result.sections.attributes.currentStates.temperature.unit == 'C'
        result.sections.attributes.declaredAttributes[0].name == 'temperature'
        result.sections.attributes.declaredAttributes[0].dataType == 'NUMBER'
        result.sections.attributes.declaredAttributes[0].value == 21.5
        result.sections.attributes.attributeCoverage.source == 'device.currentStates'
        result.sections.attributes.attributeCoverage.declarationsComplete == false
        result.sections.relationships.parentApp.id == 301
        result.sections.relationships.appsUsing[0].id == 501
        result.sections.state.health == 'online'
        result.sections.jobs[0].name == 'syntheticRefresh'
        result.references.events.args.deviceId == DEVICE_ID
        !result.toString().contains('synthetic-device-data-secret')
    }

    @spock.lang.Unroll
    def "driver catalog #failureKind is unavailable rather than an identity mismatch and logs safely"() {
        given:
        addListedDevice()
        registerFixture()
        hubGet.register('/hub2/userDeviceTypes') {
            if (failureKind == 'fetch') throw new RuntimeException('fixture-catalog-secret')
            body
        }
        settingsMap.mcpLogLevel = 'error'
        script.log.messages.clear()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')
        def errors = script.log.messages.findAll { it.startsWith('error:[MCP1] ') }

        then:
        noExceptionThrown()
        result.driverSource.status == 'unavailable'
        result.driverSource.reason.toLowerCase().contains('catalog')
        !result.driverSource.reason.contains('No unique')
        result.driverSource.lookup.tool == 'hub_list_drivers'
        errors.any { it.contains('/hub2/userDeviceTypes') && it.contains(DEVICE_ID) }
        !JsonOutput.toJson(result).contains('fixture-catalog-secret')
        !script.log.messages.join('\n').contains('fixture-catalog-secret')

        where:
        failureKind    | body
        'fetch'        | null
        'invalid-json' | '<html>fixture-catalog-secret</html>'
        'wrong-shape'  | '{"drivers":"fixture-catalog-secret"}'
        'empty-body'   | ''
    }

    def "successfully read empty driver catalog remains unresolved without ERROR"() {
        given:
        addListedDevice()
        registerFixture()
        hubGet.register('/hub2/userDeviceTypes') { '[]' }
        settingsMap.mcpLogLevel = 'error'
        script.log.messages.clear()

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.driverSource.status == 'unresolved'
        result.driverSource.reason.contains('No unique')
        result.driverSource.lookup.tool == 'hub_list_drivers'
        !script.log.messages.any { it.startsWith('error:[MCP1] ') }
    }

    def "duplicate user driver identity resolves through exact usedBy device membership"() {
        given:
        addListedDevice()
        registerFixture()
        hubGet.register('/hub2/userDeviceTypes') {
            JsonOutput.toJson([
                [id: 808, name: 'Synthetic Environmental Driver', namespace: 'synthetic.example',
                    usedBy: [[id: 900, name: 'Another device']]],
                [id: 809, name: 'Synthetic Environmental Driver', namespace: 'synthetic.example',
                    usedBy: [[id: 901, name: 'Synthetic Hall Climate']]]
            ])
        }

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.driverSource == [status: 'available', gateway: 'hub_read_apps_code',
            tool: 'hub_get_source', args: [type: 'driver', id: '809']]
    }

    @spock.lang.Unroll
    def "duplicate user driver identity with #membership remains unresolved"() {
        given:
        addListedDevice()
        registerFixture()
        hubGet.register('/hub2/userDeviceTypes') {
            JsonOutput.toJson([
                [id: 808, name: 'Synthetic Environmental Driver', namespace: 'synthetic.example', usedBy: firstUsedBy],
                [id: 809, name: 'Synthetic Environmental Driver', namespace: 'synthetic.example', usedBy: secondUsedBy]
            ])
        }

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.driverSource.status == 'unresolved'
        !result.driverSource.containsKey('args')
        result.driverSource.lookup.tool == 'hub_list_drivers'

        where:
        membership                   | firstUsedBy                          | secondUsedBy
        'no exact device membership' | []                                   | []
        'ambiguous exact membership' | [[id: 901, name: 'First instance']]  | [[id: '901', name: 'Second instance']]
    }

    def "new native device fields make incomplete details coverage visible"() {
        given:
        addListedDevice()
        def full = fixture()
        full.device.futureFirmwareField = 'new information'
        registerFixture(DEVICE_ID, full)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details')

        then:
        result.sourceCoverage.status == 'partial'
        result.sourceCoverage.unmappedDeviceFields == ['futureFirmwareField']
    }

    def "details redacts secret attribute records from the listed device path"() {
        given:
        childDevicesList << new TestDevice(id: 901, name: 'Fixture')
        def nativeModel = fixture()
        nativeModel.device.currentStates = [apiToken: [name: 'apiToken', value: 'private-attribute-value', dataType: 'STRING']]
        nativeModel.remove('commands')
        registerFixture(DEVICE_ID, nativeModel)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details', ['attributes'])

        then:
        !JsonOutput.toJson(result).contains('private-attribute-value')
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
        result.sections.attributes.declaredAttributes[0].name == 'apiToken'
        result.sections.attributes.declaredAttributes[0].value == '***redacted (password)***'
    }

    def "empty multiple enum storage is a saved empty selection while explicit null remains null"() {
        given:
        def full = [device: [id: 1], settings: [[name: 'choices', type: 'enum', multiple: true, value: '']], inputValues: []]

        when:
        def empty = script._lookupDevicePreference(script._readDevicePreferenceModel(full), 'choices')
        full.settings[0].value = null
        def explicitNull = script._lookupDevicePreference(script._readDevicePreferenceModel(full), 'choices')

        then:
        empty.value == []
        empty.valuePresent
        empty.valueStatus == 'stored'
        explicitNull.value == null
        explicitNull.valuePresent
    }

    @spock.lang.Unroll
    def "JSON-array multiple enum storage preserves selection and defaults for #wire"() {
        given:
        def full = [device: [id: 1], settings: [[name: 'choices', type: 'enum', multiple: true,
            value: wire, defaultValue: wire]], inputValues: [[name: 'choices', inputValue: wire]]]

        when:
        def model = script._readDevicePreferenceModel(full)
        def entry = script._lookupDevicePreference(model, 'choices')

        then:
        model.status == 'complete'
        entry.valueStatus == 'stored'
        entry.valuePresent
        entry.value == expected
        entry.defaultValue == expected

        where:
        wire                       | expected
        '["red"]'                  | ['red']
        '["red", "blue"]'          | ['red', 'blue']
        '[]'                       | []
        '["red,blue", "two words"]' | ['red,blue', 'two words']
    }

    def "missing and malformed native sections cannot masquerade as complete empty information"() {
        given:
        addListedDevice()
        def full = fixture()
        full.scheduledJobs = 'unexpected'
        ['parentApp', 'childDevices', 'appsUsing', 'appsUsingCount', 'appsUsingForDialog', 'hasChildren'].each { full.remove(it) }
        full.remove('amazonAlexaEnabled')
        registerFixture(DEVICE_ID, full)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details', ['jobs', 'relationships', 'integrations'])

        then:
        result.sectionRead.jobs.status in ['partial', 'unavailable']
        result.sectionRead.relationships.status in ['partial', 'unavailable']
        result.sectionRead.integrations.status in ['partial', 'unavailable']
        result.sectionRead.values().every { it.reason }
    }

    def "field discovery and projection keep individually large sections reachable below the response cap"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [first: 'a' * 60000, second: 'b' * 60000, third: 'c' * 60000]
        full.settings.each { it.description = 'd' * 25000 }
        registerFixture(DEVICE_ID, full)

        when:
        def index = script.toolGetDevice(DEVICE_ID, 'details', ['state'], [])
        def part = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['second'])
        def configuration = script.toolGetDevice(DEVICE_ID, 'configuration', null, ['descriptionLogging'])

        then:
        index.availableFields.state == ['first', 'second', 'third']
        index.sections.state == [:]
        part.sections.state == [second: 'b' * 60000]
        configuration.preferences*.name == ['descriptionLogging']
        JsonOutput.toJson(index).getBytes('UTF-8').length < 4000
        JsonOutput.toJson(part).getBytes('UTF-8').length < 100000
        JsonOutput.toJson(configuration).getBytes('UTF-8').length < 100000
    }

    def "native string dashboard availability remains an editable control"() {
        given:
        addListedDevice()
        def full = fixture()
        full.hasDashboards = 'true'
        registerFixture(DEVICE_ID, full)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'configuration')

        then:
        result.editableFields.find { it.name == 'dashboardIds' }.applicable
        result.editableFields.find { it.name == 'dashboardIds' }.writable
    }

    def "oversized single values are completely reachable through bounded JSON fragment cursors"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: ('quote \" slash \\ newline\n caf\u00e9 ' * 12000)]
        registerFixture(DEVICE_ID, full)

        when:
        def result = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])
        def chunks = []
        def pages = []
        int pageCount = 0
        while (true) {
            pages << result
            chunks << result.content
            if (!result.nextCursor || ++pageCount > 100) break
            result = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], result.nextCursor)
        }
        def assembled = new JsonSlurper().parseText(chunks.join(''))

        then:
        pages.size() > 1
        pages.every { it.contentFormat == 'json-fragment' }
        pages.every { page ->
            def envelope = [jsonrpc: '2.0', id: 1, result: [content: [[type: 'text', text: JsonOutput.toJson(page)]]]]
            JsonOutput.toJson(envelope).getBytes('UTF-8').length < 100000
        }
        !result.nextCursor
        assembled.sections.state.large == full.deviceState.large
    }

    def "fragment cursor completes its original snapshot while native state changes"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * 180000]
        registerFixture(DEVICE_ID, full)
        def first = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])
        assert first.nextCursor
        full.deviceState.large = 'y' * 180000

        when:
        def chunks = [first.content]
        def cursor = first.nextCursor
        while (cursor) {
            def page = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], cursor)
            chunks << page.content
            cursor = page.nextCursor
        }
        def assembled = new JsonSlurper().parseText(chunks.join(''))

        then:
        assembled.sections.state.large == 'x' * 180000
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "an empty cursor preserves the normal small configuration shape"() {
        given:
        addListedDevice()
        registerFixture()

        expect:
        script.toolGetDevice(DEVICE_ID, 'configuration', null, null, '') ==
            script.toolGetDevice(DEVICE_ID, 'configuration')
    }

    def "configuration and details redact the native psw convention without hiding ordinary values"() {
        given:
        addListedDevice()
        def full = fixture()
        full.settings << [name: 'psw', type: 'text', value: 'sensitive-stored', defaultValue: 'sensitive-default']
        full.deviceState = [psw: 'sensitive-state', harmless: 'public']
        registerFixture(DEVICE_ID, full)

        when:
        def configuration = script.toolGetDevice(DEVICE_ID, 'configuration')
        def details = script.toolGetDevice(DEVICE_ID, 'details', ['state'])

        then:
        def preference = configuration.preferences.find { it.name == 'psw' }
        preference.value == '***redacted (password)***'
        preference.defaultValue == '***redacted (password)***'
        details.sections.state.psw == '***redacted (password)***'
        details.sections.state.harmless == 'public'
    }

    def "string false flags preserve standalone identity applicability"() {
        given:
        addListedDevice()
        def full = fixture()
        full.device.linkedDevice = 'false'
        full.device.isComponent = 'false'
        full.device.linkedAndDisabled = 'false'
        full.device.zigbeeId = '0200000000411001'
        registerFixture(DEVICE_ID, full)

        when:
        def fields = script.toolGetDevice(DEVICE_ID, 'configuration').editableFields

        then:
        ['name', 'label', 'deviceTypeId', 'deviceNetworkId', 'zigbeeId', 'preferences'].every { name ->
            fields.find { it.name == name }.applicable
        }
    }

    @spock.lang.Unroll
    def "Zigbee ID #shape read applicability agrees with write prevalidation"() {
        given:
        addListedDevice()
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = System.currentTimeMillis()
        def full = fixture()
        full.device.isComponent = false
        full.device.linkedDevice = false
        if (present) full.device.put('zigbeeId', nativeId)
        else full.device.remove('zigbeeId')
        registerFixture(DEVICE_ID, full)

        when:
        def field = script.toolGetDevice(DEVICE_ID, 'configuration').editableFields.find { it.name == 'zigbeeId' }
        def prepared = null
        def refusal = null
        try {
            prepared = script._prepareDeviceUpdatePatch(
                [deviceId: DEVICE_ID, zigbeeId: '0200000000411002', confirm: true], DEVICE_ID, full)
        } catch (IllegalArgumentException ex) {
            refusal = ex.message
        }

        then:
        field.applicable == editable
        field.writable == editable
        if (editable) {
            assert refusal == null
            assert prepared.args.zigbeeId == '0200000000411002'
        } else {
            assert prepared == null
            assert refusal?.contains('zigbeeId')
        }

        where:
        shape    | present | nativeId           | editable
        'absent' | false   | null               | false
        'null'   | true    | null               | false
        'empty'  | true    | ''                 | false
        'hex'    | true    | '0200000000411001' | true
    }

    def "fragment snapshots expire with restart guidance without refetching unrelated native data"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * 180000]
        registerFixture(DEVICE_ID, full)
        long clock = 1000000L
        NOW_OVERRIDE.set({ -> clock })
        def first = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])
        clock += 301000L

        when:
        script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], first.nextCursor)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('restart')
        hubGet.calls.count { it.path == "/device/fullJson/${DEVICE_ID}" } == 1
    }

    def "fragment snapshot cannot bypass a revoked device authorization"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * 180000]
        registerFixture(DEVICE_ID, full)
        def first = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])
        childDevicesList.clear()
        settingsMap.bypassDeviceAllowlist = false

        when:
        script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], first.nextCursor)

        then:
        thrown(IllegalArgumentException)
    }

    @spock.lang.Unroll
    def "snapshot eviction bounds #payloadLength character pages and preserves the newest readable snapshot"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * payloadLength]
        registerFixture(DEVICE_ID, full)
        def first = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])
        def newest
        9.times { newest = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large']) }

        when:
        def next = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], newest.nextCursor)
        def snapshots = scriptStaticField('DEVICE_READ_SNAPSHOTS') as Map

        then:
        next.offset == 18000
        snapshots.size() <= 8
        snapshots.values().sum { it.content.length() } <= 2097152

        when:
        script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], first.nextCursor)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('restart')

        where:
        payloadLength << [100000, 400000]
    }

    def "snapshot budget includes retained caller selection metadata"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * 250000]
        registerFixture(DEVICE_ID, full)
        def fields = ['large', 'unknown_' + ('x' * 60000)]
        def newest
        8.times { newest = script.toolGetDevice(DEVICE_ID, 'details', ['state'], fields) }

        when:
        def snapshots = scriptStaticField('DEVICE_READ_SNAPSHOTS') as Map
        def retainedCharacters = snapshots.values().sum { it.content.length() + it.selection.length() }
        def continued = script.toolGetDevice(DEVICE_ID, 'details', ['state'], fields, newest.nextCursor)

        then:
        retainedCharacters <= 2097152
        continued.offset == 18000

        when: 'bounding selection metadata must preserve its argument binding'
        script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'], newest.nextCursor)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('selection')
    }

    def "one over-budget expanded result fails with narrower-selection guidance without caching it"() {
        given:
        // The public read/continuation cases above exercise native fetching; isolate the hard allocation bound here.
        def expanded = [id: DEVICE_ID, mode: 'details', sections: [state: [large: 'x' * 2200000]]]

        when:
        script._deviceReadPage(expanded, 'oversized-selection')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Select fewer')
        (scriptStaticField('DEVICE_READ_SNAPSHOTS') as Map).isEmpty()
    }

    def "fragment continuation is bound to the original field selection"() {
        given:
        addListedDevice()
        def full = fixture()
        full.deviceState = [large: 'x' * 180000, other: 'y' * 180000]
        registerFixture(DEVICE_ID, full)
        def first = script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['large'])

        when:
        script.toolGetDevice(DEVICE_ID, 'details', ['state'], ['other'], first.nextCursor)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('selection')
    }
}
