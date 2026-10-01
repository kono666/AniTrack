<template>
  <!-- 外壳(左栏 + 标题行)在路由那一层的 AdminLayout.vue, 这一页只剩内容 -->

  <!-- 返回入口在**四种状态里都留着**: 它是这一页唯一的出口, 而最需要它的时刻恰恰是
       "这个用户不存在"的时候 —— 那时页面上没有别的东西可点. -->
  <div class="detail-back">
    <router-link to="/admin/users" class="back-link">
      <PhArrowLeft :size="14" weight="bold" /> 返回用户列表
    </router-link>
  </div>

  <LoadingSpinner v-if="state === 'loading'" />

  <!-- 「用户不存在」与「加载失败」是**两种**界面, 不能混成一种。
       混成一种的样子: 不存在的用户显示「加载失败：请求失败」并配一个重试按钮 ——
       而那个按钮点多少次都还是同样的结果, 管理员会反复点然后来报 bug.
       前者的正确答案是"这个 id 没有对应的用户", 出口是回列表; 后者才给重试. -->
  <EmptyState
    v-else-if="state === 'notfound'"
    type="notfound"
    :message="notFoundMessage"
    action-label="返回用户列表"
    @action="goBackToList"
  />

  <EmptyState
    v-else-if="state === 'error'"
    type="error"
    :message="error"
    action-label="重试"
    @action="load"
  />

  <template v-else-if="detail">
    <!-- 账号信息卡 -->
    <section class="detail-card">
      <div class="account-head">
        <div class="account-avatar">
          <img v-if="detail.avatar" :src="detail.avatar" :alt="detail.username" />
          <PhUser v-else :size="28" weight="light" aria-hidden="true" />
        </div>
        <div class="account-name">
          <h2>{{ detail.username }}</h2>
          <div class="account-badges">
            <span class="role-badge" :class="detail.role === 'ADMIN' ? 'role-admin' : 'role-user'">
              {{ detail.role === 'ADMIN' ? '管理员' : '用户' }}
            </span>
            <span class="status-badge" :class="detail.status === 'ACTIVE' ? 'status-active' : 'status-disabled'">
              {{ detail.status === 'ACTIVE' ? '正常' : '已禁用' }}
            </span>
            <!-- locked 由服务端算好发过来(isLocked()), 前端**不要**自己拿 lockedUntil
                 去比时间: 那样两处判据会在时区/时钟上分叉, 而列表页与详情页对同一个
                 账号给出相反答复时, 两边看着都像是对的 -->
            <span v-if="detail.locked" class="status-badge status-locked">已锁定</span>
          </div>
        </div>
      </div>

      <dl class="account-facts">
        <div class="fact"><dt>邮箱</dt><dd>{{ detail.email || '-' }}</dd></div>
        <div class="fact"><dt>注册时间</dt><dd>{{ formatDateTime(detail.createdAt) }}</dd></div>
        <!-- 与列表页那一列同一个语义边界: 记的是**登录成功**那一刻, 不是最近活动.
             null 读作"注册后从未登录过", 由前端渲染成「从未登录」 -->
        <div class="fact">
          <dt>最近登录</dt>
          <dd>{{ detail.lastLoginAt ? formatDateTime(detail.lastLoginAt) : '从未登录' }}</dd>
        </div>
        <div class="fact" v-if="detail.locked">
          <dt>锁定至</dt><dd>{{ formatDateTime(detail.lockedUntil) }}</dd>
        </div>
      </dl>
    </section>

    <!-- 四个计数. 它们是这一页"一眼看出这个号是活的还是死的"那部分 -->
    <section class="count-row">
      <div class="count-box">
        <span class="count-value">{{ detail.counts.trackings }}</span>
        <span class="count-label">追番</span>
      </div>
      <div class="count-box">
        <span class="count-value">{{ detail.counts.reviewsAlive }}</span>
        <span class="count-label">在架评论</span>
      </div>
      <div class="count-box">
        <span class="count-value">{{ detail.counts.reviewsRemoved }}</span>
        <span class="count-label">已移除评论</span>
      </div>
      <div class="count-box">
        <span class="count-value">{{ detail.counts.episodesWatched }}</span>
        <span class="count-label">已看剧集</span>
      </div>
    </section>

    <!-- 三个只读小列表. 各自独立的空态文案 —— 混用一句「暂无数据」的话,
         "这个号没追番"与"服务端没发这个列表"就分不出来了. -->
    <section class="detail-section">
      <h3>最近追番</h3>
      <ul v-if="detail.trackings.length" class="mini-list">
        <li v-for="t in detail.trackings" :key="t.subjectId" class="mini-row">
          <span class="mini-main">{{ animeLabel(t) }}</span>
          <span class="mini-meta">{{ t.status }} · {{ t.progress }} 集</span>
          <span class="mini-time">{{ formatDateTime(t.updatedAt) }}</span>
        </li>
      </ul>
      <p v-else class="mini-empty">没有追番记录</p>
    </section>

    <section class="detail-section">
      <h3>最近评论</h3>
      <ul v-if="detail.reviews.length" class="mini-list">
        <li v-for="r in detail.reviews" :key="r.id" class="mini-row">
          <span class="mini-main">
            {{ animeLabel(r) }}
            <!-- 被移除的评论**照样出现在这里**(后端刻意含它们), 靠这个徽章区分.
                 不给徽章的话, 管理员刚移除的那条会与在架的混在一起, 而他会以为
                 自己点错了; 计数上「已移除 1」与列表里那条也必须对得上 -->
            <span v-if="r.deletedAt" class="status-badge status-removed">已移除</span>
          </span>
          <span class="mini-meta">{{ r.rating }} 分 · {{ r.content || '（无正文）' }}</span>
          <span class="mini-time">{{ formatDateTime(r.createdAt) }}</span>
        </li>
      </ul>
      <p v-else class="mini-empty">没有评论</p>
    </section>

    <section class="detail-section">
      <h3>管理操作记录</h3>
      <ul v-if="detail.actions.length" class="mini-list">
        <li v-for="a in detail.actions" :key="a.id" class="mini-row">
          <span class="mini-main">{{ actionLabel(a.action) }}</span>
          <span class="mini-meta">{{ a.actorName }} · {{ a.detail || '（无详情）' }}</span>
          <span class="mini-time">{{ formatDateTime(a.createdAt) }}</span>
        </li>
      </ul>
      <p v-else class="mini-empty">没有对这个账号的管理操作</p>
    </section>
  </template>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PhArrowLeft from '@icons/PhArrowLeft.vue.mjs'
import PhUser from '@icons/PhUser.vue.mjs'
import { getAdminUserDetail } from '../../api'
import { useLatestOnly } from '../../composables/useLatestOnly'
import { loadErrorMessage } from '../../utils/loadError'
import LoadingSpinner from '../../components/LoadingSpinner.vue'
import EmptyState from '../../components/EmptyState.vue'

/**
 * 用户详情(管理端): 一个账号的账号事实 + 四个计数 + 追番/评论/账本三个小列表.
 *
 * 【只读】这一页**没有**那四个破坏性动作按钮(封禁/解锁/改角色/重置密码). 那要配
 * confirm + toast + 刷新, 等于把 Users.vue 抄一遍; 而动作之后这一页上的计数该怎么变
 * 又是另一摊事. 本轮定位就是"能坐下来查一个人", 动作仍然只在列表页那一行上做.
 *
 * 【状态机四态】loading / error / notfound / ready. 后两者的分家是这一页最要紧的一条:
 * 混成一种就会出现"一个点了必然再失败的按钮"(详见模板里的注释).
 *
 * 【不做 URL 同步】这一页唯一的状态是 :id, 它本来就在路径上 —— 没有筛选、没有分页、
 * 没有排序, 也就没有任何东西需要写进 query. 同理路由上不加 scrollOnQueryChange.
 */
const route = useRoute()
const router = useRouter()
const request = useLatestOnly()

const state = ref('loading')
const detail = ref(null)
const error = ref('')

/**
 * id 的合法形状: 正整数.
 *
 * **必须在发请求之前判** —— 直接拿 route.params.id 拼进 URL 的话, 手输
 * `/admin/users/abc` 会带着 "abc" 发出去(后端回 400), 界面上就变成"加载失败 + 重试",
 * 而正确答案是"这个用户不存在". 顺带也挡住 "1.5" / "-1" / "1e3" 这类看着像数字的输入
 * (后端认 Long, 这几个都会 400).
 */
const ID_PATTERN = /^[1-9]\d*$/

const notFoundMessage = ref('用户不存在')

function goBackToList() {
  router.push('/admin/users')
}

function formatDateTime(d) {
  return d ? new Date(d).toLocaleString('zh-CN') : '-'
}

/**
 * 番剧名, 本地没缓存过那部番时为 null.
 *
 * 退化成「番剧 #656083」而不是编一个名字: 后端刻意不给「未知作品」兜底, 因为几条
 * 不同 subjectId 的记录会挤在同一个假名字下面, 而"这部番还没进本地库"本身是真信息.
 */
function animeLabel(row) {
  return row.animeTitle || `番剧 #${row.subjectId}`
}

/**
 * 动作码 → 中文. 与 Audit.vue 同一份口径: 认不出来的码**回退成原样的码**,
 * 而不是显示空白 —— 后端加了第六个动作时, 这里显示 `USER_PASSWORD_RESET`,
 * 总好过一行看起来什么都没发生的记录.
 */
const ACTION_LABELS = {
  USER_BAN: '封禁',
  USER_UNBAN: '解封',
  USER_ROLE: '改角色',
  USER_UNLOCK: '解锁',
  USER_PASSWORD_RESET: '重置密码',
}
function actionLabel(code) {
  return ACTION_LABELS[code] || code
}

/**
 * 取这个用户的详情.
 *
 * 令牌纪律与 Users.vue / Audit.vue 一字不差: 令牌在发请求**之前**取, await 之后
 * 每一行都先过 isCurrent, 而过期分支里**绝不能写 state** —— 那会让"还在飞的那次"
 * 的结果被后到的旧结果盖掉(见 useLatestOnly.js). 这里的状态是一个字符串而不是
 * loading 布尔, 所以过期分支写错的表现是"页面停在上一个人".
 */
async function load() {
  const id = String(route.params.id)
  if (!ID_PATTERN.test(id)) {
    detail.value = null
    notFoundMessage.value = `没有这个用户：${id}`
    state.value = 'notfound'
    return
  }

  const token = request.begin()
  error.value = ''
  state.value = 'loading'
  try {
    const res = await getAdminUserDetail(id)
    if (!request.isCurrent(token)) return
    if (res.data.code === 200 && res.data.data) {
      detail.value = res.data.data
      state.value = 'ready'
      return
    }
    error.value = `加载用户详情失败：服务端返回 ${res.data.code}`
    state.value = 'error'
  } catch (e) {
    if (!request.isCurrent(token)) return
    detail.value = null
    if (e?.response?.status === 404) {
      notFoundMessage.value = '用户不存在'
      state.value = 'notfound'
    } else {
      error.value = loadErrorMessage(e, '加载用户详情')
      state.value = 'error'
    }
  }
}

onMounted(load)

/**
 * 同一组件实例换 id 时要重新取.
 *
 * 点侧栏或者手改地址栏都**不会**重挂载这个组件(路由记录是同一条, 只是 params 变了),
 * 不加这个 watch 就是"地址变了, 页面还是上一个人" —— 而看起来一切正常.
 */
watch(() => route.params.id, load)
</script>

<style scoped>
.detail-back { margin-bottom: 12px; }
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 13px; color: var(--text-secondary); text-decoration: none;
}
.back-link:hover { color: var(--text); }

/* 卡片与表格同源(admin.css 那套 --card / --card-border / --radius),
   不新造一套视觉语言 */
.detail-card {
  background: var(--card); border: 1px solid var(--card-border);
  border-radius: var(--radius); padding: 20px; margin-bottom: 16px;
}
.account-head { display: flex; align-items: center; gap: 14px; }
.account-avatar {
  width: 56px; height: 56px; flex: 0 0 56px;
  border-radius: 50%; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-secondary); color: var(--text-muted);
}
/* 圆形的关键: 图片本身也要被裁 —— 只给外层 border-radius 的话,
   方图会从圆形容器里**溢出来**(overflow:hidden 在有些浏览器下对 img 不生效) */
.account-avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
.account-name { min-width: 0; }
.account-name h2 { margin: 0 0 6px; font-size: 18px; color: var(--text); }
.account-badges { display: flex; flex-wrap: wrap; gap: 6px; }

.account-facts {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px 20px; margin: 18px 0 0;
}
.fact dt { font-size: 12px; color: var(--text-muted); margin-bottom: 2px; }
.fact dd { margin: 0; font-size: 13px; color: var(--text); word-break: break-all; }

.count-row {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px; margin-bottom: 16px;
}
.count-box {
  background: var(--card); border: 1px solid var(--card-border);
  border-radius: var(--radius); padding: 14px 16px;
  display: flex; flex-direction: column; gap: 2px;
}
.count-value { font-size: 20px; font-weight: 600; color: var(--text); }
.count-label { font-size: 12px; color: var(--text-muted); }

.detail-section { margin-bottom: 20px; }
.detail-section h3 {
  margin: 0 0 8px; font-size: 14px; font-weight: 600; color: var(--text);
}
.mini-list {
  list-style: none; margin: 0; padding: 0;
  background: var(--card); border: 1px solid var(--card-border);
  border-radius: var(--radius); overflow: hidden;
}
.mini-row {
  display: flex; align-items: baseline; gap: 12px;
  padding: 10px 16px; border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.mini-row:last-child { border-bottom: none; }
/* min-width:0 + flex:1 让长正文可以换行收缩 —— 少了它, 一条长评论会把整行撑出
   横向滚动条, 而这一页有三个列表, 每个都可能带一条长文本 */
.mini-main { flex: 1 1 auto; min-width: 0; color: var(--text); }
.mini-meta {
  flex: 2 1 auto; min-width: 0; color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.mini-time { flex: 0 0 auto; font-size: 12px; color: var(--text-muted); }
.mini-empty { margin: 0; padding: 14px 16px; font-size: 13px; color: var(--text-muted);
  background: var(--card); border: 1px solid var(--card-border); border-radius: var(--radius); }

/* 徽章: 与 Users.vue 同一套 tokens.css 语义变量, 不新造色值.
   这几个 class 在各页是 scoped 的, 所以规则要在这里再写一份 —— 但**色值只有一份**,
   两处一起改名就够了. */
.role-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.role-admin { background: var(--badge-ink-bg); color: var(--badge-ink-fg); }
.role-user { background: var(--badge-blue-bg); color: var(--badge-blue-fg); }
.status-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; }
.status-active { background: var(--badge-green-bg); color: var(--badge-green-fg); }
.status-disabled { background: var(--badge-red-bg); color: var(--badge-red-fg); }
.status-locked { background: var(--badge-amber-bg); color: var(--badge-amber-fg); }
/* 已移除: 复用琥珀那一套(与"已锁定"同色) —— 两者都是"这个状态需要你看一眼",
   而新造一个色值只会多一个要维护的变量 */
.status-removed { background: var(--badge-amber-bg); color: var(--badge-amber-fg); margin-left: 6px; }

/* 窄屏: 三个列表的"时间"那一格会先把正文挤没, 所以让它整行摞起来 */
@media (max-width: 640px) {
  .mini-row { flex-direction: column; gap: 2px; }
  .mini-time { font-size: 11px; }
}
</style>
