package server

import me.biocomp.hubitat_ci.api.common_api.ChildDeviceWrapper
import me.biocomp.hubitat_ci.app.HubitatAppScript
import org.spockframework.mock.IMockInvocation
import spock.lang.Shared
import spock.lang.Unroll
import support.LoopbackMcpServer
import support.SoftwareHubInstalledApp
import support.SoftwareHubPlatform
import support.TestHub
import support.TestLocation
import support.ToolSpecBase
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class EmulatedHubHttpSpec extends ToolSpecBase {
    @Shared SoftwareHubPlatform platform
    @Shared SoftwareHubInstalledApp installedApp
    @Shared TestLocation softwareLocation = new TestLocation(hub: new TestHub(localIP: '127.0.0.1'))
    private LoopbackMcpServer http
    private Process client

    def setupSpec() {
        appExecutor.getApp() >> { installedApp }
        appExecutor.getLocation() >> softwareLocation
        appExecutor.getExceptionMessageWithLine(_ as Throwable) >> { args -> platform.exceptionMessage(args[0] as Throwable) }
        appExecutor.getStackTrace(_ as Throwable) >> { args -> platform.exceptionTrace(args[0] as Throwable) }
        // Spock matches the first applicable interaction. The base harness and
        // lane-specific allowed interactions above precede this final fallback.
        _ * appExecutor._(*_) >> { IMockInvocation invocation ->
            String operation = 'AppExecutor:' + invocation.method.name
            platform.unsupported << operation
            throw new UnsupportedOperationException('Software hub does not implement ' + operation)
        }
    }

    def setup() {
        platform = new SoftwareHubPlatform()
        installedApp = new SoftwareHubInstalledApp(id: 1L, label: 'Software MCP', preferences: settingsMap)
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        settingsMap.enableDeveloperMode = true
        settingsMap.enableLocalAccess = true
        settingsMap.enableCloudAccess = false
        stateMap.lastBackupTimestamp = 1234567890000L
        atomicStateMap.protectedAppsPolicy = [ids: ['1']]
        settingsMap.protectedAppIds = ['1']
        platform.devices['999'] = SoftwareHubPlatform.switchModel('999', 'unselected', 'Unselected fixture')
        script.metaClass.hubInternalGet = { String path, Map query = [:], Integer timeout = 30 -> platform.get(path, query) }
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> platform.postJson(path, body) }
        script.metaClass.getAllGlobalVars = { -> platform.variables }
        script.metaClass.getGlobalVar = { String name -> platform.variables[name] }
        script.metaClass.setGlobalVar = { String name, Object value -> platform.setVariable(name, value) }
        script.metaClass.downloadHubFile = { String name -> platform.download(name) }
        script.metaClass.uploadHubFile = { String name, byte[] data -> platform.upload(name, data) }
        script.metaClass.deleteHubFile = { String name -> platform.deleteFile(name) }
        def factory = HubitatAppScript.getDeclaredField('childDeviceFactory')
        factory.accessible = true
        factory.set(script, { Object... args ->
            if (args.length == 1 && args[0] == 'list') return childDevicesList
            if (args[0] == 'delete') {
                String dni = args[2].toString()
                platform.delete(dni)
                childDevicesList.removeAll { it.deviceNetworkId == dni }
                return null
            }
            Map model = platform.create(args[0].toString(), args[1].toString(), args[2].toString(), args[4] as Map)
            String id = model.device.id.toString()
            String dni = model.device.deviceNetworkId.toString()
            def device = Mock(ChildDeviceWrapper) {
                getId() >> id
                getIdAsLong() >> id.toLong()
                getDeviceNetworkId() >> dni
            }
            childDevicesList << device
            device
        })
        // Only the background read worker runs immediately. Long-lived cleanup timers remain recorded.
        RUN_IN_MILLIS_OVERRIDE.set({ List scheduled ->
            if (scheduled[1] != 'runMrtrSlice') {
                platform.unsupported << 'timer:' + scheduled[1]
                throw new UnsupportedOperationException('Software hub does not implement timer: ' + scheduled[1])
            }
            runInMillisCalls << scheduled
            script."${scheduled[1]}"(new LinkedHashMap(scheduled[2].data as Map))
        })
        RUN_IN_OVERRIDE.set({ List scheduled ->
            if (scheduled[1] != 'runMrtrCleanup') {
                platform.unsupported << 'timer:' + scheduled[1]
                throw new UnsupportedOperationException('Software hub does not implement timer: ' + scheduled[1])
            }
            runInCalls << scheduled
        })
    }

    def cleanup() {
        if (client?.isAlive()) {
            client.destroyForcibly()
            client.waitFor(5, TimeUnit.SECONDS)
        }
        http?.close()
    }

    @Unroll
    def 'production MCP over HTTP performs software platform CRUD in #mode mode'() {
        given:
        settingsMap.useGateways = mode == 'gateway'
        http = new LoopbackMcpServer()
        File transcript = new File('ci/emulated-hub/build/scenario-' + mode + '.log')
        transcript.parentFile.mkdirs()
        client = new ProcessBuilder(System.getProperty('emulatedHub.python', 'python'), '-u',
            'ci/emulated-hub/scenario.py', http.baseUrl, mode)
            .redirectErrorStream(true).redirectOutput(transcript).start()
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
        int requestCount = 0

        when:
        while (client.isAlive() && System.nanoTime() < deadline) {
            Map request = http.requests.poll(100, TimeUnit.MILLISECONDS)
            if (request == null) continue
            requestCount++
            mcpDriver.lastRenderArgs = null
            mcpDriver.requestSource = 'local'
            mcpDriver.pushHeaders(request.headers)
            if (request.parseFailure != null) mcpDriver.pushBodyThrowing(request.parseFailure as Throwable)
            else mcpDriver.pushBody(request.body)
            try {
                if (request.suffix == 'health' && request.method == 'GET') script.handleHealth()
                else if (request.method == 'POST') script.handleMcpRequest()
                else if (request.method == 'GET') script.handleMcpGet()
                else throw new UnsupportedOperationException('Unsupported HTTP method: ' + request.method)
                assert mcpDriver.lastRenderArgs != null: 'Production handler did not render a response'
                request.response.complete(new LinkedHashMap(mcpDriver.lastRenderArgs))
            } catch (Throwable error) {
                request.response.completeExceptionally(error)
                throw error
            }
        }

        then:
        assert !client.isAlive(): 'Python HTTP scenario exceeded four minutes; transcript: ' + transcript.text
        assert client.exitValue() == 0: transcript.text
        transcript.text.contains('"passed": true')
        requestCount > 35
        platform.commands*.command == ['on', 'off', 'on']
        platform.commands*.id.unique().size() == 1
        platform.variables.software_counter.value == 7
        !stateMap.ruleVariables
        platform.devices.keySet() == ['999'] as Set
        childDevicesList.isEmpty()
        settingsMap.bypassDeviceAllowlist == false
        installedApp.settingsStore.bypassDeviceAllowlist.value == false
        !platform.files.containsKey('software.txt')
        // Overwrite and deletion can use the same second-resolution backup name.
        // The Python scenario verifies original backup bytes before deleting the file.
        platform.files.keySet().any { it.startsWith('software_backup_') }
        platform.unsupported == ['driver:software:Not implemented']

        where:
        mode << ['flat', 'gateway']
    }

    def 'separate software platform instances do not share state and unknown operations fail'() {
        given:
        def first = new SoftwareHubPlatform()
        def second = new SoftwareHubPlatform()
        first.create('hubitat', 'Virtual Switch', 'isolated', [label: 'First'])
        first.setVariable('software_counter', 99)
        first.upload('isolated.txt', 'first'.getBytes('UTF-8'))

        expect:
        second.devices.isEmpty()
        second.variables.software_counter.value == 1
        second.files.isEmpty()

        when:
        second.get('/unsupported-platform-operation')

        then:
        def error = thrown(UnsupportedOperationException)
        error.message.contains('does not implement')
        second.unsupported == ['GET:/unsupported-platform-operation']
    }

    def 'unmodeled executor APIs and timers fail instead of returning mock defaults'() {
        given:
        def diagnostic = new IllegalArgumentException('software diagnostic probe')

        expect:
        appExecutor.getState().is(stateMap)
        appExecutor.getSettings().is(settingsMap)
        appExecutor.getApp().is(installedApp)
        script._exceptionWithLine(diagnostic).contains('IllegalArgumentException: software diagnostic probe')
        script._exceptionStack(diagnostic).contains('IllegalArgumentException: software diagnostic probe')
        script._exceptionStack(diagnostic).contains(diagnostic.stackTrace[0].toString())

        when:
        appExecutor.unschedule()

        then:
        def apiError = thrown(UnsupportedOperationException)
        apiError.message.contains('AppExecutor:unschedule')

        when:
        appExecutor.runIn(1, 'runPackageDeploy', [data: [:]])

        then:
        def timerError = thrown(UnsupportedOperationException)
        timerError.message.contains('timer: runPackageDeploy')
        platform.unsupported == ['AppExecutor:unschedule', 'timer:runPackageDeploy']
        runInCalls.isEmpty()
    }

    def 'shutdown promptly releases both queued and already polled response futures'() {
        given:
        def wireClient = HttpClient.newHttpClient()
        List<Boolean> released = []

        when:
        [false, true].each { boolean pollRequest ->
            http = new LoopbackMcpServer()
            def wireRequest = HttpRequest.newBuilder(URI.create(http.baseUrl + '/apps/api/1/mcp?access_token=software-only-token'))
                .POST(HttpRequest.BodyPublishers.ofString('{}')).build()
            def wireResponse = wireClient.sendAsync(wireRequest, HttpResponse.BodyHandlers.ofString())
            try {
                Map request = null
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (request == null && System.nanoTime() < deadline) {
                    request = pollRequest ? http.requests.poll(100, TimeUnit.MILLISECONDS) : http.requests.peek()
                    if (!pollRequest && request == null) Thread.sleep(10)
                }
                assert request != null: 'HTTP request never reached the bridge'
                assert http.requests.size() == (pollRequest ? 0 : 1)
                http.close()
                assert http.requests.isEmpty()
                assert request.response.isCompletedExceptionally()
                try {
                    request.response.get(2, TimeUnit.SECONDS)
                    assert false: 'Abandoned response unexpectedly succeeded'
                } catch (ExecutionException error) {
                    assert error.cause instanceof IllegalStateException
                    assert error.cause.message == 'Loopback MCP server closed'
                    released << true
                }
            } finally {
                wireResponse.cancel(true)
                http.close()
            }
        }

        then:
        released == [true, true]
    }
}
