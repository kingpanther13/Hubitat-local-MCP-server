package server

import support.ToolSpecBase
import spock.lang.Unroll

class AppClonerSafetyParitySpec extends ToolSpecBase {

    @Unroll
    def "modern #operation reports that requested staging could not run on a discovery miss"() {
        given:
        def cleaned = []
        hubGet.register('/installedapp/configure/json/900/main') { '_action_href_name|importRule|0' }
        hubGet.register('/installedapp/configure/json/21') { '{"app":{"id":21},"childApps":[{"id":100}]}' }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 -> [status: 200] }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, Integer timeout = 420 -> [status: 200] }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            cleaned << path
            [status: 302]
        }
        def tool = "hub_${operation}_native_app".toString()
        def rec = [outerTool: tool, leafTool: tool, checkpoint: [
            phase: "${operation}_commit".toString(), clonerAppId: 900,
            sourceAppId: 100, originalSourceId: 100, parentAppId: 21,
            sourceLabel: 'Source', originalLabel: 'Source', preIds: ['100'], stageDisabled: true
        ]]

        when:
        def result = operation == 'clone' ? script._mrtrCloneNativeAppSlice(rec, [:]) :
            script._mrtrImportNativeAppSlice(rec, [:])

        then:
        result.success == false
        result.isError == true
        result.stagedDisabled == []
        result.error.contains('stageDisabled was requested but could NOT run')
        result.error.contains('ENABLED and live')
        result.error.contains('hub_set_app_disabled')
        cleaned == ['/installedapp/forcedelete/900/quiet']

        where:
        operation << ['clone', 'import']
    }

    def "modern staging failure identifies an enabled new app and a nonduplicating remedy"() {
        given:
        def cleaned = []
        script.metaClass.toolSetAppDisabled = { Map args -> [success: false, error: 'denied'] }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            cleaned << path
            [status: 302]
        }
        def cp = [newAppId: 200, clonerAppId: 900, stageTargets: [200],
                  stageFailures: [], stagedDisabled: [], baseResult: [success: true, newAppId: 200]]

        when:
        def result = script._mrtrAppClonerStageSlice(cp, 'clone')

        then:
        result.success == false
        result.isError == true
        result.partial == true
        result.stageFailures*.appId == [200]
        result.error.contains('NEW APP ITSELF (200)')
        result.error.contains('do NOT re-issue')
        result.error.contains('hub_set_app_disabled')
        cleaned == ['/installedapp/forcedelete/900/quiet']
    }

    def "staging continuation keeps prior failures and never repeats an attempted disable"() {
        given:
        def disabled = []
        def cleaned = []
        boolean overBudget = true
        script.metaClass._timeBudgetExceeded = { Long start -> overBudget }
        script.metaClass.toolSetAppDisabled = { Map args ->
            disabled << args.appId
            args.appId == 200 ? [success: false, error: 'denied'] : [success: true]
        }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            cleaned << path
            [status: 302]
        }
        def cp = [phase: 'stage_disable', newAppId: 200, clonerAppId: 900,
                  stageTargets: [200, 201, 202], stageFailures: [], stagedDisabled: [],
                  baseResult: [success: true, newAppId: 200]]

        when:
        def waiting = script._mrtrAppClonerStageSlice(cp, 'import')

        then:
        disabled == [200]
        cleaned == []
        cp.stageTargets == [201, 202]
        cp.stageFailures*.appId == [200]
        waiting.__mrtrContinue.kind == 'import_native_app'
        waiting.__mrtrContinue.checkpoint.stageTargets == [201, 202]

        when:
        overBudget = false
        def terminal = script._mrtrAppClonerStageSlice(cp, 'import')

        then:
        disabled == [200, 201, 202]
        terminal.success == false
        terminal.stagedDisabled == [201, 202]
        terminal.stageFailures*.appId == [200]
        terminal.error.contains('NEW APP ITSELF (200)')
        cleaned == ['/installedapp/forcedelete/900/quiet']
    }
}
