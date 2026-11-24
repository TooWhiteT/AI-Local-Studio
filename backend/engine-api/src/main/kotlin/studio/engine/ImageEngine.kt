package studio.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.Closeable

@Serializable data class Binding(val name: String, val label: String, val type: String, val node: String,
    val input: String, val default: JsonPrimitive, val min: Double? = null, val max: Double? = null)
@Serializable data class ModelDependency(val node: String, val input: String, val name: String)
@Serializable data class ImageOutput(val node: String, val field: String = "images")
@Serializable data class Manifest(val id: String, val version: String, val title: String, val mode: String,
    val capabilities: List<String>, val parameters: List<Binding>, val outputs: List<ImageOutput>,
    val models: List<ModelDependency>, val estimatedVramMb: Int)
@Serializable data class EngineInfo(val mode: String, val version: String, val available: Boolean = true,
    val reason: String? = null, val vramTotal: Long? = null, val vramFree: Long? = null,
    val ramTotal: Long? = null, val ramFree: Long? = null)
data class QueueEntry(val id: String, val clientId: String?, val running: Boolean)
data class OutputRef(val filename: String, val subfolder: String, val type: String)
sealed interface History {
    data class Success(val files: List<OutputRef>) : History
    data class Failure(val code: String, val message: String) : History
}
data class NodeProgress(val promptId: String, val node: String, val fraction: Double)

interface ProgressStream : Closeable {
    val open: Boolean
    fun latest(): NodeProgress?
}
class PromptRejected(message: String) : Exception(message)
class InvalidOutput(message: String) : Exception(message)

interface ImageEngine : Closeable {
    suspend fun releaseModels()
    suspend fun resourcesForSwitch(): EngineInfo = info()
    val mode: String
    val identity: String
    suspend fun info(): EngineInfo
    suspend fun objectInfo(): JsonObject
    suspend fun queue(): List<QueueEntry>
    suspend fun listen(clientId: String): ProgressStream
    suspend fun submit(clientId: String, graph: JsonObject): String
    suspend fun history(id: String, outputs: List<ImageOutput>): History?
    suspend fun findPrompt(clientId: String): String?
    suspend fun cancel(entry: QueueEntry)
    suspend fun download(ref: OutputRef): ByteArray
}
