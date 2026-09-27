<template>
  <AdminLayout>
    <LoadingSpinner v-if="loading" />
    <div v-if="!loading">
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
              </td>
              <td class="time-cell">{{ formatTime(u.createdAt) }}</td>
              <td>
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
import { getAdminUsers, toggleUserStatus, setUserRole } from '../../api'
import AdminLayout from '../../components/AdminLayout.vue'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

const router = useRouter()
const userStore = useUserStore()
const users = ref([])
const loading = ref(true)

function formatTime(d) { return d ? new Date(d).toLocaleDateString('zh-CN') : '-' }

async function loadUsers() {
  try {
    const res = await getAdminUsers()
    if (res.data.code === 200) users.value = res.data.data
  } catch (e) { console.error(e) }
  loading.value = false
}

async function handleToggleStatus(u) {
  if (!confirm(`确定${u.status === 'ACTIVE' ? '禁用' : '启用'}用户 "${u.username}"？`)) return
  try {
    await toggleUserStatus(u.id)
    await loadUsers()
  } catch (e) { $toast(e.response?.data?.message || '操作失败', 'error') }
}

async function handleSetAdmin(u) {
  if (!confirm(`确定将 "${u.username}" 设为管理员？`)) return
  try {
    await setUserRole(u.id, 'ADMIN')
    await loadUsers()
  } catch (e) { $toast(e.response?.data?.message || '操作失败', 'error') }
}

onMounted(async () => {
  if (!userStore.loggedIn || userStore.user?.role !== 'ADMIN') { router.push('/'); return }
  await loadUsers()
})
</script>

<style scoped>
.admin-table-wrap {
  background: var(--card-bg); border-radius: 12px; overflow: hidden;
  box-shadow: var(--shadow);
}
.username-cell { font-size: 14px; font-weight: 500; color: var(--text); }
.email-cell { font-size: 13px; color: var(--text-secondary); }
.time-cell { font-size: 12px; color: var(--text-muted); }
.role-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.role-admin { background: #f0e6ff; color: #7c3aed; }
.role-user { background: #e6f7ff; color: #1890ff; }
.status-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status-active { background: #f6ffed; color: #52c41a; }
.status-disabled { background: #fff2f0; color: #ff4d4f; }
.action-btn {
  padding: 4px 12px; font-size: 12px; border-radius: 4px;
  cursor: pointer; margin-right: 4px; background: var(--card-bg);
}
.btn-danger { border: 1px solid #ff4d4f; color: #ff4d4f; }
.btn-purple { border: 1px solid #7c3aed; color: #7c3aed; }
</style>
