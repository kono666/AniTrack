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

    <!-- 只看被举报. 是个**开关**而不是下拉: 只有"看全部"与"只看被举报"两态, 而
         后者正是这一页最常用的那个动作(每天进来一次, 把队列清空) -->
    <button
      class="action-btn report-toggle"
      :class="{ 'is-on': reported }"
      :aria-pressed="reported ? 'true' : 'false'"
      @click="toggleReported"
    >
      <PhFlag :size="13" :weight="reported ? 'fill' : 'regular'" />
      只看被举报
    </button>

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
            <!-- 回复数不只是个数字: 一条挂着二十条回复的评论被移除, 那些回复也会跟着
                 从用户侧一起消失(列表按评论走, 没人能再翻到它们). 所以它既能排序,
                 也进确认的文案 —— 只是 V14 之后措辞从"连带删除"变成了"跟着隐藏",
                 因为后端的删除已经是软删, 回复行一条都没动 -->
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
            <!-- 举报. 它不是个数字而是一行小标签, 因为管理员要判的是"哪一条该先看":
                 次数说明有多少人受够它了, 最近那个理由说明他们在气什么.
                 两个值都只在**有待处理举报**时才有(reportCount 不含已忽略的),
                 所以一次都没被举报的行是空的 —— 与"举报都被处理完了"长得一样,
                 而那两件事在这一页不需要分开说(点进去看明细即知). -->
            <th>举报</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <!-- template 包住两行: 主行 + 展开时的明细行. :key 挂在 template 上,
               两个 tr 才是同一个"这一条评论"的两个部分 -->
          <template v-for="r in reviews" :key="r.id">
          <tr>
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
            <td class="report-cell">
              <button v-if="r.reportCount > 0" class="report-badge" @click="toggleDetail(r)"
                :aria-expanded="detailBox[r.id]?.open ? 'true' : 'false'">
                <PhFlag :size="12" weight="fill" />
                <span>被举报 {{ r.reportCount }} 次</span>
                <!-- 最近那个理由: 「被举报 3 次」说明有人在气, 「辱骂攻击」说明在气什么 -->
                <span v-if="r.latestReason" class="report-reason">{{ reasonLabel(r.latestReason) }}</span>
              </button>
              <span v-else class="report-none">—</span>
            </td>
            <!-- 一行上只有一个动作, 而且是哪一个**由数据说了算**: 已移除的那一行给的是
                 「恢复」, 没移除的才是「移除」。不做成两个并排的按钮(一个必然是灰的) ——
                 管理员在这一列上要看的是"这一行现在能做什么", 不是"有几种可能".
                 `deletedAt` 是服务端发的第三个状态, 与 exists/removed 同一套口径 -->
            <td class="action-cell">
              <template v-if="r.deletedAt">
                <span class="removed-badge">已移除</span>
                <button class="restore-btn" @click="handleRestore(r)">恢复</button>
              </template>
              <button v-else class="delete-btn" @click="handleDelete(r)">移除</button>
            </td>
          </tr>

          <!-- 明细: 点了那个徽标才拉(懒加载) —— 一页 20 行全带明细就是 20 倍的响应体,
               而管理员一次只可能读一行. 与评论区「谁赞了」「回复」同一个做法. -->
          <tr v-if="detailBox[r.id]?.open" class="report-detail-row">
            <td :colspan="COLUMN_COUNT">
              <div class="report-detail">
                <div v-if="detailBox[r.id].loading" class="report-detail-hint">加载中…</div>
                <div v-else-if="!detailBox[r.id].list.length" class="report-detail-hint">没有举报</div>
                <template v-else>
                  <div v-for="rp in detailBox[r.id].list" :key="rp.id" class="report-item">
                    <span class="report-item-reason">{{ reasonLabel(rp.reason) }}</span>
                    <span class="report-item-who">{{ rp.reporterName }}</span>
                    <span class="report-item-time">{{ formatTime(rp.createdAt) }}</span>
                    <span v-if="rp.detail" class="report-item-note">{{ rp.detail }}</span>
                    <!-- 已处理的只留一句"谁在处理", 不再给按钮: 忽略是单向的,
                         服务端那边也没有"恢复"这条路 -->
                    <span v-if="rp.status !== 'PENDING'" class="report-item-done">
                      已忽略<template v-if="rp.handlerName">（{{ rp.handlerName }}）</template>
                    </span>
                    <button v-else class="report-dismiss" :disabled="Boolean(dismissBusy[rp.id])"
                      @click="handleDismiss(r, rp)">忽略</button>
                  </div>
                  <!-- total 是**不分状态**的全量条数, list 在服务端封顶(50):
                       不说这一句, 面板上就分不清"就这些"与"还有一堆没显示" -->
                  <div v-if="detailBox[r.id].total > detailBox[r.id].list.length"
                    class="report-detail-hint">
                    等共 {{ detailBox[r.id].total }} 条
                  </div>
                </template>
              </div>
            </td>
          </tr>
          </template>
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
import PhFlag from '@icons/PhFlag.vue.mjs'
import {
  getAdminReviews, adminDeleteReview, adminRestoreReview, getReviewReports, dismissReport,
  REVIEW_REPORT_REASONS, ADMIN_PAGE_SIZES, ADMIN_PAGE_SIZE,
} from '../../api'
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

/**
 * 「只看被举报」在 URL 上的取值.
 *
 * 后端认的是**字面量 `true`** —— 其余任何值(包括不给、`1`、`yes`)一律当"不筛"
 * (读路径对未知值沉默放行, 见 AdminService.getReviewPage). 所以这里两态写成
 * `''`(不写进 URL)与 `'true'`, 而不是 `'1'/'0'`: 后者是"发出去也当没发".
 */
const REPORTED_ON = 'true'

/**
 * 举报理由的取值 → 中文标签.
 *
 * 标签只活在前端(后端存的是 SPAM/ABUSE 这些常量), 所以映射也就只能在这儿.
 * 取不到的键**原样显示**而不是留白: 那个值是真从接口来的, 显示出来至少能查,
 * 而空白会让人以为"这条没有理由".
 */
const REASON_LABELS = Object.fromEntries(REVIEW_REPORT_REASONS.map(o => [o.value, o.label]))

/** 明细行横跨整张表. 写死一个数而不是 `colspan="100"`: 数字对不上的表现是错位一格,
 *  一眼能看出来; 而 100 会让"加了一列忘了改"永远看不出来 */
const COLUMN_COUNT = 9

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
/** 只看被举报. 存的是 URL 上的**字符串**('' 或 'true'), 与 rating 同一个形状 */
const reported = ref('')
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
  () => keyword.value.trim() !== '' || rating.value !== '' || reported.value !== '',
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
    // 只认字面量 true, 其余(含 'false'/'1'/'yes')一律当不筛 —— 与后端同一条规矩,
    // 于是手改 URL 写成 `?reported=1` 时, 界面上的开关与后端的行为仍然一致
    reported: strParam(route.query.reported) === REPORTED_ON ? REPORTED_ON : '',
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
    keyword: keyword.value, rating: rating.value, reported: reported.value,
    sort: sort.value, order: order.value, page: page.value, limit: limit.value,
  }
}

function stateKey(s) {
  return [s.keyword, s.rating, s.reported, s.sort, s.order, s.page, s.limit].join('|')
}

function applyState(s) {
  keyword.value = s.keyword
  rating.value = s.rating
  reported.value = s.reported
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
  set('reported', reported.value)
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
      reported: reported.value || undefined,
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
 * 这一下, 摘要让他确认"删的是这一条"; 回复数则是这件事的**影响面**: 回复不会再被
 * 单列出来(列表按评论走), 所以移除一条挂着二十条回复的评论, 那些回复会跟着从
 * 用户侧一起消失.
 *
 * <p><b>V14 起措辞必须改: 这里不再"删除回复", 而且这件事是可以撤销的。</b>
 * 改前那句「并连带删除其下 N 条回复」描述的是硬删(CASCADE 真的把回复行删掉);
 * 现在后端是软删, 回复行一条都没动, 只是跟着藏起来了。确认框里留着那句话, 管理员
 * 会以为自己下手很重 —— 而实际上点错了还能在这一行上恢复。**代价写清楚, 出路也写清楚**:
 * 少了后半句, 一次误点看起来就是不可挽回的。
 */
function confirmText(r) {
  const text = (r.content || '').trim()
  const excerpt = text.length > CONFIRM_EXCERPT ? text.slice(0, CONFIRM_EXCERPT) + '…' : text
  const replies = typeof r.replyCount === 'number' ? r.replyCount : 0
  const tail = replies > 0 ? `，其下 ${replies} 条回复也会跟着隐藏` : ''
  return `确定移除用户 "${r.username}" 的评论「${excerpt || '（无文字）'}」${tail}？移除后可以在这一行恢复。`
}

/**
 * 恢复的确认文案**故意问得少**: 它是一个"撤销", 不是一个破坏性动作。
 *
 * 与删除同一个形状(带上是谁的、哪一条), 但不提回复数 —— 撤销不是在做决定,
 * 回复本来就会跟着回来, 报一个数字只会让人以为还要再确认一次别的什么。
 */
function restoreText(r) {
  const text = (r.content || '').trim()
  const excerpt = text.length > CONFIRM_EXCERPT ? text.slice(0, CONFIRM_EXCERPT) + '…' : text
  return `确定恢复用户 "${r.username}" 的评论「${excerpt || '（无文字）'}」？`
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
  } catch (e) { toast(e.response?.data?.message || '移除失败', 'error') }
}

/**
 * 恢复: 与删除完全对称的一条 —— 一样要确认、一样是重取当前页、一样带上别人说的原因。
 *
 * 重取而不是就地改那一行的 `deletedAt`: 与 handleDelete 同一条理由(服务端才知道
 * 这一行在新条件下还该不该留在这一页); 而且恢复之后这一行会不会**消失**(正在筛
 * 「只看被举报」, 而恢复让它重新回到队列里? 或者反过来), 那句话由服务端说.
 */
async function handleRestore(r) {
  if (!confirm(restoreText(r))) return
  try {
    await adminRestoreReview(r.id)
    await loadReviews()
  } catch (e) { toast(e.response?.data?.message || '恢复失败', 'error') }
}

// ==================== 举报明细 ====================

/**
 * 每条评论的明细盒子: `{ open, loading, list, total }`.
 *
 * 与评论区那几个「谁赞了」盒子同形, 但**重取列表时不清** —— 那几个装的是一份
 * "那批数据当时的样子"(赞数一变就过期了), 而这个装的是**这条评论自己的举报**:
 * 列表换一次序、翻一页, 那些举报一条没变. 清了只会让管理员的展开白点一次.
 * 唯一的例外是"别人同时在处理", 那种偏差由上面那次 handleDismiss 的重取兜住.
 */
const detailBox = ref({})
/** 正在忽略的那些**举报** id(不是评论 id): 一条评论下可以有好几条举报各点各的 */
const dismissBusy = ref({})

function reasonLabel(value) {
  return REASON_LABELS[value] || value
}

/** 拉某条评论的举报明细, 填进那个盒子 */
async function fetchReports(reviewId) {
  /* 必须从 detailBox 里**读回来**再改, 不能拿赋值时那个对象的引用 —— 与 fetchLikers
     同一条理由: 存进去的是普通对象, 读的时候才被包成响应式代理, 直接改原始对象
     不触发依赖, 表现是"点了没反应"而请求其实成功了 */
  const box = detailBox.value[reviewId]
  if (!box) return
  box.loading = true
  try {
    const res = await getReviewReports(reviewId)
    const d = res.data.data || {}
    box.list = d.list || []
    box.total = d.total || 0
  } catch (e) {
    // 拉不到就收起它 —— 留一块空白比收起来更让人以为"这条没被举报"
    box.open = false
    toast(e.response?.data?.message || '加载举报明细失败', 'error')
  } finally {
    box.loading = false
  }
}

/** 展开/收起一条评论的举报明细. 第一次展开才去拉 */
async function toggleDetail(r) {
  const existing = detailBox.value[r.id]
  if (existing) { existing.open = !existing.open; return }
  detailBox.value[r.id] = { open: true, loading: true, list: [], total: 0 }
  await fetchReports(r.id)
}

/**
 * 忽略一条举报.
 *
 * 忽略之后**重取当前页**, 不做本地 `filter` —— 与删除那条同一个理由:
 * `reportCount` 只数待处理的, 而且开着「只看被举报」时, 忽略掉最后一条待处理会让
 * 这一行**不再符合筛选**. 这两件事只有服务端说得准, 本地改一个数字等于把它的口径
 * 抄了第二份. 行还在、面板还开着的话, 再把明细也刷一次(明细里那条会变成"已忽略").
 */
async function handleDismiss(r, report) {
  if (dismissBusy.value[report.id]) return
  dismissBusy.value[report.id] = true
  try {
    await dismissReport(report.id)
    toast('已忽略', 'success')
    await loadReviews()
    if (detailBox.value[r.id]?.open) await fetchReports(r.id)
  } catch (e) {
    toast(e.response?.data?.message || '忽略失败', 'error')
  } finally {
    delete dismissBusy.value[report.id]
  }
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

/** 开关没有防抖 —— 它是一次点击, 不是一个字一个字打出来的 */
function toggleReported() {
  reported.value = reported.value === REPORTED_ON ? '' : REPORTED_ON
  return applyFilterChange()
}

function clearFilters() {
  keyword.value = ''
  rating.value = ''
  reported.value = ''
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
.action-cell { white-space: nowrap; }
.delete-btn {
  padding: 6px 16px; border: 1px solid var(--badge-red-fg); color: var(--badge-red-fg);
  background: var(--card); border-radius: 6px; cursor: pointer;
  font-size: 13px; white-space: nowrap;
}
/* 已移除的那一行: 一个中性色的状态标签 + 一个不带语义色的「恢复」。
   刻意不用红色 —— 红色在这一页代表"危险动作", 而"已经移除"是**既成事实**,
   给恢复按钮上红色更错: 它是一个撤销, 不是又一次破坏。 */
.removed-badge {
  display: inline-block; margin-right: 8px; padding: 3px 10px; border-radius: 999px;
  background: var(--tag-bg); color: var(--text-secondary);
  font-size: 12px; font-weight: 600;
}
.restore-btn {
  padding: 6px 16px; border: 1px solid var(--border); color: var(--text-secondary);
  background: var(--card); border-radius: 6px; cursor: pointer;
  font-size: 13px; white-space: nowrap;
}
.restore-btn:hover { border-color: var(--primary-line); color: var(--primary); }

/* 举报徽标: 可点(展开明细), 所以必须长得像个能点的东西 —— 底色比周围重一档,
   而不是像一列普通文字. */
.report-cell { white-space: nowrap; }
.report-badge {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--primary-line); background: var(--primary-soft);
  color: var(--primary); font-size: 12px; font-weight: 600; font-family: inherit;
}
.report-badge:hover { border-color: var(--primary); }
/* 理由那一小段用中性色: 它是补充信息, 与"被举报 N 次"不是同一层信息 */
.report-reason { color: var(--text-secondary); font-weight: 500; }
.report-none { color: var(--text-muted); }

/* 明细行. 底色比主行浅一档并与主行用同一根左边框连起来: 它是那一行的下一层,
   不是又一条评论 */
.report-detail-row > td { background: var(--tag-bg); }
.report-detail{ display:flex; flex-direction:column; gap:6px; padding:2px 0; }
.report-detail-hint{ color:var(--text-muted); font-size:12px; }
.report-item{
  display:flex; align-items:center; flex-wrap:wrap; gap:10px;
  font-size:13px; color:var(--text-secondary);
}
.report-item-reason{ font-weight:600; color:var(--text); }
.report-item-who{ font-weight:500; }
.report-item-time{ color:var(--text-muted); font-size:12px; font-variant-numeric:tabular-nums; }
/* 补充说明是管理员最该读的一段, 所以给它整行的宽度(换行时不会挤成一团) */
.report-item-note{ flex-basis:100%; color:var(--text); line-height:1.6; word-break:break-word; }
.report-item-done{ color:var(--text-muted); font-size:12px; }
.report-dismiss{
  padding:3px 12px; border-radius:999px; cursor:pointer; font-family:inherit;
  border:1px solid var(--border); background:var(--card);
  color:var(--text-secondary); font-size:12px;
}
.report-dismiss:hover{ border-color:var(--primary-line); color:var(--primary); }
.report-dismiss:disabled{ cursor:default; opacity:.55; }

/* 「清除筛选」是个普通按钮(不带语义色): 它是一个中性动作, 而不是"危险"或"主要" */
.admin-toolbar .action-btn { border: 1px solid var(--border); color: var(--text-secondary); }
.admin-toolbar .action-btn:hover { border-color: var(--primary-line); color: var(--primary); }
/* 「只看被举报」开着时用主色: 与"清除筛选"共用 .action-btn 这个类, 所以这一条必须
   排在它**后面**才压得住(同权重, 后写的赢) */
.admin-toolbar .report-toggle {
  display: inline-flex; align-items: center; gap: 6px;
}
.admin-toolbar .report-toggle.is-on {
  border-color: var(--primary); background: var(--primary-soft); color: var(--primary);
}

/* 改前这里还有一句「不是管理员就 router.push('/')」. 现在收在 AdminLayout 里,
   那边用 `<router-view v-if="authorized">` 挡着, 未授权时这个组件不会挂载. */
</style>
