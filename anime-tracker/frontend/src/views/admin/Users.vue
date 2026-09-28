<template>
  <AdminLayout>
    <LoadingSpinner v-if="loading" />

    <!-- 加载失败. 改前一失败就是空表格, 和「这个站还没有用户」长得一模一样 ——
         管理端看到空表第一反应是数据没了, 而不是接口挂了 -->
    <EmptyState
      v-else-if="error"
      icon="⚠️"
      :message="error"
      action-label="重试"
      @action="loadUsers"
    />

    <div v-else>
      <div class="admin-table-wrap">
        <table class="admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>注册时间</th>
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
        <EmptyState v-if="users.length === 0" icon="👥" message="暂无用户" />
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { getAdminUsers, toggleUserStatus, setUserRole, unlockUser } from '../../api'
import { useToast } from '../../composables/useToast'
import { loadErrorMessage } from '../../utils/loadError'
import AdminLayout from '../../components/AdminLayout.vue'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()
const { show: toast } = useToast()
const users = ref([])
const loading = ref(true)
const error = ref('')

function formatTime(d) { return d ? new Date(d).toLocaleDateString('zh-CN') : '-' }

async function loadUsers() {
  error.value = ''
  loading.value = true
  try {
    const res = await getAdminUsers()
    if (res.data.code === 200) {
      users.value = res.data.data
    } else {
      error.value = `加载用户列表失败：服务端返回 ${res.data.code}`
    }
  } catch (e) {
    error.value = loadErrorMessage(e, '加载用户列表')
  }
  loading.value = false
}

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

onMounted(async () => {
  if (!userStore.loggedIn || userStore.user?.role !== 'ADMIN') { router.push('/'); return }
  await loadUsers()
})
</script>

<style scoped>
/* 这份是**实际生效**的那份, 不是 assets/css/admin.css 里那份.
   原因见 admin.css 的注释: 那份在 @layer components 里, scoped 不在层里,
   层叠层的规则一定输. 所以窄屏横向滚动这件事必须在这里改, 那边只是保持同步. */
.admin-table-wrap {
  background: var(--card-bg); border-radius: 12px; overflow-x: auto;
  box-shadow: var(--shadow);
}
.username-cell { font-size: 14px; font-weight: 500; color: var(--text); }
.email-cell { font-size: 13px; color: var(--text-secondary); }
.time-cell { font-size: 12px; color: var(--text-muted); }
/* 徽章与按钮的色值走 tokens.css 的语义变量. 改前这里是 10 个写死的色值,
   而且只有浅色那一套 —— 暗色主题下每一枚徽章都是一块自发光的浅色块,
   一排操作按钮则是四个扎眼的荧光描边. 现在两种主题各有一套(见 tokens.css). */
.role-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.role-admin { background: var(--badge-purple-bg); color: var(--badge-purple-fg); }
.role-user { background: var(--badge-blue-bg); color: var(--badge-blue-fg); }
.status-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status-active { background: var(--badge-green-bg); color: var(--badge-green-fg); }
.status-disabled { background: var(--badge-red-bg); color: var(--badge-red-fg); }
.status-locked { background: var(--badge-amber-bg); color: var(--badge-amber-fg); margin-left: 6px; }
.action-btn {
  padding: 4px 12px; font-size: 12px; border-radius: 4px;
  cursor: pointer; margin-right: 4px; background: var(--card-bg);
}
.btn-danger { border: 1px solid var(--badge-red-fg); color: var(--badge-red-fg); }
.btn-purple { border: 1px solid var(--badge-purple-fg); color: var(--badge-purple-fg); }
.btn-warn { border: 1px solid var(--badge-amber-fg); color: var(--badge-amber-fg); }
</style>
