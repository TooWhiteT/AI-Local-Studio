package studio.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class TextEngineInfo(val mode: String, val model: String?, val available: Boolean, val reason: String? = null,
    val version: String? = null, @kotlinx.serialization.Transient val supportsThinkingSwitch: Boolean = false)
@Serializable data class TextPrompt(val version: String, val system: String, val schema: JsonObject,
    val enableThinking: Boolean? = null)
data class TextCall(val kind: String, val model: String, val prompt: TextPrompt, val input: JsonObject)
sealed interface TextRun {
    data object Unsent : TextRun
    data object Active : TextRun
    data class Completed(val content: String, val completionId: String?) : TextRun
    data class Rejected(val code: String, val message: String) : TextRun
    data class Uncertain(val message: String) : TextRun
}
interface TextEngine {
    val identity: String
    val real: Boolean
    suspend fun info(): TextEngineInfo
    suspend fun idle(): Boolean
    suspend fun sleeping(): Boolean = false
    /** A durable attempt may be polled again across a process restart. */
    suspend fun advance(attempt: String, call: TextCall, allowStart: Boolean): TextRun
}
