<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NButton } from 'naive-ui'
import { api, value, allPages, pathId, mediaUrl, dateText, statusText, type Model } from '../../api/client'
import { submissionKey } from '../storyboard/production'
import { useStudio } from '../../stores/studio'
import { displayTime } from './timeline'
import ExportPlayer from './ExportPlayer.vue'
const props = defineProps<{
  episode: string
  timeline?: Model['TimelineVersion']
  history: Model['TimelineVersion'][]
  canSubmit: boolean
  dirty: boolean
}>()
const store = useStudio(),
  keys = submissionKey(),
  cap = ref<Model['ExportCapability']>(),
  rows = ref<Model['ExportVersion'][]>([]),
  resolution = ref<'480p' | '1080p'>('480p'),
  busy = ref(false)
let sequence = 0
async function check() {
  try {
    cap.value = value(await api.GET('/engines/media'))
  } catch (e) {
    store.report(e)
  }
}
void check()
watch(
  [() => props.episode, () => store.assetsChanged],
  async ([id], prev) => {
    const n = ++sequence
    if (id !== prev?.[0]) rows.value = []
    if (!id) return
    try {
      const saved = await allPages(async (cursor) =>
        value(
          await api.GET('/episodes/{id}/exports', {
            params: { ...pathId(String(id)), query: { limit: 100, cursor } },
          }),
        ),
      )
      if (n === sequence) rows.value = saved
    } catch (e) {
      if (n === sequence) store.report(e)
    }
  },
  { immediate: true },
)
const last = computed(() =>
  store.tasks.find((t) => t.kind === 'EPISODE_EXPORT' && t.targetId === props.episode),
)
async function submit() {
  const timeline = props.timeline
  if (!timeline || !props.canSubmit || props.dirty || busy.value || !cap.value?.available) return
  const body = { timelineRevisionId: timeline.id, resolution: resolution.value },
    key = JSON.stringify([props.episode, body])
  busy.value = true
  try {
    value(
      await api.POST('/episodes/{id}/export', {
        params: { ...pathId(props.episode), header: keys.for(key) },
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
</script>
<template>
  <section class="panel">
    <div class="panel-title">
      <h2>成片导出</h2>
      <small>{{ cap?.version }}</small>
    </div>
    <div class="inset form-stack">
      <div v-if="cap && !cap.available" class="notice">
        {{ cap.reason ?? '当前无法导出成片' }} <button class="text-link" @click="check">重新检测</button>
      </div>
      <div class="post-row">
        <label class="field"
          >导出规格<select v-model="resolution">
            <option value="480p">480p 预览</option>
            <option value="1080p">1080p 成片</option>
          </select></label
        >
        <n-button type="primary" :disabled="!canSubmit || dirty || busy || !cap?.available" @click="submit"
          >导出视频</n-button
        >
        <router-link v-if="last" :to="{ path: '/tasks', query: { task: last.id } }"
          >最近导出任务 · {{ statusText[last.status] }}</router-link
        >
      </div>
      <p v-if="dirty" class="post-empty">保存当前剪辑后即可导出。</p>
      <p v-if="!rows.length" class="post-empty">本集尚无导出结果。</p>
      <article v-for="item in rows" :key="item.id" class="post-export">
        <export-player
          :task-id="item.taskId"
          :media="item.media"
          :plan="store.tasks.find((t) => t.id === item.taskId)?.snapshot.export"
          :label="'成片 ' + item.resolution"
        />
        <div>
          <strong
            >{{ item.resolution }} · 时间线 V{{
              history.find((v) => v.id === item.timelineRevisionId)?.revision ?? '…'
            }}</strong
          >
          <p>
            {{ item.media.width }} × {{ item.media.height }} · {{ displayTime(item.media.durationMs ?? 0) }}
          </p>
          <small>{{ dateText(item.createdAt) }}</small>
          <div class="post-row">
            <a :href="mediaUrl(item.media.id)" :download="'成片-' + item.id.slice(0, 8) + '.mp4'">下载 MP4</a
            ><router-link :to="{ path: '/tasks', query: { task: item.taskId } }">查看导出任务</router-link>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
