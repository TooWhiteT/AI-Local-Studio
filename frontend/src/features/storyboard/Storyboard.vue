<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NInput, NInputNumber, NSelect, useMessage } from 'naive-ui'
import {
  api,
  value,
  pathId,
  allPages,
  mediaUrl,
  statusText,
  terminal,
  type Model,
  type Asset,
} from '../../api/client'
import { useStudio } from '../../stores/studio'
import BlankPanel from '../../components/BlankPanel.vue'
import ShotPanel from './ShotPanel.vue'
import ContinuityEditor from './ContinuityEditor.vue'
import { approvalLabel, useEpisodeScript, submissionKey } from './production'

const store = useStudio(),
  route = useRoute(),
  toast = useMessage(),
  keys = submissionKey()
const projectId = computed(() => String(route.params.id)),
  project = computed(() => store.projects.find((p) => p.id === projectId.value))
const {
  episode,
  script,
  approved,
  options: episodes,
} = useEpisodeScript(projectId, String(route.query.episode ?? ''))
const board = ref<Model['Board']>(),
  history = ref<Model['BoardSummary'][]>([]),
  assets = ref<Asset[]>([])
const view = ref(''),
  selected = ref(''),
  references = ref<string[]>([]),
  count = ref<number | null>(2),
  brief = ref(''),
  busy = ref(false)
const historical = computed(() => !!view.value && view.value !== history.value[0]?.id)
const shot = computed(() => board.value?.shots.find((s) => s.id === selected.value))
const scene = computed(() => board.value?.scenes.find((s) => s.id === shot.value?.sceneId))
const active = computed(() => project.value?.status === 'ACTIVE')
const editable = computed(
  () =>
    active.value &&
    approved.value &&
    !historical.value &&
    board.value?.version?.scriptRevisionId === script.value?.id,
)
const refOptions = computed(() =>
  assets.value.flatMap((a) =>
    a.versions
      .filter((v) => v.approvalStatus === 'APPROVED')
      .map((v) => ({
        label: a.name + ' · V' + v.revision + (a.activeVersionId === v.id ? ' · 当前' : ''),
        value: v.id,
      })),
  ),
)
const currentTask = computed(() =>
  store.tasks.find(
    (t) =>
      t.projectId === projectId.value &&
      ((t.kind === 'SHOT_PLAN' && t.targetId === episode.value) ||
        (['SHOT_REGENERATE', 'KEYFRAME'].includes(t.kind) &&
          !!board.value?.shots.some((s) => s.id === t.targetId))),
  ),
)
const runningPlan = computed(() =>
  store.tasks.some((t) => t.kind === 'SHOT_PLAN' && t.targetId === episode.value && !terminal(t)),
)
let boardRequest = 0,
  assetRequest = 0,
  defaultRefsFor = ''
function apply(data: Model['Board']) {
  board.value = data
  if (!data.shots.some((s) => s.id === selected.value)) selected.value = data.shots[0]?.id ?? ''
}
async function refresh() {
  const id = episode.value,
    version = view.value,
    token = ++boardRequest
  if (!id) {
    board.value = undefined
    history.value = []
    return
  }
  const [data, records] = await Promise.all([
    api
      .GET('/episodes/{id}/shots', { params: { ...pathId(id), query: { revisionId: version || undefined } } })
      .then(value),
    allPages(async (cursor) =>
      value(
        await api.GET('/episodes/{id}/shots/history', {
          params: { ...pathId(id), query: { limit: 100, cursor } },
        }),
      ),
    ),
  ])
  if (token !== boardRequest || id !== episode.value) return
  history.value = records
  apply(data)
}
watch(episode, () => {
  view.value = ''
  selected.value = ''
  board.value = undefined
  history.value = []
  void refresh().catch(store.report)
})
watch([view, () => store.assetsChanged], () => void refresh().catch(store.report))
watch(
  [projectId, () => store.assetsChanged],
  async ([id]) => {
    const token = ++assetRequest
    try {
      const rows = await allPages(async (cursor) =>
        value(
          await api.GET('/projects/{id}/assets', {
            params: { ...pathId(id), query: { cursor, limit: 100 } },
          }),
        ),
      )
      if (token !== assetRequest) return
      assets.value = rows
      if (defaultRefsFor !== id && rows.some((a) => a.activeVersionId)) {
        references.value = rows.flatMap((a) => (a.activeVersionId ? [a.activeVersionId] : [])).slice(0, 32)
        defaultRefsFor = id
      }
    } catch (e) {
      store.report(e)
    }
  },
  { immediate: true },
)
watch(projectId, () => {
  references.value = []
  assets.value = []
  defaultRefsFor = ''
})
async function changed(data: Model['Board']) {
  apply(data)
  view.value = ''
  await refresh()
}
async function move(offset: number) {
  if (!board.value?.version || !shot.value) return
  const ids = board.value.shots.map((s) => s.id),
    pos = ids.indexOf(shot.value.id),
    dest = pos + offset
  if (dest < 0 || dest >= ids.length) return
  ;[ids[pos], ids[dest]] = [ids[dest], ids[pos]]
  busy.value = true
  try {
    await changed(
      value(
        await api.PUT('/episodes/{id}/shots/order', {
          params: pathId(episode.value),
          body: { expectedRevision: board.value.version.revision, shotIds: ids },
        }),
      ),
    )
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
async function approveBoard() {
  const v = board.value?.version
  if (!v) return
  busy.value = true
  try {
    await changed(
      value(
        await api.POST('/episodes/{id}/shots/{boardId}/approve', {
          params: { path: { id: episode.value, boardId: v.id } },
          body: { expectedRevision: v.revision },
        }),
      ),
    )
    toast.success('分镜已审核')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
async function plan() {
  if (!script.value || !count.value) return
  busy.value = true
  const body = {
    scriptRevisionId: script.value.id,
    expectedRevision: history.value[0]?.revision ?? 0,
    assetVersionIds: references.value,
    shotsPerScene: count.value,
    brief: brief.value,
  }
  const id = episode.value,
    key = JSON.stringify([id, body])
  try {
    value(
      await api.POST('/episodes/{id}/shots/plan', { params: { ...pathId(id), header: keys.for(key) }, body }),
    )
    keys.accepted(key)
    toast.success('分镜计划已加入队列')
    await store.loadTasks()
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
let navigated = ''
watch(
  [() => route.query.task, episodes, () => store.tasks],
  async ([taskId, list, tasks]) => {
    if (typeof taskId !== 'string' || !list.length || navigated === taskId) return
    const t = tasks.find((t) => t.id === taskId && t.projectId === projectId.value)
    if (!t) return
    navigated = taskId
    try {
      let id = t.targetId,
        revision = t.writingResultId,
        targetShot = ''
      if (t.kind === 'SHOT_REGENERATE' || t.kind === 'KEYFRAME') {
        const found = value(
          await api.GET('/shots/{id}', {
            params: {
              ...pathId(t.targetId),
              query: {
                shotRevisionId: t.kind === 'KEYFRAME' ? (t.snapshot.shotRevisionId ?? undefined) : undefined,
              },
            },
          }),
        )
        id = found.episodeId
        revision = revision ?? found.version?.id ?? null
        targetShot = t.targetId
      } else if (t.kind !== 'SHOT_PLAN') return
      episode.value = id
      await Promise.resolve()
      view.value = revision ?? ''
      await refresh()
      if (targetShot) selected.value = targetShot
    } catch (e) {
      store.report(e)
    }
  },
  { immediate: true },
)
</script>
<template>
  <section>
    <div class="page-head">
      <div>
        <h1>分镜工作台</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span>{{ project?.title }}
        </p>
      </div>
      <router-link v-if="board?.version" :to="{ path: '/projects/' + projectId + '/render', query: { episode, shot: selected, board: board.version.id } }">打开视频渲染</router-link>
      <n-select
        aria-label="分镜剧集"
        class="episode-select"
        v-model:value="episode"
        :options="episodes"
        placeholder="选择剧集"
      />
    </div>
    <div v-if="!approved" class="notice">
      请先审核本集的最新剧本。<router-link :to="'/projects/' + projectId + '/writing'">打开剧本</router-link>
    </div>
    <section v-if="active && episode" class="panel plan-controls">
      <div class="panel-title">
        <h2>按场拆镜</h2>
        <small v-if="approved">剧本 V{{ script?.revision }} · 已审核</small>
      </div>
      <div class="plan-fields inset">
        <label class="field"
          >参考资产版本<n-select
            aria-label="拆镜参考版本"
            multiple
            v-model:value="references"
            :options="refOptions"
            placeholder="选择已审核资产"
        /></label>
        <label class="field"
          >每场镜头数<n-input-number
            aria-label="每场镜头数"
            v-model:value="count"
            :min="1"
            :max="8"
            :precision="0"
        /></label>
        <label class="field"
          >导演说明<n-input v-model:value="brief" maxlength="4000" placeholder="可选，例如强调人物反应"
        /></label>
        <n-button
          type="primary"
          :loading="busy || runningPlan"
          :disabled="!approved || !references.length || historical || !count || runningPlan"
          @click="plan"
          >生成分镜计划</n-button
        >
      </div>
      <div v-if="!refOptions.length" class="inset muted">
        <router-link :to="'/projects/' + projectId + '/assets'">前往资产页</router-link
        >，审核并选用参考图后即可拆镜。
      </div>
    </section>
    <div class="production-toolbar board-toolbar">
      <label class="field history-select"
        >分镜版本<n-select
          aria-label="分镜版本"
          :value="view || history[0]?.id"
          :options="
            history.map((v) => ({
              value: v.id,
              label: 'V' + v.revision + ' · ' + approvalLabel(v.approvalStatus),
            }))
          "
          placeholder="暂无版本"
          @update:value="(v) => (view = v === history[0]?.id ? '' : v)"
      /></label>
      <span v-if="board?.stale" class="status-tag" data-state="UNKNOWN">上游已更新 · 保留原引用</span>
      <n-button
        v-if="editable && board?.version && board.version.approvalStatus !== 'APPROVED'"
        :disabled="busy || board.stale"
        @click="approveBoard"
        >审核当前分镜</n-button
      >
      <router-link v-if="currentTask" :to="{ path: '/tasks', query: { task: currentTask.id } }"
        >最近任务 · {{ statusText[currentTask.status] }}</router-link
      >
    </div>
    <div v-if="board?.version && shot && scene" class="board-grid">
      <section class="panel">
        <div class="panel-title">
          <h2>镜头列表</h2>
          <small
            >{{ board.shots.length }} 镜 ·
            {{ board.shots.reduce((a, s) => a + s.durationMs, 0) / 1000 }} 秒</small
          >
        </div>
        <div class="shot-list">
          <button
            v-for="s in board.shots"
            :key="s.id"
            :aria-pressed="s.id === selected"
            @click="selected = s.id"
          >
            <strong>镜头 {{ s.ordinal }} · {{ s.framing }}</strong
            ><small>第 {{ s.sceneOrdinal }} 场 · {{ s.durationMs / 1000 }} 秒</small>
            <span>{{ s.action }}</span>
          </button>
        </div>
        <div v-if="editable" class="version-actions inset">
          <n-button size="small" :disabled="busy || shot.ordinal === 1" @click="move(-1)">上移</n-button
          ><n-button size="small" :disabled="busy || shot.ordinal === board.shots.length" @click="move(1)"
            >下移</n-button
          >
        </div>
      </section>
      <section class="panel">
        <div class="panel-title">
          <h2>镜头 {{ shot.ordinal }} 预览</h2>
          <small>第 {{ scene.ordinal }} 场 · {{ scene.location }}</small>
        </div>
        <div class="inset form-stack">
          <img
            v-for="p in shot.previews"
            :key="p.id"
            class="frame-preview"
            :src="mediaUrl(p.id)"
            :alt="'镜头 ' + shot.ordinal + ' 预览图'"
          />
          <div v-if="!shot.previews.length" class="empty-frame">尚无分镜预览图</div>
          <p>{{ shot.action }}</p>
          <h3>绑定的参考资产</h3>
          <div v-if="shot.references.length" class="reference-grid">
            <figure v-for="r in shot.references" :key="r.versionId">
              <img :src="mediaUrl(r.file.id)" :alt="r.name + ' V' + r.revision" />
              <figcaption>
                {{ r.name }} · V{{ r.revision
                }}<small v-if="r.needsReview">资产选用已变化，镜头保留原图</small
                ><small v-else-if="!r.isCurrent">已选定的历史版本</small>
              </figcaption>
            </figure>
          </div>
          <p v-else class="muted">本镜头未绑定参考图。</p>
          <continuity-editor
            :key="scene.id + historical"
            :scene="scene"
            :editable="editable"
            @saved="refresh().catch(store.report)"
          />
        </div>
      </section>
      <shot-panel
        :key="shot.id + historical"
        :shot="shot"
        :board="board.version"
        :editable="editable"
        :options="refOptions"
        @changed="(data) => changed(data).catch(store.report)"
        @queued="store.loadTasks().catch(store.report)"
      />
    </div>
    <blank-panel v-else title="本集尚无分镜" detail="生成计划后，可在这里调整镜头、绑定资产并查看预览图。" />
  </section>
</template>
