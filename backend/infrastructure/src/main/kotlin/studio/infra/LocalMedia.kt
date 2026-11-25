package studio.infra

import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.io.OutputStream
import javax.sound.sampled.*
import java.io.ByteArrayInputStream
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.io.path.name
import studio.application.*
import studio.core.*
import studio.engine.InvalidOutput
import studio.engine.MediaProbeUnavailable

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
        val exports = root.resolve("export-attempts")
        if (Files.isDirectory(exports, NOFOLLOW_LINKS)) Files.newDirectoryStream(exports).use { dirs ->
            for (dir in dirs) if (dir.name in terminal && Files.isDirectory(dir, NOFOLLOW_LINKS))
                Files.deleteIfExists(dir.resolve("output.mp4"))
        }
        val referenced = db.tx { rows("SELECT relative_path FROM media_files") { getString(1) }.toSet() }
        Files.walk(projects, 4).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { path ->
            val relative = root.relativize(path).joinToString("/")
            if (path.parent.name in terminal && path.parent.parent.name == "media" && path.name.matches(Regex("[0-9]+[.](png|jpg|mp4|wav)")) && relative !in referenced)
                Files.deleteIfExists(path)
        } }
    }
    fun resolve(relative: String): Path {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(projects) || !Files.isRegularFile(path, NOFOLLOW_LINKS) || !path.toRealPath().startsWith(projects.toRealPath())) missing("媒体文件不存在")
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
        return store(projectId, attemptId, ordinal, format, bytes) { id, relative, hash ->
            Media(id, relative, hash, if (format == "png") "image/png" else "image/jpeg", bytes.size.toLong(), width, height)
        }
    }
    override fun readFirstFrame(projectId: String, media: Media, width: Int, height: Int): ByteArray {
        validId(projectId)
        val path = resolve(media.relativePath)
        val expected = projects.toRealPath().resolve(projectId).resolve("media")
        if (!path.toRealPath().startsWith(expected) ||
            media.mime !in setOf("image/png", "image/jpeg") || media.sizeBytes !in 1L..32L * 1024 * 1024 ||
            Files.size(path) != media.sizeBytes) throw InvalidOutput("关键帧文件或项目归属无效")
        val bytes = Files.readAllBytes(path)
        if (sha256(bytes) != media.sha256) throw InvalidOutput("关键帧内容与固定版本不符")
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4194304)
        if (media.mime == "image/png" && !bytes.take(8).toByteArray().contentEquals(PNG_HEADER)) throw InvalidOutput("关键帧格式无效")
        if (media.mime == "image/png" && media.width == width && media.height == height) return bytes
        // Original dimensions and content were checked on import and are pinned in the task snapshot.
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw InvalidOutput("关键帧解码失败")
        try {
            val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val cropWidth = minOf(image.width, (image.height.toLong() * width / height).toInt()).coerceAtLeast(1)
            val cropHeight = minOf(image.height, (image.width.toLong() * height / width).toInt()).coerceAtLeast(1)
            val x = (image.width - cropWidth) / 2
            val y = (image.height - cropHeight) / 2
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(image, 0, 0, width, height, x, y, x + cropWidth, y + cropHeight, null)
            } finally { graphics.dispose() }
            return ByteArrayOutputStream().use { output ->
                ImageIO.write(target, "png", output)
                if (output.size() > 32 * 1024 * 1024) throw InvalidOutput("关键帧转 PNG 超出大小限制")
                output.toByteArray()
            }
        } finally { image.flush() }
    }
    override fun saveVideo(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media {
        validId(projectId); validId(attemptId)
        require(ordinal in 0..15)
        if (bytes.size !in 12..128 * 1024 * 1024 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp")
            throw InvalidOutput("视频结果需要有效的 MP4")
        val tmp = Files.createTempFile(staging, "studio-", ".part")
        try {
            Files.write(tmp, bytes)
            val v = videoInfo(tmp, false)
            return store(projectId, attemptId, ordinal, "mp4", bytes, tmp) { id, relative, hash ->
                Media(id, relative, hash, "video/mp4", bytes.size.toLong(), v.width, v.height, v.duration, v.frames, v.fps)
            }
        } finally { Files.deleteIfExists(tmp) }
    }
    private data class VideoInfo(val width: Int, val height: Int, val duration: Long, val frames: Long, val fps: Double)
    private fun videoInfo(tmp: Path, export: Boolean): VideoInfo {
        val command = mutableListOf("ffprobe", "-v", "error", "-protocol_whitelist", "file", "-f", "mov",
            "-threads", "1", "-max_pixels", "16777216", "-max_alloc", "67108864")
        if (!export) command += listOf("-select_streams", "v:0")
        command += listOf("-count_frames", "-show_entries",
            "stream=codec_type,codec_name,width,height,avg_frame_rate,nb_frames,nb_read_frames,duration,sample_rate,channels:format=duration",
            "-of", "json", "-i", tmp.toString())
        val probe = try { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch (_: java.io.IOException) { throw MediaProbeUnavailable() }
        val response = try {
            if (!probe.waitFor(if (export) 600 else 30, TimeUnit.SECONDS) || probe.exitValue() != 0) throw InvalidOutput("FFprobe 无法读取视频流")
            probe.inputStream.use { it.readNBytes(8193) }
        } finally { if (probe.isAlive) probe.destroyForcibly() }
        if (response.size !in 1..8192) throw InvalidOutput("FFprobe 视频信息无效")
        val metadata = try { json.parseToJsonElement(response.decodeToString()).jsonObject }
            catch (_: IllegalArgumentException) { throw InvalidOutput("FFprobe 视频信息无效") }
        val streams = (metadata["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val stream = streams.singleOrNull { it["codec_type"] == JsonPrimitive("video") } ?: throw InvalidOutput("缺少可解码的视频流")
        if (export) {
            val audio = streams.singleOrNull { it["codec_type"] == JsonPrimitive("audio") }
            if (stream["codec_name"] != JsonPrimitive("h264") || audio?.get("codec_name") != JsonPrimitive("aac") ||
                audio["sample_rate"]?.jsonPrimitive?.intOrNull != 48000 || audio["channels"]?.jsonPrimitive?.intOrNull != 2 ||
                (audio["nb_read_frames"]?.jsonPrimitive?.longOrNull ?: 0L) < 1)
                throw InvalidOutput("成片缺少 H.264 画面或可解码的 48 kHz 立体声 AAC 音轨")
        }
        val width = stream["width"]?.jsonPrimitive?.intOrNull ?: 0
        val height = stream["height"]?.jsonPrimitive?.intOrNull ?: 0
        val frames = stream["nb_read_frames"]?.jsonPrimitive?.longOrNull ?: 0
        val declaredFrames = stream["nb_frames"]?.jsonPrimitive?.longOrNull
        val rate = stream["avg_frame_rate"]?.jsonPrimitive?.content.orEmpty().split('/')
        val fps = (rate.getOrNull(0)?.toDoubleOrNull() ?: 0.0) / (rate.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
        val duration = stream["duration"]?.jsonPrimitive?.doubleOrNull ?: metadata["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (width <= 0 || height <= 0 || width.toLong() * height > 16777216 || frames !in (if (export) 1L..216000L else 2L..3600L) ||
            declaredFrames != null && declaredFrames != frames || !fps.isFinite() || fps !in 0.01..240.0 ||
            !duration.isFinite() || duration !in (if (export) 0.001..3601.0 else 0.001..120.0)) throw InvalidOutput("视频帧数、尺寸、时长或帧率无效")
        return VideoInfo(width, height, (duration * 1000).roundToLong(), frames, fps)
    }
    private fun fileHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
    override fun verifiedPath(projectId: String, media: Media): String {
        validId(projectId)
        val path = resolve(media.relativePath).toRealPath()
        if (!path.startsWith(projects.toRealPath().resolve(projectId).resolve("media")) || Files.size(path) != media.sizeBytes ||
            fileHash(path) != media.sha256) throw InvalidOutput("时间线素材与绑定的项目或版本不一致")
        return path.toString()
    }
    override fun saveAudio(projectId: String, attemptId: String, ordinal: Int, bytes: ByteArray): Media {
        validId(projectId); validId(attemptId); require(ordinal in 0..15)
        if (bytes.size !in 44..32*1024*1024 || String(bytes,0,4,Charsets.US_ASCII) != "RIFF" ||
            String(bytes,8,4,Charsets.US_ASCII) != "WAVE") throw InvalidOutput("语音结果需要 PCM WAV")
        val duration = try {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { input ->
                val format = input.format
                if (format.encoding !in setOf(AudioFormat.Encoding.PCM_SIGNED,AudioFormat.Encoding.PCM_UNSIGNED) ||
                    format.channels !in 1..2 || format.sampleRate !in 8000f..192000f || format.frameSize <= 0 ||
                    format.sampleSizeInBits !in setOf(8,16,24,32) || input.frameLength !in 1L..bytes.size.toLong() ||
                    input.transferTo(OutputStream.nullOutputStream()) != input.frameLength * format.frameSize)
                    throw InvalidOutput("WAV 声道、采样率或有效音频数据无效")
                (1000.0 * input.frameLength / format.frameRate).roundToLong().also { if (it <= 0) throw InvalidOutput("语音为空") }
            }
        } catch (_: UnsupportedAudioFileException) { throw InvalidOutput("无法解码 PCM WAV") }
        return store(projectId, attemptId, ordinal, "wav", bytes) { id, relative, hash ->
            Media(id, relative, hash, "audio/wav", bytes.size.toLong(), 0, 0, duration)
        }
    }
    override fun saveExport(projectId: String, attemptId: String, source: Path): Media {
        validId(projectId); validId(attemptId)
        val expected = root.toRealPath().resolve("export-attempts").resolve(attemptId).resolve("output.mp4")
        if (!Files.isRegularFile(source, NOFOLLOW_LINKS) || source.toRealPath() != expected)
            throw InvalidOutput("缺少该导出任务的受控输出")
        val size = Files.size(source)
        if (size !in 12L..8L*1024*1024*1024) throw InvalidOutput("导出文件大小无效")
        val info = videoInfo(source, true)
        val parent = directory(directory(directory(projects, projectId), "media"), attemptId)
        val dest = parent.resolve("0.mp4")
        val hash = fileHash(source)
        if (Files.exists(dest, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(dest, NOFOLLOW_LINKS) || Files.size(dest) != size || fileHash(dest) != hash)
                throw InvalidOutput("已入库导出与执行结果不符")
        } else {
            val tmp = Files.createTempFile(staging, "studio-", ".part")
            try {
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(tmp) }
        }
        val relative = root.relativize(dest).joinToString("/")
        return Media(UUID.nameUUIDFromBytes(relative.toByteArray()).toString(), relative, hash, "video/mp4", size, info.width, info.height,
            info.duration, info.frames, info.fps)
    }
    private fun store(projectId: String, attemptId: String, ordinal: Int, format: String, bytes: ByteArray,
        staged: Path? = null, makeMedia: (String, String, String) -> Media): Media {
        val parent = directory(directory(directory(projects, projectId), "media"), attemptId)
        val dest = parent.resolve("$ordinal.$format")
        val hash = sha256(bytes)
        if (Files.exists(dest, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(dest, NOFOLLOW_LINKS) || Files.size(dest) != bytes.size.toLong() || sha256(Files.readAllBytes(dest)) != hash)
                throw InvalidOutput("已暂存的结果与引擎返回内容不符")
        } else {
            val tmp = staged ?: Files.createTempFile(staging, "studio-", ".part")
            try {
                if (staged == null) Files.write(tmp, bytes)
                FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(tmp) }
        }
        val relative = root.relativize(dest).joinToString("/")
        return makeMedia(UUID.nameUUIDFromBytes(relative.toByteArray()).toString(), relative, hash)
    }
    private companion object {
        val PNG_HEADER = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
