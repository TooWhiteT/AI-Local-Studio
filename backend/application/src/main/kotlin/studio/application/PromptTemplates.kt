package studio.application

import kotlinx.serialization.json.JsonObject
import studio.core.json
import studio.engine.TextPrompt

internal object PromptTemplates {
    data class Template(val version: String, val system: String)

    private const val CONFIG_PATH = "/versions.json"
    private val folders = (WritingKind.entries.map { it.folder } + BoardJob.entries.map { it.folder }).toSet()
    private val active by lazy { load { path -> javaClass.getResource(path)?.readText() } }

    fun forTask(folder: String, schema: JsonObject, enableThinking: Boolean? = null): TextPrompt {
        val template = active.getValue(folder)
        return TextPrompt(template.version, template.system, schema, enableThinking)
    }

    fun load(readResource: (String) -> String?): Map<String, Template> {
        val config = requireNotNull(readResource(CONFIG_PATH)) { "缺少提示词版本配置 prompts$CONFIG_PATH" }
        val versions = json.decodeFromString<Map<String, String>>(config)
        val missing = folders - versions.keys
        val unknown = versions.keys - folders
        require(missing.isEmpty() && unknown.isEmpty()) {
            "提示词版本配置 prompts$CONFIG_PATH 的任务目录不匹配（缺少 $missing，未知 $unknown）"
        }

        return versions.mapValues { (folder, version) ->
            require(validSegment(version)) { "提示词 $folder 的版本号无效" }
            val path = "/$folder/$version.md"
            val system = requireNotNull(readResource(path)) { "缺少提示词模板 prompts$path" }.trim()
            require(system.isNotEmpty()) { "提示词模板 prompts$path 为空" }
            Template(version, system)
        }
    }
}
