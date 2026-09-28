import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({ login: vi.fn() }))

import Login from '../Login.vue'
import { login } from '../../api'

/**
 * 登录页的 ?redirect= 是一个**开放重定向**的入口.
 *
 * 改前是 `router.push(route.query.redirect || '/')` —— 这个参数来自 URL,
 * 谁都能构造一条 /login?redirect=//evil.com 发出去. 只判断"以 / 开头"是不够的:
 * 以 // 开头的是协议相对地址, 浏览器认它, pushState 之后当前标签页就跳到
 * evil.com 了. 而这一步发生在**登录成功之后**——用户刚输完密码, 正是最不会
 * 怀疑接下来那一跳的时候.
 */

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div>home</div>' } },
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/profile', component: { template: '<div>profile</div>' } },
    ],
  })
}

async function loginWith(redirect) {
  const router = makeRouter()
  await router.push({ path: '/login', query: redirect === undefined ? {} : { redirect } })
  await router.isReady()

  const wrapper = mount(Login, { global: { plugins: [router] } })
  await wrapper.find('input[type="text"]').setValue('alice')
  await wrapper.find('input[type="password"]').setValue('abcd1234')
  await wrapper.find('form').trigger('submit')
  await flushPromises()

  return { router, wrapper }
}

describe('登录后的跳转目标', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
    login.mockResolvedValue({ data: { code: 200, data: { id: 1, username: 'alice', token: 'jwt' } } })
  })

  it('站内路径原样跳过去(这个功能本身要保住)', async () => {
    const { router } = await loginWith('/profile')
    expect(router.currentRoute.value.path).toBe('/profile')
  })

  it('协议相对地址被换成首页, 不会跳到站外', async () => {
    const { router } = await loginWith('//evil.com')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('反斜杠那一种也被挡掉', async () => {
    // 部分浏览器把 /\evil.com 当作 //evil.com 处理, 只挡 // 会漏
    const { router } = await loginWith('/\\evil.com')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('绝对 URL 被换成首页', async () => {
    const { router } = await loginWith('https://evil.com')
    expect(router.currentRoute.value.path).toBe('/')
    expect(router.currentRoute.value.href).not.toContain('evil.com')
  })

  it('没有 redirect 参数时照旧回首页', async () => {
    const { router } = await loginWith(undefined)
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('redirect 传了多个值(query 数组)时不会崩, 也不会跟着走', async () => {
    const { router } = await loginWith(['/profile', '//evil.com'])
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('登录成功后按钮不再停在「登录中...」', async () => {
    const { wrapper } = await loginWith('/profile')
    expect(wrapper.find('.auth-submit').text()).toBe('登录')
  })
})
