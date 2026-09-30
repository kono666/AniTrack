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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getDashboard } from '../../api'
import { loadErrorMessage } from '../../utils/loadError'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

const dashboard = ref(null)
const loading = ref(true)
const error = ref('')

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
