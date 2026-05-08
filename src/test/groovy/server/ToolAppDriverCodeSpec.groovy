package server

import support.ToolSpecBase

/**
 * Spec for the manage_app_driver_code gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolInstallApp          -> install_app         (source|sourceFile; post-install verification)
 * - toolInstallDriver       -> install_driver      (source|sourceFile; bulk installs[]; post-install verification)
 * - toolUpdateAppCode       -> update_app_code     (three source modes: source, sourceFile, resave)
 * - toolUpdateDriverCode    -> update_driver_code  (three source modes + bulk updates array)
 * - toolDeleteApp           -> delete_app
 * - toolDeleteDriver        -> delete_driver
 * - toolRestoreItemBackup   -> restore_item_backup
 *
 * Every tool here runs through requireHubAdminWrite -- golden-path tests seed:
 *   settingsMap.enableHubAdminWrite = true
 *   stateMap.lastBackupTimestamp    = 1234567890000L   (matches fixed now())
 *   args.confirm                    = true
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet       -- routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPostForm  -- script-defined helper, stubbed per-test on script.metaClass
 *                            (returns [status, location, data]).
 *   - uploadHubFile / downloadHubFile -- purely dynamic, stubbed per-test on script.metaClass.
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

    def "install_app throws when neither source nor sourceFile is provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    def "install_app throws when both source and sourceFile are provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallApp([source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("not both")
    }

    def "install_app (source mode) POSTs to /app/save, verifies via ajax/code, and extracts the new appId"() {
        given:
        enableHubAdminWrite()
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/4242', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "definition(name: \"Hello\")", "version": 1}'
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
        result.sourceMode == 'source'
        result.message.contains('installed')
    }

    def "install_app (sourceFile mode) reads source from File Manager and installs it"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'definition(name: "FromFile")'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/5555', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "definition(name: \"FromFile\")", "version": 1}'
        }

        when:
        def result = script.toolInstallApp([sourceFile: 'my-app.groovy', confirm: true])

        then:
        result.success == true
        result.appId == '5555'
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    def "install_app (sourceFile mode) throws when the file is absent in File Manager"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallApp([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    def "install_app returns success=false when Location header is absent (hub did not persist item)"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: 'ok']
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "NoLoc")', confirm: true])

        then:
        result.success == false
        result.appId == null
        result.error.contains('no item ID')
    }

    def "install_app returns success=false when post-install verification shows an error state"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/9900', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Compilation failed: unexpected token"}'
        }

        when:
        def result = script.toolInstallApp([source: 'bad source', confirm: true])

        then:
        result.success == false
        result.appId == '9900'
        result.error.contains('Compilation failed')
        result.note.contains('9900')
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

    def "install_app surfaces qualified success (verified:false, verifyError populated) when post-install verification fetch throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7100', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "Test")', confirm: true])

        then:
        result.success == true
        result.appId == '7100'
        result.verified == false
        result.verifyError != null
        result.verifyError.contains('transient error')
        result.verifyError.contains('7100')
    }

    def "install_app returns success=false when verify endpoint returns an empty body"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7200', data: '']
        }
        hubGet.register('/app/ajax/code') { params -> '' }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "Test")', confirm: true])

        then:
        result.success == false
        result.appId == '7200'
        result.error.contains('empty verify body')
        result.note.contains('Do NOT retry')
        result.lastBackup != null
    }

    def "install_app returns success=false when verify endpoint returns unparseable HTML"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7300', data: '']
        }
        hubGet.register('/app/ajax/code') { params -> '<html><body>Login required</body></html>' }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "Test")', confirm: true])

        then:
        result.success == false
        result.appId == '7300'
        result.error.contains('unparseable verify body')
        result.note.contains('not valid JSON')
        result.note.contains('Do NOT retry')
        result.lastBackup != null
    }

    def "install_app rejects bulk-mode args (installs[] not supported on apps)"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallApp([installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Bulk mode")
        ex.message.contains("install_app")
    }

    def "install_driver (source mode) POSTs to /driver/save and returns the new driverId"() {
        given:
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedPath = path
            [status: 302, location: '/driver/editor/9001', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        postedPath == '/driver/save'
        result.success == true
        result.driverId == '9001'
        result.sourceMode == 'source'
    }

    def "install_driver (sourceFile mode) reads source from File Manager and installs it"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-driver.groovy' ? 'metadata { }'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/7777', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([sourceFile: 'my-driver.groovy', confirm: true])

        then:
        result.success == true
        result.driverId == '7777'
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    def "install_driver returns success=false when post-install verification shows an error state"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/8800', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Unknown identifier: xyz"}'
        }

        when:
        def result = script.toolInstallDriver([source: 'bad driver', confirm: true])

        then:
        result.success == false
        result.driverId == '8800'
        result.error.contains('Unknown identifier')
    }

    def "install_driver surfaces qualified success (verified:false, verifyError populated) when post-install verification fetch throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6600', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == true
        result.driverId == '6600'
        result.verified == false
        result.verifyError != null
        result.verifyError.contains('transient error')
        result.verifyError.contains('6600')
    }

    def "install_driver returns success=false when verify endpoint returns an empty body"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6700', data: '']
        }
        hubGet.register('/driver/ajax/code') { params -> '' }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == false
        result.driverId == '6700'
        result.error.contains('empty verify body')
        result.note.contains('Do NOT retry')
    }

    def "install_driver returns success=false when verify endpoint returns unparseable HTML"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6800', data: '']
        }
        hubGet.register('/driver/ajax/code') { params -> '<html>not json</html>' }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == false
        result.driverId == '6800'
        result.error.contains('unparseable verify body')
        result.note.contains('Do NOT retry')
    }

    def "install_driver throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "install_driver throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallDriver([source: 'metadata { }'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "install_driver throws when neither source nor sourceFile is provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallDriver([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    def "install_driver throws when both source and sourceFile are provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallDriver([source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
    }

    def "install_driver throws when sourceFile is not found in File Manager"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallDriver([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    def "install_driver returns success=false when Location header is absent (hub did not persist item)"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: 'ok']
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == false
        result.driverId == null
        result.error.contains('no item ID')
    }

    def "install_driver reports failure when the hub POST throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            throw new RuntimeException('compile error: syntax problem')
        }

        when:
        def result = script.toolInstallDriver([source: 'bad', confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('compile error')
        result.note.contains('syntax errors')
    }

    // -------- install_driver bulk mode --------

    def "install_driver bulk mode throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallDriver([installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "install_driver bulk mode throws when both installs and sourceFile are supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallDriver([sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bulk mode')
        ex.message.contains('source')
    }

    def "install_driver bulk mode throws when installs is an empty array"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallDriver([installs: [], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be empty')
    }

    def "install_driver bulk mode happy path: 3 drivers all succeed, per-item driverIds returned"() {
        given:
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${9000 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [
                [sourceFile: 'driver-a.groovy'],
                [sourceFile: 'driver-b.groovy'],
                [sourceFile: 'driver-c.groovy']
            ],
            confirm: true
        ])

        then: 'top-level result indicates overall success'
        result.success == true
        result.message.contains('3')
        result.installs.size() == 3
        result.installs.every { it.success == true }
        result.installs*.driverId == ['9001', '9002', '9003']
        result.installs.every { it.sourceMode == 'sourceFile' }
    }

    def "install_driver bulk mode partial failure: middle item missing file, outer two installed"() {
        given:
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
            // driver-b.groovy is missing -- simulate absent file
            fileName == 'driver-b.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${8000 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [
                [sourceFile: 'driver-a.groovy'],
                [sourceFile: 'driver-b.groovy'],
                [sourceFile: 'driver-c.groovy']
            ],
            confirm: true
        ])

        then: 'top-level success=false because not all items succeeded'
        result.success == false
        result.message.contains('2 of 3')

        and: 'per-item results carry correct status'
        result.installs[0].success == true
        result.installs[0].driverId == '8001'
        result.installs[1].success == false
        result.installs[1].driverId == null
        result.installs[1].error.contains('not found in File Manager')
        result.installs[2].success == true
        result.installs[2].driverId == '8002'
    }

    def "install_driver bulk mode: inline source items work alongside sourceFile items"() {
        given:
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
            'metadata { }'.getBytes('UTF-8')
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${8500 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [
                [source: 'metadata { name "inline-1" }'],
                [sourceFile: 'driver-b.groovy'],
                [source: 'metadata { name "inline-3" }']
            ],
            confirm: true
        ])

        then:
        result.success == true
        result.installs.size() == 3
        result.installs[0].sourceMode == 'source'
        result.installs[1].sourceMode == 'sourceFile'
        result.installs[2].sourceMode == 'source'
    }

    def "install_driver bulk mode: single-element installs array works"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/8900', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [[source: 'metadata { }']],
            confirm: true
        ])

        then:
        result.success == true
        result.installs.size() == 1
        result.installs[0].driverId == '8900'
    }

    def "install_driver bulk mode: malformed entry (non-Map) yields per-item failure with index hint"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/9100', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [
                [source: 'metadata { }'],
                'not-a-map',
                [:]
            ],
            confirm: true
        ])

        then:
        result.success == false
        result.installs.size() == 3
        result.installs[0].success == true
        result.installs[1].success == false
        result.installs[1].error.contains("'source' or 'sourceFile'")
        result.installs[2].success == false
        result.installs[2].error.contains("'source' or 'sourceFile'")
    }

    def "install_driver bulk mode: per-item verify-throw surfaces verified=false and verifyError on the success entry"() {
        given:
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${9300 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            // middle item's verify GET throws, outer two return clean JSON
            if (params?.id?.toString() == '9302') {
                throw new RuntimeException('hub blip on verify')
            }
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([
            installs: [
                [source: 'metadata { name "a" }'],
                [source: 'metadata { name "b" }'],
                [source: 'metadata { name "c" }']
            ],
            confirm: true
        ])

        then: 'all three installs return success=true (POST succeeded for each)'
        result.success == true
        result.installs.size() == 3
        result.installs.every { it.success == true }

        and: 'middle item carries verified=false plus verifyError; outer two are verified=true'
        result.installs[0].verified == true
        result.installs[0].verifyError == null
        result.installs[1].verified == false
        result.installs[1].verifyError != null
        result.installs[1].verifyError.contains('hub blip')
        result.installs[2].verified == true
        result.installs[2].verifyError == null
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

        and: 'the pre-edit backup manifest entry is preserved so the user can still restore_item_backup to roll back the update'
        atomicStateMap.itemBackupManifest?.containsKey('app_50')
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

    def "update_app_code rejects bulk-mode args (updates[] not supported on apps)"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateAppCode([
            updates: [[appId: '1', source: 'a'], [appId: '2', source: 'b']],
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Bulk mode")
        ex.message.contains("update_app_code")
    }

    def "update_driver_code (single mode) delegates to toolUpdateItemCode with the driver paths"() {
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

    // -------- update_driver_code bulk mode --------

    def "update_driver_code bulk mode throws when Hub Admin Write is disabled"() {
        when:
        script.toolUpdateDriverCode([updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "update_driver_code bulk mode throws when both updates and driverId are supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateDriverCode([driverId: '10', updates: [[driverId: '20', sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bulk mode')
        ex.message.contains('driverId')
    }

    def "update_driver_code bulk mode throws when updates is an empty array"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateDriverCode([updates: [], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be empty')
    }

    def "update_driver_code bulk mode happy path: 3 drivers all succeed"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        def updatePaths = []
        def updateIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            updatePaths << path
            updateIds << body.id?.toString()
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '101', sourceFile: 'driver-101.groovy'],
                [driverId: '102', sourceFile: 'driver-102.groovy'],
                [driverId: '103', sourceFile: 'driver-103.groovy']
            ],
            confirm: true
        ])

        then: 'top-level result indicates overall success'
        result.success == true
        result.message.contains('3')
        result.updates.size() == 3
        result.updates.every { it.success == true }
        result.updates*.driverId == ['101', '102', '103']

        and: 'each driver was independently posted to the update path'
        updatePaths.every { it == '/driver/ajax/update' }
        updateIds as Set == ['101', '102', '103'] as Set
    }

    def "update_driver_code bulk mode partial failure: middle driver fails, outer two still applied"() {
        given:
        enableHubAdminWrite()
        def callCount = 0
        hubGet.register('/driver/ajax/code') { params ->
            callCount++
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            // driver-202.groovy is the middle item -- simulate it missing
            fileName == 'driver-202.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        def postedIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedIds << body.id?.toString()
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '201', sourceFile: 'driver-201.groovy'],
                [driverId: '202', sourceFile: 'driver-202.groovy'],
                [driverId: '203', sourceFile: 'driver-203.groovy']
            ],
            confirm: true
        ])

        then: 'top-level success=false because not all items succeeded'
        result.success == false
        result.message.contains('2 of 3')

        and: 'per-item results carry correct status'
        result.updates[0].success == true
        result.updates[0].driverId == '201'
        result.updates[1].success == false
        result.updates[1].driverId == '202'
        result.updates[1].error.contains('not found in File Manager')
        result.updates[2].success == true
        result.updates[2].driverId == '203'

        and: 'drivers 201 and 203 were actually posted; driver 202 was not'
        postedIds as Set == ['201', '203'] as Set
    }

    def "update_driver_code bulk mode: mixed resave and sourceFile entries dispatch correctly"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            'metadata { name "from-file" }'.getBytes('UTF-8')
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { name \\"existing\\" }"}'
        }
        def postedSources = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedSources << body.source
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '301', resave: true],
                [driverId: '302', sourceFile: 'driver-302.groovy'],
                [driverId: '303', source: 'metadata { name "inline" }']
            ],
            confirm: true
        ])

        then:
        result.success == true
        result.updates.size() == 3
        result.updates[0].sourceMode == 'resave'
        result.updates[1].sourceMode == 'sourceFile'
        result.updates[2].sourceMode == 'source'

        and: 'each item posted with the correct source for its mode'
        postedSources.size() == 3
        postedSources[0].contains('existing')   // resave fetched from hub
        postedSources[1].contains('from-file')  // sourceFile read from File Manager
        postedSources[2].contains('inline')     // source passed inline
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
        atomicStateMap.itemBackupManifest = [
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
        atomicStateMap.itemBackupManifest = [
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

        and: 'both the pre-restore entry and the original backup entry are preserved (original kept so the user can restore again if needed)'
        atomicStateMap.itemBackupManifest.containsKey('prerestore_app_99')
        atomicStateMap.itemBackupManifest.containsKey('app_99')
    }

    def "restore_item_backup reports failure and preserves the backup when the hub POST fails"() {
        given:
        enableHubAdminWrite()
        atomicStateMap.itemBackupManifest = [
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
        atomicStateMap.itemBackupManifest.containsKey('driver_88')
    }
}
