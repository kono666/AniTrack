<template>
  <div class="home-page">
    <!-- Hero Carousel -->
    <HeroBanner v-if="!error" :items="heroItems" />

    <div class="page-container">
      <!-- Today's Schedule -->
      <section v-if="todayAnime.length > 0" class="home-block">
        <SectionHeader title="今日放送" v-reveal>
          <template #extra>
            <span class="today-date">{{ todayLabel }}</span>
            <!-- 改前这里写死 slice(0, 8): 当天排片第 9 部起直接丢掉, 而页面上
                 没有任何地方提示"还有更多"。不是折叠, 是消失。
                 修法上选了就地展开而不是「查看更多 →」: 站内没有一页能装下"今日放送",
                 /search 的 view 只认 rank 和 date, 硬指过去只会把人送到一个不相干的
                 列表。等真有那一页了再换成链接。 -->
            <button
              v-if="todayAnime.length > TODAY_LIMIT"
              class="today-toggle"
              @click="showAllToday = !showAllToday"
            >{{ showAllToday ? '收起' : `全部 ${todayAnime.length} 部` }}</button>
          </template>
        </SectionHeader>
        <div class="today-grid">
          <!-- 首页这几类卡片都只有 @click, 键盘到不了、读屏也不说它们能按.
               补 role/tabindex + 回车/空格(role=button 的约定是两个都触发).
               见 interactions.css 里那份 focus-visible 名单 —— 它早就把这些类
               列进去了, 只是一直没有元素能被 focus. -->
          <div
            v-for="item in visibleToday"
            :key="item.id"
            class="today-card"
            role="button"
            tabindex="0"
            @click="open(item.id)"
            @keydown.enter.prevent="open(item.id)"
            @keydown.space.prevent="open(item.id)"
          >
            <div class="today-cover">
              <img
                :src="item.images?.medium || item.images?.common || fallbackImg"
                :alt="item.nameCn"
                @error="e => e.target.src = fallbackImg"
              />
            </div>
            <div class="today-name">{{ item.nameCn || item.name }}</div>
          </div>
        </div>
      </section>

      <LoadingSpinner v-if="loading" />

      <!-- 加载失败. 排在内容前面, 而且把下面整块内容挡住 ——
           改前 Promise.all 一失败, 所有列表都是空的, 页面呈现出「这个站什么都没有」:
           空的热门、空的最近更新、只剩一个「全部」的分类栏. 用户不可能知道
           是后端挂了还是站里确实没数据 -->
      <EmptyState
        v-else-if="error"
        type="error"
        :message="error"
        action-label="重试"
        @action="loadHome"
      />

      <template v-else>
        <!-- Popular This Season -->
        <section class="home-block">
          <SectionHeader title="本季热门" more="/search?view=rank" v-reveal />
          <HorizontalScroll>
            <div
              v-for="(item, idx) in popularList"
              :key="item.id"
              class="hs-card"
              role="button"
              tabindex="0"
              @click="open(item.id)"
              @keydown.enter.prevent="open(item.id)"
              @keydown.space.prevent="open(item.id)"
            >
              <div class="hs-card-rank" :class="'rank-' + (idx + 1)">{{ idx + 1 }}</div>
              <div class="hs-card-img-wrap">
                <img
                  class="hs-card-img"
                  :src="item.images?.large || item.images?.common || fallbackImg"
                  :alt="item.nameCn"
                  loading="lazy"
                  @error="e => e.target.src = fallbackImg"
                />
                <div class="hs-card-score" v-if="item.rating?.score"><PhStar :size="10" weight="fill" /> {{ item.rating.score.toFixed(1) }}</div>
              </div>
              <div class="hs-card-title">{{ item.nameCn || item.name }}</div>
            </div>
          </HorizontalScroll>
        </section>

        <!-- Recently Updated -->
        <section class="home-block">
          <SectionHeader title="最近更新" more="/search?view=date" v-reveal />
          <HorizontalScroll>
            <div
              v-for="item in recentList"
              :key="'r-' + item.id"
              class="hs-card"
              role="button"
              tabindex="0"
              @click="open(item.id)"
              @keydown.enter.prevent="open(item.id)"
              @keydown.space.prevent="open(item.id)"
            >
              <div class="hs-card-img-wrap">
                <img
                  class="hs-card-img"
                  :src="item.images?.large || item.images?.common || fallbackImg"
                  :alt="item.nameCn"
                  loading="lazy"
                  @error="e => e.target.src = fallbackImg"
                />
                <div class="hs-card-badge" v-if="item.totalEpisodes">{{ item.totalEpisodes }}集</div>
              </div>
              <div class="hs-card-title">{{ item.nameCn || item.name }}</div>
              <div class="hs-card-year" v-if="item.date">{{ item.date.substring(0, 4) }}</div>
            </div>
          </HorizontalScroll>
        </section>

        <!-- Browse by Tag -->
        <section class="home-block">
          <SectionHeader title="分类浏览" v-reveal />
          <!-- 分类是同一类问题的第三处: 一排 <span @click>, 键盘同样到不了.
               这几个没做成 <button>: interactions.css 与 tag-filter.css 里
               已有的 .tag-chip 样式(以及 :active 的按下反馈)是按 span 写的,
               换成 button 会把它们全部作废, 而这一批要修的不是样式. -->
          <div class="tag-filter">
            <span
              class="tag-chip"
              role="button"
              tabindex="0"
              :class="{ active: selectedTag === '' }"
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
              <span style="font-size:10px;opacity:.7;">({{ tag.count }})</span>
            </span>
          </div>
          <div v-if="tagError" class="tag-error">
            {{ tagError }}
            <button class="tag-retry" @click="loadTagPage">重试</button>
          </div>
          <div v-else-if="tagItems.length > 0">
            <div class="anime-grid">
              <AnimeCard v-for="(item, idx) in tagItems" :key="item.id" :anime="item" v-reveal="{ delay: idx * 40 }" />
            </div>
            <Pagination
              :current-page="tagPage"
              :total-pages="tagTotalPages"
              @change="changeTagPage"
            />
          </div>
          <EmptyState v-else-if="selectedTag" type="tag" message="该分类暂无数据" />
        </section>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PhStar from '@icons/PhStar.vue.mjs'
import { getRanking, getCalendar, getTags, getFiltered } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { strParam, pageParam } from '../utils/query'
import { useLatestOnly } from '../composables/useLatestOnly'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'
// 缓存必须活在组件实例之外, 否则"5 分钟 TTL"等于没有 —— 见 utils/homeCache.js
import { homeCache, HOME_CACHE_TTL } from '../utils/homeCache'
// 指令直接引进来用 —— 在 <script setup> 顶层, 这个命名会自动变成可写的 `v-reveal`
// (改前是 `const { vReveal } = useReveal()`, 而那个包装层只为了往 head 里注入样式)
import { vReveal } from '../directives/reveal'
import HeroBanner from '../components/HeroBanner.vue'
import HorizontalScroll from '../components/HorizontalScroll.vue'
import SectionHeader from '../components/SectionHeader.vue'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const $router = useRouter()
const route = useRoute()
/** 分类页的请求令牌: 只认最后一次, 见 composables/useLatestOnly.js */
const tagRequest = useLatestOnly()
const loading = ref(true)
const heroItems = ref([])
const popularList = ref([])
const recentList = ref([])
const todayAnime = ref([])
const tags = ref([])
const selectedTag = ref('')
const error = ref('')
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
const pageSize = 24

// 今日放送默认只铺前 8 部, 其余收在「全部 N 部」后面(改前是直接丢掉)
const TODAY_LIMIT = 8
const showAllToday = ref(false)
const visibleToday = computed(() =>
  showAllToday.value ? todayAnime.value : todayAnime.value.slice(0, TODAY_LIMIT)
)

/** 打开详情页. 卡片和分类标签都用它 —— 同一段跳转原先在模板里写了 4 遍,
 *  补键盘支持时要写 12 遍, 这正是该收成一个函数的时候 */
function open(id) { $router.push(`/anime/${id}`) }

const tagTotalPages = computed(() => Math.max(1, Math.ceil(tagTotal.value / pageSize)))

/** 给人看的一行日期. 这里的「周三」是**显示用**的中文简写, 不是拿去匹配的键 ——
 *  匹配用的是 bgmWeekdayId(), 那是另一套写法, 理由见它上面那段 */
const todayLabel = computed(() => {
  const d = new Date()
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${d.getMonth() + 1}月${d.getDate()}日 ${weekdays[d.getDay()]}`
})

/**
 * 「今天是星期几」在 Bangumi 日历里的编号.
 *
 * 日历是每天一格, 每格的 weekday 长这样: {en:'Mon', cn:'星期一', ja:'月曜日', id:1},
 * id 从 1(周一) 到 7(周日); 而 JS 的 getDay() 是 0(周日) 到 6(周六) —— 两者差一次换算,
 * 就是下面那一行.
 *
 * 改前比的是 cn, 拿 '周三' 去比接口回的 '星期三': 字符串对不上, find 永远返回
 * undefined, todayAnime 恒为空数组, 于是整个「今日放送」被 v-if 藏掉 —— 一块内容
 * 消失得悄无声息, 没有报错, 也没有任何测试会红(当时的假数据是用被测代码同一个
 * WEEKDAYS 常量拼的, 见 Home.test.js 里的 calendarForToday).
 *
 * 两边都 String(): 后端把 weekday 收成了 Map<String,String>(BangumiDTO.CalendarDay),
 * Jackson 会把 JSON 里的数字 3 强制转成字符串 "3" —— 所以这里拿到的是 "3" 不是 3.
 * 只把 cn 换成 id、写成 === 3 是不够的, 那样仍然对不上.
 */
function bgmWeekdayId() {
  const dow = new Date().getDay()
  return String(dow === 0 ? 7 : dow)
}

/**
 * 取当前分类的当前这一页.
 *
 * 「全部」(tag 为空串)走的是同一条接口 —— 不给 tag 参数就是不按标签筛, sort=date
 * 与改前那个 getRanking('date', 200) 是同一个序(两处共用 ORDER_DATE_DESC_NULL_LAST),
 * 区别只是改前封顶 200 条、现在有真实 total.
 */
async function loadTagPage() {
  // 令牌要在发请求**之前**取. 快速连点时会有多个请求同时在飞, 而谁先回来不确定 ——
  // 没有这个, 先发的那次后到就会把界面盖成上一个筛选条件的内容(见下面对 isCurrent
  // 的两处判断).
  const token = tagRequest.begin()
  tagError.value = ''
  try {
    const res = await getFiltered({
      tag: selectedTag.value || undefined,
      sort: 'date',
      page: tagPage.value,
      limit: pageSize,
    })
    // 过期: 后面每一行写的都是别人的状态, 一行都不能执行
    if (!tagRequest.isCurrent(token)) return
    const body = res.data.data || {}
    tagItems.value = body.list || []
    tagTotal.value = body.total || 0
  } catch (e) {
    // 过期那次的失败同样不能写 tagError —— 否则界面上会留下一条属于上一个
    // 筛选条件的报错, 而它对应的请求早就没人关心了
    if (!tagRequest.isCurrent(token)) return
    // 改前这里只有 console.error: 页面会停在"这个分类没有数据"那个空态上,
    // 把一次加载失败说成了一句事实
    tagError.value = loadErrorMessage(e, '加载分类')
    tagItems.value = []
    tagTotal.value = 0
  }
}

/**
 * 把当前分类与页码写进 URL.
 *
 * 为什么筛选状态必须进 URL: App.vue 里 router-view 的 key 是 route.path, 路径一变
 * 组件就重建 —— 从首页点进详情再按后退, Home 是**重新挂载**的. 筛选只活在组件里
 * 的话, 那时它已经是一个全新的「未筛选」页了. 这与 Search.vue 的 syncQuery 是同一个
 * 理由(那边早就把 q/page 写进了 URL, 这里一直漏着).
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
 *     这与改前那句「刻意不用 watch(tagPage)」防的是同一件事 —— 当时防的是页码 ref,
 *     现在防的是 URL, 换了个对象而已, 歧义的形状没变.
 */
watch(
  () => [route.query.tag, route.query.page],
  ([rawTag, rawPage]) => {
    const tag = strParam(rawTag)
    const page = pageParam(rawPage)
    if (tag === selectedTag.value && page === tagPage.value) return
    selectedTag.value = tag
    tagPage.value = page
    loadTagPage()
  },
)

/** 先改 ref → 再写 URL → 再取数. 顺序不能换: 写 URL 触发的那个 watch 靠
 *  "ref 已经等于 URL"来早退, ref 晚一步改就会多打一次请求. */
async function selectTag(tag) {
  selectedTag.value = tag
  tagPage.value = 1
  syncTagQuery()
  await loadTagPage()
}

/**
 * 翻页.
 *
 * 这里仍然**不用 watch(tagPage)**(改前那段注释的结论保留): selectTag 也要把页码
 * 复位成 1, 而 watch 分不清"复位导致的"和"用户点的", 从第 3 页换分类时两条路都会
 * 触发, 于是发两次请求. 让每个改写 tagPage 的地方自己决定要不要取数, 歧义就不存在.
 */
function changeTagPage(page) {
  tagPage.value = page
  syncTagQuery()
  loadTagPage()
}

/**
 * 首访: 先把 URL 里的筛选收下, 再一并加载.
 *
 * 改前这里是 `onMounted(loadHome)` —— **分类区在首访时根本没有初次加载**:
 * loadTagPage 只被 selectTag / changeTagPage / 重试按钮调用过, 于是模板里那句
 * `v-else-if="tagItems.length > 0"` 首访必然为假, 分类区只剩标题和一排 chip;
 * 而 v-else-if="selectedTag" 的空态也出不来(此时 selectedTag 是空串), 所以连
 * 一句「暂无数据」都没有. 用户必须先点一下 chip 才知道那里会出东西.
 *
 * 两件事可以并行: loadTagPage 不依赖 tags 列表 —— tag 只是原样传给 /filter 的字符串.
 */
onMounted(async () => {
  selectedTag.value = strParam(route.query.tag)
  tagPage.value = pageParam(route.query.page)
  await Promise.all([loadHome(), loadTagPage()])
})

// 单独取名(原来是直接写在 onMounted 里的匿名函数)是为了让错误态上的「重试」
// 有东西可调 —— 重试就是把这一次加载原样再跑一遍
async function loadHome() {
  error.value = ''
  // 命中缓存直接渲染. 缓存对象在模块作用域(utils/homeCache.js), 跨组件实例有效 ——
  // 改前它是这里的一个对象, 跟着组件实例一起被卸载, TTL 从来没有生效过
  if (homeCache.data && (Date.now() - homeCache.time) < HOME_CACHE_TTL) {
    const c = homeCache.data
    heroItems.value = c.hero; popularList.value = c.popular
    recentList.value = c.recent; todayAnime.value = c.today
    tags.value = c.tags; loading.value = false
    return
  }

  let coreLoaded = false
  try {
    // Phase1: 核心数据先加载 (快, 不阻塞页面)
    const [rankRes, dateRes, tagRes] = await Promise.all([
      getRanking('rank', 30),
      getRanking('date', 12),
      getTags(),
    ])
    const rankData = rankRes.data.data || []
    const dateData = dateRes.data.data || []
    heroItems.value = rankData.slice(0, 6)
    popularList.value = rankData
    recentList.value = dateData
    tags.value = tagRes.data.data || []
    loading.value = false  // 页面立即可见
    coreLoaded = true
  } catch (e) {
    // 改前只 console.error: 于是所有列表保持空, 页面看起来像「站里没数据」
    error.value = loadErrorMessage(e, '加载首页')
    loading.value = false
  }

  // Phase2: 日历后台加载 (慢, 不阻塞)
  try {
    const calRes = await getCalendar()
    const calData = calRes.data.data || []
    const todayEntry = calData.find(d => String(d.weekday?.id) === bgmWeekdayId())
    todayAnime.value = todayEntry?.items || []
  } catch (e) { /* 日历失败不影响主页 */ }

  // 写缓存 —— 只在核心数据真的加载成功时才写.
  //
  // 改前这里是无条件写的: 首页加载失败时, 这份「全是空列表」的结果会被当成有效
  // 数据缓存 5 分钟. 于是错误被缓存成了事实 —— 用户点重试(或者切走再回来)拿到的
  // 还是那份空缓存, 连一次新的请求都不会发出去.
  if (coreLoaded) {
    homeCache.data = { hero: heroItems.value, popular: popularList.value, recent: recentList.value, today: todayAnime.value, tags: tags.value }
    homeCache.time = Date.now()
  }
}
</script>

<style scoped>
.home-page { padding-bottom: 40px; }

/* ── 分区 ──
   标题本身交给 SectionHeader(见 assets/css/section-header.css), 这里只管
   分区之间的节奏。改前三块的间距各写各的(.today-section 36px 下边距、
   .browse-section 8px 上边距、.hs-section 36px 下边距), 叠起来是 44 还是 36
   取决于谁先出现 —— 现在只有一个数。 */
.home-block { margin-bottom: 40px; }

/* 那两个元素是插进 SectionHeader 的 extra 插槽里的, 按 Vue 的规则它们编译在
   Home 的作用域下, 所以这份 scoped 样式照常命中, 不需要 :deep() */
.today-date { font-size: 13px; color: var(--text-secondary); font-weight: 600; }
.today-toggle {
  border: none; background: none; padding: 0; cursor: pointer;
  font-family: inherit; font-size: 13px; font-weight: 600;
  color: var(--text-secondary); transition: color var(--transition);
}
.today-toggle:hover { color: var(--text); }

/* 分类那一块自己的错误态. 不复用整页那个 EmptyState type="error":
   整页失败时首页的其他分区还是好的, 用整页的样式会把"只是这一块没加载出来"
   说成"这一页坏了" */
.tag-error {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 18px;
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

/* ── Today's Schedule ── */
.today-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(130px, 1fr));
  gap: 12px;
}
.today-card { cursor: pointer; transition: transform var(--transition); }
.today-card:hover { transform: translateY(-3px); }
.today-cover {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 6px;
}
.today-cover img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.today-card:hover .today-cover img { transform: scale(1.06); }
.today-name {
  font-size: 12px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ── Horizontal Scroll Cards ── */
.hs-card {
  width: 160px; flex-shrink: 0;
  cursor: pointer; position: relative;
  scroll-snap-align: start;
  transition: transform var(--transition);
}
.hs-card:hover { transform: translateY(-4px); }
.hs-card-rank {
  position: absolute; top: -8px; left: -8px; z-index: 3;
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  /* 改前 font-weight:900 落在**正文体**上 —— IBM Plex Sans 最粗只到 700, 900 是
     浏览器伪粗体合成的(笔画被机械加粗)。榜位数字是"显示级"的元素, 所以挪到显示体,
     那里 800 是真的字重。判据见下面其它几处: 只有 h1–h3 会从 base.css 继承显示体,
     其余元素想要显示体就得自己写。 */
  font-family: var(--font-display);
  font-size: 15px; font-weight: 800; color: var(--rank-fg);
  background: var(--text-muted);
  box-shadow: 0 2px 8px rgba(0,0,0,.3);
}
/* 金银铜是整个配色方案里**唯一**允许出现彩色的地方 —— 前三名值得一个颜色,
   其余一切靠墨色和留白说话。改成纯色不用渐变: 改前是 Tailwind 的 amber/slate
   渐变(#f59e0b→#d97706 / #94a3b8→#64748b), 那种"给什么都加个渐变"的手法是
   上一个版本的模板签名。
   第 4 名往后不给颜色, 用中性的 --text-muted —— 榜位颜色本身也是信息。 */
.hs-card-rank.rank-1 { background: var(--rank-1); }
.hs-card-rank.rank-2 { background: var(--rank-2); }
.hs-card-rank.rank-3 { background: var(--rank-3); }
.hs-card-img-wrap {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 8px; position: relative;
}
.hs-card-img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.hs-card:hover .hs-card-img { transform: scale(1.06); }
.hs-card-score {
  position: absolute; bottom: 6px; right: 6px;
  font-size: 10px; padding: 2px 7px;
  background: var(--cover-scrim); color: var(--cover-star);
  border-radius: 4px; font-weight: 700;
}
.hs-card-badge {
  position: absolute; bottom: 6px; left: 6px;
  font-size: 10px; padding: 2px 7px;
  background: var(--cover-scrim); color: var(--cover-fg);
  border-radius: 4px; font-weight: 600;
}
.hs-card-title {
  font-size: 13px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hs-card-year {
  font-size: 11px; color: var(--text-muted); margin-top: 2px;
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .hs-card { width: 130px; }
  .today-grid { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; }
}
@media (max-width: 480px) {
  .hs-card { width: 110px; }
}
</style>
