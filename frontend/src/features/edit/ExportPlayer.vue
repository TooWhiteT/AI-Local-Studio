<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, value, pathId, mediaUrl, type Model } from '../../api/client'

defineOptions({ inheritAttrs: false })
const props = defineProps<{
  media: Model['Media']
  taskId: string
  label: string
  plan?: Model['ExportPlan'] | null
}>()
const player = ref<HTMLVideoElement>()
// Browsers without MP4 mov_text playback can use the same immutable export cues.
const failed = ref(false)
let pending = false
async function addCaptions() {
  const video = player.value
  if (!video || video.readyState < 1 || video.textTracks.length || typeof VTTCue === 'undefined' || pending)
    return
  let plan = props.plan
  if (!plan) {
    pending = true
    failed.value = false
    try {
      const task = value(await api.GET('/tasks/{id}', { params: pathId(props.taskId) }))
      plan = task.snapshot.export
    } catch {
      failed.value = true
      return
    } finally {
      pending = false
    }
  }
  if (!plan || video !== player.value || video.textTracks.length) return
  let offset = 0
  const cues: VTTCue[] = []
  for (const clip of plan.clips) {
    const lines = clip.dialogues
      .filter((d) => d.subtitle && d.audio.durationMs)
      .map((d) => ({ startMs: d.startMs, durationMs: d.audio.durationMs!, text: d.subtitle }))
    for (const c of [...clip.captions, ...lines]) {
      if (!c.text.trim()) continue
      const start = (offset + c.startMs) / 1000,
        end = (offset + Math.min(clip.durationMs, c.startMs + c.durationMs)) / 1000
      if (end > start)
        cues.push(
          new VTTCue(start, end, c.text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')),
        )
    }
    offset += clip.durationMs
  }
  if (!cues.length) return
  const track = video.addTextTrack('subtitles', '中文字幕', 'zh')
  for (const cue of cues) track.addCue(cue)
  track.mode = 'showing'
}
watch(() => props.plan, addCaptions, { flush: 'post' })
</script>
<template>
  <video
    ref="player"
    v-bind="$attrs"
    :src="mediaUrl(media.id)"
    controls
    playsinline
    preload="metadata"
    :aria-label="label"
    @loadedmetadata="addCaptions"
  />
  <button v-if="failed" type="button" class="text-link" @click="addCaptions">重新加载字幕轨</button>
</template>
