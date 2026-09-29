package harness

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** Scriptable stand-in for the Statsig initialize and log_event endpoints (JDK HttpServer). */
class FakeServer {
    private val server: HttpServer = run {
        // Respond without Nagle delays so loopback latency does not dominate the benchmark.
        System.setProperty("sun.net.httpserver.nodelay", "true")
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @Volatile var initStatus = 200

    @Volatile var initBody: String = "{}"

    @Volatile var logStatus = 200
    val initRequests = ConcurrentLinkedQueue<Map<String, Any?>>()
    val logRequests = ConcurrentLinkedQueue<Map<String, Any?>>()

    init {
        server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "fake-server").apply { isDaemon = true } }
        server.createContext("/") { ex ->
            val raw = ex.requestBody.readBytes()
            val text = if (ex.requestHeaders.getFirst("Content-Encoding") == "gzip") {
                GZIPInputStream(raw.inputStream()).readBytes().decodeToString()
            } else {
                raw.decodeToString()
            }
            @Suppress("UNCHECKED_CAST")
            val body = gson.fromJson(text, Map::class.java) as Map<String, Any?>
            val path = ex.requestURI.path
            val (status, response) = when {
                path.endsWith("/initialize") -> {
                    initRequests.add(body)
                    initStatus to (if (initStatus == 200) initBody else "")
                }
                path.endsWith("/log_event") -> {
                    logRequests.add(body + ("__eventCountHeader" to ex.requestHeaders.getFirst("STATSIG-EVENT-COUNT")))
                    logStatus to "{\"success\": ${logStatus == 200}}"
                }
                else -> 404 to ""
            }
            val bytes = response.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    val api: String get() = "http://127.0.0.1:${server.address.port}/v1/"

    fun drainLogs(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        while (true) out.add(logRequests.poll() ?: break)
        return out
    }

    fun drainInits(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        while (true) out.add(initRequests.poll() ?: break)
        return out
    }

    fun close() = server.stop(0)
}
