package studio.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.TextPrompt

enum class WritingKind(val folder: String) { OUTLINE("outline"), EPISODE_PLAN("episode-plan"), SCRIPT("script") }

@Serializable data class WritingRequest(val expectedRevision: Int, val brief: String = "", val storyRevisionId: String? = null,
    val planId: String? = null, val episodeCount: Int = 3, val targetDurationMs: Int = 60000,
    val characterCount: Int? = null, val locationCount: Int? = null, val sceneCount: Int? = null, val linesPerScene: Int? = null)
@Serializable data class WritingEdit(val expectedRevision: Int, val content: JsonObject, val storyRevisionId: String? = null,
    val planId: String? = null)
@Serializable data class WritingApproval(val expectedRevision: Int)
@Serializable data class WritingRevision(val id: String, val revision: Int, val content: JsonObject, val approvalStatus: String,
    val stale: Boolean, val storyRevisionId: String?, val planId: String?, val sourceAttemptId: String?, val createdAt: Long)
@Serializable data class Episode(val id: String, val ordinal: Int, val title: String, val summary: String, val targetDurationMs: Int)
@Serializable data class EpisodeDirectory(val plan: WritingRevision?, val episodes: List<Episode>, val retired: List<Episode>)

interface WritingStore {
    fun revisions(kind: WritingKind, target: String, window: Window): Page<WritingRevision>
    fun episodes(project: String): EpisodeDirectory
    fun save(kind: WritingKind, target: String, edit: WritingEdit): WritingRevision
    fun approve(kind: WritingKind, target: String, revisionId: String, expected: Int): WritingRevision
}

object WritingPrompts {
    val templates = WritingKind.entries.associateWith { kind ->
        fun resource(path: String) = checkNotNull(javaClass.getResource(path)).readText()
        TextPrompt("1", resource("/${kind.folder}/1.md").trim(), json.parseToJsonElement(resource("/writing/${kind.name}.json")).jsonObject)
    }

    private fun JsonObject.updateAt(path: String, change: (JsonObject) -> JsonObject): JsonObject {
        val key = path.substringBefore('.')
        val child = getValue(key).jsonObject
        val tail = path.substringAfter('.', "")
        return JsonObject(this + (key to if (tail.isEmpty()) change(child) else child.updateAt(tail, change)))
    }

    fun forTask(kind: WritingKind, request: WritingRequest, outline: JsonObject?, episodeDurationMs: Int? = null): TextPrompt {
        val template = templates.getValue(kind)
        var schema = template.schema
        fun count(path: String, size: Int?, allowed: IntRange) {
            if (size == null) return
            checkInput(size in allowed, "生成数量超出范围 ${allowed.first}–${allowed.last}")
            schema = schema.updateAt(path) { JsonObject(it + mapOf("minItems" to JsonPrimitive(size), "maxItems" to JsonPrimitive(size))) }
        }
        fun choices(path: String, group: String) {
            val keys = checkNotNull(outline).getValue(group).jsonArray.map { it.jsonObject.getValue("key") }
            schema = schema.updateAt(path) { buildJsonObject { put("type", "string"); put("enum", JsonArray(keys)) } }
        }
        val scene = "properties.scenes.items.properties"
        when (kind) {
            WritingKind.OUTLINE -> {
                count("properties.characters", request.characterCount, 1..12)
                count("properties.locations", request.locationCount, 1..12)
            }
            WritingKind.EPISODE_PLAN -> {
                count("properties.episodes", request.episodeCount, 1..12)
                schema = schema.updateAt("properties.episodes.items.properties.targetDurationMs") {
                    JsonObject(it + ("const" to JsonPrimitive(request.targetDurationMs)))
                }
            }
            WritingKind.SCRIPT -> {
                count("properties.scenes", request.sceneCount, 1..30)
                count("$scene.dialogues", request.linesPerScene, 0..40)
                if (request.sceneCount == 1 && episodeDurationMs != null) {
                    schema = schema.updateAt("$scene.durationMs") { JsonObject(it + ("const" to JsonPrimitive(episodeDurationMs))) }
                }
                choices("$scene.locationKey", "locations")
                choices("$scene.characters.items", "characters")
                choices("$scene.dialogues.items.properties.speaker", "characters")
            }
        }
        return template.copy(schema = schema)
    }

}

// The schema is bundled with Studio and is also passed to the model for constrained decoding.
fun validateShape(schema: JsonObject, value: JsonElement, path: String) {
    fun bound(key: String) = schema.getValue(key).jsonPrimitive.int
    fun requireShape(ok: Boolean) = checkInput(ok, "字段 $path 不符合编剧模板约束")
    schema["const"]?.let { requireShape(value == it); return }
    schema["enum"]?.let { allowed -> requireShape(value in allowed.jsonArray); return }
    when (schema.getValue("type").jsonPrimitive.content) {
        "object" -> {
            val obj = value as? JsonObject ?: invalid("$path 需要对象")
            val fields = schema.getValue("properties").jsonObject
            requireShape(obj.keys == fields.keys)
            for ((key, spec) in fields) validateShape(spec.jsonObject, obj.getValue(key), "$path.$key")
        }
        "array" -> {
            val array = value as? JsonArray ?: invalid("$path 需要列表")
            requireShape(array.size in bound("minItems")..bound("maxItems"))
            array.forEachIndexed { i, item -> validateShape(schema.getValue("items").jsonObject, item, "$path[$i]") }
        }
        "string" -> {
            val p = value as? JsonPrimitive ?: invalid("$path 需要文本")
            requireShape(p.isString && p.content.trim().length >= bound("minLength") && p.content.length <= bound("maxLength"))
            schema["pattern"]?.let { requireShape(Regex(it.jsonPrimitive.content).matches(p.content)) }
        }
        "integer" -> {
            val p = value as? JsonPrimitive ?: invalid("$path 需要整数")
            requireShape(!p.isString && p.intOrNull in bound("minimum")..bound("maximum"))
        }
        else -> error("未支持的编剧模板类型")
    }
}
fun validateWriting(kind: WritingKind, content: JsonObject, outline: JsonObject? = null, episodeCount: Int? = null) {
    validateShape(WritingPrompts.templates.getValue(kind).schema, content, kind.folder)
    fun keys(doc: JsonObject, name: String) = doc.getValue(name).jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content }
    when (kind) {
        WritingKind.OUTLINE -> for (name in listOf("characters","locations")) {
            val ids = keys(content, name)
            checkInput(ids.distinct().size == ids.size, "$name 中存在重复标识")
        }
        WritingKind.EPISODE_PLAN -> if (episodeCount != null) checkInput(content.getValue("episodes").jsonArray.size == episodeCount, "生成的分集数与请求不符")
        WritingKind.SCRIPT -> {
            val story = checkNotNull(outline)
            val cast = keys(story,"characters").toSet()
            val locations = keys(story,"locations").toSet()
            for (row in content.getValue("scenes").jsonArray) {
                val scene = row.jsonObject
                val onSet = scene.getValue("characters").jsonArray.map { it.jsonPrimitive.content }
                checkInput(scene.getValue("locationKey").jsonPrimitive.content in locations, "场次引用了大纲中不存在的场景")
                checkInput(onSet.distinct().size == onSet.size && cast.containsAll(onSet), "出场角色重复或未在大纲声明")
                checkInput(scene.getValue("dialogues").jsonArray.all { it.jsonObject.getValue("speaker").jsonPrimitive.content in onSet }, "对白引用了本场未出场的角色")
            }
        }
    }
}
