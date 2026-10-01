<template>
  <div class="admin-shell">
    <!-- ── 侧栏 ── -->
    <!-- 这一层**没有** <style scoped>, 是刻意的: scoped 样式不在任何 @layer 里,
         层叠层里的规则无论怎么写都输给它 —— 那正是 users.vue 里 .admin-table-wrap
         被抄成两份的原因(见 admin.css 顶部那段). 外壳的规则统一写在
         assets/css/admin.css 里, 只有一个归属. -->
    <aside class="admin-sidebar" :class="{ open: sidebarOpen }">
      <router-link to="/" class="admin-brand" @click="closeSidebar">
        <PhFilmSlate :size="20" weight="fill" aria-hidden="true" />
        <span>AniTrack</span>
      </router-link>

      <div class="admin-nav-label">管理后台</div>
      <nav class="admin-nav">
        <!-- 「仪表盘」用 exact-active-class: to="/admin" 是所有后台路径的前缀,
             不加 exact 的话它在 /admin/users 上也是选中态. 与导航栏里
             「发现」vs「分类/AI 助手」是同一个分法. -->
        <router-link to="/admin" class="admin-nav-link" exact-active-class="active" @click="closeSidebar">
          <PhChartLine :size="16" weight="bold" /> 仪表盘
        </router-link>
        <!-- 这一条除了 active-class 还手写了一个 :class, 因为 /admin/users 与
             /admin/users/:id 是**兄弟路由**而不是父子(见 router/index.js 里那条注释).
             已读 vue-router 5.3.1 的 activeRecordIndex 逐行确认: 从列表点进详情后
             active-class 不会亮 —— 它会去找 /admin/users 这条记录、在 currentMatched
             里找不到, 回退拿 '/admin/users' 去比父级路径 '/admin' 也不相等.
             症状是"进了详情页, 侧栏里用户管理变成未选中", 看起来像"我不在后台这个区里了";
             而链接是手写的, **不会有任何测试或报错提示这一点**.

             startsWith 同时覆盖 /admin/users 本身与它的子路径, 不需要额外判等.
             两处(active-class 与这个 :class)说的必须是同一件事. -->
        <router-link to="/admin/users" class="admin-nav-link" active-class="active"
          :class="{ active: route.path.startsWith('/admin/users') }" @click="closeSidebar">
          <PhUsers :size="16" weight="bold" /> 用户管理
        </router-link>
        <router-link to="/admin/reviews" class="admin-nav-link" active-class="active" @click="closeSidebar">
          <PhChatCircle :size="16" weight="bold" /> 评论管理
        </router-link>
        <!-- 排在最后: 前两项管的是"人"和"内容", 这一项管的是**前面那些动作本身** ——
             它是这一层里唯一一个不属于日常操作、只在追查时才打开的页面. -->
        <router-link to="/admin/actions" class="admin-nav-link" active-class="active" @click="closeSidebar">
          <PhClockCounterClockwise :size="16" weight="bold" /> 操作日志
        </router-link>
      </nav>

      <!-- margin-top:auto 把它钉在侧栏底部 -->
      <div class="admin-sidebar-foot">
        <!-- 这两件事改前在导航栏的登录后下拉里, 而后台不渲染导航栏 ——
             不接管的话, 管理员进了后台就既切不了主题也退不了登录. -->
        <button class="admin-nav-link" @click="toggleLight">
          <PhSunHorizon v-if="!light" :size="16" weight="bold" />
          <PhMoonStars v-else :size="16" weight="bold" />
          {{ light ? '暗色模式' : '亮色模式' }}
        </button>
        <button class="admin-nav-link admin-nav-danger" @click="handleLogout">
          <PhSignOut :size="16" weight="bold" /> 退出登录
        </button>
      </div>
    </aside>

    <!-- ── 内容区 ── -->
    <main class="admin-main">
      <div class="page-header">
        <div class="admin-page-heading">
          <!-- 图标按钮在读屏里只是一句"按钮", 所以要 aria-label;
               aria-expanded 让"这个抽屉现在是开着的吗"也能被念出来 -->
          <button
            class="admin-sidebar-toggle"
            aria-label="后台导航"
            :aria-expanded="sidebarOpen ? 'true' : 'false'"
            @click="sidebarOpen = !sidebarOpen"
          >
            <PhList :size="16" weight="bold" />
          </button>
          <!-- 页面名只有一份来源: meta.title. 同一份值还被 afterEach 拼成
               document.title(见 router/index.js 的 setDocumentTitle) ——
               改前侧栏这里是不动的「管理后台」, 而标签页是「后台概览 · AniTrack」 -->
          <h1>{{ route.meta.title }}</h1>
        </div>
      </div>

      <!-- v-if 这一层是**承重**的, 不只是那句 replace 的补充:
           子页被挡住之后, 未授权时**一次请求都不会发**. 只做 replace 的话,
           子页的 onMounted 仍会先跑一轮再被卸载. -->
      <router-view v-if="authorized" />
    </main>

    <!-- v-if + CSS 里的 display:none 两条都要: 只靠 v-if 的话, 桌面端
         sidebarOpen 一旦为真就会盖上一层全屏浮层 -->
    <div v-if="sidebarOpen" class="admin-scrim" @click="closeSidebar"></div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useTheme } from '../composables/useTheme'
import PhFilmSlate from '@icons/PhFilmSlate.vue.mjs'
import PhChartLine from '@icons/PhChartLine.vue.mjs'
import PhUsers from '@icons/PhUsers.vue.mjs'
import PhChatCircle from '@icons/PhChatCircle.vue.mjs'
import PhClockCounterClockwise from '@icons/PhClockCounterClockwise.vue.mjs'
import PhList from '@icons/PhList.vue.mjs'
import PhSunHorizon from '@icons/PhSunHorizon.vue.mjs'
import PhMoonStars from '@icons/PhMoonStars.vue.mjs'
import PhSignOut from '@icons/PhSignOut.vue.mjs'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { light, toggle: toggleLight } = useTheme()

const sidebarOpen = ref(false)

/**
 * 管理员身份那一次判断, 从三个 view 里收上来一份.
 *
 * 用 replace 不用 push: 非管理员被踢回首页之后, 后退键不该把他弹回后台再被踢一次.
 * 删除 view 里那三份重复判断**没有削弱防线** —— 仍然是三层:
 * router.beforeEach(同步读 localStorage)、这里(读 pinia)、api/index.js 的 403 兜底.
 */
const authorized = ref(userStore.loggedIn && userStore.user?.role === 'ADMIN')
if (!authorized.value) router.replace('/')

function closeSidebar() {
  sidebarOpen.value = false
}

// 窄屏点完导航要把抽屉收起来, 否则新页面被抽屉盖着, 还得手动关一次.
// 抽屉本来就是窄屏专有的, 但 watch 不分支件 —— 桌面端 sidebarOpen 恒为 false.
watch(() => route.path, closeSidebar)

function handleLogout() {
  userStore.logout()
  router.push('/')
}
</script>
