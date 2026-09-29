<template>
  <Teleport to="body">
    <TransitionGroup name="toast" tag="div" class="toast-container">
      <div
        v-for="item in items"
        :key="item.id"
        class="toast-item"
        :class="'toast-' + item.type"
      >
        <component :is="ICON_MAP[item.type] || ICON_MAP.info" class="toast-icon" :size="18" weight="fill" aria-hidden="true" />
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
import { PhCheckCircle, PhXCircle, PhInfo, PhWarning } from '@phosphor-icons/vue'
import { useToast } from '../composables/useToast'

/**
 * 这个组件只负责渲染 —— 状态和操作都在 composables/useToast.js 里(模块作用域),
 * 调用方直接 import 那个, 不再经过 window.
 */
const { items, show, remove } = useToast()

// 改前是四个 emoji. 这里比别处更值得换: toast 是唯一"必须一眼看出是哪种状态"的
// 组件, 而 emoji 的对勾和叉是两个完全不同来源的字形(一个来自 Segoe UI Emoji,
// 一个来自 Apple Color Emoji), 大小和视觉重量对不齐. 现在四个是同一套线性图标,
// 颜色由左边的色条表达(见 .toast-* 那条 border-left).
const ICON_MAP = {
  success: PhCheckCircle,
  error: PhXCircle,
  info: PhInfo,
  warning: PhWarning,
}

// 仍然暴露出去: 万一有地方通过 ref 拿到这个组件再调(比如测试里), 行为不变
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
/* 图标是 SVG 了, 原来那条 font-size:18px 对它没有作用(尺寸由 :size 给).
   颜色跟着类型走 —— 左边那道色条只在边缘, 图标是第二个能一眼看出状态的落点. */
.toast-icon { flex-shrink: 0; display: block; }
.toast-success .toast-icon { color: var(--success); }
.toast-error .toast-icon { color: var(--danger); }
.toast-info .toast-icon { color: var(--text-secondary); }
.toast-warning .toast-icon { color: var(--warning); }
.toast-msg { flex: 1; line-height: 1.4; }
.toast-action {
  padding: 5px 12px; border-radius: 6px;
  font-size: 12px; font-weight: 700; cursor: pointer;
  border: none; color: var(--primary-foreground); background: var(--primary);
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

/* Transitions.
   进场用收尾型缓动(它从下方 16px 处升上来, 前段快、后段落定), 出场比进场快一档
   (用户已经看完/关掉了, 不该再等) —— 和导航下拉菜单是同一套说法。 */
.toast-enter-active { transition: all var(--dur-slow) var(--ease-out); }
.toast-leave-active { transition: all var(--dur) var(--ease); }
.toast-enter-from { opacity: 0; transform: translateY(16px) scale(.95); }
.toast-leave-to { opacity: 0; transform: translateX(60px) scale(.9); }
</style>
