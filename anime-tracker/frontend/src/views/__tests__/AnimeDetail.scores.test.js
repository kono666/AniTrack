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
  likeReview: vi.fn(),
  unlikeReview: vi.fn(),
  getReviewLikers: vi.fn(),
  getReplies: vi.fn(),
  addReply: vi.fn(),
  editReply: vi.fn(),
  deleteReply: vi.fn(),
  likeReply: vi.fn(),
  unlikeReply: vi.fn(),
  getReplyLikers: vi.fn(),
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
 * 详情页上那**四个「分」**各自叫什么名字。
 *
 * 站内的 UI 上从来没出现过「Bangumi」这个词(它只活在代码注释里), 于是「评分」这两个字
 * 被用在了两个不同的东西上, 而它们是**可以差出好几分**的:
 *
 *   1. hero 大数字排  —— 上游的聚合分(root 才有, 全站用户打了十几年的那个);
 *   2. 追番栏那个输入框 —— 我给这部番打的分;
 *   3. 评论区标题栏    —— 本站用户在座各位打的分;
 *   4. 我的评论表单里那排可点的 ★ —— 我写这条评论时打的分(上下文已足, 不动)。
 *
 * 改前 1 和 2 的标签都叫「评分」, 而 hero 封面角上还把这个**同一个上游分**又印了一遍
 * (★9.1, 无标签), 与下面那格相距不到 200px。三个名字现在互不重合, 封面那个重复的直接删掉。
 *
 * 单独一个文件: 与 AnimeDetail.missing.test.js 同一个理由 —— 这里造的各种评分形状
 * (尤其是 count=0) 不该影响别的用例。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/anime/:id', component: { template: '<div />' } }],
})

/** 上游分与本站分刻意给成不同的数 —— 相同的话"改错了名字"也看不出来 */
const SUBJECT = {
  id: 7, nameCn: '某个番', totalEpisodes: 12, date: '2024-04-06', platform: 'TV',
  rating: { score: 9.1, rank: 42, total: 40635 },
}

async function mountWith({ stats, tracking } = {}) {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  getAnimeDetail.mockResolvedValue({ data: { code: 200, data: SUBJECT } })
  getEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getRatingStats.mockResolvedValue({
    data: { code: 200, data: stats || { average: 8.6, count: 3, distribution: Array(10).fill(0) } },
  })
  getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
  getFiltered.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
  getTrackingStatus.mockResolvedValue({ data: { code: 200, data: tracking || { tracked: false } } })
  getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
  getWatchedEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getAnimeHeat.mockResolvedValue({ data: { code: 200, data: null } })

  await router.push('/anime/7')
  await router.isReady()
  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 评论区那一段 —— 页面上的 .d-section 有好几段, 必须先按标题挑出这一段再往里找 */
const reviewSection = w => w.findAll('.d-section').find(s => s.find('.sec-title').text().startsWith('评论'))

/** 评论区标题栏右侧那一块(#extra 落在 .sec-extra 里) */
const reviewExtra = w => reviewSection(w).find('.sec-extra').text()

/** 追番栏里那两个 label 的文字 */
const trackLabels = w => w.findAll('.track-input-row label').map(n => n.text())

describe('详情页四个「分」的名字', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('hero 那个上游分带标签「番组评分」, 不再只是「评分」', async () => {
    const w = await mountWith()

    const stat = w.findAll('.d-stat').find(s => s.find('.ds-lbl').text().includes('评分'))
    expect(stat.find('.ds-lbl').text()).toBe('番组评分')
    expect(stat.find('.ds-val').text()).toBe('9.1')
  })

  it('封面角上不再重复印一遍同一个数', async () => {
    const w = await mountWith()

    // 那条角标(★9.1)与 .d-stats 里的 9.1 是同一个数, 相距不到 200px。这是"同一页
    // 重复渲染"的哨兵: 只要有人把它加回来, hero 里就会出现第二个 9.1。
    const hero = w.find('.d-hero').text()
    expect(hero.split('9.1').length - 1).toBe(1)

    // 排名角标只在这一处出现, 不是重复, 保留
    expect(w.find('.d-cover-rank').exists()).toBe(true)
    expect(w.find('.d-cover-score').exists()).toBe(false)
  })

  it('追番栏那个输入框叫「追番评分」, 与 hero 那个区分开', async () => {
    // 追番栏只在**已追番**时渲染, 所以这一条的桩必须给 tracked: true ——
    // 没追番时这一排根本不在, trackLabels 会回一个空数组, 断言"标签叫什么"
    // 就变成了对空数组的断言(假绿)
    const w = await mountWith({ tracking: { tracked: true, id: 5, status: 'watching', progress: 3, score: 7 } })

    expect(trackLabels(w)).toEqual(['直接改进度', '追番评分'])
    expect(w.find('.track-input-row').exists()).toBe(true)
  })

  it('有人评分时评论区说「本站均分 ★8.6」', async () => {
    const w = await mountWith({ stats: { average: 8.6, count: 3, distribution: Array(10).fill(0) } })

    expect(reviewExtra(w)).toContain('本站均分')
    expect(reviewExtra(w)).toContain('8.6')
    expect(reviewExtra(w)).not.toContain('暂无评分')
  })

  it('零人评分时说「暂无评分」, 而不是「均分 ★0」', async () => {
    // 后端 `ReviewService` 里那句 `count == 0 ? 0.0 : (double) sum / count` —— 没有人
    // 打过分时 average **就是 0.0**, 不是缺字段。改前这里没有 v-if, 于是渲染成
    // 「均分 ★0」, 读起来是"这部番得了 0 分"。
    const w = await mountWith({ stats: { average: 0, count: 0, distribution: Array(10).fill(0) } })

    expect(reviewExtra(w)).toContain('暂无评分')
    expect(reviewExtra(w)).not.toContain('本站均分')
    // 这是这条用例真正的断言: ★0 那两个字面量一个都不许出现
    expect(reviewExtra(w)).not.toContain('0')
  })

  it('评论数照常显示, 零人时是「评论 · 0」', async () => {
    const zero = await mountWith({ stats: { average: 0, count: 0, distribution: Array(10).fill(0) } })
    expect(reviewSection(zero).find('.sec-title').text()).toBe('评论 · 0')

    const three = await mountWith({ stats: { average: 8.6, count: 3, distribution: Array(10).fill(0) } })
    expect(reviewSection(three).find('.sec-title').text()).toBe('评论 · 3')
  })

  it('写评论那排 ★ 不动 —— 它就在提交按钮上方, 上下文已经够了', async () => {
    const w = await mountWith()

    expect(w.find('.mr-stars').exists()).toBe(true)
    expect(w.findAll('.mr-star').length).toBe(10)
  })
})
