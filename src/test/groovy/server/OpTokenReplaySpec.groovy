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
 *   - process the token for EVERY leaf, reads included (issue #351: an expensive
 *     read that outlives its transport replays instead of re-running)
 *   - dedup: running -> isError status:running (do NOT re-run the write);
 *            complete -> replay the buffered result + replayed:true (no re-run);
 *            complete-but-file-swept -> status:indeterminate (never the
 *            safe-to-retry unknown -- the op DID run here)
 *   - a started marker is written before the tool runs and completed on EVERY
 *     terminal path (success, thrown IAE, generic throw, null result, oversize)
 *   - _opTokenMark prunes >24h entries (deleting their result files) and caps at 50
 *   - _opTokenComplete stores results up to 8KB INLINE (pendingUpload) and defers the
 *     mcp-op-result-<token>.json upload to the debounced _opTokenUploadSweep -- the
 *     synchronous per-write upload was ~1.2s of tokened-call latency; bigger results
 *     upload synchronously (with the inline / failed_buffer fallback on failure)
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

    // runIn is an AppExecutor API method (metaClass stubbing can silently no-op for those --
    // see ToolDestructiveHubOpsSpec's asynchttpGet note), so the sweep-scheduling capture
    // uses the additive-stub pattern on the shared per-spec-class mock.
    @spock.lang.Shared
    List sweepSchedules = []

    def setupSpec() {
        appExecutor.runIn(*_) >> { args -> sweepSchedules << args[1].toString() }
    }

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

        and: 'the marker is completed (not left running) with the result INLINE pending the deferred upload -- the synchronous per-write upload was the measured ~1.2s/write tokened-call overhead'
        def marker = atomicStateMap.opTokens['abc12345']
        marker.state == 'complete'
        marker.tool == 'hub_create_room'
        marker.isError == false
        marker.pendingUpload == true
        marker.inline instanceof String
        !store.containsKey(FILE_PREFIX + 'abc12345.json')

        when: 'the debounced sweep runs'
        script._opTokenUploadSweep()

        then: 'the result moved to the reserved-prefix file and the inline copy was stripped'
        def swept = atomicStateMap.opTokens['abc12345']
        swept.file == FILE_PREFIX + 'abc12345.json'
        swept.inline == null
        swept.pendingUpload == null
        store.containsKey(FILE_PREFIX + 'abc12345.json')
    }

    def "a token on a READ tool is honoured too: marker, buffer, and replay on re-issue"() {
        given: 'issue #351: tokens work on EVERY tool -- an expensive read that outlives its transport replays instead of re-running'
        settingsMap.enableRead = true
        installFileStore()
        def ran = 0
        script.metaClass.getRooms = { -> ran++; [] }

        when: 'a read tool carrying a well-formed token, then the token-only re-issue'
        def first = mcpDriver.callTool('hub_list_rooms', [opToken: 'readtoken12'])
        def second = mcpDriver.callTool('hub_list_rooms', [opToken: 'readtoken12'])

        then: 'the first call ran and completed the marker'
        first.error == null
        !first.result.isError
        atomicStateMap.opTokens['readtoken12']?.state == 'complete'

        and: 'the re-issue replays the buffered result without re-running the read'
        ran == 1
        second.error == null
        mcpDriver.parseInner(second).replayed == true
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
        inner.elapsedMs == 3000L
        inner.note instanceof String && inner.note.contains('opToken') && !inner.note.contains('hub_get_op_result')
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

        and: 'the replay returns the original result plus replayed:true -- served from the INLINE copy (no sweep ran, no file exists yet), pinning the pre-sweep replay gap'
        def firstInner = mcpDriver.parseInner(first)
        firstInner.roomId == 11
        def replayInner = mcpDriver.parseInner(second)
        replayInner.replayed == true
        replayInner.roomId == 11
        !store.containsKey(FILE_PREFIX + 'donetoken1.json')
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

    def "a validation error RELEASES the token instead of spending it (issue #361 review)"() {
        given:
        settingsMap.enableWrite = true
        def store = installFileStore()
        script.metaClass.toolCreateRoom = { a -> throw new IllegalArgumentException('bad room name') }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'iaetoken12'])

        then: 'the validation error still maps to -32602'
        response.error != null
        response.error.code == -32602

        and: 'the leaf executed nothing, so the token is released (a per-entry sentinel the dedup gate treats as absent) -- not left running, not spent on the stale rejection'
        atomicStateMap.opTokens['iaetoken12'].state == 'released'
        !store.containsKey(FILE_PREFIX + 'iaetoken12.json')
    }

    def "a corrected re-issue with the SAME token executes after a validation error"() {
        given: 'the real-world footgun: first call rejected for bad args, caller fixes them and retries with the same token'
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a ->
            if (a.confirm != true) throw new IllegalArgumentException('confirm required')
            ran++; [success: true, roomId: 7]
        }

        when: 'first call fails validation (no confirm)'
        def first = mcpDriver.callTool('hub_create_room', [name: 'Den', opToken: 'retrytok123'])

        and: 'the corrected call reuses the SAME token'
        def second = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'retrytok123'])

        then: 'the rejection did not spend the token: the corrected call ran fresh (no stale -32602 replay)'
        first.error?.code == -32602
        ran == 1
        mcpDriver.parseInner(second).success == true
        atomicStateMap.opTokens['retrytok123'].state == 'complete'
        atomicStateMap.opTokens['retrytok123'].isError == false
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

        and: 'the marker still reaches a completed, isError state (inline until the sweep uploads)'
        def marker = atomicStateMap.opTokens['gentoken12']
        marker.state == 'complete'
        marker.isError == true
        marker.inline instanceof String

        and: 'the sweep lands the buffer file'
        script._opTokenUploadSweep()
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
        marker.inline instanceof String

        and: 'the sweep lands the buffer file'
        script._opTokenUploadSweep()
        store.containsKey(FILE_PREFIX + 'nulltoken1.json')
    }

    // The non-serializable terminal path has no portable trigger: JsonOutput serializes
    // Closures as {} on current Groovy, and the genuinely-failing inputs (circular maps)
    // die with StackOverflowError, not Exception. The branch is line-for-line parallel to
    // the null-result path pinned above, which covers the marker-completion guarantee.

    // ---------------- deferred-upload sweep lifecycle ----------------

    def "a sweep upload failure keeps a small result permanently inline (replayable) and clears pendingUpload"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        script.metaClass.toolCreateRoom = { a -> [success: true, roomId: 3] }
        mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'failup12345'])
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('quota') }

        when:
        script._opTokenUploadSweep()

        then: 'small results fall back to the permanent-inline contract; the sweep never spins on the record'
        def rec = atomicStateMap.opTokens['failup12345']
        rec.state == 'complete'
        rec.inline instanceof String
        rec.pendingUpload == null

        and: 'the replay still serves it'
        mcpDriver.parseInner(mcpDriver.callTool('hub_create_room', [opToken: 'failup12345'])).replayed == true
    }

    def "a sweep upload failure on a mid-size result downgrades to failed_buffer (too big to stay inline)"() {
        given: 'a result in the deferred band (2049-8192 bytes) whose upload then fails'
        settingsMap.enableWrite = true
        installFileStore()
        def blob = 'x' * 4000
        script.metaClass.toolCreateRoom = { a -> [success: true, blob: blob] }
        mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'failbig1234'])
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('quota') }

        when:
        script._opTokenUploadSweep()

        then: 'the record downgrades to failed_buffer rather than bloating atomicState forever'
        def rec = atomicStateMap.opTokens['failbig1234']
        rec.state == 'failed_buffer'
        rec.inline == null
        rec.pendingUpload == null
    }

    def "an over-8KB result skips the deferred path and uploads synchronously"() {
        given: 'bigger than the deferred cap but under the 120KB response guard'
        settingsMap.enableWrite = true
        def store = installFileStore()
        def blob = 'x' * 20000
        script.metaClass.toolCreateRoom = { a -> [success: true, blob: blob] }

        when:
        mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'synctok1234'])

        then: 'the file exists immediately -- no inline copy of a 20KB payload ever sits in atomicState'
        def rec = atomicStateMap.opTokens['synctok1234']
        rec.file == FILE_PREFIX + 'synctok1234.json'
        rec.inline == null
        rec.pendingUpload == null
        store.containsKey(FILE_PREFIX + 'synctok1234.json')
    }

    def "the sweep debounce guard schedules once per burst and the sweep re-arms it"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        sweepSchedules.clear()
        script.metaClass.toolCreateRoom = { a -> [success: true, roomId: 5] }

        when: 'two completions land in one burst'
        mcpDriver.callTool('hub_create_room', [name: 'A', confirm: true, opToken: 'burst1aaaaa'])
        mcpDriver.callTool('hub_create_room', [name: 'B', confirm: true, opToken: 'burst2aaaaa'])

        then: 'one sweep scheduled (the guard timestamp holds the second back)'
        sweepSchedules == ['_opTokenUploadSweep']

        when: 'the sweep runs (clearing the guard) and another completion lands'
        script._opTokenUploadSweep()
        mcpDriver.callTool('hub_create_room', [name: 'C', confirm: true, opToken: 'burst3aaaaa'])

        then: 'a fresh sweep is scheduled'
        sweepSchedules == ['_opTokenUploadSweep', '_opTokenUploadSweep']
    }

    def "an oversize result buffers the too-large envelope so the replay reproduces the original response"() {
        given: 'a result whose wire encoding trips the 120KB response guard'
        settingsMap.enableWrite = true
        def store = installFileStore()
        def bigPayload = 'x' * 130000
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, blob: bigPayload] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'bigtoken123'])

        then: 'the wire response is the too-large envelope'
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.response_too_large == true

        and: 'the buffered result is that SAME envelope -- the raw oversize payload could never ride the dedup short-circuit (it would trip the outer 128KB cap as an opaque -32603 on every poll)'
        def marker = atomicStateMap.opTokens['bigtoken123']
        marker.state == 'complete'
        script._opTokenUploadSweep()   // the small too-large envelope defers its upload like any other result
        !new String(store[FILE_PREFIX + 'bigtoken123.json'], 'UTF-8').contains(bigPayload)

        when: 'the token-only poll replays it'
        def second = mcpDriver.callTool('hub_create_room', [opToken: 'bigtoken123'])

        then: 'one run total; the replay is the original too-large envelope, delivered under the cap'
        ran == 1
        def replay = mcpDriver.parseInner(second)
        replay.replayed == true
        replay.response_too_large == true
    }

    def "an oversize result on a schema-advertised tool buffers isError:true so its replay cannot ride out as a schema-noncompliant success"() {
        given: 'publishOutputSchemas ON: the wire too-large envelope is flagged isError (#342) -- the buffered record must carry the SAME flag, or the replay decorates the envelope with structuredContent as a fake success'
        settingsMap.publishOutputSchemas = true
        settingsMap.useGateways = true
        settingsMap.enableRead = true
        installFileStore()
        def big = 'x' * 130000
        def ran = 0
        script.metaClass.toolGetHubInfo = { a -> ran++; [firmwareVersion: '9.9.9', blob: big] }

        when:
        def first = mcpDriver.callTool('hub_get_info', [opToken: 'bigschema123'])

        then: 'the wire envelope is too-large and isError; the marker matches it'
        first.result.isError == true
        mcpDriver.parseInner(first).response_too_large == true
        atomicStateMap.opTokens['bigschema123'].isError == true

        when: 'the token-only poll replays it'
        def second = mcpDriver.callTool('hub_get_info', [opToken: 'bigschema123'])

        then: 'one run; the replay rides the isError envelope and gains no structuredContent'
        ran == 1
        second.result.isError == true
        second.result.structuredContent == null
        mcpDriver.parseInner(second).replayed == true
        mcpDriver.parseInner(second).response_too_large == true
    }

    def "the token nested in gateway inner args is extracted (args.args.opToken) and stripped before dispatch"() {
        given: 'gateway mode pinned -- this test exercises the gateway-nested token shape'
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def received = [:]
        script.metaClass.toolCreateRoom = { a -> received.args = a; [success: true, roomId: 3] }

        when: 'a gateway-wrapped write whose opToken rides in the inner args'
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: [name: 'Den', confirm: true, opToken: 'gwtoken123']])

        then: 'the token was processed -- a marker exists for it'
        response.error == null
        atomicStateMap.opTokens['gwtoken123']?.state == 'complete'

        and: 'the leaf never sees the consumed token (strict-arg tools stay tokenable)'
        received.args instanceof Map
        !received.args.containsKey('opToken')
    }

    def "a token inside a JSON-string-encoded gateway inner args is extracted and honoured"() {
        given: 'the gateway accepts inner args as a JSON string; the token must not silently lose protection there'
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def received = [:]
        script.metaClass.toolCreateRoom = { a -> received.args = a; [success: true, roomId: 3] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: '{"name":"Den","confirm":true,"opToken":"strtoken123"}'])

        then: 'the token was extracted, processed, and stripped from what the leaf saw'
        response.error == null
        atomicStateMap.opTokens['strtoken123']?.state == 'complete'
        received.args instanceof Map
        !received.args.containsKey('opToken')
        received.args.name == 'Den'
    }

    def "a token-only poll works in the JSON-string inner-args form too"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: '{"opToken":"strpoll1234"}'])

        then: 'parsed to an empty inner map -- a pure poll, nothing ran, token not burned'
        ran == 0
        !atomicStateMap.opTokens?.containsKey('strpoll1234')
        mcpDriver.parseInner(response).status == 'unknown'
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

    // ---------------- token-only poll: the write IS the poll (issue #351) ----------------
    // Re-issuing a write with ONLY its opToken is the documented recovery poll. The
    // running/complete/indeterminate answers come from the dedup gate regardless of
    // shape; the poll shape matters for an UNSEEN token, where a required-args write
    // must answer "unknown" instead of marking the token and burning it on the
    // leaf's missing-arg validation error.

    def "a token-only call on a required-args write is a pure poll: an unseen token reports unknown without executing or marking"() {
        given:
        settingsMap.enableWrite = true
        def uploads = 0
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads++ }
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when: 'the recovery poll: the write re-issued with ONLY the token'
        def response = mcpDriver.callTool('hub_create_room', [opToken: 'pollNever12'])

        then: 'nothing ran, nothing was marked or buffered -- the token stays fresh for the real re-issue'
        ran == 0
        uploads == 0
        !atomicStateMap.opTokens?.containsKey('pollNever12')

        and: 'the caller learns the original call never arrived and how to recover (the LEAF shape keeps the generic full-args re-issue note -- the catalog-shape clause is gateway-name only)'
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.status == 'unknown'
        inner.opToken == 'pollNever12'
        inner.note instanceof String && inner.note.contains('Re-issue the ORIGINAL call (full arguments)')
    }

    def "a token-only gateway envelope ({tool, opToken}) is the same pure poll"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_create_room', opToken: 'gwpoll12345'])

        then:
        ran == 0
        !atomicStateMap.opTokens?.containsKey('gwpoll12345')
        response.result.isError == true
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "a gateway poll with the token nested in an otherwise-empty inner args is still a pure poll"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_create_room', args: [opToken: 'gwpoll23456']])

        then:
        ran == 0
        !atomicStateMap.opTokens?.containsKey('gwpoll23456')
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "a token-only poll still counts as a poll when it carries the mandatory-BPS acknowledgment key"() {
        given: 'a BPS-gated client attaches bestPracticeKey to every write -- the poll shape must tolerate it'
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_create_room', [opToken: 'bpspoll1234', bestPracticeKey: 'bps-ack-299'])

        then:
        ran == 0
        !atomicStateMap.opTokens?.containsKey('bpspoll1234')
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "an invalid token on a READ tool is rejected with -32602 -- reads validate tokens now"() {
        given: 'issue #351 behaviour change pin: pre-fold, reads silently ignored malformed tokens'
        settingsMap.enableRead = true
        def ran = 0
        script.metaClass.getRooms = { -> ran++; [] }

        when:
        def response = mcpDriver.callTool('hub_list_rooms', [opToken: 'abc'])

        then: 'validation rejects it before the read runs'
        ran == 0
        response.error != null
        response.error.code == -32602
    }

    def "the running refusal for a hub_update_package token names the lastSelfDeploy done-signal"() {
        given: 'the monolithic deploy can drop even its token completion -- the note must point at the independent signal'
        settingsMap.enableWrite = true
        installFileStore()
        atomicStateMap.opTokens = [
            pkgrun12345: [state: 'running', tool: 'hub_update_package', startedAt: FIXED_NOW - 5000L]
        ]

        when:
        def response = mcpDriver.callTool('hub_update_package', [opToken: 'pkgrun12345'])

        then:
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.status == 'running'
        inner.note.contains('lastSelfDeploy')
    }

    def "a stale gateway client's retired-name poll ({tool: hub_get_op_result} via hub_read_diagnostics) is honored too"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()

        when:
        def response = mcpDriver.callTool('hub_read_diagnostics', [tool: 'hub_get_op_result', args: [opToken: 'gwstale1234']])

        then: 'answered as a pure poll -- unknown status, token not marked'
        !atomicStateMap.opTokens?.containsKey('gwstale1234')
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "a no-op prune never rewrites the token map -- the #351 concurrency invariant"() {
        given: 'a small, fresh map: nothing expired, under the cap'
        installFileStore()
        script.metaClass.toolCreateRoom = { a -> [success: true] }
        script._opTokenMark('freshtok1234', 'hub_create_room')
        def instanceAfterMark = atomicStateMap.opTokens

        when: 'prune runs with nothing to remove'
        script._opTokenPrune()

        then: 'the stored map is the SAME instance -- no whole-map write happened, so a concurrent per-entry write could not have been clobbered'
        atomicStateMap.opTokens.is(instanceAfterMark)
        atomicStateMap.opTokens['freshtok1234'].state == 'running'
    }

    def "a tokened call with PARTIAL args is NOT a poll -- it dispatches, and the leaf's validation error releases the token"() {
        given: 'negative-space pin: only a truly bare tokened call polls; partial args mean a real (malformed) execution attempt'
        settingsMap.enableWrite = true
        installFileStore()
        script.metaClass.toolCreateRoom = { a ->
            if (a.confirm != true) throw new IllegalArgumentException('confirm required')
            [success: true]
        }

        when: 'token + name but no confirm'
        def response = mcpDriver.callTool('hub_create_room', [opToken: 'partial12345', name: 'Den'])

        then: 'the leaf validation error surfaced (-32602), and the token was released for a corrected re-issue'
        response.error != null
        response.error.code == -32602
        atomicStateMap.opTokens['partial12345'].state == 'released'
    }

    def "a token-only call naming a bare GATEWAY is a pure poll -- the token is never spent on a catalog listing"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()

        when: 'the gateway name with ONLY a token -- catalog mode is never the tokened op'
        def response = mcpDriver.callTool('hub_manage_rooms', [opToken: 'gwcat123456'])

        then:
        !atomicStateMap.opTokens?.containsKey('gwcat123456')
        mcpDriver.parseInner(response).status == 'unknown'

        and: 'the note gives the catalog-shape exit, not the generic re-issue instruction (which would loop forever -- this token can never be marked)'
        mcpDriver.parseInner(response).note.contains('WITHOUT an opToken')
    }

    def "a token-only gateway call with a DEAD tool key (empty string) is still a pure poll -- the token is not spent on the catalog"() {
        given: 'tool present but empty fails the isGatewayCall truthiness check, so without the dead-key tolerance this shape would dispatch as catalog mode and spend the token on the listing'
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: '', opToken: 'gwdead123456'])

        then:
        !atomicStateMap.opTokens?.containsKey('gwdead123456')
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "a completed token polled through the gateway envelope ({tool, opToken}) replays -- never the unknown shape"() {
        given: 'every replayed:true pin elsewhere is flat-shaped; this pins the gateway poll shape against a reorder that answers it from the poll-shape check instead of the dedup gate'
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 9] }

        when: 'the tokened write completes via the gateway, then the poll re-issues token-only through the SAME envelope'
        mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: [name: 'Den', confirm: true], opToken: 'gwreplay1234'])
        def poll = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_create_room', opToken: 'gwreplay1234'])

        then: 'one run; the gateway-shaped poll replays the buffered result'
        ran == 1
        mcpDriver.parseInner(poll).replayed == true
        mcpDriver.parseInner(poll).roomId == 9
    }

    def "an outer token plus a string-encoded inner args carrying its own opToken strips BOTH before the leaf sees the args"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()
        def received = [:]
        script.metaClass.toolCreateRoom = { a -> received.args = a; [success: true, roomId: 5] }

        when: 'token at the envelope level AND inside the JSON string (a confused client)'
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', opToken: 'outertok1234',
             args: '{"name":"Den","confirm":true,"opToken":"innertok1234"}'])

        then: 'the OUTER token won and was processed; the inner one was stripped, not leaked to the leaf'
        response.error == null
        atomicStateMap.opTokens['outertok1234']?.state == 'complete'
        !atomicStateMap.opTokens?.containsKey('innertok1234')
        received.args instanceof Map
        !received.args.containsKey('opToken')
        received.args.name == 'Den'
    }

    def "a token-only re-issue while the op is running returns the running refusal without arg validation"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        atomicStateMap.opTokens = [
            polling123: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW - 3000L]
        ]
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }

        when: 'poll by re-issuing with ONLY the token -- no name/confirm'
        def response = mcpDriver.callTool('hub_create_room', [opToken: 'polling123'])

        then:
        ran == 0
        response.result.isError == true
        mcpDriver.parseInner(response).status == 'running'
    }

    def "a token-only re-issue after completion replays the buffered result"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 4] }

        when: 'full call, then the token-only poll'
        mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'pollDone123'])
        def second = mcpDriver.callTool('hub_create_room', [opToken: 'pollDone123'])

        then: 'the write ran once; the poll replays the buffered result'
        ran == 1
        def inner = mcpDriver.parseInner(second)
        inner.replayed == true
        inner.roomId == 4
    }

    // ---------------- issue #351: the separate poll tool is retired ----------------

    def "a stale client's call to the retired hub_get_op_result name is honored as a pure poll (unknown token is not burned)"() {
        given:
        settingsMap.enableWrite = true
        def uploads = 0
        script.metaClass.uploadHubFile = { String name, byte[] content -> uploads++ }

        when: 'the retired tool name, called flat the way a stale client would'
        def response = mcpDriver.callTool('hub_get_op_result', [opToken: 'staleclient1'])

        then: 'answered as a poll -- unknown status, nothing marked, nothing buffered'
        uploads == 0
        !atomicStateMap.opTokens?.containsKey('staleclient1')
        response.result.isError == true
        mcpDriver.parseInner(response).status == 'unknown'
    }

    def "the retired name replays a completed token for a stale client (dedup answers before the shape check)"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true, roomId: 6] }

        when: 'a tokened write completes, then a stale client polls via the retired name'
        mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'staleclient2'])
        def polled = mcpDriver.callTool('hub_get_op_result', [opToken: 'staleclient2'])

        then:
        ran == 1
        def inner = mcpDriver.parseInner(polled)
        inner.replayed == true
        inner.roomId == 6
    }

    def "hub_get_op_result is fully retired: absent from the catalog, gateways, classifications, and display meta"() {
        expect:
        script.getAllToolDefinitions().every { it.name != 'hub_get_op_result' }
        script.getGatewayConfig().every { name, cfg -> !(cfg.tools?.contains('hub_get_op_result')) }
        !script.getReadOnlyToolNames().contains('hub_get_op_result')
        !script.getToolDisplayMeta().containsKey('hub_get_op_result')
    }

    // ---------------- _opTokenMark: started marker, TTL prune, size cap ----------------

    def "token records are written per-entry via updateMapValue -- successive different-token marks both survive"() {
        given: 'probe whether the script\'s atomicState exposes the per-entry API -- the groovy2x compat lane\'s older harness wraps atomicState without it, and production must work (via the fallback) either way'
        boolean perEntryAvailable
        try {
            script.atomicState.updateMapValue('opTokens', 'probe.entry1', [state: 'probe'])
            perEntryAvailable = true
        } catch (MissingMethodException ignored) {
            perEntryAvailable = false
        }
        atomicStateMap.remove('opTokens')
        int callsBefore = ((support.TestAtomicState) atomicStateMap).@updateMapValueCalls

        when: 'two different tokens marked back-to-back (the successive/parallel-client pattern)'
        script._opTokenMark('tokenAlpha1', 'hub_create_room')
        script._opTokenMark('tokenBravo1', 'hub_delete_room')

        then: 'both records survive regardless of which write path the platform offers'
        atomicStateMap.opTokens['tokenAlpha1']?.state == 'running'
        atomicStateMap.opTokens['tokenBravo1']?.state == 'running'

        and: 'when the per-entry API exists, production routed through it -- not a whole-map rewrite'
        ((support.TestAtomicState) atomicStateMap).@updateMapValueCalls == callsBefore + (perEntryAvailable ? 2 : 0)
    }

    def "_opTokenPutWholeMap (the no-updateMapValue fallback) lands the record without disturbing others"() {
        given:
        atomicStateMap.opTokens = [existing123: [state: 'complete', startedAt: FIXED_NOW - 1000L]]

        when:
        script._opTokenPutWholeMap('fallback1234', [state: 'running', startedAt: FIXED_NOW])

        then:
        atomicStateMap.opTokens['fallback1234']?.state == 'running'
        atomicStateMap.opTokens['existing123']?.state == 'complete'
    }

    def "_opTokenPut catches the missing updateMapValue and delegates to the whole-map writer (pre-2.3.2 firmware path)"() {
        given: 'whatever object the script sees as atomicState gets a THROWING updateMapValue, like a platform without the per-entry API -- the gating lane\'s TestAtomicState always implements it, so the catch-and-delegate needs a forced pin. Instance metaClass must go through setMetaClass(): .metaClass property access on a Map subclass is an entry lookup.'
        def store = script.atomicState
        def threw = false
        def emc = new ExpandoMetaClass(store.getClass(), false, true)
        emc.updateMapValue = { Object p, Object k, Object v ->
            threw = true
            throw new MissingMethodException('updateMapValue', store.getClass(), [p, k, v] as Object[])
        }
        emc.initialize()
        store.setMetaClass(emc)

        when:
        script._opTokenPut('mmefall12345', [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW])

        then: 'the record landed via the whole-map fallback'
        store.opTokens['mmefall12345'].state == 'running'

        and: 'on the shared TestAtomicState the poisoned per-entry call really threw first (the compat lane\'s wrapper lacks the method and exercises the catch naturally)'
        !(store instanceof support.TestAtomicState) || threw

        cleanup: 'the instance metaClass rides a JVM-shared store -- it must not leak into later tests'
        store?.setMetaClass(null)
    }

    def "a thrown _opTokenComplete cannot wedge the token: the finally's fallback writes failed_buffer and polls answer indeterminate"() {
        given:
        settingsMap.enableWrite = true
        installFileStore()
        def ran = 0
        script.metaClass.toolCreateRoom = { a -> ran++; [success: true] }
        script.metaClass._opTokenComplete = { String t, String j, boolean e -> throw new RuntimeException('atomicState write failed') }

        when: 'the tokened write completes but completion-buffering blows up'
        def first = mcpDriver.callTool('hub_create_room', [name: 'Den', confirm: true, opToken: 'wedgeproof12'])

        and: 'the caller later polls token-only'
        def poll = mcpDriver.callTool('hub_create_room', [opToken: 'wedgeproof12'])

        then: 'the write ran once and its own response was not disturbed'
        ran == 1
        first.error == null

        and: 'the fallback record landed: failed_buffer, clock-stamped so it lives its own TTL (a null startedAt would prune to the unsafe unknown)'
        atomicStateMap.opTokens['wedgeproof12'].state == 'failed_buffer'
        atomicStateMap.opTokens['wedgeproof12'].startedAt == FIXED_NOW

        and: 'the poll answers indeterminate -- never running-forever, never the unsafe unknown'
        mcpDriver.parseInner(poll).status == 'indeterminate'
    }

    def "the TTL sweep deletes the result file by the token's deterministic name even when the record lost its .file pointer"() {
        given: 'the prune-vs-complete race shape: an expired record with no .file'
        def deleted = []
        script.metaClass.uploadHubFile = { String name, byte[] content -> }
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        atomicStateMap.opTokens = [
            orphaned1234: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW - DAY_MS - 1L]
        ]

        when:
        script._opTokenMark('newmark45678', 'hub_create_room')

        then:
        !atomicStateMap.opTokens.containsKey('orphaned1234')
        deleted.contains(FILE_PREFIX + 'orphaned1234.json')
    }

    def "a malformed JSON-string inner args leaves the token unextracted and falls through to the gateway's own parse error"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        installFileStore()

        when: 'the string mentions opToken but is not valid JSON'
        def response = mcpDriver.callTool('hub_manage_rooms',
            [tool: 'hub_create_room', args: '{"name":"Den","opToken":"brokenstr123"'])

        then: 'no token processed, nothing marked; the gateway surfaces its own -32602 for the invalid JSON'
        !atomicStateMap.opTokens?.containsKey('brokenstr123')
        response.error != null
        response.error.code == -32602
    }

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

    def "the size cap engages past 100 stored records, batch-evicting the oldest down to 50"() {
        given: '100 fresh entries, op_00 the oldest -- exactly at the hysteresis high-water mark'
        def seed = [:]
        (0..99).each { i ->
            def key = "op_${String.format('%02d', i)}".toString()
            seed[key] = [state: 'complete', tool: 'hub_create_room', startedAt: FIXED_NOW - (100L - i)]
        }
        atomicStateMap.opTokens = seed

        when: 'the 101st record arrives'
        script._opTokenMark('capnew1234', 'hub_create_room')

        then: 'one batch eviction takes the map to 50: the 51 oldest terminal records are gone'
        atomicStateMap.opTokens.size() == 50
        !atomicStateMap.opTokens.containsKey('op_00')
        !atomicStateMap.opTokens.containsKey('op_50')
        atomicStateMap.opTokens.containsKey('op_51')
        atomicStateMap.opTokens.containsKey('capnew1234')
    }

    def "the size cap has a dead band: between 50 and 100 stored records the prune never rewrites the map"() {
        given: '60 fresh terminal records -- over the old hard cap, inside the dead band'
        def seed = [:]
        (0..59).each { i ->
            def key = "op_${String.format('%02d', i)}".toString()
            seed[key] = [state: 'complete', tool: 'hub_create_room', startedAt: FIXED_NOW - (60L - i)]
        }
        atomicStateMap.opTokens = seed
        script._opTokenPut('deadband1234', [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW])
        def instanceAfterPut = atomicStateMap.opTokens

        when: 'the prune runs at 61 stored records'
        script._opTokenPrune()

        then: 'nothing evicted and the map instance is untouched -- steady state stays free of the whole-map write (the #351 race exposure a hard at-50 cap would make permanent)'
        atomicStateMap.opTokens.is(instanceAfterPut)
        atomicStateMap.opTokens.size() == 61
        atomicStateMap.opTokens.containsKey('op_00')
    }

    def "the size cap never evicts a running token -- the oldest TERMINAL records go instead"() {
        given: '100 fresh entries; the oldest is still running, the rest are complete'
        def seed = [:]
        (0..99).each { i ->
            def key = "op_${String.format('%02d', i)}".toString()
            seed[key] = [state: (i == 0 ? 'running' : 'complete'), tool: 'hub_create_room',
                         startedAt: FIXED_NOW - (100L - i)]
        }
        atomicStateMap.opTokens = seed

        when:
        script._opTokenMark('capnew1234', 'hub_create_room')

        then: 'the live op_00 survives the batch eviction; the oldest terminal records went instead'
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

    def "_opTokenComplete stamps its own clock when the running marker was lost -- an orphaned completion never prunes to the unsafe unknown"() {
        given: 'no running marker (the prune-vs-per-entry race shape): prev is empty'
        installFileStore()
        atomicStateMap.remove('opTokens')

        when:
        script._opTokenComplete('orphandone12', '{"success":true}', false)

        then: 'the completed record carries a live startedAt, so it survives its own full TTL'
        atomicStateMap.opTokens['orphandone12'].state == 'complete'
        atomicStateMap.opTokens['orphandone12'].startedAt == FIXED_NOW
    }

    def "a non-error replay carries structuredContent for a schema-advertised base tool when publishOutputSchemas is on"() {
        given: 'spec-validating clients require structuredContent on every non-error result of an advertised tool (#342) -- the replay short-circuit must comply too'
        settingsMap.publishOutputSchemas = true
        settingsMap.useGateways = true
        settingsMap.enableRead = true
        installFileStore()
        def ran = 0
        script.metaClass.toolGetHubInfo = { a -> ran++; [firmwareVersion: '9.9.9'] }

        when: 'a tokened call to a flat core tool completes, then the token-only poll replays it'
        def first = mcpDriver.callTool('hub_get_info', [opToken: 'structok1234'])
        def second = mcpDriver.callTool('hub_get_info', [opToken: 'structok1234'])

        then: 'one run; both the original and the replay carry structuredContent'
        ran == 1
        first.result.structuredContent != null
        second.result.structuredContent != null
        second.result.structuredContent.replayed == true
        second.result.structuredContent.firmwareVersion == '9.9.9'
    }

    def "_opTokenComplete defers small results inline, and the sweep lands the exact bytes in the reserved file"() {
        given:
        def store = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content -> store[name] = new String(content, 'UTF-8') }
        atomicStateMap.opTokens = [ct123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW - 500L]]

        when:
        script._opTokenComplete('ct123456', '{"success":true,"x":1}', false)

        then: 'the marker is completed while preserving tool + startedAt, result inline pending the sweep'
        def marker = atomicStateMap.opTokens['ct123456']
        marker.state == 'complete'
        marker.isError == false
        marker.tool == 'hub_create_room'
        marker.startedAt == FIXED_NOW - 500L
        marker.finishedAt == FIXED_NOW
        marker.inline == '{"success":true,"x":1}'
        marker.pendingUpload == true
        store.isEmpty()

        when:
        script._opTokenUploadSweep()

        then: 'the exact bytes land in mcp-op-result-<token>.json and the inline copy is stripped'
        store[FILE_PREFIX + 'ct123456.json'] == '{"success":true,"x":1}'
        def swept = atomicStateMap.opTokens['ct123456']
        swept.file == FILE_PREFIX + 'ct123456.json'
        swept.inline == null
        swept.pendingUpload == null
    }

    def "_opTokenComplete keeps a small result inline regardless of File Manager health (deferred path, no upload attempted)"() {
        given:
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('storage full') }
        atomicStateMap.opTokens = [inl123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW]]
        def small = '{"success":true}'

        when:
        script._opTokenComplete('inl123456', small, false)

        then: 'the result is inline on the marker so replay works even before (or without) the sweep'
        def marker = atomicStateMap.opTokens['inl123456']
        marker.state == 'complete'
        marker.inline == small
    }

    def "_opTokenComplete marks failed_buffer when the SYNCHRONOUS upload (over the deferred cap) fails"() {
        given: 'a result too big to defer inline (>8192) whose upload then fails'
        script.metaClass.uploadHubFile = { String name, byte[] content -> throw new RuntimeException('storage full') }
        atomicStateMap.opTokens = [big123456: [state: 'running', tool: 'hub_create_room', startedAt: FIXED_NOW]]
        def big = '{"blob":"' + ('x' * 9000) + '"}'

        when:
        script._opTokenComplete('big123456', big, false)

        then: 'an un-bufferable oversize result is flagged rather than inlined'
        atomicStateMap.opTokens['big123456'].state == 'failed_buffer'
    }
}
