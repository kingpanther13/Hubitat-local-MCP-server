package server

import support.ToolSpecBase

/**
 * Spec for the opToken idempotency machinery (issue #348, section A) in
 * hubitat-mcp-server.groovy::handleToolsCall. Token processing happens ONCE per
 * request in handleToolsCall (never in executeTool, which gateway re-entry would
 * double-process), so these drive the full envelope via mcpDriver.callTool and
 * assert on the returned inner result + the atomicState.opTokens marker + the
 * buffered File-Manager result file.
 *
 * Contract under test:
 *   - extract opToken from args.opToken OR the gateway inner args.args.opToken
 *   - validate 8-128 chars, charset [A-Za-z0-9._-]; invalid -> IAE -> -32602
 *   - process the token ONLY when the resolved leaf is a WRITE tool (reads ignore it)
 *   - dedup: running -> isError status:running (do NOT re-run the write);
 *            complete -> replay the buffered result + replayed:true (no re-run);
 *            complete-but-file-swept -> status:unknown
 *   - a started marker is written before the tool runs and completed on EVERY
 *     terminal path (success, thrown IAE, generic throw)
 *   - _opTokenMark prunes >24h entries (deleting their result files) and caps at 50
 *   - _opTokenComplete buffers the result to mcp-op-result-<token>.json (with an
 *     inline / failed_buffer fallback when the upload fails)
 *
 * Mocking strategy (see docs/testing.md): uploadHubFile / downloadHubFile /
 * deleteHubFile are stubbed per-test on script.metaClass, exactly like the
 * McpFilesLib specs. A write tool is stubbed via metaClass (toolCreateRoom) so the
 * token machinery is exercised without wiring the room tool's own hub calls; the
 * central Write master (enableWrite default ON) still lets the write through.
 */
class OpTokenReplaySpec extends ToolSpecBase {

    private static final String FILE_PREFIX = 'mcp-op-result-'
    private static final long FIXED_NOW = 1234567890000L
    private static final long DAY_MS = 24L * 60L * 60L * 1000L

    // A byte-array store shared by the upload/download stubs so a buffered result
    // round-trips exactly the way it would through the hub File Manager.
    private Map<String, byte[]> installFileStore() {
        Map<String, byte[]> store = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content -> store[name] = content }
        script.metaClass.downloadHubFile = { String name -> store[name] }
        script.metaClass.deleteHubFile = { String name -> store.remove(name) }
        return store
    }

    // ---------------- full-path dedup / replay / marker completion ----------------

    def "a valid token on a write tool marks running then completes and buffers the result"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 7, name: 'Den'] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'abc12345'])

        then: 'the write ran exactly once and the room result came back'
        ran == 1
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.roomId == 7

        and: 'the marker is completed (not left running) and names its buffer file'
        def marker = atomicStateMap.opTokens['abc12345']
        marker.state == 'complete'
        marker.tool == 'hub_create_room'
        marker.isError == false
        marker.file == FILE_PREFIX + 'abc12345.json'

        and: 'the result was buffered to the reserved-prefix file'
        store.containsKey(FILE_PREFIX + 'abc12345.json')
    }

    def "a token on a READ tool is ignored -- no marker, no buffer file"() {
        given:
        settingsMap.enableRead = true
        def uploads = 0
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads++ }
        script.metaClass.getRooms = { -> [] }

        when: 'a read tool carrying a well-formed token'
        def response = mcpDriver.callTool('hub_list_rooms', [opToken: 'readtoken1'])

        then: 'the read succeeds and the token was silently ignored -- no marker for it, nothing buffered'
        response.error == null
        !response.result.isError
        !atomicStateMap.opTokens?.containsKey('readtoken1')
        uploads == 0
    }

    def "re-issuing a token whose op is still running is refused without re-running the write"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        atomicStateMap.opTokens = [
            runtok1234: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW - 3000L]
        ]
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 9] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'runtok1234'])

        then: 'the write did NOT run again (dedup short-circuits before executeTool)'
        ran == 0

        and: 'an isError envelope carrying the running status + recovery guidance'
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.status == 'running'
        inner.opToken == 'runtok1234'
        inner.tool == 'hub_create_room'
        inner.startedAt == FIXED_NOW - 3000L
        inner.note instanceof String && inner.note.contains('hub_get_op_result')
    }

    def "re-issuing a completed token replays the buffered result with replayed=true and does not re-run the write"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 11, name: 'Den'] }

        when: 'first call commits and buffers under the token'
        def first = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'donetoken1'])

        and: 're-issue the identical call with the same token'
        def second = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'donetoken1'])

        then: 'the write ran exactly once across both calls'
        ran == 1

        and: 'the replay returns the original result plus replayed:true (round-tripped through the buffered file)'
        def firstInner = mcpDriver.parseInner(first)
        firstInner.roomId == 11
        def replayInner = mcpDriver.parseInner(second)
        replayInner.replayed == true
        replayInner.roomId == 11
    }

    def "a completed token whose buffer file was swept returns status indeterminate, never the safe-to-retry unknown"() {
        given:
        settingsMap.enableWrite = true
        // Marker says complete, but the result file is gone (24h sweep) -> downloadHubFile null.
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.downloadHubFile = { String name -> null }
        atomicStateMap.opTokens = [
            swept123456: [state: 'complete', tool: 'hub_create_room', isError: false,
                          finishedAt: FIXED_NOW - 1000L, file: FILE_PREFIX + 'swept123456.json']
        ]
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'swept123456'])

        then: 'the write is NOT re-run; the caller is told the result expired but the op DID complete'
        ran == 0
        def inner = mcpDriver.parseInner(response)
        inner.status == 'indeterminate'
        inner.note instanceof String && inner.note.toLowerCase().contains('verify')
    }

    def "the marker is completed with isError when the write throws IllegalArgumentException"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        script.metaClass.toolCreateRoom = { a -> throw new IllegalArgumentException('bad room name') }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'iaetoken12'])

        then: 'the validation error still maps to -32602'
        response.error != null
        response.error.code == -32602

        and: 'no token is left eternally running -- the terminal IAE path completes the marker'
        def marker = atomicStateMap.opTokens['iaetoken12']
        marker.state == 'complete'
        marker.isError == true
        store.containsKey(FILE_PREFIX + 'iaetoken12.json')
    }

    def "the marker is completed with isError when the write throws a generic exception"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        script.metaClass.toolCreateRoom = { a -> throw new RuntimeException('hub blew up') }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'gentoken12'])

        then: 'generic tool errors stay in the success channel with isError:true (MCP spec)'
        response.error == null
        response.result.isError == true

        and: 'the marker still reaches a completed, isError state'
        def marker = atomicStateMap.opTokens['gentoken12']
        marker.state == 'complete'
        marker.isError == true
        store.containsKey(FILE_PREFIX + 'gentoken12.json')
    }

    def "the marker is completed with isError when the write returns null (internal tool bug path)"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        script.metaClass.toolCreateRoom = { a -> null }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'nulltoken1'])

        then: 'the null result surfaces as an isError envelope'
        response.error == null
        response.result.isError == true

        and: 'the marker still completes -- no eternal running'
        def marker = atomicStateMap.opTokens['nulltoken1']
        marker.state == 'complete'
        marker.isError == true
        store.containsKey(FILE_PREFIX + 'nulltoken1.json')
    }

    def "the marker is completed with isError when the write returns a non-serializable result"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        script.metaClass.toolCreateRoom = { a -> [success: true, oops: { -> 'a closure' }] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'sertoken123'])

        then: 'the serializer failure surfaces as an isError envelope'
        response.error == null
        response.result.isError == true

        and: 'the marker completes with the error envelope buffered'
        def marker = atomicStateMap.opTokens['sertoken123']
        marker.state == 'complete'
        marker.isError == true
        store.containsKey(FILE_PREFIX + 'sertoken123.json')
    }

    def "an oversize result buffers the REAL result under the token, not the too-large envelope"() {
        given: 'a result whose wire encoding trips the 120KB response guard'
        settingsMap.enableWrite = true
        def store = installFileStore()
        def bigPayload = 'x' * 130000
        script.metaClass.toolCreateRoom = { a -> [success: true, blob: bigPayload] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'bigtoken123'])

        then: 'the wire response is the too-large envelope'
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.response_too_large == true

        and: 'the buffered result is the REAL oversize payload -- captured before the size guard'
        def marker = atomicStateMap.opTokens['bigtoken123']
        marker.state == 'complete'
        marker.isError == false
        new String(store[FILE_PREFIX + 'bigtoken123.json'], 'UTF-8').contains(bigPayload)
    }

    def "the token nested in gateway inner args is extracted (args.args.opToken)"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        script.metaClass.toolCreateRoom = { a -> [success: true, roomId: 3] }

        when: 'a gateway-wrapped write whose opToken rides in the inner args'
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: [name: 'Den', confirm: true, opToken: 'gwtoken123']])

        then: 'the token was processed -- a marker exists for it'
        response.error == null
        atomicStateMap.opTokens['gwtoken123']?.state == 'complete'
    }

    @spock.lang.Unroll
    def "an invalid token on a write tool is rejected with -32602 (token=#label)"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: token])

        then: 'validation rejects it (a caller-recoverable bad arg) before the write runs'
        ran == 0
        response.error != null
        response.error.code == -32602

        where:
        label            | token
        'too short'      | 'abc'
        'bad charset'    | 'abc!defg'
        'has a space'    | 'abc defgh'
        'too long'       | ('a' * 129)
    }

    // ---------------- _opTokenMark: started marker, TTL prune, size cap ----------------

    def "_opTokenMark writes a running marker stamped with the current clock"() {
        when:
        script._opTokenMark('marktok12', 'hub_create_room')

        then:
        def marker = atomicStateMap.opTokens['marktok12']
        marker.state == 'running'
        marker.tool == 'hub_create_room'
        marker.startedAt == FIXED_NOW
    }

    def "_opTokenMark prunes entries older than 24h and best-effort deletes their result files"() {
        given:
        def deleted = []
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        atomicStateMap.opTokens = [
            stale123456: [state: 'complete', tool: 'hub_create_room',
                          startedAt: FIXED_NOW - DAY_MS - 1L, file: FILE_PREFIX + 'stale123456.json'],
            fresh123456: [state: 'complete', tool: 'hub_create_room', startedAt: FIXED_NOW - 1000L]
        ]

        when:
        script._opTokenMark('newmark123', 'hub_create_room')

        then: 'the >24h entry is gone, its file deleted; the fresh entry and the new marker survive'
        !atomicStateMap.opTokens.containsKey('stale123456')
        deleted.contains(FILE_PREFIX + 'stale123456.json')
        atomicStateMap.opTokens.containsKey('fresh123456')
        atomicStateMap.opTokens.containsKey('newmark123')
    }

    def "_opTokenMark caps the map at 50 entries, dropping the oldest first"() {
        given: '50 fresh entries, op_00 the oldest'
        def seed = [:]
        (0..49).each { i ->
            def key = "op_${String.format('%02d', i)}".toString()
            seed[key] = [state: 'complete', tool: 'hub_create_room', startedAt: FIXED_NOW - (50L - i)]
        }
        atomicStateMap.opTokens = seed

        when:
        script._opTokenMark('capnew1234', 'hub_create_room')

        then: 'the map is held at 50 and the single oldest entry was evicted for the newcomer'
        atomicStateMap.opTokens.size() == 50
        !atomicStateMap.opTokens.containsKey('op_00')
        atomicStateMap.opTokens.containsKey('capnew1234')
    }

    def "the size cap never evicts a running token -- the oldest TERMINAL record goes instead"() {
        given: '50 fresh entries; the oldest is still running, the next-oldest is complete'
        def seed = [:]
        (0..49).each { i ->
            def key = "op_${String.format('%02d', i)}".toString()
            seed[key] = [state: (i == 0 ? 'running' : 'complete'), tool: 'hub_create_room',
                         startedAt: FIXED_NOW - (50L - i)]
        }
        atomicStateMap.opTokens = seed

        when:
        script._opTokenMark('capnew1234', 'hub_create_room')

        then: 'the live op_00 survives; op_01 (oldest terminal) was evicted'
        atomicStateMap.opTokens.size() == 50
        atomicStateMap.opTokens.containsKey('op_00')
        !atomicStateMap.opTokens.containsKey('op_01')
        atomicStateMap.opTokens.containsKey('capnew1234')
    }

    def "replaying a paused in_progress result carries a replayNote so a spent-token resume cannot loop silently"() {
        given: 'a completed token whose buffered result is a paused in_progress envelope'
        settingsMap.enableWrite = true
        script.metaClass.downloadHubFile = { String name ->
            '{"success":true,"status":"in_progress","patchesRemaining":[{"x":1}]}'.getBytes('UTF-8')
        }
        atomicStateMap.opTokens = [
            pausedtok99: [state: 'complete', tool: 'hub_create_room', isError: false,
                          finishedAt: FIXED_NOW - 1000L, file: FILE_PREFIX + 'pausedtok99.json']
        ]
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when: 'the same token is re-issued as if it could resume'
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'pausedtok99'])

        then: 'no re-run; the replay is flagged as the original paused envelope'
        ran == 0
        def inner = mcpDriver.parseInner(response)
        inner.replayed == true
        inner.status == 'in_progress'
        inner.replayNote instanceof String && inner.replayNote.toLowerCase().contains('fresh')
    }

    // ---------------- _opTokenComplete: buffering + fallbacks ----------------

    def "_opTokenComplete buffers the result to the reserved file and flips the marker to complete"() {
        given:
        def store = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content -> store[name] = new String(content, 'UTF-8') }
        atomicStateMap.opTokens = [ct123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW - 500L]]

        when:
        script._opTokenComplete('ct123456', '{"success":true,"x":1}', false)

        then: 'the exact bytes land in mcp-op-result-<token>.json'
        store[FILE_PREFIX + 'ct123456.json'] == '{"success":true,"x":1}'

        and: 'the marker is completed while preserving tool + startedAt'
        def marker = atomicStateMap.opTokens['ct123456']
        marker.state == 'complete'
        marker.isError == false
        marker.tool == 'hub_create_room'
        marker.startedAt == FIXED_NOW - 500L
        marker.finishedAt == FIXED_NOW
        marker.file == FILE_PREFIX + 'ct123456.json'
    }

    def "_opTokenComplete falls back to inline storage when the upload fails and the result is small"() {
        given:
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('storage full') }
        atomicStateMap.opTokens = [inl123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW]]
        def small = '{"success":true}'

        when:
        script._opTokenComplete('inl123456', small, false)

        then: 'a small result is kept inline on the marker so replay still works'
        def marker = atomicStateMap.opTokens['inl123456']
        marker.state == 'complete'
        marker.inline == small
    }

    def "_opTokenComplete marks failed_buffer when the upload fails and the result is too large to inline"() {
        given:
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('storage full') }
        atomicStateMap.opTokens = [big123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW]]
        def big = '{"blob":"' + ('x' * 3000) + '"}'

        when:
        script._opTokenComplete('big123456', big, false)

        then: 'an un-bufferable oversize result is flagged rather than inlined'
        atomicStateMap.opTokens['big123456'].state == 'failed_buffer'
    }
}
