package server

import support.ToolSpecBase
import support.TestLocation
import spock.lang.Shared

class NativeRuntimeSpec extends ToolSpecBase {
    @Shared TestLocation sharedLocation = new TestLocation()
    Map domain
    List children
    Map nativeState
    List calls
    int lookups
    Map typedSettings
    boolean ignoreWrites

    void setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    void setup() {
        sharedLocation.timeZone = TimeZone.getTimeZone('UTC')
        nativeState = [keep: 'unchanged', cursor: 1]
        calls = []
        typedSettings = [:]
        ignoreWrites = false
        lookups = 0
        domain = [id: 42L, parentAppId: 28L, appType: [name: 'Rule-5.1', namespace: 'hubitat']]
        children = null
        script.metaClass.getAppByAppId = { Long id -> id == 42L ? domain : null }
        script.metaClass.getChildAppsByParentId = { Long id ->
            lookups++
            children != null ? children : [new NativeRuntimeChild(42L, nativeState, calls, typedSettings, ignoreWrites)]
        }
    }

    def 'wrong ID type namespace and parent are rejected before mutation'() {
        when:
        script._nrHandle(99L)
        then:
        thrown(IllegalArgumentException)
        when:
        domain.appType.name = 'Other App'
        script._nrStatePatch(42L, [x: 1])
        then:
        thrown(IllegalArgumentException)
        when:
        domain.appType.name = 'Rule-5.1'
        domain.appType.namespace = 'custom'
        script._nrHandle(42L)
        then:
        thrown(IllegalArgumentException)
        when:
        domain.appType.namespace = 'hubitat'
        domain.parentAppId = null
        script._nrHandle(42L)
        then:
        thrown(IllegalArgumentException)
        calls.empty
    }

    def 'lookup requires exact child ID and accepts native button rules'() {
        given:
        children = [new NativeRuntimeChild(43L, nativeState, calls)]
        when:
        script._nrHandle(42L)
        then:
        thrown(IllegalArgumentException)
        when:
        children = null
        domain.appType.name = 'Button Rule-5.1'
        def handle = script._nrHandle(42L)
        then:
        handle.id == 42L
    }

    def 'state reload observes a native page change'() {
        when:
        def before = script._nrState(42L)
        def outcome = script._nrPage(42L, 'doActPage')
        def after = script._nrState(42L)
        then:
        before.cursor == 1
        after.cursor == 2
        lookups == 3
        outcome.verificationNeeded
        outcome.result == 'page-result'
    }

    def 'patch reloads and preserves unrelated keys while removing only named keys'() {
        when:
        def result = script._nrStatePatch(42L, [cursor: 0, empty: false], ['keep'])
        then:
        nativeState == [cursor: 0, empty: false]
        calls*.name == ['setState', 'saveState']
        result.verificationNeeded
    }

    def 'setting rows retain false zero and empty exactly'() {
        when:
        def result = script._nrWriteSettings(42L, [[name: 'flag', type: 'bool', value: false],
                [name: 'count', type: 'number', value: 0], [name: 'empty', type: 'text', value: '']])
        then:
        calls*.value == [false, 0, '']
        result.verificationNeeded
        result.attempted == ['flag', 'count', 'empty']
        result.settingsVerified
        result.settings.flag.value == false
        result.settings.count.value == 0
        result.settings.empty.value == ''
    }

    def 'time verification preserves the native local clock across date and DST normalization'() {
        given:
        sharedLocation.timeZone = TimeZone.getTimeZone('America/New_York')
        script.metaClass.toDateTime = { String value ->
            Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", value)
        }
        expect:
        script._nrComparableSetting('time', '2026-01-01T23:59:00.000+0000') ==
                script._nrComparableSetting('time', '2026-10-20T18:59:00.000-0400')
        script._nrComparableSetting('time', '2026-01-01T23:59:00.000+0000') !=
                script._nrComparableSetting('time', '2026-10-20T19:59:00.000-0400')
    }

    def 'malformed native times refuse during descriptor validation'() {
        given:
        script.metaClass.toDateTime = { String value ->
            Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", value)
        }
        when:
        script._nrValidateInput([name: 'atTime1', type: 'time', multiple: false], '__invalid_time__')
        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'Invalid native time for atTime1'
        calls.empty
    }

    def 'all settings validate before the first update'() {
        when:
        script._nrWriteSettings(42L, [[name: 'good', type: 'text', value: 'x'],
                [name: 'device', type: 'device.custom', value: [1]]])
        then:
        thrown(IllegalArgumentException)
        calls.empty
        lookups == 0
    }

    def 'enum lists and capability IDs pass through without invented multiple metadata'() {
        when:
        def result = script._nrWriteSettings(42L, [[name: 'choices', type: 'enum', value: ['a', 'b']],
                [name: 'devices', type: 'capability.switch', value: [1, '2']],
                [name: 'device', type: 'capability.switch', value: '3']])
        then:
        result.verificationNeeded
        calls*.value == [['a', 'b'], [1, '2'], '3']
        result.success
        result.settingsVerified
    }

    def 'existing type mismatch rejects the entire batch before any update'() {
        given:
        typedSettings.old = [type: 'number', value: 7]
        when:
        def result = script._nrWriteSettings(42L, [[name: 'new', type: 'text', value: 'x'],
                [name: 'old', type: 'text', value: '7']])
        then:
        !result.success
        !result.partial
        result.reason == 'existing_type_mismatch'
        result.attempted.empty
        calls.empty
    }

    def 'silent native no-op is caught by fresh typed readback'() {
        given:
        ignoreWrites = true
        when:
        def result = script._nrWriteSettings(42L, [[name: 'a', type: 'text', value: 'wanted']])
        then:
        !result.success
        result.partial
        !result.settingsVerified
        result.rejected == ['a']
        lookups == 2
    }

    def 'known setting reads serialize device wrapper IDs and preserve false and zero'() {
        given:
        typedSettings.devices = [type: 'capability.switch', value: [new NativeRuntimeDevice(id: 3L), new NativeRuntimeDevice(id: 2L)]]
        typedSettings.flag = [type: 'bool', value: false]
        typedSettings.count = [type: 'number', value: 0]
        when:
        def result = script._nrSettings(42L, ['devices', 'flag', 'count'])
        then:
        result.devices.value == ['2', '3']
        result.flag.value == false
        result.count.value == 0
    }

    def 'null is rejected before writing because it does not reliably clear settings'() {
        when:
        script._nrWriteSettings(42L, [[name: 'good', type: 'text', value: 'x'],
                [name: 'clear', type: 'text', value: null]])
        then:
        thrown(IllegalArgumentException)
        calls.empty
        lookups == 0
    }

    def 'unknown page and unsupported page params are blocked before invoking target'() {
        when:
        script._nrPage(42L, 'delete')
        then:
        thrown(IllegalArgumentException)
        when:
        script._nrPage(42L, 'mainPage', [arbitrary: true])
        then:
        thrown(IllegalArgumentException)
        lookups == 0
        calls.empty
    }

    def 'button return never claims compilation success'() {
        when:
        def result = script._nrButton(42L, 'actionDone')
        then:
        result.verificationNeeded
        !result.containsKey('success')
        result.result == null
        calls*.name == ['actionDone']
    }
}

class NativeRuntimeChild {
    Long id
    Map backing
    Map snapshot
    List calls
    Map typedSettings
    boolean ignoreWrites
    NativeRuntimeChild(Long id, Map backing, List calls, Map typedSettings = [:], boolean ignoreWrites = false) {
        this.id = id
        this.typedSettings = typedSettings
        this.ignoreWrites = ignoreWrites
        this.backing = backing
        this.snapshot = [:] + backing
        this.calls = calls
    }
    Map getState() { snapshot }
    void setState(Map value) { snapshot = value; calls << [name: 'setState'] }
    void saveState() { backing.clear(); backing.putAll(snapshot); calls << [name: 'saveState'] }
    String getSettingType(String name) { typedSettings[name]?.type }
    Object getSetting(String name) {
        def row = typedSettings[name]
        if (row?.type?.startsWith('capability')) {
            def ids = row.value instanceof List ? row.value : [row.value]
            return ids.reverse().collect { id -> id instanceof NativeRuntimeDevice ? id : new NativeRuntimeDevice(id: id.toString().toLong()) }
        }
        row?.value
    }
    void updateSetting(String name, Map row) {
        calls << [name: name, type: row.type, value: row.value]
        if (!ignoreWrites) typedSettings[name] = [type: row.type, value: row.value]
    }
    Object doActPage() { backing.cursor = 2; 'page-result' }
    Object appButtonHandler(String name) { calls << [name: name]; null }
}

class NativeRuntimeDevice { Long id }
