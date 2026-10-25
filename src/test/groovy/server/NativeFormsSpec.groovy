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
        script.callerClient = null
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
        // Snapshot behavior has dedicated NativeRuleEditBackupSpec coverage.
        settingsMap.backupBeforeRuleEdits = false
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

    void cleanup() { script.callerClient = null }

    private Map cachedContexts() {
        def field = script.class.getDeclaredField('NATIVE_FORM_CONTEXTS')
        field.accessible = true
        field.get(null) as Map
    }

    private Map busyContexts() {
        def field = script.class.getDeclaredField('NATIVE_FORM_BUSY')
        field.accessible = true
        field.get(null) as Map
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
        fields << [[selection: ['invalid']], [selection: 'a'], [sensor: ['7']], [enabled: 'true'], [count: 11], [count: 'not numeric'], [unknown: 'value'], [devices: ['8']]]
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

    def 'existing native child link exposes a guarded handoff without target rendering'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', description:
            "<table><tr><td><a href='/installedapp/configure/43/selectActions?resetScroll=true'><span style='color:purple'>On: Switch</span></a></td></tr></table>"]
        def opened = open()
        def link = opened.controls.find { it.kind == 'local_app' }
        calls.clear()
        script.metaClass.getAppByAppId = { Long id ->
            [id: id, parentAppId: id == 43L ? 42 : null, appType: [namespace: 'hubitat'], disabled: false]
        }
        when:
        def result = submit([:], link.control_id)
        then:
        link.title == 'On: Switch'
        !opened.unsupported.contains('HTML navigation link')
        result.success
        result.closed
        result.navigated_app_id == 43L
        result.navigated_page == 'selectActions'
        calls.isEmpty()
    }

    def 'local app handoff refuses accompanying fields without discarding them'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', description:
            "<a href='/installedapp/configure/43/selectActions?resetScroll=true'>Child</a>"]
        def opened = open()
        calls.clear()
        when:
        def result = submit([count: 3], opened.controls.find { it.kind == 'local_app' }.control_id)
        then:
        !result.success
        !result.partial
        calls.isEmpty()
    }

    def 'native script handoff stops remaining source steps and retains verified target'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', description:
            "<script>{window.open('/installedapp/configure/42/selectActions?resetScroll=true', '_self')}</script>"]
        when:
        def result = script.toolCallAppForm([app_id: 42, confirm: true,
            steps: [[kind: 'open'], [kind: 'submit', expect_page: 'edit', fields: [count: 3]]]])
        then:
        !result.success
        result.partial
        result.closed
        result.navigated_app_id == 42L
        result.completed.size() == 1
        result.remaining_steps == 1
        calls*.path == ['/installedapp/configure/json/42']
    }

    @Unroll
    def 'native local navigation hands off only a verified same app or child: #target'() {
        given:
        assert open().success
        calls.clear()
        model.configPage.sections[0].input << [element: 'paragraph', description:
            "<script>{window.open('/installedapp/configure/${target}/selectActions?resetScroll=true', '_self')}</script>"]
        // A user app's own child is as valid a target as a built-in one.
        script.metaClass.getAppByAppId = { Long id ->
            [id: id, parentAppId: id == 43L ? 42 : null, appType: [namespace: id == 43L ? 'user' : 'hubitat'], disabled: false]
        }
        when:
        def result = submit([:], 'apply||clicked')
        def retry = submit([:], 'apply||clicked')
        then:
        result.success
        result.closed
        result.app_id == 42L
        result.navigated_app_id == target
        result.navigated_page == 'selectActions'
        !result.containsKey('created_app_id')
        !retry.success
        calls*.path == ['/installedapp/btn', '/installedapp/update/json']
        where:
        target << [42L, 43L]
    }

    @Unroll
    def 'native navigation refuses invalid or protected target before reading it: #reason'() {
        given:
        assert open().success
        calls.clear()
        model.configPage.sections[0].input << [element: 'paragraph', description:
            "<script>{window.open('/installedapp/configure/43/selectActions?resetScroll=true', '_self')}</script>"]
        script.metaClass.getAppByAppId = { Long id ->
            id == 42L ? [id: id, appType: [namespace: 'hubitat'], disabled: false] : target
        }
        if (reason == 'protected') atomicStateMap.protectedAppsPolicy = [ids: ['43']]
        when:
        def result = submit([:], 'apply||clicked')
        then:
        !result.success
        result.partial
        result.reopen_required
        !result.containsKey('navigated_app_id')
        calls*.path == ['/installedapp/btn', '/installedapp/update/json']
        where:
        reason      | target
        'unrelated' | [id: 43, parentAppId: 99, appType: [namespace: 'hubitat']]
        'disabled'  | [id: 43, parentAppId: 42, appType: [namespace: 'hubitat'], disabled: true]
        'missing'   | null
        'protected' | [id: 43, parentAppId: 42, appType: [namespace: 'hubitat']]
    }

    @Unroll
    def 'arbitrary browser script remains unsupported: #html'() {
        given:
        model.configPage.sections[0].input << [element: 'paragraph', description: html]
        when:
        def result = open()
        then:
        result.success
        !result.closed
        !result.containsKey('navigated_app_id')
        result.unsupported.contains('Custom browser script')
        calls*.path == ['/installedapp/configure/json/42']
        where:
        html << [
            "<script>{window.open('https://example.invalid/installedapp/configure/43/selectActions?resetScroll=true', '_self')}</script>",
            "<script>{window.open('/installedapp/configure/43/selectActions?resetScroll=true', '_blank')}</script>",
            "<script>{window.open('/installedapp/configure/43/selectActions?resetScroll=true', '_self');custom()}</script>",
            "<script>{window.open('/installedapp/configure/43/selectActions?other=true', '_self')}</script>"
        ]
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

    def 'native child creation uses the returned parent control and verifies child identity'() {
        given:
        model.configPage.sections[0].input << [element: 'app', name: 'children', namespace: 'hubitat',
                                               appName: 'Example Child', appId: 42, title: 'Create child']
        def opened = open()
        def control = opened.controls.find { it.kind == 'create_child' }
        calls.clear()
        script.metaClass.hubInternalGetRaw = { String path ->
            calls << [path: path]
            [status: 302, location: 'Location: http://127.0.0.1:8080/installedapp/configure/43']
        }
        script.metaClass.getAppByAppId = { Long id ->
            [id: id, parentAppId: id == 43L ? 42 : null, disabled: false,
             appType: [namespace: 'hubitat', name: id == 43L ? 'Example Child' : 'Example Parent']]
        }
        when:
        def result = submit([:], control.control_id)
        then:
        result.success
        result.closed
        result.app_id == 42L
        result.created_app_id == 43L
        calls*.path == ['/installedapp/createchild/hubitat/Example Child/parent/42']
    }

    def 'a created child that is not the requested type fails the form and still reports its id'() {
        given:
        model.configPage.sections[0].input << [element: 'app', name: 'children', namespace: 'hubitat',
                                               appName: 'Example Child', appId: 42, title: 'Create child']
        def control = open().controls.find { it.kind == 'create_child' }
        calls.clear()
        script.metaClass.hubInternalGetRaw = { String path ->
            calls << [path: path]
            [status: 302, location: '/installedapp/configure/43']
        }
        script.metaClass.getAppByAppId = { Long id ->
            [id: id, parentAppId: id == 43L ? 42 : null, disabled: false, appType: [namespace: 'hubitat', name: 'Other Child']]
        }
        when:
        def result = submit([:], control.control_id)
        then:
        !result.success
        result.partial
        result.created_app_id == 43L
        result.error == 'Created app 43 did not match the native parent and child type'
        calls*.path == ['/installedapp/createchild/hubitat/Example Child/parent/42']
    }

    def 'child creation refuses accompanying fields before the native request'() {
        given:
        model.configPage.sections[0].input << [element: 'app', name: 'children', namespace: 'hubitat',
                                               appName: 'Example Child', appId: 42]
        def opened = open()
        calls.clear()
        when:
        def result = submit([count: 1], opened.controls.find { it.kind == 'create_child' }.control_id)
        then:
        !result.success
        !result.partial
        calls.empty
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

    def 'a submit posts the page default of every unset input, as the browser does, and that default satisfies a required check'() {
        given:
        model.configPage.sections[0].input << [element: 'input', name: 'defaultChoice', type: 'enum', required: true, options: ['a', 'b'], defaultValue: 'a']
        model.configPage.sections[0].input << [element: 'input', name: 'luxOff', type: 'number', defaultValue: 100]
        model.configPage.sections[0].input << [element: 'input', name: 'secret', type: 'password', defaultValue: 'never-posted']
        model.configPage.sections[0].input << [element: 'input', name: 'storedNumber', type: 'number', defaultValue: 5]
        model.settings.storedNumber = 9
        model.configPage.sections[0].input << [element: 'input', name: 'lockedNumber', type: 'number', defaultValue: 4, disabled: true]
        model.configPage.sections[0].input << [element: 'input', name: 'tint', type: 'color', defaultValue: '#ff0000']
        model.configPage.sections[0].input << [element: 'input', name: 'staleChoice', type: 'enum', options: ['a', 'b'], defaultValue: 'z']
        def opened = open()
        calls.clear()
        when:
        def result = submit([:], '_action_update|Done|')
        def body = calls.find { it.path == '/installedapp/update/json' }.body
        then:
        opened.fields.find { it.name == 'defaultChoice' }.value_source == 'default'
        result.success
        body['settings[defaultChoice]'] == 'a'
        body['defaultChoice.type'] == 'enum'
        body['settings[luxOff]'] == '100'
        !body.containsKey('settings[secret]')
        !body.containsKey('settings[storedNumber]')
        !body.containsKey('settings[lockedNumber]')
        !body.containsKey('settings[tint]')
        !body.containsKey('settings[staleChoice]')
        result.defaults_posted as Set == ['defaultChoice', 'luxOff'] as Set
        result.defaults_not_posted*.name == ['staleChoice']
        result.defaults_not_posted[0].reason.contains('staleChoice')
        when: 'a supplied value wins over the default'
        calls.clear()
        assert open().success
        calls.clear()
        submit([luxOff: 20])
        then:
        calls.find { it.path == '/installedapp/update/json' }.body['settings[luxOff]'] == '20'
        when: 'a later step of the same batch still posts the default'
        assert open().success
        calls.clear()
        def batch = script.toolCallAppForm([app_id: 42, confirm: true, steps: [
            [kind: 'submit', expect_page: 'edit', fields: [text: 'typed']],
            [kind: 'submit', expect_page: 'edit', activate: '_action_update|Done|']]])
        def posts = calls.findAll { it.path == '/installedapp/update/json' }*.body
        then:
        batch.success
        posts.size() == 2
        posts[1]._action_update == 'Done'
        posts[1]['settings[luxOff]'] == '100'
        posts[1]['settings[defaultChoice]'] == 'a'
        !batch.fields.any { it.containsKey('default_value') }
    }

    def 'a sub-page opened directly with from offers its Done back to that parent'() {
        given: 'a direct render carries an empty breadcrumb trail and no Done of its own'
        model.pageBreadcrumbs = '%5B%5D'
        model.doneButton = false
        model.cancelButton = false
        when:
        def plain = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open', page: 'onMeansPage']], confirm: true])
        def opened = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open', page: 'onMeansPage', from: 'mainPage']], confirm: true])
        calls.clear()
        def done = submit([:], '_action_previous|Done|')
        then:
        !plain.controls*.control_id.contains('_action_previous|Done|')
        opened.controls*.control_id.contains('_action_previous|Done|')
        done.success
        def body = calls.find { it.path == '/installedapp/update/json' }.body
        body._action_previous == 'Done'
        body.pageBreadcrumbs == '%5B%22mainPage%22%5D'
        when:
        script.toolCallAppForm([app_id: 42, steps: [[kind: 'open', from: 'mainPage']], confirm: true])
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('requires page')
    }

    def 'a page with a next and a done page offers _action_done, posted like the other navigation after the required check'() {
        given: 'the firmware renders _action_done instead of Next when the page names both'
        model.doneButton = false
        model.cancelButton = false
        model.configPage = [name: 'pageTwo', title: 'Step two', nextPage: 'pageThree', donePage: 'pageThree',
                            sections: [[input: [[element: 'input', name: 'second', type: 'text', required: true]]]]]
        model.settings = [:]
        def opened = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
        calls.clear()
        when:
        def missing = script.toolCallAppForm([app_id: 42, confirm: true, steps: [[kind: 'submit', expect_page: 'pageTwo', activate: '_action_done|Done|']]])
        then:
        opened.controls*.control_id == ['_action_done|Done|']
        opened.controls[0].kind == 'navigation'
        opened.unsupported.isEmpty()
        !missing.success
        missing.error.contains('second')
        calls.empty
        when:
        assert open().success
        calls.clear()
        def done = script.toolCallAppForm([app_id: 42, confirm: true, steps: [[kind: 'submit', expect_page: 'pageTwo', fields: [second: 'two'], activate: '_action_done|Done|']]])
        then:
        done.success
        calls*.path == ['/installedapp/update/json']
        calls[0].body._action_done == 'Done'
        calls[0].body['settings[second]'] == 'two'
        calls[0].body.nextPage == 'pageThree'
        calls[0].body.donePage == 'pageThree'
        !calls[0].body.containsKey('_action_next')
    }

    def 'the hub default fields are the app label and mode restriction, posted as label and mode[mode]'() {
        given:
        model.defaultFields = true
        model.modeMap = ['1': 'Day', '2': 'Night']
        model.app.label = 'Porch'
        model.app.restrictedModes = [2]
        when:
        def opened = open()
        def label = opened.fields.find { it.name == 'label' }
        def modes = opened.fields.find { it.name == 'mode[mode]' }
        then:
        label.type == 'text'
        label.title == 'Assign a name'
        label.value == 'Porch'
        !label.containsKey('app_field') && !label.containsKey('sidecars')
        modes.type == 'mode'
        modes.multiple
        modes.value == ['2']
        modes.options == [[value: '1', label: 'Day'], [value: '2', label: 'Night']]
        opened.unsupported.every { !it.contains('label') }
        when:
        calls.clear()
        def saved = submit([label: 'Back <b>porch</b>', 'mode[mode]': ['1', '2']])
        def body = calls.find { it.path == '/installedapp/update/json' }.body
        then:
        saved.success
        body.label == 'Back porch'
        body['mode[mode]'] == '["1","2"]'
        body['mode[mode].multiple'] == 'true'
        !body.keySet().any { it in ['settings[label]', 'settings[mode[mode]]', 'label.type', 'mode[mode].type'] }
        when: 'an unset default field is not posted, and a mode outside the options is refused before any request'
        assert open().success
        calls.clear()
        submit([count: 1])
        def plain = calls.find { it.path == '/installedapp/update/json' }.body
        assert open().success
        calls.clear()
        def refused = submit(['mode[mode]': ['9']])
        then:
        !plain.containsKey('label') && !plain.containsKey('mode[mode]')
        !refused.success
        !refused.partial
        calls.empty
    }

    def 'a mode[mode] write posts the current label with it, as the browser does, and a label write posts no mode[mode]'() {
        given:
        model.defaultFields = true
        model.modeMap = ['1': 'Day', '2': 'Night']
        model.app.label = "<span style='color:green'>Porch</span>"
        model.app.trueLabel = 'Porch'
        when:
        assert open().success
        calls.clear()
        def modes = submit(['mode[mode]': ['1']])
        def modeBody = calls.find { it.path == '/installedapp/update/json' }.body
        assert open().success
        calls.clear()
        def label = submit([label: 'Back porch'])
        def labelBody = calls.find { it.path == '/installedapp/update/json' }.body
        then:
        modes.success
        modeBody['mode[mode]'] == '["1"]'
        modeBody.label == 'Porch'
        label.success
        labelBody.label == 'Back porch'
        !labelBody.keySet().any { it.startsWith('mode[mode]') }
    }

    def 'the label field reads trueLabel, the label without the HTML an app decorates it with'() {
        given:
        model.defaultFields = true
        model.app.label = "<span style='color:green'>Porch</span>"
        model.app.trueLabel = 'Porch'
        when:
        def opened = open()
        then:
        opened.fields.find { it.name == 'label' }.value == 'Porch'
    }

    @Unroll
    def 'an accessToken input reads #shown when the toggle is #toggle and the caller is #caller'() {
        given:
        settingsMap.redactAccessTokens = toggle
        if (caller == 'a client') script.callerClient = [id: 'c1']
        model.settings.accessToken = '0f8e2c1a-token'
        model.configPage.sections[0].input << [element: 'input', name: 'accessToken', type: 'text']
        when:
        def field = open().fields.find { it.name == 'accessToken' }
        then:
        field.value == (shown == 'redacted' ? '***redacted (access token)***' : '0f8e2c1a-token')
        field.value_source == 'stored'
        where:
        toggle | caller       | shown
        false  | 'the main'   | 'its value'
        true   | 'the main'   | 'redacted'
        false  | 'a client'   | 'redacted'
    }

    def 'a page own label and mode elements are the same fields, the label posted with its type and checked when required'() {
        given:
        model.modeMap = ['1': 'Day']
        model.app.label = ''
        model.configPage.sections[0].input += [[element: 'label', name: 'label', type: 'text', title: 'Name this app', required: true],
                                               [element: 'mode', title: 'Only in modes']]
        when:
        def opened = open()
        calls.clear()
        def missing = submit([:], '_action_update|Done|')
        then:
        opened.fields.find { it.name == 'label' }.title == 'Name this app'
        opened.fields.find { it.name == 'label' }.required
        opened.fields.find { it.name == 'mode[mode]' }.title == 'Set for specific mode(s)'
        opened.unsupported.every { !it.contains('mode') }
        !missing.success
        missing.error.contains('label')
        calls.empty
        when:
        def done = submit([label: 'Kitchen'], '_action_update|Done|')
        def body = calls.find { it.path == '/installedapp/update/json' }.body
        then:
        done.success
        body.label == 'Kitchen'
        body['label.type'] == 'text'
        body._action_update == 'Done'
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

    def 'engine failures carry the hub own error text, HTTP status and parse failure'() {
        given:
        assert open().success
        replies << [status: 200, data: JsonOutput.toJson([status: 'success', app: [id: 42], configPage: [name: 'edit', error: 'Value out of range']])]
        when:
        def reported = submit([count: 1])
        then:
        !reported.success
        reported.error == 'Native form reported an error: Value out of range; inspect the native editor and logs'
        when:
        assert open().success
        replies << [status: 500, data: '']
        def failed = submit([count: 1])
        then:
        failed.error == 'Native form request returned HTTP 500; inspect the app before retrying'
        when:
        assert open().success
        replies << [status: 200, data: '<html>not json</html>']
        def unreadable = submit([count: 1])
        then:
        unreadable.error.startsWith('Native form returned unreadable JSON (')
        unreadable.error.endsWith('); inspect the app before retrying')
    }

    def 'an error the hub reports at the top level is carried too'() {
        given:
        assert open().success
        replies << [status: 200, data: JsonOutput.toJson([status: 'success', errorMessage: 'Rule is busy'])]
        when:
        def result = submit([count: 1])
        then:
        result.error == 'Native form reported an error: Rule is busy; inspect the native editor and logs'
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
    def 'a missing or disabled app #appDescriptor is refused before rendering'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> appDescriptor }
        when:
        open()
        then:
        thrown(IllegalArgumentException)
        calls.empty
        where:
        appDescriptor << [null, [id: 99, appType: [namespace: 'hubitat']], [id: 42, appType: [namespace: 'hubitat'], disabled: true]]
    }

    def 'a user or community app is opened like a built-in one, and hub_set_native_app names itself in the failure log'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'someDeveloper', name: 'Community App'], disabled: false] }
        List logged = []
        script.metaClass.mcpLogError = { String component, String message, Exception error -> logged << message }
        when:
        def opened = open()
        def failed = script.toolSetNativeApp([operation: 'form', app_id: 42, args: [steps: [[kind: 'submit', expect_page: 'nowhere']]], confirm: true])
        then:
        opened.success
        opened.page.name == 'edit'
        opened.backup.skipped
        !failed.success
        logged.any { it.contains('hub_set_native_app form failed for app 42') && it.contains('expect_page does not match') }
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

    def 'sequential explicit opens reuse one caller workflow beyond eight apps'() {
        given:
        List outcomes = []
        when:
        (42L..65L).each { id ->
            model.app.id = id
            outcomes << script.toolCallAppForm([app_id: id, steps: [[kind: 'open']], confirm: true])
        }
        calls.clear()
        def stale = script.toolCallAppForm([app_id: 42, steps: [[kind: 'submit', expect_page: 'edit', fields: [count: 3]]], confirm: true])
        def current = script.toolCallAppForm([app_id: 65, steps: [[kind: 'submit', expect_page: 'edit', fields: [count: 3]]], confirm: true])
        then:
        outcomes.every { it.success }
        cachedContexts().size() == 1
        cachedContexts().values().first().app_id == 65L
        !stale.success
        !stale.partial
        current.success
        calls*.path == ['/installedapp/update/json']
    }

    def 'caller contexts remain isolated when another caller opens another app'() {
        given:
        assert open().success
        script.callerClient = [id: 'a']
        model.app.id = 43
        assert script.toolCallAppForm([app_id: 43, steps: [[kind: 'open']], confirm: true]).success
        calls.clear()
        when:
        script.callerClient = [id: 'b']
        def unbound = script.toolCallAppForm([app_id: 43, steps: [[kind: 'submit', expect_page: 'edit']], confirm: true])
        script.callerClient = [id: 'a']
        def wrongApp = submit()
        def bound = script.toolCallAppForm([app_id: 43, steps: [[kind: 'submit', expect_page: 'edit']], confirm: true])
        script.callerClient = null
        model.app.id = 42
        def original = submit()
        then:
        !unbound.success
        !wrongApp.success
        bound.success
        original.success
        cachedContexts().size() == 2
        calls*.path == ['/installedapp/update/json', '/installedapp/update/json']
    }

    def 'full caller capacity refuses a new caller without evicting existing workflows'() {
        given:
        (1..8).each { id ->
            script.callerClient = [id: id.toString()]
            assert open().success
        }
        def before = new LinkedHashMap(cachedContexts())
        when:
        script.callerClient = [id: '9']
        def full = open()
        script.callerClient = [id: '1']
        def retained = submit()
        then:
        !full.success
        full.error.contains('capacity')
        retained.success
        cachedContexts().keySet() == before.keySet()
    }

    def 'one caller cannot replace a workflow while its native render is active'() {
        given:
        List errors = []
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            calls << [path: path]
            def nestedResult = script.toolCallAppForm([app_id: 43, steps: [[kind: 'open']], confirm: true])
            if (nestedResult.success == false) errors << nestedResult.error
            JsonOutput.toJson(model)
        }
        when:
        def result = open()
        then:
        result.success
        errors.size() == 1
        errors[0].contains('active form operation')
        calls*.path == ['/installedapp/configure/json/42']
        busyContexts().isEmpty()
    }

    def 'per app contention releases the rejected caller lock and keeps the owner active'() {
        given:
        List errors = []
        boolean nested = false
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            calls << [path: path]
            if (!nested) {
                nested = true
                script.callerClient = [id: 'other']
                try {
                    def nestedResult = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
                    if (nestedResult.success == false) errors << nestedResult.error
                } finally { script.callerClient = null }
            }
            JsonOutput.toJson(model)
        }
        when:
        def owner = open()
        script.callerClient = [id: 'other']
        model.app.id = 43
        def later = script.toolCallAppForm([app_id: 43, steps: [[kind: 'open']], confirm: true])
        then:
        owner.success
        errors == ['This app already has an active form operation']
        later.success
        busyContexts().isEmpty()
        calls*.path == ['/installedapp/configure/json/42', '/installedapp/configure/json/43']
    }

    def 'failed explicit open discards only that callers stale workflow and releases locks'() {
        given:
        assert open().success
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            throw new IOException('native render unavailable')
        }
        when:
        def failure = script.toolCallAppForm([app_id: 43, steps: [[kind: 'open']], confirm: true])
        def stale = submit()
        then:
        !failure.success
        failure.partial
        !stale.success
        !stale.partial
        cachedContexts().isEmpty()
        busyContexts().isEmpty()
    }

    def 'tool catalog exposes one bounded mutating form surface'() {
        when:
        def definitions = script._getAllToolDefinitions_partNativeDocuments()
        then:
        definitions*.name == ['hub_set_rule']
        script._ndOperationSchema('form').properties.steps.maxItems == 16
        script._naOperationSchema('form') == script._ndOperationSchema('form')
        !definitions[0].inputSchema.required?.contains('confirm')
        script._ndGuide().contains('Even open renders execute app code')
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
