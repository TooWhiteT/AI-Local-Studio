package studio.application

import kotlinx.serialization.json.*
import kotlin.test.*
import studio.core.*

class WritingTest {
    @Test fun singleSceneUsesDurationFromTheReferencedEpisode() {
        val outline = json.parseToJsonElement("""{"characters":[{"key":"c"}],"locations":[{"key":"l"}]}""").jsonObject
        val request = WritingRequest(0,sceneCount=1,linesPerScene=0)
        val schema = WritingPrompts.forTask(WritingKind.SCRIPT,request,outline,45000).schema
        val content = """{"title":"归还","scenes":[{"locationKey":"l","characters":["c"],"action":"开门","durationMs":45000,"dialogues":[]}]}"""
        validateShape(schema,json.parseToJsonElement(content),"script")
        assertFailsWith<StudioError> { validateShape(schema,json.parseToJsonElement(content.replace("45000","30000")),"script") }
        val unconstrained = WritingPrompts.forTask(WritingKind.SCRIPT,request.copy(sceneCount=null),outline,45000).schema
        validateShape(unconstrained,json.parseToJsonElement(content.replace("45000","30000")),"script")
    }

    @Test fun rejectsExtraFieldsWrongTypesAndBlankStrings() {
        val episode = buildJsonObject { put("title","雨夜"); put("summary","末班车返回"); put("targetDurationMs",60000) }
        fun plan(item: JsonObject) = buildJsonObject { put("episodes",JsonArray(listOf(item))) }
        validateWriting(WritingKind.EPISODE_PLAN,plan(episode),episodeCount=1)
        for (change in listOf("tool" to JsonPrimitive("run"), "targetDurationMs" to JsonPrimitive("60000"), "title" to JsonPrimitive(" "))) {
            assertFailsWith<StudioError> { validateWriting(WritingKind.EPISODE_PLAN,plan(JsonObject(episode+change))) }
        }
        assertFailsWith<StudioError> { validateWriting(WritingKind.EPISODE_PLAN,plan(episode),episodeCount=3) }
    }
}
