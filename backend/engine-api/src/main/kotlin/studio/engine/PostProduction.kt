package studio.engine

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable data class TtsVoice(val id: String, val name: String, val modelVersion: String? = null)
@Serializable data class TtsInfo(val mode: String, val version: String, val device: String, val voices: List<TtsVoice>,
    val available: Boolean = true, val reason: String? = null) {
    fun modelVersionFor(voiceId: String): String? = voices.find { it.id == voiceId }?.let { it.modelVersion ?: version }
}
@Serializable data class SpeechCall(val text: String, val voiceId: String, val speed: Double = 1.0, val modelVersion: String? = null)

sealed interface SpeechRun {
    data object Active : SpeechRun
    data object Cancelled : SpeechRun
    data class Completed(val wav: ByteArray) : SpeechRun
    data class Failed(val code: String, val message: String) : SpeechRun
}
interface TtsEngine {
    val identity: String
    suspend fun releaseModels()
    suspend fun info(): TtsInfo
    suspend fun advance(attempt: String, call: SpeechCall, cancel: Boolean): SpeechRun
}

data class Movie(val width: Int, val height: Int, val fps: Int, val clips: List<MovieClip>)
data class MovieClip(val video: String, val sourceInMs: Long, val durationMs: Long, val originalGain: Double,
    val dialogue: List<MovieAudio>, val captions: List<MovieCaption>)
data class MovieAudio(val path: String, val startMs: Long, val gain: Double)
data class MovieCaption(val startMs: Long, val durationMs: Long, val text: String)
sealed interface ExportRun {
    data object Active : ExportRun
    data object Cancelled : ExportRun
    data class Completed(val path: Path) : ExportRun
    data class Failed(val code: String, val message: String) : ExportRun
}
interface ExportEngine {
    val identity: String
    suspend fun version(): String
    fun releaseResult(attempt: String) {}
    suspend fun advance(attempt: String, cancel: Boolean, plan: () -> Movie): ExportRun
}
