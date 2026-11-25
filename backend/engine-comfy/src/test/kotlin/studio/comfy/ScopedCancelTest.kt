package studio.comfy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import studio.core.*
import studio.engine.QueueEntry

class ScopedCancelTest {
    private suspend fun verify(status: Int, body: String, test: suspend (ComfyClient, List<String>) -> Unit) {
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { call ->
            call.use {
                requests += "${it.requestMethod} ${it.requestURI.path}"
                val result = body.toByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, result.size.toLong())
                it.responseBody.use { stream -> stream.write(result) }
            }
        }
        server.start()
        try {
            ComfyClient("http://127.0.0.1:${server.address.port}", "comfy").use { test(it, requests) }
        } finally { server.stop(0) }
    }
    @Test fun acceptedInterruptIsScopedToOneRunningJobAndAcknowledgedOnce() = runBlocking {
        verify(200, """{"cancelled":true}""") { engine, requests ->
            val owned = QueueEntry(uid(), uid(), true)
            val other = QueueEntry(uid(), uid(), true)
            engine.cancel(owned)
            engine.cancel(owned)
            engine.cancel(other)
            assertEquals(listOf("POST /api/jobs/${owned.id}/cancel", "POST /api/jobs/${other.id}/cancel"), requests)
        }
        verify(200, """{"cancelled":false}""") { engine, requests ->
            val entry = QueueEntry(uid(), uid(), true)
            engine.cancel(entry)
            assertEquals(listOf("POST /api/jobs/${entry.id}/cancel"), requests)
        }
    }
    @Test fun missingScopedRouteIsCachedWithoutInterruptingAnUnrelatedJob() = runBlocking {
        for (status in listOf(404, 405, 501)) verify(status, "{}") { engine, requests ->
            val first = QueueEntry(uid(), uid(), true)
            engine.cancel(first)
            engine.cancel(first)
            engine.cancel(QueueEntry(uid(), uid(), true))
            assertEquals(listOf("POST /api/jobs/${first.id}/cancel"), requests)
        }
        verify(503, "{}") { engine, requests ->
            val entry = QueueEntry(uid(), uid(), true)
            repeat(2) { assertFailsWith<StudioError> { engine.cancel(entry) } }
            assertEquals(List(2) { "POST /api/jobs/${entry.id}/cancel" }, requests)
        }
    }
}
