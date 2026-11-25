package studio.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import studio.application.*
import studio.core.*
import studio.engine.*

fun Route.postProductionRoutes(s: Studio) {
    fun ApplicationCall.key() = validId(request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key"))
    suspend fun ttsInfo(): TtsInfo {
        val engine = s.tts ?: return TtsInfo("disabled","","",emptyList(),false,"配音服务已禁用")
        return try { engine.info() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { TtsInfo(s.settings.ttsMode,"","",emptyList(),false,"无法连接配音服务，请核对服务地址与模型") }
    }
    suspend fun onlineTts(): TtsInfo = ttsInfo().also { if (!it.available) unavailable(it.reason ?: "配音服务不可用") }
    suspend fun mediaInfo(): ExportCapability = try { ExportCapability(true,s.exporter.version()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ExportCapability(false,null,e.message?.take(200) ?: "FFmpeg 不可用") }

    get("/engines/tts") { call.respond(ttsInfo()) }
    get("/engines/media") { call.respond(mediaInfo()) }
    get("/projects/{id}/voices") { call.respond(s.post.voices(call.id())) }
    put("/projects/{id}/voices/{characterKey}") {
        val edit = call.receive<VoiceEdit>()
        val info = onlineTts()
        call.respond(s.post.bind(call.id(),call.parameters["characterKey"].orEmpty(),edit,checkNotNull(s.tts).identity,info))
    }
    get("/shots/{id}/audio") { call.respond(s.post.audio(call.id(),call.request.queryParameters["shotRevisionId"]?.let(::validId))) }
    post("/shots/{id}/audio/generate") {
        val id = call.id(); val key = call.key(); val request = call.receive<SpeechRequest>()
        val prior = s.tasks.priorSpeech(id,key,request)
        val result = prior ?: onlineTts().let { s.tasks.enqueueSpeech(id,key,request,checkNotNull(s.tts).identity,it) }
        call.respond(HttpStatusCode.Accepted,result)
    }
    post("/shots/{id}/audio/{takeId}/select") {
        call.respond(s.post.select(call.id(),validId(call.parameters["takeId"].orEmpty()),call.receive<WritingApproval>().expectedRevision))
    }
    get("/episodes/{id}/timeline") { call.respond(s.post.timeline(call.id(),call.request.queryParameters["revisionId"]?.let(::validId))) }
    put("/episodes/{id}/timeline") { call.respond(s.post.save(call.id(),call.receive())) }
    get("/episodes/{id}/timeline/history") { call.respond(s.post.history(call.id(),call.window())) }
    post("/episodes/{id}/export") {
        val id = call.id(); val key = call.key(); val request = call.receive<ExportRequest>()
        val prior = s.tasks.priorExport(id,key,request)
        val result = prior ?: mediaInfo().let {
            if (!it.available) unavailable(it.reason ?: "导出工具不可用")
            s.tasks.enqueueExport(id,key,request,s.exporter.identity,checkNotNull(it.version))
        }
        call.respond(HttpStatusCode.Accepted,result)
    }
    get("/episodes/{id}/exports") { call.respond(s.post.exports(call.id(),call.window())) }
}
