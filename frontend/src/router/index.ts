import { createRouter, createWebHashHistory } from 'vue-router'
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/projects', component: () => import('../features/Projects.vue'), meta: { title: '项目库' } },
    { path: '/projects/:id/writing', component: () => import('../features/writing/Writing.vue'), meta: { title: '编剧工作台' } },
    {
      path: '/projects/:id/assets',
      component: () => import('../features/Assets.vue'),
      meta: { title: '资产生成' },
    },
    { path: '/projects/:id/storyboard', component: () => import('../features/storyboard/Storyboard.vue'), meta: { title: '分镜工作台' } },
    { path: '/projects/:id/render', component: () => import('../features/render/Render.vue'), meta: { title: '视频渲染' } },
    { path: '/projects/:id/audio', component: () => import('../features/audio/Dubbing.vue'), meta: { title: '角色配音' } },
    { path: '/projects/:id/edit', component: () => import('../features/edit/Editing.vue'), meta: { title: '剪辑成片' } },
    { path: '/tasks', component: () => import('../features/Tasks.vue'), meta: { title: '任务中心' } },
    { path: '/workflows', component: () => import('../features/Workflows.vue'), meta: { title: '工作流' } },
    { path: '/:pathMatch(.*)*', redirect: '/projects' },
  ],
})
