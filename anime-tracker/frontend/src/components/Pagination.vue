<template>
  <div v-if="totalPages > 1" class="pagination">
    <button class="pg-btn" :disabled="currentPage <= 1" @click="$emit('change', 1)" title="首页">«</button>
    <button class="pg-btn" :disabled="currentPage <= 1" @click="$emit('change', currentPage - 1)">‹</button>

    <template v-for="p in pageRange" :key="p">
      <span v-if="p === '...'" class="pg-ellipsis">…</span>
      <button
        v-else
        class="pg-btn"
        :class="{ active: p === currentPage }"
        @click="$emit('change', p)"
      >{{ p }}</button>
    </template>

    <button class="pg-btn" :disabled="currentPage >= totalPages" @click="$emit('change', currentPage + 1)">›</button>
    <button class="pg-btn" :disabled="currentPage >= totalPages" @click="$emit('change', totalPages)" title="末页">»</button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  currentPage: { type: Number, required: true },
  totalPages: { type: Number, required: true },
  maxVisible: { type: Number, default: 7 },
})

defineEmits(['change'])

const pageRange = computed(() => {
  const total = props.totalPages
  const cur = props.currentPage
  const max = props.maxVisible

  if (total <= max + 2) {
    // Show all pages (no ellipsis needed)
    return Array.from({ length: total }, (_, i) => i + 1)
  }

  const pages = []
  const sideCount = Math.floor((max - 3) / 2) // pages on each side of current

  // Always show first page
  pages.push(1)

  let left = Math.max(2, cur - sideCount)
  let right = Math.min(total - 1, cur + sideCount)

  // Adjust if near edges
  if (cur - sideCount <= 2) {
    right = Math.min(total - 1, 1 + max - 2)
  }
  if (cur + sideCount >= total - 1) {
    left = Math.max(2, total - max + 1)
  }

  if (left > 2) pages.push('...')
  for (let i = left; i <= right; i++) pages.push(i)
  if (right < total - 1) pages.push('...')

  // Always show last page
  pages.push(total)

  return pages
})
</script>

<style scoped>
.pagination { display:flex; justify-content:center; align-items:center; gap:4px; margin-top:28px; }
.pg-btn {
  min-width: 36px; height: 36px; padding: 0 8px;
  border: 1.5px solid var(--border); border-radius: var(--radius-sm);
  background: var(--card); cursor: pointer; font-size: 13px; color: var(--text-secondary);
  transition: all var(--transition);
  display: flex; align-items: center; justify-content: center;
  font-family: inherit;
}
.pg-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); background: var(--card-hover); }
.pg-btn:disabled { opacity:.25; cursor:default; }
.pg-btn.active { background: var(--primary); color: #fff; border-color: var(--primary); font-weight: 700; box-shadow: 0 2px 8px rgba(168,85,247,.3); }
.pg-ellipsis { width: 36px; text-align: center; color: var(--text-muted); font-size: 14px; user-select: none; }
</style>
