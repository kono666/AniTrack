<template>
  <div id="app-root">
    <!-- 后台有自己的外壳(左栏), 公共导航栏在那里是多余的 —— 而且它的登录后
         下拉里同时挂着"主题开关"和"退出登录", 所以侧栏必须把那两件事一起接管.
         见 components/AdminLayout.vue. -->
    <NavBar v-if="!isAdminRoute" />
    <main class="main-content" :class="{ 'main-content--admin': isAdminRoute }">
      <router-view v-slot="{ Component, route }">
        <Transition :name="transitionName" mode="out-in">
          <component :is="Component" :key="viewKey(route)" />
        </Transition>
      </router-view>
    </main>
    <Toast />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import Toast from './components/Toast.vue'

const router = useRouter()
const route = useRoute()
const transitionName = ref('page-fade')

/** 后台三条子路由共用同一个外壳, 所以它们对"要不要导航栏"这件事是一个答案 */
const isAdminRoute = computed(() => route.path.startsWith('/admin'))

/**
 * 给 <component> 的 key.
 *
 * 后台三个子页把 key 固定成 '/admin', 于是 shell 在子页之间**不重建** ——
 * 侧栏不重播入场动画、抽屉状态不丢, 内容区的切换交给 shell 内部的 router-view.
 *
 * 其余页面仍然按整条路径重建, 这一条是**承重**的: /anime/5 -> /anime/6 必须换实例
 * (详情页的数据靠 onMounted 拉, 不换实例就不会重新请求). 所以这里**不能**图省事
 * 改成 route.matched[0].path —— 那对后台是对的, 却会把 /anime/:id 之间的一起弄坏.
 */
function viewKey(r) {
  return r.path.startsWith('/admin') ? '/admin' : r.path
}

// Detect navigation direction for slide transition
watch(() => router.currentRoute.value, (to, from) => {
  if (!from) { transitionName.value = 'page-fade'; return }
  const toDepth = to.path.split('/').length
  const fromDepth = from.path.split('/').length
  transitionName.value = toDepth >= fromDepth ? 'page-slide-left' : 'page-slide-right'
})
</script>

<style scoped>
.main-content {
  min-height: calc(100vh - 64px);
  background: var(--bg);
  /* 它跟着切主题变底色, 所以用跟 body 同一条 --transition-slow(改前这里是 .3s,
     而 body 是 400ms —— 切一次主题, 两层底色会在不同时刻停住) */
  transition: background var(--transition-slow);
}

/* 上面那条 min-height 减掉的 64px 正是导航栏的高度. 后台不渲染导航栏, 减了
   就会在页面底部留一条 64px 的死带(滚到底之后多出一截空白背景). */
.main-content--admin {
  min-height: 100vh;
}

/* 页面切换的两套动效. 时长和缓动都收进 token(改前两处各写一份 .25s +
   `cubic-bezier(.4,0,.2,1)` —— 而那个值恰好就是 --ease)。
   滑动用 --ease 而不是 --ease-out: 它同时有"离开"和"进入"两半, 需要一条
   两边对称的曲线; 收尾型只适合只有入场的那一种。 */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity var(--dur) var(--ease);
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}

/* Page Slide Left (going deeper) */
.page-slide-left-enter-active,
.page-slide-left-leave-active {
  transition: all var(--dur) var(--ease);
}
.page-slide-left-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.page-slide-left-leave-to {
  opacity: 0;
  transform: translateX(-20px);
}

/* Page Slide Right (going back) */
.page-slide-right-enter-active,
.page-slide-right-leave-active {
  transition: all var(--dur) var(--ease);
}
.page-slide-right-enter-from {
  opacity: 0;
  transform: translateX(-20px);
}
.page-slide-right-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

/* 整页横向滑动是"减少动效"这个设置最想拦掉的那一类东西 —— 不是一个小位移,
   而是**整屏内容**从边上滑进来. 系统里开了这个设置的话, 这里直接不播:
   页面切换仍然发生, 只是不再有过程. */
@media (prefers-reduced-motion: reduce) {
  .page-fade-enter-active, .page-fade-leave-active,
  .page-slide-left-enter-active, .page-slide-left-leave-active,
  .page-slide-right-enter-active, .page-slide-right-leave-active {
    transition: none;
  }
}
</style>
