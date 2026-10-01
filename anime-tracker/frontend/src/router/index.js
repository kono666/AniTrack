import { createRouter, createWebHistory } from 'vue-router'
import { loadStoredUser } from '../utils/userStorage'
import { rememberPath } from '../utils/loginRedirect'

// 每条路由的 meta.title 都会被 afterEach 拼成 document.title.
// 它是**页面名**不是完整标题 —— 后缀「· AniTrack」由 afterEach 统一加,
// 免得 11 条路由各写一遍站点名、改站名时漏掉几条.
const routes = [
  { path: '/', name: 'Home', component: () => import('../views/Home.vue'), meta: { title: '首页' } },
  { path: '/search', name: 'Search', component: () => import('../views/Search.vue'), meta: { title: '搜索' } },
  // 「分类浏览」原先挤在首页最底部, 现在整块搬出来成了独立页(首页只留导航栏一个入口).
  //
  // meta.scrollOnQueryChange: 同一条路径上只换 query(点标签、翻页)时**不要**回顶.
  // 改前 query 一变就 {top:0}, 于是"点一个标签, 页面跳回顶部, 而结果在标签墙底下
  // 根本看不见". 这一页自己决定要不要把结果送去视野(见 Tags.vue 的 scrollToResults).
  //
  // 这条例外**必须挂在 meta 上**, 不能写成全局的"同 path 就不滚": 搜索页的翻页
  // 与"在 /search 上再搜一次"也是同 path 的 query 变化, 那两处正是靠回顶让用户
  // 看到新结果的开头.
  { path: '/tags', name: 'Tags', component: () => import('../views/Tags.vue'), meta: { title: '分类浏览', scrollOnQueryChange: false } },
  { path: '/anime/:id', name: 'AnimeDetail', component: () => import('../views/AnimeDetail.vue'), props: true, meta: { title: '番剧详情' } },
  // AI 助手刻意不要求登录: 访客能直接对话是公网 Demo 的重点,
  // 而服务端只会把公开工具暴露给访客, 不存在越权的可能
  { path: '/assistant', name: 'Assistant', component: () => import('../views/Assistant.vue'), meta: { title: 'AI 助手' } },
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { title: '登录' } },
  { path: '/register', name: 'Register', component: () => import('../views/Register.vue'), meta: { title: '注册' } },

  // ── 需要登录的路由 ──────────────────────────────
  {
    path: '/my',
    redirect: '/profile',
  },
  {
    path: '/profile',
    name: 'Profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true, title: '个人中心' },
  },

  // ── 需要管理员权限的路由 ──────────────────────────
  //
  // 改前这是三条**互相平级**的路由, 各自在模板里包一层 AdminLayout. 后果是:
  // 外壳跟着每一页的 bundle 走、"外壳归谁管"没有答案, 而三个页面各自把
  // 「不是管理员就踢走」那句判断抄了一遍.
  //
  // 现在收成一条父路由 + 三个子路由. 三件不能想当然的事:
  //
  // 一、**父路由上不要写 redirect**. 下面 `path: ''` 那个子路由的地址就是 /admin,
  //   写 redirect 等于让它指自己(症状是"点管理后台没反应").
  // 二、**name 留在子路由上**. router/__tests__/guard.test.js 断言的正是
  //   AdminUsers / AdminDashboard 这两个名字, 它是这次重构的守卫.
  // 三、**meta 是合并的, 后面的记录赢** —— 子路由的 title 覆盖父的, 父的
  //   requiresAdmin 被子继承(所以守卫那句一个字不用改). 实测过 vue-router 5.3.1:
  //   /admin 落在 AdminDashboard 上, 三个 name 与三份 title 都正确, matched 长度 2.
  {
    path: '/admin',
    component: () => import('../components/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'AdminDashboard',
        component: () => import('../views/admin/Dashboard.vue'),
        meta: { title: '后台概览' },
      },
      {
        path: 'users',
        name: 'AdminUsers',
        component: () => import('../views/admin/Users.vue'),
        // 与 /tags 同一个理由: 这一页的搜索/筛选/排序/翻页全写在 query 上,
        // 而点一下筛选并不是"到了另一个地方" —— 回顶会把用户从翻页条那里
        // 扔回页面最上面. 判据挂 meta 上而不是写成全局的"同 path 就不滚":
        // 搜索页的翻页与"在 /search 上再搜一次"正靠回顶让用户看到新结果的开头.
        meta: { title: '用户管理', scrollOnQueryChange: false },
      },
      {
        path: 'reviews',
        name: 'AdminReviews',
        component: () => import('../views/admin/Reviews.vue'),
        meta: { title: '评论管理' },
      },
      {
        path: 'actions',
        name: 'AdminActions',
        component: () => import('../views/admin/Audit.vue'),
        // 与 users 同一条理由, 只是这里的条件更少: action / page / limit 三个也
        // 全写在 query 上, 而改筛选、翻页都不是"到了另一个地方". 加上
        // scrollOnQueryChange:false 之后, 越界那一页点「回到第 1 页」才不会
        // 把人从分页条那里扔回页面最顶上.
        meta: { title: '操作日志', scrollOnQueryChange: false },
      },
    ],
  },

  // ── 兜底: 必须放在最后 ────────────────────────────
  //
  // 这条吃下所有没被上面匹配到的路径. 没有它的时候, 访问一个不存在的地址
  // (手输错、旧书签、别人分享的失效链接)会得到一个**全白的页面** ——
  // router 匹配不到任何路由, 而 App.vue 里没有可渲染的东西, 控制台也没有任何提示.
  // 对作品集来说这类访问并不罕见(有人就是会去戳地址栏), 白屏会被读成「网站坏了」.
  //
  // 语法用 :pathMatch(.*)* —— 末尾那个 *(可重复)只影响 params.pathMatch 被解析成
  // 数组还是字符串, **不影响匹配哪些路径**(实测: 去掉它, 全部用例仍然绿).
  // 真正会让嵌套路径漏下去的是不带正则的写法 `:pathMatch`, 它只吃一级.
  // 所以这里保留 (.*)* 只是为了拿到数组形式的 params, 别把它当成"能吃多级"的原因.
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue'),
    meta: { title: '页面不存在' },
  },
]

/** 页面过渡时长读不到时的兜底值, 与 tokens.css 的 --dur 同值 */
const PAGE_TRANSITION_FALLBACK_MS = 200
/** 过渡结束后再多等一点, 让新页面把内容铺进 DOM 之后再滚 */
const TRANSITION_SLACK_MS = 40

/**
 * 读 tokens.css 的 --dur(页面切换过渡时长).
 *
 * 必须**调用时**读, 不能模块加载时读成一个常量: main.js 里 `./router` 先于
 * `./assets/css/style.css` 求值, 那一刻样式表还没注入, 读到的是空串.
 * 读不到就用兜底值 —— jsdom 里也走这条(getComputedStyle 不解析自定义属性),
 * 单测因此可以按 200ms 来打点.
 */
function pageTransitionMs() {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--dur').trim()
  const n = Number.parseFloat(raw) // '200ms' -> 200
  return Number.isFinite(n) ? n : PAGE_TRANSITION_FALLBACK_MS
}

/**
 * 后退/前进时把滚动位置放回去.
 *
 * 改前这里写死 `{ top: 0 }` —— 后退也是回顶, 于是「首页翻到第 3 页 → 点进详情 →
 * 按后退」回到的是首页顶部, 用户滚到哪儿完全不记得.
 *
 * 四件事让它不是一个"一行就能改"的改动:
 *
 * 一、必须**延迟到页面过渡结束之后**再滚.
 *   vue-router 的 handleScroll 是 `nextTick().then(() => scrollBehavior(...))`
 *   (vue-router.esm-browser.js 的 handleScroll), 也就是路由一变就在下一个微任务
 *   里滚. 而 App.vue 是 `<Transition mode="out-in">` —— 新页面要等旧页面走完
 *   --dur(200ms)才挂载. 所以那一刻 DOM 里**还是旧页面**: 滚动作用在旧页面的高度
 *   上, 紧接着旧页面卸载、高度塌掉, scrollTop 被夹回 0, 而且不会再补一次
 *   (getSavedScrollPosition 读完就 delete, 只有一次机会). 直接 return
 *   savedPosition 在本项目里等于没写 —— 这是实测过 vue-router 源码才敢下的结论.
 *   返回 Promise 把滚动推迟过去, 顺便白拿一个好处: handleScroll 最后有
 *   `to === currentRoute.value &&` 的判断, 等待期间用户又导航了, 这次滚动会自动
 *   被丢掉, 不会滚错页面.
 *
 * 二、behavior 必须是 'instant', 不能是 'auto'.
 *   'auto' 的意思是"按 CSS 的 scroll-behavior 来", 而 base.css 上写着
 *   `html { scroll-behavior: smooth }` —— 用 'auto' 等于让它平滑滚动, 而这段
 *   平滑动画正好和页面切换撞在一起. 'instant' 才是"立刻到那儿".
 *   回顶那条**刻意不带 behavior**(与改动前逐字一致), 那是既有的观感, 本轮不动.
 *
 * 三、浏览器自带的恢复已经被关掉了.
 *   createRouter 见到 options.scrollBehavior 就会把 history.scrollRestoration
 *   设成 'manual'(vue-router.esm-browser.js 里那一行), 也就是说这套是"全有或全无",
 *   设了就得自己负责放回去.
 *
 * ⚠️ 已知落差(接受, 见计划取舍): 延迟只保证"页面过渡"结束, 不保证"数据"到齐.
 * 首页冷缓存 / 搜索结果页要等各自的请求回来才会变高, 那时文档还不够高, 位置会被
 * 钳到当时的底部. 缓存命中的首页、以及本来就在底部附近的后退是准的.
 *
 * 四、目标是**同一条路径上只换了 query** 的页面可以声明"这次别滚"(见下面第二条).
 *   分类页的筛选条件写在 query 里, 而点一个标签 / 翻一页并不是"到了另一个地方" ——
 *   回顶会把用户从"我刚点的那排标签"扔回页面最上面, 而他点的那个标签在几百像素
 *   以下. 判据挂在目标路由的 meta 上而不是写成全局的 `to.path === from.path`:
 *   搜索页的翻页与"在 /search 上再搜一次"也是同 path 的 query 变化, 那两处正是
 *   靠回顶让用户看到新结果的开头, 放宽成全局会把它弄坏.
 */
export function scrollBehavior(to, from, savedPosition) {
  // 一、后退/前进优先. 这一条必须排在最前面: 有 savedPosition 就是 popstate,
  //    那时候"恢复到哪儿"比"这一页要不要自己滚"更权威. 反过来的话, 同一条路径的
  //    两个历史条目之间后退会被判成"query 变了, 不滚", 恢复位置就丢了.
  if (savedPosition) {
    return new Promise((resolve) => {
      setTimeout(
        () => resolve({ ...savedPosition, behavior: 'instant' }),
        pageTransitionMs() + TRANSITION_SLACK_MS,
      )
    })
  }
  // 二、同 path 且只有 query 变了, 而目标页声明了自己管滚动 → 一次都不滚.
  //    `to.path === from.path` 这一半不能省: 从别处**进入** /tags 时 to.meta 上
  //    同样有这个标记, 但那一次是换页, 应该照常回顶.
  //    `to.fullPath !== from.fullPath` 也不能省: 已经在 /tags 上再点一次导航栏
  //    「分类」时 query 一个字都没变, 那次仍然是"重新去这一页", 该回顶.
  //    判据写 `=== false` 而不是 `!to.meta?.x`: 单测里传的是 {} , meta 是
  //    undefined, 必须短路到下面那条既有的回顶分支(见 scrollBehavior.test.js).
  //    返回 false 会被 vue-router 的 handleScroll 用 `position &&` 短路掉 ——
  //    确实一次滚动都不发生, 而不是"滚到 0".
  if (to.meta?.scrollOnQueryChange === false
      && to.path === from.path && to.fullPath !== from.fullPath) {
    return false
  }
  // 三、其余 push 式导航维持原样, 逐字不动
  return { top: 0 }
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior,
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

/**
 * 记下「刚才那一页」, 给登录页兜底用.
 *
 * 守卫只覆盖了 requiresAuth 的路由; 而跳登录的入口有好几处是硬跳的 ——
 * 导航栏的「登录」、AnimeDetail 的「+ 追番」与「登录后参与讨论」、Assistant、
 * Profile, 它们都不带 redirect, URL 里没有任何线索, 只能靠这里记的一笔.
 *
 * 用 afterEach 而不是 beforeEach: 只记**真的进去了**的页面. beforeEach 会在
 * 被重定向掉的跳转上也记一笔, 那记下的就是没去成的那一页.
 */
router.afterEach((to) => {
  rememberPath(to.fullPath)
  setDocumentTitle(to)
})

/** 站点名. 只在两处出现: 这里, 以及 index.html 里那条首屏兜底 —— 改站名要一起改 */
const SITE_NAME = 'AniTrack - 动漫追番'

/**
 * 把当前路由的 meta.title 写进标签页标题.
 *
 * 改前整站只有 index.html 里那条写死的标题: 从首页点到详情再点进助手, 浏览器
 * 标签页、历史记录、书签**永远是同一句话**, 开三个标签分不清哪个是哪个.
 *
 * 没有 meta.title 就退回站点名, 而不是拼一个空串出来 —— 后者会让标签页标题
 * 变成光秃秃的「 · AniTrack」, 比不写还难认.
 *
 * 用 afterEach 而不是 beforeEach: 只给**真的进去了**的页面改名. beforeEach 会在
 * 被守卫重定向掉的那次跳转上先改一遍, 于是标签页会闪一下没去成的那一页的名字
 * (记 rememberPath 用的是同一个理由).
 */
function setDocumentTitle(to) {
  const title = to.meta?.title
  document.title = title ? `${title} · AniTrack` : SITE_NAME
}

export default router
