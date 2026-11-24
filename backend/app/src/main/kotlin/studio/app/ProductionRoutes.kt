package studio.app

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus
import studio.engine.EngineInfo
import java.nio.file.Files

@Serializable data class Reconciliation(val attemptId: String, val confirmedStopped: Boolean)
@Serializable data class Resources(val engine: EngineInfo, val diskFree: Long, val leaseTaskId: String?, val waitingReason: String?,
    val workflowIssues: List<String>)
private suspend fun <T> remote(action: suspend () -> T): T = try { action() }
catch (e: CancellationException) { throw e }
catch (e: StudioError) { throw e }
catch (e: Exception) { unavailable("无法访问本地 ComfyUI，请核对服务地址及运行状态") }

fun Route.productionRoutes(s: Studio) {
    val tasks = s.tasks
    suspend fun info(): EngineInfo = try { remote { s.engine.info() } }
        catch (e: StudioError) { EngineInfo(s.settings.mode, "未报告", false, e.message) }

    get("/workflows") { call.respond(s.workflows.list(s.settings.mode)) }
    post("/workflows/refresh") { call.respond(s.registry.refresh()) }
    post("/workflows/{id}/versions") {
        val edit = call.receive<WorkflowEdit>()
        call.respond(HttpStatusCode.Created, remote { s.registry.edit(call.id(), edit) })
    }
    get("/engines") { call.respond(listOf(info())) }
    get("/system/resources") {
        call.respond(Resources(info(), Files.getFileStore(s.settings.storage).usableSpace, tasks.lease()?.id, s.scheduler.waitingReason, s.registry.issues))
    }
    post("/assets/{id}/generate") {
        val key = call.request.header("Idempotency-Key") ?: invalid("需要 Idempotency-Key")
        call.respond(HttpStatusCode.Accepted, tasks.enqueue(call.id(), key, call.receive(), s.engine.identity, s.settings.mode))
    }
    get("/tasks/{id}") { call.respond(tasks.get(call.id())) }
    get("/tasks") {
        val q = call.request.queryParameters
        val status = q["status"]?.let { value -> TaskStatus.entries.find { it.name == value } ?: invalid("未知任务状态") }
        call.respond(tasks.list(q["projectId"]?.let(::validId), status, call.window()))
    }
    post("/tasks/{id}/retry") { call.respond(HttpStatusCode.Accepted, tasks.retry(call.id())) }
    post("/tasks/{id}/cancel") { call.respond(HttpStatusCode.Accepted, tasks.cancel(call.id())) }
    post("/tasks/{id}/reconcile") {
        val input = call.receive<Reconciliation>()
        val t = tasks.get(call.id())
        val a = t.attempts.last()
        val engineId = if (t.isImageJob) s.engine.identity else s.writer?.identity
        if (t.status != TaskStatus.UNKNOWN || a.id != input.attemptId || a.engineId != engineId) conflict("任务或引擎状态已变化")
        checkInput(input.confirmedStopped, "需要人工确认引擎已停止该任务")
        remote {
            if (t.isImageJob) {
                s.engine.info()
                if (s.engine.queue().isNotEmpty() || s.engine.findPrompt(a.id) != null ||
                    (a.externalId != null && s.engine.history(a.externalId!!, s.workflows.workflow(checkNotNull(t.snapshot.workflowId)).manifest.outputs) != null))
                    conflict("引擎中仍有任务或匹配的提交记录，请等待自动核对")
            } else if (!s.scheduler.canReleaseWriting(t)) conflict("编剧执行尚未结束或仍有结果待入库")
        }
        tasks.confirmAbsent(t.id, a.id)
        call.respond(tasks.get(t.id))
    }
    get("/media/{id}") {
        val media = s.catalog.media(call.id())
        call.response.header(HttpHeaders.ETag, "\"${media.sha256}\"")
        call.respondFile(s.media.resolve(media.relativePath).toFile())
    }
    get("/events") {
        val bounds = tasks.eventBounds()
        val requested = (call.request.header("Last-Event-ID") ?: call.request.queryParameters["after"])?.let {
            it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: invalid("事件游标无效")
        }
        val reset = requested != null && (requested > bounds.last || requested < bounds.first - 1)
        var seq = if (reset || requested == null) bounds.last else requested
        call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
        call.response.header("X-Accel-Buffering", "no")
        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
            write("id: $seq\nevent: ${if (reset) "reset" else "ready"}\ndata: {}\n\n")
            flush()
            var lastWrite = System.nanoTime()
            while (currentCoroutineContext().isActive) {
                val events = tasks.events(seq)
                for (e in events) {
                    write("id: ${e.seq}\nevent: task.updated\ndata: ${e.payload}\n\n")
                    seq = e.seq
                }
                if (events.isNotEmpty() || System.nanoTime() - lastWrite >= 15_000_000_000L) {
                    if (events.isEmpty()) write(": heartbeat\n\n")
                    flush(); lastWrite = System.nanoTime()
                }
                if (events.size < 100) delay(s.settings.pollMs)
            }
        }
    }
}
