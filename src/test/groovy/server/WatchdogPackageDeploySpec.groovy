package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import support.PassThroughAppValidator
import support.PermissiveLog

class WatchdogPackageDeploySpec extends WatchdogV3Harness {
    /** An atomicState whose writes do not land. */
    static class DroppingMap extends HashMap {
        DroppingMap(Map source) { super(source) }
        @Override Object put(Object key, Object value) { null }
    }

    def 'watchdog advertises start status and release tools through MCP'() {
        when:
        def response = script.processJsonRpcMessage([jsonrpc: '2.0', id: 1, method: 'tools/list'])
        then:
        response.result.tools*.name.containsAll(['hub_update_package', 'hub_get_package_deployment', 'hub_set_package_deployment'])
    }

    def 'acceptance is immediate duplicate delivery schedules once and status is operation bound'() {
        when:
        def accepted = script.adminUpdatePackage(request())
        def duplicate = script.adminUpdatePackage(request())
        def other = script.adminUpdatePackage(request() + [requestId: 'other'])
        then:
        accepted.status == 'queued'
        duplicate.requestId == accepted.requestId
        other.success == false
        writes.empty
        scheduled.size() == 1
        script.adminGetPackageDeployment([requestId: 'other']).success == false
    }

    def 'repair saves existing child then parent once and retains hold pending endpoint verification'() {
        given:
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'awaiting_verification'
        job().hold == true
        writes*.phase == ['installing_bundle', 'updating_app', 'updating_app']
        writes.findAll { it.path }*.body*.id == ['179', '178']
        writes.findAll { it.path }.every { it.path == '/app/ajax/update' && it.body.keySet() == ['id', 'version', 'source'] as Set }
        sources['178'] == parent
        sources['179'] == child
        when: 'a stale scheduler callback is delivered again'
        tick()
        then:
        writes.size() == 3
    }

    def 'lost save response is verified by full source without another save'() {
        given:
        dropSaveResponse = true
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'awaiting_verification'
        writes.size() == 3
    }

    def 'unsettled save only schedules reads and eventually stops with safety hold'() {
        given:
        dropSaveResponse = true
        applySave = false
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'verifying_app'
        job().detail.contains('still at code version 7')
        writes.size() == 2
        when:
        tick()
        clock += 1000000L
        tick()
        then:
        writes.size() == 2
        job().phase == 'stopped'
        job().error.contains('still at code version 7')
        job().hold == true
    }

    def 'compiler rejection is retained without retry or parent save'() {
        given:
        failCompile = true
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'stopped'
        job().error.contains('unexpected token')
        writes.size() == 2
    }

    def 'bundle response loss is reconciled against every expected library'() {
        given:
        bundleSuccess = false
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'awaiting_verification'
        writes.size() == 3
    }

    def 'stale library stops before any app save even when bundle claims success'() {
        given:
        liveLibrary = 'wrong revision'
        applyBundle = false
        script.adminUpdatePackage(request())
        when:
        tick()
        clock += 1000000L
        tick()
        then:
        job().phase == 'stopped'
        writes.size() == 1
    }

    def 'duplicate or missing app identity aborts before any code writes'() {
        given:
        inventory << [id: 555, namespace: 'mcp', name: 'MCP Rule Server']
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        writes.empty
        job().phase == 'stopped'
    }

    def 'status dispatch returns progress without touching hub HTTP'() {
        given:
        script.adminUpdatePackage(request())
        int reads = 0
        script.metaClass.hubGet = { String path, Map query -> reads++; null }
        when:
        def response = script.processJsonRpcMessage([jsonrpc: '2.0', id: 9, method: 'tools/call',
            params: [name: 'hub_get_package_deployment', arguments: [requestId: 'test-operation']]])
        def status = new JsonSlurper().parseText(response.result.content[0].text)
        then:
        status.phase == 'queued'
        status.requestId == 'test-operation'
        status.plan == null
        status.libraries == null
        reads == 0
    }

    def 'a scheduling failure stops the operation with its hold retained'() {
        given:
        failScheduling = true
        when:
        def result = script.adminUpdatePackage(request())
        then:
        result.phase == 'stopped'
        result.success == false
        result.hold == true
        result.error.contains('scheduler unavailable')
        writes.empty
    }

    @spock.lang.Unroll
    def 'preflight refuses a manifest with #problem before any write'() {
        given:
        Map manifest = [
            apps: [[name: 'MCP Rule Server', namespace: 'mcp', location: 'https://example.test/hubitat-mcp-server.groovy'],
                   [name: 'MCP Rule', namespace: 'mcp', location: 'https://example.test/hubitat-mcp-rule.groovy']],
            bundles: [[location: 'https://example.test/mcp-libraries.zip']]]
        mutate(manifest)
        script.metaClass.fetchExternal = { String url ->
            if (url.endsWith('packageManifest.json')) return JsonOutput.toJson(manifest)
            url.endsWith('hubitat-mcp-server.groovy') ? parent : child
        }
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'stopped'
        job().hold == true
        writes.empty
        where:
        problem                      | mutate
        'an extra app'               | { it.apps << [name: 'Other', namespace: 'mcp', location: 'https://example.test/other.groovy'] }
        'a driver'                   | { it.drivers = [[name: 'driver']] }
        'a file'                     | { it.files = [[name: 'file']] }
        'a foreign namespace'        | { it.apps[0].namespace = 'other' }
        'a differently named bundle' | { it.bundles[0].location = 'https://example.test/other.zip' }
        'a mismatched app location'  | { it.apps[1].location = 'https://example.test/elsewhere.groovy' }
    }

    def 'release requires an explicit matching request and verified endpoint after all saves'() {
        given:
        script.adminUpdatePackage(request())
        when:
        def early = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        tick()
        def refused = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true])
        def released = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        then:
        early.success == false
        refused.success == false
        released.phase == 'complete'
        job().hold == false
        ((PublicationMap) persisted).getPublications()*.phase == ['complete']
        writes.size() == 3
    }

    def 'same operation id cannot be rebound to different source or library expectations'() {
        given:
        script.adminUpdatePackage(request())
        when:
        def result = script.adminUpdatePackage(request() + [ref: 'b' * 40])
        then:
        result.success == false
        scheduled.size() == 1
        writes.empty
    }

    def 'another callback cannot enter the worker while an update is still executing'() {
        given:
        script.adminUpdatePackage(request())
        script.metaClass.hubPostForm = { String path, Map body ->
            writes << [path: path, body: body]
            tick()
            sources[body.id.toString()] = body.source
            versions[body.id.toString()]++
            [status: 200, data: '{"status":"success"}']
        }
        when:
        tick()
        then:
        writes.size() == 3
        job().phase == 'awaiting_verification'
    }

    def 'readiness release cannot conceal source drift after the save'() {
        given:
        script.adminUpdatePackage(request())
        tick()
        sources['178'] = 'changed by another actor'
        when:
        def result = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        then:
        result.success == false
        result.phase == 'awaiting_verification'
        result.error.contains('MCP Rule Server does not match the expected source')
        job().hold == true
        !job().workerActive
    }

    def 'a transient read failure at completion keeps the hold and completion can be retried'() {
        given:
        script.adminUpdatePackage(request())
        tick()
        Map release = [requestId: 'test-operation', confirm: true, endpointVerified: true]
        when:
        librariesUnreadable = true
        def failed = script.adminSetPackageDeployment(release)
        then:
        failed.success == false
        failed.phase == 'awaiting_verification'
        failed.error.contains('Could not read the installed library list')
        job().hold == true
        !job().workerActive
        when:
        librariesUnreadable = false
        def released = script.adminSetPackageDeployment(release)
        then:
        released.phase == 'complete'
        released.error == null
        job().hold == false
    }

    def 'stopped job needs explicit abandonment after outstanding writes finish'() {
        given:
        failCompile = true
        script.adminUpdatePackage(request())
        tick()
        when:
        def ordinary = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        def abandoned = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true, abandon: true, writesSettled: true])
        then:
        ordinary.success == false
        abandoned.phase == 'abandoned'
        abandoned.hold == false
        abandoned.success == false
    }

    def 'same-version response loss cannot advance before the code version changes'() {
        given:
        sources['179'] = child
        sources['178'] = parent
        applySave = false
        dropSaveResponse = true
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'verifying_app'
        writes.size() == 2
        when:
        tick()
        then:
        writes.size() == 2
        job().hold == true
    }

    def 'already matching libraries are not submitted as an ambiguous redundant bundle import'() {
        given:
        liveLibrary = library
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        writes.size() == 2
        writes.every { it.path == '/app/ajax/update' }
        job().phase == 'awaiting_verification'
    }

    def 'unreadable library baseline stops before any package write'() {
        given:
        liveLibrary = null
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'stopped'
        job().error.contains('library baseline')
        writes.empty
    }

    def 'restarting v3 schedules the health tick but no restore or package resume'() {
        given:
        script.metaClass.createAccessToken = { -> }
        script.metaClass.getState = { -> [accessToken: 'test-token'] }
        when:
        script.initialize()
        then:
        periodic == ['checkHubHealth']
        scheduled.empty
        writes.empty
    }

    def 'the first request after a code load arms the health tick, once'() {
        given: 'a code update runs no initialize(); the next POST to /mcp is the first thing to execute'
        def injected = me.biocomp.hubitat_ci.app.HubitatAppScript.getDeclaredField('injectedMappingHandlerData')
        injected.accessible = true
        injected.set(script, [request: [JSON: [jsonrpc: '2.0', id: 1, method: 'tools/list']]])
        when:
        script.handleMcpRequest()
        script.handleMcpRequest()
        script.initialize()
        then:
        periodic == ['checkHubHealth']
    }

    def 'a restart after the install came to rest does not interrupt it, and completion still releases'() {
        given:
        script.adminUpdatePackage(request())
        tick()
        when: 'the watchdog restarts: the in-memory progress is gone, the persisted record remains'
        script.PACKAGE_PROGRESS.clear()
        def status = script.adminGetPackageDeployment([requestId: 'test-operation'])
        def released = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        then:
        status.phase == 'awaiting_verification'
        status.hold == true
        released.phase == 'complete'
        job().hold == false
    }

    def 'a release whose record cannot be persisted keeps the hold and leaves no claim behind'() {
        given:
        script.adminUpdatePackage(request())
        tick()
        Map release = [requestId: 'test-operation', confirm: true, endpointVerified: true]
        Map healthy = persisted
        when: 'the record write does not land'
        persisted = new DroppingMap(healthy)
        script.adminSetPackageDeployment(release)
        then:
        thrown(IllegalStateException)
        job().hold == true
        job().phase == 'awaiting_verification'
        !job().workerActive
        when:
        persisted = healthy
        def released = script.adminSetPackageDeployment(release)
        then:
        released.phase == 'complete'
        job().hold == false
    }

    def 'a worker that outlived a code load stops once the persisted hold is no longer its own'() {
        given: 'a newer class admits another operation mid-save; this worker sees only its own memory'
        script.adminUpdatePackage(request())
        script.metaClass.hubPostForm = { String path, Map body ->
            writes << [path: path, body: body]
            persisted.packageDeployment = [requestId: 'newer', hold: true, phase: 'queued', startedAt: clock]
            sources[body.id.toString()] = body.source
            versions[body.id.toString()]++
            [status: 200, data: '{"status":"success"}']
        }
        when:
        tick()
        then: 'the bundle and the child were written; the parent never is, and the newer hold is untouched'
        writes.size() == 2
        persisted.packageDeployment.requestId == 'newer'
        persisted.packageDeployment.hold == true
    }

    def 'saving the settings page does not cancel a pending verification poll'() {
        given:
        dropSaveResponse = true
        applySave = false
        script.adminUpdatePackage(request())
        tick()
        when:
        script.updated()
        then:
        unscheduled.empty
        job().phase == 'verifying_app'
    }

    def 'abandonment releases an awaiting deployment for repair without marking it complete'() {
        given: 'everything installed, but the new MCP code does not answer'
        script.adminUpdatePackage(request())
        tick()
        when:
        def unsettled = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, abandon: true])
        then:
        unsettled.success == false
        job().hold == true
        when:
        def result = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true,
            abandon: true, writesSettled: true])
        then:
        result.phase == 'abandoned'
        result.success == false
        job().hold == false
        when: 'a known-good ref can now be deployed over it'
        def restore = script.adminUpdatePackage(request() + [requestId: 'restore'])
        then:
        restore.phase == 'queued'
    }

    def 'a worker that hangs without a restart can be abandoned only once it has been silent past the stale window'() {
        given: 'a worker still holding its in-memory claim during a save'
        persisted.packageDeployment = [requestId: 'dead', phase: 'queued', hold: true, startedAt: clock]
        script.PACKAGE_PROGRESS.job = [requestId: 'dead', phase: 'updating_app', hold: true, workerActive: true,
            startedAt: clock, stageStartedAt: clock, updatedAt: clock]
        Map abandon = [requestId: 'dead', confirm: true, abandon: true, writesSettled: true]
        when:
        def early = script.adminSetPackageDeployment(abandon)
        then:
        early.success == false
        !script.adminGetPackageDeployment([requestId: 'dead']).workerStale
        job().hold == true
        when:
        clock += 900001L
        def status = script.adminGetPackageDeployment([requestId: 'dead'])
        def unsettled = script.adminSetPackageDeployment([requestId: 'dead', confirm: true, abandon: true])
        def completion = script.adminSetPackageDeployment([requestId: 'dead', confirm: true, endpointVerified: true])
        def released = script.adminSetPackageDeployment(abandon)
        then:
        status.workerStale
        unsettled.success == false
        completion.success == false
        released.phase == 'abandoned'
        job().hold == false
        !job().workerActive
    }

    def 'a watchdog restart mid-deployment reports interrupted at once, resumes nothing, and can be abandoned'() {
        given: 'the persisted hold survived a restart; the in-memory progress did not'
        persisted.packageDeployment = [requestId: 'test-operation', ref: sha, hold: true, phase: 'queued', startedAt: clock]
        when:
        def status = script.adminGetPackageDeployment([requestId: 'test-operation'])
        tick()
        def other = script.adminUpdatePackage(request() + [requestId: 'other'])
        def completion = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        def unsettled = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, abandon: true])
        then:
        status.phase == 'interrupted'
        status.success == false
        status.hold == true
        !status.workerActive
        writes.empty
        scheduled.empty
        other.success == false
        other.heldRequestId == 'test-operation'
        completion.success == false
        unsettled.success == false
        when:
        def released = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true,
            abandon: true, writesSettled: true])
        then:
        released.phase == 'abandoned'
        persisted.packageDeployment.hold == false
    }

    def 'a clean deployment persists only at admission, awaiting verification, and release'() {
        when:
        script.adminUpdatePackage(request())
        tick()
        then:
        ((PublicationMap) persisted).deploymentWriteCount() == 2
        persisted.packageDeployment.phase == 'awaiting_verification'
        when:
        script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, endpointVerified: true])
        then:
        ((PublicationMap) persisted).deploymentWriteCount() == 3
        persisted.packageDeployment.phase == 'complete'
        persisted.packageDeployment.workerActive == null
    }

    def 'a verification poll that lost its schedule can be abandoned and a late callback writes nothing'() {
        given:
        dropSaveResponse = true
        applySave = false
        script.adminUpdatePackage(request())
        tick()
        when:
        def released = script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true,
            abandon: true, writesSettled: true])
        tick()
        then:
        released.phase == 'abandoned'
        job().phase == 'abandoned'
        job().hold == false
        writes.size() == 2
    }

    def 'a worker presumed dead cannot write again or revive the hold after abandonment'() {
        given: 'the operator abandons while the child save is still blocked'
        script.adminUpdatePackage(request())
        script.metaClass.hubPostForm = { String path, Map body ->
            writes << [path: path, body: body]
            clock += 900001L
            script.adminSetPackageDeployment([requestId: 'test-operation', confirm: true, abandon: true, writesSettled: true])
            sources[body.id.toString()] = body.source
            versions[body.id.toString()]++
            [status: 200, data: '{"status":"success"}']
        }
        when:
        tick()
        then: 'the bundle and the child were written; the parent never is'
        writes.size() == 2
        job().phase == 'abandoned'
        job().hold == false
    }

    def 'a commit that adds a library is deployed by letting the bundle create it'() {
        given:
        String added = 'library(name: "Added", namespace: "mcp")\n'
        parent = parent + '#include mcp.Added\n'
        script.metaClass.adminInstallBundle = { Map args ->
            writes << [bundle: args.importUrl]
            liveLibrary = library
            installedLibraries << [id: 13, name: 'Added', namespace: 'mcp']
            extraLibrarySources['13'] = added
            [success: true]
        }
        when:
        script.adminUpdatePackage(request() + [libraries: [[name: 'Example', sha256: digest(library)],
                                                           [name: 'Added', sha256: digest(added)]]])
        tick()
        then:
        job().phase == 'awaiting_verification'
        job().libraries.find { it.name == 'Added' }.id == null
        job().libraries.find { it.name == 'Example' }.id == '12'
        writes.size() == 3
    }

    def 'a library installed twice stops the deployment before any write and names it'() {
        given:
        installedLibraries << [id: 99, name: 'Example', namespace: 'mcp']
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        job().phase == 'stopped'
        job().error.contains('Library Example is installed 2 times')
        writes.empty
    }

    @spock.lang.Unroll
    def 'package admission rejects #reason before persisting anything'() {
        when:
        script.adminUpdatePackage(request() + change)
        then:
        thrown(IllegalArgumentException)
        persisted.packageDeployment == null
        scheduled.empty
        where:
        reason                        | change
        'a branch name ref'           | [ref: 'main']
        'a short SHA'                 | [ref: 'a' * 39]
        'an uppercase SHA'            | [ref: 'A' * 40]
        'a requestId with a space'    | [requestId: 'bad id']
        'no libraries'                | [libraries: []]
        'a malformed library hash'    | [libraries: [[name: 'Example', sha256: 'xyz']]]
        'duplicate library names'     | [libraries: [[name: 'Example', sha256: 'a' * 64], [name: 'Example', sha256: 'b' * 64]]]
        'a missing confirm'           | [confirm: false]
        'a non-repository bundle URL' | [bundleBaseUrl: 'https://example.com/owner/repo']
    }

    def 'explicit bundle rejection stops before app saves'() {
        given:
        script.adminUpdatePackage(request())
        script.metaClass.adminInstallBundle = { Map args ->
            writes << [bundle: args.importUrl]
            [success: false, rawResponse: rejection]
        }
        when:
        tick()
        then:
        job().phase == 'stopped'
        job().error.contains('Bundle install rejected')
        job().hold == true
        writes.size() == 1
        where:
        rejection << ['{"success":false,"message":"invalid bundle"}', 'false']
    }

    def 'an unrecognized bundle response still reconciles installed source'() {
        given:
        script.adminUpdatePackage(request())
        script.metaClass.adminInstallBundle = { Map args ->
            writes << [bundle: args.importUrl]
            liveLibrary = library
            [success: false, rawResponse: '<html>response unavailable</html>']
        }
        when:
        tick()
        then:
        job().phase == 'awaiting_verification'
        writes.size() == 3
    }

}
