import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'

// 这里 mock 的是**整个 api 模块** —— 与 views/admin/__tests__/adminLoadError.test.js
// 那种"只列用得到的几个导出"不同, 因为 App 挂的是**真路由**, 而真路由的每一条
// 都是懒加载的真页面: 光是走进后台三页就会发出真请求.
//
// 所以用 importOriginal 把没点名的导出原样放回去. 漏一个名字的话 vitest 会在
// import 阶段直接报「No X export is defined on the mock」, 而这里要钉的是
// App.vue 的渲染结构, 不该被一串接口清单淹没.
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal()
  const list = () => Promise.resolve({ data: { code: 200, data: [] } })
  return {
    ...actual,
    login: vi.fn(list),
    getDashboard: vi.fn(() => Promise.resolve({ data: { code: 200, data: {} } })),
    getAdminUsers: vi.fn(() => Promise.resolve({ data: { code: 200, data: [] } })),
    getAdminReviews: vi.fn(list),
    toggleUserStatus: vi.fn(list),
    setUserRole: vi.fn(list),
    unlockUser: vi.fn(list),
    adminDeleteReview: vi.fn(list),
  }
})

import router from './router'
import App from './App.vue'
import { useUserStore } from './stores/user'
import { useTheme } from './composables/useTheme'

/**
 * 后台路由与公共导航栏的关系.
 *
 * 两件事只有在这一层才测得出来:
 *
 * 一、**后台不渲染 NavBar**. 改前 App.vue 无条件渲染它; 现在后台有自己的左栏,
 *   而导航栏那个登录后下拉里挂着主题开关与退出登录 —— 不藏掉的话两套入口
 *   同时在场, 藏错了则是后台没有导航栏也没有替代品.
 *
 * 二、**跨后台子页时外壳不重建**. App.vue 给 <component> 的 key 对后台固定成
 *   '/admin', 于是 AdminLayout 在 /admin ↔ /admin/users ↔ /admin/reviews 之间
 *   是被 patch 而不是被重建 —— 侧栏不重播入场动画、抽屉状态不丢.
 *   这条测的就是**元素同一性**: 换成 route.path 当 key 的话, 三个子页各是一个
 *   key, 下面这条会拿到一个新元素. 也就是说这条断言正是让 viewKey() 从
 *   "看着像优化"变成"承重"的那一条.
 *
 * 用真路由而不是自建一份: 这里要的恰恰是"App.vue 与真路由表配在一起是什么样",
 * 包括 /admin 落在父+子两层上这件事.
 */

const ADMIN = { id: 1, username: 'admin', role: 'ADMIN', token: 'jwt' }

/** <Transition mode="out-in"> 下换页是"旧走完新的才挂上", 一次 flush 不够 */
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

async function mountApp(path, user = null) {
  localStorage.clear()
  setActivePinia(createPinia())
  if (user) useUserStore().setUser(user)

  await router.push(path)
  await router.isReady()

  const wrapper = mount(App, { global: { plugins: [router] } })
  await settle()
  return wrapper
}

describe('App 与后台外壳', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.className = ''
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
    // 主题是模块级单例, 用例之间会串 —— 归零到深色, 免得某条用例的起点
    // 取决于它前面那条有没有点过主题按钮
    const { light, toggle } = useTheme()
    if (light.value) toggle()
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('后台页面上不渲染公共导航栏', async () => {
    const wrapper = await mountApp('/admin/users', ADMIN)

    expect(wrapper.find('.admin-shell').exists()).toBe(true)
    expect(wrapper.find('.navbar').exists()).toBe(false)
  })

  it('普通页面上导航栏照常在', async () => {
    // 用 /login 而不是 /: NavBar 出不出现只取决于 path 是不是 /admin 开头,
    // 任何一条非后台路由都能证明这件事, 而登录页是其中起来最轻的一条
    // (首页一挂就会发一串列表请求)
    const wrapper = await mountApp('/login')

    expect(wrapper.find('.navbar').exists()).toBe(true)
  })

  it('从后台回到普通页面, 导航栏会回来', async () => {
    // 上面两条各测一半, 这条防的是"藏掉了就再也没回来"
    const wrapper = await mountApp('/admin', ADMIN)
    expect(wrapper.find('.navbar').exists()).toBe(false)

    await router.push('/login')
    await settle()

    expect(wrapper.find('.navbar').exists()).toBe(true)
    expect(wrapper.find('.admin-shell').exists()).toBe(false)
  })

  it('在后台各子页之间切换时, 外壳元素不被重建', async () => {
    const wrapper = await mountApp('/admin', ADMIN)
    const shell = wrapper.find('.admin-shell').element
    expect(shell).toBeTruthy()

    await router.push('/admin/users')
    await settle()

    // 先确认真的换页了 —— 否则下面那条在同一页上比较, 永远是绿的
    expect(wrapper.find('.admin-main h1').text()).toBe('用户管理')
    expect(wrapper.find('.admin-shell').element).toBe(shell)

    await router.push('/admin/reviews')
    await settle()

    expect(wrapper.find('.admin-main h1').text()).toBe('评论管理')
    expect(wrapper.find('.admin-shell').element).toBe(shell)

    await router.push('/admin')
    await settle()

    expect(wrapper.find('.admin-main h1').text()).toBe('后台概览')
    expect(wrapper.find('.admin-shell').element).toBe(shell)
  })
})
