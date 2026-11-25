<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NInput, NSelect, useMessage } from 'naive-ui'
import { api, pathId, value, statusText, type Model, type Scalar } from '../../api/client'
import { useStudio } from '../../stores/studio'
import BlankPanel from '../../components/BlankPanel.vue'
import ParameterField from '../../components/ParameterField.vue'
import { approvalLabel, submissionKey } from '../storyboard/production'
import FrameReview from './FrameReview.vue'
import RenderHistory from './RenderHistory.vue'
import ReferencePicker from './ReferencePicker.vue'
import { useRenderBoard } from './useRenderBoard'

const store = useStudio(),
  route = useRoute(),
  toast = useMessage(),
  keys = submissionKey()
const projectId = computed(() => String(route.params.id))
const project = computed(() => store.projects.find((p) => p.id === projectId.value))
const {
  episode,
  episodes,
  board,
  versions,
  historyId,
  focused,
  shot,
  historical,
  active,
  editable,
  loading,
  refresh,
} = useRenderBoard(projectId)
const flows = computed(() =>
  store.workflows.filter(
    (w) =>
      w.enabled &&
      ((w.manifest.capabilities.includes('image-to-video') && w.manifest.firstFrame) ||
        (w.manifest.capabilities.includes('reference-to-video') && w.manifest.referenceImage)),
  ),
)
const workflowId = ref(''),
  parameters = ref<Record<string, Scalar>>({}),
  prompt = ref(''),
  customDuration = ref(false),
  checked = ref<string[]>([]),
  busy = ref(false)
const flow = computed(() => flows.value.find((w) => w.id === workflowId.value))
const needsLast = computed(() => !!flow.value?.manifest.lastFrame)
const duration = computed(() => flow.value?.manifest.parameters.find((p) => p.name === 'durationSeconds'))
const scalarFields = computed(
  () =>
    flow.value?.manifest.parameters.filter((p) => p.name !== 'prompt' && p.name !== 'durationSeconds') ?? [],
)
watch(
  flows,
  (options) => {
    if (!options.some((w) => w.id === workflowId.value))
      workflowId.value =
        options.find((w) => w.manifest.firstFrame && !w.manifest.lastFrame)?.id ?? options[0]?.id ?? ''
  },
  { immediate: true },
)
watch(
  workflowId,
  () => {
    parameters.value = Object.fromEntries(scalarFields.value.map((p) => [p.name, p.default]))
    if (duration.value) parameters.value.durationSeconds = duration.value.default
    customDuration.value = false
    prompt.value = ''
  },
  { immediate: true },
)
const referenceMode = computed(() => !!flow.value?.manifest.referenceImage)
const chosenRefs = ref<Record<string, string>>({})
const references = (s: Model['Shot']) =>
  s.references.filter((r) => !r.needsReview && ['image/png', 'image/jpeg'].includes(r.file.mime))
function referenceId(s: Model['Shot']) {
  const candidates = references(s)
  return (
    candidates.find((r) => r.versionId === chosenRefs.value[s.revisionId])?.versionId ??
    candidates[0]?.versionId
  )
}
function inputStatus(s: Model['Shot']) {
  if (referenceMode.value) return referenceId(s) ? '参考图已选定' : '缺少参考图'
  if (!s.approvedPreviewId) return '待选首帧'
  return needsLast.value && !s.approvedLastFrameId ? '待选尾帧' : '输入帧已审核'
}
const selectionReady = (s: Model['Shot']) =>
  !!flow.value &&
  (referenceMode.value
    ? !!referenceId(s)
    : !!s.approvedPreviewId && (!needsLast.value || !!s.approvedLastFrameId))
const checkedShots = computed(
  () => board.value?.shots.filter((s) => checked.value.includes(s.revisionId) && selectionReady(s)) ?? [],
)
watch([() => board.value?.version?.id, workflowId], () => {
  checked.value = []
})
const currentTasks = computed(() =>
  store.tasks.filter(
    (t) =>
      t.kind === 'SHOT_RENDER' &&
      t.targetId === focused.value &&
      t.snapshot.shotRevisionId === shot.value?.revisionId,
  ),
)
function toggle(s: Model['Shot'], event: Event) {
  if ((event.target as HTMLInputElement).checked) checked.value = [...checked.value, s.revisionId]
  else checked.value = checked.value.filter((id) => id !== s.revisionId)
}
async function submit(batch: boolean) {
  const w = flow.value,
    version = board.value?.version,
    selected = batch ? checkedShots.value : shot.value ? [shot.value] : []
  if (!editable.value || busy.value || !w || !version || !selected.length || !selected.every(selectionReady))
    return
  const values = { ...parameters.value }
  if (!customDuration.value) delete values.durationSeconds
  const common = {
    workflowId: w.id,
    workflowVersion: w.manifest.version,
    parameters: { ...values, ...(prompt.value.trim() ? { prompt: prompt.value.trim() } : {}) },
  }
  const inputs = selected.map((s) => ({
    shotRevisionId: s.revisionId,
    ...(referenceMode.value
      ? { referenceVersionId: referenceId(s)! }
      : {
          firstFrameId: s.approvedPreviewId!,
          ...(needsLast.value ? { lastFrameId: s.approvedLastFrameId! } : {}),
        }),
  }))
  const identity = JSON.stringify([batch, version.id, inputs, common]),
    header = keys.for(identity)
  busy.value = true
  try {
    if (batch) {
      value(
        await api.POST('/episodes/{id}/render', {
          params: { ...pathId(episode.value), header },
          body: { ...common, boardId: version.id, shots: inputs },
        }),
      )
      checked.value = []
    } else {
      value(
        await api.POST('/shots/{id}/render', {
          params: { ...pathId(selected[0].id), header },
          body: { ...common, ...inputs[0] },
        }),
      )
    }
    keys.accepted(identity)
    toast.success(selected.length + ' 个镜头已加入渲染队列')
    await store.loadTasks()
  } catch (e) {
    store.report(e)
    await refresh()
  } finally {
    busy.value = false
  }
}
const boardLink = computed(() => ({
  path: '/projects/' + projectId.value + '/storyboard',
  query: { episode: episode.value || undefined },
}))
</script>
<template>
  <section class="render-room">
    <div class="page-head">
      <div>
        <h1>视频渲染</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span>{{ project?.title }}
        </p>
      </div>
      <router-link :to="boardLink">打开分镜工作台</router-link>
    </div>
    <div class="panel render-toolbar">
      <label class="field"
        >剧集<n-select
          aria-label="渲染剧集"
          v-model:value="episode"
          :options="episodes"
          placeholder="选择剧集"
      /></label>
      <label class="field"
        >分镜版本<n-select
          aria-label="渲染分镜版本"
          :value="historyId || versions[0]?.id || null"
          :options="
            versions.map((v) => ({
              value: v.id,
              label: 'V' + v.revision + ' · ' + approvalLabel(v.approvalStatus),
            }))
          "
          @update:value="(id) => (historyId = id === versions[0]?.id ? '' : id)"
          placeholder="暂无分镜"
      /></label>
      <label class="field"
        >视频工作流<n-select
          v-model:value="workflowId"
          aria-label="视频工作流"
          :options="
            flows.map((w) => ({
              value: w.id,
              label:
                w.manifest.title +
                ' · v' +
                w.manifest.version +
                (w.manifest.mode === 'mock' ? '（模拟）' : ''),
            }))
          "
          placeholder="暂无可用模板"
      /></label>
    </div>
    <div v-if="!flows.length && !store.loading" class="notice">
      当前没有可用的图生视频模板。<router-link to="/workflows">检测工作流</router-link>
    </div>
    <div v-if="board?.version && !editable" class="notice">
      {{
        !active
          ? '项目已归档。'
          : historical
            ? '正在查看历史分镜版本。'
            : board.stale
              ? '上游已更新，请在分镜工作台核对并审核当前版本。'
              : '请先审核本集剧本和当前分镜。'
      }}
    </div>
    <div v-if="board?.shots.length && shot" class="render-layout">
      <section class="panel render-directory">
        <div class="panel-title">
          <h2>镜头列表</h2>
          <small>{{ board.shots.length }} 个镜头</small>
        </div>
        <div
          class="render-shot"
          v-for="s in board.shots"
          :key="s.revisionId"
          :data-focused="s.id === focused"
        >
          <input
            type="checkbox"
            :aria-label="'批量选择镜头 ' + s.ordinal"
            :checked="checked.includes(s.revisionId)"
            :disabled="!editable || !selectionReady(s) || busy"
            @change="toggle(s, $event)"
          />
          <button type="button" :aria-pressed="s.id === focused" @click="focused = s.id">
            <strong>场 {{ s.sceneOrdinal }} · 镜头 {{ s.ordinal }}</strong
            ><span>{{ s.action }}</span>
            <small>{{ inputStatus(s) }}</small>
          </button>
        </div>
        <div class="inset">
          <n-button block :disabled="!editable || !checkedShots.length || busy" @click="submit(true)"
            >批量渲染（{{ checkedShots.length }}）</n-button
          >
        </div>
      </section>
      <div class="render-detail">
        <section class="panel render-settings">
          <div class="panel-title">
            <h2>场 {{ shot.sceneOrdinal }} · 镜头 {{ shot.ordinal }}</h2>
            <small>镜头 V{{ shot.revision }}</small>
          </div>
          <div class="inset form-stack">
            <p>{{ shot.action }}</p>
            <small>{{ shot.framing }} · {{ shot.camera }} · 分镜计划 {{ shot.durationMs / 1000 }} 秒</small>
            <template v-if="flow">
              <div v-if="scalarFields.length" class="render-parameters">
                <parameter-field
                  v-for="p in scalarFields"
                  :key="p.name"
                  :binding="p"
                  v-model="parameters[p.name]"
                />
              </div>
              <div v-if="duration" class="render-duration">
                <label><input v-model="customDuration" type="checkbox" /> 自定义渲染时长</label>
                <parameter-field
                  v-if="customDuration"
                  :binding="duration"
                  v-model="parameters.durationSeconds"
                />
                <small v-else
                  >按每个分镜的计划时长生成<template v-if="duration.min != null && duration.max != null"
                    >，模板范围 {{ duration.min }}–{{ duration.max }} 秒</template
                  >。</small
                >
              </div>
              <label v-if="flow.manifest.parameters.some((p) => p.name === 'prompt')" class="field"
                >补充提示词<n-input
                  v-model:value="prompt"
                  type="textarea"
                  maxlength="4000"
                  placeholder="可选，批量时分别补充到每个镜头的画面动作与运镜"
              /></label>
            </template>
            <div class="version-actions">
              <n-button
                type="primary"
                :disabled="!editable || !selectionReady(shot) || busy"
                @click="submit(false)"
                >渲染此镜头</n-button
              >
              <router-link
                v-if="currentTasks[0]"
                :to="{ path: '/tasks', query: { task: currentTasks[0].id } }"
                >最新任务 · {{ statusText[currentTasks[0].status] }}</router-link
              >
            </div>
          </div>
        </section>
        <reference-picker
          v-if="referenceMode"
          :options="references(shot)"
          :model-value="referenceId(shot)"
          :editable="editable"
          :frame-size="flow?.manifest.frameSize"
          @update:model-value="
            (id) => {
              if (shot) chosenRefs[shot.revisionId] = id
            }
          "
        />
        <frame-review
          v-else
          :shot="shot"
          :editable="editable"
          :needs-last="needsLast"
          :frame-size="flow?.manifest.frameSize"
          @changed="refresh"
        />
        <render-history :shot="shot" :editable="editable" :board-id="board.version!.id" />
      </div>
    </div>
    <div v-else class="panel">
      <blank-panel
        :title="loading ? '正在读取分镜' : '暂无可渲染的镜头'"
        detail="在分镜工作台生成并审核分镜后，可在这里选择首帧、提交视频并审核结果。"
      />
    </div>
  </section>
</template>
<style src="./render.css"></style>
