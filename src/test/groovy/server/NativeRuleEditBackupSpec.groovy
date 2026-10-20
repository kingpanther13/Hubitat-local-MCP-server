package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeRuleEditBackupSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')
    List events
    boolean backupFails
    boolean renderFails
    Map nativeApp

    void setupSpec() { appExecutor.getApp() >> serverApp }

    void setup() {
        script.callerClient = null
        events = []
        backupFails = false
        renderFails = false
        settingsMap.enableMandatoryBPS = false
        stateMap.lastBackupTimestamp = script.now()
        nativeApp = [id: 42L, appType: [namespace: 'hubitat', name: 'Rule-5.1']]
        script.metaClass.getAppByAppId = { Long id -> nativeApp }
        script.metaClass._nrHandle = { Long id -> [:] }
        script.metaClass._ndPreflight = { Long id, List changes -> events << 'preflight' }
        script.metaClass._ndApply = { Long id, List changes, boolean created -> events << 'apply'; [success: true, rule_id: id] }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean requireNativeExport ->
            events << 'snapshot'
            assert requireNativeExport
            if (backupFails) throw new IllegalStateException('snapshot unavailable')
            [backupKey: 'rm-rule_42_fixture', ruleId: id]
        }
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            events << 'open'
            if (renderFails) throw new IllegalStateException('render failed')
            JsonOutput.toJson(form())
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false ->
            events << 'submit'
            [status: 200, data: JsonOutput.toJson(form())]
        }
        ['NATIVE_FORM_CONTEXTS', 'NATIVE_FORM_BUSY'].each { name ->
            def field = script.class.getDeclaredField(name)
            field.accessible = true
            field.get(null).clear()
        }
    }

    void cleanup() { script.callerClient = null }

    private static Map form() {
        [app: [id: 42, version: 1], configPage: [name: 'mainPage', sections: []], settings: [:]]
    }

    private static Map update() {
        [rule_id: 42, confirm: true, changes: [
            [part: 'required', value: [tokens: ['1']]],
            [part: 'required', value: [tokens: ['1', 'OR', '2']]]]]
    }

    def 'a whole document batch gets one fresh snapshot after preflight and before mutation'() {
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        result.success
        result.backup.backupKey == 'rm-rule_42_fixture'
        events == ['preflight', 'snapshot', 'apply']
    }

    def 'disabled backups preserve explicit opt out without affecting the edit'() {
        given:
        settingsMap.backupBeforeRuleEdits = false
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        result.success
        events == ['preflight', 'apply']
    }

    def 'failed snapshot prevents document mutation'() {
        given:
        backupFails = true
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        !result.success
        !result.partial
        events == ['preflight', 'snapshot']
    }

    def 'partial document changes retain their pre-edit recovery handle'() {
        given:
        script.metaClass._ndApply = { Long id, List changes, boolean created -> [success: false, partial: true] }
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        !result.success && result.partial
        result.backup.backupKey == 'rm-rule_42_fixture'
    }

    def 'rejected document preflight does not take a snapshot'() {
        given:
        script.metaClass._ndPreflight = { Long id, List changes -> throw new IllegalArgumentException('invalid edit') }
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        !result.success && !result.partial
        events.empty
    }

    def 'a form workflow snapshots before rendering and reuses its recovery handle through submissions'() {
        when:
        def opened = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open'], [kind: 'open']], confirm: true])
        def submitted = script.toolCallAppForm([app_id: 42, steps: [[kind: 'submit', expect_page: 'mainPage']], confirm: true])
        then:
        opened.success && submitted.success
        opened.backup.backupKey == submitted.backup.backupKey
        events == ['snapshot', 'open', 'open', 'submit']
        when:
        script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
        then:
        events.count('snapshot') == 2
    }

    def 'failed form snapshot stops before rendering and failed render retains recovery metadata'() {
        given:
        backupFails = true
        when:
        def refused = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
        then:
        !refused.success && !refused.partial
        events == ['snapshot']
        when:
        backupFails = false
        renderFails = true
        def partial = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
        then:
        !partial.success && partial.partial
        partial.backup.backupKey == 'rm-rule_42_fixture'
    }

    def 'Rule Machine parent forms explicitly do not promise child recovery'() {
        given:
        nativeApp.appType.name = 'Rule Machine'
        when:
        def result = script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true])
        then:
        result.success
        result.backup.skipped
        result.backup.reason.contains('child')
        events == ['open']
    }
}
