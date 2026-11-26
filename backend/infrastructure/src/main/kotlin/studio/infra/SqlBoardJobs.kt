package studio.infra

import java.sql.Connection
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*

internal fun Connection.boardProject(kind: BoardJob, target: String): String = when (kind) {
    BoardJob.ASSET_EXTRACT -> getProject(target).id
    BoardJob.SHOT_PLAN -> ownerProject(WritingKind.SCRIPT,target)
    BoardJob.SHOT_REGENERATE -> ownerProject(WritingKind.SCRIPT,episodeForShot(target))
}

internal data class BoardContext(val source: ScriptSource, val selected: Shot?, val references: List<Reference>, val input: JsonObject)

private fun Shot.promptContext() = buildJsonObject {
    put("sceneOrdinal", sceneOrdinal)
    put("ordinal", ordinal)
    put("framing", framing)
    put("camera", camera)
    put("action", action)
    put("durationMs", durationMs)
}

internal fun Connection.boardContext(kind: BoardJob, target: String, request: BoardRequest): BoardContext {
    val src=source(request.scriptRevisionId)
    checkInput(boardProject(kind,target)==src.project,"目标与剧本必须属于同一项目")
    requireScript(src)
    checkInput(request.brief.length<=4000 && request.shotsPerScene in 1..8,"创作说明或每场镜头数超出范围")
    var selected: Shot?=null
    var previousShot: Shot? = null
    var nextShot: Shot? = null
    if (kind==BoardJob.ASSET_EXTRACT) {
        if (src.revision!=request.expectedRevision) conflict("剧本已更新")
        checkInput(request.assetVersionIds.isEmpty(),"提取资产无需提供参考版本")
    } else {
        val episode=if (kind==BoardJob.SHOT_PLAN) target else episodeForShot(target)
        checkInput(episode==src.episode,"剧本属于其他剧集")
        val head=expectBoard(episode,request.expectedRevision)
        if (kind==BoardJob.SHOT_REGENERATE) {
            if (head?.scriptRevisionId!=src.id) conflict("当前分镜的上游剧本已变化")
            val ordered = loadBoard(episode).shots
            val index = ordered.indexOfFirst { it.id == target }
            val current = ordered.getOrNull(index) ?: conflict("镜头已移出当前分镜")
            selected = current
            previousShot = ordered.getOrNull(index - 1)?.takeIf { it.sceneId == current.sceneId }
            nextShot = ordered.getOrNull(index + 1)?.takeIf { it.sceneId == current.sceneId }
        }
    }
    val refs=approvedReferences(src.project, if (kind==BoardJob.SHOT_REGENERATE && request.assetVersionIds.isEmpty())
        selected!!.references.map { it.versionId } else request.assetVersionIds)
    if(kind==BoardJob.SHOT_PLAN) checkInput(refs.isNotEmpty(),"拆镜前请先审核并选择资产版本")
    sceneIds(src)
    val input=buildJsonObject {
        put("request",json.encodeToJsonElement(request)); put("script",src.script); put("outline",src.outline)
        put("sceneContinuity",json.encodeToJsonElement(boardScenes(src)))
        put("references",buildJsonArray { for(ref in refs) addJsonObject {
            put("name",ref.name); put("type",ref.type); put("version",ref.revision); put("description",ref.description); ref.visualPrompt?.let { put("visualPrompt",it.take(500)) }
        } })
        selected?.let { put("shot", it.promptContext()) }
        previousShot?.let { put("previousShot", it.promptContext()) }
        nextShot?.let { put("nextShot", it.promptContext()) }
    }
    return BoardContext(src,selected,refs,input)
}

private fun Connection.extractedAsset(project: String,type: String,key: String,name: String,description: String): String {
    checkInput(name.isNotBlank() && description.isNotBlank(),"提取结果缺少资产名或描述")
    rows("SELECT asset_id FROM asset_keys WHERE project_id=? AND type=? AND source_key=?",project,type,key) { getString(1) }
        .singleOrNull()?.let { return it }
    val matches=rows("SELECT id FROM assets WHERE project_id=? AND type=? AND name=? AND NOT EXISTS (SELECT 1 FROM asset_keys k WHERE k.asset_id=assets.id) ORDER BY rowid",project,type,name) { getString(1) }
    val asset=matches.singleOrNull() ?: uid().also {
        update("INSERT INTO assets(id,project_id,type,name,description,created_at) VALUES(?,?,?,?,?,?)",it,project,type,name,description,System.currentTimeMillis())
    }
    update("INSERT INTO asset_keys VALUES(?,?,?,?)",project,type,key,asset)
    return asset
}

private fun Connection.publishExtraction(src: ScriptSource,content: JsonObject,attempt: String): String {
    val id=uid()
    update("INSERT INTO asset_extractions VALUES(?,?,?,?,?)",id,src.project,src.id,attempt,System.currentTimeMillis())
    val locations=src.entities("locations"); val cast=src.entities("characters")
    val ids=sceneIds(src)
    for ((i,scene) in src.scenes.withIndex()) {
        fun link(type: String,key: String,entity: JsonObject) {
            val asset=extractedAsset(src.project,type,key,entity.text("name").trim(),entity.text("description").trim())
            update("INSERT INTO extracted_assets VALUES(?,?,?) ON CONFLICT DO NOTHING",id,ids[i],asset)
        }
        val site=scene.text("locationKey"); link("SCENE",site,locations.getValue(site))
        for (actor in scene.getValue("characters").jsonArray) {
            val key=actor.jsonPrimitive.content; link("CHARACTER",key,cast.getValue(key))
        }
        for (item in content.getValue("scenes").jsonArray[i].jsonObject.getValue("items").jsonArray) {
            val entity=item.jsonObject
            link(entity.text("type"),entity.text("name").trim().lowercase(),entity)
        }
    }
    return id
}

internal fun Connection.publishBoardJob(task: Task,kind: BoardJob,content: JsonObject): String {
    val request=json.decodeFromJsonElement<BoardRequest>(task.snapshot.parameters.getValue("request"))
    val ctx=boardContext(kind,task.targetId,request)
    if (kind!=BoardJob.ASSET_EXTRACT && ctx.input["sceneContinuity"]!=task.snapshot.parameters["sceneContinuity"])
        conflict("生成期间场次人物状态已修改")
    val src=ctx.source; val attempt=task.attempts.last().id
    val refs=approvedReferences(src.project,task.snapshot.assetVersionIds)
    // Jobs queued before migration 006 recorded only the pinned versions.
    val defaults=task.snapshot.referenceDefaults.ifEmpty { refs.associate { it.assetId to it.versionId } }
    return when(kind) {
        BoardJob.ASSET_EXTRACT -> publishExtraction(src,content,attempt)
        BoardJob.SHOT_REGENERATE -> {
            val old=checkNotNull(ctx.selected)
            if(old.revisionId!=task.snapshot.shotRevisionId) conflict("待重做的镜头已修改")
            val row=addShotRevision(src.project,old.id,content.text("framing"),content.text("camera"),content.text("action"),old.durationMs,refs,attempt,defaults)
            publishBoard(src,request.expectedRevision,loadBoard(src.episode).replaceShot(row),attempt)
        }
        BoardJob.SHOT_PLAN -> {
            val sceneIds=sceneIds(src)
            val used=rows("""SELECT DISTINCT a.asset_id,a.scene_id FROM extracted_assets a JOIN asset_extractions e ON e.id=a.extraction_id
                WHERE e.script_revision_id=?""",src.id) { getString(1) to getString(2) }.groupBy({it.first},{it.second})
            val entries=mutableListOf<ShotVersion>()
            for ((i,scene) in content.getValue("scenes").jsonArray.withIndex()) {
                val items=scene.jsonObject.getValue("shots").jsonArray
                val duration=src.scenes[i].getValue("durationMs").jsonPrimitive.int
                val selected=refs.filter { used[it.assetId]?.contains(sceneIds[i]) != false }
                for ((j,item) in items.withIndex()) {
                    val shot=uid(); val fields=item.jsonObject
                    update("INSERT INTO shots(id,project_id,scene_id) VALUES(?,?,?)",shot,src.project,sceneIds[i])
                    entries+=addShotRevision(src.project,shot,fields.text("framing"),fields.text("camera"),fields.text("action"),
                        duration/items.size + if(j<duration%items.size) 1 else 0,selected,attempt,defaults)
                }
            }
            publishBoard(src,request.expectedRevision,entries,attempt)
        }
    }
}
