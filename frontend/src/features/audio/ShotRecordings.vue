<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton } from 'naive-ui'
import { api, value, pathId, mediaUrl, dateText, statusText, type Model } from '../../api/client'
import { submissionKey } from '../storyboard/production'
import { displayTime } from '../edit/timeline'
import { useStudio } from '../../stores/studio'
const props = defineProps<{
  shot: Model['Shot']
  bindings: Model['VoiceBinding'][]
  editable: boolean
  engineReady: boolean
  names: Record<string, string>
  writingLink: string
}>()
const store = useStudio(),
  keys = submissionKey(),
  audio = ref<Model['ShotAudio']>(),
  busy = ref(false)
const speed = ref(1)
let request = 0
async function load() {
  const token = ++request,
    shot = props.shot
  try {
    const data = value(
      await api.GET('/shots/{id}/audio', {
        params: { ...pathId(shot.id), query: { shotRevisionId: shot.revisionId } },
      }),
    )
    if (token === request) audio.value = data
  } catch (e) {
    if (token === request) store.report(e)
  }
}
watch(
  [() => props.shot.revisionId, () => props.bindings, () => store.assetsChanged],
  (next, prev) => {
    if (next[0] !== prev?.[0]) audio.value = undefined
    void load()
  },
  { immediate: true },
)
const latestTask = computed(() =>
  store.tasks.find(
    (t) =>
      t.kind === 'SHOT_AUDIO' &&
      t.targetId === props.shot.id &&
      t.snapshot.shotRevisionId === props.shot.revisionId,
  ),
)
async function generate(line: Model['ShotAudio']['lines'][number]) {
  const voice = props.bindings.find((b) => b.characterKey === line.speaker)
  if (!voice || busy.value || !props.engineReady || !props.editable) return
  const body = {
    shotRevisionId: props.shot.revisionId,
    lineOrdinal: line.ordinal,
    voiceBindingId: voice.id,
    speed: speed.value,
  }
  const id = props.shot.id,
    key = JSON.stringify([id, body])
  busy.value = true
  try {
    value(
      await api.POST('/shots/{id}/audio/generate', {
        params: { ...pathId(id), header: keys.for(key) },
        body,
      }),
    )
    keys.accepted(key)
    await store.loadTasks()
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
async function select(takeId: string, expectedRevision: number) {
  if (busy.value || !props.editable) return
  busy.value = true
  try {
    value(
      await api.POST('/shots/{id}/audio/{takeId}/select', {
        params: { path: { id: props.shot.id, takeId } },
        body: { expectedRevision },
      }),
    )
  } catch (e) {
    store.report(e)
  } finally {
    await load()
    busy.value = false
  }
}
</script>
<template>
  <section class="panel">
    <div class="panel-title">
      <h2>镜头对白</h2>
      <router-link :to="writingLink">编辑本集台词</router-link>
    </div>
    <div v-if="audio" class="form-stack inset">
      <div class="post-row">
        <span
          >镜头 {{ displayTime(audio.durationMs) }} · 已选配音
          {{ displayTime(audio.selectedDurationMs) }}</span
        >
        <router-link v-if="latestTask" :to="{ path: '/tasks', query: { task: latestTask.id } }"
          >最新配音任务 · {{ statusText[latestTask.status] }}</router-link
        >
      </div>
      <p v-if="audio.overrunMs > 0" class="notice">
        配音比镜头长 {{ displayTime(audio.overrunMs) }}，可调整语速或在分镜工作台延长镜头。
      </p>
      <p v-if="!audio.lines.length" class="post-empty">该镜头没有待配音的对白。</p>
      <label v-else class="field recording-speed"
        >合成语速<input v-model.number="speed" min="0.5" max="2" step="0.05" type="number"
      /></label>
      <article v-for="line in audio.lines" :key="line.ordinal" class="post-item audio-line">
        <div class="post-row">
          <strong
            >{{ names[line.speaker] ?? line.speaker }} <small>{{ line.emotion }}</small></strong
          >
          <n-button
            size="small"
            :disabled="
              !editable ||
              !engineReady ||
              busy ||
              speed < 0.5 ||
              speed > 2 ||
              !bindings.some((b) => b.characterKey === line.speaker)
            "
            @click="generate(line)"
            >生成配音</n-button
          >
        </div>
        <p>{{ line.text }}</p>
        <div v-for="take in line.takes" :key="take.id" class="audio-take">
          <div class="post-row">
            <span>录音 V{{ take.revision }} · {{ displayTime(take.media.durationMs ?? 0) }}</span
            ><small>{{ dateText(take.createdAt) }} <template v-if="take.stale">· 上游已更新</template></small>
          </div>
          <audio
            :src="mediaUrl(take.media.id)"
            controls
            preload="metadata"
            :aria-label="'试听录音 V' + take.revision"
          />
          <div class="post-row">
            <n-button
              size="small"
              @click="select(take.id, line.reviewRevision)"
              :disabled="!editable || busy || take.stale || take.id === line.selectedTakeId"
              >{{ take.id === line.selectedTakeId ? '已选用 V' : '审核并选用 V'
              }}{{ take.revision }}</n-button
            >
            <router-link :to="{ path: '/tasks', query: { task: take.taskId } }">查看配音任务</router-link>
          </div>
        </div>
      </article>
    </div>
    <p v-else class="inset post-empty">正在读取配音…</p>
  </section>
</template>
