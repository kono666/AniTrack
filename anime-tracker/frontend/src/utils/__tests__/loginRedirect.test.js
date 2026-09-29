import { describe, it, expect, beforeEach } from 'vitest'
import { rememberPath, resolvePostAuthPath, resetRememberedPath } from '../loginRedirect'

/**
 * 「登录后回哪去」的兜底.
 *
 * 改前只有路由守卫那一条路会带 ?redirect=(它把 to.fullPath 塞进 query), 而
 * 「+ 追番」「登录后参与讨论」这些是硬跳 /login 的, URL 上什么都没有, 于是登录
 * 成功后回首页. 现在由 router.afterEach 记下「刚才那一页」, 登录页拿它兜底.
 */

describe('resolvePostAuthPath', () => {
  beforeEach(() => resetRememberedPath())

  it('站内路径原样返回', () => {
    expect(resolvePostAuthPath('/profile')).toBe('/profile')
    expect(resolvePostAuthPath('/anime/12')).toBe('/anime/12')
    expect(resolvePostAuthPath('/search?q=高达&page=2')).toBe('/search?q=高达&page=2')
  })

  it('协议相对地址不会被采用(开放重定向)', () => {
    // 只判断"以 / 开头"是不够的: 以 // 开头的是协议相对地址, 浏览器认它
    expect(resolvePostAuthPath('//evil.com')).toBe('/')
    expect(resolvePostAuthPath('/\\evil.com')).toBe('/')
  })

  it('绝对 URL 与数组(重复 query 参数)都不会被采用', () => {
    expect(resolvePostAuthPath('https://evil.com')).toBe('/')
    expect(resolvePostAuthPath(['/profile', '//evil.com'])).toBe('/')
    expect(resolvePostAuthPath(undefined)).toBe('/')
  })

  it('没有 redirect 时回到记住的那一页', () => {
    rememberPath('/anime/12')
    expect(resolvePostAuthPath(undefined)).toBe('/anime/12')
  })

  it('带 query 的页面整个记住(页码、筛选都得留住)', () => {
    rememberPath('/search?q=高达&page=3')
    expect(resolvePostAuthPath(undefined)).toBe('/search?q=高达&page=3')
  })

  it('登录页/注册页本身不记 —— 否则兜底会变成登录页自己', () => {
    rememberPath('/profile')
    rememberPath('/login?redirect=/admin')
    expect(resolvePostAuthPath(undefined)).toBe('/profile')

    resetRememberedPath()
    rememberPath('/register')
    expect(resolvePostAuthPath(undefined)).toBe('/')
  })

  it('redirect 不合规时用兜底页, 而不是把它替换成首页', () => {
    // 别人构造一条 /login?redirect=https://evil.com 发给受害者, 受害者登录后
    // 应该回到他自己刚才在的地方 —— 没道理因为别人发的链接受罚
    rememberPath('/profile')
    expect(resolvePostAuthPath('https://evil.com')).toBe('/profile')
  })

  it('站外路径不进兜底变量(rememberPath 自己先挡住)', () => {
    rememberPath('https://evil.com')
    rememberPath('//evil.com')
    expect(resolvePostAuthPath(undefined)).toBe('/')
  })

  it('redirect 优先于兜底页', () => {
    rememberPath('/profile')
    expect(resolvePostAuthPath('/anime/3')).toBe('/anime/3')
  })
})

describe('与真实路由的接线', () => {
  it('成功导航到站内页面之后, 那一页会被记下来', async () => {
    resetRememberedPath()
    // 动态 import: src/router/index.js 建的是 web history, 会摸 window.location,
    // 放在用例里加载, 免得影响上面那些纯粹读模块状态的用例
    const { default: router } = await import('../../router')
    await router.push('/assistant')
    await router.isReady()

    // 这就是「+ 追番」硬跳 /login 之后 Login.vue 能拿到的兜底值
    expect(resolvePostAuthPath(undefined)).toBe('/assistant')
  })
})
