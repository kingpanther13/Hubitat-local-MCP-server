package server

import support.ToolSpecBase

/**
 * Spec for the manage_apps_drivers gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolListHubApps       -> list_hub_apps
 * - toolListHubDrivers    -> list_hub_drivers
 * - toolGetAppSource      -> get_app_source     (delegates to toolGetItemSource)
 * - toolGetDriverSource   -> get_driver_source  (delegates to toolGetItemSource)
 * - toolListItemBackups   -> list_item_backups  (reads state only — no gating)
 * - toolGetItemBackup     -> get_item_backup    (reads state + downloadHubFile)
 *
 * All source/listing tools go through requireHubAdminRead — tests that exercise
 * the happy path seed settingsMap.enableHubAdminRead = true. Backup tools are
 * not gated.
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet   — routed by HarnessSpec via hubGet.register(path) closures.
 *   - downloadHubFile  — purely dynamic, stubbed per-test on script.metaClass.
 *   - uploadHubFile    — purely dynamic, stubbed per-test on script.metaClass
 *                        (only needed for large-source paths in get_app_source).
 */
class ToolAppsDriversSpec extends ToolSpecBase {

    private void enableHubAdminRead() {
        settingsMap.enableHubAdminRead = true
    }

    // -------- toolListHubApps --------

    def "list_hub_apps throws when Hub Admin Read is disabled"() {
        when:
        script.toolListHubApps([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "list_hub_apps returns parsed apps from the hub API"() {
        given:
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params ->
            '[{"id": 1, "name": "App One"}, {"id": 2, "name": "App Two"}]'
        }

        when:
        def result = script.toolListHubApps([:])

        then:
        result.source == 'hub_api'
        result.count == 2
        result.apps*.name == ['App One', 'App Two']
    }

    def "list_hub_apps returns raw response when hub returns non-JSON"() {
        given:
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params -> '<html>not json</html>' }

        when:
        def result = script.toolListHubApps([:])

        then:
        result.source == 'hub_api_raw'
        result.rawResponse.contains('not json')
        result.note.contains('not JSON')
    }

    def "list_hub_apps falls back to MCP child apps when hub API throws"() {
        given: 'hub API unavailable'
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params ->
            throw new RuntimeException('endpoint not available')
        }

        when:
        def result = script.toolListHubApps([:])

        then:
        result.source == 'mcp_only'
        result.note.contains('endpoint not available')
        result.apps == []
    }

    // -------- toolListHubDrivers --------

    def "list_hub_drivers throws when Hub Admin Read is disabled"() {
        when:
        script.toolListHubDrivers([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "list_hub_drivers returns parsed drivers from the hub API"() {
        given:
        enableHubAdminRead()
        hubGet.register('/hub2/userDeviceTypes') { params ->
            '[{"id": 10, "name": "Generic Z-Wave Switch"}]'
        }

        when:
        def result = script.toolListHubDrivers([:])

        then:
        result.source == 'hub_api'
        result.count == 1
        result.drivers[0].name == 'Generic Z-Wave Switch'
    }

    def "list_hub_drivers reports unavailable when the hub API throws"() {
        given:
        enableHubAdminRead()
        hubGet.register('/hub2/userDeviceTypes') { params ->
            throw new RuntimeException('Connection refused')
        }

        when:
        def result = script.toolListHubDrivers([:])

        then:
        result.source == 'unavailable'
        result.count == 0
        result.drivers == []
        result.note.contains('Connection refused')
    }

    // -------- toolGetAppSource / toolGetDriverSource --------

    def "get_app_source throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetAppSource([appId: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_app_source throws when appId is missing"() {
        given:
        enableHubAdminRead()

        when:
        script.toolGetAppSource([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    def "get_app_source returns the full source for a small app in a single chunk"() {
        given:
        enableHubAdminRead()
        hubGet.register('/app/ajax/code') { params ->
            params.id == '123'
                ? '{"status": "ok", "version": 7, "source": "definition(name: \\"Hello\\")"}'
                : '{"status": "error", "errorMessage": "unknown id"}'
        }

        when:
        def result = script.toolGetAppSource([appId: '123'])

        then:
        result.success == true
        result.appId == '123'
        result.version == 7
        result.source.contains('Hello')
        result.hasMore == false
        result.offset == 0
        result.totalLength == result.source.length()
    }

    def "get_app_source reports hub-side error when the ajax endpoint returns status=error"() {
        given:
        enableHubAdminRead()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "error", "errorMessage": "No such app"}'
        }

        when:
        def result = script.toolGetAppSource([appId: '999'])

        then:
        result.success == false
        result.error.contains('No such app')
    }

    def "get_app_source chunks large sources and saves a File Manager copy"() {
        given:
        enableHubAdminRead()
        def bigSource = 'x' * 70000  // exceeds the 64000-byte chunk threshold
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "' + bigSource + '"}'
        }
        and: 'uploadHubFile succeeds and records the call'
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, size: content.length]
            return true
        }

        when:
        def result = script.toolGetAppSource([appId: '456'])

        then: 'first chunk returned with pagination metadata'
        result.success == true
        result.totalLength == 70000
        result.chunkLength == 64000
        result.hasMore == true
        result.nextOffset == 64000
        result.remainingChars == 6000
        result.sourceFile == 'mcp-source-app-456.groovy'

        and: 'full source was uploaded to File Manager'
        uploads.size() == 1
        uploads[0].name == 'mcp-source-app-456.groovy'
        uploads[0].size == 70000
    }

    def "get_driver_source delegates to the same implementation with driverId"() {
        given:
        enableHubAdminRead()
        hubGet.register('/driver/ajax/code') { params ->
            params.id == '88'
                ? '{"status": "ok", "version": 1, "source": "metadata { }"}'
                : '{"status": "error", "errorMessage": "unknown driver"}'
        }

        when:
        def result = script.toolGetDriverSource([driverId: '88'])

        then:
        result.success == true
        result.driverId == '88'
        result.source.contains('metadata')
    }

    def "get_driver_source throws when driverId is missing"() {
        given:
        enableHubAdminRead()

        when:
        script.toolGetDriverSource([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('driverId is required')
    }

    // -------- toolListItemBackups --------

    def "list_item_backups returns empty list and guidance when no backups exist"() {
        when:
        def result = script.toolListItemBackups()

        then:
        result.backups == []
        result.total == 0
        result.message.contains('No item backups')
        result.maxBackups == 20
    }

    def "list_item_backups returns all manifest entries sorted newest first"() {
        given: 'two manifest entries with different timestamps'
        stateMap.itemBackupManifest = [
            'app_10'   : [type: 'app',    id: '10', fileName: 'mcp-backup-app-10.groovy',
                          version: 5, timestamp: 1_000_000_000_000L, sourceLength: 100],
            'driver_20': [type: 'driver', id: '20', fileName: 'mcp-backup-driver-20.groovy',
                          version: 2, timestamp: 2_000_000_000_000L, sourceLength: 200]
        ]

        when:
        def result = script.toolListItemBackups()

        then:
        result.total == 2
        result.backups[0].backupKey == 'driver_20'  // newer timestamp listed first
        result.backups[1].backupKey == 'app_10'
        result.backups[0].type == 'driver'
        result.backups[0].version == 2
        result.backups[0].sourceLength == 200
    }

    // -------- toolGetItemBackup --------

    def "get_item_backup throws when backupKey is missing"() {
        when:
        script.toolGetItemBackup([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('backupKey is required')
    }

    def "get_item_backup returns helpful error when the key is unknown"() {
        given:
        stateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 1, timestamp: 1_000_000_000_000L, sourceLength: 0]
        ]

        when:
        def result = script.toolGetItemBackup([backupKey: 'app_missing'])

        then:
        result.error.contains("'app_missing'")
        result.availableBackups.contains('app_1')
        result.hint.contains('list_item_backups')
    }

    def "get_item_backup reads the backup source from File Manager"() {
        given:
        stateMap.itemBackupManifest = [
            'app_5': [type: 'app', id: '5', fileName: 'mcp-backup-app-5.groovy',
                      version: 2, timestamp: 1_234_000_000_000L, sourceLength: 123]
        ]

        and: 'downloadHubFile returns the backup contents'
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-app-5.groovy' ? 'definition(name: "App Five")'.getBytes('UTF-8') : null
        }

        when:
        def result = script.toolGetItemBackup([backupKey: 'app_5'])

        then:
        result.backupKey == 'app_5'
        result.type == 'app'
        result.id == '5'
        result.version == 2
        result.source == 'definition(name: "App Five")'
        result.sourceLength == result.source.length()
        result.howToRestore.contains('restore_item_backup')
    }

    def "get_item_backup reports error when the backup file cannot be read"() {
        given:
        stateMap.itemBackupManifest = [
            'app_6': [type: 'app', id: '6', fileName: 'mcp-backup-app-6.groovy',
                      version: 1, timestamp: 1_234_000_000_000L, sourceLength: 0]
        ]
        script.metaClass.downloadHubFile = { String fileName -> null }  // file missing

        when:
        def result = script.toolGetItemBackup([backupKey: 'app_6'])

        then:
        result.error.contains("'mcp-backup-app-6.groovy'")
        result.backupKey == 'app_6'
        result.suggestion.contains('File Manager')
    }

    def "get_item_backup omits inline source when the file exceeds the MCP response limit"() {
        given:
        stateMap.itemBackupManifest = [
            'driver_9': [type: 'driver', id: '9', fileName: 'mcp-backup-driver-9.groovy',
                         version: 1, timestamp: 1_234_000_000_000L, sourceLength: 0]
        ]
        def hugeSource = 'y' * 60001  // > 60000 cap
        script.metaClass.downloadHubFile = { String fileName -> hugeSource.getBytes('UTF-8') }

        when:
        def result = script.toolGetItemBackup([backupKey: 'driver_9'])

        then:
        result.sourceTooLargeForResponse == true
        result.source == null
        result.message.contains('60001')
        result.manualDownload.contains('mcp-backup-driver-9.groovy')
    }
}
