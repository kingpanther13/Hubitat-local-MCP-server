package server

import support.ToolSpecBase

/**
 * Spec for the library management tools in manage_app_driver_code gateway:
 *
 * - toolGetLibrarySource   -> get_library_source
 * - toolInstallLibrary     -> install_library
 * - toolUpdateLibraryCode  -> update_library_code
 * - toolDeleteLibrary      -> delete_library
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

    private void enableHubAdminRead() {
        settingsMap.enableHubAdminRead = true
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolGetLibrarySource --------

    def "get_library_source throws when Hub Admin Read is disabled"() {
        when:
        script.toolGetLibrarySource([libraryId: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Read')
    }

    def "get_library_source throws when libraryId is missing"() {
        given:
        enableHubAdminRead()

        when:
        script.toolGetLibrarySource([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    def "get_library_source returns source and metadata for a known library"() {
        given:
        enableHubAdminRead()
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

    def "get_library_source returns error when library not found"() {
        given:
        enableHubAdminRead()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        def result = script.toolGetLibrarySource([libraryId: '999'])

        then:
        result.success == false
        result.error.contains('999')
        result.error.toLowerCase().contains('not found')
    }

    def "get_library_source returns error on empty hub response"() {
        given:
        enableHubAdminRead()
        hubGet.register('/library/list/single/data/42') { params -> null }

        when:
        def result = script.toolGetLibrarySource([libraryId: '42'])

        then:
        result.success == false
        result.error.contains('Empty response')
    }

    def "get_library_source saves full source to File Manager and returns chunk when source exceeds 64KB"() {
        given:
        enableHubAdminRead()
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
        result.sourceFileHint.contains('update_library_code')
        uploadedFiles.containsKey('mcp-source-library-10.groovy')
    }

    def "get_library_source respects offset and length for chunked reading"() {
        given:
        enableHubAdminRead()
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

    // -------- toolInstallLibrary --------

    def "install_library throws when Hub Admin Write is disabled"() {
        when:
        script.toolInstallLibrary([source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "install_library throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallLibrary([source: SAMPLE_SOURCE])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "install_library throws when neither source nor sourceFile is supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallLibrary([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('source')
        ex.message.contains('sourceFile')
    }

    def "install_library throws when both source and sourceFile are supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolInstallLibrary([source: SAMPLE_SOURCE, sourceFile: 'foo.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
    }

    def "install_library (source mode) POSTs JSON to /library/saveOrUpdateJson and returns libraryId"() {
        given:
        enableHubAdminWrite()
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

    def "install_library (sourceFile mode) reads source from File Manager and posts it"() {
        given:
        enableHubAdminWrite()
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

    def "install_library throws when sourceFile is not found in File Manager"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolInstallLibrary([sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    def "install_library reports hub failure when saveOrUpdateJson returns success=false"() {
        given:
        enableHubAdminWrite()
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

    def "install_library reports failure when hub POST throws"() {
        given:
        enableHubAdminWrite()
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

    def "install_library fails closed when post-install verification finds the library absent from the list"() {
        given:
        enableHubAdminWrite()
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

    def "install_library returns success with verified=true when post-install library list includes the new library"() {
        given:
        enableHubAdminWrite()
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

    def "install_library returns success with verifyError when post-install verification fetch throws"() {
        given:
        enableHubAdminWrite()
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

    def "install_library fails closed with anti-retry note when verify returns unparseable body"() {
        given:
        enableHubAdminWrite()
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

    def "install_library fails closed with anti-retry note when hub returns null/empty response"() {
        given:
        enableHubAdminWrite()
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

    def "install_library fails closed with anti-retry note when hub response has no id field"() {
        given:
        enableHubAdminWrite()
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

    // -------- toolUpdateLibraryCode --------

    def "update_library_code throws when Hub Admin Write is disabled"() {
        when:
        script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "update_library_code throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "update_library_code throws when libraryId is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateLibraryCode([source: SAMPLE_SOURCE, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    def "update_library_code throws when none of source, sourceFile, or resave is supplied"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolUpdateLibraryCode([libraryId: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'source'")
    }

    def "update_library_code (source mode) backs up, fetches version, POSTs to saveOrUpdateJson"() {
        given:
        enableHubAdminWrite()
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

    def "update_library_code skips re-uploading backup when an entry exists within the 1-hour dedup window"() {
        given:
        enableHubAdminWrite()
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

    def "update_library_code (sourceFile mode) reads source from File Manager"() {
        given:
        enableHubAdminWrite()
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

    def "update_library_code (sourceFile mode) throws when File Manager file is absent"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String fileName -> null }

        when:
        script.toolUpdateLibraryCode([libraryId: '42', sourceFile: 'missing.groovy', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found in File Manager')
    }

    def "update_library_code (resave mode) fetches source and version, then re-saves without external source"() {
        given:
        enableHubAdminWrite()
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

    def "update_library_code throws when resave mode cannot fetch the library"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/library/list/single/data/999') { params -> '[]' }

        when:
        script.toolUpdateLibraryCode([libraryId: '999', resave: true, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found')
    }

    def "update_library_code reports hub failure when saveOrUpdateJson returns success=false"() {
        given:
        enableHubAdminWrite()
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

    def "update_library_code throws when pre-update backup fails"() {
        given:
        enableHubAdminWrite()
        // Backup GET throws -- update must abort (fail-closed, matching apps/drivers update path)
        hubGet.register('/library/list/single/data/42') { params ->
            throw new RuntimeException('storage unavailable')
        }

        when:
        script.toolUpdateLibraryCode([libraryId: '42', source: SAMPLE_SOURCE, confirm: true])

        then:
        thrown(Exception)
    }

    // -------- toolDeleteLibrary --------

    def "delete_library throws when Hub Admin Write is disabled"() {
        when:
        script.toolDeleteLibrary([libraryId: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "delete_library throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteLibrary([libraryId: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "delete_library throws when libraryId is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteLibrary([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('libraryId is required')
    }

    def "delete_library backs up source then deletes and reports backup file"() {
        given:
        enableHubAdminWrite()
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
        result.restoreHint.contains('install_library')
    }

    def "delete_library skips re-uploading backup when an entry exists within the 1-hour dedup window"() {
        given:
        enableHubAdminWrite()
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
        result.restoreHint.contains('install_library')
    }

    def "delete_library proceeds with backupWarning when pre-delete backup fails"() {
        given:
        enableHubAdminWrite()
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

    def "delete_library reports failure when hub returns success=false"() {
        given:
        enableHubAdminWrite()
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

    def "delete_library reports failure when hub delete endpoint throws"() {
        given:
        enableHubAdminWrite()
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

    def "delete_library reports failure when hub delete endpoint returns empty response"() {
        given:
        // Empty response -- responseText is falsy in Groovy, so the JSON-parse block is skipped.
        // success stays false and the generic "check the Hubitat web UI" path fires.
        enableHubAdminWrite()
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

    def "delete_library reports failure when hub delete endpoint returns non-JSON body"() {
        given:
        // Non-JSON (e.g. a login-redirect page) -- JsonSlurper throws, caught and returned
        // as the parse-failure path with "Delete response was not valid JSON".
        enableHubAdminWrite()
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

    // -------- libraryId integer validation (Finding #10) --------
    //
    // Three tools share the same positive-integer guard for libraryId:
    // get_library_source, update_library_code, delete_library.
    // All three should reject the same bad-input cases identically.

    @spock.lang.Unroll
    def "get_library_source rejects invalid libraryId: #description"() {
        given:
        enableHubAdminRead()

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
    def "update_library_code rejects invalid libraryId: #description"() {
        given:
        enableHubAdminWrite()

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
    def "delete_library rejects invalid libraryId: #description"() {
        given:
        enableHubAdminWrite()

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
}
