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
      placeholder="搜索用户名或邮箱"
      aria-label="搜索用户名或邮箱"
      @input="onKeywordInput"
    />

    <select class="admin-select" :value="role" aria-label="按角色筛选" @change="onRoleChange">
      <option value="">全部角色</option>
      <option value="ADMIN">管理员</option>
      <option value="USER">普通用户</option>
    </select>

    <!-- 状态是一个四下选一的下拉: 「已锁定」是伪值, 它落在另一个字段上,
         不与 正常/已禁用 叠加(所以表达不了「已锁定 且 已禁用」, 见提交说明) -->
    <select class="admin-select" :value="status" aria-label="按状态筛选" @change="onStatusChange">
      <option value="">全部状态</option>
      <option value="ACTIVE">正常</option>
      <option value="DISABLED">已禁用</option>
      <option value="LOCKED">已锁定</option>
    </select>

    <select class="admin-select" :value="limit" aria-label="每页条数" @change="onLimitChange">
      <option v-for="size in ADMIN_PAGE_SIZES" :key="size" :value="size">{{ size }} 条/页</option>
    </select>

    <button v-if="hasFilter" class="action-btn" @click="clearFilters">清除筛选</button>

    <span class="admin-toolbar-spacer" />
    <span v-if="!loading && !error" class="admin-result-count">共 {{ total }} 个用户</span>
  </div>

  <LoadingSpinner v-if="loading" />

  <!-- 加载失败. 改前一失败就是空表格, 和「这个站还没有用户」长得一模一样 ——
       管理端看到空表第一反应是数据没了, 而不是接口挂了 -->
  <EmptyState
    v-else-if="error"
    type="error"
    :message="error"
    action-label="重试"
    @action="loadUsers"
  />

  <template v-else>
    <div class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>ID</th>
            <th :aria-sort="ariaSortFor('username')">
              <button
                class="admin-sort-btn"
                :class="{ 'is-active': sort === 'username' }"
                @click="toggleSort('username')"
              >
                用户名
                <PhCaretUp
                  v-if="sort === 'username' && order === 'asc'"
                  class="sort-icon" :size="12" aria-hidden="true"
                />
                <PhCaretDown v-else class="sort-icon" :size="12" aria-hidden="true" />
              </button>
            </th>
            <th>邮箱</th>
            <th>角色</th>
            <th>状态</th>
            <th :aria-sort="ariaSortFor('createdAt')">
              <button
                class="admin-sort-btn"
                :class="{ 'is-active': sort === 'createdAt' }"
                @click="toggleSort('createdAt')"
              >
                注册时间
                <PhCaretUp
                  v-if="sort === 'createdAt' && order === 'asc'"
                  class="sort-icon" :size="12" aria-hidden="true"
                />
                <PhCaretDown v-else class="sort-icon" :size="12" aria-hidden="true" />
              </button>
            </th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>{{ u.id }}</td>
            <td class="username-cell">{{ u.username }}</td>
            <td class="email-cell">{{ u.email || '-' }}</td>
            <td>
              <span class="role-badge" :class="u.role === 'ADMIN' ? 'role-admin' : 'role-user'">
                {{ u.role === 'ADMIN' ? '管理员' : '用户' }}
              </span>
            </td>
            <td>
              <span class="status-badge" :class="u.status === 'ACTIVE' ? 'status-active' : 'status-disabled'">
                {{ u.status === 'ACTIVE' ? '正常' : '已禁用' }}
              </span>
              <span v-if="u.locked" class="status-badge status-locked">已锁定</span>
            </td>
            <td class="time-cell">{{ formatTime(u.createdAt) }}</td>
            <td>
              <button
                v-if="u.locked"
                class="action-btn btn-warn"
                @click="handleUnlock(u)"
              >解锁</button>
              <button
                v-if="u.role !== 'ADMIN'"
                class="action-btn btn-danger"
                @click="handleToggleStatus(u)"
              >{{ u.status === 'ACTIVE' ? '禁用' : '启用' }}</button>
              <button
                v-if="u.role !== 'ADMIN'"
                class="action-btn btn-purple"
                @click="handleSetAdmin(u)"
              >设为管理员</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 三种"空"必须分开说, 否则三种都会显示成「暂无用户」:
           · 翻到的那一页没行了(总数还在) —— 是页码越界, 不是没有用户. 这种情况
             **会**真的发生: 在筛 ACTIVE 的最后一页禁用了最后一个人, 这一页就空了.
             所以这里给的不只是更好的文案, 还是**唯一的出路** —— 分页条就算渲染出来,
             用户也不一定看得出该点哪一页.
           · 有条件在筛 —— 说的是"没有匹配的", 并给出清条件的入口.
           · 干净的零 —— 才是「暂无用户」 -->
    <EmptyState
      v-if="users.length === 0 && total > 0"
      type="search"
      message="这一页没有用户了"
      action-label="回到第 1 页"
      @action="changePage(1)"
    />
    <EmptyState
      v-else-if="users.length === 0"
      :type="hasFilter ? 'search' : 'user'"
      :message="hasFilter ? '没有匹配的用户' : '暂无用户'"
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
import {
  getAdminUsers, toggleUserStatus, setUserRole, unlockUser,
  ADMIN_PAGE_SIZES, ADMIN_PAGE_SIZE,
} from '../../api'
import { useToast } from '../../composables/useToast'
import { useLatestOnly } from '../../composables/useLatestOnly'
import { loadErrorMessage } from '../../utils/loadError'
import { strParam, pageParam } from '../../utils/query'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'
import Pagination from '../../components/Pagination.vue'

/**
 * 用户管理: 服务端分页 / 搜索 / 筛选 / 排序.
 *
 * 改前这一页拿的是**裸数组** —— 服务端一次把全部用户吐出来. 用户表只有两行时看不出
 * 问题; 真站规模下它既不能搜、不能筛、不能排序, 也没有分页, 而且每一行操作完都要
 * 把整张表重下一次.
 *
 * 【状态全进 URL】七个参数(q/role/status/sort/order/page/limit)都写在地址栏上,
 * 与 Tags.vue 同一套做法: 后退键可用、刷新能复原、筛选结果可分享. 代价是 URL 长,
 * 换来的是"我筛出来的这一屏"能原样发给另一个人 —— 后台只有一个人时这条看着没用,
 * 但它同样决定了**刷新之后条件还在不在**.
 *
 * 【默认值不写进 URL】`?q=&role=&page=1` 是噪音, 与"没有这个参数"完全等价.
 */

const route = useRoute()
const router = useRouter()
const { show: toast } = useToast()
const request = useLatestOnly()

/** 排序口径的白名单, 与后端 AdminService 那两个常量同源 */
const SORT_CREATED = 'createdAt'
const SORT_USERNAME = 'username'
const SORTS = [SORT_CREATED, SORT_USERNAME]
const DEFAULT_SORT = SORT_CREATED

const ORDERS = ['asc', 'desc']

/**
 * 每一列的**自然首向** —— 没在 URL 上写 order 时按它算.
 *
 * 时间给"最新在前", 名字给 A→Z. 后端有一条一模一样的规则, 两边必须一致:
 * 前端把 `sort=username&order=asc` 当作默认组合、**不写进 URL**, 于是分享出去的
 * 链接是光秃秃的 `?sort=username` —— 后端的"不是 asc 就是 desc"会让它翻成倒序,
 * 而界面上显示的却是正序.
 */
const NATURAL_ORDER = { [SORT_CREATED]: 'desc', [SORT_USERNAME]: 'asc' }

const USER_ROLES = ['ADMIN', 'USER']
const USER_STATUSES = ['ACTIVE', 'DISABLED', 'LOCKED']

/** 输入到发请求之间的静默期. 300ms ≈ 正常打字的字间隔, 连打一个字不会各发一次 */
const SEARCH_DEBOUNCE = 300

const users = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

const keyword = ref('')
const role = ref('')
const status = ref('')
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
 * loadUsers), 结果集也确实是全量, 这时说"有筛选条件"会让空态和「清除筛选」按钮
 * 都在说一件没发生的事.
 */
const hasFilter = computed(
  () => keyword.value.trim() !== '' || role.value !== '' || status.value !== '',
)

function formatTime(d) { return d ? new Date(d).toLocaleDateString('zh-CN') : '-' }

/** 白名单挑选: 认不出来的一律当"没有这个值". 只可能来自被手改过的 URL */
function pick(allowed, value) {
  return allowed.includes(value) ? value : ''
}

/**
 * URL → 状态.
 *
 * 垃圾值一律落回默认而**不报错**, 与 pageParam 同一口径(手输错一个参数不该让整个
 * 页面变成错误页). 两处**必须**走白名单而不是直接拿来用:
 *   · `limit` —— 若用 parseInt, `?limit=5` 会让前端按 5 条算页数, 而服务端收到 5 也照做,
 *     看着一致; 但 `?limit=7` 服务端收 7、前端…也一致. 真正的问题是 `?limit=abc`:
 *     parseInt 给 NaN, 分页控件算出 NaN 页.
 *   · `sort`/`order` —— 认不出来时若原样发给后端, 后端会当作"没给"走默认;
 *     前端却以为在按那一列排, 于是表头上的 caret 指着一列、行是按另一列排的.
 */
function readQuery() {
  const nextSort = pick(SORTS, strParam(route.query.sort)) || DEFAULT_SORT
  const rawOrder = strParam(route.query.order)
  const rawLimit = strParam(route.query.limit)
  return {
    keyword: strParam(route.query.q),
    role: pick(USER_ROLES, strParam(route.query.role)),
    status: pick(USER_STATUSES, strParam(route.query.status)),
    sort: nextSort,
    // order 的默认值跟着**解析后**的 sort 走, 不是跟着 URL 上那个原始值 ——
    // `?sort=bogus` 会落回 createdAt, 那 order 的自然首向也该是 desc
    order: ORDERS.includes(rawOrder) ? rawOrder : NATURAL_ORDER[nextSort],
    page: pageParam(route.query.page),
    limit: ADMIN_PAGE_SIZES.includes(Number(rawLimit)) ? Number(rawLimit) : ADMIN_PAGE_SIZE,
  }
}

/** 当前状态. 与 readQuery 的返回值同形, 用来判定"URL 读回来的和手上的其实一样" */
function currentState() {
  return {
    keyword: keyword.value, role: role.value, status: status.value,
    sort: sort.value, order: order.value, page: page.value, limit: limit.value,
  }
}

function stateKey(s) {
  return [s.keyword, s.role, s.status, s.sort, s.order, s.page, s.limit].join('|')
}

function applyState(s) {
  keyword.value = s.keyword
  role.value = s.role
  status.value = s.status
  sort.value = s.sort
  order.value = s.order
  page.value = s.page
  limit.value = s.limit
}

/**
 * 把当前状态写进 URL.
 *
 * 默认值一律不写(空关键词 / 全部角色 / 全部状态 / 默认排序列 / 该列的自然首向 /
 * 第 1 页 / 20 条).
 *
 * ⚠️ `order` 的省略条件是「等于**当前排序列**的自然首向」, 不是"等于 desc".
 * 写成后者的话, `sort=username&order=asc` 会被压成 `?sort=username`, 而那时
 * URL 与"用户看到的状态"仍然一致(后端对 username 缺 order 也按 asc) —— 一致是因为
 * 后端那条规则, 两边缺一条就散.
 *
 * 用 replace 不用 push: 调条件不该在历史里堆层, 否则从"筛了三层"退回"没筛"
 * 要按好几次后退.
 */
function syncQuery() {
  const next = { ...route.query }
  const set = (key, value) => { if (value) next[key] = value; else delete next[key] }

  // 写的是**原样**的关键词, 不 trim. 修词是请求那一侧的事(loadUsers 里 trim 过),
  // 而 URL 这一侧必须和手上的 ref 逐字相等 —— 否则打一个尾随空格就写出一条
  // 短一位的 q, 下面那个 watch 判定"URL 与我手上的不一样"而再取一次数据.
  set('q', keyword.value)
  set('role', role.value)
  set('status', status.value)
  set('sort', sort.value === DEFAULT_SORT ? '' : sort.value)
  set('order', order.value === NATURAL_ORDER[sort.value] ? '' : order.value)
  set('limit', limit.value === ADMIN_PAGE_SIZE ? '' : String(limit.value))
  set('page', page.value > 1 ? String(page.value) : '')

  router.replace({ query: next })
}

/**
 * 取当前条件下的这一页.
 *
 * 令牌纪律与 Tags.vue 一字不差: `begin()` 在发请求**之前**取, `await` 之后每一行都
 * 先过 `isCurrent`, 而过期分支里**绝不能写 `loading = false`** —— 那会让"这一次转圈"
 * 停在界面上(还在飞的那次才是该关它的那个, 见 useLatestOnly.js).
 */
async function loadUsers() {
  const token = request.begin()
  error.value = ''
  loading.value = true
  try {
    const res = await getAdminUsers({
      // undefined 而不是 '': axios 会把它从 query 里丢掉, 于是"没筛"这一件事
      // 在请求上就表现为参数不存在, 与后端 `:param IS NULL` 那条一一对应
      keyword: keyword.value.trim() || undefined,
      role: role.value || undefined,
      status: status.value || undefined,
      // sort 只在**不是默认列**时才发. 注意 order 的条件跟着 sort 走:
      // `sort=username&order=asc` 要发 sort、可以不发 order(后端对 username 缺 order
      // 就是 asc); 而 `sort=createdAt&order=asc` 反过来只能发 order.
      sort: sort.value === DEFAULT_SORT ? undefined : sort.value,
      order: order.value === NATURAL_ORDER[sort.value] ? undefined : order.value,
      page: page.value,
      limit: limit.value,
    })
    if (!request.isCurrent(token)) return
    if (res.data.code === 200) {
      const body = res.data.data || {}
      users.value = body.list || []
      total.value = body.total || 0
    } else {
      error.value = `加载用户列表失败：服务端返回 ${res.data.code}`
      users.value = []
      total.value = 0
    }
  } catch (e) {
    if (!request.isCurrent(token)) return
    error.value = loadErrorMessage(e, '加载用户列表')
    users.value = []
    total.value = 0
  }
  // 能走到这里 ⇔ 上面没早退 ⇔ 令牌仍是最新的, 不用再判一次
  loading.value = false
}

// ==================== 用户操作 ====================

/**
 * 四个行内操作**都重取当前页**, 不做乐观的本地改写.
 *
 * 本地改写在服务端分页下是错的: 筛着 `status=ACTIVE` 时禁用了这一页的最后一个人,
 * 那一行**已经不符合筛选**了 —— 留在表里就得凭空决定"它该不该消失", 而 total 也变了,
 * 分页条会跟着错. 重取一次把这三件事交给服务端说, 是唯一不会自相矛盾的做法.
 */
async function handleToggleStatus(u) {
  if (!confirm(`确定${u.status === 'ACTIVE' ? '禁用' : '启用'}用户 "${u.username}"？`)) return
  try {
    await toggleUserStatus(u.id)
    await loadUsers()
  } catch (e) { toast(e.response?.data?.message || '操作失败', 'error') }
}

async function handleSetAdmin(u) {
  if (!confirm(`确定将 "${u.username}" 设为管理员？`)) return
  try {
    await setUserRole(u.id, 'ADMIN')
    await loadUsers()
  } catch (e) { toast(e.response?.data?.message || '操作失败', 'error') }
}

// 解锁按钮对管理员账号也显示, 与「禁用/设为管理员」不同.
// 原因是解锁不动任何权限, 只是把登录失败计数清零; 而管理员账号恰恰是最需要
// 这条恢复路径的 —— 它一旦被人在线爆破锁死, 就没人能进后台把别人解开了.
async function handleUnlock(u) {
  if (!confirm(`确定解除 "${u.username}" 的登录锁定？`)) return
  try {
    const res = await unlockUser(u.id)
    toast(res.data?.message || '账号已解锁', 'success')
    await loadUsers()
  } catch (e) { toast(e.response?.data?.message || '操作失败', 'error') }
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

function onRoleChange(e) { role.value = e.target.value; return applyFilterChange() }
function onStatusChange(e) { status.value = e.target.value; return applyFilterChange() }

/** 每页条数: 必须回到第 1 页 —— 不重置的话, 20 条/页时的第 4 页在 100 条/页下是空的 */
function onLimitChange(e) { limit.value = Number(e.target.value); return applyFilterChange() }

function clearFilters() {
  keyword.value = ''
  role.value = ''
  status.value = ''
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
  await loadUsers()
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
  await loadUsers()
}

/**
 * 反向: URL 变了 → 读回来再取数. 后退/前进、以及手改地址栏走的是这条路.
 *
 * 七个条件合成**一个** watch: 拆开的话"换筛选同时回到第 1 页"会让两条都触发,
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
    loadUsers()
  },
)

/**
 * 首访: 先把 URL 里的条件收下, 再加载. 深链进来时条件就已经生效, 不会先按默认取一次
 * 再"跳"到筛选结果(那会白白多发一次请求, 界面上还会闪一下全量列表).
 */
onMounted(() => {
  applyState(readQuery())
  loadUsers()
})

// 防抖定时器必须清: 组件已经没了, 300ms 后它还会触发一次 loadUsers ——
// 那次请求的结果会写进一个已经卸载的组件的 ref, 而 loading 会永远停在转圈上.
onUnmounted(cancelPendingSearch)
</script>

<style scoped>
/* 这里原本有一份 .admin-table-wrap. 它存在的原因不是"这一页要长得不一样",
   而是 scoped 不在任何 @layer 里、永远压过层内的规则 —— 于是 admin.css 里那份
   改了等于没改, 真正生效的一直是这里, 两处必须一起动. 现在那份重复已删掉,
   admin.css 是唯一的归属(外壳搬过去之后, 这张表的两条规则也不该再分家). */
.username-cell { font-size: 14px; font-weight: 500; color: var(--text); }
.email-cell { font-size: 13px; color: var(--text-secondary); }
.time-cell { font-size: 12px; color: var(--text-muted); }
/* 徽章与按钮的色值走 tokens.css 的语义变量. 改前这里是 10 个写死的色值,
   而且只有浅色那一套 —— 暗色主题下每一枚徽章都是一块自发光的浅色块,
   一排操作按钮则是四个扎眼的荧光描边. 现在两种主题各有一套(见 tokens.css). */
.role-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.role-admin { background: var(--badge-ink-bg); color: var(--badge-ink-fg); }
.role-user { background: var(--badge-blue-bg); color: var(--badge-blue-fg); }
.status-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status-active { background: var(--badge-green-bg); color: var(--badge-green-fg); }
.status-disabled { background: var(--badge-red-bg); color: var(--badge-red-fg); }
.status-locked { background: var(--badge-amber-bg); color: var(--badge-amber-fg); margin-left: 6px; }
.action-btn {
  padding: 4px 12px; font-size: 12px; border-radius: 4px;
  cursor: pointer; margin-right: 4px; background: var(--card);
}
.btn-danger { border: 1px solid var(--badge-red-fg); color: var(--badge-red-fg); }
/* 改前叫 .btn-purple, 取的是徽章那套紫。现在"更高权限"这件事由墨色表达,
   所以它是个普通的主色描边按钮 —— 用 --primary-line/--primary 而不是
   --badge-ink-fg: 后者是"压在墨色实心徽章上的字"(深色主题下是黑的),
   拿来当描边色会在深色底上直接看不见。 */
.btn-purple { border: 1px solid var(--primary-line); color: var(--primary); }
.btn-warn { border: 1px solid var(--badge-amber-fg); color: var(--badge-amber-fg); }

/* 「清除筛选」是个普通按钮(不带语义色): 它是一个中性动作, 而不是"危险"或"主要" */
.admin-toolbar .action-btn { border: 1px solid var(--border); color: var(--text-secondary); }
.admin-toolbar .action-btn:hover { border-color: var(--primary-line); color: var(--primary); }

/* 改前这里还有一句「不是管理员就 router.push('/')」, 三个后台页面各抄一遍.
   现在收在 AdminLayout 里, 而且那边用 `<router-view v-if="authorized">` 挡着 ——
   未授权时这个组件根本不会挂载, 所以那句判断在这里已经无处可放. */
</style>
