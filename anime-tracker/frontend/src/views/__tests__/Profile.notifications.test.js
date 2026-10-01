import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getTrackingList: vi.fn(),
  getOverallStats: vi.fn(),
  saveTracking: vi.fn(),
  getNotifications: vi.fn(),
  markNotificationsRead: vi.fn(),
}))

import Profile from '../Profile.vue'
import { getTrackingList, getOverallStats, getNotifications, markNotificationsRead } from '../../api'
import { useNotificationStore } from '../../stores/notification'

/**
 * 个人页的通知区块. 它取代了原先的「收到的回复」, 所以旧的几条断言(摘要为空时
 * 显示「（无文字）」、失败不拖垮整页、失败态与空态要分得开)在这里都有对应的继任者,
 * 另外多了三件新东西:
 *
 *   1. **三类通知**. 赞评论那条没有回复正文, 回复类那条有两段(我的评论 + 他的话)——
 *      三类共用一套行渲染, 所以要钉住每种类型各显示哪几段。
 *   2. **未读态与"进去就把红点清掉"**. 顺序是"先读列表、再标已读": 反过来标的话,
 *      服务端回给我们的每一行都是 read: true, 未读那道竖线永远不出现。所以这里同时
 *      钉住"标已读**发生了**"和"标之前那几行**仍然显示为未读**"。
 *   3. **分页**. 旧版是服务端封顶 30 条 + 一句"只显示最近 30 条"; 现在是真的翻页。
 *
 * ⚠️ api mock 是整体替换模块. 漏一个导出就在解构时抛.
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

/**
 * 一条通知. 默认是"已读的回复类", 需要别的形状就覆盖字段。
 *
 * 默认 read: true 是刻意的 —— 这样"没有未读时不发标已读请求"那一条用例的起点
 * 就是干净的, 而需要未读的用例显式写 read: false, 读起来一眼看得出它要什么。
 */
function notice(overrides = {}) {
  return {
    id: 11, type: 'REPLY', actorName: 'carol', actorAvatar: null,
    reviewId: 3, subjectId: 101, reviewContent: '这部的作画真稳',
    replyId: 7, replyContent: '同感',
    createdAt: '2026-01-05T00:00:00', read: true,
    ...overrides,
  }
}

/**
 * 挂个人页并铺好四个接口的桩.
 *
 * ⚠️ **桩全部在这里设**: 用例要换形状就传参, 不要在调用它**之前**写
 * `mockNotificationsRead.mockRejectedValue(...)` —— 那会被这里的默认值当场覆盖掉,
 * 而症状是"我明明让它失败, 它却成功了"。
 */
async function mountProfile({ list = [], total = list.length, fails = false, markFails = false, unread = 0 } = {}) {
  localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  setActivePinia(createPinia())
  const notificationStore = useNotificationStore()
  // 红点上的数字来自这个 store(NavBar 与这一页共用一份), 所以"进个人页后红点清零"
  // 这件事在两处之间是可断言的
  notificationStore.unreadCount = unread

  getTrackingList.mockResolvedValue(ok([
    { id: 1, subjectId: 101, animeTitle: 'A', status: 'watching', progress: 3, totalEpisodes: 12 },
  ]))
  getOverallStats.mockResolvedValue(ok({ totalAnime: 1, totalEpisodes: 12, totalReviews: 1, avgScore: 8 }))
  if (markFails) markNotificationsRead.mockRejectedValue(new Error('boom'))
  else markNotificationsRead.mockResolvedValue(ok(null))
  if (fails) getNotifications.mockRejectedValue(new Error('boom'))
  else getNotifications.mockResolvedValue(ok({ list, total }))

  await router.push('/')
  await router.isReady()

  const wrapper = mount(Profile, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, notificationStore }
}

const rows = w => w.findAll('.pn-item')

describe('个人页的通知区块', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
  })

  it('回复类: 谁回复了我、我那条评论的摘要、他说的那句话', async () => {
    const { wrapper } = await mountProfile({ list: [notice()] })

    const row = rows(wrapper)[0]
    expect(row.text()).toContain('carol')
    expect(row.text()).toContain('回复了你的评论')
    expect(row.text()).toContain('这部的作画真稳')
    expect(row.text()).toContain('同感')
  })

  it('赞评论类: 只有我那条评论的摘要, 没有"回复正文"那一段', async () => {
    const { wrapper } = await mountProfile({
      list: [notice({ type: 'REVIEW_LIKE', replyId: null, replyContent: null })],
    })

    const row = rows(wrapper)[0]
    expect(row.text()).toContain('赞了你的评论')
    expect(row.text()).toContain('这部的作画真稳')
    // 赞评论那条压根没有回复可说 —— 空着比显示一个空行对
    expect(row.find('.pn-text').exists()).toBe(false)
  })

  it('赞回复类: 我那条评论 + 我那条回复, 两段都在', async () => {
    const { wrapper } = await mountProfile({
      list: [notice({ type: 'REPLY_LIKE', replyContent: '我那句回复' })],
    })

    const row = rows(wrapper)[0]
    expect(row.text()).toContain('赞了你的回复')
    expect(row.text()).toContain('这部的作画真稳')
    expect(row.find('.pn-text').text()).toBe('我那句回复')
  })

  it('认不出来的类型给一句兜底, 不是空白', async () => {
    // 服务端将来加了第四类而前端没跟上时, 那一行该读成"某人 和你有互动"
    const { wrapper } = await mountProfile({ list: [notice({ type: 'FUTURE_KIND' })] })

    expect(rows(wrapper)[0].text()).toContain('和你有互动')
  })

  it('点一条跳到那部番的详情页', async () => {
    const { wrapper } = await mountProfile({ list: [notice({ subjectId: 101 })] })

    await rows(wrapper)[0].trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/anime/101')
  })

  it('评论只有评分没有正文时显示「（无文字）」而不是一片空白', async () => {
    const { wrapper } = await mountProfile({ list: [notice({ reviewContent: null })] })

    expect(wrapper.find('.pn-quote').text()).toBe('（无文字）')
  })

  it('未读的行带未读样式, 已读的不带', async () => {
    const { wrapper } = await mountProfile({
      list: [notice({ id: 1, read: false }), notice({ id: 2, read: true })],
    })

    expect(rows(wrapper)[0].classes()).toContain('pn-unread')
    expect(rows(wrapper)[1].classes()).not.toContain('pn-unread')
  })

  it('进来就把未读标成已读, 红点跟着清零 —— 而且那一行仍以未读的样子渲染', async () => {
    const { wrapper, notificationStore } = await mountProfile({
      list: [notice({ read: false })],
      unread: 3,
    })

    expect(markNotificationsRead).toHaveBeenCalledTimes(1)
    expect(notificationStore.unreadCount).toBe(0)
    // 顺序的哨兵: 先标已读的话, 服务端回给我们的会是 read: true, 这道竖线不会出现
    expect(rows(wrapper)[0].classes()).toContain('pn-unread')
  })

  it('一条未读都没有时不发标已读的请求', async () => {
    const { notificationStore } = await mountProfile({ list: [notice({ read: true })], unread: 0 })

    expect(markNotificationsRead).not.toHaveBeenCalled()
    expect(notificationStore.unreadCount).toBe(0)
  })

  it('没有通知时给空态, 而且它与失败态长得不一样', async () => {
    const { wrapper } = await mountProfile({ list: [] })

    const hint = wrapper.find('.pn-hint')
    expect(hint.text()).toBe('还没有人回复或赞过你')
    expect(hint.classes()).not.toContain('pn-hint-err')
    expect(rows(wrapper)).toHaveLength(0)
  })

  it('这一块拉不到: 只在这一块里说, 追番列表照常显示', async () => {
    // 这一条是从旧版「收到的回复」原样搬过来的 —— 那一版的失败会经 Promise.all
    // 把整页拖进错误态, 所以当时必须给 mock; 现在通知是独立的一路
    const { wrapper } = await mountProfile({ fails: true })

    expect(wrapper.find('.p-card').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('加载追番记录失败')

    const hint = wrapper.find('.pn-hint')
    expect(hint.text()).toContain('通知暂时拉不到')
    // 与"还没有人回复或赞过你"必须区分得开
    expect(hint.classes()).toContain('pn-hint-err')
  })

  it('标已读失败不影响列表: 通知照常显示, 红点留着下次再清', async () => {
    const { wrapper, notificationStore } = await mountProfile({
      list: [notice({ read: false })],
      markFails: true,
      unread: 1,
    })

    expect(rows(wrapper)).toHaveLength(1)
    expect(wrapper.find('.pn-hint-err').exists()).toBe(false)
    expect(notificationStore.unreadCount).toBe(1)
  })

  it('超过一页时出现翻页控件, 点第二页只重拉通知', async () => {
    const { wrapper } = await mountProfile({ list: [notice()], total: 45 })

    expect(getNotifications).toHaveBeenLastCalledWith({ page: 1, limit: 20 })

    const page2 = wrapper.findAll('.pg-btn').find(b => b.text() === '2')
    expect(page2, '45 条 / 每页 20 条 → 应当有第 2 页').toBeTruthy()
    await page2.trigger('click')
    await flushPromises()

    expect(getNotifications).toHaveBeenLastCalledWith({ page: 2, limit: 20 })
    // 追番列表与页码无关, 不该跟着重拉
    expect(getTrackingList).toHaveBeenCalledTimes(1)
  })

  it('只有一页时不显示翻页控件', async () => {
    const { wrapper } = await mountProfile({ list: [notice()], total: 1 })

    expect(wrapper.find('.pagination').exists()).toBe(false)
  })
})
