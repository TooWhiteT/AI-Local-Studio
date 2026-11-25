<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { NButton, NSelect } from 'naive-ui'
import { api, allPages, value, pathId, type Model } from '../../api/client'
import { useStudio } from '../../stores/studio'
import { useRenderBoard } from '../render/useRenderBoard'
import { shotLabel } from '../edit/timeline'
import VoiceBindings from './VoiceBindings.vue'
import ShotRecordings from './ShotRecordings.vue'
const route = useRoute(),
  store = useStudio(),
  projectId = computed(() => String(route.params.id))
const project = computed(() => store.projects.find((p) => p.id === projectId.value))
const { episode, episodes, script, board, focused, shot, editable } = useRenderBoard(projectId)
const bindings = ref<Model['VoiceBinding'][]>([]),
  engine = ref<Model['TtsInfo']>(),
  roles = ref<Model['StoryOutline']['characters']>([]),
  scriptRoles = ref<Model['StoryOutline']['characters']>([]),
  approvedOutline = ref(false)
const names = computed(() => Object.fromEntries(scriptRoles.value.map((r) => [r.key, r.name])))
let token = 0,
  roleToken = 0
async function load() {
  const id = projectId.value,
    n = ++token
  try {
    const [voices, info] = await Promise.all([
      api.GET('/projects/{id}/voices', { params: pathId(id) }).then(value),
      api.GET('/engines/tts').then(value),
    ])
    if (n !== token) return
    bindings.value = voices
    engine.value = info
  } catch (e) {
    if (n === token) store.report(e)
  }
}
watch(
  projectId,
  () => {
    bindings.value = []
    engine.value = undefined
    void load()
  },
  { immediate: true },
)
watch(
  [projectId, () => script.value?.storyRevisionId],
  async ([id, story]) => {
    const n = ++roleToken
    roles.value = []
    scriptRoles.value = []
    approvedOutline.value = false
    try {
      const records = await allPages(async (cursor) =>
        value(
          await api.GET('/projects/{id}/outline', {
            params: { ...pathId(id!), query: { cursor, limit: 100 } },
          }),
        ),
      )
      if (n !== roleToken) return
      const latest = records[0]
      roles.value = (latest?.content as Model['StoryOutline'] | undefined)?.characters ?? []
      approvedOutline.value = latest?.approvalStatus === 'APPROVED'
      scriptRoles.value =
        (records.find((r) => r.id === story)?.content as Model['StoryOutline'] | undefined)?.characters ??
        roles.value
    } catch (e) {
      if (n === roleToken) store.report(e)
    }
  },
  { immediate: true },
)
</script>
<template>
  <section class="post-room">
    <div class="page-head">
      <div>
        <h1>角色配音</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span>{{ project?.title }}
        </p>
      </div>
      <router-link :to="{ path: '/projects/' + projectId + '/edit', query: { episode } }"
        >打开剪辑成片</router-link
      >
    </div>
    <div class="panel post-toolbar">
      <label class="field"
        >剧集<n-select
          aria-label="配音剧集"
          v-model:value="episode"
          :options="episodes"
          placeholder="选择剧集"
      /></label>
      <div class="post-info">
        <strong>{{ engine?.mode === 'mock' ? 'Mock 配音引擎' : '配音引擎' }}</strong
        ><small>{{
          engine?.available
            ? [engine.version, engine.device].filter(Boolean).join(' · ')
            : (engine?.reason ?? '正在检测服务')
        }}</small>
      </div>
      <n-button size="small" @click="load">检测音色服务</n-button>
    </div>
    <voice-bindings
      :project-id="projectId"
      :roles="roles"
      :bindings="bindings"
      :voices="engine?.voices ?? []"
      :editable="project?.status === 'ACTIVE' && approvedOutline && !!engine?.available"
      @changed="load"
    />
    <div v-if="board?.shots.length && shot" class="post-layout">
      <section class="panel">
        <div class="panel-title">
          <h2>分镜</h2>
          <small>{{ board.shots.length }} 个镜头</small>
        </div>
        <div class="post-list">
          <button
            v-for="s in board.shots"
            :key="s.id"
            :aria-pressed="focused === s.id"
            @click="focused = s.id"
          >
            <strong>{{ shotLabel(s) }}</strong
            ><span>{{ s.action }}</span>
          </button>
        </div>
      </section>
      <div class="form-stack">
        <div v-if="!editable" class="notice">当前镜头可试听历史录音。生成或选用录音需当前已审核分镜。</div>
        <shot-recordings
          :shot="shot"
          :bindings="bindings"
          :names="names"
          :editable="editable"
          :engine-ready="!!engine?.available"
          :writing-link="'/projects/' + projectId + '/writing?episode=' + episode"
        />
      </div>
    </div>
    <div v-else class="panel inset post-empty">
      本集暂无分镜。<router-link :to="{ path: '/projects/' + projectId + '/storyboard', query: { episode } }"
        >打开分镜工作台</router-link
      >
    </div>
  </section>
</template>
<style src="../edit/post.css"></style>
