<template>
  <div v-if="!loading" class="profile-page">
    <!-- Banner -->
    <div class="p-banner">
      <div class="p-banner-inner"></div>
    </div>

    <!-- Header: Avatar + Stats -->
    <div class="p-header">
      <div class="p-avatar-wrap">
        <div class="p-avatar">
          <!-- 有头像就显示图片, 没有就退回图标. `userStore.user?.avatar` 是后端
               `user.avatar` 那一列 —— 它是一个**带版本号的 URL**(?v=...), 所以
               换头像之后这个值本身就变了, 不需要在这里做任何缓存处理.
               avatarBroken 兜的是"URL 在、图却是死的"(记录被删了之类): 那种情况下
               这一格不该变成一个破图标记, 它原本只是"没有头像"而已. -->
          <img
            v-if="userStore.user?.avatar && !avatarBroken"
            :src="userStore.user.avatar"
            :alt="userStore.user?.username || '头像'"
            class="p-avatar-img"
            @error="avatarBroken = true"
          />
          <PhUserCircle v-else :size="80" weight="fill" />
        </div>
        <div class="p-avatar-actions">
          <button class="pa-btn" type="button" :disabled="avatarLoading" @click="pickAvatar">
            {{ avatarLoading ? '上传中…' : (userStore.user?.avatar ? '更换' : '上传头像') }}
          </button>
          <button
            v-if="userStore.user?.avatar"
            class="pa-btn pa-btn-muted"
            type="button"
            :disabled="avatarLoading"
            @click="handleDeleteAvatar"
          >删除</button>
        </div>
        <!-- accept 与后端白名单**逐字对应**, 不含 webp: 后端 ImageIO 没有 WebP 解码器,
             收进来也只会被拒. 两处不一致的表现是"文件选择框里能选中、上传后被拒",
             那比一开始就选不中更让人困惑. -->
        <input
          ref="fileInput"
          class="pa-file"
          type="file"
          accept="image/png,image/jpeg"
          @change="handleAvatarPick"
        />
      </div>
      <div class="p-info">
        <h1 class="p-name">{{ userStore.user?.username }}</h1>
        <p class="p-email">{{ userStore.user?.email || '' }}</p>
        <div class="p-stats">
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalAnime || 0 }}</span>
            <span class="ps-lbl">追番</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalEpisodes || 0 }}</span>
            <span class="ps-lbl">集数</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.totalReviews || 0 }}</span>
            <span class="ps-lbl">评论</span>
          </div>
          <div class="p-stat">
            <span class="ps-num">{{ stats?.avgScore || '-' }}</span>
            <span class="ps-lbl">均分</span>
          </div>
          <!-- 「在看」要的是 status=watching 的条数. 改前是
               totalAnime - completed 的差值 —— 那是「除了看完的之外全都算在看」,
               于是想看/搁置/抛弃的番也被算了进去. 同一页下面的筛选栏就摆着
               「在看 N」, 两个数字经常对不上, 而用户没有理由知道该信哪个.
               口径改成和筛选栏完全一致(都从 trackings 来). -->
          <div class="p-stat">
            <span class="ps-num">{{ counts.watching || 0 }}</span>
            <span class="ps-lbl">在看</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Filter Tabs -->
    <!-- 出错时不显示筛选栏: 这些计数是从 trackings 算出来的, 加载失败时全是空的,
         摆着一排「0」只会让人以为追番记录真的没了 -->
    <div class="p-tabs" v-if="!error">
      <button v-for="f in filters" :key="f.key" class="p-tab" :class="{ active: filter === f.key }" @click="filter = f.key">
        {{ f.label }}
        <span v-if="counts[f.key] !== undefined" class="p-tab-count">{{ counts[f.key] }}</span>
      </button>
      <div class="p-tab-spacer"></div>
      <button class="p-tab p-tab-sort" @click="sortBy = sortBy === 'date' ? 'score' : 'date'">
        {{ sortBy === 'date' ? '按时间' : '按评分' }}
      </button>
    </div>

    <!-- Anime List -->
    <!-- 错误态优先于空态: 改前请求失败时 trackings 是空的, 页面显示的是
         「还没有追番记录」—— 把「没拉到」说成了「你没有」, 用户会以为数据丢了 -->
    <EmptyState
      v-if="error"
      type="error"
      :message="error"
      action-label="重试"
      @action="loadProfile"
    />
    <template v-else-if="filtered.length > 0">
      <!-- 这一行只在有记录时出现, 而且只出现一次 —— 每张卡片都挂一句的话,
           列表一滚就变成噪音. 它回答的是「+1 集」这个按钮字面上答不出的那件事:
           加的是**你的进度**, 不是番剧的集数. -->
      <p class="p-hint">点「+1 集」= 这一集看完了，进度往前推一格；详情页里也能按集打卡或直接输数字</p>
      <div class="p-list">
        <div
          v-for="item in filtered"
          :key="item.id"
          class="p-card"
          @click="$router.push(`/anime/${item.subjectId}`)"
        >
          <div class="pc-cover">
            <img :src="item.animeCover || fallbackImg" :alt="item.animeTitle" @error="e => e.target.src = fallbackImg" />
          </div>
          <div class="pc-body">
            <div class="pc-top">
              <div class="pc-title">{{ item.animeTitle || '番剧 #' + item.subjectId }}</div>
              <div class="pc-score" v-if="item.score"><PhStar :size="11" weight="fill" /> {{ item.score }}</div>
            </div>
            <div class="pc-meta">
              <span class="pc-status-badge" :class="'st-' + item.status">{{ statusLabel[item.status] }}</span>
              <span class="pc-type" v-if="item.animeType">{{ item.animeType }}</span>
              <span class="pc-year" v-if="item.animeYear">{{ item.animeYear }}</span>
            </div>
            <div class="pc-progress" v-if="item.totalEpisodes">
              <div class="pc-bar"><div class="pc-fill" :style="{ width: pct(item) + '%' }"></div></div>
              <span class="pc-prog-text">{{ item.progress || 0 }}/{{ item.totalEpisodes }}</span>
            </div>
          </div>
          <div class="pc-actions" @click.stop>
            <!-- 到顶就禁用. 改前是无限 +1: 一部 12 集的番能被点成 13/12,
                 进度条按 pct() 卡在 100% 所以看不出来, 但数字就摆在那儿;
                 而且这个值会原样进数据库, 之后每一处「已看 N 集」的统计都带着它 -->
            <button
              class="pca-btn"
              :disabled="atLastEpisode(item)"
              :title="plusOneHint(item)"
              :aria-label="plusOneHint(item)"
              @click="quickUpdate(item, 'progress', nextProgress(item))"
            >+1 集</button>
            <select class="pca-select" :value="item.status" @change="e => updateStatus(item, e.target.value)">
              <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.short }}</option>
            </select>
          </div>
        </div>
      </div>
    </template>

    <EmptyState v-else type="tracking" message="还没有追番记录">
      <router-link to="/" style="color:var(--primary);">去发现动漫</router-link>
    </EmptyState>

    <!-- 通知: 有人回复了我的评论 / 赞了我的评论 / 赞了我的回复.
         刻意放在追番列表**下面**: 这一页的主任务是追番管理, 通知是附带的,
         排在它前面会把列表推下去. -->
    <section v-if="!error" class="p-notices">
      <h2 class="pf-title">通知</h2>
      <!-- 三态, 且失败优先于空态: 拉不到时说"还没有人回复或赞过你"是把"没拉到"
           说成了"你没有" —— 与这一页追番列表那条是同一件事.
           "加载中"那一态是必需的: 通知与追番列表并发拉, 主内容先到时列表还没回来,
           少了它就会闪一下空态(而那一下正是上面那句谎话). -->
      <div v-if="!noticesLoaded" class="pn-hint">加载中…</div>
      <div v-else-if="noticeError" class="pn-hint pn-hint-err">{{ noticeError }}</div>
      <div v-else-if="!notices.length" class="pn-hint">还没有人回复或赞过你</div>
      <div v-else class="pn-list">
        <!-- 点进那部番的详情页. 不做"直接滚到那条评论": 详情页没有按评论定位的
             锚点, 而评论本来就是整页展开的, 找得到 -->
        <div
          v-for="n in notices"
          :key="n.id"
          class="pn-item"
          :class="{ 'pn-unread': !n.read }"
          @click="$router.push(`/anime/${n.subjectId}`)"
        >
          <!-- 头像: 有就用图, 没有就用名字首字母. 这一格是**32px 的圆**,
               所以图必须是 cover 裁切 —— 不裁的话一张方图会被压扁成椭圆 -->
          <div class="pn-avatar">
            <img v-if="n.actorAvatar" :src="n.actorAvatar" :alt="n.actorName || ''" class="pn-avatar-img" />
            <template v-else>{{ (n.actorName || '?')[0] }}</template>
          </div>
          <div class="pn-body">
            <div class="pn-top">
              <span class="pn-name">{{ n.actorName }}</span>
              <span class="pn-action">{{ noticeAction(n.type) }}</span>
              <span class="pn-time">{{ fmtDate(n.createdAt) }}</span>
            </div>
            <!-- 摘要可能为空: 评论可以只打分不写字(服务端把空正文回成 null,
                 空串会让"有内容但看不见"这件事分不出来) -->
            <div class="pn-quote">{{ n.reviewContent || '（无文字）' }}</div>
            <!-- 三类里只有"赞了我的回复"和"回复了我"有这一行(赞评论那条没有回复) -->
            <div v-if="n.replyContent" class="pn-text">{{ n.replyContent }}</div>
          </div>
        </div>
      </div>
      <!-- 分页是这一块相对旧版"收到的回复"最大的变化: 那一版服务端封顶 30 条且
           不分页, 到顶时只能写一句"只显示最近 30 条" -->
      <Pagination :current-page="noticePage" :total-pages="noticeTotalPages" @change="goNoticePage" />
    </section>

    <!-- 改密码.
         刻意**不看** error: error 说的是"追番列表没拉到", 而这个表和它没有关系 ——
         列表挂了就不让人改密码, 是拿另一件事的失败去关掉一个入口.
         也刻意不做成弹窗: 改密是一个低频、需要想一想的动作, 塞进模态框里
         反而更容易点错; 页面底部这个位置本来也没人路过会误触. -->
    <section class="p-security">
      <h2 class="pf-title">账号安全</h2>
      <form class="sec-form" @submit.prevent="handleChangePassword">
        <label class="sec-label" for="sec-old">原密码</label>
        <!-- current-password / new-password 不是可省的装饰: 说成同一个值,
             浏览器会把已存的旧密码填进"新密码"那一栏, 或者反过来 -->
        <input
          id="sec-old"
          v-model="pwd.oldPassword"
          class="sec-input"
          type="password"
          autocomplete="current-password"
          placeholder="当前使用的密码"
        />
        <label class="sec-label" for="sec-new">新密码</label>
        <input
          id="sec-new"
          v-model="pwd.newPassword"
          class="sec-input"
          type="password"
          autocomplete="new-password"
          placeholder="至少8位，需含字母和数字"
          minlength="8"
          maxlength="100"
        />
        <label class="sec-label" for="sec-confirm">确认新密码</label>
        <!-- 确认栏是这个表单里最该有的一格: 新密码打错一个字符, 服务端照样
             接受, 而**下一次登录才会发现** —— 那时人已经在门外了 -->
        <input
          id="sec-confirm"
          v-model="pwd.confirmPassword"
          class="sec-input"
          type="password"
          autocomplete="new-password"
          placeholder="再次输入新密码"
        />
        <div v-if="pwdError" class="sec-error">{{ pwdError }}</div>
        <div v-else class="sec-hint">改完之后其它设备上的登录会失效，需要重新登录</div>
        <button class="sec-submit" type="submit" :disabled="pwdLoading">
          {{ pwdLoading ? '提交中...' : '修改密码' }}
        </button>
      </form>
    </section>
  </div>

  <div v-else class="page-container">
    <LoadingSpinner />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useNotificationStore } from '../stores/notification'
import { getTrackingList, getOverallStats, saveTracking, getNotifications, markNotificationsRead, changePassword, uploadAvatar, deleteAvatar } from '../api'
import { loadErrorMessage } from '../utils/loadError'
import { COVER_FALLBACK as fallbackImg } from '../utils/fallbackImg'
import { useToast } from '../composables/useToast'
import PhUserCircle from '@icons/PhUserCircle.vue.mjs'
import PhStar from '@icons/PhStar.vue.mjs'
import LoadingSpinner from '../components/LoadingSpinner.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'

const router = useRouter()
const userStore = useUserStore()
const notificationStore = useNotificationStore()
const { show: toast } = useToast()
const loading = ref(true)
const error = ref('')
const trackings = ref([])
const stats = ref(null)
const filter = ref('all')
const sortBy = ref('date')

// ── 通知 ──
const notices = ref([])
const noticesLoaded = ref(false)
const noticeError = ref('')
const noticePage = ref(1)
const noticeTotal = ref(0)

/** 一页几条. 与后端 NotificationService.DEFAULT_PAGE_SIZE 同一个数 —— 请求里发的是它,
 *  而 totalPages 也要按它算, 两边不一致时"最后一页"会点到空页(与 ADMIN_PAGE_SIZE 同一条理由) */
const NOTICE_PAGE_SIZE = 20
const noticeTotalPages = computed(() => Math.ceil(noticeTotal.value / NOTICE_PAGE_SIZE) || 1)

/** 三类通知在行里怎么念. 键是后端 Notification 的三个常量. */
const NOTICE_ACTION = {
  REPLY: '回复了你的评论',
  REVIEW_LIKE: '赞了你的评论',
  REPLY_LIKE: '赞了你的回复',
}
/** 认不出来的类型给一句兜底: 服务端将来加了第四类而前端没跟上时, 那一行应当是
 *  "某人 和你有互动", 而不是一个空白的动词位置 */
function noticeAction(type) {
  return NOTICE_ACTION[type] || '和你有互动'
}

const statusLabel = { want_to_watch: '想看', watching: '在看', watched: '看过', on_hold: '搁置', dropped: '抛弃' }
const statusOptions = [
  { value: 'watching', short: '在看' },
  { value: 'watched', short: '看过' },
  { value: 'want_to_watch', short: '想看' },
  { value: 'on_hold', short: '搁置' },
  { value: 'dropped', short: '抛弃' },
]

const filters = [
  { key: 'all', label: '全部' },
  { key: 'watching', label: '在看' },
  { key: 'watched', label: '看过' },
  { key: 'want_to_watch', label: '想看' },
  { key: 'on_hold', label: '搁置' },
  { key: 'dropped', label: '抛弃' },
]

const counts = computed(() => {
  const c = { all: trackings.value.length }
  for (const t of trackings.value) {
    c[t.status] = (c[t.status] || 0) + 1
  }
  return c
})

const filtered = computed(() => {
  let list = filter.value === 'all' ? trackings.value : trackings.value.filter(t => t.status === filter.value)
  if (sortBy.value === 'score') {
    list = [...list].sort((a, b) => (b.score || 0) - (a.score || 0))
  }
  return list
})

/** 通知那一条的日期. 与详情页的 fmt 同一个口径(只到日), 但不共用 ——
 *  那个是 AnimeDetail 的组件内函数, 复制一份比为一个 8 行的工具建一个模块便宜 */
function fmtDate(d) {
  return d ? new Date(d).toLocaleDateString('zh-CN') : ''
}

function pct(item) {
  if (!item.totalEpisodes) return 0
  return Math.min(100, Math.round(((item.progress || 0) / item.totalEpisodes) * 100))
}

/** 下一集的集数, 封顶在总集数.
 *  总集数未知(后端没给)时不封顶 —— 那种情况下任何上限都是我们编的.
 *
 *  这里的 Math.min 与按钮上的 :disabled 是同一件事的两道锁, 而且**前者是多余的**:
 *  按钮禁用了就点不到, 而能点到的情况下 progress < totalEpisodes, 加一必不越界.
 *  留着它是因为禁用状态依赖渲染时的那份数据, 而这是道免费的保险 ——
 *  只挡住一道门的话, 将来谁把禁用条件放宽一点, 越界就悄悄回来了. */
function nextProgress(item) {
  const next = (item.progress || 0) + 1
  const total = item.totalEpisodes
  return total ? Math.min(next, total) : next
}

/** 已经看到最后一集(或超过). 总集数未知时返回 false, 与 nextProgress 一致 */
function atLastEpisode(item) {
  return !!item.totalEpisodes && (item.progress || 0) >= item.totalEpisodes
}

/**
 * 按钮上那句提示.
 *
 * 按钮只有「+1 集」三个字, 而它答不出最要紧的那半句: 加的是**你的进度**,
 * 不是番剧的集数 —— 用户问过一次「+1啥意思」, 就是被这三个字绊住的.
 * 与其解释语义, 不如把结果直接写出来: 点完会变成第几集, 用具体数字说话.
 *
 * 「到顶了」那条文案也要写, 哪怕禁用的按钮在浏览器里不弹 title:
 * 它同时是 aria-label, 屏幕阅读器读得到, 而禁用按钮读出来只有「按钮, 不可用」
 * 是一句不说清原因的话.
 */
function plusOneHint(item) {
  if (atLastEpisode(item)) return '已经看到最后一集了'
  return `这一集看完了：进度记成第 ${nextProgress(item)} 集`
}

/**
 * 改状态: **只发 status**.
 *
 * 改前这里把整行发回去(含本地那份 progress 与 score), 而"本地那份"是这个页面
 * 进来时拉的 —— 页面放一会儿、或者去详情页打过卡再切回来, 进度就被这一次改状态
 * 悄悄写回旧值, 而界面上两处都显示成功. 服务端现在是局部更新, 没动的字段不该发.
 */
async function updateStatus(item, newStatus) {
  try {
    await saveTracking({ subjectId: item.subjectId, status: newStatus })
    item.status = newStatus
    toast('已更新', 'success')
  } catch (e) { toast('更新失败', 'error') }
}

/** 「+1」: 只发 progress. field 现在是载荷的键名 —— 改前它是个传进来没人用的死参数 */
async function quickUpdate(item, field, val) {
  try {
    await saveTracking({ subjectId: item.subjectId, [field]: val })
    item[field] = val
  } catch (e) { toast('更新失败', 'error') }
}

onMounted(loadProfile)

// ── 改密码 ──

/**
 * 与后端 PasswordPolicy + {@code Register.vue} 同一份口径. 后端那份是权威,
 * 这份只是为了在提交前就把问题拦下来, 省掉一次往返。
 *
 * <p>与 Register.vue 的那一份**没有抽成公共模块**, 是已知的一处重复: 两处都是
 * 「前端复述后端规则」, 而抄错的表现是拦不下(服务端仍然兜住)或多拦一下,
 * 不是数据坏掉。为它建一个模块的收益比不上让两个表单各自读起来是完整的。
 */
const PASSWORD_LETTER_AND_DIGIT = /^(?=.*[A-Za-z])(?=.*\d).*$/

const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdError = ref('')
const pwdLoading = ref(false)

function validatePassword() {
  if (!pwd.oldPassword) return '请输入原密码'
  if (pwd.newPassword !== pwd.confirmPassword) return '两次密码输入不一致'
  if (pwd.newPassword.length < 8) return '密码至少 8 位'
  if (!PASSWORD_LETTER_AND_DIGIT.test(pwd.newPassword)) return '密码必须同时包含字母和数字'
  // 服务端也会拦这一条(它才是权威), 但这两种错误在服务端是同一段文案,
  // 前端先拦一次能让"哪一格填错了"更明确
  if (pwd.newPassword === pwd.oldPassword) return '新密码不能与原密码相同'
  return ''
}

async function handleChangePassword() {
  pwdError.value = ''
  const problem = validatePassword()
  if (problem) {
    pwdError.value = problem
    return
  }
  pwdLoading.value = true
  try {
    const res = await changePassword({
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword,
    })
    /* 把响应里那张新 token 存回去. 这一行不是"顺手更新一下" —— 改密会让改密之前
       签发的 token 全部作废, 手上这张正在其中; 不存新的, 下一个请求就是 401,
       用户看到的是「我刚改完密码就被登出了」。 */
    userStore.setUser(res.data.data)
    pwd.oldPassword = ''
    pwd.newPassword = ''
    pwd.confirmPassword = ''
    toast('密码已修改，其它设备需重新登录', 'success')
  } catch (e) {
    // 走服务端的原话(「原密码不正确」等) —— 那些文案比前端能编的更准
    pwdError.value = e.response?.data?.message || '修改失败，请稍后重试'
  }
  pwdLoading.value = false
}

// ── 头像 ──

/**
 * 与后端 AvatarService 的两条硬约束同一份口径: 512KB、只收 png/jpeg。
 *
 * <p>后端那一份才是权威(它还会读文件头判尺寸、判真实格式), 这一份纯粹是为了省掉一次
 * 白跑的往返 —— 传一张 5MB 的图上去、等它传完再被拒, 体验上差得很远。所以两处**故意**
 * 没有任何"谁同步谁"的机制: 前端多拦一下不会坏数据, 少拦一下服务端仍然兜住。
 *
 * <p>这里判的是 `file.type` 而不是扩展名 —— 扩展名是文件名里的一段字, 可以随便写;
 * 而 `file.type` 虽然也能伪造, 但它与后端第 2 关看的**是同一个东西**, 两边不会打架。
 */
const AVATAR_MAX_BYTES = 512 * 1024
const AVATAR_TYPES = ['image/png', 'image/jpeg']

const fileInput = ref(null)
const avatarLoading = ref(false)
/** 图挂了(URL 在、取不到图)时退回图标, 见模板里的注释 */
const avatarBroken = ref(false)

/** 什么都不做, 只把系统文件框弹出来 —— 真正的 `<input type="file">` 是藏着的 */
function pickAvatar() {
  fileInput.value?.click()
}

/** 返回一句给用户看的话, 通过则返回空串 */
function checkAvatarFile(file) {
  if (!AVATAR_TYPES.includes(file.type)) return '只支持 PNG 或 JPEG 格式的图片'
  if (file.size > AVATAR_MAX_BYTES) return '图片不能超过 512KB'
  return ''
}

async function handleAvatarPick(event) {
  const file = event.target.files?.[0]
  /* 先把 input 的值清掉再去上传. 不清的话"同一个文件选了第二次"不会触发 change
     (值没变), 用户看到的是"点了没反应" —— 而重试一次正是这里最常见的动作(第一次
     上传失败之后)。清掉 value 之后同一个文件也能再次触发。 */
  event.target.value = ''
  if (!file) return

  const problem = checkAvatarFile(file)
  if (problem) {
    toast(problem, 'error')
    return
  }

  avatarLoading.value = true
  try {
    const res = await uploadAvatar(file)
    // 服务端回的地址(带 ?v= 版本号)写回 store, 于是这一页、导航栏、以及刷新之后的
    // localStorage 里都是新地址
    userStore.setAvatar(res.data.data.avatar)
    // 上一张图加载失败留下的标记要清掉, 否则新图明明能读, 这一格还是显示图标
    avatarBroken.value = false
    toast('头像已更新', 'success')
  } catch (e) {
    // 走服务端的原话:「图片不能超过 512KB」「图片尺寸不能超过 2048×2048 像素」
    // 这类只有它知道(它真的读了文件头)
    toast(e.response?.data?.message || '头像上传失败，请重试', 'error')
  }
  avatarLoading.value = false
}

async function handleDeleteAvatar() {
  avatarLoading.value = true
  try {
    await deleteAvatar()
    userStore.setAvatar(null)
    avatarBroken.value = false
    toast('头像已删除', 'success')
  } catch (e) {
    toast('删除失败，请重试', 'error')
  }
  avatarLoading.value = false
}

// ── 通知 ──

/**
 * 拉一页通知, 并顺手把未读标成已读.
 *
 * <p><b>顺序是"先读列表、再标已读", 不能反过来。</b> 标已读之后再读, 服务端回给我们的
 * 每一行都是 read: true —— 于是"哪几条是新的"这个信息在页面首次渲染时就没了(未读态
 * 那一道竖线永远不出现), 而 `read` 这个字段也就成了摆设。反过来做, 行里带着标记前的
 * 状态渲染出来, 红点同时被清掉。
 *
 * <p>标已读**只在真的读到未读行时**发: 未读的必然是最新的几条(排序是 created_at DESC,
 * 而"未读"就是 read_at IS NULL), 所以第一页里没有未读 ⇒ 服务端也没有未读。这一条是
 * 从这个页面的读法推出来的, 不是猜的; 它省掉的是每进一次个人页都发一个必然改 0 行的 PUT。
 *
 * <p>它自己咽掉全部失败(包括标已读的): 通知是这一页的附带区块, 拉不到时最坏的结果
 * 应该是"这一块空着", 而不是整页落到「加载追番记录失败」—— 追番记录其实好好的。
 */
async function loadNotices() {
  noticeError.value = ''
  try {
    const res = await getNotifications({ page: noticePage.value, limit: NOTICE_PAGE_SIZE })
    const data = res.data.data || {}
    notices.value = data.list || []
    noticeTotal.value = data.total || 0
    if (notices.value.some(n => !n.read)) await markRead()
  } catch (e) {
    notices.value = []
    noticeTotal.value = 0
    noticeError.value = '通知暂时拉不到'
  }
  noticesLoaded.value = true
}

/**
 * 把未读全部标为已读, 并就地清掉导航栏的红点.
 *
 * <p>清的是 store 里那个数字(clear()), 不是重新问一次服务端: 我们**知道**结果就是 0,
 * 再问一遍只是把同一件事问第二次, 而且那一次往返里红点还亮着。
 *
 * <p>失败不上报: 它是一次"尽力而为"的收尾, 标不上最多是红点多亮一会儿, 下次进来还会
 * 再试。为它弹一个 toast 是把一件用户没请求过的事说成出了问题。
 */
async function markRead() {
  try {
    await markNotificationsRead()
    notificationStore.clear()
  } catch (e) { /* 见上 */ }
}

/** 翻页: 只重拉通知那一块(追番列表与统计与页码无关) */
function goNoticePage(p) {
  noticePage.value = p
  loadNotices()
}

// 单独取名是为了让错误态上的「重试」能重新跑这整段(账号信息来自 store,
// 失败的是列表和统计这两个接口)
async function loadProfile() {
  if (!userStore.loggedIn) { router.push('/login'); return }
  loading.value = true
  error.value = ''
  /* 通知与主内容并发拉, 且**不 await**: 它是附带区块, 没有理由让追番列表等它。
     它自己咽掉失败(见 loadNotices), 所以"没 await"不等于"它的失败会漏到这里"。 */
  loadNotices()
  try {
    const [listRes, statsRes] = await Promise.all([
      getTrackingList(),
      getOverallStats(),
    ])
    trackings.value = (listRes.data.data || []).map(t => ({
      ...t,
      animeYear: t.animeDate ? t.animeDate.substring(0, 4) : null,
    }))
    stats.value = statsRes.data.data || {}
  } catch (e) {
    error.value = loadErrorMessage(e, '加载追番记录')
    trackings.value = []
    stats.value = {}
  }
  loading.value = false
}
</script>

<style scoped>
.profile-page { padding-bottom: 60px; }

/* ── Banner ── */
/* 这张横幅是**有意保留深色**的三处孤岛之一(见 tokens.css): 它上面压着头像和
   用户名, 换成浅底的话白字会糊掉, 而且整个个人页会失去重心。
   但"深色"不等于"紫色" —— 改前是紫→靛的四段渐变加紫/粉两团光晕
   (#1a1040/#2d1b69 + rgba(168,85,247)/rgba(236,72,153)), 那是上一版的配色。
   现在换成中性深色 + 一层很淡的白色光晕, 深色的分量留着, 颜色还回去。 */
.p-banner { height: 160px; position: relative; overflow: hidden; background: linear-gradient(135deg, var(--hero-bg) 0%, var(--hero-bg-2) 45%, var(--hero-bg) 100%); }
.p-banner-inner { position: absolute; inset: 0; background: radial-gradient(circle at 30% 50%, rgba(255,255,255,.07) 0%, transparent 60%), radial-gradient(circle at 70% 30%, rgba(255,255,255,.05) 0%, transparent 50%); }

/* ── Header ── */
.p-header { display: flex; gap: 28px; max-width: 1000px; margin: -44px auto 0; padding: 0 32px; position: relative; z-index: 2; }
.p-avatar-wrap { flex-shrink: 0; }
.p-avatar { width: 96px; height: 96px; border-radius: 50%; background: var(--card); border: 4px solid var(--bg); box-shadow: 0 4px 24px rgba(0,0,0,.3); display: flex; align-items: center; justify-content: center; color: var(--primary); }
/* 图片要自己再来一次 border-radius: 上面那个 50% 是给这层容器的, 而 scoped 样式
   之下溢出不会被裁 —— 少了这一行, 头像是一张**方图**压在一个圆的描边环里, 四个
   角露在环外面. (容器没写 overflow: hidden 是故意的: 那样会连描边一起裁掉.)
   object-fit: cover 而不是 contain: 非正方形的图用 contain 会留出两条背景色的边,
   而这一格是圆的, 留边看起来像图没加载完 */
.p-avatar-img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; }
/* 头像下面那两个小按钮. 宽度跟着头像那一列(96px), 于是它们与头像的左边缘对齐 */
.p-avatar-actions { display: flex; gap: 6px; margin-top: 8px; }
.pa-btn {
  flex: 1; padding: 3px 8px; border-radius: 6px; cursor: pointer; font-family: inherit;
  font-size: 11px; font-weight: 600; white-space: nowrap;
  border: 1px solid var(--card-border); background: var(--card); color: var(--text-secondary);
  transition: all var(--transition);
}
.pa-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.pa-btn:disabled { opacity: .5; cursor: not-allowed; }
/* 「删除」比「更换」低一档: 这两个按钮挨在一起, 同样醒目的话误点的代价不对等 */
.pa-btn-muted { background: none; color: var(--text-muted); }
/* 文件选择框本身永远不出现 —— 它由「上传头像/更换」那个按钮代点(programmatic
   .click() 对隐藏元素同样有效). 不用 opacity/尺寸压零那一类: 那样元素仍在文档流里、
   仍在 Tab 顺序上, 键盘用户会停在一个看不见的控件上, 而这里已经有了真的按钮 */
.pa-file { display: none; }
.p-info { flex: 1; padding-top: 48px; min-width: 0; }
.p-name { font-size: 24px; font-weight: 800; color: var(--text); margin-bottom: 2px; }
.p-email { font-size: 13px; color: var(--text-muted); margin-bottom: 16px; }
.p-stats { display: flex; gap: 32px; }
.p-stat { text-align: center; }
/* 统计数字换到显示体: 22px 是显示级字号, 而 800 落在正文字体上时是伪粗体
   (IBM Plex Sans 最粗 700)。上面那条 .p-name 不用改 —— 它是 <h1>, 显示体
   由 base.css 的 h1 规则给它。 */
.ps-num { font-family: var(--font-display); font-size: 22px; font-weight: 800; color: var(--text); }
.ps-lbl { font-size: 11px; color: var(--text-muted); display: block; }

/* ── Tabs ── */
.p-tabs { display: flex; gap: 4px; max-width: 1000px; margin: 24px auto 0; padding: 0 32px; border-bottom: 2px solid var(--border); }
.p-tab { display: flex; align-items: center; gap: 6px; padding: 10px 16px; border: none; background: none; font-size: 13px; font-weight: 500; color: var(--text-secondary); cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -2px; transition: all var(--transition); font-family: inherit; }
.p-tab:hover { color: var(--text); }
.p-tab.active { color: var(--primary); border-bottom-color: var(--primary); }
.p-tab-count { font-size: 11px; color: var(--text-muted); }
.p-tab.active .p-tab-count { color: var(--primary); }
.p-tab-spacer { flex: 1; }
.p-tab-sort { font-size: 12px; color: var(--text-muted); }

/* ── List ── */
/* 列表上方那句说明. 与 .p-list 共用一套宽度与边距, 于是标题、说明、卡片
   三条左边缘对得齐 —— 差几个像素看起来就像两页拼起来的(通知那块记过同一条) */
.p-hint { max-width: 1000px; margin: 14px auto 0; padding: 0 32px; font-size: 12px; color: var(--text-muted); }
.p-list { max-width: 1000px; margin: 20px auto 0; padding: 0 32px; display: flex; flex-direction: column; gap: 8px; }
.p-card { display: flex; gap: 16px; padding: 16px; background: var(--card); border: 1px solid var(--card-border); border-radius: var(--radius); cursor: pointer; transition: all var(--transition); align-items: center; }
.p-card:hover { border-color: var(--primary-line); background: var(--card-hover); }
.pc-cover { width: 64px; aspect-ratio: 3/4; border-radius: 6px; overflow: hidden; background: var(--bg-secondary); flex-shrink: 0; }
.pc-cover img { width: 100%; height: 100%; object-fit: cover; }
.pc-body { flex: 1; min-width: 0; }
.pc-top { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
.pc-title { font-size: 15px; font-weight: 700; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc-score { font-size: 14px; color: var(--star); font-weight: 700; white-space: nowrap; }
.pc-meta { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; font-size: 11px; }
/* 五个状态的底色/字色走 tokens.css 的语义徽章变量.
   改前是写死的「半透明底 + 亮字」, 那套在暗色下没问题, 但亮色主题下
   字色(#60a5fa / #34d399 …)是给深色底挑的, 贴在近白的卡片上几乎读不出来.
   token 里亮色那一套是浅底 + 深字, 两边都各有一套. */
.pc-status-badge { padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.st-watching { background: var(--badge-blue-bg); color: var(--badge-blue-fg); }
.st-watched { background: var(--badge-green-bg); color: var(--badge-green-fg); }
.st-want_to_watch { background: var(--badge-amber-bg); color: var(--badge-amber-fg); }
.st-on_hold { background: var(--badge-gray-bg); color: var(--badge-gray-fg); }
.st-dropped { background: var(--badge-red-bg); color: var(--badge-red-fg); }
.pc-type, .pc-year { color: var(--text-muted); }
.pc-progress { display: flex; align-items: center; gap: 8px; }
.pc-bar { width: 120px; height: 4px; background: var(--bg-secondary); border-radius: 2px; overflow: hidden; }
.pc-fill { height: 100%; background: var(--primary); border-radius: 2px; transition: width .3s; }
.pc-prog-text { font-size: 11px; color: var(--text-muted); }
.pc-actions { display: flex; gap: 6px; align-items: center; flex-shrink: 0; }
/* 宽度从写死的 30px 改成 min-width + 内边距: 按钮上是「+1 集」三个字,
   30px 装不下(文字会溢出或者被压扁). 高度不动 —— 30px 那一档本来就不是
   触控目标, 窄屏那档另有 40px 的规则. */
.pca-btn { min-width: 30px; height: 30px; padding: 0 10px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text-secondary); font-size: 12px; font-weight: 700; cursor: pointer; transition: all var(--transition); font-family: inherit; white-space: nowrap; }
.pca-btn:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.pca-btn:disabled { opacity: .4; cursor: not-allowed; }
.pca-select { padding: 6px 8px; border-radius: 6px; border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text); font-size: 11px; cursor: pointer; font-family: inherit; }

/* ── 通知 ── */
/* 与 .p-list 同宽同边距, 于是它与上面的追番列表左右对齐 —— 两块的左边缘
   如果差几个像素, 看起来像两页拼起来的 */
.p-notices { max-width: 1000px; margin: 32px auto 0; padding: 0 32px; }
/* 通知与账号安全两个区块的标题共用一条. 这不是"顺手复用": 两块的左边缘在
   同一条线上, 字号/间距也必须同一条, 否则两块看起来是两个页面拼的 */
.pf-title { font-size: 15px; font-weight: 700; color: var(--text); margin-bottom: 12px; }
.pn-hint { font-size: 13px; color: var(--text-muted); padding: 12px 0; }
/* 失败那一句要跟"还没有"区分开: 同色同字号的话, 一次网络抖动看起来就像
   "这个站没人理我" */
.pn-hint-err { color: var(--danger); }
.pn-list { display: flex; flex-direction: column; gap: 8px; }
.pn-item {
  display: flex; gap: 12px; padding: 12px 14px; cursor: pointer;
  background: var(--card); border: 1px solid var(--card-border);
  border-radius: var(--radius); transition: all var(--transition);
}
.pn-item:hover { border-color: var(--primary-line); background: var(--card-hover); }
/* 未读 = 左边一道强调色. 用 inset 阴影而不是 border-left: 后者会把这一行的
   内容整体右移 2px, 于是"已读"和"未读"两行的头像不在一条竖线上 */
.pn-unread { box-shadow: inset 3px 0 0 var(--primary); }
.pn-avatar {
  width: 32px; height: 32px; border-radius: 50%; background: var(--primary);
  color: var(--primary-foreground); display: flex; align-items: center;
  justify-content: center; font-weight: 700; font-size: 13px; flex-shrink: 0;
  overflow: hidden;
}
/* 有头像时这一格换成图片; 没有时容器自己显示首字母. overflow: hidden 写在容器上
   (而不是给 img 一个 border-radius) —— 这一格是 32px 的圆, 图片本身不一定是方的 */
.pn-avatar-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.pn-body { flex: 1; min-width: 0; }
.pn-top { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.pn-name { font-weight: 700; font-size: 13px; color: var(--text); }
/* 动词块跟着名字, 与它一起读成一句话("alice 赞了你的评论"); 颜色压一档,
   让名字仍然是这一行的主语 */
.pn-action { font-size: 13px; color: var(--text-secondary); }
.pn-time { font-size: 11px; color: var(--text-muted); margin-left: auto; }
/* 我那条评论的摘要: 用左侧竖线 + 斜体压成"引文", 与下面的回复正文一眼分得开 ——
   两块都是正文的话, 读起来不知道哪句是谁说的 */
.pn-quote {
  font-size: 12px; color: var(--text-muted); padding-left: 8px;
  border-left: 2px solid var(--border); margin-bottom: 4px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pn-text { font-size: 13px; line-height: 1.6; color: var(--text-secondary); word-break: break-word; }

/* ── 账号安全 ── */
/* 与 .p-notices 同宽同边距, 理由同那条 —— 三块的左边缘不在一条线上就像两页拼的 */
.p-security { max-width: 1000px; margin: 32px auto 0; padding: 0 32px; }
/* 表单本身不铺满 1000px: 输入框横跨整行会让人以为要填很长一段内容,
   而这里只有三格短文本 */
.sec-form { display: flex; flex-direction: column; max-width: 360px; }
.sec-label { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; }
/* 只有第一格需要上方间距, 后面每一格的间距由 label 的 margin-top 给,
   这样"标签贴着它自己的输入框"这件事不会因为间距写错而串位 */
.sec-form .sec-label:not(:first-child) { margin-top: 12px; }
.sec-input {
  padding: 9px 12px; border-radius: 8px; font-size: 13px; font-family: inherit;
  border: 1px solid var(--border); background: var(--bg-secondary); color: var(--text);
  transition: border-color var(--transition);
}
.sec-input:focus { outline: none; border-color: var(--primary); }
.sec-error { font-size: 12px; color: var(--danger); margin-top: 10px; }
.sec-hint { font-size: 12px; color: var(--text-muted); margin-top: 10px; }
.sec-submit {
  align-self: flex-start; margin-top: 14px; padding: 9px 20px; border: none;
  border-radius: 8px; font-size: 13px; font-weight: 600; font-family: inherit;
  background: var(--primary); color: var(--primary-foreground); cursor: pointer;
  transition: opacity var(--transition);
}
.sec-submit:hover:not(:disabled) { opacity: .88; }
.sec-submit:disabled { opacity: .5; cursor: not-allowed; }

@media (max-width: 768px) {
  .p-header { padding: 0 16px; gap: 16px; }
  .p-notices { padding: 0 16px; }
  .p-security { padding: 0 16px; }
  .p-avatar { width: 72px; height: 72px; }
  .p-info { padding-top: 36px; }
  .p-name { font-size: 20px; }
  .p-stats { gap: 16px; }
  .ps-num { font-size: 16px; }
  .p-tabs { padding: 0 16px; overflow-x: auto; }
  .p-tab { padding: 8px 10px; font-size: 12px; white-space: nowrap; }
  .p-list { padding: 0 16px; }
  .p-hint { padding: 0 16px; }
  .pc-cover { width: 48px; }

  /* 操作区在窄屏下从「藏起来」改成「单独占一行」.
     改前这里是一句 display:none —— 「+1 集」和「改状态」在手机上直接消失,
     而这两个恰恰是移动端最常用的动作(看完一集顺手点一下), 藏掉功能换来的
     "干净"不划算: 用户只会以为这个站没有这个功能, 或者以为自己没登录.
     卡片改成可换行, 操作区 width:100% 于是被挤到第二行, 用一条分隔线和内容分开. */
  .p-card { flex-wrap: wrap; }
  .pc-actions {
    display: flex; width: 100%; gap: 10px;
    padding-top: 12px; margin-top: 4px; border-top: 1px solid var(--border);
  }
  /* 手指不是鼠标: 两个控件都按 40px 的触控目标放大, 下拉框吃掉剩下的宽度 */
  .pca-btn { min-width: 40px; height: 40px; padding: 0 12px; font-size: 14px; }
  .pca-select { flex: 1; min-height: 40px; padding: 8px 10px; font-size: 13px; }
}
</style>
