<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NDrawer, NDrawerContent } from 'naive-ui'
import { useStudio } from '../stores/studio'
import { lastAttempt } from '../api/client'
import StatusTag from './StatusTag.vue'
const open = ref(false),
  store = useStudio(),
  router = useRouter()
function view(id: string) {
  open.value = false
  void router.push({ path: '/tasks', query: { task: id } })
}
</script>
<template>
  <n-button text size="small" @click="open = true"
    >任务队列<span v-if="store.activeTasks.length"> · {{ store.activeTasks.length }}</span></n-button
  >
  <n-drawer v-model:show="open" :width="'min(420px, 96vw)'"
    ><n-drawer-content title="任务队列" closable>
      <p v-if="!store.activeTasks.length" class="muted">当前没有等待或运行中的任务。</p>
      <button v-for="t in store.activeTasks" :key="t.id" class="task-row" @click="view(t.id)">
        <div class="grow">
          <strong>{{ store.projects.find((p) => p.id === t.projectId)?.title }}</strong
          ><small
            >{{ t.id.slice(0, 8)
            }}<span v-if="lastAttempt(t).nodeProgress != null">
              · 节点 {{ Math.round(lastAttempt(t).nodeProgress! * 100) }}%</span
            ></small
          >
        </div>
        <status-tag :state="t.status" />
      </button> </n-drawer-content
  ></n-drawer>
</template>
