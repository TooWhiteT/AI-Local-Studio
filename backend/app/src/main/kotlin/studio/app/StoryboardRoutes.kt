package studio.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import studio.application.*
import studio.core.*

fun Route.storyboardRoutes(studio: Studio) {
    val boards=studio.boards
    fun generation(path: String,kind: BoardJob) = post(path) {
        val key=call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
        val request=call.receive<BoardRequest>()
        val target=call.id()
        val prior=studio.tasks.priorBoard(kind,target,key,request)
        if (prior!=null) { call.respond(HttpStatusCode.Accepted,prior); return@post }
        val writer=studio.writer ?: unavailable("尚未配置本地编剧模型")
        val info=writer.info()
        val model=info.model?.takeIf { info.available } ?: unavailable(info.reason ?: "编剧模型不可用")
        call.respond(HttpStatusCode.Accepted,studio.tasks.enqueueBoard(kind,target,key,request,writer.identity,
            model,info.version,false.takeIf { info.supportsThinkingSwitch }))
    }
    generation("/projects/{id}/assets/extract",BoardJob.ASSET_EXTRACT)
    generation("/episodes/{id}/shots/plan",BoardJob.SHOT_PLAN)
    generation("/shots/{id}/regenerate",BoardJob.SHOT_REGENERATE)
    get("/projects/{id}/assets/extractions/{resultId}") {
        call.respond(boards.extraction(call.id(),validId(call.parameters["resultId"].orEmpty())))
    }
    get("/episodes/{id}/shots") { call.respond(boards.board(call.id(),call.request.queryParameters["revisionId"]?.let(::validId))) }
    get("/episodes/{id}/shots/history") { call.respond(boards.history(call.id(),call.window())) }
    get("/shots/{id}") { call.respond(boards.locate(call.id(),call.request.queryParameters["shotRevisionId"]?.let(::validId))) }
    patch("/shots/{id}") { call.respond(boards.edit(call.id(),call.receive())) }
    put("/episodes/{id}/shots/order") { call.respond(boards.reorder(call.id(),call.receive())) }
    post("/episodes/{id}/shots/{boardId}/approve") {
        call.respond(boards.approve(call.id(),validId(call.parameters["boardId"].orEmpty()),call.receive<WritingApproval>().expectedRevision))
    }
    put("/scenes/{id}/characters/{key}/state") { call.respond(boards.state(call.id(),call.parameters["key"].orEmpty(),call.receive())) }
    post("/shots/{id}/keyframes/{fileId}/approve") {
        call.respond(boards.approveFrame(call.id(),validId(call.parameters["fileId"].orEmpty()),call.receive()))
    }
    post("/shots/{id}/render") {
        val key = call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
        call.respond(HttpStatusCode.Accepted,studio.tasks.enqueueRender(call.id(),key,call.receive(),studio.engine.identity,studio.settings.mode))
    }
    post("/episodes/{id}/render") {
        val key = call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
        call.respond(HttpStatusCode.Accepted,studio.tasks.enqueueBatch(call.id(),key,call.receive(),studio.engine.identity,studio.settings.mode))
    }
    get("/shots/{id}/renders") {
        call.respond(boards.renders(call.id(),call.request.queryParameters["shotRevisionId"]?.let(::validId),call.window()))
    }
    fun renderReview(suffix: String,approve: Boolean) = post("/shots/{id}/renders/{versionId}/$suffix") {
        call.respond(boards.reviewRender(call.id(),validId(call.parameters["versionId"].orEmpty()),call.receive<WritingApproval>().expectedRevision,approve))
    }
    renderReview("approve",true)
    renderReview("reject",false)
    post("/shots/{id}/keyframes/generate") {
        val key=call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
        call.respond(HttpStatusCode.Accepted,studio.tasks.enqueueKeyframe(call.id(),key,call.receive(),studio.engine.identity,studio.settings.mode))
    }
}
