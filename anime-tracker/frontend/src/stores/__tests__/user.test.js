import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '../user'
import { USER_STORAGE_KEY } from '../../utils/userStorage'

/**
 * user store 是应用启动时最早被创建的东西之一(导航栏、各个页面都要用它),
 * 所以它初始化时抛异常等于整个界面起不来 —— 而它读的是 localStorage,
 * 一段谁都可能写坏的数据.
 */
describe('user store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('storage 里是坏 JSON 时按未登录启动, 而不是抛异常', () => {
    // 改前这里会抛: JSON.parse('乱码') -> SyntaxError, 组件树根本建立不起来
    localStorage.setItem(USER_STORAGE_KEY, '乱码不是 JSON')

    expect(() => useUserStore()).not.toThrow()
    expect(useUserStore().loggedIn).toBe(false)
    expect(useUserStore().user).toBeNull()
  })

  it('读回已登录的用户态', () => {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ username: 'u', token: 'jwt', role: 'USER' }))

    const store = useUserStore()

    expect(store.loggedIn).toBe(true)
    expect(store.token).toBe('jwt')
  })

  it('setUser 写进 storage, logout 清掉', () => {
    const store = useUserStore()

    store.setUser({ username: 'u', token: 'jwt' })
    expect(JSON.parse(localStorage.getItem(USER_STORAGE_KEY))).toEqual({ username: 'u', token: 'jwt' })
    expect(store.loggedIn).toBe(true)

    store.logout()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
    expect(store.loggedIn).toBe(false)
  })
})
