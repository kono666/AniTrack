import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))

vi.mock('../../../api', () => ({
  getAdminUsers: vi.fn(),
  toggleUserStatus: vi.fn(),
  setUserRole: vi.fn(),
  unlockUser: vi.fn(),
  resetUserPassword: vi.fn(),
  ADMIN_PAGE_SIZES: [20, 50, 100],
  ADMIN_PAGE_SIZE: 20,
}))

vi.mock('../../../composables/useToast', () => ({
  useToast: () => ({ show: toastSpy, remove: vi.fn(), items: { value: [] } }),
  showToast: toastSpy,
}))

import Users from '../Users.vue'
import { useUserStore } from '../../../stores/user'
import { getAdminUsers, resetUserPassword } from '../../../api'

/**
 * 管理员**替别人**重置密码的那块面板.
 *
 * 为什么这个端点必须有界面: 一个没有界面的端点等于没有这个能力 —— 而这正是这一批
 * 要修的缺口本身(全仓零 password 端点, 而 DataInitializer 一直在打印「请尽快修改
 * 初始密码」). 所以这一组钉的不是"面板好不好看", 而是**这条路径真的能用**.
 *
 * 三件与其它三个行内动作**不一样**的地方, 是这一组的重点:
 *
 * 1. **它不弹 confirm, 而是就地展开一块面板** —— 它要管理员输入一个字符串, 而
 *    `window.prompt` 的输入是不遮的(站旁边的人一眼看见新密码). 于是"面板开了没有、
 *    收起来了没有"需要被验.
 * 2. **它成功之后不重取列表** —— 这个动作改的是密码列, 而这张表根本不显示密码.
 *    其它三个动作改了状态/角色, 都在表里看得见, 所以它们必须重取. 这条差异很容易
 *    被下一个人"统一"掉, 于是它值得一条断言.
 * 3. **失败原因就地显示, 不弹 toast** —— 提示要挨着唯一那格输入.
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

/** 登录的管理员是 1 号; 列表里同时有他自己和另一个人 */
const ADMIN = { id: 1, username: 'admin', token: 'jwt', role: 'ADMIN' }

async function mountAt(list, me = ADMIN) {
  localStorage.setItem('anime_user', JSON.stringify(me))
  setActivePinia(createPinia())
  useUserStore()
  router = makeRouter()
  await router.push('/')
  await router.isReady()
  await router.push('/admin/users')
  getAdminUsers.mockResolvedValue(pageResult(list))
  const wrapper = mount(Users, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

/** 某一行的按钮 —— 按钮文案在多行之间会重名, 必须按行取 */
function rowButton(row, text) {
  return row.findAll('button').find((b) => b.text() === text)
}
const rows = (w) => w.findAll('tbody tr')
const panel = (w) => w.find('.reset-panel')
const panelInput = (w) => w.find('.reset-panel .admin-input')
const panelError = (w) => w.find('.reset-panel .reset-error').exists()
  ? w.find('.reset-panel .reset-error').text() : ''
const panelButton = (w, text) => w.findAll('.reset-panel button').find((b) => b.text() === text)

async function openFor(wrapper, username) {
  const row = rows(wrapper).find((r) => r.text().includes(username))
  await rowButton(row, '重置密码').trigger('click')
  await wrapper.vm.$nextTick()
}

async function submitPanel(wrapper, password) {
  await panelInput(wrapper).setValue(password)
  await panelButton(wrapper, '确认重置').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  resetUserPassword.mockResolvedValue({ data: { code: 200, message: '密码已重置', data: null } })
})

afterEach(() => {
  mounted.forEach((w) => w.unmount())
  mounted = []
})

describe('管理员重置密码: 面板的开与合', () => {
  it('默认收着; 点某一行的「重置密码」才展开, 并写明是给谁改', async () => {
    const wrapper = await mountAt([user(1), user(8)])

    expect(panel(wrapper).exists()).toBe(false)

    await openFor(wrapper, 'u8')

    expect(panel(wrapper).text()).toContain('u8')
    // 提示里要说清代价 —— 重置是"把那个人踢下线", 不是"帮他改个偏好设置"
    expect(panel(wrapper).text()).toContain('登录会全部失效')
    expect(panelInput(wrapper).attributes('type')).toBe('password')
  })

  it('自己那一行没有这个按钮: 改自己的密码要验旧密码, 走个人中心那条路', async () => {
    const wrapper = await mountAt([user(1), user(8)])

    expect(rowButton(rows(wrapper)[0], '重置密码')).toBeFalsy()
    expect(rowButton(rows(wrapper)[1], '重置密码')).toBeTruthy()
  })

  it('「取消」收起面板并清掉输入, 一个请求都不发', async () => {
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')
    await panelInput(wrapper).setValue('brandnew1')

    await panelButton(wrapper, '取消').trigger('click')
    await wrapper.vm.$nextTick()

    expect(panel(wrapper).exists()).toBe(false)
    expect(resetUserPassword).not.toHaveBeenCalled()

    // 再开一次要是干净的 —— 上一次的明文不能留在框里
    await openFor(wrapper, 'u8')
    expect(panelInput(wrapper).element.value).toBe('')
  })
})

describe('管理员重置密码: 本地校验', () => {
  it('太短 → 就地提示, 不发请求', async () => {
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')

    await submitPanel(wrapper, 'short1')

    expect(panelError(wrapper)).toBe('密码至少 8 位')
    expect(resetUserPassword).not.toHaveBeenCalled()
  })

  it('只有字母 → 就地提示, 不发请求', async () => {
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')

    await submitPanel(wrapper, 'abcdefgh')

    expect(panelError(wrapper)).toBe('密码必须同时包含字母和数字')
    expect(resetUserPassword).not.toHaveBeenCalled()
  })

  it('回车与点按钮是同一条路', async () => {
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')

    await panelInput(wrapper).setValue('brandnew1')
    await panelInput(wrapper).trigger('keyup.enter')
    await flushPromises()

    expect(resetUserPassword).toHaveBeenCalledWith(8, 'brandnew1')
  })
})

describe('管理员重置密码: 提交结果', () => {
  it('成功: 带上目标 id, 收起面板, 提示, 且**不重取列表**', async () => {
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')
    getAdminUsers.mockClear()

    await submitPanel(wrapper, 'brandnew1')

    expect(resetUserPassword).toHaveBeenCalledWith(8, 'brandnew1')
    expect(toastSpy).toHaveBeenCalledWith('已重置 "u8" 的密码', 'success')
    expect(panel(wrapper).exists()).toBe(false)
    // 这张表不显示密码列, 重取一次换不来任何界面上看得见的变化 ——
    // 与其它三个行内动作刻意不同, 见文件头
    expect(getAdminUsers).not.toHaveBeenCalled()
  })

  it('失败: 面板留着、就地显示服务端的原话、也不重取', async () => {
    resetUserPassword.mockRejectedValue({ response: { data: { message: '不能重置自己的密码' } } })
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')
    getAdminUsers.mockClear()

    await submitPanel(wrapper, 'brandnew1')

    expect(panelError(wrapper)).toBe('不能重置自己的密码')
    expect(panel(wrapper).exists()).toBe(true)
    expect(panelInput(wrapper).element.value).toBe('brandnew1')
    expect(getAdminUsers).not.toHaveBeenCalled()
  })

  it('连接断开(没有 response)时给兜底文案, 不是 undefined', async () => {
    resetUserPassword.mockRejectedValue(new Error('Network Error'))
    const wrapper = await mountAt([user(8)])
    await openFor(wrapper, 'u8')

    await submitPanel(wrapper, 'brandnew1')

    expect(panelError(wrapper)).toBe('重置失败，请稍后重试')
  })
})
