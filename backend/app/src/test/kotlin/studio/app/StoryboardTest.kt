package studio.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*

private fun doc(raw: String)=json.parseToJsonElement(raw).jsonObject
internal fun Rig.script(owner: Project = project): Pair<String,WritingRevision> {
    val story=studio.writing.save(WritingKind.OUTLINE,owner.id,WritingEdit(0,doc("""{"title":"来信","logline":"一封信","world":"山城",
        "characters":[{"key":"lin","name":"林青","description":"站员"}],"locations":[{"key":"post","name":"邮局","description":"旧柜台"}],"beats":["发现退信"]}""")))
    studio.writing.approve(WritingKind.OUTLINE,owner.id,story.id,1)
    val plan=studio.writing.save(WritingKind.EPISODE_PLAN,owner.id,WritingEdit(0,doc("""{"episodes":[{"title":"来信","summary":"找到信件","targetDurationMs":60000}]}"""),story.id))
    val episode=studio.writing.episodes(owner.id).episodes.single().id
    val scene=doc("""{"locationKey":"post","characters":["lin"],"action":"展开信件","durationMs":30000,"dialogues":[{"speaker":"lin","text":"到了。","emotion":"平静"}]}""")
    val script=studio.writing.save(WritingKind.SCRIPT,episode,WritingEdit(0,buildJsonObject {
        put("title","来信");put("scenes",JsonArray(listOf(scene,scene)))
    },story.id,plan.id))
    return episode to script
}
internal fun Rig.queue(kind: BoardJob,target: String,req: BoardRequest,key: String=uid()) =
    studio.tasks.enqueueBoard(kind,target,key,req,studio.writer!!.identity,"mock-writer-v1","mock-writer-v1",null).taskId


class StoryboardTest {
    @Test fun extractionDeduplicatesAndRequiresApprovedProjectScript()=runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val (ep,s)=script(); val req=BoardRequest(s.id,1)
            assertFailsWith<StudioError> {queue(BoardJob.ASSET_EXTRACT,project.id,req)}
            studio.writing.approve(WritingKind.SCRIPT,ep,s.id,1)
            val other=studio.catalog.create(ProjectInput("另一项目"))
            assertFailsWith<StudioError> {queue(BoardJob.ASSET_EXTRACT,other.id,req)}
            assertFailsWith<StudioError> {queue(BoardJob.SHOT_PLAN,ep,req.copy(expectedRevision=0))}
            val key=uid();val id=queue(BoardJob.ASSET_EXTRACT,project.id,req,key)
            assertEquals(id,queue(BoardJob.ASSET_EXTRACT,project.id,req,key))
            val result=studio.boards.extraction(project.id,await(id,SUCCEEDED).writingResultId!!)
            assertEquals(setOf(1,2),result.assets.map { it.sceneOrdinal }.toSet())
            val perScene=result.assets.groupBy {it.sceneOrdinal}.mapValues { a -> a.value.map {it.assetId}.toSet() }
            assertEquals(3,perScene[1]?.size);assertEquals(perScene[1],perScene[2])
            val again=await(queue(BoardJob.ASSET_EXTRACT,project.id,req),SUCCEEDED)
            assertEquals(result.assets,studio.boards.extraction(project.id,again.writingResultId!!).assets)
            assertFailsWith<StudioError> {studio.boards.extraction(other.id,result.id)}
        } }
    }

    @Test fun historyKeepsV1ReorderAndSingleShotRegenerationAreIsolated()=runBlocking<Unit> {
        Rig().use { rig -> with(rig) {
            val (ep,s)=script();studio.writing.approve(WritingKind.SCRIPT,ep,s.id,1)
            await(submit(),SUCCEEDED);val v1=studio.catalog.asset(asset.id).versions.single()
            val req=BoardRequest(s.id,0,listOf(v1.id),2)
            assertFailsWith<StudioError>{queue(BoardJob.SHOT_PLAN,ep,req)}
            studio.catalog.review(asset.id,v1.id,0,true)
            val other=studio.catalog.create(ProjectInput("异项目"))
            val foreign=studio.catalog.createAsset(other.id,AssetInput("外部"))
            await(studio.tasks.enqueue(foreign.id,uid(),request("异项目"),studio.engine.identity,"mock").taskId,SUCCEEDED)
            val fv=studio.catalog.asset(foreign.id).versions.single()
            studio.catalog.review(foreign.id,fv.id,0,true)
            assertFailsWith<StudioError>{queue(BoardJob.SHOT_PLAN,ep,req.copy(assetVersionIds=listOf(fv.id)))}
            val task=await(queue(BoardJob.SHOT_PLAN,ep,req),SUCCEEDED)
            val original=studio.boards.board(ep,task.writingResultId)
            assertEquals(listOf(1,1,2,2),original.shots.map{it.sceneOrdinal})
            assertEquals(60000,original.shots.sumOf{it.durationMs})
            val one=original.shots.first();val others=original.shots.drop(1).map{it.revisionId}
            studio.boards.approve(ep,original.version!!.id,1)
            await(submit("新服装"),SUCCEEDED);val v2=studio.catalog.asset(asset.id).versions.first()
            studio.catalog.review(asset.id,v2.id,1,true)
            assertTrue(studio.boards.board(ep).stale)
            assertTrue(studio.boards.board(ep).shots.all {it.references.single().versionId==v1.id})
            val edit=studio.boards.edit(one.id,ShotEdit(1,action="取出信件",durationMs=4501,assetVersionIds=listOf(v2.id)))
            assertEquals(others,edit.shots.drop(1).map{it.revisionId})
            assertFailsWith<StudioError>{studio.boards.edit(one.id,ShotEdit(1,action="过期修改"))}
            assertFailsWith<StudioError>{studio.boards.reorder(ep,ShotOrder(2,List(4){one.id}))}
            val reordered=studio.boards.reorder(ep,ShotOrder(2,edit.shots.map{it.id}.reversed()))
            val future=queue(BoardJob.SHOT_REGENERATE,one.id,req.copy(expectedRevision=3,assetVersionIds=listOf(v2.id),brief="将信收进衣袋"))
            val regenerated=studio.boards.board(ep,await(future,SUCCEEDED).writingResultId)
            assertEquals(reordered.shots.dropLast(1),regenerated.shots.dropLast(1))
            val newest=regenerated.shots.last()
            assertEquals(4501,newest.durationMs);assertEquals(v2.id,newest.references.single().versionId)
            studio.scheduler.stop()
            val keyframe=studio.tasks.enqueueKeyframe(one.id,uid(),KeyframeRequest(newest.revisionId,wf.id,wf.manifest.version),studio.engine.identity,"mock").taskId
            val inflight=queue(BoardJob.SHOT_REGENERATE,one.id,req.copy(expectedRevision=4))
            studio.boards.edit(one.id,ShotEdit(4,camera="俯拍"))
            restart()
            val preview=await(keyframe,SUCCEEDED)
            assertEquals(listOf(v2.id),preview.snapshot.assetVersionIds)
            assertContains(preview.snapshot.parameters.getValue("prompt").jsonPrimitive.content,"新服装")
            assertEquals("月台",studio.boards.board(ep,original.version!!.id).shots.first().references.single().visualPrompt)
            assertEquals(preview.results,studio.boards.locate(one.id,newest.revisionId).shots.last().previews)
            assertTrue(studio.boards.board(ep).shots.last().previews.isEmpty())
            assertEquals("REVISION_CONFLICT",await(inflight,FAILED).attempts.single().error?.code)
            assertEquals(5,studio.boards.history(ep,Window()).items.size)
            assertEquals(v1.file,studio.boards.board(ep,original.version!!.id).shots.first().references.single().file)
            val sc=original.scenes.first().id
            val state=StateEdit(0,Continuity(location="门外"),Continuity(location="柜台",possessions="信"))
            assertFailsWith<StudioError>{studio.boards.state(sc,"unknown",state)}
            studio.boards.state(sc,"lin",state)
            assertFailsWith<StudioError>{studio.boards.state(sc,"lin",state)}
            assertEquals("信",studio.boards.board(ep).scenes.first().states.single().after.possessions)
            assertTrue(studio.boards.board(ep,original.version!!.id).scenes.first().states.isEmpty())
            assertTrue(studio.boards.board(ep,original.version!!.id).shots.all { it.references.single().needsReview })
            val pinned=await(queue(BoardJob.SHOT_PLAN,ep,req.copy(expectedRevision=5,shotsPerScene=1)),SUCCEEDED)
            val deliberate=studio.boards.board(ep,pinned.writingResultId)
            assertFalse(deliberate.stale)
            assertTrue(deliberate.shots.all { !it.references.single().isCurrent && !it.references.single().needsReview })
            assertEquals("APPROVED",studio.boards.approve(ep,deliberate.version!!.id,6).version!!.approvalStatus)
            assertTrue(studio.boards.board(ep,original.version!!.id).stale)
        } }
    }
}
