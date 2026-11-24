package studio.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import studio.application.*
import studio.core.*
import studio.engine.TextEngineInfo

fun Route.writingRoutes(studio: Studio) {
    val writing = studio.writing
    get("/engines/writer") {
        call.respond(studio.writer?.info() ?: TextEngineInfo("disabled",null,false,"尚未配置本地编剧模型"))
    }
    get("/projects/{id}/episodes") { call.respond(writing.episodes(call.id())) }
    fun register(kind: WritingKind, base: String, generation: String) {
        get(base) { call.respond(writing.revisions(kind,call.id(),call.window())) }
        put(base) { call.respond(HttpStatusCode.Created,writing.save(kind,call.id(),call.receive())) }
        post("$base/{revisionId}/approve") {
            call.respond(writing.approve(kind,call.id(),validId(call.parameters["revisionId"].orEmpty()),call.receive<WritingApproval>().expectedRevision))
        }
        post(generation) {
            val key = call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
            val req = call.receive<WritingRequest>()
            val target = call.id()
            val existing = studio.tasks.priorWriting(kind,target,key,req)
            if (existing != null) { call.respond(HttpStatusCode.Accepted,existing); return@post }
            val writer = studio.writer ?: unavailable("尚未配置本地编剧模型")
            val info = writer.info()
            val model = info.model?.takeIf { info.available } ?: unavailable(info.reason ?: "编剧模型不可用")
            val enableThinking = if (info.supportsThinkingSwitch) false else null
            call.respond(HttpStatusCode.Accepted,studio.tasks.enqueueWriting(kind,target,key,req,writer.identity,model,info.version,enableThinking))
        }
    }
    register(WritingKind.OUTLINE,"/projects/{id}/outline","/projects/{id}/outline/generate")
    register(WritingKind.EPISODE_PLAN,"/projects/{id}/episodes/plan","/projects/{id}/episodes/plan")
    register(WritingKind.SCRIPT,"/episodes/{id}/script","/episodes/{id}/script/generate")
}
