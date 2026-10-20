package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

class NativeFormsSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 1L, label: 'MCP')
    Map model
    List calls
    List replies
    List devices

    void setupSpec() {
        appExecutor.getApp() >> serverApp
    }

    void setup() {
        calls = []
        replies = []
        devices = [[id: 7, label: 'Fixture one'], [id: 8, label: 'Fixture two']]
        model = [
            app: [id: 42, version: 4], formAction: 'update', pageBreadcrumbs: '%5B%22main%22%5D',
            paramsForPage: [row: 3, text: 'a+b & c'], cancelButton: true, doneButton: true,
            settings: [enabled: false, count: 0, credential: 'private-password', devices: ['7': 'Fixture one']],
            configPage: [name: 'edit', title: 'Edit', sections: [[input: [
                [element: 'input', name: 'enabled', type: 'bool', title: 'Enabled'],
                [element: 'input', name: 'count', type: 'number', title: 'Count', range: '0..10'],
                [element: 'input', name: 'credential', type: 'password'],
                [element: 'input', name: 'selection', type: 'enum', multiple: true, options: [['a': 'Alpha'], ['b': 'Beta']]],
                [element: 'input', name: 'devices', type: 'capability.switch', multiple: true, ignoreDevices: ['8']],
                [element: 'input', name: 'sensor', type: 'capability.temperatureMeasurement', multiple: false],
                [element: 'input', name: 'text', type: 'text'],
                [element: 'input', name: 'apply', type: 'button', title: 'Apply', state: 'clicked', submitOnChange: true],
                [element: 'href', name: 'advance', page: 'next', title: 'Next page', params: [row: 5, operation: 'edit']]
            ]]]]
        ]
        settingsMap.enableMandatoryBPS = false
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat'], disabled: false] }
        ['NATIVE_FORM_CONTEXTS', 'NATIVE_FORM_BUSY'].each { name ->
            def field = script.class.getDeclaredField(name)
            field.accessible = true
            field.get(null).clear()
        }
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            calls << [path: path, query: query]
            JsonOutput.toJson(path == '/device/listJson' ? devices : model)
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encoded, int timeout = 420, boolean retry = false ->
            calls << [path: path, body: decodeForm(encoded)]
            if (!replies.empty) return replies.remove(0)
            [status: 200, data: JsonOutput.toJson(path == '/installedapp/btn' ? [status: 'success'] : model)]
        }
    }

    private static Map decodeForm(String encoded) {
        encoded.split('&').collectEntries { part ->
            List pair = part.split('=', 2).toList()
            [(URLDecoder.decode(pair[0], 'UTF-8')): URLDecoder.decode(pair.size() > 1 ? pair[1] : '', 'UTF-8')]
        }
    }

    private Map open() {
        script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
    }

    private Map submit(Map fields = [:], String activate = null, String page = 'edit') {
        Map step = [kind: 'submit', expect_page: page, fields: fields]
        if (activate != null) step.activate = activate
        script.toolCallAppForm([app_id: 42, steps: [step], confirm: true])
    }

    def 'open returns native metadata and false zero values without exposing passwords or fetching devices'() {
        when:
        def result = open()
        then:
        result.success
        result.page.name == 'edit'
        result.fields.find { it.name == 'enabled' }.value == false
        result.fields.find { it.name == 'count' }.value == 0
        result.fields.find { it.name == 'devices' }.value == ['7']
        result.fields.find { it.name == 'selection' }.options == [[value: 'a', label: 'Alpha'], [value: 'b', label: 'Beta']]
        result.fields.find { it.name == 'credential' }.redacted
        !JsonOutput.toJson(result).contains('private-password')
        calls*.path == ['/installedapp/configure/json/42']
        atomicStateMap.values().every { !it?.toString()?.contains('private-password') }
    }

    def 'batched field submission uses native wire encoding and keeps navigation parameters'() {
        when:
        def result = script.toolCallAppForm([app_id: 42, confirm: true, steps: [
            [kind: 'open'],
            [kind: 'submit', expect_page: 'edit', fields: [enabled: false, count: 0, selection: ['a', 'b'], devices: ['7'], text: 'a+b & <b>text</b>']]
        ]])
        then:
        result.success
        calls*.path == ['/installedapp/configure/json/42', '/device/listJson', '/installedapp/update/json']
        calls[1].query == [capability: 'capability.switch']
        calls[2].body['settings[enabled]'] == 'false'
        calls[2].body['settings[count]'] == '0'
        calls[2].body['settings[selection]'] == '["a","b"]'
        calls[2].body['settings[devices]'] == '7'
        calls[2].body['settings[text]'] == 'a+b & text'
        calls[2].body['devices.multiple'] == 'true'
        calls[2].body['devices.type'] == 'capability.switch'
        calls[2].body.currentPage == 'edit'
        calls[2].body.pageBreadcrumbs == '%5B%22main%22%5D'
        calls[2].body.paramsForPage == URLEncoder.encode(JsonOutput.toJson(model.paramsForPage), 'UTF-8')
        !calls[2].body.containsKey('settings[credential]')
    }

    @Unroll
    def 'invalid field patch #fields is rejected before mutation'() {
        given:
        assert open().success
        calls.clear()
        when:
        def result = submit(fields)
        then:
        !result.success
        !result.partial
        !calls.any { it.path == '/installedapp/update/json' || it.path == '/installedapp/btn' }
        where:
        fields << [[selection: ['invalid']], [selection: 'a'], [sensor: ['7']], [enabled: 'true'], [count: 11], [count: 1.5], [unknown: 'value'], [devices: ['8']]]
    }

    def 'page guard and incomplete control ID fail before callbacks'() {
        given:
        assert open().success
        calls.clear()
        when:
        def mismatch = submit([count: 1], null, 'other')
        def ambiguous = submit([:], '_action_update')
        then:
        !mismatch.success
        !mismatch.partial
        !ambiguous.success
        !ambiguous.partial
        calls.empty
    }

    def 'structured button uses native state attribute then posts current field patch'() {
        given:
        assert open().success
        calls.clear()
        when:
        def result = submit([count: 3], 'apply||clicked')
        then:
        result.success
        calls*.path == ['/installedapp/btn', '/installedapp/update/json']
        calls[0].body == [id: '42', name: 'apply', stateAttribute: 'clicked', 'settings[apply]': 'clicked', 'apply.type': 'button']
        !calls[0].body.containsKey('settings[count]')
        calls[1].body['settings[count]'] == '3'
    }

    def 'native paragraph buttonLink is discoverable and preserves hidden native name and state attribute'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', title: """<div class='form-group'><input type='hidden' name='choose42.type' value='button'></div><div><a href='#' class='submitOnChange' onclick='buttonClick(this); return false;' data-stateAttribute='choice' aria-label='Choose &amp; continue'>Choose</a><input type='hidden' name='settings[choose42]' value=''></div>"""]
        when:
        def opened = open()
        def result = submit([:], 'choose42||choice')
        then:
        opened.success
        opened.controls.find { it.name == 'choose42' }.title == 'Choose & continue'
        result.success
        calls[-2].path == '/installedapp/btn'
        calls[-2].body.name == 'choose42'
        calls[-2].body.stateAttribute == 'choice'
        calls[-1].path == '/installedapp/update/json'
    }

    def 'href uses returned native name encoded params and current breadcrumbs'() {
        given:
        def opened = open()
        def href = opened.controls.find { it.kind == 'href' }
        calls.clear()
        when:
        def result = submit([count: 2], href.control_id)
        then:
        result.success
        calls.size() == 1
        calls[0].body.containsKey('_action_href_advance|next|9')
        calls[0].body['params_for_action_href_advance|next|9'] == URLEncoder.encode(JsonOutput.toJson([row: 5, operation: 'edit']), 'UTF-8')
        calls[0].body.pageBreadcrumbs == '%5B%22main%22%5D'
    }

    def 'same native button name with different state attributes has distinct executable control IDs'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', title:
            "<div><input type='hidden' name='Counter.type' value='button'></div><div><a href='#' class='submitOnChange' onclick='buttonClick(this); return false;' data-stateAttribute='editGV' title='Edit Counter'><i>edit</i></a><input type='hidden' name='settings[Counter]' value=''></div>" +
            "<div><input type='hidden' name='Counter.type' value='button'></div><div><a href='#' class='submitOnChange' onclick='buttonClick(this); return false;' data-stateAttribute='deleteGV' title='Delete Counter'><i>delete</i></a><input type='hidden' name='settings[Counter]' value=''></div>"]
        when:
        def opened = open()
        def controls = opened.controls.findAll { it.name == 'Counter' }
        def result = submit([:], controls.find { it.title == 'Edit Counter' }.control_id)
        then:
        opened.success
        controls.size() == 2
        controls*.control_id.unique().size() == 2
        result.success
        calls[-2].body.name == 'Counter'
        calls[-2].body.stateAttribute == 'editGV'
    }

    def 'device options expand only on request with native capability and ignored IDs'() {
        when:
        def result = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], options_for: ['devices'], confirm: true])
        then:
        result.success
        result.fields.find { it.name == 'devices' }.options == [[value: '7', label: 'Fixture one']]
        calls*.path == ['/installedapp/configure/json/42', '/device/listJson']
        calls[1].query.capability == 'capability.switch'
    }

    def 'unsupported browser behavior is explicit and cannot activate arbitrary controls'() {
        given:
        model.configPage.sections[0].input += [
            [element: 'href', title: 'External', url: 'https://example.invalid/setup'],
            [element: 'paragraph', title: '<script>custom()</script><button onclick="custom()">Custom</button>'],
            [element: 'input', name: 'upload', type: 'file']
        ]
        when:
        def opened = open()
        calls.clear()
        def result = submit([:], 'custom')
        then:
        opened.success
        opened.unsupported.any { it.contains('browser') }
        opened.unsupported.contains('Custom browser script')
        opened.fields.find { it.name == 'upload' }.supported == false
        !result.success
        !result.partial
        calls.empty
    }

    def 'lost callback response is uncertain and invalidates context without replay'() {
        given:
        assert open().success
        calls.clear()
        replies << [status: 200, data: null]
        when:
        def result = submit([:], 'apply||clicked')
        def retry = submit([:], 'apply||clicked')
        then:
        !result.success
        result.partial
        result.reopen_required
        !retry.success
        !retry.partial
        calls*.path == ['/installedapp/btn']
    }

    def 'batch stops at unavailable future fields and reports completed steps truthfully'() {
        when:
        def result = script.toolCallAppForm([app_id: 42, confirm: true, steps: [
            [kind: 'open'], [kind: 'submit', expect_page: 'edit', fields: [count: 2]],
            [kind: 'submit', expect_page: 'edit', fields: [future: 'unknown']],
            [kind: 'submit', expect_page: 'edit', activate: 'apply||clicked']
        ]])
        then:
        !result.success
        result.partial
        result.completed.size() == 2
        result.remaining_steps == 2
        calls*.path == ['/installedapp/configure/json/42', '/installedapp/update/json']
    }

    def 'native terminal response closes context and never follows its redirect'() {
        given:
        assert open().success
        calls.clear()
        replies << [status: 200, data: JsonOutput.toJson([status: 'success', location: '/installedapp/list'])]
        when:
        def result = submit([:], '_action_update|Done|')
        then:
        result.success
        result.closed
        calls*.path == ['/installedapp/update/json']
        calls[0].body._action_update == 'Done'
    }

    def 'native required field blocks Done until supplied including through cached context'() {
        given:
        model.configPage.sections[0].input << [element: 'input', name: 'requiredDevice', type: 'capability.switch', required: true]
        assert open().success
        calls.clear()
        when:
        def missing = submit([:], '_action_update|Done|')
        def supplied = submit([requiredDevice: '7'], '_action_update|Done|')
        then:
        !missing.success
        !missing.partial
        missing.error.contains('requiredDevice')
        supplied.success
        calls*.path == ['/device/listJson', '/installedapp/update/json']
    }

    def 'required displayed default must be explicitly submitted before navigation'() {
        given:
        model.configPage.sections[0].input << [element: 'input', name: 'defaultChoice', type: 'enum', required: true, options: ['a', 'b'], defaultValue: 'a']
        def opened = open()
        calls.clear()
        when:
        def result = submit([:], '_action_update|Done|')
        then:
        opened.fields.find { it.name == 'defaultChoice' }.value == 'a'
        opened.fields.find { it.name == 'defaultChoice' }.value_source == 'default'
        !result.success
        !result.partial
        calls.empty
    }

    def 'button reload after native href uses the current page and breadcrumbs'() {
        given:
        def opened = open()
        def href = opened.controls.find { it.kind == 'href' }
        model.configPage = [name: 'next', sections: [[input: [[element: 'input', name: 'reload', type: 'button', submitOnChange: false]]]]]
        model.pageBreadcrumbs = '%5B%22main%22%2C%22edit%22%5D'
        assert submit([:], href.control_id).success
        calls.clear()
        when:
        def result = submit([:], 'reload||', 'next')
        then:
        result.success
        calls*.path == ['/installedapp/btn', '/installedapp/configure/json/42/main/edit/next']
    }

    def 'HTTP 200 with native configPage error is not successful navigation'() {
        given:
        assert open().success
        calls.clear()
        replies << [status: 200, data: JsonOutput.toJson([status: 'success', app: [id: 42], configPage: [name: 'edit', error: 'native failure']])]
        when:
        def result = submit([count: 1])
        then:
        !result.success
        result.partial
        result.error.contains('Native form reported an error')
        calls*.path == ['/installedapp/update/json']
    }

    def 'transient context retains execution descriptors without field values messages or option labels'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', title: 'Native help text']
        when:
        def result = open()
        def field = script.class.getDeclaredField('NATIVE_FORM_CONTEXTS')
        field.accessible = true
        def cached = field.get(null).values().first()
        then:
        result.success
        cached.fields.selection.option_keys == ['a', 'b']
        !cached.fields.values().any { it.containsKey('value') || it.containsKey('title') || it.containsKey('options') }
        !cached.containsKey('messages')
        cached.bytes <= 24576
        !JsonOutput.toJson(cached).contains('Alpha')
        !JsonOutput.toJson(cached).contains('private-password')
    }

    @Unroll
    def 'non-native or disabled app #appDescriptor is refused before rendering'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> appDescriptor }
        when:
        open()
        then:
        thrown(IllegalArgumentException)
        calls.empty
        where:
        appDescriptor << [null, [appType: [namespace: 'custom']], [appType: [namespace: 'hubitat'], disabled: true]]
    }

    def 'native Remove is reported unsupported and is never a form control'() {
        given:
        model.removeButton = true
        when:
        def opened = open()
        calls.clear()
        def result = submit([:], '_action_remove|Remove|')
        then:
        opened.unsupported.any { it.contains('dedicated native-app deletion tool') }
        !opened.controls.any { it.name == '_action_remove' }
        !result.success
        !result.partial
        calls.empty
    }

    @Unroll
    def 'invalid public step #step fails before rendering'() {
        when:
        script.toolCallAppForm([app_id: 42, confirm: true, steps: [step]])
        then:
        thrown(IllegalArgumentException)
        calls.empty
        where:
        step << [[kind: 'open', page: '../settings'], [kind: 'open', url: '/anything'], [kind: 'submit', fields: [:]], [kind: 'invoke', method: 'updated']]
    }

    def 'tool catalog exposes one bounded mutating form surface'() {
        when:
        def definitions = script._getAllToolDefinitions_partNativeForms()
        then:
        definitions*.name == ['hub_call_app_form']
        definitions[0].inputSchema.properties.steps.maxItems == 16
        definitions[0].inputSchema.required.contains('confirm')
        definitions[0].description.contains('Rendering executes app code')
    }
    def 'shared validation preserves optional form clearing and native wire encodings'() {
        given:
        assert open().success
        calls.clear()
        when:
        def result = submit([count: '', selection: [], devices: [], text: ''])
        then:
        result.success
        calls.find { it.path == '/installedapp/update/json' }.body.subMap([
            'settings[count]', 'settings[selection]', 'settings[devices]', 'settings[text]']) == [
            'settings[count]': '', 'settings[selection]': '[]', 'settings[devices]': '', 'settings[text]': '']
    }

}
