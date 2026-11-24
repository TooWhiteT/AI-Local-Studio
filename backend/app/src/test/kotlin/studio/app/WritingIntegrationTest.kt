package studio.app

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*
import studio.engine.*
import studio.llm.MockTextEngine
import studio.scheduler.GpuScheduler
import studio.scheduler.WriterLoadBudget

private val fixtureOutline = json.parseToJsonElement("""{"title":"旧站","logline":"站员寻找寄件人","world":"山城",
    "characters":[{"key":"lin","name":"林青","description":"站员"}],"locations":[{"key":"post","name":"邮局","description":"老站"}],"beats":["找到信件"]}""").jsonObject

/** HTTP fixture for a synchronous llama.cpp exchange. Counting POSTs detects actual resubmission. */
private class ChatFixture : AutoCloseable {
    val posts = AtomicInteger()
    val gate = CountDownLatch(1)
    val began = CountDownLatch(1)
    @Volatile var code = 200
    @Volatile var output = fixtureOutline.toString()
    @Volatile var captured: JsonObject? = null
    @Volatile var chatTemplate: String? = null
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
        executor = pool
        createContext("/") { exchange ->
            val (status, data) = when (exchange.requestURI.path) {
                "/v1/models" -> 200 to """{"data":[{"id":"wire-fixture"}]}"""
                "/props" -> 200 to buildJsonObject {
                    put("build_info","fixture-build"); put("is_sleeping",false); put("endpoint_slots",true)
                    chatTemplate?.let { put("chat_template",it) }
                }.toString()
                "/slots" -> 200 to """[{"is_processing":${began.count == 0L && gate.count > 0L}}]"""
                "/v1/chat/completions" -> {
                    posts.incrementAndGet()
                    captured = json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
                    began.countDown(); gate.await(12,TimeUnit.SECONDS)
                    code to buildJsonObject {
                        put("id","chatcmpl-fixture")
                        put("choices",buildJsonArray { addJsonObject {
                            put("finish_reason","stop"); putJsonObject("message") { put("role","assistant"); put("content",output) }
                        } })
                    }.toString()
                }
                else -> 404 to "{}"
            }
            val bytes=data.toByteArray()
            exchange.sendResponseHeaders(status,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    override fun close() { gate.countDown(); server.stop(0); pool.shutdownNow() }
}
private fun Rig.write(key: String = uid(), brief: String = "山城的信") = studio.tasks.enqueueWriting(
    WritingKind.OUTLINE,project.id,key,WritingRequest(0,brief),checkNotNull(studio.writer).identity,"wire-fixture","fixture-build").taskId

class WritingIntegrationTest {
    @Test fun structuredEndpointPinsSupportedThinkingSwitchAndOmitsItForOtherTemplates() = runBlocking<Unit> {
        ChatFixture().use { http -> Rig(llmUrl=http.url).use { rig -> with(rig) {
            http.gate.countDown()
            val api = studioServer(studio,0).start()
            try {
                val port = api.engine.resolvedConnectors().single().port
                val uri = java.net.URI("http://127.0.0.1:$port/api/v1/projects/${project.id}/outline/generate")
                java.net.http.HttpClient.newHttpClient().use { client ->
                    for ((revision, supported) in listOf(true,false).withIndex()) {
                        http.chatTemplate = if (supported) "{% if enable_thinking %}…{% endif %}" else "{{ messages }}"
                        val body = json.encodeToString(WritingRequest(revision,"旧站来信"))
                        val req = java.net.http.HttpRequest.newBuilder(uri).header("Content-Type","application/json")
                            .header("Idempotency-Key",uid()).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build()
                        val result = client.send(req,java.net.http.HttpResponse.BodyHandlers.ofString())
                        assertEquals(202,result.statusCode(),result.body())
                        val done = await(json.decodeFromString<Accepted>(result.body()).taskId,SUCCEEDED)
                        assertEquals(if (supported) false else null,done.snapshot.prompt?.enableThinking)
                        val kwargs = http.captured!!.get("chat_template_kwargs") as? JsonObject
                        if (supported) assertEquals(JsonPrimitive(false),kwargs?.get("enable_thinking")) else assertNull(kwargs)
                    }
                }
            } finally { api.stop(200,1500) }
        } } }
    }

    @Test fun imageWaitsWithoutOverstatingAvailableVramOrKeepingWriterAwake() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            studio.scheduler.stop()
            val asleep = java.util.concurrent.atomic.AtomicBoolean(false)
            val slotChecks = AtomicInteger()
            val image = object : ImageEngine by studio.engine {
                override val mode = "comfy"
                override suspend fun resourcesForSwitch() = studio.engine.info().copy(vramTotal=32L shl 30, vramFree=null)
            }
            val text = object : TextEngine by MockTextEngine() {
                override val real = true
                override suspend fun sleeping() = asleep.get()
                override suspend fun idle(): Boolean { slotChecks.incrementAndGet(); return true }
            }
            val scheduler = GpuScheduler(studio.tasks,studio.registry,studio.media,image,20,text)
            scheduler.start()
            try {
                val id = submit()
                withTimeout(4000) { while (scheduler.waitingReason?.contains("休眠") != true) delay(20) }
                assertEquals(0,mock.submissions)
                assertEquals(0,slotChecks.get())
                asleep.set(true)
                await(id,SUCCEEDED)
                assertEquals(1,mock.submissions)
            } finally { scheduler.stop() }
        } }
    }

    @Test fun sharedDeviceWaitsForComfyMemoryBeforeWakingWriter() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            studio.scheduler.stop()
            val free = java.util.concurrent.atomic.AtomicLong(0)
            val releases = AtomicInteger()
            val image = object : ImageEngine by studio.engine {
                override val mode = "comfy"
                override suspend fun releaseModels() { releases.incrementAndGet() }
                override suspend fun resourcesForSwitch() = studio.engine.info().copy(
                    vramTotal=8L shl 30,vramFree=free.get(),ramTotal=16L shl 30,ramFree=8L shl 30)
            }
            val text = object : TextEngine by MockTextEngine() {
                override val real = true
                override suspend fun sleeping() = true
            }
            val scheduler = GpuScheduler(studio.tasks,studio.registry,studio.media,image,20,text,WriterLoadBudget(1024,1024))
            scheduler.start()
            try {
                val id=studio.tasks.enqueueWriting(WritingKind.OUTLINE,project.id,uid(),WritingRequest(0,"来信"),text.identity,"mock-writer-v1").taskId
                await(id,SUBMITTING)
                val picture=submit()
                withTimeout(4000) { while (scheduler.waitingReason?.contains("内存") != true) delay(20) }
                assertEquals(1,releases.get()); assertEquals(0,mock.submissions)
                free.set(2L shl 30)
                val written=await(id,SUCCEEDED); val rendered=await(picture,SUCCEEDED)
                assertTrue(rendered.attempts.single().startedAt!! >= written.attempts.single().finishedAt!!)
                assertEquals(1,releases.get())
            } finally { scheduler.stop() }
        } }
    }

    @Test fun synchronousModelSurvivesStudioRestartAndSharesImageLease() = runBlocking<Unit> {
        ChatFixture().use { http -> Rig(llmUrl=http.url).use { rig -> with(rig) {
            val key=uid(); val id=write(key)
            assertEquals(id,write(key)); assertFailsWith<StudioError> { write(key,"另一请求") }
            assertTrue(http.began.await(8,TimeUnit.SECONDS))
            val first=await(id,RUNNING).attempts.single()
            val image=submit()
            restart()
            assertEquals(id,studio.tasks.lease()?.id)
            assertEquals(0,mock.submissions)
            assertEquals(first.id,studio.tasks.get(id).attempts.single().id)
            http.gate.countDown()
            val done=await(id,SUCCEEDED); val second=await(image,SUCCEEDED)
            assertEquals(1,http.posts.get()); assertTrue(second.attempts.single().startedAt!! >= done.attempts.single().finishedAt!!)
            assertEquals("chatcmpl-fixture",done.attempts.single().externalId)
            assertEquals("fixture-build",done.snapshot.engineVersion); assertNotNull(done.snapshot.prompt)
            assertEquals("json_schema",http.captured!!["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertFalse(http.captured!!.containsKey("tools"))
            val rev=studio.writing.revisions(WritingKind.OUTLINE,project.id,Window()).items.single()
            assertEquals(done.writingResultId,rev.id);assertEquals(first.id,rev.sourceAttemptId);assertEquals("REVIEW_REQUIRED",rev.approvalStatus)
            restart();assertEquals(rev,studio.writing.revisions(WritingKind.OUTLINE,project.id,Window()).items.single())
        } } }
    }
    @Test fun cancelWaitsForModelBeforeStartingImage() = runBlocking<Unit> {
        ChatFixture().use { http -> Rig(llmUrl=http.url).use { rig -> with(rig) {
            val id=write();assertTrue(http.began.await(8,TimeUnit.SECONDS));studio.tasks.cancel(id)
            val image=submit();delay(120)
            assertEquals(CANCEL_REQUESTED,studio.tasks.get(id).status);assertEquals(0,mock.submissions)
            http.gate.countDown();await(id,CANCELED);await(image,SUCCEEDED)
            assertTrue(studio.writing.revisions(WritingKind.OUTLINE,project.id,Window()).items.isEmpty())
        } } }
    }
    @Test fun unknownDoesNotReplayAndRequiresIdleProvider() = runBlocking<Unit> {
        ChatFixture().use { http -> Rig(llmUrl=http.url).use { rig -> with(rig) {
            http.code=502;http.gate.countDown()
            val id=write();val unknown=await(id,UNKNOWN);val image=submit()
            restart();delay(130)
            assertEquals(1,http.posts.get());assertEquals(0,mock.submissions)
            assertTrue(studio.scheduler.canReleaseWriting(studio.tasks.get(id)))
            studio.tasks.confirmAbsent(id,unknown.attempts.single().id)
            await(image,SUCCEEDED)
            http.code=200;studio.tasks.retry(id);await(id,SUCCEEDED)
            assertEquals(2,http.posts.get());assertEquals(2,studio.tasks.get(id).attempts.size)
        } } }
    }
    @Test fun invalidModelOutputAndConcurrentEditsPreserveHumanWork() = runBlocking<Unit> {
        ChatFixture().use { http -> Rig(llmUrl=http.url).use { rig -> with(rig) {
            val id=write();assertTrue(http.began.await(8,TimeUnit.SECONDS))
            val manual=studio.writing.save(WritingKind.OUTLINE,project.id,WritingEdit(0,fixtureOutline))
            http.gate.countDown()
            assertEquals("REVISION_CONFLICT",await(id,FAILED).attempts.single().error?.code)
            http.output="""{"tools":["run"]}"""
            val invalid=studio.tasks.enqueueWriting(WritingKind.OUTLINE,project.id,uid(),WritingRequest(1,"调整"),
                checkNotNull(studio.writer).identity,"wire-fixture","fixture-build").taskId
            assertEquals("INVALID_MODEL_OUTPUT",await(invalid,FAILED).attempts.single().error?.code)
            assertEquals(listOf(manual),studio.writing.revisions(WritingKind.OUTLINE,project.id,Window()).items)
        } } }
    }
}
