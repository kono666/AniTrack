import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))

vi.mock('../../../api', () => ({
  getAdminUsers: vi.fn(),
  toggleUserStatus: vi.fn(),
  setUserRole: vi.fn(),
  unlockUser: vi.fn(),
  ADMIN_USER_PAGE_SIZES: [20, 50, 100],
  ADMIN_USER_PAGE_SIZE: 20,
}))

vi.mock('../../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Users from '../Users.vue'
import { getAdminUsers, toggleUserStatus, setUserRole, unlockUser } from '../../../api'

/**
 * 行内四个操作(解锁 / 禁用启用 / 设为管理员)之后**重取当前页**, 而不是本地改写.
 *
 * 在服务端分页下, 本地改写在两种情况下会立刻自相矛盾: 筛着 `status=ACTIVE` 时禁用了
 * 这一页的最后一个人(那一行已经不符合筛选了), 以及 total 变了(分页条跟着错).
 * 所以这一组钉的是"操作之后那一次请求**带着当前全部条件**、要的是**当前这一页**" ——
 * 少带一个条件就会返回另一个结果集, 而界面上看不出区别.
 *
 * ⚠️ 与 Users.query.test.js 一样: 每个用例一份新的 router、挂载过的组件统一卸载.
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
let mounted = []

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

/** 带上全部七个参数的地址, 用来验"重取时一个条件都没丢" */
const FULL_PATH =
  '/admin/users?q=ab&role=USER&status=ACTIVE&sort=username&order=desc&page=2&limit=50'

const FULL_PARAMS = {
  keyword: 'ab', role: 'USER', status: 'ACTIVE',
  sort: 'username', order: 'desc', page: 2, limit: 50,
}

async function mountAt(fullPath, list) {
  router = makeRouter()
  await router.push('/')
  await router.isReady()
  await router.push(fullPath)
  getAdminUsers.mockResolvedValue(pageResult(list))
  const wrapper = mount(Users, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const buttonWith = (w, text) => w.findAll('button').find((b) => b.text() === text)
const lastParams = () => getAdminUsers.mock.calls.at(-1)[0]

beforeEach(() => {
  vi.clearAllMocks()
  // confirm 一律点"确定": 这里测的是确认之后发生的事, 弹窗本身在 jsdom 里是空实现
  vi.stubGlobal('confirm', () => true)
  toggleUserStatus.mockResolvedValue({ data: { code: 200, data: null } })
  setUserRole.mockResolvedValue({ data: { code: 200, data: null } })
  unlockUser.mockResolvedValue({ data: { code: 200, message: '账号已解锁' } })
})

afterEach(() => {
  vi.unstubAllGlobals()
  mounted.forEach((w) => w.unmount())
  mounted = []
})

describe('用户管理: 行内操作之后重取当前页', () => {
  it('禁用: 带着当前全部条件重取这一页', async () => {
    const wrapper = await mountAt(FULL_PATH, [user(7)])
    getAdminUsers.mockClear()

    await buttonWith(wrapper, '禁用').trigger('click')
    await flushPromises()

    expect(toggleUserStatus).toHaveBeenCalledWith(7)
    expect(getAdminUsers).toHaveBeenCalledTimes(1)
    expect(lastParams()).toEqual(FULL_PARAMS)
  })

  it('设为管理员: 同样不丢条件', async () => {
    const wrapper = await mountAt(FULL_PATH, [user(7)])
    getAdminUsers.mockClear()

    await buttonWith(wrapper, '设为管理员').trigger('click')
    await flushPromises()

    expect(setUserRole).toHaveBeenCalledWith(7, 'ADMIN')
    expect(getAdminUsers).toHaveBeenCalledTimes(1)
    expect(lastParams()).toEqual(FULL_PARAMS)
  })

  it('解锁: 提示服务端那句话, 并重取', async () => {
    const wrapper = await mountAt(FULL_PATH, [user(7, { locked: true })])
    getAdminUsers.mockClear()

    await buttonWith(wrapper, '解锁').trigger('click')
    await flushPromises()

    expect(unlockUser).toHaveBeenCalledWith(7)
    expect(toastSpy).toHaveBeenCalledWith('账号已解锁', 'success')
    expect(lastParams()).toEqual(FULL_PARAMS)
  })

  it('管理员那一行没有「禁用」和「设为管理员」, 但有「解锁」', async () => {
    const wrapper = await mountAt('/admin/users', [
      user(1, { role: 'ADMIN' }),
      user(2, { role: 'ADMIN', locked: true }),
    ])

    // 改前就是这样, 这里钉住它: 前端只是不让点, 真正的防线在 setUserRole 里
    // (「不能改自己的角色」), 而解锁对管理员**必须**留着 —— 管理员账号被人
    // 在线爆破锁死时, 那是唯一能把它救回来的路径
    expect(buttonWith(wrapper, '禁用')).toBeFalsy()
    expect(buttonWith(wrapper, '设为管理员')).toBeFalsy()
    expect(buttonWith(wrapper, '解锁')).toBeTruthy()
  })
})

describe('用户管理: 操作失败', () => {
  it('服务端给了原因就说原因, 并且不重取', async () => {
    const wrapper = await mountAt(FULL_PATH, [user(7)])
    toggleUserStatus.mockRejectedValue({ response: { data: { message: '不能禁用自己' } } })
    getAdminUsers.mockClear()

    await buttonWith(wrapper, '禁用').trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('不能禁用自己', 'error')
    // 失败之后那一行还是原样, 重取一次只会把屏幕刷一遍 —— 而"刷完没变化"
    // 会让用户以为操作成功了
    expect(getAdminUsers).not.toHaveBeenCalled()
  })

  it('没有原因时用兜底文案', async () => {
    const wrapper = await mountAt(FULL_PATH, [user(7)])
    toggleUserStatus.mockRejectedValue(new Error('boom'))

    await buttonWith(wrapper, '禁用').trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('操作失败', 'error')
  })

  it('用户在确认框上点「取消」时, 一个请求都不发', async () => {
    vi.stubGlobal('confirm', () => false)
    const wrapper = await mountAt(FULL_PATH, [user(7)])
    getAdminUsers.mockClear()

    await buttonWith(wrapper, '禁用').trigger('click')
    await flushPromises()

    expect(toggleUserStatus).not.toHaveBeenCalled()
    expect(getAdminUsers).not.toHaveBeenCalled()
  })
})
