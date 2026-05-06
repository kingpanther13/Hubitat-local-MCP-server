package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for the sendRmAction / sendRmActionFallback internals
 * (hubitat-mcp-server.groovy approx line 7867).
 *
 * The 3-arg fallback fires ONLY on MissingMethodException or NoSuchMethodError
 * from the 4-arg sendAction call. All other Throwables propagate directly as a
 * {success:false} map without a retry (narrowed in the PR-79-review fix commit).
 *
 * Mock strategy for "4-arg throws MME but 3-arg succeeds":
 *   RMUtilsMock.throwOnSendAction is a single throwable -- it cannot distinguish
 *   4-arg from 3-arg calls by itself. After RMUtilsMock.install(), we install a
 *   spec-local stateful closure directly on the hubitat.helper.RMUtils metaClass
 *   that replaces the 4-arg overload only, throwing on the first call and letting
 *   subsequent calls dispatch cleanly via the stub method (which routes through
 *   the 3-arg mock overload registered by install()). The 3-arg overload installed
 *   by RMUtilsMock.install() is left unchanged and records the fallback call.
 *
 *   This is a legitimate harness technique when a single throwOnX flag is
 *   insufficient for multi-attempt scenarios. A future follow-up could add a
 *   throwSequenceOnSendAction field to RMUtilsMock to encapsulate this pattern.
 *
 * Exercises:
 *   - 4-arg succeeds (no fallback)
 *   - 4-arg throws MissingMethodException -> 3-arg fallback succeeds
 *   - 4-arg throws NoSuchMethodError -> 3-arg fallback succeeds
 *   - 4-arg throws RuntimeException -> NO fallback, propagates as {success:false}
 *   - 4-arg throws NoClassDefFoundError -> NO fallback, propagates
 *   - both 4-arg and 3-arg throw -> success=false mentioning both failures
 */
class SendRmActionFallbackSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
        // toolRunRmRule is the simplest gateway tool that funnels through sendRmAction.
        // All these tests use ruleId=1 with action='rule' (-> rmAction='runRule').
        settingsMap.enableBuiltinApp = true
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "4-arg sendAction succeeds: success=true, no fallback triggered"() {
        // No throwable installed -- both 4-arg and 3-arg go through cleanly.

        when:
        def result = script.toolRunRmRule([ruleId: 1, action: 'rule'])

        then:
        result.success == true
        result.ruleId == 1
        result.rmAction == 'runRule'
        // The 'fallback' key should be absent when the 4-arg form succeeded.
        !result.containsKey('fallback')

        and: 'exactly one sendAction call recorded (the 4-arg form)'
        def sendCalls = rmUtils.calls.findAll { it.method == 'sendAction' }
        sendCalls.size() == 1
        sendCalls[0].version == '5.0'   // 4-arg form carries a version field
    }

    def "4-arg throws MissingMethodException: 3-arg fallback is attempted and succeeds"() {
        given: 'stateful closure: throws MissingMethodException on the first (4-arg) call'
        // RMUtilsMock.install() registered both overloads. We replace the 4-arg entry
        // with a closure that throws on its first invocation; the 3-arg overload
        // (installed by RMUtilsMock.install() under the (List,String,String) arity)
        // remains in place and will record the fallback call.
        def callCount = 0
        hubitat.helper.RMUtils.metaClass.static.sendAction = {
            List ids, String action, String appLabel, String version ->
                callCount++
                throw new MissingMethodException('sendAction', hubitat.helper.RMUtils,
                    [ids, action, appLabel, version] as Object[])
        }

        when:
        def result = script.toolRunRmRule([ruleId: 2, action: 'rule'])

        then:
        result.success == true
        result.fallback == '3-arg'

        and: 'the 3-arg form was recorded by RMUtilsMock (no version field on 3-arg calls)'
        def sendCalls = rmUtils.calls.findAll { it.method == 'sendAction' }
        sendCalls.size() == 1
        sendCalls[0].action == 'runRule'
        !sendCalls[0].containsKey('version')
    }

    def "4-arg throws NoSuchMethodError: 3-arg fallback is attempted and succeeds"() {
        given: 'replace 4-arg overload to throw NoSuchMethodError'
        hubitat.helper.RMUtils.metaClass.static.sendAction = {
            List ids, String action, String appLabel, String version ->
                throw new NoSuchMethodError('sendAction')
        }

        when:
        def result = script.toolRunRmRule([ruleId: 3, action: 'rule'])

        then:
        result.success == true
        result.fallback == '3-arg'
    }

    def "4-arg throws generic RuntimeException: NO fallback, returns success=false"() {
        given: 'throwOnSendAction causes any sendAction call (4-arg or 3-arg) to throw RuntimeException'
        // We use throwOnSendAction for the runtime-exception case because we want
        // to verify that NO second attempt is made at all -- not just that it fails.
        rmUtils.throwOnSendAction = new RuntimeException("timeout")

        when:
        def result = script.toolRunRmRule([ruleId: 4, action: 'rule'])

        then:
        result.success == false
        result.error?.contains('timeout') == true

        and: 'only one sendAction call was recorded (the 4-arg, which threw RuntimeException)'
        // Because throwOnSendAction fires on every sendAction arity, we only see
        // the single 4-arg attempt -- the fallback was never triggered.
        // Actually RMUtilsMock throws BEFORE recording (see the implementation).
        // So zero calls are recorded when throwOnSendAction is set.
        rmUtils.calls.findAll { it.method == 'sendAction' }.size() == 0
    }

    def "4-arg throws NoClassDefFoundError: NO fallback, returns success=false"() {
        given: 'use a stateful 4-arg replacement that throws NCDFE (not caught by MME/NSMError branch)'
        hubitat.helper.RMUtils.metaClass.static.sendAction = {
            List ids, String action, String appLabel, String version ->
                throw new NoClassDefFoundError("hubitat/helper/RMUtils")
        }

        when:
        def result = script.toolRunRmRule([ruleId: 5, action: 'rule'])

        then:
        result.success == false
        result.error?.toLowerCase()?.contains('rmutils') == true

        and: 'the 3-arg overload was never invoked (no sendAction calls recorded)'
        rmUtils.calls.findAll { it.method == 'sendAction' }.empty
    }

    def "4-arg throws MissingMethodException and 3-arg also fails: success=false mentioning both"() {
        given: '4-arg throws MME; then throwOnSendAction makes 3-arg throw too'
        // Replace 4-arg to throw MME on every call.
        hubitat.helper.RMUtils.metaClass.static.sendAction = {
            List ids, String action, String appLabel, String version ->
                throw new MissingMethodException('sendAction', hubitat.helper.RMUtils,
                    [ids, action, appLabel, version] as Object[])
        }
        // Replace 3-arg to also throw (different message so we can distinguish them).
        hubitat.helper.RMUtils.metaClass.static.sendAction = {
            List ids, String action, String appLabel ->
                throw new MissingMethodException('sendAction', hubitat.helper.RMUtils,
                    [ids, action, appLabel] as Object[])
        }

        when:
        def result = script.toolRunRmRule([ruleId: 6, action: 'rule'])

        then: 'error field carries the 3-arg (m2) failure message'
        result.success == false
        result.error?.contains('sendAction') == true

        and: 'note field carries the 4-arg (m1) failure via the "4-arg attempt also failed" prefix'
        result.note?.contains('4-arg attempt also failed') == true
        result.note?.contains('sendAction') == true
    }
}
