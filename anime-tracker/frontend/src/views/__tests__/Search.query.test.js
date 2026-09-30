import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  searchAnime: vi.fn(),
  getRanking: vi.fn(),
  SEARCH_PAGE_SIZE: 20,
}))

import Search from '../Search.vue'
import { searchAnime, getRanking } from '../../api'

/**
 * 搜索页的 URL 同步、页码与竞态.
 *
 * ⚠️ 刻意与 Search.test.js 分开一个文件: 那边每个用例挂载后都不卸载, 而这一组
 * 里有几条要数"发了几次请求"、还要用 mockReturnValueOnce 排定谁先回来 ——
 * 留在同一个文件里的话, 前一个用例遗留的组件实例的 watcher 也会跟着路由动、
 * 把排好的返回值吃掉, 结果取决于用例的执行顺序.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/search', component: Search }],
})

/** 后端的返回形状: { data: { data: { list, total } } } */
function resultPage(list, total = 45) {
  return { data: { data: { list, total } } }
}
function card(id) {
  return { id, nameCn: `番剧${id}`, images: {}, rating: { score: 8 } }
}
/** 手写的 deferred: 这一组要精确控制"谁先回来", 不用假定时器 */
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/**
 * 挂载过的组件都记在这儿, 用例结束统一卸载.
 *
 * 不这么做的话, 上一个用例遗留的组件实例仍然挂着 watch——它照样跟着路由动、
 * 照样发请求, 于是"点一下发了几次请求"这种断言会随用例的执行顺序飘.
 */
let mounted = []

function mountSearch() {
  const wrapper = mount(Search, { global: { plugins: [router] } })
  mounted.push(wrapper)
  return wrapper
}

async function mountAt(fullPath) {
  await router.push(fullPath)
  await router.isReady()
  const wrapper = mountSearch()
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.forEach(w => w.unmount())
  mounted = []
})

function pageButton(wrapper, label) {
  return wrapper.findAll('.pg-btn').find(b => b.text() === label)
}

describe('搜索页: URL 同步与页码', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getRanking.mockResolvedValue({ data: { data: [] } })
    searchAnime.mockResolvedValue(resultPage([card(1)]))
  })

  it('点「搜索」按钮: 按第 1 页搜, 请求里没有事件对象', async () => {
    const wrapper = await mountAt('/search')

    await wrapper.find('.search-input').setValue('测试')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()

    // 改前模板写的是 @click="doSearch"(不带括号): Vue 传进来的是个 MouseEvent,
    // 它落在第一个形参上 —— 而第一个形参是页码. 于是请求参数里带着一个序列化出来的
    // 怪物, 页码那一栏则静默失效(不报错, 只是永远第 1 页)
    expect(searchAnime).toHaveBeenCalledTimes(1)
    expect(searchAnime).toHaveBeenCalledWith('测试', 1)
    expect(router.currentRoute.value.query.q).toBe('测试')
  })

  it('第 1 页不写进 URL(?page=1 是噪音)', async () => {
    await mountAt('/search?q=测试')
    expect(router.currentRoute.value.query.page).toBeUndefined()
  })

  it('点翻页: 只发一次请求, URL 跟着走', async () => {
    const wrapper = await mountAt('/search?q=测试')
    searchAnime.mockClear()

    await pageButton(wrapper, '2').trigger('click')
    await flushPromises()

    expect(searchAnime).toHaveBeenCalledTimes(1)
    expect(searchAnime).toHaveBeenLastCalledWith('测试', 2)
    expect(router.currentRoute.value.query.page).toBe('2')
  })

  it('同路径上 page 变了(后退/前进)会重新取那一页', async () => {
    // 改前这里只 watch 了 route.query.q: 页码写进了 URL, 却从来没人读回来 ——
    // 「搜 X → 翻到第 3 页 → 进详情 → 后退」看起来是坏的
    const wrapper = await mountAt('/search?q=测试')
    expect(wrapper.find('.pg-btn.active').text()).toBe('1')
    searchAnime.mockClear()

    await router.replace({ query: { q: '测试', page: '3' } })
    await flushPromises()

    // 一次就是一次: 那条 watch 里的早退判断挡的是 doSearch 自己写 URL 触发回来的那次
    expect(searchAnime).toHaveBeenCalledTimes(1)
    expect(searchAnime).toHaveBeenLastCalledWith('测试', 3)
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('从详情页后退(组件重新挂载)时按 URL 里的页码取数', async () => {
    // App.vue 的 router-view key 是 route.path, 所以从详情页回来 Home/Search 是
    // **重新挂载**的 —— 页码只存在组件里的话, 那时它已经是全新的一页了
    const wrapper = await mountAt('/search?q=测试&page=2')

    expect(searchAnime).toHaveBeenCalledWith('测试', 2)
    expect(wrapper.find('.pg-btn.active').text()).toBe('2')
  })

  it('关键词带首尾空格时只搜一次', async () => {
    const wrapper = await mountAt('/search')

    await wrapper.find('.search-input').setValue('  巨人  ')
    await wrapper.find('.search-btn').trigger('click')
    await flushPromises()

    // syncQuery 写进 URL 的是 trim 过的值, 所以那句自触发的早退判断必须比
    // keyword.value.trim() —— 拿原值去比的话, "自己刚写出去的那一次"会被认成
    // 外部变化, 于是又搜一遍
    expect(searchAnime).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query.q).toBe('巨人')
  })

  it('URL 里的 page 不合法时按第 1 页处理, 不把整页变成错误态', async () => {
    await mountAt('/search?q=测试&page=abc')
    expect(searchAnime).toHaveBeenCalledWith('测试', 1)
  })
})

describe('搜索页: 竞态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getRanking.mockResolvedValue({ data: { data: [] } })
  })

  it('先发的那次后到, 不能把界面盖回上一页', async () => {
    const wrapper = await mountAt('/search?q=测试')

    const slow = deferred()
    const fast = deferred()
    searchAnime.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { q: '测试', page: '2' } })
    await flushPromises()
    await router.replace({ query: { q: '测试', page: '3' } })
    await flushPromises()

    // 后发的先回来
    fast.resolve(resultPage([card(33)]))
    await flushPromises()
    expect(wrapper.text()).toContain('番剧33')

    // 先发的后回来 —— 界面必须纹丝不动
    slow.resolve(resultPage([card(22)]))
    await flushPromises()

    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.text()).not.toContain('番剧22')
    expect(wrapper.find('.pg-btn.active').text()).toBe('3')
  })

  it('过期那次的失败也不能写进界面', async () => {
    const wrapper = await mountAt('/search?q=测试')

    const slow = deferred()
    const fast = deferred()
    searchAnime.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.replace({ query: { q: '测试', page: '2' } })
    await flushPromises()
    await router.replace({ query: { q: '测试', page: '3' } })
    await flushPromises()

    fast.resolve(resultPage([card(33)]))
    await flushPromises()

    slow.reject(new Error('boom'))
    await flushPromises()

    // 没有守卫的话, 这次失败会把 results 清空并写一条 error —— 界面上留下的是一条
    // 属于上一个请求的报错, 而那个请求早就没人关心了
    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.text()).not.toContain('boom')
  })
})
