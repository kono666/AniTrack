import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getAnimeDetail: vi.fn(),
  getEpisodes: vi.fn(),
  getRatingStats: vi.fn(),
  getSubjectReviews: vi.fn(),
  getMyReview: vi.fn(),
  saveReview: vi.fn(),
  deleteMyReview: vi.fn(),
  getTrackingStatus: vi.fn(),
  saveTracking: vi.fn(),
  deleteTracking: vi.fn(),
  getWatchedEpisodes: vi.fn(),
  toggleEpisode: vi.fn(),
  getAnimeHeat: vi.fn(),
  getFiltered: vi.fn(),
  // 点赞那三个, 加上两个排序常量 —— vi.mock 整体替换模块, 漏一个就在解构时抛,
  // 整页落到错误态(见这个文件顶部那条). 常量也得列: 漏了组件里就是 undefined
  likeReview: vi.fn(),
  unlikeReview: vi.fn(),
  getReviewLikers: vi.fn(),
  // 回复那一组同理, 一个都不能漏(整体替换模块)
  getReplies: vi.fn(),
  addReply: vi.fn(),
  editReply: vi.fn(),
  deleteReply: vi.fn(),
  likeReply: vi.fn(),
  unlikeReply: vi.fn(),
  getReplyLikers: vi.fn(),
  // 举报同理(值 + 那四个理由的常量), 理由也要列全 —— 漏了就是面板空白
  reportReview: vi.fn(),
  REVIEW_REPORT_REASONS: [
    { value: 'SPAM', label: '垃圾广告' },
    { value: 'ABUSE', label: '辱骂攻击' },
    { value: 'SPOILER', label: '剧透' },
    { value: 'OTHER', label: '其他' },
  ],
  REVIEW_SORT_CREATED: 'createdAt',
  REVIEW_SORT_HOT: 'hot',
}))

import AnimeDetail from '../AnimeDetail.vue'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews, getFiltered,
  getWatchedEpisodes, getAnimeHeat, getTrackingStatus, getMyReview,
} from '../../api'

/**
 * 详情页把「缺数据」明晃晃摆出来.
 *
 * 改前那排 评分/排名/总集数/年份/类型 是**恒渲染**的, 缺值就显示 "#-" / "?" ——
 * 于是站内 2.9 万部里最常见的那种页面(没名次、没总集数、没人追)看起来像
 * 「这站坏了」, 而不是「这站还年轻」. 热度那一行更直白: 三项全零也照显示
 * 「0人想看 0人在看 0人看过」.
 *
 * 还有一处不在建议里、但性质更差的: 类型那一格写的是 `platform || 'TV'` ——
 * 缺值时它**猜一个 TV**. 一部剧场版没有 platform 就会被告知「类型 TV」.
 * 缺数据是"还不知道", 猜出来的数据是"说错了", 后者更糟.
 *
 * 单独一个文件: 与 Home.today.test.js 同一个理由, 免得这里造的各种残缺 subject
 * 影响别的用例.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/anime/:id', component: { template: '<div />' } }],
})

/** 字段齐全的一部 */
const FULL = {
  id: 7, nameCn: '齐活番', totalEpisodes: 12, date: '2024-04-06', platform: 'TV',
  rating: { score: 8.8, rank: 8, total: 40635 },
}

async function mountWith(subject, heat) {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  getAnimeDetail.mockResolvedValue({ data: { code: 200, data: subject } })
  getEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getRatingStats.mockResolvedValue({ data: { code: 200, data: { average: 0, count: 0, distribution: Array(10).fill(0) } } })
  getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
  getFiltered.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
  // 登录态的 load() 里还有一串串行请求, 任何一环返回 undefined 都会在解构 .data
  // 时抛, 被最外层 catch 接住 → 热度那一行永远没机会被赋值, 测试变成假绿
  getTrackingStatus.mockResolvedValue({ data: { code: 200, data: { tracked: false } } })
  getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
  getWatchedEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getAnimeHeat.mockResolvedValue({ data: { code: 200, data: heat || null } })

  await router.push('/anime/7')
  await router.isReady()
  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 那一排里的标签文字 */
const labels = w => w.findAll('.d-stat .ds-lbl').map(n => n.text())

describe('详情页的缺数据', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('字段齐全时五格都在(对照)', async () => {
    const w = await mountWith(FULL, null)
    // 「评分」→「番组评分」(c117): 这一页有四个分, 其中两个原先都叫「评分」。
    // 改这个名字时这条断言如期变红 —— 那是好事, 它就是为"标签被改动"准备的哨兵。
    expect(labels(w)).toEqual(['番组评分', '排名', '总集数', '年份', '类型'])
    expect(w.text()).toContain('#8')
  })

  it('没有名次、没有总集数时, 那两格不出现, 而不是显示 #- 和 ?', async () => {
    const w = await mountWith({ id: 7, nameCn: '冷门番', date: '2011-04-06', platform: 'TV' }, null)

    expect(labels(w)).toEqual(['年份', '类型'])
    expect(w.text()).not.toContain('#-')
    expect(w.text()).not.toContain('?')
  })

  it('没有 platform 时不显示「类型 TV」—— 缺值不许猜', async () => {
    // 一部剧场版没有 platform, 改前会被标成「类型 TV」
    const w = await mountWith({ id: 7, nameCn: '某剧场版', date: '2011-04-06' }, null)

    expect(labels(w)).not.toContain('类型')
    expect(w.text()).not.toContain('TV')
  })

  it('五格全空时整排不渲染(不留一个带 18px 下边距的空容器)', async () => {
    const w = await mountWith({ id: 7, nameCn: '毛坯番' }, null)

    expect(w.find('.d-stats').exists()).toBe(false)
    expect(labels(w)).toEqual([])
  })

  it('热度三项全零时整行不显示', async () => {
    const w = await mountWith(FULL, { wantToWatch: 0, watching: 0, watched: 0 })

    expect(w.find('.d-heat').exists()).toBe(false)
    expect(w.text()).not.toContain('人想看')
  })

  it('热度只要有一项非零就显示, 而且要显示全三项', async () => {
    const w = await mountWith(FULL, { wantToWatch: 0, watching: 2, watched: 0 })

    expect(w.find('.d-heat').exists()).toBe(true)
    expect(w.text()).toContain('2人在看')
    expect(w.text()).toContain('0人想看')
  })

  it('拿不到热度数据时不显示', async () => {
    const w = await mountWith(FULL, null)
    expect(w.find('.d-heat').exists()).toBe(false)
  })
})
