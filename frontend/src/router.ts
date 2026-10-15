import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './store'

/**
 * 路由表。
 *
 * <p>守卫只判「有没有 token」，不主动调 /me——启动时在 App.vue 里统一确认一次，
 * 避免每个路由跳转都打一发请求。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('./views/AppShell.vue'),
      children: [
        { path: '', redirect: '/projects' },
        { path: 'projects', name: 'projects', component: () => import('./views/ProjectsView.vue') },
        {
          path: 'projects/:id',
          name: 'project-detail',
          component: () => import('./views/ProjectDetailView.vue'),
          props: true,
        },
        { path: 'sessions', name: 'sessions', component: () => import('./views/SessionsView.vue') },
        {
          path: 'sessions/:id',
          name: 'chat',
          component: () => import('./views/ChatView.vue'),
          props: true,
        },
        { path: 'settings', name: 'settings', component: () => import('./views/SettingsView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public) {
    return true
  }
  if (!isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
