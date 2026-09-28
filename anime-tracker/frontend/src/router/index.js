import { createRouter, createWebHistory } from 'vue-router'
import { loadStoredUser } from '../utils/userStorage'

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

  // ── 兜底: 必须放在最后 ────────────────────────────
  //
  // 这条吃下所有没被上面匹配到的路径. 没有它的时候, 访问一个不存在的地址
  // (手输错、旧书签、别人分享的失效链接)会得到一个**全白的页面** ——
  // router 匹配不到任何路由, 而 App.vue 里没有可渲染的东西, 控制台也没有任何提示.
  // 对作品集来说这类访问并不罕见(有人就是会去戳地址栏), 白屏会被读成「网站坏了」.
  //
  // 语法用 :pathMatch(.*)* 而不是 :pathMatch(.*): 前者才能匹配到多级路径
  // (/a/b/c 之类), 后者只吃一级. 尾部那个 *(可重复)是关键.
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue'),
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
  // 从 localStorage 读取用户状态 (与 stores/user.js 走同一个入口)
  //
  // 必须用 loadStoredUser() 而不是自己 JSON.parse: 守卫是每一次跳转的必经之路,
  // 这里抛异常等于整个站白屏, 而 storage 里的内容用户和任何脚本都能改.
  // 解析失败时它返回 null 并把坏数据清掉, 于是最坏的结果只是「回到未登录态」.
  const user = loadStoredUser()
  const isLoggedIn = user !== null

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
