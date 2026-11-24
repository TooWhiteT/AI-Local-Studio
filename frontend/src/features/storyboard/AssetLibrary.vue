<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { NButton, NSelect, useMessage } from 'naive-ui'
import { api, pathId, value, mediaUrl, assetTypes, type Asset } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { useEpisodeScript, submissionKey } from './production'
const props = defineProps<{ assets: Asset[]; selected: string; active: boolean; projectId: string }>()
const emit = defineEmits<{ select: [id: string] }>()
const store = useStudio(),
  toast = useMessage(),
  keys = submissionKey()
const { episode, script, approved, options } = useEpisodeScript(toRef(props, 'projectId'))
const filter = ref('ALL'),
  busy = ref(false)
const filters = [
  { value: 'ALL', label: '全部类型' },
  ...Object.entries(assetTypes).map(([value, label]) => ({ value, label })),
]
const items = computed(() => props.assets.filter((a) => filter.value === 'ALL' || a.type === filter.value))
function thumbnail(a: Asset) {
  return a.versions.find((v) => v.id === a.activeVersionId)?.file ?? a.versions[0]?.file
}
async function extract() {
  if (!approved.value || !script.value || busy.value) return
  busy.value = true
  const body = { scriptRevisionId: script.value.id, expectedRevision: script.value.revision }
  const key = JSON.stringify([props.projectId, body])
  try {
    value(
      await api.POST('/projects/{id}/assets/extract', {
        params: { ...pathId(props.projectId), header: keys.for(key) },
        body,
      }),
    )
    keys.accepted(key)
    toast.success('资产提取已加入队列')
    await store.loadTasks()
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="panel asset-library">
    <div class="panel-title">
      <h2>
        项目资产 <small>{{ assets.length }}</small>
      </h2>
      <n-select v-model:value="filter" aria-label="资产类型筛选" :options="filters" class="compact-select" />
    </div>
    <div class="production-toolbar">
      <n-select
        v-model:value="episode"
        aria-label="资产提取剧集"
        :options="options"
        placeholder="选择剧集"
        class="episode-select"
      />
      <n-button :disabled="!active || !approved" :loading="busy" @click="extract">从剧本提取资产</n-button>
      <small v-if="approved">已审核剧本 V{{ script?.revision }}</small>
      <router-link v-else :to="'/projects/' + projectId + '/writing'">前往编剧工作台审核剧本</router-link>
    </div>
    <div v-if="items.length" class="asset-strip">
      <button v-for="a in items" :key="a.id" :aria-pressed="a.id === selected" @click="emit('select', a.id)">
        <img v-if="thumbnail(a)" :src="mediaUrl(thumbnail(a)!.id)" alt="" />
        <span v-else class="asset-type">{{ assetTypes[a.type] }}</span>
        <span
          ><strong>{{ a.name }}</strong
          ><small>{{ assetTypes[a.type] }} · {{ a.versions.length }} 个版本</small></span
        >
      </button>
    </div>
    <p v-else class="inset muted">
      {{ filter === 'ALL' ? '可从已审核剧本提取资产，也可以新建资产。' : '该类型暂无资产。' }}
    </p>
  </section>
</template>
