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

class WatchdogPackageDeploySpec extends Specification {
    HubitatAppScript script
    Map persisted = [:]
    List scheduled = []
    List writes = []
    Map sources = ['178': 'old parent', '179': 'old child']
    String sha = 'a' * 40
    String parent = 'definition(name: "MCP Rule Server", namespace: "mcp")\n#include mcp.Example\n'
    String child = 'definition(name: "MCP Rule", namespace: "mcp")\n'
    String library = 'library(name: "Example", namespace: "mcp")\ndef example() { true }\n'
    String liveLibrary = library
    long clock = 1000000L
    boolean dropSaveResponse = false
    boolean applySave = true
    boolean bundleSuccess = true
    boolean failCompile = false
    List inventory = [[id: 178, namespace: 'mcp', name: 'MCP Rule Server'],
                      [id: 179, namespace: 'mcp', name: 'MCP Rule'],
                      [id: 254, namespace: 'mcp', name: 'E2E Dead-Man Watchdog v2']]

    void setup() {
        script = new HubitatAppSandbox(new File('e2e-deadman-watchdog-v2.groovy').text).run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false]
                _ * getAtomicState() >> { persisted }
                _ * now() >> { clock }
                _ * runIn(*_) >> { args -> scheduled << args.toList() }
            }, userSettingValues: [hubSecurityEnabled: false],
            validator: new PassThroughAppValidator([Flags.DontValidatePreferences,
                Flags.DontValidateDefinition, Flags.DontRestrictGroovy, Flags.DontRunScript]))
        script.metaClass.fetchExternal = { String url ->
            if (url.endsWith('packageManifest.json')) return JsonOutput.toJson([
                apps: [[name: 'MCP Rule Server', namespace: 'mcp', location: 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-server.groovy'],
                       [name: 'MCP Rule', namespace: 'mcp', location: 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/hubitat-mcp-rule.groovy']],
                bundles: [[location: 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/bundle-artifacts/branches/main/mcp-libraries.zip']]])
            if (url.endsWith('hubitat-mcp-server.groovy')) return parent
            if (url.endsWith('hubitat-mcp-rule.groovy')) return child
            throw new IllegalStateException('unexpected download')
        }
        script.metaClass.hubGet = { String path, Map query ->
            if (path == '/hub2/userAppTypes') return JsonOutput.toJson(inventory)
            if (path == '/hub2/userLibraries') return JsonOutput.toJson([[id: 12, name: 'Example', namespace: 'mcp']])
            if (path == '/app/ajax/code') return JsonOutput.toJson([source: sources[query.id.toString()], version: 7])
            if (path == '/library/ajax/code') return JsonOutput.toJson([source: liveLibrary])
            throw new IllegalStateException('unexpected read ' + path)
        }
        script.metaClass.hubPostForm = { String path, Map body ->
            writes << [path: path, body: body, phase: persisted.packageDeployment?.phase]
            if (applySave && !failCompile) sources[body.id.toString()] = body.source
            if (failCompile) return [status: 200, data: '{"status":"error","errorMessage":"unexpected token"}']
            dropSaveResponse ? [status: null, data: null] : [status: 200, data: '{"status":"success"}']
        }
        script.metaClass.adminInstallBundle = { Map args ->
            writes << [bundle: args.importUrl, phase: persisted.packageDeployment?.phase]
            [success: bundleSuccess]
        }
    }

    Map request() {
        [requestId: 'test-operation', ref: sha, confirm: true,
         libraries: [[name: 'Example', sha256: digest(library)]]]
    }

    static String digest(String source) {
        java.security.MessageDigest.getInstance('SHA-256').digest(source.getBytes('UTF-8')).encodeHex().toString()
    }

    void tick() { script.runWatchdogPackageDeploy([requestId: 'test-operation']) }

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
        persisted.packageDeployment.phase == 'awaiting_verification'
        persisted.packageDeployment.hold == true
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
        persisted.packageDeployment.phase == 'awaiting_verification'
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
        persisted.packageDeployment.phase == 'verifying_app'
        writes.size() == 2
        when:
        tick()
        clock += 1000000L
        tick()
        then:
        writes.size() == 2
        persisted.packageDeployment.phase == 'stopped'
        persisted.packageDeployment.hold == true
    }

    def 'compiler rejection is retained without retry or parent save'() {
        given:
        failCompile = true
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        persisted.packageDeployment.phase == 'stopped'
        persisted.packageDeployment.error.contains('unexpected token')
        writes.size() == 2
    }

    def 'bundle response loss is reconciled against every expected library'() {
        given:
        bundleSuccess = false
        script.adminUpdatePackage(request())
        when:
        tick()
        then:
        persisted.packageDeployment.phase == 'awaiting_verification'
        writes.size() == 3
    }

    def 'stale library stops before any app save even when bundle claims success'() {
        given:
        liveLibrary = 'wrong revision'
        script.adminUpdatePackage(request())
        when:
        tick()
        clock += 1000000L
        tick()
        then:
        persisted.packageDeployment.phase == 'stopped'
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
        persisted.packageDeployment.phase == 'stopped'
    }

    def 'active and stopped deployments block dangerous tools and deadman recovery'() {
        given:
        script.adminUpdatePackage(request())
        script.metaClass.probeLoopbackAlive = { -> throw new AssertionError('deadman must remain parked') }
        expect:
        ['hub_update_app', 'hub_reboot', 'hub_set_app_disabled', 'hub_update_platform', 'hub_install_bundle'].every {
            script.executeAdminTool(it, [confirm: true]).success == false
        }
        when:
        script.checkDeadman()
        then:
        noExceptionThrown()
        writes.empty
    }

    def 'status dispatch returns progress without touching hub HTTP'() {
        given:
        script.adminUpdatePackage(request())
        when:
        def response = script.processJsonRpcMessage([jsonrpc: '2.0', id: 9, method: 'tools/call',
            params: [name: 'hub_get_package_deployment', arguments: [requestId: 'test-operation']]])
        def status = new JsonSlurper().parseText(response.result.content[0].text)
        then:
        status.phase == 'queued'
        status.requestId == 'test-operation'
        status.plan == null
        status.libraries == null
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
        persisted.packageDeployment.hold == false
        writes.size() == 3
    }
}
