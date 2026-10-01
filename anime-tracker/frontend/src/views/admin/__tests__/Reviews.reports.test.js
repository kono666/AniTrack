import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminReviews: vi.fn(),
  adminDeleteReview: vi.fn(),
  // 举报那三个 + 理由常量, 一个都不能漏(整体替换模块): 漏了 REVIEW_REPORT_REASONS
  // 这一页的「举报」列整个是空的, 而看起来像"后端没发这个字段"
  getReviewReports: vi.fn(),
  dismissReport: vi.fn(),
  REVIEW_REPORT_REASONS: [
    { value: 'SPAM', label: '垃圾广告' },
    { value: 'ABUSE', label: '辱骂攻击' },
    { value: 'SPOILER', label: '剧透' },
    { value: 'OTHER', label: '其他' },
  ],
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))
vi.mock('../../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Reviews from '../Reviews.vue'
import { getAdminReviews, getReviewReports, dismissReport } from '../../../api'

/**
 * 后台评论页的举报那一列: 只看被举报、徽标、明细展开, 以及忽略.
 *
 * 盯的是这几件从界面上看不出来的事:
 *
 *   · 明细是**点了才拉**的 —— 一页 20 行全带明细就是 20 倍的响应体;
 *   · 忽略之后**重取当前页**而不是本地删一行 —— `reportCount` 只数待处理的, 而
 *     开着「只看被举报」时, 忽略掉最后一条待处理会让这一行**不再符合筛选**;
 *   · 重取列表**不清**已经展开的明细盒子 —— 它装的是这条评论自己的举报, 换一次序
 *     不变, 清了只会让管理员的展开白点一次;
 *   · 理由标签在界面上是中文, 而库里存的是英文常量(改文案不该配一次数据迁移);
 *   · 「只看被举报」的取值只在 URL 上是 'true' 时才算数 —— 手改 URL 写成
 *     `?reported=1` 要按"没筛"处理, 与后端对未知值的宽容一致.
 */

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/reviews', component: Reviews },
      { path: '/anime/:id', component: { template: '<div />' } },
    ],
  })
}

function pageResult(list, total = list.length) {
  return { data: { code: 200, data: { list, total, page: 1 } } }
}

function reportsResult(list, total = list.length) {
  return { data: { code: 200, data: { list, total } } }
}

function review(id, extra = {}) {
  return {
    id,
    userId: 900 + id,
    username: `u${id}`,
    rating: 8,
    content: `评论${id}`,
    subjectId: 100 + id,
    animeTitle: `番剧${id}`,
    likeCount: 0,
    replyCount: 0,
    reportCount: 0,
    latestReason: null,
    createdAt: '2026-01-01T00:00:00',
    ...extra,
  }
}

/** 一条举报明细. status='PENDING' 时界面上才有「忽略」按钮 */
function report(id, extra = {}) {
  return {
    id,
    reason: 'SPAM',
    reporterName: 'bob',
    detail: null,
    status: 'PENDING',
    handlerName: null,
    handledAt: null,
    createdAt: '2026-02-01T00:00:00',
    ...extra,
  }
}

let router = null
let mounted = []

async function mountAt(fullPath) {
  router = makeRouter()
  await router.push('/')
  await router.isReady()
  await router.push(fullPath)
  const wrapper = mount(Reviews, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  vi.useRealTimers()
  mounted.forEach((w) => w.unmount())
  mounted = []
})

const reportToggle = (w) => w.find('.report-toggle')
const badgeAt = (w, i) => w.findAll('.report-cell')[i]
const detailRowAt = (w, i) => w.findAll('.report-detail-row')[i]
/** 展开某一条的明细 */
async function openDetail(w, i = 0) {
  await badgeAt(w, i).find('.report-badge').trigger('click')
  await flushPromises()
}
const lastParams = () => getAdminReviews.mock.calls.at(-1)[0]

describe('评论管理: 举报', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    toastSpy.mockReset()
    getAdminReviews.mockResolvedValue(pageResult([review(1)], 1))
    getReviewReports.mockResolvedValue(reportsResult([report(11)]))
    dismissReport.mockResolvedValue({ data: { code: 200, data: null } })
  })

  it('「只看被举报」是个开关: 打开参数进请求与 URL, 再点取消', async () => {
    const wrapper = await mountAt('/admin/reviews')

    expect(reportToggle(wrapper).attributes('aria-pressed')).toBe('false')
    expect(lastParams().reported).toBeUndefined()

    await reportToggle(wrapper).trigger('click')
    await flushPromises()

    expect(lastParams().reported).toBe('true')
    expect(router.currentRoute.value.query.reported).toBe('true')
    expect(reportToggle(wrapper).attributes('aria-pressed')).toBe('true')
    expect(reportToggle(wrapper).classes()).toContain('is-on')

    await reportToggle(wrapper).trigger('click')
    await flushPromises()

    expect(lastParams().reported).toBeUndefined()
    expect(router.currentRoute.value.query.reported).toBeUndefined()
  })

  it('手改 URL 写成 ?reported=1 当作没筛 —— 与后端对未知值的宽容一致', async () => {
    const wrapper = await mountAt('/admin/reviews?reported=1')

    expect(reportToggle(wrapper).attributes('aria-pressed')).toBe('false')
    expect(lastParams().reported).toBeUndefined()
  })

  it('徽标显示次数与最近那个理由, 没被举报的行是一个短横', async () => {
    getAdminReviews.mockResolvedValue(pageResult([
      review(1, { reportCount: 3, latestReason: 'ABUSE' }),
      review(2),
    ], 2))

    const wrapper = await mountAt('/admin/reviews')

    const badge = badgeAt(wrapper, 0).find('.report-badge')
    expect(badge.text()).toContain('被举报 3 次')
    // 库里是 ABUSE, 界面上是中文 —— 标签只活在前端
    expect(badge.find('.report-reason').text()).toBe('辱骂攻击')

    // 一次都没被举报的行没有徽标(不可点), 也就不会为它发一次明细请求
    expect(badgeAt(wrapper, 1).find('.report-badge').exists()).toBe(false)
    expect(badgeAt(wrapper, 1).find('.report-none').exists()).toBe(true)
  })

  it('明细是点了才拉, 拉的是那一条评论的', async () => {
    getAdminReviews.mockResolvedValue(pageResult([
      review(1, { reportCount: 1, latestReason: 'SPAM' }),
      review(2, { reportCount: 1, latestReason: 'SPOILER' }),
    ], 2))

    const wrapper = await mountAt('/admin/reviews')

    expect(getReviewReports).not.toHaveBeenCalled()
    expect(wrapper.find('.report-detail-row').exists()).toBe(false)

    await openDetail(wrapper, 1)

    expect(getReviewReports).toHaveBeenCalledWith(2)
    expect(wrapper.findAll('.report-detail-row')).toHaveLength(1)
  })

  it('收起再展开不重拉', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    const wrapper = await mountAt('/admin/reviews')

    await openDetail(wrapper)
    await openDetail(wrapper)                 // 收起
    expect(wrapper.find('.report-detail-row').exists()).toBe(false)
    await openDetail(wrapper)                 // 再展开

    expect(getReviewReports).toHaveBeenCalledTimes(1)
  })

  it('明细里: 理由中文化、举报人、补充说明, 待处理的给「忽略」', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    getReviewReports.mockResolvedValue(reportsResult([
      report(11, { reason: 'SPOILER', reporterName: 'carol', detail: '第 3 集就剧透了' }),
    ]))

    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    const item = detailRowAt(wrapper, 0).find('.report-item')
    expect(item.find('.report-item-reason').text()).toBe('剧透')
    expect(item.find('.report-item-who').text()).toBe('carol')
    expect(item.find('.report-item-note').text()).toBe('第 3 集就剧透了')
    expect(item.find('.report-dismiss').exists()).toBe(true)
    expect(item.find('.report-item-done').exists()).toBe(false)
  })

  it('已经处理过的只留一句"谁处理的", 不给按钮(忽略是单向的)', async () => {
    // reportCount 只数待处理的 → 1; 而明细是**不分状态**的全量 → 两条都在
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    getReviewReports.mockResolvedValue(reportsResult([
      report(11, { status: 'DISMISSED', handlerName: 'admin' }),
      report(12, { reason: 'ABUSE', reporterName: 'dave' }),
    ], 2))

    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    const items = wrapper.findAll('.report-item')
    expect(items).toHaveLength(2)
    expect(items[0].find('.report-item-done').text()).toContain('已忽略')
    expect(items[0].find('.report-item-done').text()).toContain('admin')
    expect(items[0].find('.report-dismiss').exists()).toBe(false)
    expect(items[1].find('.report-dismiss').exists()).toBe(true)
  })

  it('只被忽略过的评论没有徽标, 但那不代表它从来没被举报过', async () => {
    // 徽标说的是"有待处理的", 而不是"有没有被举报过" —— 那两个值在后端算的时候就
    // 只数 PENDING. 点不进去是**有意的**(没有待办就不该占一行的注意力)
    getAdminReviews.mockResolvedValue(pageResult([review(1)], 1))
    const wrapper = await mountAt('/admin/reviews')

    expect(badgeAt(wrapper, 0).find('.report-badge').exists()).toBe(false)
    expect(badgeAt(wrapper, 0).find('.report-none').exists()).toBe(true)
  })

  it('明细条数被封顶时把真实总数说出来', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 60, latestReason: 'SPAM' })], 1))
    getReviewReports.mockResolvedValue(reportsResult([report(11), report(12)], 60))

    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    const hints = detailRowAt(wrapper, 0).findAll('.report-detail-hint')
    expect(hints.map(h => h.text()).join('|')).toContain('60')
  })

  it('忽略一条: 调接口、提示、重取当前页、明细也刷一次', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    getAdminReviews.mockClear()
    getReviewReports.mockClear()

    await detailRowAt(wrapper, 0).find('.report-dismiss').trigger('click')
    await flushPromises()

    expect(dismissReport).toHaveBeenCalledWith(11)
    expect(toastSpy).toHaveBeenCalledWith('已忽略', 'success')
    // 只数待处理的 reportCount 与「只看被举报」的筛选都只有服务端说得准
    expect(getAdminReviews).toHaveBeenCalledTimes(1)
    expect(getReviewReports).toHaveBeenCalledWith(1)
  })

  it('忽略失败: 说服务端那句原因, 不重取列表', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    getAdminReviews.mockClear()
    dismissReport.mockRejectedValue({ response: { data: { message: '举报不存在' } } })

    await detailRowAt(wrapper, 0).find('.report-dismiss').trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('举报不存在', 'error')
    expect(getAdminReviews).not.toHaveBeenCalled()
  })

  it('连点两下只忽略一次', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    const btn = detailRowAt(wrapper, 0).find('.report-dismiss')
    await btn.trigger('click')
    await btn.trigger('click')
    await flushPromises()

    expect(dismissReport).toHaveBeenCalledTimes(1)
  })

  it('明细拉不到就把它收起来, 不留一块空白让人以为"没被举报"', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    getReviewReports.mockRejectedValue({ response: { data: { message: '评论不存在' } } })

    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)

    expect(wrapper.find('.report-detail-row').exists()).toBe(false)
    expect(toastSpy).toHaveBeenCalledWith('评论不存在', 'error')
  })

  it('重取列表不清已经展开的明细 —— 换一次序, 那些举报一条没变', async () => {
    getAdminReviews.mockResolvedValue(pageResult([review(1, { reportCount: 1, latestReason: 'SPAM' })], 1))
    const wrapper = await mountAt('/admin/reviews')
    await openDetail(wrapper)
    expect(getReviewReports).toHaveBeenCalledTimes(1)

    // 换排序 = 重取列表
    const likesSort = wrapper.findAll('.admin-sort-btn').find(b => b.text().includes('赞'))
    await likesSort.trigger('click')
    await flushPromises()

    expect(getAdminReviews).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.report-detail-row').exists()).toBe(true)
    expect(wrapper.find('.report-item').exists()).toBe(true)
    // 盒子留着, 所以不该为它再拉一次
    expect(getReviewReports).toHaveBeenCalledTimes(1)
  })
})
