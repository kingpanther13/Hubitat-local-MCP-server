package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the File Manager tools (libraries/mcp-files-lib.groovy):
 *
 * - toolListFiles  -> hub_list_files
 * - toolReadFile   -> hub_read_file
 * - toolWriteFile  -> hub_write_file   (Write master + confirm + 24h backup)
 * - toolDeleteFile -> hub_delete_file  (Write master + confirm + 24h backup)
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet                        -> HarnessSpec's hubGet.register(path); the
 *                                              /hub/fileManager/json stubs use the hub's real
 *                                              shape ({files:[...], freeSpace, backupSelection})
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

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.50')
    }

    // The /hub/fileManager/json answer as the hub sends it.
    private void listing(List files, Map extra = [:]) {
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([files: files, freeSpace: 1000000, backupSelection: [excludedFiles: []]] + extra)
        }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- toolListFiles --------

    def "hub_list_files returns the parsed JSON file list from /hub/fileManager/json"() {
        given:
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                files: [
                    [name: 'b.csv', type: 'file', size: 200, date: 1794555886617L, backupIncluded: true],
                    [name: 'a.txt', type: 'file', size: 100, date: 1794555880000L, backupIncluded: false],
                    [name: 'webcore', type: 'dir', size: 4096, date: 1794555800000L]
                ],
                freeSpace: 52428800,
                backupSelection: [excludedFiles: ['a.txt']]
            ])
        }

        when:
        def result = script.toolListFiles()

        then: 'sorted alphabetically by name, with numeric size and lastModified'
        result.total == 3
        result.files*.name == ['a.txt', 'b.csv', 'webcore']
        result.files[0] == [name: 'a.txt', type: 'file', size: 100L, lastModified: 1794555880000L, backupIncluded: false]
        result.files[1].size instanceof Number
        result.files[1].lastModified instanceof Number

        and: 'a folder carries no size'
        result.files[2] == [name: 'webcore', type: 'dir', lastModified: 1794555800000L]

        and: 'one download base instead of a URL per file, and no boilerplate'
        result.downloadUrlBase == 'http://192.168.1.50/local/'
        result.files.every { !it.containsKey('directDownload') }
        !result.containsKey('storage')
        result.freeSpaceBytes == 52428800L
        result.filesExcludedFromFullBackup == ['a.txt']
    }

    def "hub_list_files converts a string size and date to numbers"() {
        given:
        listing([[name: 'a.txt', type: 'file', size: '100450', date: '1794555886617']])

        when:
        def result = script.toolListFiles()

        then:
        result.files[0].size == 100450L
        result.files[0].lastModified == 1794555886617L
    }

    def "hub_list_files lists a folder through the folder query and prefixes its download base"() {
        given:
        hubGet.register('/hub/fileManager/json?folder=webcore') { params ->
            JsonOutput.toJson([files: [[name: 'piston.json', type: 'file', size: 10, date: 1L]], freeSpace: 5])
        }

        when:
        def result = script.toolListFiles([folder: 'webcore'])

        then:
        result.folder == 'webcore'
        result.files*.name == ['piston.json']
        result.downloadUrlBase == 'http://192.168.1.50/local/webcore/'
    }

    def "hub_list_files falls back to the <HUB_IP> placeholder only when the hub IP is unknown"() {
        given:
        sharedLocation.hub = new TestHub()
        listing([[name: 'a.txt', type: 'file', size: 1, date: 1]])

        expect:
        script.toolListFiles().downloadUrlBase == 'http://<HUB_IP>/local/'
    }

    def "hub_list_files filters file names by case-insensitive substring"() {
        given:
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                [name: 'alpha.txt', size: 10],
                [name: 'ALPINE.csv', size: 20],
                [name: 'beta.txt', size: 30]
            ])
        }

        when:
        def result = script.toolListFiles([filter: 'AlP'])

        then:
        result.total == 2
        result.files*.name == ['ALPINE.csv', 'alpha.txt']
    }

    def "hub_list_files refuses a non-string filter before any hub read"() {
        when:
        script.toolListFiles([filter: 123])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('filter must be a string')
        hubGet.calls.isEmpty()
    }

    def "hub_list_files case folding is locale-independent"() {
        given:
        def prior = Locale.default
        Locale.default = new Locale('tr', 'TR')
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                [name: 'TITLE.txt', size: 10],
                [name: 'other.txt', size: 20]
            ])
        }

        when:
        def result = script.toolListFiles([filter: 'title'])

        then:
        result.files*.name == ['TITLE.txt']

        cleanup:
        Locale.default = prior
    }

    @spock.lang.Unroll
    def "hub_list_files filter survives the dispatch envelope in both gateway modes (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([
                [name: 'alpha.txt', size: 10],
                [name: 'ALPINE.csv', size: 20],
                [name: 'beta.txt', size: 30]
            ])
        }

        when:
        def response = mcpDriver.callTool('hub_list_files', [filter: 'AlP'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.total == 2
        inner.files*.name == ['ALPINE.csv', 'alpha.txt']

        where:
        useGateways << [true, false]
    }

    def "hub_list_files filters BEFORE paginating so every page holds only matches"() {
        // Ordering proof: the cursor must index into the FILTERED list. If pagination ran
        // first, total would count the non-matching files and they would surface on page 2.
        given:
        def names = (0..101).collect { [name: String.format('match-%03d.txt', it), size: 1] }
        names << [name: 'other-a.txt', size: 1]
        names << [name: 'other-b.txt', size: 1]
        hubGet.register('/hub/fileManager/json') { params -> JsonOutput.toJson(names) }

        when: 'first page of the filtered set'
        def page1 = script.toolListFiles([filter: 'MATCH', cursor: '0'])

        then: 'a full page of matches, with a cursor for the remainder'
        page1.total == 102
        page1.files.size() == 100
        page1.nextCursor == '100'

        when: 'the remainder'
        def page2 = script.toolListFiles([filter: 'MATCH', cursor: page1.nextCursor])

        then: 'the two pages together are exactly the 102 matches, no non-matching file anywhere'
        page2.files.size() == 2
        page2.nextCursor == null
        (page1.files*.name + page2.files*.name) == (0..101).collect { String.format('match-%03d.txt', it) }
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

    @spock.lang.Unroll
    def "hub_list_files returns success:false when the listing #why"() {
        given:
        hubGet.register('/hub/fileManager/json', responder)

        when:
        def result = script.toolListFiles()

        then: 'a runtime failure, and no other endpoint is tried'
        result.success == false
        result.error.startsWith('Could not list File Manager')
        result.note.contains('Nothing was changed')
        hubGet.calls*.path == ['/hub/fileManager/json']

        where:
        why                    | responder
        'throws'               | { params -> throw new RuntimeException('Connection refused') }
        'is empty'             | { params -> null }
        'is an HTML page'      | { params -> '<html><body><a href="/local/foo.txt">foo.txt</a></body></html>' }
        'has no files list'    | { params -> JsonOutput.toJson('just a string') }
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
    def "hub_read_file via dispatch maps missing-fileName IAE to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_read_file', [:])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('fileName is required')

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
        result.chunkLength == 30000
        result.totalLength == 70000
        result.hasMore == true
        result.nextOffset == 30000
        result.remainingChars == 40000
        result.hint.contains('offset: 30000')
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

    def "hub_read_file says a missing file does not exist"() {
        given: 'the hub throws with just the name, as it does for a missing file'
        script.metaClass.downloadHubFile = { String name -> throw new RuntimeException(name) }
        listing([[name: 'other.txt', type: 'file', size: 1, date: 1]])

        when:
        def result = script.toolReadFile([fileName: 'missing.txt'])

        then:
        result.success == false
        result.error == "File 'missing.txt' does not exist in File Manager."
        result.note.contains('hub_list_files')
    }

    @spock.lang.Unroll
    def "hub_read_file keeps the generic error when #why"() {
        given:
        script.metaClass.downloadHubFile = { String name -> throw new RuntimeException('read timed out') }
        setupListing()

        when:
        def result = script.toolReadFile([fileName: 'notes.txt'])

        then:
        result.success == false
        result.error == "File 'notes.txt' could not be read: read timed out"
        result.directDownload == 'http://192.168.1.50/local/notes.txt'

        where:
        why                           | setupListing
        'the file is listed'          | { -> listing([[name: 'notes.txt', type: 'file', size: 1, date: 1]]) }
        'the listing fails too'       | { -> hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('down') } }
    }

    @spock.lang.Unroll
    def "hub_read_file refuses #args before reading the file"() {
        given:
        script.metaClass.downloadHubFile = { String name -> throw new AssertionError('must not read') }

        when:
        script.toolReadFile([fileName: 'notes.txt'] + args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)

        where:
        args              | message
        [offset: -1]      | 'offset must be an integer from 0 to'
        [offset: 'abc']   | 'offset must be an integer from 0 to'
        [offset: 1.5]     | 'offset must be an integer from 0 to'
        [length: 0]       | 'length must be an integer from 1 to 60000'
        [length: 60001]   | 'length must be an integer from 1 to 60000'
    }

    def "hub_read_file accepts the largest chunk and an offset past the end"() {
        given:
        script.metaClass.downloadHubFile = { String name -> '0123456789'.getBytes('UTF-8') }

        expect:
        script.toolReadFile([fileName: 'nums.txt', length: 60000]).content == '0123456789'
        script.toolReadFile([fileName: 'nums.txt', offset: 50]).with { content == '' && !hasMore }
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
    def "hub_write_file via dispatch maps confirm-missing IAE to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_write_file', [fileName: 'x.txt', content: 'hi'])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('SAFETY CHECK FAILED')
        mcpDriver.parseInner(response).error.contains('confirm=true')

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

        then: 'nothing is uploaded -- neither a backup copy nor the same bytes again'
        uploads.isEmpty()
        result.success == true
        result.unchanged == true
        result.message.contains('nothing was written')
        !result.backupFile
    }

    @spock.lang.Unroll
    def "hub_write_file identical content reaches the client as unchanged (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.downloadHubFile = { String name -> 'same content'.getBytes('UTF-8') }
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new AssertionError('must not upload') }

        when:
        def response = mcpDriver.callTool('hub_write_file', [fileName: 'notes.txt', content: 'same content', confirm: true])

        then:
        response.error == null
        !response.result.isError
        mcpDriver.parseInner(response).unchanged == true

        where:
        useGateways << [true, false]
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

    // A listing that reflects uploads and deletes, seeded with six copies of notes.txt older than
    // the harness clock (2009), in the second-only name form, and
    // look-alike names that must never be pruned (another base name, another extension, no timestamp).
    private Map fileManagerWithOldCopies() {
        Map files = [:]
        (1..6).each { files["notes_backup_20080101-00000${it}.txt".toString()] = 'old'.getBytes('UTF-8') }
        ['notes-2_backup_20080101-000000.txt', 'notes_backup_20080101-000000.json', 'notes_backup_old.txt',
         'xnotes_backup_20080101-000000.txt'].each { files[it] = 'other'.getBytes('UTF-8') }
        files['notes.txt'] = 'current'.getBytes('UTF-8')
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([files: files.keySet().collect { [name: it, type: 'file', size: '3', date: '1'] }])
        }
        script.metaClass.downloadHubFile = { String name -> files[name] }
        script.metaClass.uploadHubFile = { String name, byte[] content -> files[name] = content }
        script.metaClass.deleteHubFile = { String name -> files.remove(name) }
        return files
    }

    def "hub_write_file keeps the newest five backup copies of that file name only"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new content', confirm: true])

        then: 'the new copy plus the four newest older copies remain; the two oldest are removed'
        result.success == true
        result.removedOldBackups == ['notes_backup_20080101-000002.txt', 'notes_backup_20080101-000001.txt']
        result.backupRetention == 'copies'
        result.backupRetentionNote.contains('newest 5 backup copies')
        result.backupFile ==~ /notes_backup_\d{8}-\d{6}-\d{3}\.txt/
        !result.containsKey('pruneWarning')
        files.keySet().findAll { it ==~ /notes_backup_\d{8}-\d{6}(-\d{3})?\.txt/ }.size() == 5
        files.containsKey(result.backupFile)

        and: 'look-alike names are untouched'
        ['notes-2_backup_20080101-000000.txt', 'notes_backup_20080101-000000.json', 'notes_backup_old.txt',
         'xnotes_backup_20080101-000000.txt'].every { files.containsKey(it) }
    }

    def "hub_delete_file applies the same five-copy cap to its pre-delete copy"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()

        when:
        def result = script.toolDeleteFile([fileName: 'notes.txt', confirm: true])

        then:
        result.success == true
        !files.containsKey('notes.txt')
        result.removedOldBackups == ['notes_backup_20080101-000002.txt', 'notes_backup_20080101-000001.txt']
        result.backupRetention == 'copies'
        files.keySet().findAll { it ==~ /notes_backup_\d{8}-\d{6}(-\d{3})?\.txt/ }.size() == 5
    }

    def "hub_write_file still writes when the listing for pruning fails"() {
        given:
        enableWrite()
        hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('listing unavailable') }
        def uploads = []
        script.metaClass.downloadHubFile = { String name -> name == 'notes.txt' ? 'old'.getBytes('UTF-8') : null }
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new', confirm: true])

        then: 'the write lands and the response says the old copies were not checked'
        result.success == true
        !result.containsKey('removedOldBackups')
        result.pruneWarning.contains('listing failed')
        uploads.size() == 2
        uploads[1] == 'notes.txt'
    }

    def "hub_write_file writes nothing when the backup copy of an existing file fails"() {
        given:
        enableWrite()
        def uploads = []
        script.metaClass.downloadHubFile = { String name -> name == 'notes.txt' ? 'old'.getBytes('UTF-8') : null }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            uploads << name
            if (name.contains('_backup_')) throw new RuntimeException('hub storage full')
        }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new', confirm: true])

        then:
        result.success == false
        result.error == "Could not back up existing 'notes.txt' before overwriting: hub storage full"
        result.note.contains('Nothing was written')
        uploads.size() == 1
        uploads[0].startsWith('notes_backup_')
    }

    def "a failed write prunes nothing, so every older copy survives"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            if (name == 'notes.txt') throw new RuntimeException('write refused')
            files[name] = content
        }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new content', confirm: true])

        then:
        result.success == false
        files.keySet().findAll { it ==~ /notes_backup_\d{8}-\d{6}(-\d{3})?\.txt/ }.size() == 7
    }

    def "a failed old-copy delete is reported as pruneWarning"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()
        script.metaClass.deleteHubFile = { String name ->
            if (name == 'notes_backup_20080101-000001.txt') throw new RuntimeException('busy')
            files.remove(name)
        }

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new content', confirm: true])

        then:
        result.success == true
        result.removedOldBackups == ['notes_backup_20080101-000002.txt']
        result.pruneWarning.contains('notes_backup_20080101-000001.txt')
    }

    def "two writes in the same second get distinct copy names, and both name forms are pruned by age"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()

        and: 'a clock that advances one millisecond per read, as two back-to-back writes see it'
        def clock = [1234567890000L]
        NOW_OVERRIDE.set({ -> clock[0]++ })

        when:
        def first = script.toolWriteFile([fileName: 'notes.txt', content: 'one', confirm: true])
        def second = script.toolWriteFile([fileName: 'notes.txt', content: 'two', confirm: true])

        then:
        first.backupFile != second.backupFile
        files.containsKey(first.backupFile) && files.containsKey(second.backupFile)
        def kept = files.keySet().findAll { it ==~ /notes_backup_\d{8}-\d{6}(-\d{3})?\.txt/ }
        kept.size() == 5
        // the newest three second-only copies stay beside the two millisecond copies
        kept.containsAll(['notes_backup_20080101-000004.txt', 'notes_backup_20080101-000005.txt', 'notes_backup_20080101-000006.txt'])
    }

    def "the copy just taken is never pruned, even when a clock step makes its name sort oldest"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()

        and: 'a clock that reads 2007 in any time zone (noon UTC, 1 Jan), before every existing 2008 copy'
        NOW_OVERRIDE.set({ -> 1167652800000L })

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: 'new content', confirm: true])

        then: 'the new copy survives and two old copies go instead, keeping five in all'
        result.success == true
        result.backupFile.startsWith('notes_backup_2007')
        files.containsKey(result.backupFile)
        result.removedOldBackups == ['notes_backup_20080101-000002.txt', 'notes_backup_20080101-000001.txt']
        files.keySet().findAll { it ==~ /notes_backup_\d{8}-\d{6}(-\d{3})?\.txt/ }.size() == 5
    }

    def "an existing file whose bytes differ is backed up even when its decoded text matches the incoming content"() {
        given:
        enableWrite()
        Map files = fileManagerWithOldCopies()
        byte[] invalidUtf8 = [-61] as byte[]   // a lone 0xC3 decodes to the replacement character
        files['notes.txt'] = invalidUtf8

        when:
        def result = script.toolWriteFile([fileName: 'notes.txt', content: '\uFFFD', confirm: true])

        then: 'the write is not treated as identical content, so the old bytes are copied first'
        result.success == true
        result.backupFile ==~ /notes_backup_\d{8}-\d{6}-\d{3}\.txt/
        files[result.backupFile] == invalidUtf8
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
    def "hub_delete_file via dispatch maps Write-master-disabled IAE to an isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_delete_file', [fileName: 'x.txt', confirm: true])

        then:
        response.error == null
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

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
        'mcp-rm-backup-42-20260419.json'       | _   // native-rule backup prefix
        'mcp-prerestore-driver-9.groovy'      | _   // prefix mcp-prerestore-
    }

    def "hub_delete_file unlinks every manifest entry for the deleted backup file"() {
        given:
        enableWrite()
        backupIndex = [
            "rm-rule_42_old": [type: "rm-rule", ruleId: 42, fileName: "mcp-rm-backup-42-old.json", timestamp: 1L],
            "rm-rule_42_alias": [type: "rm-rule", ruleId: 42, fileName: "mcp-rm-backup-42-old.json", timestamp: 2L],
            "rm-rule_43_keep": [type: "rm-rule", ruleId: 43, fileName: "mcp-rm-backup-43-keep.json", timestamp: 3L]
        ]
        def deleted = []
        script.metaClass.downloadHubFile = { String name -> throw new AssertionError("backup files must not be backed up again") }
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new AssertionError("backup files must not be uploaded again") }
        script.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        def result = script.toolDeleteFile([fileName: "mcp-rm-backup-42-old.json", confirm: true])

        then:
        result.success == true
        deleted == ["mcp-rm-backup-42-old.json"]
        backupIndex.keySet() == ["rm-rule_43_keep"] as Set
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
        result.fileName == 'notes.txt'
        result.message.contains('permission denied')
        result.error.contains('permission denied')
        result.suggestion.contains('hub_list_files')
    }

    @spock.lang.Unroll
    def "hub_delete_file deletes nothing when #fileName does not exist"() {
        given:
        enableWrite()
        def deleted = []
        def uploads = []
        script.metaClass.downloadHubFile = { String name -> throw new RuntimeException(name) }
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        listing([[name: 'other.txt', type: 'file', size: 1, date: 1]])

        when:
        def result = script.toolDeleteFile([fileName: fileName, confirm: true])

        then:
        result.success == false
        result.error == "File '${fileName}' does not exist in File Manager; nothing was deleted."
        result.note.contains('hub_list_files')
        deleted.isEmpty()
        uploads.isEmpty()

        where:
        fileName << ['missing.txt', 'notes_backup_20260419-100000.txt', 'mcp-rm-backup-42-old.json']
    }

    def "hub_delete_file treats a null download of an unlisted file as missing"() {
        given:
        enableWrite()
        def deleted = []
        script.metaClass.downloadHubFile = { String name -> null }
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        listing([])

        when:
        def result = script.toolDeleteFile([fileName: 'missing.txt', confirm: true])

        then:
        result.success == false
        deleted.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_delete_file still deletes, with a warning, when the file cannot be downloaded and #why"() {
        given:
        enableWrite()
        def deleted = []
        script.metaClass.downloadHubFile = { String name -> throw new RuntimeException('read timed out') }
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new AssertionError('no copy without content') }
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        setupListing()

        when:
        def result = script.toolDeleteFile([fileName: 'notes.txt', confirm: true])

        then:
        result.success == true
        deleted == ['notes.txt']
        result.warning.contains('could not be backed up')
        !result.backupFile

        where:
        why                       | setupListing
        'it is listed'            | { -> listing([[name: 'notes.txt', type: 'file', size: 1, date: 1]]) }
        'the listing fails'       | { -> hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('down') } }
    }

    @spock.lang.Unroll
    def "#tool refusal '#needle' reaches the client as an isError result and changes nothing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def downloads = [], changes = []
        script.metaClass.downloadHubFile = { String name -> downloads << name; throw new RuntimeException(name) }
        script.metaClass.uploadHubFile = { String name, byte[] content -> changes << "upload ${name}" }
        script.metaClass.deleteHubFile = { String name -> changes << "delete ${name}" }
        if (listingFails) hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('Connection refused') }
        else listing([[name: 'other.txt', type: 'file', size: 1, date: 1]])

        when:
        def response = mcpDriver.callTool(tool, args)

        then:
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains(needle)
        changes.isEmpty()
        downloads.size() == expectedDownloads

        where:
        [tool, args, listingFails, needle, expectedDownloads, useGateways] << [
            ['hub_list_files', [:], true, 'Could not list File Manager', 0],
            ['hub_read_file', [fileName: 'notes.txt', offset: -5], false, 'offset must be an integer from 0', 0],
            ['hub_read_file', [fileName: 'notes.txt', length: 70000], false, 'length must be an integer from 1 to 60000', 0],
            ['hub_read_file', [fileName: 'notes.txt', length: 'abc'], false, 'length must be an integer from 1 to 60000', 0],
            ['hub_read_file', [fileName: 'missing.txt'], false, "File 'missing.txt' does not exist in File Manager.", 1],
            ['hub_delete_file', [fileName: 'missing.txt', confirm: true], false, 'does not exist in File Manager; nothing was deleted', 1],
            ['hub_delete_file', [fileName: 'missing_backup_20260101-000000.txt', confirm: true], false, 'does not exist in File Manager; nothing was deleted', 0],
        ].collectMany { row -> [true, false].collect { mode -> row + [mode] } }
    }

    def "write, read and delete results carry the hub's real download URL"() {
        given:
        enableWrite()
        Map files = ['notes.txt': 'old'.getBytes('UTF-8')]
        listing([[name: 'notes.txt', type: 'file', size: 3, date: 1]])
        script.metaClass.downloadHubFile = { String name -> files[name] }
        script.metaClass.uploadHubFile = { String name, byte[] content -> files[name] = content }
        script.metaClass.deleteHubFile = { String name -> files.remove(name) }

        when:
        def write = script.toolWriteFile([fileName: 'notes.txt', content: 'new', confirm: true])
        def read = script.toolReadFile([fileName: 'notes.txt'])
        def delete = script.toolDeleteFile([fileName: 'notes.txt', confirm: true])

        then:
        write.directDownload == 'http://192.168.1.50/local/notes.txt'
        write.backupDownload == "http://192.168.1.50/local/${write.backupFile}".toString()
        read.directDownload == 'http://192.168.1.50/local/notes.txt'
        delete.backupDownload == "http://192.168.1.50/local/${delete.backupFile}".toString()
        [write, read, delete].every { !it.toString().contains('<HUB_IP>') }
    }
}
