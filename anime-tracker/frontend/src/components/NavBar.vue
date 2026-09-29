<template>
  <nav class="navbar" :class="{ 'nav-scrolled': scrolled }">
    <!-- Left: Brand -->
    <router-link to="/" class="navbar-brand" @click="closeMenu">
      <span class="brand-icon">🎬</span>
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
      <router-link to="/" class="nav-link" exact-active-class="nav-link--active" @click="closeMenu">
        <PhCompass :size="18" weight="duotone" />
        <span class="nav-link-label">发现</span>
      </router-link>
      <router-link to="/assistant" class="nav-link" active-class="nav-link--active" @click="closeMenu">
        <PhSparkle :size="18" weight="duotone" />
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
              <PhUser :size="16" weight="duotone" /> 个人主页
            </router-link>
            <router-link v-if="userStore.user?.role === 'ADMIN'" to="/admin" class="dropdown-item" @click="closeMenu">
              <PhGear :size="16" weight="duotone" /> 管理后台
            </router-link>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item" @click="toggleLight">
              <PhSunHorizon v-if="!light" :size="16" weight="duotone" />
              <PhMoonStars v-else :size="16" weight="duotone" />
              {{ light ? '暗色模式' : '亮色模式' }}
            </button>
            <div class="dropdown-divider"></div>
            <button class="dropdown-item dropdown-danger" @click="handleLogout">
              <PhSignOut :size="16" weight="duotone" /> 退出登录
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
import {
  PhCompass, PhMagnifyingGlass, PhBookmarkSimple, PhGear,
  PhUserCircle, PhSunHorizon, PhMoonStars, PhCaretDown,
  PhUser, PhSignOut, PhSparkle
} from '@phosphor-icons/vue'

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
const light = ref(localStorage.getItem('theme') === 'light')
if (light.value) document.body.classList.add('light')
function toggleLight() {
  light.value = !light.value
  document.body.classList.toggle('light', light.value)
  localStorage.setItem('theme', light.value ? 'light' : 'dark')
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
  box-shadow: 0 0 0 3px rgba(168,85,247,.15);
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
.brand-icon { font-size: 22px; }
.brand-text {
  background: linear-gradient(135deg, var(--primary), var(--accent));
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
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
.nav-link--active {
  color: var(--primary) !important;
  background: rgba(168,85,247,.1);
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
.dropdown-danger:hover { background: rgba(248,113,113,.1); color: var(--danger); }

/* ── Dropdown Transition ── */
.dropdown-enter-active { transition: all .15s ease; }
.dropdown-leave-active { transition: all .1s ease; }
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
  color: #fff;
  border-color: var(--primary);
}
.nav-btn-primary:hover { background: var(--primary-hover); opacity: 0.95; }

/* ── Scroll Shrink ── */
.navbar {
  transition: height .25s ease, padding .25s ease, background .25s ease;
}
.nav-scrolled {
  height: 48px !important;
  box-shadow: 0 1px 3px rgba(0,0,0,.08);
}
.nav-scrolled .brand-icon { font-size: 18px; transition: font-size .25s; }
.nav-scrolled .brand-text { font-size: 16px; transition: font-size .25s; }
.nav-scrolled .nav-search-input { padding: 6px 0; font-size: 13px; transition: all .25s; }

/* ── Responsive ── */
@media (max-width: 768px) {
  .nav-search-wrap { max-width: 200px; margin: 0 12px; }
  .nav-link-label { display: none; }
  .nav-link { padding: 8px 10px; }
  .nav-username { display: none; }
}
@media (max-width: 480px) {
  .nav-search-wrap { max-width: 140px; margin: 0 8px; }
  .brand-text { font-size: 16px; }
}
</style>
