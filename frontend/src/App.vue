<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NConfigProvider,
  NButton,
  NMessageProvider,
  zhCN,
  dateZhCN,
  type GlobalThemeOverrides,
} from 'naive-ui'
import { useStudio } from './stores/studio'
import TaskTray from './components/TaskTray.vue'
import './style.css'
import './features/storyboard/production.css'
const store = useStudio(),
  route = useRoute(),
  router = useRouter()
const project = computed(() => store.projects.find((p) => p.id === route.params.id))
const currentAssetProject = computed(() => project.value ?? store.projects.find((p) => p.status === 'ACTIVE'))
// Naive UI computes translucent focus colors from the resolved palette values.
const palette = getComputedStyle(document.documentElement)
const themeColor = (property: string) => palette.getPropertyValue(property).trim()
const theme: GlobalThemeOverrides = {
  common: {
    primaryColor: themeColor('--primary'),
    primaryColorHover: themeColor('--primary-hover'),
    primaryColorPressed: themeColor('--primary-pressed'),
    primaryColorSuppl: themeColor('--primary'),
    textColorBase: themeColor('--ink'),
    textColor1: themeColor('--ink'),
    textColor2: themeColor('--text'),
    textColor3: themeColor('--muted'),
    placeholderColor: themeColor('--muted'),
    borderColor: themeColor('--border'),
    dividerColor: themeColor('--divider'),
    hoverColor: themeColor('--surface-hover'),
    pressedColor: themeColor('--primary-soft'),
    borderRadius: '7px',
    fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
  },
}
const nav = computed(() => [
  { to: '/projects', title: '项目库', icon: 'library', active: route.path === '/projects' },
  { to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/writing' : '/projects',
    title: '编剧工作台', icon: 'library', active: route.path.endsWith('/writing'), disabled: !currentAssetProject.value },
  {
    to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/assets' : '/projects',
    title: '资产生成',
    icon: 'picture',
    active: route.path.endsWith('/assets'),
    disabled: !currentAssetProject.value,
  },
  { to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/storyboard' : '/projects',
    title: '分镜工作台', icon: 'flow', active: route.path.endsWith('/storyboard'), disabled: !currentAssetProject.value },
  { to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/render' : '/projects',
    title: '视频渲染', icon: 'picture', active: route.path.endsWith('/render'), disabled: !currentAssetProject.value },
  { to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/audio' : '/projects',
    title: '角色配音', icon: 'queue', active: route.path.endsWith('/audio'), disabled: !currentAssetProject.value },
  { to: currentAssetProject.value ? '/projects/' + currentAssetProject.value.id + '/edit' : '/projects',
    title: '剪辑成片', icon: 'flow', active: route.path.endsWith('/edit'), disabled: !currentAssetProject.value },
  { to: '/tasks', title: '任务中心', icon: 'queue', active: route.path === '/tasks' },
  { to: '/workflows', title: '工作流', icon: 'flow', active: route.path === '/workflows' },
])
onMounted(() => void store.start())
onUnmounted(() => store.stop())
</script>
<template>
  <n-config-provider :theme-overrides="theme" :locale="zhCN" :date-locale="dateZhCN"
    ><n-message-provider>
      <div class="shell">
        <aside class="sidebar">
          <router-link class="brand" to="/projects"
            ><span class="brand-icon" aria-hidden="true"><i /><i /><i /></span
            ><span>Local AI Studio</span></router-link
          >
          <nav aria-label="主导航">
            <button
              v-for="n in nav"
              :key="n.title"
              :class="{ selected: n.active }"
              :disabled="n.disabled"
              @click="router.push(n.to)"
            >
              <span class="nav-icon" :class="n.icon" aria-hidden="true"></span><span>{{ n.title }}</span
              ><b v-if="n.title === '任务中心' && store.activeTasks.length">{{ store.activeTasks.length }}</b>
            </button>
          </nav>
          <div class="sidebar-bottom">
            <i class="dot" :class="{ online: store.resources?.engine.available }"></i>
            <div>
              <strong>{{ store.resources?.engine.mode === 'mock' ? 'Mock ComfyUI' : 'ComfyUI' }}</strong>
              <small>{{ store.resources?.engine.available ? '本地服务已连接' : '等待本地服务' }}</small>
            </div>
          </div>
        </aside>
        <div class="workspace">
          <header class="topbar">
            <span
              >制作工作台 <i>/</i> <strong>{{ route.meta.title }}</strong></span
            >
            <span class="wire"
              ><task-tray /><i class="dot" :class="{ online: store.live }"></i
              >{{ store.live ? '实时同步' : '连接中' }}</span
            >
          </header>
          <main>
            <div v-if="store.error" class="error-banner" role="alert">
              <span>{{ store.error }}</span
              ><n-button text @click="store.error = ''">关闭</n-button>
            </div>
            <router-view />
          </main>
        </div></div></n-message-provider
  ></n-config-provider>
</template>
