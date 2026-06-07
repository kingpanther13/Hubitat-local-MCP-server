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
    // spec-scope stub reads these per-test fields; setup() resets them.
    @Shared byte[] nextExportData = ([0x50, 0x4B, 0x03, 0x04] as byte[])
    @Shared Throwable nextExportThrow = null
    @Shared Map exportCaptured = [:]

    def setupSpec() {
        appExecutor.httpGet(*_) >> { args ->
            if (nextExportThrow) throw nextExportThrow
            Map params = args[0] as Map
            Closure handler = args[1] as Closure
            exportCaptured.path = params?.path
            handler.call([data: nextExportData])
        }
    }

    def setup() {
        nextExportData = ([0x50, 0x4B, 0x03, 0x04] as byte[])
        nextExportThrow = null
        exportCaptured.clear()
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
        result.note == null   // valid PK signature -> no warning
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

    def "hub_export_bundle flags a non-zip body but still saves it"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/userBundles') { params -> bundlesJson([bundle(id: 4)]) }
        nextExportData = ("not a zip".getBytes("UTF-8"))

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == true
        result.note.contains("ZIP signature")
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
}
