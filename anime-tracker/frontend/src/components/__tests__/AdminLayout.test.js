import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import AdminLayout from '../AdminLayout.vue'
import { useUserStore } from '../../stores/user'
import { useTheme } from '../../composables/useTheme'

/**
 * 后台外壳.
 *
 * 改前 AdminLayout 是「每个 view 在模板里包一层」的那个 31 行组件: 一个 title
 * prop 加一个 slot, 导航是三条平铺的 tab, 没有 <style>. 现在它是**路由布局** ——
 * 一个父路由撑起侧栏 + 内容区, 三个子页只是内容.
 *
 * 所以这里钉的是「外壳接管了哪些事」. 其中两件是**改前根本没有归属**的:
 *   · 主题开关 —— 改前只在 NavBar 的登录后下拉里, 而后台不渲染 NavBar;
 *   · 退出登录 —— 同上.
 * 漏掉任意一件, 管理员进了后台就出不来了(要么切不了主题, 要么退不了登录).
 *
 * 用一份自建的小路由而不是 import 真路由: 这里要的是**父/子这个形状**, 而
 * 真路由的三条 name/meta 由 router/__tests__/guard.test.js 与 App.test.js 盯着.
 */

const ADMIN = { id: 1, username: 'admin', role: 'ADMIN', token: 'jwt' }

/** 子页被挂载时记一笔 —— 「未授权时一次请求都不发」那条断言靠它 */
let childMounts = []

const child = name => ({
  template: `<div class="child-${name}" />`,
  mounted() { childMounts.push(name) },
})

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div class="home-stub" />' } },
    {
      path: '/admin',
      // 与真路由同形: 外壳在父记录上, 名字与 meta.title 在子记录上
      component: AdminLayout,
      children: [
        { path: '', name: 'AdminDashboard', component: child('dashboard'), meta: { title: '后台概览' } },
        { path: 'users', name: 'AdminUsers', component: child('users'), meta: { title: '用户管理' } },
        { path: 'reviews', name: 'AdminReviews', component: child('reviews'), meta: { title: '评论管理' } },
      ],
    },
  ],
})

/** 直接 mount AdminLayout 是拿不到子出口的, 得让它当一次真正的路由组件 */
const Host = { template: '<router-view />' }

async function mountAt(path, user = ADMIN) {
  childMounts = []
  localStorage.clear()
  setActivePinia(createPinia())
  if (user) useUserStore().setUser(user)

  await router.push(path)
  await router.isReady()

  const wrapper = mount(Host, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function addThemeMeta() {
  const meta = document.createElement('meta')
  meta.setAttribute('name', 'theme-color')
  meta.setAttribute('content', '#0d0c0b')
  document.head.appendChild(meta)
}

const navLinks = wrapper => wrapper.findAll('.admin-nav .admin-nav-link')
const activeLinks = wrapper => navLinks(wrapper).filter(l => l.classes().includes('active'))
const buttonWith = (wrapper, text) => wrapper.findAll('button').find(b => b.text().includes(text))

describe('后台外壳', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    document.body.className = ''
    document.head.innerHTML = ''
    addThemeMeta()
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))

    // 主题是模块级单例, 用例之间会串. 先归零到深色再开始, 免得某条用例的
    // 断言取决于它前面那条有没有点过主题按钮
    const { light, toggle } = useTheme()
    if (light.value) toggle()
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('三条导航各自指向一条后台路由', async () => {
    const wrapper = await mountAt('/admin')
    const links = navLinks(wrapper)

    expect(links.map(l => l.attributes('href')))
      .toEqual(['/admin', '/admin/users', '/admin/reviews'])
    expect(links[0].text()).toContain('仪表盘')
    expect(links[1].text()).toContain('用户管理')
    expect(links[2].text()).toContain('评论管理')
  })

  it('在 /admin/users 上只有「用户管理」是选中态', async () => {
    // 「仪表盘」那条 to="/admin" 是所有后台路径的前缀. 它用的是 exact-active-class
    // 而不是 active-class —— 换成后者的话这里会同时亮两条, 而且看上去只是
    // "两个菜单都想强调一下", 不像 bug
    const wrapper = await mountAt('/admin/users')

    expect(activeLinks(wrapper)).toHaveLength(1)
    expect(activeLinks(wrapper)[0].text()).toContain('用户管理')
  })

  it('在 /admin 上只有「仪表盘」是选中态', async () => {
    const wrapper = await mountAt('/admin')

    expect(activeLinks(wrapper)).toHaveLength(1)
    expect(activeLinks(wrapper)[0].text()).toContain('仪表盘')
  })

  it('标题行显示的是当前子页的 meta.title', async () => {
    // 改前这里是不动的 prop 默认值「管理后台」, 而标签页一直是「后台概览 ·
    // AniTrack」—— 同一页两个名字. 现在两边都读 meta.title, 只有一份来源
    expect((await mountAt('/admin/users')).find('.admin-main h1').text()).toBe('用户管理')
    expect((await mountAt('/admin/reviews')).find('.admin-main h1').text()).toBe('评论管理')
  })

  it('侧栏的主题开关改 body、地址栏色和存储', async () => {
    // 改前这件事只有 NavBar 的登录后下拉能做, 而后台不渲染 NavBar ——
    // 不接管的话管理员在后台就切不了主题
    const wrapper = await mountAt('/admin')
    const btn = buttonWith(wrapper, '亮色模式')
    expect(btn).toBeTruthy()

    await btn.trigger('click')

    expect(document.body.classList.contains('light')).toBe(true)
    expect(document.querySelector('meta[name="theme-color"]').getAttribute('content')).toBe('#fcf1f0')
    expect(localStorage.getItem('theme')).toBe('light')
  })

  it('退出登录清掉登录态并落回首页', async () => {
    // 同一件事的另一半: 侧栏不接管的话, 管理员进了后台就退不了登录
    const wrapper = await mountAt('/admin')
    const store = useUserStore()
    expect(store.loggedIn).toBe(true)

    await buttonWith(wrapper, '退出登录').trigger('click')
    await flushPromises()

    expect(store.loggedIn).toBe(false)
    expect(localStorage.getItem('anime_user')).toBeNull()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('不是管理员时子页一次都不挂载, 并落回首页', async () => {
    // 未授权 = 不渲染出口, 不只是 redirect. 只做 replace 的话, 子页的 onMounted
    // 仍会先跑一轮再被卸载 —— 对后台那三个页面就是白打一次接口
    const wrapper = await mountAt('/admin/users', null)

    expect(childMounts).toEqual([])
    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.find('.admin-shell').exists()).toBe(false)
  })

  it('抽屉开关: 点开有遮罩, 点遮罩收回', async () => {
    const wrapper = await mountAt('/admin')
    expect(wrapper.find('.admin-sidebar').classes()).not.toContain('open')
    expect(wrapper.find('.admin-scrim').exists()).toBe(false)

    await wrapper.find('.admin-sidebar-toggle').trigger('click')
    expect(wrapper.find('.admin-sidebar').classes()).toContain('open')
    expect(wrapper.find('.admin-scrim').exists()).toBe(true)

    await wrapper.find('.admin-scrim').trigger('click')
    expect(wrapper.find('.admin-sidebar').classes()).not.toContain('open')
    expect(wrapper.find('.admin-scrim').exists()).toBe(false)
  })

  it('换页会把抽屉收起来', async () => {
    // 不收的话, 窄屏点完「用户管理」新页面被抽屉盖着, 还得手动关一次
    const wrapper = await mountAt('/admin')
    await wrapper.find('.admin-sidebar-toggle').trigger('click')
    expect(wrapper.find('.admin-sidebar').classes()).toContain('open')

    await wrapper.find('.admin-nav a[href="/admin/users"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.admin-sidebar').classes()).not.toContain('open')
  })
})
