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
  transition: background .3s;
}

/* Page Fade */
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity .2s ease;
}
.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}

/* Page Slide Left (going deeper) */
.page-slide-left-enter-active,
.page-slide-left-leave-active {
  transition: all .25s cubic-bezier(.4,0,.2,1);
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
  transition: all .25s cubic-bezier(.4,0,.2,1);
}
.page-slide-right-enter-from {
  opacity: 0;
  transform: translateX(-20px);
}
.page-slide-right-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
