import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminReviews: vi.fn(),
  adminDeleteReview: vi.fn(),
  // 常量也必须在: 这是整体替换, 漏掉的导出在导入侧是 undefined, 而 ref(undefined)
  // 不报错 —— limit 会安静地变成"没有每页条数", 分页页数跟着变成 NaN
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

import Reviews from '../Reviews.vue'
import { getAdminReviews } from '../../../api'

/**
 * 评论管理的 URL 同步、筛选/排序/分页的交互, 以及竞态.
 *
 * ⚠️ 与 `Reviews.test.js`、`adminLoadError.test.js` 分开三个文件: 这一组里有好几条要
 * 数"发了几次请求"、还要用 mockReturnValueOnce 排定谁先回来 —— 留在同一个文件里的话,
 * 前一个用例遗留的组件实例的 watcher 也跟着路由动、把排好的返回值吃掉,
 * 结果取决于用例的执行顺序. (与 `Users.query.test.js` 分成三份是同一条理由.)
 */

/**
 * 每个用例一份**新的** router.
 *
 * 共用一个 router 的话, 每一条用例的 push 都往同一条历史栈上加层 —— 于是「后退一次」
 * 退到的是上一条用例留下的地址, 断言结果取决于用例的执行顺序.
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

let router = null

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
    likeCount: 0,
    replyCount: 0,
    createdAt: '2026-01-01T00:00:00',
    ...extra,
  }
}

/** 手写的 deferred: 这一组要精确控制"谁先回来", 不用假定时器 */
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** 挂载过的组件都记在这儿, 用例结束统一卸载(理由同 Users.query.test.js) */
let mounted = []

async function mountAt(fullPath) {
  router = makeRouter()
  // 先落在 '/' 再走一步: 历史栈里得先有"上一页", 后退那一条才有地方退
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

// 按语义找控件, 不按下标 —— 以后往工具栏里插一个下拉, 下标式的断言会静默指错元素
const selectByLabel = (w, label) =>
  w.findAll('select').find((s) => s.attributes('aria-label') === label)
const searchBox = (w) => w.find('input[aria-label="搜索评论正文或用户名"]')
const sortButton = (w, label) =>
  w.findAll('.admin-sort-btn').find((b) => b.text().includes(label))
const pageButton = (w, label) => w.findAll('.pg-btn').find((b) => b.text() === label)
const thAriaSort = (w, label) =>
  w.findAll('th').find((th) => th.text().includes(label))?.attributes('aria-sort')

/** 最后一次请求发出去的参数 */
const lastParams = () => getAdminReviews.mock.calls.at(-1)[0]

describe('评论管理: URL 同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminReviews.mockResolvedValue(pageResult([review(1)], 1))
  })

  it('六个参数全从 URL 读回来, 原样发给服务端', async () => {
    await mountAt('/admin/reviews?q=ab&rating=low&sort=likes&order=asc&page=2&limit=50')

    // order=asc 与 likes 的自然首向(desc)不同, 所以它是**必须**发出去的那个
    expect(getAdminReviews).toHaveBeenCalledWith({
      keyword: 'ab', rating: 'low',
      sort: 'likes', order: 'asc', page: 2, limit: 50,
    })
  })

  it('默认值不写进 URL(?q=&page=1 是噪音)', async () => {
    await mountAt('/admin/reviews')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('默认排序列是 id, 而且默认序不写进 URL', async () => {
    const wrapper = await mountAt('/admin/reviews')

    expect(lastParams()).toEqual({
      keyword: undefined, rating: undefined,
      sort: undefined, order: undefined, page: 1, limit: 20,
    })
    // 「时间」那一列的排的是主键(理由见 Reviews.vue 里 SORT_ID 那段), 默认倒序
    expect(thAriaSort(wrapper, '时间')).toBe('descending')
  })

  it('likes 的倒序是自然首向, 所以只写 sort 不写 order', async () => {
    const wrapper = await mountAt('/admin/reviews?sort=likes')

    expect(lastParams()).toEqual({
      keyword: undefined, rating: undefined,
      sort: 'likes', order: undefined, page: 1, limit: 20,
    })
    // 表头要说出同一件事, 否则用户看到"按赞降序"而结果是升的
    expect(thAriaSort(wrapper, '赞')).toBe('descending')
  })

  it('认不出来的 sort / order / limit / rating 落回默认, 不把页面变成错误态', async () => {
    const wrapper = await mountAt('/admin/reviews?sort=bogus&order=sideways&limit=7&rating=NOPE')

    expect(lastParams()).toEqual({
      keyword: undefined, rating: undefined,
      sort: undefined, order: undefined, page: 1, limit: 20,
    })
    // limit 走白名单而不是 parseInt: `?limit=7` 若原样发给后端, 前端按 20 算页数、
    // 后端按 7 条给, 两边对 totalPages 各说各话
    expect(selectByLabel(wrapper, '每页条数').element.value).toBe('20')
    // 认不出来的 sort 落到 id 之后, order 的自然首向**跟着解析后的列**走 ——
    // 不是跟着 URL 上那个原始值
    expect(thAriaSort(wrapper, '时间')).toBe('descending')
  })
})

describe('评论管理: 筛选 / 排序 / 分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminReviews.mockResolvedValue(pageResult([review(1)], 250))
  })

  it('连打三个字符只发一次请求', async () => {
    const wrapper = await mountAt('/admin/reviews')
    getAdminReviews.mockClear()

    // 假定时器从"开始打字"那一刻起才需要 —— 挂载那一步要让路由正常结算
    vi.useFakeTimers()
    const box = searchBox(wrapper)
    await box.setValue('a')
    await box.setValue('ab')
    await box.setValue('abc')

    // 静默期内一次都不发. 少了防抖, 这里已经是 3 次
    expect(getAdminReviews).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300)
    vi.useRealTimers()
    await flushPromises()

    expect(getAdminReviews).toHaveBeenCalledTimes(1)
    expect(lastParams().keyword).toBe('abc')
    expect(router.currentRoute.value.query.q).toBe('abc')
  })

  it('换评分档位回到第 1 页', async () => {
    const wrapper = await mountAt('/admin/reviews?page=3')
    getAdminReviews.mockClear()

    await selectByLabel(wrapper, '按评分档位筛选').setValue('low')
    await flushPromises()

    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(router.currentRoute.value.query.rating).toBe('low')
    expect(lastParams()).toEqual({
      keyword: undefined, rating: 'low',
      sort: undefined, order: undefined, page: 1, limit: 20,
    })
  })

  it('换每页条数回到第 1 页, 页数按新条数算', async () => {
    const wrapper = await mountAt('/admin/reviews')
    // 250 条 / 20 = 13 页
    expect(pageButton(wrapper, '13')).toBeTruthy()

    await selectByLabel(wrapper, '每页条数').setValue('50')
    await flushPromises()

    // 不重置页码是经典 bug: 250 条时 20/页的第 4 页在 100/页下是空的
    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(router.currentRoute.value.query.limit).toBe('50')
    expect(lastParams().limit).toBe(50)
    expect(lastParams().page).toBe(1)
    expect(pageButton(wrapper, '5')).toBeTruthy()
    expect(pageButton(wrapper, '13')).toBeFalsy()
  })

  it('点另一列表头: 用那一列的自然首向(三列都是倒序)', async () => {
    const wrapper = await mountAt('/admin/reviews')
    expect(thAriaSort(wrapper, '时间')).toBe('descending')

    await sortButton(wrapper, '赞').trigger('click')
    await flushPromises()

    expect(lastParams().sort).toBe('likes')
    // 自然首向等于默认, 所以 order 不该写进 URL、也不该发出去
    expect(lastParams().order).toBeUndefined()
    expect(router.currentRoute.value.query).toEqual({ sort: 'likes' })
    expect(thAriaSort(wrapper, '赞')).toBe('descending')
    expect(thAriaSort(wrapper, '时间')).toBe('none')
  })

  it('点同一列表头翻向, 并把 order 写进 URL', async () => {
    const wrapper = await mountAt('/admin/reviews?sort=replies')

    await sortButton(wrapper, '回复').trigger('click')
    await flushPromises()

    expect(lastParams().order).toBe('asc')
    expect(thAriaSort(wrapper, '回复')).toBe('ascending')
    expect(router.currentRoute.value.query).toMatchObject({ sort: 'replies', order: 'asc' })

    await sortButton(wrapper, '回复').trigger('click')
    await flushPromises()

    // 翻回自然首向之后 order 又该从 URL 上消失 —— 留在上面会让分享出去的链接
    // 看起来"特意选了正序", 而它本来就是默认
    expect(lastParams().order).toBeUndefined()
    expect(router.currentRoute.value.query.order).toBeUndefined()
  })

  it('清除筛选把关键词与档位一起清掉, 但不动排序', async () => {
    const wrapper = await mountAt('/admin/reviews?q=ab&rating=low&sort=likes')
    getAdminReviews.mockClear()

    await wrapper.findAll('button').find((b) => b.text() === '清除筛选').trigger('click')
    await flushPromises()

    expect(lastParams()).toEqual({
      keyword: undefined, rating: undefined,
      sort: 'likes', order: undefined, page: 1, limit: 20,
    })
    expect(router.currentRoute.value.query).toEqual({ sort: 'likes' })
  })

  it('改条件不往历史里堆层', async () => {
    const wrapper = await mountAt('/admin/reviews')
    await selectByLabel(wrapper, '按评分档位筛选').setValue('low')
    await flushPromises()
    await selectByLabel(wrapper, '按评分档位筛选').setValue('high')
    await flushPromises()

    router.back()
    await flushPromises()

    // 用 push 的话这里是"退回上一组条件"(还在 /admin/reviews 上), 要从筛了三层退回
    // 没筛就得按好几次后退
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('URL 变了(后退/前进)会按新条件重新取数', async () => {
    const wrapper = await mountAt('/admin/reviews')
    expect(wrapper.find('.pg-btn.active').text()).toBe('1')
    getAdminReviews.mockClear()

    await router.replace({ query: { page: '3' } })
    await flushPromises()

    expect(getAdminReviews).toHaveBeenCalledTimes(1)
    expect(lastParams().page).toBe(3)
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })
})

describe('评论管理: 竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminReviews.mockResolvedValue(pageResult([review(1)], 250))
  })

  it('先发的那次后到, 不能盖掉后发的', async () => {
    const wrapper = await mountAt('/admin/reviews')

    const slow = deferred()
    const fast = deferred()
    getAdminReviews.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([review(33)], 250))
    await flushPromises()
    expect(wrapper.text()).toContain('u33')

    slow.resolve(pageResult([review(22)], 250))
    await flushPromises()

    expect(wrapper.text()).toContain('u33')
    expect(wrapper.text()).not.toContain('u22')
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('过期那次的失败也不能写进界面', async () => {
    const wrapper = await mountAt('/admin/reviews')

    const slow = deferred()
    const fast = deferred()
    getAdminReviews.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([review(33)], 250))
    await flushPromises()

    slow.reject(new Error('boom'))
    await flushPromises()

    // 没有守卫的话这次失败会把列表清空并盖上一条报错 —— 界面上留下的是一条属于
    // 上一个请求的失败提示, 而那个请求早就没人关心了
    expect(wrapper.text()).toContain('u33')
    expect(wrapper.text()).not.toContain('失败')
  })
})
