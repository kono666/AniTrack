/**
 * vReveal — IntersectionObserver 滚动入场动画(指令)
 *
 * Usage:
 *   <div v-reveal>content</div>
 *   <div v-reveal="{ delay: 200 }">staggered</div>
 *
 * 为什么从 composables/useReveal.js 搬到这里
 * ------------------------------------------
 * 它**不是 composable**。composable 的约定是 `useXxx()`, 在组件 setup 里被调用、
 * 返回一组响应式状态; 而这里从头到尾只有一个指令对象, 调用方拿到的就是它。
 * 改前那个 `useReveal()` 包装层唯一做的事是 `onMounted` 里往 head 里注入一段
 * <style> —— 那段样式现在在 assets/css/interactions.css 里(那里写了为什么要搬),
 * 于是包装层也没了存在的理由。
 *
 * 样式**不在这个文件里**: `[data-reveal]` / `.revealed` 两条规则在
 * assets/css/interactions.css(@layer utilities)。改前它们是这里的一段模板字符串,
 * 运行期才注入 —— 不在任何层里(于是压过全站所有规则, 并且真的压坏了一处悬停效果),
 * 而且首屏之后才出现(元素先正常画出来再跳成透明)。
 *
 * 这个文件现在只负责一件事: 观察元素, 进入视口时把 `revealed` 类加上去。
 * 用法是在组件里 `import { vReveal } from '../directives/reveal'` —— 在
 * <script setup> 的顶层作用域里, vXxx 这个命名会被编译成可以写 `v-xxx` 的局部指令。
 */

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
