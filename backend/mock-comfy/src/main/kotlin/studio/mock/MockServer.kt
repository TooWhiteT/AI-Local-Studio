package studio.mock

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import studio.core.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** Standalone wire fixture. The graph, pixels and timings are synthetic. */
class MockComfy(private val stepMs: Long = 160) : AutoCloseable {
    private data class Item(val id: String, val client: String, val graph: JsonObject, var interrupted: Boolean = false) {
        fun tuple() = buildJsonArray {
            add(0); add(id); add(graph); add(buildJsonObject { put("client_id", client) }); add(buildJsonArray { add("1") })
        }
    }
    private val guard = Any()
    private val pending = ArrayDeque<Item>()
    private var active: Item? = null
    private val history = linkedMapOf<String, JsonObject>()
    private val images = ConcurrentHashMap<String, ByteArray>()
    private val failedOnce = ConcurrentHashMap.newKeySet<String>()
    private val progress = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val loseNextAck = AtomicBoolean()
    val externalBusy = AtomicBoolean()
    val statsUnavailable = AtomicBoolean()
    private val externalId = uid()
    @Volatile var submissions = 0; private set
    @Volatile var maxOutstanding = 0; private set

    private val info = """{"MockOnlyImage":{"input":{"required":{"text":["STRING",{}],"seed":["INT",{"min":0,"max":2147483647}]}},"output_node":true}}"""
    fun install(app: Application) = with(app) {
        install(WebSockets)
        routing {
            get("/object_info") { call.respondText(info, ContentType.Application.Json) }
            get("/system_stats") {
                if (statsUnavailable.get()) call.respond(HttpStatusCode.ServiceUnavailable)
                else call.respondText("""{"system":{"comfyui_version":"mock-wire-1"},"devices":[]}""", ContentType.Application.Json)
            }
            get("/queue") {
                val value = synchronized(guard) { buildJsonObject {
                    put("queue_pending", JsonArray(pending.map(Item::tuple)))
                    put("queue_running", buildJsonArray {
                        active?.let { add(it.tuple()) }
                        if (externalBusy.get()) add(Item(externalId, "external", JsonObject(emptyMap())).tuple())
                    })
                } }
                call.respondText(value.toString(), ContentType.Application.Json)
            }
            get("/history/{id?}") {
                val id = call.parameters["id"]
                val value = synchronized(guard) { JsonObject(history.filterKeys { id == null || it == id }) }
                call.respondText(value.toString(), ContentType.Application.Json)
            }
            webSocket("/ws") {
                val client = call.request.queryParameters["clientId"]
                progress.collect { (to, msg) -> if (to == client) send(Frame.Text(msg)) }
            }
            post("/prompt") {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val graph = body.getValue("prompt").jsonObject
                val node = graph["1"] as? JsonObject
                if (node?.get("class_type") != JsonPrimitive("MockOnlyImage")) {
                    call.respondText("""{"error":"Only MockOnlyImage is registered"}""", status = HttpStatusCode.BadRequest)
                    return@post
                }
                val item = Item(uid(), body.getValue("client_id").jsonPrimitive.content, graph)
                synchronized(guard) {
                    pending.addLast(item); submissions++
                    maxOutstanding = maxOf(maxOutstanding, pending.size + (if (active == null) 0 else 1))
                }
                if (loseNextAck.getAndSet(false)) call.respond(HttpStatusCode.ServiceUnavailable)
                else call.respondText("""{"prompt_id":"${item.id}","number":0,"node_errors":{}}""", ContentType.Application.Json)
            }
            post("/queue") {
                val ids = json.parseToJsonElement(call.receiveText()).jsonObject.getValue("delete").jsonArray
                synchronized(guard) { pending.removeAll { JsonPrimitive(it.id) in ids } }
                call.respond(HttpStatusCode.OK)
            }
            post("/interrupt") {
                val id = json.parseToJsonElement(call.receiveText()).jsonObject["prompt_id"]?.jsonPrimitive?.content
                synchronized(guard) { active?.takeIf { it.id == id }?.interrupted = true }
                call.respond(HttpStatusCode.OK)
            }
            get("/view") {
                val data = images[call.request.queryParameters["filename"]?.removeSuffix(".png").orEmpty()]
                if (data == null) call.respond(HttpStatusCode.NotFound) else call.respondBytes(data, ContentType.Image.PNG)
            }
        }
        scope.launch {
            while (isActive) {
                val item = synchronized(guard) { pending.removeFirstOrNull()?.also { active = it } }
                if (item == null) { delay(15); continue }
                for (step in 1..5) {
                    delay(stepMs)
                    if (synchronized(guard) { item.interrupted }) break
                    val msg = """{"type":"progress","data":{"prompt_id":"${item.id}","node":"1","value":$step,"max":5}}"""
                    progress.emit(item.client to msg)
                }
                val inputs = item.graph.getValue("1").jsonObject.getValue("inputs").jsonObject
                val prompt = inputs.getValue("text").jsonPrimitive.content
                val fail = prompt == "[mock:fail]" || (prompt == "[mock:fail-once]" && failedOnce.add(prompt))
                val interrupted = synchronized(guard) { item.interrupted }
                if (!fail && !interrupted) images[item.id] = sampleImage(inputs.getValue("seed").jsonPrimitive.int)
                val status = if (fail || interrupted) "error" else "success"
                val msg = if (interrupted) "execution_interrupted" else "execution_error"
                val result = buildJsonObject {
                    put("prompt", item.tuple())
                    put("status", buildJsonObject {
                        put("status_str", status); put("completed", status == "success")
                        put("messages", if (status == "success") JsonArray(emptyList()) else buildJsonArray { add(buildJsonArray {
                            add(msg); add(buildJsonObject { put("exception_message", "模拟引擎失败") })
                        }) })
                    })
                    put("outputs", if (status != "success") JsonObject(emptyMap()) else buildJsonObject {
                        put("1", buildJsonObject { put("images", buildJsonArray { add(buildJsonObject {
                            put("filename", "${item.id}.png"); put("subfolder", ""); put("type", "output")
                        }) }) })
                    })
                }
                synchronized(guard) { history[item.id] = result; active = null }
            }
        }
    }
    override fun close() { scope.cancel() }

    private fun sampleImage(seed: Int): ByteArray {
        val img = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color(31, 46, 52); g.fillRect(0, 0, img.width, img.height)
            g.color = Color(124, 170 + seed.mod(45), 149); g.fillOval(205, 37, 78, 78)
            g.color = Color(73, 106, 102); g.fillPolygon(intArrayOf(0, 120, 240), intArrayOf(180, 50, 180), 3)
            g.color = Color.WHITE; g.drawString("LOCAL AI STUDIO  /  MOCK", 14, 161)
        } finally { g.dispose() }
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
    }
}
fun mockServer(mock: MockComfy, listenPort: Int): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
    return embeddedServer(Netty, configure = {
        connector { host = "127.0.0.1"; port = listenPort }
        shareWorkGroup = true
        channelPipelineConfig = { connections.add(channel()) }
    }) {
        mock.install(this)
        monitor.subscribe(ApplicationStopPreparing) { connections.close().awaitUninterruptibly() }
        monitor.subscribe(ApplicationStopping) { mock.close() }
    }
}
fun main() {
    mockServer(MockComfy(), System.getenv("MOCK_PORT")?.toInt() ?: 18188).start(wait = true)
}
