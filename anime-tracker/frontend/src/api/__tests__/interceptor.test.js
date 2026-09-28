import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { saveStoredUser, loadStoredUser } from '../../utils/userStorage'

// 只把 router 换掉(拦截器要跳转), 其余走真实模块 —— 包括真实的 axios 实例,
// 这样拦下来的请求真的会从 adapter 走一遍 axios 的派发管线和两个拦截器.
// vi.mock 的工厂会被提升到文件顶部, 所以里面的东西必须用 vi.hoisted 一起提上去.
const { push, currentRoute, toast } = vi.hoisted(() => ({
  push: vi.fn(() => Promise.resolve()),
  currentRoute: { value: { path: '/', fullPath: '/' } },
  toast: vi.fn(),
}))
vi.mock('../../router', () => ({
  default: { push, currentRoute },
}))
vi.mock('../../composables/useToast', () => ({
  showToast: (...args) => toast(...args),
}))

import api from '../index.js'
import { useUserStore } from '../../stores/user'

/**
 * 这一组测的是「接线」而不是那几个 if.
 *
 * 401 和 403 的处理都写在拦截器里, 平时点页面看不出来 —— 只有真的过期、
 * 真的越权的时候才跑一次, 而那正是最不该出错的时刻. 所以这里把实例的
 * adapter 换成一个必定失败的桩, 让请求真的发出去、真的被拦下来.
 */

const USER = { id: 1, username: 'admin', role: 'ADMIN', token: 'jwt-abc' }

// 下一次请求要回什么状态码
let nextStatus = 401

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  push.mockClear()
  toast.mockClear()
  nextStatus = 401
  currentRoute.value = { path: '/', fullPath: '/' }

  api.defaults.adapter = async (config) => {
    if (nextStatus === 200) {
      return { data: { code: 200, data: null }, status: 200, statusText: 'OK', headers: {}, config }
    }
    const err = new Error(`Request failed with status code ${nextStatus}`)
    err.config = config
    err.response = { status: nextStatus, data: { code: nextStatus, message: 'x' }, headers: {}, config }
    throw err
  }
})

/** 发一个必定失败的请求, 把拦截器跑完 */
async function request(path = '/x') {
  await expect(api.get(path)).rejects.toBeTruthy()
}

describe('401 处理', () => {
  it('清掉登录态并跳登录页, 带上登录后要回的地址', async () => {
    saveStoredUser(USER)
    const store = useUserStore()
    store.setUser(USER)
    currentRoute.value = { path: '/profile', fullPath: '/profile?tab=1' }

    await request()

    expect(loadStoredUser()).toBeNull()
    expect(store.loggedIn).toBe(false)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ path: '/login', query: { redirect: '/profile?tab=1' } })
  })

  it('并发的好几个 401 只跳一次', async () => {
    saveStoredUser(USER)
    currentRoute.value = { path: '/profile', fullPath: '/profile' }
    nextStatus = 401

    // 首页/管理端那种 Promise.all 场景: 几个请求同时拿到 401
    await Promise.all([request('/a'), request('/b'), request('/c')])

    expect(push).toHaveBeenCalledTimes(1)
  })

  it('已经过期过一次, 重新登录后再过期, 还会跳', async () => {
    saveStoredUser(USER)
    currentRoute.value = { path: '/profile', fullPath: '/profile' }
    await request()
    expect(push).toHaveBeenCalledTimes(1)

    // 用户重新登录: 登录接口本身不带 token, 之后的应用请求会带上新的 token,
    // 标志位正是在那里复位的
    saveStoredUser({ ...USER, token: 'jwt-new' })
    nextStatus = 200
    await api.get('/ok')

    nextStatus = 401
    push.mockClear()
    currentRoute.value = { path: '/profile', fullPath: '/profile' }
    await request()

    expect(push).toHaveBeenCalledTimes(1)
  })

  it('已经在登录页就不再跳(别把用户正在填的表单顶掉)', async () => {
    saveStoredUser(USER)
    currentRoute.value = { path: '/login', fullPath: '/login' }

    await request()

    expect(push).not.toHaveBeenCalled()
  })
})

describe('403 处理', () => {
  beforeEach(() => {
    nextStatus = 403
  })

  it('提示没有权限并回首页', async () => {
    currentRoute.value = { path: '/admin/users', fullPath: '/admin/users' }

    await request()

    expect(toast).toHaveBeenCalledWith('没有权限', 'error')
    expect(push).toHaveBeenCalledWith('/')
  })

  it('本来就在首页时不重复导航, 但仍然提示', async () => {
    currentRoute.value = { path: '/', fullPath: '/' }

    await request()

    expect(toast).toHaveBeenCalledWith('没有权限', 'error')
    expect(push).not.toHaveBeenCalled()
  })

  it('403 不动登录态: 有权限问题不等于没登录', async () => {
    saveStoredUser(USER)

    await request()

    expect(loadStoredUser()).not.toBeNull()
  })
})
