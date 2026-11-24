package studio.infra

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*

private val WritingKind.table get() = when (this) {
    WritingKind.OUTLINE -> "story_revisions"
    WritingKind.EPISODE_PLAN -> "episode_plans"
    WritingKind.SCRIPT -> "script_revisions"
}
private val WritingKind.owner get() = if (this == WritingKind.SCRIPT) "episode_id" else "project_id"
internal fun Connection.ownerProject(kind: WritingKind, target: String): String {
    val table = if (kind == WritingKind.SCRIPT) "episodes" else "projects"
    val column = if (kind == WritingKind.SCRIPT) "project_id" else "id"
    return rows("SELECT $column FROM $table WHERE id=?", validId(target)) { getString(1) }.firstOrNull() ?: missing("项目或剧集不存在")
}
private fun ResultSet.revision() = WritingRevision(getString("id"),getInt("revision"),
    json.parseToJsonElement(getString("content_json")).jsonObject,getString("approval_status"),getBoolean("stale"),
    getString("story"),getString("plan"),getString("source_attempt_id"),getLong("created_at"))

private fun selectRevisions(kind: WritingKind): String {
    val story = if (kind == WritingKind.OUTLINE) "NULL" else "r.story_revision_id"
    val plan = if (kind == WritingKind.SCRIPT) "r.plan_id" else "NULL"
    val oldStory = "$story <> (SELECT id FROM story_revisions WHERE project_id=r.project_id ORDER BY revision DESC LIMIT 1)"
    val oldPlan = "$plan <> (SELECT id FROM episode_plans WHERE project_id=r.project_id ORDER BY revision DESC LIMIT 1)"
    return "SELECT r.rowid AS cursor,r.*, $story AS story, $plan AS plan, (coalesce($oldStory,0) OR coalesce($oldPlan,0)) AS stale FROM ${kind.table} r"
}
private fun Connection.head(kind: WritingKind, target: String) =
    rows("${selectRevisions(kind)} WHERE r.${kind.owner}=? ORDER BY r.revision DESC LIMIT 1",target) { revision() }.firstOrNull()

internal data class WritingContext(val project: String, val previous: WritingRevision?, val story: WritingRevision?,
    val plan: WritingRevision?, val episode: JsonObject?)
internal fun Connection.writingContext(kind: WritingKind, target: String, expected: Int, storyId: String?, planId: String?): WritingContext {
    val project = ownerProject(kind,target)
    requireActive(project)
    val previous = head(kind,target)
    if ((previous?.revision ?: 0) != expected) conflict("已有新修订，请重新载入后再保存")
    if (kind == WritingKind.OUTLINE) {
        checkInput(storyId == null && planId == null,"大纲不接受上游引用")
        return WritingContext(project,previous,null,null,null)
    }
    val story = head(WritingKind.OUTLINE,project)
    if (story == null || story.approvalStatus != "APPROVED" || story.id != storyId) conflict("需要当前项目最新且已审核的大纲")
    if (kind == WritingKind.EPISODE_PLAN) {
        checkInput(planId == null,"分集规划不接受其他规划引用")
        return WritingContext(project,previous,story,null,null)
    }
    val plan = head(WritingKind.EPISODE_PLAN,project)
    if (plan == null || plan.stale || plan.id != planId) conflict("分集规划已变更，请使用当前规划")
    val ordinal = rows("SELECT ordinal FROM episodes WHERE id=?",target) { getInt(1) }.single()
    val episode = plan.content.getValue("episodes").jsonArray.getOrNull(ordinal - 1)?.jsonObject ?: conflict("该集已移出当前规划")
    return WritingContext(project,previous,story,plan,episode)
}
internal fun Connection.publishWriting(kind: WritingKind, target: String, edit: WritingEdit, attempt: String?, count: Int? = null): WritingRevision {
    val ctx = writingContext(kind,target,edit.expectedRevision,edit.storyRevisionId,edit.planId)
    validateWriting(kind,edit.content,ctx.story?.content,count)
    val id = uid()
    val columns = mutableListOf("id","project_id","revision","content_json","approval_status","source_attempt_id","created_at")
    val values = mutableListOf<Any?>(id,ctx.project,edit.expectedRevision+1,edit.content.toString(),
        if (attempt == null) "DRAFT" else "REVIEW_REQUIRED",attempt,System.currentTimeMillis())
    if (ctx.story != null) { columns += "story_revision_id"; values += ctx.story.id }
    if (ctx.plan != null) { columns += listOf("plan_id","episode_id"); values.addAll(listOf(ctx.plan.id,target)) }
    update("INSERT INTO ${kind.table}(${columns.joinToString()}) VALUES(${columns.joinToString { "?" }})",*values.toTypedArray())
    if (kind == WritingKind.EPISODE_PLAN) {
        for (i in edit.content.getValue("episodes").jsonArray.indices)
            update("INSERT INTO episodes(id,project_id,ordinal) VALUES(?,?,?) ON CONFLICT(project_id,ordinal) DO NOTHING",uid(),ctx.project,i+1)
    }
    return checkNotNull(head(kind,target))
}

class SqlWriting(private val db: StudioDb) : WritingStore {
    override fun revisions(kind: WritingKind, target: String, window: Window) = db.tx {
        ownerProject(kind,target)
        window.page(rows("${selectRevisions(kind)} WHERE r.${kind.owner}=? AND r.rowid<? ORDER BY r.rowid DESC LIMIT ?",
            target, window.before, window.limit+1) { getLong("cursor") to revision() })
    }
    override fun episodes(project: String) = db.tx {
        ownerProject(WritingKind.OUTLINE,project)
        val plan = head(WritingKind.EPISODE_PLAN,project)
        val briefs = plan?.content?.get("episodes")?.jsonArray.orEmpty()
        val items = rows("SELECT id,ordinal FROM episodes WHERE project_id=? AND ordinal<=? ORDER BY ordinal",project,briefs.size) {
            val i = getInt("ordinal")
            val brief = briefs[i-1].jsonObject
            Episode(getString("id"),i,brief.getValue("title").jsonPrimitive.content,brief.getValue("summary").jsonPrimitive.content,
                brief.getValue("targetDurationMs").jsonPrimitive.int)
        }
        val retired = rows("SELECT id,ordinal FROM episodes WHERE project_id=? AND ordinal>? ORDER BY ordinal",project,briefs.size) {
            getString("id") to getInt("ordinal")
        }.map { (id,i) ->
            val previous = rows("""SELECT json_extract(content_json,?) FROM episode_plans WHERE project_id=?
                AND json_array_length(content_json,'$.episodes')>=? ORDER BY revision DESC LIMIT 1""",
                "$.episodes[${i-1}]",project,i) { json.parseToJsonElement(getString(1)).jsonObject }.single()
            Episode(id,i,previous.getValue("title").jsonPrimitive.content,previous.getValue("summary").jsonPrimitive.content,
                previous.getValue("targetDurationMs").jsonPrimitive.int)
        }
        EpisodeDirectory(plan,items,retired)
    }
    override fun save(kind: WritingKind,target: String,edit: WritingEdit) = db.tx { publishWriting(kind,target,edit,null) }
    override fun approve(kind: WritingKind,target: String,revisionId: String,expected: Int) = db.tx {
        val current = head(kind,target) ?: missing("尚无可审核的版本")
        writingContext(kind,target,expected,current.storyRevisionId,current.planId)
        if (current.id != validId(revisionId) || current.stale) conflict("只能审核当前有效版本")
        update("UPDATE ${kind.table} SET approval_status='APPROVED' WHERE id=?",revisionId)
        current.copy(approvalStatus="APPROVED")
    }
}
