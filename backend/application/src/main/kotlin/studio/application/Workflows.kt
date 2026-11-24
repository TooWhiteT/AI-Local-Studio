package studio.application

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.*
import studio.domain.acyclic

@Serializable data class Workflow(val id: String, val manifest: Manifest, val templateHash: String,
    val enabled: Boolean, val reason: String? = null, val engineVersion: String? = null)
data class Package(val manifest: Manifest, val graph: JsonObject, val path: String) {
    val hash: String get() = sha256(sortedJson(buildJsonObject {
        put("manifest", json.encodeToJsonElement(manifest)); put("graph", graph)
    }).toString().toByteArray())
}
interface WorkflowStore {
    fun register(pkg: Package, reason: String?, engineVersion: String?): Workflow
    fun list(mode: String): List<Workflow>
    fun workflow(id: String): Workflow
    fun graph(id: String): JsonObject
    fun disableMissing(mode: String, paths: Set<String>)
}
interface PackageLibrary {
    fun packages(): List<Package>
    val issues: List<String>
    fun publish(pkg: Package)
}
const val offlineReason = "引擎离线，请启动服务后重新检测"
@Serializable data class WorkflowEdit(val version: String, val title: String, val parameters: List<Binding>)
class WorkflowRegistry(val store: WorkflowStore, private val library: PackageLibrary, private val engine: ImageEngine) {
    @Volatile private var modeIssues: List<String> = emptyList()
    val issues get() = library.issues + modeIssues
    private val mutex = kotlinx.coroutines.sync.Mutex()
    suspend fun refresh(): List<Workflow> = locked {
        val (packages, otherModes) = library.packages().partition { it.manifest.mode == engine.mode }
        modeIssues = otherModes.map { "${it.path} · 模板声明的引擎模式与当前设置不符" }
        val (version, info) = try { engine.info().version to engine.objectInfo() }
            catch (e: java.util.concurrent.CancellationException) { throw e }
            catch (e: Exception) { null to null }
        for (pkg in packages) {
            val reason = if (info == null) offlineReason else try {
                validatePackage(pkg, engine.mode, info)
                null
            } catch (e: Exception) { e.message ?: "模板结构无效" }
            store.register(pkg, reason, version)
        }
        store.disableMissing(engine.mode, packages.map { it.path }.toSet())
        store.list(engine.mode)
    }
    suspend fun edit(id: String, edit: WorkflowEdit): Workflow = locked {
        val base = store.workflow(id)
        checkInput(base.manifest.mode == engine.mode && base.enabled, "模板当前不可编辑")
        if (store.list(engine.mode).any { it.manifest.id == base.manifest.id && it.manifest.version == edit.version })
            conflict("模板版本已发布")
        val original = base.manifest.parameters.associateBy { it.node to it.input }
        for (b in edit.parameters) {
            val allowed = original[b.node to b.input] ?: invalid("只能绑定原模板授权的输入")
            checkInput(b.type == allowed.type && (allowed.min == null || (b.min ?: Double.NEGATIVE_INFINITY) >= allowed.min!!) &&
                (allowed.max == null || (b.max ?: Double.POSITIVE_INFINITY) <= allowed.max!!), "新绑定超出原范围")
        }
        val manifest = base.manifest.copy(version = edit.version, title = edit.title, parameters = edit.parameters)
        val pkg = Package(manifest, store.graph(id), "${manifest.id}/${manifest.version}")
        val info = engine.info()
        validatePackage(pkg, engine.mode, engine.objectInfo())
        library.publish(pkg)
        store.register(pkg, null, info.version)
    }
    private suspend fun <T> locked(action: suspend () -> T): T {
        mutex.lock()
        try { return action() } finally { mutex.unlock() }
    }
}
private val segment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
fun validSegment(value: String) = segment.matches(value) && ".." !in value
private fun inputDescriptions(info: JsonObject, node: JsonObject): Map<String, JsonElement> {
    val desc = info[node["class_type"]?.jsonPrimitive?.content]?.jsonObject ?: invalid("引擎缺少节点 ${node["class_type"]}")
    val inputs = desc["input"]?.jsonObject ?: invalid("节点缺少输入定义")
    return inputs["required"]?.jsonObject.orEmpty() + inputs["optional"]?.jsonObject.orEmpty()
}
private fun choices(spec: JsonArray): JsonArray? = spec.firstOrNull() as? JsonArray ?:
    (spec.getOrNull(1) as? JsonObject)?.get("options") as? JsonArray

private fun validateLiteral(value: JsonElement, spec: JsonArray, field: String) {
    choices(spec)?.let { options ->
        checkInput(value in options, "节点输入 $field 的选项或模型不可用")
        return
    }
    val type = (spec.firstOrNull() as? JsonPrimitive)?.content ?: return
    if (type !in setOf("STRING", "INT", "FLOAT", "BOOLEAN")) return
    val literal = value as? JsonPrimitive ?: invalid("节点输入 $field 需要标量")
    val bounds = spec.getOrNull(1) as? JsonObject
    val binding = Binding(field, field, type, "", "", literal, bounds?.get("min")?.jsonPrimitive?.doubleOrNull,
        bounds?.get("max")?.jsonPrimitive?.doubleOrNull)
    checkValue(binding, literal)
}

fun validatePackage(pkg: Package, mode: String, info: JsonObject) {
    val m = pkg.manifest
    checkInput(validSegment(m.id) && validSegment(m.version) && m.title.isNotBlank() && m.title.length <= 120, "模板标识或名称无效")
    checkInput(m.mode == mode && m.capabilities == listOf("text-to-image"), "模板模式或能力不匹配")
    checkInput(m.estimatedVramMb in 0..32768 && m.outputs.isNotEmpty() && m.outputs.size <= 16, "资源估算或输出数量无效")
    checkInput(pkg.graph.isNotEmpty() && m.parameters.size <= 64, "空图或参数过多")
    val links = mutableMapOf<String, MutableList<String>>()
    for ((id, raw) in pkg.graph) {
        val node = raw as? JsonObject ?: invalid("需要 API 格式的节点图")
        val fields = inputDescriptions(info, node)
        val inputs = node["inputs"] as? JsonObject ?: invalid("节点 $id 缺少 inputs")
        val desc = info.getValue(node.getValue("class_type").jsonPrimitive.content).jsonObject
        checkInput(inputs.keys.containsAll(desc["input"]?.jsonObject?.get("required")?.jsonObject.orEmpty().keys), "节点 $id 缺少必要输入")
        for ((key, value) in inputs) {
            val spec = fields[key] as? JsonArray ?: invalid("节点 $id 输入 $key 不可用")
            if (value is JsonArray) {
                checkInput(value.size == 2, "节点引用无效")
                val upstream = value[0].jsonPrimitive.content
                val source = pkg.graph[upstream]?.jsonObject ?: invalid("节点引用不存在")
                val outputs = info[source["class_type"]?.jsonPrimitive?.content]?.jsonObject?.get("output") as? JsonArray
                val index = value[1].jsonPrimitive.intOrNull ?: invalid("输出索引无效")
                checkInput(outputs != null && index in outputs.indices, "引用的输出端口不存在")
                links.getOrPut(id) { mutableListOf() }.add(upstream)
            } else {
                validateLiteral(value, spec, "$id.$key")
            }
        }
    }
    checkInput(acyclic(links), "模板节点引用成环")
    val slots = m.parameters.map { it.node to it.input }
    checkInput(slots.distinct().size == slots.size && m.parameters.distinctBy { it.name }.size == slots.size, "参数绑定重复")
    for (p in m.parameters) {
        checkInput(validSegment(p.name) && p.label.isNotBlank() && p.label.length <= 100, "绑定名称无效")
        val node = pkg.graph[p.node] as? JsonObject ?: invalid("绑定节点不存在")
        val spec = inputDescriptions(info, node)[p.input] as? JsonArray ?: invalid("绑定输入不存在")
        checkInput(p.type in setOf("STRING", "INT", "FLOAT", "BOOLEAN") && spec[0] == JsonPrimitive(p.type) &&
            node["inputs"]?.jsonObject?.get(p.input) is JsonPrimitive, "只允许绑定已声明的标量输入")
        val bounds = spec.getOrNull(1) as? JsonObject
        val lo = bounds?.get("min")?.jsonPrimitive?.doubleOrNull
        val hi = bounds?.get("max")?.jsonPrimitive?.doubleOrNull
        if (p.type in setOf("INT", "FLOAT")) {
            checkInput(p.min != null && p.max != null && p.min!! <= p.max!! &&
                (lo == null || p.min!!.toDouble() >= lo) && (hi == null || p.max!!.toDouble() <= hi), "参数范围超出引擎约束")
        }
        checkValue(p, p.default)
    }
    for (model in m.models) {
        val n = pkg.graph[model.node]?.jsonObject ?: invalid("模型绑定节点不存在")
        val spec = inputDescriptions(info, n)[model.input] as? JsonArray ?: invalid("模型输入不存在")
        checkInput(n["inputs"]?.jsonObject?.get(model.input) == JsonPrimitive(model.name) &&
            choices(spec)?.contains(JsonPrimitive(model.name)) == true, "依赖模型不可用")
    }
    checkInput(m.outputs.distinct().size == m.outputs.size, "输出声明重复")
    for (out in m.outputs) {
        val node = pkg.graph[out.node]?.jsonObject ?: invalid("输出节点不存在")
        checkInput(out.field == "images" && info[node["class_type"]?.jsonPrimitive?.content]?.jsonObject?.get("output_node") == JsonPrimitive(true),
            "需要已声明的图片输出节点")
    }
}
private fun checkValue(b: Binding, value: JsonElement) {
    val p = value as? JsonPrimitive ?: invalid("参数 ${b.label} 需要标量")
    val ok = when (b.type) {
        "STRING" -> p.isString && p.content.length <= 8000
        "BOOLEAN" -> !p.isString && p.booleanOrNull != null
        "INT", "FLOAT" -> !p.isString && (b.type != "INT" || p.longOrNull != null) && p.doubleOrNull?.let {
            it.isFinite() && it >= (b.min ?: Double.NEGATIVE_INFINITY) && it <= (b.max ?: Double.POSITIVE_INFINITY)
        } == true
        else -> false
    }
    checkInput(ok, "参数 ${b.label} 的类型或范围无效")
}
fun parameters(manifest: Manifest, supplied: JsonObject): JsonObject = buildJsonObject {
    checkInput(supplied.keys.all { key -> manifest.parameters.any { it.name == key } }, "包含未注册的参数")
    for (b in manifest.parameters) {
        val v = supplied[b.name] ?: b.default
        checkValue(b, v)
        put(b.name, v)
    }
}
fun bind(graph: JsonObject, manifest: Manifest, supplied: JsonObject): JsonObject {
    val values = parameters(manifest, supplied)
    val result = graph.toMutableMap()
    for (p in manifest.parameters) {
        val node = result.getValue(p.node).jsonObject
        val inputs = node.getValue("inputs").jsonObject + (p.input to values.getValue(p.name))
        result[p.node] = JsonObject(node + ("inputs" to JsonObject(inputs)))
    }
    return JsonObject(result)
}
