package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.TestLocation
import support.ToolSpecBase

/**
 * hub_set_native_app create and update run the native form engine the way the browser does
 * (issue #561): the parent's create_child control, the new app's first page and its Done; on an
 * existing app the page's Done, and on a sub-page the Done of the page it returns to. A refusal
 * fires before the page is posted; a create refused once its child exists removes the child and
 * returns success:false. An update input whose own save reveals a required one stops before the
 * Done and reports partial. The hub answers an accepted Done with a location; without one the app
 * did not commit. One read-back after the Done reports what the hub stored.
 */
class NativeAppOperationsSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 1L, label: 'MCP')
    @Shared TestLocation sharedLocation = new TestLocation()
    // The hub answers a Done it accepts with the page the browser goes to next.
    static final Map DONE = [status: 'success', location: '/installedapp/list']
    Map pages
    Map apps
    Map replies
    List gets
    List posts
    List raws
    List deleted
    List devices
    boolean deleteFails
    boolean deleteSilent
    Closure postFailure

    void setupSpec() {
        appExecutor.getApp() >> serverApp
        appExecutor.getLocation() >> sharedLocation
        appExecutor.toDateTime(_ as String) >> { String value -> Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", value) }
    }

    void setup() {
        script.callerClient = null
        pages = [:]; apps = [:]; replies = [:]
        gets = []; posts = []; raws = []; deleted = []
        devices = [[id: 7, label: 'Lamp'], [id: 8, label: 'Fan']]
        deleteFails = false
        deleteSilent = false
        postFailure = null
        sharedLocation.timeZone = TimeZone.getTimeZone('UTC')
        settingsMap.enableMandatoryBPS = false
        settingsMap.backupBeforeRuleEdits = false
        stateMap.lastBackupTimestamp = 1234567890000L
        atomicStateMap.parentAppIds = [notifier: 40, rule_machine: 43, thermostat_scheduler: 45, basic_rule: 46, button_controller: 47, groups_scenes: 48]
        ['NATIVE_FORM_CONTEXTS', 'NATIVE_FORM_BUSY'].each { name -> sharedMap(name).clear() }
        script.metaClass.getAppByAppId = { Long id -> apps[id] }
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            gets << path
            if (path == '/device/listJson') return JsonOutput.toJson(devices)
            def page = pages[path]
            if (page instanceof List) page = page.size() > 1 ? page.remove(0) : page[0]
            if (page instanceof Closure) page = page.call()
            if (page == null) throw new IllegalStateException("status code: 404, unexpected GET ${path}")
            JsonOutput.toJson(page)
        }
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, Integer timeout = 30 ->
            raws << path
            def created = path =~ /^\/installedapp\/createchild\/hubitat\/.+\/parent\/\d+$/
            if (created.find()) return [status: 302, location: "/installedapp/configure/${replies.createdId}", data: '']
            def removal = path =~ /^\/installedapp\/forcedelete\/(\d+)\/quiet$/
            if (removal.find()) {
                if (deleteFails) return [status: 500, data: '']
                if (deleteSilent) return null
                deleted << (removal.group(1) as Integer)
                apps.remove(removal.group(1) as Long)
                return [status: 302, location: '/installedapp/list', data: '']
            }
            throw new IllegalStateException("unexpected raw GET ${path}")
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String encoded, int timeout = 420, boolean retry = false ->
            Map body = decodeForm(encoded)
            posts << [path: path, body: body]
            if (postFailure) postFailure.call(path, body)
            List queue = replies[path]
            def reply = queue ? (queue.size() > 1 ? queue.remove(0) : queue[0]) : (body._action_update == 'Done' ? DONE : [status: 'success'])
            [status: 200, data: JsonOutput.toJson(reply)]
        }
    }

    void cleanup() { script.callerClient = null }

    private Map sharedMap(String name) {
        def field = script.class.getDeclaredField(name)
        field.accessible = true
        field.get(null) as Map
    }

    private static Map decodeForm(String encoded) {
        encoded.split('&').collectEntries { part ->
            List pair = part.split('=', 2).toList()
            [(URLDecoder.decode(pair[0], 'UTF-8')): URLDecoder.decode(pair.size() > 1 ? pair[1] : '', 'UTF-8')]
        }
    }

    private static Map input(String name, String type, Map extra = [:]) { [element: 'input', name: name, type: type] + extra }

    private static Map page(Long id, List inputs, Map opts = [:]) {
        Map model = [app: [id: id, label: opts.label, installed: opts.containsKey('installed') ? opts.installed : false, version: 3],
                     configPage: [name: opts.page ?: 'mainPage', title: 'Page', install: opts.containsKey('install') ? opts.install : true, sections: [[input: inputs]]],
                     settings: opts.settings ?: [:]]
        if (opts.modeMap) model.modeMap = opts.modeMap
        model
    }

    private void parentAt(Long id, String control, String appName) {
        pages["/installedapp/configure/json/${id}".toString()] = page(id, [[element: 'app', namespace: 'hubitat', appName: appName, name: control, appId: id, title: "Create New ${appName}"]])
    }

    private void childAt(Long id, Long parentId, String typeName, List renders) {
        replies.createdId = id
        apps[id] = [id: id, parentAppId: parentId, appType: [namespace: 'hubitat', name: typeName], disabled: false]
        pages["/installedapp/configure/json/${id}".toString()] = renders
    }

    private List notifierInputs() {
        [input('origLabel', 'text', [required: true]), input('logging', 'bool'), input('devType', 'enum', [options: ['Email', 'Push']]),
         input('switches', 'capability.switch', [multiple: true]), input('go', 'button', [title: 'Go'])]
    }

    private Map create(Map args) { script.toolSetNativeApp([operation: 'create', args: args, confirm: true]) }

    private Map update(Long id, Map args) { script.toolSetNativeApp([operation: 'update', app_id: id, args: args, confirm: true]) }

    private void existing(Long id, String typeName = 'Notifier') {
        apps[id] = [id: id, appType: [namespace: 'hubitat', name: typeName], disabled: false]
    }

    // ---------------------------------------------------------------- create

    def 'create uses the parent page create control, fills the first page and presses its Done in one post'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()),
            page(90L, notifierInputs(), [label: 'Front door', installed: true, settings: [origLabel: 'Front door', logging: 'true']])])

        when:
        def result = create([app_type: 'notifier', name: 'Front door', fields: [logging: true]])

        then:
        result.success
        result.app_id == 90L
        result.app_type == 'notifier'
        result.label == 'Front door'
        result.fields_applied == ['logging', 'origLabel']
        !result.containsKey('backup')
        !result.containsKey('installed')
        result.note.contains("hub_set_native_app(operation:'update', app_id:90")
        raws == ['/installedapp/createchild/hubitat/Notifier/parent/40']
        posts.size() == 1
        posts[0].path == '/installedapp/update/json'
        posts[0].body['settings[origLabel]'] == 'Front door'
        posts[0].body['settings[logging]'] == 'true'
        posts[0].body._action_update == 'Done'
        gets.count { it == '/installedapp/configure/json/90' } == 2
        deleted.isEmpty()
    }

    def 'a Rule Machine rule is created through the Rule Machine page and pointed at hub_set_rule'() {
        given:
        parentAt(43L, 'childRules51', 'Rule-5.1')
        childAt(91L, 43L, 'Rule-5.1', [page(91L, [input('origLabel', 'text', [required: true])]),
            page(91L, [input('origLabel', 'text', [required: true])], [label: 'Hall', installed: true, settings: [origLabel: 'Hall']])])

        when:
        def result = create([app_type: 'rule_machine', name: 'Hall'])

        then:
        result.success
        raws == ['/installedapp/createchild/hubitat/Rule-5.1/parent/43']
        posts*.body*._action_update == ['Done']
        result.note.contains("hub_set_rule(operation:'update', rule_id:91")
    }

    def 'a create drops the caller cached form context of the parent page it changed'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()),
            page(90L, notifierInputs(), [label: 'N', installed: true, settings: [origLabel: 'N']])])
        sharedMap('NATIVE_FORM_CONTEXTS')[script._nfCallerKey()] = [app_id: 40L, page: [name: 'mainPage']]

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        result.success
        !sharedMap('NATIVE_FORM_CONTEXTS').containsKey(script._nfCallerKey())
    }

    def 'a parent page without a create control for the type refuses before anything is created'() {
        given:
        pages['/installedapp/configure/json/40'] = page(40L, [[element: 'app', namespace: 'hubitat', appName: 'Notifier Plus', name: 'other', appId: 40L]])

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.error.contains('offers no control that creates Notifier')
        result.error.contains('Notifier Plus')
        raws.isEmpty()
        posts.isEmpty()
    }

    @Unroll
    def 'a create its first page refuses removes the new app, posts nothing and returns success false: #label'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs())])

        when:
        def result = create(args)

        then:
        !result.success
        result.app_type == 'notifier'
        !result.containsKey('app_id')
        result.error.contains(message)
        result.error.contains('The new app was removed, so nothing was created.')
        result.note.contains("hub_get_tool_guide(section='set_native_app_reference')")
        deleted == [90]
        posts.isEmpty()

        where:
        label                     | args                                                                   | message
        'unknown field'           | [app_type: 'notifier', name: 'N', fields: [nope: 1]]                   | 'Field is not present on the current native page: nope'
        'button in fields'        | [app_type: 'notifier', name: 'N', fields: [go: 'x']]                   | 'go is a button on the current native page'
        'bad enum'                | [app_type: 'notifier', name: 'N', fields: [devType: 'Fax']]            | 'Invalid native choice for devType'
        'unknown device id'       | [app_type: 'notifier', name: 'N', fields: [switches: ['99']]]          | 'Invalid native choice for switches'
        'device cardinality'      | [app_type: 'notifier', name: 'N', fields: [switches: '7']]             | 'Incorrect cardinality for switches'
        'required empty for Done' | [app_type: 'notifier']                                                 | 'Complete required native fields before navigating: origLabel'
        'label given twice'       | [app_type: 'notifier', name: 'N', fields: [origLabel: 'M']]            | 'Pass the label once'
    }

    def 'name is refused, and the new app removed, when the first page renders no label input'() {
        given:
        parentAt(47L, 'childButtons', 'Button Controller-5.1')
        childAt(92L, 47L, 'Button Controller-5.1', [page(92L, [input('buttonDev', 'capability.pushableButton', [multiple: true, required: true])])])

        when:
        def result = create([app_type: 'button_controller', name: 'Kitchen', fields: [buttonDev: ['7']]])

        then:
        !result.success
        result.error.contains('renders no label input (origLabel or name)')
        result.error.contains('The new app was removed, so nothing was created.')
        deleted == [92]
        posts.isEmpty()
    }

    def 'a refused create whose removal fails reports the app it left'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs())])
        deleteFails = true

        when:
        def result = create([app_type: 'notifier', name: 'N', fields: [nope: 1]])

        then:
        !result.success
        result.app_id == 90L
        result.error.contains('Removing the unfinished app then failed')
        result.note.contains('hub_delete_native_app')
        posts.isEmpty()
    }

    def 'a first page that cannot be read removes the new app'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [{ -> throw new IOException('read timed out') }])

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.app_id == 90L
        result.orphan_cleanup == 'removed'
        deleted == [90]
        posts.isEmpty()
    }

    def 'a lost response to the Done keeps the new app and says how to inspect it'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs())])
        postFailure = { String path, Map body -> throw new IOException('read timed out') }

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.partial
        result.app_id == 90L
        result.note.contains('do not create it again')
        deleted.isEmpty()
    }

    def 'a read-back failure after the Done reports the written fields as unverified'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()), { -> throw new IOException('read timed out') }])

        when:
        def result = create([app_type: 'notifier', name: 'N', fields: [logging: true]])

        then:
        !result.success
        result.app_id == 90L
        result.fields_unverified == ['logging', 'origLabel']
        result.error.contains('reading it back failed')
        deleted.isEmpty()
    }

    def 'a field the hub did not store as written fails the create and is named'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()),
            page(90L, notifierInputs(), [label: 'N', installed: true, settings: [origLabel: 'N', logging: 'false']])])

        when:
        def result = create([app_type: 'notifier', name: 'N', fields: [logging: true]])

        then:
        !result.success
        result.fields_not_landed == ['logging']
        result.fields_applied == ['origLabel']
        result.note.contains('did not store the value written for logging')
    }

    def 'a submitOnChange input posts first and the Done goes on the page that post renders, with its new defaults'() {
        given:
        parentAt(45L, 'Thermostat Scheduler 2.0', 'Thermostat Scheduler 2.0')
        List first = [input('therm', 'capability.switch', [multiple: true, required: true, submitOnChange: true])]
        List revealed = first + [input('setOnStart', 'bool', [defaultValue: true])]
        childAt(93L, 45L, 'Thermostat Scheduler 2.0', [page(93L, first),
            page(93L, revealed, [label: 'Sched', installed: true, settings: [therm: ['7': 'Lamp'], setOnStart: 'true']])])
        replies['/installedapp/update/json'] = [page(93L, revealed, [settings: [therm: ['7': 'Lamp']]]), DONE]

        when:
        def result = create([app_type: 'thermostat_scheduler', fields: [therm: ['7']]])

        then:
        result.success
        posts.size() == 2
        !posts[0].body.containsKey('_action_update')
        posts[0].body['settings[therm]'] == '7'
        posts[1].body._action_update == 'Done'
        posts[1].body['settings[setOnStart]'] == 'true'
        !posts[1].body.containsKey('settings[therm]')
        result.defaults_posted == ['setOnStart']
        result.fields_applied == ['therm']
    }

    def 'a first page with no Done is saved as the page posts it and reported uninstalled'() {
        given:
        parentAt(46L, 'childButtons', 'Basic Rule-1.0')
        List inputs = [input('comments', 'textarea')]
        childAt(94L, 46L, 'Basic Rule-1.0', [page(94L, inputs, [install: false]), page(94L, inputs, [install: false, settings: [comments: 'note']])])

        when:
        def result = create([app_type: 'basic_rule', fields: [comments: 'note']])

        then:
        result.success
        result.installed == false
        posts.size() == 1
        !posts[0].body.containsKey('_action_update')
        result.note.contains('renders no Done yet')
    }

    def 'a create through the native gateway returns the new app id'() {
        given:
        settingsMap.useGateways = true
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()), page(90L, notifierInputs(), [label: 'GW', installed: true, settings: [origLabel: 'GW']])])

        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_native_app',
            [operation: 'create', args: [app_type: 'notifier', name: 'GW'], confirm: true])

        then:
        result.isError != true
        result.app_id == 90L
        result.label == 'GW'
    }

    // ---------------------------------------------------------------- update

    def 'an update sets the fields and presses the page Done in one post, then reads the app back once'() {
        given:
        existing(70L)
        List inputs = [input('origLabel', 'text', [required: true]), input('logging', 'bool'), input('count', 'number', [defaultValue: 5])]
        pages['/installedapp/configure/json/70'] = [page(70L, inputs, [settings: [origLabel: 'N']]),
            page(70L, inputs, [settings: [origLabel: 'N', logging: 'true', count: 5]])]

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        result.success
        result.app_id == 70L
        result.fields_applied == ['logging']
        result.defaults_posted == ['count']
        result.backup.skipped == true
        posts.size() == 1
        posts[0].body['settings[logging]'] == 'true'
        posts[0].body['settings[count]'] == '5'
        !posts[0].body.containsKey('settings[origLabel]')
        posts[0].body._action_update == 'Done'
        !posts.any { it.path == '/installedapp/btn' }
        gets.count { it == '/installedapp/configure/json/70' } == 2
    }

    def 'a sub-page update presses its Done back to the main page, whose Done then commits the app'() {
        given:
        existing(71L, 'Room Lights')
        List sub = [input('motions', 'capability.switch', [multiple: true, required: true])]
        List main = [input('origLabel', 'text')]
        pages['/installedapp/configure/json/71/onMeansPage'] = page(71L, sub, [page: 'onMeansPage', install: false])
        pages['/installedapp/configure/json/71'] = page(71L, main, [settings: [origLabel: 'Room', motions: ['8': 'Fan']]])
        replies['/installedapp/update/json'] = [page(71L, main, [settings: [origLabel: 'Room']]), DONE]

        when:
        def result = update(71L, [fields: [motions: ['8']], page: 'onMeansPage'])

        then:
        result.success
        result.page == 'onMeansPage'
        result.fields_applied == ['motions']
        posts.size() == 2
        posts[0].body.currentPage == 'onMeansPage'
        posts[0].body.pageBreadcrumbs == '%5B%22mainPage%22%5D'
        posts[0].body['settings[motions]'] == '8'
        posts[0].body._action_previous == 'Done'
        posts[1].body.currentPage == 'mainPage'
        posts[1].body._action_update == 'Done'
    }

    def 'a sub-page update on an app whose root page is not named mainPage returns to that root'() {
        given:
        apps[73L] = [id: 73L, appType: [namespace: 'mcptest', name: 'BAT user app'], disabled: false]
        List sub = [input('subNote', 'text')]
        List main = [input('note', 'text')]
        pages['/installedapp/configure/json/73'] = page(73L, main, [page: 'pageMain', settings: [note: 'root', subNote: 'x']])
        pages['/installedapp/configure/json/73/pageSub'] = page(73L, sub, [page: 'pageSub', install: false])
        replies['/installedapp/update/json'] = [page(73L, main, [page: 'pageMain', settings: [note: 'root']]), DONE]

        when:
        def result = update(73L, [fields: [subNote: 'x'], page: 'pageSub'])

        then:
        result.success
        result.fields_applied == ['subNote']
        posts.size() == 2
        posts[0].body.currentPage == 'pageSub'
        posts[0].body.pageBreadcrumbs == '%5B%22pageMain%22%5D'
        posts[0].body._action_previous == 'Done'
        posts[1].body.currentPage == 'pageMain'
        posts[1].body._action_update == 'Done'
    }

    def 'naming the root page itself is not a sub-page update, whatever the root is called'() {
        given:
        apps[73L] = [id: 73L, appType: [namespace: 'mcptest', name: 'BAT user app'], disabled: false]
        pages['/installedapp/configure/json/73'] = [page(73L, [input('note', 'text')], [page: 'pageMain']),
            page(73L, [input('note', 'text')], [page: 'pageMain', settings: [note: 'root']])]

        when:
        def result = update(73L, [fields: [note: 'root'], page: 'pageMain'])

        then:
        result.success
        posts.size() == 1
        posts[0].body.currentPage == 'pageMain'
        posts[0].body._action_update == 'Done'
        !gets.any { it.endsWith('/pageMain') }
    }

    @Unroll
    def 'an update the page refuses writes nothing: #label'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = page(70L, notifierInputs(), [settings: [origLabel: 'N']])

        when:
        update(70L, [fields: fields])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)
        posts.isEmpty()

        where:
        label               | fields                 | message
        'unknown field'     | [nope: 1]              | 'Field is not present on the current native page: nope'
        'button in fields'  | [go: 'x']              | 'go is a button on the current native page'
        'bad enum'          | [devType: 'Fax']       | 'Invalid native choice for devType'
        'unknown device id' | [switches: ['99']]     | 'Invalid native choice for switches'
        'required emptied'  | [origLabel: '']        | 'Required field: origLabel'
        'object value'      | [logging: [a: 1]]      | 'Scalar values are required for logging'
    }

    def 'a device input takes the {id: label} map a configuration read returns, posted as its ids'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]),
            page(70L, notifierInputs(), [settings: [origLabel: 'N', switches: ['7': 'Lamp', '8': 'Fan']]])]

        when:
        def result = update(70L, [fields: [switches: ['7': 'a', '8': 'b']]])

        then:
        result.success
        result.fields_applied == ['switches']
        posts[0].body['settings[switches]'] == '7,8'
        posts[0].body['switches.multiple'] == 'true'
    }

    def 'a page the hub renders under another name is refused before any write'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = page(70L, notifierInputs())
        pages['/installedapp/configure/json/70/noSuchPage'] = page(70L, notifierInputs(), [page: ''])

        when:
        update(70L, [fields: [logging: true], page: 'noSuchPage'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("renders no page 'noSuchPage'")
        posts.isEmpty()
    }

    def 'a disabled app is refused before any read'() {
        given:
        apps[70L] = [id: 70L, appType: [namespace: 'hubitat', name: 'Notifier'], disabled: true]

        when:
        update(70L, [fields: [logging: true]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Enable the app')
        gets.isEmpty()
    }

    def 'an app another form operation holds is refused before any read'() {
        given:
        existing(70L)
        sharedMap('NATIVE_FORM_BUSY')[script._nfKey(70L)] = true

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        !result.success
        result.error.contains('active form operation')
        result.note.contains('retry')
        gets.isEmpty()
        sharedMap('NATIVE_FORM_BUSY')[script._nfKey(70L)]
    }

    def 'a lost response to the Done is a partial failure with its backup and recovery'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = page(70L, notifierInputs(), [settings: [origLabel: 'N']])
        postFailure = { String path, Map body -> throw new IOException('read timed out') }

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        !result.success
        result.partial
        result.backup.skipped == true
        result.note.contains('Some fields may already be saved')
        result.note.contains("operation:'form'")
    }

    def 'a read-back failure after a fully posted update reports the fields unverified, not partial'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]), { -> throw new IOException('read timed out') }]

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        !result.success
        !result.containsKey('partial')
        result.fields_unverified == ['logging']
        result.error == 'App 70 was updated, but reading it back failed: read timed out'
        result.note.contains('hub_get_app_config(appId=70, includeSettings=true)')
        result.backup.skipped == true
        posts.size() == 1
    }

    def 'a field the hub did not store as written fails the update and is named'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]),
            page(70L, notifierInputs(), [settings: [origLabel: 'N', devType: 'Email']])]

        when:
        def result = update(70L, [fields: [devType: 'Push', logging: true]])

        then:
        !result.success
        result.fields_not_landed == ['devType', 'logging']
        result.fields_applied == []
    }

    def 'a password is written and read back by presence only, and never returned'() {
        given:
        existing(72L, 'Community App')
        apps[72L].appType.namespace = 'someDeveloper'
        List inputs = [input('p', 'password'), input('note', 'text')]
        pages['/installedapp/configure/json/72'] = [page(72L, inputs), page(72L, inputs, [settings: [p: 'BAT-secret', note: 'n']])]

        when:
        def result = update(72L, [fields: [p: 'BAT-secret', note: 'n']])

        then:
        result.success
        result.fields_applied == ['p', 'note']
        posts[0].body['settings[p]'] == 'BAT-secret'
        !JsonOutput.toJson(result).contains('BAT-secret')
    }

    def 'an input whose save reveals a required one stops before the Done and says what was saved'() {
        given:
        existing(71L, 'Room Lights')
        List sub = [input('onMeans', 'enum', [multiple: true, submitOnChange: true, options: ['motion becomes active']])]
        pages['/installedapp/configure/json/71'] = page(71L, [input('origLabel', 'text')])
        pages['/installedapp/configure/json/71/onMeansPage'] = page(71L, sub, [page: 'onMeansPage', install: false])
        replies['/installedapp/update/json'] = [page(71L, sub + [input('motions', 'capability.switch', [multiple: true, required: true])],
            [page: 'onMeansPage', install: false, settings: [onMeans: ['motion becomes active']]])]

        when:
        def result = update(71L, [fields: [onMeans: ['motion becomes active']], page: 'onMeansPage'])

        then:
        !result.success
        result.partial
        result.error.contains('onMeans saved, but the page that save rendered refused its Done')
        result.error.contains('motions')
        posts.size() == 1
        !posts[0].body.containsKey('_action_previous')
    }

    def 'a page with no Done is saved as it posts and the note says the update did not run'() {
        given:
        existing(73L, 'Basic Rule-1.0')
        List inputs = [input('comments', 'textarea')]
        pages['/installedapp/configure/json/73'] = [page(73L, inputs, [install: false]), page(73L, inputs, [install: false, settings: [comments: 'x']])]

        when:
        def result = update(73L, [fields: [comments: 'x']])

        then:
        result.success
        result.committed == false
        posts.size() == 1
        !posts[0].body.containsKey('_action_update')
        result.note.contains("Page 'mainPage' renders no Done, so the fields were saved as the page posts them and the app's update did not run.")
    }

    // ---------------------------------------------------------------- button_rule

    private Map controllerPage(List inputs) { page(60L, inputs) }

    private static Map addButton() { input('true', 'button', [title: 'Add Button', state: 'addBut', submitOnChange: true]) }

    private static Map setButton(String name) { input(name, 'button', [title: "Create Button Rule for ${name}".toString(), state: 'setBut', submitOnChange: true]) }

    private void controller(String typeName = 'Button Controller-5.1') {
        apps[60L] = [id: 60L, appType: [namespace: 'hubitat', name: typeName], disabled: false]
    }

    private Map openedRule(Long id) {
        [app: [id: 60L], configPage: [name: 'mainPage', sections: [[body: [[element: 'paragraph',
            description: "<script>{window.open('/installedapp/configure/${id}/selectActions?resetScroll=true', '_self');}</script>".toString()]]]]]]
    }

    def 'a button rule runs the controller page: Add Button, the number, the reload, then that event create control'() {
        given:
        controller()
        apps[95L] = [id: 95L, parentAppId: 60L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1'], label: 'Btn: button 1 pushed', disabled: false]
        pages['/installedapp/configure/json/60'] = [controllerPage([addButton()]), controllerPage([setButton('1 pushed'), setButton('1 held'), addButton()])]
        replies['/installedapp/update/json'] = [
            controllerPage([input('newBut', 'enum', [multiple: true, submitOnChange: true, options: ['1', '2']]), input('cancelBtn', 'button', [title: 'Cancel'])]),
            controllerPage([]),
            openedRule(95L)]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        result.success
        result.app_id == 95L
        result.label == 'Btn: button 1 pushed'
        result.controller_id == 60L
        result.backup.skipped == true
        posts*.path == ['/installedapp/btn', '/installedapp/update/json', '/installedapp/update/json', '/installedapp/btn', '/installedapp/update/json']
        posts[0].body.stateAttribute == 'addBut'
        posts[2].body['settings[newBut]'] == '["1"]'
        posts[3].body.name == '1 pushed'
        posts[3].body.stateAttribute == 'setBut'
        gets.count { it == '/installedapp/configure/json/60' } == 2
    }

    def 'a button the controller already defines goes straight to its create control'() {
        given:
        controller()
        apps[95L] = [id: 95L, parentAppId: 60L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1'], label: 'B', disabled: false]
        pages['/installedapp/configure/json/60'] = controllerPage([setButton('1 held'), addButton()])
        replies['/installedapp/update/json'] = [openedRule(95L)]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'held'])

        then:
        result.success
        result.app_id == 95L
        posts*.body*.stateAttribute == ['setBut', null]
    }

    def 'a button number the Add Button picker does not offer is refused after the page Cancel'() {
        given:
        controller()
        pages['/installedapp/configure/json/60'] = controllerPage([addButton()])
        pages['/installedapp/configure/json/60/mainPage'] = controllerPage([addButton()])
        replies['/installedapp/update/json'] = [controllerPage([input('newBut', 'enum', [multiple: true, submitOnChange: true, options: ['2', '3']]), input('cancelBtn', 'button', [title: 'Cancel'])])]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        !result.success
        !result.partial
        result.error.contains('offers buttons [2, 3], not 1')
        result.error.contains('The controller was left unchanged.')
        result.note.contains('Cancel was pressed; the controller renders as before.')
        result.backup.skipped == true
        posts.findAll { it.path == '/installedapp/btn' }*.body*.name == ['true', 'cancelBtn']
        !posts.any { it.body.stateAttribute == 'setBut' }
        gets.count { it == '/installedapp/configure/json/60' } == 2
    }

    @Unroll
    def 'a button rule the controller page cannot make is refused before any write: #label'() {
        given:
        controller(typeName)
        pages['/installedapp/configure/json/60'] = controllerPage(inputs)

        when:
        create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)
        posts.isEmpty()

        where:
        label                     | typeName                | inputs                              | message
        'event already has one'   | 'Button Controller-5.1' | [setButton('1 held'), addButton()]   | 'offers no rule to create for pushed'
        'no button device yet'    | 'Button Controller-5.1' | []                                  | 'renders no Add Button control'
        'not a Button Controller' | 'Rule-5.1'              | [addButton()]                       | 'is not an installed Button Controller'
    }

    // ---------------------------------------------------------------- review round 2

    @Unroll
    def 'a created child that is not the requested type under the parent is left in place and its id named: #label'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        replies.createdId = 90L
        if (record != null) apps[90L] = record

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.app_id == 90L
        result.error.contains('Created app 90 did not match the native parent and child type')
        result.error.contains('left in place')
        result.note.contains('hub_get_app_config(appId=90)')
        deleted.isEmpty()
        posts.isEmpty()
        !gets.contains('/installedapp/configure/json/90')

        where:
        label           | record
        'wrong parent'  | [id: 90L, parentAppId: 41L, appType: [namespace: 'hubitat', name: 'Notifier'], disabled: false]
        'no app record' | null
    }

    def 'a create whose first post reveals a required input removes the app and does not ask for another call'() {
        given:
        parentAt(45L, 'Thermostat Scheduler 2.0', 'Thermostat Scheduler 2.0')
        List first = [input('therm', 'capability.switch', [multiple: true, required: true, submitOnChange: true])]
        childAt(93L, 45L, 'Thermostat Scheduler 2.0', [page(93L, first)])
        replies['/installedapp/update/json'] = [page(93L, first + [input('heatSet', 'number', [required: true])], [settings: [therm: ['7': 'Lamp']]])]

        when:
        def result = create([app_type: 'thermostat_scheduler', fields: [therm: ['7']]])

        then:
        !result.success
        !result.containsKey('app_id')
        result.error == 'The first page accepted therm, but the page that save rendered needs more before its Done (Complete required native fields before navigating: heatSet). ' +
            'The new app was removed, so nothing was created; supply the revealed input in fields, or create without therm and finish with update or form.'
        !result.error.contains('another call')
        !result.error.contains('saved')
        deleted == [93]
        posts.size() == 1
        !posts[0].body.containsKey('_action_update')
    }

    def 'a force delete that answers without a status while the app still exists is reported as a failed removal'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs())])
        deleteSilent = true

        when:
        def result = create([app_type: 'notifier', name: 'N', fields: [nope: 1]])

        then:
        !result.success
        result.app_id == 90L
        result.error.contains('Removing the unfinished app then failed: the app still exists after its force delete')
        !result.error.contains('was removed')
        result.note.contains('Delete app 90 with hub_delete_native_app')
        posts.isEmpty()
    }

    def 'a create whose first page renders only name writes the label there'() {
        given:
        parentAt(48L, 'childGroups', 'Group-2.1')
        List inputs = [input('name', 'text', [required: true])]
        childAt(96L, 48L, 'Group-2.1', [page(96L, inputs), page(96L, inputs, [label: 'Hall group', installed: true, settings: [name: 'Hall group']])])

        when:
        def result = create([app_type: 'groups_scenes', name: 'Hall group'])

        then:
        result.success
        result.app_id == 96L
        result.fields_applied == ['name']
        raws == ['/installedapp/createchild/hubitat/Group-2.1/parent/48']
        posts.size() == 1
        posts[0].body['settings[name]'] == 'Hall group'
        !posts[0].body.containsKey('settings[origLabel]')
    }

    def 'a create whose Done the hub answers by rendering the page again reports the app not installed'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs()),
            page(90L, notifierInputs(), [label: 'N', installed: true, settings: [origLabel: 'N']])])
        replies['/installedapp/update/json'] = [page(90L, notifierInputs(), [settings: [origLabel: 'N']])]

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        result.success
        result.installed == false
        result.note.contains('instead of accepting its Done, so the app is not installed')
        posts*.body*._action_update == ['Done']
        deleted.isEmpty()
    }

    def 'a cached parent that no longer exists is dropped and the refusal names its type'() {
        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.error.contains('The Notifications app recorded for this type no longer exists')
        result.note.contains('Retry')
        !atomicStateMap.parentAppIds.containsKey('notifier')
        atomicStateMap.parentAppIds.rule_machine == 43
        raws.isEmpty()
        posts.isEmpty()
    }

    def 'a create while another form operation holds the parent is refused before anything is created'() {
        given:
        parentAt(40L, 'notifierChild', 'Notifier')
        sharedMap('NATIVE_FORM_BUSY')[script._nfKey(40L)] = true

        when:
        def result = create([app_type: 'notifier', name: 'N'])

        then:
        !result.success
        result.error.contains('active form operation')
        raws.isEmpty()
        gets.isEmpty()
        sharedMap('NATIVE_FORM_BUSY')[script._nfKey(40L)]
    }

    def 'without a recent backup a create makes no child and an update posts nothing'() {
        given:
        stateMap.lastBackupTimestamp = 0
        parentAt(40L, 'notifierChild', 'Notifier')
        childAt(90L, 40L, 'Notifier', [page(90L, notifierInputs())])
        existing(70L)
        pages['/installedapp/configure/json/70'] = page(70L, notifierInputs(), [settings: [origLabel: 'N']])

        when:
        create([app_type: 'notifier', name: 'N'])

        then:
        def refusedCreate = thrown(IllegalArgumentException)
        refusedCreate.message.contains('BACKUP REQUIRED')
        raws.isEmpty()

        when:
        update(70L, [fields: [logging: true]])

        then:
        def refusedUpdate = thrown(IllegalArgumentException)
        refusedUpdate.message.contains('BACKUP REQUIRED')
        posts.isEmpty()
        !gets.any { it.startsWith('/installedapp/configure/json/') }
    }

    def 'an update drops the caller cached form context of the app it changed'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]),
            page(70L, notifierInputs(), [settings: [origLabel: 'N', logging: 'true']])]
        sharedMap('NATIVE_FORM_CONTEXTS')[script._nfCallerKey()] = [app_id: 70L, page: [name: 'mainPage']]

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        result.success
        !sharedMap('NATIVE_FORM_CONTEXTS').containsKey(script._nfCallerKey())
    }

    def 'an app whose default page does not render is refused before any write'() {
        given:
        existing(70L)

        when:
        update(70L, [fields: [logging: true]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("App 70 renders no page 'its default page'")
        e.message.contains('Nothing was written.')
        posts.isEmpty()
    }

    def 'a sub-page update whose returned main page renders no Done reports the app uncommitted'() {
        given:
        existing(71L, 'Room Lights')
        List sub = [input('motions', 'capability.switch', [multiple: true, required: true])]
        List main = [input('origLabel', 'text')]
        pages['/installedapp/configure/json/71/onMeansPage'] = page(71L, sub, [page: 'onMeansPage', install: false])
        pages['/installedapp/configure/json/71'] = page(71L, main, [settings: [origLabel: 'Room', motions: ['8': 'Fan']]])
        replies['/installedapp/update/json'] = [page(71L, main, [install: false, settings: [origLabel: 'Room']])]

        when:
        def result = update(71L, [fields: [motions: ['8']], page: 'onMeansPage'])

        then:
        result.success
        result.committed == false
        result.fields_applied == ['motions']
        result.note.contains("Page 'mainPage', which the sub-page returned to, renders no Done, so the app's update did not run.")
        posts.size() == 1
        posts[0].body._action_previous == 'Done'
    }

    def 'a Done the hub answers without a location reports the app uncommitted'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]),
            page(70L, notifierInputs(), [settings: [origLabel: 'N', logging: 'true']])]
        replies['/installedapp/update/json'] = [[status: 'success']]

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        result.success
        result.committed == false
        result.fields_applied == ['logging']
        result.note.contains("The hub rendered page 'mainPage' again instead of accepting its Done, so the app's update did not run")
        posts*.body*._action_update == ['Done']
    }

    def 'an accepted Done reports no committed flag'() {
        given:
        existing(70L)
        pages['/installedapp/configure/json/70'] = [page(70L, notifierInputs(), [settings: [origLabel: 'N']]),
            page(70L, notifierInputs(), [settings: [origLabel: 'N', logging: 'true']])]

        when:
        def result = update(70L, [fields: [logging: true]])

        then:
        result.success
        !result.containsKey('committed')
        !result.containsKey('note')
    }

    def 'a time, a mode list and a multi-select enum read back as the hub stores them'() {
        given:
        existing(74L, 'Community App')
        List inputs = [input('at', 'time'), input('modes', 'mode', [multiple: true]), input('pick', 'enum', [multiple: true, options: ['a', 'b', 'c']])]
        Map modeMap = ['1': 'Day', '2': 'Night', '3': 'Away']
        pages['/installedapp/configure/json/74'] = [page(74L, inputs, [modeMap: modeMap]),
            page(74L, inputs, [modeMap: modeMap, settings: [at: '2026-10-26T07:30:00.000+0000', modes: [2, 1], pick: ['a', 'b']]])]

        when:
        def result = update(74L, [fields: [at: '2026-10-25T07:30:00.000+0000', modes: ['1', '2'], pick: ['b', 'a']]])

        then:
        result.success
        result.fields_applied == ['at', 'modes', 'pick']
        !result.containsKey('fields_not_landed')
        posts[0].body['settings[modes]'] == '["1","2"]'
        posts[0].body['settings[pick]'] == '["b","a"]'
    }

    def 'a time stored at another clock is named as not landed'() {
        given:
        existing(74L, 'Community App')
        List inputs = [input('at', 'time')]
        pages['/installedapp/configure/json/74'] = [page(74L, inputs), page(74L, inputs, [settings: [at: '2026-10-25T08:30:00.000+0000']])]

        when:
        def result = update(74L, [fields: [at: '2026-10-25T07:30:00.000+0000']])

        then:
        !result.success
        result.fields_not_landed == ['at']
    }

    private Map pickerPage(List options) {
        controllerPage([input('newBut', 'enum', [multiple: true, submitOnChange: true, options: options]), input('cancelBtn', 'button', [title: 'Cancel'])])
    }

    def 'a picker Cancel after which the controller still renders the picker is a partial failure'() {
        given:
        controller()
        pages['/installedapp/configure/json/60'] = [controllerPage([addButton()]), pickerPage(['2', '3'])]
        pages['/installedapp/configure/json/60/mainPage'] = pickerPage(['2', '3'])
        replies['/installedapp/update/json'] = [pickerPage(['2', '3'])]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        !result.success
        result.partial
        result.error.contains('Its Cancel was pressed, but the controller still renders the Add Button picker')
        !result.note.contains('renders as before')
        !result.note.contains('Nothing was changed')
        result.note.contains("hub_set_native_app(operation:'form', app_id:60")
    }

    def 'a Cancel post that fails during the picker refusal is a partial failure'() {
        given:
        controller()
        pages['/installedapp/configure/json/60'] = controllerPage([addButton()])
        replies['/installedapp/update/json'] = [pickerPage(['2', '3'])]
        postFailure = { String path, Map body -> if (path == '/installedapp/btn' && body.name == 'cancelBtn') throw new IOException('read timed out') }

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        !result.success
        result.partial
        result.error.contains('read timed out')
        !result.note.contains('Nothing was changed')
    }

    def 'a button added without a rule control for the event is a partial failure'() {
        given:
        controller()
        pages['/installedapp/configure/json/60'] = [controllerPage([addButton()]), controllerPage([addButton()])]
        replies['/installedapp/update/json'] = [pickerPage(['1', '2']), controllerPage([])]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        !result.success
        result.partial
        result.error.contains('Button 1 was added, but the controller offers no rule to create for pushed')
        !result.note.contains('Nothing was changed')
        posts.find { it.body.containsKey('settings[newBut]') }.body['settings[newBut]'] == '["1"]'
    }

    @Unroll
    def 'a rule control that opens no new Button Rule is a partial failure with its recovery backup: #label'() {
        given:
        controller()
        settingsMap.backupBeforeRuleEdits = true
        script.metaClass._rmBackupBeforeEdit = { Integer id, String reason, String page = null -> [backupKey: 'k1'] }
        if (other != null) apps[other.id] = other
        pages['/installedapp/configure/json/60'] = controllerPage([setButton('1 pushed'), addButton()])
        replies['/installedapp/update/json'] = [other == null ? controllerPage([]) : openedRule(other.id)]

        when:
        def result = create([app_type: 'button_rule', controller_id: 60, button_number: 1, event: 'pushed'])

        then:
        !result.success
        result.partial
        result.error.contains('The controller did not open a new Button Rule')
        result.backup.backupKey == 'k1'
        result.note.contains("hub_restore_backup(backupKey:'k1')")
        !result.note.contains('Nothing was changed')
        result.opened_app_id == (other == null ? null : other.id)
        result.containsKey('opened_app_id') == (other != null)

        where:
        label                   | other
        'no app opened'         | null
        'another app type'      | [id: 96L, parentAppId: 60L, appType: [namespace: 'hubitat', name: 'Notifier'], label: 'N', disabled: false]
    }
}
