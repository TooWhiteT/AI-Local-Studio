package studio.infra

import java.io.ByteArrayInputStream
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.io.path.name
import studio.application.*
import studio.core.*
import studio.engine.InvalidOutput

class StudioLock(root: Path, mode: String, enginePort: Int, realWriter: Boolean = false) : AutoCloseable {
    private val channels = mutableListOf<FileChannel>()
    init {
        Files.createDirectories(root)
        // Real engines on different ports still share the workstation's GPU.
        val scopes = buildList {
            if (mode == "mock") add("mock-$enginePort")
            if (mode != "mock" || realWriter) add("gpu")
        }
        val user = sha256(System.getProperty("user.name").toByteArray()).take(16)
        try {
            for (path in listOf(root.resolve("studio.lock")) + scopes.map { Path.of(System.getProperty("java.io.tmpdir"),"local-ai-studio-$user-$it.lock") }) {
                val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
                channels += channel
                check(channel.tryLock() != null) { "数据目录或 GPU 调度器正由另一个 Studio 服务使用" }
            }
        } catch (e: Exception) { close(); throw e }
    }
    override fun close() { channels.asReversed().forEach { it.close() } }
}
class LocalMedia(storage: Path) : MediaLibrary {
    private val root = storage.toRealPath()
    private val staging = directory(root, "staging")
    private val projects = directory(root, "projects")
    private fun directory(parent: Path, segment: String): Path {
        val path = parent.resolve(segment)
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root))
            throw java.io.IOException("存储目录越界")
        return path
    }
    fun clean(db: StudioDb) {
        Files.newDirectoryStream(staging, "studio-*.part").use { files -> files.forEach { Files.deleteIfExists(it) } }
        // A crash can occur between a rename and the result transaction. Active attempts reuse their verified files.
        val terminal = db.tx { rows("SELECT id FROM task_attempts WHERE status IN ('FAILED','CANCELED','SUCCEEDED')") { getString(1) }.toSet() }
        val referenced = db.tx { rows("SELECT relative_path FROM media_files") { getString(1) }.toSet() }
        Files.walk(projects, 4).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { path ->
            val relative = root.relativize(path).joinToString("/")
            if (path.parent.name in terminal && path.parent.parent.name == "media" && path.name.matches(Regex("[0-9]+[.](png|jpg)")) && relative !in referenced)
                Files.deleteIfExists(path)
        } }
    }
    fun resolve(relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(projects) || !Files.isRegularFile(path, NOFOLLOW_LINKS) || !path.toRealPath().startsWith(projects)) missing("媒体文件不存在")
        return path
    }
    override fun save(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media {
        validId(projectId); validId(attemptId)
        check(ordinal in 0..15)
        if (bytes.size !in 1..32 * 1024 * 1024) throw InvalidOutput("图片大小无效")
        val (format, width, height) = try {
            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: throw InvalidOutput("无法解码图片")
                try {
                    reader.input = stream
                    val ext = when (reader.formatName.lowercase()) { "png" -> "png"; "jpeg", "jpg" -> "jpg"; else -> throw InvalidOutput("仅支持 PNG/JPEG") }
                    val w = reader.getWidth(0); val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w.toLong() * h > 16777216) throw InvalidOutput("图片像素尺寸超出限制")
                    reader.read(0).flush()
                    Triple(ext, w, h)
                } finally { reader.dispose() }
            }
        } catch (e: java.io.IOException) { throw InvalidOutput("图片解码失败") }
        val parent = directory(directory(directory(projects, projectId), "media"), attemptId)
        val dest = parent.resolve("$ordinal.$format")
        val hash = sha256(bytes)
        if (Files.exists(dest, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(dest, NOFOLLOW_LINKS) || Files.size(dest) != bytes.size.toLong() || sha256(Files.readAllBytes(dest)) != hash)
                throw InvalidOutput("已暂存的结果与引擎返回内容不符")
        } else {
            val tmp = Files.createTempFile(staging, "studio-", ".part")
            try {
                Files.write(tmp, bytes)
                FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(tmp) }
        }
        val relative = root.relativize(dest).joinToString("/")
        return Media(UUID.nameUUIDFromBytes(relative.toByteArray()).toString(), relative, hash,
            if (format == "png") "image/png" else "image/jpeg", bytes.size.toLong(), width, height)
    }
}
