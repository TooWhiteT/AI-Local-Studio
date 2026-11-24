package studio.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.TextPrompt

enum class BoardJob(val targetType: String, val folder: String) {
    ASSET_EXTRACT("PROJECT", "asset-extract"), SHOT_PLAN("EPISODE", "shot-plan"), SHOT_REGENERATE("SHOT", "shot-regenerate")
}
@Serializable data class BoardRequest(val scriptRevisionId: String, val expectedRevision: Int,
    val assetVersionIds: List<String> = emptyList(), val shotsPerScene: Int = 2, val brief: String = "")
@Serializable data class Reference(val assetId: String, val versionId: String, val name: String, val type: String,
    val revision: Int, val description: String, val file: Media, val isCurrent: Boolean, val visualPrompt: String? = null,
    val currentVersionId: String? = null, val needsReview: Boolean = false)
@Serializable data class Shot(val id: String, val revisionId: String, val revision: Int, val sceneId: String,
    val sceneOrdinal: Int, val ordinal: Int, val framing: String, val camera: String, val action: String, val durationMs: Int,
    val references: List<Reference>, val previews: List<Media>)
@Serializable data class Continuity(val wardrobe: String = "", val location: String = "", val injuries: String = "",
    val possessions: String = "", val knowledge: String = "")
@Serializable data class CharacterState(val characterKey: String, val revision: Int, val before: Continuity, val after: Continuity)
@Serializable data class StateEdit(val expectedRevision: Int, val before: Continuity, val after: Continuity)
@Serializable data class BoardScene(val id: String, val ordinal: Int, val action: String, val location: String,
    val cast: Map<String,String>, val states: List<CharacterState>)
@Serializable data class BoardSummary(val id: String, val revision: Int, val scriptRevisionId: String,
    val approvalStatus: String, val createdAt: Long)
@Serializable data class Board(val episodeId: String, val version: BoardSummary?, val stale: Boolean,
    val scenes: List<BoardScene>, val shots: List<Shot>)
@Serializable data class ShotEdit(val expectedRevision: Int, val framing: String? = null, val camera: String? = null,
    val action: String? = null, val durationMs: Int? = null, val assetVersionIds: List<String>? = null)
@Serializable data class ShotOrder(val expectedRevision: Int, val shotIds: List<String>)
@Serializable data class KeyframeRequest(val shotRevisionId: String, val workflowId: String, val workflowVersion: String,
    val parameters: JsonObject = JsonObject(emptyMap()))
@Serializable data class ExtractedAsset(val sceneOrdinal: Int, val assetId: String)
@Serializable data class Extraction(val id: String, val scriptRevisionId: String, val assets: List<ExtractedAsset>)

interface Storyboards {
    fun board(episode: String, version: String? = null): Board
    fun locate(shot: String, revision: String?): Board
    fun history(episode: String, window: Window): Page<BoardSummary>
    fun extraction(project: String, id: String): Extraction
    fun edit(shot: String, request: ShotEdit): Board
    fun reorder(episode: String, request: ShotOrder): Board
    fun approve(episode: String, board: String, expectedRevision: Int): Board
    fun state(scene: String, character: String, request: StateEdit): CharacterState
}

object StoryboardPrompts {
    private val instructions = BoardJob.entries.associateWith { job ->
        checkNotNull(javaClass.getResource("/${job.folder}/1.md")).readText().trim()
    }
    private fun string(max: Int) = buildJsonObject { put("type","string"); put("minLength",1); put("maxLength",max) }
    private fun obj(vararg fields: Pair<String,JsonObject>) = buildJsonObject {
        put("type","object"); put("properties",JsonObject(fields.toMap()))
        put("required",JsonArray(fields.map { JsonPrimitive(it.first) })); put("additionalProperties",false)
    }
    private fun array(items: JsonObject, min: Int, max: Int) = buildJsonObject {
        put("type","array"); put("items",items); put("minItems",min); put("maxItems",max)
    }
    fun forTask(kind: BoardJob, scenes: Int, count: Int, thinking: Boolean?): TextPrompt {
        val shot = obj("framing" to string(80),"camera" to string(160),"action" to string(800))
        val schema = when (kind) {
            BoardJob.ASSET_EXTRACT -> {
                val type = buildJsonObject { put("type","string"); put("enum",JsonArray(listOf("PROP","COSTUME","VOICE").map(::JsonPrimitive))) }
                val item = obj("type" to type,"name" to string(120),"description" to string(600))
                obj("scenes" to array(obj("items" to array(item,0,12)),scenes,scenes))
            }
            BoardJob.SHOT_PLAN -> obj("scenes" to array(obj("shots" to array(shot,count,count)),scenes,scenes))
            BoardJob.SHOT_REGENERATE -> shot
        }
        return TextPrompt("1",instructions.getValue(kind),schema,thinking)
    }
}
