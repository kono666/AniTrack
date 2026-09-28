import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  searchAnime: vi.fn(),
  getRanking: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  SEARCH_PAGE_SIZE: 20,
}))

import Search from '../Search.vue'
import { searchAnime } from '../../api'

/**
 * 后端 /bangumi/search 本来就吃 page/limit 并回 total(批次 1.3 加的),
 * 缺的一直是前端: 改前只请求第 1 页, 而结果上方写着「共找到 N 个结果」——
 * 用户看得见总数, 却翻不到第 21 条.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/search', component: { template: '<div />' } }],
})

function resultPage(count, startId = 1) {
  return {
    data: {
      data: {
        list: Array.from({ length: count }, (_, i) => ({ id: startId + i, nameCn: `番剧${startId + i}` })),
        total: 45,
      },
    },
  }
}

async function mountSearch(query = '测试') {
  await router.push({ path: '/search', query: { q: query } })
  await router.isReady()
  const wrapper = mount(Search, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('搜索结果分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    searchAnime.mockResolvedValue(resultPage(20))
  })

  it('第一次搜索请求的是第 1 页', async () => {
    await mountSearch()
    expect(searchAnime).toHaveBeenCalledWith('测试', 1)
  })

  it('总数超过一页时给出分页, 点第 2 页就去取第 2 页', async () => {
    const wrapper = await mountSearch()

    const pagination = wrapper.find('.pagination')
    expect(pagination.exists()).toBe(true)

    searchAnime.mockResolvedValue(resultPage(20, 21))
    const page2 = wrapper.findAll('.pg-btn').find(b => b.text() === '2')
    expect(page2).toBeTruthy()
    await page2.trigger('click')
    await flushPromises()

    expect(searchAnime).toHaveBeenLastCalledWith('测试', 2)
    // 换的是整页内容, 不是追加
    expect(wrapper.text()).toContain('番剧21')
    expect(wrapper.text()).not.toContain('番剧1')   // 第 1 页的内容已经换掉了
    // 页码高亮跟着走
    expect(wrapper.find('.pg-btn.active').text()).toBe('2')
  })

  it('结果不到一页时不显示分页', async () => {
    searchAnime.mockResolvedValue({ data: { data: { list: [{ id: 1, nameCn: '番剧1' }], total: 5 } } })
    const wrapper = await mountSearch()

    expect(wrapper.find('.pagination').exists()).toBe(false)
  })

  it('翻页失败后点重试, 重试的是当前那一页而不是第 1 页', async () => {
    const wrapper = await mountSearch()
    searchAnime.mockResolvedValue(resultPage(20, 21))
    await wrapper.findAll('.pg-btn').find(b => b.text() === '2').trigger('click')
    await flushPromises()

    searchAnime.mockRejectedValueOnce(new Error('boom'))
    await wrapper.findAll('.pg-btn').find(b => b.text() === '3').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('搜索失败')

    searchAnime.mockResolvedValue(resultPage(5, 41))
    await wrapper.find('.empty-state .action-btn').trigger('click')
    await flushPromises()

    // 失败的这一次是第 3 页, 重试也应该重试第 3 页
    expect(searchAnime).toHaveBeenLastCalledWith('测试', 3)
  })
})
