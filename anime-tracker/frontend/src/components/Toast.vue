<template>
  <Teleport to="body">
    <TransitionGroup name="toast" tag="div" class="toast-container">
      <div
        v-for="item in items"
        :key="item.id"
        class="toast-item"
        :class="'toast-' + item.type"
      >
        <span class="toast-icon">{{ iconMap[item.type] }}</span>
        <span class="toast-msg">{{ item.message }}</span>
        <button
          v-if="item.action"
          class="toast-action"
          @click="item.action.handler(); remove(item.id)"
        >{{ item.action.label }}</button>
        <button class="toast-close" @click="remove(item.id)">×</button>
      </div>
    </TransitionGroup>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'

const items = ref([])
let nextId = 0
const timers = new Map()
const iconMap = { success: '✅', error: '❌', info: 'ℹ️', warning: '⚠️' }

function remove(id) {
  items.value = items.value.filter(i => i.id !== id)
  const t = timers.get(id)
  if (t) { clearTimeout(t); timers.delete(id) }
}

function show(message, type = 'info', duration = 3500, action = null) {
  const id = nextId++
  items.value.push({ id, message, type, action })
  const timer = setTimeout(() => remove(id), duration)
  timers.set(id, timer)
}

// Mount to global
if (typeof window !== 'undefined') {
  window.$toast = show
}

defineExpose({ show, remove })
</script>

<style scoped>
.toast-container {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 9999;
  display: flex;
  flex-direction: column-reverse;
  gap: 8px;
  pointer-events: none;
}
.toast-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 8px 32px rgba(0,0,0,.2);
  pointer-events: auto;
  min-width: 240px;
  max-width: 420px;
  background: var(--card);
  color: var(--text);
  border: 1px solid var(--card-border);
  backdrop-filter: blur(12px);
}
.toast-icon { font-size: 18px; flex-shrink: 0; }
.toast-msg { flex: 1; line-height: 1.4; }
.toast-action {
  padding: 5px 12px; border-radius: 6px;
  font-size: 12px; font-weight: 700; cursor: pointer;
  border: none; color: #fff; background: var(--primary);
  white-space: nowrap; font-family: inherit;
  transition: background var(--transition);
}
.toast-action:hover { background: var(--primary-hover); }
.toast-close {
  width: 24px; height: 24px; border-radius: 6px;
  border: none; background: transparent;
  color: var(--text-muted); font-size: 16px;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: all var(--transition); flex-shrink: 0;
}
.toast-close:hover { background: var(--card-hover); color: var(--text); }

/* Type colors */
.toast-success { border-left: 3px solid var(--success); }
.toast-error { border-left: 3px solid var(--danger); }
.toast-info { border-left: 3px solid var(--primary); }
.toast-warning { border-left: 3px solid var(--warning); }

/* Transitions */
.toast-enter-active { transition: all .35s cubic-bezier(.4,0,.2,1); }
.toast-leave-active { transition: all .2s ease; }
.toast-enter-from { opacity: 0; transform: translateY(16px) scale(.95); }
.toast-leave-to { opacity: 0; transform: translateX(60px) scale(.9); }
</style>
