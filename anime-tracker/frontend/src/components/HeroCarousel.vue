<template>
  <!--
    首页首屏「清晰大图」轮播。

    数据来自 data/heroShelf.js（12 部，站主推荐 + 热度推荐各 6），不是接口：
    宽幅 key art 不在 Bangumi 数据里，是单独下载到 public/hero/ 的 12 张图。
    图片素材来自 TMDB，仅用于非商业的课程/毕设展示。

    桌面端：整幅 banner + 左下文字。
    手机端（≤560px）：宽幅图在竖屏只能裁出中间一小条，改用竖版封面，
      600×863 的原生尺寸，高 DPI 屏上也清晰。
  -->
  <section
    ref="heroEl"
    class="hero"
    role="region"
    aria-roledescription="carousel"
    aria-label="编辑推荐"
    @mouseenter="hovering = true; sync()"
    @mouseleave="hovering = false; sync()"
    @focusin="focusing = true; sync()"
    @focusout="onFocusOut"
    @keydown="onKeydown"
  >
    <article
      v-for="(item, i) in items"
      :key="item.id"
      class="slide"
      :class="{ active: i === current }"
      :aria-hidden="i === current ? 'false' : 'true'"
    >
      <img
        class="banner"
        :src="item.banner"
        alt=""
        :loading="i === 0 ? 'eager' : 'lazy'"
      />
      <div class="scrim"></div>

      <div class="inner">
        <div class="poster">
          <img
            :src="item.cover"
            :alt="`${item.title} 封面`"
            :loading="i === 0 ? 'eager' : 'lazy'"
            @error="onCoverError"
          />
        </div>

        <div class="copy">
          <span class="eyebrow">
            {{ item.group }} · No.{{ String((i % groupSize) + 1).padStart(2, '0') }}
          </span>
          <h1 class="title" :class="titleClass(item.title)">{{ item.title }}</h1>
          <p class="original">{{ item.original }}</p>
          <div class="meta">
            <span class="score">★ {{ item.score }}</span>
            <span>{{ item.year }}</span>
            <span>{{ item.tags.join(' / ') }}</span>
          </div>
          <div class="actions">
            <router-link class="btn" :to="`/anime/${item.id}`">查看详情 →</router-link>
            <button class="btn btn-ghost" type="button" @click="addTracking(item)">
              加入追番
            </button>
          </div>
        </div>
      </div>
    </article>

    <button class="arrow prev" type="button" aria-label="上一部" @click="go(current - 1)">
      ‹
    </button>
    <button class="arrow next" type="button" aria-label="下一部" @click="go(current + 1)">
      ›
    </button>

    <div class="dots">
      <button
        v-for="(item, i) in items"
        :key="`dot-${item.id}`"
        class="dot"
        :class="{ active: i === current }"
        type="button"
        :aria-label="`第 ${i + 1} 部：${item.title}`"
        @click="go(i)"
      ></button>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useToast } from '../composables/useToast'
import { saveTracking } from '../api'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'
import { HERO_SHELF, HERO_GROUP_SIZE } from '../data/heroShelf'

const INTERVAL_MS = 6000

const items = HERO_SHELF
const groupSize = HERO_GROUP_SIZE

const current = ref(0)
const heroEl = ref(null)
const hovering = ref(false)
const focusing = ref(false)

const { show: toast } = useToast()

/*
 * 自动轮播。
 *
 * 两个必须守住的点：
 *   1. 计时器幂等 —— 改前在原型里 start() 不检查已有计时器，而 focusin/focusout
 *      会冒泡，在轮播里 Tab 换元素时不断叠加 setInterval，表现为「有时 6 秒一换、
 *      有时 2 秒一换」。实测（采样 20s）：修复后间隔稳定 6.18 / 6.97s。
 *   2. 暂停条件统一 —— 鼠标在其中「或」焦点在其中就停：只看其中一个的话，
 *      键盘用户在轮播里操作时画面照样会自己跳走。
 */
let timer = null

function reducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)').matches
    : false
}

function start() {
  if (timer || reducedMotion()) return
  if (typeof document !== 'undefined' && document.hidden) return
  timer = setInterval(() => {
    current.value = (current.value + 1) % items.length
  }, INTERVAL_MS)
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function sync() {
  if (hovering.value || focusing.value) stop()
  else start()
}

/** 手动切换：重置计时，让新一屏完整停留 6 秒 */
function go(i) {
  current.value = (i + items.length) % items.length
  stop()
  sync()
}

function onFocusOut(e) {
  // 焦点仍在轮播内部移动时（relatedTarget 还在 hero 里）不恢复计时
  if (heroEl.value && heroEl.value.contains(e.relatedTarget)) return
  focusing.value = false
  sync()
}

function onKeydown(e) {
  if (e.key === 'ArrowLeft') { e.preventDefault(); go(current.value - 1) }
  if (e.key === 'ArrowRight') { e.preventDefault(); go(current.value + 1) }
}

/** 图片代理挂了/上游超时时退到占位图，别让手机端只剩一行 alt 文字 */
function onCoverError(e) {
  if (e?.target && e.target.src !== fallbackImg) e.target.src = fallbackImg
}

function titleClass(title) {
  const n = title.length
  return n >= 16 ? 'xlong' : n >= 10 ? 'long' : ''
}

async function addTracking(item) {
  try {
    await saveTracking({ subjectId: item.id, status: 'watching' })
    toast('已追番', 'success')
  } catch (e) {
    toast(e?.response?.data?.message || '追番失败，请先登录', 'error')
  }
}

function onVisibilityChange() {
  if (typeof document !== 'undefined' && document.hidden) stop()
  else sync()
}

onMounted(() => {
  sync()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.hero {
  position: relative;
  /*
   * 高度完全跟着图片的 16:9 走，不做裁切 —— 图片是什么比例，首屏就是什么比例。
   * **不设 max-height**：设了上限，矮屏（例如 1366×768 的笔记本）上容器就会比
   * 16:9 宽，要么裁图、要么两边留黑边。宁可让首屏比视口高一点、用户往下滚，
   * 也不让图片缺一块。
   */
  aspect-ratio: 16 / 9;
  overflow: hidden;
  background: #000;
  isolation: isolate;
}

.slide {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.7s ease;
}
.slide.active {
  opacity: 1;
  pointer-events: auto;
}

.banner {
  width: 100%;
  height: 100%;
  /*
   * contain 而不是 cover：这张图是首屏的主角，**一张都不许裁**。
   * 容器本身是 16:9，正常宽度下与图片比例一致，看不出区别；
   * 只有在「矮屏 + max-height 生效」时（例如 1366×768 的笔记本），
   * 容器会比 16:9 更宽，这时两侧留黑边——这也是有意的：黑边比切掉人物好。
   */
  object-fit: contain;
  object-position: center 40%;
  display: block;
  background: #0d0c0b;
}

/* 只在文字一侧压暗，图片主体保持原样 */
.scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(0deg, rgba(13, 12, 11, 0.95) 0%, rgba(13, 12, 11, 0.62) 30%, rgba(13, 12, 11, 0.14) 56%, rgba(13, 12, 11, 0) 74%),
    linear-gradient(90deg, rgba(13, 12, 11, 0.88) 0%, rgba(13, 12, 11, 0.45) 42%, rgba(13, 12, 11, 0) 68%);
}

.inner {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 32px 38px;
  display: flex;
  align-items: flex-end;
  gap: 28px;
}
.copy {
  min-width: 0;
}

/* 手机专用：竖版封面 */
.poster {
  display: none;
}

.eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  color: #e6a184;
  text-transform: uppercase;
  margin-bottom: 10px;
}
.eyebrow::before {
  content: '';
  width: 24px;
  height: 1px;
  background: #a8583f;
}

.title {
  font-family: var(--font-display);
  font-weight: 800;
  font-size: clamp(40px, 4.6vw, 72px);
  line-height: 0.98;
  letter-spacing: 0.005em;
  color: #f5e7e6;
  text-shadow: 0 2px 24px rgba(0, 0, 0, 0.45);
}
.title.long {
  font-size: clamp(30px, 3.4vw, 52px);
}
.title.xlong {
  font-size: clamp(24px, 2.7vw, 40px);
  line-height: 1.08;
}

.original {
  margin-top: 8px;
  font-size: clamp(13px, 1.05vw, 15px);
  color: rgba(245, 231, 230, 0.66);
  letter-spacing: 0.02em;
}

.meta {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 14px;
  font-size: 13.5px;
  color: rgba(245, 231, 230, 0.82);
}
/*
 * 首屏是「图上的深色版面」，两套主题下都长得一样 —— 所以这里的颜色一律写死，
 * 不吃 var(--primary) / var(--bg) 这类会随 body.light 变化的 token。
 * 改前按钮吃 var(--primary)：浅色主题下它是近黑色，压在深色图上直接看不见。
 */
.score {
  color: #e0b95e;
  font-weight: 700;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #e0d0ce;
  color: #141212;
  font-weight: 600;
  font-size: 14px;
  padding: 11px 20px;
  border-radius: 10px;
  border: 1px solid transparent;
  text-decoration: none;
  transition: transform 0.15s ease, background 0.2s ease;
}
.btn:hover {
  transform: translateY(-1px);
  background: #f5e7e6;
}
.btn:active {
  transform: translateY(0) scale(0.99);
}
.btn-ghost {
  background: rgba(13, 12, 11, 0.35);
  color: #f5e7e6;
  border-color: rgba(245, 231, 230, 0.28);
  backdrop-filter: blur(4px);
}
.btn-ghost:hover {
  background: rgba(255, 255, 255, 0.1);
}

.dots {
  position: absolute;
  right: 32px;
  bottom: 40px;
  z-index: 3;
  display: flex;
  gap: 8px;
}
.dot {
  width: 18px;
  height: 4px;
  border-radius: 2px;
  border: 0;
  padding: 0;
  background: rgba(245, 231, 230, 0.35);
  transition: background 0.25s;
  cursor: pointer;
}
.dot.active {
  background: var(--primary);
}
/* 前 6 个是站主推荐、后 6 个热度推荐，中间留一道空隙 */
.dot:nth-child(7) {
  margin-left: 10px;
}

.arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 3;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(245, 231, 230, 0.22);
  background: rgba(13, 12, 11, 0.35);
  color: #f5e7e6;
  font-size: 24px;
  line-height: 1;
  display: grid;
  place-items: center;
  opacity: 0.5;
  transition: opacity 0.2s, background 0.2s;
  backdrop-filter: blur(4px);
  cursor: pointer;
}
.hero:hover .arrow,
.arrow:focus-visible {
  opacity: 1;
}
.arrow:hover {
  background: rgba(255, 255, 255, 0.12);
}
.arrow.prev {
  left: 18px;
}
.arrow.next {
  right: 18px;
}

@media (max-width: 900px) {
  .title {
    font-size: clamp(30px, 5vw, 46px);
  }
  .dots {
    right: 24px;
    bottom: 26px;
  }
}

@media (max-width: 560px) {
  /* 手机：宽幅 banner 在竖屏只能裁出中间一条，改用竖版封面（600×863 原生尺寸） */
  .hero {
    aspect-ratio: auto;
    height: 76vh;
    min-height: 560px;
  }
  .banner {
    display: none;
  }
  /* 手机端没有图片可压，用一块固定的深色底 —— 同样不吃主题 token：
     浅色主题下如果落到 var(--bg)（近白粉），浅色文字就整块糊掉了。 */
  .hero {
    background: #0d0c0b;
  }
  .scrim {
    background: radial-gradient(120% 80% at 50% 30%, rgba(60, 50, 48, 0.55) 0%, #0d0c0b 70%);
  }
  .inner {
    inset: 0;
    max-width: none;
    padding: 24px 24px 40px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    text-align: center;
    gap: 18px;
  }
  .poster {
    display: block;
    width: 150px;
    aspect-ratio: 600 / 863;
    border-radius: 10px;
    overflow: hidden;
    box-shadow: 0 20px 44px -16px rgba(0, 0, 0, 0.85), 0 0 0 1px rgba(255, 255, 255, 0.06);
  }
  .poster img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
  .title {
    font-size: 34px;
  }
  .title.long {
    font-size: 25px;
  }
  .title.xlong {
    font-size: 20px;
    line-height: 1.2;
  }
  .original {
    margin-top: 4px;
  }
  .meta {
    gap: 12px;
    font-size: 12.5px;
    justify-content: center;
  }
  .actions {
    margin-top: 6px;
    justify-content: center;
  }
  .btn {
    padding: 10px 16px;
    font-size: 13px;
  }
  .arrow {
    display: none;
  }
  .dots {
    left: 50%;
    right: auto;
    transform: translateX(-50%);
    bottom: 18px;
  }
}
</style>
