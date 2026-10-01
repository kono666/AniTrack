import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

// 未读数走 store, 而 store 走 api/index.js。这里只替掉那一个导出, 其余原样放回
// (importOriginal): NavBar 这条链上还有别的模块也在 import 它
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getUnreadCount: vi.fn() }
})

import NavBar from '../NavBar.vue'
import { getUnreadCount } from '../../api'
import { useUserStore } from '../../stores/user'
import { useNotificationStore } from '../../stores/notification'

/**
 * 导航栏的三项.
 *
 * 分类原先挤在首页最底部, 现在整块搬成了独立页, 导航栏是它**唯一**的入口 ——
 * 这一项要是没了或指错了地方, 那个页面在站内就没有任何路径能到达. 所以这里
 * 给导航栏建第一个测试文件.
 *
 * ⚠️ 两个桩是必须的, 少了就挂载不起来:
 *   · matchMedia —— jsdom 里 `typeof window.matchMedia === 'undefined'`,
 *     而 NavBar 的 initialLight() 要读 prefers-color-scheme.
 *   · pinia —— useUserStore.
 *
 * 刻意**不**断言"窄屏时标签文字被隐藏": 那是 @media (max-width:768px) 里的
 * display:none, 而 jsdom 不跑布局、也不应用媒体查询 —— 钉不住的东西不要假装
 * 钉住了.
 *
 * 后面那一组是未读红点. 它是导航栏上第一处角标, 有三件事只有在这一层测得出来:
 * 红点读的是**共用的**那个 store(个人页清完它要立刻跟着灭)、游客一个请求都不发、
 * 以及拉不到时红点不出现而**导航栏照常可用** —— 一个次要接口不该把导航栏拖下水.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/tags', component: { template: '<div />' } },
    { path: '/assistant', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
    { path: '/register', component: { template: '<div />' } },
    { path: '/profile', component: { template: '<div />' } },
  ],
})

const ME = { id: 1, username: 'alice', token: 'jwt', role: 'USER' }

/* 挂起来的实例留在模块作用域里, 每个用例结束时卸掉. 这不是讲究 —— NavBar 会
   watch 路由, 而 router 是这个文件里**共用**的一个: 不卸的话所有历史实例的
   watcher 都还活着, 下一次 router.push 会把它们一起叫醒, 于是"换页拉了 1 次"
   这种断言会数出个说不清的数(实测 4 次: 本用例的 1 次 + 三个历史实例)。 */
let mounted = null

async function mountAt(path, user = null) {
  setActivePinia(createPinia())
  if (user) useUserStore().setUser(user)
  await router.push(path)
  await router.isReady()
  mounted = mount(NavBar, { global: { plugins: [router] } })
  await flushPromises()
  return mounted
}

const labels = w => w.findAll('.nav-link-label').map(n => n.text())
const tagsLink = w => w.findAll('.nav-link').find(a => a.attributes('href') === '/tags')

/** 打开用户下拉(红点旁边那个角标在里面) */
async function openMenu(w) {
  await w.find('.nav-user-btn').trigger('click')
  await flushPromises()
  return w.find('.nav-dropdown')
}

describe('导航栏', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    // clearAllMocks 不清实现, 上一个用例设过的 mockRejectedValue 会串过来 ——
    // 所以默认值在每个用例之前重设一次
    getUnreadCount.mockResolvedValue({ data: { code: 200, data: { count: 0 } } })
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  })

  afterEach(() => {
    mounted?.unmount()
    mounted = null
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('三项的顺序是 发现 / 分类 / AI 助手', async () => {
    const w = await mountAt('/')

    expect(labels(w)).toEqual(['发现', '分类', 'AI 助手'])
  })

  it('「分类」指向 /tags, 并且带一个图标', async () => {
    const w = await mountAt('/')

    const link = tagsLink(w)
    expect(link, '导航栏上应当有一项指向 /tags').toBeTruthy()
    // 图标不是装饰: ≤768px 时 .nav-link-label 被隐藏, 它成了窄屏下唯一能说明
    // 这一项是什么的东西. 删掉它宽屏看不出来, 窄屏就成了一个没有含义的方块.
    expect(link.find('svg').exists()).toBe(true)
    expect(link.text()).toContain('分类')
  })

  it('已经在 /tags 上时那一项是选中态', async () => {
    const w = await mountAt('/tags')

    expect(tagsLink(w).classes()).toContain('nav-link--active')
    // 选中的只有它一个
    expect(w.findAll('.nav-link--active')).toHaveLength(1)
  })

  it('在别的页面上它不选中', async () => {
    const w = await mountAt('/assistant')

    expect(tagsLink(w).classes()).not.toContain('nav-link--active')
  })

  it('品牌的链接自带一个读得出来的名字', async () => {
    const w = await mountAt('/')

    const brand = w.find('.navbar-brand')
    expect(brand.exists()).toBe(true)
    /* ≤480px 时 .brand-text 是 display:none —— 它连无障碍树一起摘掉, 那个链接
       就只剩一个没有名字的胶片图标. aria-label 是补在那里的名字. 布局量不到,
       但属性量得到, 所以这条钉得住. */
    expect(brand.attributes('aria-label')).toBeTruthy()
  })
})

describe('导航栏的未读红点', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    getUnreadCount.mockResolvedValue({ data: { code: 200, data: { count: 0 } } })
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  })

  afterEach(() => {
    mounted?.unmount()
    mounted = null
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('没有未读时不出现红点', async () => {
    const w = await mountAt('/', ME)

    expect(w.find('.nav-dot').exists()).toBe(false)
  })

  it('有未读时头像上出现红点, 下拉里同时给出条数', async () => {
    getUnreadCount.mockResolvedValue({ data: { code: 200, data: { count: 3 } } })
    const w = await mountAt('/', ME)

    // 红点挂在头像上(它一直在场), 而数字在下拉里 —— 红点的价值恰恰是"不用点开"
    expect(w.find('.nav-dot').exists()).toBe(true)

    const menu = await openMenu(w)
    expect(menu.find('.dropdown-badge').text()).toBe('3')
  })

  it('游客一个未读请求都不发', async () => {
    // 点了红点也无处可去(个人页要登录), 而每换一页多发一个必然 401 的请求
    const w = await mountAt('/')

    expect(getUnreadCount).not.toHaveBeenCalled()
    expect(w.find('.nav-dot').exists()).toBe(false)
  })

  it('换页时重新拉一次 —— 否则个人页里读完通知, 红点会一直亮着', async () => {
    const w = await mountAt('/', ME)
    expect(getUnreadCount).toHaveBeenCalledTimes(1)

    await router.push('/tags')
    await flushPromises()

    expect(getUnreadCount).toHaveBeenCalledTimes(2)
    expect(w.find('.nav-dot').exists()).toBe(false)
  })

  it('个人页把未读清零后, 导航栏的红点跟着灭', async () => {
    // 这是"两份状态就会点进去红点还在"的反例: 数字住在共用的 store 里
    getUnreadCount.mockResolvedValue({ data: { code: 200, data: { count: 2 } } })
    const w = await mountAt('/', ME)
    expect(w.find('.nav-dot').exists()).toBe(true)

    useNotificationStore().clear()
    await flushPromises()

    expect(w.find('.nav-dot').exists()).toBe(false)
  })

  it('未读数拉不到: 红点不出现, 导航栏照常可用', async () => {
    // 红点是**提示**, 不是内容. 一个次要接口挂掉不该连累导航栏

    getUnreadCount.mockRejectedValue(new Error('boom'))
    const w = await mountAt('/', ME)

    expect(w.find('.nav-dot').exists()).toBe(false)
    // 导航栏还在, 而且菜单照样能打开(挂载过程本身也没有把异常抛出来)
    expect(w.find('.nav-username').text()).toBe('alice')
    const menu = await openMenu(w)
    expect(menu.find('.dropdown-badge').exists()).toBe(false)
    expect(menu.text()).toContain('个人主页')
  })

  it('数字脏了(字段是字符串)也读得出来, 不会是 NaN 的红点', async () => {
    getUnreadCount.mockResolvedValue({ data: { code: 200, data: { count: '5' } } })
    const w = await mountAt('/', ME)

    const menu = await openMenu(w)
    expect(menu.find('.dropdown-badge').text()).toBe('5')
  })
})

