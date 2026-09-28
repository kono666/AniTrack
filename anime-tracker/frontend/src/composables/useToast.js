import { ref } from 'vue'

/**
 * 全局提示(toast)的状态与操作.
 *
 * 改前这些东西长在 Toast.vue 的 <script setup> 里, 并且靠一句
 * `window.$toast = show` 把它挂到全局, 调用方写的是裸的 `$toast(...)`.
 * 那样有三个问题:
 *
 *   1. 调用方和 Toast.vue 的加载顺序耦合 —— 声明它的那个组件还没被求值时,
 *      window.$toast 是 undefined, 报的是一个和真实原因毫无关系的
 *      ReferenceError, 而且只在"恰好没渲染过"的路径上出现;
 *   2. 裸标识符对打包器和静态检查都是"不存在的变量", 改名不会有人提醒,
 *      写错了也不会有人拦;
 *   3. 写单测时必须先手动往 window 上塞一个假函数, 每个测试文件各塞一遍.
 *
 * 现在状态放在模块作用域(整个应用共用一份), 谁要用谁 import 进来,
 * Toast.vue 退化成一个只负责渲染的组件.
 */

/** 显示中的提示. 模块作用域 → 全应用一份, 多个调用点看到的是同一个列表. */
const items = ref([])
let nextId = 0
/** id -> timer. 提前关掉时需要把定时器一起清掉, 否则它到点还会再去删一次. */
const timers = new Map()

/**
 * @param {string} message  提示正文
 * @param {'success'|'error'|'info'|'warning'} type
 * @param {number} duration 毫秒; 传 0 表示不自动消失
 * @param {{label:string, handler:Function}|null} action 可选的行动按钮
 */
export function showToast(message, type = 'info', duration = 3500, action = null) {
  const id = nextId++
  items.value.push({ id, message, type, action })
  if (duration > 0) {
    timers.set(id, setTimeout(() => removeToast(id), duration))
  }
}

export function removeToast(id) {
  items.value = items.value.filter(i => i.id !== id)
  const timer = timers.get(id)
  if (timer) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

/** 供组件使用. 返回的是同一份状态, 不是每个组件各一份. */
export function useToast() {
  return { items, show: showToast, remove: removeToast }
}
