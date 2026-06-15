package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for the hub_manage_files gateway tools (hubitat-mcp-server.groovy):
 *
 * - toolListFiles  -> hub_list_files
 * - toolReadFile   -> hub_read_file
 * - toolWriteFile  -> hub_write_file   (Write master + confirm + 24h backup)
 * - toolDeleteFile -> hub_delete_file  (Write master + confirm + 24h backup)
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet                        -> HarnessSpec's hubGet.register(path)
 *   - downloadHubFile / uploadHubFile /
 *     deleteHubFile                         -> stubbed per-test on script.metaClass
 *
 * hub_write_file + hub_delete_file are gated centrally by the Write master at the
 * executeTool chokepoint (enableWrite, default ON; only an explicit ==false blocks),
 * then call requireDestructiveConfirm(args.confirm) which requires confirm=true AND a
 * lastBackupTimestamp within 24h — see ToolDestructiveHubOpsSpec for the same
 * confirm+backup gate's dedicated tests on hub_reboot / hub_shutdown / hub_delete_device.
 * The Write-master gate tests here are explicit rather than shared so each tool's gate
 * regression is discoverable from its own spec.
 */
class ToolManageFilesSpec extends ToolSpecBase {

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- toolListFiles --------

    def "hub_list_files returns the parsed JSON file list from /hub/fileManager/json"() {
        given:
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                [name: 'b.csv', size: 200, date: '2026-04-19'],
                [name: 'a.txt', size: 100, date: '2026-04-18']
            ])
        }

        when:
        def result = script.toolListFiles()

        then: 'sorted alphabetically by name'
        result.total == 2
        result.files[0].name == 'a.txt'
        result.files[1].name == 'b.csv'
        result.files[0].size == 100
        result.files[0].lastModified == '2026-04-18'
        result.files[0].directDownload.contains('/local/a.txt')
    }

    @spock.lang.Unroll
    def "hub_list_files via dispatch returns sorted file list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                [name: 'b.csv', size: 200, date: '2026-04-19'],
                [name: 'a.txt', size: 100, date: '2026-04-18']
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_list_files', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.total == 2
        inner.files[0].name == 'a.txt'
        inner.files[1].name == 'b.csv'

        where:
        useGateways << [true, false]
    }

    def "hub_list_files falls back to /hub/fileManager when /json fails"() {
        given:
        hubGet.register('/hub/fileManager/json') { params ->
            throw new RuntimeException('endpoint 404')
        }
        hubGet.register('/hub/fileManager') { params ->
            JsonOutput.toJson([files: [[name: 'only.txt', size: 10]]])
        }

        when:
        def result = script.toolListFiles()

        then:
        result.total == 1
        result.files[0].name == 'only.txt'
    }

    def "hub_list_files returns 'API not available' message when no endpoint responds"() {
        given:
        hubGet.register('/hub/fileManager/json') { params -> null }
        hubGet.register('/hub/fileManager') { params -> null }

        when:
        def result = script.toolListFiles()

        then:
        result.total == 0
        result.files == []
        result.message.contains('File Manager API not available')
        result.manualAccess.contains('Settings > File Manager')
    }

    def "hub_list_files returns 'API not available' when both endpoints throw (hub network failure)"() {
        given:
        hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('Connection refused') }
        hubGet.register('/hub/fileManager')      { params -> throw new RuntimeException('Connection refused') }

        when:
        def result = script.toolListFiles()

        then:
        result.total == 0
        result.files == []
        result.message.contains('File Manager API not available')
    }

    def "hub_list_files extracts file names from an HTML fallback response"() {
        given:
        hubGet.register('/hub/fileManager/json') { params ->
            '''<html><body>
               <a href="/local/foo.txt">foo.txt</a>
               <a href="/local/bar%20baz.csv">bar baz.csv</a>
               </body></html>'''
        }

        when:
        def result = script.toolListFiles()

        then:
        result.total == 2
        result.files*.name.sort() == ['bar baz.csv', 'foo.txt']
        result.note.contains('HTML')
    }

    // -------- toolReadFile --------

    def "hub_read_file throws when fileName is missing"() {
        when:
        script.toolReadFile([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    @spock.lang.Unroll
    def "hub_read_file via dispatch maps missing-fileName IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_read_file', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('fileName is required')

        where:
        useGateways << [true, false]
    }

    def "hub_read_file returns full content when it fits in one chunk"() {
        given:
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'Hello, world!'.getBytes('UTF-8') : null
        }

        when:
        def result = script.toolReadFile([fileName: 'notes.txt'])

        then:
        result.success == true
        result.fileName == 'notes.txt'
        result.content == 'Hello, world!'
        result.totalLength == 13
        result.offset == 0
        result.chunkLength == 13
        result.hasMore == false
        result.directDownload.contains('/local/notes.txt')
    }

    @spock.lang.Unroll
    def "hub_read_file via dispatch returns full content envelope when it fits in one chunk (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'Hello, world!'.getBytes('UTF-8') : null
        }

        when:
        def response = mcpDriver.callTool('hub_read_file', [fileName: 'notes.txt'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.fileName == 'notes.txt'
        inner.content == 'Hello, world!'
        inner.totalLength == 13
        inner.hasMore == false

        where:
        useGateways << [true, false]
    }

    def "hub_read_file returns a chunk + nextOffset hint when content exceeds max chunk"() {
        given:
        def big = 'x' * 70000  // exceeds the 60000 max chunk
        script.metaClass.downloadHubFile = { String name -> big.getBytes('UTF-8') }

        when:
        def result = script.toolReadFile([fileName: 'big.txt'])

        then:
        result.chunkLength == 60000
        result.totalLength == 70000
        result.hasMore == true
        result.nextOffset == 60000
        result.remainingChars == 10000
        result.hint.contains('offset: 60000')
    }

    def "hub_read_file honours an explicit offset + length"() {
        given:
        script.metaClass.downloadHubFile = { String name -> '0123456789'.getBytes('UTF-8') }

        when:
        def result = script.toolReadFile([fileName: 'nums.txt', offset: 2, length: 4])

        then:
        result.content == '2345'
        result.offset == 2
        result.chunkLength == 4
        result.hasMore == true
        result.nextOffset == 6
    }

    def "hub_read_file returns an error map when the file is missing"() {
        given:
        script.metaClass.downloadHubFile = { String name -> null }

        when:
        def result = script.toolReadFile([fileName: 'missing.txt'])

        then:
        result.success == false
        result.error.contains('missing.txt')
        result.suggestion.contains('File Manager')
    }

    // -------- toolWriteFile (DESTRUCTIVE: Write master + confirm + 24h backup) --------

    def "hub_write_file throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolWriteFile([fileName: 'x.txt', content: 'hi'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "hub_write_file throws when the Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_write_file', [fileName: 'x.txt', content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_write_file throws when no recent backup exists"() {
        given:
        settingsMap.enableWrite = true
        // stateMap.lastBackupTimestamp is unset

        when:
        script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
    }

    def "hub_write_file throws when fileName is missing"() {
        given:
        enableWrite()

        when:
        script.toolWriteFile([content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    def "hub_write_file throws when content is missing"() {
        given:
        enableWrite()

        when:
        script.toolWriteFile([fileName: 'x.txt', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('content is required')
    }

    def "hub_write_file rejects invalid file names"() {
        given:
        enableWrite()

        when:
        script.toolWriteFile([fileName: badName, content: 'ok', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Invalid file name')

        where:
        badName         | _
        '../escape'     | _
        '.hidden'       | _
        'with space'    | _
        'x/y'           | _
        '_leading_us'   | _   // underscore isn't allowed as first char
        'foo$bar'       | _   // $ isn't in the allowed [A-Za-z0-9._-] set
        'café.txt'      | _   // non-ASCII rejected — ASCII-only allowlist
    }

    def "hub_write_file creates a new file when none exists"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name -> null }  // no existing file
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, content: new String(content, 'UTF-8')]
        }

        when:
        def result = script.toolWriteFile([fileName: 'new.txt', content: 'hello', confirm: true])

        then:
        result.success == true
        result.message.contains("'new.txt' created")
        result.fileName == 'new.txt'
        result.contentLength == 5
        result.backupFile == null
        uploads.size() == 1
        uploads[0].name == 'new.txt'
        uploads[0].content == 'hello'
    }

    @spock.lang.Unroll
    def "hub_write_file via dispatch creates a new file (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name -> null }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, content: new String(content, 'UTF-8')]
        }

        when:
        def response = mcpDriver.callTool('hub_write_file', [fileName: 'new.txt', content: 'hello', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.message.contains("'new.txt' created")
        inner.fileName == 'new.txt'
        inner.contentLength == 5
        inner.backupFile == null
        uploads.size() == 1
        uploads[0].name == 'new.txt'
        uploads[0].content == 'hello'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_write_file via dispatch maps confirm-missing IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_write_file', [fileName: 'x.txt', content: 'hi'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')
        response.error.message.contains('confirm=true')

        where:
        useGateways << [true, false]
    }

    def "hub_write_file backs up an existing file before overwriting"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'old content'.getBytes('UTF-8') : null
        }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, content: new String(content, 'UTF-8')]
        }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new content', confirm: true])

        then: 'backup upload preceded the write, and both land on the hub'
        uploads.size() == 2
        uploads[0].name.startsWith('notes_backup_')
        uploads[0].name.endsWith('.txt')
        uploads[0].content == 'old content'
        uploads[1].name == 'notes.txt'
        uploads[1].content == 'new content'

        and: 'response surfaces the backup name + download URL'
        result.success == true
        result.backupFile.startsWith('notes_backup_')
        result.backupDownload.contains('/local/notes_backup_')
        result.message.contains('updated')
    }

    def "hub_write_file skips the backup when the existing content equals the incoming content (retry-safe)"() {
        // The idempotentHint contract: a retry after a dropped response must not
        // mint another timestamped backup of the very bytes being written.
        given:
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'same content'.getBytes('UTF-8') : null
        }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, content: new String(content, 'UTF-8')]
        }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'same content', confirm: true])

        then: 'only the byte-identical overwrite lands -- no backup file is created'
        uploads.size() == 1
        uploads[0].name == 'notes.txt'
        result.success == true
        !result.backupFile
    }

    def "hub_write_file treats a download-throws exception as 'no existing file' and skips the backup step"() {
        // Pins the current server behaviour: the backup attempt is wrapped in
        // a try/catch that swallows ANY download throw and proceeds as if
        // there was no prior file (libraries/mcp-files-lib.groovy). Note
        // this means a transient File-Manager read failure on a file that
        // DOES exist would cause the overwrite to silently skip the backup —
        // worth future design review, but pinning current behaviour here.
        given:
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name ->
            throw new RuntimeException('File Manager API transient error')
        }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << [name: name, content: new String(content, 'UTF-8')]
        }

        when:
        def result = script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then: 'one upload (the write itself) — no backup attempted because the download threw'
        uploads.size() == 1
        uploads[0].name == 'x.txt'
        result.success == true
        result.backupFile == null
    }

    def "hub_write_file reports failure without throwing when uploadHubFile errors"() {
        given: 'no existing file — so the only upload attempt is the new-file write itself'
        enableWrite()
        def uploadCalls = []
        script.metaClass.downloadHubFile = { String name -> null }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploadCalls << name
            throw new RuntimeException('hub storage full')
        }

        when:
        def result = script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then: 'exactly one upload attempt (the write itself) — no spurious backup attempt for a nonexistent file'
        uploadCalls == ['x.txt']
        result.success == false
        result.error.contains('hub storage full')
    }

    // -------- toolDeleteFile (DESTRUCTIVE: Write master + confirm + 24h backup) --------

    def "hub_delete_file throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteFile([fileName: 'x.txt'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "hub_delete_file throws when the Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_delete_file', [fileName: 'x.txt', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_delete_file throws when fileName is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteFile([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    def "hub_delete_file backs up then deletes a normal file"() {
        given:
        enableWrite()
        def uploads = []
        def deleted = []
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'saved content'.getBytes('UTF-8') : null
        }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
        }
        script.metaClass.deleteHubFile = { String name ->
            deleted << name
        }

        when:
        def result = script.toolDeleteFile([fileName: 'notes.txt', confirm: true])

        then: 'backup upload precedes the delete'
        uploads.size() == 1
        uploads[0].startsWith('notes_backup_')
        uploads[0].endsWith('.txt')
        deleted == ['notes.txt']

        and: 'result reports the backup and a recovery hint'
        result.success == true
        result.fileName == 'notes.txt'
        result.backupFile.startsWith('notes_backup_')
        result.backupDownload.contains('/local/notes_backup_')
        result.undoHint.contains('hub_read_file')
    }

    @spock.lang.Unroll
    def "hub_delete_file via dispatch backs up then deletes (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def uploads = []
        def deleted = []
        script.metaClass.downloadHubFile = { String name ->
            name == 'notes.txt' ? 'saved content'.getBytes('UTF-8') : null
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        def response = mcpDriver.callTool('hub_delete_file', [fileName: 'notes.txt', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        uploads.size() == 1
        uploads[0].startsWith('notes_backup_')
        deleted == ['notes.txt']
        inner.success == true
        inner.fileName == 'notes.txt'
        inner.backupFile.startsWith('notes_backup_')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_file via dispatch maps Write-master-disabled IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_file', [fileName: 'x.txt', confirm: true])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "hub_delete_file skips auto-backup on files that are already backups (#fileName)"() {
        given: 'isBackupFile trips on any of three markers (source ~line 4555): _backup_ substring, mcp-backup- prefix, mcp-prerestore- prefix'
        enableWrite()
        def uploads = []
        def deleted = []
        script.metaClass.downloadHubFile = { String name -> 'ignored'.getBytes('UTF-8') }
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        def result = script.toolDeleteFile([fileName: fileName, confirm: true])

        then: 'no backup-of-a-backup is written — each trigger wired independently'
        uploads == []
        deleted == [fileName]
        result.success == true
        result.message.contains('Backup file')
        result.message.contains('deleted permanently')

        where:
        fileName                              | _
        'notes_backup_20260419-100000.txt'    | _   // substring _backup_
        'mcp-backup-app-42.groovy'            | _   // prefix mcp-backup-
        'mcp-prerestore-driver-9.groovy'      | _   // prefix mcp-prerestore-
    }

    def "hub_delete_file reports failure without throwing when deleteHubFile errors"() {
        given:
        enableWrite()
        script.metaClass.downloadHubFile = { String name -> 'bytes'.getBytes('UTF-8') }
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.deleteHubFile = { String name ->
            throw new RuntimeException('permission denied')
        }

        when:
        def result = script.toolDeleteFile([fileName: 'notes.txt', confirm: true])

        then:
        result.success == false
        result.error.contains('permission denied')
        result.suggestion.contains('hub_list_files')
    }
}
