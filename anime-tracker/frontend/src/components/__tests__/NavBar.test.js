import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

import NavBar from '../NavBar.vue'

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
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/tags', component: { template: '<div />' } },
    { path: '/assistant', component: { template: '<div />' } },
    { path: '/login', component: { template: '<div />' } },
    { path: '/register', component: { template: '<div />' } },
  ],
})

async function mountAt(path) {
  setActivePinia(createPinia())
  await router.push(path)
  await router.isReady()
  return mount(NavBar, { global: { plugins: [router] } })
}

const labels = w => w.findAll('.nav-link-label').map(n => n.text())
const tagsLink = w => w.findAll('.nav-link').find(a => a.attributes('href') === '/tags')

describe('导航栏', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  })

  afterEach(() => {
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
