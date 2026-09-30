<template>
  <nav class="navbar" :class="{ 'nav-scrolled': scrolled }">
    <!-- Left: Brand -->
    <router-link to="/" class="navbar-brand" @click="closeMenu">
      <PhFilmSlate :size="22" weight="fill" class="brand-icon" aria-hidden="true" />
      <span class="brand-text">AniTrack</span>
    </router-link>

    <!-- Center: Search -->
    <div class="nav-search-wrap" :class="{ focused: searchFocused }">
      <PhMagnifyingGlass :size="16" weight="bold" class="nav-search-icon" />
      <input
        class="nav-search-input"
        v-model="searchQuery"
        placeholder="搜索番剧..."
        @keyup.enter="doSearch"
        @focus="searchFocused = true"
        @blur="searchFocused = false"
      />
    </div>

    <!-- Right: Nav links + User -->
    <div class="navbar-links">
      <!-- 这三个图标只在窄屏是"内容": ≤768px 时下面的 .nav-link-label 被隐藏,
           图标成了唯一能说明这一项是什么的东西, 所以它们不能按"标签前面的
           装饰"删掉。宽屏下它们确实只是装饰 —— 但同一个元素在两个断点下身份
           不同, 删了没法在窄屏补回来。
           AI 助手用 PhRobot: 和 ChatMessage 里助手头像用的是同一个图标。
           分类用 PhTag: 和 EmptyState 的 tag 空态用的是同一个图标 ——
           站内两处指同一件事时用同一个形状, 比换个更漂亮的火花重要。 -->
      <router-link to="/" class="nav-link" exact-active-class="nav-link--active" @click="closeMenu">
        <PhCompass :size="18" weight="bold" />
        <span class="nav-link-label">发现</span>
      </router-link>
      <!-- 分类原先挤在首页最底部, 现在搬成了独立页, 这里成了它唯一的入口。
           用 active-class 不用 exact-active-class: /tags 没有子路径, 与 AI 助手
           同一档; 「发现」那个 exact 是给 to="/"(所有路径的前缀)用的。 -->
      <router-link to="/tags" class="nav-link" active-class="nav-link--active" @click="closeMenu">
        <PhTag :size="18" weight="bold" />
        <span class="nav-link-label">分类</span>
      </router-link>
      <router-link to="/assistant" class="nav-link" active-class="nav-link--active" @click="closeMenu">
        <PhRobot :size="18" weight="bold" />
        <span class="nav-link-label">AI 助手</span>
      </router-link>
      <!-- User Dropdown -->
      <div v-if="userStore.loggedIn" class="nav-user-area">
        <button class="nav-user-btn" @click="toggleMenu">
          <PhUserCircle :size="22" weight="fill" class="nav-avatar" />
          <span class="nav-username">{{ userStore.user?.username }}</span>
          <PhCaretDown :size="12" weight="bold" class="nav-caret" :class="{ open: menuOpen }" />
        </button>

        <Teleport to="body">
          <div v-if="menuOpen" class="nav-dropdown-backdrop" @click="closeMenu"></div>
        </Teleport>
        <Transition name="dropdown">
          <div v-if="menuOpen" class="nav-dropdown">
            <div class="dropdown-header">
              <PhUserCircle :size="32" weight="fill" />
              <div>
                <div class="dropdown-name">{{ userStore.user?.username }}</div>
                <div class="dropdown-role">{{ userStore.user?.role === 'ADMIN' ? '管理员' : '用户' }}</div>
              </div>
            </div>
            <div class="dropdown-divider"></div>
            <router-link to="/profile" class="dropdown-item" @click="closeMenu">
              <PhUser :size="16" weight="bold" /> 个人主页
            </router-link>
            <router-link v-if="userStore.user?.role === 'ADMIN'" to="/admin" class="dropdown-item" @click="closeMenu">
              <PhGear :size="16" weight="bold" /> 管理后台
            </router-link>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item" @click="toggleLight">
              <PhSunHorizon v-if="!light" :size="16" weight="bold" />
              <PhMoonStars v-else :size="16" weight="bold" />
              {{ light ? '暗色模式' : '亮色模式' }}
            </button>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item dropdown-danger" @click="handleLogout">
              <PhSignOut :size="16" weight="bold" /> 退出登录
            </button>
          </div>
        </Transition>
      </div>

      <!-- Guest buttons -->
      <template v-else>
        <router-link to="/login" class="nav-btn" @click="closeMenu">登录</router-link>
        <router-link to="/register" class="nav-btn nav-btn-primary" @click="closeMenu">注册</router-link>
      </template>
    </div>
  </nav>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
/* 字重统一成两档: 身份标记(品牌标、用户头像)用 fill, 其余功能性图标一律 bold。
   模板里这 12 处图标改前混着 fill / bold / duotone 三种 —— duotone 在小尺寸下
   会把一个 16px 的图形切成两层灰, 是"图标语言不统一"最明显的一处。
   小尺寸用粗一档、大尺寸用细一档, 和 c71 定空态图标时(40px 用 light)是同一条规则。
   （原先这里还有个 PhSparkle 给 AI 助手用 —— 火花是 AI 产品的陈词滥调, 已换掉。）*/
import PhCompass from '@icons/PhCompass.vue.mjs'
import PhTag from '@icons/PhTag.vue.mjs'
import PhMagnifyingGlass from '@icons/PhMagnifyingGlass.vue.mjs'
import PhGear from '@icons/PhGear.vue.mjs'
import PhUserCircle from '@icons/PhUserCircle.vue.mjs'
import PhSunHorizon from '@icons/PhSunHorizon.vue.mjs'
import PhMoonStars from '@icons/PhMoonStars.vue.mjs'
import PhCaretDown from '@icons/PhCaretDown.vue.mjs'
import PhUser from '@icons/PhUser.vue.mjs'
import PhSignOut from '@icons/PhSignOut.vue.mjs'
import PhRobot from '@icons/PhRobot.vue.mjs'
import PhFilmSlate from '@icons/PhFilmSlate.vue.mjs'

const router = useRouter()
const userStore = useUserStore()

// Search
const searchQuery = ref('')
const searchFocused = ref(false)
function doSearch() {
  const q = searchQuery.value.trim()
  if (!q) return
  closeMenu()
  router.push({ path: '/search', query: { q } })
}

// Dropdown
const menuOpen = ref(false)
function toggleMenu() { menuOpen.value = !menuOpen.value }
function closeMenu() { menuOpen.value = false }

// Theme
// body 上的 light 类**不在这里加** —— 那是首屏之后才发生的事, 存浅色主题时会闪一下.
// 首屏那一次由 index.html 里的内联脚本负责; 这里只读初始值, 给按钮状态用.
// 读的时候包 try: 隐私模式/禁用存储下 localStorage 会直接抛, 不该让整个导航栏挂掉
// (与 index.html 那段内联脚本保持一致).
//
// 「没存过」和「存了 dark」是**两件事**, 改前把它们当成一件事了(默认深色):
// localStorage 里没有记录时应该跟系统走, 而不是替用户选深色。
const metaThemeColor = () => document.querySelector('meta[name="theme-color"]')
const THEME_COLORS = { light: '#fcf1f0', dark: '#0d0c0b' }  // 与 tokens.css 的 --bg 一致

function storedTheme() {
  try { return localStorage.getItem('theme') } catch (e) { return null }
}
function initialLight() {
  const stored = storedTheme()
  if (stored) return stored === 'light'
  return window.matchMedia('(prefers-color-scheme: light)').matches
}
const light = ref(initialLight())
function toggleLight() {
  light.value = !light.value
  document.body.classList.toggle('light', light.value)
  // 地址栏颜色得跟着 body 走, 而它只认 meta, 拿不到 CSS 变量 —— 所以这里必须
  // 再写一遍色值(与 index.html 那段内联脚本是同一份, 改一处要改两处).
  const meta = metaThemeColor()
  if (meta) meta.setAttribute('content', light.value ? THEME_COLORS.light : THEME_COLORS.dark)
  // 写也包 try: 存储被禁用时 getItem 会抛, setItem 一样会 —— 改前这一行是裸的,
  // 隐私模式下点一下主题按钮就会抛出去
  try { localStorage.setItem('theme', light.value ? 'light' : 'dark') } catch (e) { /* 存储不可用 */ }
}

// Scroll shrink
const scrolled = ref(false)
function onScroll() { scrolled.value = window.scrollY > 40 }

// Logout
function handleLogout() {
  closeMenu()
  userStore.logout()
  router.push('/')
}

// Click outside dropdown (backdrop handles this)
onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))
</script>

<style scoped>
/* ── Search ── */
.nav-search-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg-secondary);
  border: 1.5px solid var(--border);
  border-radius: 24px;
  padding: 0 16px;
  transition: border-color var(--transition), box-shadow var(--transition), background var(--transition);
  max-width: 360px;
  width: 100%;
  margin: 0 24px;
}
.nav-search-wrap.focused {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--focus-ring-soft);
  background: var(--card);
}
.nav-search-icon { color: var(--text-muted); flex-shrink: 0; transition: color var(--transition); }
.nav-search-wrap.focused .nav-search-icon { color: var(--primary); }
.nav-search-input {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  padding: 9px 0;
  outline: none;
  font-family: inherit;
}
.nav-search-input::placeholder { color: var(--text-muted); }

/* ── Brand ── */
/* 图标从 emoji 换成 SVG 之后 font-size 不再起作用, 尺寸改由 CSS 给 —— 这样
   下面那条"滚动时缩小"还能照常生效(它原本改的就是字号, 现在改成宽高). */
.brand-icon {
  display: block;
  width: 22px; height: 22px;
  color: var(--primary);
  transition: width var(--dur) var(--ease), height var(--dur) var(--ease);
}
/* 站名的两处改动:
   1. 字色改前是紫→粉的渐变描字(AI 生成界面最常见的签名, 也是这个站"模板感"
      最直观的一处), 换配色那一步已经改成实心墨色。
   2. 字体换成显示体(窄体)。改前它继承 navbar.css 的 `font-size:20px;
      font-weight:800`, 而 800 落在**正文体**上 —— IBM Plex Sans 最粗只到 700
      (见 main.js 的注记), 所以那个字重是浏览器伪粗体合成出来的。现在 700 是
      这个字体真有的字重, 加上窄体, 站名从"一行粗字"变成一块刊头。
   字号也一并收到这里独占: 改前 .brand-text 自己没有字号, 靠从 .navbar-brand
   继承, 于是"窄屏 16px"在 navbar.css、"滚动时 16px"在这里, 同一个元素的大小
   由两个文件各管一段。 */
.brand-text {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: .01em;
  line-height: 1;
  color: var(--text);
  transition: font-size var(--dur) var(--ease);
}

/* ── Nav Links with Indicator ── */
.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-secondary);
  font-size: 14px;
  font-weight: 500;
  padding: 8px 14px;
  border-radius: var(--radius-sm);
  transition: all var(--transition);
  position: relative;
  text-decoration: none;
  white-space: nowrap;
}
.nav-link:hover {
  color: var(--text);
  background: var(--card-hover);
}
/* 选中态靠**洗色底**表达, 不靠换文字色: --primary 现在是墨色, 深色主题下就是
   近白, 跟 --text 几乎同一个色 —— 拿它当文字色的话, 选中和没选中看不出区别。
   底色的对比度不受主题影响, 所以这个做法两套主题下都成立。 */
.nav-link--active {
  color: var(--text) !important;
  background: var(--primary-soft);
}
.nav-link-label { display: inline; }

/* ── User Button ── */
.nav-user-area { position: relative; }
.nav-user-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px 6px 6px;
  border-radius: 24px;
  border: 1.5px solid var(--border);
  background: var(--card);
  cursor: pointer;
  transition: all var(--transition);
  color: var(--text);
  font-size: 13px;
  font-family: inherit;
}
.nav-user-btn:hover { border-color: var(--primary); background: var(--card-hover); }
.nav-avatar { color: var(--primary); flex-shrink: 0; }
.nav-username { font-weight: 600; max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav-caret { color: var(--text-muted); transition: transform var(--transition); flex-shrink: 0; }
.nav-caret.open { transform: rotate(180deg); }

/* ── Dropdown Backdrop ── */
.nav-dropdown-backdrop {
  position: fixed;
  inset: 0;
  z-index: 998;
}

/* ── Dropdown Menu ── */
.nav-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  min-width: 200px;
  background: var(--card);
  border: 1px solid var(--card-border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  z-index: 999;
  overflow: hidden;
  padding: 6px;
}
.dropdown-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 12px 8px;
  color: var(--text);
}
.dropdown-name { font-weight: 700; font-size: 14px; }
.dropdown-role { font-size: 11px; color: var(--text-muted); }
.dropdown-divider {
  height: 1px;
  background: var(--border);
  margin: 4px 8px;
}
.dropdown-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all var(--transition);
  text-decoration: none;
  background: none;
  border: none;
  width: 100%;
  font-family: inherit;
}
.dropdown-item:hover { background: var(--card-hover); color: var(--text); }
.dropdown-danger { color: var(--danger); }
.dropdown-danger:hover { background: var(--danger-soft); color: var(--danger); }

/* ── Dropdown Transition ── */
/* 进入用收尾型缓动(前段快、后段落定), 退出比进入更快: 打开是"给你看一样东西",
   关掉是"你已经看完了" —— 后者不该再等。 */
.dropdown-enter-active { transition: all var(--dur) var(--ease-out); }
.dropdown-leave-active { transition: all var(--dur-fast) var(--ease); }
.dropdown-enter-from { opacity: 0; transform: translateY(-6px) scale(.96); }
.dropdown-leave-to { opacity: 0; transform: translateY(-4px) scale(.98); }

/* ── Guest Buttons ── */
.nav-btn {
  display: inline-flex;
  align-items: center;
  padding: 8px 20px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--transition);
  border: 1.5px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  text-decoration: none;
}
.nav-btn:hover { border-color: var(--primary); color: var(--primary); }
.nav-btn-primary {
  background: var(--primary);
  color: var(--primary-foreground);
  border-color: var(--primary);
}
.nav-btn-primary:hover { background: var(--primary-hover); opacity: 0.95; }

/* ── Scroll Shrink ── */
/* 滚动收缩的时长归到 --dur, 缓动用 --ease(两端都要看得清的状态切换, 不是入场)。 */
.navbar {
  transition: height var(--dur) var(--ease), padding var(--dur) var(--ease), background var(--dur) var(--ease);
}
.nav-scrolled {
  height: 48px !important;
  /* 改前这里写死 `0 1px 3px rgba(0,0,0,.08)` —— 这个值恰好就是浅色主题的 --shadow,
     而深色主题的 --shadow 是 rgba(0,0,0,.4)。用 token 而不是再抄一遍数字:
     同一个"浮起来一层"的语义, 两套主题各有各的深浅。 */
  box-shadow: var(--shadow);
}
.nav-scrolled .brand-icon { width: 18px; height: 18px; }
/* 收缩后的字号只改值, 过渡由上面 .brand-text 那条就够 —— 改前这里另写了一条
   `transition: font-size .25s`, 而它只在 .nav-scrolled 存在时才生效, 于是
   **展开回去的那一段没有过渡**(类名一移除, 过渡规则和字号变化同时消失)。
   过渡要写在基态上, 两个方向才都有。 */
.nav-scrolled .brand-text { font-size: 17px; }
.nav-scrolled .nav-search-input { padding: 6px 0; font-size: 13px; transition: all var(--dur) var(--ease); }

/* ── Responsive ── */
@media (max-width: 768px) {
  .nav-search-wrap { max-width: 200px; margin: 0 12px; }
  .nav-link-label { display: none; }
  .nav-link { padding: 8px 10px; }
  .nav-username { display: none; }
}
@media (max-width: 480px) {
  .nav-search-wrap { max-width: 140px; margin: 0 8px; }
  /* 窄屏的站名大小回到这里(原来挂在 navbar.css 的 .navbar-brand 上, 靠继承生效) */
  .brand-text { font-size: 18px; }
}
</style>
