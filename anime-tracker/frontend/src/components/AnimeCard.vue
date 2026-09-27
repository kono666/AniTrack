<template>
  <div class="anime-card" @click="goDetail">
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

const props = defineProps({ anime: Object })
const router = useRouter()
const imgLoaded = ref(false)
const imgFailed = ref(false)

const fallbackImg = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#1a1a2e"><rect width="300" height="400" rx="8"/><text x="150" y="195" text-anchor="middle" fill="#3f3f46" font-size="14">暂无封面</text><text x="150" y="215" text-anchor="middle" fill="#27272a" font-size="48">🎬</text></svg>')

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
  box-shadow: 0 16px 48px rgba(0,0,0,.5), 0 0 0 1px rgba(168,85,247,.3);
  border-color: rgba(168,85,247,.2);
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
  color: #fff; backdrop-filter: blur(8px);
}
.type-tv { background: rgba(59,130,246,.85); }
.type-movie { background: rgba(236,72,153,.85); }
.type-other { background: rgba(168,85,247,.85); }

/* Rating badge (top-right) */
.card-rating-badge {
  position: absolute; top: 8px; right: 8px; z-index: 3;
  display: flex; align-items: center; gap: 3px;
  padding: 4px 9px; border-radius: 6px;
  background: rgba(0,0,0,.75); backdrop-filter: blur(8px);
  color: var(--star); font-size: 12px; font-weight: 800;
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
  font-size: 12px; color: #fff; font-weight: 700;
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
.anime-card:hover .anime-card-title { color: var(--primary); }
.anime-card-meta {
  font-size: 11px; color: var(--text-secondary);
  margin-top: 6px; display: flex; align-items: center; gap: 3px;
}
.meta-dot { color: var(--text-muted); margin: 0 2px; }
.meta-score { color: var(--star); font-weight: 700; }
</style>
