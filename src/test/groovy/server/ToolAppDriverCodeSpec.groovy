package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the hub_manage_code gateway tools in libraries/mcp-code-management-lib.groovy
 * (toolRestoreItemBackup is in libraries/mcp-item-backups-lib.groovy):
 *
 * - toolCreateApp           -> hub_create_app     (source|sourceFile|importUrl; codeAppId)
 * - toolCreateDriver        -> hub_create_driver  (source|sourceFile|importUrl; bulk installs[])
 * - toolUpdateAppCode       -> hub_update_app     (four source modes: source, sourceFile, importUrl, resave)
 * - toolUpdateDriverCode    -> hub_update_driver  (four source modes + bulk updates array)
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
    // The running instance is 1; enableWrite supplies Apps Code class 178.
    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
    }

    private void enableWrite() {
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    private void stubSourceFiles(Map initialFiles) {
        def files = new LinkedHashMap(initialFiles)
        script.metaClass.downloadHubFile = { String fileName -> files.get(fileName) }
        script.metaClass.uploadHubFile = { String name, byte[] content -> files.put(name, content) }
    }

    // -------- one save path: the save response is the verdict --------

    @spock.lang.Unroll
    def "a #type create returns the hub's id and version with no read after the save"() {
        given:
        enableWrite()
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            posts << path
            [success: true, message: '', id: 77, version: 1]
        }

        when:
        def result = script."toolCreate${type.capitalize()}"([source: 'code', confirm: true])

        then:
        posts == ["/${type}/saveOrUpdateJson".toString()]
        hubGet.calls.isEmpty()
        result.success == true
        result[idField] == '77'
        result.version == 1
        !result.containsKey('verified')
        !result.containsKey('verifyError')

        where:
        type      | idField
        'app'     | 'appId'
        'driver'  | 'driverId'
        'library' | 'libraryId'
    }

    @spock.lang.Unroll
    def "a #tool save whose request throws has an unknown outcome and says to check before retrying (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('Read timed out')
        }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool(tool, [source: 'code', confirm: true]))

        then:
        inner.success == false
        inner.error.contains('Read timed out')
        inner.note.contains(listCall)
        inner.note.contains('duplicate')
        !inner.note.contains('syntax')

        where:
        tool                 | listCall             | useGateways
        'hub_create_app'     | 'hub_list_apps'      | true
        'hub_create_driver'  | 'hub_list_drivers'   | false
        'hub_create_library' | 'hub_list_libraries' | true
    }

    def "an unparseable save response is reported as unverified with the check-before-retry note"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [_unparseable: true, message: 'hub returned a non-JSON body from /driver/saveOrUpdateJson: <html>']
        }

        when:
        def result = script.toolCreateDriver([source: 'code', confirm: true])

        then:
        result.success == false
        result.driverId == null
        result.error.contains('install unverified')
        result.error.contains('<html>')
        result.note.contains('hub_list_drivers')
        result.note.contains('duplicate')
    }

    @spock.lang.Unroll
    def "a refused driver create is a plain refusal with the hub's message and no read: #label"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: false, message: hubReply] }

        when:
        def result = script.toolCreateDriver([source: source, confirm: true])

        then:
        result.success == false
        result.driverId == null
        result.error == "Driver installation failed: ${expected}".toString()
        result.note == 'Fix the source and retry.'
        !result.containsKey('retryable')
        hubGet.calls.isEmpty()

        where:
        label                      | source                                                                                       | hubReply                                                                  | expected
        'a compile error'          | 'metadata { definition(name: "Probe Driver", namespace: "mcptest", author: "x") { } } }'     | "unexpected token: } @ line <span id='errorLine'>1</span>, column 85."   | 'unexpected token: } @ line 1, column 85.'
        'a name with a parenthesis' | 'metadata { definition(name: "My Sensor (beta)", namespace: "mcptest", author: "x") { boom( } }' | "unexpected token: } @ line <span id='errorLine'>1</span>, column 90." | 'unexpected token: } @ line 1, column 90.'
        'a null-object refusal'    | 'metadata { definition(name: "Probe Driver", namespace: "mcptest", author: "x") { } }'       | "Cannot get property 'id' on null object"                                 | "Cannot get property 'id' on null object"
    }

    def "an app create compile error comes back without markup and with no read"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: "unexpected token: boom @ line <span id='errorLine'>1</span>, column 1."]
        }

        when:
        def result = script.toolCreateApp([source: 'definition(name: "X")', confirm: true])

        then:
        result.success == false
        result.appId == null
        result.error == 'App installation failed: unexpected token: boom @ line 1, column 1.'
        result.note == 'Fix the source and retry.'
        !result.containsKey('retryable')
        hubGet.calls.isEmpty()
    }

    def "an app create refused for a missing definition field returns the hub's message and a neutral note"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'description,author cannot be empty in definition section']
        }

        when:
        def result = script.toolCreateApp([source: 'definition(name: "X")', confirm: true])

        then:
        result.success == false
        result.error.contains('description,author cannot be empty in definition section')
        result.note == 'Fix the source and retry.'
    }

    def "a bulk entry carries the hub's version and no verification fields; a refused entry has no retryable flag"() {
        given:
        enableWrite()
        def calls = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            calls++
            calls == 1 ? [success: true, id: 5, version: 1] : [success: false, message: 'unexpected token: } @ line 2']
        }

        when:
        def result = script.toolCreateDriver([installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        hubGet.calls.isEmpty()
        result.success == false
        result.installs[0] == [driverId: '5', success: true, version: 1, sourceMode: 'source', sourceLength: 1]
        result.installs[1].success == false
        !result.installs[1].containsKey('retryable')
    }

    @spock.lang.Unroll
    def "#tool rejects a misspelled argument before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def posted = false
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posted = true; [:] }

        when:
        def response = mcpDriver.callTool(tool, [source: 'code', sourcefile: 'x.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown')
        mcpDriver.parseInner(response).error.contains('sourcefile')
        !posted
        hubGet.calls.isEmpty()

        where:
        tool                 | useGateways
        'hub_create_app'     | true
        'hub_create_driver'  | false
        'hub_create_library' | true
    }

    @spock.lang.Unroll
    def "#tool refuses #arg, which belongs to another create tool, before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def posted = false
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posted = true; [:] }

        when:
        def response = mcpDriver.callTool(tool, [source: 'code', confirm: true] + extra)

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("Unknown ${tool} argument: ${arg}")
        !posted
        hubGet.calls.isEmpty()

        where:
        tool                 | arg         | extra                       | useGateways
        'hub_create_driver'  | 'codeAppId' | [codeAppId: 5]              | true
        'hub_create_library' | 'codeAppId' | [codeAppId: 5]              | false
        'hub_create_app'     | 'installs'  | [installs: [[source: 'a']]] | false
        'hub_create_library' | 'installs'  | [installs: [[source: 'a']]] | true
    }

    def "a bulk entry with an unknown key is refused for that entry and the others still install"() {
        given:
        enableWrite()
        def calls = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            calls++
            [success: true, id: 5, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [[source: 'a', sourcefile: 'x.groovy'], [source: 'b']], confirm: true])

        then:
        calls == 1
        result.success == false
        result.installs[0].success == false
        result.installs[0].driverId == null
        result.installs[0].error.contains('Unknown installs[] argument: sourcefile')
        result.installs[0].note.contains("section='hub_admin_write_code'")
        result.installs[1].success == true
    }

    def "a bulk entry whose only source key is misspelled is named as unknown, and a non-object entry still needs a source"() {
        given:
        enableWrite()
        def calls = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> calls++; [success: true, id: 5, version: 1] }

        when:
        def result = script.toolCreateDriver([installs: [[sorce: 'a'], 'not-an-object'], confirm: true])

        then:
        calls == 0
        result.success == false
        result.installs[0].error.startsWith('Unknown installs[] argument: sorce.')
        result.installs[1].error == "Each installs entry must have a 'source', 'sourceFile', or 'importUrl' field."
    }

    def "a bulk update entry whose driverId key is misspelled is named as unknown, and a non-object entry still needs a driverId"() {
        given:
        enableWrite()
        def calls = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> calls++; [success: true, id: 5, version: 2] }

        when:
        def result = script.toolUpdateDriverCode([confirm: true, updates: [[driverid: '55', source: 'new'], 'not-an-object']])

        then:
        calls == 0
        result.updates[0].driverId == 'item[0]'
        result.updates[0].error.startsWith('Unknown updates[] argument: driverid.')
        result.updates[1].error == "Each updates entry must have a 'driverId' field."
    }

    // -------- one delete path --------

    @spock.lang.Unroll
    def "hub_delete_item rejects the unknown #param argument (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '1', (param): '1', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("argument: ${param}.".toString())
        hubGet.calls.isEmpty()

        where:
        param   | useGateways
        'id'    | true
        'appId' | false
    }

    @spock.lang.Unroll
    def "hub_delete_item requires a valid type, named in the refusal, before any hub call: #label"() {
        given:
        enableWrite()

        when:
        script.toolDeleteItem(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('type')
        ex.message.contains('app, driver, library')
        hubGet.calls.isEmpty()
        backupIndex.isEmpty()

        where:
        label          | args
        'missing type' | [item_id: '5', confirm: true]
        'unknown type' | [type: 'bundle', item_id: '5', confirm: true]
    }

    @spock.lang.Unroll
    def "hub_delete_item rejects item_id '#badId' before any hub call"() {
        given:
        enableWrite()

        when:
        script.toolDeleteItem([type: 'driver', item_id: badId, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')
        hubGet.calls.isEmpty()

        where:
        badId << ['abc', '0', '-3', '1.5', null, ' 999999', '999999 ', '+999999', '2147483648', '']
    }

    def "hub_delete_item uses the parsed id for the backup key, the hub path and the result"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/driver/editor/deleteJson/12') { params -> '{"status":true}' }

        when:
        def result = script.toolDeleteItem([type: 'driver', item_id: '012', confirm: true])

        then:
        result.success == true
        result.id == '12'
        hubGet.calls.find { it.path == '/driver/ajax/code' }.params == [id: '12']
        hubGet.calls.any { it.path == '/driver/editor/deleteJson/12' }
        backupIndex.containsKey('driver_12')
    }

    def "deleting a missing app id within an hour of a backup of that id reports success"() {
        given: 'a backup of app 999 taken a minute ago, and an app the hub no longer has'
        enableWrite()
        backupIndex = [app_999: [type: 'app', id: '999', fileName: 'mcp-backup-app-999.groovy', version: 3,
                                 timestamp: 1234567890000L - 60_000L, sourceLength: 10]]
        hubGet.register('/app/ajax/code') { params -> '{"id":null,"name":null,"version":null,"source":null,"status":"success"}' }
        hubGet.register('/app/edit/deleteJsonSafe/999') { params -> '{"status":true}' }

        when:
        def result = script.toolDeleteItem([type: 'app', item_id: '999', confirm: true])

        then: 'the reused backup skips the source read, and the hub accepts the delete of a missing app'
        result.success == true
        result.backupFile == 'mcp-backup-app-999.groovy'
        !hubGet.calls.any { it.path == '/app/ajax/code' }
        hubGet.calls.any { it.path == '/app/edit/deleteJsonSafe/999' }
    }

    @spock.lang.Unroll
    def "deleting an app id that does not exist is refused with no delete request (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"id":null,"name":null,"version":null,"source":null,"status":"success"}' }
        hubGet.register('/app/edit/deleteJsonSafe/999') { params -> '{"status":true}' }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '999', confirm: true]))

        then:
        inner.success == false
        inner.error == 'App 999 does not exist'
        inner.note.contains('hub_list_apps')
        !hubGet.calls.any { it.path.contains('deleteJsonSafe') }

        where:
        useGateways << [true, false]
    }

    def "a driver whose source cannot be read is refused before the delete: it may not exist"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params -> throw new RuntimeException('HTTP 500') }
        hubGet.register('/driver/editor/deleteJson/999') { params -> '{"status":true}' }

        when:
        def result = script.toolDeleteItem([type: 'driver', item_id: '999', confirm: true])

        then:
        result.success == false
        result.error.startsWith('Could not read the source of driver 999')
        result.error.contains('may not exist')
        result.id == '999'
        result.type == 'driver'
        result.note.startsWith('Nothing was deleted')
        result.note.contains('hub_list_drivers')
        !hubGet.calls.any { it.path == '/driver/editor/deleteJson/999' }
    }

    def "a delete the hub refuses with a bare status:false says the driver may not exist"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/driver/editor/deleteJson/998') { params -> '{"status":false}' }

        when:
        def result = script.toolDeleteItem([type: 'driver', item_id: '998', confirm: true])

        then:
        result.success == false
        result.error.contains('without giving a reason')
        result.error.contains('may not exist')
        result.response == '{"status":false}'
        result.note.contains('hub_list_drivers')
        !result.note.contains('another driver')
    }

    def "a backup that cannot be written refuses the delete and says to retry once File Manager accepts writes"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('disk full') }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/driver/editor/deleteJson/997') { params -> '{"status":true}' }

        when:
        def result = script.toolDeleteItem([type: 'driver', item_id: '997', confirm: true])

        then:
        result.success == false
        result.error.startsWith('Pre-delete backup failed for driver 997')
        result.error.contains('disk full')
        result.note == 'Nothing was deleted. Retry once File Manager accepts writes.'
        !hubGet.calls.any { it.path == '/driver/editor/deleteJson/997' }
    }

    def "a missing library is refused at the backup read with no delete request and no in-use note"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/5') { params -> '[]' }
        hubGet.register('/library/edit/deleteJson/5') { params -> '{"status":false,"message":"No library found for id 5"}' }

        when:
        def result = script.toolDeleteItem([type: 'library', item_id: '5', confirm: true])

        then:
        result.success == false
        result.error == 'Library 5 does not exist'
        result.note.contains('hub_list_libraries')
        !result.note.contains('#include')
        !hubGet.calls.any { it.path == '/library/edit/deleteJson/5' }
    }

    @spock.lang.Unroll
    def "a #type delete with an unrecognizable reply fails closed: '#body'"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/library/list/single/data/8') { params -> '[{"id":8,"version":1,"source":"x"}]' }
        hubGet.register(path) { params -> body }

        when:
        def result = script.toolDeleteItem([type: type, item_id: '8', confirm: true])

        then:
        result.success == false
        result.error.contains('Could not confirm')
        result.response == body
        result.note.contains(listCall)

        where:
        type      | path                           | body                           | listCall
        'app'     | '/app/edit/deleteJsonSafe/8'   | '<html>Deleted</html>'         | 'hub_list_apps'
        'driver'  | '/driver/editor/deleteJson/8'  | 'ok'                           | 'hub_list_drivers'
        'library' | '/library/edit/deleteJson/8'   | '[]'                           | 'hub_list_libraries'
        'driver'  | '/driver/editor/deleteJson/8'  | '{"unrelated":true}'           | 'hub_list_drivers'
    }

    @spock.lang.Unroll
    def "a refused #type delete gets the in-use note only when the hub says the item is in use: '#message'"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","version":1,"source":"x"}' }
        hubGet.register(path) { params -> groovy.json.JsonOutput.toJson([status: false, (key): message]) }

        when:
        def result = script.toolDeleteItem([type: type, item_id: '8', confirm: true])

        then:
        result.success == false
        result.error == message
        result.note.contains(inUse ? inUseText : listCall)
        !result.note.contains(inUse ? listCall : inUseText)

        where:
        // The app delete names its reason in message; the driver delete in error.
        type     | path                          | key       | message                                                     | inUse | inUseText        | listCall
        'app'    | '/app/edit/deleteJsonSafe/8'  | 'message' | 'App is still in use by 1 app'                              | true  | 'app instances'  | 'hub_list_apps'
        'app'    | '/app/edit/deleteJsonSafe/8'  | 'message' | 'Something else went wrong'                                 | false | 'app instances'  | 'hub_list_apps'
        'driver' | '/driver/editor/deleteJson/8' | 'error'   | 'Driver is in use by the following devices: [ Lamp (12) ]' | true  | 'another driver' | 'hub_list_drivers'
    }

    // -------- toolCreateApp / toolCreateDriver --------

    def "hub_create_app throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateApp([source: 'definition(name: "X")'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns isError validation result envelope when confirm is not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "X")'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')

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
    def "hub_create_app via dispatch returns an isError validation result when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "X")', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app throws when neither source nor sourceFile is provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateApp([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns isError validation result envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app throws when both source and sourceFile are provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateApp([source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns isError validation result envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'code', sourceFile: 'app.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('exactly one')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app (source mode) POSTs to /app/saveOrUpdateJson with no read afterwards, and extracts the new appId"() {
        given:
        enableWrite()
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            captured.path = path
            captured.body = body
            [success: true, id: 4242, version: 1]
        }

        when:
        def result = script.toolCreateApp([source: 'definition(name: "Hello")', confirm: true])

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
        result.version == 1
        !result.containsKey('verified')
        !result.containsKey('verifyError')
        hubGet.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch (source mode) POSTs and extracts appId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            captured.path = path
            captured.body = body
            [success: true, id: 4242, version: 1]
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 5555, version: 1]
        }

        when:
        def result = script.toolCreateApp([sourceFile: 'my-app.groovy', confirm: true])

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 5555, version: 1]
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
        script.toolCreateApp([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    def "a sourceFile the hub throws for is reported as not found in File Manager, on create and update"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> throw new RuntimeException("read of ${fileName} failed: disk busy") }
        def posted = false
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posted = true; [:] }

        when:
        def response = mcpDriver.callTool(tool, args + [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("Source file 'missing.groovy' not found in File Manager")
        mcpDriver.parseInner(response).error.contains("(the hub's read failed: read of missing.groovy failed: disk busy)")
        !posted

        where:
        tool                | args
        'hub_create_app'    | [:]
        'hub_update_driver' | [driverId: '55', backup: false]
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns isError validation result envelope when sourceFile is absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_create_app', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app returns success=false when the hub rejects the create (success!=true)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def result = script.toolCreateApp([source: 'definition(name: "NoLoc")', confirm: true])

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'definition(name: "NoLoc")', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.appId == null
        inner.error.contains('installation failed')
        inner.error.contains('NoLoc rejected')

        where:
        useGateways << [true, false]
    }

    def "hub_create_app reports failure when the hub POST throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('compile error: bad syntax')
        }

        when:
        def result = script.toolCreateApp([source: 'bad', confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('compile error')
        result.note.contains('before retrying')
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('compile error: bad syntax')
        }

        when:
        def response = mcpDriver.callTool('hub_create_app', [source: 'bad', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('compile error')
        inner.note.contains('before retrying')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_app via dispatch returns isError validation result envelope when bulk-mode installs[] (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [installs: [[source: 'a'], [source: 'b']], confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_create_app argument: installs')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver (source mode) POSTs to /driver/saveOrUpdateJson and returns the new driverId"() {
        given:
        enableWrite()
        def postedPath = null
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            postedPath = path
            [success: true, id: 9001, version: 1]
        }

        when:
        def result = script.toolCreateDriver([source: 'metadata { }', confirm: true])

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            postedPath = path
            [success: true, id: 9001, version: 1]
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 7777, version: 1]
        }

        when:
        def result = script.toolCreateDriver([sourceFile: 'my-driver.groovy', confirm: true])

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 7777, version: 1]
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
    def "hub_create_driver via dispatch returns an isError validation result when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateDriver([source: 'metadata { }'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns isError validation result envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when neither source nor sourceFile is provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateDriver([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns isError validation result envelope when neither source nor sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when both source and sourceFile are provided"() {
        given:
        enableWrite()

        when:
        script.toolCreateDriver([source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns isError validation result envelope when both source and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', sourceFile: 'driver.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('exactly one')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver throws when sourceFile is not found in File Manager"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolCreateDriver([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch returns isError validation result envelope when sourceFile not found in File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver returns success=false when the hub rejects the create (success!=true)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def result = script.toolCreateDriver([source: 'metadata { }', confirm: true])

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'NoLoc rejected']
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'metadata { }', confirm: true])

        then:
        response.error == null
        response.result.isError == true
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('compile error: syntax problem')
        }

        when:
        def result = script.toolCreateDriver([source: 'bad', confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('compile error')
        result.note.contains('before retrying')
    }

    @spock.lang.Unroll
    def "hub_create_driver via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('compile error: syntax problem')
        }

        when:
        def response = mcpDriver.callTool('hub_create_driver', [source: 'bad', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('compile error')
        inner.note.contains('before retrying')

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
    def "hub_create_driver bulk via dispatch returns an isError validation result when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_driver', [installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode throws when both installs and sourceFile are supplied"() {
        given:
        enableWrite()

        when:
        script.toolCreateDriver([sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('bulk mode')
        ex.message.contains('source')
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch returns isError validation result envelope when both installs and sourceFile (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [sourceFile: 'x.groovy', installs: [[sourceFile: 'f.groovy']], confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('bulk mode')
        mcpDriver.parseInner(response).error.contains('source')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode throws when installs is an empty array"() {
        given:
        enableWrite()

        when:
        script.toolCreateDriver([installs: [], confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must not be empty')
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch returns isError validation result envelope when installs is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_driver', [installs: [], confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('must not be empty')

        where:
        useGateways << [true, false]
    }

    def "hub_create_driver bulk mode happy path: 3 drivers all succeed, per-item driverIds returned"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.downloadHubFile = { String fileName -> 'metadata { }'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 9000 + callNum, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 9000 + callNum, version: 1]
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 8000 + callNum, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 8000 + callNum, version: 1]
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
        response.result.isError == true
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 8500 + callNum, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            [success: true, id: 8500 + callNum, version: 1]
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 8900, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [[source: 'metadata { }']],
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 8900, version: 1]
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 9100, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 9100, version: 1]
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
        response.result.isError == true
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

    def "hub_create_driver bulk mode: a per-item hub refusal propagates error, note, and lastBackup on the failed entry"() {
        given:
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            // the middle item is refused at save, as the hub does for a compile failure
            callNum == 2 ? [success: false, message: 'compile failed: bad syntax'] : [success: true, id: 9400 + callNum, version: 1]
        }

        when:
        def result = script.toolCreateDriver([installs: [
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

        and: 'failed entry propagates error, note, and lastBackup from the inner failure return'
        result.installs[1].error != null
        result.installs[1].error.toLowerCase().contains('compile failed')
        result.installs[1].note != null
        result.installs[1].note == 'Fix the source and retry.'
        result.installs[1].lastBackup != null
    }

    @spock.lang.Unroll
    def "hub_create_driver bulk via dispatch a per-item hub refusal propagates note and lastBackup (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def callNum = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            callNum++
            // the middle item is refused at save, as the hub does for a compile failure
            callNum == 2 ? [success: false, message: 'compile failed: bad syntax'] : [success: true, id: 9400 + callNum, version: 1]
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
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.installs.size() == 3
        inner.installs[0].success == true
        inner.installs[1].success == false
        inner.installs[2].success == true
        inner.installs[1].error != null
        inner.installs[1].error.toLowerCase().contains('compile failed')
        inner.installs[1].note != null
        inner.installs[1].note == 'Fix the source and retry.'
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
    def "hub_update_app via dispatch returns isError validation result envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', source: 'x'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')

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
    def "hub_update_app via dispatch returns isError validation result envelope when appId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [source: 'x', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('appId is required')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
    def "hub_update_app via dispatch returns isError validation result envelope when none of source/sourceFile/resave supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains("'source'")
        mcpDriver.parseInner(response).error.contains("'sourceFile'")
        mcpDriver.parseInner(response).error.contains("'resave'")

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex?.containsKey('app_50')
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posted = path; [success: true] }

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: true, id: 50] }
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: true, id: 60] }
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: false, error: 'compile boom'] }
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex?.containsKey('app_50')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
    def "hub_update_app via dispatch returns isError validation result envelope when sourceFile absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '60', sourceFile: 'missing.groovy', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('not found in File Manager')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'Compilation failed']
        }

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '80', source: 'broken', confirm: true])

        then:
        response.error == null
        response.result.isError == true
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [[odd: 'list']] }

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
    def "hub_update_app via dispatch refuses bulk-mode updates[] as an unknown argument (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_app', [
            updates: [[appId: '1', source: 'a'], [appId: '2', source: 'b']],
            confirm: true
        ])

        then: 'public dispatch refuses the unknown argument; the direct-call check above keeps its own message'
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_update_app argument: updates')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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

    def "hub_update_driver returns a compile error without the hub's markup"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 4, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: "boom on line <span id='errorLine'>1</span>"]
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        result.success == false
        result.error == 'boom on line 1'
    }

    def "hub_update_driver maps the collision answer to a retryable note, not a syntax hint"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 4, "source": "metadata { }"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, message: 'java.sql.SQLException: Failed to update device type']
        }

        when:
        def result = script.toolUpdateDriverCode([driverId: '55', source: 'metadata { v2 }', confirm: true])

        then:
        result.success == false
        result.error.contains('SQLException')
        result.retryable == true
        result.note.contains('retry')
        result.note.contains("section='hub_admin_write_code'")
        !result.note.contains('syntax')
        !result.containsKey('conflict')
    }

    @spock.lang.Unroll
    def "only the path's own collision answer is retryable: #label"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "old"}' }
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: false, message: message] }

        when:
        def result = type == 'driver' ? script.toolUpdateDriverCode([driverId: '55', source: 'new', confirm: true])
                                      : script.toolUpdateAppCode([appId: '55', source: 'new', confirm: true])

        then:
        result.success == false
        result.containsKey('retryable') == retryable
        result.note.contains('syntax') == !retryable

        where:
        label                     | type     | message                                                        | retryable
        'app compile path'        | 'app'    | './appTargetDir/user_app_mcptest_My_App_55.groovy'             | true
        'driver database error'   | 'driver' | 'java.sql.SQLException: Failed to update device type'          | true
        'missing import, driver'  | 'driver' | 'unable to resolve class SQLException'                         | false
        'missing import, app'     | 'app'    | 'unable to resolve class SQLException'                         | false
        'other database error'    | 'driver' | 'java.sql.SQLException: disk full'                             | false
        'database answer on app'  | 'app'    | 'java.sql.SQLException: Failed to update device type'          | false
        'path answer on driver'   | 'driver' | './appTargetDir/user_app_mcptest_My_App_55.groovy'             | false
        'path inside a longer one'| 'app'    | './appTargetDir/user_app_a_b_55.groovy: 3: unexpected token'   | false
    }

    def "bulk hub_update_driver carries retryable on the collision entry, and a note on an unknown key"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            posts << path
            [success: false, message: 'java.sql.SQLException: Failed to update device type']
        }

        when:
        def result = script.toolUpdateDriverCode([confirm: true, updates: [
            [driverId: '55', source: 'new'],
            [driverId: '56', source: 'new', expectedVerison: 4]
        ]])

        then: 'the misspelled lock posted nothing, and its refusal names the keys the schema declares'
        posts.size() == 1
        result.updates[0].retryable == true
        result.updates[1].error.contains('Unknown updates[] argument: expectedVerison')
        result.updates[1].error.contains('driverId, sourceFile, source, importUrl, resave, backup, expectedVersion')
        result.updates[1].note.contains("section='hub_admin_write_code'")
    }

    def "a stale lock on the server's own app is a conflict before any post, not a confirmed save"() {
        given: 'Developer Mode on and the code row at 174'
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 174, "source": "self"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass._resolveSelfAppClassId = { -> 50 }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << body; null }

        when:
        def result = script.toolUpdateAppCode([appId: '50', resave: true, expectedVersion: 173, confirm: true])

        then:
        posts.isEmpty()
        result.success == false
        result.conflict == true
        result.currentVersion == 174
        !result.verified
        atomicStateMap.lastSelfDeploy == null
    }

    def "a self save whose version cannot be read is refused, never started from a reused backup's version"() {
        given: 'a backup from the last hour at 5, and no readable version'
        enableWrite()
        settingsMap.enableDeveloperMode = true
        stateMap.lastBackupTimestamp = 1234567890000L
        backupIndex = [app_50: [type: 'app', id: '50', fileName: 'mcp-backup-app-50.groovy', version: 5,
                                timestamp: 1234567890000L - 60_000L, sourceLength: 100]]
        hubGet.register('/app/ajax/code') { params -> throw new RuntimeException('hub busy') }
        script.metaClass._resolveSelfAppClassId = { -> 50 }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << body; null }

        when:
        script.toolUpdateAppCode([appId: '50', source: 'new', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Could not determine current version')
        posts.isEmpty()
    }

    def "a lock whose current version cannot be read is refused, not compared with a reused backup's version"() {
        given:
        enableWrite()
        stateMap.lastBackupTimestamp = 1234567890000L
        backupIndex = [app_50: [type: 'app', id: '50', fileName: 'mcp-backup-app-50.groovy', version: 5,
                                timestamp: 1234567890000L - 60_000L, sourceLength: 100]]
        hubGet.register('/app/ajax/code') { params -> throw new RuntimeException('hub busy') }
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << body; [success: true] }

        when:
        script.toolUpdateAppCode([appId: '50', source: 'new', expectedVersion: 5, confirm: true])

        then:
        thrown(IllegalArgumentException)
        posts.isEmpty()
    }

    def "the code create and update tools point at the code section of the guide"() {
        expect:
        ['hub_create_app', 'hub_create_driver', 'hub_create_library', 'hub_update_app', 'hub_update_driver', 'hub_update_library']
            .every { script._guideSectionForTool(it) == 'hub_admin_write_code' }
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
    def "hub_update_driver bulk via dispatch returns an isError validation result when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_update_driver', [updates: [[driverId: '1', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

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
    def "hub_update_driver bulk via dispatch returns isError validation result envelope when both updates and driverId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_driver', [driverId: '10', updates: [[driverId: '20', sourceFile: 'f.groovy']], confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('bulk mode')
        mcpDriver.parseInner(response).error.contains('driverId')

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
    def "hub_update_driver bulk via dispatch returns isError validation result envelope when updates is empty array (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_driver', [updates: [], confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('must not be empty')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        response.result.isError == true
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        response.result.isError == true
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
    def "hub_delete_item (app) via dispatch returns isError validation result envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '1'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')

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
    def "hub_delete_item (app) via dispatch returns an isError validation result when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', item_id: '1', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) throws when item_id is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteItem([type: 'app', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('item_id is required')
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch returns isError validation result envelope when item_id missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'app', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('item_id is required')

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
        result.id == '33'
        result.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        result.restoreHint.contains('hub_create_app')

        and: 'the response states the eviction rule and no retention kind'
        !result.containsKey('backupRetention')
        result.backupRetentionNote == script._itemBackupRetentionNote()
        result.restoreHint.endsWith(result.backupRetentionNote)
        result.restoreHint.contains('10 MB storage budget')
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
        inner.id == '33'
        inner.backupFile == 'mcp-backup-app-33.groovy'
        uploads == ['mcp-backup-app-33.groovy']
        inner.restoreHint.contains('hub_create_app')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_item (app) sends no delete when the pre-delete backup fails"() {
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

        then: 'the backup failure refuses the delete before it is sent'
        result.success == false
        result.error.startsWith('Could not read the source of')
        result.note.startsWith('Nothing was deleted')
        !hubGet.calls.any { it.path == '/app/edit/deleteJsonSafe/44' }
    }

    @spock.lang.Unroll
    def "hub_delete_item (app) via dispatch sends no delete when the pre-delete backup fails (useGateways=#useGateways)"() {
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
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.startsWith('Could not read the source of')
        inner.note.startsWith('Nothing was deleted')
        !hubGet.calls.any { it.path == '/app/edit/deleteJsonSafe/44' }

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
        result.error == 'in use'
        result.id == '55'
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
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error == 'in use'
        inner.id == '55'

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
        result.id == '77'
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
        inner.id == '77'
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
    def "hub_restore_backup via dispatch returns isError validation result envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_1'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')

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
    def "hub_restore_backup via dispatch returns isError validation result envelope when backupKey missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('backupKey is required')

        where:
        useGateways << [true, false]
    }

    def "hub_restore_backup returns error response when the key is unknown"() {
        given:
        enableWrite()
        backupIndex = [
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
        backupIndex = [
            'app_existing': [type: 'app', id: '1', fileName: 'f.groovy',
                             version: 1, timestamp: 1L, sourceLength: 0]
        ]

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_missing', confirm: true])

        then:
        response.error == null
        response.result.isError == true
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
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]

        and: 'downloadHubFile returns the backup contents'
        def files = ['mcp-backup-app-99.groovy': 'old source v4'.getBytes('UTF-8')]
        script.metaClass.downloadHubFile = { String fileName ->
            files.get(fileName)
        }

        and: 'current on-hub source fetch + version lookup (both hit /app/ajax/code)'
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }

        and: 'uploadHubFile records pre-restore backup writes'
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
            files.put(name, content)
        }

        and: 'hubInternalPostJson captures the save call and returns success'
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        result.undoAvailable == true
        result.preRestoreBackup == 'prerestore_app_99'
        result.undoHint.contains('prerestore_app_99')

        and: 'both the pre-restore entry and the original backup entry are preserved (original kept so the user can restore again if needed)'
        backupIndex.containsKey('prerestore_app_99')
        backupIndex.containsKey('app_99')

        when: 'a client reads the generated undo handle as a JSON String'
        def undo = script.toolGetItemBackup([backupKey: result.preRestoreBackup.toString()])

        then:
        undo.error == null
        undo.source == 'current source on hub'
        undo.fileName == 'mcp-prerestore-app-99.groovy'
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch reports a failed pre-restore capture in the success channel (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mcp-backup-app-99.groovy' ? 'old source v4'.getBytes('UTF-8') : null
        }
        hubGet.register('/app/ajax/code') { params -> null }
        def writes = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> writes << path; [success: true, id: 99] }

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'app_99', confirm: true])

        then: 'the abort is a structured tool result, not a protocol error'
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('pre-restore backup')
        inner.note.contains('nothing needs undoing')
        writes.isEmpty()
        backupIndex.keySet() == ['app_99'] as Set

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch reads backup creates pre-restore copy and pushes to hub (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        def files = ['mcp-backup-app-99.groovy': 'old source v4'.getBytes('UTF-8')]
        script.metaClass.downloadHubFile = { String fileName -> files.get(fileName) }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name; files.put(name, content) }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        inner.undoAvailable == true
        inner.preRestoreBackup == 'prerestore_app_99'
        inner.undoHint.contains('prerestore_app_99')
        backupIndex.containsKey('prerestore_app_99')
        backupIndex.containsKey('app_99')

        where:
        useGateways << [true, false]
    }

    def "hub_restore_backup reports failure and preserves the backup when the hub POST fails"() {
        given:
        enableWrite()
        backupIndex = [
            'driver_88': [type: 'driver', id: '88', fileName: 'mcp-backup-driver-88.groovy',
                          version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'backup bytes'.getBytes('UTF-8')])
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        result.message.contains('Check the live source') && result.message.contains('hub_get_backup')
        backupIndex.containsKey('driver_88')
    }

    @spock.lang.Unroll
    def "hub_restore_backup via dispatch reports failure envelope and preserves backup when hub POST fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        backupIndex = [
            'driver_88': [type: 'driver', id: '88', fileName: 'mcp-backup-driver-88.groovy',
                          version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'backup bytes'.getBytes('UTF-8')])
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            captured.path = path
            [success: false, message: 'bad code']
        }

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'driver_88', confirm: true])

        then:
        captured.path == '/driver/saveOrUpdateJson'
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('bad code')
        inner.message.contains('Check the live source') && inner.message.contains('hub_get_backup')
        backupIndex.containsKey('driver_88')

        where:
        useGateways << [true, false]
    }

    def "hub_restore_backup fails closed on an empty hub response (non-self restore)"() {
        given:
        enableWrite()
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'old source v4'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'failure is reported and the backup entry is preserved for retry'
        result.success == false
        result.error.contains('Empty response from /app/saveOrUpdateJson')
        result.message.contains('Check the live source') && result.message.contains('hub_get_backup')
        backupIndex.containsKey('app_99')
    }

    def "hub_restore_backup self-restore treats an empty response as assumed success and stashes lastSelfDeploy"() {
        // Restoring the MCP server's OWN code drops the response exactly like a self-update
        // (the recompile kills the in-flight request), so the empty-body leniency applies and
        // the outcome is recoverable from atomicState.lastSelfDeploy via hub_get_info.
        given:
        enableWrite()

        and: 'backup entry id matches app.id (sharedAppStub id 1); class-id lookup resolves nothing'
        backupIndex = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'self backup source'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }

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
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'old source v4'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }

        and: 'saveOrUpdateJson is an upsert: success with a different echoed id means it saved elsewhere'
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: true, id: 777]
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'reported as failure naming both ids; backup entry preserved for retry'
        result.success == false
        result.error.contains('duplicate')
        result.error.contains('777')
        result.error.contains('99')
        backupIndex.containsKey('app_99')
    }

    def "hub_restore_backup self-restore is detected via the Apps Code CLASS id (the real CI deploy path), not just the instance id"() {
        // A code restore targets the Apps Code CLASS id (e.g. 178), which differs from app.id
        // (the running instance, 1). The REAL _resolveSelfAppClassId runs here against a stubbed
        // /hub2/userAppTypes -- if detection only checked the instance id, this self-restore
        // would fail closed instead of getting the empty-body leniency.
        given:
        enableWrite()
        backupIndex = [
            'app_178': [type: 'app', id: '178', fileName: 'mcp-backup-app-178.groovy',
                        version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'self backup source'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }

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
        backupIndex = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'self backup source'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'self backup source'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }

        and: 'the hub rejects the save with its real compile error'
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'old source v4'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            [success: false, foo: 'bar']
        }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then: 'the raw response rides in the error instead of a null/blank message'
        result.success == false
        result.error.contains('hub response lacked success=true')
        result.error.contains('foo')
        backupIndex.containsKey('app_99')
    }

    def "hub_restore_backup success without an echoed id is accepted (id-echo guard tolerates a missing id)"() {
        given:
        enableWrite()
        backupIndex = [
            'app_99': [type: 'app', id: '99', fileName: 'mcp-backup-app-99.groovy',
                       version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'old source v4'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current source on hub"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex = [
            'driver_1': [type: 'driver', id: '1', fileName: 'mcp-backup-driver-1.groovy',
                         version: 2, timestamp: 1_234_000_000_000L, sourceLength: 10]
        ]
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'driver backup'.getBytes('UTF-8')])
        hubGet.register('/driver/ajax/code') { params ->
            '{"status": "ok", "version": 3, "source": "current"}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }

        when:
        def result = script.toolRestoreItemBackup([backupKey: 'driver_1', confirm: true])

        then: 'fails closed on the DRIVER save path -- no leniency bleed across types'
        result.success == false
        result.error.contains('Empty response from /driver/saveOrUpdateJson')
        backupIndex.containsKey('driver_1')

        and: 'no self-deploy stash was written'
        atomicStateMap.lastSelfDeploy == null
    }

    def "hub_restore_backup self-restore with a Map-confirmed success carries no assumed flag (hub-confirmed, not inferred)"() {
        given:
        enableWrite()
        backupIndex = [
            'app_1': [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                      version: 4, timestamp: 1_234_000_000_000L, sourceLength: 50]
        ]
        script.metaClass._resolveSelfAppClassId = { -> null }
        stubSourceFiles([(backupIndex.values().first().fileName.toString()): 'self backup source'.getBytes('UTF-8')])
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 9, "source": "current self source"}'
        }

        and: 'the response survived the recompile and confirms the save (matching echoed id)'
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex = [
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

    // -------- hub_update_app / hub_update_driver: expectedVersion, checked by the hub on save --------

    // Stands in for /app|driver/saveOrUpdateJson: the hub refuses a save whose version is not the
    // item's current one, writing nothing. Every POST body is recorded.
    private Closure versionCheckingSave(Map hubVersions, List posts) {
        return { String path, String body, int timeout = 420 ->
            def b = new groovy.json.JsonSlurper().parseText(body)
            posts << b
            def cur = hubVersions[b.id?.toString()]
            if (cur != null && b.version != cur) {
                return [success: false, message: "Version does not match, attempting to update version ${b.version}, current version is ${cur}".toString()]
            }
            if (cur != null) hubVersions[b.id.toString()] = cur + 1
            [success: true, id: b.id]
        }
    }

    private boolean versionReadMade() {
        hubGet.calls.any { it.path in ['/app/list/all/data', '/driver/list/data'] }
    }

    def "hub_update_app sends a matching expectedVersion as the save version with no separate version read"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 42, "source": "old source"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 42], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new source', expectedVersion: 42, confirm: true])

        then:
        posts*.version == [42]
        result.success == true
        result.previousVersion == 42

        and: 'the only code read is the backup; no list or extra ajax/code version read'
        !versionReadMade()
        hubGet.calls.count { it.path == '/app/ajax/code' } == 1
    }

    def "hub_update_app with a stale expectedVersion returns the conflict before posting"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 50, "source": "current source"}' }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 50], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'stale', expectedVersion: 42, confirm: true])

        then: 'nothing is posted: the backup taken in this call supplied the current version'
        posts.isEmpty()
        !versionReadMade()

        and: 'conflict result carries both versions and the conflict flag'
        result.success == false
        result.conflict == true
        result.expectedVersion == 42
        result.currentVersion == 50
        result.error.toLowerCase().contains('optimistic-lock conflict')
        result.error.contains('retry with the new expectedVersion')
        result.appId == '50'

        and: 'backup IS taken even on conflict (the 1h reuse makes a retry free)'
        uploads == ['mcp-backup-app-50.groovy']
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch surfaces a stale lock as a conflict (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 50, "source": "current source"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 50], posts)

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '50', source: 'stale', expectedVersion: 42, confirm: true])

        then: 'nothing posted; conflict is a tool error result, not a JSON-RPC error'
        posts.isEmpty()
        response.error == null
        response.result.isError == true

        and: 'inner payload carries the conflict triple'
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.conflict == true
        inner.expectedVersion == 42
        inner.currentVersion == 50

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "expectedVersion refuses #value before any hub call"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: value, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion must be an integer')

        and: 'rejected before the backup or any read'
        uploads.isEmpty()
        !hubGet.calls.any { it.path == '/app/ajax/code' }

        where:
        value << [2.9, 0.9, 1.5, 3.0, '8', 'not-a-number', [bad: 1], true, -1, 4294967301L]
    }


    def "hub_update_app rejects explicit-null expectedVersion (silent-overwrite footgun)"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: null, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('expectedVersion was supplied as null')
        !hubGet.calls.any { it.path == '/app/ajax/code' }
    }

    def "hub_update_app with expectedVersion:0 against hub version 0 succeeds (no Groovy truthy short-circuit)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 0, "source": "v0 source"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 0], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'v1', expectedVersion: 0, confirm: true])

        then:
        result.success == true
        posts*.version == [0]
    }

    def "hub_update_app with expectedVersion:0 against hub version 1 returns conflict (proves containsKey is checked, not truthiness)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 1, "source": "v1"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 1], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 0, confirm: true])

        then:
        posts.isEmpty()
        result.conflict == true
        result.expectedVersion == 0
        result.currentVersion == 1
    }

    def "hub_update_app with expectedVersion + resave checks the lock against the resave read"() {
        given:
        enableWrite()
        def getCount = 0
        hubGet.register('/app/ajax/code') { params ->
            getCount++
            '{"status": "ok", "version": 7, "source": "on-hub"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        def hubVersions = ['50': 7]
        script.metaClass.hubInternalPostJson = versionCheckingSave(hubVersions, posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', resave: true, expectedVersion: 7, confirm: true])

        then: 'resave-fetch + backup-fetch only; no extra version GET'
        getCount == 2
        result.success == true
        posts*.version == [7]

        when:
        posts.clear()
        def conflict = script.toolUpdateAppCode([appId: '50', resave: true, expectedVersion: 99, confirm: true])

        then: 'the resave read (7) refuses 99 with no post'
        posts.isEmpty()
        conflict.conflict == true
        conflict.expectedVersion == 99
        conflict.currentVersion == 7
    }

    def "hub_update_app without expectedVersion uses a backup taken in this call and skips the version read"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 12, "source": "old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 12], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new', confirm: true])

        then:
        result.success == true
        posts*.version == [12]
        !versionReadMade()
        hubGet.calls.count { it.path == '/app/ajax/code' } == 1
    }

    def "hub_update_app without expectedVersion reads the list row when the backup is a reused one"() {
        given: 'a backup from a minute ago carrying a stale version'
        enableWrite()
        backupIndex = [app_50: [type: 'app', id: '50', fileName: 'mcp-backup-app-50.groovy', version: 3,
                                timestamp: 1234567890000L - 60_000L, sourceLength: 10]]
        hubGet.register('/app/list/all/data') { params -> '[{"id": 50, "version": 9}]' }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 9], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', source: 'new', confirm: true])

        then: 'the reused version is not trusted; the cheap list read supplies the current one'
        result.success == true
        posts*.version == [9]
        hubGet.calls.count { it.path == '/app/list/all/data' } == 1
    }

    def "hub_update_app without expectedVersion returns a refused stale save as a failure and never re-posts"() {
        given: 'the resave read sees v4, but another save lands before ours'
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "on-hub"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 5], posts)

        when:
        def result = script.toolUpdateAppCode([appId: '50', resave: true, confirm: true])

        then:
        posts*.version == [4]
        result.success == false
        result.conflict == true
        !result.containsKey('expectedVersion')
        result.error.contains('Another save of this app landed while this one was being sent')
        !result.error.contains('expected app version')
        result.currentVersion == 5
        result.note.contains("section='hub_admin_write_code'")
    }


    def "hub_update_app does not retry a stale refusal that names no current version"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "on-hub"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        int posts = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            posts++
            [success: false, message: 'VERSION DOES NOT MATCH']
        }

        when:
        def result = script.toolUpdateAppCode([appId: '50', resave: true, confirm: true])

        then:
        posts == 1
        result.conflict == true
        !result.containsKey('expectedVersion')
        !result.containsKey('currentVersion')
        result.error.contains('the hub reports a different version')
    }

    def "hub_update_app propagates POST failure with a clean envelope -- no conflict:true leakage on non-conflict failures"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 1, "source": "old"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        hubGet.register('/driver/ajax/code') { params -> '{"status": "ok", "version": 9, "source": "drv"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['77': 9], posts)

        when:
        def result = script.toolUpdateDriverCode([driverId: '77', source: 's', expectedVersion: 8, confirm: true])

        then:
        posts.isEmpty()
        !versionReadMade()
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
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['101': 10, '102': 20, '103': 30], posts)

        when: 'caller asserts wrong version on the middle entry only'
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '101', source: 'new-a', expectedVersion: 10],
                [driverId: '102', source: 'new-b', expectedVersion: 99],   // hub is at v20 -> conflict
                [driverId: '103', source: 'new-c', expectedVersion: 30]
            ],
            confirm: true
        ])

        then: 'each item saved once at its own version; the middle one never posted'
        posts.collect { [it.id, it.version] } == [[101, 10], [103, 30]]

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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

    def "hub_update_driver bulk mode refuses an entry with a misspelled lock instead of saving it unlocked; siblings still apply"() {
        given:
        enableWrite()
        hubGet.register('/driver/ajax/code') { params -> '{"status": "ok", "version": 4, "source": "metadata { }"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def postedIds = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            postedIds << new groovy.json.JsonSlurper().parseText(body).id?.toString()
            [success: true]
        }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [
                [driverId: '301', source: 'a', expectedVerison: 4],
                [driverId: '302', source: 'b']
            ],
            confirm: true
        ])

        then:
        postedIds == ['302']
        result.success == false
        result.updates[0].success == false
        result.updates[0].error.contains('Unknown updates[] argument: expectedVerison')
        result.updates[0].error.contains('Nothing was changed for this driver')
        result.updates[1].success == true
    }

    def "the strict code-update schemas declare bestPracticeKey, and bulk driver entries are strict too"() {
        when:
        def tools = script.getAllToolDefinitions().findAll { it.name in ['hub_update_app', 'hub_update_driver', 'hub_update_library'] }

        then:
        tools.size() == 3
        tools.every { it.inputSchema.additionalProperties == false && it.inputSchema.properties.bestPracticeKey?.type == 'string' }
        tools.find { it.name == 'hub_update_driver' }.inputSchema.properties.updates.items.additionalProperties == false
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
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['201': 5, '203': 8], posts)

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
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 5, "source": "src"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def posts = []
        script.metaClass.hubInternalPostJson = versionCheckingSave(['50': 5], posts)

        when: 'caller passes a Long (e.g. parser produced Long for an integer JSON literal)'
        def result = script.toolUpdateAppCode([appId: '50', source: 's', expectedVersion: 5L, confirm: true])

        then:
        result.success == true
        posts*.version == [5]
    }


    // -------- strict arguments (additionalProperties: false) --------

    @spock.lang.Unroll
    def "#tool rejects an unknown argument before any hub call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        int posts = 0
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts++; [success: true] }

        when: 'a misspelled lock that would otherwise be dropped silently'
        def response = mcpDriver.callTool(tool, args + [expectedVerison: 3, confirm: true])

        then:
        response.result.isError == true
        def error = mcpDriver.parseInner(response).error
        error.contains("Unknown ${tool} argument: expectedVerison")
        error.contains('Valid arguments:')
        error.contains('expectedVersion')
        posts == 0
        !hubGet.calls.any { it.path.contains('ajax/code') || it.path.contains('/library/') }

        where:
        tool                 | args                             | useGateways
        'hub_update_app'     | [appId: '50', source: 's']       | true
        'hub_update_app'     | [appId: '50', source: 's']       | false
        'hub_update_driver'  | [driverId: '77', source: 's']    | true
        'hub_update_driver'  | [driverId: '77', source: 's']    | false
        'hub_update_library' | [libraryId: '42', source: 's']   | true
        'hub_update_library' | [libraryId: '42', source: 's']   | false
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

    // appId is an Apps Code class id, which differs from the running instance id (1 here).
    @spock.lang.Unroll
    def "hub_update_app refuses canonical self id #targetId before fetching source or saving with Developer Mode OFF"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        def posts = []
        def downloads = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << path; [success: true] }
        script.metaClass.downloadHubFile = { String name -> downloads << name; 'self-overwrite'.getBytes('UTF-8') }

        when:
        script.toolUpdateAppCode([appId: targetId, sourceFile: 'probe.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("refuses to overwrite the MCP server's own app source")
        posts.isEmpty()
        downloads.isEmpty()

        where:
        targetId << ['178', '0178', '+178', '1', '01', '+1']
    }

    @spock.lang.Unroll
    def "hub_update_app refuses an unresolved self class before source or save (#lookup)"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        hubGet.register('/hub2/userAppTypes') { params ->
            if (lookup == 'transport') throw new RuntimeException('hub unavailable')
            return lookup
        }
        def downloads = []
        def posts = []
        script.metaClass.downloadHubFile = { String name -> downloads << name; 'source'.getBytes('UTF-8') }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << path; [success: true] }

        when:
        script.toolUpdateAppCode([appId: '178', sourceFile: 'probe.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('class id could not be resolved')
        downloads.isEmpty()
        posts.isEmpty()

        where:
        lookup << ['transport', '', '[]', '{}', 'not-json']
    }

    @spock.lang.Unroll
    def "hub_update_app via dispatch returns isError validation result envelope on self-update with Developer Mode off (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        settingsMap.enableDeveloperMode = false

        when:
        def response = mcpDriver.callTool('hub_update_app', [appId: '1', source: 'self-overwrite', confirm: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('self-update')
        mcpDriver.parseInner(response).error.contains('Developer Mode')

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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }
        script.metaClass._selfSaveWaitMs = { -> 0L }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v3', confirm: true])

        then:
        result.success == true
        result.assumed == true
        result.status == 'saving'
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.error == null
        atomicStateMap.lastSelfDeploy.assumed == true

        and: 'the record names what the App Code row must pass for the save to count as landed'
        atomicStateMap.lastSelfDeploy.status == 'saving'
        atomicStateMap.lastSelfDeploy.startedVersion == 5
        atomicStateMap.lastSelfDeploy.appId == '1'
    }

    def "hub_update_app self-update gives the save POST 20 s and treats its timeout as the dropped response, not a failure"() {
        // Issue #522: a 2.6 MB self save outruns the platform's 300 s request cap; the hub keeps
        // compiling after the caller stops waiting, so a timed-out POST is accepted and the App
        // Code row's version is what confirms it.
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 5, "source": "self source"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def seenTimeout = null
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            seenTimeout = timeout
            throw new RuntimeException('java.net.SocketTimeoutException: Read timed out')
        }
        script.metaClass._selfSaveWaitMs = { -> 0L }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v5', confirm: true])

        then:
        seenTimeout == 20
        result.success == true
        result.status == 'saving'
        result.note.contains('Poll hub_get_info.lastSelfDeploy')
        atomicStateMap.lastSelfDeploy.success == true
        atomicStateMap.lastSelfDeploy.status == 'saving'
        atomicStateMap.lastSelfDeploy.startedVersion == 5
    }

    def "hub_update_app self-update confirms the save when the App Code row's version passes the starting one"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 5, "source": "self source"}' }
        int rowReads = 0
        hubGet.register('/app/list/all/data') { params -> rowReads++; rowReads < 2 ? '[{"id": 1, "version": 5}]' : '[{"id": 1, "version": 6}]' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> null }
        script.metaClass._selfSaveWaitMs = { -> 60000L }
        script.metaClass._selfSavePollMs = { -> 1L }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v6', confirm: true])

        then:
        result.success == true
        result.verified == true
        result.version == 6
        result.status == null
        atomicStateMap.lastSelfDeploy.success == true
        atomicStateMap.lastSelfDeploy.confirmedVersion == 6
        !atomicStateMap.lastSelfDeploy.containsKey('status')
        !atomicStateMap.lastSelfDeploy.containsKey('assumed')
    }

    def "the version check reads the code list, never the single-item read the hub compiles to answer"() {
        given: 'list rows for an app and a driver; the single-item reads are left unstubbed'
        hubGet.register('/app/list/all/data') { params -> '[{"id": 7, "version": 3}, {"id": 12, "version": 9}]' }
        hubGet.register('/driver/list/data') { params -> '[{"id": 40, "version": 4}]' }

        expect:
        script._itemRowVersion('app', '12') == 9
        script._itemRowVersion('driver', 40) == 4
        script._itemRowVersion('app', '99') == null
        !hubGet.calls.any { it.path.contains('/list/single/data/') }
    }

    def "hub_update_app self-update still fails on a non-timeout POST exception"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params -> '{"status": "ok", "version": 5, "source": "self source"}' }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> throw new RuntimeException('Connection refused') }

        when:
        def result = script.toolUpdateAppCode([appId: '1', source: 'self-update v7', confirm: true])

        then:
        result.success == false
        result.error.contains('Connection refused')
        atomicStateMap.lastSelfDeploy.success == false
        !atomicStateMap.lastSelfDeploy.containsKey('status')
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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

    def "hub_update_app public args cannot spoof package correlation on self-update success"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: true] }
        def marker = [requestId: 'pkg-live', ref: 'main', startedAt: 1234567880000L,
                      args: [ref: 'main', confirm: true]]
        atomicStateMap.packageDeployInFlight = marker
        script._writeStateCacheInvalidate()
        def expectedMarker = new groovy.json.JsonSlurper().parseText(
            groovy.json.JsonOutput.toJson(marker)) as Map  // deep: nested args must not alias the live marker

        when:
        def result = script.toolUpdateAppCode([
            appId: '1', source: 'self-update spoof', confirm: true,
            __packageRequestId: 'pkg-live', __packageRef: 'attacker-ref'
        ])

        then:
        result.success == true
        atomicStateMap.packageDeployInFlight == expectedMarker
        atomicStateMap.lastSelfDeploy.success == true
        !atomicStateMap.lastSelfDeploy.containsKey('requestId')
        !atomicStateMap.lastSelfDeploy.containsKey('packageRef')
    }

    def "hub_update_app dispatch args cannot spoof package correlation"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: true] }
        def marker = [requestId: 'pkg-live', ref: 'main', startedAt: 1234567880000L,
                      args: [ref: 'main', confirm: true]]
        atomicStateMap.packageDeployInFlight = marker
        script._writeStateCacheInvalidate()
        def expectedMarker = new groovy.json.JsonSlurper().parseText(
            groovy.json.JsonOutput.toJson(marker)) as Map  // deep: nested args must not alias the live marker

        when:
        def response = mcpDriver.callTool('hub_update_app', [
            appId: '1', source: 'self-update spoof', confirm: true,
            __packageRequestId: 'pkg-live', __packageRef: 'attacker-ref'
        ])

        then:
        response.error == null
        mcpDriver.parseInner(response).success == true
        atomicStateMap.packageDeployInFlight == expectedMarker
        !atomicStateMap.lastSelfDeploy.containsKey('requestId')
        !atomicStateMap.lastSelfDeploy.containsKey('packageRef')
    }

    def "hub_update_app public args cannot spoof package correlation on self-update failure"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
            throw new RuntimeException('cloud 504')
        }
        def marker = [requestId: 'pkg-live', ref: 'main', startedAt: 1234567880000L,
                      args: [ref: 'main', confirm: true]]
        atomicStateMap.packageDeployInFlight = marker
        script._writeStateCacheInvalidate()
        def expectedMarker = new groovy.json.JsonSlurper().parseText(
            groovy.json.JsonOutput.toJson(marker)) as Map  // deep: nested args must not alias the live marker

        when:
        def result = script.toolUpdateAppCode([
            appId: '1', source: 'self-update spoof', confirm: true,
            __packageRequestId: 'pkg-live', __packageRef: 'attacker-ref'
        ])

        then:
        result.success == false
        atomicStateMap.packageDeployInFlight == expectedMarker
        atomicStateMap.lastSelfDeploy.success == false
        atomicStateMap.lastSelfDeploy.error.contains('cloud 504')
        !atomicStateMap.lastSelfDeploy.containsKey('requestId')
        !atomicStateMap.lastSelfDeploy.containsKey('packageRef')
    }

    def "worker-internal package correlation must match the active marker"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "version": 5, "source": "self source"}'
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> [success: true] }
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-live', ref: 'main', startedAt: 1234567880000L,
            args: [ref: 'main', confirm: true]
        ]
        script._writeStateCacheInvalidate()

        when:
        def mismatched = script._toolUpdateAppCode(
            [appId: '1', source: 'self-update mismatch', confirm: true],
            [requestId: 'pkg-other', packageRef: 'main']
        )

        then:
        mismatched.success == true
        atomicStateMap.packageDeployInFlight.requestId == 'pkg-live'
        !atomicStateMap.lastSelfDeploy.containsKey('requestId')
        !atomicStateMap.lastSelfDeploy.containsKey('packageRef')

        when:
        def matched = script._toolUpdateAppCode(
            [appId: '1', source: 'self-update matched', confirm: true],
            [requestId: 'pkg-live', packageRef: 'main']
        )

        then:
        matched.success == true
        atomicStateMap.lastSelfDeploy.requestId == 'pkg-live'
        atomicStateMap.lastSelfDeploy.packageRef == 'main'
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 ->
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
        backupIndex = [
            'library_42': [type: 'library', id: '42', fileName: 'mcp-backup-library-42.groovy',
                           version: 3, timestamp: 1L, sourceLength: 200]
        ]

        when:
        def response = mcpDriver.callTool('hub_restore_backup', [backupKey: 'library_42', confirm: true])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('use hub_create_library or hub_update_library')
        inner.backupFile == 'mcp-backup-library-42.groovy'
        inner.type == 'library'

        where:
        useGateways << [true, false]
    }
}
