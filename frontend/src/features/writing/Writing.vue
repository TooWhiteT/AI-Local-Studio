<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { NButton, useMessage } from 'naive-ui'
import { api, value, pathId, allPages, terminal, statusText, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import StoryForm from './StoryForm.vue'
import PlanForm from './PlanForm.vue'
import ScriptForm from './ScriptForm.vue'
import './writing.css'

type Kind = 'OUTLINE' | 'EPISODE_PLAN' | 'SCRIPT'
type Revision = Model['WritingRevision']
type Document = Revision['content']
const names = { OUTLINE: '故事大纲', EPISODE_PLAN: '分集规划', SCRIPT: '分集剧本' }
const paths = {
  OUTLINE: '/projects/{id}/outline',
  EPISODE_PLAN: '/projects/{id}/episodes/plan',
  SCRIPT: '/episodes/{id}/script',
} as const
const generation = {
  OUTLINE: '/projects/{id}/outline/generate',
  EPISODE_PLAN: '/projects/{id}/episodes/plan',
  SCRIPT: '/episodes/{id}/script/generate',
} as const
const approvals = {
  OUTLINE: '/projects/{id}/outline/{revisionId}/approve',
  EPISODE_PLAN: '/projects/{id}/episodes/plan/{revisionId}/approve',
  SCRIPT: '/episodes/{id}/script/{revisionId}/approve',
} as const
const labels = { DRAFT: '草稿', REVIEW_REQUIRED: '待审核', APPROVED: '已审核' }
const route = useRoute(),
  store = useStudio(),
  toast = useMessage()
const projectId = computed(() => String(route.params.id)),
  project = computed(() => store.projects.find((p) => p.id === projectId.value))
const kind = ref<Kind>('OUTLINE'),
  episode = ref(''),
  selected = ref(''),
  base = ref(0),
  busy = ref(false),
  loading = ref(true)
const outlines = ref<Revision[]>([]),
  history = ref<Revision[]>([]),
  directory = ref<Model['EpisodeDirectory']>({ plan: null, episodes: [], retired: [] })
const engine = ref<Model['TextEngineInfo']>(),
  brief = ref(''),
  count = ref(3),
  seconds = ref(60)
const characters = ref(2),
  locations = ref(3),
  scenes = ref(''),
  lines = ref('')
const draft = ref<Document>(),
  baseline = ref(''),
  boundStory = ref<string | null>(null),
  boundPlan = ref<string | null>(null),
  updatingReferences = ref(false)
const latest = computed(() => history.value[0]),
  shown = computed(() => history.value.find((r) => r.id === selected.value))
const historical = computed(() => !!shown.value && shown.value.id !== latest.value?.id)
const target = computed(() => (kind.value === 'SCRIPT' ? episode.value : projectId.value))
const dirty = computed(() => !!draft.value && (updatingReferences.value || JSON.stringify(draft.value) !== baseline.value))
const currentOutline = computed(() => outlines.value[0])
const viewingStaleScript = computed(() => kind.value === 'SCRIPT' && !!shown.value?.stale && !updatingReferences.value)
const referenceOutline = computed(
  () =>
    (
      outlines.value.find(
        (r) => r.id === (historical.value || viewingStaleScript.value ? shown.value?.storyRevisionId : boundStory.value),
      ) ?? currentOutline.value
    )?.content as Model['StoryOutline'] | undefined,
)
const currentTask = computed(() =>
  store.tasks.find(
    (t) => t.projectId === projectId.value && t.kind === kind.value && t.targetId === target.value,
  ),
)
const writingActive = computed(() =>
  store.tasks.some((t) => t.kind === kind.value && t.targetId === target.value && !terminal(t)),
)
const prerequisite = computed(() => {
  if (project.value?.status === 'ARCHIVED') return '项目已归档'
  if (kind.value === 'SCRIPT' && directory.value.retired.some((e) => e.id === episode.value))
    return '该集已移出当前分集规划，可查阅历史剧本。'
  if (kind.value !== 'OUTLINE' && currentOutline.value?.approvalStatus !== 'APPROVED')
    return '请先审核当前大纲'
  if (kind.value === 'SCRIPT' && (!directory.value.plan || directory.value.plan.stale))
    return '请先更新分集规划'
  return ''
})
let stamp = 0,
  submittedKey = '',
  submittedBody = ''
const copy = <T,>(v: T): T => JSON.parse(JSON.stringify(v))
function empty(): Document {
  if (kind.value === 'OUTLINE')
    return {
      title: '',
      logline: '',
      world: '',
      characters: [{ key: 'c1', name: '', description: '' }],
      locations: [{ key: 'l1', name: '', description: '' }],
      beats: [''],
    }
  if (kind.value === 'EPISODE_PLAN')
    return { episodes: [{ title: '', summary: '', targetDurationMs: 60000 }] }
  return { title: directory.value.episodes.find((e) => e.id === episode.value)?.title ?? '', scenes: [] }
}
function selectVersion(id: string) {
  updatingReferences.value = false
  selected.value = id
  const rev = history.value.find((r) => r.id === id)
  draft.value = rev ? copy(rev.content) : empty()
  baseline.value = JSON.stringify(draft.value)
  base.value = latest.value?.revision ?? 0
  boundStory.value = currentOutline.value?.id ?? null
  boundPlan.value = directory.value.plan?.id ?? null
}
function reviseAgainstCurrentPlan() {
  const outline = currentOutline.value
  const plan = directory.value.plan
  if (!draft.value || !outline || !plan || historical.value || prerequisite.value) return
  const { characters, locations } = outline.content as Model['StoryOutline']
  const cast = new Set(characters.map((person) => person.key))
  const locationsByKey = new Set(locations.map((location) => location.key))
  for (const scene of (draft.value as Model['EpisodeScript']).scenes) {
    if (!locationsByKey.has(scene.locationKey)) scene.locationKey = ''
    scene.characters = scene.characters.filter((key) => cast.has(key))
    for (const line of scene.dialogues) {
      if (!scene.characters.includes(line.speaker)) line.speaker = ''
    }
  }
  boundStory.value = outline.id
  boundPlan.value = plan.id
  updatingReferences.value = true
}
async function refresh(reset = false) {
  const n = ++stamp,
    p = projectId.value,
    k = kind.value,
    id = target.value
  if (reset) loading.value = true
  try {
    const [stories, dir, versions, writer] = await Promise.all([
      allPages(async (cursor) =>
        value(
          await api.GET('/projects/{id}/outline', {
            params: { ...pathId(p), query: { cursor, limit: 100 } },
          }),
        ),
      ),
      api.GET('/projects/{id}/episodes', { params: pathId(p) }).then(value),
      k === 'OUTLINE'
        ? Promise.resolve(null)
        : allPages(async (cursor) =>
            value(await api.GET(paths[k], { params: { ...pathId(id), query: { cursor, limit: 100 } } })),
          ),
      api.GET('/engines/writer').then(value),
    ])
    if (n !== stamp) return
    const keepHistorical = historical.value
    outlines.value = stories
    directory.value = dir
    history.value = versions ?? stories
    engine.value = writer
    // An SSE refresh may finish the reload requested after a save or navigation.
    if (loading.value || (!dirty.value && !keepHistorical)) selectVersion(latest.value?.id ?? '')
  } catch (e) {
    if (n === stamp) store.report(e)
  } finally {
    if (n === stamp) loading.value = false
  }
}
function mayDiscard() {
  return !dirty.value || window.confirm('离开并放弃未保存的修改？')
}
function open(k: Kind, id = '') {
  if ((k === kind.value && id === episode.value) || !mayDiscard()) return
  kind.value = k
  episode.value = id
  brief.value = ''
  draft.value = undefined
  history.value = []
  void refresh(true)
}
onBeforeRouteLeave(mayDiscard)
onBeforeRouteUpdate(mayDiscard)
watch(
  [projectId, () => route.query.task, () => route.query.episode],
  async ([p, taskId, requestedEpisode]) => {
    ++stamp
    kind.value = 'OUTLINE'
    episode.value = ''
    brief.value = ''
    draft.value = undefined
    history.value = []
    outlines.value = []
    if (typeof taskId === 'string') {
      try {
        const task = value(await api.GET('/tasks/{id}', { params: pathId(taskId) }))
        if (projectId.value !== p || route.query.task !== taskId) return
        if (task.projectId === p && (task.kind === 'OUTLINE' || task.kind === 'EPISODE_PLAN' || task.kind === 'SCRIPT')) {
          kind.value = task.kind
          episode.value = task.kind === 'SCRIPT' ? task.targetId : ''
          await refresh(true)
          if (
            route.query.task === taskId &&
            task.writingResultId &&
            history.value.some((r) => r.id === task.writingResultId)
          )
            selectVersion(task.writingResultId)
          return
        }
      } catch (e) {
        store.report(e)
      }
    }
    await refresh(true)
    if (typeof requestedEpisode === 'string' && requestedEpisode === route.query.episode && p === projectId.value
      && [...directory.value.episodes, ...directory.value.retired].some(e => e.id === requestedEpisode)) open('SCRIPT', requestedEpisode)
  },
  { immediate: true },
)
watch(
  () => store.assetsChanged,
  () => void refresh(),
)
function changeVersion(e: Event) {
  const el = e.target as HTMLSelectElement
  if (mayDiscard()) selectVersion(el.value)
  else el.value = selected.value
}
function upstream() {
  return {
    storyRevisionId: kind.value === 'OUTLINE' ? null : boundStory.value,
    planId: kind.value === 'SCRIPT' ? boundPlan.value : null,
  }
}
async function perform(action: 'save' | 'approve' | 'generate') {
  if (busy.value || !draft.value) return
  const k = kind.value,
    id = target.value
  busy.value = true
  try {
    if (action === 'save') {
      value(
        await api.PUT(paths[k], {
          params: pathId(id),
          body: { expectedRevision: base.value, content: draft.value, ...upstream() },
        }),
      )
    } else if (action === 'approve') {
      value(
        await api.POST(approvals[k], {
          params: { path: { id, revisionId: selected.value } },
          body: { expectedRevision: base.value },
        }),
      )
    } else {
      const body = {
        expectedRevision: base.value,
        brief: brief.value,
        episodeCount: count.value,
        targetDurationMs: Math.round(seconds.value * 1000),
        ...upstream(),
        ...(k === 'OUTLINE' ? { characterCount: characters.value, locationCount: locations.value } : {}),
        ...(k === 'SCRIPT'
          ? {
              sceneCount: scenes.value === '' ? null : Number(scenes.value),
              linesPerScene: lines.value === '' ? null : Number(lines.value),
            }
          : {}),
      }
      const identity = JSON.stringify([k, id, body])
      if (submittedBody !== identity) {
        submittedBody = identity
        submittedKey = crypto.randomUUID()
      }
      value(
        await api.POST(generation[k], {
          params: { ...pathId(id), header: { 'Idempotency-Key': submittedKey } },
          body,
        }),
      )
      submittedBody = ''
      await store.loadTasks()
    }
    if (id === target.value && k === kind.value) await refresh(action !== 'generate')
    toast.success(
      action === 'generate' ? '编剧任务已入队' : action === 'approve' ? '版本已审核' : '已保存新版本',
    )
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="writing">
    <div class="page-head">
      <div>
        <h1>编剧工作台</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span>{{ project?.title }}
        </p>
      </div>
    </div>
    <div class="writing-layout">
      <aside class="panel writing-index">
        <h2>目录</h2>
        <button
          v-for="k in ['OUTLINE', 'EPISODE_PLAN'] as const"
          :key="k"
          :class="{ picked: kind === k }"
          @click="open(k)"
        >
          {{ names[k] }}
        </button>
        <button
          v-for="ep in directory.episodes"
          :key="ep.id"
          :class="{ picked: kind === 'SCRIPT' && episode === ep.id }"
          @click="open('SCRIPT', ep.id)"
        >
          第 {{ ep.ordinal }} 集 · {{ ep.title }}
        </button>
        <button
          v-for="ep in directory.retired"
          :key="ep.id"
          :class="{ picked: kind === 'SCRIPT' && episode === ep.id }"
          @click="open('SCRIPT', ep.id)"
        >
          已移出 · 第 {{ ep.ordinal }} 集 · {{ ep.title }}
        </button>
      </aside>
      <div class="panel writing-editor">
        <div class="writing-section-head">
          <h2>{{ names[kind] }}</h2>
          <select v-if="history.length" aria-label="修订历史" :value="selected" @change="changeVersion">
            <option v-for="r in history" :key="r.id" :value="r.id">
              版本 {{ r.revision }} · {{ labels[r.approvalStatus] }}{{ r.stale ? ' · 上游已变更' : '' }}
            </option>
          </select>
        </div>
        <p v-if="loading">正在读取稿件…</p>
        <template v-else-if="draft">
          <p v-if="prerequisite || shown?.stale" class="notice">
            {{ prerequisite || '上游已变更，可以依据当前版本修订或重新生成。' }}
          </p>
          <p v-if="dirty && base !== (latest?.revision ?? 0)" class="notice">
            已有新版本，当前修改仍保留。请在修订历史中重新选择版本后合并修改。
          </p>
          <fieldset :disabled="historical || viewingStaleScript || !!prerequisite || busy" class="writing-fields">
            <StoryForm v-if="kind === 'OUTLINE'" :form="draft as Model['StoryOutline']" />
            <PlanForm v-else-if="kind === 'EPISODE_PLAN'" :form="draft as Model['EpisodePlan']" />
            <ScriptForm v-else :form="draft as Model['EpisodeScript']" :outline="referenceOutline" />
          </fieldset>
          <div class="writing-actions">
            <n-button v-if="viewingStaleScript && !historical && !prerequisite" :disabled="busy" @click="reviseAgainstCurrentPlan">
              按当前规划修订
            </n-button>
            <n-button
              type="primary"
              :disabled="busy || !dirty || historical || viewingStaleScript || !!prerequisite"
              @click="perform('save')"
              >保存新版本</n-button
            >
            <n-button
              :disabled="
                busy ||
                dirty ||
                !shown ||
                shown.stale ||
                shown.approvalStatus === 'APPROVED' ||
                historical ||
                !!prerequisite
              "
              @click="perform('approve')"
              >审核通过</n-button
            >
          </div>
        </template>
      </div>
      <aside class="panel writing-ai">
        <h2>AI 编剧</h2>
        <p class="writer-model">
          {{ engine?.available ? (engine.mode === 'mock' ? 'Mock 模拟模型' : engine.model) : engine?.reason }}
        </p>
        <label class="field"
          >创作意图<textarea
            v-model="brief"
            rows="4"
            maxlength="4000"
            placeholder="例如：沿海小镇的修表师收到一封迟到三十年的信"
          />
        </label>
        <div v-if="kind === 'OUTLINE'" class="writing-pair">
          <label class="field"
            >角色数<input v-model.number="characters" type="number" min="1" max="12"
          /></label>
          <label class="field"
            >地点数<input v-model.number="locations" type="number" min="1" max="12"
          /></label>
        </div>
        <div v-if="kind === 'SCRIPT'" class="writing-pair">
          <label class="field"
            >场次数<input v-model="scenes" type="number" min="1" max="30" placeholder="自动"
          /></label>
          <label class="field"
            >每场对白句数<input v-model="lines" type="number" min="0" max="40" placeholder="自动"
          /></label>
        </div>
        <div v-if="kind === 'EPISODE_PLAN'" class="writing-pair">
          <label class="field">集数<input v-model.number="count" type="number" min="1" max="12" /></label>
          <label class="field"
            >每集秒数<input v-model.number="seconds" type="number" min="1" max="600"
          /></label>
        </div>
        <n-button
          type="primary"
          :disabled="
            busy ||
            loading ||
            !engine?.available ||
            !!prerequisite ||
            dirty ||
            historical ||
            writingActive ||
            (kind === 'OUTLINE' && !brief.trim())
          "
          @click="perform('generate')"
          >生成{{ names[kind] }}</n-button
        >
        <small v-if="dirty">保存修改后可继续生成。</small>
        <router-link v-if="currentTask" :to="{ path: '/tasks', query: { task: currentTask.id } }"
          >最近任务 · {{ statusText[currentTask.status] }}</router-link
        >
      </aside>
    </div>
  </section>
</template>
