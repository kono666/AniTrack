/**
 * useCountUp — 数字递增动画
 *
 * Usage:
 *   const { display } = useCountUp(targetValue, { duration: 1500 })
 *   <span>{{ display }}</span>
 */

import { ref, watch, onUnmounted } from 'vue'

export function useCountUp(targetRef, options = {}) {
  const { duration = 1200, startVal = 0 } = options
  const display = ref(startVal)
  let rafId = null
  let startTime = null

  function animate(timestamp) {
    if (!startTime) startTime = timestamp
    const elapsed = timestamp - startTime
    const progress = Math.min(elapsed / duration, 1)
    // easeOutCubic
    const eased = 1 - Math.pow(1 - progress, 3)
    const val = typeof targetRef === 'function' ? targetRef() : (targetRef?.value ?? targetRef)
    display.value = Math.round(startVal + (val - startVal) * eased)

    if (progress < 1) {
      rafId = requestAnimationFrame(animate)
    }
  }

  function start() {
    if (rafId) cancelAnimationFrame(rafId)
    startTime = null
    const val = typeof targetRef === 'function' ? targetRef() : (targetRef?.value ?? targetRef)
    if (val > 0) {
      rafId = requestAnimationFrame(animate)
    }
  }

  // Auto-start when target becomes > 0
  watch(
    () => (typeof targetRef === 'function' ? targetRef() : targetRef?.value ?? targetRef),
    (newVal) => { if (newVal > 0) start() },
    { immediate: true }
  )

  onUnmounted(() => { if (rafId) cancelAnimationFrame(rafId) })

  return { display, start }
}
