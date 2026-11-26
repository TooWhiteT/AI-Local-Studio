package studio.application

import kotlinx.serialization.json.*
import kotlin.test.*
import studio.core.*

class StoryboardPromptTest {
    private val shot = buildJsonObject { put("framing","近景");put("camera","平视");put("action","收起票根") }

    @Test fun h3VersionThreeExamplesMatchTheWriterResponseSchemas() {
        val example = Regex("""```json\s+(.*?)\s*```""", RegexOption.DOT_MATCHES_ALL)
        for (kind in listOf(BoardJob.SHOT_PLAN, BoardJob.SHOT_REGENERATE)) {
            val template = assertNotNull(javaClass.getResource("/${kind.folder}/3.md")).readText()
            val response = assertNotNull(example.find(template), "缺少 ${kind.folder} 的 JSON 示例")
            val schema = StoryboardPrompts.forTask(kind, scenes = 1, count = 2, thinking = false).schema
            validateShape(schema, json.parseToJsonElement(response.groupValues[1]), kind.folder)
        }
    }

    @Test fun planFixesNumberOfScenesAndShotsAndRejectsUnexpectedFields() {
        val prompt=StoryboardPrompts.forTask(BoardJob.SHOT_PLAN,2,3,false)
        fun plan(scenes:Int,count:Int,value:JsonObject=shot)=buildJsonObject {
            put("scenes",buildJsonArray { repeat(scenes){ addJsonObject { put("shots",JsonArray(List(count){value})) } } })
        }
        validateShape(prompt.schema,plan(2,3),"plan")
        assertFailsWith<StudioError>{validateShape(prompt.schema,plan(1,3),"plan")}
        assertFailsWith<StudioError>{validateShape(prompt.schema,plan(2,2),"plan")}
        assertFailsWith<StudioError>{validateShape(prompt.schema,plan(2,3,JsonObject(shot+("assetVersionId" to JsonPrimitive(uid())))),"plan")}
        validateShape(StoryboardPrompts.forTask(BoardJob.SHOT_REGENERATE,2,3,null).schema,shot,"shot")
    }

    @Test fun extractedTypesAreLimitedAndScenesCanHaveNoExtraAssets() {
        val schema=StoryboardPrompts.forTask(BoardJob.ASSET_EXTRACT,1,2,false).schema
        fun scene(items:String)=json.parseToJsonElement("""{"scenes":[{"items":$items}]}""")
        validateShape(schema,scene("[]"),"assets")
        for(type in listOf("PROP","COSTUME","VOICE"))
            validateShape(schema,scene("""[{"type":"$type","name":"测试","description":"场次中的素材"}]"""),"assets")
        assertFailsWith<StudioError>{validateShape(schema,scene("""[{"type":"CHARACTER","name":"新人物","description":"无来源"}]"""),"assets")}
    }
}
