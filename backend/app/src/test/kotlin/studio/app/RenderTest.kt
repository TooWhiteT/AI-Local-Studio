package studio.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*

internal data class Scene(val episode: String, val board: Board, val frames: List<Media>)
internal suspend fun Rig.scene(owner: Project = project): Scene {
    val (ep, script) = script(owner)
    studio.writing.approve(WritingKind.SCRIPT,ep,script.id,1)
    val item = studio.catalog.createAsset(owner.id,AssetInput("场景","SCENE"))
    await(studio.tasks.enqueue(item.id,uid(),request("测试场景"),studio.engine.identity,"mock").taskId,SUCCEEDED)
    val ref = studio.catalog.asset(item.id).versions.single()
    studio.catalog.review(item.id,ref.id,0,true)
    val boardId = await(queue(BoardJob.SHOT_PLAN,ep,BoardRequest(script.id,0,listOf(ref.id),1)),SUCCEEDED).writingResultId
    val board = studio.boards.board(ep,boardId)
    val frames = board.shots.map { shot ->
        val input = KeyframeRequest(shot.revisionId,wf.id,wf.manifest.version)
        await(studio.tasks.enqueueKeyframe(shot.id,uid(),input,studio.engine.identity,"mock").taskId,SUCCEEDED).results.single()
    }
    return Scene(ep,board,frames)
}
internal suspend fun Rig.video(): Workflow {
    val target = Files.createDirectories(root.resolve("templates/mock-video/1"))
    for (name in listOf("workflow.json","manifest.json")) Files.copy(Path.of("fixtures/mock-workflows/mock-video/1",name),target.resolve(name))
    return studio.registry.refresh().single { it.manifest.id == "mock-video" }.also { assertTrue(it.enabled,it.reason) }
}
internal fun Rig.approve(scene: Scene) {
    studio.boards.approve(scene.episode,scene.board.version!!.id,1)
    for ((index,shot) in scene.board.shots.withIndex())
        studio.boards.approveFrame(shot.id,scene.frames[index].id,FrameReview(shot.revisionId,0))
}


class RenderTest {
    @Test fun batchIsAtomicAndIdempotentAcrossRestartsAndForeignFramesAreRejected() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val main = scene()
            val other = scene(studio.catalog.create(ProjectInput("其他项目")))
            val image = wf
            val video = video()
            val shot = main.board.shots.first()
            val frame = main.frames.first()
            val single = RenderRequest(shot.revisionId,frame.id,video.id,video.manifest.version)
            fun render(request: RenderRequest,key: String=uid()) = studio.tasks.enqueueRender(shot.id,key,request,studio.engine.identity,"mock")
            assertFailsWith<StudioError> { render(single) }
            studio.boards.approve(main.episode,main.board.version!!.id,1)
            assertFailsWith<StudioError> { render(single) }
            assertFailsWith<StudioError> { studio.boards.approveFrame(shot.id,other.frames.first().id,FrameReview(shot.revisionId,0)) }
            approve(main)
            assertFailsWith<StudioError> { studio.boards.approveFrame(shot.id,frame.id,FrameReview(shot.revisionId,0)) }
            assertFailsWith<StudioError> { render(single.copy(workflowId=image.id,workflowVersion=image.manifest.version)) }
            assertFailsWith<StudioError> { render(single.copy(firstFrameId=other.frames.first().id)) }
            studio.boards.approveFrame(shot.id,frame.id,FrameReview(shot.revisionId,1,"last"))
            assertFailsWith<StudioError> { render(single.copy(lastFrameId=frame.id)) }
            val selections = main.board.shots.zip(main.frames).map { (s,f) -> RenderSelection(s.revisionId,f.id) }
            val batch = BatchRequest(main.board.version!!.id,video.id,video.manifest.version,selections)
            val key = uid()
            fun submitBatch(request: BatchRequest) = studio.tasks.enqueueBatch(main.episode,key,request,studio.engine.identity,"mock")
            studio.scheduler.stop()
            val before = studio.tasks.list(project.id,null,Window()).items
            val foreign = RenderSelection(other.board.shots.first().revisionId,other.frames.first().id)
            assertFailsWith<StudioError> { submitBatch(batch.copy(shots=listOf(selections.first(),foreign))) }
            assertEquals(before,studio.tasks.list(project.id,null,Window()).items)
            assertFailsWith<StudioError> { submitBatch(batch.copy(shots=List(2){selections.first()})) }
            val accepted = submitBatch(batch)
            assertEquals(2,accepted.taskIds.distinct().size)
            assertEquals(accepted,submitBatch(batch))
            assertFailsWith<StudioError> { submitBatch(batch.copy(shots=selections.reversed())) }
            assertFailsWith<StudioError> { render(single,key) }
            for ((i,id) in accepted.taskIds.withIndex()) {
                val task = studio.tasks.get(id)
                assertEquals(main.frames[i],task.snapshot.firstFrame)
                assertEquals(main.board.shots[i].references.map { it.versionId },task.snapshot.assetVersionIds)
                assertEquals(main.board.version!!.id,task.snapshot.boardId)
                assertEquals(listOf(PENDING),task.attempts.map { it.status })
            }
            val submissions = mock.submissions
            restart()
            for (id in accepted.taskIds) {
                val done = await(id,SUCCEEDED,FAILED)
                assertEquals(SUCCEEDED,done.status,done.attempts.last().error.toString())
                assertTrue(done.attempts.single().submitStarted)
                assertTrue(done.results.single().frameCount!! > 1)
            }
            assertEquals(accepted,submitBatch(batch))
            assertEquals(submissions+2,mock.submissions)
            assertEquals(1,mock.maxOutstanding)
            val result = studio.boards.renders(shot.id,null,Window()).items.single()
            val approved = studio.boards.reviewRender(shot.id,result.id,0,true)
            assertEquals("APPROVED",approved.approvalStatus)
            assertFailsWith<StudioError> { studio.boards.reviewRender(other.board.shots.first().id,result.id,0,true) }
            studio.boards.edit(shot.id,ShotEdit(1,action="镜头已更新"))
            val history = studio.boards.renders(shot.id,shot.revisionId,Window()).items.single()
            assertTrue(history.stale)
            assertEquals(approved.media,history.media)
            assertEquals("APPROVED",history.approvalStatus)
            assertFalse(studio.boards.renders(main.board.shots.last().id,null,Window()).items.single().stale)
            assertEquals(accepted,submitBatch(batch))
        } }
    }

    @Test fun inputModesUseOnlyTheApprovedFilesForEachShot() = runBlocking<Unit> {
        Rig(stepMs=25).use { rig -> with(rig) {
            val scene = scene()
            val shot = scene.board.shots.first()
            val frame = scene.frames.first()
            val reference = shot.references.single()
            studio.boards.approve(scene.episode,scene.board.version!!.id,1)
            val base = video()
            val graph = studio.workflows.graph(base.id)
            val fixtures = root.resolve("templates")
            fun publish(name: String, manifest: studio.engine.Manifest, variant: JsonObject) {
                val directory = Files.createDirectories(fixtures.resolve("$name/1"))
                Files.writeString(directory.resolve("manifest.json"),json.encodeToString(manifest.copy(id=name)))
                Files.writeString(directory.resolve("workflow.json"),variant.toString())
            }
            val frameInput = checkNotNull(base.manifest.firstFrame)
            publish("mock-reference",base.manifest.copy(firstFrame=null,referenceImage=frameInput,capabilities=listOf("reference-to-video")),graph)
            val videoNode = graph.getValue("1").jsonObject
            val inputs = videoNode.getValue("inputs").jsonObject.toMutableMap()
            inputs["last_frame"] = buildJsonArray { add("3"); add(0) }
            val dual = JsonObject(graph + mapOf("1" to JsonObject(videoNode + ("inputs" to JsonObject(inputs))),"3" to graph.getValue("2")))
            publish("mock-dual",base.manifest.copy(lastFrame=frameInput.copy(node="3")),dual)
            val flows = studio.registry.refresh().associateBy { it.manifest.id }
            val r2v = flows.getValue("mock-reference")
            val fl2v = flows.getValue("mock-dual")
            assertTrue(r2v.enabled,r2v.reason)
            assertTrue(fl2v.enabled,fl2v.reason)
            fun send(input: RenderRequest) = studio.tasks.enqueueRender(shot.id,uid(),input,studio.engine.identity,"mock").taskId
            val refRequest = RenderRequest(shot.revisionId,workflowId=r2v.id,workflowVersion="1",referenceVersionId=reference.versionId)
            assertFailsWith<StudioError> { send(refRequest.copy(referenceVersionId=uid())) }
            assertFailsWith<StudioError> { send(refRequest.copy(firstFrameId=frame.id)) }
            assertFailsWith<StudioError> { send(refRequest.copy(workflowId=base.id)) }
            val refResult = await(send(refRequest),SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,refResult.status,refResult.attempts.last().error.toString())
            assertEquals(reference.file,refResult.snapshot.referenceFrame)
            assertNull(refResult.snapshot.firstFrame)
            assertContains(refResult.snapshot.parameters.getValue("prompt").jsonPrimitive.content,"<Picture 1>")
            assertEquals(5.0,refResult.snapshot.parameters.getValue("durationSeconds").jsonPrimitive.double)
            studio.boards.approveFrame(shot.id,frame.id,FrameReview(shot.revisionId,0))
            val dualRequest = RenderRequest(shot.revisionId,frame.id,fl2v.id,"1",lastFrameId=frame.id)
            assertFailsWith<StudioError> { send(dualRequest) }
            studio.boards.approveFrame(shot.id,frame.id,FrameReview(shot.revisionId,1,"last"))
            assertFailsWith<StudioError> { send(dualRequest.copy(lastFrameId=null)) }
            val dualResult = await(send(dualRequest),SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,dualResult.status,dualResult.attempts.last().error.toString())
            assertEquals(frame,dualResult.snapshot.firstFrame)
            assertEquals(frame,dualResult.snapshot.lastFrame)
            val results = studio.boards.renders(shot.id,null,Window()).items
            assertEquals(listOf(null,reference.versionId),results.map { it.referenceVersionId })
            assertEquals(listOf(frame.id,null),results.map { it.firstFrameId })
            assertTrue(results.none { it.stale })
        } }
    }

    @Test fun videoSubmissionCheckpointSurvivesCrashesAndCancellationKeepsTheLease() = runBlocking<Unit> {
        Rig(stepMs=100).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            val shot = scene.board.shots.first()
            val video = video()
            val input = RenderRequest(shot.revisionId,scene.frames.first().id,video.id,"1")
            fun render() = studio.tasks.enqueueRender(shot.id,uid(),input,studio.engine.identity,"mock").taskId
            studio.scheduler.stop()
            val id = render()
            val first = studio.tasks.next(studio.engine.identity)!!
            assertTrue(studio.tasks.claim(id,first.attempts.single().id))
            assertFalse(studio.tasks.get(id).attempts.single().submitStarted)
            val count = mock.submissions
            mock.loseNextAck.set(true)
            restart()
            val recovered = await(id,SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,recovered.status,recovered.attempts.single().error.toString())
            assertEquals(count+1,mock.submissions)
            assertNotNull(recovered.attempts.single().externalId)
            assertTrue(studio.tasks.events(0).any { it.payload.contains(id) && it.payload.contains("UNKNOWN") })
            val original = studio.media.resolve(scene.frames.first().relativePath)
            val moved = Files.move(original,original.resolveSibling("temporarily-missing-for-test.png"))
            val retry = render()
            try {
                val failed = await(retry,FAILED)
                assertEquals("FRAME_INPUT_ERROR",failed.attempts.single().error?.code)
                assertFalse(failed.attempts.single().submitStarted)
            } finally { Files.move(moved,original) }
            studio.tasks.retry(retry)
            assertEquals(listOf(FAILED,SUCCEEDED),await(retry,SUCCEEDED).attempts.map { it.status })
            studio.scheduler.stop()
            val uncertain = render()
            val attempt = studio.tasks.next(studio.engine.identity)!!.attempts.single()
            assertTrue(studio.tasks.claim(uncertain,attempt.id))
            assertTrue(studio.tasks.beginRenderSubmit(uncertain,attempt.id))
            assertFalse(studio.tasks.beginRenderSubmit(uncertain,attempt.id))
            val pending = render()
            restart()
            await(uncertain,UNKNOWN)
            assertEquals(count+2,mock.submissions)
            assertEquals(uncertain,studio.tasks.lease()!!.id)
            assertEquals(CANCELED,studio.tasks.cancel(pending).status)
            studio.tasks.confirmAbsent(uncertain,attempt.id)
            val cancel = render()
            await(cancel,RUNNING)
            assertEquals(CANCEL_REQUESTED,studio.tasks.cancel(cancel).status)
            val next = render()
            assertEquals(CANCELED,await(cancel,CANCELED).status)
            await(next,SUCCEEDED)
            assertTrue(studio.tasks.get(cancel).results.isEmpty())
            assertEquals(1,mock.maxOutstanding)
        } }
    }

    @Test fun exportedH3GridAgreesWithTheValidatedSample() {
        val pkg = studio.infra.WorkflowFiles(Path.of("workflows")).packages().single { it.manifest.id == "minimax-h3-i2v" }
        assertEquals(124,plannedVideoFrames(pkg.graph))
        assertEquals(73,plannedVideoFrames(bind(pkg.graph,pkg.manifest,buildJsonObject { put("durationSeconds",3) })))
        assertNull(plannedVideoFrames(JsonObject(emptyMap())))
    }

    @Test fun restShotRenderRecoversAcceptedPromptAndReturnsPlayableImmutableMedia() = runBlocking<Unit> {
        Rig(stepMs=100).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            val video = video()
            val shot = scene.board.shots.first()
            val request = RenderRequest(shot.revisionId,scene.frames.first().id,video.id,video.manifest.version,
                buildJsonObject { put("durationMs",3000); put("resolution","480p"); put("prompt","缓慢平移") })
            val key = uid()
            val queued = studio.tasks.enqueueRender(shot.id,key,request,studio.engine.identity,"mock")
            await(queued.taskId,SUBMITTED,RUNNING)
            restart()
            val task = await(queued.taskId,SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,task.status,task.attempts.single().error.toString())
            assertEquals(3.0,task.snapshot.parameters.getValue("durationSeconds").jsonPrimitive.double)
            assertContains(task.snapshot.parameters.getValue("prompt").jsonPrimitive.content,shot.action)
            val media = task.results.single()
            assertEquals("video/mp4",media.mime)
            assertEquals(media.sha256,sha256(Files.readAllBytes(studio.media.resolve(media.relativePath))))
            val api = studioServer(studio,0).start()
            val base = "http://127.0.0.1:${api.engine.resolvedConnectors().single().port}/api/v1"
            HttpClient.newHttpClient().use { client ->
                fun uri(path: String) = HttpRequest.newBuilder(URI.create(base+path))
                try {
                    val replay = uri("/shots/${shot.id}/render").header("Content-Type","application/json").header("Idempotency-Key",key)
                        .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request))).build()
                    val accepted = client.send(replay,BodyHandlers.ofString())
                    assertEquals(202,accepted.statusCode(),accepted.body())
                    assertEquals(queued.taskId,json.decodeFromString<Accepted>(accepted.body()).taskId)
                    val results = client.send(uri("/shots/${shot.id}/renders?shotRevisionId=${shot.revisionId}").GET().build(),BodyHandlers.ofString())
                    assertEquals(200,results.statusCode(),results.body())
                    val version = json.decodeFromString<Page<RenderVersion>>(results.body()).items.single()
                    assertEquals(media,version.media)
                    val range = client.send(uri("/media/${media.id}").header("Range","bytes=4-7").build(),BodyHandlers.ofByteArray())
                    assertEquals(206,range.statusCode())
                    assertEquals("ftyp",String(range.body(),Charsets.US_ASCII))
                    val reject = uri("/shots/${shot.id}/renders/${version.id}/reject").header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"expectedRevision":0}""")).build()
                    assertEquals("ARCHIVED",json.decodeFromString<RenderVersion>(client.send(reject,BodyHandlers.ofString()).body()).approvalStatus)
                    assertEquals(409,client.send(reject,BodyHandlers.ofString()).statusCode())
                } finally { api.stop(0,1000) }
            }
        } }
    }
}
