package studio.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import studio.core.*
import studio.domain.TaskStatus
import studio.engine.Manifest
import studio.engine.TextPrompt

@Serializable data class Generate(val workflowId: String, val workflowVersion: String, val parameters: JsonObject,
    val dependencies: List<String> = emptyList(), val priority: Int = 0)
@Serializable data class Snapshot(val workflowId: String?, val workflowVersion: String, val templateHash: String,
    val parameters: JsonObject, val models: List<String>, val engineVersion: String, val assetVersionIds: List<String> = emptyList(),
    val seeds: Map<String, Long> = emptyMap(), val prompt: TextPrompt? = null, val parentAssetVersionId: String? = null, val shotRevisionId: String? = null,
    val referenceDefaults: Map<String,String?> = emptyMap(), val boardId: String? = null,
    val firstFrame: Media? = null, val lastFrame: Media? = null, val referenceFrame: Media? = null, val referenceVersionId: String? = null,
    val targetWidth: Int? = null, val targetHeight: Int? = null, val plannedFrameCount: Int? = null,
    val speech: studio.engine.SpeechCall? = null, val speechOrigin: SpeechOrigin? = null,
    val export: ExportPlan? = null, val timelineRevisionId: String? = null, val resolution: String? = null)
@Serializable data class Problem(val code: String, val message: String)
@Serializable data class Attempt(val id: String, val attemptNo: Int, val engineId: String, val externalId: String?,
    val status: TaskStatus, val error: Problem?, val startedAt: Long?, val finishedAt: Long?, val node: String?, val nodeProgress: Double?,
    val peakVramBytes: Long?, val peakRamBytes: Long?, val resourceSamples: Int, val submitStarted: Boolean = false)
@Serializable data class Task(val id: String, val projectId: String, val assetId: String?, val status: TaskStatus,
    val snapshot: Snapshot, val priority: Int, val createdAt: Long, val attempts: List<Attempt>, val dependencies: List<String>,
    val results: List<Media>, val cancelRequested: Boolean, val kind: String, val targetId: String, val writingResultId: String?)
val Task.isImageJob get() = kind in setOf("IMAGE", "KEYFRAME", "SHOT_RENDER")
@Serializable data class Accepted(val taskId: String, val status: TaskStatus)
@Serializable data class TaskUpdate(val taskId: String, val projectId: String, val attemptId: String,
    val status: TaskStatus, val stage: String, val node: String? = null, val nodeProgress: Double? = null, val overallProgress: Double? = null)
data class Event(val seq: Long, val payload: String)

interface Tasks {
    fun priorSpeech(shot: String,key: String,request: SpeechRequest): Accepted?
    fun enqueueSpeech(shot: String,key: String,request: SpeechRequest,engineId: String,info: studio.engine.TtsInfo): Accepted
    fun priorExport(episode: String,key: String,request: ExportRequest): Accepted?
    fun enqueueExport(episode: String,key: String,request: ExportRequest,engineId: String,version: String): Accepted
    fun enqueueRender(shot: String, key: String, request: RenderRequest, engineId: String, mode: String): Accepted
    fun enqueueBatch(episode: String, key: String, request: BatchRequest, engineId: String, mode: String): BatchAccepted
    fun priorBoard(kind: BoardJob, target: String, key: String, request: BoardRequest): Accepted?
    fun enqueueBoard(kind: BoardJob, target: String, key: String, request: BoardRequest, engineId: String, model: String,
        engineVersion: String?, enableThinking: Boolean?): Accepted
    fun enqueueKeyframe(shot: String, key: String, request: KeyframeRequest, engineId: String, mode: String): Accepted
    fun priorWriting(kind: WritingKind, target: String, key: String, request: WritingRequest): Accepted?
    fun enqueueWriting(kind: WritingKind, target: String, key: String, request: WritingRequest, engineId: String, model: String,
        engineVersion: String? = null, enableThinking: Boolean? = null): Accepted
    fun finishWriting(id: String, content: JsonObject, completionId: String?)
    fun enqueue(asset: String, key: String, request: Generate, engineId: String, mode: String): Accepted
    fun retry(id: String): Accepted
    fun cancel(id: String): Task
    fun get(id: String): Task
    fun list(projectId: String?, status: TaskStatus?, window: Window): Page<Task>
    fun events(after: Long, limit: Int = 100): List<Event>
    fun eventBounds(): LongRange
    fun next(vararg engineIds: String): Task?
    fun lease(): Task?
    fun claim(taskId: String, attemptId: String): Boolean
    fun beginRenderSubmit(taskId: String, attemptId: String): Boolean
    fun acknowledge(taskId: String, externalId: String?)
    fun running(taskId: String)
    fun uncertain(taskId: String, reason: String)
    fun confirmAbsent(taskId: String, attemptId: String)
    fun progress(taskId: String, node: String, fraction: Double)
    fun recordUsage(attemptId: String, vramUsed: Long?, ramUsed: Long?)
    fun finish(taskId: String, files: List<Media> = emptyList(), error: Problem? = null)
}
interface MediaLibrary {
    fun verifiedPath(projectId: String, media: Media): String
    fun saveAudio(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media
    fun saveExport(projectId: String, attemptId: String, source: java.nio.file.Path): Media
    fun readFirstFrame(projectId: String, media: Media, width: Int, height: Int): ByteArray
    fun saveVideo(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media
    fun save(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media
}
