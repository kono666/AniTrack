<template>
  <!-- 整张卡片是一个链接式的目标. 改前只有 @click: 键盘用户 tab 不到它,
       读屏软件也不会说"这是个可以按的东西" —— 首页那一屏卡片对外就只剩
       每张图里的 alt 文字. 语义标签(<a>)在这里换不了: 卡片里是块级内容,
       而 <a> 包块级元素会牵动一整套布局. 所以补 role + tabindex + 回车/空格.
       回车和空格都要: role=button 的约定是两者都触发, 只写回车会让
       "按空格没反应"成为一个只有键盘用户才会遇到的小 bug. -->
  <div
    class="anime-card"
    role="button"
    tabindex="0"
    @click="goDetail"
    @keydown.enter.prevent="goDetail"
    @keydown.space.prevent="goDetail"
  >
    <div class="anime-card-img-wrap">
      <!-- Blur placeholder -->
      <div class="card-placeholder" v-if="!imgLoaded">
        <div class="placeholder-shimmer"></div>
      </div>
      <img
        class="anime-card-img"
        :class="{ loaded: imgLoaded }"
        :src="imgSrc"
        :alt="name"
        loading="lazy"
        @load="imgLoaded = true"
        @error="onImgError"
      />

      <!-- Type badge (top-left) -->
      <div v-if="typeLabel" class="card-type-badge" :class="typeClass">{{ typeLabel }}</div>

      <!-- Rating badge (top-right) -->
      <div class="card-rating-badge" :class="{ 'no-score': !hasScore }">
        <PhStar :size="11" weight="fill" v-if="hasScore" />
        {{ hasScore ? scoreDisplay : '暂无' }}
      </div>

      <!-- Hover overlay -->
      <div class="card-overlay">
        <div class="card-overlay-info">
          <span v-if="date" class="overlay-year">{{ date.substring(0, 4) }}</span>
          <span v-if="episodeCount" class="overlay-eps">{{ episodeCount }}集</span>
        </div>
        <div class="overlay-action">点击查看详情 →</div>
      </div>
    </div>

    <div class="anime-card-body">
      <div class="anime-card-title" :title="name">{{ name }}</div>
      <div class="anime-card-meta">
        <span v-if="date" class="meta-year">{{ date.substring(0, 4) }}</span>
        <span v-if="episodeCount" class="meta-dot">·</span>
        <span v-if="episodeCount" class="meta-eps">{{ episodeCount }}集</span>
        <span v-if="hasScore" class="meta-dot">·</span>
        <span v-if="hasScore" class="meta-score">⭐{{ scoreDisplay }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { PhStar } from '@phosphor-icons/vue'
import { COVER_FALLBACK_CARD as fallbackImg } from '../utils/fallbackImg'

const props = defineProps({ anime: Object })
const router = useRouter()
const imgLoaded = ref(false)
const imgFailed = ref(false)

const name = computed(() => props.anime.nameCn || props.anime.name || 'Unknown')
const imgSrc = computed(() => {
  if (imgFailed.value) return fallbackImg
  return props.anime.images?.large || props.anime.images?.common || props.anime.images?.medium || props.anime.animeCover || ''
})
const scoreDisplay = computed(() => {
  const r = props.anime.rating
  if (r?.score) return Number(r.score).toFixed(1)
  return '0'
})
const hasScore = computed(() => {
  const r = props.anime.rating
  return r?.score && Number(r.score) > 0
})
const date = computed(() => props.anime.date || '')
const episodeCount = computed(() => props.anime.totalEpisodes || props.anime.episodesCount || 0)

// Type detection
const typeLabel = computed(() => {
  const t = props.anime.type
  if (t === 'TV') return 'TV'
  if (t === 'Movie' || t === '剧场版') return '剧场版'
  if (t === 'OVA') return 'OVA'
  if (t === 'ONA' || t === 'WEB') return 'ONA'
  // Guess from episode count
  if (episodeCount.value === 1) return null // could be movie, but uncertain
  if (episodeCount.value > 0 && episodeCount.value <= 3) return null
  return null // Don't guess
})
const typeClass = computed(() => {
  if (typeLabel.value === 'TV') return 'type-tv'
  if (typeLabel.value === '剧场版') return 'type-movie'
  return 'type-other'
})

function onImgError() {
  imgFailed.value = true
  imgLoaded.value = true
}
function goDetail() { router.push(`/anime/${props.anime.id}`) }
</script>

<style scoped>
.anime-card {
  background: var(--card);
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  transition: transform .3s cubic-bezier(.4,0,.2,1), box-shadow .3s cubic-bezier(.4,0,.2,1), border-color .3s;
  box-shadow: var(--shadow-card);
  position: relative;
  border: 1.5px solid transparent;
}
.anime-card:hover {
  transform: translateY(-6px);
  box-shadow: 0 16px 48px rgba(0,0,0,.5), 0 0 0 1px var(--primary-line);
  border-color: var(--primary-line);
}

/* Image */
.anime-card-img-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 3/4;
  overflow: hidden;
  background: var(--bg-secondary);
}
.anime-card-img {
  width: 100%; height: 100%; object-fit: cover;
  transition: transform .6s cubic-bezier(.25,.46,.45,.94), opacity .4s;
  opacity: 0;
}
.anime-card-img.loaded { opacity: 1; }
.anime-card:hover .anime-card-img.loaded { transform: scale(1.08); }

/* Placeholder */
.card-placeholder {
  position: absolute; inset: 0; z-index: 1;
}
.placeholder-shimmer {
  width: 100%; height: 100%;
  background: linear-gradient(90deg, var(--bg-secondary) 25%, var(--card-border) 50%, var(--bg-secondary) 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

/* Type badge (top-left) */
.card-type-badge {
  position: absolute; top: 8px; left: 8px; z-index: 3;
  font-size: 10px; font-weight: 800; letter-spacing: .5px;
  padding: 3px 8px; border-radius: 4px;
  color: var(--cover-fg); backdrop-filter: blur(8px);
}
/* 类型标签压在封面图上, 所以这三个**不随主题变**(见 tokens.css 的注释):
   底下的图不认主题, 换一套更亮的底色只会让白字糊掉。
   三个 token 现在同值(一块黑纱), 类型由文字本身区分 —— 蓝/粉/紫三个彩色药丸
   并排出现时, 抢的是封面自己的戏。角标之间的进一步区分交给卡片改版那一步 */
.type-tv { background: var(--cover-tag-tv); }
.type-movie { background: var(--cover-tag-movie); }
.type-other { background: var(--cover-tag-other); }

/* Rating badge (top-right) */
.card-rating-badge {
  position: absolute; top: 8px; right: 8px; z-index: 3;
  display: flex; align-items: center; gap: 3px;
  padding: 4px 9px; border-radius: 6px;
  background: var(--cover-scrim); backdrop-filter: blur(8px);
  color: var(--cover-star); font-size: 12px; font-weight: 800;
}
.card-rating-badge.no-score {
  color: var(--text-muted); font-size: 10px; font-weight: 500;
}

/* Hover overlay */
.card-overlay {
  position: absolute; inset: 0;
  background: linear-gradient(to top, rgba(0,0,0,.88) 0%, rgba(0,0,0,.2) 50%, transparent 70%);
  opacity: 0; transition: opacity .3s;
  display: flex; flex-direction: column; justify-content: flex-end;
  padding: 12px; z-index: 2;
}
.anime-card:hover .card-overlay { opacity: 1; }
.card-overlay-info { display: flex; gap: 10px; margin-bottom: 6px; }
.overlay-year, .overlay-eps { font-size: 11px; color: rgba(255,255,255,.6); }
.overlay-action {
  font-size: 12px; color: var(--cover-fg); font-weight: 700;
  opacity: 0; transform: translateY(8px);
  transition: all .3s .05s;
}
.anime-card:hover .overlay-action { opacity: 1; transform: translateY(0); }

/* Body */
.anime-card-body { padding: 12px 14px 14px; }
.anime-card-title {
  font-size: 13px; font-weight: 600; line-height: 1.4;
  color: var(--text);
  display: -webkit-box;
  -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
  transition: color var(--transition);
}
/* 改前是悬停时标题变 --primary(紫)。--primary 现在是墨色, 跟标题已有的
   --text 是同一个色 —— 这条规则会变成空操作, 而"看着写了其实没效果"正是
   这次要清掉的东西。先换成下划线: 它表达的是"这里可以点进去", 不依赖色相。
   卡片真正的悬停语言(切角/描边/位移)放到卡片改版那一步统一做 */
.anime-card:hover .anime-card-title { text-decoration: underline; text-underline-offset: 3px; text-decoration-thickness: 1px; }
.anime-card-meta {
  font-size: 11px; color: var(--text-secondary);
  margin-top: 6px; display: flex; align-items: center; gap: 3px;
}
.meta-dot { color: var(--text-muted); margin: 0 2px; }
.meta-score { color: var(--star); font-weight: 700; }

/* 窄屏收一点内边距和字号.
   这两条原先写在 assets/css/anime-card.css 的媒体查询里, 但那个文件是在
   @layer components 里的, 而 scoped 样式不加 layer —— 层叠层里的规则无论
   媒体查询怎么写都输给不在层里的规则. 也就是说它们从来没有生效过, 只是看起来
   像是移动端适配. 现在按它原本的意图搬进 scoped, 才真的会用到. */
@media (max-width: 480px) {
  .anime-card-body { padding: 8px 10px 12px; }
  .anime-card-title { font-size: 11px; }
}
</style>
