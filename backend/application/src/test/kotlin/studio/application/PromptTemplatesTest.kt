package studio.application

import kotlinx.serialization.json.jsonObject
import kotlin.test.*
import studio.core.json

class PromptTemplatesTest {
    private fun resource(path: String): String? = javaClass.getResource(path)?.readText()
    private val versions = json.decodeFromString<Map<String, String>>(checkNotNull(resource("/versions.json")))

    private fun load(config: Map<String, String>, template: (String) -> String? = ::resource) =
        PromptTemplates.load { path -> if (path == "/versions.json") json.encodeToString(config) else template(path) }

    @Test fun allTextTasksUseTheConfiguredVersionAndTemplate() {
        val outline = json.parseToJsonElement("""{"characters":[{"key":"lin"}],"locations":[{"key":"pier"}]}""").jsonObject
        val writing = WritingKind.entries.associate { it.folder to WritingPrompts.forTask(it, WritingRequest(0), outline) }
        val boards = BoardJob.entries.associate { it.folder to StoryboardPrompts.forTask(it, 1, 2, false) }
        val prompts = writing + boards
        assertEquals(versions, prompts.mapValues { it.value.version })
        for ((folder, version) in versions) {
            assertEquals(checkNotNull(resource("/$folder/$version.md")).trim(), prompts.getValue(folder).system, folder)
        }
    }

    @Test fun changingOneVersionSelectsItsMarkdown() {
        val folder = BoardJob.SHOT_PLAN.folder
        val version = "test-release"
        val body = "新版分镜提示词"
        val templates = load(versions + (folder to version)) { path ->
            if (path == "/$folder/$version.md") body else resource(path)
        }
        assertEquals(PromptTemplates.Template(version, body), templates.getValue(folder))
    }

    @Test fun rejectsMissingAndUnknownTaskEntries() {
        val missing = assertFailsWith<IllegalArgumentException> { load(versions - "outline") }
        assertContains(missing.message.orEmpty(), "缺少 [outline]")
        val unknown = assertFailsWith<IllegalArgumentException> { load(versions + ("outlien" to "1")) }
        assertContains(unknown.message.orEmpty(), "未知 [outlien]")
    }

    @Test fun rejectsInvalidVersionsAndUnavailableTemplates() {
        val invalid = assertFailsWith<IllegalArgumentException> { load(versions + ("outline" to "../1")) }
        assertContains(invalid.message.orEmpty(), "版本号无效")
        val missing = assertFailsWith<IllegalArgumentException> { load(versions + ("outline" to "no-such-version")) }
        assertContains(missing.message.orEmpty(), "prompts/outline/no-such-version.md")
        val empty = assertFailsWith<IllegalArgumentException> {
            load(versions) { path -> if (path.startsWith("/outline/")) "  \n" else resource(path) }
        }
        assertContains(empty.message.orEmpty(), "为空")
    }
}
