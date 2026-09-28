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

// ========== Bangumi 番剧 ==========
export const searchAnime = (keyword, page = 1) =>
  api.get('/bangumi/search', { params: { keyword, page, limit: 20 } })
export const getAnimeDetail = (subjectId) =>
  api.get(`/bangumi/subject/${subjectId}`)
export const getEpisodes = (subjectId) =>
  api.get(`/bangumi/subject/${subjectId}/episodes`)
export const getCalendar = () => api.get('/bangumi/calendar')
export const getRanking = (sort = 'rank', limit = 20) =>
  api.get('/bangumi/ranking', { params: { sort, limit } })
export const getTags = () => api.get('/bangumi/tags')
export const getByTag = (tag) => api.get('/bangumi/by-tag', { params: { tag } })

// ========== 追番管理 ==========
export const saveTracking = (data) => api.post('/track', data)
export const deleteTracking = (subjectId) =>
  api.delete('/track', { params: { subjectId } })
export const getTrackingList = () => api.get('/track/list')
export const getTrackingStatus = (subjectId) =>
  api.get('/track/status', { params: { subjectId } })
export const getUserStats = () => api.get('/track/stats')

// ========== 评论 ==========
export const saveReview = (data) => api.post('/review', data)
export const deleteMyReview = (reviewId) => api.delete(`/review/${reviewId}`)
export const getSubjectReviews = (subjectId, userId = 0) =>
  api.get('/review/list', { params: { subjectId, userId } })
export const getRatingStats = (subjectId) =>
  api.get('/review/stats', { params: { subjectId } })
export const getMyReview = (subjectId) =>
  api.get('/review/my', { params: { subjectId } })

// ========== 管理员 ==========
export const getDashboard = () => api.get('/admin/dashboard')
export const getAdminUsers = () => api.get('/admin/users')
export const toggleUserStatus = (targetUserId) =>
  api.put(`/admin/users/${targetUserId}/toggle`)
export const unlockUser = (targetUserId) =>
  api.put(`/admin/users/${targetUserId}/unlock`)
export const setUserRole = (targetUserId, role) =>
  api.put(`/admin/users/${targetUserId}/role`, null, { params: { role } })
export const getAdminReviews = () => api.get('/admin/reviews')
export const adminDeleteReview = (reviewId) =>
  api.delete(`/admin/reviews/${reviewId}`)

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
