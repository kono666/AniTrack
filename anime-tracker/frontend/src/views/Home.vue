<template>
  <div class="home-page">
    <!-- Hero Carousel -->
    <HeroBanner v-if="!error" :items="heroItems" />

    <div class="page-container">
      <!-- 继续看. 只在已登录、且确实有在看的番时渲染 —— 首页是公开页, 访客不该
           看到一个空盒子. 空数组时整块不渲染(不是渲染一个空标题).

           它**不在** loading / error 那套开关里面, 位置也在这两者之前: 这些数据
           与公开的排行接口没有任何关系, 排行榜挂了/还在转, 不该把「我昨天看到哪了」
           一起藏起来 —— 那恰恰是登录用户回首页最想要的那一件事. -->
      <section v-if="continueList.length > 0" class="home-block">
        <SectionHeader title="继续看" v-reveal />
        <HorizontalScroll>
          <!-- 类名刻意不叫 .hs-card: 首页已有的两条 .hs-card 用例(键盘可达性、
               点击跳转)拿 find('.hs-card') 取第一个, 继续看的卡片排在它们前面,
               混用同一个类会让那两条断言指到别的卡片上 -->
          <div
            v-for="item in continueList"
            :key="'c-' + item.subjectId"
            class="cw-card"
            role="button"
            tabindex="0"
            @click="open(item.subjectId)"
            @keydown.enter.prevent="open(item.subjectId)"
            @keydown.space.prevent="open(item.subjectId)"
          >
            <div class="cw-cover">
              <img
                :src="item.animeCover || fallbackImg"
                :alt="item.animeTitle"
                loading="lazy"
                @error="e => e.target.src = fallbackImg"
              />
            </div>
            <!-- 本地没缓存过这部番时后端**不发** animeTitle 这个键(不是给个 null),
                 兜底文案与个人页保持一致 -->
            <div class="cw-title">{{ item.animeTitle || '番剧 #' + item.subjectId }}</div>
            <ProgressBar :value="item.progress" :total="totalOf(item)" />
            <div class="cw-ep">
              第 {{ item.progress || 0 }} 集<template v-if="totalOf(item)"> / 共 {{ totalOf(item) }} 集</template>
            </div>
          </div>
        </HorizontalScroll>
      </section>

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

        <!-- 分类浏览原先在这里(首页的第 6 个区块), 整块搬成了 /tags 独立页 ——
             导航栏是它现在唯一的入口. 删掉的不只是模板: 首页因此也不再请求
             /api/bangumi/tags, 首屏少一个请求. -->
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import PhStar from '@icons/PhStar.vue.mjs'
import { getRanking, getCalendar, getContinueWatching } from '../api'
import { useUserStore } from '../stores/user'
import { loadErrorMessage } from '../utils/loadError'
import { effectiveEpisodes } from '../utils/episodes'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'
// 缓存必须活在组件实例之外, 否则"5 分钟 TTL"等于没有 —— 见 utils/homeCache.js
import { homeCache, HOME_CACHE_TTL } from '../utils/homeCache'
// 指令直接引进来用 —— 在 <script setup> 顶层, 这个命名会自动变成可写的 `v-reveal`
// (改前是 `const { vReveal } = useReveal()`, 而那个包装层只为了往 head 里注入样式)
import { vReveal } from '../directives/reveal'
import HeroBanner from '../components/HeroBanner.vue'
import HorizontalScroll from '../components/HorizontalScroll.vue'
import SectionHeader from '../components/SectionHeader.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import ProgressBar from '../components/ProgressBar.vue'

const $router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const heroItems = ref([])
const popularList = ref([])
const recentList = ref([])
const todayAnime = ref([])
const error = ref('')
/** 「继续看」的原始行, 形状与 /api/track/list 相同 —— 只有已登录时才有内容 */
const continueList = ref([])

// 今日放送默认只铺前 8 部, 其余收在「全部 N 部」后面(改前是直接丢掉)
const TODAY_LIMIT = 8
const showAllToday = ref(false)
const visibleToday = computed(() =>
  showAllToday.value ? todayAnime.value : todayAnime.value.slice(0, TODAY_LIMIT)
)

/** 打开详情页. 首页三类卡片都用它 —— 同一段跳转原先在模板里写了 4 遍,
 *  补键盘支持时要写 12 遍, 这正是该收成一个函数的时候 */
function open(id) { $router.push(`/anime/${id}`) }

/** 「继续看」每张卡的分母(集数).
 *
 *  为什么不能直接用 `item.totalEpisodes`: 那是条目接口的**声明值**, 而 Bangumi 对
 *  绝大多数条目填的就是 0(实测 29379 条里 29322 条), 于是 `ProgressBar` 的
 *  `v-if="total > 0"` 恒假、进度条整根不画 —— 只有极少数几条才画得出来, 看上去
 *  像"随机坏掉". 兜底与顺序写在 utils/episodes.js 一处。 */
const totalOf = (item) => effectiveEpisodes(item.totalEpisodes, item.episodeTotal)

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

onMounted(() => {
  // 两个各自跑、互不等待. 「继续看」**不进** loadHome 里那个 Promise.all: 一次超时
  // 不能把整个公开首页打成错误态(见 loadContinue 里的 catch)
  loadHome()
  loadContinue()
})

/**
 * 拉「继续看」. 已登录才有意义, 匿名直接就返回(连请求都不发).
 *
 * 三条硬约束, 每一条做错都很难在开发时看出来:
 *
 * 1. **不写进 homeCache, 也不受它的提前 return 影响.**
 *    homeCache 是模块级的、**不含用户维度**(见 utils/homeCache.js): 把「张三看到
 *    第 5 集」缓存进去, 换个账号登录首页显示的就是上一个人的进度. 而 loadHome 的
 *    缓存命中会在函数开头直接 return —— 所以这个请求必须单独发, 不能挂在它后面,
 *    否则「第一次进首页有、第二次没了」, 只有第二次挂载才看得见.
 *
 * 2. **失败不写 error.** 这一块是加分项, 后端抖一下的结果应该是"这块不显示",
 *    而不是整个首页变成错误页(排行榜那两条才该那样).
 *
 * 3. **过期 token 会走全局 401 处理被弹到登录页** —— 首页不开例外. 这是既有规则
 *    (首页今天就已经因为 getRanking 带着过期 token 而被弹走), 不在这里绕开.
 */
async function loadContinue() {
  if (!userStore.loggedIn) return
  try {
    const res = await getContinueWatching()
    continueList.value = res.data?.data || []
  } catch (e) { /* 见上面第 2 条 */ }
}

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
    loading.value = false
    return
  }

  let coreLoaded = false
  try {
    // Phase1: 核心数据先加载 (快, 不阻塞页面)
    const [rankRes, dateRes] = await Promise.all([
      getRanking('rank', 30),
      getRanking('date', 12),
    ])
    const rankData = rankRes.data.data || []
    const dateData = dateRes.data.data || []
    heroItems.value = rankData.slice(0, 6)
    popularList.value = rankData
    recentList.value = dateData
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
    homeCache.data = { hero: heroItems.value, popular: popularList.value, recent: recentList.value, today: todayAnime.value }
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

/* ── 继续看 ──
   宽度与 .hs-card 同一套节奏, 但卡片比它多两行(进度条 + 集号), 所以高度不写死 ——
   让它们自己撑开. 一行卡片的封面也一样是 3/4, 与站内其它卡片对齐. */
.cw-card {
  width: 160px; flex-shrink: 0;
  cursor: pointer;
  scroll-snap-align: start;
  transition: transform var(--transition);
}
.cw-card:hover { transform: translateY(-4px); }
.cw-cover {
  aspect-ratio: 3/4; border-radius: var(--radius-sm);
  overflow: hidden; background: var(--bg-secondary);
  margin-bottom: 8px;
}
.cw-cover img { width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.cw-card:hover .cw-cover img { transform: scale(1.06); }
.cw-title {
  font-size: 13px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-bottom: 6px;
}
.cw-ep { font-size: 11px; color: var(--text-muted); margin-top: 5px; }

/* ── Responsive ── */
@media (max-width: 768px) {
  .hs-card { width: 130px; }
  .cw-card { width: 130px; }
  .today-grid { grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 10px; }
}
@media (max-width: 480px) {
  .hs-card { width: 110px; }
  .cw-card { width: 110px; }
}
</style>
