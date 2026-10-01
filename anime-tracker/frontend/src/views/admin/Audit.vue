<template>
  <!-- 外壳(左栏 + 标题行)在路由那一层的 AdminLayout.vue, 这一页只剩内容 -->

  <!-- 工具栏**在加载与出错时也留着**: 它是"改条件"的入口, 而最需要改条件的时刻
       恰恰是"这次查出来不对"的时候(与 Users.vue 同一条理由) -->
  <div class="admin-toolbar">
    <!-- 只有这一类筛法: 账本没有排序开关, 也没有关键词搜索 —— 它是一份时间倒序的
         流水, 而"在人名或正文里搜"是另一个需求(真要做得先有全文索引, 见提交说明) -->
    <select class="admin-select" :value="action" aria-label="按操作类型筛选" @change="onActionChange">
      <option value="">全部操作</option>
      <option v-for="(label, code) in ACTION_LABELS" :key="code" :value="code">{{ label }}</option>
    </select>

    <select class="admin-select" :value="limit" aria-label="每页条数" @change="onLimitChange">
      <option v-for="size in ADMIN_PAGE_SIZES" :key="size" :value="size">{{ size }} 条/页</option>
    </select>

    <button v-if="hasFilter" class="action-btn" @click="clearFilter">清除筛选</button>

    <span class="admin-toolbar-spacer" />
    <span v-if="!loading && !error" class="admin-result-count">共 {{ total }} 条记录</span>
  </div>

  <LoadingSpinner v-if="loading" />

  <!-- 加载失败. 不分开的话这里显示的是「暂无操作记录」—— 一份**说没事**的谎话,
       而这一页的全部意义就是"到底有没有发生过那件事" -->
  <EmptyState
    v-else-if="error"
    type="error"
    :message="error"
    action-label="重试"
    @action="loadLogs"
  />

  <template v-else>
    <div class="admin-table-wrap">
      <table class="admin-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>操作</th>
            <th>操作者</th>
            <th>对象</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in logs" :key="row.id">
            <td class="time-cell">{{ formatTime(row.createdAt) }}</td>
            <td>
              <span class="action-badge" :class="actionTone(row.action)">{{ actionLabel(row.action) }}</span>
            </td>
            <td class="actor-cell">{{ row.actorName }}</td>
            <!-- 对象只给**类型 + id**, 不回表查名字: 账本记的是已经发生过的事,
                 而被记的那些东西(被删的评论、被注销的账号)现在可能已经不存在了 ——
                 可读的那部分在 detail 里, 它写的时候就带上了当时的名字快照 -->
            <td class="target-cell">{{ targetLabel(row.targetType) }} #{{ row.targetId }}</td>
            <td class="detail-cell">{{ row.detail || '（无详情）' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 三种"空"必须分开说, 否则三种都会显示成「暂无操作记录」:
           · 翻到的那一页没行了(总数还在) —— 是页码越界, 不是没有记录.
           · 有一类在筛 —— 说的是"没有这一类", 并给出清条件的入口.
           · 干净的零 —— 才是「暂无操作记录」. -->
    <EmptyState
      v-if="logs.length === 0 && total > 0"
      type="search"
      message="这一页没有记录了"
      action-label="回到第 1 页"
      @action="changePage(1)"
    />
    <EmptyState
      v-else-if="logs.length === 0"
      :type="hasFilter ? 'search' : 'empty'"
      :message="hasFilter ? '没有这一类操作' : '暂无操作记录'"
      :action-label="hasFilter ? '清除筛选' : ''"
      @action="clearFilter"
    />

    <!-- 分页条按 total 渲染而不是按"这一页有没有行": 越界的那一页正好是**没有行**
         的那一页, 按行数渲染就等于在最需要翻页的时候把翻页条藏起来 -->
    <Pagination :current-page="page" :total-pages="totalPages" @change="changePage" />
  </template>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAdminActions, ADMIN_PAGE_SIZES, ADMIN_PAGE_SIZE } from '../../api'
import { useLatestOnly } from '../../composables/useLatestOnly'
import { loadErrorMessage } from '../../utils/loadError'
import { strParam, pageParam } from '../../utils/query'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'
import Pagination from '../../components/Pagination.vue'

/**
 * 操作日志(审计账本): 谁在什么时候对谁做了什么.
 *
 * 这是这一页存在的全部理由: 管理端那四个破坏性动作(封人 / 改角色 / 解锁 / 删评论)
 * 在这个页面上线之前**零留痕** —— 改角色能把任何人提成管理员, 而事后不可查,
 * 管理员自己也查不了. 所以这里不是一个"锦上添花的记录页", 它是那几个动作能被
 * 追责的唯一依据; 这也决定了它的读法只有一种: **最新的在最上面**.
 *
 * 【状态全进 URL】三个参数(action/page/limit)都写在地址栏上, 与 Users.vue /
 * Tags.vue 同一套做法. 这里比那一页简单得多, 因为**没有搜索框、没有防抖、
 * 没有排序** —— 少一处状态机就少一处会漂的地方.
 *
 * 【默认值不写进 URL】`?action=&page=1` 是噪音, 与"没有这个参数"完全等价.
 */

const route = useRoute()
const router = useRouter()
const request = useLatestOnly()

/**
 * action 码 → 给人看的名字.
 *
 * 这个映射是**前端的**, 而合法的 action 集合是后端的(AdminActionLog.ACTIONS) ——
 * 两边各有一份, 因为后端那份是"能不能写进去"的白名单, 这一份是文案. 所以下面
 * actionLabel() 对认不出来的码回退成原样的码: 后端加了第六个动作时, 这一页会
 * 显示 `USER_PASSWORD_RESET` 而不是**一片空白** —— 那才是真正糟的失败方式,
 * 一行操作记录看上去像没写操作.
 */
const ACTION_LABELS = {
  USER_BAN: '封禁用户',
  USER_UNBAN: '启用用户',
  USER_ROLE: '修改角色',
  USER_UNLOCK: '解除锁定',
  USER_PASSWORD_RESET: '重置密码',
  REVIEW_DELETE: '删除评论',
}

const TARGET_LABELS = { USER: '用户', REVIEW: '评论' }

/** 下拉里能选的与 readQuery 认可的必须是**同一份**, 否则 URL 上会出现一个选不中也清不掉的筛选 */
const ACTIONS = Object.keys(ACTION_LABELS)

/**
 * 三档语义色. 判据是"这个动作有多该被查", 不是"它是不是危险" —— 提权看着最无害, 实际最该被查.
 *
 * 重置密码与封禁同一档, 虽然它**什么权限都没改**: 它会把那个人手上所有 token 立刻作废,
 * 而能不能再进来取决于有没有人把新密码告诉他 —— 后果与封禁最接近, 也是账号被盗时
 * 攻击者最先想用的那一下。它归到"中性"里就等于把这页最该被看见的一类操作藏起来了。
 */
const DANGEROUS = ['USER_BAN', 'USER_PASSWORD_RESET', 'REVIEW_DELETE']
const PRIVILEGE = ['USER_ROLE']

const logs = ref([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

const action = ref('')
const page = ref(1)
const limit = ref(ADMIN_PAGE_SIZE)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / limit.value)))

/** 有没有在筛. 每页条数不算 —— 它不缩小结果集, 清除筛选也不该重置它 */
const hasFilter = computed(() => action.value !== '')

const actionLabel = code => ACTION_LABELS[code] || code
const targetLabel = type => TARGET_LABELS[type] || type

/** 认不出来的动作当成中性色, 不抛错 —— 与 actionLabel 的兜底是同一件事的两半 */
function actionTone(code) {
  if (DANGEROUS.includes(code)) return 'badge-danger'
  if (PRIVILEGE.includes(code)) return 'badge-privilege'
  return 'badge-neutral'
}

function formatTime(d) {
  return d ? new Date(d).toLocaleString('zh-CN') : '-'
}

/**
 * URL → 状态.
 *
 * 垃圾值一律落回默认而**不报错**, 与 Users.vue 的 readQuery 同一口径(手输错一个
 * 参数不该让整个页面变成错误页). 两处都必须走白名单, 理由各是一条:
 *   · `limit` —— `?limit=abc` 会算出 NaN 页; 而原样把 7 发出去还会让前端按 20
 *     算页数、后端按 7 条给, 两边对 totalPages 各说各话.
 *   · `action` —— 服务端对认不出来的值当"不筛"(这是它的契约, 不变), 但**前端
 *     不能跟着原样用**: 那个值在下拉里没有任何一个 option 对得上, `<select>` 会
 *     变成 selectedIndex=-1 的空白框, 而列表是按"不筛"给的 —— 界面上留下一个
 *     既说不清在筛什么、又清不掉的控件.
 */
function readQuery() {
  const rawLimit = strParam(route.query.limit)
  return {
    action: ACTIONS.includes(strParam(route.query.action)) ? strParam(route.query.action) : '',
    page: pageParam(route.query.page),
    limit: ADMIN_PAGE_SIZES.includes(Number(rawLimit)) ? Number(rawLimit) : ADMIN_PAGE_SIZE,
  }
}

function currentState() {
  return { action: action.value, page: page.value, limit: limit.value }
}

function stateKey(s) {
  return [s.action, s.page, s.limit].join('|')
}

function applyState(s) {
  action.value = s.action
  page.value = s.page
  limit.value = s.limit
}

/**
 * 把当前状态写进 URL. 默认值一律不写(全部操作 / 第 1 页 / 20 条).
 *
 * 用 replace 不用 push: 调条件不该在历史里堆层, 否则从"筛了三层"退回"没筛"
 * 要按好几次后退.
 */
function syncQuery() {
  const next = { ...route.query }
  const set = (key, value) => { if (value) next[key] = value; else delete next[key] }

  set('action', action.value)
  set('limit', limit.value === ADMIN_PAGE_SIZE ? '' : String(limit.value))
  set('page', page.value > 1 ? String(page.value) : '')

  router.replace({ query: next })
}

/**
 * 取当前条件下的这一页.
 *
 * 令牌纪律与 Users.vue 一字不差: `begin()` 在发请求**之前**取, `await` 之后每一行
 * 都先过 `isCurrent`, 而过期分支里**绝不能写 `loading = false`** —— 那会让"这一次
 * 转圈"停在界面上(还在飞的那次才是该关它的那个, 见 useLatestOnly.js).
 */
async function loadLogs() {
  const token = request.begin()
  error.value = ''
  loading.value = true
  try {
    const res = await getAdminActions({
      // undefined 而不是 '': axios 会把它从 query 里丢掉, 于是"没筛"这一件事
      // 在请求上就表现为参数不存在, 与后端 `:action IS NULL` 那条一一对应
      action: action.value || undefined,
      page: page.value,
      limit: limit.value,
    })
    if (!request.isCurrent(token)) return
    if (res.data.code === 200) {
      const body = res.data.data || {}
      logs.value = body.list || []
      total.value = body.total || 0
    } else {
      error.value = `加载操作日志失败：服务端返回 ${res.data.code}`
      logs.value = []
      total.value = 0
    }
  } catch (e) {
    if (!request.isCurrent(token)) return
    error.value = loadErrorMessage(e, '加载操作日志')
    logs.value = []
    total.value = 0
  }
  // 能走到这里 ⇔ 上面没早退 ⇔ 令牌仍是最新的, 不用再判一次
  loading.value = false
}

// ==================== 改条件 ====================

/** 改筛选必须回第 1 页: 停在第 3 页上看一个新条件的结果, 多半是空的 */
function onActionChange(e) {
  action.value = e.target.value
  return applyFilterChange()
}

/** 每页条数也要回第 1 页 —— 不重置的话, 20 条/页时的第 4 页在 100 条/页下可能是空的 */
function onLimitChange(e) {
  limit.value = Number(e.target.value)
  return applyFilterChange()
}

function clearFilter() {
  action.value = ''
  return applyFilterChange()
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
  await loadLogs()
}

/** 翻页. 不写 watch(page): 上面那个复位与"用户点的"会走成两条路, 各发一次请求 */
async function changePage(next) {
  page.value = next
  syncQuery()
  await nextTick()
  await loadLogs()
}

/**
 * 反向: URL 变了 → 读回来再取数. 后退/前进、以及手改地址栏走的是这条路.
 *
 * 三个条件合成**一个** watch: 拆开的话"换筛选同时回到第 1 页"会让两条都触发,
 * 发两次请求. 开头的早退是必须的 —— 我们自己调 syncQuery 写 URL 同样会让它触发,
 * 不判断就变成"点一次按钮发两次请求".
 */
watch(
  () => route.fullPath,
  () => {
    const next = readQuery()
    if (stateKey(next) === stateKey(currentState())) return
    applyState(next)
    loadLogs()
  },
)

/**
 * 首访: 先把 URL 里的条件收下, 再加载. 深链进来时条件就已经生效, 不会先按默认取一次
 * 再"跳"到筛选结果(那会白白多发一次请求, 界面上还会闪一下全量列表).
 */
onMounted(() => {
  applyState(readQuery())
  loadLogs()
})
</script>

<style scoped>
/* 表格外框、行距、表头这些都在 admin.css 里(外壳搬过去之后它们只该有一个归属,
   见 Users.vue 里那段注释). 这里只放这一页特有的几处. */
.time-cell { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
.actor-cell { font-size: 14px; font-weight: 500; color: var(--text); }
.target-cell { font-size: 13px; color: var(--text-secondary); white-space: nowrap; }
/* 详情是一句可能很长的话(带评论摘要), 让它换行而不是把表格撑出横向滚动条 */
.detail-cell { font-size: 13px; line-height: 1.6; color: var(--text); min-width: 16rem; }

.action-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; white-space: nowrap; }
/* 三档走 tokens.css 的语义变量(与 Users.vue 的徽章同一条规矩: 不用写死的色值,
   否则暗色主题下每一枚都是一块自发光的浅色块) */
.badge-danger { background: var(--badge-red-bg); color: var(--badge-red-fg); }
.badge-privilege { background: var(--badge-ink-bg); color: var(--badge-ink-fg); }
.badge-neutral { background: var(--badge-blue-bg); color: var(--badge-blue-fg); }

/* 按钮底子与 Users.vue 那份同名同值. 不是重复: scoped 样式不出组件, 而 .action-btn
   至今没有收进 admin.css(那边只有外壳: 表格外框、工具栏排布、下拉框). 真要收的话
   得连 Users.vue 一起改, 那是另一件事 —— 这一页只保证同一枚按钮长得一样. */
.action-btn {
  padding: 4px 12px; font-size: 12px; border-radius: 4px;
  cursor: pointer; margin-right: 4px; background: var(--card);
}
/* 「清除筛选」是个普通按钮(不带语义色): 它是一个中性动作, 而不是"危险"或"主要" */
.admin-toolbar .action-btn { border: 1px solid var(--border); color: var(--text-secondary); }
.admin-toolbar .action-btn:hover { border-color: var(--primary-line); color: var(--primary); }
</style>
