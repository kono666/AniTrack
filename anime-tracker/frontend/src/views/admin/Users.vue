<template>
  <!-- 外壳(左栏 + 标题行)升到了路由那一层的 AdminLayout.vue, 这一页只剩内容 -->

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
      <EmptyState v-if="users.length === 0" type="user" message="暂无用户" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAdminUsers, toggleUserStatus, setUserRole, unlockUser } from '../../api'
import { useToast } from '../../composables/useToast'
import { loadErrorMessage } from '../../utils/loadError'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

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

// 改前这里还有一句「不是管理员就 router.push('/')」, 三个后台页面各抄一遍.
// 现在收在 AdminLayout 里, 而且那边用 `<router-view v-if="authorized">` 挡着 ——
// 未授权时这个组件根本不会挂载, 所以那句判断在这里已经无处可放.
onMounted(loadUsers)
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
</style>
