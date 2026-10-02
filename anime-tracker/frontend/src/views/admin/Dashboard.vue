<template>
  <!-- 外壳(左栏 + 标题行)升到了路由那一层的 AdminLayout.vue, 这一页只剩内容.
       三块本来就是互斥的, 所以直接并列在根上. -->

  <LoadingSpinner v-if="loading" />

  <!-- 加载失败. 改前这个页面 catch 里只有 console.error: 接口一挂, dashboard
       保持 null, 模板里两个 v-if 都不成立 —— 页面上就剩一个空壳布局, 连
       「暂无数据」都没有, 用户只能猜是不是自己点错了 -->
  <EmptyState
    v-else-if="error"
    type="error"
    :message="error"
    action-label="重试"
    @action="load"
  />

  <div v-else-if="dashboard">
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.totalUsers }}</div>
        <div class="stat-label">总用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.activeUsers }}</div>
        <div class="stat-label">活跃用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.disabledUsers }}</div>
        <div class="stat-label">禁用用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.adminUsers }}</div>
        <div class="stat-label">管理员</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.totalTrackings }}</div>
        <div class="stat-label">追番记录</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ dashboard.totalReviews }}</div>
        <div class="stat-label">评论总数</div>
      </div>
    </div>

    <!-- 时间维度. 上面六张全是累计值 —— 一个一直在涨的数看不出"最近是不是不行了":
         总量翻倍既可能是这周新增了很多, 也可能是两年来一直这样. 这两张是同一批表的
         时间切片, 不需要新表(直接从 created_at 推), 所以没有"数据不足"的问题.
         ⚠️ v-if 和下面那些 ?. 都不是防御性写法, 是必要的: 老版本后端不返回这两个键,
         而 adminLoadError.test.js 里仪表盘的成功响应就是 data: [] —— 少了这层,
         那一整个文件会因为"读 undefined 的属性"全红, 而它验的根本不是这件事. -->
    <div v-if="dashboard.growth" class="stats-row">
      <div v-for="w in GROWTH_WINDOWS" :key="w.key" class="stat-card stat-card--wide">
        <div class="stat-label">{{ w.label }}</div>
        <div class="stat-deltas">
          <span><b>{{ dashboard.growth[w.key]?.users }}</b>用户</span>
          <span><b>{{ dashboard.growth[w.key]?.reviews }}</b>短评</span>
          <span><b>{{ dashboard.growth[w.key]?.trackings }}</b>追番</span>
        </div>
      </div>
    </div>

    <!-- 真活跃度. 上面那些"活跃用户"数的是 status='ACTIVE'(账号没被禁用),
         是账号状态不是行为 —— 一个两年没登录的账号在那一列里照样是"活跃用户".
         这里数的是最近真的登录过的人. -->
    <div v-if="activity" class="panel">
      <div class="panel-head">
        <h2 class="panel-title">活跃度</h2>
        <span class="panel-hint">数的是登录过的人, 不是访问过的人；同一人当天多次登录算一个</span>
      </div>

      <div class="stats-row">
        <div class="stat-card">
          <div class="stat-num">{{ activity.dau }}</div>
          <div class="stat-label">今日活跃</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ activity.wau }}</div>
          <div class="stat-label">近 7 天活跃</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ activity.failedAttempts24h }}</div>
          <div class="stat-label">近 24 小时登录失败</div>
        </div>
      </div>

      <!-- 「一条事件都没有」和「这两周没人来」画出来是同一条贴地的平线, 但含义
           正好相反. trackedSince 为 null 是前者 —— 那时不画线, 直说没数据. -->
      <p v-if="!activity.trackedSince" class="trend-empty">
        数据不足。登录事件从本次更新上线才开始记录，有用户登录之后这里会显示近 14 天的活跃走势。
      </p>
      <div v-else class="trend">
        <div class="trend-bars">
          <div
            v-for="p in trend"
            :key="p.date"
            class="trend-col"
            :title="`${p.date}：${p.users} 人`"
          >
            <div class="trend-bar" :style="{ height: barHeight(p) }"></div>
          </div>
        </div>
        <div class="trend-axis">
          <span>{{ firstDay }}</span>
          <span class="trend-peak">近 14 天最高 {{ peakUsers }} 人</span>
          <span>{{ lastDay }}</span>
        </div>
        <!-- 记录是从某天才开始的: 窗口左边那几天的 0 不是"没人来", 是"当时还没开始记".
             不解释的话, 上线第一周看这条曲线会以为站点在衰退. -->
        <p v-if="trendStartsLater" class="trend-note">
          库里最早的一条登录事件是 {{ activity.trackedSince }}，在那之前的空白不代表当时没人来。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { getDashboard } from '../../api'
import { loadErrorMessage } from '../../utils/loadError'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

/**
 * 「近 N 天新增」的两个窗口。文案与顺序写死在前端 —— 窗口是后端定义的
 * (含今天的 N 个自然日), 这里只负责把它的键摆到页面上。
 */
const GROWTH_WINDOWS = [
  { key: 'last7d', label: '近 7 天新增' },
  { key: 'last30d', label: '近 30 天新增' },
]

const dashboard = ref(null)
const loading = ref(true)
const error = ref('')

/** activity 缺席时(老后端 / 接口形状变了)整块不渲染, 而不是渲染一堆 undefined */
const activity = computed(() => dashboard.value?.activity || null)

// trend 一律当数组用. 后端固定回 14 个点, 但这里不假设它 —— 下面全是 optional chaining,
// 拿不到就退化成"轴上一个日期都没有", 不会把整页带崩.
const trend = computed(() => activity.value?.trend || [])
const firstDay = computed(() => trend.value[0]?.date || '')
const lastDay = computed(() => trend.value[trend.value.length - 1]?.date || '')
const peakUsers = computed(() =>
  trend.value.reduce((max, p) => Math.max(max, p.users || 0), 0))

/**
 * 窗口左边那几天是不是"还没开始记"。
 *
 * 两个日期都是 yyyy-MM-dd, 定长零填充, 所以字符串比较与日期比较同序 ——
 * 这也是后端选这个格式而不是回时间戳的用处之一。
 */
const trendStartsLater = computed(() => {
  const since = activity.value?.trackedSince
  return !!since && !!firstDay.value && since > firstDay.value
})

/**
 * 一根柱子的高度, 按窗口内的峰值归一化。
 *
 * 归一化是必须的: 日活的绝对量级从个位数到几十都可能, 固定刻度要么把数据压成一条缝,
 * 要么把小数据放大成"很活跃"。代价是不同时间的截图不能直接比高度, 所以轴上写了峰值。
 *
 * 非零的最小高度 8%: 只来了一个人的那天必须看得见 —— 否则"1 人"和"0 人"在图上
 * 长得一模一样, 而那两天的运营含义差着一整个量级。
 */
function barHeight(point) {
  if (!point.users) return '0%'
  return Math.max(8, Math.round((point.users / (peakUsers.value || 1)) * 100)) + '%'
}

// 单独取名(原来是写在 onMounted 里的匿名函数)是为了让错误态上的「重试」有东西可调
async function load() {
  error.value = ''
  loading.value = true
  try {
    const res = await getDashboard()
    if (res.data.code === 200) {
      dashboard.value = res.data.data
    } else {
      // HTTP 是 200 但业务码不是 —— 接口层不认为这是异常, 所以拦截器不会兜住.
      // 不处理的话页面同样是一片空白, 而且比接口挂了更难查(日志里什么都没有)
      error.value = `加载管理台数据失败：服务端返回 ${res.data.code}`
    }
  } catch (e) {
    error.value = loadErrorMessage(e, '加载管理台数据')
  }
  loading.value = false
}

// 改前这里还有一句 `if (!loggedIn || role !== 'ADMIN') router.push('/')`,
// 三个后台页面各抄一遍. 现在收在 AdminLayout 里 —— 而且那边是用
// `<router-view v-if="authorized">` 挡的, 未授权时这个组件根本不会挂载,
// 所以这句判断在这里既重复又已经没有位置可放.
onMounted(load)
</script>
