<template>
  <div class="loading" :class="{ inline: inline }">
    <span class="spinner" v-if="!noSpinner"></span>
    <span>{{ text }}</span>
  </div>
</template>

<script setup>
defineProps({
  text: { type: String, default: '加载中...' },
  inline: { type: Boolean, default: false },
  noSpinner: { type: Boolean, default: false },
})
</script>

<style scoped>
.inline { padding: 12px 0; }
.spinner {
  display: inline-block;
  width: 18px; height: 18px;
  border: 2px solid var(--border);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin .6s linear infinite;
  margin-right: 8px;
  vertical-align: middle;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
