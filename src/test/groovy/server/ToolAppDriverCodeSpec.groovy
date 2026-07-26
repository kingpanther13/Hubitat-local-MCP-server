package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the hub_manage_code gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolInstallApp          -> hub_create_app         (source|sourceFile; post-install verification)
 * - toolInstallDriver       -> hub_create_driver      (source|sourceFile; bulk installs[]; post-install verification)
 * - toolUpdateAppCode       -> hub_update_app     (three source modes: source, sourceFile, resave)
 * - toolUpdateDriverCode    -> hub_update_driver  (three source modes + bulk updates array)
 * - toolDeleteItem (type=app)    -> hub_delete_item
 * - toolDeleteItem (type=driver) -> hub_delete_item
 * - toolRestoreItemBackup   -> hub_restore_backup
 *
 * Every tool here runs through requireDestructiveConfirm (confirm + 24h backup)
 * and is gated centrally on the Write master in executeTool -- golden-path tests seed:
 *   settingsMap.enableWrite      = true
 *   stateMap.lastBackupTimestamp = 1234567890000L   (matches fixed now())
 *   args.confirm                 = true
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet       -- routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPostJson  -- create/update/restore POST helper (POST /app|driver/saveOrUpdateJson),
 *                            stubbed per-test on script.metaClass; takes (String path,
 *                            String body) and returns the PARSED response Map ({success, id, ...}),
 *                            null for an EMPTY body, or the [_unparseable: true, message: ...]
 *                            sentinel Map for a non-JSON body.
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

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolInstallApp / toolInstallDriver --------

    def "hub_create_app throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([source: 'definition(name: "X")'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when confirm is not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "X")'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_create_app', [source: 'definition(name: "X")', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "X")', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app throws when neither source nor sourceFile is provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app throws when both source and sourceFile are provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('exactly one')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app (source mode) POSTs to /app/saveOrUpdateJson, verifies via ajax/code, and extracts the new appId"() {
        given:
        enableWrite()
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 4242, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-app-source", "version": 1}'
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "Hello")', confirm: true])

        then:
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.source == 'definition(name: "Hello")'
        sent.id == null
        sent.version == 1
        result.success == true
        result.appId == '4242'
        result.sourceMode == 'source'
        result.message.contains('installed')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch (source mode) POSTs and extracts appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 4242, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-app-source", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "Hello")', confirm: true])

        then:
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.source == 'definition(name: "Hello")'
        sent.id == null
        sent.version == 1
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

    def "hub_create_app (sourceFile mode) reads source from File Manager and installs it"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'definition(name: "FromFile")'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 5555, version: 1]
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
    def "hub_create_app via dispatch (sourceFile mode) reads source and installs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'definition(name: "FromFile")'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 5555, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-app-source-from-file", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [sourceFile: 'my-app.groovy', confirm: true])

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

    def "hub_create_app (sourceFile mode) throws when the file is absent in File Manager"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallApp([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when sourceFile is absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_create_app', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app returns success=false when the hub rejects the create (success!=true)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def result = script.toolInstallApp([source: 'definition(name: "NoLoc")', confirm: true])

        then:
        result.success == false
        result.appId == null
        result.error.contains('installation failed')
        result.error.contains('NoLoc rejected')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns success=false envelope when the hub rejects the create (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "NoLoc")', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == null
        inner.error.contains('installation failed')
        inner.error.contains('NoLoc rejected')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app returns success=false when post-install verification shows an error state"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 9900, version: 1]
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
    def "hub_create_app via dispatch returns success=false envelope when post-install verification errors (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 9900, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Compilation failed: unexpected token"}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'bad source', confirm: true])

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

    def "hub_create_app reports failure when the hub POST throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
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
    def "hub_create_app via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            throw new RuntimeException('compile error: bad syntax')
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'bad', confirm: true])

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

    def "hub_create_app surfaces qualified success (verified:false, verifyError populated) when post-install verification fetch throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7100, version: 1]
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
    def "hub_create_app via dispatch surfaces qualified success envelope when verify fetch throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7100, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "Test")', confirm: true])

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

    def "hub_create_app returns success=false when verify endpoint returns an empty body"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7200, version: 1]
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
    def "hub_create_app via dispatch returns success=false envelope when verify endpoint returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7200, version: 1]
        }
        hubGet.register('/app/ajax/code') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "Test")', confirm: true])

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

    def "hub_create_app returns success=false when verify endpoint returns unparseable HTML"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7300, version: 1]
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
    def "hub_create_app via dispatch returns success=false envelope when verify returns unparseable HTML (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7300, version: 1]
        }
        hubGet.register('/app/ajax/code') { params -> '<html><body>Login required</body></html>' }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "Test")', confirm: true])

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

    def "hub_create_app rejects bulk-mode args (installs[] not supported on apps)"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Bulk mode")
        ex.message.contains("hub_create_app")
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns -32602 envelope when bulk-mode installs[] (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Bulk mode')
        response.error.message.contains('hub_create_app')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver (source mode) POSTs to /driver/saveOrUpdateJson and returns the new driverId"() {
        given:
        enableWrite()
        def postedPath = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedPath = path
            [success: true, id: 9001, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        postedPath == '/driver/saveOrUpdateJson'
        result.success == true
        result.driverId == '9001'
        result.sourceMode == 'source'
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch (source mode) POSTs to /driver/saveOrUpdateJson and returns driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def postedPath = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedPath = path
            [success: true, id: 9001, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        postedPath == '/driver/saveOrUpdateJson'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '9001'
        inner.sourceMode == 'source'

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver (sourceFile mode) reads source from File Manager and installs it"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-driver.groovy' ? 'metadata { }'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7777, version: 1]
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
    def "hub_create_driver via dispatch (sourceFile mode) reads source and installs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-driver.groovy' ? 'metadata { }'.getBytes('UTF-8') : null
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 7777, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [sourceFile: 'my-driver.groovy', confirm: true])

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

    def "hub_create_driver returns success=false when post-install verification shows an error state"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 8800, version: 1]
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
    def "hub_create_driver via dispatch returns success=false envelope when verify shows error state (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 8800, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "error", "errorMessage": "Unknown identifier: xyz"}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'bad driver', confirm: true])

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

    def "hub_create_driver surfaces qualified success (verified:false, verifyError populated) when post-install verification fetch throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6600, version: 1]
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
    def "hub_create_driver via dispatch surfaces qualified success envelope when verify fetch throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6600, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            throw new RuntimeException('transient error')
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

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

    def "hub_create_driver returns success=false when verify endpoint returns an empty body"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6700, version: 1]
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
    def "hub_create_driver via dispatch returns success=false envelope when verify returns empty body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6700, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

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

    def "hub_create_driver returns success=false when verify endpoint returns unparseable HTML"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6800, version: 1]
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
    def "hub_create_driver via dispatch returns success=false envelope when verify returns unparseable HTML (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 6800, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params -> '<html>not json</html>' }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

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

    def "hub_create_driver throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallDriver([source: 'metadata { }'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when neither source nor sourceFile is provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallDriver([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns -32602 envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when both source and sourceFile are provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallDriver([source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns -32602 envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('exactly one')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when sourceFile is not found in File Manager"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallDriver([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns -32602 envelope when sourceFile not found in File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver returns success=false when the hub rejects the create (success!=true)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def result = script.toolInstallDriver([source: 'metadata { }', confirm: true])

        then:
        result.success == false
        result.driverId == null
        result.error.contains('installation failed')
        result.error.contains('NoLoc rejected')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns success=false envelope when the hub rejects the create (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.driverId == null
        inner.error.contains('installation failed')
        inner.error.contains('NoLoc rejected')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver reports failure when the hub POST throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
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
    def "hub_create_driver via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            throw new RuntimeException('compile error: syntax problem')
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'bad', confirm: true])

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

    // -------- hub_create_driver bulk mode --------

    def "hub_create_driver bulk mode throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_create_driver', [installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_driver', [installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode throws when both installs and sourceFile are supplied"() {
        given:
        enableWrite()

        when:
        script.toolInstallDriver([sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bulk mode')
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch returns -32602 envelope when both installs and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('bulk mode')
        response.error.message.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode throws when installs is an empty array"() {
        given:
        enableWrite()

        when:
        script.toolInstallDriver([installs: [], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be empty')
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch returns -32602 envelope when installs is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [installs: [], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('must not be empty')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode happy path: 3 drivers all succeed, per-item driverIds returned"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9000 + callNum, version: 1]
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
    def "hub_create_driver bulk via dispatch happy path 3 drivers all succeed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9000 + callNum, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_create_driver bulk mode partial failure: middle item missing file, outer two installed"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
            // driver-b.groovy is missing -- simulate absent file
            fileName == 'driver-b.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 8000 + callNum, version: 1]
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
    def "hub_create_driver bulk via dispatch partial failure middle item missing file (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'driver-b.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 8000 + callNum, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_create_driver bulk mode: inline source items work alongside sourceFile items"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName ->
            'metadata { }'.getBytes('UTF-8')
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 8500 + callNum, version: 1]
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
    def "hub_create_driver bulk via dispatch inline source items work alongside sourceFile items (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 8500 + callNum, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_create_driver bulk mode: single-element installs array works"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 8900, version: 1]
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
    def "hub_create_driver bulk via dispatch single-element installs array works (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 8900, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_create_driver bulk mode: malformed entry (non-Map) yields per-item failure with index hint"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 9100, version: 1]
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
        result.installs[1].error.contains("'source', 'sourceFile', or 'importUrl'")
        result.installs[2].success == false
        result.installs[2].error.contains("'source', 'sourceFile', or 'importUrl'")
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch malformed entry yields per-item failure (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 9100, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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
        inner.installs[1].error.contains("'source', 'sourceFile', or 'importUrl'")
        inner.installs[2].success == false
        inner.installs[2].error.contains("'source', 'sourceFile', or 'importUrl'")

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode: per-item verify-throw surfaces verified=false and verifyError on the success entry"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9300 + callNum, version: 1]
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
    def "hub_create_driver bulk via dispatch per-item verify-throw surfaces verifyError on success entry (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9300 + callNum, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            if (params?.id?.toString() == '9302') {
                throw new RuntimeException('hub blip on verify')
            }
            '{"status": "ok", "source": "metadata { }", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_create_driver bulk mode: per-item verify status=error propagates error, note, and lastBackup on the failed entry"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9400 + callNum, version: 1]
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
    def "hub_create_driver bulk via dispatch per-item verify status=error propagates note and lastBackup (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            callNum++
            [success: true, id: 9400 + callNum, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            if (params?.id?.toString() == '9402') {
                '{"status": "error", "errorMessage": "compile failed: bad syntax"}'
            } else {
                '{"status": "ok", "source": "metadata { }", "version": 1}'
            }
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [
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

    def "hub_update_app throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([appId: '1', source: 'x'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', source: 'x'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app throws when appId is missing"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([source: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope when appId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [source: 'x', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('appId is required')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app rejects a non-numeric appId before any I/O"() {
        given:
        enableWrite()
        def getCalls = 0
        hubGet.register('/app/ajax/code') { params ->
            getCalls++
            '{"status": "ok", "version": 1, "source": "x"}'
        }
        def postCalls = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCalls++
            [success: true]
        }

        when:
        script.toolUpdateAppCode([appId: 'abc', source: 'code', confirm: true])

        then: 'a bad id is caller-recoverable: IllegalArgumentException, not a runtime envelope from the POST'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must be an integer')
        ex.message.contains('abc')

        and: 'validation fired before any I/O -- no backup fetch, no save POST'
        getCalls == 0
        postCalls == 0
    }

    def "hub_update_app throws when none of source, sourceFile, or resave are supplied"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([appId: '1', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'source'")
        ex.message.contains("'sourceFile'")
        ex.message.contains("'resave'")
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope when none of source/sourceFile/resave supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains("'source'")
        response.error.message.contains("'sourceFile'")
        response.error.message.contains("'resave'")

        where:
        useGateways << [true, false]
    }

    def "hub_update_app (source mode) backs up, fetches version, POSTs to /app/saveOrUpdateJson"() {
        given:
        enableWrite()

        and: 'backupItemSource fetches source + uploads backup; update then fetches version again'
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 12, "source": "old source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }

        and: 'hubInternalPostJson returns a success response'
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 50]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new source', confirm: true])

        then: 'update path and body match the hub contract'
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.id == 50
        sent.version == 12
        sent.source == 'new source'

        and: 'pre-edit backup written to File Manager'
        uploads.size() == 1
        uploads[0] == 'mcp-backup-app-50.groovy'

        and: 'result reports success with sourceMode=source'
        result.success == true
        result.sourceMode == 'source'
        result.sourceLength == 'new source'.length()
        result.appId == '50'
        result.previousVersion == 12

        and: 'the pre-edit backup manifest entry is preserved so the user can still hub_restore_backup to roll back the update'
        atomicStateMap.itemBackupManifest?.containsKey('app_50')
    }

    // -------- hub_update_app OAuth fold (#259): enable/configure OAuth on an app code definition --------

    def "hub_update_app oauth-only enables OAuth and returns the (hub-generated) creds with no source change"() {
        given:
        enableWrite()
        and: 'fresh app: detail read shows no current creds, so the hub generates and returns them'
        hubGet.register('/app/list/single/data/42') { params -> '[{"id":42,"oauthClientId":null,"oauthClientSecret":null}]' }
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=&clientSecret=&refreshSecret=false') { params ->
            '{"success":true,"clientId":"gen-id","clientSecret":"gen-secret"}'
        }
        def posted = null
        script.metaClass.hubInternalPostJson = { String path, String body -> posted = path; [success: true] }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true], confirm: true])

        then:
        result.success == true
        posted == null                                   // no source save on an OAuth-only update
        result.message.contains('no source change')
        result.oauth.success == true
        result.oauth.enabled == true
        result.oauth.clientId == 'gen-id'
        result.oauth.clientSecret == 'gen-secret'
    }

    def "hub_update_app oauth with explicit client_id/client_secret sends them verbatim (no detail read)"() {
        given:
        enableWrite()
        // Only updateOAuth is registered: if the tool wrongly did the detail read, that GET would be
        // unregistered and throw, surfacing as success:false -- so success here proves it skipped the read.
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=my-id&clientSecret=my-sec&refreshSecret=false') { params ->
            '{"success":true,"clientId":"my-id","clientSecret":"my-sec"}'
        }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true, client_id: 'my-id', client_secret: 'my-sec'], confirm: true])

        then:
        result.success == true
        result.oauth.clientId == 'my-id'
    }

    def "hub_update_app oauth refresh_secret=true regenerates the secret"() {
        given:
        enableWrite()
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=cid&clientSecret=csec&refreshSecret=true') { params ->
            '{"success":true,"clientId":"cid","clientSecret":"rotated"}'
        }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true, client_id: 'cid', client_secret: 'csec', refresh_secret: true], confirm: true])

        then:
        result.success == true
        result.oauth.clientSecret == 'rotated'
    }

    def "hub_update_app refuses to change the MCP server's OWN app OAuth when Developer Mode is off"() {
        given:
        enableWrite()   // app.id == 1 (the stubbed self app); Developer Mode is off

        when:
        script.toolUpdateAppCode([appId: '1', oauth: [enabled: true], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("own OAuth")
    }

    def "hub_update_app self-OAuth guard catches the Apps Code CLASS id, not just the instance id"() {
        // OAuth targets the Apps Code CLASS id (178 here), which differs from app.id (instance 1).
        // The REAL _resolveSelfAppClassId resolves it from /hub2/userAppTypes -- an instance-id-only
        // guard would miss this and let a self-OAuth (which would break the live /mcp token) through.
        given:
        enableWrite()
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }

        when:
        script.toolUpdateAppCode([appId: '178', oauth: [enabled: true], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("own OAuth")
    }

    def "hub_update_app updates source AND enables OAuth in one call"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":7,"source":"old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: true, id: 50] }
        hubGet.register('/app/updateOAuth?id=50&oauthEnabled=true&clientId=cid&clientSecret=csec&refreshSecret=false') { params ->
            '{"success":true,"clientId":"cid","clientSecret":"csec"}'
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new src', oauth: [enabled: true, client_id: 'cid', client_secret: 'csec'], confirm: true])

        then:
        result.success == true
        result.sourceMode == 'source'        // source leg ran
        result.oauth.success == true         // OAuth leg ran
    }

    def "hub_update_app surfaces an OAuth failure without false-greening"() {
        given:
        enableWrite()
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=c&clientSecret=s&refreshSecret=false') { params ->
            '{"success":false}'
        }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        result.success == false
        result.oauth.success == false
    }

    def "hub_update_app oauth refuses to submit empty creds when the current-cred read fails (no clobber)"() {
        given:
        enableWrite()
        // /app/list/single/data/42 is NOT registered -> the preserve-read throws -> the tool must
        // refuse rather than send empty creds (which would blank an already-enabled app's live creds).
        // updateOAuth IS registered but must never be reached.
        def hitOAuth = false
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=&clientSecret=&refreshSecret=false') { params ->
            hitOAuth = true; '{"success":true,"clientId":"x","clientSecret":"y"}'
        }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true], confirm: true])

        then:
        hitOAuth == false                                   // never reached the write
        result.success == false
        result.oauth.success == false
        result.oauth.error.contains('current OAuth credentials')
    }

    def "hub_update_app source saved but OAuth failed reports partial=true (a half-applied write)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":3,"source":"old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: true, id: 60] }
        hubGet.register('/app/updateOAuth?id=60&oauthEnabled=true&clientId=c&clientSecret=s&refreshSecret=false') { params -> '{"success":false}' }

        when:
        def result = script.toolUpdateAppCode([appId: '60', source: 'new', oauth: [enabled: true, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        result.sourceMode == 'source'   // source leg landed
        result.success == false         // overall failed because the OAuth leg failed
        result.partial == true          // half-applied: code saved, OAuth not
        result.oauth.success == false
    }

    def "hub_update_app aborts before OAuth when the source update fails (OAuth never attempted)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":3,"source":"old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: false, error: 'compile boom'] }
        def hitOAuth = false
        hubGet.register('/app/updateOAuth?id=60&oauthEnabled=true&clientId=c&clientSecret=s&refreshSecret=false') { params -> hitOAuth = true; '{"success":true}' }

        when:
        def result = script.toolUpdateAppCode([appId: '60', source: 'new', oauth: [enabled: true, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        result.success == false
        hitOAuth == false                 // OAuth is never attempted once the source leg fails
        !result.containsKey('oauth')      // no oauth leg ran
    }

    def "hub_update_app oauth with only client_secret reads + preserves the current client_id"() {
        given:
        enableWrite()
        hubGet.register('/app/list/single/data/42') { params -> '[{"id":42,"oauthClientId":"kept-id","oauthClientSecret":"old-sec"}]' }
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=kept-id&clientSecret=new-sec&refreshSecret=false') { params -> '{"success":true,"clientId":"kept-id","clientSecret":"new-sec"}' }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true, client_secret: 'new-sec'], confirm: true])

        then:
        result.success == true
        result.oauth.clientId == 'kept-id'      // the omitted side preserved from the read
        result.oauth.clientSecret == 'new-sec'  // the caller's override
    }

    def "hub_update_app oauth enabled=false sends oauthEnabled=false"() {
        given:
        enableWrite()
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=false&clientId=c&clientSecret=s&refreshSecret=false') { params -> '{"success":true}' }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: false, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        result.success == true
        result.oauth.enabled == false
    }

    def "hub_update_app oauth treats a non-true 'success' value as failure (not greened)"() {
        given:
        enableWrite()
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=c&clientSecret=s&refreshSecret=false') { params -> '{"success":"true","clientId":"x"}' }

        when:
        def result = script.toolUpdateAppCode([appId: '42', oauth: [enabled: true, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        result.success == false          // "true" (string) is not boolean true -> not greened
        result.oauth.success == false
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch enables OAuth (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/updateOAuth?id=42&oauthEnabled=true&clientId=c&clientSecret=s&refreshSecret=false') { params ->
            '{"success":true,"clientId":"c","clientSecret":"s"}'
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '42', oauth: [enabled: true, client_id: 'c', client_secret: 's'], confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.oauth.success == true

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch (source mode) backs up and POSTs to /app/saveOrUpdateJson (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 12, "source": "old source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 50]
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '50', source: 'new source', confirm: true])

        then:
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.id == 50
        sent.version == 12
        sent.source == 'new source'
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

    def "hub_update_app sends a JSON body whose id is an Integer (string appId arg is coerced for the endpoint)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 12, "source": "old source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.json = body
            [success: true, id: 123]
        }

        when: 'appId arrives as a String, the way MCP args always do'
        def result = script.toolUpdateAppCode([appId: '123', source: 'new source', confirm: true])

        then: 'the wire body carries id as a JSON number, with version and source forwarded'
        def sent = new groovy.json.JsonSlurper().parseText(captured.json)
        sent.id == 123
        sent.id instanceof Integer
        sent.version == 12
        sent.source == 'new source'
        result.success == true
    }

    def "hub_update_app (sourceFile mode) reads source from File Manager"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "on-hub source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'source from file'.getBytes('UTF-8') : null
        }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = body
            [success: true, id: 60]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '60', sourceFile: 'my-app.groovy', confirm: true])

        then:
        new groovy.json.JsonSlurper().parseText(captured.body).source == 'source from file'
        result.success == true
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch (sourceFile mode) reads source from File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "on-hub source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'my-app.groovy' ? 'source from file'.getBytes('UTF-8') : null
        }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = body
            [success: true, id: 60]
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '60', sourceFile: 'my-app.groovy', confirm: true])

        then:
        new groovy.json.JsonSlurper().parseText(captured.body).source == 'source from file'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'sourceFile'
        inner.note.contains('File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app (sourceFile mode) throws when the file is absent"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolUpdateAppCode([appId: '60', sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("not found in File Manager")
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope when sourceFile absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '60', sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app (resave mode) fetches the current source and re-saves it"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 7, "source": "current source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = body
            [success: true, id: 70]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '70', resave: true, confirm: true])

        then: 'submitted source matches fetched source; version captured from the fresh fetch'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.source == 'current source'
        sent.version == 7
        result.success == true
        result.sourceMode == 'resave'
        result.note.contains('no cloud round-trip')
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch (resave mode) fetches current source and re-saves it (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 7, "source": "current source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = body
            [success: true, id: 70]
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '70', resave: true, confirm: true])

        then:
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.source == 'current source'
        sent.version == 7
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'resave'
        inner.note.contains('no cloud round-trip')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app reports failure when the hub response carries success=false"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Compilation failed']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'broken', confirm: true])

        then:
        result.success == false
        result.error.contains('Compilation failed')
        result.note.contains('syntax')
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch reports failure envelope when hub response carries success=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Compilation failed']
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '80', source: 'broken', confirm: true])

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

    @spock.lang.Unroll
    def "hub_update_app failure surfaces the hub's verbatim compile error from the '#errKey' field"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, (errKey): 'unable to resolve class Foo']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'broken', confirm: true])

        then:
        result.success == false
        result.error.contains('unable to resolve class Foo')

        where:
        errKey << ['message', 'errorMessage']
    }

    def "hub_update_app fails closed on an empty hub response (non-self update)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'new', confirm: true])

        then: 'a dropped response on a foreign app is NOT assumed to have landed'
        result.success == false
        result.error.contains('Empty response from /app/saveOrUpdateJson')
        result.error.contains('verify')
    }

    def "hub_update_app fails closed when the hub echoes success for a DIFFERENT id (duplicate-save signature)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        and: 'saveOrUpdateJson is an upsert: success with a different echoed id means it saved elsewhere'
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 999]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new', confirm: true])

        then: 'reported as failure naming both ids, not as a silent mis-targeted success'
        result.success == false
        result.error.contains('duplicate')
        result.error.contains('999')
        result.error.contains('50')
    }

    def "hub_update_app failure with neither message nor errorMessage still returns a concrete error"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, foo: 'bar']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'new', confirm: true])

        then: 'the raw response rides in the error instead of a null/blank message'
        result.success == false
        result.error.contains('hub response lacked success=true')
        result.error.contains('foo')
    }

    def "hub_update_app reports failure on an unexpected response shape (hub returned a list)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> [[odd: 'list']] }

        when:
        def result = script.toolUpdateAppCode([appId: '80', source: 'new', confirm: true])

        then:
        result.success == false
        result.error.contains('Unexpected response shape')
    }

    def "hub_update_app rejects bulk-mode args (updates[] not supported on apps)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([
            updates: [[appId: '1', source: 'a'], [appId: '2', source: 'b']],
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Bulk mode")
        ex.message.contains("hub_update_app")
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope when bulk-mode updates[] (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [
            updates: [[appId: '1', source: 'a'], [appId: '2', source: 'b']],
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('Bulk mode')
        response.error.message.contains('hub_update_app')

        where:
        useGateways << [true, false]
    }

    def "hub_update_driver (single mode) delegates to toolUpdateItemCode with the driver paths"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 4, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            [success: true, id: 55]
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        captured.path == '/driver/saveOrUpdateJson'
        result.success == true
        result.driverId == '55'
        result.sourceMode == 'source'
    }

    @spock.lang.Unroll
    def "hub_update_driver via dispatch (single mode) delegates with driver paths (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 4, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            [success: true, id: 55]
        }

        when:
        def response = mcpDriver.callTool('hub_update_driver', [driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        captured.path == '/driver/saveOrUpdateJson'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.driverId == '55'
        inner.sourceMode == 'source'

        where:
        useGateways << [true, false]
    }

    // -------- hub_update_driver bulk mode --------

    def "hub_update_driver bulk mode throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_update_driver', [updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_update_driver bulk via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_update_driver', [updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_update_driver bulk mode throws when both updates and driverId are supplied"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDriverCode([driverId: '10', updates: [[driverId: '20', sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bulk mode')
        ex.message.contains('driverId')
    }

    @spock.lang.Unroll
    def "hub_update_driver bulk via dispatch returns -32602 envelope when both updates and driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_driver', [driverId: '10', updates: [[driverId: '20', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('bulk mode')
        response.error.message.contains('driverId')

        where:
        useGateways << [true, false]
    }

    def "hub_update_driver bulk mode throws when updates is an empty array"() {
        given:
        enableWrite()

        when:
        script.toolUpdateDriverCode([updates: [], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be empty')
    }

    @spock.lang.Unroll
    def "hub_update_driver bulk via dispatch returns -32602 envelope when updates is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_driver', [updates: [], confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('must not be empty')

        where:
        useGateways << [true, false]
    }

    def "hub_update_driver bulk mode happy path: 3 drivers all succeed"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        def updatePaths = []
        def updateIds = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            updatePaths << path
            updateIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
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
        updatePaths.every { it == '/driver/saveOrUpdateJson' }
        updateIds as Set == ['101', '102', '103'] as Set
    }

    @spock.lang.Unroll
    def "hub_update_driver bulk via dispatch happy path 3 drivers all succeed (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        def updatePaths = []
        def updateIds = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            updatePaths << path
            updateIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
        }

        when:
        def response = mcpDriver.callTool('hub_update_driver', [
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
        updatePaths.every { it == '/driver/saveOrUpdateJson' }
        updateIds as Set == ['101', '102', '103'] as Set

        where:
        useGateways << [true, false]
    }

    def "hub_update_driver bulk mode partial failure: middle driver fails, outer two still applied"() {
        given:
        enableWrite()
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
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
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
    def "hub_update_driver bulk via dispatch partial failure middle driver fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'driver-202.groovy' ? null : 'metadata { }'.getBytes('UTF-8')
        }
        def postedIds = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
        }

        when:
        def response = mcpDriver.callTool('hub_update_driver', [
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

    def "hub_update_driver bulk mode: mixed resave and sourceFile entries dispatch correctly"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            'metadata { name "from-file" }'.getBytes('UTF-8')
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { name \\"existing\\" }"}'
        }
        def postedSources = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedSources << new groovy.json.JsonSlurper().parseText(body).source
            [success: true]
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
    def "hub_update_driver bulk via dispatch mixed resave and sourceFile entries dispatch correctly (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            'metadata { name "from-file" }'.getBytes('UTF-8')
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { name \\"existing\\" }"}'
        }
        def postedSources = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedSources << new groovy.json.JsonSlurper().parseText(body).source
            [success: true]
        }

        when:
        def response = mcpDriver.callTool('hub_update_driver', [
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

    def "hub_update_driver bulk mode: per-item update failure propagates note and lastBackup on the failed entry"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        // Middle item's hub POST returns success:false — reaches toolUpdateItemCodeInner's
        // failure branch (which carries note + lastBackup).
        script.metaClass.hubInternalPostJson = { String path, String body ->
            if (new groovy.json.JsonSlurper().parseText(body).id == 402) {
                [success: false, message: 'version conflict']
            } else {
                [success: true]
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
    def "hub_update_driver bulk via dispatch per-item failure propagates note and lastBackup (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            if (new groovy.json.JsonSlurper().parseText(body).id == 402) {
                [success: false, message: 'version conflict']
            } else {
                [success: true]
            }
        }

        when:
        def response = mcpDriver.callTool('hub_update_driver', [
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

    def "hub_create_driver returns success=false when verify endpoint returns clean JSON with empty source field"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 4444, version: 1]
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
    def "hub_create_driver via dispatch returns success=false envelope when verify clean JSON has empty source (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 4444, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "source": "", "version": 1}'
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

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

    // -------- toolDeleteItem (app / driver) --------

    def "hub_delete_item (app) throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteItem([type: 'app', item_id: '1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '1'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_delete_item', [type: 'app', item_id: '1', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '1', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) throws when appId is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteItem([type: 'app', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch returns -32602 envelope when appId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('appId is required')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) backs up source, deletes via deleteJsonSafe, and reports the backup file"() {
        given:
        enableWrite()
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
        def result = script.toolDeleteItem([type: 'app', item_id: '33', confirm: true])

        then:
        result.success == true
        result.appId == '33'
        result.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        result.restoreHint.contains('hub_create_app')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch backs up source and deletes via deleteJsonSafe (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 2, "source": "code body"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/33') { params -> '{"status": "true"}' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '33', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.appId == '33'
        inner.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        inner.restoreHint.contains('hub_create_app')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) proceeds with a warning when pre-delete backup fails"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('source fetch failed')
        }
        hubGet.register('/app/edit/deleteJsonSafe/44') { params ->
            '{"status": "true"}'
        }

        when:
        def result = script.toolDeleteItem([type: 'app', item_id: '44', confirm: true])

        then: 'delete still succeeds, but the response flags that the backup could not be created'
        result.success == true
        result.message.contains('backup failed')
        result.backupWarning.contains('permanently lost')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch proceeds with backupWarning when pre-delete backup fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            throw new RuntimeException('source fetch failed')
        }
        hubGet.register('/app/edit/deleteJsonSafe/44') { params -> '{"status": "true"}' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '44', confirm: true])

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

    def "hub_delete_item (app) reports failure when the hub delete response signals error"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "x"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/55') { params ->
            '{"status": "error", "errorMessage": "in use"}'
        }

        when:
        def result = script.toolDeleteItem([type: 'app', item_id: '55', confirm: true])

        then:
        result.success == false
        result.error.contains('Delete may have failed')
        result.appId == '55'
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch reports failure envelope when hub delete signals error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "x"}'
        }
        hubGet.register('/app/edit/deleteJsonSafe/55') { params ->
            '{"status": "error", "errorMessage": "in use"}'
        }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '55', confirm: true])

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

    def "hub_delete_item (driver) hits /driver/editor/deleteJson/<id> and succeeds"() {
        given:
        enableWrite()
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
        def result = script.toolDeleteItem([type: 'driver', item_id: '77', confirm: true])

        then:
        deletePath == '/driver/editor/deleteJson/77'
        result.success == true
        result.driverId == '77'
        result.backupFile == 'mcp-backup-driver-77.groovy'
    }

    @spock.lang.Unroll
    def "hub_delete_item (driver) via dispatch hits /driver/editor/deleteJson and succeeds (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
        def response = mcpDriver.callTool('hub_delete_item', [type: 'driver', item_id: '77', confirm: true])

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

    def "hub_restore_backup throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([backupKey: 'app_1'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_1'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_restore_backup throws when backupKey is missing"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('backupKey is required')
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch returns -32602 envelope when backupKey missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('backupKey is required')

        where:
        useGateways << [true, false]
    }

    def "hub_restore_backup returns error response when the key is unknown"() {
        given:
        enableWrite()
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
    def "hub_restore_backup via dispatch returns success=false envelope when key unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_existing': [type: 'app', id: '1', fileName: 'f.groovy',
                             version: 1, timestamp: 1L, sourceLength: 0]
        ]

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_missing', confirm: true])

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

    def "hub_restore_backup reads backup, creates a pre-restore copy, and pushes source to the hub"() {
        given:
        enableWrite()

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

        and: 'hubInternalPostJson captures the save call and returns success'
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 99]
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'pre-restore backup file was created with the current-source contents'
        uploads == ['mcp-prerestore-app-99.groovy']

        and: 'restore hits /app/saveOrUpdateJson with the backup source and the fresh version'
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.id == 99
        sent.version == 9
        sent.source == 'old source v4'

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
    def "hub_restore_backup via dispatch reads backup creates pre-restore copy and pushes to hub (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
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
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 99]
        }

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_99', confirm: true])

        then:
        uploads == ['mcp-prerestore-app-99.groovy']
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.id == 99
        sent.version == 9
        sent.source == 'old source v4'
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

    def "hub_restore_backup reports failure and preserves the backup when the hub POST fails"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'driver_88': [type: 'driver', id: '88', fileName: 'mcp-backup-driver-88.groovy',
                          version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'backup bytes'.getBytes('UTF-8') }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            [success: false, message: 'bad code']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'driver_88', confirm: true])

        then: 'a driver restore rides the DRIVER save endpoint (a cross-write to /app/ would corrupt an app)'
        captured.path == '/driver/saveOrUpdateJson'

        and: 'failure is reported, original manifest entry preserved for retry'
        result.success == false
        result.error.contains('bad code')
        result.message.contains('preserved')
        atomicStateMap.itemBackupManifest.containsKey('driver_88')
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch reports failure envelope and preserves backup when hub POST fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'driver_88': [type: 'driver', id: '88', fileName: 'mcp-backup-driver-88.groovy',
                          version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'backup bytes'.getBytes('UTF-8') }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            [success: false, message: 'bad code']
        }

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'driver_88', confirm: true])

        then:
        captured.path == '/driver/saveOrUpdateJson'
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

    def "hub_restore_backup fails closed on an empty hub response (non-self restore)"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'old source v4'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'failure is reported and the backup entry is preserved for retry'
        result.success == false
        result.error.contains('Empty response from /app/saveOrUpdateJson')
        result.message.contains('preserved')
        atomicStateMap.itemBackupManifest.containsKey('app_99')
    }

    def "hub_restore_backup self-restore treats an empty response as assumed success and stashes lastSelfDeploy"() {
        // Restoring the MCP server's OWN code drops the response exactly like a self-update
        // (the recompile kills the in-flight request), so the empty-body leniency applies and
        // the outcome is recoverable from atomicState.lastSelfDeploy via hub_get_info.
        given:
        enableWrite()

        and: 'backup entry id matches app.id (sharedAppStub id 1); class-id lookup resolves nothing'
        atomicStateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        script.metaClass.downloadHubFile = { String fileName -> 'self backup source'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_1', confirm: true])

        then: 'success is inferred, flagged as assumed (not hub-confirmed) with a verification pointer'
        result.success == true
        result.assumed == true
        result.note.contains('hub_get_info')

        and: 'the lastSelfDeploy stash records the restore outcome for a follow-up read'
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.assumed == true
        atomicStateMap.lastSelfDeploy.sourceMode == 'restore'
        atomicStateMap.lastSelfDeploy.error == null
    }

    def "hub_restore_backup fails closed when the hub echoes success for a DIFFERENT id (duplicate-save signature)"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'old source v4'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        and: 'saveOrUpdateJson is an upsert: success with a different echoed id means it saved elsewhere'
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 777]
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'reported as failure naming both ids; backup entry preserved for retry'
        result.success == false
        result.error.contains('duplicate')
        result.error.contains('777')
        result.error.contains('99')
        atomicStateMap.itemBackupManifest.containsKey('app_99')
    }

    def "hub_restore_backup self-restore is detected via the Apps Code CLASS id (the real CI deploy path), not just the instance id"() {
        // A code restore targets the Apps Code CLASS id (e.g. 178), which differs from app.id
        // (the running instance, 1). The REAL _resolveSelfAppClassId runs here against a stubbed
        // /hub2/userAppTypes -- if detection only checked the instance id, this self-restore
        // would fail closed instead of getting the empty-body leniency.
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_178': [type: 'app', id: '178', fileName: 'mcp-backup-app-178.groovy',
                        version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        script.metaClass.downloadHubFile = { String fileName -> 'self backup source'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_178', confirm: true])

        then: 'empty-body leniency fires via the class-id match'
        result.success == true
        result.assumed == true
        result.note.contains('hub_get_info')

        and: 'the stash records the restore outcome'
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.assumed == true
        atomicStateMap.lastSelfDeploy.sourceMode == 'restore'
    }

    def "hub_restore_backup self-restore stashes lastSelfDeploy when the save THROWS (exception path)"() {
        // A big self-restore can kill the connection before any response body exists. The catch
        // block must still persist a failure record so a follow-up hub_get_info can recover it.
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        script.metaClass.downloadHubFile = { String fileName -> 'self backup source'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            throw new RuntimeException('connection reset mid-restore')
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_1', confirm: true])

        then:
        result.success == false
        result.error.contains('Restore failed:')
        result.error.contains('connection reset mid-restore')

        and: 'the failure is stashed for a follow-up read, marked as a restore'
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.sourceMode == 'restore'
        atomicStateMap.lastSelfDeploy.error.contains('connection reset mid-restore')
        !atomicStateMap.lastSelfDeploy.containsKey('assumed')
    }

    def "hub_restore_backup self-restore records the hub's VERBATIM error to lastSelfDeploy on a rejected save"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        script.metaClass.downloadHubFile = { String fileName -> 'self backup source'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        and: 'the hub rejects the save with its real compile error'
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'unable to resolve class Foo']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_1', confirm: true])

        then: 'the result carries the verbatim message'
        result.success == false
        result.error.contains('unable to resolve class Foo')

        and: 'the stash carries it verbatim (un-prefixed), the thing a follow-up read must recover'
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.error == 'unable to resolve class Foo'
        atomicStateMap.lastSelfDeploy.sourceMode == 'restore'
    }

    def "hub_restore_backup failure with neither message nor errorMessage still returns a concrete error"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'old source v4'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, foo: 'bar']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'the raw response rides in the error instead of a null/blank message'
        result.success == false
        result.error.contains('hub response lacked success=true')
        result.error.contains('foo')
        atomicStateMap.itemBackupManifest.containsKey('app_99')
    }

    def "hub_restore_backup success without an echoed id is accepted (id-echo guard tolerates a missing id)"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'old source v4'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]   // no id key at all
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'the duplicate-save guard only fires on a PRESENT mismatched id'
        result.success == true
        result.id == '99'
        !result.containsKey('assumed')
    }

    def "hub_restore_backup driver restore fails closed on an empty response even when the driver id collides with app.id"() {
        // isSelfRestore is keyed on type=='app'; a driver whose code id happens to equal the
        // MCP server's instance id must NOT inherit the self-restore empty-body leniency.
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'driver_1': [type: 'driver', id: '1', fileName: 'mcp-backup-driver-1.groovy',
                         version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        script.metaClass.downloadHubFile = { String fileName -> 'driver backup'.getBytes('UTF-8') }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'driver_1', confirm: true])

        then: 'fails closed on the DRIVER save path -- no leniency bleed across types'
        result.success == false
        result.error.contains('Empty response from /driver/saveOrUpdateJson')
        atomicStateMap.itemBackupManifest.containsKey('driver_1')

        and: 'no self-deploy stash was written'
        atomicStateMap.lastSelfDeploy == null
    }

    def "hub_restore_backup self-restore with a Map-confirmed success carries no assumed flag (hub-confirmed, not inferred)"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        script.metaClass.downloadHubFile = { String fileName -> 'self backup source'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }

        and: 'the response survived the recompile and confirms the save (matching echoed id)'
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, id: 1]
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_1', confirm: true])

        then: 'a hub-confirmed self-restore is a plain success -- no assumed flag, no verification note'
        result.success == true
        !result.containsKey('assumed')
        !result.containsKey('note')

        and: 'the stash records the confirmed outcome without assumed'
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.error == null
        atomicStateMap.lastSelfDeploy.sourceMode == 'restore'
        !atomicStateMap.lastSelfDeploy.containsKey('assumed')
    }

    def "hub_restore_backup returns clear error for library type and directs user to hub_update_library"() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1L, sourceLength: 200]
        ]

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'library_42', confirm: true])

        then:
        result.success == false
        result.error.contains('use hub_create_library or hub_update_library')
        result.backupFile == 'mcp-backup-library-42.groovy'
        result.type == 'library'
    }

    // -------- hub_update_app: expectedVersion optimistic-lock --------

    def "hub_update_app with matching expectedVersion proceeds and POSTs to /app/saveOrUpdateJson"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 42, "source": "old source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 50]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new source', expectedVersion: 42, confirm: true])

        then:
        captured.path == '/app/saveOrUpdateJson'
        new groovy.json.JsonSlurper().parseText(captured.body).version == 42
        result.success == true
        result.previousVersion == 42
    }

    def "hub_update_app with mismatching expectedVersion aborts BEFORE POST and returns conflict"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 50, "source": "current source"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
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

    @spock.lang.Unroll
    def "hub_update_app via dispatch surfaces expectedVersion conflict inside result.content text (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 50, "source": "current source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '50', source: 'stale', expectedVersion: 42, confirm: true])

        then: 'no hub-update POST happens; conflict is a non-error successful tool response, not a JSON-RPC error'
        postCount == 0
        response.error == null
        !response.result.isError

        and: 'inner payload carries the conflict triple'
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.conflict == true
        inner.expectedVersion == 42
        inner.currentVersion == 50

        where:
        useGateways << [true, false]
    }

    def "hub_update_app coerces a string expectedVersion to integer and detects mismatch (inequality-proves-coercion)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 7, "source": "current"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
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

    def "hub_update_app rejects unparseable expectedVersion with a clear message that preserves the original cause"() {
        given:
        enableWrite()
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

    def "hub_update_app rejects explicit-null expectedVersion (silent-overwrite footgun)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: null, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion was supplied as null')
    }

    def "hub_update_app with expectedVersion:0 against hub version 0 succeeds (no Groovy truthy short-circuit)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 0, "source": "v0 source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'v1', expectedVersion: 0, confirm: true])

        then:
        result.success == true
        captured.body.version == 0
    }

    def "hub_update_app with expectedVersion:0 against hub version 1 returns conflict (proves containsKey is checked, not truthiness)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "v1"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 0, confirm: true])

        then:
        postCount == 0
        result.conflict == true
        result.expectedVersion == 0
        result.currentVersion == 1
    }

    def "hub_update_app with expectedVersion + resave fetches version from the resave parse (no second GET) and detects match/mismatch"() {
        given:
        enableWrite()
        def getCount = 0
        hubGet.register('/app/ajax/code') { params ->
            getCount++
            '{"status": "ok", "version": 7, "source": "on-hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
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

    def "hub_update_app with hub returning non-integer version aborts with a clear error (no silent unguarded write)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": "v1.2.3", "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
        }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 1, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-integer current version')
        postCount == 0
    }

    def "hub_update_app propagates POST failure with a clean envelope -- no conflict:true leakage on non-conflict failures"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "old"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'compile failed']
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

    // -------- hub_update_driver expectedVersion --------

    def "hub_update_driver single-mode expectedVersion conflict carries driverId (not appId)"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "drv"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postCount++
            [success: true]
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '77', source: 's', expectedVersion: 8, confirm: true])

        then:
        postCount == 0
        result.conflict == true
        result.driverId == '77'
        !result.containsKey('appId')
    }

    def "hub_update_driver bulk mode propagates per-item expectedVersion conflicts without aborting siblings"() {
        given:
        enableWrite()
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
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
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

    def "hub_update_driver rejects bulk + top-level expectedVersion (schema-vs-mutex consistency)"() {
        given:
        enableWrite()

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

    def "hub_update_driver bulk per-item explicit-null expectedVersion is rejected (same footgun as single-mode)"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            def id = params.id?.toString()
            if (id == '301') return '{"status": "ok", "version": 1, "source": "a"}'
            if (id == '302') return '{"status": "ok", "version": 2, "source": "b"}'
            null
        }
        def postedIds = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
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

    def "hub_update_driver bulk mode mixes a per-item conflict with a per-item thrown error in the same batch"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            def id = params.id?.toString()
            if (id == '201') return '{"status": "ok", "version": 5, "source": "a"}'   // conflict (caller will say 99)
            if (id == '203') return '{"status": "ok", "version": 8, "source": "c"}'   // success
            null
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
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

    def "hub_update_app accepts Long expectedVersion (common JSON parser output)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
        }

        when: 'caller passes a Long (e.g. parser produced Long for an integer JSON literal)'
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 5L, confirm: true])

        then:
        result.success == true
        captured.body.version == 5
    }

    def "hub_update_app rejects non-coercible Map expectedVersion (exercises ClassCastException leg of the union catch)"() {
        given:
        enableWrite()
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

    // -------- hub_update_app: self-update guard --------

    def "hub_update_app refuses self-update on the MCP server's own appId when Developer Mode is OFF"() {
        given:
        enableWrite()
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

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns -32602 envelope on self-update with Developer Mode off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        settingsMap.enableDeveloperMode = false

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', source: 'self-overwrite', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('self-update')
        response.error.message.contains('Developer Mode')

        where:
        useGateways << [true, false]
    }

    def "hub_update_app allows self-update on the MCP server's own appId when Developer Mode is ON and audit-logs it"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
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

    def "hub_update_app self-update treats an empty response as success and records lastSelfDeploy with assumed:true"() {
        // A self-update legitimately drops the response -- the recompile kills the in-flight
        // request -- so an EMPTY body is success here; the outcome is recovered later from
        // atomicState.lastSelfDeploy via hub_get_info. The success is inferred, not
        // hub-confirmed, so the stash carries assumed:true.
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v3', confirm: true])

        then:
        result.success == true
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.error == null
        atomicStateMap.lastSelfDeploy.assumed == true
    }

    def "hub_update_app self-update fails closed on a non-JSON (unparseable-sentinel) response and stashes the failure"() {
        // The empty-body leniency must NOT extend to a non-JSON body: an HTML login page or
        // error page is never a legitimate dropped-response signature, even mid-self-update.
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [_unparseable: true, message: 'hub returned a non-JSON body from /app/saveOrUpdateJson: <html>...']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v4', confirm: true])

        then:
        result.success == false
        result.error.contains('non-JSON body')

        and: 'the stash records the failure with the sentinel message, not an assumed success'
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.error.contains('non-JSON body')
        !atomicStateMap.lastSelfDeploy.containsKey('assumed')
    }

    def "hub_update_app self-update guard blocks resave mode (most-likely real-world brick scenario)"() {
        given:
        enableWrite()
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

    def "hub_update_app self-update guard blocks sourceFile mode (no File Manager read happens)"() {
        given:
        enableWrite()
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

    def "hub_update_app self-update guard allows resave mode when Developer Mode is ON"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current self src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.body = new groovy.json.JsonSlurper().parseText(body)
            [success: true]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '1', resave: true, confirm: true])

        then:
        result.success == true
        captured.body.source == 'current self src'
    }

    def "hub_update_app self-update guard does NOT fire on non-self appIds even when Developer Mode is OFF"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "other app"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
        }

        when:
        def result = script.toolUpdateAppCode([appId: '999', source: 'foreign update', confirm: true])

        then:
        result.success == true
    }

    def "hub_update_driver self-update guard does NOT fire (keyed on type=='app'; driverId 1 is unrelated)"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "driver src"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '1', source: 'driver v2', confirm: true])

        then:
        result.success == true
    }

    def "hub_update_app self-update guard fails closed when app context is unavailable (refuses + ERROR-logs)"() {
        given:
        enableWrite()
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

    def "hub_update_driver bulk mode: a per-item driverId equal to app.id still succeeds (type=='app' guard does not fire on driver bulk)"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 1, "source": "drv"}'
        }
        def postedIds = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
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
    def "hub_restore_backup via dispatch returns success=false envelope for library type (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1L, sourceLength: 200]
        ]

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'library_42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('use hub_create_library or hub_update_library')
        inner.backupFile == 'mcp-backup-library-42.groovy'
        inner.type == 'library'

        where:
        useGateways << [true, false]
    }
}
