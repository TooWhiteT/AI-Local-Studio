package studio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
fun uid(): String = UUID.randomUUID().toString()
fun sha256(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
fun sortedJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { sortedJson(it.value) })
    is JsonArray -> JsonArray(value.map(::sortedJson))
    else -> value
}
class StudioError(val code: String, message: String) : RuntimeException(message)
fun invalid(message: String): Nothing = throw StudioError("VALIDATION_ERROR", message)
fun missing(message: String): Nothing = throw StudioError("NOT_FOUND", message)
fun conflict(message: String): Nothing = throw StudioError("CONFLICT", message)
fun unavailable(message: String): Nothing = throw StudioError("ENGINE_UNAVAILABLE", message)
fun checkInput(condition: Boolean, message: String) { if (!condition) invalid(message) }
fun validId(id: String): String {
    checkInput(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false), "需要有效的 UUID")
    return id
}

@Serializable data class Page<T>(val items: List<T>, val nextCursor: String? = null)
data class Window(val before: Long = Long.MAX_VALUE, val limit: Int = 30) {
    init { checkInput(before > 0 && limit in 1..100, "分页参数超出范围") }
    fun <T> page(rows: List<Pair<Long, T>>): Page<T> =
        Page(rows.take(limit).map { it.second }, if (rows.size > limit) rows[limit - 1].first.toString() else null)
}
