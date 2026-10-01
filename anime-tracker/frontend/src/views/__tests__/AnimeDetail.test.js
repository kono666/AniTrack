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
  // 相关推荐改走 /filter(sort=rating), 不再用 /by-tag —— 理由见 AnimeDetail.vue
  getFiltered: vi.fn(),
  // 点赞那三个, 加上两个排序常量. 常量也要列 —— vi.mock 是**整体替换**模块,
  // 漏了它们组件里 REVIEW_SORT_CREATED 就是 undefined, 排序开关两个按钮会同时
  // 命中 active
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
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, getTrackingStatus, saveTracking,
} from '../../api'

/**
 * 进度输入框的封顶.
 *
 * 改前那个 input 连 min 都没有, 值也原样提交. 手打 999 的后端会照收
 * (后端只校验了 >= 0), 于是进度变成 999/12: 进度条按百分比卡在 100%
 * 所以看不出异常, 数字却永远停在那儿, 而它会被"已看 N 集"这类统计一路带下去.
 *
 * 负数那一侧由后端的 @Min(0) 兜着, 但用户拿到的只是一句"保存失败",
 * 输入框里那个 -5 还在 —— 前端也要给出结论, 而且要说清楚它被改成了什么.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/anime/:id', component: { template: '<div />' } }],
})

const SUBJECT = { id: 7, nameCn: '某番', totalEpisodes: 12, images: { large: 'x.jpg' } }

async function mountDetail() {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  await router.push('/anime/7')
  await router.isReady()

  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 已经是追番状态, 所以进度输入框会渲染出来 */
function trackingPayload(id) {
  return { data: { code: 200, data: { id, tracked: true, status: 'watching', progress: 3, score: 8 } } }
}

/**
 * 这一页会调的接口给一套默认值.
 *
 * load() 里是一个 Promise.all: 任何一个返回 undefined 都会在解构 .data 时抛,
 * 整页落到错误态 —— 那样后面的断言全都会变成"找不到元素".
 */
function stubApis() {
  getAnimeDetail.mockResolvedValue({ data: { code: 200, data: SUBJECT } })
  getEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
  getRatingStats.mockResolvedValue({
    data: { code: 200, data: { average: 0, count: 0, distribution: Array(10).fill(0) } },
  })
  getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
  getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
  getTrackingStatus.mockResolvedValue(trackingPayload(11))
}

/** 那一排状态按钮里的某一个(顺序与 statusOptions 一致) */
function statusButton(wrapper, label) {
  return wrapper.findAll('.track-status-btn').find(b => b.text() === label)
}

describe('追番进度的封顶', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  it('输入超过总集数时按总集数提交', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue(999)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 12 }))
  })

  it('夹过之后输入框里的数字也跟着改回来', async () => {
    // 否则界面上还显示 999, 库里存的是 12 —— 用户下次看到这个框会以为没保存成功
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    const input = wrapper.find('.track-input-row input[type="number"]')
    await input.setValue(999)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(input.element.value).toBe('12')
  })

  it('负数按 0 提交, 而不是原样发给后端挨一句"保存失败"', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue(-5)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 0 }))
  })

  it('清空输入框时按 0 提交(v-model.number 给的是空串)', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue('')
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 0 }))
  })

  it('合法范围内的数字原样提交(封顶不该把正常输入也改掉)', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue(7)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 7 }))
  })

  it('总集数未知时不封顶(那种情况下任何上限都是编的)', async () => {
    getAnimeDetail.mockResolvedValue({ data: { code: 200, data: { id: 7, nameCn: '某番' } } })
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue(999)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 999 }))
  })
})

/**
 * 只提交动过的字段, 以及状态按钮的提交时机.
 *
 * 改前这一条 bar 上是三套规矩: 剧集格子立刻落库、状态按钮只改本地要再按「保存」、
 * 而「保存」把 status + progress + score **整行**发回去. 于是任何一处副本旧了,
 * 没碰过的字段就被写回旧值 —— 而界面上每一步都显示成功.
 *
 * 断言写精确对象而不是 objectContaining: 多带一个字段正是这个 bug 本身.
 */
describe('详情页只提交动过的字段', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    stubApis()
  })

  it('点状态按钮立刻落库, 而且只发 status', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await statusButton(wrapper, '看过').trigger('click')
    await flushPromises()

    // 改前这一步一个字都不发, 得再按一次「保存」才作数 —— 而按钮点完就高亮了
    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 7, status: 'watched' })
  })

  it('状态落库失败时高亮退回原来那个, 不留一个"看着已生效"的按钮', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockRejectedValue(new Error('boom'))

    await statusButton(wrapper, '看过').trigger('click')
    await flushPromises()

    expect(statusButton(wrapper, '看过').classes()).not.toContain('active')
    expect(statusButton(wrapper, '在看').classes()).toContain('active')
  })

  it('保存只发动过的进度, 不带 status / score', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    await wrapper.find('.track-input-row input[type="number"]').setValue(7)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 7, progress: 7 })
  })

  it('只动了评分时, 进度一个字都不发', async () => {
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11 } } })

    const score = wrapper.findAll('.track-input-row input[type="number"]')[1]
    await score.setValue(6)
    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 7, score: 6 })
  })

  it('什么都没改就点保存: 不发请求(服务端那边三个字段全可缺席, 空请求只会凭空建一条)', async () => {
    const wrapper = await mountDetail()

    await wrapper.find('.d-btn-save').trigger('click')
    await flushPromises()

    expect(saveTracking).not.toHaveBeenCalled()
  })

  it('「+ 追番」只发 subjectId + status, 不把进度和评分写成 0', async () => {
    getTrackingStatus.mockResolvedValue({ data: { code: 200, data: { tracked: false } } })
    const wrapper = await mountDetail()
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 11, status: 'watching', progress: 0 } } })

    await wrapper.find('.d-btn-track').trigger('click')
    await flushPromises()

    // 改前这里先把 trackForm 清成 progress:0 / score:0 再整行走 saveTrack ——
    // 于是"服务端已经有行、本地还没拿到 id"的那一小段时间里点它, 会把打卡
    // 刚推上去的进度清零
    expect(saveTracking).toHaveBeenCalledWith({ subjectId: 7, status: 'watching' })
  })
})
