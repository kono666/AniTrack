import axios from 'axios'
import router from '../router'
import { useUserStore } from '../stores/user'
import { loadStoredUser, clearStoredUser } from '../utils/userStorage'
import { showToast } from '../composables/useToast'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

/**
 * 「已经因为 401 在跳登录页了」的标志位.
 *
 * 为什么需要: 一个页面同时发几个请求时(首页的 Promise.all、管理端的多个面板),
 * 它们几乎会在同一瞬间各自拿到 401. 不去重的话会连着清好几次登录态、连着推
 * 好几次 /login —— 第二次起是重复导航, 控制台一片警告, 而用户看到的地址栏
 * 还可能被后到的那个请求覆盖掉.
 *
 * 复位放在请求拦截器里(见下), 不用「设上就再不复位的布尔值」: 那种写法会让
 * 同一个标签页里 token **第二次**过期时永远不再跳登录页 —— 这比不去重更糟.
 */
let redirectingToLogin = false

/** 跳登录页, 并把「登录后回哪去」带上(与路由守卫的写法一致). */
function redirectToLogin() {
  const current = router.currentRoute.value
  // 已经在登录/注册页: 不必再跳, 也别把用户正在填的表单顶掉
  if (current.path === '/login' || current.path === '/register') return

  redirectingToLogin = true
  // 首页不用带 redirect —— 那本来也就是登录后的默认落点, 带上只是让地址变长
  router.push(current.path === '/'
    ? { path: '/login' }
    : { path: '/login', query: { redirect: current.fullPath } })
}

// 请求拦截器：自动携带 JWT Token
api.interceptors.request.use(config => {
  // 拦截器在每一次请求前都会跑, 这里抛异常等于所有请求都发不出去 ——
  // 所以同样交给 loadStoredUser() 兜住脏数据
  const user = loadStoredUser()
  if (user?.token) {
    config.headers.Authorization = `Bearer ${user.token}`
    // 带着 token 发请求 = 又处在登录态了, 标志位回到初始值.
    // 这样上面那个标志的生命周期就是「一次登录态」而不是「一次页面加载」.
    redirectingToLogin = false
  }
  return config
})

// 响应拦截器：401 清登录态并跳登录页, 403 提示没有权限
api.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      // 并发的 401 只处理第一个
      if (!redirectingToLogin) {
        // 清除 localStorage 和 Pinia store
        clearStoredUser()
        useUserStore().logout()
        // 用 router 实例而不是 window.location.pathname:
        // pathname 是浏览器的地址栏, 而这里要判断的是**应用正在渲染哪一页** ——
        // 两者在跳转过程中的一瞬间并不一致, 拿地址栏做判断会出现
        // 「刚跳到 /login, 但地址栏还没更新, 于是又跳一次」这种重复导航.
        redirectToLogin()
      }
    } else if (error.response?.status === 403) {
      // 403 = 登录着, 但这件事你没权限做(最典型: 普通用户直接敲 /admin,
      // 或者管理端接口被越权调用). 改前这里什么都不做, 只在各个调用方自己的
      // catch 里 console.error —— 于是管理端页面剩下一片空白, 用户看到的是
      // 「这页坏了」, 而不是「你没有权限」, 也不知道该回哪去.
      showToast('没有权限', 'error')
      const current = router.currentRoute.value
      if (current.path !== '/') router.push('/')
    }
    return Promise.reject(error)
  }
)

// 导出这个配置好的实例: 拦截器是「接线」, 只把里面那两个函数抠出来单测等于
// 没测接线. 有了它, 测试可以换掉实例的 adapter, 让真实的 axios 管线跑一遍.
export default api

// ========== 用户 ==========
export const register = (data) => api.post('/user/register', data)
export const login = (data) => api.post('/user/login', data)
export const getUserInfo = (userId) => api.get(`/user/info/${userId}`)
export const getCurrentUser = () => api.get('/user/me')

/**
 * 改自己的密码.
 *
 * <p><b>返回值里的 {@code data.token} 是新的, 必须存回去。</b> 改密会让改密之前签发的
 * token 全部作废, 而手上这张正在其中 —— 不存新的, 下一个请求就是 401, 用户看到的是
 * 「我刚改完密码就被登出了」。这不是可选的收尾动作, 是这个接口契约的一半。
 */
export const changePassword = (data) => api.put('/user/password', data)

/**
 * 上传/更换头像.
 *
 * <p><b>不设 Content-Type</b>: 传 FormData 时必须让**浏览器**自己写这个头, 因为
 * multipart 的 boundary 是它生成的. 手写 {@code 'multipart/form-data'} 会得到一个
 * 没有 boundary 的头, 服务端解析不出任何部分 —— 而报出来的错是"没有收到 file",
 * 与真正的原因差得很远. (axios 1.x 传 FormData 时会主动删掉这个头, 这里不设是为了
 * 让这条规矩在代码里看得见.)
 *
 * <p><b>超时单独放宽到 30 秒。</b> 实例默认是 10 秒, 那是按小 JSON 定的; 一张 512KB 的图
 * 在弱网下(200KB/s)就要 2.5 秒, 上传方向的带宽往往还更窄. 用默认值的话, 用户看到的
 * 是"传到一半就失败", 而重试一次往往又成功了 —— 最难查的那类问题.
 *
 * <p>响应里的 {@code data.avatar} 是新的地址(带 {@code ?v=} 版本号), 调用方要把它写回
 * store, 否则页面上的头像要等下一次 {@code /api/user/me} 才会变.
 */
export const uploadAvatar = (file) => {
  const form = new FormData()
  form.append('file', file)
  return api.post('/user/avatar', form, { timeout: 30000 })
}

/** 删掉头像, 退回首字母/图标兜底. 幂等 —— 本来就没有头像时调它也不会报错. */
export const deleteAvatar = () => api.delete('/user/avatar')

// ========== Bangumi 番剧 ==========
/** 搜索每页条数. 后端 limit 的上限是 50(BangumiController 的 @Max), 20 是本项目的口径;
 *  导出它是为了让 Search.vue 算「共几页」时用的是同一个数, 而不是自己再抄一份 20. */
export const SEARCH_PAGE_SIZE = 20
export const searchAnime = (keyword, page = 1) =>
  api.get('/bangumi/search', { params: { keyword, page, limit: SEARCH_PAGE_SIZE } })
export const getAnimeDetail = (subjectId) =>
  api.get(`/bangumi/subject/${subjectId}`)
export const getEpisodes = (subjectId) =>
  api.get(`/bangumi/subject/${subjectId}/episodes`)
export const getCalendar = () => api.get('/bangumi/calendar')
export const getRanking = (sort = 'rank', limit = 20) =>
  api.get('/bangumi/ranking', { params: { sort, limit } })
export const getTags = () => api.get('/bangumi/tags')

// ========== 追番管理 ==========
export const saveTracking = (data) => api.post('/track', data)
export const deleteTracking = (subjectId) =>
  api.delete('/track', { params: { subjectId } })
export const getTrackingList = () => api.get('/track/list')
export const getTrackingStatus = (subjectId) =>
  api.get('/track/status', { params: { subjectId } })
export const getUserStats = () => api.get('/track/stats')

// ========== 评论 ==========
/**
 * 评论列表的排序方式. 与后端 `ReviewService.SORT_CREATED / SORT_HOT` 同源.
 *
 * 导出成常量而不是在视图里写字符串: 这两个值是**接口参数**, 拼错了不会报错 ——
 * 后端对未知的 sort 值退化成默认序(理由见 ReviewService.getSubjectReviews),
 * 于是「最热」按钮点了没反应, 页面上看不出任何异常.
 */
export const REVIEW_SORT_CREATED = 'createdAt'
export const REVIEW_SORT_HOT = 'hot'

export const saveReview = (data) => api.post('/review', data)
export const deleteMyReview = (reviewId) => api.delete(`/review/${reviewId}`)
export const getSubjectReviews = (subjectId, userId = 0, sort) =>
  api.get('/review/list', { params: { subjectId, userId, sort } })
export const getRatingStats = (subjectId) =>
  api.get('/review/stats', { params: { subjectId } })
export const getMyReview = (subjectId) =>
  api.get('/review/my', { params: { subjectId } })

/**
 * 点赞 / 取消点赞.
 *
 * 是**两个**端点而不是一个 toggle: 客户端表达的是目标状态("我要它处于已赞状态"),
 * 于是重发安全 —— 一次点击因为超时被浏览器重发两次, 结果与发一次相同. Toggle 在这
 * 种情况下会把用户的赞翻回去, 而两次都返回 200(理由见 ReviewController.likeReview).
 *
 * 两个都不带 body, 因此都不经过 JSON 序列化这条路径.
 */
export const likeReview = (reviewId) => api.post(`/review/${reviewId}/like`)
export const unlikeReview = (reviewId) => api.delete(`/review/${reviewId}/like`)
/** 谁赞了这条评论. 公开接口, 未登录也能看(与评论列表本身一样) */
export const getReviewLikers = (reviewId) => api.get(`/review/${reviewId}/likes`)

// ========== 举报 ==========
//
// 四个理由的**取值**与后端 ReviewReport.REASONS 逐个对齐, 标签只活在前端 ——
// 后端存的是英文常量, 界面上的中文不占库也不进接口, 于是改文案不用配一次数据迁移.
//
// 顺序与后端那个 LinkedHashSet 一致: 单选按钮照这个顺序渲染, 两边各写一份顺序的话,
// 每次改动后"哪个排第一"会悄悄分家.

/** 举报理由, 值为接口参数. 界面上渲染的是 label */
export const REVIEW_REPORT_REASONS = [
  { value: 'SPAM', label: '垃圾广告' },
  { value: 'ABUSE', label: '辱骂攻击' },
  { value: 'SPOILER', label: '剧透' },
  { value: 'OTHER', label: '其他' },
]

/**
 * 举报一条评论. **必须登录**(这条路刻意不在后端的免登录清单里, 匿名会拿到 401).
 *
 * 幂等: 同一个人对同一条评论重复举报回 200 且 `duplicate=true`, 不是 409. 前端因此
 * 不需要为"已经举报过"写一条错误分支 —— 它是一条正常响应, 只是提示语不一样.
 *
 * @param payload {reason, detail} —— detail 选填, 空白由后端存成 null
 */
export const reportReview = (reviewId, payload) =>
  api.post(`/review/${reviewId}/report`, payload)

/**
 * 某条评论的举报明细(管理端). 回 `{list, total}`, 评论不存在时 404.
 *
 * 明细与列表**分开一条接口**: 一页 20 行全带明细就是 20 倍的响应体, 而管理员一次
 * 只可能展开一行 —— 所以它是「点了展开才拉」.
 */
export const getReviewReports = (reviewId) =>
  api.get(`/admin/reviews/${reviewId}/reports`)
/** 忽略一条举报. PUT 而不是 POST: 它把状态改回一个确定值, 重复调用结果相同 */
export const dismissReport = (reportId) =>
  api.put(`/admin/reports/${reportId}/dismiss`)

// ========== 回复 ==========
//
// 路径分成两组前缀, 与后端一一对应: 「某条评论下的回复」用 /review/{id}/replies,
// 「针对某一条回复」用 /reply/{id} —— 回复的 id 已经唯一确定了它, 再带一个评论 id
// 只是多一个必须与库对上的参数(理由见 ReviewReplyController 的类注释).

/** 某条评论下的回复. 公开接口, 未登录也能看 */
export const getReplies = (reviewId) => api.get(`/review/${reviewId}/replies`)
export const addReply = (reviewId, content) =>
  api.post(`/review/${reviewId}/replies`, { content })
/**
 * 改自己的回复. 只有作者能改 —— 评论作者**不能**改别人的回复(与删除权限刻意不同:
 * 删是"我的地盘我做主", 改是替别人说话).
 */
export const editReply = (replyId, content) =>
  api.put(`/reply/${replyId}`, { content })
export const deleteReply = (replyId) => api.delete(`/reply/${replyId}`)

/** 回复的赞, 与评论的赞是同一套: 两个端点、各自幂等(理由见上面 likeReview) */
export const likeReply = (replyId) => api.post(`/reply/${replyId}/like`)
export const unlikeReply = (replyId) => api.delete(`/reply/${replyId}/like`)
/** 谁赞了这条回复. 公开接口 */
export const getReplyLikers = (replyId) => api.get(`/reply/${replyId}/likes`)

// ========== 通知 ==========
//
// 三个端点取代了原先的 getReceivedReplies(那个只有"谁回复了我"一种, 没有 total、
// 没有分页、没有已读)。它已随 V11 一起删掉, 没有留兼容——个人页那一块现在读通知。

/**
 * 我的通知: 有人回复了我的评论 / 赞了我的评论 / 赞了我的回复. 需要登录.
 *
 * 返回 {list, total, page} 的分页信封(与评论列表同一个形状), 所以这一块有翻页控件 ——
 * 而旧端点回的是裸 {list} 且封顶 30 条.
 */
export const getNotifications = (params) => api.get('/user/notifications', { params })

/**
 * 未读条数, 只回 {count}. 导航栏的红点用.
 *
 * 单独一个端点而不是让前端去数列表里的未读: 红点每个页面都要显示, 而列表只在个人页拉.
 */
export const getUnreadCount = () => api.get('/user/notifications/unread-count')

/**
 * 把未读全部标为已读. 幂等 —— 重复调用改 0 行, 第一次那个已读时间不会被覆盖.
 *
 * 用 PUT 不用 POST: 它把状态设成一个确定值, 与 unlockUser 是同一条理由。
 */
export const markNotificationsRead = () => api.put('/user/notifications/read')

// ========== 管理员 ==========
export const getDashboard = () => api.get('/admin/dashboard')

/**
 * 后台表格可选的每页条数. 后端 limit 的上限是 100(AdminController 的 @Max), 20 是默认.
 *
 * 导出它是为了让算「共几页」的地方用的是**同一个数** —— 请求里发 limit=50 而分页控件
 * 按 20 算, 两边对 totalPages 各说各话, 用户点到"最后一页"会发现是空的.
 * 与 SEARCH_PAGE_SIZE 是同一条理由.
 *
 * 名字里没有 "USER": 用户管理与操作日志两页共用同一份取值. 抄第二份的代价与
 * PageResults/TextSnippet 被抽出来是同一条 —— 两处一旦漂掉, 没有任何测试会抓得到.
 */
export const ADMIN_PAGE_SIZES = [20, 50, 100]
export const ADMIN_PAGE_SIZE = ADMIN_PAGE_SIZES[0]

/**
 * 用户列表.
 *
 * 参数全部可选; 值为 undefined 的键会被 axios 从 query 里丢掉(不是发成空串) ——
 * 这正是我们要的: 后端对 `keyword=` 与"没有这个参数"的处理虽然等价, 但地址栏和
 * 服务端日志里少一堆空参数总是更好读.
 */
export const getAdminUsers = (params) => api.get('/admin/users', { params })
export const toggleUserStatus = (targetUserId) =>
  api.put(`/admin/users/${targetUserId}/toggle`)
export const unlockUser = (targetUserId) =>
  api.put(`/admin/users/${targetUserId}/unlock`)
export const setUserRole = (targetUserId, role) =>
  api.put(`/admin/users/${targetUserId}/role`, null, { params: { role } })
/**
 * 重置某个用户的密码. **没有 oldPassword** —— 管理员是在用户拿不出旧密码时替他换一把。
 * 后端会记一条 USER_PASSWORD_RESET 的账(理由见 AdminService.resetPassword)。
 */
export const resetUserPassword = (targetUserId, newPassword) =>
  api.put(`/admin/users/${targetUserId}/password`, { newPassword })

/**
 * 管理端的操作日志(账本).
 *
 * 只有一个 action 精确筛, **没有排序参数** —— 账本只有"最新的在最上面"这一种读法,
 * 与后端 getActionPage 一一对应。action 认不出来时后端当"不筛", 所以这里不需要
 * 在前端做白名单校验。
 */
export const getAdminActions = (params) => api.get('/admin/actions', { params })
/**
 * 评论列表. 参数全部可选, 值为 undefined 的键会被 axios 丢掉(同 getAdminUsers).
 *
 * 改前这个函数**一个参数都没有**, 后端也是 —— 一次拿回整张评论表.
 */
export const getAdminReviews = (params) => api.get('/admin/reviews', { params })
/**
 * 移除一条评论. V14 起后端是**软删** —— 评论从用户侧消失, 行还在, 可以被下面那一条恢复.
 * 所以这里的前端语义也变了: 同一个列表行上, 移除与恢复是两个按钮的两次点击, 不是一次.
 */
export const adminDeleteReview = (reviewId) =>
  api.delete(`/admin/reviews/${reviewId}`)
/** 撤销上面那一次移除. 与删除对称: 同一个 URL 上的另一个动词 */
export const adminRestoreReview = (reviewId) =>
  api.put(`/admin/reviews/${reviewId}/restore`)

// ========== 筛选 ==========
export const getFilterMeta = () => api.get('/bangumi/filter-meta')
export const getFiltered = (params) => api.get('/bangumi/filter', { params })

// ========== 统计 ==========
export const getGenreDistribution = () => api.get('/stats/genre-distribution')
export const getScoreDistribution = () => api.get('/stats/score-distribution')
export const getOverallStats = () => api.get('/stats/overall')
export const getRecentActivity = () => api.get('/stats/recent-activity')

// ========== 单集勾选 ==========
export const getWatchedEpisodes = (animeId) =>
  api.get('/stats/watched-episodes', { params: { animeId } })
export const toggleEpisode = (animeId, episodeNum) =>
  api.post('/stats/toggle-episode', null, { params: { animeId, episodeNum } })
export const getAnimeHeat = (animeId) =>
  api.get('/stats/anime-heat', { params: { animeId } })

// ========== AI 助手 ==========
// 流式对话不在这里 —— 它必须用 fetch 手动解析 SSE, 见 api/agentStream.js
export const getAgentInfo = () => api.get('/agent/info')
export const agentChat = (data) => api.post('/agent/chat', data)
export const getConversations = () => api.get('/agent/conversations')
export const getConversation = (id) => api.get(`/agent/conversations/${id}`)
export const deleteConversation = (id) => api.delete(`/agent/conversations/${id}`)
