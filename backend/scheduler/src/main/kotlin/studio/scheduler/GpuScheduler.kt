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
    private val loadBudget: WriterLoadBudget = WriterLoadBudget(), private val tts: TtsEngine? = null, private val exporter: ExportEngine? = null) {
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
            if (active.kind == "SHOT_AUDIO") {
                val speech = tts?.takeIf { it.identity == active.attempts.last().engineId } ?: return "未结束的配音任务需要原语音服务"
                return advanceSpeech(active, speech)
            }
            if (active.kind == "EPISODE_EXPORT") {
                val export = exporter?.takeIf { it.identity == active.attempts.last().engineId } ?: return "未结束的导出任务需要原执行器"
                return advanceExport(active, export)
            }
            if (!active.isImageJob) {
                if (writer == null || active.attempts.last().engineId != writer.identity) return "未结束的文字任务需要原模型服务"
                return advanceWriting(active,writer)
            }
            if (active.attempts.last().engineId != engine.identity) return "未结束的租约属于 ${active.attempts.last().engineId}"
            return reconcile(active)
        }
        detach()
        val engines = listOfNotNull(engine.identity, writer?.identity, tts?.identity, exporter?.identity).toMutableList()
        var waiting: String? = null
        while (engines.isNotEmpty()) {
            val task = tasks.next(*engines.toTypedArray()) ?: break
            val reason = try { dispatch(task) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { e.message ?: "等待引擎可用" }
            if (reason == null || tasks.lease() != null) return reason
            if (waiting == null) waiting = reason
            if (!engines.remove(task.attempts.last().engineId)) return waiting
        }
        return waiting
    }
    private suspend fun dispatch(t: Task, claimedRender: Boolean = false): String? {
        if (t.kind == "SHOT_AUDIO") {
            val speech = checkNotNull(tts)
            val info = speech.info()
            if (!info.available) return info.reason ?: "语音服务离线"
            if (info.device == "gpu" && engine.mode == "comfy" && engine.queue().isNotEmpty()) return "等待 ComfyUI 队列清空"
            if (info.device == "gpu" && writer?.real == true && !writer.sleeping()) return "等待 llama.cpp 休眠释放显存"
            if (tasks.claim(t.id, t.attempts.last().id)) return advanceSpeech(tasks.get(t.id), speech)
            return null
        }
        if (t.kind == "EPISODE_EXPORT") {
            val export = checkNotNull(exporter)
            if (export.version() != t.snapshot.engineVersion) {
                tasks.finish(t.id, error = Problem("ENGINE_CHANGED","FFmpeg 版本已变化，请重新建立导出任务")); return null
            }
            if (tasks.claim(t.id, t.attempts.last().id)) return advanceExport(tasks.get(t.id), export)
            return null
        }
        if (!t.isImageJob) {
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
        val attempt = t.attempts.last()
        if (engine.queue().isNotEmpty()) return "等待 ComfyUI 外部队列清空"
        if (engine.mode == "comfy" && writer?.real == true && !writer.sleeping()) {
            val free = info.vramFree
            if (t.kind == "SHOT_RENDER" || free == null || wf.manifest.estimatedVramMb * 1048576L > free)
                return "等待 llama.cpp 休眠释放显存，请检查 --sleep-idle-seconds"
            if (!writer.idle()) return "等待 llama.cpp 空闲槽位"
        }
        val imageGraph = if (t.kind == "SHOT_RENDER") null else bind(workflows.graph(workflowId), wf.manifest, t.snapshot.parameters)
        attach(attempt.id)
        if (!claimedRender && !tasks.claim(t.id, attempt.id)) return null
        recordSample(attempt.id, info)
        if (t.kind == "SHOT_RENDER") return prepareRender(tasks.get(t.id), wf)
        return submit(t.id, attempt.id, checkNotNull(imageGraph))
    }
    private suspend fun prepareRender(t: Task, workflow: Workflow): String? {
        val attempt = t.attempts.last()
        if (t.cancelRequested) { tasks.finish(t.id); return null }
        val manifest = workflow.manifest
        val size = checkNotNull(manifest.frameSize)
        suspend fun upload(frame: Media): String {
            val png = media.readFirstFrame(t.projectId, frame, size.width, size.height)
            return engine.uploadFrame("studio-${sha256(png)}.png", png)
        }
        val graph = try {
            val first = t.snapshot.firstFrame?.let { upload(it) }
            val last = t.snapshot.lastFrame?.let { upload(it) }
            val reference = t.snapshot.referenceFrame?.let { upload(it) }
            bindFrames(bind(workflows.graph(workflow.id), manifest, t.snapshot.parameters), manifest, first, last, reference)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            tasks.finish(t.id, error = Problem("FRAME_INPUT_ERROR", e.message?.take(240) ?: "关键帧上传失败"))
            return null
        }
        attach(attempt.id)
        // Uploads can be repeated by content hash after a restart. Once this marker commits, only remote reconciliation is safe.
        if (!tasks.beginRenderSubmit(t.id, attempt.id)) return null
        return submit(t.id, attempt.id, graph)
    }
    private suspend fun submit(taskId: String, attemptId: String, graph: JsonObject): String? {
        try {
            val id = engine.submit(attemptId, graph)
            tasks.acknowledge(taskId, id)
        } catch (e: CancellationException) { throw e }
        catch (e: PromptRejected) {
            tasks.finish(taskId, error = Problem("PROMPT_REJECTED", e.message.orEmpty()))
            return null
        }
        catch (e: Exception) {
            tasks.uncertain(taskId, "未取得可靠提交回执，正在核对远端记录")
            return null
        }
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
            if (t.kind == "SHOT_RENDER" && !a.submitStarted) {
                if (t.cancelRequested) { tasks.finish(t.id); return null }
                return dispatch(t, claimedRender = true)
            }
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
                        try {
                            if (t.kind == "SHOT_RENDER") media.saveVideo(t.projectId, a.id, i, bytes).also {
                                val snapshot = t.snapshot
                                if ((snapshot.targetWidth != null && snapshot.targetWidth != it.width) ||
                                    (snapshot.targetHeight != null && snapshot.targetHeight != it.height) ||
                                    (snapshot.plannedFrameCount != null && snapshot.plannedFrameCount?.toLong() != it.frameCount))
                                    throw InvalidOutput("视频尺寸或解码帧数与入队时的计划不符")
                            }
                            else media.save(t.projectId, a.id, i, bytes)
                        }
                        catch (e: MediaProbeUnavailable) {
                            tasks.finish(t.id, error = Problem("FFPROBE_MISSING", e.message.orEmpty()))
                            return null
                        }
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
    private suspend fun advanceSpeech(t: Task, speech: TtsEngine): String? {
        val attempt = t.attempts.last()
        val pinnedDevice = t.snapshot.parameters["device"]?.jsonPrimitive?.content
        val info = if (t.status == SUBMITTING || pinnedDevice == null) speech.info() else null
        if (info != null && !info.available) return info.reason ?: "等待原语音服务恢复"
        val gpu = (pinnedDevice ?: info?.device) == "gpu"
        val call = checkNotNull(t.snapshot.speech).copy(modelVersion = t.snapshot.engineVersion)
        if (t.status == SUBMITTING) {
            if (info == null || info.modelVersionFor(call.voiceId) != t.snapshot.engineVersion ||
                pinnedDevice != null && info.device != pinnedDevice) {
                tasks.finish(t.id, error = Problem("VOICE_CHANGED", "任务绑定的音色或服务版本已改变")); return null
            }
            if (gpu) {
                if (writer?.real == true && !writer.sleeping()) return "等待 llama.cpp 休眠释放显存"
                if (engine.mode == "comfy") {
                    if (engine.queue().isNotEmpty()) return "等待 ComfyUI 队列清空"
                    if (freedForAttempt != attempt.id) {
                        engine.releaseModels(); freedForAttempt = attempt.id
                        return "正在等待 ComfyUI 释放模型"
                    }
                }
            }
            tasks.acknowledge(t.id, attempt.id)
        }
        val state = try { speech.advance(attempt.id, call, t.cancelRequested) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = e.message?.take(240) ?: "等待原语音服务恢复对账"
                tasks.uncertain(t.id, reason)
                return reason
            }
        if (t.status == UNKNOWN) tasks.acknowledge(t.id, attempt.id)
        if (state == SpeechRun.Active) {
            if (!t.cancelRequested) tasks.running(t.id)
            return if (t.cancelRequested) "等待语音服务结束" else null
        }
        if (gpu) speech.releaseModels()
        if (t.cancelRequested) { tasks.finish(t.id); return null }
        when (state) {
            is SpeechRun.Failed -> tasks.finish(t.id, error = Problem(state.code, state.message))
            SpeechRun.Cancelled -> tasks.finish(t.id, error = Problem("ENGINE_CANCELLED", "语音服务已取消合成"))
            is SpeechRun.Completed -> finishMedia(t) { media.saveAudio(t.projectId, attempt.id, 0, state.wav) }
            else -> Unit
        }
        return null
    }
    private suspend fun advanceExport(t: Task, export: ExportEngine): String? {
        val attempt = t.attempts.last()
        if (t.status == SUBMITTING) tasks.acknowledge(t.id, attempt.id)
        val plan = checkNotNull(t.snapshot.export)
        val state = export.advance(attempt.id, t.cancelRequested) {
            val paths = mutableMapOf<String,String>()
            fun path(file: Media) = paths.getOrPut(file.id) { media.verifiedPath(t.projectId, file) }
            Movie(plan.width, plan.height, plan.fps, plan.clips.map { clip ->
                val captions = clip.captions.map { MovieCaption(it.startMs, it.durationMs, it.text) } + clip.dialogues
                    .filter { it.subtitle.isNotBlank() }.map { MovieCaption(it.startMs, checkNotNull(it.audio.durationMs), it.subtitle) }
                MovieClip(path(clip.video), clip.sourceInMs, clip.durationMs, clip.originalGain,
                    clip.dialogues.map { MovieAudio(path(it.audio), it.startMs, it.gain) }, captions)
            })
        }
        if (state == ExportRun.Active) {
            if (!t.cancelRequested) tasks.running(t.id)
            return if (t.cancelRequested) "等待导出进程退出" else null
        }
        if (t.cancelRequested) {
            tasks.finish(t.id)
            export.releaseResult(attempt.id)
            return null
        }
        when (state) {
            is ExportRun.Failed -> tasks.finish(t.id, error = Problem(state.code, state.message))
            ExportRun.Cancelled -> tasks.finish(t.id, error = Problem("ENGINE_CANCELLED", "本地导出已取消"))
            is ExportRun.Completed -> finishMedia(t) {
                val result = media.saveExport(t.projectId, attempt.id, state.path)
                val duration = plan.clips.sumOf { it.durationMs }
                if (result.width != plan.width || result.height != plan.height || result.fps != plan.fps.toDouble() ||
                    kotlin.math.abs(checkNotNull(result.durationMs) - duration) > 2000L / plan.fps + 10)
                    throw InvalidOutput("成片的尺寸、帧率或时长与固定时间线不符")
                result
            }
            else -> Unit
        }
        export.releaseResult(attempt.id)
        return null
    }
    private fun finishMedia(task: Task, read: () -> Media) {
        val result = try { read() }
            catch (e: InvalidOutput) { tasks.finish(task.id, error = Problem("INVALID_OUTPUT", e.message.orEmpty())); return }
            catch (e: MediaProbeUnavailable) { tasks.finish(task.id, error = Problem("FFPROBE_MISSING", e.message.orEmpty())); return }
            catch (e: java.io.IOException) { tasks.finish(task.id, error = Problem("STORAGE_ERROR", "无法写入或读取媒体文件")); return }
        tasks.finish(task.id, listOf(result))
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
