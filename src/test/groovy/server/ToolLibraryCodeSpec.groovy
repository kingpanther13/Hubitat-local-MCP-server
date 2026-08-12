package server

import support.ToolSpecBase

/**
 * Spec for the library management tools in the hub_read_apps_code
 * (hub_get_source) and hub_manage_code (create/update/delete) gateways:
 *
 * - toolGetLibrarySource   -> hub_get_source (type:library)
 * - toolInstallLibrary     -> hub_create_library
 * - toolUpdateLibraryCode  -> hub_update_library
 * - toolDeleteLibrary      -> hub_delete_item (type:library)
 *
 * Hub API contracts verified here:
 *   GET  /library/list/single/data/<id>  -- returns [{id, version, source, name, namespace, ...}]
 *   GET  /hub2/userLibraries             -- returns [{id, name, namespace, ...}] (no source)
 *   POST /library/saveOrUpdateJson       -- JSON body {id, source, version}; returns {success, message, id, version}
 *   GET  /library/edit/deleteJson/<id>   -- returns {success: true, message: null}
 *
 * Mocking strategy:
 *   - hubInternalGet        -- routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPostJson   -- purely dynamic helper, stubbed per-test on script.metaClass.
 *   - uploadHubFile / downloadHubFile -- purely dynamic, stubbed per-test on script.metaClass.
 *
 * Each direct-call feature has a parallel "via dispatch" feature that fires
 * the same tool through {@code mcpDriver.callTool} so the production
 * envelope path (JSON-RPC parse → tools/call → executeTool routing → error
 * mapping → response wrapping) is covered alongside the unit-level tool
 * internals. Dispatch features are @Unroll'd across useGateways true/false.
 */
class ToolLibraryCodeSpec extends ToolSpecBase {

    private static final String SAMPLE_SOURCE = '''\
library(
    name: "TestLib",
    namespace: "level99",
    author: "Test",
    description: "Test library"
)

def helperMethod() { return "ok" }
'''

    private static final String SAMPLE_RESPONSE_JSON = '''\
[{"id":42,"version":1,"author":"Test","category":null,"description":"A test library",
  "name":"TestLib","namespace":"level99","type":"usr","documentationLink":null,
  "updateTime":"2026-05-08T00:00:00+0000","usedByAppTypes":"","usedByDeviceTypes":"",
  "private":false,"lastModified":"2026-05-08T00:00:00+0000",
  "source":"library(\\n    name: \\"TestLib\\"\\n)\\n\\ndef helperMethod() { return \\"ok\\" }\\n"}]'''

    private void enableRead() {
        settingsMap.enableRead = true
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolGetLibrarySource --------

    def "get_library_source throws when Read tools are disabled"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool('hub_get_source', [type: 'library', id: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Read tools are disabled')
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns -32602 envelope when Read tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '42'])

        then:
        response.error.code == -32602
        response.error.message.contains('Read tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "get_library_source throws when libraryId is missing"() {
        given:
        enableRead()

        when:
        script.toolGetLibrarySource([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns -32602 envelope when libraryId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library'])

        then:
        response.error.code == -32602
        response.error.message.contains('libraryId is required')

        where:
        useGateways << [true, false]
    }

    def "get_library_source returns source and metadata for a known library"() {
        given:
        enableRead()
        hubGet.register('/library/list/single/data/42') { params ->
            SAMPLE_RESPONSE_JSON
        }

        when:
        def result = script.toolGetLibrarySource([libraryId: '42'])

        then:
        result.success == true
        result.libraryId == '42'
        result.version == 1
        result.name == 'TestLib'
        result.namespace == 'level99'
        result.totalLength > 0
        result.offset == 0
        result.hasMore == false
        result.source != null
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns source and metadata for a known library (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '42'
        inner.version == 1
        inner.name == 'TestLib'
        inner.namespace == 'level99'
        inner.totalLength > 0
        inner.offset == 0
        inner.hasMore == false
        inner.source != null

        where:
        useGateways << [true, false]
    }

    def "get_library_source returns error when library not found"() {
        given:
        enableRead()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        def result = script.toolGetLibrarySource([libraryId: '999'])

        then:
        result.success == false
        result.error.contains('999')
        result.error.toLowerCase().contains('not found')
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns success=false envelope when library not found (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '999'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('999')
        inner.error.toLowerCase().contains('not found')

        where:
        useGateways << [true, false]
    }

    def "get_library_source returns error on empty hub response"() {
        given:
        enableRead()
        hubGet.register('/library/list/single/data/42') { params -> null }

        when:
        def result = script.toolGetLibrarySource([libraryId: '42'])

        then:
        result.success == false
        result.error.contains('Empty response')
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns success=false envelope on empty hub response (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()
        hubGet.register('/library/list/single/data/42') { params -> null }

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Empty response')

        where:
        useGateways << [true, false]
    }

    def "get_library_source saves full source to File Manager and returns chunk when source exceeds 64KB"() {
        given:
        enableRead()
        // Build a source string slightly over the 64000-char chunk limit
        def bigSource = 'x' * 65000
        def bigLibJson = groovy.json.JsonOutput.toJson([
            [id: 10, version: 1, name: 'BigLib', namespace: 'ns', source: bigSource]
        ])
        hubGet.register('/library/list/single/data/10') { params -> bigLibJson }
        def uploadedFiles = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploadedFiles[name] = new String(content, 'UTF-8')
        }

        when:
        def result = script.toolGetLibrarySource([libraryId: '10'])

        then:
        result.success == true
        result.totalLength == 65000
        result.hasMore == true
        result.sourceFile == 'mcp-source-library-10.groovy'
        result.sourceFileHint.contains('hub_update_library')
        uploadedFiles.containsKey('mcp-source-library-10.groovy')
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch saves full source to File Manager and returns chunk when source exceeds 64KB (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()
        def bigSource = 'x' * 65000
        def bigLibJson = groovy.json.JsonOutput.toJson([
            [id: 10, version: 1, name: 'BigLib', namespace: 'ns', source: bigSource]
        ])
        hubGet.register('/library/list/single/data/10') { params -> bigLibJson }
        def uploadedFiles = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploadedFiles[name] = new String(content, 'UTF-8')
        }

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '10'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.totalLength == 65000
        inner.hasMore == true
        inner.sourceFile == 'mcp-source-library-10.groovy'
        inner.sourceFileHint.contains('hub_update_library')
        uploadedFiles.containsKey('mcp-source-library-10.groovy')

        where:
        useGateways << [true, false]
    }

    def "get_library_source respects offset and length for chunked reading"() {
        given:
        enableRead()
        def source = 'A' * 200
        def libJson = groovy.json.JsonOutput.toJson([[id: 5, version: 1, name: 'L', namespace: 'n', source: source]])
        hubGet.register('/library/list/single/data/5') { params -> libJson }

        when:
        def result = script.toolGetLibrarySource([libraryId: '5', offset: 100, length: 50])

        then:
        result.success == true
        result.offset == 100
        result.chunkLength == 50
        result.source == 'A' * 50
        result.hasMore == true
        result.nextOffset == 150
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch respects offset and length for chunked reading (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()
        def source = 'A' * 200
        def libJson = groovy.json.JsonOutput.toJson([[id: 5, version: 1, name: 'L', namespace: 'n', source: source]])
        hubGet.register('/library/list/single/data/5') { params -> libJson }

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: '5', offset: 100, length: 50])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.offset == 100
        inner.chunkLength == 50
        inner.source == 'A' * 50
        inner.hasMore == true
        inner.nextOffset == 150

        where:
        useGateways << [true, false]
    }

    // -------- toolInstallLibrary --------

    def "hub_create_library throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolInstallLibrary([source: SAMPLE_SOURCE])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library throws when neither source nor sourceFile is supplied"() {
        given:
        enableWrite()

        when:
        script.toolInstallLibrary([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
        ex.message.contains('sourceFile')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns -32602 envelope when neither source nor sourceFile supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_library', [confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('source')
        response.error.message.contains('sourceFile')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library throws when both source and sourceFile are supplied"() {
        given:
        enableWrite()

        when:
        script.toolInstallLibrary([source: SAMPLE_SOURCE, sourceFile: 'foo.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns -32602 envelope when both source and sourceFile supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, sourceFile: 'foo.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('exactly one')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library (source mode) POSTs JSON to /library/saveOrUpdateJson and returns libraryId"() {
        given:
        enableWrite()
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 100, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 100, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        capturedBody.id == null
        capturedBody.version == null
        capturedBody.source == SAMPLE_SOURCE
        result.success == true
        result.libraryId == '100'
        result.version == 1
        result.sourceMode == 'source'
        result.sourceLength == SAMPLE_SOURCE.length()
        result.message.contains('installed')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch (source mode) POSTs JSON and returns libraryId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 100, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 100, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        capturedBody.id == null
        capturedBody.version == null
        capturedBody.source == SAMPLE_SOURCE
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '100'
        inner.version == 1
        inner.sourceMode == 'source'
        inner.sourceLength == SAMPLE_SOURCE.length()
        inner.message.contains('installed')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library (sourceFile mode) reads source from File Manager and posts it"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mylib.groovy' ? SAMPLE_SOURCE.getBytes('UTF-8') : null
        }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 101, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 101, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def result = script.toolInstallLibrary([sourceFile: 'mylib.groovy', confirm: true])

        then:
        capturedBody.source == SAMPLE_SOURCE
        result.success == true
        result.libraryId == '101'
        result.sourceMode == 'sourceFile'
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch (sourceFile mode) reads source from File Manager and posts it (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'mylib.groovy' ? SAMPLE_SOURCE.getBytes('UTF-8') : null
        }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 101, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 101, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [sourceFile: 'mylib.groovy', confirm: true])

        then:
        capturedBody.source == SAMPLE_SOURCE
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '101'
        inner.sourceMode == 'sourceFile'

        where:
        useGateways << [true, false]
    }

    def "hub_create_library throws when sourceFile is not found in File Manager"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallLibrary([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns -32602 envelope when sourceFile not found in File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_create_library', [sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library reports hub failure when saveOrUpdateJson returns success=false"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Cannot parse library definition']
        }

        when:
        def result = script.toolInstallLibrary([source: 'bad source', confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('Cannot parse library definition')
        result.note.contains('library() definition block')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch reports failure envelope when saveOrUpdateJson returns success=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Cannot parse library definition']
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: 'bad source', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('Cannot parse library definition')
        inner.note.contains('library() definition block')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library reports failure when hub POST throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            throw new RuntimeException('connection refused')
        }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == false
        result.error.contains('installation failed')
        result.error.contains('connection refused')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch reports failure envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            throw new RuntimeException('connection refused')
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('installation failed')
        inner.error.contains('connection refused')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library fails closed when post-install verification finds the library absent from the list"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 200, version: 1]
        }
        // Verification fetch returns non-empty list but does NOT include the new library
        hubGet.register('/hub2/userLibraries') { params -> '[]' }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then: 'fail-closed: success=false with libraryId populated so caller can inspect'
        result.success == false
        result.libraryId == '200'
        result.error.contains('unverified')
        result.error.contains('200')
        result.note.contains('Do NOT retry')
        result.lastBackup != null
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch fails closed envelope when verification finds library absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 200, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params -> '[]' }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.libraryId == '200'
        inner.error.contains('unverified')
        inner.error.contains('200')
        inner.note.contains('Do NOT retry')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
    }

    def "hub_create_library returns success with verified=true when post-install library list includes the new library"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 201, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 201, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == true
        result.libraryId == '201'
        result.verified == true
        result.verifyError == null
        result.message.contains('installed')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns success envelope with verified=true when verify includes new library (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 201, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            groovy.json.JsonOutput.toJson([[id: 201, name: 'TestLib', namespace: 'level99']])
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '201'
        inner.verified == true
        inner.verifyError == null
        inner.message.contains('installed')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library returns success with verifyError when post-install verification fetch throws"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 202, version: 1]
        }
        // Verification fetch throws (transient failure)
        hubGet.register('/hub2/userLibraries') { params ->
            throw new RuntimeException('verify endpoint unavailable')
        }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then: 'transient verify failure keeps success=true but sets verified=false + verifyError'
        result.success == true
        result.libraryId == '202'
        result.verified == false
        result.verifyError != null
        result.verifyError.contains('verify endpoint unavailable')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch returns success envelope with verifyError when verify fetch throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 202, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params ->
            throw new RuntimeException('verify endpoint unavailable')
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '202'
        inner.verified == false
        inner.verifyError != null
        inner.verifyError.contains('verify endpoint unavailable')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library fails closed with anti-retry note when verify returns unparseable body"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 203, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params -> '<html>login page</html>' }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == false
        result.libraryId == '203'
        result.error.contains('unverified')
        result.note.contains('Do NOT retry')
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch fails closed envelope with anti-retry note when verify returns unparseable body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 203, version: 1]
        }
        hubGet.register('/hub2/userLibraries') { params -> '<html>login page</html>' }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.libraryId == '203'
        inner.error.contains('unverified')
        inner.note.contains('Do NOT retry')

        where:
        useGateways << [true, false]
    }

    def "hub_create_library fails closed with anti-retry note when hub returns null/empty response"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == false
        result.error.contains('unverified')
        result.error.contains('empty/null response')
        result.note.contains('FOR DEVELOPERS > Libraries code')
        result.note.contains('Do NOT retry')
        result.lastBackup != null
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch fails closed envelope when hub returns null response (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body -> null }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('unverified')
        inner.error.contains('empty/null response')
        inner.note.contains('FOR DEVELOPERS > Libraries code')
        inner.note.contains('Do NOT retry')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
    }

    def "hub_create_library fails closed with anti-retry note when hub response has no id field"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: 'persisted', version: 5]  // missing id
        }

        when:
        def result = script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == false
        result.error.contains('unverified')
        result.error.contains('missing id field')
        result.note.contains('FOR DEVELOPERS > Libraries code')
        result.note.contains('Do NOT retry')
        result.lastBackup != null
    }

    @spock.lang.Unroll
    def "hub_create_library via dispatch fails closed envelope when hub response has no id field (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: 'persisted', version: 5]
        }

        when:
        def response = mcpDriver.callTool('hub_create_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('unverified')
        inner.error.contains('missing id field')
        inner.note.contains('FOR DEVELOPERS > Libraries code')
        inner.note.contains('Do NOT retry')
        inner.lastBackup != null

        where:
        useGateways << [true, false]
    }

    // -------- toolUpdateLibraryCode --------

    def "hub_update_library throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library throws when libraryId is missing"() {
        given:
        enableWrite()

        when:
        script.toolUpdateLibraryCode([source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when libraryId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_library', [source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('libraryId is required')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library throws when none of source, sourceFile, or resave is supplied"() {
        given:
        enableWrite()

        when:
        script.toolUpdateLibraryCode([libraryId: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'source'")
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when none of source/sourceFile/resave (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains("'source'")

        where:
        useGateways << [true, false]
    }

    def "hub_update_library (source mode) backs up, fetches version, POSTs to saveOrUpdateJson"() {
        given:
        enableWrite()
        // Backup + version fetch use /library/list/single/data/42
        hubGet.register('/library/list/single/data/42') { params ->
            SAMPLE_RESPONSE_JSON
        }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then: 'backup created before update'
        uploads.size() == 1
        uploads[0] == 'mcp-backup-library-42.groovy'

        and: 'POST body carries the correct id, version and new source'
        capturedBody.id == 42
        capturedBody.version == 1
        capturedBody.source == SAMPLE_SOURCE

        and: 'result reports success with source mode'
        result.success == true
        result.libraryId == '42'
        result.previousVersion == 1
        result.newVersion == 2
        result.sourceMode == 'source'
        result.sourceLength == SAMPLE_SOURCE.length()

        and: 'backup manifest entry was created'
        atomicStateMap.itemBackupManifest?.containsKey('library_42')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch (source mode) backs up fetches version and POSTs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        uploads.size() == 1
        uploads[0] == 'mcp-backup-library-42.groovy'
        capturedBody.id == 42
        capturedBody.version == 1
        capturedBody.source == SAMPLE_SOURCE
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '42'
        inner.previousVersion == 1
        inner.newVersion == 2
        inner.sourceMode == 'source'
        inner.sourceLength == SAMPLE_SOURCE.length()
        atomicStateMap.itemBackupManifest?.containsKey('library_42')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library skips re-uploading backup when an entry exists within the 1-hour dedup window"() {
        given:
        enableWrite()
        // Pre-populate manifest with a recent backup (60s ago, well inside 1-hour window).
        // Original baseline must be preserved through rapid edits — second update should NOT
        // overwrite the existing backup file.
        atomicStateMap.itemBackupManifest = [
            'library_42': [
                type: 'library', id: '42',
                version: 1,
                timestamp: 1234567890000L - 60_000L,
                fileName: 'mcp-backup-library-42.groovy',
                sourceLength: SAMPLE_SOURCE.length()
            ]
        ]
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE + ' // edit', confirm: true])

        then: 'no new backup upload occurred (dedup window held)'
        uploads == []

        and: 'existing manifest entry was preserved (fileName + original timestamp unchanged)'
        atomicStateMap.itemBackupManifest['library_42'].fileName == 'mcp-backup-library-42.groovy'
        atomicStateMap.itemBackupManifest['library_42'].timestamp == (1234567890000L - 60_000L)

        and: 'update itself still succeeded'
        result.success == true
        result.libraryId == '42'
        result.newVersion == 2
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch skips re-uploading backup when entry exists within 1-hour dedup window (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [
                type: 'library', id: '42',
                version: 1,
                timestamp: 1234567890000L - 60_000L,
                fileName: 'mcp-backup-library-42.groovy',
                sourceLength: SAMPLE_SOURCE.length()
            ]
        ]
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE + ' // edit', confirm: true])

        then:
        uploads == []
        atomicStateMap.itemBackupManifest['library_42'].fileName == 'mcp-backup-library-42.groovy'
        atomicStateMap.itemBackupManifest['library_42'].timestamp == (1234567890000L - 60_000L)
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '42'
        inner.newVersion == 2

        where:
        useGateways << [true, false]
    }

    def "hub_update_library (sourceFile mode) reads source from File Manager"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'updated-lib.groovy' ? SAMPLE_SOURCE.getBytes('UTF-8') : null
        }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', sourceFile: 'updated-lib.groovy', confirm: true])

        then:
        capturedBody.source == SAMPLE_SOURCE
        result.success == true
        result.sourceMode == 'sourceFile'
        result.note.contains('File Manager')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch (sourceFile mode) reads source from File Manager (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String fileName ->
            fileName == 'updated-lib.groovy' ? SAMPLE_SOURCE.getBytes('UTF-8') : null
        }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', sourceFile: 'updated-lib.groovy', confirm: true])

        then:
        capturedBody.source == SAMPLE_SOURCE
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'sourceFile'
        inner.note.contains('File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library (sourceFile mode) throws when File Manager file is absent"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolUpdateLibraryCode([libraryId: '42', sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when sourceFile absent (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', sourceFile: 'missing.groovy', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found in File Manager')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library (resave mode) fetches source and version, then re-saves without external source"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', resave: true, confirm: true])

        then: 'submitted source matches the source fetched from the hub'
        capturedBody.version == 1
        result.success == true
        result.sourceMode == 'resave'
        result.note.contains('no cloud round-trip')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch (resave mode) fetches source and version then re-saves (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        def capturedBody = null
        script.metaClass.hubInternalPostJson = { String path, String body ->
            capturedBody = new groovy.json.JsonSlurper().parseText(body)
            [success: true, message: '', id: 42, version: 2]
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', resave: true, confirm: true])

        then:
        capturedBody.version == 1
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'resave'
        inner.note.contains('no cloud round-trip')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library throws when resave mode cannot fetch the library"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        script.toolUpdateLibraryCode([libraryId: '999', resave: true, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope when resave mode cannot fetch library (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '999', resave: true, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('not found')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library reports hub failure when saveOrUpdateJson returns success=false"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Compilation error']
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        result.success == false
        result.error.contains('Compilation error')
        result.note.contains('syntax errors')
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch reports failure envelope when saveOrUpdateJson returns success=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'Compilation error']
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Compilation error')
        inner.note.contains('syntax errors')

        where:
        useGateways << [true, false]
    }

    def "hub_update_library throws when pre-update backup fails"() {
        given:
        enableWrite()
        // Backup GET throws -- update must abort (fail-closed, matching apps/drivers update path)
        hubGet.register('/library/list/single/data/42') { params ->
            throw new RuntimeException('storage unavailable')
        }

        when:
        script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        thrown(Exception)
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch surfaces error envelope when pre-update backup fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params ->
            throw new RuntimeException('storage unavailable')
        }

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then: 'any thrown exception must surface as either -32602 (IAE) or isError envelope (RuntimeException)'
        response.error?.code == -32602 || response.result?.isError == true

        where:
        useGateways << [true, false]
    }

    // -------- toolDeleteLibrary --------

    def "delete_library throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch returns -32602 envelope when Write tools disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "delete_library throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteLibrary([libraryId: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch returns -32602 envelope when confirm not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42'])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "delete_library throws when libraryId is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteLibrary([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch returns -32602 envelope when libraryId missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('libraryId is required')

        where:
        useGateways << [true, false]
    }

    def "delete_library backs up source then deletes and reports backup file"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '{"success":true,"message":null}'
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == true
        result.libraryId == '42'
        result.backupFile == 'mcp-backup-library-42.groovy'
        uploads == ['mcp-backup-library-42.groovy']
        result.restoreHint.contains('hub_create_library')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch backs up source then deletes and reports backup file (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params -> '{"success":true,"message":null}' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '42'
        inner.backupFile == 'mcp-backup-library-42.groovy'
        uploads == ['mcp-backup-library-42.groovy']
        inner.restoreHint.contains('hub_create_library')

        where:
        useGateways << [true, false]
    }

    def "delete_library skips re-uploading backup when an entry exists within the 1-hour dedup window"() {
        given:
        enableWrite()
        // Pre-populate manifest with a recent backup (60s ago).
        atomicStateMap.itemBackupManifest = [
            'library_42': [
                type: 'library', id: '42',
                version: 1,
                timestamp: 1234567890000L - 60_000L,
                fileName: 'mcp-backup-library-42.groovy',
                sourceLength: SAMPLE_SOURCE.length()
            ]
        ]
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '{"success":true,"message":null}'
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then: 'no new backup upload occurred (dedup window held)'
        uploads == []

        and: 'response still references the existing backup file'
        result.success == true
        result.libraryId == '42'
        result.backupFile == 'mcp-backup-library-42.groovy'
        result.restoreHint.contains('hub_create_library')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch skips re-uploading backup when entry exists within 1-hour dedup window (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        atomicStateMap.itemBackupManifest = [
            'library_42': [
                type: 'library', id: '42',
                version: 1,
                timestamp: 1234567890000L - 60_000L,
                fileName: 'mcp-backup-library-42.groovy',
                sourceLength: SAMPLE_SOURCE.length()
            ]
        ]
        def uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params -> '{"success":true,"message":null}' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        uploads == []
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.libraryId == '42'
        inner.backupFile == 'mcp-backup-library-42.groovy'
        inner.restoreHint.contains('hub_create_library')

        where:
        useGateways << [true, false]
    }

    def "delete_library proceeds with backupWarning when pre-delete backup fails"() {
        given:
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params ->
            throw new RuntimeException('source unavailable')
        }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '{"success":true,"message":null}'
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == true
        result.message.contains('backup failed')
        result.backupWarning.contains('permanently lost')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch proceeds with backupWarning envelope when pre-delete backup fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/library/list/single/data/42') { params ->
            throw new RuntimeException('source unavailable')
        }
        hubGet.register('/library/edit/deleteJson/42') { params -> '{"success":true,"message":null}' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

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

    def "delete_library reports failure when hub returns success=false"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '{"success":false,"message":"Library is in use"}'
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == false
        // Fail-closed path: hub message returned directly, not a generic "Delete may have failed" string
        result.error.contains('Library is in use')
        result.libraryId == '42'
        result.note.contains('#include')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch reports failure envelope when hub returns success=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '{"success":false,"message":"Library is in use"}'
        }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Library is in use')
        inner.libraryId == '42'
        inner.note.contains('#include')

        where:
        useGateways << [true, false]
    }

    def "delete_library reports failure when hub delete endpoint throws"() {
        given:
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            throw new RuntimeException('connection reset')
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == false
        result.error.contains('deletion failed')
        result.error.contains('connection reset')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch reports failure envelope when hub delete endpoint throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            throw new RuntimeException('connection reset')
        }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('deletion failed')
        inner.error.contains('connection reset')

        where:
        useGateways << [true, false]
    }

    def "delete_library reports failure when hub delete endpoint returns empty response"() {
        given:
        // Empty response -- responseText is falsy in Groovy, so the JSON-parse block is skipped.
        // success stays false and the generic "check the Hubitat web UI" path fires.
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params -> '' }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == false
        result.error.contains('Delete may have failed')
        result.libraryId == '42'
        result.response == ''
    }

    @spock.lang.Unroll
    def "delete_library via dispatch reports failure envelope when hub delete returns empty response (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params -> '' }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Delete may have failed')
        inner.libraryId == '42'
        inner.response == ''

        where:
        useGateways << [true, false]
    }

    def "delete_library reports failure when hub delete endpoint returns non-JSON body"() {
        given:
        // Non-JSON (e.g. a login-redirect page) -- JsonSlurper throws, caught and returned
        // as the parse-failure path with "Delete response was not valid JSON".
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '<html><body>Login required</body></html>'
        }

        when:
        def result = script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        result.success == false
        result.error.contains('Delete response was not valid JSON')
        result.libraryId == '42'
        result.note.contains('verify whether the library was deleted')
    }

    @spock.lang.Unroll
    def "delete_library via dispatch reports failure envelope when hub delete returns non-JSON body (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        hubGet.register('/library/list/single/data/42') { params -> SAMPLE_RESPONSE_JSON }
        hubGet.register('/library/edit/deleteJson/42') { params ->
            '<html><body>Login required</body></html>'
        }

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Delete response was not valid JSON')
        inner.libraryId == '42'
        inner.note.contains('verify whether the library was deleted')

        where:
        useGateways << [true, false]
    }

    // -------- libraryId integer validation (Finding #10) --------
    //
    // Three tools share the same positive-integer guard for libraryId:
    // get_library_source, hub_update_library, delete_library.
    // All three should reject the same bad-input cases identically.

    @spock.lang.Unroll
    def "get_library_source rejects invalid libraryId: #description"() {
        given:
        enableRead()

        when:
        script.toolGetLibrarySource([libraryId: badId])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')

        where:
        badId  | description
        'abc'  | 'non-integer string'
        '-5'   | 'negative integer'
        '0'    | 'zero'
        '1.5'  | 'decimal string'
    }

    @spock.lang.Unroll
    def "get_library_source via dispatch returns -32602 envelope for invalid libraryId '#badId' (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableRead()

        when:
        def response = mcpDriver.callTool('hub_get_source', [type: 'library', id: badId])

        then:
        response.error.code == -32602
        response.error.message.contains('positive integer')

        where:
        useGateways | badId
        true        | 'abc'
        false       | 'abc'
        true        | '-5'
        false       | '-5'
        true        | '0'
        false       | '0'
        true        | '1.5'
        false       | '1.5'
    }

    @spock.lang.Unroll
    def "hub_update_library rejects invalid libraryId: #description"() {
        given:
        enableWrite()

        when:
        script.toolUpdateLibraryCode([libraryId: badId, source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')

        where:
        badId  | description
        'abc'  | 'non-integer string'
        '-5'   | 'negative integer'
        '0'    | 'zero'
        '1.5'  | 'decimal string'
    }

    @spock.lang.Unroll
    def "hub_update_library via dispatch returns -32602 envelope for invalid libraryId '#badId' (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_update_library', [libraryId: badId, source: SAMPLE_SOURCE, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('positive integer')

        where:
        useGateways | badId
        true        | 'abc'
        false       | 'abc'
        true        | '-5'
        false       | '-5'
        true        | '0'
        false       | '0'
        true        | '1.5'
        false       | '1.5'
    }

    @spock.lang.Unroll
    def "delete_library rejects invalid libraryId: #description"() {
        given:
        enableWrite()

        when:
        script.toolDeleteLibrary([libraryId: badId, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')

        where:
        badId  | description
        'abc'  | 'non-integer string'
        '-5'   | 'negative integer'
        '0'    | 'zero'
        '1.5'  | 'decimal string'
    }

    @spock.lang.Unroll
    def "delete_library via dispatch returns -32602 envelope for invalid libraryId '#badId' (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_item', [type: 'library', item_id: badId, confirm: true])

        then:
        response.error.code == -32602
        response.error.message.contains('positive integer')

        where:
        useGateways | badId
        true        | 'abc'
        false       | 'abc'
        true        | '-5'
        false       | '-5'
        true        | '0'
        false       | '0'
        true        | '1.5'
        false       | '1.5'
    }
}
