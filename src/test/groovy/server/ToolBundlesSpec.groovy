package server

import spock.lang.Shared
import spock.lang.Unroll
import support.ToolSpecBase

import static support.BundleRecords.SECRET
import static support.BundleRecords.listJson
import static support.BundleRecords.record
import static support.BundleRecords.recordJson
import static support.BundleRecords.unknownJson

/**
 * Spec for the bundle-management tools whose impls live in the McpBundlesLib #include
 * library (resolved + inlined by the harness before compile): hub_list_bundles (read),
 * hub_delete_bundle (write/destructive), hub_export_bundle (write, File Manager).
 *
 * Hub API contracts verified here (record shapes in support.BundleRecords):
 *   GET /bundle/list/json          -> every bundle record (list)
 *   GET /bundle/json/<id>          -> {bundle: record}; {"success":false} for an unknown id
 *   GET /bundle/deleteJson/<id>    -> {success, message}; success:true even for a missing id
 *   GET /bundle/deleteJson/<id>?full=true -> also deletes the code ids stored on the record,
 *                                    keeping code in use without saying so
 *   GET /bundle/export/<id>        -> application/zip binary (export; saved via uploadHubFile)
 *   GET /hub2/userAppTypes, /hub2/userDeviceTypes, /hub2/userLibraries -> the code lists
 *                                    deleteCode checks the stored ids against, before and after
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
        script.metaClass.deleteHubFile = { String name -> savedFiles.remove(name) }
    }

    @Shared Map savedFiles = [:]

    // Runs inside stubHub's delete request, before the hub deletes anything.
    Closure onDelete = null

    private static Map libsBundle(Map over = [:]) {
        record([id: 4, name: "mcp_libraries", namespace: "mcp", "private": false, libraryIds: [31, 32],
                contentReadable: "apps [], drivers [], libraries [McpRoomsLib, McpBundlesLib]"] + over)
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // "within 24h" of the fixed test now()
    }

    private static String guide() { "hub_get_tool_guide(section='bundles')" }

    // ==================== hub_list_bundles ====================

    def "hub_list_bundles returns an empty result when the hub has no bundles"() {
        given:
        hubGet.register('/bundle/list/json') { params -> listJson([]) }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.count == 0
        result.source == "hub_api"
        hubGet.calls*.key == ['/bundle/list/json']
    }

    def "hub_list_bundles returns the stored code ids and the names, and never the zip passwords"() {
        given:
        hubGet.register('/bundle/list/json') { params ->
            listJson([
                libsBundle(),
                record(id: 1, name: "HubitatPackageManager_Bundle", namespace: "dcm.hpm", appTypeIds: [7],
                       sourceUrl: "https://example.com/hpm.zip",
                       contentReadable: "apps [Hubitat Package Manager], drivers [], libraries []")
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
        lib.codeIds == [apps: [], drivers: [], libraries: ['31', '32']]
        lib.contains.libraries == ["McpRoomsLib", "McpBundlesLib"]
        lib.contains.apps == []
        def hpm = result.bundles.find { it.id == "1" }
        hpm.contains.apps == ["Hubitat Package Manager"]
        hpm.codeIds.apps == ['7']
        hpm.sourceUrl == "https://example.com/hpm.zip"
        hpm["private"] == false

        and: 'the password fields are not copied'
        !groovy.json.JsonOutput.toJson(result).contains(SECRET)
        result.bundles.every { !it.containsKey('sourcePassword') && !it.containsKey('encryptedPassword') }
    }

    def "hub_list_bundles reads a list wrapped under bundles"() {
        given:
        hubGet.register('/bundle/list/json') { params -> groovy.json.JsonOutput.toJson([bundles: [libsBundle()]]) }

        expect:
        script.toolListBundles([:]).bundles*.id == ['4']
    }

    @Unroll
    def "hub_list_bundles gives the summary text as content when it does not match the ids (#why)"() {
        given:
        hubGet.register('/bundle/list/json') { params -> listJson([record(id: 9, appTypeIds: ids, contentReadable: text)]) }

        when:
        def b = script.toolListBundles([:]).bundles[0]

        then:
        b.content == text
        !b.containsKey("contains")
        b.codeIds.apps == ids*.toString()

        where:
        why                         | ids   | text
        'a name with a bracket'     | [845] | "apps []PR580 Review App], drivers [], libraries []"
        'code deleted since'        | []    | "apps [Gone], drivers [], libraries []"
        'an unrecognized shape'     | [1]   | "totally different shape"
    }

    @Unroll
    def "hub_list_bundles via dispatch returns the bundle list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/bundle/list/json') { params -> listJson([libsBundle()]) }

        when:
        def response = mcpDriver.callTool('hub_list_bundles', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 1
        inner.bundles[0].id == "4"
        !groovy.json.JsonOutput.toJson(response).contains(SECRET)

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_list_bundles reports an unrecognized reply without returning it (#body)"() {
        given:
        hubGet.register('/bundle/list/json') { params -> body }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.source == "hub_api_raw"
        !result.containsKey("rawResponse")
        result.note.contains(guide())

        where:
        body << ["<html>not json</html>", '{"oops":"' + SECRET + '"}']
    }

    def "hub_list_bundles reports source=unavailable when the hub API throws"() {
        given:
        hubGet.register('/bundle/list/json') { params -> throw new RuntimeException("boom") }

        when:
        def result = script.toolListBundles([:])

        then:
        result.bundles == []
        result.source == "unavailable"
        result.note.contains("Hub internal API unavailable")
    }

    def "hub_list_bundles notes bundles returned without an id"() {
        given:
        hubGet.register('/bundle/list/json') { params -> listJson([record(id: null, name: "noid")]) }

        when:
        def result = script.toolListBundles([:])

        then:
        result.source == "hub_api"
        result.note?.contains("cannot be targeted")
    }

    def "hub_list_bundles paginates when a cursor is supplied"() {
        given:
        def many = (1..60).collect { record(id: it, name: "b${it}") }
        hubGet.register('/bundle/list/json') { params -> listJson(many) }

        when:
        def page1 = script.toolListBundles([cursor: ''])

        then:
        page1.total == 60
        page1.count == 50
        page1.nextCursor != null
    }

    // ==================== hub_delete_bundle ====================

    private static Map fixtureBundle(Map over = [:]) {
        record([id: 4, name: "Throwaway", namespace: "mcptest", appTypeIds: [11], libraryIds: [21],
                contentReadable: "apps [Target App], drivers [], libraries [Shared Lib]"] + over)
    }

    private static List targetApps() { [[id: 11, name: "Target App", namespace: "mcptest"], [id: 12, name: "Target App", namespace: "other"]] }
    private static List sharedLibs() { [[id: 21, name: "Shared Lib", namespace: "elsewhere"]] }

    // The bundle record, a delete that removes the bundle and (with full=true) every stored code id
    // not in `inUse`, and code lists and sources that follow it.
    private void stubHub(Map bundle, List apps, List libs, Set inUse = [] as Set, String deleteReply = '{"success":true,"message":null}') {
        boolean deleted = false
        boolean full = false
        hubGet.register('/bundle/json/4') { params -> deleted ? unknownJson() : recordJson(bundle) }
        hubGet.register('/bundle/deleteJson/4') { params -> onDelete?.call(); deleted = true; full = params?.full == 'true'; deleteReply }
        Closure gone = { row -> deleted && full && !(row.id.toString() in inUse) &&
            ((bundle.appTypeIds + bundle.libraryIds + bundle.deviceTypeIds)*.toString()).contains(row.id.toString()) }
        hubGet.register('/hub2/userAppTypes') { params -> groovy.json.JsonOutput.toJson(apps.findAll { !gone(it) }) }
        hubGet.register('/hub2/userDeviceTypes') { params -> '[]' }
        hubGet.register('/hub2/userLibraries') { params -> groovy.json.JsonOutput.toJson(libs.findAll { !gone(it) }) }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":3,"source":"definition()"}' }
        hubGet.register('/library/list/single/data/21') { params -> '[{"id":21,"version":2,"source":"library()"}]' }
    }

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
        stubHub(fixtureBundle(), targetApps(), sharedLibs())

        when:
        def result = script.toolDeleteBundle([bundleId: given, confirm: true])

        then:
        result.success == true
        result.bundleId == "4"
        hubGet.calls*.key == ['/bundle/json/4', '/bundle/deleteJson/4']

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

    def "an id the hub does not have is refused from the one record read, without a delete request"() {
        given:
        enableWrite()
        hubGet.register('/bundle/json/999') { params -> unknownJson() }

        when:
        def result = script.toolDeleteBundle([bundleId: "999", confirm: true])

        then:
        result.success == false
        result.error == "No bundle with id 999"
        result.note.contains('Nothing was deleted')
        result.note.contains(guide())
        hubGet.calls*.key == ['/bundle/json/999']
    }

    @Unroll
    def "an unreadable bundle record refuses the delete (#why)"() {
        given:
        enableWrite()
        hubGet.register('/bundle/json/4') { params -> reply() }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains('was not deleted')
        result.note.contains('reports success for any bundle id')
        result.note.contains(guide())
        !deleteSent()

        where:
        why            | reply
        'html'         | { -> '<html>login</html>' }
        'other json'   | { -> '{"not":"a record"}' }
        'a thrown read' | { -> throw new RuntimeException('HTTP 500') }
    }

    @Unroll
    def "a missing bundle via dispatch is an isError result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/bundle/json/4') { params -> unknownJson() }

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error == "No bundle with id 4"
        !deleteSent()

        where:
        useGateways << [true, false]
    }

    def "without deleteCode one record read and one deleteJson call delete the bundle"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        hubGet.calls*.key == ['/bundle/json/4', '/bundle/deleteJson/4']
        result.success == true
        result.isError == null
        result.bundleId == "4"
        result.name == "Throwaway"
        result.deleteCode == false
        result.message.contains('stay in Code')
        !result.containsKey('codeDeleted')
        !groovy.json.JsonOutput.toJson(result).contains(SECRET)
    }

    def "the hub's success:false is returned with its message and whether the bundle is still there"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        hubGet.register('/bundle/deleteJson/4') { params -> '{"success":false,"message":"Bundle is locked"}' }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error == "Bundle is locked"
        result.bundleExists == true
        result.note.contains('still on the hub')
        result.note.contains(guide())
        !result.containsKey('codeDeleted')
    }

    @Unroll
    def "an unrecognized delete reply fails closed: '#body'"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        hubGet.register('/bundle/deleteJson/4') { params -> body }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains('Could not confirm')
        result.note.contains('hub_list_bundles')
        result.bundleExists == true

        where:
        body << ['<html>Bundles</html>', '{"status":true}', '']
    }

    def "a thrown delete request reports where the bundle stands"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        hubGet.register('/bundle/deleteJson/4') { params -> throw new RuntimeException("connection refused") }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", confirm: true])

        then:
        result.success == false
        result.error.contains("connection refused")
        result.bundleExists == true
        result.note.contains(guide())
    }

    def "deleteCode backs up exactly the stored ids, sends full=true and reports code the hub kept"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs(), ['21'] as Set)
        onDelete = {
            assert savedFiles.keySet().any { it.contains('mcp-backup-app-11') }
            assert savedFiles.keySet().any { it.contains('mcp-backup-library-21') }
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then: 'app 12, a namesake the bundle does not store, is never read or backed up'
        def keys = hubGet.calls*.key
        keys.indexOf('/bundle/deleteJson/4?full=true') > keys.indexOf('/app/ajax/code?id=11')
        keys.indexOf('/bundle/deleteJson/4?full=true') > keys.indexOf('/library/list/single/data/21')
        !keys.contains('/app/ajax/code?id=12')
        !keys.contains('/bundle/deleteJson/4')
        savedFiles.keySet().any { it.contains('mcp-backup-app-11') }
        savedFiles.keySet().any { it.contains('mcp-backup-library-21') }
        !savedFiles.keySet().any { it.contains('mcp-backup-app-12') }

        and: 'each source is read once: the backup reuses the read the budget check made'
        keys.count { it == '/app/ajax/code?id=11' } == 1
        keys.count { it == '/library/list/single/data/21' } == 1

        and:
        result.success == true
        result.deleteCode == true
        result.codeDeleted == [[type: 'app', id: '11', name: 'Target App']]
        result.codeKept == [[type: 'library', id: '21', name: 'Shared Lib']]
        result.backupFiles.size() == 2
        result.note.contains('still uses it')
        result.note.contains('hub_delete_item')
        !groovy.json.JsonOutput.toJson(result).contains(SECRET)
    }

    def "deleteCode acts on the id the bundle stores, not on a namesake in the bundle's namespace"() {
        given: 'the bundle delivered app 844; app 843 in the bundle namespace has the same name'
        enableWrite()
        def apps = [[id: 843, name: "PR Review App", namespace: "pr580rev"], [id: 844, name: "PR Review App", namespace: "pr580rev.apps"]]
        stubHub(fixtureBundle(namespace: "pr580rev", appTypeIds: [844], libraryIds: [],
                              contentReadable: "apps [PR Review App], drivers [], libraries []"), apps, [])

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == true
        result.codeDeleted == [[type: 'app', id: '844', name: 'PR Review App']]
        result.codeKept == []
        savedFiles.keySet().any { it.contains('mcp-backup-app-844') }
        !savedFiles.keySet().any { it.contains('mcp-backup-app-843') }
        !hubGet.calls*.key.contains('/app/ajax/code?id=843')
    }

    def "deleteCode backs up an app whose name the summary text cannot split"() {
        given:
        enableWrite()
        def apps = [[id: 845, name: "]PR580 Review App", namespace: "pr580rev"]]
        stubHub(fixtureBundle(appTypeIds: [845], libraryIds: [],
                              contentReadable: "apps []PR580 Review App], drivers [], libraries []"), apps, [])

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == true
        result.codeDeleted == [[type: 'app', id: '845', name: ']PR580 Review App']]
        savedFiles.keySet().any { it.contains('mcp-backup-app-845') }
        result.message.contains('1 of 1')
    }

    @Unroll
    def "deleteCode via dispatch deletes the delivered code (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", deleteCode: true, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.codeDeleted*.id == ['11', '21']
        inner.codeKept == []
        inner.note == null

        where:
        useGateways << [true, false]
    }

    def "deleteCode refuses up front, naming the id, when the bundle stores code that is gone"() {
        given: 'library 21 was deleted by hand after the install; the bundle still lists it'
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), [])

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains('library 21')
        result.missingCode == [[type: 'library', id: '21']]
        result.note.contains('Nothing was deleted')
        result.note.contains(guide())
        !hubGet.calls.any { it.path in ['/app/ajax/code', '/library/list/single/data/21'] }
        savedFiles.isEmpty()
        !deleteSent()
    }

    def "deleteCode refuses when a code list cannot be read"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        hubGet.register('/hub2/userLibraries') { params -> throw new RuntimeException('HTTP 500') }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains('library code list could not be read')
        savedFiles.isEmpty()
        !deleteSent()
    }

    def "deleteCode fails closed when a source cannot be read for its backup"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        hubGet.register('/library/list/single/data/21') { params -> '[]' }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains("Could not read the source of library 21")
        result.note.contains('Nothing was backed up or deleted')
        savedFiles.isEmpty()
        !deleteSent()
    }

    def "deleteCode fails closed when a backup cannot be written"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            if (name.contains('library')) throw new RuntimeException('disk full')
            savedFiles[name] = content
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains("Could not back up library 21")
        result.note.contains('Nothing was deleted. backupFiles lists the backups already written.')
        result.backupFiles.size() == 1
        result.backupFiles[0].contains('mcp-backup-app-11')
        !deleteSent()
    }

    def "deleteCode refuses before any backup when the bundle's backups alone exceed the budget"() {
        given:
        enableWrite()
        settingsMap.backupBudgetMb = 1
        String big = 'x' * (700 * 1024)
        stubHub(fixtureBundle(appTypeIds: [11, 12], libraryIds: []), targetApps(), [])
        hubGet.register('/app/ajax/code') { params -> groovy.json.JsonOutput.toJson([status: 'ok', version: 1, source: big]) }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains('backup storage budget')
        result.note.contains('Nothing was backed up or deleted')
        savedFiles.isEmpty()
        !deleteSent()
    }

    def "every backup deleteCode takes is kept until the delete, even the oldest"() {
        given: 'app 11 has an older backup this delete reuses; app 99 has a newer, unrelated one'
        enableWrite()
        settingsMap.backupBudgetMb = 1
        long now = 1234567890000L
        long kb400 = 400L * 1024L
        backupIndex = [
            app_11: [type: 'app', id: '11', fileName: 'mcp-backup-app-11.groovy', timestamp: now - 1800000L, version: 1, sourceLength: kb400, sizeBytes: kb400],
            app_99: [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy', timestamp: now - 600000L, version: 1, sourceLength: kb400, sizeBytes: kb400]
        ]
        stubHub(fixtureBundle(appTypeIds: [11, 12], libraryIds: []), targetApps(), [])
        hubGet.register('/app/ajax/code') { params -> groovy.json.JsonOutput.toJson([status: 'ok', version: 1, source: 'y' * kb400]) }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then: 'publishing app 12 evicts the unrelated app 99, not the bundle\'s older app 11 backup'
        result.success == true
        backupIndex.keySet() == ['app_11', 'app_12'] as Set
        result.backupFiles == ['mcp-backup-app-11.groovy', 'mcp-backup-app-12.groovy']
        !hubGet.calls*.key.contains('/app/ajax/code?id=11')
    }

    def "deleteCode holds the backup lock through the delete, so a competing backup publishes after it and is kept"() {
        given: 'a 1 MB budget: the bundle\'s two 400 KB backups and a third 400 KB one cannot all be kept'
        enableWrite()
        settingsMap.backupBudgetMb = 1
        long kb400 = 400L * 1024L
        stubHub(fixtureBundle(appTypeIds: [11, 12], libraryIds: []), targetApps(), [])
        hubGet.register('/app/ajax/code') { params -> groovy.json.JsonOutput.toJson([status: 'ok', version: 1, source: 'y' * kb400]) }

        and: 'another tool backs up app 99 once the bundle\'s first backup is being written'
        Map rival = [:]
        Thread rivalThread = null
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            savedFiles[name] = content
            if (rivalThread == null) {
                rivalThread = Thread.start {
                    try { rival.entry = script.backupItemSource('app', '99') } catch (Throwable t) { rival.error = t }
                }
            }
        }
        boolean rivalWaiting = false
        Set indexAtDelete = null
        onDelete = {
            rivalThread.join(300)
            rivalWaiting = rivalThread.alive
            indexAtDelete = backupIndex.keySet()
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])
        rivalThread.join(5000)

        then: 'the rival backup waited through the bundle\'s backups and the delete'
        rivalWaiting
        indexAtDelete == ['app_11', 'app_12'] as Set
        result.success == true
        result.backupFiles == ['mcp-backup-app-11.groovy', 'mcp-backup-app-12.groovy']

        and: 'it then published, and the file it reports still exists'
        !rivalThread.alive
        rival.error == null
        savedFiles.containsKey(rival.entry.fileName)
        backupIndex.containsKey('app_99')
    }

    // Each way deleteCode refuses before it sends the delete.
    private String arrangeDeleteCodeRefusal(String why) {
        switch (why) {
            case 'missingCode':
                stubHub(fixtureBundle(), targetApps(), [])
                return 'library 21'
            case 'unreadable code list':
                stubHub(fixtureBundle(), targetApps(), sharedLibs())
                hubGet.register('/hub2/userLibraries') { params -> throw new RuntimeException('HTTP 500') }
                return 'library code list could not be read'
            case 'failed source read':
                stubHub(fixtureBundle(), targetApps(), sharedLibs())
                hubGet.register('/library/list/single/data/21') { params -> '[]' }
                return 'Could not read the source of library 21'
            case 'failed backup':
                stubHub(fixtureBundle(), targetApps(), sharedLibs())
                script.metaClass.uploadHubFile = { String name, byte[] content ->
                    if (name.contains('library')) throw new RuntimeException('disk full')
                    savedFiles[name] = content
                }
                return 'Could not back up library 21'
            case 'over budget':
                settingsMap.backupBudgetMb = 1
                stubHub(fixtureBundle(appTypeIds: [11, 12], libraryIds: []), targetApps(), [])
                hubGet.register('/app/ajax/code') { params -> groovy.json.JsonOutput.toJson([status: 'ok', version: 1, source: 'x' * (700 * 1024)]) }
                return 'backup storage budget'
        }
        throw new IllegalStateException(why)
    }

    @Unroll
    def "a deleteCode refusal via dispatch is an isError result with no delete sent (#why, useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        String expected = arrangeDeleteCodeRefusal(why)

        when:
        def response = mcpDriver.callTool('hub_delete_bundle', [bundleId: "4", deleteCode: true, confirm: true])

        then:
        response.jsonrpc == "2.0"
        response.id == mcpDriver.lastSentId
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains(expected)
        !deleteSent()

        where:
        [why, useGateways] << [['missingCode', 'unreadable code list', 'failed source read', 'failed backup', 'over budget'], [true, false]].combinations()
    }

    @Unroll
    def "a delete with deleteCode the hub did not confirm reports where the bundle and each item stand (#why)"() {
        given: 'the hub deletes the bundle and app 11, then reports failure'
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs(), ['21'] as Set, reply)

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.bundleExists == false
        result.codeDeleted == [[type: 'app', id: '11', name: 'Target App']]
        result.codeKept == [[type: 'library', id: '21', name: 'Shared Lib']]
        result.backupFiles.size() == 2
        result.note.contains('gone although')
        result.note.contains('codeDeleted')
        result.note.contains(guide())

        where:
        why            | reply
        'success:false' | '{"success":false,"message":null}'
        'unrecognized' | '<html>Bundles</html>'
    }

    def "a thrown delete with deleteCode reports the bundle still there and the code unverified when a list fails after"() {
        given:
        enableWrite()
        stubHub(fixtureBundle(), targetApps(), sharedLibs())
        int libReads = 0
        hubGet.register('/bundle/deleteJson/4') { params -> throw new RuntimeException("read timed out") }
        hubGet.register('/hub2/userLibraries') { params ->
            if (libReads++ > 0) throw new RuntimeException('busy')
            groovy.json.JsonOutput.toJson(sharedLibs())
        }

        when:
        def result = script.toolDeleteBundle([bundleId: "4", deleteCode: true, confirm: true])

        then:
        result.success == false
        result.error.contains('read timed out')
        result.bundleExists == true
        result.codeKept == [[type: 'app', id: '11', name: 'Target App']]
        result.codeUnverified == [[type: 'library', id: '21', name: 'Shared Lib']]
        result.note.contains('codeUnverified')
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
        hubGet.register('/bundle/json/999') { params -> unknownJson() }

        when:
        def result = script.toolExportBundle([bundleId: "999"])

        then:
        result.success == false
        result.error.contains("No bundle with id 999")
        result.note.contains("hub_get_tool_guide(section='bundles')")
        savedFiles.isEmpty()
        exportCaptured.path == null
    }

    def "hub_export_bundle falls back to a default name when the bundle record cannot be read"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/bundle/json/4') { params -> throw new RuntimeException('timeout') }

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == true
        result.fileName == "bundle-4.zip"
    }

    def "hub_export_bundle fetches the zip and saves it to the File Manager"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }

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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        result.note.contains("may not exist")
        result.note.contains("hub_list_bundles")
        savedFiles.isEmpty()
        hubGet.calls.isEmpty()
    }

    def "hub_export_bundle with saveAs and an unknown id says the id may not exist when the hub's 500 throws"() {
        given:
        settingsMap.enableWrite = true
        nextExportThrow = new RuntimeException("status code: 500, reason phrase: Server Error")

        when:
        def result = script.toolExportBundle([bundleId: "99999", saveAs: "x"])

        then:
        result.success == false
        result.error.contains("status code: 500")
        result.note.contains("may not exist")
        result.note.contains("hub_list_bundles")
        result.note.contains("hub_get_tool_guide(section='bundles')")
        savedFiles.isEmpty()
    }

    @Unroll
    def "a failed export via dispatch is an isError result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true
        nextExportStatus = 500
        nextExportData = ("<html>500</html>".getBytes("UTF-8"))

        when:
        def response = mcpDriver.callTool('hub_export_bundle', [bundleId: "4", saveAs: "x"])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).success == false

        where:
        useGateways << [true, false]
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
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
        hubGet.register('/bundle/json/4') { params -> recordJson(libsBundle()) }
        nextExportData = [unexpected: "not bytes"]   // a Map -- not byte[]/InputStream

        when:
        def result = script.toolExportBundle([bundleId: "4"])

        then:
        result.success == false
        result.error.contains("non-binary body")
        savedFiles.isEmpty()
    }

}
