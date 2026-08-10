package support

/**
 * Base class for server-tool specs. Extends HarnessSpec — the fixture maps
 * (settingsMap, childDevicesList, childAppsList) and all runtime shims
 * (state, atomicState, log, now) are already wired via HarnessSpec's
 * AppExecutor mock + userSettingValues. This class currently just exists
 * so tests can clearly express "this is a server tool spec" and we have a
 * place to add tool-test-specific helpers later.
 */
abstract class ToolSpecBase extends HarnessSpec {

    def setup() {
        // Belt AND braces, because the two CI lanes route runIn differently: under
        // eighty20results (Groovy 3.0) it reaches the AppExecutor mock, where HarnessSpec's
        // permanent stub records it; under joelwetzel (the Groovy 2.5 lane) that stub never
        // fires and only a script-level override sees the call. Installed AFTER HarnessSpec's
        // metaClass wipe so it survives the test, and appending to the same list means an
        // assertion reads the same place whichever fork is running. The positive control in
        // OpTokenReplaySpec is what proves the recorder is live rather than silently inert.
        script.metaClass.runIn = { Object delay, String handler -> runInCalls << [delay, handler] }
    }

    /**
     * Record every uploaded file name EXCEPT the op-token result buffers. Auto-tokened writes
     * upload one of those per call, so a spec asserting on "what did this tool write" has to
     * exclude them or its expectations shift with unrelated token machinery. Owning the prefix
     * here means a rename lands in one place instead of every spec that filters on it.
     */
    protected void ignoreOpResultUploads(List sink) {
        script.metaClass.uploadHubFile = { String name, byte[] content ->
            if (!name.startsWith('mcp-op-result-')) sink << name
        }
    }

    /**
     * In-memory File Manager: upload/download/delete round-trip exactly as they would through
     * the hub, and the returned map IS the store so a spec can assert on its contents.
     */
    protected Map<String, byte[]> installOpTokenFileStore() {
        Map<String, byte[]> store = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content -> store[name] = content }
        script.metaClass.downloadHubFile = { String name -> store[name] }
        script.metaClass.deleteHubFile = { String name -> store.remove(name) }
        return store
    }
}
