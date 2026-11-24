package studio.infra

import kotlinx.serialization.json.*
import studio.application.*
import studio.core.*
import studio.engine.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.sql.ResultSet
import java.util.logging.Logger
import kotlin.io.path.*

class WorkflowFiles(private val root: Path) : PackageLibrary {
    @Volatile override var issues: List<String> = emptyList()
        private set
    init { Files.createDirectories(root) }
    override fun packages(): List<Package> {
        val packages = mutableListOf<Package>()
        val problems = mutableListOf<String>()
        Files.walk(root, 3).use { paths ->
            paths.filter { root.relativize(it).nameCount == 3 && it.fileName.toString() == "manifest.json" }
                .sorted().forEach { file ->
                    runCatching {
                        val dir = file.parent
                        val key = dir.parent.name
                        val version = dir.name
                        checkInput(validSegment(key) && validSegment(version), "目录名无效")
                        fun read(name: String): String {
                            val path = dir.resolve(name)
                            checkInput(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().startsWith(root.toRealPath()) &&
                                Files.size(path) <= 4_000_000, "模板文件无效或过大")
                            return path.readText()
                        }
                        val manifest = json.decodeFromString<Manifest>(read("manifest.json"))
                        checkInput(manifest.id == key && manifest.version == version, "模板标识和目录不一致")
                        packages += Package(manifest, json.parseToJsonElement(read("workflow.json")).jsonObject, "$key/$version")
                    }.onFailure {
                        val reason = "${root.relativize(file)} · ${it.message?.take(240)}"
                        problems += reason
                        Logger.getLogger("workflows").warning(reason)
                    }
                }
        }
        issues = problems
        return packages
    }
    override fun publish(pkg: Package) {
        checkInput(validSegment(pkg.manifest.id) && validSegment(pkg.manifest.version), "版本标识无效")
        val parent = root.resolve(pkg.manifest.id)
        checkInput(Files.isDirectory(parent, NOFOLLOW_LINKS) && parent.toRealPath().startsWith(root.toRealPath()), "模板目录无效")
        val dest = parent.resolve(pkg.manifest.version)
        if (Files.exists(dest, NOFOLLOW_LINKS)) conflict("模板版本已存在")
        val tmp = Files.createTempDirectory(parent, ".publish-")
        try {
            tmp.resolve("manifest.json").writeText(json.encodeToString(pkg.manifest))
            tmp.resolve("workflow.json").writeText(pkg.graph.toString())
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (Files.exists(tmp)) {
                Files.deleteIfExists(tmp.resolve("manifest.json"))
                Files.deleteIfExists(tmp.resolve("workflow.json"))
                Files.deleteIfExists(tmp)
            }
        }
    }
}

class SqlWorkflows(private val db: StudioDb) : WorkflowStore {
    private fun ResultSet.dto() = Workflow(getString("id"), json.decodeFromString<Manifest>(getString("manifest_json")),
        getString("template_hash"), getBoolean("enabled"), getString("reason"), getString("engine_version"))
    override fun register(pkg: Package, reason: String?, engineVersion: String?) = db.tx {
        val m = pkg.manifest
        val old = rows("SELECT * FROM workflows WHERE mode=? AND workflow_key=? AND version=?", m.mode, m.id, m.version) { dto() }.firstOrNull()
        val id = old?.id ?: uid()
        val problem = if (old != null && old.templateHash != pkg.hash) "已发布版本的内容发生变化，请用新版本发布" else reason
        if (old == null) {
            update("""INSERT INTO workflows(id,workflow_key,version,mode,manifest_path,manifest_json,graph_json,template_hash,
                enabled,reason,engine_version,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", id, m.id, m.version, m.mode, pkg.path,
                json.encodeToString(m), pkg.graph.toString(), pkg.hash, problem == null, problem, engineVersion, System.currentTimeMillis())
        } else update("UPDATE workflows SET enabled=?,reason=?,engine_version=? WHERE id=?", problem == null, problem, engineVersion, id)
        rows("SELECT * FROM workflows WHERE id=?", id) { dto() }.single()
    }
    override fun list(mode: String) = db.tx { rows("SELECT * FROM workflows WHERE mode=? ORDER BY rowid DESC", mode) { dto() } }
    override fun workflow(id: String) = db.tx { rows("SELECT * FROM workflows WHERE id=?", id) { dto() }.firstOrNull() ?: missing("模板不存在") }
    override fun graph(id: String) = db.tx { rows("SELECT graph_json FROM workflows WHERE id=?", id) {
        json.parseToJsonElement(getString(1)).jsonObject
    }.firstOrNull() ?: missing("模板不存在") }
    override fun disableMissing(mode: String, paths: Set<String>) {
        db.tx {
            rows("SELECT id,manifest_path FROM workflows WHERE mode=?", mode) { getString(1) to getString(2) }
                .filter { it.second !in paths }.forEach { update("UPDATE workflows SET enabled=0,reason=? WHERE id=?", "未找到原模板文件", it.first) }
        }
    }
}
