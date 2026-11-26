import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/Login.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: () => import('../views/Home.vue'),
    },
    {
      path: '/graph',
      name: 'graph',
      component: () => import('../views/Graph.vue'),
    },
    {
      path: '/continue',
      name: 'continue',
      component: () => import('../views/Continue.vue'),
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('../views/Admin.vue'),
    },
  ],
})

router.beforeEach(async (to, _from, next) => {
  if (to.path === '/admin') {
    const auth = useAuthStore()
    if (!auth.user) await auth.fetchMe()
    if (!auth.isAdmin) return next('/')
  }
  next()
})

export default router
