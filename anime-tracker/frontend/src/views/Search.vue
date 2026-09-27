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

    <!-- Search results -->
    <div v-if="!loading && searched">
      <p style="margin-bottom: 16px; color: var(--text-secondary);">
        共找到 <strong style="color:var(--primary);">{{ total }}</strong> 个结果
      </p>
      <div v-if="results.length > 0" class="anime-grid">
        <AnimeCard v-for="item in results" :key="item.id" :anime="item" />
      </div>
      <EmptyState v-else icon="🔍" message="没有找到相关番剧" />
    </div>

    <!-- Browse view (no search query) -->
    <div v-if="!loading && !searched && browseList.length > 0">
      <div class="anime-grid">
        <AnimeCard v-for="item in browseList" :key="item.id" :anime="item" />
      </div>
    </div>
    <EmptyState v-if="!loading && !searched && browseList.length === 0" icon="🎌" message="输入关键词搜索你喜欢的动漫" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchAnime, getRanking } from '../api'
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
  try {
    const res = await searchAnime(q, 1)
    results.value = res.data.data?.list || []
    total.value = res.data.data?.total || 0
  } catch (e) { console.error(e) }
  loading.value = false
}

// Watch URL changes for external links (navbar search, "查看全部")
watch(() => route.query.q, (val) => {
  if (val) { keyword.value = val; doSearch() }
})
watch(() => route.query.view, () => { loadBrowse() })

async function loadBrowse() {
  if (route.query.q) return // Don't load browse if searching
  loading.value = true
  try {
    const sort = viewMode.value === 'date' ? 'date' : 'rank'
    const res = await getRanking(sort, 60)
    browseList.value = res.data.data || []
  } catch (e) { console.error(e) }
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
