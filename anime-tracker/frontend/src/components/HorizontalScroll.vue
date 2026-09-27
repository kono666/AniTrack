<template>
  <div class="hs-section">
    <div class="hs-header" v-if="title">
      <h2 class="hs-title">{{ title }}</h2>
      <router-link v-if="link" :to="link" class="hs-more">查看全部 →</router-link>
    </div>
    <div class="hs-wrap">
      <button
        v-if="showArrows"
        class="hs-arrow hs-arrow-left"
        :class="{ visible: canScrollLeft }"
        @click="scroll(-300)"
        aria-label="向左滚动"
      >‹</button>
      <div class="hs-track" ref="trackRef" @scroll="onScroll">
        <div class="hs-list">
          <slot />
        </div>
      </div>
      <button
        v-if="showArrows"
        class="hs-arrow hs-arrow-right"
        :class="{ visible: canScrollRight }"
        @click="scroll(300)"
        aria-label="向右滚动"
      >›</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

defineProps({
  title: { type: String, default: '' },
  link: { type: String, default: '' },
  showArrows: { type: Boolean, default: true },
})

const trackRef = ref(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(true)

function scroll(amount) {
  if (!trackRef.value) return
  trackRef.value.scrollBy({ left: amount, behavior: 'smooth' })
}

function onScroll() {
  if (!trackRef.value) return
  const t = trackRef.value
  canScrollLeft.value = t.scrollLeft > 10
  canScrollRight.value = t.scrollLeft < t.scrollWidth - t.clientWidth - 10
}

// Mouse drag
let dragging = false, startX = 0, startScroll = 0
function onMouseDown(e) {
  dragging = true; startX = e.pageX; startScroll = trackRef.value?.scrollLeft || 0
  if (trackRef.value) trackRef.value.style.cursor = 'grabbing'
}
function onMouseUp() { dragging = false; if (trackRef.value) trackRef.value.style.cursor = '' }
function onMouseMove(e) {
  if (!dragging || !trackRef.value) return
  trackRef.value.scrollLeft = startScroll - (e.pageX - startX)
}
function onMouseLeave() { dragging = false; if (trackRef.value) trackRef.value.style.cursor = '' }

onMounted(() => {
  trackRef.value?.addEventListener('mousedown', onMouseDown)
  window.addEventListener('mouseup', onMouseUp)
  window.addEventListener('mousemove', onMouseMove)
  trackRef.value?.addEventListener('mouseleave', onMouseLeave)
  onScroll()
})
onUnmounted(() => {
  window.removeEventListener('mouseup', onMouseUp)
  window.removeEventListener('mousemove', onMouseMove)
})
</script>

<style scoped>
.hs-section { margin-bottom: 36px; }
.hs-header {
  display: flex; align-items: baseline; justify-content: space-between;
  margin-bottom: 16px;
}
.hs-title { font-size: 20px; font-weight: 800; color: var(--text); display: flex; align-items: center; gap: 8px; }
.hs-more { font-size: 13px; color: var(--primary); font-weight: 600; transition: opacity var(--transition); }
.hs-more:hover { opacity: .8; }

.hs-wrap { position: relative; }
.hs-track {
  overflow-x: auto; overflow-y: hidden;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
  -ms-overflow-style: none;
  padding-bottom: 4px;
}
.hs-track::-webkit-scrollbar { display: none; }
.hs-list {
  display: flex; gap: 14px;
  width: max-content;
  min-width: 100%;
}

/* Arrows */
.hs-arrow {
  position: absolute; top: 50%; transform: translateY(-50%);
  z-index: 5;
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--card); border: 1px solid var(--card-border);
  color: var(--text); font-size: 20px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity var(--transition), box-shadow var(--transition);
  box-shadow: var(--shadow);
}
.hs-arrow.visible { opacity: 1; }
.hs-arrow:hover { box-shadow: var(--shadow-lg); }
.hs-arrow-left { left: -8px; }
.hs-arrow-right { right: -8px; }

@media (max-width: 768px) {
  .hs-arrow { display: none; }
  .hs-list { gap: 10px; }
}
</style>
