package studio.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*

class PostProductionTest {
    @Test fun confirmedDialogueMixAndSubtitlesExportFromPinnedInputsAcrossRestart() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            val shot = scene.board.shots.first()
            val voiceEngine = checkNotNull(studio.tts)
            val info = voiceEngine.info()
            val key = uid()
            val line = studio.post.audio(shot.id,null).lines.single()
            val voice = studio.post.bind(project.id,line.speaker,VoiceEdit(0,info.voices.first().id),voiceEngine.identity,info)
            val speech = SpeechRequest(shot.revisionId,line.ordinal,voice.id)
            fun generate(req: SpeechRequest,key: String) = studio.tasks.enqueueSpeech(shot.id,key,req,voiceEngine.identity,info)
            val a = generate(speech,key)
            assertEquals(a.taskId,generate(speech,key).taskId)
            assertFailsWith<StudioError> { generate(speech.copy(speed=1.5),key) }
            val sound = await(a.taskId,SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,sound.status,sound.attempts.last().error.toString())
            assertEquals(line.text,sound.snapshot.speech!!.text)
            assertEquals(voice.id,sound.snapshot.speechOrigin!!.voiceBindingId)
            val original = studio.post.audio(shot.id,null).lines.single().takes.single()
            val selected = studio.post.select(shot.id,original.id,0)
            assertEquals(original.media.durationMs,selected.selectedDurationMs)
            assertEquals("audio/wav",original.media.mime)
            val video = video()
            val req = RenderRequest(shot.revisionId,scene.frames.first().id,video.id,video.manifest.version)
            val v = studio.tasks.enqueueRender(shot.id,uid(),req,studio.engine.identity,"mock").taskId
            await(v,SUCCEEDED)
            val render = studio.boards.renders(shot.id,null,Window()).items.single()
            val clip = TimelineClip(shot.id,render.id,100,1600,0.3,listOf(TimelineDialogue(original.id,40,1.2,"中文，字幕！")),
                listOf(Caption(800,600,"下一幕")))
            val edit = TimelineEdit(0,scene.board.version!!.id,listOf(clip))
            assertFailsWith<StudioError> { studio.post.save(scene.episode,edit) }
            studio.boards.reviewRender(shot.id,render.id,0,true)
            assertFailsWith<StudioError> { studio.post.save(scene.episode,edit.copy(clips=listOf(clip.copy(durationMs=999999)))) }
            val longCaption = clip.copy(captions=listOf(Caption(1599,10,"越界")))
            val lateDialogue = clip.copy(dialogues=listOf(TimelineDialogue(original.id,1599)))
            assertFailsWith<StudioError> { studio.post.save(scene.episode,edit.copy(clips=listOf(longCaption))) }
            assertFailsWith<StudioError> { studio.post.save(scene.episode,edit.copy(clips=listOf(lateDialogue))) }
            val originalTimeline = studio.post.save(scene.episode,edit.copy(clips=listOf(clip.copy(dialogues=emptyList()))))
            val timeline = studio.post.save(scene.episode,edit.copy(expectedRevision=1))
            assertEquals(listOf(timeline.id,originalTimeline.id),studio.post.history(scene.episode,Window()).items.map { it.id })
            assertFalse(timeline.stale)
            assertEquals(listOf(clip),timeline.clips)
            assertFailsWith<StudioError> { studio.post.save(scene.episode,edit) }
            val replace = generate(speech.copy(speed=1.3),uid()).taskId
            await(replace,SUCCEEDED)
            val replacement = studio.post.audio(shot.id,shot.revisionId).lines.single().takes.first()
            val version = studio.exporter.version()
            val export = ExportRequest(timeline.id,"480p")
            val exportKey = uid()
            studio.scheduler.stop()
            fun queue(request: ExportRequest,key: String=exportKey) = studio.tasks.enqueueExport(scene.episode,key,request,studio.exporter.identity,version)
            val accepted = queue(export)
            assertEquals(export.resolution,studio.tasks.get(accepted.taskId).snapshot.resolution)
            assertEquals(original.media,studio.tasks.get(accepted.taskId).snapshot.export!!.clips.single().dialogues.single().audio)
            studio.post.select(shot.id,replacement.id,1)
            assertTrue(studio.post.timeline(scene.episode,timeline.id).current!!.stale)
            assertFailsWith<StudioError> { queue(export,uid()) }
            restart()
            val done = await(accepted.taskId,SUCCEEDED,FAILED)
            assertEquals(SUCCEEDED,done.status,done.attempts.last().error.toString())
            assertEquals(done.id,queue(export).taskId)
            assertFailsWith<StudioError> { queue(export.copy(resolution="1080p")) }
            val result = studio.post.exports(scene.episode,Window()).items.single()
            assertEquals(result.media,done.results.single())
            assertEquals(480,result.media.width)
            assertEquals(854,result.media.height)
            assertTrue(result.media.durationMs!! in 1500..1700)
            val path = studio.media.resolve(result.media.relativePath)
            val probe = ProcessBuilder("ffprobe","-v","error","-show_entries","stream=codec_name","-of","csv=p=0",path.toString()).start()
            val streams = probe.inputStream.bufferedReader().readText()
            assertEquals(0,probe.waitFor())
            listOf("h264","aac","mov_text").forEach { assertContains(streams,it) }
            val extract = ProcessBuilder("ffmpeg","-v","error","-i",path.toString(),"-map","0:s:0","-f","srt","-").start()
            val subtitles = extract.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0,extract.waitFor())
            assertContains(subtitles,"中文，字幕！")
            assertContains(subtitles,"下一幕")
            val samples = ProcessBuilder("ffmpeg","-v","error","-i",path.toString(),"-map","0:a:0","-ar","16000","-ac","1","-f","s16le","-").start()
            val pcm = samples.inputStream.readAllBytes()
            assertEquals(0,samples.waitFor())
            fun peak(fromMs: Int,toMs: Int): Int {
                val bytes = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                return (fromMs*16 until toMs*16).maxOf { kotlin.math.abs(bytes.getShort(2*it).toInt()) }
            }
            assertTrue(peak(100,300) > 800,"配音时段应包含选定的音频")
            assertTrue(peak(1000,1200) < 32,"无原声和配音的时段应静音")
            assertEquals(render.media.sha256,sha256(Files.readAllBytes(studio.media.resolve(render.media.relativePath))))
            assertEquals(original.media.sha256,sha256(Files.readAllBytes(studio.media.resolve(original.media.relativePath))))
            assertEquals(2,studio.post.audio(shot.id,shot.revisionId).lines.single().takes.size)
        } }
    }

    @Test fun voicesAreProjectScopedAndMeasuredDialogueDurationFeedsBackToCurrentShot() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val scene = scene()
            val foreign = studio.catalog.create(ProjectInput("另一剧组"))
            val other = scene(foreign)
            val shot = scene.board.shots.first()
            val otherShot = other.board.shots.first()
            val board = studio.boards.edit(shot.id,ShotEdit(1,durationMs=100))
            val head = studio.boards.approve(scene.episode,board.version!!.id,2).shots.first()
            approve(other)
            val engine = checkNotNull(studio.tts)
            val info = engine.info()
            val voice = info.voices.first()
            fun bind(p: String,who: String,revision: Int,id: String=voice.id) = studio.post.bind(p,who,VoiceEdit(revision,id),engine.identity,info)
            assertFailsWith<StudioError> { bind(project.id,"陌生人",0) }
            assertFailsWith<StudioError> { bind(project.id,"lin",0,"missing") }
            val binding = bind(project.id,"lin",0)
            assertEquals(binding,bind(project.id,"lin",1))
            assertFailsWith<StudioError> { bind(project.id,"lin",0) }
            val otherBinding = bind(foreign.id,"lin",0)
            val req = SpeechRequest(head.revisionId,1,binding.id)
            fun submit(input: SpeechRequest) = studio.tasks.enqueueSpeech(head.id,uid(),input,engine.identity,info).taskId
            assertFailsWith<StudioError> { submit(req.copy(voiceBindingId=otherBinding.id)) }
            assertFailsWith<StudioError> { submit(req.copy(shotRevisionId=otherShot.revisionId)) }
            assertFailsWith<StudioError> { submit(req.copy(shotRevisionId=shot.revisionId)) }
            assertFailsWith<StudioError> { submit(req.copy(lineOrdinal=2)) }
            await(submit(req),SUCCEEDED)
            val take = studio.post.audio(head.id,null).lines.single().takes.single()
            assertFailsWith<StudioError> { studio.post.select(otherShot.id,take.id,0) }
            val selected = studio.post.select(head.id,take.id,0)
            assertTrue(selected.overrunMs > 0)
            assertEquals(take.media.durationMs!!-100,selected.overrunMs)
            assertFailsWith<StudioError> { studio.post.select(head.id,take.id,0) }
            bind(project.id,"lin",1,info.voices.last().id)
            assertTrue(studio.post.audio(head.id,null).lines.single().takes.single().stale)
            assertFailsWith<StudioError> { studio.post.select(head.id,take.id,1) }
            assertFailsWith<StudioError> { submit(req) }
            val api = studioServer(studio,0).start()
            val base = "http://127.0.0.1:${api.engine.resolvedConnectors().single().port}/api/v1"
            try {
                HttpClient.newHttpClient().use { client ->
                    fun get(path: String) = client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),BodyHandlers.ofString())
                    assertEquals(200,get("/engines/tts").statusCode())
                    assertEquals(200,get("/engines/media").statusCode())
                    val res = get("/shots/${head.id}/audio")
                    assertEquals(take.id,json.decodeFromString<ShotAudio>(res.body()).lines.single().selectedTakeId)
                    assertEquals(TimelineView(scene.episode,null),json.decodeFromString<TimelineView>(get("/episodes/${scene.episode}/timeline").body()))
                }
            } finally { api.stop(0,1000) }
        } }
    }
}
