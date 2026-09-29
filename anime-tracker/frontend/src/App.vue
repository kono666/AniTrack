<template>
  <div id="app-root">
    <NavBar />
    <main class="main-content">
      <router-view v-slot="{ Component, route }">
        <Transition :name="transitionName" mode="out-in">
          <component :is="Component" :key="route.path" />
        </Transition>
      </router-view>
    </main>
    <Toast />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import NavBar from './components/NavBar.vue'
import Toast from './components/Toast.vue'

const router = useRouter()
const transitionName = ref('page-fade')

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
