/**
 * useReveal — IntersectionObserver 滚动入场动画
 *
 * Usage:
 *   <div v-reveal>content</div>
 *   <div v-reveal="{ delay: 200 }">staggered</div>
 *
 * CSS:
 *   [data-reveal] { opacity: 0; transform: translateY(24px); transition: all .5s ease; }
 *   [data-reveal].revealed { opacity: 1; transform: translateY(0); }
 */

import { onMounted, onUnmounted } from 'vue'

const observers = new Map()

function getObserver(root = null) {
  const key = root || '__default__'
  if (!observers.has(key)) {
    const obs = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            const el = entry.target
            const delay = parseInt(el.dataset.revealDelay || '0')
            if (delay > 0) {
              setTimeout(() => el.classList.add('revealed'), delay)
            } else {
              el.classList.add('revealed')
            }
            obs.unobserve(el)
          }
        })
      },
      { threshold: 0.1, rootMargin: '0px 0px -40px 0px' }
    )
    observers.set(key, obs)
  }
  return observers.get(key)
}

// Directive
export const vReveal = {
  mounted(el, binding) {
    el.setAttribute('data-reveal', '')
    if (binding.value?.delay) {
      el.dataset.revealDelay = binding.value.delay
    }
    getObserver().observe(el)
  },
  unmounted(el) {
    getObserver().unobserve(el)
  },
}

// Composable for manual use
export function useReveal() {
  onMounted(() => {
    // Ensure base styles exist
    if (!document.getElementById('reveal-styles')) {
      const style = document.createElement('style')
      style.id = 'reveal-styles'
      style.textContent = `
        [data-reveal] {
          opacity: 0;
          transform: translateY(24px);
          transition: opacity .55s cubic-bezier(.4,0,.2,1), transform .55s cubic-bezier(.4,0,.2,1);
        }
        [data-reveal].revealed {
          opacity: 1;
          transform: translateY(0);
        }
      `
      document.head.appendChild(style)
    }
  })
  return { vReveal }
}
