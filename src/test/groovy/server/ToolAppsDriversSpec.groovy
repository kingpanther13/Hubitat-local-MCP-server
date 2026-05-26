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
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false.
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

    @spock.lang.Unroll
    def "list_hub_apps via dispatch returns -32602 envelope when Hub Admin Read disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('list_hub_apps', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Read')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_hub_apps via dispatch returns parsed apps from the hub API (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params ->
            '[{"id": 1, "name": "App One"}, {"id": 2, "name": "App Two"}]'
        }

        when:
        def response = mcpDriver.callTool('list_hub_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'hub_api'
        inner.count == 2
        inner.apps*.name == ['App One', 'App Two']

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_hub_apps via dispatch returns raw response envelope when hub returns non-JSON (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params -> '<html>not json</html>' }

        when:
        def response = mcpDriver.callTool('list_hub_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'hub_api_raw'
        inner.rawResponse.contains('not json')
        inner.note.contains('not JSON')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_hub_apps via dispatch falls back to MCP child apps envelope when hub API throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/hub2/userAppTypes') { params ->
            throw new RuntimeException('endpoint not available')
        }

        when:
        def response = mcpDriver.callTool('list_hub_apps', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'mcp_only'
        inner.note.contains('endpoint not available')
        inner.apps == []

        where:
        useGateways << [true, false]
    }

    // -------- toolListHubDrivers --------

    def "list_hub_drivers throws when Hub Admin Read is disabled"() {
        when:
        script.toolListHubDrivers([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    @spock.lang.Unroll
    def "list_hub_drivers via dispatch returns -32602 envelope when Hub Admin Read disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('list_hub_drivers', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Read')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_hub_drivers via dispatch returns parsed drivers from the hub API (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/hub2/userDeviceTypes') { params ->
            '[{"id": 10, "name": "Generic Z-Wave Switch"}]'
        }

        when:
        def response = mcpDriver.callTool('list_hub_drivers', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'hub_api'
        inner.count == 1
        inner.drivers[0].name == 'Generic Z-Wave Switch'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_hub_drivers via dispatch reports unavailable envelope when hub API throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/hub2/userDeviceTypes') { params ->
            throw new RuntimeException('Connection refused')
        }

        when:
        def response = mcpDriver.callTool('list_hub_drivers', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.source == 'unavailable'
        inner.count == 0
        inner.drivers == []
        inner.note.contains('Connection refused')

        where:
        useGateways << [true, false]
    }

    // -------- toolGetAppSource / toolGetDriverSource --------

    def "get_app_source throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetAppSource([appId: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    @spock.lang.Unroll
    def "get_app_source via dispatch returns -32602 envelope when Hub Admin Read disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('get_app_source', [appId: '1'])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Read')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_app_source via dispatch returns -32602 envelope when appId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()

        when:
        def response = mcpDriver.callTool('get_app_source', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('appId is required')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_app_source via dispatch returns full source for a small app in a single chunk (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/app/ajax/code') { params ->
            params.id == '123'
                ? '{"status": "ok", "version": 7, "source": "definition(name: \\"Hello\\")"}'
                : '{"status": "error", "errorMessage": "unknown id"}'
        }

        when:
        def response = mcpDriver.callTool('get_app_source', [appId: '123'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '123'
        inner.version == 7
        inner.source.contains('Hello')
        inner.hasMore == false
        inner.offset == 0
        inner.totalLength == inner.source.length()

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_app_source via dispatch reports hub-side error envelope when ajax endpoint returns status=error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "error", "errorMessage": "No such app"}'
        }

        when:
        def response = mcpDriver.callTool('get_app_source', [appId: '999'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('No such app')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_app_source via dispatch chunks large sources and saves File Manager copy (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        def bigSource = 'x' * 70000
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "' + bigSource + '"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, size: content.length]
            return true
        }

        when:
        def response = mcpDriver.callTool('get_app_source', [appId: '456'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.totalLength == 70000
        inner.chunkLength == 64000
        inner.hasMore == true
        inner.nextOffset == 64000
        inner.remainingChars == 6000
        inner.sourceFile == 'mcp-source-app-456.groovy'
        uploads.size() == 1
        uploads[0].name == 'mcp-source-app-456.groovy'
        uploads[0].size == 70000

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_driver_source via dispatch delegates with driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()
        hubGet.register('/driver/ajax/code') { params ->
            params.id == '88'
                ? '{"status": "ok", "version": 1, "source": "metadata { }"}'
                : '{"status": "error", "errorMessage": "unknown driver"}'
        }

        when:
        def response = mcpDriver.callTool('get_driver_source', [driverId: '88'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '88'
        inner.source.contains('metadata')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "get_driver_source via dispatch returns -32602 envelope when driverId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminRead()

        when:
        def response = mcpDriver.callTool('get_driver_source', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('driverId is required')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "list_item_backups via dispatch returns empty list envelope when no backups exist (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('list_item_backups', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.backups == []
        inner.total == 0
        inner.message.contains('No item backups')
        inner.maxBackups == 20

        where:
        useGateways << [true, false]
    }

    def "list_item_backups returns all manifest entries sorted newest first"() {
        given: 'two manifest entries with different timestamps'
        atomicStateMap.itemBackupManifest = [
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

    @spock.lang.Unroll
    def "list_item_backups via dispatch returns all manifest entries sorted newest first (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'app_10'   : [type: 'app',    id: '10', fileName: 'mcp-backup-app-10.groovy',
                          version: 5, timestamp: 1_000_000_000_000L, sourceLength: 100],
            'driver_20': [type: 'driver', id: '20', fileName: 'mcp-backup-driver-20.groovy',
                          version: 2, timestamp: 2_000_000_000_000L, sourceLength: 200]
        ]

        when:
        def response = mcpDriver.callTool('list_item_backups', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.total == 2
        inner.backups[0].backupKey == 'driver_20'
        inner.backups[1].backupKey == 'app_10'
        inner.backups[0].type == 'driver'
        inner.backups[0].version == 2
        inner.backups[0].sourceLength == 200

        where:
        useGateways << [true, false]
    }

    // -------- toolGetItemBackup --------

    def "get_item_backup throws when backupKey is missing"() {
        when:
        script.toolGetItemBackup([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('backupKey is required')
    }

    @spock.lang.Unroll
    def "get_item_backup via dispatch returns -32602 envelope when backupKey is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('get_item_backup', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('backupKey is required')

        where:
        useGateways << [true, false]
    }

    def "get_item_backup returns helpful error when the key is unknown"() {
        given:
        atomicStateMap.itemBackupManifest = [
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

    @spock.lang.Unroll
    def "get_item_backup via dispatch returns helpful error envelope when key is unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 1, timestamp: 1_000_000_000_000L, sourceLength: 0]
        ]

        when:
        def response = mcpDriver.callTool('get_item_backup', [backupKey: 'app_missing'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.error.contains("'app_missing'")
        inner.availableBackups.contains('app_1')
        inner.hint.contains('list_item_backups')

        where:
        useGateways << [true, false]
    }

    def "get_item_backup reads the backup source from File Manager"() {
        given:
        atomicStateMap.itemBackupManifest = [
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

    @spock.lang.Unroll
    def "get_item_backup via dispatch reads backup source from File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'app_5': [type: 'app', id: '5', fileName: 'mcp-backup-app-5.groovy',
                      version: 2, timestamp: 1_234_000_000_000L, sourceLength: 123]
        ]
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-app-5.groovy' ? 'definition(name: "App Five")'.getBytes('UTF-8') : null
        }

        when:
        def response = mcpDriver.callTool('get_item_backup', [backupKey: 'app_5'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.backupKey == 'app_5'
        inner.type == 'app'
        inner.id == '5'
        inner.version == 2
        inner.source == 'definition(name: "App Five")'
        inner.sourceLength == inner.source.length()
        inner.howToRestore.contains('restore_item_backup')

        where:
        useGateways << [true, false]
    }

    def "get_item_backup reports error when the backup file cannot be read"() {
        given:
        atomicStateMap.itemBackupManifest = [
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

    @spock.lang.Unroll
    def "get_item_backup via dispatch reports error envelope when backup file cannot be read (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'app_6': [type: 'app', id: '6', fileName: 'mcp-backup-app-6.groovy',
                      version: 1, timestamp: 1_234_000_000_000L, sourceLength: 0]
        ]
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('get_item_backup', [backupKey: 'app_6'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.error.contains("'mcp-backup-app-6.groovy'")
        inner.backupKey == 'app_6'
        inner.suggestion.contains('File Manager')

        where:
        useGateways << [true, false]
    }

    def "get_item_backup omits inline source when the file exceeds the MCP response limit"() {
        given:
        atomicStateMap.itemBackupManifest = [
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

    @spock.lang.Unroll
    def "get_item_backup via dispatch omits inline source when file exceeds the MCP response limit (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'driver_9': [type: 'driver', id: '9', fileName: 'mcp-backup-driver-9.groovy',
                         version: 1, timestamp: 1_234_000_000_000L, sourceLength: 0]
        ]
        def hugeSource = 'y' * 60001
        script.metaClass.downloadHubFile = { String fileName -> hugeSource.getBytes('UTF-8') }

        when:
        def response = mcpDriver.callTool('get_item_backup', [backupKey: 'driver_9'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.sourceTooLargeForResponse == true
        inner.source == null
        inner.message.contains('60001')
        inner.manualDownload.contains('mcp-backup-driver-9.groovy')

        where:
        useGateways << [true, false]
    }

    def "get_item_backup library-type uses update_library_code restore path, not restore_item_backup"() {
        given:
        // Library backups cannot go through restore_item_backup -- that tool only handles
        // apps and drivers. The howToRestore text must point to update_library_code.
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-library-42.groovy' ? 'library(name:"Foo")'.getBytes('UTF-8') : null
        }

        when:
        def result = script.toolGetItemBackup([backupKey: 'library_42'])

        then: 'standard fields are present'
        result.backupKey == 'library_42'
        result.type == 'library'
        result.id == '42'
        result.version == 3
        result.source == 'library(name:"Foo")'
        result.directDownload.contains('mcp-backup-library-42.groovy')

        and: 'restore path names update_library_code as the action; restore_item_backup mentioned only as not applicable'
        result.howToRestore.contains('save_library')
        result.howToRestore.contains('cannot be restored via restore_item_backup')

        and: 'restore path includes the correct library ID and file reference'
        result.howToRestore.contains("libraryId='42'")
        result.howToRestore.contains("sourceFile='mcp-backup-library-42.groovy'")
    }

    @spock.lang.Unroll
    def "get_item_backup via dispatch library-type uses update_library_code restore path (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-library-42.groovy' ? 'library(name:"Foo")'.getBytes('UTF-8') : null
        }

        when:
        def response = mcpDriver.callTool('get_item_backup', [backupKey: 'library_42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.backupKey == 'library_42'
        inner.type == 'library'
        inner.id == '42'
        inner.version == 3
        inner.source == 'library(name:"Foo")'
        inner.directDownload.contains('mcp-backup-library-42.groovy')
        inner.howToRestore.contains('save_library')
        inner.howToRestore.contains('cannot be restored via restore_item_backup')
        inner.howToRestore.contains("libraryId='42'")
        inner.howToRestore.contains("sourceFile='mcp-backup-library-42.groovy'")

        where:
        useGateways << [true, false]
    }
}
