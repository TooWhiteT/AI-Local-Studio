package studio.scheduler

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*
import studio.engine.*
import java.util.logging.Logger

data class WriterLoadBudget(val vramMiB: Long = 0, val ramMiB: Long = 0) {
    init { require(vramMiB in 0..1048576 && ramMiB in 0..1048576) }
}

class GpuScheduler(private val tasks: Tasks, private val registry: WorkflowRegistry, private val media: MediaLibrary,
    private val engine: ImageEngine, private val pollMs: Long = 250, private val writer: TextEngine? = null,
    private val loadBudget: WriterLoadBudget = WriterLoadBudget()) {
    private val workflows get() = registry.store
    private var worker: Job? = null
    private var stream: ProgressStream? = null
    private var streamAttempt: String? = null
    private var nextSample = 0L
    private var freedForAttempt: String? = null
    @Volatile var waitingReason: String? = null; private set

    fun start() {
        check(worker == null)
        worker = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive) {
                    try { waitingReason = tick() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = e.message ?: e.javaClass.simpleName
                        if (waitingReason != message) Logger.getLogger("scheduler").warning(message)
                        waitingReason = message
                    }
                    delay(pollMs)
                }
            } finally { detach() }
        }
    }
    suspend fun stop() { worker?.cancelAndJoin(); worker = null }
    private fun detach() { stream?.close(); stream = null; streamAttempt = null }
    private suspend fun attach(attempt: String) {
        if (streamAttempt == attempt && stream?.open == true) return
        detach()
        stream = engine.listen(attempt)
        streamAttempt = attempt
    }
    private suspend fun tick(): String? {
        val active = tasks.lease()
        if (active != null) {
            if (active.kind != "IMAGE") {
                if (writer == null || active.attempts.last().engineId != writer.identity) return "未结束的文字任务需要原模型服务"
                return advanceWriting(active,writer)
            }
            if (active.attempts.last().engineId != engine.identity) return "未结束的租约属于 ${active.attempts.last().engineId}"
            return reconcile(active)
        }
        detach()
        val engines = listOfNotNull(engine.identity,writer?.identity).toTypedArray()
        val t = tasks.next(*engines) ?: return null
        suspend fun tryDispatch(task: Task): String? = try { dispatch(task) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { e.message ?: "等待引擎可用" }
        val waiting = tryDispatch(t) ?: return null
        if (writer == null || tasks.lease() != null) return waiting
        val otherEngine = if (t.kind == "IMAGE") writer.identity else engine.identity
        val other = tasks.next(otherEngine) ?: return waiting
        return tryDispatch(other)
    }
    private suspend fun dispatch(t: Task): String? {
        if (t.kind != "IMAGE") {
            val text = checkNotNull(writer)
            val model = text.info()
            if (!model.available) return model.reason
            if (model.model != t.snapshot.models.single() || model.version != null && t.snapshot.engineVersion !in setOf("未报告",model.version)) {
                tasks.finish(t.id,error=Problem("MODEL_CHANGED","任务固定的模型与当前服务不一致")); return null
            }
            if (text.real && !text.idle()) return "等待 llama.cpp 空闲槽位"
            if (text.real && engine.mode == "comfy" && engine.queue().isNotEmpty()) return "等待 ComfyUI 外部队列清空"
            if (tasks.claim(t.id,t.attempts.last().id)) return advanceWriting(tasks.get(t.id),text)
            return null
        }
        val workflowId = checkNotNull(t.snapshot.workflowId)
        var wf = workflows.workflow(workflowId)
        val info = if (engine.mode == "comfy" && writer?.real == true) engine.resourcesForSwitch() else engine.info()
        if (!wf.enabled && wf.reason == offlineReason) {
            registry.refresh()
            wf = workflows.workflow(workflowId)
            if (wf.reason == offlineReason) return offlineReason
        }
        if (!wf.enabled || wf.templateHash != t.snapshot.templateHash) {
            tasks.finish(t.id, error = Problem("WORKFLOW_DISABLED", wf.reason ?: "模板哈希不一致")); return null
        }
        if (info.version != t.snapshot.engineVersion) {
            tasks.finish(t.id, error = Problem("ENGINE_CHANGED", "引擎版本已变更，请重新检测模板并创建任务")); return null
        }
        val total = info.vramTotal
        if (total != null && wf.manifest.estimatedVramMb * 1024L * 1024 > total) {
            tasks.finish(t.id, error = Problem("VRAM_LIMIT", "模板显存估算超过设备容量")); return null
        }
        val graph = bind(workflows.graph(wf.id), wf.manifest, t.snapshot.parameters)
        val attempt = t.attempts.last()
        if (engine.queue().isNotEmpty()) return "等待 ComfyUI 外部队列清空"
        if (engine.mode == "comfy" && writer?.real == true && !writer.sleeping()) {
            val free = info.vramFree
            if (free == null || wf.manifest.estimatedVramMb * 1048576L > free)
                return "等待 llama.cpp 休眠释放显存，请检查 --sleep-idle-seconds"
            if (!writer.idle()) return "等待 llama.cpp 空闲槽位"
        }
        attach(attempt.id)
        if (!tasks.claim(t.id, attempt.id)) return null
        try {
            val id = engine.submit(attempt.id, graph)
            tasks.acknowledge(t.id, id)
        } catch (e: CancellationException) { throw e }
        catch (e: PromptRejected) {
            tasks.finish(t.id, error = Problem("PROMPT_REJECTED", e.message.orEmpty()))
            return null
        }
        catch (e: Exception) {
            tasks.uncertain(t.id, "未取得可靠提交回执，正在核对远端记录")
            return null
        }
        recordSample(attempt.id, info)
        return null
    }
    private suspend fun reconcile(t: Task): String? {
        val a = t.attempts.last()
        if (engine.mode == "comfy" && System.nanoTime() >= nextSample) {
            nextSample = System.nanoTime() + 1_000_000_000L
            val info = try { withTimeoutOrNull(2000) { if (writer?.real == true) engine.resourcesForSwitch() else engine.info() } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (info != null) recordSample(a.id, info)
        }
        if (a.externalId == null) {
            tasks.uncertain(t.id, "提交状态待核对")
            val found = engine.findPrompt(a.id) ?: return "等待按 attempt 核对 ComfyUI 提交记录"
            tasks.acknowledge(t.id, found)
            return null
        }
        val id = a.externalId!!
        val outputs = workflows.workflow(checkNotNull(t.snapshot.workflowId)).manifest.outputs
        try {
            val history = engine.history(id, outputs)
            val entry = engine.queue().firstOrNull { it.id == id }
            if (t.status == UNKNOWN && (entry != null || history != null)) {
                tasks.acknowledge(t.id, id); return null
            }
            if (entry != null) {
                if (t.cancelRequested) { engine.cancel(entry); return "等待引擎停止" }
                if (entry.running) tasks.running(t.id)
                try { attach(a.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* history remains authoritative */ }
                stream?.latest()?.takeIf { it.promptId == id }?.let { tasks.progress(t.id, it.node, it.fraction) }
                return null
            }
            // Queue and history are separate remote reads. Check history again across the completion boundary.
            val done = history ?: engine.history(id, outputs)
            if (done == null) {
                if (t.cancelRequested && t.status != UNKNOWN) tasks.finish(t.id)
                else tasks.uncertain(t.id, "队列及历史均缺少已提交的 prompt")
                return null
            }
            if (t.cancelRequested) { tasks.finish(t.id); return null }
            when (done) {
                is History.Failure -> tasks.finish(t.id, error = Problem(done.code, done.message))
                is History.Success -> {
                    val files = done.files.mapIndexed { i, ref ->
                        val bytes = engine.download(ref)
                        try { media.save(t.projectId, a.id, i, bytes) }
                        catch (e: java.io.IOException) {
                            tasks.finish(t.id, error = Problem("STORAGE_ERROR", "无法写入资产文件，请检查磁盘剩余空间和目录权限"))
                            return null
                        }
                    }
                    tasks.finish(t.id, files)
                }
            }
        } catch (e: InvalidOutput) {
            if (engine.queue().none { it.id == id }) tasks.finish(t.id, error = Problem("INVALID_OUTPUT", e.message.orEmpty()))
        }
        return null
    }
    private fun textCall(t: Task) = TextCall(t.kind,t.snapshot.models.single(),checkNotNull(t.snapshot.prompt),t.snapshot.parameters)

    suspend fun canReleaseWriting(t: Task): Boolean {
        val text = writer ?: return false
        val a = t.attempts.last()
        if (a.engineId != text.identity) return false
        val state = text.advance(a.id,textCall(t),false)
        return (state is TextRun.Uncertain || state == TextRun.Unsent) && text.idle()
    }

    private suspend fun advanceWriting(t: Task, text: TextEngine): String? {
        val a = t.attempts.last()
        if (t.status == SUBMITTING) {
            if (text.real && engine.mode == "comfy") {
                if (engine.queue().isNotEmpty()) return "等待 ComfyUI 外部队列清空"
                if (freedForAttempt != a.id) {
                    engine.releaseModels()
                    freedForAttempt = a.id
                    return "正在等待 ComfyUI 释放模型"
                }
                val memory = engine.resourcesForSwitch()
                recordSample(a.id,memory)
                if (text.sleeping()) {
                    val vram = loadBudget.vramMiB * 1048576
                    val ram = loadBudget.ramMiB * 1048576
                    if ((memory.vramTotal ?: Long.MAX_VALUE) < vram || (memory.ramTotal ?: Long.MAX_VALUE) < ram) {
                        tasks.finish(t.id,error=Problem("MEMORY_LIMIT","编剧加载预算超过目标机容量")); return null
                    }
                    if ((memory.vramFree ?: 0) < vram || (memory.ramFree ?: 0) < ram)
                        return "等待目标机释放编剧模型所需的显存和内存"
                }
                if (!text.idle()) return "等待 llama.cpp 空闲槽位"
            }
            tasks.acknowledge(t.id,null)
        }
        if (text.real && engine.mode == "comfy" && System.nanoTime() >= nextSample) {
            nextSample = System.nanoTime() + 1_000_000_000L
            val sample = try { withTimeoutOrNull(2000) { engine.resourcesForSwitch() } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (sample != null) recordSample(a.id,sample)
        }
        val outcome = text.advance(a.id,textCall(t),!t.cancelRequested && t.status != UNKNOWN)
        if (outcome is TextRun.Uncertain) {
            tasks.uncertain(t.id,outcome.message); return outcome.message
        }
        if (t.status == UNKNOWN && outcome != TextRun.Unsent) tasks.acknowledge(t.id,null)
        if (outcome == TextRun.Active) {
            tasks.running(t.id); return if (t.cancelRequested) "等待模型执行结束" else null
        }
        if (outcome == TextRun.Unsent && t.status == UNKNOWN) return "文字提交状态待核对"
        if (t.cancelRequested) { tasks.finish(t.id); return null }
        when (outcome) {
            is TextRun.Completed -> {
                val content = try { json.parseToJsonElement(outcome.content) as? JsonObject }
                    catch (_: kotlinx.serialization.SerializationException) { null }
                if (content == null) tasks.finish(t.id,error=Problem("INVALID_MODEL_OUTPUT","模型结果须为单个 JSON 对象"))
                else try { tasks.finishWriting(t.id,content,outcome.completionId) }
                catch (e: StudioError) {
                    tasks.finish(t.id,error=Problem(if (e.code == "CONFLICT") "REVISION_CONFLICT" else "INVALID_MODEL_OUTPUT",e.message.orEmpty()))
                }
            }
            is TextRun.Rejected -> tasks.finish(t.id,error=Problem(outcome.code,outcome.message))
            else -> Unit
        }
        return null
    }
    private fun recordSample(attemptId: String, info: EngineInfo) {
        fun used(total: Long?, free: Long?): Long? =
            if (total != null && total > 0 && free != null && free in 0..total) total - free else null
        tasks.recordUsage(attemptId, used(info.vramTotal, info.vramFree), used(info.ramTotal, info.ramFree))
    }
}
