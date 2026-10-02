<template>
  <div class="page-container">
    <div class="page-header">
      <h1>分类浏览</h1>
      <span v-if="total > 0" class="browse-total">共 {{ total }} 部</span>
    </div>

    <!-- 窄屏才出现的抽屉开关. 桌面上左栏一直摆着, 这个按钮 display:none -->
    <button type="button" class="filter-toggle" :aria-expanded="sidebarOpen ? 'true' : 'false'" @click="sidebarOpen = !sidebarOpen">
      筛选
      <span v-if="activeCount > 0" class="filter-toggle-count">{{ activeCount }}</span>
    </button>

    <div class="browse-layout">
      <!-- 左栏: 七组条件. 四个多选组来自前端的封闭词表(constants/filterDimensions.js);
           年份与状态是单选、来自 /filter-meta; 季度也是单选, 但四个选项写死在前端
           (一年永远四个季度, 让后端为它多算一份投影换不到任何东西). -->
      <aside class="filter-panel" :class="{ open: sidebarOpen }">
        <section v-for="group in FILTER_GROUPS" :key="group.key" class="filter-group">
          <div class="filter-group-head">
            <h2>{{ group.label }}</h2>
            <button
              v-if="selection[group.key].length"
              type="button"
              class="filter-clear"
              @click="clearGroup(group.key)"
            >清除</button>
          </div>
          <div class="filter-options">
            <button
              v-for="option in visibleOptions(group)"
              :key="option.value"
              type="button"
              class="filter-option"
              :class="{ active: selection[group.key].includes(option.value) }"
              :aria-pressed="selection[group.key].includes(option.value) ? 'true' : 'false'"
              @click="toggleOption(group, option.value)"
            >{{ option.label }}</button>
            <button
              v-if="isCollapsed(group)"
              type="button"
              class="filter-option filter-more"
              @click="expandedGroups = { ...expandedGroups, [group.key]: true }"
            >展开全部</button>
          </div>
        </section>

        <!-- 年份 / 季度 / 状态: 单选. 年份与状态来自 /filter-meta, 而不是写死
             2026..2006 —— 明年不用改代码, 而且天然只列出库里真有数据的年份. -->
        <section class="filter-group">
          <div class="filter-group-head">
            <h2>年份</h2>
            <button v-if="selection.year" type="button" class="filter-clear" @click="clearGroup('year')">清除</button>
          </div>
          <!-- 这一组的加载失败只影响它自己: 结果区照常出卡片, 见 loadMeta 的注释 -->
          <div v-if="metaError" class="filter-error filter-error-inline">
            {{ metaError }}
            <button type="button" class="filter-retry" @click="loadMeta">重试</button>
          </div>
          <div v-else class="filter-options">
            <button
              v-for="year in years"
              :key="year"
              type="button"
              class="filter-option"
              :class="{ active: selection.year === year }"
              :aria-pressed="selection.year === year ? 'true' : 'false'"
              @click="toggleSingle('year', year)"
            >{{ year }}</button>
          </div>
        </section>

        <!-- 季度: 一年固定四个, 不来自 /filter-meta —— 所以这一组**没有**加载失败
             这一说, 也就刻意不摆错误条(与上面两组不同). 它跟着年份走: 没选年份时
             四个按钮都禁用, 因为按钮的值要拼成 `2024-Q4`, 缺了年份拼出来是 `-Q1` ——
             后端认不出、只会静默筛空. -->
        <section class="filter-group">
          <div class="filter-group-head">
            <h2>季度</h2>
            <button v-if="selection.season" type="button" class="filter-clear" @click="clearGroup('season')">清除</button>
          </div>
          <div class="filter-options">
            <button
              v-for="quarter in SEASON_QUARTERS"
              :key="quarter.q"
              type="button"
              class="filter-option"
              :disabled="!selection.year"
              :class="{ active: selection.season === quarterValue(quarter.q) }"
              :aria-pressed="selection.season === quarterValue(quarter.q) ? 'true' : 'false'"
              @click="toggleSingle('season', quarterValue(quarter.q))"
            >{{ quarter.label }}</button>
          </div>
          <p v-if="!selection.year" class="filter-hint">先选年份</p>
        </section>

        <section class="filter-group">
          <div class="filter-group-head">
            <h2>状态</h2>
            <button v-if="selection.status" type="button" class="filter-clear" @click="clearGroup('status')">清除</button>
          </div>
          <div v-if="metaError" class="filter-error filter-error-inline">
            {{ metaError }}
            <button type="button" class="filter-retry" @click="loadMeta">重试</button>
          </div>
          <div v-else class="filter-options">
            <button
              v-for="item in statuses"
              :key="item.value"
              type="button"
              class="filter-option"
              :class="{ active: selection.status === item.value }"
              :aria-pressed="selection.status === item.value ? 'true' : 'false'"
              @click="toggleSingle('status', item.value)"
            >{{ item.label }}</button>
          </div>
        </section>
      </aside>

      <!-- 右栏: 结果. 一进来就发请求(首屏是"什么都不筛的第一页"), 不是等用户点出来 ——
           左右两栏的形态下, 右栏空着看起来就是坏了. -->
      <div ref="resultsRef" class="filter-results">
        <LoadingSpinner v-if="loading" text="加载中..." />
        <div v-else-if="error" class="filter-error">
          {{ error }}
          <button type="button" class="filter-retry" @click="loadResults">重试</button>
        </div>
        <template v-else-if="items.length > 0">
          <div class="anime-grid">
            <AnimeCard v-for="(item, idx) in items" :key="item.id" :anime="item" v-reveal="{ delay: idx * 40 }" />
          </div>
          <Pagination :current-page="page" :total-pages="totalPages" @change="changePage" />
        </template>
        <EmptyState v-else type="tag" :message="emptyMessage" />
      </div>
    </div>

    <!-- 抽屉打开时的遮罩. 只在窄屏存在(桌面上 .filter-scrim 是 display:none) -->
    <div v-if="sidebarOpen" class="filter-scrim" @click="sidebarOpen = false"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getFiltered, getFilterMeta } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { strParam, pageParam, arrParam } from '../utils/query'
import {
  FILTER_GROUPS, MULTI_KEYS, SINGLE_KEYS, SEASON_QUARTERS,
  tagNamesOf, labelsOf, isKnownValue, seasonYear,
} from '../constants/filterDimensions'
import { useLatestOnly } from '../composables/useLatestOnly'
import { vReveal } from '../directives/reveal'
import AnimeCard from '../components/AnimeCard.vue'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const route = useRoute()
const $router = useRouter()
/** 分类页的请求令牌: 只认最后一次, 见 composables/useLatestOnly.js */
const request = useLatestOnly()

const pageSize = 24

/** 一年最多列出几个年份档. "近 20 年"是拍的板; 库里混着 2027/2028/2029 那批未上映的 */
const YEAR_WINDOW = 20

/**
 * 六组条件的当前值.
 *
 * 四个多选组是 slug 数组, 年份/季度/状态是字符串(空串 = 没选) —— 语义不同, 所以
 * 不硬凑成同一种形状. 这个对象是**唯一**的真相: URL 与请求都由它推导出来.
 *
 * 两半都按 key 表铺开而不是逐字写死: 加一个维度时 `MULTI_KEYS` / `SINGLE_KEYS`
 * 各改一行, 而这五处(本对象 / readQuery / syncQuery / selectionKey / activeCount)
 * 之所以都跟着动, 是因为它们都循环同一份 key 表. 逐字写死的话, 漏掉的那一处
 * 不会报错, 只会让条件"选得上、读不回来".
 */
const EMPTY_SELECTION = {
  ...Object.fromEntries(MULTI_KEYS.map((key) => [key, []])),
  ...Object.fromEntries(SINGLE_KEYS.map((key) => [key, ''])),
}
const selection = ref({ ...EMPTY_SELECTION })

const items = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const resultsRef = ref(null)

/** 年份与状态是 /filter-meta 给的 —— 它们与结果区各自独立地成功或失败 */
const years = ref([])
const statuses = ref([])
const metaError = ref('')

/** 窄屏抽屉; 桌面端一直是 false(开关按钮 display:none) */
const sidebarOpen = ref(false)
/** 题材默认折到 12 个, 「展开全部」之后不再折 */
const expandedGroups = ref({})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))

/**
 * 第 q 个季度(1..4)的按钮值 `yyyy-Qq`. 年份拼在里面而不是只存 `Q4` —— URL 要能
 * 深链、也能被直接调 API 的人看懂(见 filterDimensions.js 的 SEASON_QUARTERS).
 *
 * ⚠️ 传进来的是**季度序号**不是月份: 传月份会拼出 `2024-Q10`, 后端按"认不出的值"
 * 处理 → 静默筛空.
 *
 * 没选年份时会拼出 `-Q1`: 所以按钮 `:disabled` 且 pickSeason 自己也挡一道, 两道闸
 * 各自都挡得住.
 */
function quarterValue(q) {
  return `${selection.value.year}-Q${q}`
}

const activeCount = computed(
  () => MULTI_KEYS.reduce((n, key) => n + selection.value[key].length, 0)
    + SINGLE_KEYS.filter((key) => selection.value[key]).length,
)

/** 空态文案说"用户点的那个词", 不是 slug —— 屏幕上没有 mecha 这个词 */
const emptyMessage = computed(() => {
  if (activeCount.value === 0) return '站里还没有可浏览的作品'
  const labels = MULTI_KEYS.flatMap((key) => labelsOf(key, selection.value[key]))
  if (selection.value.year) labels.push(selection.value.year)
  if (selection.value.season) {
    // 屏幕上没有 "2024-Q4" 这种东西 —— 说成「2024年10月」, 与按钮上那个标签一致
    const q = SEASON_QUARTERS.find((x) => quarterValue(x.q) === selection.value.season)
    labels.push(q ? `${seasonYear(selection.value.season)}年${q.label}` : selection.value.season)
  }
  if (selection.value.status) {
    labels.push(statuses.value.find((s) => s.value === selection.value.status)?.label || selection.value.status)
  }
  return `「${labels.join(' + ')}」暂无作品`
})

function visibleOptions(group) {
  if (!isCollapsed(group)) return group.options
  return group.options.slice(0, group.collapsedCount)
}

function isCollapsed(group) {
  return Boolean(group.collapsedCount) && !expandedGroups.value[group.key]
}

/**
 * URL → 选择状态.
 *
 * 认不出来的 slug 会被 isKnownValue 丢掉: 它只可能来自被手改过的 URL, 而带着它去
 * 请求会让那一组静默变成空结果(后端对"给了名字却一个都没解析出来"的处理就是空).
 * 丢掉之后那一组等于没选, 是更合理的解释.
 */
function readQuery() {
  const next = { ...EMPTY_SELECTION }
  for (const key of MULTI_KEYS) {
    next[key] = arrParam(route.query[key]).filter((slug) => isKnownValue(key, slug))
  }
  for (const key of SINGLE_KEYS) {
    next[key] = strParam(route.query[key])
  }
  // 季度自带年份(`2024-Q4`), 而 URL 上可能根本没写 year, 或者写的与它不一致
  // (手改地址栏). 一律**以季度为准**: 按钮亮着的必须是真正生效的那一年, 否则会
  // 出现"左边亮着 2023、结果却是 2024 年秋季"这种说不清的状态.
  const fromSeason = seasonYear(next.season)
  if (fromSeason) next.year = fromSeason
  return { selection: next, page: pageParam(route.query.page) }
}

/** 选择状态的比较键. 逐字段比对象太啰嗦, 而 JSON.stringify 对 key 顺序敏感 */
function selectionKey(sel) {
  return MULTI_KEYS.map((key) => sel[key].join(',')).join('|')
    + '#' + SINGLE_KEYS.map((key) => sel[key]).join('#')
}

/**
 * 一个多选组选中的**标签名** → 逗号串, 给它去走线; 空组返回 undefined, 也就是"整个参数不发".
 *
 * ⚠️ 这个函数的返回值**不能拿去写 URL**. URL 上走的是 slug(`mecha,fantasy`),
 * 这里是标签名(`机战,萝卜,机甲,机器人,奇幻,魔幻,玄幻`) —— 写混了的话, 下一轮
 * readQuery 会拿标签名去 isKnownValue 里查, 一个都认不出来, 于是**条件刚选上就被
 * 自己抹掉**: URL 看着变了, 请求却回到了没筛的状态.
 */
function csvOf(key) {
  const names = tagNamesOf(key, selection.value[key])
  return names.length > 0 ? names.join(',') : undefined
}

/**
 * 取当前条件下的这一页.
 *
 * 排序沿用 `sort=date`(与改动前一致, 本轮不加排序控件).
 *
 * 令牌纪律与 c77 一字不差: `begin()` 在发请求**之前**取, `await` 之后每一行都先
 * 过 `isCurrent`, 而过期分支里**绝不能写 loading = false** —— 那会让"这一次转圈"
 * 停在界面上(还在飞的那次才是该关它的那个, 见 useLatestOnly.js).
 */
async function loadResults() {
  const token = request.begin()
  error.value = ''
  loading.value = true
  try {
    const res = await getFiltered({
      genre: csvOf('genre'),
      medium: csvOf('medium'),
      source: csvOf('source'),
      region: csvOf('region'),
      year: selection.value.year || undefined,
      season: selection.value.season || undefined,
      status: selection.value.status || undefined,
      sort: 'date',
      page: page.value,
      limit: pageSize,
    })
    if (!request.isCurrent(token)) return
    const body = res.data.data || {}
    items.value = body.list || []
    total.value = body.total || 0
  } catch (e) {
    if (!request.isCurrent(token)) return
    error.value = loadErrorMessage(e, '加载分类')
    items.value = []
    total.value = 0
  }
  // 能走到这里 ⇔ 上面没早退 ⇔ 令牌仍是最新的, 不用再判一次
  loading.value = false
}

/**
 * 年份与状态的取值.
 *
 * 与结果区**各自独立**地失败: 这一路挂了只让年份/状态两组显示错误条, 右栏照常出
 * 卡片. 两者挤在同一个 try 里的话, `/filter-meta` 一挂整页就没有内容了, 而它
 * 只是两个下拉框的取值来源.
 *
 * 年份切片: 只留不晚于今年的, 再取前 20 个. 后端给的是**库里真实存在的**年份前缀
 * 倒序全量(128 个), 里面混着 2027/2028/2029 那批未上映的(来源是「剧场版」关键词
 * 回源), 以及 1900 年代的老片. 切片而不是在代码里写死 2026..2006: 明年不用改.
 */
async function loadMeta() {
  metaError.value = ''
  try {
    const res = await getFilterMeta()
    const meta = res.data.data || {}
    const thisYear = new Date().getFullYear()
    years.value = (meta.years || [])
      .filter((y) => Number(y) <= thisYear)
      .slice(0, YEAR_WINDOW)
    statuses.value = meta.statuses || []
  } catch (e) {
    metaError.value = loadErrorMessage(e, '加载筛选条件')
    years.value = []
    statuses.value = []
  }
}

/**
 * 把当前选择写进 URL.
 *
 * 默认值一律不写(空组 / 没选年份 / 第 1 页): `?genre=&page=1` 是噪音, 与"没有这个
 * 参数"完全等价, 写进去只会让地址栏变长、让分享出去的链接看起来比实际更特殊.
 *
 * 用 replace 不用 push: 调条件不该在历史里堆层, 否则从"筛了五层"退回"没筛"
 * 要按好几次后退.
 */
function syncQuery() {
  const next = { ...route.query }
  for (const key of MULTI_KEYS) {
    // slug 而不是标签名 —— 见 csvOf 上面那段
    const slugs = selection.value[key]
    if (slugs.length > 0) next[key] = slugs.join(',')
    else delete next[key]
  }
  for (const key of SINGLE_KEYS) {
    if (selection.value[key]) next[key] = selection.value[key]
    else delete next[key]
  }
  if (page.value > 1) next.page = String(page.value)
  else delete next.page
  $router.replace({ query: next })
}

/**
 * 把结果区送回视野.
 *
 * block 用 'start' —— 与 c77 的 'nearest' 相反, 因为页面形态变了. 那一条是在
 * "标签墙在上、结果在下"的页面里定的: 墙只有 4 行, 结果本来就露在墙下面,
 * 'nearest'("已经看得见就不动")让那次调用成为空操作、不会把墙顶出视野, 在那里是对的.
 * 现在结果**就是**目的地: 从抽屉里选完条件、或在结果底部点「下一页」时, 'nearest' 会
 * 判"结果区还看得见"而一动不动, 用户停在旧内容上 —— 正是 c77 在详情页修掉的那个毛病.
 *
 * 落点会不会被 sticky 的导航栏盖住由 .filter-results 的 scroll-margin-top 管
 * (jsdom 不算布局, 这条测试抓不到, 只能肉眼看).
 *
 * 刻意不传 behavior: 交给 base.css 的 html{scroll-behavior:smooth}, 而它在
 * prefers-reduced-motion 下被改成 auto.
 */
function scrollToResults() {
  resultsRef.value?.scrollIntoView({ block: 'start' })
}

/**
 * 改完条件的统一收尾: 页码复位 → 关抽屉 → 写 URL → 滚动 → 取数.
 *
 * 关抽屉是无条件的: 桌面端 sidebarOpen 本来就是 false, 写一次没有副作用; 而窄屏上
 * 不关的话, 用户选完一个条件还要手动关一次才看得见结果.
 *
 * 顺序不能换: 写 URL 触发的那个 watch 靠"解析出来的值等于当前 ref"来早退,
 * ref 晚一步改就会多打一次请求.
 */
/**
 * 年份一变, 原来选中的季度就作废.
 *
 * 季度按钮的值是**拼出来的**(`${year}-Q${n}`), 所以换了年份之后旧值要么指向另一年
 * 的同一个季度、要么指向一个年份按钮上已经不存在的年 —— 两种都是"季度按钮还亮着,
 * 筛的却是用户没打算要的那几个月". 清掉才是"年份是季度前提"这个模型该有的行为.
 *
 * 放在 applySelectionChange 的**最前面**, 于是 toggleSingle('year') 与
 * clearGroup('year') 两条路都覆盖到 —— 分别写在两个 handler 里的话, 漏掉的那条
 * 不会报错, 只会留下一个对不上的季度.
 */
function pruneSeason() {
  const { season, year } = selection.value
  if (season && seasonYear(season) !== year) {
    selection.value = { ...selection.value, season: '' }
  }
}

async function applySelectionChange() {
  pruneSeason()
  page.value = 1
  sidebarOpen.value = false
  syncQuery()
  await nextTick()
  scrollToResults()
  await loadResults()
}

/** 多选组: 点一下切换, 再点一下取消. 组内是「或」—— 点第二个是追加, 不是替换 */
async function toggleOption(group, value) {
  const current = selection.value[group.key]
  const next = current.includes(value)
    ? current.filter((v) => v !== value)
    : [...current, value]
  selection.value = { ...selection.value, [group.key]: next }
  await applySelectionChange()
}

/** 单选组(年份/季度/状态): 再点一下取消选中, 而不是"选中就没法取消" */
async function toggleSingle(key, value) {
  selection.value = { ...selection.value, [key]: selection.value[key] === value ? '' : value }
  await applySelectionChange()
}

/* 季度按钮的点击就是普通的单选组点击: `@click="toggleSingle('season', quarterValue(quarter.q))"`.
   曾经在这里包过一个带 `if (!year) return` 的 handler 当"第二道闸" —— 反向验证时
   发现它**不可证伪**: disabled 的按钮在真实浏览器与 jsdom 里都收不到 click, 那行
   return 永远走不到, 删掉它没有任何用例会红. 挡"没选年份"的是模板上那一个
   `:disabled`, 而它是能被测红的(去掉它唯一那条季度用例立刻红). */

async function clearGroup(key) {
  selection.value = { ...selection.value, [key]: Array.isArray(selection.value[key]) ? [] : '' }
  await applySelectionChange()
}

/**
 * 翻页.
 *
 * 不用 watch(page): toggleOption 也要把页码复位成 1, 而 watch 分不清"复位导致的"
 * 和"用户点的", 从第 3 页改条件时两条路都会触发, 于是发两次请求.
 */
async function changePage(next) {
  page.value = next
  syncQuery()
  await nextTick()
  scrollToResults()
  await loadResults()
}

/**
 * 反向: URL 变了 → 读回来再取数. 后退/前进、以及手改地址栏走的是这条路.
 *
 * 六个条件 + 页码合成**一个** watch: 拆开的话"换个条件同时回到第 1 页"会让两条都
 * 触发, 发两次请求. 开头的早退判断也是必须的 —— 我们自己调 syncQuery 写 URL 同样会
 * 让它触发, 不判断就变成"点一次按钮发两次请求".
 */
watch(
  () => route.fullPath,
  () => {
    const next = readQuery()
    if (selectionKey(next.selection) === selectionKey(selection.value) && next.page === page.value) return
    selection.value = next.selection
    page.value = next.page
    loadResults()
  },
)

/**
 * 首访: 先把 URL 里的条件收下, 再一并加载.
 *
 * 两条路**并行**: /filter-meta 只喂年份与状态两组, 结果不依赖它.
 *
 * 这里**不调 scrollToResults**: 深链进来时用户还没做任何动作, 一进页面就把自己
 * 滚一下是没有道理的. 滚动只在"用户改了条件"和"用户翻了页"之后发生.
 */
onMounted(async () => {
  const read = readQuery()
  selection.value = read.selection
  page.value = read.page
  await Promise.all([loadMeta(), loadResults()])
})
</script>

<style scoped>
/* 结果区. scroll-margin-top 是必须的, 不是美化: .navbar 是
   position:sticky; top:0; height:64px, 不留这段高度的话 scrollIntoView({block:'start'})
   的落点会被导航栏盖住. 88 = 64 + 24 呼吸. */
/* min-width:0 是必须的, 不是美化 —— .filter-results 是 .browse-layout 的 grid item,
   而 grid item 的 min-width 默认是 auto: 它**不能小于内容的 min-content**. 结果区里
   最宽的那个 min-content 是翻页条(窄屏下 12 个按钮 × 36 + 间隙 = 476px), 于是整列被
   撑到 476px, 连带 .anime-grid 的 repeat(3,1fr) 每格变成 153px(本该 114), 第三列直接
   跑出 390px 的视口. 显式写 0 就是切断这条链: 列宽由容器说了算. */
.filter-results { scroll-margin-top: 88px; min-height: 240px; min-width: 0; }
.browse-total { font-size: 13px; color: var(--text-secondary); }

/* 这一块自己的错误条. 不复用整页那个 EmptyState type="error":
   结果区没加载出来时左栏还是好的, 用整页的样式会把"只是这一块坏了"说成"这一页坏了" */
.filter-error {
  display: flex; align-items: center; gap: 12px;
  padding: 16px 18px;
  font-size: 13px; color: var(--text-secondary);
  background: var(--bg-secondary);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}
.filter-error-inline { padding: 10px 12px; font-size: 12px; margin-bottom: 8px; }
.filter-retry {
  margin-left: auto;
  padding: 5px 14px;
  font-family: inherit; font-size: 12px; font-weight: 600;
  color: var(--text); background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  cursor: pointer; transition: border-color var(--transition), color var(--transition);
}
.filter-retry:hover { border-color: var(--primary); color: var(--primary); }
</style>
