import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getRanking: vi.fn(),
  getCalendar: vi.fn(),
  getTags: vi.fn(),
  getFiltered: vi.fn(),
}))

import Home from '../Home.vue'
import { getRanking, getCalendar, getTags, getFiltered } from '../../api'
import { resetHomeCache } from '../../utils/homeCache'

/**
 * 首页「分类浏览」的筛选状态.
 *
 * 改前 selectedTag / tagPage 只活在组件实例里, 而 App.vue 的 router-view key 是
 * route.path —— 从首页点进详情再按后退, Home 是**重新挂载**的, 于是筛选全丢.
 * 这一组盯两件事: 筛选写进 URL, 以及 URL 变了能读回来.
 *
 * ⚠️ 与 Home.test.js 分开一个文件: 那边每个用例挂载后都不卸载, 而这一组要数
 * "发了几次请求"、还要用 mockReturnValueOnce 排定谁先回来 —— 同文件里前一个
 * 用例遗留组件的 watcher 会跟着路由动, 把排好的返回值吃掉.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

// Home 用 v-reveal (IntersectionObserver), jsdom 没有实现它
class FakeIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function mountHome() {
  return mount(Home, { global: { plugins: [router] } })
}

function card(id) {
  return { id, nameCn: `番剧${id}`, images: {}, rating: { score: 8 } }
}
function filteredPage(list, total = 45) {
  return { data: { data: { list, total } } }
}
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** 当前高亮的那个 chip 的文字(没有就是 null) */
function activeChip(wrapper) {
  const chip = wrapper.findAll('.tag-chip').find(c => c.classes().includes('active'))
  return chip ? chip.text() : null
}

describe('首页分类筛选进 URL', () => {
  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
    getCalendar.mockResolvedValue({ data: { data: [] } })
    getTags.mockResolvedValue({
      data: { data: [{ name: '治愈', count: 3 }, { name: '热血', count: 5 }] },
    })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/')
    await router.isReady()
  })

  it('首访(URL 里没有任何筛选)也会把分类区第 1 页加载出来', async () => {
    const wrapper = mountHome()
    await flushPromises()

    // 改前 onMounted 只调 loadHome: 分类区永远停在"一个标题 + 一排 chip",
    // 连那句「该分类暂无数据」都出不来(它要 selectedTag 为真)
    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ page: 1 }))
    // 「全部」的口径与 Search 一致: 不带这个参数, 而不是传空串
    expect(getFiltered.mock.calls[0][0].tag).toBeUndefined()
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('点分类标签: 筛选进 URL, 而且只发一次请求', async () => {
    const wrapper = mountHome()
    await flushPromises()
    getFiltered.mockClear()

    await wrapper.findAll('.tag-chip')[1].trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tag).toBe('治愈')
    expect(router.currentRoute.value.query.page).toBeUndefined()
    // 这条专门防"写 URL → watch 又发一次": 点一下打两个请求.
    // 靠的是 watch 开头那句「URL 解析出来的值和当前 ref 一致就早退」,
    // 而 selectTag 是先改 ref 再写 URL 的
    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '治愈', page: 1 }))

    wrapper.unmount()
  })

  it('点「全部」把 tag 从 URL 上摘掉', async () => {
    await router.push('/?tag=治愈')
    const wrapper = mountHome()
    await flushPromises()
    expect(activeChip(wrapper)).toContain('治愈')

    await wrapper.findAll('.tag-chip')[0].trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tag).toBeUndefined()
    expect(activeChip(wrapper)).toBe('全部')
    expect(getFiltered).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))

    wrapper.unmount()
  })

  it('带 ?tag=&page= 进来: 首访就按它取数, 对应 chip 也是亮的', async () => {
    // 这一条同时覆盖两个入口: 分享/收藏的链接直接打开, 以及从详情页后退
    await router.push('/?tag=热血&page=3')
    const wrapper = mountHome()
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '热血', page: 3 }))
    expect(activeChip(wrapper)).toContain('热血')

    wrapper.unmount()
  })

  it('同路径的 query 变化(后退/前进)会被读回来并取那一页', async () => {
    const wrapper = mountHome()
    await flushPromises()
    getFiltered.mockClear()

    await router.push('/?tag=治愈&page=2')
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '治愈', page: 2 }))
    expect(activeChip(wrapper)).toContain('治愈')

    wrapper.unmount()
  })

  it('翻页把页码写进 URL, 第 1 页不写', async () => {
    await router.push('/?tag=治愈')
    const wrapper = mountHome()
    await flushPromises()

    const page2 = wrapper.findAll('.pg-btn').find(b => b.text() === '2')
    await page2.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.page).toBe('2')

    const page1 = wrapper.findAll('.pg-btn').find(b => b.text() === '1')
    await page1.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.page).toBeUndefined()

    wrapper.unmount()
  })
})

describe('首页分类区: 竞态', () => {
  beforeEach(async () => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
    getCalendar.mockResolvedValue({ data: { data: [] } })
    getTags.mockResolvedValue({ data: { data: [{ name: '治愈', count: 3 }] } })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/')
    await router.isReady()
  })

  it('先发的那次后到, 不能盖掉后发的结果', async () => {
    const wrapper = mountHome()
    await flushPromises()

    const slow = deferred()
    const fast = deferred()
    getFiltered.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.push('/?tag=治愈&page=2')
    await flushPromises()
    await router.push('/?tag=治愈&page=3')
    await flushPromises()

    fast.resolve(filteredPage([card(33)]))
    await flushPromises()
    expect(wrapper.text()).toContain('番剧33')

    slow.resolve(filteredPage([card(22)]))
    await flushPromises()

    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.text()).not.toContain('番剧22')

    wrapper.unmount()
  })
})
