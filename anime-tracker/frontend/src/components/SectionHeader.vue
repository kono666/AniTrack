<template>
  <!-- 分区标题栏.

       全站原本有四套并行的实现: Home 的 .section-heading、HorizontalScroll 内建的
       .hs-header、AnimeDetail 的三处 .d-section-hd, 还有 layout.css 里已经死掉的
       .section-title。四份里的字号字重各写各的(20px/800、20px/800、20px/800 但
       margin 不同、20px/700) —— 没有哪一份是"对"的, 它们只是各自被改过一次。
       收成组件之后, "分区标题长什么样"这件事才有唯一的答案。

       左侧那道竖条是伪元素而不是 ▍ 字符: 字符的宽度和基线跟着字体走, 同一个字在
       Saira Condensed 和微软雅黑下宽窄不一, 而伪元素的尺寸是确定的。 -->
  <div class="sec-head">
    <h2 class="sec-title">{{ title }}</h2>
    <div class="sec-side">
      <span v-if="$slots.extra" class="sec-extra"><slot name="extra" /></span>
      <router-link v-if="more" :to="more" class="sec-more">
        {{ moreText }}<span class="sec-more-arrow" aria-hidden="true">→</span>
      </router-link>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  /** 右侧「查看全部」的去处。不传就整条不渲染 —— 多数分区并不存在一个能装下它的页面,
   *  硬塞一个不相干的链接比没有链接更糟。 */
  more: { type: String, default: '' },
  moreText: { type: String, default: '查看全部' },
})
</script>
