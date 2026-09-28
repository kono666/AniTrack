import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getRanking: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getCalendar: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getTags: vi.fn(() => Promise.resolve({ data: { data: [] } })),
  getByTag: vi.fn(() => Promise.resolve({ data: { data: [] } })),
}))

import Home from '../Home.vue'
import { getRanking } from '../../api'
import { resetHomeCache } from '../../utils/homeCache'

/**
 * 首页缓存的 TTL 只有在缓存对象**活得比组件实例久**的时候才谈得上生效.
 *
 * 所以这里不是断言"缓存了", 而是断言"卸载再挂载之后不再发请求" —— 改前缓存
 * 写在 <script setup> 里, 每个实例一份, 卸载即丢, 这个用例会红.
 */

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div />' } }],
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

describe('首页缓存', () => {
  beforeEach(() => {
    resetHomeCache()
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    getRanking.mockResolvedValue({ data: { data: [] } })
  })

  it('第二次进入首页直接用缓存, 不再发请求', async () => {
    const first = mountHome()
    await flushPromises()
    // 核心数据是两个排行接口(rank + date)
    expect(getRanking).toHaveBeenCalledTimes(2)
    first.unmount()

    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(2)
    second.unmount()
  })

  it('过了 5 分钟 TTL 就重新请求', async () => {
    // 只伪造 Date, 不伪造定时器 —— flushPromises 自己要用定时器
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-09-29T10:00:00Z'))

    const first = mountHome()
    await flushPromises()
    first.unmount()
    expect(getRanking).toHaveBeenCalledTimes(2)

    vi.setSystemTime(new Date('2026-09-29T10:05:01Z'))
    const second = mountHome()
    await flushPromises()

    expect(getRanking).toHaveBeenCalledTimes(4)
    second.unmount()
    vi.useRealTimers()
  })

  it('加载失败不写缓存: 再进来会真的重发请求, 并显示错误态', async () => {
    getRanking.mockRejectedValueOnce(new Error('boom'))

    const first = mountHome()
    await flushPromises()

    expect(first.text()).toContain('加载首页失败')
    first.unmount()

    const second = mountHome()
    await flushPromises()

    // 失败的那次没写缓存, 所以还会有新请求发出去(而不是命中一份空缓存)
    expect(getRanking.mock.calls.length).toBeGreaterThan(2)
    second.unmount()
  })
})
