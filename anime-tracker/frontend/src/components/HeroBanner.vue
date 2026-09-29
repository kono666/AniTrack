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
            <div class="hero-badge">热门推荐</div>
            <h1 class="hero-title">{{ item.nameCn || item.name }}</h1>
            <p class="hero-subtitle" v-if="item.name && item.nameCn !== item.name">{{ item.name }}</p>
            <div class="hero-meta">
              <span v-if="item.rating?.score" class="hero-score"><PhStar :size="12" weight="fill" /> {{ item.rating.score.toFixed(1) }}</span>
              <span v-if="item.totalEpisodes" class="hero-eps">{{ item.totalEpisodes }}集</span>
              <span v-if="item.date" class="hero-year">{{ item.date.substring(0, 4) }}</span>
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
      <!-- 圆点只有 10px、里面一个字都没有, 读屏软件原本读到的是一串「按钮」.
           aria-label 说明这是第几张, aria-current 说明现在停在哪一张 ——
           后者也顺便让「哪个点是亮的」这件事不只靠颜色表达 -->
      <button
        v-for="(item, i) in items"
        :key="i"
        class="hero-dot"
        :class="{ active: i === current }"
        :aria-label="`第 ${i + 1} 张: ${item.nameCn || item.name}`"
        :aria-current="i === current ? 'true' : undefined"
        @click="goTo(i)"
      ></button>
    </div>

    <!-- Arrows -->
    <button v-if="items.length > 1" class="hero-arrow hero-arrow-left" aria-label="上一张" @click="jump(-1)">‹</button>
    <button v-if="items.length > 1" class="hero-arrow hero-arrow-right" aria-label="下一张" @click="jump(1)">›</button>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { PhStar } from '@phosphor-icons/vue'
import { useRouter } from 'vue-router'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'

const props = defineProps({
  items: { type: Array, default: () => [] }
})

const $router = useRouter()
const current = ref(0)
let timer = null
const INTERVAL_MS = 5000

function stepBy(delta) {
  // 空数组要挡掉: 0 条的时候 (0+1)%0 是 NaN, 会写进 transform 变成
  // translateX(-NaN%). 平时按钮不渲染所以碰不到, 但 props 从有到无的过程中
  // 定时器还在跑, 这是唯一会走到这里的路径.
  if (props.items.length === 0) return
  current.value = (current.value + delta + props.items.length) % props.items.length
}
function next() { stepBy(1) }

/** 手动切换(箭头 / 圆点): 顺带把 5 秒的计时重新开始 ——
 *  用户刚点完, 不该在 0.2 秒之后又被自动切走 */
function jump(delta) { stepBy(delta); syncTimer() }
function goTo(i) { current.value = i; syncTimer() }

/** 用户在系统里开了「减少动效」就完全不自动播放(但手动左右切换仍然可用) */
function prefersReducedMotion() {
  // jsdom 没有 matchMedia, 可选调用兜住 —— 测试环境按「不减少动效」处理
  return window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches === true
}

/**
 * 自动轮播的总开关. 三种情况都不转:
 *   * 少于两条(没什么可轮的);
 *   * 标签页在后台 —— 改前不管这个: 切走之后定时器照跑, 回来时可能已经
 *     空转了几十次, 用户看到的是轮播"跳"到了某个位置, 而且白白占着 CPU;
 *   * 用户要求减少动效.
 *
 * 另外这个函数同时是「数据到位了再开始转」的那一处. 改前是 onMounted 里
 * 判断一次就完了, 而 Home.vue 是先用空数组挂上组件、数据回来了才通过 props
 * 传进来的 —— onMounted 那一刻 length 是 0, 于是定时器**从来没起来过**,
 * 首页那个轮播其实一直停在第一张(箭头按钮能点, 所以看着像"能动")。
 * 现在改成 watch items.length, 数据到了才开始转.
 */
function syncTimer() {
  clearInterval(timer)
  timer = null
  if (props.items.length < 2) return
  if (document.hidden) return
  if (prefersReducedMotion()) return
  timer = setInterval(next, INTERVAL_MS)
}

function onVisibilityChange() { syncTimer() }

watch(() => props.items.length, () => {
  // 数据换了(或条数变了): 回到第一张, 计时重新开始
  if (current.value >= props.items.length) current.value = 0
  syncTimer()
})

onMounted(() => {
  syncTimer()
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.hero {
  position: relative;
  width: 100%;
  height: 420px;
  overflow: hidden;
  border-radius: 0 0 24px 24px;
  background: var(--hero-bg);
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
/* 压在模糊封面上的幕布, 是「这一块永远深色」的第三处孤岛(另两处见 tokens.css).
   改前是紫→靛→紫(#0c0418/#1a1040/#140824), 上一版配色的残留 —— 底下那张封面被
   blur+brightness(.35) 处理过, 再盖一层紫, 整块轮播就泛着一层谁也没挑过的色.
   换成中性深色, 分量和明暗关系原样保留.
   这里的三个色值是 --hero-bg / --hero-bg-2 / --hero-bg 的**半透明版本**:
   幕布必须透, 否则底下的封面就白模糊了. CSS 变量带不进透明度, 所以只能写死 rgba,
   改 --hero-bg 时记得回来同步(同 fallbackImg.js 的那条注记). */
.hero-gradient {
  position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(18,15,15,.9) 0%, rgba(34,28,28,.7) 50%, rgba(18,15,15,.85) 100%);
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
  background: var(--hero-badge-bg);
  color: var(--hero-badge-fg);
  margin-bottom: 16px;
  letter-spacing: .5px;
}
.hero-title {
  font-size: 36px; font-weight: 900;
  color: var(--cover-fg); line-height: 1.2;
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
.hero-score { color: var(--cover-star); font-weight: 700; }
.hero-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 22px; }
.hero-tag {
  padding: 4px 12px; border-radius: 14px;
  background: rgba(255,255,255,.08); color: rgba(255,255,255,.65);
  font-size: 11px; border: 1px solid rgba(255,255,255,.08);
}
.hero-btn {
  padding: 12px 32px; border-radius: 24px;
  background: var(--primary); color: var(--primary-foreground); border: none;
  font-size: 15px; font-weight: 700; cursor: pointer;
  transition: all var(--transition);
}
.hero-btn:hover { background: var(--primary-hover); transform: translateY(-1px); box-shadow: 0 4px 20px var(--primary-glow); }

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
/* 改前用的是 --primary。轮播**刻意保持深色**(见 tokens.css 的深色孤岛),
   而 --primary 是跟着主题走的 —— 浅色主题下它变成近黑, 于是"当前是第几张"
   这个指示点在深色头图上几乎看不见。压在封面上的东西一律用 --cover-* */
.hero-dot.active { background: var(--cover-fg); transform: scale(1.3); }

/* Arrows */
.hero-arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  z-index: 3; width: 42px; height: 42px; border-radius: 50%;
  background: rgba(0,0,0,.4); border: 1px solid rgba(255,255,255,.15);
  color: var(--cover-fg); font-size: 22px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all var(--transition);
  backdrop-filter: blur(8px);
}
/* 悬停用白色加亮而不是换色相 —— 箭头压在封面图上, 色相会跟底下的图打架 */
.hero-arrow:hover { background: rgba(255,255,255,.22); border-color: rgba(255,255,255,.38); }
.hero-arrow-left { left: 16px; }
.hero-arrow-right { right: 16px; }

/* 用户在系统里开了「减少动效」: 自动播放已经在 JS 里停掉了(见 syncTimer),
   这里再去掉手动切换时的滑动动画 —— 尊重这个设置的完整含义是两件事都做 */
@media (prefers-reduced-motion: reduce) {
  .hero-track { transition: none; }
}

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
