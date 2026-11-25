package studio.media

import studio.core.*
import studio.engine.*
import java.io.DataOutputStream
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.TimeUnit

class FfmpegEngine(storage: Path) : ExportEngine {
    override val identity = "ffmpeg@local"
    private val root = storage.toRealPath().resolve("export-attempts").also {
        Files.createDirectories(it); require(Files.isDirectory(it, NOFOLLOW_LINKS))
    }
    private var child: Process? = null
    private var childId: String? = null

    private fun probe(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            // Version and individual stream queries produce bounded output.
            check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { "媒体工具无法读取版本或本地轨道" }
            return process.inputStream.use { it.readNBytes(8192).decodeToString().trim() }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
    override suspend fun version(): String {
        probe("ffprobe", "-version")
        return probe("ffmpeg", "-version").lineSequence().first()
    }

    override fun releaseResult(attempt: String) {
        val dir = root.resolve(validId(attempt))
        if (Files.isDirectory(dir, NOFOLLOW_LINKS)) Files.deleteIfExists(dir.resolve("output.mp4"))
    }
    override suspend fun advance(attempt: String, cancel: Boolean, plan: () -> Movie): ExportRun = synchronized(this) {
        val dir = root.resolve(validId(attempt))
        Files.createDirectories(dir); require(Files.isDirectory(dir, NOFOLLOW_LINKS))
        fun exists(name: String) = Files.isRegularFile(dir.resolve(name), NOFOLLOW_LINKS)
        if (cancel && !exists("cancel")) Files.createFile(dir.resolve("cancel"))
        if (childId == attempt && child?.isAlive == true || ExportWorker.busy(dir)) return@synchronized ExportRun.Active
        if (exists("cancel")) return@synchronized ExportRun.Cancelled
        if (exists("output.mp4")) return@synchronized ExportRun.Completed(dir.resolve("output.mp4"))
        if (exists("failed") || exists("started") || childId == attempt)
            return@synchronized ExportRun.Failed("EXPORT_FAILED", "FFmpeg 执行失败，请检查磁盘空间、素材与编码器")
        try {
            if (!exists("args.bin")) {
                val args = arguments(dir, plan())
                val temp = Files.createTempFile(dir, "args-", ".part")
                try {
                    DataOutputStream(Files.newOutputStream(temp)).use { out -> out.writeInt(args.size); args.forEach(out::writeUTF) }
                    Files.move(temp, dir.resolve("args.bin"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temp) }
            }
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val worker = ExportWorker::class.java
            val classes = Path.of(worker.protectionDomain.codeSource.location.toURI())
            child = ProcessBuilder(java.toString(), "-Xmx48m", "-cp", classes.toString(), worker.name, dir.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            childId = attempt
            ExportRun.Active
        } catch (e: Exception) { ExportRun.Failed("EXPORT_INPUT_ERROR", e.message?.take(280) ?: "无法准备导出素材") }
    }

    private fun arguments(dir: Path, movie: Movie): List<String> {
        require(movie.width in 2..4096 && movie.height in 2..4096 && movie.width % 2 == 0 && movie.height % 2 == 0 &&
            movie.fps in 1..60 && movie.clips.size in 1..256)
        val args = mutableListOf("-nostdin", "-y", "-loglevel", "error", "-filter_complex_threads", "2")
        var input = 0
        fun addFile(path: String, vararg options: String): Int {
            require(Path.of(path).isAbsolute && Files.isRegularFile(Path.of(path), NOFOLLOW_LINKS))
            args += listOf("-protocol_whitelist","file", "-threads", "1")
            args += options
            args += listOf("-i", path)
            return input++
        }
        val filters = mutableListOf<String>()
        val captions = mutableListOf<MovieCaption>()
        val audioStreams = mutableMapOf<String, Boolean>()
        var cursor = 0L
        for ((i, clip) in movie.clips.withIndex()) {
            require(clip.durationMs in 1..600000 && clip.sourceInMs in 0..600000 && clip.originalGain in 0.0..4.0)
            val seconds = (clip.durationMs / 1000.0).toString()
            // Derive each cut from cumulative frame boundaries so fractional clips never accumulate rounding drift.
            fun frameAt(timeMs: Long) = (timeMs * movie.fps + 500) / 1000
            val frames = frameAt(cursor + clip.durationMs) - frameAt(cursor)
            require(frames > 0) { "片段短于时间线的一帧" }
            val v = addFile(clip.video, "-ss", (clip.sourceInMs / 1000.0).toString(), "-t", ((clip.durationMs + 250) / 1000.0).toString())
            filters += "[${v}:v:0]setpts=PTS-STARTPTS,fps=${movie.fps},scale=${movie.width}:${movie.height}:force_original_aspect_ratio=decrease:force_divisible_by=2," +
                "pad=${movie.width}:${movie.height}:(ow-iw)/2:(oh-ih)/2,setsar=1,format=yuv420p,tpad=stop_mode=clone:stop_duration=0.25,trim=end_frame=$frames[v$i]"
            filters += "anullsrc=r=48000:cl=stereo,atrim=duration=$seconds[s$i]"
            val mix = mutableListOf("[s$i]")
            val hasAudio = clip.originalGain > 0 && audioStreams.getOrPut(clip.video) {
                probe("ffprobe","-v","error","-protocol_whitelist","file","-select_streams","a:0","-show_entries","stream=index",
                    "-of","csv=p=0","-i",clip.video).isNotBlank()
            }
            if (hasAudio) {
                filters += "[${v}:a:0]asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=${clip.originalGain}[o$i]"
                mix += "[o$i]"
            }
            for ((j, line) in clip.dialogue.withIndex()) {
                require(line.startMs in 0 until clip.durationMs && line.gain in 0.0..4.0)
                val a = addFile(line.path)
                filters += "[${a}:a:0]asetpts=PTS-STARTPTS,aresample=48000,aformat=channel_layouts=stereo,volume=${line.gain},adelay=${line.startMs}:all=1[d${i}_$j]"
                mix += "[d${i}_$j]"
            }
            if (mix.size == 1) filters += "[s$i]anull[a$i]"
            else filters += mix.joinToString("") + "amix=inputs=${mix.size}:duration=first:normalize=0,atrim=duration=$seconds[a$i]"
            for (c in clip.captions) {
                require(c.startMs in 0 until clip.durationMs && c.durationMs in 1..(clip.durationMs-c.startMs))
                if (c.text.isNotBlank()) captions += c.copy(startMs = cursor + c.startMs)
            }
            cursor += clip.durationMs
        }
        require(cursor <= 3600000)
        filters += movie.clips.indices.joinToString("") { "[v$it]" } +
            "concat=n=${movie.clips.size}:v=1:a=0,settb=1/${movie.fps},setpts=N[video]"
        filters += movie.clips.indices.joinToString("") { "[a$it]" } + "concat=n=${movie.clips.size}:v=0:a=1[audio]"
        val graph = dir.resolve("filters.txt")
        Files.writeString(graph, filters.joinToString(";\n"))
        val subtitle = if (captions.isEmpty()) null else {
            val file = dir.resolve("subtitles.srt")
            Files.newBufferedWriter(file).use { writeSubtitles(it, captions) }
            addFile(file.toString(), "-f", "srt")
        }
        args += listOf("-filter_complex_script", graph.toString(), "-map", "[video]", "-map", "[audio]")
        if (subtitle != null) args += listOf("-map", "${subtitle}:s:0", "-c:s", "mov_text", "-metadata:s:s:0", "language=zho",
            "-disposition:s:0", "default")
        args += listOf("-r", movie.fps.toString(), "-fps_mode", "cfr", "-c:v","libx264","-threads","2","-preset","veryfast","-crf","19","-pix_fmt","yuv420p",
            "-c:a","aac","-b:a","160k","-ar","48000","-ac","2","-movflags","+faststart","-f","mp4","output.part.mp4")
        return args
    }

    /** MP4 timed text has one cue at a time. Preserve overlapping speakers as distinct lines of a combined cue. */
    private fun writeSubtitles(out: Appendable, cues: List<MovieCaption>) {
        fun time(ms: Long) = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
            ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
        data class Change(val index: Int, val start: Boolean)
        val events = sortedMapOf<Long, MutableList<Change>>()
        val texts = cues.mapIndexed { index, cue ->
            events.getOrPut(cue.startMs) { mutableListOf() }.add(Change(index, true))
            events.getOrPut(cue.startMs + cue.durationMs) { mutableListOf() }.add(Change(index, false))
            cue.text.replace(Regex("[\\p{Cntrl}]+"), " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        }
        val active = sortedSetOf<Int>()
        var number = 0
        var previous = 0L
        for ((position, changes) in events) {
            if (active.isNotEmpty()) {
                out.append((++number).toString()).append('\n').append(time(previous)).append(" --> ").append(time(position)).append('\n')
                active.forEach { index -> out.append(texts[index]).append('\n') }
                out.append('\n')
            }
            changes.forEach { if (it.start) active.add(it.index) else active.remove(it.index) }
            previous = position
        }
    }
}
