package studio.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.round

@Serializable data class FrameReview(val shotRevisionId: String, val expectedRevision: Int, val role: String = "first")
@Serializable data class RenderRequest(val shotRevisionId: String, val firstFrameId: String? = null, val workflowId: String,
    val workflowVersion: String, val parameters: JsonObject = JsonObject(emptyMap()), val lastFrameId: String? = null, val referenceVersionId: String? = null)
@Serializable data class RenderSelection(val shotRevisionId: String, val firstFrameId: String? = null, val lastFrameId: String? = null, val referenceVersionId: String? = null)
@Serializable data class BatchRequest(val boardId: String, val workflowId: String, val workflowVersion: String,
    val shots: List<RenderSelection>, val parameters: JsonObject = JsonObject(emptyMap()))
@Serializable data class BatchAccepted(val batchId: String, val taskIds: List<String>)
@Serializable data class RenderVersion(val id: String, val shotId: String, val shotRevisionId: String, val revision: Int,
    val firstFrameId: String?, val lastFrameId: String?, val referenceVersionId: String?, val taskId: String, val media: Media, val approvalStatus: String,
    val reviewRevision: Int, val stale: Boolean, val createdAt: Long)

// Recognize only the exported H3 template's frame-grid calculation; never evaluate user expressions.
fun plannedVideoFrames(graph: JsonObject): Int? {
    val h3 = graph.values.map { it.jsonObject }.singleOrNull { it["class_type"]?.jsonPrimitive?.content in
        setOf("MiniMaxH3ImageToVideo","MiniMaxH3ReferenceToVideo") } ?: return null
    fun sourceNode(node: JsonObject, input: String, port: Int): JsonObject? {
        val link = node["inputs"]?.jsonObject?.get(input) as? JsonArray ?: return null
        if (link.size != 2 || link[1].jsonPrimitive.intOrNull != port) return null
        return graph[link[0].jsonPrimitive.content] as? JsonObject
    }
    val calc = sourceNode(h3,"length",1) ?: return null
    if (calc["class_type"] != JsonPrimitive("ComfyMathExpression") ||
        calc["inputs"]?.jsonObject?.get("expression") != JsonPrimitive("max(5, round(a * 24)) + (5 - (max(5, round(a * 24)) % 17)) % 17")) return null
    val duration = sourceNode(calc,"values.a",0) ?: return null
    if (duration["class_type"] != JsonPrimitive("PrimitiveFloat")) return null
    val seconds = duration["inputs"]?.jsonObject?.get("value")?.jsonPrimitive?.doubleOrNull ?: return null
    if (seconds !in 0.0..600.0) return null
    val requested = maxOf(5,round(seconds * 24).toInt())
    return requested + Math.floorMod(5 - requested,17)
}
