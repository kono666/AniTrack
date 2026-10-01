import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminReviews: vi.fn(),
  adminDeleteReview: vi.fn(),
  adminRestoreReview: vi.fn(),
  // 举报那一组: 漏了 REVIEW_REPORT_REASONS 组件在 setup 里就 .map 一个 undefined,
  // 整页直接抛, 下面每条断言都会变成"找不到元素"
  getReviewReports: vi.fn(),
  dismissReport: vi.fn(),
  REVIEW_REPORT_REASONS: [
    { value: 'SPAM', label: '垃圾广告' },
    { value: 'ABUSE', label: '辱骂攻击' },
    { value: 'SPOILER', label: '剧透' },
    { value: 'OTHER', label: '其他' },
  ],
  // 常量也要在: 这是**整体替换**而不是部分替换, 漏掉的导出在导入侧是 undefined,
  // 而 `ref(undefined)` 不会报错 —— 它会安静地把 limit 变成"没有每页条数".
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

// 提示是模块作用域的一份全局状态(见 useToast 的说明), 断言渲染出来的 DOM
// 要先把 Toast.vue 挂上; 换成 spy 就只问"有没有提示、说的是什么"
const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))
vi.mock('../../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Reviews from '../Reviews.vue'
import { getAdminReviews, adminDeleteReview, adminRestoreReview } from '../../../api'

/**
 * 后台评论页: 一行的各个格子、删除, 以及三种空态.
 *
 * ⚠️ 这个文件的 mock **曾经比现实更宽** —— 改前它自己造了 `replyCount: 3`,
 * 而后端从来没发过这个键(`getAllReviews` 的 map 里没有). 于是用例恒绿, 而线上
 * 那一项**从不显示**. 这一轮后端补上了 `replyCount` 与 `animeTitle`, 所以下面
 * 每一个键都必须在 fixture 里出现, 而且要有一条断言**指着它**:
 * 缺一个键的 mock 会让那一格走 `undefined` 分支, 用例照样绿, 而真实场景里
 * 番剧名那一格是空的.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/admin/reviews', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

/** 服务端的形状: { data: { code, data: { list, total, page } } } */
function pageResult(list, total = list.length) {
  return { data: { code: 200, data: { list, total, page: 1 } } }
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
    likeCount: 2,
    replyCount: 0,
    createdAt: '2026-01-01T00:00:00',
    ...extra,
  }
}

async function mountReviews(list = [review(1)], total = list.length) {
  getAdminReviews.mockResolvedValue(pageResult(list, total))

  await router.push('/')
  await router.isReady()
  await router.push('/admin/reviews')

  const wrapper = mount(Reviews, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const rows = (w) => w.findAll('tbody tr')

describe('后台评论页: 一行里的各个格子', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('番剧名渲染成一条能点进详情页的链接', async () => {
    const wrapper = await mountReviews()

    const link = wrapper.find('.anime-link')
    expect(link.text()).toBe('番剧1')
    // 改前这里是**一个连链接都没有的裸数字**(「番剧ID: #101」), 管理员看不出
    // 这是哪部番, 也就判断不了这条评论该不该删
    expect(link.attributes('href')).toBe('/anime/101')
  })

  it('本地没缓存过那部番时退化成「番剧 #id」, 不编一个名字也不空着', async () => {
    // review.subject_id 与 anime 之间没有外键, 那部番可能还没进本地库 ——
    // 后端这时发的是 animeTitle: null, 而 id 是**唯一**能给出的信息
    const wrapper = await mountReviews([review(1, { animeTitle: null })])

    expect(wrapper.find('.anime-link').text()).toBe('番剧 #101')
    expect(wrapper.find('.anime-link').attributes('href')).toBe('/anime/101')
  })

  it('评分渲染成十颗星, 实心数等于评分', async () => {
    const wrapper = await mountReviews([review(1, { rating: 3 })])

    expect(wrapper.find('.stars-cell').text()).toBe('★★★☆☆☆☆☆☆☆')
  })

  it('评分超出 1..10 时也不炸(那是整页白屏, 不是一格难看)', async () => {
    // '☆'.repeat(10 - 11) 会抛 RangeError, 而那是渲染期抛的 ——
    // 一条脏数据会让整页白屏. 服务端本来约束在 1..10, 这一条防的是约束被绕过
    const wrapper = await mountReviews([review(1, { rating: 11 })])

    expect(wrapper.find('.stars-cell').text()).toBe('★★★★★★★★★★')
  })

  it('赞数与回复数都摆出来', async () => {
    const wrapper = await mountReviews([review(1, { likeCount: 2, replyCount: 3 })])

    const nums = wrapper.findAll('.num-cell').map((td) => td.text())
    expect(nums).toEqual(['2', '3'])
  })

  it('正文为空的行说「（无文字）」而不是留一格空白', async () => {
    const wrapper = await mountReviews([review(1, { content: null })])
    expect(wrapper.find('.content-cell').text()).toBe('（无文字）')
  })

  it('时间那一列把主键也印出来(排查时要报给后端的东西)', async () => {
    const wrapper = await mountReviews([review(7)])
    expect(wrapper.find('.id-cell').text()).toBe('#7')
  })
})

describe('后台评论页: 删除', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('确认文案里点名是谁的评论, 并把影响面(回复数)说清楚', async () => {
    const confirmSpy = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = await mountReviews([
      review(1, { username: 'bob', content: '这部剧的作画崩得很有诚意', replyCount: 3 }),
    ])

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    const text = confirmSpy.mock.calls[0][0]
    expect(text).toContain('bob')
    expect(text).toContain('这部剧的作画崩得很有诚意')
    // 回复数说的是**影响面**: 列表按评论走, 一条挂着三条回复的评论被移除之后,
    // 那些回复也跟着从用户侧看不见了 —— 而改前那句话里一个字都没提。
    // (V14 起措辞从"连带删除"改成了"跟着隐藏": 回复行一条都没动, 是软删.)
    expect(text).toContain('3 条回复')
    // 出口也要写清楚: 管理员点错了还能在这一行上恢复, 而确认框里不说,
    // 一次误点看起来就是不可挽回的
    expect(text).toContain('恢复')
  })

  it('没有回复时不说「其下 0 条回复也会跟着隐藏」', async () => {
    const confirmSpy = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = await mountReviews([review(1, { replyCount: 0 })])

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    expect(confirmSpy.mock.calls[0][0]).not.toContain('回复')
  })

  it('长正文在确认文案里截断(确认框不是用来看全文的)', async () => {
    const confirmSpy = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmSpy)
    const wrapper = await mountReviews([review(1, { content: '啊'.repeat(100) })])

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    expect(confirmSpy.mock.calls[0][0]).toContain('…')
    expect(confirmSpy.mock.calls[0][0].length).toBeLessThan(120)
  })

  it('点「取消」一个请求都不发, 那一行还在', async () => {
    vi.stubGlobal('confirm', () => false)
    const wrapper = await mountReviews()

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    expect(adminDeleteReview).not.toHaveBeenCalled()
    expect(rows(wrapper)).toHaveLength(1)
  })

  it('删除后**重取当前页**, 表格里是服务端说的那一份', async () => {
    // 删除接口回的是 ApiResponse<Void>, 不是列表那个分页信封 —— 别拿 pageResult 造它
    adminDeleteReview.mockResolvedValue({ data: { code: 200, data: null } })
    const wrapper = await mountReviews([review(1), review(2)])
    expect(rows(wrapper)).toHaveLength(2)

    // 第二次请求故意回一份**完全换过**的列表: 本地 filter 的实现会让表格里
    // 剩下 r2 且一次请求都不再发, 于是这条断言能把它和"重取"分开
    getAdminReviews.mockResolvedValue(pageResult([review(3)]))

    await wrapper.findAll('.delete-btn')[0].trigger('click')
    await flushPromises()

    expect(adminDeleteReview).toHaveBeenCalledWith(1)
    expect(getAdminReviews).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('u3')
    expect(wrapper.text()).not.toContain('u1')
    expect(wrapper.text()).not.toContain('u2')
  })

  it('删除失败: 那一行留着, 而且给出服务端说的原因', async () => {
    adminDeleteReview.mockRejectedValue({ response: { data: { message: '评论不存在' } } })
    const wrapper = await mountReviews()

    await wrapper.find('.delete-btn').trigger('click')
    await flushPromises()

    // 行还在 —— 否则管理员会以为删成功了, 刷新一下它又回来
    expect(rows(wrapper)).toHaveLength(1)
    // 说的服务端给的原因, 不是一句笼统的「删除失败」
    expect(toastSpy).toHaveBeenCalledWith('评论不存在', 'error')
  })
})

/**
 * 已移除的那一行(V14)。
 *
 * <p>软删给这一页添了**第二个状态**, 而它的难点全在"两副面孔必须对得上": 列表行上
 * 该显示什么、能点什么, 由服务端发的 `deletedAt` 说了算。写错的话, 管理员会对一条
 * 已经移除的评论再点一次「移除」—— 后端回 400「该评论已被移除」, 而他看不懂这个
 * 答复里说的"已移除"是什么意思: 界面上那一行看起来和别的行一模一样。
 */
describe('后台评论页: 已移除的行', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('confirm', () => true)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('带 deletedAt 的行标「已移除」, 而且给的是「恢复」而不是「移除」', async () => {
    const wrapper = await mountReviews([review(1, { deletedAt: '2026-01-01T00:00:00' })])

    expect(wrapper.find('.removed-badge').text()).toBe('已移除')
    expect(wrapper.find('.restore-btn').text()).toBe('恢复')
    // 这一格上只有一个动作, 而且是哪一个**由数据说了算**
    expect(wrapper.find('.delete-btn').exists()).toBe(false)
  })

  it('没带 deletedAt 的行照旧只有「移除」, 不该凭空多出一个「恢复」', async () => {
    const wrapper = await mountReviews([review(1)])

    expect(wrapper.find('.delete-btn').text()).toBe('移除')
    expect(wrapper.find('.restore-btn').exists()).toBe(false)
    expect(wrapper.find('.removed-badge').exists()).toBe(false)
  })

  it('点「恢复」会调恢复接口, 并重取当前页', async () => {
    adminRestoreReview.mockResolvedValue({ data: { code: 200, data: null } })
    const wrapper = await mountReviews([review(1, { deletedAt: '2026-01-01T00:00:00' })])
    expect(getAdminReviews).toHaveBeenCalledTimes(1)

    // 重取这一句与删除那条同一个理由, 而且这里还多一层: 恢复之后这一行会不会**消失**
    // (比如正筛着「只看被举报」而它从此回到队列里), 那句话只有服务端说了算
    getAdminReviews.mockResolvedValue(pageResult([review(2)]))

    await wrapper.find('.restore-btn').trigger('click')
    await flushPromises()

    expect(adminRestoreReview).toHaveBeenCalledWith(1)
    expect(getAdminReviews).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('u2')
  })

  it('恢复的确认文案也要点名是谁的评论(点错一行同样要看得出来)', async () => {
    const confirmSpy = vi.fn(() => true)
    vi.stubGlobal('confirm', confirmSpy)
    adminRestoreReview.mockResolvedValue({ data: { code: 200, data: null } })
    const wrapper = await mountReviews([
      review(1, { username: 'bob', content: '这条被误删了', deletedAt: '2026-01-01T00:00:00' }),
    ])

    await wrapper.find('.restore-btn').trigger('click')
    await flushPromises()

    const text = confirmSpy.mock.calls[0][0]
    expect(text).toContain('bob')
    expect(text).toContain('这条被误删了')
    expect(text).toContain('恢复')
  })

  it('点「取消」一个请求都不发', async () => {
    vi.stubGlobal('confirm', () => false)
    const wrapper = await mountReviews([review(1, { deletedAt: '2026-01-01T00:00:00' })])

    await wrapper.find('.restore-btn').trigger('click')
    await flushPromises()

    expect(adminRestoreReview).not.toHaveBeenCalled()
    expect(rows(wrapper)).toHaveLength(1)
  })

  it('恢复失败: 那一行留着, 而且给出服务端说的原因', async () => {
    adminRestoreReview.mockRejectedValue({ response: { data: { message: '该评论未被移除' } } })
    const wrapper = await mountReviews([review(1, { deletedAt: '2026-01-01T00:00:00' })])

    await wrapper.find('.restore-btn').trigger('click')
    await flushPromises()

    expect(rows(wrapper)).toHaveLength(1)
    expect(toastSpy).toHaveBeenCalledWith('该评论未被移除', 'error')
  })
})

describe('后台评论页: 三种空态', () => {
  beforeEach(() => vi.clearAllMocks())

  it('干净的零说「暂无评论」, 且不给清除筛选', async () => {
    const wrapper = await mountReviews([], 0)

    expect(wrapper.text()).toContain('暂无评论')
    expect(wrapper.text()).not.toContain('没有匹配的评论')
    expect(wrapper.findAll('button').some((b) => b.text() === '清除筛选')).toBe(false)
  })

  it('筛完没结果说「没有匹配的」, 并给一个清条件的出口', async () => {
    const wrapper = await mountReviews([], 0)
    // 先加载完再改条件: 深链 ?q= 也行, 但那会连"从 URL 读回来"一起测了
    await router.replace({ query: { q: 'zzz' } })
    await flushPromises()

    expect(wrapper.text()).toContain('没有匹配的评论')
    expect(wrapper.text()).not.toContain('暂无评论')
  })

  it('翻到越界的那一页: 说「这一页没有评论了」并给一条回到第 1 页的路', async () => {
    // 总数还在但这一页没有行 —— 服务端对越界页就是报真实的 total + 空 list,
    // 因为前端要靠 total 算出还有几页. 这时说「暂无评论」是错的
    const wrapper = await mountReviews([], 250)

    expect(wrapper.text()).toContain('这一页没有评论了')
    expect(wrapper.text()).not.toContain('暂无评论')
    expect(wrapper.findAll('button').some((b) => b.text() === '回到第 1 页')).toBe(true)
  })
})
