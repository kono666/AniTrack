<template>
  <div class="empty-state">
    <component :is="ICONS[type] || ICONS.empty" class="icon" :size="40" weight="light" aria-hidden="true" :data-type="type" />
    <p>{{ message }}</p>
    <!-- 默认插槽: 给「空」的时候放一个出口用的(Profile 那里放的就是
         「去发现动漫」的链接). 改前这个组件根本没有 slot, 于是调用方写进去的
         内容被**丢掉**了 —— 既没有报错也没有提示, 只是那一行字不出现,
         而"空态里少一个链接"这种事不会有人去核对. -->
    <slot />
    <button v-if="actionLabel" class="action-btn" @click="$emit('action')">{{ actionLabel }}</button>
  </div>
</template>

<script setup>
import {
  PhTray, PhWarning, PhMagnifyingGlass, PhTag,
  PhFilmStrip, PhBookmarks, PhChatCircle, PhUsers, PhCompass,
} from '@phosphor-icons/vue'

/* 空态图标表. 改前这里是个 `icon: String`(默认是那个"空邮箱" emoji), 由 15 个调用点各传一个
   emoji —— 而那 15 次里有 7 次传的是同一个警告图标. 也就是说这个 prop 几乎不
   承载信息, 它只是让每个调用点把同一件事重写一遍, 顺手把 emoji 的字形交给了
   系统字体(同一个站在 Windows 和 macOS 上是两套图标).
   改成语义名之后, 调用点写的是"这是什么空态", 图标由组件决定. */
const ICONS = {
  empty: PhTray,             // 默认: 确实是"这里没有东西"
  error: PhWarning,          // 加载失败(调用最多次的一个)
  search: PhMagnifyingGlass, // 搜了但没结果 / 还没搜
  tag: PhTag,
  episode: PhFilmStrip,
  tracking: PhBookmarks,
  comment: PhChatCircle,
  user: PhUsers,
  notfound: PhCompass,
}
defineProps({
  type: { type: String, default: 'empty' },
  message: { type: String, default: '暂无数据' },
  actionLabel: { type: String, default: '' },
})
defineEmits(['action'])
</script>

<style scoped>
/* 图标现在是 SVG, 不是字符 —— `.empty-state .icon { font-size:56px }` 那条
   对 SVG 一点作用都没有(改完就删了). 大小由 :size 给, 颜色跟着 .empty-state
   的 --text-muted 走(Phosphor 用 currentColor). */
.icon { display: block; margin: 0 auto 10px; }

.action-btn {
  margin-top: 12px;
  padding: 8px 24px;
  background: var(--primary);
  color: var(--primary-foreground);
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background .2s;
}
/* 改前这里写的是 var(--primary-dark), 而全项目**没有**这个变量(只有
   --primary 和 --primary-hover). 后果不是"回到默认色": var() 取不到值时
   整条声明在计算值阶段就失效, background 退回初始值 transparent ——
   悬停时按钮会变成透明底 + 白字, 像被抠掉了一样. */
.action-btn:hover { background: var(--primary-hover); }
</style>
