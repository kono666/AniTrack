<template>
  <router-link class="anime-chip" :to="`/anime/${anime.id}`">
    <div class="anime-chip-cover">
      <img v-if="anime.cover" :src="anime.cover" :alt="title" loading="lazy" @error="broken = true" />
      <!-- 封面挂了或者根本没有封面时不摆一个碎图标: 用番剧名首字顶上, 至少还认得出是哪部 -->
      <span v-if="!anime.cover || broken" class="anime-chip-fallback">{{ title.charAt(0) }}</span>
    </div>

    <div class="anime-chip-body">
      <div class="anime-chip-title">{{ title }}</div>
      <div v-if="subtitle" class="anime-chip-sub">{{ subtitle }}</div>
      <div v-if="anime.summary" class="anime-chip-summary">{{ anime.summary }}</div>
      <div class="anime-chip-meta">
        <span v-if="rating" class="anime-chip-rating">
          <PhStar :size="11" weight="fill" />{{ rating }}
        </span>
        <!-- 追番人数只在运营榜里有, 集数在用户侧的结果里有: 两者都是「一眼能判断要不要点进去」的信息 -->
        <span v-if="anime.trackingCount" class="anime-chip-extra">{{ anime.trackingCount }} 人追</span>
        <span v-else-if="anime.episodes" class="anime-chip-extra">共 {{ anime.episodes }} 集</span>
        <span v-if="anime.date" class="anime-chip-date">{{ anime.date }}</span>
      </div>
    </div>
  </router-link>
</template>

<script setup>
import { computed, ref } from 'vue'
import PhStar from '@icons/PhStar.vue.mjs'

const props = defineProps({
  anime: { type: Object, required: true },
})

// 图片挂了就换成文字底: 演示给别人看的时候, 一排破图比没有图更糟
const broken = ref(false)

const title = computed(() => props.anime.nameCn || props.anime.name || '未知番剧')
// 中文名当标题时, 把原名放在下一行 —— 用户可能就是照着原名搜过来的
const subtitle = computed(() =>
  props.anime.nameCn && props.anime.name && props.anime.nameCn !== props.anime.name
    ? props.anime.name
    : ''
)

// 分数可能是数字也可能是字符串 (工具结果是通用 Map, 没有类型约束), 统一成一位小数.
// 为 0 或缺失时干脆不显示 —— 「0.0 分」看着像数据出错
const rating = computed(() => {
  const n = Number(props.anime.rating)
  return Number.isFinite(n) && n > 0 ? n.toFixed(1) : ''
})
</script>
