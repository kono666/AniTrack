<template>
  <AdminLayout>
    <LoadingSpinner v-if="loading" />
    <div v-if="!loading && dashboard">
      <div class="stats-row">
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.totalUsers }}</div>
          <div class="stat-label">👥 总用户</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.activeUsers }}</div>
          <div class="stat-label">✅ 活跃用户</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.disabledUsers }}</div>
          <div class="stat-label">🚫 禁用用户</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.adminUsers }}</div>
          <div class="stat-label">🛡️ 管理员</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.totalTrackings }}</div>
          <div class="stat-label">📚 追番记录</div>
        </div>
        <div class="stat-card">
          <div class="stat-num">{{ dashboard.totalReviews }}</div>
          <div class="stat-label">💬 评论总数</div>
        </div>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { getDashboard } from '../../api'
import AdminLayout from '../../components/AdminLayout.vue'
import LoadingSpinner from '../../components/LoadingSpinner.vue'

const router = useRouter()
const userStore = useUserStore()
const dashboard = ref(null)
const loading = ref(true)

onMounted(async () => {
  if (!userStore.loggedIn || userStore.user?.role !== 'ADMIN') {
    router.push('/')
    return
  }
  try {
    const res = await getDashboard()
    if (res.data.code === 200) {
      dashboard.value = res.data.data
    }
  } catch (e) {
    console.error(e)
  }
  loading.value = false
})
</script>
