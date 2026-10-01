<template>
  <!-- 外壳(左栏 + 标题行)在路由那一层的 AdminLayout.vue, 这一页只剩内容 -->

  <!-- 工具栏**在加载与出错时也留着**: 它是"改条件"的入口, 而用户最需要改条件的
       时刻恰恰是"这次查出来不对"的时候. 跟着内容一起消失的话, 一次失败的请求会
       把筛选条件也锁住, 只能刷新页面才能换个词再搜 -->
  <div class="admin-toolbar">
    <input
      class="admin-input admin-search"
      type="search"
      :value="keyword"
      placeholder="搜索评论正文或用户名"
      aria-label="搜索评论正文或用户名"
      @input="onKeywordInput"
    />

    <!-- 三档而不是 1~10 的十个值: 后台是一个下拉, 十个选项没人会去点,
         而管理员真正会做的判断只有「差评有哪些」. 三个档位键与后端
         AdminService.RATING_BANDS 那张表**逐字对应**, 改一边必须改另一边 -->
    <select class="admin-select" :value="rating" aria-label="按评分档位筛选" @change="onRatingChange">
      <option value="">全部评分</option>
      <option value="low">差评 1–4</option>
      <option value="mid">中评 5–7</option>
      <option value="high">好评 8–10</option>
    </select>

    <select class="admin-select" :value="limit" aria-label="每页条数" @change="onLimitChange">
      <option v-for="size in ADMIN_PAGE_SIZES" :key="size" :value="size">{{ size }} 条/页</option>
    </select>

    <button v-if="hasFilter" class="action-btn" @click="clearFilters">清除筛选</button>

    <span class="admin-toolbar-spacer" />
    <span v-if="!loading && !error" class="admin-result-count">共 {{ total }} 条评论</span>
  </div>

  <LoadingSpinner v-if="loading" />

  <!-- 加载失败. 改前一失败就是空列表, 和「这个站还没有评论」长得一模一样 ——
       管理端看到空表第一反应是数据没了, 而不是接口挂了 -->
  <EmptyState
    v-else-if="error"
    type="error"
    :message="error"
    action-label="重试"
    @action="loadReviews"
  />

  <template v-else>
    <div class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <!-- 这一列排的是**主键**而不是 createdAt, 见下面 SORT_ID 那段注释 -->
            <th :aria-sort="ariaSortFor('id')">
              <button
                class="admin-sort-btn"
                :class="{ 'is-active': sort === 'id' }"
                @click="toggleSort('id')"
              >
                时间
                <PhCaretUp
                  v-if="sort === 'id' && order === 'asc'"
                  class="sort-icon" :size="12" aria-hidden="true"
                />
                <PhCaretDown v-else class="sort-icon" :size="12" aria-hidden="true" />
              </button>
            </th>
            <th>用户</th>
            <th>评分</th>
            <th>内容</th>
            <th>番剧</th>
            <th :aria-sort="ariaSortFor('likes')">
              <button
                class="admin-sort-btn"
                :class="{ 'is-active': sort === 'likes' }"
                @click="toggleSort('likes')"
              >
                赞
                <PhCaretUp
                  v-if="sort === 'likes' && order === 'asc'"
                  class="sort-icon" :size="12" aria-hidden="true"
                />
                <PhCaretDown v-else class="sort-icon" :size="12" aria-hidden="true" />
              </button>
            </th>
            <!-- 回复数不只是个数字: 一条挂着二十条回复的评论删掉, 带走的是**一整串
                 对话**(回复靠 ON DELETE CASCADE 跟着走). 所以它既能排序, 也进
                 删除确认的文案 -->
            <th :aria-sort="ariaSortFor('replies')">
              <button
                class="admin-sort-btn"
                :class="{ 'is-active': sort === 'replies' }"
                @click="toggleSort('replies')"
              >
                回复
                <PhCaretUp
                  v-if="sort === 'replies' && order === 'asc'"
                  class="sort-icon" :size="12" aria-hidden="true"
                />
                <PhCaretDown v-else class="sort-icon" :size="12" aria-hidden="true" />
              </button>
            </th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in reviews" :key="r.id">
            <td class="time-cell">
              {{ formatTime(r.createdAt) }}
              <span class="id-cell">#{{ r.id }}</span>
            </td>
            <td class="user-cell">{{ r.username }}</td>
            <td class="stars-cell">{{ starsOf(r.rating) }}</td>
            <td class="content-cell">{{ r.content || '（无文字）' }}</td>
            <!-- 番剧名可点: 改前这里是一个**连链接都没有的裸数字**, 管理员看不出
                 这是哪部番, 也就判断不了这条评论该不该删.
                 名字为 null 时退化成「番剧 #id」—— review.subject_id 与 anime 之间
                 没有外键, 那部番可能还没进本地库(见 AdminService.toAdminReviewRows),
                 这时 id 是**唯一**能给出的信息, 编一个名字比空着更糟 -->
            <td class="anime-cell">
              <router-link :to="`/anime/${r.subjectId}`" class="anime-link">
                {{ r.animeTitle || `番剧 #${r.subjectId}` }}
              </router-link>
            </td>
            <td class="num-cell">{{ r.likeCount }}</td>
            <td class="num-cell">{{ r.replyCount }}</td>
            <td>
              <button class="delete-btn" @click="handleDelete(r)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 三种"空"必须分开说, 否则三种都会显示成「暂无评论」:
           · 翻到的那一页没行了(总数还在) —— 是页码越界, 不是没有评论. 这种情况
             **会**真的发生: 在筛"差评"的最后一页把最后那条差评删了, 这一页就空了.
             所以这里给的不只是更好的文案, 还是**唯一的出路** —— 分页条就算渲染出来,
             用户也不一定看得出该点哪一页.
           · 有条件在筛 —— 说的是"没有匹配的", 并给出清条件的入口.
           · 干净的零 —— 才是「暂无评论」 -->
    <EmptyState
      v-if="reviews.length === 0 && total > 0"
      type="search"
      message="这一页没有评论了"
      action-label="回到第 1 页"
      @action="changePage(1)"
    />
    <EmptyState
      v-else-if="reviews.length === 0"
      :type="hasFilter ? 'search' : 'comment'"
      :message="hasFilter ? '没有匹配的评论' : '暂无评论'"
      :action-label="hasFilter ? '清除筛选' : ''"
      @action="clearFilters"
    />

    <!-- 分页条按 total 渲染而不是按"这一页有没有行": 越界的那一页正好是**没有行**
         的那一页, 按行数渲染就等于在最需要翻页的时候把翻页条藏起来 -->
    <Pagination :current-page="page" :total-pages="totalPages" @change="changePage" />
  </template>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PhCaretUp from '@icons/PhCaretUp.vue.mjs'
import PhCaretDown from '@icons/PhCaretDown.vue.mjs'
import { getAdminReviews, adminDeleteReview, ADMIN_PAGE_SIZES, ADMIN_PAGE_SIZE } from '../../api'
import { useToast } from '../../composables/useToast'
import { useLatestOnly } from '../../composables/useLatestOnly'
import { loadErrorMessage } from '../../utils/loadError'
import { strParam, pageParam } from '../../utils/query'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'
import Pagination from '../../components/Pagination.vue'

/**
 * 评论管理: 服务端分页 / 搜索 / 筛选 / 排序.
 *
 * 改前这一页拿的是**裸数组** —— 服务端 `getAllReviews()` 走 `Pageable.unpaged()`,
 * 整张评论表进 JVM 再原样塞进一个 JSON 数组. 库里两条评论时看不出问题; 真站规模下
 * 它既不能搜、不能筛、不能排序, 也没有分页, 而且每删一条都要把整张表重下一次.
 * 同一个方法还喂着两个 AI 工具, 各取全表只为了截前 30 / 15 条.
 *
 * 骨架与 `Users.vue` 逐条相同(状态全进 URL、默认值不写进 URL、useLatestOnly 令牌
 * 纪律、三种空态分开说、行内操作重取当前页), 那一段的注释已经把每条理由写过了,
 * 这里只记这一页**独有**的几处.
 */

const route = useRoute()
const router = useRouter()
const { show: toast } = useToast()
const request = useLatestOnly()

/**
 * 排序键的白名单, 与后端 `AdminService.SORT_ID/SORT_LIKES/SORT_REPLIES` 同源.
 *
 * 【为什么「时间」那一列排的是 `id` 而不是 `createdAt`】
 * 后端那条排序故意选的主键: `review.created_at` 在 V1 建表时是**可空**的, 按它排
 * 就得在 JPQL 里写「先按有没有值分组 + COALESCE + id 兜底」那三段式, 两个库才给出
 * 同一个序; 而 `id` 是主键、永远非空, 那三段一段都不需要.
 * 自增主键的顺序就是入库顺序, 也就是"先发的在前" —— 界面上两者给的是同一个序,
 * 所以列头照用户的话叫「时间」(并在格子里把 `#id` 一并显示出来), 而 URL 上写的是
 * `sort=id`: 那是对外契约的一部分, AI 工具那条路也传 "id".
 */
const SORT_ID = 'id'
const SORT_LIKES = 'likes'
const SORT_REPLIES = 'replies'
const SORTS = [SORT_ID, SORT_LIKES, SORT_REPLIES]
const DEFAULT_SORT = SORT_ID

const ORDERS = ['asc', 'desc']

/**
 * 每一列的**自然首向** —— 没在 URL 上写 order 时按它算.
 *
 * 三列**都是 desc**: 最新在前、赞多的在前、回复多的在前. 这是这一页与 `Users.vue`
 * 的一处差别 —— 那边两列的首向不同(时间 desc / 名字 asc), 于是要按列查表; 这里
 * 塌缩成一句"默认就是倒序". 后端 `getReviewPage` 那边写了同一件事的另一半.
 *
 * 两边必须一致的理由与 `Users.vue` 一字不差: 前端把默认组合**不写进 URL**,
 * 于是分享出去的链接是光秃秃的 `?sort=likes` —— 后端若按"不是 asc 就是 desc"以外
 * 的规则解释它, 表头上的 caret 就会指着一个方向、行是按另一个方向排的.
 */
const NATURAL_ORDER = { [SORT_ID]: 'desc', [SORT_LIKES]: 'desc', [SORT_REPLIES]: 'desc' }

/** 档位键, 与后端 RATING_BANDS 的三行逐字对应 */
const RATING_BANDS = ['low', 'mid', 'high']

/** 输入到发请求之间的静默期. 300ms ≈ 正常打字的字间隔, 连打一个字不会各发一次 */
const SEARCH_DEBOUNCE = 300

/** 确认框里正文摘要的字数 —— 与后端 TextSnippet 的 60 字口径无关, 这里只要够认出是哪条 */
const CONFIRM_EXCERPT = 40

const reviews = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

const keyword = ref('')
const rating = ref('')
const sort = ref(DEFAULT_SORT)
const order = ref(NATURAL_ORDER[DEFAULT_SORT])
const page = ref(1)
const limit = ref(ADMIN_PAGE_SIZE)

/**
 * 分页控件认的页数.
 *
 * 分子是**服务端报的 total**, 不是手上这几十行 —— 本地算的话, 第 1 页永远显示"共 1 页".
 * limit 与请求里发的是同一个 ref, 不会出现"发 50 条按 20 条算页数".
 */
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / limit.value)))

/**
 * 有没有在"筛". 排序与每页条数不算 —— 它们不缩小结果集, 清除筛选也不该重置它们.
 *
 * 关键词那半边用 trim 后的结果: 只打了几个空格时请求里根本不带 keyword(见
 * loadReviews), 结果集也确实是全量, 这时说"有筛选条件"会让空态和「清除筛选」按钮
 * 都在说一件没发生的事.
 */
const hasFilter = computed(
  () => keyword.value.trim() !== '' || rating.value !== '',
)

function formatTime(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

/**
 * 十颗星, 实心的按评分.
 *
 * 夹一下再 repeat: `'☆'.repeat(10 - rating)` 在 rating > 10 时**抛 RangeError**,
 * 而那是渲染期抛的 —— 一条脏数据会让整页白屏, 而不是让那一格难看. 评分本来由
 * 服务端约束在 1..10, 这一句防的是"约束被绕过"(比如手工灌进库的行).
 */
function starsOf(rating) {
  const n = Math.max(0, Math.min(10, Number(rating) || 0))
  return '★'.repeat(n) + '☆'.repeat(10 - n)
}

/** 白名单挑选: 认不出来的一律当"没有这个值". 只可能来自被手改过的 URL */
function pick(allowed, value) {
  return allowed.includes(value) ? value : ''
}

/**
 * URL → 状态.
 *
 * 垃圾值一律落回默认而**不报错**, 与 pageParam 同一口径(手输错一个参数不该让整个
 * 页面变成错误页). 三处必须走白名单而不是直接拿来用, 理由与 `Users.vue` 那一段
 * 逐字相同(limit 的 parseInt 会给 NaN、sort/order 认不出来时会让 caret 与行对不上).
 */
function readQuery() {
  const nextSort = pick(SORTS, strParam(route.query.sort)) || DEFAULT_SORT
  const rawOrder = strParam(route.query.order)
  const rawLimit = strParam(route.query.limit)
  return {
    keyword: strParam(route.query.q),
    rating: pick(RATING_BANDS, strParam(route.query.rating)),
    sort: nextSort,
    // order 的默认值跟着**解析后**的 sort 走, 不是跟着 URL 上那个原始值 ——
    // `?sort=bogus` 会落回 id, 那 order 的自然首向也该是 desc
    order: ORDERS.includes(rawOrder) ? rawOrder : NATURAL_ORDER[nextSort],
    page: pageParam(route.query.page),
    limit: ADMIN_PAGE_SIZES.includes(Number(rawLimit)) ? Number(rawLimit) : ADMIN_PAGE_SIZE,
  }
}

/** 当前状态. 与 readQuery 的返回值同形, 用来判定"URL 读回来的和手上的其实一样" */
function currentState() {
  return {
    keyword: keyword.value, rating: rating.value,
    sort: sort.value, order: order.value, page: page.value, limit: limit.value,
  }
}

function stateKey(s) {
  return [s.keyword, s.rating, s.sort, s.order, s.page, s.limit].join('|')
}

function applyState(s) {
  keyword.value = s.keyword
  rating.value = s.rating
  sort.value = s.sort
  order.value = s.order
  page.value = s.page
  limit.value = s.limit
}

/**
 * 把当前状态写进 URL.
 *
 * 默认值一律不写(空关键词 / 全部评分 / 默认排序列 / 该列的自然首向 / 第 1 页 / 20 条).
 *
 * ⚠️ `order` 的省略条件是「等于**当前排序列**的自然首向」, 不是"等于 desc".
 * 这一页三列的首向恰好都是 desc, 所以眼下两者等价 —— 但仍然照 `Users.vue` 写成
 * "跟着列走": 哪天加一列首向是 asc 的(比如"用户名"), 写成后者会让它一分享出去就反.
 *
 * 用 replace 不用 push: 调条件不该在历史里堆层, 否则从"筛了三层"退回"没筛"
 * 要按好几次后退.
 */
function syncQuery() {
  const next = { ...route.query }
  const set = (key, value) => { if (value) next[key] = value; else delete next[key] }

  // 写的是**原样**的关键词, 不 trim. 修词是请求那一侧的事(loadReviews 里 trim 过),
  // 而 URL 这一侧必须和手上的 ref 逐字相等 —— 否则打一个尾随空格就写出一条
  // 短一位的 q, 下面那个 watch 判定"URL 与我手上的不一样"而再取一次数据.
  set('q', keyword.value)
  set('rating', rating.value)
  set('sort', sort.value === DEFAULT_SORT ? '' : sort.value)
  set('order', order.value === NATURAL_ORDER[sort.value] ? '' : order.value)
  set('limit', limit.value === ADMIN_PAGE_SIZE ? '' : String(limit.value))
  set('page', page.value > 1 ? String(page.value) : '')

  router.replace({ query: next })
}

/**
 * 取当前条件下的这一页.
 *
 * 令牌纪律与 `Users.vue` 一字不差: `begin()` 在发请求**之前**取, `await` 之后每一行
 * 都先过 `isCurrent`, 而过期分支里**绝不能写 `loading = false`** —— 那会让"这一次转圈"
 * 停在界面上(还在飞的那次才是该关它的那个, 见 useLatestOnly.js).
 */
async function loadReviews() {
  const token = request.begin()
  error.value = ''
  loading.value = true
  try {
    const res = await getAdminReviews({
      // undefined 而不是 '': axios 会把它从 query 里丢掉, 于是"没筛"这一件事
      // 在请求上就表现为参数不存在, 与后端 `:param IS NULL` 那条一一对应
      keyword: keyword.value.trim() || undefined,
      rating: rating.value || undefined,
      // sort 只在**不是默认列**时才发 —— 与 Users.vue 同一条规矩, 后端对缺省
      // 走默认(主键倒序)
      sort: sort.value === DEFAULT_SORT ? undefined : sort.value,
      order: order.value === NATURAL_ORDER[sort.value] ? undefined : order.value,
      page: page.value,
      limit: limit.value,
    })
    if (!request.isCurrent(token)) return
    if (res.data.code === 200) {
      const body = res.data.data || {}
      reviews.value = body.list || []
      total.value = body.total || 0
    } else {
      error.value = `加载评论列表失败：服务端返回 ${res.data.code}`
      reviews.value = []
      total.value = 0
    }
  } catch (e) {
    if (!request.isCurrent(token)) return
    error.value = loadErrorMessage(e, '加载评论列表')
    reviews.value = []
    total.value = 0
  }
  // 能走到这里 ⇔ 上面没早退 ⇔ 令牌仍是最新的, 不用再判一次
  loading.value = false
}

// ==================== 删除 ====================

/**
 * 确认文案里带上**正文摘要与回复数**.
 *
 * 改前是一句 `确定删除用户 "X" 的评论？` —— 而管理员是在一张列了长正文的表里点的
 * 这一下, 摘要让他确认"删的是这一条"; 回复数则是这件事的**影响面**: 回复靠
 * `ON DELETE CASCADE` 跟着走, 删一条挂着二十条回复的评论带走的是一整串对话,
 * 而改前那句话里一个字都没提.
 */
function confirmText(r) {
  const text = (r.content || '').trim()
  const excerpt = text.length > CONFIRM_EXCERPT ? text.slice(0, CONFIRM_EXCERPT) + '…' : text
  const replies = typeof r.replyCount === 'number' ? r.replyCount : 0
  const tail = replies > 0 ? `，并连带删除其下 ${replies} 条回复` : ''
  return `确定删除用户 "${r.username}" 的评论「${excerpt || '（无文字）'}」${tail}？`
}

/**
 * 删除后**重取当前页**, 不做本地 `filter`.
 *
 * 改前是 `reviews.value = reviews.value.filter(...)`. 那在裸数组下勉强说得通,
 * 在服务端分页下是错的: 少一行而 total 没变 —— 表格比总数少一条, 而"这一页还剩
 * 几条"要靠它; 更糟的是筛着"差评"删掉最后一条时, 那一行**已经不符合筛选**了,
 * 留在表里等于凭空决定"它该不该消失". 重取一次把这三件事交给服务端说.
 * 与 `Users.vue` 四个行内操作的选择同一个理由.
 */
async function handleDelete(r) {
  if (!confirm(confirmText(r))) return
  try {
    await adminDeleteReview(r.id)
    await loadReviews()
  } catch (e) { toast(e.response?.data?.message || '删除失败', 'error') }
}

// ==================== 改条件 ====================

let debounceTimer = null

function cancelPendingSearch() {
  if (debounceTimer) { clearTimeout(debounceTimer); debounceTimer = null }
}

/**
 * 关键词: 防抖 300ms.
 *
 * 绑 `@input` 而**不是** `watch(keyword)`: 从 URL 读回来的程序化赋值(后退键)
 * 不该再触发一次请求 —— 那条路是 route.fullPath 的 watch 负责的.
 */
function onKeywordInput(e) {
  keyword.value = e.target.value
  cancelPendingSearch()
  debounceTimer = setTimeout(() => {
    debounceTimer = null
    applyFilterChange()
  }, SEARCH_DEBOUNCE)
}

function onRatingChange(e) { rating.value = e.target.value; return applyFilterChange() }

/** 每页条数: 必须回到第 1 页 —— 不重置的话, 20 条/页时的第 4 页在 100 条/页下是空的 */
function onLimitChange(e) { limit.value = Number(e.target.value); return applyFilterChange() }

function clearFilters() {
  keyword.value = ''
  rating.value = ''
  cancelPendingSearch()
  return applyFilterChange()
}

/**
 * 排序: 点同一列翻向, 点另一列用**那一列的自然首向**.
 *
 * 一律回第 1 页: 换了序之后"第 3 页"指的内容完全变了, 停在原页码只会让人以为
 * 数据没动.
 */
function toggleSort(column) {
  if (sort.value === column) {
    order.value = order.value === 'asc' ? 'desc' : 'asc'
  } else {
    sort.value = column
    order.value = NATURAL_ORDER[column]
  }
  return applyFilterChange()
}

function ariaSortFor(column) {
  if (sort.value !== column) return 'none'
  return order.value === 'asc' ? 'ascending' : 'descending'
}

/**
 * 改完条件的统一收尾: 页码复位 → 写 URL → 取数.
 *
 * 顺序不能换: 写 URL 触发的那个 watch 靠"解析出来的值等于当前状态"来早退,
 * ref 晚一步改就会多打一次请求.
 */
async function applyFilterChange() {
  page.value = 1
  syncQuery()
  await nextTick()
  await loadReviews()
}

/**
 * 翻页.
 *
 * 不写 watch(page): applyFilterChange 也要把页码复位成 1, 而 watch 分不清"复位导致的"
 * 和"用户点的", 从第 3 页改条件时两条路都会触发, 于是发两次请求.
 */
async function changePage(next) {
  page.value = next
  syncQuery()
  await nextTick()
  await loadReviews()
}

/**
 * 反向: URL 变了 → 读回来再取数. 后退/前进、以及手改地址栏走的是这条路.
 *
 * 六个条件合成**一个** watch: 拆开的话"换筛选同时回到第 1 页"会让两条都触发,
 * 发两次请求. 开头的早退判断也是必须的 —— 我们自己调 syncQuery 写 URL 同样会
 * 让它触发, 不判断就变成"点一次按钮发两次请求".
 *
 * 读回来之前先取消挂起的防抖: 用户打了半个词就按了后退, 那个定时器还等着 300ms 后
 * 把**按键时的**关键词写回去.
 */
watch(
  () => route.fullPath,
  () => {
    const next = readQuery()
    if (stateKey(next) === stateKey(currentState())) return
    cancelPendingSearch()
    applyState(next)
    loadReviews()
  },
)

/**
 * 首访: 先把 URL 里的条件收下, 再加载. 深链进来时条件就已经生效, 不会先按默认取一次
 * 再"跳"到筛选结果(那会白白多发一次请求, 界面上还会闪一下全量列表).
 */
onMounted(() => {
  applyState(readQuery())
  loadReviews()
})

// 防抖定时器必须清: 组件已经没了, 300ms 后它还会触发一次 loadReviews ——
// 那次请求的结果会写进一个已经卸载的组件的 ref, 而 loading 会永远停在转圈上.
onUnmounted(cancelPendingSearch)
</script>

<style scoped>
/* --card 而不是 --card-bg: 后者是只在 :root 里定义过的别名, 浅色主题下会冻在
   深色值上(整个面板深底深字). 详见 tokens.css 顶部那段.
   表壳(.admin-table-wrap)那一条在 admin.css 里, 这一页不再重复一份 ——
   scoped 的规则永远压过层内的, 两处各写一份的话改了其中一处等于没改. */
.time-cell { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
/* 主键单独一行小字: 它是"时间"那一列真正的排序键, 也是排查时要报给后端的东西 */
.id-cell { display: block; color: var(--text-muted); opacity: .7; font-variant-numeric: tabular-nums; }
.user-cell { font-size: 14px; font-weight: 500; color: var(--text); }
/* 与别处的星星用同一个 token. 改前这里是 #f5a623 —— 首页的评分角标用的是
   var(--star)(#fbbf24), 两处星星颜色其实不一样, 只是并排看不出来 */
.stars-cell { color: var(--star); white-space: nowrap; }
/* 正文是这一页最宽的一列, 也是唯一可能很长的一列: 给个上限并允许换行,
   否则一条 5000 字的评论会把整张表撑到只能横向滚动 */
.content-cell {
  font-size: 14px; line-height: 1.6; color: var(--text);
  min-width: 220px; max-width: 420px; word-break: break-word;
}
.anime-cell { font-size: 13px; max-width: 180px; }
.anime-link { color: var(--primary); text-decoration: none; }
.anime-link:hover { text-decoration: underline; }
/* 赞数/回复数是可比较的量: 等宽数字让一列数字能对齐着看 */
.num-cell { color: var(--text-secondary); font-variant-numeric: tabular-nums; }
.delete-btn {
  padding: 6px 16px; border: 1px solid var(--badge-red-fg); color: var(--badge-red-fg);
  background: var(--card); border-radius: 6px; cursor: pointer;
  font-size: 13px; white-space: nowrap;
}

/* 「清除筛选」是个普通按钮(不带语义色): 它是一个中性动作, 而不是"危险"或"主要" */
.admin-toolbar .action-btn { border: 1px solid var(--border); color: var(--text-secondary); }
.admin-toolbar .action-btn:hover { border-color: var(--primary-line); color: var(--primary); }

/* 改前这里还有一句「不是管理员就 router.push('/')」. 现在收在 AdminLayout 里,
   那边用 `<router-view v-if="authorized">` 挡着, 未授权时这个组件不会挂载. */
</style>
