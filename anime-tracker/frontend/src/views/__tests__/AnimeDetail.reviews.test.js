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
  getMyReview, getTrackingStatus, getWatchedEpisodes, getAnimeHeat,
  likeReview, unlikeReview, getReviewLikers,
} from '../../api'

/**
 * 详情页评论区的点赞与排序.
 *
 * 这几条盯的都是**从界面上看不出来**的错误:
 *
 *   · 计数是本地 +1 猜的还是服务端回的那个 —— 幂等路径(已经赞过再点一次)下
 *     两者不是一回事, 猜出来的数字会一直错下去, 直到下次刷新;
 *   · 未登录点按钮是"没反应"还是"去登录" —— 前者看起来像坏了;
 *   · 排序开关有没有真的把参数换掉 —— 传错值不会报错, 后端对未知 sort 退化成
 *     默认序, 于是"最热"点了没反应, 页面上看不出任何异常;
 *   · 「谁赞了」拉回来的名单有没有真的渲染上去 —— 这里曾经差点栽在 Vue 的
 *     响应式上: 存进 likerBox 的是个普通对象, 拿赋值时那个引用去改它不会触发
 *     更新, 名单到了页面上也不动, 而请求是成功的.
 *
 * ⚠️ 这个文件里的 api mock 是**整体替换**模块, 必须把组件用到的每个导出都列上,
 * 漏一个就在解构 .data 时抛, 整页落到错误态, 后面所有断言都会变成"找不到元素"。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const SUBJECT = { id: 7, nameCn: '某番', totalEpisodes: 12, images: { large: 'x.jpg' } }

/** 一条评论. 默认: 已有点赞(2 个), 而「我」没赞过 */
function review(overrides = {}) {
  return {
    id: 1, userId: 9, username: 'bob', rating: 8, content: '好看',
    createdAt: '2026-01-01T00:00:00', likeCount: 2, likedByMe: false,
    ...overrides,
  }
}

const ok = data => ({ data: { code: 200, data } })

/** 挂上详情页. loggedIn=false 时不写 localStorage, 于是走访客那条路 */
async function mountDetail({ loggedIn = true, reviews = [review()] } = {}) {
  if (loggedIn) {
    localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  }
  setActivePinia(createPinia())

  await router.push('/anime/7')
  await router.isReady()

  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 第 i 条评论那一块 */
const itemAt = (wrapper, i) => wrapper.findAll('.rv-item')[i]
/** 那一条的赞按钮 */
const likeBtnAt = (wrapper, i) => itemAt(wrapper, i).find('.rv-act')

describe('评论点赞', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    getAnimeDetail.mockResolvedValue(ok(SUBJECT))
    getEpisodes.mockResolvedValue(ok([]))
    getRatingStats.mockResolvedValue(ok({ average: 8, count: 1, distribution: Array(10).fill(0) }))
    getSubjectReviews.mockResolvedValue(ok([review()]))
    getMyReview.mockResolvedValue(ok({ exists: false }))
    getTrackingStatus.mockResolvedValue(ok({ tracked: false }))
    getWatchedEpisodes.mockResolvedValue(ok([]))
    getAnimeHeat.mockResolvedValue(ok(null))
  })

  it('未赞时点一下: 调 likeReview, 选中态与计数都取服务端回的那一份', async () => {
    // 服务端说 3 —— 而不是"本来 2, 本地 +1" 那个同样是 3 的巧合值:
    // 下面那条幂等用例才分得开这两者
    likeReview.mockResolvedValue(ok({ liked: true, likeCount: 3 }))
    const wrapper = await mountDetail()

    expect(likeBtnAt(wrapper, 0).classes()).not.toContain('on')
    await likeBtnAt(wrapper, 0).trigger('click')
    await flushPromises()

    expect(likeReview).toHaveBeenCalledWith(1)
    expect(unlikeReview).not.toHaveBeenCalled()
    expect(likeBtnAt(wrapper, 0).classes()).toContain('on')
    expect(likeBtnAt(wrapper, 0).text()).toContain('3')
  })

  it('已赞时点一下走取消, 计数同样以服务端为准', async () => {
    getSubjectReviews.mockResolvedValue(ok([review({ likedByMe: true, likeCount: 3 })]))
    unlikeReview.mockResolvedValue(ok({ liked: false, likeCount: 1 }))
    const wrapper = await mountDetail()

    await likeBtnAt(wrapper, 0).trigger('click')
    await flushPromises()

    expect(unlikeReview).toHaveBeenCalledWith(1)
    expect(likeReview).not.toHaveBeenCalled()
    expect(likeBtnAt(wrapper, 0).classes()).not.toContain('on')
    expect(likeBtnAt(wrapper, 0).text()).toContain('1')
  })

  it('幂等路径: 服务端已经记着这个赞了, 计数就不该被本地再猜一次', async () => {
    // 界面以为没赞过(比如另一个标签页赞的), 点下去服务端回 liked=true / likeCount=3 ——
    // 本地 +1 会算成 3 也好巧不巧, 所以这里让服务端回 5
    likeReview.mockResolvedValue(ok({ liked: true, likeCount: 5 }))
    const wrapper = await mountDetail()

    await likeBtnAt(wrapper, 0).trigger('click')
    await flushPromises()

    expect(likeBtnAt(wrapper, 0).text()).toContain('5')
  })

  it('未登录点按钮: 送去登录页, 一个请求都不发', async () => {
    const wrapper = await mountDetail({ loggedIn: false })

    expect(likeBtnAt(wrapper, 0).exists()).toBe(true)   // 按钮照渲染, 不藏起来
    await likeBtnAt(wrapper, 0).trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(likeReview).not.toHaveBeenCalled()
  })

  it('点赞失败: 弹提示, 而且选中态不翻过去(界面与库里的状态不能各说各话)', async () => {
    likeReview.mockRejectedValue(new Error('boom'))
    const wrapper = await mountDetail()

    await likeBtnAt(wrapper, 0).trigger('click')
    await flushPromises()

    expect(likeBtnAt(wrapper, 0).classes()).not.toContain('on')
    expect(likeBtnAt(wrapper, 0).text()).toContain('2')
  })
})

describe('评论排序', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    getAnimeDetail.mockResolvedValue(ok(SUBJECT))
    getEpisodes.mockResolvedValue(ok([]))
    getRatingStats.mockResolvedValue(ok({ average: 8, count: 2, distribution: Array(10).fill(0) }))
    getSubjectReviews.mockResolvedValue(ok([review(), review({ id: 2, username: 'carol' })]))
    getMyReview.mockResolvedValue(ok({ exists: false }))
    getTrackingStatus.mockResolvedValue(ok({ tracked: false }))
    getWatchedEpisodes.mockResolvedValue(ok([]))
    getAnimeHeat.mockResolvedValue(ok(null))
  })

  const sortBtns = wrapper => wrapper.findAll('.rv-sort-btn')

  it('初次加载按时间序, 并把 userId 一起带上(点赞状态是按人算的)', async () => {
    await mountDetail()

    expect(getSubjectReviews).toHaveBeenCalledWith(7, 1, 'createdAt')
  })

  it('点「最热」用 hot 重新拉一遍列表, 不是本地重排', async () => {
    const wrapper = await mountDetail()
    getSubjectReviews.mockClear()

    await sortBtns(wrapper)[1].trigger('click')
    await flushPromises()

    expect(getSubjectReviews).toHaveBeenCalledWith(7, 1, 'hot')
    expect(sortBtns(wrapper)[1].classes()).toContain('active')
    expect(sortBtns(wrapper)[0].classes()).not.toContain('active')
  })

  it('点已选中的那个不重发请求', async () => {
    const wrapper = await mountDetail()
    getSubjectReviews.mockClear()

    await sortBtns(wrapper)[0].trigger('click')
    await flushPromises()

    expect(getSubjectReviews).not.toHaveBeenCalled()
  })

  it('切换失败时开关退回去 —— 显示「最热」而列表还是时间序比不切换更糟', async () => {
    const wrapper = await mountDetail()
    getSubjectReviews.mockRejectedValueOnce(new Error('boom'))

    await sortBtns(wrapper)[1].trigger('click')
    await flushPromises()

    expect(sortBtns(wrapper)[0].classes()).toContain('active')
    expect(sortBtns(wrapper)[1].classes()).not.toContain('active')
  })

  it('只有一条评论时不摆排序开关(排了也看不出差别)', async () => {
    getSubjectReviews.mockResolvedValue(ok([review()]))
    const wrapper = await mountDetail()

    expect(wrapper.find('.rv-sort').exists()).toBe(false)
  })
})

describe('谁赞了', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    getAnimeDetail.mockResolvedValue(ok(SUBJECT))
    getEpisodes.mockResolvedValue(ok([]))
    getRatingStats.mockResolvedValue(ok({ average: 8, count: 1, distribution: Array(10).fill(0) }))
    getSubjectReviews.mockResolvedValue(ok([review()]))
    getMyReview.mockResolvedValue(ok({ exists: false }))
    getTrackingStatus.mockResolvedValue(ok({ tracked: false }))
    getWatchedEpisodes.mockResolvedValue(ok([]))
    getAnimeHeat.mockResolvedValue(ok(null))
  })

  it('展开时拉名单, 名字真的渲染出来', async () => {
    getReviewLikers.mockResolvedValue(ok({
      total: 2, list: [{ userId: 3, username: 'bob' }, { userId: 4, username: 'carol' }],
    }))
    const wrapper = await mountDetail()

    expect(wrapper.find('.rv-likers').exists()).toBe(false)
    await itemAt(wrapper, 0).find('.rv-act-likers').trigger('click')
    await flushPromises()

    expect(getReviewLikers).toHaveBeenCalledWith(1)
    const names = wrapper.findAll('.rv-liker').map(n => n.text())
    expect(names).toEqual(['bob', 'carol'])
  })

  it('名单在服务端封顶时把真实总数说出来, 免得看着像漏了', async () => {
    getReviewLikers.mockResolvedValue(ok({ total: 80, list: [{ userId: 3, username: 'bob' }] }))
    const wrapper = await mountDetail()

    await itemAt(wrapper, 0).find('.rv-act-likers').trigger('click')
    await flushPromises()

    expect(wrapper.find('.rv-likers').text()).toContain('80')
  })

  it('收起再展开不重拉(名单是拉过一次就留着的)', async () => {
    getReviewLikers.mockResolvedValue(ok({ total: 1, list: [{ userId: 3, username: 'bob' }] }))
    const wrapper = await mountDetail()
    const toggle = () => itemAt(wrapper, 0).find('.rv-act-likers').trigger('click')

    await toggle()
    await flushPromises()
    await toggle()          // 收起
    await flushPromises()
    expect(wrapper.find('.rv-likers').exists()).toBe(false)

    await toggle()          // 再展开
    await flushPromises()
    expect(wrapper.findAll('.rv-liker').map(n => n.text())).toEqual(['bob'])
    expect(getReviewLikers).toHaveBeenCalledTimes(1)
  })

  it('一个赞都没有时不摆「谁赞了」(点开来是空的)', async () => {
    getSubjectReviews.mockResolvedValue(ok([review({ likeCount: 0 })]))
    const wrapper = await mountDetail()

    expect(itemAt(wrapper, 0).find('.rv-act-likers').exists()).toBe(false)
    // 而举报按钮照在 —— 这一排的按钮各有各的显示条件, 别把"谁赞了不见了"读成
    // "安静的那几个都不见了": 它们曾经共用一个 rv-act-quiet 类, 认类就等于认错人
    expect(itemAt(wrapper, 0).find('.rv-act-report').exists()).toBe(true)
  })

  it('切排序后旧名单必须清掉 —— 它存的是上一批数据的快照', async () => {
    getSubjectReviews.mockResolvedValue(ok([review(), review({ id: 2, username: 'carol' })]))
    getReviewLikers.mockResolvedValue(ok({ total: 1, list: [{ userId: 3, username: 'bob' }] }))
    const wrapper = await mountDetail()

    await itemAt(wrapper, 0).find('.rv-act-likers').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.rv-liker')).toHaveLength(1)

    await wrapper.findAll('.rv-sort-btn')[1].trigger('click')
    await flushPromises()

    expect(wrapper.find('.rv-likers').exists()).toBe(false)
  })
})
