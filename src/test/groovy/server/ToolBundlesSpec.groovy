package server

import groovy.json.JsonOutput
import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the bundle-management tools whose impls live in the McpBundlesLib #include
 * library (resolved + inlined by the harness before compile): hub_list_bundles (read),
 * hub_delete_bundle (write/destructive), hub_export_bundle (write, File Manager).
 *
 * Hub API contracts verified here:
 *   GET /hub2/userBundles          -> [{id, name, namespace, private, content}] (list)
 *   GET /bundle/deleteJson/<id>    -> {success, message}; success:true even for a missing id
 *   GET /bundle/deleteJson/<id>?full=true -> also deletes the delivered code the hub does not
 *                                    find in use, and keeps the rest without saying so
 *   GET /bundle/export/<id>        -> application/zip binary (export; saved via uploadHubFile)
 *   GET /hub2/userAppTypes, /hub2/userDeviceTypes, /hub2/userLibraries -> the code lists
 *                                    deleteCode matches the bundle's contents against, before and after
 *
 * Mocking: hubInternalGet routes through the harness hubGet.register; httpGet routes through the appExecutor mock (the
 * script.metaClass route does not intercept httpGet); uploadHubFile/getHubSecurityCookie are
 * metaClass stubs. Each behaviour also fires via mcpDriver.callTool for the envelope path.
 */
class ToolBundlesSpec extends ToolSpecBase {

    // httpGet (used only by hub_export_bundle) dispatches through the appExecutor mock. One
    // spec-scope stub reads these per-test fields; setup() resets them. nextExportData is untyped
    // so tests can hand back a non-binary body (a Map) to exercise the unexpected-type guard.
    @Shared def nextExportData = ([0x50, 0x4B, 0x03, 0x04] as byte[])
    @Shared int nextExportStatus = 200
    @Shared Throwable nextExportThrow = null
    @Shared Map exportCaptured = [:]

    def setupSpec() {
        appExecutor.httpGet(*_) >> { args ->
            if (nextExportThrow) throw nextExportThrow
            Map params = args[0] as Map
            Closure handler = args[1] as Closure
            exportCaptured.path = params?.path
            exportCaptured.headers = params?.headers
            handler.call([status: nextExportStatus, data: nextExportData])
        }
    }

    def setup() {
        nextExportData = ([0x50, 0x4B, 0x03, 0x04] as byte[])
        nextExportStatus = 200
        nextExportThrow = null
        exportCaptured.clear()
        savedFiles.clear()
        script.metaClass.getHubSecurityCookie = { -> null }
        script.metaClass.uploadHubFile = { String name, byte[] content -> savedFiles[name] = content }
    }

    @Shared Map savedFiles = [:]

    private static String bundlesJson(List items) { JsonOutput.toJson(items) }

    private static Map bundle(Map over = [:]) {
        [id: 4, name: "mcp_libraries", namespace: "mcp", "private": false,
         content: "apps [], drivers [], libraries [McpRoomsLib, McpBundlesLib]"] + over
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // "within 24h" of the fixed test now()
    }

    // ==================== hub_list_bundles ====================

    def "hub_list_bundles returns an empty result when the hub has no bundles"() {
        given:
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([]) }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.count == 0
        result.source == "hub_api"
    }

    def "hub_list_bundles parses bundles and the content summary into a contains map"() {
        given:
        hubGet.register('/hub2/userBundles') { params ->
            bundlesJson([
                bundle(id: 4),
                [id: 1, name: "HubitatPackageManager_Bundle", namespace: "dcm.hpm", "private": false,
                 content: "apps [Hubitat Package Manager], drivers [], libraries []"]
            ])
        }

        when:
        def result = script.toolListBundles([:])

        then:
        result.source == "hub_api"
        result.count == 2
        def lib = result.bundles.find { it.id == "4" }
        lib.name == "mcp_libraries"
        lib.namespace == "mcp"
        lib["private"] == false
        lib.contains.libraries == ["McpRoomsLib", "McpBundlesLib"]
        lib.contains.apps == []
        def hpm = result.bundles.find { it.id == "1" }
        hpm.contains.apps == ["Hubitat Package Manager"]
    }

    @Unroll
    def "hub_list_bundles via dispatch returns the bundle list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }

        when:
        def response = mcpDriver.callTool('hub_list_bundles', [:])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.count == 1
        inner.bundles[0].id == "4"

        where:
        useGateways << [true, false]
    }

    def "hub_list_bundles surfaces a non-JSON body as hub_api_raw rather than failing"() {
        given:
        hubGet.register('/hub2/userBundles') { params -> "<html>not json</html>" }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.source == "hub_api_raw"
        result.rawResponse.contains("not json")
    }

    // ==================== hub_delete_bundle ====================

    private static Map fixtureBundle(Map over = [:]) {
        [id: 4, name: "Throwaway", namespace: "mcptest", "private": false,
         content: "apps [Target App], drivers [], libraries [Shared Lib]"] + over
    }

    // Code lists that drop an id once the full delete runs, unless it is listed in `inUse`.
    private void stubCodeLists(List apps, List libs, Set inUse = [] as Set) {
        boolean deleted = false
        hubGet.register('/bundle/deleteJson/4') { params -> deleted = true; '{"success":true,"message":null}' }
        hubGet.register('/hub2/userAppTypes') { params ->
            JsonOutput.toJson(deleted ? apps.findAll { it.id.toString() in inUse } : apps)
        }
        hubGet.register('/hub2/userLibraries') { params ->
            JsonOutput.toJson(deleted ? libs.findAll { it.id.toString() in inUse } : libs)
        }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":3,"source":"definition()"}' }
        hubGet.register('/library/list/single/data/21') { params -> '[{"id":21,"version":2,"source":"library()"}]' }
    }

    private static List targetApps() { [[id: 11, name: "Target App", namespace: "mcptest"], [id: 12, name: "Target App", namespace: "other"]] }
    private static List sharedLibs() { [[id: 21, name: "Shared Lib", namespace: "elsewhere"]] }

    private boolean deleteSent() { hubGet.calls.any { it.path.startsWith('/bundle/deleteJson') } }

    def "hub_delete_bundle throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([bundleId: "4"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "hub_delete_bundle rejects bundleId=#id before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([bundleId: id, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(message)
        hubGet.calls.isEmpty()

        where:
        id      | message
        null    | 'bundleId is required'
        '  '    | 'bundleId is required'
        'abc'   | 'bundleId must be a positive integer'
        '-4'    | 'bundleId must be a positive integer'
        '0'     | 'bundleId must be a positive integer'
        '+4'    | 'bundleId must be a positive integer'
    }

    @Unroll
    def "hub_delete_bundle sends the parsed integer for bundleId '#given'"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        hubGet.register('/bundle/deleteJson/4') { params -> '{"success":true,"message":null}' }

        when:
        def result = script.toolDeleteBundle([bundleId: given, confirm: true])

        then:
        result.success == true
        result.bundleId == "4"
        hubGet.calls*.key == ['/hub2/userBundles', '/bundle/deleteJson/4']

        where:
        given << [' 4', '4 ', '04']
    }

    def "hub_delete_bundle rejects a non-boolean deleteCode before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([bundleId: "4", deleteCode: "true", confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deleteCode must be true or false')
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "hub_delete_bundle via dispatch refuses an id argument in place of bundleId before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [id: "4", confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_delete_bundle argument: id')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_delete_bundle via dispatch is refused when Write tools are disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "a bundle id that is not on the list is refused without a delete request"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }

        when:
        def result = script.toolDeleteBundle([bundleId: "999", confirm: true])

        then:
        result.success == false
        result.error == "No bundle with id 999"
        result.note.contains('Nothing was deleted')
        !deleteSent()
    }

    @Unroll
    def "an unreadable bundle list refuses the delete (#body)"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> body }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains('was not deleted')
        result.note.contains('reports success for any bundle id')
        !deleteSent()

        where:
        body << ['<html>login</html>', '{"not":"a list"}']
    }

    @Unroll
    def "a missing bundle via dispatch is a success=false result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([]) }

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error == "No bundle with id 4"
        !deleteSent()

        where:
        useGateways << [true, false]
    }

    def "without deleteCode one list read and one deleteJson call delete the bundle"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        hubGet.register('/bundle/deleteJson/4') { params -> '{"success":true,"message":null}' }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        hubGet.calls*.key == ['/hub2/userBundles', '/bundle/deleteJson/4']
        result.success == true
        result.bundleId == "4"
        result.name == "Throwaway"
        result.deleteCode == false
        result.message.contains('stay in Code')
        !result.containsKey('codeDeleted')
    }

    def "the hub's success:false is returned with its message"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        hubGet.register('/bundle/deleteJson/4') { params -> '{"success":false,"message":"Bundle is locked"}' }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error == "Bundle is locked"
    }

    @Unroll
    def "an unrecognized delete reply fails closed: '#body'"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        hubGet.register('/bundle/deleteJson/4') { params -> body }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains('Could not confirm')
        result.note.contains('hub_list_bundles')

        where:
        body << ['<html>Bundles</html>', '{"status":true}', '']
    }

    def "a thrown delete request reports the outcome unknown"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        hubGet.register('/bundle/deleteJson/4') { params -> throw new RuntimeException("connection refused") }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains("connection refused")
        result.note.contains('unknown')
    }

    def "deleteCode backs up each delivered item, sends full=true and reports code the hub kept"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        stubCodeLists(targetApps(), sharedLibs(), ['21'] as Set)

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then: 'the namespace match picks app 11 over its namesake 12; the library falls back to its unique name'
        def keys = hubGet.calls*.key
        keys.indexOf('/bundle/deleteJson/4?full=true') > keys.indexOf('/app/ajax/code?id=11')
        keys.indexOf('/bundle/deleteJson/4?full=true') > keys.indexOf('/library/list/single/data/21')
        !keys.contains('/bundle/deleteJson/4')
        savedFiles.keySet().any { it.contains('mcp-backup-app-11') }
        savedFiles.keySet().any { it.contains('mcp-backup-library-21') }

        and:
        result.success == true
        result.deleteCode == true
        result.codeDeleted == [[type: 'app', id: '11', name: 'Target App']]
        result.codeKept == [[type: 'library', id: '21', name: 'Shared Lib']]
        result.backupFiles.size() == 2
        result.note.contains('still uses it')
        result.note.contains('hub_delete_item')
    }

    @Unroll
    def "deleteCode via dispatch deletes the delivered code (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        stubCodeLists(targetApps(), sharedLibs())

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", deleteCode: true, confirm: true])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.codeDeleted*.id == ['11', '21']
        inner.codeKept == []
        inner.note == null

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "deleteCode refuses before any backup or delete when a name is #why"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        stubCodeLists(apps, sharedLibs())

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.unresolved == [[type: 'app', name: 'Target App', reason: reason]]
        result.note.contains('Nothing was deleted')
        !hubGet.calls.any { it.path in ['/app/ajax/code', '/library/list/single/data/21'] }
        savedFiles.isEmpty()
        !deleteSent()

        where:
        why          | apps                                                                                         | reason
        'ambiguous'  | [[id: 11, name: "Target App", namespace: "a"], [id: 12, name: "Target App", namespace: "b"]] | '2 apps have this name'
        'unresolved' | [[id: 11, name: "Other", namespace: "mcptest"]]                                              | 'no app has this name'
    }

    def "deleteCode refuses when a code list cannot be read"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        stubCodeLists(targetApps(), sharedLibs())
        hubGet.register('/hub2/userLibraries') { params -> throw new RuntimeException('HTTP 500') }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.unresolved*.name == ['Shared Lib']
        savedFiles.isEmpty()
        !deleteSent()
    }

    def "deleteCode fails closed when a backup cannot be taken"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([fixtureBundle()]) }
        stubCodeLists(targetApps(), sharedLibs())
        hubGet.register('/library/list/single/data/21') { params -> '[]' }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains("Could not back up library 21")
        result.note == 'Nothing was deleted.'
        !deleteSent()
    }

    // ==================== hub_export_bundle ====================

    def "hub_export_bundle throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_export_bundle', [bundleId: "4"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_export_bundle rejects a non-numeric bundleId"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.toolExportBundle([bundleId: "x"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')
    }

    def "hub_export_bundle throws when bundleId is missing"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.toolExportBundle([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bundleId is required')
    }

    def "hub_export_bundle returns success=false when the bundle id is not present"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }

        when:
        def result = script.toolExportBundle([bundleId: "999"])

        then:
        result.success == false
        result.error.contains("No bundle with id 999")
        savedFiles.isEmpty()
    }

    def "hub_export_bundle fetches the zip and saves it to the File Manager"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ([0x50, 0x4B, 0x03, 0x04, 0x05, 0x06] as byte[])

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        exportCaptured.path == "/bundle/export/4"
        savedFiles["mcp_libraries.zip"]?.length == 6
        result.success == true
        result.fileName == "mcp_libraries.zip"
        result.bytes == 6
        result.directDownload == "/local/mcp_libraries.zip"
    }

    @Unroll
    def "hub_export_bundle sends the parsed integer for bundleId '#given'"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }

        when:
        def result = script.toolExportBundle([bundleId: given])

        then:
        exportCaptured.path == "/bundle/export/4"
        result.success == true
        result.bundleId == "4"

        where:
        given << [' 4', '4 ', '04']
    }

    def "hub_export_bundle reads a stream body via duck-typed .bytes (no InputStream class reference)"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        // httpGet with textParser:false often hands back a stream, not a byte[]. The impl must read
        // it WITHOUT naming java.io.InputStream (sandbox-blocked); this exercises that duck-typed path.
        nextExportData = new ByteArrayInputStream([0x50, 0x4B, 0x03, 0x04, 0x05] as byte[])

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == true
        result.bytes == 5
        savedFiles["mcp_libraries.zip"]?.length == 5
    }

    def "hub_export_bundle honors saveAs and appends .zip"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ([0x50, 0x4B] as byte[])

        when:
        def result = script.toolExportBundle([bundleId: "4", saveAs: "my backup"])

        then: 'saveAs needs no bundle list read'
        savedFiles.containsKey("my_backup.zip")
        result.fileName == "my_backup.zip"
        hubGet.calls.isEmpty()
    }

    def "hub_export_bundle with saveAs relies on the HTTP check for a missing id"() {
        given:
        settingsMap.enableWrite = true
        nextExportStatus = 404
        nextExportData = ("<html>404</html>".getBytes("UTF-8"))

        when:
        def result = script.toolExportBundle([bundleId: "999", saveAs: "x"])

        then:
        result.success == false
        result.error.contains("HTTP 404")
        savedFiles.isEmpty()
        hubGet.calls.isEmpty()
    }

    @Unroll
    def "hub_export_bundle via dispatch refuses an id argument in place of bundleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true

        when:
        def response = mcpDriver.callTool('hub_export_bundle', [id: "4"])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_export_bundle argument: id')
        savedFiles.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "hub_export_bundle returns success=false when the export body is empty"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ([] as byte[])

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("returned no data")
    }

    def "hub_export_bundle fails on a non-zip body and does NOT save it"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ("not a zip".getBytes("UTF-8"))

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("PK signature")
        savedFiles.isEmpty()   // a corrupt/non-zip body is never written to the File Manager
    }

    def "hub_export_bundle fails on a non-2xx response without saving"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportStatus = 500
        nextExportData = ("<html>500 error</html>".getBytes("UTF-8"))

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("HTTP 500")
        result.status == 500
        savedFiles.isEmpty()
    }

    @Unroll
    def "hub_export_bundle via dispatch saves and returns the envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ([0x50, 0x4B, 0x03, 0x04] as byte[])

        when:
        def response = mcpDriver.callTool('hub_export_bundle', [bundleId: "4"])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.fileName == "mcp_libraries.zip"

        where:
        useGateways << [true, false]
    }

    // -------- export: error branches + cookie + unexpected body --------

    def "hub_export_bundle returns a fetch error when httpGet throws"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportThrow = new RuntimeException("connection refused")

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("Failed to fetch")
        savedFiles.isEmpty()
    }

    def "hub_export_bundle returns a save error when uploadHubFile throws"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException("disk full") }

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("saving to File Manager")
    }

    def "hub_export_bundle attaches the Hub Security cookie when present"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        script.metaClass.getHubSecurityCookie = { -> "HUBSESSION=abc" }

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == true
        exportCaptured.headers?.Cookie == "HUBSESSION=abc"
    }

    def "hub_export_bundle fails on an unexpected non-binary body without saving"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = [unexpected: "not bytes"]   // a Map -- not byte[]/InputStream

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("non-binary body")
        savedFiles.isEmpty()
    }

    // -------- list: degraded source, no-id note, raw-content fallback, pagination --------

    def "hub_list_bundles reports source=unavailable when the hub API throws"() {
        given:
        hubGet.register('/hub2/userBundles') { params -> throw new RuntimeException("boom") }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.source == "unavailable"
        result.note.contains("Hub internal API unavailable")
    }

    def "hub_list_bundles notes bundles returned without an id"() {
        given:
        hubGet.register('/hub2/userBundles') { params ->
            bundlesJson([[name: "noid", namespace: "mcp", "private": false, content: "apps [], drivers [], libraries []"]])
        }

        when:
        def result = script.toolListBundles([:])

        then:
        result.source == "hub_api"
        result.note?.contains("cannot be targeted")
    }

    def "hub_list_bundles surfaces raw content when the content shape is unrecognized"() {
        given:
        hubGet.register('/hub2/userBundles') { params ->
            bundlesJson([[id: 9, name: "weird", namespace: "mcp", "private": false, content: "totally different shape"]])
        }

        when:
        def result = script.toolListBundles([:])
        def b = result.bundles[0]

        then:
        b.content == "totally different shape"
        !b.containsKey("contains")
    }

    def "hub_list_bundles paginates when a cursor is supplied"() {
        given:
        def many = (1..60).collect { bundle(id: it, name: "b${it}") }
        hubGet.register('/hub2/userBundles') { params -> bundlesJson(many) }

        when:
        def page1 = script.toolListBundles([cursor: ''])

        then:
        page1.total == 60
        page1.count == 50
        page1.nextCursor != null
    }
}
