<template>
  <div class="page-container">
    <div class="page-header">
      <h1>分类浏览</h1>
      <span v-if="hasSelection && tagTotal > 0" class="tag-total">共 {{ tagTotal }} 部</span>
    </div>

    <!-- 标签墙.
         这一块是从 Home.vue 整块搬过来的(连同下面"为什么不用 <button>"那段理由),
         形态与口径一个字没改 —— 搬家的目的是让首页不必再挤出第 6 个区块, 不是
         顺手重做一遍筛选条.

         分类是同一类问题的第三处: 一排 <span @click>, 键盘同样到不了.
         这几个没做成 <button>: interactions.css 与 tag-filter.css 里
         已有的 .tag-chip 样式(以及 :active 的按下反馈)是按 span 写的,
         换成 button 会把它们全部作废, 而这一批要修的不是样式. -->
    <div class="tag-filter">
      <span
        class="tag-chip"
        role="button"
        tabindex="0"
        :class="{ active: hasSelection && selectedTag === '' }"
        @click="selectTag('')"
        @keydown.enter.prevent="selectTag('')"
        @keydown.space.prevent="selectTag('')"
      >全部</span>
      <span
        class="tag-chip"
        v-for="tag in tags"
        :key="tag.name"
        role="button"
        tabindex="0"
        :class="{ active: selectedTag === tag.name }"
        @click="selectTag(tag.name)"
        @keydown.enter.prevent="selectTag(tag.name)"
        @keydown.space.prevent="selectTag(tag.name)"
      >
        {{ tag.name }}
        <span class="tag-count">({{ tag.count }})</span>
      </span>
    </div>

    <!-- 标签墙自己加载失败: 只在这一块头上报错, 不把整页判死.
         首页那里 /tags 挂了会让整个 loadHome 进错误页(它当时是首页的必要组成),
         而这一页的标签墙**不是**全部内容 —— 一挂就全白是错的. -->
    <div v-if="tagsError" class="tag-error">
      {{ tagsError }}
      <button class="tag-retry" @click="loadTags">重试</button>
    </div>

    <!-- 结果区. 首访(没选过任何标签)不渲染、也不发请求 —— 这一页首先是一堵标签墙. -->
    <div v-if="hasSelection" ref="resultsRef" class="tag-results">
      <LoadingSpinner v-if="tagLoading" text="加载中..." />
      <div v-else-if="tagError" class="tag-error">
        {{ tagError }}
        <button class="tag-retry" @click="loadTagPage">重试</button>
      </div>
      <template v-else-if="tagItems.length > 0">
        <div class="anime-grid">
          <AnimeCard v-for="(item, idx) in tagItems" :key="item.id" :anime="item" v-reveal="{ delay: idx * 40 }" />
        </div>
        <Pagination
          :current-page="tagPage"
          :total-pages="tagTotalPages"
          @change="changeTagPage"
        />
      </template>
      <!-- 空态不再挂 `v-else-if="selectedTag"`(首页那个写法)。首页的默认视图不含
           「全部」这个动作, 所以"全部 + 0 条"什么都不显示是对的; 这里「全部」是用户
           明确点出来的, 什么都不显示会让人以为页面没加载. -->
      <EmptyState
        v-else
        type="tag"
        :message="selectedTag ? `「${selectedTag}」暂无作品` : '站里还没有可浏览的作品'"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTags, getFiltered } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { strParam, pageParam } from '../utils/query'
import { useLatestOnly } from '../composables/useLatestOnly'
// (改前是 `const { vReveal } = useReveal()`, 而那个包装层只为了往 head 里注入样式)
import { vReveal } from '../directives/reveal'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const $router = useRouter()
/** 分类页的请求令牌: 只认最后一次, 见 composables/useLatestOnly.js */
const tagRequest = useLatestOnly()

const tags = ref([])
const tagsError = ref('')
const selectedTag = ref('')
/* 分类浏览是**服务端分页**: tagItems 是当前这一页, tagTotal 是这个分类的总条数.
   改前这里是"一次把服务端给的全都拿回来, 自己 slice 24 条一页" —— 而服务端那条
   /by-tag 封顶 50 条(BY_TAG_LIMIT), 于是任何分类都只有 3 页, 第 3 页还只有 2 张.
   改用 /filter: 它返回 {list, total, page}, total 是 SQL count 出来的真实值,
   切页也在 SQL 里. 排序口径不变 —— /by-tag 与 /filter?sort=date 走的是同一条
   仓储方法、同一套 ORDER_DATE_DESC_NULL_LAST(见 AnimeRepository 的注释). */
const tagItems = ref([])
const tagTotal = ref(0)
const tagPage = ref(1)
const tagError = ref('')
const tagLoading = ref(false)
const pageSize = 24
const resultsRef = ref(null)

/**
 * 「还没选过」与「选了全部」是两件事, 但它们的 URL 是同一条(/tags) —— 前者是
 * 目录页的样子, 后者是"我要看全部". 用一个只增不减的标志区分.
 *
 * 只增不减是刻意的: 点「全部」会写一次 URL(replace, 不带 tag), 上面那个 watch
 * 若把标志翻回 false, 结果区会在点下去的一瞬间消失 —— 自己把自己的动作撤掉.
 */
const browsing = ref(false)
const hasSelection = computed(() => browsing.value || selectedTag.value !== '')

const tagTotalPages = computed(() => Math.max(1, Math.ceil(tagTotal.value / pageSize)))

/**
 * 标签墙自己那一份加载.
 *
 * 独立于结果区: 这一页的标签墙不是"全部内容", 它挂了不该把结果区一起带走
 * (「全部」那条路根本不依赖 tags 列表). 首页那里两者挤在同一个 try 里, 是因为
 * 那边 /tags 只是首页众多请求中的一个, 一挂就该走整页错误态 —— 口径不同是
 * 因为页面不同.
 */
async function loadTags() {
  tagsError.value = ''
  try {
    const res = await getTags()
    tags.value = res.data.data || []
  } catch (e) {
    tagsError.value = loadErrorMessage(e, '加载标签')
    tags.value = []
  }
}

/**
 * 取当前分类的当前这一页.
 *
 * 「全部」(tag 为空串)走的是同一条接口 —— 不给 tag 参数就是不按标签筛, sort=date
 * 与首页那个 getRanking('date', 12) 是同一个序(两处共用 ORDER_DATE_DESC_NULL_LAST),
 * 区别只是这里分页、每页 24 条.
 */
async function loadTagPage() {
  // 令牌要在发请求**之前**取. 快速连点时会有多个请求同时在飞, 而谁先回来不确定 ——
  // 没有这个, 先发的那次后到就会把界面盖成上一个筛选条件的内容(见下面对 isCurrent
  // 的两处判断).
  const token = tagRequest.begin()
  tagError.value = ''
  tagLoading.value = true
  try {
    const res = await getFiltered({
      tag: selectedTag.value || undefined,
      sort: 'date',
      page: tagPage.value,
      limit: pageSize,
    })
    // 过期: 后面每一行写的都是别人的状态, 一行都不能执行 ——
    // 包括 tagLoading. 在这里关掉它, 正是"这一次转圈"会永远停不下来的原因
    // (还在飞的那次才是该关它的那个).
    if (!tagRequest.isCurrent(token)) return
    const body = res.data.data || {}
    tagItems.value = body.list || []
    tagTotal.value = body.total || 0
  } catch (e) {
    // 过期那次的失败同样不能写 tagError —— 否则界面上会留下一条属于上一个
    // 筛选条件的报错, 而它对应的请求早就没人关心了
    if (!tagRequest.isCurrent(token)) return
    tagError.value = loadErrorMessage(e, '加载分类')
    tagItems.value = []
    tagTotal.value = 0
  }
  // 能走到这里 ⇔ 上面没早退 ⇔ 令牌仍是最新的, 不用再判一次(见 useLatestOnly.js)
  tagLoading.value = false
}

/**
 * 把当前分类与页码写进 URL.
 *
 * 为什么筛选状态必须进 URL: App.vue 里 router-view 的 key 是 route.path, 而
 * tag/page 只改 query —— 组件**不会**重建, 所以这条 URL 主要是给分享/收藏/
 * 刷新用的, 以及让后退能真的退到上一个筛选(而不是退到别的页面).
 * 这与 Search.vue 的 syncQuery 是同一个理由.
 *
 * 默认值不写进去(空 tag / 第 1 页): ?tag=&page=1 是噪音, 与「没有这个参数」等价,
 * 写进去只会让地址栏变长、让分享出去的链接看起来比实际更特殊.
 * 用 replace 不用 push: 换分类/翻页不该在历史里堆层, 否则从第 5 页退回未筛选
 * 要按好几次后退.
 */
function syncTagQuery() {
  const next = { ...route.query }
  if (selectedTag.value) next.tag = selectedTag.value
  else delete next.tag
  if (tagPage.value > 1) next.page = String(tagPage.value)
  else delete next.page
  if (route.query.tag === next.tag && route.query.page === next.page) return
  $router.replace({ query: next })
}

/**
 * 反向: URL 变了 → 读回来再取数. 后退/前进走的是这条路.
 *
 * 两处刻意的地方:
 *   · tag 与 page 合成**一个** watch. 拆成两个的话,「换个分类同时回到第 1 页」
 *     会让两条都触发, 发两次请求.
 *   · 开头的早退判断是必须的 —— 我们自己调 syncTagQuery 写 URL 同样会让这个 watch
 *     触发, 不判断就变成「点一次 chip 发两次请求」. 判据是「URL 解析出来的值与当前
 *     ref 是否一致」: 写之前 ref 已经先改好了, 所以那次一定一致.
 *
 * 这一路**不碰 browsing**: 点「全部」写出的 URL 与"从没选过"是同一条, 若在这里
 * 把它翻回 false, 结果区会在点下去的一瞬间消失.
 */
watch(
  () => [route.query.tag, route.query.page],
  ([rawTag, rawPage]) => {
    const tag = strParam(rawTag)
    const page = pageParam(rawPage)
    if (tag === selectedTag.value && page === tagPage.value) return
    selectedTag.value = tag
    tagPage.value = page
    if (tag) browsing.value = true
    loadTagPage()
  },
)

/**
 * 把结果区送回视野.
 *
 * 改前的症状是「点分类标签会自动跳到顶部, 但是分类出的动漫是在页面底部」——
 * 那一次的真凶是 router 那条"任何 push/replace 都回顶"(现在由本页的
 * meta.scrollOnQueryChange = false 让掉了), 所以这里**不是**在补那个 bug:
 * router 不滚之后, 若这一页也不滚, 用户在窄屏上点完一个标签会什么都没有发生
 * 的样子(标签只高亮了一下, 结果在折线以下).
 *
 * block 用 'nearest' 而不是 'start': 这一页的标签墙只有 31 个 chip, 桌面上约 4 行,
 * 结果区本来就露在墙下面 —— 'nearest' 的语义是"已经看得见就不动", 于是这次调用
 * 是个空操作, 标签墙不会被顶出视野(用 'start' 就会, 换标签还得滚回来 —— 那是把
 * 一个毛病换成另一个). 窄屏墙约 8 行, 结果区顶在折线以下, 那时才滚.
 *
 * 刻意不传 behavior: 交给 base.css 的 html{scroll-behavior:smooth}, 而它在
 * prefers-reduced-motion 下被改成 auto —— 不用在 JS 里再查一次媒体查询.
 *
 * 不做"先量一下再决定"的优化: getBoundingClientRect 在 jsdom 里恒返回 0, 那样写
 * 这条行为在单测里永远是空跑, 等于没测. 落点会不会被 sticky 的导航栏盖住由
 * .tag-results 上的 scroll-margin-top 管.
 */
function scrollToResults() {
  resultsRef.value?.scrollIntoView({ block: 'nearest' })
}

/** 先改 ref → 再写 URL → 再取数. 顺序不能换: 写 URL 触发的那个 watch 靠
 *  "ref 已经等于 URL"来早退, ref 晚一步改就会多打一次请求. */
async function selectTag(tag) {
  selectedTag.value = tag
  tagPage.value = 1
  browsing.value = true
  syncTagQuery()
  // 结果区这一刻才刚挂上 DOM(v-if="hasSelection"), ref 之前是 null
  await nextTick()
  scrollToResults()
  await loadTagPage()
}

/**
 * 翻页.
 *
 * 这里仍然**不用 watch(tagPage)**: selectTag 也要把页码复位成 1, 而 watch 分不清
 * "复位导致的"和"用户点的", 从第 3 页换分类时两条路都会触发, 于是发两次请求.
 * 让每个改写 tagPage 的地方自己决定要不要取数, 歧义就不存在.
 */
async function changeTagPage(page) {
  tagPage.value = page
  syncTagQuery()
  await loadTagPage()
}

/**
 * 首访: 先把 URL 里的筛选收下, 再一并加载.
 *
 * 标签墙与结果**并行** —— loadTagPage 不依赖 tags 列表: tag 只是原样传给
 * /filter 的字符串.
 *
 * 没有 ?tag= 时不发 /filter: 这一页首先是一堵标签墙, 结果要用户点出来.
 * 那正是"只有标签墙"这个形态的意思, 也是它和首页那块最大的区别.
 */
onMounted(async () => {
  selectedTag.value = strParam(route.query.tag)
  tagPage.value = pageParam(route.query.page)
  if (selectedTag.value) browsing.value = true
  await Promise.all([loadTags(), hasSelection.value ? loadTagPage() : Promise.resolve()])
})
</script>

<style scoped>
/* 结果区. scroll-margin-top 是必须的, 不是美化: .navbar 是
   position:sticky; top:0; height:64px, 不留这段高度的话 scrollIntoView 的落点
   会被导航栏盖住. 80 = 64 + 16 呼吸. */
.tag-results { margin-top: 20px; scroll-margin-top: 80px; }
.tag-total { font-size: 13px; color: var(--text-secondary); }
/* 把首页那处 inline style 收成类 —— 同一个数字只该写一遍 */
.tag-count { font-size: 10px; opacity: .7; }

/* 分类那一块自己的错误态. 不复用整页那个 EmptyState type="error":
   标签墙没加载出来时结果区还是好的, 用整页的样式会把"只是这一块没加载出来"
   说成"这一页坏了" */
.tag-error {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 18px;
  margin-top: 16px;
  font-size: 13px; color: var(--text-secondary);
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.tag-retry {
  margin-left: auto;
  padding: 5px 14px;
  font-family: inherit; font-size: 12px; font-weight: 600;
  color: var(--text); background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  cursor: pointer; transition: border-color var(--transition), color var(--transition);
}
.tag-retry:hover { border-color: var(--primary); color: var(--primary); }
</style>
