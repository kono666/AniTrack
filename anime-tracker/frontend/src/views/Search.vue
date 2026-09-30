<template>
  <div class="page-container">
    <div class="page-header">
      <h1>{{ pageTitle }}</h1>
      <!-- 排序切换.
           改前这个状态**只藏在网址里**: 首页「查看全部 →」把人送进
           /search?view=date, 而页面上没有任何地方能看见它、更别说改它 ——
           到了这一页就出不去了, 只能自己动手改地址栏.
           搜索态不显示: 那会儿列表里是搜索结果, 与"排行/最新"无关(loadBrowse
           里本来就有 `if (q) return`). -->
      <div v-if="!searched" class="view-switch" role="group" aria-label="列表排序">
        <button
          v-for="v in VIEWS"
          :key="v.key"
          class="vs-btn"
          :class="{ active: viewMode === v.key }"
          :aria-pressed="viewMode === v.key ? 'true' : 'false'"
          @click="setView(v.key)"
        >{{ v.label }}</button>
      </div>
    </div>

    <!-- Search bar (always visible) -->
    <div class="search-bar">
      <input
        class="search-input"
        v-model="keyword"
        placeholder="输入番剧名称..."
        @keyup.enter="doSearch()"
      />
      <!-- 括号不能省. 写成 @click="doSearch"(不带括号)时 Vue 传的是**事件对象**,
           doSearch 的第一个形参是页码, 于是 page 变成一个 MouseEvent ——
           请求参数里带的是它序列化出来的垃圾, 而页码那一栏静默失效 -->
      <button class="search-btn" @click="doSearch()">搜索</button>
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
import { strParam, pageParam } from '../utils/query'
import { useLatestOnly } from '../composables/useLatestOnly'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const router = useRouter()
/** 搜索请求令牌: 只认最后一次, 见 composables/useLatestOnly.js */
const searchRequest = useLatestOnly()

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

const VIEWS = [
  { key: 'rank', label: '热门排行' },
  { key: 'date', label: '最近更新' },
]

/**
 * 换排序. 只改 URL —— 列表由已有的 `watch(() => route.query.view)` 去重载,
 * 这里再自己调一次 loadBrowse 就会发两个请求.
 *
 * rank 是默认值, 不写进 URL(与 page=1 同一个口径): /search?view=rank 与 /search
 * 是同一个页面, 而首页那个「查看全部 →」链接仍然带着它, 两条路都能进来.
 */
function setView(v) {
  if (viewMode.value === v) return
  const next = { ...route.query }
  if (v === 'rank') delete next.view
  else next.view = v
  router.replace({ query: next })
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
  // 形参防御: 这个函数还会被当作事件处理器调用(模板上的 @click / @keyup.enter),
  // 那条路上第一个实参是事件对象. 模板已经补了括号, 但补括号这件事一旦被哪次重构
  // 抹掉, 症状是"搜索静默变成第 1 页 + 请求里带一个怪物参数", 很难往这里想.
  const target = Number.isInteger(p) && p > 0 ? p : 1
  const q = keyword.value.trim()
  if (!q) return
  // 令牌要在发请求**之前**取. 连点两页会有两个请求同时在飞, 谁先回来不确定 ——
  // 没有这个, 先发的那次后到就会把界面盖成上一页的结果.
  const token = searchRequest.begin()
  page.value = target
  syncQuery()
  loading.value = true
  searched.value = true
  error.value = ''
  try {
    const res = await searchAnime(q, target)
    // 过期: 后面每一行写的都是别人的状态, 一行都不能执行
    if (!searchRequest.isCurrent(token)) return
    results.value = res.data.data?.list || []
    total.value = res.data.data?.total || 0
  } catch (e) {
    // 过期那次的失败同样不能写 error —— 否则界面上会留下一条属于上一次搜索的
    // 报错, 而它对应的请求早就没人关心了
    if (!searchRequest.isCurrent(token)) return
    // 改前只 console.error: 请求失败时 results 保持上一次的值(或空), 界面上
    // 显示成「没有找到相关番剧」, 与「真的搜不到」完全分不出来
    error.value = loadErrorMessage(e, '搜索')
    results.value = []
    total.value = 0
  }
  // 能走到这里 ⇔ 上面没有早退 ⇔ 令牌仍是最新的, 所以收尾不需要再判一次
  loading.value = false
}

/** 失败后重试: 按当前是在搜索还是在浏览列表, 重跑对应那一次加载.
 *  搜索态要带上当前页码 —— 在第 3 页上失败, 重试应该重试第 3 页,
 *  而不是把用户悄悄送回第 1 页 */
function retry() {
  if (searched.value) doSearch(page.value)
  else loadBrowse()
}

/**
 * URL 变了 → 读回来. 三个入口都走这里: 导航栏搜索、「查看全部」这类外链,
 * 以及**后退/前进**.
 *
 * 改前这里只 watch 了 q, 而且拿到新 q 后调的是 `doSearch()` —— 默认参数 = 第 1 页.
 * 于是「搜 X → 翻到第 3 页 → 进详情 → 后退」看起来是坏的: 组件重新挂载后 URL 里
 * 的 page=3 从来没有被读过(只有 onMounted 那条路读了). 页码进 URL 是对的, 缺的是
 * 把它读回来这一半.
 *
 * 与 Home.vue 同构的两处:
 *   · q 与 page 合成**一个** watch, 否则一次改两样会发两次请求;
 *   · 开头的早退判断防的是**自己**——doSearch 里 syncQuery 会写 URL, 同样触发这个
 *     watch. 判据是「URL 解析出来的值和当前 state 是否一致」, 而 doSearch 是先改
 *     page/keyword 再写 URL 的, 所以那次一定一致.
 */
watch(
  () => [route.query.q, route.query.page],
  ([rawQ, rawPage]) => {
    const q = strParam(rawQ)
    if (!q) return
    const nextPage = pageParam(rawPage)
    // 比的是 keyword.value.trim(): syncQuery 写进 URL 的是 trim 过的值, 而用户
    // 输入框里可能还带着首尾空格 —— 用原值比的话,"自己刚写出去的那次"会被认成
    // 外部变化, 于是又搜一遍(输入 " 巨人 " 时会发两次请求)
    if (q === keyword.value.trim() && nextPage === page.value) return
    keyword.value = q
    doSearch(nextPage)
  },
)
watch(() => route.query.view, () => { loadBrowse() })

async function loadBrowse() {
  if (strParam(route.query.q)) return // Don't load browse if searching
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
  const q = strParam(route.query.q)
  if (q) {
    keyword.value = q
    // 带上 URL 里的页码: 从详情页后退回来时组件是重新挂载的, 这是唯一记得住
    // 「刚才在第几页」的地方(缺省就是第 1 页)
    doSearch(pageParam(route.query.page))
  } else {
    loadBrowse()
  }
})
</script>
