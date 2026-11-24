<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NInputNumber, NSelect, useMessage } from 'naive-ui'
import { api, value, pathId, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { submissionKey } from './production'
const props = defineProps<{
  shot: Model['Shot']
  board: Model['BoardSummary']
  editable: boolean
  options: { label: string; value: string }[]
}>()
const emit = defineEmits<{ changed: [board: Model['Board']]; queued: [] }>()
const store = useStudio(),
  toast = useMessage(),
  keys = submissionKey()
const framing = ref(''),
  camera = ref(''),
  action = ref(''),
  seconds = ref<number | null>(null),
  refs = ref<string[]>([])
const base = ref(0),
  baseline = ref(''),
  brief = ref(''),
  busy = ref(false),
  workflow = ref('')
const content = computed(() => ({
  framing: framing.value,
  camera: camera.value,
  action: action.value,
  durationMs: Math.round((seconds.value ?? 0) * 1000),
  assetVersionIds: refs.value,
}))
const dirty = computed(() => JSON.stringify(content.value) !== baseline.value)
function restore() {
  const s = props.shot
  framing.value = s.framing
  camera.value = s.camera
  action.value = s.action
  seconds.value = s.durationMs / 1000
  refs.value = s.references.map((r) => r.versionId)
  base.value = props.board.revision
  baseline.value = JSON.stringify(content.value)
}
watch(
  () => [props.shot.revisionId, props.board.id],
  () => {
    if (!baseline.value || !dirty.value) restore()
  },
  { immediate: true },
)
const flows = computed(() =>
  store.workflows.filter(
    (w) => w.enabled && w.manifest.parameters.some((p) => p.name === 'prompt' && p.type === 'STRING'),
  ),
)
watch(
  flows,
  (rows) => {
    if (!rows.some((r) => r.id === workflow.value)) workflow.value = rows[0]?.id ?? ''
  },
  { immediate: true },
)
async function save(confirmReferences = false) {
  busy.value = true
  try {
    const { assetVersionIds, ...fields } = content.value
    const referencesChanged =
      JSON.stringify(assetVersionIds) !== JSON.stringify(props.shot.references.map((r) => r.versionId))
    const board = value(
      await api.PATCH('/shots/{id}', {
        params: pathId(props.shot.id),
        body: {
          expectedRevision: base.value,
          ...fields,
          ...(confirmReferences || referencesChanged ? { assetVersionIds } : {}),
        },
      }),
    )
    baseline.value = JSON.stringify(content.value)
    emit('changed', board)
    toast.success('镜头已保存')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
async function generate(preview: boolean) {
  busy.value = true
  try {
    const w = flows.value.find((x) => x.id === workflow.value)
    const id = props.shot.id
    const request = {
      scriptRevisionId: props.board.scriptRevisionId,
      expectedRevision: base.value,
      assetVersionIds: refs.value,
      brief: brief.value,
    }
    const frame = {
      shotRevisionId: props.shot.revisionId,
      workflowId: w?.id ?? '',
      workflowVersion: w?.manifest.version ?? '',
    }
    const key = JSON.stringify([id, preview ? frame : request])
    const params = { ...pathId(id), header: keys.for(key) }
    if (preview) value(await api.POST('/shots/{id}/keyframes/generate', { params, body: frame }))
    else value(await api.POST('/shots/{id}/regenerate', { params, body: request }))
    keys.accepted(key)
    emit('queued')
    toast.success(preview ? '预览图已加入队列' : '单镜头重做已加入队列')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <div class="panel shot-settings">
    <div class="panel-title">
      <h2>镜头参数</h2>
      <small>镜头 V{{ shot.revision }}</small>
    </div>
    <div class="form-stack inset">
      <label class="field"
        >景别<n-input aria-label="景别" v-model:value="framing" maxlength="80" :disabled="!editable"
      /></label>
      <label class="field"
        >机位与运动<n-input
          aria-label="机位与运动"
          v-model:value="camera"
          maxlength="160"
          :disabled="!editable"
      /></label>
      <label class="field"
        >画面动作<n-input
          aria-label="画面动作"
          v-model:value="action"
          type="textarea"
          maxlength="800"
          :disabled="!editable"
      /></label>
      <label class="field"
        >时长（秒）<n-input-number
          aria-label="镜头时长"
          v-model:value="seconds"
          :min="0.001"
          :max="600"
          :precision="3"
          :disabled="!editable"
      /></label>
      <label class="field"
        >镜头参考版本<n-select
          aria-label="镜头参考版本"
          multiple
          v-model:value="refs"
          :options="options"
          :disabled="!editable"
      /></label>
      <div v-if="editable" class="version-actions">
        <n-button
          type="primary"
          :disabled="!dirty || !action.trim() || !camera.trim() || !framing.trim() || !seconds"
          :loading="busy"
          @click="save()"
          >保存镜头</n-button
        >
        <n-button
          v-if="!dirty && shot.references.some((r) => r.needsReview)"
          :disabled="busy"
          @click="save(true)"
          >保留这些参考版本</n-button
        >
        <n-button v-if="dirty" @click="restore">还原编辑</n-button>
      </div>
      <template v-if="editable">
        <label class="field"
          >重做说明<n-input v-model:value="brief" placeholder="希望调整的动作或镜头语言" maxlength="4000"
        /></label>
        <n-button :disabled="busy || dirty" @click="generate(false)">重做此镜头</n-button>
        <label class="field"
          >预览图工作流<n-select
            v-model:value="workflow"
            :options="flows.map((w) => ({ value: w.id, label: w.manifest.title }))"
            placeholder="无可用文生图模板"
        /></label>
        <n-button :disabled="busy || dirty || !workflow" @click="generate(true)">生成分镜预览图</n-button>
      </template>
    </div>
  </div>
</template>
