package studio.infra

import java.sql.Connection
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.engine.TtsInfo
import kotlin.math.roundToInt

private fun Connection.timelineClips(id: String) = rows("SELECT * FROM timeline_clips WHERE timeline_id=? ORDER BY ordinal",id) {
    val dialogue = rows("SELECT * FROM timeline_dialogues WHERE timeline_id=? AND clip_ordinal=? ORDER BY ordinal",id,getInt("ordinal")) {
        TimelineDialogue(getString("take_id"),getLong("start_ms"),getDouble("gain"),getString("subtitle"))
    }
    TimelineClip(getString("shot_id"),getString("render_version_id"),getLong("source_in_ms"),getLong("duration_ms"),
        getDouble("original_gain"),dialogue,json.decodeFromString(getString("captions_json")))
}
private fun within(start: Long,length: Long,limit: Long) = length > 0 && length <= limit && start in 0..limit-length
private fun validGain(gain: Double) = gain.isFinite() && gain in 0.0..2.0
private fun validCaption(text: String) = text.length <= 1000 && text.none { it.code < 32 && it != '\n' }
private fun Connection.resolveClips(episode: String,boardId: String,clips: List<TimelineClip>): List<ExportClip> {
    val board = loadBoard(episode)
    if (board.version?.id != boardId || board.version?.approvalStatus != "APPROVED" || board.stale)
        conflict("需要本集最新且已审核的有效分镜")
    checkInput(clips.size in 1..256,"时间轴须包含 1 至 256 个片段")
    val shots = board.shots.associateBy { it.id }
    val videos = mutableMapOf<Pair<String,String>,RenderVersion>()
    val audio = mutableMapOf<String,ShotAudio>()
    val resolved = clips.map { clip ->
        val shot = shots[validId(clip.shotId)] ?: invalid("包含不属于本集当前分镜的镜头")
        val version = videos.getOrPut(shot.id to clip.renderVersionId) { renderForTimeline(shot.id,validId(clip.renderVersionId)) }
        if (version.shotRevisionId != shot.revisionId || version.approvalStatus != "APPROVED" || version.stale)
            conflict("请选择当前镜头已审核且有效的视频")
        checkInput(within(clip.sourceInMs,clip.durationMs,version.media.durationMs ?: 0),"裁剪区间超出视频时长")
        checkInput(clip.durationMs >= 42 && validGain(clip.originalGain),"片段至少需要一帧，原声音量应在 0 至 2 之间")
        checkInput(clip.dialogues.size <= 100 && clip.captions.size <= 100,"单片段的音轨或字幕过多")
        for (caption in clip.captions) checkInput(within(caption.startMs,caption.durationMs,clip.durationMs) &&
            caption.text.isNotBlank() && validCaption(caption.text),"字幕文本或起止时间无效")
        val overlays = clip.dialogues.map { overlay ->
            val selection = audio.getOrPut(shot.id) { loadAudio(shot.id,shot.revisionId) }
            val takeId = validId(overlay.takeId)
            val line = selection.lines.find { it.selectedTakeId == takeId } ?: conflict("音轨必须使用本镜头已选用的对白")
            val take = line.takes.single { it.id == takeId }
            if (take.stale) conflict("选用的对白音色已变化")
            checkInput(validGain(overlay.gain) && validCaption(overlay.subtitle),"对白音量或字幕无效")
            checkInput(within(overlay.startMs,take.media.durationMs ?: 0,clip.durationMs),"对白超出片段，请调整时长或重新配音")
            ExportDialogue(take.media,overlay.startMs,overlay.gain,overlay.subtitle)
        }
        ExportClip(version.media,clip.sourceInMs,clip.durationMs,clip.originalGain,overlays,clip.captions)
    }
    checkInput(resolved.sumOf { it.durationMs } <= 1800000,"单集时间轴最多 30 分钟")
    return resolved
}
private fun Connection.timelineRevision(episode: String,id: String): TimelineVersion {
    val row = rows("SELECT * FROM timelines WHERE episode_id=? AND id=?",episode,validId(id)) {
        TimelineVersion(id,episode,getString("board_id"),getInt("revision"),getString("aspect_ratio"),getInt("fps"),
            getLong("duration_ms"),timelineClips(id),false,getLong("created_at"))
    }.singleOrNull() ?: missing("该剧集没有此时间轴修订")
    val stale = try { resolveClips(episode,row.boardId,row.clips); false } catch (e: StudioError) { true }
    return row.copy(stale=stale)
}
internal fun Connection.exportSnapshot(episode: String,request: ExportRequest,engineVersion: String): Snapshot {
    val project = ownerProject(WritingKind.SCRIPT,episode)
    requireActive(project)
    val id = validId(request.timelineRevisionId)
    val ratio = rows("SELECT aspect_ratio FROM timelines WHERE id=? AND episode_id=?",id,episode) { getString(1) }
        .singleOrNull() ?: missing("该剧集没有此时间轴")
    val boardId = rows("SELECT board_id FROM timelines WHERE id=?",id) { getString(1) }.single()
    val short = when(request.resolution) { "480p" -> 480; "1080p" -> 1080; else -> invalid("仅支持 480p 和 1080p") }
    val (x,y) = ratio.split(":").map(String::toInt)
    val scale = short.toDouble()/minOf(x,y)
    fun scaled(n: Int) = (n*scale/2).roundToInt()*2
    val plan = ExportPlan(scaled(x),scaled(y),24,resolveClips(episode,boardId,timelineClips(id)))
    val hash = sha256(sortedJson(json.encodeToJsonElement(plan)).toString().toByteArray())
    return Snapshot(null,"1",hash,json.encodeToJsonElement(request).jsonObject,emptyList(),engineVersion,boardId=boardId,
        export=plan,timelineRevisionId=id,resolution=request.resolution,targetWidth=plan.width,targetHeight=plan.height)
}

class SqlPostProduction(private val db: StudioDb): PostProduction {
    override fun voices(project: String) = db.tx { currentVoices(project) }
    override fun bind(project: String,character: String,edit: VoiceEdit,engineId: String,info: TtsInfo) =
        db.tx { bindVoice(project,character,edit,engineId,info) }
    override fun audio(shot: String,revision: String?) = db.tx { loadAudio(shot,revision) }
    override fun select(shot: String,take: String,expectedRevision: Int) = db.tx { chooseTake(shot,take,expectedRevision) }
    override fun timeline(episode: String,revision: String?) = db.tx {
        ownerProject(WritingKind.SCRIPT,episode)
        val id = revision ?: rows("SELECT id FROM timelines WHERE episode_id=? ORDER BY revision DESC LIMIT 1",episode) { getString(1) }.singleOrNull()
        TimelineView(episode,id?.let { timelineRevision(episode,it) })
    }
    override fun history(episode: String,window: Window) = db.tx {
        ownerProject(WritingKind.SCRIPT,episode)
        window.page(rows("SELECT rowid,id FROM timelines WHERE episode_id=? AND rowid<? ORDER BY rowid DESC LIMIT ?",episode,window.before,window.limit+1) {
            getLong(1) to timelineRevision(episode,getString(2))
        })
    }
    override fun save(episode: String,edit: TimelineEdit) = db.tx {
        val project = ownerProject(WritingKind.SCRIPT,episode)
        requireActive(project)
        val prior = rows("SELECT coalesce(max(revision),0) FROM timelines WHERE episode_id=?",episode) { getInt(1) }.single()
        if (prior != edit.expectedRevision) conflict("时间轴已更新，请刷新")
        resolveClips(episode,validId(edit.boardId),edit.clips)
        val id = uid()
        val aspect = getProject(project).aspectRatio
        val duration = edit.clips.sumOf { it.durationMs }
        val now = System.currentTimeMillis()
        update("INSERT INTO timelines VALUES(?,?,?,?,?,?,?,?)",id,episode,edit.boardId,prior+1,aspect,24,duration,now)
        for ((index,clip) in edit.clips.withIndex()) {
            update("INSERT INTO timeline_clips VALUES(?,?,?,?,?,?,?,?)",id,index,clip.shotId,clip.renderVersionId,clip.sourceInMs,
                clip.durationMs,clip.originalGain,json.encodeToString(clip.captions))
            for ((n,overlay) in clip.dialogues.withIndex())
                update("INSERT INTO timeline_dialogues VALUES(?,?,?,?,?,?,?)",id,index,n,overlay.takeId,overlay.startMs,overlay.gain,overlay.subtitle)
        }
        TimelineVersion(id,episode,edit.boardId,prior+1,aspect,24,duration,edit.clips,false,now)
    }
    override fun exports(episode: String,window: Window) = db.tx {
        ownerProject(WritingKind.SCRIPT,episode)
        window.page(rows("""SELECT e.rowid AS cursor,e.*,a.task_id,f.* FROM episode_exports e JOIN media_files f ON f.id=e.file_id
            JOIN task_attempts a ON a.id=e.attempt_id WHERE e.episode_id=? AND e.rowid<? ORDER BY e.rowid DESC LIMIT ?""",
            episode,window.before,window.limit+1) {
            getLong("cursor") to ExportVersion(getString("id"),getString("timeline_id"),getString("task_id"),getString("resolution"),toMedia(),getLong("created_at"))
        })
    }
}
