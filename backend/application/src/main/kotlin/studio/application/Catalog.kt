package studio.application

import kotlinx.serialization.Serializable
import studio.core.*

@Serializable data class ProjectInput(val title: String, val genre: String = "", val style: String = "", val aspectRatio: String = "9:16")
@Serializable data class ProjectEdit(val expectedRevision: Int, val title: String? = null, val genre: String? = null,
    val style: String? = null, val aspectRatio: String? = null, val status: String? = null)
@Serializable data class Project(val id: String, val title: String, val genre: String, val style: String,
    val aspectRatio: String, val status: String, val revision: Int, val createdAt: Long)
@Serializable data class AssetInput(val name: String, val type: String = "CHARACTER")
@Serializable data class Media(val id: String, val relativePath: String, val sha256: String, val mime: String,
    val sizeBytes: Long, val width: Int, val height: Int)
@Serializable data class Version(val id: String, val revision: Int, val approvalStatus: String, val file: Media, val parentVersionId: String? = null)
@Serializable data class Asset(val id: String, val projectId: String, val name: String, val type: String,
    val versions: List<Version>, val createdAt: Long, val description: String = "",
    val activeVersionId: String? = null, val reviewRevision: Int = 0)

interface Catalog {
    fun create(input: ProjectInput): Project
    fun project(id: String): Project
    fun projects(window: Window): Page<Project>
    fun edit(id: String, edit: ProjectEdit): Project
    fun assets(projectId: String, window: Window): Page<Asset>
    fun createAsset(projectId: String, input: AssetInput): Asset
    fun asset(id: String): Asset
    fun review(asset: String, version: String, expectedRevision: Int, approve: Boolean): Asset
    fun media(id: String): Media
}
fun ProjectInput.validate() {
    checkInput(title.isNotBlank() && title.trim().length <= 120, "项目名须为 1 至 120 个字符")
    checkInput(style.length <= 1000 && genre.length <= 100, "项目设置过长")
    checkInput(aspectRatio in setOf("9:16", "16:9", "1:1", "4:3"), "不支持此画幅")
}
