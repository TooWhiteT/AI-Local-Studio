package studio.llm

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.*
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class LlamaEngine(address: String, private val selectedModel: String?, storage: Path) : TextEngine {
    private val base = address.trimEnd('/').removeSuffix("/v1")
    init {
        val uri = URI(base)
        val host = uri.host.orEmpty()
        val ip = host.split('.').mapNotNull(String::toIntOrNull)
        val privateIp = ip.size == 4 && ip.all { it in 0..255 } && ip.joinToString(".") == host &&
            (ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168)
        require(uri.scheme == "http" && uri.rawPath.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.port in 1..65535 && (host in setOf("localhost","127.0.0.1","[::1]") || privateIp)) {
            "LLM 地址须为配置的回环或私网 HTTP 地址及端口"
        }
    }
    override val identity = "llama.cpp@$base"
    override val real = true
    private val root = storage.toRealPath().resolve("writing-attempts").also {
        Files.createDirectories(it); require(Files.isDirectory(it,NOFOLLOW_LINKS))
    }
    private var child: Process? = null
    private var childAttempt: String? = null

    private suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        val c = URI(base+path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.instanceFollowRedirects = false; c.connectTimeout = 1500; c.readTimeout = 2500
            System.getenv("STUDIO_LLM_API_KEY")?.takeIf(String::isNotBlank)?.let { c.setRequestProperty("Authorization","Bearer $it") }
            if (c.responseCode != 200) unavailable("llama.cpp $path 返回 HTTP ${c.responseCode}")
            val body = c.inputStream.use { it.readNBytes(1_048_577) }
            if (body.size > 1_048_576) unavailable("llama.cpp 响应过大")
            json.parseToJsonElement(body.decodeToString())
        } finally { c.disconnect() }
    }
    override suspend fun info(): TextEngineInfo {
        return try {
            val models = get("/v1/models").jsonObject.getValue("data").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val model = selectedModel ?: models.singleOrNull()
            val properties = get("/props").jsonObject
            if (model.isNullOrBlank() || model !in models) TextEngineInfo("llama.cpp",null,false,"请指定已加载的 STUDIO_LLM_MODEL")
            else if (properties["endpoint_slots"] == JsonPrimitive(false)) TextEngineInfo("llama.cpp",model,false,"请为 llama-server 启用 --slots")
            else TextEngineInfo("llama.cpp",model,true,version=properties["build_info"]?.jsonPrimitive?.contentOrNull,
                supportsThinkingSwitch=(properties["chat_template"] as? JsonPrimitive)?.contentOrNull?.contains("enable_thinking") == true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { TextEngineInfo("llama.cpp",null,false,"无法读取配置的 llama.cpp 模型列表") }
    }
    override suspend fun idle(): Boolean {
        if (sleeping()) return true
        val slots = get("/slots").jsonArray
        return slots.isNotEmpty() && slots.all { it.jsonObject["is_processing"] == JsonPrimitive(false) }
    }
    override suspend fun sleeping() = get("/props").jsonObject["is_sleeping"] == JsonPrimitive(true)
    private fun body(call: TextCall) = buildJsonObject {
        put("model",call.model); put("stream",false); put("max_tokens",4096)
        call.prompt.enableThinking?.let { enabled -> putJsonObject("chat_template_kwargs") { put("enable_thinking",enabled) } }
        put("messages",buildJsonArray {
            addJsonObject { put("role","system"); put("content",call.prompt.system) }
            addJsonObject { put("role","user"); put("content",call.input.toString()) }
        })
        putJsonObject("response_format") {
            put("type","json_schema")
            putJsonObject("json_schema") { put("name",call.kind.lowercase()); put("strict",true); put("schema",call.prompt.schema) }
        }
    }
    private fun response(raw: String): TextRun = try {
        val reply = json.parseToJsonElement(raw).jsonObject
        val choice = reply.getValue("choices").jsonArray.single().jsonObject
        val msg = choice.getValue("message").jsonObject
        val content = msg["content"] as? JsonPrimitive
        val hasTools = msg["tool_calls"] !in listOf(null,JsonNull,JsonArray(emptyList())) || msg["function_call"] !in listOf(null,JsonNull)
        if (choice["finish_reason"] != JsonPrimitive("stop") || hasTools || content == null || !content.isString)
            TextRun.Rejected("INVALID_MODEL_OUTPUT","模型返回了截断内容或工具调用")
        else TextRun.Completed(content.content,reply["id"]?.jsonPrimitive?.contentOrNull?.take(160))
    } catch (_: Exception) { TextRun.Rejected("INVALID_MODEL_OUTPUT","模型返回的补全响应格式无效") }

    override suspend fun advance(attempt: String, call: TextCall, allowStart: Boolean) = synchronized(this) { inspect(attempt,call,allowStart) }
    private fun inspect(attempt: String, call: TextCall, allowStart: Boolean): TextRun {
        val dir = root.resolve(validId(attempt))
        Files.createDirectories(dir); require(Files.isDirectory(dir,NOFOLLOW_LINKS))
        fun exists(name: String) = Files.isRegularFile(dir.resolve(name),NOFOLLOW_LINKS)
        if (exists("response.json")) return response(Files.readString(dir.resolve("response.json")))
        if (exists("invalid")) return TextRun.Rejected("INVALID_MODEL_OUTPUT","模型响应超过 1 MiB")
        if (exists("status")) {
            val status = Files.readString(dir.resolve("status")).toInt()
            return if (status in 400..499 && status != 408) TextRun.Rejected("MODEL_REJECTED","模型服务拒绝请求（HTTP $status）")
                else TextRun.Uncertain("模型服务返回 HTTP $status，需要核对槽位")
        }
        if (childAttempt == attempt && child?.isAlive == true || CompletionWorker.busy(dir)) return TextRun.Active
        if (exists("sent")) return TextRun.Uncertain("请求已送出，尚无可恢复的模型回执")
        if (!allowStart) return if (CompletionWorker.withdrawIfUnsent(dir)) TextRun.Unsent else TextRun.Active
        if (childAttempt == attempt) return TextRun.Rejected("WORKER_START","本地文字执行进程在提交前退出")
        val temp = Files.createTempFile(dir,"request-",".part")
        try {
            Files.writeString(temp,body(call).toString())
            Files.move(temp,dir.resolve("request.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        val java = Path.of(System.getProperty("java.home"),"bin",if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val worker = CompletionWorker::class.java
        val classes = Path.of(worker.protectionDomain.codeSource.location.toURI())
        child = ProcessBuilder(java.toString(),"-Xmx64m","-cp",classes.toString(),worker.name,dir.toString(),"$base/v1/chat/completions")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        childAttempt = attempt
        return TextRun.Active
    }
}
