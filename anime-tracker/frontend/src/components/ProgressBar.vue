<template>
  <!--
    「看到第几集了」的那根细条.

    视觉与 Profile.vue 里就地写的 .pc-bar/.pc-fill 是同一套(4px 高、2px 圆角、
    --bg-secondary 底 + --primary 填充 + .3s 过渡) —— 那一处本轮**刻意不动**:
    不为一个进度条去改一个 791 行的文件与它那 5 条用例. 代价是同一套视觉有两份
    实现, 这是明确接受的重复, 不是漏了.

    唯一没照抄的是宽度: Profile 那里是写死的 120px, 因为那一行的宽度由列表决定;
    首页的卡片只有 110–160px(见 Home.vue 的响应式), 120px 会在窄屏撑出去, 所以这里是 100%。

    total 为空或 ≤0 时**整根不渲染**, 不是渲染成 0% 的条
  -->
  <div v-if="total > 0" class="pb-bar">
    <div class="pb-fill" :style="{ width: percent + '%' }"></div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 已看到的集号 */
  value: { type: Number, default: 0 },
  /** 总集数. null / 0 / 负数都表示「不知道一共多少集」—— 见下面 percent */
  total: { type: Number, default: 0 },
})

/**
 * 百分比, 0–100.
 *
 * 除零由 total > 0 那道判断挡住 —— 这正是 total 为 null 时整根不渲染的理由:
 * 把 null 当成 0 的话, 这里要么除零得 NaN(宽度算不出来, 条是空的但仍在占位),
 * 要么退化成 0%, 而 0% 的意思是「一集都没看」, 与「不知道一共多少集」是两件事.
 *
 * 上限截到 100: 进度是手输的(AnimeDetail 的数字框), 能改到比总集数还大 ——
 * 不截断就是一条溢出容器的填充色.
 */
const percent = computed(() => {
  if (!(props.total > 0)) return 0
  return Math.min(100, Math.round(((props.value || 0) / props.total) * 100))
})
</script>

<style scoped>
.pb-bar {
  width: 100%; height: 4px;
  background: var(--bg-secondary);
  border-radius: 2px; overflow: hidden;
}
.pb-fill {
  height: 100%; background: var(--primary);
  border-radius: 2px; transition: width .3s;
}
</style>
