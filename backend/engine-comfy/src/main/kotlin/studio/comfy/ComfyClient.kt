package studio.comfy

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.*
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

private fun engineOrigin(address: String, mode: String, acceptanceOrigin: String?): String {
    val base = address.removeSuffix("/")
    val uri = URI(base)
    require(mode in setOf("mock", "comfy") && uri.scheme == "http" && uri.rawPath.isNullOrEmpty() &&
        uri.rawQuery == null && uri.rawUserInfo == null && uri.rawFragment == null) { "ComfyUI 需要固定的 HTTP 服务地址" }
    if (acceptanceOrigin == null) {
        require(uri.host in setOf("127.0.0.1", "localhost", "[::1]")) { "ComfyUI 默认只接受本机地址" }
    } else {
        val host = uri.host.orEmpty()
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        val privateIp = octets.size == 4 && octets.all { it in 0..255 } && octets.joinToString(".") == host &&
            (octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))
        require(mode == "comfy" && base == acceptanceOrigin.removeSuffix("/") && privateIp &&
            uri.port in 1..65535 && base == "http://$host:${uri.port}") { "验收地址须固定到指定的私网 IPv4 和端口" }
    }
    return base
}

class ComfyClient(address: String, override val mode: String, private val targetedInterrupt: Boolean = mode == "mock",
    timeoutMs: Long = 8000, acceptanceOrigin: String? = null) : ImageEngine {
    private val base = engineOrigin(address, mode, acceptanceOrigin)
    override val identity = "$mode@$base"
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(WebSockets) { maxFrameSize = 2_000_000 }
        install(HttpTimeout) { connectTimeoutMillis = timeoutMs; requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
    }
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val board = AtomicReference<BoardMemory?>()
    private var monitor: Job? = null
    private var nextConnect = 0L
    @Synchronized private fun startBoardMonitor() {
        val now = System.nanoTime()
        if (monitor?.isActive == true || now < nextConnect) return
        nextConnect = now + 30_000_000_000L
        monitor = monitorScope.launch {
            try {
                client.webSocket(base.replaceFirst("http:", "ws:") + "/ws?clientId=" + uid()) {
                    for (frame in incoming) {
                        if (frame is Frame.Text) readBoardMemory(frame.readText())?.let(board::set)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The remote board monitor is optional. */ }
            finally { board.set(null) }
        }
    }
    private fun freshBoardInfo(stats: EngineInfo): EngineInfo? {
        val value = board.get() ?: return null
        if (System.nanoTime() - value.observed > 5_000_000_000L) return null
        return stats.copy(vramTotal=value.total, vramFree=value.total-value.used)
    }
    private suspend fun getObject(path: String): JsonObject = client.prepareGet(base + path).execute {
        if (!it.status.isSuccess()) unavailable("ComfyUI 返回 HTTP ${it.status.value}")
        val bytes = it.bodyAsChannel().toInputStream().use { stream -> stream.readNBytes(16_000_001) }
        if (bytes.size > 16_000_000) unavailable("ComfyUI 响应过大")
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }
    private suspend fun post(path: String, body: JsonObject) = client.post(base + path) {
        contentType(ContentType.Application.Json); setBody(body.toString())
    }
    override suspend fun info(): EngineInfo {
        val stats = getObject("/system_stats")
        val sys = stats.getValue("system").jsonObject
        val version = sys["comfyui_version"]?.jsonPrimitive?.content ?: "未报告"
        if ((version == "mock-wire-1") != (mode == "mock")) unavailable("配置的引擎模式与服务身份不符")
        val device = (stats["devices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val info = EngineInfo(mode, version, vramTotal = device?.get("vram_total")?.jsonPrimitive?.longOrNull,
            vramFree = device?.get("vram_free")?.jsonPrimitive?.longOrNull, ramTotal = sys["ram_total"]?.jsonPrimitive?.longOrNull,
            ramFree = sys["ram_free"]?.jsonPrimitive?.longOrNull)
        return freshBoardInfo(info) ?: info
    }
    override suspend fun objectInfo() = getObject("/object_info")
    override suspend fun resourcesForSwitch(): EngineInfo {
        val stats = info()
        if (mode != "comfy") return stats
        if (URI(base).host in setOf("127.0.0.1","localhost","[::1]")) {
            localBoardInfo(stats)?.let { return it }
        }
        startBoardMonitor()
        // On Windows system_stats may omit another process's CUDA allocation.
        return freshBoardInfo(stats) ?: stats.copy(vramFree=null)
    }
    private suspend fun localBoardInfo(stats: EngineInfo): EngineInfo? = withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("nvidia-smi","--id=0","--query-gpu=memory.total,memory.free","--format=csv,noheader,nounits")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            } catch (_: java.io.IOException) { return@withContext null }
            try {
                if (!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) return@withContext null
                val columns = process.inputStream.bufferedReader().use { it.readLine() }?.split(',').orEmpty()
                val memory = columns.mapNotNull { it.trim().toLongOrNull() }
                if (memory.size == 2 && memory[0] > 0 && memory[1] in 0..memory[0])
                    stats.copy(vramTotal=memory[0]*1048576, vramFree=memory[1]*1048576) else null
            } finally { if (process.isAlive) process.destroyForcibly() }
    }
    override suspend fun releaseModels() {
        val response = post("/free", buildJsonObject { put("unload_models",true); put("free_memory",true) })
        if (!response.status.isSuccess()) unavailable("ComfyUI 无法释放模型，HTTP ${response.status.value}")
    }
    private fun entry(raw: JsonElement, running: Boolean): QueueEntry {
        val item = raw.jsonArray
        return QueueEntry(validId(item[1].jsonPrimitive.content), item[3].jsonObject["client_id"]?.jsonPrimitive?.content, running)
    }
    override suspend fun queue(): List<QueueEntry> {
        val q = getObject("/queue")
        return q.getValue("queue_running").jsonArray.map { entry(it, true) } + q.getValue("queue_pending").jsonArray.map { entry(it, false) }
    }
    override suspend fun listen(clientId: String): ProgressStream {
        validId(clientId)
        val session = client.webSocketSession(base.replaceFirst("http:", "ws:") + "/ws?clientId=$clientId")
        val last = AtomicReference<NodeProgress?>()
        val reader = session.launch {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val msg = json.parseToJsonElement(frame.readText()).jsonObject
                if (msg["type"] != JsonPrimitive("progress")) continue
                val d = msg.getValue("data").jsonObject
                val max = d["max"]?.jsonPrimitive?.doubleOrNull ?: continue
                val value = d["value"]?.jsonPrimitive?.doubleOrNull ?: continue
                val id = d["prompt_id"]?.jsonPrimitive?.content ?: continue
                if (max > 0) last.set(NodeProgress(id, d["node"]?.jsonPrimitive?.content.orEmpty(), (value / max).coerceIn(0.0, 1.0)))
            }
        }
        return object : ProgressStream {
            override val open get() = reader.isActive
            override fun latest(): NodeProgress? = last.getAndSet(null)
            override fun close() { reader.cancel(); session.cancel() }
        }
    }
    override suspend fun submit(clientId: String, graph: JsonObject): String {
        val res = post("/prompt", buildJsonObject { put("client_id", validId(clientId)); put("prompt", graph) })
        if (res.status == HttpStatusCode.BadRequest) throw PromptRejected("引擎拒绝模板: " + res.bodyAsText().take(600))
        if (!res.status.isSuccess()) unavailable("提交返回 HTTP ${res.status.value}，需要核对队列")
        return validId(json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("prompt_id").jsonPrimitive.content)
    }
    override suspend fun findPrompt(clientId: String): String? {
        queue().firstOrNull { it.clientId == clientId }?.let { return it.id }
        return getObject("/history?max_items=100").entries.firstOrNull { (_, raw) ->
            entry(raw.jsonObject.getValue("prompt"), false).clientId == clientId
        }?.key?.let(::validId)
    }
    override suspend fun history(id: String, outputs: List<ImageOutput>): History? {
        val item = getObject("/history/${validId(id)}")[id]?.jsonObject ?: return null
        val status = item.getValue("status").jsonObject
        if (status["status_str"] == JsonPrimitive("error")) {
            val messages = (status["messages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
            if (messages.any { it.firstOrNull() == JsonPrimitive("execution_interrupted") }) return History.Failure("INTERRUPTED", "引擎已中止任务")
            val msg = messages.lastOrNull { it.firstOrNull() == JsonPrimitive("execution_error") }?.getOrNull(1) as? JsonObject
            val reason = msg?.get("exception_message")?.jsonPrimitive?.content.orEmpty()
            val oom = reason.contains("out of memory", true) || msg?.get("exception_type").toString().contains("OutOfMemory")
            return History.Failure(if (oom) "OOM" else "ENGINE_ERROR", if (oom) "显存不足，请降低图片分辨率" else reason.take(600).ifBlank { "引擎执行失败" })
        }
        if (status["completed"] != JsonPrimitive(true)) return null
        val results = item["outputs"] as? JsonObject ?: throw InvalidOutput("history 缺少输出")
        val files = outputs.flatMap { output ->
            val items = (results[output.node] as? JsonObject)?.get(output.field) as? JsonArray
            if (items.isNullOrEmpty()) throw InvalidOutput("声明的图片节点未返回结果")
            items.map {
                val img = it as? JsonObject ?: throw InvalidOutput("无效的图片记录")
                OutputRef(img["filename"]?.jsonPrimitive?.content.orEmpty(), img["subfolder"]?.jsonPrimitive?.content.orEmpty(),
                    img["type"]?.jsonPrimitive?.content.orEmpty()).also(::validateOutput)
            }
        }
        if (files.size !in 1..16) throw InvalidOutput("输出图片数量超出限制")
        return History.Success(files)
    }
    override suspend fun cancel(entry: QueueEntry) {
        val id = validId(entry.id)
        val res = when {
            !entry.running -> post("/queue", buildJsonObject { put("delete", JsonArray(listOf(JsonPrimitive(id)))) })
            targetedInterrupt -> post("/interrupt", buildJsonObject { put("prompt_id", id) })
            else -> return
        }
        if (!res.status.isSuccess()) unavailable("引擎未确认取消请求")
    }
    override suspend fun download(ref: OutputRef): ByteArray {
        validateOutput(ref)
        return client.prepareGet("$base/view") {
            parameter("filename", ref.filename); parameter("subfolder", ref.subfolder); parameter("type", ref.type)
        }.execute {
            if (it.status == HttpStatusCode.NotFound) throw InvalidOutput("引擎输出文件已丢失")
            if (!it.status.isSuccess()) unavailable("无法读取图片，HTTP ${it.status.value}")
            it.bodyAsChannel().toInputStream().use { stream -> stream.readNBytes(32 * 1024 * 1024 + 1) }.also { data ->
                if (data.size > 32 * 1024 * 1024) throw InvalidOutput("图片超过 32 MiB")
            }
        }
    }
    override fun close() { monitorScope.cancel(); client.close() }
}
internal data class BoardMemory(val total: Long, val used: Long, val observed: Long = System.nanoTime())
internal fun readBoardMemory(text: String): BoardMemory? {
    val msg = try { json.parseToJsonElement(text) as? JsonObject } catch (_: kotlinx.serialization.SerializationException) { null }
    if (msg?.get("type") != JsonPrimitive("crystools.monitor")) return null
    val data = msg["data"] as? JsonObject ?: return null
    if (data["device_type"] != JsonPrimitive("cuda")) return null
    val gpu = (data["gpus"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
    val total = (gpu["vram_total"] as? JsonPrimitive)?.longOrNull ?: return null
    val used = (gpu["vram_used"] as? JsonPrimitive)?.longOrNull ?: return null
    return if (total > 0 && used in 0..total) BoardMemory(total,used) else null
}
internal fun validateOutput(ref: OutputRef) {
    fun safe(part: String) = part.isNotBlank() && part !in setOf(".", "..") && part.none { it in "/\\:\u0000" || it.isISOControl() }
    if (ref.type != "output" || !safe(ref.filename) || (ref.subfolder.isNotEmpty() && !ref.subfolder.split('/').all(::safe))) {
        throw InvalidOutput("引擎返回了无效的输出路径")
    }
}
