<template>
  <div class="page-container">
    <div class="page-header">
      <h1>{{ title }}</h1>
    </div>

    <div class="tabs">
      <router-link
        v-for="tab in tabs"
        :key="tab.path"
        :to="tab.path"
        class="tab"
        :class="{ active: $route.path === tab.path }"
      >{{ tab.label }}</router-link>
    </div>

    <slot />
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '⚙️ 管理后台' },
})

const tabs = [
  { path: '/admin', label: '📊 仪表盘' },
  { path: '/admin/users', label: '👥 用户管理' },
  { path: '/admin/reviews', label: '💬 评论管理' },
]
</script>
