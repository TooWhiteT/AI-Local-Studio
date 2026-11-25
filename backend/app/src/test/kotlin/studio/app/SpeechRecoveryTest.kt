package studio.app

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import studio.application.*
import studio.core.*
import studio.domain.TaskStatus.*
import studio.engine.*
import studio.scheduler.GpuScheduler

class SpeechRecoveryTest {
    @Test fun tasksPinTheirVoiceModelAcrossInventoryChanges() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            studio.scheduler.stop()
            val shot = scene.board.shots.first()
            val line = studio.post.audio(shot.id,null).lines.single()
            val original = checkNotNull(studio.tts)
            val legacy = original.info()
            val expanded = legacy.copy(version="combined-inventory", voices=listOf(
                legacy.voices.first().copy(modelVersion=legacy.version),
                legacy.voices.last().copy(modelVersion="second-model-v1")))
            fun bind(info: TtsInfo, voiceId: String, revision: Int) =
                studio.post.bind(project.id,line.speaker,VoiceEdit(revision,voiceId),original.identity,info)
            fun queue(binding: VoiceBinding, info: TtsInfo) = studio.tasks.enqueueSpeech(shot.id,uid(),
                SpeechRequest(shot.revisionId,line.ordinal,binding.id),original.identity,info).taskId

            val oldVoice = bind(legacy,legacy.voices.first().id,0)
            val oldJob = queue(oldVoice,legacy)
            assertEquals(oldVoice,bind(expanded,oldVoice.voiceId,oldVoice.revision))
            val newVoice = bind(expanded,expanded.voices.last().id,oldVoice.revision)
            assertEquals("second-model-v1",newVoice.engineVersion)
            val newJob = queue(newVoice,expanded)
            val changed = expanded.copy(voices=expanded.voices.map { it.copy(modelVersion="replaced") })
            assertFailsWith<StudioError> { queue(newVoice,changed) }

            val requests = java.util.concurrent.ConcurrentHashMap<String,String>()
            val adapter = object: TtsEngine by original {
                override suspend fun info() = expanded.copy(version="catalog-3")
                override suspend fun advance(attempt: String, call: SpeechCall, cancel: Boolean): SpeechRun {
                    requests[call.voiceId] = checkNotNull(call.modelVersion)
                    return original.advance(attempt,call,cancel)
                }
            }
            val worker = GpuScheduler(studio.tasks,studio.registry,studio.media,studio.engine,20,tts=adapter)
            worker.start()
            try {
                val a = await(oldJob,SUCCEEDED,FAILED)
                val b = await(newJob,SUCCEEDED,FAILED)
                assertEquals(SUCCEEDED,a.status)
                assertEquals(SUCCEEDED,b.status)
                assertEquals(legacy.version,a.snapshot.engineVersion)
                assertEquals(newVoice.engineVersion,b.snapshot.engineVersion)
                assertEquals(mapOf(oldVoice.voiceId to oldVoice.engineVersion,newVoice.voiceId to newVoice.engineVersion),requests)
            } finally { worker.stop() }
        } }
    }

    @Test fun ambiguousSpeechRetainsLeaseAcrossRestartAndCancelWaitsForTheRemoteTerminal() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            val shot = scene.board.shots.first()
            val real = checkNotNull(studio.tts)
            val info = real.info()
            val line = studio.post.audio(shot.id,null).lines.single()
            val voice = studio.post.bind(project.id,line.speaker,VoiceEdit(0,info.voices.first().id),real.identity,info)
            val uncertain = AtomicBoolean(true)
            val stopped = AtomicBoolean(false)
            val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val cancelCalls = AtomicInteger()
            val speech = object: TtsEngine by real {
                override suspend fun advance(attempt: String, call: SpeechCall, cancel: Boolean): SpeechRun {
                    ids += attempt
                    if (uncertain.get()) unavailable("测试：回执暂不可读取")
                    if (cancel) cancelCalls.incrementAndGet()
                    return if (cancel && stopped.get()) SpeechRun.Cancelled else SpeechRun.Active
                }
            }
            studio.scheduler.stop()
            val queued = studio.tasks.enqueueSpeech(shot.id,uid(),SpeechRequest(shot.revisionId,line.ordinal,voice.id),real.identity,info)
            fun worker() = GpuScheduler(studio.tasks,studio.registry,studio.media,studio.engine,20,tts=speech)
            val before = worker()
            before.start()
            try {
                val t = await(queued.taskId,UNKNOWN)
                assertEquals(t.id,studio.tasks.lease()?.id)
                assertEquals(t.attempts.single().id,t.attempts.single().externalId)
            } finally { before.stop() }
            uncertain.set(false)
            val after = worker()
            after.start()
            try {
                val running = await(queued.taskId,RUNNING)
                studio.tasks.cancel(running.id)
                withTimeout(4000) { while (cancelCalls.get()==0) delay(10) }
                assertEquals(CANCEL_REQUESTED,studio.tasks.lease()?.status)
                stopped.set(true)
                val end = await(running.id,CANCELED)
                assertEquals(setOf(end.attempts.single().id),ids)
                assertTrue(end.results.isEmpty())
                assertNull(studio.tasks.lease())
            } finally { after.stop() }
        } }
    }
    @Test fun gpuSpeechHoldsTheLeaseUntilItsModelServiceConfirmsUnloading() = runBlocking<Unit> {
        Rig(stepMs=20).use { rig -> with(rig) {
            val scene = scene()
            approve(scene)
            val shot = scene.board.shots.first()
            val real = checkNotNull(studio.tts)
            val info = real.info().copy(device="gpu")
            val line = studio.post.audio(shot.id,null).lines.single()
            val voice = studio.post.bind(project.id,line.speaker,VoiceEdit(0,info.voices.first().id),real.identity,info)
            val canUnload = AtomicBoolean(false)
            val unloadCalls = AtomicInteger()
            val comfyFrees = AtomicInteger()
            val speech = object: TtsEngine by real {
                override suspend fun info() = info
                override suspend fun releaseModels() {
                    unloadCalls.incrementAndGet()
                    if (!canUnload.get()) unavailable("测试：TTS 尚未释放显存")
                }
            }
            val image = object: ImageEngine by studio.engine {
                override val mode = "comfy"
                override suspend fun releaseModels() { comfyFrees.incrementAndGet() }
            }
            studio.scheduler.stop()
            val before = mock.submissions
            val audio = studio.tasks.enqueueSpeech(shot.id,uid(),SpeechRequest(shot.revisionId,line.ordinal,voice.id),real.identity,info)
            val nextImage = submit("等待语音模型释放")
            val worker = GpuScheduler(studio.tasks,studio.registry,studio.media,image,20,tts=speech)
            worker.start()
            try {
                withTimeout(5000) { while (unloadCalls.get()==0) delay(10) }
                assertEquals(1,comfyFrees.get())
                assertEquals(audio.taskId,studio.tasks.lease()?.id)
                assertEquals(before,mock.submissions)
                canUnload.set(true)
                await(audio.taskId,SUCCEEDED)
                await(nextImage,SUCCEEDED)
                assertEquals(before+1,mock.submissions)
            } finally { worker.stop() }
        } }
    }
}
