<script setup lang="ts">
import { computed, ref } from 'vue'
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
const store = useStudio(),
  route = useRoute(),
  router = useRouter(),
  message = useMessage()
const project = ref<string | null>(null),
  status = ref<Model['Status'] | null>(null),
  busy = ref('')
const selected = computed(() => store.tasks.find((t) => t.id === route.query.task))
const rows = computed(() =>
  store.tasks.filter(
    (t) => (!project.value || t.projectId === project.value) && (!status.value || t.status === status.value),
  ),
)
const projectName = (id: string) => store.projects.find((p) => p.id === id)?.title ?? id.slice(0, 8)
const title = (t: Task) =>
  t.kind === 'IMAGE' ? (store.workflows.find((w) => w.id === t.snapshot.workflowId)?.manifest.title ?? '图片生成') :
    ({ OUTLINE: '故事大纲', EPISODE_PLAN: '分集规划', SCRIPT: '分集剧本' }[t.kind])
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
          ><span>{{ selected.kind === 'IMAGE' ? '模板版本' : '提示词版本' }}</span><strong>{{ selected.snapshot.workflowVersion }}</strong
          ><span>引擎版本</span><strong>{{ selected.snapshot.engineVersion }}</strong
          ><span>创建时间</span><strong>{{ dateText(selected.createdAt) }}</strong>
        </div>
        <h3>输入快照</h3>
        <router-link v-if="selected.kind !== 'IMAGE'" :to="{path:'/projects/'+selected.projectId+'/writing',query:{task:selected.id}}">打开对应稿件</router-link>
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
          <img
            v-for="m in selected.results"
            :key="m.id"
            class="preview-image"
            :src="mediaUrl(m.id)"
            alt="生成结果"
          />
        </div>
        <template #footer
          ><n-button
            v-if="selected.status === 'FAILED'"
            type="primary"
            :loading="busy === selected.id"
            @click="action(selected, 'retry')"
            >重试任务</n-button
          >
          <n-button
            v-else-if="!terminal(selected) && selected.status !== 'UNKNOWN'"
            :disabled="selected.cancelRequested"
            @click="action(selected, 'cancel')"
            >请求取消</n-button
          >
          <n-popconfirm v-if="selected.status === 'UNKNOWN'" @positive-click="action(selected, 'reconcile')"
            ><template #trigger><n-button>人工核对</n-button></template
            >已在 ComfyUI 核对队列及历史，确认该尝试已停止且没有结果？</n-popconfirm
          >
        </template></n-drawer-content
      ></n-drawer
    >
  </section>
</template>
