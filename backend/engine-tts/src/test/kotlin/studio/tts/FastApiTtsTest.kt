package studio.tts

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import studio.core.*
import studio.engine.*

class FastApiTtsTest {
    @Test fun remoteJobsUseImmutableAttemptAndRecoverWithoutRepeatingSynthesis() = runBlocking {
        val id = uid()
        val call = SpeechCall("我们明天再来。", "speaker-8", 1.0, "v1")
        val wav = assertIs<SpeechRun.Completed>(MockTtsEngine().advance(id,call.copy(voiceId="mock-1"),false)).wav
        var submitted = false
        var done = false
        val audioStatus = AtomicInteger(200)
        var cancelled = false
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1",0), 0)
        server.createContext("/") { req ->
            req.use {
                var code = 200
                val body = when (it.requestMethod + " " + it.requestURI.path) {
                    "GET /voices" -> """{"version":"v1","device":"cpu","voices":[{"id":"speaker-8","name":"音色八"},{"id":"speaker-9","name":"音色九","modelVersion":"other-model"},{"id":"legacy","name":"旧音色","modelVersion":null}]}""".toByteArray()
                    "GET /jobs/$id" -> {
                        if (!submitted) code = 404
                        """{"status":"${if (done) "succeeded" else "running"}"}""".toByteArray()
                    }
                    "PUT /jobs/$id" -> {
                        val dto = json.decodeFromString<SpeechCall>(it.requestBody.readBytes().decodeToString())
                        if (dto != call) code = 409 else { count.incrementAndGet(); submitted = true }
                        """{"status":"queued"}""".toByteArray()
                    }
                    "GET /jobs/$id/audio" -> { code = audioStatus.get(); wav }
                    "DELETE /jobs/$id" -> { cancelled = true; """{"status":"cancelled"}""".toByteArray() }
                    else -> { code=404; byteArrayOf() }
                }
                it.sendResponseHeaders(code,body.size.toLong()); it.responseBody.use { out -> out.write(body) }
            }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val first = FastApiTtsEngine(base)
            val inventory = first.info()
            assertEquals("v1",inventory.modelVersionFor("speaker-8"))
            assertEquals("other-model",inventory.modelVersionFor("speaker-9"))
            assertEquals("v1",inventory.modelVersionFor("legacy"))
            assertNull(inventory.modelVersionFor("missing"))
            assertEquals(SpeechRun.Active,first.advance(id,call,false))
            val restarted = FastApiTtsEngine(base)
            assertEquals(SpeechRun.Active,restarted.advance(id,call,false))
            done=true
            assertContentEquals(wav,assertIs<SpeechRun.Completed>(restarted.advance(id,call,false)).wav)
            audioStatus.set(503)
            assertFailsWith<StudioError> { restarted.advance(id,call,false) }
            for (status in listOf(404,410)) {
                audioStatus.set(status)
                assertEquals("TTS_OUTPUT_MISSING",assertIs<SpeechRun.Failed>(restarted.advance(id,call,false)).code)
            }
            assertEquals(1,count.get())
            assertEquals(SpeechRun.Cancelled,restarted.advance(id,call,true))
            assertTrue(cancelled)
        } finally { server.stop(0) }
    }
}
