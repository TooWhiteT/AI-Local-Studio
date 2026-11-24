package studio.infra

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.domain.*
import studio.domain.TaskStatus.*
import studio.engine.Manifest

private const val leased = "'SUBMITTING','SUBMITTED','RUNNING','UNKNOWN','CANCEL_REQUESTED'"
private fun now() = System.currentTimeMillis()
private fun ResultSet.nullLong(key: String): Long? = getLong(key).takeUnless { wasNull() }
private fun ResultSet.toAttempt() = Attempt(getString("id"), getInt("attempt_no"), getString("engine_id"), getString("external_id"),
    TaskStatus.valueOf(getString("status")), getString("error_json")?.let { json.decodeFromString<Problem>(it) },
    nullLong("started_at"), nullLong("finished_at"), getString("node"), getDouble("node_progress").takeUnless { wasNull() },
    nullLong("peak_vram_bytes"), nullLong("peak_ram_bytes"), getInt("resource_samples"))

class SqlTasks(private val db: StudioDb) : Tasks {
    private fun Connection.task(id: String): Task {
        val attempts = rows("SELECT * FROM task_attempts WHERE task_id=? ORDER BY attempt_no", id) { toAttempt() }
        val deps = rows("SELECT dependency_id FROM task_dependencies WHERE task_id=? ORDER BY rowid", id) { getString(1) }
        val results = rows("""SELECT r.file_id,f.* FROM render_results r JOIN media_files f ON f.id=r.file_id
            JOIN task_attempts a ON a.id=r.attempt_id WHERE a.task_id=? ORDER BY a.attempt_no,r.ordinal""", id) { toMedia() }
        return rows("SELECT * FROM render_tasks WHERE id=?", id) {
            Task(id, getString("project_id"), getString("image_asset"), TaskStatus.valueOf(getString("status")),
                json.decodeFromString(getString("input_snapshot_json")), getInt("priority"), getLong("created_at"), attempts, deps,
                results, getBoolean("cancel_requested"), getString("task_type"), getString("target_id"), getString("writing_result_id"))
        }.firstOrNull() ?: missing("任务不存在")
    }
    private fun Connection.attempt(taskId: String) = rows("SELECT * FROM task_attempts WHERE task_id=? ORDER BY attempt_no DESC LIMIT 1", taskId) {
        toAttempt()
    }.firstOrNull() ?: missing("任务不存在")
    private fun Connection.event(id: String, stage: String) {
        val a = attempt(id)
        val projectId = rows("SELECT project_id FROM render_tasks WHERE id=?", id) { getString(1) }.single()
        val payload = TaskUpdate(id, projectId, a.id, a.status, stage, a.node, a.nodeProgress)
        update("INSERT INTO task_events(task_id,event_type,payload_json,created_at) VALUES(?,'task.updated',?,?)", id, json.encodeToString(payload), now())
    }
    private fun Connection.transition(id: String, to: TaskStatus, error: Problem? = null) {
        val a = attempt(id)
        if (!a.status.allows(to)) conflict("不允许状态转换 ${a.status} → $to")
        update("UPDATE render_tasks SET status=? WHERE id=?", to.name, id)
        update("""UPDATE task_attempts SET status=?,error_json=?,finished_at=?,
            started_at=CASE WHEN ?='SUBMITTING' THEN ? ELSE started_at END WHERE id=?""",
            to.name, error?.let { json.encodeToString(it) }, if (to.terminal) now() else null, to.name, now(), a.id)
        event(id, to.name)
    }
    private fun Connection.addAttempt(id: String, no: Int, engine: String) {
        update("INSERT INTO task_attempts(id,task_id,attempt_no,engine_id,status,created_at) VALUES(?,?,?,?,'PENDING',?)", uid(), id, no, engine, now())
        event(id, "PENDING")
    }
    private fun Connection.existing(projectId: String, key: String, hash: String): Accepted? {
        val existing = rows("SELECT id,request_hash,status FROM render_tasks WHERE project_id=? AND idempotency_key=?", projectId, validId(key)) {
            Triple(getString(1), getString(2), getString(3))
        }.firstOrNull()
        if (existing != null) {
            if (hash != existing.second) conflict("幂等键已用于不同请求")
            return Accepted(existing.first, TaskStatus.valueOf(existing.third))
        }
        return null
    }
    private fun writingHash(kind: WritingKind, target: String, request: WritingRequest): String {
        val data = json.encodeToJsonElement(request).jsonObject.filterNot { (key,value) ->
            value == JsonNull && key in setOf("characterCount","locationCount","sceneCount","linesPerScene")
        }
        return sha256((kind.name + target + sortedJson(JsonObject(data))).toByteArray())
    }
    override fun priorWriting(kind: WritingKind, target: String, key: String, request: WritingRequest) = db.tx {
        existing(ownerProject(kind,target),key,writingHash(kind,target,request))
    }
    override fun enqueueWriting(kind: WritingKind, target: String, key: String, request: WritingRequest, engineId: String, model: String, engineVersion: String?, enableThinking: Boolean?) = db.tx {
        val project = ownerProject(kind,target)
        val hash = writingHash(kind,target,request)
        existing(project,key,hash)?.let { return@tx it }
        checkInput(request.brief.length <= 4000 && (kind != WritingKind.OUTLINE || request.brief.isNotBlank()), "请输入有效的创作意图（最多 4000 字）")
        checkInput(request.episodeCount in 1..12 && request.targetDurationMs in 1000..600000,"集数或目标时长超出范围")
        val ctx = writingContext(kind,target,request.expectedRevision,request.storyRevisionId,request.planId)
        val projectInfo = rows("SELECT title,genre,style,aspect_ratio FROM projects WHERE id=?",project) {
            buildJsonObject { for (field in listOf("title","genre","style","aspect_ratio")) put(field,getString(field)) }
        }.single()
        val input = buildJsonObject {
            put("request",json.encodeToJsonElement(request)); put("project",projectInfo)
            ctx.previous?.let { put("currentDraft",it.content) }
            ctx.story?.let { put("outline",it.content) }
            ctx.episode?.let { put("episode",it) }
        }
        val duration = ctx.episode?.getValue("targetDurationMs")?.jsonPrimitive?.int
        val prompt = WritingPrompts.forTask(kind,request,ctx.story?.content,duration).copy(enableThinking=enableThinking)
        val snapshot = Snapshot(null,prompt.version,sha256(sortedJson(json.encodeToJsonElement(prompt)).toString().toByteArray()),
            input,listOf(model),engineVersion ?: "未报告",prompt=prompt)
        addTextTask(project,if (kind == WritingKind.SCRIPT) "EPISODE" else "PROJECT",target,kind.name,snapshot,hash,key,engineId)
    }
    private fun Connection.addTextTask(project: String, targetType: String, target: String, kind: String,
        snapshot: Snapshot, hash: String, key: String, engineId: String): Accepted {
        val id = uid()
        update("""INSERT INTO render_tasks(id,project_id,target_type,target_id,task_type,input_snapshot_json,
            request_hash,status,priority,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?)""",
            id,project,targetType,target,kind,json.encodeToString(snapshot),hash,key,now())
        addAttempt(id,1,engineId)
        return Accepted(id,PENDING)
    }
    private fun boardHash(kind: BoardJob,target: String,request: BoardRequest) =
        sha256((kind.name+target+sortedJson(json.encodeToJsonElement(request))).toByteArray())
    override fun priorBoard(kind: BoardJob,target: String,key: String,request: BoardRequest) = db.tx {
        existing(boardProject(kind,target),key,boardHash(kind,target,request))
    }
    override fun enqueueBoard(kind: BoardJob,target: String,key: String,request: BoardRequest,engineId: String,
        model: String,engineVersion: String?,enableThinking: Boolean?) = db.tx {
        val project=boardProject(kind,target); val hash=boardHash(kind,target,request)
        existing(project,key,hash)?.let { return@tx it }
        val ctx=boardContext(kind,target,request)
        val prompt=StoryboardPrompts.forTask(kind,ctx.source.scenes.size,request.shotsPerScene,enableThinking)
        val snapshot=Snapshot(null,prompt.version,sha256(sortedJson(json.encodeToJsonElement(prompt)).toString().toByteArray()),
            ctx.input,listOf(model),engineVersion ?: "未报告",ctx.references.map { it.versionId },prompt=prompt,shotRevisionId=ctx.selected?.revisionId,
            referenceDefaults=ctx.references.associate { it.assetId to it.currentVersionId })
        addTextTask(project,kind.targetType,target,kind.name,snapshot,hash,key,engineId)
    }
    override fun finishWriting(id: String, content: JsonObject, completionId: String?) = db.tx {
        val t = task(id)
        if (t.cancelRequested) { transition(id,CANCELED); return@tx }
        check(t.status in setOf(SUBMITTED,RUNNING))
        validateShape(checkNotNull(t.snapshot.prompt).schema,content,t.kind)
        val boardKind=BoardJob.entries.find { it.name==t.kind }
        val result=if (boardKind!=null) publishBoardJob(t,boardKind,content) else {
            val req = json.decodeFromJsonElement<WritingRequest>(t.snapshot.parameters.getValue("request"))
            val kind = WritingKind.valueOf(t.kind)
            publishWriting(kind,t.targetId,WritingEdit(req.expectedRevision,content,req.storyRevisionId,req.planId),
                t.attempts.last().id,req.episodeCount.takeIf { kind == WritingKind.EPISODE_PLAN }).id
        }
        update("UPDATE render_tasks SET writing_result_id=? WHERE id=?",result,id)
        update("UPDATE task_attempts SET external_id=? WHERE id=?",completionId,t.attempts.last().id)
        transition(id,SUCCEEDED)
    }
    override fun enqueue(asset: String, key: String, request: Generate, engineId: String, mode: String) = db.tx {
        val projectId = rows("SELECT project_id FROM assets WHERE id=?", asset) { getString(1) }.firstOrNull() ?: missing("资产不存在")
        val hash = sha256((asset + sortedJson(json.encodeToJsonElement(request))).toByteArray())
        existing(projectId,key,hash)?.let { return@tx it }
        requireActive(projectId)
        val (type, parent) = rows("SELECT type,active_version_id FROM assets WHERE id=?",asset) { getString(1) to getString(2) }.single()
        checkInput(type != "VOICE", "声音档案不能生成图片")
        val snapshot=imageSnapshot(request,mode).copy(parentAssetVersionId=parent)
        addImageTask(projectId,"ASSET",asset,"IMAGE",key,hash,request,snapshot,engineId)
    }
    override fun enqueueKeyframe(shot: String,key: String,request: KeyframeRequest,engineId: String,mode: String) = db.tx {
        val episode=episodeForShot(shot)
        val project=ownerProject(WritingKind.SCRIPT,episode)
        val hash=sha256(("KEYFRAME"+shot+sortedJson(json.encodeToJsonElement(request))).toByteArray())
        existing(project,key,hash)?.let { return@tx it }
        val board=loadBoard(episode)
        val version=board.version ?: missing("分镜不存在")
        requireScript(source(version.scriptRevisionId))
        val current=board.shots.find { it.id==shot } ?: conflict("镜头已移出当前分镜")
        if (current.revisionId!=request.shotRevisionId) conflict("镜头已更新，请刷新")
        val scene=board.scenes.single { it.id==current.sceneId }
        val description=listOf(scene.location,current.framing,current.camera,current.action) +
            current.references.map { ref ->
                val depiction=ref.visualPrompt?.takeIf { it.isNotBlank() } ?: ref.description
                ref.name+"："+depiction.take(200)
            }
        val extra=request.parameters["prompt"]?.let { v ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("提示词应为文本")
        }.orEmpty()
        val values=JsonObject(request.parameters+("prompt" to JsonPrimitive((description+extra).filter { it.isNotBlank() }.joinToString("。"))))
        val gen=Generate(request.workflowId,request.workflowVersion,values)
        val snapshot=imageSnapshot(gen,mode).copy(shotRevisionId=current.revisionId,assetVersionIds=current.references.map { it.versionId })
        addImageTask(project,"SHOT",shot,"KEYFRAME",key,hash,gen,snapshot,engineId)
    }
    private fun Connection.imageSnapshot(request: Generate,mode: String): Snapshot =
        rows("SELECT * FROM workflows WHERE id=?", validId(request.workflowId)) {
            val m = json.decodeFromString<Manifest>(getString("manifest_json"))
            checkInput(request.workflowVersion == m.version && mode == m.mode, "模板版本或引擎模式不匹配")
            if (!getBoolean("enabled")) unavailable(getString("reason") ?: "模板已禁用")
            val values = parameters(m, request.parameters)
            val bound = bind(json.parseToJsonElement(getString("graph_json")).jsonObject, m, values)
            val seeds = buildMap {
                for ((nodeId, node) in bound) {
                    for ((key, v) in node.jsonObject.getValue("inputs").jsonObject) {
                        if (key == "seed" || key == "noise_seed") (v as? JsonPrimitive)?.longOrNull?.let { put("$nodeId.$key", it) }
                    }
                }
            }
            Snapshot(request.workflowId, m.version, getString("template_hash"), values,
                m.models.map { it.name }, getString("engine_version"), seeds = seeds)
        }.firstOrNull() ?: missing("模板不存在")
    private fun Connection.addImageTask(projectId: String,targetType: String,target: String,kind: String,key: String,
        hash: String,request: Generate,snapshot: Snapshot,engineId: String): Accepted {
        checkInput(request.priority in 0..9, "优先级须为 0 至 9")
        val id = uid()
        val deps = request.dependencies
        checkInput(deps.size <= 64 && deps.distinct().size == deps.size, "依赖过多或重复")
        for (dep in deps) checkInput(rows("SELECT project_id FROM render_tasks WHERE id=?", validId(dep)) { getString(1) }.singleOrNull() == projectId,
            "依赖必须属于当前项目")
        // Only new tasks can acquire edges, and each edge points to a committed task.
        checkInput(acyclic(mapOf(id to deps)), "任务依赖成环")
        update("""INSERT INTO render_tasks(id,project_id,target_type,target_id,task_type,workflow_id,input_snapshot_json,
            request_hash,status,priority,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?,?)""",
            id, projectId, targetType, target, kind, request.workflowId, json.encodeToString(snapshot), hash, request.priority, key, now())
        for (dep in deps) update("INSERT INTO task_dependencies VALUES(?,?)", id, dep)
        addAttempt(id, 1, engineId)
        return Accepted(id, PENDING)
    }
    override fun retry(id: String) = db.tx {
        val t = task(id)
        if (t.status != FAILED) conflict("仅失败任务可重试")
        requireActive(t.projectId)
        val a = t.attempts.last()
        update("UPDATE render_tasks SET status='PENDING',cancel_requested=0 WHERE id=?", id)
        addAttempt(id, a.attemptNo + 1, a.engineId)
        Accepted(id, PENDING)
    }
    override fun cancel(id: String) = db.tx {
        val a = attempt(id)
        if (!a.status.terminal && a.status != CANCEL_REQUESTED) {
            update("UPDATE render_tasks SET cancel_requested=1 WHERE id=?", id)
            if (a.status in setOf(READY, PENDING)) transition(id, CANCELED)
            else if (a.status != UNKNOWN) transition(id, CANCEL_REQUESTED)
            else event(id, "CANCEL_REQUESTED")
        }
        task(id)
    }
    override fun get(id: String) = db.tx { task(id) }
    override fun list(projectId: String?, status: TaskStatus?, window: Window) = db.tx {
        window.page(rows("""SELECT rowid,id FROM render_tasks WHERE (? IS NULL OR project_id=?) AND (? IS NULL OR status=?)
            AND rowid<? ORDER BY rowid DESC LIMIT ?""", projectId, projectId, status?.name, status?.name, window.before, window.limit + 1) {
            getLong(1) to getString(2)
        }.map { (row, id) -> row to task(id) })
    }
    override fun events(after: Long, limit: Int) = db.tx {
        rows("SELECT event_seq,payload_json FROM task_events WHERE event_seq>? ORDER BY event_seq LIMIT ?", after, limit) { Event(getLong(1), getString(2)) }
    }
    override fun eventBounds() = db.tx { rows("SELECT min(event_seq),max(event_seq) FROM task_events") { getLong(1)..getLong(2) }.single() }
    override fun lease() = db.tx { rows("SELECT id FROM render_tasks WHERE status IN ($leased)") { getString(1) }.singleOrNull()?.let { task(it) } }
    override fun next(vararg engineIds: String) = db.tx {
        val placeholders = engineIds.joinToString { "?" }
        // Pending tasks with a failed dependency terminate independently of unrelated work.
        val wait = rows("""SELECT t.id FROM render_tasks t JOIN task_attempts a ON a.task_id=t.id
            WHERE t.status='PENDING' AND a.status='PENDING' AND a.engine_id IN ($placeholders) ORDER BY t.rowid""", *engineIds) { getString(1) }
        for (id in wait) {
            val deps = rows("SELECT t.status FROM task_dependencies d JOIN render_tasks t ON t.id=d.dependency_id WHERE d.task_id=?", id) { getString(1) }
            if (deps.any { it in setOf("FAILED", "CANCELED") }) transition(id, FAILED, Problem("DEPENDENCY", "前置任务失败或已取消"))
            else if (deps.all { it == "SUCCEEDED" }) transition(id, READY)
        }
        rows("""SELECT t.id FROM render_tasks t JOIN task_attempts a ON a.task_id=t.id WHERE t.status='READY' AND a.status='READY'
            AND a.engine_id IN ($placeholders) ORDER BY (t.priority + (? - t.created_at)/60000) DESC,t.rowid LIMIT 1""", *engineIds, now()) { getString(1) }
            .firstOrNull()?.let { task(it) }
    }
    override fun claim(taskId: String, attemptId: String) = db.tx {
        val a = attempt(taskId)
        if (a.id != attemptId || a.status != READY || rows("SELECT 1 FROM render_tasks WHERE status IN ($leased)") { getInt(1) }.isNotEmpty()) false
        else { transition(taskId, SUBMITTING); true }
    }
    override fun acknowledge(taskId: String, externalId: String?) = db.tx {
        val t = task(taskId)
        val a = t.attempts.last()
        check(a.externalId == null || a.externalId == externalId)
        if (t.isImageJob) checkNotNull(externalId)
        update("UPDATE task_attempts SET external_id=? WHERE id=?", externalId?.let(::validId), a.id)
        val to = if (t.cancelRequested) CANCEL_REQUESTED else SUBMITTED
        if (a.status != to) transition(taskId, to) else event(taskId, to.name)
    }
    override fun running(taskId: String) = db.tx { if (attempt(taskId).status == SUBMITTED) transition(taskId, RUNNING) }
    override fun uncertain(taskId: String, reason: String) = db.tx {
        if (attempt(taskId).status != UNKNOWN) transition(taskId, UNKNOWN, Problem("UNKNOWN_SUBMISSION", reason))
    }
    override fun confirmAbsent(taskId: String, attemptId: String) = db.tx {
        val a = attempt(taskId)
        if (a.status != UNKNOWN || a.id != attemptId) conflict("任务状态已变化")
        transition(taskId, FAILED, Problem("MANUALLY_RECONCILED", "操作者已核对引擎记录并确认任务已停止"))
    }
    override fun progress(taskId: String, node: String, fraction: Double) = db.tx {
        val a = attempt(taskId)
        if (a.status in setOf(SUBMITTED, RUNNING) && (a.node != node || a.nodeProgress != fraction)) {
            if (a.status == SUBMITTED) transition(taskId, RUNNING)
            update("UPDATE task_attempts SET node=?,node_progress=? WHERE id=?", node, fraction, a.id)
            event(taskId, "SAMPLING")
        }
    }
    override fun recordUsage(attemptId: String, vramUsed: Long?, ramUsed: Long?) {
        if (vramUsed == null && ramUsed == null) return
        db.tx {
            update("""UPDATE task_attempts SET resource_samples=resource_samples+1,
                peak_vram_bytes=CASE WHEN ?>coalesce(peak_vram_bytes,-1) THEN ? ELSE peak_vram_bytes END,
                peak_ram_bytes=CASE WHEN ?>coalesce(peak_ram_bytes,-1) THEN ? ELSE peak_ram_bytes END
                WHERE id=? AND status IN ($leased)""", vramUsed, vramUsed, ramUsed, ramUsed, attemptId)
        }
    }
    override fun finish(taskId: String, files: List<Media>, error: Problem?) = db.tx {
        val t = task(taskId)
        val a = t.attempts.last()
        if (t.cancelRequested && t.status != UNKNOWN) { transition(taskId, CANCELED); return@tx }
        if (error != null) { transition(taskId, FAILED, error); return@tx }
        check(files.isNotEmpty() && t.status in setOf(SUBMITTED, RUNNING))
        var revision = rows("SELECT coalesce(max(revision),0) FROM asset_versions WHERE asset_id=?", t.assetId) { getInt(1) }.single()
        for ((i, f) in files.withIndex()) {
            update("""INSERT INTO media_files(id,project_id,relative_path,sha256,mime,size_bytes,width,height,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)""", f.id, t.projectId, f.relativePath, f.sha256, f.mime, f.sizeBytes, f.width, f.height, now())
            if (t.kind=="KEYFRAME") update("INSERT INTO shot_previews VALUES(?,?,?,?)",checkNotNull(t.snapshot.shotRevisionId),f.id,a.id,i)
            else update("""INSERT INTO asset_versions(id,asset_id,revision,file_id,metadata_json,approval_status,parent_version_id,created_at)
                VALUES(?,?,?,?,?,'REVIEW_REQUIRED',?,?)""", uid(), t.assetId, ++revision, f.id, json.encodeToString(t.snapshot), t.snapshot.parentAssetVersionId, now())
            update("INSERT INTO render_results VALUES(?,?,?,?,?,?)", uid(), a.id, f.id, t.kind, i, now())
        }
        transition(taskId, SUCCEEDED)
    }
}
