package studio.infra

import studio.application.*
import studio.core.*
import java.sql.Connection
import java.sql.ResultSet

internal fun ResultSet.toMedia() = Media(getString("file_id"), getString("relative_path"), getString("sha256"),
    getString("mime"), getLong("size_bytes"), getInt("width"), getInt("height"),
    getLong("duration_ms").takeUnless { wasNull() }, getLong("frame_count").takeUnless { wasNull() },
    getDouble("fps").takeUnless { wasNull() })
private fun ResultSet.toProject() = Project(getString("id"), getString("title"), getString("genre"),
    getString("style"), getString("aspect_ratio"), getString("status"), getInt("revision"), getLong("created_at"))
internal fun Connection.getProject(id: String) = rows("SELECT * FROM projects WHERE id=?", id) { toProject() }
    .firstOrNull() ?: missing("项目不存在")
internal fun Connection.requireActive(id: String) {
    if (getProject(id).status != "ACTIVE") conflict("项目已归档")
}

class SqlCatalog(private val db: StudioDb) : Catalog {
    override fun create(input: ProjectInput) = db.tx {
        input.validate()
        val id = uid()
        update("INSERT INTO projects VALUES(?,?,?,?,?,'ACTIVE',1,?)", id, input.title.trim(), input.genre,
            input.style, input.aspectRatio, System.currentTimeMillis())
        getProject(id)
    }
    override fun project(id: String) = db.tx { getProject(id) }
    override fun projects(window: Window) = db.tx {
        window.page(rows("SELECT rowid,* FROM projects WHERE rowid<? ORDER BY rowid DESC LIMIT ?", window.before,
            window.limit + 1) { getLong("rowid") to toProject() })
    }
    override fun edit(id: String, edit: ProjectEdit) = db.tx {
        val p = getProject(id)
        if (p.revision != edit.expectedRevision) conflict("项目已被修改，请刷新")
        val input = ProjectInput(edit.title ?: p.title, edit.genre ?: p.genre, edit.style ?: p.style, edit.aspectRatio ?: p.aspectRatio)
        input.validate()
        val status = edit.status ?: p.status
        checkInput(status in setOf("ACTIVE", "ARCHIVED"), "项目状态无效")
        update("UPDATE projects SET title=?,genre=?,style=?,aspect_ratio=?,status=?,revision=revision+1 WHERE id=? AND revision=?",
            input.title.trim(), input.genre, input.style, input.aspectRatio, status, id, edit.expectedRevision)
        getProject(id)
    }
    override fun assets(projectId: String, window: Window) = db.tx {
        getProject(projectId)
        window.page(rows("SELECT rowid,id FROM assets WHERE project_id=? AND rowid<? ORDER BY rowid DESC LIMIT ?",
            projectId, window.before, window.limit + 1) { getLong(1) to getString(2) }.map { (row, id) -> row to getAsset(id) })
    }
    override fun createAsset(projectId: String, input: AssetInput) = db.tx {
        requireActive(projectId)
        checkInput(input.name.isNotBlank() && input.name.length <= 120, "资产名须为 1 至 120 个字符")
        checkInput(input.type in setOf("CHARACTER", "SCENE", "PROP", "COSTUME", "VOICE"), "资产类型无效")
        val id = uid()
        update("INSERT INTO assets(id,project_id,type,name,created_at) VALUES(?,?,?,?,?)",
            id, projectId, input.type, input.name.trim(), System.currentTimeMillis())
        getAsset(id)
    }
    override fun asset(id: String) = db.tx { getAsset(id) }
    override fun review(asset: String, version: String, expectedRevision: Int, approve: Boolean) = db.tx {
        val current = getAsset(asset)
        requireActive(current.projectId)
        val candidate = current.versions.find { it.id == version } ?: missing("该资产没有这个版本")
        if (current.reviewRevision != expectedRevision) conflict("资产审核已更新，请刷新")
        if (!approve && candidate.approvalStatus != "REVIEW_REQUIRED") conflict("仅待审核候选可以弃用")
        if (approve && candidate.approvalStatus == "ARCHIVED") conflict("该候选已弃用")
        update("UPDATE asset_versions SET approval_status=? WHERE id=?", if (approve) "APPROVED" else "ARCHIVED", version)
        update("UPDATE assets SET active_version_id=?,review_revision=review_revision+1 WHERE id=?",
            if (approve) version else current.activeVersionId, asset)
        getAsset(asset)
    }
    override fun media(id: String) = db.tx {
        rows("SELECT id AS file_id,* FROM media_files WHERE id=?", id) { toMedia() }.firstOrNull() ?: missing("媒体不存在")
    }
    private fun Connection.getAsset(id: String): Asset {
        val versions = rows("""SELECT v.id AS version_id,v.revision,v.approval_status,v.parent_version_id,v.file_id,f.*
            FROM asset_versions v JOIN media_files f ON f.id=v.file_id WHERE v.asset_id=? ORDER BY v.revision DESC""", id) {
            Version(getString("version_id"), getInt("revision"), getString("approval_status"), toMedia(), getString("parent_version_id"))
        }
        return rows("SELECT * FROM assets WHERE id=?", id) {
            Asset(id, getString("project_id"), getString("name"), getString("type"), versions, getLong("created_at"),
                getString("description"), getString("active_version_id"), getInt("review_revision"))
        }.firstOrNull() ?: missing("资产不存在")
    }
}
