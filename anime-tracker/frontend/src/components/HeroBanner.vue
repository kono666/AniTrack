<template>
  <div class="hero" v-if="items.length > 0">
    <div class="hero-track" :style="{ transform: `translateX(-${current * 100}%)` }">
      <div
        v-for="(item, i) in items"
        :key="item.id"
        class="hero-slide"
        :class="{ active: i === current }"
      >
        <div class="hero-bg">
          <img
            v-if="item.images?.large"
            :src="item.images.large"
            :alt="item.nameCn"
            class="hero-bg-img"
            @error="e => e.target.style.display = 'none'"
          />
          <div class="hero-gradient"></div>
        </div>
        <div class="hero-content">
          <div class="hero-text">
            <div class="hero-badge">🔥 热门推荐</div>
            <h1 class="hero-title">{{ item.nameCn || item.name }}</h1>
            <p class="hero-subtitle" v-if="item.name && item.nameCn !== item.name">{{ item.name }}</p>
            <div class="hero-meta">
              <span v-if="item.rating?.score" class="hero-score">⭐ {{ item.rating.score.toFixed(1) }}</span>
              <span v-if="item.totalEpisodes" class="hero-eps">📺 {{ item.totalEpisodes }}集</span>
              <span v-if="item.date" class="hero-year">📅 {{ item.date.substring(0, 4) }}</span>
            </div>
            <div class="hero-tags" v-if="item.tags?.length">
              <span v-for="tag in item.tags.slice(0, 3)" :key="tag.name" class="hero-tag">{{ tag.name }}</span>
            </div>
            <button class="hero-btn" @click="$router.push(`/anime/${item.id}`)">查看详情 →</button>
          </div>
          <div class="hero-cover-wrap">
            <img
              class="hero-cover"
              :src="item.images?.large || item.images?.common || fallbackImg"
              :alt="item.nameCn"
              @error="e => e.target.src = fallbackImg"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Dots -->
    <div class="hero-dots" v-if="items.length > 1">
      <button
        v-for="(_, i) in items"
        :key="i"
        class="hero-dot"
        :class="{ active: i === current }"
        @click="goTo(i)"
      ></button>
    </div>

    <!-- Arrows -->
    <button v-if="items.length > 1" class="hero-arrow hero-arrow-left" @click="prev">‹</button>
    <button v-if="items.length > 1" class="hero-arrow hero-arrow-right" @click="next">›</button>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  items: { type: Array, default: () => [] }
})

const $router = useRouter()
const current = ref(0)
let timer = null
const fallbackImg = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#18181b"><rect width="300" height="400"/><text x="150" y="200" text-anchor="middle" fill="#3f3f46" font-size="16">No Cover</text></svg>')

function next() { current.value = (current.value + 1) % props.items.length }
function prev() { current.value = (current.value - 1 + props.items.length) % props.items.length }
function goTo(i) { current.value = i; resetTimer() }
function resetTimer() {
  clearInterval(timer)
  timer = setInterval(next, 5000)
}

onMounted(() => { if (props.items.length > 1) resetTimer() })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.hero {
  position: relative;
  width: 100%;
  height: 420px;
  overflow: hidden;
  border-radius: 0 0 24px 24px;
  background: #0c0418;
}
.hero-track {
  display: flex;
  height: 100%;
  transition: transform .5s cubic-bezier(.4,0,.2,1);
}
.hero-slide {
  min-width: 100%;
  height: 100%;
  position: relative;
}

/* Background */
.hero-bg { position: absolute; inset: 0; }
.hero-bg-img {
  width: 100%; height: 100%; object-fit: cover;
  filter: blur(8px) brightness(.35);
  transform: scale(1.1);
}
.hero-gradient {
  position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(12,4,24,.9) 0%, rgba(26,16,64,.7) 50%, rgba(20,8,36,.85) 100%);
}

/* Content */
.hero-content {
  position: relative; z-index: 2;
  display: flex; align-items: center; gap: 48px;
  max-width: 1100px; margin: 0 auto; padding: 40px 32px;
  height: 100%;
}
.hero-text { flex: 1; min-width: 0; }
.hero-badge {
  display: inline-block;
  font-size: 12px; font-weight: 700;
  padding: 4px 12px; border-radius: 20px;
  background: rgba(168,85,247,.2);
  color: #c084fc;
  margin-bottom: 16px;
  letter-spacing: .5px;
}
.hero-title {
  font-size: 36px; font-weight: 900;
  color: #fff; line-height: 1.2;
  margin-bottom: 4px;
  text-shadow: 0 2px 12px rgba(0,0,0,.5);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.hero-subtitle {
  font-size: 14px; color: rgba(255,255,255,.45);
  margin-bottom: 16px;
}
.hero-meta { display: flex; gap: 18px; margin-bottom: 14px; font-size: 13px; color: rgba(255,255,255,.7); }
.hero-score { color: var(--star); font-weight: 700; }
.hero-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 22px; }
.hero-tag {
  padding: 4px 12px; border-radius: 14px;
  background: rgba(255,255,255,.08); color: rgba(255,255,255,.65);
  font-size: 11px; border: 1px solid rgba(255,255,255,.08);
}
.hero-btn {
  padding: 12px 32px; border-radius: 24px;
  background: var(--primary); color: #fff; border: none;
  font-size: 15px; font-weight: 700; cursor: pointer;
  transition: all var(--transition);
}
.hero-btn:hover { background: var(--primary-hover); transform: translateY(-1px); box-shadow: 0 4px 20px rgba(168,85,247,.4); }

/* Cover */
.hero-cover-wrap { flex-shrink: 0; }
.hero-cover {
  width: 200px; border-radius: var(--radius);
  aspect-ratio: 3/4; object-fit: cover;
  box-shadow: 0 12px 48px rgba(0,0,0,.6);
  border: 2px solid rgba(255,255,255,.1);
}

/* Dots */
.hero-dots {
  position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%);
  display: flex; gap: 10px; z-index: 3;
}
.hero-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: rgba(255,255,255,.3); border: none; cursor: pointer;
  transition: all var(--transition);
}
.hero-dot.active { background: var(--primary); transform: scale(1.3); }

/* Arrows */
.hero-arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  z-index: 3; width: 42px; height: 42px; border-radius: 50%;
  background: rgba(0,0,0,.4); border: 1px solid rgba(255,255,255,.15);
  color: #fff; font-size: 22px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--transition);
  backdrop-filter: blur(8px);
}
.hero-arrow:hover { background: rgba(168,85,247,.3); border-color: rgba(168,85,247,.5); }
.hero-arrow-left { left: 16px; }
.hero-arrow-right { right: 16px; }

@media (max-width: 768px) {
  .hero { height: 340px; }
  .hero-content { gap: 24px; padding: 24px 16px; }
  .hero-title { font-size: 24px; }
  .hero-cover { width: 130px; }
  .hero-arrow { width: 34px; height: 34px; font-size: 18px; }
}
@media (max-width: 480px) {
  .hero { height: 280px; }
  .hero-content { gap: 16px; padding: 16px; }
  .hero-title { font-size: 20px; }
  .hero-cover { width: 100px; }
  .hero-meta { gap: 10px; font-size: 11px; }
  .hero-btn { padding: 8px 20px; font-size: 13px; }
}
</style>
