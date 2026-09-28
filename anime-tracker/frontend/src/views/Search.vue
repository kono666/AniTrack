<template>
  <div class="page-container">
    <div class="page-header">
      <h1>{{ pageTitle }}</h1>
    </div>

    <!-- Search bar (always visible) -->
    <div class="search-bar">
      <input
        class="search-input"
        v-model="keyword"
        placeholder="输入番剧名称..."
        @keyup.enter="doSearch"
      />
      <button class="search-btn" @click="doSearch">搜索</button>
    </div>

    <LoadingSpinner v-if="loading" text="搜索中..." />

    <!-- 加载失败. 必须排在最前面: 空态和错误态要分开, 否则断网时用户看到的是
         「没有找到相关番剧」—— 他会以为这个站里就是没有, 而不是自己网络断了 -->
    <EmptyState
      v-if="!loading && error"
      icon="⚠️"
      :message="error"
      action-label="重试"
      @action="retry"
    />

    <!-- Search results -->
    <div v-else-if="!loading && searched">
      <p style="margin-bottom: 16px; color: var(--text-secondary);">
        共找到 <strong style="color:var(--primary);">{{ total }}</strong> 个结果
      </p>
      <div v-if="results.length > 0" class="anime-grid">
        <AnimeCard v-for="item in results" :key="item.id" :anime="item" />
      </div>
      <EmptyState v-else icon="🔍" message="没有找到相关番剧" />
    </div>

    <!-- Browse view (no search query) -->
    <div v-else-if="!loading && browseList.length > 0">
      <div class="anime-grid">
        <AnimeCard v-for="item in browseList" :key="item.id" :anime="item" />
      </div>
    </div>
    <EmptyState v-else-if="!loading" icon="🎌" message="输入关键词搜索你喜欢的动漫" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchAnime, getRanking } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const results = ref([])
const total = ref(0)
const loading = ref(false)
const searched = ref(false)
const browseList = ref([])
const error = ref('')

const viewMode = computed(() => route.query.view || 'rank')
const pageTitle = computed(() => {
  if (searched.value) return '🔍 搜索结果'
  if (viewMode.value === 'date') return '🆕 最近更新'
  return '🏆 热门排行'
})

async function doSearch() {
  const q = keyword.value.trim()
  if (!q) return
  // Sync to URL
  if (route.query.q !== q) {
    router.replace({ query: { q } })
  }
  loading.value = true
  searched.value = true
  error.value = ''
  try {
    const res = await searchAnime(q, 1)
    results.value = res.data.data?.list || []
    total.value = res.data.data?.total || 0
  } catch (e) {
    // 改前只 console.error: 请求失败时 results 保持上一次的值(或空), 界面上
    // 显示成「没有找到相关番剧」, 与「真的搜不到」完全分不出来
    error.value = loadErrorMessage(e, '搜索')
    results.value = []
    total.value = 0
  }
  loading.value = false
}

/** 失败后重试: 按当前是在搜索还是在浏览列表, 重跑对应那一次加载 */
function retry() {
  if (searched.value) doSearch()
  else loadBrowse()
}

// Watch URL changes for external links (navbar search, "查看全部")
watch(() => route.query.q, (val) => {
  if (val) { keyword.value = val; doSearch() }
})
watch(() => route.query.view, () => { loadBrowse() })

async function loadBrowse() {
  if (route.query.q) return // Don't load browse if searching
  loading.value = true
  error.value = ''
  try {
    const sort = viewMode.value === 'date' ? 'date' : 'rank'
    const res = await getRanking(sort, 60)
    browseList.value = res.data.data || []
  } catch (e) {
    // 同上: 失败与「这个筛选下没有作品」必须能分辨
    error.value = loadErrorMessage(e, '加载列表')
    browseList.value = []
  }
  loading.value = false
}

onMounted(() => {
  const q = route.query.q
  if (q) {
    keyword.value = q
    doSearch()
  } else {
    loadBrowse()
  }
})
</script>
