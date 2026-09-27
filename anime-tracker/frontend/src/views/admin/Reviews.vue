<template>
  <AdminLayout>
    <LoadingSpinner v-if="loading" />
    <div v-if="!loading">
      <div class="reviews-wrap">
        <div v-if="reviews.length > 0">
          <div v-for="r in reviews" :key="r.id" class="review-row">
            <div style="flex:1;">
              <div class="review-head">
                <span class="review-user">👤 {{ r.username }}</span>
                <span class="review-stars">{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(10 - r.rating) }}</span>
                <span class="review-time">{{ formatTime(r.createdAt) }}</span>
              </div>
              <div class="review-subject">番剧ID: #{{ r.subjectId }}</div>
              <div class="review-text">{{ r.content || '（无文字）' }}</div>
            </div>
            <button class="delete-btn" @click="handleDelete(r)">删除</button>
          </div>
        </div>
        <EmptyState v-else icon="💬" message="暂无评论" />
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { getAdminReviews, adminDeleteReview } from '../../api'
import AdminLayout from '../../components/AdminLayout.vue'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()
const reviews = ref([])
const loading = ref(true)

function formatTime(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

async function loadReviews() {
  try {
    const res = await getAdminReviews()
    if (res.data.code === 200) reviews.value = res.data.data
  } catch (e) { console.error(e) }
  loading.value = false
}

async function handleDelete(r) {
  if (!confirm(`确定删除用户 "${r.username}" 的评论？`)) return
  try {
    await adminDeleteReview(r.id)
    reviews.value = reviews.value.filter(x => x.id !== r.id)
  } catch (e) { $toast(e.response?.data?.message || '删除失败', 'error') }
}

onMounted(async () => {
  if (!userStore.loggedIn || userStore.user?.role !== 'ADMIN') { router.push('/'); return }
  await loadReviews()
})
</script>

<style scoped>
.reviews-wrap {
  background: var(--card-bg); border-radius: 12px; padding: 4px;
  box-shadow: var(--shadow);
}
.review-row {
  display: flex; align-items: flex-start; gap: 16px; padding: 16px;
  border-bottom: 1px solid var(--border);
}
.review-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.review-user { font-weight: 600; font-size: 14px; color: var(--text); }
.review-stars { color: #f5a623; }
.review-time { color: var(--text-muted); font-size: 12px; }
.review-subject { font-size: 13px; color: var(--text-secondary); margin-bottom: 4px; }
.review-text { font-size: 14px; line-height: 1.6; color: var(--text); }
.delete-btn {
  padding: 6px 16px; border: 1px solid #ff4d4f; color: #ff4d4f;
  background: var(--card-bg); border-radius: 6px; cursor: pointer;
  font-size: 13px; white-space: nowrap;
}
</style>
