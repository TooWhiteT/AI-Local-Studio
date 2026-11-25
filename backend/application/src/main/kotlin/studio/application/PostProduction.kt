package studio.application

import kotlinx.serialization.Serializable
import studio.core.Page
import studio.core.Window
import studio.engine.TtsInfo

@Serializable data class VoiceEdit(val expectedRevision: Int, val voiceId: String)
@Serializable data class VoiceBinding(val id: String, val projectId: String, val characterKey: String, val revision: Int,
    val voiceId: String, val voiceName: String, val engineId: String, val engineVersion: String, val createdAt: Long)
@Serializable data class SpeechRequest(val shotRevisionId: String, val lineOrdinal: Int, val voiceBindingId: String, val speed: Double = 1.0)
@Serializable data class SpeechOrigin(val scriptRevisionId: String, val lineOrdinal: Int, val speaker: String, val voiceBindingId: String)
@Serializable data class AudioTake(val id: String, val revision: Int, val voiceBindingId: String, val taskId: String,
    val media: Media, val stale: Boolean, val createdAt: Long)
@Serializable data class AudioLine(val ordinal: Int, val speaker: String, val text: String, val emotion: String,
    val selectedTakeId: String?, val reviewRevision: Int, val takes: List<AudioTake>)
@Serializable data class ShotAudio(val shotId: String, val shotRevisionId: String, val durationMs: Int,
    val selectedDurationMs: Long, val overrunMs: Long, val lines: List<AudioLine>)
@Serializable data class Caption(val startMs: Long, val durationMs: Long, val text: String)
@Serializable data class TimelineDialogue(val takeId: String, val startMs: Long = 0, val gain: Double = 1.0, val subtitle: String = "")
@Serializable data class TimelineClip(val shotId: String, val renderVersionId: String, val sourceInMs: Long, val durationMs: Long,
    val originalGain: Double = 1.0, val dialogues: List<TimelineDialogue> = emptyList(), val captions: List<Caption> = emptyList())
@Serializable data class TimelineEdit(val expectedRevision: Int, val boardId: String, val clips: List<TimelineClip>)
@Serializable data class TimelineVersion(val id: String, val episodeId: String, val boardId: String, val revision: Int,
    val aspectRatio: String, val fps: Int, val durationMs: Long, val clips: List<TimelineClip>, val stale: Boolean, val createdAt: Long)
@Serializable data class TimelineView(val episodeId: String, val current: TimelineVersion?)
@Serializable data class ExportRequest(val timelineRevisionId: String, val resolution: String = "1080p")
@Serializable data class ExportVersion(val id: String, val timelineRevisionId: String, val taskId: String, val resolution: String,
    val media: Media, val createdAt: Long)
@Serializable data class ExportPlan(val width: Int, val height: Int, val fps: Int, val clips: List<ExportClip>)
@Serializable data class ExportClip(val video: Media, val sourceInMs: Long, val durationMs: Long, val originalGain: Double,
    val dialogues: List<ExportDialogue>, val captions: List<Caption> = emptyList())
@Serializable data class ExportDialogue(val audio: Media, val startMs: Long, val gain: Double, val subtitle: String)
@Serializable data class ExportCapability(val available: Boolean, val version: String?, val reason: String? = null)

interface PostProduction {
    fun voices(project: String): List<VoiceBinding>
    fun bind(project: String, character: String, edit: VoiceEdit, engineId: String, info: TtsInfo): VoiceBinding
    fun audio(shot: String, revision: String?): ShotAudio
    fun select(shot: String,take: String,expectedRevision: Int): ShotAudio
    fun timeline(episode: String, revision: String?): TimelineView
    fun history(episode: String, window: Window): Page<TimelineVersion>
    fun save(episode: String, edit: TimelineEdit): TimelineVersion
    fun exports(episode: String, window: Window): Page<ExportVersion>
}
