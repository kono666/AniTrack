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

describe('追番进度的封顶', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    // load() 里是一个 Promise.all: 任何一个返回 undefined 都会在解构 .data 时抛,
    // 整页落到错误态 —— 那样后面的断言全都会变成"找不到元素"
    getAnimeDetail.mockResolvedValue({ data: { code: 200, data: SUBJECT } })
    getEpisodes.mockResolvedValue({ data: { code: 200, data: [] } })
    getRatingStats.mockResolvedValue({
      data: { code: 200, data: { average: 0, count: 0, distribution: Array(10).fill(0) } },
    })
    getSubjectReviews.mockResolvedValue({ data: { code: 200, data: [] } })
    getMyReview.mockResolvedValue({ data: { code: 200, data: { exists: false } } })
    getTrackingStatus.mockResolvedValue(trackingPayload(11))
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
