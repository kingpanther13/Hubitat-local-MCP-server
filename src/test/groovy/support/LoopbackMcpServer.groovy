package support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonSlurper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** HTTP worker queues requests; the Spock thread executes the app and owns its mocks. */
class LoopbackMcpServer implements Closeable {
    final LinkedBlockingQueue<Map> requests = new LinkedBlockingQueue<>()
    private final HttpServer server
    private final Object lifecycleLock = new Object()
    private final Set<CompletableFuture<Map>> pendingResponses = new HashSet<>()
    private boolean closed = false

    LoopbackMcpServer() {
        server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
        server.createContext('/apps/api/1/', { HttpExchange exchange ->
            CompletableFuture<Map> response = null
            try {
                if (exchange.requestURI.rawQuery != 'access_token=software-only-token') {
                    respond(exchange, [status: 401, data: '{"error":"Unauthorized"}'])
                    return
                }
                String suffix = exchange.requestURI.path.substring('/apps/api/1/'.length())
                if (!(suffix in ['mcp', 'health'])) {
                    respond(exchange, [status: 404, data: '{"error":"Unsupported route"}'])
                    return
                }
                String text = exchange.requestBody.getText('UTF-8')
                Object body = null
                Throwable parseFailure = null
                if (text) {
                    try { body = new JsonSlurper().parseText(text) }
                    catch (Exception error) { parseFailure = error }
                }
                response = new CompletableFuture<Map>()
                synchronized (lifecycleLock) {
                    if (closed) {
                        response.completeExceptionally(new IllegalStateException('Loopback MCP server closed'))
                    } else {
                        pendingResponses.add(response)
                        requests.add([suffix: suffix, method: exchange.requestMethod, body: body,
                                      parseFailure: parseFailure, headers: new LinkedHashMap(exchange.requestHeaders), response: response])
                    }
                }
                respond(exchange, response.get(30, TimeUnit.SECONDS))
            } finally {
                synchronized (lifecycleLock) {
                    if (response != null) pendingResponses.remove(response)
                }
                exchange.close()
            }
        } as com.sun.net.httpserver.HttpHandler)
        server.start()
    }

    String getBaseUrl() { "http://127.0.0.1:${server.address.port}" }

    private static void respond(HttpExchange exchange, Map response) {
        byte[] bytes = (response.data ?: '').toString().getBytes('UTF-8')
        exchange.responseHeaders.set('Content-Type', (response.contentType ?: 'application/json').toString())
        exchange.sendResponseHeaders((response.status ?: 200) as int, bytes.length ? bytes.length : -1L)
        if (bytes.length) exchange.responseBody.write(bytes)
    }

    @Override void close() {
        List<CompletableFuture<Map>> abandoned
        synchronized (lifecycleLock) {
            if (closed) return
            closed = true
            abandoned = new ArrayList<>(pendingResponses)
            pendingResponses.clear()
            requests.clear()
        }
        // Polled requests are absent from the queue but still own a waiting HTTP worker.
        abandoned.each { it.completeExceptionally(new IllegalStateException('Loopback MCP server closed')) }
        server.stop(0)
    }
}
