package studio.infra

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.engine.*

private fun ResultSet.voice() = VoiceBinding(getString("id"),getString("project_id"),getString("character_key"),
    getInt("revision"),getString("voice_id"),getString("voice_name"),getString("engine_id"),getString("engine_version"),getLong("created_at"))
internal fun Connection.currentVoices(project: String): List<VoiceBinding> {
    getProject(project)
    return rows("""SELECT v.* FROM character_voices v WHERE v.project_id=? AND v.revision=(
        SELECT max(revision) FROM character_voices WHERE project_id=v.project_id AND character_key=v.character_key)
        ORDER BY v.character_key""",project) { voice() }
}
internal fun Connection.bindVoice(project: String,character: String,edit: VoiceEdit,engineId: String,info: TtsInfo): VoiceBinding {
    requireActive(project)
    val outline = head(WritingKind.OUTLINE,project)
    if (outline == null || outline.approvalStatus != "APPROVED") conflict("请先审核当前大纲")
    checkInput(outline.content.getValue("characters").jsonArray.any { it.jsonObject.text("key") == character },"当前大纲没有这个角色")
    val voice = info.voices.find { it.id == edit.voiceId } ?: invalid("请选择引擎已报告的音色")
    val version = voice.modelVersion ?: info.version
    val previous = currentVoices(project).find { it.characterKey == character }
    if ((previous?.revision ?: 0) != edit.expectedRevision) conflict("角色音色已更新")
    if (previous?.voiceId == voice.id && previous.engineId == engineId && previous.engineVersion == version) return previous
    val binding = VoiceBinding(uid(),project,character,edit.expectedRevision+1,voice.id,voice.name,engineId,version,System.currentTimeMillis())
    update("INSERT INTO character_voices VALUES(?,?,?,?,?,?,?,?,?)",binding.id,project,character,binding.revision,
        voice.id,voice.name,engineId,version,binding.createdAt)
    return binding
}
internal data class DubbingContext(val source: ScriptSource,val shot: Shot,val boardId: String)
internal fun Connection.currentShotAudio(shot: String,revision: String): DubbingContext {
    val board = currentForRender(episodeForShot(shot))
    val current = board.shots.find { it.id == shot && it.revisionId == validId(revision) } ?: conflict("需要本集当前镜头修订")
    val version = checkNotNull(board.version)
    return DubbingContext(source(version.scriptRevisionId),current,version.id)
}
private fun DubbingContext.lines() = source.scenes[shot.sceneOrdinal-1].getValue("dialogues").jsonArray
internal fun Connection.speechSnapshot(shot: String,request: SpeechRequest,engineId: String,info: TtsInfo): Snapshot {
    val ctx = currentShotAudio(shot,request.shotRevisionId)
    val lines = ctx.lines()
    checkInput(request.lineOrdinal in 1..lines.size,"本场没有这句对白")
    checkInput(request.speed.isFinite() && request.speed in 0.5..2.0,"语速须在 0.5 至 2 之间")
    val line = lines[request.lineOrdinal-1].jsonObject
    val speaker = line.text("speaker")
    val binding = currentVoices(ctx.source.project).find { it.characterKey == speaker && it.id == validId(request.voiceBindingId) }
        ?: invalid("请选择该角色当前的项目音色")
    if (!info.available || binding.engineId != engineId || binding.engineVersion != info.modelVersionFor(binding.voiceId))
        conflict("音色服务版本或可用音色已变化，请再次绑定")
    val speech = SpeechCall(line.text("text"),binding.voiceId,request.speed,modelVersion=binding.engineVersion)
    val origin = SpeechOrigin(ctx.source.id,request.lineOrdinal,speaker,binding.id)
    val params = JsonObject(json.encodeToJsonElement(request).jsonObject + ("device" to JsonPrimitive(info.device)))
    return Snapshot(null,"1",sha256(sortedJson(json.encodeToJsonElement(speech)).toString().toByteArray()),
        params,listOf(binding.voiceId),binding.engineVersion,shotRevisionId=ctx.shot.revisionId,boardId=ctx.boardId,speech=speech,speechOrigin=origin)
}
internal fun Connection.loadAudio(shot: String,revision: String?): ShotAudio {
    val episode = episodeForShot(shot)
    val head = loadBoard(episode)
    val current = head.shots.find { it.id == shot }
    val rev = revision?.let(::validId) ?: current?.revisionId ?: missing("镜头已移出当前分镜")
    val context = rows("""SELECT s.script_revision_id,s.ordinal,r.duration_ms FROM shot_revisions r JOIN shots sh ON sh.id=r.shot_id
        JOIN scenes s ON s.id=sh.scene_id WHERE r.id=? AND sh.id=?""",rev,shot) { Triple(getString(1),getInt(2),getInt(3)) }
        .singleOrNull() ?: missing("该镜头没有此修订")
    val src = source(context.first)
    val voices = currentVoices(src.project).associateBy { it.characterKey }
    val staleShot = head.stale || current?.revisionId != rev
    val lines = src.scenes[context.second-1].getValue("dialogues").jsonArray.mapIndexed { index,raw ->
        val ordinal = index+1
        val speaker = raw.jsonObject.text("speaker")
        val takes = rows("""SELECT d.*,a.task_id,f.* FROM dialogue_takes d JOIN media_files f ON f.id=d.file_id
            JOIN task_attempts a ON a.id=d.attempt_id WHERE d.shot_revision_id=? AND d.line_ordinal=? ORDER BY d.revision DESC""",rev,ordinal) {
            AudioTake(getString("id"),getInt("revision"),getString("voice_binding_id"),getString("task_id"),toMedia(),
                staleShot || getString("voice_binding_id") != voices[speaker]?.id,getLong("created_at"))
        }
        val selection = rows("SELECT take_id,review_revision FROM dialogue_selections WHERE shot_revision_id=? AND line_ordinal=?",rev,ordinal) {
            getString(1) to getInt(2)
        }.singleOrNull()
        AudioLine(ordinal,speaker,raw.jsonObject.text("text"),raw.jsonObject.text("emotion"),selection?.first,selection?.second ?: 0,takes)
    }
    val duration = lines.sumOf { line -> line.takes.find { it.id == line.selectedTakeId }?.media?.durationMs ?: 0L }
    return ShotAudio(shot,rev,context.third,duration,(duration-context.third).coerceAtLeast(0L),lines)
}
internal fun Connection.chooseTake(shot: String,take: String,expected: Int): ShotAudio {
    val (rev,ordinal) = rows("SELECT shot_revision_id,line_ordinal FROM dialogue_takes WHERE shot_id=? AND id=?",shot,validId(take)) {
        getString(1) to getInt(2)
    }.singleOrNull() ?: missing("该镜头没有此音频")
    currentShotAudio(shot,rev)
    val audio = loadAudio(shot,rev)
    val line = audio.lines.single { it.ordinal == ordinal }
    if (line.reviewRevision != expected) conflict("对白音频已重新选用")
    if (line.takes.single { it.id == take }.stale) conflict("音色绑定已变化，请重新配音")
    update("""INSERT INTO dialogue_selections VALUES(?,?,?,?) ON CONFLICT(shot_revision_id,line_ordinal)
        DO UPDATE SET take_id=excluded.take_id,review_revision=excluded.review_revision""",rev,ordinal,take,expected+1)
    return loadAudio(shot,rev)
}
