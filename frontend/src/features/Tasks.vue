<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NSelect, NDrawer, NDrawerContent, NPopconfirm, useMessage } from 'naive-ui'
import {
  api,
  pathId,
  value,
  statusText,
  lastAttempt,
  terminal,
  dateText,
  mediaUrl,
  gib,
  type Task,
  type Model,
} from '../api/client'
import { useStudio } from '../stores/studio'
import StatusTag from '../components/StatusTag.vue'
import BlankPanel from '../components/BlankPanel.vue'
import ExportPlayer from './edit/ExportPlayer.vue'
const store = useStudio(),
  route = useRoute(),
  router = useRouter(),
  message = useMessage()
const project = ref<string | null>(null),
  status = ref<Model['Status'] | null>(null),
  busy = ref('')
const missingTask=ref<Task>()
const cachedTask=computed(()=>store.tasks.find(t=>t.id===route.query.task))
const selected=computed(()=>cachedTask.value ?? (missingTask.value?.id===route.query.task?missingTask.value:undefined))
watch([()=>route.query.task,cachedTask,()=>store.assetsChanged],async([id,cached])=>{
  if(typeof id!=='string' || cached)return
  try {
    const item=value(await api.GET('/tasks/{id}',{params:pathId(id)}))
    if(id===route.query.task)missingTask.value=item
  }catch(e){if(id===route.query.task)store.report(e)}
},{immediate:true})
const rows = computed(() =>
  store.tasks.filter(
    (t) => (!project.value || t.projectId === project.value) && (!status.value || t.status === status.value),
  ),
)
const projectName = (id: string) => store.projects.find((p) => p.id === id)?.title ?? id.slice(0, 8)
const title = (t: Task) =>
  t.kind === 'IMAGE' ? (store.workflows.find((w) => w.id === t.snapshot.workflowId)?.manifest.title ?? '图片生成') :
    ({ OUTLINE: '故事大纲', EPISODE_PLAN: '分集规划', SCRIPT: '分集剧本', ASSET_EXTRACT: '提取资产', SHOT_PLAN: '分镜计划', SHOT_REGENERATE: '单镜头重做', KEYFRAME: '分镜预览图', SHOT_RENDER: '镜头视频渲染', SHOT_AUDIO: '对白配音', EPISODE_EXPORT: '成片导出' }[t.kind])
const manualKinds = new Set<Task['kind']>(['IMAGE','KEYFRAME','SHOT_RENDER','OUTLINE','EPISODE_PLAN','SCRIPT','ASSET_EXTRACT','SHOT_PLAN','SHOT_REGENERATE'])
const manualReconcile = computed(() => selected.value?.status==='UNKNOWN' && manualKinds.has(selected.value.kind))
const statuses = Object.entries(statusText).map(([value, label]) => ({ label, value }))
async function action(t: Task, which: 'retry' | 'cancel' | 'reconcile') {
  if (busy.value) return
  busy.value = t.id
  try {
    if (which === 'retry') value(await api.POST('/tasks/{id}/retry', { params: pathId(t.id) }))
    if (which === 'cancel') value(await api.POST('/tasks/{id}/cancel', { params: pathId(t.id) }))
    if (which === 'reconcile')
      value(
        await api.POST('/tasks/{id}/reconcile', {
          params: pathId(t.id),
          body: { attemptId: lastAttempt(t).id, confirmedStopped: true },
        }),
      )
    await store.loadTasks()
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = ''
  }
}
function select(id?: string) {
  void router.replace({ path: '/tasks', query: id ? { task: id } : {} })
}
</script>
<template>
  <section>
    <div class="page-head">
      <div>
        <h1>任务中心</h1>
        <p>查看生成进度、引擎回执和每次尝试的结果。</p>
      </div>
      <span class="capacity"><i class="dot"></i>GPU 并发上限 1</span>
    </div>
    <div v-if="store.resources?.waitingReason" class="notice">{{ store.resources.waitingReason }}</div>
    <div class="panel">
      <div class="panel-tools">
        <h2>
          生成任务 <small>{{ rows.length }}</small>
        </h2>
        <div class="filters">
          <n-select
            v-model:value="project"
            clearable
            placeholder="全部项目"
            :options="store.projects.map((p) => ({ label: p.title, value: p.id }))"
          />
          <n-select v-model:value="status" clearable placeholder="全部状态" :options="statuses" />
        </div>
      </div>
      <div v-if="rows.length" class="task-list">
        <button v-for="t in rows" :key="t.id" class="task-row" @click="select(t.id)">
          <div class="task-mark" aria-hidden="true">▧</div>
          <div class="grow">
            <strong>{{ title(t) }}</strong
            ><small
              >{{ projectName(t.projectId) }} · <code>{{ t.id.slice(0, 8) }}</code></small
            >
          </div>
          <div v-if="t.status === 'RUNNING' && lastAttempt(t).nodeProgress != null" class="mini-progress">
            节点 {{ Math.round(lastAttempt(t).nodeProgress! * 100) }}%
          </div>
          <status-tag :state="t.status" /><time>{{ dateText(t.createdAt) }}</time
          ><span aria-hidden="true">›</span>
        </button>
      </div>
      <blank-panel v-else title="暂无生成任务" detail="提交图片后，可在这里查看状态和生成结果。" />
    </div>
    <n-drawer
      :show="!!selected"
      :width="'min(480px, 96vw)'"
      @update:show="
        (v) => {
          if (!v) select()
        }
      "
      ><n-drawer-content title="任务详情" closable v-if="selected">
        <div class="detail-head">
          <h2>{{ title(selected) }}</h2>
          <status-tag :state="selected.status" />
        </div>
        <p class="mono detail-id">{{ selected.id }}</p>
        <div class="detail-grid">
          <span>项目</span><strong>{{ projectName(selected.projectId) }}</strong
          ><span>{{ selected.kind === 'SHOT_AUDIO' ? '配音版本' : selected.kind === 'EPISODE_EXPORT' ? '合成版本' : selected.snapshot.workflowId ? '模板版本' : '提示词版本' }}</span><strong>{{ selected.snapshot.workflowVersion }}</strong
          ><span>引擎版本</span><strong>{{ selected.snapshot.engineVersion }}</strong
          ><span>创建时间</span><strong>{{ dateText(selected.createdAt) }}</strong>
        </div>
        <h3>输入快照</h3>
        <router-link v-if="['OUTLINE','EPISODE_PLAN','SCRIPT'].includes(selected.kind)" :to="{path:'/projects/'+selected.projectId+'/writing',query:{task:selected.id}}">打开对应稿件</router-link>
        <router-link v-if="selected.kind==='ASSET_EXTRACT'" :to="'/projects/'+selected.projectId+'/assets'">查看项目资产</router-link>
        <router-link v-if="['SHOT_PLAN','SHOT_REGENERATE','KEYFRAME'].includes(selected.kind)" :to="{path:'/projects/'+selected.projectId+'/storyboard',query:{task:selected.id}}">查看对应分镜</router-link>
        <router-link v-if="selected.kind === 'SHOT_RENDER'" :to="{ path: '/projects/' + selected.projectId + '/render', query: { shot: selected.targetId, revision: selected.snapshot.shotRevisionId ?? undefined } }">查看镜头视频</router-link>
        <router-link v-if="selected.kind === 'SHOT_AUDIO'" :to="{path:'/projects/'+selected.projectId+'/audio',query:{shot:selected.targetId,revision:selected.snapshot.shotRevisionId ?? undefined}}">打开镜头配音</router-link>
        <router-link v-if="selected.kind === 'EPISODE_EXPORT'" :to="{path:'/projects/'+selected.projectId+'/edit',query:{episode:selected.targetId,timeline:selected.snapshot.timelineRevisionId ?? undefined,task:selected.id}}">查看成片导出</router-link>
        <div v-if="selected.kind === 'SHOT_RENDER'" class="detail-grid">
          <template v-if="selected.snapshot.firstFrame"><span>首帧</span><a :href="mediaUrl(selected.snapshot.firstFrame.id)" target="_blank" rel="noopener">查看输入首帧</a></template>
          <template v-if="selected.snapshot.lastFrame"><span>尾帧</span><a :href="mediaUrl(selected.snapshot.lastFrame.id)" target="_blank" rel="noopener">查看输入尾帧</a></template>
          <template v-if="selected.snapshot.referenceFrame"><span>参考资产版本</span><a :href="mediaUrl(selected.snapshot.referenceFrame.id)" target="_blank" rel="noopener">{{ selected.snapshot.referenceVersionId }}</a></template>
          <template v-if="selected.snapshot.targetWidth && selected.snapshot.targetHeight"><span>目标画布</span><strong>{{ selected.snapshot.targetWidth }} × {{ selected.snapshot.targetHeight }}</strong></template>
          <template v-if="selected.snapshot.plannedFrameCount"><span>计划帧数</span><strong>{{ selected.snapshot.plannedFrameCount }}</strong></template>
        </div>
        <pre>{{ JSON.stringify(selected.snapshot.parameters, null, 2) }}</pre>
        <h3>尝试记录</h3>
        <div v-for="a in selected.attempts" :key="a.id" class="attempt">
          <div>
            <strong>第 {{ a.attemptNo }} 次</strong><status-tag :state="a.status" />
          </div>
          <small v-if="a.externalId">Prompt ID · {{ a.externalId }}</small
          ><small v-if="a.startedAt"
            >开始于 {{ dateText(a.startedAt)
            }}<span v-if="a.finishedAt">
              · 用时 {{ ((a.finishedAt - a.startedAt) / 1000).toFixed(1) }} 秒</span
            ></small
          >
          <div v-if="a.nodeProgress != null">
            节点 {{ a.node }} · {{ Math.round(a.nodeProgress * 100) }}%<progress
              :value="a.nodeProgress"
              max="1"
            />
          </div>
          <small v-if="a.peakVramBytes != null">显存采样峰值 {{ gib(a.peakVramBytes) }}</small
          ><small v-if="a.peakRamBytes != null">内存采样峰值 {{ gib(a.peakRamBytes) }}</small>
          <p v-if="a.error" class="problem">{{ a.error.code }} · {{ a.error.message }}</p>
        </div>
        <div v-if="selected.results.length">
          <h3>生成结果</h3>
          <template v-for="m in selected.results" :key="m.id">
            <export-player v-if="selected.kind==='EPISODE_EXPORT' && m.mime.startsWith('video/')" class="preview-image" :media="m" :task-id="selected.id" :plan="selected.snapshot.export" label="任务输出视频" />
            <video v-else-if="m.mime.startsWith('video/')" class="preview-image" :src="mediaUrl(m.id)" controls playsinline preload="metadata" aria-label="任务输出视频" />
            <audio v-else-if="m.mime.startsWith('audio/')" :src="mediaUrl(m.id)" controls preload="metadata" aria-label="任务输出音频" />
            <img v-else class="preview-image" :src="mediaUrl(m.id)" alt="生成结果" />
          </template>
        </div>
        <p v-if="selected.status === 'UNKNOWN' && !manualReconcile" class="notice">
          {{ selected.kind === 'SHOT_AUDIO' ? '配音服务正在核对原尝试。请求取消后，工作台会等待服务确认停止。' : '工作台正在核对原导出进程和结果。' }}
        </p>
        <template #footer
          ><n-button
            v-if="selected.status === 'FAILED'"
            type="primary"
            :loading="busy === selected.id"
            @click="action(selected, 'retry')"
            >重试任务</n-button
          >
          <n-button
            v-else-if="!terminal(selected) && !manualReconcile"
            :disabled="selected.cancelRequested"
            @click="action(selected, 'cancel')"
            >请求取消</n-button
          >
          <n-popconfirm v-if="manualReconcile" @positive-click="action(selected, 'reconcile')"
            ><template #trigger><n-button>人工核对</n-button></template
            >{{ selected.snapshot.workflowId ? '已在 ComfyUI 核对队列及历史，确认该尝试已停止且没有结果？' : '已核对编剧服务，确认该尝试已停止且没有结果？' }}</n-popconfirm
          >
        </template></n-drawer-content
      ></n-drawer
    >
  </section>
</template>
