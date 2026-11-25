package studio.infra

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*

internal data class ScriptSource(val project: String, val episode: String, val id: String, val revision: Int,
    val script: JsonObject, val outline: JsonObject) {
    val scenes get() = script.getValue("scenes").jsonArray.map { it.jsonObject }
    fun entities(group: String) = outline.getValue(group).jsonArray.map { it.jsonObject }.associateBy { it.text("key") }
}
internal fun JsonObject.text(field: String) = getValue(field).jsonPrimitive.content
internal fun Connection.source(id: String) = rows("""SELECT s.project_id,s.episode_id,s.revision,s.content_json,o.content_json
    FROM script_revisions s JOIN story_revisions o ON o.id=s.story_revision_id WHERE s.id=?""",validId(id)) {
    ScriptSource(getString(1),getString(2),id,getInt(3),json.parseToJsonElement(getString(4)).jsonObject,
        json.parseToJsonElement(getString(5)).jsonObject)
}.singleOrNull() ?: missing("剧本修订不存在")
internal fun Connection.currentScript(src: ScriptSource): Boolean {
    val latest = head(WritingKind.SCRIPT,src.episode)
    return latest?.id == src.id && latest.approvalStatus == "APPROVED" && !latest.stale
}
internal fun Connection.requireScript(src: ScriptSource) {
    requireActive(src.project)
    if (!currentScript(src)) conflict("需要最新且已审核的有效剧本")
}
internal fun Connection.sceneIds(src: ScriptSource): List<String> {
    for (i in src.scenes.indices) update("""INSERT INTO scenes(id,project_id,episode_id,script_revision_id,ordinal) VALUES(?,?,?,?,?)
        ON CONFLICT(script_revision_id,ordinal) DO NOTHING""",uid(),src.project,src.episode,src.id,i+1)
    return rows("SELECT id FROM scenes WHERE script_revision_id=? ORDER BY ordinal",src.id) { getString(1) }
}
private fun ResultSet.summary() = BoardSummary(getString("id"),getInt("revision"),getString("script_revision_id"),
    getString("approval_status"),getLong("created_at"))
internal fun Connection.boardHead(episode: String) = rows("SELECT * FROM storyboards WHERE episode_id=? ORDER BY revision DESC LIMIT 1",episode) { summary() }.singleOrNull()
internal fun Connection.expectBoard(episode: String,expected: Int): BoardSummary? {
    val head = boardHead(episode)
    if ((head?.revision ?: 0) != expected) conflict("分镜已有新版本，请刷新")
    return head
}
private val referenceSql = """SELECT a.id AS asset_id,a.name,a.type,a.description,a.active_version_id,v.id AS version_id,
    v.approval_status,v.revision,v.file_id,CASE WHEN json_valid(v.metadata_json) THEN json_extract(v.metadata_json,'$.parameters.prompt') END AS visual_prompt,f.* FROM asset_versions v JOIN assets a ON a.id=v.asset_id JOIN media_files f ON f.id=v.file_id"""
private fun ResultSet.reference(bound: Boolean = false): Reference {
    val active=getString("active_version_id")
    val version=getString("version_id")
    return Reference(getString("asset_id"),version,getString("name"),getString("type"),getInt("revision"),getString("description"),
        toMedia(),active==version,getString("visual_prompt"),active,bound && active!=version && active!=getString("default_version_id"))
}
internal fun Connection.approvedReferences(project: String,ids: List<String>): List<Reference> {
    checkInput(ids.size <= 32 && ids.distinct().size == ids.size,"参考版本过多或重复")
    val refs = ids.map { id ->
        rows("$referenceSql WHERE a.project_id=? AND v.id=? AND v.approval_status='APPROVED'",project,validId(id)) { reference() }
            .singleOrNull() ?: invalid("参考必须是本项目已审核的资产版本")
    }
    checkInput(refs.distinctBy { it.assetId }.size==refs.size,"同一资产只能绑定一个版本")
    return refs
}
internal fun Connection.episodeForShot(id: String) = rows("""SELECT s.episode_id FROM shots sh JOIN scenes s ON s.id=sh.scene_id
    WHERE sh.id=?""",validId(id)) { getString(1) }.singleOrNull() ?: missing("镜头不存在")
private fun Connection.states(scene: String) = rows("SELECT * FROM character_states WHERE scene_id=? ORDER BY character_key",scene) {
    CharacterState(getString("character_key"),getInt("revision"),json.decodeFromString(getString("before_json")),json.decodeFromString(getString("after_json")))
}
internal fun Connection.boardScenes(src: ScriptSource): List<BoardScene> {
    val sites=src.entities("locations"); val people=src.entities("characters")
    return rows("SELECT id,ordinal FROM scenes WHERE script_revision_id=? ORDER BY ordinal",src.id) { getString(1) to getInt(2) }.map { (id,n) ->
        val row=src.scenes[n-1]
        val cast=row.getValue("characters").jsonArray.associate { key -> key.jsonPrimitive.content.let { it to people.getValue(it).text("name") } }
        BoardScene(id,n,row.text("action"),sites.getValue(row.text("locationKey")).text("name"),cast,states(id))
    }
}
internal fun Connection.loadBoard(episode: String, versionId: String? = null): Board {
    ownerProject(WritingKind.SCRIPT,episode)
    val v=if (versionId==null) boardHead(episode) else rows("SELECT * FROM storyboards WHERE episode_id=? AND id=?",episode,validId(versionId)) { summary() }
        .singleOrNull() ?: missing("该剧集没有此分镜版本")
    if (v==null) return Board(episode,null,false,emptyList(),emptyList())
    val src=source(v.scriptRevisionId)
    val shots=rows("""SELECT r.*,sh.scene_id,s.ordinal AS scene_ordinal,b.ordinal AS position,fr.first_file_id,fr.last_file_id,fr.review_revision
        FROM storyboard_shots b JOIN shot_revisions r ON r.id=b.shot_revision_id JOIN shots sh ON sh.id=r.shot_id
        JOIN scenes s ON s.id=sh.scene_id LEFT JOIN shot_frame_reviews fr ON fr.shot_revision_id=r.id WHERE b.board_id=? ORDER BY b.ordinal""",v.id) {
        val rev=getString("id")
        val refs=rows("""SELECT r.*,sa.default_version_id FROM ($referenceSql) r JOIN shot_assets sa ON sa.asset_version_id=r.version_id
            WHERE sa.shot_revision_id=? ORDER BY sa.ordinal""",rev) { reference(bound=true) }
        val previews=rows("""SELECT p.file_id,f.* FROM shot_previews p JOIN media_files f ON f.id=p.file_id WHERE p.shot_revision_id=?
            ORDER BY p.rowid DESC""",rev) { toMedia() }
        Shot(getString("shot_id"),rev,getInt("revision"),getString("scene_id"),getInt("scene_ordinal"),getInt("position"),
            getString("framing"),getString("camera"),getString("action"),getInt("duration_ms"),refs,previews,
            getString("first_file_id"),getString("last_file_id"),getInt("review_revision"))
    }
    val live=boardScenes(src)
    val frozen=rows("SELECT scene_id,states_json FROM storyboard_states WHERE board_id=?",v.id) {
        getString(1) to json.decodeFromString<List<CharacterState>>(getString(2))
    }.toMap()
    val historical=live.map { it.copy(states=frozen[it.id].orEmpty()) }
    val stale=!currentScript(src) || live!=historical || shots.any { shot -> shot.references.any { it.needsReview } }
    return Board(episode,v,stale,if (versionId==null) live else historical,shots)
}
internal data class ShotVersion(val shot: String,val revision: String)
internal fun Connection.addShotRevision(project: String,shot: String,framing: String,camera: String,action: String,duration: Int,refs: List<Reference>,attempt: String? = null,
    defaults: Map<String,String?> = refs.associate { it.assetId to it.currentVersionId }): ShotVersion {
    checkInput(framing.isNotBlank() && framing.length<=80 && camera.isNotBlank() && camera.length<=160 &&
        action.isNotBlank() && action.length<=800 && duration in 1..600000,"镜头景别、机位、动作或时长无效")
    val id=uid()
    update("""INSERT INTO shot_revisions(id,shot_id,revision,framing,camera,action,duration_ms,source_attempt_id,created_at)
        SELECT ?,?,coalesce(max(revision),0)+1,?,?,?,?,?,? FROM shot_revisions WHERE shot_id=?""",id,shot,framing,camera,action,duration,attempt,System.currentTimeMillis(),shot)
    for ((index,ref) in refs.withIndex()) update(
        "INSERT INTO shot_assets VALUES(?,?,?,?,?,?,?)",id,index,project,ref.assetId,ref.versionId,ref.type,defaults.getValue(ref.assetId))
    update("UPDATE shots SET active_revision_id=? WHERE id=?",id,shot)
    return ShotVersion(shot,id)
}
internal fun Connection.publishBoard(src: ScriptSource, prior: Int, entries: List<ShotVersion>, attempt: String? = null): String {
    val id=uid()
    update("INSERT INTO storyboards VALUES(?,?,?,?,?,?,?,?)",id,src.project,src.episode,src.id,prior+1,
        if(attempt==null) "DRAFT" else "REVIEW_REQUIRED",attempt,System.currentTimeMillis())
    for (scene in boardScenes(src)) update("INSERT INTO storyboard_states VALUES(?,?,?)",id,scene.id,json.encodeToString(scene.states))
    for ((i,row) in entries.withIndex()) update("INSERT INTO storyboard_shots VALUES(?,?,?,?)",id,i+1,row.shot,row.revision)
    return id
}
internal fun Board.replaceShot(shot: ShotVersion) = shots.map { if (it.id==shot.shot) shot else ShotVersion(it.id,it.revisionId) }

class SqlStoryboards(private val db: StudioDb): Storyboards {
    override fun approveFrame(shot: String,file: String,review: FrameReview) = db.tx { selectFrame(shot,file,review) }
    override fun renders(shot: String,revision: String?,window: Window) = db.tx { listRenders(shot,revision,window) }
    override fun reviewRender(shot: String,version: String,expectedRevision: Int,approve: Boolean) =
        db.tx { decideRender(shot,version,expectedRevision,approve) }
    override fun board(episode: String,version: String?) = db.tx { loadBoard(episode,version) }
    override fun locate(shot: String,revision: String?) = db.tx {
        val episode=episodeForShot(shot)
        val id=rows("""SELECT b.id FROM storyboards b JOIN storyboard_shots s ON s.board_id=b.id
            WHERE s.shot_id=? AND (? IS NULL OR s.shot_revision_id=?) ORDER BY b.revision DESC LIMIT 1""",shot,revision,revision) { getString(1) }
            .singleOrNull() ?: missing("镜头版本不存在")
        loadBoard(episode,id)
    }
    override fun history(episode: String,window: Window) = db.tx {
        ownerProject(WritingKind.SCRIPT,episode)
        window.page(rows("SELECT rowid,* FROM storyboards WHERE episode_id=? AND rowid<? ORDER BY rowid DESC LIMIT ?",episode,window.before,window.limit+1) { getLong("rowid") to summary() })
    }
    override fun extraction(project: String,id: String) = db.tx {
        val script=rows("SELECT script_revision_id FROM asset_extractions WHERE project_id=? AND id=?",project,id) { getString(1) }.singleOrNull() ?: missing("提取记录不存在")
        Extraction(id,script,rows("""SELECT s.ordinal,a.asset_id FROM extracted_assets a JOIN scenes s ON s.id=a.scene_id
            WHERE a.extraction_id=? ORDER BY s.ordinal,a.rowid""",id) { ExtractedAsset(getInt(1),getString(2)) })
    }
    override fun edit(shot: String,request: ShotEdit) = db.tx {
        val episode=episodeForShot(shot)
        val head=expectBoard(episode,request.expectedRevision) ?: missing("分镜不存在")
        val src=source(head.scriptRevisionId); requireScript(src)
        val board=loadBoard(episode)
        val old=board.shots.find { it.id==shot } ?: conflict("镜头已移出当前分镜")
        val refs=approvedReferences(src.project,request.assetVersionIds ?: old.references.map { it.versionId })
        val defaults=if (request.assetVersionIds!=null) refs.associate { it.assetId to it.currentVersionId } else
            rows("SELECT asset_id,default_version_id FROM shot_assets WHERE shot_revision_id=?",old.revisionId) { getString(1) to getString(2) }.toMap()
        val changed=addShotRevision(src.project,shot,request.framing ?: old.framing,request.camera ?: old.camera,
            request.action ?: old.action,request.durationMs ?: old.durationMs,refs,defaults=defaults)
        loadBoard(episode,publishBoard(src,head.revision,board.replaceShot(changed)))
    }
    override fun reorder(episode: String,request: ShotOrder) = db.tx {
        val head=expectBoard(episode,request.expectedRevision) ?: missing("分镜不存在")
        val src=source(head.scriptRevisionId); requireScript(src)
        val board=loadBoard(episode);val byId=board.shots.associateBy { it.id }
        checkInput(request.shotIds.size==byId.size && request.shotIds.toSet()==byId.keys,"排序必须恰好包含本集当前的所有镜头")
        if (request.shotIds==board.shots.map { it.id }) return@tx board
        loadBoard(episode,publishBoard(src,head.revision,request.shotIds.map { ShotVersion(it,byId.getValue(it).revisionId) }))
    }
    override fun approve(episode: String,board: String,expectedRevision: Int) = db.tx {
        val head=expectBoard(episode,expectedRevision) ?: missing("分镜不存在")
        requireScript(source(head.scriptRevisionId))
        if (head.id!=board || loadBoard(episode).stale) conflict("只可审核当前且参考资料有效的分镜")
        update("UPDATE storyboards SET approval_status='APPROVED' WHERE id=?",board)
        loadBoard(episode)
    }
    override fun state(scene: String,character: String,request: StateEdit) = db.tx {
        val (script,n)=rows("SELECT script_revision_id,ordinal FROM scenes WHERE id=?",scene) { getString(1) to getInt(2) }.singleOrNull() ?: missing("场次不存在")
        val src=source(script);requireScript(src)
        checkInput(src.scenes[n-1].getValue("characters").jsonArray.contains(JsonPrimitive(character)),"角色未在本场出场")
        val prior=states(scene).find { it.characterKey==character }?.revision ?: 0
        if (prior!=request.expectedRevision) conflict("场次人物状态已修改")
        val docs=listOf(request.before,request.after).map { json.encodeToJsonElement(it).jsonObject }
        checkInput(docs.all { d -> d.values.all { it.jsonPrimitive.content.length<=500 } },"状态字段最多 500 字")
        update("""INSERT INTO character_states VALUES(?,?,?,?,?) ON CONFLICT(scene_id,character_key) DO UPDATE SET
            revision=excluded.revision,before_json=excluded.before_json,after_json=excluded.after_json""",scene,character,prior+1,docs[0].toString(),docs[1].toString())
        CharacterState(character,prior+1,request.before,request.after)
    }
}
