package studio.media

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import studio.core.*
import studio.engine.*
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FfmpegEngineTest {
    private fun tool(vararg args: String): ByteArray {
        val proc = ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = proc.inputStream.use { it.readAllBytes() }
        check(proc.waitFor(15, TimeUnit.SECONDS) && proc.exitValue() == 0) { "本机媒体工具失败: ${args.first()}" }
        return bytes
    }
    @Test fun exportReattachesToOneChildAndMixesTrimmedMediaAndChineseSubtitles() = runBlocking {
        val root = Files.createTempDirectory("studio-ffmpeg")
        try {
            val sample = Path.of("backend/mock-comfy/src/main/resources/mock-shot.mp4").toAbsolutePath().toString()
            val voice = root.resolve("对白.wav").toString()
            tool("ffmpeg", "-v","error","-f","lavfi","-i","sine=frequency=660:duration=0.35","-c:a","pcm_s16le", voice)
            val mixed = root.resolve("原声.mp4").toString()
            tool("ffmpeg","-v","error","-i",sample,"-f","lavfi","-i","sine=frequency=440:duration=3",
                "-c:v","copy","-c:a","aac",mixed)
            val plan = Movie(160,90,24,listOf(
                MovieClip(sample, 300, 1100, 1.0, listOf(MovieAudio(voice,200,1.0)),
                    listOf(MovieCaption(200,350,"第一句"),MovieCaption(300,200,"同时说话"))),
                MovieClip(mixed, 500, 900, 0.5, emptyList(), listOf(MovieCaption(0,500,"结束")))))
            val id = uid()
            var launches = 0
            val first = FfmpegEngine(root)
            assertTrue(first.version().startsWith("ffmpeg version"))
            assertIs<ExportRun.Active>(first.advance(id, false) { launches++; plan })
            val second = FfmpegEngine(root)
            val end = withTimeout(15000) {
                var result = second.advance(id, false) { launches++; plan }
                while (result == ExportRun.Active) { delay(30); result = second.advance(id, false) { launches++; plan } }
                result
            }
            val file = assertIs<ExportRun.Completed>(end).path.toString()
            val streams = json.parseToJsonElement(tool("ffprobe","-v","error","-show_entries",
                "stream=width,height,codec_name,channels,duration","-of","json",file).decodeToString()).jsonObject.getValue("streams").jsonArray
            assertEquals(listOf("h264","aac","mov_text"), streams.map { it.jsonObject.getValue("codec_name").jsonPrimitive.content })
            assertEquals(2,streams[1].jsonObject.getValue("channels").jsonPrimitive.int)
            assertEquals(160,streams[0].jsonObject.getValue("width").jsonPrimitive.int)
            assertTrue(kotlin.math.abs(streams[0].jsonObject.getValue("duration").jsonPrimitive.double-2.0)<0.085)
            val subtitles = tool("ffmpeg","-v","error","-i",file,"-map","0:s:0","-f","srt","pipe:1").decodeToString()
            assertContains(subtitles, "第一句"); assertContains(subtitles, "同时说话"); assertContains(subtitles, "结束")
            val pcm = tool("ffmpeg","-v","error","-i",file,"-map","0:a:0","-ar","8000","-ac","1","-f","s16le","pipe:1")
            fun peak(a:Int,b:Int) = (a until b).maxOf { i -> kotlin.math.abs(((pcm[i*2+1].toInt() shl 8) or (pcm[i*2].toInt() and 255))) }
            assertTrue(peak(0,1000)<50); assertTrue(peak(2500,3200)>1000); assertTrue(peak(10000,11000)>300)
            assertEquals(end,second.advance(id,false){ error("已完成工作不应重复准备") })
            assertEquals(1, launches)
            val cancelled = uid()
            assertEquals(ExportRun.Cancelled, second.advance(cancelled,true){ error("取消后不应启动") })
            assertEquals(ExportRun.Cancelled, second.advance(cancelled,false){ error("重启后保持取消") })
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun manyShortFractionalFrameEditsMatchTheWholeTimeline() = runBlocking {
        val root = Files.createTempDirectory("studio-ffmpeg-grid")
        try {
            val sample = Path.of("backend/mock-comfy/src/main/resources/mock-shot.mp4").toAbsolutePath().toString()
            val clips = List(26) { i ->
                MovieClip(sample, (13*i).toLong(), 43L + i % 23, 0.0, emptyList(), emptyList())
            }
            val id = uid()
            val engine = FfmpegEngine(root)
            val movie = Movie(160, 90, 24, clips)
            val result = withTimeout(15000) {
                var state = engine.advance(id,false) { movie }
                while (state == ExportRun.Active) { delay(30); state = engine.advance(id,false) { movie } }
                state
            }
            val filename = assertIs<ExportRun.Completed>(result).path.toString()
            val frames = tool("ffprobe","-v","error","-select_streams","v:0","-count_frames","-show_entries",
                "stream=nb_read_frames","-of","csv=p=0",filename).decodeToString().trim().toLong()
            val ms = clips.sumOf { it.durationMs }
            assertEquals((ms * movie.fps + 500)/1000, frames)
            val audio = tool("ffprobe","-v","error","-select_streams","a:0","-show_entries","stream=duration",
                "-of","csv=p=0",filename).decodeToString().trim().toDouble()
            assertTrue(kotlin.math.abs(audio-ms/1000.0)<0.002)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun runningEncoderGetsCancelledAcrossStudioRestart() = runBlocking {
        val root = Files.createTempDirectory("studio-export-cancel")
        try {
            val sample = Path.of("backend/mock-comfy/src/main/resources/mock-shot.mp4").toAbsolutePath().toString()
            val clip = MovieClip(sample,0,2800,0.0,emptyList(),emptyList())
            val movie = Movie(1080,1920,24,List(4) { clip })
            val id = uid()
            val initial = FfmpegEngine(root)
            assertIs<ExportRun.Active>(initial.advance(id,false){ movie })
            val dir = root.resolve("export-attempts").resolve(id)
            withTimeout(5000) { while (!Files.isRegularFile(dir.resolve("started"))) delay(5) }
            val restarted = FfmpegEngine(root)
            val end = withTimeout(5000) {
                var state = restarted.advance(id,true){ error("已提交无需重建命令") }
                while (state == ExportRun.Active) { delay(20); state = restarted.advance(id,true){ error("取消不可重启任务") } }
                state
            }
            assertEquals(ExportRun.Cancelled,end)
            assertFalse(Files.exists(dir.resolve("output.mp4")))
            assertFalse(Files.exists(dir.resolve("output.part.mp4")))
        } finally { root.toFile().deleteRecursively() }
    }
}
