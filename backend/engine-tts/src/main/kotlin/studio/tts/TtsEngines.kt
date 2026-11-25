package studio.tts

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.*
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MockTtsEngine : TtsEngine {
    override val identity = "tts@mock"
    override suspend fun releaseModels() {}
    private val voices = listOf(TtsVoice("mock-1", "模拟声线一"), TtsVoice("mock-2", "模拟声线二"))
    override suspend fun info() = TtsInfo("mock", "mock-tts-1", "cpu", voices)
    override suspend fun advance(attempt: String, call: SpeechCall, cancel: Boolean): SpeechRun {
        validId(attempt)
        if (cancel) return SpeechRun.Cancelled
        if (voices.none { it.id == call.voiceId } || call.text.isBlank() || call.speed !in 0.5..2.0)
            return SpeechRun.Failed("INVALID_SPEECH", "模拟音色或对白无效")
        val rate = 16000
        val duration = (0.12 * call.text.length / call.speed).coerceIn(0.25, 20.0)
        val frames = (duration * rate).roundToInt()
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(text: String) { wav.put(text.toByteArray(Charsets.US_ASCII)) }
        tag("RIFF"); wav.putInt(36 + frames * 2); tag("WAVE"); tag("fmt "); wav.putInt(16); wav.putShort(1); wav.putShort(1)
        wav.putInt(rate); wav.putInt(rate * 2); wav.putShort(2); wav.putShort(16); tag("data"); wav.putInt(frames * 2)
        val hz = if (call.voiceId == "mock-1") 220 else 330
        repeat(frames) { i -> wav.putShort((4000 * sin(2 * PI * hz * i / rate)).toInt().toShort()) }
        return SpeechRun.Completed(wav.array())
    }
}

class FastApiTtsEngine(address: String) : TtsEngine {
    private val base = URI.create(address.trimEnd('/'))
    init {
        val host = base.host.orEmpty()
        val ip = host.split('.').mapNotNull(String::toIntOrNull)
        val privateIp = ip.size == 4 && ip.all { it in 0..255 } && ip.joinToString(".") == host &&
            (ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168)
        require(base.scheme == "http" && base.port in 1..65535 && base.rawPath.isNullOrEmpty() &&
            base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null &&
            (host in setOf("localhost", "127.0.0.1", "[::1]") || privateIp)) { "TTS 地址须为回环或已配置的私网 HTTP 服务" }
    }
    override val identity = "tts@$base"
    private class Oversized : Exception("TTS 响应超过大小限制")
    private data class Reply(val status: Int, val body: ByteArray) {
        fun obj() = json.parseToJsonElement(body.decodeToString()).jsonObject
    }
    private suspend fun request(method: String, path: String, body: String? = null, max: Int = 262144): Reply = withContext(Dispatchers.IO) {
        val conn = base.resolve(path).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false; conn.connectTimeout = 2000; conn.readTimeout = 10000
            conn.requestMethod = method
            if (body != null) {
                val bytes = body.toByteArray()
                conn.setRequestProperty("Content-Type", "application/json"); conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readNBytes(max + 1) } ?: ByteArray(0)
            if (bytes.size > max) throw Oversized()
            Reply(code, bytes)
        } finally { conn.disconnect() }
    }
    override suspend fun releaseModels() {
        val result = request("POST", "/free")
        if (result.status !in 200..299) unavailable("等待 GPU TTS 服务确认释放显存（HTTP ${result.status}）")
    }
    override suspend fun info(): TtsInfo = try {
        val reply = request("GET", "/voices")
        check(reply.status == 200)
        val obj = reply.obj()
        val voices = obj.getValue("voices").jsonArray.map {
            val v = it.jsonObject
            val model = v["modelVersion"]?.takeUnless { value -> value == JsonNull }?.jsonPrimitive
            require(model == null || model.isString && model.content.isNotBlank())
            TtsVoice(v.getValue("id").jsonPrimitive.content, v.getValue("name").jsonPrimitive.content, model?.content)
        }
        val device = obj.getValue("device").jsonPrimitive.content
        val version = obj.getValue("version").jsonPrimitive.content
        require(device in setOf("cpu", "gpu") && version.isNotBlank() && voices.size in 1..4096 &&
            voices.distinctBy { it.id }.size == voices.size && voices.all { it.id.isNotBlank() && it.name.isNotBlank() })
        TtsInfo("fastapi", version, device, voices)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { TtsInfo("fastapi", "", "unknown", emptyList(), false, "无法读取 TTS 服务的音色与设备信息") }

    override suspend fun advance(attempt: String, call: SpeechCall, cancel: Boolean): SpeechRun {
        val path = "/jobs/${validId(attempt)}"
        var reply = request(if (cancel) "DELETE" else "GET", path)
        if (!cancel && reply.status == 404) reply = request("PUT", path, json.encodeToString(call))
        if (reply.status == 410) return SpeechRun.Failed("TTS_ATTEMPT_GONE", "语音服务已移除此尝试的记录")
        if (reply.status in setOf(400,409,422) && !cancel) return SpeechRun.Failed("TTS_REJECTED", "TTS 拒绝了固定的音色或对白（HTTP ${reply.status}）")
        if (reply.status !in 200..299) unavailable("等待按尝试编号核对 TTS 服务（HTTP ${reply.status}）")
        val state = reply.obj()
        return when (state["status"]?.jsonPrimitive?.content) {
            "queued", "running" -> SpeechRun.Active
            "cancelled" -> SpeechRun.Cancelled
            "failed" -> SpeechRun.Failed("TTS_FAILED", (state["error"] as? JsonPrimitive)?.contentOrNull?.take(300) ?: "语音合成失败")
            "succeeded" -> if (cancel) SpeechRun.Cancelled else {
                val audio = try { request("GET", "$path/audio", max = 32 * 1024 * 1024) }
                    catch (_: Oversized) { return SpeechRun.Failed("INVALID_OUTPUT", "TTS 音频超过 32 MiB") }
                if (audio.status in setOf(404,410)) return SpeechRun.Failed("TTS_OUTPUT_MISSING", "语音服务已完成任务，但成品音频不存在")
                if (audio.status != 200) unavailable("无法读取已完成的 TTS 音频（HTTP ${audio.status}）")
                SpeechRun.Completed(audio.body)
            }
            else -> unavailable("TTS 服务返回未知任务状态，等待对账")
        }
    }
}
