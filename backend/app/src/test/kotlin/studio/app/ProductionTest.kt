package studio.app

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.*
import kotlin.test.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus
import studio.domain.TaskStatus.*
import studio.engine.*
import studio.mock.MockComfy
import studio.mock.mockServer

internal class Rig(stepMs: Long = 65, llmUrl: String? = null) : AutoCloseable {
    val root: Path = Files.createTempDirectory("studio-e2e")
    val mock = MockComfy(stepMs)
    val server = mockServer(mock, 0).start()
    private val settings = Settings(root, root.resolve("templates"), "mock",
        "http://127.0.0.1:${runBlocking { server.engine.resolvedConnectors().single().port }}", pollMs = 25,
        llmMode = if (llmUrl == null) "mock" else "llama", llmUrl = llmUrl ?: "http://127.0.0.1:8081")
    init {
        val dest = settings.packages.resolve("mock-image/1")
        Files.createDirectories(dest)
        for (name in listOf("manifest.json", "workflow.json")) Files.copy(Path.of("fixtures/mock-workflows/mock-image/1", name), dest.resolve(name))
    }
    var studio = Studio(settings).also { it.start() }
    val project = studio.catalog.create(ProjectInput("夜航"))
    val asset = studio.catalog.createAsset(project.id, AssetInput("车站", "SCENE"))
    val wf get() = studio.workflows.list("mock").last()
    fun request(prompt: String) = Generate(wf.id, wf.manifest.version, buildJsonObject { put("prompt", prompt) })
    fun submit(prompt: String = "月台", key: String = uid(), deps: List<String> = emptyList()) = studio.tasks.enqueue(
        asset.id, key, request(prompt).copy(dependencies = deps), studio.engine.identity, "mock").taskId
    suspend fun await(id: String, vararg states: TaskStatus): Task = withTimeout(10000) {
        var t = studio.tasks.get(id)
        while (t.status !in states) { delay(15); t = studio.tasks.get(id) }
        t
    }
    fun restart() { studio.close(); studio = Studio(settings).also { it.start() } }
    fun competingStudio() = Studio(settings.copy(storage = root.resolve("second-storage")))
    override fun close() { studio.close(); mock.close(); server.stop(200, 1500); root.toFile().deleteRecursively() }
}

class ProductionTest {
    @Test fun serializationIdempotencyAndImmutableResults() = runBlocking<Unit> {
        Rig().use { rig ->
            with(rig) {
                mock.externalBusy.set(true)
                val key = uid()
                val first = submit(key = key)
                assertEquals(first, submit(key = key))
                assertFailsWith<StudioError> { submit("参数不同", key) }
                val second = submit("第二张")
                await(first, READY)
                delay(100)
                assertEquals(0, mock.submissions)
                mock.externalBusy.set(false)
                await(second, SUCCEEDED)
                val f = await(first, SUCCEEDED).results.single()
                assertEquals(1, mock.maxOutstanding)
                assertEquals(2, mock.submissions)
                restart()
                assertEquals(f, studio.tasks.get(first).results.single())
                assertEquals(f.sha256, sha256(Files.readAllBytes(studio.media.resolve(f.relativePath))))
                assertEquals(mapOf("1.seed" to 42L), studio.tasks.get(first).snapshot.seeds)
                assertNull(studio.tasks.get(first).attempts.single().peakVramBytes)
                val versions = studio.catalog.asset(asset.id).versions
                assertEquals(listOf(2, 1), versions.map { it.revision })
                assertTrue(versions.all { it.approvalStatus == "REVIEW_REQUIRED" })
                assertTrue(studio.tasks.events(0).any { "SAMPLING" in it.payload })
            }
        }
    }
    @Test fun queuedWorkSurvivesOfflineStartupAndExclusiveEngineOwnership() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            assertFails { competingStudio().close() }
            studio.scheduler.stop()
            val id = submit()
            mock.statsUnavailable.set(true)
            restart()
            await(id, READY)
            assertFalse(studio.workflows.list("mock").single().enabled)
            assertEquals(0, mock.submissions)
            mock.statsUnavailable.set(false)
            await(id, SUCCEEDED)
            assertEquals(1, mock.submissions)
        } }
    }
    @Test fun unusableTemplateAndInvalidMediaAreContained() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val dir = root.resolve("templates/broken/1")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("manifest.json"), "{")
            studio.registry.refresh()
            assertTrue(studio.registry.issues.single().contains("broken"))
            assertFailsWith<InvalidOutput> { studio.media.save(project.id, uid(), 0, byteArrayOf(1, 2, 3)) }
            assertFailsWith<StudioError> { studio.media.resolve("../studio.db") }
            assertTrue(studio.workflows.workflow(wf.id).enabled)
        } }
    }
    @Test fun failureRetryAndDependencyIsolation() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val id = submit("[mock:fail-once]")
            val dep = submit(deps = listOf(id))
            val independent = submit("其他")
            val failure = await(id, FAILED)
            assertEquals("ENGINE_ERROR", failure.attempts.single().error?.code)
            assertEquals("DEPENDENCY", await(dep, FAILED).attempts.last().error?.code)
            studio.tasks.retry(id)
            val done = await(id, SUCCEEDED)
            assertEquals(listOf(FAILED, SUCCEEDED), done.attempts.map { it.status })
            assertEquals(failure.attempts.first(), done.attempts.first())
            await(independent, SUCCEEDED)
        } }
    }
    @Test fun cancelRetainsLeaseAndQueuedCancellationSkipsSubmission() = runBlocking<Unit> {
        Rig(160).use { rig -> with(rig) {
            val id = submit()
            await(id, RUNNING)
            val second = submit("接续")
            val skipped = submit("撤回")
            assertEquals(CANCELED, studio.tasks.cancel(skipped).status)
            assertEquals(CANCEL_REQUESTED, studio.tasks.cancel(id).status)
            assertTrue(await(id, CANCELED).results.isEmpty())
            await(second, SUCCEEDED)
            assertEquals(2, mock.submissions)
            assertEquals(1, mock.maxOutstanding)
        } }
    }
    @Test fun restartWithAndWithoutSubmissionReceipt() = runBlocking<Unit> {
        Rig(135).use { rig -> with(rig) {
            val id = submit()
            val promptId = await(id, RUNNING).attempts.single().externalId
            restart()
            assertEquals(promptId, await(id, SUCCEEDED).attempts.single().externalId)
            mock.loseNextAck.set(true)
            val lost = submit()
            await(lost, SUCCEEDED)
            val updates = studio.tasks.events(0).map { json.decodeFromString<TaskUpdate>(it.payload) }
            assertTrue(updates.any { it.taskId == lost && it.status == UNKNOWN })
            assertEquals(2, mock.submissions)
            assertEquals(1, studio.tasks.get(lost).attempts.size)
        } }
    }
    @Test fun crashAfterFileMoveReusesBytesAndCommitsOneResult() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            studio.scheduler.stop()
            val id = submit()
            val t = studio.tasks.next(studio.engine.identity)!!
            val attempt = t.attempts.single()
            assertTrue(studio.tasks.claim(id, attempt.id))
            val graph = bind(studio.workflows.graph(wf.id), wf.manifest, t.snapshot.parameters)
            val prompt = studio.engine.submit(attempt.id, graph)
            studio.tasks.acknowledge(id, prompt)
            val done = withTimeout(5000) {
                var h = studio.engine.history(prompt, wf.manifest.outputs)
                while (h == null) { delay(20); h = studio.engine.history(prompt, wf.manifest.outputs) }
                h as History.Success
            }
            val moved = studio.media.save(project.id, attempt.id, 0, studio.engine.download(done.files.single()))
            assertTrue(studio.catalog.asset(asset.id).versions.isEmpty())
            restart()
            assertEquals(moved, await(id, SUCCEEDED).results.single())
            assertEquals(1, studio.catalog.asset(asset.id).versions.size)
            assertEquals(1, mock.submissions)
        } }
    }
    @Test fun samplesAreScopedToOneAttemptAndDependenciesToOneProject() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            studio.scheduler.stop()
            val id = submit()
            val other = studio.catalog.create(ProjectInput("其他项目"))
            val otherAsset = studio.catalog.createAsset(other.id, AssetInput("角色"))
            assertFailsWith<StudioError> {
                studio.tasks.enqueue(otherAsset.id, uid(), request("文本").copy(dependencies=listOf(id)), studio.engine.identity, "mock")
            }
            val attempt = studio.tasks.next(studio.engine.identity)!!.attempts.single()
            assertTrue(studio.tasks.claim(id, attempt.id))
            studio.tasks.recordUsage(attempt.id, 100, null)
            studio.tasks.recordUsage(attempt.id, 60, 300)
            studio.tasks.finish(id, error=Problem("TEST", "测试终止"))
            studio.tasks.retry(id)
            val attempts = studio.tasks.get(id).attempts
            assertEquals(100L, attempts.first().peakVramBytes)
            assertEquals(300L, attempts.first().peakRamBytes)
            assertEquals(2, attempts.first().resourceSamples)
            assertNull(attempts.last().peakVramBytes)
        } }
    }
    @Test fun indeterminateSubmissionBlocksDispatchUntilReconciled() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            studio.scheduler.stop()
            val id = submit()
            val t = studio.tasks.next(studio.engine.identity)!!
            assertTrue(studio.tasks.claim(id, t.attempts.single().id))
            val other = submit("等待")
            restart()
            await(id, UNKNOWN)
            assertFailsWith<StudioError> { studio.tasks.retry(id) }
            delay(180)
            assertEquals(0, mock.submissions)
            studio.tasks.confirmAbsent(id, t.attempts.single().id)
            await(other, SUCCEEDED)
            assertEquals(1, mock.submissions)
        } }
    }
    @Test fun publishedWorkflowVersionsRemainImmutable() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val base = wf
            val param = base.manifest.parameters.first().copy(name = "description", default = JsonPrimitive("月光"))
            val next = studio.registry.edit(base.id, WorkflowEdit("2", "另一个版本", listOf(param)))
            assertEquals("2", next.manifest.version)
            assertNotEquals(base.templateHash, next.templateHash)
            assertFailsWith<StudioError> { studio.registry.edit(base.id, WorkflowEdit("2", "冲突", listOf(param))) }
            assertFailsWith<StudioError> { studio.registry.edit(base.id, WorkflowEdit("3", "越界", listOf(param.copy(input = "other")))) }
            restart()
            assertEquals(2, studio.workflows.list("mock").count { it.enabled })
            val original = root.resolve("templates/mock-image/1/workflow.json")
            Files.writeString(original, Files.readString(original).replace("42", "43"))
            studio.registry.refresh()
            assertFalse(studio.workflows.workflow(base.id).enabled)
            assertEquals(base.templateHash, studio.workflows.workflow(base.id).templateHash)
        } }
    }
    @Test fun restSseCursorAndRange() = runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val api = studioServer(studio, 0).start()
            val base = "http://127.0.0.1:${api.engine.resolvedConnectors().single().port}/api/v1"
            val client = HttpClient.newHttpClient()
            fun req(path: String) = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(6))
            try {
                val post = req("/assets/${asset.id}/generate").header("Content-Type", "application/json").header("Idempotency-Key", uid())
                    .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request("测试")))).build()
                val accepted = client.send(post, BodyHandlers.ofString())
                assertEquals(202, accepted.statusCode(), accepted.body())
                val id = json.decodeFromString<Accepted>(accepted.body()).taskId
                val task = await(id, SUCCEEDED)
                val cursor = studio.tasks.events(0).first().seq
                val stream = client.send(req("/events").header("Last-Event-ID", cursor.toString()).build(), BodyHandlers.ofInputStream())
                stream.body().bufferedReader().use { reader ->
                    val first = List(8) { reader.readLine() }
                    assertEquals("event: ready", first[1])
                    assertEquals("id: ${cursor + 1}", first[4])
                    assertTrue(first[6].contains(id))
                }
                val reset = client.send(req("/events?after=99999999").build(), BodyHandlers.ofInputStream())
                reset.body().bufferedReader().use { it.readLine(); assertEquals("event: reset", it.readLine()) }
                val media = client.send(req("/media/${task.results.single().id}").header("Range", "bytes=0-7").build(), BodyHandlers.ofByteArray())
                assertEquals(206, media.statusCode())
                assertEquals(8, media.body().size)
                val bad = client.send(req("/tasks?limit=200").build(), BodyHandlers.ofString())
                assertEquals(422, bad.statusCode())
                assertTrue(bad.body().contains("requestId"))
                val cross = req("/projects").header("Origin","https://outside.invalid").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"title":"无效来源"}""")).build()
                assertEquals(403, client.send(cross, BodyHandlers.ofString()).statusCode())
                val large = req("/projects").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat(1024*1024+1))).build()
                assertEquals(413, client.send(large, BodyHandlers.ofString()).statusCode())
            } finally { api.stop(0, 1000); client.close() }
        } }
    }
}
