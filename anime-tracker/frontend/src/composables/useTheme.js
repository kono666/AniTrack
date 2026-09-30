import { ref } from 'vue'

/**
 * 站点明暗主题.
 *
 * 改前这套判断**长在 NavBar.vue 的 <script setup> 里**, 而切换入口只有导航栏
 * 那个登录后的下拉 —— 后台不渲染 NavBar 之后, 管理员就既切不了主题也退不了登录.
 * 所以两件事一起搬到侧栏, 主题这部分抽出来共用(退出登录本来就在 store 上).
 *
 * 为什么是抽出来而不是在侧栏里再抄一份: 「初始值怎么定」这段逻辑本来就已经
 * **有三处**了 —— index.html 的首屏内联脚本、NavBar 的 initialLight、以及
 * THEME_COLORS 与 tokens.css 的 --bg 对齐. 三处的注释都写着「改一处要改两处」,
 * 第四份就是它开始漂的起点.
 *
 * index.html 那条**不在这里**, 也搬不过来: 它必须在首屏样式之前同步跑完,
 * 否则存了浅色主题的人每次加载都会先闪一下深色. 这条是唯一合理的例外.
 */

/** localStorage 里的键名. index.html 那段内联脚本按字面量读同一个键 */
export const THEME_STORAGE_KEY = 'theme'

/**
 * 地址栏颜色.
 *
 * 它只认 <meta name="theme-color">, 拿不到 CSS 变量 —— 所以这份色值**必须**
 * 在 JS 里存在一份, 与 tokens.css 的 --bg 以及 index.html 内联脚本里的那份
 * 三处对齐. 改主题底色时这三处要一起改.
 */
const THEME_COLORS = { light: '#fcf1f0', dark: '#0d0c0b' }

/**
 * 当前是否浅色. 模块作用域 → 全应用一份, 侧栏与导航栏看到的是同一个值.
 *
 * ⚠️ **惰性初始化, 不是 `ref(initialLight())` 写在模块顶层.** 下面 initialLight()
 * 会调 window.matchMedia, 而在 jsdom 里那是 undefined; NavBar.test.js 又是在
 * beforeEach 里才打桩的(即**模块导入之后**). 写成模块顶层求值的话, 每一个
 * 间接 import 到 NavBar 的测试文件都会在 import 阶段就抛 —— 报出来的还是一句
 * 与真实原因毫无关系的 "matchMedia is not a function".
 */
let light = null

/** 读存储. 包 try: 隐私模式/禁用存储下 localStorage 会直接抛, 不该让导航栏挂掉 */
function storedTheme() {
  try {
    return localStorage.getItem(THEME_STORAGE_KEY)
  } catch (e) {
    return null
  }
}

/**
 * 初始值.
 *
 * 「没存过」和「存了 dark」是**两件事**: localStorage 里没有记录时应该跟系统走,
 * 而不是替用户选深色(改前正是当成一件事, 默认深色).
 *
 * body 上的 light 类**不在这里加** —— 那是首屏之后才发生的事, 存浅色主题时会闪
 * 一下. 首屏那一次由 index.html 的内联脚本负责; 这里只读初始值, 给按钮状态用.
 */
function initialLight() {
  const stored = storedTheme()
  if (stored) return stored === 'light'
  return window.matchMedia('(prefers-color-scheme: light)').matches
}

export function useTheme() {
  if (light === null) light = ref(initialLight())
  const state = light

  function toggle() {
    state.value = !state.value
    document.body.classList.toggle('light', state.value)
    // 写也包 try: 存储被禁用时 getItem 会抛, setItem 一样会 ——
    // 改前这一行是裸的, 隐私模式下点一下主题按钮就会抛出去
    const meta = document.querySelector('meta[name="theme-color"]')
    if (meta) meta.setAttribute('content', state.value ? THEME_COLORS.light : THEME_COLORS.dark)
    try {
      localStorage.setItem(THEME_STORAGE_KEY, state.value ? 'light' : 'dark')
    } catch (e) {
      /* 存储不可用: 本次会话仍然切换, 只是记不住 */
    }
  }

  return { light: state, toggle }
}
