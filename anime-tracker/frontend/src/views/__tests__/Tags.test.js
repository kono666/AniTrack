import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../api', () => ({
  getTags: vi.fn(),
  getFiltered: vi.fn(),
}))

import Tags from '../Tags.vue'
import { getTags, getFiltered } from '../../api'

/**
 * 分类页: 标签墙 → 选中就地看结果.
 *
 * 这一块原先长在首页最底部, 现在整块搬成了独立页. 下面第一组用例是从
 * Home.tagUrl.test.js **逐字搬过来**的 6 条 URL 契约 + 1 条竞态 —— 断言里的
 * .tag-chip / .pg-btn / getFiltered 的参数一个字都没动. 留着它们的原样本身就是
 * 价值: 它证明这次搬家没有顺手把筛选口径改掉.
 *
 * ⚠️ 每个用例自己卸载组件(不用 afterEach 收集): 这一组要数"发了几次请求"、
 * 还要用 mockReturnValueOnce 排定谁先回来, 而残留组件的 route watcher 会跟着
 * 路由动, 把排好的返回值吃掉.
 */
const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/tags', component: { template: '<div />' } },
    { path: '/anime/:id', component: { template: '<div />' } },
  ],
})

// Tags 用 v-reveal (IntersectionObserver), jsdom 没有实现它
class FakeIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function mountTags() {
  return mount(Tags, { global: { plugins: [router] } })
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

describe('分类页: 筛选进 URL', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    // jsdom 里 Element.prototype.scrollIntoView 是 **undefined**(不是 no-op):
    // 不打桩的话点标签会直接 TypeError, 而它会被组件的 catch 吞掉, 变成
    // "看着绿其实什么都没发生"的假绿
    Element.prototype.scrollIntoView = vi.fn()
    getTags.mockResolvedValue({
      data: { data: [{ name: '治愈', count: 3 }, { name: '热血', count: 5 }] },
    })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('首访(URL 里没有 ?tag=): 只有标签墙, 一个 /filter 都不发', async () => {
    const wrapper = mountTags()
    await flushPromises()

    // 这一页首先是一堵标签墙 —— 结果要用户点出来. 改前它在首页, 首访时
    // 必须自己加载(否则那一块只剩标题和一排 chip); 搬到独立页之后"什么都不选"
    // 是一个正常的落地状态, 不该顺手发一次全站浏览的请求
    expect(getFiltered).not.toHaveBeenCalled()
    expect(wrapper.findAll('.tag-chip')).toHaveLength(3)  // 全部 + 治愈 + 热血
    expect(wrapper.text()).not.toContain('番剧1')
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('点分类标签: 筛选进 URL, 而且只发一次请求', async () => {
    const wrapper = mountTags()
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
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('点「全部」把 tag 从 URL 上摘掉, 而结果区**不消失**', async () => {
    await router.push('/tags?tag=治愈')
    const wrapper = mountTags()
    await flushPromises()
    expect(activeChip(wrapper)).toContain('治愈')

    await wrapper.findAll('.tag-chip')[0].trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.tag).toBeUndefined()
    expect(activeChip(wrapper)).toBe('全部')
    expect(getFiltered).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    // 「还没选过」与「选了全部」写出来是同一条 URL(/tags). 结果区要是跟着
    // URL 走, 点「全部」的那一瞬间它就会消失 —— 用户自己把自己的动作撤掉了
    expect(wrapper.find('.tag-results').exists()).toBe(true)
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('带 ?tag=&page= 进来: 首访就按它取数, 对应 chip 也是亮的', async () => {
    // 这一条同时覆盖两个入口: 分享/收藏的链接直接打开, 以及浏览器刷新
    await router.push('/tags?tag=热血&page=3')
    const wrapper = mountTags()
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '热血', page: 3 }))
    expect(activeChip(wrapper)).toContain('热血')

    wrapper.unmount()
  })

  it('同路径的 query 变化(后退/前进)会被读回来并取那一页', async () => {
    const wrapper = mountTags()
    await flushPromises()
    getFiltered.mockClear()

    await router.push('/tags?tag=治愈&page=2')
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(getFiltered).toHaveBeenCalledWith(expect.objectContaining({ tag: '治愈', page: 2 }))
    expect(activeChip(wrapper)).toContain('治愈')

    wrapper.unmount()
  })

  it('翻页把页码写进 URL, 第 1 页不写', async () => {
    await router.push('/tags?tag=治愈')
    const wrapper = mountTags()
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

describe('分类页: 竞态', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getTags.mockResolvedValue({ data: { data: [{ name: '治愈', count: 3 }] } })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags?tag=治愈')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('先发的那次后到, 不能盖掉后发的结果', async () => {
    const wrapper = mountTags()
    await flushPromises()

    const slow = deferred()
    const fast = deferred()
    getFiltered.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    await router.push('/tags?tag=治愈&page=2')
    await flushPromises()
    await router.push('/tags?tag=治愈&page=3')
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

  it('过期那一份先回来时转圈不许停 —— 关它的只能是最后一次', async () => {
    const wrapper = mountTags()
    await flushPromises()

    const stale = deferred()
    const fresh = deferred()
    getFiltered.mockReturnValueOnce(stale.promise).mockReturnValueOnce(fresh.promise)

    await router.push('/tags?tag=治愈&page=2')
    await flushPromises()
    await router.push('/tags?tag=治愈&page=3')
    await flushPromises()

    // **先发的先回来**, 而它已经过期 —— 这个顺序是这条用例的全部意义.
    // 过期分支里若顺手写了 tagLoading.value = false, 转圈就在这一刻灭了,
    // 而真正该关它的那次还在飞: 界面从此停在"没有转圈、也还是旧内容"的样子.
    // (反过来先 resolve fresh 的话, 两次都是写 false, 什么都看不出来.)
    stale.resolve(filteredPage([card(22)]))
    await flushPromises()
    expect(wrapper.text()).not.toContain('番剧22')          // 过期结果没写进列表
    expect(wrapper.find('.loading').exists()).toBe(true)     // 转圈还在

    fresh.resolve(filteredPage([card(33)]))
    await flushPromises()
    expect(wrapper.text()).toContain('番剧33')
    expect(wrapper.find('.loading').exists()).toBe(false)    // 这次才是收工

    wrapper.unmount()
  })
})

describe('分类页: 选中后把结果送去视野', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getTags.mockResolvedValue({ data: { data: [{ name: '治愈', count: 3 }] } })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('点标签后滚一次, 用 nearest', async () => {
    const wrapper = mountTags()
    await flushPromises()

    await wrapper.findAll('.tag-chip')[1].trigger('click')
    await flushPromises()

    // block 必须是 nearest: 这一页的墙只有 4 行左右, 结果区本来就露在墙下面,
    // 'start' 会把标签墙顶出视野 —— 换标签还得滚回来, 那是把一个毛病换成另一个
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledTimes(1)
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })

    wrapper.unmount()
  })

  it('翻页不滚 —— 用户点的就是分页控件, 界面不该在脚下移动', async () => {
    await router.push('/tags?tag=治愈')
    const wrapper = mountTags()
    await flushPromises()
    Element.prototype.scrollIntoView.mockClear()

    const page2 = wrapper.findAll('.pg-btn').find(b => b.text() === '2')
    await page2.trigger('click')
    await flushPromises()

    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('带 ?tag= 进来时不滚 —— 刚导航过来的页面不该自己动', async () => {
    await router.push('/tags?tag=治愈')
    const wrapper = mountTags()
    await flushPromises()

    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('分类页: 两块内容各自的错误态', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
    Element.prototype.scrollIntoView = vi.fn()
    getTags.mockResolvedValue({ data: { data: [{ name: '治愈', count: 3 }] } })
    getFiltered.mockResolvedValue(filteredPage([card(1)]))

    await router.push('/tags?tag=治愈')
    await router.isReady()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('/filter 挂了: 出错条 + 重试, 而标签墙还在', async () => {
    getFiltered.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.text()).toContain('加载分类失败')
    // 标签墙不该被结果区的错误带走 —— 它是这一页的另一半
    expect(wrapper.findAll('.tag-chip')).toHaveLength(2)
    expect(wrapper.findAll('.tag-retry')).toHaveLength(1)

    wrapper.unmount()
  })

  it('重试只重发 /filter, 不重发 /tags', async () => {
    getFiltered.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()
    getTags.mockClear()
    getFiltered.mockClear()

    await wrapper.find('.tag-retry').trigger('click')
    await flushPromises()

    expect(getFiltered).toHaveBeenCalledTimes(1)
    expect(getTags).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('/tags 挂了: 标签墙出错条, 而结果区照常出卡片', async () => {
    getTags.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountTags()
    await flushPromises()

    expect(wrapper.text()).toContain('加载标签失败')
    // 结果区不依赖 tags 列表 —— 「治愈」只是原样传给 /filter 的字符串
    expect(wrapper.text()).toContain('番剧1')

    wrapper.unmount()
  })

  it('结果为空时给一句空态, 「全部」也不例外', async () => {
    // 首页那个写法是 v-else-if="selectedTag": 「全部 + 0 条」什么都不显示.
    // 在这一页「全部」是用户明确点出来的动作, 什么都不显示会让人以为没加载
    getFiltered.mockResolvedValue(filteredPage([], 0))
    const wrapper = mountTags()
    await flushPromises()

    await wrapper.findAll('.tag-chip')[0].trigger('click')
    await flushPromises()

    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('站里还没有可浏览的作品')

    wrapper.unmount()
  })

  it('空态文案会带上标签名', async () => {
    getFiltered.mockResolvedValue(filteredPage([], 0))
    const wrapper = mountTags()
    await flushPromises()

    await wrapper.findAll('.tag-chip')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('「治愈」暂无作品')

    wrapper.unmount()
  })
})
