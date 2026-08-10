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
