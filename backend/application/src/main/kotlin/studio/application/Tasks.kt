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
    val seeds: Map<String, Long> = emptyMap(), val prompt: TextPrompt? = null)
@Serializable data class Problem(val code: String, val message: String)
@Serializable data class Attempt(val id: String, val attemptNo: Int, val engineId: String, val externalId: String?,
    val status: TaskStatus, val error: Problem?, val startedAt: Long?, val finishedAt: Long?, val node: String?, val nodeProgress: Double?,
    val peakVramBytes: Long?, val peakRamBytes: Long?, val resourceSamples: Int)
@Serializable data class Task(val id: String, val projectId: String, val assetId: String?, val status: TaskStatus,
    val snapshot: Snapshot, val priority: Int, val createdAt: Long, val attempts: List<Attempt>, val dependencies: List<String>,
    val results: List<Media>, val cancelRequested: Boolean, val kind: String, val targetId: String, val writingResultId: String?)
@Serializable data class Accepted(val taskId: String, val status: TaskStatus)
@Serializable data class TaskUpdate(val taskId: String, val projectId: String, val attemptId: String,
    val status: TaskStatus, val stage: String, val node: String? = null, val nodeProgress: Double? = null, val overallProgress: Double? = null)
data class Event(val seq: Long, val payload: String)

interface Tasks {
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
    fun acknowledge(taskId: String, externalId: String?)
    fun running(taskId: String)
    fun uncertain(taskId: String, reason: String)
    fun confirmAbsent(taskId: String, attemptId: String)
    fun progress(taskId: String, node: String, fraction: Double)
    fun recordUsage(attemptId: String, vramUsed: Long?, ramUsed: Long?)
    fun finish(taskId: String, files: List<Media> = emptyList(), error: Problem? = null)
}
interface MediaLibrary {
    fun save(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media
}
