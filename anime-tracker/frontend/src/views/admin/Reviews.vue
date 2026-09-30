<template>
  <AdminLayout>
    <LoadingSpinner v-if="loading" />

    <!-- 加载失败. 清单里点名的是 Dashboard 和 Users 两页, 这里是同一个毛病的
         第三处(同一个目录、同一行 catch console.error、同一个结局): 失败时
         reviews 保持空数组, 页面上显示的是「暂无评论」—— 把接口挂了说成
         「本来就没有评论」 -->
    <EmptyState
      v-else-if="error"
      type="error"
      :message="error"
      action-label="重试"
      @action="loadReviews"
    />

    <div v-else>
      <div class="reviews-wrap">
        <div v-if="reviews.length > 0">
          <div v-for="r in reviews" :key="r.id" class="review-row">
            <div style="flex:1;">
              <div class="review-head">
                <span class="review-user">{{ r.username }}</span>
                <span class="review-stars">{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(10 - r.rating) }}</span>
                <span class="review-time">{{ formatTime(r.createdAt) }}</span>
              </div>
              <div class="review-subject">番剧ID: #{{ r.subjectId }}</div>
              <div class="review-text">{{ r.content || '（无文字）' }}</div>
            </div>
            <button class="delete-btn" @click="handleDelete(r)">删除</button>
          </div>
        </div>
        <EmptyState v-else type="comment" message="暂无评论" />
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { getAdminReviews, adminDeleteReview } from '../../api'
import { useToast } from '../../composables/useToast'
import { loadErrorMessage } from '../../utils/loadError'
import AdminLayout from '../../components/AdminLayout.vue'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()
const { show: toast } = useToast()
const reviews = ref([])
const loading = ref(true)
const error = ref('')

function formatTime(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

async function loadReviews() {
  error.value = ''
  loading.value = true
  try {
    const res = await getAdminReviews()
    if (res.data.code === 200) {
      reviews.value = res.data.data
    } else {
      error.value = `加载评论列表失败：服务端返回 ${res.data.code}`
    }
  } catch (e) {
    error.value = loadErrorMessage(e, '加载评论列表')
  }
  loading.value = false
}

async function handleDelete(r) {
  if (!confirm(`确定删除用户 "${r.username}" 的评论？`)) return
  try {
    await adminDeleteReview(r.id)
    reviews.value = reviews.value.filter(x => x.id !== r.id)
  } catch (e) { toast(e.response?.data?.message || '删除失败', 'error') }
}

onMounted(async () => {
  if (!userStore.loggedIn || userStore.user?.role !== 'ADMIN') { router.push('/'); return }
  await loadReviews()
})
</script>

<style scoped>
/* --card 而不是 --card-bg: 后者是只在 :root 里定义过的别名, 浅色主题下会冻在
   深色值上(整个面板深底深字). 详见 tokens.css 顶部那段. */
.reviews-wrap {
  background: var(--card); border-radius: 12px; padding: 4px;
  box-shadow: var(--shadow);
}
.review-row {
  display: flex; align-items: flex-start; gap: 16px; padding: 16px;
  border-bottom: 1px solid var(--border);
}
.review-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.review-user { font-weight: 600; font-size: 14px; color: var(--text); }
/* 与别处的星星用同一个 token. 改前这里是 #f5a623 —— 首页的评分角标用的是
   var(--star)(#fbbf24), 两处星星颜色其实不一样, 只是并排看不出来 */
.review-stars { color: var(--star); }
.review-time { color: var(--text-muted); font-size: 12px; }
.review-subject { font-size: 13px; color: var(--text-secondary); margin-bottom: 4px; }
.review-text { font-size: 14px; line-height: 1.6; color: var(--text); }
.delete-btn {
  padding: 6px 16px; border: 1px solid var(--badge-red-fg); color: var(--badge-red-fg);
  background: var(--card); border-radius: 6px; cursor: pointer;
  font-size: 13px; white-space: nowrap;
}
</style>
