<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { NButton, NSelect } from 'naive-ui'
import { useStudio } from '../../stores/studio'
import { useEpisodeScript } from '../storyboard/production'
import { useTimeline } from './useTimeline'
import { clipIssue, displayTime, shotLabel, validVideos } from './timeline'
import ClipEditor from './ClipEditor.vue'
import Exports from './Exports.vue'

const store = useStudio(),
  route = useRoute(),
  projectId = computed(() => String(route.params.id))
const project = computed(() => store.projects.find((p) => p.id === projectId.value))
const { episode, options: episodes } = useEpisodeScript(projectId, String(route.query.episode ?? ''))
const {
  board,
  history,
  sources,
  view,
  clips,
  focus,
  focused,
  source,
  shown,
  current,
  historical,
  dirty,
  saveable,
  baseRevision,
  busy,
  loading,
  add,
  move,
  remove,
  update,
  save,
  selectVersion,
  startCurrentBoard,
} = useTimeline(episode)
const active = computed(() => project.value?.status === 'ACTIVE'),
  editable = computed(() => !!active.value && saveable.value && !busy.value)
const available = computed(() => sources.value.filter((s) => validVideos(s).length))
const issue = computed(() => {
  if (!dirty.value || loading.value) return ''
  for (const [i, c] of clips.value.entries()) {
    const reason = clipIssue(
      c,
      sources.value.find((s) => s.shot.id === c.shotId),
    )
    if (reason) return '片段 ' + (i + 1) + ' · ' + reason
  }
  return ''
})
const total = computed(() => clips.value.reduce((n, c) => n + c.durationMs, 0))
let openedTimeline = ''
watch([() => route.query.timeline, history], ([id, list]) => {
  if (typeof id === 'string' && id !== openedTimeline && list.some((v) => v.id === id)) {
    openedTimeline = id
    if (view.value !== id) selectVersion(id)
  }
})
function mayLeave() {
  return !dirty.value || window.confirm('离开并放弃未保存的剪辑？')
}
onBeforeRouteLeave(mayLeave)
onBeforeRouteUpdate(mayLeave)
</script>
<template>
  <section class="post-room">
    <div class="page-head">
      <div>
        <h1>剪辑成片</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span>{{ project?.title }}
        </p>
      </div>
      <router-link :to="{ path: '/projects/' + projectId + '/audio', query: { episode } }"
        >打开角色配音</router-link
      >
    </div>
    <div class="panel post-toolbar">
      <label class="field"
        >剧集<n-select
          :value="episode"
          :options="episodes"
          aria-label="剪辑剧集"
          placeholder="选择剧集"
          @update:value="
            (id) => {
              if (mayLeave()) episode = id
            }
          "
      /></label>
      <label class="field"
        >时间线版本<select :value="view" @change="selectVersion(($event.target as HTMLSelectElement).value)">
          <option v-if="!view" value="">新剪辑</option>
          <option v-for="v in history" :key="v.id" :value="v.id">
            V{{ v.revision }} · {{ displayTime(v.durationMs) }}{{ v.stale ? ' · 上游已更新' : '' }}
          </option>
        </select></label
      >
      <span v-if="shown">{{ shown.aspectRatio }} · {{ shown.fps }} fps</span>
    </div>
    <div v-if="current && baseRevision !== current.revision && dirty" class="notice">
      时间线已有新版本，请先核对修订历史。
    </div>
    <div v-if="historical || shown?.stale" class="notice">
      {{ historical ? '正在查看历史时间线。' : '所用素材已更新。' }}
      <button
        v-if="active && board?.version?.approvalStatus === 'APPROVED' && !board.stale"
        class="text-link"
        @click="startCurrentBoard"
      >
        按当前分镜重新剪辑
      </button>
    </div>
    <div v-if="!board?.version || board.version.approvalStatus !== 'APPROVED' || board.stale" class="notice">
      请先审核当前分镜与镜头视频。<router-link
        :to="{ path: '/projects/' + projectId + '/render', query: { episode } }"
        >打开视频渲染</router-link
      >
    </div>
    <div class="post-layout">
      <section class="panel timeline-list">
        <div class="panel-title">
          <h2>时间线</h2>
          <small>{{ clips.length }} 个片段 · {{ displayTime(total) }}</small>
        </div>
        <div class="inset form-stack">
          <div v-if="editable" class="form-stack">
            <label class="field"
              >可用镜头<select
                :value="''"
                @change="
                  add(($event.target as HTMLSelectElement).value)
                  ;($event.target as HTMLSelectElement).value = ''
                "
              >
                <option value="">选择已审核镜头</option>
                <option v-for="s in available" :key="s.shot.id" :value="s.shot.id">
                  {{ shotLabel(s.shot) }}
                </option>
              </select></label
            >
            <n-button
              size="small"
              :disabled="!available.some((s) => !clips.some((c) => c.shotId === s.shot.id)) || loading"
              @click="add()"
              >按分镜顺序加入全部镜头</n-button
            >
          </div>
          <p v-if="!clips.length" class="post-empty">
            {{ loading ? '正在读取素材…' : '时间线尚未加入镜头。' }}
          </p>
          <article v-for="(c, i) in clips" :key="i" class="timeline-clip" :data-focused="focus === i">
            <button class="timeline-focus" :aria-pressed="focus === i" @click="focus = i">
              <strong>{{
                sources.find((s) => s.shot.id === c.shotId)?.shot
                  ? shotLabel(sources.find((s) => s.shot.id === c.shotId)!.shot)
                  : '历史镜头'
              }}</strong
              ><small>{{ displayTime(c.durationMs) }}</small>
            </button>
            <div v-if="editable" class="post-controls">
              <n-button size="tiny" :disabled="i === 0" @click="move(i, -1)">上移</n-button
              ><n-button size="tiny" :disabled="i === clips.length - 1" @click="move(i, 1)">下移</n-button
              ><n-button size="tiny" @click="remove(i)">移除</n-button>
            </div>
          </article>
          <p v-if="issue" class="notice">{{ issue }}</p>
          <n-button
            type="primary"
            :disabled="!editable || !dirty || !clips.length || !!issue || loading"
            @click="save"
            >保存剪辑版本</n-button
          >
        </div>
      </section>
      <div class="form-stack">
        <clip-editor v-if="focused" :clip="focused" :source="source" :editable="editable" @changed="update" />
        <exports
          v-if="episode"
          :episode="episode"
          :timeline="shown"
          :history="history"
          :can-submit="active && !!shown && !shown.stale && !historical"
          :dirty="dirty"
        />
      </div>
    </div>
  </section>
</template>
<style src="./post.css"></style>
