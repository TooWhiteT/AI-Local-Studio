<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import {
  api,
  pathId,
  value,
  allPages,
  assetTypes,
  mediaUrl,
  lastAttempt,
  type Asset,
  type Model,
  type Scalar,
} from '../api/client'
import { useStudio } from '../stores/studio'
import BlankPanel from '../components/BlankPanel.vue'
import ParameterField from '../components/ParameterField.vue'
import StatusTag from '../components/StatusTag.vue'
const store = useStudio(),
  route = useRoute(),
  router = useRouter(),
  message = useMessage()
const projectId = computed(() => String(route.params.id)),
  project = computed(() => store.projects.find((p) => p.id === projectId.value))
const assets = ref<Asset[]>([]),
  selected = ref(''),
  workflowId = ref(''),
  busy = ref(false),
  creating = ref(false),
  modal = ref(false)
const name = ref(''),
  type = ref<Model['AssetInput']['type']>('CHARACTER'),
  params = reactive<Record<string, Scalar>>({})
const chosen = computed(() => assets.value.find((a) => a.id === selected.value))
const flow = computed(() => store.workflows.find((w) => w.id === workflowId.value && w.enabled))
const active = computed(() => project.value?.status === 'ACTIVE')
const recent = computed(() => store.tasks.find((t) => t.assetId === selected.value))
const options = computed(() =>
  store.workflows.map((w) => ({
    label: w.manifest.title + ' · v' + w.manifest.version,
    value: w.id,
    disabled: !w.enabled,
  })),
)
const types = Object.entries(assetTypes).map(([value, label]) => ({ label, value }))
const preview = ref<Model['Version']>()
let loadId = 0,
  pendingKey = '',
  pendingBody = ''
async function load() {
  const n = ++loadId,
    id = projectId.value
  const items = await allPages(async (cursor) =>
    value(
      await api.GET('/projects/{id}/assets', { params: { ...pathId(id), query: { cursor, limit: 100 } } }),
    ),
  )
  if (n !== loadId || id !== projectId.value) return
  assets.value = items
  if (!items.some((a) => a.id === selected.value)) selected.value = items[0]?.id ?? ''
}
watch(
  projectId,
  () => {
    assets.value = []
    selected.value = ''
    void load().catch(store.report)
  },
  { immediate: true },
)
watch(
  () => store.assetsChanged,
  () => void load().catch(store.report),
)
watch(
  () => store.workflows,
  (items) => {
    if (!items.some((w) => w.enabled && w.id === workflowId.value))
      workflowId.value = items.find((w) => w.enabled)?.id ?? ''
  },
  { immediate: true },
)
watch(
  () => flow.value?.id,
  () => {
    for (const key of Object.keys(params)) delete params[key]
    for (const b of flow.value?.manifest.parameters ?? []) params[b.name] = b.default
  },
  { immediate: true },
)
async function createAsset() {
  if (creating.value) return
  creating.value = true
  try {
    const a = value(
      await api.POST('/projects/{id}/assets', {
        params: pathId(projectId.value),
        body: { name: name.value, type: type.value },
      }),
    )
    await load()
    selected.value = a.id
    name.value = ''
    modal.value = false
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e))
  } finally {
    creating.value = false
  }
}
async function generate() {
  const w = flow.value,
    asset = chosen.value
  if (!w || !asset || busy.value) return
  busy.value = true
  const body = { workflowId: w.id, workflowVersion: w.manifest.version, parameters: { ...params } }
  const identity = JSON.stringify([asset.id, body])
  if (identity !== pendingBody) {
    pendingKey = crypto.randomUUID()
    pendingBody = identity
  }
  try {
    value(
      await api.POST('/assets/{id}/generate', {
        params: { ...pathId(asset.id), header: { 'Idempotency-Key': pendingKey } },
        body,
      }),
    )
    pendingBody = ''
    await store.loadTasks()
    message.success('任务已加入队列')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section>
    <div class="page-head">
      <div>
        <h1>资产生成</h1>
        <p>
          <router-link to="/projects">项目库</router-link><span class="slash">/</span
          >{{ project?.title ?? '读取项目中' }} <span v-if="project">· {{ project.aspectRatio }}</span>
        </p>
      </div>
      <n-button :disabled="!active" @click="modal = true">＋ 新建资产</n-button>
    </div>
    <div v-if="project?.status === 'ARCHIVED'" class="notice">此项目已归档，可继续查看历史图片。</div>
    <div class="asset-layout">
      <div class="panel generator">
        <div class="panel-title"><h2>生成参数</h2></div>
        <form class="form-stack" @submit.prevent="generate">
          <label class="field"
            >目标资产<n-select
              v-model:value="selected"
              :options="assets.map((a) => ({ label: a.name, value: a.id }))"
              placeholder="选择或新建资产"
          /></label>
          <label class="field"
            >工作流模板<n-select v-model:value="workflowId" :options="options" placeholder="暂无可用模板"
          /></label>
          <div v-if="!flow" class="notice">
            请在工作流页检测本地引擎和模板。<router-link to="/workflows">查看工作流</router-link>
          </div>
          <parameter-field
            v-for="b in flow?.manifest.parameters"
            :key="b.name"
            :binding="b"
            v-model="params[b.name]"
          />
          <n-button
            type="primary"
            attr-type="submit"
            size="large"
            block
            :loading="busy"
            :disabled="!active || !chosen || !flow"
            >提交图片任务</n-button
          >
        </form>
        <div v-if="recent" class="latest-task">
          <span>最近任务</span><status-tag :state="recent.status" />
          <span v-if="recent.status === 'RUNNING' && lastAttempt(recent).nodeProgress != null"
            >节点 {{ Math.round(lastAttempt(recent).nodeProgress! * 100) }}%</span
          >
          <button class="text-link" @click="router.push({ path: '/tasks', query: { task: recent.id } })">
            查看详情
          </button>
        </div>
      </div>
      <div class="panel output">
        <div class="panel-title">
          <div>
            <h2>{{ chosen?.name ?? '资产图片' }}</h2>
            <small v-if="chosen">{{ assetTypes[chosen.type] }} · {{ chosen.versions.length }} 个版本</small>
          </div>
          <span v-if="flow?.manifest.mode === 'mock'" class="mode-label">Mock 输出</span>
        </div>
        <div v-if="chosen?.versions.length" class="gallery">
          <button class="image-card" v-for="v in chosen.versions" :key="v.id" @click="preview = v">
            <img :src="mediaUrl(v.file.id)" :alt="chosen.name + ' 版本 ' + v.revision" /><span
              ><strong>版本 {{ v.revision }}</strong
              ><small>{{ v.file.width }} × {{ v.file.height }} · 待审核</small></span
            >
          </button>
        </div>
        <blank-panel
          v-else
          :title="chosen ? '等待第一张图片' : '选择一个资产'"
          :detail="
            chosen
              ? '提交图片任务后，完成的版本会出现在这里。'
              : '新建角色、场景、道具或服装，用于保存生成的图片。'
          "
        />
      </div>
    </div>
    <n-modal v-model:show="modal" preset="card" title="新建资产" class="modal-card"
      ><form class="form-stack" @submit.prevent="createAsset">
        <label class="field"
          >资产名称<n-input v-model:value="name" maxlength="120" autofocus placeholder="输入资产名称"
        /></label>
        <label class="field">资产类型<n-select v-model:value="type" :options="types" /></label>
        <div class="form-actions">
          <n-button @click="modal = false">取消</n-button
          ><n-button type="primary" attr-type="submit" :loading="creating" :disabled="!name.trim()"
            >创建资产</n-button
          >
        </div>
      </form></n-modal
    >
    <n-modal
      :show="!!preview"
      preset="card"
      :title="chosen?.name + ' · 版本 ' + preview?.revision"
      class="preview-modal"
      @update:show="
        (v) => {
          if (!v) preview = undefined
        }
      "
      ><template v-if="preview"
        ><img class="preview-image" :src="mediaUrl(preview.file.id)" :alt="chosen?.name" />
        <p class="hash">SHA-256 · {{ preview.file.sha256 }}</p></template
      ></n-modal
    >
  </section>
</template>
