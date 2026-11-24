package studio.app

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import studio.application.*
import studio.core.*
import java.util.concurrent.CancellationException

@Serializable data class ApiError(val code: String, val message: String, val requestId: String, val details: Map<String,String> = emptyMap())

fun Application.installHttp() {
    install(RequestBodyLimit) { bodyLimit { 1024L * 1024 } }
    install(createApplicationPlugin("LocalOrigin") {
        onCall { call ->
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)) {
                val host = runCatching { java.net.URI(origin).host }.getOrNull()
                if (host !in setOf("localhost", "127.0.0.1", "[::1]"))
                    throw StudioError("ORIGIN_DENIED", "仅允许来自本地工作台的修改请求")
            }
        }
    })
    install(ContentNegotiation) { json(json) }
    install(PartialContent)
    install(StatusPages) {
        exception<StudioError> { call, e ->
            val status = when (e.code) {
                "NOT_FOUND" -> HttpStatusCode.NotFound
                "CONFLICT" -> HttpStatusCode.Conflict
                "ENGINE_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
                "ORIGIN_DENIED" -> HttpStatusCode.Forbidden
                else -> HttpStatusCode.UnprocessableEntity
            }
            call.respond(status, ApiError(e.code, e.message.orEmpty(), uid()))
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ApiError("PAYLOAD_TOO_LARGE", "请求超过 1 MiB", uid()))
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("UNSUPPORTED_MEDIA_TYPE", "需要 application/json", uid()))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_JSON", "请求参数或 JSON 格式错误", uid()))
        }
        exception<Throwable> { call, e ->
            if (e is CancellationException) throw e
            val requestId = uid()
            call.application.log.error("Request $requestId failed", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL_ERROR", "本地服务处理失败", requestId))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "资源不存在", uid()))
        }
    }
}
fun ApplicationCall.id() = validId(parameters["id"].orEmpty())
fun ApplicationCall.window(): Window {
    val q = request.queryParameters
    return Window(q["cursor"]?.let { it.toLongOrNull() ?: invalid("游标无效") } ?: Long.MAX_VALUE,
        q["limit"]?.let { it.toIntOrNull() ?: invalid("分页大小无效") } ?: 30)
}
fun Route.catalogRoutes(catalog: Catalog) {
    get("/projects") { call.respond(catalog.projects(call.window())) }
    post("/projects") { call.respond(HttpStatusCode.Created, catalog.create(call.receive())) }
    get("/projects/{id}") { call.respond(catalog.project(call.id())) }
    patch("/projects/{id}") { call.respond(catalog.edit(call.id(), call.receive())) }
    delete("/projects/{id}") {
        val rev = call.request.queryParameters["expectedRevision"]?.toIntOrNull() ?: invalid("需要 expectedRevision")
        call.respond(catalog.edit(call.id(), ProjectEdit(rev, status = "ARCHIVED")))
    }
    get("/projects/{id}/assets") { call.respond(catalog.assets(call.id(), call.window())) }
    post("/projects/{id}/assets") { call.respond(HttpStatusCode.Created, catalog.createAsset(call.id(), call.receive())) }
    for (decision in listOf("approve", "reject")) {
        post("/assets/{id}/versions/{vid}/$decision") {
            call.respond(catalog.review(call.id(), validId(call.parameters["vid"].orEmpty()),
                call.receive<WritingApproval>().expectedRevision, decision == "approve"))
        }
    }
    get("/assets/{id}") { call.respond(catalog.asset(call.id())) }
}
