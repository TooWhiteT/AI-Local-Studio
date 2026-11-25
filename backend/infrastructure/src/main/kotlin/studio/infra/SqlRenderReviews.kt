package studio.infra

import java.sql.Connection
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*

internal fun Connection.currentForRender(episode: String): Board {
    val board = loadBoard(episode)
    val version = board.version ?: conflict("需要先生成并审核分镜")
    requireActive(ownerProject(WritingKind.SCRIPT,episode))
    if (version.approvalStatus != "APPROVED" || board.stale) conflict("需要当前已审核且有效的分镜")
    return board
}

internal fun Connection.selectFrame(shot: String, file: String, review: FrameReview): Shot {
    checkInput(review.role in setOf("first","last"), "帧角色必须为 first 或 last")
    val episode = episodeForShot(shot)
    val board = loadBoard(episode)
    requireScript(source(board.version?.scriptRevisionId ?: missing("分镜不存在")))
    val current = board.shots.find { it.id == shot && it.revisionId == validId(review.shotRevisionId) }
        ?: conflict("镜头修订已变化")
    if (current.frameReviewRevision != review.expectedRevision) conflict("帧审核已变化，请刷新")
    checkInput(current.previews.any { it.id == file && it.mime in setOf("image/png","image/jpeg") }, "请选择该镜头修订的预览图")
    val first = if (review.role == "first") file else current.approvedPreviewId
    val last = if (review.role == "last") file else current.approvedLastFrameId
    val next = current.frameReviewRevision + 1
    update("""INSERT INTO shot_frame_reviews VALUES(?,?,?,?) ON CONFLICT(shot_revision_id)
        DO UPDATE SET first_file_id=excluded.first_file_id,last_file_id=excluded.last_file_id,review_revision=excluded.review_revision""",
        current.revisionId,first,last,next)
    return current.copy(approvedPreviewId=first, approvedLastFrameId=last, frameReviewRevision=next)
}

private data class ShotFreshness(val current: Shot?, val script: String?, val liveStates: List<CharacterState>)
private fun Connection.freshness(shot: String): ShotFreshness {
    val board = loadBoard(episodeForShot(shot))
    val current = board.shots.find { it.id == shot }
    val script = board.version?.scriptRevisionId?.takeIf { currentScript(source(it)) }
    return ShotFreshness(current,script,board.scenes.find { it.id == current?.sceneId }?.states.orEmpty())
}
private const val versionSql = """SELECT v.rowid AS rid,v.*,f.relative_path,f.sha256,f.mime,f.size_bytes,f.width,f.height,f.duration_ms,
    f.frame_count,f.fps,a.task_id,b.script_revision_id,cs.states_json FROM shot_render_versions v JOIN media_files f ON f.id=v.file_id
    JOIN task_attempts a ON a.id=v.attempt_id JOIN storyboards b ON b.id=v.board_id JOIN shots sh ON sh.id=v.shot_id
    LEFT JOIN storyboard_states cs ON cs.board_id=b.id AND cs.scene_id=sh.scene_id"""
private fun java.sql.ResultSet.version(fresh: ShotFreshness): RenderVersion {
    val rev = getString("shot_revision_id")
    val first = getString("first_file_id")
    val last = getString("last_file_id")
    val reference = getString("reference_version_id")
    val states = getString("states_json")?.let { json.decodeFromString<List<CharacterState>>(it) }.orEmpty()
    val current = fresh.current
    val stale = current == null || current.revisionId != rev || (first != null && current.approvedPreviewId != first) ||
        (reference != null && current.references.none { it.versionId == reference }) ||
        (last != null && current.approvedLastFrameId != last) || current.references.any { it.needsReview } ||
        fresh.script != getString("script_revision_id") || states != fresh.liveStates
    return RenderVersion(getString("id"),getString("shot_id"),rev,getInt("revision"),first,last,reference,getString("task_id"),
        toMedia(),getString("approval_status"),getInt("review_revision"),stale,getLong("created_at"))
}
internal fun Connection.listRenders(shot: String, revision: String?, window: Window): Page<RenderVersion> {
    val fresh = freshness(shot)
    if (revision != null && rows("SELECT 1 FROM shot_revisions WHERE id=? AND shot_id=?",validId(revision),shot) { getInt(1) }.isEmpty())
        missing("该镜头没有此修订")
    return window.page(rows("""$versionSql WHERE v.shot_id=? AND (? IS NULL OR v.shot_revision_id=?) AND v.rowid<?
        ORDER BY v.rowid DESC LIMIT ?""",shot,revision,revision,window.before,window.limit+1) {
        getLong("rid") to version(fresh)
    })
}
internal fun Connection.decideRender(shot: String, id: String, expected: Int, approve: Boolean): RenderVersion {
    val episode = episodeForShot(shot)
    requireActive(ownerProject(WritingKind.SCRIPT,episode))
    val fresh = freshness(shot)
    val version = rows("$versionSql WHERE v.id=? AND v.shot_id=?",id,shot) { version(fresh) }
        .singleOrNull() ?: missing("该镜头没有此渲染版本")
    if (version.reviewRevision != expected) conflict("渲染审核已更新，请刷新")
    if (version.approvalStatus != "REVIEW_REQUIRED") conflict("该视频已完成审核")
    if (approve) {
        currentForRender(episode)
        if (version.stale) conflict("已过期的视频需要重新渲染")
    }
    val status = if (approve) "APPROVED" else "ARCHIVED"
    update("UPDATE shot_render_versions SET approval_status=?,review_revision=review_revision+1 WHERE id=?",status,id)
    return version.copy(approvalStatus=status,reviewRevision=expected+1)
}

internal fun Connection.renderForTimeline(shot: String,id: String): RenderVersion {
    val current = freshness(shot)
    return rows("$versionSql WHERE v.shot_id=? AND v.id=?",shot,id) { version(current) }
        .singleOrNull() ?: missing("该镜头没有此渲染版本")
}
