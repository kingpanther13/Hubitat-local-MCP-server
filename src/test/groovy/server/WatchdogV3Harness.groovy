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

// Shared fake hub for the v3 package-deployment specs.
abstract class WatchdogV3Harness extends Specification {
    HubitatAppScript script
    Map persisted = new PublicationMap()
    List scheduled = []
    List periodic = []
    boolean failScheduling = false
    List unscheduled = []
    List writes = []
    Map sources = ['178': 'old parent', '179': 'old child']
    Map versions = ['178': 7, '179': 7]
    String sha = 'a' * 40
    String parent = 'definition(name: "MCP Rule Server", namespace: "mcp")\n#include mcp.Example\n'
    String child = 'definition(name: "MCP Rule", namespace: "mcp")\n'
    String library = 'library(name: "Example", namespace: "mcp")\ndef example() { true }\n'
    String liveLibrary = 'old library'
    List installedLibraries = [[id: 12, name: 'Example', namespace: 'mcp']]
    Map extraLibrarySources = [:]
    boolean librariesUnreadable = false
    long clock = 1000000L
    boolean dropSaveResponse = false
    boolean applySave = true
    boolean bundleSuccess = true
    boolean applyBundle = true
    boolean failCompile = false
    List inventory = [[id: 178, namespace: 'mcp', name: 'MCP Rule Server'],
                      [id: 179, namespace: 'mcp', name: 'MCP Rule'],
                      [id: 254, namespace: 'mcp', name: 'E2E Dead-Man Watchdog v2']]

    static class PublicationMap extends HashMap {
        List publications = []
        int deploymentWrites = 0
        // A method, not a property: property access on a Map is itself a get().
        int deploymentWriteCount() { deploymentWrites }
        @Override Object put(Object key, Object value) {
            if (key == 'packageDeployment') deploymentWrites++
            if (key == 'packageDeployment' && value instanceof Map && value.hold == false)
                publications << ([:] + value)
            return super.put(key, value)
        }
    }

    void setup() {
        script = new HubitatAppSandbox(new File('e2e-deadman-watchdog-v3.groovy').text).run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false]
                _ * getAtomicState() >> { persisted }
                _ * getState() >> [accessToken: "test-token"]
                _ * now() >> { clock }
                _ * runIn(*_) >> { args ->
                    if (failScheduling) throw new IllegalStateException('scheduler unavailable')
                    scheduled << args.toList()
                }
                _ * runEvery1Minute(_) >> { args -> periodic << args[0] }
                _ * unschedule(*_) >> { args -> unscheduled << args.toList() }
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
            if (path == '/hub2/userLibraries') return librariesUnreadable ? null : JsonOutput.toJson(installedLibraries)
            if (path == '/app/ajax/code') return JsonOutput.toJson([source: sources[query.id.toString()], version: versions[query.id.toString()]])
            if (path == '/library/list/single/data/12') return JsonOutput.toJson([[source: liveLibrary]])
            if (path.startsWith('/library/list/single/data/'))
                return JsonOutput.toJson([[source: extraLibrarySources[path.tokenize('/').last()]]])
            throw new IllegalStateException('unexpected read ' + path)
        }
        script.metaClass.hubPostForm = { String path, Map body ->
            writes << [path: path, body: body, phase: script.packageJob()?.phase]
            if (applySave && !failCompile) {
                sources[body.id.toString()] = body.source
                versions[body.id.toString()]++
            }
            if (failCompile) return [status: 200, data: '{"status":"error","errorMessage":"unexpected token"}']
            dropSaveResponse ? [status: null, data: null] : [status: 200, data: '{"status":"success"}']
        }
        script.metaClass.adminInstallBundle = { Map args ->
            writes << [bundle: args.importUrl, phase: script.packageJob()?.phase]
            if (applyBundle) liveLibrary = library
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

    /** The deployment as status sees it: the persisted hold overlaid with in-memory progress. */
    Map job() { script.packageJob() }
}
