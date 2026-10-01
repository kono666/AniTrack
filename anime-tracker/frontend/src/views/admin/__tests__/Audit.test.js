import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminActions: vi.fn(),
  // 常量也必须在: 这是整体替换, 漏掉的导出在导入侧是 undefined, 而 ref(undefined)
  // 不报错 —— limit 会安静地变成"没有每页条数", 分页页数跟着变成 NaN
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

import Audit from '../Audit.vue'
import { getAdminActions } from '../../../api'

/**
 * 操作日志页: URL 同步、筛选/分页、三态空态、竞态.
 *
 * 与 Users 那一组分开文件, 理由相同: 这里有好几条要数"发了几次请求", 而每个用例
 * 挂载后都不卸载的话, 前一个实例的 watcher 还跟着路由动、把排好的返回值吃掉,
 * 结果取决于用例的执行顺序.
 */

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/actions', component: Audit },
    ],
  })
}

let router = null

/** 服务端的形状: { data: { code, data: { list, total, page } } } */
function pageResult(list, total = list.length, page = 1) {
  return { data: { code: 200, data: { list, total, page } } }
}

function row(id, extra = {}) {
  return {
    id,
    action: 'USER_BAN',
    actorName: 'admin',
    targetType: 'USER',
    targetId: 100 + id,
    detail: `禁用用户 u${id}`,
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

let mounted = []

async function mountAt(fullPath) {
  router = makeRouter()
  // 先落在 '/' 再走一步: 历史栈里得先有"上一页", 后退那一条才有地方退
  await router.push('/')
  await router.isReady()
  await router.push(fullPath)
  const wrapper = mount(Audit, { global: { plugins: [router] } })
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
const pageButton = (w, label) => w.findAll('.pg-btn').find((b) => b.text() === label)
const buttonWith = (w, text) => w.findAll('button').find((b) => b.text() === text)

/** 最后一次请求发出去的参数 */
const lastParams = () => getAdminActions.mock.calls.at(-1)[0]

describe('操作日志: URL 同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminActions.mockResolvedValue(pageResult([row(1)], 1))
  })

  it('三个参数全从 URL 读回来, 原样发给服务端', async () => {
    await mountAt('/admin/actions?action=USER_ROLE&page=2&limit=50')

    expect(getAdminActions).toHaveBeenCalledWith({
      action: 'USER_ROLE', page: 2, limit: 50,
    })
  })

  it('默认值不写进 URL(?action=&page=1 是噪音)', async () => {
    await mountAt('/admin/actions')

    expect(router.currentRoute.value.query).toEqual({})
    // "没筛"在请求上表现为**参数不存在**, 与后端 `:action IS NULL` 那条一一对应.
    // 发空串也等价, 但那样地址栏之外还多一处需要对齐的写法
    expect(lastParams()).toEqual({ action: undefined, page: 1, limit: 20 })
  })

  it('认不出来的 action / limit 落回默认, 不把页面变成错误态', async () => {
    const wrapper = await mountAt('/admin/actions?action=BOGUS&limit=7')

    expect(lastParams()).toEqual({ action: undefined, page: 1, limit: 20 })
    // 白名单不是洁癖: action 若原样留下, 下拉里没有任何 option 对得上,
    // <select> 会变成 selectedIndex=-1 的空白框 —— 一个既说不清在筛什么、
    // 又清不掉的控件
    expect(selectByLabel(wrapper, '按操作类型筛选').element.value).toBe('')
    expect(selectByLabel(wrapper, '每页条数').element.value).toBe('20')
  })
})

describe('操作日志: 筛选 / 分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminActions.mockResolvedValue(pageResult([row(1)], 250))
  })

  it('换操作类型回到第 1 页, 并写进 URL', async () => {
    const wrapper = await mountAt('/admin/actions?page=3')
    getAdminActions.mockClear()

    await selectByLabel(wrapper, '按操作类型筛选').setValue('REVIEW_DELETE')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ action: 'REVIEW_DELETE' })
    expect(lastParams()).toEqual({ action: 'REVIEW_DELETE', page: 1, limit: 20 })
  })

  it('换每页条数回到第 1 页, 页数按新条数算', async () => {
    const wrapper = await mountAt('/admin/actions')
    // 250 条 / 20 = 13 页
    expect(pageButton(wrapper, '13')).toBeTruthy()

    await selectByLabel(wrapper, '每页条数').setValue('50')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ limit: '50' })
    expect(lastParams().limit).toBe(50)
    expect(lastParams().page).toBe(1)
    expect(pageButton(wrapper, '5')).toBeTruthy()
    expect(pageButton(wrapper, '13')).toBeFalsy()
  })

  it('清除筛选把 action 从 URL 上拿掉', async () => {
    const wrapper = await mountAt('/admin/actions?action=USER_BAN')
    getAdminActions.mockClear()

    await buttonWith(wrapper, '清除筛选').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(lastParams().action).toBeUndefined()
  })

  it('改条件不往历史里堆层', async () => {
    const wrapper = await mountAt('/admin/actions')
    await selectByLabel(wrapper, '按操作类型筛选').setValue('USER_BAN')
    await flushPromises()
    await selectByLabel(wrapper, '按操作类型筛选').setValue('REVIEW_DELETE')
    await flushPromises()

    router.back()
    await flushPromises()

    // 用 push 的话这里会退回上一组条件(还在 /admin/actions 上), 要从筛了三层
    // 退回没筛就得按好几次后退
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('URL 变了(后退/前进)会按新条件重新取数', async () => {
    const wrapper = await mountAt('/admin/actions?page=1')
    expect(wrapper.find('.pg-btn.active').text()).toBe('1')
    getAdminActions.mockClear()

    await router.replace({ query: { page: '3' } })
    await flushPromises()

    expect(getAdminActions).toHaveBeenCalledTimes(1)
    expect(lastParams().page).toBe(3)
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('点翻页按钮发出的页号与 URL 一致', async () => {
    const wrapper = await mountAt('/admin/actions')
    getAdminActions.mockClear()

    await pageButton(wrapper, '2').trigger('click')
    await flushPromises()

    expect(lastParams().page).toBe(2)
    expect(router.currentRoute.value.query.page).toBe('2')
  })
})

describe('操作日志: 列内容', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('五个字段各就各位, action 显示成人话', async () => {
    getAdminActions.mockResolvedValue(pageResult([
      row(1, { action: 'REVIEW_DELETE', targetType: 'REVIEW', targetId: 77, detail: '删了条评论' }),
    ], 1))
    const wrapper = await mountAt('/admin/actions')

    const cells = wrapper.findAll('tbody tr td').map(td => td.text())
    expect(cells[1]).toBe('删除评论')
    expect(cells[2]).toBe('admin')
    expect(cells[3]).toBe('评论 #77')
    expect(cells[4]).toBe('删了条评论')
  })

  it('没见过的 action 显示原样的码, 不是一片空白', async () => {
    // 后端加了第六个动作而前端还没跟上时, 一行操作记录**看上去像没写操作**
    // 才是最糟的失败方式 —— 宁可显示 USER_PASSWORD_RESET 让人去查
    getAdminActions.mockResolvedValue(pageResult([row(1, { action: 'USER_PASSWORD_RESET' })], 1))
    const wrapper = await mountAt('/admin/actions')

    expect(wrapper.text()).toContain('USER_PASSWORD_RESET')
  })

  it('detail 为空时说「（无详情）」而不是留一个空格子', async () => {
    getAdminActions.mockResolvedValue(pageResult([row(1, { detail: null })], 1))
    const wrapper = await mountAt('/admin/actions')

    expect(wrapper.text()).toContain('（无详情）')
  })

  it('加载失败说清楚是失败, 不说「暂无操作记录」', async () => {
    // 这一页的全部意义就是"到底有没有发生过那件事" —— 把失败显示成一份空账,
    // 是这里唯一不能犯的错. 文案由 loadErrorMessage 统一给(带状态码便于对日志)
    getAdminActions.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountAt('/admin/actions')

    expect(wrapper.text()).toContain('加载操作日志失败：服务端返回 500')
    expect(wrapper.text()).not.toContain('暂无操作记录')
    // 失败时工具栏仍在, 否则条件被锁住, 只能刷新页面才能换个条件再看
    expect(selectByLabel(wrapper, '按操作类型筛选')).toBeTruthy()
  })
})

describe('操作日志: 空态与竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('干净的零说「暂无操作记录」, 且不给清除筛选', async () => {
    getAdminActions.mockResolvedValue(pageResult([], 0))
    const wrapper = await mountAt('/admin/actions')

    expect(wrapper.text()).toContain('暂无操作记录')
    expect(wrapper.text()).not.toContain('没有这一类操作')
    expect(buttonWith(wrapper, '清除筛选')).toBeFalsy()
  })

  it('筛完没结果说「没有这一类操作」, 并给一个清条件的出口', async () => {
    getAdminActions.mockResolvedValue(pageResult([], 0))
    const wrapper = await mountAt('/admin/actions?action=USER_BAN')

    expect(wrapper.text()).toContain('没有这一类操作')
    expect(wrapper.text()).not.toContain('暂无操作记录')

    await buttonWith(wrapper, '清除筛选').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.action).toBeUndefined()
  })

  it('翻到越界的那一页: 说「这一页没有记录了」并给一条回到第 1 页的路', async () => {
    // 总数还在(250)但这一页没有行 —— 说「暂无操作记录」在这里是**假话**,
    // 而且分页条上该点哪一页也看不出来, 所以这个按钮是唯一的出路
    getAdminActions.mockResolvedValue(pageResult([], 250, 99))
    const wrapper = await mountAt('/admin/actions?page=99')

    expect(wrapper.text()).toContain('这一页没有记录了')
    expect(wrapper.text()).not.toContain('暂无操作记录')

    getAdminActions.mockClear()
    await buttonWith(wrapper, '回到第 1 页').trigger('click')
    await flushPromises()

    expect(lastParams().page).toBe(1)
  })

  it('先发的那次后到, 不能盖掉后发的', async () => {
    getAdminActions.mockResolvedValue(pageResult([row(1)], 250))
    const wrapper = await mountAt('/admin/actions')

    const slow = deferred()
    const fast = deferred()
    getAdminActions.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([row(33, { detail: '较新的那次' })], 250, 3))
    await flushPromises()
    expect(wrapper.text()).toContain('较新的那次')

    slow.resolve(pageResult([row(22, { detail: '较旧的那次' })], 250, 2))
    await flushPromises()

    expect(wrapper.text()).toContain('较新的那次')
    expect(wrapper.text()).not.toContain('较旧的那次')
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('过期那次的失败也不能写进界面', async () => {
    getAdminActions.mockResolvedValue(pageResult([row(1)], 250))
    const wrapper = await mountAt('/admin/actions')

    const slow = deferred()
    const fast = deferred()
    getAdminActions.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([row(33, { detail: '较新的那次' })], 250, 3))
    await flushPromises()

    slow.reject(new Error('boom'))
    await flushPromises()

    expect(wrapper.text()).toContain('较新的那次')
    expect(wrapper.text()).not.toContain('失败')
  })
})
