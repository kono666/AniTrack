<template>
  <div class="empty-state">
    <div class="icon" v-if="icon">{{ icon }}</div>
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
/* 改前这里写的是 var(--primary-dark), 而全项目**没有**这个变量(只有
   --primary 和 --primary-hover). 后果不是"回到默认色": var() 取不到值时
   整条声明在计算值阶段就失效, background 退回初始值 transparent ——
   悬停时按钮会变成透明底 + 白字, 像被抠掉了一样. */
.action-btn:hover { background: var(--primary-hover); }
</style>
