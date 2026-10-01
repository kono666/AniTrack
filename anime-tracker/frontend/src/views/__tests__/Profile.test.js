import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getTrackingList: vi.fn(),
  getOverallStats: vi.fn(),
  saveTracking: vi.fn(),
  // 通知那一块也是这一页会调的接口 —— 整体替换模块, 漏一个就在解构时抛
  getNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
}))

import Profile from '../Profile.vue'
import { getTrackingList, getOverallStats, saveTracking, getNotifications } from '../../api'

/**
 * 个人页的两个数字口径问题.
 *
 * 一、「在看」原先算的是 totalAnime - completed, 也就是"除了看完的都算在看":
 *     想看、搁置、抛弃全被算了进去. 而同一页下面的筛选栏就摆着一个
 *     「在看 N」—— 两个数字经常对不上, 用户没有理由知道该信哪个.
 *
 * 二、「+1 集」原先没有上限: 12 集的番能被点到 13/12. 进度条按百分比卡在 100%
 *     所以看不出来, 但数字就写在那儿, 而且会进库、进统计.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const TRACKINGS = [
  { id: 1, subjectId: 101, animeTitle: 'A', status: 'watching', progress: 3, totalEpisodes: 12, score: 8 },
  { id: 2, subjectId: 102, animeTitle: 'B', status: 'watching', progress: 12, totalEpisodes: 12, score: 7 },
  { id: 3, subjectId: 103, animeTitle: 'C', status: 'want_to_watch', progress: 0, totalEpisodes: 24 },
  { id: 4, subjectId: 104, animeTitle: 'D', status: 'on_hold', progress: 2, totalEpisodes: 24 },
  { id: 5, subjectId: 105, animeTitle: 'E', status: 'dropped', progress: 1, totalEpisodes: 12 },
  { id: 6, subjectId: 106, animeTitle: 'F', status: 'watched', progress: 12, totalEpisodes: 12 },
]

/** 按标题取那一行的 +1 按钮 */
function plusOneOf(wrapper, title) {
  const card = wrapper.findAll('.p-card').find(c => c.text().includes(title))
  return card.find('.pca-btn')
}

async function mountProfile() {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())
  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('个人页的统计口径与 +1 封顶', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    getTrackingList.mockResolvedValue({ data: { code: 200, data: TRACKINGS.map(t => ({ ...t })) } })
    getOverallStats.mockResolvedValue({ data: { code: 200, data: { totalAnime: 6, totalEpisodes: 30, totalReviews: 2, avgScore: 7.5, completed: 1 } } })
    saveTracking.mockResolvedValue({ data: { code: 200, data: { id: 1 } } })
    // 通知那一块默认给空 —— 这几个用例测的是追番统计, 不关心它.
    // 不给也能跑(那一块的失败自己咽掉了, 只显示"通知暂时拉不到"), 但给空更接近
    // 真实情况: 这几条用例的断言是"整页正常", 而一个必然失败的附带区块混在里面,
    // 会把"整页正常"这件事的成色说糊
    getNotifications.mockResolvedValue({ data: { code: 200, data: { list: [], total: 0 } } })
  })

  it('「在看」按 status=watching 统计, 与筛选栏同一口径', async () => {
    const wrapper = await mountProfile()

    const stats = wrapper.findAll('.p-stat')
    const watching = stats.find(s => s.text().includes('在看'))
    // 6 条记录里只有 2 条是 watching. 改前那个算法(6-1)会给出 5
    expect(watching.find('.ps-num').text()).toBe('2')

    // 与筛选栏上的「在看」计数一致 —— 这正是改前对不上的那两个数
    const tab = wrapper.findAll('.p-tab').find(t => t.text().startsWith('在看'))
    expect(tab.text()).toContain('2')
  })

  it('未看到最后一集时 +1 提交 progress+1', async () => {
    const wrapper = await mountProfile()
    await plusOneOf(wrapper, 'A').trigger('click')
    await flushPromises()

    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ subjectId: 101, progress: 4 }))
  })

  it('已经看完的番, +1 按钮被禁用', async () => {
    const wrapper = await mountProfile()
    expect(plusOneOf(wrapper, 'B').attributes('disabled')).toBeDefined()
  })

  it('点不动的按钮不发请求, 也不会把 12 变成 13', async () => {
    const wrapper = await mountProfile()
    await plusOneOf(wrapper, 'B').trigger('click')
    await flushPromises()

    expect(saveTracking).not.toHaveBeenCalled()
  })

  it('总集数未知时不封顶也不禁用(那种情况下任何上限都是编的)', async () => {
    getTrackingList.mockResolvedValue({
      data: { code: 200, data: [{ id: 9, subjectId: 109, animeTitle: '未知集数', status: 'watching', progress: 99 }] },
    })
    const wrapper = await mountProfile()

    expect(plusOneOf(wrapper, '未知集数').attributes('disabled')).toBeUndefined()
    await plusOneOf(wrapper, '未知集数').trigger('click')
    await flushPromises()
    expect(saveTracking).toHaveBeenCalledWith(expect.objectContaining({ progress: 100 }))
  })
})
