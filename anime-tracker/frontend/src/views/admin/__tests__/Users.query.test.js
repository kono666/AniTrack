import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../../api', () => ({
  getAdminUsers: vi.fn(),
  toggleUserStatus: vi.fn(),
  setUserRole: vi.fn(),
  unlockUser: vi.fn(),
  // 常量也必须在: 这是整体替换, 漏掉的导出在导入侧是 undefined, 而 ref(undefined)
  // 不报错 —— limit 会安静地变成"没有每页条数", 分页页数跟着变成 NaN
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

import Users from '../Users.vue'
import { getAdminUsers } from '../../../api'

/**
 * 用户管理的 URL 同步、筛选/排序/分页的交互, 以及竞态.
 *
 * ⚠️ 与 adminLoadError.test.js 分开一个文件: 那边每个用例挂载后都不卸载, 而这一组
 * 里有好几条要数"发了几次请求"、还要用 mockReturnValueOnce 排定谁先回来 ——
 * 留在同一个文件里的话, 前一个用例遗留的组件实例的 watcher 也跟着路由动、
 * 把排好的返回值吃掉, 结果取决于用例的执行顺序.
 */

/**
 * 每个用例一份**新的** router.
 *
 * 共用一个 router 的话, 每一条用例的 push 都往同一条历史栈上加层 —— 于是「后退
 * 一次」退到的是上一条用例留下的地址, 断言结果取决于用例的执行顺序. 上面的
 * 文件头注释说的是同一件事的另一半(组件实例也要卸载).
 */
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/admin/users', component: Users },
    ],
  })
}

let router = null

/** 服务端的形状: { data: { code, data: { list, total, page } } } */
function pageResult(list, total = list.length) {
  return { data: { code: 200, data: { list, total, page: 1 } } }
}

function user(id, extra = {}) {
  return {
    id,
    username: `u${id}`,
    email: `u${id}@example.com`,
    role: 'USER',
    status: 'ACTIVE',
    createdAt: '2026-01-01T00:00:00',
    locked: false,
    lockedUntil: null,
    ...extra,
  }
}

/** 手写的 deferred: 这一组要精确控制"谁先回来", 不用假定时器 */
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/**
 * 挂载过的组件都记在这儿, 用例结束统一卸载.
 *
 * 不这么做的话, 上一个用例遗留的实例仍然挂着 watch —— 它照样跟着路由动、照样发请求,
 * 于是"点一下发了几次请求"这种断言会随用例的执行顺序飘.
 */
let mounted = []

async function mountAt(fullPath) {
  router = makeRouter()
  // 先落在 '/' 再走一步: 历史栈里得先有"上一页", 后退那一条才有地方退
  await router.push('/')
  await router.isReady()
  await router.push(fullPath)
  const wrapper = mount(Users, { global: { plugins: [router] } })
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
const searchBox = (w) => w.find('input[aria-label="搜索用户名或邮箱"]')
const sortButton = (w, label) =>
  w.findAll('.admin-sort-btn').find((b) => b.text().includes(label))
const pageButton = (w, label) => w.findAll('.pg-btn').find((b) => b.text() === label)
const thAriaSort = (w, label) =>
  w.findAll('th').find((th) => th.text().includes(label))?.attributes('aria-sort')

/** 最后一次请求发出去的参数 */
const lastParams = () => getAdminUsers.mock.calls.at(-1)[0]

describe('用户管理: URL 同步', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminUsers.mockResolvedValue(pageResult([user(1)], 1))
  })

  it('七个参数全从 URL 读回来, 原样发给服务端', async () => {
    await mountAt('/admin/users?q=ab&role=ADMIN&status=LOCKED&sort=username&order=desc&page=2&limit=50')

    // order=desc 与 username 的自然首向(asc)不同, 所以它是**必须**发出去的那个
    expect(getAdminUsers).toHaveBeenCalledWith({
      keyword: 'ab', role: 'ADMIN', status: 'LOCKED',
      sort: 'username', order: 'desc', page: 2, limit: 50,
    })
  })

  it('默认值不写进 URL(?q=&page=1 是噪音)', async () => {
    await mountAt('/admin/users')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('username 的正序是默认, 只写 sort 不写 order', async () => {
    const wrapper = await mountAt('/admin/users?sort=username')

    // order 省略 —— 后端对 username 缺 order 就是 asc, 两边必须用同一条规则
    expect(lastParams()).toEqual({
      keyword: undefined, role: undefined, status: undefined,
      sort: 'username', order: undefined, page: 1, limit: 20,
    })
    // 表头要说出同一件事, 否则用户看到"按用户名升序"而结果是倒的
    expect(thAriaSort(wrapper, '用户名')).toBe('ascending')
  })

  it('认不出来的 sort / limit / role 落回默认, 不把页面变成错误态', async () => {
    const wrapper = await mountAt('/admin/users?sort=bogus&limit=7&role=NOPE')

    expect(lastParams()).toEqual({
      keyword: undefined, role: undefined, status: undefined,
      sort: undefined, order: undefined, page: 1, limit: 20,
    })
    // limit 走白名单而不是 parseInt: `?limit=7` 若原样发给后端, 前端按 20 算页数、
    // 后端按 7 条给, 两边对 totalPages 各说各话
    expect(selectByLabel(wrapper, '每页条数').element.value).toBe('20')
  })
})

describe('用户管理: 筛选 / 排序 / 分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAdminUsers.mockResolvedValue(pageResult([user(1)], 250))
  })

  it('连打三个字符只发一次请求', async () => {
    const wrapper = await mountAt('/admin/users')
    getAdminUsers.mockClear()

    // 假定时器从"开始打字"那一刻起才需要 —— 挂载那一步要让路由正常结算
    vi.useFakeTimers()
    const box = searchBox(wrapper)
    await box.setValue('a')
    await box.setValue('ab')
    await box.setValue('abc')

    // 静默期内一次都不发. 少了防抖, 这里已经是 3 次
    expect(getAdminUsers).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(300)
    vi.useRealTimers()
    await flushPromises()

    expect(getAdminUsers).toHaveBeenCalledTimes(1)
    expect(lastParams().keyword).toBe('abc')
    expect(router.currentRoute.value.query.q).toBe('abc')
  })

  it('换角色筛选回到第 1 页', async () => {
    const wrapper = await mountAt('/admin/users?page=3')
    getAdminUsers.mockClear()

    await selectByLabel(wrapper, '按角色筛选').setValue('ADMIN')
    await flushPromises()

    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(router.currentRoute.value.query.role).toBe('ADMIN')
    expect(lastParams()).toEqual({
      keyword: undefined, role: 'ADMIN', status: undefined,
      sort: undefined, order: undefined, page: 1, limit: 20,
    })
  })

  it('换每页条数回到第 1 页, 页数按新条数算', async () => {
    const wrapper = await mountAt('/admin/users')
    // 250 条 / 20 = 13 页
    expect(pageButton(wrapper, '13')).toBeTruthy()

    await selectByLabel(wrapper, '每页条数').setValue('50')
    await flushPromises()

    // 不重置页码是经典 bug: 250 条时 20/页的第 4 页在 100/页下是空的.
    // 这一条同时钉住"页数用的是同一个 limit"—— 分开写的话这里会算出 13 页
    expect(router.currentRoute.value.query.page).toBeUndefined()
    expect(router.currentRoute.value.query.limit).toBe('50')
    expect(lastParams().limit).toBe(50)
    expect(lastParams().page).toBe(1)
    expect(pageButton(wrapper, '5')).toBeTruthy()
    expect(pageButton(wrapper, '13')).toBeFalsy()
  })

  it('点另一列表头: 用那一列的自然首向(时间先给最新在前)', async () => {
    const wrapper = await mountAt('/admin/users')
    expect(thAriaSort(wrapper, '注册时间')).toBe('descending')

    await sortButton(wrapper, '用户名').trigger('click')
    await flushPromises()

    expect(lastParams().sort).toBe('username')
    expect(lastParams().order).toBeUndefined()
    expect(thAriaSort(wrapper, '用户名')).toBe('ascending')
    expect(thAriaSort(wrapper, '注册时间')).toBe('none')
    expect(router.currentRoute.value.query).toMatchObject({ sort: 'username' })
  })

  it('点同一列表头翻向, 并把 order 写进 URL', async () => {
    const wrapper = await mountAt('/admin/users?sort=username')

    await sortButton(wrapper, '用户名').trigger('click')
    await flushPromises()

    expect(lastParams().order).toBe('desc')
    expect(thAriaSort(wrapper, '用户名')).toBe('descending')
    expect(router.currentRoute.value.query).toMatchObject({ sort: 'username', order: 'desc' })

    await sortButton(wrapper, '用户名').trigger('click')
    await flushPromises()

    // 翻回自然首向之后 order 又该从 URL 上消失 —— 留在上面会让分享出去的链接
    // 看起来"特意选了正序", 而它本来就是默认
    expect(lastParams().order).toBeUndefined()
    expect(router.currentRoute.value.query.order).toBeUndefined()
  })

  it('改条件不往历史里堆层', async () => {
    const wrapper = await mountAt('/admin/users')
    await selectByLabel(wrapper, '按角色筛选').setValue('ADMIN')
    await flushPromises()
    await selectByLabel(wrapper, '按状态筛选').setValue('LOCKED')
    await flushPromises()

    router.back()
    await flushPromises()

    // 用 push 的话这里是"退回上一组条件"(还在 /admin/users 上), 要从筛了三层退回
    // 没筛就得按好几次后退
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('URL 变了(后退/前进)会按新条件重新取数', async () => {
    const wrapper = await mountAt('/admin/users?page=1')
    expect(wrapper.find('.pg-btn.active').text()).toBe('1')
    getAdminUsers.mockClear()

    await router.replace({ query: { page: '3' } })
    await flushPromises()

    expect(getAdminUsers).toHaveBeenCalledTimes(1)
    expect(lastParams().page).toBe(3)
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })
})

describe('用户管理: 空态与竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('干净的零说「暂无用户」, 且不给清除筛选', async () => {
    getAdminUsers.mockResolvedValue(pageResult([], 0))
    const wrapper = await mountAt('/admin/users')

    expect(wrapper.text()).toContain('暂无用户')
    expect(wrapper.text()).not.toContain('没有匹配的用户')
    expect(wrapper.findAll('button').some((b) => b.text() === '清除筛选')).toBe(false)
  })

  it('筛完没结果说「没有匹配的」, 并给一个清条件的出口', async () => {
    getAdminUsers.mockResolvedValue(pageResult([], 0))
    const wrapper = await mountAt('/admin/users?q=zzz')

    expect(wrapper.text()).toContain('没有匹配的用户')
    expect(wrapper.text()).not.toContain('暂无用户')

    const clear = wrapper.findAll('button').find((b) => b.text() === '清除筛选')
    await clear.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.q).toBeUndefined()
  })

  it('翻到越界的那一页: 说「这一页没有用户了」并给一条回到第 1 页的路', async () => {
    // 总数还在(250)但这一页没有行 —— 服务端对越界页就是报真实的 total + 空 list,
    // 因为前端要靠 total 算出还有几页. 这时说「暂无用户」是错的, 而分页条上
    // 该点哪一页也看不出来, 所以这个按钮是**唯一**的出路
    getAdminUsers.mockResolvedValue(pageResult([], 250))
    const wrapper = await mountAt('/admin/users?page=99')

    expect(wrapper.text()).toContain('这一页没有用户了')
    expect(wrapper.text()).not.toContain('暂无用户')

    const back = wrapper.findAll('button').find((b) => b.text() === '回到第 1 页')
    getAdminUsers.mockClear()
    await back.trigger('click')
    await flushPromises()

    expect(lastParams().page).toBe(1)
  })

  it('先发的那次后到, 不能盖掉后发的', async () => {
    getAdminUsers.mockResolvedValue(pageResult([user(1)], 250))
    const wrapper = await mountAt('/admin/users')

    const slow = deferred()
    const fast = deferred()
    getAdminUsers.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([user(33)], 250))
    await flushPromises()
    expect(wrapper.text()).toContain('u33')

    slow.resolve(pageResult([user(22)], 250))
    await flushPromises()

    expect(wrapper.text()).toContain('u33')
    expect(wrapper.text()).not.toContain('u22')
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('过期那次的失败也不能写进界面', async () => {
    getAdminUsers.mockResolvedValue(pageResult([user(1)], 250))
    const wrapper = await mountAt('/admin/users')

    const slow = deferred()
    const fast = deferred()
    getAdminUsers.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { page: '2' } })
    await flushPromises()
    await router.replace({ query: { page: '3' } })
    await flushPromises()

    fast.resolve(pageResult([user(33)], 250))
    await flushPromises()

    slow.reject(new Error('boom'))
    await flushPromises()

    // 没有守卫的话这次失败会把列表清空并盖上一条报错 —— 界面上留下的是一条属于
    // 上一个请求的失败提示, 而那个请求早就没人关心了
    expect(wrapper.text()).toContain('u33')
    expect(wrapper.text()).not.toContain('失败')
  })
})
