<script setup lang="ts">
import { computed, ref } from 'vue'
import { NButton, NInput, NInputNumber, NModal, NSelect, useMessage } from 'naive-ui'
import { useStudio } from '../stores/studio'
import { api, pathId, value, gib, type Workflow, type Binding } from '../api/client'
import BlankPanel from '../components/BlankPanel.vue'
import ParameterField from '../components/ParameterField.vue'
const store = useStudio(),
  msg = useMessage()
const base = ref<Workflow>(),
  show = ref(false),
  busy = ref(false),
  version = ref(''),
  title = ref(''),
  bindings = ref<Binding[]>([])
const slots = computed(() =>
  (base.value?.manifest.parameters ?? []).map((b) => ({
    label: b.node + ' / ' + b.input,
    value: b.node + '/' + b.input,
  })),
)
async function refresh() {
  busy.value = true
  try {
    store.workflows = value(await api.POST('/workflows/refresh'))
    await store.loadResources()
    msg.success('检测完成')
  } catch (e) {
    store.report(e)
  } finally {
    busy.value = false
  }
}
function edit(w: Workflow) {
  base.value = w
  version.value = ''
  title.value = w.manifest.title
  bindings.value = w.manifest.parameters.map((b) => ({ ...b }))
  show.value = true
}
function setSlot(index: number, v: string) {
  const b = base.value?.manifest.parameters.find((b) => b.node + '/' + b.input === v)
  if (b)
    bindings.value[index] = { ...b, name: bindings.value[index].name, label: bindings.value[index].label }
}
async function publish() {
  if (!base.value || busy.value) return
  busy.value = true
  try {
    value(
      await api.POST('/workflows/{id}/versions', {
        params: pathId(base.value.id),
        body: { version: version.value, title: title.value, parameters: bindings.value },
      }),
    )
    await store.loadWorkflows()
    show.value = false
    msg.success('新版本已发布')
  } catch (e) {
    msg.error(e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section>
    <div class="page-head">
      <div>
        <h1>工作流</h1>
        <p>管理本机模板、参数绑定和发布版本。</p>
      </div>
      <n-button size="large" :loading="busy" @click="refresh">重新检测</n-button>
    </div>
    <div class="engine-strip">
      <div>
        <i class="dot" :class="{ online: store.resources?.engine.available }"></i
        ><strong>{{ store.resources?.engine.mode === 'mock' ? 'Mock ComfyUI' : 'ComfyUI' }}</strong>
      </div>
      <span>{{
        store.resources?.engine.available
          ? store.resources.engine.version
          : (store.resources?.engine.reason ?? '正在检测')
      }}</span>
    </div>
    <div v-if="store.resources" class="resource-metrics">
      <span>磁盘可用 {{ gib(store.resources.diskFree) }}</span>
      <span v-if="store.resources.engine.vramFree != null"
        >显存可用 {{ gib(store.resources.engine.vramFree) }}</span
      >
      <span v-if="store.resources.engine.ramFree != null"
        >内存可用 {{ gib(store.resources.engine.ramFree) }}</span
      >
    </div>
    <div v-for="issue in store.resources?.workflowIssues" :key="issue" class="notice">{{ issue }}</div>
    <div v-if="store.workflows.length" class="flow-grid">
      <article class="panel flow-card" v-for="w in store.workflows" :key="w.id">
        <div class="split">
          <span class="type-label">{{ w.manifest.mode === 'mock' ? '模拟模板' : '本机模板' }}</span
          ><span class="status-tag" :data-state="w.enabled ? 'SUCCEEDED' : 'FAILED'"
            ><i></i>{{ w.enabled ? '可用' : '已禁用' }}</span
          >
        </div>
        <h2>{{ w.manifest.title }}</h2>
        <p class="mono">{{ w.manifest.id }} / v{{ w.manifest.version }}</p>
        <div class="flow-meta">
          <span
            >输入参数 <b>{{ w.manifest.parameters.length }}</b></span
          ><span
            >{{ w.manifest.capabilities.some(c => c === 'image-to-video' || c === 'reference-to-video') ? '视频输出' : '图片输出' }} <b>{{ w.manifest.outputs.length }}</b></span
          >
        </div>
        <p v-if="w.reason" class="problem">{{ w.reason }}</p>
        <div class="split flow-footer">
          <small>版本 {{ w.manifest.version }}</small
          ><n-button size="small" :disabled="!w.enabled" @click="edit(w)">编辑绑定</n-button>
        </div>
      </article>
    </div>
    <div v-else class="panel">
      <blank-panel
        title="尚未注册工作流模板"
        detail="将本机导出的 API 图与 manifest 放入工作流目录，然后重新检测。"
      />
    </div>
    <n-modal v-model:show="show" preset="card" title="编辑工作流绑定" class="editor-modal">
      <form class="form-stack" @submit.prevent="publish">
        <div class="field-pair">
          <label class="field">模板名称<n-input v-model:value="title" /></label>
          <label class="field"
            >新版本号<n-input v-model:value="version" placeholder="输入尚未发布的版本"
          /></label>
        </div>
        <fieldset v-for="(b, i) in bindings" :key="i">
          <legend>参数 {{ i + 1 }}</legend>
          <div class="field-pair">
            <label class="field">参数名<n-input v-model:value="b.name" /></label
            ><label class="field">显示名称<n-input v-model:value="b.label" /></label>
          </div>
          <label class="field"
            >节点 / 输入<n-select
              :value="b.node + '/' + b.input"
              :options="slots"
              @update:value="setSlot(i, $event)"
          /></label>
          <div v-if="b.type === 'INT' || b.type === 'FLOAT'" class="field-pair">
            <label class="field">最小值<n-input-number v-model:value="b.min" /></label
            ><label class="field">最大值<n-input-number v-model:value="b.max" /></label>
          </div>
          <parameter-field :binding="{ ...b, label: '默认值' }" v-model="b.default" />
        </fieldset>
        <div class="form-actions">
          <n-button @click="show = false">取消</n-button
          ><n-button
            type="primary"
            attr-type="submit"
            :disabled="!version.trim() || !title.trim()"
            :loading="busy"
            >发布新版本</n-button
          >
        </div>
      </form></n-modal
    >
  </section>
</template>
