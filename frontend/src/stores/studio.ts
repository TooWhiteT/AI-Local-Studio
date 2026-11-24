import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  api,
  allPages,
  terminal,
  value,
  type Model,
  type Project,
  type Task,
  type Workflow,
} from '../api/client'

export const useStudio = defineStore('studio', () => {
  const projects = ref<Project[]>([])
  const tasks = ref<Task[]>([])
  const workflows = ref<Workflow[]>([])
  const resources = ref<Model['Resources']>()
  const live = ref(false)
  const loading = ref(true)
  const error = ref('')
  const assetsChanged = ref(0)
  const activeTasks = computed(() => tasks.value.filter((t) => !terminal(t)))
  let stream: EventSource | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let resourceTimer: ReturnType<typeof setInterval> | undefined
  let dirty = false,
    refreshing = false,
    seq = 0
  function report(e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
  async function loadProjects() {
    projects.value = await allPages(async (cursor) =>
      value(await api.GET('/projects', { params: { query: { cursor, limit: 100 } } })),
    )
  }
  async function loadTasks() {
    dirty = true
    if (refreshing) return
    refreshing = true
    try {
      do {
        dirty = false
        tasks.value = await allPages(async (cursor) =>
          value(await api.GET('/tasks', { params: { query: { cursor, limit: 100 } } })),
        )
      } while (dirty)
    } finally {
      refreshing = false
    }
  }
  async function loadResources() {
    resources.value = value(await api.GET('/system/resources'))
  }
  async function loadWorkflows() {
    workflows.value = value(await api.GET('/workflows'))
  }
  function scheduleRefresh() {
    if (timer) return
    timer = setTimeout(() => {
      timer = undefined
      void loadTasks().catch(report)
    }, 80)
  }
  function connect() {
    stream = new EventSource('/api/v1/events')
    const sync = (e: MessageEvent<string>) => {
      seq = Number(e.lastEventId)
      live.value = true
      assetsChanged.value++
      scheduleRefresh()
    }
    stream.addEventListener('ready', sync)
    stream.addEventListener('reset', sync)
    stream.addEventListener('task.updated', (e: MessageEvent<string>) => {
      const id = Number(e.lastEventId)
      if (id <= seq) return
      seq = id
      const update: Model['TaskUpdate'] = JSON.parse(e.data)
      if (update.stage === 'SAMPLING') {
        const task = tasks.value.find((t) => t.id === update.taskId)
        const attempt = task?.attempts.at(-1)
        if (task && !terminal(task) && attempt?.id === update.attemptId) {
          attempt.node = update.node
          attempt.nodeProgress = update.nodeProgress
          return
        }
      }
      if (['SUCCEEDED', 'CANCELED', 'FAILED'].includes(update.status)) assetsChanged.value++
      scheduleRefresh()
    })
    stream.onerror = () => {
      live.value = false
    }
  }
  async function start() {
    connect()
    resourceTimer = setInterval(() => {
      void loadResources().catch(report)
    }, 15000)
    try {
      await Promise.all([loadProjects(), loadWorkflows(), loadResources(), loadTasks()])
    } catch (e) {
      report(e)
    } finally {
      loading.value = false
    }
  }
  function stop() {
    stream?.close()
    clearTimeout(timer)
    clearInterval(resourceTimer)
  }
  return {
    projects,
    workflows,
    tasks,
    resources,
    activeTasks,
    loading,
    live,
    error,
    assetsChanged,
    report,
    loadProjects,
    loadTasks,
    loadResources,
    loadWorkflows,
    start,
    stop,
  }
})
