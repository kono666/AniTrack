<template>
  <div class="page-container not-found">
    <EmptyState
      icon="🧭"
      :message="message"
      action-label="回首页"
      @action="goHome"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'

const route = useRoute()
const router = useRouter()

/**
 * 把访问的那个地址回显出来.
 *
 * 不是装饰: 会来到这个页面的人, 多半是手输错了地址、或者点了一个失效的分享链接.
 * 把完整路径写在提示里, 他才能一眼看出是哪里不对(少了个字母? 还是链接本身过期了),
 * 而「页面不存在」这种通用文案只会让人怀疑是不是站点坏了.
 */
const message = computed(() => `没有这个页面: ${route.fullPath}`)

function goHome() {
  router.push('/')
}
</script>

<style scoped>
/* EmptyState 本身是「一块内容」的样式(上下 padding), 直接放在页面顶部会显得像没加载完.
   这里把它在视口里居中, 让 404 看起来是一个有意为之的页面. */
.not-found {
  min-height: 60vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
