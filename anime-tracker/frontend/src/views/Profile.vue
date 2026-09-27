<template>
  <div v-if="!loading" class="profile-page">
    <!-- Banner -->
    <div class="p-banner">
      <div class="p-banner-inner"></div>
    </div>

    <!-- Header: Avatar + Stats -->
    <div class="p-header">
      <div class="p-avatar-wrap">
        <div class="p-avatar">
          <PhUserCircle :size="80" weight="fill" />
        </div>
      </div>
      <div class="p-info">
        <h1 class="p-name">{{ userStore.user?.username }}</h1>
        <p class="p-email">{{ userStore.user?.email || '' }}</p>
        <div class="p-stats">
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalAnime || 0 }}</span>
            <span class="ps-lbl">追番</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalEpisodes || 0 }}</span>
            <span class="ps-lbl">集数</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalReviews || 0 }}</span>
            <span class="ps-lbl">评论</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.avgScore || '-' }}</span>
            <span class="ps-lbl">均分</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ (stats?.totalAnime || 0) - (stats?.completed || 0) }}</span>
            <span class="ps-lbl">在看</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Filter Tabs -->
    <div class="p-tabs">
      <button v-for="f in filters" :key="f.key" class="p-tab" :class="{ active: filter === f.key }" @click="filter = f.key">
        {{ f.label }}
        <span v-if="counts[f.key] !== undefined" class="p-tab-count">{{ counts[f.key] }}</span>
      </button>
      <div class="p-tab-spacer"></div>
      <button class="p-tab p-tab-sort" @click="sortBy = sortBy === 'date' ? 'score' : 'date'">
        {{ sortBy === 'date' ? '📅 按时间' : '⭐ 按评分' }}
      </button>
    </div>

    <!-- Anime List -->
    <div v-if="filtered.length > 0" class="p-list">
      <div
        v-for="item in filtered"
        :key="item.id"
        class="p-card"
        @click="$router.push(`/anime/${item.subjectId}`)"
      >
        <div class="pc-cover">
          <img :src="item.animeCover || fallbackImg" :alt="item.animeTitle" @error="e => e.target.src = fallbackImg" />
        </div>
        <div class="pc-body">
          <div class="pc-top">
            <div class="pc-title">{{ item.animeTitle || '番剧 #' + item.subjectId }}</div>
            <div class="pc-score" v-if="item.score">⭐ {{ item.score }}</div>
          </div>
          <div class="pc-meta">
            <span class="pc-status-badge" :class="'st-' + item.status">{{ statusLabel[item.status] }}</span>
            <span class="pc-type" v-if="item.animeType">{{ item.animeType }}</span>
            <span class="pc-year" v-if="item.animeYear">{{ item.animeYear }}</span>
          </div>
          <div class="pc-progress" v-if="item.totalEpisodes">
            <div class="pc-bar"><div class="pc-fill" :style="{ width: pct(item) + '%' }"></div></div>
            <span class="pc-prog-text">{{ item.progress || 0 }}/{{ item.totalEpisodes }}</span>
          </div>
        </div>
        <div class="pc-actions" @click.stop>
          <button class="pca-btn" @click="quickUpdate(item, 'progress', (item.progress||0) + 1)" title="+1集">+1</button>
          <select class="pca-select" :value="item.status" @change="e => updateStatus(item, e.target.value)">
            <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.short }}</option>
          </select>
        </div>
      </div>
    </div>

    <EmptyState v-else icon="📭" message="还没有追番记录">
      <router-link to="/" style="color:var(--primary);">去发现动漫</router-link>
    </EmptyState>
  </div>

  <div v-else class="page-container">
    <LoadingSpinner />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { getTrackingList, getOverallStats, saveTracking } from '../api'
import { PhUserCircle } from '@phosphor-icons/vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const trackings = ref([])
const stats = ref(null)
const filter = ref('all')
const sortBy = ref('date')

const fallbackImg = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="300" height="400" fill="#18181b"><rect width="300" height="400" rx="8"/><text x="150" y="200" text-anchor="middle" fill="#3f3f46" font-size="16">No Cover</text></svg>')

const statusLabel = { want_to_watch: '想看', watching: '在看', watched: '看过', on_hold: '搁置', dropped: '抛弃' }
const statusOptions = [
  { value: 'watching', short: '👀 在看' },
  { value: 'watched', short: '✅ 看过' },
  { value: 'want_to_watch', short: '🙏 想看' },
  { value: 'on_hold', short: '⏸️ 搁置' },
  { value: 'dropped', short: '❌ 抛弃' },
]

const filters = [
  { key: 'all', label: '全部' },
  { key: 'watching', label: '在看' },
  { key: 'watched', label: '看过' },
  { key: 'want_to_watch', label: '想看' },
  { key: 'on_hold', label: '搁置' },
  { key: 'dropped', label: '抛弃' },
]

const counts = computed(() => {
  const c = { all: trackings.value.length }
  for (const t of trackings.value) {
    c[t.status] = (c[t.status] || 0) + 1
  }
  return c
})

const filtered = computed(() => {
  let list = filter.value === 'all' ? trackings.value : trackings.value.filter(t => t.status === filter.value)
  if (sortBy.value === 'score') {
    list = [...list].sort((a, b) => (b.score || 0) - (a.score || 0))
  }
  return list
})

function pct(item) {
  if (!item.totalEpisodes) return 0
  return Math.min(100, Math.round(((item.progress || 0) / item.totalEpisodes) * 100))
}

async function updateStatus(item, newStatus) {
  try {
    await saveTracking({ subjectId: item.subjectId, status: newStatus, progress: item.progress || 0, score: item.score || 0 })
    item.status = newStatus
    $toast('已更新', 'success')
  } catch (e) { $toast('更新失败', 'error') }
}

async function quickUpdate(item, field, val) {
  try {
    await saveTracking({ subjectId: item.subjectId, status: item.status, progress: val, score: item.score || 0 })
    item.progress = val
  } catch (e) { $toast('更新失败', 'error') }
}

onMounted(async () => {
  if (!userStore.loggedIn) { router.push('/login'); return }
  try {
    const [listRes, statsRes] = await Promise.all([getTrackingList(), getOverallStats()])
    trackings.value = (listRes.data.data || []).map(t => ({
      ...t,
      animeYear: t.animeDate ? t.animeDate.substring(0, 4) : null,
    }))
    stats.value = statsRes.data.data || {}
  } catch (e) { console.error(e) }
  loading.value = false
})
</script>

<style scoped>
.profile-page { padding-bottom: 60px; }

/* ── Banner ── */
.p-banner { height: 160px; position: relative; overflow: hidden; background: linear-gradient(135deg, #1a1040 0%, #2d1b69 40%, #1e1245 70%, #0c0418 100%); }
.p-banner-inner { position: absolute; inset: 0; background: radial-gradient(circle at 30% 50%, rgba(168,85,247,.15) 0%, transparent 60%), radial-gradient(circle at 70% 30%, rgba(236,72,153,.1) 0%, transparent 50%); }

/* ── Header ── */
.p-header { display: flex; gap: 28px; max-width: 1000px; margin: -44px auto 0; padding: 0 32px; position: relative; z-index: 2; }
.p-avatar-wrap { flex-shrink: 0; }
.p-avatar { width: 96px; height: 96px; border-radius: 50%; background: var(--card); border: 4px solid var(--bg); box-shadow: 0 4px 24px rgba(0,0,0,.3); display: flex; align-items: center; justify-content: center; color: var(--primary); }
.p-info { flex: 1; padding-top: 48px; min-width: 0; }
.p-name { font-size: 24px; font-weight: 800; color: var(--text); margin-bottom: 2px; }
.p-email { font-size: 13px; color: var(--text-muted); margin-bottom: 16px; }
.p-stats { display: flex; gap: 32px; }
.p-stat { text-align: center; }
.ps-num { font-size: 22px; font-weight: 800; color: var(--text); }
.ps-lbl { font-size: 11px; color: var(--text-muted); display: block; }

/* ── Tabs ── */
.p-tabs { display: flex; gap: 4px; max-width: 1000px; margin: 24px auto 0; padding: 0 32px; border-bottom: 2px solid var(--border); }
.p-tab { display: flex; align-items: center; gap: 6px; padding: 10px 16px; border: none; background: none; font-size: 13px; font-weight: 500; color: var(--text-secondary); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -2px; transition: all var(--transition); font-family: inherit; }
.p-tab:hover { color: var(--text); }
.p-tab.active { color: var(--primary); border-bottom-color: var(--primary); }
.p-tab-count { font-size: 11px; color: var(--text-muted); }
.p-tab.active .p-tab-count { color: var(--primary); }
.p-tab-spacer { flex: 1; }
.p-tab-sort { font-size: 12px; color: var(--text-muted); }

/* ── List ── */
.p-list { max-width: 1000px; margin: 20px auto 0; padding: 0 32px; display: flex; flex-direction: column; gap: 8px; }
.p-card { display: flex; gap: 16px; padding: 16px; background: var(--card); border: 1px solid var(--card-border); border-radius: var(--radius); cursor: pointer; transition: all var(--transition); align-items: center; }
.p-card:hover { border-color: rgba(168,85,247,.25); background: var(--card-hover); }
.pc-cover { width: 64px; aspect-ratio: 3/4; border-radius: 6px; overflow: hidden; background: var(--bg-secondary); flex-shrink: 0; }
.pc-cover img { width: 100%; height: 100%; object-fit: cover; }
.pc-body { flex: 1; min-width: 0; }
.pc-top { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.pc-title { font-size: 15px; font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-score { font-size: 14px; color: var(--star); font-weight: 700; white-space: nowrap; }
.pc-meta { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; font-size: 11px; }
.pc-status-badge { padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.st-watching { background: rgba(59,130,246,.15); color: #60a5fa; }
.st-watched { background: rgba(52,211,153,.15); color: #34d399; }
.st-want_to_watch { background: rgba(251,191,36,.15); color: #fbbf24; }
.st-on_hold { background: rgba(161,161,170,.15); color: #a1a1aa; }
.st-dropped { background: rgba(248,113,113,.15); color: #f87171; }
.pc-type, .pc-year { color: var(--text-muted); }
.pc-progress { display: flex; align-items: center; gap: 8px; }
.pc-bar { width: 120px; height: 4px; background: var(--bg-secondary); border-radius: 2px; overflow: hidden; }
.pc-fill { height: 100%; background: var(--primary); border-radius: 2px; transition: width .3s; }
.pc-prog-text { font-size: 11px; color: var(--text-muted); }
.pc-actions { display: flex; gap: 6px; align-items: center; flex-shrink: 0; }
.pca-btn { width: 30px; height: 30px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text-secondary); font-size: 12px; font-weight: 700; cursor: pointer; transition: all var(--transition); font-family: inherit; }
.pca-btn:hover { border-color: var(--primary); color: var(--primary); }
.pca-select { padding: 6px 8px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text); font-size: 11px; cursor: pointer; font-family: inherit; }

@media (max-width: 768px) {
  .p-header { padding: 0 16px; gap: 16px; }
  .p-avatar { width: 72px; height: 72px; }
  .p-info { padding-top: 36px; }
  .p-name { font-size: 20px; }
  .p-stats { gap: 16px; }
  .ps-num { font-size: 16px; }
  .p-tabs { padding: 0 16px; overflow-x: auto; }
  .p-tab { padding: 8px 10px; font-size: 12px; white-space: nowrap; }
  .p-list { padding: 0 16px; }
  .pc-cover { width: 48px; }
  .pc-actions { display: none; }
}
</style>
