package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for the manage_files gateway tools (hubitat-mcp-server.groovy):
 *
 * - toolListFiles  -> list_files
 * - toolReadFile   -> read_file
 * - toolWriteFile  -> write_file   (Hub Admin Write + confirm)
 * - toolDeleteFile -> delete_file  (Hub Admin Write + confirm)
 *
 * Mocking strategy (see docs/testing.md):
 *   - hubInternalGet                        -> HarnessSpec's hubGet.register(path)
 *   - downloadHubFile / uploadHubFile /
 *     deleteHubFile                         -> stubbed per-test on script.metaClass
 *
 * write_file + delete_file are gated by requireHubAdminWrite(args.confirm), which
 * requires enableHubAdminWrite=true, confirm=true, AND a lastBackupTimestamp
 * within 24h — see ToolDestructiveHubOpsSpec for the same gate's dedicated tests
 * on reboot_hub / shutdown_hub / delete_device. The Hub-Admin-Write safety gate
 * tests here are explicit rather than shared so each tool's gate regression is
 * discoverable from its own spec.
 */
class ToolManageFilesSpec extends ToolSpecBase {

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- toolListFiles --------

    def "list_files returns the parsed JSON file list from /hub/fileManager/json"() {
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

    def "list_files falls back to /hub/fileManager when /json fails"() {
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

    def "list_files returns 'API not available' message when no endpoint responds"() {
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

    def "list_files extracts file names from an HTML fallback response"() {
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

    def "read_file throws when fileName is missing"() {
        when:
        script.toolReadFile([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    def "read_file returns full content when it fits in one chunk"() {
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

    def "read_file returns a chunk + nextOffset hint when content exceeds max chunk"() {
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

    def "read_file honours an explicit offset + length"() {
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

    def "read_file returns an error map when the file is missing"() {
        given:
        script.metaClass.downloadHubFile = { String name -> null }

        when:
        def result = script.toolReadFile([fileName: 'missing.txt'])

        then:
        result.success == false
        result.error.contains('missing.txt')
        result.suggestion.contains('File Manager')
    }

    // -------- toolWriteFile (DESTRUCTIVE: confirm + Hub Admin Write gate) --------

    def "write_file throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolWriteFile([fileName: 'x.txt', content: 'hi'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "write_file throws when Hub Admin Write is disabled"() {
        when:
        script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "write_file throws when no recent backup exists"() {
        given:
        settingsMap.enableHubAdminWrite = true
        // stateMap.lastBackupTimestamp is unset

        when:
        script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
    }

    def "write_file throws when fileName is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolWriteFile([content: 'hi', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    def "write_file throws when content is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolWriteFile([fileName: 'x.txt', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('content is required')
    }

    def "write_file rejects invalid file names"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolWriteFile([fileName: badName, content: 'ok', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Invalid file name')

        where:
        badName       | _
        '../escape'   | _
        '.hidden'     | _
        'with space'  | _
        'x/y'         | _
    }

    def "write_file creates a new file when none exists"() {
        given:
        enableHubAdminWrite()
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

    def "write_file backs up an existing file before overwriting"() {
        given:
        enableHubAdminWrite()
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

    def "write_file reports failure without throwing when uploadHubFile errors"() {
        given:
        enableHubAdminWrite()
        script.metaClass.downloadHubFile = { String name -> null }
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            throw new RuntimeException('hub storage full')
        }

        when:
        def result = script.toolWriteFile([fileName: 'x.txt', content: 'hi', confirm: true])

        then:
        result.success == false
        result.error.contains('hub storage full')
    }

    // -------- toolDeleteFile (DESTRUCTIVE: confirm + Hub Admin Write gate) --------

    def "delete_file throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteFile([fileName: 'x.txt'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "delete_file throws when Hub Admin Write is disabled"() {
        when:
        script.toolDeleteFile([fileName: 'x.txt', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "delete_file throws when fileName is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteFile([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fileName is required')
    }

    def "delete_file backs up then deletes a normal file"() {
        given:
        enableHubAdminWrite()
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
        result.undoHint.contains('read_file')
    }

    def "delete_file skips auto-backup on files that are already backups"() {
        given:
        enableHubAdminWrite()
        def uploads = []
        def deleted = []
        script.metaClass.downloadHubFile = { String name -> 'ignored'.getBytes('UTF-8') }
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        def result = script.toolDeleteFile([fileName: 'notes_backup_20260419-100000.txt', confirm: true])

        then: 'no backup-of-a-backup is written'
        uploads == []
        deleted == ['notes_backup_20260419-100000.txt']

        and:
        result.success == true
        result.message.contains('Backup file')
        result.message.contains('deleted permanently')
    }

    def "delete_file reports failure without throwing when deleteHubFile errors"() {
        given:
        enableHubAdminWrite()
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
        result.suggestion.contains('list_files')
    }
}
