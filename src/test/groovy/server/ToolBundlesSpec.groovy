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
 *   GET /hub2/userBundles      -> [{id, name, namespace, private, content}] (list)
 *   GET /bundle/delete/<id>    -> 302 redirect (delete; verified by re-list)
 *   GET /bundle/export/<id>    -> application/zip binary (export; saved via uploadHubFile)
 *
 * Mocking: hubInternalGet routes through the harness hubGet.register; hubInternalGetRaw is
 * stubbed per-test on script.metaClass; httpGet routes through the appExecutor mock (the
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

    def "hub_delete_bundle throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_delete_bundle', [bundleId: "4", confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_delete_bundle throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([bundleId: "4"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_delete_bundle rejects a non-numeric bundleId"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([bundleId: "abc", confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')
    }

    def "hub_delete_bundle returns success=false when the bundle id is not present"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }

        when:
        def result = script.toolDeleteBundle([bundleId: "999", confirm: true])

        then:
        result.success == false
        result.error.contains("No bundle with id 999")
    }

    def "hub_delete_bundle deletes the bundle and verifies it is gone via re-list"() {
        given:
        enableWrite()
        def live = [bundle(id: 4)]
        hubGet.register('/hub2/userBundles') { params -> bundlesJson(live) }
        def deletedPath = null
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            deletedPath = path
            live.removeAll { it.id == 4 }   // simulate the hub removing it
            [status: 302, location: "/bundle/list", data: null]
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        deletedPath == "/bundle/delete/4"
        result.success == true
        result.verified == true
        result.bundleId == "4"
        result.bundleName == "mcp_libraries"
    }

    def "hub_delete_bundle returns success=false when the bundle survives the delete request"() {
        given:
        enableWrite()
        def live = [bundle(id: 4)]
        hubGet.register('/hub2/userBundles') { params -> bundlesJson(live) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 200, location: null, data: null]   // hub refused; nothing removed
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains("still present")
        result.status == 200
    }

    def "hub_delete_bundle returns success=false when the delete request throws"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            throw new RuntimeException("connection refused")
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains("Bundle delete request failed")
    }

    def "hub_delete_bundle throws when bundleId is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteBundle([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("bundleId is required")
    }

    @Unroll
    def "hub_delete_bundle via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4"])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
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

        then:
        savedFiles.containsKey("my_backup.zip")
        result.fileName == "my_backup.zip"
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

    // -------- delete: degraded re-list cannot confirm removal --------

    def "hub_delete_bundle returns unverified (success=false) when the post-delete list is degraded"() {
        given:
        enableWrite()
        hubGet.register('/hub2/userBundles') { params -> "<html>not json</html>" }  // degraded list
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 302, location: "/bundle/list", data: null]
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.verified == false
        result.error.contains("could not be verified")
    }

    def "hub_delete_bundle reports verified=false when the PRE-delete list is degraded (existence unconfirmed)"() {
        given:
        enableWrite()
        // First list (pre-delete) is degraded -> existence can't be confirmed; second list
        // (post-delete) is a clean empty list -> the id is absent. Deleting a nonexistent id is a
        // harmless hub no-op (302), so "absent now" must NOT be reported as a verified deletion.
        def calls = 0
        hubGet.register('/hub2/userBundles') { params ->
            calls++
            calls == 1 ? "<html>not json</html>" : bundlesJson([])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 302, location: "/bundle/list", data: null]
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "7", confirm: true])

        then:
        result.success == true
        result.verified == false
        result.message.contains("could not confirm the bundle existed")
        result.bundleId == "7"
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
