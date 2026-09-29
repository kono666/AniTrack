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
      type="error"
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
      <EmptyState v-else type="search" message="没有找到相关番剧" />

      <!-- 翻页. 后端 /bangumi/search 本来就吃 page/limit 并回 total(批次 1.3 加的),
           缺的一直是前端: 改前只请求第 1 页, 而结果上方还写着「共找到 N 个结果」——
           用户看得见总数, 却翻不到第 21 条, 只能换个词再搜一次 -->
      <Pagination
        v-if="results.length > 0"
        :current-page="page"
        :total-pages="totalPages"
        @change="doSearch"
      />
    </div>

    <!-- Browse view (no search query) -->
    <div v-else-if="!loading && browseList.length > 0">
      <div class="anime-grid">
        <AnimeCard v-for="item in browseList" :key="item.id" :anime="item" />
      </div>
    </div>
    <EmptyState v-else-if="!loading" type="search" message="输入关键词搜索你喜欢的动漫" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchAnime, getRanking, SEARCH_PAGE_SIZE } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const results = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const searched = ref(false)
const browseList = ref([])
const error = ref('')

// 页数按后端口径算(它回的 total 是**全部**匹配数, 不是这一页的条数)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / SEARCH_PAGE_SIZE)))

const viewMode = computed(() => route.query.view || 'rank')
const pageTitle = computed(() => {
  if (searched.value) return '搜索结果'
  if (viewMode.value === 'date') return '最近更新'
  return '热门排行'
})

/** 从 URL 读页码: 只认正整数, 缺省/乱填/0/负数一律当第 1 页 */
function pageFromQuery(raw) {
  const n = Number.parseInt(raw, 10)
  return Number.isInteger(n) && n > 0 ? n : 1
}

/**
 * 把 q 和 page 同步进 URL.
 *
 * 为什么页码必须进 URL: App.vue 里 router-view 的 key 是 route.path, 路径一变组件
 * 就重建 —— 从结果页点进详情再按后退, Search 是**重新挂载**的. 页码只存在组件里的
 * 话, 那时它已经是一个全新的第 1 页了, 于是「翻到第 3 页 → 进详情 → 后退」会掉回
 * 第一页. 页码写进 URL 是后退能回到原来那一页的唯一依靠.
 *
 * 第 1 页不写(?page=1 是噪音, 与"没有这个参数"等价); 其余 query 原样带着走 ——
 * 改前这行写的是 { query: { q } }, 会把 ?view= 一起抹掉.
 * 用 replace 不用 push: 翻页不该在历史里堆层, 否则从第 5 页退回第 1 页要按四次后退.
 */
function syncQuery() {
  const next = { ...route.query, q: keyword.value.trim() }
  if (page.value > 1) next.page = String(page.value)
  else delete next.page
  if (route.query.q === next.q && route.query.page === next.page) return
  router.replace({ query: next })
}

/** 搜索第 p 页. 新关键词从第 1 页开始, 翻页时把页码传进来(默认参数就是 1) */
async function doSearch(p = 1) {
  const q = keyword.value.trim()
  if (!q) return
  page.value = p
  syncQuery()
  loading.value = true
  searched.value = true
  error.value = ''
  try {
    const res = await searchAnime(q, p)
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

/** 失败后重试: 按当前是在搜索还是在浏览列表, 重跑对应那一次加载.
 *  搜索态要带上当前页码 —— 在第 3 页上失败, 重试应该重试第 3 页,
 *  而不是把用户悄悄送回第 1 页 */
function retry() {
  if (searched.value) doSearch(page.value)
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
    // 带上 URL 里的页码: 从详情页后退回来时组件是重新挂载的, 这是唯一记得住
    // 「刚才在第几页」的地方(缺省就是第 1 页)
    doSearch(pageFromQuery(route.query.page))
  } else {
    loadBrowse()
  }
})
</script>
