<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { NButton, NInput, NModal, NSelect, useMessage } from 'naive-ui'
import { useRouter } from 'vue-router'
import { useStudio } from '../stores/studio'
import { api, pathId, value, dateText, type Project, type Model } from '../api/client'
import BlankPanel from '../components/BlankPanel.vue'
const store = useStudio(),
  router = useRouter(),
  message = useMessage()
const filter = ref<'ACTIVE' | 'ARCHIVED'>('ACTIVE'),
  search = ref(''),
  show = ref(false),
  editing = ref<Project>(),
  saving = ref(false)
const form = reactive({
  title: '',
  genre: '',
  style: '',
  aspectRatio: '9:16' as Model['Project']['aspectRatio'],
})
const visible = computed(() =>
  store.projects.filter(
    (p) => p.status === filter.value && p.title.toLowerCase().includes(search.value.trim().toLowerCase()),
  ),
)
const ratioOptions = ['9:16', '16:9', '1:1', '4:3'].map((value) => ({ label: value, value }))
function open(p?: Project) {
  editing.value = p
  Object.assign(form, {
    title: p?.title ?? '',
    genre: p?.genre ?? '',
    style: p?.style ?? '',
    aspectRatio: p?.aspectRatio ?? '9:16',
  })
  show.value = true
}
async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const p = editing.value
    const saved = p
      ? value(
          await api.PATCH('/projects/{id}', {
            params: pathId(p.id),
            body: { ...form, expectedRevision: p.revision },
          }),
        )
      : value(await api.POST('/projects', { body: { ...form } }))
    await store.loadProjects()
    show.value = false
    if (!p) await router.push('/projects/' + saved.id + '/assets')
    else message.success('项目已保存')
  } catch (e) {
    store.report(e)
  } finally {
    saving.value = false
  }
}
async function archive(p: Project) {
  try {
    if (p.status === 'ACTIVE')
      value(
        await api.DELETE('/projects/{id}', {
          params: { ...pathId(p.id), query: { expectedRevision: p.revision } },
        }),
      )
    else
      value(
        await api.PATCH('/projects/{id}', {
          params: pathId(p.id),
          body: { expectedRevision: p.revision, status: 'ACTIVE' },
        }),
      )
    await store.loadProjects()
  } catch (e) {
    store.report(e)
  }
}
</script>
<template>
  <section>
    <div class="page-head">
      <div>
        <h1>项目库</h1>
        <p>管理短剧项目和制作设置。</p>
      </div>
      <n-button type="primary" size="large" @click="open()">＋ 新建项目</n-button>
    </div>
    <div class="panel">
      <div class="panel-tools">
        <div class="tabs" aria-label="项目状态">
          <button
            v-for="f in ['ACTIVE', 'ARCHIVED'] as const"
            :key="f"
            :class="{ on: f === filter }"
            @click="filter = f"
          >
            {{ f === 'ACTIVE' ? '制作中' : '已归档' }}
            <em>{{ store.projects.filter((p) => p.status === f).length }}</em>
          </button>
        </div>
        <n-input
          v-model:value="search"
          clearable
          placeholder="搜索项目"
          aria-label="搜索项目"
          class="search"
        />
      </div>
      <div v-if="visible.length" class="project-list">
        <article v-for="p in visible" :key="p.id" class="project-row">
          <div class="project-symbol"><span :class="{ landscape: p.aspectRatio === '16:9' }"></span></div>
          <div class="grow">
            <button class="text-title" @click="router.push('/projects/' + p.id + '/assets')">
              {{ p.title }}
            </button>
            <div class="muted small">
              {{ p.genre || '未设类型' }} · {{ p.aspectRatio }}
              <span class="timestamp"> · {{ dateText(p.createdAt) }}</span>
            </div>
          </div>
          <n-button size="small" @click="open(p)">编辑</n-button
          ><n-button text size="small" @click="archive(p)">{{
            p.status === 'ACTIVE' ? '归档' : '恢复'
          }}</n-button>
        </article>
      </div>
      <blank-panel
        v-else
        :title="
          store.loading
            ? '正在读取项目…'
            : search
              ? '没有匹配的项目'
              : filter === 'ACTIVE'
                ? '还没有制作中的项目'
                : '没有已归档项目'
        "
        :detail="
          !search && filter === 'ACTIVE' && !store.loading
            ? '创建项目后，可以设置画幅、生成资产并跟踪任务。'
            : undefined
        "
      >
        <n-button v-if="!search && filter === 'ACTIVE' && !store.loading" @click="open()"
          >创建第一个项目</n-button
        ></blank-panel
      >
    </div>
    <n-modal v-model:show="show" preset="card" :title="editing ? '编辑项目' : '新建项目'" class="modal-card">
      <form class="form-stack" @submit.prevent="save">
        <label class="field"
          >项目名称<n-input v-model:value="form.title" autofocus maxlength="120" placeholder="输入短剧名称"
        /></label>
        <div class="field-pair">
          <label class="field"
            >类型<n-input v-model:value="form.genre" maxlength="100" placeholder="如 悬疑"
          /></label>
          <label class="field"
            >画幅<n-select v-model:value="form.aspectRatio" :options="ratioOptions"
          /></label>
        </div>
        <label class="field"
          >视觉风格<n-input
            v-model:value="form.style"
            type="textarea"
            maxlength="1000"
            placeholder="描述本项目的视觉风格"
        /></label>
        <div class="form-actions">
          <n-button @click="show = false">取消</n-button
          ><n-button attr-type="submit" type="primary" :loading="saving" :disabled="!form.title.trim()">{{
            editing ? '保存项目' : '创建项目'
          }}</n-button>
        </div>
      </form>
    </n-modal>
  </section>
</template>
