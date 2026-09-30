import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getTrackingList: vi.fn(),
  getOverallStats: vi.fn(),
  saveTracking: vi.fn(),
  getReceivedReplies: vi.fn(),
}))

import Profile from '../Profile.vue'
import { getTrackingList, getOverallStats, getReceivedReplies } from '../../api'

/**
 * 个人页的「收到的回复」.
 *
 * 盯的是两件:
 *
 *   · 这一块拉不到时, 整页**不能**落到「加载追番记录失败」—— 追番记录其实好好的,
 *     那是把一次局部失败说成了整页失败(与这一页原有的"错误态优先于空态"同源);
 *   · 拉不到与"还没有人回复你"必须长得不一样 —— 同色同字号的话, 一次网络抖动
 *     看起来就像"这个站没人理我".
 *
 * ⚠️ api mock 是整体替换模块. 这个文件里每个用例都必须给 getReceivedReplies 一个
 * 返回值: 组件在 Promise.all 里对它调 .catch, 返回 undefined 会当场抛.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const ok = data => ({ data: { code: 200, data } })

/** 一条「别人回复了我」: 带 subjectId(点进去看是哪部番)与我那条评论的摘要 */
function received(overrides = {}) {
  return {
    id: 11, reviewId: 3, subjectId: 101, reviewContent: '这部的作画真稳',
    userId: 20, username: 'carol', avatar: null,
    content: '同感',
    createdAt: '2026-01-05T00:00:00',
    ...overrides,
  }
}

async function mountProfile({ replies = [], fails = false } = {}) {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())

  getTrackingList.mockResolvedValue(ok([
    { id: 1, subjectId: 101, animeTitle: 'A', status: 'watching', progress: 3, totalEpisodes: 12 },
  ]))
  getOverallStats.mockResolvedValue(ok({ totalAnime: 1, totalEpisodes: 12, totalReviews: 1, avgScore: 8 }))
  if (fails) getReceivedReplies.mockRejectedValue(new Error('boom'))
  else getReceivedReplies.mockResolvedValue(ok({ list: replies }))

  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('收到的回复', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('列出发来的人、我那条评论的摘要、他说的那句话', async () => {
    const wrapper = await mountProfile({ replies: [received()] })

    const item = wrapper.find('.pr-item')
    expect(item.text()).toContain('carol')
    expect(item.text()).toContain('这部的作画真稳')
    expect(item.text()).toContain('同感')
  })

  it('点一条跳到那部番的详情页', async () => {
    const wrapper = await mountProfile({ replies: [received({ subjectId: 101 })] })

    await wrapper.find('.pr-item').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/anime/101')
  })

  it('评论只有评分没有正文时显示「（无文字）」而不是一片空白', async () => {
    const wrapper = await mountProfile({ replies: [received({ reviewContent: null })] })

    expect(wrapper.find('.pr-quote').text()).toBe('（无文字）')
  })

  it('没有回复时给空态, 而且它与失败态长得不一样', async () => {
    const wrapper = await mountProfile({ replies: [] })

    const hint = wrapper.find('.pr-hint')
    expect(hint.text()).toBe('还没有人回复你')
    expect(hint.classes()).not.toContain('pr-hint-err')
    expect(wrapper.find('.pr-item').exists()).toBe(false)
  })

  it('这一块拉不到: 只在这一块里说, 追番列表照常显示', async () => {
    const wrapper = await mountProfile({ fails: true })

    // 整页没有落到错误态 —— 追番记录是好的
    expect(wrapper.find('.p-card').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('加载追番记录失败')

    const hint = wrapper.find('.pr-hint')
    expect(hint.text()).toContain('回复暂时拉不到')
    // 与"还没有人回复你"必须区分得开
    expect(hint.classes()).toContain('pr-hint-err')
  })

  it('排到服务端封顶(30)时说明只显示了最近的一批', async () => {
    const many = Array.from({ length: 30 }, (_, i) => received({ id: i + 1 }))
    const wrapper = await mountProfile({ replies: many })

    expect(wrapper.findAll('.pr-item')).toHaveLength(30)
    expect(wrapper.find('.p-replies').text()).toContain('只显示最近 30 条')
  })

  it('没到封顶时不显示那句提示', async () => {
    const wrapper = await mountProfile({ replies: [received()] })

    expect(wrapper.find('.p-replies').text()).not.toContain('只显示最近')
  })
})
