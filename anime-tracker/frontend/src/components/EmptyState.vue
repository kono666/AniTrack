<template>
  <div class="empty-state">
    <div class="icon" v-if="icon">{{ icon }}</div>
    <p>{{ message }}</p>
    <button v-if="actionLabel" class="action-btn" @click="$emit('action')">{{ actionLabel }}</button>
  </div>
</template>

<script setup>
defineProps({
  icon: { type: String, default: '📭' },
  message: { type: String, default: '暂无数据' },
  actionLabel: { type: String, default: '' },
})
defineEmits(['action'])
</script>

<style scoped>
.action-btn {
  margin-top: 12px;
  padding: 8px 24px;
  background: var(--primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background .2s;
}
.action-btn:hover { background: var(--primary-dark); }
</style>
