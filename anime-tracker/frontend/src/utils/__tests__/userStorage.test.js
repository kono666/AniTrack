import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  loadStoredUser, saveStoredUser, clearStoredUser, USER_STORAGE_KEY,
} from '../userStorage'

/**
 * 这个模块的每一条用例都对应一个「用户能看到白屏」的输入.
 *
 * 关键断言不只是「返回 null」, 还有**脏数据被清掉了** —— 只返回 null 而不清理的话,
 * 下一次读取还会踩同一个坑, 问题会变成偶发性的(取决于哪段代码先读), 更难查.
 */
describe('userStorage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('reads back a normal stored user', () => {
    const user = { id: 1, username: 'test', token: 'jwt-token', role: 'USER' }
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))

    expect(loadStoredUser()).toEqual(user)
  })

  it('returns null when nothing is stored', () => {
    expect(loadStoredUser()).toBeNull()
  })

  it('treats broken JSON as logged out and removes it', () => {
    // 被截断的写入、用户手改、或者别的脚本写坏 —— 之前这行会让路由守卫每次跳转都抛
    localStorage.setItem(USER_STORAGE_KEY, '{"token":"abc"')

    expect(loadStoredUser()).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
  })

  it('treats a user object without a token as logged out and removes it', () => {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ username: 'test' }))

    expect(loadStoredUser()).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
  })

  it('rejects an empty token string', () => {
    // 空字符串是真的值, 但不是能用的凭证: 之前 `user && user.token` 会把它算成未登录,
    // 却会一直留在 storage 里
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify({ token: '' }))

    expect(loadStoredUser()).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
  })

  it.each([
    ['null', 'null'],
    ['a number', '123'],
    ['an array', '[{"token":"abc"}]'],
    ['a bare string', '"abc"'],
    ['a boolean', 'true'],
  ])('rejects %s even though it is valid JSON', (_label, raw) => {
    localStorage.setItem(USER_STORAGE_KEY, raw)

    expect(loadStoredUser()).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
  })

  it('saves and clears through the same key', () => {
    saveStoredUser({ token: 'abc' })
    expect(loadStoredUser()).toEqual({ token: 'abc' })

    clearStoredUser()
    expect(loadStoredUser()).toBeNull()
  })

  it('does not blow up when storage itself is unavailable', () => {
    // 浏览器禁用站点数据时, 连 getItem 都会抛. 这种情况下当未登录处理即可,
    // 不该让整个应用起不来.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled')
    })

    expect(loadStoredUser()).toBeNull()
  })

  it('does not blow up when writing fails', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })

    // 登录流程不该因为存不下就失败
    expect(() => saveStoredUser({ token: 'abc' })).not.toThrow()
  })
})
