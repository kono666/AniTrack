import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

const toastSpy = vi.fn()

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
  // 附属数据那三个. 一个都不能漏 —— 理由见 AnimeDetail.test.js 里同一处
  getSubjectCharacters: vi.fn(),
  getSubjectStaff: vi.fn(),
  getSubjectRelations: vi.fn(),
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

// 提示语是这一块**唯一**能看出「服务端说 duplicate」的地方 —— 界面其余部分
// (按钮点亮、面板收起)在"刚记下"与"本来就在"两种情况下完全一样. 所以这里不去
// 读真实的 items, 而是把 show 换成探针.
vi.mock('../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
}))

import AnimeDetail from '../AnimeDetail.vue'
import {
  getAnimeDetail, getEpisodes, getRatingStats, getSubjectReviews,
  getMyReview, getTrackingStatus, getWatchedEpisodes, getAnimeHeat,
  reportReview,
} from '../../api'

/**
 * 详情页的举报入口.
 *
 * 盯的是这几件从界面上看不出来的事:
 *
 *   · 访客点举报是**被弹去登录**, 而不是填完一整个面板再收一个 401 —— 点赞和
 *     回复的读路径公开, 举报是写, 这个差别要有人守着;
 *   · `duplicate` 只有服务端说了算 —— 幂等路径下服务端什么都没做却照回 200,
 *     前端分不出"刚记下"与"本来就在", 所以提示语必须跟着那个布尔走;
 *   · 补充说明留空时传的是 undefined 而不是空串(空白值传出去, 一次没写的填写
 *     看起来就像写了), 而填了就传 trim 过的;
 *   · 草稿与"已举报过"这个事实**换列表也不清** —— 它们是跟着"这一条评论"走的,
 *     重新排序后同一条评论还是同一条;
 *   · 上报失败时面板**不收起**, 否则用户填的东西没了还得重来.
 *
 * ⚠️ api mock 是**整体替换**模块, 组件用到的每个导出都要列上, 漏一个就在解构
 * .data 时抛, 整页落到错误态, 后面所有断言都会变成"找不到元素"(看起来像功能没做)。
 * 详情页另外三个测试文件里也各有一份同样的 mock, 加接口时四处都要补。
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/anime/:id', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
  ],
})

const SUBJECT = { id: 7, nameCn: '某番', totalEpisodes: 12, images: { large: 'x.jpg' } }

function review(overrides = {}) {
  return {
    id: 1, userId: 9, username: 'bob', rating: 8, content: '好看',
    createdAt: '2026-01-01T00:00:00', isOwner: false,
    likeCount: 0, likedByMe: false, replyCount: 0,
    ...overrides,
  }
}

const ok = data => ({ data: { code: 200, data } })

async function mountDetail({ loggedIn = true, reviews = [review()] } = {}) {
  if (loggedIn) {
    localStorage.setItem('anime_user', JSON.stringify({ id: 1, username: 'alice', token: 'jwt', role: 'USER' }))
  }
  setActivePinia(createPinia())

  getAnimeDetail.mockResolvedValue(ok(SUBJECT))
  getEpisodes.mockResolvedValue(ok([]))
  getRatingStats.mockResolvedValue(ok({ average: 8, count: 1, distribution: Array(10).fill(0) }))
  getSubjectReviews.mockResolvedValue(ok(reviews))
  getMyReview.mockResolvedValue(ok({ exists: false }))
  getTrackingStatus.mockResolvedValue(ok({ tracked: false }))
  getWatchedEpisodes.mockResolvedValue(ok([]))
  getAnimeHeat.mockResolvedValue(ok(null))

  await router.push('/anime/7')
  await router.isReady()

  const wrapper = mount(AnimeDetail, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** 第 i 条评论那一块 */
const itemAt = (wrapper, i) => wrapper.findAll('.rv-item')[i]
/** 那条评论的「举报」按钮 */
const reportBtnAt = (wrapper, i) => itemAt(wrapper, i).find('.rv-act-report')
/** 那条评论已展开的举报面板 */
const panelAt = (wrapper, i) => itemAt(wrapper, i).find('.rv-report')

/** 点开举报面板 */
async function openReport(wrapper, i = 0) {
  await reportBtnAt(wrapper, i).trigger('click')
  await flushPromises()
}

/** 勾一个理由(值走 v-model, 直接设 input 的 checked 再触发 change) */
async function pickReason(wrapper, value, i = 0) {
  const radio = panelAt(wrapper, i).find(`input[type="radio"][value="${value}"]`)
  await radio.setValue()
}

/** 填补充说明 */
async function fillDetail(wrapper, text, i = 0) {
  await panelAt(wrapper, i).find('.rv-report-detail').setValue(text)
}

const sendBtnAt = (wrapper, i = 0) => panelAt(wrapper, i).find('.rv-report-send')

describe('举报', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    toastSpy.mockReset()
  })

  it('访客点举报被送去登录页, 面板不展开也不发请求', async () => {
    const wrapper = await mountDetail({ loggedIn: false })

    await openReport(wrapper)

    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('.rv-report').exists()).toBe(false)
    expect(reportReview).not.toHaveBeenCalled()
  })

  it('四个理由都摆出来, 且 radio 的 name 逐条评论各不相同', async () => {
    const wrapper = await mountDetail({ reviews: [review(), review({ id: 2, content: '第二条' })] })

    await openReport(wrapper, 0)
    await openReport(wrapper, 1)

    const opts = panelAt(wrapper, 0).findAll('.rv-report-opt')
    expect(opts.map(o => o.text())).toEqual(['垃圾广告', '辱骂攻击', '剧透', '其他'])

    // name 相同的话, 给这条选了理由会把另一条已展开的一起清掉 —— 单选按钮是按
    // name 分组的
    const nameOf = i => panelAt(wrapper, i).find('input[type="radio"]').attributes('name')
    expect(nameOf(0)).not.toBe(nameOf(1))
  })

  it('没选理由时提交按钮是灰的, 点了也不发请求', async () => {
    const wrapper = await mountDetail()
    await openReport(wrapper)

    expect(sendBtnAt(wrapper).attributes('disabled')).toBeDefined()

    // ⚠️ 这一下是被**两道闸**一起挡住的: 模板上的 :disabled 与 submitReport 里那句
    // 同步的 if. 只删其中一个这里照样是绿的(另一道还挡得住), 两个都删才会红 ——
    // 别把"这条是绿的"读成"两道闸都在".
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    expect(reportReview).not.toHaveBeenCalled()
    expect(toastSpy).not.toHaveBeenCalled()
  })

  it('选了理由就能提交: 带上 reviewId 与理由, 提示、收起、按钮点亮', async () => {
    const wrapper = await mountDetail()
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: false }))

    await openReport(wrapper)
    await pickReason(wrapper, 'SPAM')

    expect(sendBtnAt(wrapper).attributes('disabled')).toBeUndefined()

    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    expect(reportReview).toHaveBeenCalledWith(1, { reason: 'SPAM', detail: undefined })
    expect(toastSpy).toHaveBeenCalledWith('已收到举报', 'success')
    expect(panelAt(wrapper, 0).exists()).toBe(false)
    expect(reportBtnAt(wrapper, 0).classes()).toContain('on')
  })

  it('服务端说 duplicate 时提示语不一样 —— 这个布尔前端猜不出来', async () => {
    const wrapper = await mountDetail()
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: true }))

    await openReport(wrapper)
    await pickReason(wrapper, 'ABUSE')
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('你已经举报过这条评论', 'info')
    // 点亮这件事两种情况下都一样: 结果确实是"这条我已经举报过了"
    expect(reportBtnAt(wrapper, 0).classes()).toContain('on')
  })

  it('补充说明: 只有空白时传 undefined, 有内容时传 trim 过的', async () => {
    const wrapper = await mountDetail()
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: false }))

    await openReport(wrapper)
    await pickReason(wrapper, 'OTHER')
    await fillDetail(wrapper, '   ')
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    // 传空串等于让"什么都没写"看起来像写了 —— 后端把空白一律存成 null,
    // 前端这一层就该把"没填"表达成"没有这个键"
    expect(reportReview).toHaveBeenCalledWith(1, { reason: 'OTHER', detail: undefined })

    reportReview.mockClear()
    await openReport(wrapper)
    await pickReason(wrapper, 'SPOILER')
    await fillDetail(wrapper, '  第 3 集就剧透了  ')
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    expect(reportReview).toHaveBeenCalledWith(1, { reason: 'SPOILER', detail: '第 3 集就剧透了' })
  })

  it('提交失败时面板不收起, 用户填的东西还在', async () => {
    const wrapper = await mountDetail()
    reportReview.mockRejectedValue(new Error('boom'))

    await openReport(wrapper)
    await pickReason(wrapper, 'SPAM')
    await fillDetail(wrapper, '广告')
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('举报失败', 'error')
    expect(panelAt(wrapper, 0).exists()).toBe(true)
    expect(panelAt(wrapper, 0).find('.rv-report-detail').element.value).toBe('广告')
    // busy 复位了, 否则这条评论再也提交不出去
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()
    expect(reportReview).toHaveBeenCalledTimes(2)
  })

  it('连点两下只发一次', async () => {
    const wrapper = await mountDetail()
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: false }))

    await openReport(wrapper)
    await pickReason(wrapper, 'SPAM')

    // 同一拍里的两下: 第一下把 busy 置上了, 第二下打的是还没重渲染的按钮
    const btn = sendBtnAt(wrapper)
    await btn.trigger('click')
    await btn.trigger('click')
    await flushPromises()

    expect(reportReview).toHaveBeenCalledTimes(1)
  })

  it('取消只是收起: 草稿留着, 再展开还在', async () => {
    const wrapper = await mountDetail()
    await openReport(wrapper)
    await pickReason(wrapper, 'SPAM')
    await fillDetail(wrapper, '写了一半')

    await panelAt(wrapper, 0).find('.rv-report-cancel').trigger('click')
    expect(panelAt(wrapper, 0).exists()).toBe(false)

    await openReport(wrapper)
    expect(panelAt(wrapper, 0).find('input[value="SPAM"]').element.checked).toBe(true)
    expect(panelAt(wrapper, 0).find('.rv-report-detail').element.value).toBe('写了一半')
  })

  it('举报的是点开的那一条 —— 两条评论的盒子各管各的', async () => {
    const wrapper = await mountDetail({
      reviews: [review(), review({ id: 2, content: '第二条' })],
    })
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: false }))

    await openReport(wrapper, 1)
    await pickReason(wrapper, 'ABUSE', 1)
    await sendBtnAt(wrapper, 1).trigger('click')
    await flushPromises()

    expect(reportReview).toHaveBeenCalledWith(2, { reason: 'ABUSE', detail: undefined })
    expect(reportBtnAt(wrapper, 1).classes()).toContain('on')
    expect(reportBtnAt(wrapper, 0).classes()).not.toContain('on')
  })

  it('已经举报过的评论再点开是重开面板, 不是发第二次', async () => {
    const wrapper = await mountDetail()
    reportReview.mockResolvedValue(ok({ reported: true, duplicate: false }))

    await openReport(wrapper)
    await pickReason(wrapper, 'SPAM')
    await sendBtnAt(wrapper).trigger('click')
    await flushPromises()

    reportReview.mockClear()
    await openReport(wrapper)

    expect(panelAt(wrapper, 0).exists()).toBe(true)
    expect(reportReview).not.toHaveBeenCalled()
    // 按钮仍是点亮态 —— 重开面板不改变"我已经举报过它了"
    expect(reportBtnAt(wrapper, 0).classes()).toContain('on')
  })
})
