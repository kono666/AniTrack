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
 * 搜索页的排序切换.
 *
 * 改前「热门排行 / 最近更新」这个状态**只藏在网址里**: 首页那两个「查看全部 →」
 * 把人送进 /search?view=rank 或 ?view=date, 页面上却没有任何地方能看见它、
 * 更别说改它 —— 到了这一页就出不去了, 只能自己动手改地址栏. viewMode 一直在
 * 正常工作(它驱动标题和 loadBrowse 的排序), 缺的只是"能改它的 UI".
 *
 * 单独一个文件: 与 Search.query.test.js 同一个理由(遗留组件实例的 watcher 会
 * 把排好的 mock 返回值吃掉), 而且这一组还要数请求次数.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/search', component: Search }],
})

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

function btn(wrapper, label) {
  return wrapper.findAll('.vs-btn').find(b => b.text() === label)
}
const activeLabels = w => w.findAll('.vs-btn.active').map(b => b.text())

describe('搜索页: 排行 / 最新 切换', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getRanking.mockResolvedValue({ data: { data: [] } })
    searchAnime.mockResolvedValue({ data: { data: { list: [], total: 0 } } })
  })

  it('默认进来是热门排行, 按 rank 取数', async () => {
    const w = await mountAt('/search')

    expect(w.find('h1').text()).toBe('热门排行')
    expect(activeLabels(w)).toEqual(['热门排行'])
    expect(getRanking).toHaveBeenCalledWith('rank', 60)
  })

  it('?view=date 进来是最近更新, 按 date 取数', async () => {
    const w = await mountAt('/search?view=date')

    expect(w.find('h1').text()).toBe('最近更新')
    expect(activeLabels(w)).toEqual(['最近更新'])
    expect(getRanking).toHaveBeenCalledWith('date', 60)
  })

  it('点「最近更新」: URL 写上 view=date, 只发一次请求', async () => {
    const w = await mountAt('/search')
    expect(getRanking).toHaveBeenCalledTimes(1)

    await btn(w, '最近更新').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.view).toBe('date')
    expect(w.find('h1').text()).toBe('最近更新')
    expect(activeLabels(w)).toEqual(['最近更新'])
    // 列表由 watch(route.query.view) 去重载 —— setView 里再自己调一次就会发两个
    expect(getRanking).toHaveBeenCalledTimes(2)
    expect(getRanking).toHaveBeenLastCalledWith('date', 60)
  })

  it('点「热门排行」: view 从 URL 里去掉(默认值不写进 URL), 回到 rank', async () => {
    const w = await mountAt('/search?view=date')

    await btn(w, '热门排行').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.view).toBeUndefined()
    expect(w.find('h1').text()).toBe('热门排行')
    expect(getRanking).toHaveBeenLastCalledWith('rank', 60)
  })

  it('点已经选中的那个不发请求', async () => {
    const w = await mountAt('/search')
    expect(getRanking).toHaveBeenCalledTimes(1)

    await btn(w, '热门排行').trigger('click')
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(1)
  })

  it('搜索态不显示切换 —— 那会儿列表里是搜索结果, 与排行/最新无关', async () => {
    const w = await mountAt('/search')
    expect(w.find('.view-switch').exists()).toBe(true)

    await w.find('.search-input').setValue('测试')
    await w.find('.search-btn').trigger('click')
    await flushPromises()

    expect(w.find('h1').text()).toBe('搜索结果')
    expect(w.find('.view-switch').exists()).toBe(false)
  })

  it('切换排序不会把搜索关键词弄丢(它只是往 query 里加一项)', async () => {
    const w = await mountAt('/search?view=date')
    await w.find('.search-input').setValue('测试')
    await w.find('.search-btn').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.q).toBe('测试')
    expect(router.currentRoute.value.query.view).toBe('date')
  })
})
