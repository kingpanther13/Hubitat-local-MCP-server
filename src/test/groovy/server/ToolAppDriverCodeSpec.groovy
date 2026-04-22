package server

import support.ToolSpecBase

/**
 * Spec for the manage_app_driver_code gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolInstallApp          -> install_app
 * - toolInstallDriver       -> install_driver
 * - toolUpdateAppCode       -> update_app_code     (three source modes: source, sourceFile, resave)
 * - toolUpdateDriverCode    -> update_driver_code  (three source modes)
 * - toolDeleteApp           -> delete_app
 * - toolDeleteDriver        -> delete_driver
 * - toolRestoreItemBackup   -> restore_item_backup
 *
 * Every tool here runs through requireHubAdminWrite — golden-path tests seed:
 *   settingsMap.enableHubAdminWrite = true
 *   stateMap.lastBackupTimestamp    = 1234567890000L   (matches fixed now())
 *   args.confirm                    = true
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet       — routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPostForm  — script-defined helper, stubbed per-test on script.metaClass
 *                            (returns [status, location, data]).
 *   - uploadHubFile / downloadHubFile — purely dynamic, stubbed per-test on script.metaClass.
 */
class ToolAppDriverCodeSpec extends ToolSpecBase {

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolInstallApp / toolInstallDriver --------

    def "install_app throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallApp([source: 'definition(name: "X")'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "install_app throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallApp([source: 'definition(name: "X")', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "install_app throws when source is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    def "install_app POSTs to /app/save and extracts the new appId from the Location header"() {
        given:
        enableHubAdminWrite()
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/4242', data: '']
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "Hello")', confirm: true])

        then:
        captured.path == '/app/save'
        captured.body.source == 'definition(name: "Hello")'
        captured.body.id == ''
        captured.body.create == ''
        result.success == true
        result.appId == '4242'
        result.message.contains('installed')
    }

    def "install_app returns success with warning when the Location header is absent"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: 'ok']
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "NoLoc")', confirm: true])

        then:
        result.success == true
        result.appId == null
        result.warning.contains('Could not extract')
    }

    def "install_app reports failure when the hub POST throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            throw new RuntimeException('compile error: bad syntax')
        }

        when:
        def result = script.toolInstallApp([source: 'bad', confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('compile error')
        result.note.contains('syntax errors')
    }

    def "install_driver POSTs to /driver/save and returns the new driverId"() {
        given:
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedPath = path
            [status: 302, location: '/driver/editor/9001', data: '']
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        postedPath == '/driver/save'
        result.success == true
        result.driverId == '9001'
    }

    // -------- toolUpdateAppCode / toolUpdateDriverCode --------

    def "update_app_code throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateAppCode([appId: '1', source: 'x'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "update_app_code throws when appId is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateAppCode([source: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    def "update_app_code throws when none of source, sourceFile, or resave are supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateAppCode([appId: '1', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'source'")
        ex.message.contains("'sourceFile'")
        ex.message.contains("'resave'")
    }

    def "update_app_code (source mode) backs up, fetches version, POSTs to /app/ajax/update"() {
        given:
        enableHubAdminWrite()

        and: 'backupItemSource fetches source + uploads backup; update then fetches version again'
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 12, "source": "old source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }

        and: 'hubInternalPostForm returns a success response'
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new source', confirm: true])

        then: 'update path and body match the hub contract'
        captured.path == '/app/ajax/update'
        captured.body.id == '50'
        captured.body.version == 12
        captured.body.source == 'new source'

        and: 'pre-edit backup written to File Manager'
        uploads.size() == 1
        uploads[0] == 'mcp-backup-app-50.groovy'

        and: 'result reports success with sourceMode=source'
        result.success == true
        result.sourceMode == 'source'
        result.sourceLength == 'new source'.length()
        result.appId == '50'
        result.previousVersion == 12

        and: 'manifest entry is removed after a successful update (regression guard for the GString.get/remove coercion bug)'
        !stateMap.itemBackupManifest?.containsKey('app_50')
    }

    def "update_app_code (sourceFile mode) reads source from File Manager"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "on-hub source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'source from file'.getBytes('UTF-8') : null
        }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '60', sourceFile: 'my-app.groovy', confirm: true])

        then:
        captured.body.source == 'source from file'
        result.success == true
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    def "update_app_code (sourceFile mode) throws when the file is absent"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolUpdateAppCode([appId: '60', sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("not found in File Manager")
    }

    def "update_app_code (resave mode) fetches the current source and re-saves it"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 7, "source": "current source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '70', resave: true, confirm: true])

        then: 'submitted source matches fetched source; version captured from the fresh fetch'
        captured.body.source == 'current source'
        captured.body.version == 7
        result.success == true
        result.sourceMode == 'resave'
        result.note.contains('no cloud round-trip')
    }

    def "update_app_code reports failure when the hub response parses to status=error"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "error", "errorMessage": "Compilation failed"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'broken', confirm: true])

        then:
        result.success == false
        result.error.contains('Compilation failed')
        result.note.contains('syntax')
    }

    def "update_driver_code delegates to toolUpdateItemCode with the driver paths"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 4, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        captured.path == '/driver/ajax/update'
        result.success == true
        result.driverId == '55'
        result.sourceMode == 'source'
    }

    // -------- toolDeleteApp / toolDeleteDriver --------

    def "delete_app throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteApp([appId: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "delete_app throws when Hub Admin Write is disabled"() {
        when:
        script.toolDeleteApp([appId: '1', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "delete_app throws when appId is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    def "delete_app backs up source, deletes via deleteJsonSafe, and reports the backup file"() {
        given:
        enableHubAdminWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }

        and: 'ajax/code returns current source (for backup); deleteJsonSafe returns success'
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 2, "source": "code body"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/33') { params ->
            '{"status": "true"}'
        }

        when:
        def result = script.toolDeleteApp([appId: '33', confirm: true])

        then:
        result.success == true
        result.appId == '33'
        result.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        result.restoreHint.contains('install_app')
    }

    def "delete_app proceeds with a warning when pre-delete backup fails"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('source fetch failed')
        }
        hubGet.register('/app/edit/deleteJsonSafe/44') { params ->
            '{"status": "true"}'
        }

        when:
        def result = script.toolDeleteApp([appId: '44', confirm: true])

        then: 'delete still succeeds, but the response flags that the backup could not be created'
        result.success == true
        result.message.contains('backup failed')
        result.backupWarning.contains('permanently lost')
    }

    def "delete_app reports failure when the hub delete response signals error"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "x"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/55') { params ->
            '{"status": "error", "errorMessage": "in use"}'
        }

        when:
        def result = script.toolDeleteApp([appId: '55', confirm: true])

        then:
        result.success == false
        result.error.contains('Delete may have failed')
        result.appId == '55'
    }

    def "delete_driver hits /driver/editor/deleteJson/<id> and succeeds"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "driver src"}'
        }
        def deletePath = null
        hubGet.register('/driver/editor/deleteJson/77') { params ->
            deletePath = '/driver/editor/deleteJson/77'
            '{"status": "true"}'
        }

        when:
        def result = script.toolDeleteDriver([driverId: '77', confirm: true])

        then:
        deletePath == '/driver/editor/deleteJson/77'
        result.success == true
        result.driverId == '77'
        result.backupFile == 'mcp-backup-driver-77.groovy'
    }

    // -------- toolRestoreItemBackup --------

    def "restore_item_backup throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolRestoreItemBackup([backupKey: 'app_1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "restore_item_backup throws when backupKey is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolRestoreItemBackup([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('backupKey is required')
    }

    def "restore_item_backup returns error response when the key is unknown"() {
        given:
        enableHubAdminWrite()
        stateMap.itemBackupManifest = [
            'app_existing': [type: 'app', id: '1', fileName: 'f.groovy',
                             version: 1, timestamp: 1L, sourceLength: 0]
        ]

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_missing', confirm: true])

        then:
        result.success == false
        result.error.contains('app_missing')
        result.availableBackups.contains('app_existing')
    }

    def "restore_item_backup reads backup, creates a pre-restore copy, and pushes source to the hub"() {
        given:
        enableHubAdminWrite()

        and: 'a manifest entry for an app backup'
        stateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]

        and: 'downloadHubFile returns the backup contents'
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-app-99.groovy' ? 'old source v4'.getBytes('UTF-8') : null
        }

        and: 'current on-hub source fetch + version lookup (both hit /app/ajax/code)'
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }

        and: 'uploadHubFile records pre-restore backup writes'
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }

        and: 'hubInternalPostForm captures the update call and returns success'
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'pre-restore backup file was created with the current-source contents'
        uploads == ['mcp-prerestore-app-99.groovy']

        and: 'update hits /app/ajax/update with the backup source and the fresh version'
        captured.path == '/app/ajax/update'
        captured.body.id == '99'
        captured.body.version == 9
        captured.body.source == 'old source v4'

        and: 'result carries undo info and reports success'
        result.success == true
        result.type == 'app'
        result.id == '99'
        result.restoredVersion == 4
        result.preRestoreBackup == 'prerestore_app_99'
        result.undoHint.contains('prerestore_app_99')

        and: 'the original backup manifest entry was consumed but the pre-restore entry was added'
        stateMap.itemBackupManifest.containsKey('prerestore_app_99')
        !stateMap.itemBackupManifest.containsKey('app_99')
    }

    def "restore_item_backup reports failure and preserves the backup when the hub POST fails"() {
        given:
        enableHubAdminWrite()
        stateMap.itemBackupManifest = [
            'driver_88': [type: 'driver', id: '88', fileName: 'mcp-backup-driver-88.groovy',
                          version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'backup bytes'.getBytes('UTF-8') }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "error", "errorMessage": "bad code"}']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'driver_88', confirm: true])

        then: 'failure is reported, original manifest entry preserved for retry'
        result.success == false
        result.error.contains('bad code')
        result.message.contains('preserved')
        stateMap.itemBackupManifest.containsKey('driver_88')
    }
}
