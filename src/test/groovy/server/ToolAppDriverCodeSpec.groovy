package server

import spock.lang.Shared
import support.TestChildApp
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
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false.
 */
class ToolAppDriverCodeSpec extends ToolSpecBase {

    // Stubbed self-app so the self-update guard has an app.id to compare against.
    // appId=='1' triggers the guard; anything else bypasses it.
    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
    }

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

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when confirm is not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "X")'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "install_app throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallApp([source: 'definition(name: "X")', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when Hub Admin Write disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "X")', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Write')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_app', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_app', [source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not both')

        where:
        useGateways << [true, false]
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
            '{"status": "ok", "source": "stub-app-source", "version": 1}'
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

    @spock.lang.Unroll
    def "install_app via dispatch (source mode) POSTs and extracts appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/4242', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-app-source", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "Hello")', confirm: true])

        then:
        captured.path == '/app/save'
        captured.body.source == 'definition(name: "Hello")'
        captured.body.id == ''
        captured.body.create == ''
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '4242'
        inner.sourceMode == 'source'
        inner.message.contains('installed')

        where:
        useGateways << [true, false]
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
            '{"status": "ok", "source": "stub-app-source-from-file", "version": 1}'
        }

        when:
        def result = script.toolInstallApp([sourceFile: 'my-app.groovy', confirm: true])

        then:
        result.success == true
        result.appId == '5555'
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    @spock.lang.Unroll
    def "install_app via dispatch (sourceFile mode) reads source and installs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'definition(name: "FromFile")'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/5555', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-app-source-from-file", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_app', [sourceFile: 'my-app.groovy', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '5555'
        inner.sourceMode == 'sourceFile'
        inner.note.contains('File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when sourceFile is absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('install_app', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns success=false envelope when Location header is absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: 'ok']
        }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "NoLoc")', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == null
        inner.error.contains('no item ID')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns success=false envelope when post-install verification errors (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/9900', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Compilation failed: unexpected token"}'
        }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'bad source', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == '9900'
        inner.error.contains('Compilation failed')
        inner.note.contains('9900')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            throw new RuntimeException('compile error: bad syntax')
        }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'bad', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('compile error')
        inner.note.contains('syntax errors')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch surfaces qualified success envelope when verify fetch throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7100', data: '']
        }
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "Test")', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '7100'
        inner.verified == false
        inner.verifyError != null
        inner.verifyError.contains('transient error')
        inner.verifyError.contains('7100')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns success=false envelope when verify endpoint returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7200', data: '']
        }
        hubGet.register('/app/ajax/code') { params -> '' }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "Test")', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == '7200'
        inner.error.contains('empty verify body')
        inner.note.contains('Do NOT retry')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns success=false envelope when verify returns unparseable HTML (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7300', data: '']
        }
        hubGet.register('/app/ajax/code') { params -> '<html><body>Login required</body></html>' }

        when:
        def response = mcpDriver.callTool('install_app', [source: 'definition(name: "Test")', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == '7300'
        inner.error.contains('unparseable verify body')
        inner.note.contains('not valid JSON')
        inner.note.contains('Do NOT retry')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_app via dispatch returns -32602 envelope when bulk-mode installs[] (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_app', [installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Bulk mode')
        response.error.message.contains('install_app')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch (source mode) POSTs to /driver/save and returns driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        postedPath == '/driver/save'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '9001'
        inner.sourceMode == 'source'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch (sourceFile mode) reads source and installs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('install_driver', [sourceFile: 'my-driver.groovy', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '7777'
        inner.sourceMode == 'sourceFile'
        inner.note.contains('File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns success=false envelope when verify shows error state (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/8800', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Unknown identifier: xyz"}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'bad driver', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.driverId == '8800'
        inner.error.contains('Unknown identifier')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch surfaces qualified success envelope when verify fetch throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6600', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '6600'
        inner.verified == false
        inner.verifyError != null
        inner.verifyError.contains('transient error')
        inner.verifyError.contains('6600')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns success=false envelope when verify returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6700', data: '']
        }
        hubGet.register('/driver/ajax/code') { params -> '' }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.driverId == '6700'
        inner.error.contains('empty verify body')
        inner.note.contains('Do NOT retry')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns success=false envelope when verify returns unparseable HTML (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/6800', data: '']
        }
        hubGet.register('/driver/ajax/code') { params -> '<html>not json</html>' }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.driverId == '6800'
        inner.error.contains('unparseable verify body')
        inner.note.contains('Do NOT retry')

        where:
        useGateways << [true, false]
    }

    def "install_driver throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    @spock.lang.Unroll
    def "install_driver via dispatch returns -32602 envelope when Hub Admin Write disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Write')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns -32602 envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_driver', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns -32602 envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not both')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns -32602 envelope when sourceFile not found in File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('install_driver', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch returns success=false envelope when Location header absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: 'ok']
        }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.driverId == null
        inner.error.contains('no item ID')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            throw new RuntimeException('compile error: syntax problem')
        }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'bad', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('compile error')
        inner.note.contains('syntax errors')

        where:
        useGateways << [true, false]
    }

    // -------- install_driver bulk mode --------

    def "install_driver bulk mode throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallDriver([installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    @spock.lang.Unroll
    def "install_driver bulk via dispatch returns -32602 envelope when Hub Admin Write disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('install_driver', [installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Write')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch returns -32602 envelope when both installs and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_driver', [sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('bulk mode')
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch returns -32602 envelope when installs is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('install_driver', [installs: [], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('must not be empty')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch happy path 3 drivers all succeed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [sourceFile: 'driver-a.groovy'],
                [sourceFile: 'driver-b.groovy'],
                [sourceFile: 'driver-c.groovy']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.message.contains('3')
        inner.installs.size() == 3
        inner.installs.every { it.success == true }
        inner.installs*.driverId == ['9001', '9002', '9003']
        inner.installs.every { it.sourceMode == 'sourceFile' }

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch partial failure middle item missing file (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
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
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [sourceFile: 'driver-a.groovy'],
                [sourceFile: 'driver-b.groovy'],
                [sourceFile: 'driver-c.groovy']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.message.contains('2 of 3')
        inner.installs[0].success == true
        inner.installs[0].driverId == '8001'
        inner.installs[1].success == false
        inner.installs[1].driverId == null
        inner.installs[1].error.contains('not found in File Manager')
        inner.installs[2].success == true
        inner.installs[2].driverId == '8002'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch inline source items work alongside sourceFile items (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${8500 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [source: 'metadata { name "inline-1" }'],
                [sourceFile: 'driver-b.groovy'],
                [source: 'metadata { name "inline-3" }']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.installs.size() == 3
        inner.installs[0].sourceMode == 'source'
        inner.installs[1].sourceMode == 'sourceFile'
        inner.installs[2].sourceMode == 'source'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch single-element installs array works (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/8900', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [
            installs: [[source: 'metadata { }']],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.installs.size() == 1
        inner.installs[0].driverId == '8900'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch malformed entry yields per-item failure (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/9100', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [source: 'metadata { }'],
                'not-a-map',
                [:]
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.installs.size() == 3
        inner.installs[0].success == true
        inner.installs[1].success == false
        inner.installs[1].error.contains("'source' or 'sourceFile'")
        inner.installs[2].success == false
        inner.installs[2].error.contains("'source' or 'sourceFile'")

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "install_driver bulk via dispatch per-item verify-throw surfaces verifyError on success entry (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${9300 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            if (params?.id?.toString() == '9302') {
                throw new RuntimeException('hub blip on verify')
            }
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [source: 'metadata { name "a" }'],
                [source: 'metadata { name "b" }'],
                [source: 'metadata { name "c" }']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.installs.size() == 3
        inner.installs.every { it.success == true }
        inner.installs[0].verified == true
        inner.installs[0].verifyError == null
        inner.installs[1].verified == false
        inner.installs[1].verifyError != null
        inner.installs[1].verifyError.contains('hub blip')
        inner.installs[2].verified == true
        inner.installs[2].verifyError == null

        where:
        useGateways << [true, false]
    }

    def "install_driver bulk mode: per-item verify status=error propagates error, note, and lastBackup on the failed entry"() {
        given:
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${9400 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            // middle item's verify returns status=error (compile failure surfaced post-install)
            if (params?.id?.toString() == '9402') {
                '{"status": "error", "errorMessage": "compile failed: bad syntax"}'
            } else {
                '{"status": "ok", "source": "metadata { }", "version": 1}'
            }
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

        then: 'overall partial: 2 of 3 succeeded'
        result.success == false
        result.installs.size() == 3
        result.installs[0].success == true
        result.installs[1].success == false
        result.installs[2].success == true

        and: 'failed entry propagates error, note (anti-retry), and lastBackup from the inner failure return'
        result.installs[1].error != null
        result.installs[1].error.toLowerCase().contains('compile failed')
        result.installs[1].note != null
        result.installs[1].note.contains('compilation issues')
        result.installs[1].lastBackup != null
    }

    @spock.lang.Unroll
    def "install_driver bulk via dispatch per-item verify status=error propagates note and lastBackup (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def callNum = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            callNum++
            [status: 302, location: "/driver/editor/${9400 + callNum}", data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            if (params?.id?.toString() == '9402') {
                '{"status": "error", "errorMessage": "compile failed: bad syntax"}'
            } else {
                '{"status": "ok", "source": "metadata { }", "version": 1}'
            }
        }

        when:
        def response = mcpDriver.callTool('install_driver', [
            installs: [
                [source: 'metadata { name "a" }'],
                [source: 'metadata { name "b" }'],
                [source: 'metadata { name "c" }']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.installs.size() == 3
        inner.installs[0].success == true
        inner.installs[1].success == false
        inner.installs[2].success == true
        inner.installs[1].error != null
        inner.installs[1].error.toLowerCase().contains('compile failed')
        inner.installs[1].note != null
        inner.installs[1].note.contains('compilation issues')
        inner.installs[1].lastBackup != null

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_app_code', [appId: '1', source: 'x'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch returns -32602 envelope when appId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_app_code', [source: 'x', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('appId is required')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch returns -32602 envelope when none of source/sourceFile/resave supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_app_code', [appId: '1', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains("'source'")
        response.error.message.contains("'sourceFile'")
        response.error.message.contains("'resave'")

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch (source mode) backs up and POSTs to /app/ajax/update (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 12, "source": "old source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def response = mcpDriver.callTool('update_app_code', [appId: '50', source: 'new source', confirm: true])

        then:
        captured.path == '/app/ajax/update'
        captured.body.id == '50'
        captured.body.version == 12
        captured.body.source == 'new source'
        uploads.size() == 1
        uploads[0] == 'mcp-backup-app-50.groovy'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'source'
        inner.sourceLength == 'new source'.length()
        inner.appId == '50'
        inner.previousVersion == 12
        atomicStateMap.itemBackupManifest?.containsKey('app_50')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch (sourceFile mode) reads source from File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_app_code', [appId: '60', sourceFile: 'my-app.groovy', confirm: true])

        then:
        captured.body.source == 'source from file'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'sourceFile'
        inner.note.contains('File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch returns -32602 envelope when sourceFile absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('update_app_code', [appId: '60', sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch (resave mode) fetches current source and re-saves it (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_app_code', [appId: '70', resave: true, confirm: true])

        then:
        captured.body.source == 'current source'
        captured.body.version == 7
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'resave'
        inner.note.contains('no cloud round-trip')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch reports failure envelope when hub response status=error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "error", "errorMessage": "Compilation failed"}']
        }

        when:
        def response = mcpDriver.callTool('update_app_code', [appId: '80', source: 'broken', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Compilation failed')
        inner.note.contains('syntax')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_app_code via dispatch returns -32602 envelope when bulk-mode updates[] (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_app_code', [
            updates: [[appId: '1', source: 'a'], [appId: '2', source: 'b']],
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Bulk mode')
        response.error.message.contains('update_app_code')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code via dispatch (single mode) delegates with driver paths (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_driver_code', [driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        captured.path == '/driver/ajax/update'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '55'
        inner.sourceMode == 'source'

        where:
        useGateways << [true, false]
    }

    // -------- update_driver_code bulk mode --------

    def "update_driver_code bulk mode throws when Hub Admin Write is disabled"() {
        when:
        script.toolUpdateDriverCode([updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch returns -32602 envelope when Hub Admin Write disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('update_driver_code', [updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Write')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch returns -32602 envelope when both updates and driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_driver_code', [driverId: '10', updates: [[driverId: '20', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('bulk mode')
        response.error.message.contains('driverId')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch returns -32602 envelope when updates is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('update_driver_code', [updates: [], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('must not be empty')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch happy path 3 drivers all succeed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_driver_code', [
            updates: [
                [driverId: '101', sourceFile: 'driver-101.groovy'],
                [driverId: '102', sourceFile: 'driver-102.groovy'],
                [driverId: '103', sourceFile: 'driver-103.groovy']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.message.contains('3')
        inner.updates.size() == 3
        inner.updates.every { it.success == true }
        inner.updates*.driverId == ['101', '102', '103']
        updatePaths.every { it == '/driver/ajax/update' }
        updateIds as Set == ['101', '102', '103'] as Set

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch partial failure middle driver fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'driver-202.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        def postedIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedIds << body.id?.toString()
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def response = mcpDriver.callTool('update_driver_code', [
            updates: [
                [driverId: '201', sourceFile: 'driver-201.groovy'],
                [driverId: '202', sourceFile: 'driver-202.groovy'],
                [driverId: '203', sourceFile: 'driver-203.groovy']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.message.contains('2 of 3')
        inner.updates[0].success == true
        inner.updates[0].driverId == '201'
        inner.updates[1].success == false
        inner.updates[1].driverId == '202'
        inner.updates[1].error.contains('not found in File Manager')
        inner.updates[2].success == true
        inner.updates[2].driverId == '203'
        postedIds as Set == ['201', '203'] as Set

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch mixed resave and sourceFile entries dispatch correctly (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('update_driver_code', [
            updates: [
                [driverId: '301', resave: true],
                [driverId: '302', sourceFile: 'driver-302.groovy'],
                [driverId: '303', source: 'metadata { name "inline" }']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.updates.size() == 3
        inner.updates[0].sourceMode == 'resave'
        inner.updates[1].sourceMode == 'sourceFile'
        inner.updates[2].sourceMode == 'source'
        postedSources.size() == 3
        postedSources[0].contains('existing')
        postedSources[1].contains('from-file')
        postedSources[2].contains('inline')

        where:
        useGateways << [true, false]
    }

    def "update_driver_code bulk mode: per-item update failure propagates note and lastBackup on the failed entry"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        // Middle item's hub POST returns status=failure body — reaches toolUpdateItemCodeInner's
        // else branch (now carrying note + lastBackup after this PR's symmetry fix).
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            if (body.id == '402') {
                [status: 200, location: null, data: '{"status": "failure", "errorMessage": "version conflict"}']
            } else {
                [status: 200, location: null, data: '{"status": "success"}']
            }
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '401', source: 'metadata { name "a" }'],
                [driverId: '402', source: 'metadata { name "b" }'],
                [driverId: '403', source: 'metadata { name "c" }']
            ],
            confirm: true
        ])

        then: 'overall partial: 2 of 3 succeeded'
        result.success == false
        result.updates.size() == 3
        result.updates[0].success == true
        result.updates[1].success == false
        result.updates[2].success == true

        and: 'failed entry propagates error, note (anti-retry), and lastBackup from the inner failure return'
        result.updates[1].error != null
        result.updates[1].error.toLowerCase().contains('version conflict')
        result.updates[1].note != null
        result.updates[1].note.contains('compilation issues')
        result.updates[1].lastBackup != null
    }

    @spock.lang.Unroll
    def "update_driver_code bulk via dispatch per-item failure propagates note and lastBackup (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            if (body.id == '402') {
                [status: 200, location: null, data: '{"status": "failure", "errorMessage": "version conflict"}']
            } else {
                [status: 200, location: null, data: '{"status": "success"}']
            }
        }

        when:
        def response = mcpDriver.callTool('update_driver_code', [
            updates: [
                [driverId: '401', source: 'metadata { name "a" }'],
                [driverId: '402', source: 'metadata { name "b" }'],
                [driverId: '403', source: 'metadata { name "c" }']
            ],
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.updates.size() == 3
        inner.updates[0].success == true
        inner.updates[1].success == false
        inner.updates[2].success == true
        inner.updates[1].error != null
        inner.updates[1].error.toLowerCase().contains('version conflict')
        inner.updates[1].note != null
        inner.updates[1].note.contains('compilation issues')
        inner.updates[1].lastBackup != null

        where:
        useGateways << [true, false]
    }

    def "install_driver returns success=false when verify endpoint returns clean JSON with empty source field"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/4444', data: '']
        }
        // Verify response parses cleanly but reports empty source — driver slot created but
        // no body persisted. Distinct from status:"error" and from empty/unparseable body.
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == false
        result.error.toLowerCase().contains('installation failed')
        result.driverId == '4444'
        result.note.contains('compilation issues')
        result.lastBackup != null
    }

    @spock.lang.Unroll
    def "install_driver via dispatch returns success=false envelope when verify clean JSON has empty source (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 302, location: '/driver/editor/4444', data: '']
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('install_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.toLowerCase().contains('installation failed')
        inner.driverId == '4444'
        inner.note.contains('compilation issues')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_app via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('delete_app', [appId: '1'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "delete_app throws when Hub Admin Write is disabled"() {
        when:
        script.toolDeleteApp([appId: '1', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    @spock.lang.Unroll
    def "delete_app via dispatch returns -32602 envelope when Hub Admin Write disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('delete_app', [appId: '1', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Hub Admin Write')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_app via dispatch returns -32602 envelope when appId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('delete_app', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('appId is required')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_app via dispatch backs up source and deletes via deleteJsonSafe (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 2, "source": "code body"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/33') { params -> '{"status": "true"}' }

        when:
        def response = mcpDriver.callTool('delete_app', [appId: '33', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '33'
        inner.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        inner.restoreHint.contains('install_app')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_app via dispatch proceeds with backupWarning when pre-delete backup fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('source fetch failed')
        }
        hubGet.register('/app/edit/deleteJsonSafe/44') { params -> '{"status": "true"}' }

        when:
        def response = mcpDriver.callTool('delete_app', [appId: '44', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.message.contains('backup failed')
        inner.backupWarning.contains('permanently lost')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_app via dispatch reports failure envelope when hub delete signals error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "x"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/55') { params ->
            '{"status": "error", "errorMessage": "in use"}'
        }

        when:
        def response = mcpDriver.callTool('delete_app', [appId: '55', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Delete may have failed')
        inner.appId == '55'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "delete_driver via dispatch hits /driver/editor/deleteJson and succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('delete_driver', [driverId: '77', confirm: true])

        then:
        deletePath == '/driver/editor/deleteJson/77'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '77'
        inner.backupFile == 'mcp-backup-driver-77.groovy'

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "restore_item_backup via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('restore_item_backup', [backupKey: 'app_1'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "restore_item_backup via dispatch returns -32602 envelope when backupKey missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()

        when:
        def response = mcpDriver.callTool('restore_item_backup', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('backupKey is required')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "restore_item_backup via dispatch returns success=false envelope when key unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        atomicStateMap.itemBackupManifest = [
            'app_existing': [type: 'app', id: '1', fileName: 'f.groovy',
                             version: 1, timestamp: 1L, sourceLength: 0]
        ]

        when:
        def response = mcpDriver.callTool('restore_item_backup', [backupKey: 'app_missing', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('app_missing')
        inner.availableBackups.contains('app_existing')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "restore_item_backup via dispatch reads backup creates pre-restore copy and pushes to hub (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        atomicStateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-app-99.groovy' ? 'old source v4'.getBytes('UTF-8') : null
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def response = mcpDriver.callTool('restore_item_backup', [backupKey: 'app_99', confirm: true])

        then:
        uploads == ['mcp-prerestore-app-99.groovy']
        captured.path == '/app/ajax/update'
        captured.body.id == '99'
        captured.body.version == 9
        captured.body.source == 'old source v4'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.type == 'app'
        inner.id == '99'
        inner.restoredVersion == 4
        inner.preRestoreBackup == 'prerestore_app_99'
        inner.undoHint.contains('prerestore_app_99')
        atomicStateMap.itemBackupManifest.containsKey('prerestore_app_99')
        atomicStateMap.itemBackupManifest.containsKey('app_99')

        where:
        useGateways << [true, false]
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

    @spock.lang.Unroll
    def "restore_item_backup via dispatch reports failure envelope and preserves backup when hub POST fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
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
        def response = mcpDriver.callTool('restore_item_backup', [backupKey: 'driver_88', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('bad code')
        inner.message.contains('preserved')
        atomicStateMap.itemBackupManifest.containsKey('driver_88')

        where:
        useGateways << [true, false]
    }

    def "restore_item_backup returns clear error for library type and directs user to update_library_code"() {
        given:
        enableHubAdminWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1L, sourceLength: 200]
        ]

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'library_42', confirm: true])

        then:
        result.success == false
        result.error.contains('use install_library or update_library_code')
        result.backupFile == 'mcp-backup-library-42.groovy'
        result.type == 'library'
    }

    // -------- update_app_code: expectedVersion optimistic-lock --------

    def "update_app_code with matching expectedVersion proceeds and POSTs to /app/ajax/update"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 42, "source": "old source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new source', expectedVersion: 42, confirm: true])

        then:
        captured.path == '/app/ajax/update'
        captured.body.version == 42
        result.success == true
        result.previousVersion == 42
    }

    def "update_app_code with mismatching expectedVersion aborts BEFORE POST and returns conflict"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 50, "source": "current source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'stale', expectedVersion: 42, confirm: true])

        then: 'no hub-update POST happens'
        postCount == 0

        and: 'conflict result includes both versions and the conflict flag'
        result.success == false
        result.conflict == true
        result.expectedVersion == 42
        result.currentVersion == 50
        result.error.toLowerCase().contains('optimistic-lock conflict')
        result.appId == '50'

        and: 'backup IS taken even on conflict (intentional, 1h cache makes retry free)'
        uploads == ['mcp-backup-app-50.groovy']
    }

    def "update_app_code coerces a string expectedVersion to integer and detects mismatch (inequality-proves-coercion)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 7, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when: 'caller sends "8" (string) and hub is at 7'
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: '8', confirm: true])

        then: 'envelope carries Integer values (proves both sides coerced, not String compared)'
        postCount == 0
        result.conflict == true
        result.expectedVersion == 8
        result.expectedVersion.class == Integer
        result.currentVersion == 7
        result.currentVersion.class == Integer
    }

    def "update_app_code rejects unparseable expectedVersion with a clear message that preserves the original cause"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 'not-a-number', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion must be an integer')
        ex.message.contains('not-a-number')   // input value preserved
        ex.message.contains('For input string')   // original NumberFormatException cause preserved
    }

    def "update_app_code rejects explicit-null expectedVersion (silent-overwrite footgun)"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: null, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion was supplied as null')
    }

    def "update_app_code with expectedVersion:0 against hub version 0 succeeds (no Groovy truthy short-circuit)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 0, "source": "v0 source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'v1', expectedVersion: 0, confirm: true])

        then:
        result.success == true
        captured.body.version == 0
    }

    def "update_app_code with expectedVersion:0 against hub version 1 returns conflict (proves containsKey is checked, not truthiness)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "v1"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 0, confirm: true])

        then:
        postCount == 0
        result.conflict == true
        result.expectedVersion == 0
        result.currentVersion == 1
    }

    def "update_app_code with expectedVersion + resave fetches version from the resave parse (no second GET) and detects match/mismatch"() {
        given:
        enableHubAdminWrite()
        def getCount = 0
        hubGet.register('/app/ajax/code') { params ->
            getCount++
            '{"status": "ok", "version": 7, "source": "on-hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', resave: true, expectedVersion: 7, confirm: true])

        then: 'resave-fetch + backup-fetch only; no extra version GET'
        getCount == 2
        result.success == true
        captured.body.version == 7

        when:
        getCount = 0
        captured.clear()
        def conflict = script.toolUpdateAppCode([appId: '50', resave: true, expectedVersion: 99, confirm: true])

        then:
        captured.isEmpty()                    // no POST
        conflict.conflict == true
        conflict.expectedVersion == 99
        conflict.currentVersion == 7
    }

    def "update_app_code with hub returning non-integer version aborts with a clear error (no silent unguarded write)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": "v1.2.3", "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 1, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-integer current version')
        postCount == 0
    }

    def "update_app_code propagates POST failure with a clean envelope -- no conflict:true leakage on non-conflict failures"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "error", "errorMessage": "compile failed"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'broken', expectedVersion: 1, confirm: true])

        then: 'conflict keys must not leak into POST-failure shape'
        result.success == false
        result.error.contains('compile failed')
        !result.containsKey('conflict')
        !result.containsKey('expectedVersion')
        !result.containsKey('currentVersion')
    }

    // -------- update_driver_code expectedVersion --------

    def "update_driver_code single-mode expectedVersion conflict carries driverId (not appId)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "drv"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '77', source: 's', expectedVersion: 8, confirm: true])

        then:
        postCount == 0
        result.conflict == true
        result.driverId == '77'
        !result.containsKey('appId')
    }

    def "update_driver_code bulk mode propagates per-item expectedVersion conflicts without aborting siblings"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        and: 'three drivers: first at v10, second at v20 (conflict), third at v30'
        hubGet.register('/driver/ajax/code') { params ->
            def id = params.id?.toString()
            if (id == '101') return '{"status": "ok", "version": 10, "source": "a"}'
            if (id == '102') return '{"status": "ok", "version": 20, "source": "b"}'
            if (id == '103') return '{"status": "ok", "version": 30, "source": "c"}'
            null
        }

        and: 'every update POST that does happen returns success'
        def postedIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedIds << body.id
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when: 'caller asserts wrong version on the middle entry only'
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '101', source: 'new-a', expectedVersion: 10],
                [driverId: '102', source: 'new-b', expectedVersion: 99],   // hub is at v20 -> conflict
                [driverId: '103', source: 'new-c', expectedVersion: 30]
            ],
            confirm: true
        ])

        then: 'only the two non-conflicting drivers were POSTed'
        postedIds.sort() == ['101', '103']

        and: 'overall bulk reports partial; per-item carries conflict shape for the failed entry only'
        result.success == false
        result.updates[0].success == true
        result.updates[1].success == false
        result.updates[1].conflict == true
        result.updates[1].expectedVersion == 99
        result.updates[1].currentVersion == 20
        result.updates[2].success == true
    }

    def "update_driver_code rejects bulk + top-level expectedVersion (schema-vs-mutex consistency)"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateDriverCode([
            updates: [[driverId: '1', source: 'a']],
            expectedVersion: 5,
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion')
        ex.message.contains('updates[]')
    }

    def "update_driver_code bulk per-item explicit-null expectedVersion is rejected (same footgun as single-mode)"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            def id = params.id?.toString()
            if (id == '301') return '{"status": "ok", "version": 1, "source": "a"}'
            if (id == '302') return '{"status": "ok", "version": 2, "source": "b"}'
            null
        }
        def postedIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedIds << body.id
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '301', source: 'a', expectedVersion: null],
                [driverId: '302', source: 'b']
            ],
            confirm: true
        ])

        then: 'null entry fails per-item without bypassing the lock; sibling still applies'
        result.success == false
        result.updates[0].success == false
        result.updates[0].error.contains('expectedVersion was supplied as null')
        postedIds == ['302']
        result.updates[1].success == true
    }

    def "update_driver_code bulk mode mixes a per-item conflict with a per-item thrown error in the same batch"() {
        given:
        enableHubAdminWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            def id = params.id?.toString()
            if (id == '201') return '{"status": "ok", "version": 5, "source": "a"}'   // conflict (caller will say 99)
            if (id == '203') return '{"status": "ok", "version": 8, "source": "c"}'   // success
            null
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when: 'item 0 conflicts, item 1 has no driverId (throws), item 2 succeeds'
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '201', source: 'a', expectedVersion: 99],
                [source: 'no-driverId-here'],
                [driverId: '203', source: 'c']
            ],
            confirm: true
        ])

        then:
        result.success == false
        result.updates.size() == 3
        result.updates[0].conflict == true
        result.updates[0].expectedVersion == 99
        result.updates[0].currentVersion == 5
        result.updates[1].success == false
        result.updates[1].error.contains("'driverId'")
        !result.updates[1].containsKey('conflict')   // non-conflict failure stays clean
        result.updates[2].success == true
    }

    def "update_app_code accepts Long expectedVersion (common JSON parser output)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when: 'caller passes a Long (e.g. parser produced Long for an integer JSON literal)'
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 5L, confirm: true])

        then:
        result.success == true
        captured.body.version == 5
    }

    def "update_app_code rejects non-coercible Map expectedVersion (exercises ClassCastException leg of the union catch)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: [bad: 1], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion must be an integer')
        ex.message.contains('Cannot coerce a map')   // ClassCastException cause preserved
    }

    // -------- update_app_code: self-update guard --------

    def "update_app_code refuses self-update on the MCP server's own appId when Developer Mode is OFF"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        def warnLogs = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            if (level == 'warn' && component == 'hub-admin') warnLogs << msg
        }

        when:
        script.toolUpdateAppCode([appId: '1', source: 'self-overwrite', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('self-update')
        ex.message.contains('Developer Mode')
        ex.message.contains('appId=1')

        and: 'audit log records the blocked attempt'
        warnLogs.any { it.contains('BLOCKED') && it.contains('id=1') }
    }

    def "update_app_code allows self-update on the MCP server's own appId when Developer Mode is ON and audit-logs it"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }
        def warnLogs = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            if (level == 'warn' && component == 'hub-admin') warnLogs << msg
        }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v2', confirm: true])

        then:
        result.success == true
        captured.body.source == 'self-update v2'

        and: 'security-relevant ALLOWED audit log fires'
        warnLogs.any { it.contains('ALLOWED') && it.contains('id=1') }
    }

    def "update_app_code self-update guard blocks resave mode (most-likely real-world brick scenario)"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        def getCount = 0
        hubGet.register('/app/ajax/code') { params ->
            getCount++
            '{"status": "ok", "version": 1, "source": "self src"}'
        }

        when:
        script.toolUpdateAppCode([appId: '1', resave: true, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('self-update')

        and: 'guard fired before any I/O -- no fetch for resave source'
        getCount == 0
    }

    def "update_app_code self-update guard blocks sourceFile mode (no File Manager read happens)"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        def downloadCount = 0
        script.metaClass.downloadHubFile = { String fileName ->
            downloadCount++
            'attempted self update'.getBytes('UTF-8')
        }

        when:
        script.toolUpdateAppCode([appId: '1', sourceFile: 'self.groovy', confirm: true])

        then:
        thrown(IllegalArgumentException)
        downloadCount == 0   // guard runs before File Manager read
    }

    def "update_app_code self-update guard allows resave mode when Developer Mode is ON"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current self src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.body = body
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '1', resave: true, confirm: true])

        then:
        result.success == true
        captured.body.source == 'current self src'
    }

    def "update_app_code self-update guard does NOT fire on non-self appIds even when Developer Mode is OFF"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "other app"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '999', source: 'foreign update', confirm: true])

        then:
        result.success == true
    }

    def "update_driver_code self-update guard does NOT fire (keyed on type=='app'; driverId 1 is unrelated)"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "driver src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '1', source: 'driver v2', confirm: true])

        then:
        result.success == true
    }

    def "update_app_code self-update guard fails closed when app context is unavailable (refuses + ERROR-logs)"() {
        given:
        enableHubAdminWrite()
        def originalId = sharedAppStub.id
        sharedAppStub.id = null   // simulate the rare lifecycle window where app.id is unbound
        def errorLogs = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            if (level == 'error' && component == 'hub-admin') errorLogs << msg
        }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('app context is unavailable')
        ex.message.contains('Retry the call')
        errorLogs.any { it.contains('refusing') && it.contains('appId=50') }

        cleanup:
        sharedAppStub.id = originalId
    }

    def "update_driver_code bulk mode: a per-item driverId equal to app.id still succeeds (type=='app' guard does not fire on driver bulk)"() {
        given:
        enableHubAdminWrite()
        settingsMap.enableDeveloperMode = false
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "drv"}'
        }
        def postedIds = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postedIds << body.id
            [status: 200, location: null, data: '{"status": "success"}']
        }

        when: 'bulk update of two drivers, one of which has id==app.id'
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '1', source: 'a'],     // happens to match sharedAppStub.id
                [driverId: '2', source: 'b']
            ],
            confirm: true
        ])

        then: 'both proceed -- guard does not bleed across types'
        result.success == true
        postedIds.sort() == ['1', '2']
    }

    @spock.lang.Unroll
    def "restore_item_backup via dispatch returns success=false envelope for library type (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableHubAdminWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1L, sourceLength: 200]
        ]

        when:
        def response = mcpDriver.callTool('restore_item_backup', [backupKey: 'library_42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('use install_library or update_library_code')
        inner.backupFile == 'mcp-backup-library-42.groovy'
        inner.type == 'library'

        where:
        useGateways << [true, false]
    }
}
