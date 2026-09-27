import axios from 'axios'
import { useUserStore } from '../stores/user'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 请求拦截器：自动携带 JWT Token
api.interceptors.request.use(config => {
  const user = JSON.parse(localStorage.getItem('anime_user') || 'null')
  if (user?.token) {
    config.headers.Authorization = `Bearer ${user.token}`
  }
  return config
})

// 响应拦截器：401 时清除登录状态并跳转
api.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      // 清除 localStorage 和 Pinia store (需要使用动态import避免循环依赖)
      localStorage.removeItem('anime_user')
      import('../stores/user.js').then(m => m.useUserStore().logout())
      // 使用 router 跳转而非 window.location (保留 SPA 状态)
      if (!window.location.pathname.includes('/login') &&
          !window.location.pathname.includes('/register')) {
        import('../router/index.js').then(m => m.default.push('/login'))
      }
    }
    return Promise.reject(error)
  }
)

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
