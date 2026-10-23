package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeRuleEditBackupSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')
    List events
    boolean backupFails
    boolean applyFails
    Map model
    boolean renderFails
    Map nativeApp

    void setupSpec() { appExecutor.getApp() >> serverApp }

    void setup() {
        script.callerClient = null
        events = []
        backupFails = false
        renderFails = false
        applyFails = false
        model = [capabsfalse: ['1': 'Switch on', '2': 'Switch off'], eval: [:], actNdx: 1]
        settingsMap.enableMandatoryBPS = false
        stateMap.lastBackupTimestamp = script.now()
        nativeApp = [id: 42L, appType: [namespace: 'hubitat', name: 'Rule-5.1']]
        script.metaClass.getAppByAppId = { Long id -> nativeApp }
        script.metaClass._nrHandle = { Long id -> new NativeDocumentChild([], [stopped: false, paused: false]) }
        script.metaClass._nrState = { Long id ->
            if (!events.contains('preflight')) events << 'preflight'
            [:] + model
        }
        script.metaClass._nmUpsertAction = { Long id, Map value ->
            if (!events.contains('apply')) events << 'apply'
            [success: !applyFails, partial: applyFails, id: '1']
        }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean requireNativeExport ->
            events << 'snapshot'
            assert requireNativeExport
            if (backupFails) throw new IllegalStateException('snapshot unavailable')
            [backupKey: 'rm-rule_42_fixture', ruleId: id]
        }
        script.metaClass.hubInternalGet = { String path, Map query = null, int timeout = 30, boolean retry = false ->
            if (path.startsWith('/app/ruleBuilderJson/')) return [broken: false, stopped: false, paused: false]
            if (path.startsWith('/installedapp/settings/')) return [settings: []]
            if (path.startsWith('/installedapp/statusJson/')) return [eventSubscriptions: [], scheduledJobs: []]
            events << 'open'
            if (renderFails) throw new IllegalStateException('render failed')
            JsonOutput.toJson(form())
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false ->
            if (path == '/installedapp/update/json' && body.contains('_action_update=Done')) return [status: 200]
            events << 'submit'
            [status: 200, data: JsonOutput.toJson(form())]
        }
        ['NATIVE_FORM_CONTEXTS', 'NATIVE_FORM_BUSY'].each { name ->
            def field = script.class.getDeclaredField(name)
            field.accessible = true
            field.get(null).clear()
        }
    }

    void cleanup() {
        script.callerClient = null
        sharedMap('NATIVE_FORM_CONTEXTS').clear()
        sharedMap('NATIVE_FORM_BUSY').clear()
    }

    private Map sharedMap(String name) {
        def field = script.class.getDeclaredField(name)
        field.accessible = true
        field.get(null) as Map
    }

    private void cacheForms() {
        sharedMap('NATIVE_FORM_CONTEXTS').putAll([
            target: [app_id: 42L], anotherCaller: [app_id: 42L], otherRule: [app_id: 43L]])
    }

    private static Map form() {
        [app: [id: 42, version: 1], configPage: [name: 'mainPage', sections: []], settings: [:]]
    }

    private static Map update() {
        [rule_id: 42, confirm: true, changes: [
            [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]],
            [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]]]]
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
        applyFails = true
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        !result.success && result.partial
        result.backup.backupKey == 'rm-rule_42_fixture'
    }

    def 'rejected document preflight does not take a snapshot'() {
        given:
        def invalid = update()
        invalid.changes = [[part: 'required', value: [tokens: ['999']]]]
        when:
        def result = script.toolUpdateRuleDocument(invalid)
        then:
        !result.success && !result.partial
        events == ['preflight']
    }

    def 'document contention refuses before preflight backup or mutation and preserves the owner lock'() {
        given:
        def key = script._nfKey(42L)
        sharedMap('NATIVE_FORM_BUSY')[key] = true
        cacheForms()
        when:
        script.toolUpdateRuleDocument(update())
        then:
        thrown(IllegalStateException)
        events.empty
        sharedMap('NATIVE_FORM_BUSY')[key]
        sharedMap('NATIVE_FORM_CONTEXTS').size() == 3
    }

    def 'document lock covers backup and apply while unrelated rules remain independent'() {
        given:
        def key = script._nfKey(42L)
        def other = script._nfKey(43L)
        sharedMap('NATIVE_FORM_BUSY')[other] = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict ->
            assert sharedMap('NATIVE_FORM_BUSY')[key]
            [backupKey: 'fixture']
        }
        script.metaClass._nmUpsertAction = { Long id, Map value ->
            assert sharedMap('NATIVE_FORM_BUSY')[key]
            [success: true]
        }
        cacheForms()
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        result.success
        !sharedMap('NATIVE_FORM_BUSY').containsKey(key)
        sharedMap('NATIVE_FORM_BUSY')[other]
        sharedMap('NATIVE_FORM_CONTEXTS').keySet() == ['otherRule'] as Set
    }

    def 'unexpected preflight exception is returned as a refusal and releases document lock without invalidating form contexts'() {
        given:
        cacheForms()
        script.metaClass._nrState = { Long id -> throw new IllegalStateException('read unavailable') }
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        result instanceof Map
        result.success == false
        result.partial == false
        result.rule_id == 42L
        result.error == 'read unavailable'
        result.note == 'Preflight could not read the rule; no change was written. Retry; if it persists, see the hub log.'
        sharedMap('NATIVE_FORM_BUSY').isEmpty()
        sharedMap('NATIVE_FORM_CONTEXTS').size() == 3
        events.empty
    }

    def 'failed preflight or backup releases lock and preserves existing form contexts'() {
        given:
        cacheForms()
        backupFails = !preflightFailure
        def args = update()
        if (preflightFailure) args.changes = [[part: 'required', value: [tokens: ['999']]]]
        when:
        def result = script.toolUpdateRuleDocument(args)
        then:
        !result.success && !result.partial
        sharedMap('NATIVE_FORM_BUSY').isEmpty()
        sharedMap('NATIVE_FORM_CONTEXTS').size() == 3
        where:
        preflightFailure << [true, false]
    }

    def 'failed mutation invalidates target form contexts and releases document lock'() {
        given:
        cacheForms()
        applyFails = true
        when:
        def result = script.toolUpdateRuleDocument(update())
        then:
        !result.success && result.partial
        sharedMap('NATIVE_FORM_BUSY').isEmpty()
        sharedMap('NATIVE_FORM_CONTEXTS').keySet() == ['otherRule'] as Set
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
