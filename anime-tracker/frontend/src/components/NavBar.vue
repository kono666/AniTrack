<template>
  <nav class="navbar" :class="{ 'nav-scrolled': scrolled }">
    <!-- Left: Brand -->
    <!-- aria-label 是给窄屏补的: ≤480px 时 .brand-text 被 display:none 隐掉,
         而 display:none 连同无障碍树一起摘 —— 那样这个链接就只剩一个没有名字
         的图标. 宽屏下它覆盖掉里面的站名文字, 说的是同一件事, 不冲突. -->
    <router-link to="/" class="navbar-brand" aria-label="AniTrack 首页" @click="closeMenu">
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
        <!-- 未读红点挂**头像**上, 不挂在「个人主页」那一项上: 那一项在折叠的下拉里,
             要先把菜单点开才看得见 —— 而红点的全部价值就是"不用点也知道". 下拉里
             仍然有一个带数字的角标(见下), 两个读的是同一个数字。
             title 是给鼠标用户的同一句话; 红点自己 aria-hidden —— 它是个纯视觉提示,
             数字在下拉里是**真文本**, 读屏用户点开就念得出来, 不必在这里重复一遍。 -->
        <button class="nav-user-btn" :title="unread > 0 ? `${unread} 条未读通知` : ''" @click="toggleMenu">
          <span class="nav-avatar-wrap">
            <img
              v-if="userStore.user?.avatar"
              :src="userStore.user.avatar"
              :alt="userStore.user?.username || '头像'"
              class="nav-avatar-img"
            />
            <PhUserCircle v-else :size="22" weight="fill" class="nav-avatar" />
            <span v-if="unread > 0" class="nav-dot" aria-hidden="true"></span>
          </span>
          <span class="nav-username">{{ userStore.user?.username }}</span>
          <PhCaretDown :size="12" weight="bold" class="nav-caret" :class="{ open: menuOpen }" />
        </button>

        <Teleport to="body">
          <div v-if="menuOpen" class="nav-dropdown-backdrop" @click="closeMenu"></div>
        </Teleport>
        <Transition name="dropdown">
          <div v-if="menuOpen" class="nav-dropdown">
            <div class="dropdown-header">
              <img
                v-if="userStore.user?.avatar"
                :src="userStore.user.avatar"
                :alt="userStore.user?.username || '头像'"
                class="nav-avatar-img nav-avatar-img-lg"
              />
              <PhUserCircle v-else :size="32" weight="fill" />
              <div>
                <div class="dropdown-name">{{ userStore.user?.username }}</div>
                <div class="dropdown-role">{{ userStore.user?.role === 'ADMIN' ? '管理员' : '用户' }}</div>
              </div>
            </div>
            <div class="dropdown-divider"></div>
            <router-link to="/profile" class="dropdown-item" @click="closeMenu">
              <PhUser :size="16" weight="bold" /> 个人主页
              <span v-if="unread > 0" class="dropdown-badge">{{ unread }}</span>
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
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useNotificationStore } from '../stores/notification'
import { useTheme } from '../composables/useTheme'
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
const route = useRoute()
const userStore = useUserStore()
const notificationStore = useNotificationStore()

// 未读通知数。红点与下拉里的角标读的是**同一个**数字, 它住在 store 里而不是这里 ——
// 个人页读完通知后要能就地把它清零, 而导航栏在应用外壳里、页面切换不会让它重新挂载
// (理由写在 stores/notification.js 的文件头)。
const unread = computed(() => notificationStore.unreadCount)

/**
 * 什么时候去问一次未读数。两个触发点, 都必要:
 *
 *   1. **每次换页**(含进入 Profile)。改前只有挂载时拉一次的话, 用户在个人页把
 *      通知读完了、回到首页 —— 导航栏从头到尾没重新挂载, 红点就一直是亮的。
 *      immediate 顺带把"首次进入"这件事一起管了, 不需要再在 onMounted 里写一遍。
 *   2. **登录态变化**。登入时要立刻拉一次(否则要等下一次换页), 登出时清零 ——
 *      清除**单独**放在 watcher 里而不是 handleLogout 里, 是因为登出有两条路:
 *      点"退出登录", 以及 401 拦截器里的那句 logout。写进其中一条就会漏掉另一条,
 *      而漏掉的那条的症状是"换个人登录, 上一个人的红点还在"。
 *
 * 未登录时一个请求都不发: 游客点一下换一页就多发一次 401 请求, 毫无收益。
 * (store 的 refresh() 也扛得住未登录 —— 它把 401 当"没有未读"。这里是省流量, 不是正确性。)
 */
function syncBadge() {
  if (userStore.loggedIn) notificationStore.refresh()
  else notificationStore.clear()
}
watch(() => route.fullPath, syncBadge, { immediate: true })
watch(() => userStore.loggedIn, syncBadge)

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
//
// 这段逻辑改前就长在这里, 现在搬进了 composables/useTheme.js —— 因为后台的侧栏
// 也要有一个同样的开关(它不渲染导航栏, 拿不到这个下拉), 而「初始值怎么定」
// 再抄第四份就是它开始漂的起点. 那个文件里有完整的来龙去脉.
const { light, toggle: toggleLight } = useTheme()

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
  /* 这里两条都是必须的, 不是美化 —— 而且缺一不可, 各自管一件事:
     (1) min-width:0 —— <input> 的 min-width 默认是 auto, 它的最小宽度来自
         size 属性(默认 20 个字符), 实测 152px, 与内容无关. 不写 0, 它连
         "可以被压缩"都不允许.
     (2) width:0 —— 光有 (1) 只解决了布局, 没解决**固有尺寸**: 父级
         .nav-search-wrap 算自己的 min-content 时, 输入框仍然报 152, 于是整个
         navbar 的 min-content 是 541px, 390px 的手机装不下, 整页横向滚动.
         实测: 只加 (1) → navbar min-content 476; 补上 (2) → 394, 文档也不再
         横向滚(384).
     flex:1 是 flex-basis 0%, 所以 width 只影响"报给父级的固有宽度", 不影响实际
     渲染 —— 有空间时它照样撑满整条搜索框. */
  min-width: 0;
  width: 0;
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
/* 有头像时占的是与上面那个 22px 图标**一样大**的一格 —— 尺寸不一致的话,
   上传头像会让导航栏的高度跳一下, 而红点(绝对定位在 wrap 上)也会跟着挪位 */
.nav-avatar-img { width: 22px; height: 22px; border-radius: 50%; object-fit: cover; display: block; flex-shrink: 0; }
/* 下拉里的那一份与上面的图标(32)同尺寸 */
.nav-avatar-img-lg { width: 32px; height: 32px; }
/* 包一层只为给红点当定位参照 —— 头像本身是 svg, 直接往上绝对定位会连它的
   基线一起算进去。flex-shrink:0 从 .nav-avatar 挪到这里(现在被压缩的是这个
   盒子), 窄屏下用户名让位时头像不许被挤扁。 */
.nav-avatar-wrap { position: relative; display: inline-flex; flex-shrink: 0; }
/* 红点压着头像右上角。描边用 --card 而不是白色: 按钮的底色就是 --card,
   两套主题各是各的底色, 靠它把红点从图标线条上"抠"出来。 */
.nav-dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--danger);
  border: 1.5px solid var(--card);
}
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
/* 下拉里的未读角标. 用 tokens 里那对 badge-red(两套主题各一份、对比度实测过的),
   不自己调一个红: 「压在色块上的小字」在这个仓里已经有一处定义点。 */
.dropdown-badge {
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--badge-red-bg);
  color: var(--badge-red-fg);
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}
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
  /* 站名在 480 以下让位给搜索与登录/注册.
     实测 390px: 就算把输入框压到 min-width:0, 图标 22 + 搜索 56 + 三个导航项
     102 + 登录/注册 136 + 内边距与间隙, 加起来仍超出 390 —— 这一行里品牌是最
     该让的那个(图标还是那个胶片标, 认得出来; 而搜索和登录是功能, 让了就没法用).
     上面 router-link 上的 aria-label 就是为了补这个 display:none. */
  .brand-text { display: none; }

  /* 剩下这两条是把 394 再收到 338 —— 394 在 390 的机器上刚好不溢, 但 360px
     的安卓(很常见)会差 20px. 收的都是内边距和 1px 字号, 观感上察觉不到, 换来
     的是一直到 340px 都不用再动. */
  .nav-link { padding: 6px 8px; }
  .nav-btn { padding: 6px 10px; font-size: 12px; }
}
</style>
