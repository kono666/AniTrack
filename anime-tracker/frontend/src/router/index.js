import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
  { path: '/search', name: 'Search', component: () => import('../views/Search.vue') },
  { path: '/anime/:id', name: 'AnimeDetail', component: () => import('../views/AnimeDetail.vue'), props: true },
  // AI 助手刻意不要求登录: 访客能直接对话是公网 Demo 的重点,
  // 而服务端只会把公开工具暴露给访客, 不存在越权的可能
  { path: '/assistant', name: 'Assistant', component: () => import('../views/Assistant.vue') },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue') },

  // ── 需要登录的路由 ──────────────────────────────
  {
    path: '/my',
    redirect: '/profile',
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true },
  },

  // ── 需要管理员权限的路由 ──────────────────────────
  {
    path: '/admin',
    name: 'AdminDashboard',
    component: () => import('../views/admin/Dashboard.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
  },
  {
    path: '/admin/users',
    name: 'AdminUsers',
    component: () => import('../views/admin/Users.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
  },
  {
    path: '/admin/reviews',
    name: 'AdminReviews',
    component: () => import('../views/admin/Reviews.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() { return { top: 0 } },
})

/**
 * 全局路由守卫:
 * - requiresAuth: 未登录重定向到 /login
 * - requiresAdmin: 非管理员重定向到首页
 */
router.beforeEach((to, from, next) => {
  // 从 localStorage 读取用户状态 (与 stores/user.js 保持一致)
  const stored = localStorage.getItem('anime_user')
  const user = stored ? JSON.parse(stored) : null
  const isLoggedIn = user && user.token

  if (to.meta.requiresAuth && !isLoggedIn) {
    // 保存目标路径, 登录后可跳回
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (to.meta.requiresAdmin && (!user || user.role !== 'ADMIN')) {
    next({ path: '/' })
    return
  }

  next()
})

export default router
